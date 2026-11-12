"""Select registered public papers by evidence folder, without a library scan.

Folder membership is a retrieval hint, never evidence qualification. The optional
de novo export uses the existing worker schema without changing its cache identity.
No network, model invocation, activation, or recursive directory traversal.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
from pmc_open_data import verified_xml_url

CRITERIA = ('PVS1', 'PS1', 'PS2', 'PS3', 'PS4', 'PM1', 'PM2', 'PM3', 'PM4', 'PM5', 'PM6',
            'PP1', 'PP2', 'PP3', 'PP4', 'BA1', 'BS1', 'BS2', 'BS3', 'BS4',
            'BP1', 'BP2', 'BP3', 'BP4', 'BP5', 'BP7')
MAX_INDEX_BYTES = 1_048_576
MAX_PAPER_BYTES = 2_097_152


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'PAPER_INDEX_DUPLICATE_KEY')
        result[key] = value
    return result


def bounded(path, limit):
    with path.open('rb') as stream:
        raw = stream.read(limit + 1)
    require(len(raw) <= limit, 'PAPER_SIZE_LIMIT')
    return raw


def select(library, criteria, pmcids=(), denovo=False):
    root = Path(library).resolve(strict=True)
    require(criteria and len(criteria) == len(set(criteria)) and all(c in CRITERIA for c in criteria),
            'PAPER_CRITERION_INVALID_OR_DISABLED')
    require(not denovo or set(criteria) <= {'PS2', 'PM6'}, 'DENOVO_CRITERION_NOT_SUPPORTED')
    requested = set(pmcids)
    require(all(re.fullmatch(r'PMC[1-9][0-9]*', p) for p in requested), 'PAPER_ID_INVALID')
    documents = {}
    for criterion in criteria:
        index_path = root / 'evidence' / criterion / 'index.json'
        require(index_path.is_file(), 'PAPER_CRITERION_NOT_REGISTERED')
        index_path = index_path.resolve(strict=True)
        require(index_path.is_relative_to(root / 'evidence'), 'PAPER_INDEX_OUTSIDE_LIBRARY')
        index = json.loads(bounded(index_path, MAX_INDEX_BYTES), object_pairs_hook=unique)
        require(isinstance(index, dict) and index.get('schemaVersion') == 'public-paper-evidence-index-v1'
                and index.get('criterion') == criterion and isinstance(index.get('documents'), list),
                'PAPER_INDEX_IDENTITY_INVALID')
        for entry in index['documents']:
            require(isinstance(entry, dict) and isinstance(entry.get('pmcid'), str)
                    and re.fullmatch(r'PMC[1-9][0-9]*', entry['pmcid']), 'PAPER_ID_INVALID')
            pmcid = entry['pmcid']
            if requested and pmcid not in requested:
                continue
            require(entry.get('sourceClass') == 'PUBLIC_PAPER'
                    and isinstance(entry.get('sha256'), str)
                    and re.fullmatch(r'[a-f0-9]{64}', entry['sha256']), 'PAPER_SOURCE_IDENTITY_INVALID')
            require(isinstance(entry.get('path'), str) and not Path(entry['path']).is_absolute(),
                    'PAPER_PATH_INVALID')
            path = (index_path.parent / entry['path']).resolve(strict=True)
            require(path.is_relative_to(root / 'papers'), 'PAPER_PATH_OUTSIDE_SOURCES')
            url = entry.get('acquisitionUrl')
            require(isinstance(url, str) and url.startswith('https://'), 'PAPER_ACQUISITION_URL_REQUIRED')
            if denovo:
                manuscript_url = ('https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pmc&id='
                                  + pmcid[3:] + '&retmode=xml')
                require(url in {'https://www.ebi.ac.uk/europepmc/webservices/rest/' + pmcid + '/fullTextXML', manuscript_url}
                        or verified_xml_url(url, pmcid),
                        'PAPER_DENOVO_SOURCE_UNSUPPORTED')
            document = {key: entry[key] for key in ('sourceClass', 'pmcid', 'sha256', 'acquisitionUrl')}
            document['path'] = str(path)
            if pmcid in documents:
                require(document == documents[pmcid], 'PAPER_INDEX_CONFLICT')
                continue
            require(len(documents) < 20, 'PAPER_SELECTION_LIMIT_SELECT_FEWER')
            raw = bounded(path, MAX_PAPER_BYTES)
            require(hashlib.sha256(raw).hexdigest() == document['sha256'], 'PAPER_SHA_MISMATCH')
            if denovo:
                from prepare_literature import prepare
                prepare(raw, pmcid, document['sha256'])
                if url == manuscript_url:
                    from author_manuscript_source import local_manuscript
                    original, proof = local_manuscript(root, pmcid, raw)
                    require(original is not None and proof is not None, 'PAPER_MANUSCRIPT_ORIGINAL_REQUIRED')
            documents[pmcid] = document
    require(requested <= documents.keys(), 'PAPER_NOT_IN_SELECTED_EVIDENCE')
    require(not denovo or documents, 'PAPER_NO_REGISTERED_DOCUMENTS')
    return {'schemaVersion': 'denovo-public-model-input-v1' if denovo else 'public-paper-selection-v1',
            'documents': [documents[key] for key in sorted(documents)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', required=True, type=Path)
    parser.add_argument('--criteria', required=True, nargs='+')
    parser.add_argument('--pmcid', action='append', default=[])
    parser.add_argument('--denovo', action='store_true', help='Export for existing PS2/PM6 worker')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = select(args.library, args.criteria, args.pmcid, args.denovo)
    with args.output.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({'documents': len(result['documents']), 'modelCalls': 0, 'networkCalls': 0}))


if __name__ == '__main__':
    main()
