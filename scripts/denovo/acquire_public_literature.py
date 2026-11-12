"""Acquire ranked discovery candidates into the existing public-paper library.

The source bundle is retrieval material, not a v1/v2 model request or reviewed
case. All bibliographic aliases, unsupported attachments and missing links remain
visible. Official OA/qualified author-manuscript endpoints only; arbitrary links
are never followed.
"""
import argparse
import datetime as dt
import json
from pathlib import Path, PurePosixPath
import re
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile

from discover_public_literature import paper
from literature_attachments import members, parse_attachment
from literature_search_transport import decode, atomic_json
from paper_download_transport import DownloadTransport, url_for
from paper_library_registration import encoded, register
from pmc_open_data import verified_xml_url
from prepare_literature import prepare, sha
from select_public_papers import bounded, select, unique
from author_manuscript_source import derive as derive_manuscript, local_manuscript

VERSION = 'denovo-public-paper-acquisition-v1'
SUPPORTED_CRITERIA = ('PS2', 'PM6', 'PM3', 'PP1', 'PVS1', 'PP2')
MAX_PAPERS = 50


def require(value, code):
    if not value:
        raise ValueError('PAPER_ACQUISITION_' + code)


def identity(candidate, document):
    for field in ('pmid', 'doi'):
        expected, actual = candidate.get(field), document['identifiers'].get(field)
        require(not expected or actual and actual.lower() == expected.lower(), 'BIBLIOGRAPHIC_IDENTITY_MISMATCH')


def verified_metadata(candidate, cache, with_route=False):
    """Eligibility comes from pinned original search bytes, not edited candidates."""
    records = []
    for origin in candidate.get('origins', []):
        if origin.get('kind') != 'SEARCH_RESPONSE':
            continue
        pin = origin.get('sha256', '')
        require(isinstance(pin, str) and re.fullmatch('[a-f0-9]{64}', pin), 'SEARCH_PIN_INVALID')
        raw = bounded(Path(cache) / (pin + '.body'), 2097152)
        require(sha(raw) == pin, 'SEARCH_PIN_MISMATCH')
        for row in decode(raw)['resultList']['result']:
            parsed = paper(row)
            if parsed['pmcid'] != candidate.get('pmcid'):
                continue
            for key in ('pmid', 'doi'):
                require(not parsed[key] or not candidate.get(key) or parsed[key] == candidate[key], 'SEARCH_IDENTITY_CONFLICT')
            parsed['authorManuscript'] = row.get('authMan') == 'Y' and row.get('nihAuthMan') == 'Y'
            parsed['manuscriptId'] = row.get('manuscriptId')
            records.append(parsed)
    require(records, 'SEARCH_ORIGIN_NOT_VERIFIED')
    require(not any('RETRACTION_REQUIRES_REVIEW' in row['flags'] for row in records), 'RETRACTION_REQUIRES_REVIEW')
    wants = any(row['hasSupplement'] == 'Y' for row in records)
    if all(row['openAccess'] == 'Y' for row in records):
        route = {'kind': 'fullTextXML', 'sourceKind': 'OFFICIAL_OPEN_ACCESS_RESPONSE'}
    else:
        # Never turn a bare PMC/free-access flag into permission to fetch text.
        ids = {row['manuscriptId'] for row in records}
        require(with_route and all(row['authorManuscript'] for row in records) and len(ids) == 1 and
                re.fullmatch(r'NIHMS[1-9][0-9]*', next(iter(ids)) or ''), 'OPEN_ACCESS_NOT_CONFIRMED')
        route = {'kind': 'authorManuscriptXML', 'sourceKind': 'OFFICIAL_AUTHOR_MANUSCRIPT_RESPONSE',
                 'manuscriptId': next(iter(ids))}
    return (wants, route) if with_route else wants


