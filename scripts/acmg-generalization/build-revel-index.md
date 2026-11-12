# REVEL publisher GRCh38 lookup builder

`build_revel_index.py` derives a local BGZF/TBI lookup from the complete pinned
REVEL 1.3 ZIP. It does not activate a provider, select a prediction model, apply
a threshold, or establish native REF/CDS/transcript compatibility.

The publisher's GRCh38 coordinates were obtained by liftOver. The manifest
retains `sourceMappingMethod=REVEL_publisher_liftOver`, original GRCh37 position,
unversioned transcript identity and `nativeReferenceVerified=false` /
`nativeCdsVerified=false`. `publisherLookupReady=true` means only that this
complete publisher lookup passed construction checks. It is not clinical or
runtime readiness.

## Inputs and fixed format

Supply exact ZIP byte length and SHA256, its sole member name, member byte length
and CRC32. The full ZIP is hashed before streaming. The member is completely
consumed and its size, CRC32 and SHA256 computed; `zipfile` also verifies CRC at
EOF. An optional previously known member SHA256 is checked. If it is omitted,
the manifest says `COMPUTED_FROM_FULL_SHA_PINNED_ZIP`, never that an external
member SHA was independently matched. Source file size/mtime/file identity are
checked again after reading and before completion.

The required ASCII header, including one LF, is:

```text
chr,hg19_pos,grch38_pos,ref,alt,aaref,aaalt,REVEL,Ensembl_transcriptid
```

Each record must have exactly nine literal comma-separated fields, one LF and no
CR, tab or NUL. No CSV quoting interpretation changes the original text. This
matches `RevelPredictionObservation`'s nine-field reader. A raw row has a maximum
of 16,384 bytes before LF. **`sourceLine` is the one-based data-row ordinal;
the header is not counted.** This differs from older chromosome-specific files
that counted the header, so consumers must use the explicit convention.

The indexed representation has four tab-separated columns:

```text
#chrom  position  sourceLine  rawCsv
```

Columns 1 and 2 exactly repeat the publisher's chromosome and GRCh38 position.
`rawCsv` contains all nine unchanged source cells, including hg19 position,
semicolon-separated unversioned ENST identifiers, score text and amino acids.
The output adds one LF. Stable row identity is the full ZIP/member identity plus
the data ordinal; the full output artifact and uncompressed stream are hashed.
No duplicate is removed, including byte-identical raw rows at different ordinals.
Different scores/mappings/transcripts at an allele are all retained. A consumer
must return all applicable rows and let the runtime detect conflicts; it must
not take the first, maximum or average score.

Only exact source contig tokens `1` through `22`, `X`, `Y`, `MT` and canonical
positive decimal positions below `2^29` are indexed. No `chr` stripping, leading
zero removal, chromosome remapping or coordinate fabrication occurs. A literal
`.` GRCh38 position is the documented publisher missing-map sentinel. Empty,
`NA`, other malformed positions, unsupported contigs or positions outside TBI
range retain their exact original row in `revel-unlocated-source-rows.tsv.gz`,
with separate reason counts. Other raw annotation anomalies, including a
missing/invalid score, unusual DNA/amino acids, hg19 or transcript fields, are
counted descriptively and **do not remove an indexable row**. They remain for
runtime refusal. Missing scores or mappings never become zero.

## Bounded construction and completion

Only a new, nonexistent output directory is accepted. It contains all new
artifacts and temporary sort runs; existing HNF1A resources are never touched.
No raw member is first extracted to a standalone file. The standard library
sorts 32 MiB projected chunks (also capped at 500,000 buffered records), with at
most 64 open merge inputs. Intermediate runs are retained until final validation.
The memory limit is a buffered **row-byte** bound, not an exact Python heap/RSS
claim; Python sort objects add memory overhead.

Every owned output write is charged before writing against the configured
logical disk-byte cap, at most 20,000,000,000 bytes. Short/failed writes retain
their attempted amount conservatively. A 1 MiB manifest reserve remains. BGZF
compression uses an existing `bgzip`; its stdout/stderr are drained through the
same byte budget, and stderr is bounded to 1 MiB. Child processes are hidden on
Windows and terminated on construction failure/cancellation.

Tabix writes its own TBI, so 64 MiB is reserved before invoking it. The fixed
point-coordinate scope yields at most 25 * 32,768 leaf bins, one contiguous
chunk per sorted point bin plus the linear indexes and metadata, below 32 MiB
uncompressed. The 64 MiB reservation includes BGZF overhead. The complete TBI
parser enforces that bin/chunk/linear/contig/coordinate structure; this bound
must not be reused for arbitrary intervals, other contigs, or CSI. Accounting
describes logical file bytes, not allocation units, filesystem metadata or the
already existing input ZIP.

Before `manifest.json` can appear with `COMPLETE`, the tool requires:

- Full pinned ZIP SHA, single member identity, complete member length/CRC and SHA.
- Exact data-row accounting: indexed plus excluded equals all source data rows.
- Successful BGZF process exits and full `bgzip -t` integrity checks.
- Complete indexed BGZF re-read: original row/coordinate/ordinal reconstruction,
  strict sort order, row count, exact sorted-stream SHA and projected-row
  cryptographic multiset sum all match. Both identical and conflicting rows stay.
- Complete excluded BGZF re-read, original-row classification/ordinal and stream
  SHA/count match; no excluded prefix is treated as a complete source.
- Complete TBI binary parse, exact contig listing, and all records at the first
  and last indexed coordinate of each contig retrieved with matching count and
  byte digest. Query checks are supplementary to the full BGZF/TBI checks.

Native Windows tabix emits CRLF on stdout. Query verification removes only that
transport CR before LF and records the observed newline mode; the compressed
data and original raw rows retain their verified LF bytes.

The complete manifest is first written to `manifest.pending.json`, re-read, then
renamed. Failures/cancellation preserve partial artifacts and a separate
`failure-manifest.json` when metadata writing remains available; those prefixes
must never be used. There is no automatic resume or overwrite. Only after the
complete manifest and all checks pass are the exact hash-verified owned sort
files removed. `cleanup.json` binds that complete manifest and records removal
or individually retained files; published artifacts and original ZIP remain.

## Invocation and tests

Use existing standard-library Python, bgzip and tabix. No tool installation is
required. The source version and expected checksums must come from the reviewed
capture manifest, not from the file merely being named REVEL:

```text
python build_revel_index.py --source-zip <pinned.zip> \
  --expected-zip-sha256 <64hex> --expected-zip-bytes <bytes> \
  --member-name revel_with_transcript_ids --expected-member-bytes <bytes> \
  --expected-member-crc32 <8hex> [--expected-member-sha256 <64hex>] \
  --output <new-task-owned-directory> --bgzip <existing-bgzip> \
  --tabix <existing-tabix> --maximum-disk-bytes 20000000000
```

`--maximum-data-rows` defaults to 500,000,000 and reaching a source bound fails
the complete build; it never silently produces a first-N resource. Chunk bytes
and merge fan-in may be lowered for synthetic stress tests. A larger row bound
or another missing-coordinate vocabulary must be reviewed explicitly.

Run `test_build_revel_index.py` with `VARVIS_REVEL_BGZIP`, `VARVIS_REVEL_TABIX`
and optional `VARVIS_REVEL_TEST_ROOT` pointing to existing tools and a private
temporary parent. Integration tests create only small synthetic ZIPs, run the
real local compression/index binaries and clean only their fresh test directory.
No real REVEL ZIP, FASTA, service or network is accessed. Unit tests cover raw
preservation, coordinate refusal and conservative short-write accounting.
