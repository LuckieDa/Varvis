"""LitVar 2 retrieval clues, never genomic identity or criterion-level evidence.

Resolve all sourced aliases, preserving ambiguity and original response pins.
PMID and PMCID arrays are independent sets, not pairwise mappings.
"""
import ast
import json
import re
from urllib.parse import quote, urlencode

from literature_allele_identity import protein
from literature_search_transport import require
from select_public_papers import unique
from prepare_literature import sha

ROOT = 'https://www.ncbi.nlm.nih.gov/research/litvar2-api/variant/'
LIMIT = 100  # Official autocomplete maximum, not an assertion of completeness.
# Official autocomplete also returns gene-qualified names without an rsID or
# trailing ##. These are retrieval identifiers, not verified genomic alleles.
# Official gene inventories include uncertain HGVS (e.g. p.M1?). These are
# opaque retrieval IDs, not permission to treat uncertainty as allele identity.
IDENTIFIER = r'litvar@(?:[^\s\x00-\x1f\x7f]{1,190}##|#[1-9][0-9]{0,19}#[A-Za-z0-9_=*+?().:>/\[\]\-]{1,160})'
GENE_IDENTIFIER = r'litvar@#[1-9][0-9]{0,19}#'
GENE = r'[A-Za-z][A-Za-z0-9.-]{0,39}'
TERM = r'[A-Za-z0-9_=*+().:>/\-]{1,160}'
STABLE = r'(?:rs[1-9][0-9]{0,19}|CA[1-9][0-9]{0,19}|[A-Z]{2}_[0-9]+\.[0-9]+:[0-9]+:(?:[ACGT]*|[0-9]+):[ACGT]*)'


def url_for(operation, value):
    require(isinstance(value, str), 'LITVAR_QUERY_INVALID')
    if operation == 'autocomplete':
        require(re.fullmatch(GENE + ' ' + TERM, value) or re.fullmatch(STABLE, value)
                or re.fullmatch(r'(?:[A-Z]{2}_[0-9]+\.[0-9]+:)?[pcgnmr]\.' + TERM, value),
                'LITVAR_QUERY_INVALID')
        return ROOT + 'autocomplete/?' + urlencode({'query': value, 'limit': LIMIT})
    if operation == 'gene':
        require(re.fullmatch(GENE, value), 'LITVAR_GENE_INVALID')
        return ROOT + 'search/gene/' + quote(value, safe='')
    if operation == 'sensor':
        require(re.fullmatch(r'rs[1-9][0-9]{0,19}', value), 'LITVAR_ID_INVALID')
        return ROOT.removesuffix('variant/') + 'sensor/' + value
    require(operation in {'summary', 'publications'} and re.fullmatch(IDENTIFIER, value), 'LITVAR_ID_INVALID')
    require(not re.search(r'\?[^#]*=', value), 'LITVAR_ID_INVALID')
    return ROOT + 'get/' + quote(value, safe='') + ('/publications' if operation == 'publications' else '')


def variant_row(row, inventory=False):
    require(isinstance(row, dict) and isinstance(row.get('_id'), str)
            and (re.fullmatch(IDENTIFIER, row['_id']) or inventory and re.fullmatch(GENE_IDENTIFIER, row['_id'])),
            'LITVAR_INVALID_RESPONSE')
    require(row.get('rsid') is None or isinstance(row['rsid'], str)
            and re.fullmatch(r'rs[1-9][0-9]{0,19}', row['rsid']), 'LITVAR_INVALID_RESPONSE')
    if inventory:
        require(type(row.get('pmids_count')) is int and row['pmids_count'] >= 0, 'LITVAR_INVALID_RESPONSE')
    else:
        require(isinstance(row.get('gene'), list) and len(row['gene']) <= 100
                and all(isinstance(g, str) and re.fullmatch(GENE, g) for g in row['gene'])
                and all(isinstance(row.get(k, ''), str) and len(row.get(k, '')) <= 512 for k in ('name', 'hgvs')),
                'LITVAR_INVALID_RESPONSE')
    if 'clingen_ids' in row:
        require(isinstance(row['clingen_ids'], list) and len(row['clingen_ids']) <= 10000
                and all(isinstance(v, str) and re.fullmatch(r'CA[1-9][0-9]*', v) for v in row['clingen_ids']),
                'LITVAR_INVALID_RESPONSE')


