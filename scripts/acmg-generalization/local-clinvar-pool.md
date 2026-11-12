# Local public allele identity pool

`LocalClinVarPool.java` reads a pinned local query plan and uses the existing
htsjdk `TabixReader` serially. It does not query a service, run VEP or a rule,
normalize a variant, rank source classifications, or choose the final 200 alleles.
It does not change the business comparator's 101-base query bound.

Compile with an existing JDK 17 or newer, htsjdk 2.24.1 and the existing Jackson
core/databind/annotations jars. No dependency installation is needed. Keep
compiled classes and test outputs outside the repository. `CP` below is the
platform-separated list of those four existing jars; `CLASSES` is a private build
directory. On Windows the classpath separator is `;`.

```text
javac -proc:none --release 17 -encoding UTF-8 -cp <CP> -d <CLASSES> LocalClinVarPool.java LocalClinVarPoolTest.java
java -Xmx256m -cp <CLASSES-and-CP> LocalClinVarPoolTest <new-private-synthetic-test-directory>
java -Xmx512m -cp <CLASSES-and-CP> LocalClinVarPool --plan <fixed-plan.json> --plan-sha256 <byte-sha256> --source <local.vcf.gz> --index <local.vcf.gz.tbi> --output <new-output-directory>
```

The controlled caller supplies the plan's independently recorded byte SHA. The
current plan schema is `prospective-local-public-allele-query-plan-v1`. Windows
must exactly and continuously cover each declared padded gene interval, in the
declared order, and each window is at most 25,000 bases. Gene/window counts and
quota totals must agree with the frozen sampling frame. The extractor never
changes those quotas or widens a window. The interval TSV is not re-read: the
reviewed, hashed plan already contains the fixed search envelopes, which are not
clinical transcript models.

Before any query, the tool checks local file sizes, the index byte SHA and the
original decompressed header byte SHA, from the first byte through the complete
`#CHROM` line including its final LF. Header size is itself bounded. The source
date (strict ISO date or compact YYYYMMDD), GRCh38 plan identity and VCFv4.1 format are checked. It repeats index/header
checks and data/index size/modification-time checks at completion. This identifies
the supplied snapshot without claiming a whole compressed VCF checksum. No source
file is changed. UNC/network paths and an existing output directory are rejected;
the output's parent must already exist.

Outputs are:

- `raw-pool.vcf`: the verified original header and every unique returned original
  VCF line, including rejected or ambiguous source identities and complete INFO.
  This is private evidence, not the sampler input.
- `sampling-projection.jsonl`: only CHROM/POS/ID/REF/ALT/FILTER and the allowlisted
  GENEINFO, MC, CLNHGVS, ALLELEID, RS, AF_ESP, AF_EXAC and AF_TGP source fields,
  plus raw-line and snapshot identities. Missing fields remain absent. No QUAL,
  classification, disease label, review stars or arbitrary INFO keys are copied.
- `query-occurrences.jsonl`: every retained query return, joined by raw-line SHA,
  with query gene, region and return ordinal. Exact GENEINFO symbol/NCBI Gene ID
  pairs stay separate. Multiple/malformed gene identities, substring mismatches,
  multiallelic records and out-of-envelope alleles retain explicit review or
  exclusion states. Gene-membership and allele-envelope states are recorded
  independently, including when both require review. A source NCBI identifier is
  never called an HGNC identifier.
- `manifest.json`: complete/capped/failed/cancelled status, each gene/window's
  progress, counts, budgets, source/frame/plan identities and output checksums.
  `RUNNING.txt` remains an interruption marker; only a final `COMPLETE` manifest
  with `samplingAllowed=true` allows the independent sampling stage to proceed.

Identical original lines deduplicate across Tabix span overlaps, while every
query locator remains in occurrences. Different source records for the same
allele remain different records. Raw-line SHA covers the original UTF-8 bytes
without their line terminator; the raw VCF artifact uses LF. htsjdk 2.24.1 maps
each source byte to a character, so the extractor recovers those bytes before
strict UTF-8 decoding. Valid non-ASCII metadata remains intact in private raw
evidence and the same INFO allowlist controls the sampling projection. Malformed
UTF-8 and unexpected library character contracts fail without replacement text.
The snapshot reference hashes the manifest's source identity object. Neither
raw-line count nor source ID count is a final normalized distinct-allele count.

All declared retained-row/raw-byte/artifact budgets apply. Raw-byte counts include
one LF per returned variant line; the header counts against the artifact budget.
The same row/byte ceilings also conservatively limit query observations, including
duplicates and excluded records, so repeated windows cannot evade the limits.
A per-gene cap stops that gene; a global cap stops all remaining genes. Reaching
the row observation limit can mark a pool capped without probing one extra EOF.
No incomplete first-N prefix is usable for sampling. Only a new, explicitly frozen
bounded plan can authorize another extraction or partition.

The v2 plan may declare contiguous, complete groups of the already fixed query
windows. `queryPartitions` covers each window exactly once using 1-based inclusive
first/last ordinals and explicit per-partition row/byte limits. Gene and global
counts continue across partitions; the sampling quota remains attached to the gene.
Every partition must reach EOF for the gene and whole pool to be complete. A capped
partition invalidates that pool, with subsequent partitions marked not started.
Partition limits remain at most 20,000 observations/32 MiB; v2 permits a declared
gene aggregate up to 80,000 observations/128 MiB while preserving the existing
global 150,000 observation/128 MiB raw/256 MiB artifact ceilings. v1 retains its
original per-gene ceilings and cannot silently introduce partition semantics.

htsjdk materializes a returned line before the tool can measure it. An oversized
or byte-budget-crossing lookahead is hashed/sized in the manifest but not retained
in the pool. The declared limits bound retained UTF-8 payloads, not compressed
BGZF reads, decompressor buffering or process heap. A reserved manifest allowance
keeps normal failure/cap evidence inside the artifact budget. Abrupt process kill,
out-of-memory or disk failure may leave only incomplete artifacts and the RUNNING
marker; these cannot be mistaken for a complete pool. Exit code 2 means incomplete
or invalid input, including a capped extraction.

The synthetic test constructs tiny BGZF VCFs and actual Tabix indexes with existing
htsjdk. It covers exact/multiple gene membership, cross-window duplicate locators,
all budget classes, masked clinical fields, source/hash/window failures, an
unreadable index, preserved source bytes and existing-output protection. It is
not a real ClinVar extraction or clinical/scientific validation. AF_ESP/EXAC/TGP
remain their original source fields; they are not gnomAD v4 AF/FAF and cannot
establish BA1, BS1 or PM2 expectations. Transcript/NMD/stratum assignment and
the final independently frozen panel remain downstream tasks.
