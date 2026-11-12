"""Offline UniProt/InterPro region facts, bound to an exact full protein sequence.

This is an annotation adapter, not a PM4/BP3 classifier. Unknown annotations,
algorithm misses and absent function descriptions never grant negative evidence.
"""
import argparse
import json
from pathlib import Path
import re
from protein_repeat_method import save, sha
from decipher_repeat_method import require, validate_sequence

VERSION = '1.1.0'
FUNCTION_FEATURES = {'Domain', 'Region', 'Active site', 'Binding site', 'Site', 'Motif',
                     'DNA binding', 'Zinc finger', 'Transmembrane', 'Topological domain', 'Compositional bias'}


def pinned_json(base, spec, maximum=4_000_000):
    path = (base / spec['path']).resolve()
    require(path.is_relative_to(base.resolve()), 'SOURCE_PATH_OUTSIDE_BUNDLE')
    with path.open('rb') as stream: raw = stream.read(maximum + 1)
    require(len(raw) <= maximum and sha(raw) == spec['sha256'], 'SOURCE_BYTES_OR_PIN_MISMATCH')
    return json.loads(raw)


def interpro_payload(payload, receipt, url, release, minor):
    headers = {k.lower(): str(v) for k, v in receipt['headers'].items()}
    require(receipt.get('success') is True and receipt.get('status') == 200
            and receipt.get('url') == url and receipt.get('resolvedUrl') == url, 'INTERPRO_SOURCE_NOT_SUCCESSFUL')
    require(headers.get('interpro-version') == release
            and headers.get('interpro-version-minor') == minor, 'INTERPRO_RELEASE_MISMATCH')
    # Payload bytes are checked against both receipt and externally pinned manifest by load().
    return payload


def interval(start, end, length):
    require(type(start) is int and type(end) is int and 1 <= start <= end <= length,
            'REGION_COORDINATE_INVALID')
    return dict(start=start, end=end)


