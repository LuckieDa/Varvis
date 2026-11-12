"""Prepare start-loss facts from pinned local resources, without evidence scoring.

ERepo summary records are source leads, not independently qualified references.
GENCODE structures cannot establish functional transcript relevance. Those gaps
remain visible instead of being converted into absence or a lower evidence score.
"""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
import re
from urllib.parse import urlsplit, parse_qs

from assemble_materials import canonical, content_hash, parse, sha, child
from start_loss_material_contract import SCHEMA, validate, allele as validate_allele, background as validate_background
from start_loss_sequence import resolve, validate_model, genomic_position, require
from start_loss_native_sources import NativeSources, read_pin
from build_transcript_model import reverse_complement

MODES = {'Autosomal dominant inheritance': 'AUTOSOMAL_DOMINANT',
         'Autosomal recessive inheritance': 'AUTOSOMAL_RECESSIVE',
         'X-linked dominant inheritance': 'X_LINKED_DOMINANT', 'X-linked recessive inheritance': 'X_LINKED_RECESSIVE'}


def cite(source, path):
    return [{'sourceId': source, 'jsonPointer': path}]


def transcript_review(context, model, inventory):
    findings, issues = [], ['START_LOSS_FUNCTIONAL_TRANSCRIPT_SET_NOT_ESTABLISHED']
    selected_start = [genomic_position(model, i) for i in (1, 2, 3)]
    for index, row in enumerate(inventory['transcripts']):
        tx = row.get('transcript')
        try:
            other_context = deepcopy(context); other_context['background']['transcript'] = tx
            validate_model(row, other_context)
            starts = [genomic_position(row, i) for i in (1, 2, 3)]
            different = starts != selected_start
            # Outside exons proves no direct sequence edit, not absence of regulatory
            # effects. A positive "unaffected" assertion requires scientific evidence.
            unaffected = False if context['allele']['position'] in starts else None
            findings.append({'transcript': tx, 'functionallyRelevant': None, 'differentStart': different,
                             'targetUnaffected': unaffected, 'citations': cite('inventory', f'/transcripts/{index}')})
        except (ValueError, KeyError, TypeError):
            issues.append('START_LOSS_ALTERNATIVE_STRUCTURE_UNRESOLVED:' + str(tx))
    return {'status': 'UNRESOLVED', 'checkedTranscripts': [f['transcript'] for f in findings],
            'complete': False, 'findings': findings, 'issues': issues, 'citations': cite('inventory', '/identity')}


