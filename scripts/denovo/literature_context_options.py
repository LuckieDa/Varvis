"""Published disease scopes for explicit selection, never diagnosis or rule approval."""
import argparse,json
from pathlib import Path
from pm3_specification_context import SpecificationContext,scopes
from prepare_applicability_sources import pointer
from model_literature_contract import canonical,digest,parse
from model_transport import bounded

MODES = {'autosomal dominant': 'AD', 'autosomal recessive': 'AR',
         'x-linked dominant': 'XLD', 'x-linked recessive': 'XLR',
         'mitochondrial': 'MT'}

def published_modes(disease):
    """Read explicit structured declarations only; never infer from disease names."""
    values = []
    preferred = disease.get('preferredModeOfInheritance')
    if preferred: values.append(preferred)
    other = disease.get('modesOfInheritance', [])
    if not isinstance(other, list): return [], 'UNMAPPED'
    for row in other:
        if not isinstance(row, dict): return [], 'UNMAPPED'
        values.append(row.get('modeOfInheritance'))
    if not values: return [], 'MISSING'
    modes = set()
    for value in values:
        if not isinstance(value, str): return [], 'UNMAPPED'
        normalized = ' '.join(value.strip().lower().split()).removesuffix(' inheritance')
        mode = MODES.get(normalized)
        if not mode: return [], 'UNMAPPED'
        modes.add(mode)
    # Explicit alternative modes are separate analysis backgrounds, not a conflict.
    # Only an internally inconsistent preferred declaration needs reconciliation.
    if preferred and other:
        primary = MODES.get(' '.join(preferred.strip().lower().split()).removesuffix(' inheritance'))
        alternatives = {MODES.get(' '.join(r['modeOfInheritance'].strip().lower().split()).removesuffix(' inheritance')) for r in other}
        if primary not in alternatives: return sorted(modes), 'CONFLICT'
    return sorted(modes), 'VERIFIED'

def resolve_inheritance(item):
    declarations = [s['inheritanceDeclaration'] for s in item['sources'] if 'inheritanceDeclaration' in s]
    conflict = any(d['status'] == 'CONFLICT' for d in declarations)
    unmapped = any(d['status'] == 'UNMAPPED' for d in declarations)
    supply = item.get('inheritanceSupply', {})
    source_unavailable = supply.get('status') == 'UNAVAILABLE' or item.get('expertInheritanceSupply', {}).get('status') == 'UNAVAILABLE'
    if conflict:
        item['publishedInheritance'] = []
        state = 'CONFLICT'
    elif item['publishedInheritance']:
        state = 'VERIFIED'
    else:
        state = 'UNMAPPED' if unmapped else 'RESOURCE_UNAVAILABLE' if source_unavailable else 'MISSING'
    item['inheritanceResolution'] = {'status': state, 'automaticSelectionAllowed': state == 'VERIFIED',
        'reasonCode': {'VERIFIED': 'EXACT_SOURCED_INHERITANCE', 'MISSING': 'EXACT_INHERITANCE_SOURCE_MISSING',
                       'CONFLICT': 'EXACT_INHERITANCE_SOURCE_CONFLICT', 'UNMAPPED': 'INHERITANCE_TERM_NOT_MAPPED',
                       'RESOURCE_UNAVAILABLE': 'INHERITANCE_RELATIONSHIP_RESOURCE_UNAVAILABLE'}[state],
        'requiredAction': 'NONE' if state == 'VERIFIED' else 'VERIFY_EXACT_GENE_DISEASE_SOURCE',
        'unmappedSourcePresent': unmapped, 'patientDiagnosisInferred': False}

