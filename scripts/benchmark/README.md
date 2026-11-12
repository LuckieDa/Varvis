# Benchmark resource scripts

`Test-BenchmarkBackgroundResource.ps1` verifies a completed local background
VCF before it is accepted for the benchmark. It deliberately requires the
official expected VCF byte size and MD5 before it reads the VCF header or
queries the index. Optional official index size/MD5 parameters validate the
sidecar too. The script writes a structured
`benchmark-resource-acceptance.json` only to the caller-provided output
directory.

Provide the VCF, `.tbi`, expected byte size, official MD5, sample, assembly,
contig, output directory, and explicit executable paths as parameters. Supply
an accepted BED to derive an exact singleton, biallelic, non-reference
SNV/small-indel VCF. The script records source/BED/output checksums, tool
versions, stable command tokens, genotype categories and INFO sanitization;
it validates BGZF/Tabix output and supports identity/checksum-verified
idempotent reuse. A versioned derivation contract fingerprint covers the
sample, assembly, BED, genotype/variant selection, INFO-removal policy and
output format so chromosome derivatives can be proven configuration-equivalent.
The same base values may be supplied through the
corresponding `VARVIS_BENCHMARK_*` and `VARVIS_*_EXECUTABLE` environment
variables. No repository path or machine path is embedded in the script or
its manifests.

`Test-BenchmarkBackgroundResource.Synthetic.ps1` is a synthetic smoke test. It
uses the tiny public fixture under `varvis-server/src/test/resources/benchmark/`
and creates all compressed files, indexes, BED-derived output and reports in a
temporary directory. It covers checksum/metadata failures, incomplete download
names, exact sample extraction, non-reference filtering, cohort INFO removal,
BGZF/Tabix generation and idempotent reuse. It is not real 1000 Genomes
acceptance.

`New-BenchmarkBackgroundAcquisitionPlan.ps1` validates the tracked
`benchmark-background-source-manifest-v1` metadata and writes a deterministic
plan without downloading anything. By default it plans required chromosomes
1--22; accepted chromosomes are marked explicitly and chrX is included only
with `-IncludeConditionalChrX`. Every generated plan is
`PLAN_ONLY_NOT_AUTHORIZED_FOR_DOWNLOAD`, contains logical lowercase relative
paths, and records exact pending byte totals and a parameter fingerprint.

`Test-BenchmarkBackgroundAcquisitionPlan.ps1` verifies frozen totals, the
accepted-chr22 boundary, conditional chrX policy, Y/MT status, idempotence,
unsafe path rejection, unsupported chromosomes and incomplete manifest names.

`Download-BenchmarkBackgroundResources.ps1` performs manifest-driven,
resumable downloads with bounded retries and exact size/MD5 acceptance before
promoting a `.part` file to its formal resource name.

`Invoke-BenchmarkBackgroundAcquisition.ps1` processes approved chromosomes in
deterministic order and immediately invokes source acceptance and NA12878
exome-like derivation. It stops on the first failed transfer, checksum, header,
index, sample, BED or derivation step and records progress outside Git.

`Merge-BenchmarkBackgroundResources.ps1` accepts only checksum-matched,
manifested singleton derivatives, concatenates them in `1..22,X` order, and
verifies the shared derivation fingerprint and BED, sample, contig order,
record/SNV/indel/genotype totals, exact duplicates, cohort INFO removal, BGZF
and Tabix before writing an atomic local merge manifest.

`Start-BenchmarkBackgroundAcquisition.ps1` launches the sequential pipeline in
a dedicated hidden PowerShell process. The paired `Get-*Status` and `Stop-*`
scripts use its exact state-file PID, so status and cancellation never target
unrelated PowerShell or curl processes. Heartbeats, resumable-part bytes and
logs stay under the caller-provided ignored resource root.

`import_pilot_hpo_decisions.py` imports the externally reviewed 30-case pilot
decision package. It permits only the documented removal of one malformed
human-approval text line per source document, then requires packet identity,
fingerprint, checksum, HPO order/status and KEEP decisions to match the tracked
review packets exactly. Repository-compatible provenance is emitted without
changing term decisions; Java contract validation remains authoritative.

`approve_pilot_dataset.py` validates the frozen 25+5 proposal, singleton truth
policy, four-dimensional development-smoke exclusion, review/decision mapping,
reviewer-visible masking, source/license metadata and deterministic replacement
order. It writes an immutable approved manifest and validation report without
rewriting the historical proposal or authorizing execution.

`prepare_pilot_execution_preflight.py` creates the immutable approved-pilot
execution plan plus read-only preflight and dry-run reports. It validates
approved dataset/G2 bindings, local logical resource checksums, provider path
presence, database configuration-key presence, 25+5 order and 90 G0/G1/G2
inputs without printing configuration values, calling providers or modifying a
database. Its READY state still requires a separately authorized application
artifact, dedicated database and real execution adapter.
