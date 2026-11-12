"""Bounded native event records in already acquired JSON and JATS tables.

This module locates observations, not scientific eligibility. It never reads a
model's proposals as a source, fills absent clinical fields, discovers files by
filesystem scanning, or changes the registered library.
"""
import base64
from copy import deepcopy
from pathlib import Path
import re
from model_literature_contract import canonical, digest, parse
from core_material_contract import pointer, validate_shape, evaluation_value, bounded
from core_material_schema import SPECIAL_EVENT, ALLELE
from extract_literature_cases import table_grid, cell_text, CASE_LABEL, normalized
from prepare_literature import prepare

VERSION = 'denovo-source-event-discovery-v1'
METHOD = 'BOUNDED_REGISTERED_EVENT_DISCOVERY_REPLAY'
SOURCE_ID = 'special-event-original'
MAX_JSON = 2 * 1024 * 1024
MAX_TOTAL_JSON = 4 * 1024 * 1024
MAX_RECORDS = 128
# Exact column meanings only. Broad prose, calculated test quality and inferred
# family structure are deliberately outside this dictionary.
HEADERS = {
    'event kind': '/kind', 'family id': '/familyId', 'event id': '/eventId',
    'event person id': '/personId', 'person id': '/personId', 'proband id': '/probandId',
    'sex': '/sex', 'event person sex': '/sex', 'proband sex': '/probandSex',
    'affected status': '/affectedStatus', 'ploidy': '/ploidy', 'genotype': '/genotype',
    'parental availability': '/parentalAvailability', 'other affected males': '/otherAffectedMales',
    'mother id': '/trio/motherId', 'father id': '/trio/fatherId',
    'subject target result': '/trio/subjectTarget', 'mother target result': '/trio/motherTarget',
    'father target result': '/trio/fatherTarget', 'maternity': '/trio/maternity', 'paternity': '/trio/paternity',
    'maternity method': '/trio/maternityMethod', 'paternity method': '/trio/paternityMethod',
    'testing method': '/trio/method', 'target coverage': '/trio/targetCoverage',
    'detection capability': '/trio/detectionCapability', 'test quality': '/trio/quality',
    'partner status': '/partner/status', 'partner allele': '/partner/allele', 'phase': '/partner/phase',
    'partner source refs': '/partner/sourceRefs', 'siblings': '/siblings', 'event origin': '/eventOrigin',
    'tissues': '/tissues'}
BINDINGS = {'gene': 'geneSymbol', 'gene symbol': 'geneSymbol', 'mutated gene': 'geneSymbol', 'variant': 'variant', 'nucleotide': 'variant',
            'genomic allele': 'allele', 'allele': 'allele', 'disease id': 'diseaseId'}


def require(ok, code):
    if not ok:
        raise ValueError('DENOVO_DISCOVERY_' + code)


def escape(key):
    return str(key).replace('~', '~0').replace('/', '~1')


def leaf_values(value, path=''):
    if type(value) is dict:
        for key, part in value.items():
            yield from leaf_values(part, path + '/' + escape(key))
    elif type(value) is list and value:
        for index, part in enumerate(value):
            yield from leaf_values(part, path + '/' + str(index))
    else:
        yield path, value


def shape_at(path):
    schema = SPECIAL_EVENT
    for key in path.strip('/').split('/'):
        schema = schema['properties'][key]
    return schema


def literal(value, path):
    schema = shape_at(path)
    if type(value) is str and schema.get('type') != 'string':
        value = parse(value.encode('utf-8'))
    if type(value) is str and 'enum' in schema:
        candidate = re.sub(r'[ -]+', '_', value.strip()).upper()
        if candidate in schema['enum']:
            value = candidate
    validate_shape(value, schema)
    return value


def set_path(value, path, part):
    current = value
    keys = path.strip('/').split('/')
    for key in keys[:-1]:
        current = current.setdefault(key, {})
    current[keys[-1]] = deepcopy(part)


