"""Read existing pinned expert originals and literal paper facts, never curate.

The Java source owner supplies the exact bytes it already observed. This reader
does not reopen source paths, fetch documents, approve records, score evidence,
or create reviewed bundles. Native parsing is reused from the existing owners.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys

import prepare_expert_reference_evidence as expert
import prepare_reference_evidence as source

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from prepare_literature import prepare as read_jats
from extract_literature_cases import extract as literal_cases

VERSION = 'pp2-other-phenotype-native-reader-v1'
MAX_BYTES = 64 * 1024 * 1024


def raw_blob(value):
    source.require(isinstance(value, dict), 'PP2_SOURCE_BYTES_REQUIRED')
    raw = base64.b64decode(value['base64'], validate=True)
    source.require(len(raw) <= 4 * 1024 * 1024, 'PP2_SOURCE_TECHNICAL_BYTE_LIMIT')
    source.require(hashlib.sha256(raw).hexdigest() == value['rawSha256'], 'PP2_READER_SOURCE_PIN_MISMATCH')
    return raw


def reason(error):
    value = str(error)
    return value if value and len(value) <= 120 and all(c.isupper() or c.isdigit() or c == '_' for c in value) else 'PP2_NATIVE_SOURCE_READ_FAILED'


def read(document):
    source.require(document.get('schemaVersion') == 'pp2-other-phenotype-reader-input-v1', 'PP2_READER_INPUT_VERSION_INVALID')
    originals = {key: raw_blob(value) for key, value in document['originals'].items()}
    source.require(all(key == hashlib.sha256(value).hexdigest() for key, value in originals.items()), 'PP2_READER_ARCHIVE_KEY_MISMATCH')
    result = {'schemaVersion': VERSION, 'expertScopes': [], 'literature': [], 'scientificAdoption': False}
    for scope in document['expertScopes']:
        try:
            result['expertScopes'].append(expert_scope(scope, originals))
        except (ValueError, KeyError, TypeError, IndexError, OSError) as error:
            result['expertScopes'].append({'scopeId': scope['scopeId'], 'manifestRawSha256': scope['manifestRawSha256'],
                                          'status': 'UNAVAILABLE', 'reasonCode': reason(error), 'references': []})
    for paper in document['literature']:
        item = {'sourceId': paper['sourceId'], 'pmcid': paper['pmcid'], 'rawSha256': paper['rawSha256'],
                'status': 'UNAVAILABLE', 'relationQualification': 'NOT_EVALUATED'}
        try:
            raw = originals[paper['rawSha256']]
            prepared = read_jats(raw, paper['pmcid'], paper['rawSha256'])
            facts = literal_cases(prepared)
            item.update(status='SOURCE_FACTS_READ', title=prepared.get('title'), literalFacts=facts,
                        relationQualification='SOFTWARE_UNSUPPORTED', reasonCode='PP2_OTHER_PHENOTYPE_CASE_RELATIONSHIP_NOT_IMPLEMENTED')
        except (ValueError, KeyError, TypeError, IndexError, OSError) as error:
            item['reasonCode'] = reason(error)
        result['literature'].append(item)
    return result


def expert_scope(scope, originals):
    manifest = source.parse_json(originals[scope['manifestRawSha256']])
    source.require(manifest.get('schemaVersion') == 'missense-expert-evidence-manifest-v1', 'PP2_EXPERT_MANIFEST_VERSION_INVALID')
    source.require(isinstance(manifest.get('references'), list) and 0 < len(manifest['references']) <= 256, 'PP2_EXPERT_REFERENCE_COUNT_INVALID')
    source.require(len({r['referenceId'] for r in manifest['references']}) == len(manifest['references']), 'PP2_DUPLICATE_EXPERT_REFERENCE')
    source.require(len({(r['variationId'], r['scv']) for r in manifest['references']}) == len(manifest['references']), 'PP2_DUPLICATE_EXPERT_ASSERTION')
    source.require(isinstance(manifest.get('sources'), list) and len(manifest['sources']) <= 768, 'PP2_EXPERT_SOURCE_COUNT_INVALID')
    sources = {}
    pins = {}
    for descriptor in manifest['sources']:
        expert.official_source(descriptor)
        source.require(descriptor['sourceId'] not in pins, 'PP2_DUPLICATE_SOURCE_ID')
        pins[descriptor['sourceId']] = descriptor['rawSha256']
        pin = descriptor['rawSha256']
        if pin in originals:
            source.require(hashlib.sha256(originals[pin]).hexdigest() == pin, 'PP2_READER_SOURCE_PIN_MISMATCH')
            sources[descriptor['sourceId']] = descriptor, originals[pin]
    rows = []
    for reference in manifest['references']:
        row = {'referenceId': reference.get('referenceId'), 'declaredAllele': reference.get('allele'),
               'requiredSourceIds': [reference[k] for k in ('clinvarSource', 'sepioSource', 'summarySource') if reference.get(k)],
               'status': 'UNAVAILABLE', 'reasonCode': 'PP2_EXPERT_ORIGINAL_UNAVAILABLE'}
        try:
            facts = expert.extract(reference, sources)
            row.update(status='SOURCE_FACTS_READ', reasonCode=None, facts=facts)
        except (ValueError, KeyError, TypeError, IndexError, OSError) as error:
            row['reasonCode'] = reason(error)
        rows.append(row)
    return {'scopeId': scope['scopeId'], 'manifestRawSha256': scope['manifestRawSha256'], 'sourcePins': pins, 'references': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    raw = Path(args.input).read_bytes()
    source.require(len(raw) <= MAX_BYTES, 'PP2_READER_TECHNICAL_INPUT_LIMIT')
    value = read(source.parse_json(raw))
    value['inputRawSha256'] = hashlib.sha256(raw).hexdigest()
    output = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
    source.require(len(output) <= MAX_BYTES, 'PP2_READER_TECHNICAL_OUTPUT_LIMIT')
    with Path(args.output).open('xb') as stream:
        stream.write(output)


if __name__ == '__main__':
    main()
