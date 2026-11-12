# Optional prediction clause consequence scope

Generic prediction evaluator 1.0.4 adds two explicitly declared, source-bound
eligibility conditions. Existing policy documents without these fields retain
their previous scientific domain; the historical HNF1A runtime is unchanged.
Fresh generic executions have the new algorithm identity. Saved historical
results are not rewritten or silently upgraded for BP7 dependency reuse.

```json
{
  "consequenceScope": {
    "mode": "SYNONYMOUS_OR_INTRONIC_ONLY",
    "sourceIds": ["<reviewed policy source ID>"]
  },
  "intronicPositionScope": {
    "mode": "OUTSIDE_CANONICAL_SPLICE_DINUCLEOTIDES",
    "sourceIds": ["<reviewed policy source ID>"]
  }
}
```

`consequenceScope` is allowed only for SpliceAI BP4/BP7. The complete selected
VEP consequence string must contain exactly one of `synonymous_variant` or
`intron_variant`; its only permitted additional term is `splice_region_variant`.
Standalone splice-region annotations, unknown or other consequence terms,
ambiguous classes and malformed/duplicate tokens remain not evaluated. Terms
use the production VEP parser's `&` separator; comma/pipe encodings are not
silently converted. Terms are not discarded because a low score is available.

`intronicPositionScope` is an optional BP4-only condition and requires the
consequence scope. For an intronic variant, the existing exact selected VEP
annotation must provide a simple, version-bound intronic SNV HGVSc and its
projected `vep.intronicSignedOffset` measurement in `base-offset` units. The
unique projected value must match the existing HGVS-derived offset. Absolute
offsets 1 and 2 fail; 3 and beyond satisfy this condition. Missing, zero,
overflowing, complex or inconsistent positions cannot establish eligibility.
SpliceAI's predicted DP values never substitute for the variant's position.
Synonymous variants do not require an intronic offset, but contradictory
intronic HGVS remains a source-identity failure.

The caller first verifies the stored annotation's exact transcript version and
VEP resource identity through the existing projection. This condition does not
claim a new FASTA query or independent genomic remapping. It runs before score
consumption in the clause evaluator; context-only `prepare` cannot establish
candidate consequence eligibility and does not claim to have done so.

Each condition's source IDs must be nonempty, unique and bound to the policy's
reviewed sources. Unknown fields/modes, unsupported criterion/model combinations
or a position condition without its consequence condition are rejected. The
decision and original terms/HGVS/position provenance are retained in
`consequenceScopeValidation`, which participates in the result identity.

Scientific motivation is limited to the reviewed clauses in
[RUNX1 GN008 v3.1](https://cspec.genome.network/cspec/ui/svi/doc/GN008?version=3.1)
and [TP53 GN009 v2.4](https://cspec.genome.network/cspec/ui/svi/doc/GN009?version=2.4).
TP53 BP4 explicitly excludes intronic canonical dinucleotides. Applying the same
intronic restriction to a RUNX1 candidate policy is a narrower Varvis
engineering scope, not an extra numerical requirement attributed to that VCEP.
RUNX1's missense BP4 joint predictor condition is not implemented by this mode.

BP7 continues to require its exact current SpliceAI BP4 dependency and existing
position checks: synonymous first-base/last-three-base exclusion and intronic
offsets at or beyond +7 or -21. For a RUNX1 candidate these intronic limits are
an explicitly narrower engineering scope. No conservation threshold, joint
VarSeak operator, PP3/PVS1/RNA exclusion, or other PP3 extension is added.

No disease policy, production activation, real panel result or clinical approval
is created by these operators. A newly assembled bundle still requires complete
source, policy, scope and independent validation.
