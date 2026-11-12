# PM2 Supporting resource and business validation

2026-10-11: the selected local EXOMES path now uses the official all-sites TSV through a small in-process sparse index. See [current local integration](local-all-sites.md) for policy routing, manifest binding, verification and remaining scientific limits. The JOINT/Hail material below is historical and is not required by that local path.

This directory supports the existing explicit ACMG business entry. It does not
change candidate filters, scores, order, other criteria, or final classification.

## Production policy data

`build_policy_snapshot.py` compiles **reviewed expressions** into the population
input profile. It is not an automatic natural-language interpreter. Gene, MONDO
and inheritance scope come from the official CSpec RuleSet, and each executable
expression is pinned to the reviewed version and source projection hash.

The 2026-09-09 snapshot contains 12 exact context policies and a discovery index
of 123 scopes from 97 currently released, non-replaced, non-superseded CSpecs.
Discovery metadata alone cannot authorize a rule. A matching unimplemented
specialist specification blocks fallback. Where no specialist policy exists,
population evidence is still prepared, but an authoritative PM2-specific
frequency/callability policy is required before a conclusion can be issued.
There is no universal frequency threshold or reverse BA1/BS1 inference.

Official discovery entry:
`https://cspec.genome.network/cspec/SequenceVariantInterpretation/id`
(bounded pagination; inspect release and legacy replacement/supersession flags).
Retain each selected document's full API response as `cspec/GNxxx.json` in a
private capture directory. The committed source pack retains the reviewed PM2
clauses, scope projection, source URLs and original response hashes.

```text
python scripts/pm2/build_policy_snapshot.py --capture <retained-capture> --resources varvis-server/src/main/resources/acmg
```

Changed source content requires renewed review, updated typed expressions and
hashes, focused evaluator tests, and corresponding execution-profile identity.
Do not activate unreviewed specifications or silently substitute AF for FAF.

## Independent all-sites resource

The existing Hail probe reads bounded loci from the official gnomAD v4.1 all-sites
tables. It does not download the full tables. It requires an already prepared
Hail runtime and matching GCS connector; normal joint observations do not depend
on this runtime.

Configure `variant-analysis.acmg-business.pm2-all-sites-command-file` with an
administrator-owned JSON array of executable and arguments. The runner appends
`--batch-request <path> --output <path>`. On Windows/WSL the existing wrapper is
`scripts/e3/run_gnomad_all_sites_hail_batch_wsl.sh`; its four positional arguments
are the Python executable, probe script, versioned population profile, and GCS
connector. Keep machine paths in ignored local configuration. The default
`pm2-all-sites-timeout-seconds` is 1200.

The selected policy must explicitly authorize absence and define adequate AN and
regional conditions. An independently sufficient dataset can support the branch
even if the other dataset fails. Point coverage does not establish indel
callability. Missing resources, insufficient detection and absent records remain
different states.

## Fixed public engineering panel

`public-panel-v1/public-multigene.vcf` contains 30 public alleles from 9 genes,
with synthetic genotypes and no patient phenotype. The manifest identifies exact
public records and their byte hashes. The VCF was fixed after REF/VEP and original
Exomiser frequency prechecks, before formal analysis. Do not replace alleles or
change ranking/filters to improve a result.

The opt-in `Pm2MultigeneBusinessSmokeTest#formalTop20SaveRecreateAndRepeat` uses
the original upload/analysis, formal Top-20, recommendation verification, HTTP
controller and file-backed H2 repositories. Required properties:

```text
-Dvarvis.pm2.multigene.real=true
-Dvarvis.pm2.multigene.fixedVcf=../scripts/pm2/public-panel-v1/public-multigene.vcf
-Dvarvis.pm2.multigene.fixedSha256=584a7668220a0a9059a47cfda1540add1a640d36eff130c3802f75cfab1711d7
-Dvarvis.pm2.multigene.allSitesCommand=<private-command-json>
-Dvarvis.e4b3.resourceRoot=<configured-resource-root>
```

Resume uses `-Dvarvis.pm2.multigene.resume=<closed-prior-attempt>` and
`-Dvarvis.pm2.multigene.analysisWork=<original-analysis-directory>`. It copies the
bounded engineering database into a new attempt, retains the actual uploaded
path/source identity, and never executes Exomiser or VEP. Existing source files
and prior responses stay unchanged. It is an acceptance helper, not database
migration or a patient-data import tool.

`-Dvarvis.pm2.multigene.withoutSpliceRuntime=true` disables the SpliceAI command
only in the isolated acceptance environment. It verifies independent PM2
operation without another model run; it does not alter the user's configuration
or imply successful evaluation of the unavailable prediction criteria.

The opt-in `recheckActualSavedEvidenceWithoutCallingResources` test accepts
`varvis.pm2.multigene.recheck=<saved-response>` and
`varvis.pm2.multigene.recheckOutput=<new-output>`. It re-evaluates retained actual
PM2 evidence without providers, compares scientific outcomes, and preserves the
source response byte hash. This read-only check does not replace HTTP acceptance.

Validate saved artifacts independently with the frozen E1 validator dependencies:

