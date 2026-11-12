"""Display safety checks; no biological scoring, network or model execution."""
import json
from pathlib import Path
import tempfile
import unittest

import export_literature_readable as view


class ReadableLiteratureTest(unittest.TestCase):
    def setUp(self):
        self.temp_root = Path(__file__).resolve().parents[2] / 'local/temp/literature-readable-tests'
        self.temp_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=self.temp_root)
        self.root = Path(self.temp.name).resolve()
        self.assertTrue(self.root.is_relative_to(self.temp_root.resolve()))

    def tearDown(self):
        self.temp.cleanup()

    def save(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(view.canonical(value))
        return path

    def fixture(self):
        ref = {'unitId': '/article[1]/body[1]/p[1]', 'quote': 'A female subject.',
               'sourceSha256': 'a' * 64, 'bundleSha256': 'b' * 64, 'start': 0, 'end': 17}
        target = {'geneSymbol': 'TESTG', 'variantAliases': ['c.1A>G']}
        fact = {'raw': {'fieldId': 'F05.5', 'memberLabel': 'P1', 'familyLabel': 'Family 1',
                        'sourceValue': 'female'}, 'claimId': 'c1', 'familyKey': 'family1',
                'memberKey': 'member1', 'sourceRefs': [ref], 'sourceCheck': 'EXACT_SOURCE_MATCH',
                'verification': 'UNVERIFIED', 'sourceAudit': {'verdict': 'SUPPORTED'}}
        artifact = {'schemaVersion': 'pp1-literature-artifact-v1',
                    'binding': {'pmcid': 'PMC101', 'bundleSha256': 'b' * 64, 'target': target},
                    'checked': {'facts': [fact], 'scoringEligible': False}}
        artifact_path = self.save('original.json', artifact)
        copy = self.save('run/reviewed-cache/copy.json', artifact)
        manifest = {'acceptedArtifacts': [str(copy)]}
        self.save('run/artifacts.json', manifest)
        result = {'pmcid': 'PMC101', 'bundleSha256': 'b' * 64, 'target': target,
                  'artifactManifestSha256': view.sha(view.canonical(manifest)),
                  'reading': {'qualification': {'schemaVersion': 'pp1-source-qualification-v1',
                      'sourceIdentity': {'pmcid': 'PMC101', 'bundleSha256': 'b' * 64}, 'target': target,
                      'observations': [{'kind': 'sex', 'value': 'FEMALE', 'familyKey': 'family1',
                                        'memberKey': 'member1', 'claimIds': ['c1'], 'sourceRefs': [ref]}]}},
                  'evaluation': {'status': 'NOT_EVALUATED', 'needs': [
                      {'reasonCode': 'PP1_PUBLISHED_EXACT_CONTEXT_UNAVAILABLE'}]}}
        result_path = self.save('run/result.json', result)
        self.save('library/papers/PMC101/metadata.json', {'title': 'Synthetic display control'})
        return artifact, artifact_path, result, result_path

    def test_exact_display_join_does_not_upgrade_original_scientific_state(self):
        artifact, path, result, result_path = self.fixture()
        before = path.read_bytes()
        doc = view.pp1_document(path, result_path, self.root / 'library')
        self.assertEqual('程序已识别，未计分', doc['cases'][0]['rows'][0]['statusText'])
        self.assertIn('未评价', doc['summary']['headline'])
        self.assertEqual('UNVERIFIED', doc['cases'][0]['rows'][0]['audit']['originalVerification'])
        self.assertEqual(before, path.read_bytes())

    def test_wrong_target_or_changed_accepted_copy_cannot_borrow_runtime_progress(self):
        artifact, path, result, result_path = self.fixture()
        result['target']['geneSymbol'] = 'DIFFERENT'
        self.assertIsNone(view.bind_pp1_runtime(artifact, path, result, result_path))
        artifact, path, result, result_path = self.fixture()
        self.save('run/reviewed-cache/copy.json', {'changed': True})
        self.assertIsNone(view.bind_pp1_runtime(artifact, path, result, result_path))

    def test_same_member_but_wrong_claim_or_citation_is_not_a_match(self):
        artifact, path, result, result_path = self.fixture()
        q = result['reading']['qualification']
        q['observations'][0]['claimIds'] = ['different']
        self.assertEqual([], view.typed_for(artifact['checked']['facts'][0], q))
        q['observations'][0]['claimIds'] = ['c1']
        q['observations'][0]['sourceRefs'][0] = dict(q['observations'][0]['sourceRefs'][0], start=1)
        self.assertEqual([], view.typed_for(artifact['checked']['facts'][0], q))

    def test_printed_alias_target_and_qualified_target_have_distinct_roles(self):
        artifact, path, result, result_path = self.fixture()
        qualified = {'geneSymbol': 'TESTG', 'allele': {'position': 7}, 'diseaseId': 'MONDO:TEST'}
        result['reading']['qualification']['target'] = qualified
        result['evaluation']['target'] = qualified.copy()
        self.assertIsNotNone(view.bind_pp1_runtime(artifact, path, result, result_path))
        result['evaluation']['target']['diseaseId'] = 'MONDO:OTHER'
        self.assertIsNone(view.bind_pp1_runtime(artifact, path, result, result_path))

    def test_unknown_kinship_and_apparently_healthy_are_not_strengthened(self):
        self.assertIn('aunt', view.MEMBERS['aunt'])
        self.assertNotIn('外祖父', view.MEMBERS['grandfather'])
        self.assertIn('表面健康', view.display_value('apparently healthy'))
        self.assertNotEqual('未患病', view.display_value('apparently healthy'))
        self.assertIn('待核定', view.display_value('26-year-old'))

    def test_candidate_met_is_not_reported_as_adopted_and_unverified_synonym_survives(self):
        self.save('library/papers/PMC102/metadata.json', {'title': 'Synthetic display control'})
        ref = {'unitId': '/article[1]/body[1]/p[1]', 'quote': 'A homozygous subject.',
               'bundleSha256': 'b' * 64, 'sourceSha256': 'a' * 64, 'start': 0, 'end': 21}
        raw = [{'field': 'reportedZygosity', 'sourceValue': value, 'citation': ref}
               for value in ('homozygous', 'homozygote')]
        case = {'caseLabel': 'proband', 'facts': raw, 'fields': {'reportedZygosity': {
                    'status': 'MULTIPLE_CLAIMS_REQUIRE_RECONCILIATION'}},
                'semantics': {'verifiedLiteralFacts': [{'factIndex': 0, 'field': 'reportedZygosity',
                    'value': 'homozygous', 'reportedValue': 'homozygous', 'citation': ref}],
                    'unverifiedFactIndexes': [1]}}
        source = {'schemaVersion': 'pm3-discovery-process-v1', 'target': {'geneSymbol': 'TESTG'},
                  'papers': [{'pmcid': 'PMC102', 'observations': {'cases': [case]}}],
                  'pm3Assessment': {'status': 'MET', 'quarterPoints': 2,
                                    'pm3FinalBusinessExecution': False}}
        path = self.save('pm3.json', source)
        doc = view.pm3_documents(path, self.root / 'library')[0]
        self.assertIn('尚无正式', doc['summary']['headline'])
        self.assertEqual(2, len(doc['cases']))
        self.assertEqual('pending', doc['cases'][1]['rows'][0]['status'])
        self.assertNotIn('conflict', [r['status'] for c in doc['cases'] for r in c['rows']])
        self.assertEqual([1], doc['cases'][1]['rows'][0]['audit']['factIndexes'])
        self.assertFalse(any('反式' in g['title'] or '伙伴' in g['title'] for g in doc['gaps']))

    def test_html_source_cannot_escape_json_container_or_create_script(self):
        raw = '</script><script>alert(1)</script>\u2028'
        html = view.render_html({'value': raw}, '<script type="application/json">__LITERATURE_READABLE_DATA__</script>')
        self.assertEqual(1, html.count('</script>'))
        self.assertNotIn('<script>alert', html)
        payload = html.split('>', 1)[1].rsplit('</script>', 1)[0]
        self.assertEqual(raw, json.loads(payload)['value'])

    def test_csv_formula_and_existing_output_protection(self):
        self.assertEqual("'=SUM(1,2)", view.csv_cell('=SUM(1,2)'))
        self.assertEqual('c.1A>G', view.csv_cell('c.1A>G'))
        output = self.root / 'out'
        output.mkdir()
        (output / 'keep.txt').write_text('original', encoding='utf-8')
        with self.assertRaises(ValueError):
            view.export({'documents': []}, output)
        self.assertEqual('original', (output / 'keep.txt').read_text())


if __name__ == '__main__':
    unittest.main()
