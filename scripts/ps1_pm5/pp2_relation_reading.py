"""After-claim PP2 public-source acquisition and raw extraction only.

This adapter reuses the existing library, safe transports, model budget, cache
and quote replay. Reading a source or an empty extraction does not qualify a
variant/disease relationship, issue a no-support finding or adopt PP2.
"""
import base64
from copy import deepcopy
from pathlib import Path
import re
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from acquire_public_literature import acquire, local_documents, MAX_PAPERS
from bundle_model_contract import load, pinned, requests, implementation
from discover_public_literature import paper, merge_candidate
from literature_search_transport import SearchTransport, decode, endpoint
from model_literature_contract import canonical, digest, parse
from model_transport import config as model_config, public_config, https_once
from paper_download_transport import DownloadTransport
from paper_library_registration import inside, immutable
from run_bundle_model import run as model_run, replay as model_replay, identity as model_identity
from bundle_model_transport import cache_identity
from bundle_replay_compatibility import reviewed_profiles

VERSION = 'pp2-relation-reading-v1'
MAX_FILE = 50 * 1024 * 1024
MAX_LOCAL_RETAINED = 128 * 1024 * 1024
MAX_ARCHIVE_FILE = 4 * 1024 * 1024
MAX_ARCHIVE_RETAINED = 32 * 1024 * 1024
MAX_SCOPE = 200
MODEL_PROFILE = 'core-material-v2'
RELATIONSHIP_PROFILE = 'pp2-relationship-material-v1'
METHOD_VERSION = '1.2.0'


def require(value, code):
    if not value:
        raise ValueError('PP2_READING_' + code)


def safe_error(error):
    value = str(error)
    return value if re.fullmatch(r'(?:PP2_READING|PP2_RELATIONSHIP_MATERIAL|PAPER_[A-Z]+|LITERATURE_SEARCH|DENOVO_[A-Z]+)_[A-Z0-9_]{1,150}', value) else 'PP2_READING_SOURCE_OR_EXECUTION_FAILED'


def read_bytes(path, maximum=MAX_FILE, *, empty=False):
    with Path(path).open('rb') as stream:
        raw = stream.read(maximum + 1)
    require(len(raw) <= maximum and (empty or bool(raw)), 'FILE_SIZE_LIMIT')
    return raw


def configured(root, config, name):
    value = config.get(name)
    require(isinstance(value, str) and bool(value), 'CONFIG_PATH_REQUIRED')
    path = Path(value)
    return (path if path.is_absolute() else root / path).resolve()


class Originals:
    """Actual input bytes stored once in this controlled work directory."""
    def __init__(self, work):
        self.root = Path(work).resolve(strict=True)
        self.folder = self.root / 'originals'
        self.folder.mkdir()
        self.entries, self.unretained, self.total, self.local_total = {}, {}, 0, 0

    def add(self, raw, *, source_kind, expected=None, source_id=None):
        require(isinstance(raw, bytes) and len(raw) <= MAX_FILE, 'ORIGINAL_SIZE_LIMIT')
        pin = digest(raw)
        require(expected is None or expected == pin, 'ORIGINAL_PIN_MISMATCH')
        if pin not in self.entries and pin not in self.unretained:
            require(self.local_total + len(raw) <= MAX_LOCAL_RETAINED, 'LOCAL_ORIGINAL_TECHNICAL_LIMIT')
            self.local_total += len(raw)
            path = self.folder / (pin + '.body')
            with path.open('xb') as stream:
                stream.write(raw)
            value = dict(rawSha256=pin, bytes=len(raw),
                relativePath=path.relative_to(self.root).as_posix(), provenance=[])
            if len(raw) > MAX_ARCHIVE_FILE or self.total + len(raw) > MAX_ARCHIVE_RETAINED:
                value.update(archiveRetained=False, reasonCode='PP2_READING_ARCHIVE_TECHNICAL_LIMIT')
                self.unretained[pin] = value
            else:
                self.total += len(raw)
                self.entries[pin] = value
        value = self.entries.get(pin, self.unretained.get(pin))
        provenance = dict(sourceKind=source_kind)
        if source_id:
            provenance['sourceId'] = source_id
        if provenance not in value['provenance']:
            value['provenance'].append(provenance)
        return pin

    def missing_archive(self, pins):
        return [pin for pin in dict.fromkeys(pins) if pin not in self.entries]


