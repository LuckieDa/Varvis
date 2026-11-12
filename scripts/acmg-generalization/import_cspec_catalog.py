"""Bounded official CSpec capture and fail-closed BA1 snapshot compilation.

Only a complete, stable public enumeration can produce a publishable snapshot.
Raw API objects are retained. Natural-language conditions outside the finite
grammar remain UNSUPPORTED; this tool never infers general applicability from
an empty field or an original-ACMG-summary template.
"""
import argparse
import contextlib
import copy
import csv
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
from types import SimpleNamespace
import urllib.parse
import uuid

from fetch_source import capture, save_json
from cspec_ba1_semantics import ancillary_version_text, compile_frequency, load_review, related_ba1_conditions

SCHEMA = 'a01-ba1-cspec-snapshot-v1'
VERSION = '1.1.1'
PUBLISHER_VERSION = '1.0.0'
BASE = 'https://cspec.genome.network/cspec'
HGNC_URL = 'https://storage.googleapis.com/public-download-files/hgnc/tsv/tsv/hgnc_complete_set.txt'
TYPE = 'SequenceVariantInterpretation'
META_FIELDS = 'entId,ldhId,rev,modified,entContent'
CONTENT_FIELDS = 'entId,entType,ldhId,rev,modified,entContent,ld.Gene,ld.RuleSet,ld.CriteriaCode,ld.File'
MOI = {'Autosomal dominant inheritance': 'AUTOSOMAL_DOMINANT',
       'Autosomal recessive inheritance': 'AUTOSOMAL_RECESSIVE',
       'Autosomal Dominant': 'AUTOSOMAL_DOMINANT', 'Autosomal Recessive': 'AUTOSOMAL_RECESSIVE',
       'X-linked inheritance': 'X_LINKED', 'X-linked dominant inheritance': 'X_LINKED_DOMINANT',
       'X-linked recessive inheritance': 'X_LINKED_RECESSIVE', 'Y-linked inheritance': 'Y_LINKED'}
ANCESTRIES = ['afr', 'amr', 'eas', 'mid', 'nfe', 'sas']


class Invalid(ValueError):
    pass


def require(ok, reason):
    if not ok:
        raise Invalid(reason)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def strict_json(raw):
    def pairs(items):
        out = {}
        for key, value in items:
            require(key not in out, 'DUPLICATE_JSON_KEY')
            out[key] = value
        return out
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(Invalid('NONFINITE_JSON')))


def payload(raw):
    obj = strict_json(raw)
    require(isinstance(obj, dict) and obj.get('status', {}).get('code') == 200, 'API_STATUS_INVALID')
    require(isinstance(obj.get('metadata', {}).get('rendered', {}).get('when'), str), 'API_RENDER_IDENTITY_MISSING')
    return obj['data']


def list_payload(raw):
    data = payload(raw)
    require(isinstance(data, list), 'API_LIST_REQUIRED')
    return data


def positive_int(value):
    return type(value) is int and value > 0


def entity_count(raw, service=False):
    data = payload(raw)
    value = data.get('entTypes', {}).get(TYPE, {}).get('entCount') if service else data.get('entCount')
    require(positive_int(value), 'API_ENTITY_COUNT_INVALID')
    if not service:
        require(data.get('entType') == TYPE, 'API_ENTITY_TYPE_INVALID')
    return value


def metadata_key(entity):
    require(isinstance(entity, dict) and re.fullmatch(r'GN[0-9]+', str(entity.get('entId', ''))), 'SPECIFICATION_ID_INVALID')
    require(isinstance(entity.get('ldhId'), str) and isinstance(entity.get('rev'), str), 'SPECIFICATION_REVISION_MISSING')
    content = entity.get('entContent')
    require(isinstance(content, dict), 'SPECIFICATION_CONTENT_MISSING')
    return {key: entity.get(key) for key in ('entId', 'ldhId', 'rev', 'modified', 'entContent')}


def selection_reason(entity):
    content = entity['entContent']
    states = content.get('states', [])
    require(isinstance(states, list) and all(isinstance(x, dict) for x in states), 'RELEASE_STATES_INVALID')
    for flag in ('legacyReplaced', 'legacyFullySuperseded'):
        require(flag not in content or type(content[flag]) is bool, 'LEGACY_STATE_INVALID')
    if not any(x.get('name') == 'Released' for x in states):
        return 'NEVER_RELEASED'
    if content.get('legacyReplaced') is True:
        return 'LEGACY_REPLACED'
    if content.get('legacyFullySuperseded') is False:
        return 'LEGACY_NOT_YET_SUPERSEDED'
    return None


def released_current(entity):
    states = entity['entContent'].get('states', [])
    active = [s for s in states if s.get('current') is True]
    return len(active) == 1 and active[0].get('name') == 'Released'


class CaptureClient:
    """Own task ledger; fetch_source retains uncertain reservations on cancellation."""
    def __init__(self, directory, maximum_bytes, maximum_requests, response_bytes):
        self.directory = directory
        directory.mkdir(parents=True, exist_ok=False)
        self.ledger = directory / 'receive-ledger.json'
        save_json(self.ledger, {'maximumBytes': maximum_bytes, 'singleFileMaximumBytes': response_bytes,
                               'receivedBytes': 0, 'reservedBytes': 0, 'entries': []})
        self.maximum_requests, self.response_bytes = maximum_requests, response_bytes
        self.requests = []

    def get(self, suffix, name):
        require(len(self.requests) < self.maximum_requests, 'API_REQUEST_BUDGET_EXCEEDED')
        require(suffix.startswith('/') and not suffix.startswith('//') and '\\' not in suffix, 'API_PATH_INVALID')
        return self.get_url(BASE + suffix, name)

    def get_url(self, url, name, maximum_bytes=None):
        require(url.startswith(BASE + '/') or url == HGNC_URL, 'PUBLIC_SOURCE_URL_UNSUPPORTED')
        require(len(self.requests) < self.maximum_requests, 'API_REQUEST_BUDGET_EXCEEDED')
        path = self.directory / ('raw-%03d-%s.json' % (len(self.requests) + 1, name))
        receipt = {'url': url, 'rawRef': path.name, 'startedAt': now()}
        self.requests.append(receipt)
        with contextlib.redirect_stdout(io.StringIO()):
            capture(SimpleNamespace(ledger=str(self.ledger), destination=str(path), url=url, version='public-current',
                                    purpose='A01 complete public CSpec BA1 catalog capture',
                                    size_basis='Explicit bounded public response, no redirects/retries', max_bytes=maximum_bytes or self.response_bytes))
        raw = path.read_bytes()
        receipt.update(rawSha256=hashlib.sha256(raw).hexdigest(), sizeBytes=len(raw), finishedAt=now())
        return raw, copy.deepcopy(receipt)


