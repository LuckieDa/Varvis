"""Generate synthetic-only Python -> Java BA1 cases; no resources or publication.

Run from any directory: python generate_ba1_transfer_fixture.py [--output PATH]
Expectations come from the explicit input parameters, not compiler decisions.
"""
import argparse
from decimal import Decimal
import json
from pathlib import Path

import import_cspec_catalog as compiler
from test_import_cspec_catalog import QC_INSTRUCTION, specimen


def generate():
    rows = []

    def add(number, text, threshold, operator, minimum_an, an_operator, review=None, quality=False):
        entity = specimen(number, text)
        if quality:
            entity['ld']['CriteriaCode'].append({'entContent': {'label': 'PM2', 'instructionsToUse': QC_INSTRUCTION}})
        scope = compiler.normalize_scopes(entity)[0]
        source = {'url': 'https://example.test/synthetic-only', 'sha256': compiler.digest(entity),
                  'jsonPointer': '/', 'specificationId': entity['entId'], 'version': '1.0'}
        result = compiler.compile_ba1(entity, source, scope, review)
        assert result['disposition'] == 'THRESHOLD', result
        row = {'rawEntity': entity, 'scope': scope, 'ba1': result, 'cases': []}
        if review:
            row['semanticReview'] = review
        for delta in ('-0.00000001', '0', '0.00000001'):
            value = Decimal(threshold) + Decimal(delta)
            met = value > Decimal(threshold) if operator == '>' else value >= Decimal(threshold)
            for an in (minimum_an - 1, minimum_an, minimum_an + 1):
                qualified = an > minimum_an if an_operator == '>' else an >= minimum_an
                row['cases'].append({'faf': str(value), 'an': str(an),
                                     'expected': 'NOT_EVALUATED' if not qualified else 'MET' if met else 'NOT_MET'})
        if quality:
            row['qualityFaults'] = ['EXOMES_FILTER', 'GENOMES_FILTER', 'MISSING_EXOMES', 'MISSING_GENOMES',
                                    'EMPTY_GENOMES', 'NO_RECORD', 'INCOMPLETE_SUPPLEMENT']
        rows.append(row)

    for number in (7577, 9999):
        for threshold, an in (('0.001', 2000), ('0.0070', 2500)):
            for operator in ('>', '>='):
                for an_operator in ('>', '>='):
                    add(number, f'gnomAD joint GroupMax FAF95 {operator} {threshold}; same population AN {an_operator} {an}.',
                        threshold, operator, an, an_operator)
    review = {'reviewId': 'SYNTHETIC_ONLY_NOT_PUBLICATION', 'reviewSha256': 'c' * 64,
              'sources': [{'url': 'https://example.test/synthetic-guidance', 'sha256': 'd' * 64}]}
    for number, denominator, percent, threshold in ((7577, '10,000', '0.01', '0.0001'), (9999, '20,000', '0.005', '0.00005')):
        for operator in ('>', '>='):
            add(number, f'gnomAD Grpmax FAF {operator} 1:{denominator} ({operator} {percent}% or {threshold})',
                threshold, operator, 2000, '>=', review)
    add(7577, 'gnomAD joint GroupMax FAF95 >= 0.001; same population AN >= 2000.',
        '0.001', '>=', 2000, '>=', quality=True)
    add(9999, 'gnomAD Grpmax FAF >= 1:10,000 (>= 0.01% or 0.0001)',
        '0.0001', '>=', 2000, '>=', review, quality=True)
    return {'purpose': 'SYNTHETIC_ONLY_PYTHON_COMPILED_NOT_PUBLISHED',
            'compilerVersion': compiler.VERSION, 'compilerSha256': compiler.file_sha(Path(compiler.__file__)), 'rows': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[2] /
                        'varvis-server/src/test/resources/acmg/ba1-python-compiled-transfer.json')
    args = parser.parse_args()
    args.output.write_bytes((json.dumps(generate(), indent=2, ensure_ascii=False) + '\n').encode('utf-8'))


if __name__ == '__main__':
    main()
