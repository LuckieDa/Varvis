"""Offline bounded A1 reading of registered originals; no scoring or new retrieval."""
import argparse
import json
import re
from tempfile import NamedTemporaryFile
from pathlib import Path

from start_loss_functional_reading import read, load, require, canonical, digest, cite, VERSION
from model_transport import bounded
from paper_library_registration import inside

SCHEMA = 'pvs1-start-loss-functional-library-v1'


def local_reading(library, source, gene):
    """Reuse the paper-level reading; candidate/isoform applicability is recomputed by the caller."""
    method = [digest(Path(__file__).with_name(name).read_bytes()) for name in
              ('start_loss_functional_library.py', 'start_loss_functional_reading.py', 'start_loss_experiment_facts.py')]
    identity = dict(pmcid=source['pmcid'], bundleSha256=source['bundleSha256'], gene=gene, method=method,
                    unitsSha256=digest(canonical(source['units'])), coverage=source['coverage'])
    key = digest(canonical(identity))
    root = Path(library).resolve(strict=True)
    directory = inside(root, 'reviewed-evidence/' + source['pmcid'] + '/reading-cache/PVS1/' + key[:32])
    try:
        # Limit the search to this exact reading contract. The filename authenticates the payload bytes.
        for path in sorted(directory.glob('*.json'))[:2]:
            raw = bounded(inside(root, path), 8388608)
            require(path.stem == digest(raw), 'READING_CACHE_PIN_MISMATCH')
            record = json.loads(raw)
            result = record['material']
            require(record['identity'] == identity and result['schemaVersion'] == VERSION
                    and result['pmcid'] == source['pmcid'] and result['bundleSha256'] == source['bundleSha256']
                    and result['sourceSha256'] == source['main']['sourceSha256'] and result['geneSymbol'] == gene
                    and all(result[flag] is False for flag in
                            ('runtimeEligible', 'functionalRescueEstablished', 'negativeFunctionalCoverageGranted')),
                    'READING_CACHE_SCOPE_MISMATCH')
            for finding in result['findings']:
                reference = finding['citation']
                original = cite(source, {field: reference[field] for field in ('unitId', 'sourceSha256', 'quote')})
                original.pop('sourceContext', None)
                require(original == reference, 'READING_CACHE_QUOTE_MISMATCH')
            return result, True
    except (ValueError, KeyError, TypeError, OSError):
        pass
    result = read(source, gene, [], 32)
    if result['matchingUnitCount']:
        raw = canonical(dict(schemaVersion='public-literature-checked-reading-v1', identity=identity, material=result))
        try:
            require(len(raw) <= 8388608, 'READING_CACHE_SIZE_LIMIT')
            path = inside(root, directory / (digest(raw) + '.json'))
            if not path.exists():
                directory.mkdir(parents=True, exist_ok=True)
                with NamedTemporaryFile(dir=directory, prefix='read-', suffix='.tmp', delete=False) as stream:
                    staged = Path(stream.name)
                    stream.write(raw)
                try:
                    staged.replace(path)
                finally:
                    staged.unlink(missing_ok=True)
        except (ValueError, OSError):
            # A storage failure must never change this run's observed evidence.
            pass
    return result, False


def review(library, gene, index_pin):
    require(bool(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}', gene)), 'GENE_INVALID')
    index_path = Path(library) / 'evidence/PVS1/index.json'
    index_raw = bounded(index_path, 1048576)
    require(digest(index_raw) == index_pin, 'INDEX_CHANGED')
    index = json.loads(index_raw)
    require(isinstance(index, dict) and index.get('schemaVersion') == 'public-paper-evidence-index-v1' and index.get('criterion') == 'PVS1'
            and isinstance(index.get('documents'), list), 'INDEX_SCHEMA')
    documents, pending, originals, reused = [], [], {}, 0
    entries = index['documents']
    for position, entry in enumerate(entries):
        if not isinstance(entry, dict):
            pending.append({'pmcid': '', 'reasonCode': 'REGISTERED_DOCUMENT_INVALID'})
            continue
        pmcid = entry.get('pmcid', '')
        if position >= 8:
            pending.append({'pmcid': pmcid, 'reasonCode': 'READING_DOCUMENT_BUDGET'})
            continue
        bundles = entry.get('acquisitionBundles', [])
        if (entry.get('sourceClass') != 'PUBLIC_PAPER' or not isinstance(bundles, list) or len(bundles) != 1
                or not isinstance(bundles[0], dict)):
            pending.append({'pmcid': pmcid, 'reasonCode': 'UNIQUE_ACTIVE_BUNDLE_REQUIRED'})
            continue
        try:
            source = load(library, pmcid, bundles[0]['sha256'], criterion='PVS1')
            # The gene is a search hint, never binding to an exact transcript.
            result, cache_hit = local_reading(library, source, gene)
            reused += int(cache_hit)
            if not result['matchingUnitCount']:
                pending.append({'pmcid': pmcid, 'reasonCode': 'NO_LITERAL_GENE_PASSAGE_NOT_BIOLOGICAL_EXCLUSION'})
                continue
            originals[source['main']['sourceSha256']] = {'kind': 'PUBLIC_PAPER_XML', 'pmcid': pmcid,
                                                        'url': 'https://pmc.ncbi.nlm.nih.gov/articles/' + pmcid + '/',
                                                        'rawXml': source['raw'].decode('utf-8')}
            documents.append(result)
        except (ValueError, KeyError, OSError, TypeError) as error:
            reason = str(error)
            pending.append({'pmcid': pmcid, 'reasonCode': reason if re.fullmatch(r'[A-Z][A-Z0-9_]{1,120}', reason)
                            else 'REGISTERED_SOURCE_UNAVAILABLE_OR_INVALID'})
    require(digest(bounded(index_path, 1048576)) == index_pin, 'INDEX_CHANGED')
    return {'schemaVersion': SCHEMA, 'geneSymbol': gene, 'indexSha256': index_pin,
            'status': 'READ_WITH_LIMITS' if documents else 'NO_USABLE_READING',
            'documents': documents, 'pendingDocuments': pending, 'originals': originals,
            'runtimeEligible': False, 'functionalRescueEstablished': False, 'negativeFunctionalCoverageGranted': False,
            'searchScope': 'REGISTERED_PVS1_LIBRARY_BOUNDED_LITERAL_GENE_SEARCH',
            'originalArchiveScope': 'MAIN_XML_AND_READ_QUOTES_SUPPLEMENT_ORIGINALS_REMAIN_IN_REGISTERED_LIBRARY',
            'documentCount': len(documents), 'indexedDocumentCount': len(entries), 'reusedReadingCount': reused}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('library', 'gene', 'index-sha256', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    result = review(Path(args.library), args.gene, args.index_sha256)
    raw = canonical(result)
    require(len(raw) <= 8388608, 'RESULT_SIZE_LIMIT')
    with Path(args.output).open('xb') as stream:
        stream.write(raw)
    print(json.dumps({'status': result['status'], 'documents': result['documentCount']}))


if __name__ == '__main__':
    main()
