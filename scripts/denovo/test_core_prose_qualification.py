"""Source-bound synthetic controls; neither model answers nor clinical approval."""
from pathlib import Path
import re
import tempfile
import unittest

from test_bundle_qualification import article, registered, candidate
from test_core_evaluation_intake import config_for
from bundle_model_contract import review
from qualify_bundle_candidates import qualify
from core_material_extraction import write_package
from core_material_review import review_package
from core_context_review import context_package, family_review
from core_material_contract import validate, digest as object_digest
from model_literature_contract import digest, parse
from prepare_literature import prepare


NORMAL = ('Case A is a 5-year-old boy born to nonconsanguineous parents with a normal '
          'perinatal and family history who presented with seizures.')
TESTING = 'Both parents tested negative for the variant.'
OLD_TESTING = 'Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.'


def prose(text, other=''):
    raw = article().decode()
    raw = re.sub(r'<body>.*</body>', '<body><p>' + text + '</p>' + other + '</body>', raw, flags=re.S)
    return raw.encode()


class ProseQualificationTests(unittest.TestCase):
    def family(self, text=NORMAL, other='', inheritance='AD'):
        raw = prose(text, other)
        return family_review(prepare(raw, 'PMC999999997', digest(raw)), 'A', [], inheritance)

    def test_numbered_normal_family_without_table(self):
        result = self.family()
        self.assertTrue(result['qualified'], result)
        self.assertEqual('EXPLICIT_NORMAL_FAMILY_HISTORY', result['qualificationBasis'])
        self.assertEqual(NORMAL, result['explicitHistoryProofs'][0]['citation']['quote'])

    def test_no_history_and_healthy_parents_are_supported_without_table(self):
        for sentence in ('There was no family history of similar disease.', 'Both parents were unaffected.'):
            with self.subTest(sentence=sentence):
                self.assertTrue(self.family('Case A was studied. ' + sentence)['qualified'])

    def test_missing_uncertain_other_or_perinatal_only_is_not_normal_history(self):
        for text in (NORMAL.replace('normal perinatal and family', 'normal perinatal'),
                     NORMAL.replace('normal perinatal and family', 'possibly normal family'),
                     NORMAL.replace('normal perinatal and family', 'not normal family'),
                     'Case A was studied. Both parents tested negative for the variant.',
                     'Case A was studied. The mother was unaffected.',
                     'Case A was studied. Case B had a normal family history.'):
            with self.subTest(text=text):
                self.assertFalse(self.family(text)['qualified'])

    def test_conflicts_and_unparsed_family_clauses_not_hidden_by_positive(self):
        for sentence in ('The father had similar symptoms.', 'An affected sibling was also examined.',
                         'The mother was mosaic.', 'The family history was not assessed.',
                         'The mother may have related disease.', 'The variant was inherited from the father.'):
            with self.subTest(sentence=sentence):
                self.assertFalse(self.family(NORMAL + ' ' + sentence)['qualified'])

    def test_other_numbered_case_does_not_poison_or_supply_history(self):
        other = '<p>Case B was studied. The mother had similar symptoms.</p>'
        self.assertTrue(self.family(other=other)['qualified'])
        self.assertFalse(self.family('Case A was studied.', '<p>Case B had a normal family history.</p>')['qualified'])

    def test_unassigned_cohort_conflict_still_blocks(self):
        self.assertFalse(self.family(other='<p>All parents had similar symptoms.</p>')['qualified'])

    def test_duplicate_case_labels_and_scope_limits_block(self):
        self.assertFalse(self.family(other='<p>' + NORMAL + '</p>')['qualified'])
        self.assertFalse(self.family(other='<p>Unassigned information.</p>' * 97)['qualified'])

    def test_unaffected_only_still_requires_ad(self):
        self.assertFalse(self.family('Case A was studied. Both parents were unaffected.', inheritance='AR')['qualified'])

    def package(self, sentence=TESTING, extra='', same_variant=True):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        raw = article().replace(OLD_TESTING.encode(),
            ('Genetic testing identified the heterozygous EXAMPLE variant c.123A&gt;G, p.T41A, in the proband. '
             + sentence + ' ' + extra).encode())
        raw = re.sub(rb'<fig>.*?</fig>', b'', raw, flags=re.S)
        if not same_variant:
            raw = raw.replace(b'Case A was studied.', b'Case A was studied. Another variant c.124A&gt;G, p.T42A, was found.')
        _, source = registered(root, raw)
        checked = review(source, candidate(source))
        checked['workUnit'] = {'targetUnitIds': [checked['cases'][0]['caseCitation']['unitId']]}
        native = qualify(source, [checked])
        write_package(source, native, [checked], root / 'raw', data_class='SYNTHETIC')
        review_package(source, root / 'raw', digest((root / 'raw/index.json').read_bytes()), root / 'review')
        config = parse((root / config_for(root)['path']).read_bytes())
        result = context_package(source, root / 'review', digest((root / 'review/index.json').read_bytes()),
                                 root / 'context', root, config, complete_events=True)
        row = result['cases'][0]; doc = parse((root / 'context' / row['path']).read_bytes())
        ledger = parse((root / 'context' / row['ledgerPath']).read_bytes())
        report = parse((root / 'context' / row['reportPath']).read_bytes())
        validate(doc, root / 'context', ledger, object_digest(ledger))
        self.assertEqual(0, result['modelCalls']); self.assertEqual(0, result['networkCalls'])
        return doc, report, row

    def test_explicit_target_testing_can_qualify_without_assay_name(self):
        doc, report, row = self.package()
        self.assertEqual('VERIFIED', doc['groups']['testing']['qualifiedForTarget']['verification'], report)
        self.assertTrue(doc['groups']['testing']['qualifiedForTarget']['value'])
        self.assertNotEqual('VERIFIED', doc['groups']['testing']['method']['verification'])
        self.assertEqual('READY_FOR_EVALUATION', row['readiness'])

    def test_no_test_inference_from_denovo_or_absence_claim(self):
        for sentence in ('The variant was de novo.', 'The variant was de novo, as neither parent carried the variant (PM6).',
                         'The mother tested negative for the variant.', 'Both parents may have tested negative for the variant.',
                         'Both parents tested negative for a different variant.'):
            with self.subTest(sentence=sentence):
                doc, _, _ = self.package(sentence)
                self.assertNotEqual('VERIFIED', doc['groups']['testing']['qualifiedForTarget']['verification'])

    def test_target_conflict_limitation_and_other_case_block(self):
        for extra in ('The father was not tested.', 'The mother carried the variant.', 'Maternal mosaicism was detected.',
                      'Parental testing was inconclusive.', 'The assay had insufficient coverage.', 'Case B was studied.'):
            with self.subTest(extra=extra):
                doc, _, _ = self.package(extra=extra)
                self.assertNotEqual('VERIFIED', doc['groups']['testing']['qualifiedForTarget']['verification'])
        doc, _, _ = self.package(same_variant=False)
        self.assertNotEqual('VERIFIED', doc['groups']['testing']['qualifiedForTarget']['verification'])


if __name__ == '__main__':
    unittest.main()
