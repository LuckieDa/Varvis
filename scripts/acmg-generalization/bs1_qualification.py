"""Bind explicit BS1 applicability review to fixed sources and existing records.

This is a compiler, not a scientific reviewer. It never upgrades text proposals.
Unresolved reviews survive as blocked records with their source and explanation.
"""
import copy
from pathlib import Path

from bs1_expert_batch import content_hash
from bs1_knowledge import require, sha, source_document

VERSION = 'bs1-knowledge-qualification-v1'
CATEGORIES = ('SOURCE_COMPLETENESS', 'PARAMETER_SCOPE', 'POPULATION_METRIC', 'ADDITIONAL_CONDITIONS', 'EXCEPTIONS')


def payload(record):
    return {k: v for k, v in record.items() if k not in {'review', 'qualification'}}


def resolve(document, locator):
    """RFC6901 JSON pointer, or existing GN.../ld locator into CSpec response."""
    parts = locator.split('/')
    if locator.startswith('/'):
        node = document
        parts = parts[1:]
    else:
        matches = [e for e in document.get('data', []) if e.get('entId') == parts[0]]
        require(len(matches) == 1, 'QUALIFICATION_SOURCE_LOCATOR_UNRESOLVED')
        node = matches[0]
        parts = parts[1:]
    try:
        for part in parts:
            part = part.replace('~1', '/').replace('~0', '~')
            if isinstance(node, list):
                require(part.isdecimal() and (part == '0' or not part.startswith('0')), 'QUALIFICATION_SOURCE_LOCATOR_UNRESOLVED')
                node = node[int(part)]
            else:
                node = node[part]
    except (KeyError, IndexError, TypeError, ValueError):
        raise ValueError('QUALIFICATION_SOURCE_LOCATOR_UNRESOLVED') from None
    return node


