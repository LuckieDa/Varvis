"""Join existing pinned CSpec originals and G2P materials; no scientific adoption.

The original capture supplies expert clauses, not a permission to run a rule.
All clauses, publication notes and attachments remain available in their source
graph. Exact gene/disease/MOI joins are separate from unknown inheritance and
transcript mentions. No transcript mention or MANE association grants relevance.
"""
from __future__ import annotations
import argparse
from collections import Counter
import copy
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from assemble_materials import pinned, parse, require, child, content_hash, write_new
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'acmg-generalization'))
from import_cspec_catalog import digest, released_current

VERSION = '1.0.0'
MOI = {'Autosomal dominant inheritance':'AUTOSOMAL_DOMINANT', 'Autosomal Dominant':'AUTOSOMAL_DOMINANT',
       'Autosomal recessive inheritance':'AUTOSOMAL_RECESSIVE', 'Autosomal Recessive':'AUTOSOMAL_RECESSIVE'}


def pointer(doc, path):
    require(isinstance(path, str) and path.startswith('/'), 'SOURCE_POINTER_INVALID')
    for token in path[1:].split('/'):
        token = token.replace('~1', '/').replace('~0', '~')
        doc = doc[int(token)] if isinstance(doc, list) else doc[token]
    return doc


def text_leaves(value, path=''):
    if isinstance(value, str):
        yield path, value
    elif isinstance(value, dict):
        for key, val in value.items():
            yield from text_leaves(val, path+'/'+key.replace('~','~0').replace('/','~1'))
    elif isinstance(value, list):
        for index, val in enumerate(value):
            yield from text_leaves(val, path+'/'+str(index))


def transcript_mentions(entity):
    # Pointers and whole original strings distinguish an example/citation from
    # a clinically relevant transcript. Never promote these tokens to scope.
    result = []
    for path, text in text_leaves(entity):
        for match in re.finditer(r'(?<![A-Za-z0-9_])(?:NM_|NR_|ENST)[0-9]+(?:\.[0-9]+)?(?![A-Za-z0-9_])', text):
            result.append({'accession':match[0], 'versionSpecified':'.' in match[0], 'jsonPointer':path,
                           'characterStart':match.start(), 'characterEnd':match.end(), 'originalText':text,
                           'role':'MENTION_ONLY_NOT_TRANSCRIPT_RELEVANCE'})
    return result


def scopes(entity):
    genes = {}
    for item in entity.get('ld', {}).get('Gene', []):
        raw = item.get('entContent', {}).get('HGNC', {})
        symbol, hgnc = raw.get('symbol'), raw.get('hgnc_id')
        if isinstance(symbol, str) and re.fullmatch(r'HGNC:[1-9][0-9]*', str(hgnc)):
            require(symbol not in genes or genes[symbol] == hgnc, 'CONFLICTING_SCOPE_GENE_IDENTITY')
            genes[symbol] = hgnc
    result = []
    for i, rs in enumerate(entity.get('ld', {}).get('RuleSet', [])):
        for j, gene in enumerate(rs.get('entContent', {}).get('genes', [])):
            for k, disease in enumerate(gene.get('diseases', [])):
                raw_moi = disease.get('preferredModeOfInheritance')
                raw_moi = raw_moi.strip() if isinstance(raw_moi, str) else ''
                scope = {'hgncId':genes.get(gene.get('gene'), ''), 'mondoId':disease.get('preferredMondoId', ''),
                         'moi':MOI.get(raw_moi, '')}
                status = ('EXACT' if scope['moi'] else 'INHERITANCE_NOT_PROVIDED' if not raw_moi else 'INHERITANCE_NOT_MAPPED')
                if not scope['hgncId'] or not re.fullmatch(r'MONDO:[0-9]{7}', str(scope['mondoId'])):
                    status = 'GENE_OR_DISEASE_UNRESOLVED'
                result.append({'scope':scope, 'scopeStatus':status, 'geneSymbol':gene.get('gene'),
                    'scopePointer':f'/ld/RuleSet/{i}/entContent/genes/{j}/diseases/{k}',
                    'rawGeneAssociation':copy.deepcopy(gene)})
    return result


