"""Finite source comparison atoms; not a policy, sex-scope or approval compiler.

Recognizes only an explicit comparator and a bounded decimal or HTML-superscript
base-10 quantity. Optional percent representations must be numerically equivalent.
The caller retains complete source prose and independently reviews applicability.
"""
from decimal import Decimal, DecimalException, localcontext
import re

MAX_EXPRESSION_LENGTH = 512
MAX_DIGITS = 50
MAX_EXPONENT = 100
_H = r'[ \t]*'
_DECIMAL = r'(?:0|[1-9][0-9]*)(?:\.[0-9]+)?'
_EXPONENT = r'[+-]?(?:0|[1-9][0-9]*)'
_OPERATOR = r'(?:>=|<=|≥|≤|>|<)'
_SUP_NUMBER = _DECIMAL + _H + r'[x×]' + _H + r'10<sup>' + _H + _EXPONENT + _H + r'</sup>'
_QUANTITY = r'(?:' + _SUP_NUMBER + r'|' + _DECIMAL + r')'
_EXPRESSION = re.compile(
    _H + r'(?P<operator>' + _OPERATOR + r')' + _H
    + r'(?P<quantity>' + _QUANTITY + r')' + _H + r'(?P<percent>%)?'
    + _H + r'(?:\(' + _H + r'(?P<equivalent_operator>' + _OPERATOR + r')?' + _H
    + r'(?P<equivalent_percent>' + _DECIMAL + r')' + _H + r'%' + _H + r'\))?' + _H)
_SUP_PARTS = re.compile(r'(?P<mantissa>' + _DECIMAL + r')' + _H + r'[x×]' + _H
                        + r'10<sup>' + _H + r'(?P<exponent>' + _EXPONENT + r')' + _H + r'</sup>')
_OPERATORS = {'>': 'GT', '>=': 'GTE', '≥': 'GTE', '<': 'LT', '<=': 'LTE', '≤': 'LTE'}


def _decimal(text):
    # Leading fractional zeros are scale, not significant coefficient digits.
    # Keep exact plain-decimal inputs that the existing BigDecimal consumer accepts.
    number = Decimal(text)
    return number if len(number.as_tuple().digits) <= MAX_DIGITS else None


def _quantity(text):
    scientific = _SUP_PARTS.fullmatch(text)
    if scientific is None:
        return _decimal(text)
    exponent = scientific['exponent']
    if len(exponent.lstrip('+-')) > 3:
        return None
    exponent = int(exponent)
    if abs(exponent) > MAX_EXPONENT:
        return None
    mantissa = _decimal(scientific['mantissa'])
    return None if mantissa is None else mantissa.scaleb(exponent)


def _canonical(value):
    result = format(value, 'f')
    if '.' in result:
        result = result.rstrip('0').rstrip('.')
    return result or '0'


def parse_comparison(expression):
    """Return an exact comparison atom or None, preserving every source character.

    No implicit operator, generic HTML, E notation, surrounding prose, sex/MOI,
    dataset, confidence interpretation, threshold migration or clinical adoption
    is inferred. A parenthesized percent is accepted only as a checked duplicate
    numeric representation with the same explicit comparator, if supplied.
    """
    if not isinstance(expression, str) or len(expression) > MAX_EXPRESSION_LENGTH:
        return None
    match = _EXPRESSION.fullmatch(expression)
    if match is None:
        return None
    try:
        # Bounded inputs need at most 152 places; never inherit a caller's lower
        # decimal precision or round the source's boundary during conversion.
        with localcontext() as context:
            context.prec = 256
            value = _quantity(match['quantity'])
            if value is None:
                return None
            if match['percent']:
                value = value.scaleb(-2)
            if not value.is_finite() or value < 0 or value > 1 or value.as_tuple().exponent < -MAX_EXPONENT:
                return None
            percent = match['equivalent_percent']
            if percent is not None:
                duplicate = _decimal(percent)
                if duplicate is None or duplicate.scaleb(-2) != value:
                    return None
                other_operator = match['equivalent_operator']
                if other_operator is not None and _OPERATORS[other_operator] != _OPERATORS[match['operator']]:
                    return None
            return {'originalExpression': expression,
                    'operator': _OPERATORS[match['operator']],
                    'fraction': _canonical(value)}
    except (DecimalException, ValueError, OverflowError):
        return None
