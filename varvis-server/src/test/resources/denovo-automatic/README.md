# SYNTHETIC — de novo calculator contract fixtures

These examples are invented validation cases, not patient records, clinical reports,
real parentage findings, expert approvals or literature extraction results.
`frozen-expectations.tsv` was written before the new calculator implementation.
Four quarter-points equal one SVI de novo point, not a classification score.

Expected independently checkable combinations:

- One confirmed highly specific event (2) + two unrelated assumed highly specific
  events (1 + 1): 4 points, a single PS2 VeryStrong group.
- Three confirmed high-heterogeneity events (0.5 each): raw 1.5, capped 1,
  PS2 Moderate. Duplicated papers about one event never increase it.
- Five assumed high-heterogeneity events (0.25 each): raw 1.25, capped 1,
  PM6 Moderate. One such event alone is evaluated but below Supporting.
- Missing father's target test is NOT_EVALUATED, never an automatic PM6 fallback.
- Untested/unstated parentage is distinct from untested parental target genotype;
  inconclusive or excluded parentage cannot be silently labelled assumed.
- A directly documented inherited target or inconsistent phenotype yields zero
  support, not a benign classification. Unknown phenotype is NOT_EVALUATED.
- Conflicting facts for the same occurrence exclude that occurrence; conflicting
  shared variant/disease identity stops the affected group.
- Current-case and literature-case observations may coexist, but identical people
  across sources form one event. Suspected overlap without resolution cannot count
  as a new independent occurrence. Two siblings are not two independent events.

The calculator consumes previously qualified facts only. This fixture does not
prove that its upstream source verification, automatic retrieval or LLM exists.

`synthetic-local-report.json` is a source-format fixture. Its target, people,
laboratory methods and relationship findings are invented. The reader verifies
the retained bytes, explicit roles and report fields; it is not authentication
of a laboratory and does not assign a phenotype category, an occurrence's
independence or clinical approval. Tests of this fixture are software validation.
