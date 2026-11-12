"""Generic member correspondence from explicit closed text sets and reviewed drawings.

No family/gene whitelist. Unnumbered roles need a unique, actually traced graph
path from the source's explicit index person. Generation numbers never add edges.
"""
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
import re

from model_literature_contract import canonical,digest


def coding(value):
    return re.sub(r'^(c\.[0-9]+[ACGT])\s*>\s*([ACGT])$',r'\1>\2',value.strip())


def collective_assertive(unit, quote, start):
    from pp1_source_qualification import assertive
    # A negative publication-history clause does not negate a later test result.
    # Preserve offsets; every other conditional/negative remains in force.
    def history(text):
        return re.sub(r'\bnot been (?:previously )?reported (?:before|previously)(?=,)',
                      lambda m:' ' * len(m[0]),text,flags=re.I)
    return assertive(history(unit),history(quote),start)


def add(q, family, label, kind, value, refs, claims, method):
    row={'familyKey':family,'memberKey':q['families'][family]['members'][label],
         'memberLabel':label,'kind':kind,'value':value,'sourceRefs':refs,'claimIds':sorted(set(claims)), 'method':method}
    row['id']=digest(canonical([row[k] for k in ('familyKey','memberKey','kind','value')]))
    old=next((x for x in q['observations'] if x['id']==row['id']),None)
    if old:
        old['claimIds']=sorted(set(old['claimIds']+row['claimIds']))
        old['sourceRefs']=list({digest(canonical(r)):r for r in old['sourceRefs']+refs}.values())
    else:q['observations'].append(row)


def closed_groups(source, inventory, q):
    """Unlisted collective subjects require an explicit closed set, in one unit.

    Across-unit grouping is handled separately and needs a unique family/allele
    table binding; matching counts alone are insufficient.
    """
    if not q['identity']:return
    from pp1_source_qualification import assertive
    numbers={s:i for i,s in enumerate(('one','two','three','four','five','six','seven','eight','nine','ten'),1)}
    units={u['unitId']:u for u in source['units']}
    targets={coding(a) for a in inventory['target']['variantAliases'] if a.startswith('c.')}
    for fact in inventory['facts']:
        if fact['raw']['fieldId'] not in ('F06.3','F06.4') or fact.get('sourceAudit',{}).get('verdict')!='SUPPORTED' or fact['issues']:continue
        family=fact['familyKey'];members=q['families'][family]['members']
        for ref in fact['sourceRefs']:
            quote=ref['quote'];unit=units[ref['unitId']]['text']
            pattern=r'\ball (?P<n>\d+|'+ '|'.join(numbers)+r') (?P<disease>[A-Za-z][A-Za-z -]*?) patients (?:in this family )?(?:had|carried|carry) (?P<variant>c\.\d+[ACGT]\s*>\s*[ACGT])(?: mutation| variant)?\b'
            for match in re.finditer(pattern,quote,re.I):
                if coding(match['variant']) not in targets or not collective_assertive(unit,quote,match.start()):continue
                count=numbers.get(match['n'].lower(),int(match['n']) if match['n'].isdigit() else -1)
                diagnoses=defaultdict(list)
                for row in q['observations']:
                    if row['familyKey']==family and row['kind']=='diagnosis' and row['value']==match['disease'] and any(r.get('unitId')==ref['unitId'] for r in row['sourceRefs']):
                        diagnoses[row['memberLabel']].append(row)
                # Count equality alone is not proof: the same source unit must
                # explicitly close the set within this family.
                closure=re.search(r'\bAll '+re.escape(match['n'])+' '+re.escape(match['disease'])+r' patients in this family\b',unit,re.I)
                if not closure or not assertive(unit,unit,closure.start()) or len(diagnoses)!=count:continue
                if any(label not in members for label in diagnoses):continue
                if any(o['familyKey']==family and o['memberLabel'] in diagnoses and o['kind']=='diagnosis'
                       and o['value']!=match['disease'] for o in q['observations']):continue
                refs=[ref]+[r for rows in diagnoses.values() for row in rows for r in row['sourceRefs']]
                for label in diagnoses:
                    add(q,family,label,'genotype','CARRIER',refs,[fact['claimId']], 'EXPLICIT_CLOSED_SAME_UNIT_GROUP')


