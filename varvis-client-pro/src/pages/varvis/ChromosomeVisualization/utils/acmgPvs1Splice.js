// Reader for the committed bounded PVS1 splice/coding-edit summary. Original
// source qualification and the outer selected-run identity remain caller gates.
const obj = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const text = v => typeof v === 'string' && v.length > 0 && v.length <= 32768;
const sha = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const int = v => Number.isSafeInteger(v) && Math.abs(v) <= 2000000;
const positive = v => int(v) && v > 0;
const code = v => typeof v === 'string' && /^[A-Z][A-Z0-9_]{0,160}$/.test(v);
const transcript = v => typeof v === 'string' && /^(?:NM_|NR_|ENST)[0-9]+\.[1-9][0-9]*$/.test(v);
const strengths = ['SUPPORTING', 'MODERATE', 'STRONG', 'VERY_STRONG'];
const lower = (...values) => strengths[Math.min(...values.map(value => strengths.indexOf(value)))];
const same = (a,b) => a === b || obj(a) && obj(b) && Object.keys(a).length === Object.keys(b).length && Object.keys(a).every(k => Object.hasOwn(b,k) && same(a[k],b[k])) ||
    Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((v,i) => same(v,b[i]));
const check = (condition, issue) => { if (!condition) throw Error(issue); };
const version = (value, minor) => typeof value === 'string' && /^1\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(value) && Number(value.split('.')[1]) >= minor;
const NEGATIVE = new Set(['PVS1_SPLICE_INFRAME_EXON_NOT_RELEVANT', 'PVS1_SPLICE_INFRAME_EXON_LOF_TOLERANT',
    'PVS1_ESCAPE_EXON_NOT_RELEVANT', 'PVS1_ESCAPE_EXON_LOF_TOLERANT']);
const METHODS = ['SOURCE_BOUND_SPLICE_CONSEQUENCE', 'SOURCE_BOUND_CODING_EDITS'];
const translateCodon = codon => 'FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG'[
    'TCAG'.indexOf(codon[0])*16+'TCAG'.indexOf(codon[1])*4+'TCAG'.indexOf(codon[2])];

export const isPvs1SpliceSummary = f => obj(f) && (Object.hasOwn(f,'method') || Object.hasOwn(f,'codingEditComposition') ||
    Object.hasOwn(f,'codingEditDerivation') || Object.hasOwn(f,'inFrameAssessment') || version(f.methodVersion,7));

function rows(parent, name, required = true) {
    if (!Object.hasOwn(parent,name) && !required) return null;
    const list = parent[name], total = parent[`${name}Total`], truncated = parent[`${name}Truncated`];
    check(Array.isArray(list) && list.length <= 32 && Number.isSafeInteger(total) && total >= 0 && total <= 100000 &&
        typeof truncated === 'boolean' && list.length === Math.min(total,32) && truncated === (total > 32) && list.every(obj), 'PVS1_BOUNDED_COLLECTION_INVALID');
    return { list, total, truncated };
}

// Exact production LegacyContextProjection: v3 preserves all fields, changes
// schemaVersion and recomputes the frozen E1 contextId before hashing the view.
function contextView(context, hash) {
    check(obj(context) && typeof hash === 'function' && ['evidence-context-v2','evidence-context-v3'].includes(context.schemaVersion) &&
        context.contextCompleteness?.status === 'COMPLETE' && obj(context.variant?.evidenceQueryAllele) &&
        obj(context.transcript) && positive(context.transcript.version), 'PVS1_SPLICE_CONTEXT_BINDING_REQUIRED');
    const projection = { scope: context.scope, alleleId: context.variant.alleleId, hgncId: context.gene?.hgncId,
        mondoId: context.disease?.mondoId, moi: context.moi, transcript: { accession: context.transcript.accession, version: context.transcript.version },
        patientContextId: context.patientContext?.patientContextId, familyContextId: context.patientContext?.familyContextId ?? null };
    const view = { ...context, schemaVersion: 'evidence-context-v2', contextId: `context-sha256-${hash(projection)}` };
    return { view, hash: hash(view), alleleHash: hash(context.variant.evidenceQueryAllele), transcript: `${context.transcript.accession}.${context.transcript.version}` };
}

function bindingValid(binding, f, context, identity) {
    check(obj(binding) && ['modelSha256','referenceCdsSha256','contextSha256'].every(k => sha(binding[k])) &&
        transcript(binding.transcript) && binding.transcript === f.selectedTranscript && identity &&
        binding.contextSha256 === identity.hash && binding.transcript === identity.transcript &&
        binding.assembly === context.variant.evidenceQueryAllele.assembly && binding.contig === context.variant.evidenceQueryAllele.contig,
    'PVS1_EDIT_CONTEXT_OR_MODEL_BINDING_INVALID');
}

function editsValid(c, f, context, identity, composition) {
    check(c?.schemaVersion === 'coding-transcript-edit-facts-v1' && version(c.methodVersion,0) &&
        c.scope === 'CDS_SEQUENCE_FACTS_ONLY' && c.topologyScope === 'CODING_EXONS_ONLY' && c.scientificQualificationGranted === false &&
        sha(c.alteredCdsSha256) && positive(c.referenceCdsLength) && c.referenceCdsLength <= 1000000 &&
        int(c.alteredCdsLength) && c.alteredCdsLength >= 0 && c.netCdsLengthChange === c.alteredCdsLength - c.referenceCdsLength,
    'PVS1_EDIT_FACTS_INVALID');
    bindingValid(c.binding,f,context,identity);
    const edits = rows(c,'edits');
    check(edits.total > 0 && edits.total <= 1000 && new Set(edits.list.map(e => e.id)).size === edits.list.length &&
        edits.list.every(e => text(e.id) && ['CODING_ALLELE','SPLICE_PROJECTION'].includes(e.origin) &&
            ['APPLIED','REMOVED_BY_SPLICE','DUPLICATE_SEQUENCE_EDIT'].includes(e.status) && int(e.referenceEditStart0) && e.referenceEditStart0 >= 0 &&
            int(e.referenceEditEnd0) && e.referenceEditEnd0 >= e.referenceEditStart0 && e.referenceEditEnd0 <= c.referenceCdsLength &&
            sha(e.evidenceBindingSha256) && e.alleleGroupSha256 === identity.alleleHash && int(e.insertionExonOrdinal) && e.insertionExonOrdinal >= 0), 'PVS1_EDIT_BINDING_INVALID');
    if (!composition) {
        check(!edits.truncated && edits.list.every(e => e.origin === 'CODING_ALLELE') &&
            edits.list.filter(e => e.status === 'APPLIED').length === 1 &&
            c.netCdsLengthChange === context.variant.evidenceQueryAllele.alternate.length - context.variant.evidenceQueryAllele.reference.length,
        'PVS1_CODING_EDIT_DERIVATION_INVALID');
        return edits;
    }
    check(['NO_CODING_COMPONENT','CODING_COMPONENT_REMOVED_BY_SPLICE','SAME_ALLELE_CODING_AND_SPLICE_EDITS_APPLIED'].includes(c.compositionStatus) &&
        typeof c.explicitCompositionReviewed === 'boolean' && ['REFERENCE_CDS_DELETION','COMPLETE_ALLELIC_CDS'].includes(c.rnaSequenceScope) &&
        c.fullRnaOrEjcProofGranted === false && c.completeAllelicCdsWitnessVerified === (c.rnaSequenceScope === 'COMPLETE_ALLELIC_CDS'), 'PVS1_SPLICE_COMPOSITION_INVALID');
    const splices = edits.list.filter(e => e.origin === 'SPLICE_PROJECTION');
    check(edits.truncated || splices.length === 1 && splices[0].status === 'APPLIED', 'PVS1_SPLICE_EDIT_MISSING_OR_AMBIGUOUS');
    if (c.explicitCompositionReviewed) {
        const d = c.sourceDeclaration;
        check(d?.schemaVersion === 'pvs1-splice-coding-composition-v1' && d.status === 'REVIEWED_FOR_SAME_ALLELE_COMPOSITION' && text(d.reviewId) &&
            d.alleleGroupSha256 === identity.alleleHash && d.referenceCdsSha256 === c.binding.referenceCdsSha256 &&
            sha(d.spliceEventSha256) && d.sequenceScope === c.rnaSequenceScope &&
            (edits.truncated || splices[0].id === `splice:${d.spliceEventSha256}`), 'PVS1_COMPOSITION_SOURCE_DECLARATION_INVALID');
    } else check(!c.sourceDeclaration && c.rnaSequenceScope === 'REFERENCE_CDS_DELETION', 'PVS1_COMPOSITION_REVIEW_CONTRADICTION');
    const visibleSplice = splices.find(e => e.status === 'APPLIED');
    if (visibleSplice) check(f.cdsPosition === visibleSplice.referenceEditStart0 + 1 &&
        f.spliceAssessment?.rnaReadingFrame === ((visibleSplice.referenceEditEnd0-visibleSplice.referenceEditStart0)%3 === 0 ? 'PRESERVED' : 'ALTERED'),
    'PVS1_RNA_EDIT_POSITION_OR_FRAME_MISMATCH');
    if (!edits.truncated) {
        const applied = edits.list.filter(e => e.origin === 'CODING_ALLELE' && e.status === 'APPLIED');
        const coding = edits.list.filter(e => e.origin === 'CODING_ALLELE');
        check(c.compositionStatus === 'NO_CODING_COMPONENT' ? coding.length === 0 : c.compositionStatus === 'CODING_COMPONENT_REMOVED_BY_SPLICE'
            ? coding.some(e => e.status === 'REMOVED_BY_SPLICE') && applied.length === 0 : applied.length === 1 && c.explicitCompositionReviewed,
        'PVS1_COMPOSITION_DISPOSITION_MISMATCH');
        const spliceDelta = splices[0].referenceEditStart0 - splices[0].referenceEditEnd0;
        const allele = context.variant.evidenceQueryAllele;
        check(c.netCdsLengthChange === spliceDelta + (applied.length ? allele.alternate.length - allele.reference.length : 0), 'PVS1_COMPOSITION_LENGTH_MISMATCH');
    }
    return edits;
}

