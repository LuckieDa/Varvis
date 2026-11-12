"""Resolve a paper's paired coding/protein alias against a pinned ClinVar record.

Identity only: neither a ClinVar classification nor a citation proves de novo
inheritance. No network, liftover, reference check, HGVS interpretation or scoring.
"""
import re
import xml.etree.ElementTree as ET
from prepare_literature import sha

VERSION = "denovo-literature-allele-identity-1.0.1"
MAX_RECORD_BYTES = 4 * 1024 * 1024
AMINO = dict(zip(
    "Ala Arg Asn Asp Cys Gln Glu Gly His Ile Leu Lys Met Phe Pro Ser Thr Trp Tyr Val Ter".split(),
    "ARNDCQEGHILKMFPSTWYV*"))


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def walk(node, path):
    yield node, path
    counts = {}
    for child in node:
        counts[child.tag] = counts.get(child.tag, 0) + 1
        yield from walk(child, path + "/" + child.tag + "[" + str(counts[child.tag]) + "]")


def protein(value):
    value = value.strip().removeprefix("p.").strip()
    if value.startswith("(") and value.endswith(")"):
        value = value[1:-1]
    for long, short in AMINO.items():
        value = value.replace(long, short)
    return value


def coding(value):
    """Paper shorthand only; never translate one coordinate or transcript to another."""
    value = value.strip()
    if ":" in value:
        transcript, value = value.split(":", 1)
        require(re.fullmatch(r"NM_[0-9]+\.[1-9][0-9]*", transcript),
                "LITERATURE_TRANSCRIPT_IDENTITY_UNSUPPORTED")
    else:
        transcript = None
    value = value.removeprefix("c.")
    require(re.fullmatch(r"[1-9][0-9]*(?:_[1-9][0-9]*)?(?:[ACGT]>[ACGT]|del[ACGT]*|dup[ACGT]*|ins[ACGT]+|delins[ACGT]+)", value),
            "LITERATURE_CODING_ALIAS_UNSUPPORTED")
    return transcript, "c." + value