def object_option(schema, value):
    if type(value) is dict:
        return next((part for part in schema.get('anyOf', []) if part.get('type') == 'object'), schema)
    return schema


def validate_present(value, schema):
    """Validate observed fields without calling unreported fields normal."""
    schema = object_option(schema, value)
    if schema.get('type') == 'object' and type(value) is dict:
        require(set(value) <= set(schema['properties']), 'TYPED_VALUE_UNSUPPORTED')
        for key, part in value.items():
            validate_present(part, schema['properties'][key])
    elif schema.get('type') == 'array' and type(value) is list:
        require(schema.get('minItems', 0) <= len(value) <= schema.get('maxItems', 256), 'TYPED_VALUE_UNSUPPORTED')
        for part in value:
            validate_present(part, schema['items'])
    else:
        validate_shape(value, schema)


def missing_paths(value, schema=SPECIAL_EVENT, prefix=''):
    schema = object_option(schema, value); result = []
    if schema.get('type') == 'object' and type(value) is dict:
        result += [prefix + '/' + key for key in schema.get('required', []) if key not in value]
        for key, part in value.items():
            if key in schema['properties']:
                result.extend(missing_paths(part, schema['properties'][key], prefix + '/' + key))
    elif schema.get('type') == 'array' and type(value) is list:
        for index, part in enumerate(value):
            result.extend(missing_paths(part, schema['items'], prefix + '/' + str(index)))
    return result


def acquired_json(source, inputs):
    """Read only manifest-listed attachment bytes from the existing intake."""
    intakes = {digest(raw): raw for name, raw in inputs.items() if Path(name).name == 'original-intake.json'}
    if not intakes:
        return [], []
    require(len(intakes) == 1, 'INTAKE_AMBIGUOUS')
    intake = parse(next(iter(intakes.values())))
    require(intake.get('schemaVersion') == 'denovo-bundle-candidate-intake-v1'
            and intake.get('pmcid') == source['pmcid'] and intake.get('bundleSha256') == source['bundleSha256'], 'INTAKE_IDENTITY_MISMATCH')
    prefix = 'papers/' + source['pmcid'] + '/'
    files = intake['files']
    def original(name, pin, maximum):
        require(name in files and files[name].get('sha256') == pin, 'ATTACHMENT_PIN_MISMATCH')
        raw = base64.b64decode(files[name]['base64'], validate=True)
        require(len(raw) <= maximum and digest(raw) == pin, 'ATTACHMENT_PIN_MISMATCH')
        return raw
    manifest = parse(original(prefix + 'source-bundles/' + source['bundleSha256'] + '.json', source['bundleSha256'], MAX_JSON))
    require(manifest.get('pmcid') == source['pmcid'] and manifest.get('main', {}).get('sha256') == digest(source['raw']), 'BUNDLE_IDENTITY_MISMATCH')
    registered = {(a['sha256'], a['originalName']) for a in source['attachments']}
    origins, issues, total = [], [], 0
    for attachment in manifest.get('attachments', []):
        name, pin = attachment['originalName'], attachment['sha256']
        if not name.lower().endswith('.json'):
            continue
        require((pin, name) in registered, 'ATTACHMENT_NOT_REGISTERED')
        path = attachment['path']
        require(type(path) is str and not path.startswith('/') and ':' not in path and chr(92) not in path
                and all(piece not in {'..', '.', ''} for piece in path.split('/')), 'ATTACHMENT_PATH_INVALID')
        try:
            raw = original(prefix + path, pin, MAX_JSON); total += len(raw)
            require(total <= MAX_TOTAL_JSON and len(origins) < 32, 'ATTACHMENT_BUDGET_UNSUPPORTED')
            value = parse(raw)
            origins.append({'sourceId': 'special-event-discovery-parent-' + str(len(origins)), 'sha256': pin,
                'originalName': name, 'format': 'JSON', 'role': 'PAPER_ATTACHMENT', 'raw': raw, 'value': value})
        except (ValueError, KeyError, TypeError) as error:
            code = str(error)
            issues.append({'sourceSha256': pin, 'code': code if code.startswith('DENOVO_') else 'DENOVO_DISCOVERY_JSON_PARSE_UNSUPPORTED'})
    return origins, issues