def gene_stream(raw):
    # Official gene endpoint currently returns one Python-style flat dictionary
    # per line. Parse a narrow literal grammar, NEVER eval server text or calls.
    rows = []
    for line in raw.decode('utf-8').splitlines():
        if not line.strip(): continue
        require(len(line) <= 8192 and len(rows) < 100000, 'LITVAR_BODY_LIMIT')
        tree = ast.parse(line, mode='eval').body
        require(isinstance(tree, ast.Dict) and len(tree.keys) <= 32, 'LITVAR_INVALID_RESPONSE')
        keys, values = [], []
        for key, value in zip(tree.keys, tree.values):
            require(isinstance(key, ast.Constant) and isinstance(key.value, str)
                    and isinstance(value, ast.Constant) and type(value.value) in {str, int, bool, type(None)},
                    'LITVAR_INVALID_RESPONSE')
            keys.append(key.value); values.append(value.value)
        require(len(set(keys)) == len(keys), 'LITVAR_INVALID_RESPONSE')
        rows.append(dict(zip(keys, values)))
    return rows


def decode(raw, operation):
    require(isinstance(raw, bytes) and len(raw) <= 2 * 1024 * 1024, 'LITVAR_BODY_LIMIT')
    try:
        try:
            data = json.loads(raw, object_pairs_hook=unique)
        except json.JSONDecodeError:
            if operation != 'gene': raise
            data = gene_stream(raw)
    except (ValueError, UnicodeError, SyntaxError, RecursionError) as error:
        raise ValueError('LITERATURE_SEARCH_LITVAR_INVALID_RESPONSE') from error
    if operation in {'autocomplete', 'gene'}:
        require(isinstance(data, list) and len(data) <= (LIMIT if operation == 'autocomplete' else 100000), 'LITVAR_INVALID_RESPONSE')
        for index, row in enumerate(data):
            # Autocomplete may omit gene annotations. Keep the clue; summary
            # must establish the requested gene before publications are used.
            if operation == 'autocomplete' and isinstance(row, dict) and 'gene' not in row:
                require(bool(row.get('name') or row.get('hgvs')), 'LITVAR_INVALID_RESPONSE')
                row = dict(row, gene=[])
                data[index] = row
            variant_row(row, operation == 'gene')
    elif operation == 'summary':
        if isinstance(data, dict) and 'gene' not in data:
            require(bool(data.get('name') or data.get('hgvs')), 'LITVAR_INVALID_RESPONSE')
            data = dict(data, gene=[])
        variant_row(data)
        require(data.get('concept', 'variant') == 'variant', 'LITVAR_INVALID_RESPONSE')
    elif operation == 'sensor':
        require(isinstance(data, dict), 'LITVAR_INVALID_RESPONSE')
        if data:
            require(isinstance(data.get('rsid'), str) and re.fullmatch(r'rs[1-9][0-9]*', data['rsid'])
                    and type(data.get('pmids_count')) is int and data['pmids_count'] >= 0
                    and isinstance(data.get('link'), str)
                    and data['link'].startswith('https://www.ncbi.nlm.nih.gov/research/litvar2/docsum?')
                    and len(data['link']) <= 4096 and not any(ord(c) < 32 for c in data['link']), 'LITVAR_INVALID_RESPONSE')
    else:
        require(operation == 'publications' and isinstance(data, dict), 'LITVAR_INVALID_RESPONSE')
        # No PMC links is represented by an absent optional field by LitVar.
        # Explicit null/malformed links and missing or inconsistent PMID counts
        # are still rejected; do not derive PMCID/PMID pairings.
        if 'pmcids' not in data:
            data['pmcids'] = []
        for field, pattern in [('pmids', r'[1-9][0-9]{0,11}'), ('pmcids', r'PMC[1-9][0-9]{0,11}')]:
            values = data.get(field)
            require(isinstance(values, list) and len(values) <= 100000 and all(
                (isinstance(v, str) or field == 'pmids' and type(v) is int)
                and re.fullmatch(pattern, str(v)) for v in values), 'LITVAR_INVALID_RESPONSE')
        count = data.get('pmids_count')
        require(type(count) is int and count == len(set(str(p) for p in data['pmids'])), 'LITVAR_INCOMPLETE_RESPONSE')
    return data


def inspect(operation, value, transport):
    """All five API operations through the shared bounded/cache transport."""
    url_for(operation, value)
    raw = transport.get_litvar(operation, value)
    data = decode(raw, operation)
    if operation == 'summary':
        require(data['_id'] == value, 'LITVAR_SUMMARY_ID_CONFLICT')
    if operation == 'sensor' and data:
        require(data['rsid'] == value, 'LITVAR_SENSOR_ID_CONFLICT')
    return data, sha(raw)


def empty_response(data, operation):
    if operation in {'autocomplete', 'gene', 'sensor'}: return not data
    if operation == 'publications': return not (data['pmids'] or data['pmcids'])
    return False


def normalized(value):
    # Protein spelling equivalence only. Never discard a transcript version or
    # infer that equal residue changes imply the same nucleotide allele.
    if value.startswith('p.'):
        return 'p.' + protein(value)
    return value


