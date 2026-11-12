# Explicit REVEL publisher index observation adapter

`RevelPublisherIndexObservationProvider` is an optional Java callback for
`ReviewedPredictionBundle.ObservationProvider`. The explicit reviewed-prediction
Spring configuration can register it; default configuration and the historical HNF1A resource remain unchanged. It changes no score
threshold, policy selection, transcript confirmation, or clinical classification.

## Load and bind

A trusted caller supplies the index root, existing local tabix executable, a
separately reviewed receipt, and both the receipt's raw-file and canonical JSON
SHA-256 values to `RevelPublisherIndexObservationProvider.load(...)`.
`resourceBinding()` returns exactly:

```json
{
  "resourceId": "REVEL_PUBLISHER",
  "resourceVersion": "1.3",
  "identityFingerprintSha256": "<canonical SHA-256 of the reviewed receipt>",
  "sourceArtifactSha256": "<SHA-256 of the entire uncompressed publisher CSV member>"
}
```

The reviewed clause policy must pin this binding. After policy selection, the
existing bundle constructs the request and invokes the callback. The callback
rejects other preselected models before launching tabix. It also checks the
complete request identity, confirmed clinical/transcript context, native GRCh38
single-nucleotide REF/ALT, exact model ID/version/canonical hash, shared coding
model validation, and reviewed unversioned source ENST bridge.

The receipt has this closed schema; placeholder values below are not approvals:

```json
{
  "schemaVersion": "reviewed-revel-publisher-index-v1",
  "adapterId": "revel-publisher-index-observation",
  "adapterVersion": "1.0.1",
  "review": {"status": "REVIEWED_INDEX_INTEGRITY", "reviewId": "<independent mechanical review ID>"},
  "resourceId": "REVEL_PUBLISHER",
  "resourceVersion": "1.3",
  "manifest": {"file": "manifest.json", "bytes": 1, "mtimeNs": 1, "sha256": "<raw manifest SHA-256>"},
  "artifacts": [
    {"file": "revel-grch38.lookup.tsv.gz", "bytes": 1, "mtimeNs": 1, "sha256": "<complete BGZF SHA-256>"},
    {"file": "revel-grch38.lookup.tsv.gz.tbi", "bytes": 1, "mtimeNs": 1, "sha256": "<complete TBI SHA-256>"},
    {"file": "revel-unlocated-source-rows.tsv.gz", "bytes": 1, "mtimeNs": 1, "sha256": "<complete excluded BGZF SHA-256>"}
  ],
  "tool": {"bytes": 1, "mtimeNs": 1, "sha256": "<existing tabix executable SHA-256>", "version": "<exact builder-recorded version output>"},
  "producer": {"version": "1.0.1", "sha256": "<reviewed build_revel_index.py SHA-256>"},
  "clinicalAcceptance": false,
  "defaultEnabled": false
}
```

Use actual positive byte lengths and last-modification timestamps in nanoseconds.
There are no absolute paths in this receipt; paths are private factory arguments.
The loader requires the complete builder 1.0.1 manifest with every mandatory
field, successful full ZIP/member/BGZF/TBI validation, complete row accounting,
and the original false native-reference/native-CDS/classification/default flags.
Missing or unknown fields, invalid numeric types/ranges, changed hashes, or
changed resource metadata reject the load. Duplicate JSON keys and trailing JSON
documents are rejected. Small JSON reads are capped at 2 MiB, TBI hashing at
64 MiB, and executable hashing at 20 MB.

The full derived BGZF and excluded BGZF hashes come from the independently
reviewed complete build. They are not recomputed for every query. Their size,
mtime and available filesystem file identity, plus manifest/TBI/executable
metadata, must still match at load and immediately before and after each query.
This is an explicit trusted immutable-resource contract, not protection against
an adversary rewriting a large artifact while preserving all metadata.

## Query and evidence boundaries

One exact coordinate is queried with a 15-second deadline. The dedicated runner
uses the existing `ExternalProcessRunner` interface and owns only its child and
two drain threads. Nonzero exit, stderr, timeout, interruption, read failure,
more than 4 MiB stdout, incomplete final line, non-ASCII content, more than 1,000
coordinate rows, or malformed source identity produces `UNAVAILABLE`; no prefix
becomes a successful observation. An empty complete matching set alone produces
`NO_RECORD`, which supplies no score and is never AF=0 or benign evidence.
Cancellation is checked after transport, during row parsing, and before returning
successful evidence; the caller's interrupt flag remains set and no partial
records or score survive a cancelled lookup.