def reference_candidates(context, model, sequence, document, source_id, source_url):
    url = urlsplit(source_url); query = parse_qs(url.query)
    require(url.scheme == 'https' and url.netloc in {'erepo.genome.network', 'erepo.clinicalgenome.org'}
            and url.path == '/evrepo/api/summary/classifications'
            and query.get('columns') == ['gene'] and query.get('matchTypes') == ['exact']
            and query.get('values') == [model['geneSymbol']], 'REFERENCE_SOURCE_SCOPE_INVALID')
    require(isinstance(document, dict), 'REFERENCE_SOURCE_SCHEMA_INVALID')
    rows = document.get('data')
    require(isinstance(rows, list) and len(rows) <= 1000 and all(isinstance(r, dict) for r in rows), 'REFERENCE_SOURCE_SCHEMA_INVALID')
    records, leads = [], []
    if sequence['status'] != 'AVAILABLE' or not sequence['candidateRestartCdsPosition']:
        return records, [{'reason': 'START_LOSS_REFERENCE_INTERVAL_UNRESOLVED', 'sourceId': source_id}]
    bg, target, cds = context['background'], context['allele'], sequence['referenceCds']
    seen = set()
    for index, row in enumerate(rows):
        citations = cite(source_id, f'/data/{index}')
        if row.get('gene') != model['geneSymbol']:
            leads.append({'reason': 'REFERENCE_GENE_MISMATCH', 'citations': citations}); continue
        representations = row.get('hgvs')
        if not isinstance(representations, list) or len(representations) > 1000:
            leads.append({'reason': 'REFERENCE_REPRESENTATIONS_UNRESOLVED', 'citations': citations}); continue
        pattern = re.escape(model['transcript']) + r':c\.([1-9][0-9]*)([ACGT])>([ACGT])'
        parsed = {tuple(m.groups()) for h in representations if isinstance(h, str) and (m := re.fullmatch(pattern, h))}
        if len(parsed) != 1:
            leads.append({'reason': 'REFERENCE_EXACT_TRANSCRIPT_SNV_NOT_UNIQUE', 'citations': citations}); continue
        pos, ref, alt = next(iter(parsed)); pos = int(pos)
        if pos > len(cds) or cds[pos-1] != ref or ref == alt:
            leads.append({'reason': 'REFERENCE_SEQUENCE_CONFLICT', 'citations': citations}); continue
        genome_ref, genome_alt = (ref, alt) if model['strand'] == 1 else (reverse_complement(ref), reverse_complement(alt))
        a = {'assembly': target['assembly'], 'contig': target['contig'], 'position': genomic_position(model, pos),
             'reference': genome_ref, 'alternate': genome_alt}
        classification = {'Pathogenic': 'PATHOGENIC', 'Likely Pathogenic': 'LIKELY_PATHOGENIC'}.get(row.get('classification'), 'OTHER')
        record_background = dict(bg, mondoId=row.get('mondoId'), moi=MODES.get(row.get('moi')))
        try:
            validate_background(record_background)
        except ValueError:
            leads.append({'reason': 'REFERENCE_BACKGROUND_UNRESOLVED', 'citations': citations}); continue
        eligibility, reason = 'UNRESOLVED', 'REFERENCE_CLASSIFICATION_INDEPENDENCE_NOT_ESTABLISHED'
        if a == target:
            eligibility, reason = 'EXCLUDED', 'REFERENCE_IS_TARGET'
        elif pos >= sequence['candidateRestartCdsPosition']:
            eligibility, reason = 'EXCLUDED', 'REFERENCE_OUTSIDE_UPSTREAM_INTERVAL'
        elif row.get('mondoId') != bg['mondoId'] or MODES.get(row.get('moi')) != bg['moi']:
            eligibility, reason = 'EXCLUDED', 'REFERENCE_BACKGROUND_MISMATCH'
        elif row.get('retracted') is not False:
            eligibility, reason = 'UNRESOLVED', 'REFERENCE_RETRACTION_STATUS_UNRESOLVED'
        elif classification != 'PATHOGENIC':
            eligibility, reason = 'EXCLUDED', 'REFERENCE_BASELINE_PATHOGENIC_REQUIRED'
        else:
            try:
                for key in ('approvedDate', 'publishedDate'):
                    datetime.strptime(row[key], '%Y-%m-%d')
            except (ValueError, KeyError, TypeError):
                reason = 'REFERENCE_PUBLICATION_STATUS_UNRESOLVED'
        # Preserve competing source records; only identical source rows/alleles are duplicates.
        key = content_hash({'allele': a, 'original': row})
        if key in seen:
            leads.append({'reason': 'REFERENCE_DUPLICATE_SOURCE_RECORD', 'citations': citations}); continue
        seen.add(key)
        records.append({'allele': a, 'background': record_background, 'cdsStart': pos, 'cdsEnd': pos,
                        'classification': classification, 'eligibility': eligibility, 'reason': reason,
                        'citations': citations})
    return records, leads


