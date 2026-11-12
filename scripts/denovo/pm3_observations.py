"""Lossless PM3 observation projection. Case ownership is not clinical adoption.

Only replay-verified artifacts enter through collect(). A row belongs to a case
only through an explicit case column; adjacent cells or model agreement do not
establish a subject, phase, classification or independent observation.
"""
from collections import defaultdict
from copy import deepcopy
import re
import math
from bundle_model_contract import load, require, cite
from literature_case_structure import paragraph_owned, case_header, row_label, primary_origin
from pm3_table_headers import layout, source_tree
from model_literature_contract import canonical, digest
from pm3_literature_contract import FIELDS


def coordinate_projection(value):
    """Keep PDF coordinates as decimal text in the saved facts contract.

    The raw PDF, native units, model request and receipt remain unchanged.
    Binary floats cannot be identity values in the Java evidence carrier.
    """
    if isinstance(value, list): return [coordinate_projection(v) for v in value]
    if not isinstance(value, dict): return value
    result = {k:coordinate_projection(v) for k,v in value.items()}
    if result.get('bbox') is not None:
        box = result['bbox']
        require(isinstance(box,list) and len(box)==4 and all(type(v) in (int,float)
                and math.isfinite(v) for v in box), 'PM3_PDF_COORDINATES_INVALID')
        result['bbox'] = [str(v) for v in box]
        result['bboxEncoding'] = 'DECIMAL_STRINGS_IN_PDF_POINTS'
    return result


def cell_position(value):
    match = re.fullmatch(r'R([1-9][0-9]*)C([1-9][0-9]*)', value)
    if match: return tuple(map(int, match.groups()))
    match = re.fullmatch(r'([A-Z]{1,3})([1-9][0-9]*)', value)
    if not match: return None
    column = 0
    for letter in match[1]: column = column * 26 + ord(letter) - ord('A') + 1
    return int(match[2]), column


def pm3_row_label(value, header):
    # Printed number markers are syntax, not an inferred patient identity.
    numbered = re.fullmatch(r'(?:#|N[°º])\s*([1-9][0-9]*)', value.strip())
    return numbered[1] if numbered else row_label(value, header)


def pm3_case_header(text):
    return case_header(text) or bool(re.fullmatch(r'pt\s*id|(?:patient|case|proband|subject)(?:\s*#|\s+(?:id|no\.?|number))',text.strip(),re.I))


