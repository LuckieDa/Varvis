"""Compile explicitly reviewed CSpec clauses; never infer an adoption from a number.

The review file binds the entire published entity, including other criteria and
notes. A changed source needs a new review. Gene-specific evaluation code is not
generated. This module is offline and does not modify its inputs.
"""
import copy
import hashlib
import json
import re
import unicodedata
from decimal import Decimal
from bs1_knowledge import require
import bs1_expert_interval as interval
import bs1_expert_sex_chromosome as sex_chromosome
from cspec_frequency_expression import parse_comparison

MODES = {'Autosomal dominant inheritance': 'AUTOSOMAL_DOMINANT',
         'Autosomal recessive inheritance': 'AUTOSOMAL_RECESSIVE'}
TARGET_AF = 'REVIEWED_SINGLE_TARGET_AF_BOUND_V1'


def reviewed_target_af(approved, text):
    """Explicit source interpretation; never infer that MAF means 1 - target AF."""
    s = approved.get('populationStatistic', {})
    require(set(s) == {'metricId', 'unit', 'confidence', 'ancestries', 'sourceTerm', 'interpretation', 'interpretationBasis', 'eligibleAn'},
            'EXPERT_AF_STATISTIC_FIELDS')
    require(s['metricId'] == 'gnomad.exomes.eligible_ancestry_max_af' and s['unit'] == 'fraction'
            and s['confidence'] == 'POINT_ESTIMATE' and isinstance(s['interpretationBasis'], str) and s['interpretationBasis'].strip(),
            'EXPERT_AF_STATISTIC_INVALID')
    expected = {'AF': 'TARGET_ALT_AF', 'MAF': 'TARGET_ALT_AF_NOT_ABOVE_HALF'}
    is_interval = approved.get('translation') == interval.VERSION
    require(s['sourceTerm'] in expected and s['interpretation'] == expected[s['sourceTerm']]
            and (is_interval or re.search(r'\b' + s['sourceTerm'] + r'\b', text)), 'EXPERT_AF_SOURCE_INTERPRETATION_REQUIRED')
    groups = s['ancestries']
    require(isinstance(groups, list) and groups and all(isinstance(g, str) for g in groups)
            and len(groups) == len(set(groups)) and set(groups) <= {'afr', 'amr', 'eas', 'nfe', 'sas', 'mid'}, 'EXPERT_AF_ANCESTRY_SCOPE')
    an = s['eligibleAn']; expression = approved.get('eligibleAnExpression', '')
    if is_interval:
        require(set(an) == {'operator','decimal'} and isinstance(an['decimal'],str)
                and re.fullmatch(r'[0-9]+',an['decimal']) and int(an['decimal']) >= 2000
                and an['operator'] in {'GT','GTE'}, 'EXPERT_AF_AN_EXPRESSION_UNBOUND')
        interval.source_metric_and_sample(approved, text, s)
        return s
    match = re.fullmatch(r'(>=|≥|>)\s*([0-9]+)', expression)
    require(set(an) == {'operator', 'decimal'} and isinstance(an['decimal'], str)
            and re.fullmatch(r'[0-9]+', an['decimal']) and int(an['decimal']) >= 2000 and match is not None
            and an['operator'] == ('GT' if match[1] == '>' else 'GTE') and int(an['decimal']) == int(match[2])
            and re.search(r'(?<![<>=≥\d.])' + re.escape(expression) + r'(?![\d.])', text), 'EXPERT_AF_AN_EXPRESSION_UNBOUND')
    return s


def canonical(value):
    if isinstance(value, str):
        return unicodedata.normalize('NFC', value)
    if isinstance(value, list):
        return [canonical(v) for v in value]
    if isinstance(value, dict):
        result = {}
        for key in sorted(value):
            normalized = unicodedata.normalize('NFC', key)
            require(normalized not in result, 'NORMALIZED_KEY_COLLISION')
            result[normalized] = canonical(value[key])
        return result
    require(value is None or type(value) in (bool, int), 'BINARY_FLOAT_FORBIDDEN')
    return value


def content_hash(value):
    return hashlib.sha256(json.dumps(canonical(value), ensure_ascii=False,
                                    separators=(',', ':')).encode('utf-8')).hexdigest()


