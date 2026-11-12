"""Bounded, explicit-language source assertions for individually identified cases.

This is a deterministic source reader, not an LLM substitute or a clinical approval.
Only supported affirmative sentences in one unique case-owned results section are
recognized. Unsupported prose stays visible; a de-novo label alone gives no facts.
The later evaluator must check phenotype, independence, reference and policy scope.
"""
import argparse
import json
from pathlib import Path
import re
from prepare_literature import prepare, MAX_BYTES
from extract_literature_cases import extract
from literature_allele_identity import (read_record, resolve, one_field, coding, protein,
                                       MAX_RECORD_BYTES, VERSION as ALLELE_VERSION)

VERSION = "denovo-case-source-assertions-1.2.0"
FIG = r"(?: \(Fig\. [0-9]+[A-Za-z]?\))?"
PARENTAGE_SENTENCES = [
    ("TRIO_SNP_AND_SANGER_CONSISTENT_PARENTAGE",
     re.compile(r"Bi-directional Sanger sequencing confirmed the next-generation sequencing findings" + FIG
                + r" and analyses of other rare SNPs in the proband and (?:his|her|their) parents were consistent with the stated parentage"
                + FIG + r", confirming that the mutation arose de novo in the patient\.")),
    ("EXPLICIT_MATERNITY_PATERNITY_SNP_OR_STR",
     re.compile(r"(?:SNP|STR) (?:analysis|testing) confirmed both maternity and paternity\.")),
]
TRIO_SENTENCES = [
    ("SANGER_TRIO_HETEROZYGOUS_DENOVO",
     re.compile(r"(?:\([A-Z]\) )?Sanger sequencing of the (?:patient|proband) and (?:his |her |their )?parents to show that the heterozygous (?:mutation|variant) is de novo\.")),
    ("SANGER_EXPLICIT_TRIO_TARGET_RESULTS",
     re.compile(r"Sanger sequencing confirmed the heterozygous (?:mutation|variant) in the proband and its absence in both parents\.")),
]
ASSUMED_SENTENCES = [
    ("EXPLICIT_PARENTAGE_NOT_TESTED", re.compile(r"(?:Parentage (?:testing )?was not (?:tested|performed)|No parentage testing was performed)\.")),
    ("EXPLICIT_PARENTAGE_NOT_REPORTED", re.compile(r"Parentage (?:testing )?was not reported\.")),
]
FAMILY_SENTENCES = [
    ("EXPLICIT_HEALTHY_PARENTS_AND_NO_HISTORY", re.compile(
        r"(?:He|She|The proband|The patient) is the (?:first|second|third|fourth) child of healthy non-consanguineous parents, with no family history of [A-Za-z -]+\.")),
    ("EXPLICIT_NO_SIMILAR_HISTORY", re.compile(r"There was no family history of (?:similar disease|similar symptoms)\.")),
]
CRITICAL_CONTEXT = re.compile(r"\b(?:parents?|mother|father|paternity|maternity|parentage|siblings?|brother|sister|inherited|inheritance|mosaicism|mosaic|de novo|family history|contamination|inconclusive|contradicted)\b", re.I)
CASE_TITLE = re.compile(r"(?:Case|Patient|Individual|Proband) ([A-Za-z0-9][A-Za-z0-9_.-]{0,63})(?:: .+)?", re.I)
GROUP = r"(?:Cases|Patients|Individuals) ([1-9][0-9]*(?:[–-][1-9][0-9]*|(?:, ?[1-9][0-9]*)*(?: and [1-9][0-9]*)?))"
GROUP_PARENTAGE = re.compile(r"The (?:mutations|variants) identified in " + GROUP
    + r" were de novo, with parentage confirmed using (?:SNP|STR) analysis(?: \([^()]{1,150}\))?\.")
CAPTION_SUMMARY = re.compile(r"A (?:novel )?de novo (?:mutation|variant) predicted to affect ([A-Za-z0-9][A-Za-z0-9 ()/.-]{0,120})\.")


def group_labels(value):
    """Literal bounded identifiers; never infer 'all', family membership or ranges across letters."""
    if re.fullmatch(r"[1-9][0-9]*[–-][1-9][0-9]*", value):
        a, b = map(int, re.split("[–-]", value))
        return {str(i) for i in range(a, b + 1)} if 0 < a <= b <= 40 else set()
    values = re.split(r", ?| and ", value)
    return set(values) if len(values) <= 40 and all(re.fullmatch(r"[1-9][0-9]*", v) and int(v) <= 40 for v in values) else set()


