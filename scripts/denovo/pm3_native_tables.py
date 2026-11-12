"""Literal structured-table intake, separate from model extraction/review.

The target selects source rows only. It supplies no gene, genotype, transcript,
disease, phase, primary origin or classification. All downstream checks remain.
"""
from copy import deepcopy
from bundle_model_contract import cite, require
from pm3_table_headers import layout, source_tree


def cases(source, target):
    from pm3_observations import pm3_case_header, pm3_row_label, ownership
    from pm3_case_semantics import VARIANT_HEADING, EXACT_ALLELE, PROTEIN_ALLELE, alias_key
    from pm3_case_semantics import (GENE_HEADING, TRANSCRIPT_HEADING, DIAGNOSIS_HEADING,
                                   AFFECTED_HEADING, ZYGOSITY_HEADING)
    aliases={alias_key(v.split(':')[-1]) for v in target['variantAliases']}
    found={};tree=source_tree(source)
    for table in source['main']['tables']:
        try:headers,rows=layout(table,tree)
        except ValueError:continue
        case_cols=[i for i,h in enumerate(headers) if pm3_case_header(h['text'])]
        allele_cols=[i for i,h in enumerate(headers) if VARIANT_HEADING.fullmatch(h['text'])
                     and h.get('headingEvidence',{}).get('status')!='AMBIGUOUS']
        if len(case_cols)!=1 or len(allele_cols) not in (1,2):continue
        for row in rows:
            needed=[case_cols[0],*allele_cols]
            if len(row)!=len(headers) or any(row[i] is None for i in needed):continue
            label=pm3_row_label(row[case_cols[0]]['text'],headers[case_cols[0]]['text'])
            if not label:continue
            values=[row[i]['text'].strip() for i in allele_cols]
            if not all(EXACT_ALLELE.fullmatch(v) or PROTEIN_ALLELE.fullmatch(v) for v in values):continue
            matched=[i for i,v in enumerate(values) if alias_key(v.split(':')[-1]) in aliases]
            if not matched:continue
            # Two different target aliases in one row are not presumed equivalent.
            if len(matched)>1 and len({values[i] for i in matched})!=1:continue
            scope=ownership(source,label)
            if any(row[i]['unitId'] not in scope or scope[row[i]['unitId']].get('method')!='EXPLICIT_UNIQUE_CASE_ROW'
                   for i in needed):continue
            anchor=cite(source,{'unitId':row[case_cols[0]]['unitId'],'quote':row[case_cols[0]]['text']})
            case=found.setdefault(label,{'caseLabel':label,'facts':[],'anchors':[],'originAssertions':[],
                'nativeStructuredIntake':{'method':'EXACT_SOURCE_TABLE_VALUES','modelExtracted':False,
                    'modelReviewed':False,'meaning':'SOURCE_NATIVE_FACTS_STILL_REQUIRE_IDENTITY_CONTEXT_AND_RULE_CHECKS'}})
            if anchor not in case['anchors']:case['anchors'].append(anchor)
            for j,col in enumerate(allele_cols):
                field='targetVariant' if j==matched[0] else 'partnerVariant'
                unit=row[col]
                fact={'field':field,'sourceValue':unit['text'].strip(),'subject':'PROBAND','issues':[],
                    'producer':'SOURCE_NATIVE_LITERAL_TABLE','status':'CASE_OWNED_SEMANTICS_PENDING',
                    'citation':cite(source,{'unitId':unit['unitId'],'quote':unit['text']}),
                    'ownership':deepcopy(scope[unit['unitId']])}
                if fact not in case['facts']:case['facts'].append(fact)
            # Import explicit typed cells from this same uniquely owned row.
            # Previously a table could supply a genotype but its printed gene,
            # zygosity and diagnosis were left for a model to rediscover.
            for field, pattern in [('geneSymbol', GENE_HEADING), ('transcript', TRANSCRIPT_HEADING),
                                   ('diagnosis', DIAGNOSIS_HEADING), ('affectedStatus', AFFECTED_HEADING),
                                   ('reportedZygosity', ZYGOSITY_HEADING)]:
                for col, heading in enumerate(headers):
                    unit = row[col]
                    if not unit or not unit['text'].strip() or not pattern.fullmatch(heading['text']):continue
                    owned = scope.get(unit['unitId'], {})
                    if (owned.get('method') != 'EXPLICIT_UNIQUE_CASE_ROW'
                            or owned.get('anchorUnitId') != row[case_cols[0]]['unitId']
                            or owned.get('headingEvidence', {}).get('status') == 'AMBIGUOUS'):continue
                    fact = {'field': field, 'sourceValue': unit['text'].strip(), 'subject': 'PROBAND', 'issues': [],
                            'producer': 'SOURCE_NATIVE_LITERAL_TABLE', 'status': 'CASE_OWNED_SEMANTICS_PENDING',
                            'citation': cite(source, {'unitId': unit['unitId'], 'quote': unit['text']}),
                            'ownership': deepcopy(owned)}
                    if fact not in case['facts']:case['facts'].append(fact)
            require(len(found)<=4096,'PM3_NATIVE_CASE_LIMIT')
    return list(found.values())
