"""Batch disease knowledge intake. Downloaded facts never become reviewed BS1 rules.

Only public knowledge is fetched here; variant evaluation remains offline. All
outputs are new files. An explicit manifest pins every input to its raw SHA256.
"""
import argparse
from collections import Counter
import csv
import hashlib
import gzip
import io
import json
from pathlib import Path
import re
import time
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

VERSION = 'bs1-knowledge-import-v3'
URLS = {
    'gencc': 'https://thegencc.org/download/action/submissions-export-csv?format=new',
    'orphadata': 'https://www.orphadata.com/data/xml/en_product9_prev.xml',
}
MODES = {'AD': 'AUTOSOMAL_DOMINANT', 'AR': 'AUTOSOMAL_RECESSIVE',
         'HP:0000006': 'AUTOSOMAL_DOMINANT', 'HP:0000007': 'AUTOSOMAL_RECESSIVE'}
READY = {'Definitive', 'Strong', 'Moderate'}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def read_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'DUPLICATE_JSON_KEY')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda _: require(False, 'NONFINITE_JSON'))


def source_document(raw, source):
    """Replay declared originals; binary identity is not automatic semantics."""
    kind = source.get('kind')
    if kind == 'GENCC_SUBMISSION_HTML':
        from bs1_gencc_submission import parse_submission
        return parse_submission(raw, source['url'])
    if kind in {'PINNED_PDF_REFERENCE', 'PINNED_TEXT_REFERENCE'}:
        reference = {'rawSha256': sha(raw), 'byteLength': len(raw)}
        if kind == 'PINNED_PDF_REFERENCE':
            require(raw.startswith(b'%PDF-') and b'%%EOF' in raw[-1024:], 'REFERENCE_PDF_BYTES_INVALID')
            reference['mediaType'] = 'application/pdf'
            return {'reference': reference}
        try:
            text = raw.decode('utf-8')
        except UnicodeError:
            raise ValueError('REFERENCE_TEXT_UTF8_INVALID') from None
        require(text and '\x00' not in text, 'REFERENCE_TEXT_BYTES_INVALID')
        reference['mediaType'] = 'text/plain'
        return {'reference': reference, 'text': text}
    return read_json(raw)


