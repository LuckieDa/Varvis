"""Validation of explicit, source-adopted formal PP1 parameters.

This module checks the implemented model contract; it does not estimate a
penetrance vector, adopt knowledge, or infer an unreported dosage probability.
"""
from decimal import Decimal, InvalidOperation
import re

FORMAL_METHOD = 'VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1'
MODEL_SCHEMA = 'pp1-formal-parameter-model-v1'
MODEL_VERSION = '1.0.0'
MAX_SIGNIFICANT_DIGITS = 24
MAX_DECIMAL_EXPONENT = 500
MODEL_FIELDS = {'schemaVersion', 'version', 'genotypeModel', 'founderConditioning',
                'ascertainment', 'pointBase', 'phenotypeModel'}
DECIMAL_TEXT = re.compile(r'(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\Z')


def validate_model(model, inheritance):
    """Return reviewable needs; malformed or unsupported parameters never throw.

    The returned records deliberately retain expected/actual values. Probabilities
    are finite decimal strings, bounded before the exact rational calculator.
    """
    needs = []

    def need(code, field, expected, actual, requirement):
        needs.append({'reasonCode': code, 'field': field, 'requirement': requirement,
                      'expected': expected, 'actual': actual})

    if inheritance not in ('AD', 'AR'):
        need('PP1_FORMAL_INHERITANCE_UNSUPPORTED', 'inheritance', ['AD', 'AR'], inheritance,
             '当前正式剂量模型仅支持常染色体AD及AR目标纯合；不回退简单方法。')
    if not isinstance(model, dict):
        need('PP1_FORMAL_MODEL_REQUIRED', 'formalModel', 'explicit versioned parameter object', model,
             '需要有独立来源和版本的完整正式模型，不能由临床标签推定参数。')
        return needs
    unknown = sorted(set(model) - MODEL_FIELDS, key=str)
    if unknown:
        need('PP1_FORMAL_MODEL_FIELDS_UNSUPPORTED', 'formalModel', sorted(MODEL_FIELDS), unknown,
             '模型含当前计算器尚未实现的条件，不能忽略该条件评分。')
    fixed = {'schemaVersion': MODEL_SCHEMA, 'version': MODEL_VERSION,
             'genotypeModel': 'AUTOSOMAL_SINGLE_ALLELE_DOSAGE',
             'founderConditioning': 'ALL_FOUNDERS_TYPED',
             'pointBase': '2.08'}
    for field, expected in fixed.items():
        if model.get(field) != expected:
            need('PP1_FORMAL_PARAMETER_REQUIRED' if field not in model else 'PP1_FORMAL_PARAMETER_UNSUPPORTED',
                 'formalModel.' + field, expected, model.get(field),
                 '需要明确采用当前支持的参数及来源；缺项或其他条件不能由默认值代替。')

    supported_ascertainment = ('PHENOTYPE_BEFORE_GENOTYPING',
                              'CONDITIONAL_SELECTION_AND_REPORTING_INDEPENDENCE')
    if model.get('ascertainment') not in supported_ascertainment:
        need('PP1_FORMAL_PARAMETER_REQUIRED' if 'ascertainment' not in model else 'PP1_FORMAL_PARAMETER_UNSUPPORTED',
             'formalModel.ascertainment', list(supported_ascertainment), model.get('ascertainment'),
             '需独立采用选择与报告条件；表型先于分型是充分路径，也可采用给定表型后的基因型独立机制。')

    def probabilities(value, field):
        if not isinstance(value, dict) or set(value) != {'0', '1', '2'}:
            need('PP1_FORMAL_DOSAGE_PROBABILITIES_REQUIRED', field,
                 {'0': 'decimal string', '1': 'decimal string', '2': 'decimal string'}, value,
                 '必须分别给出剂量0、1、2的患病概率；不默认纯合概率等于杂合概率。')
            return
        for dosage in ('0', '1', '2'):
            probability = value[dosage]
            valid = isinstance(probability, str) and len(probability) <= 64 and bool(DECIMAL_TEXT.fullmatch(probability))
            if valid:
                try:
                    parsed = Decimal(probability)
                    exponent = int(re.split('[eE]', probability)[1]) if re.search('[eE]', probability) else 0
                    valid = (parsed.is_finite() and 0 <= parsed <= 1
                             and len(parsed.as_tuple().digits) <= MAX_SIGNIFICANT_DIGITS
                             and abs(exponent) <= MAX_DECIMAL_EXPONENT
                             and abs(parsed.as_tuple().exponent) <= MAX_DECIMAL_EXPONENT)
                except (InvalidOperation, ValueError, OverflowError):
                    valid = False
            if not valid:
                need('PP1_FORMAL_DOSAGE_PROBABILITY_INVALID', field + '.' + dosage,
                     'decimal string in [0,1], at most 24 significant digits and exponent magnitude 500',
                     probability, '需要范围、精度及计算规模受支持的明确患病概率；不截断、拟合或补值。')

    phenotype = model.get('phenotypeModel')
    if not isinstance(phenotype, dict):
        need('PP1_FORMAL_PHENOTYPE_MODEL_REQUIRED', 'formalModel.phenotypeModel',
             'explicit binary-status or attained-age-band model', phenotype,
             '需要明确表型概率模型；年龄独立和累计年龄模型不能混用。')
        return needs
    kind = phenotype.get('kind')
    if kind == 'AGE_INDEPENDENT_BINARY_STATUS':
        if set(phenotype) != {'kind', 'probabilities'}:
            need('PP1_FORMAL_PHENOTYPE_FIELDS_UNSUPPORTED', 'formalModel.phenotypeModel',
                 ['kind', 'probabilities'], sorted(phenotype, key=str), '年龄独立模型需要完整且仅有已实现字段。')
        probabilities(phenotype.get('probabilities'), 'formalModel.phenotypeModel.probabilities')
    elif kind == 'ATTAINED_AGE_BANDS':
        if set(phenotype) != {'kind', 'bands'}:
            need('PP1_FORMAL_PHENOTYPE_FIELDS_UNSUPPORTED', 'formalModel.phenotypeModel',
                 ['kind', 'bands'], sorted(phenotype, key=str), '累计年龄模型需要完整且仅有已实现字段。')
        bands = phenotype.get('bands')
        if not isinstance(bands, list) or not bands or len(bands) > 64:
            need('PP1_FORMAL_AGE_BANDS_REQUIRED', 'formalModel.phenotypeModel.bands',
                 '1 to 64 contiguous age bands from 0 to no upper limit', bands,
                 '需要明确、完整、无重叠的检查年龄段，不能猜测缺失年龄范围。')
            return needs
        previous = 0
        previous_probabilities = None
        for index, band in enumerate(bands):
            field = 'formalModel.phenotypeModel.bands.' + str(index)
            if not isinstance(band, dict):
                need('PP1_FORMAL_AGE_BAND_INVALID', field, 'age-band object', band,
                     '每个年龄段均须明确边界及三个剂量的累计患病概率。')
                previous = None
                continue
            if set(band) != {'minimumAge', 'maximumAgeExclusive', 'probabilities'}:
                need('PP1_FORMAL_AGE_BAND_INVALID', field,
                     ['minimumAge', 'maximumAgeExclusive', 'probabilities'], sorted(band, key=str),
                     '年龄段存在缺项或未实现条件，不能忽略该条件。')
            low, high = band.get('minimumAge'), band.get('maximumAgeExclusive')
            valid = (type(low) is int and low >= 0 and low == previous
                     and ((index == len(bands) - 1 and high is None)
                          or (index < len(bands) - 1 and type(high) is int and high > low)))
            if not valid:
                need('PP1_FORMAL_AGE_BAND_INVALID', field,
                     'contiguous integer bounds, first minimum 0, final maximum null',
                     {'minimumAge': low, 'maximumAgeExclusive': high},
                     '累计风险年龄段须连续且互斥，末段无上限；检查年龄不能落入未定义范围。')
            before = len(needs)
            values = band.get('probabilities')
            probabilities(values, field + '.probabilities')
            if len(needs) == before:
                current = {dosage: Decimal(values[dosage]) for dosage in ('0', '1', '2')}
                if previous_probabilities and any(current[d] < previous_probabilities[d] for d in current):
                    need('PP1_FORMAL_CUMULATIVE_PROBABILITY_CONFLICT', field + '.probabilities',
                         'nondecreasing cumulative probabilities for each dosage', values,
                         '按累计患病概率定义，较高年龄段的风险不能下降。')
                previous_probabilities = current
            else:
                previous_probabilities = None
            previous = high
    else:
        need('PP1_FORMAL_PHENOTYPE_MODEL_UNSUPPORTED', 'formalModel.phenotypeModel.kind',
             ['AGE_INDEPENDENT_BINARY_STATUS', 'ATTAINED_AGE_BANDS'], kind,
             '当前不支持其他表型模型，例如发病密度或多病种竞争风险。')
    return needs
