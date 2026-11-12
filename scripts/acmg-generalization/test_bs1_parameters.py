import copy
from pathlib import Path
import tempfile
import unittest
import bs1_parameters as p
import bs1_knowledge as k


SOURCE = {'sourceId': 'raw', 'url': 'https://example.org/synthetic', 'version': 'SYNTHETIC', 'sha256': 'a'*64}


def epi(**changes):
    return dict({'sourceId': 'raw', 'sourceRecordId': '1', 'sourceCitation': 'SYNTHETIC',
                 'orphaId': 'ORPHA:1', 'type': 'Point prevalence', 'qualification': 'Value and class',
                 'mean': '0.037', 'class': '<1 / 1 000 000', 'geography': 'Worldwide', 'validation': 'Validated'}, **changes)


def background(**changes):
    return dict({'hgncId': 'HGNC:1', 'mondoId': 'MONDO:0000001', 'moi': 'AUTOSOMAL_DOMINANT',
                 'assertions': [], 'reasonCode': 'BS1_DISEASE_PARAMETERS_MISSING'}, **changes)


def mapping(**changes):
    return dict({'mondoId': 'MONDO:0000001', 'orphaId': 'ORPHA:1', 'sourceId': 'raw', 'sourceRow': 2}, **changes)


def spec(text='prevalence = 1 in 1000; penetrance = 0.8; genetic heterogeneity = 1; allelic heterogeneity = 0.2'):
    return {'sourceId': 'raw', 'specificationId': 'GN-SYNTHETIC', 'version': '1', 'original': {'ld': {
        'Gene': [{'entContent': {'HGNC': {'symbol': 'X', 'hgnc_id': 'HGNC:1'}}}],
        'RuleSet': [{'entContent': {'genes': [{'gene': 'X', 'diseases': [{'preferredMondoId': 'MONDO:0000001',
                     'preferredModeOfInheritance': 'Autosomal dominant inheritance'}]}]}}],
        'CriteriaCode': [{'entContent': {'label': 'BS1', 'gene': ['X'], 'instructionsToUse': text}}]}}}


def inventory(rows=None, mappings=None, specs=None, records=None, backgrounds=None):
    return p.assess([background()] if backgrounds is None else backgrounds,
                    [epi()] if rows is None else rows, [mapping()] if mappings is None else mappings,
                    specs or [], records or [], [SOURCE])


