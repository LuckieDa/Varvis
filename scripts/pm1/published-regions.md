# Published PM1 location interpretation

`GeneralPm1Runtime(ObjectMapper, CodingTranscriptMaterialSupply,
PublishedCriterionKnowledge)` supports the shared selected-evidence projection.
It keeps the original source gate before local sequence supply and source-policy
selection. It does not execute Exomiser, acquire public data, create review
receipts, or change the historical reviewed PM1 contract.

Compile the explicitly interpreted plan with
`scripts/acmg-generalization/prepare_published_criterion_knowledge.py`. The
complete published source and original transcript remain in the output. A
different current ENST requires the shared full RNA/exon/CDS identity proof;
versionless aliases or a bare MANE association do not suffice.

The PM1 interpretation has these exact fields:

- `type=PUBLISHED_PM1_PROTEIN_LOCATIONS`, `version=1.0.0`;
- `consequenceScope=PURE_MISSENSE_SNV` and
  `qualificationBasis=PUBLISHED_EXPERT_SPECIFICATION`;
- `proteinIdentity`: either only `mode=SOURCE_TRANSCRIPT_DEFINED`, or
  `mode=DECLARED_PROTEIN`, a versioned `accession`, and source binding;
- `rules`, `precedence`, `residualConditions`, `crossCriterionRelations`.

Each location rule contains `ruleId`, inclusive `start/end`, `regionType`,
`effect`, `strength`, `referenceResidues`, and `sourcePointer/sourceSha256`.
The latter SHA is the existing canonical SHA of the original node, not the
file's RAW SHA. Supported region types are `MUTATIONAL_HOTSPOT`,
`CRITICAL_FUNCTIONAL_REGION`, `CRITICAL_RESIDUE`, and `EXCLUSION`.

Support rules bind the actual selected PM1 strength descriptor. Its native
applicability must be `Applicable`, status must be absent or `approved`, and
strength must exactly match `MODERATE` or `SUPPORTING`. Unsupported Strong is
rejected, not lowered. Ordinary domain annotations are not accepted.
Exclusions use `effect=EXCLUDE`, `strength=NONE`, and the exact original text.
Each reference residue is `{position,aminoAcid}` and is checked against the
complete CDS; critical residues require one explicit amino acid. Optional
`expectedSequence` checks the entire interval's peptide motif.

Precedence rows contain `higherRuleId/lowerRuleId` and their source binding.
The common pure location selector is shared with the historical PM1 evaluator.
Overlapping rules without an explicit dominant rule remain a conflict;
strength is never maximized automatically. A source-backed exclusion gives
NOT_MET. No matched source location gives NE, not an assertion that all other
regions were scientifically assessed.

`residualConditions` must be an empty array in this initial grammar. Only
source-bound `NOT_MUTUALLY_EXCLUSIVE` relationships are supported in
`crossCriterionRelations`, with `criterionId/relation/sourcePointer/sourceSha256`.
Requirements involving other criterion results, phenotypes, somatic counts,
alternate amino acids, attachments, or combined-strength caps must stay in
blocked records until explicitly supported. Offline interpretation is
responsible for retaining all conditions and source conflicts; the Java
consumer does not infer scientific meaning from arbitrary English text.

The runtime returns `status/reasonCode`, `evaluation`, complete
`knowledgeSelection`, sequence material provenance, `regionAssessment`, and
`publicSummary` (`pm1-general-facts-v1`). Identity includes knowledge and
sequence-supply bindings. MET uses the original supported strength. No clinical
validation or numerical benign distribution is inferred.

Focused tests: `GeneralPm1RuntimeTest`, `PublishedPm1RegionAssessmentTest`, and
the directly affected historical `Pm1CriterionEvaluatorTest`.
`PublishedPm1LocalSourceTest` additionally takes explicit
`varvis.pm1.published.file` and `varvis.pm1.published.sha` properties. It checks
fixed real clauses with **synthetic peptide-position probes**, not real VCF
evaluation. Optional `varvis.pm1.published.artifacts` preserves those outputs.