def ownership(source, label, issues=None):
    from pm3_pdf_observations import ownership as pdf_ownership
    owned = pdf_ownership(source,label)
    from pm3_single_case import ownership as single_ownership
    owned.update(single_ownership(source,label))
    from pm3_cis_report import ownership as cis_ownership
    owned.update(cis_ownership(source,label))
    if source.get('publisherReader'):
        from pm3_publisher_case import ownership as publisher_ownership
        owned.update(publisher_ownership(source,label))
    def rows_scope(rows, container, header=None):
        if not rows:
            return
        if header is None:
            header, rows = rows[0], rows[1:]
        cols = [i for i, c in enumerate(header) if c and pm3_case_header(c['text'])]
        if len(cols) != 1:
            return
        col = cols[0]
        matched = [r for r in rows if col < len(r) and r[col]
                   and pm3_row_label(r[col]['text'], header[col]['text']) == label]
        if len(matched) != 1:
            return
        row = matched[0]
        if len(row) != len(header):
            return
        # Merged labels cannot confer ownership on every neighbouring row.
        if any(c and (c.get('rowspan', 1) != 1 or c.get('colspan', 1) != 1
                      or c.get('gridSpan', 1) != 1 or c.get('verticalMerge')
                      or c.get('cellSemantics', 'TEXT') != 'TEXT'
                      or c.get('mergedCell')
                      or c.get('sheetState', 'visible') != 'visible') for c in [*header, row[col]]):
            return
        for i, c in enumerate(row):
            if c and header[i] and c.get('cellSemantics', 'TEXT') == 'TEXT' and not c.get('mergedCell') \
                    and c.get('sheetState', 'visible') == 'visible' and c.get('rowspan', 1) == 1 \
                    and c.get('colspan', 1) == 1 and c.get('gridSpan', 1) == 1 and not c.get('verticalMerge'):
                owned[c['unitId']] = {'method':'EXPLICIT_UNIQUE_CASE_ROW', 'container':container,
                    'anchorUnitId':row[col]['unitId'],
                    'cellText':c['text'],
                    'headingUnitId':header[i]['unitId'] if i < len(header) and header[i] else None,
                    'columnHeading':header[i]['text'] if i < len(header) and header[i] else None}
                if header[i] and header[i].get('headingEvidence'):
                    owned[c['unitId']]['headingEvidence'] = deepcopy(header[i]['headingEvidence'])
                    for h in owned[c['unitId']]['headingEvidence']['sourceHeadings']:
                        if h['quote']:
                            h['citation']=cite(source, {'unitId':h['unitId'], 'quote':h['quote']})
    for unit in source['main']['units']:
        if paragraph_owned(source['main'], unit, label):
            owned[unit['unitId']] = {'method':'EXPLICIT_CASE_PARAGRAPH',
                                    'container':unit['unitId'], 'anchorUnitId':unit['unitId'],
                                    'paragraphText':unit['text'], 'caseLabel':label}
    tree = source_tree(source)
    for table in source['main']['tables']:
        try: header, rows = layout(table, tree)
        except ValueError as error:
            if issues is not None:
                issues.append({'tableId':table['tableId'], 'reasonCode':str(error)})
            continue
        rows_scope(rows, 'main:'+table['tableId'], header)
        # A caption can supply a table-wide gene/transcript context. It never
        # owns a genotype or diagnosis merely because its table contains a case.
        if any(v['container']=='main:'+table['tableId'] for v in owned.values()):
            for unit in source['main']['units']:
                if unit['kind']=='CAPTION' and unit['unitId'].startswith(table['tableId']+'/caption['):
                    owned[unit['unitId']]={'method':'SHARED_TABLE_CAPTION_SCOPE','container':'main:'+table['tableId'],
                        'captionText':unit['text'],'anchorUnitId':unit['unitId']}
    groups = defaultdict(list)
    for u in source['units']:
        if u['kind'] == 'SUPPLEMENT_CELL':
            groups[(u['sourceSha256'], u.get('archiveMemberName'), u.get('archiveMemberSha256'), u.get('sourcePart'), u['sheet'])].append(u)
    for key, cells in groups.items():
        rows = defaultdict(dict)
        duplicate = False
        for c in cells:
            m = cell_position(c.get('cell', ''))
            if not m:
                duplicate = True; break
            r, col = m
            if col in rows[r]: duplicate = True
            rows[r][col] = c
        if duplicate or not rows:
            continue
        width = max(max(row) for row in rows.values())
        require(width <= 4096 and len(rows) <= 4096, 'PM3_TABLE_SCOPE_LIMIT')
        header_rows = [n for n, row in rows.items() if any(pm3_case_header(c['text']) for c in row.values())]
        # Word gives us a bounded table and its first physical row. Values
        # such as "proband" in a later sample column are not new headers.
        first_row = min(rows)
        if (cells[0].get('sourcePart', '').startswith('word/') and first_row in header_rows):
            header_rows = [first_row]
        if len(header_rows) != 1: continue
        header_number = header_rows[0]
        rows_scope([[row.get(i) for i in range(1, width+1)] for n, row in sorted(rows.items()) if n > header_number],
                   'attachment:'+digest(canonical(list(key))),
                   [rows[header_number].get(i) for i in range(1, width+1)])
    return owned


