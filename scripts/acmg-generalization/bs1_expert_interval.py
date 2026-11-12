"""Explicit source-bound AF intervals, not uncertain model parameter ranges."""
import re
from decimal import Decimal
from bs1_knowledge import require

VERSION = 'REVIEWED_TARGET_AF_INTERVAL_V1'
_NUMBER = r'0\.\d+'
_LOWER = r'(greater than or equal to|greater than|>=|≥|>)'
_UPPER = r'(less than or equal to|less than|<=|≤|<)'
_VALUE = r'\s*(' + _NUMBER + r')(%)?(?:\s*\((' + _NUMBER + r')%\))?'
_INTERVAL = re.compile(_LOWER + _VALUE + r'\s+(?:but|and)\s+\\?' + _UPPER + _VALUE)
_OPERATORS = {'greater than or equal to':'GTE', 'greater than':'GT', '>=':'GTE', '≥':'GTE', '>':'GT',
              'less than or equal to':'LTE', 'less than':'LT', '<=':'LTE', '≤':'LTE', '<':'LT'}


def bounds(approved, clause, strengths):
    require(len(strengths) == 1 and strengths[0].get('strength') == 'Strong'
            and clause.get('instructionsToUse') == approved.get('instruction')
            and strengths[0].get('text') == approved.get('clauseText'), 'EXPERT_CLAUSE_REVIEW_MISMATCH')
    require(approved.get('statisticReview') and approved.get('additionalConditionsReview'), 'EXPERT_SEMANTIC_REVIEW_REQUIRED')
    expression = approved.get('intervalExpression')
    require(isinstance(expression, str) and len(expression) <= 300, 'EXPERT_INTERVAL_EXPRESSION_INVALID')
    match = _INTERVAL.fullmatch(expression)
    require(match is not None, 'EXPERT_INTERVAL_EXPRESSION_INVALID')
    text = approved['clauseText']
    require(len(re.findall(r'(?<![\w<>=≥≤.])' + re.escape(expression) + r'(?![\w%]|\.\d)', text)) == 1,
            'EXPERT_INTERVAL_EXPRESSION_UNBOUND')
    result = []
    for name, offset in [('lowerBound', 0), ('upperBound', 4)]:
        token, number, percent, parenthetical = match.groups()[offset:offset+4]
        value = Decimal(number) / (100 if percent else 1)
        require(parenthetical is None or Decimal(parenthetical) / 100 == value, 'EXPERT_INTERVAL_PERCENT_MISMATCH')
        bound = approved.get(name)
        require(isinstance(bound, dict) and set(bound) == {'operator','fraction'}
                and isinstance(bound['fraction'], str) and re.fullmatch(_NUMBER, bound['fraction']) is not None,
                'EXPERT_INTERVAL_BOUND_INVALID')
        require(_OPERATORS[token] == bound['operator'] and Decimal(bound['fraction']) == value,
                'EXPERT_INTERVAL_BOUND_MISMATCH')
        result.append(bound)
    require(Decimal(0) < Decimal(result[0]['fraction']) < Decimal(result[1]['fraction']) < Decimal(1),
            'EXPERT_INTERVAL_ORDER_INVALID')
    # A source's interval does not itself determine ancestry selection or minimum AN.
    require(approved.get('sampleSizeReview'), 'EXPERT_INTERVAL_SAMPLE_REVIEW_REQUIRED')
    return result


def source_metric_and_sample(approved, text, statistic):
    label = approved.get('sourceMetricExpression')
    allowed = {'AF': {'AF', 'allele frequency'}, 'MAF': {'MAF', 'minor allele frequency'}}
    require(label in allowed.get(statistic['sourceTerm'], set())
            and re.search(r'(?<!\w)' + re.escape(label) + r'(?!\w)', text) is not None,
            'EXPERT_AF_SOURCE_INTERPRETATION_REQUIRED')
    an = statistic['eligibleAn']; basis = approved.get('sampleSizeBasis')
    if basis == 'LOCAL_ENGINEERING_MINIMUM':
        require(an == {'operator':'GTE','decimal':'2000'} and 'eligibleAnExpression' not in approved
                and approved.get('sampleSizeReview'), 'EXPERT_INTERVAL_LOCAL_AN_INVALID')
        return
    require(basis == 'SOURCE_STATED', 'EXPERT_INTERVAL_SAMPLE_BASIS_REQUIRED')
    expression = approved.get('eligibleAnExpression', '')
    require(isinstance(expression, str) and expression and len(re.findall(
            r'(?<![\w<>=≥≤.])' + re.escape(expression) + r'(?![\w%]|\.\d)', text)) == 1,
            'EXPERT_AF_AN_EXPRESSION_UNBOUND')
    m = re.fullmatch(r'(at least)\s+([0-9]+(?:,[0-9]{3})*)\s+(?:observed\s+)?alleles',expression,re.I)
    if m:
        operator, number = 'GTE', m[2].replace(',','')
    else:
        m = re.fullmatch(r'(>=|≥|>)\s*([0-9]+)',expression)
        require(m is not None,'EXPERT_AF_AN_EXPRESSION_UNBOUND')
        operator, number = ('GT' if m[1] == '>' else 'GTE'), m[2]
    require(an['operator'] == operator and int(an['decimal']) == int(number), 'EXPERT_AF_AN_EXPRESSION_UNBOUND')
