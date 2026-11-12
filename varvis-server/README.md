# Varvis server

Spring Boot backend for chromosome visualization and asynchronous rare-disease
VCF prioritization.

## Configuration boundary

The full-project launcher also loads the optional, ignored
`local/private/config/acmg-evidence-current.properties` under `VARVIS_PROJECT_ROOT`.
This file supplies verified ACMG wiring only; do not put database, server-port or
analysis-storage overrides in it. Preserve existing model/public-network permissions.
`VARVIS_ACMG_LOCAL_CONFIG_IMPORT` can select another optional evidence file.
Without this file, portable defaults remain in effect.

After startup, `GET /variant-analyses/acmg/configuration` reports the actual CURRENT
profile and criteria without opening scientific providers or reading a case.
Enabled criteria do not guarantee qualified resources or a score for every variant;
those checks still occur per analysis.

Portable defaults are in `src/main/resources/application.yml`. Machine-specific
paths and feature switches belong in the ignored
`config/variant-analysis-local.properties` or environment variables. Database
credentials belong in environment variables or the ignored
`config/database-local.properties`.

```text
VARVIS_DB_URL
VARVIS_DB_USERNAME
VARVIS_DB_PASSWORD
VARVIS_PROJECT_ROOT
VARVIS_RESOURCE_ROOT
```

Never commit credentials, patient files, database dumps, or large genomic
resources. A deployment on the shared laboratory server must use a dedicated,
explicitly approved project root and database/schema boundary; existing server
files remain read-only unless the user grants exact authorization.

## Current engine behavior

`variant-analysis.default-engine=EXOMISER` is the product path. When selected,
`VariantAnalysisJobProcessor` enters the Exomiser branch before the legacy
BCFtools/QC/pre-ranking-VEP pipeline. It does not fall back to that older path.

The Exomiser path requires:

- Java 21
- Exomiser CLI 15.1.0
- Exomiser `2602_hg38` and `2602_phenotype` data
- the analysis template and recorded resource fingerprint

GRCh38 input is analyzed directly. GRCh37 input additionally requires Picard,
the `hg19ToHg38` chain, a GRCh38 FASTA, and its Picard sequence dictionary; the
lifted GRCh38 VCF is then analyzed by Exomiser. The separately installed GRCh37
normalization FASTA belongs to the legacy BCFtools route and is dormant for the
current default engine.

The adapter supports Windows and Linux runtime validation. Windows requires a
PE `java.exe` and matching JDK 21 metadata. Linux requires an executable ELF
`java` and matching Linux JDK 21 metadata. Both platforms retain the same CLI,
data-version, fingerprint, timeout, template, and path-safety checks;
unsupported hosts fail explicitly. Automated Linux fixtures validate this
contract without claiming a live shared-server deployment.

Exomiser TSV ingestion is bounded and fail-closed. `result.variants.tsv` and
`result.genes.tsv` are each opened once for content while strict UTF-8 parsing,
raw SHA-256, row/line/column counts, and size limits are computed together.
Variants are capped at 128 MiB and genes at 64 MiB; both permit at most 50,000
data rows, 100,001 physical lines, 256 columns, and a 256 KiB line payload.
The parser revalidates the optional genes snapshot after variants finish, and
the adapter revalidates variants plus genes presence/absence after VCF hydration
and immediately before publishing a completed result. Startup recovery
additionally bounds `result.jsonl` at 128 MiB and revalidates all three artifacts
before committing recovery metadata. JSONL is treated as an opaque recovery
artifact whose presence, size, raw hash, and stability are checked; this does not
validate JSON syntax. Recovery requires the persisted terminal Exomiser
attestation to match the rescanned raw variants SHA-256 and preserves that
pre-restart engine provenance. Symbolic links, Windows reparse points, malformed
TSV text, limit violations, and ordinary output-set drift fail without
persisting a path or raw-cell error. On Windows, a null provider file key uses
an OpenJDK-derived local NTFS drift token; the analysis workspace must still be
locally mounted and product-private.

## Resource roles

