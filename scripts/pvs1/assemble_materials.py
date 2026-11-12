"""Offline PVS1 source/material assembly. Never grants adoption or writes runtime configuration.

plan joins pinned G2P and MANE records and retains existing policy candidates.
finish consumes the existing Java GENCODE reader's bounded batch, preserving exact versions.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import sys

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(SCRIPTS / 'e4_a1a'))
from prepare_binding_resources import MANE_HEADER, parse_mane, rows_with_lines
sys.path.insert(0, str(SCRIPTS / 'acmg-generalization'))
from bs1_expert_batch import content_hash
from prepare_g2p_mechanisms import canonical, MOI

VERSION = '1.0.0'


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'DUPLICATE_JSON_FIELD')
        result[key] = value
    return result


def parse(raw):
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda _: require(False, 'NONFINITE_JSON'))


def pinned(file, pin, limit=16_000_000):
    path = Path(file)
    before = path.stat()
    require(before.st_size <= limit and re.fullmatch('[0-9a-f]{64}', pin), 'INPUT_SIZE_OR_PIN_INVALID')
    with path.open('rb') as stream:
        raw = stream.read(limit + 1)
    after = path.stat()
    require((before.st_size, before.st_mtime_ns, before.st_ino) ==
            (after.st_size, after.st_mtime_ns, after.st_ino), 'INPUT_CHANGED_DURING_READ')
    require(len(raw) <= limit and sha(raw) == pin, 'INPUT_SHA_MISMATCH')
    return raw


def child(root, relative):
    require(isinstance(relative, str) and relative and not Path(relative).is_absolute()
            and ':' not in relative and '..' not in relative.replace('\\', '/').split('/'), 'RESOURCE_PATH_INVALID')
    base = Path(root).resolve()
    result = (base / relative).resolve()
    require(result.is_relative_to(base), 'RESOURCE_PATH_ESCAPE')
    return result


def pinned_location(item, root, relative, digest, *, allow_missing=False):
    """Locate pinned bytes without changing the original scientific record."""
    locations = item.get('fileLocations')
    if locations is not None and digest in locations['pathsBySha256']:
        return child(locations['root'], locations['pathsBySha256'][digest])
    if allow_missing and not relative:
        return None
    return child(root, relative)


def background(raw):
    hgnc = raw.get('hgnc id', '').strip()
    if re.fullmatch('[1-9][0-9]*', hgnc):
        hgnc = 'HGNC:' + hgnc
    mondo = raw.get('disease MONDO', '').strip()
    if re.fullmatch('HGNC:[1-9][0-9]*', hgnc) and re.fullmatch('MONDO:[0-9]{7}', mondo):
        return hgnc, mondo, raw.get('allelic requirement', '').strip()
    return None


def load_sources(config):
    ref = config['mechanisms']
    raw = pinned(ref['file'], ref['sha256'])
    facts = parse(raw)
    require(facts['schemaVersion'] == 'pvs1-mechanism-source-candidates-v1'
            and facts['methodVersion'] == '1.0.0' and facts['status'] == 'UNREVIEWED'
            and facts['runtimeEligible'] is False and facts['clinicalAcceptance'] is False,
            'MECHANISM_SOURCE_CONTRACT_INVALID')
    require(0 < len(facts['records']) <= 20000 and len(facts['records']) == facts['counts']['sourceRecords'], 'MECHANISM_COUNT_INVALID')
    groups, unresolved = defaultdict(list), []
    for i, record in enumerate(facts['records'], 1):
        original = record['sourceRecord']
        require(all(isinstance(v, str) for v in original.values()) and record['sourceRow'] == i
                and record['recordId'] == original['g2p id'].strip() + ':' + str(i)
                and record['status'] == 'UNREVIEWED' and record['runtimeEligible'] is False
                and sha(canonical(original)) == record['sourceRecordSha256'], 'MECHANISM_RECORD_INVALID')
        key = background(original)
        if key:
            groups[key].append(record)
        else:
            unresolved.append(record['recordId'])
    return facts, groups, unresolved


def mane_records(config):
    spec = config['mane']
    require(isinstance(spec['version'], str) and spec['version'].strip(), 'MANE_VERSION_REQUIRED')
    compressed = pinned(spec['file'], spec['sha256'], 8_000_000)
    with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
        raw = stream.read(16_000_001)
    require(len(raw) <= 16_000_000, 'MANE_DECOMPRESSED_SIZE_INVALID')
    _, counts = parse_mane(raw)  # Reuse existing header/identity/overlap validation.
    by_gene = defaultdict(list)
    for line, values in rows_with_lines(raw, '\t')[1:]:
        row = dict(zip(MANE_HEADER, values, strict=True))
        if row['HGNC_ID']:
            by_gene[row['HGNC_ID']].append({'sourceRow': line, 'sourceRecordSha256': sha(canonical(row)), 'sourceRecord': row})
    return by_gene, counts


def source_checks(sources, item):
    checks = []
    for source in sources:
        check = {'source': copy.deepcopy(source), 'status': 'NOT_LOCATED'}
        relative = source.get('relativePath') or item.get('sourceFiles', {}).get(source.get('sha256', ''))
        file = pinned_location(item, item['resourceRoot'], relative, source.get('sha256', ''), allow_missing=True)
        if file is not None and file.is_file():
            try:
                data = pinned(file, source['sha256'], 4_000_000)
                check.update(status='RAW_SHA_MATCH', bytes=len(data))
            except ValueError as error:
                check.update(status='INVALID', reasonCode=str(error))
        checks.append(check)
    return checks


def splice_resources(doc, item):
    """Read explicit pinned outcome records; never infer an event or create its review."""
    resources = doc.get('spliceOutcomeResources', [])
    require(isinstance(resources, list) and len(resources) <= 1024, 'SPLICE_RESOURCE_LIMIT_INVALID')
    checks, materials, seen, total = [], [], set(), 0
    for ref in resources:
        require(isinstance(ref, dict) and set(ref) == {'relativePath', 'sha256', 'canonicalSha256'}
                and all(isinstance(ref[k], str) and re.fullmatch('[0-9a-f]{64}', ref[k])
                        for k in ['sha256', 'canonicalSha256']), 'SPLICE_RESOURCE_BINDING_INVALID')
        require(ref['canonicalSha256'] not in seen, 'SPLICE_RESOURCE_DUPLICATE')
        seen.add(ref['canonicalSha256'])
        file = pinned_location(item, item['resourceRoot'], ref['relativePath'], ref['sha256'])
        check = {'binding': copy.deepcopy(ref), 'status': 'NOT_LOCATED'}
        if file.is_file():
            total += file.stat().st_size
            require(total <= 16_000_000, 'SPLICE_RESOURCE_TOTAL_SIZE_EXCEEDED')
            try:
                raw = pinned(file, ref['sha256'], 2_000_000)
                outcome = parse(raw)
                require(isinstance(outcome, dict) and outcome.get('schemaVersion') == 'pvs1-splice-outcome-v1'
                        and isinstance(outcome.get('sourceRecords'), list), 'SPLICE_RESOURCE_SCHEMA_INVALID')
                require(content_hash(outcome) == ref['canonicalSha256'], 'SPLICE_RESOURCE_CANONICAL_SHA_MISMATCH')
                materials.append({'outcome': outcome, 'rawSha256': ref['sha256'],
                                  'canonicalSha256': ref['canonicalSha256'],
                                  'sourceChecks': source_checks(outcome['sourceRecords'], item)})
                check.update(status='RAW_SHA_MATCH', bytes=len(raw))
            except ValueError as error:
                check.update(status='INVALID', reasonCode=str(error))
        checks.append(check)
    return checks, materials


def same_splice_scope(policy, outcome):
    scope = policy.get('scope', {})
    return (all(outcome.get(k) == scope.get(k) for k in ['hgncId', 'mondoId', 'moi'])
            and outcome.get('allele', {}).get('assembly') == scope.get('assembly')
            and outcome.get('transcript') in [t['accession'] + '.' + str(t['version'])
                                             for t in scope.get('transcripts', [])])


def domain_review_issues(policy):
    """Do not inherit a review of NMD into a separately reviewed escape/splice domain."""
    schema = policy.get('schemaVersion')
    reasons = []
    if (schema == 'pvs1-truncating-nmd-policy-v3'
            or schema == 'pvs1-truncating-nmd-policy-v4' and 'escapeScope' in policy) and (
            policy.get('escapeScope', {}).get('status') != 'REVIEWED_FOR_PVS1_ESCAPE'
            or not policy.get('escapeScope', {}).get('reviewId')):
        reasons.append('POLICY_ESCAPE_DOMAIN_REVIEW_INCOMPLETE')
    if schema == 'pvs1-truncating-nmd-policy-v4' and (
            policy.get('spliceScope', {}).get('status') != 'REVIEWED_FOR_PVS1_SPLICE'
            or not policy.get('spliceScope', {}).get('reviewId')):
        reasons.append('POLICY_SPLICE_DOMAIN_REVIEW_INCOMPLETE')
    return reasons


def historical_materials(config):
    result = []
    for item in config.get('candidates', []):
        doc = parse(pinned(item['file'], item['sha256'], 4_000_000))
        require(doc['schemaVersion'] == 'pvs1-truncating-nmd-bundle-candidate-v1', 'HISTORICAL_CANDIDATE_SCHEMA_INVALID')
        require(content_hash(doc['catalog']) == doc['catalogSha256'], 'HISTORICAL_CATALOG_HASH_MISMATCH')
        splice_checks, outcomes = splice_resources(doc, item)
        review_checks = []
        for binding in doc.get('sourceReviewBindings', []):
            check = {'binding': copy.deepcopy(binding), 'status': 'NOT_LOCATED'}
            file = pinned_location(item, Path(item['file']).parent, binding['file'], binding['sha256'])
            if file.is_file():
                try:
                    data = pinned(file, binding['sha256'], 4_000_000)
                    check.update(status='RAW_SHA_MATCH', bytes=len(data))
                except ValueError as error:
                    check.update(status='INVALID', reasonCode=str(error))
            review_checks.append(check)
        for policy in doc['policies']:
            reasons = ['HISTORICAL_SOURCE_REVIEW_NOT_CURRENT_RUNTIME_ADOPTION']
            require(policy['schemaVersion'] in {'pvs1-truncating-nmd-policy-v1',
                    'pvs1-truncating-nmd-policy-v2', 'pvs1-truncating-nmd-policy-v3',
                    'pvs1-truncating-nmd-policy-v4'}, 'POLICY_SCHEMA_INVALID')
            sources = source_checks(policy.get('scientificSources', []), item)
            if any(s['status'] != 'RAW_SHA_MATCH' for s in sources) or not sources:
                reasons.append('POLICY_SOURCE_BYTES_NOT_ALL_VERIFIED')
            model = None
            matches = [m for m in doc.get('transcriptModelResources', [])
                       if m.get('canonicalSha256') == policy.get('transcriptModelBinding', {}).get('canonicalSha256')]
            if len(matches) == 1:
                file = child(item['resourceRoot'], matches[0]['relativePath'])
                if file.is_file() and file.stat().st_size <= 2_000_000:
                    before = file.stat()
                    with file.open('rb') as stream:
                        data = stream.read(2_000_001)
                    after = file.stat()
                    require(len(data) <= 2_000_000 and (before.st_size, before.st_mtime_ns, before.st_ino) ==
                            (after.st_size, after.st_mtime_ns, after.st_ino), 'MODEL_CHANGED_DURING_READ')
                    candidate = parse(data)
                    if content_hash(candidate) == matches[0]['canonicalSha256']:
                        model = {'model': candidate, 'rawSha256': sha(data), 'canonicalSha256': content_hash(candidate)}
            if model is None:
                reasons.append('POLICY_MODEL_BYTES_NOT_VERIFIED')
            if policy.get('review', {}).get('status') != 'REVIEWED_IMPLEMENTED':
                reasons.append('POLICY_SOURCE_REVIEW_INCOMPLETE')
            reasons.extend(domain_review_issues(policy))
            if any(r['status'] != 'RAW_SHA_MATCH' for r in review_checks):
                reasons.append('POLICY_SOURCE_REVIEW_BYTES_NOT_ALL_VERIFIED')
            result.append({'materialId': 'pvs1-material-' + content_hash(policy), 'policy': copy.deepcopy(policy),
                           'policySha256': content_hash(policy), 'candidateBundleRawSha256': item['sha256'],
                           'catalog': copy.deepcopy(doc['catalog']), 'catalogSha256': doc['catalogSha256'],
                           'activation': copy.deepcopy(doc['activation']),
                           'runtimeResourceBinding': copy.deepcopy(doc['runtimeResourceBinding']),
                           'originalCandidate': copy.deepcopy(doc), 'sourceReviewChecks': review_checks,
                           'modelMaterial': model, 'sourceChecks': sources, 'reasonCodes': reasons,
                           'runtimeEligible': False})
            if policy['schemaVersion'] == 'pvs1-truncating-nmd-policy-v4':
                result[-1]['spliceResourceChecks'] = copy.deepcopy(splice_checks)
                result[-1]['spliceMaterials'] = copy.deepcopy([o for o in outcomes if same_splice_scope(policy, o['outcome'])])
    require(len({m['materialId'] for m in result}) == len(result), 'DUPLICATE_POLICY_MATERIAL')
    return result


def plan(config):
    require(config.get('schemaVersion') == 'pvs1-material-plan-input-v1'
            and set(config) <= {'schemaVersion', 'mechanisms', 'mane', 'candidates'}, 'PLAN_CONFIG_INVALID')
    facts, groups, unresolved = load_sources(config)
    mane, mane_counts = mane_records(config)
    materials = historical_materials(config)
    requests, identities, backgrounds = [], {}, []
    def add_context(gene, symbol, tx, contig, origin):
        if not re.fullmatch('ENST[0-9]+\.[1-9][0-9]*', tx) or not contig:
            return None
        accession, version = tx.rsplit('.', 1)
        context = {'gene': {'hgncId': gene, 'symbol': symbol}, 'transcript': {'accession': accession, 'version': int(version)},
                   'variant': {'evidenceQueryAllele': {'assembly': 'GRCh38', 'contig': contig}}}
        key = content_hash(context)
        if key not in identities:
            item = {'requestId': key, 'context': context, 'origins': []}
            identities[key] = item
            requests.append(item)
        identities[key]['origins'].append(origin)
        return key
    for (hgnc, mondo, allelic), records in sorted(groups.items()):
        labels = sorted({r['sourceRecord']['molecular mechanism'].strip() for r in records})
        moi = MOI.get(allelic)
        reasons = []
        if not moi:
            reasons.append('ALLELIC_REQUIREMENT_MAPPING_NOT_IMPLEMENTED')
        if len(labels) != 1:
            reasons.append('MECHANISM_SOURCE_CONFLICT')
        elif labels != ['loss of function']:
            reasons.append('LOSS_OF_FUNCTION_NOT_ESTABLISHED_BY_SOURCE')
        if any(r['sourceRecord']['confidence'].strip() not in {'strong', 'definitive'} for r in records):
            reasons.append('SOURCE_ASSOCIATION_REQUIRES_ASSESSMENT')
        txs = []
        for entry in mane.get(hgnc, []):
            row = entry['sourceRecord']
            match = re.fullmatch(r'NC_0*([1-9][0-9]*)\.[1-9][0-9]*', row['GRCh38_chr'])
            chromosome = int(match[1]) if match else 0
            contig = str(chromosome) if 1 <= chromosome <= 22 else {23:'X', 24:'Y'}.get(chromosome)
            rid = add_context(hgnc, row['symbol'], row['Ensembl_nuc'], contig,
                              {'source': 'MANE', 'sourceRow': entry['sourceRow']})
            txs.append({'maneRecord': entry, 'structureRequestId': rid, 'clinicalRelevance': 'NOT_ESTABLISHED_BY_MANE_ALONE'})
        if not txs:
            reasons.append('MANE_EXACT_GENE_RECORD_NOT_FOUND')
        matched = [m for m in materials if (m['policy']['scope'].get('hgncId'), m['policy']['scope'].get('mondoId'),
                    m['policy']['scope'].get('moi')) == (hgnc, mondo, moi)] if moi else []
        if not matched:
            reasons.append('DISEASE_TRANSCRIPT_EXON_APPLICABILITY_NOT_PROVIDED')
        backgrounds.append({'hgncId': hgnc, 'mondoId': mondo, 'allelicRequirement': allelic, 'moi': moi,
                            'sourceRecordIds': [r['recordId'] for r in records],
                            'sourceRecordSetSha256': content_hash(records), 'sourceMechanisms': labels,
                            'transcripts': txs, 'historicalMaterialIds': [m['materialId'] for m in matched],
                            'reasonCodes': reasons, 'runtimeEligible': False})
    require(len(requests) <= 10000, 'STRUCTURE_REQUEST_LIMIT_EXCEEDED')
    return {'schemaVersion': 'pvs1-material-plan-v1', 'methodVersion': VERSION, 'status': 'PREPARATION_ONLY',
            'runtimeEligible': False, 'clinicalAcceptance': False,
            'sources': {'mechanisms': {'rawSha256': config['mechanisms']['sha256'], 'source': facts['source']},
                        'mane': {'rawSha256': config['mane']['sha256'], 'version': config['mane']['version'], 'counts': mane_counts}},
            'sourceRecords': facts['records'], 'unresolvedSourceRecordIds': unresolved,
            'backgrounds': backgrounds, 'structureRequests': requests, 'historicalMaterials': materials,
            'counts': {'sourceRecords':len(facts['records']), 'backgrounds':len(backgrounds),
                       'structureRequests':len(requests), 'historicalPolicies':len(materials), 'adoptedBackgrounds':0}}


def finish(preparation, preparation_sha, structure):
    require(preparation.get('schemaVersion') == 'pvs1-material-plan-v1'
            and preparation.get('methodVersion') == VERSION
            and preparation.get('runtimeEligible') is False and preparation.get('clinicalAcceptance') is False
            and structure.get('schemaVersion') == 'pvs1-structure-batch-v1'
            and structure.get('methodVersion') == VERSION and structure.get('coreExecutions') == 0
            and structure.get('planRawSha256') == preparation_sha
            and structure.get('runtimeEligible') is False and structure.get('clinicalAcceptance') is False,
            'STRUCTURE_BATCH_BINDING_INVALID')
    requests = {r['requestId']:r for r in preparation['structureRequests']}
    actual = {r['requestId']:r for r in structure['results']}
    require(len(requests) == len(preparation['structureRequests'])
            and len(actual) == len(structure['results']) and set(actual) == set(requests), 'STRUCTURE_BATCH_INCOMPLETE_OR_DUPLICATE')
    for key, result in actual.items():
        require(result['context'] == requests[key]['context'], 'STRUCTURE_CONTEXT_MISMATCH')
        if result['status'] == 'AVAILABLE':
            row = result['annotation']
            context = result['context']
            require(row['hgncId'] == context['gene']['hgncId'] and row['geneSymbol'] == context['gene']['symbol']
                    and row['transcript'] == context['transcript']['accession'] + '.' + str(context['transcript']['version'])
                    and row['assembly'] == context['variant']['evidenceQueryAllele']['assembly']
                    and row['contig'] == context['variant']['evidenceQueryAllele']['contig']
                    and content_hash(row) == result['annotationCanonicalSha256'], 'STRUCTURE_ANNOTATION_MISMATCH')
        else:
            require(result['status'] == 'UNAVAILABLE' and isinstance(result.get('reasonCode'), str), 'STRUCTURE_STATUS_INVALID')
    expected_materials = {m['materialId'] for m in preparation['historicalMaterials']}
    checks = structure.get('historicalChecks', [])
    require(len(checks) == len(expected_materials) and {c['materialId'] for c in checks} == expected_materials
            and all(c.get('runtimeEligible') is False for c in checks), 'HISTORICAL_CHECKS_INCOMPLETE')
    output = copy.deepcopy(preparation)
    output['schemaVersion'] = 'pvs1-material-assembly-v1'
    output['planRawSha256'] = preparation_sha
    output['structureBatch'] = structure
    counts = Counter()
    for item in output['backgrounds']:
        statuses = [actual[t['structureRequestId']]['status'] for t in item['transcripts'] if t['structureRequestId'] in actual]
        item['structuralAnnotationAvailable'] = 'AVAILABLE' in statuses
        if not item['structuralAnnotationAvailable']:
            item['reasonCodes'].append('EXACT_VERSION_STRUCTURAL_ANNOTATION_UNAVAILABLE')
        # No structure-only result establishes exon relevance, sequence effect or NMD.
        item['reasonCodes'].append('CURRENT_PVS1_RUNTIME_APPLICABILITY_NOT_ADOPTED')
        for reason in set(item['reasonCodes']):
            counts[reason] += 1
    output['counts'].update(structuralBackgrounds=sum(b['structuralAnnotationAvailable'] for b in output['backgrounds']),
                            structureOutcomes=dict(Counter(r['status'] for r in structure['results'])),
                            backgroundGapCounts=dict(sorted(counts.items())), adoptedBackgrounds=0, coreExecutions=0)
    return output


def write_new(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    start = sub.add_parser('plan'); start.add_argument('--config', required=True); start.add_argument('--config-sha256', required=True)
    end = sub.add_parser('finish'); end.add_argument('--plan', required=True); end.add_argument('--plan-sha256', required=True)
    end.add_argument('--structure', required=True); end.add_argument('--structure-sha256', required=True)
    for p in (start, end):
        p.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.command == 'plan':
        result = plan(parse(pinned(args.config, args.config_sha256, 1_000_000)))
    else:
        result = finish(parse(pinned(args.plan, args.plan_sha256, 64_000_000)), args.plan_sha256,
                        parse(pinned(args.structure, args.structure_sha256, 32_000_000)))
    write_new(args.output, result)
    print(json.dumps(result['counts'], ensure_ascii=False, sort_keys=True))


if __name__ == '__main__':
    main()