def bibliographic(candidate):
    require(isinstance(candidate, dict), 'CANDIDATE_INVALID')
    # No patient context, variant payload or arbitrary request URLs are passed
    # to acquisition/model requests. Sources revalidate all metadata origins.
    value = {k: deepcopy(candidate.get(k)) for k in ('source', 'sourceId', 'pmid', 'pmcid', 'doi',
        'title', 'abstract', 'publicationTypes', 'publicationYear', 'openAccess', 'hasSupplement',
        'flags', 'matches', 'origins')}
    require(value['source'] in ('MED', 'PMC', 'PPR') and isinstance(value['sourceId'], str)
            and re.fullmatch(r'[A-Za-z0-9._-]{1,80}', value['sourceId']), 'CANDIDATE_IDENTITY_INVALID')
    for key, pattern in (('pmid', r'[1-9][0-9]*'), ('pmcid', r'PMC[1-9][0-9]*')):
        require(value[key] is None or isinstance(value[key], str) and re.fullmatch(pattern, value[key]), 'CANDIDATE_IDENTITY_INVALID')
    require(isinstance(value['flags'], list) and isinstance(value['origins'], list)
            and isinstance(value['matches'], list), 'CANDIDATE_SOURCE_INVALID')
    value['qualification'] = 'DISCOVERY_CANDIDATE_ONLY'
    if candidate.get('localReviewedBundleSha256') is not None:
        pin = candidate['localReviewedBundleSha256']
        require(isinstance(pin, str) and re.fullmatch('[a-f0-9]{64}', pin), 'LOCAL_BUNDLE_PIN_INVALID')
        value['localReviewedBundleSha256'] = pin
    return value


def retain_events(transport, cache, archive, originals, target):
    """Retain real successful and malformed-but-read responses, never missing bytes."""
    for event in transport.events:
        value = {k: deepcopy(event[k]) for k in ('source', 'url', 'query', 'cursor', 'pmcid', 'kind',
            'status', 'reasonCode', 'sha256', 'bytes', 'retrievedAt') if k in event}
        pin = event.get('sha256')
        if pin:
            raw = read_bytes(Path(cache) / (pin + '.body'))
            value['rawSha256'] = originals.add(raw, expected=pin,
                source_kind='ACTUAL_PUBLIC_TRANSPORT_RESPONSE', source_id=event['url'])
        elif event.get('status') == 'UNAVAILABLE':
            # The existing cache owner can read bytes and reject their SHA or
            # decoding before assigning an event pin. Retain those failed-read
            # bytes without pretending they matched the expected original.
            index = Path(cache) / (digest(event['url'].encode()) + '.json')
            if index.is_file():
                cached = parse(read_bytes(index, 16384))
                expected = cached.get('sha256')
                if isinstance(expected, str) and re.fullmatch('[a-f0-9]{64}', expected):
                    body = Path(cache) / (expected + '.body')
                    if body.is_file():
                        raw = read_bytes(body, 2 * 1024 * 1024, empty=True)
                        value['expectedRawSha256'] = expected
                        value['rawSha256'] = originals.add(raw, source_kind='ACTUAL_FAILED_CACHED_PUBLIC_RESPONSE',
                                                         source_id=event['url'])
        target.append(value)
    # Existing workers keep an actual response body even when decoding failed
    # before an event acquired a SHA. Record only this controlled call folder.
    for path in Path(archive).glob('request-*/response.body'):
        raw = read_bytes(path, empty=True)
        originals.add(raw, source_kind='ACTUAL_PUBLIC_RESPONSE_BODY_WITH_RETAINED_FAILURE')


def resolve_registered_citations(library, wanted):
    """Use local metadata only as an index; confirm identity from the pinned original."""
    matches = {}
    root = Path(library).resolve(strict=True)
    for pmcid, criterion in local_documents(root).items():
        try:
            metadata = parse(read_bytes(inside(root, 'papers/' + pmcid + '/metadata.json'), 65536))
            pmid = metadata.get('identifiers', {}).get('pmid')
            if pmid not in wanted:
                continue
            index = parse(read_bytes(inside(root, 'evidence/' + criterion + '/index.json'), 1048576))
            records = [entry for entry in index['documents'] if entry.get('pmcid') == pmcid]
            require(len(records) == 1 and records[0].get('sourceClass') == 'PUBLIC_PAPER',
                    'LOCAL_REGISTERED_CITATION_AMBIGUOUS')
            bundles = records[0].get('acquisitionBundles', [])
            if len(bundles) != 1:
                continue
            pin = bundles[0]['sha256']
            source = load(root, pmcid, pin, criterion=criterion)
            main = source['main']
            identifiers = main['identifiers']
            if identifiers.get('pmid') != pmid or main['pmcid'] != pmcid:
                continue
            paper_root = inside(root, 'papers/' + pmcid)
            bundle = parse(pinned(paper_root, 'source-bundles/' + pin + '.json', pin, 2097152))
            flags = bundle.get('discoveryFlags', [])
            if flags:
                continue
            candidate = paper(dict(source='MED', id=pmid, pmcid=pmcid, title=main['title'],
                                   doi=identifiers.get('doi')))
            matches.setdefault(pmid, []).append((candidate, pin, main['sourceSha256']))
        except (ValueError, KeyError, TypeError, OSError):
            continue
    return {pmid: rows[0] for pmid, rows in matches.items() if len(rows) == 1}