function topologyValid(f, s, c) {
    const t = f.transcriptTopology, reference = s.referenceTopology, altered = s.alteredTopology;
    check(obj(t) && obj(reference) && obj(altered) && version(t.methodVersion,1) && version(altered.methodVersion,1) &&
        ['SINGLE_EXON','MULTI_EXON_CODING_JUNCTION','LAST_JUNCTION_IN_3_PRIME_UTR'].includes(t.kind) &&
        ['methodVersion','kind','exonCount','cdsLength','lastJunctionCdsPosition'].every(k => same(t[k],altered[k])) &&
        reference.scope === 'REFERENCE_TRANSCRIPT' && positive(reference.exonCount) && positive(t.exonCount) &&
        reference.exonCount === f.referenceExonTotal && f.exonTotal === reference.exonCount && f.alteredExonTotal === t.exonCount &&
        reference.cdsLength === c.referenceCdsLength && t.cdsLength === c.alteredCdsLength &&
        same(f.referenceLastJunctionCdsPosition,reference.lastJunctionCdsPosition) && same(f.lastJunctionCdsPosition,t.lastJunctionCdsPosition) &&
        same(f.mutantLastJunctionCdsPosition,t.lastJunctionCdsPosition) && typeof t.utrApplicabilityBound === 'boolean', 'PVS1_ALTERED_TOPOLOGY_BINDING_INVALID');
    if (t.kind === 'SINGLE_EXON') check(t.exonCount === 1 && t.lastJunctionCdsPosition === null && f.distanceToLastJunction == null, 'PVS1_SINGLE_EXON_TOPOLOGY_INVALID');
    else check(t.exonCount > 1 && int(t.lastJunctionCdsPosition) && (t.kind === 'LAST_JUNCTION_IN_3_PRIME_UTR'
        ? t.lastJunctionCdsPosition >= t.cdsLength : t.lastJunctionCdsPosition < t.cdsLength), 'PVS1_LAST_JUNCTION_INVALID');
    const retained = rows(s,'retainedExons'), junctions = rows(s,'alteredJunctions');
    check(retained.total === t.exonCount && junctions.total === t.exonCount - 1, 'PVS1_TOPOLOGY_COUNT_MISMATCH');
    for (const [i,e] of retained.list.entries()) {
        check(e.alteredOrdinal === i + 1 && positive(e.referenceOrdinal) && e.referenceOrdinal <= reference.exonCount && int(e.alteredCdsStart) &&
            int(e.alteredCdsEnd) && e.alteredCdsEnd >= e.alteredCdsStart &&
            (i === 0 ? e.alteredCdsStart <= 1 : e.referenceOrdinal > retained.list[i-1].referenceOrdinal && e.alteredCdsStart === retained.list[i-1].alteredCdsEnd + 1), 'PVS1_RETAINED_EXON_GEOMETRY_INVALID');
    }
    if (!retained.truncated) check(retained.list.at(-1).alteredCdsEnd >= t.cdsLength, 'PVS1_RETAINED_EXON_CDS_COVERAGE_INVALID');
    for (const [i,j] of junctions.list.entries()) {
        const left = retained.list[i], right = retained.list[i+1];
        check(int(j.alteredCdsPosition) && (i === 0 || j.alteredCdsPosition > junctions.list[i-1].alteredCdsPosition) &&
            (!left || j.leftReferenceOrdinal === left.referenceOrdinal && j.alteredCdsPosition === left.alteredCdsEnd) &&
            (!right || j.rightReferenceOrdinal === right.referenceOrdinal), 'PVS1_ALTERED_JUNCTION_GEOMETRY_INVALID');
    }
    if (!junctions.truncated && junctions.total) check(junctions.list.at(-1).alteredCdsPosition === t.lastJunctionCdsPosition, 'PVS1_LAST_JUNCTION_MISMATCH');
    return { retained, junctions, topology: t };
}

function inFrameValid(a, s, f, criterion, c) {
    check(a?.schemaVersion === 'pvs1-splice-inframe-assessment-v1' && version(a.operatorVersion,1) && text(a.policyReviewId) &&
        positive(a.deleteStartCds) && positive(a.deleteEndCds) && a.deleteEndCds >= a.deleteStartCds &&
        a.deletedCdsBases === a.deleteEndCds - a.deleteStartCds + 1 && a.deletedCdsBases % 3 === 0 && a.nativeStopRetained === true &&
        a.referenceProteinLength === c.referenceCdsLength / 3 - 1 && a.alteredProteinLength === c.alteredCdsLength / 3 - 1 &&
        a.netCodonDeletion === a.deletedCdsBases / 3 && a.deletedProteinResidues === a.netCodonDeletion &&
        a.alteredProteinLength === a.referenceProteinLength - a.netCodonDeletion && a.deletedProteinResidueMetric === 'NET_PROTEIN_LENGTH_DECREASE' &&
        a.codonAligned === ((a.deleteStartCds - 1) % 3 === 0) &&
        a.firstTouchedReferenceResidue === Math.floor((a.deleteStartCds - 1) / 3) + 1 && a.lastTouchedReferenceResidue === Math.floor((a.deleteEndCds - 1) / 3) + 1 &&
        a.sequenceDerivationStatus === (a.codonAligned ? 'EXACT_LOCAL_CODON_ALIGNED_DELETION' : 'EXACT_LOCAL_INFRAME_DELETION_WITH_FUSION_CODON') &&
        !Object.hasOwn(f,'ptcStart') && !Object.hasOwn(f,'ptcEnd') && !Object.hasOwn(f,'nmdPrediction') &&
        c.compositionStatus !== 'SAME_ALLELE_CODING_AND_SPLICE_EDITS_APPLIED', 'PVS1_INFRAME_SEQUENCE_SUMMARY_INVALID');
    const splice = c.edits.find(e => e.origin === 'SPLICE_PROJECTION' && e.status === 'APPLIED');
    if (splice) check(a.deleteStartCds === splice.referenceEditStart0+1 && a.deleteEndCds === splice.referenceEditEnd0 &&
        s.rnaReadingFrame === 'PRESERVED', 'PVS1_INFRAME_EDIT_GEOMETRY_MISMATCH');
    const first = Math.ceil((a.deleteStartCds + 2) / 3), last = Math.floor(a.deleteEndCds / 3), removed = Math.max(0,last-first+1);
    check(a.fullyRemovedReferenceResidues === removed && (removed ? a.firstFullyRemovedReferenceResidue === first && a.lastFullyRemovedReferenceResidue === last
        : a.firstFullyRemovedReferenceResidue === null && a.lastFullyRemovedReferenceResidue === null), 'PVS1_INFRAME_REMOVED_RESIDUES_INVALID');
    if (!a.codonAligned) {
        const j = a.junctionEffect;
        check(obj(j) && /^[ACGT]{3}$/.test(j.alteredCodon || '') && /^[A-Z*]$/.test(j.alteredAminoAcid || '') &&
            j.alteredAminoAcid !== '*' && j.alteredProteinPosition === a.firstTouchedReferenceResidue &&
            j.alteredAminoAcid === translateCodon(j.alteredCodon) &&
            j.leftReferenceResidue === a.firstTouchedReferenceResidue && j.rightReferenceResidue === a.lastTouchedReferenceResidue &&
            j.leftRetainedBaseCount === (a.deleteStartCds-1)%3 && j.rightRetainedBaseCount === 3-a.deleteEndCds%3, 'PVS1_INFRAME_FUSION_CODON_INVALID');
    }
    if (criterion.status === 'NOT_MET') {
        check(a.status === 'NOT_MET' && NEGATIVE.has(a.reasonCode) && a.reasonCode.startsWith('PVS1_SPLICE_INFRAME_') && a.reasonCode === s.reasonCode && a.reasonCode === criterion.reasonCode &&
            !a.selectedRule && !a.strength, 'PVS1_INFRAME_NEGATIVE_INVALID');
        return;
    }
    if (criterion.status !== 'MET') return;
    const rule = a.selectedRule, checks = rows(a,'ruleChecks');
    check(a.status === 'MET' && strengths.includes(a.strength) && obj(rule) && text(rule.ruleId) && rule.strength === a.strength &&
        ['CRITICAL_REGION','DELETED_PROTEIN_FRACTION'].includes(rule.predicate) && checks.total > 0, 'PVS1_INFRAME_ADOPTED_RULE_INVALID');
    if (rule.predicate === 'CRITICAL_REGION') {
        check(positive(rule.start) && positive(rule.end) && rule.start <= rule.end && rule.end <= a.referenceProteinLength && positive(rule.minimumAffectedResidues) &&
            Math.max(0,Math.min(last,rule.end)-Math.max(first,rule.start)+1) >= rule.minimumAffectedResidues, 'PVS1_CRITICAL_REGION_NOT_REMOVED');
    } else {
        check(positive(rule.numerator) && positive(rule.denominator) && rule.numerator < rule.denominator && ['GT','LT'].includes(rule.comparison), 'PVS1_INFRAME_FRACTION_RULE_INVALID');
        const left = BigInt(a.netCodonDeletion) * BigInt(rule.denominator), right = BigInt(a.referenceProteinLength) * BigInt(rule.numerator);
        check(rule.comparison === 'GT' ? left > right : left < right, 'PVS1_INFRAME_EXACT_FRACTION_MISMATCH');
        check(checks.truncated || checks.list.some(row => row.status === 'MATCH' && row.actualNumerator === a.netCodonDeletion && row.actualDenominator === a.referenceProteinLength &&
            String(row.leftProduct) === String(left) && String(row.rightProduct) === String(right)), 'PVS1_INFRAME_FRACTION_TRACE_MISMATCH');
    }
    if (!checks.truncated) check(checks.list.filter(row => row.status === 'MATCH').length === 1 && checks.list.every(row =>
        ['MATCH','NO_MATCH','NOT_APPLICABLE_KNOWN_CRITICAL_REGION_AFFECTED'].includes(row.status)), 'PVS1_INFRAME_RULE_SELECTION_CONFLICT');
    check(s.adoptedStrength === lower(a.strength,s.maximumStrength,f.policyStrengthCap), 'PVS1_INFRAME_STRENGTH_CAP_MISMATCH');
}

function referenceMapping(c, context, positions) {
    if (c.editsTruncated) return null;
    const allele = context.variant.evidenceQueryAllele, delta = allele.alternate.length-allele.reference.length;
    const edits = c.edits.filter(e => e.status === 'APPLIED').sort((a,b) => a.referenceEditStart0-b.referenceEditStart0);
    return positions.map(position => {
        let referenceCursor=0, alteredCursor=0;
        for (const e of edits) {
            check(e.referenceEditStart0 >= referenceCursor, 'PVS1_APPLIED_EDITS_OVERLAP');
            const unchanged=e.referenceEditStart0-referenceCursor;
            if (position-1 < alteredCursor+unchanged) return referenceCursor+position-alteredCursor;
            alteredCursor+=unchanged;
            const inserted=e.origin === 'SPLICE_PROJECTION' ? 0 : e.referenceEditEnd0-e.referenceEditStart0+delta;
            check(inserted >= 0, 'PVS1_CODING_EDIT_LENGTH_INVALID');
            if (position-1 < alteredCursor+inserted) return 0;
            alteredCursor+=inserted;referenceCursor=e.referenceEditEnd0;
        }
        return referenceCursor+position-alteredCursor;
    });
}


