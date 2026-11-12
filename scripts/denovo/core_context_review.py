"""Stage 3: bind source-reviewed core facts to local identity and policy context.

Produces a new immutable offline package. Event deduplication, scoring and final
Top-N selection matching remain downstream; businessPublishable is always false.
"""
import argparse
from copy import deepcopy
from pathlib import Path
import re

from model_literature_contract import canonical, digest, parse
from core_material_contract import bounded, pointer, assess, context_binding, digest as object_digest
from core_material_review import read_input, review_case, implementation, safe_file, MAX_PACKAGE
from core_local_identity import LocalIdentity, pinned, require
from core_context_policy import resolve_context
from project_core_material import observed, missing
from qualify_literature_cases import source_case, matches, FAMILY_SENTENCES, CASE_TITLE, sentences, cite, GROUP, group_labels
from literature_case_structure import tables, MENTION, paragraph_label
from core_case_semantics import enrich as enrich_case_semantics, owned_paragraphs
from core_source_review import prose_assertions

VERSION = 'denovo-core-context-review-1.6.0'

UNAFFECTED_PARENTS = [('EXPLICIT_UNAFFECTED_PARENTS_AD', re.compile(
    r'(?:Both parents|The mother and father) (?:were|are) (?:clinically )?unaffected\.'))]


