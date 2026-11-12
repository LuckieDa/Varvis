"""Finite, source-reviewed BA1 condition grammar; no gene-specific branches."""
from decimal import Decimal, localcontext
import copy
import hashlib
import html
import json
from pathlib import Path
import re

FAMILY = 'SIMPLE_FAF95_THRESHOLD_V1'
RECIPROCAL_FAMILY = 'RECIPROCAL_GRPMAX_FAF_THRESHOLD_V1'
OR_COUNT_FAMILY = 'FAF95_OR_HOMO_HEMIZYGOTE_COUNT_V1'
TARGET = 'gnomad.joint.faf95max'
ANCESTRIES = ['afr', 'amr', 'eas', 'mid', 'nfe', 'sas']
NUMBER = r'(?:0(?:\.[0-9]+)?|1(?:\.0+)?)'
OP = r'(?:>=|>|≥)'
CALCULATED = re.compile(
    r'Variants with a highest population minor allele frequency \(MAF\)\s*(?P<op>' + OP + r')\s*(?P<value>' + NUMBER + r')'
    r'\s*\((?P<percent>[0-9]+(?:\.[0-9]+)?)%\) in any continental population with\s*(?P<anop>' + OP + r')\s*(?P<an>[1-9][0-9]*) alleles in gnomAD will meet BA1\. '
    r'Calculated using the Prevalence of 1:(?P<prevalence>[1-9][0-9,]*), Allelic Contribution of (?P<allelic>' + NUMBER + r'), '
    r'Genetic Contribution of (?P<genetic>' + NUMBER + r'), and Penetrance of (?P<penetrance>' + NUMBER + r') '
    r'to allow for mild (?P<condition>[A-Z][A-Z0-9-]*) that may develop in adulthood\.')
DIRECT = re.compile(r'gnomAD joint GroupMax FAF95\s*(?P<op>' + OP + r')\s*(?P<value>' + NUMBER + r')\s*;\s*same population AN\s*(?P<anop>' + OP + r')\s*(?P<an>[1-9][0-9]*)\.?')
EXPLICIT_FAF = re.compile(r'Allele frequency is\s*(?P<op>' + OP + r')\s*(?P<value>' + NUMBER + r') based on the filtering allele frequency \(FAF\) in gnomAD in the subpopulation with the highest frequency \(popmax\)\.')
RECIPROCAL_FAF = re.compile(r'gnomAD Grpmax FAF\s*(?P<op>' + OP + r')\s*1:(?P<denominator>[1-9][0-9,]*) '
                          r'\((?P<percentop>' + OP + r')\s*(?P<percent>[0-9]+(?:\.[0-9]+)?)% or (?P<value>' + NUMBER + r')\)\.?')


# Complete released-rule sentence. No branch may be discarded or compiled as a scalar.
FAF_OR_INDIVIDUAL_COUNT = re.compile(
    r'Applicable when Grpmax Filtering Allele Frequency is\s*(?P<op>' + OP + r')\s*(?P<value>' + NUMBER + r')'
    r'\s*\((?P<percent>[0-9]+(?:\.[0-9]+)?)%\) OR\s*(?:>=|≥)\s*(?P<count>[1-9][0-9]*) '
    r'homo- or hemizygotes \(or the sum of total hemi- and homozygotes\) are present in the most current '
    r'version of gnomAD available at the time of curation\.')


def _pinned_source(source):
    return (isinstance(source, dict) and isinstance(source.get('url'), str)
            and source['url'].startswith('https://')
            and isinstance(source.get('sha256'), str)
            and re.fullmatch(r'[0-9a-f]{64}', source['sha256']) is not None)


def _conditional_migration_review(review):
    # This review aligns FAF definitions only. It never qualifies individual counts
    # or approves the new policy for scientific/clinical adoption.
    return (isinstance(review, dict) and isinstance(review.get('reviewId'), str)
            and bool(review['reviewId'].strip())
            and isinstance(review.get('reviewSha256'), str)
            and re.fullmatch(r'[0-9a-f]{64}', review['reviewSha256']) is not None
            and review.get('scope') == 'SOURCE_REVIEWED_LIMITED_DERIVATION_FAMILY; NOT_CLINICAL_OR_PRODUCTION_APPROVAL'
            and isinstance(review.get('sources'), list) and bool(review['sources'])
            and all(_pinned_source(source) for source in review['sources']))