def enumerate_metadata(get, expected, page_size, phase):
    records, pages = [], []
    seen = set()
    # The actual service returns HTTP 404 beyond the declared count, not an empty
    # JSON page. Count-bounded pages are verified twice with stable service/type counts.
    for page in range(1, (expected + page_size - 1) // page_size + 1):
        query = urllib.parse.urlencode({'pg': page, 'pgSize': page_size, 'detail': 'high', 'fields': META_FIELDS})
        raw, ref = get('/' + TYPE + '/id?' + query, '%s-page-%d' % (phase, page))
        items = list_payload(raw)
        remaining = max(0, expected - len(records))
        require(len(items) == min(page_size, remaining), 'PAGINATION_COUNT_MISMATCH')
        for record in items:
            key = metadata_key(record)
            require(key['entId'] not in seen, 'PAGINATION_DUPLICATE_SPECIFICATION')
            seen.add(key['entId']); records.append(key)
        pages.append(dict(ref, page=page, pageSize=page_size, returnedCount=len(items)))
    require(len(records) == expected, 'PAGINATION_INCOMPLETE')
    return records, pages


def capture_batch(client, page_size=50, batch_size=5):
    require(1 <= page_size <= 250 and 1 <= batch_size <= 10, 'PAGE_SIZE_INVALID')
    manifest = {'schemaVersion': 'a01-cspec-capture-v1', 'status': 'STARTED', 'startedAt': now(),
                'sourceBase': BASE, 'compilerVersion': VERSION, 'importerSha256': file_sha(Path(__file__)),
                'completenessMethod': 'EXACT_DECLARED_COUNT_STABLE_DOUBLE_ENUMERATION'}
    path = client.directory / 'capture-manifest.json'
    save_json(path, manifest)
    try:
        service, service_ref = client.get('/srvc', 'service-before')
        kind, type_ref = client.get('/' + TYPE, 'type-before')
        total = entity_count(service, True)
        require(entity_count(kind) == total, 'SERVICE_TYPE_COUNT_MISMATCH')
        first, first_pages = enumerate_metadata(client.get, total, page_size, 'before')
        chosen = [r for r in first if selection_reason(r) is None]
        omitted = [{'specificationId': r['entId'], 'reasonCode': selection_reason(r)} for r in first if selection_reason(r)]
        contents = []
        for start in range(0, len(chosen), batch_size):
            subset = chosen[start:start + batch_size]
            query = urllib.parse.urlencode({'ids': ','.join(r['entId'] for r in subset), 'detail': 'high', 'fields': CONTENT_FIELDS})
            raw, ref = client.get('/' + TYPE + '/id?' + query, 'contents-%d' % start)
            entities = list_payload(raw)
            require(len(entities) == len(subset), 'CONTENT_BATCH_COUNT_MISMATCH')
            by_id = {r['entId']: r for r in subset}
            require(len({r.get('entId') for r in entities}) == len(entities), 'CONTENT_BATCH_DUPLICATE')
            for index, entity in enumerate(entities):
                require(entity.get('entId') in by_id and metadata_key(entity) == by_id[entity['entId']], 'CONTENT_RELEASE_REVISION_CHANGED')
                contents.append({'specificationId': entity['entId'], 'rawRef': ref['rawRef'], 'rawSha256': ref['rawSha256'],
                                 'jsonPointer': '/data/' + str(index), 'declaredVersion': entity['entContent'].get('version'),
                                 'revision': entity['rev'], 'currentReleased': released_current(entity)})
            print(json.dumps({'phase': 'contents', 'captured': len(contents), 'selected': len(chosen)}), flush=True)
        final, final_pages = enumerate_metadata(client.get, total, page_size, 'after')
        require(sorted(first, key=lambda x: x['entId']) == sorted(final, key=lambda x: x['entId']), 'ENUMERATION_CHANGED_DURING_CAPTURE')
        final_service, final_service_ref = client.get('/srvc', 'service-after')
        final_type, final_type_ref = client.get('/' + TYPE, 'type-after')
        require(entity_count(final_service, True) == entity_count(final_type) == total, 'FINAL_ENTITY_COUNT_CHANGED')
        manifest.update(status='COMPLETE', apiVersion=payload(service)['version'], declaredEntityCount=total,
                        enumeratedEntityCount=len(first), selectedReleasedCount=len(chosen),
                        enumerationSha256=digest(sorted(first, key=lambda x: x['entId'])), metadataRecords=first,
                        pages=first_pages, verificationPages=final_pages, contents=contents, omitted=omitted,
                        serviceSources=[service_ref, type_ref, final_service_ref, final_type_ref])
    except BaseException as error:
        manifest.update(status='CANCELLED' if isinstance(error, (KeyboardInterrupt, SystemExit)) else 'FAILED', reasonCode=str(error))
        raise
    finally:
        manifest.update(finishedAt=now(), requests=client.requests)
        save_json(path, manifest)
    return manifest


def release_timestamp(entity):
    releases = [s.get('event', {}).get('timeStamp') for s in entity['entContent'].get('states', []) if s.get('name') == 'Released']
    require(len(releases) == 1 and isinstance(releases[0], str) and releases[0], 'RELEASE_TIMESTAMP_UNRESOLVED')
    return releases[0]


def checked_raw(directory, ref):
    name = ref['rawRef']
    path = directory / name
    require(not Path(name).is_absolute() and directory.resolve() in path.resolve().parents
            and file_sha(path) == ref['rawSha256'], 'RAW_HASH_OR_PATH_INVALID')
    return path.read_bytes()


def archive_versions(raw, spec_id):
    data = list_payload(raw)
    require(0 < len(data) <= 100, 'ARCHIVE_VERSION_LIST_INVALID')
    prefix = BASE + '/' + TYPE + '/id/' + spec_id + '/version/'
    versions = []
    for value in data:
        uri = value.get('@id', '')
        require(isinstance(uri, str) and uri.startswith(prefix), 'ARCHIVE_IDENTITY_INVALID')
        version = uri[len(prefix):]
        require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version), 'ARCHIVE_VERSION_INVALID')
        versions.append(version)
    require(len(set(versions)) == len(versions), 'ARCHIVE_VERSION_DUPLICATE')
    return sorted(versions, key=lambda x: tuple(int(t) for t in x.split('.')), reverse=True)


