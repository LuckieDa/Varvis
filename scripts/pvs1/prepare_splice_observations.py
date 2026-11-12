"""Replay explicitly declared RNA deletions against pinned, versioned RefSeq bytes.

This offline preparation step binds a quotation and a manual observation claim to
source bytes. It does not interpret assay validity, map a DNA edit, qualify RNA
evidence, choose a PVS1 rule or produce executable runtime material.
"""
import argparse
from copy import deepcopy
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "acmg-generalization"))
from build_transcript_model import CODONS, bounded_bytes, parse_refseq, sha

CLAIM_SCHEMA = "pvs1-rna-deletion-observation-claim-v1"
SCHEMA = "pvs1-rna-observation-source-bound-v1"
MAX_SOURCE_BYTES = 8_000_000
TEXT_COORDINATES = "NORMALIZED_VISIBLE_TEXT_CHAR0_HALF_OPEN"
CDS_COORDINATES = "REFERENCE_CDS_1_BASED_INCLUSIVE"
MISSING_FACTS = (
    "EXACT_ALLELE_ASSEMBLY_AND_GENOMIC_REFERENCE_BINDING",
    "RNA_OBSERVATION_TO_EXACT_ALLELE_ATTRIBUTION",
    "OBSERVED_DELETION_SEMANTICS_AND_TRANSCRIPT_APPLICABILITY_REVIEW",
    "RESIDUAL_NORMAL_TRANSCRIPT_ASSESSED_SCOPE_AND_QUANTIFICATION",
    "FUNCTIONAL_RESCUE_AND_NEARBY_SPLICE_RESCUE_ASSESSMENT",
    "MULTIPLE_AND_ALTERNATIVE_RNA_OUTCOMES_ASSESSMENT",
    "ASSAY_QUALITY_CONTROLS_REPLICATION_AND_TISSUE_RELEVANCE",
    "EXACT_GENE_DISEASE_MODE_OF_INHERITANCE_AND_LOF_MECHANISM",
    "APPLICABLE_PVS1_RULE_AND_STRENGTH_SOURCE_REVIEW",
    "FUNCTIONAL_REGION_AND_EXON_RELEVANCE_ASSESSMENT",
    "SAME_EFFECT_EVIDENCE_OVERLAP_REVIEW",
)


def require(condition, reason):
    if not condition:
        raise ValueError("PVS1_RNA_OBSERVATION_" + reason)


def object_fields(value, required, optional=()):
    return isinstance(value, dict) and set(required) <= value.keys() <= set(required) | set(optional)


def normalized_text(value):
    return re.sub(r"\s+", " ", value).strip()