def read_record(raw, trusted_sha256, expected_accession):
    require(0 < len(raw) <= MAX_RECORD_BYTES, "LITERATURE_ALLELE_SOURCE_SIZE_LIMIT")
    require(sha(raw) == trusted_sha256, "LITERATURE_ALLELE_SOURCE_SHA_MISMATCH")
    require(re.fullmatch(r"VCV[0-9]{9}", expected_accession), "LITERATURE_VCV_ID_INVALID")
    xml = raw.decode("utf-8-sig")
    require(not re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", xml, re.I), "LITERATURE_ALLELE_XML_DTD_FORBIDDEN")
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise ValueError("LITERATURE_ALLELE_XML_INVALID") from error
    require(root.tag in {"ClinVarResult-Set", "VariationArchive"}, "LITERATURE_VCV_FORMAT_UNSUPPORTED")
    # Check depth before recursive provenance construction.
    stack, count = [(root, 0)], 0
    while stack:
        node, depth = stack.pop()
        count += 1
        require(depth <= 64 and count <= 40000, "LITERATURE_VCV_STRUCTURE_LIMIT")
        stack.extend((child, depth + 1) for child in node)
    records = [root] if root.tag == "VariationArchive" else root.findall("VariationArchive")
    require(len(records) == 1, "LITERATURE_SINGLE_VCV_REQUIRED")
    archive = records[0]
    require(archive.get("Accession") == expected_accession
            and archive.get("VariationID") == str(int(expected_accession[3:]))
            and re.fullmatch(r"[1-9][0-9]*", archive.get("Version", "")), "LITERATURE_VCV_ID_MISMATCH")
    require(archive.findtext("RecordStatus") == "current", "LITERATURE_VCV_NOT_CURRENT")
    require(archive.findtext("Species") == "Homo sapiens", "LITERATURE_VCV_SPECIES_UNSUPPORTED")
    classified = archive.findall("ClassifiedRecord")
    require(len(classified) == 1 and not archive.findall("IncludedRecord"), "LITERATURE_SINGLE_ALLELE_REQUIRED")
    classified = classified[0]
    simples = classified.findall("SimpleAllele")
    require(len(simples) == 1 and classified.find("Haplotype") is None and classified.find("Genotype") is None,
            "LITERATURE_SINGLE_ALLELE_REQUIRED")
    simple = simples[0]
    require(simple.get("VariationID") == archive.get("VariationID"), "LITERATURE_SIMPLE_ALLELE_ID_MISMATCH")
    paths = {id(n): p for n, p in walk(root, "/" + root.tag + "[1]")}

    def cite(node):
        return {"sourceSha256": trusted_sha256, "xpath": paths[id(node)],
                "attributes": dict(node.attrib), "text": "".join(node.itertext()).strip()}

    genes = simple.findall("GeneList/Gene")
    require(len(genes) == 1 and re.fullmatch(r"HGNC:[1-9][0-9]*", genes[0].get("HGNC_ID", "")),
            "LITERATURE_GENE_IDENTITY_AMBIGUOUS")
    # Direct allele location, never a descendant gene interval.
    locations = [n for n in simple.findall("Location/SequenceLocation") if n.get("Assembly") == "GRCh38"]
    require(len(locations) == 1, "LITERATURE_GRCH38_LOCATION_AMBIGUOUS")
    loc = locations[0]
    contig, pos, ref, alt = (loc.get(k, "") for k in ("Chr", "positionVCF", "referenceAlleleVCF", "alternateAlleleVCF"))
    require(contig in {str(i) for i in range(1, 23)} and re.fullmatch(r"[1-9][0-9]*", pos)
            and re.fullmatch(r"[ACGT]{1,50}", ref) and re.fullmatch(r"[ACGT]{1,50}", alt)
            and ref != alt and (len(ref) == 1 or len(alt) == 1), "LITERATURE_ALLELE_SCOPE_UNSUPPORTED")
    pairs = []
    for hgvs in simple.findall("HGVSlist/HGVS"):
        if hgvs.get("Type") != "coding":
            continue
        nt, aa = hgvs.findall("NucleotideExpression"), hgvs.findall("ProteinExpression")
        if len(nt) != 1 or len(aa) != 1:
            continue
        transcript = nt[0].get("sequenceAccessionVersion", "")
        if not re.fullmatch(r"NM_[0-9]+\.[1-9][0-9]*", transcript):
            continue
        change = nt[0].get("change", "")
        expression = nt[0].findtext("Expression", "")
        pchange = aa[0].get("change", "")
        paccession = aa[0].get("sequenceAccessionVersion", "")
        if (expression != transcript + ":" + change or not change.startswith("c.")
                or aa[0].findtext("Expression", "") != paccession + ":" + pchange):
            continue
        pairs.append({"transcript": transcript, "coding": change, "protein": protein(pchange), "citation": cite(hgvs)})
    pmids = []
    # A PMID in a disease background alone cannot link this variant to a paper.
    for node, path in walk(classified, paths[id(classified)]):
        if node.tag == "ID" and node.get("Source") == "PubMed" and "/Citation[" in path:
            if any(s in path for s in ("/TraitSet[", "/ConditionList[", "/Trait[", "/RCVList[")):
                continue
            if re.fullmatch(r"[1-9][0-9]*", node.text or ""):
                pmids.append({"pmid": node.text, "citation": cite(node)})
    return {"methodVersion": VERSION, "vcv": expected_accession + "." + archive.get("Version"),
            "sourceSha256": trusted_sha256, "geneSymbol": genes[0].get("Symbol"), "hgncId": genes[0].get("HGNC_ID"),
            "allele": {"assembly": "GRCh38", "contig": contig, "position": int(pos), "reference": ref, "alternate": alt},
            "geneCitation": cite(genes[0]), "locationCitation": cite(loc), "pairedHgvs": pairs, "pubmed": pmids,
            "qualification": "ALIAS_RESOURCE_ONLY_NOT_REFERENCE_OR_DENOVO_PROOF"}


def one_field(case, field):
    values = {f["sourceValue"] for f in case["fields"] if f["field"] == field and f["findingState"] == "REPORTED"}
    require(len(values) == 1, "LITERATURE_FIELD_MISSING_OR_AMBIGUOUS:" + field)
    return next(iter(values))


def resolve(case, pmid, records):
    try:
        gene = one_field(case, "geneSymbol")
        transcript, change = coding(one_field(case, "variantText"))
        prot = protein(one_field(case, "proteinChange"))
        tx_fields = {f["sourceValue"] for f in case["fields"] if f["field"] == "transcript" and f["findingState"] == "REPORTED"}
        if tx_fields:
            require(len(tx_fields) == 1 and (transcript is None or transcript in tx_fields), "LITERATURE_TRANSCRIPT_CONFLICT")
            transcript = next(iter(tx_fields))
            require(re.fullmatch(r"NM_[0-9]+\.[1-9][0-9]*", transcript), "LITERATURE_TRANSCRIPT_IDENTITY_UNSUPPORTED")
        require(re.fullmatch(r"[1-9][0-9]*", pmid or ""), "LITERATURE_PMID_REQUIRED")
        matches = []
        for record in records:
            links = [p for p in record["pubmed"] if p["pmid"] == pmid]
            pairs = [p for p in record["pairedHgvs"] if p["coding"] == change and p["protein"] == prot
                     and (transcript is None or p["transcript"] == transcript)]
            if record["geneSymbol"] == gene and links and pairs:
                matches.append((record, links, pairs))
        identities = {(r["hgncId"], tuple(r["allele"].items())) for r, unused, unused2 in matches}
        require(len(identities) == 1, "LITERATURE_ALLELE_ALIAS_NOT_FOUND" if not identities else "LITERATURE_ALLELE_ALIAS_AMBIGUOUS")
        record = matches[0][0]
        return {"status": "ALIAS_RESOLVED_REFERENCE_CHECK_PENDING", "hgncId": record["hgncId"], "allele": record["allele"],
                "matches": [{"vcv": r["vcv"], "sourceSha256": r["sourceSha256"], "geneCitation": r["geneCitation"],
                             "locationCitation": r["locationCitation"], "pairedHgvs": pairs, "pubmed": links}
                            for r, links, pairs in matches], "issues": []}
    except ValueError as error:
        return {"status": "UNRESOLVED", "issues": [str(error)]}
