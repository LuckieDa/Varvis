"""Pinned Mondo SSSOM exact mappings. No fuzzy, broader/narrower or gene inference."""
import csv
import io
import re
from core_local_identity import pinned
from model_literature_contract import digest

SET_ID='http://purl.obolibrary.org/obo/mondo/mappings/mondo_exactmatch_orphanet.sssom.tsv'
URL='https://raw.githubusercontent.com/monarch-initiative/mondo/master/src/ontology/mappings/mondo_exactmatch_orphanet.sssom.tsv'


def normalize(value):
    return ' '.join(value.split()).casefold()


class ExactDiseaseNames:
    def __init__(self,root,spec):
        raw=pinned(root,spec,8*1024*1024)
        text=raw.decode('utf-8-sig'); lines=text.splitlines()
        metadata=dict(line[2:].split(': ',1) for line in lines if line.startswith('# ') and ': ' in line[2:])
        version=metadata.get('mapping_set_version','')
        if (spec.get('sourceUrl')!=URL or metadata.get('mapping_set_id')!=SET_ID
                or not re.fullmatch(r'http://purl.obolibrary.org/obo/mondo/releases/\d{4}-\d{2}-\d{2}/mappings/mondo_exactmatch_orphanet.sssom.tsv',version)
                or spec.get('version')!=version):raise ValueError('PM3_DISEASE_MAPPING_SOURCE_BINDING_INVALID')
        self.identity={'sha256':digest(raw),'sourceUrl':URL,'version':version,'method':'MONDO_SSSOM_EXACT_MAPPING'}
        self.names={}
        for line,row in enumerate(csv.DictReader(io.StringIO('\n'.join(l for l in lines if not l.startswith('#'))),delimiter='\t'),1):
            if line>30000:raise ValueError('PM3_DISEASE_MAPPING_ROW_LIMIT')
            if (row.get('predicate_id')!='skos:exactMatch' or row.get('mapping_justification')!='semapv:ManualMappingCuration'
                    or not re.fullmatch(r'MONDO:[0-9]{7}',row.get('subject_id',''))
                    or not re.fullmatch(r'Orphanet:[1-9][0-9]*',row.get('object_id',''))):continue
            proof={'recordNumber':line,'source':self.identity,'mapping':row}
            for k in ('subject_id','subject_label','object_id','object_label'):
                if row.get(k):self.names.setdefault(normalize(row[k]),[]).append(proof)

    def lookup(self,value):
        return self.names.get(normalize(value),[])


class ExactMondoTerms:
    """Reuse pinned OLS term records without requiring a parent/subclass route.

    Only the term ID, label and explicitly exact synonyms are equivalences.
    Xrefs, related synonyms and parent labels cannot silently become aliases.
    """
    def __init__(self, root, spec):
        from urllib.parse import urlparse, parse_qs
        from model_literature_contract import parse
        from pm3_mondo_context import BASE, page_terms, require
        entries = spec.get('entries')
        require(isinstance(entries, list) and 0 < len(entries) <= 256, 'ENTRY_LIMIT')
        self.names = {}; seen = set()
        for entry in entries:
            term_spec, metadata_spec = entry['term'], entry['metadata']
            url = urlparse(term_spec.get('sourceUrl', ''))
            require(url.scheme == 'https' and url.netloc == 'www.ebi.ac.uk'
                    and url.path == '/ols4/api/ontologies/mondo/terms'
                    and set(parse_qs(url.query)) == {'obo_id'}, 'SOURCE_URL_INVALID')
            raw = pinned(root, term_spec, 1024*1024)
            terms = page_terms(parse(raw)); require(len(terms) == 1, 'EXACT_TERM_REQUIRED')
            term = terms[0]; ident = term['obo_id']
            require(parse_qs(url.query)['obo_id'] == [ident], 'QUERY_TERM_MISMATCH')
            require(ident not in seen, 'DUPLICATE_TERM'); seen.add(ident)
            require(metadata_spec.get('sourceUrl') == BASE, 'METADATA_URL_INVALID')
            metadata_raw = pinned(root, metadata_spec, 65536); metadata = parse(metadata_raw)
            version = metadata.get('version', '')
            require(metadata.get('ontologyId') == 'mondo' and metadata.get('status') == 'LOADED'
                    and re.fullmatch(r'\d{4}-\d{2}-\d{2}', version)
                    and metadata.get('config', {}).get('version') == version
                    and metadata.get('config', {}).get('versionIri') ==
                    f'http://purl.obolibrary.org/obo/mondo/releases/{version}/mondo-international.owl',
                    'METADATA_BINDING_INVALID')
            source = {'sha256': digest(raw), 'sourceUrl': term_spec['sourceUrl'],
                      'metadataSha256': digest(metadata_raw), 'version': version,
                      'method': 'PINNED_OLS_MONDO_EXACT_TERM',
                      'versionMeaning': 'OLS_METADATA_REPORTED_VERSION_NOT_FULL_ONTOLOGY_REHASH'}
            names = [(ident, 'TERM_ID'), (term['label'], 'PREFERRED_LABEL')]
            names += [(s['name'], 'EXACT_SYNONYM') for s in term.get('obo_synonym', [])
                      if s.get('scope') == 'hasExactSynonym' and isinstance(s.get('name'), str) and s['name'].strip()]
            for name, kind in names:
                proof = {'source': source, 'nameKind': kind,
                         'mapping': {'subject_id': ident, 'subject_label': term['label'],
                                     'object_label': name, 'predicate_id': 'skos:exactMatch'}}
                self.names.setdefault(normalize(name), []).append(proof)

    def lookup(self, value):
        from copy import deepcopy
        return deepcopy(self.names.get(normalize(value), []))


class CombinedDiseaseNames:
    def __init__(self, sources):
        self.sources = sources

    def lookup(self, value):
        return [p for source in self.sources for p in source.lookup(value)]