def local_documents(library):
    # Request known papers individually at acquisition time, avoiding the old
    # extraction worker's 20-document selection limit for a growing library.
    result = {}
    for criterion in SUPPORTED_CRITERIA:
        path = Path(library) / 'evidence' / criterion / 'index.json'
        if not path.exists():
            continue
        index = json.loads(bounded(path, 1048576), object_pairs_hook=unique)
        require(index.get('schemaVersion') == 'public-paper-evidence-index-v1' and
                index.get('criterion') == criterion, 'LOCAL_INDEX_INVALID')
        for entry in index['documents']:
            pmcid = entry.get('pmcid')
            require(isinstance(pmcid, str) and re.fullmatch(r'PMC[1-9][0-9]*', pmcid), 'LOCAL_INDEX_INVALID')
            result.setdefault(pmcid, criterion)
    return result


def captured_single_bundle(library, pmcid, criterion='PS2'):
    """Reuse latest explicitly registered import; revalidate before any network.

    Old continuation tasks keep their pinned bundle. New retrieval tasks may use
    additional registered sources, never their unverified derived JSON alone.
    """
    from bundle_model_contract import load, pinned
    from official_supplement_source import VERSION as SINGLE_FILE_VERSION
    from paper_library_registration import inside
    root = inside(Path(library).resolve(strict=True), 'papers/' + pmcid)
    require(criterion in SUPPORTED_CRITERIA, 'CRITERIA_INVALID')
    index_path = Path(library) / 'evidence' / criterion / 'index.json'
    if not index_path.exists(): return None
    index = json.loads(bounded(index_path, 1048576), object_pairs_hook=unique)
    entries = [e for e in index['documents'] if e.get('pmcid') == pmcid]
    if not entries: return None
    require(len(entries) == 1, 'INDEX_DUPLICATE')
    entry = entries[0]
    refs = entry.get('acquisitionBundles', [])
    require(len(refs) <= 100, 'BUNDLE_HISTORY_LIMIT')
    for ref in reversed(refs):
        pin = ref['sha256']
        require(re.fullmatch('[a-f0-9]{64}', pin or ''), 'BUNDLE_PIN_INVALID')
        raw = pinned(root, 'source-bundles/' + pin + '.json', pin, 2097152)
        bundle = json.loads(raw, object_pairs_hook=unique)
        if bundle.get('singleFileImport', {}).get('version') == SINGLE_FILE_VERSION:
            load(library, pmcid, pin, criterion=criterion)
            return pin, bundle, raw
    return None


def register_existing_bundle(library, pmcid, pin, source_criterion, criteria, *, candidate):
    """Add a retrieval purpose to the SAME already validated source bundle.

    This is index membership only. Originals, bundle bytes and their scientific
    qualification remain unchanged; every newly requested view is then loaded
    through its ordinary parser/security checks.
    """
    from bundle_model_contract import load, pinned
    from paper_library_registration import inside
    source = load(library, pmcid, pin, criterion=source_criterion)
    # Verify the requested bibliography before writing ANY new purpose index.
    require(candidate.get('pmcid') == pmcid, 'BIBLIOGRAPHIC_IDENTITY_MISMATCH')
    identity(candidate, source['main'])
    root = inside(Path(library).resolve(strict=True), 'papers/' + pmcid)
    raw = pinned(root, 'source-bundles/' + pin + '.json', pin, 2097152)
    bundle = json.loads(raw, object_pairs_hook=unique)
    index_path = Path(library) / 'evidence' / source_criterion / 'index.json'
    index = json.loads(bounded(index_path, 1048576), object_pairs_hook=unique)
    entries = [e for e in index['documents'] if e.get('pmcid') == pmcid]
    require(len(entries) == 1, 'INDEX_DUPLICATE')
    metadata = {'sha256': sha(source['raw']), 'acquisitionUrl': entries[0]['acquisitionUrl']}
    registered = register(library, pmcid, {pmcid + '.xml': source['raw']}, bundle, metadata, criteria=criteria)
    require(registered['status'] == 'REGISTERED' and registered['bundleSha256'] == pin, 'REUSED_BUNDLE_IDENTITY_CHANGED')
    for criterion in criteria:
        load(library, pmcid, pin, criterion=criterion)
    return registered


