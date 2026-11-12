# Masked public panel stratification

`stratify_public_panel.py` implements the mechanical sampling contract
`PUBLIC_ALLELE_FINAL_STRATIFICATION_CONTRACT_V1.json` v1.0.0, frozen at commit
`d2bd980` (file SHA-256
`05438b76236d0e411a6ad0ecd06ac60ec9c8778c0d139872e09c89a98595e40f`).
It does not run VEP, prediction models, evidence rules, a server, network
requests, or reference FASTA queries. It is a task tool, with no default
business integration. Its tests contain invented identities only.

Version 1.1.0 additionally requires the prospectively frozen
`FROZEN_ANCHOR_IDENTITY_RESOLUTION_CONTRACT_V2_1.json` version 2.1.0
(commit `43f6af6`, file SHA-256
`4e533b2cc0c3d40c2509eaff4400cf63e1adfb32b293d0577c782a149735eb20`).
Its closed operational contract and prior completion-contract identity are
checked alongside the original stratification contract. Neither contract changes
the twenty-gene frame, ten slots per gene, seed, strata, ranking or quotas.

The fixed contract is passed by path and expected byte SHA. Its operational
sections are additionally bound to this implementation's canonical semantic
SHA; unknown contract requirements fail closed. The original frame supplies
the gene order, disease/MOI identities, exposure roles and unchanged 20 × 10
quotas. The seed and stratum order are checked. No score or ClinVar
classification determines selection. Original `MC` is retained as source
provenance and never read to infer consequence.

## Inputs

All flags are required. Paths are explicit; no filesystem discovery, source
download or resource substitution occurs.

```text
python -I -S -B -X utf8 scripts/acmg-generalization/stratify_public_panel.py \
  --contract <fixed-contract.json> --contract-sha256 <reviewed-file-sha256> \
  --frame <original-frame.json> --query-plan <fixed-query-plan-v2.json> \
  --normalization-plan <fixed-normalization-plan-v1.0.1.json> \
  --pool-manifest <original-pool-manifest.json> \
  --projection <sampling-projection.jsonl> --occurrences <query-occurrences.jsonl> \
  --normalization-manifest <complete-normalization/manifest.json> \
  --normalization-manifest-sha256 <reviewed-execution-manifest-sha256> \
  --models-manifest <native-models/manifest.json> \
  --model-source-review <independent-model-source-review.json> \
  --geneinfo-bridge <reviewed-geneinfo-identity-bridge.json> \
  --geneinfo-bridge-sha256 <reviewed-bridge-file-sha256> \
  --population-header-receipt <historical-af-header-receipt.json> \
  --prerank-manifest <original-private-prerank-manifest.json> \
  --anchor-projection <reviewed-masked-frozen-anchor-identities.json> \
  --anchor-projection-sha256 <reviewed-anchor-projection-sha256> \
  --anchor-resolution-contract <fixed-completion-contract-v2.1.json> \
  --anchor-resolution-contract-sha256 <reviewed-contract-sha256> \
  --prior-anchor-projection <unchanged-original-projection-v1.json> \
  --source-discovery-projection <reviewed-complete-twenty-probes.json> \
  --source-discovery-projection-sha256 <reviewed-projection-sha256> \
  --source-discovery-prepare <original-twenty-probe-prepare-manifest.json> \
  --source-discovery-vcf <original-synthetic-discovery.vcf> \
  --task-output-root <controlled-task-directory> --output <new-direct-child>
```

The expected SHAs are trust inputs supplied after independent review, not
authentication derived by merely rehashing an arbitrary caller file. The
normalization manifest and every referenced artifact must match. Source
projection/occurrence records are loaded by the existing normalizer's strict
masked loader, then reconciled with every normalized source and canonical
alias. A failed/incomplete normalization cannot authorize prefix sampling.
The normalizer's existing full-FASTA checksum receipt remains its reference
identity; this tool only preserves that evidence. It does not compute another
whole-file checksum or relabel local sequence hashes as one.