def write(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as output:
        json.dump(value, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write('\n')


def fetch(kind, directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    url = URLS[kind]
    receipt = {'url': url, 'startedAtEpoch': time.time(), 'attempts': 1,
               'maximumBytes': 64 * 1024 * 1024, 'deadlineSeconds': 1800}
    class Redirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            host = urllib.parse.urlsplit(newurl)
            require(host.scheme == 'https' and host.hostname in {
                'thegencc.org', 'www.thegencc.org', 'www.orphadata.com',
                'sciences.orphadata.com'}, 'SOURCE_REDIRECT_NOT_ALLOWED')
            return super().redirect_request(req, fp, code, msg, headers, newurl)
    raw = bytearray()
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'VarvisKnowledgeImport/1.0', 'Accept-Encoding': 'gzip'})
        with urllib.request.build_opener(Redirect).open(req, timeout=30) as response:
            receipt.update(status=response.status, finalUrl=response.url,
                           headers=dict(response.headers))
            require(response.status == 200, 'SOURCE_HTTP_STATUS')
            while True:
                require(time.time() - receipt['startedAtEpoch'] <= receipt['deadlineSeconds'], 'SOURCE_DEADLINE')
                chunk = response.read(65536)
                if not chunk:
                    break
                raw.extend(chunk)
                require(len(raw) <= receipt['maximumBytes'], 'SOURCE_SIZE_LIMIT')
            length = response.headers.get('Content-Length')
            require(length is None or (length.isdecimal() and int(length) == len(raw)), 'SOURCE_LENGTH_MISMATCH')
        filename = 'source.csv' if kind == 'gencc' else 'source.xml'
        (directory / 'response.bin').write_bytes(raw)
        receipt.update(responseBytes=len(raw), responseSha256=sha(raw))
        if receipt['headers'].get('Content-Encoding', '').lower() == 'gzip':
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as zipped:
                raw = zipped.read(receipt['maximumBytes'] + 1)
            require(len(raw) <= receipt['maximumBytes'], 'SOURCE_DECOMPRESSED_SIZE_LIMIT')
        (directory / filename).write_bytes(raw)
        receipt.update(outcome='CAPTURED_NOT_SCIENTIFICALLY_ADOPTED', file=filename,
                       bytes=len(raw), sha256=sha(raw))
    except Exception as error:
        if raw:
            (directory / 'partial.bin').write_bytes(raw)
        receipt.update(outcome='FAILED', receivedBytes=len(raw), error=str(error))
    receipt['finishedAtEpoch'] = time.time()
    write(directory / 'receipt.json', receipt)
    return receipt


def gencc(raw):
    reader = csv.DictReader(io.StringIO(raw.decode('utf-8-sig')))
    require({'sgc_id', 'version_number', 'gene_curie', 'disease_curie',
             'moi_curie', 'classification_title'}.issubset(reader.fieldnames or []), 'GENCC_NEW_HEADER_REQUIRED')
    seen = set()
    for number, row in enumerate(reader, 2):
        require(None not in row and all(value is not None for value in row.values()), 'GENCC_INCOMPLETE_ROW')
        identity = (row['sgc_id'], row['version_number'])
        require(all(identity) and identity not in seen, 'GENCC_DUPLICATE_OR_MISSING_SUBMISSION')
        seen.add(identity)
        yield {'hgncId': row['gene_curie'], 'mondoId': row['disease_curie'],
               'moi': MODES.get(row['moi_curie'], row['moi_curie']),
               'classification': row['classification_title'],
               'diseaseLabel': row.get('disease_title', ''), 'sourceRow': number,
               'classificationDate': row.get('submitted_as_date', ''),
               'reportLocator': row.get('submitted_as_public_report_url', ''),
               'original': {k: row.get(k, '') for k in ('sgc_id', 'version_number', 'gene_curie', 'gene_symbol',
                   'disease_curie', 'disease_original_curie', 'disease_original_title', 'moi_curie', 'classification_curie',
                   'classification_title', 'submitter_curie', 'submitter_title', 'submitted_as_date', 'submitted_as_public_report_url')}}


def orphanet(raw):
    require(b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper(), 'XML_ENTITIES_FORBIDDEN')
    tree = ET.fromstring(raw)
    for disorder in tree.findall('.//Disorder'):
        code = disorder.findtext('OrphaCode')
        require(code and code.isdecimal(), 'ORPHA_CODE_REQUIRED')
        for item in disorder.findall('./PrevalenceList/Prevalence'):
            # Preserve the complete record. Incidence/classes are NOT converted to point prevalence.
            yield {'orphaId': 'ORPHA:' + code, 'diseaseLabel': disorder.findtext('Name'),
                   'sourceRecordId': item.get('id'), 'sourceCitation': item.findtext('Source'),
                   'disorderGroup': disorder.findtext('./DisorderGroup/Name'),
                   'disorderType': disorder.findtext('./DisorderType/Name'),
                   'type': item.findtext('./PrevalenceType/Name'),
                   'geography': item.findtext('./PrevalenceGeographic/Name'),
                   'mean': item.findtext('ValMoy'), 'class': item.findtext('./PrevalenceClass/Name'),
                   'qualification': item.findtext('./PrevalenceQualification/Name'),
                   'validation': item.findtext('./PrevalenceValidationStatus/Name'),
                   'originalXml': ET.tostring(item, encoding='unicode')}


def compile_manifest(manifest_path, output):
    manifest_path, output = Path(manifest_path), Path(output)
    manifest = read_json(manifest_path.read_bytes())
    require(manifest.get('schemaVersion') == 'bs1-knowledge-input-v1', 'MANIFEST_SCHEMA')
    sources, relations, epidemiology, records, specifications, expert_scopes = [], [], [], [], [], []
    expert_reviews, disease_mappings, qualification_reviews, source_documents = [], [], [], {}
    for source in manifest['sources']:
        path = (manifest_path.parent / source['path']).resolve()
        require(path.stat().st_size <= 64 * 1024 * 1024, 'SOURCE_TOO_LARGE')
        raw = path.read_bytes()
        require(sha(raw) == source['sha256'], 'SOURCE_SHA_MISMATCH')
        require(source['url'].startswith('https://') and source['version'], 'SOURCE_PROVENANCE')
        identity = {k: source[k] for k in ('sourceId', 'url', 'version', 'sha256')}
        require(identity['sourceId'] not in [s['sourceId'] for s in sources], 'DUPLICATE_SOURCE_ID')
        sources.append(identity)
        kind = source['kind']
        if kind in {'CLINGEN_JSON', 'GENCC_CSV', 'GENCC_SUBMISSION_HTML'}:
            if kind == 'GENCC_SUBMISSION_HTML':
                rows = source_document(raw, source)
            else:
                rows = read_json(raw) if kind == 'CLINGEN_JSON' else gencc(raw)
            if kind in {'CLINGEN_JSON', 'GENCC_SUBMISSION_HTML'}:
                source_documents[identity['sourceId']] = rows
            for row in rows:
                row = dict(row)
                row['moi'] = MODES.get(row['moi'], row['moi'])
                row['sourceId'] = identity['sourceId']
                relations.append(row)
        elif kind in {'PINNED_PDF_REFERENCE', 'PINNED_TEXT_REFERENCE'}:
            source_documents[identity['sourceId']] = source_document(raw, source)
        elif kind == 'ORPHA_XML':
            epidemiology.extend(dict(row, sourceId=identity['sourceId']) for row in orphanet(raw))
        elif kind == 'CURATED_RECORDS':
            doc = read_json(raw)
            require(doc.get('schemaVersion') == 'bs1-knowledge-records-v1', 'RECORDS_SCHEMA')
            records.extend(doc['records'])
        elif kind == 'EXPERT_CLAUSE_REVIEW':
            expert_reviews.append(read_json(raw))
        elif kind == 'QUALIFICATION_REVIEW':
            qualification_reviews.append(read_json(raw))
        elif kind == 'MONDO_SSSOM':
            lines = [(number, line) for number, line in enumerate(raw.decode('utf-8-sig').splitlines(), 1) if not line.startswith('#')]
            reader = csv.DictReader((line for _, line in lines), delimiter='\t')
            require({'subject_id', 'predicate_id', 'object_id'}.issubset(reader.fieldnames or []), 'MONDO_MAPPING_HEADER')
            for row in reader:
                require(None not in row and all(v is not None for v in row.values()), 'MONDO_MAPPING_INCOMPLETE')
                match = re.fullmatch(r'(?:Orphanet|ORPHA):(\d+)', row['object_id'])
                if row['predicate_id'] == 'skos:exactMatch' and re.fullmatch(r'MONDO:[0-9]{7}', row['subject_id']) and match:
                    disease_mappings.append({'mondoId': row['subject_id'], 'orphaId': 'ORPHA:' + match[1],
                                             'sourceId': identity['sourceId'], 'sourceRow': lines[reader.line_num - 1][0], 'original': row})
        elif kind == 'CSPEC_RAW':
            doc = read_json(raw)
            source_documents[identity['sourceId']] = doc
            require(doc.get('status', {}).get('code') == 200 and isinstance(doc.get('data'), list), 'CSPEC_RESPONSE_INVALID')
            for entity in doc['data']:
                content = entity.get('entContent', {})
                states = content.get('states', [])
                if [s.get('name') for s in states if s.get('current') is True] != ['Released']:
                    continue
                # Keep complete rules incl. cross-rule restrictions. No regex-to-executable promotion.
                if content.get('legacyReplaced') is True or content.get('legacyFullySuperseded') is False:
                    continue
                specifications.append({'specificationId': entity['entId'], 'version': content.get('version'),
                                       'sourceId': identity['sourceId'], 'original': entity,
                                       'status': 'STRUCTURING_REQUIRED'})
                genes = {g.get('entContent', {}).get('HGNC', {}).get('symbol'):
                         g.get('entContent', {}).get('HGNC', {}).get('hgnc_id') for g in entity.get('ld', {}).get('Gene', [])}
                for rule in entity.get('ld', {}).get('CriteriaCode', []):
                    clause = rule.get('entContent', {})
                    if clause.get('label') != 'BS1':
                        continue
                    for symbol in clause.get('gene', []):
                        gene = genes.get(symbol)
                        if not gene:
                            continue
                        for disease in clause.get('disease', []) or ['*']:
                            expert_scopes.append({'hgncId': gene, 'mondoId': disease,
                                                  'specificationId': entity['entId'], 'version': content.get('version'),
                                                  'sourceId': identity['sourceId'], 'status': 'STRUCTURING_REQUIRED'})
        else:
            raise ValueError('SOURCE_KIND_UNSUPPORTED:' + kind)
    from bs1_expert_batch import compile_reviews
    sex_preparations = []
    for review in expert_reviews:
        from bs1_expert_sex_chromosome import VERSION as sex_translation, prepare_reviews
        if any(s.get('translation') == sex_translation for s in review['specifications']):
            preparation = prepare_reviews(review, specifications, sources, source_documents)
            records.extend(preparation.pop('records'))
            sex_preparations.append(dict(preparation, specificationIds=[s['specificationId'] for s in review['specifications']]))
        else:
            records.extend(compile_reviews(review, specifications, sources))
    if qualification_reviews:
        from bs1_qualification import qualify_from_manifest
        records = qualify_from_manifest(qualification_reviews, manifest, manifest_path, sources, records)
    grouped = {}
    rejected = []
    for row in relations:
        if not re.fullmatch(r'HGNC:[1-9][0-9]*', row['hgncId']) or not re.fullmatch(r'MONDO:[0-9]{7}', row['mondoId']):
            rejected.append(dict(row, reasonCode='EXACT_DISEASE_ID_MAPPING_REQUIRED'))
            continue
        key = (row['hgncId'], row['mondoId'], row['moi'])
        grouped.setdefault(key, []).append(row)
    backgrounds = []
    for (gene, disease, mode), assertions in sorted(grouped.items()):
        classes = {a['classification'] for a in assertions}
        reason = ('BS1_MODEL_MOI_UNSUPPORTED' if mode not in MODES.values() else
                  'BS1_RELATIONSHIP_CONFLICT' if len(classes) > 1 and not classes.issubset(READY) else
                  'BS1_RELATIONSHIP_NOT_ESTABLISHED' if not classes.issubset(READY) else
                  'BS1_DISEASE_PARAMETERS_MISSING')
        backgrounds.append({'hgncId': gene, 'mondoId': disease, 'moi': mode,
                            'assertions': assertions, 'reasonCode': reason})
    output.mkdir(parents=True, exist_ok=False)
    # GenCC's submitted ORPHA identifier is a traceable candidate mapping, not a
    # blanket proof of disease equivalence and never an automatic parameter adoption.
    epi_by_id = {}
    for row in epidemiology:
        epi_by_id.setdefault(row['orphaId'], []).append(row)
    from bs1_parameters import assess
    assessed, observations, parameter_report = assess(backgrounds, epidemiology, disease_mappings,
                                                      specifications, records, sources)
    drafts = []
    for background, assessment in zip(backgrounds, assessed):
        candidates = []
        mappings = [m for m in disease_mappings if m['mondoId'] == background['mondoId']]
        # Exact cross-references narrow the search, but do not adopt epidemiology
        # or silently prefer one target when source ontologies map to several.
        for mapping in mappings:
            candidates.extend(dict(row, diseaseMapping=mapping) for row in epi_by_id.get(mapping['orphaId'], []))
        for assertion in background['assertions']:
            raw_id = assertion.get('original', {}).get('disease_original_curie', '')
            match = re.fullmatch(r'(?:ORPHA|Orphanet):(\d+)', raw_id)
            if match:
                candidates.extend(epi_by_id.get('ORPHA:' + match[1], []))
        drafts.append(dict(assessment, epidemiologyCandidates=candidates))
    from bs1_parameter_text import extract
    from bs1_parameter_supply import summarize, key as supply_key
    proposals, text_drafts, text_report = extract(specifications, sources, backgrounds)
    summaries = summarize(drafts, observations, proposals, text_drafts)
    explained_backgrounds = [dict(b, parameterSupply=summaries[supply_key(b)]) for b in backgrounds]
    bundle = {'schemaVersion': 'bs1-knowledge-bundle-v1', 'version': manifest['version'],
              'importerVersion': VERSION, 'sources': sources, 'backgrounds': explained_backgrounds,
              'records': records, 'expertScopes': expert_scopes, 'claim': 'BATCH_KNOWLEDGE_NOT_PATIENT_DIAGNOSIS'}
    write(output / 'bundle.json', bundle)
    if sex_preparations:
        write(output / 'expert-sex-preparation.json', sex_preparations)
    write(output / 'epidemiology.json', epidemiology)
    write(output / 'specifications.json', specifications)
    write(output / 'parameter-drafts.json', drafts)
    write(output / 'parameter-observations.json', observations)
    parameter_report['loadedSourceKinds'] = dict(sorted(Counter(s['kind'] for s in manifest['sources']).items()))
    parameter_report['runtimeBundleSha256'] = sha((output / 'bundle.json').read_bytes())
    write(output / 'parameter-coverage.json', parameter_report)
    write(output / 'parameter-text-proposals.json', proposals)
    write(output / 'parameter-text-backgrounds.json', text_drafts)
    write(output / 'parameter-text-coverage.json', text_report)
    write(output / 'rejected-relationships.json', rejected)
    write(output / 'disease-mappings.json', disease_mappings)
    report = {'relationshipAssertions': len(relations), 'exactBackgrounds': len(backgrounds),
              'eligibleRelationshipBackgrounds': sum(b['reasonCode'] == 'BS1_DISEASE_PARAMETERS_MISSING' for b in backgrounds),
              'epidemiologyObservations': len(epidemiology), 'releasedSpecificationsCaptured': len(specifications),
              'curatedRecordCandidates': len(records), 'automaticScientificApprovals': 0,
              'epidemiologyLinkedCandidateBackgrounds': sum(bool(d['epidemiologyCandidates']) for d in drafts),
              'expertScopeBarriers': len(expert_scopes),
              'exactDiseaseMappingCandidates': len(disease_mappings),
              'bundleSha256': sha((output / 'bundle.json').read_bytes()),
              'note': 'Relationship and mapped epidemiology coverage are not BS1 evaluation coverage. All parameter drafts remain UNREVIEWED; exact ontology links alone do not approve prevalence, penetrance, contributions or strength.'}
    write(output / 'coverage.json', report)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    download = commands.add_parser('fetch')
    download.add_argument('--source', choices=URLS, required=True)
    download.add_argument('--output', required=True)
    compiler = commands.add_parser('compile')
    compiler.add_argument('--manifest', required=True)
    compiler.add_argument('--output', required=True)
    args = parser.parse_args()
    result = fetch(args.source, args.output) if args.command == 'fetch' else compile_manifest(args.manifest, args.output)
    print(json.dumps(result, ensure_ascii=True))
    if result.get('outcome') == 'FAILED':
        raise SystemExit(2)