def plans(targets):
    merged = {}
    for target in targets:
        aliases = target['variantAliases']
        if not aliases:
            continue
        ordered = sorted(dict.fromkeys(aliases), key=lambda a: (
            0 if re.fullmatch(STABLE, a) else 1 if ':' in a else 2 if a.startswith('c.') else 3, a))
        names = list(dict.fromkeys(normalized(a) for a in ordered))
        require(len(names) <= 64, 'LITVAR_ALIAS_LIMIT')
        identity = json.dumps([target['geneSymbol'], names], separators=(',', ':'))
        if identity in merged:
            merged[identity]['targetIds'].append(target['targetId'])
        else:
            merged[identity] = {'queryId': sha(('litvar-v2:' + identity).encode()),
                'tier': 'LITVAR_VARIANT', 'priority': 1, 'query': search_text(target['geneSymbol'], names[0]),
                'gene': target['geneSymbol'], 'aliases': names, 'targetIds': [target['targetId']],
                'omittedTerms': [], 'unqueriedAliases': list(names)}
    return list(merged.values())


def search_text(gene, alias):
    return alias if re.fullmatch(STABLE, alias) else gene + ' ' + alias


def relation(row, gene, aliases):
    if not row['gene']: return 'UNRESOLVED_GENE_MAPPING'
    if gene not in row['gene']: return 'GENE_CONFLICT'
    names = {normalized(row.get(k, '')) for k in ('name', 'hgvs')}
    identifiers = {row.get('rsid'), *row.get('clingen_ids', [])}
    if set(aliases) & identifiers: return 'IDENTIFIER_MATCH_ONLY'
    if set(aliases) & names: return 'GENE_ALIAS_MATCH_ONLY'
    # An explicit alternate substitution at the SAME named residue is not an
    # alternate transcript spelling. Different numbering remains unresolved.
    for alias in aliases:
        a = re.fullmatch(r'p\.([A-Z*])([0-9]+)([A-Z*])', alias)
        for name in names:
            b = re.fullmatch(r'p\.([A-Z*])([0-9]+)([A-Z*])', name)
            if a and b and a.group(1, 2) == b.group(1, 2) and a[3] != b[3]:
                return 'SUBSTITUTION_CONFLICT'
    return 'UNRESOLVED_NAME_MAPPING'


