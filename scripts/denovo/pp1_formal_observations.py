"""Bind reviewed clinical status and attained age to one source observation.

This is a bounded native-text/table reader, not a diagnosis or a visit selector.
It never combines legacy ``clinical`` and ``examinationAge`` rows. A full native
assertion, or an explicitly shared visit, must connect status and attained age.
Unsupported wording stays distinguishable from a missing source observation.
"""
from collections import defaultdict
from copy import deepcopy
import re

from model_literature_contract import canonical, digest
from pp1_literature_contract import require

VERSION = 'pp1-formal-clinical-observations-v1'
CONTEXT_MAX_UNITS = 5000
CONTEXT_MAX_CHARACTERS = 2_000_000
FIELDS = {'F05.2', 'F05.3', 'F05.4'}
IGNORED_ISSUES = {'MULTIPLE_VALUES_REQUIRE_RECONCILIATION',
                  'TARGET_ALLELE_LINK_NOT_EXPLICIT_IN_CITATIONS'}
VISIT = r'(?:visit|observation|follow-up) [A-Za-z0-9][A-Za-z0-9_-]{0,39}'
YEARS = r'(?P<age>\d{1,3}) (?:years old|years of age|years)'
AGE = rf'at (?:(?:the )?age (?:of )?)?{YEARS}'
EXAM = r'(?:clinical examination|examination|follow-up|clinical assessment|clinical evaluation)'


def _unique(values):
    return list({canonical(v): v for v in values}.values())


def _need(code, requirement, family=None, member=None, records=(), actual=None,
          blocking=True, route='SOURCE_REVIEW'):
    refs = _unique([r for item in records for r in item.get('sourceRefs', [])])
    result = {'reasonCode': code, 'requirement': requirement,
              'scope': {'familyKey': family, 'memberKey': member}, 'route': route,
              'blockingEffect': 'BLOCKS_SCORING' if blocking else 'MAY_ADD_INFORMATION',
              'knownFacts': deepcopy(list(records)), 'sourceLocators': refs,
              'expected': 'SAME_SOURCE_CLINICAL_OBSERVATION_STATUS_AND_ATTAINED_AGE',
              'actual': actual, 'ruleClause': 'PP1_FORMAL_SOURCE_BOUND_CLINICAL_OBSERVATION_V1'}
    result['needId'] = digest(canonical([code, family, member, actual, refs]))
    return result


def _sentences(text):
    # Full sentences are matched in the original unit, before considering a
    # quoted inner clause. Semicolons also terminate this bounded grammar.
    for match in re.finditer(r'.+?(?:[.!?;](?=\s|$)|$)', text, re.S):
        literal = match[0]
        left = len(literal) - len(literal.lstrip())
        statement = literal.strip().rstrip('.!?;')
        if statement:
            start = match.start() + left
            yield statement, start, start + len(statement)


def _subject(member):
    return rf'(?:[Tt]he )?(?:(?:patient|individual|member) )?\(?{re.escape(member)}\)?'


def _visit_label(value):
    # Normalize the ordinary observation noun, never the printed identifier.
    # V1 and v1 are not assumed to denote the same source observation.
    if re.fullmatch(VISIT, value, re.I):
        kind, identifier = value.rsplit(' ', 1)
        return kind.lower() + ' ' + identifier
    return value.lower()


def _status(body, member):
    person = _subject(member)
    match = re.fullmatch(rf'{person} (?:was|is|remained) '
                         r'(?P<clinical>clinically )?(?P<status>unaffected|healthy|affected)'
                         r'(?: (?:with respect to|with|by|for) (?P<disease>[^,;.!?]+))?', body, re.I)
    if match:
        disease = match['disease'].strip() if match['disease'] else None
        return {'status': 'UNAFFECTED' if match['status'].lower() in {'unaffected', 'healthy'} else 'AFFECTED',
                'diseaseTerm': disease,
                'diseaseScopeBasis': 'EXPLICIT_DISEASE_TERM' if disease else 'GENERAL_CLINICAL_STATUS'}
    match = re.fullmatch(rf'{person} (?:was|is|has been) (?:diagnosed with|affected with) ([^,;.!?]+)', body, re.I)
    if match:
        return {'status': 'AFFECTED', 'diseaseTerm': match[1].strip(),
                'diseaseScopeBasis': 'EXPLICIT_DISEASE_TERM'}
    return None