def prepare(context, model, inventory, read_region, snapshots=(), annotation=None, data_class='PUBLIC_REFERENCE', reference_proof=None):
    """Pure fact assembly around caller-owned readers and already captured snapshots.

    snapshots contain sourceId/sourceUrl/raw or an explicit unavailable reason.
    The returned original documents must be retained with the candidate material.
    """
    validate_allele(context['allele']); validate_background(context['background'])
    require(data_class in {'PUBLIC_REFERENCE', 'SYNTHETIC'}, 'DATA_CLASS_INVALID')
    require(len(snapshots) <= 10, 'REFERENCE_SNAPSHOT_LIMIT')
    sequence = resolve(context, model, read_region, annotation)
    if reference_proof:
        sequence['referenceProof'] = reference_proof()
    now = datetime.now(timezone.utc).isoformat()
    sources, originals, rejected, leads = [], {}, {}, []

    def add(sid, url, raw, reason=None, source_class=None):
        require(sid not in originals and sid not in {s['sourceId'] for s in sources}, 'SOURCE_ID_COLLISION')
        descriptor = {'sourceId': sid, 'sourceUrl': url, 'capturedAt': now, 'dataClass': source_class or data_class,
                      'captureStatus': 'AVAILABLE' if raw is not None else 'UNAVAILABLE',
                      'sha256': sha(raw) if raw is not None else None, 'reason': reason}
        sources.append(descriptor)
        if raw is not None:
            originals[sid] = raw

    url = inventory['identity']['source']['url']
    add('inventory', url, canonical(inventory))
    add('sequence', url, canonical({'kind': 'DERIVED_TECHNICAL_FACTS_NOT_OFFICIAL_ASSERTIONS',
                                  'model': model, 'inventorySha256': sha(originals['inventory']), 'sequence': sequence}))
    ready = sequence['status'] == 'AVAILABLE'
    edit = {k: sequence[k] if ready else None for k in ('strand', 'cdsPosition', 'referenceCodon', 'alternateCodon', 'modelSha256')}
    edit.update(status='AVAILABLE' if ready else sequence['status'] if sequence['status'] in {'CONFLICT', 'UNSUPPORTED'} else 'UNRESOLVED',
                citations=cite('sequence', '/sequence'))
    restart = sequence.get('candidateRestartCdsPosition') if ready else None
    material = {'schemaVersion': SCHEMA, 'dataClass': data_class, 'runtimeEligible': False, 'context': deepcopy(context),
                'policy': {'status': 'UNRESOLVED', 'policyId': None, 'version': None, 'sha256': None, 'citations': []},
                'mechanism': {'status': 'UNRESOLVED', 'citations': []}, 'startEdit': edit,
                'transcriptReview': {'status': 'UNRESOLVED', 'checkedTranscripts': [], 'complete': False,
                                     'findings': [], 'issues': ['START_LOSS_SEQUENCE_UNRESOLVED'], 'citations': []},
                'restart': {'status': 'CANDIDATE_FOUND' if restart else 'NO_CANDIDATE' if ready else 'UNRESOLVED',
                            'cdsPosition': restart, 'citations': cite('sequence', '/sequence') if ready else []},
                'upstreamReview': {'status': 'INCOMPLETE', 'records': [], 'requiredSourceIds': [], 'queries': [],
                                   'issues': ['START_LOSS_REFERENCE_COVERAGE_NOT_ESTABLISHED']}, 'sources': sources}
    if ready:
        material['transcriptReview'] = transcript_review(context, model, inventory)
    for snapshot in snapshots:
        sid, source_url, raw = snapshot['sourceId'], snapshot['sourceUrl'], snapshot.get('raw')
        reason, document = snapshot.get('reason'), None
        if raw is not None:
            require(isinstance(raw, bytes) and len(raw) <= 4_000_000, 'REFERENCE_SNAPSHOT_SIZE_INVALID')
            try:
                document = parse(raw)
            except (ValueError, UnicodeError):
                # Retain rejected bytes separately: they cannot satisfy a JSON citation.
                rejected[sid] = raw
                leads.append({'sourceId': sid, 'reason': 'REFERENCE_SOURCE_JSON_INVALID', 'sha256': sha(raw)})
                reason, raw = 'REFERENCE_SOURCE_JSON_INVALID', None
        add(sid, source_url, raw, reason if raw is None else None, snapshot.get('dataClass'))
        upstream = material['upstreamReview']; upstream['requiredSourceIds'].append(sid)
        adopted = False
        if raw is None:
            upstream['issues'].append('START_LOSS_REFERENCE_SOURCE_UNAVAILABLE:' + sid)
        else:
            try:
                records, missing = reference_candidates(context, model, sequence, document, sid, source_url)
                upstream['records'].extend(records); leads.extend(missing)
                adopted = True
            except (ValueError, KeyError, TypeError) as error:
                upstream['issues'].append('START_LOSS_REFERENCE_SOURCE_NOT_ADOPTED:' + sid)
                leads.append({'sourceId': sid, 'reason': str(error)[:200]})
        if restart:
            upstream['queries'].append({'sourceId': sid, 'status': 'PARTIAL' if adopted else 'UNAVAILABLE',
                                       'cdsStart': 1, 'cdsEnd': restart-1,
                                       'citations': cite(sid, '/data') if adopted else []})
    check = validate(material, context, originals)
    return {'material': material, 'originals': originals, 'rejectedOriginals': rejected, 'preparation': sequence,
            'referenceLeads': leads, 'contractCheck': check, 'networkCalls': 0, 'modelCalls': 0}