def table_family_binding(source, reviewed, targets, expected):
    """Literal Family / Mutation columns; no shifted/merged target cells."""
    found=[]
    for table in source.get('main',{}).get('tables',[]):
        if not table['rows']:continue
        cells=table['rows'][0]['cells'];headers={c['text'].strip().lower():c for c in cells}
        if len(headers)!=len(cells):continue
        family=next((headers[k] for k in ('family','family number','family no') if k in headers),None)
        variant=next((headers[k] for k in ('mutations','mutation','variant','variants') if k in headers),None)
        if not family or not variant:continue
        for row_index,row in enumerate(table['rows'][1:],1):
            columns={c['sourceCellIndex']:c for c in row['cells']}
            f,v=columns.get(family['sourceCellIndex']),columns.get(variant['sourceCellIndex'])
            if not f or not v or coding(v['text']) not in targets:continue
            proof=[family,variant,f,v]
            if any(c.get('rowspan',1)!=1 or c.get('colspan',1)!=1 for c in proof):return []
            # Raw source-cell indices are not logical columns under spanning
            # cells, including spans inherited from a preceding row.
            if any(c.get('colspan',1)!=1 for c in cells+row['cells']):return []
            if any(c.get('rowspan',1)>row_index-before for before,previous in enumerate(table['rows'][:row_index])
                   for c in previous['cells']):return []
            label=family_number(f['text']) or f['text'].strip().lower()
            found.append((label,proof))
    if not found or {x[0] for x in found}!={expected}:return []
    if not all(c['unitId'] in reviewed for _,proof in found for c in proof):return []
    return [{'unitId':c['unitId'],'quote':c['text'],'meaning':'EXPLICIT_FAMILY_VARIANT_TABLE_BINDING'} for _,proof in found for c in proof]