const proofPositiveInt = v => Number.isSafeInteger(v) && v > 0 && v <= 2147483647;
const proofText = v => typeof v === 'string' && v.trim().length > 0 && v.length <= 256 && !/[\r\n]/.test(v);
const proofList = (v,max=32) => Array.isArray(v) && v.length > 0 && v.length <= max;
function sourceIdsValid(value,known) {
    return proofList(value) && value.every(proofText) && new Set(value).size === value.length && (!known || value.every(id=>known.has(id)));
}
function proofSources(proof) {
    check(proofList(proof.sources) && proof.sources.every(source=>obj(source) && proofText(source.sourceId) && sha(source.sha256) &&
        (proofText(source.version) || proofText(source.sourceVersion))) && new Set(proof.sources.map(source=>source.sourceId)).size===proof.sources.length,
    'PVS1_ESCAPE_PUBLIC_SOURCE_INVALID');
    for(const source of proof.sources) {
        if(source.url!==undefined) {
            let url;try{url=new URL(source.url);}catch{check(false,'PVS1_ESCAPE_PUBLIC_SOURCE_URL_INVALID');}
            check(typeof source.url==='string' && source.url.length<=2048 && ![...source.url].some(character=>character.charCodeAt(0)<=32 || character.charCodeAt(0)===127) && ['http:','https:'].includes(url.protocol) &&
                !!url.hostname && !url.username && !url.password && !url.search && !url.hash && !source.urlOmitted,'PVS1_ESCAPE_PUBLIC_SOURCE_URL_INVALID');
        } else check(source.urlOmitted===true,'PVS1_ESCAPE_PUBLIC_SOURCE_URL_INVALID');
    }
    return new Set(proof.sources.map(source=>source.sourceId));
}
function checkEscapeRule(rule,row,effect,known) {
    check(obj(rule) && proofText(rule.ruleId) && strengths.includes(rule.strength) && sourceIdsValid(rule.sourceIds,known) &&
        obj(row) && ['MATCH','NO_MATCH'].includes(row.status),'PVS1_ESCAPE_PUBLIC_RULE_INVALID');
    let hit;
    if(rule.predicate==='CRITICAL_REGION') {
        check(positive(rule.start) && positive(rule.end) && rule.start<=rule.end && rule.end<=effect.referenceProteinLength &&
            positive(rule.minimumAffectedResidues) && rule.minimumAffectedResidues<=rule.end-rule.start+1,'PVS1_ESCAPE_PUBLIC_RULE_INVALID');
        const affected=Math.max(0,Math.min(effect.referenceProteinLength,rule.end)-Math.max(effect.firstAlteredResidue,rule.start)+1);
        check(row.affectedResiduesInRegion===affected,'PVS1_ESCAPE_PUBLIC_RULE_ARITHMETIC_INVALID');
        hit=affected>=rule.minimumAffectedResidues;
    } else {
        check(rule.predicate==='PROTEIN_LOSS_FRACTION' && ['REFERENCE_TAIL_ALTERED_OR_REMOVED','NET_PROTEIN_SHORTENING'].includes(rule.metric) &&
            proofPositiveInt(rule.numerator) && proofPositiveInt(rule.denominator) && rule.numerator<rule.denominator && ['GT','LT'].includes(rule.comparison) &&
            rule.strength!=='VERY_STRONG','PVS1_ESCAPE_PUBLIC_RULE_INVALID');
        const numerator=rule.metric==='REFERENCE_TAIL_ALTERED_OR_REMOVED'?effect.referenceResiduesAffected:effect.netProteinShortening;
        const left=BigInt(numerator)*BigInt(rule.denominator),right=BigInt(effect.referenceProteinLength)*BigInt(rule.numerator);
        check(row.actualNumerator===numerator && row.actualDenominator===effect.referenceProteinLength &&
            Number.isSafeInteger(row.leftProduct) && Number.isSafeInteger(row.rightProduct) &&
            String(row.leftProduct)===String(left) && String(row.rightProduct)===String(right),'PVS1_ESCAPE_PUBLIC_RULE_ARITHMETIC_INVALID');
        hit=rule.comparison==='GT'?left>right:left<right;
        check(left!==right,'PVS1_ESCAPE_PUBLIC_FRACTION_BOUNDARY_UNSPECIFIED');
    }
    check(row.status===(hit?'MATCH':'NO_MATCH'),'PVS1_ESCAPE_PUBLIC_RULE_ARITHMETIC_INVALID');
}
function checkPositionProof(value,scope,known,f,c,coding) {
    check(obj(value),'PVS1_ESCAPE_PUBLIC_POSITION_INVALID');
    if(value.status==='NOT_DECLARED') {check(!value.conditions,'PVS1_ESCAPE_PUBLIC_POSITION_INVALID');return;}
    check(value.status===(scope==='escape'?'CHECKED':'NOT_USED_BY_ESCAPE_METHOD') && proofList(value.conditions,16), 'PVS1_ESCAPE_PUBLIC_POSITION_INVALID');
    check(new Set(value.conditions.map(condition=>condition.conditionId)).size===value.conditions.length,'PVS1_ESCAPE_PUBLIC_POSITION_INVALID');
    for(const condition of value.conditions) {
        check(obj(condition) && proofText(condition.conditionId) && sourceIdsValid(condition.sourceIds,known) &&
            ['REFERENCE_EVENT_SPAN','REFERENCE_PTC_SPAN','ALTERED_PTC_SPAN'].includes(condition.coordinate) &&
            (condition.minimumInclusive!==undefined || condition.maximumExclusive!==undefined) &&
            (condition.minimumInclusive===undefined || positive(condition.minimumInclusive)) &&
            (condition.maximumExclusive===undefined || positive(condition.maximumExclusive)) &&
            (condition.minimumInclusive===undefined || condition.maximumExclusive===undefined || condition.minimumInclusive<condition.maximumExclusive), 'PVS1_ESCAPE_PUBLIC_POSITION_INVALID');
        if(scope!=='escape'){check(!condition.status && !condition.checkedPositions,'PVS1_ESCAPE_PUBLIC_POSITION_INVALID');continue;}
        check(condition.status==='PASS' && proofList(condition.checkedPositions,8) && condition.checkedPositions.every(position=>positive(position) &&
            (condition.minimumInclusive===undefined || position>=condition.minimumInclusive) && (condition.maximumExclusive===undefined || position<condition.maximumExclusive)), 'PVS1_ESCAPE_PUBLIC_POSITION_INVALID');
        if(coding) {
            // These are the producer's typed coordinates, not a new sequence
            // derivation. Insertion flanks, duplicates and order are normative.
            const expected=coding.conditionPositions[condition.coordinate];
            check(Array.isArray(expected) && expected.every(positive) && same(condition.checkedPositions,expected), 'PVS1_ESCAPE_PUBLIC_POSITION_BINDING_INVALID');
            continue;
        }
        const expected=condition.coordinate==='ALTERED_PTC_SPAN'?[f.ptcStart,f.ptcEnd]:condition.coordinate==='REFERENCE_PTC_SPAN'
            ?[f.ptcReferenceStart,f.ptcReferenceMiddle,f.ptcReferenceEnd]:null;
        if(expected)check(same(condition.checkedPositions,[...new Set(expected)]),'PVS1_ESCAPE_PUBLIC_POSITION_BINDING_INVALID');
        else {
            // Pvs1NmdConditions.positions checks the splice deletion alone, or
            // both normalized and HGVS coding representations (including both
            // insertion flanks). A bounded list without either witness cannot
            // independently establish a reference-event PASS.
            check(!c.editsTruncated && Array.isArray(c.edits),'PVS1_ESCAPE_PUBLIC_REFERENCE_EVENT_BOUNDARY_REQUIRED');
            let positions;
            if(f.method==='SOURCE_BOUND_SPLICE_CONSEQUENCE') {
                const edits=c.edits.filter(edit=>edit.origin==='SPLICE_PROJECTION'&&edit.status==='APPLIED');
                check(edits.length===1 && edits[0].referenceEditEnd0>edits[0].referenceEditStart0,'PVS1_ESCAPE_PUBLIC_REFERENCE_EVENT_BOUNDARY_REQUIRED');
                positions=[edits[0].referenceEditStart0+1,edits[0].referenceEditEnd0];
            } else {
                check(f.method==='SOURCE_BOUND_CODING_EDITS' && f.variantMechanism==='FRAMESHIFT','PVS1_ESCAPE_PUBLIC_REFERENCE_EVENT_BOUNDARY_REQUIRED');
                const normal=c.edits.filter(edit=>edit.origin==='CODING_ALLELE'&&edit.id?.startsWith('allele:')&&edit.status==='APPLIED');
                const hgvs=c.edits.filter(edit=>edit.origin==='CODING_ALLELE'&&edit.id?.startsWith('hgvs:')&&edit.status==='DUPLICATE_SEQUENCE_EDIT');
                check(normal.length===1 && hgvs.length===1,'PVS1_ESCAPE_PUBLIC_REFERENCE_EVENT_BOUNDARY_REQUIRED');
                positions=[normal[0],hgvs[0]].flatMap(edit=>edit.referenceEditStart0===edit.referenceEditEnd0
                    ? [edit.referenceEditStart0,edit.referenceEditStart0+1] : [edit.referenceEditStart0+1,edit.referenceEditEnd0]);
            }
            check(positions.every(positive) && same(condition.checkedPositions,positions),'PVS1_ESCAPE_PUBLIC_POSITION_BINDING_INVALID');
        }
    }
}
function escapeProofValid(criterion,context,options,coding=null) {
    const f=criterion?.details?.pvs1Facts,p=f?.escapeAdoptionProof;
    check(obj(p),'PVS1_SPLICE_ESCAPE_PUBLIC_RULE_PROOF_REQUIRED');
    check(p.schemaVersion==='pvs1-escape-adoption-proof-v1' && version(p.methodVersion,0) && p.scientificApprovalCreated===false &&
        p.rescoringPerformed===false && ['AVAILABLE','UNAVAILABLE','NOT_ADOPTED'].includes(p.status) && code(p.reasonCode),'PVS1_ESCAPE_PUBLIC_PROOF_INVALID');
    if(p.status!=='AVAILABLE')check(Object.keys(p).every(key=>['schemaVersion','methodVersion','scientificApprovalCreated','rescoringPerformed','status','reasonCode'].includes(key)), 'PVS1_ESCAPE_PUBLIC_NONADOPTION_CONTRADICTION');
    if(p.status==='UNAVAILABLE')check(false,'PVS1_ESCAPE_PUBLIC_PROOF_UNAVAILABLE');
    if(p.status==='NOT_ADOPTED') {
        check((criterion.status!=='MET' || f.nmdPrediction!=='NMD_NOT_PREDICTED') && p.reasonCode==='PVS1_ESCAPE_PUBLIC_PROOF_NO_ADOPTED_ESCAPE' &&
            !p.selectedRule && !p.strengthDerivation && !p.bindings,'PVS1_ESCAPE_PUBLIC_NONADOPTION_CONTRADICTION');return;
    }
    check(criterion.status==='MET' && f.nmdPrediction==='NMD_NOT_PREDICTED' && p.reasonCode==='PVS1_ESCAPE_PUBLIC_PROOF_VERIFIED', 'PVS1_ESCAPE_PUBLIC_PROOF_STATUS_CONTRADICTION');
    const c=f.codingEditComposition || f.codingEditDerivation,identity=contextView(context,options.identityHash),b=p.bindings,e=p.proteinEffect,cap=p.strengthDerivation;
    check((coding || obj(c)) && obj(b) && ['policySha256','escapeScopeSha256','selectedRuleSha256','transcriptModelSha256','resolvedInputSha256','selectionSha256','evaluationSha256','alleleSha256'].every(key=>sha(b[key])) &&
        /^evaluation-sha256-[a-f0-9]{64}$/.test(b.evaluationId || '') && b.transcriptModelSha256===(coding ? coding.model.modelSha256 : c.binding?.modelSha256) && b.alleleSha256===identity.alleleHash &&
        criterion.details.originalResultSha256===b.evaluationSha256,'PVS1_ESCAPE_PUBLIC_IDENTITY_INVALID');
    const scope=p.scope;
    check(obj(scope) && scope.hgncId===context.gene?.hgncId && scope.mondoId===context.disease?.mondoId && scope.moi===context.moi &&
        scope.assembly===context.variant?.evidenceQueryAllele?.assembly && scope.transcript===identity.transcript &&
        ['NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT','NATIVE_NONPROJECTED_SNV'].includes(scope.variantType) &&
        ['GENERAL_POLICY_SELECTED','SPECIALIZED_POLICY_SELECTED'].includes(scope.selectionOutcome),'PVS1_ESCAPE_PUBLIC_SCOPE_INVALID');
    check(obj(p.policy) && p.policy.schemaVersion===f.policySchema && ['policyId','policyVersion','reviewId'].every(key=>proofText(p.policy[key])), 'PVS1_ESCAPE_PUBLIC_POLICY_INVALID');
    const known=proofSources(p),required=new Set();
    const ids=value=>{check(sourceIdsValid(value,known),'PVS1_ESCAPE_PUBLIC_SOURCE_INVALID');value.forEach(id=>required.add(id));};
    check(p.review?.status==='REVIEWED_FOR_PVS1_ESCAPE' && proofText(p.review.reviewId),'PVS1_ESCAPE_PUBLIC_REVIEW_INVALID');ids(p.review.sourceIds);
    check(obj(e) && positive(e.referenceProteinLength) && positive(e.firstAlteredResidue) && e.firstAlteredResidue<=e.referenceProteinLength &&
        e.referenceProteinLength===(coding ? coding.model.proteinLength : c.referenceCdsLength/3-1) && int(e.alteredProteinLength) && e.alteredProteinLength>=0 &&
        e.alteredProteinLength===(f.ptcStart-1)/3 && e.firstAlteredResidue<=e.alteredProteinLength+1 &&
        e.referenceResiduesAffected===e.referenceProteinLength-e.firstAlteredResidue+1 &&
        e.netProteinShortening===Math.max(0,e.referenceProteinLength-e.alteredProteinLength),'PVS1_ESCAPE_PUBLIC_PROTEIN_EFFECT_INVALID');
    check(proofList(p.ruleChecks) && new Set(p.ruleChecks.map(row=>row.rule?.ruleId)).size===p.ruleChecks.length,'PVS1_ESCAPE_PUBLIC_RULE_CHECKS_INVALID');
    for(const row of p.ruleChecks){checkEscapeRule(row.rule,row,e,known);ids(row.rule.sourceIds);}
    const matched=p.ruleChecks.filter(row=>row.status==='MATCH');
    check(matched.length===1 && same(matched[0].rule,p.selectedRule) &&
        same(Object.fromEntries(Object.entries(matched[0]).filter(([key])=>key!=='rule')),p.ruleCheck),'PVS1_ESCAPE_PUBLIC_SELECTED_RULE_INVALID');
    ids(p.selectedRule.sourceIds);
    check(p.selectedRule.strength!=='VERY_STRONG' || scope.selectionOutcome==='SPECIALIZED_POLICY_SELECTED','PVS1_ESCAPE_PUBLIC_SPECIALIZATION_REQUIRED');
    check(obj(cap) && cap.sourceRuleStrength===p.selectedRule.strength && cap.parentPolicyCap===f.policyStrengthCap &&
        strengths.includes(cap.parentPolicyCap),'PVS1_ESCAPE_PUBLIC_CAP_INVALID');
    const caps=[cap.sourceRuleStrength,cap.parentPolicyCap];
    if(f.spliceAssessment) {
        check(['spliceScopeCap','outcomeReviewCap','localMaximumStrength'].every(key=>strengths.includes(cap[key])) &&
            cap.localMaximumStrength===lower(cap.spliceScopeCap,cap.outcomeReviewCap) && cap.localMaximumStrength===f.spliceAssessment.maximumStrength &&
            sha(b.spliceMaterialSha256) && c.edits.some(edit=>edit.origin==='SPLICE_PROJECTION'&&edit.evidenceBindingSha256===b.spliceMaterialSha256), 'PVS1_ESCAPE_PUBLIC_CAP_INVALID');
        caps.push(cap.localMaximumStrength);
        for(const review of [p.localReviews?.scope,p.localReviews?.outcome]) {
            check(review?.status==='REVIEWED_FOR_PVS1_SPLICE' && proofText(review.reviewId),'PVS1_ESCAPE_PUBLIC_REVIEW_INVALID');ids(review.sourceIds);
        }
        for(const source of f.spliceAssessment.sourceRecords || []) {
            const match=p.sources.find(item=>item.sourceId===source.sourceId);if(match)check(match.sha256===source.sha256,'PVS1_ESCAPE_PUBLIC_SOURCE_INVALID');
        }
    } else check(!p.localReviews && !b.spliceMaterialSha256 && ['spliceScopeCap','outcomeReviewCap','localMaximumStrength'].every(key=>!Object.hasOwn(cap,key)), 'PVS1_ESCAPE_PUBLIC_CAP_INVALID');
    check(cap.adoptedStrength===lower(...caps) && cap.adoptedStrength===criterion.appliedStrength &&
        (!f.spliceAssessment || cap.adoptedStrength===f.spliceAssessment.adoptedStrength),'PVS1_ESCAPE_PUBLIC_CAP_INVALID');
    check(proofList(p.exonChecks) && proofList(p.affectedReferenceExonOrdinals) && p.affectedReferenceExonOrdinals.length===p.exonChecks.length &&
        new Set(p.affectedReferenceExonOrdinals).size===p.exonChecks.length,'PVS1_ESCAPE_PUBLIC_EXON_INVENTORY_INVALID');
    for(const [index,exon]of p.exonChecks.entries()) {
        check(obj(exon) && positive(exon.ordinal) && exon.ordinal<=(f.referenceExonTotal ?? f.exonTotal) && exon.ordinal===p.affectedReferenceExonOrdinals[index] &&
            exon.biologicalRelevance==='PRESENT' && ['NOT_FREQUENT','UNKNOWN'].includes(exon.populationLof),'PVS1_ESCAPE_PUBLIC_EXON_INVENTORY_INVALID');ids(exon.sourceIds);
        if(p.ruleChecks.some(row=>row.rule.predicate==='PROTEIN_LOSS_FRACTION'))check(exon.populationLof==='NOT_FREQUENT','PVS1_ESCAPE_PUBLIC_POPULATION_UNRESOLVED');
    }
    check(obj(p.positionConditions),'PVS1_ESCAPE_PUBLIC_POSITION_INVALID');
    checkPositionProof(p.positionConditions.escape,'escape',known,f,c,coding);checkPositionProof(p.positionConditions.nmd,'nmd',known,f,c,coding);
    for(const positionScope of Object.values(p.positionConditions))for(const condition of positionScope.conditions || [])ids(condition.sourceIds);
    check(required.size===known.size,'PVS1_ESCAPE_PUBLIC_SOURCE_INVALID');
    // selectedRuleSha256 hashes the raw rule including its omitted private basis.
    // The public partial rule cannot be rehashed against that distinct hash scope.
    // Its opaque raw digest remains upstream provenance; the published adopted
    // witness is checked above without inventing a private-prose approval gate.
}
export function inspectPvs1EscapeAdoptionProof(criterion,context,options={}) {
    try{escapeProofValid(criterion,context,options);return {valid:true,issues:[]};}
    catch(error){return {valid:false,issues:[error?.message?.startsWith('PVS1_')?error.message:'PVS1_ESCAPE_PUBLIC_PROOF_MALFORMED']};}
}