def derive_gene_mappings(raw):
    reader = csv.DictReader(io.StringIO(raw.decode('utf-8-sig')), delimiter='\t')
    needed = {'hgnc_id', 'symbol', 'status', 'ensembl_gene_id', 'entrez_id'}
    require(reader.fieldnames is not None and needed.issubset(reader.fieldnames)
            and len(reader.fieldnames) == len(set(reader.fieldnames)), 'HGNC_HEADER_INVALID')
    output, seen_id, seen_symbol, excluded = [], set(), set(), 0
    for row in reader:
        require(None not in row and all(v is not None for v in row.values()), 'HGNC_ROW_WIDTH_INVALID')
        if row['status'] != 'Approved':
            excluded += 1
            continue
        identifier, symbol = row['hgnc_id'], row['symbol']
        require(re.fullmatch(r'HGNC:[0-9]+', identifier) and symbol and identifier not in seen_id and symbol not in seen_symbol, 'HGNC_APPROVED_IDENTITY_INVALID')
        seen_id.add(identifier); seen_symbol.add(symbol)
        ensembl = [x.strip('"') for x in row['ensembl_gene_id'].split('|') if x]
        entrez = [x.strip('"') for x in row['entrez_id'].split('|') if x]
        require(all(re.fullmatch(r'ENSG[0-9]+', x) for x in ensembl) and all(re.fullmatch(r'[0-9]+', x) for x in entrez), 'HGNC_EXTERNAL_ID_INVALID')
        output.append({'hgncId': identifier, 'geneSymbols': [symbol], 'ensemblGeneIds': ensembl, 'entrezGeneIds': entrez})
    require(bool(output), 'HGNC_APPROVED_RECORDS_MISSING')
    return output, excluded


def complete_released_capture(directory, capture_sha, supplement_name='supplements-v1'):
    """Append independently pinned released archives and approved HGNC, preserving the original capture."""
    path = directory / 'capture-manifest.json'
    require(file_sha(path) == capture_sha, 'CAPTURE_MANIFEST_HASH_MISMATCH')
    original = strict_json(path.read_bytes())
    require(original.get('status') == 'COMPLETE', 'CAPTURE_NOT_COMPLETE')
    require(re.fullmatch(r'[a-z0-9-]+', supplement_name), 'SUPPLEMENT_DIRECTORY_INVALID')
    destination = directory / supplement_name
    prior_bytes = sum(r['sizeBytes'] for r in original['requests'])
    client = CaptureClient(destination, 134217728 - prior_bytes, 128 - len(original['requests']), 25165824)
    result = copy.deepcopy(original)
    result.update(status='STARTED', baseCaptureManifestSha256=capture_sha, completionStartedAt=now(),
                  completionImporterSha256=file_sha(Path(__file__)), releasedArchives=[])
    output = directory / (supplement_name + '-manifest.json')
    require(not output.exists(), 'COMPLETION_MANIFEST_ALREADY_EXISTS')
    def get(suffix, name):
        raw, ref = client.get(suffix, name)
        ref['rawRef'] = supplement_name + '/' + ref['rawRef']
        return raw, ref
    try:
        by_id = {r['entId']: r for r in original['metadataRecords']}
        for content in original['contents']:
            spec_id = content['specificationId']
            if content['currentReleased']:
                continue
            live = by_id[spec_id]
            expected_release = release_timestamp(live)
            raw, versions_ref = get('/' + TYPE + '/id/' + spec_id + '/version', spec_id + '-versions')
            versions = archive_versions(raw, spec_id)
            found = None
            attempted = []
            # Usually the newest archived graph is the published predecessor of the editable graph.
            # Match the actual Released event; never promote a draft based on version-number order.
            for version in versions[:10]:
                raw, ref = get('/' + TYPE + '/id/' + spec_id + '/version/' + version, spec_id + '-archive-' + version)
                entity = payload(raw)
                require(isinstance(entity, dict) and entity.get('entId') == spec_id, 'ARCHIVE_GRAPH_IDENTITY_INVALID')
                attempted.append(ref)
                if released_current(entity) and release_timestamp(entity) == expected_release:
                    found = dict(ref, specificationId=spec_id, archiveVersion=version,
                                 declaredVersion=entity['entContent'].get('version'), releasedAt=expected_release,
                                 versionsSource=versions_ref, attemptedArchives=attempted)
                    break
            require(found is not None, 'RELEASED_ARCHIVE_NOT_RESOLVED:' + spec_id)
            result['releasedArchives'].append(found)
            print(json.dumps({'phase': 'released-archive', 'specificationId': spec_id,
                              'archiveVersion': found['archiveVersion'], 'scientificVersion': found['declaredVersion']}), flush=True)
        gene_raw, gene_ref = client.get_url(HGNC_URL, 'hgnc-approved-complete', maximum_bytes=25165824)
        gene_ref['rawRef'] = supplement_name + '/' + gene_ref['rawRef']
        mappings, excluded = derive_gene_mappings(gene_raw)
        result['geneMappingSource'] = dict(gene_ref, status='COMPLETE', recordCount=len(mappings),
                                          excludedUnapprovedCount=excluded, retrievedAt=gene_ref['finishedAt'])
        # Bind archive selection back to a fresh unchanged directory, not an old cached catalogue.
        refreshed, pages = enumerate_metadata(get, original['declaredEntityCount'], 50, 'completion-verify')
        require(sorted(refreshed, key=lambda x: x['entId']) == sorted(original['metadataRecords'], key=lambda x: x['entId']), 'CATALOG_CHANGED_DURING_ARCHIVE_RESOLUTION')
        result.update(completionVerificationPages=pages, status='COMPLETE')
    except BaseException as error:
        result.update(status='CANCELLED' if isinstance(error, (KeyboardInterrupt, SystemExit)) else 'FAILED', reasonCode=str(error))
        raise
    finally:
        result.update(completionFinishedAt=now(), supplementalRequests=client.requests)
        save_json(output, result)
    return output


