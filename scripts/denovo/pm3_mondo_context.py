"""Pinned OLS MONDO labels, exact synonyms and direct disease subclass edges.

This is not an equivalence map. Broader/related synonyms, non-is-a navigation,
obsolete terms, incomplete pages and arbitrary ancestor traversal are excluded.
The disease consumer still requires its qualified exact gene/AR relationship.
"""
import re
from copy import deepcopy
from urllib.parse import urlparse, parse_qs, quote
from core_local_identity import pinned
from model_literature_contract import parse, digest

BASE = 'https://www.ebi.ac.uk/ols4/api/ontologies/mondo'
IRI = 'http://purl.obolibrary.org/obo/'


def require(ok, reason):
    if not ok:
        raise ValueError('PM3_MONDO_' + reason)


def normal(value):
    return ' '.join(value.split()).casefold()


def term_valid(term):
    ident = term.get('obo_id','')
    return (re.fullmatch(r'MONDO:[0-9]{7}',ident) is not None
        and term.get('iri') == IRI + ident.replace(':','_')
        and term.get('ontology_name') == 'mondo'
        and term.get('is_defining_ontology') is True
        and term.get('is_obsolete') is False and term.get('term_replaced_by') is None
        and isinstance(term.get('label'),str) and bool(term['label'].strip()))


def page_terms(value):
    terms = value.get('_embedded',{}).get('terms',[])
    page = value.get('page',{})
    require(isinstance(terms,list) and 0 < len(terms) <= 32
            and page.get('number') == 0 and page.get('totalPages') == 1
            and page.get('totalElements') == len(terms), 'INCOMPLETE_TERM_PAGE')
    require(all(term_valid(t) for t in terms), 'TERM_IDENTITY_INVALID')
    return terms


class DirectSubclassContext:
    def __init__(self, root, config):
        require(config.get('policy') == 'EXPLICIT_DIRECT_MONDO_SUBCLASS_SAME_GENE_AR_V1', 'POLICY_INVALID')
        entries = config.get('entries')
        require(isinstance(entries,list) and 0 < len(entries) <= 256, 'ENTRY_LIMIT')
        self.matches = {}; seen = set()
        for entry in entries:
            spec = entry['term']; url = urlparse(spec.get('sourceUrl',''))
            require(url.scheme == 'https' and url.netloc == 'www.ebi.ac.uk'
                and url.path == '/ols4/api/ontologies/mondo/terms'
                and set(parse_qs(url.query)) == {'obo_id'}, 'SOURCE_URL_INVALID')
            raw = pinned(root, spec, 1024*1024)
            terms = page_terms(parse(raw)); require(len(terms) == 1, 'EXACT_TERM_REQUIRED')
            term = terms[0]; ident = term['obo_id']
            require(ident not in seen, 'DUPLICATE_TERM'); seen.add(ident)
            require(parse_qs(url.query)['obo_id'] == [ident], 'QUERY_TERM_MISMATCH')
            parents_spec = entry['parents']
            expected = term.get('_links',{}).get('parents',{}).get('href','').replace('http://','https://',1)
            require(expected == BASE+'/terms/'+quote(quote(term['iri'],safe=''),safe='')+'/parents'
                and parents_spec.get('sourceUrl') == expected, 'DIRECT_PARENTS_URL_MISMATCH')
            parents_raw = pinned(root, parents_spec, 1024*1024)
            parents = page_terms(parse(parents_raw))
            require(ident not in {p['obo_id'] for p in parents}, 'SELF_PARENT')
            metadata_spec = entry['metadata']
            require(metadata_spec.get('sourceUrl') == BASE, 'METADATA_URL_INVALID')
            metadata_raw = pinned(root,metadata_spec,65536); metadata = parse(metadata_raw)
            version = metadata.get('version','')
            require(metadata.get('ontologyId') == 'mondo' and metadata.get('status') == 'LOADED'
                and re.fullmatch(r'\d{4}-\d{2}-\d{2}',version)
                and metadata.get('config',{}).get('version') == version
                and metadata.get('config',{}).get('versionIri') ==
                    f'http://purl.obolibrary.org/obo/mondo/releases/{version}/mondo-international.owl',
                'METADATA_BINDING_INVALID')
            proof = {'method':'PINNED_OLS_MONDO_EXACT_NAME_AND_DIRECT_SUBCLASS',
                'reportedDiseaseId':ident, 'reportedDiseaseLabel':term['label'],
                'parentDiseaseIds':sorted({p['obo_id'] for p in parents}),
                'relationship':'rdfs:subClassOf', 'equivalent':False,
                'source':{'term':deepcopy(spec),'parents':deepcopy(parents_spec),
                          'metadata':deepcopy(metadata_spec),'reportedOntologyVersion':version,
                          'versionMeaning':'OLS_METADATA_REPORTED_VERSION_NOT_FULL_ONTOLOGY_REHASH'},
                'termRecord':term, 'parentRecords':parents,
                'parentPageSha256':digest(parents_raw)}
            names = {normal(ident),normal(term['label'])}
            names.update(normal(s['name']) for s in term.get('obo_synonym',[])
                         if s.get('scope') == 'hasExactSynonym' and isinstance(s.get('name'),str))
            for name in names:
                self.matches.setdefault(name,[]).append(proof)

    def lookup(self, value):
        return deepcopy(self.matches.get(normal(value),[]))
