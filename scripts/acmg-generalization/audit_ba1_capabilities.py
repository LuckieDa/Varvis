"""Offline inventory of frozen BA1 catalogs; never publishes or interprets new grammar."""
import argparse
import json
from pathlib import Path

import import_cspec_catalog as compiler
from cspec_ba1_semantics import compile_frequency


def scope_key(entry):
    scope = entry['scope']
    return (entry['specificationId'], entry['version'], compiler.canonical({
        k: scope.get(k) for k in ('hgncIds', 'mondoIds', 'moi', 'transcripts', 'transcriptScope', 'sequenceScope')}).decode())


def aggregate(rows, field):
    groups = {}
    for row in rows:
        values = row[field] if isinstance(row[field], list) else [row[field]]
        for value in values:
            groups.setdefault(value, []).append(row)
    return {key: {'associatedEntryCount': len({r['entryId'] for r in items}),
                  'specificationCount': len({r['specificationId'] for r in items}),
                  'occurrenceCount': len(items)} for key, items in sorted(groups.items())}


def audit(catalog_path, manifest_path, publication, verification=None):
    snapshot = compiler.strict_json(catalog_path.read_bytes())
    metadata = snapshot['metadata']
    compiler.require(publication in ('ACTIVE', 'CANDIDATE_PENDING_REVIEW'), 'PUBLICATION_LABEL_INVALID')
    compiler.require(metadata['batchReview']['status'] == ('REVIEWED' if publication == 'ACTIVE' else 'PENDING'),
                     'PUBLICATION_REVIEW_STATUS_MISMATCH')
    source_sha = compiler.file_sha(manifest_path)
    compiler.require(source_sha == metadata['captureManifestSha256'], 'CATALOG_MANIFEST_MISMATCH')
    # Full existing source/page/scope/semantic validators, without recapture or publication.
    replay = compiler.compile_snapshot(manifest_path.parent, source_sha, manifest_path.name,
                                       metadata.get('semanticReview'))
    originals = {scope_key(e): e for e in snapshot['entries']}
    fresh = {scope_key(e): e for e in replay['entries']}
    compiler.require(len(originals) == len(snapshot['entries']) and originals.keys() == fresh.keys(),
                     'FROZEN_SCOPE_REPLAY_MISMATCH')
    rows = []
    for key, entry in sorted(originals.items()):
        ba1, current = entry['ba1'], fresh[key]['ba1']
        reasons = [r for r in (ba1.get('reasonCode') if ba1['disposition'] == 'UNSUPPORTED' else None,
                              entry['scope'].get('reasonCode'),
                              'SCOPE_UNRESOLVED' if entry['scope']['status'] != 'RESOLVED' else None) if r]
        disposition = ba1['disposition']
        ready = disposition in ('THRESHOLD', 'GENERAL', 'DISABLED') and not reasons
        summary = {k: ba1.get(k) for k in ('metric', 'originalMetric', 'ancestries', 'value', 'comparator',
                                          'minimumAn', 'minimumAnComparator', 'conditions', 'migration')}
        if disposition == 'UNSUPPORTED':
            summary['conditions'] = None
        # Null is unknown, never an inferred absence of exceptions or default baseline.
        summary['unknownReasons'] = reasons
        summary['exceptionPolicy'] = 'EXISTING_REVIEWED_BASELINE_GATES' if ready else None
        clauses = ba1.get('clauses', [])
        standalone = [d for c in clauses for d in c.get('entContent', {}).get('strengthDescriptor', [])
                      if d.get('strength') == 'Stand Alone']
        clause_only = None
        declared = 'UNKNOWN'
        if len(standalone) == 1:
            descriptor = standalone[0]
            if descriptor.get('applicability') in ('Not Applicable', 'Not applicable', 'Not Applicable for this VCEP'):
                declared = 'DECLARED_NOT_APPLICABLE_GLOBAL_GATES_STILL_REQUIRED'
            text = descriptor.get('text')
            if isinstance(text, str):
                clause_only = compile_frequency(text, ba1['sourceRefs'][0], metadata.get('semanticReview'))
        rows.append({'entryId': entry['entryId'], 'specificationId': entry['specificationId'],
                     'version': entry['version'], 'scope': entry['scope'], 'scopeStatus': entry['scope']['status'],
                     'sourceManifestSha256': source_sha, 'catalogSha256': compiler.file_sha(catalog_path),
                     'compilerVersion': metadata['compilerVersion'], 'sourceSha256': entry['rawSha256'],
                     'rawRef': entry['rawRef'], 'jsonPointer': entry['source']['jsonPointer'],
                     'semanticFamily': ba1.get('semanticFamily', 'GENERAL_BASELINE' if disposition == 'GENERAL'
                                               else 'EXPLICIT_NOT_APPLICABLE' if disposition == 'DISABLED' else 'UNKNOWN'),
                     'compileStatus': disposition, 'originalReasonCode': ba1.get('reasonCode'),
                     'scientificDisposition': 'EXPLICIT_NOT_APPLICABLE' if disposition == 'DISABLED' else declared,
                     'clauseOnlyRecognition': clause_only,
                     'clauseOnlyBoundary': 'Scalar sentence only; does not consume global restrictions or establish full specification support.',
                     'reasonCodes': reasons, 'conditionSummary': summary,
                     'publicationStatus': publication, 'sourcePublicationStatus': entry['publicationStatus'],
                     'capabilityStatus': ('ACTIVE_SUPPORTED' if publication == 'ACTIVE' else 'REVIEW_PUBLICATION_PENDING')
                                         if ready else 'UNKNOWN_OR_UNSUPPORTED',
                     'currentCompilerReplay': {'compilerVersion': replay['metadata']['compilerVersion'],
                                               'disposition': current['disposition'], 'reasonCode': current.get('reasonCode'),
                                               'semanticFamily': current.get('semanticFamily'),
                                               'conditions': current.get('conditions'),
                                               'scope': fresh[key]['scope'],
                                               'equalsFrozen': ba1 == current},
                     'verificationRefs': [ref for ref in (verification or {}).get(entry['entryId'], [])
                                          if ref.get('sourceSha256') == entry['rawSha256']]})
    return {'schemaVersion': 'ba1-capability-audit-v1', 'publicationStatus': publication,
            'replayCompiler': {k: replay['metadata'][k] for k in ('compilerVersion', 'compilerSourceSha256',
                                                                'semanticCompilerSourceSha256')},
            'identity': {k: metadata.get(k) for k in ('compilerVersion', 'compilerSourceSha256',
                         'semanticCompilerSourceSha256', 'batchReview', 'captureManifestSha256')},
            'entryCount': len(rows), 'specificationCount': len({r['specificationId'] for r in rows}),
            'aggregates': {f: aggregate(rows, f) for f in ('semanticFamily', 'reasonCodes', 'scopeStatus',
                                                           'compileStatus', 'capabilityStatus', 'publicationStatus', 'scientificDisposition')},
            'entriesWithBlockers': sum(bool(r['reasonCodes']) for r in rows),
            'reasonOccurrenceCount': sum(len(r['reasonCodes']) for r in rows),
            'blockerBoundary': 'Compiler first-stop reason plus independent scope blockers; not an exhaustive interpretation of every unknown sentence.',
            'scopeBoundary': 'Frozen associated scopes only; no uncollected-gene denominator, clinical sensitivity or query reliability claim.',
            'generalBaseline': 'Separate existing general policy; never inferred from missing/unsupported clauses.',
            'rows': rows}


