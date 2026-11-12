# VEP 116 transcript source exporter (diagnostic, unregistered)

This tool exports source candidates for an explicit list of 1–32 versioned ENST
accessions. It is not a VEP default plugin, annotation provider, transcript model,
policy approval or clinical result. It does not register resources, change normal
VEP options, replace `NearestExonJB`, fetch sequences, query databases or download
anything. No real VEP invocation is included in its tests.

## Source/API contract

The implementation was checked against the installed Ensembl/VEP release 116
Perl sources, including these methods and cache preparation/cleanup paths:

| Source | Consequence for export |
| --- | --- |
| Core `Transcript::get_all_Exons`, `translation`, `cdna_coding_start/end` | Require the already cached complete exon array, translation and cDNA boundaries before invoking accessors. Exons retain transcript 5′→3′ order, including pure UTR exons. |
| Core `Transcript::biotype` / `set_Biotype` and `Biotype` | A getter can resolve a Biotype object through an adaptor. Read the cached string or an already present exact `Biotype` object's explicit `name` and `object_type` fields. Missing identity never defaults to protein coding. |
| Core `Translation::start/end`, `start_Exon/end_Exon` | Start/end are offsets within boundary exons. Reconstruct cDNA and absolute genomic CDS boundaries; do not mistake offsets for genome positions. |
| Core `Feature::seq_region_start/end/strand`, `Slice::is_circular` | Account for offset/reversed slices. Require native primary GRCh38 chromosome identity and noncircular state; refuse a restored adaptor whose circular cache is missing, before any accessor could query it. |
| VEP `AnnotationSource::Database::Transcript::prefetch_transcript_data/prefetch_translation_data` | CDS, peptide, codon table and translation edits can already exist in `_variation_effect_feature_cache`. Inspect only those stored values, without `seq`, `translate`, `translateable_seq` or `Translation::length`. |
| VEP `Pipeline::DumpVEP::Dumper::Core::clean_transcript` | Transcript/protein versions remain; exon versions and adaptors are removed. Never invent an exon version. The curated attribute list retains RNA edits and incomplete-CDS flags. |
| VEP `AnnotationSource::Cache::Transcript::deserialized_obj_to_features` | Adaptors/slices are restored. Therefore getter names alone do not guarantee read-only behavior. |
| VEP `CacheDir::dir`, `AnnotationSourceAdaptor::get_all_AnnotationSources` | Bind the request's `info.txt` to the effective `full_cache_dir` or `dir_cache`/`dir` + `homo_sapiens/116_GRCh38` path. |
| VEP `Runner::get_all_Plugins` / `finish` | Plugins load through Perl `@INC`; a normal finish supplies the mandatory export footer. Forked execution is unsupported. |

Exporter 1.0.1 pins the actual nine loaded core/plugin API module files by SHA256,
including `Biotype.pm`. The converter requires that version and its full source
manifest; older requests/exports remain historical artifacts, not silently
reinterpreted input. The first real developer export from 1.0.0 refused all three
requested transcripts at the string-only biotype gate; no model was promoted.
The export pins the exporter source too. Preserve the outer VEP execution command,
VEP source/version identity, input identity and normal exit receipt separately.
The cache `info.txt` hash is **metadata integrity only**, not a checksum of all
cache shards. The exported object hash binds the inspected object content; this
tool does not claim to retain the exact serialized shard bytes or their source
filename. These limitations must remain in any source registry entry.

## Explicit invocation

Use the existing VEP 116 Perl interpreter and its existing `PERL5LIB`. Make this
directory available through that process's `PERL5LIB` without changing the user's
global environment or removing other plugin/module paths. Paths passed as plugin
parameters must not contain commas. All output paths must be new.

1. Prepare a request from an independently pinned cache metadata file. This reads
   metadata and loaded API source files only; it does not execute VEP:

   ```text
   perl prepare_request.pl --cache-info <cache>/homo_sapiens/116_GRCh38/info.txt --cache-info-sha256 <pinned-info-sha256> --request-id <id> --transcript <ENST.version> --output <new-request.json>
   ```

   Repeat `--transcript` for additional explicit accessions. Keep the printed
   `requestSha256`, `exporterSha256` and API manifest in the controlled execution
   record. Request JSON is compact canonical UTF-8, with a final LF. Arbitrary
   handwritten formatting, duplicate keys, unknown fields and unversioned IDs
   are rejected.

