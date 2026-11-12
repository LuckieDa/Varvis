"""Versioned, source-bound prompt v2 inputs for acquired public attachments.

No source is truncated. Exact text is necessary, never sufficient for adoption.
Legacy model receipts and their eight implementation hashes remain unchanged.
"""
from copy import deepcopy
from pathlib import Path
import re

from model_literature_contract import canonical, digest, parse, source_labels, canonical_case_label
from prepare_literature import prepare
from prepare_literature_prompt import (SYSTEM, SCHEMA, check_shape, quote_check,
                                      validate_candidates, request as main_request)
from literature_attachments import parse_attachment, members
from paper_library_registration import inside
from model_transport import bounded
from literature_work_units import plans as work_plans

VERSION = 'denovo-bundle-model-1.1.0'
RESPONSE_VERSION = 'denovo-bounded-candidates-v1'
BOUNDED_SCHEMA = deepcopy(SCHEMA)
BOUNDED_SCHEMA['properties']['schemaVersion']['enum'] = [RESPONSE_VERSION]
BOUNDED_SCHEMA['properties']['cases']['maxItems'] = 2
bounded_case = BOUNDED_SCHEMA['properties']['cases']['items']
bounded_case['properties']['fields']['maxItems'] = 8
bounded_case['properties']['originCitations']['maxItems'] = 2
bounded_case['properties']['caseCitation']['properties']['quote']['maxLength'] = 600
bounded_case['properties']['originCitations']['items']['properties']['quote']['maxLength'] = 600
bounded_case['properties']['fields']['items']['properties']['citation']['properties']['quote']['maxLength'] = 600
bounded_case['properties']['fields']['items']['properties']['sourceValue']['maxLength'] = 300
BOUNDED_SCHEMA['properties']['coverage'] = {'type':'string','enum':[
    'EXTRACTED_WITHIN_TARGET','MORE_CASES_OR_FIELDS','CONTEXT_INSUFFICIENT'], 'maxLength':40}
BOUNDED_SCHEMA['properties']['sourceAssessments'] = {'type':'array','maxItems':2,'items':{
    'type':'object','additionalProperties':False,'required':['materialKind','citation'], 'properties':{
        'materialKind':{'type':'string','maxLength':40,'enum':['INDIVIDUAL_CASE_MATERIAL','AGGREGATE_OR_METHODS','UNCLEAR']},
        'citation':deepcopy(bounded_case['properties']['caseCitation'])}}}
BOUNDED_SCHEMA['required'] += ['coverage','sourceAssessments']


def check_candidate(candidate):
    if isinstance(candidate, dict) and candidate.get('schemaVersion') == 'pp2-relationship-material-candidates-v1':
        check_shape(candidate, relationship_material().SCHEMA)
        return
    if isinstance(candidate, dict) and candidate.get('schemaVersion') == 'denovo-core-material-candidates-v1':
        from core_extraction_prompt import check_candidate as core_check
        return core_check(candidate)
    check_shape(candidate, BOUNDED_SCHEMA)
    require(bool(candidate['sourceAssessments']), 'TARGET_ASSESSMENT_REQUIRED')


def require(value, code):
    if not value:
        raise ValueError('DENOVO_BUNDLE_' + code)


def relationship_material():
    """Explicit sibling module; imported lazily to preserve the legacy cycle."""
    import sys
    folder = str(Path(__file__).resolve().parents[1] / 'ps1_pm5')
    if folder not in sys.path:
        sys.path.insert(0, folder)
    import pp2_relationship_material
    return pp2_relationship_material


def pinned(root, relative, pin, maximum, *, registered_empty=False):
    path = inside(root, relative)
    if registered_empty:
        # Only a pinned manifest may declare an unread empty attachment. All
        # other artifacts retain the strict nonempty bounded-reader contract.
        with path.open('rb') as stream:
            raw = stream.read(maximum + 1)
        require(len(raw) <= maximum, 'SOURCE_SIZE_LIMIT')
        require(not raw, 'EMPTY_ATTACHMENT_CHANGED')
    else:
        raw = bounded(path, maximum)
    require(digest(raw) == pin, 'SOURCE_PIN_MISMATCH')
    return raw


