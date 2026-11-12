"""Discover candidate papers for PS2/PM6; never qualify a case or fetch full text.

Inputs use pinned ClinVar records, registered public JATS, or a local annotation
signals file. Missing variant aliases produce an explicit gap, never gene fallback. Paper-derived
aliases are search clues, not proof that the paper concerns the selected allele.
"""
import argparse
import html
import json
from pathlib import Path
import re
from copy import deepcopy

from literature_allele_identity import read_record, protein
from prepare_literature import prepare, sha
from select_public_papers import bounded, unique
from literature_search_transport import SearchTransport, decode, PAGE_SIZE, MAX_REQUESTS, require

VERSION = 'denovo-literature-discovery-v1'
MAX_CANDIDATES = 200
GENE = r'[A-Za-z][A-Za-z0-9.-]{0,39}'
TERM = r'[A-Za-z0-9_=*+().:>/-]{1,160}'


def load_json(path, maximum=1048576):
    return json.loads(bounded(path, maximum), object_pairs_hook=unique)


def source_file(root, source):
    require(isinstance(source, dict) and isinstance(source.get('path'), str)
            and not Path(source['path']).is_absolute(), 'SOURCE_PATH')
    path = (root / source['path']).resolve(strict=True)
    require(path.is_relative_to(root), 'SOURCE_PATH')
    raw = bounded(path, 4 * 1024 * 1024)
    require(sha(raw) == source.get('sha256'), 'SOURCE_SHA_MISMATCH')
    return raw


def term(value, pattern=TERM):
    require(isinstance(value, str) and re.fullmatch(pattern, value), 'SIGNAL_INVALID')
    return value


def contains(text, value, ignore_case=True):
    return re.search(r'(?<![A-Za-z0-9_])' + re.escape(value) + r'(?![A-Za-z0-9_])',
                     text, re.I if ignore_case else 0) is not None


