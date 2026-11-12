"""Offline PP1 evaluation of server-captured, pinned audit artifacts.

Replays reviewed facts and public source bytes, not saved scores. The
current selected context and current ledger are supplied by Java preparation.
There is no network/model transport in this entry point.
"""
import argparse
import re
from pathlib import Path

from bundle_model_contract import load
from model_literature_contract import canonical, digest, parse
from model_transport import atomic, bounded
from pp1_context import select
from pp1_segregation import evaluate, REQUIREMENTS, RULE as SIMPLE_RULE
from process_pp1_paper import require
from pp1_reading_loop import material_coverage, qualify_artifacts

VERSION = 'pp1-business-evaluation-v1'

STAGE_PROJECTION_VERSION = 'literature-stage-public-v1'
_STAGE_LIMIT = 256 * 1024
_PIN = re.compile(r'[a-f0-9]{64}')
_CODE = re.compile(r'[A-Z][A-Z0-9_]{0,191}')
_PMCID = re.compile(r'PMC[1-9][0-9]{0,28}')
_ALIAS = re.compile(r'(?:(?:[A-Z]{2}_[0-9]+(?:\.[1-9][0-9]*)?|ENS[TP][0-9]+(?:\.[1-9][0-9]*)?):)?(?:[cgpmunrb]\.[A-Za-z0-9_*+>?=():;\[\],-]+|rs[1-9][0-9]*)')


class _StageProjectionInvalid(Exception):
    """An operational summary is withheld, never a new scoring gate."""


def _stage_check(ok):
    if not ok:
        raise _StageProjectionInvalid()


def _stage_pin(value):
    return type(value) is str and _PIN.fullmatch(value) is not None


def _stage_code(value):
    return type(value) is str and _CODE.fullmatch(value) is not None


def _stage_count(value, maximum=2147483647):
    return type(value) is int and 0 <= value <= maximum


def _stage_target(value):
    _stage_check(type(value) is dict and set(value) == {'geneSymbol', 'variantAliases'})
    gene, aliases = value['geneSymbol'], value['variantAliases']
    _stage_check(type(gene) is str and re.fullmatch(r'[A-Za-z0-9_-]{1,40}', gene))
    _stage_check(type(aliases) is list and 1 <= len(aliases) <= 20
                 and all(type(a) is str and 1 <= len(a) <= 200 and not re.search(r'[\x00-\x1f\x7f]', a)
                         and _ALIAS.fullmatch(_stage_notation(a)) for a in aliases)
                 and len(set(aliases)) == len(aliases))
    # Request aliases are public variant notation, never arbitrary labels/paths.
    return {'geneSymbol': gene, 'variantAliases': list(aliases)}


def _stage_notation(value):
    value = re.sub(r'\s+', '', value)
    prefix = re.match(r'^(N[MRP]_[0-9]+\.[1-9][0-9]*|ENS[TP][0-9]+\.[1-9][0-9]*):', value)
    if prefix:
        return prefix[0] + _stage_notation(value[len(prefix[0]):])
    protein = re.fullmatch(r'p\.?\(([A-Za-z*]+[1-9][0-9]*[A-Za-z*=]+)\)', value)
    return 'p.' + protein[1] if protein else value


def _stage_binding(document, source):
    request, result = document['request'], document['result']
    pmcid, bundle = request.get('pmcid'), request.get('bundleSha256')
    _stage_check(_stage_pin(document.get('taskId')) and _stage_pin(document.get('resultSha256'))
                 and document['resultSha256'] == digest(canonical(result)))
    _stage_check(type(pmcid) is str and _PMCID.fullmatch(pmcid) and _stage_pin(bundle)
                 and result.get('pmcid') == pmcid and result.get('bundleSha256') == bundle
                 and source.get('pmcid') == pmcid and source.get('bundleSha256') == bundle)
    _stage_check(result.get('analysisBindingSha256') == digest(canonical(request['analysisBinding'])))
    target = _stage_target(request.get('target')); returned = _stage_target(result.get('target'))
    _stage_check(target['geneSymbol'] == returned['geneSymbol'] == request['analysisBinding']['selection']['geneSymbol'])
    allowed = set()
    for alias in target['variantAliases']:
        notation = _stage_notation(alias); allowed.add(notation)
        short = re.fullmatch(r'(?:N[MRP]_[0-9]+\.[1-9][0-9]*|ENS[TP][0-9]+\.[1-9][0-9]*):([cp]\..+)', notation)
        if short:
            allowed.add(short[1])
    _stage_check(all(_stage_notation(a) in allowed for a in returned['variantAliases']))
    # This pin is the captured paper's canonical result. A discovery task may
    # have several paper fragments; it is not the whole task raw-result pin and
    # must never be sent as a continuation authorization.
    out = {'bundleSha256': bundle, 'capturedResultSha256': document['resultSha256'],
           'bindingPinKind': 'CAPTURED_PAPER_RESULT_CANONICAL_SHA256', 'target': target}
    if _stage_pin(request.get('selectionSha256')):
        out['selectionSha256'] = request['selectionSha256']
    out['analysisBindingSha256'] = result['analysisBindingSha256']
    return out