def run(root, request, output):
    root, output = Path(root).resolve(strict=True), Path(output).resolve()
    require(output.is_relative_to(root / 'local') and not output.exists(), 'NEW_LOCAL_OUTPUT_REQUIRED')
    require(request['schemaVersion'] == 'pvs1-start-loss-preparation-request-v1', 'REQUEST_SCHEMA_INVALID')
    specs = request.get('referenceSnapshots', [])
    require(isinstance(specs, list) and len(specs) <= 10, 'REFERENCE_SNAPSHOT_LIMIT')
    context = request['context']
    native = NativeSources(root, request['identity'], {context['background']['hgncId']})
    model = native.model(context['background']['hgncId'], context['background']['transcript'])
    snapshots = []
    for spec in specs:
        try:
            raw = read_pin(root, spec, 4_000_000)
            snapshots.append({'sourceId': spec['sourceId'], 'sourceUrl': spec['sourceUrl'], 'raw': raw,
                              'dataClass': spec.get('dataClass', request['dataClass'])})
        except (ValueError, OSError):
            snapshots.append({'sourceId': spec['sourceId'], 'sourceUrl': spec['sourceUrl'], 'reason': 'PINNED_SOURCE_UNAVAILABLE'})
    result = prepare(context, model, native.inventory(context['background']['hgncId']), native.region,
                     snapshots, request.get('annotation'), request['dataClass'], native.reference_proof)
    output.mkdir(parents=True, exist_ok=False)
    files = []
    for index, (sid, raw) in enumerate(result.pop('originals').items()):
        name = f'source-{index:03}.json'; (output / name).write_bytes(raw)
        files.append({'sourceId': sid, 'file': name, 'sha256': sha(raw)})
    rejected_files = []
    for index, (sid, raw) in enumerate(result.pop('rejectedOriginals').items()):
        name = f'rejected-source-{index:03}.bin'; (output / name).write_bytes(raw)
        rejected_files.append({'sourceId': sid, 'file': name, 'sha256': sha(raw)})
    (output / 'material.json').write_bytes(canonical(result['material']))
    (output / 'preparation.json').write_bytes(canonical(result))
    (output / 'manifest.json').write_bytes(canonical({'schemaVersion': 'pvs1-start-loss-preparation-output-v1',
        'sources': files, 'rejectedSources': rejected_files, 'requestSha256': content_hash(request), 'materialSha256': content_hash(result['material']),
        'preparationSha256': content_hash(result), 'runtimeEligible': False, 'scientificEvaluationPerformed': False}))
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', required=True); p.add_argument('--request', required=True)
    p.add_argument('--request-sha256', required=True); p.add_argument('--output', required=True)
    args = p.parse_args()
    request = parse(read_pin(args.root, {'path': args.request, 'sha256': args.request_sha256}))
    result = run(args.root, request, args.output)
    print(result['contractCheck']['status'], result['preparation']['status'])