def _compile_faf_or_individual_count(normalized, original, source, review):
    match = FAF_OR_INDIVIDUAL_COUNT.fullmatch(normalized)
    if match is None or not _pinned_source(source) or not _conditional_migration_review(review):
        return None
    value, percent = Decimal(match['value']), Decimal(match['percent'])
    # Keep percent equivalence exact even for a high-precision source value.
    with localcontext() as context:
        context.prec = max(28, len(match['value']) + len(match['percent']) + 5)
        if value <= 0 or value > 1 or value * 100 != percent:
            return None
    return {
        'disposition': 'CONDITIONAL',
        'reasonCode': 'CSPEC_BA1_COMPLETE_FAF_OR_INDIVIDUAL_COUNT_FAMILY',
        'semanticFamily': OR_COUNT_FAMILY,
        'originalMetric': 'FAF',
        'originalStatisticText': 'Grpmax Filtering Allele Frequency',
        'originalText': original,
        'sourceRefs': [copy.deepcopy(source)],
        'conditions': [],
        'derivedPolicyVersion': 'a01-ba1-faf-or-individual-count-v1',
        'expression': {
            'operator': 'ANY',
            'frequency': {
                'metric': TARGET, 'comparator': comparator(match['op']),
                'value': str(value), 'unit': 'fraction',
                'minimumAn': '2000', 'minimumAnComparator': 'GREATER_THAN_OR_EQUAL',
                'ancestries': list(ANCESTRIES)},
            'individualCount': {
                'operator': 'GREATER_THAN_OR_EQUAL', 'value': match['count'], 'unit': 'individuals',
                'populationScope': 'SAME_DEDUPLICATED_SOURCE',
                'counts': ['HOMOZYGOUS', 'HEMIZYGOUS'], 'sumOnlyIfMutuallyExclusive': True}},
        'migration': {
            'status': 'REVIEWED', 'reviewId': review['reviewId'],
            'reviewSha256': review['reviewSha256'],
            'sources': copy.deepcopy(review['sources']) + [copy.deepcopy(source)],
            'basis': 'EXPLICIT_FAF_DEFINITION_ALIGNMENT',
            'reviewRole': 'PROJECT_SOURCE_DERIVATION_NOT_NEW_VCEP_OR_CLINICAL_APPROVAL'}}


def clean(text):
    text = re.sub(r'(?m)^\s*[*-]\s+', '', text)
    return ' '.join(html.unescape(text).replace('**', '').split())


def comparator(raw):
    return 'GREATER_THAN' if raw == '>' else 'GREATER_THAN_OR_EQUAL'


def ancillary_version_text(key, text):
    """Consume only complete, non-BA1 publication metadata templates.

    These closed templates describe revisions to other named criteria; arbitrary
    prose with no literal BA1 token is not assumed irrelevant to BA1.
    """
    if text is None or isinstance(text, str) and not text.strip():
        return True
    if not isinstance(text, str):
        return False
    text = clean(text)
    symbol, version, date = r'[A-Za-z0-9-]+', r'[0-9]+(?:\.[0-9]+){1,2}', r'[0-9]{1,2}/[0-9]{1,2}/[0-9]{2}'
    combination = date + r' the VCEP added that PVS1 \+ PM2 \(supporting\) = Likely Pathogenic'
    if key == 'description':
        if re.fullmatch(r'This version (?:is )?specified for the following genes: ' + symbol + r'(?:, ' + symbol + r')*', text):
            return True
        return bool(re.fullmatch(
            r'(?P<symbol>' + symbol + r') Specifications version ' + version + r'\. The VCEP reviewed all the codes and updated wording\. '
            r'(?P=symbol) Specifications version ' + version + r'\. The VCEP updated the wording for PP3 and BP4 to only include reference to the Splice AI tool\. '
            + combination, text))
    if key == 'releaseNotes':
        if re.fullmatch(r'Updating PS2 to clarify when a family history of [a-z]+(?: [a-z]+)* prevents the application of this criterion\. '
                        r'Minor edits for clarity and avoiding redundancy in the specifications\. Updating citations so reference list populates\.', text):
            return True
        return bool(re.fullmatch(r'Version ' + version + r" - the " + symbol + r" VCEP reviewed the PP3 and BP4 codes' wording and updated the reference to only include the Splice AI tool\. "
                                + combination + r' ' + date + r' the VCEP re-added that PVS1 \+ PM2 \(supporting\) = LP; No pilot classifications changed due to this added rule\.', text))
    return False