def _stage_recovery(value):
    _stage_check(type(value) is dict and all(_stage_code(value.get(k)) for k in ('status', 'stage'))
                 and type(value.get('resumeAvailable')) is bool and value.get('automaticRetryAllowed') is False)
    out = {k: value[k] for k in ('status', 'stage', 'resumeAvailable', 'automaticRetryAllowed')}
    if value.get('reasonCode') is not None:
        _stage_check(_stage_code(value['reasonCode'])); out['reasonCode'] = value['reasonCode']
    for key in ('sharedBudgetReconciliationRequired', 'resourceRepairRequired'):
        if key in value:
            _stage_check(type(value[key]) is bool); out[key] = value[key]
    return out


_FACT_ENUMS = {
    'genotype': {'CARRIER', 'NONCARRIER', 'HETEROZYGOUS', 'HOMOZYGOUS', 'HEMIZYGOUS'},
    'clinical': {'AFFECTED', 'UNAFFECTED'}, 'sex': {'MALE', 'FEMALE'},
    'candidateScope': {'SINGLE_REPORTED_CANDIDATE'},
    'formalAscertainment': {'PHENOTYPE_BEFORE_GENOTYPING'},
    'phase': {'IN_TRANS', 'IN_CIS', 'UNKNOWN'},
    'geneDiseaseValidity': {'STRONG', 'DEFINITIVE', 'MODERATE', 'LIMITED', 'DISPUTED', 'REFUTED'},
    'expertPolicyStatus': {'GENERAL_ALLOWED', 'APPLICABLE_IMPLEMENTED', 'APPLICABLE_UNRESOLVED'},
    'penetrance': {'COMPLETE', 'AFFECTED_ONLY', 'EXPLICIT_FORMAL_MODEL'},
    'phenocopy': {'HEURISTIC_APPLICABLE', 'EXPLICIT_FORMAL_MODEL'},
    'locusModel': {'HETEROGENEOUS', 'HIGH_YIELD_HOMOGENEOUS'},
    'method': {'CLINGEN_SVI_2024_SIMPLE_SEGREGATION', 'PUBLISHED_AFFECTED_RELATIVES_COUNT',
               'PUBLISHED_INFORMATIVE_MEIOSES_COUNT', 'VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1'},
    'familyRelationship': {'RELATED', 'SAME_FAMILY', 'UNRESOLVED'},
    'hhtManifestation': {'EPISTAXIS', 'TELANGIECTASES', 'VISCERAL_LESIONS'},
    'hhtManifestationAbsent': {'EPISTAXIS', 'TELANGIECTASES', 'VISCERAL_LESIONS'},
}
# The six static route fallbacks of pp1_segregation.evaluate.need(). Keep this
# display-only mirror byte-for-byte aligned with the readonly producer literal;
# the focused AST test detects drift without executing or reflecting on scoring.
_ROUTE_REQUIREMENTS = {
    'KNOWLEDGE': '需要相容且有来源依据的疾病/方法知识；论文事实和模型意见不替代知识采用。',
    'BUSINESS_CONTEXT': '需要正式选择和既有证据记录绑定，不能借用其他分析或疾病的结果。',
    'IDENTITY_RESOURCE': '需要相容的本地身份资源或已支持的表示解析，软件未支持与数据库无记录分开处理。',
    'METHOD_REVIEW': '当前事实触发简单计数之外的方法条件，保留原文，不能套用简单分数。',
    'IDENTITY_REVIEW': '需要确定报告之间的家系身份及独立性，不能把不同论文视为不同家系。',
    'SOFTWARE_OR_SOURCE_REVIEW': '原文信息已找到，但语义核实尚不充分；需源语义处理，不应解释成没有资料。',
}
_REQUIRED_INPUTS = {'CLINICAL_STATUS', 'TARGET_GENOTYPE', 'INFORMATIVE_RELATIONSHIP'}
_OPAQUE_SCOPE_IDS = {'familyKey', 'memberKey', 'claimId', 'eventId', 'probandKey'}
_SCOPE_ID_ARRAYS = {'priorEventIds', 'priorExclusiveFactIds'}


def _detail_status(total, displayed, withheld, omitted, recorded=True):
    status = ('NOT_RECORDED' if not recorded or total == 0 else 'AVAILABLE' if withheld == omitted == 0
              else 'PARTIAL' if displayed else 'WITHHELD')
    return {'status': status, 'total': total, 'displayed': displayed, 'withheld': withheld, 'omitted': omitted}


def _required_inputs(scope):
    values = scope.get('missingInputs') if type(scope) is dict else None
    if type(values) is not list:
        return [], _detail_status(0, 0, 0, 0, False)
    shown = [v for v in values[:8] if type(v) is str and v in _REQUIRED_INPUTS]
    return shown, _detail_status(len(values), len(shown), min(len(values), 8)-len(shown), max(0, len(values)-8))


