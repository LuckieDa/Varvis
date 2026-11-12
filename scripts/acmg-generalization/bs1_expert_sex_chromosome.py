"""Source-bound expert thresholds on sex chromosomes; no X-linked MCF model.

Compilation preserves a reviewed source requirement even when the local exome
resource cannot meet it. Dataset readiness is separate from scientific adoption.
"""
import copy
from decimal import Decimal, DecimalException
import re

from bs1_knowledge import require
import bs1_expert_interval as interval
from cspec_frequency_expression import parse_comparison

VERSION = 'REVIEWED_SEX_CHROMOSOME_THRESHOLD_V1'
REGION_CATALOG_SHA256 = '41b99cb587f84e0f9823a7994ea29573a3f6aad619ebe02d011e02b964b820aa'
MODES = {'Autosomal dominant inheritance': 'AUTOSOMAL_DOMINANT',
         'Autosomal recessive inheritance': 'AUTOSOMAL_RECESSIVE',
         'X-linked dominant inheritance': 'X_LINKED_DOMINANT',
         'X-linked recessive inheritance': 'X_LINKED_RECESSIVE',
         'Y-linked inheritance': 'Y_LINKED'}
METRICS = {f'gnomad.{dataset}.sex_ancestry_max_{suffix}': fields
           for dataset in ('exomes', 'joint') for suffix, fields in (
               ('af', ('POINT_ESTIMATE', 'AF', 'TARGET_ALT_AF')),
               ('faf95', ('95_PERCENT_LOWER_BOUND', 'FAF95', 'TARGET_ALT_FAF95')))}
_AC_ATOM = (r'(?:(?:>=|≥|>)\s*[0-9]+\s+variant[- ]alleles(?:\s+present)?'
            r'|AC\s*(?:>=|≥|>)\s*[0-9]+'
            r'|(?:at least|(?:a )?minimum of)\s+(?:[0-9]+|five)\s+variant[- ]alleles(?:\s+present)?)')
_AC_SOURCE_EXPRESSIONS = re.compile(r'(?<![\w<>=≥≤.])' + _AC_ATOM + r'(?![\w%]|\.\d)', re.I)


def _runtime_number(text):
    """Match Bs1SexChromosomePolicy.number without rounding or normalization."""
    if not isinstance(text, str) or len(text) > 80:
        return False
    try:
        number = Decimal(text)
        return (number.is_finite() and len(number.as_tuple().digits) <= 50
                and -100 <= -number.as_tuple().exponent <= 100)
    except DecimalException:
        return False


def strings(value, allowed):
    return (isinstance(value, list) and bool(value) and all(isinstance(v, str) for v in value)
            and len(value) == len(set(value)) and set(value) <= allowed)


def bounds(approved, clause, strengths):
    require(len(strengths) == 1 and strengths[0].get('strength') == 'Strong'
            and clause.get('instructionsToUse') == approved.get('instruction')
            and strengths[0].get('text') == approved.get('clauseText'), 'EXPERT_CLAUSE_REVIEW_MISMATCH')
    require(approved.get('statisticReview') and approved.get('additionalConditionsReview'),
            'EXPERT_SEMANTIC_REVIEW_REQUIRED')
    if 'intervalExpression' in approved:
        require(not any(k in approved for k in ('thresholdExpression', 'operator', 'fraction')),
                'EXPERT_SEX_THRESHOLD_AMBIGUOUS')
        result = interval.bounds(approved, clause, strengths)
        require(all(_runtime_number(bound['fraction']) for bound in result), 'EXPERT_SEX_RUNTIME_NUMBER_INVALID')
        return result
    require(not any(k in approved for k in ('lowerBound', 'upperBound')), 'EXPERT_SEX_THRESHOLD_AMBIGUOUS')
    expression = approved.get('thresholdExpression')
    parsed = parse_comparison(expression)
    require(parsed is not None and parsed['operator'] in {'GT', 'GTE'}
            and len(re.findall(r'(?<![\w<>=≥≤.])' + re.escape(expression)
            + r'(?![\w%]|\.\d)', approved['clauseText'])) == 1, 'EXPERT_THRESHOLD_EXPRESSION_UNBOUND')
    fraction = Decimal(parsed['fraction'])
    require(_runtime_number(approved.get('fraction')), 'EXPERT_SEX_RUNTIME_NUMBER_INVALID')
    require(approved.get('operator') == parsed['operator']
            and isinstance(approved.get('fraction'), str) and re.fullmatch(r'0\.\d+', approved['fraction'])
            and Decimal(approved['fraction']) == fraction and Decimal(0) < fraction < Decimal(1),
            'EXPERT_THRESHOLD_TEXT_MISMATCH')
    return [{'operator': approved['operator'], 'fraction': approved['fraction']}]