def family_review(document, label, legacy, inheritance=None):
    """Bounded original-source assessment, never unknown-history-as-negative.

    Explicit no-related-history or, in AD, unaffected parents can provide a
    basis. Negative variant tests alone cannot. Nonspecific unassigned cohort
    history is retained as a warning, never assigned to a case or called absent.
    """
    result = {'qualified': False, 'reasonCode': None, 'explicitHistoryProofs': [],
              'unassignedHistory': [], 'targetHistory': [], 'unassignedSpecialHistory': [],
              'unaffectedParentProofs': [], 'warnings': [], 'qualificationBasis': None,
              'missingRequirements': [], 'checkedUnitIds': [],
              'scope': 'MAIN_RESULTS_CASE_REPORTS_AND_UNSECTIONED_BODY_NOT_ALL_PEDIGREES_OR_ATTACHMENTS'}
    units = [u for u in document['units'] if u['kind'] in {'BODY_PARAGRAPH', 'CAPTION'} and
             (not u['sections'] or any(s.lower() in {'results', 'case reports', 'case report', 'clinical report'} for s in u['sections']))
             and not any(re.search(r'discussion|references|methods|background', s, re.I) for s in u['sections'])]
    result['checkedUnitIds'] = [u['unitId'] for u in units]
    if len(units) > 96 or sum(len(u['text'].encode('utf-8')) for u in units) > 65536:
        result['reasonCode'] = 'DENOVO_FAMILY_REVIEW_SCOPE_LIMIT'
        return result
    checked_ids = set(result['checkedUnitIds'])
    owned = [u for u in owned_paragraphs(document, label) if u['unitId'] in checked_ids]
    own_ids = {u['unitId'] for u in owned}
    prose = prose_assertions(document, label, owned)
    result['explicitHistoryProofs'] = matches(document, owned, FAMILY_SENTENCES) + prose['normalHistory']
    result['unresolvedOwnedContext'] = prose['unresolved']
    result['unaffectedParentProofs'] = prose['unaffectedParents'] if inheritance == 'AD' else []
    family = re.compile(r'\b(?:mothers?|fathers?|parents?|maternal|paternal|siblings?|brothers?|sisters?|families|family)\b', re.I)
    concern = re.compile(r'\b(?:miscarriages?|congenital|mosaic(?:ism)?|recurrence|recurrent|abnormalit\w*|symptoms?|disease)\b'
                         r'|\baffected\s+(?:mothers?|fathers?|parents?|siblings?|brothers?|sisters?|relatives?)\b'
                         r'|\b(?:mothers?|fathers?|parents?|siblings?|brothers?|sisters?|relatives?)\s+'
                         r'(?:was|were|is|are)\s+(?:similarly\s+)?affected\b', re.I)
    special = re.compile(r'\b(?:mosaic(?:ism)?|affected|symptoms?|disease|inherited|inheritance)\b', re.I)
    universal = re.compile(r'\b(?:all|every|each)\s+(?:the\s+)?(?:mothers?|fathers?|parents?|families)\b', re.I)
    for u in units:
        title = CASE_TITLE.fullmatch(u['sections'][-1]) if u['sections'] else None
        other_label = paragraph_label(u)
        if u['unitId'] in own_ids or (title and title[1] != label) or (other_label and other_label != label):
            continue
        for start, end, sentence in sentences(u['text']):
            named = {m[1] for m in MENTION.finditer(sentence)}
            groups = list(re.finditer(GROUP, sentence))
            if groups:
                named.update(v for g in groups for v in group_labels(g[1]))
            # Explicit other-case observations are not transferred to this case.
            # A group or unassigned maternal statement is not assigned by its
            # proximity to a preceding sentence mentioning the target patient.
            if named and label not in named:
                continue
            if family.search(sentence) and concern.search(sentence):
                ref = cite(document, u, start, end)
                if named:
                    result['targetHistory'].append(ref)
                else:
                    result['unassignedHistory'].append(ref)
                    # Universal cohort claims may include this case; do not
                    # downgrade them to a nonspecific unassigned warning.
                    if special.search(sentence) or universal.search(sentence):
                        result['unassignedSpecialHistory'].append(ref)
    if result['unassignedHistory']:
        result['warnings'].append('UNASSIGNED_HISTORY_RETAINED_WITHOUT_CASE_ATTRIBUTION')
    if len(result['unassignedHistory']) > len(result['unassignedSpecialHistory']):
        result['warnings'].append('UNASSIGNED_NONSPECIFIC_HISTORY_NOT_CASE_CONFLICT')
    if result['targetHistory'] or any(c.get('contextConcerns') for c in legacy):
        result['reasonCode'] = 'DENOVO_FAMILY_SPECIAL_OR_CONFLICT_REVIEW_REQUIRED'
    elif result['unassignedSpecialHistory']:
        result['reasonCode'] = 'DENOVO_FAMILY_HISTORY_CASE_ASSIGNMENT_UNRESOLVED'
    elif len(legacy) > 1 or (not legacy and not owned):
        result['reasonCode'] = 'DENOVO_IDENTITY_FAMILY_NARRATIVE_READER_UNSUPPORTED'
    else:
        previous = legacy[0] if legacy else {}
        history = [f for f in previous.get('fields', []) if f['field'] == 'reportedFamilyHistory']
        table_none = previous.get('familyHistory') == 'REPORTED_NONE'
        basis = ('EXPLICIT_NO_RELATED_HISTORY' if table_none or (not history and result['explicitHistoryProofs']) else
                 'EXPLICIT_UNAFFECTED_PARENTS_AD' if not history and result['unaffectedParentProofs'] else None)
        if not history and prose['normalHistory']:
            basis = 'EXPLICIT_NORMAL_FAMILY_HISTORY'
        # Unknown/positive table values cannot be overwritten by healthy-parent
        # prose. No statement of compatibility remains a real missing fact.
        if basis is None:
            result['reasonCode'] = 'DENOVO_FAMILY_HISTORY_NOT_ESTABLISHED'
            result['missingRequirements'] = ['CASE_SPECIFIC_COMPATIBLE_HISTORY_OR_AD_UNAFFECTED_PARENTS']
        else:
            # Accept only the newly supported complete clinical sentences among
            # the legacy reader's unknown clauses, never arbitrary parent prose.
            accepted = {canonical(p['citation']) for p in result['unaffectedParentProofs'] + prose['normalHistory']}
            accepted.update(canonical(r) for r in prose['understood'])
            unresolved = [r for r in previous.get('unresolvedContext', prose['unresolved']) if canonical(r) not in accepted]
            has_context = not legacy or previous.get('contextVerification') in ('SUPPORTED_EXPLICIT_FAMILY_STATEMENTS', 'UNRESOLVED')
            if not has_context or unresolved:
                result['reasonCode'] = 'DENOVO_IDENTITY_FAMILY_NARRATIVE_READER_UNSUPPORTED'
            else:
                result['qualified'] = True
                result['qualificationBasis'] = basis
    return result