Exactly one reviewed versioned native Select transcript model is required per
frame gene. Model byte/canonical/CDS hashes and the independent RefSeq/exon
source review are cross-bound. The source-only GeneID bridge additionally
binds symbol + numeric GeneID to HGNC and that same versioned RefSeq source.
Missing, conflicting or incorrect source gene identities remain unresolved.
The bridge is independently pinned by its file SHA; it does not decide
consequence or clinical applicability.

Version 1.1.2 separates that source-identity uncertainty from a provable
exclusion outside the already fixed mechanical frame. Only a native canonical
SNV with matching reference and local-haplotype verification may be excluded
when its point is outside **all twenty complete physical model spans**. The
closed spans include UTR exons, intervening introns and both endpoints; every
model on the same chromosome is checked. A partial model set, non-SNV or any
intersection leaves the source issue unresolved. The exclusion retains the
original unknown gene status, every source/alias record, normalization locator
and all twenty model hashes and intervals. It assigns no gene or quota credit
and makes no clinical applicability claim. No sampling seed, quota, ordering,
source metadata, scientific contract or previously written result is changed.

The command requires `masked-frozen-anchor-identities-v2`, including the
unchanged original v1 projection as an independently pinned comparison input:

- `bindings`: original `frameSha256`, `prerankManifestSha256` and
  `priorProjectionSha256` from the completion contract.
- `sourceFiles`: exact `{file, sha256}` identities from the private prerank
  manifest. The separately frozen PTEN independent-reference input identity
  is also allowed; this is a source allowlist, not a gene-specific algorithm.
- `records`: exact original DNA/source keys, gene/disease/MOI, source
  file/hash/locator, original date and criterion-specific exposure roles.
- `supplementaryRecords`: exact original anchors outside the fixed frame,
  retaining nullable original HGNC/MONDO and original MOI/roles.
- `unresolvedAnchors`: original protein-only identities and reasons. Original
  missing HGNC/MONDO values stay null; even out-of-frame anchors remain in
  this supplementary set.
- `identitySources` / `identityResolutions`: source-only DNA resolution of
  previously frozen protein-only anchors; never a new selection anchor.
- `sourceCoverage`: per-file in-frame plus supplementary exact-DNA-role and unresolved-protein counts,
  plus a nonempty source-enumeration review string.
- `review`: `REVIEWED_SOURCE_IDENTITY_PROJECTION`, reviewer/time,
  `allFrozenAnchorsAccountedFor=true`, and `exactDnaIdentityComplete` matching
  the enumerated unresolved set.

This review records complete source transcription, not clinical signoff.
The tool does not open heterogeneous original assay/classification files;
it preserves their exact expectation locators and original denominator roles.
Separate source roles for the same original allele are all retained.
Every prior exact object must remain unchanged. Every prior protein-only role
must remain unresolved or have one uniquely linked source resolution, with its
original context/date/expectations retained. Original `c.`/`p.` strings are
accepted unchanged; an accession prefix, when present, must match the separately
versioned transcript/protein exactly. A new source-resolution date never replaces
the original exposure date. Source resolution leaves REF, normalization and
mechanical validation pending.

An outside-frame reviewed SNV may be proven disjoint only against **all twenty**
validated models' closed physical spans: each minimum exon genomic start through
maximum exon genomic end, including all UTR exons and intervening introns. Both
endpoints intersect; another model on the same chromosome may block the proof.
No distance cutoff, gene-name inference, coding-only interval or partial model
set substitutes for this check. An indel or missing identity/model stays pending.
All twenty model hashes and intervals are recorded. This gives no REF,
normalization, mechanical stratum, source-pool absence or quota credit.

## Source-discovery exposure