def project(source, checked_reviews, target=None):
    """Source-checked reviews and optional native tables remain distinct producers."""
    from pm3_native_tables import cases as native_cases
    cases = {c['caseLabel']:c for c in native_cases(source,target)} if target is not None else {}
    for checked in checked_reviews:
        require(checked['pmcid'] == source['pmcid'] and checked['bundleSha256'] == source['bundleSha256']
                and checked['criterion'] == 'PM3', 'PM3_OBSERVATION_SOURCE_MISMATCH')
        for row in checked['cases']:
            label = row['caseLabel']
            case = cases.setdefault(label, {'caseLabel':label, 'facts':[], 'anchors':[], 'originAssertions':[]})
            if row['caseCitation'] not in case['anchors']: case['anchors'].append(deepcopy(row['caseCitation']))
            origin = {'proposal':row.get('caseOriginProposal','UNCLEAR'),
                      'citations':deepcopy(row.get('originCitations',[])),
                      'primarySourceVerified':primary_origin(source['main'], row)}
            if origin not in case['originAssertions']:case['originAssertions'].append(origin)
            scope = ownership(source, label)
            if any(v.get('method')=='EXPLICIT_SINGLE_PROBAND_RESULTS' for v in scope.values()):
                case['identityKind']='SOURCE_SCOPED_UNNAMED_PROBAND_NOT_PATIENT_ID'
                case['patientIdentifier']='NOT_REPORTED'
                # Actual clinical presentation plus own assay in one bounded
                # source section, not the model's author-cohort label alone.
                if origin['proposal']=='AUTHOR_COHORT' and any(
                        c['unitId'] in scope and c['quote'] in scope[c['unitId']]['paragraphText']
                        for c in origin['citations']):
                    origin['primarySourceVerified']=True
                    origin['method']='EXPLICIT_SINGLE_PROBAND_RESULTS'
            if any(v.get('method')=='ANONYMOUS_SOURCE_GENOTYPE_ROW' for v in scope.values()):
                case['identityKind']='ANONYMOUS_SOURCE_ROW_NOT_PATIENT_ID'
                case['patientIdentifier']='NOT_REPORTED'
            for field in row['fields']:
                fact = deepcopy(field)
                fact['ownership'] = scope.get(field['citation']['unitId'])
                fact['status'] = 'CASE_OWNED_SEMANTICS_PENDING' if fact['ownership'] else 'CASE_ATTRIBUTION_UNRESOLVED'
                if fact not in case['facts']: case['facts'].append(fact)
    from pm3_source_aliases import crosswalks
    crosswalk_issues=[]
    source_crosswalks=crosswalks(source,crosswalk_issues) if cases else []
    for case in cases.values():
        from literature_allele_identity import protein
        reported_proteins={protein(f['sourceValue']) for f in case['facts'] if f['field'] in ('targetVariant','partnerVariant')}
        reported_coding={f['sourceValue'].strip().split(':')[-1] for f in case['facts'] if f['field'] in ('targetVariant','partnerVariant')}
        case['sourceAlleleCrosswalks']=deepcopy([r for r in source_crosswalks if r['protein'] in reported_proteins or r['coding'] in reported_coding])
        case['sourceAlleleCrosswalkIssues']=deepcopy(crosswalk_issues)
        case['tableLayoutIssues']=[]
        scope=ownership(source,case['caseLabel'],case['tableLayoutIssues'])
        from pm3_source_context import table_context_facts
        from pm3_cohort_context import cohort_context
        context=cohort_context(source,scope)
        case['cohortContext']=context
        for derived in context['facts']:
            matches=[f for f in case['facts'] if f['field']==derived['field'] and
                     f['sourceValue']==derived['sourceValue'] and
                     f['citation']['unitId']==derived['citation']['unitId'] and not f.get('issues')]
            if len(matches)==1:
                # Update the derived projection only; the model artifact remains
                # immutable. A native proof now resolves this same literal claim.
                matches[0]['originalOwnership']=deepcopy(matches[0].get('ownership'))
                for k in ('ownership','producer','status','supportingCitations'):
                    matches[0][k]=deepcopy(derived[k])
            else:
                case['facts'].append(derived)
        for fact in table_context_facts(source,scope):
            if fact not in case['facts']:case['facts'].append(fact)
        from pm3_genotype_context import facts as genotype_context_facts
        for fact in genotype_context_facts(source,scope):
            if fact not in case['facts']:case['facts'].append(fact)
        from pm3_clinical_table_context import context as clinical_context
        clinical = clinical_context(source,scope,case['caseLabel'])
        case['clinicalAppendixContext'] = clinical
        for fact in clinical['facts']:
            if fact not in case['facts']:case['facts'].append(fact)
        from pm3_supplement_context import context as supplement_context
        supplement = supplement_context(source, scope)
        case['supplementContext'] = supplement
        for fact in supplement['facts']:
            if fact not in case['facts']:case['facts'].append(fact)
        case['paperPmid']=source['main'].get('identifiers',{}).get('pmid')
        case['fields'] = {}
        for name in FIELDS:
            facts = [f for f in case['facts'] if f['field'] == name]
            values = {f['sourceValue'] for f in facts}
            case['fields'][name] = {'status': 'NOT_EXTRACTED' if not facts else
                'MULTIPLE_CLAIMS_REQUIRE_RECONCILIATION' if len(values)>1 else 'PROPOSED',
                'factIndexes':[i for i,f in enumerate(case['facts']) if f['field']==name]}
        case['independenceStatus'] = 'NOT_ESTABLISHED'
        origins=case['originAssertions']
        # Preserve source/author-cohort proof separately from independent occurrence.
        # A reviewed model label or a distinct case number never proves unrelatedness.
        proposals={o['proposal'] for o in origins if o['proposal']!='UNCLEAR'}
        verified=any(o['primarySourceVerified'] for o in origins) or supplement['primarySourceVerified'] or clinical['primarySourceVerified']
        conflict=verified and any(p!='AUTHOR_COHORT' for p in proposals)
        case['primarySourceQualification']={
            'qualification':'CONFLICT' if conflict else 'VERIFIED' if verified else 'UNKNOWN',
            'reasonCode':'PM3_CASE_ORIGIN_CONFLICT' if conflict else
                'PM3_ORIGINAL_CASE_SOURCE_VERIFIED' if verified else 'PM3_ORIGINAL_CASE_SOURCE_UNRESOLVED',
            'method':'SHARED_PINNED_PRIMARY_ORIGIN_RECHECK',
            'independenceEstablished':False}
        if clinical['primarySourceVerified']:
            case['primarySourceQualification']['clinicalAppendixBindings'] = deepcopy(clinical['bindings'])
        if supplement['primarySourceVerified']:
            case['primarySourceQualification']['supplementSourceCitations'] = deepcopy(supplement['primarySourceCitations'])
    assessments=[]
    for checked in checked_reviews:
        for item in checked.get('sourceAssessments',[]):
            if item not in assessments:assessments.append(deepcopy(item))
    return {'schemaVersion':'pm3-observations-v1', 'pmcid':source['pmcid'],
        'bundleSha256':source['bundleSha256'], 'cases':list(cases.values()),
        'businessPublishable':False, 'scientificExecution':False,
        'coverageProposals':[r['extractionCoverageProposal'] for r in checked_reviews],
        'materialAssessments':assessments}