def load(library, pmcid, bundle_pin, criterion='PS2'):
    require(re.fullmatch(r'PMC[1-9][0-9]*', pmcid or '') and
            re.fullmatch(r'[a-f0-9]{64}', bundle_pin or ''), 'IDENTITY_INVALID')
    library = Path(library).resolve(strict=True)
    require(criterion in {'PS2', 'PM6', 'PM3', 'PP1', 'PVS1', 'PP2'}, 'CRITERION_INVALID')
    index = library / 'evidence' / criterion / 'index.json'
    index_document = parse(bounded(index, 1048576))
    require(index_document.get('criterion') == criterion, 'INDEX_CRITERION_MISMATCH')
    entries = index_document['documents']
    matches = [e for e in entries if e.get('pmcid') == pmcid and e.get('sourceClass') == 'PUBLIC_PAPER']
    require(len(matches) == 1, 'REGISTERED_PUBLIC_SOURCE_REQUIRED')
    entry = matches[0]
    refs = [b for b in entry.get('acquisitionBundles', []) if b.get('sha256') == bundle_pin]
    require(len(refs) == 1, 'REGISTERED_BUNDLE_REQUIRED')
    root = inside(library, 'papers/' + pmcid)
    bundle_path = (index.parent / refs[0]['path']).resolve()
    require(bundle_path == root / 'source-bundles' / (bundle_pin + '.json'), 'BUNDLE_PATH_INVALID')
    bundle = parse(pinned(root, 'source-bundles/' + bundle_pin + '.json', bundle_pin, 2097152))
    require(bundle.get('schemaVersion') == 'public-paper-source-bundle-v1' and bundle.get('pmcid') == pmcid,
            'BUNDLE_SCHEMA')
    require(bundle['main']['sha256'] == entry['sha256'], 'ACTIVE_SOURCE_CHANGED')
    raw = pinned(root, bundle['main']['path'], entry['sha256'], 2097152)
    reader = bundle.get('publisherReader')
    if reader is not None:
        from pm3_publisher_reader import prepare as prepare_publisher
        require(criterion == 'PM3' and bundle['main']['path'] == pmcid + '.publisher.html'
                and (index.parent / entry['path']).resolve() == root / bundle['main']['path']
                and bundle['main']['url'] == entry['acquisitionUrl']
                and not bundle['attachments'] and not bundle.get('package')
                and not bundle.get('authorManuscript'), 'PUBLISHER_REVIEW_SCOPE_INVALID')
        def read(ref, limit):
            return pinned(root, ref['path'], ref['sha256'], limit)
        document = prepare_publisher(raw, pmcid, bundle['main'], reader, read)
        return {'pmcid': pmcid, 'bundleSha256': bundle_pin, 'raw': raw, 'main': document,
                'units': deepcopy(document['units']), 'attachments': [], 'coverage': bundle['supplementCoverage'],
                'publisherReader': True}
    manuscript = bundle.get('authorManuscript')
    if manuscript:
        from author_manuscript_source import derive
        original = pinned(root, manuscript['rawPath'], manuscript['rawSha256'], 2097152)
        derived, unused_document, derivation = derive(original, pmcid, manuscript['manuscriptId'])
        # The old implementation pin is historical provenance, not a claim that
        # today's reader bytes are identical. Reproduce every derived byte and
        # semantic receipt field instead; never rewrite the old receipt.
        require(derived == raw and all(manuscript.get(k) == v for k, v in derivation.items()
                                      if k != 'implementationSha256'),
                'MANUSCRIPT_DERIVATION_MISMATCH')
    scan = bundle.get('scannedPages')
    reviewed_pages = {}
    if scan is not None:
        require(criterion == 'PP1' and not manuscript and not bundle['attachments'] and not bundle.get('package'),
                'SCAN_REQUIRES_PP1_MANUAL_REVIEW')
        from pp1_reviewed_pages import bibliography, pages
        reviewed_pages = pages(raw, pmcid, scan, lambda ref, limit: pinned(root, ref['path'], ref['sha256'], limit))
        document = bibliography(raw, pmcid)
    else:
        document = prepare(raw, pmcid, entry['sha256'])
    units = deepcopy(document['units'])
    for unit in units:
        unit['sourceSha256'] = entry['sha256']
    attachments, expansion = [], [100 * 1024 * 1024]
    package = bundle.get('package')
    package_files = {}
    if package:
        package_files = dict(members(pinned(root, package['path'], package['sha256'], 50 * 1024 * 1024), expansion))
    require(len(bundle['attachments']) <= 512, 'ATTACHMENT_LIMIT')
    for item in bundle['attachments']:
        registered_empty = (item.get('status') == 'ARCHIVED_NOT_PARSED'
                            and type(item.get('bytes')) is int and item['bytes'] == 0)
        body = pinned(root, item['path'], item['sha256'], 20 * 1024 * 1024,
                      registered_empty=registered_empty)
        if package_files.get(item['originalName']) != body:
            require(item.get('officialSingleFile'), 'ATTACHMENT_NOT_IN_OFFICIAL_PACKAGE')
            from official_supplement_source import validate
            def read_evidence(ref, limit):
                return pinned(root, ref['path'], ref['sha256'], limit)
            receipt = parse(read_evidence(item['officialSingleFile'], 65536))
            validate(document, item, body, receipt, read_evidence)
        # Reparse authoritative bytes, not an editable derived JSON spreadsheet.
        derived = ({'status': 'ARCHIVED_NOT_PARSED',
                    'reasonCode': item.get('reasonCode') or 'PAPER_ATTACHMENT_EMPTY'}
                   if registered_empty else parse_attachment(item['originalName'], body, expansion,
                       allow_pdf=criterion in {'PM3', 'PP1', 'PP2'}, allow_archive=criterion in {'PM3', 'PP1', 'PP2'}))
        summary = {k: item[k] for k in ('originalName', 'sha256', 'association')}
        summary.update(status=derived['status'], reasonCode=derived.get('reasonCode'))
        if registered_empty:
            summary.update(bytes=0, readingLimitation='REGISTERED_EMPTY_ATTACHMENT_UNREAD')
        if derived.get('format') in ('DOCX', 'PDF'):
            summary.update(limitations=derived.get('limitations', []),
                           unparsedParts=derived.get('unparsedParts', []),
                           readingLimitation=derived.get('limitation'))
        attachments.append(summary)
        leaves = derived.get('members', [derived]) if derived.get('format') == 'ZIP' else [derived]
        if derived.get('format') == 'ZIP':
            summary['members'] = [{k: child.get(k) for k in
                ('originalName', 'sha256', 'bytes', 'format', 'status', 'reasonCode', 'limitation')}
                for child in leaves]
        for derived in leaves:
            prefix = f"attachment:{item['sha256']}"
            member_metadata = {}
            if summary.get('members') is not None:
                prefix += ':member:' + derived['sha256'] + ':' + digest(derived['originalName'].encode())
                member_metadata = {'archiveMemberName': derived['originalName'],
                                   'archiveMemberSha256': derived['sha256']}
            if derived['status'] == 'TEXT_READ_NOT_SEMANTICALLY_INTERPRETED' or derived.get('format') == 'PDF':
                for unit in derived.get('units', []):
                    units.append({**unit, 'unitId': f"{prefix}:{unit['location']}",
                        'sourceSha256': item['sha256'], 'originalName': item['originalName'],
                        **member_metadata, 'sections': [], 'cellSemantics': 'TEXT',
                        'emptyCell': not unit['text']})
                if derived['status'] == 'TEXT_READ_NOT_SEMANTICALLY_INTERPRETED': continue
            if derived['status'] != 'CELLS_READ_NOT_SEMANTICALLY_INTERPRETED':
                continue
            sheets = derived.get('sheets')
            if sheets is None:
                sheets = [{'name': 'delimited', 'cells': [
                    {'address': f"R{row['row']}C{cell['column']}", 'text': cell['text'], 'use': 'TEXT'}
                    for row in derived['rows'] for cell in row['cells']]}]
            for number, sheet in enumerate(sheets):
                for cell in sheet['cells']:
                    text = cell.get('text')
                    require(text is None or isinstance(text, str), 'CELL_TEXT_INVALID')
                    units.append({'unitId': f"{prefix}:{number}:{cell['address']}",
                        'kind': 'SUPPLEMENT_CELL', 'text': '' if text is None else text, 'sections': [],
                        'emptyCell': text is None or text == '', 'rawValue': cell.get('rawValue'),
                        'sourceSha256': item['sha256'], 'originalName': item['originalName'],
                        **member_metadata, 'sheet': sheet['name'], 'cell': cell['address'], 'cellSemantics': cell.get('use', 'TEXT'),
                        **({'mergedCell': True} if cell.get('mergedCell') else {}),
                        'formula': cell.get('formula'), 'styleIndex': cell.get('styleIndex'),
                        'storedType': cell.get('storedType'), 'sheetState': sheet.get('state', 'visible'),
                        **({'page': cell['page'], 'bbox': cell.get('bbox'), 'sourcePart': 'pdf',
                            'sourcePath': cell['sourcePath'], 'pdfTable': True} if 'page' in cell else {})})
    require(len({u['unitId'] for u in units}) == len(units), 'DUPLICATE_UNIT')
    return {'pmcid': pmcid, 'bundleSha256': bundle_pin, 'raw': raw, 'main': document,
            'units': units, 'attachments': attachments, 'coverage': bundle['supplementCoverage'],
            **({'reviewedPages': reviewed_pages} if reviewed_pages else {})}