def extract_entity(entity, source):
    require(released_current(entity), 'SOURCE_NOT_RELEASED')
    meta = entity['entContent']
    require(re.fullmatch(r'GN[0-9]+', str(entity.get('entId'))) and
            re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}', str(meta.get('version'))), 'SPECIFICATION_IDENTITY_INVALID')
    key = entity['entId']+'@'+meta['version']
    clauses = [(i, c) for i,c in enumerate(entity.get('ld',{}).get('CriteriaCode',[]))
               if c.get('entContent',{}).get('label') == 'PVS1']
    associations = scopes(entity)
    entries = []
    for scope in associations:
        for index, clause in clauses:
            content = clause['entContent']
            gene_list, disease_list = content.get('gene'), content.get('disease')
            genes_ok = isinstance(gene_list,list) and scope['geneSymbol'] in gene_list
            # An absent clause disease field is only parent RuleSet context,
            # never a statement that the clause is unrestricted or executable.
            diseases_ok = 'disease' not in content or isinstance(disease_list,list) and (not disease_list or scope['scope']['mondoId'] in disease_list)
            status = scope['scopeStatus'] if genes_ok and diseases_ok else 'CLAUSE_SCOPE_UNRESOLVED'
            relation = ('RULESET_DISEASE_CONTEXT_ONLY' if 'disease' not in content else
                        'EXPLICIT_EMPTY_DISEASE_SCOPE' if disease_list == [] else 'EXPLICIT_GENE_AND_DISEASE') if genes_ok and diseases_ok else 'UNRESOLVED'
            entry = {**copy.deepcopy(scope),'scopeStatus':status,'specificationKey':key,
                     'clauseScopeRelation':relation,'clausePointer':f'/ld/CriteriaCode/{index}', 'clauseSha256':content_hash(clause)}
            entry['entryId'] = 'pvs1-source-'+content_hash(entry)
            entries.append(entry)
    spec = {'specificationKey':key,'source':copy.deepcopy(source),'entitySha256':content_hash(entity),
            'original':copy.deepcopy(entity),'transcriptMentions':transcript_mentions(entity),
            'pvs1ClauseCount':len(clauses),'scopeCount':len(associations),
            'attachmentStatus':'REFERENCES_ONLY_BODY_NOT_VERIFIED', 'runtimeEligible':False}
    return spec, entries


def load_specifications(catalog, capture):
    require(catalog.get('schemaVersion') == 'a01-ba1-cspec-snapshot-v1'
            and catalog.get('sha256') == digest({k:v for k,v in catalog.items() if k != 'sha256'})
            and catalog.get('metadata',{}).get('status') == 'COMPLETE', 'SOURCE_CATALOG_INVALID')
    # Reuse the snapshot only as a pinned source inventory. Re-read originals
    # and derive PVS1 scopes; never inherit a BA1 scope, conclusion or approval.
    unique = {}
    for item in catalog['entries']:
        key = item['specificationId']
        source = {k:item['source'][k] for k in ('rawRef','rawSha256','jsonPointer','sourceUrl')}
        require(item['rawRef'] == source['rawRef'] and item['rawSha256'] == source['rawSha256'], 'SOURCE_REF_MISMATCH')
        if key in unique:
            require(unique[key] == source, 'SPECIFICATION_SOURCE_AMBIGUOUS')
        unique[key] = source
    require(len(unique) == catalog['metadata']['selectedReleasedCount'], 'SOURCE_SPECIFICATION_SET_INCOMPLETE')
    specs, entries, cache = [], [], {}
    for key, source in sorted(unique.items()):
        raw_key = (source['rawRef'],source['rawSha256'])
        if raw_key not in cache:
            cache[raw_key] = parse(pinned(child(capture,source['rawRef']), source['rawSha256'], 8_000_000))
        entity = pointer(cache[raw_key],source['jsonPointer'])
        require(entity.get('entId') == key, 'SOURCE_ENTITY_MISMATCH')
        spec, joined = extract_entity(entity,source)
        specs.append(spec);entries.extend(joined)
    require(len({e['entryId'] for e in entries}) == len(entries),'DUPLICATE_SOURCE_ENTRY')
    return specs, entries


def evidence_details(records):
    result = []
    for row in records:
        raw = row['sourceRecord']
        def publications(field):
            return [{'rawToken':s.strip(),'pmid':s.strip() if s.strip().isdigit() else None} for s in raw.get(field,'').split(';') if s.strip()]
        result.append({'recordId':row['recordId'],'sourceRecordSha256':row['sourceRecordSha256'],
            'support':raw.get('molecular mechanism support',''), 'categorisation':raw.get('molecular mechanism categorisation',''),
            'mechanismEvidence':raw.get('molecular mechanism evidence',''), 'variantTypes':raw.get('variant types',''),
            'variantConsequence':raw.get('variant consequence',''),'comments':raw.get('comments',''),
            'curatedPublications':publications('publications'),'minedPublicationLeads':publications('additional mined publications'),
            'lastSourceReview':raw.get('date of last review',''), 'runtimeEligible':False})
    return result


