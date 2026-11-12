"""Exact replay of registered JSON observations, without scientific qualification.

Service configuration locates originals. A configuration pin is never evidence
of parentage, phase or an assay result: every reported leaf must be read from an
original registered attachment. A derived report keeps its distinct source role.
"""
from copy import deepcopy
from model_literature_contract import canonical, digest, parse
from core_material_schema import SPECIAL_EVENT, ALLELE, TEXT, obj, array, string

VERSION = 'denovo-special-event-source-replay-1.0.0'
METHOD = 'PINNED_SPECIAL_EVENT_SOURCE_REPLAY'
PATH = '/extensions/specialEvent'
SOURCE_ID = 'special-event-original'
SOURCE_SCHEMA = obj({'schemaVersion': {'const': 'denovo-special-event-source-v1'},
    'sourceClass': string(['ORIGINAL_JSON_ATTACHMENT', 'REGISTERED_STRUCTURED_REPORT']),
    'sourceDescription': TEXT, 'caseKey': TEXT, 'allele': ALLELE,
    'scope': obj({'hgncId': TEXT, 'diseaseId': TEXT, 'inheritance': string(['AD', 'AR', 'X_LINKED'])}),
    'specialEvent': SPECIAL_EVENT,
    'sourceReferences': array(obj({'eventPath': TEXT, 'sourceSha256': {'type': 'string', 'pattern': '^[a-f0-9]{64}$'},
                                    'recordLocator': TEXT}))})


def require(condition, code):
    if not condition:
        raise ValueError('DENOVO_SPECIAL_EVENT_' + code)


def leaves(value, path=''):
    if type(value) is dict:
        for key, part in value.items():
            yield from leaves(part, path + '/' + key.replace('~', '~0').replace('/', '~1'))
    elif type(value) is list and value:
        for i, part in enumerate(value):
            yield from leaves(part, path + '/' + str(i))
    else:
        yield path, value


def replay(envelope, source_pin, document, parents, registered):
    """Return only source-reproduced observations; Java checks scientific use."""
    from core_material_contract import validate_shape, pointer, evaluation_value
    validate_shape(envelope, SOURCE_SCHEMA)
    require(envelope['caseKey'] == document['caseKey'], 'CASE_MISMATCH')
    expected_allele = evaluation_value('/groups/variant/resolvedAllele', document['groups']['variant']['resolvedAllele'])
    require(expected_allele is not None and envelope['allele'] == expected_allele, 'ALLELE_MISMATCH')
    scope = {k: evaluation_value('/context/' + k, document['context'][k]) for k in ('hgncId', 'diseaseId', 'inheritance')}
    require(envelope['scope'] == scope, 'BACKGROUND_MISMATCH')
    event = envelope['specialEvent']; refs = envelope['sourceReferences']
    event_leaves = dict(leaves(event))
    if envelope['sourceClass'] == 'ORIGINAL_JSON_ATTACHMENT':
        require(source_pin in registered, 'ORIGINAL_NOT_REGISTERED')
        require(not refs, 'ORIGINAL_REFERENCES_UNEXPECTED')
        source_refs = [(SOURCE_ID, '/specialEvent' + p, v) for p, v in event_leaves.items()]
    else:
        require(len(refs) == len(event_leaves) and {r['eventPath'] for r in refs} == set(event_leaves), 'FIELD_REFERENCES_INCOMPLETE')
        source_refs = []
        for ref in refs:
            pin = ref['sourceSha256']
            require(pin in registered and pin in parents, 'PARENT_ORIGINAL_NOT_REGISTERED')
            sid, original = parents[pin]
            value = pointer(original, ref['recordLocator'])
            require(canonical(value) == canonical(event_leaves[ref['eventPath']]), 'FIELD_SOURCE_MISMATCH')
            source_refs.append((sid, ref['recordLocator'], value))
    citations = [{'sourceId': SOURCE_ID, 'kind': 'JSON_POINTER', 'locator': '/specialEvent',
                  'quote': canonical(event).decode('utf-8')}]
    citations += [{'sourceId': sid, 'kind': 'JSON_POINTER', 'locator': loc,
                   'quote': v if type(v) is str else canonical(v).decode('utf-8')}
                  for sid, loc, v in source_refs]
    require(len(citations) <= 256, 'FIELD_REFERENCE_LIMIT_UNSUPPORTED')
    return deepcopy(event), citations


def event_identity_proof(envelope, source_pin):
    """Namespace reported labels only by the original record that supplied them.

    This is called after source replay. Two labels read from different originals
    do not establish one registry authority, even when their strings agree.
    """
    if envelope['sourceClass'] == 'ORIGINAL_JSON_ATTACHMENT':
        family_pin, family_locator = source_pin, '/specialEvent/familyId'
        event_pin, event_locator = source_pin, '/specialEvent/eventId'
    else:
        refs = {ref['eventPath']: ref for ref in envelope['sourceReferences']}
        family, event = refs['/familyId'], refs['/eventId']
        family_pin, family_locator = family['sourceSha256'], family['recordLocator']
        event_pin, event_locator = event['sourceSha256'], event['recordLocator']
    return {'authoritySha256': family_pin if family_pin == event_pin else None,
            'familySourceSha256': family_pin, 'familyLocator': family_locator,
            'eventSourceSha256': event_pin, 'eventLocator': event_locator}