def _parse(statement, member):
    """One named subject and one explicit observation; no implicit anaphora."""
    if len(statement) > 2400:
        return None
    body, visit, age, age_text = statement, None, None, None
    prefix = re.match(rf'At (?P<visit>{VISIT}), ', body, re.I)
    if prefix:
        visit = _visit_label(prefix['visit'])
        body = body[prefix.end():]
    suffix = re.search(rf' (?:at|during) (?P<visit>{VISIT})$', body, re.I)
    if suffix:
        if visit and visit != _visit_label(suffix['visit']):
            return None
        visit = _visit_label(suffix['visit'])
        body = body[:suffix.start()]
    # A time explicitly attached to clinical status describes attained status,
    # whereas an ordinary age or onset age never supplies this observation.
    prefix = re.match(rf'At (?:the |his |her )?(?:last )?{EXAM} {AGE}, ', body, re.I)
    if prefix:
        age, age_text = int(prefix['age']), prefix[0].rstrip(', ')
        body = body[prefix.end():]
    suffix = re.search(rf' (?:at (?:the |his |her )?(?:last )?{EXAM} )?{AGE}$', body, re.I)
    if suffix:
        if age is not None and age != int(suffix['age']):
            return None
        age, age_text = int(suffix['age']), suffix[0].strip()
        body = body[:suffix.start()]
    # A clinical examination and status in the *same complete assertion*.
    compound = re.fullmatch(rf'{_subject(member)} (?:was|is) '
                            rf'(?:examined|clinically (?:examined|assessed|evaluated)) {AGE}'
                            r' and (?:was|is) (?P<status>.+)', body, re.I)
    if compound:
        if age is not None and age != int(compound['age']):
            return None
        age, age_text = int(compound['age']), compound[0].split(' and ')[0]
        body = member + ' was ' + compound['status']
    status = _status(body, member)
    if status:
        # A diagnosis/onset date is not silently upgraded to an examination
        # time. The source must identify an actual examination or named visit.
        if (age is not None and re.search(r'\bdiagnosed with\b', body, re.I)
                and not visit and not re.search(EXAM, statement, re.I)):
            age, age_text = None, None
        return {**status, 'attainedAge': age, 'ageText': age_text,
                'visit': visit, 'component': 'STATUS'}
    # Age-only statements must explicitly describe examination or follow-up.
    person = _subject(member)
    patterns = [
        rf'{person} (?:was|is) (?:examined|clinically (?:examined|assessed|evaluated)) {AGE}',
        rf'{person} underwent (?:a )?clinical examination {AGE}',
        rf'{person} was (?:last )?followed up {AGE}',
        rf'{person} (?:was|is) {YEARS} at (?:the |his |her )?(?:last |clinical )?{EXAM}',
        rf'At (?:the |his |her )?(?:last |clinical )?{EXAM}, {person} (?:was|is) {YEARS}',
    ]
    # The suffix pass above removed ``at age``; use the original remaining
    # statement to avoid turning a bare ordinary age into an examination age.
    candidate = statement
    candidate = re.sub(rf'^At {VISIT}, ', '', candidate, flags=re.I)
    candidate = re.sub(rf' (?:at|during) {VISIT}$', '', candidate, flags=re.I)
    for pattern in patterns:
        match = re.fullmatch(pattern, candidate, re.I)
        if match:
            return {'status': None, 'diseaseTerm': None, 'diseaseScopeBasis': None,
                    'attainedAge': int(match['age']), 'ageText': candidate,
                    'visit': visit, 'component': 'AGE'}
    return None


def _reference(source, unit, statement, start, end):
    return {'unitId': unit['unitId'], 'quote': statement, 'start': start, 'end': end,
            'sourceSha256': unit.get('sourceSha256'), 'pmcid': source['pmcid'],
            'bundleSha256': source['bundleSha256'], 'meaning': 'COMPLETE_NATIVE_CLINICAL_ASSERTION'}