// Optional, coding-only public witness. Its presence is intentionally independent
// of the unchanged ordinary facts 1.1 / complex coding facts 1.7 versions.
export const isPvs1CodingEscapeAssessment = facts => obj(facts) && Object.hasOwn(facts,'codingEscapeAssessment');
const only = (value,keys) => obj(value) && Object.keys(value).every(key=>keys.includes(key));
const clone = value => JSON.parse(JSON.stringify(value));
const CODING_HEADERS=['schemaVersion','methodVersion','proofPointer','scientificApprovalCreated','rescoringPerformed',
    'sourceQualifiedResearch','sourceQualification','status','reasonCode'];
// Exact rejection vocabulary of the committed 3983da90 producer. These codes
// describe projection availability; arbitrary caller reason strings cannot
// manufacture a qualified availability view.
const CODING_PROJECTION_FAILURES=new Set(['ANNOTATION_BINDING_MISMATCH','EVENT_BOUNDARY_INVALID','EVENT_DELTA_MISMATCH',
    'HGVS_CODON_MISMATCH','HGVS_EQUIVALENCE_REQUIRED','HGVS_POSITION_MISMATCH','HGVS_REQUIRED','METHOD_CONTRADICTION',
    'PTC_MAPPING_INVALID','PTC_SPAN_INVALID','STOP_POSITION_MISMATCH'].map(reason=>`PVS1_ESCAPE_PUBLIC_PROOF_CODING_${reason}`));
CODING_PROJECTION_FAILURES.add('PVS1_ESCAPE_PUBLIC_PROOF_NUMERIC_FACT_INVALID');
CODING_PROJECTION_FAILURES.add('PVS1_CODING_ESCAPE_PUBLIC_FACTS_INPUT_INVALID');
const CODING_PROOF_FAILURES=new Set(['CAP_BINDING_MISMATCH','CONTEXT_BINDING_MISMATCH','CONTEXT_REQUIRED','EVALUATION_BINDING_MISMATCH',
    'EXECUTION_BINDING_MISMATCH','EXON_CHECKS_INCOMPLETE','EXON_CHECKS_MISMATCH','FIELD_LIMIT_EXCEEDED','INPUT_REQUIRED','INPUT_INVALID',
    'MATERIAL_BINDING_MISMATCH','MATERIAL_QUALIFICATION_MISMATCH','MODEL_BINDING_MISMATCH','MODEL_FACTS_BINDING_MISMATCH','NUMERIC_FACT_INVALID',
    'POLICY_BINDING_MISMATCH','POSITION_CHECKS_MISMATCH','PROTEIN_EFFECT_BINDING_MISMATCH','PROTEIN_EFFECT_MISMATCH','REVIEW_MISMATCH',
    'RULE_ARITHMETIC_MISMATCH','RULE_BINDING_MISMATCH','RULE_CHECKS_INCOMPLETE','RULE_PREDICATE_UNSUPPORTED','SCOPE_BINDING_MISMATCH',
    'SELECTED_RULE_MISMATCH','SELECTION_BINDING_MISMATCH','SOURCE_BINDING_MISMATCH','SOURCE_LIMIT_EXCEEDED','SOURCE_QUALIFICATION_MISMATCH',
    'SPECIALIZED_STRENGTH_REQUIRED','STRENGTH_MISMATCH','TRACE_BINDING_MISMATCH'].map(reason=>`PVS1_ESCAPE_PUBLIC_PROOF_${reason}`));