| Priority | Resource | Effect in the current Exomiser path |
| --- | --- | --- |
| P0 | Exomiser runtime and 2602 data | Required; a missing/unready engine fails every analysis job. |
| P0 conditional | Picard + chain + GRCh38 FASTA/dict | Required only for GRCh37 uploads. |
| P1 | ClinVar GRCh38 | Strong local clinical-evidence contribution; provider failure is fail-open. |
| P1/P2 | compact gnomAD GRCh38 | Strong population-frequency exclusion; currently disabled locally, while Exomiser has its own population data. |
| P2 | CADD | Bounded computational score and an AlphaGenome candidate prerequisite; its full local SNV file is very large. |
| P2 | SpliceAI | Specialist splice score; currently disabled locally. |
| P2 | AlphaMissense | Specialist missense score; enabled locally. |
| P3 | Phen2Gene | Creates an HPO-to-gene prior summary; Exomiser itself also consumes HPO terms. |
| P3 | AlphaGenome | Explicit asynchronous enhancement, not required for a base job. |
| P4 | VEP | All local execution profiles are disabled and it has no current product effect. |

See `../docs/RESOURCE_IMPORTANCE_AUDIT_2026-08-25.md` before deciding what to
copy to a deployment host.

## Run on Windows

```powershell
.\scripts\start-varvis-server.ps1
```

The helper safely checks port `8086`, infers the workspace roots, loads ignored
local properties, selects a JDK 17+ runtime, validates required configuration,
and starts Maven in the current console. It honors `VARVIS_SERVER_JAVA_HOME`,
then `JAVA_HOME`; on the migrated workstation it can reuse the configured
Exomiser JDK 21 when the system Java is too old. To validate configuration
without starting the service:

```powershell
.\scripts\start-varvis-server.ps1 -ValidateConfigurationOnly
```

To stop only a recognized local Varvis listener:

```powershell
.\scripts\stop-varvis-server.ps1
```

For a generic development start, after supplying equivalent environment
configuration:

```text
mvn spring-boot:run
```

## Analysis endpoints

`POST /variant-analyses/sample-detection` inspects assembly/sample evidence
before submission. `POST /variant-analyses` accepts a single-sample VCF or a
joint family VCF with explicit sample relationships. Status, events, phenotype
prior, paged variants, detail, CSV/JSON export, optional enhancement, and delete
operations are exposed under `/variant-analyses/**`; the repository root README
contains the complete list.

`GET /variant-analyses/{id}/diagnostics` provides a sanitized, read-only
lifecycle diagnosis for an existing job. Its contract is limited to fixed
states, failure classifications, next-action/retry classifications, and
persisted timestamps. It does not expose raw error or event messages, paths,
sample/patient fields, engine payloads, or ranking data; it performs no retry
and no state change. A missing job retains the normal `404` behavior. This
endpoint diagnoses one job and is not a replacement for submission readiness
or infrastructure health monitoring. Version 1 classifies only explicitly
allowlisted structured failures; unstructured or unknown failures deliberately
return `UNKNOWN_FAILURE` and operator review.

`GET /variant-analyses/preflight?genomeAssembly=<GRCh37|GRCh38>` is the
submission-readiness contract. Under the default Exomiser route it blocks on
the Exomiser runtime and writable analysis storage, plus the complete Picard
liftover set for GRCh37. Optional local evidence and VEP readiness are returned
as non-blocking component limitations. The legacy Varvis engine retains its
assembly-specific VEP requirement.

The database schema is an operational prerequisite. The portable conditional
configuration still enables legacy bootstrap/migration when their properties
are absent, but this migrated workstation explicitly disables both, and
acceptance-table bootstrap defaults off. Inspect the effective configuration;
do not assume a fresh or shared database will be mutated into readiness.

## Provider failure semantics

ClinVar, gnomAD, CADD, SpliceAI, and AlphaMissense are local evidence providers.
Their absence or lookup failure is recorded as structured unavailable evidence
rather than silently interpreted as a benign result. The base Exomiser result
remains engine-primary. AlphaGenome is invoked only by an explicit asynchronous
enhancement request and sends one de-identified coordinate/allele request per
candidate to the configured official API client.

## Explicit ACMG evidence business entry

