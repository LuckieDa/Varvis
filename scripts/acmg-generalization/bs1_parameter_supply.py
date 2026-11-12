"""Compact explanation of existing preparation facts; never selects model inputs."""
import json
from bs1_parameters import FIELDS

VERSION = 'bs1-parameter-supply-v1'
MAX_EXAMPLES = 3


def key(scope):
    return tuple(scope.get(k) for k in ('hgncId', 'mondoId', 'moi'))


def display(value):
    if value is None:
        return ''
    return (value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True))[:300]


def summarize(drafts, observations, proposals, text_backgrounds):
    facts = {f['observationId']: f for f in observations}
    texts = {p['clauseId']: p for p in proposals}
    by_scope = {key(b['scope']): b for b in text_backgrounds}
    result = {}
    for draft in drafts:
        scope = {k: draft[k] for k in ('hgncId', 'mondoId', 'moi')}
        matches = [texts[i] for i in by_scope[key(scope)]['proposalIds']]
        fields = {}
        for field in FIELDS:
            assessment = draft['parameters'][field]
            observed = [facts[i] for i in assessment['observationIds']]
            mentioned = [p for p in matches if p['parameters'][field]['status'] != 'NO_EXPLICIT_LABEL']
            statuses = sorted({p['parameters'][field]['status'] for p in mentioned})
            reasons = set(assessment['reasonCodes'])
            for proposal in mentioned:
                reasons.update(proposal['reviewRequirements'])
                reasons.update(proposal['parsed']['issues'])
                for i in proposal['parameters'][field]['mentionIndexes']:
                    reasons.update(proposal['parsed']['mentions'][i]['issues'])
            status = assessment['status']
            point_values = {json.dumps(p['parameters'][field]['candidateValue'], sort_keys=True)
                            for p in mentioned if p['parameters'][field]['candidateValue'] is not None}
            if 'MULTIPLE_VALUES_REQUIRE_REVIEW' in statuses or len(point_values) > 1:
                status = 'CONFLICT'
                reasons.add('MULTIPLE_TEXT_VALUES_REQUIRE_RECONCILIATION')
            elif mentioned and status == 'SOURCE_GAP':
                status = 'REVIEW_REQUIRED'
            examples = []
            # Explicit text extraction first; the original full clause remains in preparation files.
            for proposal in mentioned:
                parameter = proposal['parameters'][field]
                examples.append({'source': proposal['source'], 'locator': proposal['locator'],
                    'factId': proposal['clauseId'], 'kind': 'TEXT_PROPOSAL',
                    'valueKind': parameter['status'], 'valueText': display(parameter['candidateValue']),
                    'statistic': '', 'population': ''})
            for fact in observed:
                if not fact.get('source') or not fact.get('locator'):
                    continue  # Invalid source remains in reasonCodes; never fabricate a citation.
                examples.append({'source': fact['source'], 'locator': fact['locator'],
                    'factId': fact['observationId'], 'kind': 'PREPARATION_OBSERVATION',
                    'valueKind': fact['valueKind'], 'valueText': display(fact.get('value', fact.get('reportedRange'))),
                    'statistic': fact.get('statistic') or '', 'population': fact.get('population') or ''})
            fields[field] = {'status': status, 'structuredStatus': assessment['status'],
                'textStatuses': statuses, 'reasonCodes': sorted(reasons),
                'observationCount': len(observed), 'textProposalCount': len(mentioned),
                'evidenceCount': len(examples), 'omittedEvidenceCount': max(0, len(examples)-MAX_EXAMPLES),
                'evidence': examples[:MAX_EXAMPLES]}
        result[key(scope)] = {'schemaVersion': VERSION, 'scope': scope,
            'sourceScope': 'ONLY_PINNED_LOADED_INPUTS', 'modelExecutable': False,
            'scientificallyAdopted': False, 'blockingReasons': draft['blockingReasons'], 'fields': fields}
    return result