def source_value(doc, path):
    return evaluation_value(path, pointer(doc, path))


def bind_record(record, doc):
    gene = source_value(doc, '/groups/gene/symbol')
    allele = source_value(doc, '/groups/variant/resolvedAllele')
    if type(gene) is not str or type(record.get('geneSymbol')) is not str or record['geneSymbol'] != gene:
        return 'GENE_BINDING_MISSING_OR_MISMATCH'
    if 'diseaseId' in record and record['diseaseId'] != source_value(doc, '/context/diseaseId'):
        return 'DISEASE_BINDING_MISMATCH'
    if 'allele' in record:
        try:
            validate_shape(record['allele'], ALLELE)
        except ValueError:
            return 'ALLELE_BINDING_MISMATCH'
        return None if canonical(record['allele']) == canonical(allele) and allele is not None else 'ALLELE_BINDING_MISMATCH'
    variants = source_value(doc, '/groups/variant/representations') or []
    if type(record.get('variant')) is str and record['variant'] in variants:
        return None
    return 'EXACT_VARIANT_BINDING_MISSING_OR_MISMATCH'


def json_candidates(origin, doc):
    value = origin['value']; rows = []
    if type(value) is list:
        rows = [(v, '/' + str(i)) for i, v in enumerate(value)]
    elif type(value) is dict:
        containers = [key for key in ('cases', 'events', 'observations') if type(value.get(key)) is list]
        require(len(containers) <= 1, 'JSON_RECORD_CONTAINER_AMBIGUOUS')
        if containers:
            key = containers[0]; rows = [(v, '/' + key + '/' + str(i)) for i, v in enumerate(value[key])]
        else:
            rows = [(value, '')]
    require(len(rows) <= MAX_RECORDS, 'JSON_RECORD_LIMIT_UNSUPPORTED')
    result = []
    label = doc['caseKey'].split('#', 1)[1]
    for row, locator in rows:
        if type(row) is not dict:
            continue
        case = row.get('caseLabel', row.get('caseId', row.get('caseKey')))
        if case not in (label, doc['caseKey']):
            continue
        nested = row.get('specialEvent'); row_issues = []
        raw_marker = 'specialEvent' in row or any(HEADERS.get(normalized(re.sub(r'(?<!^)(?=[A-Z])', ' ', key))) in {'/kind', '/eventId'} for key in row)
        if type(nested) is dict:
            event = deepcopy(nested); base = locator + '/specialEvent'
            refs = [{'eventPath': p, 'sourceSha256': origin['sha256'], 'recordLocator': base + p,
                     'sourceId': origin['sourceId'], 'kind': 'JSON_POINTER',
                     'quote': v if type(v) is str else canonical(v).decode('utf-8')}
                    for p, v in leaf_values(event) if p != '/schemaVersion']
        else:
            event, refs = {}, []
            for key, raw in row.items():
                field = HEADERS.get(normalized(re.sub(r'(?<!^)(?=[A-Z])', ' ', key)))
                if field is None:
                    continue
                try:
                    part = literal(raw, field)
                except (ValueError, KeyError, TypeError):
                    row_issues.append('DENOVO_DISCOVERY_TYPED_VALUE_UNSUPPORTED'); continue
                try:
                    previous = pointer(event, field)
                    if canonical(previous) != canonical(part):
                        row_issues.append('DENOVO_DISCOVERY_SOURCE_FIELD_CONFLICT'); continue
                except ValueError:
                    pass
                set_path(event, field, part)
                for suffix, v in leaf_values(part):
                    refs.append({'eventPath': field + suffix, 'sourceSha256': origin['sha256'],
                        'recordLocator': locator + '/' + escape(key) + (suffix if type(raw) is not str else ''), 'sourceId': origin['sourceId'],
                        'kind': 'JSON_POINTER', 'quote': raw if type(raw) is str else v if type(v) is str else canonical(v).decode('utf-8')})
            if 'personId' in event:
                event.setdefault('trio', {})['personId'] = event['personId']
                ref = next(r for r in refs if r['eventPath'] == '/personId')
                refs.append({**ref, 'eventPath': '/trio/personId'})
        if not raw_marker:
            continue
        if 'specialEvent' in row and type(nested) is not dict:
            row_issues.append('DENOVO_DISCOVERY_TYPED_VALUE_UNSUPPORTED')
        event.setdefault('schemaVersion', 'denovo-special-event-v1')
        item = candidate(origin, locator, event, refs, bind_record(row, doc))
        item['issues'].extend(row_issues); result.append(item)
    return result