def _unresolved_context(statement, member, previous, labels):
    """Recognize a clinical question needing review, never an accepted fact.

    The affirmative grammar above is intentionally narrower. Reusing only that
    grammar to search for adverse context would silently discard an unsupported
    follow-up, changed status, finding, or explicit disease-scope restriction.
    This separate finite reader requires a native subject/predicate connection,
    or a local observation referent; a disease word alone proves nothing.
    """
    restriction = (r'(?:This|That|The) (?:clinical )?'
        r'(?:status|observation|assessment|examination|visit|follow-up|finding|diagnosis|result) '
        r'(?:refers? to|concerns?|concerned|applies? to|related to|relates? to|pertains? to|'
        r'pertained to|was for|is for|was specific to|is specific to) .+')
    # A printed member scope remains explicit across native paragraphs; no
    # cross-paragraph pronoun resolution is needed for "For I:1, this status".
    if re.fullmatch(rf'(?:For|Regarding|Concerning) {_subject(member)}, {restriction}', statement, re.I):
        return {'components': ['DISEASE_SCOPE'], 'scopeBasis': 'EXPLICIT_MEMBER_OBSERVATION_REFERENT',
                'includePrevious': False}
    body = re.sub(r'^(?:At|During|By|On|After|Before) [^,;]{1,120}, ', '', statement, flags=re.I)
    explicit = re.match(rf'^{_subject(member)}(?:[’\']s)? (?P<predicate>.+)$', body, re.I)
    previous_labels = {label for label in labels if re.search(
        r'(?<![\w:-])' + re.escape(label) + r'(?![\w:-])', previous or '')}
    predicate, basis = None, None
    if explicit:
        predicate, basis = explicit['predicate'], 'EXPLICIT_NAMED_MEMBER_PREDICATE'
        # An explicitly named relative is a different subject. These sentences
        # must not become the named member's clinical observation or a bogus
        # contradiction of it.
        if re.match(r'(?:(?:mother|father|brother|sister|son|daughter|parent|child|relative)\b|'
                    r'(?:had|has) (?:a |an |one |two |three )?(?:siblings?|brothers?|sisters?|children)\b)', predicate, re.I):
            return None
    elif previous and member in previous_labels:
        # A literal "this status" restriction is linked to the preceding
        # observation, even when it was excluded from the model's quote. Other
        # pronouns remain a review question, not automatic coreference.
        modifier = re.fullmatch(restriction, statement, re.I)
        if modifier:
            return {'components': ['DISEASE_SCOPE'], 'scopeBasis': 'LOCAL_EXPLICIT_OBSERVATION_REFERENT',
                    'includePrevious': True}
        pronoun = re.match(r'^(?:He|She|This patient|The patient|The individual) (?P<predicate>.+)$', body, re.I)
        if pronoun:
            predicate = pronoun['predicate']
            basis = ('UNIQUE_PRECEDING_NATIVE_SUBJECT_REQUIRES_REVIEW' if len(previous_labels) == 1
                     else 'AMBIGUOUS_PRECEDING_NATIVE_SUBJECTS_REQUIRE_REVIEW')
    if not predicate:
        return None
    # A statement about a named second person is not propagated to this member.
    if any(label != member and re.search(r'(?<![\w:-])' + re.escape(label) + r'(?![\w:-])', predicate)
           for label in labels):
        return None
    clinical_terms = r'\b(?:clinically|affected|unaffected|healthy|asymptomatic|symptomatic|unwell|disease|diagnos\w*|onset|symptoms?|manifestations?)\b'
    status = (re.match(r'^(?:was|is|were|are|remained|remains|has been|had been|became|becomes|'
                       r'appeared|seemed|did not remain)\b', predicate, re.I)
              and re.search(clinical_terms, predicate, re.I))
    event = re.match(r'^(?:developed|develops|experienced|manifested|presented with|'
                     r'reported symptoms?|denied symptoms?)\b', predicate, re.I)
    history = (re.match(r'^(?:had|has|reported|denied)\b', predicate, re.I)
               and (re.search(clinical_terms, predicate, re.I)
                    or re.search(r'\b(?:at age|at the age|at visit|at follow-up)\b', predicate, re.I)))
    examination = (re.match(r'^(?:was|is|underwent|received|attended|returned|had|has|showed|revealed)\b', predicate, re.I)
                   and re.search(r'\b(?:examin\w*|clinically (?:evaluated|assessed)|follow-up|followed up|'
                                 r'echocardiogra\w*|ECHO|ECG|cardiological|MRI)\b', predicate, re.I))
    if status or event or history or examination:
        return {'components': ['UNRESOLVED_CLINICAL_CONTEXT'], 'scopeBasis': basis,
                'includePrevious': not bool(explicit)}
    return None


def _record(source, fact, parsed, identity, refs):
    status = parsed['status'] if fact['raw']['fieldId'] in {'F05.2', 'F05.3'} else None
    age = parsed['attainedAge']
    # An F05.2 proposal may cover an entire time-qualified clinical assertion.
    # A shortened status value, or an age-only F05.4 proposal, cannot silently
    # authorize an additional field merely because its broad quote includes it.
    if age is not None and fact['raw']['fieldId'] != 'F05.4':
        if not parsed['ageText'] or parsed['ageText'] not in fact['raw']['sourceValue']:
            age = None
    if status is None and age is None:
        return None
    observation = {'familyKey': fact['familyKey'], 'memberKey': fact['memberKey'],
                   'memberLabel': fact['raw']['memberLabel'], 'status': status,
                   'diseaseTerm': parsed['diseaseTerm'] if status else None,
                   'diseaseScopeBasis': parsed['diseaseScopeBasis'] if status else None,
                   'attainedAge': age, 'observationIdentity': identity,
                   'sourceObservationId': digest(canonical(identity)),
                   'statusFactIds': [fact['claimId']] if status else [],
                   'ageFactIds': [fact['claimId']] if age is not None else [],
                   'claimIds': [fact['claimId']], 'sourceRefs': _unique(refs),
                   'statusSourceRefs': _unique(refs) if status else [],
                   'ageSourceRefs': _unique(refs) if age is not None else [],
                   'bindingBasis': identity['kind'], 'issues': []}
    observation['id'] = digest(canonical([identity, observation['status'], observation['diseaseTerm'], age]))
    return observation