class ParameterTest(unittest.TestCase):
    def test_unit_conversion_retains_source_and_does_not_adopt(self):
        d, obs, report = inventory()
        self.assertEqual('0.00000037', obs[0]['value'])
        self.assertEqual('per_100000', obs[0]['rawUnit'])
        self.assertEqual(SOURCE, obs[0]['source'])
        self.assertEqual('ORPHA:1/Prevalence/1', obs[0]['locator'])
        self.assertEqual('CANDIDATE_AVAILABLE', d[0]['parameters']['prevalence']['status'])
        self.assertEqual(list(p.FIELDS[1:]), d[0]['missingParameters'])
        self.assertIsNone(d[0]['parameters']['prevalence']['selectedValue'])
        self.assertFalse(d[0]['modelExecutable']); self.assertEqual(0, report['newModelAdoptions'])

    def test_class_only_zero_is_not_a_point_estimate(self):
        d, obs, _ = inventory([epi(qualification='Class only', mean='0.0')])
        self.assertNotIn('value', obs[0])
        self.assertEqual('RANGE', obs[0]['valueKind'])
        self.assertEqual('0.000001', obs[0]['reportedRange']['upper'])
        self.assertFalse(obs[0]['reportedRange']['upperInclusive'])
        self.assertEqual('SOFTWARE_UNSUPPORTED', d[0]['parameters']['prevalence']['status'])
        self.assertNotIn('qualifiedPrevalence', d[0]['missingParameters'])

    def test_all_classes_preserve_bounds_instead_of_midpoints(self):
        for label, lower, upper in [('1-9 / 100 000', '0.00001', '0.00009'),
                                    ('1-9 / 1 000 000', '0.000001', '0.000009'),
                                    ('1-5 / 10 000', '0.0001', '0.0005'),
                                    ('6-9 / 10 000', '0.0006', '0.0009'), ('>1 / 1000', '0.001', None)]:
            with self.subTest(label=label):
                fact=p.epidemiology_fact(epi(qualification='Class only', mean='0.0', **{'class':label}),SOURCE)
                self.assertEqual(lower, fact['reportedRange']['lower']); self.assertEqual(upper,fact['reportedRange']['upper'])
                self.assertNotIn('value',fact)

    def test_unknown_class_is_not_zero_or_software_interval(self):
        d, obs, _ = inventory([epi(qualification='Class only', mean='0.0', **{'class':'Unknown'})])
        self.assertEqual('SOURCE_GAP',d[0]['parameters']['prevalence']['status'])
        self.assertNotIn('value',obs[0])

    def test_birth_lifetime_incidence_and_cases_are_not_point_prevalence(self):
        for kind, expected in [('Prevalence at birth','SOFTWARE_UNSUPPORTED'),('Lifetime Prevalence','SOFTWARE_UNSUPPORTED'),
                               ('Annual incidence','SOURCE_GAP'),('Cases/families','SOURCE_GAP')]:
            with self.subTest(kind=kind):
                d,obs,_=inventory([epi(type=kind)])
                self.assertEqual(expected,d[0]['parameters']['prevalence']['status'])
                self.assertFalse(obs[0]['technicalCandidate'])

    def test_invalid_and_unvalidated_estimates_are_not_available(self):
        for value in ['0.0','-1','NaN','100001',None]:
            with self.subTest(value=value):
                d,_,_=inventory([epi(mean=value)])
                self.assertEqual('INVALID_SOURCE_DATA',d[0]['parameters']['prevalence']['status'])
        d,_,_=inventory([epi(validation='Not yet validated')])
        self.assertEqual('REVIEW_REQUIRED',d[0]['parameters']['prevalence']['status'])
        self.assertIn('SOURCE_NOT_VALIDATED',d[0]['parameters']['prevalence']['reasonCodes'])

    def test_missing_citation_and_unknown_qualification_are_explicit(self):
        d,_,_=inventory([epi(sourceCitation=None)])
        self.assertIn('SOURCE_CITATION_MISSING',d[0]['parameters']['prevalence']['reasonCodes'])
        d,_,_=inventory([epi(qualification='Future unsupported qualifier')])
        self.assertEqual('SOFTWARE_UNSUPPORTED',d[0]['parameters']['prevalence']['status'])

    def test_estimates_in_same_population_require_reconciliation(self):
        d,_,_=inventory([epi(mean='2',**{'class':'1-9 / 100 000'}),epi(sourceRecordId='2',mean='4',**{'class':'1-9 / 100 000'})])
        self.assertEqual('CONFLICT',d[0]['parameters']['prevalence']['status'])
        self.assertIsNone(d[0]['parameters']['prevalence']['selectedValue'])

    def test_different_populations_not_averaged_or_reported_as_same_population_conflict(self):
        d,_,_=inventory([epi(mean='2',geography='Europe',**{'class':'1-9 / 100 000'}),epi(sourceRecordId='2',mean='4',geography='Japan',**{'class':'1-9 / 100 000'})])
        self.assertEqual('REVIEW_REQUIRED',d[0]['parameters']['prevalence']['status'])
        self.assertIn('POPULATION_OR_STATISTIC_SELECTION_REQUIRED',d[0]['parameters']['prevalence']['reasonCodes'])

    def test_duplicate_observation_and_mapping_do_not_inflate_counts(self):
        d,obs,_=inventory([epi(),epi()],mappings=[mapping(),mapping()])
        self.assertEqual(1,len(obs));self.assertEqual(1,d[0]['parameters']['prevalence']['technicalCandidateCount'])

    def test_one_to_many_and_unverified_mapping_do_not_select(self):
        d,_,_=inventory(mappings=[mapping(),mapping(orphaId='ORPHA:2')])
        self.assertEqual('REVIEW_REQUIRED',d[0]['parameters']['prevalence']['status'])
        self.assertIn('DISEASE_MAPPING_AMBIGUOUS',d[0]['blockingReasons'])
        b=background(assertions=[{'original':{'disease_original_curie':'ORPHA:1'}}])
        d,_,_=inventory(mappings=[],backgrounds=[b])
        self.assertIn('SUBMITTED_DISEASE_IDENTIFIER_NOT_VERIFIED',d[0]['blockingReasons'])
        self.assertEqual('REVIEW_REQUIRED',d[0]['parameters']['prevalence']['status'])

    def test_mapping_gap_is_not_reported_as_missing_disease_fact(self):
        d,_,_=inventory(mappings=[])
        self.assertEqual('IDENTITY_UNRESOLVED',d[0]['parameters']['prevalence']['status'])
        self.assertNotIn('qualifiedPrevalence',d[0]['missingParameters'])
        self.assertIn('qualifiedPrevalence',d[0]['blockedParameters'])

    def test_different_orpha_scopes_are_not_compared_as_same_disease(self):
        d,_,_=inventory([epi(),epi(orphaId='ORPHA:2',mean='0.05')],mappings=[mapping(),mapping(orphaId='ORPHA:2')])
        self.assertEqual('REVIEW_REQUIRED',d[0]['parameters']['prevalence']['status'])
        self.assertNotIn('MULTIPLE_ESTIMATES_REQUIRE_RECONCILIATION',d[0]['parameters']['prevalence']['reasonCodes'])

    def test_value_class_disagreement_is_retained_for_review(self):
        d,obs,_=inventory([epi(mean='10')])
        self.assertEqual('0.0001',obs[0]['value'])
        self.assertFalse(obs[0]['technicalCandidate'])
        self.assertIn('VALUE_CLASS_DISAGREEMENT_REQUIRES_REVIEW',d[0]['parameters']['prevalence']['reasonCodes'])

    def test_text_mentions_found_for_all_fields_without_extracting_numbers(self):
        d,obs,_=inventory(rows=[],specs=[spec()])
        self.assertEqual([],d[0]['missingParameters'])
        self.assertEqual(set(p.FIELDS),{f['field'] for f in obs})
        self.assertTrue(all(f['valueKind']=='UNSTRUCTURED' and 'value' not in f for f in obs))
        self.assertTrue(all(d[0]['parameters'][f]['status']=='REVIEW_REQUIRED' for f in p.FIELDS))

    def test_text_scope_cannot_leak_across_gene_disease_mode_or_criterion(self):
        for changes in [{'hgncId':'HGNC:2'},{'mondoId':'MONDO:0000002'},{'moi':'AUTOSOMAL_RECESSIVE'}]:
            with self.subTest(changes=changes):
                d,_,_=inventory(rows=[],specs=[spec()],backgrounds=[background(**changes)])
                self.assertTrue(all(not d[0]['parameters'][f]['observationIds'] for f in p.FIELDS))
        s=spec();s['original']['ld']['CriteriaCode'][0]['entContent']['label']='PM2'
        d,_,_=inventory(rows=[],specs=[s]);self.assertEqual(4,len(d[0]['missingParameters']))

    def test_explicit_clause_disease_and_unknown_mode_are_preserved(self):
        s=spec();s['original']['ld']['CriteriaCode'][0]['entContent']['disease']=['MONDO:0000002']
        d,_,_=inventory(rows=[],specs=[s]);self.assertEqual(4,len(d[0]['missingParameters']))
        s=spec();s['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance']='Unknown'
        d,_,_=inventory(rows=[],specs=[s]);self.assertIn('SPECIFICATION_MOI_SCOPE_UNRESOLVED',d[0]['blockingReasons'])

    def test_structured_parameters_never_gain_adoption_from_a_label(self):
        model={f:{'value':'0.01','unit':'fraction','sourceId':'raw','locator':'synthetic/'+f} for f in p.FIELDS}
        model['prevalence']['statistic']='POINT_PREVALENCE';model['population']='Worldwide'
        r={'recordId':'synthetic','kind':'MAX_CREDIBLE_AF','scope':{'hgncId':'HGNC:1','mondoId':'MONDO:0000001','modes':['AUTOSOMAL_DOMINANT']},
           'model':model,'review':{'status':'REVIEWED_FOR_LOCAL_USE','contentSha256':'forged'}}
        d,_,report=inventory(rows=[],mappings=[],records=[r])
        self.assertTrue(d[0]['completeNumericCandidateSet']);self.assertFalse(d[0]['modelExecutable'])
        self.assertEqual(0,report['newModelAdoptions'])
        r['model']['penetrance']['sourceId']='unbound'
        d,_,_=inventory(rows=[],mappings=[],records=[r])
        self.assertFalse(d[0]['completeNumericCandidateSet'])
        self.assertIn('PARAMETER_SOURCE_UNBOUND',d[0]['parameters']['penetrance']['reasonCodes'])

    def test_input_is_unchanged_and_output_deterministic(self):
        rows=[epi()];before=copy.deepcopy(rows)
        self.assertEqual(inventory(rows),inventory(rows));self.assertEqual(before,rows)

    def test_compiler_outputs_field_inventory_and_keeps_runtime_bundle_identical(self):
        from test_bs1_knowledge import KnowledgeTest
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);manifest=KnowledgeTest().setup_manifest(folder)
            original=k.read_json(manifest.read_bytes())
            k.compile_manifest(manifest,folder/'out')
            b=k.read_json((folder/'out/bundle.json').read_bytes())
            self.assertEqual([],b['records']);self.assertNotIn('parameters',b['backgrounds'][0])
            report=k.read_json((folder/'out/parameter-coverage.json').read_bytes())
            drafts=k.read_json((folder/'out/parameter-drafts.json').read_bytes())
            self.assertEqual(3,report['backgrounds'])
            self.assertTrue(all(sum(report['fieldStatusCounts'][f].values())==3 for f in p.FIELDS))
            self.assertEqual(original,k.read_json(manifest.read_bytes()))
            self.assertTrue(all(not d['modelExecutable'] for d in drafts))


if __name__=='__main__': unittest.main()