def registered_pins(source):
    require(type(source) is dict and type(source.get('attachments')) is list
            and all(type(a) is dict and type(a.get('sha256')) is str for a in source['attachments']),
            'REGISTERED_SOURCE_SCOPE_REQUIRED')
    return {a['sha256'] for a in source['attachments']}


def read_source(root, spec, document, source):
    from core_local_identity import pinned
    from core_material_contract import validate_shape
    require(type(spec) is dict and set(spec) <= {'caseKey', 'path', 'sha256', 'parents', 'partnerSources'}
            and {'caseKey', 'path', 'sha256'} <= set(spec), 'CONFIG_SHAPE')
    require(spec['caseKey'] == document['caseKey'], 'CONFIG_CASE_MISMATCH')
    raw = pinned(root, spec, 2097152); envelope = parse(raw)
    validate_shape(envelope, SOURCE_SCHEMA)
    parent_specs = spec.get('parents', [])
    require(type(parent_specs) is list and len(parent_specs) <= 64, 'PARENT_LIMIT')
    registered = registered_pins(source)
    require(envelope['sourceClass'] != 'ORIGINAL_JSON_ATTACHMENT' or not parent_specs, 'ORIGINAL_PARENTS_UNEXPECTED')
    parents, originals = {}, []
    for i, parent in enumerate(parent_specs):
        require(type(parent) is dict and set(parent) == {'path', 'sha256'}, 'PARENT_CONFIG_SHAPE')
        body = pinned(root, parent, 2097152); pin = digest(body)
        require(pin not in parents, 'PARENT_DUPLICATE')
        require(pin in registered, 'PARENT_ORIGINAL_NOT_REGISTERED')
        sid = 'special-event-parent-' + str(i)
        parents[pin] = (sid, parse(body)); originals.append((sid, body, 'PAPER_ATTACHMENT'))
    pin = digest(raw)
    event, citations = replay(envelope, pin, document, parents, registered)
    role = 'PAPER_ATTACHMENT' if envelope['sourceClass'] == 'ORIGINAL_JSON_ATTACHMENT' else 'DERIVED_FACTS'
    originals.insert(0, (SOURCE_ID, raw, role))
    return {'event': event, 'citations': citations, 'originals': originals,
            'source': {'sourceClass': envelope['sourceClass'], 'sourceDescription': envelope['sourceDescription'],
                'sourceSha256': pin, 'parentSourceSha256s': sorted(parents),
                'eventIdentityProof': event_identity_proof(envelope, pin),
                'replayStatus': 'EXACT_REGISTERED_SOURCE_VALUES', 'scientificQualification': 'NOT_RUN'}}


def verify_fact(document, fact, check, sources, root=None):
    from core_source_event_discovery import METHOD as DISCOVERY_METHOD, verify_fact as verify_discovered
    if check.get('method') == DISCOVERY_METHOD:
        return verify_discovered(document, fact, check, sources, root)
    """Reconstruct the value again; a ledger or VERIFIED flag cannot invent it."""
    require(fact['origin'] == 'PROGRAM_DERIVED' and fact['subject'] == 'FAMILY' and fact['availability'] == 'REPORTED'
            and check.get('method') == METHOD, 'REPLAY_PROOF_REQUIRED')
    require(SOURCE_ID in sources and sources[SOURCE_ID][0] == 'JSON_POINTER', 'ORIGINAL_REQUIRED')
    envelope = sources[SOURCE_ID][1]
    registered = set()
    if 'scope' in sources:
        registered = registered_pins(sources['scope'][1])
    parents = {meta['sha256']: (sid, sources[sid][1]) for sid, meta in document['sources'].items()
               if sid.startswith('special-event-parent-') and sources[sid][0] == 'JSON_POINTER'}
    require(all(pin in registered and document['sources'][sid]['role'] == 'PAPER_ATTACHMENT'
                for pin, (sid, _) in parents.items()), 'PARENT_ORIGINAL_NOT_REGISTERED')
    event, citations = replay(envelope, document['sources'][SOURCE_ID]['sha256'], document, parents, registered)
    require(event == fact['value'] and fact['rawValue'] == canonical(event).decode('utf-8')
            and citations == fact['citations'] and check.get('basis') == citations,
            'REPLAY_VALUE_OR_PROOF_MISMATCH')
    role = 'PAPER_ATTACHMENT' if envelope['sourceClass'] == 'ORIGINAL_JSON_ATTACHMENT' else 'DERIVED_FACTS'
    require(document['sources'][SOURCE_ID]['role'] == role, 'SOURCE_ROLE_MISMATCH')
    return event