function codingBase(criterion,context,options) {
    const f=criterion?.details?.pvs1Facts,identity=contextView(context,options.identityHash),supply=f?.materialSupply;
    check(criterion?.criterionId==='ACMG_AMP_2015:PVS1' && f?.schemaVersion==='pvs1-selected-facts-v1' &&
        f.scope==='REVIEWED_SELECTED_TRANSCRIPT' && f.clinicalValidationCompleted===false &&
        f.doubleCountingConstraint==='NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE' &&
        (f.methodVersion==='1.1.0' && !Object.hasOwn(f,'method') && !isPvs1SpliceSummary(f) ||
            version(f.methodVersion,7) && f.method==='SOURCE_BOUND_CODING_EDITS' && obj(f.codingEditDerivation)) &&
        !f.spliceAssessment && !f.codingEditComposition && !f.inFrameAssessment, 'PVS1_CODING_ESCAPE_METHOD_INVALID');
    check(['MET','NOT_MET','NOT_EVALUATED'].includes(criterion.status) && code(criterion.reasonCode) &&
        (criterion.status==='MET'?strengths.includes(criterion.appliedStrength):criterion.appliedStrength==='NONE') &&
        typeof f.sourceQualifiedResearch==='boolean' && [...strengths,'UNAVAILABLE'].includes(f.policyStrengthCap), 'PVS1_CODING_ESCAPE_STATUS_INVALID');
    check(transcript(f.selectedTranscript) && f.selectedTranscript===identity.transcript, 'PVS1_CODING_ESCAPE_CONTEXT_INVALID');
    if(options.profile && version(f.methodVersion,7)) {
        const release=options.profile.moduleBindings?.pvs1?.runtimeBinding?.pvs1SelectedVersion;
        check(version(release,2),'PVS1_CODING_ESCAPE_RUNTIME_UNSUPPORTED');
    }
    if(criterion.status==='MET') {
        check(f.knowledgeStatus==='READY' && proofText(f.knowledgeReason) && strengths.includes(f.policyStrengthCap) &&
            strengths.indexOf(criterion.appliedStrength)<=strengths.indexOf(f.policyStrengthCap) &&
            supply?.status==='READY' && supply.bindingMethod==='EXACT_FULL_TRANSCRIPT_EQUIVALENCE' &&
            supply.policyModelUnchanged===true && positive(supply.verifiedExonCount) && code(supply.reasonCode) &&
            /^pvs1-truncating-nmd-policy-v[1-4]$/.test(f.policySchema || '') &&
            f.sequenceDerivationStatus==='GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE' &&
            f.nmdPrediction==='NMD_NOT_PREDICTED' && f.escapeAssessment?.schemaVersion==='pvs1-nmd-escape-assessment-v1' &&
            f.escapeAssessment.status==='MET' && ['cdsPosition','ptcStart','ptcEnd','exonOrdinal','exonTotal'].every(key=>positive(f[key])) &&
            f.exonOrdinal<=f.exonTotal && supply.verifiedExonCount===f.exonTotal && f.ptcEnd===f.ptcStart+2 &&
            (f.ptcStart-1)%3===0 && sha(criterion.details.originalResultSha256), 'PVS1_CODING_ESCAPE_PARENT_FACTS_INVALID');
        const topology=f.transcriptTopology;
        if(topology)check(topology.version==='1.5.0' && topology.exonCount===f.exonTotal && positive(topology.cdsLength) &&
            typeof topology.utrApplicabilityBound==='boolean' && ['SINGLE_EXON','LAST_JUNCTION_IN_3_PRIME_UTR'].includes(topology.kind) &&
            (topology.kind==='SINGLE_EXON'?topology.exonCount===1 && topology.referenceLastJunctionCdsPosition===null &&
                f.lastJunctionCdsPosition==null && f.distanceToLastJunction==null:
                topology.exonCount>1 && topology.utrApplicabilityBound && int(topology.referenceLastJunctionCdsPosition) &&
                topology.referenceLastJunctionCdsPosition>=topology.cdsLength &&
                (f.referenceLastJunctionCdsPosition ?? f.lastJunctionCdsPosition)===topology.referenceLastJunctionCdsPosition),
        'PVS1_CODING_ESCAPE_TOPOLOGY_INVALID');
        if(topology?.kind!=='SINGLE_EXON')check(int(f.lastJunctionCdsPosition) && f.lastJunctionCdsPosition>=0 &&
            int(f.distanceToLastJunction) && f.distanceToLastJunction===f.lastJunctionCdsPosition-f.ptcEnd, 'PVS1_CODING_ESCAPE_JUNCTION_INVALID');
    }
    if(f.codingEditDerivation)editsValid(f.codingEditDerivation,f,context,identity,false);
    return {f,identity};
}

function codingModel(p,m,hash) {
    check(only(m,['schemaVersion','modelSha256','proteinLength','cdsLength']) && m.schemaVersion==='pvs1-transcript-model-facts-v1' &&
        sha(m.modelSha256) && positive(m.cdsLength) && m.cdsLength<=1000000 && m.cdsLength%3===0 &&
        m.proteinLength===m.cdsLength/3-1 && positive(m.proteinLength) && same(m,p.transcriptModelFacts) &&
        p.bindings?.transcriptModelSha256===m.modelSha256 && p.bindings.transcriptModelFactsSha256===hash(m), 'PVS1_CODING_ESCAPE_MODEL_INVALID');
    return m;
}
function codingWitness(a,f,context,options) {
    const p=f.escapeAdoptionProof,m=codingModel(p,a.transcriptModelFacts,options.identityHash),s=a.positions;
    check(only(s,['cdsPosition','proteinPosition','firstChangedProteinPosition','ptcSpan','referenceEvent','hgvscEvent','referencePtc']) &&
        s.cdsPosition===f.cdsPosition && positive(s.proteinPosition) &&
        (!Object.hasOwn(s,'firstChangedProteinPosition') || positive(s.firstChangedProteinPosition)) &&
        only(s.ptcSpan,['start','end','coordinateSystem']) && s.ptcSpan.start===f.ptcStart && s.ptcSpan.end===f.ptcEnd, 'PVS1_CODING_ESCAPE_POSITIONS_INVALID');
    const events=[s.referenceEvent,s.hgvscEvent];
    for(const [index,e]of events.entries())check(only(e,['coordinateSystem','start0','end0','deletedBaseCount','insertedBaseCount','kind',...(index?['sequenceEquivalent']:[])]) &&
        e.coordinateSystem==='REFERENCE_CDS_0_BASED_HALF_OPEN' && int(e.start0) && e.start0>=0 && int(e.end0) && e.end0>=e.start0 && e.end0<=m.cdsLength &&
        e.deletedBaseCount===e.end0-e.start0 && int(e.insertedBaseCount) && e.insertedBaseCount>=0 && e.insertedBaseCount<=1000000 &&
        (e.start0!==e.end0 || e.insertedBaseCount>0) && e.kind===(e.start0===e.end0?'INSERTION':e.insertedBaseCount===0?'DELETION':
            e.deletedBaseCount===1 && e.insertedBaseCount===1?'SUBSTITUTION':'DELINS') && (!index || e.sequenceEquivalent===true), 'PVS1_CODING_ESCAPE_EVENT_INVALID');
    const normal=events[0],hgvs=events[1],allele=context.variant.evidenceQueryAllele,delta=allele.alternate.length-allele.reference.length;
    let eventPositions,referencePositions;
    if(a.mechanism==='STOP_GAINED') {
        check(a.coordinateSystem==='REFERENCE_CDS' && (f.variantMechanism===undefined || f.variantMechanism==='STOP_GAINED') &&
            (f.coordinateSystem===undefined || f.coordinateSystem==='REFERENCE_CDS') && context.variant.variantType==='SNV' && allele.reference.length===1 && allele.alternate.length===1 &&
            allele.reference!==allele.alternate && s.ptcSpan.coordinateSystem==='REFERENCE_CDS_1_BASED_INCLUSIVE' &&
            f.ptcStart===Math.floor((f.cdsPosition-1)/3)*3+1 && s.proteinPosition===(f.ptcStart-1)/3+1 && f.ptcEnd<=m.cdsLength &&
            normal.start0===f.cdsPosition-1 && normal.end0===f.cdsPosition && normal.deletedBaseCount===1 && normal.insertedBaseCount===1 &&
            same(normal,Object.fromEntries(Object.entries(hgvs).filter(([key])=>key!=='sequenceEquivalent'))) && !Object.hasOwn(s,'referencePtc') && !f.codingEditDerivation,
        'PVS1_CODING_ESCAPE_STOP_INVALID');
        eventPositions=[f.cdsPosition];referencePositions=[f.ptcStart,f.ptcEnd];
    } else {
        check(a.mechanism==='FRAMESHIFT' && f.variantMechanism==='FRAMESHIFT' && a.coordinateSystem==='ALTERED_CDS' && f.coordinateSystem==='ALTERED_CDS' &&
            s.ptcSpan.coordinateSystem==='ALTERED_CDS_1_BASED_INCLUSIVE' && delta%3!==0 &&
            events.every(e=>e.insertedBaseCount-e.deletedBaseCount===delta) && s.cdsPosition===normal.start0+1 && f.ptcEnd<=m.cdsLength+delta &&
            (!Object.hasOwn(s,'firstChangedProteinPosition') || s.firstChangedProteinPosition===p.proteinEffect?.firstAlteredResidue), 'PVS1_CODING_ESCAPE_FRAMESHIFT_INVALID');
        const r=s.referencePtc;
        check(only(r,['coordinateSystem','positions','status']) && r.coordinateSystem==='REFERENCE_CDS_1_BASED' && Array.isArray(r.positions) && r.positions.length===3 &&
            r.positions.every(value=>value===null || positive(value) && value<=m.cdsLength), 'PVS1_CODING_ESCAPE_REFERENCE_PTC_INVALID');
        const expected=[f.ptcStart,f.ptcStart+1,f.ptcEnd].map(position=>{
            const offset=position-1;
            return offset<normal.start0?position:offset<normal.start0+normal.insertedBaseCount?null:offset-normal.insertedBaseCount+normal.deletedBaseCount+1;
        });
        check(same(r.positions,expected) && same(r.positions,[f.ptcReferenceStart,f.ptcReferenceMiddle,f.ptcReferenceEnd]) &&
            r.status===(expected.every(value=>value!==null)?'REFERENCE_BASES':'CONTAINS_INSERTED_BASES') && r.status===f.ptcReferenceMapping, 'PVS1_CODING_ESCAPE_REFERENCE_PTC_INVALID');
        eventPositions=events.flatMap(e=>e.start0===e.end0?[e.start0,e.start0+1]:[e.start0+1,e.end0]);
        referencePositions=r.status==='REFERENCE_BASES'?r.positions:null;
        if(f.codingEditDerivation) {
            const c=f.codingEditDerivation;
            check(c.binding.modelSha256===m.modelSha256 && c.referenceCdsLength===m.cdsLength && c.netCdsLengthChange===delta &&
                c.alteredCdsLength===m.cdsLength+delta && !c.editsTruncated && c.edits.length>=1 && c.edits.length<=2 &&
                c.edits.filter(edit=>edit.status==='APPLIED').length===1 && c.edits.every(edit=>{
                    const index=edit.status==='APPLIED'?0:1;
                    return edit.origin==='CODING_ALLELE' && edit.id.startsWith(index?'hgvs:':'allele:') &&
                        edit.status===(index?'DUPLICATE_SEQUENCE_EDIT':'APPLIED') && edit.referenceEditStart0===events[index].start0 &&
                        edit.referenceEditEnd0===events[index].end0;
                }), 'PVS1_CODING_ESCAPE_EDIT_BINDING_INVALID');
        }
    }
    check(p.proteinEffect?.firstAlteredResidue===(s.firstChangedProteinPosition ?? s.proteinPosition) &&
        s.proteinPosition===p.proteinEffect.firstAlteredResidue, 'PVS1_CODING_ESCAPE_PROTEIN_POSITION_INVALID');
    return {model:m,conditionPositions:{REFERENCE_EVENT_SPAN:eventPositions,REFERENCE_PTC_SPAN:referencePositions,ALTERED_PTC_SPAN:[f.ptcStart,f.ptcEnd]}};
}