def candidate(origin, locator, event, refs, binding_issue):
    missing = missing_paths(event)
    issues = ['DENOVO_DISCOVERY_' + binding_issue] if binding_issue else []
    try:
        validate_present(event, SPECIAL_EVENT)
    except ValueError:
        issues.append('DENOVO_DISCOVERY_TYPED_VALUE_UNSUPPORTED')
    return {'origin': origin, 'recordLocator': locator, 'event': event, 'refs': refs,
            'missingPaths': missing, 'issues': issues}


def jats_candidates(origin, doc):
    result = []; label = doc['caseKey'].split('#', 1)[1]; document = origin['value']
    require(len(document['tables']) <= 64, 'JATS_TABLE_LIMIT_UNSUPPORTED')
    for table in document['tables']:
        grid = table_grid(table)
        if not grid:
            continue
        header = grid[0]
        columns = [i for i, cell in enumerate(header) if i and CASE_LABEL.fullmatch(cell_text(cell))
                   and CASE_LABEL.fullmatch(cell_text(cell))[1] == label]
        if len(columns) != 1:
            continue
        column = columns[0]; event, refs, bindings, binding_refs, seen, issues, seen_paths = {}, [], {}, [], set(), [], {}
        for row in grid[1:]:
            key, cell = normalized(cell_text(row[0])), row[column] if column < len(row) else None
            path = HEADERS.get(key); binding = BINDINGS.get(key)
            if path is None and binding is None:
                continue
            if not cell or not cell_text(cell).strip():
                continue
            if key in seen or cell['rowspan'] != 1 or cell['colspan'] != 1:
                issues.append('DENOVO_DISCOVERY_JATS_FIELD_AMBIGUOUS'); continue
            seen.add(key); raw = cell_text(cell)
            if binding:
                try:
                    bound = parse(raw.encode()) if binding == 'allele' else raw
                    if binding in bindings and canonical(bindings[binding]) != canonical(bound):
                        issues.append('DENOVO_DISCOVERY_BINDING_FIELD_CONFLICT'); continue
                    bindings[binding] = bound
                    binding_refs.append({'eventPath': '/binding/' + binding, 'sourceSha256': origin['sha256'],
                        'recordLocator': cell['unitId'], 'sourceId': origin['sourceId'], 'kind': 'JATS_UNIT', 'quote': raw})
                except ValueError:
                    issues.append('DENOVO_DISCOVERY_ALLELE_BINDING_MISMATCH')
                continue
            try:
                part = literal(raw, path)
                if path in seen_paths and canonical(seen_paths[path]) != canonical(part):
                    issues.append('DENOVO_DISCOVERY_SOURCE_FIELD_CONFLICT'); continue
                seen_paths[path] = part; set_path(event, path, part)
                for suffix, _ in leaf_values(part):
                    refs.append({'eventPath': path + suffix, 'sourceSha256': origin['sha256'],
                        'recordLocator': cell['unitId'], 'sourceId': origin['sourceId'], 'kind': 'JATS_UNIT', 'quote': raw})
            except (ValueError, TypeError, KeyError):
                issues.append('DENOVO_DISCOVERY_JATS_VALUE_UNSUPPORTED')
        is_event = bool({'event kind', 'event id'} & seen)
        if not is_event and not bindings:
            continue
        event['schemaVersion'] = 'denovo-special-event-v1'
        if 'personId' in event:
            event.setdefault('trio', {})['personId'] = event['personId']
            ref = next(r for r in refs if r['eventPath'] == '/personId')
            refs.append({**ref, 'eventPath': '/trio/personId'})
        row = candidate(origin, table['tableId'], event, refs, bind_record(bindings, doc))
        row['issues'].extend(issues); row['isEventCandidate'] = is_event
        row['bindingRefs'] = binding_refs
        row['caseReference'] = {'sourceSha256': origin['sha256'], 'locator': header[column]['unitId'],
                              'kind': 'JATS_UNIT', 'quote': cell_text(header[column])}
        result.append(row)
    return result