def resolve_known(known, candidates, library, search_cache, work, online, originals, factory, ledger):
    for reference in known:
        require(isinstance(reference, dict) and isinstance(reference.get('pmid'), str)
                and re.fullmatch(r'[1-9][0-9]*', reference['pmid'])
                and isinstance(reference.get('sourceRawSha256'), str)
                and re.fullmatch('[a-f0-9]{64}', reference['sourceRawSha256']), 'KNOWN_CITATION_INVALID')
    pmids = sorted({r['pmid'] for r in known}, key=int)
    missing = [pmid for pmid in pmids if not any(c['pmid'] == pmid and c['pmcid'] for c in candidates)]
    if not missing:
        return
    try:
        registered = resolve_registered_citations(library, set(missing))
    except (ValueError, KeyError, TypeError, OSError):
        registered = {}
    for pmid, (candidate, pin, source_pin) in registered.items():
        if any(c['pmid'] == pmid and c.get('flags') for c in candidates):
            continue
        pmcid = candidate['pmcid']
        require(merge_candidate(candidates, candidate), 'DECLARED_SCOPE_TECHNICAL_LIMIT')
        matching = [c for c in candidates if c['pmid'] == pmid and c['pmcid'] == pmcid and not c['flags']]
        if len(matching) != 1:
            continue
        matching[0]['localReviewedBundleSha256'] = pin
        ledger['knownCitationMetadata'].append(dict(pmid=pmid, pmcid=pmcid, status='READ',
            sourceKind='VERIFIED_REGISTERED_ARTICLE', sourceRawSha256=source_pin, bundleSha256=pin))
        missing.remove(pmid)
    if not missing:
        return
    archive = work / 'known-metadata-requests'
    with factory(search_cache, archive, online) as transport:
        for pmid in missing:
            query = 'EXT_ID:' + pmid + ' AND SRC:MED'
            item = dict(pmid=pmid, status='UNAVAILABLE')
            ledger['knownCitationMetadata'].append(item)
            try:
                raw = transport.get(query, '*')
                doc = decode(raw)
                require(doc['hitCount'] == 1 and len(doc['resultList']['result']) == 1,
                        'KNOWN_CITATION_METADATA_NOT_UNIQUE')
                row = doc['resultList']['result'][0]
                candidate = paper(row)
                require(candidate['pmid'] == pmid and candidate['source'] == 'MED', 'KNOWN_CITATION_METADATA_ID_MISMATCH')
                pin = originals.add(raw, source_kind='KNOWN_CITATION_METADATA_RESPONSE',
                    source_id=endpoint('europepmc', query, '*')[0])
                candidate['origins'] = [dict(kind='SEARCH_RESPONSE', sha256=pin,
                    url=endpoint('europepmc', query, '*')[0])]
                merge_candidate(candidates, candidate)
                item.update(status='READ', responseRawSha256=pin, pmcid=candidate['pmcid'])
                if not candidate['pmcid']:
                    item.update(status='UNAVAILABLE', reasonCode='PP2_READING_KNOWN_CITATION_FULL_TEXT_ID_UNAVAILABLE')
                    ledger['pending'].append(dict(pmid=pmid, reasonCode=item['reasonCode']))
            except (ValueError, KeyError, TypeError, OSError) as error:
                item['reasonCode'] = safe_error(error)
                ledger['pending'].append(dict(pmid=pmid, reasonCode=item['reasonCode']))
        ledger['networkRequests'] += transport.requests
        retain_events(transport, search_cache, archive, originals, ledger['transportReads'])


def retain_bundle(library, pmcid, pin, originals):
    root = inside(Path(library).resolve(strict=True), 'papers/' + pmcid)
    raw = pinned(root, 'source-bundles/' + pin + '.json', pin, 2 * 1024 * 1024)
    bundle = parse(raw)
    originals.add(raw, expected=pin, source_kind='REGISTERED_PUBLIC_SOURCE_BUNDLE', source_id=pmcid)
    references = [(bundle['main']['path'], bundle['main']['sha256'], False)]
    for key in ('package', 'unparsedPackage'):
        if bundle.get(key):
            references.append((bundle[key]['path'], bundle[key]['sha256'], False))
    manuscript = bundle.get('authorManuscript')
    if manuscript:
        references.append((manuscript['rawPath'], manuscript['rawSha256'], False))
    for item in bundle['attachments']:
        references.append((item['path'], item['sha256'], item.get('bytes') == 0 and item.get('status') == 'ARCHIVED_NOT_PARSED'))
        if item.get('officialSingleFile'):
            descriptor = item['officialSingleFile']
            references.append((descriptor['path'], descriptor['sha256'], False))
    pins = []
    for relative, expected, empty in references:
        raw = pinned(root, relative, expected, MAX_FILE, registered_empty=empty)
        pins.append(originals.add(raw, expected=expected, source_kind='ACTUAL_REGISTERED_PUBLIC_SOURCE', source_id=pmcid))
    return list(dict.fromkeys(pins))


def retain_reviewed_material(library, paper_result, checked):
    root = Path(library).resolve(strict=True)
    pmcid = paper_result['pmcid']
    record = dict(schemaVersion='public-literature-reviewed-material-v1', pmcid=pmcid, criterion='PP2',
        sourceSha256=paper_result['sourceRawSha256'], bundleSha256=paper_result['bundleSha256'],
        artifactSha256=checked['artifactRawSha256'], extractionProfile=checked['originalExtractionProfile'],
        source='https://pmc.ncbi.nlm.nih.gov/articles/' + pmcid + '/', scoringAuthority=False,
        material=checked['review'])
    raw = canonical(record)
    require(len(raw) <= 8 * 1024 * 1024, 'REVIEW_ARCHIVE_SIZE_LIMIT')
    immutable(inside(root, 'reviewed-evidence/' + pmcid + '/reviews/PP2/' + digest(raw) + '.json'), raw)


