# BP2 R1 TEST_ONLY material boundary

The original v1 fixtures and reviewer loader bytes are immutable. `frozen/` is a
byte-for-byte copy of the three independently reviewed failing inputs, including
their original raw/canonical pins. Tests replay those bytes directly, without
reissuing them. They must now return minimal NE, not source-pin mismatch.

With explicit user agreement, v1 trans material is readable but cannot establish
executable penetrance conditions. It returns
`BP2_PENETRANCE_CONDITIONS_NOT_ESTABLISHED`. Cis remains valid and needs no
penetrance object. `beta-*` is a separately issued, unrestricted v2 replacement;
`restricted-*` is a new v2 positive at age 18 with MALE sex and exact population A.
Expected semantic projections were fixed before running the evaluator.

Only disease applicability is versioned (`bp2-disease-applicability-v2`, with an
explicit `materialVersion`). Each condition preserves its source text and adds
`constraint` and `patient`. The patient binds the entire existing observation,
case, patient, family, context, pair, directional alleles, gene/disease/MOI scope,
mechanism and material version. All these fields participate in the existing
source report, part, assessment, policy, catalog and evidence identities.

- `UNRESTRICTED` permits only `{mode: UNRESTRICTED}` and a patient binding with
  mechanism/materialVersion. No limits or patient value fields are permitted;
  the review label must be `REVIEWED_UNRESTRICTED`.
- `RESTRICTED` requires `SATISFIED`, known patient facts and an actual successful
  comparison. Age supports only `GTE`, integer completed `YEARS`, 0–150 inclusive
  for patient and threshold. There is no conversion or decimal age support.
- Sex supports `IN` with a nonempty unique MALE/FEMALE set. Patient sex must be
  known, equal context sex, and belong to the required set.
- Population supports `IN` with a nonempty unique identity set and an explicit
  population system/version identity. The patient system must match; membership
  compares exact NFC identities without case folding, trimming or inference.
  `UNKNOWN` and unknown fact status cannot satisfy a restriction.

Free text is preserved provenance, never an executable predicate. Conditions
outside this small language require NE or strict loader refusal; medical prose
is not parsed. Structured predicates are the reviewed executable projection.
Contradictory review labels cannot override calculated values. `UNRESTRICTED`
cannot hide a structured limit. Strict unknown-field and numeric identity rules
are unchanged. These are synthetic engineering materials, not professional review
or a claim that any real disease meets the conditions.