def _stage_scope(value):
    if value is None:
        return None, {'status': 'NOT_RECORDED', 'kind': 'NULL', 'total': 0, 'displayed': 0, 'withheld': 0, 'omitted': 0}
    if type(value) is str:
        valid = _stage_pin(value)
        return value if valid else None, {**_detail_status(1, int(valid), int(not valid), 0), 'kind': 'OPAQUE_ID' if valid else 'UNSUPPORTED'}
    if type(value) is list:
        shown = [v for v in value[:32] if _stage_pin(v)]
        return shown, {**_detail_status(len(value), len(shown), min(len(value), 32)-len(shown), max(0, len(value)-32)), 'kind': 'OPAQUE_ID_LIST'}
    if type(value) is not dict:
        return None, {**_detail_status(1, 0, 1, 0), 'kind': 'UNSUPPORTED'}
    out, paper, accepted = {}, {}, set()
    for key in ('pmcid', 'bundleSha256', 'mainSha256'):
        part = value.get(key)
        if (_PMCID.fullmatch(part) if key == 'pmcid' and type(part) is str else _stage_pin(part) if key != 'pmcid' else False):
            paper[key] = part;accepted.add(key)
    if type(value.get('paper')) is dict:
        nested = value['paper'];safe = {}
        for key in ('pmcid', 'bundleSha256', 'mainSha256'):
            part = nested.get(key)
            if (_PMCID.fullmatch(part) if key == 'pmcid' and type(part) is str else _stage_pin(part) if key != 'pmcid' else False):
                safe[key] = part
        if safe:
            out['paper'] = safe;accepted.add('paper')
    if paper:
        out['paper'] = paper
    for key in _OPAQUE_SCOPE_IDS:
        if _stage_pin(value.get(key)):
            out[key] = value[key];accepted.add(key)
    for key in _SCOPE_ID_ARRAYS:
        if type(value.get(key)) is list and len(value[key]) <= 32 and all(_stage_pin(v) for v in value[key]):
            out[key] = list(value[key]);accepted.add(key)
    if type(value.get('missingInputs')) is list:
        inputs, projection = _required_inputs(value)
        if projection['status'] == 'AVAILABLE':
            accepted.add('missingInputs')
    # No family/member role is assigned to an opaque scalar or list, and raw
    # memberLabel is deliberately never part of this public scope.
    return out, {**_detail_status(len(value), len(accepted), len(value)-len(accepted), 0), 'kind': 'OBJECT'}


def _fact_literal(kind, value):
    if kind in _FACT_ENUMS and type(value) is str and value in _FACT_ENUMS[kind]:
        return value, 'ENUM'
    if kind in {'proband', 'consanguinity', 'hhtCuracaoDefinite'} and type(value) is bool:
        return value, 'BOOLEAN'
    if kind in {'age', 'examinationAge'} and _stage_count(value):
        return value, 'INTEGER'
    if kind == 'dosage' and _stage_count(value, 2):
        return value, 'INTEGER'
    if kind in {'parentOf', 'childOf', 'fullSiblingOf', 'phaseAnchor', 'alleleOriginParent'} and _stage_pin(value):
        return value, 'OPAQUE_MEMBER_ID'
    if kind in {'reportedTranscript', 'transcript'} and type(value) is str and re.fullmatch(
            r'(?:N[CMRPG]_[0-9]+|ENS[TP][0-9]+)\.[1-9][0-9]*', value):
        return value, 'SOURCE_ACCESSION'
    if kind in {'reportedVariant', 'targetVariant', 'partnerVariant', 'variant'} and type(value) is str             and 0 < len(value) <= 300 and not re.search(r'[\x00-\x1f\x7f]', value):
        compact = value.replace(' ', '')
        from literature_allele_identity import coding
        try:
            coding(compact)
            return value, 'HGVS_LITERAL'
        except ValueError:
            # Display recognition only. Never mine an HGVS substring from prose
            # or treat this lexical recognition as allele identity qualification.
            notation = _stage_notation(compact)
            if ':' in notation:
                accession, protein = notation.split(':', 1)
                if not re.fullmatch(r'(?:N[MRP]_[0-9]+|ENS[TP][0-9]+)\.[1-9][0-9]*', accession):
                    raise _StageProjectionInvalid()
            else:
                protein = notation
            amino = r'(?:Ala|Arg|Asn|Asp|Cys|Gln|Glu|Gly|His|Ile|Leu|Lys|Met|Phe|Pro|Ser|Thr|Trp|Tyr|Val|Ter|[ARNDCQEGHILKMFPSTWYV*])'
            if re.fullmatch(r'p\.'+amino+r'[1-9][0-9]*(?:'+amino+r'|=|\?|fs(?:Ter|\*)[1-9][0-9]*)', protein):
                return value, 'HGVS_LITERAL'
    raise _StageProjectionInvalid()