def collect(library, artifact_paths, manual_reviews=()):
    from run_pm3_literature_model import replay
    # Each source chunk has an extraction and a review artifact. Continuations
    # also replay cached chunks; their total is not the per-run new-work limit.
    require(0 < len(artifact_paths) <= 8192, 'PM3_ARTIFACT_COUNT')
    artifacts = [replay(library, p) for p in artifact_paths]
    first = artifacts[0]['binding']
    require(all(all(a['binding'][k] == first[k] for k in ('pmcid','bundleSha256','target'))
                for a in artifacts), 'PM3_OBSERVATION_BINDING_MISMATCH')
    # A completed source review is a replacement proposal, not additional votes.
    # Retain extraction artifacts as provenance, but never qualify their removed
    # or corrected facts alongside the reviewed replacement. An extraction whose
    # review failed remains visible in a separate, explicitly unreviewed branch.
    stages = {}
    for a in artifacts:
        stage=a['binding']['stage'];index=a['binding']['chunkIndex']
        require(stage in ('EXTRACTION','SOURCE_REVIEW'),'PM3_STAGE_INVALID')
        key=(index,stage)
        require(key not in stages or stages[key]['review']==a['review'],'PM3_CHUNK_REVIEW_CONFLICT')
        stages[key]=a
    reviewed=sorted(i for (i,s),a in stages.items() if s=='SOURCE_REVIEW' and a['review'].get('status')!='SOURCE_CHECK_REJECTED')
    extracted=sorted({i for i,s in stages})
    reviews=[stages[(i,'SOURCE_REVIEW')]['review'] for i in reviewed]
    source = load(library, first['pmcid'], first['bundleSha256'], criterion='PM3')
    result = project(source, [*reviews,*manual_reviews], first['target'])
    pending=[stages[(i,'EXTRACTION')]['review'] for i in extracted if i not in reviewed
             and (i,'SOURCE_REVIEW') not in stages and stages[(i,'EXTRACTION')]['review'].get('status')!='SOURCE_CHECK_REJECTED']
    result['unreviewedExtraction']=project(source,pending)
    result['unreviewedExtraction']['qualificationStatus']='MODEL_SOURCE_REVIEW_PENDING'
    result['target'] = deepcopy(first['target'])
    result['sourceRejectedProposals']=[{'chunkIndex':i,'stage':s,'reasonCode':a['review']['reasonCode']}
        for (i,s),a in stages.items() if a['review'].get('status')=='SOURCE_CHECK_REJECTED']
    result['sourceRejectedItems']=[{'chunkIndex':i,'stage':s,'artifactCandidatePointer':item['path'],
                                   'reasonCode':item['reasonCode']}
        for (i,s),a in stages.items() for item in a['review'].get('rejectedSourceItems',[])]
    qualify_projected(source,result,first['target'])
    result['extractedChunks'] = extracted
    result['processedChunks'] = reviewed
    result['chunkCount'] = first['chunkCount']
    result['allChunksProcessed'] = len(result['processedChunks']) == first['chunkCount']
    return coordinate_projection(result)


