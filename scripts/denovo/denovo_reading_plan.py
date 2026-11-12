"""Need-driven PS2/PM6 target selection; no transport, fact adoption or scoring.

Inputs belong to the local service: a freshly checked core feedback package,
source loaded by bundle_model_contract, public model configuration and optional
checkpoint. Text matches rank candidates; they never prove case ownership.
"""
import re
from pathlib import Path

from model_literature_contract import canonical, digest, parse, canonical_case_label, CASE_TITLE
from bundle_model_contract import load, requests, require
from bundle_model_transport import cached, cache_identity
from model_transport import bounded
from bundle_replay_compatibility import reviewed_profiles
from run_bundle_model import identity
from denovo_reading_feedback import from_package, VERSION as FEEDBACK_VERSION, HINTS
from single_case_scope import units as single_case_units
from literature_allele_identity import protein

VERSION = 'denovo-need-reading-plan-v1'
PROFILE = 'core-material-v2'
READABLE = {'LITERATURE_READING', 'SOURCE_REVIEW', 'CONFLICT_REVIEW'}
PATTERNS = {
    'CASE': r'\b(case|patient|proband|individual|family|pedigree)\b',
    'IDENTITY': r'\b(?:NM_|NP_|ENST)[0-9]+|\b[cp]\.|transcript|reference sequence',
    'TESTING': r'\b(sanger|sequenc\w*|genotyp\w*|test\w*|detect\w*|negative|absent|heterozyg\w*)\b',
    'PARENTAGE': r'\b(parentage|paternity|maternity|SNP|STR|microsatellite|relationship|nonpaternity)\b',
    'PHENOTYPE': r'\b(clinical|phenotyp\w*|symptom\w*|diagnos\w*|affected|unaffected|healthy)\b',
    'FAMILY': r'\b(family|families|pedigree|parents?|mother|father|maternal|paternal|unaffected|healthy|recurrence|sibling\w*)\b',
    'EVENT': r'\b(unrelated|cohort|recruit\w*|previously reported|reported previously|independent|overlap)\b',
    'CONDITIONAL': r'\b(mosaic\w*|phase|phasing|recurrence|sibling\w*|coverage|allele fraction)\b',
}
LABEL = re.compile(r'\b(?i:Case|Patient|Individual|Proband)\s+([A-Za-z0-9][A-Za-z0-9_.-]{0,63})(?![\w.-])')
META = ('/ref-list[', '/ack[', '/permissions[', '/author-notes[')


def alias_patterns(values):
    aliases = set()
    for value in values:
        value = value.split(':')[-1]
        # No genomic/transcript conversion: only the caller's known spellings.
        aliases.add(value)
        if value.startswith('p.'):
            short = protein(value)
            if short:
                aliases.update((short, 'p.' + short))
    return [re.compile(r'(?<![\w.])' + r'\s*'.join(re.escape(c) for c in v) + r'(?![\w])', re.I)
            for v in sorted(aliases) if v]


def scope_hint(units, label, owned):
    labels = set()
    for unit in units:
        labels.update(m.rstrip('.') for m in LABEL.findall(unit['text']))
        for section in unit.get('sections', []):
            match = CASE_TITLE.fullmatch(section)
            if match:
                labels.add(match[1])
    direct = label in labels or any(u['unitId'] in owned for u in units)
    # A bare identifier is only a row hint, never a proven subject link.
    row_hint = any(u['kind'] in ('TABLE_CELL', 'SUPPLEMENT_CELL') and u['text'].strip() == label for u in units)
    if labels and not direct and not row_hint:
        return 'OTHER_CASE_ONLY'
    if direct or row_hint:
        return 'TARGET_CASE_HINT' if not labels - {label} else 'MIXED_CASES_REVIEW_REQUIRED'
    return 'UNRESOLVED_SCOPE'


def _history(history, scope, chunks):
    if history is None:
        return {}
    require(history.get('scope') == scope and history.get('schemaVersion') == 'denovo-reading-checkpoint-v1',
            'READING_CHECKPOINT_SCOPE_CHANGED')
    entries = history.get('entries')
    require(isinstance(entries, list) and len(entries) <= len(chunks), 'READING_CHECKPOINT_INVALID')
    found = {}
    for entry in entries:
        index = entry.get('chunkIndex')
        require(type(index) is int and 0 <= index < len(chunks) and index not in found, 'READING_CHECKPOINT_INVALID')
        require(entry.get('requestSha256') == chunks[index]['requestSha256']
                and entry.get('status') in {'REVIEWED', 'FAILED', 'INCOMPLETE'}
                and re.fullmatch(r'[a-f0-9]{64}', entry.get('artifactSha256', '')) is not None,
                'READING_CHECKPOINT_BINDING_INVALID')
        found[index] = entry
    return found