def contract(approved, sources, source_documents=None):
    """Validate finite v2 fields without inferring source terminology or ploidy."""
    scope = approved.get('sexChromosomeScope', {})
    require(isinstance(scope, dict) and set(scope) == {'assemblies', 'contigs', 'regions',
            'regionCatalogSha256', 'requiredDataset', 'sourceId', 'sourceLocator'}, 'EXPERT_SEX_SCOPE_FIELDS')
    require(strings(scope['assemblies'], {'GRCh37', 'GRCh38'}) and strings(scope['contigs'], {'X', 'Y'})
            and strings(scope['regions'], {'PAR', 'NON_PAR'})
            and scope['regionCatalogSha256'] == REGION_CATALOG_SHA256
            and scope['requiredDataset'] in {'EXOMES', 'JOINT'}, 'EXPERT_SEX_SCOPE_INVALID')
    statistic = approved.get('populationStatistic', {})
    statistic_fields = {'metricId', 'unit', 'confidence', 'ancestries', 'karyotypes', 'eligibleAn',
                        'sourceTerm', 'interpretation', 'interpretationBasis', 'sourceId', 'locator'}
    require(isinstance(statistic, dict) and set(statistic) in (statistic_fields, statistic_fields | {'eligibleAc'}),
            'EXPERT_SEX_STATISTIC_FIELDS')
    metric = METRICS.get(statistic['metricId'])
    require(metric and statistic['metricId'].startswith('gnomad.' + scope['requiredDataset'].lower() + '.')
            and statistic['unit'] == 'fraction'
            and tuple(statistic[k] for k in ('confidence', 'sourceTerm', 'interpretation')) == metric
            and isinstance(statistic['interpretationBasis'], str) and statistic['interpretationBasis'].strip(),
            'EXPERT_SEX_STATISTIC_INVALID')
    require(strings(statistic['ancestries'], {'afr', 'amr', 'eas', 'nfe', 'sas', 'mid'})
            and strings(statistic['karyotypes'], {'ALL', 'XX', 'XY'}), 'EXPERT_SEX_STRATA_INVALID')
    require(not ('PAR' in scope['regions'] and statistic['sourceTerm'] == 'FAF95'
                 and set(statistic['karyotypes']) != {'ALL'}), 'EXPERT_SEX_PAR_SEX_FAF_NOT_APPLICABLE')
    for source_id, locator in ((scope['sourceId'], scope['sourceLocator']),
                               (statistic['sourceId'], statistic['locator'])):
        require(isinstance(source_id, str) and len([s for s in sources if s.get('sourceId') == source_id]) == 1
                and isinstance(locator, str) and locator.strip(), 'EXPERT_SEX_SOURCE_UNBOUND')
    text = approved['clauseText']
    label = approved.get('sourceMetricExpression')
    labels = {'AF': {'AF', 'allele frequency'},
              'FAF95': {'FAF', 'FAF95', 'filtering allele frequency', 'Grpmax Filtering Allele Frequency'}}
    # Match only whitespace between the already finite metric words. The source
    # text (including male/provenance qualifiers) is never rewritten or shortened.
    metric_expression = r'\s+'.join(re.escape(word) for word in label.split()) if isinstance(label, str) else ''
    require(isinstance(label, str) and label in labels[statistic['sourceTerm']] and re.search(r'(?<!\w)' + metric_expression
            + r'(?!\w)', text, re.I), 'EXPERT_SEX_METRIC_EXPRESSION_UNBOUND')
    an = statistic['eligibleAn']
    require(isinstance(an, dict) and set(an) == {'operator', 'decimal'}
            and an['operator'] in {'GT', 'GTE'} and _runtime_number(an['decimal'])
            and re.fullmatch(r'[0-9]+', an['decimal']) and int(an['decimal']) >= 2000,
            'EXPERT_SEX_AN_REQUIREMENT_INVALID')
    require(approved.get('sampleSizeReview'), 'EXPERT_INTERVAL_SAMPLE_REVIEW_REQUIRED')
    _sample(approved, an, text)
    explicit_ac = {_ac_comparison(match[0])
                   for source_text in (text, approved.get('instruction')) if isinstance(source_text, str)
                   for match in _AC_SOURCE_EXPRESSIONS.finditer(source_text)}
    if 'eligibleAc' in statistic:
        _eligible_ac(approved, statistic['eligibleAc'], sources, source_documents)
        expected_ac = (statistic['eligibleAc']['operator'], int(statistic['eligibleAc']['decimal']))
        require(not explicit_ac or explicit_ac == {expected_ac}, 'EXPERT_SEX_AC_REQUIREMENT_AMBIGUOUS')
    else:
        require(not explicit_ac and 'eligibleAcExpression' not in approved, 'EXPERT_SEX_AC_REQUIREMENT_MISSING')
    return copy.deepcopy(scope), copy.deepcopy(statistic)