function codingPublicProofFields(p) {
    // Opaque identifiers follow the producer's bounded text contract, including
    // Unicode, spaces and slashes. They are never parsed as URLs or file paths;
    // only the explicit source URL field has URL semantics.
    for(const source of p.sources)check(only(source,['sourceId','sha256','version','sourceVersion','url','urlOmitted']) && proofText(source.sourceId) &&
        (!Object.hasOwn(source,'version') || proofText(source.version)) && (!Object.hasOwn(source,'sourceVersion') || proofText(source.sourceVersion)), 'PVS1_CODING_ESCAPE_PUBLIC_SOURCE_INVALID');
    check(only(p,['schemaVersion','methodVersion','scientificApprovalCreated','rescoringPerformed','transcriptModelFacts','ruleChecks','selectedRule','ruleCheck',
        'proteinEffect','bindings','strengthDerivation','policy','scope','review','exonChecks','affectedReferenceExonOrdinals','positionConditions','sources','status','reasonCode']) &&
        only(p.policy,['policyId','policyVersion','schemaVersion','reviewId']) && ['policyId','policyVersion','reviewId'].every(key=>proofText(p.policy[key])) &&
        only(p.review,['status','reviewId','sourceIds']) && proofText(p.review.reviewId) && p.review.sourceIds.every(proofText) &&
        only(p.proteinEffect,['firstAlteredResidue','referenceProteinLength','alteredProteinLength','referenceResiduesAffected','netProteinShortening']) &&
        p.exonChecks.every(exon=>only(exon,['ordinal','biologicalRelevance','populationLof','sourceIds']) && exon.sourceIds.every(proofText)) &&
        only(p.scope,['hgncId','mondoId','moi','assembly','variantType','transcript','selectionOutcome']) &&
        only(p.strengthDerivation,['sourceRuleStrength','parentPolicyCap','adoptedStrength']) &&
        only(p.bindings,['policySha256','escapeScopeSha256','selectedRuleSha256','transcriptModelSha256','resolvedInputSha256','selectionSha256','evaluationSha256','evaluationId','transcriptModelFactsSha256','alleleSha256']), 'PVS1_CODING_ESCAPE_PUBLIC_FIELDS_INVALID');
    const ruleKeys=['ruleId','predicate','start','end','minimumAffectedResidues','metric','comparison','numerator','denominator','strength','sourceIds'];
    for(const rule of [p.selectedRule,...p.ruleChecks.map(row=>row.rule)])check(only(rule,ruleKeys) && proofText(rule.ruleId) && rule.sourceIds.every(proofText), 'PVS1_CODING_ESCAPE_PUBLIC_FIELDS_INVALID');
    const rowKeys=['status','affectedResiduesInRegion','actualNumerator','actualDenominator','leftProduct','rightProduct'];
    check(only(p.ruleCheck,rowKeys) && p.ruleChecks.every(row=>only(row,['rule',...rowKeys])) &&
        only(p.positionConditions,['escape','nmd']) && Object.values(p.positionConditions).every(value=>only(value,['status','conditions']) &&
            (!value.conditions || value.conditions.every(condition=>only(condition,['conditionId','coordinate','minimumInclusive','maximumExclusive','sourceIds','status','checkedPositions']) &&
                proofText(condition.conditionId) && condition.sourceIds.every(proofText)))), 'PVS1_CODING_ESCAPE_PUBLIC_FIELDS_INVALID');
}

// Unknown producer fields are never copied to the display object. Only the
// bounded public proof vocabulary is rendered; private receipts remain raw-only.
function codingView(criterion,context,a,witness,availabilityOnly) {
    const f=criterion.details.pvs1Facts,p=f.escapeAdoptionProof;
    const view={criterionId:criterion.criterionId,result:{status:criterion.status,reasonCode:criterion.reasonCode,appliedStrength:criterion.appliedStrength},
        expectedContextId:context.contextId,factsMethodVersion:f.methodVersion,assessmentStatus:a.status,assessmentReasonCode:a.reasonCode,
        proofStatus:p.status,proofReasonCode:p.reasonCode,proofPointer:a.proofPointer,parentSourceQualifiedResearch:f.sourceQualifiedResearch,
        sourceQualifiedResearch:a.sourceQualifiedResearch,sourceQualification:clone(a.sourceQualification),availabilityOnly,
        clinicalValidationCompleted:false,scientificApprovalCreated:false,rescoringPerformed:false,
        sourceAuthentication:'SERVER_SOURCE_QUALIFICATION_AND_ORIGINAL_REPLAY_REQUIRED'};
    if(witness)Object.assign(view,{mechanism:a.mechanism,coordinateSystem:a.coordinateSystem,positions:clone(a.positions),
        referenceEventPositions:[...witness.conditionPositions.REFERENCE_EVENT_SPAN],transcriptModelFacts:clone(a.transcriptModelFacts),
        scope:clone(p.scope),selectedRule:clone(p.selectedRule),ruleCheck:clone(p.ruleCheck),ruleChecks:clone(p.ruleChecks),
        strengthDerivation:clone(p.strengthDerivation),positionConditions:clone(p.positionConditions),bindings:clone(p.bindings),sources:clone(p.sources)});
    return view;
}

export function inspectPvs1CodingEscapeAssessment(criterion,context,options={}) {
    const f=criterion?.details?.pvs1Facts;
    if(!isPvs1CodingEscapeAssessment(f))return {claimed:false,valid:true,issues:[],view:null};
    try {
        codingBase(criterion,context,options);
        const a=f.codingEscapeAssessment,p=f.escapeAdoptionProof;
        check(only(a,[...CODING_HEADERS,'mechanism','coordinateSystem','positions','transcriptModelFacts']) &&
            a.schemaVersion==='pvs1-coding-escape-assessment-v1' && a.methodVersion==='1.0.0' && a.proofPointer==='#/escapeAdoptionProof' &&
            a.scientificApprovalCreated===false && a.rescoringPerformed===false && ['AVAILABLE','UNAVAILABLE','NOT_ADOPTED'].includes(a.status) && code(a.reasonCode) &&
            typeof a.sourceQualifiedResearch==='boolean' && a.sourceQualifiedResearch===f.sourceQualifiedResearch &&
            only(a.sourceQualification,['status','reasonCode']), 'PVS1_CODING_ESCAPE_HEADER_INVALID');
        const qualification=a.sourceQualification,qualified=a.sourceQualifiedResearch;
        check(qualified?qualification.status==='VERIFIED' && qualification.reasonCode==='PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND' &&
            f.knowledgeReason==='PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND':
            qualification.status==='NOT_AVAILABLE' && qualification.reasonCode==='PVS1_CODING_ESCAPE_SOURCE_QUALIFICATION_NOT_AVAILABLE' ||
            qualification.status==='INVALID' && qualification.reasonCode==='PVS1_CODING_ESCAPE_SOURCE_QUALIFICATION_INVALID', 'PVS1_CODING_ESCAPE_SOURCE_QUALIFICATION_INVALID');
        check(obj(p) && p.schemaVersion==='pvs1-escape-adoption-proof-v1' && p.methodVersion==='1.0.0' &&
            p.scientificApprovalCreated===false && p.rescoringPerformed===false && ['AVAILABLE','UNAVAILABLE','NOT_ADOPTED'].includes(p.status) && code(p.reasonCode), 'PVS1_CODING_ESCAPE_PROOF_INVALID');
        if(a.status!=='AVAILABLE') {
            check(only(a,CODING_HEADERS) && (a.status==='NOT_ADOPTED'?p.status==='NOT_ADOPTED' && criterion.status!=='MET' &&
                a.reasonCode===p.reasonCode && p.reasonCode==='PVS1_ESCAPE_PUBLIC_PROOF_NO_ADOPTED_ESCAPE':
                criterion.status==='MET' && (p.status==='AVAILABLE'?CODING_PROJECTION_FAILURES.has(a.reasonCode):
                    p.status==='UNAVAILABLE' && CODING_PROOF_FAILURES.has(a.reasonCode))), 'PVS1_CODING_ESCAPE_AVAILABILITY_INVALID');
            if(p.status==='NOT_ADOPTED')escapeProofValid(criterion,context,options);
            if(p.status==='UNAVAILABLE')check(only(p,['schemaVersion','methodVersion','scientificApprovalCreated','rescoringPerformed','status','reasonCode']) &&
                a.reasonCode===p.reasonCode && CODING_PROOF_FAILURES.has(p.reasonCode), 'PVS1_CODING_ESCAPE_AVAILABILITY_INVALID');
            if(a.status==='NOT_ADOPTED')return {claimed:true,valid:true,issues:[],view:codingView(criterion,context,a,null,false)};
            if(p.status==='AVAILABLE') {
                // Missing typed facts must not hide a conflicting source/model/
                // context proof. Check all available original proof fields, but
                // never fabricate a missing event witness or position PASS.
                const model=codingModel(p,p.transcriptModelFacts,options.identityHash);
                check(f.ptcEnd<=model.cdsLength+(f.variantMechanism==='FRAMESHIFT'?
                    context.variant.evidenceQueryAllele.alternate.length-context.variant.evidenceQueryAllele.reference.length:0) &&
                    (!f.codingEditDerivation || f.codingEditDerivation.binding.modelSha256===model.modelSha256), 'PVS1_CODING_ESCAPE_MODEL_INVALID');
                escapeProofValid(criterion,context,options,{model,conditionPositions:{REFERENCE_EVENT_SPAN:null,
                    REFERENCE_PTC_SPAN:f.variantMechanism==='FRAMESHIFT'?[f.ptcReferenceStart,f.ptcReferenceMiddle,f.ptcReferenceEnd]:[f.ptcStart,f.ptcEnd],
                    ALTERED_PTC_SPAN:[f.ptcStart,f.ptcEnd]}});
                codingPublicProofFields(p);
            }
            return {claimed:true,valid:false,issues:['PVS1_CODING_ESCAPE_PUBLIC_FACTS_UNAVAILABLE'],view:codingView(criterion,context,a,null,true)};
        }
        check(a.reasonCode==='PVS1_CODING_ESCAPE_PUBLIC_FACTS_VERIFIED' && criterion.status==='MET' && p.status==='AVAILABLE', 'PVS1_CODING_ESCAPE_STATUS_CONTRADICTION');
        const witness=codingWitness(a,f,context,options);
        escapeProofValid(criterion,context,options,witness);
        codingPublicProofFields(p);
        return {claimed:true,valid:qualified,issues:qualified?[]:['PVS1_CODING_ESCAPE_SOURCE_QUALIFICATION_UNAVAILABLE'],view:codingView(criterion,context,a,witness,!qualified)};
    }catch(error) {
        return {claimed:true,valid:false,issues:[error?.message?.startsWith('PVS1_')?error.message:'PVS1_CODING_ESCAPE_MALFORMED_VALUE'],view:null};
    }
}