def targets_from(request_path):
    request_path = Path(request_path).resolve(strict=True)
    request = load_json(request_path)
    require(isinstance(request, dict) and set(request) == {'schemaVersion', 'targets'}
            and request['schemaVersion'] == 'denovo-literature-discovery-input-v1'
            and isinstance(request['targets'], list) and 1 <= len(request['targets']) <= 50, 'INPUT_SCHEMA')
    targets, ids = [], set()
    for item in request['targets']:
        require(isinstance(item, dict) and set(item) == {'targetId', 'sourceKind', 'source'}, 'TARGET_SCHEMA')
        target_id = term(item['targetId'], r'[A-Za-z0-9_-]{1,64}')
        require(target_id not in ids, 'DUPLICATE_TARGET')
        ids.add(target_id)
        source = item['source']
        raw = source_file(request_path.parent, source)
        aliases, pmids, gene_aliases = [], [], []
        kind = item['sourceKind']
        if kind == 'CLINVAR_RECORD':
            require(set(source) == {'path', 'sha256', 'vcv'}, 'SOURCE_SCHEMA')
            parsed = read_record(raw, source['sha256'], source['vcv'])
            gene = parsed['geneSymbol']
            for pair in parsed['pairedHgvs']:
                aliases.extend([pair['coding'], pair['transcript'] + ':' + pair['coding'], 'p.' + pair['protein']])
            pmids = sorted({row['pmid'] for row in parsed['pubmed']}, key=int)
            scope = 'PUBLIC_VARIANT'
        elif kind == 'PUBLIC_JATS':
            require(set(source) == {'path', 'sha256', 'pmcid', 'geneSymbol', 'variantAliases'}, 'SOURCE_SCHEMA')
            document = prepare(raw, source['pmcid'], source['sha256'])
            gene = term(source['geneSymbol'], GENE)
            require(isinstance(source['variantAliases'], list) and len(source['variantAliases']) <= 16, 'SIGNAL_LIMIT')
            texts = [unit['text'] for unit in document['units']]
            require(any(contains(text, gene, False) for text in texts), 'GENE_NOT_IN_SOURCE')
            for value in source['variantAliases']:
                term(value)
                require(any(contains(text, value) for text in texts), 'ALIAS_NOT_IN_SOURCE')
                aliases.append(value)
            # The source paper's own PMID is NEVER injected as a search seed.
            scope = 'PUBLIC_VARIANT'
        elif kind == 'ANNOTATION_SIGNALS':
            require(set(source) == {'path', 'sha256'}, 'SOURCE_SCHEMA')
            data = json.loads(raw, object_pairs_hook=unique)
            require(isinstance(data, dict) and set(data) == {'schemaVersion', 'geneSymbol', 'geneAliases'}
                    and data['schemaVersion'] == 'denovo-local-gene-signals-v1'
                    and isinstance(data['geneAliases'], list) and len(data['geneAliases']) <= 8, 'ANNOTATION_SCHEMA')
            gene = data['geneSymbol']
            gene_aliases = [term(value, GENE) for value in data['geneAliases']]
            scope = 'GENE_ONLY'
        elif kind == 'SELECTED_VARIANT_SIGNALS':
            require(set(source) == {'path', 'sha256'}, 'SOURCE_SCHEMA')
            data = json.loads(raw, object_pairs_hook=unique)
            require(isinstance(data, dict) and set(data) == {'schemaVersion', 'geneSymbol',
                    'variantAliases', 'citationPmids', 'citationSources', 'issues'} and
                    data['schemaVersion'] == 'denovo-selected-search-signals-v1', 'SELECTED_SIGNAL_SCHEMA')
            gene = data['geneSymbol']
            require(isinstance(data['variantAliases'], list) and len(data['variantAliases']) <= 64
                    and isinstance(data['citationPmids'], list) and len(data['citationPmids']) <= 100
                    and isinstance(data['citationSources'], list) and isinstance(data['issues'], list), 'SIGNAL_LIMIT')
            aliases = [term(v) for v in data['variantAliases']]
            pmids = [term(v, r'[1-9][0-9]*') for v in data['citationPmids']]
            require(not pmids or bool(data['citationSources']), 'CITATION_SOURCE_REQUIRED')
            # Server-derived annotation clues are NOT a declaration that patient data is public.
            scope = 'SELECTED_VARIANT_TERMS'
        else:
            raise ValueError('LITERATURE_SEARCH_SOURCE_KIND_UNSUPPORTED')
        gene = term(gene, GENE)
        aliases = list(dict.fromkeys(term(value) for value in aliases))
        # One/three letter protein spellings are retrieval synonyms, never allele resolution.
        for value in list(aliases):
            if value.startswith('p.'):
                short = 'p.' + protein(value)
                if short != value:
                    aliases.append(term(short))
        require(len(aliases) <= 64 and len(pmids) <= 100, 'SIGNAL_LIMIT')
        targets.append({'targetId': target_id, 'geneSymbol': gene,
                        'geneAliases': list(dict.fromkeys(gene_aliases)), 'variantAliases': list(dict.fromkeys(aliases)),
                        'citationPmids': pmids, 'externalScope': scope, 'sourceKind': kind,
                        'sourceSha256': source['sha256'],
                        'sourceIssues': data['issues'] if kind == 'SELECTED_VARIANT_SIGNALS' else [],
                        'aliasMeaning': 'RETRIEVAL_CLUE_NOT_ALLELE_MATCH'})
    return targets


def make_plan(targets, litvar=True, search_mode='LITVAR_ONLY'):
    require(search_mode == 'LITVAR_ONLY', 'MODE_RETIRED_USE_LITVAR_ONLY')
    from litvar_literature import plans
    return plans(targets)


def clean(value, maximum=100000):
    require(isinstance(value, str) and len(value) <= maximum, 'METADATA_INVALID')
    return html.unescape(re.sub('<[^>]*>', ' ', value)).strip()