def _known_fact_projection(values):
    if type(values) is not list:
        return [], _detail_status(0, 0, 0, 0, False)
    shown, withheld = [], 0
    for raw in values[:8]:
        try:
            _stage_check(type(raw) is dict and type(raw.get('kind')) is str)
            value, kind = _fact_literal(raw['kind'], raw.get('value'))
            shown.append({'kind': raw['kind'], 'value': value, 'valueType': kind,
                          'interpretation': 'CAPTURED_SOURCE_VALUE_NOT_CURRENT_ADOPTION'})
        except (_StageProjectionInvalid, ValueError, TypeError):
            withheld += 1
    projection = _detail_status(len(values), len(shown), withheld, max(0, len(values)-8))
    if withheld:
        projection['reasonCode'] = 'FREE_TEXT_OR_UNSUPPORTED_TYPE_WITHHELD'
    return shown, projection


def _stage_locator_projection(values, source):
    if type(values) is not list:
        return [], _detail_status(0, 0, 0, 0, False)
    units = {u['unitId']: u for u in source.get('units', []) if type(u) is dict and type(u.get('unitId')) is str}
    pins = {u.get('sourceSha256') for u in units.values() if _stage_pin(u.get('sourceSha256'))}
    if _stage_pin(source.get('main', {}).get('sourceSha256')):
        pins.add(source['main']['sourceSha256'])
    pins.update(a.get('sha256') for a in source.get('attachments', []) if type(a) is dict and _stage_pin(a.get('sha256')))
    shown, withheld = [], 0
    for ref in values[:8]:
        if type(ref) is not dict:
            withheld += 1;continue
        uid = ref.get('unitId', ref.get('locator'));unit = units.get(uid) if type(uid) is str else None
        if ref.get('pmcid', source['pmcid']) != source['pmcid'] or ref.get('bundleSha256', source['bundleSha256']) != source['bundleSha256']:
            withheld += 1;continue
        if ref.get('sourceKind') == 'ORIGINAL_PUBLISHED_PAGE' and source.get('reviewedPages'):
            from pp1_reviewed_pages import check_reference
            try:
                check_reference(source, ref)
            except (ValueError, KeyError, TypeError):
                withheld += 1
            else:
                shown.append({'kind': 'ORIGINAL_PUBLISHED_PAGE', 'unitId': uid, 'sourceSha256': ref['sourceSha256'],
                              'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
                              'bindingStatus': 'MATCHED_REGISTERED_SOURCE_UNIT'})
            continue
        if unit is not None and len(uid) <= 1024:
            pin = unit.get('sourceSha256')
            valid = (_stage_pin(pin) and ref.get('sourceSha256', pin) == pin
                     and not re.search(r'[\x00-\x1f\x7f\\]|://|(?:^|:)[A-Za-z]:[/\\]', uid))
            jats = re.fullmatch(r'/article(?:\[[1-9][0-9]*\])?(?:/[A-Za-z][A-Za-z0-9_.-]*(?:\[[1-9][0-9]*\])?)*', uid)
            attachment = re.fullmatch(r'attachment:[a-f0-9]{64}:(?:member:[a-f0-9]{64}:[a-f0-9]{64}:)?[A-Za-z0-9_:/.[\]-]+', uid)
            if valid and (jats or attachment):
                shown.append({'kind': 'JATS_UNIT' if jats else 'ATTACHMENT_UNIT', 'unitId': uid,
                              'sourceSha256': pin, 'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
                              'bindingStatus': 'MATCHED_REGISTERED_SOURCE_UNIT'})
                continue
        pin = ref.get('sourceSha256')
        if _stage_pin(pin) and pin in pins and uid is None:
            shown.append({'kind': 'SOURCE_PIN_ONLY', 'sourceSha256': pin, 'pmcid': source['pmcid'],
                          'bundleSha256': source['bundleSha256'], 'bindingStatus': 'REGISTERED_PIN_WITHOUT_VERIFIED_LOCATOR'})
        else:
            withheld += 1
    return shown, _detail_status(len(values), len(shown), withheld, max(0, len(values)-8))


def _trim_missing_details(row):
    # Bound each rich row first, preserving native reason/scope hash and all
    # totals. Removing a safe item for space is omission, not an invalid fact.
    while len(canonical(row)) > 8192:
        for key, projection in (('sourceLocators', 'sourceLocatorProjection'), ('knownFacts', 'knownFactsProjection')):
            if row[key]:
                row[key].pop();row[projection]['displayed'] -= 1;row[projection]['omitted'] += 1
                row[projection]['status'] = 'PARTIAL' if row[key] else 'WITHHELD'
                break
        else:
            if 'requirement' in row:
                row.pop('requirement');row['requirementProjection'] = {'status': 'WITHHELD', 'reasonCode': 'PUBLIC_DETAIL_SIZE_LIMIT'}
            elif 'scope' in row:
                row.pop('scope');row['scopeProjection']['status'] = 'WITHHELD'
                row['scopeProjection']['omitted'] += row['scopeProjection']['displayed'];row['scopeProjection']['displayed'] = 0
            else:
                raise _StageProjectionInvalid()