def _eligible_ac(approved, requirement, sources, documents):
    """Bind an explicit target-variant allele count; never infer people or observations."""
    require(isinstance(requirement, dict) and set(requirement) == {'operator', 'decimal', 'unit', 'sourceTerm', 'sourceId', 'locator'}
            and isinstance(requirement['operator'], str) and requirement['operator'] in {'GT', 'GTE'}
            and _runtime_number(requirement['decimal'])
            and re.fullmatch(r'0|[1-9][0-9]*', requirement['decimal'])
            and requirement['unit'] == 'alleles' and requirement['sourceTerm'] == 'AC',
            'EXPERT_SEX_AC_REQUIREMENT_INVALID')
    require(isinstance(approved.get('additionalConditionsReview'), str) and approved['additionalConditionsReview'].strip(),
            'EXPERT_SEX_AC_INTERPRETATION_REVIEW_REQUIRED')
    # Reuse the existing source graph, content digest and attributed interpretation
    # entry instead of adding a self-approved count/quality flag.
    bound = supporting_sources(approved, sources, documents)
    matches = [item for item in bound if item['sourceId'] == requirement['sourceId']
               and item['locator'] == requirement['locator']]
    require(len(matches) == 1, 'EXPERT_SEX_AC_SOURCE_UNBOUND')
    from bs1_qualification import resolve
    text = resolve(documents[requirement['sourceId']], requirement['locator'])
    require(isinstance(text, str), 'EXPERT_SEX_AC_SOURCE_TEXT_REQUIRED')
    expression = approved.get('eligibleAcExpression')
    require(isinstance(expression, str) and expression and len(expression) <= 200
            and len(re.findall(r'(?<![\w<>=≥≤.])' + re.escape(expression) + r'(?![\w%]|\.\d)', text)) == 1,
            'EXPERT_SEX_AC_EXPRESSION_UNBOUND')
    comparison = _ac_comparison(expression)
    require(comparison is not None, 'EXPERT_SEX_AC_EXPRESSION_UNSUPPORTED')
    require((requirement['operator'], int(requirement['decimal'])) == comparison,
            'EXPERT_SEX_AC_EXPRESSION_MISMATCH')


def _ac_comparison(expression):
    """Finite explicit allele-count atoms only; not an observation/person parser."""
    if len(expression) > 200:
        return None
    match = re.fullmatch(r'(>=|≥|>)\s*([0-9]+)\s+variant[- ]alleles(?:\s+present)?', expression, re.I)
    if match:
        operator, number = ('GT' if match[1] == '>' else 'GTE'), match[2]
    else:
        match = re.fullmatch(r'AC\s*(>=|≥|>)\s*([0-9]+)', expression)
        if match:
            operator, number = ('GT' if match[1] == '>' else 'GTE'), match[2]
        else:
            match = re.fullmatch(r'(?:at least|(?:a )?minimum of)\s+([0-9]+|five)\s+variant[- ]alleles(?:\s+present)?', expression, re.I)
            if match is None:
                return None
            operator, number = 'GTE', '5' if match[1].lower() == 'five' else match[1]
    return operator, int(number)


def _sample(approved, an, text):
    if approved.get('sampleSizeBasis') == 'LOCAL_ENGINEERING_MINIMUM':
        require(an == {'operator': 'GTE', 'decimal': '2000'} and 'eligibleAnExpression' not in approved,
                'EXPERT_INTERVAL_LOCAL_AN_INVALID')
        return
    require(approved.get('sampleSizeBasis') == 'SOURCE_STATED', 'EXPERT_INTERVAL_SAMPLE_BASIS_REQUIRED')
    expression = approved.get('eligibleAnExpression')
    require(isinstance(expression, str) and expression and len(re.findall(r'(?<![\w<>=≥≤.])'
            + re.escape(expression) + r'(?![\w%]|\.\d)', text)) == 1, 'EXPERT_AF_AN_EXPRESSION_UNBOUND')
    match = re.fullmatch(r'at least\s+([0-9]+(?:,[0-9]{3})*)\s+(?:observed\s+)?alleles', expression, re.I)
    if match:
        operator, number = 'GTE', match[1].replace(',', '')
    else:
        match = re.fullmatch(r'(>=|≥|>)\s*([0-9]+)', expression)
        require(match is not None, 'EXPERT_AF_AN_EXPRESSION_UNBOUND')
        operator, number = ('GT' if match[1] == '>' else 'GTE'), match[2]
    require(an['operator'] == operator and int(an['decimal']) == int(number), 'EXPERT_AF_AN_EXPRESSION_UNBOUND')