def single_faf_threshold(approved, clause, strengths):
    """Check an explicitly reviewed single FAF bound; never infer scientific scope."""
    require(len(strengths) == 1 and strengths[0]['strength'] == 'Strong', 'EXPERT_CLAUSE_REVIEW_MISMATCH')
    if approved.get('translation') in {'REVIEWED_SINGLE_FAF_BOUND_V1', TARGET_AF}:
        require(clause.get('instructionsToUse') == approved['instruction']
                and strengths[0].get('text') == approved['clauseText'], 'EXPERT_CLAUSE_REVIEW_MISMATCH')
        expression = approved['thresholdExpression']
        parsed = parse_comparison(expression)
        require(parsed is not None and parsed['operator'] in {'GT', 'GTE'}
                and len(re.findall(r'(?<![<>=≥\d.])' + re.escape(expression) + r'(?![\d.%])',
                                   approved['clauseText'])) == 1, 'EXPERT_THRESHOLD_EXPRESSION_UNBOUND')
        operator = parsed['operator']
        fraction = Decimal(parsed['fraction'])
        require(operator == approved['operator'] and fraction == Decimal(approved['fraction']), 'EXPERT_THRESHOLD_TEXT_MISMATCH')
        require(approved.get('statisticReview') and approved.get('additionalConditionsReview'), 'EXPERT_SEMANTIC_REVIEW_REQUIRED')
        if approved.get('translation') == TARGET_AF:
            reviewed_target_af(approved, approved['clauseText'])
        return approved['clauseText']
    require('translation' not in approved, 'EXPERT_TRANSLATION_UNSUPPORTED')
    require(clause.get('instructionsToUse') == approved['instruction']
            and strengths[0].get('text') == approved['instruction'], 'EXPERT_CLAUSE_REVIEW_MISMATCH')
    operator = '≥' if approved['operator'] == 'GTE' else '>'
    percent = format((Decimal(approved['fraction']) * 100).normalize(), 'f')
    require(approved['instruction'] == 'GnomAD filtering allele frequency ' + operator + percent + '%.',
            'EXPERT_THRESHOLD_TEXT_MISMATCH')
    return approved['instruction']