def load_config(root, spec):
    raw = pinned(Path(root).resolve(strict=True), spec, 65536); config = parse(raw)
    require(config.get('schemaVersion') == 'denovo-core-context-config-v1'
            and set(config) <= {'schemaVersion', 'identity', 'contexts', 'specialEventSources'}
            and type(config.get('contexts', [])) is list and len(config.get('contexts', [])) <= 100, 'CONTEXT_CONFIG_INVALID')
    event_sources = config.get('specialEventSources', [])
    require(type(event_sources) is list and len(event_sources) <= 128
            and all(type(e) is dict and type(e.get('caseKey')) is str for e in event_sources)
            and len({e['caseKey'] for e in event_sources}) == len(event_sources), 'SPECIAL_EVENT_CONFIG_INVALID')
    return config, digest(raw)


def read_review(source, package, pin):
    root = Path(package).resolve(strict=True)
    raw = bounded(root / 'index.json'); require(digest(raw) == pin, 'SOURCE_REVIEW_INDEX_PIN_MISMATCH')
    index = parse(raw)
    require(index.get('schemaVersion') == 'denovo-core-source-review-package-v1'
            and index.get('implementation') == implementation() and index.get('pmcid') == source['pmcid']
            and index.get('bundleSha256') == source['bundleSha256'], 'SOURCE_REVIEW_VERSION_OR_IDENTITY_CHANGED')
    original, documents, observations, inputs = read_input(source, root / 'inputs', index['inputIndexSha256'])
    require(len(index['cases']) == len(documents), 'SOURCE_REVIEW_CASE_SET_MISMATCH')
    files = {'inputs/' + p: b for p, b in inputs.items()}; files['index.json'] = raw
    reviewed = []
    for row, (item, original_doc) in zip(index['cases'], documents):
        selected = [c for c in observations if c['caseKey'] == original_doc['caseKey']]
        doc, ledger, report = review_case(source, original_doc, selected)
        require(row['caseKey'] == item['caseKey'], 'SOURCE_REVIEW_CASE_IDENTITY')
        for path_key, pin_key, expected in [('path', 'sha256', doc), ('ledgerPath', 'ledgerSha256', ledger),
                                             ('reportPath', 'reportSha256', report)]:
            body = bounded(safe_file(root, row[path_key]))
            require(digest(body) == row[pin_key] and parse(body) == expected, 'SOURCE_REVIEW_REPLAY_MISMATCH')
            files[row[path_key]] = body
        reviewed.append((row, doc, ledger, report))
    require(sum(map(len, files.values())) <= MAX_PACKAGE, 'SOURCE_REVIEW_SIZE_LIMIT')
    return reviewed, files, parse(inputs['source-facts.json'])


def verified(doc, path):
    f = pointer(doc, path)
    return f['value'] if f['verification'] == 'VERIFIED' and f['availability'] == 'REPORTED' else None


def reason(error):
    if isinstance(error, FileNotFoundError):
        code = 'DENOVO_IDENTITY_LOCAL_RESOURCE_NOT_FOUND'
    elif isinstance(error, (KeyError, TypeError, IndexError)):
        code = 'DENOVO_IDENTITY_RESOURCE_OR_CONFIG_STRUCTURE_INVALID'
    else:
        code = str(error)
        if not code.replace('_', '').isalnum():
            code = 'DENOVO_IDENTITY_RESOURCE_READ_FAILED'
    category = ('DATA_MISSING' if code.startswith('DENOVO_DISCOVERY_') and 'MISSING' in code else
                'SOFTWARE_UNSUPPORTED' if 'UNSUPPORTED' in code or 'SNV_REQUIRED' in code or 'LOCAL_IDENTITY_NOT_CONFIGURED' in code or code == 'DENOVO_FAMILY_REVIEW_SCOPE_LIMIT' else
                'REVIEW_REQUIRED' if code == 'DENOVO_FAMILY_SPECIAL_OR_CONFLICT_REVIEW_REQUIRED' else
                'DATA_CONFLICT' if any(s in code for s in ('MISMATCH', 'CONFLICT', 'AMBIGUOUS')) else
                'KNOWLEDGE_MISSING' if 'POLICY' in code or 'APPLICABILITY' in code or 'PHENOTYPE' in code else
                'DATA_MISSING' if code == 'DENOVO_FAMILY_HISTORY_NOT_ESTABLISHED' else
                'REVIEW_REQUIRED' if any(s in code for s in ('PRIMARY_CASE_NOT_VERIFIED', 'PRIOR_EVENT_RELATION_UNRESOLVED', 'FAMILY_COMPATIBILITY', 'DENOVO_FAMILY_')) else
                'DATA_MISSING' if 'ASSAY_QUALIFICATION' in code or 'REVIEWED_IDENTIFIERS' in code else
                'RESOURCE_UNAVAILABLE')
    return {'code': code, 'category': category}