The current explicit BA1 / PM2 Supporting business entry uses `POST /variant-analyses/{id}/acmg/runs`,
`GET /variant-analyses/{id}/acmg/runs[/{runId}]`, and
`GET /variant-analyses/{id}/candidates/{candidateId}/acmg?runId={runId}`.
The last resource associates candidate details with the saved evaluations and
bundles, including their original identities. Base candidate payloads and ranking
are not updated by this entry.

As of 2026-09-16, the sole formal allowlist is `AcmgBusinessRuleScope.CURRENT`
(`src/main/java/org/nbscn/varvis/variantanalysis/provider/`). Production wiring uses
`AcmgExecutionProfile.current`, population-only context preparation and this same
snapshot for dispatch, result completeness and `input-options`. The page reads
that snapshot; historical downloads remain complete and unchanged. Disabled
algorithms and their explicit test constructors remain available outside the
formal production entry. Expanding the list requires user-approved scope and
corresponding preparation/dispatch tests; there is no request-level rule switch.
The older optional integrations described below are retained implementation
reference, not the current production enablement list.

Execution is opt-in: `VARVIS_ACMG_BUSINESS_ENABLED=false` by default. Configure
`VARVIS_ACMG_BINDING_DIRECTORY`, `VARVIS_RESOURCE_ROOT`,
`VARVIS_ACMG_REFERENCE_RECEIPT_FILE`, `VARVIS_ACMG_REFERENCE_RECEIPT_SHA256`, and
`VARVIS_ACMG_REFERENCE_RECEIPT_LOCATOR` on the server, together with the existing
native GRCh38 reference/profile and population resource settings. Prediction
resources are not required by this entry. The receipt must be an
accepted `ExplicitContextSourceLedger` checksum record; a configured FASTA SHA is
not a substitute. No resource path or execution witness is accepted in the HTTP
body. Disabling execution does not prevent saved result readback.

`variant-analysis.acmg-business.working-root` optionally separates new business
query output and functional review records from the published analysis storage
root. Its default remains the analysis storage root. An isolated instance that
reads existing published jobs must set a private working root and copy any
required historical review records there without changing the original files.
The setting participates in the private runtime identity digest.

For HNF1A / MONDO:0015967 / autosomal dominant missense SNVs, GN017 v3.1
selects REVEL before reading a score: PP3 >= 0.70 and BP4 <= 0.15, Supporting
only, with ENST00000257555.11 / NM_000545.8 and native GRCh38 input.
`variant-analysis.acmg-business.revel-enabled` defaults to `true` within the
explicit entry. Its optional `revel-manifest-file` property uses the same prefix;
the default file is `${VARVIS_RESOURCE_ROOT}/Tool/revel/hnf1a/revel-hnf1a.manifest.json`.
The verified precomputed resource is loaded as an immutable service snapshot;
restart after an intentional resource update to bind its new execution/cache
identity. Missing, disabled or incompatible REVEL remains not evaluated and
does not fall back to AlphaMissense. Other applicable contexts retain their
existing prediction policies. REVEL protein prediction does not satisfy BP7
splice dependencies. Source GRCh38 coordinates are publisher liftOver derivatives,
with original GRCh37 coordinates retained and allele/transcript mapping verified.
See `scripts/revel/` for bounded preparation and independent verification.

Declarations remain explicit single-proband native GRCh38 inputs. Prediction
lanes retain their SNV scope. PM2 also accepts source-verified, already minimal
small sequence alleles (up to 51 bases including an indel anchor), independently
of AlphaMissense/protein matching or SpliceAI availability. Unverified
normalization, other assemblies and unsupported alleles retain explicit blockers.
PM2 selects versioned gene/disease/MOI policies; it does not apply one numerical
threshold to every gene. Independent all-sites resource configuration and the
public engineering panel are documented in [the PM2 workflow](../scripts/pm2/README.md).
The
body supplies `topN` (integer 1–100), `declarationVersion`, `sampleId`, `dataClass`,
`patient`, and `hypotheses`; the latter use the existing A3C declaration fields.
Bodies are limited to 1 MiB and 200 hypotheses. Numeric coercion, duplicate JSON
keys and unsupported fields are rejected. Missing clinical inputs remain blockers;
the service prepares source-backed recommendations and requires confirmation of
unresolved disease or transcript choices. A catalog relationship is not a patient
diagnosis.