def requests(source, model='gemini-3-flash', max_output_tokens=8192, max_request_bytes=196608,
             extraction_profile='legacy-v1'):
    if extraction_profile == 'pp2-relationship-material-v1':
        return relationship_material().requests(source, model, max_output_tokens, max_request_bytes)
    require(extraction_profile in ('legacy-v1', 'core-material-v1', 'core-material-v2'), 'EXTRACTION_PROFILE_INVALID')
    # Partition in source order, never by favourable genes/claims. Every chunk is
    # explicitly partial; the full source/unit manifest remains in the artifact.
    minimal = {**source['main'], 'units': [], 'tables': []}
    result = main_request(minimal, model, max_output_tokens)
    result['response_format']['json_schema'] = {'name':'denovo_bounded_candidates_v1',
        'strict':True,'schema':BOUNDED_SCHEMA}
    result['messages'][0]['content'] = SYSTEM + '''
The supplied SUPPLEMENT_CELL units are actual acquired attachment content, not
links. Their sourceSha256, sheet and cell identify the original file location.
Read neighbouring cells/headers and all supplied body context. Do not invent a
case owner for a cell. Spreadsheet formula caches, raw typed/date values and
hidden sheets are not clinically interpreted facts. Preserve their limitations.
Unparsed PDF/images and missing supplements remain unread. Quotes are verified
against each source file, not against a different file with the same sentence.

This request covers ONE small extraction target, not the whole paper. Extract
only cases with a fact or explicit case anchor in targetUnitIds. Other units
are context, not additional extraction targets. Never turn gene/cohort summaries,
probability matrices or methods into individual patients. Return 1-2 short
sourceAssessments citing an exact target quote even if cases is empty.
At most two cases and eight fields per case; keep quotes short (prefer <=120
characters) while preserving negation and subject. Never abbreviate/rewrite a
quote. If more facts/cases exist, report MORE_CASES_OR_FIELDS; if context is
insufficient report CONTEXT_INSUFFICIENT. Empty cases is not absence of cases
in the paper. Do not infer a case label from a spreadsheet row number.
'''
    content = {
        'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
        'sourceSha256': source['main']['sourceSha256'], 'title': source['main']['title'],
        'sourceCaseLabels': sorted(source_labels(source['main'])),
        'attachments': source['attachments'], 'supplementCoverage': source['coverage'],
        'partialInputMeaning': 'Other chunks may contain necessary headers, negation or contradictory facts. '
             'Do not infer absence or assign a cell to a case without explicit context. No automatic adoption.'}
    if any('sourcePart' in u for u in source['units']):
        # Keep legacy requests byte-identical for already cached model responses.
        result['messages'][0]['content'] += '''
Word attachments contain SUPPLEMENT_PARAGRAPH and SUPPLEMENT_CELL units. Use
sourcePart/sourcePath and sourceSha256 for exact original positions, not page
numbers. Row numbers and merged cells do not prove case ownership. Footnotes,
headers and neighbouring text are context, not negative findings. Visual content,
comments and unparsed parts remain unread. Authors' ACMG classifications are
reported opinions, not authority to award evidence or confirm parentage.
'''
    require(type(max_request_bytes) is int and 0 < max_request_bytes <= 262144, 'REQUEST_LIMIT_INVALID')
    manifest = digest(canonical(source['units']))
    plans = work_plans(source)
    def packet(plan, index):
        value = deepcopy(result)
        sent = set(plan['targetUnitIds'] + plan['contextUnitIds'])
        units = [u for u in source['units'] if u['unitId'] in sent]
        value['messages'][1]['content'] = canonical({**content, 'units': units,
            'chunkIndex': index, 'chunkCount': len(plans), 'totalUnits': len(source['units']),
            'inputScope': 'BOUNDED_TARGET_WITH_CONTEXT', 'workUnit': plan,
            'workWithinLimit': plan['targetTextCharacters'] <= 6000 and len(plan['targetUnitIds']) <= 64,
            'unitManifestSha256': manifest}).decode()
        return value
    outputs = []
    for index, plan in enumerate(plans):
        value = packet(plan, index)
        require(len(canonical(value)) <= max_request_bytes, 'INPUT_TOO_LARGE_NOT_TRUNCATED')
        outputs.append(value)
    if extraction_profile in ('core-material-v1', 'core-material-v2'):
        from core_extraction_prompt import adapt_requests
        return adapt_requests(outputs, max_request_bytes, extraction_profile)
    return outputs