def compare(active, candidate):
    def key(r):
        return scope_key(r)
    left, right = ({key(r): r for r in report['rows']} for report in (active, candidate))
    matched = []
    for identity in sorted(left.keys() & right.keys()):
        a, b = left[identity], right[identity]
        matched.append({'activeEntryId': a['entryId'], 'candidateEntryId': b['entryId'],
                        'sourceChanged': a['sourceSha256'] != b['sourceSha256'],
                        'semanticChanged': any(a[f] != b[f] for f in ('compileStatus', 'semanticFamily'))
                                           or any(a['conditionSummary'][f] != b['conditionSummary'][f]
                                                  for f in ('metric', 'originalMetric', 'ancestries', 'value', 'comparator',
                                                            'minimumAn', 'minimumAnComparator', 'conditions')),
                        'blockerChanged': a['reasonCodes'] != b['reasonCodes'],
                        'publicationChanged': a['publicationStatus'] != b['publicationStatus']})
    return {'matched': matched, 'activeOnly': [left[k]['entryId'] for k in sorted(left.keys() - right.keys())],
            'candidateOnly': [right[k]['entryId'] for k in sorted(right.keys() - left.keys())],
            'matchedCount': len(matched), 'sourceChangedCount': sum(x['sourceChanged'] for x in matched),
            'semanticChangedCount': sum(x['semanticChanged'] for x in matched)}


def write_report(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as output:
        json.dump(value, output, ensure_ascii=False, indent=2, sort_keys=True)
        output.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('active', 'active-manifest', 'candidate', 'candidate-manifest', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--verification', type=Path)
    args = parser.parse_args()
    verification = json.loads(args.verification.read_bytes()) if args.verification else {}
    active = audit(args.active, args.active_manifest, 'ACTIVE', verification)
    candidate = audit(args.candidate, args.candidate_manifest, 'CANDIDATE_PENDING_REVIEW', verification)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, value in (('active', active), ('candidate', candidate), ('comparison', compare(active, candidate))):
        write_report(args.output / (name + '.json'), value)


if __name__ == '__main__':
    main()