def scalar_strings(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, list) and all(isinstance(x, str) for x in value):
        return value
    return []


def normalize_scopes(entity, source=None):
    """Keep each official gene/disease/MOI association; never Cartesian-expand."""
    genes = {}
    for record in entity.get('ld', {}).get('Gene', []):
        hgnc = record.get('entContent', {}).get('HGNC', {})
        symbol, identifier = hgnc.get('symbol'), hgnc.get('hgnc_id')
        if isinstance(symbol, str) and re.fullmatch(r'HGNC:[0-9]+', str(identifier)):
            require(symbol not in genes, 'DUPLICATE_SCOPE_GENE')
            genes[symbol] = {'hgncIds': [identifier], 'geneSymbols': [symbol],
                             'ensemblGeneIds': scalar_strings(hgnc.get('ensembl_gene_id')),
                             'entrezGeneIds': scalar_strings(hgnc.get('entrez_id'))}
    scopes = []
    for rs_index, ruleset in enumerate(entity.get('ld', {}).get('RuleSet', [])):
        for gene_index, association in enumerate(ruleset.get('entContent', {}).get('genes', [])):
            symbol = association.get('gene')
            for disease_index, disease in enumerate(association.get('diseases', [])):
                scope = copy.deepcopy(genes.get(symbol, {'hgncIds': [], 'geneSymbols': [symbol] if isinstance(symbol, str) else [],
                                                         'ensemblGeneIds': [], 'entrezGeneIds': []}))
                mondo = disease.get('preferredMondoId')
                raw_inheritance = disease.get('preferredModeOfInheritance')
                inheritance = MOI.get(raw_inheritance.strip()) if isinstance(raw_inheritance, str) else None
                scope.update(mondoIds=[mondo] if re.fullmatch(r'MONDO:[0-9]{7}', str(mondo)) else [],
                             moi=[inheritance] if inheritance else [], transcripts=[], transcriptScope='UNRESOLVED')
                # Missing is unresolved. Explicit empty transcript array/string in the actual gene association means no restriction.
                transcript = association.get('preferredTranscript', association.get('transcript', association.get('transcripts', None)))
                values = scalar_strings(transcript)
                if transcript in ('', []):
                    scope['transcriptScope'] = 'UNRESTRICTED'
                elif values and all(re.fullmatch(r'(?:NM_|NR_|ENST)[0-9]+\.[0-9]+', x) for x in values):
                    scope.update(transcripts=sorted(set(values)), transcriptScope='EXACT')
                scope['status'] = 'RESOLVED' if scope['hgncIds'] and scope['mondoIds'] and scope['moi'] and scope['transcriptScope'] != 'UNRESOLVED' else 'UNRESOLVED'
                scope['sourcePointer'] = '/ld/RuleSet/%d/entContent/genes/%d/diseases/%d' % (rs_index, gene_index, disease_index)
                scopes.append(scope)
    if not scopes:
        scopes.append({'status': 'UNRESOLVED', 'hgncIds': sorted({h for g in genes.values() for h in g['hgncIds']}),
                       'geneSymbols': sorted(genes), 'ensemblGeneIds': [], 'entrezGeneIds': [], 'mondoIds': [], 'moi': [],
                       'transcripts': [], 'transcriptScope': 'UNRESOLVED', 'sourcePointer': '/ld/RuleSet'})
        rulesets = entity.get('ld', {}).get('RuleSet', [])
        clauses = [(i, c) for i, c in enumerate(entity.get('ld', {}).get('CriteriaCode', []))
                   if c.get('entContent', {}).get('label') == 'BA1']
        # A genome-wide mitochondrial specification has no nuclear HGNC scope.
        # Both independent official scope nodes must say mitochondrial. A title,
        # absent gene list, or a single inconsistent node never establishes this.
        if not genes and rulesets and clauses and all(r.get('entContent', {}).get('geneType') == 'mitochondrial' for r in rulesets) and all(c['entContent'].get('geneType') == 'mitochondrial' for _, c in clauses):
            pointers = ['/ld/RuleSet/%d/entContent/geneType' % i for i in range(len(rulesets))]
            pointers += ['/ld/CriteriaCode/%d/entContent/geneType' % i for i, _ in clauses]
            scopes[0].update(sequenceScope='MITOCHONDRIAL', contigs=['MT'],
                             sequenceScopeSourceRefs=[dict(source or {}, relativeJsonPointer=p) for p in pointers])
    return scopes


def unsupported(reason, source, clauses):
    return {'disposition': 'UNSUPPORTED', 'reasonCode': reason, 'conditions': [], 'sourceRefs': [source], 'clauses': clauses}


def plain(text):
    # Presentation-only markdown emphasis and HTML entities are not additional clauses.
    import html
    return ' '.join(html.unescape(text).replace('**', '').split())


