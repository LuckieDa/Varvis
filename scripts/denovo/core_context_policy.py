"""Preflight adapter for the existing denovo-phenotype-policy-v1 contract.

This does not create/adopt a policy or replace Java DeNovoApplicabilityPolicy.
The later scientific intake must still load the same pinned policy in Java.
No similarity scores, model-generated categories, or missing-as-negative rules.
"""
import re

from core_local_identity import pinned, require
from model_literature_contract import digest, parse

CATEGORIES = {'HIGHLY_SPECIFIC', 'CONSISTENT', 'CONSISTENT_HIGH_HETEROGENEITY', 'NOT_CONSISTENT'}


def keys(value, expected):
    require(type(value) is dict and set(value) == set(expected.split()), 'POLICY_SHAPE')


def strings(value, maximum):
    require(type(value) is list and len(value) <= maximum and all(type(s) is str and s.strip() for s in value)
            and len(set(value)) == len(value), 'POLICY_STRINGS')
    return set(value)


def terms(value):
    values = strings(value, 100)
    require(all(re.fullmatch('HP:[0-9]{7}', v) for v in values), 'POLICY_HPO')
    return values


def read_policy(root, spec, data_class):
    raw = pinned(root, spec['policy'], 2097152); p = parse(raw)
    fields = 'schemaVersion policyId version materialClass scope allowAssumedParentage expertPolicyStatus sources conditions'
    keys(p, fields + (' literatureTerms' if 'literatureTerms' in p else '')
         + (' specialEvents' if 'specialEvents' in p else ''))
    require(p['schemaVersion'] == 'denovo-phenotype-policy-v1' and
            p['materialClass'] == ('SYNTHETIC' if data_class == 'SYNTHETIC' else 'SOURCE_ASSESSED_RESEARCH'), 'POLICY_USE_MISMATCH')
    require(all(type(p[k]) is str and p[k].strip() for k in ('policyId', 'version')), 'POLICY_ID_REQUIRED')
    keys(p['scope'], 'hgncId diseaseId inheritance')
    require(all(type(v) is str and v for v in p['scope'].values()) and p['scope']['inheritance'] in {'AD', 'AR', 'X_LINKED'}, 'POLICY_SCOPE_UNSUPPORTED')
    require(type(p['allowAssumedParentage']) is bool and p['expertPolicyStatus'] in
            {'GENERAL_ALLOWED', 'APPLICABLE_IMPLEMENTED', 'APPLICABLE_UNRESOLVED'}, 'POLICY_STATUS')
    require(type(p['sources']) is list and 1 <= len(p['sources']) <= 100, 'POLICY_SOURCE_LIMIT')
    ids, sources = set(), {}
    for s in p['sources']:
        keys(s, 'sourceId sha256 locator quote')
        require(all(type(v) is str and v for v in s.values()) and s['sourceId'] not in ids, 'POLICY_SOURCE_INVALID')
        ids.add(s['sourceId']); require(s['sourceId'] in spec['sources'], 'POLICY_SOURCE_MISSING')
        body = pinned(root, spec['sources'][s['sourceId']], 2097152)
        require(digest(body) == s['sha256'] and s['quote'] in body.decode('utf-8'), 'POLICY_SOURCE_PROOF_MISMATCH')
        sources[s['sourceId']] = body
    special = p.get('specialEvents')
    if special is not None:
        # Quantitative case conditions require separately replayed, case-bound
        # measurements. The legacy model/core projection supplies HPO terms.
        require(not isinstance(special, dict) or special.get('mode') != 'SVI_1_1_PUBLISHED_POINTS',
                'PUBLISHED_CASE_SOURCE_NOT_IMPLEMENTED')
        keys(special, 'mode sourceIds')
        require(special['mode'] in {'SVI_1_1', 'SVI_1_1_SECOND_ALLELE_REQUIRED',
                'SVI_1_1_COMBINED_PS2', 'TISSUE_DIFFERENTIAL_PS2', 'RASOPATHY_POINTS', 'SVI_1_1_PHENOTYPE_ONLY'},
                'POLICY_SPECIAL_MODE_UNSUPPORTED')
        refs = strings(special['sourceIds'], 100)
        require(refs and refs <= ids, 'POLICY_SPECIAL_SOURCE')
        require(special['mode'] == 'SVI_1_1' or p['expertPolicyStatus'] == 'APPLICABLE_IMPLEMENTED',
                'POLICY_SPECIAL_EXPERT_REQUIRED')
    require(p['scope']['inheritance'] == 'AD' or special is not None, 'POLICY_SCOPE_UNSUPPORTED')
    categories = CATEGORIES
    if special is not None and special['mode'] in {'RASOPATHY_POINTS', 'SVI_1_1_PHENOTYPE_ONLY'}:
        require(p['scope']['inheritance'] == 'AD', 'POLICY_SCOPE_UNSUPPORTED')
    if special is not None and special['mode'] == 'RASOPATHY_POINTS':
        categories = {'RASOPATHY_COMPATIBLE', 'CONSISTENT', 'NOT_CONSISTENT'}
    require(type(p['conditions']) is list and 1 <= len(p['conditions']) <= 100, 'POLICY_CONDITION_LIMIT')
    condition_ids = set()
    for c in p['conditions']:
        keys(c, 'conditionId category presentAll absentAll sourceIds')
        require(type(c['conditionId']) is str and c['conditionId'] and c['conditionId'] not in condition_ids
                and c['category'] in categories, 'POLICY_CONDITION_INVALID')
        condition_ids.add(c['conditionId'])
        positive, negative = terms(c['presentAll']), terms(c['absentAll'])
        require((positive or negative) and not positive & negative, 'POLICY_CONDITION_CONTRADICTION')
        refs = strings(c['sourceIds'], 100); require(refs and refs <= ids, 'POLICY_CONDITION_SOURCE')
    require(type(p.get('literatureTerms', [])) is list and len(p.get('literatureTerms', [])) <= 1000, 'POLICY_TERM_LIMIT')
    for t in p.get('literatureTerms', []):
        keys(t, 'hpoId aliases sourceIds'); terms([t['hpoId']])
        require(strings(t['aliases'], 30) and strings(t['sourceIds'], 100) and set(t['sourceIds']) <= ids, 'POLICY_TERM_SOURCE')
    return p, raw, sources