def retain_model(library, path, payload, originals, *, criterion='PP2', extraction_profile=MODEL_PROFILE):
    artifact_raw = read_bytes(path, 8 * 1024 * 1024)
    artifact = parse(artifact_raw)
    checked = model_replay(library, path, criterion=criterion)
    require(artifact['binding'].get('extractionProfile', 'legacy-v1') == extraction_profile,
            'MODEL_EXTRACTION_PROFILE_MISMATCH')
    receipt = artifact['receipt']
    body = canonical(payload)
    require(digest(body) == artifact['requestSha256'] == receipt['identity']['requestSha256'], 'MODEL_REQUEST_PIN_MISMATCH')
    response = base64.b64decode(artifact['responseBase64'], validate=True)
    require(digest(response) == receipt['responseSha256'], 'MODEL_RESPONSE_PIN_MISMATCH')
    pmcid = artifact['binding']['pmcid']
    return dict(artifactRawSha256=originals.add(artifact_raw, source_kind='ACTUAL_MODEL_ARTIFACT', source_id=pmcid),
        requestRawSha256=originals.add(body, expected=artifact['requestSha256'], source_kind='EXACT_MODEL_REQUEST_RECONSTRUCTION', source_id=pmcid),
        responseRawSha256=originals.add(response, expected=receipt['responseSha256'], source_kind='ACTUAL_RAW_MODEL_RESPONSE', source_id=pmcid),
        receiptSha256=originals.add(canonical(receipt), source_kind='ORIGINAL_MODEL_RECEIPT_CANONICAL_JSON', source_id=pmcid),
        originalExtractionCriterion=criterion, originalExtractionProfile=extraction_profile,
        purposeBorrowedLiteralFacts=criterion != 'PP2',
        otherPhenotypeQuestionCoverage=('RAW_QUESTION_REQUEST_REPLAYED_NOT_QUALIFIED'
            if extraction_profile == RELATIONSHIP_PROFILE else 'NOT_ESTABLISHED'),
        review=checked)


def retain_failed_model(budget, c, payload, pmcid, pin, index, count, originals, *, cache_read=False,
                        criterion='PP2', extraction_profile=MODEL_PROFILE):
    """A failed reservation remains charged/unknown under the existing owner."""
    binding = model_identity(pmcid, pin, index, count, extraction_profile, criterion=criterion)
    unused, key = cache_identity(c, payload, binding)
    if cache_read:
        # The real runner checks current, then explicit known historical
        # profiles in this order. Only the first receipt-bearing candidate can
        # have caused a failed cache read; do not scan unrelated cache entries.
        for candidate in [binding] + [{**binding, 'implementation': p} for p in reviewed_profiles()]:
            if extraction_profile == RELATIONSHIP_PROFILE and 'pp2_relationship_material.py' not in candidate['implementation']:
                continue
            unused, candidate_key = cache_identity(c, payload, candidate)
            if (Path(budget) / 'responses' / candidate_key / 'receipt.json').is_file():
                key = candidate_key
                break
    folder = Path(budget) / 'responses' / key
    value = dict(cacheKey=key, originalExtractionCriterion=criterion, originalExtractionProfile=extraction_profile,
                 accountingState='FAILED_CACHE_READ_NO_NEW_CALL' if cache_read
                 else 'FAILED_OR_UNKNOWN_RESERVATION_RETAINED', originals={})
    for name in ('request.json', 'response.json', 'receipt.json'):
        path = folder / name
        if path.is_file():
            raw = read_bytes(path, max(c['maxRequestBytes'], c['maxResponseBytes']))
            if name == 'request.json' and not cache_read:
                require(raw == canonical(payload), 'FAILED_MODEL_REQUEST_CHANGED')
            value['originals'][name] = originals.add(raw, source_kind='ACTUAL_FAILED_MODEL_' + name.split('.')[0].upper())
    state_path = Path(budget) / 'budget.json'
    if state_path.is_file():
        state = parse(read_bytes(state_path, 262144))
        matches = [r for r in state.get('calls', []) if r.get('key') == key]
        require(len(matches) <= 1, 'FAILED_MODEL_LEDGER_AMBIGUOUS')
        if matches:
            value['reservation'] = {k: deepcopy(matches[0][k]) for k in ('key', 'status', 'reasonCode',
                'chargedOrReservedMicroCredits', 'responseSha256', 'reportedUsage', 'usageRateEstimatedMicroCredits') if k in matches[0]}
    return value


def ps2_literal_plans(library, pmcid, pin, c):
    """Optional actual registered PS2 view. No purpose registration is invented."""
    try:
        source = load(library, pmcid, pin, criterion='PS2')
        return requests(source, c['model'], c['maxOutputTokens'], c['maxRequestBytes'], MODEL_PROFILE)
    except (ValueError, KeyError, TypeError, OSError):
        # Different/unsupported source views are not eligible for cross-purpose
        # reuse. The independently registered PP2 view still follows its own path.
        return []