def source_bundle(candidate, raw, document, transport, wants_supplement):
    pmcid = candidate['pmcid']
    files = {pmcid + '.xml': raw}
    prepared = encoded(document)
    require(len(prepared) <= 20 * 1024 * 1024, 'DERIVED_SIZE_LIMIT')
    prepared_name = 'derived/' + sha(prepared) + '.json'
    files[prepared_name] = prepared
    root = ET.fromstring(raw.decode('utf-8-sig'))  # prepare() already checked safe JATS.
    permissions = root.find('./front/article-meta/permissions')
    links = [link for link in document['links'] if link['kind'] in {'supplementary-material', 'media'}]
    # Some JATS represents supplements as ext-link rather than supplementary-material.
    links += [link for link in document['links'] if link['kind'] == 'ext-link' and
              '/ref-list[' not in link['sourceUnitId'] and '/ref[' not in link['sourceUnitId'] and
              re.search(r'\.(?:xlsx?|docx|csv|tsv|pdf|zip)(?:$|\?)', link.get('href') or '', re.I)]
    bundle = {'schemaVersion': 'public-paper-source-bundle-v1', 'pmcid': pmcid,
              'identifiers': document['identifiers'], 'title': document['title'],
              'main': {'path': pmcid + '.xml', 'sha256': sha(raw), 'preparedPath': prepared_name,
                       'preparedSha256': sha(prepared), 'units': len(document['units']), 'tables': len(document['tables'])},
              'permissionsXml': ET.tostring(permissions, encoding='unicode') if permissions is not None else None,
              'explicitLinks': links, 'attachments': [], 'qualification': 'ACQUIRED_NOT_QUALIFIED',
              'discoveryFlags': candidate.get('flags', []),
              'businessPublishable': False, 'modelInputReady': False,
              'modelLimitation': 'Existing v1/v2 model workers have not adopted supplementary source bundles.',
              'processing': {name: sha(Path(__file__).with_name(name).read_bytes()) for name in
                             ('acquire_public_literature.py', 'literature_attachments.py', 'prepare_literature.py')}}
    coverage = {'status': 'NO_EXPLICIT_SUPPLEMENT_SIGNAL', 'completePublicationProven': False,
                'meaning': 'Absence of a link or endpoint result does not prove that no supplementary material exists.'}
    if wants_supplement or links:
        try:
            package = transport.get(pmcid, 'supplementaryFiles')
            name = 'supplementary/' + sha(package) + '.zip'
            files[name] = package
            package_record = {'path': name, 'sha256': sha(package), 'bytes': len(package),
                              'url': url_for(pmcid, 'supplementaryFiles')}
            # Keep failed responses as originals, but do not advertise them as a
            # validated ZIP: the model loader reparses every advertised package.
            bundle['unparsedPackage'] = package_record
            expansion_budget = [100 * 1024 * 1024]
            items = members(package, expansion_budget)
            require(items, 'SUPPLEMENT_PACKAGE_EMPTY')
            bundle['package'] = bundle.pop('unparsedPackage')
            names = [original for original, unused in items]
            derived_total = len(prepared)
            for original, body in items:
                # Filenames are provenance only; never used to extract to filesystem paths.
                suffix = PurePosixPath(original).suffix.lower()
                suffix = suffix if re.fullmatch(r'\.[a-z0-9]{1,8}', suffix) else '.bin'
                source_name = 'supplementary/' + sha(body) + suffix
                files[source_name] = body
                parsed = parse_attachment(original, body, expansion_budget)
                try:
                    parsed_raw = encoded(parsed, max(1, 20 * 1024 * 1024 - derived_total))
                except ValueError:
                    parsed = {'originalName': original, 'sha256': sha(body), 'bytes': len(body),
                              'status': 'ARCHIVED_NOT_PARSED', 'reasonCode': 'PAPER_ATTACHMENT_DERIVED_SIZE_LIMIT'}
                    parsed_raw = encoded(parsed)
                derived_total += len(parsed_raw)
                derived_name = 'derived/' + sha(parsed_raw) + '.json'
                files[derived_name] = parsed_raw
                matches = []
                for link in links:
                    basename = PurePosixPath(urllib.parse.urlsplit(link.get('href') or '').path).name
                    equal = [n for n in names if PurePosixPath(n).name == basename]
                    if len(equal) == 1 and equal[0] == original:
                        matches.append(link['sourceUnitId'])
                bundle['attachments'].append({'originalName': original, 'path': source_name, 'sha256': sha(body),
                    'bytes': len(body), 'derivedPath': derived_name, 'derivedSha256': sha(parsed_raw),
                    'status': parsed['status'], 'reasonCode': parsed.get('reasonCode'), 'matchedSourceUnits': matches,
                    'association': 'EXACT_JATS_FILENAME' if matches else 'OFFICIAL_PMCID_PACKAGE_ONLY'})
            matched = {unit for item in bundle['attachments'] for unit in item['matchedSourceUnits']}
            coverage = {'status': 'PACKAGE_ACQUIRED', 'completePublicationProven': False,
                        'unmatchedSourceUnits': [link['sourceUnitId'] for link in links if link['sourceUnitId'] not in matched],
                        'parsedAttachments': sum(a['status'] in {'CELLS_READ_NOT_SEMANTICALLY_INTERPRETED',
                                                                'TEXT_READ_NOT_SEMANTICALLY_INTERPRETED'}
                                                 for a in bundle['attachments']),
                        'unparsedAttachments': sum(a['status'] == 'ARCHIVED_NOT_PARSED' for a in bundle['attachments'])}
        except (ValueError, OSError, zipfile.BadZipFile, RuntimeError, NotImplementedError) as error:
            code = str(error)
            coverage = {'status': 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED', 'completePublicationProven': False,
                        'reasonCode': code if code.startswith(('PAPER_', 'LITERATURE_')) else 'PAPER_ATTACHMENT_PACKAGE_INVALID'}
    bundle['supplementCoverage'] = coverage
    return files, bundle