def compile_ba1(entity, source, scope=None, semantic_review=None):
    clauses = [copy.deepcopy(c) for c in entity.get('ld', {}).get('CriteriaCode', []) if c.get('entContent', {}).get('label') == 'BA1']
    if not released_current(entity):
        return unsupported('RELEASED_VERSION_CONTENT_UNRESOLVED', source, clauses)
    if len(clauses) != 1:
        return unsupported('BA1_CLAUSE_MISSING_OR_AMBIGUOUS', source, clauses)
    content = clauses[0]['entContent']
    if scope is not None:
        if not isinstance(content.get('gene'), list) or not content['gene'] or any(g not in content['gene'] for g in scope['geneSymbols']):
            return unsupported('BA1_GENE_SCOPE_UNRESOLVED', source, clauses)
        if content.get('disease') and any(d not in content['disease'] for d in scope['mondoIds']):
            return unsupported('BA1_DISEASE_SCOPE_UNRESOLVED', source, clauses)
    descriptors = content.get('strengthDescriptor')
    if not isinstance(descriptors, list):
        return unsupported('BA1_STRENGTHS_MISSING', source, clauses)
    standalone = [d for d in descriptors if d.get('strength') == 'Stand Alone']
    if len(standalone) != 1:
        return unsupported('BA1_STANDALONE_AMBIGUOUS', source, clauses)
    chosen = standalone[0]
    inactive = {'Not Applicable', 'Not applicable', 'Not Applicable for this VCEP'}
    active = {'Applicable', 'Applicable with VCEP specification'}
    disabled = chosen.get('applicability') in inactive and (content.get('applicability') in inactive or 'applicability' not in content)
    enabled = content.get('applicability') == 'Applicable' and chosen.get('applicability') in active
    enabled = enabled or ('applicability' not in content and chosen.get('applicability') == 'Applicable with VCEP specification')
    if not enabled and not disabled:
        return unsupported('BA1_APPLICABILITY_AMBIGUOUS', source, clauses)
    if any(d.get('applicability') not in inactive and d is not chosen for d in descriptors):
        return unsupported('BA1_MULTIPLE_APPLICABLE_STRENGTHS', source, clauses)
    known = {'_uniqueProp', 'additionalComments', 'applicability', 'baseStrength', 'defaultStrength', 'disease', 'gene', 'geneType',
             'evidenceCategory', 'instructionsToUse', 'label', 'ns', 'originalACMGSummary', 'sepioID', 'specificationType', 'status', 'strengthDescriptor'}
    descriptor_known = {'applicability', 'defaultPoint', 'id', 'instructionsToUse', 'status', 'strength', 'strengthSepioID', 'text', 'type', 'specificationType'}
    if set(content) - known or set(chosen) - descriptor_known:
        return unsupported('BA1_UNKNOWN_OPERATIONAL_FIELD', source, clauses)
    # The chosen Stand Alone descriptor may carry this literal CSpec metadata
    # sentinel. It is not a point value, applicability override or instruction.
    if 'defaultPoint' in chosen and chosen['defaultPoint'] != 'Not Applicable':
        return unsupported('BA1_UNKNOWN_OPERATIONAL_FIELD', source, clauses)
    if any(x not in (None, '') for x in (content.get('additionalComments'), content.get('instructionsToUse'), chosen.get('instructionsToUse'))):
        return unsupported('BA1_ADDITIONAL_INSTRUCTIONS_UNSUPPORTED', source, clauses)
    root_known = {'approvedOn', 'description', 'doi', 'hideFlag', 'legacy', 'legacyFullySuperseded', 'legacyReplaced',
                  'namespace', 'notes', 'references', 'releaseNotes', 'releasedUnderRevision', 'shortTitle',
                  'specificationSource', 'states', 'tagNameSpaces', 'title', 'type', 'version', 'versioned'}
    ruleset_known = {'_uniqueProp', 'geneType', 'generalComments', 'genes', 'ns', 'references', 'rules', 'type'}
    if set(entity['entContent']) - root_known or any(set(r.get('entContent', {})) - ruleset_known for r in entity.get('ld', {}).get('RuleSet', [])):
        return unsupported('BA1_UNKNOWN_GLOBAL_OPERATIONAL_FIELD', source, clauses)
    general_comments = [r.get('entContent', {}).get('generalComments') for r in entity.get('ld', {}).get('RuleSet', [])]
    if entity['entContent'].get('notes') not in (None, '') or any(not isinstance(x, (str, type(None))) or isinstance(x, str) and x.strip() and not re.fullmatch(r'\s*Version [0-9]+(?:\.[0-9]+){1,2}\s*', x) for x in general_comments) or entity.get('ld', {}).get('File'):
        return unsupported('BA1_GLOBAL_NOTES_OR_ATTACHMENTS_UNSUPPORTED', source, clauses)
    if any(not ancillary_version_text(key, entity['entContent'].get(key)) for key in ('description', 'releaseNotes')):
        return unsupported('BA1_GLOBAL_PUBLICATION_TEXT_UNSUPPORTED', source, clauses)
    conditions = related_ba1_conditions(entity)
    if conditions is None:
        return unsupported('BA1_CROSS_CRITERION_CONDITIONS_UNSUPPORTED', source, clauses)
    text = chosen.get('text')
    if disabled:
        if conditions:
            return unsupported('BA1_CROSS_CRITERION_CONDITIONS_UNSUPPORTED', source, clauses)
        if text is None or isinstance(text, str) and plain(text) in ('', 'Not applicable.', 'BA1 is not applicable.', 'Not applicable for this VCEP.'):
            return {'disposition': 'DISABLED', 'reasonCode': 'CSPEC_BA1_EXPLICITLY_NOT_APPLICABLE', 'conditions': [], 'sourceRefs': [source], 'clauses': clauses}
        return unsupported('BA1_INACTIVE_TEXT_CONTRADICTS_DISPOSITION', source, clauses)
    if not isinstance(text, str) or not text.strip():
        return unsupported('BA1_ACTIVE_TEXT_MISSING', source, clauses)
    cleaned = plain(text)
    if cleaned in ('No change from the ACMG/AMP guidelines.', 'No change from the original ACMG/AMP recommendation.'):
        if conditions:
            return unsupported('BA1_CROSS_CRITERION_CONDITIONS_UNSUPPORTED', source, clauses)
        return {'disposition': 'GENERAL', 'reasonCode': 'CSPEC_BA1_EXPLICIT_GENERAL_INHERITANCE', 'conditions': [], 'sourceRefs': [source], 'clauses': clauses}
    compiled = compile_frequency(text, source, semantic_review)
    if compiled:
        compiled['conditions'] = conditions
        compiled['clauses'] = clauses
        return compiled
    return unsupported('BA1_ACTIVE_SEMANTICS_UNSUPPORTED', source, clauses)


