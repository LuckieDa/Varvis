"""Merge bounded proposals; derive assertions again from complete pinned sources.

No model/network calls and no evidence scoring. Partial extraction is not an
absence claim. Only explicitly owned main-text/table cases enter the existing
source verifier; unsupported attachment ownership remains a visible gap.
"""
from copy import deepcopy
import re

from extract_literature_cases import finding_state
from model_literature_contract import equivalent_field, FIELDS
from qualify_literature_cases import (source_case, read_record, CASE_TITLE,
    shared_field_matches, matches, FAMILY_SENTENCES, sentences)
from literature_case_structure import (tables, paragraph_owned, primary_origin, structural_assertions,
                                      VERSION as STRUCTURE_VERSION)

# Wire contract remains compatible with the existing Java source reader.
# Structural reader identity is additive and covered by implementation hashes.
VERSION = 'denovo-bundle-case-qualification-1.0.0'


def qualify(source, reviews, allele_sources=()):
    document = source['main']
    pin = document['sourceSha256']
    units = {u['unitId']: u for u in document['units']}
    native = tables(document)
    groups = {}
    for review in reviews:
        if review['pmcid'] != source['pmcid'] or review['bundleSha256'] != source['bundleSha256']:
            raise ValueError('DENOVO_BUNDLE_QUALIFICATION_SOURCE_MISMATCH')
        for row in review['cases']:
            groups.setdefault(row['caseLabel'], []).append(row)
    records = [read_record(*a) for a in allele_sources]
    candidates = []
    for label, rows in sorted(groups.items()):
        originals = [n for n in native if n['caseLabel'] == label]
        fields = [deepcopy(f) for n in originals for f in n['fields'] if f['findingState'] == 'REPORTED']
        issues = [i for n in originals for i in n['issues'] if i.startswith('LITERATURE_FIELD_CONFLICT:')]
        candidate = {'observationId': pin + ':bundle:' + label, 'caseLabel': label,
                     'caseCitation': rows[0]['caseCitation'], 'fields': fields, 'issues': issues}
        limitations = []
        negations = []
        from supplement_case_review import scope as supplement_scope
        attachment_scope = supplement_scope(source, label)

        def reject(code):
            issues.append('LITERATURE_FIELD_CONFLICT:BUNDLE_' + code)

        def defer(field, code):
            # Preserve source text, but do not add an unverified value to fields
            # consumed by phenotype matching or turn it into a source conflict.
            limitations.append({'reasonCode': 'LITERATURE_FIELD_NOT_VERIFIED:BUNDLE_' + code,
                                'disposition': 'NOT_ADOPTED', 'field': deepcopy(field)})

        for row in rows:
            if row['caseOriginProposal'] != 'AUTHOR_COHORT':
                issues.append('LITERATURE_CASE_ORIGIN_NOT_PRIMARY_OR_UNRESOLVED')
            if not primary_origin(document, row):
                issues.append('LITERATURE_PRIMARY_COHORT_NOT_ESTABLISHED')
            for field in row['fields']:
                citation = field['citation']; name = field['field']; value = field['sourceValue']
                unit = units.get(citation['unitId'])
                if unit is None:
                    if citation['unitId'] in attachment_scope['ownedUnitIds']:
                        defer(field, 'SUPPLEMENT_FIELD_SEMANTICS_PENDING')
                    else:
                        reject('ATTACHMENT_CASE_ATTRIBUTION_NOT_VERIFIED')
                    continue
                if field.get('issues'):
                    reject('VALUE_SEMANTICS_NOT_VERIFIED')
                    continue
                title = CASE_TITLE.fullmatch(unit['sections'][-1]) if unit['sections'] else None
                owned = bool(title and title[1] == label and unit['kind'] in {'BODY_PARAGRAPH', 'CAPTION'})
                owned = owned or paragraph_owned(document, unit, label)
                table = any(f['field'] == name and f['sourceValue'] == value and
                            f.get('citation', {}).get('unitId') == citation['unitId'] for n in originals for f in n['fields'])
                if not (owned or table or shared_field_matches(document, label, field)):
                    defer(field, 'CASE_ATTRIBUTION_UNRESOLVED')
                    continue
                if unit['text'].count(citation['quote']) != 1 or value not in citation['quote']:
                    reject('CITATION_OR_VALUE_INVALID')
                    continue
                if name == 'reportedFamilyHistory' and owned and field['subject'] in {'PROBAND', 'FAMILY', 'BOTH_PARENTS'}:
                    verified = matches(document, [unit], FAMILY_SENTENCES)
                    if any(value in m['citation']['quote'] for m in verified):
                        old = [f for f in fields if f['field'] == name]
                        if any(f['sourceValue'].strip().lower() != 'none' for f in old):
                            reject('CONFLICTING_REPORTEDFAMILYHISTORY')
                        # Recomputed again below from the complete source. The
                        # model's free-form family value is not a scoring fact.
                        continue
                if field['subject'] != 'PROBAND':
                    reject('SUBJECT_NOT_VERIFIED')
                    continue
                if name not in FIELDS:
                    if name == 'reportedPhenotypeNegation':
                        negations.append(citation['quote'])
                    continue
                if name == 'reportedPhenotype' and not table:
                    subject = r'(?:The (?:proband|patient)|(?:Case|Patient|Individual) ' + re.escape(label) + r')'
                    clauses = re.findall(subject + r' (?:had|presented with|exhibited) [^.!?]+', unit['text'])
                    if not any(value in c and not re.search(r'\b(?:no|not|without|may|possible|mother|father|parent|sibling|brother|sister)\b', c, re.I) for c in clauses):
                        containing = [text for unused, unused_end, text in sentences(unit['text']) if value in text]
                        concern = r'\b(?:no|not|never|without|absent|normal|negative|denied|may|possible|mother|father|parents?|sibling|brother|sister)\b'
                        if not containing or any(re.search(concern, text, re.I) for text in containing):
                            reject('PHENOTYPE_SUBJECT_OR_NEGATION_UNRESOLVED')
                        else:
                            defer(field, 'PHENOTYPE_LANGUAGE_UNSUPPORTED')
                        continue
                old = [f for f in fields if f['field'] == name]
                if any(equivalent_field(name, f['sourceValue'], value) for f in old):
                    continue
                if old and name != 'reportedPhenotype':
                    reject('CONFLICTING_' + name.upper())
                fields.append({'field': name, 'sourceValue': value, 'findingState': finding_state(value), 'citation': citation})
        # Compare after merging all proposals so citation/response ordering
        # cannot hide a contradiction with a phenotype used by disease matching.
        for field in fields:
            if (field['field'] == 'reportedPhenotype'
                    and any(re.search(r'(?<!\w)' + re.escape(field['sourceValue']) + r'(?!\w)', text, re.I)
                            for text in negations)):
                reject('CONFLICTING_REPORTEDPHENOTYPE')
        # Normalize equivalent duplicate table facts without losing originals.
        unique = []
        for field in fields:
            previous = [f for f in unique if f['field'] == field['field']]
            if any(equivalent_field(field['field'], f['sourceValue'], field['sourceValue']) for f in previous):
                continue
            if previous and field['field'] != 'reportedPhenotype':
                reject('CONFLICTING_' + field['field'].upper())
            unique.append(field)
        candidate['fields'] = unique
        candidate['candidateProvenance'] = rows
        candidate['fieldLimitations'] = limitations
        candidates.append(candidate)
    cases = []
    for candidate in candidates:
        result = source_case(document, candidates, candidate, records)
        result['issues'].extend(i for i in candidate['issues']
                                if not i.startswith('LITERATURE_FIELD_CONFLICT:'))
        structural_assertions(source, candidate, native, result)
        scope = result.get('caseSection', '')
        owned = [u for u in units.values() if scope and u['unitId'].startswith(scope + '/')]
        # Previously reported cases cannot silently become a new event. The
        # evaluator separately checks cross-paper/event independence.
        overlap = [u['unitId'] for u in owned if re.search(
            r'\b(?:previously (?:reported|described|published)|reported (?:elsewhere|previously)|described by)\b', u['text'], re.I)]
        if overlap:
            result['issues'].append('LITERATURE_FIELD_CONFLICT:BUNDLE_PRIOR_PUBLICATION_OVERLAP_UNRESOLVED')
            result['overlapUnits'] = overlap
        history = matches(document, [u for u in owned if u['kind'] == 'BODY_PARAGRAPH'], FAMILY_SENTENCES)
        if history:
            result['familyHistory'] = 'REPORTED_NONE'
            result['familyHistoryCitations'] = history
        result['candidateProvenance'] = candidate['candidateProvenance']
        if candidate['fieldLimitations']:
            result['fieldLimitations'] = candidate['fieldLimitations']
            result['pendingChecks'].extend(sorted({f['reasonCode'] for f in candidate['fieldLimitations']}))
        result['extractionMethod'] = VERSION
        result['issues'] = sorted(set(result['issues']))
        cases.append(result)
    return {'schemaVersion': 'denovo-literature-source-assertions-v1', 'methodVersion': VERSION,
            'structuralReaderVersion': STRUCTURE_VERSION,
            'pmcid': document['pmcid'], 'pmid': document['identifiers'].get('pmid'), 'sourceSha256': pin,
            'cases': cases, 'issues': [] if cases else ['DENOVO_BUNDLE_NO_QUALIFIED_CASE_CANDIDATES_NOT_ABSENCE'],
            'extractionCoverage': {'status': 'PARTIAL', 'meaning': 'Only supplied targets processed; full original case context rechecked. Unprocessed cases are not absence.',
                'processedTargets': [r['workUnit']['targetUnitIds'] for r in reviews],
                'proposals': [r['extractionCoverageProposal'] for r in reviews],
                'supplementCoverage': source['coverage'], 'attachments': source['attachments']},
            'modelCalls': 0, 'networkCalls': 0, 'clinicalAdoption': 'NONE',
            'evaluationStatus': 'NOT_RUN_SOURCE_FACTS_ONLY'}