```text
python scripts/pm2/verify_multigene_business.py <completed-attempt> --output <new-report.json>
```

Optional `--python-packages` points to an existing isolated schema dependency
directory. The verifier checks unchanged Java identities, source/policy bindings,
formal candidate order, saved GET and repeat POST equality. Fixture results and
real resource results must be reported separately. The original page requires
separate manual acceptance; this workflow never uses Computer Use or an existing
listening service.

The smoke declaration is now generated by `build_business_declaration.mjs` using
the original page module and its default choices. It preserves a recommended
disease relationship without selecting an ambiguous prediction transcript, and
does not silently choose a relationship which the page leaves unconfirmed.

For a bounded flow repair, `-Dvarvis.pm2.multigene.replayPopulation=<saved-response>`
replays retained actual population evidence in the isolated test runtime; only
missing alleles reach its real provider. Set
`-Dvarvis.pm2.multigene.expectedNewPopulationBatches=<expected-count>` to check
the query budget (default 1). This is test-only reuse with source/policy identities
retained, not a production cache or a fresh-resource claim. The resume directory's
saved response remains the before/after comparison. Report changes caused by
corrected declarations separately from changes in scientific evidence.

### Bounded gnomAD timeout diagnosis and saved-task retry

BA1 and PM2 use the same explicit business query budget. The ordinary ranking
`VARVIS_GNOMAD_TIMEOUT` does not set this budget. Optional local properties are:

```properties
variant-analysis.acmg-business.gnomad-timeout-seconds=60
variant-analysis.acmg-business.gnomad-total-timeout-seconds=120
variant-analysis.acmg-business.gnomad-max-attempts=2
```

The defaults are 60 seconds per attempt, 120 seconds per original resource group,
and at most two attempts per locus. A failed multi-position group is split once
into two smaller retry groups sharing the remaining budget; with the defaults,
this can mean three subprocesses for one provider batch. A single position is
retried without splitting. Independent successes survive failures. Cleanup of
this invocation's own processes is bounded separately. Truncated output and
stderr errors cannot become successful absence. Legacy non-business callers
retain one attempt unless explicitly configured otherwise.

Saved responses include `pm2QueryDiagnostics` and `ba1QueryDiagnostics`: resource
filename, query digest, locus count, effective budget, retry phase, timings,
exit/status, byte counts, and a bounded redacted stderr summary. These describe
transport execution, not per-allele evaluation latency or new model calls.

An explicit retry may retain successful PM2 HIT observations from its direct
predecessor after saved-run, source, allele, resource, profile, data-class and
atomic-identity verification. It rebinds the observation to the new context and
evaluates PM2 again; no previous disease conclusion is copied. `populationReuse`
identifies the source run/evidence. Failures and NO_RECORD are not reused by this
path. BA1 retains its existing independently verified successful-result reuse.

`GnomadTimeoutExportProbeTest` is an opt-in real population query check using
retained confirmed contexts, **not** a trusted source import or HTTP acceptance.
Set `varvis.gnomad.timeout.real=true`, `varvis.gnomad.timeout.export`,
`varvis.gnomad.timeout.exportSha256`, `varvis.gnomad.timeout.tabix`, and
`varvis.gnomad.timeout.output`. An optional `populationReplay` under the same
prefix accepts this probe's identity-checked successful population carriers;
failures are queried again. Its output identifies the original run as
`sourceRunId`, never as a new saved business run.

`GnomadTimeoutSavedTaskSmokeTest` restores a **separately authorized, read-only
single-task SQL export** into a new file H2 database. The test itself has no
production database connection. It preserves the actual formal ranking,
declaration and source snapshot, verifies the saved run, performs an explicit
retry with real population queries, and checks GET, repository/service rebuild,
old-run preservation and repeat POST with zero new calls. To avoid rerunning
predictions, its test-only coordinator boundary retains the original validated
prediction dispatch and source receipts; recommendation references are checked
against the unchanged policy, catalog, allele and clinical annotation. This is
population-flow acceptance, not a fresh prediction or page acceptance.

Select only that test and set `varvis.gnomad.timeout.savedTask=true`,
`varvis.gnomad.timeout.taskExport`, `varvis.gnomad.timeout.taskExportSha256`,
`varvis.gnomad.timeout.export`, `varvis.gnomad.timeout.exportSha256`,
`varvis.gnomad.timeout.output`, `varvis.gnomad.timeout.originalStorageRoot`,
and `varvis.e4b3.resourceRoot`. The export is pinned by SHA-256 and contains only
the selected task's job, engine metadata, formal Top-20, source snapshot, and
specified run. Keep these private files in the ignored acceptance directory;
do not commit them or substitute another task's saved evidence. Neither test
starts Exomiser, VEP, AlphaMissense, SpliceAI or an existing listening service.

For a subsequent retry after an isolated run, optional
`varvis.gnomad.timeout.priorDatabase`, `priorDatabaseSha256`, and `priorRunId`
under the same prefix import that exact run from a read-only copy of its closed
H2 file. The prior file and records remain unchanged. This uses the production
successful-observation reuse path, not probe-only population injection.
