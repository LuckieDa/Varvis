"""Source-bound reading priorities, never a source fact or scientific verdict.

Only target units count towards coverage. Context mentions may prioritize a
packet, but reading a packet does not mark every context unit as reviewed.
Unselected units remain visible; heuristic exclusion never means no evidence.
"""
import re
from model_literature_contract import parse
from bundle_model_contract import require
from pm3_chunk_priority import order as initial_order

VERSION = 'pm3-need-reading-plan-v2'
PATTERNS = {
    'CASE': r'\b(proband|patient|case|family|families|unrelated|previously reported|pedigree)\b',
    'GENOTYPE': r'\b(homozyg\w*|heterozyg\w*|allele\w*|genotyp\w*|sanger|segregat\w*)\b',
    'IDENTITY': r'\b(?:NM_|NP_|ENST)[0-9]+|\b(?:c|p)\.[A-Za-z0-9_*+>\-]+|transcript|reference sequence',
    'PHASE': r'\b(trans|cis|phase|phasing|paternal|maternal|father|mother|parents?|segregat\w*|pedigree)\b',
    'DISEASE': r'\b(diagnos\w*|phenotyp\w*|symptom\w*|affected|unaffected|recessive|clinical)\b',
    'PARTNER_EVIDENCE': r'\b(pathogenic|classification|functional|previously reported|comparison|evidence)\b',
    'CONFLICT': r'\b(not|negative|absent|unaffected|conflict\w*|inconsistent|correction|erratum|retract\w*)\b',
}
GROUPS = frozenset(PATTERNS) | {'EXTERNAL_RESOURCE', 'UNSUPPORTED'}
METADATA = ('/ref-list[', '/ack[', '/permissions[', '/author-notes[')


def plan(requests, target, *, pmcid, bundle_pin, feedback=None):
    """Feedback is server-owned scheduling only; it cannot assert completion."""
    feedback = feedback or {}
    require(not feedback or (feedback.get('pmcid') == pmcid
            and feedback.get('bundleSha256') == bundle_pin), 'PM3_READING_SOURCE_CHANGED')
    needs = feedback.get('needs', [])
    require(isinstance(needs, list) and len(needs) <= 64, 'PM3_READING_NEEDS_LIMIT')
    require(all(isinstance(n, dict) and n.get('group') in GROUPS
                and isinstance(n.get('reasonCode'), str)
                and re.fullmatch(r'[A-Z][A-Z0-9_]{1,160}', n['reasonCode']) for n in needs),
            'PM3_READING_NEED_INVALID')
    active = {n['group'] for n in needs}
    require(all(isinstance(n.get('sourceUnitIds', []), list) and len(n.get('sourceUnitIds', [])) <= 64
                and all(isinstance(u, str) and len(u) <= 1024 for u in n.get('sourceUnitIds', [])) for n in needs),
            'PM3_READING_SOURCE_UNIT_INVALID')
    source_ids = {u for n in needs for u in n.get('sourceUnitIds', [])}
    # A known case's partner allele may be in the adjacent cell without repeating
    # the target variant. Promote only that exact row, never all rows in its table.
    source_rows = {m.group(1) for u in source_ids if (m := re.match(r'(.*/tr\[[0-9]+\])/(?:td|th)\[', u))}
    baseline = initial_order(requests, target)
    base_rank = {index: rank for rank, index in enumerate(baseline)}
    aliases = {re.sub(r'\s+', '', v.split(':')[-1]).casefold() for v in target['variantAliases']}
    from literature_allele_identity import protein
    for value in tuple(aliases):
        if value.startswith('p.'):
            short = protein(value)
            if short: aliases.update((short.casefold(), 'p.' + short.casefold()))
    # Boundaries prevent c.1A>G matching c.11A>G; normalization is only ranking.
    alias_patterns = [re.compile(r'(?<![a-z0-9])' + re.escape(v) + r'(?![a-z0-9])') for v in aliases if v]
    chunks = []
    for index, request in enumerate(requests):
        content = parse(request['messages'][1]['content'])
        target_ids = set(content['workUnit']['targetUnitIds'])
        units = [u for u in content['units'] if u['unitId'] in target_ids]
        text = '\n'.join(u['text'] for u in units)
        compact = re.sub(r'\s+', '', text).casefold()
        hits = sorted(group for group, pattern in PATTERNS.items() if re.search(pattern, text, re.I))
        direct = any(p.search(compact) for p in alias_patterns)
        case_link = bool(target_ids & source_ids) or any(
            any(u.startswith(row + '/') for row in source_rows) for u in target_ids)
        metadata = bool(units) and all(any(m in u['unitId'] for m in METADATA) for u in units)
        table_row = bool(units) and all(u.get('kind') in ('TABLE_CELL','SUPPLEMENT_CELL','SUPPLEMENT_PDF_CELL') for u in units)
        # Broad clinical/genotype scope deliberately includes possible contrary
        # facts; match quality is assessed downstream, not by these keywords.
        reasons = (['TARGET_VARIANT_MENTION'] if direct else [])
        if case_link: reasons.append('CURRENT_CASE_SOURCE_UNIT')
        if not metadata and not table_row:
            reasons += ['CLINICAL_OR_GENOTYPE_CONTEXT'] if set(hits) & {'CASE', 'GENOTYPE', 'PHASE', 'DISEASE'} else []
            if active.intersection(hits): reasons.append('MATCHES_CURRENT_NEED')
        # "Patient" in every row of a literature review is not a link to our
        # case. Unmatched rows stay visible as unselected, not reviewed negatives.
        selected = bool(reasons) and (not metadata or direct or case_link)
        chunks.append({'chunkIndex': index, 'targetUnitIds': sorted(target_ids),
                       'selected': selected, 'selectionReasons': reasons if selected else ['NOT_SELECTED_BY_HEURISTIC'],
                       'needMatches': sorted(active.intersection(hits)), 'contentGroups': hits})
    selected = [c for c in chunks if c['selected']]
    selected.sort(key=lambda c: ('CURRENT_CASE_SOURCE_UNIT' not in c['selectionReasons'], -len(c['needMatches']), base_rank[c['chunkIndex']]))
    return {'schemaVersion': VERSION, 'pmcid': pmcid, 'bundleSha256': bundle_pin,
            'scope': 'HEURISTIC_RELEVANT_TARGET_UNITS_NOT_FULL_PAPER',
            'absenceProven': False, 'needs': needs, 'chunks': chunks,
            'orderedRelevantChunks': [c['chunkIndex'] for c in selected],
            'unselectedChunks': [c['chunkIndex'] for c in chunks if not c['selected']]}


def coverage(reading_plan, completed):
    """Completion comes from actual accepted review artifacts, never feedback."""
    accepted = set(completed)
    total = len(reading_plan['chunks'])
    require(all(type(i) is int and 0 <= i < total for i in accepted), 'PM3_READING_COVERAGE_INVALID')
    return {**reading_plan, 'completedReviewChunks': sorted(accepted),
            'pendingRelevantChunks': [i for i in reading_plan['orderedRelevantChunks'] if i not in accepted],
            'pendingAllChunks': [i for i in range(total) if i not in accepted]}
