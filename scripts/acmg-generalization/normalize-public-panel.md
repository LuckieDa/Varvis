# Bounded masked public-allele reference normalization

`normalize_public_panel.py` is an explicitly invoked source-preparation tool. It
does not run a scientific criterion, choose a clinical transcript, select a panel,
change the source pool or activate business behavior. Its only allele inputs are
the separately pinned `sampling-projection.jsonl` and `query-occurrences.jsonl`.
The extractor manifest and prospective query/normalization plans must match.
It never opens the raw ClinVar VCF or reads classification fields.

The implementation reuses the existing production `bcftools norm` executable and
options: `-f`, `-c e`, `-m -any`, `--old-rec-tag VARVIS_ORIGINAL_VARIANT`. Its input
gate excludes multi-ALT, symbolic, MNV and complex replacements before the CLI;
one original record must yield exactly one record. Supported input is native
GRCh38 uppercase A/C/G/T SNV or simple anchored insertion/deletion, with REF and
ALT each 1–50 bases. It never fixes REF, flips strands, atomizes variants or
performs HGVS 3-prime alignment. `--no-version` suppresses absolute temporary paths
in the VCF header; executable, version, options and command receipts are recorded
separately. The existing installed executable is pinned by size and SHA-256.

The fixed source windows come from query-plan v2. The normalization plan fixes
right padding at 49 bases for every attempted window and cumulative left
extensions at 0, 1,000 and 10,000 bases. Only actual chromosome ends clip these
coordinates. Limits are 129 original windows, 35,049 bases per read, three attempts
per original window, 387 reads and 12 MiB cumulative requested FASTA bases.
Repeated candidates share a window; no per-allele FASTA fetch is performed.
Completed empty windows need no read. Requested bases are reserved before reading,
and failed reads are not refunded. These are logical requested/returned sequence
limits, not a claim about operating-system physical disk read sizes.

The entire original REF is checked before calling bcftools. Each temporary
contig is an explicitly identified slice of the native reference. Its start,
end, source contig, reference contig and absolute/local offset are retained.
Normalized REF and the entire edited local haplotype must agree. A normalized
indel at temporary position 1 with a genomic slice start greater than 1 is
incomplete and triggers only the next fixed extension. If the third attempt
still ends there, canonical allele/key remain null. A true chromosome position 1
is distinguishable. SNV positions are invariant and do not need repeat left-shift
proof. Ambiguous bases in REF or the traversed normalization context are rejected
for canonicalization; unrelated N bases elsewhere in a slice are not misused as
evidence about the allele.

Reference identity is inherited only from an externally pinned, previously
completed whole-file SHA-256 receipt. The complete receipt must name the actual
native resource, `RAW_BYTES`, verified checksum, size, modification time and index
hash. Current metadata, explicit contig-alias file and FAI hashes must agree before
and after sequence access. The normalization plan independently binds the exact
reference packaging and nested receipt. Window hashes are explicitly scoped to
the requested bases and never presented as a new whole-reference checksum.
Numeric source contigs stay numeric in canonical keys; the explicit mapping to
the actual FASTA contig is retained in each window locator. No guessed `chr`
prefix conversion is performed.

Run with explicit local paths and the independently frozen normalization-plan
hash. The output must be a new immediate child of an existing task-private root.
No existing output directory is reused or deleted:

```text
python normalize_public_panel.py
  --plan <normalization-plan.json> --plan-sha256 <SHA256>
  --query-plan <query-plan-v2.json> --pool-manifest <pool-manifest.json>
  --projection <sampling-projection.jsonl> --occurrences <query-occurrences.jsonl>
  --resource-root <resource-root> --reference-receipt <reference-ledger.json>
  --reference-source-id <receipt-envelope-source>
  --reference-source-version <receipt-envelope-version>
  --reference-record-locator reference
  --aliases <explicit-contig-aliases.tsv> --aliases-sha256 <SHA256>
  --bcftools <existing-bcftools-executable>
  --task-output-root <task-private-root> --output <task-private-root/new-run>
```

The manifest/projection/occurrence input bytes share a 256 MiB ceiling. The
150,000-row cap applies separately to each JSONL input, with 1 MiB maximum lines.
Original UTF-8 source-row hashes and the extractor's exact snapshot identity are
preserved; the latter is not relabeled as a full ClinVar data checksum. All
completed gene/window/partition states and every observation ordinal must agree.
Unknown projected fields, missing joins, altered hashes or incomplete peers stop
the run before sequence access.

All persisted artifacts share a 256 MiB ceiling, including temporary reference
slices, FAI files, CLI VCF files, logs and partial failed files. The final manifest
reserves 1 MiB **inside** that ceiling. CLI stdout/stderr are drained through the
same bounded writer. A cap or process failure terminates only this invocation's
owned child and retains partial files. There is no retry outside the three
declared window attempts. No final COMPLETE manifest means unusable/incomplete;
never sample a successful prefix from a failed run.

Outputs are `source-normalization.jsonl`, `window-attempts.jsonl`,
`canonical-aliases.jsonl`, `manifest.json`, and bounded CLI evidence. Every source
record remains accounted for, including deterministic exclusions. Each canonical
group references all original records, source keys, raw-row hashes and membership
statuses. Detailed source/query provenance remains in those original records.
Multiple-gene review status is never cleared. Aliases add no distinct-allele credit.
`COMPLETE` means source normalization/accounting completed, including explicit
exclusions; it is not a sampled panel. `samplingAllowed` stays false, and selected
allele, rule-evaluation and network-call counts stay zero. Final canonical-key
ranking and clinical transcript attribution belong to the subsequent reviewed
sampling workflow.

Synthetic tests use temporary miniature FASTA files and their own full checksum
receipts. Set `VARVIS_TEST_BCFTOOLS` to the existing reviewed binary and direct
`TEMP`/`TMP` to the task-private build cache, then run:

```text
python -I -S -B test_normalize_public_panel.py -v
```

The whole-contig oracle in these tests is synthetic. Passing these tests does not
constitute real panel, clinical, held-out or backend acceptance. Real resource
execution remains the coordinating task's responsibility after independent review.