def read_scope(workflow_config_path, expected_config_raw_sha, candidates, known_citation_references, work, *,
               search_transport_factory=SearchTransport, download_transport_factory=DownloadTransport,
               model_transport=https_once, remaining_seconds=240):
    """Explicit AFTER_CLAIM call. No caller-provided completion/review is accepted.

    Source/model transport injection is for engineering tests; production uses
    the existing owners and unchanged operational configuration. Output contains
    only raw facts/read coverage, never a scientific review token.
    """
    work = Path(work)
    work.mkdir(parents=True, exist_ok=False)
    work = work.resolve(strict=True)
    originals = Originals(work)
    result = dict(schemaVersion=VERSION, methodVersion=METHOD_VERSION, stage='AFTER_CLAIM',
        status='UNAVAILABLE', readingComplete=False, relationQualification='NOT_GRANTED',
        readingScopeMeaning='DECLARED_AVAILABLE_MAIN_AND_PARSED_ATTACHMENT_TEXT_UNITS_ONLY', wholePublicationRead=False,
        scientificAdoption=False, businessPublishable=False, clinicalAdoption='NONE',
        extractionProfile=MODEL_PROFILE, relationshipExtractionProfile=RELATIONSHIP_PROFILE,
        questionVersion=RELATIONSHIP_PROFILE, papers=[], knownCitationMetadata=[], transportReads=[], pending=[],
        modelCalls=0, modelAttempts=0, cacheHits=0, borrowedLiteralFactCacheHits=0, networkRequests=0, originals={},
        archiveBudget=dict(maximumOriginalBytes=MAX_ARCHIVE_FILE, maximumTotalBytes=MAX_ARCHIVE_RETAINED))
    deadline = time.monotonic() + remaining_seconds
    before = {**implementation(), 'pp2_relation_reading.py': digest(Path(__file__).read_bytes())}
    result['implementation'] = before
    result['methodSha256'] = digest(canonical(before))
    integrity_error = False
    try:
        require(type(remaining_seconds) is int and 1 <= remaining_seconds <= 900, 'TIME_BUDGET_INVALID')
        require(isinstance(expected_config_raw_sha, str) and re.fullmatch('[a-f0-9]{64}', expected_config_raw_sha), 'CONFIG_PIN_REQUIRED')
        raw = read_bytes(workflow_config_path, 65536)
        require(digest(raw) == expected_config_raw_sha, 'CONFIG_PIN_MISMATCH')
        config = parse(raw)
        require(config.get('schemaVersion') == 'denovo-public-literature-workflow-v1'
                and config.get('enabled') is True and type(config.get('allowModel')) is bool
                and type(config.get('allowPublicNetwork')) is bool, 'CONFIG_UNAVAILABLE')
        root = Path(config['projectRoot']).resolve(strict=True)
        library, cache, budget, model_path = [configured(root, config, k) for k in ('library', 'searchCache', 'modelBudget', 'modelConfig')]
        require(library.is_dir(), 'LIBRARY_UNAVAILABLE')
        cache.mkdir(parents=True, exist_ok=True)
        allow_model, online = config['allowModel'], config['allowPublicNetwork']
        model_raw = read_bytes(model_path, 16384)
        model_pin = digest(model_raw)
        c = model_config(model_path) if allow_model else parse(model_raw)
        require(digest(read_bytes(model_path, 16384)) == model_pin, 'MODEL_CONFIG_CHANGED_DURING_LOADING')
        max_calls, max_chunks = config.get('maximumReadingCalls', 8), config.get('maximumReadingChunks', 8)
        require(type(max_calls) is int and 1 <= max_calls <= 32 and type(max_chunks) is int and 1 <= max_chunks <= 64,
                'READING_LIMIT_INVALID')
        result['configuration'] = dict(workflowRawSha256=expected_config_raw_sha,
            modelConfigurationRawSha256=model_pin,
            allowModel=allow_model, allowPublicNetwork=online,
            modelPublicConfigurationSha256=digest(canonical(public_config(c))),
            maximumNewModelCalls=max_calls, maximumNewChunks=max_chunks)
        require(isinstance(candidates, list) and len(candidates) <= MAX_SCOPE
                and isinstance(known_citation_references, list) and len(known_citation_references) <= MAX_SCOPE,
                'DECLARED_SCOPE_TECHNICAL_LIMIT')
        declared = []
        for candidate in candidates:
            require(merge_candidate(declared, bibliographic(candidate)), 'DECLARED_SCOPE_TECHNICAL_LIMIT')
        result['knownCitationReferences'] = [{k: deepcopy(r[k]) for k in ('pmid', 'sourceRawSha256', 'citation') if k in r}
                                             for r in known_citation_references]
        result['declaredInputSha256'] = digest(canonical(dict(candidates=declared,
            knownCitationReferences=result['knownCitationReferences'])))
        resolve_known(known_citation_references, declared, library, cache, work, online, originals, search_transport_factory, result)
        require(len(declared) <= MAX_SCOPE, 'DECLARED_SCOPE_TECHNICAL_LIMIT')
        # All candidates are already current declared scope. No relevance model
        # may silently remove a target paper, direct citation or retracted item.
        new_chunks, model_stopped = 0, False
        for batch_index, start in enumerate(range(0, len(declared), MAX_PAPERS)):
            selected = declared[start:start + MAX_PAPERS]
            discovery = work / ('acquisition-scope-' + str(batch_index) + '.json')
            raw = canonical(dict(schemaVersion='denovo-literature-discovery-v1', candidates=selected))
            discovery.write_bytes(raw)
            originals.add(raw, source_kind='DECLARED_ACQUISITION_INPUT_NOT_APPROVAL')
            archive = work / ('acquisition-requests-' + str(batch_index))
            require(time.monotonic() < deadline - 2, 'TIME_BUDGET')
            with download_transport_factory(cache, archive, online) as transport:
                try:
                    acquired = acquire(discovery, digest(raw), library, transport,
                        work / ('acquisition-' + str(batch_index)), maximum=len(selected), criteria=('PP2',))
                finally:
                    result['networkRequests'] += transport.requests
                    retain_events(transport, cache, archive, originals, result['transportReads'])
            for paper_result in acquired['papers']:
                item = {k: deepcopy(paper_result[k]) for k in ('pmid', 'pmcid', 'status', 'reasonCode') if k in paper_result}
                item.update(workPlans=[], checkedChunks=[], relationshipWorkPlans=[], relationshipChunks=[], pending=[], materialIssues=[],
                    questionCoverage=dict(status='INCOMPLETE', questionVersion=RELATIONSHIP_PROFILE,
                        expectedPlans=None, checkedPlans=0, missingMeaning='NOT_EXTRACTED_NOT_ABSENT',
                        relationQualification='NOT_GRANTED', gaps=[]))
                result['papers'].append(item)
                if paper_result['status'] != 'REGISTERED':
                    item['pending'].append(dict(reasonCode=paper_result.get('reasonCode', 'PP2_READING_PUBLIC_SOURCE_NOT_REGISTERED')))
                    continue
                try:
                    pmcid, pin = paper_result['pmcid'], paper_result['registration']['bundleSha256']
                    source = load(library, pmcid, pin, criterion='PP2')
                    item.update(bundleSha256=pin, sourceRawSha256=source['main']['sourceSha256'],
                        title=source['main']['title'], supplementCoverage=deepcopy(source['coverage']),
                        attachments=deepcopy(source['attachments']), originalRawSha256s=retain_bundle(library, pmcid, pin, originals))
                    item['sourceUnitManifestSha256'] = digest(canonical(source['units']))
                    item['unreviewedVisualCaptionUnitIds'] = [u['unitId'] for u in source['units']
                        if '/fig[' in u['unitId'] and u.get('kind') == 'CAPTION']
                    unavailable = originals.missing_archive(item['originalRawSha256s'] + [pin])
                    if unavailable:
                        item['status'] = 'PARTIAL'
                        item['pending'].append(dict(reasonCode='PP2_READING_ARCHIVE_TECHNICAL_LIMIT',
                            unretainedRawSha256s=unavailable, modelSkipped=True))
                        # All complete bytes remain in the source library/work.
                        # Do not spend money on a paper whose required originals
                        # cannot enter the actual derived archive unchanged.
                        continue
                    for profile in (MODEL_PROFILE, RELATIONSHIP_PROFILE):
                        relationship = profile == RELATIONSHIP_PROFILE
                        plans_key = 'relationshipWorkPlans' if relationship else 'workPlans'
                        checked_key = 'relationshipChunks' if relationship else 'checkedChunks'
                        try:
                            payloads = requests(source, c['model'], c['maxOutputTokens'], c['maxRequestBytes'], profile)
                        except (ValueError, KeyError, TypeError, OSError) as error:
                            item['pending'].append(dict(extractionProfile=profile, reasonCode=safe_error(error)))
                            continue
                        if relationship:
                            item['questionCoverage']['expectedPlans'] = len(payloads)
                        else:
                            item['_expectedCorePlans'] = len(payloads)
                        borrowed_plans = [] if relationship else ps2_literal_plans(library, pmcid, pin, c)
                        if not payloads:
                            item['pending'].append(dict(extractionProfile=profile,
                                reasonCode='PP2_READING_NO_READABLE_SOURCE_UNITS'))
                        phase_pending_start = len(item['pending'])
                        for index, payload in enumerate(payloads):
                            content = parse(payload['messages'][1]['content'])
                            plan = content['workUnit']
                            item[plans_key].append(dict(chunkIndex=index, workUnitSha256=digest(canonical(plan)),
                                targetUnitIds=deepcopy(plan['targetUnitIds']), contextUnitIds=deepcopy(plan['contextUnitIds'])))
                            if time.monotonic() >= deadline - 2:
                                item['pending'].append(dict(chunkIndex=index, reasonCode='PP2_READING_TIME_BUDGET'))
                                continue
                            require(digest(read_bytes(workflow_config_path, 65536)) == expected_config_raw_sha,
                                    'CONFIG_CHANGED_DURING_READING')
                            require(digest(read_bytes(model_path, 16384)) == model_pin, 'MODEL_CONFIG_CHANGED_DURING_READING')
                            folder = work / (pmcid + ('-relationship-chunk-' if relationship else '-chunk-') + str(index))
                            criterion = 'PP2'
                            try:
                                value = None
                                if len(borrowed_plans) == len(payloads) and canonical(borrowed_plans[index]) == canonical(payload):
                                    criterion = 'PS2'
                                    value = model_run(library, pmcid, pin, c, model_path, budget, folder, model_transport,
                                        chunk_index=index, cache_only=True, extraction_profile=profile, criterion='PS2')
                                if value is None:
                                    criterion = 'PP2'
                                    value = model_run(library, pmcid, pin, c, model_path, budget, folder, model_transport,
                                        chunk_index=index, cache_only=True, extraction_profile=profile, criterion='PP2')
                            except (ValueError, KeyError, TypeError, OSError, RuntimeError) as error:
                                model_stopped = True
                                failed = retain_failed_model(budget, c, payload, pmcid, pin, index, len(payloads), originals,
                                    cache_read=True, criterion=criterion, extraction_profile=profile)
                                failed.update(reasonCode=safe_error(error), chunkIndex=index)
                                item.setdefault('failedModelWork', []).append(failed)
                                item['pending'].append(dict(chunkIndex=index, reasonCode=failed['reasonCode']))
                                continue
                            if value is None:
                                if not allow_model:
                                    item['pending'].append(dict(chunkIndex=index, reasonCode='PP2_READING_MODEL_DISABLED_CACHE_MISSING'))
                                    continue
                                if new_chunks >= max_chunks or result['modelAttempts'] >= max_calls:
                                    item['pending'].append(dict(chunkIndex=index, reasonCode='PP2_READING_NEW_WORK_LIMIT'))
                                    continue
                                if model_stopped:
                                    item['pending'].append(dict(chunkIndex=index, reasonCode='PP2_READING_MODEL_BUDGET_REVIEW_REQUIRED'))
                                    continue
                                if deadline - time.monotonic() <= c['timeoutSeconds'] + 5:
                                    item['pending'].append(dict(chunkIndex=index, reasonCode='PP2_READING_TIME_BUDGET'))
                                    continue
                                new_chunks += 1
                                result['modelAttempts'] += 1
                                try:
                                    value = model_run(library, pmcid, pin, c, model_path, budget, folder, model_transport,
                                        chunk_index=index, extraction_profile=profile, criterion='PP2')
                                except (ValueError, KeyError, TypeError, OSError) as error:
                                    model_stopped = True
                                    failed = retain_failed_model(budget, c, payload, pmcid, pin, index, len(payloads), originals, extraction_profile=profile)
                                    failed['reasonCode'] = safe_error(error)
                                    failed['chunkIndex'] = index
                                    item.setdefault('failedModelWork', []).append(failed)
                                    item['pending'].append(dict(chunkIndex=index, reasonCode=failed['reasonCode']))
                                    result['modelAccountingState'] = 'FAILED_OR_UNKNOWN_RESERVATION_RETAINED'
                                    continue
                            require(value is not None, 'MODEL_RESULT_UNAVAILABLE')
                            result['modelCalls'] += value['modelCalls']
                            result['cacheHits'] += int(value['cacheHit'])
                            result['borrowedLiteralFactCacheHits'] += int(criterion == 'PS2' and value['cacheHit'])
                            checked = retain_model(library, folder / 'artifact.json', payload, originals, criterion=criterion, extraction_profile=profile)
                            unavailable = originals.missing_archive([checked[k] for k in (
                                'artifactRawSha256', 'requestRawSha256', 'responseRawSha256', 'receiptSha256')])
                            if unavailable:
                                item['pending'].append(dict(chunkIndex=index, reasonCode='PP2_READING_ARCHIVE_TECHNICAL_LIMIT',
                                    unretainedRawSha256s=unavailable, modelAlreadyRead=True))
                                item.setdefault('unretainedModelWork', []).append({k: deepcopy(checked[k]) for k in (
                                    'artifactRawSha256', 'requestRawSha256', 'responseRawSha256', 'receiptSha256',
                                    'originalExtractionCriterion', 'originalExtractionProfile', 'purposeBorrowedLiteralFacts')})
                                model_stopped = True
                                continue
                            checked['chunkIndex'] = index
                            checked['workUnitSha256'] = digest(canonical(plan))
                            try:
                                retain_reviewed_material(library, item, checked)
                            except (ValueError, OSError):
                                item['reviewArchiveUnavailable'] = True
                            item[checked_key].append(checked)
                            review = checked['review']
                            if review.get('extractionCoverageProposal') != 'EXTRACTED_WITHIN_TARGET':
                                item['pending'].append(dict(chunkIndex=index, reasonCode='PP2_READING_EXTRACTION_INCOMPLETE',
                                    actual=review.get('extractionCoverageProposal')))
                            if relationship:
                                require(review.get('questionVersion') == RELATIONSHIP_PROFILE, 'RELATIONSHIP_QUESTION_VERSION_MISMATCH')
                                checked['questionVersion'] = review['questionVersion']
                                if not review['records']:
                                    item['pending'].append(dict(chunkIndex=index, extractionProfile=profile,
                                        reasonCode='PP2_READING_RELATIONSHIP_EMPTY_EXTRACTION_IS_NOT_ABSENCE'))
                                elif 'RECORD_WITHOUT_EXTRACTED_FACTS' in review.get('issues', []):
                                    item['pending'].append(dict(chunkIndex=index, extractionProfile=profile,
                                        reasonCode='PP2_READING_RELATIONSHIP_RECORD_WITHOUT_EXTRACTED_FACTS'))
                            elif not review['cases']:
                                item['materialIssues'].append(dict(chunkIndex=index, reasonCode='PP2_READING_EMPTY_EXTRACTION_IS_NOT_ABSENCE'))
                        for gap in item['pending'][phase_pending_start:]:
                            gap.setdefault('extractionProfile', profile)
                    for attachment in source['attachments']:
                        if (attachment.get('status') == 'ARCHIVED_NOT_PARSED' or attachment.get('unparsedParts')
                                or any(m.get('status') == 'ARCHIVED_NOT_PARSED' for m in attachment.get('members', []))):
                            item['pending'].append(dict(reasonCode='PP2_READING_ATTACHMENT_NOT_FULLY_READ',
                                originalName=attachment['originalName'], sourceRawSha256=attachment['sha256']))
                    if (source['coverage'].get('status') == 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED'
                            or source['coverage'].get('unparsedAttachments', 0) or source['coverage'].get('missingFiles')
                            or source['coverage'].get('unmatchedSourceUnits')):
                        item['pending'].append(dict(reasonCode='PP2_READING_ATTACHMENT_COVERAGE_INCOMPLETE'))
                    item['status'] = 'PARTIAL'  # Both actual profiles are finalised together below.
                except (ValueError, KeyError, TypeError, OSError) as error:
                    item['status'] = 'PARTIAL'
                    item['pending'].append(dict(reasonCode=safe_error(error)))
        for item in result['papers']:
            for gap in item['pending']:
                result['pending'].append(dict(pmcid=item.get('pmcid'), pmid=item.get('pmid'), **gap))
        if originals.unretained:
            result['pending'].append(dict(reasonCode='PP2_READING_ARCHIVE_TECHNICAL_LIMIT',
                unretainedRawSha256s=sorted(originals.unretained)))
        require(digest(read_bytes(workflow_config_path, 65536)) == expected_config_raw_sha, 'CONFIG_CHANGED_DURING_READING')
        require(digest(read_bytes(model_path, 16384)) == model_pin, 'MODEL_CONFIG_CHANGED_DURING_READING')
        after = {**implementation(), 'pp2_relation_reading.py': digest(Path(__file__).read_bytes())}
        require(before == after, 'METHOD_CHANGED_DURING_READING')
        result['readingComplete'] = bool(declared) and not result['pending'] and len(result['papers']) == len(declared)
        result['status'] = 'RAW_SCOPE_READ' if result['readingComplete'] else 'PARTIAL'
    except (ValueError, KeyError, TypeError, OSError) as error:
        integrity_error = True
        result['pending'].append(dict(reasonCode=safe_error(error)))
        result['status'] = 'PARTIAL' if result['papers'] else 'UNAVAILABLE'
    for item in result['papers']:
        coverage = item['questionCoverage']
        expected = coverage['expectedPlans']
        core_expected = item.pop('_expectedCorePlans', None)
        coverage['checkedPlans'] = len(item['relationshipChunks'])
        coverage['gaps'] = deepcopy(item['pending'])
        if integrity_error:
            coverage['gaps'].append(dict(reasonCode='PP2_READING_SCOPE_INTEGRITY_OR_EXECUTION_FAILED'))
        complete = (not integrity_error and not item['pending'] and type(expected) is int and expected > 0
                    and len(item['relationshipChunks']) == expected
                    and type(core_expected) is int and core_expected > 0
                    and len(item['checkedChunks']) == core_expected)
        coverage['status'] = 'RAW_QUESTION_SCOPE_READ' if complete else 'INCOMPLETE'
        if 'bundleSha256' in item:
            item['status'] = 'RAW_WORK_SCOPE_READ' if complete else 'PARTIAL'
    if any(item['questionCoverage']['status'] != 'RAW_QUESTION_SCOPE_READ' for item in result['papers']):
        result['readingComplete'] = False
        if result['status'] == 'RAW_SCOPE_READ':
            result['status'] = 'PARTIAL'
    result['originals'] = deepcopy(originals.entries)
    result['unretainedOriginals'] = deepcopy(originals.unretained)
    result['retainedOriginalBytes'] = originals.total
    result['localOriginalBytes'] = originals.local_total
    result['ledgerSha256'] = digest(canonical(result))
    # Fresh JSON data only; no callable, source owner, opaque scientific handle
    # or mutable externally supplied object escapes this mechanical adapter.
    return parse(canonical(result))