def across_unit_groups(source, inventory, q):
    """Join a closed diagnosed set to a result only with an explicit family/allele table.

    The count must close the set in the family's own description; matching counts
    or an LLM's familyLabel alone are insufficient to cross paragraph boundaries.
    """
    if not q['identity']:return
    from pp1_source_qualification import assertive
    units={u['unitId']:u for u in source['units']};reviewed=set(q['reviewedUnitIds'])
    targets={coding(a) for a in inventory['target']['variantAliases'] if a.startswith('c.')}
    numbers={s:i for i,s in enumerate(('one','two','three','four','five','six','seven','eight','nine','ten'),1)}
    for family,roster in q['families'].items():
        number=family_number(roster.get('sourceFamilyLabel',''))
        binding=table_family_binding(source,reviewed,targets,number)
        if not number or not binding:continue
        for uid in sorted(reviewed):
            text=units[uid]['text']
            mentioned={family_number(m[0]) for m in re.finditer(r'\b(?:family|pedigree) (?:\d+|one|two|three|four|five|six|seven|eight|nine|ten)\b',text,re.I)}
            if mentioned!={number}:continue
            for closure in re.finditer(r'\bAll (\d+|'+ '|'.join(numbers)+r') ([A-Za-z][A-Za-z -]*?) patients in this family\b',text,re.I):
                if not assertive(text,text,closure.start()):continue
                count=numbers.get(closure[1].lower(),int(closure[1]) if closure[1].isdigit() else -1)
                disease=closure[2];expanded=set()
                for row in q['observations']:
                    if row['familyKey']!=family or row['kind']!='diagnosis':continue
                    if row['value']==disease:expanded.add(disease)
                    if any(r.get('unitId')==uid and re.search(r'\bdiagnosed with '+re.escape(row['value'])+r' \('+re.escape(disease)+r'\)',r.get('quote','')) for r in row['sourceRefs']):expanded.add(row['value'])
                if len(expanded)!=1:continue
                term=next(iter(expanded));members={}
                for row in q['observations']:
                    if row['familyKey']==family and row['kind']=='diagnosis' and row['value']==term and any(r.get('unitId')==uid for r in row['sourceRefs']):
                        members.setdefault(row['memberKey'],row)
                if len(members)!=count:continue
                # Another diagnosis for a proposed member is a conflict, not
                # permission to choose the value that matches this group.
                if any(o['familyKey']==family and o['memberKey'] in members and o['kind']=='diagnosis' and o['value']!=term for o in q['observations']):continue
                for fact in inventory['facts']:
                    if fact['familyKey']!=family or fact['raw']['fieldId'] not in ('F06.3','F06.4') or fact['issues'] or fact.get('sourceAudit',{}).get('verdict')!='SUPPORTED':continue
                    for ref in fact['sourceRefs']:
                        pattern=r'\ball '+re.escape(closure[1])+' '+re.escape(disease)+r' patients (?:in this family )?(?:had|carried|carry) (c\.\d+[ACGT]\s*>\s*[ACGT])(?: mutation| variant)?\b'
                        match=re.search(pattern,ref['quote'],re.I)
                        if not match or coding(match[1]) not in targets or not collective_assertive(units[ref['unitId']]['text'],ref['quote'],match.start()):continue
                        other_labels={family_number(m[0]) for m in re.finditer(r'\b(?:family|pedigree) (?:\d+|one|two|three|four|five|six|seven|eight|nine|ten)\b',units[ref['unitId']]['text'],re.I)}
                        if other_labels and other_labels!={number}:continue
                        proof=binding+[ref,{'unitId':uid,'quote':closure[0],'meaning':'EXPLICIT_CLOSED_AFFECTED_SET'}]+[r for row in members.values() for r in row['sourceRefs']]
                        proof=[{**r,'pmcid':source['pmcid'],'bundleSha256':source['bundleSha256']} for r in proof]
                        for row in members.values():add(q,family,row['memberLabel'],'genotype','CARRIER',proof,[fact['claimId']], 'CLOSED_GROUP_AND_UNIQUE_FAMILY_ALLELE_TABLE')
                        q['unresolved']=[x for x in q['unresolved'] if not (x.get('claimId')==fact['claimId'] and x.get('reasonCode')=='PP1_TYPED_SEMANTICS_UNRESOLVED')]
                        q.setdefault('collectiveCorrespondence',[]).append({'claimId':fact['claimId'],'familyKey':family,
                            'members':[r['memberLabel'] for r in members.values()],'sourceRefs':proof,'meaning':'COUNT_ALONE_WAS_NOT_USED'})


def explicit_role_edges(source, q):
    """A literal mother/father in the index subject's source chain gives one edge.

    A grandfather does not reveal which intermediate parent; it adds no edge.
    """
    units={u['unitId']:u for u in source['units']}
    for family,roster in q['families'].items():
        probands=[o for o in q['observations'] if o['familyKey']==family and o['kind']=='proband' and o['value'] is True]
        if len({x['memberKey'] for x in probands})!=1:continue
        proband=probands[0]
        for row in list(q['observations']):
            if row['familyKey']!=family or row['kind']!='diagnosis' or row['memberLabel'] not in ('mother','father'):continue
            ids={r.get('unitId') for r in row['sourceRefs']} & {r.get('unitId') for r in proband['sourceRefs']}
            for uid in ids:
                if uid not in units:continue
                text=units[uid]['text']
                if re.search(r'\b(adopted|adoptive|stepmother|stepfather|donor|surrogate|suspected|possible)\b',text,re.I):continue
                if any(proband['memberLabel'] in r.get('quote','') for r in row['sourceRefs']) and any(re.search(r'\b(?:Her|His)\b.*\b'+row['memberLabel']+r'\b',r.get('quote','')) for r in row['sourceRefs']):
                    add(q,family,row['memberLabel'],'parentOf',proband['memberKey'],row['sourceRefs']+proband['sourceRefs'],row['claimIds'], 'EXPLICIT_PARENT_ROLE_IN_UNIQUE_INDEX_SUBJECT_CHAIN')