def compile_reviews(review, specifications, sources, source_documents=None):
    require(review.get('schemaVersion') == 'bs1-expert-clause-review-v1', 'EXPERT_REVIEW_SCHEMA')
    require(review.get('reviewer') and review.get('reviewedAt') and review.get('basis'), 'EXPERT_REVIEW_ATTRIBUTION')
    require(review.get('dataScopePolicyId') in {'BS1_LOCAL_EXOMES_V1', 'BS1_JOINT_SEX_V1'}, 'EXPERT_LOCAL_SCOPE_REQUIRED')
    result, seen = [], set()
    for approved in review['specifications']:
        matches = [s for s in specifications if s['specificationId'] == approved['specificationId']]
        require(len(matches) == 1, 'EXPERT_SOURCE_AMBIGUOUS_OR_MISSING')
        spec = matches[0]; entity = spec['original']; meta = entity['entContent']
        require(spec['version'] == approved['version'] and content_hash(entity) == approved['entitySha256'], 'EXPERT_REVIEW_SOURCE_CHANGED')
        joint = (approved.get('translation') == sex_chromosome.VERSION
                 and approved.get('sexChromosomeScope', {}).get('requiredDataset') == 'JOINT')
        require(review['dataScopePolicyId'] == ('BS1_JOINT_SEX_V1' if joint else 'BS1_LOCAL_EXOMES_V1'),
                'EXPERT_LOCAL_SCOPE_REQUIRED')
        require(approved.get('decision') == ('ADOPT_JOINT_SEX' if joint else 'ADOPT_LOCAL_EXOMES') and approved.get('crossRuleReview')
                and approved.get('exceptionReview') and approved.get('scopeReview'), 'EXPERT_SEMANTIC_REVIEW_REQUIRED')
        clauses = [c['entContent'] for c in entity['ld']['CriteriaCode'] if c['entContent'].get('label') == 'BS1']
        require(len(clauses) == 1, 'EXPERT_BS1_CLAUSE_AMBIGUOUS')
        clause = clauses[0]
        is_sex = approved.get('translation') == sex_chromosome.VERSION
        reviewed_translation = approved.get('translation') in {'REVIEWED_SINGLE_FAF_BOUND_V1', TARGET_AF, interval.VERSION, sex_chromosome.VERSION}
        if reviewed_translation:
            require([s.get('name') for s in meta.get('states', []) if s.get('current') is True] == ['Released'],
                    'EXPERT_NOT_RELEASED')
            strengths = [s for s in clause.get('strengthDescriptor', []) if s.get('applicability', '').lower().startswith('applicable')]
            for strength in strengths:
                require(strength.get('status') == 'approved' or ('status' not in strength
                        and approved.get('statusBasis') == 'RELEASED_ENTITY_LEGACY_STRENGTH_STATUS_ABSENT'),
                        'EXPERT_STRENGTH_NOT_APPROVED')
        else:
            strengths = [s for s in clause.get('strengthDescriptor', []) if s.get('status') == 'approved'
                         and s.get('applicability', '').lower() == 'applicable']
        is_interval = approved.get('translation') == interval.VERSION
        if is_sex:
            source_bounds = sex_chromosome.bounds(approved, clause, strengths)
            original_text = approved['clauseText']
            sex_scope, sex_statistic = sex_chromosome.contract(approved, sources, source_documents)
        elif is_interval:
            source_bounds = interval.bounds(approved, clause, strengths)
            original_text = approved['clauseText']
            reviewed_target_af(approved, original_text)
        else:
            require(approved['operator'] in ['GT', 'GTE'] and isinstance(approved['fraction'], str)
                    and Decimal(0) < Decimal(approved['fraction']) < Decimal(1), 'EXPERT_THRESHOLD_INVALID')
            original_text = single_faf_threshold(approved, clause, strengths)
        sources_for_spec = [s for s in sources if s['sourceId'] == spec['sourceId']]
        require(len(sources_for_spec) == 1, 'EXPERT_SOURCE_UNBOUND')
        source = dict(sources_for_spec[0], locator=spec['specificationId'] if is_sex
                      else spec['specificationId'] + '/ld/CriteriaCode/BS1;ld/RuleSet;entContent')
        gene_ids = {g['entContent']['HGNC']['symbol']: g['entContent']['HGNC']['hgnc_id'] for g in entity['ld']['Gene']}
        scopes = []
        for rules in entity['ld']['RuleSet']:
            for gene in rules['entContent']['genes']:
                require(gene['gene'] in clause.get('gene', []), 'EXPERT_GENE_SCOPE_MISMATCH')
                for disease in gene['diseases']:
                    if is_sex and clause.get('disease'):
                        require(disease.get('preferredMondoId') in clause['disease'], 'EXPERT_SEX_DISEASE_SCOPE_MISMATCH')
                    modes = disease.get('modesOfInheritance')
                    if modes is None:
                        require(reviewed_translation and (is_sex or disease.get('preferredModeOfInheritance') in MODES),
                                'EXPERT_MOI_UNSUPPORTED')
                        modes = [{'modeOfInheritance': disease['preferredModeOfInheritance']}]
                    require(isinstance(modes, list) and bool(modes), 'EXPERT_MOI_UNSUPPORTED')
                    for mode in modes:
                        if is_sex:
                            selected_mode, mode_sources = sex_chromosome.mode(mode['modeOfInheritance'], approved,
                                    gene_ids[gene['gene']], disease['preferredMondoId'], sources, source_documents, disease)
                            sex_chromosome.mode_scope(selected_mode, sex_scope)
                        else:
                            require(mode['modeOfInheritance'] in MODES, 'EXPERT_MOI_UNSUPPORTED')
                            selected_mode, mode_sources = MODES[mode['modeOfInheritance']], []
                        scopes.append((gene, disease, selected_mode, mode_sources))
        require(len(scopes) == approved['expectedBackgrounds'], 'EXPERT_BACKGROUND_COUNT_CHANGED')
        for gene, disease, mode, mode_sources in scopes:
            scope = {'hgncId': gene_ids[gene['gene']], 'mondoId': disease['preferredMondoId'], 'modes': [mode]}
            record_sources = [source]
            if is_sex:
                scope['sexChromosome'] = copy.deepcopy(sex_scope)
                extra_sources = list(mode_sources)
                extra_sources.extend(sex_chromosome.supporting_sources(approved, sources, source_documents))
                source_locations = [(sex_scope['sourceId'], sex_scope['sourceLocator']),
                                    (sex_statistic['sourceId'], sex_statistic['locator'])]
                if 'eligibleAc' in sex_statistic:
                    source_locations.append((sex_statistic['eligibleAc']['sourceId'], sex_statistic['eligibleAc']['locator']))
                for source_id, source_locator in source_locations:
                    extra_sources.extend(dict(s, locator=source_locator) for s in sources if s['sourceId'] == source_id)
                for extra_source in extra_sources:
                    if not any(s['sourceId'] == extra_source['sourceId'] for s in record_sources):
                        record_sources.append(extra_source)
            key = (scope['hgncId'], scope['mondoId'], mode)
            require(key not in seen, 'EXPERT_DUPLICATE_BACKGROUND'); seen.add(key)
            locator = source['locator']
            exceptions = {'setId': spec['specificationId'] + '-BS1-published-scope', 'version': spec['version'],
                          'criterionId': 'ACMG_AMP_2015:BS1', 'status': 'COMPLETE_IN_PINNED_SCOPE',
                          'entries': [], 'sourceLocator': locator + '; ' + approved['exceptionReview']}
            policy = {'sourceCompleteness': 'COMPLETE', 'semanticSupport': 'SUPPORTED', 'identityResolution': 'RESOLVED',
                      'scope': dict(scope, modeSelection='EXACT_DECLARED_MODE', variantTypes=['SNV', 'SMALL_INSERTION', 'SMALL_DELETION']),
                      'statistic': {'metricId': 'gnomad.exomes.faf95max', 'unit': 'fraction', 'confidence': '95_PERCENT_LOWER_BOUND',
                                    'ancestries': ['afr', 'amr', 'eas', 'nfe', 'sas', 'mid']},
                      'commonConditions': [], 'exceptionSet': exceptions,
                      'requiredContext': {'transcript': 'NOT_REQUIRED_BY_POLICY'},
                      'tiers': [{'tierId': 'PUBLISHED_STRONG', 'moi': mode, 'priority': 1, 'strength': 'STRONG', 'sourceLocator': locator,
                                 'conditions': [{'fact': sex_statistic['sourceTerm'] if is_sex else 'AF' if is_interval else 'FAF95', 'operator': bound['operator'], 'decimal': bound['fraction'],
                                                 'unit': 'fraction', 'basis': 'OFFICIAL_CONDITION', 'originalText': original_text, 'sourceLocator': locator}
                                                for bound in (source_bounds if is_interval or is_sex else [approved])]}]}
            record = {'recordId': spec['specificationId'] + '-BS1-' + mode, 'version': spec['version'], 'kind': 'EXPERT_POLICY',
                      'publicationStatus': 'Released', 'scope': scope, 'sources': record_sources, 'exceptionSet': exceptions, 'expertPolicy': policy,
                      'relationship': {'symbol': gene['gene'], 'diseaseLabel': disease['preferredTitle'], 'sourceId': spec['sourceId'],
                                       'specificationId': spec['specificationId'], 'specificationVersion': spec['version'],
                                       'approvedOn': meta['approvedOn'], 'panel': meta['shortTitle'],
                                       'url': 'https://cspec.clinicalgenome.org/cspec/ui/svi/doc/' + spec['specificationId']}}
            if approved.get('translation') in {TARGET_AF, interval.VERSION}:
                policy['statistic'] = copy.deepcopy(approved['populationStatistic'])
                policy['tiers'][0]['conditions'][0]['fact'] = 'AF'
            if is_sex:
                policy['statistic'] = sex_statistic
            record['review'] = {'status': 'REVIEWED_FOR_LOCAL_USE', 'contentSha256': content_hash(record),
                                'reviewer': review['reviewer'], 'reviewedAt': review['reviewedAt'], 'dataScopePolicyId': review['dataScopePolicyId'],
                                'basis': review['basis'] + '; ' + approved['crossRuleReview'] + '; ' + approved['scopeReview']
                                         + ('; ' + approved['sampleSizeReview'] if is_interval or is_sex else '')}
            result.append(record)
    return result