def combine(accession, sequence, uniprot, interpro_protein, entries, source_pins):
    """Pure semantic adapter. Call load() for the acquired byte/release/receipt gate."""
    validate_sequence(accession, sequence)
    meta = interpro_protein['metadata']
    require(uniprot['primaryAccession'] == accession and meta['accession'] == accession,
            'PROTEIN_ACCESSION_MISMATCH')
    require(uniprot['organism']['taxonId'] == 9606 and str(meta['source_organism']['taxId']) == '9606'
            and meta.get('is_fragment') is False and meta.get('source_database') == 'reviewed',
            'HUMAN_FULL_REVIEWED_PROTEIN_REQUIRED')
    require(uniprot['sequence']['value'] == sequence == meta['sequence']
            and uniprot['sequence']['length'] == len(sequence) == meta['length'], 'FULL_SEQUENCE_MISMATCH')
    require('next' in entries and 'previous' in entries and type(entries.get('results')) is list
            and entries['next'] is None and entries['previous'] is None
            and type(entries.get('count')) is int and entries['count'] == len(entries['results']) <= 200,
            'INTERPRO_ENTRIES_INCOMPLETE')
    require(type(uniprot.get('features')) is list, 'UNIPROT_FEATURES_INCOMPLETE')
    regions, unplaced, context = [], [], []
    for i, feature in enumerate(uniprot['features']):
        kind = feature.get('type')
        if kind not in FUNCTION_FEATURES | {'Repeat'}: continue
        pointer = f'/features/{i}'
        location = feature['location']
        if location.get('sequence') or any(location.get(k, {}).get('modifier') != 'EXACT' for k in ('start', 'end')):
            unplaced.append(dict(source='UniProt', locator=pointer, reason='ISOFORM_OR_IMPRECISE_LOCATION', raw=feature))
            continue
        span = interval(location['start']['value'], location['end']['value'], len(sequence))
        regions.append(dict(source='UniProt', locator=pointer, category='REPEAT' if kind == 'Repeat' else 'FUNCTION_LEAD',
                            kind=kind, fragments=[span], raw=feature))
    seen = set()
    for i, entry in enumerate(entries['results']):
        em = entry['metadata']; entry_id = em['accession']; kind = em['type']
        require(re.fullmatch(r'IPR[0-9]{6}', entry_id) and entry_id not in seen
                and em.get('source_database') == 'interpro', 'INTERPRO_ENTRY_IDENTITY_INVALID')
        seen.add(entry_id)
        require(len(entry['proteins']) == 1, 'INTERPRO_PROTEIN_CARDINALITY')
        protein = entry['proteins'][0]
        require(protein['accession'].upper() == accession and protein['protein_length'] == len(sequence)
                and str(protein['organism']) == '9606', 'INTERPRO_LOCATION_PROTEIN_MISMATCH')
        pointer = f'/results/{i}'
        for j, location in enumerate(protein['entry_protein_locations']):
            fragments = []
            require(location['fragments'], 'EMPTY_INTERPRO_LOCATION')
            for fragment in location['fragments']:
                span = interval(fragment['start'], fragment['end'], len(sequence))
                span['discontinuity'] = fragment['dc-status']
                require(span['discontinuity'] in ('CONTINUOUS', 'N_TERMINAL_DISC', 'C_TERMINAL_DISC', 'NC_TERMINAL_DISC'),
                        'UNKNOWN_INTERPRO_DISCONTINUITY')
                fragments.append(span)
            # Family/superfamily descriptions and GO terms have no asserted local
            # functional boundary. Keep them as context, never local function approval.
            category = 'REPEAT' if kind == 'repeat' else 'DOMAIN_LEAD' if kind == 'domain' else 'PROTEIN_CONTEXT'
            regions.append(dict(source='InterPro', entry=entry_id, category=category, kind=kind, fragments=fragments,
                                locator=pointer+f'/proteins/0/entry_protein_locations/{j}', metadata=em, raw=location))
        context.append(dict(source='InterPro', entry=entry_id, locator=pointer+'/metadata', raw=em,
                            scope='ENTRY_ASSOCIATION_NOT_LOCAL_FUNCTION_PROOF'))
    return dict(schemaVersion='combined-protein-region-facts-v1', adapterVersion=VERSION, accession=accession,
                sequenceSha256=sha(sequence.encode()), length=len(sequence), coordinateSystem='ONE_BASED_CLOSED',
                sources=source_pins, regions=regions, unplaced=unplaced, context=context,
                sourceCompleteness=dict(uniprotFeaturesComplete=True, interproEntriesComplete=True,
                    exactFullSequenceMatch=True, uniprotFeatureCount=len(uniprot['features']), interproEntryCount=entries['count']),
                reviewStatus='UNREVIEWED', clinicalAdoption=False, nonRepeatQualified=False,
                noKnownFunctionQualified=False, sourceScope='UNIPROT_FEATURES_AND_INTEGRATED_INTERPRO_ENTRIES_ONLY')


def load(base, spec):
    """Manifest pins must come from the caller's frozen acquisition, not this output."""
    sources = spec['sources']
    bodies = {k: pinned_json(base, v) for k, v in sources.items()}
    release, minor, uniprot_release = spec['interproRelease'], spec['interproMinor'], spec['uniprotRelease']
    accession = spec['accession']
    urls = dict(root='https://www.ebi.ac.uk/interpro/api/',
                protein=f'https://www.ebi.ac.uk/interpro/api/protein/uniprot/{accession}/',
                entries=f'https://www.ebi.ac.uk/interpro/api/entry/interpro/protein/uniprot/{accession}/?page_size=200')
    for key, url in urls.items():
        receipt = bodies[key+'Receipt']
        require(receipt['sha256'] == sources[key]['sha256'], 'RECEIPT_BODY_PIN_MISMATCH')
        interpro_payload(bodies[key], receipt, url, release, minor)
    require(bodies['root']['databases']['uniprot']['version'] == uniprot_release
            and bodies['root']['databases']['interpro']['version'] == release, 'ROOT_RELEASE_MISMATCH')
    acquisition = bodies['uniprotAcquisition']
    matches = [r for r in acquisition if r['accession'] == accession]
    require(len(matches) == 1, 'UNIPROT_ACQUISITION_AMBIGUOUS')
    receipt = matches[0]
    headers = {k.strip().lower(): v.strip() for line in receipt['headers'].splitlines()
               if ':' in line for k, v in [line.split(':', 1)]}
    require(receipt.get('success') is True and receipt.get('status') == 200
            and receipt.get('url') == f'https://rest.uniprot.org/uniprotkb/{accession}.json'
            and receipt['sha256'] == sources['uniprot']['sha256']
            and headers.get('x-uniprot-release') == uniprot_release, 'UNIPROT_SOURCE_OR_RELEASE_MISMATCH')
    return combine(accession, spec['sequence'], bodies['uniprot'], bodies['protein'], bodies['entries'],
                   dict(pins=sources, interproRelease=release, interproMinor=minor, uniprotRelease=uniprot_release))