def compile_qualifications(document, sources, documents):
    require(document.get('schemaVersion') == 'bs1-qualification-input-v1', 'QUALIFICATION_SCHEMA')
    require(set(document) <= {'schemaVersion', 'reviewer', 'reviewedAt', 'basis', 'entries'}, 'QUALIFICATION_UNKNOWN_FIELD')
    require(all(isinstance(document.get(k), str) and document[k].strip() for k in ('reviewer', 'reviewedAt', 'basis')),
            'QUALIFICATION_REVIEW_ATTRIBUTION')
    require(isinstance(document.get('entries'), list), 'QUALIFICATION_ENTRIES')
    source_by_id = {s['sourceId']: s for s in sources}
    result, seen = [], set()
    for entry in document['entries']:
        require(set(entry) == {'record', 'payloadSha256', 'sourceBindings', 'checks'}, 'QUALIFICATION_ENTRY_FIELDS')
        record = copy.deepcopy(entry['record'])
        require(isinstance(record, dict) and 'qualification' not in record, 'QUALIFICATION_RECURSIVE_INPUT')
        require(record.get('kind') in {'EXPERT_POLICY', 'MAX_CREDIBLE_AF'}, 'QUALIFICATION_RECORD_KIND')
        require(record.get('recordId') and record['recordId'] not in seen, 'QUALIFICATION_DUPLICATE_RECORD')
        seen.add(record['recordId'])
        require(content_hash(payload(record)) == entry['payloadSha256'], 'QUALIFICATION_PAYLOAD_CHANGED')
        original_review = record.get('review', {})
        require(original_review.get('status') == 'REVIEWED_FOR_LOCAL_USE'
                and original_review.get('contentSha256') == content_hash(payload(record))
                and original_review.get('dataScopePolicyId') == (
                    'BS1_JOINT_SEX_V1' if record.get('scope', {}).get('sexChromosome', {}).get('requiredDataset') == 'JOINT'
                    else 'BS1_LOCAL_EXOMES_V1')
                and all(original_review.get(k) for k in ('reviewer', 'reviewedAt', 'basis')), 'QUALIFICATION_PRIOR_ADOPTION_REQUIRED')
        referenced = set()
        for s in record.get('sources', []):
            known = source_by_id.get(s.get('sourceId'))
            require(known and all(s.get(k) == known.get(k) for k in ('sourceId', 'url', 'version', 'sha256')),
                    'QUALIFICATION_RECORD_SOURCE_UNBOUND')
            referenced.add(s['sourceId'])
        require(referenced, 'QUALIFICATION_SOURCE_REQUIRED')
        bindings, bound = set(), set()
        require(isinstance(entry['sourceBindings'], list) and entry['sourceBindings'], 'QUALIFICATION_SOURCE_REQUIRED')
        for b in entry['sourceBindings']:
            require(set(b) == {'sourceId', 'locator', 'contentSha256'} and b['sourceId'] in referenced
                    and isinstance(b['locator'], str) and b['locator'], 'QUALIFICATION_SOURCE_BINDING_INVALID')
            key = (b['sourceId'], b['locator'])
            require(key not in bindings, 'QUALIFICATION_DUPLICATE_BINDING'); bindings.add(key)
            require(b['sourceId'] in documents, 'QUALIFICATION_SOURCE_FORMAT_UNSUPPORTED')
            source_node = resolve(documents[b['sourceId']], b['locator'])
            require(content_hash(source_node) == b['contentSha256'], 'QUALIFICATION_SOURCE_CONTENT_CHANGED')
            bound.add(b['sourceId'])
        require(bound == referenced, 'QUALIFICATION_SOURCE_INCOMPLETE')
        require(isinstance(entry['checks'], dict) and set(entry['checks']) == set(CATEGORIES), 'QUALIFICATION_CHECKS_INCOMPLETE')
        for category, check in entry['checks'].items():
            require(set(check) == {'status', 'basis', 'sourceIds'} and isinstance(check['basis'], str) and check['basis'].strip(),
                    'QUALIFICATION_CHECK_INVALID')
            ids = check['sourceIds']
            require(isinstance(ids, list) and ids and all(isinstance(i, str) for i in ids)
                    and len(ids) == len(set(ids)) and set(ids) <= bound, 'QUALIFICATION_CHECK_SOURCE_UNBOUND')
            expected = ('PUBLISHED_THRESHOLD_NOT_RECALCULATED' if record['kind'] == 'EXPERT_POLICY' else 'MODEL_PARAMETERS_MATCHED') if category == 'PARAMETER_SCOPE' else 'SATISFIED'
            require(check['status'] in {expected, 'UNRESOLVED'}, 'QUALIFICATION_CHECK_INVALID')
        record['qualification'] = dict(schemaVersion=VERSION, payloadSha256=entry['payloadSha256'], scope=copy.deepcopy(record['scope']),
                                       sourceBindings=copy.deepcopy(entry['sourceBindings']), checks=copy.deepcopy(entry['checks']),
                                       priorReview=copy.deepcopy(original_review),
                                       **{k: document[k] for k in ('reviewer', 'reviewedAt', 'basis')})
        # Keep the original receipt verbatim under priorReview. The new outer
        # binding is attributed to this qualification author, not the old reviewer.
        record['review'].update({k: document[k] for k in ('reviewer', 'reviewedAt', 'basis')})
        record['review']['contentSha256'] = content_hash({k: v for k, v in record.items() if k != 'review'})
        result.append(record)
    return result


def qualify_from_manifest(documents_to_compile, manifest, manifest_path, sources, records):
    needed = {b['sourceId'] for d in documents_to_compile for e in d['entries'] for b in e['sourceBindings']}
    originals = {}
    for s in manifest['sources']:
        if s['sourceId'] not in needed: continue
        path = (Path(manifest_path).parent / s['path']).resolve()
        require(path.stat().st_size <= 64 * 1024 * 1024, 'QUALIFICATION_SOURCE_SIZE')
        raw = path.read_bytes()
        require(sha(raw) == s['sha256'], 'QUALIFICATION_SOURCE_SHA_CHANGED')
        originals[s['sourceId']] = source_document(raw, s)
    updated = list(records)
    seen = set()
    for document in documents_to_compile:
        for record in compile_qualifications(document, sources, originals):
            require(record['recordId'] not in seen, 'QUALIFICATION_DUPLICATE_RECORD'); seen.add(record['recordId'])
            indexes = [i for i, r in enumerate(updated) if r['recordId'] == record['recordId']]
            require(len(indexes) <= 1, 'QUALIFICATION_DUPLICATE_RECORD')
            if indexes:
                i = indexes[0]
                require(content_hash(payload(updated[i])) == content_hash(payload(record)), 'QUALIFICATION_CANNOT_REPLACE_DIFFERENT_RECORD')
                updated[i] = record
            else:
                updated.append(record)
    return updated
