"""PS2/PM6 offline core-material contract. No production or model integration."""
import json
from pathlib import Path

VERSION = 'denovo-core-material-v1'


def string(enum=None):
    return {'type': 'string', **({'enum': enum} if enum else {'minLength': 1, 'maxLength': 32768})}


def array(items, minimum=0):
    return {'type': 'array', 'items': items, 'minItems': minimum, 'maxItems': 256}


def obj(properties):
    return {'type': 'object', 'properties': properties, 'required': list(properties), 'additionalProperties': False}


TEXT = string()
STRINGS = array(TEXT)
BOOL = {'type': 'boolean'}
ALLELE = obj({'assembly': string(['GRCh37', 'GRCh38']), 'contig': TEXT,
              'position': {'type': 'integer', 'minimum': 1},
              'reference': {'type': 'string', 'pattern': '^[ACGT]+$', 'maxLength': 4096},
              'alternate': {'type': 'string', 'pattern': '^[ACGT]+$', 'maxLength': 4096}})
TARGET = string(['PRESENT', 'ABSENT', 'QUALITY_FAILED', 'CONFLICT'])
MOSAIC = string(['REPORTED_PRESENT', 'REPORTED_ABSENT'])
RELATION = string(['CONFIRMED', 'ASSUMED_NOT_TESTED', 'ASSUMED_NOT_REPORTED',
                   'ASSUMED_NOT_CONFIRMED', 'INCONCLUSIVE', 'EXCLUDED'])
SUBJECTS = ['PROBAND', 'MOTHER', 'FATHER', 'BOTH_PARENTS', 'FAMILY', 'COHORT', 'PAPER', 'CONTEXT', 'UNCLEAR']
CITATION = obj({'sourceId': TEXT, 'kind': string(['JATS_UNIT', 'JSON_POINTER', 'TEXT']),
                'locator': TEXT, 'quote': TEXT})
SCAN = obj({'sourceId': TEXT, 'kind': string(['JATS_UNIT', 'JSON_POINTER', 'TEXT']), 'locator': TEXT})


def fact(value):
    return obj({'caseKey': TEXT, 'subject': string(SUBJECTS),
                'rawValue': {'anyOf': [TEXT, {'type': 'null'}]},
                'value': {'anyOf': [value, {'type': 'null'}]},
                'origin': string(['SOURCE_OBSERVATION', 'MODEL_PROPOSAL', 'PROGRAM_DERIVED', 'REVIEWED_ASSESSMENT']),
                'availability': string(['REPORTED', 'NOT_EXTRACTED', 'NOT_REPORTED_IN_CHECKED_SCOPE',
                                        'SOURCE_UNAVAILABLE', 'NOT_APPLICABLE']),
                'verification': string(['VERIFIED', 'UNVERIFIED', 'CONFLICT', 'UNSUPPORTED']),
                'citations': array(CITATION), 'checkedLocations': array(SCAN), 'issues': STRINGS})


