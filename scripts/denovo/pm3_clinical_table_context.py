"""Join an explicitly linked clinical appendix to its source genotype cohort.

Requires the full case-number set and matching sex/onset fields, not a matching
number alone. Only a source-defined, measured clinical classification is used;
database predictions and the queried disease never provide a diagnosis.
"""
import re
from collections import defaultdict
from copy import deepcopy
from pathlib import PurePosixPath
from bundle_model_contract import cite
from pm3_table_headers import source_tree, layout


def norm(value):
    return ' '.join(value.split()).casefold()


def heading_kind(value):
    value = norm(value)
    if value == 'sex': return 'sex'
    if re.fullmatch(r'age at on-?set/first event', value): return 'onset'
    return None


def context(source, scope, label):
    from pm3_observations import pm3_case_header, pm3_row_label, cell_position
    result = {'facts': [], 'bindings': [], 'primarySourceVerified': False,
              'reasonCode': 'PM3_CLINICAL_APPENDIX_JOIN_UNRESOLVED'}
    tree = source_tree(source)
    if tree is None: return result
    main = []
    for table in source['main']['tables']:
        container = 'main:' + table['tableId']
        if not any(v.get('container') == container and v.get('method') == 'EXPLICIT_UNIQUE_CASE_ROW'
                   for v in scope.values()): continue
        try: headers, rows = layout(table, tree)
        except ValueError: continue
        case_cols = [i for i,h in enumerate(headers) if pm3_case_header(h['text'])]
        kinds = {kind:[i for i,h in enumerate(headers) if heading_kind(h['text']) == kind]
                 for kind in ('sex','onset')}
        if len(case_cols) != 1 or any(len(v) != 1 for v in kinds.values()): continue
        records = {}; valid = True
        for row in rows:
            cols = [case_cols[0], *[v[0] for v in kinds.values()]]
            if len(row) != len(headers) or any(row[i] is None for i in cols): valid=False; break
            ident = pm3_row_label(row[case_cols[0]]['text'], headers[case_cols[0]]['text'])
            if not ident or ident in records: valid=False; break
            records[ident] = {'anchor':row[case_cols[0]], **{k:row[v[0]] for k,v in kinds.items()}}
        if valid and label in records: main.append((container, records))
    if len(main) != 1: return result
    container, records = main[0]
    groups = defaultdict(list)
    for u in source['units']:
        if u['kind'] == 'SUPPLEMENT_CELL':
            groups[(u['sourceSha256'],u.get('archiveMemberSha256'),u.get('archiveMemberName'),
                    u.get('sourcePart'),u.get('sheet'))].append(u)
    candidates = []
    for key, cells in groups.items():
        # Source-native named appendix; do not guess a ZIP member from its order.
        member = key[2]
        match = re.fullmatch(r'Supplementary Materials (S[1-9][0-9]*)\.xlsx',member or '')
        if not match: continue
        appendix_label = match[1]
        cell_ids = {c['unitId'] for c in cells}
        owned = {k:v for k,v in scope.items() if k in cell_ids and v.get('method') == 'EXPLICIT_UNIQUE_CASE_ROW'}
        anchors = {v['anchorUnitId'] for v in owned.values()}
        if len(anchors) != 1: continue
        # Native appendix links identify the containing file and clinical member.
        links = []
        for app in tree.iter('app'):
            appid = app.get('id')
            if not appid or sum(n.get('id') == appid for n in tree.iter()) != 1: continue
            description = ' '.join(''.join(app.itertext()).split())
            if not re.search(r'Supplementary Materials '+appendix_label+r': Clinical characteristics of ',description): continue
            files = [PurePosixPath(n.get('{http://www.w3.org/1999/xlink}href','')).name for n in app.iter('media')]
            if files != [cells[0].get('originalName')]: continue
            for u in source['main']['units']:
                if u['kind'] != 'BODY_PARAGRAPH': continue
                text = u['text']
                if not re.search(r'The (?:biochemical and )?clinical parameters of the patients are reported in the Supplementary Materials \('+appendix_label+r'\)\.',text): continue
                node = tree.find('./'+u['unitId'].removeprefix('/article[1]/'))
                if node is not None and any(x.get('ref-type') == 'app' and x.get('rid') == appid for x in node.iter('xref')):
                    links.append((u,appid,description))
        if len(links) != 1: continue
        headers = [c for c in cells if pm3_case_header(c['text'])]
        if len(headers) != 1: continue
        header = headers[0]; pos = cell_position(header.get('cell',''))
        if not pos: continue
        matrix = {cell_position(c.get('cell','')):c for c in cells}
        if None in matrix or len(matrix) != len(cells): continue
        hrow = {p[1]:c for p,c in matrix.items() if p[0] == pos[0]}
        cols = {k:[i for i,h in hrow.items() if heading_kind(h['text']) == k] for k in ('sex','onset')}
        if any(len(v) != 1 for v in cols.values()): continue
        clinical = [c for c in hrow.values() if re.fullmatch(r'Metabolic Phenotype\s*(?:\([^\n()]{1,80}\))?(\*{1,3})',c['text'])]
        if len(clinical) != 1: continue
        phenotype_header = clinical[0]; marker = re.search(r'\*+$',phenotype_header['text'])[0]
        # A database name in the header is insufficient. The marked source note
        # must explicitly classify patients from clinical measurements/tolerance.
        notes = [c for c in cells if re.fullmatch(re.escape(marker)+r' patients were assigned to these (?:[1-9][0-9]*|three) phenotype categories according to their pre-treatment plasma [A-Za-z][A-Za-z0-9 -]{0,40} levels or dietary [A-Za-z][A-Za-z0-9 -]{0,40} tolerance\.',c['text'].strip())]
        if len(notes) != 1: continue
        appendix_records = {}; valid = True
        for p,c in matrix.items():
            if p[1] != pos[1] or p[0] <= pos[0]: continue
            ident = pm3_row_label(c['text'],header['text'])
            if not ident: continue
            if ident in appendix_records: valid=False; break
            rec = {'anchor':c, **{k:matrix.get((p[0],v[0])) for k,v in cols.items()}}
            rec['phenotype'] = matrix.get((p[0],cell_position(phenotype_header['cell'])[1]))
            if any(v is None for v in rec.values()): valid=False; break
            appendix_records[ident] = rec
        if not valid or set(records) != set(appendix_records): continue
        if any(norm(records[i][k]['text']) != norm(appendix_records[i][k]['text'])
               or norm(records[i][k]['text']) in ('','na','unknown','not available')
               for i in records for k in ('sex','onset')): continue
        row = appendix_records[label]; value = row['phenotype']['text'].strip()
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9]{1,24}',value): continue
        expansions = [(c,m[1]) for c in cells for m in re.finditer(
            r'(?:^|;\s*)'+re.escape(value)+r': ([A-Za-z][A-Za-z -]{1,100})(?=;|\.$|$)',c['text'])]
        if len(expansions) != 1: continue
        expansion_unit, diagnosis = expansions[0]
        if re.search(r'\b(?:normal|unaffected|unknown|possible|suspected|available|predicted)\b',diagnosis,re.I): continue
        supporting = [links[0][0],records[label]['anchor'],row['anchor'],phenotype_header,notes[0],expansion_unit]
        supporting += [records[label][k] for k in ('sex','onset')]+[row[k] for k in ('sex','onset')]
        used = supporting + [row['phenotype'],header] + list(hrow.values())
        used += [v for r in records.values() for v in r.values()]
        used += [v for r in appendix_records.values() for v in r.values()]
        if any(c.get('cellSemantics','TEXT') != 'TEXT' or c.get('mergedCell')
               or c.get('sheetState','visible') != 'visible' or c.get('rowspan',1) != 1
               or c.get('colspan',1) != 1 for c in used): continue
        citations = [cite(source,{'unitId':c['unitId'],'quote':c['text']}) for c in supporting]
        binding = {'method':'SOURCE_CLINICAL_APPENDIX_COHORT_ROW_JOIN','caseLabel':label,
            'container':container,'appendixXmlId':links[0][1],'appendixDescription':links[0][2],
            'archiveMemberName':member,'archiveMemberSha256':key[1],
            'caseLabels':sorted(records),'matchedFields':['caseLabel','sex','onset'],
            'citations':citations,'independenceEstablished':False}
        recruitment = links[0][0]['text']
        binding['primaryRecruitmentVerified'] = bool(
            re.search(r'\bA total of [1-9][0-9]* DNA samples consisting of controls, probands and their parents were recruited by the [^.]{1,160}\bHospital\b',recruitment)
            and re.search(r'\bThe test group consisted of '+str(len(records))+r' patients\b',recruitment)
            and not re.search(r'\b(?:previously reported|published cases|literature review|pooled studies)\b',recruitment,re.I))
        candidates.append((binding,row['phenotype'],diagnosis))
    if len(candidates) != 1: return result
    binding, unit, diagnosis = candidates[0]
    for field,value in [('diagnosis',diagnosis),('affectedStatus','affected')]:
        result['facts'].append({'field':field,'sourceValue':value,'subject':'PROBAND','issues':[],
            'producer':'SOURCE_NATIVE_CLINICAL_APPENDIX','status':'CASE_OWNED_SEMANTICS_PENDING',
            'citation':cite(source,{'unitId':unit['unitId'],'quote':unit['text']}),
            'supportingCitations':deepcopy(binding['citations']),
            'ownership':{'method':binding['method'],'container':container,
                         'anchorUnitId':binding['citations'][1]['unitId'],'binding':deepcopy(binding)}})
    result.update(bindings=[binding],reasonCode='PM3_SOURCE_CLINICAL_CLASSIFICATION_BOUND',
                  primarySourceVerified=binding['primaryRecruitmentVerified'])
    return result


def accepts(fact, label):
    own = fact.get('ownership') or {}; binding = own.get('binding',{})
    return (fact.get('producer') == 'SOURCE_NATIVE_CLINICAL_APPENDIX'
        and own.get('method') == 'SOURCE_CLINICAL_APPENDIX_COHORT_ROW_JOIN'
        and binding.get('caseLabel') == label and len(binding.get('citations',[])) == 10
        and binding.get('matchedFields') == ['caseLabel','sex','onset']
        and not fact.get('issues') and fact.get('subject') == 'PROBAND'
        and fact.get('field') in ('diagnosis','affectedStatus'))