def qualify_projected(source, result, target):
    from pm3_case_semantics import qualify
    for case in result['cases']:
        for fact in native_phase_facts(source, case['caseLabel']):
            if not any(f['field'] == fact['field'] and f['sourceValue'] == fact['sourceValue']
                       and f['citation']['unitId'] == fact['citation']['unitId'] for f in case['facts']):
                case['facts'].append(fact)
        from pm3_single_case import native_facts as single_facts
        for fact in single_facts(source,case['caseLabel'],target):
            if fact not in case['facts']:case['facts'].append(fact)
        from pm3_prose_facts import native_facts
        for fact in native_facts(source,ownership(source,case['caseLabel']),case['caseLabel'],target):
            if not any(f['field']==fact['field'] and f['sourceValue']==fact['sourceValue']
                       and f['citation']['unitId']==fact['citation']['unitId'] for f in case['facts']):
                case['facts'].append(fact)
        for name in FIELDS:
            indexes=[i for i,f in enumerate(case['facts']) if f['field']==name]
            values={case['facts'][i]['sourceValue'] for i in indexes}
            case['fields'][name]={'status':'NOT_EXTRACTED' if not indexes else 'MULTIPLE_CLAIMS_REQUIRE_RECONCILIATION' if len(values)>1 else 'PROPOSED','factIndexes':indexes}
        case['semantics'] = qualify(case, target)
        if source.get('publisherReader'):
            from pm3_publisher_case import attach as attach_publisher_case
            attach_publisher_case(source, case, target)
        else:
            from pm3_cis_report import attach as attach_cis_report
            attach_cis_report(source, case, target)
            from pm3_supplement_diplotype import attach as attach_supplement_diplotype
            attach_supplement_diplotype(source, case, target)
        phase_observations(case)
    from pm3_occurrences import attach
    attach(source,result)
    return result