def _stage_missing(values, source):
    _stage_check(type(values) is list)
    rows, invalid, size_omitted = [], 0, 0
    for index, need in enumerate(values[:128]):
        try:
            _stage_check(type(need) is dict and all(_stage_code(need.get(k)) for k in ('reasonCode', 'route', 'blockingEffect')))
            row = {k: need[k] for k in ('reasonCode', 'route', 'blockingEffect')}
            if 'needId' in need:
                _stage_check(_stage_pin(need['needId'])); row['needId'] = need['needId']
            if 'absenceClaim' in need:
                _stage_check(_stage_code(need['absenceClaim'])); row['absenceClaim'] = need['absenceClaim']
            scope = need.get('scope');row['scopeSha256'] = digest(canonical(scope))
            row['scope'], row['scopeProjection'] = _stage_scope(scope)
            row['requiredInputs'], row['requiredInputsProjection'] = _required_inputs(scope)
            row['knownFacts'], row['knownFactsProjection'] = _known_fact_projection(need.get('knownFacts'))
            row['sourceLocators'], row['sourceLocatorProjection'] = _stage_locator_projection(need.get('sourceLocators'), source)
            row['knownFactCount'] = len(need['knownFacts']) if type(need.get('knownFacts')) is list else 0
            row['sourceLocatorCount'] = len(need['sourceLocators']) if type(need.get('sourceLocators')) is list else 0
            requirement = REQUIREMENTS.get(need['reasonCode'])
            catalog = 'PROGRAM_REQUIREMENTS_CATALOG'
            if requirement is None:
                requirement = _ROUTE_REQUIREMENTS.get(need['route'])
                if requirement is not None:
                    catalog = 'PROGRAM_ROUTE_REQUIREMENTS_CATALOG'
            row['requirementProjection'] = {'status': 'AVAILABLE' if requirement else 'NOT_RECORDED', 'source': catalog}
            if requirement:
                row['requirement'] = requirement
                if catalog == 'PROGRAM_ROUTE_REQUIREMENTS_CATALOG':
                    row['requirementProjection']['fallbackRoute'] = need['route']
            if need.get('ruleClause') == SIMPLE_RULE:
                row['ruleClause'] = SIMPLE_RULE
            _trim_missing_details(row)
            if len(canonical(rows+[row])) > 192*1024:
                size_omitted = len(values)-index;break
            rows.append(row)
        except (_StageProjectionInvalid, ValueError, TypeError, RecursionError):
            invalid += 1
    return rows, {'missingTotal': len(values), 'missingOmitted': len(values)-len(rows),
                  'missingInvalid': invalid, 'missingSizeOmitted': size_omitted}


