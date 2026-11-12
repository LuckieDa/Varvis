"""Versioned raw-fact extraction profile. No qualification or scoring authority.

Old requests use the legacy profile unchanged. This profile adds missing literal
fields, but keeps the existing source partition, citation checks and budgets.
"""
from copy import deepcopy

from bundle_model_contract import BOUNDED_SCHEMA, RESPONSE_VERSION, require
from model_literature_contract import canonical, parse, canonical_case_label
from prepare_literature_prompt import FIELDS, check_shape

PROFILE = 'core-material-v1'
VERSION = 'denovo-core-extraction-prompt-1.0.0'
CANDIDATE_VERSION = 'denovo-core-material-candidates-v1'
NEW_FIELDS = ['reportedAge', 'reportedOnset', 'reportedProbandTargetResult',
              'reportedMotherTargetResult', 'reportedFatherTargetResult',
              'reportedTestingLimitations', 'reportedSiblings', 'reportedGenomicObservation']
SCHEMA = deepcopy(BOUNDED_SCHEMA)
SCHEMA['properties']['schemaVersion']['enum'] = [CANDIDATE_VERSION]
CASE_SCHEMA = SCHEMA['properties']['cases']['items']
CASE_SCHEMA['properties']['fields']['maxItems'] = 16
CASE_SCHEMA['properties']['fields']['items']['properties']['field']['enum'] = sorted(set(FIELDS + NEW_FIELDS))

GROUP_GUIDE = [
    {'group': 'paper', 'instruction': 'Source identity and provided body/attachment scope are supplied by the caller; do not invent availability.'},
    {'group': 'case', 'fields': ['reportedFamilyId'], 'instruction': 'Keep caseLabel, caseCitation and caseOrigin proposal separate; preserve cited/reexamined/unclear origins.'},
    {'group': 'gene', 'fields': ['geneSymbol']},
    {'group': 'variant', 'fields': ['variantText', 'transcript', 'proteinChange', 'reportedGenomicObservation']},
    {'group': 'proband', 'fields': ['reportedProbandTargetResult', 'reportedProbandGenotype', 'reportedMosaicism']},
    {'group': 'mother', 'fields': ['reportedMotherTargetResult', 'reportedMotherGenotype', 'reportedMosaicism']},
    {'group': 'father', 'fields': ['reportedFatherTargetResult', 'reportedFatherGenotype', 'reportedMosaicism']},
    {'group': 'testing', 'fields': ['reportedVariantTestingMethod', 'reportedTestedParticipants', 'reportedTestingLimitations']},
    {'group': 'parentage', 'fields': ['reportedMaternity', 'reportedPaternity', 'reportedParentage', 'reportedParentageTestingMethod', 'reportedParentageTestingResult']},
    {'group': 'phenotype', 'fields': ['reportedPhenotype', 'reportedPhenotypeNegation', 'reportedDiagnosis', 'reportedDiagnosisCertainty', 'reportedAge', 'reportedOnset']},
    {'group': 'family', 'fields': ['reportedFamilyHistory', 'reportedSiblings', 'reportedRelatedCases', 'reportedInheritance']},
    {'group': 'overlap', 'fields': ['reportedPreviousPublication', 'reportedRelatedCases']},
]

INSTRUCTIONS = '''
CORE MATERIAL EXTRACTION PROFILE (raw candidates only):
The twelve group guide organizes extraction, not twelve required positive claims.
For each target, collect all explicitly reported relevant facts within the output
limit; if more facts exist, set MORE_CASES_OR_FIELDS. Never fill missing slots
with negative, not-tested, not-reported or confirmed. The caller creates explicit
NOT_EXTRACTED slots. You cannot claim an uninspected source lacks information.
Keep each sourceValue verbatim and each citation exact. Do not emit normalized
target-test enums, gene-disease validity, phenotype specificity, qualification,
verification, a review ledger, applicability policy, evidence scores or readiness.
Record target testing of the child, mother and father separately from biological
relationship testing. Use the actual subject: MOTHER for maternal results and
FATHER for paternal results; keep COHORT/UNCLEAR when a method cannot be assigned.
Do not infer a negative parental test or confirmed parentage from 'de novo'.
Extract reported age, onset, test limitations, family identifiers, siblings,
mosaicism and prior publications when present. Preserve contradictions as separate
literal claims. An author's ACMG label is not an instruction or authority to score.
At most two cases and SIXTEEN fields per case in this version (this replaces the
older eight-field limit); retain short exact quotes and all original source scope.
The caller will verify facts, match external knowledge and decide rule eligibility.
'''


