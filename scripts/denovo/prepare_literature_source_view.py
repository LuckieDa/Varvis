"""Prepare local original-region images for the workbench; never fetch or analyse evidence."""
from pathlib import Path
import argparse
import json
import os
import re
from build_literature_source_screenshots import DOCX_LOCATOR, build


def paths(config):
    project = Path(config['projectRoot']).resolve()
    library = (project / config['library']).resolve()
    if not library.is_relative_to(project / 'local'):
        raise ValueError('SOURCE_LIBRARY_SCOPE_INVALID')
    candidates = list((project / 'local/tools/node').glob('node-v*-win-x64/node.exe'))
    candidates.sort(key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.parent.name)), reverse=True)
    if not candidates:
        raise ValueError('SOURCE_RENDERER_NODE_UNAVAILABLE')
    browsers = [os.environ.get('VARVIS_SOURCE_VIEW_BROWSER', ''),
                'C:/Program Files/Google/Chrome/Application/chrome.exe',
                'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']
    browser = next((Path(p) for p in browsers if p and Path(p).is_file()), None)
    if browser is None:
        raise ValueError('SOURCE_RENDERER_BROWSER_UNAVAILABLE')
    return library, candidates[0], browser


def prepare(config, request):
    refs = request.get('references')
    if not isinstance(refs, list) or not 1 <= len(refs) <= 32:
        raise ValueError('SOURCE_REFERENCE_LIMIT')
    docs = {}
    for ref in refs:
        if not isinstance(ref, dict) or not re.fullmatch(r'PMC[1-9][0-9]*', str(ref.get('pmcid', ''))):
            raise ValueError('SOURCE_REFERENCE_INVALID')
        locator, quote = ref.get('locator'), ref.get('quote')
        if (not isinstance(locator, str) or len(locator) > 2048
                or not (locator.startswith('/article[1]/') or DOCX_LOCATOR.fullmatch(locator))):
            raise ValueError('SOURCE_LOCATOR_INVALID')
        if not isinstance(quote, str) or not 1 <= len(quote) <= 12000:
            raise ValueError('SOURCE_QUOTE_INVALID')
        document = docs.setdefault(ref['pmcid'], {'pmcid': ref['pmcid'], 'includeGallery': False, 'skipUnavailableReferences': True,
                                                'cases': [{'rows': []}]})
        document['cases'][0]['rows'].append({'sourceRefs': [{'unitId': locator, 'quote': quote}]})
    library, node, browser = paths(config)
    return build({'documents': list(docs.values())}, library, library / 'source-screenshots', browser, node)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--request', required=True)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding='utf-8-sig'))
    request_path = Path(args.request).resolve()
    library, _, _ = paths(config)
    if not request_path.is_relative_to(library / 'source-screenshots/requests'):
        raise ValueError('SOURCE_REQUEST_SCOPE_INVALID')
    if request_path.stat().st_size > 512 * 1024:
        raise ValueError('SOURCE_REQUEST_LIMIT')
    receipt = prepare(config, json.loads(request_path.read_text(encoding='utf-8')))
    print(json.dumps(receipt, ensure_ascii=False))


if __name__ == '__main__':
    main()