def _table_records(source, facts, reviewed):
    """Only explicit co-timed column headings; a common row alone is weaker."""
    by_unit = defaultdict(list)
    units = {u['unitId']: u for u in source['units']}
    for fact in facts:
        for ref in fact['sourceRefs']:
            by_unit[ref['unitId']].append((fact, ref))
    for table_index, table in enumerate(source.get('main', {}).get('tables', [])):
        headers = defaultdict(list)
        for row in table.get('rows', []):
            for cell in row.get('cells', []):
                if cell.get('header'):
                    headers[cell['sourceCellIndex']].append(cell)
        # Multi-level/merged headers require a different reader; do not flatten.
        if any(len(cells) != 1 for cells in headers.values()):
            continue
        headers = {i: cells[0] for i, cells in headers.items()}
        if any(h.get('rowspan', 1) != 1 or h.get('colspan', 1) != 1 for h in headers.values()):
            continue
        person_columns = [i for i, h in headers.items() if h['text'].strip().lower()
                          in {'family member', 'individual', 'individual id', 'patient id'}]
        if len(person_columns) != 1:
            continue
        for row_index, row in enumerate(table.get('rows', [])):
            cells = {c['sourceCellIndex']: c for c in row.get('cells', [])}
            if any(c.get('rowspan', 1) != 1 or c.get('colspan', 1) != 1 for c in row.get('cells', [])):
                continue
            person = cells.get(person_columns[0])
            if not person:
                continue
            for index, cell in cells.items():
                header = headers.get(index)
                if not header or cell.get('header'):
                    continue
                heading = header['text'].strip()
                age_header = re.fullmatch(rf'Age at (?P<visit>(?:last )?{EXAM}|{VISIT}) \(years\)', heading, re.I)
                state_header = re.fullmatch(rf'(?P<disease>.+?) status at (?P<visit>(?:last )?{EXAM}|{VISIT})', heading, re.I)
                if not age_header and not state_header:
                    continue
                visit = _visit_label((age_header or state_header)['visit'])
                for fact, ref in by_unit[cell['unitId']]:
                    if fact['raw']['memberLabel'] != person['text'].strip() or cell['text'].strip() not in ref['quote']:
                        continue
                    proof = [ref] + [{'unitId': c['unitId'], 'quote': c['text'],
                                      'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
                                      'meaning': 'EXPLICIT_TABLE_MEMBER_AND_OBSERVATION_HEADER'}
                                     for c in (person, header, headers[person_columns[0]])]
                    if not all(p['unitId'] in reviewed and p['unitId'] in units
                               and p['quote'] in units[p['unitId']]['text'] for p in proof):
                        continue
                    parsed = {'status': None, 'diseaseTerm': None, 'diseaseScopeBasis': None,
                              'attainedAge': None, 'ageText': cell['text'].strip()}
                    if age_header and fact['raw']['fieldId'] == 'F05.4':
                        if not re.fullmatch(r'\d{1,3}(?: years(?: old)?)?', cell['text'].strip()):
                            continue
                        parsed['attainedAge'] = int(cell['text'].strip().split()[0])
                    elif state_header and fact['raw']['fieldId'] in {'F05.2', 'F05.3'}:
                        text = cell['text'].strip().lower()
                        if text not in {'healthy', 'unaffected', 'affected'}:
                            continue
                        parsed.update(status='AFFECTED' if text == 'affected' else 'UNAFFECTED',
                                      diseaseTerm=state_header['disease'].strip(),
                                      diseaseScopeBasis='EXPLICIT_DISEASE_TERM')
                    else:
                        continue
                    identity = {'kind': 'EXPLICIT_SHARED_TABLE_OBSERVATION', 'pmcid': source['pmcid'],
                                'bundleSha256': source['bundleSha256'], 'familyKey': fact['familyKey'],
                                'memberKey': fact['memberKey'], 'tableIndex': table_index,
                                'rowIndex': row_index, 'visit': visit}
                    record = _record(source, fact, parsed, identity, proof)
                    if record:
                        yield record