The existing Exomiser publication path captures a private, versioned source
snapshot for at most the first 100 formally persisted rows, including the existing
single-scan allele summary and actual annotation objects. When business execution
is enabled, native unconverted Exomiser publication also uses the existing bounded
VEP annotation service for those rows lacking completed annotations, before the
snapshot is saved. This only updates annotations, without invoking enhancement or
reranking. Existing VEP configuration/readiness still applies; unavailable
annotations remain blockers and no transcript is chosen automatically. Recovery verifies the
stored hash, task and candidate identities and VCF metadata without rescanning
VCF content. Missing or stale historical sources cannot execute ACMG. Only
server-owned database snapshots may re-establish an execution witness.

The additive private tables `variant_analysis_acmg_source` and
`variant_analysis_acmg_run` follow the configured table prefix. An atomic database
claim precedes evaluation; the result and private ledger are saved in one update
and read back before success is returned. Identical declarations return the same
run, including concurrent submissions. Revised declarations create distinct runs.
Explicit `retryOf` accepts a terminal `FAILED` or `COMPLETED_WITH_BLOCKERS` run;
it creates a new run and retains the failed one. A process interrupted while
`RUNNING` remains incomplete and is never silently rerun or reported successful.
There is no automatic trigger, clinical signoff or five-tier classification.

The invocation registration `pp3-bp4-explicit-business-e4-b1-v1` supersedes only
the old explicit-only invocation activation. It pins the unchanged E3-C1
calibration/evaluator and paired A2A CSpec registry; thresholds and Supporting
strength are unchanged. Old profile and registry snapshots remain available to
the old explicit entry and its independent validators.

### Optional reviewed prediction bundle

`variant-analysis.acmg-business.reviewed-prediction-policy-enabled` defaults to
`false`. Enabling it additionally requires `reviewed-prediction-policy-file`,
`reviewed-prediction-policy-file-sha256` (whole file) and
`reviewed-prediction-policy-sha256` (canonical JSON), under the same property
prefix. The bundle remains a reviewed, inactive source snapshot. This explicit
business entry does not enable automatic classification or adoption.

The optional publisher REVEL adapter uses `revel-publisher-index-directory`,
`revel-publisher-tabix-executable`, `revel-publisher-review-file`,
`revel-publisher-review-file-sha256` and `revel-publisher-review-sha256` under that
prefix. Its reviewed resource binding must match the selected policy exactly.
Global business and individual predictor disabling takes precedence over both
current and permitted legacy clauses. Invalid enabled configuration retains a
saveable unavailable identity; it does not become the default legacy entry.

`REVIEWED_PREDICTION_CONTEXT` confirms the exact persisted VEP 116 transcript
identity without fetching AlphaMissense identities or scores. Every submitted
context prepares PP3, BP4 and BP7 policies before source queries begin. Only
clauses explicitly outside a valid catalog retain their existing bounded policy;
covered gaps, missing scores, disabled sources and cancellation never select
another model. SpliceAI contexts share one raw batch, and current BP7 consumes
only its same-context current BP4 result. A legacy BP7 whose legacy BP4 is not
permitted remains not evaluated. PM2-only confirmation remains separate.

The successor execution profile stores the complete preparation, lookup
requests, observations and results in `transcriptInput.reviewedPrediction` and
uses `reviewed-prediction-business-artifact-v1`. Its bundle `providerCalls` counts
callback attempts; the enclosing dispatch count explicitly uses
`SOURCE_PROVIDER_INVOCATIONS`. `sourceSessionAudit` with stage
`DISPATCH_FINISHED` records the final shared raw-batch count. These are distinct
from physical tabix processes or model inference calls. Cancellation preserves
already completed work and actual calls, and stops remaining evaluations.
Saved GET and identical-request reuse read the original results without queries;
changing the bound configuration creates a separate request identity.

This opt-in wiring is covered by synthetic and private persistence tests. New
policy contexts still require source and business validation, and the new page
state remains pending manual acceptance. The default entry and historical
results retain their existing identities.

## Validation

```text
mvn test
```

Use focused integration tests only when their external tools and small test
regions are explicitly configured. Do not scan full VCF/FASTA resources merely
to validate documentation changes.
