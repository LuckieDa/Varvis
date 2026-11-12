"""Bounded validation for the NCBI PMC Open Access archive."""
import hashlib
import re
from urllib.parse import urlencode
from xml.etree import ElementTree

ROOT = 'https://pmc-oa-opendata.s3.amazonaws.com/'
ARCHIVE = 's3://pmc-oa-opendata/'
S3 = '{http://s3.amazonaws.com/doc/2006-03-01/}'


def require(value, code):
    if not value:
        raise ValueError('PAPER_PMC_OPEN_DATA_' + code)


def listing_url(pmcid):
    return ROOT + '?' + urlencode({'list-type': 2, 'prefix': pmcid + '.', 'delimiter': '/', 'max-keys': 32})


def latest_version(raw, pmcid):
    try:
        result = ElementTree.fromstring(raw)
    except ElementTree.ParseError as error:
        raise ValueError('PAPER_PMC_OPEN_DATA_LIST_INVALID') from error
    require(result.tag == S3 + 'ListBucketResult' and result.findtext(S3 + 'Name') == 'pmc-oa-opendata'
            and result.findtext(S3 + 'Prefix') == pmcid + '.'
            and result.findtext(S3 + 'IsTruncated') == 'false', 'LIST_INVALID_OR_INCOMPLETE')
    versions = [re.fullmatch(re.escape(pmcid) + r'\.([1-9][0-9]{0,5})/', prefix.text or '')
                for prefix in result.findall(S3 + 'CommonPrefixes/' + S3 + 'Prefix')]
    require(versions and len(versions) <= 32 and all(versions), 'VERSION_NOT_AVAILABLE')
    return str(max(int(match[1]) for match in versions))


def xml_url(pmcid, version):
    require(isinstance(version, str) and re.fullmatch(r'[1-9][0-9]{0,5}', version), 'VERSION_INVALID')
    return ROOT + f'{pmcid}.{version}/{pmcid}.{version}.xml'


def verified_xml_url(url, pmcid):
    key = re.escape(pmcid) + r'\.([1-9][0-9]{0,5})'
    return re.fullmatch(re.escape(ROOT) + key + '/' + re.escape(pmcid) + r'\.\1\.xml', url) is not None


def image_url(pmcid, version, filename):
    require(isinstance(filename, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,199}\.(?:jpe?g|png|webp|tiff?)',
                                                       filename, re.I), 'IMAGE_NAME_INVALID')
    return xml_url(pmcid, version).rsplit('/', 1)[0] + '/' + filename


def verified_image_url(url, pmcid):
    match = re.fullmatch(re.escape(ROOT + pmcid) + r'\.([1-9][0-9]{0,5})/([^/]+)', url)
    return match is not None and image_url(pmcid, match[1], match[2]) == url


def xml_digest(metadata, pmcid, version, candidate):
    require(metadata.get('pmcid') == pmcid and type(metadata.get('version')) is int
            and str(metadata['version']) == version and metadata.get('is_pmc_openaccess') is True
            and metadata.get('is_retracted') is False, 'METADATA_NOT_QUALIFIED')
    for key in ('pmid', 'doi'):
        if candidate.get(key):
            require(str(metadata.get(key, '')).lower() == str(candidate[key]).lower(), 'IDENTITY_MISMATCH')
    path = f'{pmcid}.{version}/{pmcid}.{version}.xml'
    return object_digest(metadata.get('xml_url'), path)


def object_digest(url, path):
    match = re.fullmatch(re.escape(ARCHIVE + path) + r'\?md5=([0-9a-f]{32})', url or '')
    require(match, 'OBJECT_METADATA_INVALID')
    return match[1]


def verify_object(raw, pin):
    require(hashlib.md5(raw, usedforsecurity=False).hexdigest() == pin, 'OBJECT_CHECKSUM_MISMATCH')
