"""Prepare bounded exact-version NCBI mapping inputs for offline qualification.

This opt-in public-resource command writes a new cache folder and descriptor;
it neither edits service configuration nor grants scientific approval. The
offline resolver must still check sequence, codon, protein and local reference.
"""
import argparse
from pathlib import Path
import re
import time
import urllib.request
from urllib.parse import quote
from model_literature_contract import canonical, digest, parse
from refseq_identity import API


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('DENOVO_REFSEQ_REDIRECT_NOT_ALLOWED')


def prepare(root, output, transcript, coding, fetch=None):
    if not re.fullmatch(r'NM_[0-9]+\.[0-9]+', transcript) or not re.fullmatch(r'c\.[1-9][0-9]*[ACGT]>[ACGT]', coding):
        raise ValueError('DENOVO_REFSEQ_EXACT_CODING_SNV_REQUIRED')
    root = Path(root).resolve(strict=True); output = Path(output).resolve()
    if not output.is_relative_to(root / 'local') or output.exists():
        raise ValueError('DENOVO_REFSEQ_NEW_LOCAL_DESTINATION_REQUIRED')
    output.mkdir(parents=True); sources = {}; started = time.monotonic()
    opener = urllib.request.build_opener(NoRedirect())

    def network(url):
        remaining = 60 - (time.monotonic() - started)
        if remaining <= 0: raise TimeoutError('DENOVO_REFSEQ_TOTAL_BUDGET')
        request = urllib.request.Request(url, headers={'User-Agent': 'Varvis-public-research-mapping/1.0'})
        with opener.open(request, timeout=min(20, remaining)) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
            return raw, response.status, response.geturl()

    def get(name, url):
        raw, status, final = (fetch or network)(url)
        if status != 200 or final != url or not 0 < len(raw) <= 4 * 1024 * 1024:
            raise ValueError('DENOVO_REFSEQ_RESPONSE_NOT_QUALIFIED')
        path = output / (name + '.raw'); path.write_bytes(raw)
        sources[name] = dict(path=path.relative_to(root).as_posix(), sha256=digest(raw),
                             url=url, finalUrl=final, httpStatus=status)
        (output / (name + '-receipt.json')).write_bytes(canonical(sources[name]))
        return raw

    data = parse(get('contextual', API + 'hgvs/' + quote(transcript + ':' + coding, safe='') + '/contextuals'))['data']
    rows = data.get('spdis', [])
    if data.get('input_hgvs_validity') != 'valid' or len(rows) != 1 or rows[0].get('seq_id') != transcript:
        raise ValueError('DENOVO_REFSEQ_CONTEXTUAL_UNRESOLVED')
    item = rows[0]
    if type(item.get('position')) is not int or item['position'] < 0 or any(
            not re.fullmatch('[ACGT]', item.get(k, '')) for k in ('deleted_sequence', 'inserted_sequence')):
        raise ValueError('DENOVO_REFSEQ_CONTEXTUAL_NOT_SNV')
    spdi = ':'.join(str(item[k]) for k in ('seq_id', 'position', 'deleted_sequence', 'inserted_sequence'))
    get('refseq', 'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=nuccore&id=' + transcript + '&rettype=gb&retmode=text')
    get('equivalents', API + 'spdi/' + quote(spdi, safe='') + '/all_equivalent_contextual')
    if time.monotonic() - started > 60: raise TimeoutError('DENOVO_REFSEQ_TOTAL_BUDGET')
    path = output / 'manifest.json'
    path.write_bytes(canonical(dict(schemaVersion='denovo-refseq-snv-cache-v1', transcript=transcript, coding=coding, sources=sources)))
    descriptor = dict(transcript=transcript, coding=coding, manifest=dict(path=path.relative_to(root).as_posix(),sha256=digest(path.read_bytes())))
    (output / 'descriptor.json').write_bytes(canonical(descriptor))
    return descriptor


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for key in ('project-root', 'output', 'transcript', 'coding'): p.add_argument('--' + key, required=True)
    a = p.parse_args()
    print(canonical(prepare(a.project_root, a.output, a.transcript, a.coding)).decode())