def shared_statements(document, label):
    """Return explicitly scoped result sentences, not a paragraph's implied subject."""
    result = []
    for u in document["units"]:
        if u["kind"] != "BODY_PARAGRAPH" or not any(t.lower() in {"results", "case reports", "clinical report"} for t in u["sections"]):
            continue
        if u["sections"] and CASE_TITLE.fullmatch(u["sections"][-1]):
            continue
        for start, end, sentence in sentences(u["text"]):
            groups = list(re.finditer(GROUP, sentence))
            if len(groups) == 1 and label in group_labels(groups[0][1]):
                result.append((u, start, end, sentence))
    return result


def shared_field_matches(document, label, field):
    for u, start, end, sentence in shared_statements(document, label):
        if u["unitId"] != field["citation"]["unitId"] or field["citation"]["quote"] not in sentence:
            continue
        if GROUP_PARENTAGE.fullmatch(sentence) and field["field"] in {"reportedParentage", "reportedInheritance"}:
            return True
        diagnosis = re.match(GROUP + r" (?:were|was) diagnosed with (?P<diagnosis>[A-Za-z][A-Za-z -]{1,120}?)(?: based on|[,.])", sentence)
        if field["field"] == "reportedDiagnosis" and diagnosis:
            if field["sourceValue"] in diagnosis["diagnosis"] and not re.search(r"\b(?:not|no|uncertain|possible|except)\b", diagnosis["diagnosis"], re.I):
                return True
    return False


def descriptive_caption(unit, start, sentence):
    # A functional prediction heading contributes no family fact. Its presence
    # need not invalidate separate, explicit trio/parentage source sentences.
    match = CAPTION_SUMMARY.fullmatch(sentence)
    return (unit["kind"] == "CAPTION" and start == 0 and match is not None
            and not CRITICAL_CONTEXT.search(match[1])
            and not re.search(r"\b(?:no|not|never|without|uncertain|possible)\b", match[1], re.I))


def sentences(text):
    # Do not split HGVS or Fig. 2. Unrecognized prose remains unqualified.
    start = 0
    for boundary in re.finditer(r"(?<=[.!?])\s+(?=(?:\([A-Z]\) )?[A-Z])", text):
        yield start, boundary.start(), text[start:boundary.start()]
        start = boundary.end()
    if start < len(text):
        yield start, len(text), text[start:]


def cite(document, unit, start=0, end=None):
    end = len(unit["text"]) if end is None else end
    return {"sourceSha256": document["sourceSha256"], "unitId": unit["unitId"],
            "start": start, "end": end, "quote": unit["text"][start:end]}


def matches(document, units, grammars):
    found = []
    for unit in units:
        for start, end, sentence in sentences(unit["text"]):
            for rule, pattern in grammars:
                if pattern.fullmatch(sentence):
                    found.append({"rule": rule, "citation": cite(document, unit, start, end)})
    return found


