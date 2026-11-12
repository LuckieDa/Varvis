"""Bounded NCBI author-manuscript wrapper -> auditable JATS derivative.

This is not an OA override. Both pinned discovery metadata and the returned
manuscript's explicit text-mining license are required by the acquisition path.
The untouched response is archived alongside the deterministic derivative.
"""
import re
from pathlib import Path
import xml.etree.ElementTree as ET

from prepare_literature import MAX_BYTES, prepare, sha, text

VERSION = 'ncbi-author-manuscript-jats-1'


def require(value, code):
    if not value:
        raise ValueError('PAPER_ACQUISITION_MANUSCRIPT_' + code)


def derive(raw, pmcid, manuscript_id):
    require(0 < len(raw) <= MAX_BYTES, 'SIZE_LIMIT')
    require(re.fullmatch(r'NIHMS[1-9][0-9]*', manuscript_id or ''), 'ID_INVALID')
    xml = raw.decode('utf-8-sig')
    require(not re.search(r'<!ENTITY|<!DOCTYPE[^>]*\[', xml, re.I), 'ENTITY_FORBIDDEN')
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise ValueError('PAPER_ACQUISITION_MANUSCRIPT_XML_INVALID') from error
    require(root.tag == 'pmc-articleset' and len(root) == 1 and root[0].tag == 'article',
            'SINGLE_ARTICLE_REQUIRED')
    article = root[0]
    stack, nodes = [(article, 1)], 0
    while stack:
        node, depth = stack.pop()
        nodes += 1
        require(nodes <= 40000 and depth <= 64, 'STRUCTURE_LIMIT')
        stack.extend((child, depth + 1) for child in node)
    meta = article.find('./front/article-meta')
    require(meta is not None, 'METADATA_REQUIRED')
    manuscript_ids = [text(n) for n in meta.findall('article-id') if n.get('pub-id-type') == 'manuscript-id']
    require(manuscript_ids == [manuscript_id], 'IDENTITY_MISMATCH')
    permissions = meta.find('permissions')
    license_texts = [text(n) for n in permissions.findall('.//license-p')] if permissions is not None else []
    require(any(re.match(r'^This file is available for text mining\.(?:\s|$)', s) for s in license_texts),
            'TEXT_MINING_PERMISSION_REQUIRED')
    # Alternative manuscript IDs are repeatable aliases, not conflicting PMIDs.
    # Retain every alias in the receipt and the same order in the derived field.
    alternatives = [n for n in meta.findall('article-id') if n.get('pub-id-type') == 'manuscript-id-alternative']
    values = [text(n) for n in alternatives]
    require(all(re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', v) for v in values), 'ALIAS_INVALID')
    if len(alternatives) > 1:
        alternatives[0].text = '; '.join(values)
        for n in alternatives[1:]:
            meta.remove(n)
    derived = ET.tostring(article, encoding='utf-8', xml_declaration=True)
    document = prepare(derived, pmcid, sha(derived))
    require(any(u['kind'] == 'BODY_PARAGRAPH' for u in document['units']), 'BODY_CONTENT_REQUIRED')
    receipt = {'version': VERSION, 'rawSha256': sha(raw), 'rawBytes': len(raw),
               'derivedSha256': sha(derived), 'manuscriptId': manuscript_id,
               'alternativeManuscriptIds': values, 'licenseTexts': license_texts,
               'transformations': ['UNWRAP_SINGLE_ARTICLE', 'XML_ELEMENTTREE_SERIALIZATION'] +
               (['JOIN_REPEATABLE_ALTERNATIVE_MANUSCRIPT_IDS_IN_SOURCE_ORDER'] if len(alternatives) > 1 else []),
               'implementationSha256': sha(Path(__file__).read_bytes())}
    return derived, document, receipt


def local_manuscript(library, pmcid, source, criterion='PS2'):
    """Keep the raw-to-derived chain when a registered manuscript is reused."""
    import json
    from select_public_papers import bounded, unique
    from paper_library_registration import inside
    library = Path(library).resolve()
    require(criterion in {'PS2','PM6','PM3','PP1','PVS1','PP2'}, 'LOCAL_CRITERION_INVALID')
    entries = json.loads(bounded(library / 'evidence' / criterion / 'index.json', 1048576), object_pairs_hook=unique)['documents']
    entry = next(e for e in entries if e.get('pmcid') == pmcid)
    base = inside(library, 'papers/' + pmcid)
    for ref in reversed(entry.get('acquisitionBundles', [])):
        require(isinstance(ref.get('sha256'), str) and re.fullmatch(r'[a-f0-9]{64}', ref['sha256']),
                'LOCAL_BUNDLE_PIN_INVALID')
        bundle_raw = bounded(base / 'source-bundles' / (ref['sha256'] + '.json'), 2097152)
        require(sha(bundle_raw) == ref['sha256'], 'LOCAL_BUNDLE_PIN_MISMATCH')
        bundle = json.loads(bundle_raw, object_pairs_hook=unique)
        require(bundle.get('pmcid') == pmcid and bundle.get('main', {}).get('sha256') == sha(source),
                'LOCAL_BUNDLE_IDENTITY_MISMATCH')
        proof = bundle.get('authorManuscript')
        if proof:
            original = bounded(inside(base, proof['rawPath']), 2097152)
            derived, unused, measured = derive(original, pmcid, proof['manuscriptId'])
            require(original and sha(original) == proof['rawSha256'] and derived == source and
                    {k: v for k, v in measured.items() if k != 'implementationSha256'} ==
                    {k: v for k, v in proof.items() if k not in {'rawPath', 'acquisitionUrl', 'implementationSha256'}},
                    'LOCAL_MANUSCRIPT_BINDING_MISMATCH')
            return original, {k: v for k, v in proof.items() if k not in {'rawPath', 'acquisitionUrl'}}
    return None, None