def supply_inheritance(item, relationships):
    """Join exact identities only; a gene or similar disease name is not enough."""
    rows=[(i,r) for i,r in enumerate(relationships.rows)
          if r.get('hgncId')==item['hgncId'] and r.get('symbol')==item['geneSymbol']
          and r.get('mondoId')==item['mondoId']]
    modes={r.get('moi') for _,r in rows}
    declared=set(item['publishedInheritance'])
    proof={'status':'MISSING','reasonCode':'EXACT_GENE_DISEASE_INHERITANCE_NOT_AVAILABLE',
           'source':relationships.source,'matches':[{'catalogPointer':f'/{i}','record':r} for i,r in rows],
           'meaning':'ANALYSIS_BACKGROUND_NOT_PATIENT_DIAGNOSIS_OR_RULE_APPROVAL'}
    if rows:
        if (not modes or not modes <= {'AD','AR','XLD','XLR','MT'}
                or any(r.get('classification') not in {'Strong','Definitive'} for _,r in rows)):
            proof.update(status='UNRESOLVED',reasonCode='INHERITANCE_SOURCE_CONFLICT_OR_UNQUALIFIED')
        else:
            proof.update(status='VERIFIED',reasonCode='EXACT_QUALIFIED_GENE_DISEASE_INHERITANCE')
            item['publishedInheritance']=sorted(declared | modes)
            item['sources'].append({'kind':'EXACT_CLINGEN_RELATIONSHIP',**proof})
    item['inheritanceSupply']=proof

def options(root, configuration):
    config=configuration.get('pp1Knowledge',{})
    if config.get('kind')!='PUBLISHED_CSPEC':
        return {'schemaVersion':'literature-context-options-v1','contexts':[],'reasonCode':'PUBLISHED_CONTEXT_SOURCE_NOT_CONFIGURED'}
    source=SpecificationContext(root,config); grouped={}
    for spec in source.specifications:
        for row in scopes(spec['original']):
            if row['scopeStatus'] not in {'EXACT','INHERITANCE_NOT_PROVIDED','INHERITANCE_NOT_MAPPED'}:continue
            scope=row['scope']; disease=pointer(spec['original'],row['scopePointer'])
            label=disease.get('preferredTitle')
            if not isinstance(label,str) or not label.strip():continue
            key=(scope['hgncId'],row['geneSymbol'],scope['mondoId'])
            item=grouped.setdefault(key,{'hgncId':key[0],'geneSymbol':key[1],'mondoId':key[2],
                'diseaseLabels':[],'publishedInheritance':[],'sources':[],
                'selectionMeaning':'EXPLICIT_ANALYSIS_HYPOTHESIS_NOT_DIAGNOSIS_OR_RULE_APPROVAL'})
            if label not in item['diseaseLabels']:item['diseaseLabels'].append(label)
            declared, status = published_modes(disease)
            if status == 'VERIFIED':
                for mode in declared:
                    if mode not in item['publishedInheritance']: item['publishedInheritance'].append(mode)
            item['sources'].append({'specificationKey':spec['specificationKey'],**spec['source'],'scopePointer':row['scopePointer'],
                                   'inheritanceDeclaration': {'modes': declared, 'status': status}})
    values=[grouped[k] for k in sorted(grouped)]
    if configuration.get('pm3DiseaseContext'):
        # Reuse the already pinned shared ClinGen relationship resource. No
        # PM3 AR rule, patient phenotype, subtype inference or new network read.
        from pm3_disease_context import DiseaseContext
        try:
            relationships=DiseaseContext(root,configuration['pm3DiseaseContext'])
        except (ValueError, OSError, KeyError):
            # Optional supplementary knowledge is not authority to invalidate a
            # separately verified published declaration or invent a missing one.
            for value in values:value['inheritanceSupply'] = {'status':'UNAVAILABLE',
                'reasonCode':'INHERITANCE_RELATIONSHIP_RESOURCE_UNAVAILABLE','matches':[]}
        else:
            for value in values:supply_inheritance(value,relationships)
    if configuration.get('publishedInheritanceSources'):
        from expert_inheritance_source import supply
        supply(root, configuration['publishedInheritanceSources'], source.specifications, values)
    for value in values:
        resolve_inheritance(value)
        value['contextId']=digest(canonical(value))
    return {'schemaVersion':'literature-context-options-v1','contexts':values,'source':source.source,
            'clinicalApproval':False,'networkRequests':0,'modelCalls':0}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--output',required=True);a=p.parse_args()
    raw=bounded(a.config,16384);config=parse(raw)
    result=options(Path(config['projectRoot']).resolve(strict=True),config)
    result['workflowConfigSha256']=digest(raw)
    Path(a.output).write_bytes(canonical(result))
