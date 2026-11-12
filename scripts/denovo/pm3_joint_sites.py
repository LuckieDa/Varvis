"""Supply pinned, bounded public joint-VCF site slices to PM3."""
import base64
import re

from core_local_identity import pinned
from model_literature_contract import digest, parse

ROOT = 'https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/joint/'


def attach(project, configuration, observations):
    if not any(row.get('policySelection', {}).get('status') == 'SELECTED'
               and any(m.get('jointRarity') for m in row['policySelection']['matches'])
               for case in observations['cases'] for row in case.get('resolvedObservations', [])):
        return
    configurations = configuration.get('regions', [configuration])
    if not isinstance(configurations, list) or not 1 <= len(configurations) <= 16:
        raise ValueError('PM3_JOINT_SITE_REGIONS_INVALID')
    slices, issues = [], []
    for region in configurations:
        try:
            slices.append(site_slice(project, region))
        except (ValueError, OSError, KeyError, TypeError) as error:
            reason = str(error)
            issues.append(reason if re.fullmatch(r'PM3_[A-Z0-9_]+', reason) else 'PM3_PUBLISHED_JOINT_INDEXED_SOURCE_UNAVAILABLE')
    if issues:
        observations['jointPopulationIssues'] = sorted(set(issues))
    if 'regions' in configuration:
        observations['jointPopulationOriginals'] = slices
    elif slices:
        observations['jointPopulationOriginal'] = slices[0]


def site_slice(project, configuration):
    receipt_bytes = pinned(project, configuration['retrieval'], 65536)
    receipt = parse(receipt_bytes)
    raw = pinned(project, configuration['region'], 2097152)
    chrom = receipt['chromosome']
    positions = receipt['queriedPositions']
    url = ROOT + 'gnomad.joint.v4.1.sites.chr' + chrom + '.vcf.bgz'
    if (receipt['assembly'] != 'GRCh38' or receipt['dataset'] != 'gnomAD v4.1 joint sites'
            or not re.fullmatch(r'(?:[1-9]|1[0-9]|2[0-2])', chrom) or receipt['sourceUrl'] != url
            or not isinstance(positions, list) or not 1 <= len(positions) <= 32
            or any(type(p) is not int or p < receipt['indexedIntervalStart'] or p > receipt['indexedIntervalEnd'] for p in positions)
            or len(set(positions)) != len(positions) or receipt['sha256'] != digest(raw)):
        raise ValueError('PM3_JOINT_SITE_QUERY_BINDING_INVALID')
    ranges = receipt['ranges']
    if (not isinstance(ranges, list) or not 2 <= len(ranges) <= 64 or
            {r['uri'] for r in ranges} != {url, url + '.tbi'} or any(
                not re.fullmatch(r'bytes [0-9]+-[0-9]+/[0-9]+', r['contentRange'])
                or not re.fullmatch(r'[0-9]+', r['generation'])
                or not re.fullmatch(r'[a-f0-9]{64}', r['sha256']) for r in ranges)
            or any(len({r['generation'] for r in ranges if r['uri'] == uri}) != 1 for uri in (url, url + '.tbi'))):
        raise ValueError('PM3_JOINT_INDEXED_RANGES_INVALID')
    return {
        'source': {k: receipt[k] for k in ('assembly', 'dataset', 'sourceUrl', 'chromosome', 'queriedPositions',
            'indexedIntervalStart', 'indexedIntervalEnd', 'rowCount', 'retrievedAt', 'sha256')},
        'retrievalSha256': digest(receipt_bytes), 'vcf': base64.b64encode(raw).decode('ascii')}
