"""Prepare bounded, provenance-bound JATS source units. Does not qualify clinical facts.

The same units serve deterministic table readers and a future model adapter. Model
or user claims cannot become reviewed facts merely because a citation is present.
No network calls, external DTD resolution, PDF OCR or fabricated clinical fields.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

VERSION = "denovo-jats-source-1.2.0"
MAX_BYTES = 2 * 1024 * 1024
MAX_NODES = 40000
MAX_DEPTH = 64


def text(node):
    return " ".join("".join(node.itertext()).split())


def prose(node):
    """Keep a paragraph's own prose without concatenating embedded figures/tables."""
    # Iterative so malformed deep inline markup reaches the normal structure
    # limit below rather than Python's recursion limit during text preparation.
    stack, parts = [node], []
    while stack:
        element = stack.pop()
        if isinstance(element, str):
            parts.append(element)
            continue
        parts.append(element.text or "")
        for child in reversed(list(element)):
            stack.append(child.tail or "")
            if child.tag not in {"fig", "table-wrap", "supplementary-material"}:
                stack.append(child)
    return " ".join("".join(parts).split())


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def prepare(raw, expected_pmcid, expected_sha256):
    if not re.fullmatch(r"PMC[1-9][0-9]*", expected_pmcid):
        raise ValueError("LITERATURE_PMCID_INVALID")
    if not re.fullmatch(r"[a-f0-9]{64}", expected_sha256) or sha(raw) != expected_sha256:
        raise ValueError("LITERATURE_SOURCE_SHA_MISMATCH")
    if not raw or len(raw) > MAX_BYTES:
        raise ValueError("LITERATURE_SOURCE_SIZE_LIMIT")
    xml = raw.decode("utf-8-sig")
    # JATS commonly names an external DTD. ElementTree does not resolve it. Internal
    # entity declarations/subsets are rejected before parsing (including expansion bombs).
    if re.search(r"<!ENTITY|<!DOCTYPE[^>]*\[", xml, re.IGNORECASE):
        raise ValueError("LITERATURE_XML_ENTITY_DECLARATION_FORBIDDEN")
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise ValueError("LITERATURE_XML_INVALID") from error
    if root.tag != "article":
        raise ValueError("LITERATURE_JATS_ARTICLE_REQUIRED")
    meta = root.find("./front/article-meta")
    if meta is None:
        raise ValueError("LITERATURE_METADATA_REQUIRED")
    identifiers = {}
    for node in meta.findall("article-id"):
        kind = node.get("pub-id-type", "")
        value = text(node)
        if kind in identifiers and identifiers[kind] != value:
            raise ValueError("LITERATURE_IDENTIFIER_CONFLICT")
        identifiers[kind] = value
    pmc = identifiers.get("pmcid", identifiers.get("pmc", ""))
    if pmc and not pmc.startswith("PMC"):
        pmc = "PMC" + pmc
    if pmc != expected_pmcid:
        raise ValueError("LITERATURE_PMCID_MISMATCH")
    if root.find("body") is None:
        raise ValueError("LITERATURE_BODY_REQUIRED")
    title = meta.find("./title-group/article-title")
    if title is None or not text(title):
        raise ValueError("LITERATURE_TITLE_REQUIRED")

    units, tables, links, cell_records = [], [], [], {}
    count = 0

    def unit(node, path, kind, sections, content=None):
        content = text(node) if content is None else content
        if not content:
            return
        units.append({"unitId": path, "kind": kind, "xmlId": node.get("id"),
                      "sections": list(sections), "text": content,
                      "textSha256": sha(content.encode("utf-8"))})

    def visit(node, path, ancestors, sections):
        nonlocal count
        count += 1
        if count > MAX_NODES or len(ancestors) > MAX_DEPTH:
            raise ValueError("LITERATURE_STRUCTURE_LIMIT")
        if node.tag == "sec":
            heading = node.find("title")
            sections = sections + ([text(heading)] if heading is not None else [])
        if node.tag == "sub-article":
            raise ValueError("LITERATURE_SUBARTICLE_REQUIRES_SEPARATE_IDENTITY")
        if node.tag in ("td", "th"):
            unit(node, path, "TABLE_CELL", sections)
            if id(node) in cell_records:
                cell_records[id(node)]["unitId"] = path
        elif node.tag == "caption":
            unit(node, path, "CAPTION", sections)
        elif node.tag == "p" and not any(a in ancestors for a in ("td", "th", "caption", "ref")):
            kind = "ABSTRACT" if "abstract" in ancestors else "BODY_PARAGRAPH" if "body" in ancestors else "OTHER_TEXT"
            unit(node, path, kind, sections, prose(node))
        elif node.tag == "ref":
            unit(node, path, "REFERENCE", sections)
        if node.tag == "table-wrap":
            table = node.find(".//table")
            rows = []
            if table is not None:
                if sum(1 for unused in table.iter("table")) != 1:
                    raise ValueError("LITERATURE_NESTED_TABLE_NOT_SUPPORTED")
                for row_index, row in enumerate(table.findall(".//tr")):
                    cells = []
                    for col_index, cell in enumerate(list(row)):
                        if cell.tag not in ("td", "th"):
                            raise ValueError("LITERATURE_TABLE_SHAPE_UNSUPPORTED")
                        try:
                            rowspan, colspan = int(cell.get("rowspan", "1")), int(cell.get("colspan", "1"))
                        except ValueError as error:
                            raise ValueError("LITERATURE_TABLE_SPAN_INVALID") from error
                        if not (1 <= rowspan <= 100 and 1 <= colspan <= 100):
                            raise ValueError("LITERATURE_TABLE_SPAN_INVALID")
                        record = {"sourceCellIndex": col_index, "text": text(cell),
                                  "rowspan": rowspan, "colspan": colspan, "header": cell.tag == "th"}
                        cells.append(record)
                        cell_records[id(cell)] = record
                    rows.append({"sourceRowIndex": row_index, "cells": cells})
            tables.append({"tableId": path, "xmlId": node.get("id"), "rows": rows,
                           "layoutStatus": "SOURCE_SPANS_PRESERVED_NOT_FLATTENED"})
        if node.tag in ("supplementary-material", "media", "ext-link", "xref"):
            href = node.get("{http://www.w3.org/1999/xlink}href")
            rid = node.get("rid")
            if href or rid:
                links.append({"sourceUnitId": path, "kind": node.tag, "href": href,
                              "rid": rid, "refType": node.get("ref-type"), "label": text(node)})
        siblings = {}
        for child in node:
            siblings[child.tag] = siblings.get(child.tag, 0) + 1
            visit(child, path + "/" + child.tag + "[" + str(siblings[child.tag]) + "]", ancestors + [node.tag], sections)

    visit(root, "/article[1]", [], [])
    return {"schema": VERSION, "sourceSha256": expected_sha256, "sourceBytes": len(raw),
            "pmcid": pmc, "identifiers": identifiers, "title": text(title),
            "qualification": "SOURCE_PREPARED_NOT_CLINICALLY_QUALIFIED", "units": units,
            "tables": tables, "links": links, "modelCalls": 0}