def qualify_assessments(source, inventory, selection):
    """Re-read the native context of audited raw F05 facts, without scoring.

    The caller should use the same locally bound inventory as the native source
    qualifier. No fact is authorized by a model verdict alone: supported audit,
    exact native quote, reviewed scope, member scope, and this grammar all apply.
    """
    require(inventory['sourceIdentity']['bundleSha256'] == source['bundleSha256'], 'ASSESSMENT_SOURCE_MISMATCH')
    require(inventory['target']['geneSymbol'] == selection['geneSymbol'], 'ASSESSMENT_GENE_MISMATCH')
    result = {'schemaVersion': VERSION, 'sourceIdentity': deepcopy(inventory['sourceIdentity']),
              'target': deepcopy(selection),
              'observations': [], 'needs': [], 'businessPublishable': False}
    context_budget = {'scope': 'ALL_SUPPLIED_NATIVE_UNITS_ADVERSE_REVIEW_ONLY',
                      'positiveScope': 'REVIEWED_RAW_AUDIT_ONLY',
                      'maximumUnits': CONTEXT_MAX_UNITS, 'maximumCharacters': CONTEXT_MAX_CHARACTERS,
                      'suppliedUnits': len(source['units']),
                      'suppliedCharacters': sum(len(u.get('text', '')) for u in source['units'])}
    result['contextScan'] = context_budget
    if (context_budget['suppliedUnits'] > CONTEXT_MAX_UNITS
            or context_budget['suppliedCharacters'] > CONTEXT_MAX_CHARACTERS):
        context_budget['status'] = 'BUDGET_EXCEEDED'
        issue = _need('PP1_FORMAL_CLINICAL_CONTEXT_BUDGET_EXCEEDED',
            '完整已提供来源的临床反证核查超过显式预算，未截断或输出部分状态；须审查来源范围或执行预算。',
            actual=deepcopy(context_budget), route='METHOD_REVIEW')
        issue['expected'] = {'maximumUnits': CONTEXT_MAX_UNITS, 'maximumCharacters': CONTEXT_MAX_CHARACTERS}
        result['needs'].append(issue)
        return result
    context_budget['status'] = 'WITHIN_BUDGET'
    units = {u['unitId']: u for u in source['units']}
    reviewed = set(inventory['inputScope']['unitIds'])
    facts, covered = [], set()
    ownership = defaultdict(set)
    for label, family in inventory['families'].items():
        for member in family['members']:
            ownership[member].add(family.get('canonicalLabel') or ('UNRESOLVED:' + label))
    for fact in inventory['facts']:
        raw = fact['raw']
        if raw['fieldId'] not in FIELDS or raw['memberLabel'] == 'FAMILY':
            continue
        issues = set(fact.get('issues', [])) - IGNORED_ISSUES
        if (fact.get('verification') == 'REJECTED' or issues
                or fact.get('sourceAudit', {}).get('verdict') != 'SUPPORTED'
                or fact.get('sourceSupport') == 'ADDITION_REQUIRES_AUDIT'
                or len(ownership[raw['memberLabel']]) != 1):
            result['needs'].append(_need('PP1_FORMAL_ASSESSMENT_REQUIRES_SOURCE_REVIEW',
                '临床观察原始事实尚未通过来源、成员或新增事实审核，不能由上下文自授资格。',
                fact.get('familyKey'), fact.get('memberKey'),
                [{'claimIds': [fact['claimId']], 'sourceRefs': fact.get('sourceRefs', []),
                  'sourceValue': raw['sourceValue']}], actual={'issues': sorted(issues)}))
            continue
        checked = []
        for original in fact.get('sourceRefs', []):
            unit = units.get(original.get('unitId'))
            quote = original.get('quote', '')
            if (not unit or unit['unitId'] not in reviewed or not quote or quote not in unit['text']
                    or original.get('sourceSha256', unit.get('sourceSha256')) != unit.get('sourceSha256')
                    or original.get('pmcid', source['pmcid']) != source['pmcid']
                    or original.get('bundleSha256', source['bundleSha256']) != source['bundleSha256']):
                continue
            start = original.get('start')
            if start is None:
                if unit['text'].count(quote) != 1:
                    continue
                start = unit['text'].index(quote)
            if (type(start) is not int or start < 0 or unit['text'][start:start + len(quote)] != quote
                    or original.get('end', start + len(quote)) != start + len(quote)):
                continue
            checked.append({**deepcopy(original), 'start': start, 'end': start + len(quote),
                            'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256']})
        if not checked:
            result['needs'].append(_need('PP1_FORMAL_ASSESSMENT_SOURCE_CONTEXT_REQUIRED',
                '临床观察引用无法在已审核原文范围重放。', fact.get('familyKey'), fact.get('memberKey'),
                [{'claimIds': [fact['claimId']], 'sourceRefs': fact.get('sourceRefs', [])}]))
            continue
        checked_fact = {**fact, 'sourceRefs': checked}
        facts.append(checked_fact)
        emitted = False
        for ref in checked:
            unit = units[ref['unitId']]
            for statement, start, end in _sentences(unit['text']):
                # Fullmatch of the original sentence prevents a shortened quote
                # from erasing outer uncertainty, negation or another disease.
                if (statement not in ref['quote'] or start < ref['start'] or end > ref['end']
                        or not (raw['sourceValue'] in statement or statement in raw['sourceValue'])):
                    continue
                parsed = _parse(statement, raw['memberLabel'])
                if not parsed:
                    continue
                identity = {'kind': 'EXPLICIT_NAMED_VISIT' if parsed['visit'] else 'SAME_COMPLETE_SOURCE_ASSERTION',
                            'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
                            'familyKey': fact['familyKey'], 'memberKey': fact['memberKey']}
                if parsed['visit']:
                    identity['visit'] = parsed['visit']
                else:
                    identity.update(unitId=unit['unitId'], start=start, end=end)
                record = _record(source, checked_fact, parsed, identity,
                                 [ref, _reference(source, unit, statement, start, end)])
                if record:
                    result['observations'].append(record)
                    emitted = True
                    for component, value in [('STATUS', record['status']), ('AGE', record['attainedAge'])]:
                        if value is not None:
                            covered.add((fact['memberKey'], unit['unitId'], start, end, component))
        if not emitted:
            # An ordinary/onset age is known to be insufficient, not a claim
            # that the source lacks examination age anywhere else.
            ordinary = raw['fieldId'] == 'F05.4' and bool(re.search(
                r'\b(onset|first developed|years old|y\.o\.|aged)\b', raw['sourceValue'], re.I))
            result['needs'].append(_need(
                'PP1_FORMAL_ATTAINED_AGE_SEMANTICS_UNRESOLVED' if ordinary else 'PP1_FORMAL_ASSESSMENT_EXTRACTOR_UNSUPPORTED',
                '已审原始事实尚无本解析器支持的状态与同次检查/随访年龄联系；不是原件确缺声明。',
                fact.get('familyKey'), fact.get('memberKey'),
                [{'claimIds': [fact['claimId']], 'sourceRefs': checked, 'sourceValue': raw['sourceValue']}],
                actual={'fieldId': raw['fieldId'], 'sourceAvailability': 'FACT_PRESENT_BINDING_NOT_QUALIFIED'},
                blocking=raw['fieldId'] in {'F05.2', 'F05.3'}))
    result['observations'].extend(_table_records(source, facts, reviewed))
    result['observations'] = _unique(result['observations'])
    supported_claims = {claim for record in result['observations'] for claim in record['claimIds']}
    result['needs'] = [n for n in result['needs'] if n['reasonCode'] not in {
        'PP1_FORMAL_ASSESSMENT_EXTRACTOR_UNSUPPORTED', 'PP1_FORMAL_ATTAINED_AGE_SEMANTICS_UNRESOLVED'}
        or not all(claim in supported_claims for r in n['knownFacts'] for claim in r.get('claimIds', []))]
    # Read ALL supplied native units for adverse or later observations, including
    # units omitted from the audit input scope. Only needs may come from this
    # broader scope; positive facts above remain restricted to reviewed units.
    people = {(f['familyKey'], f['memberKey'], f['raw']['memberLabel']) for f in facts}
    labels = {label for roster in inventory['families'].values() for label in roster['members']}
    for family, member, label in sorted(people):
        for unit in source['units']:
            unit_id = unit['unitId']
            if label not in unit['text']:
                continue
            review_scope = ('INSIDE_AUDITED_INPUT_SCOPE' if unit_id in reviewed
                            else 'OUTSIDE_AUDITED_INPUT_SCOPE')
            sentences = list(_sentences(unit['text']))
            for index, (statement, start, end) in enumerate(sentences):
                parsed = _parse(statement, label)
                if not parsed:
                    previous = sentences[index - 1] if index else None
                    signal = _unresolved_context(statement, label, previous[0] if previous else None, labels)
                    if signal:
                        refs = [_reference(source, unit, statement, start, end)]
                        if signal['includePrevious'] and previous:
                            refs.insert(0, _reference(source, unit, *previous))
                        result['needs'].append(_need('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW',
                            '相关原文存在未被当前观察语法核定的临床事件、后续时点或疾病范围限制；须来源复核，不能忽略或自授新状态。',
                            family, member, [{'sourceRefs': refs}],
                            actual={'unreviewedComponents': signal['components'], 'scopeBasis': signal['scopeBasis'],
                                    'sourceReviewScope': review_scope,
                                    'sourceAvailability': 'NATIVE_CLINICAL_CONTEXT_PRESENT_SEMANTICS_UNRESOLVED'}))
                    continue
                missing = [component for component, value in [('STATUS', parsed['status']), ('AGE', parsed['attainedAge'])]
                           if value is not None and (member, unit_id, start, end, component) not in covered]
                if missing:
                    result['needs'].append(_need('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW',
                        '相关原文另有同成员临床观察或时点，尚无对应F05审核；保留原文并补核，不能自授或忽略。',
                        family, member, [{'sourceRefs': [_reference(source, unit, statement, start, end)]}],
                        actual={'unreviewedComponents': missing, 'sourceReviewScope': review_scope,
                                'sourceAvailability': 'EXPLICIT_NATIVE_CONTEXT_PRESENT'}))
            # A finite source-availability grammar for coordinated clinical
            # examinations. This does not assign the following test finding to
            # a disease state, nor upgrade an F07 relationship audit to F05.
            for statement, start, end in _sentences(unit['text']):
                match = re.fullmatch(r'The proband[’\']s (?P<people>.+) underwent a '
                                     r'(?:cardiological|clinical) examination', statement)
                if not match:
                    continue
                person = r'\d{1,3}-year-old (?:mother|father|brother|sister|relative) \([A-Za-z0-9:_-]+\)'
                if not re.fullmatch(person + r'(?: and ' + person + r')*', match['people']):
                    continue
                native_age = re.search(r'(?P<age>\d{1,3})-year-old [a-z]+ \(' + re.escape(label) + r'\)', match['people'])
                if native_age:
                    result['needs'].append(_need('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW',
                        '相关原文明确记录该成员检查时年龄，但当前F05审核尚未覆盖该观察；临床检测阴性也不自动等于整病未患病。',
                        family, member, [{'sourceRefs': [_reference(source, unit, statement, start, end),
                            {**_reference(source, unit, unit['text'], 0, len(unit['text'])),
                             'meaning': 'RELATED_EXAMINATION_AND_FINDING_CONTEXT_NOT_ADOPTED_STATUS'}]}],
                        actual={'unreviewedComponents': ['AGE', 'DISEASE_STATUS_MAPPING'],
                                'sourceReviewScope': review_scope,
                                'nativeExaminationAgeCandidate': int(native_age['age']),
                                'sourceAvailability': 'EXPLICIT_NATIVE_EXAMINATION_PRESENT_NOT_F05_REVIEWED'}))
    return result