export function pvs1CodingEscapeDetails(inspected) {
    const v=inspected?.view;
    if(!v)return [];
    const out=[['编码逃逸公开资料', `${v.assessmentStatus}；${v.assessmentReasonCode}；事实方法 ${v.factsMethodVersion}`],
        ['编码逃逸来源资格', `${v.sourceQualification.status}；父来源资格 ${v.parentSourceQualifiedResearch}；编码来源资格 ${v.sourceQualifiedResearch}；${v.sourceQualification.reasonCode}`],
        ['编码逃逸采用证明', `${v.proofStatus}；${v.proofReasonCode}；${v.proofPointer}（相对于 pvs1Facts）`]];
    if(v.positions)out.push(['编码事件与位置', `${v.mechanism}；${v.coordinateSystem}；${JSON.stringify(v.positions)}`],
        ['规则检查所用事件位置（保留顺序及重复）',JSON.stringify(v.referenceEventPositions)],['转录本模型事实',JSON.stringify(v.transcriptModelFacts)],
        ['采用条款与强度上限',`${JSON.stringify(v.selectedRule)}；${JSON.stringify(v.strengthDerivation)}`],
        ['原声明的位置条件',JSON.stringify(v.positionConditions)],['模型、背景与原始结果绑定',`${JSON.stringify(v.scope)}；${JSON.stringify(v.bindings)}`],
        ['编码逃逸公开来源',JSON.stringify(v.sources)]);
    out.push(['编码逃逸核验边界','只读核对公开位置、条款、来源标识与强度上限；AVAILABLE不等于来源资格。未重新判定、未新授科学或临床资格，未重放未公开原件。']);
    return out;
}

function ptcValid(f, s, geometry, criterion, c, context, options) {
    check(positive(f.ptcStart) && f.ptcEnd === f.ptcStart + 2 && (f.ptcStart-1)%3 === 0 && f.ptcEnd <= geometry.topology.cdsLength &&
        ['PREDICTED_NMD','NMD_NOT_PREDICTED'].includes(f.nmdPrediction) &&
        f.sequenceDerivationStatus === 'SOURCE_BOUND_SPLICE_EDIT_AND_TRANSCRIPT_SEQUENCE_AGREE', 'PVS1_SPLICE_PTC_INVALID');
    const bases = [f.ptcReferenceStart,f.ptcReferenceMiddle,f.ptcReferenceEnd];
    check(bases.every(n => int(n) && n >= 0 && n <= s.referenceTopology.cdsLength), 'PVS1_PTC_REFERENCE_BASE_INVALID');
    const mapped = bases.filter(n => n > 0).length;
    check(f.ptcReferenceMapping === (mapped === 3 ? 'REFERENCE_BASES' : mapped === 0 ? 'NO_REFERENCE_BASES' : 'MIXED_REFERENCE_AND_INSERTED_BASES') &&
        (mapped === 3 ? !f.ptcReferenceMappingReason : f.ptcReferenceMappingReason === 'INSERTED_OR_REPLACEMENT_BASES_HAVE_NO_REFERENCE_IDENTITY'), 'PVS1_PTC_REFERENCE_MAPPING_INVALID');
    const expected=referenceMapping(c,context,[f.ptcStart,f.ptcStart+1,f.ptcEnd]);
    check(!expected || same(expected,bases), 'PVS1_PTC_REFERENCE_EDIT_MAP_MISMATCH');
    if (criterion.status === 'NOT_MET') check(criterion.reasonCode.startsWith('PVS1_ESCAPE_') && f.nmdPrediction === 'NMD_NOT_PREDICTED' &&
        f.escapeAssessment?.schemaVersion === 'pvs1-nmd-escape-assessment-v1', 'PVS1_ESCAPE_NEGATIVE_BRANCH_INVALID');
    const exons = rows(s,'ptcExons');
    check(!exons.truncated && exons.total >= 1 && exons.total <= 3 && typeof s.ptcCrossesJunction === 'boolean' &&
        s.ptcCrossesJunction === (exons.total > 1) && exons.list.every((e,i) => positive(e.referenceOrdinal) && positive(e.alteredOrdinal) &&
            (i===0 || e.alteredOrdinal === exons.list[i-1].alteredOrdinal+1)) &&
        f.ptcAlteredExonOrdinal === exons.list.at(-1).alteredOrdinal, 'PVS1_PTC_EXON_IDENTITY_INVALID');
    if (!geometry.retained.truncated) {
        const carriers = geometry.retained.list.filter(e => e.alteredCdsStart <= f.ptcEnd && e.alteredCdsEnd >= f.ptcStart);
        check(same(exons.list,carriers.map(e => ({ referenceOrdinal:e.referenceOrdinal,alteredOrdinal:e.alteredOrdinal }))), 'PVS1_PTC_EXON_GEOMETRY_MISMATCH');
    }
    const junction = geometry.topology.lastJunctionCdsPosition;
    check(junction === null ? f.distanceToLastJunction == null : int(f.distanceToLastJunction) && f.distanceToLastJunction === junction-f.ptcEnd, 'PVS1_PTC_JUNCTION_DISTANCE_INVALID');
    if (criterion.status === 'MET' && f.nmdPrediction === 'PREDICTED_NMD') check(junction !== null && f.distanceToLastJunction >= 51 &&
        s.adoptedStrength === lower('VERY_STRONG',s.maximumStrength,f.policyStrengthCap), 'PVS1_NMD_STRENGTH_OR_DISTANCE_INVALID');
    if (criterion.status === 'MET' && f.nmdPrediction === 'NMD_NOT_PREDICTED') {
        check(f.escapeAssessment?.schemaVersion === 'pvs1-nmd-escape-assessment-v1' && f.escapeAssessment.status === 'MET', 'PVS1_ESCAPE_ASSESSMENT_REQUIRED');
        // The bounded contract does not expose escapeAssessment.selectedRule.
        // Its actual selected strength cannot be recovered from the NMD label.
        escapeProofValid(criterion,context,options);
    }
}

function validate(criterion, context, options) {
    const f = criterion?.details?.pvs1Facts;
    check(criterion?.criterionId === 'ACMG_AMP_2015:PVS1' && isPvs1SpliceSummary(f) && f.schemaVersion === 'pvs1-selected-facts-v1' &&
        f.scope === 'REVIEWED_SELECTED_TRANSCRIPT' && f.clinicalValidationCompleted === false &&
        f.doubleCountingConstraint === 'NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE', 'PVS1_SPLICE_SUMMARY_INVALID');
    const legacyIncrement = f.methodVersion === '1.1.0' && !Object.hasOwn(f,'method') && !!f.codingEditComposition && !!f.spliceAssessment &&
        !f.inFrameAssessment && !f.codingEditDerivation && f.codingEditComposition.explicitCompositionReviewed === false;
    check((version(f.methodVersion,7) && METHODS.includes(f.method)) || legacyIncrement, 'PVS1_SPLICE_METHOD_UNSUPPORTED');
    check(['MET','NOT_MET','NOT_EVALUATED'].includes(criterion.status) && code(criterion.reasonCode) &&
        (criterion.status === 'MET' ? strengths.includes(criterion.appliedStrength) : criterion.appliedStrength === 'NONE') &&
        typeof f.sourceQualifiedResearch === 'boolean' && [...strengths,'UNAVAILABLE'].includes(f.policyStrengthCap), 'PVS1_SPLICE_STATUS_INVALID');
    if (criterion.status !== 'NOT_EVALUATED') check(f.knowledgeStatus === 'READY' && text(f.knowledgeReason) && strengths.includes(f.policyStrengthCap) &&
        (legacyIncrement || f.sourceQualifiedResearch === true) && f.materialSupply?.status === 'READY' &&
        f.materialSupply.bindingMethod === 'EXACT_FULL_TRANSCRIPT_EQUIVALENCE' && positive(f.materialSupply.verifiedExonCount) &&
        f.materialSupply.policyModelUnchanged === true && text(f.materialSupply.reasonCode), 'PVS1_SPLICE_PARENT_QUALIFICATION_REQUIRED');
    if (Object.hasOwn(f,'escapeAdoptionProof') && (criterion.status!=='MET' || f.nmdPrediction!=='NMD_NOT_PREDICTED')) escapeProofValid(criterion,context,options);
    const identity = context ? contextView(context,options.identityHash) : null;
    if (criterion.status !== 'NOT_EVALUATED') check(identity, 'PVS1_SPLICE_CONTEXT_BINDING_REQUIRED');
    const c = f.codingEditComposition || f.codingEditDerivation;
    if (c) { check(identity, 'PVS1_SPLICE_CONTEXT_BINDING_REQUIRED'); editsValid(c,f,context,identity,!!f.codingEditComposition); }
    if (f.method === 'SOURCE_BOUND_CODING_EDITS') {
        check(f.codingEditDerivation && !f.codingEditComposition && !f.spliceAssessment && !f.inFrameAssessment, 'PVS1_CODING_METHOD_CONTRADICTION');
        if (criterion.status === 'MET') check(f.variantMechanism === 'FRAMESHIFT' && c.netCdsLengthChange % 3 !== 0 &&
            positive(f.ptcStart) && f.ptcEnd === f.ptcStart+2 && f.ptcEnd <= c.alteredCdsLength &&
            (f.ptcStart-1)%3 === 0 && /^pvs1-truncating-nmd-policy-v[1-4]$/.test(f.policySchema || '') &&
            f.sequenceDerivationStatus === 'GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE' &&
            ['PREDICTED_NMD','NMD_NOT_PREDICTED'].includes(f.nmdPrediction) && strengths.indexOf(criterion.appliedStrength) <= strengths.indexOf(f.policyStrengthCap), 'PVS1_CODING_TRUNCATION_INVALID');
        if (criterion.status === 'MET' && f.nmdPrediction === 'PREDICTED_NMD') check(int(f.lastJunctionCdsPosition) &&
            f.distanceToLastJunction === f.lastJunctionCdsPosition-f.ptcEnd && f.distanceToLastJunction >= 51 &&
            criterion.appliedStrength === f.policyStrengthCap, 'PVS1_CODING_NMD_STRENGTH_OR_DISTANCE_INVALID');
        if (criterion.status === 'MET' && f.nmdPrediction === 'NMD_NOT_PREDICTED') escapeProofValid(criterion,context,options);
        if (criterion.status === 'MET') {
            const mapping=referenceMapping(c,context,[f.ptcStart,f.ptcStart+1,f.ptcEnd]);
            const bases=[f.ptcReferenceStart,f.ptcReferenceMiddle,f.ptcReferenceEnd];
            check(mapping && mapping.every((base,i) => base === 0 ? bases[i] == null : bases[i] === base) &&
                f.ptcReferenceMapping === (mapping.every(base => base > 0) ? 'REFERENCE_BASES' : 'CONTAINS_INSERTED_BASES'), 'PVS1_CODING_PTC_REFERENCE_MAPPING_INVALID');
        }
        if (criterion.status === 'NOT_MET') check(NEGATIVE.has(criterion.reasonCode) && criterion.reasonCode.startsWith('PVS1_ESCAPE_') &&
            f.nmdPrediction === 'NMD_NOT_PREDICTED' && f.escapeAssessment?.schemaVersion === 'pvs1-nmd-escape-assessment-v1', 'PVS1_CODING_NEGATIVE_INVALID');
        return;
    }
    const s = f.spliceAssessment;
    check(obj(s) && s.schemaVersion === 'pvs1-splice-assessment-v1' && version(s.methodVersion,5) &&
        ['OBSERVED_RNA','PREDICTED_CANONICAL'].includes(s.evidenceKind) && text(s.outcomeId) && strengths.includes(s.maximumStrength) &&
        s.interpretation === (s.evidenceKind === 'OBSERVED_RNA' ? 'SOURCE_REVIEWED_RNA_OUTCOME' : 'PREDICTED_OUTCOME_NOT_OBSERVED_RNA') &&
        s.doubleCountingConstraint === 'DO_NOT_COMBINE_WITH_PP3_OR_PS3_FOR_THE_SAME_SPLICE_EFFECT' &&
        f.policySchema === 'pvs1-truncating-nmd-policy-v4' && !f.codingEditDerivation, 'PVS1_SPLICE_ASSESSMENT_INVALID');
    if (criterion.status === 'NOT_EVALUATED') {
        check(s.status === 'NOT_EVALUATED' && s.reasonCode === criterion.reasonCode && !s.adoptedStrength && !NEGATIVE.has(s.reasonCode), 'PVS1_SPLICE_UNEVALUATED_CONTRADICTION');
        return;
    }
    check(c && f.variantMechanism === 'SPLICE_CDS_DELETION' && f.coordinateSystem === 'ALTERED_CDS' && positive(f.cdsPosition) &&
        positive(f.exonOrdinal) && f.exonOrdinal <= f.exonTotal && s.compositionStatus === c.compositionStatus &&
        ['PRESERVED','ALTERED'].includes(s.rnaReadingFrame), 'PVS1_SPLICE_SEQUENCE_BINDING_INVALID');
    const sources = rows(s,'sourceRecords');
    check(sources.total > 0 && sources.list.every(source => text(source.sourceId) && /^https:\/\/[^\s]+$/.test(source.url || '') && sha(source.sha256)), 'PVS1_SPLICE_SOURCE_IDENTITY_INVALID');
    const geometry = topologyValid(f,s,c);
    check(f.materialSupply.verifiedExonCount === f.referenceExonTotal, 'PVS1_FULL_REFERENCE_EXON_BINDING_INVALID');
    if (criterion.status === 'MET') check(s.status === 'MET' && s.adoptedStrength === criterion.appliedStrength &&
        !s.reasonCode && strengths.indexOf(criterion.appliedStrength) <= strengths.indexOf(f.policyStrengthCap) &&
        strengths.indexOf(criterion.appliedStrength) <= strengths.indexOf(s.maximumStrength), 'PVS1_SPLICE_ADOPTED_STRENGTH_INVALID');
    else check(NEGATIVE.has(criterion.reasonCode) && s.status === 'NOT_MET' && s.reasonCode === criterion.reasonCode && !s.adoptedStrength, 'PVS1_SPLICE_NEGATIVE_REASON_INVALID');
    if (f.inFrameAssessment) inFrameValid(f.inFrameAssessment,s,f,criterion,c);
    else ptcValid(f,s,geometry,criterion,c,context,options);
    if (geometry.topology.kind === 'LAST_JUNCTION_IN_3_PRIME_UTR' && criterion.status === 'MET') check(geometry.topology.utrApplicabilityBound, 'PVS1_UTR_APPLICABILITY_REQUIRED');
}