def family_number(value):
    words={w:str(i) for i,w in enumerate(('one','two','three','four','five','six','seven','eight','nine','ten','eleven','twelve'),1)}
    m=re.fullmatch(r'(?:family|pedigree)\s+([\w-]+)',value.strip(),re.I)
    return words.get(m[1].lower(),m[1].lower()) if m else None


def merge_drawing(q, source, inventory, graph, pair_sha):
    from pp1_pedigree import require
    families=[(key,r) for key,r in q['families'].items() if family_number(r.get('sourceFamilyLabel','')) is not None
              and family_number(r['sourceFamilyLabel'])==family_number(graph['familyLabel'])]
    require(len(families)==1,'FAMILY_NOT_UNIQUE')
    family,roster=families[0];members=roster['members'];nodes={n['id']:n for n in graph['nodes']}
    probands={o['memberLabel'] for o in q['observations'] if o['familyKey']==family and o['kind']=='proband' and o['value'] is True}
    require(len(probands)==1,'TEXT_PROBAND_UNRESOLVED')
    proband=next(iter(probands));require(proband in nodes,'INDEX_ID_NOT_IN_DRAWING')
    # The family panel must also carry the target coding allele. Similar disease
    # or family labels in another paper/panel are insufficient.
    targets={coding(a) for a in inventory['target']['variantAliases'] if a.startswith('c.')}
    require(q['identity'] and any(coding(n['variantText']) in targets for n in nodes.values()),'TARGET_NOT_IN_PANEL')
    parents=defaultdict(set)
    for e in graph['parentEdges']:parents[e['child']].add(e['parent'])
    require(all(len(v)<=2 for v in parents.values()),'TOO_MANY_PARENTS')
    def cycle(n,active):
        return n in active or any(cycle(p,active|{n}) for p in parents[n])
    require(not any(cycle(n,set()) for n in list(nodes)),'CYCLIC_GRAPH')
    aliases={label:label for label in members if label in nodes}
    relations={'mother':(1,'FEMALE'),'father':(1,'MALE'),'grandmother':(2,'FEMALE'),'grandfather':(2,'MALE')}
    for role,(distance,sex) in relations.items():
        if role not in members:continue
        # Require the literal role anchored to this exact index person's source
        # paragraph, not a generic family-label or model-only relationship.
        rows=[o for o in q['observations'] if o['familyKey']==family and o['memberLabel']==role and o['kind']=='diagnosis']
        if not any(any(proband in r.get('quote','') for r in row['sourceRefs']) and
                   any(re.search(r'\b(?:Her|His)\b.*\b'+role+r'\b',r.get('quote','')) for r in row['sourceRefs']) for row in rows):continue
        generation={proband}
        for unused in range(distance):generation={p for n in generation for p in parents[n]}
        candidates={n for n in generation if nodes[n]['sex']==sex}
        if len(candidates)==1:aliases[role]=next(iter(candidates))
    # Collapse two labels for one explicitly linked person before counting.
    by_node=defaultdict(list)
    for label,id in aliases.items():by_node[id].append(label)
    node_label={id:(id if id in labels else sorted(labels)[0]) for id,labels in by_node.items()}
    replacements={members[label]:members[node_label[id]] for label,id in aliases.items()}
    for label,id in aliases.items():members[label]=members[node_label[id]]
    for row in q['observations']:
        if row['familyKey']!=family:continue
        row['memberKey']=replacements.get(row['memberKey'],row['memberKey'])
        if row['kind'] in {'parentOf','childOf','fullSiblingOf','siblingOf','grandchildOf'}:
            row['value']=replacements.get(row['value'],row['value'])
        row['id']=digest(canonical([row[k] for k in ('familyKey','memberKey','kind','value')]))
    # The same member may previously have appeared under a role and printed ID.
    # Keep contradictory values, but merge identical facts and their provenance.
    unique={}
    for row in q['observations']:
        if row['id'] not in unique:unique[row['id']]=row
        else:
            old=unique[row['id']]
            old['claimIds']=sorted(set(old['claimIds']+row['claimIds']))
            old['sourceRefs']=list({digest(canonical(r)):r for r in old['sourceRefs']+row['sourceRefs']}.values())
    q['observations']=list(unique.values())
    proof={'pmcid':source['pmcid'],'bundleSha256':source['bundleSha256'],
           'sourceKind':'PEDIGREE_IMAGE','imageSha256':graph['imageSha256'],'figureId':graph['figureId'],
           'visualPairSha256':pair_sha,'method':graph['method'],
           'locator':graph['figureId']+' / '+graph['familyLabel'],
           'meaning':'PRINTED_IDS_AND_VERIFIED_SOLID_PARENT_LINES_NOT_FILLED_SYMBOL_INFERENCE'}
    for id,label in node_label.items():
        node=nodes[id]
        if coding(node['variantText']) in targets:
            ref={**proof,'memberId':id,'symbolBox':node['symbolBox'],'printedVariant':node['variantText']}
            add(q,family,label,'genotype','CARRIER',[ref],[], 'TWICE_READ_PRINTED_VARIANT_IN_SAME_PANEL')
    for edge in graph['parentEdges']:
        if all(edge[k] in node_label for k in ('parent','child')):
            parent,child=node_label[edge['parent']],node_label[edge['child']]
            add(q,family,parent,'parentOf',members[child],[{**proof,**edge}],[], 'TWICE_READ_AND_PIXEL_TRACED_PARENT_EDGE')
    q.setdefault('memberCorrespondence',[]).append({'familyKey':family,'aliases':aliases,'sourceRef':proof,'issues':graph['issues']})