The required projection schema is
`reviewed-source-discovery-exposure-projection-v1`, version 1.0.0. Its closed
top-level keys are `schemaVersion`, `projectionId`, `version`, `review`,
`bindings`, `probeCount`, `records` and `boundaries`.
The review must attest `REVIEWED_COMPLETE_SOURCE_DISCOVERY_IDENTITIES`, with
review ID, reviewer/time, `allOriginalProbesAccountedFor=true`,
`originalReferenceReadReceiptsVerified=true` and `clinicalSignoff=false`.

`bindings` pins six original SHA-256 values: `probeManifestSha256`,
`inputVcfSha256`, `executionStartSha256`, `executionFinishSha256`,
`transcriptExportSha256` and `vepOutputSha256`. The separately reviewed projection
retains the already verified execution/output source chain. This sampler reads
only the bounded original prepare manifest and VCF, checks their SHA-256 values,
and matches all twenty original IDs, exact GRCh38 SNVs and archived one-byte
REF receipts. It does not rerun a reference query, VEP, or annotation parsing.

Each record has exactly `probeId`, `allele`, `canonicalKey`, `sourceRole`,
`referenceValidationStatus`, and `sourceLocators`. The allele uses the five
assembly/contig/position/reference/alternate fields. The key is the unchanged
five-field SNV identity, not public-panel normalization evidence. The role is
`SYNTHETIC_TRANSCRIPT_SOURCE_DISCOVERY_ONLY`; validation status is
`MATCH_FROM_ARCHIVED_SINGLE_BASE_READ`. Locators contain `prepareProbeIndex0`,
`vcfDataRow1`, and the original five-field `referenceRead` receipt. Its original
byte must decode to exactly one ASCII A/C/G/T/a/c/g/t base and equal the source
REF after case normalization. The original soft-mask byte/hex remains unchanged;
N, multiple bytes and non-ASCII values are rejected. `boundaries` must be exactly selection effect
`NONE`, zero criterion evaluations and false clinical acceptance.

Every candidate receives actual `MATCHED` or
`NO_MATCH_IN_COMPLETE_PINNED_PROBE_SET`, matching probe IDs, and source pins or
a manifest locator for those shared pins. The join uses the proven canonical
public allele, including REF and ALT. It does not use gene name or raw alias as
a substitute. Missing, incomplete or inconsistent probe inputs reject final
selection. Exposure never filters, replaces, reprioritizes or credits a public
candidate and never changes its original criterion/held-out role. Archived
reference verification is explicitly separate from `freshReferenceRead=false`.

## Mechanical behavior and output

Native mapping uses the existing continuous CDS-relative exon convention
from `CodingTranscriptMapping` and the standard code/reverse-complement
helpers from `build_transcript_model.py`. All physical exons, including UTR
exons, retain their ordinals. Canonical alleles come from the already reviewed
bcftools normalization; prefix/suffix trimming only locates the changed event.
Coding REF bases are independently checked against the model CDS. Both
insertion flanks must be in the same coding exon. Minus-strand payloads and
insertion indices use transcript biological order.

The contract's final-coding-exon / penultimate-last-55-base challenge precedes
internal stop/frameshift assignment. It is a sampling definition, not an NMD
cutoff. Start/native-stop SNVs are excluded from those codon strata. Coding
frameshift length changes at those sites remain mechanical frameshifts, with
explicit challenge flags and separate pending PTC/NMD derivation. There is
no filtering for convenient PTCs, tolerated exons or successful evidence rules.

Historical `AF_ESP`, `AF_EXAC` and `AF_TGP` are exact finite decimal sampling
observations. Absent, empty and dot values stay unavailable; zero is observed
zero. Malformed eligible synonymous observations leave the highest-AF slot
unresolved. Alias discordance is retained; the declared maximum operation
does not average or adjudicate conflicting values. These fields never become
gnomAD, FAF, BA1, BS1 or PM2 evidence.