def paper(row):
    source, identifier = row.get('source'), row.get('id')
    require(source in {'MED', 'PMC', 'PPR'} and isinstance(identifier, str)
            and re.fullmatch(r'[A-Za-z0-9._-]{1,80}', identifier), 'METADATA_ID_INVALID')
    pmid = identifier if source == 'MED' else row.get('pmid')
    pmcid = row.get('pmcid') or (identifier if source == 'PMC' else None)
    doi = row.get('doi')
    require(pmid is None or isinstance(pmid, str) and re.fullmatch(r'[1-9][0-9]*', pmid), 'METADATA_ID_INVALID')
    require(pmcid is None or isinstance(pmcid, str) and re.fullmatch(r'PMC[1-9][0-9]*', pmcid), 'METADATA_ID_INVALID')
    require(doi is None or isinstance(doi, str) and re.fullmatch(r'10\.[0-9]{4,9}/\S{1,200}', doi), 'METADATA_ID_INVALID')
    title = clean(row.get('title', ''), 4096)
    require(bool(title), 'METADATA_TITLE_REQUIRED')
    type_list = row.get('pubTypeList', {})
    require(isinstance(type_list, dict), 'METADATA_INVALID')
    types = type_list.get('pubType', [])
    require(isinstance(types, list) and all(isinstance(t, str) for t in types), 'METADATA_INVALID')
    flags = []
    if source == 'PPR' or any('preprint' in t.lower() for t in types): flags.append('PREPRINT_REQUIRES_REVIEW')
    if any('review' in t.lower() for t in types): flags.append('REVIEW_SOURCE_NOT_PRIMARY_CASE_PROOF')
    if row.get('isRetracted') == 'Y' or any('retract' in t.lower() for t in types): flags.append('RETRACTION_REQUIRES_REVIEW')
    return {'source': source, 'sourceId': identifier, 'pmid': pmid, 'pmcid': pmcid,
            'doi': doi.lower() if doi else None, 'title': title, 'abstract': clean(row.get('abstractText', '')),
            'publicationTypes': types, 'publicationYear': row.get('pubYear'),
            'openAccess': row.get('isOpenAccess', 'UNKNOWN'), 'hasSupplement': row.get('hasSuppl', 'UNKNOWN'),
            'flags': flags, 'qualification': 'DISCOVERY_CANDIDATE_ONLY', 'matches': [], 'origins': []}


def keys(candidate):
    return {kind + ':' + candidate[kind] for kind in ('pmid', 'pmcid', 'doi') if candidate.get(kind)} | {
        candidate['source'] + ':' + candidate['sourceId']}


def merge_candidate(candidates, candidate):
    matches = [c for c in candidates if keys(c) & keys(candidate)]
    if not matches:
        if len(candidates) >= MAX_CANDIDATES:
            return False
        candidates.append(candidate)
        return True
    conflicts = any(len({c.get(kind) for c in [candidate, *matches] if c.get(kind)}) > 1
                    for kind in ('pmid', 'pmcid', 'doi'))
    if conflicts:
        for c in [candidate, *matches]:
            if 'BIBLIOGRAPHIC_IDENTITY_CONFLICT' not in c['flags']:
                c['flags'].append('BIBLIOGRAPHIC_IDENTITY_CONFLICT')
        if len(candidates) >= MAX_CANDIDATES:
            return False
        candidates.append(candidate)
        return True
    primary = matches[0]
    for other in [*matches[1:], candidate]:
        for kind in ('pmid', 'pmcid', 'doi'):
            if not primary[kind]: primary[kind] = other[kind]
        for field in ('matches', 'origins', 'flags'):
            for value in other[field]:
                if value not in primary[field]: primary[field].append(value)
        if other in candidates and other is not primary:
            candidates.remove(other)
    return True


def add_matches(candidate, query, targets):
    text = candidate['title'] + '\n' + candidate['abstract']
    for target in targets:
        if target['targetId'] not in query['targetIds']: continue
        candidate['matches'].append({'targetId': target['targetId'], 'tier': query['tier'],
            'priority': query['priority'], 'queryId': query['queryId'],
            'metadataGeneMentions': [v for v in [target['geneSymbol'], *target['geneAliases']] if contains(text, v)],
            'metadataVariantMentions': [v for v in target['variantAliases'] if contains(text, v)],
            'alleleIdentityVerified': False})
        if query['tier'] == 'LITVAR_PUBLICATIONS':
            links = litvar_links(candidate, query)
            candidate['matches'][-1]['litvarLinks'] = links
            ids = list(dict.fromkeys(v['litvarVariantId'] for v in links))
            candidate['matches'][-1]['litvarVariantIds'] = ids
            if len(ids) == 1: candidate['matches'][-1]['litvarVariantId'] = ids[0]