def _project_reading_state(document, source):
    original = document.get('result', {}).get('readingState')
    fallback = {'projectionVersion': STAGE_PROJECTION_VERSION,
                'projection': {'status': 'NOT_RECORDED', 'reasonCode': 'PP1_STAGE_STATE_NOT_RECORDED'}}
    try:
        binding = _stage_binding(document, source)
    except (_StageProjectionInvalid, ValueError, TypeError, KeyError, RecursionError):
        if original is not None:
            fallback['projection'].update(status='WITHHELD', reasonCode='PP1_STAGE_BINDING_INVALID')
        return {}, fallback
    if original is None:
        return binding, fallback
    try:
        _stage_check(type(original) is dict and original.get('schemaVersion') == 'pp1-paper-reading-state-v1'
                     and _stage_code(original.get('stage')) and original.get('wholePaperAbsenceClaim') is False)
        required, available, checkpoint = (original.get(k) for k in ('required', 'available', 'checkpoint'))
        _stage_check(all(type(v) is dict for v in (required, available, checkpoint)))
        pin = binding['bundleSha256']; pmcid = document['request']['pmcid']
        _stage_check(required.get('registeredSourceBundle') == available.get('verifiedSourceBundle') == checkpoint.get('bundleSha256') == pin
                     and checkpoint.get('pmcid') == pmcid and required.get('sourceReview') == 'REPLAYABLE_SOURCE_REVIEW'
                     and required.get('decisionInputs') == 'CURRENT_SELECTED_CONTEXT_AND_QUALIFIED_OBSERVATIONS'
                     and type(required.get('sourceReviewArtifactsAtLeast')) is int and required['sourceReviewArtifactsAtLeast'] == 1)
        for name in ('textUnits', 'acceptedReviewArtifacts', 'remainingCallCapacity', 'qualifiedObservations'):
            _stage_check(_stage_count(available.get(name), 64 if name == 'remainingCallCapacity' else 32 if name == 'acceptedReviewArtifacts' else 2147483647))
        _stage_check(type(available.get('modelExecutionEnabled')) is bool)
        pins = checkpoint.get('acceptedArtifactSha256s')
        _stage_check(type(pins) is list and len(pins) == available['acceptedReviewArtifacts'] <= 32
                     and all(_stage_pin(p) for p in pins) and len(set(pins)) == len(pins))
        missing, projection = _stage_missing(original.get('missing'), source)
        state = {'schemaVersion': 'pp1-paper-reading-state-v1', 'projectionVersion': STAGE_PROJECTION_VERSION,
                 'stage': original['stage'], 'required': {k: required[k] for k in ('registeredSourceBundle', 'sourceReview', 'sourceReviewArtifactsAtLeast', 'decisionInputs')},
                 'available': {k: available[k] for k in ('verifiedSourceBundle', 'textUnits', 'acceptedReviewArtifacts', 'modelExecutionEnabled', 'remainingCallCapacity', 'qualifiedObservations')},
                 'checkpoint': {'pmcid': pmcid, 'bundleSha256': pin, 'acceptedArtifactSha256s': list(pins)},
                 'missing': missing, 'recovery': _stage_recovery(original.get('recovery')), 'stageGaps': [],
                 'wholePaperAbsenceClaim': False, 'projection': {'status': 'AVAILABLE', **projection}}
        if 'sourceExecutionRecovery' in original:
            state['sourceExecutionRecovery'] = _stage_recovery(original['sourceExecutionRecovery'])
        gaps = original.get('stageGaps')
        _stage_check(type(gaps) is list and len(gaps) <= 1)
        for gap in gaps:
            _stage_check(type(gap) is dict)
            gap_scope = gap.get('scope')
            _stage_check(type(gap_scope) is dict and gap.get('kind') == 'SYSTEM_STAGE' and gap.get('stage') == 'SOURCE_REVIEW'
                         and _stage_code(gap.get('reasonCode')) and gap.get('requirement') == 'REPLAYABLE_SOURCE_REVIEW'
                         and gap_scope.get('pmcid') == pmcid and gap_scope.get('bundleSha256') == pin)
            required_gap, available_gap, difference = (gap.get(k) for k in ('required', 'available', 'difference'))
            _stage_check(all(type(v) is dict for v in (required_gap, available_gap, difference)))
            _stage_check(type(required_gap.get('minimumReviewArtifacts')) is int and required_gap['minimumReviewArtifacts'] == 1
                         and _stage_count(available_gap.get('replayedReviewArtifacts')) and available_gap['replayedReviewArtifacts'] == available['acceptedReviewArtifacts'] == 0
                         and available_gap.get('verifiedSourceBundle') == pin
                         and type(difference.get('missingMinimumReviewArtifacts')) is int and difference['missingMinimumReviewArtifacts'] == 1)
            state['stageGaps'].append({'kind': 'SYSTEM_STAGE', 'stage': 'SOURCE_REVIEW', 'reasonCode': gap['reasonCode'],
                'requirement': 'REPLAYABLE_SOURCE_REVIEW', 'required': {'minimumReviewArtifacts': 1},
                'available': {'replayedReviewArtifacts': 0, 'verifiedSourceBundle': pin},
                'difference': {'missingMinimumReviewArtifacts': 1}, 'scope': {'pmcid': pmcid, 'bundleSha256': pin},
                'recovery': _stage_recovery(gap.get('recovery'))})
        _stage_check(len(canonical(state)) <= _STAGE_LIMIT)
        return binding, state
    except (_StageProjectionInvalid, ValueError, TypeError, KeyError, RecursionError):
        return binding, {'projectionVersion': STAGE_PROJECTION_VERSION,
                         'projection': {'status': 'WITHHELD', 'reasonCode': 'PP1_STAGE_STATE_INVALID'}}


def _public_reading_plan(value):
    if type(value) is not dict:
        return None
    out = {'wholePaperAbsenceClaim': False}
    if _stage_count(value.get('remainingChunks')):
        out['remainingChunks'] = value['remainingChunks']
    if _stage_code(value.get('selectionBasis')):
        out['selectionBasis'] = value['selectionBasis']
    def chunk(row):
        if type(row) is not dict or not _stage_pin(row.get('chunkKey')) or not _stage_code(row.get('status')):
            return None
        result = {'chunkKey': row['chunkKey'], 'status': row['status']}
        if _stage_count(row.get('relevance')):
            result['relevance'] = row['relevance']
        for source, target in (('unitIds', 'unitCount'), ('targetUnitIds', 'targetUnitCount')):
            if type(row.get(source)) is list:
                result[target] = len(row[source])
        return result
    if type(value.get('chunks')) is list:
        chunks = value['chunks'];out['chunks'] = [part for row in chunks[:128] if (part := chunk(row)) is not None]
        out.update(chunkTotal=len(chunks), chunkOmitted=len(chunks)-len(out['chunks']))
    if type(value.get('selected')) is dict:
        out['selected'] = chunk(value['selected'])
    pending = value.get('pendingSourceReview')
    if type(pending) is dict:
        item = {'status': 'PENDING_SOURCE_REVIEW'}
        if _stage_pin(pending.get('parentSha256')):
            item['parentSha256'] = pending['parentSha256']
        for source, target in (('claimIds', 'claimCount'), ('unitIds', 'unitCount')):
            if type(pending.get(source)) is list:
                item[target] = len(pending[source])
        out['pendingSourceReview'] = item
    else:
        out['pendingSourceReview'] = None
    review = value.get('pedigreeReview')
    if type(review) is dict:
        item = {k: review[k] for k in ('registeredFigures', 'reviewedPairs') if _stage_count(review.get(k))}
        if type(review.get('issues')) is list:
            item['issues'] = [c for c in review['issues'][:32] if _stage_code(c)]
        out['pedigreeReview'] = item
    return out