def _check_mode_conflicts(selected_mode, gene, disease, source_documents, source_disease):
    declared = {selected_mode}
    # Do not silently prefer a selected assertion over contradictory precise
    # assertions in the same pinned intake, or over an explicit CSpec qualifier.
    explicit_terms = {'X-linked dominant (HP:0001423)': 'X_LINKED_DOMINANT',
                      'X-linked recessive (HP:0001419)': 'X_LINKED_RECESSIVE'}
    source_disease = source_disease or {}
    for item in [source_disease] + source_disease.get('modesOfInheritance', []):
        if item.get('adjective') in explicit_terms:
            declared.add(explicit_terms[item['adjective']])
    for document in (source_documents or {}).values():
        for assertion in document if isinstance(document, list) else []:
            if (isinstance(assertion, dict) and assertion.get('hgncId') == gene
                    and assertion.get('mondoId') == disease
                    and assertion.get('classification') in {'Definitive', 'Strong', 'Moderate'}
                    and assertion.get('moi') in {'X_LINKED_DOMINANT', 'X_LINKED_RECESSIVE'}):
                declared.add(assertion['moi'])
    require(len(declared) == 1, 'EXPERT_SEX_MOI_SOURCE_CONFLICT')


def mode(source_mode, approved, gene, disease, sources, source_documents, source_disease=None):
    """Generic X-linked source labels need an independent, exact MOI assertion."""
    if source_mode in MODES:
        selected = MODES[source_mode]
        if selected.startswith('X_LINKED'):
            _check_mode_conflicts(selected, gene, disease, source_documents, source_disease)
        return selected, []
    require(source_mode == 'X-linked inheritance', 'EXPERT_MOI_UNSUPPORTED')
    mappings = approved.get('reviewedModeMappings', [])
    require(isinstance(mappings, list) and all(isinstance(m, dict) for m in mappings),
            'EXPERT_SEX_MOI_MAPPING_INVALID')
    matches = [m for m in mappings
               if m.get('sourceMode') == source_mode and m.get('hgncId') == gene
               and m.get('mondoId') == disease]
    require(matches, 'EXPERT_SEX_MOI_MAPPING_REQUIRED')
    require(len(matches) == 1, 'EXPERT_SEX_MOI_MAPPING_AMBIGUOUS')
    mapping = matches[0]
    require(set(mapping) == {'sourceMode', 'targetMode', 'hgncId', 'mondoId', 'sourceId', 'locator',
            'contentSha256', 'interpretationBasis'} and mapping['targetMode'] in {'X_LINKED_DOMINANT', 'X_LINKED_RECESSIVE'}
            and isinstance(mapping['interpretationBasis'], str) and mapping['interpretationBasis'].strip(),
            'EXPERT_SEX_MOI_MAPPING_INVALID')
    require(mapping['sourceId'] not in {approved['sexChromosomeScope']['sourceId'],
            approved['populationStatistic']['sourceId']}, 'EXPERT_SEX_MOI_INDEPENDENT_SOURCE_REQUIRED')
    matches = [s for s in sources if s.get('sourceId') == mapping['sourceId']]
    require(len(matches) == 1 and mapping['sourceId'] in (source_documents or {}),
            'EXPERT_SEX_MOI_SOURCE_REQUIRED')
    primary_shas = {s['sha256'] for s in sources if s['sourceId'] in {
            approved['sexChromosomeScope']['sourceId'], approved['populationStatistic']['sourceId']}}
    require(matches[0]['sha256'] not in primary_shas, 'EXPERT_SEX_MOI_INDEPENDENT_SOURCE_REQUIRED')
    from bs1_qualification import resolve
    from bs1_expert_batch import content_hash
    node = resolve(source_documents[mapping['sourceId']], mapping['locator'])
    require(content_hash(node) == mapping['contentSha256'] and isinstance(node, dict)
            and node.get('hgncId') == gene and node.get('mondoId') == disease
            and node.get('moi') == mapping['targetMode']
            and node.get('classification') in {'Definitive', 'Strong', 'Moderate'}, 'EXPERT_SEX_MOI_SOURCE_MISMATCH')
    _check_mode_conflicts(mapping['targetMode'], gene, disease, source_documents, source_disease)
    return mapping['targetMode'], [dict(matches[0], locator=mapping['locator'])]