def context_package(source, package, index_sha256, output, project_root, config=None, config_sha256=None, complete_events=False):
    reviewed, inputs, native_facts = read_review(source, package, index_sha256)
    output = Path(output); require(not output.exists(), 'NEW_OUTPUT_REQUIRED')
    bodies = {'inputs/' + p: b for p, b in inputs.items()}
    config = config or {}; resolver = LocalIdentity(project_root, config['identity']) if config.get('identity') else None
    from core_source_event_discovery import acquired_json, discover
    try:
        discovered_origins, acquisition_issues = acquired_json(source, inputs)
    except (ValueError, KeyError, TypeError) as error:
        code = str(error)
        discovered_origins, acquisition_issues = [], [{'code': code if code.startswith('DENOVO_') else 'DENOVO_DISCOVERY_INPUT_UNSUPPORTED'}]
    entries, prepared = [], []
    for row, original_doc, original_ledger, source_report in reviewed:
        doc, ledger = deepcopy(original_doc), deepcopy(original_ledger)
        for s in doc['sources'].values():
            s['path'] = 'inputs/' + s['path']
        stem = Path(row['path']).stem
        report = {'caseKey': doc['caseKey'], 'issues': [], 'verifiedPaths': [], 'identity': None,
                  'applicability': None, 'stage4Pending': ['EVENT_INDEPENDENCE', 'EXACT_TOPN_AND_DISEASE_MATCH', 'ORIGINAL_JAVA_SCIENTIFIC_INTAKE'],
                  'scoring': 'NOT_RUN', 'clinicalAdoption': 'NONE'}
        report['caseSemantics'] = enrich_case_semantics(source, doc, ledger)
        report['verifiedPaths'].extend(p for p in report['caseSemantics']['changedPaths']
                                       if pointer(doc, p)['verification'] == 'VERIFIED')

        def proof(sid, value, role='DERIVED_FACTS', raw=None, fmt='JSON'):
            raw = canonical(value) if raw is None else raw
            name = stem + '-' + sid + ('.json' if fmt == 'JSON' else '.xml' if fmt == 'XML' else '.txt')
            bodies[name] = raw
            doc['sources'][sid] = {'path': name, 'sha256': digest(raw), 'role': role, 'format': fmt, 'pmcid': None}

        def put(path, value, sid, locator, method):
            f = pointer(doc, path); text = value if isinstance(value, str) else canonical(value).decode('utf-8')
            citations = [{'sourceId': sid, 'kind': 'JSON_POINTER', 'locator': locator, 'quote': text}]
            replacement = observed(doc['caseKey'], f['subject'], value, text, citations, 'PROGRAM_DERIVED')
            replacement['verification'] = 'VERIFIED'
            f.clear(); f.update(replacement)
            ledger['checks'][path] = {'factSha256': object_digest(f), 'method': method,
                                     'basis': citations, 'authority': 'LOCAL_RESEARCH_PREFLIGHT_NOT_CLINICAL_ADOPTION'}
            report['verifiedPaths'].append(path)

        def issue(error, paths):
            item = reason(error); item['paths'] = paths; report['issues'].append(item)
            for path in paths:
                f = pointer(doc, path)
                f['issues'] = sorted(set(f['issues'] + [item['code']]))
                # Do not mutate an inherited VERIFIED fact without rebinding it.
                if path in ledger['checks']:
                    ledger['checks'][path]['factSha256'] = object_digest(f)

        identity = None
        try:
            require(resolver is not None, 'LOCAL_IDENTITY_NOT_CONFIGURED')
            identity = resolver.resolve(doc, source['main']['identifiers'].get('pmid'))
            proof('local-identity', identity)
            put('/groups/variant/resolvedAllele', identity['allele'], 'local-identity', '/allele', 'EXACT_SOURCE_ALIASES_AND_CURRENT_LOCAL_REFERENCE')
            put('/context/allele', identity['allele'], 'local-identity', '/allele', 'LITERATURE_ALLELE_NOT_PATIENT_SELECTION')
            put('/context/hgncId', identity['hgncId'], 'local-identity', '/hgncId', 'PINNED_GENE_TRANSCRIPT_OR_ALIAS')
            proof('variant-scope', {'variantScope': 'SMALL_SEQUENCE_VARIANT'})
            put('/context/variantScope', 'SMALL_SEQUENCE_VARIANT', 'variant-scope', '/variantScope', 'VERIFIED_SINGLE_BASE_SUBSTITUTION')
            report['identity'] = identity['status']
        except (ValueError, OSError, KeyError, TypeError, IndexError) as error:
            issue(error, ['/groups/variant/resolvedAllele', '/context/allele'])

        if identity is not None:
            try:
                result, policy_raw, knowledge = resolve_context(Path(project_root), config, identity, doc)
                proof('applicability', result, 'EVALUATION_KNOWLEDGE')
                proof('policy', None, 'EVALUATION_KNOWLEDGE', policy_raw)
                for i, (sid, raw) in enumerate(sorted(knowledge.items())):
                    proof('knowledge-' + str(i), None, 'EVALUATION_KNOWLEDGE', raw, 'TEXT')
                for name in ('diseaseId', 'inheritance'):
                    put('/context/' + name, result['scope'][name], 'applicability', '/scope/' + name, 'EXACT_CONFIGURED_RESEARCH_POLICY_SCOPE')
                for name in ('policyId', 'policyVersion', 'expertPolicyStatus', 'allowAssumedParentage'):
                    put('/context/' + name, result[name], 'applicability', '/' + name, 'PINNED_EXISTING_RESEARCH_POLICY')
                proof('applicability-result', {'geneDiseaseApplicability': 'APPLICABLE'})
                put('/context/geneDiseaseApplicability', 'APPLICABLE', 'applicability-result', '/geneDiseaseApplicability',
                    'EXACT_SOURCE_ASSESSED_POLICY_SCOPE_NOT_CLINGEN_ASSOCIATION_ALONE')
                if result['phenotype']:
                    put('/context/phenotypeCategory', result['phenotype']['category'], 'applicability', '/phenotype/category',
                        'EXACT_REPORTED_POSITIVES_AND_EXISTING_POLICY_PREFLIGHT')
                for code in result['issues']:
                    issue(ValueError(code), ['/context/phenotypeCategory'])
                report['applicability'] = 'RESEARCH_POLICY_PREFLIGHT' if result['phenotype'] else 'PHENOTYPE_UNRESOLVED'
            except (ValueError, OSError, KeyError, TypeError, IndexError) as error:
                issue(error, ['/context/geneDiseaseApplicability', '/context/phenotypeCategory', '/context/diseaseId',
                    '/context/inheritance', '/context/policyId', '/context/policyVersion',
                    '/context/expertPolicyStatus', '/context/allowAssumedParentage'])

            methods = [p for r in source_report['fields'] if r['path'] in {
                '/groups/testing/method', '/groups/testing/participants'} for p in r['sourceProofs']]
            supported = [p for p in methods if p['method'] in {
                'PUBLISHED_TRIO_SANGER_DENOVO', 'PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO',
                'EXPLICIT_CASE_TARGET_TEST_RESULTS'}]
            target_ok = all(verified(doc, '/groups/' + who + '/targetResult') == value
                            for who, value in [('proband', 'PRESENT'), ('mother', 'ABSENT'), ('father', 'ABSENT')])
            participants = verified(doc, '/groups/testing/participants')
            limitations = doc['groups']['testing']['limitations']
            if supported and target_ok and set(participants or []) == {'PROBAND', 'MOTHER', 'FATHER'} and not limitations['value']:
                proof('assay', {'qualified': True, 'reportedAssayProofs': supported, 'identity': identity['allele'],
                                'meaning': 'QUALIFIED_REPORTED_TARGET_TESTING_NOT_RAW_LAB_QC_OR_MOSAIC_EXCLUSION'})
                put('/groups/testing/qualifiedForTarget', True, 'assay', '/qualified', 'SOURCE_OWNED_ASSAY_TARGET_AND_EXACT_ALLELE')
            else:
                issue(ValueError('DENOVO_IDENTITY_TARGET_ASSAY_QUALIFICATION_UNRESOLVED'), ['/groups/testing/qualifiedForTarget'])

        # Already acquired native records are located without model proposals or
        # per-case event configuration. Incomplete records keep their locators.
        for origin in discovered_origins:
            proof(origin['sourceId'], None, origin['role'], origin['raw'], origin['format'])
        automatic_event, discovery = discover(source, doc, discovered_origins, acquisition_issues)
        report['specialEventDiscovery'] = discovery
        typed_event = None
        event_specs = [e for e in config.get('specialEventSources', []) if e['caseKey'] == doc['caseKey']]
        has_discovered_event = any(r['isEventCandidate'] for r in discovery['records'])
        if event_specs or automatic_event is not None or has_discovered_event:
            from core_special_event import read_source, PATH as EVENT_PATH, METHOD as EVENT_METHOD
            doc['extensions']['specialEvent'] = missing(doc['caseKey'], 'FAMILY')
            try:
                if event_specs:
                    require(len(event_specs) == 1, 'SPECIAL_EVENT_CONFIG_AMBIGUOUS')
                    selected_event = read_source(Path(project_root), event_specs[0], doc, source)
                    require(automatic_event is None or automatic_event['event'] == selected_event['event'], 'SPECIAL_EVENT_DISCOVERED_SOURCE_CONFLICT')
                    partner_config = event_specs[0].get('partnerSources')
                else:
                    if automatic_event is None:
                        event_records = [r for r in discovery['records'] if r['isEventCandidate']]
                        record_issues = [code for r in event_records for code in r['issues']]
                        code = ('DENOVO_DISCOVERY_MULTIPLE_EVENT_RECORDS_AMBIGUOUS' if len(event_records) > 1 else
                                record_issues[0] if record_issues else 'DENOVO_DISCOVERY_REQUIRED_FACTS_MISSING')
                        raise ValueError(code)
                    selected_event = automatic_event
                    clinvar = config.get('identity', {}).get('clinvar')
                    partner_config = {'clinvar': clinvar} if clinvar else None
                for original in selected_event['originals']:
                    sid, body, role = original[:3]
                    proof(sid, None, role, body, original[3] if len(original) == 4 else 'JSON')
                typed_event = selected_event['event']
                f = observed(doc['caseKey'], 'FAMILY', typed_event, canonical(typed_event).decode('utf-8'),
                             selected_event['citations'], 'PROGRAM_DERIVED')
                f['verification'] = 'VERIFIED'
                doc['extensions']['specialEvent'] = f
                ledger['checks'][EVENT_PATH] = {'factSha256': object_digest(f), 'method': selected_event.get('method', EVENT_METHOD),
                    'basis': selected_event['citations'], 'authority': 'EXACT_SOURCE_REPLAY_NOT_SCIENTIFIC_QUALIFICATION'}
                report['verifiedPaths'].append(EVENT_PATH)
                report['specialEventSource'] = selected_event['source']
                from core_special_partner import prepare as prepare_partner
                partner_result, partner_originals = prepare_partner(Path(project_root), partner_config, doc, identity or {})
                for sid, body in partner_originals:
                    proof(sid, None, 'DERIVED_FACTS', body, 'XML')
                proof('special-partner-qualification', partner_result)
            except (ValueError, OSError, KeyError, TypeError, IndexError) as error:
                issue(error, ['/extensions/specialEvent'])

        # Preserve strict, already-implemented source qualification. Unassigned
        # maternal history and absent text are not a positive family assessment.
        label = doc['caseKey'].split('#', 1)[1]
        # Reconstruct from the pinned native table, not the model-augmented fact
        # projection. An unrelated rejected proposal must not poison a previously
        # established family statement. Actual source conflicts still veto it.
        native_cases = tables(source['main'])
        legacy = [source_case(source['main'], native_cases, c, [])
                  for c in native_cases if c['caseLabel'] == label]
        report['familyReview'] = family_review(source['main'], label, legacy, verified(doc, '/context/inheritance'))
        for warning in report['familyReview']['warnings']:
            doc['reviewPlan']['unprocessedScope'].append('FAMILY_REVIEW_WARNING:' + warning)
        if typed_event is not None and typed_event['kind'] != 'CONSTITUTIONAL':
            special_model = ('SIBLING_RECURRENCE' if typed_event['kind'] == 'SIBLING_RECURRENCE' else
                             'MOSAIC' if typed_event['kind'] == 'POSTZYGOTIC' else 'NONE_IDENTIFIED_IN_CHECKED_SCOPE')
            proof('special-family-source', {'compatibility': 'SPECIAL_MODEL', 'specialFamilyModel': special_model,
                'meaning': 'TYPED_REPORTED_OBSERVATIONS_REQUIRE_JAVA_SCIENTIFIC_QUALIFICATION'})
            put('/groups/family/compatibility', 'SPECIAL_MODEL', 'special-family-source', '/compatibility', 'TYPED_FAMILY_DISPATCH')
            put('/conditions/specialFamilyModel', special_model, 'special-family-source', '/specialFamilyModel', 'TYPED_FAMILY_DISPATCH')
        elif identity is not None and report['familyReview']['qualified']:
            proof('family-preflight', {'compatibility': 'COMPATIBLE', 'specialFamilyModel': 'NONE_IDENTIFIED_IN_CHECKED_SCOPE',
                                      'sourceQualification': legacy[0] if legacy else None, 'familyReview': report['familyReview'],
                                      'scope': 'EXISTING_BOUNDED_FAMILY_READER_NOT_ALL_PEDIGREE_MODELS'})
            put('/groups/family/compatibility', 'COMPATIBLE', 'family-preflight', '/compatibility', 'EXISTING_EXPLICIT_FAMILY_QUALIFIER')
            put('/conditions/specialFamilyModel', 'NONE_IDENTIFIED_IN_CHECKED_SCOPE', 'family-preflight', '/specialFamilyModel', 'EXISTING_BOUNDED_CONTEXT_QUALIFIER')
        else:
            code = report['familyReview']['reasonCode'] or 'DENOVO_IDENTITY_FAMILY_COMPATIBILITY_UNRESOLVED'
            issue(ValueError(code), ['/groups/family/compatibility', '/conditions/specialFamilyModel'])
        if complete_events:
            from core_case_completion import complete_event
            report['eventReview'] = complete_event(doc, source, native_facts, proof, put)
            for code in report['eventReview']['issues']:
                issue(ValueError(code), ['/groups/case/origin', '/groups/overlap/eventId', '/groups/overlap/independence'])
            report['stage4Pending'] = ['CROSS_SOURCE_AND_CURRENT_CASE_EVENT_CHECK', 'EXACT_TOPN_AND_DISEASE_MATCH', 'ORIGINAL_JAVA_SCIENTIFIC_INTAKE']
            if typed_event is not None and report['eventReview']['primary'] and not report['eventReview']['issues']:
                # Reuse the source's biological event identity across relatives
                # and publications; downstream grouping still checks duplicates.
                proof('special-event-identity', {'eventId': typed_event['eventId'],
                    'independence': 'UNIQUE_WITHIN_CHECKED_SCOPE',
                    'meaning': 'REPORTED_EVENT_ID_NOT_WORLDWIDE_INDEPENDENCE'})
                put('/groups/overlap/eventId', typed_event['eventId'], 'special-event-identity', '/eventId', 'REPORTED_BIOLOGICAL_EVENT_ID')
                put('/groups/overlap/independence', 'UNIQUE_WITHIN_CHECKED_SCOPE', 'special-event-identity', '/independence', 'CURRENT_SOURCE_IDENTITY_ONLY')

        ledger['contextBinding'] = context_binding(doc)
        for suffix, value in [('.json', doc), ('-ledger.json', ledger), ('-context.json', report)]:
            bodies[stem + suffix] = canonical(value)
        entries.append({'caseKey': doc['caseKey'], 'path': stem + '.json', 'sha256': digest(bodies[stem + '.json']),
            'ledgerPath': stem + '-ledger.json', 'ledgerSha256': object_digest(ledger), 'reportPath': stem + '-context.json',
            'reportSha256': digest(bodies[stem + '-context.json']), 'identityStatus': report['identity'],
            'applicabilityStatus': report['applicability'], 'issues': report['issues'], 'verifiedPaths': report['verifiedPaths']})
        prepared.append((doc, ledger, stem))
    require(sum(map(len, bodies.values())) <= MAX_PACKAGE, 'CONTEXT_PACKAGE_SIZE_LIMIT')
    output.mkdir(parents=True, exist_ok=False)
    for name, raw in bodies.items():
        path = output / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(raw)
    for entry, (doc, ledger, stem) in zip(entries, prepared):
        readiness = assess(doc, output, ledger, object_digest(ledger))
        readiness_raw = canonical(readiness)
        (output / (stem + '-readiness.json')).write_bytes(readiness_raw)
        entry['readiness'] = readiness['status']
        entry['readinessPath'] = stem + '-readiness.json'
        entry['readinessSha256'] = digest(readiness_raw)
    from core_special_partner import DEPENDENCIES as PARTNER_FILES
    context_dependencies = ('denovo/core_source_event_discovery.py', 'denovo/core_context_review.py', 'denovo/core_special_event.py', 'denovo/core_case_semantics.py',
        'denovo/core_context_policy.py', 'denovo/core_local_identity.py', 'denovo/core_case_completion.py',
        'denovo/refseq_identity.py', 'denovo/single_case_scope.py', 'acmg-generalization/normalize_public_panel.py',
        'acmg-generalization/build_transcript_model.py', *('denovo/' + name for name in PARTNER_FILES))
    result = {'schemaVersion': 'denovo-core-context-package-v1', 'version': VERSION, 'inputIndexSha256': index_sha256,
              'configSha256': config_sha256, 'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
              'cases': entries, 'status': 'CONTEXT_REVIEWED_EVALUATION_PENDING', 'businessPublishable': False,
              'criterionDecision': 'NOT_RUN', 'modelCalls': 0, 'networkCalls': 0,
              'referenceBasesRead': resolver.reference.charged_bases if resolver and resolver.reference else 0,
              'implementation': {n: digest((Path(__file__).resolve().parents[1] / n).read_bytes()) for n in context_dependencies}}
    (output / 'index.json').write_bytes(canonical(result)); return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('library', 'input', 'index-sha256', 'output', 'project-root'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--config'); parser.add_argument('--config-sha256')
    args = parser.parse_args()
    from bundle_model_contract import load
    raw = bounded(Path(args.input) / 'index.json'); require(digest(raw) == args.index_sha256, 'SOURCE_REVIEW_INDEX_PIN_MISMATCH')
    index = parse(raw)
    config, pin = load_config(args.project_root, {'path': args.config, 'sha256': args.config_sha256}) if args.config else ({}, None)
    result = context_package(load(Path(args.library), index['pmcid'], index['bundleSha256']), args.input,
        args.index_sha256, args.output, args.project_root, config, pin)
    print(canonical({'status': result['status'], 'cases': len(result['cases']), 'referenceBasesRead': result['referenceBasesRead']}).decode())


if __name__ == '__main__':
    main()
