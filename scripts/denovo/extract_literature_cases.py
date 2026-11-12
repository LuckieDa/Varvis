"""Extract source-native case table fields; never infer clinical qualification.

No paper, gene, disease or variant allowlist. Both row and column case layouts are
supported with explicit case labels. Ambiguous identities and tables remain issues.
Free narrative is retained as an extraction task, not silently converted to facts.
"""
import argparse
import json
from pathlib import Path
import re
from prepare_literature import prepare, MAX_BYTES

VERSION = "denovo-case-table-extraction-1.1.0"
CASE_LABEL = re.compile(r"(?:case|patient|individual|proband)\s+([A-Za-z0-9][A-Za-z0-9_.-]{0,63})", re.I)
FIELD_NAMES = {
    "gene": "geneSymbol", "mutated gene": "geneSymbol", "gene symbol": "geneSymbol",
    "variant": "variantText", "mutation": "variantText", "nucleotide": "variantText",
    "dna change": "variantText", "nucleotide change": "variantText", "cdna change": "variantText",
    "hgvs c": "variantText", "hgvs c.": "variantText", "transcript": "transcript",
    "transcript id": "transcript", "refseq": "transcript", "diagnosis": "reportedDiagnosis",
    "clinical diagnosis": "reportedDiagnosis", "disease": "reportedDiagnosis",
    "inheritance": "reportedInheritance", "inheritance pattern": "reportedInheritance",
    "parentage": "reportedParentage", "paternity": "reportedPaternity", "maternity": "reportedMaternity",
    "father genotype": "reportedFatherGenotype", "mother genotype": "reportedMotherGenotype",
    "paternal genotype": "reportedFatherGenotype", "maternal genotype": "reportedMotherGenotype",
    "family": "reportedFamilyId", "family id": "reportedFamilyId", "family history": "reportedFamilyHistory",
    "phenotype": "reportedPhenotype", "phenotypes": "reportedPhenotype", "clinical features": "reportedPhenotype",
    "main clinical features": "reportedPhenotype", "protein": "proteinChange", "protein change": "proteinChange",
}
CASE_HEADERS = {"case", "case id", "patient", "patient id", "individual", "individual id", "proband"}
MAX_ROWS, MAX_COLUMNS, MAX_CASES = 1000, 200, 1000


def normalized(value):
    return " ".join(value.lower().strip().rstrip(":").split())


def table_grid(table):
    """Expand layout by reference to original cells, preserving merged-cell origins."""
    rows = table["rows"]
    if len(rows) > MAX_ROWS:
        raise ValueError("LITERATURE_TABLE_ROW_LIMIT")
    grid = [{} for unused in rows]
    for r, row in enumerate(rows):
        column = 0
        for cell in row["cells"]:
            while column in grid[r]:
                column += 1
            rs, cs = cell["rowspan"], cell["colspan"]
            if r + rs > len(rows) or column + cs > MAX_COLUMNS:
                raise ValueError("LITERATURE_TABLE_SPAN_OUTSIDE_GRID")
            for rr in range(r, r + rs):
                for cc in range(column, column + cs):
                    if cc in grid[rr]:
                        raise ValueError("LITERATURE_TABLE_SPAN_OVERLAP")
                    grid[rr][cc] = cell
            column += cs
    width = max((max(row, default=-1) + 1 for row in grid), default=0)
    return [[row.get(c) for c in range(width)] for row in grid]


def cell_text(cell):
    return cell["text"] if cell is not None else ""


def citation(document, cell):
    value = cell["text"]
    if not value.strip():
        return None
    units = {u["unitId"]: u for u in document["units"]}
    unit_id = cell.get("unitId")
    if unit_id not in units or units[unit_id]["text"] != value:
        raise ValueError("LITERATURE_TABLE_UNIT_MISMATCH")
    return {"sourceSha256": document["sourceSha256"], "unitId": unit_id,
            "start": 0, "end": len(value), "quote": value}


def finding_state(value):
    value = normalized(value)
    if not value or value in {"unknown", "not reported", "not available", "n/a", "na", "-", "?"}:
        return "UNKNOWN"
    if value in {"not applicable", "none found", "under investigation"}:
        return "NOT_APPLICABLE_OR_UNRESOLVED"
    if value in {"not tested", "not performed"}:
        return "NOT_TESTED"
    return "REPORTED"