The fixed hash is SHA-256 of UTF-8 `seed + LF + gene + LF + stratum + LF +
canonicalKey`, without a final LF. Ranking is repeated from canonical keys.
Genes receive globally distinct credit in original frame order. Missense
uses at most two eligible prior anchors and at least two candidates outside
the entire resolved frozen-anchor universe. Synonymous slots retain their
separate maximum-AF and remaining-hash operations. No quota is transferred.

Outputs preserve source accounting, all candidate mappings/exclusions, complete
hash orders, selected slot records, missing slots, and every original anchor.
Version 1.1.1 changes only their storage encoding. All seven JSONL artifacts are
streamed as single-member `.jsonl.gz` files with compression level 6, `mtime=0`
and an empty gzip filename. Decoded bytes remain the exact canonical UTF-8 JSON
records, in the original order, each followed by LF. Compression is deterministic
for the same Python/zlib implementation and input bytes. No original source or
normalization locator, selection, quota, seed, AF value or anchor is rewritten.

Each `artifactIdentities` entry for JSONL provides `logicalFile` (for example,
`source-accounting.jsonl`), physical `file` (`source-accounting.jsonl.gz`),
`encoding="gzip"`, `decodedEncoding="UTF-8"`, `recordDelimiter="LF"`, and
`encodingComplete`. Top-level `bytes`/`sha256` identify the compressed file.
Only a completely written and independently decoded single gzip member has
`encodingComplete=true` and `decoded={bytes, sha256, rowCount}`. Header, body,
CRC and trailer all pass through the unchanged output byte budget. Full EOF,
CRC, decoded digest/size and LF row count are checked before completion; a
second member or trailing garbage is rejected. Failed artifacts instead retain
`encodingComplete=false` and may include `attemptedDecoded` progress. This is
not a verified saved-prefix identity or permission to use a partial panel.

Locators inside records keep their original logical filenames. Resolve a logical
name through the unique corresponding manifest entry, then use the decoded
JSONL record IDs or one-based LF line numbers; these are never compressed byte
offsets. `resolve_jsonl_artifact(root, manifest, logical_name)` checks containment,
both physical and decoded identities, and complete single-member gzip framing
using bounded 64 KiB streaming buffers before returning the physical path.
It verifies the artifact only: callers must separately require the manifest's
panel/finality flags for sampling use. The `serialization` manifest object
records this encoding and locator convention. Plain `RUNNING.json` and final
`manifest.json` stay within the same total output cap and reserved manifest space.

`quotaComplete` reports the mechanical 200-slot count separately from anchor
or source reconciliation. Any unresolved source/CDS, in-frame frozen anchor or
outside-frame identity lacking the permitted disjointness proof blocks
`panelComplete` and `selectedRecordsFinal`. A quota-complete panel with only
proven-disjoint supplementary work pending may have those two flags true while
`allFrozenAnchorsReconciled=false` and overall `status=INCOMPLETE`. Its exact
supplementary REF/normalization/mechanical reasons remain explicit. Overall
`COMPLETE` requires both panel completion and all original anchor reconciliation.
Failed/cancelled prefixes are retained but never
become an authorized final panel. A `COMPLETE` panel still has pending
independent rule expectations and no clinical or formal TopN acceptance.

Input bytes are cumulatively capped at 512 MiB, including original masked
sources, already generated normalization artifacts and model/identity files.
Outputs are capped at 256 MiB, including a 1 MiB reserved final manifest.
Original projection and occurrence row limits remain separately 150,000,
with 1 MiB per line. Output must be a new direct child of the declared local
task root. Nothing is deleted or overwritten. Exit 0 means complete mechanical
selection; exit 2 means a recorded gap/failure or preflight refusal.

Run the focused synthetic tests with:

```text
python -I -S -B -X utf8 scripts/acmg-generalization/test_stratify_public_panel.py -v
```

The synthetic contract fixture preserves the reviewed operational text with
invented input identities. Its 200-row test checks accounting and mechanics;
it provides no public-panel, resource, clinical or held-out acceptance credit.