def supporting_sources(approved, sources, documents):
    """Pin explicitly reviewed references; this does not interpret PDF semantics."""
    from bs1_qualification import resolve
    from bs1_expert_batch import content_hash
    bindings = approved.get('supportingSourceBindings', [])
    require(isinstance(bindings, list), 'EXPERT_SUPPORTING_SOURCE_INVALID')
    result, seen = [], set()
    for binding in bindings:
        require(isinstance(binding, dict) and set(binding) == {'sourceId', 'locator', 'contentSha256', 'interpretationBasis'}
                and all(isinstance(binding[k], str) and binding[k].strip() for k in binding)
                and re.fullmatch(r'[0-9a-f]{64}', binding['contentSha256']), 'EXPERT_SUPPORTING_SOURCE_INVALID')
        source_id, locator = binding['sourceId'], binding['locator']
        require((source_id, locator) not in seen, 'EXPERT_SUPPORTING_SOURCE_DUPLICATE')
        seen.add((source_id, locator))
        matches = [s for s in sources if s['sourceId'] == source_id]
        require(len(matches) == 1 and source_id in (documents or {}), 'EXPERT_SUPPORTING_SOURCE_UNBOUND')
        require(content_hash(resolve(documents[source_id], locator)) == binding['contentSha256'],
                'EXPERT_SUPPORTING_SOURCE_CONTENT_CHANGED')
        result.append(dict(matches[0], locator=locator))
    return result


def mode_scope(mode_name, scope):
    require(not (mode_name.startswith('X_LINKED') and set(scope['contigs']) != {'X'})
            and not (mode_name == 'Y_LINKED' and (set(scope['contigs']) != {'Y'} or set(scope['regions']) != {'NON_PAR'}))
            and not (mode_name.startswith('AUTOSOMAL') and set(scope['regions']) != {'PAR'}),
            'EXPERT_SEX_MOI_REGION_CONFLICT')


def local_readiness(record, resource_available=False, available_dataset='EXOMES'):
    """Preparation only: never produces a BS1 score or a scientific approval."""
    return dataset_readiness(record['expertPolicy']['scope']['sexChromosome'], resource_available, available_dataset)


def dataset_readiness(scope, resource_available=False, available_dataset='EXOMES'):
    """Check the reviewed requirement even before an exact MOI can be resolved."""
    require(isinstance(scope, dict) and scope.get('requiredDataset') in {'EXOMES', 'JOINT'}
            and type(resource_available) is bool, 'EXPERT_SEX_DATASET_REQUIREMENT_INVALID')
    required = scope['requiredDataset']
    reason = ('BS1_SEX_RESOURCE_DATASET_MISMATCH' if available_dataset != required else
              'BS1_RESOURCE_EXECUTION_UNAVAILABLE' if not resource_available else None)
    return {'status': 'BLOCKED' if reason else 'ELIGIBLE_FOR_RUNTIME_VALIDATION', 'reasonCode': reason,
            'requiredDataset': required, 'availableDataset': available_dataset,
            'scientificallyAdopted': False, 'bs1Evaluated': False}


def prepare_reviews(review, specifications, sources, source_documents=None):
    """Expose a missing exact X MOI source as an unresolved preparation candidate."""
    from bs1_expert_batch import compile_reviews
    try:
        records = compile_reviews(review, specifications, sources, source_documents=source_documents)
    except ValueError as error:
        if str(error) not in {'EXPERT_SEX_MOI_MAPPING_REQUIRED', 'EXPERT_SEX_MOI_MAPPING_AMBIGUOUS',
                              'EXPERT_SEX_MOI_SOURCE_REQUIRED', 'EXPERT_SEX_MOI_SOURCE_CONFLICT'}:
            raise
        return {'status': 'UNRESOLVED', 'reasonCode': str(error), 'records': [],
                'scientificallyAdopted': False, 'bs1Evaluated': False}
    return {'status': 'COMPILED_NOT_SCIENTIFICALLY_ADOPTED', 'reasonCode': None, 'records': records,
            'scientificallyAdopted': False, 'bs1Evaluated': False}
