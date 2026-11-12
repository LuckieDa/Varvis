"""Stage 2 source review of a pinned Stage 1 package, without model/network calls.

Source-derived proofs and unresolved model claims remain separate. This produces
an offline factual ledger, never a clinical certificate or a scoring request.
"""
import argparse
from copy import deepcopy
from pathlib import Path
import re

from model_literature_contract import canonical, digest, parse
from core_material_contract import (bounded, facts, validate, context_binding,
                                    digest as object_digest, assess)
from core_material_extraction import destination
from core_source_review import collect, VERSION
from project_core_material import observed, missing
from prepare_literature import prepare

MAX_PACKAGE = 32 * 1024 * 1024


def require(condition, code):
    if not condition:
        raise ValueError('DENOVO_CORE_REVIEW_' + code)


def implementation():
    names = ('core_material_review.py', 'core_source_review.py', 'core_case_completion.py', 'core_material_extraction.py',
             'core_material_contract.py', 'core_material_schema.py', 'prepare_literature.py',
             'extract_literature_cases.py', 'literature_case_structure.py', 'qualify_literature_cases.py',
             'literature_allele_identity.py', 'project_core_material.py', 'supplement_case_review.py', 'single_case_scope.py')
    return {name: digest(Path(__file__).with_name(name).read_bytes()) for name in names}


def empty_ledger(doc):
    return {'schemaVersion': 'denovo-core-review-ledger-v1', 'scope': 'OFFLINE_CONTRACT_VALIDATION_ONLY',
            'contextBinding': context_binding(doc), 'checks': {}}


def safe_file(root, relative):
    require(isinstance(relative, str) and relative and not Path(relative).is_absolute()
            and '..' not in Path(relative).parts, 'PATH_INVALID')
    path = (root / relative).resolve(strict=True)
    require(path.is_relative_to(root), 'PATH_OUTSIDE_PACKAGE')
    return path


def read_input(source, package, expected_index_sha256):
    root = Path(package).resolve(strict=True)
    index_raw = bounded(root / 'index.json'); require(digest(index_raw) == expected_index_sha256, 'INDEX_PIN_MISMATCH')
    index = parse(index_raw)
    require(index['schemaVersion'] == 'denovo-core-extraction-package-v1' and
            index['pmcid'] == source['pmcid'] and index['bundleSha256'] == source['bundleSha256'], 'PACKAGE_IDENTITY')
    require(source['main'] == prepare(source['raw'], source['pmcid'], digest(source['raw'])), 'SOURCE_RECONSTRUCTION')
    files = {'index.json': index_raw}
    for name, key in [('source-facts.json', 'sourceFactsSha256'), ('observations.json', 'observationsSha256'),
                      ('source-scope.json', 'sourceScopeSha256')]:
        files[name] = bounded(root / name)
        require(digest(files[name]) == index[key], 'INPUT_PIN_MISMATCH')
    if index.get('originalIntakeSha256'):
        files['original-intake.json'] = bounded(root / 'original-intake.json')
        require(digest(files['original-intake.json']) == index['originalIntakeSha256'], 'INTAKE_PIN_MISMATCH')
    scope = parse(files['source-scope.json'])
    require(scope['pmcid'] == source['pmcid'] and scope['bundleSha256'] == source['bundleSha256'] and scope['sourceSha256'] == digest(source['raw'])
            and scope['coverage'] == source['coverage'] and scope['attachments'] == source['attachments'], 'SOURCE_SCOPE_CHANGED')
    native_view = parse(files['source-facts.json'])
    expected_units = [{'unitId': u['unitId'], 'sourceSha256': u['sourceSha256'], 'text': u['text'],
                       'metadata': {k: v for k, v in u.items() if k not in ('text', 'unitId', 'sourceSha256')}} for u in source['units']]
    unit_raw = bounded(root / 'source-units.json')
    require(parse(unit_raw) == expected_units, 'SOURCE_UNIT_RECONSTRUCTION')
    files['source-units.json'] = unit_raw
    require(native_view['pmcid'] == source['pmcid'] and native_view['sourceSha256'] == digest(source['raw']), 'SOURCE_FACT_IDENTITY')
    documents = []
    require(len(index['cases']) <= 128 and len({c['caseKey'] for c in index['cases']}) == len(index['cases']), 'CASE_LIMIT_OR_DUPLICATE')
    for item in index['cases']:
        raw = bounded(safe_file(root, item['path'])); require(digest(raw) == item['sha256'], 'CASE_PIN_MISMATCH')
        doc = parse(raw); require(doc['caseKey'] == item['caseKey'], 'CASE_IDENTITY')
        require(all(f['verification'] != 'VERIFIED' for f in facts(doc).values()), 'INPUT_ALREADY_VERIFIED')
        ledger = empty_ledger(doc); validate(doc, root, ledger, object_digest(ledger))
        require(doc['sources']['paper']['sha256'] == digest(source['raw']), 'PAPER_PIN_MISMATCH')
        for spec in doc['sources'].values():
            body = bounded(safe_file(root, spec['path']))
            if spec['path'] in files:
                require(files[spec['path']] == body, 'SHARED_SOURCE_CHANGED')
            files[spec['path']] = body
        files[item['path']] = raw; documents.append((item, doc))
    # No cases is still a valid partial extraction. It is not absence of cases.
    require(sum(map(len, files.values())) <= MAX_PACKAGE, 'PACKAGE_SIZE_LIMIT')
    claims = parse(files['observations.json']); require(len(claims) <= 128 * 256, 'CLAIM_LIMIT')
    units = {u['unitId']: u for u in source['units']}
    for claim in claims:
        ref = claim['rawField']['citation']; value = claim['rawField']['sourceValue']
        require(claim['caseKey'] in {c['caseKey'] for c in index['cases']}, 'CLAIM_CASE_UNKNOWN')
        require(ref['unitId'] in units and ref['sourceSha256'] == units[ref['unitId']]['sourceSha256']
                and ref['quote'] and ref['quote'] in units[ref['unitId']]['text'] and value and value in ref['quote'], 'CLAIM_SOURCE_INVALID')
        require(claim['subject'] == claim['rawField'].get('subject', 'PROBAND'), 'CLAIM_SUBJECT_CHANGED')
    return index, documents, claims, files