def acquire(discovery, discovery_sha, library, transport, output, maximum=5, criteria=('PS2','PM6')):
    require(type(maximum) is int and 1 <= maximum <= MAX_PAPERS, 'PAPER_BUDGET_INVALID')
    require(isinstance(criteria, tuple) and bool(criteria) and len(set(criteria))==len(criteria)
            and set(criteria) <= set(SUPPORTED_CRITERIA), 'CRITERIA_INVALID')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    raw = bounded(Path(discovery), 4 * 1024 * 1024)
    require(sha(raw) == discovery_sha, 'DISCOVERY_PIN_MISMATCH')
    plan = json.loads(raw, object_pairs_hook=unique)
    require(plan.get('schemaVersion') == 'denovo-literature-discovery-v1' and
            isinstance(plan.get('candidates'), list) and len(plan['candidates']) <= 200, 'DISCOVERY_SCHEMA')
    known = local_documents(library)
    report = {'schemaVersion': VERSION, 'discoverySha256': discovery_sha, 'maximumPapers': maximum,
              'papers': [], 'networkRequests': 0, 'transferredBytes': 0, 'modelCalls': 0,
              'businessPublishable': False, 'registrationMeaning': 'RETRIEVAL_CANDIDATES_NOT_SCIENTIFIC_APPROVAL'}
    count, seen = 0, set()
    for rank, candidate in enumerate(plan['candidates'], 1):
        item = {'rank': rank, 'pmcid': candidate.get('pmcid'), 'pmid': candidate.get('pmid'), 'status': 'SKIPPED'}
        report['papers'].append(item)
        pmcid = candidate.get('pmcid')
        try:
            require(isinstance(pmcid, str) and re.fullmatch(r'PMC[1-9][0-9]*', pmcid), 'NO_PMCID')
            require(not any(flag in candidate.get('flags', []) for flag in
                           ('BIBLIOGRAPHIC_IDENTITY_CONFLICT', 'RETRACTION_REQUIRES_REVIEW')), 'CANDIDATE_REQUIRES_REVIEW')
            require(pmcid not in seen, 'DUPLICATE_PMCID')
            seen.add(pmcid)
            require(count < maximum, 'PAPER_BUDGET')
            if candidate.get('localReviewedBundleSha256'):
                # Preserve the exact registered source snapshot referenced by a
                # reviewed-cache hit; rebuilding from JATS can drop attachments
                # and produce a different bundle. This grants no evidence score.
                from bundle_model_contract import load
                pin=candidate['localReviewedBundleSha256']
                require(isinstance(pin,str) and re.fullmatch('[a-f0-9]{64}',pin),'LOCAL_BUNDLE_PIN')
                require(pmcid in known, 'LOCAL_BUNDLE_NOT_REGISTERED')
                register_existing_bundle(library, pmcid, pin, known[pmcid], criteria, candidate=candidate)
                for criterion in criteria:cached=load(library,pmcid,pin,criterion=criterion)
                identity(candidate,cached['main'])
                count+=1
                item.update(status='REGISTERED',sourceKind='VERIFIED_REGISTERED_PINNED_BUNDLE_REUSE',
                    registration={'status':'REGISTERED','bundleSha256':pin,'reused':True})
                continue
            local = pmcid in known
            route = {'kind': 'fullTextXML'}
            source_url = url_for(pmcid, route['kind'])
            manuscript = None
            if local:
                selected = select(library, [known[pmcid]], [pmcid], denovo=known[pmcid] in {'PS2','PM6'})['documents'][0]
                source_url = selected['acquisitionUrl']
                require(source_url in {url_for(pmcid, 'fullTextXML'), url_for(pmcid, 'authorManuscriptXML')}
                        or verified_xml_url(source_url, pmcid),
                    'LOCAL_SOURCE_ROUTE_UNSUPPORTED')
                source = bounded(Path(selected['path']), 2097152)
                document = prepare(source, pmcid, selected['sha256'])
                identity(candidate, document)
                original, manuscript = local_manuscript(library, pmcid, source, criterion=known[pmcid])
                if manuscript is not None:
                    route = {'kind': 'authorManuscriptXML'}
                wants = candidate.get('hasSupplement') == 'Y'
                source_kind = 'REGISTERED_LOCAL_SOURCE'
            else:
                wants, route = verified_metadata(candidate, transport.cache, with_route=True)
                source_kind = route['sourceKind']
            count += 1
            item['status'] = 'ACQUISITION_STARTED'
            if not local:
                if route['kind'] == 'authorManuscriptXML':
                    source_url = url_for(pmcid, route['kind'])
                    original = transport.get(pmcid, route['kind'])
                    source, document, manuscript = derive_manuscript(original, pmcid, route['manuscriptId'])
                else:
                    source, source_url = transport.get_open_access(pmcid, candidate)
                    if verified_xml_url(source_url, pmcid):
                        source_kind = 'OFFICIAL_NCBI_PMC_OPEN_ACCESS_ARCHIVE'
                    document = prepare(source, pmcid, sha(source))
                identity(candidate, document)
            captured = captured_single_bundle(library, pmcid, known[pmcid]) if local else None
            if captured:
                pin, bundle, bundle_raw = captured
                register_existing_bundle(library, pmcid, pin, known[pmcid], criteria, candidate=candidate)
                folder = output / pmcid; folder.mkdir()
                (folder / 'bundle.json').write_bytes(bundle_raw)
                item.update(status='REGISTERED', sourceKind='VERIFIED_REGISTERED_SINGLE_FILE_REUSE',
                    sourceSha256=sha(source), units=len(document['units']), tables=len(document['tables']),
                    supplementCoverage=bundle['supplementCoverage'],
                    registration={'status': 'REGISTERED', 'bundleSha256': pin, 'reused': True})
                report.update(networkRequests=transport.requests, transferredBytes=transport.transferred,
                              retrievals=transport.events)
                atomic_json(output / 'acquisition.json', report)
                continue
            files, bundle = source_bundle(candidate, source, document, transport, wants)
            if manuscript is not None:
                raw_path = 'originals/' + sha(original) + '.xml'
                files[raw_path] = original
                bundle['authorManuscript'] = {**manuscript, 'rawPath': raw_path,
                    'acquisitionUrl': url_for(pmcid, route['kind'])}
            folder = output / pmcid
            folder.mkdir()
            for name, body in files.items():
                destination = folder / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(body)
            (folder / 'bundle.json').write_bytes(encoded(bundle))
            metadata = {'schemaVersion': 'public-paper-metadata-v1', 'identifiers': document['identifiers'],
                        'title': document['title'], 'filename': pmcid + '.xml', 'bytes': len(source),
                        'sha256': sha(source), 'format': 'JATS_XML', 'sourceClass': 'PUBLIC_PAPER',
                        'acquisitionUrl': source_url, 'acquiredAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                        'candidateCriteria': list(criteria), 'discoverySha256': discovery_sha,
                        'sourceBundleSha256': sha(encoded(bundle)), 'qualification': 'ACQUIRED_NOT_QUALIFIED'}
            registration = register(library, pmcid, files, bundle, metadata, criteria=criteria)
            item.update(status=registration['status'], sourceKind=source_kind, sourceSha256=sha(source),
                        units=len(document['units']), tables=len(document['tables']),
                        supplementCoverage=bundle['supplementCoverage'], registration=registration)
        except (ValueError, KeyError, TypeError, OSError) as error:
            code = str(error)
            item.update(status='UNAVAILABLE' if item['status'] == 'ACQUISITION_STARTED' else 'SKIPPED',
                        reasonCode=code if code.startswith(('PAPER_', 'LITERATURE_', 'DENOVO_BUNDLE_')) else 'PAPER_ACQUISITION_INVALID_INPUT_OR_IO')
        report.update(networkRequests=transport.requests, transferredBytes=transport.transferred, retrievals=transport.events)
        atomic_json(output / 'acquisition.json', report)
    report['status'] = 'COMPLETED_WITH_EXPLICIT_LIMITS'
    report['implementation'] = {name: sha(Path(__file__).with_name(name).read_bytes()) for name in
        ('acquire_public_literature.py', 'paper_download_transport.py', 'paper_library_registration.py',
         'author_manuscript_source.py', 'pmc_open_data.py', 'literature_attachments.py', 'literature_docx.py',
         'official_supplement_source.py', 'prepare_literature.py')}
    atomic_json(output / 'acquisition.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('discovery', 'library', 'cache', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--max-papers', type=int, default=5)
    parser.add_argument('--online', action='store_true')
    args = parser.parse_args()
    # Requests live beside results so a failed batch never loses partial bodies.
    with DownloadTransport(args.cache, args.output.with_name(args.output.name + '-requests'), args.online) as transport:
        report = acquire(args.discovery, args.sha256, args.library, transport, args.output, args.max_papers)
    print(json.dumps({'status': report['status'], 'networkRequests': report['networkRequests'],
                      'registered': sum(p['status'] == 'REGISTERED' for p in report['papers']), 'modelCalls': 0}))


if __name__ == '__main__':
    main()