def source_case(document, candidates, case, allele_records):
    result = {"observationId": case["observationId"], "caseLabel": case["caseLabel"],
              "sourceClass": "PUBLIC_PAPER", "sourceKind": "LITERATURE_CASE",
              "caseCitation": case["caseCitation"], "fields": case["fields"],
              "qualification": "SOURCE_ASSERTIONS_ONLY_NOT_CLINICALLY_QUALIFIED",
              "independence": "NOT_ASSESSED", "phenotypeApplicability": "NOT_ASSESSED",
              "targetTesting": {"status": "UNKNOWN", "citations": []},
              "parentage": {"status": "UNKNOWN", "citations": []},
              "familyHistory": "UNKNOWN", "issues": [],
              "pendingChecks": ["LITERATURE_REFERENCE_IDENTITY_NOT_CHECKED", "LITERATURE_PHENOTYPE_APPLICABILITY_NOT_ASSESSED",
                                "LITERATURE_CASE_INDEPENDENCE_NOT_ASSESSED", "LITERATURE_SCIENTIFIC_POLICY_NOT_APPLIED"]}
    result["alleleIdentity"] = resolve(case, document["identifiers"].get("pmid"), allele_records)
    result["issues"].extend(result["alleleIdentity"]["issues"])
    result["issues"].extend(i for i in case["issues"] if i.startswith("LITERATURE_FIELD_CONFLICT:"))
    label = case["caseLabel"]
    if sum(c["caseLabel"] == label for c in candidates) != 1:
        result["issues"].append("LITERATURE_CASE_COHORT_AMBIGUOUS")
        return result
    sections = {}
    for unit in document["units"]:
        titles = unit["sections"]
        if unit["kind"] not in {"BODY_PARAGRAPH", "CAPTION"} or not titles:
            continue
        title = CASE_TITLE.fullmatch(titles[-1])
        if not title or title[1] != label or not any(t.lower() in {"results", "case reports", "case report", "clinical report"} for t in titles[:-1]):
            continue
        path = unit["unitId"][:unit["unitId"].rfind("/sec[")]
        # Include the last sec index, but not its child p/fig path.
        last = unit["unitId"][len(path):].split("/", 2)[1]
        section = path + "/" + last
        sections.setdefault(section, []).append(unit)
    if len(sections) != 1:
        result["issues"].append("LITERATURE_PRIMARY_CASE_SECTION_UNRESOLVED")
        return result
    section, units = next(iter(sections.items()))
    result["caseSection"] = section
    # A section heading and a table number alone do not establish the target of an
    # assay. Require the complete paired target in that case's own narrative.
    try:
        gene = one_field(case, "geneSymbol")
        unused_tx, nt = coding(one_field(case, "variantText"))
        aa = protein(one_field(case, "proteinChange"))
    except ValueError as error:
        result["issues"].append(str(error))
        return result
    anchors, all_changes = [], set()
    for unit in units:
        if unit["kind"] != "BODY_PARAGRAPH":
            continue
        value = unit["text"]
        c_changes = set(re.findall(r"\bc\.[0-9]+(?:_[0-9]+)?(?:[ACGT]>[ACGT]|delins[ACGT]+|del[ACGT]*|dup[ACGT]*|ins[ACGT]+)", value))
        all_changes.update(c_changes)
        proteins = {protein(p) for p in re.findall(r"\bp\.\s*([A-Za-z*]+[0-9]+[A-Za-z*]+)", value)}
        if (re.search(r"(?<![A-Za-z0-9_])" + re.escape(gene) + r"(?![A-Za-z0-9_])", value)
                and nt in c_changes and aa in proteins):
            if c_changes != {nt} or proteins != {aa}:
                result["issues"].append("LITERATURE_CASE_MULTIPLE_TARGETS_UNRESOLVED")
            anchors.append(cite(document, unit))
    if not anchors:
        result["issues"].append("LITERATURE_CASE_TARGET_LINK_UNRESOLVED")
        return result
    result["targetAnchors"] = anchors
    if all_changes != {nt}:
        result["issues"].append("LITERATURE_CASE_MULTIPLE_TARGETS_UNRESOLVED")
    # Retain uncertainty rather than cherry-pick a positive sentence from the case.
    # This is a conservative language-review flag, not a claim of mosaicism.
    concerns = []
    for unit in units:
        if re.search(r"\bmosaic(?:ism)?\b|\b(?:inconclusive|excluded|unconfirmed)\s+(?:parentage|paternity|maternity)\b"
                     r"|\b(?:parentage|paternity|maternity)\s+(?:(?:was|were|could)\s+)?(?:excluded|inconclusive)\b"
                     r"|\b(?:variant|mutation)\s+(?:was\s+)?(?:inherited|detected in (?:the )?(?:mother|father))\b"
                     r"|\b(?:mother|father|parents)\s+(?:(?:was|were)\s+)?not\s+(?:tested|sequenced)\b"
                     r"|\b(?:affected|similarly affected)\s+(?:sibling|brother|sister)\b", unit["text"], re.I):
            concerns.append(cite(document, unit))
    if concerns:
        result["issues"].append("LITERATURE_SPECIAL_OR_CONFLICT_TEXT_NOT_AUTOMATICALLY_RESOLVED")
        result["contextConcerns"] = concerns
    shared = shared_statements(document, label)
    understood = PARENTAGE_SENTENCES + TRIO_SENTENCES + ASSUMED_SENTENCES + FAMILY_SENTENCES
    unsupported = []
    for unit in units:
        for start, end, sentence in sentences(unit["text"]):
            if (CRITICAL_CONTEXT.search(sentence) and not any(pattern.fullmatch(sentence) for unused, pattern in understood)
                    and not descriptive_caption(unit, start, sentence)):
                unsupported.append(cite(document, unit, start, end))
    # A shared affirmative sentence must not hide another scoped contradiction.
    for unit, start, end, sentence in shared:
        if CRITICAL_CONTEXT.search(sentence) and not GROUP_PARENTAGE.fullmatch(sentence):
            unsupported.append(cite(document, unit, start, end))
    result["contextVerification"] = "SUPPORTED_EXPLICIT_FAMILY_STATEMENTS" if not unsupported and not concerns else "UNRESOLVED"
    result["unresolvedContext"] = unsupported
    if unsupported or concerns:
        result["pendingChecks"].append("LITERATURE_UNRECOGNIZED_CLINICAL_NARRATIVE_NOT_VERIFIED")
    if concerns or any(i.startswith("LITERATURE_FIELD_CONFLICT:") or i == "LITERATURE_CASE_MULTIPLE_TARGETS_UNRESOLVED" for i in result["issues"]):
        return result
    # A figure in another paragraph may concern another variant; do not borrow it.
    anchor_paths = [a["unitId"] for a in anchors]
    assay_units = [u for u in units if u["unitId"] in anchor_paths or
                   any(u["unitId"].startswith(p + "/fig[") for p in anchor_paths)]
    trio = matches(document, assay_units, TRIO_SENTENCES)
    parentage = matches(document, [u for u in units if u["kind"] == "BODY_PARAGRAPH"], PARENTAGE_SENTENCES)
    parentage.extend({"rule": "EXPLICIT_CASE_GROUP_SNP_OR_STR_PARENTAGE", "citation": cite(document, u, start, end)}
                     for u, start, end, sentence in shared if GROUP_PARENTAGE.fullmatch(sentence))
    assumed = matches(document, [u for u in units if u["kind"] == "BODY_PARAGRAPH"], ASSUMED_SENTENCES)
    if trio:
        result["targetTesting"] = {"status": "PUBLISHED_TRIO_SANGER_DENOVO", "method": "SANGER",
                                   "proband": "PRESENT", "mother": "REPORTED_ABSENT", "father": "REPORTED_ABSENT",
                                   "citations": trio}
    else:
        result["issues"].append("LITERATURE_PARENTAL_TARGET_TESTS_NOT_RESOLVED")
    if parentage and assumed:
        result["parentage"] = {"status": "CONFLICT", "citations": parentage + assumed}
        result["issues"].append("LITERATURE_PARENTAGE_CONFLICT")
    elif parentage:
        result["parentage"] = {"status": "PUBLISHED_BOTH_PARENTS_CONFIRMED", "citations": parentage}
    elif assumed:
        statuses = {p["rule"] for p in assumed}
        if len(statuses) == 1:
            result["parentage"] = {"status": "PUBLISHED_PARENTAGE_NOT_TESTED" if "EXPLICIT_PARENTAGE_NOT_TESTED" in statuses else "PUBLISHED_PARENTAGE_NOT_REPORTED", "citations": assumed}
        else:
            result["issues"].append("LITERATURE_PARENTAGE_CONFLICT")
    else:
        result["issues"].append("LITERATURE_PARENTAGE_NOT_RESOLVED")
    history = [f for f in case["fields"] if f["field"] == "reportedFamilyHistory"]
    if history and all(f["sourceValue"].strip().lower() == "none" for f in history):
        result["familyHistory"] = "REPORTED_NONE"
    return result


