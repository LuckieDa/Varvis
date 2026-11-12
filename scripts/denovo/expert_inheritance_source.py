"""Exact published expert context declarations, never variant evidence adoption.

Join ERepo's structured context to a released CSpec scope and its HGNC/NCBI
identity and panel. Classification, met codes, narrative and coordinates are
deliberately not consumed. A captured page is positive provenance, not proof
that the entire database contains no alternatives.
"""
from datetime import date
import re
from urllib.parse import urlsplit, parse_qs
from core_local_identity import pinned
from model_literature_contract import parse
from pm3_specification_context import scopes
from prepare_applicability_sources import released_current

MODES = {'Autosomal dominant inheritance': 'AD', 'Autosomal recessive inheritance': 'AR',
         'X-linked dominant inheritance': 'XLD', 'X-linked recessive inheritance': 'XLR',
         'Mitochondrial inheritance': 'MT'}


def panel(value):
    if not isinstance(value, str): return ''
    return ' '.join(value.replace('Variant Curation Expert Panel', 'VCEP').split()).casefold()


def declarations(document, descriptor, specifications):
    url = urlsplit(descriptor['sourceUrl'])
    query = parse_qs(url.query)
    if (url.scheme != 'https' or url.netloc not in {'erepo.genome.network', 'erepo.clinicalgenome.org'}
            or url.path != '/evrepo/api/summary/classifications' or url.fragment
            or query.get('columns') != ['gene'] or query.get('matchTypes') != ['exact']
            or len(query.get('values', [])) != 1):
        raise ValueError('EXPERT_INHERITANCE_SOURCE_URL_INVALID')
    rows = document.get('data')
    if not isinstance(rows, list) or len(rows) > 1000 or any(not isinstance(r, dict) for r in rows):
        raise ValueError('EXPERT_INHERITANCE_SOURCE_SCHEMA_INVALID')
    result, seen = [], set()
    for spec in specifications:
        original = spec['original']
        if not released_current(original): continue
        authority = panel(original.get('entContent', {}).get('shortTitle', ''))
        identities = [n.get('entContent', {}).get('HGNC', {}) for n in original.get('ld', {}).get('Gene', [])]
        for scope in scopes(original):
            if scope['scopeStatus'] not in {'EXACT', 'INHERITANCE_NOT_PROVIDED', 'INHERITANCE_NOT_MAPPED'}: continue
            identity = [g for g in identities if g.get('hgnc_id') == scope['scope']['hgncId']
                        and g.get('symbol') == scope['geneSymbol'] and g.get('status') == 'Approved']
            if len(identity) != 1 or not str(identity[0].get('entrez_id', '')).isdigit(): continue
            for index, row in enumerate(rows):
                if (row.get('gene') != scope['geneSymbol'] or row.get('gene') != query['values'][0]
                        or str(row.get('geneNcbiId')) != str(identity[0]['entrez_id'])
                        or row.get('mondoId') != scope['scope']['mondoId'] or not authority
                        or not isinstance(row.get('ep'), str) or panel(row['ep']) != authority
                        or row.get('retracted') is not False or row.get('moi') not in MODES
                        or not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', row.get('uuid', ''))
                        or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', row.get('docVersion', ''))): continue
                try:
                    date.fromisoformat(row['approvedDate']); date.fromisoformat(row['publishedDate'])
                except (KeyError, ValueError, TypeError): continue
                key = (spec['specificationKey'], scope['scope']['hgncId'], row['mondoId'], row['moi'])
                if key in seen: continue
                seen.add(key)
                result.append({'hgncId': scope['scope']['hgncId'], 'geneSymbol': scope['geneSymbol'],
                    'mondoId': scope['scope']['mondoId'], 'mode': MODES[row['moi']],
                    'specificationKey': spec['specificationKey'], 'scopePointer': scope['scopePointer'],
                    'source': {**descriptor, 'jsonPointer': f'/data/{index}', 'uuid': row['uuid'],
                               'documentVersion': row['docVersion'], 'panel': row['ep'], 'declaredMode': row['moi']},
                    'specificationSource': spec['source']})
    return result


def supply(root, descriptors, specifications, contexts):
    if not isinstance(descriptors, list) or len(descriptors) > 128:
        raise ValueError('EXPERT_INHERITANCE_SOURCE_LIMIT')
    records, errors = [], []
    for descriptor in descriptors:
        try:
            records.extend(declarations(parse(pinned(root, descriptor, 4*1024*1024)), descriptor, specifications))
        except (ValueError, OSError, KeyError, TypeError):
            gene = descriptor.get('geneSymbol') if isinstance(descriptor, dict) else None
            if not gene and isinstance(descriptor, dict):
                try: gene = parse_qs(urlsplit(descriptor.get('sourceUrl', '')).query).get('values', [None])[0]
                except (ValueError, TypeError): gene = None
            errors.append({'reasonCode': 'EXPERT_INHERITANCE_SOURCE_UNAVAILABLE_OR_INVALID',
                           'sourceSha256': descriptor.get('sha256') if isinstance(descriptor, dict) else None,
                           'geneSymbol': gene})
    for context in contexts:
        matches = [r for r in records if all(r[k] == context[k] for k in ('hgncId', 'geneSymbol', 'mondoId'))]
        if matches:
            context['publishedInheritance'] = sorted(set(context['publishedInheritance']) | {r['mode'] for r in matches})
            context['sources'].append({'kind': 'EXACT_PUBLISHED_EXPERT_CONTEXT', 'matches': matches,
                'inheritanceDeclaration': {'modes': sorted({r['mode'] for r in matches}), 'status': 'VERIFIED'},
                'meaning': 'ANALYSIS_BACKGROUND_ONLY_NO_CLASSIFICATION_OR_CRITERION_ADOPTION',
                'completeDatabaseCoverage': False})
        relevant_errors = [e for e in errors if not e['geneSymbol'] or e['geneSymbol'] == context['geneSymbol']]
        context['expertInheritanceSupply'] = {'status': 'VERIFIED' if matches else 'UNAVAILABLE' if relevant_errors else 'MISSING',
            'matchCount': len(matches), 'sourceIssues': relevant_errors, 'scientificEvidenceAwarded': False}