2. In a separately authorized, isolated public-input VEP invocation, append this
   diagnostic plugin to the existing reviewed command:

   ```text
   --plugin VarvisTranscriptModelExport,request=<request.json>,request_sha256=<request-sha256>,output=<new-export.jsonl>
   ```

   The tool requires native human GRCh38 offline Ensembl cache 116, without fork,
   database, GFF/GTF, RefSeq-cache or merged-cache modes. An ENST is observable only
   if VEP encounters it for that run's input. This is not an arbitrary cache-wide
   transcript lookup: `NOT_OBSERVED` is a gap, not evidence that no such transcript
   exists. Exact-version/field failures become `REJECTED`. Different object
   identities for one request become `CONFLICT`; repeated identical objects are
   counted once. The plugin emits no variant-derived annotation fields.

3. Pin the finished JSONL bytes from the controlled run, then convert:

   ```text
   python -I -S -B convert_export.py --request <request.json> --request-sha256 <request-sha256> --export <export.jsonl> --export-sha256 <export-byte-sha256> --exporter-sha256 <previously-pinned-exporter-sha256> --output-dir <new-candidate-directory>
   ```

   All requested transcripts must be uniquely exported, with a normal final
   footer. The converter does not salvage incomplete, rejected or conflicting
   batches. It rechecks the canonical object hashes, exact schema, transcript and
   protein identities, ordered exons and both CDS boundary mappings before any
   output. External SHA pins must come from the controlled execution/source
   record, not be trusted merely because the export asserts them. Hashes do not
   confer scientific approval.

## Compatibility and independent sequence verification

Each `*.lookup-candidate.json` has the expanded transcript/exon/Translation shape
accepted by the existing `../build_transcript_model.py`. Its origin remains
`VEP116_OFFLINE_CACHE_TRANSCRIPT_OBJECT_NOT_REST`, as recorded in `manifest.json`.
Do not register it as an Ensembl REST response or silently reuse a REST URL.
The converter does not edit the source ledger or that builder.

Before treating a candidate as a model source, preserve the manifest and complete
the existing source workflow: pin the independently acquired RefSeq GenBank bytes;
check exact NM/NP ↔ ENST/ENSP MANE versions, all transcript exon lengths, cDNA CDS
boundaries and peptide length; compare RefSeq-derived CDS/peptide SHA256 with the
export's `objectSequenceChecks`. Use the existing builder's independent RefSeq
ORIGIN versus native tiny FASTA full-exon/CDS check. The object-cache sequence
hashes are an additional consistency check; they are **not independent RNA**.
Reading the same genomic FASTA twice cannot satisfy that independent-source step.
For a RefSeq-kind model, preserve the explicit reviewed bridge and recompute model
identity; do not relabel an ENST model by replacing its transcript string.

Only complete standard-code protein-coding models with unedited ATG-to-stop CDS
and a consistent cached peptide are supported. Missing cache fields, edits,
incomplete CDS, circular/non-primary sequences and unsupported coordinate models
remain explicit gaps. No biological relevance, NMD, assay transfer, disease/MOI,
policy strength, final classification or production activation is inferred.

## Focused verification

Set `TEMP`/`TMP` to a dedicated build-cache directory and
`VARVIS_EXPORT_TEST_ARTIFACT_DIR` to that directory (use a path understood by the
existing Perl interpreter). Run:

```text
perl -c VarvisTranscriptModelExport.pm
perl -c prepare_request.pl
perl test_exporter.pl
python -I -S -B -X utf8 test_convert_export.py --fixture-dir <same-test-artifact-directory>
```

The Perl tests construct real Ensembl 116 objects with synthetic coordinates and
poisoned adaptors/sequence getters. The Python tests consume the resulting actual
Perl JSONL, test tampering and missing/conflicting source semantics, and exercise
the unmodified model builder against independently declared synthetic RefSeq RNA
and a tiny synthetic FASTA. They do not inspect real variants, run VEP, read cache
shards, query real FASTA, validate a real model or approve clinical use. Real cache
field availability and source-ledger/business consumption remain separate tasks.
