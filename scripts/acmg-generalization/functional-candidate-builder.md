# Functional candidate builder

`build_functional_candidate.py` packages independently reviewed source inputs
for `FunctionalEvidenceRuntime`. It performs no network requests, VEP execution,
model inference, evidence adoption, or final classification. Python's standard
library is sufficient. The script has no gene identifiers or scientific cutoffs;
the reviewed construction input supplies the exact scope, method and threshold.

Run with an explicitly pinned local manifest and a **new** output directory:

```text
python build_functional_candidate.py --inputs build-inputs.json --inputs-sha256 <byte-sha256> --output-dir <new-candidate-directory>
python test_build_functional_candidate.py -v
```

The build manifest uses this shape. Each artifact/source/model binding is exactly
`{"path":"<explicit-local-file>","sha256":"<byte-sha256>"}`. Paths are local
inputs and may be absolute; output candidates contain hashes, not those paths.
The input manifest and its expected hash must be supplied by the controlled
caller. Hashes provide identity and tamper detection, not scientific approval.

```json
{
  "schemaVersion": "functional-candidate-build-manifest-v1",
  "candidateId": "SOURCE_REVIEWED_DEVELOPER",
  "version": "1.0.0",
  "artifacts": {
    "construction": {"path": "reviewed-construction.json", "sha256": "<sha256>"},
    "expectations": {"path": "prospective-expectations.json", "sha256": "<sha256>"},
    "rawRows": {"path": "typed-source-rows.json", "sha256": "<sha256>"},
    "annotationIdentity": {"path": "actual-vep-identity.json", "sha256": "<sha256>"},
    "registry": {"path": "actual-registry.json", "sha256": "<sha256>"},
    "vepJsonl": {"path": "existing-vep.jsonl", "sha256": "<sha256>"},
    "vepInputManifest": {"path": "input-manifest.json", "sha256": "<sha256>"},
    "vepExecutionStart": {"path": "execution-start.json", "sha256": "<sha256>"},
    "vepExecutionFinish": {"path": "execution-finish.json", "sha256": "<sha256>"}
  },
  "models": {
    "<exact-versioned-transcript>": {"path": "native-model.json", "sha256": "<sha256>"}
  },
  "sourceFiles": {
    "<declared-source-id>": {"path": "original-source-file", "sha256": "<sha256>"}
  },
  "annotationResourceId": "<resource-in-pinned-annotation-manifest>",
  "registryClasspathResource": "/acmg/rule-registry-v2/actual-registry.json"
}
```

Source contracts are `reviewed-functional-construction-inputs-v1`,
`prospective-source-expectations-v1` and `prospective-raw-source-rows-v1`.
The PTEN developer instances live under
`docs/execution/ten-rule-generalization/validation/` and remain source-review
evidence, not runtime approval. A second context must provide its own reviewed
files with the same supported operators. Unknown scientific conditions fail
closed. Only a source-bound measured scalar and original Excel boolean quality
operator are supported by this builder.

The builder checks:

- Independently pinned source bytes, input byte/canonical identities and model
  canonical identities, plus exact disease/MOI/assembly/transcript/protein scope.
- Original workbook sheet/row/cell identity, cell type, resolved text and raw
  OOXML storage. Formula cells cannot stand in for an original measurement.
  Measurement decimal strings, false/missing quality and imputation remain
  distinct. The script does not evaluate the score or impute missing values.
- Full native CDS and exon mechanics, source WT and experimental mutant codons,
  public DNA REF/ALT to protein reconstruction, and existing VEP's original
  allele, versioned HGVS, local codon/AA/position, exon and gene fields.
- A separately reviewed model bridge for aliases. Each model retains its own
  exact hash and identity; an ENST VEP record is never relabeled as an NM result.
- Actual annotation-resource fingerprint and model FASTA-index binding, registry
  identity, completed developer annotation batch, prospective input identity and
  declared assembly/options. No whole-genome checksum claim is made.

One candidate is written per exact model, with a manifest containing byte and
canonical SHA-256 values. Canonical identities match the Java adapter's compact
NFC JSON rules; JSON floats, duplicate keys and normalized-key collisions are
rejected. All model/annotation/source inputs remain unchanged.

Candidates deliberately retain `PENDING_ASSEMBLED_BUNDLE_REVIEW` for policy and
per-assay conflict review, and `SOURCE_REVIEWED_PENDING_BUNDLE_REVIEW` for facts.
The current Java validator rejects these review states. Root's later independent
review may explicitly form a controlled executable policy with new hashes;
the builder never promotes source review to that state. This is a project policy
review boundary, not a request for an invented clinical expert signoff.

The original execution/profile relationship also remains open: an execution's
additional plugins or options do not silently become part of an older resource
fingerprint. The pinned original execution files support that separate review.
No generated candidate is a catalog registration or approved selection receipt.
BS3, default activation, adoption, formal Top-N/held-out acceptance and clinical
classification all remain disabled or unclaimed.