def bind_formal_observations(qualified, policy):
    """Resolve explicit source observations against the independent disease policy.

    Multiple time points are retained and require a selection decision. No
    latest/oldest/maximum-age heuristic or legacy-cache migration is performed.
    Returned memberStates are the only source-bound states ready for the named
    phenotype model. Only AGE_INDEPENDENT_BINARY_STATUS waives attained age.
    """
    out = {'schemaVersion': VERSION, 'observations': [], 'memberStates': [], 'needs': []}
    age_required = policy.get('formalModel', {}).get('phenotypeModel', {}).get('kind') != 'AGE_INDEPENDENT_BINARY_STATUS'
    records = []
    for source in qualified:
        package = source.get('clinicalAssessments')
        if not isinstance(package, dict) or package.get('schemaVersion') != VERSION:
            clinical = [r for r in source.get('observations', []) if r.get('kind') in
                        {'clinical', 'diagnosis', 'nativePhenotypes', 'reportedDiagnosticCriteria'}]
            unresolved_clinical = [r for r in source.get('unresolved', [])
                                   if r.get('fieldId', '').startswith('F05.')]
            if clinical or unresolved_clinical:
                out['needs'].append(_need('PP1_FORMAL_OBSERVATION_BINDING_REQUIRED',
                    '旧来源结果没有单次临床观察身份，需从已审原始资料重新资格审核，不能拼接独立状态和年龄。',
                    records=clinical + unresolved_clinical, actual='SOURCE_REPLAY_REQUIRED'))
            continue
        if package.get('sourceIdentity') != source.get('sourceIdentity'):
            out['needs'].append(_need('PP1_FORMAL_ASSESSMENT_SOURCE_IDENTITY_MISMATCH',
                '临床观察包与当前原件身份不一致，需重新重放。', actual=package.get('sourceIdentity')))
            continue
        if 'target' in source and package.get('target') != source['target']:
            out['needs'].append(_need('PP1_FORMAL_ASSESSMENT_TARGET_MISMATCH',
                '临床观察包没有绑定当前精确目标，需以当前选择从已审原文重新生成。',
                actual={'expectedTarget': source['target'], 'packetTarget': package.get('target')}))
            continue
        for need in deepcopy(package.get('needs', [])):
            if (not age_required and need['reasonCode'] == 'PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW'
                    and need.get('actual', {}).get('unreviewedComponents') == ['AGE']):
                # Keep the missing age review visible; it cannot affect a model
                # that explicitly adopts age-independent status probabilities.
                need['blockingEffect'] = 'MAY_ADD_INFORMATION'
            out['needs'].append(need)
        for record in deepcopy(package.get('observations', [])):
            identity = record.get('observationIdentity', {})
            if (identity.get('pmcid') != package['sourceIdentity'].get('pmcid')
                    or identity.get('bundleSha256') != package['sourceIdentity'].get('bundleSha256')):
                out['needs'].append(_need('PP1_FORMAL_ASSESSMENT_SOURCE_IDENTITY_MISMATCH',
                    '临床观察记录与该原件的身份不一致。', record.get('familyKey'), record.get('memberKey'), [record]))
                continue
            records.append(record)
    groups = defaultdict(list)
    for record in _unique(records):
        identity = record.get('observationIdentity', {})
        if (not identity or record.get('sourceObservationId') != digest(canonical(identity))
                or identity.get('familyKey') != record.get('familyKey')
                or identity.get('memberKey') != record.get('memberKey')
                or (record.get('attainedAge') is not None and (type(record['attainedAge']) is not int
                    or not 0 <= record['attainedAge'] <= 999))
                or not record.get('claimIds') or not record.get('sourceRefs')
                or not all(ref.get('pmcid') == identity.get('pmcid')
                           and ref.get('bundleSha256') == identity.get('bundleSha256')
                           for ref in record['sourceRefs'])):
            out['needs'].append(_need('PP1_FORMAL_OBSERVATION_BINDING_REQUIRED',
                '临床观察缺少可重放的身份、审核事实或完整来源。',
                record.get('familyKey'), record.get('memberKey'), [record]))
            continue
        groups[(record['familyKey'], record['memberKey'], record['sourceObservationId'])].append(record)
    by_member = defaultdict(list)
    terms = set(policy.get('affectedTerms', []))
    for (family, member, observation), parts in sorted(groups.items()):
        statuses = [p for p in parts if p.get('status')]
        disease_terms = {p['diseaseTerm'] for p in statuses if p.get('diseaseTerm')}
        values = {p['status'] for p in statuses}
        ages = {p['attainedAge'] for p in parts if p.get('attainedAge') is not None}
        merged = {'familyKey': family, 'memberKey': member, 'memberLabel': parts[0]['memberLabel'],
                  'sourceObservationId': observation, 'observationIdentity': parts[0]['observationIdentity'],
                  'assessmentIds': sorted({p['id'] for p in parts}),
                  'status': next(iter(values)) if len(values) == 1 else None,
                  'attainedAge': next(iter(ages)) if len(ages) == 1 else None,
                  'diseaseTerms': sorted(disease_terms),
                  'statusFactIds': sorted({f for p in parts for f in p['statusFactIds']}),
                  'ageFactIds': sorted({f for p in parts for f in p['ageFactIds']}),
                  'claimIds': sorted({f for p in parts for f in p['claimIds']}),
                  'sourceRefs': _unique([r for p in parts for r in p['sourceRefs']]),
                  'bindingBasis': parts[0]['bindingBasis'], 'issues': []}
        if len(values) > 1 or len(ages) > 1 or len(disease_terms) > 1:
            merged['issues'].append('CONFLICTING_SAME_OBSERVATION')
            out['needs'].append(_need('PP1_FORMAL_SAME_OBSERVATION_CONFLICT',
                '同一次临床观察的疾病、状态或年龄冲突，不能挑选有利值。', family, member, parts,
                actual={'statuses': sorted(values), 'attainedAges': sorted(ages), 'diseaseTerms': sorted(disease_terms)}))
        elif disease_terms and not disease_terms <= terms:
            merged['issues'].append('DISEASE_SCOPE_UNRESOLVED')
            out['needs'].append(_need('PP1_FORMAL_ASSESSMENT_DISEASE_SCOPE_UNRESOLVED',
                '原文疾病与当前受审患病定义不一致或尚未映射，不能借用其状态或年龄。', family, member, parts,
                actual={'diseaseTerms': sorted(disease_terms), 'affectedTerms': sorted(terms)}, route='KNOWLEDGE'))
        elif merged['status'] == 'AFFECTED' and not disease_terms:
            merged['issues'].append('AFFECTED_DEFINITION_UNRESOLVED')
            out['needs'].append(_need('PP1_FORMAL_DIAGNOSTIC_DEFINITION_REQUIRED',
                '一般受累描述尚未对应当前精确疾病定义。', family, member, parts, route='KNOWLEDGE'))
        elif not statuses and not age_required:
            merged['issues'].append('NO_REPORTED_DISEASE_STATUS')
        elif not statuses or (age_required and not ages):
            merged['issues'].append('STATUS_AGE_NOT_BOUND')
            out['needs'].append(_need('PP1_FORMAL_STATUS_AGE_OBSERVATION_REQUIRED',
                '状态与检查/随访年龄没有同次观察的来源联系；同成员或同段落不足以建立该联系。',
                family, member, parts, actual={'hasStatus': bool(statuses), 'hasAttainedAge': bool(ages)}))
        out['observations'].append(merged)
        if age_required or statuses:
            by_member[(family, member)].append(merged)
    for (family, member), observations in sorted(by_member.items()):
        usable = [r for r in observations if not r['issues']]
        if len(observations) > 1:
            out['needs'].append(_need('PP1_FORMAL_MULTIPLE_CLINICAL_OBSERVATIONS_REQUIRE_SELECTION',
                '同一成员存在不同临床观察；当前方法尚无受审的时点选择，不自动采用最近或最大年龄。',
                family, member, observations,
                actual={'sourceObservationIds': [r['sourceObservationId'] for r in observations]}))
            continue
        pending = any(n.get('blockingEffect') == 'BLOCKS_SCORING'
                      and n.get('scope', {}).get('familyKey') in (None, family)
                      and n.get('scope', {}).get('memberKey') in (None, member)
                      for n in out['needs'])
        if len(usable) == 1 and not pending:
            out['memberStates'].append(deepcopy(usable[0]))
    out['needs'] = _unique(out['needs'])
    return out