def _public_material_coverage(source):
    value = material_coverage(source)
    out = {'wholePaperAbsenceClaim': False, 'completePublicationProven': value.get('completePublicationProven') is True}
    for key in ('textUnits', 'parsedAttachments', 'unparsedAttachments'):
        if _stage_count(value.get(key)):
            out[key] = value[key]
    out['acquisitionStatus'] = value['acquisitionStatus'] if _stage_code(value.get('acquisitionStatus')) else 'UNKNOWN'
    files = value.get('unparsedFiles', []);out['unparsedFileCount'] = len(files);out['unparsedFiles'] = [];names_withheld = 0
    for row in files[:32]:
        item = {k: row[k] for k in ('status', 'reasonCode') if _stage_code(row.get(k))}
        if _stage_pin(row.get('sha256')):
            item['sha256'] = row['sha256']
        name = row.get('originalName')
        if (type(name) is str and 1 <= len(name) <= 160 and name[0].isalnum()
                and all(c.isalnum() or c in '._- ()' for c in name)):
            item['originalName'] = name
        elif name is not None:
            names_withheld += 1
        out['unparsedFiles'].append(item)
    out['unparsedFilesOmitted'] = max(0, len(files)-32)
    out['unparsedFileNamesWithheld'] = names_withheld
    return out


def _public_source_coverage(document, source):
    request, result = document['request'], document['result']
    row = {'projectionVersion': STAGE_PROJECTION_VERSION,
           'status': result.get('status') if _stage_code(result.get('status')) else 'UNKNOWN',
           'stopReason': result.get('stopReason') if _stage_code(result.get('stopReason')) else None,
           'readingPlan': _public_reading_plan(result['reading'].get('readingPlan')) if type(result.get('reading')) is dict else None,
           'materialCoverage': _public_material_coverage(source), 'wholePaperAbsenceClaim': False}
    if _stage_pin(document.get('taskId')):
        row['taskId'] = document['taskId']
    if type(request.get('pmcid')) is str and _PMCID.fullmatch(request['pmcid']):
        row['pmcid'] = request['pmcid']
    binding, state = _project_reading_state(document, source)
    row.update(binding);row['readingState'] = state
    if len(canonical(row)) > _STAGE_LIMIT:
        row['readingPlan'] = None
        row['readingState'] = {'projectionVersion': STAGE_PROJECTION_VERSION,
            'projection': {'status': 'WITHHELD', 'reasonCode': 'PP1_STAGE_PROJECTION_SIZE_LIMIT'}}
    return row


