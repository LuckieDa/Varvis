# B01b TEST_ONLY material provenance

The positive alpha/beta cases read the unchanged bytes in `../ps2-denovo-core/r1/`.
They are different patients/families and are published into separate analyses.
The upstream test transport replays a tiny TSV; the actual parser, single VCF
scan, job publication, snapshot recovery and ordinary store are exercised.
No real Exomiser, annotation provider, reference sequence or clinical review runs.

The following two inputs are byte-for-byte copies of already independently
validated R1 inputs, fixed before the business tests. They preserve the original
context/reference witness and already matched facts/source/assessment/policy pins.
They are not reissued or changed at submit time.

| New file | Historical input under `local/development/handoffs/PS2-denovo-core-r1-review-20260928/root-01/independent-02/artifacts/` | Independent expected result |
| --- | --- | --- |
| `alpha-unknown.json` | `r1-alpha-scope-UNKNOWN-input.json` | Minimal NOT_EVALUATED, PS2_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE |
| `alpha-ft-conflict.json` | `r1-alpha-ft-fail-pass-input.json` | Minimal NOT_EVALUATED, PS2_RAW_FT_CONFLICT |

Alpha is 2/STRONG; beta is 0.5/SUPPORTING. The new business wrapper must not
change these expectations. No expectation is inferred from its own verdict.
The development handoff's `fixture-provenance.json` records RAW_BYTES identities
for these inputs and their independent expected-result sources.