def request(source, model='gemini-3-flash', max_output_tokens=8192):
    values = requests(source, model, max_output_tokens)
    require(len(values) == 1, 'MULTIPLE_CHUNKS_REQUIRED')
    return values[0]


def cite(source, citation):
    found = [u for u in source['units'] if u['unitId'] == citation['unitId']]
    require(len(found) == 1, 'CITATION_UNIT_MISSING')
    unit = found[0]
    checked = quote_check({'units': found, 'sourceSha256': unit['sourceSha256']}, citation)
    for field in ('originalName', 'sheet', 'cell', 'cellSemantics', 'formula', 'sheetState', 'page', 'bbox',
                  'sourcePart', 'sourcePath', 'archiveMemberName', 'archiveMemberSha256', 'mergedCell', 'gridSpan', 'verticalMerge',
                  'noteReferences', 'sourceObjectId', 'sourceLabel', 'publishedFigure'):
        if field in unit:
            checked[field] = unit[field]
    checked['bundleSha256'] = source['bundleSha256']
    return checked


def review(source, candidate, allowed_units=None, target_units=None, candidate_schema=None, *, actual_payload=None):
    if actual_payload is not None:
        expected = actual_payload['response_format']['json_schema']['schema']['properties']['schemaVersion']['enum'][0]
        require(isinstance(candidate, dict) and candidate.get('schemaVersion') == expected,
                'CANDIDATE_ACTUAL_REQUEST_PROFILE_MISMATCH')
    if isinstance(candidate, dict) and candidate.get('schemaVersion') == 'pp2-relationship-material-candidates-v1':
        require(actual_payload is not None, 'RELATIONSHIP_ACTUAL_REQUEST_REQUIRED')
        return relationship_material().review(source, candidate, actual_payload)
    if isinstance(candidate, dict) and candidate.get('schemaVersion') == 'denovo-core-material-candidates-v1':
        from core_extraction_prompt import review as core_review
        return core_review(source, candidate, allowed_units, target_units)
    if candidate_schema is None:
        check_candidate(candidate)
    else:
        check_shape(candidate, candidate_schema)
        require(bool(candidate['sourceAssessments']), 'TARGET_ASSESSMENT_REQUIRED')
    assessments = []
    for item in candidate['sourceAssessments']:
        require(allowed_units is None or item['citation']['unitId'] in allowed_units, 'CITATION_OUTSIDE_ACTUAL_INPUT')
        require(target_units is None or item['citation']['unitId'] in target_units, 'ASSESSMENT_OUTSIDE_TARGET')
        assessments.append({**item, 'citation':cite(source,item['citation']),
                            'status':'MODEL_MATERIAL_KIND_PROPOSAL_NOT_SCIENTIFIC_FACT'})
    labels, cases = set(), []
    for row in candidate['cases']:
        label = canonical_case_label(row['caseLabel'])
        require(label not in labels, 'DUPLICATE_CASE_LABEL')
        labels.add(label)
        citations = [row['caseCitation'], *row['originCitations'], *[f['citation'] for f in row['fields']]]
        require(allowed_units is None or all(c['unitId'] in allowed_units for c in citations),
                'CITATION_OUTSIDE_ACTUAL_INPUT')
        require(target_units is None or any(c['unitId'] in target_units for c in
                [row['caseCitation'], *[f['citation'] for f in row['fields']]]), 'CASE_OUTSIDE_TARGET')
        has_attachment = any(c['unitId'].startswith('attachment:') for c in citations)
        anchor = cite(source, row['caseCitation'])
        if not has_attachment:
            # Preserve existing main-table and case-section attribution checks.
            result = validate_candidates(source['raw'], source['pmcid'], source['main']['sourceSha256'],
                {'schemaVersion':'denovo-literature-screening-candidates-v2', 'cases':[row]})['cases'][0]
        else:
            require(re.search(r'\b(?:Case|Patient|Individual|Proband)\s+' + re.escape(label) + r'(?![\w.-])',
                              anchor['quote'], re.I) or anchor['quote'].strip() == label,
                    'CASE_LABEL_NOT_ANCHORED')
            require(row['caseOrigin'] == 'UNCLEAR' or row['originCitations'], 'ORIGIN_CITATION_REQUIRED')
            result = {'caseLabel': label, 'caseOriginProposal': row['caseOrigin'],
                      'issues': ['ATTACHMENT_CASE_ATTRIBUTION_NOT_VERIFIED',
                                 'CASE_ORIGIN_SEMANTICS_NOT_VERIFIED', 'CASE_INDEPENDENCE_NOT_VERIFIED']}
        result['caseCitation'] = anchor
        result['originCitations'] = [cite(source, c) for c in row['originCitations']]
        result['fields'] = []
        for field in row['fields']:
            citation = cite(source, field['citation'])
            require(field['sourceValue'] in citation['quote'], 'VALUE_NOT_IN_QUOTE')
            issues = []
            if citation.get('cellSemantics', 'TEXT') != 'TEXT' or citation.get('sheetState', 'visible') != 'visible':
                issues.append('SPREADSHEET_VALUE_SEMANTICS_NOT_VERIFIED')
            result['fields'].append({**field, 'citation': citation, 'issues': issues,
                                     'subjectStatus': 'MODEL_PROPOSED_NOT_VERIFIED'})
        cases.append(result)
    return {'schemaVersion': 'denovo-bundle-review-v1', 'pmcid': source['pmcid'],
            'bundleSha256': source['bundleSha256'], 'cases': cases,
            'status': 'CANDIDATES_QUOTE_CHECKED_NOT_QUALIFIED', 'businessPublishable': False,
            'scientificExecution': False, 'clinicalAdoption': 'NONE',
            'sourceAssessments':assessments,'extractionCoverageProposal':candidate['coverage'],
            'supplementCoverage': source['coverage'], 'attachments': source['attachments'],
            'issues': ['CASE_QUALIFICATION_REQUIRED'] + ([] if cases else ['NO_EXTRACTION_NOT_PROVEN_NO_CASES'])
                + ([] if candidate['coverage']=='EXTRACTED_WITHIN_TARGET' else ['TARGET_EXTRACTION_INCOMPLETE'])}


def implementation():
    from model_replay import implementation as legacy
    files = ('bundle_model_contract.py', 'bundle_model_transport.py', 'run_bundle_model.py',
             'prepare_literature_prompt.py', 'literature_attachments.py', 'paper_library_registration.py',
             'literature_work_units.py', 'literature_case_structure.py', 'bundle_replay_compatibility.py',
             'author_manuscript_source.py', 'core_extraction_prompt.py', 'bundle_replay_profiles.json',
             'literature_docx.py', 'official_supplement_source.py', 'single_case_scope.py',
             'literature_pdf.py', 'literature_pdf_worker.py')
    question = Path(__file__).resolve().parents[1] / 'ps1_pm5' / 'pp2_relationship_material.py'
    return {**legacy(), **{f: digest(Path(__file__).with_name(f).read_bytes()) for f in files},
            'pp2_relationship_material.py': digest(question.read_bytes())}
