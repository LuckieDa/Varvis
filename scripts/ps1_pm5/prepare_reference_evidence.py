"""Offline, version-bound ClinVar evidence supplement for existing missense drafts.

No discovery, HTTP, clinical classification, disease mapping or approval. Sources
are caller-pinned full VCV XML records, not replacements for the original draft.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import xml.etree.ElementTree as ET

MAX_FILE = 4_000_000
MAX_TOTAL = 32_000_000
MAX_REFERENCES = 256
MAX_ASSERTIONS = 1024


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'JSON_DUPLICATE_KEY')
        result[key] = value
    return result


def parse_json(data):
    return json.loads(data.decode('utf-8-sig'), object_pairs_hook=unique_object,
                      parse_constant=lambda _: require(False, 'JSON_NONFINITE_NUMBER'))


def text(node):
    return ''.join(node.itertext()) if node is not None else ''


def only(parent, path, reason):
    nodes = parent.findall(path)
    require(len(nodes) == 1, reason)
    return nodes[0]


def tree(node):
    """Structural extraction; exact lexical XML remains in the pinned source."""
    return dict(tag=node.tag, attributes=dict(node.attrib), text=node.text,
                tail=node.tail, children=[tree(child) for child in node])


def walk(node, path):
    yield node, path
    counts = {}
    for child in node:
        counts[child.tag] = counts.get(child.tag, 0) + 1
        yield from walk(child, f'{path}/{child.tag}[{counts[child.tag]}]')


def iso_date(value):
    if re.fullmatch(r'\d{4}-\d{2}-\d{2}', value or ''):
        return dt.date.fromisoformat(value).isoformat()
    match = re.fullmatch(r'([A-Z][a-z]{2}) (\d{1,2}), (\d{4})', value or '')
    require(match is not None, 'ASSERTION_DATE_INVALID')
    months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
    require(match[1] in months, 'ASSERTION_DATE_INVALID')
    return dt.date(int(match[3]), months.index(match[1]) + 1, int(match[2])).isoformat()


def citation_scope(path):
    # Condition/criteria citations are not variant-specific evidence.
    if '/TraitSet[' in path:
        return 'CONDITION_BACKGROUND'
    if '/Classification[' in path:
        return 'CLASSIFICATION'
    if '/ObservedInList[' in path:
        return 'OBSERVATION'
    if '/AttributeSet[' in path:
        return 'ASSERTION_ATTRIBUTE_OR_METHOD'
    return 'ASSERTION_OTHER'


def parse_vcv(data, expected_vid, allele):
    require(len(data) <= MAX_FILE, 'INPUT_SIZE_LIMIT')
    xml = data.decode('utf-8-sig')
    require(not re.search(r'<!\s*(?:DOCTYPE|ENTITY)\b', xml, re.I), 'XML_DTD_OR_ENTITY_FORBIDDEN')
    root = ET.fromstring(xml)
    require(root.tag in ('ClinVarResult-Set', 'VariationArchive'), 'VCV_XML_FORMAT_UNSUPPORTED')
    archives = [root] if root.tag == 'VariationArchive' else root.findall('VariationArchive')
    require(len(archives) == 1, 'ONE_VCV_RECORD_REQUIRED')
    archive = archives[0]
    require(re.fullmatch(r'[1-9][0-9]*', expected_vid) is not None, 'VARIATION_ID_INVALID')
    require(archive.get('VariationID') == expected_vid, 'VARIATION_ID_MISMATCH')
    require(archive.get('Accession') == 'VCV' + expected_vid.zfill(9), 'VCV_ACCESSION_MISMATCH')
    require(re.fullmatch(r'[1-9][0-9]*', archive.get('Version', '')) is not None, 'VCV_VERSION_REQUIRED')
    require(archive.get('RecordType') == 'classified', 'CLASSIFIED_RECORD_REQUIRED')
    classified = only(archive, 'ClassifiedRecord', 'CLASSIFIED_RECORD_REQUIRED')
    require(classified.find('Haplotype') is None and classified.find('Genotype') is None,
            'SINGLE_ALLELE_RECORD_REQUIRED')
    simple = only(classified, 'SimpleAllele', 'SINGLE_ALLELE_RECORD_REQUIRED')
    require(simple.get('VariationID') == expected_vid, 'SIMPLE_ALLELE_ID_MISMATCH')
    require(allele.get('assembly') == 'GRCh38'
            and type(allele.get('position')) is int and allele['position'] > 0
            and re.fullmatch(r'[ACGT]', allele.get('reference', '')) is not None
            and re.fullmatch(r'[ACGT]', allele.get('alternate', '')) is not None,
            'DRAFT_NATIVE_SNV_SCOPE_REQUIRED')
    locations = [n for n in simple.findall('Location/SequenceLocation')
                 if n.get('Assembly') == allele['assembly'] and n.get('Chr') == allele.get('contig')
                 and n.get('positionVCF') == str(allele['position'])
                 and n.get('referenceAlleleVCF') == allele['reference']
                 and n.get('alternateAlleleVCF') == allele['alternate']]
    require(len(locations) == 1, 'REFERENCE_ALLELE_MISMATCH_OR_AMBIGUOUS')
    prefix = '/ClinVarResult-Set[1]' if root.tag == 'ClinVarResult-Set' else ''
    base = prefix + '/VariationArchive[1]/ClassifiedRecord[1]'
    statements = classified.findall('ClinicalAssertionList/ClinicalAssertion')
    require(0 < len(statements) <= MAX_ASSERTIONS, 'ASSERTION_COUNT_INVALID')
    assertions = []
    seen = set()
    for index, statement in enumerate(statements, 1):
        accession = only(statement, 'ClinVarAccession', 'ONE_SCV_ACCESSION_REQUIRED')
        scv = accession.get('Accession', '') + '.' + accession.get('Version', '')
        require(re.fullmatch(r'SCV[0-9]+\.[1-9][0-9]*', scv) is not None, 'SCV_VERSION_REQUIRED')
        require(scv.split('.')[0] not in seen, 'DUPLICATE_SCV_ACCESSION')
        seen.add(scv.split('.')[0])
        classification = only(statement, 'Classification', 'CLASSIFICATION_REQUIRED')
        require(len(classification.findall('GermlineClassification')) <= 1
                and len(classification.findall('ReviewStatus')) <= 1, 'CLASSIFICATION_AMBIGUOUS')
        path = f'{base}/ClinicalAssertionList[1]/ClinicalAssertion[{index}]'
        comments, citations = [], []
        for node, locator in walk(statement, path):
            if node.tag == 'Comment':
                comments.append(dict(text=text(node), attributes=dict(node.attrib),
                                     scope=citation_scope(locator), xpath=locator))
            elif node.tag == 'Citation':
                ids = [dict(source=n.get('Source', ''), value=text(n)) for n in node.findall('ID')]
                citations.append(dict(scope=citation_scope(locator), xpath=locator,
                                      identifiers=ids, original=tree(node)))
        assertions.append(dict(scv=scv, accession=dict(accession.attrib), xpath=path,
            submissionIdentity=[tree(n) for n in statement.findall('ClinVarSubmissionID')],
            submittedAllele=[tree(n) for n in statement.findall('SimpleAllele')],
            recordStatus=statement.findtext('RecordStatus', ''),
            classification=classification.findtext('GermlineClassification', ''),
            reviewStatus=classification.findtext('ReviewStatus', ''),
            dateLastEvaluated=classification.get('DateLastEvaluated', ''),
            classificationDetail=tree(classification), comments=comments, citations=citations,
            condition=[tree(n) for n in statement.findall('TraitSet')],
            assertionAttributes=[tree(n) for n in statement.findall('AttributeSet')],
            observations=[tree(n) for n in statement.findall('ObservedInList')]))
    return dict(vcv=archive.get('Accession') + '.' + archive.get('Version'),
        variationId=expected_vid, recordStatus=archive.findtext('RecordStatus', ''),
        archiveAttributes=dict(archive.attrib), allele=allele, nativeLocation=dict(locations[0].attrib),
        geneSymbols=[n.get('Symbol') for n in simple.findall('GeneList/Gene')],
        hgvs=[tree(n) for n in simple.findall('HGVSlist/HGVS')],
        aggregateClassifications=[tree(n) for n in classified.findall('Classifications')],
        # These conditions are VCV-level, never copied into individual SCVs.
        aggregateConditions=[tree(n) for n in classified.findall('Classifications/GermlineClassification/TraitSet')],
        assertions=assertions)


def bind_assertion(original, full, archive_status, genes):
    if full is None:
        return ['EXACT_SCV_VERSION_NOT_PRESENT']
    reasons = []
    if archive_status != 'current' or full['recordStatus'] != 'current':
        reasons.append('SOURCE_RECORD_NOT_CURRENT')
    for field in ('classification', 'reviewStatus'):
        if original.get(field) != full[field]:
            reasons.append('SOURCE_' + field.upper() + '_MISMATCH')
    try:
        if iso_date(original.get('dateLastEvaluated')) != iso_date(full['dateLastEvaluated']):
            reasons.append('SOURCE_DATE_MISMATCH')
    except (ValueError, TypeError):
        reasons.append('SOURCE_DATE_INVALID')
    if original.get('geneSymbol') not in genes:
        reasons.append('SOURCE_GENE_MISMATCH')
    return reasons


def prepare(manifest_path, manifest_pin, output):
    manifest_path, output = Path(manifest_path), Path(output)
    require(not output.exists(), 'OUTPUT_EXISTS_REFUSE_OVERWRITE')
    total = 0
    inputs = []

    def read(path, pin):
        nonlocal total
        require(re.fullmatch(r'[0-9a-f]{64}', pin or '') is not None, 'RAW_SHA256_REQUIRED')
        with path.open('rb') as stream:
            data = stream.read(MAX_FILE + 1)
        total += len(data)
        require(len(data) <= MAX_FILE and total <= MAX_TOTAL, 'INPUT_SIZE_LIMIT')
        require(sha(data) == pin, 'RAW_SHA256_MISMATCH')
        inputs.append(dict(logicalName=path.name, bytes=len(data), rawSha256=pin))
        return data

    manifest = parse_json(read(manifest_path, manifest_pin))
    require(manifest.get('schemaVersion') == 'missense-full-reference-evidence-manifest-v1', 'MANIFEST_VERSION_INVALID')
    root = manifest_path.resolve().parent
    draft_bytes = read(root / manifest['referenceDraft']['path'], manifest['referenceDraft']['rawSha256'])
    draft = parse_json(draft_bytes)
    require(draft.get('schemaVersion') == 'missense-reference-audit-draft-v1'
            and draft.get('reviewStatus') == 'UNREVIEWED' and draft.get('draft') is True,
            'UNREVIEWED_REFERENCE_DRAFT_REQUIRED')
    require(draft.get('dataClass') in ('SYNTHETIC', 'PUBLIC_REFERENCE'), 'DATA_CLASS_UNSUPPORTED')
    references = draft['references']
    require(isinstance(references, list) and len(references) <= MAX_REFERENCES, 'REFERENCE_COUNT_INVALID')
    require(len({r['referenceDraftId'] for r in references}) == len(references), 'DUPLICATE_REFERENCE_DRAFT_ID')
    require(sum(len(r['assertions']) for r in references) <= MAX_ASSERTIONS, 'DRAFT_ASSERTION_COUNT_INVALID')
    sources = manifest['sources']
    require(isinstance(sources, list) and len(sources) <= MAX_REFERENCES, 'SOURCE_COUNT_INVALID')
    require(len({s['variationId'] for s in sources}) == len(sources), 'DUPLICATE_SOURCE_VARIATION_ID')
    require({s['variationId'] for s in sources} <= {r['variationId'] for r in references}, 'UNBOUND_SOURCE')
    cached = {}
    descriptors, source_bytes = [], []
    for index, source in enumerate(sources):
        vid = source['variationId']
        candidates = [r for r in references if r['variationId'] == vid]
        require(all(r['allele'] == candidates[0]['allele'] for r in candidates), 'DRAFT_VARIATION_ALLELE_CONFLICT')
        url = urllib.parse.urlsplit(source['url'])
        query = urllib.parse.parse_qs(url.query)
        requested = query.get('id', [''])
        require(url.scheme == 'https' and url.netloc == 'eutils.ncbi.nlm.nih.gov'
                and url.path == '/entrez/eutils/efetch.fcgi'
                and query.get('db') == ['clinvar'] and query.get('rettype') == ['vcv']
                and len(requested) == 1 and re.fullmatch(r'VCV' + vid.zfill(9) + r'(?:\.[1-9][0-9]*)?', requested[0]),
                'OFFICIAL_VCV_SOURCE_URL_REQUIRED')
        raw = read(root / source['path'], source['rawSha256'])
        full = parse_vcv(raw, vid, candidates[0]['allele'])
        require('.' not in requested[0] or requested[0] == full['vcv'], 'REQUESTED_VCV_VERSION_MISMATCH')
        source_id = 'clinvar-full-' + full['vcv'] + '-' + sha(raw)[:16]
        full['sourceId'] = source_id
        cached[vid] = full
        name = f'source-{index:03d}.xml'
        descriptors.append(dict(path=name, rawSha256=sha(raw), format='BYTES', sourceDigestKind='RAW_BYTES',
            sourceRecord=dict(sourceId=source_id, version=full['vcv'], url=source['url'], sha256=sha(raw))))
        source_bytes.append((name, raw))
    rows = []
    for reference in references:
        full = cached.get(reference['variationId'])
        observed = {} if full is None else {s['scv']: s for s in full['assertions']}
        bindings = []
        seen = set()
        for assertion in reference['assertions']:
            original = assertion['sourceAssertion']
            scv = original.get('scv', '')
            require(re.fullmatch(r'SCV[0-9]+\.[1-9][0-9]*', scv) is not None, 'DRAFT_SCV_VERSION_REQUIRED')
            require(scv not in seen, 'DUPLICATE_DRAFT_SCV')
            seen.add(scv)
            reasons = ['FULL_SOURCE_NOT_PROVIDED'] if full is None else bind_assertion(
                original, observed.get(scv), full['recordStatus'], full['geneSymbols'])
            detail = observed.get(scv)
            bindings.append(dict(assertionDraftId=assertion['assertionDraftId'], sourceAssertion=original,
                sourceBindingStatus='EXACT_VERSION_BOUND' if not reasons else 'UNBOUND', reasons=reasons,
                fullAssertion=detail if not reasons else None,
                availableSameAccessionVersions=[s for s in observed if s.split('.')[0] == scv.split('.')[0]],
                conditionCompatibility='NOT_EVALUATED', independence='NOT_EVALUATED', scientificAdoption=False))
        rows.append(dict(referenceDraftId=reference['referenceDraftId'], variationId=reference['variationId'],
            transcript=reference['transcript'], allele=reference['allele'],
            originalAnnotation=reference['annotation'], fullSource=full, assertionBindings=bindings,
            reviewStatus='UNREVIEWED'))
    bound = sum(b['sourceBindingStatus'] == 'EXACT_VERSION_BOUND' for r in rows for b in r['assertionBindings'])
    count = sum(len(r['assertionBindings']) for r in rows)
    packet = dict(schemaVersion='missense-full-reference-evidence-v1', draft=True, reviewStatus='UNREVIEWED',
        dataClass=draft['dataClass'], draftId=draft['draftId'],
        draftRawSha256=sha(draft_bytes), scientificAdoption=False, runtimeExecution='NOT_RUN',
        status='EXTRACTED' if bound == count else 'PARTIAL', boundAssertions=bound, totalDraftAssertions=count,
        references=rows, limitations=['SOURCE_BINDING_IS_NOT_CLINICAL_APPROVAL',
            'PUBLISHED_COMMENTS_ARE_NOT_INDEPENDENT_REASSESSMENT',
            'CONDITION_MAPPING_AND_TRANSCRIPT_EQUIVALENCE_NOT_INFERRED',
            'NEW_SCVS_NOT_SUBSTITUTED_FOR_FROZEN_ASSERTIONS', 'POLICY_AND_RNA_REVIEW_STILL_REQUIRED'])
    output.mkdir()

    def write(name, value):
        raw = value if isinstance(value, bytes) else (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()
        require(len(raw) <= MAX_FILE, 'OUTPUT_SIZE_LIMIT')
        with (output / name).open('xb') as stream:
            stream.write(raw)
        return dict(path=name, bytes=len(raw), rawSha256=sha(raw))

    artifacts = [write(name, raw) for name, raw in source_bytes]
    artifacts.append(write('reference-evidence.UNREVIEWED.json', packet))
    artifacts.append(write('review-evidence-sources.json', dict(
        schemaVersion='missense-review-evidence-sources-v1', reviewStatus='UNREVIEWED',
        scientificAdoption=False, reviewEvidence=descriptors)))
    write('manifest.json', dict(schemaVersion='missense-full-reference-evidence-artifacts-v1',
                               inputs=inputs, outputs=artifacts))
    return packet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        result = prepare(args.manifest, args.manifest_sha256, args.output)
        print(json.dumps({k: result[k] for k in ('status', 'boundAssertions', 'totalDraftAssertions', 'scientificAdoption')}))
        return 0 if result['status'] == 'EXTRACTED' else 2
    except (ValueError, OSError, ET.ParseError, KeyError, TypeError) as error:
        print(json.dumps(dict(status='BLOCKED', reason=str(error), scientificAdoption=False)))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
