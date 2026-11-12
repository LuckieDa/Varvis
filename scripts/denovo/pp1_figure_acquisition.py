"""Automatically register exact-version official open-access pedigree images."""
from pathlib import Path
import re
from xml.etree import ElementTree

from model_literature_contract import digest, parse
from pmc_open_data import ARCHIVE, image_url, object_digest, verify_object
from pp1_pedigree import register, require


def acquire_figures(library, source, transport, output):
    root = ElementTree.fromstring(source['raw'])
    stems = set()
    for figure in root.iter('fig'):
        captions = ' '.join(''.join(x.itertext()) for x in figure.findall('caption'))
        if not re.search(r'\bpedigree\b', captions, re.I):
            continue
        for graphic in figure.iter('graphic'):
            href = graphic.get('{http://www.w3.org/1999/xlink}href', '')
            if graphic.get('content-type') != 'thumb' and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,195}', href):
                stems.add(Path(href).stem if Path(href).suffix.lower() in {'.png','.jpg','.jpeg','.webp','.tif','.tiff'} else href)
    if not stems:
        return []
    pmcid = source['pmcid']
    existing = Path(library)/'papers'/pmcid/'pedigree-figures'
    from pp1_pedigree import load_figure
    registered = []
    for record in sorted(existing.glob('*/source.json'))[:8]:
        try:
            proof, _, _ = load_figure(source, record)
        except (ValueError, OSError, KeyError):
            continue
        stems.discard(Path(proof['graphicHref']).stem)
        registered.append({'figureId': proof['figureId'], 'status': 'REUSED', 'imageSha256': proof['imageSha256']})
    if not stems:
        return registered
    archive_xml, version, metadata = transport.get_archive_article(pmcid, source['main']['identifiers'])
    original_sha = source['main']['sourceSha256']
    require(digest(archive_xml) == original_sha, 'ARCHIVE_XML_VERSION_DIFFERS')
    media = metadata.get('media_urls', [])
    require(isinstance(media, list) and len(media) <= 256, 'ARCHIVE_MEDIA_LIMIT')
    prefix = ARCHIVE + f'{pmcid}.{version}/'
    for stem in sorted(stems)[:4]:
        assets = [url for url in media if isinstance(url, str) and url.startswith(prefix)
                  and re.fullmatch(re.escape(stem) + r'\.(?:jpe?g|png|webp|tiff?)', url[len(prefix):].split('?', 1)[0], re.I)]
        if len(assets) != 1:
            registered.append({'status': 'UNAVAILABLE', 'reasonCode': 'PP1_PEDIGREE_ARCHIVE_IMAGE_NOT_UNIQUE'})
            continue
        filename = assets[0][len(prefix):].split('?', 1)[0]
        checksum = object_digest(assets[0], f'{pmcid}.{version}/{filename}')
        raw = transport.get(pmcid, 'pmcArchiveImage', version, filename)
        verify_object(raw, checksum)
        pin = digest(raw)
        output.mkdir(parents=True, exist_ok=True)
        original = output / (pin + '.bin')
        original.write_bytes(raw)
        record = register(library, source, original, {'status': 200, 'bytes': len(raw), 'sha256': pin,
                          'finalUrl': image_url(pmcid, version, filename), 'archiveXmlSha256': original_sha,
                          'archiveImageMd5': checksum, 'source': 'NCBI_PMC_OPEN_ACCESS_ARCHIVE'})
        registered.append({'status': 'REGISTERED', 'imageSha256': pin, 'figureId': parse(record.read_bytes())['figureId']})
    return registered