def qualify(raw, pmcid, trusted_sha256, allele_sources=()):
    """Inputs are pinned originals, not caller/model-authored 'qualified' fields."""
    document = prepare(raw, pmcid, trusted_sha256)
    candidates = extract(document)
    if not candidates["cases"] and not candidates["issues"]:
        candidates["issues"].append({"reasonCode": "LITERATURE_NARRATIVE_EXTRACTION_NOT_SUPPORTED"})
    if len(allele_sources) > 100 or sum(len(s[0]) for s in allele_sources) > 16 * 1024 * 1024:
        raise ValueError("LITERATURE_ALLELE_INPUT_BUDGET")
    records = [read_record(*source) for source in allele_sources]
    return {"schemaVersion": "denovo-literature-source-assertions-v1", "methodVersion": VERSION,
            "sourcePreparationVersion": document["schema"], "alleleIdentityVersion": ALLELE_VERSION,
            "pmcid": document["pmcid"], "pmid": document["identifiers"].get("pmid"), "sourceSha256": trusted_sha256,
            "cases": [source_case(document, candidates["cases"], case, records) for case in candidates["cases"]],
            "issues": candidates["issues"], "modelCalls": 0, "modelStatus": "DEFERRED_NOT_CONFIGURED",
            "clinicalAdoption": "NONE", "evaluationStatus": "NOT_RUN_SOURCE_FACTS_ONLY",
            "unrecognizedNarrative": "PRESERVED_IN_PINNED_SOURCE_REQUIRES_SUPPORTED_READER_OR_MODEL_AND_VERIFICATION"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--pmcid", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--allele", action="append", nargs=3, metavar=("XML", "SHA256", "VCV"), default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if len(args.allele) > 100:
        raise ValueError("LITERATURE_ALLELE_INPUT_BUDGET")
    with args.input.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    sources = []
    total = 0
    for path, pin, vcv in args.allele:
        with Path(path).open("rb") as stream:
            data = stream.read(MAX_RECORD_BYTES + 1)
        total += len(data)
        if total > 16 * 1024 * 1024:
            raise ValueError("LITERATURE_ALLELE_INPUT_BUDGET")
        sources.append((data, pin, vcv))
    result = qualify(raw, args.pmcid, args.sha256, sources)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"cases": len(result["cases"]), "sourceTrioAssertions": sum(c["targetTesting"]["status"] != "UNKNOWN" for c in result["cases"]),
                      "modelCalls": 0, "evaluationStatus": result["evaluationStatus"]}))


if __name__ == "__main__":
    main()