def compile_snapshot(directory, capture_sha, manifest_name='capture-manifest.json', semantic_review=None):
    manifest_path = directory / manifest_name
    require(manifest_path.resolve().parent == directory.resolve(), 'MANIFEST_PATH_INVALID')
    require(file_sha(manifest_path) == capture_sha, 'CAPTURE_MANIFEST_HASH_MISMATCH')
    manifest = strict_json(manifest_path.read_bytes())
    require(manifest.get('schemaVersion') == 'a01-cspec-capture-v1' and manifest.get('status') == 'COMPLETE', 'CAPTURE_NOT_COMPLETE')
    first = manifest['metadataRecords']
    require(len(first) == manifest['declaredEntityCount'] == manifest['enumeratedEntityCount'], 'CAPTURE_COUNT_MISMATCH')
    require(len({r['entId'] for r in first}) == len(first), 'CAPTURE_DUPLICATE_SPECIFICATION')
    require(digest(sorted(first, key=lambda x: x['entId'])) == manifest['enumerationSha256'], 'CAPTURE_ENUMERATION_HASH_MISMATCH')
    # Re-read all original pages and actual service counts: manifest claims alone are insufficient.
    for page_set in ('pages', 'verificationPages') + (('completionVerificationPages',) if 'completionVerificationPages' in manifest else ()):
        recovered = []
        require(isinstance(manifest.get(page_set), list) and bool(manifest[page_set]), 'RAW_PAGES_MISSING')
        page_size = manifest[page_set][0]['pageSize']
        require(type(page_size) is int and 1 <= page_size <= 250, 'RAW_PAGE_SIZE_INVALID')
        require(len(manifest[page_set]) == (len(first) + page_size - 1) // page_size, 'RAW_PAGE_SET_INCOMPLETE')
        for number, page in enumerate(manifest[page_set], 1):
            data = list_payload(checked_raw(directory, page))
            require(number == page['page'] and page['pageSize'] == page_size
                    and len(data) == page['returnedCount'] == min(page_size, len(first) - len(recovered)), 'RAW_PAGE_ORDER_OR_COUNT_INVALID')
            recovered.extend(metadata_key(e) for e in data)
        require(sorted(recovered, key=lambda x: x['entId']) == sorted(first, key=lambda x: x['entId']), 'RAW_ENUMERATION_INCOMPLETE')
    for i, ref in enumerate(manifest['serviceSources']):
        path = directory / ref['rawRef']
        require(path.resolve().parent == directory.resolve() and file_sha(path) == ref['rawSha256'], 'RAW_SERVICE_HASH_OR_PATH_INVALID')
        require(entity_count(path.read_bytes(), i in (0, 2)) == len(first), 'RAW_SERVICE_COUNT_MISMATCH')
    selected = {r['entId']: r for r in first if selection_reason(r) is None}
    require(manifest['selectedReleasedCount'] == len(selected), 'RELEASED_SELECTION_COUNT_MISMATCH')
    require(len(manifest['contents']) == len(selected) and {r['specificationId'] for r in manifest['contents']} == set(selected), 'RELEASED_CONTENT_SET_MISMATCH')
    archives = {r['specificationId']: r for r in manifest.get('releasedArchives', [])}
    require(len(archives) == len(manifest.get('releasedArchives', [])), 'DUPLICATE_RELEASED_ARCHIVE')
    unresolved_releases = {r['specificationId'] for r in manifest['contents'] if not r['currentReleased']}
    require(set(archives) == unresolved_releases, 'RELEASED_ARCHIVE_SET_INCOMPLETE')
    entries = []
    for ref in manifest['contents']:
        path = directory / ref['rawRef']
        require(path.resolve().parent == directory.resolve() and file_sha(path) == ref['rawSha256'], 'RAW_CONTENT_HASH_OR_PATH_INVALID')
        require(re.fullmatch(r'/data/[0-9]+', ref['jsonPointer']), 'RAW_CONTENT_POINTER_INVALID')
        entity = list_payload(path.read_bytes())[int(ref['jsonPointer'].split('/')[-1])]
        require(metadata_key(entity) == selected[ref['specificationId']], 'RAW_CONTENT_RELEASE_MISMATCH')
        if ref['specificationId'] in archives:
            archive_ref = archives[ref['specificationId']]
            versions = archive_versions(checked_raw(directory, archive_ref['versionsSource']), ref['specificationId'])
            require(archive_ref['archiveVersion'] in versions, 'ARCHIVE_VERSION_NOT_LISTED')
            archived = payload(checked_raw(directory, archive_ref))
            require(archived.get('entId') == entity['entId'] and released_current(archived)
                    and release_timestamp(archived) == release_timestamp(entity), 'ARCHIVE_RELEASE_BINDING_INVALID')
            entity = archived
            ref = dict(archive_ref, jsonPointer='/data', currentGraphSource=ref)
        version = entity['entContent'].get('version')
        require(isinstance(version, str) and re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}', version), 'DECLARED_VERSION_INVALID')
        source = dict(ref, sourceUrl=BASE + '/' + TYPE + '/id/' + ref['specificationId'])
        capture_urls = [r['url'] for r in manifest['requests'] if r['rawRef'] == ref['rawRef']]
        captured_url = ref.get('url') or (capture_urls[0] if len(capture_urls) == 1 else None)
        require(isinstance(captured_url, str) and captured_url.startswith('https://'), 'SOURCE_CAPTURE_URL_UNRESOLVED')
        clause_source = {'url': captured_url, 'sha256': ref['rawSha256'],
                         'jsonPointer': ref['jsonPointer'], 'specificationId': ref['specificationId'], 'version': version}
        for scope in normalize_scopes(entity, clause_source):
            criterion_diseases = {d for c in entity.get('ld', {}).get('CriteriaCode', []) if c.get('entContent', {}).get('label') == 'BA1'
                                  for d in c['entContent'].get('disease', [])}
            ruleset_diseases = {d['preferredMondoId'] for r in entity.get('ld', {}).get('RuleSet', [])
                               for g in r.get('entContent', {}).get('genes', []) for d in g.get('diseases', []) if d.get('preferredMondoId')}
            if criterion_diseases and not criterion_diseases.issubset(ruleset_diseases):
                scope.update(status='UNRESOLVED', reasonCode='BA1_CRITERION_RULESET_DISEASE_SCOPE_DIFFERS',
                             criterionDiseaseIds=sorted(criterion_diseases))
            ba1 = compile_ba1(entity, clause_source, scope, semantic_review)
            entry = {'entryId': entity['entId'] + '@' + version + ':' + digest(scope),
                     'specificationId': entity['entId'], 'version': version,
                     'publicationStatus': 'Released' if released_current(entity) else 'RELEASED_VERSION_UNRESOLVED',
                     'scope': scope, 'ba1': ba1, 'rawRef': ref['rawRef'], 'rawSha256': ref['rawSha256'],
                     'source': source, 'sourceMetadata': entity['entContent'], 'footnotes': entity['entContent'].get('notes'),
                     'references': entity['entContent'].get('references', []),
                     'ruleSetReferences': [r.get('entContent', {}).get('references', []) for r in entity.get('ld', {}).get('RuleSet', [])]}
            entries.append(entry)
    require(len({e['entryId'] for e in entries}) == len(entries), 'DUPLICATE_FLATTENED_SCOPE')
    snapshot = {'schemaVersion': SCHEMA, 'metadata': {
        'snapshotId': 'a01-cspec-' + manifest['enumerationSha256'], 'generatedAt': now(), 'sourceBase': BASE,
        'apiVersion': manifest['apiVersion'], 'requestedType': TYPE, 'publicationScope': 'Released', 'status': 'COMPLETE',
        'declaredEntityCount': len(first), 'enumeratedEntityCount': len(first), 'selectedReleasedCount': len(selected),
        'entryCount': len(entries), 'pages': manifest['pages'], 'verificationPages': manifest['verificationPages'],
        'enumerationSha256': manifest['enumerationSha256'], 'selectionPolicyVersion': 'cspec-released-legacy-filter-v1',
        'compilerVersion': VERSION, 'captureManifestSha256': capture_sha, 'semanticsComplete': all(e['ba1']['disposition'] != 'UNSUPPORTED' and e['scope']['status'] == 'RESOLVED' for e in entries)},
        'entries': entries}
    snapshot['metadata']['batchReview'] = {'status': 'PENDING', 'reviewId': None, 'compilerVersion': VERSION,
                                           'sourceCaptureSha256': capture_sha}
    snapshot['metadata']['compilerSourceSha256'] = file_sha(Path(__file__))
    snapshot['metadata']['semanticCompilerSourceSha256'] = file_sha(Path(__file__).with_name('cspec_ba1_semantics.py'))
    if semantic_review:
        snapshot['metadata']['semanticReview'] = semantic_review
    if 'geneMappingSource' in manifest:
        source = manifest['geneMappingSource']
        mappings, excluded = derive_gene_mappings(checked_raw(directory, source))
        require(source['recordCount'] == len(mappings) and source['excludedUnapprovedCount'] == excluded, 'GENE_MAPPING_COUNT_MISMATCH')
        snapshot['geneMappings'] = mappings
        snapshot['geneMappingSource'] = {k: source[k] for k in ('url', 'rawSha256', 'retrievedAt', 'status', 'recordCount')}
    snapshot['sha256'] = digest(snapshot)
    return snapshot


def pinned_json(path, expected_sha, limit, reason):
    require(isinstance(expected_sha, str) and re.fullmatch(r'[0-9a-f]{64}', expected_sha), reason)
    require(path.is_file() and path.stat().st_size <= limit, reason)
    with path.open('rb') as stream:
        raw = stream.read(limit + 1)
    require(len(raw) <= limit and hashlib.sha256(raw).hexdigest() == expected_sha, reason)
    return strict_json(raw)


def publish_snapshot(candidate_path, candidate_sha, review_path, review_sha, output):
    """Publish only a separately reviewed, pinned candidate; never enable a service.

    The caller supplies trusted external hashes. A reviewer label or a freshly
    calculated hash alone is not independent review authorization. The publisher
    does not issue a receipt or infer approval from passing author tests.
    """
    require(not output.exists() and not output.is_symlink(), 'PUBLISHED_SNAPSHOT_ALREADY_EXISTS')
    require(output.parent.is_dir(), 'PUBLICATION_DIRECTORY_MISSING')
    candidate = pinned_json(candidate_path, candidate_sha, 32 * 1024 * 1024, 'PUBLISH_CANDIDATE_HASH_OR_SIZE_INVALID')
    review = pinned_json(review_path, review_sha, 1024 * 1024, 'PUBLISH_REVIEW_HASH_OR_SIZE_INVALID')
    require(isinstance(candidate, dict) and candidate.get('schemaVersion') == SCHEMA, 'PUBLISH_CANDIDATE_SCHEMA_INVALID')
    candidate_body = {k: v for k, v in candidate.items() if k != 'sha256'}
    require(candidate.get('sha256') == digest(candidate_body), 'PUBLISH_CANDIDATE_SEAL_INVALID')
    metadata = candidate.get('metadata', {})
    require(isinstance(metadata, dict) and metadata.get('status') == 'COMPLETE'
            and metadata.get('publicationScope') == 'Released'
            and metadata.get('batchReview', {}).get('status') == 'PENDING', 'PUBLISH_CANDIDATE_NOT_PENDING_COMPLETE')
    expected_keys = {'schemaVersion', 'status', 'reviewId', 'reviewScope', 'candidate', 'reviewer', 'reviewedAt', 'sourcesIntegrityReceipt'}
    require(isinstance(review, dict) and expected_keys <= set(review) and set(review) <= expected_keys | {'metadata'}, 'PUBLISH_REVIEW_FIELDS_UNSUPPORTED')
    require(review['schemaVersion'] == 'a01-ba1-cspec-batch-review-v1' and review['status'] == 'REVIEWED'
            and review['reviewScope'] == 'FINITE_BA1_COMPILER_AND_COMPLETE_RELEASED_CATALOG', 'PUBLISH_REVIEW_SCOPE_NOT_APPROVED')
    require(isinstance(review['reviewId'], str) and review['reviewId'].strip(), 'PUBLISH_REVIEW_ID_MISSING')
    reviewer = review['reviewer']
    require(isinstance(reviewer, dict) and set(reviewer) == {'role', 'id'}
            and reviewer['role'] == 'INDEPENDENT_SOURCE_AND_SEMANTICS_REVIEW'
            and isinstance(reviewer['id'], str) and reviewer['id'].strip(), 'PUBLISH_REVIEWER_ROLE_INVALID')
    try:
        reviewed_at = datetime.datetime.fromisoformat(review['reviewedAt'].replace('Z', '+00:00'))
        require(reviewed_at.utcoffset() is not None, 'PUBLISH_REVIEW_TIME_INVALID')
    except (AttributeError, TypeError, ValueError) as error:
        raise Invalid('PUBLISH_REVIEW_TIME_INVALID') from error
    require('metadata' not in review or isinstance(review['metadata'], dict), 'PUBLISH_REVIEW_METADATA_INVALID')
    integrity = review['sourcesIntegrityReceipt']
    require(isinstance(integrity, dict) and set(integrity) == {'sha256'}
            and isinstance(integrity['sha256'], str) and re.fullmatch(r'[0-9a-f]{64}', integrity['sha256']), 'PUBLISH_SOURCE_REVIEW_PIN_MISSING')
    binding = review['candidate']
    expected_binding = {'sha256': candidate_sha, 'compilerVersion': metadata.get('compilerVersion'),
                        'sourceCaptureSha256': metadata.get('captureManifestSha256'),
                        'compilerSourceSha256': metadata.get('compilerSourceSha256'),
                        'semanticCompilerSourceSha256': metadata.get('semanticCompilerSourceSha256')}
    require(isinstance(binding, dict) and binding == expected_binding, 'PUBLISH_REVIEW_CANDIDATE_BINDING_MISMATCH')
    require(isinstance(binding['compilerVersion'], str) and re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', binding['compilerVersion'])
            and all(isinstance(binding[key], str) and re.fullmatch(r'[0-9a-f]{64}', binding[key]) for key in ('sourceCaptureSha256', 'compilerSourceSha256', 'semanticCompilerSourceSha256')),
            'PUBLISH_REVIEW_SOURCE_BINDING_INVALID')
    pending = metadata['batchReview']
    require(pending.get('compilerVersion') == binding['compilerVersion']
            and pending.get('sourceCaptureSha256') == binding['sourceCaptureSha256'], 'PUBLISH_PENDING_BINDING_MISMATCH')
    published = copy.deepcopy(candidate_body)
    published['metadata']['batchReview'] = {
        'status': 'REVIEWED', 'reviewId': review['reviewId'], 'compilerVersion': binding['compilerVersion'],
        'sourceCaptureSha256': binding['sourceCaptureSha256'], 'reviewScope': review['reviewScope'],
        'candidateFileSha256': candidate_sha, 'reviewReceiptSha256': review_sha,
        'reviewer': reviewer, 'reviewedAt': review['reviewedAt'],
        'sourcesIntegrityReceiptSha256': integrity['sha256'], 'publisherVersion': PUBLISHER_VERSION}
    published['sha256'] = digest(published)
    # Write and close a complete sibling before an atomic no-replace link. Unlike
    # os.replace, link cannot overwrite a target created after the initial check.
    # Failures preserve the complete/partial staging evidence and all old files.
    staging = output.with_name('.' + output.name + '.publication-' + uuid.uuid4().hex)
    save_json(staging, published)
    os.link(staging, output)
    staging.unlink()
    return published


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    take = sub.add_parser('capture')
    take.add_argument('--directory', required=True, type=Path)
    take.add_argument('--maximum-bytes', type=int, default=134217728)
    take.add_argument('--maximum-requests', type=int, default=128)
    take.add_argument('--response-bytes', type=int, default=8388608)
    take.add_argument('--page-size', type=int, default=50)
    build = sub.add_parser('compile')
    build.add_argument('--directory', required=True, type=Path)
    build.add_argument('--capture-sha256', required=True)
    build.add_argument('--manifest', default='capture-manifest.json')
    build.add_argument('--semantic-review', type=Path)
    build.add_argument('--semantic-review-sha256')
    build.add_argument('--output', required=True, type=Path)
    complete = sub.add_parser('complete-releases')
    complete.add_argument('--directory', required=True, type=Path)
    complete.add_argument('--capture-sha256', required=True)
    complete.add_argument('--supplement-name', default='supplements-v1')
    publish = sub.add_parser('publish')
    publish.add_argument('--candidate', required=True, type=Path)
    publish.add_argument('--candidate-sha256', required=True)
    publish.add_argument('--review', required=True, type=Path)
    publish.add_argument('--review-sha256', required=True)
    publish.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.command == 'capture':
        require(0 < args.maximum_bytes <= 134217728 and 0 < args.response_bytes <= 8388608 and 0 < args.maximum_requests <= 128, 'CAPTURE_BUDGET_INVALID')
        client = CaptureClient(args.directory, args.maximum_bytes, args.maximum_requests, args.response_bytes)
        capture_batch(client, args.page_size)
    elif args.command == 'complete-releases':
        result = complete_released_capture(args.directory, args.capture_sha256, args.supplement_name)
        print(json.dumps({'manifest': str(result), 'sha256': file_sha(result)}))
    elif args.command == 'publish':
        result = publish_snapshot(args.candidate, args.candidate_sha256, args.review, args.review_sha256, args.output)
        print(json.dumps({'snapshot': str(args.output), 'sha256': file_sha(args.output), 'batchReview': result['metadata']['batchReview']}))
    else:
        require(bool(args.semantic_review) == bool(args.semantic_review_sha256), 'SEMANTIC_REVIEW_IDENTITY_INCOMPLETE')
        review = load_review(args.semantic_review, args.semantic_review_sha256) if args.semantic_review else None
        snapshot = compile_snapshot(args.directory, args.capture_sha256, args.manifest, review)
        require(args.output.resolve().parent == args.directory.resolve(), 'PUBLISH_PATH_OUTSIDE_CAPTURE')
        require(not args.output.exists(), 'PUBLISHED_SNAPSHOT_ALREADY_EXISTS')
        save_json(args.output, snapshot)
        print(json.dumps({'snapshot': str(args.output), 'sha256': file_sha(args.output), 'metadata': snapshot['metadata']}))


if __name__ == '__main__':
    main()
