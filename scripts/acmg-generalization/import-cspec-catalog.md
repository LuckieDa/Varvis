# Official CSpec BA1 catalog importer

`import_cspec_catalog.py` captures the official public CSpec directory, resolves
the published version of every selected specification, and compiles a candidate
`a01-ba1-cspec-snapshot-v1`. `cspec_ba1_semantics.py` owns the finite BA1 text
grammar. Neither script activates a runtime policy or performs variant queries.

## Capture and replay

Use a new task archive directory for `capture`. The default limit is 128 MiB,
128 requests, and 8 MiB per CSpec response. The existing `fetch_source.py` owns
atomic receive reservations, exact raw bytes, SHA-256, cancellation accounting,
and redirect refusal. There are no automatic retries. Failed captures, partial
responses, and their ledgers remain available as evidence.

The actual service returns HTTP 404 beyond its declared entity count. Completeness
therefore requires count-bounded pages, unique IDs, equal full metadata across
two enumerations, stable service/type counts, and exact revision matching for
the full selected content. Never-released, replaced, and explicitly retained
legacy entities are excluded according to the official registry API filter.
Raw response timestamps remain distinct; they are not fabricated as one server
snapshot time.

`complete-releases` preserves that capture and writes an additional manifest.
For each editable current graph it enumerates version URLs and requires a unique
published archive matching the original Released event and specification ID.
Archive URI versions and scientific versions are recorded separately. At most
10 archives per specification are attempted, under the remaining shared capture
budget. A final directory enumeration must still agree. The same supplement
captures the complete official HGNC TSV, with a 24 MiB response ceiling within
the remaining budget. Only approved current HGNC symbols and their declared
Ensembl/Entrez IDs become mappings; withdrawn records and aliases are not used.

Replay pins the chosen complete manifest, checks every consumed raw response,
rechecks all page counts/identities and selected release bindings, and derives
the mappings again. Missing archives or inconsistent raw evidence prevent a
new snapshot. `compile` requires a previously nonexistent output path and uses
an atomic JSON write. A failed/new candidate never updates an earlier snapshot.

```text
python -S import_cspec_catalog.py capture --directory <new-task-directory>
python -S import_cspec_catalog.py complete-releases --directory <capture-directory> --capture-sha256 <base-manifest-sha256>
python -S import_cspec_catalog.py compile --directory <capture-directory> --manifest supplements-v1-manifest.json --capture-sha256 <complete-manifest-sha256> --semantic-review <source-review.json> --semantic-review-sha256 <review-sha256> --output <capture-directory/new-candidate.json>
python -S import_cspec_catalog.py publish --candidate <candidate.json> --candidate-sha256 <candidate-file-sha256> --review <independent-batch-review.json> --review-sha256 <trusted-review-file-sha256> --output <existing-resource-directory/new-version.json>
python -S -m unittest -v test_import_cspec_catalog
```

## Semantic boundary

Each entry preserves an actual gene/disease/inheritance association. The importer
does not create a Cartesian product. Missing inheritance or versioned transcript
scope stays unresolved; it does not imply unrestricted applicability. An empty
HGNC set normally remains an unresolved global scope. Only agreeing explicit
mitochondrial geneType fields in both RuleSet and BA1 can narrow it to
`sequenceScope=MITOCHONDRIAL`, `contigs=[MT]`, with both original source pointers.
No HGNC or disease identifier is invented for that case.

`GENERAL`, `THRESHOLD`, and `DISABLED` require complete recognized active
semantics. Empty text, original-ACMG templates, unrelated inactive strength
templates, contradictory disposition, unrecognized instructions, attachments,
or additional operators produce `UNSUPPORTED`. Only complete, closed templates
of known publication metadata are classified as non-BA1 prose; absence of the
word BA1 is insufficient. Full clauses, ancillary source text, references, and
raw pointers are preserved for review.

The scalar family supports a single lower frequency bound and same-population
AN requirement of at least 2000. Source `>2000` remains strict `>2000`.
Lower AN requirements are unsupported, never silently increased. Percent and
fraction spellings must agree. An explicitly reviewed calculated-MAF migration
retains its original threshold, complete calculation explanation, source SHA,
review ID, and distinct derived policy version. Arbitrary MAF thresholds and
pathogenic-comparator derivations are not migrated automatically. Unrecognized
cohort, ancestry, prevalence, exception, or cross-criterion conditions stay gaps.

The snapshot distinguishes entity count, selected published-specification count,
flattened entry count, and approved HGNC mapping count. `status=COMPLETE` means
capture completeness; `semanticsComplete=false` reports remaining clause gaps.
Every generated candidate has `metadata.batchReview.status=PENDING`. A pinned
source derivation review is not an independent review of the finished batch,
clinical sign-off, or permission to enable policies.

## Reviewed publication and normal updates

`publish` consumes a separate `a01-ba1-cspec-batch-review-v1` receipt, pinned by
an externally trusted file SHA-256. A hash computed by the author or a reviewer
label by itself is not proof of independent review. The controlling workflow
must obtain that pin from the completed independent review. The publisher never
writes or approves its own review receipt.

The required review is `status=REVIEWED`, with
`reviewScope=FINITE_BA1_COMPILER_AND_COMPLETE_RELEASED_CATALOG`. Its `candidate`
object binds the original candidate file SHA, compiler version, capture manifest
SHA, importer source SHA, and semantic compiler source SHA. The reviewer role is
`INDEPENDENT_SOURCE_AND_SEMANTICS_REVIEW`; the receipt includes a review ID, a
timezone-qualified review time, and the source-integrity receipt SHA. Unknown
execution fields are rejected. Optional `metadata` is non-executing audit
information; outstanding approval conditions require a non-REVIEWED status.

The publisher validates the candidate's internal seal and pending/complete
state. It changes only `metadata.batchReview` and the top-level internal seal.
All entries, mappings, source identities, original generation time, and scientific
boundaries remain equal to the reviewed candidate. It writes a complete sibling
staging file, then uses an atomic no-replace hard link to publish the new path.
This also rejects a target created concurrently after the initial existence
check. Failed staging evidence is retained; old snapshots and the candidate are
never overwritten. The destination directory must already exist on a filesystem
that supports that atomic operation. A failure is not retried using a weaker
replacement operation.

Keep published snapshots and their raw capture/review evidence in the controlled
external resource/archive directories, outside Git. A normal update repeats
capture, released-version resolution, compile, independent review, and publish;
it does not require a hand-authored policy package for each gene. Configure the
new file through `VARVIS_ACMG_BA1_SPECIFICATION_CATALOG_FILE` and its **external
file SHA-256** through `VARVIS_ACMG_BA1_SPECIFICATION_CATALOG_SHA256`, preserving
the existing explicit BA1 enable/disable setting. The service loads one immutable
catalog at startup: changing a file or these settings does not hot-reload it.
Use the normal authorized restart process to adopt a new version. This command
does not edit configuration, restart services, or activate a catalog.

Internal `sha256` uses Python sorted-key, compact UTF-8 JSON without ASCII
escaping and without the top-level `sha256` field. Numeric spellings are Python
JSON spellings; downstream consumers should pin the external file SHA-256
instead of assuming a different JSON canonicalizer emits identical numbers.
Source references name the actual captured batch/archive URL and its raw SHA,
with the JSON pointer locating the specification inside that response.

Official API documentation:
<https://genboree.org/gitlab/clingen/cspec/cspec-registry/-/wikis/CSpec-Registry-API>
and its `Versions` page. HGNC download entry point:
<https://www.genenames.org/download/>.
