"""Bounded interpretation of explicit experimental observations, not PVS1 scoring.

Literal observations retain their experimental scope. A fragment, therapeutic
intervention or partial endpoint cannot establish endogenous isoform rescue.
Unrecognised prose remains unread scientifically; it is never negative evidence.
"""
import re
from qualify_literature_cases import sentences

VERSION = 'pvs1-start-loss-experiment-facts-v1'


def facts(finding):
    citation = finding['citation']
    text = citation['quote']
    observations = []
    # Full clauses, not keyword hits. Conservative negation/conditional checks
    # deliberately leave ambiguous statements for scientific review.
    for begin, end, sentence in sentences(text):
        clause = sentence.strip()
        if not clause or re.search(r'\b(?:not|no|might|may|could|would|hypothesi\w*)\b', clause, re.I):
            continue
        patterns = [
            ('FRAGMENT_CONSTRUCT_REPORTED', r'\bprotein fragments\b', 'constructExtent', 'FRAGMENT_ASSAY_NOT_WHOLE_ISOFORM'),
            ('EXON_DELETION_INTERVENTION_REPORTED', r'\bwe (?:first )?(?:genetically )?deleted exon (?P<exon>[1-9][0-9]*) in\b',
             'dosageAndIntervention', 'INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION'),
            ('OVEREXPRESSION_INTERVENTION_REPORTED', r'\bwe (?:transiently |stably )?overexpressed\b',
             'dosageAndIntervention', 'INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION'),
            ('PARTIAL_READOUT_RESCUE_REPORTED',
             r'(?<![\w.+−–-])(?P<percent>[0-9]+(?:\.[0-9]+)?)% of (?P<endpoint>[A-Za-z][A-Za-z -]{0,80}) were (?P<extent>at least partially|partially) rescued\b',
             'functionalOutcome', 'PARTIAL_ENDPOINT_NOT_WHOLE_FUNCTION'),
            ('PROTEIN_ABUNDANCE_INCREASE_REPORTED',
             r'\bprotein was [~\u223c]?(?P<low>[0-9]+(?:\.[0-9]+)?)(?:[-\u2013](?P<high>[0-9]+(?:\.[0-9]+)?))?% higher\b',
             'functionalOutcome', 'ABUNDANCE_NOT_FUNCTIONAL_RESCUE'),
        ]
        for kind, pattern, field, limitation in patterns:
            match = re.search(pattern, clause, re.I)
            if not match:
                continue
            values = {k: v for k, v in match.groupdict().items() if v is not None}
            if 'percent' in values and not 0 <= float(values['percent']) <= 100:
                continue
            if 'high' in values and float(values['high']) < float(values['low']):
                continue
            start = begin + len(sentence) - len(sentence.lstrip())
            observations.append({'kind': kind, 'field': field, 'reportedValues': values,
                                 'sourceStart': citation['start'] + start,
                                 'sourceEnd': citation['start'] + start + len(clause),
                                 'sourceQuote': clause, 'unitId': citation['unitId'],
                                 'sourceSha256': citation['sourceSha256'],
                                 'sourceRole': finding['sourceContext'],
                                 'status': 'EXPLICIT_REPORTED_OBSERVATION',
                                 'useLimit': limitation, 'establishesFunctionalAlternative': False})
    return observations


def assess(reading):
    observations = [item for finding in reading['findings'] for item in facts(finding)]
    # Even primary passages do not establish isoform-version binding, control
    # adequacy, native dose or biological equivalence on their own.
    return {'schemaVersion': VERSION,
            'status': 'EXPERIMENTAL_OBSERVATIONS_WITH_LIMITS' if observations else 'EXPERIMENT_SEMANTICS_UNRESOLVED',
            'observations': observations, 'observationCount': len(observations),
            'interpretation': 'LIMITED_OBSERVATIONS_NOT_ALTERNATIVE_FUNCTION_QUALIFICATION',
            'remainingRequirements': ['EXACT_ISOFORM_TO_TRANSCRIPT_BINDING', 'DISEASE_RELEVANT_FUNCTION_AND_CONTEXT',
                                      'CONTROLS_AND_REPLICATION', 'EXPERIMENT_SCOPE_AND_DOSAGE_APPLICABILITY',
                                      'CONTRADICTORY_AND_UNREAD_MATERIAL_REVIEW'],
            'runtimeEligible': False, 'functionalRescueEstablished': False,
            'negativeFunctionalCoverageGranted': False}