def litvar_links(candidate, query):
    links = []
    for reference in query.get('referenceLinks', []):
        if candidate['pmid' if reference['source'] == 'MED' else 'pmcid'] != reference['id']: continue
        for link in reference['variantLinks']:
            if link not in links: links.append(link)
    return links


def discover(targets, transport, local=(), max_pages=None, precision_first=False, litvar=False,
             search_mode='LITVAR_ONLY', checkpoint=None):
    max_pages = MAX_REQUESTS if max_pages is None else max_pages
    require(type(max_pages) is int and 1 <= max_pages <= MAX_REQUESTS, 'PAGE_BUDGET_INVALID')
    plans = make_plan(targets, True, search_mode)
    candidates = list(local)
    deferred = deepcopy(checkpoint.get('deferredCandidates', [])) if checkpoint else []
    while deferred and len(candidates) < MAX_CANDIDATES:
        merge_candidate(candidates, deferred.pop(0))
    def new_query(p):
        return {**p, 'pagesRead': 0, 'rowsRead': 0, 'hitCount': None,
                'status': 'PENDING', 'cursor': '*', 'seenCursors': [], 'seenResultIds': [], 'issues': []}
    target_pin = sha(json.dumps(targets, sort_keys=True, separators=(',', ':')).encode())
    if checkpoint is not None:
        require(checkpoint.get('targetSha256') == target_pin and checkpoint.get('searchMode') == search_mode
                and checkpoint.get('precisionFirst') == precision_first, 'CHECKPOINT_IDENTITY_MISMATCH')
        queries = deepcopy(checkpoint['queries'])
        require(all(q.get('tier') in {'LITVAR_VARIANT', 'LITVAR_PUBLICATIONS'} for q in queries),
                'CHECKPOINT_RETIRED_QUERY_TIER')
    else:
        queries = [new_query(p) for p in plans]
    queue = [q for q in queries if not q.get('executionComplete')]
    prior_hits = set(checkpoint.get('preciseTargetIds', [])) if checkpoint else set()
    for query in queries:
        if query['omittedTerms']: query['issues'].append('LITERATURE_SEARCH_TERMS_LIMIT')
    pages = 0
    while queue:
        query = queue.pop(0)
        if pages >= max_pages or candidates and len(candidates) + PAGE_SIZE > MAX_CANDIDATES:
            for pending in [query, *queue]:
                pending['status'] = 'PARTIAL'
                pending['issues'].append('LITERATURE_SEARCH_BATCH_LIMIT')
            break
        try:
            if query['tier'] == 'LITVAR_VARIANT':
                from litvar_literature import resolve, metadata_plans
                query['issues'] = []
                references = resolve(query, transport)
                known = {q['queryId'] for q in queries}
                requested = {(r['source'], r['id']) for q in queries if q.get('parentQueryId') == query['queryId']
                             for r in q.get('requestedReferences', [])}
                children = [new_query(p) for p in metadata_plans(query, [r for r in references if r not in requested])
                            if p['queryId'] not in known]
                queries.extend(children)
                queue.extend(children)
                query['status'] = 'PARTIAL' if query['issues'] else 'COMPLETE'
                query['executionComplete'] = query.get('resolutionComplete', False)
                continue
            # A resumed batch retries only unfinished work; completed pages and
            # previous candidates remain in the immutable parent result.
            query['issues'] = [i for i in query['issues'] if i not in {
                'LITERATURE_SEARCH_BATCH_LIMIT', 'LITERATURE_SEARCH_REQUEST_BUDGET',
                'LITERATURE_SEARCH_TOTAL_TIMEOUT', 'LITERATURE_SEARCH_TIMEOUT',
                'LITERATURE_SEARCH_NETWORK_DISABLED', 'LITERATURE_SEARCH_RATE_LIMIT',
                'LITERATURE_SEARCH_RATE_LIMIT_COOLDOWN'}]
            raw = transport.get(query['query'], query['cursor'])
            data = decode(raw)
            pages += 1
            query['pagesRead'] += 1
            query['seenCursors'].append(query['cursor'])
            rows = data['resultList']['result']
            if query['hitCount'] is not None and query['hitCount'] != data['hitCount']:
                query['issues'].append('LITERATURE_SEARCH_RESULTS_CHANGED_DURING_PAGING')
            query['hitCount'] = data['hitCount']
            for row in rows:
                query['rowsRead'] += 1
                try:
                    candidate = paper(row)
                    record_key = candidate['source'] + ':' + candidate['sourceId']
                    if record_key in query['seenResultIds']:
                        query['issues'].append('LITERATURE_SEARCH_REPEATED_RESULT')
                    else:
                        query['seenResultIds'].append(record_key)
                    if query['tier'] == 'LITVAR_PUBLICATIONS':
                        requested = query['requestedReferences']
                        matches = [r for r in requested if candidate['pmid' if r['source'] == 'MED' else 'pmcid'] == r['id']]
                        require(bool(matches), 'LITVAR_PUBLICATION_IDENTITY_CONFLICT')
                        query.setdefault('matchedReferences', []).extend(r for r in matches
                            if r not in query.get('matchedReferences', []))
                        for link in litvar_links(candidate, query):
                            candidate['origins'].append({'kind': 'LITVAR_PUBLICATIONS',
                                'sha256': link['publicationResponseSha256'], **link,
                                'queryId': query['parentQueryId']})
                    candidate['origins'].append({'kind': 'SEARCH_RESPONSE', 'sha256': sha(raw), 'queryId': query['queryId']})
                    effective = {**query, 'targetIds': query.get('executedTargetIds', query['targetIds'])}
                    add_matches(candidate, effective, targets)
                    if not merge_candidate(candidates, candidate):
                        deferred.append(candidate)
                        query['issues'].append('LITERATURE_SEARCH_CANDIDATE_LIMIT')
                except ValueError as error:
                    query['issues'].append(str(error))
            more = query['rowsRead'] < data['hitCount']
            cursor = data.get('nextCursorMark')
            if more and rows and cursor and cursor not in query['seenCursors']:
                query['cursor'] = cursor
                query['status'] = 'PARTIAL'
                queue.append(query)
            elif more:
                query['status'] = 'PARTIAL'
                query['issues'].append('LITERATURE_SEARCH_PAGINATION_INCOMPLETE')
            else:
                if query['tier'] == 'LITVAR_PUBLICATIONS':
                    missing = [r for r in query['requestedReferences'] if r not in query.get('matchedReferences', [])]
                    if missing:
                        query['referencesWithoutMetadata'] = missing
                        query['issues'].append('LITERATURE_SEARCH_LITVAR_METADATA_MISSING')
                query['status'] = 'COMPLETE' if not query['issues'] else 'PARTIAL'
                query['executionComplete'] = True
        except (ValueError, OSError) as error:
            code = str(error)
            query['issues'].append(code if code.startswith('LITERATURE_SEARCH_') else 'LITERATURE_SEARCH_FAILED')
            query['status'] = 'PARTIAL' if query['pagesRead'] else 'UNAVAILABLE'
    candidates.sort(key=lambda c: (min(m['priority'] for m in c['matches']),
                                   bool(c['flags']), c['pmid'] or c['pmcid'] or c['sourceId']))
    summaries = []
    for target in targets:
        relevant = [q for q in queries if target['targetId'] in q['targetIds']]
        count = sum(any(m['targetId'] == target['targetId'] for m in c['matches']) for c in candidates)
        def completed_for_target(q):
            return q['status'] == 'COMPLETE'
        complete = all(completed_for_target(q) for q in relevant)
        missing_clues = search_mode == 'LITVAR_ONLY' and not relevant
        if missing_clues: complete = False
        summaries.append({'targetId': target['targetId'], 'candidateCount': count,
                          'status': 'CANDIDATES_FOUND' if count else 'NO_HITS_IN_EXECUTED_QUERIES' if complete else 'SEARCH_INCOMPLETE',
                          'plannedQueriesComplete': complete,
                          'geneSymbol': target['geneSymbol'],
                          'fallbackReason': None,
                          'issues': sorted({issue for q in relevant if not completed_for_target(q) for issue in q['issues']} |
                                           set(target.get('sourceIssues', [])) |
                                           ({'LITERATURE_SEARCH_VARIANT_CLUES_UNAVAILABLE'} if missing_clues else set()))})
    result = {'schemaVersion': VERSION, 'status': 'DISCOVERY_ONLY_NOT_QUALIFIED', 'targets': summaries,
            'queries': queries, 'candidates': candidates, 'networkRequests': transport.requests,
            'retrievals': transport.events, 'completeLiteratureSearch': False,
            'searchPolicy': 'LITVAR_ONLY',
            'litvarEnabled': True, 'searchMode': search_mode,
            'fullTextDownloads': 0, 'modelCalls': 0, 'businessPublishable': False,
            'limits': {'queryPages': max_pages, 'uniqueCandidates': MAX_CANDIDATES},
            'matchingMeaning': 'RETRIEVAL_RELEVANCE_ONLY_NOT_VARIANT_OR_CASE_IDENTITY'}
    result['checkpoint'] = {'targetSha256': target_pin, 'searchMode': search_mode, 'precisionFirst': precision_first,
                            'queries': queries, 'deferredCandidates': deferred, 'preciseTargetIds': sorted(prior_hits | {
                                m['targetId'] for c in candidates for m in c['matches'] if m['priority'] < 2})}
    result['resumeAvailable'] = bool(deferred) or any(not q.get('executionComplete') and q['status'] not in {
        'SKIPPED_PRECISE_HIT', 'BLOCKED_PRECISE_INCOMPLETE'} for q in queries)
    return result