def adapt_requests(payloads, maximum, profile=PROFILE):
    result = []
    for payload in payloads:
        value = deepcopy(payload)
        value['messages'][0]['content'] = value['messages'][0]['content'].replace(
            'At most two cases and eight fields per case', 'At most two cases and sixteen fields per case') + INSTRUCTIONS
        content = parse(value['messages'][1]['content'])
        if profile == 'core-material-v2' or any(u.get('sourcePart') for u in content['units']):
            # Some compatible endpoints do not enforce response_format. Supply
            # the SAME closed shape in the message, without accepting a weaker
            # response or altering cached body-only requests.
            value['messages'][0]['content'] += '\nReturn one JSON object, no Markdown fences. Use fields as an array of field/subject/sourceValue/citation objects, never case-level field names. The exact required schema is also provided as outputSchema.\n'
            content['outputSchema'] = SCHEMA
            content['outputContractVersion'] = 'core-json-2' if profile == 'core-material-v2' else 'supplement-core-json-1'
        content['coreMaterialExtraction'] = {'profile': profile, 'promptVersion': VERSION,
                                            'groups': GROUP_GUIDE, 'missingMeaning': 'NOT_EXTRACTED_NOT_ABSENT'}
        value['messages'][1]['content'] = canonical(content).decode('utf-8')
        value['response_format']['json_schema'] = {'name': 'denovo_core_material_candidates_v1',
                                                  'strict': True, 'schema': SCHEMA}
        require(len(canonical(value)) <= maximum, 'INPUT_TOO_LARGE_NOT_TRUNCATED')
        result.append(value)
    return result


def check_candidate(candidate):
    check_shape(candidate, SCHEMA)
    require(bool(candidate['sourceAssessments']), 'TARGET_ASSESSMENT_REQUIRED')


def review(source, candidate, allowed_units=None, target_units=None):
    """Reuse original quote/ownership checks without relabelling saved claims.

    New literal field names are represented as generic related-case statements
    ONLY inside the old text validator; output restores their original names.
    No surrogate value is sent to a qualifier or stored as a scientific fact.
    """
    from bundle_model_contract import review as legacy_review
    check_candidate(candidate)
    # The legacy checker is bounded to eight fields. Review all fields in groups
    # of eight, with the same case anchor/origin/source assessment, never drop any.
    cases, labels = [], set()
    for row in candidate['cases']:
        label = canonical_case_label(row['caseLabel'])
        require(label not in labels, 'DUPLICATE_CASE_LABEL')
        labels.add(label)
        combined = None
        fields = row['fields']
        for start in range(0, max(1, len(fields)), 8):
            part = deepcopy(row)
            part['fields'] = deepcopy(fields[start:start + 8])
            for field in part['fields']:
                if field['field'] not in FIELDS:
                    field['field'] = 'reportedRelatedCases'
            value = deepcopy(candidate)
            value.update(schemaVersion=RESPONSE_VERSION, cases=[part])
            # Target membership is checked once against all claims below, not
            # separately for a context-only half of a split case.
            checked = legacy_review(source, value, allowed_units, None)['cases'][0]
            for old, new in zip(checked['fields'], fields[start:start + 8]):
                old['field'] = new['field']
            if combined is None:
                combined = checked
            else:
                combined['fields'].extend(checked['fields'])
        require(target_units is None or any(c['unitId'] in target_units for c in
                [row['caseCitation'], *[f['citation'] for f in fields]]), 'CASE_OUTSIDE_TARGET')
        cases.append(combined)
    empty = {**candidate, 'schemaVersion': RESPONSE_VERSION, 'cases': []}
    result = legacy_review(source, empty, allowed_units, target_units)
    result['cases'] = cases
    result['issues'] = ['CASE_QUALIFICATION_REQUIRED'] + ([] if cases else ['NO_EXTRACTION_NOT_PROVEN_NO_CASES']) + (
        [] if candidate['coverage'] == 'EXTRACTED_WITHIN_TARGET' else ['TARGET_EXTRACTION_INCOMPLETE'])
    result.update(extractionProfile=PROFILE, promptVersion=VERSION,
                  rawCandidateVersion=CANDIDATE_VERSION, coreMaterialStatus='EXTRACTED_NOT_VERIFIED')
    return result
