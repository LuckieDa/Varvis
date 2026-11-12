"""Create a new local de novo source snapshot; never qualify science or activate it.

Use one pinned knowledge base and explicitly supplied report/JATS/ClinVar files.
This replaces manual path/hash assembly. No network, model, patient remapping,
recursive directory scan, threshold changes or fabricated review receipts.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from prepare_literature import prepare
from literature_allele_identity import read_record

VERSION = 'denovo-material-registration-1.1.0'
MAX_FILE = 1_048_576
MAX_TOTAL = 16_777_216


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def unique(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ValueError('REGISTRATION_DUPLICATE_JSON_KEY')
        out[key] = value
    return out


def read(path, pin=None):
    path = Path(path)
    before = path.stat()
    if not path.is_file() or not 0 < before.st_size <= MAX_FILE:
        raise ValueError('REGISTRATION_FILE_SIZE_LIMIT')
    with path.open('rb') as stream:
        raw = stream.read(MAX_FILE + 1)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or len(raw) != before.st_size:
        raise ValueError('REGISTRATION_SOURCE_CHANGED')
    if pin is not None and sha(raw) != pin:
        raise ValueError('REGISTRATION_PIN_MISMATCH')
    return raw


def xml_root(raw):
    text = raw.decode('utf-8-sig')
    if re.search(r'<!\s*(?:ENTITY)\b|<!DOCTYPE[^>]*\[', text, re.I):
        raise ValueError('REGISTRATION_XML_ENTITY_FORBIDDEN')
    return ET.fromstring(text)


def snapshot(base_file, base_pin, reports=(), papers=(), variants=()):
    """In-memory, bounded proposal. Production DeNovoSelectedBatch remains the final loader."""
    if not re.fullmatch(r'[0-9a-f]{64}', base_pin):
        raise ValueError('REGISTRATION_BASE_PIN_INVALID')
    if len(reports) > 100 or len(papers) > 32 or len(variants) > 100:
        raise ValueError('REGISTRATION_INPUT_COUNT_LIMIT')
    base_file = Path(base_file).resolve(strict=True)
    raw = read(base_file, base_pin)
    manifest = json.loads(raw, object_pairs_hook=unique)
    if set(manifest) - {'schemaVersion', 'materialClass', 'reports', 'sources', 'phenotypePolicies', 'catalog', 'selectorPolicies', 'literature', 'citationRecords'} or manifest.get('schemaVersion') != 'denovo-selected-materials-v1':
        raise ValueError('REGISTRATION_BASE_SCHEMA')
    if manifest.get('materialClass') not in {'SYNTHETIC', 'SOURCE_ASSESSED_RESEARCH'}:
        raise ValueError('REGISTRATION_BASE_CLASS')
    assets, origins, notices = {}, [], []

    def retain(data, original_path):
        pin = sha(data)
        if pin not in assets:
            if sum(map(len, assets.values())) + len(data) > MAX_TOTAL:
                raise ValueError('REGISTRATION_TOTAL_SIZE_LIMIT')
            assets[pin] = data
        origins.append({'sha256': pin, 'bytes': len(data), 'originalPath': str(original_path)})
        return {'path': 'originals/' + pin, 'sha256': pin}

    def inherited(binding):
        if not isinstance(binding, dict) or set(binding) - {'sourceId', 'path', 'sha256'} or not {'path', 'sha256'} <= set(binding):
            raise ValueError('REGISTRATION_BASE_BINDING')
        relative = Path(binding['path'])
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('REGISTRATION_PATH_OUTSIDE_BASE')
        path = (base_file.parent / relative).resolve(strict=True)
        if not path.is_relative_to(base_file.parent):
            raise ValueError('REGISTRATION_PATH_OUTSIDE_BASE')
        value = retain(read(path, binding['sha256']), path)
        return {'sourceId': binding['sourceId'], **value} if 'sourceId' in binding else value

    result = copy.deepcopy(manifest)
    for key, maximum in [('sources', 100), ('reports', 100), ('phenotypePolicies', 100)]:
        if not isinstance(result.get(key), list) or len(result[key]) > maximum:
            raise ValueError('REGISTRATION_BASE_COUNT_LIMIT')
        result[key] = [inherited(b) for b in result[key]]
    for key in ['catalog', 'selectorPolicies']:
        result[key] = inherited(result[key])
    source_ids = {}
    for s in result['sources']:
        if s['sourceId'] in source_ids:
            raise ValueError('REGISTRATION_SOURCE_ID_DUPLICATE')
        source_ids[s['sourceId']] = s['sha256']

    def source(source_id, data, path):
        b = retain(data, path)
        if source_id in source_ids:
            if source_ids[source_id] != b['sha256']:
                raise ValueError('REGISTRATION_SOURCE_ID_CONFLICT')
        else:
            for existing_id, pin in source_ids.items():
                if pin == b['sha256']:
                    return existing_id
            result['sources'].append({'sourceId': source_id, **b})
            source_ids[source_id] = b['sha256']
        return source_id

    cases, report_pins = [], {r['sha256'] for r in result['reports']}
    for path in reports:
        data = read(path); n = json.loads(data, object_pairs_hook=unique)
        if n.get('schemaVersion') != 'denovo-local-report-v1' or n.get('sourceClass') not in {'SYNTHETIC', 'LOCAL_REPORT'}:
            raise ValueError('REGISTRATION_REPORT_SCHEMA')
        if n['sourceClass'] == 'SYNTHETIC' and result['materialClass'] != 'SYNTHETIC':
            raise ValueError('REGISTRATION_SYNTHETIC_REPORT_IN_RESEARCH')
        b = retain(data, path)
        if b['sha256'] not in report_pins:
            result['reports'].append(b); report_pins.add(b['sha256'])
        probands = [m.get('memberId') for m in n.get('members', []) if m.get('role') == 'PROBAND']
        if len(probands) != 1 or not isinstance(probands[0], str) or not probands[0].strip() or not all(isinstance(n.get(k), str) and n[k].strip() for k in ['caseId', 'familyId']):
            raise ValueError('REGISTRATION_REPORT_CASE_IDENTITY')
        if not any(c['sourceSha256'] == b['sha256'] for c in cases):
            cases.append({'sourceSha256': b['sha256'], 'caseId': n['caseId'], 'familyId': n['familyId'],
                          'probandId': probands[0], 'target': n.get('target'), 'bindingStatus': 'EXPLICIT_CURRENT_CASE_BINDING_REQUIRED'})

    records = []
    for path in variants:
        data = read(path); root = xml_root(data)
        archives = [root] if root.tag == 'VariationArchive' else root.findall('VariationArchive')
        if len(archives) != 1:
            raise ValueError('REGISTRATION_SINGLE_VCV_REQUIRED')
        vcv = archives[0].get('Accession', '')
        record = read_record(data, sha(data), vcv)
        records.append((source('clinvar-' + vcv, data, path), vcv, record))

    citations=result.get('citationRecords',[])
    if not isinstance(citations,list) or len(citations)>100:
        raise ValueError('REGISTRATION_CITATION_COUNT_LIMIT')
    for binding in citations:
        if not isinstance(binding,dict) or set(binding)!={'sourceId','vcv'} or binding['sourceId'] not in source_ids:
            raise ValueError('REGISTRATION_CITATION_BINDING')
        raw=assets[source_ids[binding['sourceId']]]
        read_record(raw,sha(raw),binding['vcv'])
    for sid,vcv,_ in records:
        binding={'sourceId':sid,'vcv':vcv}
        if binding not in citations:citations.append(binding)
    if len(citations)>100:raise ValueError('REGISTRATION_CITATION_COUNT_LIMIT')
    if citations or 'citationRecords' in result:result['citationRecords']=citations

    documents = result.get('literature', [])
    if not isinstance(documents, list) or len(documents) > 32:
        raise ValueError('REGISTRATION_LITERATURE_LIMIT')
    by_pmc = {}
    for d in documents:
        if d['pmcid'] in by_pmc or d['sourceId'] not in source_ids:
            raise ValueError('REGISTRATION_BASE_LITERATURE_IDENTITY')
        by_pmc[d['pmcid']] = d
    for path in papers:
        data = read(path); root = xml_root(data)
        values = [n.text for n in root.findall('./front/article-meta/article-id') if n.get('pub-id-type') in {'pmc', 'pmcid'}]
        pmcids = {v if v.startswith('PMC') else 'PMC' + v for v in values if isinstance(v, str)}
        if len(pmcids) != 1:
            raise ValueError('REGISTRATION_PMCID_AMBIGUOUS')
        pmcid = next(iter(pmcids)); prepared = prepare(data, pmcid, sha(data))
        pmid = prepared['identifiers'].get('pmid')
        existing = by_pmc.get(pmcid)
        if existing and source_ids[existing['sourceId']] != sha(data):
            raise ValueError('REGISTRATION_PAPER_VERSION_CONFLICT')
        source_id = existing['sourceId'] if existing else source('literature-' + pmcid, data, path)
        document = existing or {'sourceId': source_id, 'pmcid': pmcid, 'alleles': []}
        bindings = {(a['sourceId'], a['vcv']) for a in document['alleles']}
        for sid, vcv, record in records:
            if pmid and any(p['pmid'] == pmid for p in record['pubmed']) and (sid, vcv) not in bindings:
                document['alleles'].append({'sourceId': sid, 'vcv': vcv}); bindings.add((sid, vcv))
        if not document['alleles']:
            notices.append({'pmcid': pmcid, 'reasonCode': 'LITERATURE_ALLELE_IDENTITY_SOURCE_REQUIRED'})
        if not existing:
            documents.append(document); by_pmc[pmcid] = document
    if len(documents) > 32 or len(result['reports']) > 100 or len(result['sources']) > 100 or any(len(d['alleles']) > 100 for d in documents):
        raise ValueError('REGISTRATION_OUTPUT_COUNT_LIMIT')
    if papers or 'literature' in result:
        result['literature'] = documents
    receipt = {'schemaVersion': VERSION, 'baseManifestSha256': base_pin, 'status': 'REGISTERED_PENDING_PRODUCTION_VALIDATION',
               'scientificApproval': 'NOT_GRANTED', 'clinicalApproval': 'NONE', 'networkCalls': 0, 'modelCalls': 0,
               'modelValidation': 'DEFERRED_NOT_CONFIGURED', 'completeLiteratureSearch': False,
               'sourceScope': 'EXPLICIT_LOCAL_INPUTS_ONLY', 'originals': origins, 'issues': notices,
               'caseBindings': cases}
    return result, assets, receipt


def write_snapshot(destination, manifest, assets, receipt):
    dest = Path(destination)
    # No overwrite, merge, activation, or cleanup of a partially written proposal.
    dest.mkdir(parents=False, exist_ok=False)
    (dest/'originals').mkdir()
    for pin, raw in assets.items():
        with (dest/'originals'/pin).open('xb') as f:
            f.write(raw)
        if sha((dest/'originals'/pin).read_bytes()) != pin:
            raise ValueError('REGISTRATION_COPY_MISMATCH')
    raw = json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8')
    receipt = {**receipt, 'manifestSha256': sha(raw)}
    with (dest/'manifest.json').open('xb') as f:
        f.write(raw)
    with (dest/'registration.json').open('x', encoding='utf-8') as f:
        json.dump(receipt, f, ensure_ascii=False, indent=2)
    return receipt


def files(directory, extension, maximum):
    if directory is None:
        return []
    if not Path(directory).is_dir():
        raise ValueError('REGISTRATION_DIRECTORY_UNAVAILABLE')
    paths = sorted(Path(directory).glob('*' + extension))
    if len(paths) > maximum:
        raise ValueError('REGISTRATION_DIRECTORY_COUNT_LIMIT')
    return paths


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base', required=True, type=Path)
    p.add_argument('--base-sha256', required=True)
    p.add_argument('--reports', type=Path)
    p.add_argument('--papers', type=Path)
    p.add_argument('--clinvar', type=Path)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    m, blobs, receipt = snapshot(a.base, a.base_sha256, files(a.reports, '.json', 100), files(a.papers, '.xml', 32), files(a.clinvar, '.xml', 100))
    receipt = write_snapshot(a.output, m, blobs, receipt)
    print(json.dumps({k: receipt[k] for k in ['status', 'manifestSha256', 'scientificApproval', 'networkCalls', 'modelCalls']}))


if __name__ == '__main__':
    main()
