import { isPvs1SpliceSummary, inspectPvs1SpliceCriterion, validatePvs1SpliceCriterion, pvs1SpliceDetails,
    isPvs1CodingEscapeAssessment, inspectPvs1CodingEscapeAssessment, pvs1CodingEscapeDetails } from './acmgPvs1Splice.js';
import { isDeNovoMethodsSummary, isDeNovoMethodsBinding, deNovoMethodsBindingValid, inspectDeNovoMethodsPair, validateDeNovoMethodsPair, deNovoMethodsResultDetails, deNovoSourceDiscoveryValid } from './acmgDeNovoMethods.js';
import { isPp1FormalEvaluation, validatePp1FormalCriterion, pp1FormalResultDetails } from './acmgPp1Formal.js';
import { sequenceEditSummaryValid, sequenceEditDetails, sourceExecutionDetails, sequenceSourceExecutionValid, populationStrataClaim, populationStrataResultValid, populationStrataDetails, populationRuntimeBindingValid, populationSourceRoutingValid, populationRoutingDetails } from './acmgSequencePopulation.js';
import { inspectProteinLengthProof, proteinLengthProofDetails } from './acmgProteinLengthProof.js';
import { inspectPublishedConditionCriterion, publishedConditionDetails } from './acmgPublishedConditionProof.js';
import { pp2HcmClaimed, pp2HcmCriterionValid } from './acmgPp2HcmProof.js';
import { inspectBa1Conditional } from './acmgBa1Conditional.js';
import { fullRnaCriterionClaim, inspectFullRnaCriterion, rememberFullRnaProjection, fullRnaSummary } from './acmgFullRna.js';
import { pm3V4BindingClaim, pm3V4BindingValid, pm3V4FactsValid } from './acmgPm3Conditions.js';

const PROTEIN_LENGTH_PROOF_UNAVAILABLE = '当前序列材料已核对，但本次公开采用证明不可用；保留原始结论，不将其解释为病例资料不足。';
const PVS1_CODING_PROOF_UNAVAILABLE = 'PVS1 编码逃逸公开资料或来源资格尚不能完成采用核对；原始状态与强度保留，不将其解释为病例资料不足。';
const strings = a => Array.isArray(a) && a.length <= 256 && a.every(s => typeof s === 'string' && s.length <= 32768);
// Internal runtime releases are not scientific adoption decisions. Within the
// supported major, validate the facts schema/fields and exact run binding below
// instead of maintaining a list of every additive minor/patch release.
const compatibleRuntimeVersion = (value, major) => typeof value === 'string' &&
    /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(value) && value.split('.')[0] === String(major);
export function validLiteratureSource(c, paperId) {
    return !!c && /^[a-f0-9]{64}$/.test(c.sourceSha256 || '') && ['PAPER_PRIMARY', 'PAPER_ATTACHMENT'].includes(c.role) &&
        c.kind === 'SOURCE_UNIT' && c.paperId === paperId && /^PMC[0-9]+$/.test(paperId) &&
        ['quote', 'locator', 'title', 'locationLabel'].every(k => typeof c[k] === 'string' && c[k].length <= 32768) &&
        !!c.locator && !!c.locationLabel && c.url === `https://pmc.ncbi.nlm.nih.gov/articles/${paperId}/` &&
        c.linkScope === 'ARTICLE_LANDING_PAGE' && typeof c.scopeOnly === 'boolean' && (c.scopeOnly ? c.quote === '' : !!c.quote);
}
export function validLiteratureExplanation(e, m) {
    const paperId = m.caseKey.split('#')[0], paths = new Set(m.fields.map(f => f.path));
    const text = (o, keys) => keys.every(k => typeof o[k] === 'string' && o[k].length > 0 && o[k].length <= 32768);
    const refs = a => Array.isArray(a) && a.length <= 256 && a.every(c => validLiteratureSource(c, paperId));
    if (!e || e.schemaVersion !== 'literature-explanation-v1' || e.caseKey !== m.caseKey || e.paper?.paperId !== paperId ||
        !text(e, ['summary', 'scopeStatement']) || !text(e.paper, ['title']) || e.paper.url !== `https://pmc.ncbi.nlm.nih.gov/articles/${paperId}/` ||
        !Array.isArray(e.items) || e.items.length > 256 || !Array.isArray(e.warnings) || e.warnings.length > 256 ||
        (m.status === 'READY_FOR_EVALUATION' && e.items.length) ||
        !m.fields.every(f => refs(f.sourceEvidence))) return false;
    const ids = new Set(), covered = new Set();
    for (const item of e.items) {
        if (!item || !text(item, ['id', 'category', 'label', 'requirement', 'finding', 'explanation', 'distinction', 'nextStep', 'effect', 'scopeStatement']) ||
            ids.has(item.id) || !strings(item.fieldPaths) || !item.fieldPaths.length || item.fieldPaths.some(p => !paths.has(p)) ||
            !strings(item.reasonCodes) || !strings(item.observedFieldPaths) || item.observedFieldPaths.some(p => !m.fields.some(f => f.path === p && f.verification === 'VERIFIED' && f.availability === 'REPORTED')) ||
            !refs(item.checkedScope) || item.checkedScope.some(c => !c.scopeOnly) ||
            item.fieldPaths.some(p => !m.blockers.some(b => b && b.path === p && b.category === item.category))) return false;
        ids.add(item.id); item.fieldPaths.forEach(p => covered.add(p));
    }
    return m.blockers.every(b => b && covered.has(b.path)) && e.warnings.every(w => w && text(w, ['text']) && validLiteratureSource(w.source, paperId) && !w.source.scopeOnly);
}
const followUpReportsPath = '/groups/overlap/followUpReports';
const eventGroupingPath = '/groups/overlap/eventGrouping';
const sameCaseFollowUp = 'VERIFIED_FOLLOW_UP_SAME_EVENT';
function followUpSourcePins(material, primary) {
    const reports = material.fields.find(f => f?.path === followUpReportsPath);
    const grouping = material.fields.find(f => f?.path === eventGroupingPath);
    const pins = new Map();
    if (!reports && grouping?.value !== sameCaseFollowUp) return pins;
    const reviewed = f => f?.origin === 'REVIEWED_ASSESSMENT' && f.subject === 'PROBAND' && f.verification === 'VERIFIED' &&
        f.availability === 'REPORTED' && Array.isArray(f.sourceEvidence) && f.sourceEvidence.length <= 256;
    if (material.dataClass !== 'PUBLIC_PAPER_RESEARCH' || grouping?.value !== sameCaseFollowUp || !reviewed(reports) || !reviewed(grouping) ||
        !Array.isArray(reports.value) || reports.value.length < 1 || reports.value.length > 4) return null;
    for (const label of reports.value) {
        const id = typeof label === 'string' && /^(PMC[1-9][0-9]*) · .{1,256}$/.exec(label)?.[1];
        if (!id || id === primary || pins.has(id)) return null;
        const cited = field => field.sourceEvidence.filter(c => c?.paperId === id && c.role === 'PAPER_PRIMARY');
        const fromGrouping = new Set(cited(grouping).map(c => c.sourceSha256));
        const fromReports = cited(reports);
        if (fromGrouping.size !== 1 || !fromReports.length || !fromReports.every(c => fromGrouping.has(c.sourceSha256))) return null;
        pins.set(id, fromGrouping.values().next().value);
    }
    if (![reports, grouping].every(f => f.sourceEvidence.some(c => c?.paperId === primary && c.role === 'PAPER_PRIMARY'))) return null;
    return pins;
}
export function validCoreMaterial(v) {
    if (v && Object.hasOwn(v, 'sourceDiscovery') && !deNovoSourceDiscoveryValid(v.sourceDiscovery)) return false;
    if (!v || v.schemaVersion !== 'denovo-core-display-v1' || !['SYNTHETIC', 'PUBLIC_PAPER_RESEARCH'].includes(v.dataClass) ||
        !['READY_FOR_EVALUATION', 'INSUFFICIENT_DATA', 'NOT_APPLICABLE', 'SOFTWARE_UNSUPPORTED'].includes(v.status) || v.criterionDecision !== 'NOT_RUN' ||
        typeof v.caseKey !== 'string' || !/^PMC[0-9]+#.{1,256}$/.test(v.caseKey) || !['documentSha256', 'ledgerSha256'].every(k => /^[a-f0-9]{64}$/.test(v[k] || '')) ||
        !Array.isArray(v.fields) || v.fields.length > 256 || !Array.isArray(v.blockers) || v.blockers.length > 256 || !strings(v.coverageLimitations) ||
        (v.status === 'READY_FOR_EVALUATION' && v.blockers.length) ||
        (Object.hasOwn(v, 'selectionMatch') && !['EXACT', 'DIFFERENT_TARGET', 'UNRESOLVED'].includes(v.selectionMatch))) return false;
    const primary = v.caseKey.split('#')[0];
    const followUps = followUpSourcePins(v, primary);
    if (!followUps) return false;
    const paths = new Set();
    for (const f of v.fields) {
        const followUpField = f?.path === followUpReportsPath || f?.path === eventGroupingPath;
        const sourcePaper = c => followUpField && c?.role === 'PAPER_PRIMARY' && followUps.has(c.paperId) &&
            followUps.get(c.paperId) === c.sourceSha256 ? c.paperId : primary;
        if (!f || typeof f.path !== 'string' || !/^\/(groups|context|extensions|conditions)\/[A-Za-z0-9_/]+$/.test(f.path) || paths.has(f.path) ||
            !Object.hasOwn(f, 'value') || JSON.stringify(f.value).length > 65536 ||
            !['REPORTED', 'NOT_EXTRACTED', 'NOT_REPORTED_IN_CHECKED_SCOPE', 'SOURCE_UNAVAILABLE', 'NOT_APPLICABLE'].includes(f.availability) ||
            !['VERIFIED', 'UNVERIFIED', 'CONFLICT', 'UNSUPPORTED'].includes(f.verification) ||
            !strings(f.issues) || !Array.isArray(f.citations) || f.citations.length > 256 ||
            (Object.hasOwn(f, 'sourceEvidence') && (!Array.isArray(f.sourceEvidence) || f.sourceEvidence.length > 256 ||
                f.sourceEvidence.some(c => !validLiteratureSource(c, sourcePaper(c)) || c.scopeOnly))) ||
            f.citations.some(c => !c || !/^[a-f0-9]{64}$/.test(c.sourceSha256 || '') || typeof c.quote !== 'string' || c.quote.length > 32768 || typeof c.locator !== 'string' || c.locator.length > 32768) ||
            (f.verification === 'VERIFIED' && f.availability === 'REPORTED' && f.value === null)) return false;
        paths.add(f.path);
    }
    return (!Object.hasOwn(v, 'explanation') || validLiteratureExplanation(v.explanation, v)) &&
        ['paper', 'case', 'gene', 'variant', 'proband', 'mother', 'father', 'testing', 'parentage', 'phenotype', 'family', 'overlap'].every(g => v.fields.some(f => f.path.startsWith(`/groups/${g}/`))) &&
        v.blockers.every(b => b && paths.has(b.path) && typeof b.category === 'string' && typeof b.code === 'string' &&
            (!Object.hasOwn(b, 'causeCodes') || strings(b.causeCodes)) &&
            ['NO_CLAIM_OF_SOURCE_ABSENCE', 'DECLARED_SOURCE_SCOPE_ONLY', 'QUALIFICATION_REQUIRED'].includes(b.meaning));
}

const LOCAL_BATCH_PROFILE = 'acmg-local-computational-batch1-v1';
const LOCAL_BATCH_DECLARATION = 'ordinary-local-computational-batch1-v1';
const LOCAL_BATCH_CONTEXT = 'SELECTED_LOCAL_BATCH1_V1';
const LOCAL_BATCH_CRITERIA = ['PM2', 'PP3', 'BP4', 'PS1', 'PM5'].map((code) => `ACMG_AMP_2015:${code}`);
const LOCAL_BATCH_PM4_CRITERIA = [...LOCAL_BATCH_CRITERIA, 'ACMG_AMP_2015:PM4'];
const BP7_BATCH_CRITERIA = [...LOCAL_BATCH_CRITERIA, 'ACMG_AMP_2015:BP7'];
const UNIFIED_PROFILE = 'acmg-unified-local-eight-v1';
const UNIFIED_DECLARATION = 'ordinary-unified-local-eight-v1';
const UNIFIED_CRITERIA = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1', ...BP7_BATCH_CRITERIA];
const ELEVEN_PROFILE = 'acmg-unified-local-eleven-v1';
const ELEVEN_DECLARATION = 'ordinary-unified-local-eleven-v1';
const FOURTEEN_PROFILE = 'acmg-unified-local-fourteen-v1';
const FOURTEEN_DECLARATION = 'ordinary-unified-local-fourteen-v1';
const EXTENDED_BATCH_CRITERIA = [...BP7_BATCH_CRITERIA, 'ACMG_AMP_2015:PVS1', 'ACMG_AMP_2015:PM4', 'ACMG_AMP_2015:BP3'];
const ELEVEN_CRITERIA = [...UNIFIED_CRITERIA.slice(0, 2), ...EXTENDED_BATCH_CRITERIA];
const PUBLISHED_BATCH_CRITERIA = [...EXTENDED_BATCH_CRITERIA, ...['PM1', 'PP2', 'BP1'].map(c => `ACMG_AMP_2015:${c}`)];
const FOURTEEN_CRITERIA = [...UNIFIED_CRITERIA.slice(0, 2), ...PUBLISHED_BATCH_CRITERIA];
const DENOVO_CRITERIA = ['ACMG_AMP_2015:PS2', 'ACMG_AMP_2015:PM6'];
const PP1_LITERATURE_ID = 'ACMG_AMP_2015:PP1';
const pp1LiteratureScope = value => value?.executionProfile?.profileId === 'acmg-unified-local-pp1-v1' ||
    Object.hasOwn(value?.executionProfile?.moduleBindings || {}, 'pp1');
function pp1LiteratureBase(value) {
    const p = value.executionProfile, modules = { ...p.moduleBindings }; delete modules.pp1;
    const profileId = p.profileId === 'acmg-unified-local-pp1-v1'
        ? modules.pm3 ? 'acmg-unified-local-pm3-v1' : modules.deNovo ? 'acmg-unified-local-denovo-v1' :
            ({ [UNIFIED_DECLARATION]: UNIFIED_PROFILE, [ELEVEN_DECLARATION]: ELEVEN_PROFILE, [FOURTEEN_DECLARATION]: FOURTEEN_PROFILE })[p.requiredDeclaration]
        : p.profileId;
    return { ...value, enabledCriteria: value.enabledCriteria?.filter(id => id !== PP1_LITERATURE_ID),
        executionProfile: { ...p, profileId, moduleBindings: modules, enabledCriteria: p.enabledCriteria?.filter(id => id !== PP1_LITERATURE_ID) } };
}
function pp1LiteratureBindingValid(p) {
    const b = p?.moduleBindings?.pp1;
    return b?.version === 'pp1-selected-literature-v1' && ['pp1-paper-job-v1', 'pp1-paper-job-v2'].includes(b.source?.version) &&
        (/^[a-f0-9]{64}$/.test(b.source.workflowConfigSha256 || '') || b.source.status === 'CONFIGURATION_UNAVAILABLE') &&
        ['pm2', 'prediction', 'reference'].every(k => JSON.stringify(p.moduleBindings?.[k]?.runtimeBinding?.pp1) === JSON.stringify(b));
}
const PM3_LITERATURE_ID = 'ACMG_AMP_2015:PM3';
const pm3LiteratureScope = value => value?.executionProfile?.profileId === 'acmg-unified-local-pm3-v1' ||
    Object.hasOwn(value?.executionProfile?.moduleBindings || {}, 'pm3');
function pm3LiteratureBase(value) {
    const p = value.executionProfile, modules = { ...p.moduleBindings }; delete modules.pm3;
    const profileId = p.profileId === 'acmg-unified-local-pm3-v1'
        ? modules.deNovo ? 'acmg-unified-local-denovo-v1' :
            ({ [UNIFIED_DECLARATION]: UNIFIED_PROFILE, [ELEVEN_DECLARATION]: ELEVEN_PROFILE, [FOURTEEN_DECLARATION]: FOURTEEN_PROFILE })[p.requiredDeclaration]
        : p.profileId;
    return { ...value, enabledCriteria: value.enabledCriteria?.filter(id => id !== PM3_LITERATURE_ID),
        executionProfile: { ...p, profileId, moduleBindings: modules, enabledCriteria: p.enabledCriteria?.filter(id => id !== PM3_LITERATURE_ID) } };
}
function pm3LiteratureBindingValid(p) {
    const b = p?.moduleBindings?.pm3;
    const version = { 'pm3-selected-literature-v1': 'pm3-literature-assessment-v1',
        'pm3-selected-literature-v2': 'pm3-literature-assessment-v2',
        'pm3-selected-literature-v3': 'pm3-literature-assessment-v2',
        'pm3-selected-literature-v4': 'pm3-literature-assessment-v3' }[b?.version];
    return Boolean(version) && b.assessmentVersion === version && (!pm3V4BindingClaim(b)||pm3V4BindingValid(b)) &&
        b.source?.version === 'pm3-business-source-v1' && (/^[a-f0-9]{64}$/.test(b.source.workflowConfigSha256 || '') ||
            b.source.status === 'PM3_WORKFLOW_CONFIGURATION_UNAVAILABLE') &&
        ['pm2', 'prediction', 'reference'].every(k => JSON.stringify(p.moduleBindings?.[k]?.runtimeBinding?.pm3) === JSON.stringify(b));
}
export const deNovoLocalScope = value => ['acmg-local-material-and-computational-v1', 'acmg-unified-local-denovo-v1'].includes(value?.executionProfile?.profileId);
// Project only the transport scope for the existing checks; never modify the saved result.
function deNovoBase(value) {
    const p = value?.executionProfile;
    if (!deNovoLocalScope(value)) return value;
    const profileId = p.profileId === 'acmg-local-material-and-computational-v1' ? LOCAL_BATCH_PROFILE
        : ({ [UNIFIED_DECLARATION]: UNIFIED_PROFILE, [ELEVEN_DECLARATION]: ELEVEN_PROFILE, [FOURTEEN_DECLARATION]: FOURTEEN_PROFILE })[p.requiredDeclaration];
    const modules = { ...p.moduleBindings }; delete modules.deNovo;
    return { ...value, enabledCriteria: value.enabledCriteria?.filter(id => !DENOVO_CRITERIA.includes(id)),
        executionProfile: { ...p, profileId, moduleBindings: modules, enabledCriteria: p.enabledCriteria?.filter(id => !DENOVO_CRITERIA.includes(id)) } };
}
function deNovoModelStatusValid(status, binding) {
    const replay = binding?.modelArtifactReplay;
    if (!replay) return status === 'DEFERRED_NOT_CONFIGURED';
    if (replay.version !== 'denovo-literature-model-replay-1.0.0' ||
        !/^[0-9a-f]{64}$/.test(replay.artifactIndexSha256 || '') || replay.automaticModelCalls !== false ||
        replay.sourceScope !== 'PUBLIC_PAPER_ONLY') return false;
    return ['DEFERRED_NOT_CONFIGURED', 'MODEL_CANDIDATES_SOURCE_REPLAYED_NOT_CLINICALLY_VALIDATED'].includes(status) ||
        status === 'SYNTHETIC_MODEL_REPLAY_ONLY' && binding.use === 'SYNTHETIC_VALIDATION';
}
function deNovoBindingValid(p) {
    const b = p?.moduleBindings?.deNovo;
    if (isDeNovoMethodsBinding(b)) return deNovoMethodsBindingValid(b) &&
        ['pm2', 'prediction', 'reference'].every(k => JSON.stringify(p.moduleBindings?.[k]?.runtimeBinding?.deNovo) === JSON.stringify(b));
    const supply = b?.sourceSupply;
    const supplyValid = !supply || ['denovo-selected-batch-1.3.0', 'denovo-selected-batch-1.4.0'].includes(b.version) &&
        supply.version === 'denovo-selected-literature-supply-1.0.0' && typeof supply.publicNetworkEnabled === 'boolean' &&
        supply.scope === 'EXACT_PINNED_CLINVAR_CITATIONS_ONLY' && supply.modelCalls === 0 && supply.completeLiteratureSearch === false &&
        (!Object.hasOwn(supply, 'executionMode') || supply.executionMode === 'ASYNCHRONOUS' && supply.taskVersion === 'denovo-source-task-v1') &&
        (!['retryPolicy', 'maximumAttempts'].some(k => Object.hasOwn(supply, k)) || supply.executionMode === 'ASYNCHRONOUS' &&
            supply.retryPolicy === 'denovo-source-explicit-retry-v1' && supply.maximumAttempts === 3) &&
        ['supply_selected_literature.py', 'fetch_public_literature.py', 'register_materials.py', 'prepare_literature.py', 'literature_allele_identity.py']
            .every(k => /^[0-9a-f]{64}$/.test(supply.implementation?.[k] || ''));
    return supplyValid && (b?.literatureQualification === 'SELECTED_SOURCE_SUPPLY_CONFIGURED' ? !!supply : !supply) &&
        ['denovo-selected-batch-1.0.0', 'denovo-selected-batch-1.1.0', 'denovo-selected-batch-1.2.0', 'denovo-selected-batch-1.3.0', 'denovo-selected-batch-1.4.0'].includes(b?.version) &&
        /^[0-9a-f]{64}$/.test(b.manifestSha256 || '') && ['RESEARCH', 'SYNTHETIC_VALIDATION'].includes(b.use) &&
        b.calculatorVersion === 'denovo-event-calculator-1.0.0' &&
        ['denovo-local-evidence-1.0.0', 'denovo-local-evidence-1.1.0', 'denovo-local-evidence-1.2.0'].includes(b.qualifierVersion) &&
        ['denovo-phenotype-matcher-1.0.0', 'denovo-phenotype-matcher-1.1.0'].includes(b.phenotypeVersion) &&
        b.readerVersion === 'denovo-local-report-reader-1.1.0' && b.modelCalls === 0 && deNovoModelStatusValid(b.modelValidation, b) &&
        typeof b.literatureQualification === 'string' &&
        (b.literatureQualification !== 'SOURCE_REPLAYED_FOR_PER_TARGET_QUALIFICATION' ||
            ['denovo-literature-evidence-1.0.0', 'denovo-literature-evidence-1.1.0', 'denovo-literature-evidence-1.2.0', 'denovo-literature-evidence-1.2.1', 'denovo-literature-evidence-1.3.0', 'denovo-literature-evidence-1.4.0'].includes(b.literatureVersion) && b.literatureReaderVersion ===
                (b.modelArtifactReplay ? 'denovo-literature-model-replay-1.0.0' : 'denovo-literature-batch-reader-1.0.0') && /^[0-9a-f]{64}$/.test(b.literatureSourceResultSha256 || '')) &&
        ['pm2', 'prediction', 'reference'].every(k => JSON.stringify(p.moduleBindings?.[k]?.runtimeBinding?.deNovo) === JSON.stringify(b));
}
const publishedLocalScope = (value) => value?.executionProfile?.profileId === FOURTEEN_PROFILE ||
    value?.executionProfile?.profileId === LOCAL_BATCH_PROFILE && value?.executionProfile?.profileVersion === '1.5.0-published-three';
export const unifiedLocalScope = (value) => [UNIFIED_PROFILE, ELEVEN_PROFILE, FOURTEEN_PROFILE, 'acmg-unified-local-denovo-v1', 'acmg-unified-local-pm3-v1', 'acmg-unified-local-pp1-v1'].includes(value?.executionProfile?.profileId);
const extendedLocalScope = (value) => publishedLocalScope(value) || value?.executionProfile?.profileId === ELEVEN_PROFILE ||
    value?.executionProfile?.profileId === LOCAL_BATCH_PROFILE && value?.executionProfile?.profileVersion === '1.4.0-extended';
const batchCriteriaFor = value => value?.executionProfile?.criterionSelection ? value.executionProfile.enabledCriteria : configuredBatchCriteriaFor(value);
const configuredBatchCriteriaFor = (value) => pp1LiteratureScope(value) ? [...batchCriteriaFor(pp1LiteratureBase(value)), PP1_LITERATURE_ID]
    : pm3LiteratureScope(value) ? [...batchCriteriaFor(pm3LiteratureBase(value)), PM3_LITERATURE_ID]
    : deNovoLocalScope(value) ? [...batchCriteriaFor(deNovoBase(value)), ...DENOVO_CRITERIA]
    : value?.executionProfile?.profileId === FOURTEEN_PROFILE ? FOURTEEN_CRITERIA
    : publishedLocalScope(value) ? PUBLISHED_BATCH_CRITERIA : value?.executionProfile?.profileId === ELEVEN_PROFILE ? ELEVEN_CRITERIA
    : unifiedLocalScope(value) ? UNIFIED_CRITERIA : extendedLocalScope(value) ? EXTENDED_BATCH_CRITERIA
    : value?.executionProfile?.profileVersion === '1.3.0-bp7-pm4' ? [...BP7_BATCH_CRITERIA, 'ACMG_AMP_2015:PM4']
    : value?.executionProfile?.profileVersion === '1.2.0' ? LOCAL_BATCH_PM4_CRITERIA
    : value?.executionProfile?.profileVersion === '1.2.0-bp7' ? BP7_BATCH_CRITERIA : LOCAL_BATCH_CRITERIA;
const batchDeclarationFor = (value) => pp1LiteratureScope(value) ? batchDeclarationFor(pp1LiteratureBase(value))
    : pm3LiteratureScope(value) ? batchDeclarationFor(pm3LiteratureBase(value))
    : deNovoLocalScope(value) ? batchDeclarationFor(deNovoBase(value))
    : value?.executionProfile?.profileId === FOURTEEN_PROFILE ? FOURTEEN_DECLARATION
    : value?.executionProfile?.profileId === ELEVEN_PROFILE ? ELEVEN_DECLARATION
    : unifiedLocalScope(value) ? UNIFIED_DECLARATION : LOCAL_BATCH_DECLARATION;
const localBatchText = (value) => typeof value === 'string' && value.trim().length > 0;
const localBatchObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const PM2_PROJECT_SCOPE = {
    schemaVersion: 'pm2-project-local-scope-v1', dataset: 'EXOMES', release: '4.1',
    interpretation: 'LOCAL_DATASET_CRITERION_ONLY', wholeGnomadConclusion: false,
    clinicalAdoption: 'NOT_ESTABLISHED', externalSpecificationEquivalence: 'NOT_ESTABLISHED',
    allowedQueryOutcome: 'HIT', allowNoRecord: false, allowMissingFafSingleton: false,
    allowWholeDatabaseHomozygoteRequirement: false, reviewOwnership: 'IMPLEMENTER_TECHNICAL_REVIEW',
};
const PM2_ABSENCE_SCOPE = { ...PM2_PROJECT_SCOPE, schemaVersion: 'pm2-project-local-scope-v2',
    allowedQueryOutcome: 'HIT_OR_NO_RECORD', allowNoRecord: true };
const PM2_REGION_FLAGS = ['outside_broad_capture_region', 'outside_ukb_capture_region',
    'outside_broad_calling_region', 'outside_ukb_calling_region'];
const pm2ProjectScopeValid = (scope) => localBatchObject(scope) && [PM2_PROJECT_SCOPE, PM2_ABSENCE_SCOPE].some((expected) =>
    Object.keys(scope).length === Object.keys(expected).length &&
    Object.entries(expected).every(([key, value]) => scope[key] === value));
const pm2ProjectCriterionValid = (criterion) => {
    const claimed = criterion?.reasonCode?.startsWith('PM2_PROJECT_LOCAL_') ||
        Object.hasOwn(criterion?.details || {}, 'populationScope');
    if (!claimed) return true;
    const absence = criterion.reasonCode === 'PM2_PROJECT_LOCAL_ABSENCE_MET';
    const facts = criterion.details?.populationFacts;
    if (absence && !(criterion.status === 'MET' && criterion.details?.populationScope?.allowNoRecord === true &&
        facts?.schemaVersion === 'pm2-local-facts-v1' && facts.dataset === 'EXOMES' && facts.release === '4.1' &&
        facts.queryStatus === 'NO_RECORD' && facts.detection?.status === 'HIT' && /^[1-9][0-9]{0,6}$/.test(facts.detection?.adjAn || '') &&
        Number(facts.detection.adjAn) <= 1461894 &&
        facts.detection.stratum === 'ALL_RELEASE_SAMPLES_ADJ' && facts.detection.ancestryStratified === false &&
        PM2_REGION_FLAGS.every((key) => facts.detection.regionFlags?.[key] === false))) return false;
    return criterion.criterionId === 'ACMG_AMP_2015:PM2' && pm2ProjectScopeValid(criterion.details?.populationScope) &&
        (criterion.status === 'NOT_EVALUATED' || absence || criterion.reasonCode === `PM2_PROJECT_LOCAL_HIT_${criterion.status}`);
};

export function localBatchScopeClaim(value) {
    const p = value?.executionProfile;
    return deNovoLocalScope(value) || [LOCAL_BATCH_PROFILE, UNIFIED_PROFILE, ELEVEN_PROFILE, FOURTEEN_PROFILE].includes(p?.profileId) || p?.contextPreparation === LOCAL_BATCH_CONTEXT ||
        [p?.requiredDeclaration, value?.requiredDeclaration, value?.declarationVersion].some((d) => [LOCAL_BATCH_DECLARATION, UNIFIED_DECLARATION, ELEVEN_DECLARATION, FOURTEEN_DECLARATION].includes(d)) ||
        [p?.enabledCriteria, value?.enabledCriteria].some((ids) => Array.isArray(ids) && [5, 6, 7, 8, 9, 11, 12, 14].includes(ids.length) &&
            LOCAL_BATCH_CRITERIA.every((id) => ids.includes(id))) ||
        Object.hasOwn(value || {}, 'localBatch');
}

// Restore only the configured transport scope for existing binding checks. Never invent criterion results.
function configuredSelectionView(value) {
    const p = value?.executionProfile, scope = p?.criterionSelection;
    if (!scope) return value;
    const { criterionSelection, ...configured } = p;
    return { ...value, enabledCriteria: scope.configuredCriteria, executionProfile: {
        ...configured, enabledCriteria: scope.configuredCriteria, manifestSha256: scope.configuredManifestSha256 } };
}
function selectionScopeIssue(value) {
    const p = value?.executionProfile, scope = p?.criterionSelection;
    if (!scope) return null;
    const actual = p.enabledCriteria, available = scope.configuredCriteria;
    if (scope.schemaVersion !== 'acmg-criterion-selection-v1' || !Array.isArray(available) || !Array.isArray(actual) ||
        !actual.length || new Set(actual).size !== actual.length || !/^[0-9a-f]{64}$/.test(scope.configuredManifestSha256 || '') ||
        !/^[0-9a-f]{64}$/.test(p.manifestSha256 || '') ||
        JSON.stringify(actual) !== JSON.stringify(available.filter(id => actual.includes(id))) ||
        JSON.stringify(actual) !== JSON.stringify(value.enabledCriteria)) return '保存的证据选择范围不完整或不相容。';
    return baseLocalBatchScopeIssue(configuredSelectionView(value), actual);
}

/** Transport scope only: neither this check nor an explicit confirmation grants scientific eligibility. */
export function localBatchScopeIssue(value) {
    if (value?.executionProfile?.criterionSelection) return selectionScopeIssue(value);
    return baseLocalBatchScopeIssue(value);
}
function baseLocalBatchScopeIssue(value, selectedCriteria) {
    if (!localBatchScopeClaim(value)) return null;
    if (pp1LiteratureScope(value)) {
        const p = value.executionProfile, expected = batchCriteriaFor(value);
        if (!pp1LiteratureBindingValid(p) || p.clinicalValidationCompleted !== false ||
            JSON.stringify(p.enabledCriteria) !== JSON.stringify(expected) || JSON.stringify(value.enabledCriteria) !== JSON.stringify(expected))
            return 'PP1 文献评价的范围或来源绑定不完整；请重新读取，当前不能提交。';
        return localBatchScopeIssue(pp1LiteratureBase(value));
    }
    if (pm3LiteratureScope(value)) {
        const p = value.executionProfile, expected = batchCriteriaFor(value);
        if (!pm3LiteratureBindingValid(p) || p.clinicalValidationCompleted !== false ||
            JSON.stringify(p.enabledCriteria) !== JSON.stringify(expected) || JSON.stringify(value.enabledCriteria) !== JSON.stringify(expected))
            return 'PM3 文献评价的范围或来源绑定不完整；请重新读取，当前不能提交。';
        return localBatchScopeIssue(pm3LiteratureBase(value));
    }
    if (deNovoLocalScope(value)) {
        const p = value.executionProfile, expected = batchCriteriaFor(value);
        if (!deNovoBindingValid(p) || p.clinicalValidationCompleted !== false ||
            JSON.stringify(p.enabledCriteria) !== JSON.stringify(expected) || JSON.stringify(value.enabledCriteria) !== JSON.stringify(expected))
            return 'PS2/PM6 共同资料模式的范围、版本或来源绑定不完整；请重新读取，当前不能提交。';
        return localBatchScopeIssue(deNovoBase(value));
    }
    const p = value?.executionProfile;
    if (Object.hasOwn(p?.moduleBindings || {}, 'deNovo')) return '共同新生证据绑定必须使用对应执行身份，不能借用旧计算入口。';
    const contextPolicy = p?.interpretationContextPolicy;
    const unified = unifiedLocalScope(value);
    const extended = extendedLocalScope(value);
    const published = publishedLocalScope(value);
    const declaration = batchDeclarationFor(value);
    const bp7 = unified || extended || ['1.2.0-bp7', '1.3.0-bp7-pm4'].includes(p?.profileVersion);
    const pm4 = extended || !unified && ['1.2.0', '1.3.0-bp7-pm4'].includes(p?.profileVersion);
    const extensionValid = extended ? ['pvs1', 'pm4', 'bp3'].every((key) => {
        const module = p?.moduleBindings?.[key], b = module?.runtimeBinding;
        return module?.readiness === 'CHECKED_AT_SUBMIT' && module.scientificApproval === 'NOT_GRANTED_BY_CONFIGURATION' &&
            b?.pvs1Configured === true && b.pm4Configured === true && b.bp3Configured === true &&
            compatibleRuntimeVersion(b.pvs1SelectedVersion, 1) && b.pm4PolicyVersion === '1.2.0' && ['1.0.0', '1.1.0'].includes(b.bp3PolicyVersion) &&
            /^[0-9a-f]{64}$/.test(b.configurationSha256 || '') &&
            JSON.stringify(b) === JSON.stringify(p.moduleBindings?.pm4?.runtimeBinding);
    }) : ['pvs1', 'bp3'].every((key) => !Object.hasOwn(p?.moduleBindings || {}, key));
    const publishedValid = published ? ['pm1', 'pp2', 'bp1'].every((key) => {
        const module = p?.moduleBindings?.[key], b = module?.runtimeBinding;
        return module?.readiness === 'CHECKED_AT_SUBMIT' && module.scientificApproval === 'NOT_GRANTED_BY_CONFIGURATION' &&
            ['pm1Configured', 'pp2Configured', 'bp1Configured'].every(k => b?.[k] === true) &&
            b?.pm1GeneralVersion === '1.0.0' && compatibleRuntimeVersion(b?.pp2GeneralVersion, 1) &&
            ['1.0.0', '1.1.0', '1.2.0'].includes(b?.bp1GeneralVersion) &&
            typeof b.pp2GermlineDeclared === 'boolean' && p.pp2GermlineDeclared === b.pp2GermlineDeclared && /^[0-9a-f]{64}$/.test(b.configurationSha256 || '') &&
            JSON.stringify(b) === JSON.stringify(p.moduleBindings?.pm4?.runtimeBinding);
    }) : ['pm1', 'pp2', 'bp1'].every(key => !Object.hasOwn(p?.moduleBindings || {}, key));
    const population = p?.populationExecutionProfile;
    const populationValid = !unified || (population?.profileId === 'acmg-explicit-business-ba1-pm2-bs1-v1' &&
        /^[0-9a-f]{64}$/.test(population.manifestSha256 || '') &&
        JSON.stringify(population.enabledCriteria) === JSON.stringify(UNIFIED_CRITERIA.slice(0, 2)) &&
        (population.profileVersion === '4.1.0' && p.bs1RuntimeBinding?.activationContract === 'LOCAL_EXOMES_BUSINESS_V1' ||
            compatibleRuntimeVersion(population.profileVersion, 4) && Number(population.profileVersion.split('.')[1]) >= 2 &&
            populationRuntimeBindingValid(p.bs1RuntimeBinding) && p.populationDataScope?.policyId === 'POLICY_ROUTED_POPULATION_V1' &&
            p.populationDataScope.dataset === 'POLICY_SELECTED' &&
            JSON.stringify(p.populationDataScope.sourceRouting) === JSON.stringify(p.bs1RuntimeBinding.sourceRouting)) &&
        ['ba1RuntimeBinding', 'bs1RuntimeBinding', 'populationDataScope'].every((key) =>
            (localBatchObject(p[key]) || key === 'ba1RuntimeBinding' &&
                Array.isArray(selectedCriteria) && !selectedCriteria.includes('ACMG_AMP_2015:BA1') &&
                p[key] === null && population[key] === null) && JSON.stringify(p[key]) === JSON.stringify(population[key])));
    const binding = p?.moduleBindings?.bp7?.runtimeBinding;
    const bp7Valid = !bp7 ? !Object.hasOwn(p?.moduleBindings || {}, 'bp7') :
        binding?.method === 'BP7_SELECTED_SHARED_PREDICTION' &&
        ['1.0.0', '1.0.1', '1.0.2'].includes(binding.version) &&
        ['coreVersion', 'positionVersion', 'predictionDependencyVersion'].every((key) => binding[key] === '1.0.0') &&
        /^[0-9a-f]{64}$/.test(binding.policySha256 || '') && localBatchText(binding.experimentSnapshotId) &&
        ['AVAILABLE', 'UNAVAILABLE'].includes(binding.experimentSourceStatus) && binding.clinicalApproval === false;
    const contextValid = contextPolicy?.policyId === 'LOCAL_ALLELE_INTERPRETATION_CONTEXT' && ['1.0.0', '1.1.0'].includes(contextPolicy.policyVersion);
    const versionValid = unified ? p.profileVersion === '1.0.0' && contextValid
        : p?.profileVersion === '1.0.0' ? !contextPolicy : ['1.1.0', '1.2.0', '1.2.0-bp7', '1.3.0-bp7-pm4', '1.4.0-extended', '1.5.0-published-three'].includes(p?.profileVersion) && contextValid;
    if (![LOCAL_BATCH_PROFILE, UNIFIED_PROFILE, ELEVEN_PROFILE, FOURTEEN_PROFILE].includes(p?.profileId) || !versionValid || !bp7Valid || !populationValid || !extensionValid || !publishedValid ||
        (pm4 ? p.moduleBindings?.pm4?.runtimeBinding?.pm4Configured !== true : Object.hasOwn(p?.moduleBindings || {}, 'pm4')) ||
        p.contextPreparation !== LOCAL_BATCH_CONTEXT || p.requiredDeclaration !== declaration ||
        JSON.stringify(p.enabledCriteria) !== JSON.stringify(batchCriteriaFor(value)) ||
        JSON.stringify(value.enabledCriteria) !== JSON.stringify(batchCriteriaFor(value)) ||
        !['formalEnabled', 'classificationEnabled', 'automaticTrigger'].every((key) => p[key] === false &&
            (!Object.hasOwn(value, key) || value[key] === false)) ||
        !/^[0-9a-f]{64}$/.test(p.manifestSha256 || '') || !localBatchObject(p.moduleBindings) ||
        !['pm2', 'prediction', 'reference'].every((key) => localBatchObject(p.moduleBindings[key])) ||
        (Object.hasOwn(value, 'requiredDeclaration') && value.requiredDeclaration !== declaration) ||
        (Object.hasOwn(value, 'declarationVersion') && value.declarationVersion !== declaration))
        return `${published ? unified ? '十四条' : '十二条' : extended ? unified ? '十一条' : '九条' : unified ? '八条' : bp7 && pm4 ? '七条' : bp7 || pm4 ? '六条' : '五条'}本地证据模式的执行范围、版本或材料绑定不完整；请重新读取，当前不能提交。`;
    return null;
}

export function localInterpretationContextText(context) {
    if (!context) return null; // Historical records have no separate context assessment.
    const labels = { AUTOSOMAL_DOMINANT: '常染色体显性', AUTOSOMAL_RECESSIVE: '常染色体隐性',
        X_LINKED_DOMINANT: 'X 连锁显性', X_LINKED_RECESSIVE: 'X 连锁隐性' };
    if (context.policyId !== 'LOCAL_ALLELE_INTERPRETATION_CONTEXT' || !['1.0.0', '1.1.0'].includes(context.policyVersion) ||
        context.diagnosisClaimed !== false) return '解释背景身份不完整，请核对原始记录。';
    return `排序背景：${labels[context.rankingMoi] || context.rankingMoi || '未明确'}；本次解释背景：${labels[context.interpretationMoi] || context.interpretationMoi || '未明确'}。` +
        (context.genotypeCheck === 'X_LINKED_CATALOG_SCOPE_EXPLICIT_CONTEXT_NOT_DIAGNOSIS'
            ? '来源资料只注明 X 连锁；本次沿用排序中的具体背景，需主动选择并确认，不代表来源已区分显隐性或患者诊断。'
            : context.requiresExplicitSelection ? '两者不同，需主动选择并确认；仅评价该等位基因，不证明患者符合该病遗传模式或已经患病。' : '两者一致；背景确认不等于患者诊断。') +
        (context.reasonCode ? ` 当前限制：${context.reasonCode}` : '');
}

export function localBatchSavedRun(run) {
    return localBatchScopeClaim(run) && !localBatchScopeIssue(run) &&
        run.declarationVersion === batchDeclarationFor(run) &&
        ['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status);
}

export function localBatchRetryEligible(run, analysisId, currentOptions) {
    if (['FAILED','CANCELLED','INTERRUPTED'].includes(run?.status) && run.resultsAvailable === false && currentOptions && localBatchScopeClaim(currentOptions))
        return run.analysisId === analysisId && localBatchText(run.runId) &&
            JSON.stringify(run.retryExecutionProfile) === JSON.stringify(currentOptions.executionProfile);
    return localBatchScopeClaim(run) && !localBatchScopeIssue(run) && run.analysisId === analysisId &&
        (!currentOptions || JSON.stringify(run.executionProfile) === JSON.stringify(currentOptions.executionProfile)) &&
        localBatchText(run.runId) && ['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status);
}

export function localBatchCriteriaText(value) {
    return localBatchScopeIssue(value) ? '本地证据' : batchCriteriaFor(value).map((id) => id.split(':').pop()).join(' / ');
}

export function localBatchAttemptIssue(candidate, attempt, criteria = LOCAL_BATCH_CRITERIA, profile, selectedCriterionId, expected) {
    if (profile?.criterionSelection) {
        const scope = { executionProfile: profile, enabledCriteria: criteria };
        if (selectionScopeIssue(scope)) return '保存的证据选择范围与配置绑定不一致。';
        const local = criteria.filter(id => !['ACMG_AMP_2015:BA1','ACMG_AMP_2015:BS1'].includes(id));
        const rows = attempt?.localBatch?.criteria;
        const issue = genericLocalAttemptIssue(candidate, attempt, local, profile, selectedCriterionId, true, expected);
        if (issue) return issue;
        for (const row of rows) {
            if (selectedCriterionId && row.criterionId !== selectedCriterionId) continue;
            if (row.criterionId === PP1_LITERATURE_ID && !pp1LiteratureCriterionValid(row, profile, attempt.context)) return '保存的 PP1 资料与结论不一致。';
            if (row.criterionId === PM3_LITERATURE_ID && !pm3LiteratureCriterionValid(row, profile, attempt.context)) return '保存的 PM3 资料与结论不一致。';
        }
        if ((!selectedCriterionId || DENOVO_CRITERIA.includes(selectedCriterionId)) && local.some(id=>DENOVO_CRITERIA.includes(id)) &&
            !deNovoPairValid(rows, profile, local.filter(id=>DENOVO_CRITERIA.includes(id)), attempt.context)) return '保存的新生证据事实与所选结论不一致。';
        return null;
    }
    if (pp1LiteratureScope({ executionProfile: profile })) {
        const rows = attempt?.localBatch?.criteria, expectedCriteria = batchCriteriaFor({ executionProfile: profile });
        const pp1 = Array.isArray(rows) ? rows.filter(r => r?.criterionId === PP1_LITERATURE_ID) : [];
        if (!pp1LiteratureBindingValid(profile) || JSON.stringify(criteria) !== JSON.stringify(expectedCriteria) || pp1.length !== 1 ||
            JSON.stringify(rows.map(r => r?.criterionId)) !== JSON.stringify(expectedCriteria.filter(id => !['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1'].includes(id))) ||
            (!selectedCriterionId || selectedCriterionId === PP1_LITERATURE_ID) && !pp1LiteratureCriterionValid(pp1[0], profile, attempt.context)) return '保存的 PP1 文献结论、分值或来源不一致；完整公开结果仍可导出。';
        const base = pp1LiteratureBase({ executionProfile: profile, enabledCriteria: criteria });
        return localBatchAttemptIssue(candidate, { ...attempt, localBatch: { ...attempt.localBatch, criteria: rows.filter(r => r.criterionId !== PP1_LITERATURE_ID) } }, base.enabledCriteria, base.executionProfile, selectedCriterionId, expected);
    }
    if (pm3LiteratureScope({ executionProfile: profile })) {
        const rows = attempt?.localBatch?.criteria, expectedCriteria = batchCriteriaFor({ executionProfile: profile });
        const pm3 = Array.isArray(rows) ? rows.filter(r => r?.criterionId === PM3_LITERATURE_ID) : [];
        if (!pm3LiteratureBindingValid(profile) || JSON.stringify(criteria) !== JSON.stringify(expectedCriteria) || pm3.length !== 1 ||
            JSON.stringify(rows.map(r => r?.criterionId)) !== JSON.stringify(expectedCriteria.filter(id => !['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1'].includes(id))) ||
            (!selectedCriterionId || selectedCriterionId === PM3_LITERATURE_ID) && (!pm3LiteratureCriterionValid(pm3[0], profile, attempt.context) || !validApplicabilityRouting(pm3[0]))) return '保存的 PM3 文献结论、分值或来源不一致；完整公开结果仍可导出。';
        const base = pm3LiteratureBase({ executionProfile: profile, enabledCriteria: criteria });
        return localBatchAttemptIssue(candidate, { ...attempt, localBatch: { ...attempt.localBatch, criteria: rows.filter(r => r.criterionId !== PM3_LITERATURE_ID) } }, base.enabledCriteria, base.executionProfile, selectedCriterionId, expected);
    }
    if (deNovoLocalScope({ executionProfile: profile })) {
        const rows = attempt?.localBatch?.criteria;
        if (!deNovoBindingValid(profile) || JSON.stringify(criteria) !== JSON.stringify(batchCriteriaFor({ executionProfile: profile })) ||
            !Array.isArray(rows) || JSON.stringify(rows.map(r => r?.criterionId)) !== JSON.stringify(criteria.filter(id => !['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1'].includes(id))) ||
            (!selectedCriterionId || DENOVO_CRITERIA.includes(selectedCriterionId)) && !deNovoPairValid(rows, profile, DENOVO_CRITERIA, attempt.context))
            return '保存的 PS2/PM6 共同结果身份、积分或来源说明不一致；不展示科学结论，完整公开结果仍可导出。';
        const base = deNovoBase({ executionProfile: profile, enabledCriteria: criteria });
        return localBatchAttemptIssue(candidate, { ...attempt, localBatch: { ...attempt.localBatch, criteria: rows.filter(r => !DENOVO_CRITERIA.includes(r.criterionId)) } }, base.enabledCriteria, base.executionProfile, selectedCriterionId, expected);
    }
    if (profile?.profileId === UNIFIED_PROFILE && JSON.stringify(criteria) === JSON.stringify(UNIFIED_CRITERIA)) criteria = BP7_BATCH_CRITERIA;
    if (profile?.profileId === ELEVEN_PROFILE && JSON.stringify(criteria) === JSON.stringify(ELEVEN_CRITERIA)) criteria = EXTENDED_BATCH_CRITERIA;
    if (profile?.profileId === FOURTEEN_PROFILE && JSON.stringify(criteria) === JSON.stringify(FOURTEEN_CRITERIA)) criteria = PUBLISHED_BATCH_CRITERIA;
    return genericLocalAttemptIssue(candidate, attempt, criteria, profile, selectedCriterionId, false, expected);
}
function publishedConditionExpected(candidate, attempt, profile, expected) {
    return { analysisId: expected?.analysisId, executionSourceSha256: expected?.executionSourceSha256,
        enabledCriteria: expected?.enabledCriteria, executionProfile: expected?.executionProfile || profile,
        pp2OtherPhenotypeSourceIdentity: expected?.localBatch?.pp2OtherPhenotypeSourceIdentity,
        candidateId: candidate?.candidateId, hypothesisId: Object.hasOwn(attempt || {}, 'hypothesisId')
            ? attempt.hypothesisId : hypothesisIdOf(attempt), context: attempt?.context };
}
function fullRnaExpected(candidate, attempt, profile, expected) {
    return { ...publishedConditionExpected(candidate, attempt, profile, expected), criteria: attempt?.localBatch?.criteria,
        fullRnaBatchIdentity: expected?.localBatch?.identity, fullRnaObservedSourceIdentity: expected?.localBatch?.fullRnaObservedSourceIdentity, fullRnaBatchSourceSha256: expected?.localBatch?.sourceSnapshotSha256 };
}
function genericLocalAttemptIssue(candidate, attempt, criteria, profile, selectedCriterionId, scoped, expected) {
    const value = attempt?.localBatch;
    const rnaExpected = fullRnaExpected(candidate, attempt, profile, expected);
    const proteinExpected = { mode: 'SELECTED_V3_AND_EVALUATION_V2', candidateId: candidate.candidateId,
        hypothesisId: value?.hypothesisId, context: attempt?.context };
    const contextId = attempt?.context?.contextId;
    const hypothesisId = Object.hasOwn(attempt || {}, 'hypothesisId') ? attempt.hypothesisId : hypothesisIdOf(attempt);
    if (!scoped && ![LOCAL_BATCH_CRITERIA, BP7_BATCH_CRITERIA, LOCAL_BATCH_PM4_CRITERIA, [...BP7_BATCH_CRITERIA, 'ACMG_AMP_2015:PM4'], EXTENDED_BATCH_CRITERIA, PUBLISHED_BATCH_CRITERIA].some((ids) => JSON.stringify(ids) === JSON.stringify(criteria)) ||
        value?.schemaVersion !== 'local-batch1-attempt-v1' || value.candidateId !== candidate.candidateId ||
        !localBatchText(value.candidateId) || !(value.hypothesisId === null || localBatchText(value.hypothesisId)) ||
        (hypothesisId !== undefined && hypothesisId !== value.hypothesisId) ||
        !(value.contextId === null || localBatchText(value.contextId)) ||
        (Object.hasOwn(attempt?.context || {}, 'contextId') && contextId !== value.contextId) ||
        (value.hypothesisId === null && value.contextId !== null) ||
        !Array.isArray(value.criteria) || value.criteria.length !== criteria.length ||
        (value.hypothesisId === null && value.criteria.some((criterion) => criterion?.status !== 'NOT_EVALUATED')) ||
        value.criteria.some((criterion, index) => !criterion || criterion.criterionId !== criteria[index]) ||
        value.criteria.some((criterion) => (!selectedCriterionId || selectedCriterionId === criterion.criterionId) && (
            (!['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(criterion.status) &&
                !(criterion.status === 'NOT_APPLICABLE' && fullRnaCriterionClaim(criterion) &&
                    inspectFullRnaCriterion(criterion, rnaExpected, { identityHash: ps2IdentityHash }).view?.completedExclusion === true) &&
                !(criterion.status === 'NOT_APPLICABLE' && criterion.details?.pm5PtcFacts)) ||
            (criterion.status === 'MET' ? !['SUPPORTING', 'MODERATE', 'STRONG', 'VERY_STRONG'].includes(criterion.appliedStrength)
                : criterion.appliedStrength !== 'NONE') || !localBatchText(criterion.reasonCode) ||
            typeof criterion.reason !== 'string' || !Object.hasOwn(criterion, 'details') ||
            (fullRnaCriterionClaim(criterion) ? !inspectFullRnaCriterion(criterion, rnaExpected, { identityHash: ps2IdentityHash }).valid :
            !sequenceTransportValid(criterion) || !validApplicabilityRouting(criterion) ||
            !publishedPtcCriterionValid(criterion, attempt.context) || !pm2ProjectCriterionValid(criterion) ||
            !bp7CriterionValid(criterion, profile) || !pm4ProjectCriterionValid(criterion, proteinExpected) ||
            !pvs1SelectedCriterionValid(criterion, attempt.context, profile) || !bp3ProjectCriterionValid(criterion, proteinExpected) || !publishedMissenseCriterionValid(criterion, profile, publishedConditionExpected(candidate, attempt, profile, expected))))))
        return '保存的本地证据结果缺项或身份、状态、强度不一致；本条背景不展示科学结论，完整公开结果仍可导出。';
    // All independent profile/row/fact guards above have passed. A source-bound
    // current-material receipt still cannot stand in for an unavailable rule proof.
    if (value.criteria.some(criterion => (!selectedCriterionId || criterion.criterionId === selectedCriterionId) &&
        inspectProteinLengthProof(criterion, proteinExpected, { identityHash: ps2IdentityHash }).view?.availabilityOnly))
        return PROTEIN_LENGTH_PROOF_UNAVAILABLE;
    if (value.criteria.some(criterion => (!selectedCriterionId || criterion.criterionId === selectedCriterionId) &&
        inspectPvs1CodingEscapeAssessment(criterion, attempt.context, { identityHash: ps2IdentityHash, profile }).view?.availabilityOnly))
        return PVS1_CODING_PROOF_UNAVAILABLE;
    return null;
}

function sequenceTransportValid(criterion) {
    const d = criterion?.details;
    if (!d || typeof d !== 'object') return true;
    const summaries = [d.codingEditDerivation, d.sequenceDerivation, d.resolvedInput?.codingEditDerivation,
        ...(isPvs1SpliceSummary(d.pvs1Facts) ? [] : [d.pvs1Facts?.codingEditDerivation]),
        d.proteinLengthFacts?.codingEditDerivation, d.bp3Facts?.codingEditDerivation];
    if (summaries.some(value => value !== undefined && !sequenceEditSummaryValid(value))) return false;
    if (['MET', 'NOT_MET'].includes(criterion.status) && !sequenceSourceExecutionValid(d)) return false;
    return true;
}

function sequenceFailureDetails(criterion) {
    const d = criterion?.details;
    return d && !sequenceSourceExecutionValid(d) ? sourceExecutionDetails(d) : [];
}

function unsupportedSavedMethod(criterion, profile, rows, expected, context) {
    if (['ACMG_AMP_2015:PM4', 'ACMG_AMP_2015:BP3'].includes(criterion?.criterionId) && ['MET', 'NOT_MET'].includes(criterion.status) &&
        ['PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE', 'PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE']
            .includes((criterion.details?.proteinLengthFacts || criterion.details?.bp3Facts)?.scope) &&
        !(criterion.details?.proteinLengthFacts || criterion.details?.bp3Facts)?.adoptionProof)
        return '保存的完整序列小等位基因分支结论保留；公开摘要尚未提供当前序列材料与完整比较的核验回执，当前页面不能独立核对该方法的采用证明，不将其改判为病例资料不足。';
    const e = criterion?.details?.pp1Facts?.evaluation;
    if (e && (!['pp1-segregation-evaluation-v1', 'pp1-segregation-evaluation-v2'].includes(e.schemaVersion) ||
        e.schemaVersion === 'pp1-segregation-evaluation-v2' && e.method !== 'VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1'))
        return `保存的 PP1 方法尚未被当前页面实现（${e.method || e.schemaVersion}）；原始结论与出处保留，当前不采用其分值。`;
    const pvs1 = criterion?.details?.pvs1Facts;
    if (isPvs1SpliceSummary(pvs1) && !isPvs1CodingEscapeAssessment(pvs1)) {
        const issues = inspectPvs1SpliceCriterion(criterion, context, { identityHash: ps2IdentityHash, profile }).issues;
        if (issues.includes('PVS1_ESCAPE_PUBLIC_REFERENCE_EVENT_BOUNDARY_REQUIRED'))
            return '保存的 PVS1 原始结论保留；公开摘要尚未完整给出标准化与 HGVS 编码事件边界，当前无法核对参考事件的位置条件，不将其改判为病例资料不足或未满足。';
        if (issues.includes('PVS1_ESCAPE_PUBLIC_PROOF_UNAVAILABLE'))
            return '保存的 PVS1 原始结论与强度保留；本次公开采用证明投影不可用，尚不能完成展示核验。这不表示病例资料不足或科学结论未满足。';
        if (issues.includes('PVS1_SPLICE_ESCAPE_PUBLIC_RULE_PROOF_REQUIRED'))
            return '保存的 PVS1 NMD逃逸结果已保留；公共摘要尚未提供所选逃逸规则及采用强度的对应依据，当前页面不能独立核验，不将其改判为病例资料不足。';
        if (typeof pvs1.method === 'string' && !['SOURCE_BOUND_SPLICE_CONSEQUENCE', 'SOURCE_BOUND_CODING_EDITS'].includes(pvs1.method) ||
            pvs1.methodVersion && !compatibleRuntimeVersion(pvs1.methodVersion, 1))
            return `保存的 PVS1 方法尚未由当前页面实现（${pvs1.method || pvs1.methodVersion}）；原始结果保留，当前不采用其强度。`;
    }
    const d = criterion?.details?.deNovoFacts;
    if (isDeNovoMethodsSummary(d)) {
        const issues = inspectDeNovoMethodsPair(rows, profile, expected, { validCoreMaterial, context, identityHash: ps2IdentityHash }).issues;
        if (issues.some(code => ['DENOVO_ADOPTION_PUBLIC_PROOF_INCOMPLETE', 'DENOVO_ADOPTION_PUBLIC_PROOF_UNAVAILABLE'].includes(code)))
            return '保存的新发证据原始状态、强度及积分保留；本次公开采用证明投影不完整或不可用，不能完成展示核验，不将其解释为病例资料不足或科学结论未满足。';
        if (issues.includes('DENOVO_FIXED_STRENGTH_PUBLIC_PROOF_REQUIRED'))
            return '保存的固定强度与零积分已保留；公共摘要尚缺专规模式、条件及最终强度的来源对应，当前页面不能独立核验采用，不表示病例资料不足。';
        if (issues.includes('DENOVO_COMBINED_PS2_PUBLIC_POLICY_PROOF_REQUIRED'))
            return '保存的组合 PS2 原值已保留；公共摘要尚缺已采用的组合规则及来源对应，当前页面暂不能独立核验采用，不改判为未满足。';
        if (d.detailVersion !== '1.2.0' || d.calculationMethod !== undefined && !['SVI_POINTS', 'EXPERT_FIXED_STRENGTH'].includes(d.calculationMethod))
            return `保存的新发结果使用当前页面尚未实现的方法（${d.calculationMethod || d.detailVersion}）；原始结论与出处保留，不将展示限制解释为病例资料不足。`;
    }
    return null;
}

// Only the normal selected-entry decoder can mark a live display projection.
// JSON properties, clones and later edits cannot manufacture this provenance.
const pp1ContributionProjections = new WeakMap();
const pp1ProjectionFields = (value, keys) => Object.fromEntries(keys.map(key => [key, value?.[key]]));
const pp1ProjectionAllele = value => pp1ProjectionFields(value, ['assembly', 'contig', 'position', 'reference', 'alternate']);
const pp1ProjectionIdentity = value => JSON.stringify({ criterionId: value?.criterionId, strength: value?.appliedStrength,
    result: pp1ProjectionFields(value?.evaluationResult, ['status', 'reasonCode']), contextId: value?.contextId,
    applicability: value?.criterionApplicability?.status });
function pp1ProjectionSourceBinding(attempt, id) {
    const context = attempt.context, row = attempt.localBatch?.criteria?.find(value => value.criterionId === id);
    const facts = row?.details?.pp1Facts, evaluation = facts?.evaluation, ledger = evaluation?.ledger;
    const preparation = facts?.contributionPreparation;
    return JSON.stringify({ candidateId: attempt.localBatch?.candidateId, hypothesisId: attempt.localBatch?.hypothesisId,
        contextId: attempt.localBatch?.contextId,
        selectedContext: { contextId: context?.contextId, gene: pp1ProjectionFields(context?.gene, ['hgncId', 'symbol']),
            diseaseId: context?.disease?.mondoId, moi: context?.moi, allele: pp1ProjectionAllele(context?.variant?.evidenceQueryAllele),
            transcript: pp1ProjectionFields(context?.transcript, ['accession', 'version']) },
        criterionId: row?.criterionId, status: row?.status, strength: row?.appliedStrength, reason: row?.reasonCode,
        sourceSnapshotSha256: facts?.sourceSnapshotSha256, method: evaluation?.method, methodStatus: evaluation?.status,
        target: { ...pp1ProjectionFields(evaluation?.target, ['selectionKey', 'geneSymbol', 'diseaseId', 'inheritance']),
            allele: pp1ProjectionAllele(evaluation?.target?.allele) },
        ledger: { status: ledger?.status, reason: ledger?.reasonCode, pp4Points: ledger?.pp4Points,
            selectionKey: ledger?.selectionKey, scope: pp1ProjectionFields(ledger?.scope, ['geneSymbol', 'diseaseId', 'inheritance']),
            allele: pp1ProjectionAllele(ledger?.allele), provenanceSha256: ledger?.provenanceSha256,
            snapshotSha256: ledger?.contributionSnapshotSha256, sourceIdentitySha256: ledger?.contributionSourceIdentitySha256 },
        preparation: { capability: preparation?.capability, status: preparation?.status, ledgerStatus: preparation?.ledgerStatus,
            reason: preparation?.reasonCode, snapshotSha256: preparation?.snapshotSha256,
            sourceIdentitySha256: preparation?.sourceIdentitySha256, nextStep: preparation?.nextStep } });
}
function pp1ContributionProjection(evaluation) {
    const marker = localBatchObject(evaluation) ? pp1ContributionProjections.get(evaluation) : null;
    if (!marker) return null;
    try {
        return pp1ProjectionIdentity(evaluation) === marker.projection && marker.currentBinding() === marker.binding ? marker : null;
    } catch { return null; }
}

function validPublishedPp4Exclusion(preparation, evaluation, pin) {
    const { ledger, context } = evaluation;
    const proof = ledger.publishedPp4Exclusion;
    const copies = [preparation.publishedPp4Exclusion, context?.policy?.pp4Exclusion];
    return preparation.reasonCode === 'PP1_CURRENT_CONTRIBUTION_PUBLISHED_PP4_NOT_APPLICABLE' &&
        ledger.status === 'KNOWN_EMPTY' && context?.status === 'QUALIFIED' &&
        context.qualificationBasis === 'ORIGINAL_PUBLISHED_EXPERT_SPECIFICATION' &&
        proof?.schemaVersion === 'pp1-published-pp4-exclusion-v1' && proof.criterion === 'ACMG_AMP_2015:PP4' &&
        proof.status === 'NOT_APPLICABLE' && /^\/ld\/CriteriaCode\/\d+$/.test(proof.clausePointer) &&
        pin(proof.clauseSha256) && pin(context.sourceEvidence?.specification?.rawSha256) &&
        localBatchText(proof.specificationKey) && localBatchText(context.policy?.ruleClause) &&
        context.policy.ruleClause.startsWith(`${proof.specificationKey}/ld/CriteriaCode/`) &&
        ['geneSymbol', 'diseaseId', 'inheritance'].every(key => localBatchText(proof.scope?.[key]) && proof.scope[key] === ledger.scope?.[key]) &&
        copies.every(copy => ['schemaVersion', 'criterion', 'status', 'specificationKey', 'clausePointer', 'clauseSha256']
            .every(key => copy?.[key] === proof[key]) && ['geneSymbol', 'diseaseId', 'inheritance'].every(key => copy.scope?.[key] === proof.scope[key]));
}

function validClosedWorkbenchPp4Scope(preparation, ledger, pin) {
    const proof = ledger.workbenchScope;
    if (preparation.reasonCode !== 'PP1_CURRENT_CONTRIBUTION_PP4_NOT_REGISTERED' || ledger.status !== 'KNOWN_EMPTY' ||
        proof?.schemaVersion !== 'pp1-closed-workbench-pp4-scope-v1' || proof.reasonCode !== preparation.reasonCode ||
        !['analysisId', 'runId'].every(key => localBatchText(proof[key])) ||
        !['proofSha256', 'requestSha256', 'executionSourceSha256', 'consumerExecutionSha256'].every(key => pin(proof[key])) ||
        !Array.isArray(proof.configuredCriteria) || !proof.configuredCriteria.includes(PP1_LITERATURE_ID) ||
        proof.configuredCriteria.includes('ACMG_AMP_2015:PP4')) return false;
    try {
        const { proofSha256, ...identity } = proof;
        return ps2IdentityHash(identity) === proofSha256 && ps2IdentityHash(preparation.workbenchScope) === ps2IdentityHash(proof);
    } catch { return false; }
}

// Optional current-source display metadata. It never creates a ledger, awards
// points, or adds a requirement to historical records which omit this field.
export function pp1ContributionPreparationView(row) {
    const facts = row?.details?.pp1Facts, preparation = facts?.contributionPreparation;
    if (preparation === undefined) return null;
    const evaluation = facts?.evaluation, ledger = evaluation?.ledger;
    const invalid = { valid: false };
    const pin = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
    if (row.criterionId !== PP1_LITERATURE_ID || !localBatchObject(preparation) ||
        preparation.capability !== 'TRUSTED_CURRENT_CONTRIBUTION_SNAPSHOT_V1' ||
        !['UNKNOWN', 'CAPTURED', 'COMPLETE'].includes(preparation.status) || !['UNKNOWN', 'KNOWN', 'KNOWN_EMPTY'].includes(preparation.ledgerStatus) ||
        !['reasonCode', 'requirement', 'finding', 'impact', 'nextStep'].every(key =>
            typeof preparation[key] === 'string' && preparation[key].trim() && preparation[key].length <= 32768) ||
        typeof preparation.gap !== 'string' || preparation.gap.length > 32768 ||
        (preparation.status === 'COMPLETE' ? preparation.gap !== '' : !preparation.gap.trim()) ||
        evaluation?.status !== row.status || ledger?.status !== preparation.ledgerStatus || ledger.reasonCode !== preparation.reasonCode ||
        !/^PP1_(?:CURRENT_CONTRIBUTION_|CONTRIBUTION_|VERIFIED_CURRENT_CONTRIBUTION_)[A-Z0-9_]+$/.test(preparation.reasonCode)) return invalid;
    if (preparation.status === 'CAPTURED' && (!pin(preparation.snapshotSha256) || !pin(preparation.sourceIdentitySha256))) return invalid;
    if (['snapshotSha256', 'sourceIdentitySha256'].some(key => Object.hasOwn(preparation, key) && !pin(preparation[key])) ||
        !pin(evaluation.target?.selectionKey) || ledger.selectionKey !== evaluation.target.selectionKey ||
        ['geneSymbol', 'diseaseId', 'inheritance'].some(key => ledger.scope?.[key] !== evaluation.target?.[key]) ||
        ['assembly', 'contig', 'position', 'reference', 'alternate'].some(key => ledger.allele?.[key] !== evaluation.target?.allele?.[key])) return invalid;
    if (preparation.status === 'UNKNOWN' && preparation.ledgerStatus !== 'UNKNOWN') return invalid;
    if (preparation.ledgerStatus === 'KNOWN_EMPTY') {
        const zero = ledger.pp4Points === 0 || typeof ledger.pp4Points === 'string' && /^0(?:\.0+)?$/.test(ledger.pp4Points);
        if (!zero || !Array.isArray(ledger.eventIds) || ledger.eventIds.length !== 0 ||
            !Array.isArray(ledger.exclusiveFactIds) || ledger.exclusiveFactIds.length !== 0) return invalid;
    }
    if (preparation.status === 'COMPLETE') {
        if (!validPublishedPp4Exclusion(preparation, evaluation, pin) &&
            !validClosedWorkbenchPp4Scope(preparation, ledger, pin)) return invalid;
    } else if (preparation.ledgerStatus === 'UNKNOWN') {
        if (row.status !== 'NOT_EVALUATED' || row.appliedStrength !== 'NONE' || ledger.pp4Points !== null ||
            preparation.reasonCode === 'PP1_VERIFIED_CURRENT_CONTRIBUTION_INVENTORY') return invalid;
    } else if (preparation.status !== 'CAPTURED' || preparation.reasonCode !== 'PP1_VERIFIED_CURRENT_CONTRIBUTION_INVENTORY' ||
        preparation.snapshotSha256 !== ledger.contributionSnapshotSha256 ||
        preparation.sourceIdentitySha256 !== ledger.contributionSourceIdentitySha256) return invalid;
    return { valid: true, systemGap: preparation.ledgerStatus === 'UNKNOWN', preparation, pp4Points: ledger.pp4Points };
}

export function pp1LiteratureCriterionValid(row, profile, selectedContext) {
    if (!row || row.criterionId !== PP1_LITERATURE_ID || !['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(row.status) ||
        !localBatchText(row.reasonCode) || typeof row.reason !== 'string') return false;
    const f = row.details?.pp1Facts, e = f?.evaluation;
    if (f && (f.schemaVersion !== 'pp1-selected-facts-v1' || f.clinicalApproval !== false)) return false;
    if (Object.hasOwn(f || {}, 'contributionPreparation') && !pp1ContributionPreparationView(row)?.valid) return false;
    if (!e) return row.status === 'NOT_EVALUATED' && row.appliedStrength === 'NONE';
    const formal = isPp1FormalEvaluation(e);
    const publishedMeiosis = e.context?.policy?.method === 'PUBLISHED_INFORMATIVE_MEIOSES_COUNT';
    if (formal || publishedMeiosis || f.contributionPreparation?.status === 'COMPLETE') {
        // Bind the new internal method to the outer selected background when
        // available, in addition to its own target/ledger/original checks.
        if (selectedContext) {
            const moi = { AUTOSOMAL_DOMINANT: 'AD', AUTOSOMAL_RECESSIVE: 'AR', X_LINKED_RECESSIVE: 'XLR' }[selectedContext.moi];
            const allele = selectedContext.variant?.evidenceQueryAllele;
            if (e.target?.geneSymbol !== selectedContext.gene?.symbol || e.target?.diseaseId !== selectedContext.disease?.mondoId ||
                e.target?.inheritance !== moi || !allele || ['assembly', 'contig', 'position', 'reference', 'alternate']
                    .some(key => e.target?.allele?.[key] !== allele[key])) return false;
        }
    }
    if (formal) return validatePp1FormalCriterion(row, profile);
    if (e.schemaVersion !== 'pp1-segregation-evaluation-v1' || e.status !== row.status || e.businessPublishable !== false ||
        !Array.isArray(e.events) || !Array.isArray(e.needs) || !Array.isArray(f.sourceCoverage) || !Array.isArray(f.qualifications)) return false;
    if (row.status === 'NOT_EVALUATED') return row.appliedStrength === 'NONE' && e.awardedPP1Points === 0 && e.strength === null;
    const decimal = v => typeof v === 'string' && /^(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(v) && Number.isFinite(Number(v));
    if (!['rawSegregationPoints', 'remainingLocusCapacity', 'cappedPP1Points'].every(k => decimal(e[k])) ||
        e.events.some(v => !localBatchText(v.eventId) || !Array.isArray(v.sourceRefs) || !v.sourceRefs.length) ||
        new Set(e.events.map(v => v.eventId)).size !== e.events.length ||
        Number(e.remainingLocusCapacity) > 5 || Number(e.cappedPP1Points) !== Math.min(Number(e.rawSegregationPoints), Number(e.remainingLocusCapacity))) return false;
    const raw = publishedMeiosis ? pp1PublishedMeiosisPoints(e) : e.events.reduce((n,v) => n + (decimal(v.rawPoints) ? Number(v.rawPoints) : NaN),0);
    if (!(Math.abs(raw - Number(e.rawSegregationPoints)) <= 1e-9)) return false;
    const n = Math.floor(Number(e.cappedPP1Points)), points = n >= 4 ? 4 : n >= 2 ? 2 : n >= 1 ? 1 : 0;
    const strength = ({ 4: 'STRONG', 2: 'MODERATE', 1: 'SUPPORTING', 0: null })[points];
    return e.awardedPP1Points === points && e.strength === strength && (row.status === 'MET') === (points > 0) &&
        row.appliedStrength === (strength || 'NONE');
}

function pp1PublishedMeiosisPoints(evaluation) {
    const { meiosisAssessment: result, events, context } = evaluation;
    if (!result || context.qualificationBasis !== 'ORIGINAL_PUBLISHED_EXPERT_SPECIFICATION' || evaluation.target?.inheritance !== 'AD') return NaN;
    const families = new Set(events.map(event => event.familyKey));
    if (result.informativeMeioses !== events.length || result.qualifiedFamilyCount !== families.size ||
        events.some(event => event.informativeMeioses !== 1 || !localBatchText(event.familyKey) || !localBatchText(event.memberKey) ||
            event.ruleClause !== context.policy.ruleClause || !Array.isArray(event.parentKeys) || event.parentKeys.length !== 1 ||
            !localBatchText(event.parentKeys[0]) || event.parentKeys[0] === event.memberKey) ||
        new Set(events.map(event => JSON.stringify([event.familyKey,event.memberKey,event.parentKeys]))).size !== events.length) return NaN;
    const pairs = result.familyIndependenceEvidence;
    if (!Array.isArray(pairs) || pairs.length !== families.size * (families.size - 1) / 2 ||
        pairs.some(row => row?.status !== 'INDEPENDENT' || !Array.isArray(row.familyKeys) || row.familyKeys.length !== 2 || row.familyKeys[0] === row.familyKeys[1] ||
            !row.familyKeys.every(key => families.has(key)) || !Array.isArray(row.sourceRefs) || !row.sourceRefs.length) ||
        new Set(pairs.map(row => JSON.stringify([...row.familyKeys].sort()))).size !== pairs.length) return NaN;
    const strengths = [['STRONG',4],['MODERATE',2],['SUPPORTING',1]];
    for (const [strength] of strengths) {
        const rule = context.policy.thresholds?.[strength], saved = result.thresholds?.[strength];
        if (!rule || !saved || !['minimum','maximum','minimumIndependentFamilies','sourceText'].every(key => rule[key] === saved[key]) ||
            !Number.isSafeInteger(rule.minimum) || rule.minimum < 1 ||
            (rule.maximum !== null && (!Number.isSafeInteger(rule.maximum) || rule.maximum < rule.minimum)) ||
            !Number.isSafeInteger(rule.minimumIndependentFamilies) || rule.minimumIndependentFamilies < 1 || !localBatchText(rule.sourceText)) return NaN;
    }
    const match = strengths.find(([strength]) => {
        const rule = context.policy.thresholds[strength];
        return result.informativeMeioses >= rule.minimum && (rule.maximum === null || result.informativeMeioses <= rule.maximum) &&
            families.size >= rule.minimumIndependentFamilies;
    });
    return result.countStrength === (match?.[0] ?? null) ? (match?.[1] ?? 0) : NaN;
}

export function pm3LiteratureCriterionValid(row, profile, context) {
    if (!row || !['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(row.status) || !localBatchText(row.reasonCode) || typeof row.reason !== 'string') return false;
    if(pm3V4BindingClaim(profile?.moduleBindings?.pm3)&&!pm3V4BindingValid(profile.moduleBindings.pm3))return false;
    const f = row.details?.pm3Facts;
    if (!f) return row.status === 'NOT_EVALUATED' && row.appliedStrength === 'NONE';
    if (f.schemaVersion !== 'pm3-selected-facts-v1' || f.clinicalApproval !== false || f.scope !== 'SOURCE_CHECKED_LOCAL_RESEARCH' ||
        !Array.isArray(f.excludedObservations) || !Array.isArray(f.excludedTasks)) return false;
    const c = f.assessment?.calculation;
    if (!c) return row.status === 'NOT_EVALUATED' && row.appliedStrength === 'NONE';
    if (!Number.isSafeInteger(c.quarterPoints) || c.quarterPoints < 0 || !Array.isArray(c.contributions) ||
        c.contributions.some(v => !Number.isSafeInteger(v.awardedQuarterPoints) || v.awardedQuarterPoints < 0 ||
            !Number.isSafeInteger(v.proposedQuarterPoints) || v.awardedQuarterPoints > v.proposedQuarterPoints || !Array.isArray(v.sourceReferences) || !v.sourceReferences.length) ||
        c.contributions.reduce((total, v) => total + v.awardedQuarterPoints, 0) !== c.quarterPoints) return false;
    const strength = c.quarterPoints >= 16 ? 'VERY_STRONG' : c.quarterPoints >= 8 ? 'STRONG' : c.quarterPoints >= 4 ? 'MODERATE' : c.quarterPoints >= 2 ? 'SUPPORTING' : 'NONE';
    if (c.strength !== strength || (c.status === 'MET') !== (strength !== 'NONE') ||
        row.status !== (c.status === 'NOT_APPLICABLE' ? 'NOT_EVALUATED' : c.status) || row.appliedStrength !== (row.status === 'MET' ? strength : 'NONE')) return false;
    if(pm3V4BindingClaim(profile?.moduleBindings?.pm3))return pm3V4FactsValid(row,profile,context,ps2IdentityHash);
    return Array.isArray(f.policySelections) && f.policySelections.length > 0 && f.policySelections.every(p =>
        p.schemaVersion === 'pm3-specification-context-v1' && p.status === 'SELECTED' && p.policy === 'CLINGEN_SVI_PM3_2019_V1');
}

function deNovoPairValid(rows, profile, expected = DENOVO_CRITERIA, context) {
    if (isDeNovoMethodsBinding(profile?.moduleBindings?.deNovo) || rows.some(row => isDeNovoMethodsSummary(row?.details?.deNovoFacts)))
        return validateDeNovoMethodsPair(rows, profile, expected, { validCoreMaterial, context, identityHash: ps2IdentityHash });
    if (rows.some(r => !DENOVO_CRITERIA.includes(r.criterionId) && Object.hasOwn(r.details || {}, 'deNovoFacts'))) return false;
    const pair = rows.filter(r => DENOVO_CRITERIA.includes(r.criterionId));
    if (pair.length !== expected.length || pair.some(r=>!expected.includes(r.criterionId)) || pair.some(r => !['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(r.status) ||
        !localBatchText(r.reasonCode) || typeof r.reason !== 'string' || !Object.hasOwn(r, 'details'))) return false;
    const f = pair[0].details?.deNovoFacts;
    if (!f) return pair.every(r => r.status === 'NOT_EVALUATED' && r.appliedStrength === 'NONE' &&
        !r.details?.deNovoFacts && !r.reasonCode.startsWith('DENOVO_'));
    if (pair.some(r => JSON.stringify(f) !== JSON.stringify(r.details?.deNovoFacts)) || f.schemaVersion !== 'denovo-selected-facts-v1' ||
        !['CURRENT_CASE_LOCAL_REPORTS', 'CURRENT_CASE_AND_LITERATURE'].includes(f.sourceScope) ||
        !deNovoModelStatusValid(f.modelValidation, profile.moduleBindings.deNovo) || f.materialUse !== profile.moduleBindings.deNovo.use ||
        f.literatureQualification !== profile.moduleBindings.deNovo.literatureQualification ||
        !['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(f.status)) return false;
    const integer = n => Number.isSafeInteger(n) && n >= 0 && n <= 8000;
    if (Object.hasOwn(f, 'coreMaterials') && (!Array.isArray(f.coreMaterials) || f.coreMaterials.length > 256 || f.coreMaterials.some(m => !validCoreMaterial(m)))) return false;
    const scored = Object.hasOwn(f, 'selectedCriterion');
    if (scored && (!['NONE', 'PS2', 'PM6'].includes(f.selectedCriterion) || !integer(f.rawQuarterPoints) ||
        !integer(f.effectiveQuarterPoints) || f.effectiveQuarterPoints > f.rawQuarterPoints ||
        !Number.isInteger(f.eventCount) || f.eventCount < 0 || f.eventCount > 1000 || typeof f.hasExcludedObservations !== 'boolean')) return false;
    if (!scored && (f.status !== 'NOT_EVALUATED' || !localBatchText(f.reasonCode))) return false;
    const met = f.status === 'MET', n = f.effectiveQuarterPoints;
    const strength = n >= 16 ? 'VERY_STRONG' : n >= 8 ? 'STRONG' : n >= 4 ? 'MODERATE' : n >= 2 ? 'SUPPORTING' : 'NONE';
    if (scored && (met ? strength === 'NONE' || f.selectedCriterion === 'NONE' : f.selectedCriterion !== 'NONE' || n >= 2)) return false;
    if (!pair.every(r => {
        const chosen = r.criterionId === `ACMG_AMP_2015:${f.selectedCriterion}`;
        return r.status === (met && !chosen ? 'NOT_EVALUATED' : f.status) && r.appliedStrength === (met && chosen ? strength : 'NONE') &&
            (met ? r.reasonCode === (chosen ? 'DENOVO_QUALIFIED_POINTS' : `DENOVO_SHARED_EVENT_ASSIGNED_TO_${f.selectedCriterion}`)
                : f.reasonCode ? r.reasonCode === f.reasonCode : true);
    })) return false;
    const detailed = ['denovo-selected-batch-1.2.0', 'denovo-selected-batch-1.3.0', 'denovo-selected-batch-1.4.0'].includes(profile.moduleBindings.deNovo.version);
    if (!detailed) return !Object.hasOwn(f, 'detailVersion'); // Explicit legacy history, not invented event details.
    const codes = a => Array.isArray(a) && a.every(s => typeof s === 'string' && /^[A-Z][A-Z0-9_]{0,160}$/.test(s));
    if (Object.hasOwn(f, 'sourceSupplyIssues') && (!profile.moduleBindings.deNovo.sourceSupply ||
        !codes(f.sourceSupplyIssues) || f.sourceSupplyIssues.length > 100)) return false;
    if (!['1.0.0', '1.1.0'].includes(f.detailVersion) || f.clinicalApproval !== 'NONE' || f.completeLiteratureSearch !== false ||
        !Array.isArray(f.events) || !codes(f.qualificationIssues) || (scored ? f.events.length !== f.eventCount : f.events.length !== 0)) return false;
    if (Object.hasOwn(f, 'criterionExplanations') && (!Array.isArray(f.criterionExplanations) || f.criterionExplanations.length !== expected.length ||
        new Set(f.criterionExplanations.map(d => d?.criterionId)).size !== expected.length || f.criterionExplanations.some(d =>
            !d || d.schemaVersion !== 'literature-decision-explanation-v1' || d.basis !== 'SAVED_RULE_RESULT_NOT_MODEL_OPINION' ||
            typeof d.summary !== 'string' || !d.summary || d.summary.length > 32768 ||
            !pair.some(r => r.criterionId === d.criterionId && r.status === d.status && r.reasonCode === d.reasonCode && r.appliedStrength === d.appliedStrength)))) return false;
    let raw = 0, confirmed = false;
    for (const [i, e] of f.events.entries()) {
        if (e.ordinal !== i + 1 || !['CURRENT_CASE', 'LITERATURE_CASE'].includes(e.sourceKind) ||
            !['COUNTED', 'ZERO_SUPPORT', 'DUPLICATE', 'EXCLUDED'].includes(e.disposition) ||
            !codes([e.reasonCode]) || !codes(e.issues) || typeof e.confirmedParentage !== 'boolean' ||
            !Array.isArray(e.phenotypeCategories) || e.phenotypeCategories.some(p => !['HIGHLY_SPECIFIC', 'RASOPATHY_COMPATIBLE', 'CONSISTENT', 'CONSISTENT_HIGH_HETEROGENEITY', 'NOT_CONSISTENT', 'UNKNOWN'].includes(p)) ||
            !Array.isArray(e.sourceRefs) || e.sourceRefs.some(r => typeof r !== 'string' || !/^[0-9a-f]{64}#(?:\/[A-Za-z0-9_~/-]*)?$/.test(r)) ||
            (e.disposition === 'COUNTED' ? !(e.confirmedParentage ? [2, 4, 8] : [1, 2, 4]).includes(e.rawQuarterPoints) : e.rawQuarterPoints !== 0)) return false;
        raw += e.rawQuarterPoints; confirmed ||= e.disposition === 'COUNTED' && e.confirmedParentage;
    }
    return !scored || (raw === f.rawQuarterPoints && integer(f.highHeterogeneityRawQuarterPoints) &&
        f.highHeterogeneityRawQuarterPoints <= raw && f.highHeterogeneityEffectiveQuarterPoints === Math.min(f.highHeterogeneityRawQuarterPoints, 4) &&
        f.effectiveQuarterPoints === raw - f.highHeterogeneityRawQuarterPoints + f.highHeterogeneityEffectiveQuarterPoints &&
        (!met || f.selectedCriterion === (confirmed ? 'PS2' : 'PM6')));
}

export function deNovoResultDetails(criterion) {
    const f = criterion?.details?.deNovoFacts;
    if (!DENOVO_CRITERIA.includes(criterion?.criterionId) || !f) return [];
    if (isDeNovoMethodsSummary(f)) return deNovoMethodsResultDetails(criterion);
    const dispositions = { COUNTED: '计入', ZERO_SUPPORT: '已核查但不贡献分数', DUPLICATE: '同一事件，去重', EXCLUDED: '资料未合格，排除' };
    const phenotypes = { HIGHLY_SPECIFIC: '高度特异', RASOPATHY_COMPATIBLE: '符合 RASopathy 表型', CONSISTENT: '一致', CONSISTENT_HIGH_HETEROGENEITY: '一致但遗传异质性高', NOT_CONSISTENT: '不一致', UNKNOWN: '依据不足' };
    return [['共同新生证据', f.selectedCriterion && f.selectedCriterion !== 'NONE' ? `本组采用 ${f.selectedCriterion}；PS2 与 PM6 不重复计分` : '尚无可采用的共同新生证据'],
        ['资料用途', `${f.materialUse === 'RESEARCH' ? '来源核实后的科研资料' : '明确标注的合成验证资料'}；未授予临床批准`],
        ['模型与检索范围', f.modelValidation === 'SYNTHETIC_MODEL_REPLAY_ONLY'
            ? '合成模型响应的工程验证；未调用真实模型，不代表真实提取准确率或完整文献检索'
            : f.modelValidation === 'MODEL_CANDIDATES_SOURCE_REPLAYED_NOT_CLINICALLY_VALIDATED'
                ? '已读取模型候选并重新核对原文；模型不决定证据分值，不代表临床批准或完整文献检索'
                : '未采用真实模型提取结果；大模型未配置或当前原文无可用模型产物；仅核验已接入资料，不代表完整文献检索'],
        ...(Number.isInteger(f.effectiveQuarterPoints) ? [['事件积分', `原始 ${f.rawQuarterPoints / 4} 分；封顶后 ${f.effectiveQuarterPoints / 4} 分`]] : []),
        ...(Number.isInteger(f.highHeterogeneityRawQuarterPoints) ? [['高异质性部分', `${f.highHeterogeneityRawQuarterPoints / 4} 分，封顶后 ${f.highHeterogeneityEffectiveQuarterPoints / 4} 分`]] : []),
        ...(f.events || []).flatMap(e => [[`观察记录 ${e.ordinal}`, `${e.sourceKind === 'CURRENT_CASE' ? '当前病例' : '公开文献病例'}；${dispositions[e.disposition]}；${e.rawQuarterPoints / 4} 分；${e.confirmedParentage ? '亲缘关系已证实' : '亲缘关系未完全证实'}；${e.phenotypeCategories.map(p => phenotypes[p]).join('、') || '未提供表型摘要'}`],
            [`观察 ${e.ordinal} 原因`, [e.reasonCode, ...e.issues].map(c => reasonHelp(c, 'DENOVO')).join('；')],
            [`观察 ${e.ordinal} 来源`, e.sourceRefs.join('；') || '未提供可公开的来源定位']]),
        ...(!f.detailVersion ? [['历史说明', '本记录保存时未包含逐事件摘要；完整原始结果保持，不补造详情']] : []),
        ...(f.qualificationIssues?.length ? [['资料资格缺项', f.qualificationIssues.map(c => reasonHelp(c, 'DENOVO')).join('；')]] : []),
        ...(f.sourceSupplyIssues?.length ? [['公开资料获取', f.sourceSupplyIssues.map(c => reasonHelp(c, 'DENOVO')).join('；')]] : [])];
}

// Validate the saved transport, not the private scientific original or its RAW hash.
function bp7CriterionValid(criterion, profile) {
    if (criterion.criterionId !== 'ACMG_AMP_2015:BP7') return true;
    const b = criterion.details?.bp7;
    if (!b && criterion.details?.applicabilityRouting?.skipSpecializedAcquisition === true)
        return validApplicabilityRouting(criterion);
    if (!b) return criterion.status === 'NOT_EVALUATED' && !criterion.reasonCode.startsWith('BP7_');
    if (b.schemaVersion !== 'bp7-selected-summary-v1' ||
        !['MET', 'NOT_MET', 'NOT_EVALUATED', 'NOT_APPLICABLE'].includes(b.coreStatus) ||
        criterion.status !== (b.coreStatus === 'NOT_APPLICABLE' ? 'NOT_EVALUATED' : b.coreStatus) ||
        typeof b.reviewRequired !== 'boolean' ||
        !['additionalPredictionPerformed', 'experimentalNormalityEstablished', 'externalExperimentsCompletelyCovered', 'clinicalAdoption'].every((k) => b[k] === false) ||
        !['ELIGIBLE', 'EXCLUDED', 'NOT_APPLICABLE', 'NOT_EVALUATED'].includes(b.positionStatus) ||
        !Array.isArray(b.matchedRecordIds) || b.matchedRecordIds.some((id) => !/^[A-Za-z0-9_-]{1,160}$/.test(id)) ||
        (b.policySha256 && (!/^[0-9a-f]{64}$/.test(b.policySha256) ||
            (profile && b.policySha256 !== profile.moduleBindings?.bp7?.runtimeBinding?.policySha256))) ||
        (b.reviewRequired !== (b.experimentCheckStatus === 'REVIEW_REQUIRED' || b.functionalCheckStatus === 'REVIEW_REQUIRED')) ||
        (b.coreStatus === 'MET' && (criterion.appliedStrength !== 'SUPPORTING' || b.reviewRequired ||
            criterion.reasonCode !== 'BP7_LOCAL_COMPUTATIONAL_REQUIREMENTS_MET' || b.positionStatus !== 'ELIGIBLE' ||
            b.experimentCheckStatus !== 'NO_MATCH_IN_DECLARED_CATALOG' || b.functionalCheckStatus !== 'NO_LINKED_SPLICE_RESTRICTION' ||
            b.catalogSourceStatus !== 'AVAILABLE' || b.matchedRecordIds.length !== 0 ||
            b.policyId !== 'BP7_LOCAL_NATIVE_SPLICE' || b.policyVersion !== '1.2.0' || !b.policySha256 ||
            !/^bp7-local-sha256-[0-9a-f]{64}$/.test(b.resultId || '') ||
            !/^selected-prediction-sha256-[0-9a-f]{64}$/.test(b.sharedPredictionResultId || '') ||
            !/^ENST[0-9]+\.[0-9]+$/.test(b.selectedTranscriptId || '') || !localBatchObject(b.geometry)))) return false;
    return true;
}

export function bp7ResultDetails(criterion) {
    const b = criterion?.details?.bp7;
    if (criterion?.criterionId !== 'ACMG_AMP_2015:BP7' || !b) return [];
    const g = b.geometry || {};
    const labels = { ELIGIBLE: '位置符合当前范围', EXCLUDED: '靠近剪接边界，位置排除', NOT_APPLICABLE: '后果不适用', NOT_EVALUATED: '位置未完成核验' };
    const experiments = { NO_MATCH_IN_DECLARED_CATALOG: '本地目录未收录匹配记录；不代表实验正常', REVIEW_REQUIRED: '存在实验冲突或关联待确认，需复核', UNAVAILABLE: '实验资料不可用，不能当作没有冲突' };
    return [['BP7 采用状态', b.reviewRequired ? '需复核；不会自动采用' : b.coreStatus === 'NOT_APPLICABLE' ? '不适用当前计算 BP7' : criterion.reason],
        ['所选转录本', b.selectedTranscriptId || '未取得合格的精确版本'], ['位置核对', labels[b.positionStatus] || b.positionStatus],
        ...(Number.isSafeInteger(g.donorDistance) && Number.isSafeInteger(g.acceptorDistance)
            ? [['内含子距离', `供体侧 ${g.donorDistance}；受体侧 ${g.acceptorDistance} 个碱基（按转录方向）`]] : []),
        ...(Number.isSafeInteger(g.zeroBasedDistanceFromExonStart) && Number.isSafeInteger(g.zeroBasedDistanceFromExonEnd)
            ? [['外显子距离', `距首端 ${g.zeroBasedDistanceFromExonStart}；距末端 ${g.zeroBasedDistanceFromExonEnd} 个碱基（边界本身为0）`]] : []),
        ['共享剪接预测', b.sharedPredictionResultId ? '复用本次同源 BP4 剪接预测，没有额外运行模型；不累加为最终分类。' : '未取得合格共享预测；不推断分数或良性。'],
        ['预测结果引用', b.sharedPredictionResultId || '未形成'],
        ['实验检查', experiments[b.experimentCheckStatus] || b.experimentCheckStatus],
        ['匹配实验记录', b.matchedRecordIds?.length ? b.matchedRecordIds.join('、') : '未匹配；外部实验未穷尽'],
        ['功能材料检查', b.functionalCheckStatus === 'NO_LINKED_SPLICE_RESTRICTION' ? '本次本地审核范围内未发现阻断引用' : b.functionalCheckStatus],
        ['BP7 适用边界', '原生 GRCh38 SNV；精确转录本的同义或限定内含子，最高 Supporting。仅本地研究范围，未收录不等于实验正常，不是临床分类。']];
}

const positiveInteger = (value) => Number.isSafeInteger(value) && value > 0;
const versionedTranscript = (value) => /^(?:ENST[0-9]+|NM_[0-9]+)\.[1-9][0-9]*$/.test(value || '');
const proteinRangeValid = (f) => versionedTranscript(f.transcript) && localBatchText(f.protein) &&
    Number.isSafeInteger(f.netAminoAcidChange) && f.netAminoAcidChange !== 0 &&
    positiveInteger(f.queryRange?.start) && Number.isSafeInteger(f.queryRange?.end) && f.queryRange.end >= f.queryRange.start &&
    f.queryRange.coordinateSystem === 'REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE';

// Public transport consistency only; private source qualification remains on the server.
function publishedMissenseCriterionValid(c, profile, expected) {
    const keys = { PM1: 'pm1Facts', PP2: 'pp2Facts', BP1: 'bp1Facts' };
    const versionedProtein = value => /^(?:ENSP[0-9]+|NP_[0-9]+)\.[1-9][0-9]*$/.test(value || '');
    const code = c.criterionId?.split(':').pop();
    if (Object.entries(keys).some(([name, key]) => name !== code && Object.hasOwn(c.details || {}, key))) return false;
    const key = keys[code];
    if (!key) return true;
    const f = c.details?.[key];
    if (!f) return c.status === 'NOT_EVALUATED';
    const supportedVersion = code === 'BP1' ? ['1.0.0', '1.1.0', '1.2.0'].includes(f.policyVersion)
        : code === 'PP2' ? compatibleRuntimeVersion(f.policyVersion, 1) : f.policyVersion === '1.0.0';
    if (f.schemaVersion !== `${code.toLowerCase()}-general-facts-v1` || !supportedVersion ||
        f.clinicalValidationCompleted !== false) return false;
    if (code === 'PM1') {
        if (f.proteinDeletion) return validPublishedPm1Deletion(c, f, expected);
        const current = inspectPublishedConditionCriterion(c, expected, { identityHash: ps2IdentityHash, profile });
        if (current.claimed) return current.valid;
        if (f.scope !== 'PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS' || f.qualificationBasis !== 'PUBLISHED_EXPERT_SPECIFICATION' ||
            f.benignCountsInferred !== false || f.automaticNegativeFromNoHit !== false) return false;
        if (c.status === 'NOT_EVALUATED') return true;
        const r = f.selectedRule;
        if (!versionedTranscript(f.transcript) || !versionedTranscript(f.declaredTranscript) || f.executionTranscript !== f.transcript ||
            !versionedProtein(f.protein) || !positiveInteger(f.proteinPosition) || !localBatchText(f.specificationKey) ||
            !/^[ACDEFGHIKLMNPQRSTVWY]$/.test(f.refAminoAcid || '') || !/^[ACDEFGHIKLMNPQRSTVWY]$/.test(f.altAminoAcid || '') ||
            f.refAminoAcid === f.altAminoAcid || !r || !positiveInteger(r.start) || !positiveInteger(r.end) ||
            r.start > f.proteinPosition || r.end < f.proteinPosition || !localBatchText(r.ruleId) ||
            !/^[0-9a-f]{64}$/.test(r.sourceSha256 || '')) return false;
        return c.status === 'MET' ? c.reasonCode === 'PM1_PUBLISHED_CRITICAL_LOCATION_MATCH' && r.effect === 'SUPPORT' &&
            ['MODERATE', 'SUPPORTING'].includes(r.strength) && c.appliedStrength === r.strength &&
            ['MUTATIONAL_HOTSPOT', 'CRITICAL_FUNCTIONAL_REGION', 'CRITICAL_RESIDUE'].includes(r.regionType)
            : c.reasonCode === 'PM1_PUBLISHED_EXPLICIT_EXCLUSION' && r.effect === 'EXCLUDE' && r.strength === 'NONE' && r.regionType === 'EXCLUSION';
    }
    if (code === 'PP2') {
        if (profile && f.policyVersion !== profile.moduleBindings?.pp2?.runtimeBinding?.pp2GeneralVersion) return false;
        if (pp2HcmClaimed(f)) return pp2HcmCriterionValid(c,f,profile,expected,ps2IdentityHash);
        if (f.scope !== 'PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS' || f.constraintScoresRecalculated !== false ||
            f.createsReviewReceipt !== false || typeof f.germlineDeclared !== 'boolean') return false;
        if (c.status === 'NOT_EVALUATED') return true;
        if (!f.germlineDeclared || (profile && profile.moduleBindings?.pp2?.runtimeBinding?.pp2GermlineDeclared !== true) ||
            f.knowledgeStatus !== 'READY' || !localBatchText(f.specificationKey) || !versionedTranscript(f.declaredTranscript) ||
            !versionedTranscript(f.executionTranscript)) return false;
        const mechanism = f.conditions?.missenseMechanism, benign = f.conditions?.lowBenignMissense;
        if (f.authoritySpectrum) {
            const proof = f.authoritySpectrum;
            if (c.status !== 'NOT_MET' || c.appliedStrength !== 'NONE' ||
                proof.specialistCheck?.status !== 'NO_MATCHING_SPECIFICATION' ||
                !/^[a-f0-9]{64}$/.test(proof.specialistCheck?.inventoryRawSha256 || '') ||
                !/^[ACDEFGHIKLMNPQRSTVWY][1-9][0-9]*[ACDEFGHIKLMNPQRSTVWY]$/.test(f.proteinVariantKey || '')) return false;
            if (proof.method === 'PP2_ACGS_CONSTRAINT_NEGATIVE_V1') {
                const measure = benign?.measurement;
                const pinned = source => localBatchText(source?.locator) && /^[a-f0-9]{64}$/.test(source?.rawSha256 || '');
                const transcript = /^(ENST[0-9]+)\.[1-9][0-9]*$/.exec(f.declaredTranscript);
                return Boolean(transcript) && f.declaredTranscript === f.executionTranscript &&
                    c.reasonCode === 'PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED' && f.specificationKey === 'ACGS_2024@1.2' &&
                    proof.source?.url === 'https://www.acgs.uk.com/media/12533/_media_12533_uk-practice-guidelines-for-variant-classification-v12-2024.pdf#page=13' &&
                    proof.source.locator === 'Section 5 PP2, pp. 13-14' && pinned(proof.source) &&
                    mechanism?.conclusion === 'SUPPORTED' && mechanism.basisType === 'PUBLISHED_DISEASE_MISSENSE_SPECTRUM' &&
                    localBatchText(mechanism.sourceText) && pinned(mechanism.source) &&
                    /^https:\/\/pmc\.ncbi\.nlm\.nih\.gov\/articles\/PMC[0-9]+\/#[A-Za-z0-9_-]+$/.test(mechanism.source.url || '') &&
                    benign?.conclusion === 'NOT_SUPPORTED' && benign.basisType === 'PUBLISHED_MISSENSE_Z_THRESHOLD' &&
                    pinned(benign.source) && benign.source.rawSha256 !== mechanism.source.rawSha256 &&
                    benign.source.url === `https://gnomad.broadinstitute.org/transcript/${transcript[1]}?dataset=gnomad_r4` &&
                    measure?.transcript === f.executionTranscript && measure.assembly === 'GRCh38' && measure.release === '4.1.2' &&
                    Number.isInteger(measure.observed) && measure.observed >= 0 && Number.isFinite(measure.expected) &&
                    measure.expected > 0 && Number.isFinite(measure.z) && measure.operator === 'GTE' &&
                    measure.threshold === 3.09 && measure.z < measure.threshold;
            }
            const qualified = condition => condition?.basisType === 'PUBLISHED_AUTHORITY_SPECTRUM' &&
                ['SUPPORTED', 'NOT_SUPPORTED'].includes(condition.conclusion) &&
                condition.sourceLocator === 'S19' && localBatchText(condition.sourceText);
            if (!qualified(mechanism) || !qualified(benign)) return false;
            const reason = mechanism.conclusion === 'NOT_SUPPORTED' ? 'PP2_MISSENSE_MECHANISM_NOT_SUPPORTED'
                : benign.conclusion === 'NOT_SUPPORTED' ? 'PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED' : '';
            return Boolean(reason) && c.reasonCode === reason &&
                f.specificationKey === 'ACMG_AMP_2015@2015' &&
                proof.method === 'PP2_PUBLISHED_AUTHORITY_SPECTRUM_V1' &&
                proof.source?.url === 'https://pmc.ncbi.nlm.nih.gov/articles/PMC4544753/#S19' &&
                proof.source?.locator === 'S19' && /^[a-f0-9]{64}$/.test(proof.source?.rawSha256 || '');
        }
        return c.status === 'MET' && c.appliedStrength === 'SUPPORTING' && c.reasonCode === 'PP2_PUBLISHED_MECHANISM_AND_LOW_BENIGN_BACKGROUND' &&
            mechanism?.conclusion === 'SUPPORTED' && mechanism.basisType === 'PUBLISHED_PP2_APPLICATION' &&
            benign?.conclusion === 'SUPPORTED' && benign.basisType === 'PUBLISHED_CONSTRAINT_ASSERTION';
    }
    const current = inspectPublishedConditionCriterion(c, expected, { identityHash: ps2IdentityHash, profile });
    if (current.claimed && (!current.valid || current.handlesDecision)) return current.valid;
    const regional = f.scope === 'PUBLISHED_REGION_AND_SPLICE_CONDITIONS';
    if (f.experimentalNormalityEstablished !== false) return false;
    if (regional) {
        if (f.policyVersion !== '1.2.0' || f.expertBp1SpliceThreshold !== true || f.requiredDistanceBp !== 10000 ||
            f.projectSplicePolicy !== 'PUBLISHED_OUTSIDE_REGION_SPLICE_STRONG_V1') return false;
    } else if (f.scope !== 'PUBLISHED_SCOPE_PLUS_PROJECT_RESEARCH_RESTRICTION' || f.expertBp1SpliceThreshold !== false ||
        f.projectSplicePolicy !== 'PROJECT_RESEARCH_CONSERVATIVE_SPLICE_EXCLUSION_V1') return false;
    if (c.status === 'NOT_EVALUATED') return true;
    if (['1.1.0', '1.2.0'].includes(f.policyVersion)) {
        const screening = f.functionalScreen;
        if (f.knowledgeDisposition !== 'EXECUTABLE' || f.knowledgeStatus !== 'READY' || screening?.status !== 'CHECKED' ||
            !['BP1_LOADED_FUNCTION_SCREENS_CHECKED', 'BP1_NO_MATCH_IN_LOADED_FUNCTION_SCREENS'].includes(screening.reasonCode) ||
            !/^[0-9a-f]{64}$/.test(screening.catalogSha256 || '') || screening.coverage !== 'LOADED_PUBLICATIONS_ONLY' ||
            screening.absenceMeansNormal !== false || screening.clinicalAdoption !== false || !Array.isArray(screening.matches) ||
            screening.matches.some(m => !localBatchText(m.screenId) || !localBatchText(m.sourceId) ||
                !/^https:\/\//.test(m.sourceUrl || '') || !/^[0-9a-f]{64}$/.test(m.sourceRawSha256 || '') || m.proteinVariantKey !== f.proteinVariantKey ||
                !(m.reportedClass === 'FUNCTIONAL' && m.outcome === 'NO_ADVERSE_SIGNAL_IN_THIS_ASSAY_NOT_NORMALITY_PROOF' &&
                    positiveInteger(m.sourceRow) && localBatchText(m.sourceSheet) || m.reportedClass === '' && m.outcome === 'OUTSIDE_REPORTED_SCREEN_RANGES'))) return false;
    }
    const change = /^([ACDEFGHIKLMNPQRSTVWY])([1-9][0-9]*)([ACDEFGHIKLMNPQRSTVWY])$/.exec(f.proteinVariantKey || '');
    if (regional) {
        const r = f.regionConditionCheck;
        if (!r || r.type !== 'PUBLISHED_OUTSIDE_REGION_SPLICE_STRONG_V1' || r.status !== 'READY' ||
            r.reasonCode !== 'BP1_OUTSIDE_PUBLISHED_REGIONS' || r.appliedStrength !== 'STRONG' || r.distanceBp !== 10000 ||
            r.maximumRawDelta !== '0.1' || r.sourceTranscript !== f.sourceTranscript || r.experimentalNormalityEstablished !== false ||
            r.functionalCoverage !== 'LOADED_PUBLICATIONS_ONLY' || !change || r.proteinPosition !== Number(change[2]) ||
            !/^c\.[1-9][0-9]*[ACGT]>[ACGT]$/.test(r.codingChange || '') ||
            !Array.isArray(r.excludedProteinRegions) || !r.excludedProteinRegions.length || !Array.isArray(r.functionalMatches)) return false;
        let previousEnd = 0;
        for (const region of r.excludedProteinRegions) {
            if (!localBatchText(region.label) || !positiveInteger(region.start) || !positiveInteger(region.end) ||
                region.start <= previousEnd || region.start > region.end ||
                (r.proteinPosition >= region.start && r.proteinPosition <= region.end)) return false;
            previousEnd = region.end;
        }
        if (r.functionalMatches.some(m => !positiveInteger(m.sourceRow) || !Array.isArray(m.originalCells) ||
            typeof m.reviewRequired !== 'boolean' || typeof m.spliceReviewRequired !== 'boolean' ||
            (m.proteinVariantKey !== f.proteinVariantKey && m.codingChange !== r.codingChange) ||
            (m.proteinVariantKey === f.proteinVariantKey && m.reviewRequired) ||
            (m.codingChange === r.codingChange && m.spliceReviewRequired))) return false;
    }
    return c.status === 'MET' && c.appliedStrength === (regional ? 'STRONG' : 'SUPPORTING') &&
        c.reasonCode === (regional ? 'BP1_PUBLISHED_REGION_AND_SPLICE_CONDITIONS_MET' : 'BP1_PUBLISHED_SCOPE_AND_PROJECT_CHECKS_MET') &&
        localBatchText(f.sourceSpecification) && versionedTranscript(f.sourceTranscript) && versionedTranscript(f.transcript) &&
        versionedProtein(f.protein) && change !== null && positiveInteger(Number(change[2])) && change[1] !== change[3] &&
        typeof f.maximumObservedDelta === 'string' && /^0(?:\.(?:0[0-9]*|10*))?$/.test(f.maximumObservedDelta);
}

function validPublishedPm1Deletion(criterion, facts, expected) {
    const d = facts.proteinDeletion;
    if (d.schemaVersion !== 'published-pm1-protein-deletion-v1' ||
        !/^[a-f0-9]{64}$/.test(d.policySha256 || '') || !/^[a-f0-9]{64}$/.test(d.sourceSha256 || '') ||
        d.coverage !== 'COMPLETE_DOMAIN_DELETION' || !versionedTranscript(d.selectedTranscript)) return false;
    if (criterion.status === 'NOT_EVALUATED') return true;
    const binding = facts.currentEvaluationBinding;
    const transcript = expected?.context?.transcript;
    const interval = value => positiveInteger(value?.start) && positiveInteger(value?.end) && value.start <= value.end;
    if (!binding || binding.candidateId !== expected?.candidateId || binding.hypothesisId !== expected?.hypothesisId ||
        binding.originalContextId !== expected?.context?.contextId || binding.executionSourceSha256 !== expected?.executionSourceSha256 ||
        binding.status !== criterion.status || binding.appliedStrength !== criterion.appliedStrength ||
        binding.reasonCode !== criterion.reasonCode || d.selectedTranscript !== `${transcript?.accession}.${transcript?.version}` ||
        !interval(d.possibleDeletedResidues) || !positiveInteger(d.deletedAminoAcids) ||
        !Array.isArray(d.domains) || !d.domains.length ||
        !d.domains.every(domain => interval(domain) && ['COMPLETE', 'DISJOINT', 'PARTIAL_OR_AMBIGUOUS'].includes(domain.outcome))) return false;
    if (criterion.status === 'MET')
        return criterion.reasonCode === 'PM1_PUBLISHED_DOMAIN_DELETION_MET' && criterion.appliedStrength === 'MODERATE' &&
            interval(d.confirmedDeletedResidues) && d.domains.some(domain => domain.outcome === 'COMPLETE' &&
                domain.start >= d.confirmedDeletedResidues.start && domain.end <= d.confirmedDeletedResidues.end);
    return criterion.status === 'NOT_MET' && criterion.reasonCode === 'PM1_DELETION_OUTSIDE_PUBLISHED_DOMAINS' &&
        criterion.appliedStrength === 'NONE' && d.domains.every(domain => domain.outcome === 'DISJOINT' &&
            (domain.end < d.possibleDeletedResidues.start || d.possibleDeletedResidues.end < domain.start));
}

export function publishedMissenseDetails(criterion, profile, expected) {
    const code = criterion?.criterionId?.split(':').pop(), keys = { PM1: 'pm1Facts', PP2: 'pp2Facts', BP1: 'bp1Facts' };
    const f = criterion?.details?.[keys[code]];
    if (!f || !publishedMissenseCriterionValid(criterion, profile, expected)) return [];
    if (code === 'PM1' && f.reviewedRegion) return [];
    const condition = inspectPublishedConditionCriterion(criterion, expected, { identityHash: ps2IdentityHash, profile });
    if (condition.valid && condition.view?.kind === 'BP1_CURRENT_CONDITIONS') return publishedConditionDetails(condition);
    const version = `${f.declaredTranscript || f.sourceTranscript || '未取得'} → ${f.executionTranscript || f.transcript || '未取得'}`;
    const unsupportedEffect = criterion.reasonCode === 'CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED';
    const rows = [[`${code}发布规范与材料`, f.specificationKey || f.sourceSpecification || (unsupportedEffect
        ? '蛋白后果超出当前分支范围，尚未进入规范匹配；不表示数据库没有规范' : '未匹配合格发布规范')],
        ['规范 / 执行转录本', version], ['采用范围', '项目科研解释；保留原发布条件，未完成临床验证。']];
    if (code === 'BP1') rows.push(...functionalReferenceDetails(f.functionalReferences));
    if (code === 'PM1') rows.push(['蛋白位置与替换', f.proteinPosition ? `${f.protein}：${f.refAminoAcid}${f.proteinPosition}${f.altAminoAcid}` : '尚未取得完整序列核验'],
        ['命中区域', f.selectedRule ? `${f.selectedRule.start}–${f.selectedRule.end}；${f.selectedRule.regionType}；${f.selectedRule.effect}` : '未取得合格命中；不自动判为不满足'],
        ['PM1依据', '采用完整专家规范中的区域条件；普通结构域或良性记录未命中不能自动成立PM1。']);
    if (code === 'PP2') rows.push(['PP2胚系范围', f.germlineDeclared ? '本次明确声明胚系科研用途' : '尚未声明，不能据GT推断'],
        ['错义机制 / 低良性错义背景', `${f.conditions?.missenseMechanism?.conclusion || '未核定'} / ${f.conditions?.lowBenignMissense?.conclusion || '未核定'}`],
        ['PP2依据', '消费规范中的两项专家断言，不重新计算约束分数；非胚系或不同疾病用途不借用。']);
    if (code === 'BP1') rows.push(['BP1附加剪接核对', f.maximumObservedDelta === 'UNAVAILABLE' ? '未取得合格RAW预测' : `RAW最大变化分数 ${f.maximumObservedDelta}`],
        ...(f.knowledgeDisposition ? [['BP1资料适用状态', ({ EXECUTABLE: '已支持的发布条件，仍须逐项检查', NOT_APPLICABLE: '专家规范明确不适用',
            UNSUPPORTED_CONDITIONS: '来源已找到，软件尚未支持全部条件', SOURCE_CONFLICT: '来源条件冲突，待核定', UNRESOLVED: '尚未匹配合格完整资料' })[f.knowledgeDisposition] || '状态未识别，查看原始记录']] : []),
        ...(f.functionalScreen ? [['公开功能研究检查', `${({ CHECKED: '已核对本次加载的研究资料', REVIEW_REQUIRED: '发现需复核的功能结果', UNAVAILABLE: '研究资料或精确对应尚不充分', NOT_CHECKED: '尚未进入检查' })[f.functionalScreen.status] || '状态未识别'}；未覆盖不代表功能正常`],
            ...(Array.isArray(f.functionalScreen.matches) ? f.functionalScreen.matches : []).map(m => ['功能研究来源与命中', `${m?.sourceId || '未记录来源'}；${m?.proteinVariantKey || '编号未核定'}；${({ DAMAGING: '原研究报告功能受损', INTERMEDIATE: '原研究报告中间结果', FUNCTIONAL: '本项实验未见功能受损' })[m?.reportedClass] || (m?.outcome === 'OUTSIDE_REPORTED_SCREEN_RANGES' ? '该位点不在此研究筛查范围内' : '尚未取得该替换的原始行')}；${m?.sourceSheet ? `${m.sourceSheet} 第${m.sourceRow}行；` : ''}${m?.sourceUrl || ''}`])] : []),
        ...(f.scope === 'PUBLISHED_REGION_AND_SPLICE_CONDITIONS' ? [
            ['BP1区域核对', f.regionConditionCheck ? `蛋白位置 ${f.regionConditionCheck.proteinPosition}；排除区域 ${(Array.isArray(f.regionConditionCheck.excludedProteinRegions) ? f.regionConditionCheck.excludedProteinRegions : []).filter(r => r && typeof r === 'object').map(r => `${r.label} ${r.start}–${r.end}`).join('；')}` : '尚未完成精确区域核对'],
            ['BP1计算边界', '按该发布规范核对区域外条件及10 kb未掩码SpliceAI≤0.1，满足时采用强级；不是正常RNA实验，已知功能/剪接疑点须复核。']
        ] : [['BP1计算边界', '0.1是项目额外保守排除条件，不是专家BP1阈值或正常RNA实验；本地资料未收录冲突不代表外部资料已穷尽。']]));
    rows.push(...publishedConditionDetails(inspectPublishedConditionCriterion(criterion, expected, { identityHash: ps2IdentityHash, profile })));
    rows.push(['证据关系', '各条独立记录；未评价不是阴性，不自动相加或形成最终临床分类。']);
    return rows;
}

function pvs1ExpertAdditionalScopeValid(e) {
    const binding = e?.expertAssertions?.transcriptBinding;
    if (binding && (binding.method !== 'EXACT_FULL_TRANSCRIPT_EQUIVALENCE' || binding.executionContextUnchanged !== true ||
        !/^[a-f0-9]{64}$/.test(binding.verifiedModelSha256 || '') ||
        e.publishedRuleCheck && binding.verifiedModelSha256 !== e.publishedRuleCheck.verifiedModelSha256 ||
        `${binding.selectedTranscript?.accession}.${binding.selectedTranscript?.version}` !== e.sequence?.selectedTranscript ||
        !/^NM_\d+\.[1-9]\d*$/.test(`${binding.sourceTranscript?.accession}.${binding.sourceTranscript?.version}`))) return false;
    return (e?.expertAssertions?.entries || []).every(x => {
        const join = x.inheritanceScopeJoin;
        return !join || join.status === 'QUALIFIED' && join.methodVersion === '1.0.0' &&
            join.basis === 'PINNED_EXACT_DISEASE_MISSING_INHERITANCE_JOIN' && join.originalSourceModified === false && join.mechanismEstablished === false &&
            ['AUTOSOMAL_DOMINANT', 'AUTOSOMAL_RECESSIVE'].includes(join.resolvedMode) &&
            /^[a-f0-9]{64}$/.test(join.binding?.sourceCandidatesRawSha256 || '') &&
            Array.isArray(join.records) && join.records.length > 0 && join.records.every(r =>
                /^[a-f0-9]{64}$/.test(r.sourceRecordSha256 || '') && ['strong', 'definitive'].includes(r.sourceConfidence) &&
                r.sourceInheritance === (join.resolvedMode === 'AUTOSOMAL_DOMINANT' ? 'monoallelic_autosomal' : 'biallelic_autosomal')) &&
            Array.isArray(join.missingFieldLocators) && join.missingFieldLocators.length > 0 && join.missingFieldLocators.every(p =>
                /^\/ld\/RuleSet\/\d+\/entContent\/genes\/\d+\/diseases\/\d+\/preferredModeOfInheritance$/.test(p));
    });
}

function pvs1SourceQualifiedTranscriptValid(a, t) {
    const q = a?.sourceQualifiedTranscriptAssessment, e = q?.evidence, s = t?.sourceQualifiedAssessment;
    if (!q || q.method !== 'INTERNAL_SOURCE_REPLAY' || q.contextSha256 !== a.contextSha256 ||
        typeof q.functionalAlternativeFound !== 'boolean' || typeof q.completeNegative !== 'boolean' ||
        q.functionalAlternativeFound === q.completeNegative || !e || !s) return false;
    const status = q.functionalAlternativeFound ? 'FOUND_FUNCTIONAL_ALTERNATIVE' : 'NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET';
    const validEvidence = x => x.schemaVersion === 'pvs1-start-loss-source-qualified-transcripts-v1' &&
        x.contextSha256 === a.contextSha256 && x.status === status && x.complete === q.completeNegative &&
        Array.isArray(x.sourceBindings) && x.sourceBindings.length > 0 && x.sourceBindings.length <= 32 &&
        x.sourceBindings.every(b => localBatchText(b?.role) && /^[a-f0-9]{64}$/.test(b.sourceSha256 || '') &&
            localBatchText(b.sourceLocator) && typeof b.url === 'string' && b.url.length <= 2048 && b.url.startsWith('https://'));
    return validEvidence(e) && validEvidence(s) && e.sourceBindings.length === s.sourceBindings.length &&
        e.sourceBindings.every(b => s.sourceBindings.some(c => ['role', 'sourceSha256', 'sourceLocator', 'url'].every(k => b[k] === c[k]))) &&
        t.qualificationBasis === 'SOURCE_QUALIFIED_FUNCTIONAL_TRANSCRIPT_REVIEW' && t.status === status && t.complete === q.completeNegative;
}

function pvs1SourceExclusionResolvesSpecialist(a, expert) {
    const q = a?.sourceQualifiedTranscriptAssessment, e = q?.evidence, s = a?.preparation?.transcriptReview?.sourceQualifiedAssessment;
    if (q?.functionalAlternativeFound !== true || !e || !s || !Array.isArray(expert?.entries) || expert.entries.length === 0) return false;
    for (const evidence of [e, s]) {
        if (evidence.qualificationBasis !== 'EXPLICIT_SOURCE_START_LOSS_EXCLUSION' || evidence.policyDisposition !== 'PROHIBITED' ||
            !Array.isArray(evidence.expertSpecificationKeys) || evidence.expertSpecificationKeys.length === 0 ||
            !evidence.expertSpecificationKeys.every(localBatchText) || new Set(evidence.expertSpecificationKeys).size !== evidence.expertSpecificationKeys.length ||
            !Array.isArray(evidence.sourceBindings)) return false;
        const keys = new Set(evidence.expertSpecificationKeys), seen = new Set();
        for (const entry of expert.entries) {
            if (entry?.status !== 'UNRESOLVED' || entry.reasonCode !== 'EXPERT_EXACT_TRANSCRIPT_SCOPE_UNRESOLVED' ||
                !keys.has(entry.specificationKey) || !/^[a-f0-9]{64}$/.test(entry.source?.rawSha256 || '') ||
                !evidence.sourceBindings.some(b => b.sourceSha256 === entry.source.rawSha256)) return false;
            seen.add(entry.specificationKey);
        }
        if (seen.size !== keys.size) return false;
    }
    return true;
}

const pvs1ClinGenSourceHeaders = {
    'https://search.clinicalgenome.org/kb/gene-dosage/download': ['GENE SYMBOL', 'HGNC ID', 'HAPLOINSUFFICIENCY', 'TRIPLOSENSITIVITY', 'ONLINE REPORT', 'DATE'],
    'https://search.clinicalgenome.org/kb/gene-validity/download': ['GENE SYMBOL', 'GENE ID (HGNC)', 'DISEASE LABEL', 'DISEASE ID (MONDO)', 'MOI', 'SOP', 'CLASSIFICATION', 'ONLINE REPORT', 'CLASSIFICATION DATE', 'GCEP'],
    'https://ftp.clinicalgenome.org/ClinGen_gene_curation_list_GRCh38.tsv': ['Gene Symbol', 'Gene ID', 'cytoBand', 'Genomic Location',
        'Haploinsufficiency Score', 'Haploinsufficiency Description', 'Haploinsufficiency PMID1', 'Haploinsufficiency PMID2', 'Haploinsufficiency PMID3',
        'Haploinsufficiency PMID4', 'Haploinsufficiency PMID5', 'Haploinsufficiency PMID6', 'Triplosensitivity Score', 'Triplosensitivity Description',
        'Triplosensitivity PMID1', 'Triplosensitivity PMID2', 'Triplosensitivity PMID3', 'Triplosensitivity PMID4', 'Triplosensitivity PMID5', 'Triplosensitivity PMID6',
        'Date Last Evaluated', 'Haploinsufficiency Disease ID', 'Triplosensitivity Disease ID']
};
const pvs1ClinGenBasis = 'CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_VALIDITY';
const pvs1ClinGenG2pBasis = 'CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_G2P_ASSOCIATION';
const pvs1DateValid = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    Number.isFinite(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
const pvs1DateTimeValid = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    pvs1DateValid(value.slice(0, 10)) && Number.isFinite(Date.parse(value));

function pvs1ClinGenSourceRowValid(source) {
    const headers = Object.hasOwn(pvs1ClinGenSourceHeaders, source?.sourceUrl) ? pvs1ClinGenSourceHeaders[source.sourceUrl] : null;
    const record = source?.sourceRecord;
    return !!headers && /^[a-f0-9]{64}$/.test(source.sha256 || '') && Number.isSafeInteger(source.sourceRow) && source.sourceRow >= 7 && source.sourceRow <= 10000 &&
        pvs1DateValid(source.capturedDate) && pvs1DateValid(source.fileCreatedDate) && source.fileCreatedDate <= source.capturedDate &&
        !!record && !Array.isArray(record) && Object.keys(record).length === headers.length &&
        headers.every(key => Object.hasOwn(record, key) && typeof record[key] === 'string' && record[key].length <= 32768);
}

function pvs1G2pAssociationAnchorValid(c, b, original, contextSha256) {
    const a = c.associationAnchor, scan = c.clinGenValidityCheck, binding = a?.binding;
    if (a?.schemaVersion !== 'pvs1-start-loss-association-v1' || !compatibleRuntimeVersion(a.methodVersion, 1) ||
        a.status !== 'CURATED_ASSOCIATION_SUPPORTED' || a.contextSha256 !== contextSha256 || a.basis !== 'PINNED_G2P_EXACT_DISEASE_ASSOCIATION' ||
        a.reasonCode !== 'PVS1_START_LOSS_PINNED_G2P_ASSOCIATION' || a.runtimeEligible !== false || a.mechanismEstablished !== false || a.transcriptRelevanceEstablished !== false ||
        binding?.sourceDataClass !== 'PUBLIC_REFERENCE' || binding.source?.url !== 'https://www.ebi.ac.uk/gene2phenotype/api/panel/all/download/' ||
        !localBatchText(binding.source.version) || !/^[a-f0-9]{64}$/.test(binding.source.sha256 || '') ||
        !/^[a-f0-9]{64}$/.test(binding.sourceCandidatesRawSha256 || '') || !/^[a-f0-9]{64}$/.test(binding.adoptionRawSha256 || '') ||
        original.binding?.sourceCandidatesRawSha256 !== binding.sourceCandidatesRawSha256 || !/^[a-f0-9]{64}$/.test(a.sourceRecordSetSha256 || '') ||
        !Array.isArray(a.records) || a.records.length < 1 || a.records.length > 20 || new Set(a.records.map(r => r?.recordId)).size !== a.records.length ||
        c.associationSource !== 'G2P' || c.validityClassification !== 'NOT_USED_G2P_ASSOCIATION_ANCHOR') return false;
    for (const row of a.records) {
        const raw = row?.sourceRecord;
        if (!raw || !['g2p id', 'hgnc id', 'gene symbol', 'disease MONDO', 'disease name', 'allelic requirement', 'confidence', 'comments', 'review',
            'cross cutting modifier', 'date of last review', 'molecular mechanism support'].every(key => typeof raw[key] === 'string' && raw[key].length <= 32768) ||
            row.status !== 'UNREVIEWED' || row.runtimeEligible !== false || !Number.isSafeInteger(row.sourceRow) || row.sourceRow < 1 ||
            !/^G2P\d+$/.test(raw['g2p id'].trim()) || row.recordId !== `${raw['g2p id'].trim()}:${row.sourceRow}` || !/^[a-f0-9]{64}$/.test(row.sourceRecordSha256 || '') ||
            !original.findings.some(f => f.recordId === row.recordId && f.sourceRecordSha256 === row.sourceRecordSha256) ||
            (raw['hgnc id'].trim().startsWith('HGNC:') ? raw['hgnc id'].trim() : `HGNC:${raw['hgnc id'].trim()}`) !== b.hgncId || raw['gene symbol'] !== b.geneSymbol ||
            raw['disease MONDO'].trim() !== b.mondoId || !localBatchText(raw['disease name']) || raw['allelic requirement'].trim() !== 'monoallelic_autosomal' ||
            !['strong', 'definitive'].includes(raw.confidence.trim()) || ['comments', 'review', 'cross cutting modifier'].some(key => raw[key].trim()) ||
            !pvs1DateTimeValid(raw['date of last review'].replace(' ', 'T'))) return false;
    }
    if (c.diseaseLabel !== a.records[0].sourceRecord['disease name'] || scan?.sourceUrl !== 'https://search.clinicalgenome.org/kb/gene-validity/download' ||
        scan.sourceEnumerationComplete !== true || !/^[a-f0-9]{64}$/.test(scan.sha256 || '') || !pvs1DateValid(scan.fileCreatedDate) ||
        !pvs1DateValid(scan.capturedDate) || scan.fileCreatedDate > scan.capturedDate || !Array.isArray(scan.records)) return false;
    if (scan.status === 'NO_EXACT_DISEASE_ASSOCIATION') return scan.records.length === 0;
    if (scan.status !== 'INSUFFICIENT_ASSOCIATION_STRENGTH' || scan.records.length < 1 || scan.records.length > 20 ||
        new Set(scan.records.map(row => row?.sourceRow)).size !== scan.records.length) return false;
    const validRows = scan.records.every(row => {
        const v = row?.sourceRecord;
        return pvs1ClinGenSourceRowValid(row) && row.sourceUrl === scan.sourceUrl && row.sha256 === scan.sha256 &&
        row.fileCreatedDate === scan.fileCreatedDate && row.capturedDate === scan.capturedDate &&
        v['GENE SYMBOL'] === b.geneSymbol && v['GENE ID (HGNC)'] === b.hgncId && v['DISEASE ID (MONDO)'] === b.mondoId && localBatchText(v.MOI) &&
        /^SOP[1-9]\d*$/.test(v.SOP) && localBatchText(v.GCEP) &&
        /^https:\/\/search\.clinicalgenome\.org\/kb\/gene-validity\/CGGV:assertion_[A-Za-z0-9_.:-]+$/.test(v['ONLINE REPORT']) &&
        pvs1DateTimeValid(v['CLASSIFICATION DATE']) && v['CLASSIFICATION DATE'].slice(0, 10) <= row.fileCreatedDate;
    });
    const matchingMode = scan.records.filter(row => row?.sourceRecord?.MOI === 'AD');
    return validRows && matchingMode.length === 1 && ['Limited', 'Moderate'].includes(matchingMode[0].sourceRecord.CLASSIFICATION);
}

/** Validate the saved semantic contract, rather than treating an additive
 * runtime release as a change to a scientific source or its acceptance. */
export function pvs1StartLossMechanismValid(m, contextSha256) {
    if (m?.schemaVersion !== 'pvs1-start-loss-mechanism-v1' || !compatibleRuntimeVersion(m.methodVersion, 1) ||
        m.status !== 'CURATED_MECHANISM_SUPPORTED' || !/^[a-f0-9]{64}$/.test(contextSha256 || '') ||
        m.contextSha256 !== contextSha256 || m.runtimeEligible !== false) return false;
    if (![pvs1ClinGenBasis, pvs1ClinGenG2pBasis].includes(m.basis)) return Array.isArray(m.findings) && m.findings.length > 0 && m.findings.length <= 20 &&
        m.findings.every(x => x?.status === 'CURATED_MECHANISM_SUPPORTED' && x.sourceSupport === 'evidence' && ['strong', 'definitive'].includes(x.sourceConfidence));
    const c = m.clingenAssessment, b = c?.binding, original = m.g2pAssessment, g2pAssociation = m.basis === pvs1ClinGenG2pBasis;
    if (m.primaryExperimentsReevaluated !== false || m.transcriptRelevanceEstablished !== false ||
        c?.schemaVersion !== 'pvs1-start-loss-clingen-mechanism-v1' || !compatibleRuntimeVersion(c.methodVersion, 1) ||
        c.status !== 'CURATED_MECHANISM_SUPPORTED' || c.contextSha256 !== contextSha256 || c.basis !== m.basis ||
        c.reasonCode !== 'PVS1_START_LOSS_CLINGEN_DISEASE_HAPLOINSUFFICIENCY' || m.reasonCode !== c.reasonCode ||
        c.runtimeEligible !== false || c.primaryExperimentsReevaluated !== false || c.transcriptRelevanceEstablished !== false ||
        c.hiScore !== 3 || !g2pAssociation && !['Strong', 'Definitive'].includes(c.validityClassification) || !localBatchText(c.diseaseLabel) ||
        !pvs1DateValid(c.evaluationDate) || !/^[1-9]\d*$/.test(c.geneId || '') ||
        !Array.isArray(c.publications) || c.publications.length < 1 || c.publications.length > 6 || c.publications.some(p => !/^[1-9]\d*$/.test(p)) ||
        b?.sourceDataClass !== 'PUBLIC_REFERENCE' || !/^HGNC:[1-9]\d*$/.test(b.hgncId || '') ||
        !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(b.geneSymbol || '') || !/^MONDO:\d{7}$/.test(b.mondoId || '') || b.moi !== 'AUTOSOMAL_DOMINANT' ||
        original?.schemaVersion !== 'pvs1-start-loss-mechanism-v1' || !compatibleRuntimeVersion(original.methodVersion, 1) ||
        original.status !== 'UNRESOLVED' || original.contextSha256 !== contextSha256 || original.runtimeEligible !== false ||
        ['PVS1_START_LOSS_MECHANISM_CONFLICT', 'PVS1_START_LOSS_MECHANISM_QUALIFIERS_UNSUPPORTED'].includes(original.reasonCode) ||
        !Array.isArray(original.findings) || original.findings.length > 20 || original.findings.some(x => !x ||
            !['UNRESOLVED', 'CURATED_MECHANISM_SUPPORTED'].includes(x.status) || !localBatchText(x.reasonCode) ||
            ['PVS1_START_LOSS_MECHANISM_CONFLICT', 'PVS1_START_LOSS_MECHANISM_QUALIFIERS_UNSUPPORTED'].includes(x.reasonCode))) return false;
    if (g2pAssociation && !pvs1G2pAssociationAnchorValid(c, b, original, contextSha256)) return false;
    const sources = b.sources, expectedUrls = Object.keys(pvs1ClinGenSourceHeaders).filter(url => !g2pAssociation || !url.includes('gene-validity'));
    if (!Array.isArray(sources) || sources.length !== expectedUrls.length || new Set(sources.map(s => s?.sourceUrl)).size !== expectedUrls.length ||
        sources.some(source => !expectedUrls.includes(source?.sourceUrl) || !pvs1ClinGenSourceRowValid(source))) return false;
    const [dosage, validity, disease] = Object.keys(pvs1ClinGenSourceHeaders).map(url => sources.find(s => s.sourceUrl === url));
    const d = dosage.sourceRecord, v = validity?.sourceRecord, t = disease.sourceRecord;
    if (d['GENE SYMBOL'] !== b.geneSymbol || d['HGNC ID'] !== b.hgncId || d.HAPLOINSUFFICIENCY !== 'Sufficient Evidence for Haploinsufficiency' ||
        d['ONLINE REPORT'] !== `https://search.clinicalgenome.org/kb/gene-dosage/${b.hgncId}` || !pvs1DateTimeValid(d.DATE) ||
        d.DATE.slice(0, 10) !== c.evaluationDate || c.evaluationDate > dosage.fileCreatedDate ||
        !g2pAssociation && (v['GENE SYMBOL'] !== b.geneSymbol || v['GENE ID (HGNC)'] !== b.hgncId || v['DISEASE ID (MONDO)'] !== b.mondoId || v.MOI !== 'AD' ||
        v['DISEASE LABEL'] !== c.diseaseLabel || v.CLASSIFICATION !== c.validityClassification || !/^SOP[1-9]\d*$/.test(v.SOP) || !localBatchText(v.GCEP) ||
        !/^https:\/\/search\.clinicalgenome\.org\/kb\/gene-validity\/CGGV:assertion_[A-Za-z0-9_.:-]+$/.test(v['ONLINE REPORT']) ||
        !pvs1DateTimeValid(v['CLASSIFICATION DATE']) || v['CLASSIFICATION DATE'].slice(0, 10) > validity.fileCreatedDate) ||
        t['Gene Symbol'] !== b.geneSymbol || t['Gene ID'] !== c.geneId || t['Haploinsufficiency Disease ID'] !== b.mondoId ||
        t['Haploinsufficiency Score'] !== '3' || t['Haploinsufficiency Description'] !== 'Sufficient evidence for dosage pathogenicity' ||
        !/^chr(?:[1-9]|1[0-9]|2[0-2]|X|Y):[1-9]\d*-[1-9]\d*$/.test(t['Genomic Location']) ||
        t['Date Last Evaluated'] !== c.evaluationDate || c.evaluationDate > disease.fileCreatedDate) return false;
    const pmids = Array.from({ length: 6 }, (_, i) => t[`Haploinsufficiency PMID${i + 1}`]);
    if (pmids.some(p => p && !/^[1-9]\d*$/.test(p)) || JSON.stringify(pmids.filter(Boolean)) !== JSON.stringify(c.publications)) return false;
    const bound = m.binding;
    return ['sourceDataClass', 'hgncId', 'geneSymbol', 'mondoId', 'moi'].every(key => bound?.[key] === b[key]) &&
        Array.isArray(bound.sources) && bound.sources.length === expectedUrls.length && new Set(bound.sources.map(s => s?.sourceUrl)).size === expectedUrls.length &&
        bound.sources.every(s => {
            const other = sources.find(x => x.sourceUrl === s?.sourceUrl);
            const headers = Object.hasOwn(pvs1ClinGenSourceHeaders, s?.sourceUrl) ? pvs1ClinGenSourceHeaders[s.sourceUrl] : null;
            return other && headers && ['sha256', 'sourceRow', 'capturedDate', 'fileCreatedDate'].every(key => s[key] === other[key]) &&
                s.sourceRecord && Object.keys(s.sourceRecord).length === headers.length && headers.every(key => s.sourceRecord[key] === other.sourceRecord[key]);
        });
}

function pvs1SelectedCriterionValid(criterion, context, profile) {
    if (criterion.criterionId !== 'ACMG_AMP_2015:PVS1') return !Object.hasOwn(criterion.details || {}, 'pvs1Facts');
    if (criterion.details?.pm5PtcFacts) return publishedPtcCriterionValid(criterion, context);
    const f = criterion.details?.pvs1Facts;
    if (!f) return criterion.status === 'NOT_EVALUATED';
    if (isPvs1CodingEscapeAssessment(f)) {
        const inspected = inspectPvs1CodingEscapeAssessment(criterion, context, { identityHash: ps2IdentityHash, profile });
        // Availability-only is admitted through the independent row/profile
        // guards, then withheld from scientific adoption by the sentinel above.
        return inspected.valid || inspected.view?.availabilityOnly === true;
    }
    if (isPvs1SpliceSummary(f)) {
        if (profile && criterion.status !== 'NOT_EVALUATED' && f.methodVersion !== '1.1.0') {
            const release = profile.moduleBindings?.pvs1?.runtimeBinding?.pvs1SelectedVersion;
            if (!compatibleRuntimeVersion(release, 1) || Number(release.split('.')[1]) < 2) return false;
        }
        return validatePvs1SpliceCriterion(criterion, context, { identityHash: ps2IdentityHash, profile });
    }
    if (['1.5.0', '1.6.0'].includes(f.methodVersion)) {
        const a = f.automaticAssessment, p = a?.preparation, s = a?.sequence, t = p?.transcriptReview, u = p?.upstreamReview, m = p?.mechanismAssessment;
        const native = f.methodVersion === '1.6.0', alternative = native && a?.sourceQualifiedTranscriptAssessment?.functionalAlternativeFound === true;
        const expected = alternative ? 'NONE' : u?.status === 'WITNESS_VALIDATED' && u.qualifyingReferenceCount > 0 ? 'MODERATE'
            : u?.status === 'NO_QUALIFYING_REFERENCE_WITHIN_SCOPE' && u.complete === true && u.negativeCoverageGranted === true && u.coverage?.complete === true ? 'SUPPORTING' : null;
        const reason = alternative ? 'PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT'
            : expected === 'MODERATE' ? 'PVS1_START_LOSS_UPSTREAM_PATHOGENIC_REFERENCE' : 'PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE';
        return f.schemaVersion === 'pvs1-selected-facts-v1' && f.scope === 'AUTOMATIC_GENERIC_START_LOSS' && f.clinicalValidationCompleted === false &&
            a?.schemaVersion === 'pvs1-start-loss-automatic-facts-v1' && a.methodVersion === (native ? '1.1.0' : '1.0.0') &&
            /^[a-f0-9]{64}$/.test(a.contextSha256 || '') && /^[a-f0-9]{64}$/.test(a.verifiedModelSha256 || '') &&
            a.rule === 'CLINGEN_SVI_START_LOSS_2018_ASSESSED_SOURCE_SET' && a.ruleSource === 'https://pubmed.ncbi.nlm.nih.gov/30192042/' &&
            a.doubleCountingConstraint === 'NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE' &&
            criterion.status === (alternative ? 'NOT_APPLICABLE' : 'MET') && a.decisionOutcome === (alternative ? 'NOT_APPLICABLE' : 'MET') &&
            expected && expected === a.decisionStrength && criterion.appliedStrength === expected &&
            criterion.reasonCode === reason && a.reasonCode === reason &&
            p?.status === 'GENERIC_FACTS_QUALIFIED' && p.contextSha256 === a.contextSha256 && Array.isArray(p.issues) && p.issues.length === 0 &&
            (p.expertAssertions?.status === 'NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT' && Array.isArray(p.expertAssertions.entries) && p.expertAssertions.entries.length === 0 ||
                native && alternative && pvs1SourceExclusionResolvesSpecialist(a, p.expertAssertions)) &&
            (alternative || pvs1StartLossMechanismValid(m, a.contextSha256)) &&
            (native ? pvs1SourceQualifiedTranscriptValid(a, t) : t?.status === 'NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET' && t.complete === true &&
                t.sourceEnumerationComplete === true && t.selectedTranscriptPresent === true &&
                t.qualificationBasis === 'COMPLETE_STRUCTURAL_SOURCE_SET_NO_DIFFERENT_START' && t.unresolvedCount === 0) &&
            s?.status === 'AVAILABLE' && versionedTranscript(s.selectedTranscript) && [-1, 1].includes(s.strand) && [1, 2, 3].includes(s.cdsPosition) &&
            s.referenceCodon === 'ATG' && /^[ACGT]{3}$/.test(s.alternateCodon || '') && s.alternateCodon !== 'ATG' &&
            (alternative ? u?.status === 'NOT_REQUIRED' : Number.isSafeInteger(s.candidateRestartCdsPosition) && s.candidateRestartCdsPosition > 3 && (s.candidateRestartCdsPosition - 1) % 3 === 0);
    }
    if (f.methodVersion === '1.4.0') {
        const e = f.expertAssessment, s = e?.sequence, check = e?.publishedRuleCheck, entries = e?.expertAssertions?.entries;
        if (!pvs1ExpertAdditionalScopeValid(e)) return false;
        return f.schemaVersion === 'pvs1-selected-facts-v1' && f.scope === 'PUBLISHED_EXPERT_START_LOSS' && f.clinicalValidationCompleted === false &&
            e?.schemaVersion === 'pvs1-start-loss-expert-facts-v2' && e.methodVersion === '1.3.0' && /^[a-f0-9]{64}$/.test(e.contextSha256 || '') &&
            e.decisionOutcome === 'MET' && ['SUPPORTING', 'MODERATE'].includes(e.decisionStrength) && criterion.status === 'MET' &&
            criterion.appliedStrength === e.decisionStrength && criterion.reasonCode === e.reasonCode && e.reasonCode === 'PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE' &&
            e.doubleCountingConstraint === 'NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE' &&
            check?.status === 'QUALIFIED' && check.strength === e.decisionStrength && check.reasonCode === e.reasonCode && /^[a-f0-9]{64}$/.test(check.verifiedModelSha256 || '') &&
            s?.schemaVersion === 'pvs1-start-loss-sequence-facts-v1' && ['1.0.0', '1.1.0', '1.2.0'].includes(s.methodVersion) &&
            s.status === 'AVAILABLE' && s.clinicalScoringPerformed === false && versionedTranscript(s.selectedTranscript) && [-1, 1].includes(s.strand) &&
            [1, 2, 3].includes(s.cdsPosition) && s.referenceCodon === 'ATG' && /^[ACGT]{3}$/.test(s.alternateCodon || '') && s.alternateCodon !== 'ATG' &&
            Array.isArray(entries) && entries.length > 0 && entries.every(x => {
                const r = x?.startLossRule, next = r?.requiredRestartProteinPosition, protein = r?.requiredProtein;
                return x?.status !== 'CONFLICT' && x.policyDisposition !== 'PROHIBITED' && x.scopeRelation === 'MATCHING_CLAUSE_REQUIRES_SELECTION' &&
                    localBatchText(x.specificationKey) && /^[a-f0-9]{64}$/.test(x.source?.rawSha256 || '') && /^\/data(?:\/\d+)?$/.test(x.source?.jsonPointer || '') &&
                    r?.status === 'QUALIFIED' && r.method === 'EXPLICIT_START_LOSS_STRENGTH_V1' && r.strength === e.decisionStrength &&
                    r.basis === 'EXPLICIT_UNCONDITIONAL_INITIATION_CODON_CLAUSE' && Number.isSafeInteger(next) && next >= 0 && next <= 100000 &&
                    (next === 0 ? protein === '' : next > 1 && /^NP_\d+(?:\.[1-9]\d*)?$/.test(protein || '') &&
                        s.candidateRestartCdsPosition === (next - 1) * 3 + 1 && /^NP_\d+\.[1-9]\d*$/.test(check.verifiedProtein || '') &&
                        (protein.includes('.') ? protein === check.verifiedProtein : protein === check.verifiedProtein.split('.')[0])) &&
                    Array.isArray(r.claims) && r.claims.length > 0 && r.claims.every(c => localBatchText(c?.sourceText) && localBatchText(c?.matchedParagraph) &&
                        /^\/ld\/CriteriaCode\/\d+\/entContent\//.test(c?.sourceLocator || ''));
            });
    }
    if (f.methodVersion === '1.3.0') {
        const e = f.expertAssessment, s = e?.sequence, entries = e?.expertAssertions?.entries;
        if (!pvs1ExpertAdditionalScopeValid(e)) return false;
        return f.schemaVersion === 'pvs1-selected-facts-v1' && f.scope === 'PUBLISHED_EXPERT_START_LOSS' &&
            f.clinicalValidationCompleted === false && e?.schemaVersion === 'pvs1-start-loss-expert-facts-v1' && ['1.1.0', '1.2.0'].includes(e.methodVersion) &&
            /^[a-f0-9]{64}$/.test(e.contextSha256 || '') && e.decisionOutcome === 'NOT_APPLICABLE' && e.decisionStrength === 'NONE' &&
            criterion.status === 'NOT_APPLICABLE' && criterion.appliedStrength === 'NONE' && criterion.reasonCode === e.reasonCode &&
            e.reasonCode === 'PVS1_START_LOSS_PUBLISHED_ALTERNATIVE_START_EXCLUSION' &&
            s?.schemaVersion === 'pvs1-start-loss-sequence-facts-v1' && ['1.0.0', '1.1.0', '1.2.0'].includes(s.methodVersion) &&
            s.status === 'AVAILABLE' && s.clinicalScoringPerformed === false && versionedTranscript(s.selectedTranscript) &&
            [-1, 1].includes(s.strand) && [1, 2, 3].includes(s.cdsPosition) && s.referenceCodon === 'ATG' &&
            /^[ACGT]{3}$/.test(s.alternateCodon || '') && s.alternateCodon !== 'ATG' &&
            Array.isArray(entries) && entries.length > 0 && entries.every(x => x?.status === 'QUALIFIED' &&
                x.policyDisposition === 'PROHIBITED' && x.transcriptStatus === 'FOUND_FUNCTIONAL_ALTERNATIVE' &&
                x.scopeRelation === 'MATCHING_CLAUSE_REQUIRES_SELECTION' && localBatchText(x.specificationKey) &&
                /^[a-f0-9]{64}$/.test(x.source?.rawSha256 || '') && /^\/data(?:\/\d+)?$/.test(x.source?.jsonPointer || '') &&
                Array.isArray(x.claims) && x.claims.length > 0 && x.claims.every(c =>
                    c?.assertion === 'FUNCTIONAL_ALTERNATIVE_EXPERT_EXCLUSION' && localBatchText(c.sourceText) &&
                    /^\/ld\/CriteriaCode\/\d+\/entContent\//.test(c.sourceLocator || '')));
    }
    if (f.schemaVersion !== 'pvs1-selected-facts-v1' || !['1.0.0', '1.1.0', '1.2.0'].includes(f.methodVersion) || f.scope !== 'REVIEWED_SELECTED_TRANSCRIPT' ||
        f.clinicalValidationCompleted !== false || !localBatchText(f.knowledgeStatus) || !localBatchText(f.knowledgeReason) ||
        f.doubleCountingConstraint !== 'NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE') return false;
    if (f.methodVersion === '1.2.0') {
        const s = f.startLossAssessment;
        if (s?.schemaVersion !== 'pvs1-start-loss-sequence-facts-v1' || !['1.0.0', '1.1.0', '1.2.0'].includes(s.methodVersion) ||
            !['AVAILABLE', 'CONFLICT', 'UNSUPPORTED', 'UNRESOLVED'].includes(s.status) || s.clinicalScoringPerformed !== false) return false;
        if (s.status === 'AVAILABLE' && (!versionedTranscript(s.selectedTranscript) || ![-1, 1].includes(s.strand) ||
            ![1, 2, 3].includes(s.cdsPosition) || s.referenceCodon !== 'ATG' || !/^[ACGT]{3}$/.test(s.alternateCodon) ||
            s.alternateCodon === 'ATG' || s.candidateRestartCdsPosition != null &&
            (!Number.isSafeInteger(s.candidateRestartCdsPosition) || s.candidateRestartCdsPosition <= 3 || (s.candidateRestartCdsPosition - 1) % 3 !== 0))) return false;
        const bound = /^[a-f0-9]{64}$/.test(s.runtimeMaterialSha256 || '') && Array.isArray(s.sources) && s.sources.length > 0 &&
            s.sources.every(x => localBatchText(x.sourceId) && ['TRANSCRIPT_REVIEW', 'UPSTREAM_REVIEW'].includes(x.role) &&
                /^https:\/\//.test(x.url || '') && /^[a-f0-9]{64}$/.test(x.sha256 || '') && localBatchText(x.reviewId)) &&
            new Set(s.sources.map(x => x.sourceId)).size === s.sources.length &&
            s.sources.filter(x => x.role === 'TRANSCRIPT_REVIEW').length === 1;
        const audit = s.materialEvidence;
        const transcriptAudit = bound && Array.isArray(audit?.transcriptFindings) && audit.transcriptFindings.length > 0 &&
            audit.transcriptFindings.every(x => versionedTranscript(x.transcript) && /^\/(?:contexts\/[a-f0-9]{64}\/)?findings\/\d+$/.test(x.sourceLocator || '') &&
                s.sources.some(src => src.sourceId === x.sourceId && src.role === 'TRANSCRIPT_REVIEW'));
        const referenceAudit = bound && Array.isArray(audit?.referenceReviews) && audit.referenceReviews.every(x =>
            /^\/(?:contexts\/[a-f0-9]{64}\/)?records\/\d+$/.test(x.sourceLocator || '') && s.sources.some(src => src.sourceId === x.sourceId && src.role === 'UPSTREAM_REVIEW') &&
            (s.methodVersion === '1.2.0' && ['UNRESOLVED', 'CONFLICT'].includes(x.disposition)
                ? ['REFERENCE_MAPPING_UNRESOLVED', 'REFERENCE_BACKGROUND_UNRESOLVED', 'REFERENCE_STATUS_UNRESOLVED', 'REFERENCE_INDEPENDENCE_UNRESOLVED', 'REFERENCE_CONFLICT'].includes(x.reasonCode)
                : positiveInteger(x.cdsPosition) && ['QUALIFYING', 'DUPLICATE_EXCLUDED', 'OUTSIDE_UPSTREAM_INTERVAL', 'TARGET_EXCLUDED', 'WITHDRAWN_EXCLUDED', 'BASELINE_PATHOGENIC_REQUIRED'].includes(x.disposition)) &&
            (x.disposition !== 'QUALIFYING' || x.classification === 'PATHOGENIC' && x.classificationBasis === 'INDEPENDENT_OF_START_LOSS_INFERENCE' &&
                x.cdsPosition < s.candidateRestartCdsPosition));
        if (s.methodVersion === '1.2.0' && s.upstreamSearchComplete === true &&
            Array.isArray(audit?.referenceReviews) && audit.referenceReviews.some(x => x && ['UNRESOLVED', 'CONFLICT'].includes(x.disposition))) return false;
        if (criterion.status === 'MET') return s.status === 'AVAILABLE' && f.policySchema === 'pvs1-start-loss-policy-v1' &&
            s.decisionOutcome === 'MET' && ['SUPPORTING', 'MODERATE'].includes(s.decisionStrength) &&
            criterion.appliedStrength === s.decisionStrength && criterion.reasonCode === s.decisionReasonCode &&
            transcriptAudit && referenceAudit && audit.referenceReviews.filter(x => x.disposition === 'QUALIFYING').length === s.qualifyingReferenceCount && s.transcriptReviewComplete === true &&
            s.transcriptReviewStatus === 'NOT_FOUND_IN_ASSESSED_SET' && typeof s.upstreamSearchComplete === 'boolean' &&
            s.sources.some(x => x.role === 'UPSTREAM_REVIEW') && positiveInteger(s.candidateRestartCdsPosition) &&
            s.reviewReasonCode === 'PVS1_START_LOSS_MATERIALS_REPLAYED' && Number.isSafeInteger(s.qualifyingReferenceCount) &&
            (s.decisionStrength === 'MODERATE' ? s.qualifyingReferenceCount > 0 && s.upstreamReferenceReviewStatus === 'QUALIFYING_FOUND' &&
                s.decisionReasonCode === 'PVS1_START_LOSS_UPSTREAM_PATHOGENIC_REFERENCE'
                : s.upstreamSearchComplete === true && s.qualifyingReferenceCount === 0 && s.upstreamReferenceReviewStatus === 'NONE_IN_ASSESSED_SOURCES' &&
                s.decisionReasonCode === 'PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE');
        if (criterion.status !== 'NOT_EVALUATED' || criterion.appliedStrength !== 'NONE' ||
            s.decisionStrength && s.decisionStrength !== 'NONE' || s.decisionOutcome === 'MET') return false;
        if (s.decisionOutcome === 'NOT_APPLICABLE') return s.status === 'AVAILABLE' && f.policySchema === 'pvs1-start-loss-policy-v1' &&
            (['PVS1_START_LOSS_POLICY_PROHIBITS_CRITERION', 'PVS1_START_LOSS_LOF_NOT_APPLICABLE'].includes(s.decisionReasonCode) ||
                s.decisionReasonCode === 'PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT' && bound &&
                transcriptAudit && audit.transcriptFindings.some(x => x.transcript !== s.selectedTranscript && x.functionallyRelevant === true && x.differentStart === true && x.targetUnaffected === true) &&
                typeof s.transcriptReviewComplete === 'boolean' && s.transcriptReviewStatus === 'FOUND_FUNCTIONAL_ALTERNATIVE');
        return !s.decisionOutcome || ['INSUFFICIENT_INFORMATION', 'SOURCE_UNAVAILABLE', 'CONFLICT', 'RULE_NOT_IMPLEMENTED', 'SOFTWARE_UNSUPPORTED'].includes(s.decisionOutcome);
    }
    const t = f.transcriptTopology, supply = f.materialSupply, strengths = ['SUPPORTING', 'MODERATE', 'STRONG', 'VERY_STRONG'];
    if (f.methodVersion === '1.0.0' && (t || supply || Object.hasOwn(f, 'policyStrengthCap') || Object.hasOwn(f, 'sourceQualifiedResearch'))) return false;
    if (f.methodVersion === '1.1.0' && (typeof f.sourceQualifiedResearch !== 'boolean' || ![...strengths, 'UNAVAILABLE'].includes(f.policyStrengthCap))) return false;
    if (supply && (supply.bindingMethod !== 'EXACT_FULL_TRANSCRIPT_EQUIVALENCE' || !['READY', 'UNAVAILABLE'].includes(supply.status) ||
        !localBatchText(supply.reasonCode) || supply.status === 'READY' && (!positiveInteger(supply.verifiedExonCount) || supply.policyModelUnchanged !== true))) return false;
    if (t && (t.version !== '1.5.0' || !positiveInteger(t.exonCount) || !positiveInteger(t.cdsLength) || typeof t.utrApplicabilityBound !== 'boolean' ||
        !['SINGLE_EXON', 'LAST_JUNCTION_IN_3_PRIME_UTR'].includes(t.kind) ||
        t.kind === 'SINGLE_EXON' && (t.exonCount !== 1 || t.referenceLastJunctionCdsPosition !== null ||
            f.lastJunctionCdsPosition != null || f.distanceToLastJunction != null) ||
        t.kind === 'LAST_JUNCTION_IN_3_PRIME_UTR' && (t.exonCount < 2 || !Number.isSafeInteger(t.referenceLastJunctionCdsPosition) || t.referenceLastJunctionCdsPosition < t.cdsLength))) return false;
    if (criterion.status !== 'MET') return true;
    if (f.methodVersion === '1.1.0' && (!strengths.includes(f.policyStrengthCap) || strengths.indexOf(criterion.appliedStrength) < 0 ||
        strengths.indexOf(criterion.appliedStrength) > strengths.indexOf(f.policyStrengthCap) || supply && supply.status !== 'READY' ||
        f.sourceQualifiedResearch && !supply)) return false;
    const single = t?.kind === 'SINGLE_EXON';
    if (t && (t.exonCount !== f.exonTotal || single && f.nmdPrediction !== 'NMD_NOT_PREDICTED' ||
        !single && (!t.utrApplicabilityBound || (f.referenceLastJunctionCdsPosition ?? f.lastJunctionCdsPosition) !== t.referenceLastJunctionCdsPosition))) return false;
    if (f.knowledgeStatus !== 'READY' || !/^pvs1-truncating-nmd-policy-v[1-4]$/.test(f.policySchema || '') ||
        !versionedTranscript(f.selectedTranscript) || !['PREDICTED_NMD', 'NMD_NOT_PREDICTED'].includes(f.nmdPrediction) ||
        !['GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE', 'SOURCE_BOUND_SPLICE_EDIT_AND_TRANSCRIPT_SEQUENCE_AGREE'].includes(f.sequenceDerivationStatus) ||
        !['exonOrdinal', 'exonTotal', 'cdsPosition', 'ptcStart', 'ptcEnd'].every(k => positiveInteger(f[k])) || f.exonOrdinal > f.exonTotal ||
        f.ptcEnd !== f.ptcStart + 2 || !single && (!Number.isSafeInteger(f.lastJunctionCdsPosition) || f.lastJunctionCdsPosition < 0 ||
        !Number.isSafeInteger(f.distanceToLastJunction) || f.distanceToLastJunction !== f.lastJunctionCdsPosition - f.ptcEnd) ||
        f.nmdPrediction === 'NMD_NOT_PREDICTED' && (f.escapeAssessment?.schemaVersion !== 'pvs1-nmd-escape-assessment-v1' || f.escapeAssessment.status !== 'MET')) return false;
    if (f.variantMechanism === 'SPLICE_CDS_DELETION' || Object.hasOwn(f, 'spliceAssessment')) {
        const s = f.spliceAssessment, strengths = ['SUPPORTING', 'MODERATE', 'STRONG', 'VERY_STRONG'];
        if (f.policySchema !== 'pvs1-truncating-nmd-policy-v4' || f.variantMechanism !== 'SPLICE_CDS_DELETION' ||
            s?.schemaVersion !== 'pvs1-splice-assessment-v1' || !['OBSERVED_RNA', 'PREDICTED_CANONICAL'].includes(s.evidenceKind) ||
            s.interpretation !== (s.evidenceKind === 'OBSERVED_RNA' ? 'SOURCE_REVIEWED_RNA_OUTCOME' : 'PREDICTED_OUTCOME_NOT_OBSERVED_RNA') ||
            s.doubleCountingConstraint !== 'DO_NOT_COMBINE_WITH_PP3_OR_PS3_FOR_THE_SAME_SPLICE_EFFECT' ||
            !strengths.includes(s.maximumStrength) || strengths.indexOf(criterion.appliedStrength) > strengths.indexOf(s.maximumStrength)) return false;
    }
    return true;
}

function bp3ProjectCriterionValid(criterion, expected) {
    if (criterion.criterionId !== 'ACMG_AMP_2015:BP3') return !Object.hasOwn(criterion.details || {}, 'bp3Facts');
    const f = criterion.details?.bp3Facts;
    if (!f) return criterion.status === 'NOT_EVALUATED';
    const proof = inspectProteinLengthProof(criterion, expected, { identityHash: ps2IdentityHash });
    if (!proof.valid && !proof.view?.availabilityOnly) return false;
    const verifiedScope = f.scope === 'PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE';
    if (verifiedScope && (f.policyVersion !== '1.1.0' || !proof.claimed &&
        (criterion.status !== 'NOT_EVALUATED' || criterion.appliedStrength !== 'NONE'))) return false;
    if (f.schemaVersion !== 'bp3-general-facts-v1' || !['1.0.0', '1.1.0'].includes(f.policyVersion) ||
        f.scope !== 'PROJECT_LOCAL_SIMPLE_INFRAME' && !verifiedScope ||
        f.clinicalValidationCompleted !== false || f.functionAbsenceInferredFromNoHit !== false ||
        !['UNKNOWN', 'EXCLUDED', 'QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION'].includes(f.regionState) ||
        !['KNOWN_REPEAT', 'NO_REPEAT_OBSERVED', 'INCOMPLETE'].includes(f.repeatObservation) ||
        !Number.isSafeInteger(f.functionalLeadCount) || f.functionalLeadCount < 0) return false;
    if (f.qualificationBasis === 'PUBLISHED_EXPERT_SPECIFICATION') {
        if (f.policyVersion !== '1.1.0' || f.functionalAnnotationSearchPerformed !== false) return false;
        if (criterion.status === 'NOT_EVALUATED') return f.regionState === 'UNKNOWN';
        const r = f.selectedRule;
        if (!proteinRangeValid(f) || f.knowledgeStatus !== 'READY' || !/^GN[0-9]+@[0-9]+(?:\.[0-9]+){1,2}$/.test(f.specificationKey || '') ||
            !/^[a-f0-9]{64}$/.test(f.clauseSha256 || '') || !versionedTranscript(f.declaredTranscript) ||
            f.executionTranscript !== f.transcript || r?.type !== 'PUBLISHED_BP3_EXPLICIT_REPEAT_REGION_V1' || r.version !== '1.0.0' ||
            r.strength !== 'SUPPORTING' || r.coordinateSystem !== 'REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE' ||
            r.supportedVariantScope !== 'SINGLE_SIMPLE_INFRAME_COMPLETE_EQUIVALENT_ENVELOPE' ||
            !positiveInteger(r.start) || !positiveInteger(r.end) || r.end < r.start ||
            !positiveInteger(r.modelRegionStart) || !positiveInteger(r.modelRegionEnd) ||
            r.modelRegionStart > r.start || r.modelRegionEnd < r.end || f.functionalLeadCount !== 0) return false;
        const covered = f.queryRange.start >= r.start && f.queryRange.end <= r.end;
        if (criterion.status === 'MET') return covered && criterion.appliedStrength === 'SUPPORTING' &&
            criterion.reasonCode === 'BP3_PUBLISHED_REPEAT_REGION_MATCHED' && f.regionState === 'QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION' &&
            f.repeatObservation === 'KNOWN_REPEAT';
        return criterion.status === 'NOT_MET' && !covered && criterion.appliedStrength === 'NONE' &&
            criterion.reasonCode === 'BP3_OUTSIDE_PUBLISHED_ELIGIBLE_REGION' && f.regionState === 'EXCLUDED';
    }
    if (criterion.status === 'MET') return proteinRangeValid(f) && criterion.appliedStrength === 'SUPPORTING' &&
        criterion.reasonCode === 'BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION' && f.regionState === 'QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION' &&
        f.repeatObservation !== 'INCOMPLETE' && f.functionalLeadCount === 0;
    if (criterion.status === 'NOT_MET') return proteinRangeValid(f) && criterion.appliedStrength === 'NONE' &&
        criterion.reasonCode === 'BP3_REVIEWED_NON_REPEAT_OR_KNOWN_FUNCTION' && f.regionState === 'EXCLUDED' && f.repeatObservation !== 'INCOMPLETE';
    return f.regionState === 'UNKNOWN';
}

export function pvs1SelectedDetails(criterion, context, profile) {
    const f = criterion?.details?.pvs1Facts;
    if (!f || !pvs1SelectedCriterionValid(criterion, context, profile)) return [];
    if (isPvs1CodingEscapeAssessment(f)) return pvs1CodingEscapeDetails(inspectPvs1CodingEscapeAssessment(criterion, context, { identityHash: ps2IdentityHash, profile }));
    if (isPvs1SpliceSummary(f)) return pvs1SpliceDetails(criterion);
    if (['1.5.0', '1.6.0'].includes(f.methodVersion)) {
        const a = f.automaticAssessment, s = a.sequence;
        return [...pvs1StartLossAutomaticDetails(a.preparation),
            ['PVS1起始丢失序列核对', `${s.selectedTranscript}；c.${s.cdsPosition} ${s.referenceCodon}→${s.alternateCodon}`],
            [a.decisionOutcome === 'NOT_APPLICABLE' ? '适用性结论' : '采用强度', a.decisionOutcome === 'NOT_APPLICABLE' ? '存在已核定的相关功能替代，本分支不适用' : a.decisionStrength],
            ['核查来源', `通用起始丢失建议 ${a.ruleSource}；机制资料 ${a.preparation.mechanismAssessment?.binding?.source?.version || '见完整记录'}；模型 SHA256 ${a.verifiedModelSha256}`],
            ['PVS1采用边界', '按已声明来源集合核对功能替代；不存在合格替代时，独立参考或完整范围阴性决定强度。未声称穷尽全部生物学转录本或互联网，不自动组合为最终诊断。']];
    }
    const automatic = pvs1StartLossAutomaticDetails(f.automaticPreparation);
    if (f.methodVersion === '1.4.0') {
        const e = f.expertAssessment, s = e.sequence;
        return [...automatic,
            ['PVS1起始丢失序列核对', `${s.selectedTranscript}；c.${s.cdsPosition} ${s.referenceCodon}→${s.alternateCodon}`],
            ['专家规范判定', '按精确适用的已发布起始密码子条款采用强度；条款声明的下游位点与蛋白身份已核对。'],
            ['采用强度', e.decisionStrength],
            ['核查来源', e.expertAssertions.entries.map(x => `${x.specificationKey}（SHA256 ${x.source.rawSha256}）`).join('；')],
            ['专家原文与定位', e.expertAssertions.entries.flatMap(x => x.startLossRule.claims.map(c =>
                `${c.matchedParagraph}（${x.source.jsonPointer}${c.sourceLocator}）`)).join('；')],
            ['PVS1采用边界', '本条执行专规明确的起始丢失强度，未推定通用树的所有条件均已核查；不与其他证据自动合并或给出最终临床分类。']];
    }
    if (f.methodVersion === '1.3.0') {
        const e = f.expertAssessment, s = e.sequence;
        return [...automatic,
            ['PVS1起始丢失序列核对', `${s.selectedTranscript}；c.${s.cdsPosition} ${s.referenceCodon}→${s.alternateCodon}`],
            ['专家规范判定', '精确匹配的已发布规范明确：存在替代起始，本分支不适用；不等于变异良性或排除疾病。'],
            ['核查来源', e.expertAssertions.entries.map(x => `${x.specificationKey}（SHA256 ${x.source.rawSha256}）`).join('；')],
            ['专家原文与定位', e.expertAssertions.entries.flatMap(x => x.claims.map(c =>
                `${c.sourceText}（${x.source.jsonPointer}${c.sourceLocator}）`)).join('；')],
            ['PVS1采用边界', '仅执行原件明确的起始丢失排除条款；不进行证据加权或最终致病分类。']];
    }
    if (f.methodVersion === '1.2.0') {
        const s = f.startLossAssessment;
        return [...automatic, ['PVS1起始丢失序列核对', `${s.status}；${s.selectedTranscript || '转录本未确认'}；c.${s.cdsPosition ?? '未定位'} ${s.referenceCodon || '未知'}→${s.alternateCodon || '未知'}`],
            ['候选下游起始位点', s.candidateRestartCdsPosition ? `c.${s.candidateRestartCdsPosition}；仅为潜在同框 ATG，不代表观察到翻译恢复` : '未发现可用候选或序列尚未解析'],
            ['判定状态', `${s.decisionOutcome || 'INSUFFICIENT_INFORMATION'}；${reasonHelp(s.decisionReasonCode || s.reviewReasonCode, 'PVS1_START_LOSS_V1')}`],
            ['替代转录本核查', s.transcriptReviewStatus === 'FOUND_FUNCTIONAL_ALTERNATIVE' ? '存在功能性替代转录本，本分支不适用。'
                : s.transcriptReviewComplete ? '在所选规则固定的已核查转录本集合内未发现功能性替代。' : '功能性替代转录本资料尚未完成核查。'],
            ['上游参考核查', s.upstreamSearchComplete ? `限定来源与区间核查完成，合格独立致病参考 ${s.qualifyingReferenceCount} 个。`
                : s.upstreamReferenceReviewStatus === 'NOT_REQUIRED' ? '已有明确不适用依据，无需继续核查上游参考。' : '来源或区间核查尚未完成，不能视为无参考。'],
            ...(s.decisionOutcome === 'MET' ? [['采用强度', s.decisionStrength]] : []),
            ...(Array.isArray(s.materialEvidence?.transcriptFindings) ? [['已核查转录本', s.materialEvidence.transcriptFindings.map(x =>
                `${x.transcript}（${x.transcript === s.selectedTranscript ? '本次所选' : x.functionallyRelevant ? '功能相关' : '来源核定不相关'}；${x.sourceId}${x.sourceLocator}）`).join('；')]] : []),
            ...(Array.isArray(s.materialEvidence?.referenceReviews) && s.materialEvidence.referenceReviews.length ? [['上游参考逐条核对', s.materialEvidence.referenceReviews.map(x => {
                const disposition = { QUALIFYING: '符合独立致病参考条件', DUPLICATE_EXCLUDED: '重复记录，不重复计入', OUTSIDE_UPSTREAM_INTERVAL: '位于区间外',
                    TARGET_EXCLUDED: '目标自身，排除', WITHDRAWN_EXCLUDED: '已撤回，排除', BASELINE_PATHOGENIC_REQUIRED: '不满足基线 Pathogenic 要求',
                    CONFLICT: '此参考存在冲突，隔离处理', UNRESOLVED: '此参考尚未解决，不能作为阴性依据' };
                return `${x.recordId || '来源记录'}：${x.cdsPosition ? `c.${x.cdsPosition}` : '位置尚未核对'}，${x.classification}，${disposition[x.disposition] || x.disposition}${x.reasonCode ? `；${reasonHelp(`PVS1_START_LOSS_RUNTIME_${x.reasonCode}`, 'PVS1_START_LOSS_V1')}` : ''}（${x.sourceId}${x.sourceLocator}）`;
            }).join('；')]] : []),
            ...(Array.isArray(s.sources) ? [['核查来源', s.sources.map(x => `${x.sourceId}：${x.url}（${x.sha256}）`).join('；')]] : []),
            ['PVS1采用边界', '本分支不使用 NMD 距离作为起始丢失判据；未完成临床验证，不与其他证据自动合并计权。']];
    }
    return [...automatic, ['PVS1机制与材料', `${f.selectedTranscript || '精确转录本尚未形成'}；${f.variantMechanism || '终止改变或尚未解析'}；知识状态 ${f.knowledgeStatus}（${f.knowledgeReason}）`],
        ['转录本后果', `${f.nmdPrediction || '尚不能预测NMD'}；终止位置 ${f.ptcStart ?? '未取得'}–${f.ptcEnd ?? '未取得'}；${f.transcriptTopology?.kind === 'SINGLE_EXON' ? '单外显子，无末次连接点，按非 NMD 依据判断。' : `距末连接点 ${f.distanceToLastJunction ?? '未取得'} 个碱基。`}`],
        ...(f.materialSupply ? [['自动序列与转录本供给', `${f.materialSupply.status}；${reasonHelp(f.materialSupply.reasonCode, 'PVS1_NMD_V6')}；原政策和转录本版本不变`]] : []),
        ...(f.sourceQualifiedResearch ? [['来源资格方式', '按共同规则核对原政策与来源，未新增专家审核或临床批准']] : []),
        ...(f.policyStrengthCap ? [['政策证据强度上限', f.policyStrengthCap]] : []),
        ...(f.transcriptTopology?.kind === 'LAST_JUNCTION_IN_3_PRIME_UTR' ? [['转录本结构分支', `末次接头位于编码区末端或 3′UTR；${f.transcriptTopology.utrApplicabilityBound ? '结构适用依据已绑定' : '结构适用依据仍缺失'}`]] : []),
        ...(f.spliceAssessment ? [['剪接后果来源', f.spliceAssessment.evidenceKind === 'OBSERVED_RNA' ? '原RNA来源经当前政策核定；不代表本系统重新完成实验。' : '来源绑定的预测剪接后果，不是实际RNA实验。']] : []),
        ...(f.escapeAssessment ? [['NMD逃逸核定', `未预测发生NMD；另按受审逃逸政策核对，状态 ${f.escapeAssessment.status}。`]] : []),
        ['PVS1采用边界', '需适用的LoF机制与转录本/区域知识；预测不是实验，未完成临床验证。同一效应不与PP3、PM4等自动重复计权，不生成最终分类。']];
}

export function pvs1FunctionalApplicabilityDetails(a) {
    if (!a || a.schemaVersion !== 'pvs1-start-loss-functional-applicability-v1' ||
        a.functionalQualificationGranted !== false || a.negativeFunctionalCoverageGranted !== false) return [];
    if (a.status === 'UNAVAILABLE') return [['候选功能资料核对失败', '未能完成实验与候选转录本的对应核查，本次不据此形成结论。']];
    if (!Array.isArray(a.candidates) || a.candidates.length > 30) return [];
    const names = {
        IDENTITY_NOT_ESTABLISHED: '缺少实验异构体与精确转录本的可靠对应',
        IDENTITY_MENTION_ONLY_FUNCTION_UNRESOLVED: '已找到精确版本提及，相关功能仍未核定',
        OBSERVATION_PROVENANCE_INVALID: '存在原文定位核验失败的观察，未采用',
        NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT: '已读资料未建立该精确转录本的对应，异构体名称尚不能直接换成转录本编号',
        EXACT_VERSION_MENTION_NOT_FUNCTION: '提及精确版本；不等于已经证明该转录本具有相关功能',
        UNVERSIONED_ACCESSION_REQUIRES_BINDING: '只提及无版本编号，需核对所用序列或版本',
        TRANSCRIPT_VERSION_MISMATCH: '资料中的转录本版本与候选不符，尚未核实是否等价',
        AMBIGUOUS_EXPERIMENT_SUBJECT: '同一实验句子涉及多个转录本，尚不能确定实验对象',
        TRANSCRIPT_MENTION_ONLY_IN_CONTEXT: '转录本只在上下文提及，实验句子尚未与其对应',
        PRIMARY_EXPERIMENT_NOT_ESTABLISHED: '该观察来自背景或引用，尚未核定原始实验',
        FRAGMENT_ASSAY_NOT_WHOLE_ISOFORM: '蛋白片段实验不足以说明完整转录本的相关功能',
        INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION: '实验包含删除或过表达干预，需核查剂量和实验范围的适用性',
        PARTIAL_ENDPOINT_NOT_WHOLE_FUNCTION: '部分实验指标恢复，尚不足以核定相关替代功能',
        ABUNDANCE_NOT_FUNCTIONAL_RESCUE: '蛋白丰度变化不能单独证明相关功能',
        EXPERIMENT_SEMANTICS_UNRESOLVED: '该实验结果的科学含义仍待核定',
    };
    const rows = [];
    if (a.unassessedUnresolvedRowCount > 0) rows.push(['尚不能对应的结构候选',
        `${a.unassessedUnresolvedRowCount} 条未解决记录缺少可靠起始结构或精确转录本身份，尚未进入实验对应核查；不代表不存在功能替代。`]);
    for (const candidate of a.candidates) {
        if (!/^(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*$/.test(candidate?.transcript || '') ||
            candidate.functionalQualificationGranted !== false || candidate.negativeFunctionalCoverageGranted !== false ||
            !Array.isArray(candidate.documents) || candidate.documents.length > 8) continue;
        rows.push([`${candidate.transcript} 功能资料缺项`, names[candidate.status] || '候选实验适用性仍待核定']);
        for (const doc of candidate.documents) {
            if (!/^PMC[1-9][0-9]*$/.test(doc?.pmcid || '') || !Array.isArray(doc.observations) || doc.observations.length > 10) continue;
            const uses = doc.observations.map(x => names[x.identityRelation] && x.identityRelation !== 'EXACT_VERSION_MENTION_NOT_FUNCTION' ?
                names[x.identityRelation] : names[x.useDisposition]).filter(Boolean);
            rows.push([`${candidate.transcript} · ${doc.pmcid}`, [names[doc.identityRelation], ...new Set(uses),
                doc.invalidObservationCount > 0 ? '原文引用或位置核验失败，未采用相应观察' : '',
                doc.observationDisplayTruncated ? '此处仅展示部分观察，完整记录随结果保存' : ''].filter(Boolean).join('；')]);
        }
    }
    if (a.displayTruncated) rows.push(['候选功能资料展示范围', `共 ${a.candidateCount} 条候选，此处最多展示30条；完整记录随结果保存。`]);
    return rows;
}

export function pvs1FunctionalLiteratureDetails(f) {
    if (!f || f.schemaVersion !== 'pvs1-start-loss-functional-summary-v1' ||
        f.runtimeEligible !== false || f.functionalRescueEstablished !== false || f.negativeFunctionalCoverageGranted !== false) return [];
    const failures = {
        FUNCTIONAL_LITERATURE_READER_UNAVAILABLE: '已登记文献或本地阅读配置不可用',
        FUNCTIONAL_LITERATURE_READING_TIMEOUT: '本地文献阅读超时，仍有资料未读取',
        FUNCTIONAL_LITERATURE_SOURCE_CHANGED: '阅读期间来源发生变化，需要重新核对',
        FUNCTIONAL_LITERATURE_READING_FAILED: '本地文献阅读未成功完成',
    };
    if (['UNAVAILABLE', 'DISABLED'].includes(f.status)) return [['替代功能文献读取', f.status === 'DISABLED' ?
        '文献工作流未启用，本次未读取' : failures[f.reasonCode] || '文献读取或原件核验未通过；不能据此判为没有功能替代']];
    if (!['READ_WITH_LIMITS', 'NO_USABLE_READING'].includes(f.status) || !Array.isArray(f.documents) || f.documents.length > 8) return [];
    const kinds = {
        FRAGMENT_CONSTRUCT_REPORTED: '蛋白片段实验：不能代表完整转录本的功能',
        EXON_DELETION_INTERVENTION_REPORTED: '外显子删除干预：不能直接代表天然表达条件',
        OVEREXPRESSION_INTERVENTION_REPORTED: '过表达干预：不能直接代表天然表达条件',
        PARTIAL_READOUT_RESCUE_REPORTED: '部分实验指标恢复：不能等同完整功能恢复',
        PROTEIN_ABUNDANCE_INCREASE_REPORTED: '蛋白量增加：不能单独证明功能替代',
    };
    const requirements = {
        EXACT_ISOFORM_TO_TRANSCRIPT_BINDING: '实验中的异构体与当前精确版本转录本对应',
        DISEASE_RELEVANT_FUNCTION_AND_CONTEXT: '实验功能、组织及疾病机制适用性',
        CONTROLS_AND_REPLICATION: '对照设计与重复实验的可靠性',
        ENDOGENOUS_COMPENSATION_APPLICABILITY: '天然表达条件下的功能补偿能力',
        EXPERIMENT_SCOPE_AND_DOSAGE_APPLICABILITY: '实验剂量、干预及功能结论的适用范围',
        CONTRADICTORY_AND_UNREAD_MATERIAL_REVIEW: '矛盾结果、未读段落及补充材料核对',
    };
    const rows = [['替代功能文献范围', `已读取 ${f.documents.length} 篇登记文献；另有 ${Number.isInteger(f.pendingDocumentCount) ? f.pendingDocumentCount : '未知'} 篇未形成可用阅读结果。当前范围不代表完整文献检索。`]];
    for (const doc of f.documents) {
        if (!/^PMC[1-9][0-9]*$/.test(doc?.pmcid || '') || !Array.isArray(doc.observations) || doc.observations.length > 20) continue;
        rows.push([`${doc.pmcid} 已有资料`, `已读 ${doc.reviewedUnitCount} 个相关段落，${doc.pendingUnitCount} 个待读；定位到 ${doc.observationCount} 条实验观察，以下最多展示20条。`]);
        if (doc.supplementCoverage?.status === 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED') rows.push([`${doc.pmcid} 补充材料缺项`,
            doc.supplementCoverage.reasonCode === 'PAPER_DOWNLOAD_TIMEOUT' ? '补充材料获取超时，不能认为已核查全文及全部实验细节。' :
                '补充材料尚不可用或未能解析，不能认为已核查全文及全部实验细节。']);
        for (const observation of doc.observations) {
            if (!kinds[observation?.kind] || typeof observation.sourceQuote !== 'string' || observation.sourceQuote.length > 600) continue;
            rows.push([`${doc.pmcid} 实验观察`, `${kinds[observation.kind]}。${observation.sourceRole === 'CITED_OR_BACKGROUND' ? '此处是背景或引用，尚非原始实验资格。' : '仍需核查实验对象与适用范围。'} 原文：${observation.sourceQuote}${observation.quoteTruncated ? '…（完整原文见保存资料）' : ''}`]);
        }
        if (Array.isArray(doc.remainingRequirements)) rows.push([`${doc.pmcid} 仍需核查`, doc.remainingRequirements.map(r => requirements[r]).filter(Boolean).join('；')]);
    }
    rows.push(...pvs1FunctionalApplicabilityDetails(f.candidateApplicability));
    rows.push(['功能文献与判定的差距', '当前取得的是有限实验观察及其适用限制，尚未核定相关替代功能，不单独赋予PVS1强度。完整段落、出处、未读项目随结果保存。']);
    return rows;
}

export function pvs1StartLossAutomaticDetails(p) {
    if (!p || p.schemaVersion !== 'pvs1-start-loss-automatic-preparation-v1' || !['1.0.0', '1.1.0', '1.2.0'].includes(p.methodVersion) ||
        p.runtimeEligible !== false || p.scientificEvaluationPerformed !== false ||
        !['INCOMPLETE', 'UNAVAILABLE', 'UNRESOLVED', 'CONFLICT', 'UNSUPPORTED', 'NOT_REQUIRED', 'EXPERT_TRANSCRIPT_FACTS_QUALIFIED', 'PUBLISHED_RULE_FACTS_QUALIFIED', 'GENERIC_FACTS_QUALIFIED'].includes(p.status) ||
        !Array.isArray(p.issues) || !p.issues.every(localBatchText)) return [];
    const t = p.transcriptReview, u = p.upstreamReview;
    if (t?.findings !== undefined && (!Array.isArray(t.findings) || t.findings.length > 100 ||
        !t.findings.every(x => x && localBatchText(x.transcript) && localBatchText(x.reasonCode)))) return [];
    if (u && (!localBatchText(u.reasonCode) || u.records !== undefined && (!Array.isArray(u.records) || u.records.length > 100 ||
        !u.records.every(x => x && localBatchText(x.reasonCode))))) return [];
    const names = {
        SELECTED_TRANSCRIPT: '本次所选转录本', SAME_START_CODON: '使用相同起始密码子',
        FUNCTION_AND_TARGET_EFFECT_REQUIRE_SOURCE: '使用不同起始位点，功能及变异影响仍需原始依据',
        ALTERNATIVE_STRUCTURE_UNRESOLVED: '替代转录本结构不能可靠映射',
        ANNOTATED_NONCODING_TRANSCRIPT: '来源标注为非编码转录本，不作为蛋白替代',
        TRANSCRIPT_IDENTITY_UNRESOLVED: '转录本身份尚不能核对',
        REFSEQ_SEQUENCE_NOT_VERIFIED: 'RefSeq结构已取得，序列一致性尚未核实，不据此排除功能替代',
        REFSEQ_EXACT_SEQUENCE_UNAVAILABLE: '尚未取得该精确版本的RefSeq RNA序列',
        REFSEQ_SEQUENCE_LENGTH_OR_BASES_INVALID: 'RNA长度或碱基内容不满足核验要求',
        REFSEQ_RNA_REFERENCE_SEQUENCE_MISMATCH: 'RefSeq RNA与本地参考的逐外显子重建不一致',
        REFSEQ_REFERENCE_CHANGED: '核验过程中参考来源身份发生变化',
        REFSEQ_REFERENCE_IDENTITY_MISMATCH: '参考来源与当前分析绑定不一致',
        REFSEQ_REFERENCE_READ_BUDGET_EXHAUSTED: '本基因序列核验达到读取上限，保留未完成状态',
        REFSEQ_CDS_START_UNSUPPORTED: '来源编码序列的起始条件超出当前支持范围',
        REFSEQ_CDS_STOP_UNSUPPORTED: '来源编码序列存在未支持的终止结构',
        REFSEQ_SEQUENCE_VERIFICATION_UNAVAILABLE: 'RNA与参考序列核验未完成',
        PVS1_START_LOSS_NO_DIFFERENT_START_IN_SOURCE_SET: '完整已核查来源集合中没有使用不同起始位点的候选；不代表穷尽全部生物学转录本',
        TRANSCRIPT_SOURCE_QUALIFICATION_REQUIRED: '来源清单和结构已汇总，仍须完成序列与功能资格核查',
        EXPERT_ALTERNATIVE_START_EXCLUSION: '适用专家原件明确记载替代起始，不需要继续上游检索',
        EXPERT_NO_KNOWN_ALTERNATIVE_START: '适用专家原件记载其他转录本中没有已知替代起始',
        EXPERT_TRANSCRIPT_ASSERTIONS_CONFLICT: '适用专家来源的替代起始结论存在冲突',
        EXPERT_EXACT_TRANSCRIPT_SCOPE_UNRESOLVED: '当前转录本与专家资料的精确版本范围不一致',
        EXPERT_START_LOSS_ASSERTION_NOT_IMPLEMENTED: '专家条款尚不能由当前起始丢失解析器执行',
        EXPERT_CONDITIONAL_ASSERTION_NOT_IMPLEMENTED: '专家原文包含限定或例外，当前解析器尚不能完整执行',
        EXPERT_SOURCE_COVERAGE_UNRESOLVED: '仍有适用专家来源未解决，不能据此确认没有功能替代',
        EXPERT_BACKGROUND_SCOPE_UNRESOLVED: '专家条款的疾病或遗传方式范围尚未匹配',
        EXPERT_ORIGINAL_UNAVAILABLE: '专家条款原件不可用',
        BACKGROUND_MISMATCH: '疾病或遗传方式不匹配', GENE_MISMATCH: '基因不匹配',
        BACKGROUND_UNRESOLVED: '来源疾病或遗传方式尚不能确定，不能按无关记录排除',
        EXACT_TRANSCRIPT_MAPPING_UNRESOLVED: '缺少精确版本的转录本映射', REFERENCE_MAPPING_INVALID: '参考映射无效',
        REFERENCE_SEQUENCE_CONFLICT: '参考碱基与CDS冲突', OUTSIDE_UPSTREAM_INTERVAL: '位于待查区间之外',
        TARGET_EXCLUDED: '目标变异自身，已排除', WITHDRAWN_EXCLUDED: '记录已撤回，已排除',
        RETRACTION_STATUS_UNRESOLVED: '撤回状态未知', REFERENCE_CLASSIFICATION_UNRESOLVED: '来源分类尚不能核定', BASELINE_PATHOGENIC_REQUIRED: '不是明确Pathogenic参考',
        PUBLICATION_STATUS_UNRESOLVED: '发表或审核日期不完整', ORIGINAL_INTERPRETATION_UNAVAILABLE_OR_MISMATCH: '原始判定无法取得或与摘要不一致',
        ORIGINAL_CRITERIA_UNRESOLVED: '原始证据结构尚不能解析', START_LOSS_DEPENDENT_CRITERION_PRESENT: '原判定使用了PVS1或PM4，不能直接认定独立',
        ORIGINAL_COMBINATION_RULE_UNSPECIFIED: '原件未明确给出采用的组合规则，不能默认套用通用规则重算',
        ORIGINAL_COMBINATION_RULE_NOT_IMPLEMENTED: '原件标明的组合规则尚未实现重放',
        ACTUAL_APPLIED_STRENGTH_UNRESOLVED: '实际采用强度缺失，不能用默认强度替代',
        ORIGINAL_GUIDELINE_SOURCE_OR_SCOPE_UNRESOLVED: '尚未取得与来源判定版本和背景一致的原始规范',
        ORIGINAL_GUIDELINE_BINDING_MISMATCH: '来源判定与原始规范版本不匹配',
        SOURCE_APPLIED_CODES_CONFLICT: '摘要中的实际证据与原始判定条目不一致',
        SOURCE_APPLIED_STRENGTH_CONFLICT: '摘要与原始判定记载的实际强度不一致',
        ORIGINAL_CLASSIFICATION_REPLAY_MISMATCH: '按原规则重算的分类与来源分类不一致',
        ORIGINAL_CLASSIFICATION_REPLAYED_INDEPENDENCE_NOT_ESTABLISHED: '已按对应版本的原始规范重现来源分类，独立性仍待核查',
        INDEPENDENT_CURATED_PATHOGENIC_COMBINATION: '专家原件中可解释的独立依据按原规则仍支持致病；参考身份与跨来源冲突需另行核查',
        INDEPENDENT_CURATED_GROUNDS_NOT_SUFFICIENT_FOR_PATHOGENIC: '当前可解释的独立依据不足以按原规则重现致病分类',
        ORIGINAL_CLASSIFICATION_REPLAY_REQUIRED: '原分类尚未成功按对应原规则重算',
        ORIGINAL_OBSERVATIONS_MISSING: '缺少支持原判定的原始观察',
        ORIGINAL_OBSERVATION_REFERENCE_UNRESOLVED: '原始观察引用尚未取得，不能按空记录处理',
        ORIGINAL_OBSERVATION_ID_CONFLICT: '同一观察编号对应不同内容，需核查来源',
        ORIGINAL_OBSERVATION_CYCLE: '原始证据链存在循环引用',
        ORIGINAL_OBSERVATION_SHAPE_INVALID: '原始观察结构无法解析',
        ORIGINAL_OBSERVATION_FACTS_UNRESOLVED: '观察记录尚未提供可解释的事实位置',
        ORIGINAL_GRAPH_SIZE_LIMIT: '原始证据链超出有界处理范围，核查未完成',
        ORIGINAL_CRITERIA_MISSING: '原件没有可解析的证据条目',
        OBSERVATION_SCIENTIFIC_SEMANTICS_UNVERIFIED: '原始观察的科学含义和适用性尚未核验',
        SHARED_OBSERVATIONS_REQUIRE_DEPENDENCY_REVIEW: '多条证据使用相同观察，需核查依赖以避免重复计数',
        START_LOSS_DEPENDENCY_REVIEW_REQUIRED: '原判定含PVS1或PM4，须核查其实际推理依据；该标签本身不代表循环论证',
        CLASSIFICATION_INDEPENDENCE_REQUIRES_SCIENTIFIC_FACTS: '已读原始证据条目，仍需核实判定独立性', REFERENCE_CLASSIFICATION_CONFLICT: '同一变异的来源分类存在冲突',
        PVS1_START_LOSS_AUTO_FUNCTIONAL_TRANSCRIPT_COVERAGE_UNRESOLVED: '功能性转录本集合尚未核实完整',
        PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE: '已检索来源不能证明上游参考覆盖完整',
        PVS1_START_LOSS_INDEPENDENT_REFERENCE_VALIDATED: '已核实至少一条上游独立致病参考；该存在性结论不表示全部来源已查完，也不单独授予PVS1',
        CLINVAR_EREPO_REFERENCE_CONSISTENT: 'ClinVar逐提交记录与ERepo专家原件对应一致；同一专家判定不重复计数',
        CLINVAR_REFERENCE_CURRENT_ASSERTION_CONFLICT: '同一背景存在相反或不确定的当前提交，参考暂不采用',
        CLINVAR_REFERENCE_POTENTIAL_CONFLICT_UNRESOLVED: '存在背景尚未确定的潜在相反提交，需进一步核查',
        CLINVAR_EREPO_SUBMISSION_MISMATCH: 'ClinVar专家提交与ERepo的日期、背景或原文不一致',
        CLINVAR_LINKED_EXPERT_ASSERTION_UNRESOLVED: '尚未核对到唯一对应的ClinVar专家提交',
        CLINVAR_EREPO_CODING_ALLELE_MISMATCH: 'ClinVar与ERepo未能映射到同一编码等位基因',
        CLINVAR_EQUIVALENT_SECONDARY_RECORD_REQUIRES_REVIEW: '发现同一等位基因的另一个ClinVar编号，仍需核查其提交',
        CLINVAR_LOCAL_ALLELE_ID_CONFLICT: '本地ClinVar编号与对应等位基因发生冲突',
        CLINVAR_LOCAL_EQUIVALENCE_RECHECK_REQUIRED: '本次尚未完成本地等价记录复查，不能沿用先前的参考资格',
        CLINVAR_POTENTIALLY_EQUIVALENT_ALLELE_UNRESOLVED: '可能等价的本地变异尚不能完成映射，参考资格未确认',
        CLINVAR_NO_CURRENT_PATHOGENIC_ASSERTION_IN_SCOPE: '逐提交核查后没有当前背景下的明确致病断言；不代表变异已判为良性',
        CLINVAR_CANDIDATE_QUALIFICATION_REQUIRED: '仍有致病线索或未明确的提交，需要继续核定独立依据',
        CLINVAR_INTERVAL_CANDIDATES_RESOLVED: '当前ClinVar区间候选已逐条说明排除理由',
        CLINVAR_WITNESS_VALIDATED_NEGATIVE_ENUMERATION_NOT_REQUIRED: '已找到合格参考并完成其必要冲突核查，无需再为证明“没有参考”逐条获取其他候选提交',
        CLINVAR_REFERENCE_QUALIFICATION_REQUIRED: '仍有ClinVar候选未解决，不能据此认定没有合格参考',
        CLINVAR_LOCAL_SOURCE_ALLELE_MISMATCH: '本地区间候选与当前ClinVar原件的等位基因不一致',
        REFERENCE_CLASSIFICATION_CONFLICT_OR_UNRESOLVED: '同一等位基因仍存在冲突或分类未知的来源记录',
        PVS1_START_LOSS_NO_REFERENCE_IN_DECLARED_SOURCES: '已完成规定的ClinVar区间与ERepo来源核查，范围内未发现合格参考；不表示所有文献均无记录，也不单独授予PVS1',
        PVS1_START_LOSS_AUTO_TRANSCRIPT_INVENTORY_UNAVAILABLE: '转录本清单来源不可用',
        PVS1_START_LOSS_AUTO_PUBLIC_NETWORK_DISABLED: '公网开关关闭', PVS1_START_LOSS_AUTO_ACQUISITION_BUDGET_EXHAUSTED: '本批次资料获取已达时间或请求上限',
        PVS1_START_LOSS_AUTO_SOURCE_UNAVAILABLE_OR_INVALID: '来源请求失败或原件格式无法核验',
        PVS1_START_LOSS_POLICY_UNRESOLVED: '尚未匹配可执行的起始丢失规则',
        PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE: '已核对明确适用的专家起始丢失条款及其声明条件',
        PUBLISHED_RULE_SEPARATE_GENERIC_TRANSCRIPT_REVIEW_NOT_REQUIRED: '本次执行专规明确的起始丢失条款，无需额外重复通用树的转录本判定；不表示已完成该通用核查',
        PUBLISHED_RULE_SEPARATE_GENERIC_UPSTREAM_REVIEW_NOT_REQUIRED: '本次强度由明确适用的专规规定，无需额外重复通用树的上游参考检索',
        PVS1_START_LOSS_PUBLISHED_CONDITIONS_UNSUPPORTED: '适用的起始丢失专规尚有未实现条件，不能改用默认强度',
        PVS1_START_LOSS_PUBLISHED_SEQUENCE_CONDITION_MISMATCH: '实际序列或蛋白身份与专规声明的下游起始条件不一致',
        PVS1_START_LOSS_PUBLISHED_STRENGTH_CONFLICT: '适用条款记载的起始丢失强度不一致',
        PVS1_START_LOSS_PUBLISHED_RULE_CONFLICT: '起始丢失专规的许可、排除或序列条件存在冲突'
    };
    const explain = code => names[code] || reasonHelp(code, 'PVS1_START_LOSS_V1');
    const rows = [['起始丢失自动资料准备', '自动核对结构和原始判定；资料不足时保留缺项，不由分类标签授予证据强度。']];
    const sourceReview = t?.sourceQualifiedAssessment;
    if (sourceReview?.schemaVersion === 'pvs1-start-loss-source-qualified-transcripts-v1' &&
        sourceReview.contextSha256 === p.contextSha256 && Array.isArray(sourceReview.sourceBindings) && sourceReview.sourceBindings.length <= 32) {
        const statuses = { FOUND_FUNCTIONAL_ALTERNATIVE: '已有来源核定的相关功能替代；本分支不适用',
            NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET: '所声明且完成核查的来源范围内没有合格功能替代', CONFLICT: '相关来源的功能转录本结论存在冲突，未采用' };
        if (statuses[sourceReview.status]) rows.push(['功能转录本来源核定', statuses[sourceReview.status]]);
        rows.push(['功能转录本原件出处', sourceReview.sourceBindings.filter(b => localBatchText(b?.role) && localBatchText(b.sourceLocator) &&
            /^[a-f0-9]{64}$/.test(b.sourceSha256 || '')).map(b => `${b.role}：${b.url || '见完整资料'}；${b.sourceLocator}；SHA256 ${b.sourceSha256}`).join('\n')]);
    }
    const mechanism = p.mechanismAssessment;
    if (localBatchText(mechanism?.reasonCode)) {
        const mechanismReasons = {
            PVS1_START_LOSS_CURATED_LOF_MECHANISM: '原始G2P记录明确整理了有证据支持的功能丧失机制，并包含相关空功能变异谱',
            PVS1_START_LOSS_CLINGEN_DISEASE_HAPLOINSUFFICIENCY: '同一基因、同一疾病及常染色体显性背景的权威剂量审查和强关联资料共同支持单倍剂量不足机制',
            PVS1_START_LOSS_MECHANISM_SOURCE_NOT_FOUND: '没有匹配疾病及遗传方式的机制记录',
            PVS1_START_LOSS_MECHANISM_RECORDS_UNRESOLVED: '机制记录仍有待核定条件',
            PVS1_START_LOSS_MECHANISM_CONFLICT: '同一疾病背景的机制记录存在相反结论',
            PVS1_START_LOSS_MECHANISM_CONTRARY_OR_UNKNOWN: '来源记载相反机制或机制未知',
            PVS1_START_LOSS_MECHANISM_INHERITANCE_UNRESOLVED: '遗传方式尚不能精确对应来源',
            PVS1_START_LOSS_MECHANISM_IDENTITY_UNRESOLVED: '基因或疾病身份不一致',
            PVS1_START_LOSS_MECHANISM_ASSOCIATION_INSUFFICIENT: '基因与疾病关联证据尚不充分',
            PVS1_START_LOSS_MECHANISM_INFERRED_ONLY: '来源仅标记推断机制，尚未取得此路径所需的机制依据',
            PVS1_START_LOSS_MECHANISM_QUALIFIERS_UNSUPPORTED: '原件有附加限定，需要进一步解释',
            PVS1_START_LOSS_NULL_MECHANISM_SCOPE_UNRESOLVED: '尚不能确定蛋白产物缺失和截断变异属于该疾病机制',
            PVS1_START_LOSS_MECHANISM_EVIDENCE_REFERENCES_UNRESOLVED: '机制依据与已整理的文献列表尚不能对应',
            PVS1_START_LOSS_MECHANISM_SOURCE_DATE_UNRESOLVED: '缺少可核对的来源审核日期'
        };
        const explainMechanism = code => mechanismReasons[code] || explain(code);
        rows.push(['起始丢失机制核查', explainMechanism(mechanism.reasonCode)]);
        if (Array.isArray(mechanism.findings)) rows.push([[pvs1ClinGenBasis, pvs1ClinGenG2pBasis].includes(mechanism.basis) ? 'G2P路径逐条核查' : '机制来源逐条核查', mechanism.findings.filter(x => localBatchText(x?.reasonCode))
            .map(x => `${x.recordId || '来源记录'}：${explainMechanism(x.reasonCode)}`).join('；') || '没有精确匹配的来源记录']);
        if ([pvs1ClinGenBasis, pvs1ClinGenG2pBasis].includes(mechanism.basis) && pvs1StartLossMechanismValid(mechanism, p.contextSha256)) {
            const c = mechanism.clingenAssessment, b = c.binding;
            const association = c.associationSource === 'G2P' ? 'G2P同一疾病的强或确定关联' : `ClinGen疾病关联${c.validityClassification === 'Definitive' ? '确定' : '强'}`;
            rows.push(['ClinGen疾病机制依据', `${b.geneSymbol}（${b.hgncId}）与${c.diseaseLabel}（${b.mondoId}）；常染色体显性；单倍剂量不足评分3；${association}；剂量复核日期 ${c.evaluationDate}。`]);
            rows.push(['ClinGen机制原件出处', b.sources.map(source => `${source.sourceUrl}；原记录第${source.sourceRow}行；资料日期 ${source.fileCreatedDate}；SHA256 ${source.sha256}`).join('\n')]);
            if (c.associationSource === 'G2P') {
                const a = c.associationAnchor;
                rows.push(['G2P疾病关联出处', `${a.binding.source.url}；${a.binding.source.version}；原CSV SHA256 ${a.binding.source.sha256}\n` +
                    a.records.map(row => `${row.recordId}，原记录第${row.sourceRow}行；关联${row.sourceRecord.confidence}；记录 SHA256 ${row.sourceRecordSha256}`).join('\n')]);
                rows.push(['ClinGen关联资料核查', `${c.clinGenValidityCheck.status === 'NO_EXACT_DISEASE_ASSOCIATION' ? '本次ClinGen关联清单没有同一疾病记录，关联采用独立G2P原件' : 'ClinGen关联记录尚未达到强级，关联采用独立G2P原件'}；${c.clinGenValidityCheck.sourceUrl}；SHA256 ${c.clinGenValidityCheck.sha256}。`]);
            }
            rows.push(['G2P原始记录保留', 'G2P路径的原结论与缺项仍保留，本次机制由独立且疾病背景一致的ClinGen资料建立；没有将inferred改写为evidence。']);
        }
        const c = mechanism.clingenAssessment;
        if (c?.status === 'UNRESOLVED' && localBatchText(c.reasonCode)) {
            const reasons = {
                AD_CONTEXT_REQUIRED: '此剂量路径仅适用于常染色体显性背景', ASSEMBLY_UNSUPPORTED: '尚未取得当前参考组装对应的剂量资料',
                ORIGINAL_UNAVAILABLE: '来源原件尚不可用', ORIGINAL_PIN_MISMATCH: '来源内容与保存的校验值不一致',
                UNIQUE_DOSAGE_GENE_REQUIRED: '尚未取得唯一对应基因的剂量记录', UNIQUE_EXACT_DISEASE_VALIDITY_REQUIRED: '尚未取得同一疾病及遗传方式的唯一关联记录',
                UNIQUE_EXACT_DISEASE_HI_REQUIRED: '剂量记录尚不能直接对应同一疾病', HI3_REQUIRED: '来源未达到单倍剂量不足评分3',
                STRONG_VALIDITY_REQUIRED: '来源疾病关联尚未达到强或确定', DOSAGE_SNAPSHOTS_DISAGREE: '两个剂量来源的复核日期或结论不一致',
                STRONG_ASSOCIATION_REQUIRED: '尚未取得同一疾病及遗传方式的强或确定关联依据', CONTRARY_VALIDITY_PRESENT: '同一疾病的关联资料存在争议或反对结论，不能忽略',
                VALIDITY_SCOPE_CONFLICT: '关联资料的遗传方式或适用范围存在冲突', ASSOCIATION_CONTEXT_MISMATCH: '关联资料未绑定当前分析背景',
                GENE_IDENTITY_MISMATCH: '来源基因身份不一致', CONTEXT_MISMATCH: '资料未绑定当前分析背景'
            };
            rows.push(['ClinGen机制补充核查', reasons[c.reasonCode.replace(/^PVS1_CLINGEN_MECHANISM_/, '')] || '来源格式、身份或日期尚未完成核实；具体缺项保留在完整结果中。']);
        }
        rows.push(['机制核查边界', '此处核对原数据库整理的疾病机制及空功能变异谱，或同一疾病的权威剂量与关联资料；不是重新评价实验，也不单独证明转录本相关性或授予PVS1。']);
    }
    if (Array.isArray(t?.findings)) rows.push(['自动转录本核查', t.findings.map(x => `${x.transcript}：${explain(x.reasonCode)}`).join('；') || '未取得可核查转录本']);
    if (t?.qualificationBasis === 'EXPLICIT_SCOPED_EXPERT_ASSERTION' && localBatchText(t.reasonCode))
        rows.push(['专家转录本依据', explain(t.reasonCode)]);
    if (Array.isArray(p.expertAssertions?.entries)) {
        const entries = p.expertAssertions.entries.filter(x => x && localBatchText(x.specificationKey) && localBatchText(x.reasonCode));
        if (entries.length) rows.push(['专家资料范围核查', entries.map(x => `${x.specificationKey}：${explain(x.startLossRule?.status === 'QUALIFIED' ? x.startLossRule.reasonCode : x.reasonCode)}`).join('；')]);
        const joined = entries.filter(x => x.inheritanceScopeJoin?.status === 'QUALIFIED');
        if (joined.length) rows.push(['遗传方式出处', joined.map(x => `${x.specificationKey}：原规范未填写遗传方式，以固定的同一基因—疾病资料补齐为 ${x.inheritanceScopeJoin.resolvedMode}；原规范内容保持不变，不代表单独确认了 LoF 机制。`).join('；')]);
    }
    if (p.expertAssertions?.transcriptBinding?.method === 'EXACT_FULL_TRANSCRIPT_EQUIVALENCE') {
        const b = p.expertAssertions.transcriptBinding;
        rows.push(['专家转录本对应', `${b.selectedTranscript?.accession}.${b.selectedTranscript?.version} → ${b.sourceTranscript?.accession}.${b.sourceTranscript?.version}；依据完整 RNA、外显子与 CDS 一致性证明，保留原分析转录本。`]);
    }
    if (u) rows.push(['自动上游检索', `${u.cdsEnd ? `CDS 1–${u.cdsEnd}；` : ''}${explain(u.reasonCode)}${u.source?.sourceUrl ? `；来源：${u.source.sourceUrl}` : ''}`]);
    if (Array.isArray(u?.records) && u.records.length) rows.push(['参考记录处理', u.records.map(x => `${x.recordId || '来源记录'}${x.cdsPosition ? `，c.${x.cdsPosition}` : ''}：${explain(x.reasonCode)}`).join('；')]);
    if (Array.isArray(u?.records)) {
        const intronic = u.records.filter(x => x?.disposition === 'EXCLUDED' && x?.reasonCode === 'OUTSIDE_UPSTREAM_INTERVAL' &&
            ['EXACT_TRANSCRIPT_INTRON_SNV_GEOMETRY_V1', 'EXACT_TRANSCRIPT_INTRON_DELETION_GEOMETRY_V1'].includes(x?.scopeExclusion?.method) &&
            Number.isSafeInteger(x.scopeExclusion.genomicPosition) && x.scopeExclusion.genomicPosition > 0 &&
            localBatchText(x.scopeExclusion.transcript) &&
            (x.scopeExclusion.method === 'EXACT_TRANSCRIPT_INTRON_SNV_GEOMETRY_V1' ||
                (Number.isSafeInteger(x.scopeExclusion.genomicEnd) && x.scopeExclusion.genomicEnd >= x.scopeExclusion.genomicPosition &&
                    x.scopeExclusion.genomicDescriptionConfirmed === true)));
        if (intronic.length) rows.push(['区间外内含子记录', intronic.map(x => `${x.recordId || '来源记录'}：${x.scopeExclusion.transcript}，基因组位置 ${x.scopeExclusion.genomicPosition}${x.scopeExclusion.genomicEnd ? `–${x.scopeExclusion.genomicEnd}` : ''}；按精确转录本的相邻外显子边界核对，位于本次编码检索区间之外。`).join('\n') + '\n这里只核对位置，不代表已排除剪接影响、判为良性或完成全部检索。']);
        const replays = u.records.filter(x => localBatchText(x?.nativeAssessment?.originalRuleReplay?.reasonCode));
        if (replays.length) rows.push(['来源分类重算', replays.map(x => `${x.recordId || '来源记录'}：${explain(x.nativeAssessment.originalRuleReplay.reasonCode)}`).join('\n')]);
        const grounds = u.records.filter(x => localBatchText(x?.nativeAssessment?.curatedGrounds?.reasonCode));
        if (grounds.length) rows.push(['专家原件独立依据', grounds.map(x => `${x.recordId || '来源记录'}：${explain(x.nativeAssessment.curatedGrounds.reasonCode)}`).join('\n') + '\n依据专家已整理的观察核查，不表示已重新分析原始实验或核查私有病例。']);
        const assertions = u.records.filter(x => localBatchText(x?.nativeAssessment?.clinvarAssertions?.reasonCode));
        if (assertions.length) rows.push(['ClinVar逐提交交叉核查', assertions.map(x => `${x.recordId || '来源记录'}：${explain(x.nativeAssessment.clinvarAssertions.reasonCode)}`).join('\n')]);
        const audits = u.records.filter(x => x?.nativeAssessment?.independenceEstablished !== true && Array.isArray(x?.nativeAssessment?.sourceEvidenceAudit?.unresolvedReasons) &&
            x.nativeAssessment.sourceEvidenceAudit.unresolvedReasons.every(localBatchText));
        if (audits.length) rows.push(['原始判定的具体缺项', audits.map(x => `${x.recordId || '来源记录'}：${x.nativeAssessment.sourceEvidenceAudit.unresolvedReasons.map(explain).join('；')}`).join('\n')]);
        const qualified = u.records.filter(x => x?.nativeAssessment?.independenceEstablished === true);
        if (qualified.length) rows.push(['已采用的上游参考', qualified.map(x => `${x.recordId || '来源记录'}：${explain(x.reasonCode)}`).join('\n') + '\n仅采用已核定的独立依据子集；原件全条目的初步核查与未采用条目仍保留在完整结果中。']);
    }
    if (p.clinvarSearch) rows.push(['ClinVar区间检索', `${p.clinvarSearch.status || '尚未完成'}；记录 ${p.clinvarSearch.totalRecords ?? 0} 条。区间遍历完成不代表记录已具备独立致病资格，也不代表所有来源均已查全。`]);
    if (Array.isArray(p.clinvarSearch?.candidateReview?.records)) rows.push(['ClinVar候选处理', p.clinvarSearch.candidateReview.records.map(x => `${x.variationId || '来源记录'}：${explain(x.reasonCode)}`).join('；') || '当前区间无候选记录']);
    else if (localBatchText(p.clinvarSearch?.candidateReview?.reasonCode)) rows.push(['ClinVar候选处理', explain(p.clinvarSearch.candidateReview.reasonCode)]);
    if (p.refseqInventory) rows.push(['RefSeq转录本来源', `${p.refseqInventory.sourceCoverageComplete === true ? '源清单枚举完成' : '源清单尚未完整取得'}；记录 ${p.refseqInventory.transcriptCount ?? 0} 条，完整RNA与参考序列一致 ${p.refseqInventory.sequenceVerifiedCount ?? 0} 条。序列一致不等于已证明功能。`]);
    if (t?.displayTruncated || u?.displayTruncated || p.clinvarSearch?.displayTruncated) rows.push(['资料展示范围', '此处最多展示各100条摘要；全部记录及来源原件保留在完整结果中。']);
    if (p.clinvarSearch?.candidateReview?.displayTruncated || p.clinvarSearch?.candidateReview?.records?.some(x => x?.assertions?.displayTruncated) ||
        u?.records?.some(x => x?.nativeAssessment?.clinvarAssertions?.displayTruncated))
        rows.push(['提交记录展示范围', '此处最多展示100条候选及每个来源的20条提交摘要，优先保留冲突；完整结果仍保留全部记录与原件。']);
    rows.push(...pvs1FunctionalLiteratureDetails(t?.functionalLiterature));
    if (p.issues.length) rows.push(['自动准备仍缺的依据', p.issues.map(explain).join('；')]);
    if (localBatchText(p.publishedRuleCheck?.reasonCode) && p.expertAssertions?.entries?.length)
        rows.push(['起始丢失专规接线', explain(p.publishedRuleCheck.reasonCode)]);
    return rows;
}

export function bp3FunctionalDetails(criterion, expected) {
    const f = criterion?.details?.bp3Facts;
    if (!f || !bp3ProjectCriterionValid(criterion, expected)) return [];
    if (f.qualificationBasis === 'PUBLISHED_EXPERT_SPECIFICATION') return [
        ['BP3适用来源', `${f.specificationKey || '尚未匹配'}；专家规范明确的重复区域条件，科研用途。`],
        ['精确蛋白', `${f.transcript || '未取得'} / ${f.protein || '未取得'}；来源转录本 ${f.declaredTranscript || '未取得'}`],
        ['全部等价影响范围', f.queryRange ? `${f.queryRange.start}–${f.queryRange.end}；规范区域 ${f.selectedRule?.start}–${f.selectedRule?.end}` : '尚未核定'],
        ['BP3采用边界', '只执行规范中明确的区域；区外不满足该条条件，不能解释为致病。不以空注释证明无功能，也不由PM4反推。']];
    const states = { UNKNOWN: '功能适用或区域覆盖尚不足', EXCLUDED: '受审资料记载非重复区或已知功能，不满足BP3',
        QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION: '完整影响范围位于受审的重复、无已知功能区域' };
    return [['BP3功能适用', states[f.regionState]],
        ...(f.scope === 'PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE' && !f.sequenceMaterial ? [['当前序列核查回执', '公开摘要未包含当前完整序列材料或比较回执；早期取证前检查是保留的初始路由，不能代替当前材料进度。']] : []),
        ['精确蛋白', `${f.transcript || '未取得'} / ${f.protein || '未取得'}`],
        ['影响范围', f.queryRange ? `${f.queryRange.start}–${f.queryRange.end}（参考蛋白）；长度变化 ${f.netAminoAcidChange} 个氨基酸` : '尚未完成等价影响范围核对'],
        ['区域事实', `${f.repeatObservation}；当前功能线索 ${f.functionalLeadCount} 条。未找到注释不代表无功能。`],
        ['BP3采用边界', '无已知功能依赖独立受审资料，最高Supporting；不是实验证明功能正常。不由PM4反推，不自动合并最终分类。']];
}

const pm4ProjectCriterionValid = (criterion, expected) => {
    if (criterion.criterionId !== 'ACMG_AMP_2015:PM4') return !Object.hasOwn(criterion.details || {}, 'proteinLengthFacts');
    const f = criterion.details?.proteinLengthFacts;
    if (!f) return criterion.status === 'NOT_EVALUATED';
    const proof = inspectProteinLengthProof(criterion, expected, { identityHash: ps2IdentityHash });
    if (!proof.valid && !proof.view?.availabilityOnly) return false;
    const verifiedScope = ['PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE', 'PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE'].includes(f.scope);
    if (verifiedScope && (!proof.claimed && (criterion.status !== 'NOT_EVALUATED' || criterion.appliedStrength !== 'NONE') ||
        f.policyVersion !== '1.2.0' || f.factsVersion !== '1.3.0')) return false;
    if (['PROJECT_LOCAL_STOP_LOSS_SNV', 'PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE'].includes(f.scope)) {
        if (f.schemaVersion !== 'pm4-general-facts-v1' || f.policyVersion !== '1.2.0' || f.factsVersion !== '1.3.0' ||
            f.clinicalValidationCompleted !== false || f.regionState !== 'NOT_APPLICABLE_STOP_LOSS' ||
            f.repeatObservation !== 'NOT_QUERIED_STOP_LOSS_BRANCH' ||
            !['shortRepeatCandidate', 'nonRepeatFromNoHit', 'absoluteNonRepeatProof', 'pvs1CombinedAdoptionSupported', 'observedRnaOrProtein'].every(k => f[k] === false)) return false;
        if (criterion.status !== 'MET') return criterion.status === 'NOT_EVALUATED' && f.qualificationBasis === 'NONE';
        return criterion.appliedStrength === 'MODERATE' && criterion.reasonCode === 'PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET' &&
            f.qualificationBasis === 'SEQUENCE_DERIVED_STOP_LOSS' && versionedTranscript(f.transcript) && localBatchText(f.protein) &&
            f.newTerminationResolved === true && ['netAminoAcidChange', 'referenceProteinLength', 'alteredProteinLength', 'alteredStopCdsPosition',
                'availableThreePrimeBases', 'inspectedThreePrimeBases'].every(k => positiveInteger(f[k])) &&
            f.inspectedThreePrimeBases <= 100000 && f.inspectedThreePrimeBases <= f.availableThreePrimeBases &&
            f.alteredProteinLength - f.referenceProteinLength === f.netAminoAcidChange &&
            f.alteredStopCdsPosition === f.alteredProteinLength * 3 + 1 && localBatchText(f.predictedHgvsp);
    }
    const operational = f.regionState === 'OPERATIONAL_NON_REPEAT_SUPPORTED';
    const current = ['1.1.0', '1.2.0'].includes(f.policyVersion);
    const factsVersion = f.policyVersion === '1.2.0' ? '1.3.0' : current ? '1.2.0' : '1.1.0';
    if (f.schemaVersion !== 'pm4-general-facts-v1' || !['1.0.0', '1.1.0', '1.2.0'].includes(f.policyVersion) ||
        f.scope !== 'PROJECT_LOCAL_SIMPLE_INFRAME' && f.scope !== 'PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE' ||
        f.clinicalValidationCompleted !== false || f.nonRepeatFromNoHit !== operational ||
        typeof f.shortRepeatCandidate !== 'boolean' || !['UNKNOWN', 'REPEAT_OVERLAP', 'NON_REPEAT_SUPPORTED', 'OPERATIONAL_NON_REPEAT_SUPPORTED'].includes(f.regionState)) return false;
    if (current) {
        if (f.factsVersion !== factsVersion || !['REVIEWED_ONLY', 'ANNOTATION_SEQUENCE_V1'].includes(f.nonRepeatPolicy) ||
            f.absoluteNonRepeatProof !== false || f.pvs1CombinedAdoptionSupported !== false ||
            f.qualificationBasis !== (operational ? 'OPERATIONAL_INFERENCE' : criterion.status === 'MET' ? 'REVIEWED_COVERAGE' : 'NONE')) return false;
    } else if (operational) return false;
    if (operational && (!current || f.nonRepeatPolicy !== 'ANNOTATION_SEQUENCE_V1' || criterion.status !== 'MET')) return false;
    if (Object.hasOwn(f, 'factsVersion') || Object.hasOwn(f, 'repeatObservation')) {
        if (f.factsVersion !== factsVersion || !['KNOWN_REPEAT', 'SHORT_REPEAT_CANDIDATE', 'NO_REPEAT_OBSERVED', 'INCOMPLETE'].includes(f.repeatObservation)) return false;
        if (f.repeatObservation === 'NO_REPEAT_OBSERVED' && (f.shortRepeatCandidate || f.regionState === 'REPEAT_OVERLAP')) return false;
        if (f.repeatObservation === 'SHORT_REPEAT_CANDIDATE' && !f.shortRepeatCandidate) return false;
        if (criterion.status === 'MET' && f.repeatObservation !== 'NO_REPEAT_OBSERVED') return false;
        if (criterion.status === 'NOT_MET' && f.repeatObservation !== 'KNOWN_REPEAT') return false;
    }
    if (criterion.status === 'MET' || criterion.status === 'NOT_MET') {
        if (!f.transcript || !f.protein || !Number.isSafeInteger(f.netAminoAcidChange) || f.netAminoAcidChange === 0 ||
            !Number.isSafeInteger(f.queryRange?.start) || f.queryRange.start < 1 ||
            !Number.isSafeInteger(f.queryRange?.end) || f.queryRange.end < f.queryRange.start ||
            f.queryRange.coordinateSystem !== 'REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE') return false;
    }
    if (criterion.status === 'MET') return criterion.appliedStrength === 'MODERATE' &&
        (operational ? criterion.reasonCode === 'PM4_PROJECT_LOCAL_OPERATIONAL_NON_REPEAT_MET' :
            criterion.reasonCode === 'PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET' && f.regionState === 'NON_REPEAT_SUPPORTED') && !f.shortRepeatCandidate;
    return criterion.status !== 'NOT_MET' || (criterion.reasonCode === 'PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET' &&
        criterion.appliedStrength === 'NONE' && f.regionState === 'REPEAT_OVERLAP');
};

export function pm4ProteinLengthDetails(criterion, expected) {
    const f = criterion?.details?.proteinLengthFacts;
    if (!f || !pm4ProjectCriterionValid(criterion, expected)) return [];
    if (['PROJECT_LOCAL_STOP_LOSS_SNV', 'PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE'].includes(f.scope)) return [
        ['PM4采用范围', f.scope === 'PROJECT_LOCAL_STOP_LOSS_SNV'
            ? '终止丢失SNV：由精确参考和完整转录本推导延长；不是实际RNA或蛋白实验。'
            : '终止丢失小等位基因：由精确参考和完整转录本推导延长；不是实际RNA或蛋白实验。'],
        ['精确蛋白', `${f.transcript || '未取得'} / ${f.protein || '未取得'}`],
        ['延长后果', f.newTerminationResolved ? `${f.referenceProteinLength} → ${f.alteredProteinLength} 个氨基酸，净延长 ${f.netAminoAcidChange}；${f.predictedHgvsp}` : '尚未解析到可信的新终止位置'],
        ['3′端查询范围', `已检查 ${f.inspectedThreePrimeBases ?? '未取得'} / ${f.availableThreePrimeBases ?? '未取得'} 个可用碱基；有界查找，不把未找到新终止认作无穷延长。`],
        ['采用边界', '此分支单独核验终止丢失，无需重复区查询；不由PM4反推BP3，不与PVS1自动合并计权，未完成临床采用。']];
    const labels = { UNKNOWN: '区域依据不足', REPEAT_OVERLAP: '与明确重复区重叠', NON_REPEAT_SUPPORTED: '完整非重复范围有受审依据', OPERATIONAL_NON_REPEAT_SUPPORTED: '按通用项目政策推定非重复；不是绝对证明' };
    const observations = { KNOWN_REPEAT: '已查到明确重复区重叠', SHORT_REPEAT_CANDIDATE: '序列扫描发现短重复候选，尚需核定',
        NO_REPEAT_OBSERVED: '固定UniProt/InterPro区域查询及短重复扫描已完成，未观察到重复；这不自动授予非重复资格。',
        INCOMPLETE: '区域查询、坐标映射或序列扫描尚未全部完成；不能报告未检出。' };
    return [['PM4采用范围', f.scope === 'PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE'
        ? '本条为完整序列小等位基因分支的已保存结果；不由分支名称推定科学采用或临床采用。'
        : '本条结果按简单框内插入/缺失分支核验；未完成临床采用，复杂变异不在此分支范围。'],
        ...(f.scope === 'PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE' && !f.sequenceMaterial ? [['当前序列核查回执', '公开摘要未包含当前完整序列材料或比较回执；早期取证前检查是保留的初始路由，不能代替当前材料进度。']] : []),
        ['精确蛋白', `${f.transcript || '尚未取得'} / ${f.protein || '尚未取得'}`],
        ['蛋白长度变化', Number.isInteger(f.netAminoAcidChange) ? `${f.netAminoAcidChange} 个氨基酸` : '尚未完成序列核对'],
        ['等价影响范围', f.queryRange ? `${f.queryRange.start}–${f.queryRange.end}（参考蛋白）` : '尚未确定'],
        ['区域资料', labels[f.regionState]], ['短重复候选', f.shortRepeatCandidate ? '已发现，需核定适用性' : '未发现或尚未完成；不能据此证明非重复'],
        ['查询完成情况', observations[f.repeatObservation] || '旧结果未记录完整查询状态，保留原判定。'],
        ['非重复采用依据', f.qualificationBasis === 'OPERATIONAL_INFERENCE' ?
            '显式ANNOTATION_SEQUENCE_V1：完整同序列UniProt/InterPro、原/变异序列短重复筛查及排除条件均通过；不需要逐蛋白审核文件，仍非临床验证。' :
            f.qualificationBasis === 'REVIEWED_COVERAGE' ? '受审范围支持' : '本次未取得非重复采用资格'],
        ['采用边界', 'PM4不满足不代表BP3成立；不支持与PVS1自动合并计权，BP3还需要独立的功能适用依据。']];
}

export function referenceFactDetails(criterion) {
    if (criterion?.details?.spliceComparisonFacts) return spliceComparisonDetails(criterion);
    if (criterion?.criterionId === 'ACMG_AMP_2015:PM5' && criterion.status === 'NOT_EVALUATED' &&
        criterion.reasonCode === 'PM5_SPLICE_COMPARISON_NOT_APPLICABLE')
        return [['参考比较适用性', reasonHelp(criterion.reasonCode)],
            ['本次处理', '本次剪接关系不进入 PM5 的错义参考比较；无需为此补查同残基参考。']];
    const facts = criterion?.details?.referenceFacts;
    if (!facts) return [];
    const labels = { NOT_APPLICABLE: '该目标不适用当前错义参考查找', UNAVAILABLE: '参考资源或查询尚不可用',
        INCOMPLETE: '参考查找有缺项，不能声称完整排除', COMPLETE: '本次有界参考查找已完成' };
    const matches = (row) => row.proteinRelation === (criterion.criterionId === 'ACMG_AMP_2015:PS1'
        ? 'SAME_AMINO_ACID_CHANGE' : 'SAME_RESIDUE_DIFFERENT_AMINO_ACID');
    const bounded = (value, max) => Number.isInteger(value) && value >= 0 && value <= max;
    if (!['ACMG_AMP_2015:PS1', 'ACMG_AMP_2015:PM5'].includes(criterion.criterionId) ||
        facts.schemaVersion !== 'missense-reference-facts-v1' || facts.version !== '1.0.0' ||
        facts.criterionId !== criterion.criterionId || facts.purpose !== 'EXPLANATION_ONLY' || facts.scientificApprovalGranted !== false ||
        !Object.hasOwn(labels, facts.searchStatus) || !/^[A-Z][A-Z0-9_]{0,200}$/.test(facts.searchReasonCode || '') ||
        !['discoveredCandidates', 'comparableReferences', 'criterionMatches', 'excludedCandidates', 'unresolvedCandidates']
            .every((key) => bounded(facts[key], 256)) ||
        !Array.isArray(facts.references) || facts.references.length !== facts.comparableReferences ||
        facts.comparableReferences + facts.excludedCandidates + facts.unresolvedCandidates !== facts.discoveredCandidates ||
        (['NOT_APPLICABLE', 'UNAVAILABLE'].includes(facts.searchStatus) && facts.comparableReferences !== 0) ||
        (facts.searchStatus === 'COMPLETE' && facts.unresolvedCandidates !== 0) ||
        facts.references.some((row) => !row || !/^[1-9][0-9]{0,17}$/.test(row.variationId || '') ||
            !['SAME_AMINO_ACID_CHANGE', 'SAME_RESIDUE_DIFFERENT_AMINO_ACID'].includes(row.proteinRelation) ||
            !bounded(row.sourceAssertionCount, 1024)) ||
        new Set(facts.references.map((row) => row.variationId)).size !== facts.references.length ||
        facts.references.filter(matches).length !== facts.criterionMatches)
        return [['参考查找说明', '附加参考说明格式不完整；不据此推断或改变原证据结论，原始 JSON 保留。']];
    const relation = criterion.criterionId === 'ACMG_AMP_2015:PS1' ? '相同氨基酸替换' : '同残基不同氨基酸替换';
    const rows = [['参考查找', `${labels[facts.searchStatus]}；${facts.searchReasonCode}`],
        ['候选处理', `发现 ${facts.discoveredCandidates} 条；可比错义参考 ${facts.comparableReferences} 条；明确排除 ${facts.excludedCandidates} 条；未解决 ${facts.unresolvedCandidates} 条。`],
        ['本条蛋白关系', `${relation}：${facts.criterionMatches} 条。蛋白关系匹配还不等于合格致病参考。`]];
    facts.references.forEach((row) => rows.push([`ClinVar VariationID ${row.variationId}`,
        `${row.proteinRelation === 'SAME_AMINO_ACID_CHANGE' ? '相同替换' : '同残基不同替换'}；保留 ${row.sourceAssertionCount} 份源声明，源标签不是本系统的科学批准。`]));
    rows.push(['参考采用边界', '查找事实不授予 PS1/PM5。仍需核对疾病适用、参考独立致病性、审核状态及必要 RNA/RAW 剪接条件；未评价不等于不满足。']);
    return rows;
}

export function spliceComparisonDetails(criterion) {
    const f = criterion?.details?.spliceComparisonFacts;
    if (!f) return [];
    const validReason = (s) => typeof s === 'string' && /^[A-Z][A-Z0-9_]{0,150}$/.test(s);
    const validEvent = (e) => e && ['NATIVE_DONOR_LOSS', 'NATIVE_ACCEPTOR_LOSS'].includes(e.event) &&
        Number.isSafeInteger(e.position) && e.position > 0 && typeof e.score === 'string' &&
        e.score.length <= 40 && /^(?:0(?:\.\d+)?|1(?:\.0+)?)$/.test(e.score) && Number(e.score) > 0.2;
    const fullProfile = f.scope === 'INTRONIC_SNV_MATCHED_NATIVE_LOSS_PROFILE';
    const kinds = ['AG', 'AL', 'DG', 'DL'];
    const validProfile = e => e?.profile && Object.keys(e.profile).length === 4 && kinds.every(k => {
        const v = e.profile[k];
        return typeof v?.score === 'string' && v.score.length <= 40 && /^(?:0(?:\.\d+)?|1(?:\.0+)?)$/.test(v.score) &&
            Number.isSafeInteger(v.position) && v.position > 0 && Number.isSafeInteger(v.offset) && Math.abs(v.offset) <= 4999;
    }) && e.profile[e.event === 'NATIVE_DONOR_LOSS' ? 'DL' : 'AL'].position === e.position &&
        compareDecimal(e.profile[e.event === 'NATIVE_DONOR_LOSS' ? 'DL' : 'AL'].score, e.score) === 0;
    const compareDecimal = (a, b, numerator = 1, denominator = 1) => {
        const fraction = score => {
            const [whole, tail = ''] = score.split('.');
            const scale = BigInt(`1${'0'.repeat(tail.length)}`);
            return [BigInt(whole + tail), scale];
        };
        const [an, ad] = fraction(a), [bn, bd] = fraction(b);
        const d = an * bd * BigInt(denominator) - bn * ad * BigInt(numerator);
        return d < 0 ? -1 : d > 0 ? 1 : 0;
    };
    const profilesAgree = c => {
        if (!validProfile(c.targetEvent) || !validProfile(c.referenceEvent)) return false;
        const main = c.targetEvent.event === 'NATIVE_DONOR_LOSS' ? 'DL' : 'AL', gain = main === 'DL' ? 'DG' : 'AG';
        const tolerant = c.similarityMode === 'PUBLISHED_RELATIVE_TEN_PERCENT';
        return kinds.every(k => {
            const t = c.targetEvent.profile[k], r = c.referenceEvent.profile[k];
            if (k === main) return compareDecimal(t.score, r.score, tolerant ? 9 : 10, 10) >= 0;
            const gray = v => compareDecimal(v, '0.1') > 0 && compareDecimal(v, '0.2') <= 0;
            if (gray(t.score) || gray(r.score)) return false;
            const ta = compareDecimal(t.score, '0.2') > 0, ra = compareDecimal(r.score, '0.2') > 0;
            return ta === ra && (!ta || (k === gain && t.position === r.position &&
                compareDecimal(t.score, r.score, tolerant ? 9 : 10, 10) >= 0 && compareDecimal(t.score, r.score, tolerant ? 11 : 10, 10) <= 0));
        });
    };
    const b = f.baselineSource;
    const validBaseline = !b || (b.strength === f.baseline && b.currentPvs1EvaluationCreated === false &&
        ['CURRENT_CONTEXT_PVS1', 'TARGET_SPLICE_PREDICTION', 'SOURCE_ORIGINAL_EXPERT_PVS1'].includes(b.origin) &&
        (b.origin !== 'SOURCE_ORIGINAL_EXPERT_PVS1' || (/^NM_\d+\.[1-9]\d*$/.test(b.sourceTranscript) && b.runtimeTranscript === f.transcript &&
            /^c\.\d+[+-][12][ACGT]>[ACGT]$/.test(b.codingHgvs) && ['PVS1', 'PVS1_STRONG', 'PVS1_MODERATE', 'PVS1_SUPPORTING'].includes(b.strength) &&
            Array.isArray(b.assertions) && b.assertions.length > 0 && b.assertions.length <= 256 && b.assertions.every(a =>
                /^SCV\d+\.\d+$/.test(a.scv) && /^\d{4}-\d{2}-\d{2}$/.test(a.evaluationDate) && typeof a.originalVersion === 'string' && a.originalVersion.length > 0 && /^[0-9a-f]{64}$/.test(a.xmlSha256)))));
    if (criterion.criterionId !== 'ACMG_AMP_2015:PS1' || f.schemaVersion !== 'ps1-splice-facts-v1' || !['1.0.0', '1.1.0'].includes(f.methodVersion) ||
        !(f.scope === 'INTRONIC_SNV_SINGLE_NATIVE_GT_AG_SITE_LOSS' || (f.methodVersion === '1.1.0' && fullProfile)) || f.observedRna !== false || !validBaseline ||
        !['MET', 'NOT_EVALUATED'].includes(f.status) || f.status !== criterion.status || !validReason(f.reasonCode) || f.reasonCode !== criterion.reasonCode ||
        !Array.isArray(f.unresolvedReasons) || f.unresolvedReasons.length > 64 || !f.unresolvedReasons.every(validReason) ||
        (f.targetEvent && (!validEvent(f.targetEvent) || (fullProfile && !validProfile(f.targetEvent))))) return [['剪接比较', '保存的剪接比较说明不完整或相互矛盾，请查看原始结果。']];
    const rows = [['PS1 分支', '比较剪接影响；当前支持内含子SNV造成同一正常GT/AG剪接位点丢失的预测。'],
        ['剪接比较结果', reasonHelp(f.reasonCode)], ['转录本', f.transcript || '未取得相容的转录本'],
        ['已有基线', f.baseline || '尚未满足所需的 PP3/PVS1 条件']];
    if (b?.origin === 'SOURCE_ORIGINAL_EXPERT_PVS1') rows.push(['基线来源',
        `保留目标自身的专家原始依据：${b.assertions.map(a => `${a.scv}（原规范 ${a.originalVersion}）`).join('、')}；${b.sourceTranscript}:${b.codingHgvs}。不是本轮重新计算出的 PVS1，不借用参考变异的强度。`]);
    if (f.status === 'MET') {
        const c = f.comparison;
        if (!c || c.status !== 'MET' || c.observedRna !== false || c.baseline !== f.baseline || !validEvent(c.targetEvent) || !validEvent(c.referenceEvent) ||
            (f.methodVersion === '1.1.0' && !b) ||
            (fullProfile && (!profilesAgree(c) || !kinds.every(k => ['score', 'offset', 'position'].every(p => c.targetEvent.profile[k][p] === f.targetEvent?.profile?.[k]?.[p])))) ||
            !f.targetEvent || !['event', 'position', 'score'].every((k) => c.targetEvent[k] === f.targetEvent[k]) ||
            c.targetEvent.event !== c.referenceEvent.event || c.targetEvent.position !== c.referenceEvent.position ||
            !['STRONG', 'MODERATE', 'SUPPORTING'].includes(c.appliedStrength) || c.appliedStrength !== criterion.appliedStrength ||
            !['NO_WEAKER', 'PUBLISHED_RELATIVE_TEN_PERCENT'].includes(c.similarityMode) ||
            Number(c.targetEvent.score) + Number.EPSILON < Number(c.referenceEvent.score) * (c.similarityMode === 'NO_WEAKER' ? 1 : 0.9) ||
            typeof f.referenceScv !== 'string' || !/^SCV\d+\.\d+$/.test(f.referenceScv))
            return [['剪接比较', '保存的参考关系、预测分数或强度不一致，请查看原始结果。']];
        rows.push(['采用的参考', f.referenceScv], ['相同预测影响', `${c.targetEvent.event === 'NATIVE_DONOR_LOSS' ? '供体' : '受体'}位点 ${c.targetEvent.position} 丢失；目标 ${c.targetEvent.score}，参考 ${c.referenceEvent.score}。`],
            ['比较尺度', c.similarityMode === 'NO_WEAKER' ? '目标预测不弱于参考。' : '所选规范明确允许10%范围；按相对比例保守执行，不推广到其他规范。']);
        if (fullProfile) {
            kinds.forEach(k => rows.push([`完整预测 ${k}`, `目标 ${c.targetEvent.profile[k].score}／位置 ${c.targetEvent.profile[k].position}；参考 ${c.referenceEvent.profile[k].score}／位置 ${c.referenceEvent.profile[k].position}。`]));
            rows.push(['多事件范围', '逐项核对同类位点损失及相同位置的同类增益。次要增益须相近，不能把增益更高直接当作更有害；混合模式和灰区保留缺项。']);
        }
    }
    f.unresolvedReasons.forEach((r) => rows.push(['其他参考缺项', reasonHelp(r)]));
    rows.push(['适用边界', '这是预测比较，不是RNA实验或最终致病分类。仅检查已登记的RNA资料；复杂剪接和外显子蛋白影响仍可能需要补充依据。']);
    return rows;
}

export function functionalReferenceDetails(value) {
    if (!value || value.purpose !== 'EXPLANATION_ONLY') return [];
    const ready = value.status === 'VERIFIED_SAVED_REFERENCES';
    const stages = { HISTORY_INDEX: '历史资料读取', REQUEST_INTEGRITY: '请求身份核验',
        FUNCTIONAL_SOURCE_READ: '功能资料与审核记录读取', CONTEXT_AND_REFERENCE_BINDING: '背景与引用关联核验' };
    const rows = [['功能证据依赖', ready ? '已核对本次范围内的功能引用；不代表已穷尽外部实验。'
        : '功能引用尚未通过核验，相关证据保持未评价；不表示预测无效或实验不存在。']];
    if (stages[value.failureStage]) rows.push(['依赖失败环节', stages[value.failureStage]]);
    if (typeof value.reasonCode === 'string' && /^[A-Z][A-Z0-9_]{0,160}$/.test(value.reasonCode))
        rows.push(['依赖原因代码', value.reasonCode]);
    if (!ready && value.status !== 'NOT_RECORDED') rows.push(['后续处理', '修复资料读取或关联问题后，可选择受影响证据重新分析；已有历史结果保留。']);
    return rows;
}

export function predictionMechanismDetails(criterion) {
    const value = criterion?.details?.predictionMechanisms;
    if (!value) return []; // Historical results remain historical; do not invent a current assessment.
    const names = ['PROTEIN_MISSENSE', 'SPLICING'];
    const labels = { PROTEIN_MISSENSE: '错义蛋白', SPLICING: '剪接', UNRESOLVED: '未确定' };
    const models = { REVEL: 'REVEL', ALPHAMISSENSE: 'AlphaMissense', SPLICEAI: 'SpliceAI', NOT_REQUIRED: '不需要', UNRESOLVED: '未确定' };
    const directions = { HARMFUL: '有害方向', BENIGN: '良性方向', UNINFORMATIVE: '无信息区间', UNRESOLVED: '尚未确定' };
    const routes = { BLOCKED: '来源或背景阻断', REVEL_EXISTING_SUBSET: '既有 REVEL 子集', GENERAL_LOCAL_REVEL: '通用本地 REVEL（研究范围）', GENERAL_LOCAL_ALPHAMISSENSE: '通用本地 AlphaMissense（研究范围）',
        REVIEWED_POLICY: '已配置规则目录', EXISTING_DEVELOPMENT_CORE: '既有通用预测核心' };
    const safeReason = (text) => typeof text === 'string' && /^[A-Z][A-Z0-9_]{0,160}$/.test(text);
    if (!['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'].includes(criterion.criterionId) ||
        value.schemaVersion !== 'prediction-mechanism-summary-v1' || value.purpose !== 'EXPLANATION_ONLY' ||
        value.fallbackAfterScore !== false || !Object.hasOwn(routes, value.route) || !Object.hasOwn(labels, value.primaryMechanism) ||
        !['allowedPp3Mechanisms', 'requiredBp4Mechanisms'].every((key) => Array.isArray(value[key]) &&
            value[key].every((name) => names.includes(name)) && new Set(value[key]).size === value[key].length) ||
        !Array.isArray(value.mechanisms) || value.mechanisms.length !== 2 || value.mechanisms.some((row, i) =>
            row?.mechanism !== names[i] || !Object.hasOwn(models, row.model) || !Object.hasOwn(directions, row.direction) ||
            !['CALIBRATED', 'NOT_EVALUATED', 'NOT_APPLICABLE'].includes(row.status) ||
            !['NONE', 'SUPPORTING', 'MODERATE', 'STRONG', 'VERY_STRONG'].includes(row.appliedStrength) ||
            !['RAW', 'MASKED', 'UNKNOWN'].includes(row.scoreMode) ||
            !['HIT', 'NO_RECORD', 'DISABLED', 'UNAVAILABLE', 'NOT_QUERIED', 'UNKNOWN'].includes(row.observationStatus) ||
            !['reasonCode', 'observationReasonCode', 'observationQualificationReasonCode'].every((key) => safeReason(row[key])) ||
            (row.status !== 'CALIBRATED' && (row.direction !== 'UNRESOLVED' || row.appliedStrength !== 'NONE')) ||
            (row.status === 'CALIBRATED' && (row.direction === 'UNRESOLVED' ||
                (row.direction === 'UNINFORMATIVE' ? row.appliedStrength !== 'NONE' : row.appliedStrength === 'NONE')))))
        return [['预测机制说明', '附加机制说明格式不完整；不据此推断或改变原证据结论，原始 JSON 保留。']];
    const result = [['预测工具选择', `${routes[value.route]}；主机制：${labels[value.primaryMechanism]}；不根据分数切换工具。`],
        ['机制范围', `PP3 允许：${value.allowedPp3Mechanisms.map((n) => labels[n]).join('、') || '未确定'}；BP4 要求：${value.requiredBp4Mechanisms.map((n) => labels[n]).join('、') || '未确定'}。`]];
    result.push(...functionalReferenceDetails(value.functionalReferences));
    if (value.route === 'GENERAL_LOCAL_REVEL') result.push(['通用 REVEL 采用范围',
        '本地研究用途，最高强度 Strong；蛋白良性不足以单独满足完整 BP4。与 PM1 合用须核对合计最多 4 点及重复效应，本入口不自动合并分类。']);
    if (value.route === 'GENERAL_LOCAL_ALPHAMISSENSE') result.push(['通用 AlphaMissense 采用范围',
        '本地研究用途：PP3 最高 Strong、BP4 蛋白机制最高 Moderate；完整 BP4 仍须同时满足剪接条件。不按分数更换模型，不自动合并分类。']);
    if (value.transcriptMaterial !== undefined) {
        const material = value.transcriptMaterial;
        const valid = material?.purpose === 'TECHNICAL_MATERIAL_ONLY' && material.scientificApproval === false &&
            ['READY', 'UNAVAILABLE'].includes(material.status) &&
            ['GENCODE_GTF_PLUS_REFERENCE', 'PREBUILT_LOCAL'].includes(material.modelOrigin) &&
            typeof material.transcript === 'string' && /^(?:(?:ENST[0-9]+|NM_[0-9]+)\.[1-9][0-9]*|UNRESOLVED)$/.test(material.transcript) &&
            safeReason(material.reasonCode);
        result.push(['转录本材料', valid
            ? `${material.transcript}；${material.modelOrigin === 'GENCODE_GTF_PLUS_REFERENCE' ? '本地 GENCODE 与参考序列' : '既有本地模型'}；${material.status === 'READY' ? '身份与序列核对通过，仍须满足预测规则条件' : `当前不可用（${material.reasonCode}）`}。`
            : '材料说明格式不完整；不据此改变原证据结论，原始 JSON 保留。']);
    }
    for (const row of value.mechanisms) {
        const state = row.status === 'CALIBRATED' ? `${directions[row.direction]}（机制层）` : row.status === 'NOT_APPLICABLE' ? '本计划不需要' : '尚未完成机制评价';
        result.push([`${labels[row.mechanism]}机制`, `${models[row.model]} · ${state}；${row.reason || row.reasonCode}`]);
        if (row.mechanism === 'SPLICING') result.push(['剪接辅助观测',
            `读取状态 ${row.observationStatus}；分数类型 ${row.scoreMode}；${row.observationReasonCode}；${row.observationQualificationReason || row.observationQualificationReasonCode} 辅助观测不替代所选评价路径的资格，也不等于正常 RNA。`]);
    }
    return result;
}

// Exact reason definitions for historical records without server explanation metadata.
// Kept in parity with EvidenceReasonExplanation by the focused explanation regression.
export const evidenceReasonDefinitions = {
    PREDICTION_PUBLISHED_TRANSCRIPT_MISMATCH: {
        category: 'MISSING', summary: '所选转录本与专家规范指定的转录本不一致。',
        missing: '需要核对规范指定转录本上的变异后果和预测分值。', impact: '本次预测证据未采用。',
    },
    PREDICTION_PUBLISHED_CLAUSE_UNSUPPORTED: {
        category: 'POLICY_NOT_READY', summary: '该背景的专家预测条款尚未接入。',
        missing: '需要接入该规范的模型及阈值。', impact: '本次预测证据未采用。',
    },
    PREDICTION_PUBLISHED_SCOPE_UNRESOLVED: {
        category: 'MISSING', summary: '尚未确认发布规范是否覆盖当前背景。',
        missing: '需要核对疾病、遗传模式及转录本范围。', impact: '本次预测证据未采用。',
    },
    BA1_GENE_MAPPING_REQUIRED: {
        category: 'MISSING', summary: '该变异的相关基因映射尚未核全。',
        missing: '需要核对相关注释的 HGNC 基因身份。', impact: '本次 BA1 未采用。',
    },
    BA1_SPECIFICATION_SCOPE_UNRESOLVED: {
        category: 'MISSING', summary: '尚未确认 BA1 专家规范的适用范围。',
        missing: '需要核对规范中的基因、疾病、遗传模式或转录本范围。', impact: '本次 BA1 未采用。',
    },
    BA1_DISEASE_CONTEXT_REQUIRED: {
        category: 'MISSING', summary: '尚未确认 BA1 所需的疾病及遗传模式。',
        missing: '需要核对本背景是否适用疾病专用阈值。', impact: '本次 BA1 未采用。',
    },
    "PM1_PUBLISHED_SOURCE_SCOPE_NOT_COVERED": {"category":"MISSING","summary":"当前变化未命中已加载规范可执行的关键区域或位点。","missing":"覆盖当前变化的合格区域或位点依据。","impact":"不能由已加载区域未覆盖推断其他功能区域不存在，也不自动给予 PM1。"},
    "BP1_INSIDE_PUBLISHED_EXCLUDED_REGION": {"category":"NOT_APPLICABLE","summary":"当前变异位于所选规范明确排除的蛋白区域，本次不能采用该 BP1 区域外条款。","missing":"该条款的排除条件已明确；不能通过补资料绕过排除区域。","impact":"这是当前条款不适用，不等于已经判定变异致病。"},
    "DENOVO_AFFECTED_STATUS_REQUIRED": {
        "category": "MISSING",
        "summary": "当前分析样本的患病状态未满足现有新生变异分支的前置要求；这不表示文献病例或父母检测资料缺失。",
        "missing": "当前病例若要作为新生事件计分，仍需其自身患病与表型依据；论文病例按独立来源核查。",
        "impact": "本条保存结果保持原状，不能用论文病例替用户填写患病状态；旧运行不会自动重新评价。"
    },
    "PVS1_LOF_SOURCE_NOT_ESTABLISHED": {
        "category": "MISSING",
        "summary": "已找到机制来源，但该来源未确立当前基因—疾病背景的功能缺失致病机制。",
        "missing": "当前背景可采用的功能缺失机制依据。",
        "impact": "不能仅凭截短或预测后果授予 PVS1；不代表该变异已证实良性。"
    },
    "PS1_SPLICE_NATIVE_SNV_SCOPE_REQUIRED": {
        "category": "UNSUPPORTED",
        "summary": "当前 PS1 原生剪接比较路径仅支持其规定的 SNV 范围，本次变异未满足该路径范围要求。",
        "missing": "适用于该变异类型的已实现剪接比较路径。",
        "impact": "属于当前软件范围限制，不表示文献没有相关剪接依据。"
    },
    "DENOVO_ASSEMBLY_OR_CHROMOSOME_NOT_SUPPORTED": {
        "category": "UNSUPPORTED",
        "summary": "当前新生变异实现仅支持 GRCh38 常染色体，本次参考版本或染色体不在实现范围。",
        "missing": "支持当前参考版本及染色体的执行路径。",
        "impact": "PS2/PM6 保持未评价，不能据此认定医学上不适用。"
    },
    "DENOVO_INHERITANCE_NOT_SUPPORTED": {
        "category": "UNSUPPORTED",
        "summary": "当前新生变异实现仅接通常染色体显性背景，本次遗传方式不在实现范围。",
        "missing": "与当前遗传方式相容的执行路径。",
        "impact": "属于实现范围限制，不等于该遗传方式不能使用新生变异证据。"
    },
    "DENOVO_VARIANT_TYPE_NOT_SUPPORTED": {
        "category": "UNSUPPORTED",
        "summary": "当前新生变异路径未支持本次等位基因表示或长度范围。",
        "missing": "支持本次变异类型并经身份核验的执行路径。",
        "impact": "保留输入身份和未评价结果，不以改写变异表示绕过门禁。"
    },
    "BP7_NATIVE_PREPARATION_UNAVAILABLE": {
        "category": "UNAVAILABLE",
        "summary": "未取得状态为 PREPARED 的原生剪接准备计划，尚不能完成 BP7 所需的剪接核验。",
        "missing": "与当前变异、方法及版本绑定的剪接准备结果。",
        "impact": "不能把尚未完成的剪接准备当作正常剪接，也不表示程序必然崩溃。"
    },
    "PM2_LOCAL_INTERVAL_QC_FAILED": {
        "category": "QUALITY_REJECTED",
        "summary": "已取得本地人口记录，但记录带有区间覆盖质控失败标记，不能用于本次 PM2 判定。",
        "missing": "满足所用规则质量要求的人口观测。",
        "impact": "这是记录质量不合格，不是程序执行失败；不能认作频率为零或充分检测后未检出。"
    },
    "VARIANT_FILTER_NOT_PASS": {
        "category": "QUALITY_REJECTED",
        "summary": "用于本项核查的变异记录未满足 FILTER=PASS 质量要求。",
        "missing": "满足本项质量要求的记录；具体记录来源见保存事实。",
        "impact": "保留未评价，不能从质量未通过推出良性或致病，也不应反复重跑工具掩盖质控。"
    },
    "BS1_LOCAL_FILTER_NOT_PASS": {
        "category": "QUALITY_REJECTED",
        "summary": "本地外显子组人口记录的原生质量标记不是 PASS，不能用于本次 BS1 判断。",
        "missing": "质量合格且身份相容的人口记录。",
        "impact": "资料已查得但未通过质量要求，不是查询程序崩溃。"
    },
    "BS1_EXPERT_SPECIFICATION_REQUIRES_STRUCTURING": {
        "category": "POLICY_NOT_READY",
        "summary": "已找到相关专家规范，但完整条件尚未转成可执行规则，不能用通用阈值绕过。",
        "missing": "对应专家条款的结构化条件及执行接线。",
        "impact": "属于规范接入缺口，不是数据库没有变异。"
    },
    "PM2_SPECIALIST_POLICY_REVIEW_REQUIRED": {
        "category": "POLICY_NOT_READY",
        "summary": "已找到相关疾病专用规范，但适用关系或评价条件尚未完整接通。",
        "missing": "明确适用于当前背景的专规条件与接线。",
        "impact": "保留未评价，不能绕过专规套用通用阈值。"
    },
    "CLAUSE_SEARCH_SCOPE_NOT_COVERED": {
        "category": "POLICY_NOT_READY",
        "summary": "当前加载的政策目录未覆盖本项证据的解释背景，尚无相容的可执行政策。",
        "missing": "与本条证据及当前背景相容的适用政策。",
        "impact": "不能据此推断参考数据库没有变异或原文没有资料。"
    },
    "PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED": {
        "category": "MISSING",
        "summary": "当前已加载知识未覆盖所选基因、疾病和遗传方式的精确背景。",
        "missing": "适用于当前背景的合格知识。",
        "impact": "不能借用同基因其他疾病背景的结论。"
    },
    "PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED": {
        "category": "MISSING",
        "summary": "当前知识未覆盖所选带版本转录本，尚无可采用的对应关系。",
        "missing": "所选转录本的适用依据或经过核实的版本对应关系。",
        "impact": "不能直接更换转录本名称来取得得分。"
    },
    "PP2_GENERIC_AUTHORITY_DEFINITION_ONLY": {
        "category": "MISSING",
        "summary": "已找到 PP2 通用定义，但缺少当前背景的错义致病机制和低良性错义两项采用依据。",
        "missing": "当前背景同时满足两项要求的知识资料。",
        "impact": "通用定义本身不能作为 PP2 授分依据。"
    },
    "PP2_SOURCE_CONTENT_CONFLICT": {
        "category": "CONFLICT",
        "summary": "已匹配的发布规范对 PP2 的适用条件有相反说明。",
        "missing": "专家组对本版规范采用的错义约束资料及适用条件的核定。",
        "impact": "冲突解决前不采用 PP2。"
    },
    "PP2_HCM_NO_OTHER_PHENOTYPE_SUPPORT_IN_ASSESSED_SCOPE": {
        "category": "MET",
        "summary": "符合本次肥厚型心肌病专家规范的错义变异条件，已完成范围内未发现支持其他疾病表型的资料。"
    },
    "PP2_HCM_OTHER_PHENOTYPE_SUPPORTED": {
        "category": "NOT_MET",
        "summary": "已有资料支持该变异与其他疾病表型相关，未满足本次 PP2 的附加条件。"
    },
    "PP2_HCM_OTHER_PHENOTYPE_REVIEW_INCOMPLETE": {
        "category": "INCOMPLETE",
        "summary": "当前变异的其他疾病表型关联核查尚未完成。",
        "missing": "当前变异的完整专家记录范围、文献检索及病例疾病关联核查。",
        "impact": "本次尚不能采用 PP2。"
    },
    "PP2_HCM_OTHER_PHENOTYPE_CONFLICT": {
        "category": "CONFLICT",
        "summary": "疾病关联资料存在冲突。",
        "missing": "冲突来源的核对结果。",
        "impact": "冲突解决前不采用 PP2。"
    },
    "PP2_HCM_OTHER_PHENOTYPE_REVIEW_INVALID": {
        "category": "QUALITY_REJECTED",
        "summary": "核查资料与当前变异或疾病背景不一致。",
        "missing": "资料与本次分析对象之间的有效对应关系。",
        "impact": "本次不采用 PP2。"
    },
    "PP2_HCM_OTHER_PHENOTYPE_SOURCE_UNAVAILABLE": {
        "category": "CONFIGURATION",
        "summary": "其他疾病表型的取证资料尚未就绪。",
        "missing": "可用的取证配置与来源资料。",
        "impact": "本次尚不能采用 PP2。"
    },
    "PP2_HCM_OTHER_PHENOTYPE_OBSERVATION_REQUIRED": {
        "category": "INCOMPLETE",
        "summary": "本次运行尚未取得其他疾病表型的来源观察。",
        "missing": "与本次变异和背景绑定的取证记录。",
        "impact": "本次尚不能采用 PP2。"
    },
    "PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION": {
        "category": "POLICY_NOT_READY",
        "summary": "已知存在相关专门规范，尚需核定适用于当前背景的 PM4 条款。",
        "missing": "适用的完整 PM4 专家条款。",
        "impact": "不能跳过专规直接使用通用蛋白长度条件。"
    },
    "PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE": {
        "category": "UNSUPPORTED",
        "summary": "本次注释后果超出当前预测实现的支持范围，尚无对应评价路径。",
        "missing": "支持该后果的预测方法、校准与执行路径。",
        "impact": "不表示变异无影响，也不能由未评价反推另一条预测证据。"
    },
    "CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED": {
        "category": "UNSUPPORTED",
        "summary": "本次蛋白后果不满足当前纯编码错义实现的支持条件，可能涉及混合或其他后果。",
        "missing": "适用于实际后果组合的实现及资料。",
        "impact": "保留具体后果，不强行按纯错义路径评分。"
    },
    "PVS1_MECHANISM_SOURCE_NOT_FOUND": {
        "category": "MISSING",
        "summary": "当前加载的知识中未找到相容的基因—疾病机制来源。",
        "missing": "该精确背景的机制来源及适用依据。",
        "impact": "未找到来源不表示已经排除功能缺失机制。"
    },
    "PVS1_CONSEQUENCE_NOT_APPLICABLE": {
        "category": "NOT_APPLICABLE",
        "summary": "原评价器已确认所选转录本后果不属于本次 PVS1 执行分支。",
        "missing": "本次分支无需继续补取资料；其他分支适用性仍按各自依据判断。",
        "impact": "保留原未评价状态，不将当前分支不适用扩大为不存在任何潜在功能影响。"
    },
    "PM3_NO_COMPATIBLE_LITERATURE_OBSERVATIONS": {
        "category": "UNAVAILABLE",
        "summary": "本次尚无与所选变异相容、可采用的 PM3 文献观察。",
        "missing": "身份、版本和适用条件均通过核查的病例观察。",
        "impact": "可能涉及旧缓存未采用；不表示世界范围内没有相关论文。"
    }
};
export function reasonExplanation(evaluation, saved) {
    const result = evaluation?.evaluationResult || evaluation;
    if (result?.status !== 'NOT_EVALUATED') return undefined;
    const definition = evidenceReasonDefinitions[result.reasonCode];
    if (!definition) return undefined;
    // Do not trust arbitrary categories or a descriptor copied from another result.
    if (saved && (saved.schemaVersion !== 'evidence-reason-explanation-v1' ||
        saved.status !== result.status || saved.reasonCode !== result.reasonCode ||
        ['category', 'summary', 'missing', 'impact'].some(k => saved[k] !== definition[k]))) return undefined;
    return definition;
}
const unexplainedEvidenceReason = '当前保存信息尚未提供可识别的具体原因说明，请维护者核查原因代码与原始结果；不据此推断缺少数据或证据不满足。';
const localReasonScope = (id) => ({ PVS1: 'PVS1_NMD_V6', BP3: 'BP3_INFRAME_V7',
    BP1: 'BP1_MISSENSE_V9', PP2: 'PP2_MISSENSE_V8' })[id?.split(':').pop()] || id?.split(':').pop();
const genericEvidenceReason = (text) => !text || /^(?:现有材料或资格不足|原评价器判定|查看原始原因)/.test(text) || /^[A-Z][A-Z0-9_]*$/.test(text);

const publishedPtcOutcomes = {
    PM5_PUBLISHED_GENOMIC_PTC_RULE_MET: ['PM5', 'MET'],
    PM5_PTC_NO_WEIGHT_IN_PUBLISHED_STOP_EXON: ['PM5', 'NOT_MET'],
    PM5_PTC_PVS1_EXCLUDED_BY_RULE: ['PM5', 'NOT_APPLICABLE'],
    PVS1_PUBLISHED_GENOMIC_PTC_RULE_MET: ['PVS1', 'MET'],
    PVS1_PUBLISHED_PTC_RULE_EXCLUDED: ['PVS1', 'NOT_APPLICABLE'],
};

function publishedPtcCriterionValid(criterion, context) {
    const details = criterion.details;
    const facts = details?.pm5PtcFacts;
    const expected = publishedPtcOutcomes[criterion.reasonCode];
    if (!expected) return !facts || (criterion.criterionId === 'ACMG_AMP_2015:PM5' && criterion.status === 'NOT_EVALUATED');
    if (criterion.criterionId !== 'ACMG_AMP_2015:' + expected[0] || criterion.status !== expected[1] ||
        facts?.schemaVersion !== 'published-pm5-ptc-facts-v1' ||
        !/^\/modules\/pm5Ptc\/results\/\d+$/.test(details?.modulePointer) ||
        !/^[a-f0-9]{64}$/.test(details?.originalResultSha256) || !/^[a-f0-9]{64}$/.test(facts.tableSha256) ||
        facts.contextId !== context?.contextId || facts.hgncId !== context?.gene?.hgncId ||
        facts.mondoId !== context?.disease?.mondoId || facts.moi !== context?.moi ||
        facts.selectedTranscript !== `${context?.transcript?.accession}.${context?.transcript?.version}` ||
        facts.weightBasis !== 'STOP_CODON_EXON' || !Number.isInteger(facts.stopExon) ||
        !Number.isInteger(facts.stopProteinPosition)) return false;
    if (expected[1] === 'NOT_APPLICABLE') return facts.pvs1Status === 'NOT_APPLICABLE';
    if (facts.pvs1Status !== 'MET' || facts.pvs1Strength !== 'VERY_STRONG') return false;
    if (expected[0] === 'PVS1') return criterion.appliedStrength === facts.pvs1Strength;
    return criterion.appliedStrength === facts.pm5Strength;
}

// Display-only: never changes persisted statuses, strengths, facts or export bytes.
export function validApplicabilityRouting(criterion) {
    const r = criterion?.details?.applicabilityRouting;
    if (!r) return true; // Historical results keep their original validation.
    if (!['NOT_APPLICABLE', 'SOFTWARE_UNSUPPORTED', 'UNRESOLVED', 'REQUIRES_RULE_CHECK'].includes(r.status) ||
        typeof r.skipSpecializedAcquisition !== 'boolean' || typeof r.reasonCode !== 'string') return false;
    if (!r.skipSpecializedAcquisition) return ['UNRESOLVED', 'REQUIRES_RULE_CHECK'].includes(r.status);
    const code = criterion.criterionId.split(':').pop();
    if (r.reasonCode === 'ROUTING_EXTENDED_DELETION_PM1_ONLY')
        return code !== 'PM1' && criterion.status === 'NOT_EVALUATED' && criterion.appliedStrength === 'NONE' &&
            r.status === 'SOFTWARE_UNSUPPORTED' && r.reasonCode === criterion.reasonCode;
    if (code === 'PM5' && r.branch === 'GENOMIC_PTC') {
        const excluded = ['PM5_PTC_SPLICE_MECHANISM_EXCLUDED', 'PM5_PTC_DIRECT_GENOMIC_STOP_REQUIRED'];
        const unresolved = ['PM5_PTC_TRANSCRIPT_IDENTITY_UNRESOLVED', 'PM5_PTC_CONSEQUENCE_MECHANISM_UNRESOLVED'];
        return criterion.status === 'NOT_EVALUATED' && criterion.appliedStrength === 'NONE' &&
            r.reasonCode === criterion.reasonCode &&
            (r.status === 'NOT_APPLICABLE' && excluded.includes(r.reasonCode) ||
             r.status === 'UNRESOLVED' && unresolved.includes(r.reasonCode));
    }
    const reasons = { PM3: ['ROUTING_PM3_NON_RECESSIVE_CONTEXT'],
        PM1: ['ROUTING_PM1_IMPLEMENTED_MISSENSE_SCOPE'],
        PM4: ['ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE'], BP3: ['ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE'],
        PP2: ['ROUTING_MISSENSE_REQUIRED'], BP1: ['ROUTING_MISSENSE_REQUIRED'], PM5: ['ROUTING_MISSENSE_REQUIRED'],
        BP7: ['BP7_CONSEQUENCE_NOT_APPLICABLE', 'BP7_INTRONIC_SPLICE_PROXIMAL'] };
    return criterion.status === 'NOT_EVALUATED' && criterion.appliedStrength === 'NONE' &&
        r.status === (code === 'PM1' ? 'SOFTWARE_UNSUPPORTED' : 'NOT_APPLICABLE') &&
        r.reasonCode === criterion.reasonCode && reasons[code]?.includes(r.reasonCode) === true;
}

function localEvidenceExplanation(criterion, scope, blockers = []) {
    const code = criterion?.reasonCode, status = criterion?.status;
    if (status === 'NOT_MET' && criterion.criterionId === 'ACMG_AMP_2015:BP4' && code === 'BP4_REQUIRED_MECHANISM_NOT_BENIGN') {
        const mechanisms = criterion.details?.predictionMechanisms;
        if (predictionMechanismDetails(criterion).some(([label]) => label === '预测工具选择')) {
            const names = { PROTEIN_MISSENSE: '蛋白错义', SPLICING: '剪接' };
            const notBenign = mechanisms.mechanisms.filter(m => mechanisms.requiredBp4Mechanisms.includes(m.mechanism) &&
                m.status === 'CALIBRATED' && m.direction !== 'BENIGN');
            if (notBenign.length) return `已有预测资料进入评价，但${notBenign.map(m => `${names[m.mechanism]}（${m.model}）`).join('、')}未达到所选校准政策的良性条件，因此 BP4 不满足；不据此自动授予 PP3 或作最终分类。`;
        }
    }
    const descriptor = reasonExplanation(criterion, criterion?.details?.explanation);
    if (descriptor) {
        const suffix = criterion?.details?.populationScope ? ' 结论仅限 gnomAD 4.1 EXOMES，不代表全库或临床采用。' : '';
        return descriptor.summary + suffix;
    }
    const known = reasonHelp(code, scope, true);
    let text;
    if (status === 'MET' || status === 'NOT_MET') {
        text = !genericEvidenceReason(criterion.reason) ? criterion.reason :
            `${status === 'MET' ? '满足' : '未满足'}本次规则条件；实际强度和依据以保存结果为准。`;
    } else {
        text = known !== unexplainedEvidenceReason ? known :
            !genericEvidenceReason(criterion?.reason) ? criterion.reason : unexplainedEvidenceReason;
    }
    if (['EXPLICIT_INPUT_MISSING', 'BINDING_NOT_READY', 'SELECTED_ATTEMPT_AMBIGUOUS_OR_MISSING'].includes(code)) {
        const causes = [...new Set(blockers.map(b => b?.reasonCode || b).filter(c => typeof c === 'string' && c && c !== code))];
        if (causes.length) text += ` 具体背景缺项：${causes.map(c => reasonHelp(c, scope, true)).join('；')}`;
    }
    if (criterion?.details?.populationScope && !text.includes('EXOMES'))
        text += ' 结论仅限 gnomAD 4.1 EXOMES，本轮没有建立全库或临床采用结论。';
    return text;
}

function localBatchCards(candidate, criteria, profile, validationCriteria = criteria, expected) {
    const attempts = candidate.attempts?.length ? candidate.attempts : [{}];
    return criteria.map((id) => ({ id, code: id.split(':').pop(), entries: attempts.map((attempt) => {
        const issue = localBatchAttemptIssue(candidate, attempt, validationCriteria, profile, id, expected);
        const value = attempt.localBatch;
        const savedCriterion = value?.criteria?.find((row) => row.criterionId === id);
        const rnaExpected = fullRnaExpected(candidate, attempt, profile, expected);
        const fullRna = inspectFullRnaCriterion(savedCriterion, rnaExpected, { identityHash: ps2IdentityHash });
        const legacyProof = { claimed: false, valid: true, issues: [] };
        const codingProof = fullRna.claimed ? legacyProof : inspectPvs1CodingEscapeAssessment(savedCriterion, attempt.context, { identityHash: ps2IdentityHash, profile });
        const codingAvailabilityOnly = issue === PVS1_CODING_PROOF_UNAVAILABLE && codingProof.view?.availabilityOnly;
        const proteinExpected = { mode: 'SELECTED_V3_AND_EVALUATION_V2', candidateId: candidate.candidateId,
            hypothesisId: value?.hypothesisId, context: attempt.context };
        const proteinProof = fullRna.claimed ? legacyProof : inspectProteinLengthProof(savedCriterion, proteinExpected, { identityHash: ps2IdentityHash });
        const proteinAvailabilityOnly = issue === PROTEIN_LENGTH_PROOF_UNAVAILABLE && proteinProof.view?.availabilityOnly;
        const unsupported = issue && !fullRna.claimed ? unsupportedSavedMethod(savedCriterion, profile, value?.criteria, validationCriteria.filter(key => DENOVO_CRITERIA.includes(key)), attempt.context) : null;
        const proofUnavailable = issue && !fullRna.claimed && (codingAvailabilityOnly || proteinAvailabilityOnly || !codingProof.claimed && isPvs1SpliceSummary(savedCriterion?.details?.pvs1Facts) &&
            inspectPvs1SpliceCriterion(savedCriterion, attempt.context, { identityHash: ps2IdentityHash, profile }).issues.some(code => ['PVS1_ESCAPE_PUBLIC_PROOF_UNAVAILABLE', 'PVS1_ESCAPE_PUBLIC_REFERENCE_EVENT_BOUNDARY_REQUIRED'].includes(code)) ||
            isDeNovoMethodsSummary(savedCriterion?.details?.deNovoFacts) && inspectDeNovoMethodsPair(value?.criteria, profile,
                validationCriteria.filter(key => DENOVO_CRITERIA.includes(key)), { validCoreMaterial, context: attempt.context, identityHash: ps2IdentityHash }).issues
                .some(code => ['DENOVO_ADOPTION_PUBLIC_PROOF_INCOMPLETE', 'DENOVO_ADOPTION_PUBLIC_PROOF_UNAVAILABLE'].includes(code)));
        const criterion = issue ? null : savedCriterion;
        const publishedCondition = criterion ? inspectPublishedConditionCriterion(criterion,
            publishedConditionExpected(candidate, attempt, profile, expected), { identityHash: ps2IdentityHash, profile }) : null;
        const contribution = criterion ? pp1ContributionPreparationView(criterion) : null;
        const reasonScope = localReasonScope(id);
        // PP1 has its own validated selected-context binding. An unrelated
        // computational preparation blocker is not a PP1 material requirement.
        const contextReasons = !issue && !attempt.context && criterion.status === 'NOT_EVALUATED' &&
            !(id === PP1_LITERATURE_ID && criterion.details?.pp1Facts?.evaluation)
            ? [...new Set((Array.isArray(attempt.blockers) ? attempt.blockers : [])
                .map((blocker) => blocker?.reasonCode).filter((code) => typeof code === 'string' && code))] : [];
        const explanation = fullRna.claimed && fullRna.valid ? fullRnaSummary(fullRna.view) : publishedCondition?.valid && publishedCondition.view?.kind === 'BP1_CURRENT_CONDITIONS'
            ? `${publishedCondition.view.available}${publishedCondition.view.difference}`
            : criterion ? localEvidenceExplanation(criterion, reasonScope, contextReasons) : undefined;
        // A display projection, never an atomic-evidence-v2 identity or a persisted scientific result.
        const evaluation = criterion ? { criterionId: criterion.criterionId, appliedStrength: criterion.appliedStrength,
            evaluationResult: { status: criterion.status, reasonCode: criterion.reasonCode },
            explanation: criterion.details?.explanation,
            ...(id === 'ACMG_AMP_2015:BP7' ? { reviewRequired: criterion.details?.bp7?.reviewRequired === true,
                criterionApplicability: { status: criterion.details?.bp7?.coreStatus === 'NOT_APPLICABLE' ? 'NOT_APPLICABLE' : 'UNKNOWN' } } : {}),
            ...(!fullRna.claimed && ['NOT_APPLICABLE', 'SOFTWARE_UNSUPPORTED'].includes(criterion.details?.applicabilityRouting?.status)
                ? { criterionApplicability: { status: criterion.details.applicabilityRouting.status } } : {}),
            ...(fullRna.valid && fullRna.view?.inapplicable ? { criterionApplicability: { status: 'NOT_APPLICABLE' } } : {}),
            ...(publishedCondition?.valid && publishedCondition.view?.conditionStatus === 'NOT_APPLICABLE'
                ? { criterionApplicability: { status: 'NOT_APPLICABLE' } } : {}),
            ...(id === 'ACMG_AMP_2015:BP1' && publishedCondition?.valid && publishedCondition.view?.conditionStatus === 'SOFTWARE_UNSUPPORTED'
                ? { criterionApplicability: { status: 'SOFTWARE_UNSUPPORTED' } } : {}) } : undefined;
        if (!issue && evaluation && fullRna.claimed && fullRna.valid) rememberFullRnaProjection(evaluation, savedCriterion, rnaExpected, fullRna);
        if (evaluation && contribution?.valid && contribution.systemGap) {
            const currentBinding = () => pp1ProjectionSourceBinding(attempt, id);
            pp1ContributionProjections.set(evaluation, { projection: pp1ProjectionIdentity(evaluation), currentBinding,
                binding: currentBinding(), nextStep: contribution.preparation.nextStep });
        }
        return {
            evaluation,
            ...(codingProof.view && (!issue || codingAvailabilityOnly) ? { pvs1CodingProofView: codingProof.view } : {}),
            ...(issue && !proteinAvailabilityOnly || !proteinProof.view ? {} : { proteinLengthProofView: proteinProof.view }),
            hypothesisId: value?.hypothesisId || hypothesisIdOf(attempt),
            summary: unsupported || issue || explanation,
            reasonScope, strictReasonHelp: true,
            displayIssue: Boolean(issue),
            ...(proofUnavailable ? { displayOutcome: { kind: 'UNAVAILABLE', label: '公开采用证明暂不可用', next: '核对已保存证明投影的具体原因；原始科学结论保留，不从缺少展示证明反推病例缺项。' } } : unsupported ? { displayOutcome: { kind: 'UNSUPPORTED', label: '当前页面暂不支持此结果方法', next: '需补齐相应展示校验；保存原文与后台原结论未改动。' } } : {}),
            scopeLabel: criterion?.details?.populationScope
                ? 'PM2 · 仅 gnomAD 4.1 EXOMES · 未建立全库或临床采用结论'
                : `${pp1LiteratureScope({ executionProfile: profile }) || pm3LiteratureScope({ executionProfile: profile }) || deNovoLocalScope({ executionProfile: profile }) ? `统一${validationCriteria.length}条` : profile?.profileId === FOURTEEN_PROFILE ? '统一十四条' : profile?.profileId === ELEVEN_PROFILE ? '统一十一条' : profile?.profileId === UNIFIED_PROFILE ? '统一八条' : `本地${criteria.length}条`}证据 · 各规则独立结果`,
            contextLabel: [attempt.context?.gene?.symbol || attempt.context?.gene?.hgncId,
                attempt.context?.disease?.mondoId, attempt.context?.moi, value?.hypothesisId].filter(Boolean).join(' · '),
            reasons: issue ? [issue] : [explanation, ...contextReasons],
            details: issue ? [['保存结果检查', unsupported || issue],
                ...(savedCriterion ? [['原保存状态与强度（未采用）', `${savedCriterion.status} / ${savedCriterion.appliedStrength}`],
                    ['原保存原因', savedCriterion.reason], ['原保存原因代码', savedCriterion.reasonCode]] : []),
                ...(proteinAvailabilityOnly ? proteinLengthProofDetails(proteinProof) : []),
                ...(codingAvailabilityOnly ? pvs1CodingEscapeDetails(codingProof) : []),
                ...(isDeNovoMethodsSummary(savedCriterion?.details?.deNovoFacts) ? deNovoMethodsResultDetails(savedCriterion) : []),
                ...(!fullRna.claimed && isPvs1SpliceSummary(savedCriterion?.details?.pvs1Facts) && !codingProof.claimed ? pvs1SpliceDetails(savedCriterion) : [])] : fullRna.claimed ? [['本次原因', explanation]] : [['本次原因', explanation],
                ['原因代码', criterion.reasonCode], ['背景身份', value.contextId ?? '未形成'],
                ...explanationDetails(criterion, criterion.details?.explanation),
                ...(criterion.details?.applicabilityRouting ? [[['PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE', 'PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE']
                    .includes((criterion.details.proteinLengthFacts || criterion.details.bp3Facts)?.scope) ? '取证前检查（初始路由）' : '取证前检查', ({ NOT_APPLICABLE: '不适用当前分支，已跳过专用取证',
                    SOFTWARE_UNSUPPORTED: '超出当前软件分支，保留未支持原因', UNRESOLVED: '信息不足以提前排除，交原规则继续核查',
                    REQUIRES_RULE_CHECK: '需要专用资料和原规则核查；尚不表示满足证据' })[criterion.details.applicabilityRouting.status] || '保留原检查结果']] : []),
                ...(contextReasons.length ? [['背景缺项', contextReasons.map(reasonHelp).join('；')]] : []),
                ...materialPreparationDetails(criterion),
                ...pm2FactDetails(criterion),
                ...predictionMechanismDetails(criterion),
                ...pm4ProteinLengthDetails(criterion, proteinExpected),
                ...proteinLengthProofDetails(proteinProof),
                ...pvs1SelectedDetails(criterion, attempt.context, profile),
                ...bp3FunctionalDetails(criterion, proteinExpected),
                ...publishedMissenseDetails(criterion, profile, publishedConditionExpected(candidate, attempt, profile, expected)),
                ...referenceFactDetails(criterion),
                ...bp7ResultDetails(criterion),
                ...deNovoResultDetails(criterion),
                ...pp1FormalResultDetails(criterion),
                // The new PVS1 contract contains a bounded projection, not the
                // full A5 source material. Its own validated reader supplies it.
                ...(isPvs1SpliceSummary(criterion.details?.pvs1Facts) ? [] : sequenceEditDetails(criterion)),
                ['规则详情', typeof criterion.details === 'object' ? JSON.stringify(criterion.details) : criterion.details],
                ['证据边界', '来源观测、校准和实际应用分别保留；未评价不是阴性，不生成最终分类。']],
            coreMaterials: issue ? undefined : criterion.details?.deNovoFacts?.coreMaterials,
            pm3LiteratureFacts: issue ? undefined : criterion.details?.pm3Facts,
            pm3ConditionsVersion: issue ? undefined : profile?.moduleBindings?.pm3?.version,
            pp1LiteratureFacts: issue ? undefined : criterion.details?.pp1Facts,
            clinicalDisplayScope: !issue && id === PP1_LITERATURE_ID && localBatchText(expected?.analysisId) && localBatchText(expected?.runId) &&
                typeof expected?.requestSha256 === 'string' && /^[a-f0-9]{64}$/.test(expected.requestSha256) ? (() => {
                    const claims = [];
                    if (Object.hasOwn(expected, 'currentClinicalArchiveSha256')) claims.push(expected.currentClinicalArchiveSha256);
                    if (localBatchObject(expected.currentClinicalEvidence) && Object.hasOwn(expected.currentClinicalEvidence, 'sourceArchiveSha256'))
                        claims.push(expected.currentClinicalEvidence.sourceArchiveSha256);
                    if (claims.some(value => typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value)) || new Set(claims).size > 1) return undefined;
                    return { analysisId: expected.analysisId, runId: expected.runId, requestSha256: expected.requestSha256,
                        ...(claims.length ? { archiveSha256: claims[0] } : {}) };
                })() : undefined,
            pp1Criterion: issue ? undefined : criterion,
            savedCriterion,
            executionDiagnostics: fullRna.claimed ? [] : sequenceFailureDetails(savedCriterion),
            presentationIssues: issue ? [
                ...fullRna.issues,
                ...codingProof.issues,
                ...proteinProof.issues,
                ...(isDeNovoMethodsSummary(savedCriterion?.details?.deNovoFacts)
                    ? inspectDeNovoMethodsPair(value?.criteria, profile, validationCriteria.filter(key => DENOVO_CRITERIA.includes(key)), { validCoreMaterial, context: attempt.context, identityHash: ps2IdentityHash }).issues : []),
                ...(!fullRna.claimed && isPvs1SpliceSummary(savedCriterion?.details?.pvs1Facts) && !codingProof.claimed
                    ? inspectPvs1SpliceCriterion(savedCriterion, attempt.context, { identityHash: ps2IdentityHash, profile }).issues : []),
            ].filter(code => !code.endsWith('PUBLIC_PROOF_REQUIRED')) : [],
            literatureDecision: issue ? undefined : criterion.details?.deNovoFacts?.criterionExplanations?.find(d => d.criterionId === criterion.criterionId),
            raw: value,
        };
    }) }));
}

function pm2FactDetails(criterion) {
    const facts = criterion?.details?.populationFacts;
    if (criterion?.criterionId !== 'ACMG_AMP_2015:PM2' || facts?.schemaVersion !== 'pm2-local-facts-v1') return [];
    const rows = [['人口数据', 'gnomAD 4.1 外显子组'],
        ['精确变异查询', ({ HIT: '已收录', NO_RECORD: '未收录', NOT_QUERIED: '未取得有效查询' })[facts.queryStatus] || facts.queryStatus]];
    if (facts.detection) {
        rows.push(['位置检测量 AN', facts.detection.adjAn ?? '未取得'],
            ['检测量范围', '全部发布样本的质量调整后 AN；不包含祖源分组证明']);
        if (PM2_REGION_FLAGS.every((key) => typeof facts.detection.regionFlags?.[key] === 'boolean')) {
            const labels = { outside_broad_capture_region: 'Broad 捕获区域之外', outside_ukb_capture_region: 'UKB 捕获区域之外',
                outside_broad_calling_region: 'Broad 调用区域之外', outside_ukb_calling_region: 'UKB 调用区域之外' };
            rows.push(['区域标记', Object.entries(facts.detection.regionFlags).filter(([, v]) => v === true)
                .map(([key]) => labels[key] || key).join('；') || '没有触发区域外标记']);
        } else rows.push(['区域标记', '未取得完整区域标记']);
    }
    return rows;
}

// v3 references are server-provided; no scientific observations are submitted by the browser.
export function savedCalibrationParent(options, run, analysisId) {
    const ref = options?.calibrationReference;
    return Boolean(ref && run?.analysisId === analysisId && run?.runId === ref.sourceRunId &&
        run?.requestSha256 === ref.sourceRequestSha256 && run?.operation === 'ASSOCIATE_SAVED_OBSERVATIONS' &&
        ['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run?.status));
}
export function buildSavedCalibrationDeclaration(options, analysisId, run) {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(bp2ScopeClaim)) throw new Error('BP2 请使用固定双变异材料独立确认入口。');
    if ([...arguments].some(pm3ScopeClaim)) throw new Error('PM3 请使用固定双变异材料独立确认入口。');
    if ([...arguments].some(pm6ScopeClaim)) throw new Error('PM6 请使用固定材料独立确认入口。');
    if (!savedCalibrationParent(options, run, analysisId) || options.requestedN !== 2)
        throw new Error('请先读取本分析获准的已保存关联运行和 Top-2 输入选项。');
    const ref = options.calibrationReference;
    return { topN: 2, declarationVersion: options.requiredDeclaration, sampleId: options.sampleId,
        dataClass: 'SYNTHETIC', patient: null, hypotheses: [],
        calibration: { operation: ref.operation, sourceRunId: ref.sourceRunId,
            sourceRequestSha256: ref.sourceRequestSha256, executionSourceSha256: ref.executionSourceSha256,
            calibrationCanonicalSha256: ref.calibrationCanonicalSha256, adoptionSha256: ref.adoptionSha256,
            results: ref.results.map(({ candidateId, sourceResultId, sourceObservationSha256 }) =>
                ({ candidateId, sourceResultId, sourceObservationSha256 })) } };
}
export function savedCalibrationDetails(result) {
    if (result?.schemaVersion !== 'prediction-rule-result-v3') return [
        ['校准候选区间', '登记区间 / 未启用；来源强度不等于实际应用强度'],
        ['校准状态', result?.calibration?.comparisonSemantics || '未记录'],
        ['校准来源', [result?.calibration?.source?.doi, result?.calibration?.source?.locator].filter(Boolean).join(' / ') || '未记录'],
    ];
    const c = result.calibration || {};
    const complete = c.status === 'CALIBRATED';
    const direction = complete && c.direction === 'UNINFORMATIVE' ? '无信息区（校准已完成）'
        : complete && c.direction === 'BENIGN' ? '蛋白机制良性方向'
        : complete && c.direction === 'HARMFUL' ? '蛋白机制有害方向' : '未知';
    const strength = c.calibratedStrength ?? '未知';
    return [
        ['校准结果', `${direction} / ${strength === 'SUPPORTING' ? 'Supporting' : strength}；尚未应用 ${c.direction === 'BENIGN' ? 'BP4' : 'PP3/BP4'}`],
        ['校准状态', c.status || '未知'], ['校准强度', strength],
        ['校准区间', c.band ? JSON.stringify(c.band) : '未知'],
        ['校准合同', `${c.calibrationId || '未知'} @ ${c.calibrationVersion || '未知'}`],
        ['校准来源', c.sources ? JSON.stringify(c.sources) : '未知'],
        ['限定采用范围', `${c.localAdoption?.scope || '未知'} / ${c.localAdoption?.status || '未知'}；全局 PARTIAL`],
        ['直接关联来源', result.sourceRunId || '未知'], ['原始观测祖先', result.rawAncestorRunId || '未知'],
        ['待完成应用条件', c.remainingApplicationConditions?.map((code) => ({
            SPLICING_MECHANISM_NOT_EVALUATED: '剪接机制尚未评价',
            DISEASE_APPLICABILITY_NOT_EVALUATED: '疾病适用性尚未评价',
            APPLICATION_ADOPTION_NOT_EVALUATED: '规则应用采用尚未评价',
        }[code] || code)).join('；') || '未知'],
    ];
}

// Declaration preparation only. The server validates all identities and sources again.
export function validateInputOptions(options, analysisId) {
    const localIssue = localBatchScopeIssue(options);
    if (localIssue) throw new Error(localIssue);
    if (pp4ScopeClaim(options)) { const issue = pp4ScopeIssue(options, analysisId); if (issue) throw new Error(issue); return options; }
    if (bs4ScopeClaim(options)) { const issue = bs4ScopeIssue(options, analysisId); if (issue) throw new Error(issue); return options; }
    if (pp1ScopeClaim(options)) { const issue = pp1ScopeIssue(options, analysisId); if (issue) throw new Error(issue); return options; }
    if (bp2ScopeClaim(options)) { const issue = bp2ScopeIssue(options, analysisId); if (issue) throw new Error(issue); return options; }
    if (pm3ScopeClaim(options)) { const issue = pm3ScopeIssue(options, analysisId); if (issue) throw new Error(issue); return options; }
    if (pm6ScopeClaim(options)) {
        const issue = pm6ScopeIssue(options, analysisId);
        if (issue) throw new Error(issue);
        return options;
    }
    if (ps2ScopeClaim(options)) {
        const issue = ps2ScopeIssue(options, analysisId);
        if (issue) throw new Error(issue);
        return options;
    }
    const rows = options?.candidates;
    const ids = options?.candidateIds;
    if (
        options?.analysisId !== analysisId ||
        !Array.isArray(rows) ||
        !Array.isArray(ids) ||
        rows.length !== options.selectedCount ||
        rows.length !== ids.length ||
        !Number.isInteger(options.requestedN) ||
        options.requestedN < 1 ||
        options.requestedN > 100 ||
        rows.length > options.requestedN ||
        new Set(ids).size !== ids.length ||
        rows.some((row, i) => !row.candidateId || row.candidateId !== ids[i] || row.rank !== i + 1)
    )
        throw new Error('正式候选身份或顺序不一致，请重新读取任务；未提交评价。');
    return options;
}

export function transcriptOptions(candidate) {
    const rows = candidate?.transcriptAnnotations || [];
    const identity = (annotation, index) =>
        candidate.transcriptChoices?.find((c) => c.annotationIndex === index)
            ?.versionedTranscriptId || annotation.transcriptAccession;
    return rows.map((annotation, index) => {
        const versionedId = identity(annotation, index);
        const match = /^(.+)\.([1-9][0-9]*)$/.exec(versionedId || '');
        const version = match ? Number(match[2]) : null;
        const same = rows.filter(
            (a, i) =>
                identity(a, i) === versionedId &&
                a.geneSymbol === annotation.geneSymbol &&
                a.source === annotation.source &&
                a.version === annotation.version,
        );
        const eligible = Boolean(
            match &&
                Number.isSafeInteger(version) &&
                version <= 2147483647 &&
                annotation.geneSymbol === candidate.geneSymbol &&
                annotation.status === 'COMPLETED' &&
                annotation.source &&
                annotation.version &&
                same.length === 1,
        );
        return {
            index: String(index),
            annotation,
            versionedId,
            accession: match?.[1],
            version,
            eligible,
        };
    });
}

export function emptyHypothesis(candidate, index = 1) {
    return {
        hypothesisId: `h${index}`,
        hgncId: candidate.catalog?.hgncId || '',
        mondoId: '',
        moi: '',
        affectedStatus: '',
        transcriptIndex: '',
        selectionReason: '',
        recordLocator: '',
    };
}

const safeSegment = (value, fallback) => {
    const normalized = String(value || '')
        .toLowerCase()
        .replace(/[^a-z0-9._-]+/g, '-')
        .replace(/\.\.+/g, '.')
        .replace(/^[^a-z0-9]+/, '')
        .slice(0, 80);
    return normalized.length >= 8
        ? normalized
        : `${fallback}-${normalized || 'unknown'}`.slice(0, 80);
};

export function automaticCaseForm(analysisId, dataClass = 'PRIVACY_BOUNDED_RUNTIME', declarationVersion = 'ordinary-ui-v3') {
    const analysisSegment = safeSegment(analysisId, 'analysis');
    return {
        declarationVersion,
        patientContextId: `patient-context-${analysisSegment}`,
        sex: 'UNKNOWN',
        familyContextId: '',
        recordLocator: `analysis/${analysisSegment}/acmg/${declarationVersion}`,
        dataClass,
    };
}

export function scoreOriginLabel(origin) {
    return ({ REAL_LOCAL_PRECOMPUTED_RESOURCE: '真实本地预计算资源',
        SYNTHETIC: '合成分数', SYNTHETIC_FIXTURE: '合成测试分数' })[origin]
        || (origin ? `未识别的分数来源：${origin}` : '历史结果未保存分数来源');
}

export function savedObservationParent(run, analysisId) {
    return Boolean(run?.analysisId === analysisId && !run.operation &&
        run.declarationVersion === 'ordinary-pp3-bp4-development-v1' &&
        ['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status));
}

// References only. No score, observation, trust flag or invented disease context is sent.
export function buildSavedObservationAssociationDeclaration(options, analysisId, parent, choices, selectionOrigin = 'USER_EXPLICIT') {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(bp2ScopeClaim)) throw new Error('BP2 请使用固定双变异材料独立确认入口。');
    if ([...arguments].some(pm3ScopeClaim)) throw new Error('PM3 请使用固定双变异材料独立确认入口。');
    if ([...arguments].some(pm6ScopeClaim)) throw new Error('PM6 请使用固定材料独立确认入口。');
    validateInputOptions(options, analysisId);
    if (options.executionProfile?.operation !== 'ASSOCIATE_SAVED_OBSERVATIONS' ||
        !savedObservationParent(parent, analysisId)) throw new Error('请先读取本分析的原始观测父记录。');
    if (!['USER_EXPLICIT', 'DEVELOPER_PUBLIC_CONTROL'].includes(selectionOrigin) ||
        (selectionOrigin === 'DEVELOPER_PUBLIC_CONTROL' && parent.dataClass !== 'SYNTHETIC'))
        throw new Error('关联选择来源不合法。');
    const selected = Object.entries(choices || {}).filter(([, id]) => id);
    if (selected.length < 1 || selected.length > 2) throw new Error('请明确选择一至两条候选的 VEP 转录本；不自动选择第一项。');
    const selections = selected.map(([candidateId, selectedAnnotationId]) => {
        const row = options.candidates.find((c) => c.candidateId === candidateId);
        const matches = row?.associationOptions?.filter((a) => a.annotationId === selectedAnnotationId) || [];
        const observations = parent.candidates?.filter((c) => c.candidateId === candidateId)
            .flatMap((c) => c.attempts || []).map((a) => a.prediction?.observation).filter((o) => o?.status === 'HIT') || [];
        if (matches.length !== 1 || observations.length !== 1) throw new Error('所选注释或父观测无法唯一恢复，请重新读取。');
        const annotation = matches[0];
        return { candidateId, sourceObservationSha256: observations[0].observationSha256,
            selectedAnnotationId, annotationSourceSha256: annotation.annotationSourceSha256,
            versionedTranscriptId: annotation.versionedTranscriptId, selectionOrigin };
    });
    const declaration = buildCaseDeclaration(options, analysisId,
        automaticCaseForm(analysisId, parent.dataClass, 'ordinary-pp3-bp4-development-v2'), {});
    declaration.association = { operation: 'ASSOCIATE_SAVED_OBSERVATIONS', sourceRunId: parent.runId,
        sourceRequestSha256: parent.requestSha256, executionSourceSha256: parent.executionSourceSha256, selections };
    return declaration;
}

// Recommendation selection belongs to the server. Legacy payloads remain readable but never auto-select.
export function recommendedTranscript(candidate, relationship) {
    const recommendation = candidate?.recommendation;
    const selected = contextTranscriptRecommendation(candidate, relationship);
    const transcript = transcriptOptions(candidate).find(
        (t) => Number(t.index) === selected?.annotationIndex && t.eligible,
    );
    return transcript
        ? {
              transcript,
              kind:
                  predictionContextAnnotation(selected)
                      ? 'PREDICTION_CONTEXT_RECOMMENDATION'
                      : ruleContextAnnotation(selected)
                      ? 'RULE_CONTEXT_RECOMMENDATION'
                      : pvs1ContextAnnotation(selected)
                      ? 'PVS1_CONTEXT_RECOMMENDATION'
                      : revelContextAnnotation(selected)
                      ? 'HNF1A_REVEL_CONTEXT_RECOMMENDATION'
                      : selected === recommendation?.resourceCompatibleTranscript
                      ? 'RESOURCE_COMPATIBLE_RECOMMENDATION'
                      : selected === recommendation?.pm1CompatibleTranscript
                      ? 'PM1_TRANSCRIPT_RECOMMENDATION'
                      : 'PS1_PM5_COMPATIBLE_RECOMMENDATION',
          }
        : null;
}

export function ps1Pm5Relationship(relationship) {
    return Boolean(
        relationship?.selectable &&
            relationship.relationshipType === 'VCEP_SPECIFICATION_SCOPE' &&
            relationship.hgncId === 'HGNC:1097' &&
            relationship.mondoId === 'MONDO:0021060' &&
            ['AD', 'AUTOSOMAL_DOMINANT'].includes(relationship.moi),
    );
}

export function annotationSelectable(annotation, relationship) {
    return Boolean(
        predictionContextAnnotation(annotation) || ruleContextAnnotation(annotation) || annotation?.identityCompatible ||
            (annotation?.pm1Compatible && pm1Relationship(relationship)) ||
            (annotation?.ps1Pm5Compatible && ps1Pm5Relationship(relationship)),
    );
}

export function ruleContextAnnotation(annotation) {
    return Boolean(annotation?.ruleContextReady && annotation?.predictionPath === 'REVIEWED_RULE_CONTEXT' &&
        (/^REVIEWED_RULE_CONTEXT@(1\.0\.0|2\.0\.0):[0-9a-f]{64}$/.test(annotation?.ruleContextSelectionPolicy || '') ||
        (annotation.contextSelectable === true && /^REVIEWED_RULE_CONTEXT@(3\.0\.0|4\.0\.0|5\.0\.0|6\.0\.0|7\.0\.0|8\.0\.0|9\.0\.0):[0-9a-f]{64}$/.test(annotation.ruleContextSelectionPolicy || ''))));
}

export function predictionContextAnnotation(annotation) {
    return Boolean(annotation?.predictionContextReady && annotation?.predictionPath === 'REVIEWED_PREDICTION_CONTEXT' &&
        /^REVIEWED_PREDICTION_CONTEXT@1\.0\.0:[0-9a-f]{64}$/.test(annotation?.predictionContextSelectionPolicy || ''));
}

export function pm1Relationship(relationship) {
    return Boolean(relationship?.selectable && relationship.hgncId === 'HGNC:11621' &&
        relationship.mondoId === 'MONDO:0015967' && ['AD', 'AUTOSOMAL_DOMINANT'].includes(relationship.moi));
}

export function pvs1ContextAnnotation(annotation) {
    return ['PVS1_STOP_GAINED_CONTEXT', 'PVS1_FRAMESHIFT_CONTEXT'].includes(annotation?.predictionPath);
}

export function revelContextAnnotation(annotation) {
    return annotation?.predictionPath === 'HNF1A_REVEL';
}

export function contextTranscriptRecommendation(candidate, relationship) {
    const resource = candidate?.recommendation?.resourceCompatibleTranscript;
    const missense = ps1Pm5Relationship(relationship)
        ? candidate?.recommendation?.ps1Pm5CompatibleTranscript : null;
    const pm1 = pm1Relationship(relationship) ? candidate?.recommendation?.pm1CompatibleTranscript : null;
    return resource?.predictionPath === 'POPULATION_ONLY' ? missense || pm1 || resource : resource || missense || pm1;
}

export function relationshipOptions(candidate) {
    return candidate?.recommendation?.relationships || [];
}

export function pm2ContextPreparationDetails(diagnostics, saved = false) {
    if (diagnostics?.schemaVersion === 'pm2-context-preparation-v2') return [
        ['PM2 当前上下文准备', diagnostics.stage === 'POST_SUBMISSION' && diagnostics.status === 'READY'
            ? '就绪：本次声明与对应背景已通过上下文核验' : `受阻：${reasonHelp(diagnostics.reasonCode)}`],
        ['准备诊断阶段', diagnostics.stage || '未记录'],
        ['已核验背景身份', diagnostics.contextId || '未形成'],
        ['转录本核验', diagnostics.transcriptCheck?.status === 'VALIDATED_EVIDENCE_CONTEXT' ? '已通过实际上下文核验' : '尚未通过上下文核验'],
        ...(diagnostics.relationshipCheck ? [['疾病关系查找', `${diagnostics.relationshipCheck.resourceId || '未记录'} @ ${diagnostics.relationshipCheck.resourceVersion || '未记录'}；目录记录 ${diagnostics.relationshipCheck.catalogRecordCount ?? '未记录'}；可选关系 ${diagnostics.relationshipCheck.selectableRecordCount ?? '未记录'}`]] : []),
        ...(diagnostics.policyCheck ? [['PM2 政策查找', `基因记录 ${diagnostics.policyCheck.geneRecordCount ?? '未记录'}；背景匹配 ${diagnostics.policyCheck.relationshipMatchCount ?? '未记录'}；政策未命中不代表不存在疾病关系`]] : []),
    ];
    if (!diagnostics || diagnostics.schemaVersion !== 'pm2-context-preparation-v1') return [];
    const relation = diagnostics.relationshipCheck || {};
    const transcript = diagnostics.transcriptCheck || {};
    const policy = diagnostics.policyCheck || {};
    const count = (value) => Number.isInteger(value) && value >= 0 ? value : '未记录';
    return [
        [saved ? '历史准备诊断／阶段未标明' : 'PM2 上下文准备', reasonHelp(diagnostics.reasonCode)],
        ...(saved ? [['历史诊断说明', '以下为旧版准备快照，不作为当前操作待办；最终评价、上下文和实际阻断分别按保存对象展示，未补造确认凭证。']] : []),
        ['疾病关系查找', `${diagnostics.hgncId || 'HGNC 未确定'} / ${diagnostics.geneSymbol || '基因未确定'}；目录记录 ${count(relation.catalogRecordCount)}，可选关系 ${count(relation.selectableRecordCount)}`],
        ['疾病关系资源', `${relation.resourceId || '未记录'} @ ${relation.resourceVersion || '未记录'}；SHA ${relation.snapshotSha256 || '未记录'}`],
        ['转录本准备', `已完成的同基因 VEP 注释 ${count(transcript.sameGeneCompletedVepCount)}；上下文候选注释 ${count(transcript.contextAnnotationCount)}。${transcript.status === 'DISEASE_RELATIONSHIP_REQUIRED_BEFORE_CONTEXT_SELECTION' ? '先补齐可用疾病关系；此处不表示 VEP 没有转录本。' : transcript.preferredAnnotationAvailable ? '存在优先注释，仍需正常确认及来源核验。' : '尚未确定唯一有效注释，需核对版本、来源、重复及优先标记。'}`],
        ['PM2 政策查找', `当前政策中该基因记录 ${count(policy.geneRecordCount)}；疾病/遗传方式匹配 ${count(policy.relationshipMatchCount)}。政策未命中不代表不存在疾病关系。`],
        ['PM2 政策身份', policy.profileSha256 || '未记录'],
    ];
}

export function relationshipClassificationLabel(relationship) {
    if (relationship?.relationshipType === 'BS1_EXPERT_SPECIFICATION_SCOPE' &&
        relationship?.classification === 'BS1_VCEP_APPLICABLE' && relationship?.classificationIsGeneValidity === false) {
        return 'BS1 专家规范适用范围（非基因有效性评级）';
    }
    return relationship?.classification === 'VCEP_APPLICABLE' ||
        relationship?.relationshipType === 'VCEP_SPECIFICATION_SCOPE'
        ? 'VCEP 规范适用关系'
        : relationship?.classification || '未取得';
}

// Display comparison only; never chooses or changes the selected transcript.
export function hasTranscriptPreferenceDifference(candidate) {
    const rec = candidate?.recommendation;
    const preferred =
        rec?.annotationPreferredTranscript ||
        rec?.annotationPreferred ||
        rec?.clinicalPreferredTranscript;
    const compatible = rec?.resourceCompatibleTranscript;
    if (!preferred || !compatible) return false;
    if (preferred.annotationId && compatible.annotationId)
        return preferred.annotationId !== compatible.annotationId;
    return Boolean(
        preferred.versionedTranscriptId &&
            compatible.versionedTranscriptId &&
            preferred.versionedTranscriptId !== compatible.versionedTranscriptId,
    );
}

export function prepareSimplifiedInputs(options, previous = {}) {
    if (pp4ScopeClaim(options)) throw new Error('PP4 请使用独立声明入口。');
    if (bs4ScopeClaim(options)) throw new Error('BS4 请使用独立声明入口。');
    if (pp1ScopeClaim(options)) throw new Error('PP1 请使用独立声明入口。');
    if (bp2ScopeClaim(options)) throw new Error('BP2 请使用独立声明入口。');
    if (pm3ScopeClaim(options)) throw new Error('PM3 请使用固定双变异材料独立确认入口。');
    if (pm6ScopeClaim(options)) throw new Error('PM6 请使用固定材料独立确认入口。');
    if (ps2ScopeClaim(options)) throw new Error('PS2 请使用固定病例材料的独立确认入口。');
    return Object.fromEntries(
        options.candidates.map((candidate) => {
            const rec = candidate.recommendation;
            const old = previous[candidate.candidateId] || {};
            const unchanged = old.recommendationId === rec?.recommendationId &&
                (!(bp1ScopeClaim(options) || bp3ScopeClaim(options) || pp2ScopeClaim(options)) || old.publishedCandidateRawSha256 === rec?.publishedCandidateRawSha256);
            return [
                candidate.candidateId,
                {
                    recommendationId: rec?.recommendationId || '',
                    ...((bp1ScopeClaim(options) || bp3ScopeClaim(options) || pp2ScopeClaim(options)) ? { publishedCandidateRawSha256: rec?.publishedCandidateRawSha256 } : {}),
                    stableRelationshipKey: unchanged
                        ? old.stableRelationshipKey
                        : rec?.stableRelationshipKey || '',
                    annotationId: unchanged ? old.annotationId : rec?.recommendedAnnotationId || '',
                    literatureContext: unchanged ? old.literatureContext || null : null,
                    ...(deNovoLocalScope(options) ? { affectedStatus: unchanged && ['AFFECTED', 'UNAFFECTED', 'UNKNOWN'].includes(old.affectedStatus) ? old.affectedStatus : 'UNKNOWN' } : {}),
                },
            ];
        }),
    );
}

// Prepare a unique published analysis hypothesis, never a diagnosis. Multiple distinct
// disease/MOI relationships stay unresolved, even when one would yield more evidence.
export function prepareAutomaticInputs(options, previous = {}) {
    const inputs = prepareSimplifiedInputs(options, previous);
    for (const candidate of options.candidates) {
        const input = inputs[candidate.candidateId];
        if (input.literatureContext) continue;
        const choices = relationshipOptions(candidate).filter(r => r.selectable && r.stableRelationshipKey);
        const unique = [...new Map(choices.map(r => [r.stableRelationshipKey, r])).values()];
        if (!input.stableRelationshipKey && unique.length === 1)
            input.stableRelationshipKey = unique[0].stableRelationshipKey;
        const relationship = unique.find(r => r.stableRelationshipKey === input.stableRelationshipKey);
        const current = candidate.recommendation?.annotations?.find(a => a.annotationId === input.annotationId);
        if (relationship && !annotationSelectable(current, relationship)) {
            const recommended = contextTranscriptRecommendation(candidate, relationship);
            input.annotationId = recommended && annotationSelectable(recommended, relationship)
                ? recommended.annotationId : '';
        }
    }
    return inputs;
}

export function automaticBackgroundSummary(options, inputs) {
    return options.candidates.map(candidate => {
        const input = inputs[candidate.candidateId] || {};
        const choices = relationshipOptions(candidate).filter(r => r.selectable && r.stableRelationshipKey);
        const selected = choices.find(r => r.stableRelationshipKey === input.stableRelationshipKey);
        const literature = declaredLiteratureContext(candidate, input, options);
        return { candidateId: candidate.candidateId, geneSymbol: candidate.geneSymbol, rank: candidate.rank,
            status: selected || literature ? 'PREPARED' : choices.length ? 'SELECTION_REQUIRED' : 'BACKGROUND_UNAVAILABLE',
            label: selected ? `${selected.diseaseLabel || selected.mondoId} · ${selected.moi}`
                : literature ? `${literature.mondoId} · ${literature.moi}`
                : choices.length ? '存在多个疾病背景，需选择本次分析假设；未选择的相关证据会说明缺项。'
                : '当前资料未提供可选疾病背景；不能从基因或变异自动推定诊断。' };
    });
}

export function declaredLiteratureContext(candidate, input = {}, options) {
    const c = input.literatureContext;
    if (!c || !options?.enabledCriteria?.includes('ACMG_AMP_2015:PP1')) return null;
    if (c.geneSymbol !== candidate.geneSymbol || c.hgncId !== candidate.catalog?.hgncId ||
        !/^HGNC:[1-9][0-9]*$/.test(c.hgncId || '') || !/^MONDO:[0-9]{7}$/.test(c.mondoId || '') ||
        !/^[a-f0-9]{64}$/.test(c.contextId || '') || !['AD','AR','XLD','XLR','MT'].includes(c.moi) ||
        !Array.isArray(c.publishedInheritance) || (c.publishedInheritance.length && !c.publishedInheritance.includes(c.moi)) ||
        !candidate.recommendation?.executionSourceSha256) return null;
    return c;
}

export function candidateInputStatus(candidate, input = {}, options) {
    const literature = declaredLiteratureContext(candidate, input, options);
    if (literature) {
        // Transcript identity belongs to the allele/gene; a literature hypothesis
        // does not erase an existing compatible annotation or approve its disease mechanism.
        const annotation = candidate.recommendation?.annotations?.find(
            (a) => a.annotationId === input.annotationId && annotationSelectable(a, null),
        );
        const transcript = transcriptOptions(candidate).find(
            (t) => Number(t.index) === annotation?.annotationIndex && t.eligible,
        );
        return { ready: false, deNovoReady: true, ba1Ready: false, pm2Ready: false, bs1Ready: false,
            relationship: null, transcript, missing: [], literatureContext: literature,
            transcriptSelectionPolicy: predictionContextAnnotation(annotation)
                ? annotation.predictionContextSelectionPolicy : ruleContextAnnotation(annotation)
                ? annotation.ruleContextSelectionPolicy
                : `${candidate.recommendation.policyId}@${candidate.recommendation.policyVersion}` };
    }
    const relationships = relationshipOptions(candidate);
    const relationship = relationships.find(
        (r) => r.stableRelationshipKey === input.stableRelationshipKey && r.selectable,
    );
    const annotation = candidate.recommendation?.annotations?.find(
        (a) => a.annotationId === input.annotationId && annotationSelectable(a, relationship),
    );
    const transcript = transcriptOptions(candidate).find(
        (t) => Number(t.index) === annotation?.annotationIndex && t.eligible,
    );
    const missing = [];
    if (!relationship)
        missing.push(relationships.length ? 'DISEASE_RELATIONSHIP' : 'DISEASE_CATALOG');
    if (!transcript)
        missing.push(candidate.recommendation?.transcriptStatus || 'RECOMMENDATION_UNAVAILABLE');
    if (!candidate.recommendation?.executionSourceSha256) missing.push('SOURCE_NOT_READY');
    const population = candidate.recommendation?.pm2ContextPreparation;
    const pm2Ready = Boolean(
        relationship && candidate.recommendation?.executionSourceSha256 &&
        population?.available && population?.clinicalAnnotation &&
        population?.eligibleRelationshipKeys?.includes(input.stableRelationshipKey),
    );
    const ba1 = candidate.recommendation?.ba1DiseaseContextPreparation;
    const ba1Ready = Boolean(relationship && candidate.recommendation?.executionSourceSha256 &&
        ba1?.available && ba1?.clinicalAnnotation && ba1?.transcriptConfirmed === false &&
        ba1?.selectionPolicy === 'BA1_DISEASE_CONTEXT_CONFIRMED@1.0.0' &&
        ba1?.eligibleRelationshipKeys?.includes(input.stableRelationshipKey) &&
        !candidate.recommendation?.hnf1aFrequencyContextPreparation?.eligibleRelationshipKeys?.includes(input.stableRelationshipKey));
    const bs1 = candidate.recommendation?.bs1ContextPreparation;
    const bs1Policy = bs1?.policyOptions?.find((p) => p.stableRelationshipKey === input.stableRelationshipKey);
    const bs1Ready = Boolean(relationship && candidate.recommendation?.executionSourceSha256 && bs1Policy?.available &&
        bs1?.eligibleRelationshipKeys?.includes(input.stableRelationshipKey) &&
        (bs1Policy.transcriptRequirement === 'NOT_REQUIRED_BY_POLICY' || transcript));
    const deNovoReady = Boolean(deNovoLocalScope(options) && !localBatchScopeIssue(options) && relationship &&
        candidate.recommendation?.executionSourceSha256 && ['AFFECTED', 'UNAFFECTED', 'UNKNOWN'].includes(input.affectedStatus));
    return { ready: missing.length === 0, deNovoReady, pm2Ready, ba1Ready, bs1Ready, bs1Policy, relationship, transcript, missing,
        pvs1ContextOnly: Boolean(pvs1ContextAnnotation(annotation) && transcript),
        predictionContextOnly: Boolean(predictionContextAnnotation(annotation) && transcript),
        predictionContextSelectionPolicy: predictionContextAnnotation(annotation) ? annotation.predictionContextSelectionPolicy : null,
        ruleContextOnly: Boolean(ruleContextAnnotation(annotation) && transcript),
        ruleContextSelectionPolicy: ruleContextAnnotation(annotation) ? annotation.ruleContextSelectionPolicy : null,
        ps1Pm5Only: Boolean(!ruleContextAnnotation(annotation) && annotation?.ps1Pm5Compatible && (!annotation.identityCompatible || annotation.predictionPath === 'POPULATION_ONLY') && transcript),
    };
}

export function recommendationLabel(status) {
    if (status === 'PREDICTION_CONTEXT_RECOMMENDATION') return '预测评估上下文可供确认（模型与证据仍需核验）';
    return (
        {
            READY_FOR_CONFIRMATION: '系统自动确定（待整体确认）',
            AUTO_RECOMMENDED: '系统自动确定的目录关系',
            RESOURCE_COMPATIBLE_RECOMMENDATION: '计算资源兼容推荐',
            RULE_CONTEXT_RECOMMENDATION: '规则评估上下文可供确认（具体证据仍需核验）',
            PVS1_CONTEXT_RECOMMENDATION: 'PVS1 疾病转录本上下文可用（仍需核验序列、终止及 NMD）',
            PM1_TRANSCRIPT_RECOMMENDATION: 'PM1 疾病转录本上下文可用（位置与序列仍需独立核验）',
            HNF1A_REVEL_CONTEXT_RECOMMENDATION: 'HNF1A REVEL 专用转录本上下文可用（分数及资源身份在评价时核验）',
            PS1_PM5_COMPATIBLE_RECOMMENDATION: 'PS1/PM5 规范转录本可用（不代表 AM 兼容）',
            PS1_PM5_TRANSCRIPT_RECOMMENDATION: 'PS1/PM5 规范转录本可用（不代表 AM 兼容）',
            REVIEW_REQUIRED: '需要人工复核',
            NO_COMPATIBLE_TRANSCRIPT: '当前无法评价：没有精确兼容的转录本',
            IDENTITY_CHECK_UNAVAILABLE: '当前无法评价：身份资源未就绪',
            NO_ELIGIBLE_RELATIONSHIP: '当前无法评价：没有可用目录关系',
            SOURCE_NOT_READY: '当前无法评价：任务来源未就绪',
        }[status] || '推荐信息不可恢复，请重新读取候选'
    );
}

export function buildSimplifiedCaseDeclaration(
    options,
    analysisId,
    inputs,
    retryOf,
    dataClass = 'PRIVACY_BOUNDED_RUNTIME',
    caseBinding = null,
) {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    validateInputOptions(options, analysisId);
    const localBatch = localBatchScopeClaim(options);
    if ([...arguments].some(bp2ScopeClaim) || pm3ScopeClaim(options) || pm3ScopeClaim(inputs)) throw new Error('PM3 请使用固定双变异材料独立确认入口。');
    if (pm6ScopeClaim(options) || pm6ScopeClaim(inputs)) throw new Error('PM6 请使用固定材料独立确认入口。');
    if (ps2ScopeClaim(options) || ps2ScopeClaim(inputs)) throw new Error('PS2 请使用固定病例材料的独立确认入口。');
    const bp1Issue = bp1ScopeIssue(options);
    if (bp1Issue) throw new Error(bp1Issue);
    const bp1 = bp1ScopeClaim(options);
    const pp2Issue = pp2ScopeIssue(options);
    if (pp2Issue) throw new Error(pp2Issue);
    const pp2 = pp2ScopeClaim(options);
    const bp3Issue = bp3ScopeIssue(options);
    if (bp3Issue) throw new Error(bp3Issue);
    const bp3 = bp3ScopeClaim(options);
    const pvs1Issue = pvs1NmdScopeIssue(options);
    if (pvs1Issue) throw new Error(pvs1Issue);
    const pvs1 = pvs1NmdScopeClaim(options);
    const pm1Issue = pm1ScopeIssue(options);
    if (pm1Issue) throw new Error(pm1Issue);
    const pm1 = pm1ScopeClaim(options);
    const pm4Issue = pm4ScopeIssue(options);
    if (pm4Issue) throw new Error(pm4Issue);
    const pm4 = pm4ScopeClaim(options);
    const ps1Issue = ps1ScopeIssue(options);
    if (ps1Issue) throw new Error(ps1Issue);
    const ps1 = ps1ScopeClaim(options);
    const pm5Issue = pm5ScopeIssue(options);
    if (pm5Issue) throw new Error(pm5Issue);
    const pm5 = pm5ScopeClaim(options);
    const missenseCode = pm5 ? 'PM5' : ps1 ? 'PS1' : null;
    const prepared = {};
    let readyCount = 0;
    options.candidates.forEach((candidate) => {
        const input = inputs[candidate.candidateId] || {};
        if (localBatch && candidate.recommendation && (input.recommendationId !== candidate.recommendation.recommendationId ||
            (input.annotationId && !candidate.recommendation.annotations?.some((a) => a.annotationId === input.annotationId))))
            throw new Error('本地证据确认与当前推荐身份不一致，请重新读取并确认。');
        if ((bp1 || pp2 || bp3 || pm1 || pvs1) && (input.recommendationId !== candidate.recommendation?.recommendationId ||
            (input.annotationId && !candidate.recommendation.annotations.some((a) => a.annotationId === input.annotationId))))
            throw new Error(`${bp1 ? 'BP1' : pp2 ? 'PP2' : bp3 ? 'BP3' : pvs1 ? 'PVS1' : 'PM1'} 确认与当前候选推荐不一致；请重新读取并确认。`);
        const status = candidateInputStatus(candidate, input, options);
        if (input.literatureContext) {
            const c = declaredLiteratureContext(candidate, input, options);
            if (!c || !['AFFECTED','UNAFFECTED','UNKNOWN'].includes(input.affectedStatus))
                throw new Error('请完整选择文献分析的疾病背景、遗传方式及当前病例状态。');
            prepared[candidate.candidateId] = [{ hypothesisId: candidate.recommendation.hypothesisId,
                hgncId: c.hgncId, mondoId: c.mondoId, moi: c.moi, affectedStatus: input.affectedStatus,
                transcriptIndex: status.transcript?.index ?? '',
                selectionPolicy: status.transcriptSelectionPolicy,
                selectionReason: status.transcript ? 'Reuse the exact existing allele/gene transcript annotation for this independent literature hypothesis; disease applicability remains separately verified.' : undefined,
                recordLocator: status.transcript ? `recommendations/${candidate.recommendation.recommendationId}/selection`
                    : `literature-contexts/${c.contextId}/explicit-selection` }];
            readyCount += 1;
            return;
        }
        if (deNovoLocalScope(options) && !['AFFECTED', 'UNAFFECTED', 'UNKNOWN'].includes(input.affectedStatus))
            throw new Error('请明确当前病例对所选疾病的患病状态；资料未知时保留未知，不自动推定患病。');
        if (!status.ready && !status.deNovoReady) {
            prepared[candidate.candidateId] = [];
            if (!bp1 && !pp2 && !bp3 && !pvs1 && !pm1 && !pm4 && !missenseCode && (status.pm2Ready || status.ba1Ready || status.bs1Ready)) readyCount += 1;
            return;
        }
        if (status.deNovoReady && !status.transcript) {
            const r = status.relationship, rec = candidate.recommendation;
            if (r.hgncId !== candidate.catalog?.hgncId || !/^HGNC:[0-9]+$/.test(r.hgncId || '') || !/^MONDO:[0-9]{7}$/.test(r.mondoId || '') || !r.moi)
                throw new Error(`第 ${candidate.rank} 条候选的疾病目录关系不完整，请重新读取任务。`);
            prepared[candidate.candidateId] = [{ hypothesisId: rec.hypothesisId, hgncId: r.hgncId, mondoId: r.mondoId,
                moi: r.moi, affectedStatus: input.affectedStatus, transcriptIndex: '',
                recordLocator: `recommendations/${rec.recommendationId}/selection` }];
            readyCount += 1;
            return;
        }
        if (missenseCode && (!status.ruleContextOnly || !MISSENSE_CONTEXT_POLICIES[missenseCode].test(status.ruleContextSelectionPolicy || '')))
            throw new Error(`${missenseCode} 提交必须使用当前注释的完整 v${pm5 ? 4 : 3} 规则上下文；请重新读取候选并确认。`);
        if ((pm1 && (!status.ruleContextOnly || !PM1_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || ''))) ||
            (!pm1 && PM1_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || '')))
            throw new Error('PM1 v5 转录本确认只能用于当前通用 PM1 声明，不能跨证据范围提交。');
        if ((pvs1 && (!status.ruleContextOnly || !PVS1_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || ''))) ||
            (!pvs1 && PVS1_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || '')))
            throw new Error('PVS1 v6 转录本确认只能用于当前 NMD 声明，不能跨证据范围提交。');
        if ((bp3 && (!status.ruleContextOnly || !BP3_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || ''))) ||
            (!bp3 && BP3_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || '')))
            throw new Error('BP3 v7 转录本确认只能用于当前框内 indel 声明，不能跨证据范围提交。');
        if ((bp1 && (!status.ruleContextOnly || !BP1_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || ''))) ||
            (!bp1 && BP1_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || '')))
            throw new Error('BP1 v9 转录本确认只能用于当前截短主导机制与变异适用声明，不能跨证据范围提交。');
        if (bp1 && input.publishedCandidateRawSha256 !== candidate.recommendation.publishedCandidateRawSha256)
            throw new Error('BP1 已发布候选身份已变化；请重新读取并确认。');
        if ((pp2 && (!status.ruleContextOnly || !PP2_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || ''))) ||
            (!pp2 && PP2_CONTEXT_POLICY.test(status.ruleContextSelectionPolicy || '')))
            throw new Error('PP2 v8 转录本确认只能用于当前错义机制与变异谱声明，不能跨证据范围提交。');
        if (pp2 && input.publishedCandidateRawSha256 !== candidate.recommendation.publishedCandidateRawSha256)
            throw new Error('PP2 已发布候选身份已变化；请重新读取并确认。');
        if (bp3 && input.publishedCandidateRawSha256 !== candidate.recommendation.publishedCandidateRawSha256)
            throw new Error('BP3 已发布候选身份已变化；请重新读取并确认。');
        const { relationship, transcript } = status;
        if (
            relationship.hgncId !== candidate.catalog?.hgncId ||
            !/^HGNC:[0-9]+$/.test(relationship.hgncId || '') ||
            !/^MONDO:[0-9]{7}$/.test(relationship.mondoId || '') ||
            !relationship.moi
        )
            throw new Error(`第 ${candidate.rank} 条候选的疾病目录关系不完整，请重新读取任务。`);
        const rec = candidate.recommendation;
        prepared[candidate.candidateId] = [
            {
                hypothesisId: candidate.recommendation.hypothesisId,
                hgncId: relationship.hgncId,
                mondoId: relationship.mondoId,
                moi: relationship.moi,
                affectedStatus: deNovoLocalScope(options) ? input.affectedStatus : 'UNKNOWN',
                transcriptIndex: transcript.index,
                selectionPolicy: pm1 || missenseCode ? status.ruleContextSelectionPolicy
                    : status.predictionContextOnly ? status.predictionContextSelectionPolicy
                    : status.ruleContextOnly ? status.ruleContextSelectionPolicy : `${rec.policyId}@${rec.policyVersion}`,
                selectionReason: bp1
                    ? `User confirmed disease relationship, MOI and exact versioned transcript ${transcript.versionedId} for limited BP1 missense evaluation. Sequence, reviewed truncating mechanism, exact variant exceptions and splicing applicability remain verified by the server; no scientific adoption or final classification is authorized.`
                    : pp2
                    ? `User confirmed disease relationship, MOI and exact versioned transcript ${transcript.versionedId} for limited PP2 missense evaluation. Sequence, reviewed missense mechanism and low benign missense background remain verified by the server; no scientific adoption or final classification is authorized.`
                    : bp3
                    ? `User confirmed disease relationship, MOI and exact versioned transcript ${transcript.versionedId} for limited BP3 inframe repeat evaluation. Sequence, complete equivalent edit envelope and reviewed functional applicability remain verified by the server; no scientific adoption or final classification is authorized.`
                    : pvs1
                    ? `User confirmed disease relationship, MOI and exact versioned transcript ${transcript.versionedId} for limited PVS1 ${options.executionProfile.reviewedRuleBinding.algorithms['transcript-truncating-nmd-pvs1'] === '1.0.4' ? 'NMD' : 'NMD and explicitly scoped escape'} evaluation. Sequence, LoF mechanism, exon scope and policy remain verified by the server; no scientific adoption or final classification is authorized.`
                    : pm1
                    ? `User confirmed disease relationship, MOI and exact versioned transcript ${transcript.versionedId} for limited PM1 reviewed protein region evaluation. Model, region, qualification and policy remain verified by the server; no scientific adoption or final classification is authorized.`
                    : pm5
                    ? `User confirmed disease relationship, MOI and exact versioned transcript ${transcript.versionedId} for limited PM5 same-residue different-substitution comparison. Reference independence, matrix or reviewed relationship and policy remain verified by the server; no scientific adoption or final classification is authorized.`
                    : ps1
                    ? `User confirmed disease relationship, MOI and exact versioned transcript ${transcript.versionedId} for limited PS1 protein substitution comparison. Reference eligibility, independence, splice evidence and policy remain verified by the server; no scientific adoption or final classification is authorized.`
                    : pm4
                    ? `User confirmed disease relationship, MOI and exact versioned transcript ${transcript.versionedId} for limited PM4 protein length change evaluation. Model, region and policy facts remain independently verified; no scientific adoption or final classification is authorized.`
                    : localBatch
                    ? `User confirmed the existing VEP gene, disease, MOI and versioned transcript ${transcript.versionedId} for the explicitly selected local evidence scope (${localBatchCriteriaText(options)}). Resource, policy and reference eligibility remain independently checked; this confirmation grants neither scientific adoption nor final classification.`
                    : currentBusinessCriteria(options).length
                    ? `User confirmed the existing VEP gene, disease, MOI and versioned transcript ${transcript.versionedId} for ${options.executionProfile?.profileId === 'acmg-revel-pp3-application-v1' ? 'limited REVEL PP3' : 'population evidence'}. Policy, source and applicability remain independently checked. Policy ${rec.policyId}@${rec.policyVersion}.`
                    : status.predictionContextOnly
                    ? `User confirmed the persisted VEP gene, disease, MOI and exact versioned transcript context ${transcript.versionedId}. Predictor policies and resources remain unverified; no predictor score or identity lookup was used for this confirmation. Policy ${rec.policyId}@${rec.policyVersion}.`
                    : status.ruleContextOnly
                    ? `User confirmed gene, disease, MOI and exact versioned rule transcript context ${transcript.versionedId}; each criterion still requires independent policy, resource and evidence verification. No computational compatibility or loss-of-function mechanism was asserted. Policy ${rec.policyId}@${rec.policyVersion}.`
                    : status.pvs1ContextOnly
                    ? `User confirmed PVS1 disease transcript context ${transcript.versionedId}; sequence, termination and NMD facts require independent verification. Computational prediction compatibility was not established. No score/class was consulted. Policy ${rec.policyId}@${rec.policyVersion}.`
                    : status.ps1Pm5Only
                    ? `User confirmed PS1/PM5 policy compatible transcript ${transcript.versionedId} for the selected VCEP disease hypothesis; AlphaMissense compatibility was not established. No score/class was consulted. Policy ${rec.policyId}@${rec.policyVersion}.`
                    : `User confirmed resource identity compatible transcript ${transcript.versionedId}; computational resource compatibility, not a clinical transcript conclusion. No score/class was consulted. Policy ${rec.policyId}@${rec.policyVersion}.`,
                recordLocator: `recommendations/${rec.recommendationId}/selection`,
            },
        ];
        readyCount += 1;
    });
    if (!readyCount && !localBatch && !enabledCriteria(options).includes('ACMG_AMP_2015:BA1'))
        throw new Error('尚无可提交候选，请先完成至少一条候选的疾病关系和转录本选择。');
    const form = automaticCaseForm(analysisId, dataClass, [FOURTEEN_DECLARATION, ELEVEN_DECLARATION, UNIFIED_DECLARATION, LOCAL_BATCH_DECLARATION, 'ordinary-bp1-missense-v1', 'ordinary-pp2-missense-v1', 'ordinary-bp3-inframe-v1', 'ordinary-pvs1-nmd-v1', 'ordinary-ba1-pm2-bs1-v1', 'ordinary-pp3-bp4-development-v1', 'ordinary-revel-pp3-v1', 'ordinary-pm1-region-v1', 'ordinary-pm4-inframe-v1', 'ordinary-ps1-missense-v1', 'ordinary-pm5-missense-v1'].includes(options.executionProfile?.requiredDeclaration)
        ? options.executionProfile.requiredDeclaration : 'ordinary-ui-v3');
    if (caseBinding !== null) {
        if (!deNovoLocalScope(options) || !localBatchObject(caseBinding) ||
            Object.keys(caseBinding).sort().join(',') !== 'analysisId,familyContextId,materialSha256,patientContextId' ||
            caseBinding.analysisId !== analysisId || caseBinding.materialSha256 !== options.executionProfile?.moduleBindings?.deNovo?.manifestSha256 ||
            !/^patient-context-[a-z0-9][a-z0-9._-]{7,95}$/.test(caseBinding.patientContextId || '') ||
            !/^family-context-[a-z0-9][a-z0-9._-]{7,95}$/.test(caseBinding.familyContextId || ''))
            throw new Error('当前病例材料绑定不完整或已过期；请填写原报告的隐私病例和家系标识，并重新确认。');
        form.patientContextId = caseBinding.patientContextId;
        form.familyContextId = caseBinding.familyContextId;
        form.recordLocator = 'de-novo/current-case-binding';
    }
    const declaration = buildCaseDeclaration(
        options,
        analysisId,
        form,
        prepared,
        retryOf,
    );
    declaration.recommendations = options.candidates.map((candidate) => {
        const rec = candidate.recommendation;
        if (!rec) throw new Error('推荐信息缺失，请重新读取候选。');
        const input = inputs[candidate.candidateId] || {};
        return {
            candidateId: candidate.candidateId,
            recommendationId: rec.recommendationId,
            policyVersion: rec.policyVersion,
            policyHash: rec.policyHash,
            generatedAt: rec.generatedAt,
            stableRelationshipKey: input.literatureContext ? null : input.stableRelationshipKey || null,
            annotationId: input.annotationId || null,
        };
    });
    return declaration;
}

const source = (recordLocator) => ({
    source: 'EXPLICIT_USER_DECLARATION',
    version: '1.0.0',
    recordLocator,
});
const locator = (value) => {
    if (
        !value?.trim() ||
        Array.from(value).some((c) => c.charCodeAt(0) < 32) ||
        /^(?:[A-Za-z]:|[\\/])/.test(value) ||
        value.includes('..') ||
        (/^[a-z]+:/i.test(value) && !value.startsWith('https://'))
    )
        throw new Error(
            '请提供声明来源的相对记录引用或 HTTPS 地址，不填写姓名、完整病例或机器路径。',
        );
    return value.trim();
};

export function buildCaseDeclaration(options, analysisId, form, inputs, retryOf) {
    if (localBatchScopeClaim(options) ? form.declarationVersion !== batchDeclarationFor(options)
        : [LOCAL_BATCH_DECLARATION, UNIFIED_DECLARATION, ELEVEN_DECLARATION, FOURTEEN_DECLARATION].includes(form.declarationVersion))
        throw new Error('本地证据声明必须与当前独立执行范围一致，不能借用其他模式提交。');
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    if (bp2ScopeClaim(options) || bp2ScopeClaim(inputs) || pm3ScopeClaim(options) || pm3ScopeClaim(inputs) || pm3ScopeClaim(form)) throw new Error('PM3 请使用固定双变异材料独立确认入口。');
    if (pm6ScopeClaim(options) || pm6ScopeClaim(inputs) || pm6ScopeClaim(form)) throw new Error('PM6 不开放高级声明编辑。');
    if (ps2ScopeClaim(options) || ps2ScopeClaim(inputs) || ps2ScopeClaim(form)) throw new Error('PS2 不开放高级声明编辑；请使用独立确认入口。');
    validateInputOptions(options, analysisId);
    const bp1 = bp1ScopeClaim(options), bp1Issue = bp1ScopeIssue(options);
    if (bp1Issue) throw new Error(bp1Issue);
    if ((bp1 && form.declarationVersion !== 'ordinary-bp1-missense-v1') ||
        (!bp1 && form.declarationVersion === 'ordinary-bp1-missense-v1'))
        throw new Error('BP1 声明必须与当前完整截短主导机制与变异适用身份一致。');
    const pp2 = pp2ScopeClaim(options), pp2Issue = pp2ScopeIssue(options);
    if (pp2Issue) throw new Error(pp2Issue);
    if ((pp2 && form.declarationVersion !== 'ordinary-pp2-missense-v1') ||
        (!pp2 && form.declarationVersion === 'ordinary-pp2-missense-v1'))
        throw new Error('PP2 声明必须与当前完整错义机制与变异谱身份一致。');
    const bp3 = bp3ScopeClaim(options), bp3Issue = bp3ScopeIssue(options);
    if (bp3Issue) throw new Error(bp3Issue);
    if ((bp3 && form.declarationVersion !== 'ordinary-bp3-inframe-v1') ||
        (!bp3 && form.declarationVersion === 'ordinary-bp3-inframe-v1'))
        throw new Error('BP3 声明必须与当前完整框内 indel 身份一致。');
    const pvs1 = pvs1NmdScopeClaim(options), pvs1Issue = pvs1NmdScopeIssue(options);
    if (pvs1Issue) throw new Error(pvs1Issue);
    if ((pvs1 && form.declarationVersion !== 'ordinary-pvs1-nmd-v1') ||
        (!pvs1 && form.declarationVersion === 'ordinary-pvs1-nmd-v1'))
        throw new Error('PVS1 声明必须与当前完整 NMD 身份一致。');
    const pm1 = pm1ScopeClaim(options), pm1Issue = pm1ScopeIssue(options);
    if (pm1Issue) throw new Error(pm1Issue);
    if ((pm1 && form.declarationVersion !== 'ordinary-pm1-region-v1') ||
        (!pm1 && form.declarationVersion === 'ordinary-pm1-region-v1'))
        throw new Error('PM1 声明必须与当前完整区域评价身份一致。');
    if (!options.sampleId || !options.candidates.length)
        throw new Error('任务样本或正式候选缺失。');
    if (!/^[A-Za-z0-9_.-]{1,80}$/.test(form.declarationVersion || ''))
        throw new Error('声明版本仅允许字母、数字、点、下划线或连字符。');
    if (
        !/^patient-context-[a-z0-9][a-z0-9._-]{7,95}$/.test(form.patientContextId || '') ||
        !['MALE', 'FEMALE', 'UNKNOWN'].includes(form.sex)
    )
        throw new Error(
            '请生成或填写合法的 patient-context- 隐私标识，并明确选择性别（可选 UNKNOWN）。',
        );
    if (
        form.familyContextId &&
        !/^family-context-[a-z0-9][a-z0-9._-]{7,95}$/.test(form.familyContextId)
    )
        throw new Error(
            '家系标识需为 family-context- 加 8–96 位小写字母、数字、点、下划线或连字符；singleton 可留空。',
        );
    if (!['PRIVACY_BOUNDED_RUNTIME', 'SYNTHETIC'].includes(form.dataClass))
        throw new Error('请选择输入类别。');
    const patientSource = source(locator(form.recordLocator));
    const hypotheses = options.candidates.flatMap((candidate) => {
        const declared = inputs[candidate.candidateId] || [];
        const used = new Set();
        return declared
            .filter(
                (h) =>
                    h.mondoId ||
                    h.moi ||
                    h.affectedStatus ||
                    h.transcriptIndex !== '' ||
                    h.recordLocator,
            )
            .map((h) => {
                if (!h.hypothesisId?.trim() || used.has(h.hypothesisId))
                    throw new Error(`第 ${candidate.rank} 条候选的假设标识缺失或重复。`);
                used.add(h.hypothesisId);
                if (
                    !/^HGNC:[0-9]+$/.test(h.hgncId) ||
                    !/^MONDO:[0-9]{7}$/.test(h.mondoId) ||
                    !h.moi ||
                    !['AFFECTED', 'UNAFFECTED', 'UNKNOWN'].includes(h.affectedStatus)
                )
                    throw new Error(
                        `第 ${candidate.rank} 条候选需要完整的 HGNC / MONDO、遗传模式和疾病患病状态。`,
                    );
                const tx = transcriptOptions(candidate).find(
                    (t) => t.index === h.transcriptIndex && t.eligible,
                );
                if ((pm1 && (!tx || !candidate.recommendation.annotations.some((a) =>
                    ruleContextAnnotation(a) && PM1_CONTEXT_POLICY.test(h.selectionPolicy || '') &&
                    a.ruleContextSelectionPolicy === h.selectionPolicy && a.versionedTranscriptId === tx.versionedId))) ||
                    (!pm1 && PM1_CONTEXT_POLICY.test(h.selectionPolicy || '')))
                    throw new Error('PM1 v5 转录本声明不得跨候选或借用其他规则身份。');
                if ((pvs1 && (h.hypothesisId !== candidate.recommendation.hypothesisId ||
                    h.hgncId !== candidate.catalog?.hgncId ||
                    !relationshipOptions(candidate).some((r) => r.selectable && r.hgncId === h.hgncId &&
                        r.mondoId === h.mondoId && r.moi === h.moi) ||
                    !tx || !candidate.recommendation.annotations.some((a) =>
                    ruleContextAnnotation(a) && PVS1_CONTEXT_POLICY.test(h.selectionPolicy || '') &&
                    a.ruleContextSelectionPolicy === h.selectionPolicy && a.versionedTranscriptId === tx.versionedId))) ||
                    (!pvs1 && PVS1_CONTEXT_POLICY.test(h.selectionPolicy || '')))
                    throw new Error('PVS1 v6 转录本声明不得跨候选或借用其他规则身份。');
                if ((bp1 && (h.hypothesisId !== candidate.recommendation.hypothesisId ||
                    h.hgncId !== candidate.catalog?.hgncId ||
                    !relationshipOptions(candidate).some((r) => r.selectable && r.hgncId === h.hgncId &&
                        r.mondoId === h.mondoId && r.moi === h.moi) ||
                    !tx || !candidate.recommendation.annotations.some((a) =>
                    ruleContextAnnotation(a) && BP1_CONTEXT_POLICY.test(h.selectionPolicy || '') &&
                    a.ruleContextSelectionPolicy === h.selectionPolicy && a.versionedTranscriptId === tx.versionedId && a.annotationIndex === Number(tx.index) &&
                    a.annotationSource === tx.annotation.source && a.annotationSourceVersion === tx.annotation.version))) ||
                    (!bp1 && BP1_CONTEXT_POLICY.test(h.selectionPolicy || '')))
                    throw new Error('BP1 v9 转录本声明不得跨候选或借用其他规则身份。');
                if ((pp2 && (h.hypothesisId !== candidate.recommendation.hypothesisId ||
                    h.hgncId !== candidate.catalog?.hgncId ||
                    !relationshipOptions(candidate).some((r) => r.selectable && r.hgncId === h.hgncId &&
                        r.mondoId === h.mondoId && r.moi === h.moi) ||
                    !tx || !candidate.recommendation.annotations.some((a) =>
                    ruleContextAnnotation(a) && PP2_CONTEXT_POLICY.test(h.selectionPolicy || '') &&
                    a.ruleContextSelectionPolicy === h.selectionPolicy && a.versionedTranscriptId === tx.versionedId && a.annotationIndex === Number(tx.index) &&
                    a.annotationSource === tx.annotation.source && a.annotationSourceVersion === tx.annotation.version))) ||
                    (!pp2 && PP2_CONTEXT_POLICY.test(h.selectionPolicy || '')))
                    throw new Error('PP2 v8 转录本声明不得跨候选或借用其他规则身份。');
                if ((bp3 && (h.hypothesisId !== candidate.recommendation.hypothesisId ||
                    h.hgncId !== candidate.catalog?.hgncId ||
                    !relationshipOptions(candidate).some((r) => r.selectable && r.hgncId === h.hgncId &&
                        r.mondoId === h.mondoId && r.moi === h.moi) ||
                    !tx || !candidate.recommendation.annotations.some((a) =>
                    ruleContextAnnotation(a) && BP3_CONTEXT_POLICY.test(h.selectionPolicy || '') &&
                    a.ruleContextSelectionPolicy === h.selectionPolicy && a.versionedTranscriptId === tx.versionedId && a.annotationIndex === Number(tx.index) &&
                    a.annotationSource === tx.annotation.source && a.annotationSourceVersion === tx.annotation.version))) ||
                    (!bp3 && BP3_CONTEXT_POLICY.test(h.selectionPolicy || '')))
                    throw new Error('BP3 v7 转录本声明不得跨候选或借用其他规则身份。');
                // Explicit null is kept as a missing input, never auto-select another transcript.
                if (h.transcriptIndex !== '' && !tx)
                    throw new Error(
                        `第 ${candidate.rank} 条候选的注释版本、来源或唯一性不满足要求。`,
                    );
                if (tx && !h.selectionReason?.trim())
                    throw new Error(`请说明第 ${candidate.rank} 条候选的转录本选择依据。`);
                const declaredSource = source(locator(h.recordLocator));
                return {
                    hypothesis: {
                        candidateId: candidate.candidateId,
                        hypothesisId: h.hypothesisId,
                        hgncId: h.hgncId,
                        mondoId: h.mondoId,
                        moi: h.moi,
                        provenance: declaredSource,
                    },
                    affectedStatus: h.affectedStatus,
                    transcript: tx
                        ? {
                              accession: tx.accession,
                              version: tx.version,
                              selectionPolicy:
                                  h.selectionPolicy || 'EXPLICIT_USER_CONFIRMED_ANNOTATION_V1',
                              annotationSource: tx.annotation.source,
                              annotationSourceVersion: tx.annotation.version,
                              selectionReason: h.selectionReason.trim(),
                              source: declaredSource,
                          }
                        : null,
                };
            });
    });
    if (bp1 && !hypotheses.length) throw new Error('BP1 至少需要一条完整且有效的背景与转录本声明。');
    if (pp2 && !hypotheses.length) throw new Error('PP2 至少需要一条完整且有效的背景与转录本声明。');
    if (bp3 && !hypotheses.length) throw new Error('BP3 至少需要一条完整且有效的背景与转录本声明。');
    if (pvs1 && !hypotheses.length) throw new Error('PVS1 至少需要一条完整且有效的背景与转录本声明。');
    if (hypotheses.length > 200) throw new Error('每次最多提交 200 个明确假设。');
    return {
        topN: options.requestedN,
        declarationVersion: form.declarationVersion,
        ...(retryOf ? { retryOf } : {}),
        sampleId: options.sampleId,
        dataClass: form.dataClass,
        patient: {
            patientContextId: form.patientContextId.trim(),
            role: 'PROBAND',
            sex: form.sex,
            familyContextId: form.familyContextId?.trim() || null,
            source: patientSource,
        },
        hypotheses,
    };
}

export function validateSavedRun(run, analysisId) {
    if (run?.analysisId !== analysisId) throw new Error('保存结果与当前分析不对应。');
    if (run.candidates) {
        const ids = run.candidateIds;
        if (
            !Array.isArray(ids) ||
            ids.length !== run.candidates.length ||
            new Set(ids).size !== ids.length ||
            run.candidates.some((r, i) => !r.candidateId || r.candidateId !== ids[i])
        )
            throw new Error('保存结果的候选身份或顺序异常，请核查服务端来源。');
    }
    return run;
}

export function acmgRequestError(error) {
    const message = error?.response?.data?.message || error?.message || '';
    if (message === 'ACMG_DATABASE_BUSY') {
        return '证据数据库等待锁超时。请先读取保存结果，排除数据库阻塞后再重试。';
    }
    if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') {
        return '请求等待超时，后台可能仍在运行。请先读取保存结果，避免重复评价。';
    }
    return message || '请求失败，请检查服务。';
}

export function ba1MappingDetails(mapping) {
    if (!mapping) return [['基因映射诊断', '此保存结果未包含映射诊断。']];
    const failure = mapping.failure;
    const sources = Array.isArray(mapping.sources) ? mapping.sources : [];
    const details = [['基因映射范围', '核查该候选全部转录本注释，包括重叠基因；局部成功不代表完整通过。']];
    if (!mapping.diagnosticVersion) {
        details.push(['基因映射诊断', '历史结果未保存具体失败位置；不能从局部成功记录推断失败项。']);
        return details;
    }
    details.push(['基因映射进度', `共 ${mapping.totalAnnotationCount ?? '未知'} 条；已检查 ${mapping.checkedAnnotationCount ?? '未知'} 条；成功 ${sources.length} 条；尚未检查 ${mapping.uncheckedAnnotationCount ?? '未知'} 条`]);
    if (failure) {
        const show = (value) => value === null || value === undefined || value === '' ? '缺失' : String(value);
        const matches = (value) => Array.isArray(value) ? value.join('、') || '无匹配' : '未执行';
        details.push(
            ['映射失败位置', Number.isInteger(failure.annotationIndex) && failure.annotationIndex >= 0
                ? `第 ${failure.annotationIndex + 1} 条注释（索引 ${failure.annotationIndex}）` : '注释检查前置条件'],
            ['具体映射原因', `${reasonHelp(failure.reasonCode)}（${failure.reasonCode}）`],
            ['失败项标识', `geneId ${show(failure.geneId)}；geneSymbol ${show(failure.geneSymbol)}`],
            ['失败项注释', `转录本 ${show(failure.transcriptId)}；后果 ${show(failure.consequence)}`],
            ['实际检查字段', `${show(failure.field)}；来源 ${show(failure.source)} / ${show(failure.version)}；状态 ${show(failure.status)}`],
            ['映射查找结果', `Ensembl → HGNC：${matches(failure.ensemblMatches)}；symbol → HGNC：${matches(failure.symbolMatches)}`],
            ['补充要求', '补齐失败项的可靠身份映射或核实冲突；映射通过后仍须独立满足下方质量、频率及其他条件。'],
        );
    }
    return details;
}

export function reasonHelp(code = '', scope, strict = false) {
    if (code === 'PVS1_START_LOSS_NON_ATG_REFERENCE_UNSUPPORTED') return '参考转录本使用非 ATG 起始，本分支尚未实现这种翻译机制，不按阴性处理。';
    if (code === 'PVS1_START_LOSS_COMBINED_MECHANISM_CONFLICT') return '起始丢失同时存在其他编码或剪接作用路径，组合机制尚未协调，未取最高强度或重复计分。';
    if (code === 'PVS1_START_LOSS_SPLICE_MATERIAL_UNRESOLVED') return '同时提供的剪接材料未通过身份或唯一性核验，暂不能独立采用起始丢失结论。';
    if (code === 'PVS1_START_LOSS_POLICY_SELECTION_IDENTITY_INVALID') return '所选起始丢失规则与规则选择回执不一致，未执行评分。';
    if (code === 'PVS1_START_LOSS_MODEL_BINDING_MISMATCH') return '起始丢失规则绑定的转录本模型与本次序列不同，未采用。';
    if (code.startsWith('PVS1_START_LOSS_RUNTIME_')) {
        const reasons = {
            MATERIAL_MISSING: '起始丢失所需的来源材料尚未准备，未评分。',
            MATERIAL_BINDING_MISMATCH: '起始丢失材料与已选择规则绑定的内容不一致，未采用。',
            MATERIAL_NOT_ELIGIBLE: '当前材料仍是候选或不符合可执行材料合同，未采用。',
            CONTEXT_MISMATCH: '材料与本次变异、疾病或转录本背景不一致。',
            POLICY_MISMATCH: '材料绑定的判定规则与当前选择不同，未混用。',
            SEQUENCE_MISMATCH: '材料中的起始密码子或下游候选位置与本次序列重建不一致。',
            SOURCE_REQUIREMENTS_MISSING: '所选规则没有给出完整的核查来源范围，不能将空结果作为否定依据。',
            SOURCE_BINDING_INVALID: '核查来源的身份或审核依据不完整。',
            SOURCE_UNAVAILABLE: '规则要求的来源原件尚不可用，不能按没有参考处理。',
            SOURCE_BYTES_INVALID: '来源原件的内容校验或格式检查未通过，未采用。',
            SOURCE_FACT_BINDING_MISMATCH: '来源事实与所选背景、序列或审核记录不一致。',
            TRANSCRIPT_REVIEW_AMBIGUOUS: '存在多份未协调的替代转录本审核，暂不评分。',
            TRANSCRIPT_REVIEW_INCOMPLETE: '尚未完整核查规定转录本集合的功能相关性与替代起始情况。',
            TRANSCRIPT_SET_MISMATCH: '实际核查的转录本集合与规则要求不一致。',
            RESTART_UNRESOLVED: '尚不能确定下游潜在同框起始位点，未推断完整功能丧失。',
            UPSTREAM_REVIEW_INCOMPLETE: '上游区间或规定来源核查不完整，未使用 Supporting 否定分支。',
            REFERENCE_BACKGROUND_UNRESOLVED: '参考记录尚未精确对应当前基因、疾病和遗传方式。',
            REFERENCE_MAPPING_UNRESOLVED: '参考变异未能完成当前转录本的精确序列与区间核对。',
            REFERENCE_STATUS_UNRESOLVED: '参考记录的分类、撤回或冲突状态尚未明确。',
            REFERENCE_CONFLICT: '上游参考记录存在分类冲突，保留冲突且不评分。',
            REFERENCE_INDEPENDENCE_UNRESOLVED: '参考的致病依据尚未证明独立于本次起始丢失推断，避免循环论证。'
        };
        const suffix = code.slice('PVS1_START_LOSS_RUNTIME_'.length);
        if (Object.hasOwn(reasons, suffix)) return reasons[suffix];
    }
    if (evidenceReasonDefinitions[code]) {
        const name = ['PS1', 'PM5', 'PM1', 'BS1'].includes(scope) ? `（${scope}）` : '';
        return evidenceReasonDefinitions[code].summary + (code === 'CLAUSE_SEARCH_SCOPE_NOT_COVERED' ? name : '');
    }
    // Saved prose is already an explanation; words such as SNV/REVEL are not error codes.
    if (typeof code === 'string' && code && !/^[A-Z][A-Z0-9_]*$/.test(code)) return code;
    if (scope === 'BP3_INFRAME_V7' && code === 'PM4_COMPLEX_ALLELE_UNSUPPORTED')
        return '本次变异超出 BP3 共用序列处理模块支持的简单框内插入/缺失范围，属于当前软件范围限制；尚未据此判定区域或功能条件。';
    const sharedReasons = {
        DENOVO_MODEL_ARTIFACT_NOT_AVAILABLE: '这份原文尚无可用的模型提取产物；仍检查原有结构化资料，不会在读取结果时自动付费调用。',
        DENOVO_MODEL_CITATION_OR_VALUE_INVALID: '模型引用或提取值与固定原文不一致，相关事实未采用。',
        DENOVO_MODEL_CASE_ATTRIBUTION_UNRESOLVED: '无法确认模型引用属于这个病例，不能借用其他病例的资料。',
        DENOVO_MODEL_CASE_COVERAGE_INCOMPLETE: '模型漏掉原文中的病例，无法排除漏提或重复事件，本轮不采用这些模型候选。',
        DENOVO_MODEL_PHENOTYPE_SUBJECT_UNRESOLVED: '尚不能核实所提表型属于先证者；亲属的表型不能替代先证者。',
        DENOVO_MODEL_NATIVE_DISAGREEMENT: '模型提取与原文表格中的对应字段冲突，相关事实未采用。',
        DENOVO_MODEL_NO_EXTRACTED_CASES: '模型没有提取出可归属的病例；这不等于文献已经证明没有病例。',
        DENOVO_MODEL_INDEX_PIN_MISMATCH: '模型产物目录发生变化，当前结果未采用未绑定的资料。',
        DENOVO_MODEL_ARTIFACT_PIN_MISMATCH: '模型提取文件与登记摘要不一致，不能复用。',
        DENOVO_MODEL_ARTIFACT_SOURCE_OR_IMPLEMENTATION_CHANGED: '模型产物与当前原文或处理版本不相容，需要重新准备相容产物。',
        DENOVO_MODEL_SYNTHETIC_ARTIFACT_NOT_RESEARCH: '合成模型响应仅用于工程测试，不能作为真实科研提取结果采用。',
        DENOVO_QUALIFIED_POINTS: '合格且去重后的新生事件积分达到采用门槛；PS2 与 PM6 共同计算，只采用其中一条。',
        DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2: '这些事件已经计入 PS2；PM6 不再重复计分，不表示缺少父母资料。',
        DENOVO_SHARED_EVENT_ASSIGNED_TO_PM6: '这些事件已经计入 PM6；PS2 不再重复计分，不表示两条独立证据。',
        DENOVO_NO_OBSERVATIONS: '本次已接入资料中没有匹配当前变异和背景的病例观察；不代表所有文献均无记录。',
        DENOVO_NO_QUALIFIED_OCCURRENCES: '已找到观察记录，但没有事件通过全部资格核验；请查看逐事件的检测、亲缘或表型缺项。',
        DENOVO_FACTS_NOT_QUALIFIED: '该观察记录的事实资格尚未满足，具体缺项见本条来源核验原因。',
        DENOVO_SOURCE_REQUIRED: '该观察缺少可追溯的原始来源，不能用于计分。',
        DENOVO_EVENT_INCLUDED: '该发生事件通过资料核验并计入一次分数。',
        DENOVO_SAME_OCCURRENCE: '与已计入记录属于同一次发生事件，重复来源不增加分数。',
        DENOVO_INDEPENDENCE_UNRESOLVED: '不能排除与其他报告属于同一病例，独立性尚未建立，本次不重复计入。',
        DENOVO_GROUP_IDENTITY_CONFLICT: '观察的变异、基因、疾病或遗传方式与当前组不一致，不能混合计算。',
        DENOVO_OBSERVATION_ID_COLLISION: '不同观察使用了冲突的记录身份，需核对资料登记。',
        DENOVO_OCCURRENCE_FACT_CONFLICT: '同一事件的来源事实互相冲突，未任选一份进行计分。',
        DENOVO_PROBAND_TARGET_TEST_REQUIRED: '缺少先证者对这个具体变异的合格检测结果。',
        DENOVO_MATERNAL_TARGET_TEST_REQUIRED: '缺少母亲对这个具体变异的合格检测结果；母亲健康或亲缘确认不能替代目标位点检测。',
        DENOVO_PATERNAL_TARGET_TEST_REQUIRED: '缺少父亲对这个具体变异的合格检测结果；父亲健康或亲缘确认不能替代目标位点检测。',
        DENOVO_TARGET_TEST_QUALITY_FAILED: '已取得目标位点检测，但其质量未通过，不能当作未携带。',
        DENOVO_TARGET_TEST_CONFLICT: '目标位点检测记录互相冲突，需核对原件。',
        DENOVO_PARENTAGE_EXCLUDED: '资料排除了所声明的亲缘关系，不能按该家系计入新生证据。',
        DENOVO_PARENTAGE_INCONCLUSIVE: '已做亲缘检查但结果不确定，不能当作已证实或未做检查。',
        DENOVO_FAMILY_HISTORY_UNRESOLVED: '家族史资料不足或相互矛盾，尚不能确认符合新生事件的适用条件。',
        DENOVO_SPECIAL_FAMILY_MODEL_NOT_SUPPORTED: '涉及嵌合或同胞重复发生，超出当前模型支持范围。',
        DENOVO_INHERITANCE_NOT_SUPPORTED: '当前共同新生证据仅支持声明的常染色体显性范围，本次遗传方式尚未支持。',
        DENOVO_PHENOTYPE_BASIS_REQUIRED: '缺少用于核对病例与疾病相容性的表型依据。',
        DENOVO_PHENOTYPE_CONDITIONS_NOT_ESTABLISHED: '已有表型信息，但当前合格知识不能确定其与所选疾病的一致性或特异性；不能自动补成一致。',
        DENOVO_PHENOTYPE_NOT_CONSISTENT: '已核对的病例表型与当前疾病不一致，本事件不贡献分数。',
        DENOVO_EXPERT_POSITIVE_POINTS_UNIMPLEMENTED: '当前已接入该专家规则的表型核查，适用的正向计分条件尚未接通。',
        DENOVO_PARENTAL_TARGET_PRESENT: '检测发现父亲或母亲携带目标变异，本事件不满足新生条件。',
        DENOVO_PROBAND_TARGET_ABSENT: '先证者检测未见目标变异，本事件不满足新生条件。',
        DENOVO_BELOW_SUPPORTING: '已有合格观察，但去重和封顶后的积分尚未达到支持级门槛。',
        DENOVO_ASSUMED_PARENTAGE_POLICY_REQUIRED: '当前政策未授权采用未完全证实亲缘关系的观察。',
        DENOVO_EXPERT_POLICY_UNRESOLVED: '存在可能适用的专家规范，但采用条件尚未核清，不能直接套用通用规则。',
        DENOVO_EXPERT_STRENGTH_SCOPE_UNRESOLVED: '专家组条款与积分表的强度适用范围待核，保留已核事件和积分。',
        DENOVO_EXPERT_REQUIREMENTS_UNIMPLEMENTED: '已识别专家规范，但其特殊条件尚未实现，属于软件范围限制。',
        DENOVO_SPECIFICATION_UNAVAILABLE: '本次规范资料不可用，尚未进入新生证据计分。',
        DENOVO_PUBLIC_NO_EXACT_CITATION_SOURCE: '已登记资料中没有与当前变异及基因精确对应的引用记录，无法据此定位论文；不表示没有相关文献。',
        DENOVO_PUBLIC_NO_CITATION_CLUE: '已找到精确变异记录，但记录没有可用于取材的论文引用。',
        DENOVO_PUBLIC_NETWORK_DISABLED: '本轮未启用公开联网，且本地缓存没有所需原文或引用元数据。',
        DENOVO_PUBLIC_FULLTEXT_UNAVAILABLE: '已定位论文，但当前来源不提供可读取的开放全文。',
        DENOVO_PUBLIC_PAPER_BUDGET: '本次已达到有限取材数量；尚未检查的引用已记录，不代表无相关病例。',
        DENOVO_PUBLIC_RATE_LIMIT: '公开文献服务要求限流冷却，本轮停止追加请求。',
        DENOVO_PUBLIC_TIMEOUT: '公开文献读取达到时间预算，尚未取得完整资料。',
        DENOVO_SUPPLY_CONFIGURED_VCV_INVALID: '至少一份已登记引用记录未通过来源或结构核验，不能据此查找论文。',
        DENOVO_SUPPLY_PROCESS_TIMEOUT: '资料准备进程超时退出，未把未完成资料用于判定。',
        DENOVO_SUPPLY_PROCESS_FAILED: '资料准备程序未成功完成；这是运行问题，不能解释为数据库没有病例。',
        DENOVO_SUPPLY_UNAVAILABLE: '资料供给环节读取或核验失败，未取得可采用的新资料。',
        DENOVO_SUPPLY_REQUEST_LIMIT: '选中目标或资料数量达到本轮技术上限，需要缩小本次任务。',
        DENOVO_SUPPLY_OUTPUT_LIMIT: '取得的资料超过当前保存容量上限，未截断后冒充完整资料。',
        DENOVO_SUPPLY_IMPLEMENTATION_CHANGED: '资料程序与确认时的版本不一致，需要重新读取候选并确认。',
        DENOVO_SUPPLY_RESULT_MISMATCH: '资料输出与本次目标或版本不一致，已拒绝采用。',
        DENOVO_SUPPLY_ORIGINAL_INVALID: '原文与记录的摘要或大小不一致，已拒绝采用。',
        DENOVO_SUPPLY_NOT_PREPARED: '本次尚未执行所选变异的资料准备，不能视为已经完成检索。',
        DENOVO_SUPPLY_PENDING: '公开资料正在独立获取；现有结果先保存，取得资料后仍须核实并生成新的评价，不能视为已经查无病例。',
        DENOVO_TASK_QUEUE_FULL: '当前资料任务已满，本次未启动新的获取；现有证据结果不受影响。',
        DENOVO_TASK_TIMEOUT: '资料任务耗尽包含排队的总时间预算，未取得可采用的新资料。',
        DENOVO_TASK_QUEUE_TIMEOUT: '资料任务在排队期间已超过总时间预算，没有启动资料获取。',
        DENOVO_TASK_CANCELLED: '资料任务已取消；这不表示没有相关病例，已保存的评价保持原样。',
        DENOVO_TASK_INTERRUPTED: '先前资料任务已中断，未自动重新获取；已保存的评价保持原样。',
        DENOVO_TASK_RESULT_MISMATCH: '资料任务产物与记录摘要不一致，已拒绝采用。',
        DENOVO_TASK_IDENTITY_MISMATCH: '资料任务身份与原请求不一致，已拒绝采用。',
        DENOVO_TASK_EXECUTION_FAILED: '资料任务运行失败，不能据此判断没有相关病例。',
        DENOVO_TASK_RETRY_LIMIT: '此精确资料请求已达到三次尝试上限；再次提交不会继续获取，原失败原因仍保留。',
        DENOVO_TASK_RETRY_IDENTITY_MISMATCH: '重试引用与当前资料请求身份不一致，未启动新的获取。',
        DENOVO_TASK_RETRY_NOT_SAFE: '本次失败涉及未核实的运行或资料完整性问题，暂不能直接重试取材。',
        DENOVO_TASK_EXIT_PENDING: '原资料任务仍在释放执行资源，确认退出后才可重试。',
        PM2_LOCAL_VARIANT_SCOPE_UNSUPPORTED: '本次查询身份超出当前 PM2 软件支持范围：GRCh38 常染色体及所支持的等位基因表示；未检出补证另限 SNV。请核对本次组装、染色体与变异类型；不表示人口数据库没有记录。',
        VARIANT_FILTER_NOT_PASS: '已取得人口记录，但其原生质量标记 FILTER 未明确通过 PASS，本次不能采用；不表示没有查到变异。',
        CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED: '所选注释或序列推导不符合当前纯编码错义后果范围（例如同义、终止改变或合并剪接后果）；本分支尚不能处理，不是仅缺少数据库记录。',
        CODING_MISSENSE_NATIVE_SNV_REQUIRED: '当前编码错义分支仅支持原生且身份核验一致的单核苷酸替换；本次输入超出软件支持范围。',
        CODING_MISSENSE_START_OR_NONCODING_EFFECT_NOT_SUPPORTED: '该位置涉及起始或非当前可处理的编码区域，超出本分支的软件范围；不能按普通编码错义变异评价。',
        PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE: '所选转录本后果不在当前错义或剪接预测分支的支持范围内，本次未完成该预测规则评价；不是查询完成后无记录。',
        PP2_GENERIC_AUTHORITY_DEFINITION_ONLY: '已找到 PP2 的通用规则定义，但该定义未提供当前背景的错义致病机制和低良性错义两项采用依据；不能仅凭定义授予 PP2。',
        PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED: '当前加载的合格知识未覆盖本次基因、疾病、遗传方式及转录本背景；不代表所有数据库或文献都没有资料。',
        BP3_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION: '已知该基因存在专门规范，但本次尚未取得适用的完整 BP3 条款；未进入区域判定，不能跳过专规套用通用重复区条件。',
        BINDING_NOT_READY: '本次所需的执行或材料绑定尚未就绪，请核对具体背景缺项及绑定状态；不能据此认定人口数据库无记录。',
        EXPLICIT_INPUT_MISSING: '本次尚缺完整且显式确认的解释背景，未进入本条证据判定；请查看具体背景缺项，不表示数据库没有资料。',
        SELECTED_ATTEMPT_AMBIGUOUS_OR_MISSING: '未取得与所选候选唯一对应的执行尝试或背景记录，尚未进入评价；请维护者核查候选与尝试绑定，不表示数据库无记录。',
        CLAUSE_SEARCH_SCOPE_NOT_COVERED: `当前加载的${scope === 'PVS1_NMD_V6' ? ' PVS1 ' : ['PS1', 'PM5', 'PM1', 'BS1'].includes(scope) ? ` ${scope} ` : ''}政策目录未覆盖本次解释背景；缺少的是相容的适用政策，不能据此推断参考数据库没有变异。`,
    };
    if (Object.hasOwn(sharedReasons, code)) return sharedReasons[code];
    if (code === 'REFERENCE_APPROVED_MATERIAL_UNAVAILABLE')
        return '缺少与本次变异、疾病和运行身份匹配的参考审核材料；需补齐参考独立致病性、疾病适用及必要 RNA/剪接审核和材料批准。重复运行不能补足审核，也不代表资源读取失败。';
    if (code === 'PP4_REVIEWED_SINGLE_CASE_PHENOTYPE_YIELD') return 'PP4 单先证者表型证据；原样展示后端诊断率、点数和强度，不代表致病概率、最终分类或科学采用。';
    if (code === 'PP4_COHORT_ASCERTAINMENT_UNESTABLISHED') return '历史队列纳入方式未建立，本次未评价；不补零分或强度。';
    if (code === 'PP4_COHORT_ASCERTAINMENT_INCOMPATIBLE') return '历史队列纳入方式与完整检测分母不相容，本次未评价；不是阴性结论。';
    if (code === 'PP4_ALTERNATIVE_METHOD_UNESTABLISHED') return '替代诊断或表型模拟排查方法未建立，本次未评价；不推断已完成排除。';
    if (String(code).startsWith('PP4_PUBLISHED_SAMPLE_FILTER_')) return 'PP4 发布样本 FT 见证不足、失败或冲突，当前不可提交；材料 FT 不替代发布见证。';
    if (code === 'PP4_MATERIAL_UNAVAILABLE') return '当前 PP4 材料不可用（409），不可提交；保存历史仍独立只读。';
    if (code === 'BS4_REVIEWED_NUCLEAR_FAMILY_NONSEGREGATION') return 'BS4 良性方向证据；1或2事件均仅一次 Strong / −4，不代表目标最终良性分类或科学采用。';
    if (code === 'DENOVO_AR_MIXED_REDUCTION_RULE_NOT_IMPLEMENTED') return '已核实的隐性伙伴资格需要不同强度调整，当前算法尚未实现混合汇总；已有积分、来源和具体限制均保留，本次未采用，不表示原始资料缺失。';
    if (code === 'BS4_NONSEGREGATION_NOT_ESTABLISHED') return '未建立本范围的不共分离事件，本次未评价；不补零分、强度或事件。';
    if (code === 'BS4_CHILD_GENOTYPE_CLINICAL_CONFLICT') return '子女基因型与临床材料不满足本限定核验，本次未评价；不作阴性结论。';
    if (String(code).startsWith('BS4_PUBLISHED_SAMPLE_FILTER_')) return '先证者发布 FT 来源不足、失败或冲突，不可提交；材料 FT 不能替代发布见证。';
    if (code === 'BS4_MATERIAL_UNAVAILABLE') return '当前 BS4 材料不可用（409），不可提交；历史记录独立只读。';
    if (code === 'PP1_REVIEWED_NUCLEAR_FAMILY_TRANSMISSIONS') return 'PP1 致病方向证据；点数与强度取后端保存原值，不代表最终分类或科学采用。';
    if (code === 'PP1_DECLARED_COUNT_CONFLICT') return '材料声明传递数与核心核验不一致，本次未评价；不补点数、强度或事件。';
    if (code === 'PP1_UNIQUE_TRANSMITTING_PARENT_REQUIRED') return '尚未满足本限定核心的唯一传递亲本要求，本次未评价。';
    if (String(code).startsWith('PP1_PUBLISHED_SAMPLE_FILTER_')) return '发布样本 FT 来源不足、失败或冲突，当前不可提交；已保存历史独立只读。';
    if (code === 'PP1_MATERIAL_UNAVAILABLE') return '当前 PP1 材料准备冲突（409），不可提交；不改变历史科学结果。';
    const pm6Reasons = {
        PM6_SINGLE_ASSUMED_TRIO_REVIEWED_PHENOTYPE: '本次单观察 PM6 积分与强度按保存结果展示，最大 Moderate；不是最终致病分类。',
        PM6_SINGLE_OBSERVATION_BELOW_SUPPORTING_THRESHOLD: '单次观察未达支持阈值；保留原 0.25 积分，NOT_MET 不等于良性。',
        PM6_REVIEWED_PHENOTYPE_NOT_CONSISTENT: '已有受审表型不一致依据，保存为 0 / NOT_MET；与材料未知不同，不等于良性。',
        PM6_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE: '本限定单家系范围未知或条件未确定；不能解释为已证实不适用。',
        PM6_PARENTAGE_SOURCE_DOCUMENT_INVALID: '亲缘依据内嵌来源报告无法按合同核验；保留原文及最小未评价结果，不补分或强度。',
    };
    if (code === 'BP2_SINGLE_CONFIRMED_PHASE_INDEPENDENT_P') return 'BP2 良性支持证据；不是目标变异良性分类，不自动采用。';
    if (code === 'BP2_PENETRANCE_AGE_NOT_SATISFIED') return '年龄条件未满足本限定路径，未评价；不补0分或强度。';
    if (code === 'BP2_PENETRANCE_CONDITIONS_NOT_ESTABLISHED') return '旧反式材料尚未建立可执行条件，未评价；不默认无约束。';
    if (String(code).startsWith('BP2_PUBLISHED_SAMPLE_FILTER_')) return '发布样本 FT 来源不足、失败或冲突；本次不能提交，历史仍可只读。';
    if (code === 'PM3_PHASE_DECLARATION_CONFLICT') return '相位结论未满足本限定反式分支；未知不等于已证实顺式，不补0分或强度。';
    if (code === 'PM3_SINGLE_CONFIRMED_TRANS_INDEPENDENT_P_LP') return '本次单家系 PM3 保存为1分 / Moderate；伙伴P/LP是外部伙伴分类，不是目标或Varvis最终分类。';
    if (pm6Reasons[code]) return pm6Reasons[code];
    const ps2Reasons = {
        PS2_SINGLE_CONFIRMED_TRIO_REVIEWED_PHENOTYPE: '本次单家系材料满足 PS2 条件；积分和强度按保存结果展示，不是最终致病分类。',
        PS2_COMPLETE_REVIEWED_COUNTEREVIDENCE: '本次完整材料未满足 PS2；不等于良性，不自动转为其他证据。',
        PS2_SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE: '尚未取得本限定单家系范围适用结论（可含未知或冲突）；不能直接解释为已证实不适用。',
        PS2_RELATED_FAMILY_HISTORY_NOT_ABSENT: '尚未取得无相关家族史的明确结论；不一律表示家族史阳性。',
        PS2_RAW_FT_CONFLICT: '原始样本 FT 与结构化过滤事实冲突，未计分。',
        PS2_PARENTAGE_NOT_CONFIRMED_NO_PM6: '亲子关系未获所需确认；未评价 PS2，不自动转为 PM6。',
    };
    if (ps2Reasons[code]) return ps2Reasons[code];
    const bp1Reasons = {
        BP1_REVIEWED_TRUNCATING_MECHANISM_AND_VARIANT_APPLICABILITY: '保存的截短主导机制与精确变异例外、剪接适用满足本次受审条件，BP1 MET/SUPPORTING；不是最终良性分类。',
        BP1_TRUNCATING_MECHANISM_NOT_SUPPORTED: '受审材料明确不支持截短主导机制，BP1 NOT_MET/NONE；不反推 PP2/PVS1 或致病。',
        BP1_MISSENSE_EXCEPTION_APPLIES: '当前错义变异例外适用，BP1 NOT_MET/NONE；不反推 PP2/PVS1 或致病。',
        BP1_CONDITION_UNKNOWN_OR_CONFLICT: '三项材料存在未知或冲突，本候选未评价；同伴结果独立保留，未知不是阴性。',
        BP1_SPLICING_REVIEW_MISSING: '当前变异的独立剪接适用审核缺失，未评价；不把缺少 RNA 实验解释为剪接正常。',
        BP1_MODEL_MISSING: '受信完整转录本模型缺失，未评价；不补造强度或评价身份。',
        BP1_MECHANISM_SHA_MISMATCH: '机制材料内容与固定摘要不符，未评价。',
        BP1_SPECIALIZED_POLICY_UNSUPPORTED: '存在本阶段不支持的适用专规，未评价；不回退通用规则。',
        BP1_VARIANT_APPLICABILITY_IDENTITY_MISMATCH: '精确变异、版本化转录本或模型适用身份不一致，未评价；同蛋白变化不能跨核苷酸复用审核。',
        BP1_SPLICE_IMPACT_UNSUPPORTED: '已知或混合剪接影响不支持本限定分支，未评价；其它材料支持不能覆盖此拒绝。',
        BP1_MECHANISM_BASIS_UNSUPPORTED: '机制依据不支持本限定分支，未评价；PP2 阴性、PVS1 或预测分数不能代替三项审核。',
    };
    if (Object.hasOwn(bp1Reasons, code)) return bp1Reasons[code];
    if (scope === 'BP1_MISSENSE_V9') {
        if (code === 'CLAUSE_SEARCH_INCOMPLETE') return 'BP1 适用条款检索未完成，未评价；缺项不是阴性。';
        if (!strict && code.startsWith('BP1_')) return `BP1 保存的具体门禁原因：${code}；按保存状态展示，不自行推断结果。`;
    }
    const pp2Reasons = {
        PP2_REVIEWED_MECHANISM_AND_LOW_BENIGN_BACKGROUND: '保存的错义致病机制与低良性错义背景均满足本次受审适用条件，PP2 MET/SUPPORTING；不是最终致病分类。',
        PP2_MISSENSE_MECHANISM_NOT_SUPPORTED: '受审材料明确不支持错义致病机制，PP2 NOT_MET/NONE；不反推 BP1 或良性。',
        PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED: '受审材料明确不支持低良性错义背景，PP2 NOT_MET/NONE；不反推 BP1 或良性。',
        PP2_CONDITION_UNKNOWN_OR_CONFLICT: '机制或低良性错义条件未知或冲突，本候选未评价；同伴结果独立保留。',
        PP2_CONDITIONS_MISSING: '双条件材料不完整，未评价。',
        PP2_CONDITION_REVIEW_MISSING: '条件缺少必要的适用审核，未评价；保存的支持声明不等于审核已通过。',
        PP2_CONDITION_REVIEW_IDENTITY_MISMATCH: '条件审核摘要与当前材料内容不一致，未评价。',
        PP2_CONDITION_SOURCE_MISSING: '条件缺少可定位的材料来源，未评价。',
        PP2_CLAUSE_LOCATOR_MISSING: '权威条款定位缺失，未评价；不补造统计数字。',
        PP2_STATISTICS_INVALID: '统计计数或分母等资料无效，未评价；不认定为反证。',
        PP2_STATISTICS_INCOMPLETE: '统计纳排、分母或定位等必要资料不完整，未评价。',
        PP2_STATISTICS_APPLICABILITY_UNREVIEWED: '统计偏倚或精确背景适用性未完成审核，未评价。',
        PP2_ADDITIONAL_CONDITIONS_UNSUPPORTED: '存在本阶段未实现的额外适用条件或例外，未评价。',
        PP2_EXCEPTION_REVIEW_MISSING: '适用例外查核未完成，未评价。',
        PP2_COVERAGE_UNSUPPORTED: '材料未覆盖完整基因及精确疾病、遗传方式和转录本范围，未评价。',
        PP2_MECHANISM_UNRESOLVED: '致病机制或混合机制尚未解决，未评价。',
        PP2_SELECTION_OR_AUTHORITY_INVALID: '规范选择或依据身份无效，未进入完整评价。',
        PP2_POLICY_CONTEXT_MISMATCH: '政策与精确疾病、遗传方式或转录本背景不一致，未评价。',
        PP2_MODEL_IDENTITY_INVALID: '完整转录本模型身份不一致，未评价。',
        PP2_SPECTRUM_IDENTITY_INVALID: '谱材料与模型或精确适用背景不一致，未评价。',
        PP2_SOURCE_INVALID: '材料来源或版本绑定无效，未评价。',
        PP2_SPECIFICATION_INVALID: '保存的专规内容不完整或不支持，未评价。',
        PP2_SPECIFICATION_AUTHORITY_INVALID: '专规依据身份不匹配，未评价。',
        PP2_SPECIFICATION_DISABLED: '专规未允许本范围 PP2，未评价。',
        PP2_PURE_MISSENSE_SNV_REQUIRED: '本阶段仅支持原生纯错义单核苷酸替换，未评价；执行标记不代表序列通过。',
        PP2_SEQUENCE_CODING_PROTEIN_IDENTITY_MISMATCH: '完整编码序列、密码子或蛋白身份不一致，未评价。',
        PP2_SELECTED_TRANSCRIPT_NOT_UNIQUE: '所选精确转录本注释不唯一，未评价。',
        PP2_ANNOTATION_SOURCE_INVALID: '所选注释来源或版本不符合要求，未评价。',
        PP2_REGISTRY_IDENTITY_INVALID: '本次注册表或依据绑定无效，未评价。',
    };
    if (Object.hasOwn(pp2Reasons, code)) return pp2Reasons[code];
    if (scope === 'PP2_MISSENSE_V8') {
        if (code === 'CLAUSE_SEARCH_INCOMPLETE') return 'PP2 适用条款查找未完成，未评价；缺项不是阴性。';
        if (!strict && code.startsWith('PP2_SEQUENCE_')) return `PP2 序列核验受阻，未评价：${code}；不由执行计数推断序列成功。`;
        if (!strict && code.startsWith('PP2_MODEL_')) return `PP2 完整转录本模型核验受阻，未评价：${code}。`;
        if (!strict && code.startsWith('PP2_')) return `PP2 保存的具体门禁原因：${code}；按保存状态展示，不自行推断结果。`;
    }
    const bp3Reasons = {
        BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED: '该次运行未配置 BP3 所需的重复区域与无已知功能适用知识材料；已有区域或功能注释不能代替此项依据，本次保留未评价。这是保存时的缺项，不代表当前配置仍然缺失。',
        BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION: '完整等价编辑包络位于受审重复区，保存的无已知功能适用声明满足本条限定支持条件；不是最终良性分类。',
        BP3_PUBLISHED_REPEAT_REGION_MATCHED: '所有等价表示的影响范围均在专家规范明确允许的重复区域内，满足限定的BP3支持级条件。',
        BP3_OUTSIDE_PUBLISHED_ELIGIBLE_REGION: '完整影响范围未被专家规范允许的区域覆盖，不满足本条BP3条件；这不表示变异致病。',
        BP3_SOURCE_NOT_APPLICABLE: '对应专家规范明确不使用BP3；系统保留原文，不改用通用规则绕过。',
        BP3_SOURCE_APPLICABILITY_CONFLICT: '来源的适用标记与正文或强度条款冲突，暂不能确定BP3应如何使用。',
        BP3_OTHER_INFRAME_POPULATION_EVIDENCE_REQUIRED: '该规范还要求重复区内其他框内变异达到指定人群频率；只有目标变异或重复区注释不足。',
        BP3_SOURCE_STRENGTH_UNRESOLVED: '来源尚不能明确支持可执行的BP3强度条款。',
        BP3_SOURCE_APPLICABILITY_UNRESOLVED: '来源未明确说明BP3的适用状态。',
        BP3_PUBLISHED_REGION_OR_CONDITIONS_REQUIRED: '已有BP3规范，但缺少可直接采用的区域、功能依据，或附加条件尚未实现。',
        BP3_PUBLISHED_REGION_MODEL_MISMATCH: '规范的蛋白区域与当前完整转录本模型不相容，不能直接套用坐标。',
        BP3_VERIFIED_INFRAME_SEQUENCE_REQUIRED: '尚未取得基因组、编码序列与注释一致的简单框内变化。',
        BP3_NON_REPEAT_REGION: '受审材料明确为非重复区，本条不满足（NOT_MET/NONE）；不反推 PM4 或致病。',
        BP3_KNOWN_FUNCTION: '受审材料记载已知功能，本条不满足（NOT_MET/NONE）；不反推致病。',
        BP3_REGION_COVERAGE_MISSING: '所需区域材料缺失，未评价；同批其它背景保留各自结果。',
        BP3_REGION_FACT_UNKNOWN: '区域重复或功能适用事实未知，未评价，不是阴性。',
        BP3_FUNCTIONAL_REVIEW_MISSING: '缺少独立功能适用审核，未评价。',
        BP3_REGION_CONFLICT: '区域材料存在冲突，未进入完整评价。',
        BP3_MODEL_MISSING: '精确转录本模型缺失，未进入序列核验与完整评价。',
        BP3_POLICY_DISABLED: '当前政策未允许 BP3，未评价。',
        BP3_EQUIVALENT_ENVELOPE_REGION_COVERAGE_INCOMPLETE: '单一受审区域未覆盖全部等价编辑包络，未评价；不裁剪包络或拼接区域。',
        BP3_HGVSC_SEQUENCE_MISMATCH: '保存的编码 HGVS 与序列推导不一致，未评价。',
        BP3_SPLICE_BRANCH_UNSUPPORTED: '涉及本阶段未支持的剪接分支，未评价。',
        BP3_POLICY_AUTHORITY_MISSING: '政策依据身份缺失，未进入完整评价。',
    };
    if (Object.hasOwn(bp3Reasons, code)) return bp3Reasons[code];
    if (scope === 'BP3_INFRAME_V7' && code === 'CLAUSE_SEARCH_INCOMPLETE')
        return 'BP3 适用条款查找未完成，未进入完整评价；不将缺失认定为阴性。';
    if (scope === 'PVS1_NMD_V6') {
        const reasons = {
            REVIEWED_PVS1_MATERIAL_IDENTITY_UNAVAILABLE: 'PVS1 材料身份未通过核验，本候选未评价。',
            PVS1_LOF_MECHANISM_NOT_ESTABLISHED: '当前背景的 LoF 机制依据未建立，未评价。',
            PVS1_NMD_SCOPE_NOT_REVIEWED: '相关外显子或 NMD 适用范围尚未通过审核，未评价。',
            PVS1_SPLICE_BRANCH_NOT_IMPLEMENTED: '当前政策没有启用剪接后果范围，未评价。',
            PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED: '尚缺精确变异、疾病和转录本对应的剪接后果材料；注释或预测分数不能代替具体 RNA 后果。',
            PVS1_SPLICE_OUTCOME_NOT_REVIEWED: '剪接后果材料尚未完成适用核定，未评价。',
            PVS1_SPLICE_SCOPE_NOT_REVIEWED: '当前政策的剪接适用范围尚未核定。',
            PVS1_SPLICE_OUTCOME_UNRESOLVED: '剪接后果尚不能唯一确定，本轮未选择最严重的后果。',
            PVS1_SPLICE_OUTCOMES_AMBIGUOUS: '同一变异和背景存在多份剪接后果记录，未自动择取。',
            PVS1_SPLICE_FUNCTIONAL_RESCUE_UNRESOLVED: '替代剪接或功能补救尚未排除，不能直接认定功能丧失。',
            PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED: '残余正常转录本的意义尚未核定，当前完整缺失路线不适用。',
            PVS1_SPLICE_EVIDENCE_QUALIFICATION_REQUIRED: '预测后果或 RNA 实验的适用性、质量及替代后果依据尚不充分。',
            PVS1_SPLICE_INFRAME_EFFECT_NOT_IMPLEMENTED: '本次剪接保留阅读框；区域性蛋白缺失判定尚未接入，不能套用截短尾部规则。',
            PVS1_SPLICE_TERMINAL_EXON_NOT_SUPPORTED: '本轮剪接路线仅支持内部编码外显子，首末外显子仍缺实现。',
            PVS1_SPLICE_EVENT_NOT_SUPPORTED: '剪接事件类型或操作不在本轮支持范围，未评价。',
            PVS1_SPLICE_EVENT_INVALID: '剪接事件的坐标或长度无效，未评价。',
            PVS1_SPLICE_EVENT_MODEL_MISMATCH: '剪接删除区间与精确转录本外显子结构不相符。',
            PVS1_SPLICE_MODEL_IDENTITY_MISMATCH: '剪接材料所绑定的转录本模型与当前模型不一致。',
            PVS1_SPLICE_SOURCE_IDENTITY_CONFLICT: '同名剪接来源记录的版本或摘要冲突，未评价。',
            PVS1_SPLICE_REFERENCE_MISMATCH: '剪接材料的参考序列与本次变异 REF 不一致。',
            PVS1_SPLICE_REFERENCE_WITNESS_INVALID: '剪接参考序列见证缺失或格式无效。',
            PVS1_SPLICE_CURRENT_REFERENCE_IDENTITY_MISMATCH: '剪接位点读取与所选转录本的参考版本不一致。',
            PVS1_SPLICE_CURRENT_REFERENCE_WINDOW_MISMATCH: '剪接资料的参考窗口与本地参考序列不符。',
            PVS1_SPLICE_CURRENT_REFERENCE_WINDOW_UNAVAILABLE: '无法从本地参考读取剪接位点窗口。',
            PVS1_PTC_EXON_GEOMETRY_UNRESOLVED: '无法将变异后终止密码子的三个碱基定位到外显子。',
            PVS1_EXON_NOT_RELEVANT: '适用规则确认变异所在外显子与当前疾病无关，PVS1 未满足。',
            PVS1_EXON_RELEVANCE_UNRESOLVED: '变异或终止密码子所在外显子缺少疾病相关性依据。',
            PVS1_EXON_RELEVANCE_CONFLICT: '同一外显子在所选规则中同时被标为相关和不相关。',
            PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED: '已有外显子相关性依据，当前执行规则未覆盖该变异或终止密码子的位置。',
            PVS1_SPLICE_CANONICAL_SITE_REQUIRED: '预测后果路线目前只接入精确经典剪接位点；其他位置需要合格的 RNA 后果材料。',
            PVS1_SPLICE_NONCANONICAL_MOTIF_NOT_SUPPORTED: '参考剪接基序不在当前 GT/AG 预测路线范围，未套用一般规则。',
            PVS1_SPLICE_HGVSC_SITE_MISMATCH: '剪接 HGVS 与转录本方向或实际位点不一致。',
            PVS1_SPLICE_ANNOTATION_SITE_MISMATCH: '剪接注释类型与实际供体／受体位置不一致。',
            PVS1_SPLICE_MOTIF_WITNESS_REQUIRED: '缺少完整参考剪接基序，未采用预测后果。',
            PVS1_SPLICE_EVENT_SITE_MISMATCH: '预测的外显子缩短方向与受影响剪接位点不相符。',
            PVS1_SPLICE_RETAINED_CODING_ALLELE_NOT_SUPPORTED: '原变异还会改变保留的编码序列；复合后果尚未接入，不能只计算剪接删除。',
            PVS1_SPLICE_SMALL_INDEL_NOT_IMPLEMENTED: '本轮剪接后果路线只接入 SNV，剪接小插入／删除仍未实现。',
            PVS1_SPLICE_PTC_JUNCTION_UNRESOLVED: '改变后的终止密码子跨外显子连接，当前分支无法可靠判定。',
            PVS1_SPLICE_EVIDENCE_KIND_NOT_SUPPORTED: '当前材料类型不具备本次剪接采用资格，单一预测分数不等于 RNA 后果。',
            PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED: '当前保存政策未启用该变异的 NMD 逃逸范围，不能据此作出判断；不表示整个系统没有逃逸算法，也不代表良性。',
            PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE: '变异或终止位置不完全落在本次政策已核定的位置范围内，未评价。',
            PVS1_POLICY_POSITION_UNRESOLVED: '终止或编辑跨度不能可靠映射到政策要求的参考坐标，未评价。',
            PVS1_ESCAPE_SCOPE_NOT_REVIEWED: '当前政策的逃逸范围尚未单独核定；旧 NMD 审核不能自动覆盖它。',
            PVS1_ESCAPE_POPULATION_TOLERANCE_UNRESOLVED: '缺少相关外显子的人群功能丧失耐受依据；不能将资料缺失当作不存在耐受。',
            PVS1_ESCAPE_EXON_RELEVANCE_UNRESOLVED: '尚不能确认受影响外显子的疾病相关性，未评价。',
            PVS1_ESCAPE_EXON_ASSESSMENT_MISSING: '缺少受影响外显子的适用材料，未评价。',
            PVS1_ESCAPE_EXON_LOF_TOLERANT: '保存材料提示该外显子耐受功能丧失，本次不满足该 PVS1 范围；不等于最终良性。',
            PVS1_ESCAPE_EXON_NOT_RELEVANT: '保存材料表明该外显子不属于本次疾病相关范围。',
            PVS1_ESCAPE_NO_REVIEWED_RULE_MATCH: '没有匹配到已核定的逃逸规则，未评价；不能自行降级或套用其他背景。',
            PVS1_ESCAPE_FRACTION_BOUNDARY_UNSPECIFIED: '蛋白损失比例恰在来源未规定的边界，未自动取整或指定强度。',
            PVS1_ESCAPE_NMD_STATUS_UNRESOLVED: '尚不能确认真实 NMD 逃逸；工程范围排除不能替代这一判断。',
            PVS1_ESCAPE_RULES_AMBIGUOUS: '多个逃逸规则的适用结论存在歧义，未自动选择强度。',
            PVS1_ESCAPE_POLICY_INVALID: '逃逸政策结构或依据绑定无效，未评价。',
            PVS1_MECHANISM_SOURCE_NOT_FOUND: '未找到与本次基因、疾病和遗传方式匹配的机制依据。',
            PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING: '本次背景尚缺精确转录本的知识采用材料，不能把有数据库记录等同于可评价。',
            PVS1_KNOWLEDGE_POLICY_OR_EXON_BINDING_MISMATCH: '采用材料与本次政策、模型或外显子范围不匹配。',
            PVS1_KNOWLEDGE_DATA_USE_NOT_QUALIFIED: '材料用途不覆盖本次数据类别，未评价。',
            PVS1_KNOWLEDGE_APPLICABILITY_NOT_REVIEWED: '本次知识适用范围尚未核定，未评价。',
            PVS1_MECHANISM_SOURCE_CONFLICT: '当前背景的机制来源有冲突，不能直接采用。',
            PVS1_POLICY_SOURCE_APPLICABILITY_INCOMPLETE: '政策的机制、转录本或外显子适用依据仍不完整。',
            PVS1_REVIEWED_POLICY_SOURCES_BOUND: '政策原始来源与本次机制、模型和适用范围已绑定；不等同于临床批准。',
            PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND: '已自动核对原政策、完整来源和转录本条件，可用于本次科研判断；不代表临床批准。',
            PVS1_SOURCE_POLICY_CONDITIONS_MISSING: '已找到该疾病背景的功能缺失机制记录，但尚无可执行的转录本、外显子、NMD及证据强度条件；数据库标签不能直接用于判定。',
            PVS1_PUBLISHED_RULE_ASSEMBLY_REQUIRED: '已找到对应疾病和遗传方式的专家规范，尚需核对转录本并接入 PVS1 条件。',
            PVS1_SOURCE_QUALIFICATION_POLICY_INVALID: 'PVS1科研知识的用途声明不完整或不相容。',
            PVS1_SOURCE_QUALIFICATION_ORIGINAL_REVIEW_MISMATCH: '知识包中的原审核记录与实际政策不一致。',
            PVS1_SOURCE_QUALIFICATION_CONDITIONS_MISMATCH: '知识包中的机制、NMD或强度条件与原政策不一致。',
            PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED: 'PVS1政策的完整来源文件尚未通过摘要与身份核对。',
            PVS1_SOURCE_QUALIFICATION_BRANCH_UNSUPPORTED: '当前来源资格路径仅支持非剪接分支，不能用于本次分支。',
            PVS1_SOURCE_QUALIFICATION_ORIGINAL_USE_RESTRICTED: '原政策仅允许合成研究，当前知识包不能自动扩大其用途。',
            PVS1_SOURCE_AND_REVIEWED_APPLICABILITY_BOUND: '机制来源与本次适用材料已绑定；不等同于临床批准。',
            PVS1_POLICY_STRENGTH_CONDITIONS_REQUIRED: '政策缺少合法的 PVS1 证据强度上限，不能默认给出非常强证据。',
            PVS1_SINGLE_EXON_ESCAPE_CONDITIONS_MISSING: '该转录本只有一个外显子，没有外显子连接点；还缺少适用的非 NMD 功能损失依据，不能仅凭截短给出 PVS1。',
            PVS1_SINGLE_EXON_JUNCTION_CONFLICT: '单外显子模型却记录了外显子连接点，资料相互矛盾，不能继续判断。',
            PVS1_SPECIAL_TOPOLOGY_SEQUENCE_PROOF_MISSING: '特殊转录本结构缺少完整 RNA 和编码序列核验依据，不能套用普通 NMD 模型。',
            PVS1_SPECIAL_TOPOLOGY_SPLICE_NOT_IMPLEMENTED: '本次特殊转录本结构同时涉及剪接后果，当前新增路径只支持纯编码无义和简单移码，未作剪接推断。',
            PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED: '已取得末次连接点及距离，但连接点位于编码区末端或 3′UTR；缺少该结构下的 NMD 适用依据，距离本身不足以给出结论。',
            PVS1_UTR_JUNCTION_APPLICABILITY_INVALID: '3′UTR 连接点适用条件不完整或来源未绑定，未进行证据判断。',
            PVS1_SINGLE_EXON_NMD_SCOPE_NOT_IMPLEMENTED: '该转录本只有一个外显子，当前多外显子 NMD 路径不适用；不能虚构末次接头。',
            PVS1_NMD_JUNCTION_OUTSIDE_IMPLEMENTED_SCOPE: '末次接头不在当前支持的编码区位置，需其他 NMD 或非 NMD 依据。',
            TRANSCRIPT_MATERIAL_EXACT_VERSION_NOT_AVAILABLE: '本地资料尚无所选精确版本的转录本，不能换版本继续判定。',
            TRANSCRIPT_MATERIAL_ALLELE_REFERENCE_MISMATCH: '变异的参考碱基与当前本地参考基因组不一致，请核对坐标、版本和 REF。',
            TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH: '转录本序列与当前本地参考基因组不一致，未采用旧模型绕过核验。',
            TRANSCRIPT_MATERIAL_CANDIDATE_ALLELE_MISMATCH: '供给材料所核对的等位基因与本次正式候选不一致。',
            TRANSCRIPT_MATERIAL_CONTEXT_REFERENCE_MISMATCH: '本次变异归一化和转录本读取使用的参考版本不一致。',
            TRANSCRIPT_MATERIAL_SEQUENCE_PROOF_MISSING: '本地转录本缺少完整 RNA 或编码序列的核验信息。',
            TRANSCRIPT_MATERIAL_IO_UNAVAILABLE: '本地转录本文件或参考序列暂不可读取，尚未进行证据判断。',
            TRANSCRIPT_REFERENCE_IDENTITY_CHANGED: '读取期间参考文件身份发生变化，本次材料已拒绝。',
            REFERENCE_CHECKSUM_NOT_APPLICABLE: '当前参考文件的大小或时间与核验记录不一致，需先确认文件身份。',
            PVS1_START_LOSS_BRANCH_NOT_IMPLEMENTED: '起始丢失分支尚未实现，未评价。',
            PVS1_START_LOSS_FUNCTIONAL_TRANSCRIPT_AND_REFERENCE_REVIEW_REQUIRED: '起始密码子改变已完成序列核对；功能性替代转录本及上游参考资料尚未核验，未给出 PVS1 强度。',
            PVS1_START_LOSS_POLICY_OR_LOF_REVIEW_UNAVAILABLE: '当前精确疾病背景缺少已审核的 PVS1 适用政策或 LoF 机制资料，未评价。',
            PVS1_START_LOSS_POLICY_CONTEXT_MISMATCH: 'PVS1 起始丢失政策与本次疾病或遗传方式背景不匹配，未评价。',
            PVS1_START_LOSS_REFERENCE_SEQUENCE_INVALID: '转录本参考 CDS 不完整或起始/终止结构异常，无法核对起始密码子。',
            PVS1_START_LOSS_REFERENCE_ALLELE_CONFLICT: '变异参考碱基与选定转录本 CDS 不一致，保留冲突且不评分。',
            PVS1_START_LOSS_ANNOTATION_CONFLICT: 'VEP 起始丢失注释与转录本 HGVS/CDS 序列核对不一致，保留冲突且不评分。',
            PVS1_START_LOSS_TRANSCRIPT_IDENTITY_CONFLICT: '当前注释、转录本和政策绑定身份不一致，未评价。',
            PVS1_START_LOSS_NOT_START_CODON: '变异不在选定转录本起始密码子的三个碱基内，不适用本分支。',
            PVS1_START_LOSS_SCOPE_UNSUPPORTED: '当前首版只支持 GRCh38 核基因 SNV 起始密码子改变；该变异类型或参考组装尚不支持。',
            PVS1_START_LOSS_INPUT_INVALID: '起始丢失分支输入无效或不完整，未评价。',
            PVS1_POLICY_AUTHORITY_IDENTITY_INVALID: '政策依据身份缺失或无效，未评价。',
            CLAUSE_SEARCH_SCOPE_NOT_COVERED: '现有目录未覆盖该背景的政策，未评价。',
            CLAUSE_SELECTION_CONFLICT: '适用条款选择存在冲突，未评价。',
            APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED: '适用专项条款尚未实现，未评价。',
            POLICY_NOT_REVIEWED_IMPLEMENTED: '当前政策尚未获得所需审核与实现状态，未评价。',
            CATALOG_INTEGRITY_MISMATCH: '政策目录摘要不一致，未评价。',
        };
        if (Object.hasOwn(reasons, code)) return reasons[code];
    }
    if (scope === 'PVS1_START_LOSS_V1') {
        const reasons = {
            PVS1_START_LOSS_FACTS_MISSING: '起始丢失分支缺少本次变异的核验事实，未评价。',
            PVS1_START_LOSS_EVIDENCE_CONFLICT: '序列或来源记录冲突，保留冲突且不授予 PVS1。',
            PVS1_START_LOSS_SCOPE_UNSUPPORTED: '该变异超出本分支支持范围，未评价。',
            PVS1_START_LOSS_SEQUENCE_UNAVAILABLE: '完整 CDS 序列尚不可用，未评价。',
            PVS1_START_LOSS_POLICY_PROHIBITS_CRITERION: '适用专规明确不允许使用该 PVS1 起始丢失分支。',
            PVS1_START_LOSS_POLICY_CONDITIONS_UNSUPPORTED: '适用专规条件尚未实现，未回退使用通用强度。',
            PVS1_START_LOSS_POLICY_UNRESOLVED: '缺少与本疾病背景绑定的起始丢失专规条件，未评价。',
            PVS1_START_LOSS_LOF_NOT_APPLICABLE: '该疾病背景已明确不适用 LoF 机制，本分支不适用。',
            PVS1_START_LOSS_LOF_MECHANISM_UNRESOLVED: '该疾病背景的 LoF 机制尚未确认，未评价。',
            PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT: '已核实存在功能相关且保留另一未受影响起始位点的转录本，本分支不适用。',
            PVS1_START_LOSS_PUBLISHED_ALTERNATIVE_START_EXCLUSION: '精确匹配的已发布专家规范明确记载替代起始，并排除起始丢失PVS1；不代表变异良性。',
            PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE: '起始丢失身份与精确适用的专家条款条件已核对，按该条款采用证据强度；不使用默认强度或推导最终临床分类。',
            PVS1_START_LOSS_PUBLISHED_CONDITIONS_UNSUPPORTED: '适用的起始丢失专规条件尚未实现，未回落通用规则或默认强度。',
            PVS1_START_LOSS_PUBLISHED_SEQUENCE_CONDITION_MISMATCH: '已核实序列与专家条款声明的下游同框位点或蛋白身份不一致，不能采用该强度。',
            PVS1_START_LOSS_PUBLISHED_STRENGTH_CONFLICT: '适用专家条款的强度存在冲突，未选择其中更高的强度。',
            PVS1_START_LOSS_PUBLISHED_RULE_CONFLICT: '适用专家条款存在许可、排除或条件冲突，暂不采用。',
            PVS1_START_LOSS_EXPERT_RULE_UNRESOLVED: '已有相关专家规范，但其起始丢失条件尚未完整执行或原件不可用，不能自动改用通用规则。',
            PVS1_START_LOSS_TRANSCRIPT_REVIEW_INCOMPLETE: '功能性替代转录本审查不完整，未评价。',
            PVS1_START_LOSS_RESTART_CANDIDATE_UNRESOLVED: '下游候选起始位点尚不能确定，未推断完整功能丧失。',
            PVS1_START_LOSS_UPSTREAM_SEARCH_INCOMPLETE: '上游参考来源查询失败或不可用，不能按无命中处理。',
            PVS1_START_LOSS_UPSTREAM_REVIEW_INCOMPLETE: '上游参考资料的范围或资格审查尚未完成，未评价。',
            PVS1_START_LOSS_UPSTREAM_PATHOGENIC_REFERENCE: '完整核查区间中存在合格独立 Pathogenic 参考，采用 Moderate 基线。',
            PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE: '完整核查区间未发现合格独立 Pathogenic 参考，采用 Supporting 基线。'
        };
        if (Object.hasOwn(reasons, code)) return reasons[code];
    }
    if (code === 'REVIEWED_CLAUSE_POLICY_AND_MODEL_BOUND') return '条款政策与模型身份已绑定';
    if (code === 'REVIEWED_NMD_SCOPE_SUPPORTED') return '已通过当前受审 NMD 工程范围检查';
    const pm1Reasons = {
        PM1_REVIEWED_REGION_MATCH: '独立位置核对与本次受审区域条件一致，PM1 满足；实际强度以保存结果为准。',
        PM1_COVERED_POSITION_NOT_SUPPORTED: '该位置在已审核覆盖范围内，但不满足本次区域支持条件；不代表变异良性。',
        PM1_EXPLICIT_EXCLUSION: '命中本次政策的明确排除项，PM1 未满足。',
        PM1_REGION_COVERAGE_INCOMPLETE: '该位置未被受审材料充分覆盖，本次未评价，不能作为阴性结论。',
        PM1_REGION_COVERAGE_MISSING: '缺少已审核覆盖范围，本次未评价。',
        PM1_ORDINARY_DOMAIN_NOT_QUALIFIED: '普通 domain 注释不具备本次 PM1 科学资格，本次未评价。',
        PM1_REGION_QUALIFICATION_UNKNOWN: '区域科学资格尚不明确，本次未评价。',
        PM1_REGION_NOT_QUALIFIED: '受审区域明确不满足本次资格条件，PM1 未满足。',
        PM1_DISTRIBUTION_UNKNOWN: '必要的致病或良性分布审核尚不明确，本次未评价；缺少记录不等于无良性变异。',
        PM1_BENIGN_DISTRIBUTION_NOT_MET: '已审核的良性分布不满足本次区域条件，PM1 未满足。',
        PM1_PATHOGENIC_DISTRIBUTION_NOT_MET: '已审核的致病分布不满足本次区域条件，PM1 未满足。',
        PM1_REGION_REVIEW_MISSING: '区域材料或命中规则尚未完成必要审核，本次未评价。',
        PM1_POLICY_REVIEW_MISSING: '政策尚未完成必要审核，本次未评价。',
        PM1_REGION_CONFLICT: '重叠区域规则缺少可决定结果的受审优先关系，保留冲突，不取最强或叠加。',
        PM1_REGION_PRECEDENCE_CONFLICT: '区域优先关系存在冲突，本次未评价，需审核处理。',
        PM1_MODEL_MISSING: 'PM1 可信转录本模型缺失，本次未评价。',
        PM1_MODEL_SHA_MISMATCH: 'PM1 转录本模型摘要与固定身份不一致，本次未评价。',
        PM1_REGION_MISSING: 'PM1 可信区域材料缺失，本次未评价。',
        PM1_REGION_SHA_MISMATCH: 'PM1 区域材料摘要与固定身份不一致，本次未评价。',
        PM1_POLICY_AUTHORITY_MISSING: 'PM1 权威来源身份缺失，仅保留未评价原因，未形成完整评价。',
        PM1_POLICY_AUTHORITY_INVALID: 'PM1 权威来源身份不合法，本次未评价。',
    };
    if (Object.hasOwn(pm1Reasons, code)) return pm1Reasons[code];
    if (code === 'PM5_REFERENCE_MATERIALS_MISSING') return 'PM5 可信参考材料缺失，本次未评价；转录本确认不能替代参考独立性及比较依据审核。';
    if (code === 'PS1_REFERENCE_MATERIALS_MISSING') return 'PS1 可信参考材料缺失，本次未评价；转录本确认不能替代参考审核。';
    if (code === 'MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE') return '参考搜索尚不完整，本次未评价，不能作为完整阴性结论。';
    const ps1SpliceReasons = {
        PS1_SAME_PREDICTED_NATIVE_SPLICE_LOSS: '合格参考与目标预测影响同一正常剪接位点，达到所选规范的PS1条件；不代表已证实RNA异常。',
        PS1_SPLICE_POLICY_SCOPE_MISSING: '当前疾病、遗传方式或精确转录本尚无可采用的剪接PS1政策，不能沿用错义规则。',
        PS1_SPLICE_OUTSIDE_SUPPORTED_MOTIF: '变异不在当前支持的供体或受体剪接区域；深部内含子等范围尚未支持。',
        PS1_SPLICE_EXONIC_PROTEIN_REVIEW_REQUIRED: '该变异位于外显子剪接区域，还需要核对蛋白变化影响；当前分支尚未自动完成这项比较。',
        PS1_SPLICE_PVS1_BASELINE_REQUIRED: '经典剪接位点需要相容的PVS1评价，或精确适用于目标自身的专家原始基线；当前缺少该依据，不能直接指定PS1强度。',
        PS1_SPLICE_PROFILE_POSITION_MISMATCH: '目标与参考的次要剪接事件发生在不同位置，不能当作相同预测影响。',
        PS1_SPLICE_PROFILE_EVENT_SET_MISMATCH: '目标与参考预测到的剪接事件种类不一致。',
        PS1_SPLICE_PROFILE_GRAY_ZONE: '完整剪接预测中有事件落在当前比较方法的灰区，尚不能确定模式相同。',
        PS1_SPLICE_MIXED_PROFILE_UNSUPPORTED: '预测同时涉及供体和受体等混合变化，当前方法不能可靠比较其后果。',
        PS1_SPLICE_SECONDARY_SCORE_INCOMPATIBLE: '次要剪接事件的预测程度不相容；分数更高也不能自动解释为影响更大。',
        PS1_SPLICE_PROFILE_INCOMPLETE: '完整剪接模式中的分数或位置记录不完整。',
        PS1_SPLICE_PROFILE_SCOPE_UNSUPPORTED: '当前知识未声明可使用的完整剪接比较范围。',
        PS1_SPLICE_BASELINE_TARGET_SOURCE_MISSING: '尚无精确适用于目标本身的合格专家PVS1原始依据，不能借用参考变异的基线。',
        PS1_SPLICE_BASELINE_TRANSCRIPT_PROOF_MISSING: '已有专家基线尚缺精确转录本的完整RNA、外显子与编码序列对应证明。',
        PS1_SPLICE_BASELINE_TRANSCRIPT_MISMATCH: '已有专家基线的转录本、外显子或序列与当前分析不一致。',
        PS1_SPLICE_BASELINE_SOURCE_TRANSCRIPT_MISMATCH: '专家原文、原始HGVS与当前目标的精确转录本对应不一致。',
        PS1_SPLICE_BASELINE_CURRENT_RESULT_CONFLICT: '当前PVS1结果并非单纯的资料范围缺项，不能用历史专家基线直接覆盖。',
        PS1_SPLICE_BASELINE_EXPLICIT_STRENGTH_MISSING: '专家原始最终证据列表中没有可明确采用的PVS1强度。',
        PS1_SPLICE_BASELINE_SOURCE_CONFLICT: '适用于目标自身的专家原始PVS1强度存在冲突。',
        PS1_SPLICE_BASELINE_HGVS_UNSUPPORTED: '当前无法把该位点精确对应到专家原文的编码区剪接HGVS，不能借用该基线。',
        PS1_SPLICE_BASELINE_CONTEXT_MISMATCH: '已有基线政策与当前基因、疾病、遗传方式或转录本背景不一致。',
        PS1_SPLICE_BASELINE_CANONICAL_REQUIRED: '该基线读取通路仅用于明确的经典剪接位点。',
        PS1_SPLICE_REFERENCE_SOURCE_MISSING: '已找到附近参考，但本地尚无该记录完整、可核实的专家判定原件。',
        PS1_SPLICE_NO_INDEPENDENT_REFERENCE: '附近参考未通过独立致病依据检查，或缺少完整来源，暂不能进行PS1比较。',
        PS1_SPLICE_REFERENCE_INDEPENDENCE_INSUFFICIENT: '排除PS1/PM5比较依据后，参考不再足以维持原分类，不能循环引用。',
        PS1_SPLICE_REFERENCE_ORIGINAL_VERSION_UNSUPPORTED: '参考的原始规范版本尚未获得本路径的解析支持，不能猜测条款强度。',
        PS1_SPLICE_REFERENCE_CLINICAL_SUPPORT_MISSING: '这类可能致病的经典剪接参考还需要独立临床支持，当前资料未满足。',
        PS1_SPLICE_PREDICTION_BELOW_THRESHOLD: '剪接预测未达到本路径要求的影响阈值，不能作为剪接PS1比较依据。',
        PS1_SPLICE_MULTIPLE_OR_CRYPTIC_EVENTS_UNSUPPORTED: '预测还涉及其他或隐蔽剪接位点，不能简化为单一位点丢失；当前复杂结果比较尚未支持。',
        PS1_SPLICE_EVENT_POSITION_MISMATCH: '预测影响的位置未对应到所选转录本的同一正常剪接位点。',
        PS1_SPLICE_TARGET_WEAKER_THAN_REFERENCE: '目标的预测影响弱于所选规范允许的参考比较范围。',
        PS1_SPLICE_NO_COMPARABLE_EVENT: '已取得参考和预测，但没有一组满足本次剪接影响比较条件；具体原因见参考明细。',
        PS1_SPLICE_NON_GT_AG_INTRON_UNSUPPORTED: '该内含子不是当前支持的GT/AG类型，需要其他剪接区域定义。',
        PS1_SPLICE_RECORDED_RNA_REVIEW_REQUIRED: '已登记相关RNA实验，不能仅凭当前简化预测覆盖原实验，需要核对其具体结果。',
        PS1_SPLICE_REGISTERED_RNA_SOURCE_UNAVAILABLE: '已登记RNA资料的查询来源不可用，本次不能完成冲突核对。',
        PS1_SPLICE_CONFIGURATION_UNAVAILABLE: '剪接PS1的本地配置或资料身份未通过检查，本次没有执行评价。',
        PS1_SPLICE_REFERENCE_QUERY_UNAVAILABLE: '本地参考数据库查询未完成，不能理解为数据库没有参考。',
        PS1_SPLICE_NATIVE_MODEL_MISMATCH: '转录本序列模型、GENCODE结构或VEP基因身份不一致，已停止剪接比较。',
        NATIVE_SPLICE_PROCESS_FAILED: '本地剪接预测程序运行失败，尚未取得可用预测；这是工具执行问题，不代表数据库无记录或证据不满足。',
        PM5_SPLICE_COMPARISON_NOT_APPLICABLE: '本次属于剪接比较分支；PM5的不同氨基酸替换比较不适用于这条关系。',
    };
    if (Object.hasOwn(ps1SpliceReasons, code)) return ps1SpliceReasons[code];
    const pm4Reasons = {
        PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET: '已由精确参考序列和转录本推导出新的终止位点及蛋白延长，满足本项目PM4条件；不是RNA或蛋白实验，也不是最终致病分类。',
        PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_UNAVAILABLE: '缺少该精确转录本原终止密码子之后的可用3′端序列，无法推导延长长度；本次未评价，不代表已查明没有新终止位点。',
        PM4_STOP_LOSS_NEW_STOP_NOT_FOUND_IN_TRANSCRIPT: '在已取得的完整转录本序列范围内未找到新的同框终止密码子，无法确定延长终点；本次未评价，不推断转录本范围之外的序列。',
        PM4_STOP_LOSS_NEW_STOP_BEYOND_TECHNICAL_WINDOW: '在技术查询窗口内未找到新终止位点，窗口之外仍有未检查序列；本次未评价，不能据此认定不存在新终止或无限延长。',
        PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_INVALID: '该转录本的3′端序列或范围信息不合法，无法可靠推导蛋白延长；请核对材料，本次未评价。',
        PM4_STOP_LOSS_SIMPLE_SNV_REQUIRED: '当前终止丢失分支只支持简单单核苷酸替换（SNV）；本次变异不在该支持范围内，未评价。',
        PM4_STOP_LOSS_CONSEQUENCE_MISMATCH: '所选注释未形成一致的终止丢失后果，或同时包含其他不相容后果；本次未评价。',
        PM4_STOP_LOSS_OUTSIDE_TERMINAL_CODON: '变异没有映射到该精确转录本的原终止密码子，不能按终止丢失推导；本次未评价。',
        PM4_STOP_LOSS_NOT_CONFIRMED: '序列核对后原位置仍为终止密码子，未确认终止丢失；本次未评价。',
        PM4_PROJECT_LOCAL_OPERATIONAL_NON_REPEAT_MET: '通用项目政策支持非重复推定；并非绝对非重复证明或临床采用。',
        PM4_OPERATIONAL_LOW_COMPLEXITY_OR_DISORDER_EXCLUDED: '与来源注释的低复杂度或无序区域相交，当前推定政策停止判断。',
        PM4_OPERATIONAL_SOURCE_COMPLETENESS_REQUIRED: '需要完整同版本区域来源及全序列一致性证明。',
        PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET: '本项目PM4条件已满足；仍不代表临床采用或最终分类。',
        PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET: '已完成判定，与明确重复区重叠，因此不满足本项目PM4条件；不等于BP3成立。',
        PM4_SHORT_REPEAT_CANDIDATE_REQUIRES_QUALIFICATION: '存在短串联重复候选，区域适用性需进一步核定；未评价不等于不满足。',
        PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED: '缺少可采用的非重复依据；未命中数据库或预测不能替代负向证明。',
        PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION: '该基因存在专门规范，需要核对PM4条款适用性。',
        PM4_SUPPORTED_NON_REPEAT_LENGTH_CHANGE: '满足本次限定的非重复区蛋白长度改变条件；计算结论尚未科学采用。',
        PM4_REPEAT_REGION: '受影响区间位于重复区域，不满足本次 PM4 政策；不代表良性。',
        PM4_FUNCTIONAL_CONTEXT_NOT_MET: '功能上下文不满足本次政策条件；不代表良性。',
        PM4_MODEL_MISSING: '缺少受信精确编码模型，请由服务端补齐匹配版本及来源；本候选未评价。',
        PM4_REGION_MISSING: '缺少受信重复区及功能上下文材料，请由服务端补齐；缺记录不等于非重复区。',
        PM4_REGION_COVERAGE_MISSING: '区域覆盖为空，请补齐覆盖实际编辑区间的受信材料；不能据此判断非重复区。',
        PM4_REGION_COVERAGE_INCOMPLETE: '区域覆盖不足或上下文未知，请补齐材料；本候选未评价。',
        PM4_NO_NET_LENGTH_CHANGE: '没有净蛋白长度改变，不满足本次 PM4 长度改变条件。',
        PM4_CONTEXT_MISSING: '尚未形成完整背景，请核对疾病关系、版本化转录本及任务来源。',
        PM4_TRANSCRIPT_CONFIRMATION_REQUIRED: '请重新读取并确认当前疾病关系及精确版本化转录本。',
    };
    if (pm4Reasons[code]) return pm4Reasons[code];
    if (typeof code === 'string' && code.startsWith('PM4_') && code.includes('UNSUPPORTED'))
        return '该变异或注释超出当前 PM4 简单框内 indel 或终止丢失 SNV 分支的支持范围，本候选未评价；请核对具体原因，不能通过额外勾选绕过。';
    if (typeof code === 'string' && code.startsWith('PM4_'))
        return 'PM4 输入、来源或身份尚未通过核验，请按所示原因核对服务端材料并重新读取；本候选未评价。';
    const predictionReasons = {
        LICENSE_SOURCE_UNBOUND: '许可原文尚未绑定到当前资源。',
        USE_PURPOSE_UNDECLARED: '缺少本次资源使用目的。',
        USE_RESTRICTION_CONFLICT: '本次用途超出已核验的资源许可或公开控制范围。',
        PREDICTION_LOCAL_BINDING_UNCONFIGURED: '未配置服务端核验过的资源绑定。',
        PREDICTION_RESOURCE_FILE_CHANGED: '数据或索引文件已不同于核验回执，需要重新核对身份。',
        PREDICTION_PUBLIC_CONTROL_NOT_REGISTERED: '此位点不在本次预登记公开控制范围内。',
        PREDICTION_ASSOCIATION_TRANSCRIPT_INPUT_MISSING_OR_NOT_UNIQUE: '缺少可唯一关联的选定 VEP 转录本、版本或基因身份；原始分数仍保留。',
        EFFECT_REVIEW_STORE_UNAVAILABLE: '本地采用复审记录目录尚不可读，不能据此认定没有采用限制。',
        EFFECT_SOURCE_INTEGRITY_OR_READ_FAILURE: '已保存的采用来源读取或摘要核验失败。',
        EFFECT_DECLARED_REFERENCES_UNRESOLVED: '已有声明引用尚未解析，不能作为无重叠证据。',
        EFFECT_MECHANISM_LINK_UNKNOWN: '已采用证据与具体机制的关联尚未确认。',
        EFFECT_CONTEXT_OR_LINK_INVALID: '采用来源的背景或关联未通过核验。',
        EFFECT_SCOPE_RUN_LIMIT_EXCEEDED: '本分析的来源记录超过有界读取上限，覆盖尚不完整。',

        CALIBRATION_PRECISION_UNRESOLVED: '论文显示区间已登记，但当前原始分数精度的比较规则尚未核验；不能四舍五入后判定。',
        CALIBRATION_RESOURCE_COMPATIBILITY_UNVERIFIED: '打分资源与来源校准的兼容证明尚未完成。',
        CALIBRATION_RESOURCE_IDENTITY_MISMATCH: '实际资源或校准身份与预定要求不匹配。',
        PREDICTION_RESOURCE_USE_UNVERIFIED: '具体资源版本的许可及本次研究用途尚未核验，未执行取分。',
        SPEC_APPLICABILITY_UNRESOLVED_CONTEXT_MISSING: '疾病背景尚未形成，不能确定专规适用性；原始观测不代表证据可应用。',
        GN017_COMPLETE_PREDICTION_ADAPTATION_PENDING: '已命中指定 REVEL 的专规，但完整预测条款尚未适配；不会改用 AlphaMissense。',
        PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE: '选定转录本与来源注释未形成唯一关联。',
        TRANSCRIPT_PROTEIN_IDENTITY_UNRESOLVED: '原始记录与选定转录本／蛋白身份尚未核验，需要准确版本或序列等价证明。',
        SAME_IDENTITY_SCORE_CONFLICT: '同一身份存在无法协调的不同原始分数。',
        SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED: '剪接注释与校准使用的转录本集合尚未证明兼容。',
        SPLICE_PARAMETERS_INCOMPATIBLE: '剪接 raw／masked、D 或 M 参数与校准要求不符。',
        SPLICE_EXECUTION_NOT_AUTHORIZED: '本轮未授权实际剪接模型推理。',
        BP4_REQUIRED_MECHANISM_UNRESOLVED: 'BP4 所需机制尚未全部核验，不能将未知当作无影响。',
        BP4_REQUIRED_MECHANISM_NOT_BENIGN: '至少一个必要机制不支持良性方向，BP4 不满足。',
        PP3_ALLOWED_MECHANISM_UNRESOLVED: '允许提供 PP3 的机制仍有未决项，具体阶段见机制明细。',
        NO_PLANNED_MECHANISM_SUPPORTS_PP3: '已检查的计划机制均不支持 PP3。',
        PRESELECTED_MECHANISM_SUPPORTS_PP3: '预先允许采用的机制支持 PP3；未叠加其他机制强度。',
        ALL_REQUIRED_MECHANISMS_BENIGN_NO_ADDITION: '必要机制均支持良性方向，仅采用预选主机制强度。',
        SCORE_UNINFORMATIVE: '分数处于无信息区。',
        CALIBRATED_INTERVAL: '分数位于已登记校准区间；合成网格验证不代表真实精度已获批。',
        SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE: '尚未取得可核验的同效应采用限制记录，不推断实验不存在。',
        SAME_EFFECT_ALREADY_ADOPTED: '已有保存证据对同一效应的采用限制，不能重复计证。',
        PREDICTION_NATIVE_SNV_REQUIRED: '当前开发范围仅支持原生 GRCh38 SNV，需要真实来源而非改写版本标签。',
        PROTEIN_MISSENSE_NOT_REQUIRED: '此剪接路径不要求蛋白错义机制。',
    };
    if (predictionReasons[code]) return predictionReasons[code];
    if (code === 'BA1_SAVED_RESULT_ASSOCIATION_INVALID') return '保存结果与本次候选或背景的关联不完整或不一致；请重新读取当前结果，仍不一致时需修复来源关联，不采用旧结论补位。';
    const recommendationReasons = {
        BA1_MAPPING_ANNOTATIONS_MISSING: '缺少候选转录本注释。',
        BA1_MAPPING_CATALOG_IDENTITY_INVALID: '基因映射目录的来源身份或完整性未通过核验。',
        BA1_MAPPING_ANNOTATION_MISSING: '该位置的注释为空。',
        BA1_MAPPING_SOURCE_INVALID: '注释来源未满足现有 VEP 来源要求。',
        BA1_MAPPING_VERSION_MISSING: '注释缺少来源版本。',
        BA1_MAPPING_STATUS_INVALID: '注释状态未满足成功完成要求。',
        BA1_MAPPING_ENSEMBL_NOT_FOUND: '活动目录未找到该 Ensembl gene ID 对应的 HGNC 映射。',
        BA1_MAPPING_IDENTIFIERS_MISSING: '注释缺少 geneId 和 geneSymbol。',
        BA1_MAPPING_NOT_FOUND: '活动目录未找到所提供基因标识的映射。',
        BA1_MAPPING_IDENTIFIER_CONFLICT: 'geneId 与 geneSymbol 指向不同 HGNC 标识。',
        BA1_MAPPING_AMBIGUOUS: '基因标识对应多个 HGNC 结果，身份无法唯一确定。',
        BA1_MAPPING_HGNC_INVALID: '映射结果不是有效的 HGNC 标识。',
        BA1_POLICY_SELECTED: '已核验当前基因背景并选择适用的 BA1 判断条件。',
        BA1_GENE_MAPPING_REQUIRED: '需要补齐全部相关 VEP 基因与 HGNC 的可靠映射；不能忽略重叠基因后使用通用阈值。',
        BA1_DISEASE_CONTEXT_REQUIRED: '发现可能适用的疾病专用 BA1 规范，请在现有确认区补充疾病与遗传方式。',
        BA1_DISEASE_CONTEXT_CONFIRMATION_REQUIRED: '已有确认仅用于其他证据，请单独确认本次 BA1 疾病背景。',
        BA1_DISEASE_SCOPE_UNRESOLVED: '尚不能确认所选疾病和遗传方式属于或排除于该规范，需要核验疾病范围关系。',
        BA1_SPECIFICATION_SCOPE_UNRESOLVED: '相关规范的基因、疾病、遗传方式或转录本范围尚不完整，需要补齐来源映射。',
        BA1_TRANSCRIPT_CONTEXT_REQUIRED: '该规范限制了转录本，需要确认有来源依据的带版本转录本。',
        BA1_TRANSCRIPT_SCOPE_UNRESOLVED: '转录本或版本与规范不一致，需要明确的版本对应依据。',
        BA1_DISABLED_BY_SPECIFICATION: '该疾病规范明确不采用 BA1，因此本背景不适用。',
        BA1_VARIANT_TYPE_UNSUPPORTED: '此变异类型不在本轮原生 GRCh38 SNV 与受限短插入/缺失支持范围内。',
        BA1_JOINT_POPULATION_INPUT_REQUIRED: '此规范需要 gnomAD 外显子组与基因组的联合频率，当前只有本地外显子组资料。',
        BA1_EXOMES_FILTER_INPUT_MISSING: '外显子组过滤证据不足，需要同版、同等位基因的明确过滤结果。',
        BA1_GENOMES_FILTER_INPUT_MISSING: '基因组过滤证据不足，需要同版、同等位基因的明确过滤结果。',
        BA1_SUBTYPE_TIMEOUT: '原生过滤补证查询超时；可显式重试，不能把超时当作过滤通过。',
        BA1_SUBTYPE_FILTER_UNRESOLVED: '两类原生记录尚未全部明确通过过滤，请查看各来源的查询结果。',
        UNSUPPORTED_CLAUSE: '已找到相关规范，但其附加条件尚不能被当前算法完整执行；需要补齐条款解析与核验。',
        PREDICTION_TRANSCRIPT_CONFIRMATION_REQUIRED: '预测规则仍需确认预测转录本；PM2 使用已核验的临床注释独立评价。',
        HNF1A_REVEL_TRANSCRIPT_CONTEXT: '已匹配 HNF1A REVEL 专用转录本上下文；仍需核验来源记录及分数，不以 AM 可用性为前提。',
        REVEL_RESOURCE_IDENTITY_INVALID: 'REVEL 资源版本或完整性核验未通过；当前未评价，不回退 AM。',
        REVEL_RESOURCE_DISABLED: '当前运行配置未启用 REVEL 资源；HNF1A 专用路径保留未评价，不回退 AM。',
        REVEL_RECORD_CONFLICT: 'REVEL 精确匹配的来源记录存在冲突；不会挑选有利分数或回退 AM。',
        REVEL_SPLICE_BRANCH_NOT_IMPLEMENTED: '当前 REVEL 仅支持适用的错义蛋白预测，剪接分支尚未接入；不能据此排除剪接影响。',
        NO_COMPATIBLE_TRANSCRIPT:
            '没有满足当前预测路径要求的带版本转录本；请检查匹配或剪接位置的具体原因。',
        BP7_INTRONIC_SPLICE_PROXIMAL: '该内含子位置靠近剪接边界，不适用当前 BP7 内含子分支。',
        BP7_SELECTED_EXECUTION_UNAVAILABLE: 'BP7 在软件执行时失败，不能据此认定数据库无记录或证据不满足。历史结果保持原样，请由维护者核查；不要仅为消除此提示重复运行科学程序。',
        BP7_PREDICTION_PREPARATION_BLOCKED: '共享预测准备未完成，BP7 尚未评价；请查看同次 PP3/BP4 的具体阻断原因。',
        BP7_PREPARED_PLAN_UNAVAILABLE: 'BP7 所需的准备记录缺失或结构不完整，需要维护者核查。',
        BP7_CONSEQUENCE_NOT_APPLICABLE:
            '所选后果不属于当前 BP7 同义或内含子分支；不会为 BP7 额外运行模型。',
        BP7_SYNONYMOUS_EXON_BOUNDARY: '该同义位点位于外显子首位或末尾三个碱基，不适用当前 BP7。',
        SYNONYMOUS_POSITION_IDENTITY_MISMATCH: '外显子位置证据与当前变异或所选转录本不一致。',
        SYNONYMOUS_POSITION_TRANSCRIPT_MISMATCH: '外显子位置证据的转录本或注释版本不一致。',
        BP7_POSITION_CLASS_AMBIGUOUS: '注释中的位置类别不明确，需核查 VEP 注释后再评价。',
        TRANSCRIPT_RELATIVE_POSITION_MISSING:
            '所选 VEP 注释缺少可靠的外显子边界或内含子偏移；系统不会猜测剪接距离。',
        SPLICEAI_RAW_RUNTIME_UNAVAILABLE:
            '本机 SpliceAI raw 运行配置不可用，请联系管理员核查已有环境；BA1 不受影响。',
        BP4_NOT_EVALUATED: '同份 SpliceAI 证据的 BP4 未完成评价，BP7 不能满足。',
        PM2_POLICY_NOT_FOUND: '该基因、疾病及遗传方式尚无已启用的 PM2 政策。',
        PM2_POLICY_AMBIGUOUS: '匹配到多个 PM2 政策，需核查政策来源。',
        PM2_DISEASE_FREQUENCY_PARAMETERS_REQUIRED: '缺少有依据的疾病专用 PM2 频率及检测条件；无唯一适用政策时不启动取证，系统不会使用全基因统一阈值。',
        PM2_SPECIALIST_POLICY_REVIEW_REQUIRED: '已找到相关疾病专用规范，但其适用关系或评价条件尚未完整接通；请查看规范来源与缺口。',
        PM2_ABSENCE_DETECTION_POLICY_REQUIRED: '数据库无记录；当前政策尚缺少已核验的检测充分性判据。',
        PM2_ALL_SITES_QUERY_FAILED: '独立检测充分性资源查询失败；人口记录仍保留，不能据此认定变异不存在。',
        PM2_ALL_SITES_EVIDENCE_MISSING: '缺少独立的 all-sites 检测数据，请核查资源配置。',
        PM2_ALL_SITES_NOT_CONFIGURED: '独立检测充分性资源尚未配置，已观测变异的频率评价仍可使用。',
        PM2_ALL_SITES_CONFIGURATION_INVALID: '独立检测充分性资源配置无法读取或无效，需要管理员修复。',
        PM2_CALLABILITY_NOT_SUFFICIENT: '独立检测数据未达到当前政策要求的有效样本量或区域质量条件。',
        PM2_OBSERVED_SAMPLE_SIZE_INSUFFICIENT: '有效等位基因样本量低于该专用规范要求。',
        PM2_HOMOZYGOTE_INPUT_INVALID: '纯合子计数缺失、不合法或与等位基因计数冲突。',
        PM2_NO_FAF_SPECIAL_CASE_REVIEW_REQUIRED: '没有可用 FAF，且不属于已实现的单例分支；需要复核专用规范条件。',
        PM2_INDEL_DETECTION_PROOF_REQUIRED: '单碱基位点覆盖不能充分证明该 indel 可检出，暂不根据无记录给予 PM2。',
        PM2_POPULATION_INPUT_INVALID: '变异身份、上下文或参考基因组不一致，需要核对来源。',
        PM2_ASSEMBLY_UNSUPPORTED: '当前 PM2 资源不支持此参考基因组，未自动转换坐标。',
        PM2_NATIVE_SMALL_VARIANT_INPUT_UNSUPPORTED: '当前人口频率评价尚不支持此变异类型或参考基因组。',
        NATIVE_SMALL_VARIANT_ALLELE_UNSUPPORTED: '当前来源校验仅支持已规范化的小变异，暂不支持此等位基因表示。',
        ALPHAMISSENSE_VARIANT_TYPE_UNSUPPORTED: '当前 AlphaMissense 路径不支持此变异类型；人口频率证据单独处理。',
        SPLICEAI_BUSINESS_VARIANT_TYPE_UNSUPPORTED: '当前剪接预测评价尚限于 SNV；人口频率证据单独处理。',
        SMALL_VARIANT_NORMALIZATION_REQUIRED: '该小变异仍需完成规范化及来源核验，暂不能进入证据评价。',
        PM2_POLICY_NOT_FOUND_OR_AMBIGUOUS: '缺少唯一适用的 PM2 政策，未评价。',
        PM2_ABSENCE_DETECTION_INSUFFICIENT: '未检出该等位基因，但缺少独立的位点检测充分性证明，不能视为频率为零。',
        PM2_VERIFIED_CONTEXT_MISSING: '缺少已核验的基因、疾病、遗传方式或转录本上下文。',
        PM2_DISEASE_CATALOG_NO_RECORD: '当前加载的疾病关系目录按 HGNC 与基因符号未命中记录；需补充有来源且通过现有核验的疾病关系数据，当前没有可确认背景。这不表示该基因不存在疾病关系。',
        PM2_DISEASE_RELATIONSHIP_NOT_SELECTABLE: '目录有记录，但分级、遗传方式或身份条件未形成可选疾病关系；请核对具体关系，不能直接确认。',
        PM2_VERIFIED_CLINICAL_ANNOTATION_REQUIRED: '已有疾病关系，但尚未确定唯一、来源完整的上下文注释；需核对转录本版本、重复注释及优先标记。',
        PM2_CONTEXT_POLICY_NO_MATCH: '已有疾病关系，但当前 PM2 政策没有匹配该 HGNC、MONDO 与遗传方式；需经既有规范流程补齐政策。',
        PM2_CONTEXT_CONFIRMATION_REQUIRED: '存在上下文准备选项；请在候选详情确认真实适用的疾病假设及转录本用途，再整体确认提交。',
        PM2_EXECUTION_SOURCE_NOT_READY: 'PM2 上下文所需执行来源未通过核验；请查看来源阻断，不能仅补填疾病或转录本。',
        PM2_OBSERVED_FREQUENCY_INPUT_INCONSISTENT: '人群频率、祖源或 AC/AN 数据不一致，未评价。',
        PM2_ANCESTRY_NOT_SUPPORTED: '该祖源不属于适用政策允许的频率计算范围。',
        PM2_DISABLED_LOCAL_EXOMES_BA1_SCOPE: '本次为 BA1 本地模式，PM2 尚未启用，不进行远程补查。',
        PM2_BUSINESS_EXECUTION_DISABLED: '本机 PM2 显式评价尚未启用。',
        PM2_RUNTIME_NOT_CONFIGURED: 'PM2 真实查询运行配置不可用。',
        BS1_CONTEXT_MISSING: '尚未形成 BS1 独立背景，请核对本次疾病关系确认及来源诊断。',
        BS1_DISEASE_CATALOG_NO_RECORD: '当前疾病关系目录未命中该候选的 BS1 背景，不能凭空填写疾病。',
        BS1_EXECUTION_SOURCE_NOT_READY: '本次候选的执行来源尚未通过核验，请先查看来源阻断。',
        BS1_DISEASE_RELATIONSHIP_NOT_SELECTABLE: '该疾病关系与当前候选的关联或遗传模式不满足选择条件。',
        BS1_CONTEXT_CONFIRMATION_REQUIRED: '已有 BS1 政策选项，仍需确认本次候选的疾病背景及 BS1 用途。',
        BS1_POLICY_NO_RECORD: '当前 BS1 政策目录未命中该疾病关系；不代表不存在疾病关系。',
        BS1_DISEASE_PARAMETERS_MISSING: '已找到基因与疾病关系，但缺少可用于 BS1 的完整疾病参数；患病率、外显率及基因／变异贡献比例需要有据可查。',
        BS1_KNOWLEDGE_RELATIONSHIP_NO_RECORD: '本地疾病知识尚未找到该精确基因、疾病与遗传方式的关系，不代表疾病不存在。',
        BS1_RELATIONSHIP_CONFLICT: '疾病关系资料存在冲突，不能自动选取其中一份用于 BS1。',
        BS1_RELATIONSHIP_NOT_ESTABLISHED: '当前疾病关联证据不足以用于自动 BS1 判定。',
        BS1_KNOWLEDGE_REVIEW_REQUIRED: '已取得疾病知识候选，但其参数或适用范围尚未完成本地采用核对。',
        BS1_EXPERT_SPECIFICATION_REQUIRES_STRUCTURING: '已找到相关专家规范，但其完整条件尚未转为可执行规则，不能用通用阈值绕过。',
        BS1_MODEL_POPULATION_SCOPE_REQUIRED: '疾病参数缺少明确的适用人群范围，不能直接套用到所有祖源。',
        BS1_MODEL_MOI_UNSUPPORTED: '当前通用阈值模型支持常染色体显性与隐性，该遗传方式尚未实现。',
        BS1_PREVALENCE_STATISTIC_UNSUPPORTED: '取得的流行病学统计口径不适合当前模型，不能把发病率或病例数直接当作患病率。',
        BS1_MODEL_STRENGTH_ADOPTION_REQUIRED: '频率阈值已能计算，但其 BS1 证据强度与采用依据尚未确定。',
        BS1_POLICY_AMBIGUOUS: '该背景匹配多份 BS1 政策，尚不能唯一选择。',
        BS1_NO_RECORD: '本地人口数据中未找到这个精确变异；不能把未命中当作频率为零，也不会自动联网补查。',
        BS1_LOCAL_FILTER_NOT_PASS: '本地 exomes 原生质量标记不是 PASS，不能用于本次 BS1 判断。',
        BS1_LOCAL_AN_INSUFFICIENT: 'FAF 所选祖源的 AN 小于本地保护下限 2000，样本支持不足，本次未评价。',
        BS1_FAF_ANCESTRY_MISSING: '人口记录缺少可用的 GroupMax FAF95 所属祖源，不能改用普通 AF 或别的祖源代替。',
        BS1_FAF_ANCESTRY_UNSUPPORTED: 'FAF 所选祖源不在本次策略支持范围内。',
        BS1_FAF_ANCESTRY_MISMATCH_OR_MISSING: '最高 FAF95 与同祖源 FAF95 缺失或不一致，无法可靠比较疾病阈值。',
        BS1_SELECTED_AN_INVALID: '同祖源 AN 缺失或不是有效正整数，未评价。',
        BS1_SELECTED_AC_INVALID: '同祖源 AC 缺失或与 AN 不相容，未评价。',
        BS1_SELECTED_AF_INVALID: '同祖源 AF 缺失、越界或与 FAF95 不一致，未评价。',
        BS1_AF_QUALIFICATION_REQUIRED: '普通 AF 规则缺少绑定来源和适用条件的采用记录，不能代替原 FAF95 规则。',
        BS1_AF_SOURCE_INTERPRETATION_REQUIRED: '来源中的 AF／MAF 含义尚未明确，不进行字段替换或参考等位基因频率换算。',
        BS1_AF_POPULATION_AN_INVALID: '声明人群的 AN 缺失或不合法，无法确定是否满足样本量要求。',
        BS1_AF_POPULATION_AC_INVALID: '声明人群的目标等位基因 AC 缺失或不合法，未评价。',
        BS1_AF_POPULATION_AF_INVALID: '声明人群的普通 AF 缺失或越界；不能用 FAF95 或其他人群代替。',
        BS1_AF_POPULATION_COUNT_CONFLICT: '同一祖源的 AF 与 AC／AN 不一致，未评价。',
        BS1_AF_NO_ELIGIBLE_POPULATION: '声明范围内没有达到规则样本量要求的人群；这不表示频率为零。',
        BS1_MAF_TARGET_ALLELE_INTERPRETATION_UNRESOLVED: '目标等位基因频率超过一半，超出本次 MAF 解释范围；未擅自取 1−AF。',
        BS1_COUNT_AF_CONFLICT: '同祖源 AC/AN 与 AF 不一致，需核查原始记录。',
        BS1_RESOURCE_EXECUTION_UNAVAILABLE: '本地人口查询未成功完成；这是资源或执行缺口，不是该变异无记录。',
        BS1_SHARED_POPULATION_IDENTITY_MISMATCH: '共享人口事实与本次变异或分析来源不一致，已拒绝复用。',
        BS1_SHARED_NORMALIZATION_MISMATCH: '共享事实的归一化方法或参考版本不相容，已拒绝复用。',
        BS1_OBSERVATION_VERIFICATION_FAILED: '人口原始记录与保存事实或来源绑定未通过核验，未评价。',
        BS1_EXCEPTION_IDENTITY_UNRESOLVED: '此背景的已知例外尚未完成精确变异身份映射，不能把未知例外当作未命中。',
        BS1_POLICY_IDENTITY_UNRESOLVED: '规范中的基因、疾病或遗传方式身份尚未明确。',
        BS1_POLICY_SEMANTICS_UNSUPPORTED: '该规范的适用条件尚未完整实现，不能套用其他背景阈值。',
        BS1_RESOURCE_COMPATIBILITY_PENDING: '该 BS1 政策的资源兼容尚未获准执行。',
        BS1_SAVED_RESULT_ASSOCIATION_INVALID: '保存的 BS1 候选、背景或证据身份不一致，不能使用其他背景结果补位。',
        HNF1A_FREQUENCY_POLICY_NOT_APPLICABLE: '此历史 BS1 路径仅支持 HNF1A、单基因糖尿病及常染色体显性遗传这一疾病关系。',
        HNF1A_FREQUENCY_DECLARATION_REQUIRED: '需要明确确认 HNF1A 疾病频率评价用途；旧 PM2 专用确认不能授权 BS1。',
        HNF1A_FREQUENCY_ASSEMBLY_UNSUPPORTED: '当前 HNF1A 疾病频率资源仅支持原生 GRCh38，未自动转换坐标。',
        HNF1A_FREQUENCY_NATIVE_SNV_REQUIRED: '当前 HNF1A 疾病 BA1/BS1 仅支持已核验 REF 的原生 GRCh38 SNV。',
        HNF1A_POPULATION_OBSERVATION_INVALID: '人口观测的原始行、资源或执行来源校验未通过，未评价。',
        HNF1A_FREQUENCY_DISCORDANT_REVIEW_REQUIRED: '外显子与基因组频率存在显著差异，需复核；本次未评价。',
        HNF1A_FREQUENCY_DISCORDANCE_UNKNOWN: '缺少有效的频率差异统计，不能认定两种来源一致；本次未评价。',
        HNF1A_FAF_ANCESTRY_MISMATCH: '最高 FAF 与所选祖源的 FAF 记录不一致，需复核。',
        HNF1A_SUBTYPE_FILTER_REVIEW_REQUIRED: 'gnomAD 子类型质量标记需复核；本次未评价。',
        HNF1A_EXCEPTION_REVIEW_REQUIRED: '相关例外尚未解决，需复核后再评价疾病频率证据。',
        PVS1_POLICY_NOT_APPLICABLE: '该基因、疾病及遗传方式不适用本次 HNF1A PVS1 政策。',
        PM1_POLICY_NOT_APPLICABLE: '当前上下文不在本次 HNF1A 单基因糖尿病 PM1 政策范围内。',
        PM1_TRANSCRIPT_NOT_SUPPORTED: '该转录本或注释版本尚未纳入本次 PM1 范围。',
        PM1_NATIVE_GRCH38_SNV_REQUIRED: '本次 PM1 仅接入原生 GRCh38 错义 SNV，其他类型尚未接入。',
        PM1_VERIFIED_CONTEXT_MISSING: 'PM1 缺少已确认的疾病或转录本上下文。',
        PM1_TRANSCRIPT_CONFIRMATION_REQUIRED: '当前只确认了 PM2 疾病假设；PM1 还需确认疾病相关转录本。',
        PM1_SELECTED_TRANSCRIPT_NOT_UNIQUE: '保存的转录本记录缺失或重复，无法唯一核验。',
        PM1_CONSEQUENCE_NOT_APPLICABLE: '该后果不属于本次 PM1 错义蛋白位置分支。',
        PM1_SPLICE_MECHANISM_UNRESOLVED: '存在尚未厘清的剪接后果，不能直接使用本次蛋白位置分支。',
        PM1_PROTEIN_CODON_MISMATCH: '蛋白、氨基酸或密码子注释缺失或与独立序列推导不一致。',
        PM1_GENOMIC_CDS_MAPPING_MISMATCH: '基因组等位基因与 CDS 编号或编码注释不一致。',
        PM1_CDS_REFERENCE_MISMATCH: '参考碱基与已核验的 CDS 不一致。',
        PM1_CODING_POSITION_UNRESOLVED: '无法可靠解析版本化编码位置。',
        PM1_EXON_STRUCTURE_MISMATCH: '注释外显子与参考结构不一致。',
        PM1_INITIATION_BRANCH_NOT_IMPLEMENTED: '起始密码子分支尚未接入，不能强行按错义评价。',
        PM1_MISSENSE_NOT_CONFIRMED: '独立序列推导未确认错义替换。',
        PM1_CONTEXT_IDENTITY_INVALID: 'PM1 上下文身份校验未通过。',
        PM1_VEP_SOURCE_IDENTITY_INVALID: '保存的 VEP 来源与输入身份不一致。',
        PM1_BUSINESS_EXECUTION_DISABLED: '本机 PM1 显式评价尚未启用。',
        PM1_EXECUTION_SOURCE_UNAVAILABLE: 'PM1 缺少可重建的正式分析来源。',
        PVS1_TRANSCRIPT_NOT_SUPPORTED: '该转录本或注释版本尚未纳入 PVS1 政策。',
        PVS1_VERIFIED_CONTEXT_MISSING: 'PVS1 缺少已确认并核验的疾病或转录本上下文。',
        PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED: '当前仅确认人口频率用途；PVS1 仍需确认其疾病相关转录本。',
        PVS1_SELECTED_TRANSCRIPT_NOT_UNIQUE: '无法唯一确定保存的 VEP 转录本记录。',
        PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED: '该位点超出已接入的 NMD 分支；需按 HNF1A 专用规范另行评价，不代表良性。',
        PVS1_START_PROXIMAL_BRANCH_NOT_IMPLEMENTED: '起始端附近的提前终止尚未纳入本次 PVS1 范围。',
        PVS1_SPLICE_BRANCH_NOT_IMPLEMENTED: '该变异还涉及剪接后果，超出本次 PVS1 截短分支。',
        PVS1_CONSEQUENCE_NOT_APPLICABLE: '本次 PVS1 仅接入受限的无义 SNV 及简单短插入/缺失所致移码分支。',
        PVS1_GENOMIC_CDS_MAPPING_MISMATCH: '基因组位点与转录本 CDS 映射不一致，未评价。',
        PVS1_EXON_STRUCTURE_MISMATCH: 'VEP 外显子与已核验的转录本结构不一致。',
        PVS1_PROTEIN_CODON_MISMATCH: '密码子或蛋白后果缺失、存在不一致，未评价。',
        PVS1_BUSINESS_EXECUTION_DISABLED: '本机 PVS1 显式评价尚未启用。',
        PVS1_RUNTIME_NOT_CONFIGURED: 'PVS1 运行配置不可用。',
        PVS1_NATIVE_SNV_REQUIRED: '本次 PVS1 仅覆盖原生 GRCh38 单核苷酸替换。',
        PVS1_NATIVE_SMALL_VARIANT_REQUIRED: '本次 PVS1 仅覆盖原生 GRCh38、可核验的小变异。',
        PVS1_SIMPLE_SHORT_INDEL_REQUIRED: '该变异不在本次简单短插入/缺失范围内，尚未评价。',
        PVS1_FRAMESHIFT_NOT_CONFIRMED: '独立序列核验未确认移码，未据此评价 PVS1。',
        PVS1_CODING_INDEL_MAPPING_UNRESOLVED: '插入/缺失无法完整映射至已核验编码区，未评价。',
        PVS1_HGVSC_SEQUENCE_MISMATCH: '保存的 HGVS 编码注释与原生等位基因推导的改变后序列不一致。',
        PVS1_FRAMESHIFT_PROTEIN_MISMATCH: '保存的蛋白注释与独立移码序列推导不一致，未评价。',
        PVS1_FRAMESHIFT_ANNOTATION_INCOMPLETE: '移码交叉核验所需的版本化编码或蛋白注释不完整。',
        PVS1_TERMINATION_UNRESOLVED: '已核验编码序列不足以确定移码后的首次终止位置，未评价。',
        PVS1_PTC_NOT_PREMATURE: '未确认本分支所需的提前终止，不能据此套用 NMD 路径。',
        PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED: '变异或新终止涉及尚未接入的外显子边界分支，未评价。',
        PVS1_CONTEXT_IDENTITY_INVALID: 'PVS1 上下文身份校验未通过。',
        PVS1_VEP_SOURCE_IDENTITY_INVALID: '保存的 VEP 记录与评价对象身份不一致。',
        PVS1_CODING_POSITION_UNRESOLVED: '无法可靠解析该转录本的编码位置。',
        PVS1_CDS_REFERENCE_MISMATCH: '变异参考碱基与已核验的 CDS 序列不一致。',
        PVS1_STOP_GAIN_NOT_CONFIRMED: '序列核验未确认提前终止，未据此评价 PVS1。',
        PVS1_VARIANT_TRANSCRIPT_INPUT_INVALID: 'PVS1 位点与转录本输入缺失或无效。',
        PVS1_LONG_EXON_BRANCH_NOT_IMPLEMENTED: '该外显子超出已核验的 NMD 分支范围。',
        PVS1_RESOURCE_UNAVAILABLE: 'PVS1 所需资源暂不可用。',
        PVS1_RESOURCE_MISSING: 'PVS1 所需资源缺失。',
        PVS1_RESOURCE_INVALID: 'PVS1 资源内容校验未通过。',
        PVS1_PROFILE_IDENTITY_INVALID: 'PVS1 政策版本身份校验未通过。',
        PVS1_TRANSCRIPT_MODEL_IDENTITY_INVALID: 'PVS1 转录本模型版本身份校验未通过。',
        PVS1_FULL_TRANSCRIPT_SEQUENCE_AND_STRUCTURE_VERIFIED: '本地参考序列、完整转录本及外显子结构已核对一致；继续使用原疾病政策。',
        PVS1_SUPPLIED_TRANSCRIPT_IDENTITY_MISMATCH: '本地转录本的精确版本、蛋白或链方向与政策依据不一致，不能套用。',
        PVS1_SUPPLIED_TRANSCRIPT_SEQUENCE_MISMATCH: '本地转录本的编码序列或完整 RNA 序列与政策依据不一致，不能仅凭相同基因继续判定。',
        PVS1_SUPPLIED_TRANSCRIPT_STRUCTURE_MISMATCH: '本地转录本的外显子或连接位置与政策依据不一致，无法可靠预测 NMD。',
        PVS1_BOUND_FULL_TRANSCRIPT_PROOF_MISSING: '政策材料缺少完整转录本序列的核验依据；仅有编码序列不足以核对 NMD 所需结构。',
        PVS1_TRANSCRIPT_MODEL_CONTEXT_MISMATCH: '政策中的转录本版本或基因位置与本次选择不一致。',
        PVS1_REGISTRY_IDENTITY_INVALID: 'PVS1 规则注册表版本身份校验未通过。',
        PVS1_SINGLE_EXON_ESCAPE_CONDITIONS_MISSING: '单外显子没有连接点；尚缺适用的非 NMD 功能损失依据，不能仅凭截短给出 PVS1。',
        PVS1_SINGLE_EXON_JUNCTION_CONFLICT: '单外显子却记录了连接点，模型资料相互矛盾。',
        PVS1_SPECIAL_TOPOLOGY_SEQUENCE_PROOF_MISSING: '特殊转录本结构缺少完整 RNA 和编码序列核验依据。',
        PVS1_SPECIAL_TOPOLOGY_SPLICE_NOT_IMPLEMENTED: '特殊模型同时涉及剪接，当前新增路径只支持纯编码无义和简单移码。',
        PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED: '已取得末次连接点及距离，但连接点位于编码区末端或 3′UTR，尚缺该结构下的 NMD 适用依据。',
        PVS1_UTR_JUNCTION_APPLICABILITY_INVALID: '3′UTR 连接点适用条件不完整或来源未绑定。',
        CANDIDATE_MOI_REQUIRED: '候选结果缺少可核验的遗传模式。',
        CANDIDATE_MOI_UNMAPPABLE: '候选遗传模式不能无损映射到当前证据合同。',
        CANDIDATE_MOI_CONFLICT: '候选来源中的遗传模式互相冲突。',
        PREDICTION_FACT_REUSE_ENTRY_LIMIT: '本次请求的不同预测事实超过缓存数量上限；未继续读取，可缩小所选范围。',
        PREDICTION_FACT_REUSE_BYTE_LIMIT: '本次预测原始事实超过缓存容量上限；未截断或继续重复读取，请检查返回记录量。',
        PREDICTION_FACT_REUSE_INTERRUPTED: '本次预测已取消或中断，停止读取及使用缓存事实。',
        INTERPRETATION_MOI_SCOPE_UNSUPPORTED: '不同排序背景之间的切换目前只支持常染色体显性与隐性；不能据此切换 X 连锁显隐性。',
        INTERPRETATION_X_LINKED_CONTEXT_REQUIRED: '来源只注明 X 连锁，但当前候选没有一致的 X 连锁显性或隐性排序背景，暂不能确认解释背景。',
        INTERPRETATION_NATIVE_X_ALLELE_REQUIRED: 'X 连锁来源衔接目前需要 GRCh38 的 X 染色体候选；该候选超出当前软件支持范围。',
        INTERPRETATION_NATIVE_AUTOSOMAL_SNV_REQUIRED: '不同排序背景的解释衔接目前只支持 GRCh38 常染色体单碱基替换；该候选超出软件支持范围。',
        INTERPRETATION_ANALYSIS_VCF_CALL_REQUIRED: '缺少唯一匹配、可追溯到已发布分析 VCF 的样本调用，无法核对目标等位基因；请核对发布来源。',
        INTERPRETATION_ANALYSIS_VCF_CALL_INVALID: '已发布分析 VCF 的样本调用含无效字段，暂不能用于不同背景的解释；请核对原始调用。',
        INTERPRETATION_CALLED_DIPLOID_GT_REQUIRED: '不同背景的解释需要完整二倍体基因型；缺失、部分缺失或其他倍性尚不支持。',
        INTERPRETATION_TARGET_ALT_NOT_PRESENT: '已发布样本调用未包含目标 ALT，不能确认该等位基因用于本次解释。',
        INTERPRETATION_PUBLISHED_SOURCE_REQUIRED: '尚未恢复合格的正式发布来源，暂不能选择不同排序背景的解释；请先解决来源核验缺项。',
        CANDIDATE_RELATIONSHIP_MOI_CONFLICT: '候选遗传模式与该基因—疾病关系不一致。',
        NO_ELIGIBLE_RELATIONSHIP: '当前目录中没有可用于该证据上下文的合格关系。',
        DISEASE_RELATIONSHIP_REVIEW_REQUIRED: '存在多个或不确定的基因—疾病关系，需要复核。',
        TRANSCRIPT_REVIEW_REQUIRED: '存在多个兼容转录本，当前不能自动确定。',
        OUTSIDE_BRAF_RASOPATHY_POLICY: '不属于本次启用的 BRAF—RASopathy 错义变异规范范围。',
        SELECTED_TRANSCRIPT_VERSION_MISMATCH:
            '所选转录本版本与参考证据不一致，不能进行可靠的蛋白关系比较。',
        COMPARATOR_INPUT_UNAVAILABLE: '参考变异查询或蛋白比较输入不可用，本次未完成 PS1/PM5 评价。',
        INDEPENDENT_REFERENCE_EVIDENCE_MISSING:
            '缺少参考变异独立成立的致病依据，不能仅凭 ClinVar 分类标签判断满足。',
        REFERENCE_ASSERTION_CONFLICT: '参考变异在当前疾病中的判读存在冲突，不能直接用于满足规则。',
        REFERENCE_QUALIFICATION_POLICY_UNSUPPORTED: '参考原始证据的采用规范缺失或尚不受支持，暂不能判定。',
        REFERENCE_INDEPENDENT_ROUTE_NOT_ENABLED: '当前适用规范尚未启用原始独立证据采用路径，不能仅凭提交者标签判定。',
        REFERENCE_INDEPENDENT_REVIEW_MISSING: '尚缺完整的原始证据、独立性或冲突审核，暂不能采用这条参考。',
        REFERENCE_INDEPENDENT_SPECIFICATION_MISMATCH: '参考证据审核与本次适用规范的版本不一致，暂不能采用。',
        REFERENCE_INDEPENDENT_ASSERTION_BINDING_MISMATCH: '参考审核与原始提交的版本、分类或日期不一致，需要核对具体来源。',
        REFERENCE_INDEPENDENT_SOURCE_UNBOUND: '参考证据尚未绑定到可追溯的原始资料，暂不能采用。',
        REFERENCE_INDEPENDENT_EVIDENCE_INCOMPLETE: '参考的逐项独立证据尚不完整，不能仅凭致病标签判定。',
        REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT: '参考证据存在重复、比较依赖或不支持的条款，需要排除循环论证。',
        REFERENCE_INDEPENDENT_STRENGTH_UNSUPPORTED: '参考证据声明的强度不在已实现范围内，暂不能判定。',
        REFERENCE_INDEPENDENT_CRITERIA_MISMATCH: '逐项证据与独立性审核中的条款集合不一致，需要核对。',
        REFERENCE_INDEPENDENT_EVIDENCE_INSUFFICIENT: '排除比较依赖后，现有独立证据不足以支持参考的致病或可能致病分类；这不代表参考为良性。',
        REFERENCE_INDEPENDENT_CLASSIFICATION_MISMATCH: '独立证据组合的分类与参考声明不一致，不能沿用原标签判定。',
        REFERENCE_ADOPTION_CLASSIFICATION_ROUTE_NOT_ENABLED: '当前规范尚未启用原分类与本次采用分类分层，不能自行降级或改写原记录。',
        REFERENCE_ADOPTION_CLASSIFICATION_CHANGE_UNSUPPORTED: '本路径只允许保留原分类或把致病参考重核为可能致病，不允许升级或采用其他分类。',
        MISSENSE_LP_CLASSIFICATION_CEILING_NOT_IMPLEMENTED: '这条可能致病参考要求目标最终分类不得超过可能致病；系统尚无最终分类器来执行该限制，因此本次停止采用。',
        MISSENSE_CLASSIFICATION_CEILING_POLICY_UNSUPPORTED: '最终分类上限的约束配置不受支持，不能仅用提示文字代替实际限制。',
        EXPERT_EVIDENCE_BINDING_INVALID: '专家资料包与本次选择的参考或文件摘要不一致，需要重新核对来源。',
        EXPERT_EVIDENCE_IDENTITY_MISMATCH: '专家资料记录的变异、基因或原提交身份与所选参考不一致，不能借用。',
        EXPERT_EVIDENCE_ASSERTION_MISMATCH: '专家资料记录的原分类、提交版本、日期或审核标签与原始断言不一致，不能采用。',
        EXPERT_EVIDENCE_DRAFT_REQUIRED: '专家原文资料必须保留待审状态，不能由资料包自行声明科学批准。',
        EXPERT_EVIDENCE_SOURCE_UNBOUND: '专家资料缺少对应的固定原始文件和来源摘要，暂不能采用。',
        EXPERT_EVIDENCE_INDEPENDENT_REVIEW_REQUIRED: '专家资料仅提供原文导航，仍须单独审核排除比较依赖后的证据，不能直接继承原分类。',
        EXPERT_ORIGINAL_SPECIFICATION_MISMATCH: '参考专家原分类所用的规范版本尚未精确绑定，或原文存在版本冲突；不能直接当作按本次规范重新评价的结果。',
        EXPERT_ORIGINAL_SPECIFICATION_SOURCE_UNBOUND: '原分类引用的规范文件尚未绑定到一致的来源、版本和摘要，暂不能采用。',
        EXPERT_ORIGINAL_SPECIFICATION_ROUTE_REQUIRED: '当前材料未明确启用保留专家原分类的采用路线，不能仅凭旧规范引用绕过资格核验。',
        REFERENCE_IDENTITY_MISMATCH: '参考变异的等位基因、转录本或来源身份不一致，需核查后再评价。',
        PS1_PM5_OVERLAPPING_SUPPORT_NOT_INDEPENDENT:
            '已采用 PS1 的蛋白比较支持，PM5 不再重复采用；这不表示生物学阴性。',
        HNF1A_UNRESOLVED_SPLICE_MECHANISM:
            '目标或参考的剪接预测超出本项蛋白比较门禁，机制尚未解决，保留未评价。',
        HNF1A_PROTEIN_ONLY_CONSEQUENCE_REQUIRED:
            '本项仅支持已核验的 HNF1A 蛋白错义 SNV，剪接或复合后果仍未开放。',
        REFERENCE_NOT_INDEPENDENT: '参考致病依据未通过独立性核验，不能依赖 PS1/PM5 循环推导。',
        SPLICE_EVIDENCE_UNAVAILABLE: '目标或参考的剪接证据不可用，不能据此认定剪接条件已满足。',
        SPLICE_CONDITION_NOT_MET: '目标或参考的实际剪接证据不满足本次规范要求。',
        PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW:
            '目标或参考存在尚未解决的公开剪接疑点，需复核后才能用于当前蛋白比较。',
        RNA_REVIEW_SCOPE_MISMATCH: 'RNA 文献核验与当前疾病、遗传方式或转录本范围不一致。',
        RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON:
            '该参考与氨基酸替换关系缺少所需的 RNA 文献核验，低预测分数不能补足。',
        PM5_AMINO_ACID_RELATION_NOT_AUDITED:
            '该氨基酸替换关系尚未完成 PM5 规范要求的核验，不能仅凭同一位置判断满足。',
        PS1_TAKES_PRECEDENCE: '同一证据关系优先采用 PS1，PM5 不再作为额外强度叠加。',
        NO_ELIGIBLE_PROTEIN_RELATION: '已评价，但未找到满足本规则要求的可信蛋白变化关系。',
        INDEPENDENT_REFERENCE_AND_BOTH_SPLICE_GATES_MET:
            '本次比较的参考独立性及目标、参考双方剪接门禁已满足；具体强度以保存结果为准。',
        NO_ELIGIBLE_REVIEWED_PROTEIN_COMPARISON:
            '本次评价未找到符合当前政策要求的已审核蛋白比较支持，规则未满足。',
        TRUSTED_REFERENCE_AND_SPLICE_CONDITIONS_MET:
            '可信参考、蛋白关系及剪接条件均通过本次规范核验。',
    };
    if (Object.hasOwn(recommendationReasons, code)) return recommendationReasons[code];
    // Unified saved results must not guess the cause from words in a new reason code.
    if (strict) return unexplainedEvidenceReason;
    if (/REVEL/.test(code)) {
        if (/NO_RECORD|NOT_FOUND|NO_MATCH/.test(code))
            return '未取得精确匹配的 REVEL 记录，不能作为 BP4 满足依据；当前 HNF1A 专用路径不回退 AM。';
        if (/SCORE/.test(code))
            return 'REVEL 分数缺失、不合法或存在冲突；当前未评价，不回退 AM。';
        if (/TRANSCRIPT|PROTEIN|AMINO|IDENTITY|REF|ALLELE|COORDINATE|MAPPING/.test(code))
            return 'REVEL 来源记录与所选变异、转录本或蛋白改变的身份核验未通过；不会忽略版本或改用 AM。';
        if (/POLICY|CONTEXT|SCOPE|CONSEQUENCE|ASSEMBLY|SNV/.test(code))
            return '当前输入不满足 HNF1A 专用 REVEL 政策的疾病、遗传方式、转录本或原生 GRCh38 错义 SNV 范围。';
        return 'REVEL 资源不可用或查询未完成；当前 HNF1A 专用路径保留未评价，不回退 AM。';
    }
    if (/REFERENCE.*(?:INDEPENDEN|CIRCULAR)|CIRCULAR.*REFERENCE/.test(code))
        return '参考变异的独立致病依据不足或存在循环引用；不能仅凭 ClinVar 分类标签满足 PS1/PM5。';
    if (/(?:PS1|PM5|MISSENSE).*SPLICE|SPLICE.*(?:REFERENCE|COMPARATOR)/.test(code))
        return '目标或参考变异的剪接条件未通过核验；请查看实际预测或实验依据，不能用 AlphaMissense 替代。';
    if (/(?:PS1|PM5|MISSENSE).*POLICY|POLICY.*(?:PS1|PM5|MISSENSE)/.test(code))
        return '当前基因、疾病、遗传方式或变异不满足已启用的 PS1/PM5 规范范围，请查看适用条件。';
    if (code === 'RECOMMENDATION_STALE')
        return '推荐策略、来源或身份内容已变化，请重新读取候选并确认；未静默更换选择。';
    if (code === 'BA1_EXCEPTION_HIT') return '不适用：命中 BA1 例外';
    if (code === 'VERIFIED_PASS') return '本项过滤检查明确通过；整条 BA1 仍须满足其他必要条件。';
    if (code === 'SELECTED_ANCESTRY_AN_BELOW_MINIMUM')
        return '所选祖源的等位基因数 AN 未达到本次规范要求；保留未评价，不能用其他祖源或总体样本量补足。';
    if (code === 'POPULATION_FREQUENCY_COUNT_AF_CONFLICT')
        return '同祖源 AC/AN 与所报 AF 超出允许的舍入差异；需核查人口数据及字段映射，不能改用 FAF 掩盖冲突。';
    if (/^(?:POPULATION|HNF1A)_FREQUENCY_DISCORDANT_REVIEW_REQUIRED$/.test(code))
        return '外显子组与基因组频率存在显著分歧；需要核查两类数据的一致性与质量，当前未评价。';
    if (/^(?:POPULATION|HNF1A)_FREQUENCY_DISCORDANCE_UNKNOWN$/.test(code))
        return '缺少可核验的外显子组与基因组频率一致性信息，尚不能完成质量判断。';
    if (code === 'BA1_EXCEPTION_MAPPING_INCOMPLETE')
        return 'BA1 例外记录缺少当前 assembly 的可靠映射；需补齐例外数据的映射依据，不能当作未命中。';
    if (/^CONVERSION_/.test(code)) {
        if (/REF.*MISMATCH/.test(code)) return '转换前或转换后的 REF 与对应参考序列不一致；本候选未评价，请核对输入与参考版本。';
        if (/UNMAPPED|AMBIGUOUS|MULTIPLE/.test(code)) return '该等位基因未获得唯一可靠的坐标映射；本候选未评价，不影响其他已独立核验的候选。';
        if (/ALLELE|ALT|RECORD|GENOTYPE/.test(code)) return '原始记录、ALT 或基因型与转换后候选的对应关系未通过核验；不能仅凭坐标或基因名关联。';
        if (/UNSUPPORTED|VERSION/.test(code)) return '当前转换证明的类型或版本尚未受支持；需要兼容实现，不能删除来源门禁。';
        return '转换来源缺失、变化或校验失败；需核对原始上传、转换产物及执行证明后重新分析，不补造历史证明。';
    }
    if (code === 'BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED')
        return '本次只确认 BA1 疾病背景，PM2、BS1 及其他规则仍未获得所需确认。';
    if (/^BA1_/.test(code)) {
        if (/CATALOG|SPECIFICATION|POLICY/.test(code))
            return 'BA1 规范目录或条款尚未通过本次核验，保留未评价；旧 BA1 结果不会补位。';
        if (/CONTEXT|TRANSCRIPT|DISEASE|MOI/.test(code))
            return '补充或核对 BA1 所需的疾病、遗传方式或转录本背景；按实际规范列出的缺项处理。';
        if (/DISABLED/.test(code)) return '当前 BA1 执行配置已关闭，保留未评价。';
        if (/RUNTIME|SOURCE|UNAVAILABLE|MISMATCH/.test(code))
            return '本次 BA1 来源或运行身份未通过核验，保留未评价；不能借用其他版本的结果。';
    }
    if (code === 'POPULATION_INPUT_NOT_READY')
        return '人群记录未满足输入门禁，请展开查看 readiness 子原因；与疾病选择无关。';
    if (code === 'RESOURCE_APPLICABILITY_UNKNOWN')
        return '尚不能确认该证据适用；请查看适用性原因及转录本 / 蛋白匹配结果。';
    if (/CSPEC|REVIEW|SELECTION_REF/.test(code))
        return '需要 CSpec / 人工复核处理，当前不回退通用规则。';
    if (/TRANSCRIPT|ANNOTATION|PROTEIN|AMINO/.test(code))
        return '核查所选带版本转录本、注释来源及蛋白改变；不会自动切换转录本。';
    if (/ASSEMBLY|CONVERSION|NATIVE_GRCH38|SNV|ALLELE_UNSUPPORTED/.test(code))
        return '当前仅支持未转换的原生 GRCh38 错义 SNV；保留原分析结果。';
    if (/NO_RECORD|NOT_FOUND|NO_MATCH|MISMATCH|EXACT_MATCH/.test(code))
        return '未获得精确匹配证据；核查身份或等待适用资源，不能按低风险解释。';
    if (/PATIENT|HYPOTHESIS|DECLARATION|MONDO|MOI|DISEASE/.test(code))
        return '补充或核对该候选的显式病例、疾病及遗传模式声明。';
    if (/DISABLED/.test(code)) return '当前运行配置未启用，请由管理员核查 PP3/BP4 及资源配置。';
    if (/RESOURCE|TIMEOUT|FAILED|UNAVAILABLE|INDEX/.test(code))
        return '资源未就绪或执行失败；核查服务配置后显式重试，当前没有可用评价。';
    if (/SOURCE|SNAPSHOT|RECEIPT|EXECUTION|REFERENCE|REF_/.test(code))
        return '任务缺少或未通过可信来源 / REF 核验；旧任务需在正确配置下重新分析，不会静默补造证明。';
    return unexplainedEvidenceReason;
}

// Explanatory grouping only; never changes saved status, strength or evidence adoption.
export function evidenceOutcome(evaluation) {
    const status = evaluation?.evaluationResult?.status;
    const reason = evaluation?.evaluationResult?.reasonCode || '';
    const applicability = evaluation?.criterionApplicability?.status;
    const result = (kind, label, next) => ({ kind, label, next, reasonCode: reason });
    const contribution = pp1ContributionProjection(evaluation);
    if (contribution) return result('CONFIGURATION', '可信采用清单未就绪', contribution.nextStep);
    if (evaluation?.criterionId === 'ACMG_AMP_2015:BP7' && evaluation?.reviewRequired === true)
        return result('REVIEW', '需复核 · 未采用', '先核查本项要求的复核资料；暂不采用该证据，不以内部计算结果代替采用结论。');
    if (status === 'MET') return result('MET', '已评价 · 满足', '查看采用强度、适用背景和来源；不跨背景合并得分。');
    if (status === 'NOT_MET') return result('NOT_MET', '已评价 · 未满足', '资料已进入规则评价但未满足条件，不等于变异良性。');
    if (status === 'NOT_EVALUATED' && /^DENOVO_SHARED_EVENT_ASSIGNED_TO_(PS2|PM6)$/.test(reason))
        return result('NOT_COUNTED', '共同事件不重复计分', '同一新生事件已计入另一条证据；保留资料与出处，不再重复采用，不属于资料缺失。');
    const descriptor = reasonExplanation(evaluation, evaluation?.explanation);
    if (descriptor) {
        const labels = { MISSING: '判定资料不足', UNAVAILABLE: '所需资料暂不可用', UNSUPPORTED: '软件暂未支持',
            QUALITY_REJECTED: '资料质量未通过', POLICY_NOT_READY: '适用规范尚未接通', NOT_APPLICABLE: '不适用当前分支' };
        return result(descriptor.category, labels[descriptor.category], `${descriptor.missing} ${descriptor.impact}`);
    }
    if (reason === 'BA1_EXCEPTION_HIT' || applicability === 'NOT_APPLICABLE' || reason.endsWith('_NOT_APPLICABLE'))
        return result('NOT_APPLICABLE', '不适用', '当前变异或背景不属于此规则分支，无需为了这条规则反复补资料。');
    if (applicability === 'SOFTWARE_UNSUPPORTED' || /(?:^|_)UNSUPPORTED(?:_|$)/.test(reason) || reason === 'ROUTING_PM1_IMPLEMENTED_MISSENSE_SCOPE')
        return result('UNSUPPORTED', '软件暂未支持', '保留当前支持范围；这不是没有相关科学依据，也不是已判为不满足。');
    if (/(?:^|_)(DISABLED|NOT_ENABLED)(?:_|$)/.test(reason))
        return result('DISABLED', '所需能力未启用', '按现有开关停止新增取证；可用缓存仍须通过身份和版本核验。');
    if (/(?:^|_)(TIMEOUT|FAILED|ERROR|INTERRUPTED|CANCELLED|INVALID|MISMATCH|CORRUPT)(?:_|$)/.test(reason))
        return result('EXECUTION_FAILED', '执行或核查失败', '修复具体工具、连接或资料身份问题后再显式重试；失败不代表数据库无记录。');
    if (/(?:^|_)(CONFIGURATION|NOT_CONFIGURED|NOT_REGISTERED)(?:_|$)/.test(reason))
        return result('CONFIGURATION', '资源配置未就绪', '检查对应配置与资源绑定；不能把尚未接通解释为客观资料不存在。');
    if (/(?:^|_)(CONFLICT|DISCORDANT|INCOMPATIBLE)(?:_|$)/.test(reason))
        return result('CONFLICT', '资料冲突或不相容', '核对当前背景、版本及各来源差异；不能自动选有利信息或跨背景混用。');
    if (/(?:^|_)(INCOMPLETE|PENDING|LIMIT|EXHAUSTED)(?:_|$)/.test(reason))
        return result('INCOMPLETE', '取证或核查尚未完成', '查看停止原因和未完成范围；尚未取得不等于原文没有，恢复条件满足后再继续原资料链。');
    if (/(?:^|_)(NO_RECORD|NO_MATCH)(?:_|$)/.test(reason))
        return result('NO_RECORD', '未取得匹配记录', '查看查询来源、版本与范围；无匹配记录不等于不存在，也不能直接判为良性。');
    if (/(?:^|_)(MISSING|REQUIRED|INSUFFICIENT|UNRESOLVED)(?:_|$)/.test(reason))
        return result('MISSING', '判定资料不足', '展开查看已获得什么、缺少什么以及缺项影响；可补资料与确实无依据的情况分别保留。');
    if (/(?:^|_)(UNAVAILABLE|NOT_READY|NOT_FOUND)(?:_|$)/.test(reason))
        return result('UNAVAILABLE', '所需资料暂不可用', '尚不能仅凭此状态认定没有资料；查看来源记录，区分缺失、访问受阻与尚未准备。');
    return result('UNRESOLVED', '原因待核实', '保存结果尚不足以确定原因类别；保留原始原因和出处，不自动解释为信息不足。');
}

export function criterionLabel(evaluation) {
    if (evaluation?.evaluationResult?.reasonCode === 'BA1_EXCEPTION_HIT')
        return '不适用：命中 BA1 例外';
    if (evaluation?.criterionApplicability?.status === 'NOT_APPLICABLE') return '不适用';
    if (evaluation?.criterionApplicability?.status === 'SOFTWARE_UNSUPPORTED') return '软件暂未支持';
    if (evaluation?.criterionId === 'ACMG_AMP_2015:BP7' && evaluation?.reviewRequired === true) return '需复核 · 未采用';
    return evidenceOutcome(evaluation).label;
}

export const hypothesisIdOf = (attempt) =>
    attempt?.hypothesisId || attempt?.preflight?.original?.hypothesisId;

/** Formal page capabilities come only from the current server snapshot, never historical inference. */
// Synchronous SHA-256 for small server identity documents only (no sequence/resource data).
// Match the server's small identity projection: sort raw keys, then NFC keys/strings.
// Arrays retain order; only this projection is normalized, never the source objects/raw pins.
function bp3IdentityHash(value) {
    const canonical = (v) => typeof v === 'string' ? v.normalize('NFC')
        : Array.isArray(v) ? v.map(canonical)
        : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map((k) => [k.normalize('NFC'), canonical(v[k])])) : v;
    const bytes = new TextEncoder().encode(JSON.stringify(canonical(value)));
    const data = new Uint8Array(Math.ceil((bytes.length + 9) / 64) * 64);
    data.set(bytes); data[bytes.length] = 0x80;
    const view = new DataView(data.buffer);
    view.setUint32(data.length - 4, bytes.length * 8);
    const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    const k = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
    const rotate = (v, n) => (v >>> n) | (v << (32 - n));
    for (let offset = 0; offset < data.length; offset += 64) {
        const w = new Uint32Array(64);
        for (let i = 0; i < 16; i += 1) w[i] = view.getUint32(offset + i * 4);
        for (let i = 16; i < 64; i += 1) {
            const x = w[i - 15], y = w[i - 2];
            w[i] = w[i - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3)) + w[i - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10));
        }
        let [a,b,c,d,e,f,g,t] = h;
        for (let i = 0; i < 64; i += 1) {
            const one = (t + (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i]) | 0;
            const two = ((rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
            t = g; g = f; f = e; e = (d + one) | 0; d = c; c = b; b = a; a = (one + two) | 0;
        }
        [a,b,c,d,e,f,g,t].forEach((v, i) => { h[i] = (h[i] + v) | 0; });
    }
    return h.map((v) => (v >>> 0).toString(16).padStart(8, '0')).join('');
}

// PS2 identity documents only. Do not feed scientific results (legitimate 0.5 points)
// or reconstruct any server RAW_BYTES digest with this projection.
export function ps2IdentityHash(value) {
    const inspect = (v) => {
        if (v === null || typeof v === 'string' || typeof v === 'boolean') return;
        if (typeof v === 'number' && Number.isSafeInteger(v) && !Object.is(v, -0)) return;
        if (Array.isArray(v)) { v.forEach(inspect); return; }
        if (v && Object.getPrototypeOf(v) === Object.prototype) { Object.values(v).forEach(inspect); return; }
        throw new Error('PS2 身份包含非契约数值或结构。');
    };
    inspect(value);
    return bp3IdentityHash(value);
}
const PS2_CRITERION = 'ACMG_AMP_2015:PS2';
const PS2_DECLARATION = 'ordinary-ps2-denovo-v1';
const PS2_TOKEN = 'PS2_DENOVO_CONTEXT@1.0.0:';
const ps2Sha = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
const ps2Text = (v) => typeof v === 'string' && v.length > 0;
const ps2Without = (v, keys) => Object.fromEntries(Object.entries(v).filter(([k]) => !keys.includes(k)));
const ps2Keys = (v, keys) => v && Object.keys(v).length === keys.length && keys.every((k) => Object.hasOwn(v, k));
const ps2Same = (a, b) => {
    // Exact JSON equality, including floats and raw strings, without NFC rewriting.
    if (a === b) return true;
    if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every((k) => Object.hasOwn(b, k) && ps2Same(a[k], b[k]));
};
const PS2_SELECTION_KEYS = ['candidateId', 'contextId', 'observationId', 'occurrenceId', 'caseId', 'materialBindingSha256', 'preparationToken', 'policyVersion', 'confirmed'];
const ps2Closed = (v) => ['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => v?.[k] === false);
export function ps2ScopeClaim(value) {
    if (!value || typeof value !== 'object') return typeof value === 'string' &&
        (value.startsWith(PS2_TOKEN) || value.startsWith('ordinary-ps2-') || value.startsWith('acmg-ps2-denovo-development') || value.startsWith('ps2-denovo-business-') || value.startsWith('ps2-denovo-context-preparation') || value === 'PS2_DENOVO_DEVELOPMENT');
    return Object.entries(value).some(([k, v]) => ['ps2Preparation', 'ps2Selection', 'ps2MaterialPins', 'ps2'].includes(k) || ps2ScopeClaim(v));
}
function ps2ProfileValid(p) {
    return ps2Keys(p, ['profileId', 'profileVersion', 'requiredDeclaration', 'formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported', 'enabledCriteria', 'ps2MaterialPins', 'contextPreparation', 'manifestSha256']) &&
        p.profileId === 'acmg-ps2-denovo-development-v1' && p.profileVersion === '1.0.0' && p.requiredDeclaration === PS2_DECLARATION && ps2Closed(p) &&
        ps2Same(p.enabledCriteria, [PS2_CRITERION]) && ps2Same(p.contextPreparation, { schemaVersion: 'ps2-denovo-context-preparation-v1', version: '1.0.0' }) &&
        ps2Keys(p.ps2MaterialPins, ['bundleRawSha256', 'bundleCanonicalSha256']) && Object.values(p.ps2MaterialPins).every(ps2Sha) &&
        p.manifestSha256 === ps2IdentityHash(ps2Without(p, ['manifestSha256']));
}
function ps2PreparationValid(p, profile, analysisId, candidateId, sampleId) {
    if (!ps2Keys(p, ['schemaVersion', 'version', 'policyVersion', 'status', 'scientificApplicability', 'analysisId', 'candidateId', 'sampleId', 'materialBindingSha256',
        'criterionPolicySelected', 'predictionCompatibilityClaimed', 'publishedInputIdentity', 'materialBinding', 'clinicalFacts', 'review', 'preparationToken']) ||
        p.schemaVersion !== 'ps2-denovo-context-preparation-v1' || p.version !== '1.0.0' || p.policyVersion !== '1.0.0-ps2-denovo-development' ||
        p.status !== 'BOUND_NOT_EVALUATED' || p.scientificApplicability !== 'NOT_EVALUATED' || p.criterionPolicySelected !== false || p.predictionCompatibilityClaimed !== false ||
        p.analysisId !== analysisId || p.candidateId !== candidateId || p.sampleId !== sampleId || !ps2Text(sampleId) ||
        p.preparationToken !== PS2_TOKEN + ps2IdentityHash(ps2Without(p, ['preparationToken']))) return false;
    const b = p.materialBinding, c = b?.context, v = c?.variant, patient = c?.patientContext, origin = p.publishedInputIdentity;
    if (b?.schemaVersion !== 'ps2-denovo-business-binding-v1' || p.materialBindingSha256 !== ps2IdentityHash(b) ||
        !ps2Same(b.executionProfile, profile) || !ps2Same(b.materialPins, profile.ps2MaterialPins) ||
        !['caseId', 'observationId', 'observationVersion', 'occurrenceId', 'familyId'].every((k) => ps2Text(b[k])) ||
        !['factsSha256', 'assessmentSha256', 'policySha256', 'catalogSha256'].every((k) => ps2Sha(b[k])) || !ps2Sha(b.registryBinding?.registrySha256) ||
        c?.schemaVersion !== 'evidence-context-v3' || c.canonicalizationProfile !== 'varvis-json-c14n-v1' || !ps2Text(c.dataClass) ||
        !patient || !['patientContextId', 'role', 'sex', 'affectedStatus', 'familyContextId'].every((k) => ps2Text(patient[k])) || b.familyId !== patient.familyContextId ||
        origin?.analysisId !== analysisId || origin.sampleId !== sampleId || origin.inputAssembly !== v?.inputAllele?.assembly ||
        !['uploadSha256', 'candidatesRawSha256', 'executionSourceSha256'].every((k) => ps2Sha(origin[k]))) return false;
    const normalizer = Object.fromEntries(['method', 'methodVersion', 'referenceSequenceSha256', 'refValidation'].map((k) => [k, v.normalization?.[k]]));
    if (v.alleleId !== 'allele-sha256-' + ps2IdentityHash({ normalizedAllele: v.normalizedAllele, normalizer })) return false;
    const contextProjection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass,
        scope: c.scope, alleleId: v.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: patient };
    if (Object.hasOwn(c, 'transcript')) contextProjection.transcript = { accession: c.transcript.accession, version: c.transcript.version };
    if (c.contextId !== 'context-sha256-' + ps2IdentityHash(contextProjection) || ps2IdentityHash(p.review) !== b.assessmentSha256) return false;
    const clinical = p.clinicalFacts?.binding;
    return clinical && ps2Same(clinical, { observationId: b.observationId, contextId: c.contextId, alleleId: v.alleleId,
        allele: v.normalizedAllele, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi,
        patientContextId: patient.patientContextId, familyContextId: patient.familyContextId });
}
export function ps2ScopeIssue(o, analysisId = o?.analysisId) {
    if (!ps2ScopeClaim(o)) return null;
    if (pp4ScopeClaim(o)) return '混入 PP4 身份，不授予提交许可。';
    if (bs4ScopeClaim(o)) return '混入 BS4 身份，不授予提交许可。';
    if (pp1ScopeClaim(o)) return '混入 PP1 身份，不授予提交许可。';
    try {
        const p = o?.ps2Preparation, b = p?.materialBinding, s = o?.ps2Selection;
        if (bp2ScopeClaim(o) || pm3ScopeClaim(o) || pm6ScopeClaim(o) || o.analysisId !== analysisId || !ps2Text(analysisId) || o.acceptanceScope !== 'PS2_DENOVO_DEVELOPMENT' || o.requiredDeclaration !== PS2_DECLARATION ||
            o.runtimeConfigured !== true || !ps2Closed(o) || o.criterionPolicySelected !== false || o.predictionCompatibilityClaimed !== false ||
            o.requestedN !== 1 || o.selectedCount !== 1 || !ps2Same(o.enabledCriteria, [PS2_CRITERION]) ||
            !Array.isArray(o.candidateIds) || o.candidateIds.length !== 1 || !ps2Text(o.candidateIds[0]) ||
            ['candidates', 'recommendations', 'association', 'calibration', 'revelApplication', 'runtimeBinding', 'hypotheses'].some((k) => Object.hasOwn(o, k)) ||
            !ps2ProfileValid(o.executionProfile) || !ps2PreparationValid(p, o.executionProfile, analysisId, o.candidateIds[0], o.sampleId) ||
            !ps2Keys(s, PS2_SELECTION_KEYS) || !ps2Same(s, { candidateId: p.candidateId, contextId: b.context.contextId, observationId: b.observationId,
                occurrenceId: b.occurrenceId, caseId: b.caseId, materialBindingSha256: p.materialBindingSha256, preparationToken: p.preparationToken, policyVersion: p.policyVersion, confirmed: false }))
            return 'PS2 当前材料或执行身份不一致，请重新读取；未授予提交许可。';
        return null;
    } catch { return 'PS2 当前材料身份缺失或摘要不一致，请重新读取；未提交评价。'; }
}
export function ps2RetryEligible(run, analysisId) {
    if (pp4ScopeClaim(run)) return false;
    if (bs4ScopeClaim(run)) return false;
    if (pp1ScopeClaim(run)) return false;
    return run?.analysisId === analysisId && typeof run.runId === 'string' && /^[0-9a-f-]{36}$/.test(run.runId) && ['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status);
}
export function buildPs2Declaration(options, analysisId, confirmed, retryRun) {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(bp2ScopeClaim)) throw new Error('BP2 请使用固定双变异材料独立确认入口。');
    if ([...arguments].some(pm3ScopeClaim)) throw new Error('PM3 必须使用独立声明入口。');
    if (!ps2ScopeClaim(options) || ps2ScopeIssue(options, analysisId) || confirmed !== true) throw new Error('PS2 请读取当前材料并明确确认后提交。');
    if (retryRun !== undefined && !ps2RetryEligible(retryRun, analysisId)) throw new Error('PS2 仅可显式重试本分析已选 FAILED 或 COMPLETED_WITH_BLOCKERS 运行。');
    const context = options.ps2Preparation.materialBinding.context;
    return { topN: 1, declarationVersion: PS2_DECLARATION, sampleId: options.sampleId, dataClass: context.dataClass,
        patient: Object.fromEntries(['patientContextId', 'role', 'sex', 'familyContextId'].map((k) => [k, context.patientContext[k]])),
        hypotheses: [], ps2Selection: { ...options.ps2Selection, confirmed: true }, ...(retryRun ? { retryOf: retryRun.runId } : {}) };
}
export function ps2SavedRun(run) {
    if (pp4ScopeClaim(run)) return false;
    if (bs4ScopeClaim(run)) return false;
    if (pp1ScopeClaim(run)) return false;
    try {
        if (bp2ScopeClaim(run) || pm3ScopeClaim(run) || pm6ScopeClaim(run) || !ps2ScopeClaim(run) || !['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status) || !ps2Text(run.runId) || !ps2Text(run.analysisId) ||
            run.acceptanceScope !== 'PS2_DENOVO_DEVELOPMENT' || run.declarationVersion !== PS2_DECLARATION || !ps2Closed(run) ||
            !ps2ProfileValid(run.executionProfile) || !ps2Same(run.enabledCriteria, [PS2_CRITERION]) || run.requestedN !== 1 || run.selectedCount !== 1 ||
            run.candidates?.length !== 1 || run.candidateIds?.length !== 1 || run.bundles?.length !== 1) return false;
        const candidate = run.candidates[0], attempt = candidate.attempts?.[0], artifact = run.bundles[0], core = attempt?.ps2, source = core?.sourceBundle;
        const p = artifact.preparation, b = p.materialBinding, facts = source.observations?.[0]?.facts;
        if (candidate.candidateId !== run.candidateIds[0] || candidate.attempts?.length !== 1 || !ps2Keys(attempt, ['ps2', 'criterionEvaluations', 'blockers']) ||
            artifact.schemaVersion !== 'ps2-denovo-business-artifact-v1' || artifact.runId !== run.runId || artifact.analysisId !== run.analysisId || artifact.candidateId !== candidate.candidateId ||
            artifact.classificationEnabled !== false || artifact.adoptionSupported !== false || !ps2Sha(artifact.coreRawSha256) ||
            artifact.bundleId !== 'ps2-business-artifact-sha256-' + ps2IdentityHash(ps2Without(artifact, ['core', 'bundleId'])) ||
            !ps2Same(artifact.core, core) || core.criterionId !== PS2_CRITERION || core.resultMode !== 'CORE_RESULT_ONLY' ||
            core.classificationEnabled !== false || core.adoptionSupported !== false ||
            !ps2Same(attempt.criterionEvaluations, core.criterionEvaluations) || core.criterionEvaluations?.length !== 1 || core.criterionEvaluations[0].criterionId !== PS2_CRITERION ||
            !ps2PreparationValid(p, run.executionProfile, run.analysisId, candidate.candidateId, p.sampleId) || run.dataClass !== b.context.dataClass ||
            source.observations?.length !== 1 || !ps2Same(source.context, b.context) || !ps2Same(source.registryBinding, b.registryBinding) ||
            !['caseId', 'observationId', 'observationVersion', 'occurrenceId', 'familyId'].every((k) => facts[k] === b[k]) ||
            !ps2Same(facts.clinical, p.clinicalFacts) || !ps2Same(source.observations[0].assessment, p.review) ||
            ps2IdentityHash(facts) !== b.factsSha256 || ps2IdentityHash(source.policy) !== b.policySha256 || source.catalogSha256 !== b.catalogSha256 ||
            ps2IdentityHash(source.catalog) !== b.catalogSha256 || ps2IdentityHash(source) !== b.materialPins.bundleCanonicalSha256 ||
            (core.evaluation ? !ps2Same(core.evaluation, core.criterionEvaluations[0]) :
                !ps2Same(core.evaluationResult, core.criterionEvaluations[0].evaluationResult) || !ps2Same(core.criterionApplicability, core.criterionEvaluations[0].criterionApplicability))) return false;
        return true;
    } catch { return false; }
}
export function ps2EvidenceEntry(core) {
    const source = core?.sourceBundle, facts = source?.observations?.[0]?.facts, context = source?.context;
    const evaluation = core?.evaluation || core?.criterionEvaluations?.[0];
    return { evaluation, scopeLabel: 'PS2 单家系支持证据', contextLabel: [context?.gene?.symbol, context?.disease?.label, context?.moi].filter(Boolean).join(' · '),
        reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [['本次积分', core?.deNovoPoints ?? '未给出'], ['来源标签', core?.resultMode ?? '未取得'],
            ['结论边界', '不是最终致病分类；NOT_MET不等于良性，不自动PM6。UNKNOWN保持未知，背景确认不是科学审核。'],
            ['病例 / 家系 / 观察', [facts?.caseId, facts?.familyId, facts?.observationId, facts?.occurrenceId].filter(Boolean).join(' / ')],
            ['三人及检测边界原事实', facts?.members], ['原始 GT / 质量 / FT', facts?.records], ['亲缘确认原事实', facts?.parentage],
            ['临床、家族史与范围结论', facts?.clinical], ['受审记录（非本次科学批准）', source?.observations?.[0]?.assessment],
            ['出处 / 日期 / 版本及原文', facts?.sources]].map(([label, value]) => [label,
                value && typeof value === 'object' ? JSON.stringify(value) : value]), raw: core };
}

// PM6 transport identities use the same bounded canonical profile as PS2.
export const pm6IdentityHash = ps2IdentityHash;
const PM6_CRITERION = 'ACMG_AMP_2015:PM6';
const PM6_DECLARATION = 'ordinary-pm6-denovo-v1';
const PM6_TOKEN = 'PM6_DENOVO_CONTEXT@1.0.0:';
const pm6Sha = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
const pm6Text = (v) => typeof v === 'string' && v.length > 0;
const pm6Without = (v, keys) => Object.fromEntries(Object.entries(v).filter(([k]) => !keys.includes(k)));
const pm6Keys = (v, keys) => v && Object.keys(v).length === keys.length && keys.every((k) => Object.hasOwn(v, k));
const pm6Same = (a, b) => {
    // Exact JSON equality, including floats and raw strings, without NFC rewriting.
    if (a === b) return true;
    if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every((k) => Object.hasOwn(b, k) && pm6Same(a[k], b[k]));
};
const PM6_SELECTION_KEYS = ['candidateId', 'contextId', 'observationId', 'occurrenceId', 'caseId', 'materialBindingSha256', 'preparationToken', 'policyVersion', 'confirmed'];
const pm6Closed = (v) => ['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => v?.[k] === false);
export function pm6ScopeClaim(value) {
    if (!value || typeof value !== 'object') return typeof value === 'string' &&
        (value.startsWith(PM6_TOKEN) || value.startsWith('ordinary-pm6-') || value.startsWith('acmg-pm6-denovo-development') || value.startsWith('pm6-denovo-business-') || value.startsWith('pm6-denovo-context-preparation') || value === 'PM6_DENOVO_DEVELOPMENT');
    return Object.entries(value).some(([k, v]) => ['pm6Preparation', 'pm6Selection', 'pm6MaterialPins', 'pm6'].includes(k) || pm6ScopeClaim(v));
}
function pm6ProfileValid(p) {
    return pm6Keys(p, ['profileId', 'profileVersion', 'requiredDeclaration', 'formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported', 'enabledCriteria', 'pm6MaterialPins', 'contextPreparation', 'manifestSha256']) &&
        p.profileId === 'acmg-pm6-denovo-development-v1' && p.profileVersion === '1.0.0' && p.requiredDeclaration === PM6_DECLARATION && pm6Closed(p) &&
        pm6Same(p.enabledCriteria, [PM6_CRITERION]) && pm6Same(p.contextPreparation, { schemaVersion: 'pm6-denovo-context-preparation-v1', version: '1.0.0' }) &&
        pm6Keys(p.pm6MaterialPins, ['bundleRawSha256', 'bundleCanonicalSha256']) && Object.values(p.pm6MaterialPins).every(pm6Sha) &&
        p.manifestSha256 === pm6IdentityHash(pm6Without(p, ['manifestSha256']));
}
function pm6PreparationValid(p, profile, analysisId, candidateId, sampleId) {
    if (!pm6Keys(p, ['schemaVersion', 'version', 'policyVersion', 'status', 'scientificApplicability', 'analysisId', 'candidateId', 'sampleId', 'materialBindingSha256',
        'criterionPolicySelected', 'predictionCompatibilityClaimed', 'publishedInputIdentity', 'materialBinding', 'clinicalFacts', 'review', 'patientContext', 'parentageBasis', 'preparationToken']) ||
        p.schemaVersion !== 'pm6-denovo-context-preparation-v1' || p.version !== '1.0.0' || p.policyVersion !== '1.0.0-pm6-denovo-development' ||
        p.status !== 'BOUND_NOT_EVALUATED' || p.scientificApplicability !== 'NOT_EVALUATED' || p.criterionPolicySelected !== false || p.predictionCompatibilityClaimed !== false ||
        p.analysisId !== analysisId || p.candidateId !== candidateId || p.sampleId !== sampleId || !pm6Text(sampleId) ||
        p.preparationToken !== PM6_TOKEN + pm6IdentityHash(pm6Without(p, ['preparationToken']))) return false;
    const b = p.materialBinding, c = b?.context, v = c?.variant, patient = c?.patientContext, origin = p.publishedInputIdentity;
    if (!pm6Same(p.patientContext, patient) || !pm6Same(p.parentageBasis, b?.parentageBasis) ||
        !pm6Sha(b?.parentageBasisSha256) || pm6IdentityHash(p.parentageBasis) !== b.parentageBasisSha256 || b?.schemaVersion !== 'pm6-denovo-business-binding-v1' || p.materialBindingSha256 !== pm6IdentityHash(b) ||
        p.parentageBasis.factsSha256 !== b.factsSha256 || p.review?.factsSha256 !== b.factsSha256 || p.review?.parentageBasisSha256 !== b.parentageBasisSha256 ||
        p.review.contentSha256 !== pm6IdentityHash(pm6Without(p.review, ['contentSha256'])) ||
        !pm6Same(b.executionProfile, profile) || !pm6Same(b.materialPins, profile.pm6MaterialPins) ||
        !['caseId', 'observationId', 'observationVersion', 'occurrenceId', 'familyId'].every((k) => pm6Text(b[k])) ||
        !['factsSha256', 'assessmentSha256', 'policySha256', 'catalogSha256'].every((k) => pm6Sha(b[k])) || !pm6Sha(b.registryBinding?.registrySha256) ||
        c?.schemaVersion !== 'evidence-context-v3' || c.canonicalizationProfile !== 'varvis-json-c14n-v1' || !pm6Text(c.dataClass) ||
        !patient || !['patientContextId', 'role', 'sex', 'affectedStatus', 'familyContextId'].every((k) => pm6Text(patient[k])) || b.familyId !== patient.familyContextId ||
        origin?.analysisId !== analysisId || origin.sampleId !== sampleId || origin.inputAssembly !== v?.inputAllele?.assembly ||
        !['uploadSha256', 'candidatesRawSha256', 'executionSourceSha256'].every((k) => pm6Sha(origin[k]))) return false;
    const normalizer = Object.fromEntries(['method', 'methodVersion', 'referenceSequenceSha256', 'refValidation'].map((k) => [k, v.normalization?.[k]]));
    if (v.alleleId !== 'allele-sha256-' + pm6IdentityHash({ normalizedAllele: v.normalizedAllele, normalizer })) return false;
    const contextProjection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass,
        scope: c.scope, alleleId: v.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: patient };
    if (Object.hasOwn(c, 'transcript')) contextProjection.transcript = { accession: c.transcript.accession, version: c.transcript.version };
    if (c.contextId !== 'context-sha256-' + pm6IdentityHash(contextProjection) || pm6IdentityHash(p.review) !== b.assessmentSha256) return false;
    const clinical = p.clinicalFacts?.binding;
    return clinical && pm6Same(p.parentageBasis.binding, clinical) && Array.isArray(p.parentageBasis.relations) &&
        p.parentageBasis.relations.every((relation) => pm6Same(relation.binding, clinical) && relation.probandMemberId === patient.patientContextId && relation.probandSampleId === sampleId) && pm6Same(clinical, { observationId: b.observationId, contextId: c.contextId, alleleId: v.alleleId,
        allele: v.normalizedAllele, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi,
        patientContextId: patient.patientContextId, familyContextId: patient.familyContextId });
}
export function pm6ScopeIssue(o, analysisId = o?.analysisId) {
    if (!pm6ScopeClaim(o)) return null;
    if (pp4ScopeClaim(o)) return '混入 PP4 身份，不授予提交许可。';
    if (bs4ScopeClaim(o)) return '混入 BS4 身份，不授予提交许可。';
    if (pp1ScopeClaim(o)) return '混入 PP1 身份，不授予提交许可。';
    try {
        const p = o?.pm6Preparation, b = p?.materialBinding, s = o?.pm6Selection;
        if (bp2ScopeClaim(o) || pm3ScopeClaim(o) || ps2ScopeClaim(o) || o.analysisId !== analysisId || !pm6Text(analysisId) || o.acceptanceScope !== 'PM6_DENOVO_DEVELOPMENT' || o.requiredDeclaration !== PM6_DECLARATION ||
            o.runtimeConfigured !== true || !pm6Closed(o) || o.criterionPolicySelected !== false || o.predictionCompatibilityClaimed !== false ||
            o.requestedN !== 1 || o.selectedCount !== 1 || !pm6Same(o.enabledCriteria, [PM6_CRITERION]) ||
            !Array.isArray(o.candidateIds) || o.candidateIds.length !== 1 || !pm6Text(o.candidateIds[0]) ||
            ['candidates', 'recommendations', 'association', 'calibration', 'revelApplication', 'runtimeBinding', 'hypotheses'].some((k) => Object.hasOwn(o, k)) ||
            !pm6ProfileValid(o.executionProfile) || !pm6PreparationValid(p, o.executionProfile, analysisId, o.candidateIds[0], o.sampleId) ||
            !pm6Keys(s, PM6_SELECTION_KEYS) || !pm6Same(s, { candidateId: p.candidateId, contextId: b.context.contextId, observationId: b.observationId,
                occurrenceId: b.occurrenceId, caseId: b.caseId, materialBindingSha256: p.materialBindingSha256, preparationToken: p.preparationToken, policyVersion: p.policyVersion, confirmed: false }))
            return 'PM6 当前材料或执行身份不一致，请重新读取；未授予提交许可。';
        return null;
    } catch { return 'PM6 当前材料身份缺失或摘要不一致，请重新读取；未提交评价。'; }
}
export function pm6RetryEligible(run, analysisId) {
    if (pp4ScopeClaim(run)) return false;
    if (bs4ScopeClaim(run)) return false;
    if (pp1ScopeClaim(run)) return false;
    try { return !ps2ScopeClaim(run) && (!run?.acceptanceScope || run.acceptanceScope === 'PM6_DENOVO_DEVELOPMENT') &&
        (!run?.enabledCriteria || pm6Same(run.enabledCriteria, [PM6_CRITERION])) && (!run?.executionProfile || pm6ProfileValid(run.executionProfile)) && (!run?.declarationVersion || run.declarationVersion === PM6_DECLARATION) && run?.analysisId === analysisId && typeof run.runId === 'string' && /^[0-9a-f-]{36}$/.test(run.runId) && ['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status); } catch { return false; }
}
export function buildPm6Declaration(options, analysisId, confirmed, retryRun) {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(bp2ScopeClaim)) throw new Error('BP2 请使用固定双变异材料独立确认入口。');
    if ([...arguments].some(pm3ScopeClaim)) throw new Error('PM3 必须使用独立声明入口。');
    if (!pm6ScopeClaim(options) || pm6ScopeIssue(options, analysisId) || confirmed !== true) throw new Error('PM6 请读取当前材料并明确确认后提交。');
    if (retryRun !== undefined && !pm6RetryEligible(retryRun, analysisId)) throw new Error('PM6 仅可显式重试本分析已选 FAILED 或 COMPLETED_WITH_BLOCKERS 运行。');
    const context = options.pm6Preparation.materialBinding.context;
    return { topN: 1, declarationVersion: PM6_DECLARATION, sampleId: options.sampleId, dataClass: context.dataClass,
        patient: Object.fromEntries(['patientContextId', 'role', 'sex', 'familyContextId'].map((k) => [k, context.patientContext[k]])),
        hypotheses: [], pm6Selection: { ...options.pm6Selection, confirmed: true }, ...(retryRun ? { retryOf: retryRun.runId } : {}) };
}
export function pm6SavedRun(run) {
    if (pp4ScopeClaim(run)) return false;
    if (bs4ScopeClaim(run)) return false;
    if (pp1ScopeClaim(run)) return false;
    try {
        if (bp2ScopeClaim(run) || pm3ScopeClaim(run) || ps2ScopeClaim(run) || !pm6ScopeClaim(run) || !['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status) || !pm6Text(run.runId) || !pm6Text(run.analysisId) ||
            run.acceptanceScope !== 'PM6_DENOVO_DEVELOPMENT' || run.declarationVersion !== PM6_DECLARATION || !pm6Closed(run) ||
            !pm6ProfileValid(run.executionProfile) || !pm6Same(run.enabledCriteria, [PM6_CRITERION]) || run.requestedN !== 1 || run.selectedCount !== 1 ||
            run.candidates?.length !== 1 || run.candidateIds?.length !== 1 || run.bundles?.length !== 1) return false;
        const candidate = run.candidates[0], attempt = candidate.attempts?.[0], artifact = run.bundles[0], core = attempt?.pm6, source = core?.sourceBundle;
        const p = artifact.preparation, b = p.materialBinding, facts = source.observations?.[0]?.facts;
        if (candidate.candidateId !== run.candidateIds[0] || candidate.attempts?.length !== 1 || !pm6Keys(attempt, ['pm6', 'criterionEvaluations', 'blockers']) ||
            artifact.schemaVersion !== 'pm6-denovo-business-artifact-v1' || artifact.runId !== run.runId || artifact.analysisId !== run.analysisId || artifact.candidateId !== candidate.candidateId ||
            artifact.coreDigestKind !== 'RAW_BYTES_SHA256' || artifact.artifactIdentityKind !== 'CANONICAL_WITH_CORE_RAW_DIGEST' ||
            artifact.classificationEnabled !== false || artifact.adoptionSupported !== false || !pm6Sha(artifact.coreRawSha256) ||
            artifact.bundleId !== 'pm6-business-artifact-sha256-' + pm6IdentityHash(pm6Without(artifact, ['core', 'bundleId'])) ||
            !pm6Same(artifact.core, core) || core.criterionId !== PM6_CRITERION || core.resultMode !== 'CORE_RESULT_ONLY' ||
            core.classificationEnabled !== false || core.adoptionSupported !== false ||
            !pm6Same(attempt.criterionEvaluations, core.criterionEvaluations) || core.criterionEvaluations?.length !== 1 || core.criterionEvaluations[0].criterionId !== PM6_CRITERION ||
            !pm6PreparationValid(p, run.executionProfile, run.analysisId, candidate.candidateId, p.sampleId) || run.dataClass !== b.context.dataClass ||
            source.observations?.length !== 1 || !pm6Same(source.context, b.context) || !pm6Same(source.registryBinding, b.registryBinding) ||
            !['caseId', 'observationId', 'observationVersion', 'occurrenceId', 'familyId'].every((k) => facts[k] === b[k]) ||
            !pm6Same(source.observations[0].parentageBasis, p.parentageBasis) ||
            !pm6Same(facts.binding, p.clinicalFacts.binding) ||
            facts.members?.filter((m) => m.role === 'PROBAND' && m.memberId === b.context.patientContext.patientContextId && m.sampleId === p.sampleId).length !== 1 ||
            !['factsSha256', 'assessmentSha256', 'parentageBasisSha256'].every((k) => source.policy[k] === b[k]) ||
            !pm6Same(facts.clinical, p.clinicalFacts) || !pm6Same(source.observations[0].assessment, p.review) ||
            pm6IdentityHash(facts) !== b.factsSha256 || pm6IdentityHash(source.policy) !== b.policySha256 || source.catalogSha256 !== b.catalogSha256 ||
            pm6IdentityHash(source.catalog) !== b.catalogSha256 || pm6IdentityHash(source) !== b.materialPins.bundleCanonicalSha256 ||
            (core.evaluation ? !pm6Same(core.evaluation, core.criterionEvaluations[0]) :
                !pm6Same(core.evaluationResult, core.criterionEvaluations[0].evaluationResult) || !pm6Same(core.criterionApplicability, core.criterionEvaluations[0].criterionApplicability))) return false;
        return true;
    } catch { return false; }
}
export function pm6EvidenceEntry(core) {
    const source = core?.sourceBundle, facts = source?.observations?.[0]?.facts, context = source?.context;
    const evaluation = core?.evaluation || core?.criterionEvaluations?.[0];
    return { evaluation, scopeLabel: 'PM6 单家系支持证据', contextLabel: [context?.gene?.symbol, context?.disease?.label, context?.moi].filter(Boolean).join(' · '),
        reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [['本次积分', core?.deNovoPoints ?? '未给出'], ['来源标签', core?.resultMode ?? '未取得'],
            ['结论边界', '不是最终致病分类；NOT_MET不等于良性，不自动PS2。UNKNOWN保持未知，背景确认不是科学审核。'],
            ['病例 / 家系 / 观察', [facts?.caseId, facts?.familyId, facts?.observationId, facts?.occurrenceId].filter(Boolean).join(' / ')],
            ['三人及检测边界原事实', facts?.members], ['原始 GT / 质量 / FT', facts?.records], ['完整亲缘依据（检测状态与亲缘结论分别记录）', source?.observations?.[0]?.parentageBasis],
            ['临床、家族史与范围结论', facts?.clinical], ['受审记录（非本次科学批准）', source?.observations?.[0]?.assessment],
            ['出处 / 日期 / 版本及原文', facts?.sources]].map(([label, value]) => [label,
                value && typeof value === 'object' ? JSON.stringify(value) : value]), raw: core };
}

const BP1_CONTEXT_POLICY = /^REVIEWED_RULE_CONTEXT@9\.0\.0:[0-9a-f]{64}$/;
// BP1 alone is not a new-scope claim: historical HNF1A/joint runs retain their identity.
export function bp1ScopeClaim(options) {
    const p = options?.executionProfile;
    const bindings = [p?.reviewedRuleBinding, p?.ruleContextPreparationBinding,
        ...(options?.candidates || []).map((c) => c.recommendation?.ruleContextPreparationBinding),
        ...(options?.candidates || []).flatMap((c) => (c.attempts || []).map((a) => a.bp1?.bundleIdentity))];
    return Boolean((options?.candidates || []).some((candidate) =>
        candidate.recommendation?.requiredDeclaration?.startsWith?.('ordinary-bp1-') ||
        candidate.recommendation?.annotations?.some((a) => a.ruleContextSelectionPolicy?.startsWith?.('REVIEWED_RULE_CONTEXT@9.'))) || p?.profileId?.startsWith?.('acmg-bp1-') ||
        [p?.requiredDeclaration, options?.requiredDeclaration, options?.declarationVersion]
            .some((v) => v?.startsWith?.('ordinary-bp1-')) ||
        bindings.some((b) => b?.schemaVersion?.includes?.('-bp1-') || b?.reviewedRuleBinding?.schemaVersion?.includes?.('-bp1-')));
}

export function bp1ScopeIssue(options) {
    if (!bp1ScopeClaim(options)) return null;
    const p = options?.executionProfile, b = p?.reviewedRuleBinding, c = p?.ruleContextPreparationBinding;
    const onlyBp1 = (ids) => JSON.stringify(ids) === '["ACMG_AMP_2015:BP1"]';
    const sha = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
    if (p?.profileId !== 'acmg-bp1-missense-development-v1' || p.profileVersion !== '1.0.0' ||
        p.requiredDeclaration !== 'ordinary-bp1-missense-v1' || options.requiredDeclaration !== p.requiredDeclaration ||
        !onlyBp1(p.enabledCriteria) || !onlyBp1(options.enabledCriteria) || !sha(p.manifestSha256) || p.manifestSha256 !== bp3IdentityHash(Object.fromEntries(Object.entries(p).filter(([k]) => k !== 'manifestSha256'))) ||
        !['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => p[k] === false) ||
        b?.schemaVersion !== 'reviewed-rule-bundle-binding-bp1-v9' || b.status !== 'CONFIGURED' ||
        !onlyBp1(b.criterionIds) || !b.bundleId?.trim?.() || !b.bundleVersion?.trim?.() || !sha(b.bundleSha256) || !sha(b.catalogSha256) || !sha(b.bundleRawSha256) ||
        JSON.stringify(b.algorithms) !== '{"reviewed-truncating-mechanism-bp1":"1.0.0"}' ||
        !['automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => b[k] === false) ||
        c?.schemaVersion !== 'reviewed-rule-context-preparation-bp1-v9' || c.version !== '9.0.0' ||
        c.mode !== 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION' ||
        !['automaticTrigger', 'classificationEnabled', 'criterionPolicySelected', 'predictionCompatibilityClaimed'].every((k) => c[k] === false) ||
        JSON.stringify(c.reviewedRuleBinding) !== JSON.stringify(b))
        return 'BP1 执行身份冲突、材料绑定未就绪或版本不支持；请重新读取，当前不能提交。';
    if ((options.candidates || []).some((candidate) => {
        const r = candidate.recommendation;
        const policyKeys = ['policyId', 'policyVersion', 'patientSpecific', 'scoreIndependent', 'compatibility', 'requiredDeclaration', 'ruleContextPreparationBinding'];
        const relationships = [undefined, ...relationshipOptions(candidate)];
        return !r || r.policyVersion !== '1.0.0-bp1-missense-development' || r.policyHash !== bp3IdentityHash(Object.fromEntries(policyKeys.map((k) => [k, r[k]]))) || r.candidateId !== candidate.candidateId || r.analysisId !== options.analysisId ||
            r.requiredDeclaration !== p.requiredDeclaration || !r.hypothesisId || r.allele?.genomeAssembly !== candidate.allele?.genomeAssembly || JSON.stringify(r.allele) !== JSON.stringify(candidate.allele) || !sha(r.policyHash) || !sha(r.executionSourceSha256) || !sha(r.publishedCandidateRawSha256) ||
            r.recommendationId !== `recommendation-${bp3IdentityHash(Object.fromEntries(Object.entries(r).filter(([k]) => !['recommendationId', 'generatedAt'].includes(k))))}` ||
            JSON.stringify(r.ruleContextPreparationBinding) !== JSON.stringify(c) ||
            (r.resourceCompatibleTranscript?.ruleContextReady &&
                !r.annotations?.some((a) => JSON.stringify(a) === JSON.stringify(r.resourceCompatibleTranscript))) ||
            (r.annotations || []).some((a) => {
                const selectable = a.ruleContextReady || a.contextSelectable || relationships.some((rel) => annotationSelectable(a, rel));
                if (!selectable) return false;
                const transcript = transcriptOptions(candidate).find((t) => Number(t.index) === a.annotationIndex);
                return !ruleContextAnnotation(a) || !BP1_CONTEXT_POLICY.test(a.ruleContextSelectionPolicy || '') ||
                    a.ruleContextSelectionPolicy !== `REVIEWED_RULE_CONTEXT@9.0.0:${bp3IdentityHash(c)}` ||
                    !sha(a.annotationSourceSha256) || !/^annotation-[0-9a-f]{64}$/.test(a.annotationId || '') ||
                    !transcript?.eligible || transcript.versionedId !== a.versionedTranscriptId ||
                    transcript.annotation.geneSymbol !== a.geneSymbol || transcript.annotation.source !== a.annotationSource ||
                    transcript.annotation.version !== a.annotationSourceVersion ||
                    transcript.annotation.hgvsc !== a.hgvsc || transcript.annotation.hgvsp !== a.hgvsp;
            });
    })) return 'BP1 推荐与当前候选或 v9 上下文绑定不一致；请重新读取并确认。';
    return null;
}

export function bp1SavedRun(run) {
    return run?.executionProfile?.profileId === 'acmg-bp1-missense-development-v1' && bp1ScopeClaim(run) &&
        !bp1ScopeIssue({ ...run, requiredDeclaration: run.declarationVersion, candidates: [] }) &&
        (run.candidates || []).every((candidate) => (candidate.attempts || []).every((attempt) => !attempt.bp1 ||
            (attempt.bp1.criterionId === 'ACMG_AMP_2015:BP1' &&
                JSON.stringify(attempt.bp1.bundleIdentity) === JSON.stringify(run.executionProfile.reviewedRuleBinding))));
}

const PP2_CONTEXT_POLICY = /^REVIEWED_RULE_CONTEXT@8\.0\.0:[0-9a-f]{64}$/;
// PP2 alone is not a new-scope claim: historical HNF1A/joint runs retain their identity.
export function pp2ScopeClaim(options) {
    const p = options?.executionProfile;
    const bindings = [p?.reviewedRuleBinding, p?.ruleContextPreparationBinding,
        ...(options?.candidates || []).map((c) => c.recommendation?.ruleContextPreparationBinding),
        ...(options?.candidates || []).flatMap((c) => (c.attempts || []).map((a) => a.pp2?.bundleIdentity))];
    return Boolean((options?.candidates || []).some((candidate) =>
        candidate.recommendation?.requiredDeclaration?.startsWith?.('ordinary-pp2-') ||
        candidate.recommendation?.annotations?.some((a) => a.ruleContextSelectionPolicy?.startsWith?.('REVIEWED_RULE_CONTEXT@8.'))) || p?.profileId?.startsWith?.('acmg-pp2-') ||
        [p?.requiredDeclaration, options?.requiredDeclaration, options?.declarationVersion]
            .some((v) => v?.startsWith?.('ordinary-pp2-')) ||
        bindings.some((b) => b?.schemaVersion?.includes?.('-pp2-') || b?.reviewedRuleBinding?.schemaVersion?.includes?.('-pp2-')));
}

export function pp2ScopeIssue(options) {
    if (!pp2ScopeClaim(options)) return null;
    const p = options?.executionProfile, b = p?.reviewedRuleBinding, c = p?.ruleContextPreparationBinding;
    const onlyPp2 = (ids) => JSON.stringify(ids) === '["ACMG_AMP_2015:PP2"]';
    const sha = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
    if (p?.profileId !== 'acmg-pp2-missense-development-v1' || p.profileVersion !== '1.0.0' ||
        p.requiredDeclaration !== 'ordinary-pp2-missense-v1' || options.requiredDeclaration !== p.requiredDeclaration ||
        !onlyPp2(p.enabledCriteria) || !onlyPp2(options.enabledCriteria) || !sha(p.manifestSha256) || p.manifestSha256 !== bp3IdentityHash(Object.fromEntries(Object.entries(p).filter(([k]) => k !== 'manifestSha256'))) ||
        !['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => p[k] === false) ||
        b?.schemaVersion !== 'reviewed-rule-bundle-binding-pp2-v8' || b.status !== 'CONFIGURED' ||
        !onlyPp2(b.criterionIds) || !b.bundleId?.trim?.() || !b.bundleVersion?.trim?.() || !sha(b.bundleSha256) || !sha(b.catalogSha256) || !sha(b.bundleRawSha256) ||
        JSON.stringify(b.algorithms) !== '{"reviewed-missense-spectrum-pp2":"1.0.0"}' ||
        !['automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => b[k] === false) ||
        c?.schemaVersion !== 'reviewed-rule-context-preparation-pp2-v8' || c.version !== '8.0.0' ||
        c.mode !== 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION' ||
        !['automaticTrigger', 'classificationEnabled', 'criterionPolicySelected', 'predictionCompatibilityClaimed'].every((k) => c[k] === false) ||
        JSON.stringify(c.reviewedRuleBinding) !== JSON.stringify(b))
        return 'PP2 执行身份冲突、材料绑定未就绪或版本不支持；请重新读取，当前不能提交。';
    if ((options.candidates || []).some((candidate) => {
        const r = candidate.recommendation;
        const policyKeys = ['policyId', 'policyVersion', 'patientSpecific', 'scoreIndependent', 'compatibility', 'requiredDeclaration', 'ruleContextPreparationBinding'];
        const relationships = [undefined, ...relationshipOptions(candidate)];
        return !r || r.policyHash !== bp3IdentityHash(Object.fromEntries(policyKeys.map((k) => [k, r[k]]))) || r.candidateId !== candidate.candidateId || r.analysisId !== options.analysisId ||
            r.requiredDeclaration !== p.requiredDeclaration || !r.hypothesisId || r.allele?.genomeAssembly !== candidate.allele?.genomeAssembly || JSON.stringify(r.allele) !== JSON.stringify(candidate.allele) || !sha(r.policyHash) || !sha(r.executionSourceSha256) || !sha(r.publishedCandidateRawSha256) ||
            r.recommendationId !== `recommendation-${bp3IdentityHash(Object.fromEntries(Object.entries(r).filter(([k]) => !['recommendationId', 'generatedAt'].includes(k))))}` ||
            JSON.stringify(r.ruleContextPreparationBinding) !== JSON.stringify(c) ||
            (r.resourceCompatibleTranscript?.ruleContextReady &&
                !r.annotations?.some((a) => JSON.stringify(a) === JSON.stringify(r.resourceCompatibleTranscript))) ||
            (r.annotations || []).some((a) => {
                const selectable = a.ruleContextReady || a.contextSelectable || relationships.some((rel) => annotationSelectable(a, rel));
                if (!selectable) return false;
                const transcript = transcriptOptions(candidate).find((t) => Number(t.index) === a.annotationIndex);
                return !ruleContextAnnotation(a) || !PP2_CONTEXT_POLICY.test(a.ruleContextSelectionPolicy || '') ||
                    a.ruleContextSelectionPolicy !== `REVIEWED_RULE_CONTEXT@8.0.0:${bp3IdentityHash(c)}` ||
                    !sha(a.annotationSourceSha256) || !/^annotation-[0-9a-f]{64}$/.test(a.annotationId || '') ||
                    !transcript?.eligible || transcript.versionedId !== a.versionedTranscriptId ||
                    transcript.annotation.geneSymbol !== a.geneSymbol || transcript.annotation.source !== a.annotationSource ||
                    transcript.annotation.version !== a.annotationSourceVersion ||
                    transcript.annotation.hgvsc !== a.hgvsc || transcript.annotation.hgvsp !== a.hgvsp;
            });
    })) return 'PP2 推荐与当前候选或 v8 上下文绑定不一致；请重新读取并确认。';
    return null;
}

export function pp2SavedRun(run) {
    return run?.executionProfile?.profileId === 'acmg-pp2-missense-development-v1' && pp2ScopeClaim(run) &&
        !pp2ScopeIssue({ ...run, requiredDeclaration: run.declarationVersion, candidates: [] }) &&
        (run.candidates || []).every((candidate) => (candidate.attempts || []).every((attempt) => !attempt.pp2 ||
            (attempt.pp2.criterionId === 'ACMG_AMP_2015:PP2' &&
                JSON.stringify(attempt.pp2.bundleIdentity) === JSON.stringify(run.executionProfile.reviewedRuleBinding))));
}

const BP3_CONTEXT_POLICY = /^REVIEWED_RULE_CONTEXT@7\.0\.0:[0-9a-f]{64}$/;
// BP3 alone is not a new-scope claim: historical HNF1A/joint runs retain their identity.
export function bp3ScopeClaim(options) {
    const p = options?.executionProfile;
    const bindings = [p?.reviewedRuleBinding, p?.ruleContextPreparationBinding,
        ...(options?.candidates || []).map((c) => c.recommendation?.ruleContextPreparationBinding)];
    return Boolean((options?.candidates || []).some((candidate) =>
        candidate.recommendation?.requiredDeclaration?.startsWith?.('ordinary-bp3-') ||
        candidate.recommendation?.annotations?.some((a) => a.ruleContextSelectionPolicy?.startsWith?.('REVIEWED_RULE_CONTEXT@7.'))) || p?.profileId?.startsWith?.('acmg-bp3-') ||
        [p?.requiredDeclaration, options?.requiredDeclaration, options?.declarationVersion]
            .some((v) => v?.startsWith?.('ordinary-bp3-')) ||
        bindings.some((b) => b?.schemaVersion?.includes?.('-bp3-') || b?.reviewedRuleBinding?.schemaVersion?.includes?.('-bp3-')));
}

export function bp3ScopeIssue(options) {
    if (!bp3ScopeClaim(options)) return null;
    const p = options?.executionProfile, b = p?.reviewedRuleBinding, c = p?.ruleContextPreparationBinding;
    const onlyBp3 = (ids) => JSON.stringify(ids) === '["ACMG_AMP_2015:BP3"]';
    const sha = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
    if (p?.profileId !== 'acmg-bp3-inframe-development-v1' || p.profileVersion !== '1.0.0' ||
        p.requiredDeclaration !== 'ordinary-bp3-inframe-v1' || options.requiredDeclaration !== p.requiredDeclaration ||
        !onlyBp3(p.enabledCriteria) || !onlyBp3(options.enabledCriteria) || !sha(p.manifestSha256) || p.manifestSha256 !== bp3IdentityHash(Object.fromEntries(Object.entries(p).filter(([k]) => k !== 'manifestSha256'))) ||
        !['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => p[k] === false) ||
        b?.schemaVersion !== 'reviewed-rule-bundle-binding-bp3-v7' || b.status !== 'CONFIGURED' ||
        !onlyBp3(b.criterionIds) || !b.bundleId?.trim?.() || !b.bundleVersion?.trim?.() || !sha(b.bundleSha256) || !sha(b.catalogSha256) || !sha(b.bundleRawSha256) ||
        JSON.stringify(b.algorithms) !== '{"simple-inframe-repeat-bp3":"1.0.0"}' ||
        !['automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => b[k] === false) ||
        c?.schemaVersion !== 'reviewed-rule-context-preparation-bp3-v7' || c.version !== '7.0.0' ||
        c.mode !== 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION' ||
        !['automaticTrigger', 'classificationEnabled', 'criterionPolicySelected', 'predictionCompatibilityClaimed'].every((k) => c[k] === false) ||
        JSON.stringify(c.reviewedRuleBinding) !== JSON.stringify(b))
        return 'BP3 执行身份冲突、材料绑定未就绪或版本不支持；请重新读取，当前不能提交。';
    if ((options.candidates || []).some((candidate) => {
        const r = candidate.recommendation;
        const policyKeys = ['policyId', 'policyVersion', 'patientSpecific', 'scoreIndependent', 'compatibility', 'requiredDeclaration', 'ruleContextPreparationBinding'];
        const relationships = [undefined, ...relationshipOptions(candidate)];
        return !r || r.policyHash !== bp3IdentityHash(Object.fromEntries(policyKeys.map((k) => [k, r[k]]))) || r.candidateId !== candidate.candidateId || r.analysisId !== options.analysisId ||
            r.requiredDeclaration !== p.requiredDeclaration || !r.hypothesisId || r.allele?.genomeAssembly !== candidate.allele?.genomeAssembly || JSON.stringify(r.allele) !== JSON.stringify(candidate.allele) || !sha(r.policyHash) || !sha(r.executionSourceSha256) || !sha(r.publishedCandidateRawSha256) ||
            r.recommendationId !== `recommendation-${bp3IdentityHash(Object.fromEntries(Object.entries(r).filter(([k]) => !['recommendationId', 'generatedAt'].includes(k))))}` ||
            JSON.stringify(r.ruleContextPreparationBinding) !== JSON.stringify(c) ||
            (r.resourceCompatibleTranscript?.ruleContextReady &&
                !r.annotations?.some((a) => JSON.stringify(a) === JSON.stringify(r.resourceCompatibleTranscript))) ||
            (r.annotations || []).some((a) => {
                const selectable = a.ruleContextReady || a.contextSelectable || relationships.some((rel) => annotationSelectable(a, rel));
                if (!selectable) return false;
                const transcript = transcriptOptions(candidate).find((t) => Number(t.index) === a.annotationIndex);
                return !ruleContextAnnotation(a) || !BP3_CONTEXT_POLICY.test(a.ruleContextSelectionPolicy || '') ||
                    a.ruleContextSelectionPolicy !== `REVIEWED_RULE_CONTEXT@7.0.0:${bp3IdentityHash(c)}` ||
                    !sha(a.annotationSourceSha256) || !/^annotation-[0-9a-f]{64}$/.test(a.annotationId || '') ||
                    !transcript?.eligible || transcript.versionedId !== a.versionedTranscriptId ||
                    transcript.annotation.geneSymbol !== a.geneSymbol || transcript.annotation.source !== a.annotationSource ||
                    transcript.annotation.version !== a.annotationSourceVersion ||
                    transcript.annotation.hgvsc !== a.hgvsc || transcript.annotation.hgvsp !== a.hgvsp;
            });
    })) return 'BP3 推荐与当前候选或 v7 上下文绑定不一致；请重新读取并确认。';
    return null;
}

export function bp3SavedRun(run) {
    return bp3ScopeClaim(run) && !bp3ScopeIssue({ ...run, requiredDeclaration: run.declarationVersion, candidates: [] });
}

function bp1Entry(attempt) {
    const value = attempt.bp1, input = value.resolvedInput || {}, material = value.mechanismMaterial;
    const policy = value.policy, evaluation = value.evaluation || value, context = value.context;
    const shown = (v) => v == null || v === '' ? '未记录/未进入该步骤'
        : typeof v === 'object' ? JSON.stringify(v) : String(v);
    const facts = [[material?.truncatingMechanism, '截短主导致病机制'],
        [material?.reviewedVariantApplicability?.exceptions, '当前变异例外检索'],
        [material?.reviewedVariantApplicability?.splicing, '当前变异剪接适用']];
    return {
        evaluation, hypothesisId: hypothesisIdOf(attempt), reasonScope: 'BP1_MISSENSE_V9',
        reasons: evaluation.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [
            ['本次目录关系', `${shown(context?.gene?.hgncId)} / ${shown(context?.disease?.mondoId)} / ${shown(context?.moi)}`],
            ['转录本 / 蛋白', `${shown(material?.transcript || value.storedAnnotation?.transcriptAccession)} / ${shown(material?.protein)}`],
            ['适用政策', `${shown(policy?.policyId)} @ ${shown(policy?.policyVersion)}`],
            ['规范选择', shown(value.policySelection)],
            ['编码 / 蛋白 HGVS', `${shown(input.hgvsc)} / ${shown(input.hgvsp)}`],
            ['参考 / 替换密码子', `${shown(input.refCodon)} → ${shown(input.altCodon)}`],
            ['参考 / 替换氨基酸', `${shown(input.refAminoAcid)} → ${shown(input.altAminoAcid)}`],
            ['CDS / 蛋白位置', `${shown(input.cdsPosition)} / ${shown(input.proteinPosition)}（转录本方向一基位置）`],
            ['链方向', input.strand === 1 ? '正链 (+1)' : input.strand === -1 ? '负链 (−1)' : shown(input.strand)],
            ['保存的完整解析输入与身份', shown(value.resolvedInput)],
            ['机制材料身份', `${shown(material?.materialId)} @ ${shown(material?.materialVersion)}`],
            ['材料精确范围', shown(material?.scope)],
            ['保存的机制声明', shown(material?.mechanismStatus)],
            ['受审变异适用身份', shown(material?.reviewedVariantApplicability?.identity)],
            ...facts.flatMap(([fact, label]) => [
                [`${label}：保存的材料声明`, `${shown(fact?.conclusion)}；依据类型 ${shown(fact?.basisType)}（不是本次评价结果）`],
                [`${label}：来源 / 条款`, `${shown(fact?.sourceIds)} / ${shown(fact?.clauseLocator || fact?.locator)}`],
                [`${label}：统计资料`, fact?.statistics ? shown(fact.statistics) : '未记录统计资料；条款路径不补造数字'],
                [`${label}：检索范围与发现`, `${shown(fact?.searchStatus)}；${shown(fact?.searchedDomains)}；${shown(fact?.findings)}`],
                [`${label}：方法与限制`, `${shown(fact?.method)}；${shown(fact?.limitations)}`],
                [`${label}：保存的独立审核记录`, shown(fact?.review)],
            ]),
            ['材料来源及版本', shown(material?.sourceRecords)],
            ['科学材料来源', shown(policy?.scientificSources)],
            ['专规声明', shown(policy?.specification)],
            ['模型 / 机制材料绑定', `${shown(policy?.transcriptModelBinding)} / ${shown(policy?.mechanismMaterialBinding)}`],
            ['注册表与材料绑定', `${shown(value.registryBinding)}；${shown(value.bundleIdentity)}`],
            ['评价身份 / 执行状态', `${shown(evaluation.evaluationId)} / ${shown(evaluation.deterministicTrace?.executionStatus)}`],
            ['保存执行步骤', (evaluation.deterministicTrace?.steps || []).map((step) => `${step.stepCode}: ${step.outcome}`).join('；') || '未记录/未进入该步骤'],
            ['证据边界', 'BP1 MET/SUPPORTING 是良性方向支持证据，不是最终良性分类。截短主导不等于显性遗传；NOT_MET 不反推 PP2/PVS1 或致病，未知未评价不是阴性。三项材料分别保留，不能由支持声明、COMPLETE 或空 findings 重算结果。剪接适用审核不等于普遍要求 RNA 实验，未做 RNA 不等于剪接正常。背景确认不是科学审核，TEST_ONLY 不是现实专业批准；执行计数不代表序列成功，采用与最终分类未开放。'],
        ], raw: value,
    };
}

function pp2Entry(attempt) {
    const value = attempt.pp2, input = value.resolvedInput || {}, material = value.spectrumMaterial;
    const policy = value.policy, evaluation = value.evaluation || value, context = value.context;
    const shown = (v) => v == null || v === '' ? '未记录/未进入该步骤'
        : typeof v === 'object' ? JSON.stringify(v) : String(v);
    const conditions = [['missenseMechanism', '错义致病机制'], ['lowBenignMissense', '低良性错义背景']];
    return {
        evaluation, hypothesisId: hypothesisIdOf(attempt), reasonScope: 'PP2_MISSENSE_V8',
        reasons: evaluation.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [
            ['本次目录关系', `${shown(context?.gene?.hgncId)} / ${shown(context?.disease?.mondoId)} / ${shown(context?.moi)}`],
            ['转录本 / 蛋白', `${shown(material?.transcript || value.storedAnnotation?.transcriptAccession)} / ${shown(material?.protein)}`],
            ['适用政策', `${shown(policy?.policyId)} @ ${shown(policy?.policyVersion)}`],
            ['规范选择', `${shown(value.policySelection?.outcome)}；${shown(value.policySelection?.catalogId)} @ ${shown(value.policySelection?.catalogVersion)}`],
            ['编码 / 蛋白 HGVS', `${shown(input.hgvsc)} / ${shown(input.hgvsp)}`],
            ['参考 / 替换密码子', `${shown(input.refCodon)} → ${shown(input.altCodon)}`],
            ['参考 / 替换氨基酸', `${shown(input.refAminoAcid)} → ${shown(input.altAminoAcid)}`],
            ['CDS / 蛋白位置', `${shown(input.cdsPosition)} / ${shown(input.proteinPosition)}（转录本方向一基位置）`],
            ['链方向', input.strand === 1 ? '正链 (+1)' : input.strand === -1 ? '负链 (−1)' : shown(input.strand)],
            ['保存的序列输入身份', shown({ storedAnnotationSha256: input.storedAnnotationSha256, transcriptModelSha256: input.transcriptModelSha256, nativeModelSha256: input.nativeModelSha256, spectrumMaterialSha256: input.spectrumMaterialSha256 })],
            ['谱材料身份', `${shown(material?.materialId)} @ ${shown(material?.materialVersion)}`],
            ['材料精确范围', shown(material?.scope)],
            ['覆盖 / 机制声明', `${shown(material?.coverage)} / ${shown(material?.mechanismStatus)}`],
            ...conditions.flatMap(([key, label]) => {
                const condition = material?.conditions?.[key], review = condition?.review, stats = condition?.statistics;
                return [
                    [`${label}：保存的材料声明`, `${shown(condition?.conclusion)}；依据类型 ${shown(condition?.basisType)}（不是本次评价结果）`],
                    [`${label}：来源 / 条款`, `${shown(condition?.sourceIds)} / ${shown(condition?.clauseLocator)}`],
                    [`${label}：统计资料`, stats ? `人群 ${shown(stats.population)}；队列 ${shown(stats.cohort)}；样本纳排 ${shown(stats.sampleInclusion)}；变异纳排 ${shown(stats.variantInclusion)}；计数 ${shown(stats.count)}；分母 ${shown(stats.denominator)}；计数定位 ${shown(stats.countLocator)}` : '未记录统计资料；条款路径不补造数字'],
                    [`${label}：统计适用与局限`, stats ? `良性依据 ${shown(stats.benignClassificationBasis)}；去重 ${shown(stats.deduplication)}；偏倚审核 ${shown(stats.selectionBiasReview)}；适用范围 ${shown(stats.scopeApplicability)}；局限 ${shown(stats.limitations)}` : '未记录/未进入该步骤'],
                    [`${label}：保存的审核记录`, `${shown(review?.status)}；审核人 ${shown(review?.reviewer)}；日期 ${shown(review?.evaluatedAt)}`],
                    [`${label}：审核依据 / 内容绑定`, `${shown(review?.basis)}；${shown(review?.sourceIds)}；${shown(review?.contentSha256)}`],
                    [`${label}：本次已解析条件`, shown(input.reviewedConditions?.[key])],
                ];
            }),
            ['材料来源及版本', shown(material?.sourceRecords)],
            ['科学材料来源', shown(policy?.scientificSources)],
            ['专规 / 例外声明', shown(policy?.specification)],
            ['模型 / 谱绑定', `${shown(policy?.transcriptModelBinding)} / ${shown(policy?.spectrumMaterialBinding)}`],
            ['注册表与材料绑定', `${shown(value.registryBinding)}；${shown(value.bundleIdentity)}`],
            ['评价身份 / 执行状态', `${shown(evaluation.evaluationId)} / ${shown(evaluation.deterministicTrace?.executionStatus)}`],
            ['保存执行步骤', (evaluation.deterministicTrace?.steps || []).map((step) => `${step.stepCode}: ${step.outcome}`).join('；') || '未记录/未进入该步骤'],
            ['证据边界', 'PP2 MET/SUPPORTING 是致病方向支持证据，不是最终致病分类；NOT_MET 不反推 BP1 或良性，未知未评价不是阴性。两条件不能用错义标签、预测分数、PM1 命中或零条良性记录代替。背景确认不是科学审核，TEST_ONLY 保存的审核记录不是现实专业批准，采用与最终分类未开放。'],
        ], raw: value,
    };
}

function bp3Entry(attempt) {
    const value = attempt.bp3, input = value.resolvedInput || {}, policy = value.policy;
    const evaluation = value.evaluation || value, context = value.context;
    const envelope = input.equivalentEditEnvelope, region = input.selectedRegion;
    const shown = (v) => v == null || v === '' ? '未记录/未进入该步骤'
        : typeof v === 'object' ? JSON.stringify(v) : String(v);
    return {
        evaluation, hypothesisId: hypothesisIdOf(attempt), reasonScope: 'BP3_INFRAME_V7',
        reasons: evaluation.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [
            ['本次目录关系', `${shown(context?.gene?.hgncId)} / ${shown(context?.disease?.mondoId)} / ${shown(context?.moi)}`],
            ['转录本 / 蛋白', `${shown(input.transcript || value.storedAnnotation?.transcriptAccession)} / ${shown(input.protein || value.regionMaterial?.protein)}`],
            ['适用政策', `${shown(policy?.policyId)} @ ${shown(policy?.policyVersion)}`],
            ['规范选择', `${shown(value.policySelection?.outcome)}；${shown(value.policySelection?.catalogId)} @ ${shown(value.policySelection?.catalogVersion)}`],
            ['模型绑定', shown(policy?.transcriptModelBinding)],
            ['区域版本与来源', `${shown(policy?.regionMaterialBinding)}；${shown(value.regionMaterial?.sourceRecords)}`],
            ['科学材料来源', shown(policy?.scientificSources)],
            ['简单插入 / 缺失', ({ SIMPLE_INFRAME_INSERTION: '简单框内插入', SIMPLE_INFRAME_DELETION: '简单框内缺失' })[input.branch] || shown(input.branch)],
            ['链方向', input.strand === 1 ? '正链 (+1)' : input.strand === -1 ? '负链 (−1)' : shown(input.strand)],
            ['序列核对状态', shown(input.sequenceDerivationStatus)],
            ['净核苷酸 / 氨基酸变化', `${shown(input.netNucleotideChange)} / ${shown(input.netAminoAcidChange)}`],
            ['参考编辑边界', `${shown(input.referenceEditStart0)}–${shown(input.referenceEditEnd0)}；参考 CDS 零基边界，删除区间为半开，插入两边界相同`],
            ['完整等价编辑包络', `firstStart0=${shown(envelope?.firstStart0)}；lastStart0=${shown(envelope?.lastStart0)}；equivalentPositionCount=${shown(envelope?.equivalentPositionCount)}（参考 CDS 零基边界，全部等价落点）`],
            ['包络参考蛋白范围', `${shown(envelope?.proteinStart)}–${shown(envelope?.proteinEnd)}（参考蛋白一基闭区间，非改变后蛋白或单一所选对齐）`],
            ['所选区域', `${shown(region?.regionId)}；${shown(region?.start)}–${shown(region?.end)}（参考蛋白一基闭区间）`],
            ['重复事实 / 功能适用声明', `${shown(region?.repeatStatus)} / ${shown(region?.functionalStatus)}；“无已知功能”是保存的受审适用声明，不等于实验已证明无功能`],
            ['功能适用审核', `${shown(region?.functionalReview?.status)}；审核人 ${shown(region?.functionalReview?.reviewer)}；日期 ${shown(region?.functionalReview?.evaluatedAt)}`],
            ['功能审核依据与来源', `${shown(region?.functionalReview?.basis)}；${shown(region?.functionalReview?.sourceIds)}`],
            ['注册表与材料绑定', `${shown(value.registryBinding)}；${shown(value.bundleIdentity)}`],
            ['评价身份 / 执行状态', `${shown(evaluation.evaluationId)} / ${shown(evaluation.deterministicTrace?.executionStatus)}`],
            ['保存执行步骤', (evaluation.deterministicTrace?.steps || []).map((step) => `${step.stepCode}: ${step.outcome}`).join('；') || '未记录/未进入该步骤'],
            ['证据边界', 'BP3 MET/SUPPORTING 是良性方向支持证据，不是最终良性分类；NOT_MET 不反推 PM4 或致病，未评价不是阴性。背景确认不是科学审核，TEST_ONLY 合成材料不是专业批准，采用和最终分类未开放。'],
        ], raw: value,
    };
}

const PVS1_CONTEXT_POLICY = /^REVIEWED_RULE_CONTEXT@6\.0\.0:[0-9a-f]{64}$/;
const PVS1_ALGORITHM_VERSIONS = ['1.0.4', '1.1.0', '1.2.0', '1.3.0', '1.4.0', '1.5.0'];
// PVS1 alone is not a new-scope claim: historical HNF1A/joint runs retain their identity.
export function pvs1NmdScopeClaim(options) {
    const p = options?.executionProfile;
    const bindings = [p?.reviewedRuleBinding, p?.ruleContextPreparationBinding,
        ...(options?.candidates || []).map((c) => c.recommendation?.ruleContextPreparationBinding)];
    return Boolean((options?.candidates || []).some((candidate) => candidate.recommendation?.annotations?.some((a) => PVS1_CONTEXT_POLICY.test(a.ruleContextSelectionPolicy || ''))) || p?.profileId?.startsWith?.('acmg-pvs1-nmd-') ||
        [p?.requiredDeclaration, options?.requiredDeclaration, options?.declarationVersion]
            .some((v) => v?.startsWith?.('ordinary-pvs1-nmd-')) ||
        bindings.some((b) => b?.schemaVersion?.includes?.('-pvs1-') || b?.reviewedRuleBinding?.schemaVersion?.includes?.('-pvs1-')));
}

export function pvs1NmdScopeIssue(options) {
    if (!pvs1NmdScopeClaim(options)) return null;
    const p = options?.executionProfile, b = p?.reviewedRuleBinding, c = p?.ruleContextPreparationBinding;
    const onlyPvs1 = (ids) => JSON.stringify(ids) === '["ACMG_AMP_2015:PVS1"]';
    const sha = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
    if (p?.profileId !== 'acmg-pvs1-nmd-development-v1' || p.profileVersion !== '1.0.0' ||
        p.requiredDeclaration !== 'ordinary-pvs1-nmd-v1' || options.requiredDeclaration !== p.requiredDeclaration ||
        !onlyPvs1(p.enabledCriteria) || !onlyPvs1(options.enabledCriteria) || !sha(p.manifestSha256) ||
        !['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => p[k] === false) ||
        b?.schemaVersion !== 'reviewed-rule-bundle-binding-pvs1-v6' || b.status !== 'CONFIGURED' ||
        !onlyPvs1(b.criterionIds) || !b.bundleId?.trim?.() || !b.bundleVersion?.trim?.() || !sha(b.bundleSha256) || !sha(b.catalogSha256) || !sha(b.bundleRawSha256) ||
        !ps2Keys(b.algorithms, ['transcript-truncating-nmd-pvs1']) ||
        !PVS1_ALGORITHM_VERSIONS.includes(b.algorithms['transcript-truncating-nmd-pvs1']) ||
        !['automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => b[k] === false) ||
        c?.schemaVersion !== 'reviewed-rule-context-preparation-pvs1-v6' || c.version !== '6.0.0' ||
        c.mode !== 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION' ||
        !['automaticTrigger', 'classificationEnabled', 'criterionPolicySelected', 'predictionCompatibilityClaimed'].every((k) => c[k] === false) ||
        !ps2Same(c.reviewedRuleBinding, b) ||
        (Object.hasOwn(b, 'contextPreparation') && b.contextPreparation !== 'SELECTED_TOPN_V3_WITH_EXPLICIT_LEGACY_PROJECTION_1.0.0') ||
        (Object.hasOwn(b, 'pvs1Knowledge') && (b.pvs1Knowledge?.schemaVersion !== 'pvs1-knowledge-binding-v1' ||
            !['1.0.0', '1.1.0', '1.2.0'].includes(b.pvs1Knowledge.version) || b.pvs1Knowledge.clinicalAcceptance !== false ||
            !sha(b.pvs1Knowledge.sourceCandidatesRawSha256) || !sha(b.pvs1Knowledge.adoptionRawSha256))))
        return 'PVS1 执行身份冲突、材料绑定未就绪或版本不支持；请重新读取，当前不能提交。';
    if ((options.candidates || []).some((candidate) => {
        const r = candidate.recommendation;
        const relationships = [undefined, ...relationshipOptions(candidate)];
        return !r || r.candidateId !== candidate.candidateId || r.analysisId !== options.analysisId ||
            r.requiredDeclaration !== p.requiredDeclaration || !r.hypothesisId || r.allele?.genomeAssembly !== candidate.allele?.genomeAssembly || JSON.stringify(r.allele) !== JSON.stringify(candidate.allele) || !sha(r.policyHash) || !sha(r.executionSourceSha256) ||
            !/^recommendation-[0-9a-f]{64}$/.test(r.recommendationId || '') ||
            !ps2Same(r.ruleContextPreparationBinding, c) ||
            (r.resourceCompatibleTranscript?.ruleContextReady &&
                !r.annotations?.some((a) => JSON.stringify(a) === JSON.stringify(r.resourceCompatibleTranscript))) ||
            (r.annotations || []).some((a) => {
                const selectable = a.ruleContextReady || a.contextSelectable || relationships.some((rel) => annotationSelectable(a, rel));
                if (!selectable) return false;
                const transcript = transcriptOptions(candidate).find((t) => Number(t.index) === a.annotationIndex);
                return !ruleContextAnnotation(a) || !PVS1_CONTEXT_POLICY.test(a.ruleContextSelectionPolicy || '') ||
                    !sha(a.annotationSourceSha256) || !/^annotation-[0-9a-f]{64}$/.test(a.annotationId || '') ||
                    !transcript?.eligible || transcript.versionedId !== a.versionedTranscriptId ||
                    transcript.annotation.geneSymbol !== a.geneSymbol || transcript.annotation.source !== a.annotationSource ||
                    transcript.annotation.version !== a.annotationSourceVersion ||
                    transcript.annotation.hgvsc !== a.hgvsc || transcript.annotation.hgvsp !== a.hgvsp;
            });
    })) return 'PVS1 推荐与当前候选或 v6 上下文绑定不一致；请重新读取并确认。';
    return null;
}

// Read saved facts only. Partial resolvedInput is not evidence that a later step ran.
function pvs1NmdEntry(attempt) {
    const value = attempt.pvs1, input = value.resolvedInput || {}, policy = value.policy;
    const evaluation = value.evaluation || value;
    const shown = (v) => v == null || v === '' ? '未记录/未进入该步骤'
        : typeof v === 'object' ? JSON.stringify(v) : String(v);
    const context = value.context;
    const altered = input.coordinateSystem === 'ALTERED_CDS';
    const escape = input.escapeAssessment;
    const splice = input.spliceAssessment;
    const knowledge = value.knowledgeAssessment;
    const supply = value.transcriptMaterialSupply;
    const selfAssessed = policy?.review?.kind === 'IMPLEMENTER_SOURCE_TRANSLATION_SYNTHETIC_RESEARCH_ONLY';
    return {
        evaluation, hypothesisId: hypothesisIdOf(attempt), reasonScope: 'PVS1_NMD_V6',
        reasons: evaluation.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [
            ['本次目录关系', `${shown(context?.gene?.hgncId)} / ${shown(context?.disease?.mondoId)} / ${shown(context?.moi)}`],
            ['转录本', shown(input.selectedTranscript || value.storedAnnotation?.transcriptAccession ||
                (context?.transcript?.accession && `${context.transcript.accession}.${context.transcript.version}`))],
            ['适用政策', `${shown(policy?.policyId)} @ ${shown(policy?.policyVersion)}`],
            ['政策证据强度上限', shown(policy?.appliedStrength)],
            ...(input.transcriptTopology ? [
                ['转录本结构分支', input.transcriptTopology.kind === 'SINGLE_EXON'
                    ? '单外显子：无外显子连接点，按独立非 NMD 条件判断'
                    : '末次连接点位于编码区末端或 3′UTR：需要该结构的适用依据'],
                ['结构适用依据', input.transcriptTopology.kind === 'SINGLE_EXON'
                    ? shown(policy?.escapeScope?.basis) : shown(policy?.nmdScope?.utrJunctionApplicability?.basis)],
            ] : []),
            ['本次计算分支', escape ? 'NMD 逃逸：按保存的位置及蛋白后果规则评价' : input.nmdPrediction === 'PREDICTED_NMD' ? '预测 NMD：按保存的外显子与终止位置评价' : '未取得可评价分支；以下保留实际已完成的步骤'],
            ...(splice ? [
                ['剪接后果依据', splice.evidenceKind === 'OBSERVED_RNA' ? '来源经核定的 RNA 实验后果；NMD 仍为预测' : '经核定的经典位点预测后果；不是已观察到的 RNA 实验结果'],
                ['剪接事件与参考删除区间', shown(splice.event)],
                ['剪接核定与强度上限', `${shown(splice.reviewId)} / ${shown(splice.maximumStrength)}`],
                ['剪接后保留外显子', shown(splice.retainedExons)],
                ['剪接完整来源与核定', shown(value.spliceConsequenceMaterial)],
                ['重复计证边界', '同一剪接效应不能再叠加 PP3 或 PS3；本入口不执行最终联合分类。'],
            ] : []),
            ['政策核定角色', selfAssessed ? '实施者来源解释自验，仅合成研究；未取得独立科学审核或临床批准' : `原记录：${shown(policy?.review?.kind || policy?.review?.status)}；未记录独立或临床批准时不推定已批准`],
            ['政策 / 逃逸核定记录', `${shown(policy?.review?.reviewId)} / ${shown(policy?.escapeScope?.reviewId)}`],
            ['知识供给', knowledge ? `${shown(knowledge.status)}；${knowledge.reasonCode ? reasonHelp(knowledge.reasonCode, 'PVS1_NMD_V6') : '按保存的精确机制、模型与采用绑定核验'}；${shown(knowledge.g2pConsistency)}` : '历史结果未记录知识供给状态'],
            ['本次材料用途', knowledge?.adoption ? `${shown(knowledge.adoption.purpose)} / ${shown(knowledge.adoption.allowedDataClasses)}；该绑定不授权其他数据类别` : '原记录未提供采用用途，不推定临床可用'],
            ...(knowledge?.adoption?.qualification ? [
                ['来源资格方式', '自动核对未改动的政策条件、原审核和完整来源；没有新增专家审核或临床批准'],
            ] : []),
            ...(knowledge?.missingExecutableConditions ? [
                ['尚缺可执行条件', '机制与证据强度、疾病相关转录本及外显子、NMD及补救条件尚未形成完整的可执行资料；原数据库记录已保留'],
            ] : []),
            ...(escape ? [
                ['逃逸分支保存状态', shown(escape.status)],
                ['首个改变残基 / 参考与改变后蛋白长度', `${shown(escape.firstAlteredResidue)} / ${shown(escape.referenceProteinLength)} / ${shown(escape.alteredProteinLength)}`],
                ['参考尾段受影响 / 净蛋白缩短', `${shown(escape.referenceResiduesAffected)} / ${shown(escape.netProteinShortening)} 个残基；两种度量不同，不互相替代`],
            ] : []),
            ['规范选择', `${shown(value.policySelection?.outcome)}；${shown(value.policySelection?.catalogId)} @ ${shown(value.policySelection?.catalogVersion)}`],
            ['LoF 机制及来源', shown(policy?.mechanism)],
            ['科学材料来源', shown(policy?.scientificSources)],
            [escape ? '原 NMD 相关外显子 / 工程范围（不替代逃逸范围）' : '相关外显子 / 当前工程支持范围', `${shown(policy?.nmdScope?.relevantExonOrdinals)} / ${shown(policy?.nmdScope?.engineeringSupportedExonOrdinals)}`],
            [escape ? '原 NMD 工程范围理由' : '工程范围理由', shown(policy?.nmdScope?.engineeringScopeRationale)],
            ['模型绑定', shown(value.transcriptModelBinding)],
            ...(supply?.bindingMethod === 'EXACT_FULL_TRANSCRIPT_EQUIVALENCE' ? [
                ['自动序列与转录本供给', `${shown(supply.status)}；${reasonHelp(supply.reasonCode, 'PVS1_NMD_V6')}`],
                ['转录本结构核对', supply.status === 'READY' ? `完整 RNA、编码序列及 ${shown(supply.verifiedExonCount)} 个外显子一致；原政策和转录本版本不变` : '未通过完整核对，不能以已有模型代替失败的本地读取'],
                ['实际供给 / 政策模型摘要', `${shown(supply.suppliedModelCanonicalSha256)} / ${shown(supply.boundModelCanonicalSha256)}`],
            ] : []),
            ['变异类型 / 保存注释', `${shown(input.variantMechanism || value.storedAnnotation?.consequence)}；${shown(value.storedAnnotation?.hgvsc)}；${shown(value.storedAnnotation?.hgvsp)}`],
            ['序列核对状态', shown(input.sequenceDerivationStatus)],
            ['外显子与 CDS 位点', `${shown(input.exonOrdinal)} / ${shown(input.exonTotal)}；c.${shown(input.cdsPosition)}`],
            ['密码子变化', `${shown(input.refCodon)} → ${shown(input.altCodon)}`],
            ['提前终止位置', `${shown(input.ptcStart)}–${shown(input.ptcEnd)}（${altered ? '改变后' : '参考'} CDS）`],
            ['末次外显子连接位置', altered
                ? `${shown(input.mutantLastJunctionCdsPosition)}（改变后 CDS）；${shown(input.referenceLastJunctionCdsPosition)}（参考 CDS）`
                : `${shown(input.lastJunctionCdsPosition)}（参考 CDS）`],
            ['距末次外显子连接', shown(input.distanceToLastJunction)],
            ['NMD 保存状态', shown(input.nmdPrediction)],
            ...(input.positionConditionChecks || input.escapePositionConditionChecks ? [
                ['已核定位置与逐项检查', shown(input.escapePositionConditionChecks || input.positionConditionChecks)],
            ] : []),
            ...(escape ? [
                ['外显子相关性与人群耐受', shown(escape.exonChecks)],
                ['逃逸规则依据与匹配', shown(escape.ruleChecks)],
                ['本次选中逃逸规则', shown(escape.selectedRule)],
            ] : []),
            ...(altered ? [
                ['净长度变化', shown(input.netLengthChange)],
                [splice ? '首个改变氨基酸' : '首个改变氨基酸 / 终止相对编号', splice ? shown(input.firstChangedProteinPosition) : `${shown(input.firstChangedProteinPosition)} / fsTer${shown(input.terminationDistance)}`],
                ['PTC 参考对应（起/中/末）', `${shown(input.ptcReferenceStart)} / ${shown(input.ptcReferenceMiddle)} / ${shown(input.ptcReferenceEnd)}`],
                ['PTC 参考映射', `${shown(input.ptcReferenceMapping)}${input.ptcReferenceMapping === 'CONTAINS_INSERTED_BASES' ? '；含插入碱基，部分无参考对应' : ''}`],
            ] : []),
            ['保存执行步骤', (evaluation.deterministicTrace?.steps || []).map((step) => `${step.stepCode}: ${step.outcome}`).join('；') || '未记录/未进入该步骤'],
            ['证据边界', '预测不是实验结果；背景确认不是科学批准。TEST_ONLY 合成材料不代表科学采用；采用和最终分类未开放。未评价不等于不满足或良性。'],
        ], raw: value,
    };
}

export function pvs1NmdSavedRun(run) {
    return pvs1NmdScopeClaim(run) && !pvs1NmdSavedIssue(run);
}

// Verify saved transport associations, not scientific rules; never recalculate strength.
export function pvs1NmdSavedIssue(run) {
    if (!pvs1NmdScopeClaim(run)) return null;
    if (pvs1NmdScopeIssue({ ...run, requiredDeclaration: run.declarationVersion, candidates: [] }))
        return '保存的 PVS1 执行身份不受支持或相互冲突；可导出完整原始记录，不恢复提交资格。';
    const binding = run.executionProfile.reviewedRuleBinding;
    const versions = { 'pvs1-truncating-nmd-policy-v1': '1.0.4', 'pvs1-truncating-nmd-policy-v2': '1.1.0', 'pvs1-truncating-nmd-policy-v3': '1.2.0', 'pvs1-truncating-nmd-policy-v4': '1.3.0' };
    if (!Array.isArray(run.candidates)) return '保存的 PVS1 候选列表缺失；可导出原始记录，不能据此展示科学结论。';
    const invalid = run.candidates.some((candidate) => !Array.isArray(candidate?.attempts) || candidate.attempts.some((attempt) => {
        if (!attempt || typeof attempt !== 'object') return true;
        const v = attempt.pvs1;
        if (!v) return false; // Preflight may stop before a core result exists.
        if (v.bundleIdentity && !ps2Same(v.bundleIdentity, binding)) return true;
        // Legacy records keep their original read-only contract.
        const commonCoding = Array.isArray(v.resolvedInput?.ptcExonOrdinals) ||
            v.evaluation?.evaluationResult?.reasonCode === 'PVS1_EXON_NOT_RELEVANT';
        if (!commonCoding && binding.algorithms['transcript-truncating-nmd-pvs1'] === '1.0.4')
            return Boolean(v.resolvedInput?.transcriptTopology || v.policy && (v.policy.schemaVersion !== 'pvs1-truncating-nmd-policy-v1' ||
                ['STRONG', 'MODERATE', 'SUPPORTING'].includes(v.policy.appliedStrength)));
        const e = v.evaluation || v, p = v.policy, s = v.policySelection, input = v.resolvedInput;
        const capped = ['STRONG', 'MODERATE', 'SUPPORTING'].includes(p?.appliedStrength);
        const topology = input?.transcriptTopology;
        const expectedVersion = commonCoding ? '1.11.0' : topology ? '1.5.0' : capped ? '1.4.0' : versions[p?.schemaVersion];
        if (e.criterionId !== 'ACMG_AMP_2015:PVS1' ||
            !['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(e.evaluationResult?.status)) return true;
        if (e.evaluationResult.status === 'NOT_EVALUATED' && !p) return false;
        if (!p || !s || !versions[p.schemaVersion] || p.review?.status !== 'REVIEWED_IMPLEMENTED' ||
            (commonCoding ? binding.codingJunctionVersion !== expectedVersion :
                PVS1_ALGORITHM_VERSIONS.indexOf(expectedVersion) > PVS1_ALGORITHM_VERSIONS.indexOf(binding.algorithms['transcript-truncating-nmd-pvs1'])) ||
            p.policyId !== s.policyId || p.policyVersion !== s.policyVersion ||
            bp3IdentityHash(p) !== s.policySha256 || v.policySha256 !== s.policySha256 ||
            (e.deterministicTrace && (e.deterministicTrace.evaluatorVersion !== expectedVersion ||
                (e.deterministicTrace.algorithmId != null && e.deterministicTrace.algorithmId !== p.policyId) ||
                (e.deterministicTrace.algorithmVersion != null && e.deterministicTrace.algorithmVersion !== p.policyVersion))) ||
            (v.context && e.contextId !== v.context.contextId) ||
            (v.transcriptModelBinding && !ps2Same(v.transcriptModelBinding, p.transcriptModelBinding))) return true;
        if (v.knowledgeAssessment?.status === 'READY') {
            const a = v.knowledgeAssessment.adoption;
            if (a?.policySha256 !== v.policySha256 || a.modelSha256 !== p.transcriptModelBinding?.canonicalSha256 ||
                a.exonNmdScopeSha256 !== bp3IdentityHash(p.nmdScope)) return true;
        }
        const escape = input?.escapeAssessment;
        const splice = input?.spliceAssessment, fact = v.spliceConsequenceMaterial;
        if (topology) {
            if (topology.version !== '1.5.0' || !/^[a-f0-9]{64}$/.test(topology.fullSplicedTranscriptSha256) ||
                input.transcriptModelSha256 !== p.transcriptModelBinding?.canonicalSha256 ||
                !Number.isInteger(topology.exonCount) || topology.exonCount < 1 ||
                !Number.isInteger(topology.cdsLength) || topology.cdsLength < 6) return true;
            if (input.exonTotal != null && input.exonTotal !== topology.exonCount) return true;
            if (topology.kind === 'SINGLE_EXON') {
                if (topology.exonCount !== 1 || topology.referenceLastJunctionCdsPosition !== null ||
                    input.lastJunctionCdsPosition != null || input.distanceToLastJunction != null ||
                    e.evaluationResult.status === 'MET' && (!escape || input.nmdPrediction !== 'NMD_NOT_PREDICTED')) return true;
            } else if (topology.kind === 'LAST_JUNCTION_IN_3_PRIME_UTR') {
                if (topology.exonCount < 2 || !Number.isInteger(topology.referenceLastJunctionCdsPosition) ||
                    topology.referenceLastJunctionCdsPosition < topology.cdsLength ||
                    e.evaluationResult.status === 'MET' && p.nmdScope?.utrJunctionApplicability?.status !== 'REVIEWED_FOR_3_PRIME_UTR_JUNCTION') return true;
                const junction = input.referenceLastJunctionCdsPosition ?? input.lastJunctionCdsPosition;
                if (junction != null && junction !== topology.referenceLastJunctionCdsPosition) return true;
            } else return true;
        }
        const strengths = ['SUPPORTING', 'MODERATE', 'STRONG', 'VERY_STRONG'];
        if (fact && (p.schemaVersion !== 'pvs1-truncating-nmd-policy-v4' || bp3IdentityHash(fact) !== input?.spliceMaterialSha256)) return true;
        if (splice && (!fact || p.spliceScope?.status !== 'REVIEWED_FOR_PVS1_SPLICE' ||
            fact.review?.status !== 'REVIEWED_FOR_PVS1_SPLICE' || fact.review.reviewId !== splice.reviewId ||
            fact.outcomeId !== splice.outcomeId || fact.evidenceKind !== splice.evidenceKind ||
            !['OBSERVED_RNA', 'PREDICTED_CANONICAL'].includes(splice.evidenceKind) ||
            !ps2Same(fact.event, splice.event) || fact.transcriptModelSha256 !== p.transcriptModelBinding?.canonicalSha256 ||
            fact.transcript !== `${v.context?.transcript?.accession}.${v.context?.transcript?.version}` ||
            fact.hgncId !== v.context?.gene?.hgncId || fact.mondoId !== v.context?.disease?.mondoId || fact.moi !== v.context?.moi ||
            ['assembly', 'contig', 'position', 'reference', 'alternate'].some(k => fact.allele?.[k] !== v.context?.variant?.evidenceQueryAllele?.[k]) ||
            !strengths.includes(p.spliceScope.maximumStrength) || !strengths.includes(fact.review.maximumStrength) ||
            splice.maximumStrength !== strengths[Math.min(strengths.indexOf(p.spliceScope.maximumStrength), strengths.indexOf(fact.review.maximumStrength))])) return true;
        if (escape && (!['pvs1-truncating-nmd-policy-v3', 'pvs1-truncating-nmd-policy-v4'].includes(p.schemaVersion) ||
            p.escapeScope?.status !== 'REVIEWED_FOR_PVS1_ESCAPE' || escape.reviewId !== p.escapeScope.reviewId ||
            input.nmdPrediction !== 'NMD_NOT_PREDICTED')) return true;
        if (e.evaluationResult.status === 'MET') {
            if (!['VERY_STRONG', 'STRONG', 'MODERATE', 'SUPPORTING'].includes(e.appliedStrength)) return true;
            if ((fact || input?.variantMechanism === 'SPLICE_CDS_DELETION') && !splice) return true;
            const selectedStrength = escape && capped
                ? strengths[Math.min(strengths.indexOf(escape.selectedRule?.strength), strengths.indexOf(p.appliedStrength))]
                : escape ? escape.selectedRule?.strength : p.appliedStrength;
            const expectedStrength = splice ? strengths[Math.min(strengths.indexOf(selectedStrength), strengths.indexOf(splice.maximumStrength))] : selectedStrength;
            if (e.appliedStrength !== expectedStrength) return true;
            if (!escape && input?.nmdPrediction !== 'PREDICTED_NMD') return true;
            if (escape && (escape.status !== 'MET' || !Array.isArray(p.escapeScope.rules) ||
                !p.escapeScope.rules.some((rule) => ps2Same(rule, escape.selectedRule)))) return true;
        } else if (e.appliedStrength && e.appliedStrength !== 'NONE') return true;
        return false;
    }));
    return invalid ? '保存的 PVS1 政策、模型、计算分支或强度身份不一致；暂不展示科学结论，可导出完整原始记录。' : null;
}


const PM1_CONTEXT_POLICY = /^REVIEWED_RULE_CONTEXT@5\.0\.0:[0-9a-f]{64}$/;
// PM1 alone is not a new-scope claim: historical HNF1A/joint runs retain their identity.
export function pm1ScopeClaim(options) {
    const p = options?.executionProfile;
    const bindings = [p?.reviewedRuleBinding, p?.ruleContextPreparationBinding,
        ...(options?.candidates || []).map((c) => c.recommendation?.ruleContextPreparationBinding)];
    return Boolean(p?.profileId?.startsWith?.('acmg-pm1-') ||
        [p?.requiredDeclaration, options?.requiredDeclaration, options?.declarationVersion]
            .some((v) => v?.startsWith?.('ordinary-pm1-')) ||
        bindings.some((b) => b?.schemaVersion?.includes?.('-pm1-') || b?.reviewedRuleBinding?.schemaVersion?.includes?.('-pm1-')));
}

export function pm1ScopeIssue(options) {
    if (!pm1ScopeClaim(options)) return null;
    const p = options?.executionProfile, b = p?.reviewedRuleBinding, c = p?.ruleContextPreparationBinding;
    const onlyPm1 = (ids) => JSON.stringify(ids) === '["ACMG_AMP_2015:PM1"]';
    const sha = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
    if (p?.profileId !== 'acmg-pm1-region-development-v1' || p.profileVersion !== '1.0.0' ||
        p.requiredDeclaration !== 'ordinary-pm1-region-v1' || options.requiredDeclaration !== p.requiredDeclaration ||
        !onlyPm1(p.enabledCriteria) || !onlyPm1(options.enabledCriteria) || !sha(p.manifestSha256) ||
        !['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => p[k] === false) ||
        b?.schemaVersion !== 'reviewed-rule-bundle-binding-pm1-v5' || b.status !== 'CONFIGURED' ||
        !onlyPm1(b.criterionIds) || !b.bundleId || !b.bundleVersion || !sha(b.bundleSha256) || !sha(b.catalogSha256) ||
        JSON.stringify(b.algorithms) !== '{"reviewed-protein-region-pm1":"1.0.0"}' ||
        !['automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((k) => b[k] === false) ||
        c?.schemaVersion !== 'reviewed-rule-context-preparation-pm1-v5' || c.version !== '5.0.0' ||
        c.mode !== 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION' ||
        !['automaticTrigger', 'classificationEnabled', 'criterionPolicySelected', 'predictionCompatibilityClaimed'].every((k) => c[k] === false) ||
        JSON.stringify(c.reviewedRuleBinding) !== JSON.stringify(b))
        return 'PM1 执行身份冲突、材料绑定未就绪或版本不支持；请重新读取，当前不能提交。';
    if ((options.candidates || []).some((candidate) => {
        const r = candidate.recommendation;
        const relationships = [undefined, ...relationshipOptions(candidate)];
        return !r || r.candidateId !== candidate.candidateId || r.analysisId !== options.analysisId ||
            r.requiredDeclaration !== p.requiredDeclaration || !sha(r.policyHash) || !sha(r.executionSourceSha256) ||
            !/^recommendation-[0-9a-f]{64}$/.test(r.recommendationId || '') ||
            JSON.stringify(r.ruleContextPreparationBinding) !== JSON.stringify(c) ||
            (r.annotations || []).some((a) => {
                const selectable = a.ruleContextReady || a.contextSelectable || relationships.some((rel) => annotationSelectable(a, rel));
                if (!selectable) return false;
                const transcript = transcriptOptions(candidate).find((t) => Number(t.index) === a.annotationIndex);
                return !ruleContextAnnotation(a) || !PM1_CONTEXT_POLICY.test(a.ruleContextSelectionPolicy || '') ||
                    !sha(a.annotationSourceSha256) || !/^annotation-[0-9a-f]{64}$/.test(a.annotationId || '') ||
                    !transcript?.eligible || transcript.versionedId !== a.versionedTranscriptId ||
                    transcript.annotation.geneSymbol !== a.geneSymbol || transcript.annotation.source !== a.annotationSource ||
                    transcript.annotation.version !== a.annotationSourceVersion;
            });
    })) return 'PM1 推荐与当前候选或 v5 上下文绑定不一致；请重新读取并确认。';
    return null;
}

export function pm1SavedRun(run) {
    return pm1ScopeClaim(run) && !pm1ScopeIssue({ ...run, requiredDeclaration: run.declarationVersion, candidates: [] });
}

export function pm4ScopeClaim(options) {
    if (localBatchScopeClaim(options)) return false;
    const p = options?.executionProfile;
    return Boolean((typeof p?.profileId === 'string' && p.profileId.startsWith('acmg-pm4-')) ||
        [p?.requiredDeclaration, options?.requiredDeclaration].some((v) => typeof v === 'string' && v.startsWith('ordinary-pm4-')) ||
        [p?.enabledCriteria, options?.enabledCriteria].some((ids) => Array.isArray(ids) && ids.includes('ACMG_AMP_2015:PM4')) ||
        [p?.reviewedRuleBinding?.schemaVersion, p?.ruleContextPreparationBinding?.schemaVersion]
            .some((v) => typeof v === 'string' && v.includes('pm4')));
}

export function pm4ScopeIssue(options) {
    if (!pm4ScopeClaim(options)) return null;
    const p = options?.executionProfile;
    const b = p?.reviewedRuleBinding;
    const c = p?.ruleContextPreparationBinding;
    const onlyPm4 = (ids) => JSON.stringify(ids) === '["ACMG_AMP_2015:PM4"]';
    const sha = (value) => /^[0-9a-f]{64}$/.test(value || '');
    if (p?.profileId !== 'acmg-pm4-inframe-development-v1' || p.profileVersion !== '1.0.0' ||
        p.requiredDeclaration !== 'ordinary-pm4-inframe-v1' || options.requiredDeclaration !== p.requiredDeclaration ||
        !onlyPm4(p.enabledCriteria) || !onlyPm4(options.enabledCriteria) ||
        !['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((key) => p[key] === false) ||
        !sha(p.manifestSha256) || b?.schemaVersion !== 'reviewed-rule-bundle-binding-pm4-v2' || b.status !== 'CONFIGURED' ||
        !onlyPm4(b.criterionIds) || !b.bundleId || !b.bundleVersion || !sha(b.bundleSha256) || !sha(b.catalogSha256) ||
        b.algorithms?.['simple-inframe-length-pm4'] !== '1.0.0' ||
        !['automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((key) => b[key] === false) ||
        c?.schemaVersion !== 'reviewed-rule-context-preparation-pm4-v2' || c.version !== '2.0.0' ||
        c.mode !== 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION' ||
        !['automaticTrigger', 'classificationEnabled', 'criterionPolicySelected', 'predictionCompatibilityClaimed'].every((key) => c[key] === false) ||
        JSON.stringify(c.reviewedRuleBinding) !== JSON.stringify(b))
        return 'PM4 执行身份冲突、材料绑定未就绪或版本不支持；请核对服务端配置并重新读取，当前不能提交。';
    if ((options.candidates || []).some(({ recommendation: r }) => r &&
        (JSON.stringify(r.ruleContextPreparationBinding) !== JSON.stringify(c) ||
        (r.annotations || []).some((a) => a.ruleContextReady &&
            (!ruleContextAnnotation(a) || !/^REVIEWED_RULE_CONTEXT@2\.0\.0:[0-9a-f]{64}$/.test(a.ruleContextSelectionPolicy))))))
        return 'PM4 推荐与当前上下文绑定不一致；请重新读取候选并确认。';
    return null;
}

// Recognize explicit single-rule claims before legacy fallback; joint lists retain their old scope.
function missenseScopeClaim(options, code) {
    const lower = code.toLowerCase();
    const p = options?.executionProfile;
    return Boolean(p?.profileId?.startsWith?.(`acmg-${lower}-`) ||
        [p?.requiredDeclaration, options?.requiredDeclaration].some((v) => v?.startsWith?.(`ordinary-${lower}-`)) ||
        [p?.reviewedRuleBinding?.schemaVersion, p?.ruleContextPreparationBinding?.schemaVersion]
            .some((v) => v?.includes?.(`-${lower}-`)) ||
        [p?.enabledCriteria, options?.enabledCriteria].some((ids) => JSON.stringify(ids) === JSON.stringify([`ACMG_AMP_2015:${code}`])));
}

export function ps1ScopeClaim(options) { return missenseScopeClaim(options, 'PS1'); }
export function pm5ScopeClaim(options) { return missenseScopeClaim(options, 'PM5'); }
const MISSENSE_CONTEXT_POLICIES = {
    PS1: /^REVIEWED_RULE_CONTEXT@3\.0\.0:[0-9a-f]{64}$/,
    PM5: /^REVIEWED_RULE_CONTEXT@4\.0\.0:[0-9a-f]{64}$/,
};

export function ps1ScopeIssue(options) { return missenseScopeIssue(options, 'PS1', 3); }
export function pm5ScopeIssue(options) { return missenseScopeIssue(options, 'PM5', 4); }
function missenseScopeIssue(options, code, version) {
    if (!missenseScopeClaim(options, code)) return null;
    const lower = code.toLowerCase();
    const p = options?.executionProfile, b = p?.reviewedRuleBinding, c = p?.ruleContextPreparationBinding;
    const onlyRule = (ids) => JSON.stringify(ids) === JSON.stringify([`ACMG_AMP_2015:${code}`]);
    const sha = (value) => /^[0-9a-f]{64}$/.test(value || '');
    if (p?.profileId !== `acmg-${lower}-missense-development-v1` || p.profileVersion !== '1.0.0' ||
        p.requiredDeclaration !== `ordinary-${lower}-missense-v1` || options.requiredDeclaration !== p.requiredDeclaration ||
        !onlyRule(p.enabledCriteria) || !onlyRule(options.enabledCriteria) ||
        !['formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((key) => p[key] === false) ||
        !sha(p.manifestSha256) || b?.schemaVersion !== `reviewed-rule-bundle-binding-${lower}-v${version}` || b.status !== 'CONFIGURED' ||
        !onlyRule(b.criterionIds) || !b.bundleId || !b.bundleVersion ||
        !sha(b.bundleSha256) || !sha(b.catalogSha256) || !sha(b.runtimeBindingSha256) ||
        b.algorithms?.MissenseComparisonRuntime !== '1.0.0' ||
        !['automaticTrigger', 'classificationEnabled', 'adoptionSupported'].every((key) => b[key] === false) ||
        c?.schemaVersion !== `reviewed-rule-context-preparation-${lower}-v${version}` || c.version !== `${version}.0.0` ||
        c.mode !== 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION' ||
        !['automaticTrigger', 'classificationEnabled', 'criterionPolicySelected', 'predictionCompatibilityClaimed'].every((key) => c[key] === false) ||
        JSON.stringify(c.reviewedRuleBinding) !== JSON.stringify(b))
        return `${code} 执行身份冲突、材料绑定未就绪或版本不支持；请核对服务端配置并重新读取，当前不能提交。`;
    if ((options.candidates || []).some((candidate) => {
        const r = candidate.recommendation;
        // Reuse the actual selection predicate, including relationship-dependent legacy paths.
        const relationships = [undefined, ...relationshipOptions(candidate)];
        return r && (JSON.stringify(r.ruleContextPreparationBinding) !== JSON.stringify(c) ||
            (r.annotations || []).some((a) => (a.ruleContextReady || a.contextSelectable ||
                relationships.some((relationship) => annotationSelectable(a, relationship))) &&
                (!ruleContextAnnotation(a) || !MISSENSE_CONTEXT_POLICIES[code].test(a.ruleContextSelectionPolicy || ''))));
    }))
        return `${code} 推荐与当前 v${version} 上下文绑定不一致；请重新读取候选并确认。`;
    return null;
}

export function currentBusinessCriteria(options) {
    if (localBatchScopeClaim(options)) return localBatchScopeIssue(options) ? [] : [...batchCriteriaFor(options)];
    if (pp4ScopeClaim(options)) return pp4ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PP4'];
    if (bs4ScopeClaim(options)) return bs4ScopeIssue(options) ? [] : ['ACMG_AMP_2015:BS4'];
    if (pp1ScopeClaim(options)) return pp1ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PP1'];
    if (bp2ScopeClaim(options)) return bp2ScopeIssue(options) ? [] : ['ACMG_AMP_2015:BP2'];
    if (pm3ScopeClaim(options)) return pm3ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PM3'];
    if (pm6ScopeClaim(options)) return pm6ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PM6'];
    if (ps2ScopeClaim(options)) return ps2ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PS2'];
    if (bp1ScopeClaim(options)) return bp1ScopeIssue(options) ? [] : ['ACMG_AMP_2015:BP1'];
    if (pp2ScopeClaim(options)) return pp2ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PP2'];
    if (bp3ScopeClaim(options)) return bp3ScopeIssue(options) ? [] : ['ACMG_AMP_2015:BP3'];
    if (pvs1NmdScopeClaim(options)) return pvs1NmdScopeIssue(options) ? [] : ['ACMG_AMP_2015:PVS1'];
    if (pm1ScopeClaim(options)) return pm1ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PM1'];
    if (pm5ScopeClaim(options)) return pm5ScopeIssue(options) || ps1ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PM5'];
    if (ps1ScopeClaim(options)) return ps1ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PS1'];
    if (pm4ScopeClaim(options)) return pm4ScopeIssue(options) ? [] : ['ACMG_AMP_2015:PM4'];
    const profile = options?.executionProfile;
    if (profile?.profileId === 'acmg-revel-pp3-application-v1') {
        return profile.profileVersion === '1.1.0' && profile.requiredDeclaration === 'ordinary-revel-pp3-v1' &&
            profile.contextPreparation === 'REVEL_NATIVE_CONTEXT_V1' &&
            JSON.stringify(profile.enabledCriteria) === '["ACMG_AMP_2015:PP3"]' &&
            JSON.stringify(options.enabledCriteria) === '["ACMG_AMP_2015:PP3"]' ? ['ACMG_AMP_2015:PP3'] : [];
    }
    const savedProfiles = {
        '2.0.0': ['INDEPENDENT_SAVED_VEP_SELECTION_V1', 'ASSOCIATE_SAVED_OBSERVATIONS', 'ordinary-pp3-bp4-development-v2'],
        '3.0.0': ['FIXED_SAVED_CALIBRATION_V1', 'CALIBRATE_SAVED_OBSERVATIONS', 'ordinary-pp3-bp4-development-v3'],
    };
    // Recognize a saved-operation claim before the legacy context fallback, including malformed claims.
    const savedClaim = (profile?.profileId === 'acmg-pp3-bp4-development-v1' &&
        ['2.0.0', '3.0.0'].includes(profile.profileVersion)) ||
        Object.values(savedProfiles).some(([context, operation, declaration]) =>
            profile?.contextPreparation === context || profile?.operation === operation ||
            profile?.requiredDeclaration === declaration || options?.requiredDeclaration === declaration);
    if (savedClaim) {
        const expected = savedProfiles[profile?.profileVersion];
        const criteria = ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'];
        if (!expected || profile?.profileId !== 'acmg-pp3-bp4-development-v1' ||
            profile.contextPreparation !== expected[0] || profile.operation !== expected[1] ||
            profile.requiredDeclaration !== expected[2] || options.requiredDeclaration !== expected[2] ||
            JSON.stringify(profile.enabledCriteria) !== JSON.stringify(criteria) ||
            JSON.stringify(options.enabledCriteria) !== JSON.stringify(criteria)) return [];
        return [...profile.enabledCriteria];
    }
    if (!['POPULATION_CONTEXT_ONLY_V1', 'PREDICTION_CONTEXT_ONLY_V1'].includes(profile?.contextPreparation) ||
        !Array.isArray(profile.enabledCriteria) ||
        JSON.stringify(profile.enabledCriteria) !== JSON.stringify(options.enabledCriteria)) return [];
    return [...profile.enabledCriteria];
}

export function revelSourceEligible(options, run, analysisId) {
    return currentBusinessCriteria(options).length === 1 && options.executionProfile?.profileId === 'acmg-revel-pp3-application-v1' &&
        run?.executionProfile?.profileId === 'acmg-revel-pp3-application-v1' && run.analysisId === analysisId &&
        !run.operation && ['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status) &&
        Boolean(run.requestSha256 && run.runId && run.requestedN === options.requestedN && options.revelApplicationBinding?.bindingSha256);
}

export function buildRevelApplicationDeclaration(options, analysisId, run, confirmedBinding) {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(bp2ScopeClaim)) throw new Error('BP2 请使用固定双变异材料独立确认入口。');
    if ([...arguments].some(pm3ScopeClaim)) throw new Error('PM3 请使用固定双变异材料独立确认入口。');
    if ([...arguments].some(pm6ScopeClaim)) throw new Error('PM6 请使用固定材料独立确认入口。');
    if (!confirmedBinding || confirmedBinding !== options?.revelApplicationBinding?.bindingSha256 || !revelSourceEligible(options, run, analysisId)) throw new Error('请先读取本分析已保存的 REVEL 来源，并显式确认采用；服务端仍会核验批准与同效应条件。');
    return { topN: run.requestedN, declarationVersion: 'ordinary-revel-pp3-v1', sampleId: options.sampleId,
        dataClass: run.dataClass, patient: null, hypotheses: [], revelApplication: {
            operation: 'ADOPT_SAVED_REVEL_PP3', sourceRunId: run.runId, sourceRequestSha256: run.requestSha256,
            bindingSha256: options.revelApplicationBinding.bindingSha256,
        } };
}

// Saved data only: no threshold comparison, scientific lookup, or inference from another candidate.
export function revelSavedDetails(run) {
    if (run?.executionProfile?.profileId !== 'acmg-revel-pp3-application-v1') return [];
    const applications = run.applications || [];
    return (run.candidates || []).flatMap((candidate) => (candidate.attempts || []).map((attempt) => {
        const hypothesisId = attempt.preflight?.original?.hypothesisId;
        const application = applications.find((a) => a.candidateId === candidate.candidateId && a.hypothesisId === hypothesisId);
        const clause = attempt.transcriptInput?.reviewedPrediction?.clauses?.PP3?.result;
        const context = attempt.context || {};
        return { candidateId: candidate.candidateId, hypothesisId,
            background: [context.gene?.hgncId, context.disease?.mondoId, context.moi, context.transcript?.accession && `${context.transcript.accession}.${context.transcript.version}`].filter(Boolean).join(' / '),
            policy: [clause?.policy?.policyId, clause?.policy?.policyVersion].filter(Boolean).join(' @ '),
            model: clause?.policy?.transcriptModelBinding,
            score: clause?.validatedScore ?? '未取得',
            sourceOutcome: clause?.evaluation?.evaluationResult?.status || clause?.evaluationResult?.status || clause?.status || 'NOT_EVALUATED',
            adoption: application?.adoptionStatus || 'NOT_APPLIED', strength: application?.appliedStrength || 'NONE',
            reason: application?.reasonCode || applications.find((a) => !a.candidateId)?.reasonCode || clause?.evaluationResult?.reasonCode || clause?.reasonCode || attempt.blockers?.[0]?.reasonCode || run.applicationReason,
            sourceRunId: application?.sourceRunId || run.sourceRunId || run.runId,
            clauseResultId: clause?.resultId,
        };
    }));
}

export function declarationDraftIdentity(analysisId, topN, dataClass, inputs) {
    return JSON.stringify([analysisId, String(topN), dataClass, inputs]);
}

export function evidenceSummary(entry) {
    if (entry?.summary) return entry.summary;
    const first = entry?.reasons?.[0];
    const code = entry?.evaluation?.evaluationResult?.reasonCode || (typeof first === 'string' ? first : first?.reasonCode);
    if (code) return reasonHelp(code, entry.reasonScope);
    return ({ MET: '满足本次适用条件', NOT_MET: '未满足本次适用条件' })[entry?.evaluation?.evaluationResult?.status] || '具体原因见已保存证据详情';
}

// React renders boolean children as nothing; preserve explicit quality flags in the detail row.
export function evidenceDetailValue(value) {
    return typeof value === 'boolean' ? String(value) : value ?? '未记录';
}

function explanationDetails(evaluation, saved) {
    const d = reasonExplanation(evaluation, saved);
    return d ? [['所缺条件或范围', d.missing], ['对本项判定的影响', d.impact]] : [];
}

export function materialPreparationDetails(criterion) {
    const value = criterion?.details?.materialPreparation;
    if (!value) return [];
    if (value.schemaVersion !== 'material-preparation-summary-v1' || value.reasonCode !== criterion.reasonCode
        || value.moduleResultPointer !== criterion.details.modulePointer
        || !['NOT_REQUIRED', 'NOT_APPLICABLE', 'EVALUATED', 'UNRESOLVED'].includes(value.status)
        || (value.status === 'NOT_APPLICABLE' && !(criterion.criterionId === 'ACMG_AMP_2015:PVS1' &&
            criterion.status === 'NOT_EVALUATED' && criterion.appliedStrength === 'NONE' && criterion.reasonCode === 'PVS1_CONSEQUENCE_NOT_APPLICABLE'))
        || (value.status === 'NOT_REQUIRED' && !criterion.details.applicabilityRouting?.skipSpecializedAcquisition)
        || (value.status === 'EVALUATED' && !['MET', 'NOT_MET'].includes(criterion.status)))
        return [['资料准备记录', '摘要与当前结果不相容，保留原结果并待核查。']];
    const preparationStatus = value.status === 'UNRESOLVED' && criterion.criterionId === 'ACMG_AMP_2015:PVS1' &&
        criterion.status === 'NOT_EVALUATED' && criterion.appliedStrength === 'NONE' && criterion.reasonCode === 'PVS1_CONSEQUENCE_NOT_APPLICABLE'
        ? 'NOT_APPLICABLE' : value.status;
    return [['专用资料准备', ({ NOT_REQUIRED: '适用性或当前软件范围已明确排除，未启动该专用取证分支',
        NOT_APPLICABLE: '原评价器已确认本次分支不适用；不再列为资料待补，不表示取证前已跳过',
        EVALUATED: '所需资料已进入规则评价', UNRESOLVED: '资料或核查尚有缺项，具体原因见本项结果' })[preparationStatus]],
    ['资料模块与定位', `${value.module} · ${value.moduleResultPointer}`],
    ...(Number.isSafeInteger(value.capturedDocuments) && value.capturedDocuments >= 0
        ? [['捕获资料', `${value.capturedDocuments} 份任务资料；不代表均适用于当前变异和背景`]] : []),
    ...(Array.isArray(value.excludedSourceReasons) && value.excludedSourceReasons.length
        ? [['未采用的缓存资料', [...new Set(value.excludedSourceReasons)].map(r => `${reasonHelp(r)} (${r})`).join('；')]] : [])];
}

export function populationPreparation(candidate, input, confirmed, options) {
    const state = candidateInputStatus(candidate, input);
    const diagnostic = candidate.recommendation?.pm2ContextPreparation?.diagnostics;
    const pm2Code = state.ba1Ready && !state.ready ? 'PM2_CONTEXT_CONFIRMATION_REQUIRED'
        : diagnostic?.reasonCode || 'PM2_VERIFIED_CONTEXT_MISSING';
    const pm2Supported = diagnostic?.policyCheck?.relationshipMatchCount > 0;
    const catalog = options?.executionProfile?.ba1RuntimeBinding?.catalog;
    return {
        ba1: catalog && catalog.status !== 'READY' ? reasonHelp(catalog.reasonCode || 'BA1_CATALOG_NOT_CONFIGURED')
            : candidate.blockers?.length ? '来源待核验' : state.ba1Ready || state.ready
            ? confirmed ? '声明已确认，待执行' : '疾病背景待确认' : '等位基因独立评价，专规适用性待核验',
        pm2: pm2Supported && (state.ready || state.pm2Ready) && !(state.ba1Ready && !state.ready)
            ? confirmed ? '声明已确认，待执行' : '可用背景待确认' : reasonHelp(pm2Code),
        bs1: state.bs1Ready ? confirmed ? '声明已确认，待独立核验' : '疾病背景待确认'
            : reasonHelp(state.bs1Policy?.reasonCode || candidate.recommendation?.bs1ContextPreparation?.reasonCode || 'BS1_CONTEXT_MISSING'),
        pm2Code,
    };
}

export function enabledCriteria(value) {
    const ids = value?.enabledCriteria;
    if (
        Array.isArray(ids) &&
        ids.length &&
        ids.every((id) => typeof id === 'string' && id.startsWith('ACMG_AMP_2015:')) &&
        new Set(ids).size === ids.length
    )
        return ids;
    const saved = (value?.candidates || []).flatMap((candidate) => [
        ...(candidate.ba1?.evaluation ? [candidate.ba1.evaluation.criterionId] : []),
        ...(candidate.attempts || [])
            .flatMap((attempt) => [
                ...(attempt.criterionEvaluations || []),
                ...(attempt.missenseComparison?.criterionEvaluations || []),
                ...(attempt.hnf1aFrequency?.criterionEvaluations || []),
                ...(attempt.populationFrequency?.criterionEvaluations || []),
                ...(attempt.pm2?.evaluation ? [attempt.pm2.evaluation] : attempt.pm2?.evaluationResult ? [{ criterionId: 'ACMG_AMP_2015:PM2' }] : []),
                ...(attempt.pvs1?.evaluation ? [attempt.pvs1.evaluation] : attempt.pvs1?.evaluationResult ? [{ criterionId: 'ACMG_AMP_2015:PVS1' }] : []),
                ...(attempt.functional?.evaluation ? [attempt.functional.evaluation] : attempt.functional?.evaluationResult ? [{ criterionId: 'ACMG_AMP_2015:PS3' }] : []),
                ...(attempt.pm1?.evaluation ? [attempt.pm1.evaluation] : attempt.pm1?.evaluationResult ? [{ criterionId: 'ACMG_AMP_2015:PM1' }] : []),
                ...(attempt.bp1 ? [{ criterionId: 'ACMG_AMP_2015:BP1' }] : []),
                ...(attempt.pp2 ? [{ criterionId: 'ACMG_AMP_2015:PP2' }] : []),
                ...(attempt.bp3 ? [{ criterionId: 'ACMG_AMP_2015:BP3' }] : []),
                ...(attempt.pm4 ? [{ criterionId: 'ACMG_AMP_2015:PM4' }] : []),
            ])
            .map((evaluation) => evaluation.criterionId),
    ]);
    const found = [
        ...new Set(saved.filter((id) => typeof id === 'string' && id.startsWith('ACMG_AMP_2015:'))),
    ];
    return found.length ? found : ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'];
}

function missenseComparisonEntry(attempt, id) {
    const value = attempt.missenseComparison;
    const evaluation = value?.criterionEvaluations?.find((e) => e.criterionId === id);
    const shown = (item) => (item == null || item === '' ? '未取得' : String(item));
    const policy = value?.policy;
    const transcript = value?.context?.transcript || attempt.context?.transcript;
    const atomics = value?.atomicEvidence || [];
    const measurement = (atomic, name) =>
        atomic?.measurements?.find((m) => m.metricId === name)?.rawValue;
    const relationLabels = {
        SAME_AMINO_ACID_CHANGE: '不同 DNA 变化造成相同氨基酸改变',
        SAME_RESIDUE_DIFFERENT_AMINO_ACID: '同一氨基酸位置的不同替换',
    };
    const relations = [
        ...new Set(
            atomics.map((atomic) => measurement(atomic, 'vep.proteinRelation')).filter(Boolean),
        ),
    ];
    const references = value?.references?.length
        ? value.references
        : atomics
              .filter((atomic) => measurement(atomic, 'clinvar.assertion.scv'))
              .map((atomic) => ({
                  scv: measurement(atomic, 'clinvar.assertion.scv'),
                  classification: measurement(atomic, 'clinvar.assertion.classification'),
                  transcriptId: measurement(atomic, 'vep.comparator.transcriptId'),
                  proteinChange: `${shown(measurement(atomic, 'vep.comparator.referenceAminoAcid'))}${shown(measurement(atomic, 'vep.comparator.proteinPosition'))}${shown(measurement(atomic, 'vep.comparator.alternateAminoAcid'))}`,
              }));
    const links = [];
    const addLink = (label, url) => {
        if (
            typeof url === 'string' &&
            /^https:\/\/[^\s]+$/.test(url) &&
            !links.some((link) => link.url === url)
        )
            links.push({ label, url });
    };
    addLink('适用规范', policy?.authorityUrl);
    references.forEach((reference) => {
        addLink(`参考来源 ${reference.scv || reference.vcv || ''}`, reference.sourceUrl);
        addLink(`专家论据 ${reference.erepoId || ''}`, reference.erepoUrl);
        (reference.rnaReview?.sourceUrls || []).forEach((url) =>
            addLink(`RNA 核验来源 ${reference.scv || ''}`, url),
        );
    });
    (value?.rnaReviewEvidence || []).forEach((review) => {
        addLink('公开剪接疑点来源', review.sourceUrl);
        (review.sourceUrls || []).forEach((url) => addLink('RNA 核验来源', url));
    });
    (value?.spliceEvidence || []).forEach((evidence) =>
        addLink('剪接证据来源', evidence.sourceUrl),
    );
    return {
        evaluation,
        hypothesisId: hypothesisIdOf(attempt),
        reasons: [...(value?.blockers || attempt.blockers || []),
            ...(evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : []),
            ...(id === 'ACMG_AMP_2015:PM5' && value?.scientificDecision?.reasonCode ? [value.scientificDecision.reasonCode] : [])],
        details: [
            [
                '适用规范',
                policy
                    ? `${shown(policy.policyId || policy.profileId || policy.id)} @ ${shown(policy.policyVersion || policy.profileVersion || policy.version)}`
                    : '未取得适用规范',
            ],
            [
                '目录关系',
                `${shown(value?.context?.gene?.hgncId || attempt.context?.gene?.hgncId)} / ${shown(value?.context?.disease?.mondoId || attempt.context?.disease?.mondoId)}`,
            ],
            ['遗传方式', shown(value?.context?.moi || attempt.context?.moi)],
            ['目标蛋白替换', shown(value?.targetIdentity?.hgvsp || value?.targetIdentity?.proteinVariantKey)],
            ['政策选择', value?.policySelection ? JSON.stringify(value.policySelection) : '未取得'],
            ['评价轨迹', evaluation?.deterministicTrace ? JSON.stringify(evaluation.deterministicTrace) : '未取得'],
            ...(id === 'ACMG_AMP_2015:PM5' ? [
                ['PM5 比较政策及来源', policy?.rules?.PM5 ? JSON.stringify(policy.rules.PM5) : '未记录'],
                ['保存的比较决定', value?.scientificDecision ? JSON.stringify(value.scientificDecision) : '未记录'],
                ['比较依据及来源', value?.proteinComparisons?.length ? JSON.stringify(value.proteinComparisons) : '未记录'],
                ['矩阵/关系比较', value?.proteinComparisons?.length ? value.proteinComparisons.map((comparison) =>
                    comparison.ruleMode === 'SAME_RESIDUE_DIFFERENT_SUBSTITUTION_SINGLE_REFERENCE'
                        ? `${comparison.referenceId ?? '未记录'}：按所选规范核对同一残基的不同替换；本路线不要求替换矩阵分数；保存强度 ${comparison.appliedStrength ?? '未记录'}`
                        : `${comparison.referenceId ?? '未记录'}：矩阵 ${comparison.matrixKind ?? '未记录'}；算子声明（不表示通过） ${comparison.comparison ?? '未记录'}；目标分数 ${comparison.targetSubstitutionScore ?? '未记录'} / 参考分数 ${comparison.referenceSubstitutionScore ?? '未记录'}；保存强度 ${comparison.appliedStrength ?? '未记录'}`).join('；') : '未记录'],
            ] : []),
            [
                '所选转录本',
                transcript
                    ? `${shown(transcript.accession)}.${shown(transcript.version)}`
                    : '未选择',
            ],
            [
                '检索到的蛋白关系',
                relations.length
                    ? relations.map((relation) => relationLabels[relation] || relation).join('；')
                    : '未核实',
            ],
            [
                '参考变异',
                references.length
                    ? references
                          .map((reference) =>
                              [
                                  reference.scv,
                                  reference.vcv,
                                  reference.classification,
                                  reference.transcriptId || reference.annotation?.transcriptAccession,
                                  reference.proteinChange || reference.nativeProteinIdentity?.hgvsp || reference.annotation?.hgvsProtein,
                                  reference.sourceIds?.join(' / '),
                              ]
                                  .filter(Boolean)
                                  .join(' · '),
                          )
                          .join('；')
                    : '未取得可信参考',
            ],
            [
                '独立致病依据',
                references.length
                    ? references
                          .map(
                              (reference) =>
                                  `${reference.scv || reference.vcv || '参考记录'}：${shown(reference.independentEvidenceStatus || reference.independence?.status)}；${reference.independentEvidenceReason || reference.independence?.dependencyReview || '未取得独立性核验说明，不能仅凭分类标签判断满足'}`,
                          )
                          .join('；')
                    : '未核实',
            ],
            ...(references.some((reference) => reference.qualification)
                ? [['参考原始证据核验', references.filter((reference) => reference.qualification).map((reference) => {
                    const review = reference.qualification;
                    const evidence = Array.isArray(review.evidence) ? review.evidence : [];
                    return `${reference.scv || '参考记录'}：审核记录 ${shown(review.reviewId)} / ${shown(review.status)}；规范 ${shown(review.specificationVersion)}；原 ClinVar 标签 ${shown(reference.sourceAssertion?.reviewStatus)}；依据 ${evidence.map((item) => `${shown(item.code)} ${shown(item.strength)}（${shown(item.sourceLocator)}）`).join('；') || '未取得'}`;
                }).join('；')]]
                : []),
            ...(references.some((reference) => reference.qualification?.schemaVersion === 'missense-independent-reference-v2')
                ? [['参考分类分层', references.filter((reference) => reference.qualification?.schemaVersion === 'missense-independent-reference-v2').map((reference) =>
                    `${reference.scv || '参考记录'}：原始分类 ${shown(reference.classification)}；本次独立重核采用 ${shown(reference.adoptedClassification || reference.qualification.adoptedClassification)}；原始断言保留，比较强度按采用分类计算。`,
                ).join('；')]] : []),
            ...(references.some((reference) => reference.expertEvidence)
                ? [['专家资料来源与缺项', references.filter((reference) => reference.expertEvidence).map((reference) => {
                    const record = reference.expertEvidence.record || {};
                    if (reference.expertClassificationAdoption === 'PRESERVE_SOURCE_VERIFIED_EXPERT_CLASSIFICATION') {
                        return `${reference.scv || '参考记录'}：保留原专家分类 ${shown(reference.classification)}；已自动核对完整最终条款、无 PS1/PM5 比较依赖及原始版本绑定。未按新版重新分类；原始资料和原缺项记录完整保留。`;
                    }
                    return `${reference.scv || '参考记录'}：原文资料 ${shown(record.reviewStatus)}，仅作来源导航；原比较依据 ${(record.appliedComparisonCriteria || []).join('、') || '资料未列出，不能推断没有依赖'}；缺项 ${(record.gapReasons || []).join('、') || '仍须具体审核'}；不自动授予独立性、疾病或 RNA 资格。`;
                }).join('；')]] : []),
            ...(value?.classificationConstraint
                ? [['最终分类上限', `采用 ${shown(value.classificationConstraint.referenceClassification)} 参考时，目标不得超过 ${shown(value.classificationConstraint.maximumTargetClassification)}；尚无最终分类器，本次已阻断而非放行后警告。`]] : []),
            ...(references.some((reference) => reference.targetGrantham != null)
                ? [
                      [
                          'Grantham 距离',
                          references
                              .map(
                                  (reference) =>
                                      `${reference.scv || '参考记录'}：野生型→目标 ${shown(reference.targetGrantham)}；野生型→参考 ${shown(reference.referenceGrantham)}`,
                              )
                              .join('；'),
                      ],
                  ]
                : []),
            ...(references.some((reference) => reference.originalSpecificationVersion)
                ? [
                      [
                          '参考原始分类版本',
                          references
                              .map(
                                  (reference) =>
                                      `${reference.scv || '参考记录'}：${shown(reference.classification)}，${shown(reference.evaluationDate)}；专家论据规范 ${shown(reference.originalSpecificationVersion)}；ClinVar 方法标签 ${shown(reference.clinvarAssertionMethod)}`,
                              )
                              .join('；'),
                      ],
                  ]
                : []),
            [
                '剪接核验',
                value?.spliceEvidence?.length
                    ? value.spliceEvidence
                          .map(
                              (evidence) => {
                                  if (value?.rnaApplicability?.mode === 'PREDICTION_AND_REGISTERED_CONCERNS') {
                                      const role = evidence.queryId === 'TARGET' ? '目标'
                                          : references.some((reference) => reference.referenceId === evidence.queryId) ? '参考' : '身份未匹配';
                                      return `${role}：SpliceAI_RAW · ${shown(value.rawSpliceReceipt?.status)}${evidence.maximumScore == null ? '' : ` · 最大分数 ${evidence.maximumScore}`}`;
                                  }
                                  return `${{ TARGET: '目标', REFERENCE: '参考' }[evidence.role] || shown(evidence.role)}：${shown(evidence.method)} · ${shown(evidence.status)}${evidence.maximumScore == null ? '' : ` · 最大分数 ${evidence.maximumScore}`}`;
                              },
                          )
                          .join('；')
                    : '未取得目标与参考的剪接核验结果',
            ],
            [
                'RNA 文献核验',
                value?.rnaApplicability?.mode === 'PREDICTION_AND_REGISTERED_CONCERNS'
                    ? '按明确政策自动检查目标和参考的原生剪接预测，并查询已登记的来源疑点；无需逐配对人工签署。来源检索有限，RNA 影响仍未知，不代表实验正常。'
                    : references.some((reference) => reference.rnaReview)
                    ? references
                          .filter((reference) => reference.rnaReview)
                          .map(({ scv, rnaReview: review }) => {
                              const reviewed =
                                  review.status === 'REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED' &&
                                  review.rnaEffect === 'UNKNOWN' &&
                                  review.normalRnaClaimed === false;
                              return `${scv || '参考记录'}：${reviewed ? '有限来源检索未发现矛盾报告；RNA 影响仍未知，不代表 RNA 正常' : `核验尚不充分；${shown(review.status)} / ${shown(review.rnaEffect)}`}${review.reviewedOn ? `（${review.reviewedOn}）` : ''}`;
                          })
                          .join('；')
                    : '本结果没有新增 RNA 文献核验；剪接预测不证明 RNA 正常。',
            ],
            ...((value?.rnaReviewEvidence || []).some((review) => review.reviewStatus)
                ? [
                      [
                          '公开剪接疑点',
                          value.rnaReviewEvidence
                              .filter((review) => review.reviewStatus)
                              .map(
                                  (review) =>
                                      `${shown(review.reviewStatus)}：${review.description || '需复核公开来源'}`,
                              )
                              .join('；'),
                      ],
                  ]
                : []),
            [
                '证据依赖',
                'PS1 与 PM5 分别展示，不自动叠加强度；实际强度和依赖以本次规范及评价为准。REVEL 和 AlphaMissense 分数不证明参考变异独立致病；参考来源分类不是本系统对目标的最终分类。',
            ],
        ],
        links,
        raw: value,
    };
}

function boundBa1ScopeRefusal(evaluation, selection) {
    if (selection?.status !== 'BLOCKED' || evaluation?.schemaVersion !== 'ba1-allele-evaluation-v1'
        || evaluation.criterionId !== 'ACMG_AMP_2015:BA1' || evaluation.scope !== selection.scope
        || evaluation.evaluationResult?.status !== 'NOT_EVALUATED' || evaluation.appliedStrength !== 'NONE'
        || !selection.reasonCode || evaluation.evaluationResult.reasonCode !== selection.reasonCode) return false;
    const { selectionId, ...identity } = selection;
    if (evaluation.ruleBinding?.ba1SelectionId !== selectionId
        || !/^ba1-(?:local-)?selection-sha256-[a-f0-9]{64}$/.test(selectionId)) return false;
    try {
        return selectionId.endsWith(ps2IdentityHash(identity));
    } catch {
        return false;
    }
}

export function ba1Details(ba1) {
    const evaluation = ba1?.evaluation;
    const parameters = evaluation?.deterministicTrace?.parameters || [];
    const parameter = (name) => parameters.find((p) => p.name === name)?.value;
    const evidence = ba1?.evidence;
    const measurements = evidence?.measurements || [];
    const measurement = (id) => measurements.find((m) => m.metricId === id)?.rawValue;
    const selectedPolicy = ba1?.generalization?.effectivePolicy || ba1?.selection?.policy || ba1?.policy;
    const frequencyRule = selectedPolicy?.frequencyRule || selectedPolicy;
    const dataScope = ba1?.dataScope || evaluation?.dataScope;
    const localExomes = dataScope?.policyId === 'BA1_LOCAL_EXOMES_V1' && dataScope?.version === '1.0.0' && dataScope?.dataset === 'EXOMES';
    const prefix = localExomes ? 'gnomad.exomes' : 'gnomad.joint';
    const ancestry =
        parameter('frequency.ancestry') || measurement(`${prefix}.faf95max.ancestry`);
    return {
        evaluation: evaluation || ba1,
        frequency: parameter('frequency.raw') ?? measurement(frequencyRule?.metricId || `${prefix}.faf95max`),
        metric: parameter('frequency.metricId') || frequencyRule?.metricId || (ba1 ? `${prefix}.faf95max` : undefined),
        ancestry,
        localExomes,
        an:
            parameter('frequency.selectedAncestryAn') ||
            measurement(`${prefix}.ancestry.${ancestry}.an`),
        filter: measurement(`${prefix}.filter`),
        qc: parameter('frequency.discordanceQc'),
        ac: measurement(`${prefix}.ancestry.${ancestry}.ac`),
        af: measurement(`${prefix}.ancestry.${ancestry}.af`),
        exomesFilter: measurement('gnomad.joint.exomes.filtersPresence'),
        genomesFilter: measurement('gnomad.joint.genomes.filtersPresence'),
        operator: parameter('comparison.operator') || frequencyRule?.comparisonOperator,
        threshold: parameter('threshold.raw') || frequencyRule?.threshold?.rawValue,
        minimumAn:
            parameter('frequency.minimumSelectedAncestryAn') ||
            frequencyRule?.minimumSelectedAncestryAlleleNumber?.rawValue,
        minimumAnOperator: frequencyRule?.minimumSelectedAncestryAlleleNumber?.comparisonOperator,
        exception: parameter('ba1.exceptionOutcome'),
        source: evidence?.source,
        readiness: [...new Set([
            ...(Array.isArray(ba1?.recordInputReadinessReasonCodes) ? ba1.recordInputReadinessReasonCodes : []),
            ...(measurement('gnomad.recordInputReadinessReasonCodes') || '').split(/[|,;]/),
        ].filter((reason) => typeof reason === 'string' && reason.trim()).map((reason) => reason.trim()))],
    };
}

// Saved preparation facts are explanatory only; this module never selects model values.
const bs1SupplyFields = ['prevalence', 'penetrance', 'maximumGeneContribution', 'maximumAlleleContribution'];
const bs1SupplyLabels = ['患病率', '外显率', '基因最大贡献', '等位基因最大贡献'];
const bs1SupplyStates = {
    CANDIDATE_AVAILABLE: '已有数值候选，尚未核定采用', REVIEW_REQUIRED: '已有资料，适用范围或含义待核',
    IDENTITY_UNRESOLVED: '资料与疾病身份尚未精确对应', CONFLICT: '候选资料冲突，尚未协调',
    SOFTWARE_UNSUPPORTED: '已有资料，当前程序尚不支持其表达或统计类型',
    INVALID_SOURCE_DATA: '源数据有无效字段，不能采用', SOURCE_GAP: '本次固定来源中未取得合适参数',
};
const bs1SupplyReasons = {
    EXACT_EPIDEMIOLOGY_MAPPING_MISSING: '缺少疾病与流行病学资料的精确映射',
    DISEASE_MAPPING_AMBIGUOUS: '存在多个疾病映射，尚未确定适用范围',
    SUBMITTED_DISEASE_IDENTIFIER_NOT_VERIFIED: '提交的疾病映射未经核定',
    SPECIFICATION_MOI_SCOPE_UNRESOLVED: '原规范的遗传方式尚未确定',
    MULTIPLE_ESTIMATES_REQUIRE_RECONCILIATION: '同范围存在多个估计值',
    MULTIPLE_TEXT_VALUES_REQUIRE_RECONCILIATION: '原文出现多个数值候选，需核对其范围和差异',
    POPULATION_OR_STATISTIC_SELECTION_REQUIRED: '需核定适用人群和统计口径',
    CURRENT_MODEL_RANGE_UNSUPPORTED: '当前模型不支持区间参数',
    CURRENT_MODEL_STATISTIC_UNSUPPORTED: '当前模型不支持此统计类型',
    RANGE_REQUIRES_MODEL_AND_SCOPE_REVIEW: '区间的模型含义和适用范围待核',
    BOUND_REQUIRES_MODEL_AND_SCOPE_REVIEW: '上下界的模型含义和适用范围待核',
    APPROXIMATE_VALUE_NOT_EXACT: '原文是近似值，不能当作精确点值',
    TEXT_MENTION_REQUIRES_EXTRACTION_AND_SCOPE_REVIEW: '文字线索尚未完成含义与范围核定',
    FULL_CLAUSE_AND_EXPERT_PRIORITY: '需遵循完整条款及专家规范优先关系',
    PARAMETER_DEFINITIONS_AND_DENOMINATORS: '需核对参数定义及分母',
    POPULATION_AGE_AND_STATISTIC: '需核对人群、年龄及统计口径',
    MODEL_STRENGTH_AND_EXCEPTION_SCOPE: '需核定模型强度和例外范围',
};
const bs1SupplyScopeMatches = (a, b) => ['hgncId', 'mondoId', 'moi'].every((k) => typeof a?.[k] === 'string' && a[k] === b?.[k]);

export function bs1ParameterSupplyDetails(gap, expectedScope) {
    if (!gap) return [];
    const supply = gap.parameterSupply;
    if (!supply) return [['参数资料明细', '这份保存快照没有逐字段摘要；不代表资料不存在，也不使用当前知识补写历史。']];
    const valid = supply.schemaVersion === 'bs1-parameter-supply-v1' && supply.modelExecutable === false && supply.scientificallyAdopted === false &&
        supply.sourceScope === 'ONLY_PINNED_LOADED_INPUTS' && bs1SupplyScopeMatches(supply.scope, expectedScope) &&
        gap.knowledgeIdentity?.parameterSupplyVersion === supply.schemaVersion && /^[a-f0-9]{64}$/.test(gap.knowledgeIdentity?.rawSha256 || '') &&
        bs1SupplyFields.every((name) => {
            const f = supply.fields?.[name];
            return f && Object.hasOwn(bs1SupplyStates, f.status) && Array.isArray(f.reasonCodes) && f.reasonCodes.every((r) => typeof r === 'string') &&
                Array.isArray(f.evidence) && f.evidence.length <= 3 && Number.isSafeInteger(f.evidenceCount) &&
                Number.isSafeInteger(f.omittedEvidenceCount) && f.omittedEvidenceCount >= 0 && f.evidenceCount === f.evidence.length + f.omittedEvidenceCount &&
                f.evidence.every((e) => typeof e.locator === 'string' && typeof e.valueText === 'string' && typeof e.valueKind === 'string' &&
                    typeof e.source?.sourceId === 'string' && typeof e.source?.version === 'string' && /^https:\/\//.test(e.source?.url || '') &&
                    /^[a-f0-9]{64}$/.test(e.source?.sha256 || ''));
        });
    if (!valid) return [['参数资料明细', '摘要格式或保存背景关联不一致，未使用该摘要；原始记录保留。']];
    const rows = [['资料快照', `${gap.knowledgeIdentity.version} · ${gap.knowledgeIdentity.rawSha256}`],
        ['资料边界', '仅反映这次固定来源；候选参数未采用，不用于计算阈值，也不表示所有文献均无资料。']];
    bs1SupplyFields.forEach((name, index) => {
        const f = supply.fields[name];
        rows.push([`${bs1SupplyLabels[index]}资料`, bs1SupplyStates[f.status]]);
        if (f.reasonCodes.length) rows.push([`${bs1SupplyLabels[index]}待办`, f.reasonCodes.map((r) => bs1SupplyReasons[r] || r).join('；')]);
        f.evidence.forEach((e, i) => rows.push([`${bs1SupplyLabels[index]}来源 ${i + 1}`,
            `${e.source.sourceId} @ ${e.source.version} · ${e.locator}；${e.valueKind}${e.valueText ? `：${e.valueText}` : ''}${e.population ? `；人群：${e.population}` : ''}${e.statistic ? `；统计：${e.statistic}` : ''}；候选未采用`]));
        if (f.omittedEvidenceCount) rows.push([`${bs1SupplyLabels[index]}其他资料`, `另有 ${f.omittedEvidenceCount} 项保留在同版准备材料中；此处只列前 ${f.evidence.length} 项。`]);
    });
    return rows;
}

export function bs1SavedSupplyDetails(attempt, context) {
    const gap = attempt?.bs1?.preparation?.knowledgeGap;
    if (gap) return bs1ParameterSupplyDetails(gap, { hgncId: context?.gene?.hgncId, mondoId: context?.disease?.mondoId, moi: context?.moi });
    // No confirmed context: describe each unavailable option separately, never borrow a disease conclusion.
    if (context?.contextId) return [];
    const options = attempt?.bs1ContextPreparation?.policyOptions;
    if (options != null && !Array.isArray(options)) return [['参数资料明细', '保存的候选背景列表格式不正确，未使用该摘要；原始记录保留。']];
    return (options || []).flatMap((option) => {
        const saved = option?.knowledgeGap;
        if (!saved || option.available !== false) return [];
        const scope = saved.background;
        return [['资料候选背景（未确认）', [scope?.hgncId, scope?.mondoId, scope?.moi].filter(Boolean).join(' · ') || '这份知识快照未提供精确背景身份'],
            ...bs1ParameterSupplyDetails(saved, scope)];
    });
}

// A display-only adapter; persisted evidence and provenance are never mutated.
export function bs1NegativeSummary(value) {
    if (value?.evaluation?.evaluationResult?.status !== 'NOT_MET' || value.qualityDecision?.status !== 'READY' ||
        value.evaluation?.deterministicTrace?.executionStatus !== 'COMPLETED') return undefined;
    const trace = value.evaluation.deterministicTrace;
    const mode = value.preparation?.selectedMode;
    if (!mode || !trace.parameters?.some(p => p.name === 'selectedMode' && p.value === mode)) return undefined;
    const tiers = (value.preparation?.policy?.tiers || []).filter(t => t.moi === mode).slice().sort((a, b) => a.priority - b.priority);
    const comparisons = [];
    for (const step of trace.steps || []) {
        const match = /^BS1_TIER_(\d+)_CONDITION_(\d+)$/.exec(step.stepCode);
        if (!match || step.outcome !== 'FALSE') continue;
        const tier = tiers[Number(match[1]) - 1], c = tier?.conditions?.[Number(match[2]) - 1];
        const actual = c && value.qualityDecision.facts?.[c.fact];
        // Show saved decimals and saved FALSE, never round or recalculate a scientific comparison.
        const decimal = x => typeof x === 'string' && /^\d+(?:\.\d+)?$/.test(x);
        const operators = { GT: '>', GTE: '≥', LT: '<', LTE: '≤', EQ: '=' };
        if (!c || !['FAF95', 'AF', 'AC', 'AN'].includes(c.fact) || !operators[c.operator] || !decimal(actual) || !decimal(c.decimal) ||
            !trace.parameters?.some(p => p.name === `tier.${tier.tierId}.${c.fact}.${c.operator}` && p.value === c.decimal)) continue;
        comparisons.push(`${c.fact}=${actual}，未满足 ${c.operator === 'GTE' ? '≥' : operators[c.operator]} ${c.decimal}`);
    }
    return comparisons.length ? `已完成 BS1 评价：${comparisons.join('；')}（按保存的条件核验结果）；不满足 BS1 不等于已经判定致病。` : undefined;
}

function bs1Entry(attempt, candidate, run) {
    const value = attempt?.bs1;
    const context = value?.context || attempt?.bs1Context;
    const evaluation = value?.evaluation;
    const measurements = Array.isArray(value?.evidence?.measurements) ? value.evidence.measurements : [];
    const hasLocalFacts = measurements.some((m) => m.metricId?.startsWith('gnomad.exomes.'));
    const localResult = value?.dataScope?.policyId === 'BS1_LOCAL_EXOMES_V1';
    const completed = ['MET', 'NOT_MET'].includes(evaluation?.evaluationResult?.status);
    const statistic = value?.preparation?.policy?.statistic;
    const targetAf = statistic?.metricId === 'gnomad.exomes.eligible_ancestry_max_af';
    const frequencySelection = value?.qualityDecision?.frequencySelection;
    const afShapeValid = !targetAf || (value?.qualityDecision?.adapterVersion === 'bs1-target-af-v1' &&
        frequencySelection?.version === 'bs1-target-af-v1' && value?.qualityDecision?.status === 'READY' &&
        evaluation?.deterministicTrace?.evaluatorVersion === 'bs1-target-af-v1' &&
        value?.preparation?.policy?.knowledgeRecord?.qualification?.schemaVersion === 'bs1-knowledge-qualification-v1' &&
        ['metricId', 'unit', 'confidence', 'sourceTerm', 'interpretation', 'interpretationBasis'].every((k) => statistic[k] === frequencySelection.statistic?.[k]) &&
        statistic.confidence === 'POINT_ESTIMATE' && statistic.unit === 'fraction' &&
        ((statistic.sourceTerm === 'AF' && statistic.interpretation === 'TARGET_ALT_AF') ||
            (statistic.sourceTerm === 'MAF' && statistic.interpretation === 'TARGET_ALT_AF_NOT_ABOVE_HALF')) &&
        JSON.stringify(statistic.ancestries) === JSON.stringify(frequencySelection.statistic?.ancestries) &&
        ['operator', 'decimal'].every((k) => statistic.eligibleAn?.[k] === frequencySelection.statistic?.eligibleAn?.[k]) &&
        frequencySelection.comparisonBasis === 'EXACT_TARGET_AC_OVER_AN' &&
        frequencySelection.selectedAncestry === value?.qualityDecision?.facts?.ancestry &&
        frequencySelection.populations?.some((p) => p.ancestry === frequencySelection.selectedAncestry && p.status === 'ELIGIBLE'));
    const stratified = populationStrataClaim(value);
    const runtimeBinding = run?.executionProfile?.bs1RuntimeBinding || run?.executionProfile?.baseExecutionProfile?.bs1RuntimeBinding;
    const routed = value && Object.hasOwn(value, 'sourceRouting');
    const routingInvalid = completed && routed && !populationSourceRoutingValid(value, runtimeBinding);
    const localInvalid = routingInvalid || completed && (stratified ? !populationStrataResultValid(value, context, runtimeBinding) :
        measurements.some(m => m.metricId?.startsWith('gnomad.joint.')) || (hasLocalFacts || localResult) && (!hasLocalFacts || !localResult ||
        value.dataScope?.version !== '1.0.0' || value.dataScope?.dataset !== 'EXOMES' || value.dataScope?.release !== '4.1' ||
        (!targetAf && statistic?.metricId !== 'gnomad.exomes.faf95max') || !afShapeValid ||
        value.qualityDecision?.contract?.contractId !== 'bs1-local-exomes-quality-v1' ||
        value.evidence?.measurements?.some((m) => m.metricId?.startsWith('gnomad.joint.')) ||
        ['assembly', 'contig', 'position', 'reference', 'alternate'].some((k) => value.observation?.queryAllele?.[k] !== context?.variant?.evidenceQueryAllele?.[k] ||
            value.observation?.carrier?.queryAllele?.[k] !== context?.variant?.evidenceQueryAllele?.[k])));
    // Early blocked snapshots serialized JSON null with asText(); accept only that no-context negative shape.
    const legacyMissingHypothesis = attempt?.hypothesisId == null && value?.hypothesisId === 'null' &&
        !context?.contextId && !evaluation?.contextId && evaluation?.evaluationResult?.status === 'NOT_EVALUATED';
    const invalid = localInvalid || !value || value.candidateId !== candidate.candidateId ||
        (!legacyMissingHypothesis && value.hypothesisId !== attempt?.hypothesisId) ||
        (attempt?.bs1Context?.contextId && !ps2Same(context, attempt.bs1Context)) ||
        (['MET', 'NOT_MET'].includes(evaluation?.evaluationResult?.status) && (!context?.contextId || !evaluation?.contextId || !value.evidence?.contextId)) ||
        (evaluation?.contextId && (evaluation.contextId !== context?.contextId || value.evidence?.contextId !== context?.contextId));
    if (invalid && (stratified || routed)) return { displayIssue: true, hypothesisId: attempt?.hypothesisId,
        summary: '保存的性染色体分层统计、质量、政策或背景对应未通过展示校验；保留原结果，不将其改判为未满足或病例资料不足。',
        reasons: [], details: [['保存数据校验', '核对原始记录、数据集、同祖源×核型、AF/FAF口径、来源执行及所选背景；未采用不一致的分值。'], ...populationRoutingDetails(value)], raw: value };
    if (invalid) return { evaluation: { evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'BS1_SAVED_RESULT_ASSOCIATION_INVALID' } },
        reasons: [], details: [['保存关联', '所选背景与候选或证据身份不一致；不使用其他背景结果补位。']] };
    const shown = (v) => v ?? '未取得';
    const quality = value.qualityDecision;
    const policy = value.preparation?.policy;
    const localExomes = value.dataScope?.policyId === 'BS1_LOCAL_EXOMES_V1' &&
        value.dataScope?.version === '1.0.0' && value.dataScope?.dataset === 'EXOMES';
    const dataset = localExomes ? 'exomes' : 'joint';
    const ancestry = quality?.facts?.ancestry;
    const measure = (id) => value.evidence?.measurements?.find((m) => m.metricId === id)?.rawValue;
    return { evaluation, hypothesisId: attempt.hypothesisId, scopeLabel: '已保存背景 BS1', savedPopulationResult: value,
        summary: bs1NegativeSummary(value) || reasonExplanation(evaluation, value.explanation)?.summary,
        ...(evaluation?.evaluationResult?.reasonCode === 'BINDING_NOT_READY' ? {
            summary: localEvidenceExplanation(evaluation.evaluationResult, 'BS1', context?.contextId ? [] : attempt.blockers || []),
        } : {}),
        contextLabel: [context?.gene?.symbol, context?.gene?.hgncId, context?.disease?.mondoId, context?.moi].filter(Boolean).join(' · '),
        reasons: quality?.issues?.length ? quality.issues : evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [
            ...(!stratified ? populationRoutingDetails(value) : []),
            ...populationStrataDetails(value),
            ...(localExomes ? [
                ['数据范围', '本地 gnomAD exomes 4.1；未包含 genomes 或 joint 联合统计'],
                ['适用边界', '按本地适配策略应用所列疾病阈值；不代表联合数据建议或临床采用已完成'],
                [stratified ? '统计限定' : '缺口', stratified ? '仅按已核祖源 × 来源遗传核型分层；未包含 genomes 或 JOINT，未推断患者倍性。' : '未评价外显子／基因组分歧；GN005 的 ASJ 扩展未实现'],
                ['事实复用', value.observationReusedFromBa1 ? '复用同变异、同来源的人口事实；BS1 独立按当前背景评价' : '见保存的来源记录'],
                ['最低样本量', stratified ? '各声明祖源 × 核型的等位基因 AN 按政策筛选，最低不低于2000；不是患者数。' : '所选祖源 AN ≥ 2000（本地工程保护条件）'],
            ] : []),
            ['背景核验', context?.contextCompleteness?.status === 'COMPLETE' ? '本次保存背景完整；来源与政策另行核验' : '本次背景未完整形成'],
            ['转录本要求', value.preparation?.transcriptRequirement || '按政策及来源核验，不借用 PM2 条件'],
            ['转录本', context?.transcript ? `${context.transcript.accession}.${context.transcript.version}` : '本次未提供；仅政策真实要求时构成缺项'],
            ['适用政策', policy ? `${policy.policyId} @ ${policy.version}` : '未取得可执行政策'],
            ['政策身份', value.preparation?.policySha256],
            ...bs1SavedSupplyDetails(attempt, context),
            ...explanationDetails(evaluation, value.explanation),
            ...(policy?.knowledgeDecision?.basis ? [
                ['阈值来源', policy.knowledgeDecision.basis === 'MAX_CREDIBLE_AF' ? '通用疾病模型；本地采用的计算阈值，非官方专家专规' : '结构化专家规范；保留具体适用条件'],
                ['知识版本', policy.knowledgeRecord?.version],
                ['知识采用范围', '用于所列背景和祖源的本地工程评价；不代表临床批准'],
            ] : []),
            ...(policy?.knowledgeDecision?.calculation ? [
                ['计算模型', policy.knowledgeDecision.calculation.formula],
                ['模型参数（比例）', ['prevalence', 'penetrance', 'maximumGeneContribution', 'maximumAlleleContribution']
                    .map((key, i) => `${['患病率', '外显率', '基因最大贡献', '等位基因最大贡献'][i]}=${shown(policy.knowledgeDecision.calculation.inputs?.[key]?.value)}`).join('；')],
                ['参数适用人群', policy.knowledgeDecision.calculation.inputs?.population],
            ] : []),
            ['阈值与比较', policy?.tiers?.filter((tier) => !value.preparation?.selectedMode || tier.moi === value.preparation.selectedMode).map((tier) => `${tier.strength}: ${tier.conditions.map((condition) => `${condition.fact} ${condition.operator} ${condition.decimal}`).join('；')}`).join(' / ')],
            ...(!stratified && targetAf ? [
                ['普通 AF（原记录）', quality?.facts?.AF], ['AF 所选祖源', ancestry],
                ['精确比较依据', '同祖源 AC / AN；阈值比较不使用已舍入的小数'],
                ['来源频率术语', statistic.sourceTerm], ['术语解释', statistic.interpretationBasis],
                ['人群筛选', `AN ${statistic.eligibleAn?.operator} ${statistic.eligibleAn?.decimal}；只在声明范围内比较`],
                ['人群检查', frequencySelection?.populations?.map((p) => `${p.ancestry}: ${p.status}${p.reasonCode ? ` (${p.reasonCode})` : ''}`).join('；')],
            ] : !stratified ? [['FAF95', quality?.facts?.FAF95], ['FAF 所选祖源', ancestry]] : []),
            [stratified ? '同分层 AN / AC' : '同祖源 AN / AC', `${shown(quality?.facts?.AN)} / ${shown(quality?.facts?.AC)}`],
            ...(!targetAf && !stratified ? [['同祖源普通 AF（辅助）', measure(`gnomad.${dataset}.ancestry.${ancestry}.af`)]] : []),
            ['质量状态', quality?.status], [localExomes ? 'exomes 原生 FILTER' : 'joint FILTER', localExomes ? quality?.filterCondition?.observedFilter : quality?.filterCondition?.observedJointFilter],
            ['FILTER 条件依据', quality?.filterCondition?.basis],
            ...(!localExomes ? [
            ['发布 header 来源', quality?.contract?.releaseSemantics?.sourceLocator],
            ['header 摘要范围', quality?.contract?.releaseSemantics?.headerSha256 ? '仅绑定 header，不是整个 gnomAD 数据集摘要' : '历史记录未保存'],
            ['分歧保护性质', quality?.contract?.engineering?.kind || '历史记录未保存'],
            ['exomes / genomes 原始状态', `${shown(quality?.rawFieldStates?.exomes_filters?.state)} / ${shown(quality?.rawFieldStates?.genomes_filters?.state)}`],
            ['质量语义', '经绑定的 joint 发布定义满足 FILTER 条件，不等于两个 subtype 都有记录且 PASS；缺项和冲突仍按原政策处理。'],
            ] : [['质量语义', stratified ? '本地 EXOMES 原生 PASS；同祖源 × 来源核型核对，普通 AF 精确使用 AC/AN，FAF95 单独使用同分层下界；两者不混用，无记录不当作零。' : targetAf ? '本地 exomes 原生 PASS；逐声明人群核对 AN、AC、AF，先筛样本量再选最大目标 AF；不借用 FAF95。' : '只接受本地 exomes 原生 PASS，并核对同祖源 FAF95、AC、AN、AF；无记录不当作零频率。']]),
            ['查询结果', value.evidence?.queryOutcome?.status],
            ['原始 / 查询 assembly', `${shown(context?.variant?.inputAllele?.assembly)} / ${shown(context?.variant?.evidenceQueryAllele?.assembly)}`],
            ['观测来源', value.evidence?.source?.sourceId], ['观测时间', value.observation?.observedAt],
            ['观测身份', value.observation?.observationId], ['质量合同', quality?.contract ? `${quality.contract.contractId} @ ${quality.contract.version}` : '未取得'],
            ['证据关系', 'BA1、PM2、BS1 可能共用人口事实，不能相加为三份独立证据；不生成最终分类或自动采用。'],
        ], links: (policy?.sources || []).filter((source) => /^https:\/\//.test(source.url || '')).map((source) => ({ label: source.sourceId, url: source.url })), raw: value };
}

function pm1RegionEntry(attempt) {
    const value = attempt.pm1, input = value?.resolvedInput, policy = value?.policy;
    const evaluation = value?.evaluation || value;
    const shown = (v) => v == null || v === '' ? '未记录' : typeof v === 'object' ? JSON.stringify(v) : String(v);
    const region = input?.selectedRule;
    const sources = policy?.scientificSources;
    const reason = evaluation?.evaluationResult?.reasonCode;
    return {
        evaluation, hypothesisId: hypothesisIdOf(attempt), reasons: reason ? [reason] : [],
        links: (sources || []).filter((s) => typeof s.url === 'string' && /^https:\/\/[^\s]+$/.test(s.url))
            .map((s) => ({ label: `${s.sourceId} @ ${s.version}`, url: s.url })),
        details: [
            ['评价边界', '通用 PM1 受审区域限定工程；确认背景不是科学批准，TEST_ONLY 声明不是真实科学审核，不生成最终分类。'],
            ['基因 / 疾病 / 遗传方式', `${shown(value?.context?.gene?.hgncId)} / ${shown(value?.context?.disease?.mondoId)} / ${shown(value?.context?.moi)}`],
            ['精确转录本 / 蛋白', `${shown(value?.context?.transcript)} / ${shown(policy?.protein)}`],
            ['蛋白改变', shown(input?.hgvsp || value?.storedAnnotation?.hgvsProtein)],
            ['独立解析位置 / 替换', `${shown(input?.proteinPosition)} / ${shown(input?.proteinVariantKey)}`],
            ['编码与密码子', input ? `c.${shown(input.cdsPosition)}；${shown(input.refCodon)} → ${shown(input.altCodon)}` : '未记录'],
            ['命中区域或残基', region ? `${shown(region.ruleId)}；${shown(region.start)}–${shown(region.end)}；${shown(region.regionType)}；参考 AA ${shown(region.referenceAminoAcid)}` : '未记录'],
            ['支持 / 明确排除', shown(region?.effect)],
            ['区域资格 / 致病分布 / 良性分布', region ? `${shown(region.qualification)} / ${shown(region.pathogenicDistribution)} / ${shown(region.benignDistribution)}` : '未记录'],
            ['全部命中与审核依据', shown(input?.matchedRules)],
            ['已审核覆盖范围', shown(value?.regionMaterial?.reviewedCoverage)],
            ['受审优先关系', shown(input?.reviewedPrecedence)],
            ['冲突 / 审核状态', `${shown(evaluation?.conflict)} / ${shown(evaluation?.review)}`],
            ['政策版本', policy ? `${shown(policy.policyId)} @ ${shown(policy.policyVersion)}` : '未记录'],
            ['区域材料身份', shown(value?.regionMaterialBinding)],
            ['完整模型身份', shown(value?.transcriptModelBinding)],
            ['材料来源版本与定位', shown(sources)],
        ], raw: value,
    };
}

export function evidenceCards(candidate, criteria, ba1View, bs1View, run) {
    if (localBatchScopeClaim(run)) {
        if (!localBatchSavedRun(run)) return [];
        if (!unifiedLocalScope(run)) return localBatchCards(candidate, criteria, run.executionProfile, criteria, run);
        const literature = !!run.executionProfile?.criterionSelection || pp1LiteratureScope(run) || pm3LiteratureScope(run) || deNovoLocalScope(run);
        const expected = batchCriteriaFor(run);
        const cards = [...ordinaryEvidenceCards(candidate, UNIFIED_CRITERIA.slice(0, 2), ba1View, bs1View, run),
            ...(literature ? localBatchCards(candidate, expected.filter(id => !['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1'].includes(id)), run.executionProfile, expected, run)
                : localBatchCards(candidate, publishedLocalScope(run) ? PUBLISHED_BATCH_CRITERIA : extendedLocalScope(run) ? EXTENDED_BATCH_CRITERIA : BP7_BATCH_CRITERIA, run.executionProfile, undefined, run))];
        return criteria.map((id) => cards.find((card) => card.id === id)).filter(Boolean);
    }
    return ordinaryEvidenceCards(candidate, criteria, ba1View, bs1View, run);
}

function ordinaryEvidenceCards(candidate, criteria, ba1View, bs1View, run) {
    const shown = (v) => v ?? '未取得';
    const parameter = (evaluation, name) =>
        evaluation?.deterministicTrace?.parameters?.find((p) => p.name === name)?.value;
    const frequencyEntry = (attempt, id) => {
        const generalized = attempt.populationFrequency?.clauseRouting?.[id] === 'CURRENT_CATALOG_RESULT';
        const value = generalized
            ? attempt.populationFrequency?.ruleResults?.find((result) =>
                  result.criterionEvaluations?.some((evaluation) => evaluation.criterionId === id),
              )
            : attempt.hnf1aFrequency;
        const evaluation = value?.criterionEvaluations?.find((e) => e.criterionId === id);
        if (evaluation?.evaluationResult?.reasonCode === 'BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED') {
            return { evaluation, hypothesisId: hypothesisIdOf(attempt), scopeLabel: id.split(':').pop(),
                contextLabel: [attempt.context?.gene?.hgncId, attempt.context?.disease?.mondoId, attempt.context?.moi].filter(Boolean).join(' · '),
                reasons: [], details: [['确认用途', '仅 BA1 疾病背景'],
                    ['本规则状态', '尚未获得本规则所需确认；未查询或计证，不借用其他疾病的规范。']], raw: value };
        }
        const measurement = (name) => value?.evidence?.measurements?.find((m) => m.metricId === name)?.rawValue;
        const ancestry = measurement('gnomad.joint.faf95max.ancestry');
        const rule = value?.policy?.officialConditions?.[id.split(':').pop()];
        return {
            evaluation,
            hypothesisId: hypothesisIdOf(attempt),
            scopeLabel: id === 'ACMG_AMP_2015:BA1' ? '疾病 BA1' : '疾病 BS1',
            contextLabel: [attempt.context?.gene?.hgncId, attempt.context?.disease?.mondoId,
                attempt.context?.moi, hypothesisIdOf(attempt)].filter(Boolean).join(' · ') || '疾病上下文未确认',
            reasons: !attempt.context ? attempt.blockers || [] : [],
            details: [
                ['疾病作用域', generalized
                    ? [attempt.context?.gene?.symbol, attempt.context?.gene?.hgncId,
                        attempt.context?.disease?.mondoId, attempt.context?.moi].filter(Boolean).join(' · ')
                    : 'HNF1A / HGNC:11621 · 单基因糖尿病 / MONDO:0015967 · 常染色体显性'],
                ['GroupMax FAF95', `${shown(measurement('gnomad.joint.faf95max'))}（比例；95% 置信下界）`],
                ['FAF 祖源 AC / AN', `${shown(ancestry)}：${shown(measurement(`gnomad.joint.ancestry.${ancestry}.ac`))} / ${shown(measurement(`gnomad.joint.ancestry.${ancestry}.an`))}；按最高 FAF 选祖源`],
                ['本次阈值', `${comparison(rule?.operator)} ${shown(rule?.decimal)}（比例）`],
                ['质量检查', `FILTER ${shown(measurement('gnomad.joint.filter'))}；差异 p ${shown(measurement('gnomad.joint.qc.statUnionPValue'))}`],
                ['子类型标记', `外显子 ${shown(measurement('gnomad.joint.exomes.filters'))}；基因组 ${shown(measurement('gnomad.joint.genomes.filters'))}`],
                ['数据来源', shown(value?.evidence?.source?.resourceVersion)],
                ['查询状态', `${shown(value?.evidence?.queryOutcome?.status)}；无记录不等于频率为零或低风险`],
                ['人口查询执行', shown(value?.evidence?.resourceExecution?.status)],
                ['资源可用性 / 适用性', `${shown(value?.evidence?.resourceAvailability?.status)} / ${shown(value?.evidence?.resourceApplicability?.status)}`],
                ['政策', `${shown(value?.policy?.policyId)} @ ${shown(value?.policy?.policyVersion)}${generalized ? '' : '；GN017 v3.1'}`],
                ['规范来源', generalized ? (value?.policy?.sources || []).map((source) => source.url).filter(Boolean).join('；') || '未取得'
                    : 'https://cspec.genome.network/cspec/ui/svi/doc/GN017?version=3.1'],
                ...(generalized ? [
                    ['规范选择', `${shown(value?.policySelection?.outcome)}；${shown(value?.policySelection?.reasonCode || value?.policySelection?.baseRuleRelationship)}`],
                    ['目录版本', `${shown(value?.policySelection?.catalogId)} @ ${shown(value?.policySelection?.catalogVersion)}`],
                    ['队列适用依据', (value?.policy?.engineeringConditions?.ancestryApplicability || []).map((review) => review.basis).join('；') || '未取得'],
                    ['最低 AN 依据', shown(value?.policy?.engineeringConditions?.minimumAnBasis)],
                ] : []),
                ['例外审阅', generalized ? shown(value?.policy?.exceptionReview?.scope)
                    : `${shown(value?.policy?.reviewedAt)}：仅覆盖所列来源，未发现相关例外不代表所有 HNF1A 永无例外`],
                ['证据关系', '疾病 BA1 优先展示；保留 BS1 与通用 BA1 以供回查，同一次人口观察不叠加强度，不生成最终分类。'],
            ],
            raw: value,
        };
    };
    const comparison = (operator) =>
        ({
            GREATER_THAN: '>',
            GREATER_THAN_OR_EQUAL: '≥',
            LESS_THAN: '<',
            LESS_THAN_OR_EQUAL: '≤',
            GE: '≥',
            LE: '≤',
            GTE: '≥',
            LTE: '≤',
            GT: '>',
            LT: '<',
        })[operator] || shown(operator);
    return criteria.map((id) => {
        if (id === 'ACMG_AMP_2015:PP4') return { id, code: 'PP4', entries: (candidate.attempts || []).map((a) => pp4EvidenceEntry(a.pp4)) };
        if (id === 'ACMG_AMP_2015:BS4') return { id, code: 'BS4', entries: (candidate.attempts || []).map((a) => bs4EvidenceEntry(a.bs4)) };
        if (id === 'ACMG_AMP_2015:PP1') return { id, code: 'PP1', entries: (candidate.attempts || []).map((a) => pp1EvidenceEntry(a.pp1)) };
        if (id === 'ACMG_AMP_2015:BP2') return { id, code: 'BP2', entries: (candidate.attempts || []).map((a) => bp2EvidenceEntry(a.bp2)) };
        if (id === 'ACMG_AMP_2015:PM3') return { id, code: 'PM3', entries: (candidate.attempts || []).map((a) => pm3EvidenceEntry(a.pm3)) };
        if (id === 'ACMG_AMP_2015:PM6') return { id, code: 'PM6', entries: (candidate.attempts || []).map((a) => pm6EvidenceEntry(a.pm6)) };
        if (id === 'ACMG_AMP_2015:PS2') return { id, code: 'PS2', entries: (candidate.attempts || []).map((a) => ps2EvidenceEntry(a.ps2)) };
        if (id === 'ACMG_AMP_2015:PM4') return { id, code: 'PM4', entries: (candidate.attempts?.length ? candidate.attempts : [{}]).map((attempt) => {
            const value = attempt.pm4;
            const input = value?.resolvedInput;
            const context = value?.context || attempt.context;
            const evaluation = value?.evaluation || value;
            const reason = evaluation?.evaluationResult?.reasonCode;
            return { evaluation, hypothesisId: hypothesisIdOf(attempt), reasons: reason ? [reason] : attempt.blockers || [],
                contextLabel: [context?.gene?.symbol, context?.disease?.mondoId, context?.moi].filter(Boolean).join(' · '),
                details: [
                    ['评价边界', 'PM4 简单框内 indel 限定工程评价；MET 与计算强度不代表科学采用，采用及最终分类未开放。'],
                    ['适用性', shown(evaluation?.criterionApplicability?.status)],
                    ['疾病 / 遗传方式', `${shown(context?.disease?.mondoId)} / ${shown(context?.moi)}`],
                    ['精确转录本 / 蛋白', `${shown(input?.transcript || value?.storedAnnotation?.transcriptAccession || (context?.transcript?.accession && context?.transcript?.version ? `${context.transcript.accession}.${context.transcript.version}` : undefined))} / ${shown(input?.protein)}`],
                    ['HGVSc / HGVSp', `${shown(input?.hgvsc || value?.storedAnnotation?.hgvsCoding)} / ${shown(input?.hgvsp || value?.storedAnnotation?.hgvsProtein)}`],
                    ['输入 / 分支', `${shown(value?.inputDisposition)} / ${shown(input?.branch)}`],
                    ['参考 / 改变后蛋白长度', `${shown(input?.referenceProteinLength)} / ${shown(input?.alteredProteinLength)}`],
                    ['净氨基酸变化', shown(input?.netAminoAcidChange)],
                    ['净核苷酸变化', shown(input?.netNucleotideChange)],
                    ['CDS 编辑区间', `${shown(input?.coordinateSystem)}：${shown(input?.referenceEditStart0)}–${shown(input?.referenceEditEnd0)}`],
                    ['REF / ALT', `${shown(input?.reference)} / ${shown(input?.alternate)}`],
                    ['序列核对', shown(input?.sequenceDerivationStatus)],
                    ['HGVSc / HGVSp 等价核对', `${shown(input?.hgvscSequenceEquivalent)} / ${shown(input?.hgvspSequenceEquivalent)}`],
                    ['参考 / 改变后终止位置', `${shown(input?.referenceStopCdsPosition)} / ${shown(input?.alteredStopCdsPosition)}`],
                    ['规范选择', value?.policySelection ? JSON.stringify(value.policySelection) : '未取得'],
                    ['模型 / 区域 / 注释 SHA', `${shown(input?.transcriptModelSha256)} / ${shown(input?.regionMaterialSha256)} / ${shown(input?.storedAnnotationSha256)}`],
                    ['参考 / 改变后 CDS SHA', `${shown(input?.referenceCdsSha256)} / ${shown(input?.alteredCdsSha256)}`],
                    ['参考 / 改变后蛋白 SHA', `${shown(input?.referenceProteinSha256)} / ${shown(input?.alteredProteinSha256)}`],
                    ['政策来源', value?.policy?.scientificSources ? JSON.stringify(value.policy.scientificSources) : '未取得'],
                ], raw: value };
        }) };
        if (['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'].includes(id) && candidate.attempts?.some((a) => a.prediction)) {
            const code = id.split(':')[1];
            return { id, code, entries: candidate.attempts.map((attempt) => {
                const result = attempt.prediction;
                const applied = result?.criteria?.[code];
                const mechanisms = result?.mechanisms || {};
                return {
                    evaluation: { criterionId: id, evaluationResult: { status: applied?.status, reasonCode: applied?.reasonCode },
                        appliedStrength: applied?.appliedStrength || 'NONE', criterionApplicability: applied?.criterionApplicability },
                    hypothesisId: attempt.hypothesisId,
                    contextLabel: [result?.plan?.candidateId, result?.plan?.hypothesisId, attempt.context?.disease?.mondoId].filter((value) => value && value !== 'null').join(' · '),
                    reasons: [applied?.reasonCode].filter(Boolean),
                    details: [
                        ['应用原因', reasonHelp(applied?.reasonCode)],
                        ['验证用途', result?.runtimeIdentity?.syntheticGridOnly ? '合成数据网格验证，不是临床结论' : '隔离开发，未正式启用'],
                        ['样本 / 基因型性质', result?.plan?.dataClass === 'SYNTHETIC' ? '合成公开控制，不是患者临床确认' : result?.plan?.dataClass || '历史未记录'],
                        ['分数来源', scoreOriginLabel(result?.observation?.scoreOrigin)],
                        ['所选关联转录本', result?.associationInput?.transcriptId || '本次请求未选择用于关联的转录本'],
                        ['关联注释来源', result?.associationInput ? `${shown(result.associationInput.annotationSource)} @ ${shown(result.associationInput.annotationSourceVersion)} / ${shown(result.associationInput.annotationSourceSha256)}` : '未提交关联选择'],
                        ['父观测运行', result?.plan?.parentReference?.sourceRunId || '直接取得原始观测'],
                        ['关联方式', result?.associationInput?.route || '未执行'],
                        ['新增调用 / 传输', result?.newProviderInvocations !== undefined ? `${result.newProviderInvocations} / ${result.newTransportAttempts}` : '历史候选 providerCalls 为截至该条的累计计数，不相加'],
                        ['原观测历史传输', result?.observation?.transportAttempts ?? '未记录'],
                        ...savedCalibrationDetails(result),
                        ['预选工具 / 主机制', `${shown(result?.plan?.model)} / ${shown(result?.plan?.primaryMechanism)}`],
                        ['原始取分状态', shown(result?.observation?.status)],
                        ['原始分数（逐行，尚不代表已关联）', result?.observation?.rows?.map((row) => `${shown(row.transcriptId)} / ${shown(row.proteinVariant)}: ${shown(row.rawScore)}`).join('；') || '未取得原始行'],
                        ['关联状态 / 原因', `${shown(result?.association?.status)} / ${reasonHelp(result?.association?.reasonCode)}`],
                        ['已关联分数', result?.association?.rawScore ?? '尚未关联'],
                        ['取分原因', reasonHelp(result?.observation?.reasonCode)],
                        ['首个未完成阶段', shown(result?.stages?.firstUnresolvedStage)],
                        ['采用引用覆盖', `${shown(result?.effectRestrictions?.coverage)} / ${shown(result?.effectRestrictions?.restriction)}`],
                        ['采用引用原因', reasonHelp(result?.effectRestrictions?.reasonCode)],
                        ['采用引用范围', '仅本分析、候选及背景的本地已保存复审来源；不表示外部没有实验。'],
                        ['采用快照', shown(result?.effectRestrictions?.sourceSha256)],
                        ['实际应用强度', applied?.appliedStrength || 'NONE'],
                        ['采用机制', shown(applied?.selectedMechanism)],
                        ...Object.entries(mechanisms).flatMap(([name, m]) => [
                            [name === 'SPLICING' ? '剪接机制' : '蛋白机制', `${shown(m.status)} · ${shown(m.direction)} · ${reasonHelp(m.reasonCode)}`],
                            [`${name} ${result?.schemaVersion === 'prediction-rule-result-v3' ? '校准强度' : '来源分档'} / 点值`, `${result?.schemaVersion === 'prediction-rule-result-v3' ? (m.calibratedStrength ?? '未知') : (m.sourceStrength ?? '未取得')} / ${m.sourcePointValue ?? '未分配'}`],
                            [`${name} 校准状态`, (result?.schemaVersion === 'prediction-rule-result-v3' ? m.status : m.calibration?.comparisonSemantics) || '未取得'],
                            [`${name} 校准来源`, (result?.schemaVersion === 'prediction-rule-result-v3' && m.sources ? JSON.stringify(m.sources) : [m.calibration?.source?.doi, m.calibration?.source?.locator].filter(Boolean).join(' · ')) || '未取得'],
                        ]),
                        ['证据关系', '不同机制分别解释；分数不是最终分类，来源点值不相加。未运行其他规则不代表没有相关实验。'],
                    ], raw: result,
                };
            }) };
        }
        const code = id.split(':').pop();
        if (id === 'ACMG_AMP_2015:BP1' && candidate.attempts?.some((a) => a.bp1)) {
            return { id, code, entries: candidate.attempts.filter((a) => a.bp1).map(bp1Entry) };
        }
        if (id === 'ACMG_AMP_2015:PP2' && candidate.attempts?.some((a) => a.pp2)) {
            return { id, code, entries: candidate.attempts.filter((a) => a.pp2).map(pp2Entry) };
        }
        if (id === 'ACMG_AMP_2015:BP3' && candidate.attempts?.some((a) => a.bp3)) {
            return { id, code, entries: candidate.attempts.filter((a) => a.bp3).map(bp3Entry) };
        }
        if (id === 'ACMG_AMP_2015:PM1') {
            const attempts = candidate.attempts?.length ? candidate.attempts : [{}];
            return { id, code, entries: attempts.map((attempt) => {
                const value = attempt.pm1;
                if (value?.bundleIdentity?.schemaVersion?.includes?.('-pm1-') ||
                    value?.policy?.schemaVersion === 'pm1-protein-region-policy-v1') return pm1RegionEntry(attempt);
                const input = value?.resolvedInput;
                const match = input?.matchedPolicy;
                const matchText = match === 'GN017_DIRECT_DNA_BINDING_RESIDUE' ? '直接 DNA 结合残基'
                    : match === 'GN017_TAD_EXCLUDED' ? '281–631 转录激活区域；专用规范明确不予 PM1 支持'
                    : match === 'GN017_POSITION_NOT_SUPPORTED' ? '未命中本次规范残基或区域，不代表良性'
                    : match?.startsWith('GN017_REGION_') ? `关键区域 ${match.slice(13).replace('_', '–')}（含端点）` : '尚未完成位置核验';
                return {
                    evaluation: value?.evaluation || value,
                    hypothesisId: hypothesisIdOf(attempt), reasons: [],
                    links: typeof value?.policy?.authority?.sourceUrl === 'string' && /^https:\/\/[^\s]+$/.test(value.policy.authority.sourceUrl)
                        ? [{ label: 'ClinGen PM1 专用规范', url: value.policy.authority.sourceUrl }] : [],
                    details: [
                        ['蛋白改变', shown(value?.storedAnnotation?.hgvsProtein)],
                        ['蛋白位置 / 参考氨基酸', `${shown(input?.proteinPosition)} / ${shown(input?.refAminoAcid)}`],
                        ['位置匹配', matchText],
                        ['采用强度', shown(value?.evaluation?.appliedStrength)],
                        ['编码与密码子', `c.${shown(input?.cdsPosition)}；${shown(input?.refCodon)} → ${shown(input?.altCodon)}`],
                        ['适用规范', `${shown(value?.policy?.authority?.specificationId)} v${shown(value?.policy?.authority?.authorityVersion)}（HNF1A PM1）`],
                        ['规范来源', shown(value?.policy?.authority?.sourceUrl)],
                        ['身份依据', '版本化 RefSeq / Ensembl CDS 与保存的 VEP 注释交叉核验'],
                        ['证据边界', '位置支持不证明剪接正常；不自动与预测或实验叠加，不产生最终分类'],
                    ], raw: value,
                };
            }) };
        }
        if (id === 'ACMG_AMP_2015:PVS1') {
            const attempts = candidate.attempts?.length ? candidate.attempts : [{}];
            return {
                id,
                code,
                entries: attempts.map((attempt) => {
                    const value = attempt.pvs1;
                    if (value?.bundleIdentity?.schemaVersion === 'reviewed-rule-bundle-binding-pvs1-v6')
                        return pvs1NmdEntry(attempt);
                    const input = value?.resolvedInput;
                    const alteredCds = input?.coordinateSystem === 'ALTERED_CDS';
                    const reviewed = value?.bundleIdentity?.schemaVersion === 'reviewed-rule-bundle-binding-v1';
                    return {
                        evaluation: value?.evaluation || value,
                        hypothesisId: hypothesisIdOf(attempt),
                        reasons: [],
                        details: [
                            ['适用规范', reviewed ? `${shown(value?.policy?.policyId)} / ${shown(value?.policy?.policyVersion)}（仅 PVS1）` : `${shown(value?.policy?.authority?.specificationId)} / ${shown(value?.policy?.authority?.authorityVersion)}（仅 PVS1）`],
                            ['转录本', reviewed ? shown(input?.selectedTranscript || value?.storedAnnotation?.versionedTranscriptId) : `${shown(input?.ensemblTranscript)} / ${shown(input?.refseqTranscript)}`],
                            ...(reviewed ? [
                                ['本次目录关系', `${shown(value?.context?.gene?.hgncId)} / ${shown(value?.context?.disease?.mondoId)} / ${shown(value?.context?.moi)}`],
                                ['规范选择', `${shown(value?.policySelection?.outcome)}；${shown(value?.policySelection?.catalogId)} @ ${shown(value?.policySelection?.catalogVersion)}`],
                                ['LoF 机制依据', shown(value?.policy?.mechanism?.mechanismId)],
                                ['相关外显子 / 当前实现范围', `${(value?.policy?.nmdScope?.relevantExonOrdinals || []).join(', ') || '—'} / ${(value?.policy?.nmdScope?.engineeringSupportedExonOrdinals || []).join(', ') || '—'}`],
                                ['范围说明', shown(value?.policy?.nmdScope?.engineeringScopeRationale)],
                                ['采用状态', '新策略尚未接通人工采用；本页为证据计算结果'],
                            ] : []),
                            ['外显子与 CDS 位点', `${shown(input?.exonOrdinal)} / ${shown(input?.exonTotal)}；c.${shown(input?.cdsPosition)}`],
                            ['密码子变化', `${shown(input?.refCodon)} → ${shown(input?.altCodon)}`],
                            ['提前终止位置', `${shown(input?.ptcStart)}–${shown(input?.ptcEnd)}（${alteredCds ? '改变后' : '参考'} CDS）`],
                            ['距末次外显子连接', `${shown(input?.distanceToLastJunction)} bp`],
                            ['NMD 判断', input?.nmdPrediction === 'PREDICTED_NMD' ? '预计异常 RNA 被清除（预测，非实验结果）' : '未完成本分支判断'],
                            ...(alteredCds ? [
                                ['移码序列核验', `${input?.sequenceDerivationStatus === 'GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE' ? '等位基因、编码与蛋白注释一致' : '未完成交叉核验'}；净长度变化 ${shown(input?.netLengthChange)} bp`],
                                ['首个改变氨基酸 / 终止相对编号', `${shown(input?.firstChangedProteinPosition)} / fsTer${shown(input?.terminationDistance)}`],
                                ['新终止对应参考位置 / 外显子', `c.${shown(input?.ptcReferenceStart)}–${shown(input?.ptcReferenceEnd)}；${shown(input?.ptcExonOrdinal)} / ${shown(input?.exonTotal)}${input?.ptcReferenceMapping === 'CONTAINS_INSERTED_BASES' ? '；终止密码子含插入碱基，部分无参考对应' : ''}`],
                                ['末次外显子连接位置', `${shown(input?.mutantLastJunctionCdsPosition)}（改变后 CDS）；${shown(input?.referenceLastJunctionCdsPosition)}（参考 CDS）`],
                            ] : []),
                            ['数据来源', `${shown(value?.evidence?.source?.resourceVersion)}；版本化 RefSeq / Ensembl 转录本模型`],
                        ],
                        raw: value,
                    };
                }),
            };
        }
        if (id === 'ACMG_AMP_2015:PS3') {
            return {
                id,
                code: candidate.attempts?.some((attempt) => attempt.functional?.bundleIdentity?.schemaVersion === 'reviewed-rule-bundle-binding-v1') ? 'PS3' : 'PS3 · HNF1A Oxford',
                entries: (candidate.attempts?.length ? candidate.attempts : [{}]).map((attempt) => {
                    const value = attempt.functional;
                    const evaluation = value?.evaluation || value;
                    const record = value?.factRecord;
                    const facts = value?.factPackage;
                    if (value?.bundleIdentity?.schemaVersion === 'reviewed-rule-bundle-binding-v1') {
                        const policy = value?.policy;
                        const method = facts?.method;
                        const measured = record?.sourceCells?.[policy?.assay?.sourceColumns?.measuredScore]?.raw;
                        const range = record?.publishedRange;
                        return {
                            hypothesisId: hypothesisIdOf(attempt), evaluation,
                            reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
                            details: [
                                ['实验对象', record?.proteinVariantKey || record?.recordId || '无可审阅事实记录'],
                                ['规则适用范围', `${shown(policy?.scope?.geneSymbol || policy?.scope?.hgncId)} / ${shown(policy?.scope?.mondoId)} / ${shown(policy?.scope?.moi)}`],
                                ['适用规范', `${shown(policy?.policyId)} @ ${shown(policy?.policyVersion)}`],
                                ['规范选择', `${shown(value?.policySelection?.outcome)}；${shown(value?.policySelection?.catalogId)} @ ${shown(value?.policySelection?.catalogVersion)}`],
                                ['已保存事实版本', `${shown(facts?.factBundleId)} @ ${shown(facts?.version)}`],
                                ['发表结果', range ? `${range.lowerInclusive ? '[' : '('}${shown(range.lower)}, ${shown(range.upper)}${range.upperInclusive ? ']' : ')'} ${shown(range.unit)}` : `${shown(measured)} ${shown(policy?.assay?.unit)}`],
                                ['实验条件与读数', `${shown(method?.conditionId)}；${shown(method?.readout)}`],
                                ['计证条件', `${comparison(policy?.assay?.threshold?.operator)} ${shown(policy?.assay?.threshold?.decimal)}；${shown(policy?.assay?.threshold?.strength)}`],
                                ['重复', shown(method?.biologicalReplicatesDescription)],
                                ['校准依据', shown(method?.calibrationRationale)],
                                ['构建背景', shown(method?.constructDescription)],
                                ['蛋白与核苷酸核验', `${shown(value?.proteinIdentity?.hgvsc)}；${shown(value?.proteinIdentity?.hgvsp)}`],
                                ['采用状态', '新策略尚未接通人工采用；计算结果不代表用户采用或最终临床判断'],
                                ['BS3 状态', '本次未启用；正常蛋白功能结果不能排除剪接影响'],
                            ],
                            links: policy?.sources?.map((source) => ({ url: source.url, label: `${source.sourceId} · ${source.version}` })) || [],
                            raw: value,
                        };
                    }
                    return {
                        hypothesisId: hypothesisIdOf(attempt), evaluation,
                        reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
                        details: [
                            ['实验对象', record?.recordId || '无可审阅事实记录'],
                            ['规则适用范围', 'HNF1A / 单基因糖尿病 / AD；原生 GRCh38 SNV'],
                            ['本次目录关系', `${shown(value?.context?.gene?.hgncId || attempt.context?.gene?.hgncId)} / ${shown(value?.context?.disease?.mondoId || attempt.context?.disease?.mondoId)} / ${shown(value?.context?.moi || attempt.context?.moi)}`],
                            ['已保存事实版本', facts?.version || '未取得'],
                            ['发表结果', (record?.observations || []).map((o) => `${o.conditionId}：${o.lower}–${o.upper}% WT（发表图示保守范围）`).join('；') || '未取得'],
                            ['实验条件', (facts?.assay?.conditions || []).map((c) => `${c.cell} / ${c.promoter}`).join('；') || '未取得'],
                            ['计证条件', record ? '仅 HeLa / rat-albumin 可计入本次 PS3；INS-1 / HNF4A-P2 保留复核，不叠加强度' : '无可计证事实'],
                            ['归一化与重复', facts ? `${facts.assay.normalization}；${facts.assay.replicates}` : '未取得'],
                            ['校准依据', 'GN017 v3.1 Oxford ≤40% WT → PS3 Moderate；沿用 VCEP 实验认可，未独立重校准'],
                            ['构建背景', facts ? 'NM_000545.6；共同 I27L / L17=，与现行 .8 的 p.574 差异已登记；作者报告 Sanger 验证，未独立取得完整构建序列' : '未取得'],
                            ...(facts?.sourceManifest?.some((s) => s.sourceId === 'S1' && s.sha256 === 'b6bc7e1b1cc2b76a8ee39c9edb1040f2a9955085dee17e6df22e087acd620d99')
                                ? [['补表参考版本（原件复核）', 'S1 B1 为 NM_000545.5；S2 B1 未标版本。旧事实包 1.0.0 的 S1“未标版本”表述已勘误，旧保存内容保留。']]
                                : []),
                            ['门禁与局限', record?.gates?.rationale || evaluation?.evaluationResult?.reasonCode || '未评价'],
                            ['BS3 状态', '本次未启用；正常 cDNA 结果不能排除剪接/NMD'],
                        ],
                        links: facts?.sourceManifest?.map((s) => ({ url: s.url, label: `${s.sourceId} · ${s.locator}` })) || [],
                        raw: value,
                    };
                }),
            };
        }
        if (id === 'ACMG_AMP_2015:PM2') {
            const attempts = candidate.attempts?.length ? candidate.attempts : [{}];
            return {
                id,
                code: 'PM2（支持级）',
                entries: attempts.map((attempt) => {
                    const value = attempt.pm2;
                    const context = value?.context || attempt.pm2Context || attempt.context;
                    const alleleLabel = (allele) => allele ? `${shown(allele.assembly)} ${shown(allele.contig)}:${shown(allele.position)} ${shown(allele.reference)}>${shown(allele.alternate)}` : '本次保存对象未提供';
                    const evaluation = value?.evaluation || value;
                    const frequency = parameter(evaluation, 'frequency.raw');
                    const threshold = parameter(evaluation, 'threshold.raw');
                    const countPolicy = value?.policy?.maximumAlleleCount !== undefined;
                    const measurement = (metric) => value?.evidence?.measurements?.find((m) => m.metricId === metric)?.rawValue;
                    const populationExecution = value?.evidence?.resourceExecution?.status;
                    return {
                        evaluation,
                        hypothesisId: hypothesisIdOf(attempt),
                        reasons: [
                            ...(value?.contextBlockers || attempt.pm2ContextBlockers || (!context ? attempt.blockers || [] : [])),
                            ...(value?.policy?.allowWellCoveredAbsence && value?.evidence?.queryOutcome?.status === 'NO_RECORD' && value?.allSitesConfigurationIssue
                                ? [{ reasonCode: value.allSitesConfigurationIssue }] : []),
                        ],
                        details: [
                            ...pm2ContextPreparationDetails(value?.contextPreparation, true),
                            ['人群频率', frequency !== null && frequency !== undefined
                                ? `${shown(frequency)}（比例；${shown(parameter(evaluation, 'frequency.metricId'))}）`
                                : `${shown(measurement('gnomad.joint.af'))}（总体 AF，仅展示人口数据）`],
                            ['本次阈值', countPolicy ? `AC ≤ ${shown(value.policy.maximumAlleleCount)}；AN ≥ ${shown(value.policy.minimumObservedAlleleNumber)}` : `${comparison(parameter(evaluation, 'comparison.operator'))} ${shown(threshold)}（比例）`],
                            ['观测 AC / AN', `${shown(parameter(evaluation, 'frequency.selectedAc') ?? measurement('gnomad.joint.ac'))} / ${shown(parameter(evaluation, 'frequency.selectedAn') ?? measurement('gnomad.joint.an'))}`],
                            ['纯合子数', shown(measurement('gnomad.joint.nhomalt'))],
                            ['数据来源', `${shown(value?.evidence?.source?.resourceVersion)}；祖源 ${shown(parameter(evaluation, 'frequency.selectedAncestry'))}`],
                            ['适用规范', `${shown(value?.policy?.authority?.specificationId)} / ${shown(value?.policy?.authority?.authorityVersion)}`],
                            ['规范来源', shown(value?.policy?.authority?.sourceUrl)],
                            ['待接通专用规范', value?.knownSpecifications?.length
                                ? [...new Set(value.knownSpecifications.map((s) => `${s.specificationId} v${s.version} ${s.sourceUrl}`))].join('；') : '—'],
                            ['人口查询执行', ({ SUCCEEDED: '查询成功', FAILED: '查询失败', TIMEOUT: '查询超时', NOT_STARTED: '未执行' })[populationExecution] || shown(populationExecution)],
                            ['检测有效 AN（外显子 / 基因组）', `${shown(parameter(evaluation, 'callability.exomes.adjAn'))} / ${shown(parameter(evaluation, 'callability.genomes.adjAn'))}`],
                            ...(value?.allSitesEvidence || []).flatMap((evidence) => [
                                [`all-sites 来源 ${shown(evidence.source?.sourceId)}`, `${shown(evidence.source?.resourceVersion)}；执行 ${shown(evidence.resourceExecution?.status)} / ${shown(evidence.queryOutcome?.status)}`],
                                ...(evidence.measurements || []).filter((m) => /\.adjAn$|\.regionFlag\./.test(m.metricId))
                                    .map((m) => [m.metricId, shown(m.normalizedValue ?? m.rawValue)]),
                            ]),
                            ['检测 AN 含义', 'all-sites AN 为位点检测覆盖依据，不是目标变异的观测 AN；外显子和基因组不相加'],
                            ['查询与充分性', `${shown(value?.evidence?.queryOutcome?.status)}；无记录或缺值不等于频率为零`],
                            ['目录关系', `${shown(context?.gene?.hgncId)} / ${shown(context?.disease?.mondoId)} / ${shown(context?.moi)}`],
                            ['输入 / 查询参考基因组', `${shown(context?.variant?.inputAllele?.assembly)} / ${shown(context?.variant?.evidenceQueryAllele?.assembly)}`],
                            ['原始输入等位基因', alleleLabel(context?.variant?.inputAllele)],
                            ['本次查询等位基因', alleleLabel(context?.variant?.evidenceQueryAllele)],
                            ['无记录补证政策', value?.policy ? (value.policy.allowWellCoveredAbsence ? '允许，仍需核验位点覆盖和 AN' : '当前政策不允许以覆盖补证认定无记录') : '尚无唯一适用政策'],
                            ['实际取证分支', value?.evidence?.queryOutcome?.status === 'HIT' ? '已观测变异（HIT）'
                                : value?.evidence?.queryOutcome?.status === 'NO_RECORD' ? '无记录；按原政策检查独立 all-sites 检测证据'
                                    : '尚未取得可用的人口查询结果'],
                        ],
                        raw: value,
                    };
                }),
            };
        }
        if (id === 'ACMG_AMP_2015:BS1') {
            const attempts = candidate.attempts?.length ? candidate.attempts : [{}];
            if (attempts.some((attempt) => attempt.bs1)) {
                const backgrounds = attempts.map((attempt, index) => ({
                    value: String(index), label: [attempt.hypothesisId, attempt.bs1?.context?.disease?.mondoId, attempt.bs1?.context?.moi].filter(Boolean).join(' · ') || '未形成背景',
                }));
                const index = bs1View === undefined ? 0 : backgrounds.findIndex((option) => option.value === bs1View);
                return { id, code: 'BS1', backgrounds, selectedBackground: index < 0 ? '' : String(index),
                    entries: [bs1Entry(index < 0 ? null : attempts[index], candidate, run)] };
            }
            return { id, code: 'BS1', entries: attempts.map((attempt) => frequencyEntry(attempt, id)) };
        }
        if (id === 'ACMG_AMP_2015:BA1') {
            const value = candidate.ba1;
            const current = candidate.ba1ResultSelection || value?.generalization;
            if (current) {
                const contextOf = (attempt) => unifiedLocalScope(run) && Object.hasOwn(attempt || {}, 'ba1Context')
                    ? attempt.ba1Context : attempt?.ba1Context || attempt?.context;
                const alleleLabel = (allele) => allele ? `${shown(allele.assembly)} ${shown(allele.contig)}:${shown(allele.position)} ${shown(allele.reference)}>${shown(allele.alternate)}` : '本次保存对象未提供';
                const contextResults = Array.isArray(value?.contextResults) ? value.contextResults : [];
                const entry = (result, contextId, contextScoped = false, resultIndex = -1) => {
                    const reasonText = (reason) => /^[A-Z][A-Z0-9_]+$/.test(reason || '') ? reasonHelp(reason) : shown(reason);
                    const inputLabel = (field) => ({ verifiedAlleleGeneMapping: '完整基因映射', specificationCatalog: '已核验规范目录',
                        specificationScope: '规范适用范围', 'disease.mondoId': '疾病', moi: '遗传方式',
                        ba1DiseaseContextConfirmation: 'BA1 疾病背景确认', validatedDiseaseTranscript: '已核验疾病转录本',
                        reviewedTranscriptScopeOrVersionBridge: '转录本范围与版本对应依据', reviewedExecutableBa1Clause: '可执行的完整 BA1 条款',
                        verifiedDiseaseAndInheritanceScopeRelation: '疾病及遗传方式与规范的范围关系', verifiedExceptionSnapshot: '已核验例外目录',
                        selectedAncestryAlleleNumber: '所选祖源的有效 AN', jointExomeGenomeFilterFields: '外显子组及基因组过滤证据',
                        jointPopulationObservation: '外显子组与基因组联合频率',
                        completePopulationObservation: '完整且可用的人口观测', consistentAncestryAcAnAf: '一致的同祖源 AC/AN/AF',
                        exomeGenomeConcordance: '两类数据的一致性依据', populationObservationWithUsableFrequency: '含有效频率的人口记录',
                    })[field] || shown(field);
                    const effective = contextScoped ? { ...result, evidence: result?.evidence || value?.evidence } : value || {};
                    const d = ba1Details(effective);
                    const selection = contextScoped ? result?.selection : value?.generalization?.selection;
                    const selectedPolicy = contextScoped ? result?.selection?.policy || result?.policy : value?.generalization?.effectivePolicy || value?.policy;
                    const matchingAttempts = (candidate.attempts || []).filter((item) => contextOf(item)?.contextId === contextId);
                    const attempt = matchingAttempts.length === 1 ? matchingAttempts[0] : undefined;
                    const associationInvalid = contextScoped && (!contextId || !attempt
                        || !candidate.candidateId
                        || attempt.candidateId !== candidate.candidateId
                        || (result?.candidateId && result.candidateId !== candidate.candidateId)
                        || contextResults.filter((item) => item?.contextId === contextId).length !== 1
                        || (candidate.attempts || []).filter((item) => item?.ba1ResultSelection?.effectiveResultPath
                            === `/ba1/contextResults/${resultIndex}`).length !== 1);
                    const authority = contextScoped ? attempt?.ba1ResultSelection : candidate.ba1ResultSelection;
                    const authorityInvalid = (contextScoped || authority?.schemaVersion) && (authority?.schemaVersion !== 'ba1-effective-result-selection-v1'
                        || authority?.fallbackAllowed !== false || authority?.legacyResultsRole !== 'SOURCE_ONLY'
                        || authority?.effectiveResultPath !== (contextScoped ? `/ba1/contextResults/${resultIndex}` : '/ba1'));
                    // Top-level technical failure invalidates the object; candidate scientific refusal is stored inside evaluation.
                    const technicalFailure = associationInvalid || authorityInvalid ? { status: 'NOT_EVALUATED', reasonCode: 'BA1_SAVED_RESULT_ASSOCIATION_INVALID' }
                        : !contextScoped && value?.evaluationResult?.status === 'NOT_EVALUATED' ? value.evaluationResult
                            : effective.evaluationResult?.status === 'NOT_EVALUATED' ? effective.evaluationResult : null;
                    const conditional=inspectBa1Conditional({evaluation:d.evaluation,selection,policy:selectedPolicy,
                        evidence:effective.evidence,rawRecord:effective.rawPopulationRecord||value?.rawPopulationRecord,
                        context:contextOf(attempt),runtimeBinding:run?.executionProfile?.ba1RuntimeBinding},ps2IdentityHash);
                    // A verified scope refusal still carries its saved evidence identity and reason.
                    const boundRefusal = !technicalFailure && !conditional.claimed
                        && boundBa1ScopeRefusal(d.evaluation, selection);
                    // A display-only refusal never substitutes a historical MET or creates a saved evaluation.
                    const evaluation = !boundRefusal && (technicalFailure || conditional.claimed&&!conditional.valid || selection?.status !== 'SELECTED'&&!(conditional.claimed&&conditional.valid))
                        ? {
                            criterionId: id,
                            evaluationResult: { status: 'NOT_EVALUATED', reasonCode: technicalFailure?.reasonCode || (conditional.claimed&&!conditional.valid?'BA1_CONDITIONAL_SAVED_BINDING_INVALID':selection?.reasonCode) || 'BA1_SELECTION_UNAVAILABLE' },
                            criterionApplicability: { status: selection?.status === 'NOT_APPLICABLE' ? 'NOT_APPLICABLE' : 'UNKNOWN' },
                            appliedStrength: 'NONE',
                        } : d.evaluation;
                    const missingInputs = contextScoped ? result?.requiredInputs : value?.generalization?.requiredInputs;
                    const requiredInputs = Array.isArray(missingInputs) ? missingInputs
                        : Array.isArray(selection?.requiredInputs) ? selection.requiredInputs : [];
                    const sources = Array.isArray(selection?.sources) ? selection.sources : [];
                    const supplements = Array.isArray(value?.subtypeFilterEvidence?.results) ? value.subtypeFilterEvidence.results : [];
                    const links = [...sources, ...supplements.map((item) => ({ ...item, title: `gnomAD ${item.subtype} 原生过滤来源` }))]
                        .filter((source) => typeof source?.url === 'string' && /^https:\/\/[^\s]+$/.test(source.url))
                        .map((source) => ({ label: source.sourceId || source.title || '规范来源', url: source.url }));
                    const specification = selection?.specification;
                    const { specificationId, version } = specification || {};
                    const specificationLabel = /^GN\d+$/.test(specificationId) && /^\d+(\.\d+)*$/.test(version)
                        ? `${specificationId} v${version}` : '';
                    if (specificationLabel) links.unshift({ label: `ClinGen ${specificationLabel}`, title: `ClinGen ${specificationLabel}`,
                        url: `https://cspec.genome.network/cspec/ui/svi/doc/${specificationId}?version=${version}` });
                    const scopeBinding = selection?.transcriptScopeBinding;
                    const maneOutcome = { MATCHED: '与规范匹配', RULE_TRANSCRIPT_MISMATCH: '与规范指定转录本不符' }[scopeBinding?.outcome];
                    if (maneOutcome && /^https:\/\/[^\s]+$/.test(scopeBinding.source?.url || '')) {
                        const title = `MANE GRCh38 v${scopeBinding.source.version}`;
                        links.push({ label: title, title, url: scopeBinding.source.url });
                    }
                    const ruleTranscripts = specification?.scope?.transcripts;
                    return {
                        scopeLabel: contextScoped ? '已保存背景 BA1' : '候选 BA1（候选范围）',
                        ...(associationInvalid || authorityInvalid ? { displayIssue: true, summary: '保存的 BA1 结果与所选背景关联不一致。' } : {}),
                        ...(boundRefusal && selection.reasonCode === 'BA1_TRANSCRIPT_SCOPE_UNRESOLVED'
                            && scopeBinding?.outcome === 'RULE_TRANSCRIPT_MISMATCH'
                            ? { summary: '所选转录本与专规指定版本不符，本次 BA1 未评价。' } : {}),
                        ...(conditional.claimed?{displayIssue:!conditional.valid||Boolean(technicalFailure),summary:technicalFailure?'保存的 BA1 结果与所选背景关联不一致。':conditional.valid?conditional.view.summary:conditional.issue,
                            ...(conditional.valid&&!technicalFailure?{ba1ConditionView:conditional.view}:{})}:{}),
                        hypothesisId: contextScoped ? hypothesisIdOf(attempt) : undefined,
                        contextLabel: contextScoped ? [contextOf(attempt)?.gene?.hgncId, contextOf(attempt)?.disease?.mondoId,
                            contextOf(attempt)?.moi, contextId].filter(Boolean).join(' · ') || '背景身份未取得' : '未将特定疾病结论外推到其他背景',
                        evaluation,
                        reasons: [...(effective.blockers || []),
                            ...(contextScoped ? attempt?.ba1ContextBlockers || [] : value?.contextAssemblyBlockers || []),
                            ...d.readiness.map((reasonCode) => ({ reasonCode })),
                            ...(selection?.reasonCode ? [{ reasonCode: selection.reasonCode }] : [])],
                        details: [
                            ...(specificationLabel ? [['专家规范', specificationLabel]] : []),
                            ...(Array.isArray(ruleTranscripts) && ruleTranscripts.length
                                ? [['规范指定转录本', ruleTranscripts.join('、')]] : []),
                            ...(maneOutcome ? [['MANE 对应', `${scopeBinding.ensemblTranscript} ↔ ${scopeBinding.refseqTranscript} · ${maneOutcome}`]] : []),
                            ['规范选择', `${({ SELECTED: '已选择', BLOCKED: '缺项或待核验', NOT_APPLICABLE: '不适用' })[selection?.status] || '未完成'}；${reasonText(selection?.reasonCode)}`],
                            ['所需输入', requiredInputs.length ? requiredInputs.map((input) => `${inputLabel(input?.field)}：${reasonText(input?.reason)}`).join('；') : '无额外缺项声明'],
                            ...(contextScoped ? [
                                ['背景映射范围', selection?.geneMapping?.mappingBasis === 'VALIDATED_EXACT_EVIDENCE_CONTEXT'
                                    ? '仅核验当前确切疾病背景；不代表候选全部注释映射完成'
                                    : '当前背景的映射与候选全量注释检查分别保留，不互相替代'],
                            ] : ba1MappingDetails(selection?.geneMapping)),
                            ...(JSON.stringify(selection || {}).includes('BA1_ADDITIONAL_INSTRUCTIONS_UNSUPPORTED')
                                ? [['另有已记录的条款支持限制', 'BA1_ADDITIONAL_INSTRUCTIONS_UNSUPPORTED：仍有附带条款未获支持，补齐转录本不保证可以评价；不等同于官方判为不适用。']] : []),
                            ['人群频率', `${shown(d.frequency)}（比例；${shown(d.metric)}）`],
                            ...(d.operator && d.threshold != null ? [['本次阈值', `${comparison(d.operator)} ${shown(d.threshold)}（比例）`]] : []),
                            ['祖源 / AN', `${shown(d.ancestry)} / ${shown(d.an)}${d.minimumAnOperator && d.minimumAn != null
                                ? `（要求 ${comparison(d.minimumAnOperator)} ${shown(d.minimumAn)}）` : ''}`],
                            ['同祖源 AC / AF', `${shown(d.ac)} / ${shown(d.af)}；AF 仅用于一致性核验，不替代 FAF95`],
                            ['质量', `FILTER ${shown(d.filter)}；${d.localExomes ? '未评估外显子组与基因组间的差异' : shown(d.qc)}`],
                            ['记录核验子项', d.readiness.length ? d.readiness.map(reasonText).join('；') : '本次保存对象未列出子原因'],
                            d.localExomes ? ['数据范围', '仅使用本地 exomes；genomes 与 joint 未纳入本次判定'] : ['子类型字段', `外显子 ${shown(d.exomesFilter)}；基因组 ${shown(d.genomesFilter)}；明确的“.”与字段缺失分别核验`],
                            ...(supplements.length ? [['原生过滤补证', supplements.map((item) =>
                                `${({ exomes: '外显子组', genomes: '基因组' })[item.subtype] || item.subtype} v${shown(item.version)}：${shown(item.filter)} / ${({ VERIFIED_PASS: '明确通过', NONPASS: '未通过', NO_RECORD: '未查到此等位基因', TIMEOUT: '查询超时', FAILED: '查询失败', AMBIGUOUS: '记录冲突' })[item.status] || shown(item.status)}${item.reasonCode && item.status !== 'VERIFIED_PASS' ? `（${reasonText(item.reasonCode)}）` : ''}`
                            ).join('；')], ['补证范围', '保留 joint 原始缺项；只用同版、同等位基因的两类明确 PASS 补齐过滤证据，不改频率或阈值。']] : []),
                            ['例外检查', ({ NOT_HIT: '未命中', HIT: '命中' })[d.exception] || d.exception || '未完成'],
                            ['数据来源', shown(d.source?.resourceVersion)],
                            ['适用规范', `${shown(selectedPolicy?.policyId || selectedPolicy?.profileId)} @ ${shown(selectedPolicy?.policyVersion || selectedPolicy?.profileVersion)}`],
                            ['目录校验', shown(selection?.catalogSha256)],
                            ['查询状态', `${shown(effective.evidence?.resourceExecution?.status)} / ${shown(effective.evidence?.queryOutcome?.status)}；无记录不等于零频率`],
                            ...(value?.conversionSource ? [
                                ['原始上传位点', alleleLabel(value.conversionSource.originalAllele)],
                                ['实际证据查询位点', alleleLabel(value.conversionSource.analysisAllele)],
                                ['转换核验', value.conversionSource.status === 'VERIFIED' ? '已核验；仍须独立满足 BA1 判断条件'
                                    : reasonText(value.conversionSource.reasonCode || 'CONVERSION_SOURCE_NOT_VERIFIED')],
                                ['转换方法', `${shown(value.conversionSource.method)} @ ${shown(value.conversionSource.implementationVersion)}；方向 ${shown(value.conversionSource.strand)}`],
                                ['转换来源身份', `原始文件 ${shown(value.conversionSource.originalUploadSha256)}；分析文件 ${shown(value.conversionSource.analysisVcfSha256)}；chain ${shown(value.conversionSource.chainSha256)}`],
                                ['参考序列身份', `GRCh37 ${shown(value.conversionSource.originalReferenceSha256)}；GRCh38 ${shown(value.conversionSource.targetReferenceSha256)}`],
                            ] : []),
                            ['证据关系', '每个背景以本次 BA1 选择为准；旧 BA1 仅保留来源记录，不补位、不叠加强度。'],
                        ],
                        links,
                        ...(contextScoped && selectedPolicy?.clause?.requiredDataset === 'JOINT' ? {
                            ba1JointSource: { evidence: effective.evidence, runtime: run?.executionProfile?.ba1RuntimeBinding },
                        } : {}),
                        raw: contextScoped ? result : value,
                    };
                };
                const contextIds = [...new Set([
                    ...(candidate.attempts || []).filter((a) => a.ba1Context || (a.ba1ResultSelection && !(
                        a.ba1ResultSelection.effectiveResultPath === '/ba1'
                        && a.ba1ResultSelection.reasonCode === 'BA1_CONTEXT_RESULT_UNAVAILABLE_CANDIDATE_DISPOSITION_ONLY'
                        && !contextOf(a)?.contextId
                    )))
                        .map((a) => contextOf(a)?.contextId || '__missing_context__'),
                    ...contextResults.map((r) => r?.contextId || '__missing_context__'),
                ])];
                const views = [{ value: 'candidate', label: '候选范围（全注释检查）' }, ...contextIds.map((contextId) => {
                    const attempt = (candidate.attempts || []).find((a) => contextOf(a)?.contextId === contextId);
                    const context = contextOf(attempt);
                    return { value: contextId, label: [context?.gene?.symbol || context?.gene?.hgncId, context?.disease?.mondoId,
                        context?.moi, contextId].filter(Boolean).join(' / ') };
                })];
                const selectedView = views.some((v) => v.value === ba1View) ? ba1View
                    : contextIds.length === 1 ? contextIds[0] : 'candidate';
                const index = contextResults.findIndex((r) => r?.contextId === selectedView);
                const candidateEntry = entry(value);
                const selected = selectedView === 'candidate' ? candidateEntry
                    : entry(index >= 0 ? contextResults[index] : undefined, selectedView, true, index);
                return { id, code, views, selectedView, entries: [{ ...selected,
                    candidateChecks: selectedView === 'candidate' ? undefined : candidateEntry,
                }] };
            }
            const d = ba1Details(value);
            const rawReasons = value?.evidence?.measurements?.find(
                (m) => m.metricId === 'gnomad.recordInputReadinessReasonCodes',
            )?.rawValue;
            const readiness =
                value?.recordInputReadinessReasonCodes ||
                (rawReasons ? rawReasons.split(/[|,;]/) : []);
            return {
                id,
                code,
                entries: [
                    ...(candidate.attempts || []).filter((attempt) =>
                        attempt.hnf1aFrequency || attempt.populationFrequency?.clauseRouting?.[id] === 'CURRENT_CATALOG_RESULT',
                    ).map((attempt) => frequencyEntry(attempt, id)),
                    {
                        scopeLabel: '通用 BA1（等位基因）',
                        evaluation: d.evaluation,
                        reasons: value?.blockers || [],
                        details: [
                            ['作用域', '通用 BA1：独立等位基因结果；合格疾病 BA1 优先，不叠加强度'],
                            [
                                '人群频率',
                                `${shown(d.frequency)}（比例；${shown(d.metric?.split('.').pop())}）`,
                            ],
                            ['本次阈值', `${comparison(d.operator)} ${shown(d.threshold)}（比例）`],
                            ['等位基因数 AN', `${shown(d.an)}（最低 ${shown(d.minimumAn)}）`],
                            [
                                '例外检查',
                                { NOT_HIT: '未命中', HIT: '命中' }[d.exception] ||
                                    d.exception ||
                                    '未完成',
                            ],
                            [
                                '数据来源',
                                `${shown(d.source?.resourceVersion)}；祖源 ${shown(d.ancestry)}`,
                            ],
                            ...(readiness.length ? [['数据缺口', readiness.join(' · ')]] : []),
                        ],
                        raw: value,
                    },
                ],
            };
        }
        const attempts = candidate.attempts?.length ? candidate.attempts : [{}];
        return {
            id,
            code,
            entries: attempts.map((attempt) => {
                if (code === 'PS1' || code === 'PM5') return missenseComparisonEntry(attempt, id);
                const savedEvaluation = attempt.criterionEvaluations?.find(
                    (e) => e.criterionId === id,
                );
                const disposition = code === 'BP7' ? attempt.bp7Disposition : null;
                // Display-only fallback; never persisted as a fabricated evidence-v2 evaluation.
                const evaluation =
                    savedEvaluation ||
                    (disposition
                        ? {
                              evaluationResult: {
                                  status: disposition.status,
                                  reasonCode: disposition.reasonCode,
                              },
                              criterionApplicability: { status: disposition.applicability },
                          }
                        : undefined);
                const synonymous =
                    attempt.transcriptInput?.predictionPath === 'SPLICEAI_SYNONYMOUS';
                const splice =
                    synonymous || attempt.transcriptInput?.predictionPath === 'SPLICEAI_INTRONIC';
                const selection = attempt.selection || attempt.cspecSelection;
                const revel =
                    revelContextAnnotation(attempt.transcriptInput) ||
                    attempt.transcriptInput?.tool === 'REVEL' ||
                    selection?.selectedPolicyId === 'HNF1A_GN017_3_1_REVEL' ||
                    attempt.atomicEvidence?.some((e) => e.source?.sourceId?.startsWith('REVEL_'));
                const evidence =
                    attempt.atomicEvidence?.find((e) =>
                        e.source?.sourceId?.startsWith(revel ? 'REVEL_' : splice ? 'SPLICEAI_' : 'ALPHAMISSENSE_'),
                    ) || (revel ? undefined : attempt.atomicEvidence?.[0]);
                const transcript = attempt.context?.transcript;
                const threshold = parameter(
                    evaluation,
                    revel ? 'threshold' : code === 'PP3' ? 'threshold.pp3.minInclusive' : 'threshold.bp4.maxInclusive',
                );
                const authority = attempt.transcriptInput?.policyAuthority;
                const authorityUrl = authority?.sourceUrl;
                const acceptorLimit = parameter(evaluation, 'threshold.intronicAcceptorMaximum');
                const donorLimit = parameter(evaluation, 'threshold.intronicDonorMinimum');
                const firstBase = String(
                    parameter(evaluation, 'position.synonymousAtExonFirstBase'),
                );
                const endDistance = parameter(evaluation, 'position.synonymousDistanceFromExonEnd');
                const endLimit = parameter(evaluation, 'threshold.synonymousMinimumEndDistance');
                return {
                    evaluation,
                    hypothesisId: hypothesisIdOf(attempt),
                    reasons: attempt.blockers || [],
                    links: revel && typeof authorityUrl === 'string' && /^https:\/\/[^\s]+$/.test(authorityUrl)
                        ? [{ label: '适用 ClinGen 专用规范', url: authorityUrl }] : [],
                    details: [
                        [
                            splice
                                ? 'SpliceAI 最大分数'
                                : code === 'BP7'
                                  ? '计算预测路径'
                                  : revel ? 'REVEL 分数' : 'AlphaMissense 分数',
                            code === 'BP7' && !splice
                                ? revel ? '当前为 REVEL；不能作为 BP7 剪接依赖' : attempt.transcriptInput?.predictionPath ===
                                  'ALPHAMISSENSE_MISSENSE'
                                    ? '当前为 AM；不能作为 BP7 依赖'
                                    : '未取得可执行预测路径'
                                : (evidence?.measurements?.find(
                                      (m) =>
                                          m.metricId ===
                                          (revel ? 'revel.score' : splice
                                              ? 'spliceai.maximumRawDeltaScore'
                                              : 'alphamissense.amPathogenicity'),
                                  )?.rawValue ?? '无可用分数'),
                        ],
                        ...(code === 'PP3' || code === 'BP4'
                            ? [
                                  [
                                      '本次阈值',
                                      threshold == null
                                          ? '未记录'
                                          : `${revel ? comparison(parameter(evaluation, 'comparison')) : code === 'PP3' ? '≥' : '≤'} ${threshold}`,
                                  ],
                              ]
                            : []),
                        ...(revel && (code === 'PP3' || code === 'BP4')
                            ? [
                                  ['适用规范', `${shown(authority?.specificationId)} / ${shown(authority?.authorityVersion)}`],
                                  ['数据来源', shown(evidence?.source?.resourceVersion)],
                                  ['预测范围', 'HNF1A 显性单基因糖尿病的适用错义 SNV；仅提供计算支持，不能排除剪接影响，也不能用于 BP7 剪接依赖。'],
                              ]
                            : []),
                        ...(attempts.length > 1
                            ? [
                                  [
                                      '目录关系',
                                      `${attempt.context?.gene?.hgncId || '未知'} / ${attempt.context?.disease?.mondoId || '未知'}`,
                                  ],
                              ]
                            : []),
                        ...(splice || code === 'BP7'
                            ? [
                                  [
                                      '所选 VEP 注释',
                                      attempt.transcriptInput?.hgvsc ||
                                          (transcript
                                              ? `${transcript.accession}.${transcript.version}`
                                              : '未取得'),
                                  ],
                              ]
                            : [
                                  [
                                      '转录本',
                                      transcript
                                          ? `${transcript.accession}.${transcript.version}`
                                          : '未选择',
                                  ],
                                  ['蛋白改变', attempt.transcriptInput?.hgvsp || '未取得'],
                              ]),
                        ...(code === 'BP7'
                            ? [
                                  [
                                      synonymous ? '同义变异位置' : '内含子位置',
                                      synonymous
                                          ? `外显子首位：${firstBase === 'true' ? '是' : firstBase === 'false' ? '否' : '未知'}；距末位 ${shown(endDistance)} bp（要求非首位且距离 ≥ ${shown(endLimit)}）`
                                          : `偏移 ${shown(attempt.transcriptInput?.intronicSignedOffset)} bp；${
                                                acceptorLimit == null || donorLimit == null
                                                    ? '未记录位置阈值'
                                                    : `要求 ≤ ${acceptorLimit} 或 ≥ ${donorLimit} bp`
                                            }`,
                                  ],
                                  [
                                      '依赖 BP4',
                                      splice ? criterionLabel(
                                          attempt.criterionEvaluations?.find((e) =>
                                              e.criterionId.endsWith(':BP4'),
                                          ),
                                      ) : '未取得可用于 BP7 的剪接 BP4',
                                  ],
                                  [
                                      '使用说明',
                                      '限原生 GRCh38 的适用同义或内含子 SNV；依赖同次预测的 BP4，仅提供计算支持。',
                                  ],
                              ]
                            : splice
                              ? [['预测范围', 'SpliceAI 为基因级预测；所选转录本与位置来自 VEP。']]
                              : []),
                    ],
                    raw: { ...attempt },
                };
            }),
        };
    });
}

// PM3 transport verification only; scientific applicability remains the backend core's decision.
export const pm3IdentityHash = ps2IdentityHash;
const PM3_CRITERION = 'ACMG_AMP_2015:PM3';
const PM3_DECLARATION = 'ordinary-pm3-in-trans-v1';
const PM3_TOKEN = 'PM3_IN_TRANS_CONTEXT@1.0.0:';
const PM3_PARTS = ['facts', 'phase', 'partnerClassification', 'targetRarity', 'partnerRarity'];
const PM3_SELECTION_KEYS = ['candidateId', 'partnerCandidateId', 'contextId', 'targetAlleleId', 'partnerAlleleId', 'pairId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId', 'materialBindingSha256', 'preparationToken', 'policyVersion', 'confirmed'];
export function pm3ScopeClaim(value) {
    if (!value || typeof value !== 'object') return typeof value === 'string' &&
        (value.startsWith('PM3_IN_TRANS_CONTEXT@') || value.startsWith('ordinary-pm3-') || value.startsWith('acmg-pm3-in-trans-development') || value.startsWith('pm3-in-trans-business-') || value.startsWith('pm3-in-trans-context-preparation') || value === 'PM3_IN_TRANS_DEVELOPMENT');
    return Object.entries(value).some(([k, v]) => k === 'pm3' && (['pm3-selected-literature-v1', 'pm3-selected-literature-v2', 'pm3-selected-literature-v3', 'pm3-selected-literature-v4'].includes(v?.version) || v?.privateOriginalAvailable === true)
        ? false : ['pm3Preparation', 'pm3Selection', 'pm3MaterialPins', 'pm3'].includes(k) || pm3ScopeClaim(v));
}
function pm3ProfileValid(p) {
    return ps2Keys(p, ['profileId', 'profileVersion', 'requiredDeclaration', 'formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported', 'enabledCriteria', 'pm3MaterialPins', 'contextPreparation', 'manifestSha256']) &&
        p.profileId === 'acmg-pm3-in-trans-development-v1' && p.profileVersion === '1.0.0' && p.requiredDeclaration === PM3_DECLARATION && ps2Closed(p) &&
        ps2Same(p.enabledCriteria, [PM3_CRITERION]) && ps2Same(p.contextPreparation, { schemaVersion: 'pm3-in-trans-context-preparation-v1', version: '1.0.0' }) &&
        ps2Keys(p.pm3MaterialPins, ['bundleRawSha256', 'bundleCanonicalSha256']) && Object.values(p.pm3MaterialPins).every(ps2Sha) &&
        p.manifestSha256 === pm3IdentityHash(ps2Without(p, ['manifestSha256']));
}
function pm3AlleleValid(v) {
    const normalizer = Object.fromEntries(['method', 'methodVersion', 'referenceSequenceSha256', 'refValidation'].map(k => [k, v.normalization?.[k]]));
    return v.alleleId === 'allele-sha256-' + pm3IdentityHash({ normalizedAllele: v.normalizedAllele, normalizer });
}
function pm3PreparationValid(p, profile, analysisId, candidateId, sampleId) {
    if (!ps2Keys(p, ['schemaVersion', 'version', 'policyVersion', 'status', 'scientificApplicability', 'analysisId', 'sampleId', 'candidateId', 'partnerCandidateId', 'materialBindingSha256', 'criterionPolicySelected', 'predictionCompatibilityClaimed', 'materialBinding', 'patientContext', 'reviewedObservation', 'preparationToken']) ||
        p.schemaVersion !== 'pm3-in-trans-context-preparation-v1' || p.version !== '1.0.0' || p.policyVersion !== '1.0.0-pm3-in-trans-development' ||
        p.status !== 'BOUND_NOT_EVALUATED' || p.scientificApplicability !== 'NOT_EVALUATED' || p.criterionPolicySelected !== false || p.predictionCompatibilityClaimed !== false ||
        p.analysisId !== analysisId || p.candidateId !== candidateId || p.sampleId !== sampleId || !ps2Text(sampleId) ||
        !ps2Text(p.partnerCandidateId) || p.partnerCandidateId === candidateId || p.preparationToken !== PM3_TOKEN + pm3IdentityHash(ps2Without(p, ['preparationToken']))) return false;
    const b = p.materialBinding, c = b.context, patient = c.patientContext, o = p.reviewedObservation, f = o.facts, pair = b.pairBinding;
    if (b.schemaVersion !== 'pm3-in-trans-business-binding-v1' || p.materialBindingSha256 !== pm3IdentityHash(b) ||
        !ps2Same(b.materialPins, profile.pm3MaterialPins) || !ps2Same(b.executionProfile, profile) ||
        b.candidateId !== candidateId || b.partnerCandidateId !== p.partnerCandidateId || !ps2Same(patient, p.patientContext) ||
        !ps2Keys(o, [...PM3_PARTS, 'assessment']) || !ps2Keys(b.partPins, PM3_PARTS) ||
        !PM3_PARTS.every(k => ps2Sha(b.partPins[k]) && pm3IdentityHash(o[k]) === b.partPins[k] && ps2Same(o[k].binding, pair)) ||
        !ps2Same(o.assessment.partPins, b.partPins) || o.assessment.contentSha256 !== pm3IdentityHash(ps2Without(o.assessment, ['contentSha256'])) ||
        pm3IdentityHash(o.assessment) !== b.assessmentSha256 || !['assessmentSha256', 'policySha256', 'catalogSha256'].every(k => ps2Sha(b[k])) ||
        !ps2Sha(b.registryBinding?.registrySha256) || c.schemaVersion !== 'evidence-context-v3' || c.canonicalizationProfile !== 'varvis-json-c14n-v1' ||
        !['patientContextId', 'familyContextId', 'role', 'sex', 'affectedStatus'].every(k => ps2Text(patient[k])) ||
        !ps2Same(c.variant, f.alleles.target) || !pm3AlleleValid(f.alleles.target) || !pm3AlleleValid(f.alleles.partner) ||
        f.alleles.target.alleleId === f.alleles.partner.alleleId) return false;
    const projection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass, scope: c.scope,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: patient };
    if (Object.hasOwn(c, 'transcript')) projection.transcript = { accession: c.transcript.accession, version: c.transcript.version };
    const expectedPair = { observationId: f.observationId, occurrenceId: f.occurrenceId, caseId: f.caseId, contextId: c.contextId,
        targetAlleleId: f.alleles.target.alleleId, partnerAlleleId: f.alleles.partner.alleleId,
        pairId: 'pair-sha256-' + pm3IdentityHash([f.alleles.target.alleleId.normalize('NFC'), f.alleles.partner.alleleId.normalize('NFC')].sort()),
        hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContextId: patient.patientContextId, familyContextId: patient.familyContextId };
    const origin = b.publishedInputIdentity;
    return c.contextId === 'context-sha256-' + pm3IdentityHash(projection) && ps2Same(pair, expectedPair) &&
        ['caseId', 'observationId', 'occurrenceId'].every(k => ps2Text(f[k])) && f.familyId === patient.familyContextId &&
        f.members.filter(m => m.role === 'PROBAND' && m.memberId === patient.patientContextId && m.sampleId === sampleId && m.affectedStatus === patient.affectedStatus).length === 1 &&
        o.partnerClassification.alleleId === pair.partnerAlleleId && o.targetRarity.alleleRole === 'target' && o.partnerRarity.alleleRole === 'partner' &&
        ['targetAlleleId', 'pairId', 'caseId'].every(k => o.partnerClassification.independence[k] === pair[k]) && o.targetRarity.alleleId === pair.targetAlleleId && o.partnerRarity.alleleId === pair.partnerAlleleId &&
        origin.analysisId === analysisId && origin.sampleId === sampleId && origin.inputAssembly === c.variant.inputAllele.assembly && ps2Text(origin.analysisMode) &&
        ['uploadSha256', 'candidatesRawSha256', 'executionSourceSha256'].every(k => ps2Sha(origin[k]));
}
export function pm3ScopeIssue(o, analysisId = o?.analysisId) {
    if (!pm3ScopeClaim(o)) return null;
    if (pp4ScopeClaim(o)) return '混入 PP4 身份，不授予提交许可。';
    if (bs4ScopeClaim(o)) return '混入 BS4 身份，不授予提交许可。';
    if (pp1ScopeClaim(o)) return '混入 PP1 身份，不授予提交许可。';
    try {
        const p = o.pm3Preparation, s = o.pm3Selection;
        if (bs4ScopeClaim(o) || bp2ScopeClaim(o) || ps2ScopeClaim(o) || pm6ScopeClaim(o) || o.analysisId !== analysisId || !ps2Text(analysisId) || o.acceptanceScope !== 'PM3_IN_TRANS_DEVELOPMENT' || o.requiredDeclaration !== PM3_DECLARATION ||
            !ps2Closed(o) || o.clinicalValidationCompleted !== false || o.runtimeConfigured !== true || o.criterionPolicySelected !== false || o.predictionCompatibilityClaimed !== false ||
            o.requestedN !== 1 || o.selectedCount !== 1 || !ps2Same(o.enabledCriteria, [PM3_CRITERION]) || o.candidateIds?.length !== 1 || !ps2Text(o.candidateIds[0]) ||
            ['candidates', 'recommendations', 'association', 'calibration', 'revelApplication', 'runtimeBinding', 'hypotheses'].some(k => Object.hasOwn(o, k)) ||
            !pm3ProfileValid(o.executionProfile) || !pm3PreparationValid(p, o.executionProfile, analysisId, o.candidateIds[0], o.sampleId) || !ps2Keys(s, PM3_SELECTION_KEYS)) return 'PM3 当前双变异材料或执行身份不一致，请重新读取；未授予提交许可。';
        const expected = { candidateId: p.candidateId, partnerCandidateId: p.partnerCandidateId,
            ...Object.fromEntries(['contextId', 'targetAlleleId', 'partnerAlleleId', 'pairId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId'].map(k => [k, p.materialBinding.pairBinding[k]])),
            materialBindingSha256: p.materialBindingSha256, preparationToken: p.preparationToken, policyVersion: p.policyVersion, confirmed: false };
        return ps2Same(expected, s) ? null : 'PM3 当前目标/伙伴选择身份不一致，请重新读取。';
    } catch { return 'PM3 当前材料身份缺失或摘要不一致，请重新读取；未提交评价。'; }
}
export function pm3RetryEligible(run, analysisId) {
    if (pp4ScopeClaim(run)) return false;
    if (bs4ScopeClaim(run)) return false;
    if (pp1ScopeClaim(run)) return false;
    try { return !bs4ScopeClaim(run) && !bp2ScopeClaim(run) && !ps2ScopeClaim(run) && !pm6ScopeClaim(run) && (!run?.acceptanceScope || run.acceptanceScope === 'PM3_IN_TRANS_DEVELOPMENT') &&
        (!run?.executionProfile || pm3ProfileValid(run.executionProfile)) && (!run?.enabledCriteria || ps2Same(run.enabledCriteria, [PM3_CRITERION])) &&
        (!run?.declarationVersion || run.declarationVersion === PM3_DECLARATION) && run?.analysisId === analysisId && /^[0-9a-f-]{36}$/.test(run.runId) &&
        ['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status); } catch { return false; }
}
export function buildPm3Declaration(options, analysisId, confirmed, retryRun) {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(bp2ScopeClaim)) throw new Error('BP2 必须使用独立声明入口。');
    if (!pm3ScopeClaim(options) || pm3ScopeIssue(options, analysisId) || confirmed !== true) throw new Error('PM3 请读取当前双变异材料并明确确认后提交。');
    if (retryRun !== undefined && !pm3RetryEligible(retryRun, analysisId)) throw new Error('PM3 仅可显式重试本分析已选 FAILED 或 COMPLETED_WITH_BLOCKERS 运行。');
    const context = options.pm3Preparation.materialBinding.context;
    return { topN: 1, declarationVersion: PM3_DECLARATION, sampleId: options.sampleId, dataClass: context.dataClass,
        patient: Object.fromEntries(['patientContextId', 'role', 'sex', 'familyContextId'].map(k => [k, context.patientContext[k]])), hypotheses: [],
        pm3Selection: { ...options.pm3Selection, confirmed: true }, ...(retryRun ? { retryOf: retryRun.runId } : {}) };
}
// Transport linkage from Pm3InTransCriterionEvaluator/carrier and the selector receipt.
// This checks returned references, not GT/phase/classification/AF or a scientific decision.
function pm3CompletedLinked(core, b) {
    const source = core.sourceBundle, o = source.observations[0], facts = o.facts, policy = source.policy, catalog = source.catalog;
    const selection = core.policySelection, evidence = core.evidence, e = core.evaluation, trace = e?.deterministicTrace;
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || !evidence || !e || !trace || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.pm3Points !== 1 || core.observationCount !== 1 ||
        core.caseId !== facts.caseId || core.occurrenceId !== facts.occurrenceId ||
        ![selection, evidence, e].every(v => v.contextId === source.context.contextId && v.contextId === b.context.contextId) ||
        selection.schemaVersion !== 'acmg-specification-selection-v1' || !id(selection.selectionId, 'cspec-selection-sha256-') ||
        selection.outcome !== 'GENERAL_POLICY_SELECTED' || !ps2Same(selection.criterionIds, [PM3_CRITERION]) ||
        !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], catalog[k])) || selection.catalogSha256 !== b.catalogSha256 ||
        !['policyId', 'policyVersion'].every(k => ps2Text(selection[k]) && selection[k] === policy[k]) || selection.policySha256 !== b.policySha256 ||
        policy.schemaVersion !== 'pm3-reviewed-in-trans-policy-v1' || policy.mode !== 'SINGLE_CONFIRMED_TRANS_PAIR' || !ps2Same(policy.criterionIds, [PM3_CRITERION])) return false;
    const entries = catalog.entries.filter(v => v.entryId === selection.catalogEntryId);
    if (entries.length !== 1) return false;
    const entry = entries[0], context = source.context;
    if (entry.criterionId !== PM3_CRITERION || !['scope', 'disposition', 'baseRuleRelationship', 'sourceIds'].every(k => ps2Same(selection[k], entry[k])) ||
        !ps2Same(entry.policyBinding, { policyId: policy.policyId, policyVersion: policy.policyVersion, policySha256: b.policySha256 }) ||
        ![selection.scope, policy.scope].every(scope => scope.hgncId === context.gene.hgncId && scope.mondoId === context.disease.mondoId &&
            scope.moi === context.moi && scope.assembly === context.variant.evidenceQueryAllele.assembly)) return false;
    if (evidence.schemaVersion !== 'atomic-evidence-v2' || evidence.canonicalizationProfile !== 'varvis-json-c14n-v1' || evidence.evidenceType !== 'CASE_OBSERVATION' ||
        !id(evidence.evidenceId, 'evidence-sha256-') || !id(e.evaluationId, 'evaluation-sha256-') ||
        e.schemaVersion !== 'criterion-evaluation-v2' || e.canonicalizationProfile !== 'varvis-json-c14n-v1' || e.criterionId !== PM3_CRITERION ||
        e.baseStrength !== 'MODERATE' || e.appliedStrength !== 'MODERATE' ||
        !ps2Same(e.evaluationResult, { status: 'MET', reasonCode: 'PM3_SINGLE_CONFIRMED_TRANS_INDEPENDENT_P_LP' }) ||
        !ps2Same(e.criterionApplicability, { status: 'APPLICABLE' }) || !ps2Same(e.evidenceIds, [evidence.evidenceId]) ||
        !ps2Same(e.ruleBinding, { currentGeneralDesignation: 'PM3', ruleRegistryId: source.registryBinding.registryId,
            ruleRegistrySha256: source.registryBinding.registrySha256, cspecSelectionId: selection.selectionId, authoritySourceIds: policy.authoritySourceIds })) return false;
    const observationSha = pm3IdentityHash(o), date = o.assessment.review.date;
    const locators = PM3_PARTS.flatMap(part => o[part].sources.map(document => ({ locatorType: 'DOCUMENT_SECTION', locatorValue: document.locator,
        sourceRecordId: document.recordId, artifactSha256: document.rawSha256, retrievedAt: document.acquiredAt })));
    if (!ps2Same(evidence.source, { sourceId: 'REVIEWED_CASE_FAMILY_OBSERVATION', resourceVersion: facts.observationVersion,
        resourceSha256: observationSha, retrievedAt: date, sourceRecords: locators.map(locator => ({ recordId: locator.sourceRecordId, locator })), sourceLocators: locators }) ||
        evidence.provenance?.sourcePayloadSha256 !== observationSha || evidence.provenance?.producedAt !== date ||
        evidence.provenance?.identityProjectionSha256 !== evidence.evidenceId.slice('evidence-sha256-'.length) ||
        evidence.queryOutcome?.queryFingerprint !== pm3IdentityHash(facts.binding) || evidence.queryOutcome?.recordCount !== locators.length) return false;
    const parameters = { observationSha256: observationSha, pairId: b.pairBinding.pairId, factsSha256: b.partPins.facts,
        assessmentSha256: b.assessmentSha256, policySha256: b.policySha256, pm3Points: '1', occurrenceId: facts.occurrenceId };
    return trace.executionStatus === 'COMPLETED' && trace.evaluatorId === 'reviewed-single-trio-in-trans-pm3' && trace.evaluatorVersion === '1.0.0' &&
        trace.algorithmId === policy.policyId && trace.algorithmVersion === policy.policyVersion && ps2Sha(trace.traceSha256) &&
        ps2Same(trace.inputEvidenceIds, [evidence.evidenceId]) &&
        ps2Same(trace.parameters, Object.entries(parameters).map(([name, value]) => ({ name, value }))) &&
        ps2Same(trace.steps, [{ stepCode: e.evaluationResult.reasonCode, outcome: 'TRUE', inputEvidenceIds: [evidence.evidenceId] }]);
}
export function pm3SavedRun(run) {
    if (pp4ScopeClaim(run)) return false;
    if (bs4ScopeClaim(run)) return false;
    if (pp1ScopeClaim(run)) return false;
    try {
        if (bp2ScopeClaim(run) || !pm3ScopeClaim(run) || ps2ScopeClaim(run) || pm6ScopeClaim(run) || !['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status) ||
            !ps2Text(run.runId) || !ps2Text(run.analysisId) || !ps2Closed(run) || run.clinicalValidationCompleted !== false || run.candidateOrderingChanged !== false ||
            run.acceptanceScope !== 'PM3_IN_TRANS_DEVELOPMENT' || run.declarationVersion !== PM3_DECLARATION || !pm3ProfileValid(run.executionProfile) ||
            !ps2Same(run.enabledCriteria, [PM3_CRITERION]) || run.requestedN !== 1 || run.selectedCount !== 1 || run.candidates?.length !== 1 || run.candidateIds?.length !== 1 || run.bundles?.length !== 1) return false;
        const candidate = run.candidates[0], a = candidate.attempts?.[0], artifact = run.bundles[0], core = a.pm3, source = core.sourceBundle, p = artifact.preparation, b = p.materialBinding;
        if (candidate.candidateId !== run.candidateIds[0] || candidate.attempts.length !== 1 || !ps2Keys(a, ['pm3', 'criterionEvaluations', 'blockers']) ||
            artifact.schemaVersion !== 'pm3-in-trans-business-artifact-v1' || artifact.runId !== run.runId || artifact.analysisId !== run.analysisId || artifact.candidateId !== candidate.candidateId ||
            artifact.classificationEnabled !== false || artifact.adoptionSupported !== false || artifact.coreDigestKind !== 'RAW_BYTES_SHA256' || artifact.artifactIdentityKind !== 'CANONICAL_WITH_CORE_RAW_DIGEST' || !ps2Sha(artifact.coreRawSha256) ||
            artifact.bundleId !== 'pm3-business-artifact-sha256-' + pm3IdentityHash(ps2Without(artifact, ['core', 'bundleId'])) || !ps2Same(artifact.core, core) ||
            core.criterionId !== PM3_CRITERION || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.resultMode !== 'CORE_RESULT_ONLY' || core.classificationEnabled !== false || core.adoptionSupported !== false ||
            !ps2Same(a.criterionEvaluations, core.criterionEvaluations) || core.criterionEvaluations?.length !== 1 || core.criterionEvaluations[0].criterionId !== PM3_CRITERION ||
            !pm3PreparationValid(p, run.executionProfile, run.analysisId, candidate.candidateId, p.sampleId) || run.dataClass !== b.context.dataClass || source.observations?.length !== 1 ||
            !ps2Same(source.context, b.context) || !ps2Same(source.observations[0], p.reviewedObservation) || !ps2Same(source.registryBinding, b.registryBinding) ||
            !ps2Same(source.policy.partPins, b.partPins) || source.policy.assessmentSha256 !== b.assessmentSha256 || pm3IdentityHash(source.policy) !== b.policySha256 ||
            source.catalogSha256 !== b.catalogSha256 || pm3IdentityHash(source.catalog) !== b.catalogSha256 || pm3IdentityHash(source) !== b.materialPins.bundleCanonicalSha256) return false;
        const evaluation = core.criterionEvaluations[0];
        if (core.evaluation) return run.status === 'COMPLETED' && ps2Same(core.evaluation, evaluation) && pm3CompletedLinked(core, b);
        return run.status === 'COMPLETED_WITH_BLOCKERS' && ps2Keys(evaluation, ['criterionId', 'evaluationResult', 'criterionApplicability']) &&
            ps2Keys(core, ['criterionId', 'resultMode', 'sourceOfTruth', 'classificationEnabled', 'adoptionSupported', 'sourceBundle', 'evaluationResult', 'criterionApplicability', 'criterionEvaluations']) &&
            ps2Keys(core.evaluationResult, ['status', 'reasonCode']) && ps2Keys(core.criterionApplicability, ['status', 'reasonCode']) &&
            core.evaluationResult.status === 'NOT_EVALUATED' && core.criterionApplicability.status === 'UNKNOWN' &&
            typeof core.evaluationResult.reasonCode === 'string' && core.evaluationResult.reasonCode.trim().length > 0 &&
            core.evaluationResult.reasonCode === core.criterionApplicability.reasonCode && ps2Same(core.evaluationResult, evaluation.evaluationResult) && ps2Same(core.criterionApplicability, evaluation.criterionApplicability);
    } catch { return false; }
}
export function pm3EvidenceEntry(core) {
    const source = core?.sourceBundle, o = source?.observations?.[0], c = source?.context;
    const evaluation = core?.evaluation || core?.criterionEvaluations?.[0];
    return { evaluation, scopeLabel: 'PM3 单家系双变异支持证据', contextLabel: [c?.gene?.symbol, c?.disease?.label, c?.moi].filter(Boolean).join(' · '),
        reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [['本次积分', core?.pm3Points ?? '未给出'], ['来源标签', core?.resultMode], ['目标 / 伙伴原始角色', o?.facts?.alleles],
            ['相位原结论与亲本方向', o?.phase], ['伙伴外部分级及独立性（不是目标分类）', o?.partnerClassification],
            ['目标稀有性原值', o?.targetRarity], ['伙伴稀有性原值', o?.partnerRarity], ['六条记录 / 亲缘 / 来源原文', o?.facts], ['已有审核（不是本次科学批准）', o?.assessment],
            ['结论边界', '单例最大 Moderate；不自动PS2/PM6、不累计、不分类。UNKNOWN不补分或强度。']].map(([label, value]) => [label, value && typeof value === 'object' ? JSON.stringify(value) : value]), raw: core };
}

// BP2 transport verification only; scientific applicability remains the backend core's decision.
export const bp2IdentityHash = ps2IdentityHash;
const BP2_CRITERION = 'ACMG_AMP_2015:BP2';
const BP2_DECLARATION = 'ordinary-bp2-phase-v1';
const BP2_TOKEN = 'BP2_PHASE_CONTEXT@1.0.0:';
const BP2_PARTS = ['facts', 'phase', 'partnerClassification', 'diseaseApplicability'];
const BP2_SELECTION_KEYS = ['candidateId', 'partnerCandidateId', 'contextId', 'targetAlleleId', 'partnerAlleleId', 'pairId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId', 'materialBindingSha256', 'preparationToken', 'policyVersion', 'confirmed'];
export function bp2ScopeClaim(value) {
    if (!value || typeof value !== 'object') return typeof value === 'string' &&
        (value.startsWith('BP2_PHASE_CONTEXT@') || value.startsWith('ordinary-bp2-') || value.startsWith('acmg-bp2-phase-development') || value.startsWith('bp2-phase-business-') || value.startsWith('bp2-phase-context-preparation') || value === 'BP2_PHASE_DEVELOPMENT');
    return Object.entries(value).some(([k, v]) => ['bp2Preparation', 'bp2Selection', 'bp2MaterialPins', 'bp2'].includes(k) || bp2ScopeClaim(v));
}
function bp2ProfileValid(p) {
    return ps2Keys(p, ['profileId', 'profileVersion', 'requiredDeclaration', 'formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported', 'enabledCriteria', 'bp2MaterialPins', 'contextPreparation', 'manifestSha256']) &&
        p.profileId === 'acmg-bp2-phase-development-v1' && p.profileVersion === '1.0.0' && p.requiredDeclaration === BP2_DECLARATION && ps2Closed(p) &&
        ps2Same(p.enabledCriteria, [BP2_CRITERION]) && ps2Same(p.contextPreparation, { schemaVersion: 'bp2-phase-context-preparation-v1', version: '1.0.0' }) &&
        ps2Keys(p.bp2MaterialPins, ['bundleRawSha256', 'bundleCanonicalSha256']) && Object.values(p.bp2MaterialPins).every(ps2Sha) &&
        p.manifestSha256 === bp2IdentityHash(ps2Without(p, ['manifestSha256']));
}
function bp2AlleleValid(v) {
    const normalizer = Object.fromEntries(['method', 'methodVersion', 'referenceSequenceSha256', 'refValidation'].map(k => [k, v.normalization?.[k]]));
    return v.alleleId === 'allele-sha256-' + bp2IdentityHash({ normalizedAllele: v.normalizedAllele, normalizer });
}
function bp2PreparationValid(p, profile, analysisId, candidateId, sampleId) {
    if (!ps2Keys(p, ['schemaVersion', 'version', 'policyVersion', 'status', 'scientificApplicability', 'analysisId', 'sampleId', 'candidateId', 'partnerCandidateId', 'materialBindingSha256', 'criterionPolicySelected', 'predictionCompatibilityClaimed', 'materialBinding', 'patientContext', 'reviewedObservation', 'preparationToken']) ||
        p.schemaVersion !== 'bp2-phase-context-preparation-v1' || p.version !== '1.0.0' || p.policyVersion !== '1.0.0-bp2-phase-development' ||
        p.status !== 'BOUND_NOT_EVALUATED' || p.scientificApplicability !== 'NOT_EVALUATED' || p.criterionPolicySelected !== false || p.predictionCompatibilityClaimed !== false ||
        p.analysisId !== analysisId || p.candidateId !== candidateId || p.sampleId !== sampleId || !ps2Text(sampleId) ||
        !ps2Text(p.partnerCandidateId) || p.partnerCandidateId === candidateId || p.preparationToken !== BP2_TOKEN + bp2IdentityHash(ps2Without(p, ['preparationToken']))) return false;
    const b = p.materialBinding, c = b.context, patient = c.patientContext, o = p.reviewedObservation, f = o.facts, pair = b.pairBinding;
    if (b.schemaVersion !== 'bp2-phase-business-binding-v1' || b.analysisId !== analysisId || b.sampleId !== sampleId || b.dataClass !== c.dataClass || p.materialBindingSha256 !== bp2IdentityHash(b) ||
        !ps2Same(b.materialPins, profile.bp2MaterialPins) || !ps2Same(b.executionProfile, profile) ||
        b.candidateId !== candidateId || b.partnerCandidateId !== p.partnerCandidateId || !ps2Same(patient, p.patientContext) ||
        !ps2Keys(o, [...BP2_PARTS, 'assessment']) || !ps2Keys(b.partPins, BP2_PARTS) ||
        !BP2_PARTS.every(k => ps2Sha(b.partPins[k]) && bp2IdentityHash(o[k]) === b.partPins[k] && ps2Same(o[k].binding, pair)) ||
        !ps2Same(o.assessment.partPins, b.partPins) || o.assessment.contentSha256 !== bp2IdentityHash(ps2Without(o.assessment, ['contentSha256'])) ||
        bp2IdentityHash(o.assessment) !== b.assessmentSha256 || !['assessmentSha256', 'policySha256', 'catalogSha256'].every(k => ps2Sha(b[k])) ||
        !ps2Sha(b.registryBinding?.registrySha256) || c.schemaVersion !== 'evidence-context-v3' || c.canonicalizationProfile !== 'varvis-json-c14n-v1' ||
        !['patientContextId', 'familyContextId', 'role', 'sex', 'affectedStatus'].every(k => ps2Text(patient[k])) ||
        !ps2Same(c.variant, f.alleles.target) || !bp2AlleleValid(f.alleles.target) || !bp2AlleleValid(f.alleles.partner) ||
        f.alleles.target.alleleId === f.alleles.partner.alleleId) return false;
    const projection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass, scope: c.scope,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: patient };
    if (Object.hasOwn(c, 'transcript')) projection.transcript = { accession: c.transcript.accession, version: c.transcript.version };
    const expectedPair = { observationId: f.observationId, occurrenceId: f.occurrenceId, caseId: f.caseId, contextId: c.contextId,
        targetAlleleId: f.alleles.target.alleleId, partnerAlleleId: f.alleles.partner.alleleId,
        pairId: 'pair-sha256-' + bp2IdentityHash([f.alleles.target.alleleId.normalize('NFC'), f.alleles.partner.alleleId.normalize('NFC')].sort()),
        hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContextId: patient.patientContextId, familyContextId: patient.familyContextId };
    const origin = b.publishedInputIdentity;
    return c.contextId === 'context-sha256-' + bp2IdentityHash(projection) && ps2Same(pair, expectedPair) &&
        ['caseId', 'observationId', 'occurrenceId'].every(k => ps2Text(f[k])) && f.familyId === patient.familyContextId &&
        f.members.filter(m => m.role === 'PROBAND' && m.memberId === patient.patientContextId && m.sampleId === sampleId && m.affectedStatus === patient.affectedStatus).length === 1 &&
        o.partnerClassification.alleleId === pair.partnerAlleleId &&
        ['targetAlleleId', 'pairId', 'caseId'].every(k => o.partnerClassification.independence[k] === pair[k]) &&
        origin.analysisId === analysisId && origin.sampleId === sampleId && origin.inputAssembly === c.variant.inputAllele.assembly && ps2Text(origin.analysisMode) &&
        ['uploadSha256', 'candidatesRawSha256', 'executionSourceSha256'].every(k => ps2Sha(origin[k]));
}
export function bp2ScopeIssue(o, analysisId = o?.analysisId) {
    if (!bp2ScopeClaim(o)) return null;
    if (pp4ScopeClaim(o)) return '混入 PP4 身份，不授予提交许可。';
    if (bs4ScopeClaim(o)) return '混入 BS4 身份，不授予提交许可。';
    if (pp1ScopeClaim(o)) return '混入 PP1 身份，不授予提交许可。';
    try {
        const p = o.bp2Preparation, s = o.bp2Selection;
        if (ps2ScopeClaim(o) || pm6ScopeClaim(o) || pm3ScopeClaim(o) || o.analysisId !== analysisId || !ps2Text(analysisId) || o.acceptanceScope !== 'BP2_PHASE_DEVELOPMENT' || o.requiredDeclaration !== BP2_DECLARATION ||
            !ps2Closed(o) || o.clinicalValidationCompleted !== false || o.runtimeConfigured !== true || o.criterionPolicySelected !== false || o.predictionCompatibilityClaimed !== false ||
            o.requestedN !== 1 || o.selectedCount !== 1 || !ps2Same(o.enabledCriteria, [BP2_CRITERION]) || o.candidateIds?.length !== 1 || !ps2Text(o.candidateIds[0]) ||
            ['candidates', 'recommendations', 'association', 'calibration', 'revelApplication', 'runtimeBinding', 'hypotheses'].some(k => Object.hasOwn(o, k)) ||
            !bp2ProfileValid(o.executionProfile) || !bp2PreparationValid(p, o.executionProfile, analysisId, o.candidateIds[0], o.sampleId) || !ps2Keys(s, BP2_SELECTION_KEYS)) return 'BP2 当前双变异材料或执行身份不一致，请重新读取；未授予提交许可。';
        const expected = { candidateId: p.candidateId, partnerCandidateId: p.partnerCandidateId,
            ...Object.fromEntries(['contextId', 'targetAlleleId', 'partnerAlleleId', 'pairId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId'].map(k => [k, p.materialBinding.pairBinding[k]])),
            materialBindingSha256: p.materialBindingSha256, preparationToken: p.preparationToken, policyVersion: p.policyVersion, confirmed: false };
        return ps2Same(expected, s) ? null : 'BP2 当前目标/伙伴选择身份不一致，请重新读取。';
    } catch { return 'BP2 当前材料身份缺失或摘要不一致，请重新读取；未提交评价。'; }
}
export function bp2RetryEligible(run, analysisId) {
    if (pp4ScopeClaim(run)) return false;
    if (bs4ScopeClaim(run)) return false;
    if (pp1ScopeClaim(run)) return false;
    try { return !ps2ScopeClaim(run) && !pm6ScopeClaim(run) && !pm3ScopeClaim(run) && (!run?.acceptanceScope || run.acceptanceScope === 'BP2_PHASE_DEVELOPMENT') &&
        (!run?.executionProfile || bp2ProfileValid(run.executionProfile)) && (!run?.enabledCriteria || ps2Same(run.enabledCriteria, [BP2_CRITERION])) &&
        (!run?.declarationVersion || run.declarationVersion === BP2_DECLARATION) && run?.analysisId === analysisId && /^[0-9a-f-]{36}$/.test(run.runId) &&
        ['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status); } catch { return false; }
}
export function buildBp2Declaration(options, analysisId, confirmed, retryRun) {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用固定单先证者材料独立确认入口。');
    if ([...arguments].some(bs4ScopeClaim)) throw new Error('BS4 请使用固定核家系材料独立确认入口。');
    if ([...arguments].some(pp1ScopeClaim)) throw new Error('PP1 请使用固定核家系材料独立确认入口。');
    if (!bp2ScopeClaim(options) || bp2ScopeIssue(options, analysisId) || confirmed !== true) throw new Error('BP2 请读取当前双变异材料并明确确认后提交。');
    if (retryRun !== undefined && !bp2RetryEligible(retryRun, analysisId)) throw new Error('BP2 仅可显式重试本分析已选 FAILED 或 COMPLETED_WITH_BLOCKERS 运行。');
    const context = options.bp2Preparation.materialBinding.context;
    return { topN: 1, declarationVersion: BP2_DECLARATION, sampleId: options.sampleId, dataClass: context.dataClass,
        patient: Object.fromEntries(['patientContextId', 'role', 'sex', 'familyContextId'].map(k => [k, context.patientContext[k]])), hypotheses: [],
        bp2Selection: { ...options.bp2Selection, confirmed: true }, ...(retryRun ? { retryOf: retryRun.runId } : {}) };
}
// Transport linkage from Bp2InTransCriterionEvaluator/carrier and the selector receipt.
// This checks returned references, not GT/phase/classification/AF or a scientific decision.
function bp2SelectionLinked(selection, source, b) {
    const policy = source.policy, catalog = source.catalog, o = source.observations[0];
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || selection.contextId !== source.context.contextId) return false;
    if (selection.schemaVersion !== 'acmg-specification-selection-v1' || !id(selection.selectionId, 'cspec-selection-sha256-') ||
        selection.outcome !== 'GENERAL_POLICY_SELECTED' || !ps2Same(selection.criterionIds, [BP2_CRITERION]) ||
        !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], catalog[k])) || selection.catalogSha256 !== b.catalogSha256 ||
        !['policyId', 'policyVersion'].every(k => ps2Text(selection[k]) && selection[k] === policy[k]) || selection.policySha256 !== b.policySha256 ||
        policy.schemaVersion !== 'bp2-reviewed-phase-policy-v1' || policy.mode !== o.diseaseApplicability.mode || !ps2Same(policy.criterionIds, [BP2_CRITERION])) return false;
    const entries = catalog.entries.filter(v => v.entryId === selection.catalogEntryId);
    if (entries.length !== 1) return false;
    const entry = entries[0], context = source.context;
    if (entry.criterionId !== BP2_CRITERION || !['scope', 'disposition', 'baseRuleRelationship', 'sourceIds'].every(k => ps2Same(selection[k], entry[k])) ||
        !ps2Same(entry.policyBinding, { policyId: policy.policyId, policyVersion: policy.policyVersion, policySha256: b.policySha256 }) ||
        ![selection.scope, policy.scope].every(scope => scope.hgncId === context.gene.hgncId && scope.mondoId === context.disease.mondoId &&
            scope.moi === context.moi && scope.assembly === context.variant.evidenceQueryAllele.assembly)) return false;
    return true;
}
// A selector refusal is a receipt, not a completed scientific evaluation.
function bp2OptionalSelectionLinked(selection, source, b, reason) {
    if (selection?.outcome === 'GENERAL_POLICY_SELECTED') return bp2SelectionLinked(selection, source, b);
    const required = ['schemaVersion', 'contextId', 'criterionIds', 'catalogId', 'catalogVersion', 'catalogSha256', 'searchedAt', 'searchScope', 'outcome', 'reasonCode', 'selectionId'];
    const optional = ['catalogEntryId', 'scope', 'disposition', 'baseRuleRelationship'];
    if (!selection || !required.every(k => Object.hasOwn(selection, k)) || Object.keys(selection).some(k => ![...required, ...optional].includes(k)) ||
        selection.schemaVersion !== 'acmg-specification-selection-v1' || selection.contextId !== source.context.contextId ||
        !ps2Same(selection.criterionIds, [BP2_CRITERION]) || !['SELECTION_INPUT_INCOMPLETE', 'SPECIALIZED_POLICY_GAP'].includes(selection.outcome) ||
        selection.reasonCode !== reason || !ps2Text(reason) || selection.selectionId !== 'cspec-selection-sha256-' + bp2IdentityHash(ps2Without(selection, ['selectionId'])) ||
        selection.catalogSha256 !== b.catalogSha256 || !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], source.catalog[k]))) return false;
    if (!Object.hasOwn(selection, 'catalogEntryId')) return optional.every(k => !Object.hasOwn(selection, k));
    const entries = source.catalog.entries.filter(e => e.entryId === selection.catalogEntryId);
    return entries.length === 1 && entries[0].criterionId === BP2_CRITERION && Object.hasOwn(selection, 'scope') &&
        ['scope', 'disposition', 'baseRuleRelationship'].every(k => !Object.hasOwn(selection, k) || ps2Same(selection[k], entries[0][k]));
}
function bp2CompletedLinked(core, b) {
    const source = core.sourceBundle, o = source.observations[0], facts = o.facts, policy = source.policy;
    const selection = core.policySelection, evidence = core.evidence, e = core.evaluation, trace = e?.deterministicTrace;
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || !evidence || !e || !trace || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.direction !== 'BENIGN' || core.observationCount !== 1 ||
        Object.hasOwn(core, 'pm3Points') || !ps2Same(core.alleleRoles, facts.binding) || core.derivedPhase !== o.phase.conclusion ||
        core.caseId !== facts.caseId || core.occurrenceId !== facts.occurrenceId ||
        ![evidence, e].every(v => v.contextId === source.context.contextId) || !bp2SelectionLinked(selection, source, b)) return false;
    if (evidence.schemaVersion !== 'atomic-evidence-v2' || evidence.canonicalizationProfile !== 'varvis-json-c14n-v1' || evidence.evidenceType !== 'CASE_OBSERVATION' ||
        !id(evidence.evidenceId, 'evidence-sha256-') || !id(e.evaluationId, 'evaluation-sha256-') ||
        e.schemaVersion !== 'criterion-evaluation-v2' || e.canonicalizationProfile !== 'varvis-json-c14n-v1' || e.criterionId !== BP2_CRITERION ||
        e.baseStrength !== 'SUPPORTING' || e.appliedStrength !== 'SUPPORTING' ||
        !ps2Same(e.evaluationResult, { status: 'MET', reasonCode: 'BP2_SINGLE_CONFIRMED_PHASE_INDEPENDENT_P' }) ||
        !ps2Same(e.criterionApplicability, { status: 'APPLICABLE' }) || !ps2Same(e.evidenceIds, [evidence.evidenceId]) ||
        !ps2Same(e.ruleBinding, { currentGeneralDesignation: 'BP2', ruleRegistryId: source.registryBinding.registryId,
            ruleRegistrySha256: source.registryBinding.registrySha256, cspecSelectionId: selection.selectionId, authoritySourceIds: policy.authoritySourceIds })) return false;
    const observationSha = bp2IdentityHash(o), date = o.assessment.review.date;
    const locators = BP2_PARTS.flatMap(part => o[part].sources.map(document => ({ locatorType: 'DOCUMENT_SECTION', locatorValue: document.locator,
        sourceRecordId: document.recordId, artifactSha256: document.rawSha256, retrievedAt: document.acquiredAt })));
    if (!ps2Same(evidence.source, { sourceId: 'REVIEWED_CASE_FAMILY_OBSERVATION', resourceVersion: facts.observationVersion,
        resourceSha256: observationSha, retrievedAt: date, sourceRecords: locators.map(locator => ({ recordId: locator.sourceRecordId, locator })), sourceLocators: locators }) ||
        evidence.provenance?.sourcePayloadSha256 !== observationSha || evidence.provenance?.producedAt !== date ||
        evidence.provenance?.identityProjectionSha256 !== evidence.evidenceId.slice('evidence-sha256-'.length) ||
        evidence.queryOutcome?.queryFingerprint !== bp2IdentityHash(facts.binding) || evidence.queryOutcome?.recordCount !== locators.length) return false;
    const parameters = { observationSha256: observationSha, pairId: b.pairBinding.pairId, factsSha256: b.partPins.facts,
        assessmentSha256: b.assessmentSha256, policySha256: b.policySha256, derivedPhase: o.phase.conclusion, occurrenceId: facts.occurrenceId };
    return trace.executionStatus === 'COMPLETED' && trace.evaluatorId === 'reviewed-single-trio-phase-bp2' && trace.evaluatorVersion === '1.0.0' &&
        trace.algorithmId === policy.policyId && trace.algorithmVersion === policy.policyVersion && ps2Sha(trace.traceSha256) &&
        ps2Same(trace.inputEvidenceIds, [evidence.evidenceId]) &&
        ps2Same(trace.parameters, Object.entries(parameters).map(([name, value]) => ({ name, value }))) &&
        ps2Same(trace.steps, [{ stepCode: e.evaluationResult.reasonCode, outcome: 'TRUE', inputEvidenceIds: [evidence.evidenceId] }]);
}
export function bp2SavedRun(run) {
    if (pp4ScopeClaim(run)) return false;
    if (bs4ScopeClaim(run)) return false;
    if (pp1ScopeClaim(run)) return false;
    try {
        if (!bp2ScopeClaim(run) || ps2ScopeClaim(run) || pm6ScopeClaim(run) || pm3ScopeClaim(run) || !['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status) ||
            !ps2Text(run.runId) || !ps2Text(run.analysisId) || !ps2Closed(run) || run.clinicalValidationCompleted !== false || run.candidateOrderingChanged !== false ||
            run.acceptanceScope !== 'BP2_PHASE_DEVELOPMENT' || run.declarationVersion !== BP2_DECLARATION || !bp2ProfileValid(run.executionProfile) ||
            !ps2Same(run.enabledCriteria, [BP2_CRITERION]) || run.requestedN !== 1 || run.selectedCount !== 1 || run.candidates?.length !== 1 || run.candidateIds?.length !== 1 || run.bundles?.length !== 1) return false;
        const candidate = run.candidates[0], a = candidate.attempts?.[0], artifact = run.bundles[0], core = a.bp2, source = core.sourceBundle, p = artifact.preparation, b = p.materialBinding;
        if (candidate.candidateId !== run.candidateIds[0] || candidate.attempts.length !== 1 || !ps2Keys(a, ['bp2', 'criterionEvaluations', 'blockers']) ||
            artifact.schemaVersion !== 'bp2-phase-business-artifact-v1' || artifact.runId !== run.runId || artifact.analysisId !== run.analysisId || artifact.candidateId !== candidate.candidateId ||
            artifact.classificationEnabled !== false || artifact.adoptionSupported !== false || artifact.coreDigestKind !== 'RAW_BYTES_SHA256' || artifact.artifactIdentityKind !== 'CANONICAL_WITH_CORE_RAW_DIGEST' || !ps2Sha(artifact.coreRawSha256) ||
            artifact.bundleId !== 'bp2-business-artifact-sha256-' + bp2IdentityHash(ps2Without(artifact, ['core', 'bundleId'])) || !ps2Same(artifact.core, core) ||
            core.criterionId !== BP2_CRITERION || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.resultMode !== 'CORE_RESULT_ONLY' || core.classificationEnabled !== false || core.adoptionSupported !== false ||
            !ps2Same(a.criterionEvaluations, core.criterionEvaluations) || core.criterionEvaluations?.length !== 1 || core.criterionEvaluations[0].criterionId !== BP2_CRITERION ||
            !bp2PreparationValid(p, run.executionProfile, run.analysisId, candidate.candidateId, p.sampleId) || run.dataClass !== b.context.dataClass || source.observations?.length !== 1 ||
            !ps2Same(source.context, b.context) || !ps2Same(source.observations[0], p.reviewedObservation) || !ps2Same(source.registryBinding, b.registryBinding) ||
            !ps2Same(source.policy.partPins, b.partPins) || source.policy.assessmentSha256 !== b.assessmentSha256 || bp2IdentityHash(source.policy) !== b.policySha256 ||
            source.catalogSha256 !== b.catalogSha256 || bp2IdentityHash(source.catalog) !== b.catalogSha256 || bp2IdentityHash(source) !== b.materialPins.bundleCanonicalSha256) return false;
        const evaluation = core.criterionEvaluations[0];
        if (core.evaluation) return run.status === 'COMPLETED' && ps2Same(core.evaluation, evaluation) && bp2CompletedLinked(core, b);
        return run.status === 'COMPLETED_WITH_BLOCKERS' && ps2Keys(evaluation, ['criterionId', 'evaluationResult', 'criterionApplicability']) &&
            ps2Keys(core, ['criterionId', 'resultMode', 'sourceOfTruth', 'classificationEnabled', 'adoptionSupported', 'sourceBundle', 'evaluationResult', 'criterionApplicability', 'criterionEvaluations', ...(Object.hasOwn(core, 'policySelection') ? ['policySelection'] : [])]) &&
            (!Object.hasOwn(core, 'policySelection') || bp2OptionalSelectionLinked(core.policySelection, source, b, core.evaluationResult?.reasonCode)) &&
            ps2Keys(core.evaluationResult, ['status', 'reasonCode']) && ps2Keys(core.criterionApplicability, ['status', 'reasonCode']) &&
            core.evaluationResult.status === 'NOT_EVALUATED' && core.criterionApplicability.status === 'UNKNOWN' &&
            typeof core.evaluationResult.reasonCode === 'string' && core.evaluationResult.reasonCode.trim().length > 0 &&
            core.evaluationResult.reasonCode === core.criterionApplicability.reasonCode && ps2Same(core.evaluationResult, evaluation.evaluationResult) && ps2Same(core.criterionApplicability, evaluation.criterionApplicability);
    } catch { return false; }
}
export function bp2EvidenceEntry(core) {
    const source = core?.sourceBundle, o = source?.observations?.[0], c = source?.context;
    const evaluation = core?.evaluation || core?.criterionEvaluations?.[0];
    return { evaluation, scopeLabel: 'BP2 良性支持证据', contextLabel: [c?.gene?.symbol, c?.disease?.label, c?.moi].filter(Boolean).join(' · '),
        reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [['来源标签', core?.resultMode], ['本次相位', core?.derivedPhase ?? '未记录'], ['目标 / 伙伴原始角色', o?.facts?.alleles],
            ['相位原结论与亲本方向', o?.phase], ['伙伴外部分级及独立性（不是目标分类）', o?.partnerClassification],
            ['疾病适用与外显率材料原值', o?.diseaseApplicability], ['六条记录 / 亲缘 / 来源原文', o?.facts], ['已有审核（不是本次科学批准）', o?.assessment],
            ['结论边界', '单例最大 Supporting；不是目标变异良性分类，不累计、不自动采用。未评价不补0或强度。']].map(([label, value]) => [label, value && typeof value === 'object' ? JSON.stringify(value) : value]), raw: core };
}

// PP1 transport verification only; scientific applicability remains the backend core's decision.
export const pp1IdentityHash = ps2IdentityHash;
const PP1_CRITERION = 'ACMG_AMP_2015:PP1';
const PP1_DECLARATION = 'ordinary-pp1-segregation-v1';
const PP1_TOKEN = 'PP1_SEGREGATION_CONTEXT@1.0.0:';
const PP1_PARTS = ['facts', 'segregationReview', 'diseaseApplicability', 'locusAttribution'];
const PP1_SELECTION_KEYS = ['candidateId', 'contextId', 'alleleId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId', 'materialBindingSha256', 'preparationToken', 'policyVersion', 'confirmed'];
export function pp1ScopeClaim(value) {
    if (!value || typeof value !== 'object') return typeof value === 'string' &&
        (value.startsWith('PP1_SEGREGATION_CONTEXT@') || value.startsWith('ordinary-pp1-') || value.startsWith('acmg-pp1-segregation-development') || value.startsWith('pp1-segregation-business-') || value.startsWith('pp1-segregation-context-preparation') || value === 'PP1_SEGREGATION_DEVELOPMENT');
    return Object.entries(value).some(([k, v]) => k === 'pp1' && (v?.version === 'pp1-selected-literature-v1' || v?.privateOriginalAvailable === true)
        ? false : ['pp1Preparation', 'pp1Selection', 'pp1MaterialPins', 'pp1'].includes(k) || pp1ScopeClaim(v));
}
function pp1ProfileValid(p) {
    return ps2Keys(p, ['profileId', 'profileVersion', 'requiredDeclaration', 'formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported', 'clinicalValidationCompleted', 'enabledCriteria', 'pp1MaterialPins', 'contextPreparation', 'manifestSha256']) &&
        p.profileId === 'acmg-pp1-segregation-development-v1' && p.profileVersion === '1.0.0' && p.requiredDeclaration === PP1_DECLARATION && ps2Closed(p) && p.clinicalValidationCompleted === false &&
        ps2Same(p.enabledCriteria, [PP1_CRITERION]) && ps2Same(p.contextPreparation, { schemaVersion: 'pp1-segregation-context-preparation-v1', version: '1.0.0' }) &&
        ps2Keys(p.pp1MaterialPins, ['bundleRawSha256', 'bundleCanonicalSha256']) && Object.values(p.pp1MaterialPins).every(ps2Sha) &&
        p.manifestSha256 === pp1IdentityHash(ps2Without(p, ['manifestSha256']));
}
function pp1AlleleValid(v) {
    const normalizer = Object.fromEntries(['method', 'methodVersion', 'referenceSequenceSha256', 'refValidation'].map(k => [k, v.normalization?.[k]]));
    return v.alleleId === 'allele-sha256-' + pp1IdentityHash({ normalizedAllele: v.normalizedAllele, normalizer });
}
function pp1PreparationValid(p, profile, analysisId, candidateId, sampleId) {
    if (!ps2Keys(p, ['schemaVersion', 'version', 'policyVersion', 'status', 'scientificApplicability', 'analysisId', 'sampleId', 'candidateId', 'materialBindingSha256', 'criterionPolicySelected', 'predictionCompatibilityClaimed', 'materialBinding', 'patientContext', 'reviewedObservation', 'preparationToken']) ||
        p.schemaVersion !== 'pp1-segregation-context-preparation-v1' || p.version !== '1.0.0' || p.policyVersion !== '1.0.0-pp1-segregation-development' ||
        p.status !== 'BOUND_NOT_EVALUATED' || p.scientificApplicability !== 'NOT_EVALUATED' || p.criterionPolicySelected !== false || p.predictionCompatibilityClaimed !== false ||
        p.analysisId !== analysisId || p.candidateId !== candidateId || p.sampleId !== sampleId || !ps2Text(sampleId) ||
        p.preparationToken !== PP1_TOKEN + pp1IdentityHash(ps2Without(p, ['preparationToken']))) return false;
    const b = p.materialBinding, c = b.context, patient = c.patientContext, o = p.reviewedObservation, f = o.facts, family = b.familyBinding;
    if (!ps2Keys(b, ['schemaVersion', 'analysisId', 'sampleId', 'dataClass', 'materialPins', 'context', 'familyBinding', 'publishedInputIdentity', 'candidateId', 'partPins', 'assessmentSha256', 'policySha256', 'catalogSha256', 'registryBinding', 'executionProfile']) ||
        b.schemaVersion !== 'pp1-segregation-business-binding-v1' || b.analysisId !== analysisId || b.sampleId !== sampleId || b.dataClass !== c.dataClass || p.materialBindingSha256 !== pp1IdentityHash(b) ||
        !ps2Same(b.materialPins, profile.pp1MaterialPins) || !ps2Same(b.executionProfile, profile) || b.candidateId !== candidateId || !ps2Same(patient, p.patientContext) ||
        !ps2Keys(o, [...PP1_PARTS, 'assessment']) || !ps2Keys(b.partPins, PP1_PARTS) ||
        !PP1_PARTS.every(k => ps2Sha(b.partPins[k]) && pp1IdentityHash(o[k]) === b.partPins[k] && ps2Same(o[k].binding, family)) ||
        !ps2Same(o.assessment.partPins, b.partPins) || o.assessment.contentSha256 !== pp1IdentityHash(ps2Without(o.assessment, ['contentSha256'])) ||
        pp1IdentityHash(o.assessment) !== b.assessmentSha256 || !['assessmentSha256', 'policySha256', 'catalogSha256'].every(k => ps2Sha(b[k])) ||
        !ps2Sha(b.registryBinding?.registrySha256) || c.schemaVersion !== 'evidence-context-v3' || c.canonicalizationProfile !== 'varvis-json-c14n-v1' ||
        !['patientContextId', 'familyContextId', 'role', 'sex', 'affectedStatus'].every(k => ps2Text(patient[k])) ||
        !ps2Same(c.variant, f.variant) || !pp1AlleleValid(c.variant)) return false;
    const projection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass, scope: c.scope,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: patient };
    if (Object.hasOwn(c, 'transcript')) projection.transcript = { accession: c.transcript.accession, version: c.transcript.version };
    const expected = { observationId: f.observationId, occurrenceId: f.occurrenceId, caseId: f.caseId, contextId: c.contextId,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi,
        patientContextId: patient.patientContextId, familyContextId: patient.familyContextId };
    const origin = b.publishedInputIdentity, probands = f.members.filter(m => m.proband === true);
    if (probands.length !== 1) return false;
    const member = probands[0], records = f.records.filter(r => ps2Same(r.memberId, member.memberId));
    return c.contextId === 'context-sha256-' + pp1IdentityHash(projection) && ps2Same(family, expected) &&
        ['caseId', 'observationId', 'occurrenceId'].every(k => ps2Text(f[k])) && ps2Same(f.familyId, patient.familyContextId) &&
        member.role === 'CHILD' && patient.role === 'PROBAND' && ps2Same(member.memberId, patient.patientContextId) && member.sampleId === sampleId && ps2Same(member.affectedStatus, patient.affectedStatus) &&
        records.length === 1 && records[0].sampleId === sampleId && ps2Same(records[0].allele, c.variant.inputAllele) && ps2Same(records[0].sourcePersonId, member.sourcePersonId) &&
        origin.analysisId === analysisId && origin.sampleId === sampleId && origin.inputAssembly === c.variant.inputAllele.assembly && ps2Text(origin.analysisMode) &&
        ['uploadSha256', 'candidatesRawSha256', 'executionSourceSha256'].every(k => ps2Sha(origin[k]));
}

export function pp1ScopeIssue(o, analysisId = o?.analysisId) {
    if (!pp1ScopeClaim(o)) return null;
    if (pp4ScopeClaim(o)) return '混入 PP4 身份，不授予提交许可。';
    try {
        const p = o.pp1Preparation, s = o.pp1Selection;
        if (bp2ScopeClaim(o) || ps2ScopeClaim(o) || pm6ScopeClaim(o) || pm3ScopeClaim(o) || o.analysisId !== analysisId || !ps2Text(analysisId) || o.acceptanceScope !== 'PP1_SEGREGATION_DEVELOPMENT' || o.requiredDeclaration !== PP1_DECLARATION ||
            !ps2Closed(o) || o.clinicalValidationCompleted !== false || o.runtimeConfigured !== true || o.criterionPolicySelected !== false || o.predictionCompatibilityClaimed !== false ||
            o.requestedN !== 1 || o.selectedCount !== 1 || !ps2Same(o.enabledCriteria, [PP1_CRITERION]) || o.candidateIds?.length !== 1 || !ps2Text(o.candidateIds[0]) ||
            ['candidates', 'recommendations', 'association', 'calibration', 'revelApplication', 'runtimeBinding', 'hypotheses'].some(k => Object.hasOwn(o, k)) ||
            !pp1ProfileValid(o.executionProfile) || !pp1PreparationValid(p, o.executionProfile, analysisId, o.candidateIds[0], o.sampleId) || !ps2Keys(s, PP1_SELECTION_KEYS)) return 'PP1 当前核家系材料或执行身份不一致，请重新读取；未授予提交许可。';
        const expected = { candidateId: p.candidateId,
            ...Object.fromEntries(['contextId', 'alleleId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId'].map(k => [k, p.materialBinding.familyBinding[k]])),
            materialBindingSha256: p.materialBindingSha256, preparationToken: p.preparationToken, policyVersion: p.policyVersion, confirmed: false };
        return ps2Same(expected, s) ? null : 'PP1 当前目标/家系选择身份不一致，请重新读取。';
    } catch { return 'PP1 当前材料身份缺失或摘要不一致，请重新读取；未提交评价。'; }
}
export function pp1RetryEligible(run, analysisId) {
    if (pp4ScopeClaim(run)) return false;
    try { return !bp2ScopeClaim(run) && !ps2ScopeClaim(run) && !pm6ScopeClaim(run) && !pm3ScopeClaim(run) && (!run?.acceptanceScope || run.acceptanceScope === 'PP1_SEGREGATION_DEVELOPMENT') &&
        (!run?.executionProfile || pp1ProfileValid(run.executionProfile)) && (!run?.enabledCriteria || ps2Same(run.enabledCriteria, [PP1_CRITERION])) &&
        (!run?.declarationVersion || run.declarationVersion === PP1_DECLARATION) && run?.analysisId === analysisId && /^[0-9a-f-]{36}$/.test(run.runId) &&
        (run.status === 'FAILED' || (run.status === 'COMPLETED_WITH_BLOCKERS' && pp1SavedRun(run))); } catch { return false; }
}
export function buildPp1Declaration(options, analysisId, confirmed, retryRun) {
    if (!pp1ScopeClaim(options) || pp1ScopeIssue(options, analysisId) || confirmed !== true) throw new Error('PP1 请读取当前核家系材料并明确确认后提交。');
    if (retryRun !== undefined && !pp1RetryEligible(retryRun, analysisId)) throw new Error('PP1 仅可显式重试本分析已选 FAILED 或 COMPLETED_WITH_BLOCKERS 运行。');
    const context = options.pp1Preparation.materialBinding.context;
    return { topN: 1, declarationVersion: PP1_DECLARATION, sampleId: options.sampleId, dataClass: context.dataClass,
        patient: Object.fromEntries(['patientContextId', 'role', 'sex', 'familyContextId'].map(k => [k, context.patientContext[k]])), hypotheses: [],
        pp1Selection: { ...options.pp1Selection, confirmed: true }, ...(retryRun ? { retryOf: retryRun.runId } : {}) };
}
// Transport linkage from Pp1SegregationCriterionEvaluator/carrier and the selector receipt.
// This checks returned references, not GT/segregation/classification/AF or a scientific decision.
function pp1SelectionLinked(selection, source, b) {
    const policy = source.policy, catalog = source.catalog;
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || selection.contextId !== source.context.contextId ||
        selection.selectionId !== 'cspec-selection-sha256-' + pp1IdentityHash(ps2Without(selection, ['selectionId']))) return false;
    if (selection.schemaVersion !== 'acmg-specification-selection-v1' || !id(selection.selectionId, 'cspec-selection-sha256-') ||
        selection.outcome !== 'GENERAL_POLICY_SELECTED' || !ps2Same(selection.criterionIds, [PP1_CRITERION]) ||
        !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], catalog[k])) || selection.catalogSha256 !== b.catalogSha256 ||
        !['policyId', 'policyVersion'].every(k => ps2Text(selection[k]) && selection[k] === policy[k]) || selection.policySha256 !== b.policySha256 ||
        policy.schemaVersion !== 'pp1-reviewed-segregation-policy-v1' || policy.mode !== 'SINGLE_NUCLEAR_FAMILY_AD' || !ps2Same(policy.criterionIds, [PP1_CRITERION])) return false;
    const entries = catalog.entries.filter(v => v.entryId === selection.catalogEntryId);
    if (entries.length !== 1) return false;
    const entry = entries[0], context = source.context;
    if (entry.criterionId !== PP1_CRITERION || !['scope', 'disposition', 'baseRuleRelationship', 'sourceIds'].every(k => ps2Same(selection[k], entry[k])) ||
        !ps2Same(entry.policyBinding, { policyId: policy.policyId, policyVersion: policy.policyVersion, policySha256: b.policySha256 }) ||
        ![selection.scope, policy.scope].every(scope => scope.hgncId === context.gene.hgncId && scope.mondoId === context.disease.mondoId &&
            scope.moi === context.moi && scope.assembly === context.variant.evidenceQueryAllele.assembly)) return false;
    return true;
}
// A selector refusal is a receipt, not a completed scientific evaluation.
function pp1OptionalSelectionLinked(selection, source, b, reason) {
    if (selection?.outcome === 'GENERAL_POLICY_SELECTED') return pp1SelectionLinked(selection, source, b);
    const required = ['schemaVersion', 'contextId', 'criterionIds', 'catalogId', 'catalogVersion', 'catalogSha256', 'searchedAt', 'searchScope', 'outcome', 'reasonCode', 'selectionId'];
    const optional = ['catalogEntryId', 'scope', 'disposition', 'baseRuleRelationship'];
    if (!selection || !required.every(k => Object.hasOwn(selection, k)) || Object.keys(selection).some(k => ![...required, ...optional].includes(k)) ||
        selection.schemaVersion !== 'acmg-specification-selection-v1' || selection.contextId !== source.context.contextId ||
        !ps2Same(selection.criterionIds, [PP1_CRITERION]) || !['SELECTION_INPUT_INCOMPLETE', 'SPECIALIZED_POLICY_GAP'].includes(selection.outcome) ||
        ![selection.reasonCode, 'PP1_' + selection.reasonCode].includes(reason) || !ps2Text(reason) || selection.selectionId !== 'cspec-selection-sha256-' + pp1IdentityHash(ps2Without(selection, ['selectionId'])) ||
        selection.catalogSha256 !== b.catalogSha256 || !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], source.catalog[k]))) return false;
    if (!Object.hasOwn(selection, 'catalogEntryId')) return optional.every(k => !Object.hasOwn(selection, k));
    const entries = source.catalog.entries.filter(e => e.entryId === selection.catalogEntryId);
    return entries.length === 1 && entries[0].criterionId === PP1_CRITERION && Object.hasOwn(selection, 'scope') &&
        ['scope', 'disposition', 'baseRuleRelationship'].every(k => !Object.hasOwn(selection, k) || ps2Same(selection[k], entries[0][k]));
}
// Report consistency and original source references only; never infer counted from GT/phenotype.
function pp1EventsLinked(core) {
    const c = core.sourceBundle.context, f = core.sourceBundle.observations[0].facts, events = core.transmissionEvents;
    const members = f.members, children = members.filter(m => m.role === 'CHILD'), fathers = members.filter(m => m.role === 'FATHER'), mothers = members.filter(m => m.role === 'MOTHER');
    if (fathers.length !== 1 || mothers.length !== 1 || !Array.isArray(events) || events.length !== children.length || !events.length ||
        !ps2Same(core.familyId, f.familyId) || !Number.isSafeInteger(core.rawPoints) || core.rawPoints < 1 || core.rawPoints > 5 || core.appliedPoints !== core.rawPoints ||
        events.filter(e => e.counted === true).length !== core.rawPoints || !ps2Same(core.pp4, { status: 'NOT_EVALUATED', reasonCode: 'PP4_OUTSIDE_THIS_CORE' })) return false;
    const groups = core.sourceBundle.observations[0].locusAttribution.usedEvidence;
    if (!ps2Text(core.evidenceGroupId) || !groups.length || !groups.every(g => ps2Same(g.evidenceGroupId, core.evidenceGroupId))) return false;
    const seen = new Set();
    for (const event of events) {
        if (!ps2Keys(event, ['eventId', 'counted', 'reasonCode', 'identity', 'records', 'parentage', 'clinical', 'parentClinical']) ||
            !ps2Keys(event.identity, ['familyId', 'alleleId', 'parentMemberId', 'childMemberId']) || typeof event.counted !== 'boolean' ||
            event.reasonCode !== (event.counted ? 'AFFECTED_HET_TRANSMISSION' : 'UNAFFECTED_REFERENCE_NOT_COUNTED') ||
            !ps2Same(event.identity.familyId, f.familyId) || !ps2Same(event.identity.alleleId, c.variant.alleleId) ||
            event.eventId !== 'pp1-transmission-sha256-' + pp1IdentityHash(event.identity) || seen.has(event.identity.childMemberId.normalize('NFC'))) return false;
        seen.add(event.identity.childMemberId.normalize('NFC'));
        const child = children.find(m => ps2Same(m.memberId, event.identity.childMemberId));
        const parent = [fathers[0], mothers[0]].find(m => ps2Same(m.memberId, event.identity.parentMemberId));
        if (!child || !parent) return false;
        const other = parent === fathers[0] ? mothers[0] : fathers[0];
        const records = [parent, child, other].map(m => f.records.filter(r => ps2Same(r.memberId, m.memberId)));
        const relations = [fathers[0], mothers[0]].map(m => f.parentage.filter(r => ps2Same(r.parentMemberId, m.memberId) && ps2Same(r.childMemberId, child.memberId)));
        if (records.some(r => r.length !== 1) || relations.some(r => r.length !== 1) || !ps2Same(event.records, records.map(r => r[0])) ||
            !ps2Same(event.parentage, relations.map(r => r[0])) || !ps2Same(event.clinical, child.clinical) ||
            !ps2Same(event.parentClinical, [fathers[0].clinical, mothers[0].clinical])) return false;
    }
    return true;
}

// RuntimeEvidenceV2Adapter.identityProjection, limited to PP1 saved atomic reports.
// The canonical sort is by JSON text (not by digest). Copies preserve raw input.
function pp1AtomicIdentityHash(evidence) {
    const canonical = v => typeof v === 'string' ? v.normalize('NFC') : Array.isArray(v) ? v.map(canonical)
        : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(k => [k.normalize('NFC'), canonical(v[k])])) : v;
    const sorted = values => values.map(value => ({ value, key: JSON.stringify(canonical(value)) }))
        .sort((a, b) => a.key < b.key ? -1 : a.key > b.key ? 1 : 0).map(item => item.value);
    const withoutTime = locator => ps2Without(locator, ['retrievedAt']);
    return pp1IdentityHash({
        ...ps2Without(evidence, ['evidenceId', 'deduplicationKey', 'provenance', 'conflict', 'review']),
        source: { ...ps2Without(evidence.source, ['retrievedAt']),
            sourceLocators: sorted(evidence.source.sourceLocators.map(withoutTime)),
            sourceRecords: sorted(evidence.source.sourceRecords.map(record => ({ recordId: record.recordId, locator: withoutTime(record.locator) }))) },
        resourceExecution: ps2Without(evidence.resourceExecution, ['attemptedAt', 'completedAt']),
        measurements: sorted(evidence.measurements), derivedFromEvidenceIds: sorted(evidence.derivedFromEvidenceIds),
        provenance: { producerId: 'varvis-runtime-v2-adapter', producerVersion: '2.0.0-e2', sourcePayloadSha256: evidence.provenance.sourcePayloadSha256 },
    });
}
function pp1CompletedLinked(core, b) {
    const source = core.sourceBundle, o = source.observations[0], facts = o.facts, policy = source.policy;
    const selection = core.policySelection, evidence = core.evidence, e = core.evaluation, trace = e?.deterministicTrace;
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || !evidence || !e || !trace || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.direction !== 'PATHOGENIC' || core.observationCount !== 1 ||
        !pp1EventsLinked(core) ||
        core.caseId !== facts.caseId || core.occurrenceId !== facts.occurrenceId ||
        ![evidence, e].every(v => v.contextId === source.context.contextId) || !pp1SelectionLinked(selection, source, b)) return false;
    if (e.evaluationId !== 'evaluation-sha256-' + pp1IdentityHash(ps2Without(e, ['evaluationId', 'conflict', 'review'])) ||
        trace.traceSha256 !== pp1IdentityHash(ps2Without(trace, ['traceSha256'])) ||
        evidence.evidenceId !== 'evidence-sha256-' + pp1AtomicIdentityHash(evidence) ||
        evidence.resourceExecution?.status !== 'SUCCEEDED' || evidence.resourceAvailability?.status !== 'AVAILABLE') return false;
    if (evidence.schemaVersion !== 'atomic-evidence-v2' || evidence.canonicalizationProfile !== 'varvis-json-c14n-v1' || evidence.evidenceType !== 'CASE_OBSERVATION' ||
        !id(evidence.evidenceId, 'evidence-sha256-') || !id(e.evaluationId, 'evaluation-sha256-') ||
        e.schemaVersion !== 'criterion-evaluation-v2' || e.canonicalizationProfile !== 'varvis-json-c14n-v1' || e.criterionId !== PP1_CRITERION ||
        e.baseStrength !== 'SUPPORTING' || !['SUPPORTING', 'MODERATE', 'STRONG'].includes(e.appliedStrength) ||
        !ps2Same(e.evaluationResult, { status: 'MET', reasonCode: 'PP1_REVIEWED_NUCLEAR_FAMILY_TRANSMISSIONS' }) ||
        !ps2Same(e.criterionApplicability, { status: 'APPLICABLE' }) || !ps2Same(e.evidenceIds, [evidence.evidenceId]) ||
        !ps2Same(e.ruleBinding, { currentGeneralDesignation: 'PP1', ruleRegistryId: source.registryBinding.registryId,
            ruleRegistrySha256: source.registryBinding.registrySha256, cspecSelectionId: selection.selectionId, authoritySourceIds: policy.authoritySourceIds })) return false;
    const observationSha = pp1IdentityHash(o), date = o.assessment.review.date;
    const locators = PP1_PARTS.flatMap(part => o[part].sources.map(document => ({ locatorType: 'DOCUMENT_SECTION', locatorValue: document.locator,
        sourceRecordId: document.recordId, artifactSha256: document.rawSha256, retrievedAt: document.acquiredAt })));
    if (!ps2Same(evidence.source, { sourceId: 'REVIEWED_CASE_FAMILY_OBSERVATION', resourceVersion: facts.observationVersion,
        resourceSha256: observationSha, retrievedAt: date, sourceRecords: locators.map(locator => ({ recordId: locator.sourceRecordId, locator })), sourceLocators: locators }) ||
        evidence.provenance?.sourcePayloadSha256 !== observationSha || evidence.provenance?.producedAt !== date ||
        evidence.provenance?.identityProjectionSha256 !== evidence.evidenceId.slice('evidence-sha256-'.length) ||
        evidence.queryOutcome?.queryFingerprint !== pp1IdentityHash(facts.binding) || evidence.queryOutcome?.recordCount !== locators.length) return false;
    const parameters = { observationSha256: observationSha, familyId: facts.familyId, factsSha256: b.partPins.facts,
        assessmentSha256: b.assessmentSha256, policySha256: b.policySha256, transmissionEventsSha256: pp1IdentityHash(core.transmissionEvents),
        rawAndAppliedPoints: core.rawPoints, evidenceGroupId: core.evidenceGroupId, occurrenceId: facts.occurrenceId };
    return trace.executionStatus === 'COMPLETED' && trace.evaluatorId === 'reviewed-single-nuclear-family-segregation-pp1' && trace.evaluatorVersion === '1.0.0' &&
        trace.algorithmId === policy.policyId && trace.algorithmVersion === policy.policyVersion && ps2Sha(trace.traceSha256) &&
        ps2Same(trace.inputEvidenceIds, [evidence.evidenceId]) &&
        ps2Same(trace.parameters, Object.entries(parameters).map(([name, value]) => ({ name, value }))) &&
        ps2Same(trace.steps, [{ stepCode: e.evaluationResult.reasonCode, outcome: 'TRUE', inputEvidenceIds: [evidence.evidenceId] }]);
}
export function pp1SavedRun(run) {
    if (pp4ScopeClaim(run)) return false;
    try {
        if (bs4ScopeClaim(run) || bp2ScopeClaim(run) || !pp1ScopeClaim(run) || ps2ScopeClaim(run) || pm6ScopeClaim(run) || pm3ScopeClaim(run) || !['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status) ||
            !ps2Text(run.runId) || !ps2Text(run.analysisId) || !ps2Closed(run) || run.clinicalValidationCompleted !== false || run.candidateOrderingChanged !== false ||
            run.acceptanceScope !== 'PP1_SEGREGATION_DEVELOPMENT' || run.declarationVersion !== PP1_DECLARATION || !pp1ProfileValid(run.executionProfile) ||
            !ps2Same(run.enabledCriteria, [PP1_CRITERION]) || run.requestedN !== 1 || run.selectedCount !== 1 || run.candidates?.length !== 1 || run.candidateIds?.length !== 1 || run.bundles?.length !== 1) return false;
        const candidate = run.candidates[0], a = candidate.attempts?.[0], artifact = run.bundles[0], core = a.pp1, source = core.sourceBundle, p = artifact.preparation, b = p.materialBinding;
        if (candidate.candidateId !== run.candidateIds[0] || candidate.attempts.length !== 1 || !ps2Keys(a, ['pp1', 'criterionEvaluations', 'blockers']) ||
            artifact.schemaVersion !== 'pp1-segregation-business-artifact-v1' || artifact.runId !== run.runId || artifact.analysisId !== run.analysisId || artifact.candidateId !== candidate.candidateId ||
            artifact.classificationEnabled !== false || artifact.adoptionSupported !== false || artifact.coreDigestKind !== 'RAW_BYTES_SHA256' || artifact.artifactIdentityKind !== 'CANONICAL_WITH_CORE_RAW_DIGEST' || !ps2Sha(artifact.coreRawSha256) ||
            artifact.bundleId !== 'pp1-business-artifact-sha256-' + pp1IdentityHash(ps2Without(artifact, ['core', 'bundleId'])) || !ps2Same(artifact.core, core) ||
            core.criterionId !== PP1_CRITERION || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.resultMode !== 'CORE_RESULT_ONLY' || core.classificationEnabled !== false || core.adoptionSupported !== false ||
            !ps2Same(a.criterionEvaluations, core.criterionEvaluations) || core.criterionEvaluations?.length !== 1 || core.criterionEvaluations[0].criterionId !== PP1_CRITERION ||
            !pp1PreparationValid(p, run.executionProfile, run.analysisId, candidate.candidateId, p.sampleId) || run.dataClass !== b.context.dataClass || source.observations?.length !== 1 ||
            !ps2Same(source.context, b.context) || !ps2Same(source.observations[0], p.reviewedObservation) || !ps2Same(source.registryBinding, b.registryBinding) ||
            !ps2Same(source.policy.partPins, b.partPins) || source.policy.assessmentSha256 !== b.assessmentSha256 || pp1IdentityHash(source.policy) !== b.policySha256 ||
            source.catalogSha256 !== b.catalogSha256 || pp1IdentityHash(source.catalog) !== b.catalogSha256 || pp1IdentityHash(source) !== b.materialPins.bundleCanonicalSha256) return false;
        const evaluation = core.criterionEvaluations[0];
        if (core.evaluation) return run.status === 'COMPLETED' && ps2Same(core.evaluation, evaluation) && pp1CompletedLinked(core, b);
        return run.status === 'COMPLETED_WITH_BLOCKERS' && ps2Keys(evaluation, ['criterionId', 'evaluationResult', 'criterionApplicability']) &&
            ps2Keys(core, ['criterionId', 'resultMode', 'sourceOfTruth', 'classificationEnabled', 'adoptionSupported', 'sourceBundle', 'evaluationResult', 'criterionApplicability', 'criterionEvaluations', ...(Object.hasOwn(core, 'policySelection') ? ['policySelection'] : [])]) &&
            (!Object.hasOwn(core, 'policySelection') || pp1OptionalSelectionLinked(core.policySelection, source, b, core.evaluationResult?.reasonCode)) &&
            ps2Keys(core.evaluationResult, ['status', 'reasonCode']) && ps2Keys(core.criterionApplicability, ['status', 'reasonCode']) &&
            core.evaluationResult.status === 'NOT_EVALUATED' && core.criterionApplicability.status === 'UNKNOWN' &&
            typeof core.evaluationResult.reasonCode === 'string' && core.evaluationResult.reasonCode.trim().length > 0 &&
            core.evaluationResult.reasonCode === core.criterionApplicability.reasonCode && ps2Same(core.evaluationResult, evaluation.evaluationResult) && ps2Same(core.criterionApplicability, evaluation.criterionApplicability);
    } catch { return false; }
}
export function pp1EvidenceEntry(core) {
    const c = core?.sourceBundle?.context;
    const evaluation = core?.evaluation || core?.criterionEvaluations?.[0];
    return { evaluation, scopeLabel: 'PP1 致病方向证据（不是最终分类）', contextLabel: [c?.gene?.symbol, c?.disease?.label, c?.moi].filter(Boolean).join(' · '),
        reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [['核心来源标签', core?.resultMode], ['后端原始点数', core?.rawPoints ?? '未记录'], ['后端应用点数', core?.appliedPoints ?? '未记录'], ['家系', core?.familyId ?? '未记录'], ['共享证据组', core?.evidenceGroupId ?? '未记录'], ['PP4 边界', core?.pp4 ? JSON.stringify(core.pp4) : '未执行'], ['原始核心（只读）', JSON.stringify(core)]], raw: core };
}

// BS4 transport verification only; scientific applicability remains the backend core's decision.
export const bs4IdentityHash = ps2IdentityHash;
const BS4_CRITERION = 'ACMG_AMP_2015:BS4';
const BS4_DECLARATION = 'ordinary-bs4-nonsegregation-v1';
const BS4_TOKEN = 'BS4_NONSEGREGATION_CONTEXT@1.0.0:';
const BS4_PARTS = ['facts', 'nonSegregationReview', 'diseaseApplicability', 'locusAttribution'];
const BS4_SELECTION_KEYS = ['candidateId', 'contextId', 'alleleId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId', 'materialBindingSha256', 'preparationToken', 'policyVersion', 'confirmed'];
export function bs4ScopeClaim(value) {
    if (!value || typeof value !== 'object') return typeof value === 'string' &&
        (value.startsWith('BS4_NONSEGREGATION_CONTEXT@') || value.startsWith('ordinary-bs4-') || value.startsWith('acmg-bs4-nonsegregation-development') || value.startsWith('bs4-nonsegregation-business-') || value.startsWith('bs4-nonsegregation-context-preparation') || value === 'BS4_NONSEGREGATION_DEVELOPMENT');
    return Object.entries(value).some(([k, v]) => ['bs4Preparation', 'bs4Selection', 'bs4MaterialPins', 'bs4'].includes(k) || bs4ScopeClaim(v));
}
function bs4ProfileValid(p) {
    return ps2Keys(p, ['profileId', 'profileVersion', 'requiredDeclaration', 'formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported', 'clinicalValidationCompleted', 'enabledCriteria', 'bs4MaterialPins', 'contextPreparation', 'manifestSha256']) &&
        p.profileId === 'acmg-bs4-nonsegregation-development-v1' && p.profileVersion === '1.0.0' && p.requiredDeclaration === BS4_DECLARATION && ps2Closed(p) && p.clinicalValidationCompleted === false &&
        ps2Same(p.enabledCriteria, [BS4_CRITERION]) && ps2Same(p.contextPreparation, { schemaVersion: 'bs4-nonsegregation-context-preparation-v1', version: '1.0.0' }) &&
        ps2Keys(p.bs4MaterialPins, ['bundleRawSha256', 'bundleCanonicalSha256']) && Object.values(p.bs4MaterialPins).every(ps2Sha) &&
        p.manifestSha256 === bs4IdentityHash(ps2Without(p, ['manifestSha256']));
}
function bs4AlleleValid(v) {
    const normalizer = Object.fromEntries(['method', 'methodVersion', 'referenceSequenceSha256', 'refValidation'].map(k => [k, v.normalization?.[k]]));
    return v.alleleId === 'allele-sha256-' + bs4IdentityHash({ normalizedAllele: v.normalizedAllele, normalizer });
}
function bs4PreparationValid(p, profile, analysisId, candidateId, sampleId) {
    if (!ps2Keys(p, ['schemaVersion', 'version', 'policyVersion', 'status', 'scientificApplicability', 'analysisId', 'sampleId', 'candidateId', 'materialBindingSha256', 'criterionPolicySelected', 'predictionCompatibilityClaimed', 'materialBinding', 'patientContext', 'reviewedObservation', 'preparationToken']) ||
        p.schemaVersion !== 'bs4-nonsegregation-context-preparation-v1' || p.version !== '1.0.0' || p.policyVersion !== '1.0.0-bs4-nonsegregation-development' ||
        p.status !== 'BOUND_NOT_EVALUATED' || p.scientificApplicability !== 'NOT_EVALUATED' || p.criterionPolicySelected !== false || p.predictionCompatibilityClaimed !== false ||
        p.analysisId !== analysisId || p.candidateId !== candidateId || p.sampleId !== sampleId || !ps2Text(sampleId) ||
        p.preparationToken !== BS4_TOKEN + bs4IdentityHash(ps2Without(p, ['preparationToken']))) return false;
    const b = p.materialBinding, c = b.context, patient = c.patientContext, o = p.reviewedObservation, f = o.facts, family = b.familyBinding;
    if (!ps2Keys(b, ['schemaVersion', 'analysisId', 'sampleId', 'dataClass', 'materialPins', 'context', 'familyBinding', 'publishedInputIdentity', 'candidateId', 'partPins', 'assessmentSha256', 'policySha256', 'catalogSha256', 'registryBinding', 'executionProfile']) ||
        b.schemaVersion !== 'bs4-nonsegregation-business-binding-v1' || b.analysisId !== analysisId || b.sampleId !== sampleId || b.dataClass !== c.dataClass || p.materialBindingSha256 !== bs4IdentityHash(b) ||
        !ps2Same(b.materialPins, profile.bs4MaterialPins) || !ps2Same(b.executionProfile, profile) || b.candidateId !== candidateId || !ps2Same(patient, p.patientContext) ||
        !ps2Keys(o, [...BS4_PARTS, 'assessment']) || !ps2Keys(b.partPins, BS4_PARTS) ||
        !BS4_PARTS.every(k => ps2Sha(b.partPins[k]) && bs4IdentityHash(o[k]) === b.partPins[k] && ps2Same(o[k].binding, family)) ||
        !ps2Same(o.assessment.partPins, b.partPins) || o.assessment.contentSha256 !== bs4IdentityHash(ps2Without(o.assessment, ['contentSha256'])) ||
        bs4IdentityHash(o.assessment) !== b.assessmentSha256 || !['assessmentSha256', 'policySha256', 'catalogSha256'].every(k => ps2Sha(b[k])) ||
        !ps2Sha(b.registryBinding?.registrySha256) || c.schemaVersion !== 'evidence-context-v3' || c.canonicalizationProfile !== 'varvis-json-c14n-v1' ||
        !['patientContextId', 'familyContextId', 'role', 'sex', 'affectedStatus'].every(k => ps2Text(patient[k])) ||
        !ps2Same(c.variant, f.variant) || !bs4AlleleValid(c.variant) || !bs4SourcesLinked(o)) return false;
    const projection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass, scope: c.scope,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: patient };
    if (Object.hasOwn(c, 'transcript')) projection.transcript = { accession: c.transcript.accession, version: c.transcript.version };
    const expected = { observationId: f.observationId, occurrenceId: f.occurrenceId, caseId: f.caseId, contextId: c.contextId,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi,
        patientContextId: patient.patientContextId, familyContextId: patient.familyContextId };
    const origin = b.publishedInputIdentity, probands = f.members.filter(m => m.proband === true);
    if (probands.length !== 1) return false;
    const member = probands[0], records = f.records.filter(r => ps2Same(r.memberId, member.memberId));
    return c.contextId === 'context-sha256-' + bs4IdentityHash(projection) && ps2Same(family, expected) &&
        ['caseId', 'observationId', 'occurrenceId'].every(k => ps2Text(f[k])) && ps2Same(f.familyId, patient.familyContextId) &&
        member.role === 'CHILD' && patient.role === 'PROBAND' && ps2Same(member.memberId, patient.patientContextId) && member.sampleId === sampleId && ps2Same(member.affectedStatus, patient.affectedStatus) &&
        records.length === 1 && records[0].sampleId === sampleId && ps2Same(records[0].allele, c.variant.inputAllele) && ps2Same(records[0].sourcePersonId, member.sourcePersonId) &&
        origin.analysisId === analysisId && origin.sampleId === sampleId && origin.inputAssembly === c.variant.inputAllele.assembly && ps2Text(origin.analysisMode) &&
        ['uploadSha256', 'candidatesRawSha256', 'executionSourceSha256'].every(k => ps2Sha(origin[k]));
}

export function bs4ScopeIssue(o, analysisId = o?.analysisId) {
    if (!bs4ScopeClaim(o)) return null;
    if (pp4ScopeClaim(o)) return '混入 PP4 身份，不授予提交许可。';
    try {
        const p = o.bs4Preparation, s = o.bs4Selection;
        if (pp1ScopeClaim(o) || bp2ScopeClaim(o) || ps2ScopeClaim(o) || pm6ScopeClaim(o) || pm3ScopeClaim(o) || o.analysisId !== analysisId || !ps2Text(analysisId) || o.acceptanceScope !== 'BS4_NONSEGREGATION_DEVELOPMENT' || o.requiredDeclaration !== BS4_DECLARATION ||
            !ps2Closed(o) || o.clinicalValidationCompleted !== false || o.runtimeConfigured !== true || o.criterionPolicySelected !== false || o.predictionCompatibilityClaimed !== false ||
            o.requestedN !== 1 || o.selectedCount !== 1 || !ps2Same(o.enabledCriteria, [BS4_CRITERION]) || o.candidateIds?.length !== 1 || !ps2Text(o.candidateIds[0]) ||
            ['candidates', 'recommendations', 'association', 'calibration', 'revelApplication', 'runtimeBinding', 'hypotheses'].some(k => Object.hasOwn(o, k)) ||
            !bs4ProfileValid(o.executionProfile) || !bs4PreparationValid(p, o.executionProfile, analysisId, o.candidateIds[0], o.sampleId) || !ps2Keys(s, BS4_SELECTION_KEYS)) return 'BS4 当前核家系材料或执行身份不一致，请重新读取；未授予提交许可。';
        const expected = { candidateId: p.candidateId,
            ...Object.fromEntries(['contextId', 'alleleId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId'].map(k => [k, p.materialBinding.familyBinding[k]])),
            materialBindingSha256: p.materialBindingSha256, preparationToken: p.preparationToken, policyVersion: p.policyVersion, confirmed: false };
        return ps2Same(expected, s) ? null : 'BS4 当前目标/家系选择身份不一致，请重新读取。';
    } catch { return 'BS4 当前材料身份缺失或摘要不一致，请重新读取；未提交评价。'; }
}
export function bs4RetryEligible(run, analysisId) {
    if (pp4ScopeClaim(run)) return false;
    try { return !pp1ScopeClaim(run) && !bp2ScopeClaim(run) && !ps2ScopeClaim(run) && !pm6ScopeClaim(run) && !pm3ScopeClaim(run) && (!run?.acceptanceScope || run.acceptanceScope === 'BS4_NONSEGREGATION_DEVELOPMENT') &&
        (!run?.executionProfile || bs4ProfileValid(run.executionProfile)) && (!run?.enabledCriteria || ps2Same(run.enabledCriteria, [BS4_CRITERION])) &&
        (!run?.declarationVersion || run.declarationVersion === BS4_DECLARATION) && run?.analysisId === analysisId && /^[0-9a-f-]{36}$/.test(run.runId) &&
        (run.status === 'FAILED' || (run.status === 'COMPLETED_WITH_BLOCKERS' && bs4SavedRun(run))); } catch { return false; }
}
export function buildBs4Declaration(options, analysisId, confirmed, retryRun) {
    if ([...arguments].some(pp4ScopeClaim)) throw new Error('PP4 请使用独立声明入口。');
    if (!bs4ScopeClaim(options) || bs4ScopeIssue(options, analysisId) || confirmed !== true) throw new Error('BS4 请读取当前核家系材料并明确确认后提交。');
    if (retryRun !== undefined && !bs4RetryEligible(retryRun, analysisId)) throw new Error('BS4 仅可显式重试本分析已选 FAILED 或 COMPLETED_WITH_BLOCKERS 运行。');
    const context = options.bs4Preparation.materialBinding.context;
    return { topN: 1, declarationVersion: BS4_DECLARATION, sampleId: options.sampleId, dataClass: context.dataClass,
        patient: Object.fromEntries(['patientContextId', 'role', 'sex', 'familyContextId'].map(k => [k, context.patientContext[k]])), hypotheses: [],
        bs4Selection: { ...options.bs4Selection, confirmed: true }, ...(retryRun ? { retryOf: retryRun.runId } : {}) };
}
// Transport linkage from Bs4SegregationCriterionEvaluator/carrier and the selector receipt.
// This checks returned references, not GT/nonsegregation/classification/AF or a scientific decision.
function bs4SelectionLinked(selection, source, b) {
    const policy = source.policy, catalog = source.catalog;
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || selection.contextId !== source.context.contextId ||
        selection.selectionId !== 'cspec-selection-sha256-' + bs4IdentityHash(ps2Without(selection, ['selectionId']))) return false;
    if (selection.schemaVersion !== 'acmg-specification-selection-v1' || !id(selection.selectionId, 'cspec-selection-sha256-') ||
        selection.outcome !== 'GENERAL_POLICY_SELECTED' || !ps2Same(selection.criterionIds, [BS4_CRITERION]) ||
        !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], catalog[k])) || selection.catalogSha256 !== b.catalogSha256 ||
        !['policyId', 'policyVersion'].every(k => ps2Text(selection[k]) && selection[k] === policy[k]) || selection.policySha256 !== b.policySha256 ||
        policy.schemaVersion !== 'bs4-reviewed-nonsegregation-policy-v1' || policy.mode !== 'SINGLE_NUCLEAR_FAMILY_AD' || !ps2Same(policy.criterionIds, [BS4_CRITERION])) return false;
    const entries = catalog.entries.filter(v => v.entryId === selection.catalogEntryId);
    if (entries.length !== 1) return false;
    const entry = entries[0], context = source.context;
    if (entry.criterionId !== BS4_CRITERION || !['scope', 'disposition', 'baseRuleRelationship', 'sourceIds'].every(k => ps2Same(selection[k], entry[k])) ||
        !ps2Same(entry.policyBinding, { policyId: policy.policyId, policyVersion: policy.policyVersion, policySha256: b.policySha256 }) ||
        ![selection.scope, policy.scope].every(scope => scope.hgncId === context.gene.hgncId && scope.mondoId === context.disease.mondoId &&
            scope.moi === context.moi && scope.assembly === context.variant.evidenceQueryAllele.assembly)) return false;
    return true;
}
// A selector refusal is a receipt, not a completed scientific evaluation.
function bs4OptionalSelectionLinked(selection, source, b, reason) {
    if (selection?.outcome === 'GENERAL_POLICY_SELECTED') return bs4SelectionLinked(selection, source, b);
    const required = ['schemaVersion', 'contextId', 'criterionIds', 'catalogId', 'catalogVersion', 'catalogSha256', 'searchedAt', 'searchScope', 'outcome', 'reasonCode', 'selectionId'];
    const optional = ['catalogEntryId', 'scope', 'disposition', 'baseRuleRelationship'];
    if (!selection || !required.every(k => Object.hasOwn(selection, k)) || Object.keys(selection).some(k => ![...required, ...optional].includes(k)) ||
        selection.schemaVersion !== 'acmg-specification-selection-v1' || selection.contextId !== source.context.contextId ||
        !ps2Same(selection.criterionIds, [BS4_CRITERION]) || !['SELECTION_INPUT_INCOMPLETE', 'SPECIALIZED_POLICY_GAP'].includes(selection.outcome) ||
        ![selection.reasonCode, 'BS4_' + selection.reasonCode].includes(reason) || !ps2Text(reason) || selection.selectionId !== 'cspec-selection-sha256-' + bs4IdentityHash(ps2Without(selection, ['selectionId'])) ||
        selection.catalogSha256 !== b.catalogSha256 || !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], source.catalog[k]))) return false;
    if (!Object.hasOwn(selection, 'catalogEntryId')) return optional.every(k => !Object.hasOwn(selection, k));
    const entries = source.catalog.entries.filter(e => e.entryId === selection.catalogEntryId);
    return entries.length === 1 && entries[0].criterionId === BS4_CRITERION && Object.hasOwn(selection, 'scope') &&
        ['scope', 'disposition', 'baseRuleRelationship'].every(k => !Object.hasOwn(selection, k) || ps2Same(selection[k], entries[0][k]));
}
// Report consistency and original source references only; never infer counted from GT/phenotype.
function bs4EventsLinked(core) {
    const c = core.sourceBundle.context, o = core.sourceBundle.observations[0], f = o.facts, events = core.nonSegregationEvents;
    const children = f.members.filter(m => m.role === 'CHILD'), fathers = f.members.filter(m => m.role === 'FATHER'), mothers = f.members.filter(m => m.role === 'MOTHER');
    if (fathers.length !== 1 || mothers.length !== 1 || !Array.isArray(events) || events.length !== children.length || !events.length ||
        !ps2Same(core.familyId, f.familyId) || core.rawPoints !== -4 || core.appliedPoints !== -4 || !Number.isSafeInteger(core.eventCount) || core.eventCount < 1 ||
        events.filter(e => e.counted === true).length !== core.eventCount || !ps2Same(core.pp4, { status: 'NOT_EVALUATED', reasonCode: 'PP4_OUTSIDE_THIS_CORE' })) return false;
    const groups = o.locusAttribution.usedEvidence;
    if (!ps2Text(core.evidenceGroupId) || !groups.length || !groups.every(g => ps2Same(g.evidenceGroupId, core.evidenceGroupId))) return false;
    const seen = new Set();
    for (const event of events) {
        if (!ps2Keys(event, ['eventId', 'counted', 'reasonCode', 'identity', 'records', 'parentage', 'clinical', 'parentClinical']) ||
            !ps2Keys(event.identity, ['familyId', 'alleleId', 'fatherMemberId', 'motherMemberId', 'childMemberId']) || typeof event.counted !== 'boolean' ||
            !ps2Same(event.identity.familyId, f.familyId) || !ps2Same(event.identity.alleleId, c.variant.alleleId) ||
            !ps2Same(event.identity.fatherMemberId, fathers[0].memberId) || !ps2Same(event.identity.motherMemberId, mothers[0].memberId) ||
            event.eventId !== 'bs4-nonsegregation-sha256-' + bs4IdentityHash(event.identity) || seen.has(event.identity.childMemberId.normalize('NFC'))) return false;
        seen.add(event.identity.childMemberId.normalize('NFC'));
        const child = children.find(m => ps2Same(m.memberId, event.identity.childMemberId));
        if (!child || event.reasonCode !== (event.counted ? 'AFFECTED_REFERENCE_SIBLING' : child.proband ? 'PROBAND_BACKGROUND_ONLY' : 'NO_BS4_EVENT')) return false;
        // The backend preserves parent/child/other-parent order (alpha starts with mother).
        // Check identity linkage, without inferring the transmitting parent from GT.
        const parent = [fathers[0], mothers[0]].find(m => ps2Same(m.memberId, event.records?.[0]?.memberId));
        if (!parent) return false;
        const other = parent === fathers[0] ? mothers[0] : fathers[0];
        const records = [parent, child, other].map(m => f.records.filter(r => ps2Same(r.memberId, m.memberId)));
        const relations = [fathers[0], mothers[0]].map(m => f.parentage.filter(r => ps2Same(r.parentMemberId, m.memberId) && ps2Same(r.childMemberId, child.memberId)));
        if (records.some(r => r.length !== 1) || relations.some(r => r.length !== 1) || !ps2Same(event.records, records.map(r => r[0])) ||
            !ps2Same(event.parentage, relations.map(r => r[0])) || !ps2Same(event.clinical, child.clinical) ||
            !ps2Same(event.parentClinical, [fathers[0].clinical, mothers[0].clinical])) return false;
        const reviews = o.nonSegregationReview.memberReviews.filter(r => ps2Same(r.memberId, child.memberId));
        if (reviews.length !== 1 || !ps2Same(reviews[0].negativeTest.memberId, child.memberId) ||
            reviews[0].negativeTest.sampleId !== child.sampleId || !ps2Same(reviews[0].negativeTest.alleleId, c.variant.alleleId) ||
            !reviews[0].negativeTest.sourceIds?.length || !reviews[0].negativeTest.sourceIds.every(id => o.nonSegregationReview.sources.some(source => ps2Same(source.sourceId, id)))) return false;
    }
    return true;
}

// RuntimeEvidenceV2Adapter.identityProjection, limited to BS4 saved atomic reports.
// The canonical sort is by JSON text (not by digest). Copies preserve raw input.
const bs4AtomicIdentityHash = pp1AtomicIdentityHash;
function bs4CompletedLinked(core, b) {
    const source = core.sourceBundle, o = source.observations[0], facts = o.facts, policy = source.policy;
    const selection = core.policySelection, evidence = core.evidence, e = core.evaluation, trace = e?.deterministicTrace;
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || !evidence || !e || !trace || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.direction !== 'BENIGN' || core.observationCount !== 1 ||
        !bs4EventsLinked(core) ||
        core.caseId !== facts.caseId || core.occurrenceId !== facts.occurrenceId ||
        ![evidence, e].every(v => v.contextId === source.context.contextId) || !bs4SelectionLinked(selection, source, b)) return false;
    if (e.evaluationId !== 'evaluation-sha256-' + bs4IdentityHash(ps2Without(e, ['evaluationId', 'conflict', 'review'])) ||
        trace.traceSha256 !== bs4IdentityHash(ps2Without(trace, ['traceSha256'])) ||
        evidence.evidenceId !== 'evidence-sha256-' + bs4AtomicIdentityHash(evidence) ||
        evidence.resourceExecution?.status !== 'SUCCEEDED' || evidence.resourceAvailability?.status !== 'AVAILABLE') return false;
    if (evidence.direction !== 'UNDETERMINED' || evidence.resourceApplicability?.status !== 'APPLICABLE' || evidence.queryOutcome?.status !== 'HIT' || evidence.schemaVersion !== 'atomic-evidence-v2' || evidence.canonicalizationProfile !== 'varvis-json-c14n-v1' || evidence.evidenceType !== 'CASE_OBSERVATION' ||
        !id(evidence.evidenceId, 'evidence-sha256-') || !id(e.evaluationId, 'evaluation-sha256-') ||
        e.schemaVersion !== 'criterion-evaluation-v2' || e.canonicalizationProfile !== 'varvis-json-c14n-v1' || e.criterionId !== BS4_CRITERION ||
        e.baseStrength !== 'STRONG' || e.appliedStrength !== 'STRONG' ||
        !ps2Same(e.evaluationResult, { status: 'MET', reasonCode: 'BS4_REVIEWED_NUCLEAR_FAMILY_NONSEGREGATION' }) ||
        !ps2Same(e.criterionApplicability, { status: 'APPLICABLE' }) || !ps2Same(e.evidenceIds, [evidence.evidenceId]) ||
        !ps2Same(e.ruleBinding, { currentGeneralDesignation: 'BS4', ruleRegistryId: source.registryBinding.registryId,
            ruleRegistrySha256: source.registryBinding.registrySha256, cspecSelectionId: selection.selectionId, authoritySourceIds: policy.authoritySourceIds })) return false;
    const observationSha = bs4IdentityHash(o), date = o.assessment.review.date;
    const locators = BS4_PARTS.flatMap(part => o[part].sources.map(document => ({ locatorType: 'DOCUMENT_SECTION', locatorValue: document.locator,
        sourceRecordId: document.recordId, artifactSha256: document.rawSha256, retrievedAt: document.acquiredAt })));
    if (!ps2Same(evidence.source, { sourceId: 'REVIEWED_CASE_FAMILY_OBSERVATION', resourceVersion: facts.observationVersion,
        resourceSha256: observationSha, retrievedAt: date, sourceRecords: locators.map(locator => ({ recordId: locator.sourceRecordId, locator })), sourceLocators: locators }) ||
        evidence.provenance?.sourcePayloadSha256 !== observationSha || evidence.provenance?.producedAt !== date ||
        evidence.provenance?.identityProjectionSha256 !== evidence.evidenceId.slice('evidence-sha256-'.length) ||
        evidence.queryOutcome?.queryFingerprint !== bs4IdentityHash(facts.binding) || evidence.queryOutcome?.recordCount !== locators.length) return false;
    const parameters = { observationSha256: observationSha, familyId: facts.familyId, factsSha256: b.partPins.facts,
        assessmentSha256: b.assessmentSha256, policySha256: b.policySha256, nonSegregationEventsSha256: bs4IdentityHash(core.nonSegregationEvents),
        rawAndAppliedPoints: core.rawPoints, evidenceGroupId: core.evidenceGroupId, occurrenceId: facts.occurrenceId };
    return trace.executionStatus === 'COMPLETED' && trace.evaluatorId === 'reviewed-single-nuclear-family-nonsegregation-bs4' && trace.evaluatorVersion === '1.0.0' &&
        trace.algorithmId === policy.policyId && trace.algorithmVersion === policy.policyVersion && ps2Sha(trace.traceSha256) &&
        ps2Same(trace.inputEvidenceIds, [evidence.evidenceId]) &&
        ps2Same(trace.parameters, Object.entries(parameters).map(([name, value]) => ({ name, value }))) &&
        ps2Same(trace.steps, [{ stepCode: e.evaluationResult.reasonCode, outcome: 'TRUE', inputEvidenceIds: [evidence.evidenceId] }]);
}
export function bs4SavedRun(run) {
    if (pp4ScopeClaim(run)) return false;
    try {
        if (pp1ScopeClaim(run) || bp2ScopeClaim(run) || !bs4ScopeClaim(run) || ps2ScopeClaim(run) || pm6ScopeClaim(run) || pm3ScopeClaim(run) || !['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status) ||
            !ps2Text(run.runId) || !ps2Text(run.analysisId) || !ps2Closed(run) || run.clinicalValidationCompleted !== false || run.candidateOrderingChanged !== false ||
            run.acceptanceScope !== 'BS4_NONSEGREGATION_DEVELOPMENT' || run.declarationVersion !== BS4_DECLARATION || !bs4ProfileValid(run.executionProfile) ||
            !ps2Same(run.enabledCriteria, [BS4_CRITERION]) || run.requestedN !== 1 || run.selectedCount !== 1 || run.candidates?.length !== 1 || run.candidateIds?.length !== 1 || run.bundles?.length !== 1) return false;
        const candidate = run.candidates[0], a = candidate.attempts?.[0], artifact = run.bundles[0], core = a.bs4, source = core.sourceBundle, p = artifact.preparation, b = p.materialBinding;
        if (candidate.candidateId !== run.candidateIds[0] || candidate.attempts.length !== 1 || !ps2Keys(a, ['bs4', 'criterionEvaluations', 'blockers']) ||
            artifact.schemaVersion !== 'bs4-nonsegregation-business-artifact-v1' || artifact.runId !== run.runId || artifact.analysisId !== run.analysisId || artifact.candidateId !== candidate.candidateId ||
            artifact.classificationEnabled !== false || artifact.adoptionSupported !== false || artifact.coreDigestKind !== 'RAW_BYTES_SHA256' || artifact.artifactIdentityKind !== 'CANONICAL_WITH_CORE_RAW_DIGEST' || !ps2Sha(artifact.coreRawSha256) ||
            artifact.bundleId !== 'bs4-business-artifact-sha256-' + bs4IdentityHash(ps2Without(artifact, ['core', 'bundleId'])) || !ps2Same(artifact.core, core) ||
            core.criterionId !== BS4_CRITERION || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.resultMode !== 'CORE_RESULT_ONLY' || core.classificationEnabled !== false || core.adoptionSupported !== false ||
            !ps2Same(a.criterionEvaluations, core.criterionEvaluations) || core.criterionEvaluations?.length !== 1 || core.criterionEvaluations[0].criterionId !== BS4_CRITERION ||
            !bs4PreparationValid(p, run.executionProfile, run.analysisId, candidate.candidateId, p.sampleId) || run.dataClass !== b.context.dataClass || source.observations?.length !== 1 ||
            !ps2Same(source.context, b.context) || !ps2Same(source.observations[0], p.reviewedObservation) || !ps2Same(source.registryBinding, b.registryBinding) ||
            !ps2Same(source.policy.partPins, b.partPins) || source.policy.assessmentSha256 !== b.assessmentSha256 || bs4IdentityHash(source.policy) !== b.policySha256 ||
            source.catalogSha256 !== b.catalogSha256 || bs4IdentityHash(source.catalog) !== b.catalogSha256 || bs4IdentityHash(source) !== b.materialPins.bundleCanonicalSha256) return false;
        const evaluation = core.criterionEvaluations[0];
        if (core.evaluation) return run.status === 'COMPLETED' && ps2Same(core.evaluation, evaluation) && bs4CompletedLinked(core, b);
        return run.status === 'COMPLETED_WITH_BLOCKERS' && ps2Keys(evaluation, ['criterionId', 'evaluationResult', 'criterionApplicability']) &&
            ps2Keys(core, ['criterionId', 'resultMode', 'sourceOfTruth', 'classificationEnabled', 'adoptionSupported', 'sourceBundle', 'evaluationResult', 'criterionApplicability', 'criterionEvaluations', ...(Object.hasOwn(core, 'policySelection') ? ['policySelection'] : [])]) &&
            (!Object.hasOwn(core, 'policySelection') || bs4OptionalSelectionLinked(core.policySelection, source, b, core.evaluationResult?.reasonCode)) &&
            ps2Keys(core.evaluationResult, ['status', 'reasonCode']) && ps2Keys(core.criterionApplicability, ['status', 'reasonCode']) &&
            core.evaluationResult.status === 'NOT_EVALUATED' && core.criterionApplicability.status === 'UNKNOWN' &&
            typeof core.evaluationResult.reasonCode === 'string' && core.evaluationResult.reasonCode.trim().length > 0 &&
            core.evaluationResult.reasonCode === core.criterionApplicability.reasonCode && ps2Same(core.evaluationResult, evaluation.evaluationResult) && ps2Same(core.criterionApplicability, evaluation.criterionApplicability);
    } catch { return false; }
}
export function bs4EvidenceEntry(core) {
    const c = core?.sourceBundle?.context;
    const evaluation = core?.evaluation || core?.criterionEvaluations?.[0];
    return { evaluation, scopeLabel: 'BS4 良性方向证据（不是最终分类）', contextLabel: [c?.gene?.symbol, c?.disease?.label, c?.moi].filter(Boolean).join(' · '),
        reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [['核心来源标签', core?.resultMode], ['后端原始点数', core?.rawPoints ?? '未记录'], ['后端应用点数', core?.appliedPoints ?? '未记录'], ['家系', core?.familyId ?? '未记录'], ['共享证据组', core?.evidenceGroupId ?? '未记录'], ['PP4 边界', core?.pp4 ? JSON.stringify(core.pp4) : '未执行'], ['原始核心（只读）', JSON.stringify(core)]], raw: core };
}

function bs4RawUtf8Hash(text) {
    const bytes = new TextEncoder().encode(text);
    const data = new Uint8Array(Math.ceil((bytes.length + 9) / 64) * 64);
    data.set(bytes); data[bytes.length] = 0x80;
    const view = new DataView(data.buffer);
    view.setUint32(data.length - 4, bytes.length * 8);
    const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    const k = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
    const rotate = (v, n) => (v >>> n) | (v << (32 - n));
    for (let offset = 0; offset < data.length; offset += 64) {
        const w = new Uint32Array(64);
        for (let i = 0; i < 16; i += 1) w[i] = view.getUint32(offset + i * 4);
        for (let i = 16; i < 64; i += 1) {
            const x = w[i - 15], y = w[i - 2];
            w[i] = w[i - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3)) + w[i - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10));
        }
        let [a,b,c,d,e,f,g,t] = h;
        for (let i = 0; i < 64; i += 1) {
            const one = (t + (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i]) | 0;
            const two = ((rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
            t = g; g = f; f = e; e = (d + one) | 0; d = c; c = b; b = a; a = (one + two) | 0;
        }
        [a,b,c,d,e,f,g,t].forEach((v, i) => { h[i] = (h[i] + v) | 0; });
    }
    return h.map((v) => (v >>> 0).toString(16).padStart(8, '0')).join('');
}
function bs4SourcesLinked(observation) {
    return BS4_PARTS.every(part => {
        const document = observation[part], projection = ps2Without(document, ['sources']);
        return Array.isArray(document.sources) && document.sources.length > 0 && document.sources.every(source =>
            typeof source.rawUtf8 === 'string' && bs4RawUtf8Hash(source.rawUtf8) === source.rawSha256 &&
            bs4IdentityHash(JSON.parse(source.rawUtf8)) === source.canonicalSha256 && ps2Same(JSON.parse(source.rawUtf8), projection));
    });
}

// PP4 transport verification only; scientific applicability remains the backend core's decision.
export const pp4IdentityHash = ps2IdentityHash;
const PP4_CRITERION = 'ACMG_AMP_2015:PP4';
const PP4_DECLARATION = 'ordinary-pp4-phenotype-v1';
const PP4_TOKEN = 'PP4_PHENOTYPE_CONTEXT@1.0.0:';
const PP4_PARTS = ['facts', 'phenotypeReview', 'diagnosticYield', 'locusAttribution'];
const PP4_SELECTION_KEYS = ['candidateId', 'contextId', 'alleleId', 'observationId', 'occurrenceId', 'caseId', 'familyContextId', 'materialBindingSha256', 'preparationToken', 'policyVersion', 'confirmed'];
export function pp4ScopeClaim(value) {
    if (!value || typeof value !== 'object') return typeof value === 'string' &&
        (value.startsWith('PP4_PHENOTYPE_CONTEXT@') || value.startsWith('ordinary-pp4-') || value.startsWith('acmg-pp4-phenotype-development') || value.startsWith('pp4-phenotype-business-') || value.startsWith('pp4-phenotype-context-preparation') || value === 'PP4_PHENOTYPE_DEVELOPMENT');
    return Object.entries(value).some(([k, v]) => ['pp4Preparation', 'pp4Selection', 'pp4MaterialPins'].includes(k) || (k === 'pp4' && v?.criterionId === PP4_CRITERION && v?.sourceOfTruth === 'ACMG_EVIDENCE_V2') || pp4ScopeClaim(v));
}
function pp4ProfileValid(p) {
    return ps2Keys(p, ['profileId', 'profileVersion', 'requiredDeclaration', 'formalEnabled', 'automaticTrigger', 'classificationEnabled', 'adoptionSupported', 'clinicalValidationCompleted', 'enabledCriteria', 'pp4MaterialPins', 'contextPreparation', 'manifestSha256']) &&
        p.profileId === 'acmg-pp4-phenotype-development-v1' && p.profileVersion === '1.0.0' && p.requiredDeclaration === PP4_DECLARATION && ps2Closed(p) && p.clinicalValidationCompleted === false &&
        ps2Same(p.enabledCriteria, [PP4_CRITERION]) && ps2Same(p.contextPreparation, { schemaVersion: 'pp4-phenotype-context-preparation-v1', version: '1.0.0' }) &&
        ps2Keys(p.pp4MaterialPins, ['bundleRawSha256', 'bundleCanonicalSha256']) && Object.values(p.pp4MaterialPins).every(ps2Sha) &&
        p.manifestSha256 === pp4IdentityHash(ps2Without(p, ['manifestSha256']));
}
function pp4AlleleValid(v) {
    const normalizer = Object.fromEntries(['method', 'methodVersion', 'referenceSequenceSha256', 'refValidation'].map(k => [k, v.normalization?.[k]]));
    return v.alleleId === 'allele-sha256-' + pp4IdentityHash({ normalizedAllele: v.normalizedAllele, normalizer });
}
function pp4PreparationValid(p, profile, analysisId, candidateId, sampleId) {
    if (!ps2Keys(p, ['schemaVersion', 'version', 'policyVersion', 'status', 'scientificApplicability', 'analysisId', 'sampleId', 'candidateId', 'materialBindingSha256', 'criterionPolicySelected', 'predictionCompatibilityClaimed', 'materialBinding', 'patientContext', 'reviewedObservation', 'preparationToken']) ||
        p.schemaVersion !== 'pp4-phenotype-context-preparation-v1' || p.version !== '1.0.0' || p.policyVersion !== '1.0.0-pp4-phenotype-development' ||
        p.status !== 'BOUND_NOT_EVALUATED' || p.scientificApplicability !== 'NOT_EVALUATED' || p.criterionPolicySelected !== false || p.predictionCompatibilityClaimed !== false ||
        p.analysisId !== analysisId || p.candidateId !== candidateId || p.sampleId !== sampleId || !ps2Text(sampleId) ||
        p.preparationToken !== PP4_TOKEN + pp4IdentityHash(ps2Without(p, ['preparationToken']))) return false;
    const b = p.materialBinding, c = b.context, patient = c.patientContext, o = p.reviewedObservation, f = o.facts, family = b.familyBinding;
    if (!ps2Keys(b, ['schemaVersion', 'analysisId', 'sampleId', 'dataClass', 'materialPins', 'context', 'familyBinding', 'publishedInputIdentity', 'candidateId', 'partPins', 'assessmentSha256', 'policySha256', 'catalogSha256', 'registryBinding', 'executionProfile']) ||
        b.schemaVersion !== 'pp4-phenotype-business-binding-v1' || b.analysisId !== analysisId || b.sampleId !== sampleId || b.dataClass !== c.dataClass || p.materialBindingSha256 !== pp4IdentityHash(b) ||
        !ps2Same(b.materialPins, profile.pp4MaterialPins) || !ps2Same(b.executionProfile, profile) || b.candidateId !== candidateId || !ps2Same(patient, p.patientContext) ||
        !ps2Keys(o, [...PP4_PARTS, 'assessment']) || !ps2Keys(b.partPins, PP4_PARTS) ||
        !PP4_PARTS.every(k => ps2Sha(b.partPins[k]) && pp4IdentityHash(o[k]) === b.partPins[k] && ps2Same(o[k].binding, family)) ||
        !ps2Same(o.assessment.partPins, b.partPins) || o.assessment.contentSha256 !== pp4IdentityHash(ps2Without(o.assessment, ['contentSha256'])) ||
        pp4IdentityHash(o.assessment) !== b.assessmentSha256 || !['assessmentSha256', 'policySha256', 'catalogSha256'].every(k => ps2Sha(b[k])) ||
        !ps2Sha(b.registryBinding?.registrySha256) || c.schemaVersion !== 'evidence-context-v3' || c.canonicalizationProfile !== 'varvis-json-c14n-v1' ||
        !['patientContextId', 'familyContextId', 'role', 'sex', 'affectedStatus'].every(k => ps2Text(patient[k])) ||
        !pp4AlleleValid(c.variant) || !pp4SourcesLinked(o)) return false;
    const projection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass, scope: c.scope,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: patient };
    if (Object.hasOwn(c, 'transcript')) projection.transcript = { accession: c.transcript.accession, version: c.transcript.version };
    const expected = { observationId: f.observationId, contextId: c.contextId,
        alleleId: c.variant.alleleId, allele: c.variant.inputAllele, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi,
        patientContextId: patient.patientContextId, familyContextId: patient.familyContextId };
    const origin = b.publishedInputIdentity, probands = f.members.filter(m => m.role === 'PROBAND');
    if (f.schemaVersion !== 'case-family-observation-v1' || f.members.length !== 1 || f.records.length !== 1 || !ps2Same(f.parentage, []) || probands.length !== 1) return false;
    const member = probands[0], records = f.records.filter(r => r.role === 'PROBAND' && ps2Same(r.sampleId, member.sampleId));
    return c.contextId === 'context-sha256-' + pp4IdentityHash(projection) && ps2Same(family, expected) &&
        ['caseId', 'observationId', 'occurrenceId'].every(k => ps2Text(f[k])) && ps2Same(f.familyId, patient.familyContextId) &&
        member.role === 'PROBAND' && patient.role === 'PROBAND' && ps2Same(member.memberId, patient.patientContextId) && member.sampleId === sampleId && ps2Same(member.affectedStatus, patient.affectedStatus) &&
        records.length === 1 && records[0].sampleId === sampleId && ps2Same(records[0].variantKey, { assembly: family.allele.assembly, chromosome: family.allele.contig, position: family.allele.position, reference: family.allele.reference, alternate: family.allele.alternate }) && ps2Same(member.detection.binding, family) && ps2Same(member.detection.memberId, member.memberId) &&
        origin.analysisId === analysisId && origin.sampleId === sampleId && origin.inputAssembly === c.variant.inputAllele.assembly && ps2Text(origin.analysisMode) &&
        ['uploadSha256', 'candidatesRawSha256', 'executionSourceSha256'].every(k => ps2Sha(origin[k]));
}

export function pp4ScopeIssue(o, analysisId = o?.analysisId) {
    if (!pp4ScopeClaim(o)) return null;
    try {
        const p = o.pp4Preparation, s = o.pp4Selection;
        if (bs4ScopeClaim(o) || pp1ScopeClaim(o) || bp2ScopeClaim(o) || ps2ScopeClaim(o) || pm6ScopeClaim(o) || pm3ScopeClaim(o) || o.analysisId !== analysisId || !ps2Text(analysisId) || o.acceptanceScope !== 'PP4_PHENOTYPE_DEVELOPMENT' || o.requiredDeclaration !== PP4_DECLARATION ||
            !ps2Closed(o) || o.clinicalValidationCompleted !== false || o.runtimeConfigured !== true || o.criterionPolicySelected !== false || o.predictionCompatibilityClaimed !== false ||
            o.requestedN !== 1 || o.selectedCount !== 1 || !ps2Same(o.enabledCriteria, [PP4_CRITERION]) || o.candidateIds?.length !== 1 || !ps2Text(o.candidateIds[0]) ||
            ['candidates', 'recommendations', 'association', 'calibration', 'revelApplication', 'runtimeBinding', 'hypotheses'].some(k => Object.hasOwn(o, k)) ||
            !pp4ProfileValid(o.executionProfile) || !pp4PreparationValid(p, o.executionProfile, analysisId, o.candidateIds[0], o.sampleId) || !ps2Keys(s, PP4_SELECTION_KEYS)) return 'PP4 当前单先证者材料或执行身份不一致，请重新读取；未授予提交许可。';
        const expected = { candidateId: p.candidateId,
            ...Object.fromEntries(['contextId', 'alleleId', 'observationId', 'familyContextId'].map(k => [k, p.materialBinding.familyBinding[k]])),
            caseId: p.reviewedObservation.facts.caseId, occurrenceId: p.reviewedObservation.facts.occurrenceId, materialBindingSha256: p.materialBindingSha256, preparationToken: p.preparationToken, policyVersion: p.policyVersion, confirmed: false };
        return ps2Same(expected, s) ? null : 'PP4 当前目标/家系选择身份不一致，请重新读取。';
    } catch { return 'PP4 当前材料身份缺失或摘要不一致，请重新读取；未提交评价。'; }
}
export function pp4RetryEligible(run, analysisId) {
    try { return !bs4ScopeClaim(run) && !pp1ScopeClaim(run) && !bp2ScopeClaim(run) && !ps2ScopeClaim(run) && !pm6ScopeClaim(run) && !pm3ScopeClaim(run) && (!run?.acceptanceScope || run.acceptanceScope === 'PP4_PHENOTYPE_DEVELOPMENT') &&
        (!run?.executionProfile || pp4ProfileValid(run.executionProfile)) && (!run?.enabledCriteria || ps2Same(run.enabledCriteria, [PP4_CRITERION])) &&
        (!run?.declarationVersion || run.declarationVersion === PP4_DECLARATION) && run?.analysisId === analysisId && /^[0-9a-f-]{36}$/.test(run.runId) &&
        (run.status === 'FAILED' || (run.status === 'COMPLETED_WITH_BLOCKERS' && pp4SavedRun(run))); } catch { return false; }
}
export function buildPp4Declaration(options, analysisId, confirmed, retryRun) {
    if (!pp4ScopeClaim(options) || pp4ScopeIssue(options, analysisId) || confirmed !== true) throw new Error('PP4 请读取当前单先证者材料并明确确认后提交。');
    if (retryRun !== undefined && !pp4RetryEligible(retryRun, analysisId)) throw new Error('PP4 仅可显式重试本分析已选 FAILED 或 COMPLETED_WITH_BLOCKERS 运行。');
    const context = options.pp4Preparation.materialBinding.context;
    return { topN: 1, declarationVersion: PP4_DECLARATION, sampleId: options.sampleId, dataClass: context.dataClass,
        patient: Object.fromEntries(['patientContextId', 'role', 'sex', 'familyContextId'].map(k => [k, context.patientContext[k]])), hypotheses: [],
        pp4Selection: { ...options.pp4Selection, confirmed: true }, ...(retryRun ? { retryOf: retryRun.runId } : {}) };
}
// Transport linkage from Pp4PhenotypeCriterionEvaluator/carrier and the selector receipt.
// This checks returned references, not GT/phenotype/classification/AF or a scientific decision.
function pp4SelectionLinked(selection, source, b) {
    const policy = source.policy, catalog = source.catalog;
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || selection.contextId !== source.context.contextId ||
        selection.selectionId !== 'cspec-selection-sha256-' + pp4IdentityHash(ps2Without(selection, ['selectionId']))) return false;
    if (selection.schemaVersion !== 'acmg-specification-selection-v1' || !id(selection.selectionId, 'cspec-selection-sha256-') ||
        selection.outcome !== 'GENERAL_POLICY_SELECTED' || !ps2Same(selection.criterionIds, [PP4_CRITERION]) ||
        !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], catalog[k])) || selection.catalogSha256 !== b.catalogSha256 ||
        !['policyId', 'policyVersion'].every(k => ps2Text(selection[k]) && selection[k] === policy[k]) || selection.policySha256 !== b.policySha256 ||
        policy.schemaVersion !== 'pp4-reviewed-phenotype-policy-v1' || policy.mode !== 'SINGLE_CASE_AD' || !ps2Same(policy.criterionIds, [PP4_CRITERION])) return false;
    const entries = catalog.entries.filter(v => v.entryId === selection.catalogEntryId);
    if (entries.length !== 1) return false;
    const entry = entries[0], context = source.context;
    if (entry.criterionId !== PP4_CRITERION || !['scope', 'disposition', 'baseRuleRelationship', 'sourceIds'].every(k => ps2Same(selection[k], entry[k])) ||
        !ps2Same(entry.policyBinding, { policyId: policy.policyId, policyVersion: policy.policyVersion, policySha256: b.policySha256 }) ||
        ![selection.scope, policy.scope].every(scope => scope.hgncId === context.gene.hgncId && scope.mondoId === context.disease.mondoId &&
            scope.moi === context.moi && scope.assembly === context.variant.evidenceQueryAllele.assembly)) return false;
    return true;
}
// A selector refusal is a receipt, not a completed scientific evaluation.
function pp4OptionalSelectionLinked(selection, source, b, reason) {
    if (selection?.outcome === 'GENERAL_POLICY_SELECTED') return pp4SelectionLinked(selection, source, b);
    const required = ['schemaVersion', 'contextId', 'criterionIds', 'catalogId', 'catalogVersion', 'catalogSha256', 'searchedAt', 'searchScope', 'outcome', 'reasonCode', 'selectionId'];
    const optional = ['catalogEntryId', 'scope', 'disposition', 'baseRuleRelationship'];
    if (!selection || !required.every(k => Object.hasOwn(selection, k)) || Object.keys(selection).some(k => ![...required, ...optional].includes(k)) ||
        selection.schemaVersion !== 'acmg-specification-selection-v1' || selection.contextId !== source.context.contextId ||
        !ps2Same(selection.criterionIds, [PP4_CRITERION]) || !['SELECTION_INPUT_INCOMPLETE', 'SPECIALIZED_POLICY_GAP'].includes(selection.outcome) ||
        ![selection.reasonCode, 'PP4_' + selection.reasonCode].includes(reason) || !ps2Text(reason) || selection.selectionId !== 'cspec-selection-sha256-' + pp4IdentityHash(ps2Without(selection, ['selectionId'])) ||
        selection.catalogSha256 !== b.catalogSha256 || !['catalogId', 'catalogVersion', 'searchedAt', 'searchScope'].every(k => ps2Same(selection[k], source.catalog[k]))) return false;
    if (!Object.hasOwn(selection, 'catalogEntryId')) return optional.every(k => !Object.hasOwn(selection, k));
    const entries = source.catalog.entries.filter(e => e.entryId === selection.catalogEntryId);
    return entries.length === 1 && entries[0].criterionId === PP4_CRITERION && Object.hasOwn(selection, 'scope') &&
        ['scope', 'disposition', 'baseRuleRelationship'].every(k => !Object.hasOwn(selection, k) || ps2Same(selection[k], entries[0][k]));
}
// Check reported values and source references; do not recompute yield, points or strength.
function pp4ReportLinked(core) {
    const o = core.sourceBundle.observations[0], f = o.facts, y = core.diagnosticYield;
    // Compare the reported encodings exactly, without recalculating scientific points.
    const decimal = half => { const n = BigInt(half), two = BigInt(2); return `${n / two}${n % two ? '.5' : ''}`; };
    const group = { domain: 'pp1-pp4-locus-family-v1', familyId: f.familyId,
        hgncId: f.binding.hgncId, mondoId: f.binding.mondoId, alleleId: f.binding.alleleId };
    return ps2Same(core.familyId, f.familyId) && core.evidenceGroupId === 'pp1-pp4-group-sha256-' + pp4IdentityHash(group) &&
        o.locusAttribution.usedEvidence.every(v => ps2Same(v.evidenceGroupId, core.evidenceGroupId)) &&
        typeof core.rawPoints === 'string' && /^\d+(?:\.5)?$/.test(core.rawPoints) && typeof core.appliedPoints === 'string' && /^\d+(?:\.5)?$/.test(core.appliedPoints) &&
        Number.isSafeInteger(core.rawHalfPoints) && Number.isSafeInteger(core.appliedHalfPoints) && core.rawHalfPoints > 0 && core.appliedHalfPoints > 0 &&
        core.rawPoints === decimal(core.rawHalfPoints) && core.appliedPoints === decimal(core.appliedHalfPoints) &&
        (core.capReason === 'NOT_CAPPED'
            ? core.rawHalfPoints === core.appliedHalfPoints && core.rawHalfPoints <= 10
            : core.capReason === 'SINGLE_PP4_CAP_5' && core.rawHalfPoints > 10 && core.appliedHalfPoints === 10) &&
        core.pointsEncoding === 'EXACT_DECIMAL_STRING' && ['NOT_CAPPED', 'SINGLE_PP4_CAP_5'].includes(core.capReason) && core.algorithmVersion === 'pp4-table2-floor-cap5-v1' &&
        ps2Keys(y, ['numerator', 'denominator', 'unit', 'thresholdPerThousand']) && y.unit === 'FRACTION' && Number.isSafeInteger(y.thresholdPerThousand) &&
        ps2Same(y.numerator, o.diagnosticYield.geneSpecificDiagnosedCount) && ps2Same(y.denominator, o.diagnosticYield.eligibleTestedCount);
}

// RuntimeEvidenceV2Adapter.identityProjection, limited to PP4 saved atomic reports.
// The canonical sort is by JSON text (not by digest). Copies preserve raw input.
const pp4AtomicIdentityHash = pp1AtomicIdentityHash;
function pp4CompletedLinked(core, b) {
    const source = core.sourceBundle, o = source.observations[0], facts = o.facts, policy = source.policy;
    const selection = core.policySelection, evidence = core.evidence, e = core.evaluation, trace = e?.deterministicTrace;
    const id = (value, prefix) => typeof value === 'string' && value.startsWith(prefix) && ps2Sha(value.slice(prefix.length));
    if (!selection || !evidence || !e || !trace || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.direction !== 'PATHOGENIC' || core.observationCount !== 1 ||
        !pp4ReportLinked(core) ||
        core.caseId !== facts.caseId || core.occurrenceId !== facts.occurrenceId ||
        ![evidence, e].every(v => v.contextId === source.context.contextId) || !pp4SelectionLinked(selection, source, b)) return false;
    if (e.evaluationId !== 'evaluation-sha256-' + pp4IdentityHash(ps2Without(e, ['evaluationId', 'conflict', 'review'])) ||
        trace.traceSha256 !== pp4IdentityHash(ps2Without(trace, ['traceSha256'])) ||
        evidence.evidenceId !== 'evidence-sha256-' + pp4AtomicIdentityHash(evidence) ||
        evidence.resourceExecution?.status !== 'SUCCEEDED' || evidence.resourceAvailability?.status !== 'AVAILABLE') return false;
    if (evidence.direction !== 'UNDETERMINED' || evidence.resourceApplicability?.status !== 'APPLICABLE' || evidence.queryOutcome?.status !== 'HIT' || evidence.schemaVersion !== 'atomic-evidence-v2' || evidence.canonicalizationProfile !== 'varvis-json-c14n-v1' || evidence.evidenceType !== 'CASE_OBSERVATION' ||
        !id(evidence.evidenceId, 'evidence-sha256-') || !id(e.evaluationId, 'evaluation-sha256-') ||
        e.schemaVersion !== 'criterion-evaluation-v2' || e.canonicalizationProfile !== 'varvis-json-c14n-v1' || e.criterionId !== PP4_CRITERION ||
        e.baseStrength !== 'SUPPORTING' || !['SUPPORTING', 'MODERATE', 'STRONG'].includes(e.appliedStrength) ||
        !ps2Same(e.evaluationResult, { status: 'MET', reasonCode: 'PP4_REVIEWED_SINGLE_CASE_PHENOTYPE_YIELD' }) ||
        !ps2Same(e.criterionApplicability, { status: 'APPLICABLE' }) || !ps2Same(e.evidenceIds, [evidence.evidenceId]) ||
        !ps2Same(e.ruleBinding, { currentGeneralDesignation: 'PP4', ruleRegistryId: source.registryBinding.registryId,
            ruleRegistrySha256: source.registryBinding.registrySha256, cspecSelectionId: selection.selectionId, authoritySourceIds: policy.authoritySourceIds })) return false;
    const observationSha = pp4IdentityHash(o), date = o.assessment.review.date;
    const locators = PP4_PARTS.flatMap(part => o[part].sources.map(document => ({ locatorType: 'DOCUMENT_SECTION', locatorValue: document.locator,
        sourceRecordId: document.recordId, artifactSha256: document.rawSha256, retrievedAt: document.acquiredAt })));
    if (!ps2Same(evidence.source, { sourceId: 'REVIEWED_CASE_FAMILY_OBSERVATION', resourceVersion: facts.observationVersion,
        resourceSha256: observationSha, retrievedAt: date, sourceRecords: locators.map(locator => ({ recordId: locator.sourceRecordId, locator })), sourceLocators: locators }) ||
        evidence.provenance?.sourcePayloadSha256 !== observationSha || evidence.provenance?.producedAt !== date ||
        evidence.provenance?.identityProjectionSha256 !== evidence.evidenceId.slice('evidence-sha256-'.length) ||
        evidence.queryOutcome?.queryFingerprint !== pp4IdentityHash(facts.binding) || evidence.queryOutcome?.recordCount !== locators.length) return false;
    const parameters = { observationSha256: observationSha, familyId: facts.familyId, factsSha256: b.partPins.facts,
        assessmentSha256: b.assessmentSha256, policySha256: b.policySha256, yieldSha256: pp4IdentityHash(core.diagnosticYield),
        rawPoints: core.rawPoints, appliedPoints: core.appliedPoints, tableVersion: core.algorithmVersion, evidenceGroupId: core.evidenceGroupId, occurrenceId: facts.occurrenceId };
    return trace.executionStatus === 'COMPLETED' && trace.evaluatorId === 'reviewed-single-case-phenotype-pp4' && trace.evaluatorVersion === '1.0.0' &&
        trace.algorithmId === policy.policyId && trace.algorithmVersion === policy.policyVersion && ps2Sha(trace.traceSha256) &&
        ps2Same(trace.inputEvidenceIds, [evidence.evidenceId]) &&
        ps2Same(trace.parameters, Object.entries(parameters).map(([name, value]) => ({ name, value }))) &&
        ps2Same(trace.steps, [{ stepCode: e.evaluationResult.reasonCode, outcome: 'TRUE', inputEvidenceIds: [evidence.evidenceId] }]);
}
export function pp4SavedRun(run) {
    try {
        if (bs4ScopeClaim(run) || pp1ScopeClaim(run) || bp2ScopeClaim(run) || !pp4ScopeClaim(run) || ps2ScopeClaim(run) || pm6ScopeClaim(run) || pm3ScopeClaim(run) || !['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run.status) ||
            !ps2Text(run.runId) || !ps2Text(run.analysisId) || !ps2Closed(run) || run.clinicalValidationCompleted !== false || run.candidateOrderingChanged !== false ||
            run.acceptanceScope !== 'PP4_PHENOTYPE_DEVELOPMENT' || run.declarationVersion !== PP4_DECLARATION || !pp4ProfileValid(run.executionProfile) ||
            !ps2Same(run.enabledCriteria, [PP4_CRITERION]) || run.requestedN !== 1 || run.selectedCount !== 1 || run.candidates?.length !== 1 || run.candidateIds?.length !== 1 || run.bundles?.length !== 1) return false;
        const candidate = run.candidates[0], a = candidate.attempts?.[0], artifact = run.bundles[0], core = a.pp4, source = core.sourceBundle, p = artifact.preparation, b = p.materialBinding;
        if (candidate.candidateId !== run.candidateIds[0] || candidate.attempts.length !== 1 || !ps2Keys(a, ['pp4', 'criterionEvaluations', 'blockers']) ||
            artifact.schemaVersion !== 'pp4-phenotype-business-artifact-v1' || artifact.runId !== run.runId || artifact.analysisId !== run.analysisId || artifact.candidateId !== candidate.candidateId ||
            artifact.classificationEnabled !== false || artifact.adoptionSupported !== false || artifact.coreDigestKind !== 'RAW_BYTES_SHA256' || artifact.artifactIdentityKind !== 'CANONICAL_WITH_CORE_RAW_DIGEST' || !ps2Sha(artifact.coreRawSha256) ||
            artifact.bundleId !== 'pp4-business-artifact-sha256-' + pp4IdentityHash(ps2Without(artifact, ['core', 'bundleId'])) || !ps2Same(artifact.core, core) ||
            core.criterionId !== PP4_CRITERION || core.sourceOfTruth !== 'ACMG_EVIDENCE_V2' || core.resultMode !== 'CORE_RESULT_ONLY' || core.classificationEnabled !== false || core.adoptionSupported !== false ||
            !ps2Same(a.criterionEvaluations, core.criterionEvaluations) || core.criterionEvaluations?.length !== 1 || core.criterionEvaluations[0].criterionId !== PP4_CRITERION ||
            !pp4PreparationValid(p, run.executionProfile, run.analysisId, candidate.candidateId, p.sampleId) || run.dataClass !== b.context.dataClass || source.observations?.length !== 1 ||
            !ps2Same(source.context, b.context) || !ps2Same(source.observations[0], p.reviewedObservation) || !ps2Same(source.registryBinding, b.registryBinding) ||
            !ps2Same(source.policy.partPins, b.partPins) || source.policy.assessmentSha256 !== b.assessmentSha256 || pp4IdentityHash(source.policy) !== b.policySha256 ||
            source.catalogSha256 !== b.catalogSha256 || pp4IdentityHash(source.catalog) !== b.catalogSha256 || pp4IdentityHash(source) !== b.materialPins.bundleCanonicalSha256) return false;
        const evaluation = core.criterionEvaluations[0];
        if (core.evaluation) return run.status === 'COMPLETED' && ps2Same(core.evaluation, evaluation) && pp4CompletedLinked(core, b);
        return run.status === 'COMPLETED_WITH_BLOCKERS' && ps2Keys(evaluation, ['criterionId', 'evaluationResult', 'criterionApplicability']) &&
            ps2Keys(core, ['criterionId', 'resultMode', 'sourceOfTruth', 'classificationEnabled', 'adoptionSupported', 'sourceBundle', 'evaluationResult', 'criterionApplicability', 'criterionEvaluations', ...(Object.hasOwn(core, 'policySelection') ? ['policySelection'] : [])]) &&
            (!Object.hasOwn(core, 'policySelection') || pp4OptionalSelectionLinked(core.policySelection, source, b, core.evaluationResult?.reasonCode)) &&
            ps2Keys(core.evaluationResult, ['status', 'reasonCode']) && ps2Keys(core.criterionApplicability, ['status', 'reasonCode']) &&
            core.evaluationResult.status === 'NOT_EVALUATED' && core.criterionApplicability.status === 'UNKNOWN' &&
            typeof core.evaluationResult.reasonCode === 'string' && core.evaluationResult.reasonCode.trim().length > 0 &&
            core.evaluationResult.reasonCode === core.criterionApplicability.reasonCode && ps2Same(core.evaluationResult, evaluation.evaluationResult) && ps2Same(core.criterionApplicability, evaluation.criterionApplicability);
    } catch { return false; }
}
export function pp4EvidenceEntry(core) {
    const c = core?.sourceBundle?.context;
    const evaluation = core?.evaluation || core?.criterionEvaluations?.[0];
    return { evaluation, scopeLabel: 'PP4 致病方向证据（不是最终分类）', contextLabel: [c?.gene?.symbol, c?.disease?.label, c?.moi].filter(Boolean).join(' · '),
        reasons: evaluation?.evaluationResult?.reasonCode ? [evaluation.evaluationResult.reasonCode] : [],
        details: [['核心来源标签', core?.resultMode], ['后端原始点数', core?.rawPoints ?? '未记录'], ['后端应用点数', core?.appliedPoints ?? '未记录'], ['家系', core?.familyId ?? '未记录'], ['共享证据组', core?.evidenceGroupId ?? '未记录'], ['点数编码', core?.pointsEncoding ?? '未记录'], ['上限原因', core?.capReason ?? '未记录'], ['查表版本', core?.algorithmVersion ?? '未记录'], ['诊断率报告', core?.diagnosticYield ? JSON.stringify(core.diagnosticYield) : '未记录'], ['原始核心（只读）', JSON.stringify(core)]], raw: core };
}

const pp4RawUtf8Hash = bs4RawUtf8Hash;
function pp4SourcesLinked(observation) {
    return PP4_PARTS.every(part => {
        const document = observation[part], projection = ps2Without(document, ['sources']);
        return Array.isArray(document.sources) && document.sources.length > 0 && document.sources.every(source =>
            typeof source.rawUtf8 === 'string' && pp4RawUtf8Hash(source.rawUtf8) === source.rawSha256 &&
            pp4IdentityHash(JSON.parse(source.rawUtf8)) === source.canonicalSha256 && ps2Same(JSON.parse(source.rawUtf8), projection));
    });
}