# This is the field dictionary, not a list of fields that every paper must report.
GROUPS = {
    'paper': {'identity': TEXT, 'availability': string(['MAIN_AVAILABLE', 'PARTIAL', 'UNAVAILABLE']), 'supplements': STRINGS},
    'case': {'label': TEXT, 'origin': string(['AUTHOR_COHORT', 'CITED_ONLY', 'PREVIOUSLY_REPORTED_REEXAMINED', 'UNCLEAR']), 'familyId': TEXT},
    'gene': {'symbol': TEXT},
    'variant': {'representations': array(TEXT, 1), 'resolvedAllele': ALLELE},
    'proband': {'targetResult': TARGET, 'genotype': TEXT, 'mosaicism': MOSAIC},
    'mother': {'targetResult': TARGET, 'mosaicism': MOSAIC},
    'father': {'targetResult': TARGET, 'mosaicism': MOSAIC},
    'testing': {'method': TEXT, 'participants': array(string(SUBJECTS), 1), 'qualifiedForTarget': BOOL, 'limitations': STRINGS},
    'parentage': {'maternity': RELATION, 'paternity': RELATION, 'method': TEXT},
    'phenotype': {'positive': array(TEXT, 1), 'negative': STRINGS, 'diagnosis': TEXT, 'diagnosisCertainty': TEXT, 'age': TEXT, 'onset': TEXT},
    'family': {'history': TEXT, 'compatibility': string(['COMPATIBLE', 'UNRESOLVED', 'CONFLICT', 'SPECIAL_MODEL']), 'siblings': STRINGS},
    'overlap': {'eventId': TEXT, 'independence': string(['UNIQUE_WITHIN_CHECKED_SCOPE', 'POSSIBLE_OVERLAP', 'KNOWN_DUPLICATE']), 'previousReports': STRINGS},
}
CONTEXT = {
    'allele': ALLELE, 'hgncId': TEXT, 'diseaseId': TEXT,
    'inheritance': string(['AD', 'AR', 'X_LINKED', 'OTHER']),
    'variantScope': string(['SMALL_SEQUENCE_VARIANT', 'OTHER']),
    'geneDiseaseApplicability': string(['APPLICABLE', 'NOT_APPLICABLE', 'UNRESOLVED']),
    'phenotypeCategory': string(['HIGHLY_SPECIFIC', 'CONSISTENT', 'CONSISTENT_HIGH_HETEROGENEITY', 'NOT_CONSISTENT']),
    'policyId': TEXT, 'policyVersion': TEXT,
    'expertPolicyStatus': string(['GENERAL_ALLOWED', 'APPLICABLE_IMPLEMENTED', 'APPLICABLE_UNSUPPORTED', 'UNRESOLVED']),
    'allowAssumedParentage': BOOL,
}
EXTENSIONS = {
    'recessive': {'secondAlleles': STRINGS, 'classification': TEXT, 'phase': string(['TRANS', 'CIS', 'UNKNOWN']), 'classificationBasis': TEXT},
    'xlinked': {'eventPerson': TEXT, 'sex': TEXT, 'pedigree': TEXT, 'carrierStatus': TEXT, 'affectedStatus': TEXT},
    'mosaic': {'individuals': STRINGS, 'tissuesAndAssays': TEXT, 'alleleFractions': TEXT, 'relationships': TEXT, 'eventGrouping': TEXT},
}


# Strict reported observations. Completeness and scientific eligibility remain
# separate: this object never carries caller-supplied qualification flags.
TARGET_EVENT = string(['PRESENT', 'ABSENT', 'UNKNOWN', 'CONFLICT'])
METHOD_EVENT = string(['SANGER', 'TARGETED_SEQUENCING', 'EXOME', 'GENOME', 'UNKNOWN'])
RELATION_EVENT = string(['CONFIRMED', 'ASSUMED_NOT_TESTED', 'ASSUMED_NOT_REPORTED', 'INCONCLUSIVE', 'EXCLUDED'])
PARENTAGE_METHOD = string(['SNP_PANEL', 'STR_PANEL', 'SEQUENCING_KINSHIP', 'NOT_TESTED', 'NOT_REPORTED', 'UNKNOWN'])
SPECIAL_EVENT = obj({
    'schemaVersion': {'const': 'denovo-special-event-v1'},
    'kind': string(['CONSTITUTIONAL', 'MATERNAL_ORIGIN', 'SIBLING_RECURRENCE', 'POSTZYGOTIC']),
    'familyId': TEXT, 'eventId': TEXT, 'personId': TEXT, 'probandId': TEXT,
    'sex': string(['MALE', 'FEMALE', 'UNKNOWN']),
    'affectedStatus': string(['AFFECTED', 'UNAFFECTED', 'UNKNOWN']),
    'ploidy': string(['HAPLOID', 'DIPLOID', 'UNKNOWN']),
    'genotype': string(['HEMIZYGOUS_ALT', 'HETEROZYGOUS', 'HOMOZYGOUS_ALT', 'UNKNOWN']),
    'parentalAvailability': string(['AVAILABLE', 'UNAVAILABLE', 'UNKNOWN']),
    'otherAffectedMales': string(['NONE_IN_CHECKED_SCOPE', 'PRESENT', 'UNKNOWN']),
    'trio': obj({'personId': TEXT, 'motherId': TEXT, 'fatherId': TEXT,
        'subjectTarget': TARGET_EVENT, 'motherTarget': TARGET_EVENT, 'fatherTarget': TARGET_EVENT,
        'maternity': RELATION_EVENT, 'paternity': RELATION_EVENT,
        'maternityMethod': PARENTAGE_METHOD, 'paternityMethod': PARENTAGE_METHOD,
        'method': METHOD_EVENT, 'targetCoverage': string(['EXPLICIT_TARGET', 'UNKNOWN']),
        'detectionCapability': string(['ADEQUATE', 'UNKNOWN', 'INADEQUATE']),
        'quality': string(['VALID', 'UNKNOWN', 'FAILED', 'CONFLICT'])}),
    'partner': obj({'status': string(['INDEPENDENT_P_LP', 'NONE_IN_CHECKED_SCOPE', 'UNRESOLVED', 'CIRCULAR']),
        'allele': {'anyOf': [ALLELE, {'type': 'null'}]}, 'phase': string(['TRANS', 'CIS', 'UNKNOWN']),
        'sourceRefs': array({'type': 'string', 'pattern': '^[a-f0-9]{64}#/.+$'})}),
    'siblings': {**STRINGS, 'maxItems': 100}, 'eventOrigin': string(['SHARED_EVENT', 'DISTINCT_ORIGIN_CONFIRMED', 'UNKNOWN']),
    'tissues': {**array(obj({'personId': TEXT, 'tissueId': TEXT, 'affected': BOOL,
        'altReads': {'type': 'integer', 'minimum': 0}, 'totalReads': {'type': 'integer', 'minimum': 0},
        'limit': {'type': 'number', 'minimum': 0, 'maximum': 1}, 'method': METHOD_EVENT,
        'quality': string(['HIGH_CONFIDENCE', 'UNKNOWN', 'FAILED', 'CONFLICT'])})), 'maxItems': 20}})