def review_case(source, document, observations):
    doc = deepcopy(document); label = doc['caseKey'].split('#', 1)[1]
    proofs, scope = collect(source, label, observations)
    # All preserved source paths point to exact, copied inputs; originals stay intact.
    for spec in doc['sources'].values():
        spec['path'] = 'inputs/' + spec['path']
    original = facts(document); result = facts(doc); checks, rows = {}, []
    contextual = {'/groups/variant/resolvedAllele', '/groups/testing/qualifiedForTarget',
                  '/groups/family/compatibility', '/groups/overlap/eventId', '/groups/overlap/independence'}
    for path, current in result.items():
        entries = proofs.get(path, [])
        row = {'path': path, 'before': deepcopy(original[path]), 'sourceProofs': entries,
               'status': 'NOT_EXTRACTED_RECHECK_INCONCLUSIVE', 'foundBySecondPass': False}
        if path in contextual or not path.startswith('/groups/'):
            row['status'] = 'CONTEXT_OR_EVENT_CHECK_DEFERRED'
        elif entries:
            main_ids = {u['unitId'] for u in source['main']['units']}
            unit_indices = {u['unitId']: i for i, u in enumerate(source['units'])}
            refs = [({'sourceId': 'paper', 'kind': 'JATS_UNIT', 'locator': r['unitId'], 'quote': r['quote']}
                     if r['unitId'] in main_ids else {'sourceId': 'units', 'kind': 'JSON_POINTER',
                         'locator': f'/{unit_indices[r["unitId"]]}/text', 'quote': r['quote']})
                    for e in entries for r in e['citations']]
            refs = list({canonical(r): r for r in refs}.values())
            values = list({canonical(e['value']): e['value'] for e in entries}.values())
            reported = [e for e in entries if e['availability'] == 'REPORTED']
            subjects = {e['subject'] for e in entries}
            conflict = bool(reported and len(reported) != len(entries)) or len(subjects) != 1
            if reported and all(isinstance(v, list) for v in values):
                value = list(dict.fromkeys(x for v in values for x in v))
            elif len(values) == 1:
                value = values[0]
            elif path == '/groups/testing/method':
                value = '; '.join(values)  # Different assay stages are not a contradiction.
            else:
                value = None; conflict = True
            if reported:
                f = observed(doc['caseKey'], entries[0]['subject'], value,
                             canonical(values).decode('utf-8'), refs, 'PROGRAM_DERIVED')
            else:
                f = missing(doc['caseKey'], entries[0]['subject'], 'NOT_REPORTED_IN_CHECKED_SCOPE')
                f['citations'] = refs
            f['checkedLocations'] = [{k: v for k, v in r.items() if k != 'quote'} for r in refs]
            f['verification'] = 'CONFLICT' if conflict else 'VERIFIED'
            if conflict:
                f['issues'] = ['SOURCE_VALUES_OR_REPORTING_STATUS_DISAGREE']
            if path == '/groups/testing/method' and len(values) > 1:
                f['issues'].append('MULTIPLE_REPORTED_ASSAYS_EXACT_TARGET_QUALIFICATION_DEFERRED')
            current.clear(); current.update(f)
            row['status'] = ('SOURCE_CONFLICT' if conflict else
                             'SOURCE_VERIFIED_REPORTED' if reported else 'NOT_REPORTED_IN_EXPLICIT_CHECKED_SCOPE')
            row['foundBySecondPass'] = original[path]['availability'] == 'NOT_EXTRACTED' and bool(reported)
            if not conflict:
                checks[path] = {'factSha256': object_digest(f), 'method': sorted({e['method'] for e in entries}),
                                'basis': refs, 'authority': 'PROGRAM_SOURCE_FACT_CHECK_ONLY'}
        elif current['availability'] == 'REPORTED':
            # An old projection's typed value is still only a proposal.
            row['status'] = 'REPORTED_CLAIM_REQUIRES_SEMANTIC_REVIEW'
        if path.startswith('/groups/paper/'):
            key = path.rsplit('/', 1)[1]
            value, sid, locator, raw_value = {
                'identity': (source['pmcid'], 'legacy-facts', '/pmcid', source['pmcid']),
                'availability': ('MAIN_AVAILABLE' if source['coverage'].get('status') == 'NO_EXPLICIT_SUPPLEMENT_SIGNAL'
                                 and not source['attachments'] else 'PARTIAL', 'scope', '/paperAvailability', None),
                'supplements': ([a['originalName'] for a in source['attachments']], 'scope', '/attachments',
                                canonical(source['attachments']).decode())}[key]
            raw_value = value if raw_value is None else raw_value
            refs = [{'sourceId': sid, 'kind': 'JSON_POINTER', 'locator': locator, 'quote': raw_value}]
            f = observed(doc['caseKey'], 'PAPER', value, raw_value, refs)
            f['verification'] = 'VERIFIED'
            current.clear(); current.update(f)
            checks[path] = {'factSha256': object_digest(f), 'method': 'PINNED_BUNDLE_METADATA_RECONSTRUCTION', 'basis': refs}
            row['status'] = 'SOURCE_VERIFIED_REPORTED'
        if scope['status'] == 'SCOPE_LIMIT_EXCEEDED' and path.startswith('/groups/'):
            if not path.startswith('/groups/paper/'):
                row['status'] = 'SOURCE_REVIEW_SCOPE_LIMIT'
        row['after'] = deepcopy(current); rows.append(row)
    # Matching positive and negative source claims are not resolved by picking
    # the last extraction. Timing/context may explain them, but is not inferred.
    positive = result['/groups/phenotype/positive']; negative = result['/groups/phenotype/negative']
    if positive['verification'] == negative['verification'] == 'VERIFIED':
        normalize = lambda s: re.sub(r'\s+', ' ', s).strip().casefold()
        conflicts = {normalize(n) for n in (negative['value'] or [])
                     if any(re.search(r'(?<!\w)' + re.escape(normalize(n)) + r'(?!\w)', normalize(p))
                            for p in (positive['value'] or []))}
        if conflicts:
            for name in ('positive', 'negative'):
                path = '/groups/phenotype/' + name
                result[path]['verification'] = 'CONFLICT'
                result[path]['issues'].append('POSITIVE_NEGATIVE_SOURCE_CLAIMS_REQUIRE_CONTEXT_RECONCILIATION')
                checks.pop(path, None)
                row = next(r for r in rows if r['path'] == path)
                row['after'] = deepcopy(result[path]); row['status'] = 'SOURCE_CONFLICT'
    claim_checks = []
    for observation in observations:
        f = observation['rawField']; routes = destination(f['field'], observation['subject'])
        paths = ['/groups/' + g + '/' + n for g, n, _ in routes]
        supported = []
        for path in paths:
            for proof in proofs.get(path, []):
                values = proof['value'] if isinstance(proof['value'], list) else [proof['value']]
                # Typed derived assertions need their own semantics; literal audit
                # only claims a match to a verified value, never quote-only approval.
                implicit_native = ('subject' not in f and 'NATIVE_OR_QUALIFIED_SOURCE_VIEW' in observation['origins'])
                if ((proof['subject'] == observation['subject'] or implicit_native) and f['sourceValue'] in values
                        and any(r['unitId'] == f['citation']['unitId'] for r in proof['citations'])):
                    supported.append(path)
        claim_checks.append({'rawField': deepcopy(f), 'subject': observation['subject'], 'paths': paths,
                             'status': 'SUPPORTED_LITERAL_SOURCE_FACT' if supported else 'UNRESOLVED_CLAIM_NOT_ADOPTED',
                             'supportedPaths': sorted(set(supported)), 'origins': observation['origins']})
    # Source problems never turn every unfilled field into SOURCE_UNAVAILABLE.
    # A specific inaccessible attachment is recorded at scope level instead.
    pending = [r['path'] for r in rows if r['status'] not in {'SOURCE_VERIFIED_REPORTED', 'NOT_REPORTED_IN_EXPLICIT_CHECKED_SCOPE'}]
    doc['reviewPlan'].update(
        checkedScope=['paper:' + u for u in scope['unitIds']],
        secondPassStatus='PARTIAL' if pending or doc['reviewPlan']['unavailableSources'] else 'COMPLETED',
        secondPassFactPaths=[r['path'] for r in rows if r['path'].startswith('/groups/')],
        unprocessedScope=['NOT_A_FULL_PUBLICATION_REVIEW', *scope['issues'], *pending],
        unresolvedDisagreements=[r['path'] for r in rows if r['status'] == 'SOURCE_CONFLICT'])
    ledger = empty_ledger(doc); ledger['checks'] = checks
    return doc, ledger, {'caseKey': doc['caseKey'], 'scope': scope, 'fields': rows,
                         'claims': claim_checks, 'unavailableSources': doc['reviewPlan']['unavailableSources'],
                         'missingMeaning': 'NO_MATCH_IS_NOT_SOURCE_ABSENCE', 'clinicalAdoption': 'NONE'}