def resolve(query, transport):
    audit, entries, links, visited = [], {}, {}, set()
    query.update(resolution=audit, variantCandidates=[], referenceLinks=[],
                 unqueriedAliases=list(query['aliases']), litvarVariantIds=[])
    def issue(code):
        if code not in query['issues']: query['issues'].append(code)
    try:
        for alias in query['aliases']:
            text = search_text(query['gene'], alias)
            rows, pin = inspect('autocomplete', text, transport)
            query['unqueriedAliases'].remove(alias)
            audit.append({'query': text, 'sha256': pin, 'returnedIds': [r['_id'] for r in rows],
                          'matchMeaning': 'RETRIEVAL_ONLY_NOT_ALLELE_IDENTITY'})
            if len(rows) == LIMIT: issue('LITERATURE_SEARCH_LITVAR_AUTOCOMPLETE_LIMIT')
            for row in rows:
                identifier = row['_id']
                if identifier in visited: continue
                visited.add(identifier)
                pre = relation(row, query['gene'], query['aliases'])
                item = {'litvarVariantId': identifier, 'name': row.get('name'), 'gene': row['gene'],
                        'discoveryRelation': pre, 'alleleIdentityVerified': False,
                        'autocompleteSha256': pin, 'publicationStatus': 'PENDING'}
                entries[identifier] = item
                if pre in {'GENE_CONFLICT', 'SUBSTITUTION_CONFLICT'}:
                    item['publicationStatus'] = 'EXCLUDED_IDENTITY_CONFLICT'
                    issue('LITERATURE_SEARCH_LITVAR_' + pre)
                    continue
                try:
                    summary, summary_pin = inspect('summary', identifier, transport)
                    require(bool(summary['gene']), 'LITVAR_SUMMARY_GENE_MISSING')
                    require(query['gene'] in summary['gene'], 'LITVAR_SUMMARY_GENE_CONFLICT')
                except ValueError as error:
                    if str(error) not in {'LITERATURE_SEARCH_LITVAR_SUMMARY_ID_CONFLICT',
                                          'LITERATURE_SEARCH_LITVAR_SUMMARY_GENE_MISSING',
                                          'LITERATURE_SEARCH_LITVAR_SUMMARY_GENE_CONFLICT'}:
                        raise
                    item['publicationStatus'] = ('UNRESOLVED_GENE' if str(error).endswith('GENE_MISSING')
                                                 else 'EXCLUDED_IDENTITY_CONFLICT')
                    issue(str(error))
                    continue
                current = relation(summary, query['gene'], query['aliases'])
                item.update(summarySha256=summary_pin, name=summary.get('name'),
                            discoveryRelation=current, rsid=summary.get('rsid'),
                            clingenIds=summary.get('clingen_ids', []))
                if current == 'SUBSTITUTION_CONFLICT':
                    item['publicationStatus'] = 'EXCLUDED_IDENTITY_CONFLICT'
                    issue('LITERATURE_SEARCH_LITVAR_SUBSTITUTION_CONFLICT')
                    continue
                if current == 'UNRESOLVED_NAME_MAPPING': issue('LITERATURE_SEARCH_LITVAR_NAME_MAPPING_UNRESOLVED')
                data, publication_pin = inspect('publications', identifier, transport)
                item.update(publicationStatus='COMPLETE', publicationResponseSha256=publication_pin)
                origin = {k: item[k] for k in ('litvarVariantId', 'summarySha256', 'publicationResponseSha256',
                                              'discoveryRelation', 'alleleIdentityVerified')}
                # Independent arrays: never manufacture PMID/PMCID pairings.
                for source, values in [('MED', data['pmids']), ('PMC', data['pmcids'])]:
                    for value in values:
                        key = (source, str(value))
                        if origin not in links.setdefault(key, []): links[key].append(origin)
    except (ValueError, OSError) as error:
        code = str(error)
        issue(code if code.startswith('LITERATURE_SEARCH_') else 'LITERATURE_SEARCH_LITVAR_FAILED')
    query['variantCandidates'] = list(entries.values())
    query['litvarVariantIds'] = [v['litvarVariantId'] for v in entries.values() if v['publicationStatus'] == 'COMPLETE']
    query['litvarVariantId'] = query['litvarVariantIds'][0] if len(query['litvarVariantIds']) == 1 else None
    if len(query['litvarVariantIds']) == 1:
        query['publicationResponseSha256'] = entries[query['litvarVariantId']]['publicationResponseSha256']
    refs = sorted(links, key=lambda r: (r[0] != 'MED', -int(r[1]) if r[0] == 'MED' else r[1]))
    query['referenceLinks'] = [dict(source=s, id=p, variantLinks=links[(s, p)]) for s, p in refs]
    query['pmids'] = [p for s, p in refs if s == 'MED']
    query['pmcids'] = [p for s, p in refs if s == 'PMC']
    query['linkedReferenceCount'] = len(refs)
    # Preserve the entire linked-ID set. Downstream metadata queries are paged
    # by the batch scheduler; ordering is deterministic, not LitVar relevance.
    query['omittedReferences'] = []
    query['resolutionComplete'] = not query['unqueriedAliases'] and all(
        v['publicationStatus'] != 'PENDING' for v in entries.values())
    if not refs and not query['issues']: query['emptyReason'] = 'NO_MATCH_IN_EXECUTED_LITVAR_QUERIES'
    return refs


def metadata_plans(parent, references):
    results = []
    for i in range(0, len(references), 20):
        chunk = references[i:i + 20]
        expression = '(' + ' OR '.join('(PMCID:' + identifier + ')' if source == 'PMC'
                                       else '(EXT_ID:' + identifier + ' AND SRC:' + source + ')'
                                       for source, identifier in chunk) + ')'
        results.append({'queryId': sha((parent['queryId'] + expression).encode()), 'tier': 'LITVAR_PUBLICATIONS',
            'priority': 1, 'query': expression, 'targetIds': parent['targetIds'], 'omittedTerms': [],
            'requestedReferences': [dict(source=s, id=p) for s, p in chunk],
            'parentQueryId': parent['queryId'], 'litvarVariantId': parent.get('litvarVariantId'),
            'referenceLinks': [r for r in parent['referenceLinks'] if (r['source'], r['id']) in chunk]})
    return results


if __name__ == '__main__':
    import argparse
    from pathlib import Path
    from literature_search_transport import SearchTransport
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operation', required=True, choices=['autocomplete', 'summary', 'publications', 'gene', 'sensor'])
    parser.add_argument('--value', required=True)
    parser.add_argument('--cache', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--allow-public-network', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    with SearchTransport(args.cache, args.output / 'requests', args.allow_public_network) as transport:
        result, pin = inspect(args.operation, args.value, transport)
        report = {'operation': args.operation, 'query': args.value, 'sha256': pin, 'data': result,
                  'networkRequests': transport.requests, 'retrievals': transport.events,
                  'alleleIdentityVerified': False, 'businessPublishable': False}
        (args.output / 'inspection.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'operation': args.operation, 'sha256': pin, 'networkRequests': report['networkRequests']}))