def verify_citation(source_raw, pmcid, trusted_sha256, citation):
    """Rebuild from pinned source bytes, never from a model-provided unit index.

    trusted_sha256 must come from the acquisition ledger, not the extracted claim.
    An exact quotation still does not verify its clinical meaning or case binding.
    """
    if citation.get("sourceSha256") != trusted_sha256:
        return "SOURCE_MISMATCH"
    try:
        document = prepare(source_raw, pmcid, trusted_sha256)
    except ValueError:
        return "SOURCE_NOT_VERIFIED"
    matches = [u for u in document["units"] if u["unitId"] == citation.get("unitId")]
    if len(matches) != 1:
        return "UNIT_NOT_FOUND"
    source = matches[0]["text"]
    start, end, quote = citation.get("start"), citation.get("end"), citation.get("quote")
    if type(start) is not int or type(end) is not int or not (0 <= start < end <= len(source)):
        return "SPAN_INVALID"
    if not isinstance(quote, str) or not quote.strip() or source[start:end] != quote:
        return "QUOTE_MISMATCH"
    return "CITATION_MATCHED_SEMANTICS_NOT_VERIFIED"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--pmcid", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    with args.input.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    document = prepare(raw, args.pmcid, args.sha256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(document, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"pmcid": document["pmcid"], "units": len(document["units"]),
                      "tables": len(document["tables"]), "modelCalls": 0}))


if __name__ == "__main__":
    main()
