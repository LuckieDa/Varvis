# Varvis VCF Analysis Demo Suite

This directory contains tiny GRCh38 VCF files for engineering demos and
regression tests. They are synthetic fixtures, not clinical examples, and must
not be used as diagnosis or medical evidence.

The files are intentionally small enough to commit. Do not add real WES/WGS
VCF, FASTA, BAM, CRAM, provider dumps, or patient-derived files here.

## Provider Semantics

- `NO_RECORD` means no matching local provider record was found. It is not the
  same as AF=0.
- `UNAVAILABLE` means the provider or evidence field was not available. It must
  not be interpreted as low risk.
- The demo suite is designed to run without large local ClinVar, gnomAD, CADD,
  SpliceAI, or AlphaMissense resources. When those resources are
  configured, provider-specific evidence can enrich the same VCFs.

## Demo Files

| File | Purpose | Expected top/tier | Expected evidence | Expected warnings/filter reasons | Provider dependency |
| --- | --- | --- | --- | --- | --- |
| `known_clinical_pathogenic.vcf` | ClinVar P/LP low-frequency candidate | top variant, `KNOWN_CLINICAL` | `VCF_INFO_CLINVAR` P/LP, `VCF_INFO_GNOMAD` low AF | provider-unavailable warnings may appear if optional local resources are disabled | default test uses VCF INFO fallback; local ClinVar optional |
| `common_benign.vcf` | Common benign variant is deprioritized | `BENIGN_LIKELY` | ClinVar benign, high gnomAD fallback AF | benign/negative reasons expected | VCF INFO fallback |
| `high_cadd_unknown.vcf` | Computational-only review path | no clinical tier by default; may become `REVIEW` or `HIGH_PRIORITY` if local CADD is enabled and high | low AF fallback; CADD optional | no clinical conclusion should be forced | local CADD optional |
| `splice_spliceai.vcf` | Splice/high-impact consequence demo | `HIGH_PRIORITY` in default rule-based ranking | splice-acceptor consequence; SpliceAI optional | no clinical conclusion by SpliceAI alone | SpliceAI optional |
| `mixed_priority.vcf` | Multi-record demo for sorting, filtered view, and score breakdown | top variant should be the ClinVar P/LP record | ClinVar P/LP, high-impact stop, common AF, QC filtered record | common record has population filter reason; low-quality record has QC reasons | VCF INFO fallback |
| `negative_control.vcf` | No strong clinical signal | `LOW_PRIORITY` or lower review priority under default resources | low AF fallback only | should not report a pathogenic conclusion | VCF INFO fallback |
| `multiallelic_normalization.vcf` | Multi-allelic splitting and REF/ALT exact matching | two per-ALT observations after QC extraction | per-ALT keys `G>A` and `G>T` | multi-allelic QC warning expected | no provider dependency |
| `qc_warning.vcf` | QC gate behavior | `FILTERED_QC` records | low AF fallback | non-PASS, low DP/GQ, and GT=0/0 filter reasons expected | VCF INFO fallback |

## Run The Demo Manually

1. Start the server:

   ```powershell
   cd varvis-server
   .\scripts\start-varvis-server.ps1
   mvn spring-boot:run
   ```

2. Start the client:

   ```powershell
   cd varvis-client-pro
   npm start
   ```

3. Open Analysis Lab and upload one demo VCF from this directory.

4. Expected front-end checks:

   - Status becomes `READY`.
   - Candidate table shows the expected tier and score breakdown.
   - Evidence detail shows provider source/status, including
     `VCF_INFO_CLINVAR` or `VCF_INFO_GNOMAD` where applicable.
   - Use filters such as Tier, ClinVar P/LP, hide common, hide QC, and search.
   - Use `Export CSV` and `Export JSON`; exports default to the
     backend-filtered Top 50.

## Automated Coverage

`VariantAnalysisDemoSuiteTest` runs a lightweight copy-only pipeline over the
core fixtures. It verifies parsing, ranking, filter reasons, multi-allelic
splitting, and CSV/JSON export structure without requiring large local
provider resources.

Optional provider-dependent checks, such as real local CADD or SpliceAI scores,
should be treated as manual smoke tests unless their tiny fixture data is added
explicitly.