def compile_sources(config):
    require(config.get('schemaVersion') == 'pvs1-applicability-supply-input-v1'
            and set(config) == {'schemaVersion','sources','captureDirectory'}, 'CONFIG_INVALID')
    sources = config['sources'];require(set(sources)=={'assembly','catalog'},'CONFIG_SOURCES_INVALID')
    assembly = parse(pinned(sources['assembly']['file'],sources['assembly']['sha256'],64_000_000))
    require(assembly.get('schemaVersion')=='pvs1-material-assembly-v1' and assembly.get('runtimeEligible') is False
            and assembly.get('clinicalAcceptance') is False,'ASSEMBLY_NOT_SOURCE_MATERIAL')
    catalog = parse(pinned(sources['catalog']['file'],sources['catalog']['sha256'],32_000_000))
    specs, entries = load_specifications(catalog,config['captureDirectory'])
    records = {r['recordId']:r for r in assembly['sourceRecords']}
    require(len(records)==len(assembly['sourceRecords']), 'DUPLICATE_MECHANISM_RECORD')
    for r in records.values():
        require(content_hash(r['sourceRecord']) == r['sourceRecordSha256'], 'MECHANISM_RECORD_HASH_INVALID')
    indexed = {}
    for e in entries:
        s=e['scope'];indexed.setdefault((s['hgncId'],s['mondoId']),[]).append(e)
    backgrounds=[]
    for b in assembly['backgrounds']:
        rs=[records[i] for i in b['sourceRecordIds']]
        require(content_hash(rs)==b['sourceRecordSetSha256'],'BACKGROUND_SOURCE_SET_INVALID')
        related=indexed.get((b['hgncId'],b['mondoId']),[])
        exact=[e['entryId'] for e in related if e['scopeStatus']=='EXACT' and e['scope']['moi']==b['moi']]
        unresolved=[e['entryId'] for e in related if e['scopeStatus']!='EXACT']
        conflicts=[e['entryId'] for e in related if e['scopeStatus']=='EXACT' and e['scope']['moi']!=b['moi']]
        backgrounds.append({'scope':{k:b[k] for k in ('hgncId','mondoId','moi')},'allelicRequirement':b['allelicRequirement'],
            'sourceRecordSetSha256':b['sourceRecordSetSha256'],'mechanismDetails':evidence_details(rs),
            'exactEntryIds':exact,'unresolvedEntryIds':unresolved,'differentInheritanceEntryIds':conflicts,
            'transcriptCandidates':b['transcripts'],'historicalMaterialIds':b['historicalMaterialIds'],
            'status':'SOURCE_DETAILS_REQUIRE_INTERPRETATION' if exact or unresolved else 'NO_MATCH_IN_LOADED_SPECIALIST_SNAPSHOT',
            'runtimeEligible':False})
    counts={'specifications':len(specs),'pvs1Clauses':sum(s['pvs1ClauseCount'] for s in specs),'sourceAssociations':len(entries),
            'clauseScopeRelations':dict(Counter(e['clauseScopeRelation'] for e in entries)),
            'associationStatuses':dict(Counter(e['scopeStatus'] for e in entries)), 'backgrounds':len(backgrounds),
            'backgroundsWithExactScopedSource':sum(bool(b['exactEntryIds']) for b in backgrounds),
            'backgroundsWithUnresolvedScopedSource':sum(bool(b['unresolvedEntryIds']) for b in backgrounds),
            'backgroundsWithDifferentInheritanceSource':sum(bool(b['differentInheritanceEntryIds']) for b in backgrounds),
            'sourceRecordsWithEvidenceSupport':sum(r['sourceRecord'].get('molecular mechanism support')=='evidence' for r in records.values()),
            'sourceRecordsWithInferredSupport':sum(r['sourceRecord'].get('molecular mechanism support')=='inferred' for r in records.values()),
            'newlyAdoptedBackgrounds':0}
    return {'schemaVersion':'pvs1-applicability-sources-v1','methodVersion':VERSION,'status':'SOURCE_FACTS_ONLY',
            'runtimeEligible':False,'clinicalAcceptance':False,'sourceScope':'PINNED_HISTORICAL_SNAPSHOT_NOT_LATEST_CLAIM',
            'mechanismCandidatesRawSha256':assembly['sources']['mechanisms']['rawSha256'],
            'inputSources':copy.deepcopy(sources),'sourceSnapshotMetadata':catalog['metadata'],
            'specifications':specs,'entries':entries,'backgrounds':backgrounds,'counts':counts}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--config',required=True);p.add_argument('--config-sha256',required=True);p.add_argument('--output',required=True)
    a=p.parse_args();result=compile_sources(parse(pinned(a.config,a.config_sha256,1_000_000)));write_new(a.output,result)
    print(json.dumps(result['counts'],sort_keys=True))


if __name__=='__main__':
    main()