def match_features(policy, positive, negative):
    """Exact reviewed aliases only. No unreported HPO term enters either set."""
    def map_terms(features):
        result = set()
        for feature in features:
            ids = {t['hpoId'] for t in policy.get('literatureTerms', []) if
                   any(feature.strip().lower() == a.strip().lower() for a in t['aliases'])}
            require(len(ids) <= 1, 'PHENOTYPE_ALIAS_AMBIGUOUS')
            result.update(ids)
        return result
    present, absent = map_terms(positive), map_terms(negative)
    require(not present & absent, 'PHENOTYPE_SOURCE_CONFLICT')
    matched = [c for c in policy['conditions'] if set(c['presentAll']) <= present and set(c['absentAll']) <= absent]
    require(matched, 'PHENOTYPE_CONDITIONS_NOT_ESTABLISHED')
    require(len({c['category'] for c in matched}) == 1, 'PHENOTYPE_POLICY_CONFLICT')
    return {'category': matched[0]['category'], 'matchedConditions': [c['conditionId'] for c in matched],
            'presentHpo': sorted(present), 'absentHpo': sorted(absent),
            'method': 'EXACT_REVIEWED_ALIAS_AND_POLICY_SET_PREFLIGHT_RECHECK_IN_JAVA_REQUIRED'}


def resolve_context(root, config, identity, doc):
    selected = [s for s in config.get('contexts', []) if s['scope']['hgncId'] == identity['hgncId']]
    require(selected, 'APPLICABILITY_CONTEXT_NOT_CONFIGURED')
    require(len(selected) == 1, 'APPLICABILITY_CONTEXT_SELECTION_AMBIGUOUS')
    spec = selected[0]
    p, raw, sources = read_policy(root, spec, doc['dataClass'])
    require(p['scope'] == spec['scope'], 'POLICY_BACKGROUND_MISMATCH')
    require(p['expertPolicyStatus'] != 'APPLICABLE_UNRESOLVED', 'EXPERT_POLICY_UNRESOLVED')
    # The configured, source-assessed policy is the applicability authority. A
    # ClinGen association alone can never manufacture a phenotype category.
    result = {'scope': p['scope'], 'policyId': p['policyId'], 'policyVersion': p['version'],
              'expertPolicyStatus': p['expertPolicyStatus'], 'allowAssumedParentage': p['allowAssumedParentage'],
              'policySha256': digest(raw), 'policy': p, 'sourcePins': {k: digest(v) for k, v in sources.items()},
              'meaning': 'SELECTED_RESEARCH_BACKGROUND_NOT_A_DIAGNOSIS_OR_CURRENT_TOPN_MATCH',
              'phenotype': None, 'issues': []}
    positive, negative = (doc['groups']['phenotype'][k] for k in ('positive', 'negative'))
    if positive['verification'] != 'VERIFIED' or positive['availability'] != 'REPORTED':
        result['issues'].append('DENOVO_IDENTITY_REVIEWED_PHENOTYPE_UNAVAILABLE')
    elif negative['verification'] == 'CONFLICT':
        result['issues'].append('DENOVO_IDENTITY_PHENOTYPE_SOURCE_CONFLICT')
    else:
        try:
            # Match Java's existing literature entry: absence-dependent policies
            # remain unresolved until that entry supports explicit negatives.
            result['phenotype'] = match_features(p, positive['value'], [])
            if negative['verification'] == 'VERIFIED' and negative['availability'] == 'REPORTED':
                match_features(p, positive['value'], negative['value'])  # contradiction veto only
        except ValueError as error:
            result['phenotype'] = None; result['issues'].append(str(error))
    return result, raw, sources