Every returned coordinate row is parsed and retained, with exact original CSV,
one-based data-row ordinal (header excluded), CSV SHA-256 excluding its original
single LF, and the full publisher member SHA. Exact native query REF/ALT and
the preselected source ENST determine the matching subset. Distinct rows with
identical measurements, repeated rows, and conflicting measurements all remain.
Repeated or contradictory source ordinals, unresolved DNA identity, and
unresolved transcript tokens on the matching allele cannot produce partial evidence. Only the documented
semicolon-separated unversioned ENST tokens are accepted.

Scores are never averaged, maximized, or used to choose a transcript/model.
A numeric evidence measurement is projected only when every matching source
row has the same valid score. Conflicts or malformed scores retain the full
matching raw records with no measurement; the existing strict
`RevelPredictionObservation` then refuses the rule. That runtime still rebuilds
the native CDS/protein change and checks stored VEP facts before evaluating any
reviewed threshold. Publisher liftOver coordinates and unversioned ENST tokens
alone establish neither native reference validity nor version equivalence.

Output carries source, derived artifact and review hashes but no source, index,
tool or machine paths. A context/model containing an absolute local path is
rejected before copying it to the output. A separately reviewed portable model
must have its own version and canonical policy binding; this adapter does not
redact a pinned model in place.

## Focused verification

`RevelPublisherIndexObservationProviderTest` now generates its own tiny synthetic
BGZF/index using HTSJDK when no external fixture is specified. Only its external
executable test remains conditional. Optional `VARVIS_REVEL_ADAPTER_FIXTURE` and
`VARVIS_REVEL_TABIX` retain the previous fixture mode. The fixture must be a tiny
completed builder 1.0.1 output with `SYNTHETIC_ONLY.txt` equal to
`SYNTHETIC_REVEL_ADAPTER_FIXTURE_ONLY`; each copied artifact must be under 1 MB.
No test reads the production publisher ZIP, full index, patient data, or held-out
panel. `BoundedTabixProcessRunnerTest` creates only owned Java child processes
to test output limits, nonzero exit, stderr, timeout and interruption cleanup.
Use these two classes with `RevelPredictionObservationTest` and
`ReviewedPredictionBundleTest` for the affected contract regression.

## Local Java transport (2026-10-10)

`loadLocal(mapper, indexRoot, historicalReviewFile, rawReviewSha, canonicalReviewSha)`
uses the same reviewed index, exact request checks, raw-row parser and CDS/VEP
association. In Spring, explicitly select
`variant-analysis.acmg-business.revel-publisher-transport=LOCAL_HTSJDK` together
with the existing reviewed-prediction bundle and index/review properties. No
`revel-publisher-tabix-executable` is needed for this mode. The default remains
`EXTERNAL_TABIX`; unknown transport values leave REVEL unavailable and do not
fall back to another tool. Merely setting this property grants no policy, model,
resource or clinical approval.

The historical receipt is immutable. When current size matches but mtime differs,
local initialization computes the complete artifact SHA and checks stable
before/after metadata against that receipt. Mismatched bytes reject loading.
A bounded process-local cache reuses only successful hashes with identical real
path, size, nanosecond mtime, filesystem identity and expected SHA. Query-time
metadata checks remain mandatory; this retains the existing trusted immutable
file assumption, not protection against adversarial same-metadata rewriting.
The build's tabix SHA/version is retained as provenance but its executable is
neither opened nor run. Initial hashing is separate from point-query latency.

`RevelLocalIndexQuery` uses existing HTSJDK 2.24.1, one exact coordinate, a
15-second cooperative deadline, 8 MiB of compressed data reads, 1,000 returned
rows and the original 4 MiB output bound. Checks surround synchronous local I/O;
the deadline is not a hard operating-system cancellation of a stalled disk read.
Handles are owned and closed; no worker process or network access is created.
The index is SHA-verified before parsing. Query output retains native REF/CDS
qualification boundaries and conflicting rows. Complete empty matches mean
`NO_RECORD`, never a benign score. `physicalLocalQueries` and completed compressed
read bytes are distinct from `physicalTabixCalls`; incomplete read bytes are null.

The local transport identity participates in router/business cache binding.
`RevelLocalIndexObservationProviderTest` covers actual synthetic indexed reads,
relocation and corruption, cancellation, empty queries, timeout, unchanged
CDS/VEP refusal and configured reviewed routing. A real public control was
separately checked in the dated execution report; tests do not automatically
read the full production index or run patient analysis.