def review_package(source, package, expected_index_sha256, output):
    index, documents, observations, inputs = read_input(source, package, expected_index_sha256)
    output = Path(output); require(not output.exists(), 'NEW_OUTPUT_REQUIRED')
    bodies = {'inputs/' + name: body for name, body in inputs.items()}; entries = []
    prepared = []
    for item, doc in documents:
        selected = [c for c in observations if c['caseKey'] == doc['caseKey']]
        reviewed, ledger, report = review_case(source, doc, selected)
        stem = Path(item['path']).stem
        for suffix, obj in [('.json', reviewed), ('-ledger.json', ledger), ('-review.json', report)]:
            bodies[stem + suffix] = canonical(obj)
        prepared.append((reviewed, ledger))
        entries.append({'caseKey': doc['caseKey'], 'path': stem + '.json', 'sha256': digest(bodies[stem + '.json']),
                        'ledgerPath': stem + '-ledger.json', 'ledgerSha256': object_digest(ledger),
                        'reportPath': stem + '-review.json', 'reportSha256': digest(bodies[stem + '-review.json']),
                        'verifiedFacts': len(ledger['checks']), 'recoveredFields': [r['path'] for r in report['fields'] if r['foundBySecondPass']],
                        'sourceConflicts': reviewed['reviewPlan']['unresolvedDisagreements'], 'status': 'SOURCE_REVIEWED_CONTEXT_PENDING'})
    require(sum(map(len, bodies.values())) <= MAX_PACKAGE, 'OUTPUT_SIZE_LIMIT')
    output.mkdir(parents=True, exist_ok=False)
    for name, raw in bodies.items():
        path = output / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(raw)
    for doc, ledger in prepared:
        # Validates actual copies, every quote, checked location and proof binding.
        validation = assess(doc, output, ledger, object_digest(ledger))
        require(validation['status'] != 'READY_FOR_EVALUATION', 'UNEXPECTED_SCIENTIFIC_READINESS')
    result = {'schemaVersion': 'denovo-core-source-review-package-v1', 'version': VERSION,
              'inputIndexSha256': expected_index_sha256, 'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
              'implementation': implementation(), 'cases': entries, 'status': 'SOURCE_REVIEWED_CONTEXT_PENDING',
              'businessPublishable': False, 'clinicalAdoption': 'NONE', 'criterionDecision': 'NOT_RUN',
              'modelCalls': 0, 'networkCalls': 0, 'scopeMeaning': 'Bounded deterministic second pass, not complete-paper absence proof'}
    (output / 'index.json').write_bytes(canonical(result))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('library', 'input', 'index-sha256', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    from bundle_model_contract import load
    raw = bounded(Path(args.input) / 'index.json'); require(digest(raw) == args.index_sha256, 'INDEX_PIN_MISMATCH')
    index = parse(raw)
    result = review_package(load(Path(args.library), index['pmcid'], index['bundleSha256']),
                            args.input, args.index_sha256, args.output)
    print(canonical({'status': result['status'], 'cases': len(result['cases']), 'modelCalls': 0}).decode())


if __name__ == '__main__':
    main()