def load_review(path, expected_sha):
    raw = Path(path).read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError('SEMANTIC_REVIEW_HASH_MISMATCH')
    data = json.loads(raw)
    finite = data.get('scientificContract', {}).get('finiteCompiler', {})
    if data.get('schemaVersion') != 'a01-independent-ba1-source-expectations-v1' or finite.get('allowedSemanticFamily') != 'BA1_SINGLE_LOWER_FREQUENCY_BOUND_WITH_OPTIONAL_MIN_AN':
        raise ValueError('SEMANTIC_REVIEW_FAMILY_UNSUPPORTED')
    migration = finite.get('statisticMigration', {})
    if migration.get('status') != 'SOURCE_REVIEWED_LIMITED_DERIVATION_FAMILY; NOT_CLINICAL_OR_PRODUCTION_APPROVAL' or migration.get('target') != 'GNOMAD_JOINT_GROUPMAX_FAF95_FRACTION':
        raise ValueError('STATISTIC_MIGRATION_REVIEW_REQUIRED')
    sources = [s for s in data.get('sources', []) if s.get('id') == 'CLINGEN_GNOMAD_2025']
    if len(sources) != 1 or not re.fullmatch(r'[0-9a-f]{64}', sources[0].get('sha256', '')) or not sources[0].get('url', '').startswith('https://'):
        raise ValueError('MIGRATION_GUIDANCE_SOURCE_IDENTITY_MISSING')
    source = sources[0]
    if source.get('file') and hashlib.sha256(Path(source['file']).read_bytes()).hexdigest() != source['sha256']:
        raise ValueError('MIGRATION_GUIDANCE_FILE_HASH_MISMATCH')
    return {'reviewId': data['recordId'], 'reviewSha256': expected_sha,
            'sources': [{'url': source['url'], 'sha256': source['sha256'], 'version': source['version']}],
            'scope': migration['status']}


