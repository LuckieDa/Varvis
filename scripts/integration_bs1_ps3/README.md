# Joint BS1 / PS3 integration acceptance

`network_acceptance.py` evaluates one retained public formal analysis in a new,
private loopback instance. It uses the production controller and measured HTTP
responses. It does not create an analysis, copy a database, start a service or retry
a failed phase. Each of the four panels keeps its own analysis, database and
original candidate order: BS1 (12 rows), PS3 (6), HNF1A PS1/PM5 (6), PVS1 (8).

Before launch, verify the source database is offline or use an already verified
consistent backup. Preserve the original logical published-jobs path for source
identity. New scientific work and functional reviews must use the instance's
private working directory. The launcher and runtime source must be committed.
The reported `businessWorkingRoot` must resolve to exactly
`<instance-root>/business-work`, outside the original published jobs directory.
Original saved results and review records remain read-only. A copied review chain
does not authorize new decisions on an old run.

The private source inventory is a JSON object keyed by `bs1`, `ps3`, `ps1-pm5`
and `pvs1`. Each entry has `dataset`, `analysisId`, `topN`, `databaseSha256`, `jobs`,
`formalOptions`, `historicalRuns` and `maxCounters`. A file receipt contains
`path`, `bytes` and `sha256`; `formalOptions` is a receipt for the original full
HTTP input-options response. Each historical run has `runId`, a `calculation`
receipt and, where applicable, a `reviews` receipt for the functional review
export. All original JSON comparisons preserve decimal precision.
Each historical review export also declares `expectedExternalReviewCount`.
The PS3 entry includes all eight `functionalReviewFiles` receipts with safe
`relativePath` values and `externalReviewTotal: 8`. Before evaluation the client
checks that the copies under `business-work/functional-experiment-reviews`
remain byte-identical to those exact source snapshots. It never copies them.

Freeze a nonnegative integer `maxCounters` budget before calling the client. It
must include `provider`, `evaluator`, `vepComparator`, `spliceRawBatch` and
`pm2AllSitesBatch`. Exomiser, publication VEP and blocked model launches must stay
zero. Reference comparison VEP and SpliceAI may have an explicit bounded budget.
Additional observed counter limits can be included.

Run from the committed integration worktree, supplying the same common arguments
to each phase:

```text
python -B -S -X utf8 scripts/integration_bs1_ps3/network_acceptance.py evaluate bs1 --source-inventory <private-inventory.json> --acceptance-root <private-runtime-root> --instance-id <instance-UUID> --head <full-runtime-commit> --port <private-port>
```

For PS3, run `review ps3` after `evaluate ps3`. It creates three explicitly
synthetic events on the new joint run: NONE, ACCEPTED and REJECTED; verifies
idempotency, stale predecessor rejection and rejection of accepting the two
unevaluated records. These are engineering actions, not user or expert decisions.

After separately restarting the same task-owned instance, run `reuse <dataset>`.
It requires a new process ID, unchanged instance/configuration/source identity,
identical saved calculation bytes, zero scientific calls and unchanged historical
results. PS3 also requires the new review export to survive reconstruction.
The new integrated page remains unaccepted by this backend script.

Offline checks:

```text
python -B -S -X utf8 -m unittest scripts.integration_bs1_ps3.test_network_acceptance -v
```
