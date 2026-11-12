# Varvis frontend context

Source navigation and working conventions for the existing frontend. Verify
current code and effective configuration when changing behavior. For formal
acceptance and runtime readiness, use the [documentation index](../docs/README.md),
[current state](../docs/execution/CURRENT_STATE.md), and
[current roadmap](../docs/CURRENT_IMPLEMENTATION_ROADMAP.md).
The [UI redesign report](../docs/frontend/UI_REDESIGN_REPORT.md) records historical
work and verification; it is not a fresh browser acceptance result.

## Pages and responsibilities

Routes are configured in [config/routes.js](config/routes.js), using hash history.
`/` redirects to `/varvis/chromosome-visualization` (browser URL
`/#/varvis/chromosome-visualization`). Paths below are relative to
`src/pages/varvis/ChromosomeVisualization/` unless stated otherwise.

| Surface | Entry | Responsibility |
| --- | --- | --- |
| Chromosome workspace | `index.jsx` | Navigation, chromosome/band/gene/variant focus, case overlay, and opening Analysis Lab. |
| Genome views | `components/OverviewIdeogram.jsx`, `components/ChromosomeDetail.jsx`, `components/BandZoom.jsx`, `components/GeneZoom.jsx`, `components/VariantZoom.jsx` | Progressive genomic navigation and linked selection; `components/DiseaseDetailDrawer.jsx` presents disease details. |
| Analysis Lab | `components/VariantAnalysisDrawer.jsx` | Input, readiness, job submission/restoration, polling, server-paged candidates, filters, detail tabs, exports, and the candidate ACMG dialog. It is embedded in the workspace, not a separate route. |
| Candidate ACMG evaluation | `components/TopNAcmgEvaluationPanel.jsx`, `components/OrdinaryAcmgEvaluationPanel.jsx`, `components/AcmgEvidenceMatrix.jsx`, `utils/acmgInput.js` | With an analysis ID, read formal Top-N input options, review server recommendations, explicitly execute/save evidence evaluation, and read saved runs. |
| Teaching route | `src/pages/varvis/GroupDemo/index.jsx` at `/varvis/group-demo` | Reuses `TopNAcmgEvaluationPanel` without an analysis ID, selecting `TeachingAcmgEvaluationPanel`. Teaching and ordinary candidate inputs remain separate. |

## Core flow and API boundaries

- The workspace owns chromosome selection, focus and `activeCase` state. Its
  `useCytobandData` hook and `services/cytobands.js` / `geneZoom.js` use
  `/variantBasedMap/**`. The case upload uses `/variantBasedMap/cases/vcf` and
  currently sends a fixed `GRCh38` assembly. Clearing this overlay does not delete
  Analysis Lab jobs. A knowledge-base failure still leaves Analysis Lab accessible.
- Analysis Lab owns its selected file, assembly, sample, HPO/trio inputs, job,
  paging/filter state, selected candidate and evidence requests. Its Axios service
  is `services/variantAnalysis.js`, using `/variant-analyses/**`. These inputs and
  the workspace case overlay are distinct data flows.
- The analysis form accepts `.vcf` / `.vcf.gz`, detects sample IDs, offers GRCh37
  or GRCh38, WES/WGS mode and `STANDARD` / `INCLUDE_INTRONS` scope. Its multipart
  file field is `vcfFile`; the current form has no separate `.tbi` field. HPO
  presence, explicit absence and manual provenance are preserved. Joint-VCF trio
  input requires explicit proband/father/mother sample IDs, sex and affected status;
  the product does not merge three separate family VCFs.
- Submission checks `/preflight` for the selected assembly. Required readiness
  failures block submission; provider availability is separately read from
  `/providers`. Jobs and events are polled; a returned READY reuse result is loaded
  directly. Closing the panel cancels detail/explanation requests and clears the
  explanation cache; it does not cancel a backend job. Job polling is keyed to
  job identity/status, not panel visibility.
- Candidate results use `/variants` with server-side view, search, filters and
  pagination (UI default 50, options 25/50/100). Preserve backend order and total
  counts. Detail and explanation requests prefer `/candidates/{candidateId}`;
  legacy records fall back to `/variants/{variantId}`. Candidate row identity and
  canonical allele identity are different: one allele can occupy multiple rows.
- Details separate Overview, Evidence, Score, Ranking explanation, Transcript and
  legacy ACMG-ready content. Ranking explanation loads only when opened, validates
  schema and analysis/candidate identity, and caches by that identity. It renders
  local descriptions of persisted data without recalculating rank; a 404 does not
  retry or alter the analysis job. Preserve request cancellation and stale-response
  guards when changing selection or task state.
- Preview and explicit Locate send focus callbacks to `index.jsx`; selection from
  a genome view opens Analysis Lab through `visualVariantSelection`. Preserve both
  directions and the separate evidence-detail close action. Prioritize current
  focus in the genome view and candidate comparison/evidence in Analysis Lab.
