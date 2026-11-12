import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

import bs1_expert_sex_chromosome as sex
from bs1_expert_batch import compile_reviews, content_hash
import bs1_knowledge as knowledge
import bs1_qualification as qualification
import test_bs1_expert_batch as fixtures


class SexChromosomeExpertTest(unittest.TestCase):
    def fixture(self, dataset='EXOMES', point_af=False):
        f = fixtures.ExpertBatchTest()
        a, clause = f.reviewed_bound('≥0.0002', '0.0002', 'GTE')
        f.entity['entId'] = 'GN001'
        f.entity['entContent']['version'] = '1'
        term = 'allele frequency' if point_af else 'Grpmax Filtering Allele Frequency'
        text = term + ' ≥0.0002 (0.02%).'
        clause['strengthDescriptor'][0]['text'] = text
        disease = f.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        disease['modesOfInheritance'] = [{'modeOfInheritance': 'X-linked inheritance'}]
        node = {'hgncId': 'HGNC:1', 'mondoId': 'MONDO:0000001', 'moi': 'X_LINKED_RECESSIVE',
                'classification': 'Definitive', 'basis': 'SYNTHETIC SOURCE CONTROL; NOT CLINICAL APPROVAL'}
        f.sources.append({'sourceId': 'independent-moi', 'url': 'https://example.org/synthetic-moi',
                          'version': 'synthetic-1', 'sha256': 'b' * 64})
        documents = {'independent-moi': [node]}
        a.update(translation=sex.VERSION, clauseText=text, thresholdExpression='≥0.0002 (0.02%)', expectedBackgrounds=1,
            sexChromosomeScope={'assemblies': ['GRCh38'], 'contigs': ['X'], 'regions': ['NON_PAR'],
                'regionCatalogSha256': sex.REGION_CATALOG_SHA256, 'requiredDataset': dataset,
                'sourceId': 'raw', 'sourceLocator': 'GN001/ld/RuleSet'},
            sourceMetricExpression=term, sampleSizeBasis='LOCAL_ENGINEERING_MINIMUM',
            sampleSizeReview='AN>=2000 is a local safeguard, not a source-stated criterion.',
            populationStatistic={'metricId': 'gnomad.exomes.sex_ancestry_max_' + ('af' if point_af else 'faf95'),
                'unit': 'fraction', 'confidence': 'POINT_ESTIMATE' if point_af else '95_PERCENT_LOWER_BOUND',
                'ancestries': ['afr', 'eas'], 'karyotypes': ['XY'], 'eligibleAn': {'operator': 'GTE', 'decimal': '2000'},
                'sourceTerm': 'AF' if point_af else 'FAF95', 'interpretation': 'TARGET_ALT_AF' if point_af else 'TARGET_ALT_FAF95',
                'interpretationBasis': 'Synthetic source explicitly reviewed for this statistic and stratum.',
                'sourceId': 'raw', 'locator': 'GN001/ld/CriteriaCode/0/entContent/strengthDescriptor/0/text'},
            reviewedModeMappings=[{'sourceMode': 'X-linked inheritance', 'targetMode': 'X_LINKED_RECESSIVE',
                'hgncId': 'HGNC:1', 'mondoId': 'MONDO:0000001', 'sourceId': 'independent-moi', 'locator': '/0',
                'contentSha256': content_hash(node), 'interpretationBasis': 'Exact, independent synthetic gene-disease MOI.'}])
        a['entitySha256'] = content_hash(f.entity)
        if dataset == 'JOINT':
            f.review['dataScopePolicyId'] = 'BS1_JOINT_SEX_V1'
            a['decision'] = 'ADOPT_JOINT_SEX'
            a['populationStatistic']['metricId'] = a['populationStatistic']['metricId'].replace('gnomad.exomes.', 'gnomad.joint.')
        return f, a, documents

    def test_exact_source_mapping_preserves_scope_statistic_and_threshold_without_model(self):
        f, a, documents = self.fixture()
        before = copy.deepcopy((f.review, f.specs, f.sources, documents))
        record, = compile_reviews(f.review, f.specs, f.sources, documents)
        self.assertEqual(['X_LINKED_RECESSIVE'], record['scope']['modes'])
        self.assertEqual(record['scope']['sexChromosome'], record['expertPolicy']['scope']['sexChromosome'])
        self.assertEqual(a['populationStatistic'], record['expertPolicy']['statistic'])
        self.assertEqual([('FAF95', 'GTE', '0.0002')], [(c['fact'], c['operator'], c['decimal'])
                         for c in record['expertPolicy']['tiers'][0]['conditions']])
        self.assertEqual({'raw', 'independent-moi'}, {s['sourceId'] for s in record['sources']})
        self.assertNotIn('model', record)
        self.assertEqual(before, (f.review, f.specs, f.sources, documents))

    def test_missing_generic_x_mapping_is_unresolved_and_never_guesses_dominance(self):
        f, a, documents = self.fixture()
        a.pop('reviewedModeMappings')
        prepared = sex.prepare_reviews(f.review, f.specs, f.sources, documents)
        self.assertEqual(('UNRESOLVED', 'EXPERT_SEX_MOI_MAPPING_REQUIRED', []),
                         (prepared['status'], prepared['reasonCode'], prepared['records']))
        self.assertFalse(prepared['scientificallyAdopted'])
        self.assertFalse(prepared['bs1Evaluated'])

    def test_ambiguous_x_mapping_is_distinct_from_missing_source(self):
        f, a, documents = self.fixture()
        a['reviewedModeMappings'].append(copy.deepcopy(a['reviewedModeMappings'][0]))
        prepared = sex.prepare_reviews(f.review, f.specs, f.sources, documents)
        self.assertEqual('EXPERT_SEX_MOI_MAPPING_AMBIGUOUS', prepared['reasonCode'])
        self.assertEqual([], prepared['records'])

    def test_moi_cannot_be_borrowed_from_another_disease_or_changed_after_review(self):
        for fault in ('DISEASE', 'MODE', 'RAW_NODE', 'SOURCE', 'INDEPENDENCE', 'SOURCE_ALIAS'):
            with self.subTest(fault=fault):
                f, a, documents = self.fixture()
                mapping = a['reviewedModeMappings'][0]
                if fault == 'DISEASE': mapping['mondoId'] = 'MONDO:0000002'
                elif fault == 'MODE': mapping['targetMode'] = 'X_LINKED_DOMINANT'
                elif fault == 'RAW_NODE': documents['independent-moi'][0]['basis'] = 'source changed'
                elif fault == 'SOURCE': documents.clear()
                elif fault == 'INDEPENDENCE': mapping['sourceId'] = 'raw'
                elif fault == 'SOURCE_ALIAS': f.sources[1]['sha256'] = f.sources[0]['sha256']
                with self.assertRaises(ValueError): compile_reviews(f.review, f.specs, f.sources, documents)

    def test_joint_requirement_cannot_be_satisfied_by_available_exomes(self):
        f, _, documents = self.fixture('JOINT')
        record, = compile_reviews(f.review, f.specs, f.sources, documents)
        result = sex.local_readiness(record, resource_available=True)
        self.assertEqual(('BLOCKED', 'BS1_SEX_RESOURCE_DATASET_MISMATCH'), (result['status'], result['reasonCode']))
        self.assertFalse(result['bs1Evaluated'])
        f, _, documents = self.fixture()
        record, = compile_reviews(f.review, f.specs, f.sources, documents)
        self.assertEqual('BS1_RESOURCE_EXECUTION_UNAVAILABLE', sex.local_readiness(record)['reasonCode'])
        self.assertEqual('ELIGIBLE_FOR_RUNTIME_VALIDATION', sex.local_readiness(record, True)['status'])
        with self.assertRaisesRegex(ValueError, 'DATASET_REQUIREMENT_INVALID'):
            sex.dataset_readiness({'requiredDataset': 'UNKNOWN'}, resource_available=True)

    def test_af_and_interval_have_independent_facts_and_exact_bounds(self):
        f, a, documents = self.fixture(point_af=True)
        expression = '≥0.0002 (0.02%) and <0.001 (0.1%)'
        text = 'allele frequency ' + expression + '. At least 2,000 observed alleles.'
        f.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['text'] = text
        a.update(clauseText=text, intervalExpression=expression, lowerBound={'operator': 'GTE', 'fraction': '0.0002'},
                 upperBound={'operator': 'LT', 'fraction': '0.001'}, sampleSizeBasis='SOURCE_STATED',
                 eligibleAnExpression='At least 2,000 observed alleles')
        for key in ('thresholdExpression', 'operator', 'fraction'): a.pop(key)
        a['entitySha256'] = content_hash(f.entity)
        record, = compile_reviews(f.review, f.specs, f.sources, documents)
        self.assertEqual([('AF', 'GTE'), ('AF', 'LT')], [(c['fact'], c['operator'])
                         for c in record['expertPolicy']['tiers'][0]['conditions']])
        a['populationStatistic']['eligibleAn']['operator'] = 'GT'
        with self.assertRaisesRegex(ValueError, 'AN_EXPRESSION_UNBOUND'):
            compile_reviews(f.review, f.specs, f.sources, documents)

    def test_wrong_statistic_region_source_or_bound_does_not_compile(self):
        for fault in ('METRIC', 'AN', 'STRATUM', 'SOURCE', 'CATALOG', 'PAR', 'THRESHOLD', 'PERCENT', 'EXTRA_FIELD', 'DRAFT', 'DISEASE_SCOPE'):
            with self.subTest(fault=fault):
                f, a, documents = self.fixture()
                if fault == 'METRIC': a['populationStatistic']['confidence'] = 'POINT_ESTIMATE'
                elif fault == 'AN': a['populationStatistic']['eligibleAn']['decimal'] = '1999'
                elif fault == 'STRATUM': a['populationStatistic']['karyotypes'] = ['male']
                elif fault == 'SOURCE': a['sexChromosomeScope']['sourceId'] = 'absent'
                elif fault == 'CATALOG': a['sexChromosomeScope']['regionCatalogSha256'] = '0' * 64
                elif fault == 'PAR': a['sexChromosomeScope']['regions'] = ['PAR']
                elif fault == 'THRESHOLD': a['fraction'] = '0.02'
                elif fault == 'PERCENT': a['thresholdExpression'] = '≥0.0002 (0.2%)'
                elif fault == 'EXTRA_FIELD': a['populationStatistic']['implicitPloidy'] = 1
                elif fault == 'DRAFT':
                    f.entity['entContent']['states'] = [{'name': 'Pilot Rules In Prep', 'current': True}]
                    a['entitySha256'] = content_hash(f.entity)
                elif fault == 'DISEASE_SCOPE':
                    f.entity['ld']['CriteriaCode'][0]['entContent']['disease'] = ['MONDO:0000002']
                    a['entitySha256'] = content_hash(f.entity)
                with self.assertRaises(ValueError): compile_reviews(f.review, f.specs, f.sources, documents)

    def test_catalog_identity_matches_the_shared_s0_original(self):
        path = Path(__file__).resolve().parents[2] / 'varvis-server/src/main/resources/acmg/sex-chromosome-regions-v1.json'
        self.assertEqual(sex.REGION_CATALOG_SHA256, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_manifest_raw_replay_preserves_mcf_unsupported_for_x(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            f, a, documents = self.fixture()
            payloads = {'raw.json': {'status': {'code': 200}, 'data': [dict(f.entity, entId='GN001')]},
                        'moi.json': documents['independent-moi'], 'review.json': f.review}
            entries = []
            for filename, source_id, kind in [('raw.json', 'raw', 'CSPEC_RAW'), ('moi.json', 'independent-moi', 'CLINGEN_JSON'),
                                               ('review.json', 'review', 'EXPERT_CLAUSE_REVIEW')]:
                knowledge.write(directory / filename, payloads[filename])
                entries.append({'sourceId': source_id, 'kind': kind, 'path': filename, 'url': 'https://example.org/' + source_id,
                                'version': 'synthetic-1', 'sha256': knowledge.sha((directory / filename).read_bytes())})
            knowledge.write(directory / 'manifest.json', {'schemaVersion': 'bs1-knowledge-input-v1', 'version': 'synthetic-1', 'sources': entries})
            knowledge.compile_manifest(directory / 'manifest.json', directory / 'out')
            bundle = knowledge.read_json((directory / 'out/bundle.json').read_bytes())
            self.assertEqual(1, len(bundle['records']))
            self.assertEqual('BS1_MODEL_MOI_UNSUPPORTED', bundle['backgrounds'][0]['reasonCode'])
            drafts = knowledge.read_json((directory / 'out/parameter-drafts.json').read_bytes())
            self.assertFalse(drafts[0]['modelExecutable'])
            self.assertEqual('COMPILED_NOT_SCIENTIFICALLY_ADOPTED',
                             knowledge.read_json((directory / 'out/expert-sex-preparation.json').read_bytes())[0]['status'])
            record = bundle['records'][0]
            original_documents = {'raw': knowledge.read_json((directory / 'raw.json').read_bytes()),
                                  'independent-moi': knowledge.read_json((directory / 'moi.json').read_bytes())}
            locations = {(s['sourceId'], s['locator']) for s in record['sources']}
            locations.add((a['sexChromosomeScope']['sourceId'], a['sexChromosomeScope']['sourceLocator']))
            locations.add((a['populationStatistic']['sourceId'], a['populationStatistic']['locator']))
            bindings = [{'sourceId': source_id, 'locator': locator,
                         'contentSha256': content_hash(qualification.resolve(original_documents[source_id], locator))}
                        for source_id, locator in sorted(locations)]
            checks = {category: {'status': 'PUBLISHED_THRESHOLD_NOT_RECALCULATED' if category == 'PARAMETER_SCOPE' else 'SATISFIED',
                                'basis': 'SYNTHETIC engineering contract only; fixed raw nodes actually replayed.',
                                'sourceIds': ['raw', 'independent-moi']}
                      for category in qualification.CATEGORIES}
            review = {'schemaVersion': 'bs1-qualification-input-v1', 'reviewer': 'SYNTHETIC', 'reviewedAt': '2026-11-03',
                      'basis': 'SYNTHETIC cross-language contract; no scientific adoption.', 'entries': [
                          {'record': record, 'payloadSha256': content_hash(qualification.payload(record)),
                           'sourceBindings': bindings, 'checks': checks}]}
            knowledge.write(directory / 'qualification.json', review)
            entries.append({'sourceId': 'qualification', 'kind': 'QUALIFICATION_REVIEW', 'path': 'qualification.json',
                            'url': 'https://example.org/synthetic-qualification', 'version': 'synthetic-1',
                            'sha256': knowledge.sha((directory / 'qualification.json').read_bytes())})
            knowledge.write(directory / 'qualified-manifest.json', {'schemaVersion': 'bs1-knowledge-input-v1', 'version': 'synthetic-1', 'sources': entries})
            knowledge.compile_manifest(directory / 'qualified-manifest.json', directory / 'qualified')
            qualified = knowledge.read_json((directory / 'qualified/bundle.json').read_bytes())['records'][0]
            self.assertEqual(4, len(qualified['qualification']['sourceBindings']))
            self.assertEqual('GN001', qualified['expertPolicy']['tiers'][0]['sourceLocator'])
            self.assertEqual(locations, {(s['sourceId'], s['locator']) for s in qualified['qualification']['sourceBindings']})
            self.assertEqual(content_hash({k: v for k, v in qualified.items() if k != 'review'}), qualified['review']['contentSha256'])


    def ac_fixture(self, expression='≥5 variant alleles', operator='GTE', number='5'):
        f, a, documents = self.fixture(point_af=True)
        text = a['clauseText'] + ' Eligible strata must contain ' + expression + '.'
        f.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['text'] = text
        a.update(clauseText=text, eligibleAcExpression=expression)
        locator = a['populationStatistic']['locator']
        a['populationStatistic']['eligibleAc'] = {'operator': operator, 'decimal': number, 'unit': 'alleles',
                                                'sourceTerm': 'AC', 'sourceId': 'raw', 'locator': locator}
        a['supportingSourceBindings'] = [{'sourceId': 'raw', 'locator': locator,
            'contentSha256': content_hash(text),
            'interpretationBasis': 'SYNTHETIC: explicit variant alleles means target-ALT AC, never people or observations.'}]
        documents['raw'] = {'data': [dict(f.entity, entId='GN001')]}
        a['entitySha256'] = content_hash(f.entity)
        return f, a, documents

    def test_scientific_expression_runs_through_full_expert_compiler_without_removing_male_prose(self):
        f, a, documents = self.fixture(point_af=True)
        expression = '≥8.3x10<sup>-5 </sup>'
        text = 'Allele frequency in males ' + expression + ' (based on the most frequent pathogenic allele).'
        f.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['text'] = text
        a.update(clauseText=text, thresholdExpression=expression, fraction='0.000083')
        a['entitySha256'] = content_hash(f.entity)
        record, = compile_reviews(f.review, f.specs, f.sources, documents)
        condition, = record['expertPolicy']['tiers'][0]['conditions']
        self.assertEqual(('GTE', '0.000083', text), (condition['operator'], condition['decimal'], condition['originalText']))
        self.assertIn('males', condition['originalText'])
        self.assertIn('most frequent pathogenic allele', condition['originalText'])
        for expression in ['≥8.3x10<sup>-4 </sup>', '≥8.3x10<sup>-5 </sup> (in males)',
                           '≥8.3e-5', '≥8.3x10<sup>-5 </sup> extra', '≥8.3x10<sup>-5 </sup> (0.083%)']:
            a['thresholdExpression'] = expression
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                compile_reviews(f.review, f.specs, f.sources, documents)

    def test_explicit_allele_count_is_preserved_only_with_bound_original_and_interpretation(self):
        for expression, operator in [('≥5 variant alleles', 'GTE'), ('>5 variant alleles', 'GT'),
                                     ('AC >=5', 'GTE'), ('minimum of five variant alleles present', 'GTE')]:
            with self.subTest(expression=expression):
                f, a, documents = self.ac_fixture(expression, operator)
                before = copy.deepcopy((f.review, f.specs, f.sources, documents))
                record, = compile_reviews(f.review, f.specs, f.sources, documents)
                self.assertEqual(a['populationStatistic']['eligibleAc'], record['expertPolicy']['statistic']['eligibleAc'])
                self.assertEqual(a['clauseText'], record['expertPolicy']['tiers'][0]['conditions'][0]['originalText'])
                self.assertEqual(before, (f.review, f.specs, f.sources, documents))

    def test_count_precision_matches_sex_policy_before_record_emission(self):
        number = '1' * 50
        f, a, documents = self.ac_fixture('AC >= ' + number, number=number)
        record, = compile_reviews(f.review, f.specs, f.sources, documents)
        self.assertEqual(number, record['expertPolicy']['statistic']['eligibleAc']['decimal'])
        for number in ('1' * 51, '1' * 80, '1' * 81):
            with self.subTest(digits=len(number)):
                f, _, documents = self.ac_fixture('AC >= ' + number, number=number)
                with self.assertRaisesRegex(ValueError, 'AC_REQUIREMENT_INVALID'):
                    compile_reviews(f.review, f.specs, f.sources, documents)
        for precision in (50, 51):
            with self.subTest(an_precision=precision):
                f, a, documents = self.fixture(point_af=True)
                number = '1' * precision
                expression = 'at least ' + number + ' alleles'
                text = a['clauseText'] + ' ' + expression + '.'
                f.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['text'] = text
                a.update(clauseText=text, sampleSizeBasis='SOURCE_STATED', eligibleAnExpression=expression)
                a['populationStatistic']['eligibleAn']['decimal'] = number
                a['entitySha256'] = content_hash(f.entity)
                if precision == 50:
                    self.assertEqual(1, len(compile_reviews(f.review, f.specs, f.sources, documents)))
                else:
                    with self.assertRaisesRegex(ValueError, 'SEX_AN_REQUIREMENT_INVALID'):
                        compile_reviews(f.review, f.specs, f.sources, documents)

    def test_frequency_serialized_length_and_precision_match_sex_policy(self):
        for length in (80, 81):
            with self.subTest(length=length):
                f, a, documents = self.fixture(point_af=True)
                expression = '≥1x10<sup>-' + str(length - 2) + '</sup>'
                fraction = '0.' + '0' * (length - 3) + '1'
                text = 'allele frequency ' + expression + '.'
                f.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['text'] = text
                a.update(clauseText=text, thresholdExpression=expression, fraction=fraction)
                a['entitySha256'] = content_hash(f.entity)
                if length == 80:
                    record, = compile_reviews(f.review, f.specs, f.sources, documents)
                    self.assertEqual(fraction, record['expertPolicy']['tiers'][0]['conditions'][0]['decimal'])
                else:
                    with self.assertRaisesRegex(ValueError, 'SEX_RUNTIME_NUMBER_INVALID'):
                        compile_reviews(f.review, f.specs, f.sources, documents)
        # BigDecimal precision includes significant trailing zeroes. Normalizing
        # the reviewed spelling before this check would hide a Java rejection.
        for precision in (50, 51):
            with self.subTest(precision=precision):
                f, a, documents = self.fixture(point_af=True)
                text = 'allele frequency ≥0.1.'
                f.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['text'] = text
                a.update(clauseText=text, thresholdExpression='≥0.1', fraction='0.1' + '0' * (precision - 1))
                a['entitySha256'] = content_hash(f.entity)
                if precision == 50:
                    self.assertEqual(1, len(compile_reviews(f.review, f.specs, f.sources, documents)))
                else:
                    with self.assertRaisesRegex(ValueError, 'SEX_RUNTIME_NUMBER_INVALID'):
                        compile_reviews(f.review, f.specs, f.sources, documents)

    def test_ac_source_unit_count_and_review_tamper_cannot_become_a_policy(self):
        for fault in ('SOURCE', 'LOCATOR', 'DOC', 'HASH', 'BINDING', 'DUPLICATE_BINDING', 'BOOL_REVIEW',
                      'COUNT', 'OPERATOR', 'OPERATOR_LIST', 'OPERATOR_OBJECT', 'UNIT', 'SOURCE_TERM', 'EXTRA', 'INTERPRETATION_BOOL', 'NUMBER_TYPE'):
            with self.subTest(fault=fault):
                f, a, documents = self.ac_fixture()
                rule = a['populationStatistic']['eligibleAc']
                if fault == 'SOURCE': rule['sourceId'] = 'absent'
                elif fault == 'LOCATOR': rule['locator'] = '/missing'
                elif fault == 'DOC': documents.pop('raw')
                elif fault == 'HASH': a['supportingSourceBindings'][0]['contentSha256'] = '0' * 64
                elif fault == 'BINDING': a['supportingSourceBindings'] = []
                elif fault == 'DUPLICATE_BINDING': a['supportingSourceBindings'].append(copy.deepcopy(a['supportingSourceBindings'][0]))
                elif fault == 'BOOL_REVIEW': a['additionalConditionsReview'] = True
                elif fault == 'COUNT': rule['decimal'] = '6'
                elif fault == 'OPERATOR': rule['operator'] = 'GT'
                elif fault == 'OPERATOR_LIST': rule['operator'] = ['GTE']
                elif fault == 'OPERATOR_OBJECT': rule['operator'] = {'value': 'GTE'}
                elif fault == 'UNIT': rule['unit'] = 'persons'
                elif fault == 'SOURCE_TERM': rule['sourceTerm'] = 'HOMOZYGOUS_INDIVIDUALS'
                elif fault == 'EXTRA': rule['selfApproved'] = True
                elif fault == 'INTERPRETATION_BOOL': a['supportingSourceBindings'][0]['interpretationBasis'] = True
                elif fault == 'NUMBER_TYPE': rule['decimal'] = 5
                with self.assertRaises(ValueError): compile_reviews(f.review, f.specs, f.sources, documents)

    def test_recognized_source_allele_requirement_cannot_be_removed_or_partially_selected(self):
        for location in ('CLAUSE', 'INSTRUCTION'):
            with self.subTest(location=location):
                f, a, documents = self.ac_fixture()
                a['populationStatistic'].pop('eligibleAc')
                a.pop('eligibleAcExpression')
                if location == 'INSTRUCTION':
                    clause = f.entity['ld']['CriteriaCode'][0]['entContent']
                    clause['strengthDescriptor'][0]['text'] = 'allele frequency ≥0.0002 (0.02%).'
                    clause['instructionsToUse'] = 'Eligible strata must contain ≥5 variant alleles.'
                    a.update(clauseText=clause['strengthDescriptor'][0]['text'], instruction=clause['instructionsToUse'])
                    a['entitySha256'] = content_hash(f.entity)
                with self.assertRaisesRegex(ValueError, '^EXPERT_SEX_AC_REQUIREMENT_MISSING$'):
                    compile_reviews(f.review, f.specs, f.sources, documents)
        for second in ('AC >= 6', '>5 variant alleles', 'minimum of 6 variant alleles'):
            with self.subTest(second=second):
                f, a, documents = self.ac_fixture()
                text = a['clauseText'] + ' Also require ' + second + '.'
                f.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['text'] = text
                a['clauseText'] = text
                a['supportingSourceBindings'][0]['contentSha256'] = content_hash(text)
                a['entitySha256'] = content_hash(f.entity)
                with self.assertRaisesRegex(ValueError, '^EXPERT_SEX_AC_REQUIREMENT_AMBIGUOUS$'):
                    compile_reviews(f.review, f.specs, f.sources, documents)

    def test_observations_people_or_an_unbound_count_fragment_are_not_ac(self):
        for expression in ('≥5 observations', '≥5 homozygotes', '≥5 individuals', '5 variant alleles',
                           'at least five observations', '≥5.0 variant alleles', '≥-5 variant alleles'):
            with self.subTest(expression=expression):
                f, _, documents = self.ac_fixture(expression)
                with self.assertRaisesRegex(ValueError, 'AC_EXPRESSION'):
                    compile_reviews(f.review, f.specs, f.sources, documents)
        f, a, documents = self.ac_fixture()
        a['eligibleAcExpression'] = '≥5'
        with self.assertRaisesRegex(ValueError, 'AC_EXPRESSION'):
            compile_reviews(f.review, f.specs, f.sources, documents)
        f, a, documents = self.ac_fixture()
        a['clauseText'] += ' Again ≥5 variant alleles.'
        f.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][0]['text'] = a['clauseText']
        documents['raw'] = {'data': [dict(f.entity, entId='GN001')]}
        a['supportingSourceBindings'][0]['contentSha256'] = content_hash(a['clauseText'])
        a['entitySha256'] = content_hash(f.entity)
        with self.assertRaisesRegex(ValueError, 'AC_EXPRESSION_UNBOUND'):
            compile_reviews(f.review, f.specs, f.sources, documents)

    def test_optional_ac_source_point_survives_actual_manifest_and_qualification_replay(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            f, a, documents = self.ac_fixture()
            payloads = {'raw.json': dict(documents['raw'], status={'code': 200}),
                        'moi.json': documents['independent-moi'], 'review.json': f.review}
            entries = []
            for name, source_id, kind in [('raw.json', 'raw', 'CSPEC_RAW'), ('moi.json', 'independent-moi', 'CLINGEN_JSON'),
                                          ('review.json', 'review', 'EXPERT_CLAUSE_REVIEW')]:
                knowledge.write(directory / name, payloads[name])
                entries.append({'sourceId': source_id, 'kind': kind, 'path': name,
                    'url': 'https://example.org/' + source_id, 'version': 'SYNTHETIC',
                    'sha256': knowledge.sha((directory / name).read_bytes())})
            knowledge.write(directory / 'manifest.json', {'schemaVersion': 'bs1-knowledge-input-v1', 'version': 'SYNTHETIC', 'sources': entries})
            knowledge.compile_manifest(directory / 'manifest.json', directory / 'compiled')
            record, = knowledge.read_json((directory / 'compiled/bundle.json').read_bytes())['records']
            ac = record['expertPolicy']['statistic']['eligibleAc']
            self.assertEqual(a['populationStatistic']['eligibleAc'], ac)
            original_documents = {'raw': payloads['raw.json'], 'independent-moi': payloads['moi.json']}
            locations = {(item['sourceId'], item['locator']) for item in record['sources']}
            locations.update([(a['sexChromosomeScope']['sourceId'], a['sexChromosomeScope']['sourceLocator']),
                              (a['populationStatistic']['sourceId'], a['populationStatistic']['locator']),
                              (ac['sourceId'], ac['locator'])])
            bindings = [{'sourceId': source_id, 'locator': locator,
                'contentSha256': content_hash(qualification.resolve(original_documents[source_id], locator))}
                for source_id, locator in sorted(locations)]
            checks = {category: {'status': 'PUBLISHED_THRESHOLD_NOT_RECALCULATED' if category == 'PARAMETER_SCOPE' else 'SATISFIED',
                'basis': 'SYNTHETIC explicit graph replay, not a scientific approval.', 'sourceIds': ['raw', 'independent-moi']}
                for category in qualification.CATEGORIES}
            q = {'schemaVersion': 'bs1-qualification-input-v1', 'reviewer': 'SYNTHETIC', 'reviewedAt': '2026-11-04',
                'basis': 'SYNTHETIC only', 'entries': [{'record': record,
                    'payloadSha256': content_hash(qualification.payload(record)), 'sourceBindings': bindings, 'checks': checks}]}
            knowledge.write(directory / 'qualification.json', q)
            entries.append({'sourceId': 'qualification', 'kind': 'QUALIFICATION_REVIEW', 'path': 'qualification.json',
                'url': 'https://example.org/qualification', 'version': 'SYNTHETIC',
                'sha256': knowledge.sha((directory / 'qualification.json').read_bytes())})
            knowledge.write(directory / 'qualified-manifest.json', {'schemaVersion': 'bs1-knowledge-input-v1', 'version': 'SYNTHETIC', 'sources': entries})
            knowledge.compile_manifest(directory / 'qualified-manifest.json', directory / 'qualified')
            qualified, = knowledge.read_json((directory / 'qualified/bundle.json').read_bytes())['records']
            self.assertEqual(ac, qualified['expertPolicy']['statistic']['eligibleAc'])
            self.assertIn((ac['sourceId'], ac['locator']), {(item['sourceId'], item['locator'])
                for item in qualified['qualification']['sourceBindings']})

    def real_count_source(self, environment, expected_sha, locator, expression, operator, number):
        raw = Path(os.environ[environment]).read_bytes()
        self.assertEqual(expected_sha, hashlib.sha256(raw).hexdigest())
        documents = {'raw': json.loads(raw)}
        text = qualification.resolve(documents['raw'], locator)
        self.assertIsInstance(text, str)
        requirement = {'operator': operator, 'decimal': number, 'unit': 'alleles',
                       'sourceTerm': 'AC', 'sourceId': 'raw', 'locator': locator}
        approved = {'eligibleAcExpression': expression,
            'additionalConditionsReview': 'AC subcondition only: exact variant-alleles wording; no confidence, MOI or full-rule adoption.',
            'supportingSourceBindings': [{'sourceId': 'raw', 'locator': locator,
                'contentSha256': content_hash(text),
                'interpretationBasis': 'Direct real-source unit check only; observations are not assumed to mean target-ALT alleles.'}]}
        sources = [{'sourceId': 'raw', 'url': 'https://cspec.genome.network/cspec/SequenceVariantInterpretation/id',
                    'version': 'existing-capture', 'sha256': expected_sha}]
        return approved, requirement, sources, documents, text

    @unittest.skipUnless(os.environ.get('VARVIS_T03_GN071_SOURCE'), 'requires explicit pinned GN071 raw source')
    def test_real_gn071_variant_allele_count_subcondition_keeps_9999_ci_uninterpreted(self):
        inputs = self.real_count_source('VARVIS_T03_GN071_SOURCE',
            '6d96d55e8561d918fc6210301c9e3e66f1d8f04190fe24f12d7010eff01aeb7f',
            '/data/1/ld/CriteriaCode/27/entContent/instructionsToUse',
            'a minimum of five variant alleles present', 'GTE', '5')
        approved, requirement, sources, documents, text = inputs
        self.assertEqual('99.99% CI; subpopulation must have a minimum of five variant alleles present. '
                         'Males and females are included for this code.', text)
        before = copy.deepcopy(inputs)
        self.assertIsNone(sex._eligible_ac(approved, requirement, sources, documents))
        self.assertEqual(before, inputs)
        self.assertNotIn('confidence', approved)
        self.assertNotIn('populationStatistic', approved)

    @unittest.skipUnless(os.environ.get('VARVIS_T03_GN080_SOURCE'), 'requires explicit pinned GN080 raw source')
    def test_real_gn080_variant_allele_count_subcondition_does_not_compile_whole_rule(self):
        inputs = self.real_count_source('VARVIS_T03_GN080_SOURCE',
            'b978801a9c8180aac7007715fc82cc6e57009a398ec03486a0e8630e7321cd4b',
            '/data/1/ld/CriteriaCode/26/entContent/instructionsToUse',
            'minimum of five variant alleles present', 'GTE', '5')
        approved, requirement, sources, documents, text = inputs
        self.assertEqual('99.99% CI; subpopulation must have a minimum of five variant alleles present. '
                         'Males and females are included for this code.', text)
        before = copy.deepcopy(inputs)
        self.assertIsNone(sex._eligible_ac(approved, requirement, sources, documents))
        self.assertEqual(before, inputs)
        self.assertNotIn('confidence', approved)
        self.assertNotIn('populationStatistic', approved)

    @unittest.skipUnless(os.environ.get('VARVIS_T03_GN149_SOURCE'), 'requires explicit pinned GN149 raw source')
    def test_real_gn149_observation_unit_is_not_implicitly_mapped_to_ac(self):
        approved, requirement, sources, documents, text = self.real_count_source('VARVIS_T03_GN149_SOURCE',
            '95ddcdae2db6ff9b01ad99e7669809a28daa400d7c9024668ccb9babbb12f981',
            '/data/1/ld/CriteriaCode/19/entContent/strengthDescriptor/2/text',
            '>1 observation', 'GT', '1')
        self.assertTrue(text.endswith('at least 2000 alleles and >1 observation.'))
        self.assertIn('either exomes or genomes', text)
        with self.assertRaisesRegex(ValueError, '^EXPERT_SEX_AC_EXPRESSION_UNSUPPORTED$'):
            sex._eligible_ac(approved, requirement, sources, documents)

    @unittest.skipUnless(os.environ.get('VARVIS_T03_GN106_SOURCE'), 'requires explicit pinned GN106 raw source')
    def test_real_gn106_numeric_binding_stops_at_unresolved_generic_x_scope(self):
        raw = Path(os.environ['VARVIS_T03_GN106_SOURCE']).read_bytes()
        self.assertEqual('570b3f71debab0b7bc971983dbb115d3e5fd11358ed83c1dc94a48982c89ebf9', hashlib.sha256(raw).hexdigest())
        original = json.loads(raw)
        entity = original['data'][0]
        f, a, _ = self.fixture(point_af=True)
        clause = next(item['entContent'] for item in entity['ld']['CriteriaCode'] if item['entContent']['label'] == 'BS1')
        text = next(item['text'] for item in clause['strengthDescriptor'] if item['strength'] == 'Strong')
        a.update(specificationId='GN106', version='1.0', entitySha256=content_hash(entity),
            instruction=clause.get('instructionsToUse'), clauseText=text,
            thresholdExpression='≥8.3x10<sup>-5 </sup>', fraction='0.000083',
            sourceMetricExpression='allele frequency', reviewedModeMappings=[])
        a['sexChromosomeScope'].update(sourceLocator='GN106/ld/RuleSet')
        a['populationStatistic'].update(locator='GN106/ld/CriteriaCode/20/entContent/strengthDescriptor/2/text')
        f.specs = [{'specificationId': 'GN106', 'version': '1.0', 'sourceId': 'raw', 'original': entity}]
        f.sources = [{'sourceId': 'raw', 'url': 'https://cspec.genome.network/cspec/SequenceVariantInterpretation/id',
                     'version': 'capture', 'sha256': hashlib.sha256(raw).hexdigest()}]
        prepared = sex.prepare_reviews(f.review, f.specs, f.sources, {'raw': original})
        self.assertEqual(('UNRESOLVED', 'EXPERT_SEX_MOI_MAPPING_REQUIRED', []),
                         (prepared['status'], prepared['reasonCode'], prepared['records']))
        self.assertFalse(prepared['scientificallyAdopted'])
        self.assertIn('males', a['clauseText'])
        self.assertIn('most frequent pathogenic allele', a['clauseText'])


if __name__ == '__main__':
    unittest.main()