def query(facts, sequence_sha, start, end, observations=()):
    require(facts['sequenceSha256'] == sequence_sha and facts['schemaVersion'] == 'combined-protein-region-facts-v1',
            'QUERY_PROTEIN_IDENTITY_MISMATCH')
    interval(start, end, facts['length'])
    hits = [r for r in facts['regions'] if any(f['start'] <= end and start <= f['end'] for f in r['fragments'])]
    native = [r for r in hits if r['category'] == 'REPEAT']
    predictions, reasons = [], []
    for observation in observations:
        require(observation['sequenceSha256'] == sequence_sha, 'METHOD_SEQUENCE_MISMATCH')
        if observation['status'] != 'COMPLETED':
            reasons.append('METHOD_' + observation['status']); continue
        parsed = observation['facts']
        require(parsed.get('sequenceSha256') == sequence_sha
                and parsed.get('schemaVersion') == 'decipher-repeat-observation-v1'
                and parsed.get('upstreamVersion') == '3.8.1'
                and parsed.get('clinicalAdoption') is False and parsed.get('nonRepeatQualified') is False,
                'UNEXPECTED_METHOD_ADOPTION')
        predictions.extend(dict(method=parsed['method'], empirical=parsed['empirical'], group=g['number'], unit=u)
                           for g in parsed['groups'] for u in g['units'] if u['start'] <= end and start <= u['end'])
    if facts['unplaced']: reasons.append('UNMAPPED_SOURCE_FEATURES')
    reasons.extend(['NO_VALIDATED_NEGATIVE_REPEAT_POLICY', 'NO_REVIEWED_NO_KNOWN_FUNCTION_ASSESSMENT'])
    return dict(start=start, end=end, sequenceSha256=sequence_sha,
                repeatState='SOURCE_REPEAT_OVERLAP' if native else 'PREDICTED_REPEAT_CANDIDATE' if predictions else 'UNKNOWN',
                sourceRepeatHits=native, sourceOtherHits=[r for r in hits if r['category'] != 'REPEAT'],
                predictedRepeatHits=predictions, reasons=reasons, clinicalAdoption=False,
                nonRepeatQualified=False, noKnownFunctionQualified=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw = args.manifest.read_bytes()
    require(sha(raw) == args.manifest_sha256, 'INPUT_MANIFEST_PIN_MISMATCH')
    manifest = json.loads(raw)
    require(0 < len(manifest['proteins']) <= 50, 'PROTEIN_COUNT_LIMIT')
    require(len({p['accession'] for p in manifest['proteins']}) == len(manifest['proteins']), 'DUPLICATE_PROTEIN')
    results = []
    for spec in manifest['proteins']:
        try: results.append(dict(accession=spec['accession'], status='FACTS_READY', facts=load(args.bundle, spec)))
        except (KeyError, TypeError, ValueError, OSError) as error:
            results.append(dict(accession=spec['accession'], status='UNAVAILABLE', reason=str(error)))
    save(args.output, dict(manifestSha256=sha(raw), results=results, clinicalAdoption=False))
    print([(r['accession'], r['status'], r.get('reason')) for r in results])