def materialize(row, doc):
    event, origin = row['event'], row['origin']
    refs = [{key: ref[key] for key in ('eventPath', 'sourceSha256', 'recordLocator')} for ref in row['refs']]
    envelope = {'schemaVersion': VERSION, 'sourceClass': 'REGISTERED_STRUCTURED_REPORT',
        'sourceDescription': 'Bounded registered ' + origin['format'] + ' observation replay',
        'caseKey': doc['caseKey'], 'allele': source_value(doc, '/context/allele'),
        'scope': {key: source_value(doc, '/context/' + key) for key in ('hgncId', 'diseaseId', 'inheritance')},
        'specialEvent': event, 'sourceReferences': refs,
        'discovery': {'format': origin['format'], 'sourceId': origin['sourceId'], 'sourceSha256': origin['sha256'],
            'originalName': origin['originalName'], 'recordLocator': row['recordLocator'], 'technicalConstants': ['/schemaVersion']}}
    raw = canonical(envelope)
    citations = [{'sourceId': SOURCE_ID, 'kind': 'JSON_POINTER', 'locator': '/specialEvent', 'quote': canonical(event).decode()}]
    citations += [{key: ref[key] for key in ('sourceId', 'kind', 'quote')} | {'locator': ref['recordLocator']} for ref in row['refs']]
    require(len(citations) <= 256, 'FIELD_LIMIT_UNSUPPORTED')
    from core_special_event import event_identity_proof
    originals = [(SOURCE_ID, raw, 'DERIVED_FACTS', 'JSON')]
    if origin['format'] == 'JSON':
        originals.append((origin['sourceId'], origin['raw'], 'PAPER_ATTACHMENT', 'JSON'))
    return {'event': event, 'citations': citations, 'originals': originals, 'method': METHOD,
        'source': {'sourceClass': 'REGISTERED_STRUCTURED_REPORT', 'sourceDescription': envelope['sourceDescription'],
            'sourceSha256': digest(raw), 'parentSourceSha256s': [origin['sha256']],
            'eventIdentityProof': event_identity_proof(envelope, digest(raw)),
            'replayStatus': 'EXACT_REGISTERED_SOURCE_VALUES', 'scientificQualification': 'NOT_RUN',
            'discovery': envelope['discovery']}}


