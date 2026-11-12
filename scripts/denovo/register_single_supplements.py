"""Explicit offline import of captured PMC file downloads into immutable bundles.

Does not fetch, refresh indexes' old versions, approve cases, or call a model.
Input is a capture record, article HTML and its retrieval record, not naked files.
"""
import argparse
from copy import deepcopy
from pathlib import Path

from bundle_model_contract import load, pinned
from literature_attachments import parse_attachment
from model_literature_contract import digest, parse
from official_supplement_source import validate, VERSION, require
from paper_library_registration import register, encoded, inside
from select_public_papers import bounded


def import_captured(library, pmcid, parent_pin, acquisition, article, article_record):
    source = load(library, pmcid, parent_pin)  # Existing registered/qualified main only.
    root = inside(Path(library).resolve(strict=True), 'papers/' + pmcid)
    parent_raw = pinned(root, 'source-bundles/' + parent_pin + '.json', parent_pin, 2097152)
    bundle = deepcopy(parse(parent_raw))
    files = {pmcid + '.xml': source['raw']}

    def evidence(path, suffix, maximum):
        raw = bounded(Path(path), maximum)
        name = 'provenance/' + digest(raw) + suffix
        files[name] = raw
        return {'path': name, 'sha256': digest(raw)}

    receipt = {'schemaVersion': VERSION, 'pmcid': pmcid, 'mainSha256': digest(source['raw']),
               'acquisitionRecord': evidence(acquisition, '.json', 1048576),
               'article': evidence(article, '.html', 2097152),
               'articleRecord': evidence(article_record, '.json', 65536)}
    captures = parse(files[receipt['acquisitionRecord']['path']]).get('downloads', [])
    require(isinstance(captures, list) and 0 < len(captures) <= 32, 'IMPORT_FILE_LIMIT')
    names = {a['originalName'] for a in bundle['attachments']}
    budget = [100 * 1024 * 1024]
    for row in captures:
        item = {k: row[k] for k in ('originalName', 'url', 'path', 'sha256', 'bytes')}
        require(item['originalName'] not in names, 'DUPLICATE_ATTACHMENT')
        names.add(item['originalName'])
        body = pinned(root, item['path'], item['sha256'], 20 * 1024 * 1024)
        item.update(association='EXACT_JATS_FILENAME', matchedSourceUnits=[
            l['sourceUnitId'] for l in source['main']['links'] if l.get('href') in {
                item['originalName'], item['url'], item['url'].removeprefix('https://pmc.ncbi.nlm.nih.gov')}
            and l['kind'] in {'media', 'supplementary-material', 'ext-link'} and
            '/ref[' not in l['sourceUnitId'] and '/ref-list[' not in l['sourceUnitId']])
        validate(source['main'], item, body, receipt, lambda ref, limit: files[ref['path']])
        receipt_raw = encoded(receipt)
        receipt_path = 'provenance/' + digest(receipt_raw) + '.json'
        files[receipt_path] = receipt_raw
        item['officialSingleFile'] = {'path': receipt_path, 'sha256': digest(receipt_raw)}
        parsed = parse_attachment(item['originalName'], body, budget)
        derived = encoded(parsed)
        derived_path = 'derived/' + digest(derived) + '.json'
        files[derived_path] = derived
        item.update(derivedPath=derived_path, derivedSha256=digest(derived), status=parsed['status'],
                    reasonCode=parsed.get('reasonCode'))
        bundle['attachments'].append(item)
    bundle['singleFileImport'] = {'parentBundleSha256': parent_pin, 'version': VERSION,
                                 'meaning': 'RETRIEVAL_ONLY_NO_SCIENTIFIC_ADOPTION'}
    matched = {uid for item in bundle['attachments'] for uid in item.get('matchedSourceUnits', [])}
    bundle['supplementCoverage'] = {'status': 'EXPLICIT_SINGLE_FILES_REGISTERED', 'completePublicationProven': False,
        'unmatchedSourceUnits': [l['sourceUnitId'] for l in bundle['explicitLinks'] if l['sourceUnitId'] not in matched],
        'parsedAttachments': sum(a['status'] != 'ARCHIVED_NOT_PARSED' for a in bundle['attachments']),
        'unparsedAttachments': sum(a['status'] == 'ARCHIVED_NOT_PARSED' for a in bundle['attachments'])}
    bundle['modelLimitation'] = 'Loader reparses exact sources; quotation and case qualification remain separate.'
    index = parse(bounded(Path(library) / 'evidence/PS2/index.json', 1048576))
    entry = next(e for e in index['documents'] if e.get('pmcid') == pmcid)
    return register(library, pmcid, files, bundle, {'sha256': digest(source['raw']),
                    'acquisitionUrl': entry['acquisitionUrl']})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('library', 'pmcid', 'parent-pin', 'acquisition', 'article', 'article-record', 'output'):
        parser.add_argument('--' + option, required=True)
    args = parser.parse_args()
    output = Path(args.output)
    require(not output.exists(), 'OUTPUT_ALREADY_EXISTS')
    result = import_captured(args.library, args.pmcid, args.parent_pin, args.acquisition,
                             args.article, args.article_record)
    output.write_bytes(encoded(result))