export function inspectPvs1SpliceCriterion(criterion, context, options = {}) {
    try { validate(criterion,context,options); return { valid:true,issues:[] }; }
    catch (error) { return { valid:false,issues:[typeof error?.message === 'string' && error.message.startsWith('PVS1_') ? error.message : 'PVS1_SPLICE_MALFORMED_VALUE'] }; }
}
export const validatePvs1SpliceCriterion = (criterion,context,options) => inspectPvs1SpliceCriterion(criterion,context,options).valid;

const shown = v => v == null ? '未提供' : typeof v === 'string' ? v : JSON.stringify(v);
const referenceBase = (v, inserted) => v === 0 || v == null && inserted ? '无参考身份（插入或替换新碱基）' : shown(v);
export function pvs1SpliceDetails(criterion) {
    const f = criterion?.details?.pvs1Facts;
    if (!isPvs1SpliceSummary(f)) return [];
    const s=f.spliceAssessment, a=f.inFrameAssessment, c=f.codingEditComposition || f.codingEditDerivation;
    const out = [['PVS1序列方法', `${shown(f.method)} @ ${shown(f.methodVersion)}；编码编辑事实不是额外独立ACMG证据`],
        ['保存的结论与上限', `${shown(criterion.status)} / ${shown(criterion.appliedStrength)}；政策上限 ${shown(f.policyStrengthCap)}；材料上限 ${shown(s?.maximumStrength)}；模块采用 ${shown(s?.adoptedStrength)}；${shown(criterion.reasonCode)}`],
        ['父资格与模型供给', `${shown(f.knowledgeStatus)}；${shown(f.knowledgeReason)}；来源资格 ${shown(f.sourceQualifiedResearch)}；${shown(f.materialSupply)}`]];
    if (s) out.push(['RNA证据性质', `${shown(s.evidenceKind)}；${shown(s.interpretation)}；${shown(s.outcomeId)}`],
        ['原始转录本拓扑', shown(s.referenceTopology)], ['改变后转录本拓扑', shown(s.alteredTopology)],
        ['参考与改变后外显子', shown(s.retainedExons)], ['改变后连接点', shown(s.alteredJunctions)],
        ['原始RNA阅读框', `${shown(s.rnaReadingFrame)}；组合后CDS另按实际编辑推导`],
        ['剪接来源身份', shown(s.sourceRecords)]);
    if (f.ptcStart !== undefined) out.push(['改变后PTC与连接点距离', `${shown(f.ptcStart)}–${shown(f.ptcEnd)}；距离 ${shown(f.distanceToLastJunction)}；${shown(f.nmdPrediction)}`],
        ['PTC三个碱基的参考对应', [f.ptcReferenceStart,f.ptcReferenceMiddle,f.ptcReferenceEnd].map(v => referenceBase(v,f.ptcReferenceMapping === 'CONTAINS_INSERTED_BASES')).join(' / ')],
        ['参考映射说明', `${shown(f.ptcReferenceMapping)}；${shown(f.ptcReferenceMappingReason)}`], ['PTC外显子与跨连接点', `${shown(s?.ptcExons)}；${shown(s?.ptcCrossesJunction)}`]);
    if (a) out.push(['在框局部蛋白改变', `${shown(a.deletedCdsBases)} nt；净减少 ${shown(a.netCodonDeletion)} aa；${shown(a.sequenceDerivationStatus)}`],
        ['触及与完整移除残基', `触及 ${shown(a.firstTouchedReferenceResidue)}–${shown(a.lastTouchedReferenceResidue)}；完整移除 ${shown(a.fullyRemovedReferenceResidues)}，${shown(a.firstFullyRemovedReferenceResidue)}–${shown(a.lastFullyRemovedReferenceResidue)}`],
        ['融合密码子', shown(a.junctionEffect)], ['在框规则与审核', `${shown(a.selectedRule)}；${shown(a.ruleChecks)}；审核 ${shown(a.policyReviewId)}`],
        ['在框模块结论', `${shown(a.status)} / ${shown(a.strength)}；${shown(a.reasonCode)}；单条规则通过不替代父条件及最终采用上限`]);
    if (f.escapeAdoptionProof) {
        const p=f.escapeAdoptionProof;
        out.push(['逃逸公开采用证明', `${shown(p.status)}；${shown(p.reasonCode)}；证明不新授科学资格，不重新计算或修改保存结论。`],
            ['逃逸条款与逐条核验', `${shown(p.selectedRule)}；${shown(p.ruleChecks)}`], ['逃逸蛋白改变与采用上限', `${shown(p.proteinEffect)}；${shown(p.strengthDerivation)}`],
            ['逃逸上下文与原件绑定', `${shown(p.scope)}；${shown(p.bindings)}`], ['逃逸审核与来源', `${shown(p.review)}；${shown(p.localReviews)}；${shown(p.sources)}`],
            ['逃逸外显子与位置条件', `${shown(p.affectedReferenceExonOrdinals)}；${shown(p.exonChecks)}；${shown(p.positionConditions)}`]);
    }
    if (f.nmdPrediction === 'NMD_NOT_PREDICTED' && criterion.status === 'MET') out.push([f.escapeAdoptionProof?.status==='AVAILABLE'?'逃逸采用证明核验范围':'逃逸强度展示核验依赖', f.escapeAdoptionProof?.status==='AVAILABLE'
        ? '已保留并核验公开采用证明的条款、数值、背景、原件标识及强度上限；原规则SHA包含未公开basis，作为后台已核原规则的来源标识保留，页面不以局部条款重算该原始摘要，不声称完成私有原件重放。'
        : f.escapeAdoptionProof?.status==='UNAVAILABLE' ? '后台原逃逸结论保留，但公开采用证明未通过投影核查；这是证明可用性问题，不是未满足或病例资料不足。'
        : '保存的强度原值保留；当前公开摘要没有可用的逃逸所选规则及其源绑定强度，不能独立重算最终采用强度；这不是原始资料缺失或证据未满足。']);
    if (c) out.push(['同等位组合与范围', `${shown(c.compositionStatus)}；${shown(c.rnaSequenceScope)}；明确组合审核 ${shown(c.explicitCompositionReviewed)}`],
        ['参考与改变后CDS', `${shown(c.referenceCdsLength)} → ${shown(c.alteredCdsLength)}；净变化 ${shown(c.netCdsLengthChange)} nt`],
        ['模型／上下文／CDS绑定', shown(c.binding)], ['编码与剪接编辑审计', shown(c.edits)], ['同等位审核声明', shown(c.sourceDeclaration)],
        ['完整等位CDS见证边界', `见证核验 ${shown(c.completeAllelicCdsWitnessVerified)}；完整RNA/EJC证明 ${shown(c.fullRnaOrEjcProofGranted)}；CDS见证不替代完整RNA或连接复合体证明`]);
    for (const parent of [s,a,c].filter(obj)) for (const name of ['retainedExons','alteredJunctions','ptcExons','sourceRecords','ruleChecks','edits'])
        if (Object.hasOwn(parent,`${name}Total`)) out.push([`${name}公开范围`, `${shown(parent[`${name}Total`])} 项；公开 ${Array.isArray(parent[name]) ? parent[name].length : 0} 项；截断 ${shown(parent[`${name}Truncated`])}。有界摘要不等于科学资料缺失，完整审计保留在私有结果。`]);
    out.push(['采用与独立核验边界', '本页核保存的公开结构、身份对应及数值一致性；原始材料、审核、政策和执行身份仍由外层保存链核定。未知、冲突和软件未支持不是阴性；NOT_MET不产生良性证据；不自动与PP3/PS3或其他效应证据叠加。']);
    return out;
}
