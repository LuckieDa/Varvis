"""Capture public expert context pages for unresolved published scopes.

Explicit maintenance command; never changes workflow configuration or starts
variant analysis. Existing pinned sources stay immutable. No model calls.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from urllib.request import urlopen
from urllib.error import HTTPError
from model_literature_contract import canonical, digest, parse
from model_transport import bounded
from literature_context_options import options


def prepare(config, output):
    c = parse(bounded(config, 16384)); root = Path(c['projectRoot']).resolve(strict=True)
    out = Path(output).resolve()
    if c.get('allowPublicNetwork') is not True: raise ValueError('PUBLIC_NETWORK_DISABLED')
    if not out.is_relative_to(root/'local') or out.exists(): raise ValueError('NEW_LOCAL_OUTPUT_REQUIRED')
    contexts = options(root, c)['contexts']
    genes = sorted({r['geneSymbol'] for r in contexts if r['inheritanceResolution']['status'] in {'MISSING', 'RESOURCE_UNAVAILABLE'}})
    if len(genes) > 128: raise ValueError('EXPERT_INHERITANCE_REQUEST_LIMIT')
    out.mkdir(parents=True)
    sources, issues = [], []
    for index, gene in enumerate(genes):
        url = 'https://erepo.genome.network/evrepo/api/summary/classifications?' + urlencode(
            {'columns': 'gene', 'matchTypes': 'exact', 'values': gene})
        try:
            with urlopen(url, timeout=25) as response:
                actual = urlsplit(response.url)
                if actual.scheme != 'https' or actual.netloc != 'erepo.genome.network':
                    raise ValueError('EXPERT_INHERITANCE_REDIRECT_UNTRUSTED')
                raw = response.read(4*1024*1024+1)
            if len(raw) > 4*1024*1024: raise ValueError('SOURCE_SIZE_LIMIT')
            parse(raw)
            path = out/f'source-{index:03}.json'; path.write_bytes(raw)
            sources.append({'path': path.relative_to(root).as_posix(), 'sha256': digest(raw), 'sourceUrl': url, 'geneSymbol': gene})
        except HTTPError as error:
            issues.append({'geneSymbol': gene, 'sourceUrl': url, 'reasonCode': 'EXPERT_INHERITANCE_CAPTURE_FAILED',
                           'httpStatus': error.code})
        except (OSError, ValueError):
            issues.append({'geneSymbol': gene, 'sourceUrl': url, 'reasonCode': 'EXPERT_INHERITANCE_CAPTURE_FAILED'})
    result = {'sources': sources, 'issues': issues, 'capturedAt': datetime.now(timezone.utc).isoformat(),
              'networkRequests': len(genes), 'modelCalls': 0, 'completeDatabaseCoverage': False}
    (out/'manifest.json').write_bytes(canonical(result))
    return result


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--config', required=True); p.add_argument('--output', required=True)
    a=p.parse_args(); r=prepare(a.config, a.output); print(f"sources={len(r['sources'])} issues={len(r['issues'])}")