def native_phase_facts(source, label):
    """Read existing reportedPhase/phaseMethod slots from owned source cells.

    This does not change the public model contract. The native reader uses the
    same row/header ownership and source citation checks as other typed facts.
    """
    headings = re.compile(r'phase|phasing|phase method|molecular phase', re.I)
    scope = ownership(source, label)
    for unit in source['units']:
        owned = scope.get(unit['unitId'], {})
        heading = (owned.get('columnHeading') or '').strip()
        if (owned.get('method') != 'EXPLICIT_UNIQUE_CASE_ROW' or not headings.fullmatch(heading)
                or owned.get('headingEvidence', {}).get('status') == 'AMBIGUOUS'):
            continue
        value = unit['text'].strip()
        if value and owned.get('cellText', '').strip() == value:
            yield {'field': 'phaseMethod' if heading.casefold() in {'phase method', 'molecular phase'} else 'reportedPhase',
                   'sourceValue': value, 'subject': 'PROBAND', 'issues': [], 'ownership': deepcopy(owned),
                   'producer': 'SOURCE_NATIVE_LITERAL_TABLE_PHASE',
                   'citation': cite(source, {'unitId': unit['unitId'], 'quote': value})}


def phase_observations(case):
    """Retain only literal, case-owned phase claims and a bounded molecular assay.

    A claim of trans alone does not prove a method. Explicit cis is a negative
    observation, never an unknown-phase co-occurrence with positive weight.
    """
    from pm3_case_semantics import table_fact
    heading = re.compile(r'phase|phasing|phase method|molecular phase', re.I)
    molecular = {'Cloning and sequencing of both alleles confirmed the variants in trans.': 'TRANS',
                 'Cloning and sequencing of both alleles confirmed the variants in cis.': 'CIS'}
    for observation in case['semantics']['observations']:
        containers = {f['container'] for f in observation['facts']}
        accepted = []
        for index, fact in enumerate(case['facts']):
            if fact['field'] not in {'reportedPhase', 'phaseMethod'} or not table_fact(fact, heading):
                continue
            if fact['ownership']['container'] not in containers:
                continue
            value = fact['sourceValue'].strip()
            if value.casefold() not in {'cis', 'in cis', 'trans', 'in trans', 'unknown', 'phase unknown'} and value not in molecular:
                continue
            proof = {'field': 'phaseText', 'sourceField': fact['field'], 'value': value, 'reportedValue': value, 'factIndex': index,
                     'container': fact['ownership']['container'], 'columnHeading': fact['ownership'].get('columnHeading'),
                     'headingUnitId': fact['ownership'].get('headingUnitId'),
                     'method': 'EXPLICIT_CASE_TYPED_TABLE_PHASE', 'citation': deepcopy(fact['citation'])}
            observation['facts'].append(proof); accepted.append(value)
        phases = {molecular[v] for v in accepted if v in molecular}
        claims = {('CIS' if v.casefold() in {'cis', 'in cis'} else
                   'TRANS' if v.casefold() in {'trans', 'in trans'} else 'UNKNOWN')
                  for v in accepted if v not in molecular}
        if any(v.casefold() in {'cis', 'in cis'} for v in accepted):
            phases.add('CIS')
        if observation['phase'] in {'TRANS', 'CIS', 'CONFLICT'}:
            phases.add(observation['phase'])
        assertions = phases | claims
        if 'CONFLICT' in assertions or {'CIS', 'TRANS'} <= assertions or len(claims) > 1 or ('UNKNOWN' in claims and phases):
            observation.update(phase='CONFLICT', phaseBasis='CONTRADICTORY_SOURCE_PHASE')
        elif phases:
            value = next(iter(phases))
            if value != observation['phase']:
                observation.update(phase=value, phaseBasis='CLONING_AND_SEQUENCE_BOTH_ALLELES'
                    if any(v in molecular for v in accepted) else 'EXPLICIT_SOURCE_CIS')
        elif claims == {'UNKNOWN'}:
            observation['phaseBasis'] = 'EXPLICIT_SOURCE_PHASE_UNKNOWN'


def native_projection(source, target, chunk_count, manual_reviews=()):
    result=project(source,manual_reviews,target)
    result['target']=deepcopy(target)
    qualify_projected(source,result,target)
    result.update(extractedChunks=[],processedChunks=[],chunkCount=chunk_count,
                  allChunksProcessed=False,sourceRejectedProposals=[],
                  nativeStructuredCaseCount=len(result['cases']),
                  modelReviewStatus='NOT_RUN_NATIVE_FACTS_ARE_NOT_MODEL_REVIEW')
    return coordinate_projection(result)