def implementation_pin():
    return {name: sha((Path(__file__).parent / name).read_bytes()) for name in (
        'discover_public_literature.py', 'literature_search_transport.py', 'literature_allele_identity.py',
        'prepare_literature.py', 'select_public_papers.py', 'fetch_public_literature.py', 'litvar_literature.py')}


def run(request, output, cache, library=None, online=False, max_pages=MAX_REQUESTS, precision_first=False, litvar=False,
        search_mode='LITVAR_ONLY', checkpoint=None):
    require(type(max_pages) is int and 1 <= max_pages <= MAX_REQUESTS, 'PAGE_BUDGET_INVALID')
    targets = targets_from(request)
    local = []
    output = Path(output)
    output.mkdir(exist_ok=False)
    plan = {'schemaVersion': VERSION, 'inputSha256': sha(Path(request).read_bytes()), 'targets': targets,
            'queries': make_plan(targets, litvar, search_mode), 'publicNetworkEnabled': online, 'maxQueryPages': max_pages,
            'litvarEnabled': True,
            'implementation': implementation_pin()}
    (output / 'plan.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding='utf-8')
    if checkpoint:
        require(checkpoint.get('implementation') == plan['implementation'], 'CHECKPOINT_IMPLEMENTATION_CHANGED')
    with SearchTransport(cache, output / 'requests', online) as transport:
        result = discover(targets, transport, local, max_pages, precision_first, litvar, search_mode, checkpoint)
    result['checkpoint']['implementation'] = plan['implementation']
    result['inputSha256'] = plan['inputSha256']
    result['implementation'] = plan['implementation']
    (output / 'discovery.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('request', 'output', 'cache'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--library', type=Path)
    parser.add_argument('--allow-public-network', action='store_true')
    parser.add_argument('--max-pages', type=int, default=MAX_REQUESTS)
    parser.add_argument('--litvar', action='store_true', help='Include bounded LitVar variant-to-paper discovery')
    parser.add_argument('--search-mode', choices=['LITVAR_ONLY'], default='LITVAR_ONLY')
    parser.add_argument('--checkpoint', type=Path)
    args = parser.parse_args()
    checkpoint = load_json(args.checkpoint, 64 * 1024 * 1024) if args.checkpoint else None
    result = run(args.request, args.output, args.cache, args.library, args.allow_public_network, args.max_pages,
                 litvar=args.litvar or args.search_mode == 'LITVAR_ONLY', search_mode=args.search_mode, checkpoint=checkpoint)
    print(json.dumps({'status': result['status'], 'targets': result['targets'],
                      'candidates': len(result['candidates']), 'networkRequests': result['networkRequests'], 'modelCalls': 0}))