- Exports retain the current view and filters. Interactive CSV is bounded to the
  first 5,000 rows in the filtered server ordering; JSON currently requests 50.
  Neither is a claim of complete export of the ranking universe.
- For a READY job, the candidate ACMG dialog uses `/{id}/acmg/input-options` and
  `/{id}/acmg/runs` beneath `/variant-analyses`. Default Top-N is 20 (1–100), with
  no padding when fewer candidates exist. Execution requires explicit confirmation;
  enabled rules and matrix columns come from the server profile/saved run. Keep
  unavailable, unassessed and not-met states distinct and preserve saved provenance.

## Stack, design and scientific semantics

- React 18, Umi 4, MUI 5 with Emotion, Axios, Ideogram and LESS. State primarily
  uses component hooks and callback props; reuse these boundaries and existing UI
  components rather than adding a new global store or component system.
- [src/app.tsx](src/app.tsx) owns the light MUI theme: primary blue `#1d5fae`,
  neutral surfaces, semantic status colors, 14 px base text and 6–8 px component
  radii. [src/layouts/index.tsx](src/layouts/index.tsx) owns the viewport shell,
  main-content target and keyboard skip link.
- The workspace imports `styles.less` first and `ui-polish.less` second. The latter
  holds `--cv-*` design tokens and responsive refinements scoped through existing
  `cv-*` classes. Check both layers and MUI `sx` before changing a rule. Keep dense
  tables/ideograms internally scrollable; the app shell suppresses page scrolling.
  Analysis Lab becomes a fixed right overlay at 1180 px and fits the viewport at
  narrow widths. Preserve visible focus, Escape/close behavior, labels and reduced
  motion; statuses need text as well as color.
- Candidate rank, algorithm scores, computational predictions, ClinVar assertions,
  ACMG criterion evaluation and final clinical classification are distinct.
  Missing data is not zero; `NO_RECORD`, `UNAVAILABLE`, `NOT_APPLICABLE`,
  `NOT_EVALUATED` and `NOT_MET` must not become a benign label. Provider readiness
  does not guarantee per-variant evidence. Legacy ACMG-ready suggestions remain
  compatibility presentation, not v2 criterion results.
- Preserve original input assembly, analysis/evidence assembly, REF/ALT and
  transcript provenance. Under the current Exomiser product route, GRCh37 input
  is lifted by the backend to GRCh38 with explicit provenance and rejection state
  ([D-082](../docs/execution/DECISIONS.md#d-082-exomiser-owns-the-current-product-assembly-route));
  the frontend must not perform or assume its own coordinate conversion. Ordinary
  ACMG source applicability is narrower than accepted analysis uploads; do not
  infer eligibility from a READY job or bypass server capability gates.

## Development and validation

`package.json` requires Node >=20 and npm >=9. Use `npm start` (`umi dev`) with
existing dependencies; `start:dev` / `start:test` select their proxy environments.
[config/proxy.js](config/proxy.js) proxies both API prefixes to `VARVIS_API_BASE`
from local configuration, defaulting to `http://localhost:8086`. Production hosting
must route these relative API prefixes too. Follow repository rules for ignored
local configuration and the existing D-drive build dependency cache; do not
replace its dependency junction or install a second copy for routine work.

Run commands from `varvis-client-pro/`, selecting only checks relevant to the change:

| Change | Existing verification |
| --- | --- |
| JS/JSX or LESS | Focused existing ESLint/Stylelint invocation; module-wide `npm run lint:js` / `npm run lint:style` when warranted. |
| Imports, configuration or broad component integration | `npm run build`. |
| Candidate identity or manual HPO input | `npm run test:contract:candidate-identity`, `npm run test:contract:hpo-input`. |
| Ordinary ACMG inputs, saved output or matrix semantics | `node scripts/acmg-input-contract-smoke.mjs` (not a package-script alias). |
| Teaching ACMG flow | `npm run test:contract:acmg-teaching`. |
| Ranking explanation interaction | Optional `npm run test:e2e:ranking-explanation`, after setting `VARVIS_RUN_RANKING_EXPLANATION_E2E=true`; requires a running frontend and externally provided Playwright. Uses generated synthetic input and mocked API responses. |

`test:e2e:trio-weighted` is also opt-in and depends on a running frontend/backend;
check its scenario against the current joint-VCF product boundary before using it.
Skipped or mocked runs are not real-resource acceptance. Source/input contract
scripts do not prove browser interaction correctness.

For visual changes, inspect the target workflow at a main desktop size (for
example 1440×900) and a representative narrow size (for example 390×844), including
overflow, accessible controls, loading/error/empty states and affected interactions.
Use synthetic or approved de-identified data. Finish with `git diff --check` and
review the exact changed/staged scope. Documentation-only work needs link/source
checks, not an automatic full build or backend test run.
