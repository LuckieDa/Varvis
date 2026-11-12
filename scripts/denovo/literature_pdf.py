"""Bounded PDF text/grid acquisition, never OCR or clinical interpretation.

The parser runs in a short-lived child using the project-local pinned tool.
Raw bytes remain authoritative. Missing tools, encrypted/scanned documents and
ambiguous layout are explicit; no text is silently substituted for an image.
"""
import hashlib
import json
import os
from collections import OrderedDict
from copy import deepcopy
from pathlib import Path
import subprocess
import sys

VERSION = 'literature-pdf-grid-v1'
MAX_OUTPUT = 16 * 1024 * 1024
_CACHE = OrderedDict()


def read_pdf(raw, expansion_budget=None):
    root = Path(__file__).resolve().parents[2]
    tool = root / 'local/tools/literature-pdf'
    try:
        manifest_raw = (tool / 'manifest.json').read_bytes()
        manifest = json.loads(manifest_raw)
        if manifest['schemaVersion'] != 'literature-pdf-tool-v1': raise ValueError()
        if not 1 <= len(manifest['files']) <= 4096: raise ValueError()
        for item in manifest['files']:
            path = (tool / item['path']).resolve(strict=True)
            if not path.is_relative_to(tool.resolve()) or not path.is_file(): raise ValueError()
            if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']: raise ValueError()
    except (OSError, ValueError, KeyError, TypeError):
        raise ValueError('PAPER_ATTACHMENT_PDF_TOOL_UNAVAILABLE_OR_CHANGED') from None
    worker = Path(__file__).with_name('literature_pdf_worker.py')
    key = (hashlib.sha256(raw).hexdigest(), hashlib.sha256(manifest_raw).hexdigest(),
           hashlib.sha256(worker.read_bytes()).hexdigest())
    if key in _CACHE:
        parsed, size = _CACHE[key]
        if expansion_budget is not None:
            expansion_budget[0] -= size
            if expansion_budget[0] < 0: raise ValueError('PAPER_ATTACHMENT_EXPANSION_LIMIT')
        _CACHE.move_to_end(key)
        return deepcopy(parsed)
    try:
        result = subprocess.run([sys.executable, '-I', '-B', str(Path(__file__).with_name('literature_pdf_worker.py'))],
                                input=raw, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=30, cwd=root, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
    except subprocess.TimeoutExpired:
        raise ValueError('PAPER_ATTACHMENT_PDF_TIMEOUT') from None
    if len(result.stdout) > MAX_OUTPUT: raise ValueError('PAPER_ATTACHMENT_PDF_OUTPUT_LIMIT')
    try: parsed = json.loads(result.stdout)
    except (ValueError, UnicodeError): raise ValueError('PAPER_ATTACHMENT_PDF_PARSE_FAILED') from None
    if result.returncode or parsed.get('error'):
        code = parsed.get('error', 'PAPER_ATTACHMENT_PDF_PARSE_FAILED')
        raise ValueError(code)
    if expansion_budget is not None:
        expansion_budget[0] -= len(result.stdout)
        if expansion_budget[0] < 0: raise ValueError('PAPER_ATTACHMENT_EXPANSION_LIMIT')
    parsed['parserVersion'] = VERSION
    parsed['toolManifestSha256'] = key[1]
    _CACHE[key] = (deepcopy(parsed), len(result.stdout))
    if len(_CACHE) > 4: _CACHE.popitem(last=False)
    return parsed