class VisibleText(HTMLParser):
    """Deterministic text locator; scripts/styles are never citation text."""
    BLOCKS = {"address", "article", "aside", "blockquote", "br", "caption", "dd", "div", "dl", "dt",
              "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
              "li", "main", "nav", "ol", "p", "pre", "section", "table", "td", "th", "tr", "ul"}
    HIDDEN = {"script", "style", "noscript", "template"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = []

    def handle_starttag(self, tag, attrs):
        if tag in self.HIDDEN:
            self.hidden.append(tag)
        elif not self.hidden and tag in self.BLOCKS:
            self.parts.append(" ")

    def handle_endtag(self, tag):
        if self.hidden:
            if tag == self.hidden[-1]:
                self.hidden.pop()
        elif tag in self.BLOCKS:
            self.parts.append(" ")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def decode_source(content, source):
    require(isinstance(content, bytes) and 0 < len(content) <= MAX_SOURCE_BYTES, "SOURCE_SIZE_INVALID")
    require(isinstance(source["encoding"], str)
            and source["encoding"] in {"utf-8", "utf-8-sig", "ascii", "iso-8859-1", "windows-1252"}, "ENCODING_UNSUPPORTED")
    require(isinstance(source["mediaType"], str) and source["mediaType"] in {"text/plain", "text/html"},
            "SOURCE_MEDIA_TYPE_UNSUPPORTED")
    try:
        decoded = content.decode(source["encoding"], errors="strict")
    except UnicodeError as error:
        raise ValueError("PVS1_RNA_OBSERVATION_SOURCE_DECODE_FAILED") from error
    if source["mediaType"] == "text/html":
        parser = VisibleText()
        parser.feed(decoded)
        parser.close()
        decoded = "".join(parser.parts)
    return normalized_text(decoded)


def source_identity(value):
    require(isinstance(value.get("url"), str), "SOURCE_URL_INVALID")
    url = urlsplit(value["url"])
    require(url.scheme in {"http", "https"} and bool(url.netloc) and not url.username and not url.password,
            "SOURCE_URL_INVALID")
    require(isinstance(value.get("sha256"), str) and re.fullmatch(r"[0-9a-f]{64}", value["sha256"]),
            "SOURCE_DIGEST_INVALID")


def occurrences(text, literal):
    return [match.start() for match in re.finditer(r"(?<![A-Za-z0-9_])" + re.escape(literal)
                                                  + r"(?![A-Za-z0-9_]|\.[0-9])", text)]


def translate_to_stop(sequence):
    residues = []
    for position in range(0, len(sequence) - 2, 3):
        residue = CODONS[sequence[position:position + 3]]
        residues.append(residue)
        if residue == "*":
            break
    return "".join(residues)


def deletion_coordinates(refseq, declared):
    require(object_fields(declared, {"coordinateSystem"}, {"start", "end", "exonOrdinal", "deletedSequence"})
            and declared["coordinateSystem"] == CDS_COORDINATES, "DELETION_FIELDS_INVALID")
    has_range = "start" in declared or "end" in declared
    has_exon = "exonOrdinal" in declared
    require(has_range or has_exon, "DELETION_INTERVAL_REQUIRED")
    length = len(refseq["cdsSequence"])
    if has_range:
        require(type(declared.get("start")) is int and type(declared.get("end")) is int
                and 1 <= declared["start"] <= declared["end"] <= length, "DELETION_INTERVAL_INVALID")
        start, end = declared["start"], declared["end"]
        rna_start = refseq["cdsStart"] + start - 1
        rna_end = refseq["cdsStart"] + end - 1
    if has_exon:
        ordinal = declared["exonOrdinal"]
        require(type(ordinal) is int and 1 <= ordinal <= len(refseq["exons"]), "EXON_ORDINAL_INVALID")
        exon_start, exon_end = refseq["exons"][ordinal - 1]
        exon_cds_start = max(1, exon_start - refseq["cdsStart"] + 1)
        exon_cds_end = min(length, exon_end - refseq["cdsStart"] + 1)
        require(exon_cds_start <= exon_cds_end, "DELETION_HAS_NO_CDS_OVERLAP")
        require(not has_range or (start, end) == (exon_cds_start, exon_cds_end), "EXON_INTERVAL_MISMATCH")
        start, end = exon_cds_start, exon_cds_end
        rna_start, rna_end = exon_start, exon_end
    deleted = refseq["cdsSequence"][start - 1:end]
    if "deletedSequence" in declared:
        require(isinstance(declared["deletedSequence"], str) and declared["deletedSequence"] == deleted,
                "DELETED_SEQUENCE_MISMATCH")
    affected = []
    for ordinal, (lo, hi) in enumerate(refseq["exons"], 1):
        overlap_start, overlap_end = max(lo, rna_start), min(hi, rna_end)
        if overlap_start <= overlap_end:
            affected.append(dict(referenceExonOrdinal=ordinal, referenceRnaStart=overlap_start,
                                 referenceRnaEnd=overlap_end, deletedRnaBases=overlap_end - overlap_start + 1,
                                 fullExonRemoved=(overlap_start, overlap_end) == (lo, hi)))
    return dict(coordinateSystem=CDS_COORDINATES, start=start, end=end, deletedCdsLength=len(deleted),
                deletedCdsSequence=deleted, referenceRnaStart=rna_start, referenceRnaEnd=rna_end,
                deletedRnaLength=rna_end - rna_start + 1, affectedExons=affected,
                declaredWholeExonOrdinal=declared.get("exonOrdinal"))


def sequence_effect(refseq, deletion):
    reference = refseq["cdsSequence"]
    start, end = deletion["start"], deletion["end"]
    altered = reference[:start - 1] + reference[end:]
    protein = translate_to_stop(altered)
    reference_protein = refseq["proteinSequence"]
    first = 0
    while first < min(len(reference_protein), len(protein)) and reference_protein[first] == protein[first]:
        first += 1
    has_change = first < len(reference_protein) or first < len(protein.rstrip("*"))
    stop = protein.find("*")
    ptc_start = stop * 3 + 1 if stop >= 0 else None
    native_stop_retained = end < len(reference) - 2
    native_stop_altered_start = len(reference) - 2 - deletion["deletedCdsLength"] if native_stop_retained else None
    first_changed = first + 1 if has_change else None
    return dict(referenceCdsSha256=sha(reference.encode("ascii")), referenceCdsLength=len(reference),
                alteredCdsSequence=altered, alteredCdsSha256=sha(altered.encode("ascii")),
                alteredCdsLength=len(altered), deletedCdsLength=deletion["deletedCdsLength"],
                frame="IN_FRAME" if deletion["deletedCdsLength"] % 3 == 0 else "FRAME_SHIFTED",
                translationInterpretation="MECHANICAL_TRANSLATION_FROM_ALTERED_CDS_OFFSET_ZERO_NOT_OBSERVED_PROTEIN",
                referenceStartCodonRetained=start > 3, referenceStopCodonRetained=native_stop_retained,
                alteredProteinThroughFirstTermination=protein,
                firstChangedResiduePosition=first_changed,
                referenceResidueAtFirstChange=reference_protein[first] if first_changed is not None and first < len(reference_protein) else None,
                alteredResidueAtFirstChange=protein[first] if first_changed is not None and first < len(protein) else None,
                firstStopCodonStartCds=ptc_start, firstStopCodonEndCds=ptc_start + 2 if ptc_start is not None else None,
                referenceNativeStopStartInAlteredCds=native_stop_altered_start,
                terminationBeforeRetainedReferenceStop=(ptc_start < native_stop_altered_start)
                if ptc_start is not None and native_stop_altered_start is not None else None,
                residuesFromFirstChangeThroughStop=stop - first + 1 if stop >= first and first_changed is not None else None)


def prepare_observation(source_bytes, refseq_bytes, claim):
    """Return review-only observations or raise on a pin/locator/identity/replay failure."""
    require(object_fields(claim, {"schemaVersion", "observationId", "transcript", "cHgvsc", "source", "refseq", "rnaDeletion"})
            and claim["schemaVersion"] == CLAIM_SCHEMA, "CLAIM_FIELDS_INVALID")
    require(isinstance(claim["observationId"], str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", claim["observationId"]),
            "OBSERVATION_ID_INVALID")
    require(isinstance(claim["transcript"], str) and re.fullmatch(r"NM_[0-9]+\.[1-9][0-9]*", claim["transcript"]),
            "VERSIONED_TRANSCRIPT_REQUIRED")
    require(isinstance(claim["cHgvsc"], str) and re.fullmatch(r"c\.[A-Za-z0-9_*+>?().=\-]{1,198}", claim["cHgvsc"]),
            "CHGVSC_LITERAL_INVALID")
    source, genbank = claim["source"], claim["refseq"]
    require(object_fields(source, {"url", "sha256", "mediaType", "encoding", "quote", "quoteStart", "quoteEnd"})
            and object_fields(genbank, {"url", "sha256"}), "SOURCE_FIELDS_INVALID")
    source_identity(source)
    source_identity(genbank)
    require(isinstance(source_bytes, bytes) and sha(source_bytes) == source["sha256"], "SOURCE_BYTES_MISMATCH")
    require(isinstance(refseq_bytes, bytes) and 0 < len(refseq_bytes) <= 2_000_000
            and sha(refseq_bytes) == genbank["sha256"], "REFSEQ_BYTES_MISMATCH")
    text = decode_source(source_bytes, source)
    quote = source["quote"]
    require(isinstance(quote, str) and 1 <= len(quote) <= 5000 and quote == normalized_text(quote), "QUOTE_INVALID")
    first = text.find(quote)
    require(first >= 0, "QUOTE_NOT_FOUND")
    require(text.find(quote, first + 1) < 0, "QUOTE_NOT_UNIQUE")
    require(type(source["quoteStart"]) is int and type(source["quoteEnd"]) is int
            and source["quoteStart"] == first and source["quoteEnd"] == first + len(quote), "QUOTE_LOCATOR_MISMATCH")
    refseq = parse_refseq(refseq_bytes)
    require(refseq["transcript"] == claim["transcript"], "REFSEQ_TRANSCRIPT_VERSION_MISMATCH")
    accession = claim["transcript"].split(".")[0]
    published_versions = set(re.findall(r"(?<![A-Za-z0-9_])" + re.escape(accession) + r"\.[0-9]+(?![0-9]|\.[0-9])", text))
    require(published_versions == {claim["transcript"]}, "SOURCE_TRANSCRIPT_VERSION_MISMATCH_OR_MISSING")
    transcript_mentions = occurrences(text, claim["transcript"])
    allele_mentions = occurrences(text, claim["cHgvsc"])
    require(transcript_mentions and allele_mentions, "SOURCE_EXACT_IDENTITY_MENTION_MISSING")
    require(1 <= len(refseq["exons"]) <= 1000, "REFSEQ_EXON_COUNT_UNSUPPORTED")
    deletion = deletion_coordinates(refseq, claim["rnaDeletion"])
    effect = sequence_effect(refseq, deletion)
    missing = list(MISSING_FACTS)
    if not effect["referenceStartCodonRetained"]:
        missing.append("TRANSLATION_INITIATION_AFTER_REFERENCE_START_LOSS")
    if not effect["referenceStopCodonRetained"]:
        missing.append("ALTERED_RNA_THREE_PRIME_END_AND_TERMINATION_SEQUENCE")
    if effect["firstStopCodonStartCds"] is None:
        missing.append("TERMINATION_BEYOND_AVAILABLE_ALTERED_CDS")
    claim_bytes = json.dumps(claim, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")
    return dict(schemaVersion=SCHEMA, status="RNA_OBSERVATION_SOURCE_BOUND", observationId=claim["observationId"],
                runtimeEligible=False, reviewRequired=True, scientificEvaluationPerformed=False,
                transcript=refseq["transcript"], protein=refseq["protein"], geneSymbol=refseq["symbol"],
                hgncId=refseq["hgncId"], declaredCHgvsc=claim["cHgvsc"], declaredClaim=deepcopy(claim),
                claimCanonicalSha256=sha(claim_bytes),
                sourceReceipts=[dict(role="RNA_OBSERVATION_ORIGINAL", url=source["url"], sha256=sha(source_bytes),
                                     byteLength=len(source_bytes), mediaType=source["mediaType"], encoding=source["encoding"],
                                     normalizedVisibleTextSha256=sha(text.encode("utf-8")), locatorCoordinateSystem=TEXT_COORDINATES,
                                     quote=quote, quoteStart=first, quoteEnd=first + len(quote),
                                     exactTranscriptMentionOffsets=transcript_mentions, exactCHgvscMentionOffsets=allele_mentions,
                                     urlProvenance="DECLARED_URL_NOT_NETWORK_AUTHENTICATED"),
                                dict(role="VERSIONED_REFSEQ_GENBANK", url=genbank["url"], sha256=sha(refseq_bytes),
                                     byteLength=len(refseq_bytes), transcript=refseq["transcript"],
                                     cdsRnaStart=refseq["cdsStart"], cdsRnaEnd=refseq["cdsEnd"],
                                     fullSplicedTranscriptSha256=sha(refseq["sequence"].encode("ascii")),
                                     urlProvenance="DECLARED_URL_NOT_NETWORK_AUTHENTICATED")],
                replayedDeletion=deletion, sequenceEffect=effect, missingFacts=missing,
                useLimit="MANUAL_RNA_DELETION_CLAIM_REPLAYED_ONLY_NO_DNA_EDIT_MAPPING_OR_SCIENTIFIC_ADOPTION")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "refseq", "claim", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    output = Path(args.output)
    require(not output.exists(), "OUTPUT_EXISTS_REFUSE_OVERWRITE")
    claim = json.loads(bounded_bytes(args.claim, 100_000))
    result = prepare_observation(bounded_bytes(args.source, MAX_SOURCE_BYTES), bounded_bytes(args.refseq), claim)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    print(json.dumps(dict(status=result["status"], observationId=result["observationId"],
                          runtimeEligible=False, reviewRequired=True, claimCanonicalSha256=result["claimCanonicalSha256"])))


if __name__ == "__main__":
    main()