def plan(source, feedback_package, case_key, target, config, *, history=None, cache_dir=None, limit=1, profile=PROFILE):
    """Read-only plan over trusted service inputs. Context units never count as read.

    Checkpoint entries must come from the service's verified artifacts (task3),
    not model/client flags. Cached receipts are checked but still require the
    original offline semantic replay before being usable as evidence.
    """
    require(type(limit) is int and 1 <= limit <= 64, 'READING_LIMIT_INVALID')
    require(feedback_package.get('schemaVersion') == 'denovo-reading-feedback-package-v1'
            and feedback_package.get('pmcid') == source['pmcid']
            and feedback_package.get('bundleSha256') == source['bundleSha256'], 'READING_SOURCE_CHANGED')
    matches = [c for c in feedback_package['cases'] if c['bindings']['caseKey'] == case_key]
    require(len(matches) == 1 and matches[0]['schemaVersion'] == FEEDBACK_VERSION, 'READING_CASE_REQUIRED')
    feedback = matches[0]
    require(case_key.startswith(source['pmcid'] + '#'), 'READING_CASE_REQUIRED')
    label = canonical_case_label(case_key.split('#', 1)[1])
    require(bool(label) and len(label) <= 80, 'READING_CASE_REQUIRED')
    pins = feedback['bindings']['sourcePins'].values()
    require(any(p['role'] == 'PAPER_PRIMARY' and p['sha256'] == source['main']['sourceSha256'] for p in pins),
            'READING_PRIMARY_SOURCE_CHANGED')
    require(all(isinstance(target.get(k), str) and 0 < len(target[k]) <= 256
                for k in ('hgncId', 'diseaseId', 'inheritance', 'geneSymbol')), 'READING_TARGET_REQUIRED')
    allele = target.get('allele')
    require(isinstance(allele, dict) and set(allele) == {'assembly','contig','position','reference','alternate'}
            and type(allele['position']) is int and allele['position'] > 0
            and all(isinstance(allele[k], str) and 0 < len(allele[k]) <= 10000
                    for k in ('assembly','contig','reference','alternate')), 'READING_ALLELE_REQUIRED')
    for key in ('allele', 'hgncId', 'diseaseId', 'inheritance'):
        known = feedback['context'].get('/context/' + key)
        require(known is None or known == target[key], 'READING_TARGET_CHANGED')
    aliases = target.get('variantAliases')
    require(isinstance(aliases, list) and len(aliases) <= 64
            and all(isinstance(a, str) and 0 < len(a) <= 256 for a in aliases), 'READING_ALIASES_INVALID')
    needs = feedback['needs']
    require(isinstance(needs, list) and len(needs) <= 128
            and all(n['caseKey'] == case_key and n['group'] in HINTS for n in needs), 'READING_NEEDS_INVALID')
    active = [n for n in needs if n['route'] in READABLE and n['group'] in PATTERNS]
    deferred = [n['needId'] for n in needs if n not in active]
    require(profile in ('core-material-v1','core-material-v2'), 'READING_PROFILE_INVALID')
    packets = requests(source, config['model'], config['maxOutputTokens'], config['maxRequestBytes'], profile)
    patterns = alias_patterns(aliases)
    owned = {u['unitId'] for u in single_case_units(source['main'], label)}
    chunks = []
    for index, packet in enumerate(packets):
        content = parse(packet['messages'][1]['content'])
        ids = content['workUnit']['targetUnitIds']
        units = [u for u in content['units'] if u['unitId'] in ids]
        text = '\n'.join(u['text'] for u in units)
        # Only target text/target section can establish a ranking hint. Neighbour
        # patients and shared context remain attached in the original request.
        scope = scope_hint(units, label, owned)
        groups = {g for g, regex in PATTERNS.items() if re.search(regex, text, re.I)}
        variant = any(p.search(text) for p in patterns)
        relevant = [n for n in active if n['group'] in groups]
        metadata = all(any(m in uid for m in META) for uid in ids)
        row = content['workUnit']['kind'] in {'MAIN_TABLE_ROW', 'SUPPLEMENT_ROW'}
        reason = ('NO_LITERATURE_NEED' if not active else 'OTHER_CASE_ONLY' if scope == 'OTHER_CASE_ONLY'
                  else 'METADATA_NOT_CASE_DATA' if metadata else 'NO_NEED_MATCH' if not relevant
                  else 'UNLINKED_TABLE_ROW' if row and scope == 'UNRESOLVED_SCOPE' and not variant else None)
        if reason is None and not content['workWithinLimit']:
            reason = 'TARGET_WORK_LIMIT_NOT_TRUNCATED'
        chunks.append({'chunkIndex': index, 'requestSha256': digest(canonical(packet)),
            'targetUnitIds': list(ids), 'contextUnitIds': content['workUnit']['contextUnitIds'],
            'scopeHint': scope, 'variantMention': variant, 'needIds': [n['needId'] for n in relevant],
            'fieldPaths': sorted({n['fieldPath'] for n in relevant}), 'contentGroups': sorted(groups),
            'state': 'PENDING' if reason is None else 'DEFERRED', 'reasonCode': reason,
            'cacheState': 'NOT_CHECKED', 'rankMeaning': 'HEURISTIC_NOT_SUBJECT_OR_FACT_PROOF'})
    scope = {'version': VERSION, 'caseKey': case_key, 'target': target,
             'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
             'requestSetSha256': digest(canonical([c['requestSha256'] for c in chunks]))}
    previous = _history(history, scope, chunks)
    # Completed/failed/incomplete work never becomes an automatic new paid call.
    for index, entry in previous.items():
        chunks[index].update(state=entry['status'], reasonCode='CHECKPOINT_' + entry['status'],
                             artifactSha256=entry['artifactSha256'])
    profiles = reviewed_profiles() if cache_dir is not None else []
    base_binding = identity(source['pmcid'], source['bundleSha256'], 0, len(packets), profile) if cache_dir is not None and packets else None
    for chunk in chunks:
        if chunk['state'] != 'PENDING' or cache_dir is None:
            continue
        index = chunk['chunkIndex']
        binding = {**base_binding, 'chunkIndex': index}
        bindings = [binding] + [{**binding, 'implementation': p} for p in profiles]
        try:
            found = next((value for b in bindings if (value := cached(config, packets[index], cache_dir, b)) is not None), None)
            chunk['cacheState'] = 'REPLAY_REQUIRED' if found else 'MISS'
            if found:
                chunk['cachedReceiptSha256'] = digest(canonical(found[1]))
            elif (Path(cache_dir) / 'budget.json').is_file():
                # Failed/reserved calls often have no success receipt. Do not
                # reinterpret these as ordinary cache misses eligible for retry.
                ledger = parse(bounded(Path(cache_dir) / 'budget.json', 262144))
                keys = {cache_identity(config, packets[index], b)[1] for b in bindings}
                if any(e.get('key') in keys for e in ledger['calls']):
                    chunk.update(state='HELD', cacheState='RECORDED_CALL_UNRESOLVED',
                                 reasonCode='CACHE_RECORDED_CALL_REQUIRES_RECOVERY')
        except (ValueError, OSError, KeyError, TypeError):
            chunk.update(state='HELD', cacheState='INVALID', reasonCode='CACHE_REVALIDATION_REQUIRED_NO_PAID_FALLBACK')
    pending = [c for c in chunks if c['state'] == 'PENDING']
    # Relevant cache is consumed first, then same-case + exact missing-field
    # relevance. No positive/negative polarity or source-count bonus.
    pending.sort(key=lambda c: (c['cacheState'] != 'REPLAY_REQUIRED',
        c['scopeHint'] != 'TARGET_CASE_HINT', -len(c['fieldPaths']), not c['variantMention'], c['chunkIndex']))
    selected = [c['chunkIndex'] for c in pending[:limit]]
    selection_state = ('READY' if selected else 'NO_READABLE_NEED' if not active
        else 'RECOVERY_REQUIRED' if any(c['state'] in {'HELD','FAILED','INCOMPLETE'} for c in chunks)
        else 'WORK_LIMIT_UNRESOLVED' if any(c['reasonCode'] == 'TARGET_WORK_LIMIT_NOT_TRUNCATED' for c in chunks)
        else 'NO_PENDING_RELEVANT_TARGET')
    return {'schemaVersion': VERSION, 'scope': scope, 'feedbackSha256': digest(canonical(feedback_package)),
            'bindings': feedback['bindings'], 'chunks': chunks, 'selectedChunks': selected,
            'orderedPendingChunks': [c['chunkIndex'] for c in pending],
            'deferredNeedIds': deferred, 'coverageLimitations': source['coverage'],
            'pendingAllChunks': [c['chunkIndex'] for c in chunks if c['state'] != 'REVIEWED'],
            'reviewedChunks': [c['chunkIndex'] for c in chunks if c['state'] == 'REVIEWED'],
            'selectionState': selection_state,
            'absenceProven': False, 'scientificExecution': False, 'businessPublishable': False,
            'modelCalls': 0, 'networkCalls': 0,
            'scopeStatement': '只选择已有资料中的相关目标块；未选、失败、未解析均不等于原文无资料。缓存必须按原流程复核，选择不授予病例事实或证据。'}


def prepare(library, package, index_sha256, case_key, target, config, **options):
    """Production-compatible offline entry; regenerates feedback and source units."""
    feedback = from_package(package, index_sha256)
    source = load(Path(library), feedback['pmcid'], feedback['bundleSha256'])
    return plan(source, feedback, case_key, target, config, **options)