def compile_frequency(text, source, review=None):
    """Return None unless every word belongs to the declared finite family."""
    normalized = clean(text)
    conditional = _compile_faf_or_individual_count(normalized, text, source, review)
    if conditional is not None:
        return conditional
    match = DIRECT.fullmatch(normalized)
    original = TARGET
    migration_basis = None
    calculation = None
    family = FAMILY
    reciprocal = RECIPROCAL_FAF.fullmatch(normalized)
    if reciprocal:
        if review is None or comparator(reciprocal['op']) != comparator(reciprocal['percentop']):
            return None
        denominator = reciprocal['denominator']
        if ',' in denominator and not re.fullmatch(r'[1-9][0-9]{0,2}(?:,[0-9]{3})+', denominator):
            return None
        denominator = denominator.replace(',', '')
        if Decimal(reciprocal['value']) * Decimal(denominator) != 1 or Decimal(reciprocal['value']) * 100 != Decimal(reciprocal['percent']):
            return None
        match, original, family = reciprocal, 'FAF', RECIPROCAL_FAMILY
        migration_basis = 'EXPLICIT_FAF_DEFINITION_ALIGNMENT'
        calculation = {'reciprocalDenominator': denominator, 'sourcePercent': reciprocal['percent'],
                       'equivalentRepresentationsVerified': True, 'cutoffRecomputed': False}
    if not match:
        match = EXPLICIT_FAF.fullmatch(normalized)
        if match:
            if review is None:
                return None
            original = 'FAF'
            migration_basis = 'EXPLICIT_FAF_DEFINITION_ALIGNMENT'
        else:
            match = CALCULATED.fullmatch(normalized)
            if not match or review is None:
                return None
            original = 'MAF'
            migration_basis = 'EXPLICIT_CALCULATED_FREQUENCY_MIGRATION'
            if Decimal(match['value']) * 100 != Decimal(match['percent']):
                return None
            denominator = match['prevalence']
            if ',' in denominator and not re.fullmatch(r'[1-9][0-9]{0,2}(?:,[0-9]{3})+', denominator):
                return None
            if int(denominator.replace(',', '')) <= 0 or any(Decimal(match[name]) <= 0 for name in ('allelic', 'genetic', 'penetrance')):
                return None
            calculation = {'prevalence': '1/' + denominator.replace(',', ''), 'allelicContribution': match['allelic'],
                           'geneticContribution': match['genetic'], 'penetrance': match['penetrance'],
                           'explanation': normalized[normalized.index('Calculated using'):],
                           'cutoffRecomputed': False}
    if Decimal(match['value']) <= 0:
        return None
    groups = match.groupdict()
    if int(groups.get('an') or '2000') < 2000:
        # This family does not silently replace a source's lower AN requirement.
        # A reviewed exceptional requirement needs a distinct supported family.
        return None
    result = {'disposition': 'THRESHOLD', 'reasonCode': 'CSPEC_BA1_COMPLETE_SCALAR_FREQUENCY_FAMILY',
              'semanticFamily': family, 'originalMetric': original, 'metric': TARGET,
              'comparator': comparator(match['op']), 'value': str(Decimal(match['value'])), 'unit': 'fraction',
              'minimumAn': groups.get('an') or '2000', 'minimumAnComparator': comparator(groups.get('anop') or '≥'),
              'ancestries': ANCESTRIES, 'conditions': [], 'sourceRefs': [source],
              'originalStatisticText': 'MAF' if original == 'MAF' else ('FAF' if migration_basis else 'joint GroupMax FAF95'),
              'originalThresholdText': match['value'], 'derivedPolicyVersion': 'a01-ba1-scalar-derived-v1'}
    if migration_basis:
        result['migration'] = {'status': 'REVIEWED', 'reviewId': review['reviewId'],
                               'reviewSha256': review['reviewSha256'], 'sources': review['sources'] + [source],
                               'basis': migration_basis, 'reviewRole': 'PROJECT_SOURCE_DERIVATION_NOT_NEW_VCEP_OR_CLINICAL_APPROVAL'}
    if calculation:
        result['calculationProvenance'] = calculation
    if family == RECIPROCAL_FAMILY:
        result['derivedPolicyVersion'] = 'a01-ba1-reciprocal-faf-derived-v1'
    return result


def related_ba1_conditions(entity):
    """Consume complete cross-criterion instructions, not only the BA1 numeric sentence."""
    qc_text = ('Recommend using as supporting level of evidence (PM2_Supporting) per ClinGen guidance. '
               'Per guidance from ClinGen/SVI, PM2_Supporting + PVS1 is sufficient evidence of a variant being likely pathogenic. '
               'We recommend investigating the genotype metrics in gnomAD for variants that have been flagged for having failed one or more quality parameters, '
               'as it is possible that some of these filtered variants are actually real. The number of filtered alleles can be counted to determine whether '
               'PM2_Supporting would be met even if they were genuine calls. If the filtered calls are sufficient in number to not meet PM2_Supporting, '
               'then we would not use it. Because it is also possible that these calls are false positives, we would not use filtered variants to support BA1 or BS1.')
    conditions = []
    for criterion in entity.get('ld', {}).get('CriteriaCode', []):
        content = criterion.get('entContent', {})
        if content.get('label') == 'BA1':
            continue
        texts = [(field, content.get(field)) for field in ('additionalComments', 'instructionsToUse')]
        for descriptor in content.get('strengthDescriptor', []):
            texts.extend((field, descriptor.get(field)) for field in ('text', 'instructionsToUse'))
        for field, text in texts:
            if not isinstance(text, str) or not re.search(r'\bBA1\b', text):
                continue
            if content.get('label') != 'PM2' or field != 'instructionsToUse' or clean(text.replace('\\_', '_')) != qc_text:
                return None
            conditions.append('GNOMAD_FILTERED_VARIANTS_EXCLUDED')
    return sorted(set(conditions))