# The event person's sex does not identify the transmitting child's sex.
SPECIAL_EVENT['properties']['probandSex'] = string(['MALE', 'FEMALE', 'UNKNOWN'])
OBSERVATION_SCOPE = obj({'specimenId': TEXT, 'tissue': TEXT, 'timepoint': TEXT})
SPECIAL_EVENT['properties']['trio']['properties']['observationScopes'] = {
    'type': 'object', 'properties': {role: OBSERVATION_SCOPE for role in ('subject', 'mother', 'father')},
    'additionalProperties': False}


def build_schema():
    groups = obj({g: obj({k: fact(t) for k, t in fields.items()}) for g, fields in GROUPS.items()})
    extensions = {g: obj({k: fact(t) for k, t in fields.items()}) for g, fields in EXTENSIONS.items()}
    extensions['expert'] = {'type': 'object', 'maxProperties': 50, 'additionalProperties': fact(TEXT)}
    extension_schema = obj(extensions)
    extension_schema['properties']['specialEvent'] = fact(SPECIAL_EVENT)
    source = obj({'path': TEXT, 'sha256': {'type': 'string', 'pattern': '^[0-9a-f]{64}$'},
                  'role': string(['PAPER_PRIMARY', 'PAPER_ATTACHMENT', 'DERIVED_FACTS', 'EVALUATION_KNOWLEDGE', 'REVIEW_RECORD']),
                  'format': string(['JATS', 'JSON', 'TEXT', 'XML']), 'pmcid': {'anyOf': [TEXT, {'type': 'null'}]}})
    schema = obj({'schemaVersion': {'const': VERSION}, 'dataClass': string(['SYNTHETIC', 'PUBLIC_PAPER_RESEARCH']),
                  'caseKey': TEXT, 'sources': {'type': 'object', 'minProperties': 1, 'maxProperties': 128, 'additionalProperties': source},
                  'groups': groups, 'context': obj({k: fact(t) for k, t in CONTEXT.items()}),
                  'extensions': extension_schema,
                  'conditions': obj({'phaseRequired': fact(BOOL),
                                     'specialFamilyModel': fact(string(['NONE_IDENTIFIED_IN_CHECKED_SCOPE', 'MOSAIC', 'SIBLING_RECURRENCE', 'UNRESOLVED'])),
                                     'expertRequirements': array(obj({'path': TEXT, 'acceptedValues': STRINGS}))}),
                  'reviewPlan': obj({'checkedScope': STRINGS, 'unavailableSources': STRINGS, 'unprocessedScope': STRINGS,
                                     'secondPassStatus': string(['NOT_RUN', 'COMPLETED', 'PARTIAL']),
                                     'secondPassFactPaths': STRINGS, 'unresolvedDisagreements': STRINGS})})
    return {'$schema': 'https://json-schema.org/draft/2020-12/schema',
            '$id': 'urn:varvis:denovo:core-material:v1', 'title': 'Offline PS2/PM6 core material; no evidence award', **schema}


SCHEMA = build_schema()

if __name__ == '__main__':
    # Reproducible tracked schema; explicit developer operation, never a runtime write.
    Path(__file__).with_name('core-material.schema.json').write_bytes(
        (json.dumps(SCHEMA, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