def calculate(root, library, work, documents, binding, context, resolver):
    require(len(documents) <= 32, 'PP1_CAPTURE_DOCUMENT_LIMIT')
    from pp1_published_contribution import checked_ledger
    selection, ledger = binding['selection'], checked_ledger(binding['ledger'], context)
    groups, exclusions, coverage, sources, figures = {}, [], [], {}, {}
    for document in documents:
        request, result = document['request'], document['result']
        prior = request['analysisBinding']['selection']
        if any(prior.get(k) != selection.get(k) for k in ('geneSymbol', 'diseaseId', 'inheritance', 'allele')):
            exclusions.append({'taskId':document['taskId'], 'reasonCode':'PP1_TASK_DIFFERENT_SELECTED_CONTEXT'})
            continue
        key = (request['pmcid'], request['bundleSha256'], canonical(result['target']))
        if key[:2] not in sources:
            sources[key[:2]] = load(library, key[0], key[1], criterion='PP1')
        coverage.append(_public_source_coverage(document, sources[key[:2]]))
        from pp1_pedigree_delivery import replay_paths
        figures.setdefault(key,[]).extend(replay_paths(root,work,document.get('pedigreeReviews',[])))
        for artifact in document.get('artifacts', []):
            path = (Path(root) / artifact['path']).resolve(strict=True)
            require(path.is_relative_to(Path(work).resolve(strict=True)), 'PP1_ARTIFACT_OUTSIDE_WORK')
            raw = bounded(path, 16*1024*1024)
            require(digest(raw) == artifact['sha256'], 'PP1_CAPTURE_ARTIFACT_CHANGED')
            candidate = parse(raw)
            require(candidate['binding']['stage'] == 'SOURCE_REVIEW'
                    and candidate['binding']['pmcid'] == key[0] and candidate['binding']['bundleSha256'] == key[1]
                    and canonical(candidate['binding']['target']) == key[2], 'PP1_CAPTURE_ARTIFACT_BINDING_INVALID')
            # A repeated completed task or repeated artifact never adds a case.
            groups.setdefault(key, {})[digest(raw)] = path
    qualified = []
    for (pmcid, pin, target), artifacts in groups.items():
        require(len(artifacts) <= 32, 'PP1_CAPTURE_PAPER_ARTIFACT_LIMIT')
        _, _, qualification = qualify_artifacts(library,list(artifacts.values()),selection,resolver,
            list(dict.fromkeys(figures[(pmcid,pin,target)])),captured_only=True)
        qualified.append(qualification)
    out = {'schemaVersion':VERSION, 'analysisBindingSha256':digest(canonical(binding)),
           'sourceCoverage':coverage, 'excludedTasks':exclusions, 'modelCalls':0, 'networkRequests':0,
           'qualifications':qualified, 'clinicalApproval':False}
    if qualified:
        out['evaluation'] = evaluate(qualified, context, ledger)
        out['evaluatorCalls'] = 1
    else:
        out['evaluatorCalls'] = 0
        out['evaluation'] = {'schemaVersion':'pp1-segregation-evaluation-v1', 'target':selection,
            'status':'NOT_EVALUATED', 'strength':None, 'awardedPP1Points':0, 'events':[],
            'needs':[{'reasonCode':'PP1_NO_REPLAYABLE_SELECTED_SOURCE', 'route':'LITERATURE',
                'requirement':'尚无与当前变异及疾病相容、可重新核对来源的论文复核资料；这不表示论文没有相关病例。',
                'sourceLocators':[], 'knownFacts':[], 'blockingEffect':'BLOCKS_SCORING'}],
            'context':context, 'ledger':ledger, 'businessPublishable':False}
        from pp1_formal_policy import FORMAL_METHOD
        if context.get('policy', {}).get('method') == FORMAL_METHOD:
            out['evaluation'].update(schemaVersion='pp1-segregation-evaluation-v2', method=FORMAL_METHOD, modelVersion='1.0.0')
            out['evaluation']['needs'] += context.get('needs', [])
    return out


def run(spec_path, spec_pin, config_path, output):
    raw = bounded(spec_path, 64*1024*1024)
    require(digest(raw) == spec_pin, 'PP1_EVALUATION_REQUEST_CHANGED')
    spec = parse(raw)
    require(set(spec) == {'schemaVersion', 'analysisBinding', 'documents'}
            and spec['schemaVersion'] == VERSION, 'PP1_EVALUATION_REQUEST_SCHEMA')
    c_raw = bounded(config_path, 16384); c = parse(c_raw)
    require(c.get('schemaVersion') == 'denovo-public-literature-workflow-v1' and c.get('enabled') is True,
            'PP1_WORKFLOW_DISABLED')
    root = Path(c['projectRoot']).resolve(strict=True)
    def local(field):
        value = (root / c[field]).resolve()
        require(not Path(c[field]).is_absolute() and value.is_relative_to((root/'local').resolve()), 'PP1_PATH_OUTSIDE_LOCAL')
        return value
    out = Path(output).resolve()
    require(out.is_relative_to(local('work')) and not out.exists(), 'PP1_EVALUATION_OUTPUT_INVALID')
    binding = spec['analysisBinding']
    require(binding['schemaVersion'] == 'pp1-analysis-binding-v1', 'PP1_ANALYSIS_BINDING_REQUIRED')
    context = select(root, c.get('pp1Knowledge'), binding['selection'],
                     published_inheritance=c.get('publishedInheritanceSources', []))
    resolver = None
    if c.get('coreContext'):
        from core_context_review import load_config
        from core_local_identity import LocalIdentity
        shared, _ = load_config(root, c['coreContext'])
        resolver = LocalIdentity(root, shared['identity'])
    result = calculate(root, local('library'), local('work'), spec['documents'], binding, context, resolver)
    require(c_raw == bounded(config_path, 16384), 'PP1_CONFIGURATION_CHANGED_DURING_EVALUATION')
    result['requestSha256'] = spec_pin
    result['workflowConfigSha256'] = digest(c_raw)
    out.mkdir(parents=True)
    atomic(out/'result.json', result)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('spec', 'spec-sha256', 'config', 'output'): p.add_argument('--'+name, required=True)
    args = p.parse_args()
    print(run(args.spec, args.spec_sha256, args.config, args.output)['evaluation']['status'])


if __name__ == '__main__': main()
