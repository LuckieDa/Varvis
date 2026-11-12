"""Loss-preserving Stage 1 projection; never verifies, adopts or scores a case.

The normal caller supplies a source reconstructed by bundle_model_contract.load
and reviews replayed from receipts. Literal candidates stay separate from typed
facts. Existing native inferences are retained as UNVERIFIED for the next stage.
"""
from copy import deepcopy
import hashlib
from pathlib import Path

from model_literature_contract import canonical, digest
from core_material_contract import validate, context_binding, digest as object_digest
from project_core_material import project, observed
from prepare_literature import prepare

VERSION = 'denovo-core-material-extraction-1.0.0'
MAX_PACKAGE = 32 * 1024 * 1024


def require(value, code):
    if not value:
        raise ValueError('DENOVO_CORE_EXTRACTION_' + code)


def destination(name, subject):
    """Routing of literal claims, not interpretation of results or relationships."""
    fixed = {
        'geneSymbol': ('gene', 'symbol', False),
        'variantText': ('variant', 'representations', True),
        'proteinChange': ('variant', 'representations', True),
        'transcript': ('variant', 'representations', True),
        'reportedGenomicObservation': ('variant', 'representations', True),
        'reportedFamilyId': ('case', 'familyId', False),
        'reportedVariantTestingMethod': ('testing', 'method', False),
        'reportedTestingLimitations': ('testing', 'limitations', True),
        'reportedParentageTestingMethod': ('parentage', 'method', False),
        'reportedFamilyHistory': ('family', 'history', False),
        'reportedSiblings': ('family', 'siblings', True),
        'reportedPreviousPublication': ('overlap', 'previousReports', True),
    }
    patient = {'reportedProbandGenotype': ('proband', 'genotype', False),
               'reportedPhenotype': ('phenotype', 'positive', True),
               'reportedPhenotypeNegation': ('phenotype', 'negative', True),
               'reportedDiagnosis': ('phenotype', 'diagnosis', False),
               'reportedDiagnosisCertainty': ('phenotype', 'diagnosisCertainty', False),
               'reportedAge': ('phenotype', 'age', False), 'reportedOnset': ('phenotype', 'onset', False)}
    if name in patient:
        return [patient[name]] if subject == 'PROBAND' else []
    if name in fixed:
        return [fixed[name]]
    for person in ('Proband', 'Mother', 'Father'):
        if name == 'reported' + person + 'TargetResult' or (person != 'Proband' and name == 'reported' + person + 'Genotype'):
            return [(person.lower(), 'targetResult', None)] if subject == person.upper() else []
    if name == 'reportedMosaicism' and subject in ('PROBAND', 'MOTHER', 'FATHER'):
        return [(subject.lower(), 'mosaicism', None)]
    if name == 'reportedTestedParticipants':
        return [('testing', 'participants', None)]
    if name == 'reportedMaternity' and subject == 'MOTHER':
        return [('parentage', 'maternity', None)]
    if name == 'reportedPaternity' and subject == 'FATHER':
        return [('parentage', 'paternity', None)]
    if name in ('reportedParentage', 'reportedParentageTestingResult'):
        return ([('parentage', 'maternity', None), ('parentage', 'paternity', None)] if subject == 'BOTH_PARENTS' else
                [('parentage', 'maternity', None)] if subject == 'MOTHER' else
                [('parentage', 'paternity', None)] if subject == 'FATHER' else [])
    return []


