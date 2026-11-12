"""Prepare a synthetic teaching VCF from bounded local public-resource queries.

No patient data, ranking, rule result or reference checksum is manufactured.
Alleles are selected by coordinate/ALT order, independently of their AM scores.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PANELS = [
    ("HBB", "chr11:5226950-5227030", "ENST00000647020.1"),
    ("GJB2", "chr13:20189290-20189500", "ENST00000382848.5"),
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def prepare(resource_root, binding_root, output):
    output.mkdir(parents=True, exist_ok=True)
    fasta = resource_root / "Tool/reference/grch38.p14.primary-chr.fa"
    index_bytes = Path(str(fasta) + ".fai").read_bytes()
    index = {p[0]: tuple(map(int, p[1:5])) for line in index_bytes.decode().splitlines()
             if (p := line.split("\t"))}
    accepted = json.loads((ROOT / "docs/execution/E4_A2B_ACCEPTANCE_EXACT_DISPATCH_2026-09-06.json").read_text(encoding="utf-8"))
    source = accepted["smokeInputProvenance"]["sourceReceipts"][0]
    raw_ledger = source["rawJson"].encode()
    if sha(raw_ledger) != source["sha256"]:
        raise ValueError("ACCEPTED_LEDGER_HASH_MISMATCH")
    receipt = json.loads(raw_ledger)["records"]["reference"]["actualChecksumReceipt"]
    stamp = fasta.stat()
    if (stamp.st_size != receipt["byteLength"] or stamp.st_mtime_ns != receipt["mtimeNs"]
            or sha(index_bytes) != receipt["indexSha256"]):
        raise ValueError("ACCEPTED_REFERENCE_RECEIPT_NOT_APPLICABLE")
    (output / "reference-ledger.json").write_bytes(raw_ledger)
    choices = json.loads((ROOT / "varvis-server/src/test/resources/e4-a2b/hbb-gjb2-explicit-compatible-v1.json").read_text(encoding="utf-8"))
    tabix = resource_root / "Tool/msys64/ucrt64/bin/tabix.exe"
    am_file = resource_root / "Tool/m3/alphamissense_hg38.tabix.tsv.gz"
    variants = []
    query_receipts = []
    with fasta.open("rb") as reference:
        for gene, region, transcript in PANELS:
            result = subprocess.run([str(tabix), str(am_file), region], check=True,
                                    capture_output=True, timeout=60)
            (output / f"{gene.lower()}-public-am-region.tsv").write_bytes(result.stdout)
            query_receipts.append({"gene": gene, "region": region, "sha256": sha(result.stdout),
                                   "selection": "FIRST_ALT_PER_POSITION_THEN_FIRST_30_POSITIONS_NO_SCORE_FILTER"})
            rows = [r.decode().split("\t") for r in result.stdout.splitlines() if r and not r.startswith(b"#")]
            rows = sorted((r for r in rows if len(r) == 10 and r[6] == transcript
                           and len(r[2]) == len(r[3]) == 1 and r[2] != r[3]
                           and r[2] in "ACGT" and r[3] in "ACGT"), key=lambda r: (int(r[1]), r[3]))
            selected = {}
            for row in rows:
                selected.setdefault(int(row[1]), row)
            if len(selected) < 30:
                raise ValueError(f"INSUFFICIENT_PUBLIC_PANEL_ALLELES_{gene}")
            for position, row in list(selected.items())[:30]:
                length, offset, bases, width = index[row[0]]
                p = position - 1
                reference.seek(offset + p // bases * width + p % bases)
                if reference.read(1).decode().upper() != row[2] or position > length:
                    raise ValueError("DEMO_REF_MISMATCH")
                variants.append({"contig": row[0], "position": position, "reference": row[2],
                                 "alternate": row[3], "gene": gene, "transcript": transcript})
    if fasta.stat().st_mtime_ns != stamp.st_mtime_ns:
        raise ValueError("REFERENCE_CHANGED_DURING_PREPARATION")
    sample = "VARVIS_GROUP_DEMO"
    lines = ["##fileformat=VCFv4.2", "##reference=GRCh38",
             "##source=Varvis_synthetic_group_meeting_teaching_panel_v1",
             "##DEMO_NOTICE=Artificial_variant_collection_not_a_real_patient_or_clinical_validation",
             '##FORMAT=<ID=GT,Number=1,Type=String,Description="Synthetic genotype">',
             '##FORMAT=<ID=AD,Number=R,Type=Integer,Description="Synthetic allele depths">',
             '##FORMAT=<ID=DP,Number=1,Type=Integer,Description="Synthetic depth">',
             '##FORMAT=<ID=GQ,Number=1,Type=Integer,Description="Synthetic genotype quality">']
    for contig in ("chr11", "chr13"):
        lines.append(f"##contig=<ID={contig},length={index[contig][0]},assembly=GRCh38>")
    lines.append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t" + sample)
    for i, v in enumerate(variants, 1):
        lines.append("\t".join([v["contig"], str(v["position"]), f"DEMO_{i:03d}", v["reference"],
                                v["alternate"], "60", "PASS", ".", "GT:AD:DP:GQ", "1/1:0,40:40:99"]))
    vcf_bytes = ("\n".join(lines) + "\n").encode()
    (output / "varvis-group-demo.vcf").write_bytes(vcf_bytes)
    manifest = {"schemaVersion": "varvis-group-demo-v1", "dataClass": "SYNTHETIC",
                "clinicalValidation": False, "sampleId": sample, "assembly": "GRCh38",
                "vcfSha256": sha(vcf_bytes), "variantCount": len(variants), "topN": 20,
                "description": "人工教学变异集合：公开资源中的等位基因，人工基因型和病例背景；非真实患者。",
                "hpoTerms": [], "rankingMode": "EXOMISER_G0",
                "patient": {"patientContextId": "patient-context-varvis-group-demo-v1",
                            "role": "PROBAND", "sex": "UNKNOWN"},
                "geneDeclarations": [{"gene": c["symbol"], "hgncId": c["hgncId"],
                                      "mondoId": c["syntheticHypothesisMondoId"],
                                      "moi": c["syntheticHypothesisMoi"], "transcript": c["transcript"],
                                      "affectedStatus": "AFFECTED"} for c in choices["fixedChoices"]],
                "variants": variants, "publicQueryReceipts": query_receipts,
                "ranking": "MUST_BE_COMPUTED_BY_REAL_EXOMISER_NO_STORED_RANKS",
                "evaluation": "MUST_BE_COMPUTED_BY_EXISTING_PP3_BP4_BUSINESS_ENTRY"}
    write_json(output / "demo-manifest.json", manifest)
    # Command-line settings override the existing remote jdbc-url as well as url.
    # This file is local-only and deliberately points to a separate file-backed database.
    props = {
        "server.address": "127.0.0.1", "server.port": "8086",
        "varvis.datasource.jdbc-url": "jdbc:h2:file:" + (output / "database/varvis-demo").as_posix() + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "varvis.datasource.url": "jdbc:h2:file:" + (output / "database/varvis-demo").as_posix() + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "varvis.datasource.driver-class-name": "org.h2.Driver", "varvis.datasource.username": "sa",
        "varvis.datasource.password": "", "varvis.datasource.enabled": "true",
        "variant-analysis.persistence.legacy-schema-bootstrap-enabled": "true",
        "variant-analysis.storage-root": (output / "analyses").as_posix(),
        "variant-analysis.retention": "P30D", "variant-analysis.acmg-business.enabled": "true",
        "variant-analysis.acmg-business.binding-directory": binding_root.as_posix(),
        "variant-analysis.acmg-business.resource-root": resource_root.as_posix(),
        "variant-analysis.acmg-business.reference-receipt-file": (output / "reference-ledger.json").as_posix(),
        "variant-analysis.acmg-business.reference-receipt-sha256": sha(raw_ledger),
        "variant-analysis.acmg-business.reference-receipt-locator": "reference",
        "variant-analysis.vep.enabled": "true", "variant-analysis.vep.execution-enabled": "true",
        "variant-analysis.vep.resources.GRCh38.enabled": "true",
        "variant-analysis.vep.temp-dir": (output / "vep-temp").as_posix(),
    }
    (output / "demo-overrides.properties").write_text("\n".join(k + "=" + v for k, v in props.items()) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PREPARED_NOT_EXECUTION_ACCEPTED", "variantCount": len(variants),
                      "vcfSha256": sha(vcf_bytes), "resourceQueries": len(PANELS),
                      "referenceQueries": len(variants), "fullReferenceHashPerformed": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--resource-root", type=Path, required=True)
    parser.add_argument("--binding-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.resource_root.resolve(), args.binding_root.resolve(), args.output_dir.resolve())