def augment(library, source, inventory, qualified, reviews=None):
    from pp1_pedigree import verify_pair
    q=deepcopy(qualified);closed_groups(source,inventory,q);across_unit_groups(source,inventory,q);explicit_role_edges(source,q)
    root=Path(library)/'papers'/source['pmcid']/'pedigree-figures'
    sources=(sorted(root.glob('*/source.json')) if root.exists() else []) if reviews is None else sorted({Path(f) for f,p in reviews})
    q['pedigreeReview']={'registeredFigures':len(sources),'reviewedPairs':0,'issues':[]}
    for figure in sources[:8]:
        pairs=(sorted((figure.parent/'reviews').glob('*.json')) if (figure.parent/'reviews').exists() else []) if reviews is None else [Path(p) for f,p in reviews if Path(f)==figure]
        if not pairs:q['pedigreeReview']['issues'].append({'reasonCode':'PP1_PEDIGREE_IMAGE_NOT_MODEL_REVIEWED','figureSource':figure.parent.name})
        for path in pairs[:8]:
            try:
                graph,pair=verify_pair(source,figure,path)
                require_target=pair['target']==inventory['target']
                if not require_target:continue
                candidate=deepcopy(q)
                merge_drawing(candidate,source,inventory,graph,digest(path.read_bytes()))
                candidate['pedigreeReview']['reviewedPairs']+=1
                q=candidate
            except (ValueError,OSError,KeyError,TypeError) as error:
                code=str(error)
                if not re.fullmatch('[A-Z][A-Z0-9_]{1,160}',code):code='PP1_PEDIGREE_READ_FAILED'
                q['pedigreeReview']['issues'].append({'reasonCode':code,'figureSource':figure.parent.name})
    return q