def write_package(source, source_facts, reviews, output, intake=None, data_class='PUBLIC_PAPER_RESEARCH'):
    """Write a new task-local package. No mutation of originals or success cache."""
    raw = source['raw']
    pin = digest(raw)
    require(source_facts['pmcid'] == source['pmcid'] and source_facts['sourceSha256'] == pin, 'SOURCE_MISMATCH')
    main = prepare(raw, source['pmcid'], pin)
    require(main == source['main'], 'MAIN_SOURCE_RECONSTRUCTION_MISMATCH')
    rows = source_facts['cases']
    require(len(rows) <= 128 and len({r['caseLabel'] for r in rows}) == len(rows), 'CASE_LIMIT_OR_DUPLICATE')
    for review in reviews:
        require(review['pmcid'] == source['pmcid'] and review['bundleSha256'] == source['bundleSha256'], 'REVIEW_SOURCE_MISMATCH')
    known_labels = {r['caseLabel'] for r in rows}
    require(all(r['caseLabel'] in known_labels for review in reviews for r in review['cases']), 'REVIEW_CASE_NOT_IN_FACTS')
    units = {u['unitId']: u for u in source['units']}
    require(len(units) == len(source['units']), 'DUPLICATE_UNIT')
    main_units = {u['unitId']: u for u in main['units']}
    require(all(units.get(k, {}).get('text') == v['text'] and units[k]['sourceSha256'] == pin
                for k, v in main_units.items()), 'SOURCE_UNIT_MISMATCH')
    observations = []
    by_case = {}
    for case in rows:
        label, gathered = case['caseLabel'], []
        gathered += [(f, 'NATIVE_OR_QUALIFIED_SOURCE_VIEW') for f in case.get('fields', [])]
        gathered += [(f, 'PRESERVED_MODEL_PROPOSAL') for r in case.get('candidateProvenance', []) for f in r['fields']]
        gathered += [(f['field'], 'DEFERRED_OR_NOT_ADOPTED_FIELD') for f in case.get('fieldLimitations', [])]
        gathered += [(f, 'REPLAYED_MODEL_PROPOSAL') for review in reviews for r in review['cases']
                     if r['caseLabel'] == label for f in r['fields']]
        # Exact duplicate claims are one stored claim with all origin labels;
        # this is not clinical event deduplication or cross-publication merging.
        unique = {}
        for field, origin in gathered:
            key = digest(canonical(field))
            entry = unique.setdefault(key, {'rawField': deepcopy(field), 'origins': []})
            if origin not in entry['origins']:
                entry['origins'].append(origin)
        require(len(unique) <= 256, 'CASE_CLAIM_LIMIT_NOT_TRUNCATED')
        indices = []
        for entry in unique.values():
            field = entry['rawField']
            citation = field.get('citation', {})
            uid, quote = citation.get('unitId'), citation.get('quote')
            require(uid in units and citation.get('sourceSha256') == units[uid]['sourceSha256']
                    and isinstance(quote, str) and quote and quote in units[uid]['text']
                    and isinstance(field.get('sourceValue'), str) and field['sourceValue'] in quote,
                    'CITATION_MISMATCH')
            # Source-native table fields have implicit proband ownership. Model
            # fields keep their explicit subject; never assign UNCLEAR to a parent.
            subject = field.get('subject', 'PROBAND')
            routes = destination(field['field'], subject)
            item = {**entry, 'caseKey': source['pmcid'] + '#' + label, 'subject': subject,
                    'destinations': [{'group': g, 'field': n} for g, n, _ in routes],
                    'disposition': 'CANDIDATE_NOT_VERIFIED' if routes else 'PRESERVED_UNASSIGNED_OR_NO_TYPED_FIELD'}
            indices.append(len(observations)); observations.append(item)
        by_case[label] = indices
    scope = {'pmcid': source['pmcid'], 'sourceSha256': pin, 'bundleSha256': source.get('bundleSha256'),
             'coverage': deepcopy(source.get('coverage', {})), 'attachments': deepcopy(source.get('attachments', [])),
             'reviews': deepcopy(reviews), 'stage': 'EXTRACTION_ONLY', 'clinicalAdoption': 'NONE',
             'intakeSha256': digest(canonical(intake)) if intake is not None else None,
             'scopeMeaning': 'Only supplied targets and previously extracted facts; not a complete-paper claim'}
    scope['paperAvailability'] = ('MAIN_AVAILABLE' if scope['coverage'].get('status') == 'NO_EXPLICIT_SUPPLEMENT_SIGNAL'
                                  and not scope['attachments'] else 'PARTIAL')
    unit_view = [{'unitId': u['unitId'], 'sourceSha256': u['sourceSha256'], 'text': u['text'],
                  'metadata': {k: v for k, v in u.items() if k not in ('text', 'unitId', 'sourceSha256')}}
                 for u in source['units']]
    file_values = {'main.xml': raw, 'source-facts.json': canonical(source_facts),
                   'source-units.json': canonical(unit_view), 'observations.json': canonical(observations),
                   'source-scope.json': canonical(scope)}
    if intake is not None:
        file_values['original-intake.json'] = canonical(intake)
    require(sum(len(b) for b in file_values.values()) <= MAX_PACKAGE, 'PACKAGE_LIMIT_NOT_TRUNCATED')
    specs = [('paper', 'main.xml', 'JATS', 'PAPER_PRIMARY'),
             ('legacy-facts', 'source-facts.json', 'JSON', 'DERIVED_FACTS'),
             ('claims', 'observations.json', 'JSON', 'DERIVED_FACTS'),
             ('units', 'source-units.json', 'JSON', 'DERIVED_FACTS'),
             ('scope', 'source-scope.json', 'JSON', 'DERIVED_FACTS')]
    manifest = {sid: {'path': name, 'sha256': digest(file_values[name]), 'format': fmt, 'role': role,
                      'pmcid': source['pmcid'] if fmt == 'JATS' else None} for sid, name, fmt, role in specs}
    unit_indices = {u['unitId']: i for i, u in enumerate(unit_view)}
    materials = []
    for case in rows:
        doc = project(source_facts, case['caseLabel'], manifest, 'legacy-facts')
        doc['dataClass'] = data_class
        grouped = {}
        for i in by_case[case['caseLabel']]:
            field = observations[i]['rawField']; subject = observations[i]['subject']
            for group, name, is_array in destination(field['field'], subject):
                grouped.setdefault((group, name, is_array), []).append(i)
        for (group, name, is_array), indices in grouped.items():
            current = doc['groups'][group][name]
            claims = [observations[i]['rawField']['sourceValue'] for i in indices]
            require(all(isinstance(v, str) and v.strip() for v in claims), 'EMPTY_OR_INVALID_CLAIM')
            unique_values = list(dict.fromkeys(claims))
            refs = []
            for i in indices:
                field = observations[i]['rawField']
                refs.append({'sourceId': 'claims', 'kind': 'JSON_POINTER',
                             'locator': f'/{i}/rawField/sourceValue', 'quote': field['sourceValue']})
                citation = field.get('citation', {})
                uid, quote = citation.get('unitId'), citation.get('quote')
                if uid in units and isinstance(quote, str) and quote:
                    require(citation.get('sourceSha256') == units[uid]['sourceSha256'] and quote in units[uid]['text'], 'CITATION_MISMATCH')
                    if uid in main_units:
                        refs.append({'sourceId': 'paper', 'kind': 'JATS_UNIT', 'locator': uid, 'quote': quote})
                    else:
                        refs.append({'sourceId': 'units', 'kind': 'JSON_POINTER',
                                     'locator': f'/{unit_indices[uid]}/text', 'quote': quote})
            subject = (group.upper() if group in ('proband', 'mother', 'father') else
                       'MOTHER' if group == 'parentage' and name == 'maternity' else
                       'FATHER' if group == 'parentage' and name == 'paternity' else
                       observations[indices[0]]['subject'])
            if current['value'] is None:
                value = unique_values if is_array is True else unique_values[0] if is_array is False and len(unique_values) == 1 else None
            else:
                # Keep prior program-derived proposal, not a model replacement.
                value = list(dict.fromkeys(current['value'] + unique_values)) if is_array is True else current['value']
                refs = current['citations'] + refs
            f = observed(doc['caseKey'], subject, value,
                         unique_values[0] if len(unique_values) == 1 else canonical(unique_values).decode('utf-8'),
                         list({canonical(r): r for r in refs}.values()),
                         'PROGRAM_DERIVED' if current['value'] is not None else 'MODEL_PROPOSAL')
            f['issues'] = list(current['issues'])
            if len(unique_values) > 1 and is_array is not True:
                # Preserve all alternatives; do not select a preferred claim.
                f['value'] = None
                f['issues'].append('MULTIPLE_SOURCE_VALUES_REQUIRE_RECONCILIATION')
            if is_array is None:
                f['issues'].append('RAW_ASSERTION_REQUIRES_RESULT_OR_SUBJECT_INTERPRETATION')
            if current['verification'] == 'CONFLICT':
                f['verification'] = 'CONFLICT'
            doc['groups'][group][name] = f
        # Origin is a proposal, never primary-cohort qualification.
        origin_rows = [r for review in reviews for r in review['cases'] if r['caseLabel'] == case['caseLabel']]
        origin_refs = [{'sourceId': 'scope', 'kind': 'JSON_POINTER',
                        'locator': f'/reviews/{i}/cases/{j}/caseOriginProposal', 'quote': r['caseOriginProposal']}
                       for i, review in enumerate(reviews) for j, r in enumerate(review['cases'])
                       if r['caseLabel'] == case['caseLabel']]
        if not origin_rows:
            origin_rows = case.get('candidateProvenance', [])
            origin_refs = [{'sourceId': 'legacy-facts', 'kind': 'JSON_POINTER',
                            'locator': f'/cases/{rows.index(case)}/candidateProvenance/{i}/caseOriginProposal',
                            'quote': r['caseOriginProposal']} for i, r in enumerate(origin_rows)]
        origins = list(dict.fromkeys(r['caseOriginProposal'] for r in origin_rows))
        if origins:
            doc['groups']['case']['origin'] = observed(doc['caseKey'], 'PROBAND', origins[0] if len(origins) == 1 else None,
                                                       canonical(origins).decode(), origin_refs, 'MODEL_PROPOSAL')
            if len(origins) > 1:
                doc['groups']['case']['origin']['issues'] = ['CONFLICTING_ORIGIN_PROPOSALS']
        attachment_names = [a['originalName'] for a in scope['attachments']]
        for name, value, loc in [('availability', scope['paperAvailability'], '/paperAvailability'),
                                 ('supplements', attachment_names, '/attachments')]:
            from core_material_contract import pointer
            v = pointer(scope, loc); text = v if isinstance(v, str) else canonical(v).decode()
            doc['groups']['paper'][name] = observed(doc['caseKey'], 'PAPER', value, text,
                [{'sourceId': 'scope', 'kind': 'JSON_POINTER', 'locator': loc, 'quote': text}], 'PROGRAM_DERIVED')
        # A target sent to a model is not a completed source review.
        doc['reviewPlan']['unprocessedScope'] += ['EXTRACTED_TARGET_NOT_YET_REVIEWED:' + u for u in dict.fromkeys(
            u for review in reviews for u in review.get('workUnit', {}).get('targetUnitIds', []))]
        doc['reviewPlan']['unavailableSources'] = [a['originalName'] + ':' + a['status'] for a in scope['attachments']
            if a['status'] not in {'CELLS_READ_NOT_SEMANTICALLY_INTERPRETED', 'TEXT_READ_NOT_SEMANTICALLY_INTERPRETED'}]
        if scope['coverage'].get('status') == 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED':
            doc['reviewPlan']['unavailableSources'].append('SUPPLEMENT_SCOPE:' + scope['coverage'].get('reasonCode', 'UNAVAILABLE_OR_UNPARSED'))
        doc['reviewPlan']['unprocessedScope'].append('STAGE1_NO_SECOND_PASS_OR_QUALIFICATION; subject, negation and field meanings may remain unresolved')
        filename = 'case-' + hashlib.sha256(doc['caseKey'].encode()).hexdigest()[:20] + '.json'
        materials.append((filename, doc))
    output = Path(output)
    require(not output.exists(), 'NEW_OUTPUT_REQUIRED')
    material_bytes = [(filename, doc, canonical(doc)) for filename, doc in materials]
    require(sum(len(b) for b in file_values.values()) + sum(len(b) for _, _, b in material_bytes)
            <= MAX_PACKAGE, 'PACKAGE_LIMIT_NOT_TRUNCATED')
    output.mkdir(parents=True, exist_ok=False)
    for name, body in file_values.items():
        (output / name).write_bytes(body)
    entries = []
    for filename, doc, body in material_bytes:
        # Empty ledger: structural and source integrity only. All facts remain
        # UNVERIFIED/CONFLICT and cannot accidentally claim a scientific review.
        ledger = {'schemaVersion': 'denovo-core-review-ledger-v1', 'scope': 'OFFLINE_CONTRACT_VALIDATION_ONLY',
                  'contextBinding': context_binding(doc), 'checks': {}}
        validate(doc, output, ledger, object_digest(ledger))
        (output / filename).write_bytes(body)
        entries.append({'caseKey': doc['caseKey'], 'path': filename, 'sha256': digest(body),
                        'status': 'EXTRACTED_NOT_VERIFIED', 'claimCount': len(by_case[doc['caseKey'].split('#', 1)[1]]),
                        'notExtracted': [g + '.' + n for g, fields in doc['groups'].items() for n, f in fields.items()
                                         if f['availability'] == 'NOT_EXTRACTED'],
                        'unassignedClaims': [i for i in by_case[doc['caseKey'].split('#', 1)[1]] if not observations[i]['destinations']]})
    result = {'schemaVersion': 'denovo-core-extraction-package-v1', 'version': VERSION, 'pmcid': source['pmcid'],
              'bundleSha256': source.get('bundleSha256'), 'cases': entries, 'sourceFactsSha256': digest(file_values['source-facts.json']),
              'observationsSha256': digest(file_values['observations.json']), 'sourceScopeSha256': digest(file_values['source-scope.json']),
              'originalIntakeSha256': scope['intakeSha256'], 'businessPublishable': False,
              'clinicalAdoption': 'NONE', 'scientificExecution': False, 'modelCalls': 0, 'networkCalls': 0,
              'missingMeaning': 'NOT_EXTRACTED_IS_NOT_PROOF_OF_ABSENCE', 'status': 'EXTRACTED_NOT_VERIFIED'}
    (output / 'index.json').write_bytes(canonical(result))
    return result