def extract(document):
    if document.get("schema") not in {"denovo-jats-source-1.1.0", "denovo-jats-source-1.2.0"}:
        raise ValueError("LITERATURE_PREPARED_VERSION_UNSUPPORTED")
    cases, issues = {}, []
    for table in document["tables"]:
        table_id = table["tableId"]
        try:
            grid = table_grid(table)
        except ValueError as error:
            issues.append({"tableId": table_id, "reasonCode": str(error)})
            continue
        column_headers, row_headers = [], []
        for r, row in enumerate(grid):
            columns = [(c, CASE_LABEL.fullmatch(cell_text(cell))) for c, cell in enumerate(row)]
            matches = [(c, m.group(1)) for c, m in columns if c > 0 and m is not None]
            if len(matches) >= 1 and len(row) > 1 and normalized(cell_text(row[0])) not in FIELD_NAMES:
                column_headers.append((r, matches))
            columns = [c for c, cell in enumerate(row) if normalized(cell_text(cell)) in CASE_HEADERS]
            if len(columns) == 1 and any(normalized(cell_text(cell)) in FIELD_NAMES for cell in row):
                row_headers.append((r, columns[0]))
        if len(row_headers) == 1:
            column_headers = [item for item in column_headers if item[0] <= row_headers[0][0]]
        if len(column_headers) + len(row_headers) != 1:
            issues.append({"tableId": table_id, "reasonCode": "LITERATURE_CASE_LAYOUT_UNRESOLVED"})
            continue
        entries = []
        if column_headers:
            header_row, case_columns = column_headers[0]
            labels = [v for unused, v in case_columns]
            if len(labels) != len(set(labels)):
                issues.append({"tableId": table_id, "reasonCode": "LITERATURE_CASE_ID_AMBIGUOUS"})
                continue
            for column, label in case_columns:
                fields = []
                for r in range(header_row + 1, len(grid)):
                    field = FIELD_NAMES.get(normalized(cell_text(grid[r][0])))
                    if field and grid[r][column] is not None:
                        fields.append((field, grid[r][column], grid[r][0]))
                entries.append((label, grid[header_row][column], fields))
        else:
            header_row, case_column = row_headers[0]
            fields = [(c, FIELD_NAMES[normalized(cell_text(cell))], cell) for c, cell in enumerate(grid[header_row])
                      if normalized(cell_text(cell)) in FIELD_NAMES]
            labels = []
            for row in grid[header_row + 1:]:
                label = cell_text(row[case_column])
                match = CASE_LABEL.fullmatch(label)
                label = match.group(1) if match else label
                if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", label):
                    issues.append({"tableId": table_id, "reasonCode": "LITERATURE_CASE_ID_UNRESOLVED"})
                    continue
                labels.append(label)
                entries.append((label, row[case_column], [(field, row[c], header) for c, field, header in fields if row[c] is not None]))
            if len(labels) != len(set(labels)):
                issues.append({"tableId": table_id, "reasonCode": "LITERATURE_CASE_ID_AMBIGUOUS"})
                continue
        for label, case_cell, fields in entries:
            # Same paper-local label may refer to different cohorts/tables. Do not
            # silently merge; retain table identity until explicit equivalence is known.
            key = table_id + "#case=" + label
            if key not in cases:
                if len(cases) >= MAX_CASES:
                    raise ValueError("LITERATURE_CASE_LIMIT")
                cases[key] = {"observationId": document["pmcid"] + key, "sourceKind": "LITERATURE_CASE",
                              "caseLabel": label, "tableId": table_id, "caseCitation": citation(document, case_cell),
                              "qualification": "EXTRACTED_NOT_QUALIFIED", "independence": "NOT_ASSESSED",
                              "sourceClass": "PUBLIC_PAPER", "cohortRelation": "NOT_ASSESSED",
                              "fields": [], "issues": ["LITERATURE_CASE_VARIANT_IDENTITY_NOT_RESOLVED",
                                                        "LITERATURE_PARENTAL_TESTS_NOT_QUALIFIED",
                                                        "LITERATURE_PHENOTYPE_NOT_QUALIFIED"]}
            case = cases[key]
            for field, cell, header in fields:
                value = cell["text"]
                cite = citation(document, cell)
                entry = {"field": field, "sourceValue": value, "findingState": finding_state(value),
                         "citation": cite, "fieldHeaderCitation": citation(document, header)}
                previous = [e for e in case["fields"] if e["field"] == field]
                if any(e["sourceValue"] != value for e in previous):
                    case["issues"].append("LITERATURE_FIELD_CONFLICT:" + field)
                case["fields"].append(entry)
    return {"schemaVersion": "denovo-literature-case-candidates-v1", "methodVersion": VERSION,
            "sourcePreparationVersion": document["schema"], "pmcid": document["pmcid"],
            "sourceSha256": document["sourceSha256"], "cases": list(cases.values()), "issues": issues,
            "modelStatus": "NOT_USED_DETERMINISTIC_TABLE_ONLY", "modelCalls": 0, "clinicalAdoption": "NONE",
            "narrativeExtraction": "PENDING_MODEL_OR_VERIFIED_STRUCTURED_SOURCE",
            "remainingUnits": [u["unitId"] for u in document["units"] if u["kind"] in {"BODY_PARAGRAPH", "CAPTION"}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--pmcid", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    with args.input.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    result = extract(prepare(raw, args.pmcid, args.sha256))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"pmcid": result["pmcid"], "cases": len(result["cases"]),
                      "issues": result["issues"], "modelCalls": 0}))


if __name__ == "__main__":
    main()