def discover(source, doc, origins=(), acquisition_issues=()):
    main = {'sourceId': 'paper', 'sha256': digest(source['raw']), 'originalName': source['pmcid'] + '.xml',
            'format': 'JATS', 'role': 'PAPER_PRIMARY', 'raw': source['raw'], 'value': source['main']}
    records, issues = [], list(acquisition_issues)
    for origin in [main, *origins]:
        try:
            records += jats_candidates(origin, doc) if origin['format'] == 'JATS' else json_candidates(origin, doc)
        except (ValueError, KeyError, TypeError, IndexError) as error:
            code = str(error)
            issues.append({'sourceSha256': origin['sha256'], 'code': code if code.startswith('DENOVO_') else 'DENOVO_DISCOVERY_STRUCTURE_UNSUPPORTED'})
    report = {'version': VERSION, 'checkedSourceSha256s': [main['sha256'], *[o['sha256'] for o in origins]],
        'records': [{'sourceSha256': r['origin']['sha256'], 'recordLocator': r['recordLocator'],
            'isEventCandidate': r.get('isEventCandidate', True), 'caseReference': r.get('caseReference'),
            'missingPaths': r['missingPaths'], 'issues': r['issues'],
            'fields': [{'path': ref['eventPath'], 'sourceSha256': ref['sourceSha256'], 'locator': ref['recordLocator'],
                        'kind': ref['kind'], 'quote': ref['quote']} for ref in r['refs'] + r.get('bindingRefs', [])]} for r in records],
        'issues': issues, 'criterionDecision': 'NOT_RUN'}
    records = [r for r in records if r.get('isEventCandidate', True)]
    if len(records) > 1:
        report['issues'].append({'code': 'DENOVO_DISCOVERY_MULTIPLE_EVENT_RECORDS_AMBIGUOUS'})
        return None, report
    if len(records) != 1 or records[0]['missingPaths'] or records[0]['issues'] or issues:
        return None, report
    return materialize(records[0], doc), report


def verify_fact(doc, fact, check, sources, root):
    require(fact['origin'] == 'PROGRAM_DERIVED' and fact['subject'] == 'FAMILY'
            and check.get('method') == METHOD, 'PROOF_REQUIRED')
    envelope = sources[SOURCE_ID][1]
    require(envelope.get('schemaVersion') == VERSION, 'PROOF_VERSION_MISMATCH')
    discovery = envelope['discovery']; sid = discovery['sourceId']; metadata = doc['sources'][sid]
    require(metadata['sha256'] == discovery['sourceSha256'], 'ORIGINAL_PIN_MISMATCH')
    if discovery['format'] == 'JATS':
        require(sid == 'paper' and metadata['role'] == 'PAPER_PRIMARY' and metadata['format'] == 'JATS', 'ORIGINAL_ROLE_MISMATCH')
        raw = bounded(Path(root) / metadata['path'], MAX_JSON)
        require(digest(raw) == metadata['sha256'], 'ORIGINAL_PIN_MISMATCH')
        value = prepare(raw, doc['caseKey'].split('#', 1)[0], metadata['sha256'])
    else:
        require(metadata['role'] == 'PAPER_ATTACHMENT' and metadata['format'] == 'JSON', 'ORIGINAL_ROLE_MISMATCH')
        registered = sources['scope'][1]['attachments']
        require(any(a['sha256'] == metadata['sha256'] and a['originalName'] == discovery['originalName'] for a in registered), 'ORIGINAL_NOT_REGISTERED')
        value = sources[sid][1]; raw = canonical(value)
    origin = {'sourceId': sid, 'sha256': metadata['sha256'], 'originalName': discovery['originalName'],
              'format': discovery['format'], 'role': metadata['role'], 'raw': raw, 'value': value}
    rows = jats_candidates(origin, doc) if origin['format'] == 'JATS' else json_candidates(origin, doc)
    matching = [r for r in rows if r['recordLocator'] == discovery['recordLocator']]
    require(len(matching) == 1 and not matching[0]['issues'] and not matching[0]['missingPaths'], 'REPLAY_RECORD_MISMATCH')
    expected = materialize(matching[0], doc)
    expected_envelope = parse(expected['originals'][0][1])
    require(envelope == expected_envelope and fact['value'] == expected['event']
            and fact['rawValue'] == canonical(expected['event']).decode() and fact['citations'] == expected['citations']
            and check.get('basis') == expected['citations'], 'REPLAY_FACT_MISMATCH')
    require(doc['sources'][SOURCE_ID]['role'] == 'DERIVED_FACTS', 'DERIVED_ROLE_REQUIRED')
    return expected['event']
