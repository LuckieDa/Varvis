"""Offline transport checks for start-loss candidate materials; never scores PVS1.

Pins and pointers establish byte provenance, not scientific truth. Even a complete
declaration must be replayed by the future sequence/source/policy evaluators.
No filesystem, network, model, configuration or database writes occur here.
"""
from datetime import datetime
import re
from urllib.parse import urlsplit

from assemble_materials import parse, require, sha, content_hash

SCHEMA = 'pvs1-start-loss-candidate-material-v1'
PREFIX = 'PVS1_START_LOSS_'


def check(ok, reason):
    require(ok, PREFIX + reason)


def shape(value, fields):
    check(isinstance(value, dict) and set(value) == set(fields.split()), 'FIELDS_INVALID')


def text(value):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= 2048


def integer(value):
    return type(value) is int and value > 0


def pin(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None


def allele(value):
    shape(value, 'assembly contig position reference alternate')
    check(value['assembly'] in {'GRCh38', 'GRCh37'} and text(value['contig'])
          and integer(value['position']), 'ALLELE_IDENTITY_INVALID')
    check(all(isinstance(value[k], str) and re.fullmatch('[ACGT]{1,50}', value[k])
              for k in ('reference', 'alternate')) and value['reference'] != value['alternate'],
          'ALLELE_IDENTITY_INVALID')


def background(value):
    shape(value, 'hgncId mondoId moi transcript')
    check(isinstance(value['hgncId'], str) and re.fullmatch(r'HGNC:[1-9][0-9]*', value['hgncId'])
          and isinstance(value['mondoId'], str) and re.fullmatch(r'MONDO:[0-9]{7}', value['mondoId'])
          and text(value['moi']) and isinstance(value['transcript'], str)
          and re.fullmatch(r'(?:ENST[0-9]+|NM_[0-9]+)\.[1-9][0-9]*', value['transcript']),
          'BACKGROUND_IDENTITY_INVALID')


def pointer(document, path, allow_empty=False):
    check(isinstance(path, str) and path.startswith('/') and len(path) <= 2048
          and re.search(r'~(?![01])', path) is None, 'SOURCE_POINTER_INVALID')
    node = document
    try:
        for encoded in path[1:].split('/'):
            token = encoded.replace('~1', '/').replace('~0', '~')
            if isinstance(node, list):
                check(re.fullmatch(r'0|[1-9][0-9]*', token) is not None, 'SOURCE_POINTER_INVALID')
                node = node[int(token)]
            else:
                check(isinstance(node, dict), 'SOURCE_POINTER_INVALID')
                node = node[token]
    except (KeyError, IndexError, TypeError):
        raise ValueError(PREFIX + 'SOURCE_POINTER_INVALID') from None
    check(node is not None and node != '' and (allow_empty or node != [] and node != {}), 'SOURCE_POINTER_EMPTY')


def validate(material, expected_context, originals):
    """Validate against caller-owned context and original JSON bytes, without adoption.

    The caller must not reconstruct expected_context from this untrusted material.
    SUPPORTED/FOUND/ELIGIBLE values remain declarations pending semantic replay.
    """
    try:
        return _validate(material, expected_context, originals)
    except (TypeError, KeyError, AttributeError, OverflowError):
        raise ValueError(PREFIX + 'MALFORMED_INPUT') from None


def _validate(material, expected_context, originals):
    shape(material, 'schemaVersion dataClass runtimeEligible context policy mechanism startEdit '
          'transcriptReview restart upstreamReview sources')
    check(material['schemaVersion'] == SCHEMA, 'SCHEMA_UNSUPPORTED')
    check(material['dataClass'] in {'SYNTHETIC', 'PUBLIC_REFERENCE'}
          and material['runtimeEligible'] is False, 'MATERIAL_USE_INVALID')
    context = material['context']
    shape(context, 'allele background')
    allele(context['allele']); background(context['background'])
    check(context == expected_context, 'CONTEXT_MISMATCH')
    check(isinstance(originals, dict), 'SOURCE_BYTES_REQUIRED')
    descriptors = material['sources']
    check(isinstance(descriptors, list) and len(descriptors) <= 128, 'SOURCE_LIMIT')
    documents, declared_sources, total = {}, set(), 0
    for source in descriptors:
        shape(source, 'sourceId sha256 sourceUrl capturedAt dataClass captureStatus reason')
        sid = source['sourceId']
        check(text(sid) and sid not in declared_sources, 'SOURCE_IDENTITY_INVALID')
        declared_sources.add(sid)
        check(source['dataClass'] == material['dataClass'] or material['dataClass'] == 'SYNTHETIC'
              and source['dataClass'] == 'PUBLIC_REFERENCE', 'SOURCE_CLASS_MISMATCH')
        check(source['captureStatus'] in {'AVAILABLE', 'UNAVAILABLE'}, 'SOURCE_CAPTURE_STATUS_INVALID')
        try:
            url = urlsplit(source['sourceUrl'])
            timestamp = datetime.fromisoformat(source['capturedAt'].replace('Z', '+00:00'))
            valid = url.scheme == 'https' and bool(url.hostname) and not url.username and not url.password
            check(valid and timestamp.tzinfo is not None, 'SOURCE_METADATA_INVALID')
        except (ValueError, TypeError, AttributeError):
            raise ValueError(PREFIX + 'SOURCE_METADATA_INVALID') from None
        raw = originals.get(sid)
        if source['captureStatus'] == 'UNAVAILABLE':
            check(source['sha256'] is None and sid not in originals and text(source['reason']),
                  'UNAVAILABLE_SOURCE_HAS_FAKE_ORIGINAL')
            continue
        check(pin(source['sha256']) and source['reason'] is None, 'SOURCE_IDENTITY_INVALID')
        check(isinstance(raw, bytes) and 0 < len(raw) <= 4_000_000, 'SOURCE_BYTES_REQUIRED')
        total += len(raw)
        check(total <= 16_000_000, 'SOURCE_TOTAL_LIMIT')
        check(sha(raw) == source['sha256'], 'SOURCE_SHA_MISMATCH')
        documents[sid] = parse(raw)

    def citations(values, required=False, allow_empty=False):
        check(isinstance(values, list) and len(values) <= 128 and (values or not required), 'CITATIONS_REQUIRED')
        for ref in values:
            shape(ref, 'sourceId jsonPointer')
            check(ref['sourceId'] in documents, 'CITATION_SOURCE_MISSING')
            pointer(documents[ref['sourceId']], ref['jsonPointer'], allow_empty)

    def issues(values):
        check(isinstance(values, list) and len(values) <= 128 and all(text(v) for v in values), 'ISSUES_INVALID')

    policy = material['policy']
    shape(policy, 'status policyId version sha256 citations')
    check(policy['status'] in {'GENERAL_SELECTED', 'SPECIALIZED_SELECTED', 'PROHIBITED',
                              'UNRESOLVED', 'UNSUPPORTED', 'CONFLICT'}, 'POLICY_STATUS_INVALID')
    selected = policy['status'] in {'GENERAL_SELECTED', 'SPECIALIZED_SELECTED', 'PROHIBITED'}
    policy_identity = all(text(policy[k]) for k in ('policyId', 'version')) and pin(policy['sha256'])
    check(policy_identity or not selected and all(policy[k] is None for k in ('policyId', 'version', 'sha256')),
          'POLICY_IDENTITY_INVALID')
    citations(policy['citations'], bool(policy_identity))
    mechanism = material['mechanism']
    shape(mechanism, 'status citations')
    check(mechanism['status'] in {'ESTABLISHED_LOF', 'NOT_LOF', 'UNRESOLVED', 'CONFLICT'}, 'MECHANISM_STATUS_INVALID')
    citations(mechanism['citations'], mechanism['status'] != 'UNRESOLVED')

    edit = material['startEdit']
    shape(edit, 'status strand cdsPosition referenceCodon alternateCodon modelSha256 citations')
    check(edit['status'] in {'AVAILABLE', 'UNRESOLVED', 'UNSUPPORTED', 'CONFLICT'}, 'EDIT_STATUS_INVALID')
    if edit['status'] == 'AVAILABLE':
        check(type(edit['strand']) is int and edit['strand'] in {-1, 1}
              and type(edit['cdsPosition']) is int and edit['cdsPosition'] in {1, 2, 3}
              and edit['referenceCodon'] == 'ATG' and isinstance(edit['alternateCodon'], str)
              and re.fullmatch('[ACGT]{3}', edit['alternateCodon'])
              and edit['alternateCodon'] != 'ATG' and pin(edit['modelSha256']), 'EDIT_DECLARATION_INVALID')
        check(context['allele']['assembly'] == 'GRCh38'
              and re.fullmatch(r'[1-9]|1[0-9]|2[0-2]|X|Y', context['allele']['contig']) and
              all(len(context['allele'][k]) == 1 for k in ('reference', 'alternate')),
              'EDIT_OUTSIDE_FIRST_RELEASE')
    else:
        check(all(edit[k] is None for k in ('strand', 'cdsPosition', 'referenceCodon',
                                          'alternateCodon', 'modelSha256')), 'UNRESOLVED_EDIT_HAS_VALUES')
    citations(edit['citations'], edit['status'] == 'AVAILABLE')

    review = material['transcriptReview']
    shape(review, 'status checkedTranscripts complete findings issues citations')
    check(review['status'] in {'FOUND', 'NOT_FOUND_IN_ASSESSED_SET', 'UNRESOLVED', 'CONFLICT'}
          and type(review['complete']) is bool, 'TRANSCRIPT_STATUS_INVALID')
    checked = review['checkedTranscripts']
    check(isinstance(checked, list) and len(checked) <= 1000
          and all(isinstance(v, str) and re.fullmatch(r'(?:ENST[0-9]+|NM_[0-9]+)\.[1-9][0-9]*', v) for v in checked)
          and len(set(checked)) == len(checked), 'TRANSCRIPT_SET_INVALID')
    findings = review['findings']
    check(isinstance(findings, list) and len(findings) <= 1000, 'TRANSCRIPT_SET_INVALID')
    found, uncertain, seen_transcripts = False, False, set()
    for finding in findings:
        shape(finding, 'transcript functionallyRelevant differentStart targetUnaffected citations')
        check(finding['transcript'] in checked and finding['transcript'] not in seen_transcripts, 'TRANSCRIPT_NOT_CHECKED')
        seen_transcripts.add(finding['transcript'])
        flags = [finding[k] for k in ('functionallyRelevant', 'differentStart', 'targetUnaffected')]
        check(all(v is None or type(v) is bool for v in flags), 'TRANSCRIPT_FACT_INVALID')
        check(finding['transcript'] != context['background']['transcript'] or finding['differentStart'] is not True,
              'SELECTED_TRANSCRIPT_IS_NOT_ALTERNATIVE')
        found |= all(v is True for v in flags)
        uncertain |= not any(v is False for v in flags) and any(v is None for v in flags)
        citations(finding['citations'], True)
    issues(review['issues']); citations(review['citations'], review['status'] != 'UNRESOLVED')
    if review['status'] == 'FOUND':
        check(found, 'ALTERNATIVE_TRANSCRIPT_WITNESS_REQUIRED')
    if review['status'] == 'NOT_FOUND_IN_ASSESSED_SET':
        check(review['complete'] and not review['issues'] and not found and not uncertain
              and context['background']['transcript'] in checked
              and len(findings) == len(checked)
              and {f['transcript'] for f in findings} == set(checked), 'NEGATIVE_TRANSCRIPT_REVIEW_INCOMPLETE')

    restart = material['restart']
    shape(restart, 'status cdsPosition citations')
    check(restart['status'] in {'CANDIDATE_FOUND', 'NO_CANDIDATE', 'UNRESOLVED'}, 'RESTART_STATUS_INVALID')
    has_restart = restart['status'] == 'CANDIDATE_FOUND'
    check(integer(restart['cdsPosition']) and restart['cdsPosition'] > 3
          and (restart['cdsPosition'] - 1) % 3 == 0 if has_restart
          else restart['cdsPosition'] is None, 'RESTART_POSITION_INVALID')
    citations(restart['citations'], restart['status'] != 'UNRESOLVED')

    upstream = material['upstreamReview']
    shape(upstream, 'status records requiredSourceIds queries issues')
    check(upstream['status'] in {'QUALIFYING_FOUND', 'NONE_IN_ASSESSED_SOURCES', 'INCOMPLETE', 'CONFLICT'},
          'UPSTREAM_STATUS_INVALID')
    issues(upstream['issues'])
    check(isinstance(upstream['records'], list) and len(upstream['records']) <= 2000, 'REFERENCE_LIMIT')
    eligible, pending = set(), False
    for record in upstream['records']:
        shape(record, 'allele background cdsStart cdsEnd classification eligibility reason citations')
        allele(record['allele']); background(record['background'])
        check(record['classification'] in {'PATHOGENIC', 'LIKELY_PATHOGENIC', 'OTHER', 'CONFLICT', 'UNKNOWN'}
              and record['eligibility'] in {'ELIGIBLE', 'EXCLUDED', 'UNRESOLVED'} and text(record['reason']),
              'REFERENCE_DECLARATION_INVALID')
        check((record['cdsStart'] is None and record['cdsEnd'] is None)
              or (integer(record['cdsStart']) and integer(record['cdsEnd'])
                  and record['cdsEnd'] >= record['cdsStart']), 'REFERENCE_POSITION_INVALID')
        citations(record['citations'], True)
        pending |= record['eligibility'] == 'UNRESOLVED'
        if record['eligibility'] == 'ELIGIBLE':
            check(has_restart and record['background'] == context['background']
                  and record['allele'] != context['allele']
                  and all(record['allele'][k] == context['allele'][k] for k in ('assembly', 'contig'))
                  and integer(record['cdsStart']) and record['cdsEnd'] < restart['cdsPosition']
                  and record['classification'] == 'PATHOGENIC', 'REFERENCE_ELIGIBILITY_CONTRADICTION')
            key = content_hash(record['allele'])
            check(key not in eligible, 'DUPLICATE_ELIGIBLE_REFERENCE')
            eligible.add(key)
    required = upstream['requiredSourceIds']
    check(isinstance(required, list) and len(required) <= 128 and all(text(s) and s in declared_sources for s in required)
          and len(required) == len(set(required)), 'SEARCH_SCOPE_INVALID')
    queries = upstream['queries']
    check(isinstance(queries, list) and len(queries) <= 128, 'SEARCH_LIMIT')
    completed, queried = set(), set()
    for query in queries:
        shape(query, 'sourceId status cdsStart cdsEnd citations')
        sid = query['sourceId']
        check(sid in required and sid not in queried and query['status'] in
              {'COMPLETE', 'PARTIAL', 'TRUNCATED', 'TIMEOUT', 'UNAVAILABLE'}, 'SEARCH_RECEIPT_INVALID')
        queried.add(sid)
        check(integer(query['cdsStart']) and integer(query['cdsEnd'])
              and query['cdsStart'] <= query['cdsEnd'], 'SEARCH_INTERVAL_INVALID')
        citations(query['citations'], query['status'] in {'COMPLETE', 'PARTIAL', 'TRUNCATED'}, allow_empty=True)
        check(query['status'] not in {'COMPLETE', 'PARTIAL', 'TRUNCATED'} or sid in documents, 'SEARCH_SOURCE_UNAVAILABLE')
        if query['status'] == 'COMPLETE' and has_restart and query['cdsStart'] == 1 and query['cdsEnd'] == restart['cdsPosition'] - 1:
            completed.add(sid)
    if upstream['status'] == 'QUALIFYING_FOUND':
        check(bool(eligible), 'REFERENCE_WITNESS_REQUIRED')
    if upstream['status'] == 'NONE_IN_ASSESSED_SOURCES':
        check(has_restart and required and completed == set(required) and not eligible and not pending
              and not upstream['issues'], 'NEGATIVE_REFERENCE_SEARCH_INCOMPLETE')
    return {'schemaVersion': 'pvs1-start-loss-material-check-v1', 'status': 'STRUCTURALLY_VALID',
            'materialSha256': content_hash(material), 'contextSha256': content_hash(context),
            'sourceCount': len(documents), 'unavailableSourceCount': len(declared_sources) - len(documents),
            'declaredEligibleReferenceCount': len(eligible),
            'requiresSemanticReplay': True, 'runtimeEligible': False, 'scientificEvaluationPerformed': False}
