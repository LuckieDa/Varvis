"""Qualify unchanged, pinned splice records. Never create assay or scientific reviews."""
import copy
import re
from assemble_materials import content_hash, sha, parse

STRENGTHS = {'VERY_STRONG', 'STRONG', 'MODERATE', 'SUPPORTING'}


def fields(value, allowed):
    return isinstance(value, dict) and set(value) <= set(allowed.split())


def text(value):
    return isinstance(value, str) and bool(value.strip())


def positive(value):
    return type(value) is int and 0 < value <= 2147483647


def digest(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def sources_valid(sources):
    return isinstance(sources, list) and bool(sources) and len(sources) <= 256 and all(
        isinstance(s, dict) and text(s.get('sourceId')) and text(s.get('version'))
        and isinstance(s.get('url'), str) and s['url'].startswith('https://') and digest(s.get('sha256')) for s in sources
    ) and len({s['sourceId'] for s in sources}) == len(sources)


def sourced(value, sources):
    ids = value.get('sourceIds') if isinstance(value, dict) else None
    return text(value.get('basis')) and isinstance(ids, list) and bool(ids) and all(text(i) for i in ids) \
        and len(set(ids)) == len(ids) and set(ids) <= {s['sourceId'] for s in sources}


def proofs_valid(sources, checks):
    return sources_valid(sources) and isinstance(checks, list) and len(sources) == len(checks) and all(
        fields(c, 'source status bytes') and c.get('source') == s and c.get('status') == 'RAW_SHA_MATCH'
        and type(c.get('bytes')) is int and 1 <= c['bytes'] <= 4_000_000 for s, c in zip(sources, checks))


def initiation_scope_issue(policy):
    scope = policy.get('spliceScope', {}).get('initiationScope')
    if 'initiationScope' not in policy.get('spliceScope', {}):
        return None
    sources = policy.get('scientificSources', [])
    if not fields(scope, 'status method methodVersion reviewId basis sourceIds maximumStrength assessedTranscripts sourceBindings') \
            or scope.get('status') != 'REVIEWED_FOR_FULL_RNA_INITIATION' or scope.get('method') != 'CLINGEN_SVI_2018_START_LOSS' \
            or scope.get('methodVersion') != '1.0.0' or not text(scope.get('reviewId')) or not sourced(scope, sources) \
            or not isinstance(scope.get('maximumStrength'), str) or scope['maximumStrength'] not in {'MODERATE', 'SUPPORTING'}:
        return 'RNA_INITIATION_POLICY_NOT_REVIEWED'
    transcripts = scope.get('assessedTranscripts')
    if not isinstance(transcripts, list) or not 1 <= len(transcripts) <= 1000 \
            or not all(isinstance(t, str) and re.fullmatch(r'(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*', t) for t in transcripts) \
            or len(set(transcripts)) != len(transcripts):
        return 'RNA_INITIATION_TRANSCRIPT_SCOPE_INVALID'
    bindings = scope.get('sourceBindings')
    if not isinstance(bindings, list) or not 1 <= len(bindings) <= 16:
        return 'RNA_INITIATION_SOURCE_BINDINGS_INVALID'
    declared = {s['sourceId']: s for s in sources}
    seen = set()
    roles = {'PRIMARY_EVIDENCE', 'TRANSCRIPT_FUNCTION_REVIEW', 'RESTART_REVIEW', 'UPSTREAM_REVIEW'}
    for binding in bindings:
        if not fields(binding, 'sourceId role sha256 url version reviewId mediaType') or not text(binding.get('sourceId')) \
                or binding['sourceId'] in seen or not isinstance(binding.get('role'), str) or binding['role'] not in roles \
                or not digest(binding.get('sha256')) or not text(binding.get('reviewId')) or not text(binding.get('version')) \
                or not isinstance(binding.get('url'), str) or not binding['url'].startswith('https://') \
                or not isinstance(binding.get('mediaType'), str):
            return 'RNA_INITIATION_SOURCE_BINDINGS_INVALID'
        allowed = {'text/plain', 'text/html', 'application/xml', 'application/json'} if binding['role'] == 'PRIMARY_EVIDENCE' else {'application/json'}
        original = declared.get(binding['sourceId'])
        if binding['mediaType'] not in allowed or original is None or any(binding.get(k) != original.get(k) for k in ['sha256', 'url', 'version']):
            return 'RNA_INITIATION_SOURCE_BINDINGS_INVALID'
        seen.add(binding['sourceId'])
    return None


def scope_issue(policy, model):
    sources = policy.get('scientificSources')
    scope = policy.get('spliceScope')
    if not sources_valid(sources) or not fields(scope, 'status reviewId basis sourceIds maximumStrength evidenceKinds eventTypes inFrameScope initiationScope') \
            or scope.get('status') != 'REVIEWED_FOR_PVS1_SPLICE' or not text(scope.get('reviewId')) or not sourced(scope, sources) \
            or scope.get('maximumStrength') not in STRENGTHS:
        return 'SPLICE_SCOPE_NOT_REVIEWED'
    for name, allowed in [('evidenceKinds', {'OBSERVED_RNA', 'PREDICTED_CANONICAL'}),
                          ('eventTypes', {'EXON_SKIPPING', 'EXON_SHORTENING'})]:
        values = scope.get(name)
        if not isinstance(values, list) or not values or not all(isinstance(v, str) for v in values) \
                or len(set(values)) != len(values) or not set(values) <= allowed:
            return 'SPLICE_SCOPE_NOT_REVIEWED'
    if 'codingEditScope' in policy:
        coding = policy['codingEditScope']
        if not fields(coding, 'status method methodVersion maximumAlleleLengthIncludingAnchor basis sourceIds') \
                or coding.get('status') != 'REVIEWED_FOR_COMPLEX_SMALL_ALLELES' or coding.get('method') != 'CODING_TRANSCRIPT_EDITS' \
                or coding.get('methodVersion') != '1.0.0' or not positive(coding.get('maximumAlleleLengthIncludingAnchor')) \
                or coding['maximumAlleleLengthIncludingAnchor'] > 50 or not sourced(coding, sources) \
                or policy.get('scope', {}).get('variantType') != 'NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT':
            return 'SPLICE_CODING_EDIT_SCOPE_INVALID'
    initiation_issue = initiation_scope_issue(policy)
    if initiation_issue is not None:
        return initiation_issue
    if 'inFrameScope' not in scope:
        return None
    frame = scope['inFrameScope']
    if not fields(frame, 'status reviewId basis sourceIds exonAssessments rules') \
            or frame.get('status') != 'REVIEWED_FOR_PVS1_SPLICE_INFRAME' or not text(frame.get('reviewId')) or not sourced(frame, sources):
        return 'SPLICE_INFRAME_SCOPE_NOT_REVIEWED'
    exons, rules = frame.get('exonAssessments'), frame.get('rules')
    if not isinstance(exons, list) or len(exons) > len(model.get('exons', [])) or not isinstance(rules, list) or not 1 <= len(rules) <= 32:
        return 'SPLICE_INFRAME_POLICY_INVALID'
    seen = set()
    for exon in exons:
        if not fields(exon, 'ordinal biologicalRelevance populationLof basis sourceIds') or not positive(exon.get('ordinal')) \
                or exon['ordinal'] > len(model.get('exons', [])) or exon['ordinal'] in seen or not sourced(exon, sources) \
                or exon.get('biologicalRelevance') not in {'PRESENT', 'ABSENT', 'UNKNOWN'} or exon.get('populationLof') not in {'NOT_FREQUENT', 'FREQUENT', 'UNKNOWN'}:
            return 'SPLICE_INFRAME_POLICY_INVALID'
        seen.add(exon['ordinal'])
    seen = set()
    for rule in rules:
        if not isinstance(rule, dict) or not text(rule.get('ruleId')) or rule['ruleId'] in seen or not sourced(rule, sources) or rule.get('strength') not in STRENGTHS:
            return 'SPLICE_INFRAME_POLICY_INVALID'
        seen.add(rule['ruleId'])
        if rule.get('predicate') == 'CRITICAL_REGION':
            if not fields(rule, 'ruleId predicate start end minimumAffectedResidues strength basis sourceIds') \
                    or not all(positive(rule.get(k)) for k in ['start', 'end', 'minimumAffectedResidues']) \
                    or not rule['start'] <= rule['end'] <= model.get('proteinLength', 0) or rule['minimumAffectedResidues'] > rule['end'] - rule['start'] + 1:
                return 'SPLICE_INFRAME_POLICY_INVALID'
        elif rule.get('predicate') == 'DELETED_PROTEIN_FRACTION':
            if not fields(rule, 'ruleId predicate comparison numerator denominator strength basis sourceIds') \
                    or rule.get('comparison') not in {'GT', 'LT'} or not positive(rule.get('numerator')) or not positive(rule.get('denominator')) \
                    or rule['numerator'] >= rule['denominator']:
                return 'SPLICE_INFRAME_POLICY_INVALID'
        else:
            return 'SPLICE_INFRAME_POLICY_INVALID'
    return None


def residual_transcript_compatible(review, predicted):
    """Prediction may lack RNA measurements, but cannot discard an explicit conflict."""
    if not isinstance(review, dict):
        return False
    if predicted and 'residualNormalTranscript' not in review:
        return True
    residual = review.get('residualNormalTranscript')
    if not isinstance(residual, str):
        return False
    if not predicted:
        return residual == 'NOT_DETECTED_IN_ASSESSED_SCOPE'
    return residual in {'NOT_MEASURED', 'UNKNOWN', 'NOT_DETECTED_IN_ASSESSED_SCOPE'}

def outcome_issue(policy, model, outcome, verified_sources=None):
    if not fields(outcome, 'schemaVersion outcomeId evidenceKind allele hgncId mondoId moi transcript transcriptModelSha256 review event referenceWindow assessment sourceRecords inFrameReview codingEditComposition') \
            or outcome.get('schemaVersion') != 'pvs1-splice-outcome-v1' or not text(outcome.get('outcomeId')):
        return 'SPLICE_MATERIAL_INVALID'
    scope, allele = policy['scope'], outcome.get('allele', {})
    if not fields(allele, 'assembly contig position reference alternate') or any(outcome.get(k) != scope.get(k) for k in ['hgncId', 'mondoId', 'moi']) \
            or allele.get('assembly') != scope.get('assembly') or allele.get('contig') != model.get('contig') \
            or outcome.get('transcript') != model.get('transcript') or outcome.get('transcript') not in [t['accession']+'.'+str(t['version']) for t in scope['transcripts']] \
            or outcome.get('transcriptModelSha256') != policy['transcriptModelBinding']['canonicalSha256'] or not positive(allele.get('position')) \
            or not all(isinstance(allele.get(k), str) and re.fullmatch('[ACGT]{1,50}', allele[k]) for k in ['reference', 'alternate']) or allele['reference'] == allele['alternate']:
        return 'SPLICE_CONTEXT_BINDING_MISMATCH'
    sources = outcome.get('sourceRecords')
    if not sources_valid(sources):
        return 'SPLICE_SOURCE_BYTES_REQUIRED'
    declared = {s['sourceId']: s for s in policy['scientificSources']}
    if any(s['sourceId'] in declared and s != declared[s['sourceId']] for s in sources):
        return 'SPLICE_SOURCE_IDENTITY_CONFLICT'
    review, assay = outcome.get('review'), outcome.get('assessment')
    predicted = outcome.get('evidenceKind') == 'PREDICTED_CANONICAL'
    if outcome.get('evidenceKind') not in policy['spliceScope']['evidenceKinds'] \
            or not fields(review, 'status reviewId basis sourceIds maximumStrength functionalRescue outcomeMultiplicity residualNormalTranscript') \
            or review.get('status') != 'REVIEWED_FOR_PVS1_SPLICE' or not text(review.get('reviewId')) or not sourced(review, sources) \
            or review.get('maximumStrength') not in STRENGTHS:
        return 'SPLICE_OUTCOME_REVIEW_REQUIRED'
    if not fields(assay, 'status basis sourceIds tissueRelevance alleleAttribution assayQuality nearbyRescueAssessment alternativeOutcomesAssessment') \
            or not sourced(assay, sources) or assay.get('status') != ('QUALIFIED_CANONICAL_PREDICTION' if predicted else 'QUALIFIED_RNA_ASSAY') \
            or any(assay.get(k) != 'ASSESSED' for k in (['nearbyRescueAssessment', 'alternativeOutcomesAssessment'] if predicted else ['tissueRelevance', 'alleleAttribution', 'assayQuality', 'alternativeOutcomesAssessment'])):
        return 'SPLICE_ASSAY_QUALIFICATION_REQUIRED'
    event = outcome.get('event')
    if not fields(event, 'type exonOrdinal deleteStartCds deleteEndCds') or event.get('type') not in policy['spliceScope']['eventTypes'] \
            or not all(positive(event.get(k)) for k in ['exonOrdinal', 'deleteStartCds', 'deleteEndCds']) \
            or event['exonOrdinal'] > len(model.get('exons', [])) or not event['deleteStartCds'] <= event['deleteEndCds'] <= len(model.get('cdsSequence', '')):
        return 'SPLICE_EVENT_INVALID'
    exon = model['exons'][event['exonOrdinal']-1]
    start, end = max(1, exon.get('cdsStart', 0)), min(len(model['cdsSequence']), exon.get('cdsEnd', 0))
    whole = (event['deleteStartCds'], event['deleteEndCds']) == (start, end)
    if event['deleteStartCds'] < start or event['deleteEndCds'] > end or (event['type'] == 'EXON_SKIPPING') != whole \
            or not whole and event['deleteStartCds'] != exon.get('cdsStart') and event['deleteEndCds'] != exon.get('cdsEnd'):
        return 'SPLICE_EVENT_INVALID'
    window = outcome.get('referenceWindow')
    if not fields(window, 'start sequence') or not positive(window.get('start')) or not isinstance(window.get('sequence'), str) or not re.fullmatch('[ACGT]{1,100000}', window['sequence']):
        return 'SPLICE_REFERENCE_WITNESS_INVALID'
    offset = allele['position'] - window['start']
    if offset < 0 or window['sequence'][offset:offset+len(allele['reference'])] != allele['reference']:
        return 'SPLICE_REFERENCE_MISMATCH'
    if 'inFrameReview' in outcome:
        frame = outcome['inFrameReview']
        if not fields(frame, 'status reviewId basis sourceIds criticalRegionAssessment compositeEffectAssessment') or frame.get('status') != 'REVIEWED_FOR_PVS1_SPLICE_INFRAME' or not text(frame.get('reviewId')) or not sourced(frame, sources):
            return 'SPLICE_INFRAME_OUTCOME_NOT_REVIEWED'
        if 'criticalRegionAssessment' in frame:
            region = frame['criticalRegionAssessment']
            if not fields(region, 'status coverage finding start end basis sourceIds') or not sourced(region, sources) \
                    or region.get('status') not in {'REVIEWED_IN_DECLARED_SCOPE', 'UNREVIEWED'} \
                    or region.get('coverage') not in {'COMPLETE_FOR_AFFECTED_PROTEIN_REGION', 'INCOMPLETE', 'UNKNOWN'} \
                    or region.get('finding') not in {'NO_KNOWN_CRITICAL_REGION_AFFECTED', 'KNOWN_CRITICAL_REGION_AFFECTED', 'UNKNOWN'} \
                    or not positive(region.get('start')) or not positive(region.get('end')) or not region['start'] <= region['end'] <= model.get('proteinLength', 0):
                return 'SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_INVALID'
    if 'codingEditComposition' in outcome:
        c = outcome['codingEditComposition']
        if not fields(c, 'schemaVersion status reviewId basis sourceIds alleleGroupSha256 referenceCdsSha256 spliceEventSha256 sequenceScope alteredCdsSequence codingHgvsc') \
                or c.get('schemaVersion') != 'pvs1-splice-coding-composition-v1' or c.get('status') != 'REVIEWED_FOR_SAME_ALLELE_COMPOSITION' \
                or not text(c.get('reviewId')) or not sourced(c, sources) or c.get('alleleGroupSha256') != content_hash(allele) \
                or c.get('referenceCdsSha256') != sha(model['cdsSequence'].encode('ascii')) or c.get('spliceEventSha256') != content_hash(event) \
                or c.get('sequenceScope') not in {'REFERENCE_CDS_DELETION', 'COMPLETE_ALLELIC_CDS'} \
                or c.get('sequenceScope') == 'COMPLETE_ALLELIC_CDS' and (not isinstance(c.get('alteredCdsSequence'), str) or not re.fullmatch('[ACGT]{1,2000000}', c['alteredCdsSequence'])) \
                or c.get('sequenceScope') == 'REFERENCE_CDS_DELETION' and 'alteredCdsSequence' in c \
                or 'codingHgvsc' in c and not text(c['codingHgvsc']):
            return 'SPLICE_COMPOSITION_INVALID'
    if 'compositeEffectAssessment' in outcome.get('inFrameReview', {}) and 'inFrameScope' not in policy['spliceScope']:
        return 'SPLICE_COMPOSITE_POLICY_SCOPE_REQUIRED'
    return composite_original_issue(model, outcome, verified_sources)

def composite_original_issue(model, outcome, verified_sources):
    effect = outcome.get('inFrameReview', {}).get('compositeEffectAssessment')
    if effect is None and 'compositeEffectAssessment' not in outcome.get('inFrameReview', {}):
        return None
    composition, sources = outcome.get('codingEditComposition', {}), outcome['sourceRecords']
    if not fields(effect, 'schemaVersion status mechanicalEffectSha256 reviewId basis sourceIds originalReview') \
            or effect.get('schemaVersion') != 'pvs1-composite-inframe-effect-review-v1' \
            or effect.get('status') != 'REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT' or not digest(effect.get('mechanicalEffectSha256')) \
            or not text(effect.get('reviewId')) or not sourced(effect, sources) or composition.get('sequenceScope') != 'COMPLETE_ALLELIC_CDS':
        return 'SPLICE_COMPOSITE_REVIEW_REQUIRED'
    witness = effect.get('originalReview')
    if not fields(witness, 'sourceId structuredOriginalUtf8') or not text(witness.get('sourceId')) \
            or not isinstance(witness.get('structuredOriginalUtf8'), str) or witness['sourceId'] not in effect['sourceIds']:
        return 'SPLICE_COMPOSITE_ORIGINAL_REQUIRED'
    try:
        raw = witness['structuredOriginalUtf8'].encode('utf-8', errors='strict')
    except UnicodeError:
        return 'SPLICE_COMPOSITE_ORIGINAL_BYTES_INVALID'
    matches = [s for s in sources if s['sourceId'] == witness['sourceId']]
    if not 1 <= len(raw) <= 65536 or len(matches) != 1 or sha(raw) != matches[0]['sha256']:
        return 'SPLICE_COMPOSITE_ORIGINAL_BYTES_INVALID'
    source = matches[0]
    if not isinstance(verified_sources, list) or len([p for p in verified_sources if p.get('source') == source \
            and p.get('status') == 'RAW_SHA_MATCH' and type(p.get('bytes')) is int and p['bytes'] == len(raw)]) != 1:
        return 'SPLICE_COMPOSITE_ORIGINAL_BYTES_INVALID'
    try:
        original = parse(raw)
    except (ValueError, UnicodeError, RecursionError):
        return 'SPLICE_COMPOSITE_ORIGINAL_INVALID'
    if not fields(original, 'schemaVersion reviewId scope sequence events conditions use') \
            or original.get('schemaVersion') != 'pvs1-composite-effect-review-original-v1' or original.get('reviewId') != effect['reviewId']:
        return 'SPLICE_COMPOSITE_ORIGINAL_INVALID'
    scope = original.get('scope')
    keys = ['hgncId', 'mondoId', 'moi', 'transcript', 'transcriptModelSha256', 'allele']
    if not fields(scope, ' '.join(keys)) or any(scope.get(k) != outcome.get(k) for k in keys):
        return 'SPLICE_COMPOSITE_ORIGINAL_SCOPE_MISMATCH'
    sequence = original.get('sequence')
    if not fields(sequence, 'referenceCdsSha256 alteredCdsSequence alteredCdsSha256') \
            or sequence.get('referenceCdsSha256') != sha(model['cdsSequence'].encode('ascii')) \
            or sequence.get('alteredCdsSequence') != composition.get('alteredCdsSequence') \
            or not isinstance(sequence.get('alteredCdsSequence'), str) or not re.fullmatch('[ACGT]{1,2000000}', sequence['alteredCdsSequence']) \
            or sequence.get('alteredCdsSha256') != sha(sequence['alteredCdsSequence'].encode('ascii')):
        return 'SPLICE_COMPOSITE_ORIGINAL_SEQUENCE_MISMATCH'
    events = original.get('events')
    if not fields(events, 'rnaEvent codingAllele codingEditComposition') or events.get('rnaEvent') != outcome.get('event') \
            or events.get('codingAllele') != outcome.get('allele') or events.get('codingEditComposition') != composition:
        return 'SPLICE_COMPOSITE_ORIGINAL_EVENTS_MISMATCH'
    conditions = original.get('conditions')
    frame = copy.deepcopy(outcome['inFrameReview']);frame.pop('compositeEffectAssessment')
    if not fields(conditions, 'parentReview assayAssessment inFrameReview') or conditions.get('parentReview') != outcome.get('review') \
            or conditions.get('assayAssessment') != outcome.get('assessment') or conditions.get('inFrameReview') != frame:
        return 'SPLICE_COMPOSITE_ORIGINAL_CONDITIONS_MISMATCH'
    use = original.get('use')
    if not fields(use, 'purpose allowedDataClasses clinicalAcceptance createsExpertReview') \
            or use.get('purpose') != 'PROJECT_RESEARCH' or use.get('allowedDataClasses') != ['SYNTHETIC', 'PRIVACY_BOUNDED_RUNTIME'] \
            or use.get('clinicalAcceptance') is not False or use.get('createsExpertReview') is not False:
        return 'SPLICE_COMPOSITE_ORIGINAL_USE_RESTRICTED'
    return None



def qualify(material, policy):
    model_material = material.get('modelMaterial')
    if not isinstance(model_material, dict) or not isinstance(model_material.get('model'), dict) or not digest(model_material.get('rawSha256')) \
            or content_hash(model_material['model']) != policy['transcriptModelBinding']['canonicalSha256']:
        return None, ['SPLICE_MODEL_BYTES_REQUIRED'], []
    model = model_material['model']
    issue = scope_issue(policy, model)
    if issue:
        return None, [issue], []
    verified = {c.get('binding', {}).get('canonicalSha256'): c for c in material.get('spliceResourceChecks', []) if c.get('status') == 'RAW_SHA_MATCH'}
    proofs, reasons, unresolved, seen = [], [], [], set()
    for record in material.get('spliceMaterials', []):
        outcome = record.get('outcome', {})
        pin = content_hash(outcome)
        check = verified.get(pin, {})
        if pin in seen or pin != record.get('canonicalSha256') or check.get('binding', {}).get('sha256') != record.get('rawSha256') \
                or not digest(record.get('rawSha256')) or type(check.get('bytes')) is not int or not 1 <= check['bytes'] <= 2_000_000 \
                or not proofs_valid(outcome.get('sourceRecords'), record.get('sourceChecks')):
            reasons.append('SPLICE_QUALIFICATION_BYTES_REQUIRED')
            continue
        seen.add(pin)
        issue = outcome_issue(policy, model, outcome, record.get('sourceChecks'))
        if issue:
            reasons.append(issue)
        else:
            review = outcome['review']
            if review.get('outcomeMultiplicity') != 'SINGLE_SUPPORTED_OUTCOME':
                unresolved.append(dict(outcomeId=outcome['outcomeId'], reasonCode='PVS1_SPLICE_OUTCOME_UNRESOLVED'))
            elif review.get('functionalRescue') != 'EXCLUDED_IN_REVIEWED_SCOPE':
                unresolved.append(dict(outcomeId=outcome['outcomeId'], reasonCode='PVS1_SPLICE_FUNCTIONAL_RESCUE_UNRESOLVED'))
            elif not residual_transcript_compatible(review, outcome['evidenceKind'] == 'PREDICTED_CANONICAL'):
                unresolved.append(dict(outcomeId=outcome['outcomeId'], reasonCode='PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED'))
        proofs.append(dict(outcome=copy.deepcopy(outcome), rawSha256=record['rawSha256'], canonicalSha256=pin,
                           bytes=check['bytes'], status='RAW_SHA_MATCH', sourceVerification=copy.deepcopy(record['sourceChecks'])))
    return dict(model=copy.deepcopy(model), modelRawSha256=model_material['rawSha256'], outcomes=proofs), list(dict.fromkeys(reasons)), unresolved
