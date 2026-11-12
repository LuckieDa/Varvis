from copy import deepcopy
from pathlib import Path
import unittest
from xml.sax.saxutils import escape
from zipfile import ZipFile

from pp1_hht_phenotype import METHOD, compile_attachment, source_facts
from pp1_source_qualification import word
from test_pp1_meiosis import add, context, evaluate, pedigree


def hht_policy():
    policy = context()
    policy['policy']['affectedDefinition'] = METHOD
    policy['policy']['phenotypeQualification'] = {'method':METHOD, 'minimumManifestations':3,
                                                  'familyHistoryMaximum':1, 'unaffectedExcluded':True}
    for code, minimum, maximum in (('SUPPORTING',3,3), ('MODERATE',4,4), ('STRONG',5,None)):
        policy['policy']['thresholds'][code].update(minimum=minimum, maximum=maximum)
    policy['ruleAttachments'] = [{'fileSha256':'a' * 64, 'status':'VERIFIED_CONDITIONS_IMPLEMENTED'}]
    return policy


def clinical_pedigree(n):
    q = pedigree(n)
    for member in ('P', *(f'C{i}' for i in range(n))):
        add(q, member, 'hhtManifestation', 'EPISTAXIS')
        add(q, member, 'hhtManifestation', 'TELANGIECTASES')
    add(q, 'P', 'hhtManifestation', 'VISCERAL_LESIONS')
    return q


class HhtPhenotype(unittest.TestCase):
    def test_qualification_uses_distinct_categories_and_one_anchored_family_history(self):
        for count, status, strength in ((2, 'NOT_MET', None), (3, 'MET', 'SUPPORTING'),
                                        (4, 'MET', 'MODERATE'), (5, 'MET', 'STRONG')):
            with self.subTest(count=count):
                q = clinical_pedigree(count)
                add(q, 'C0', 'hhtManifestation', 'EPISTAXIS')
                result = evaluate(q, hht_policy())
                self.assertEqual((count, status, strength), (result['meiosisAssessment']['informativeMeioses'],
                                                             result['status'], result['strength']))
                row, = [a for a in result['clinicalAssessments'] if a['memberLabel'] == 'C0']
                self.assertEqual(['EPISTAXIS', 'TELANGIECTASES', 'FAMILY_HISTORY'], row['verifiedManifestations'])
                self.assertEqual('a' * 64, row['ruleAttachmentSha256'])

    def test_affected_label_and_two_features_cannot_circularly_create_affected_relatives(self):
        q = clinical_pedigree(3)
        q['observations'] = [r for r in q['observations'] if r['value'] != 'VISCERAL_LESIONS']
        scored = evaluate(q, hht_policy())
        self.assertEqual(('NOT_EVALUATED',0), (scored['status'],scored['awardedPP1Points']))
        self.assertTrue(all(a['status'] == 'UNRESOLVED' for a in scored['clinicalAssessments']))
        add(q, 'P', 'hhtCuracaoDefinite', True)
        scored = evaluate(q, hht_policy())
        self.assertEqual(0, scored['meiosisAssessment']['informativeMeioses'])
        self.assertIn('PP1_HHT_CURACAO_PHENOTYPE_INCOMPLETE', [n['reasonCode'] for n in scored['needs']])

    def test_unaffected_never_counts_and_contradiction_blocks_pedigree(self):
        q = clinical_pedigree(4)
        other = deepcopy(q)
        other['observations'] = [r for r in other['observations']
                                 if not (r['memberKey'] == 'C0' and r['kind'] == 'hhtManifestation')]
        add(other, 'C0', 'clinical', 'UNAFFECTED')
        add(other, 'C0', 'age', 85)
        scored = evaluate(other, hht_policy())
        self.assertEqual(('SUPPORTING', 3), (scored['strength'],scored['meiosisAssessment']['informativeMeioses']))
        add(q, 'C0', 'hhtManifestationAbsent', 'EPISTAXIS')
        scored = evaluate(q, hht_policy())
        self.assertEqual(('NOT_EVALUATED',0), (scored['status'],scored['awardedPP1Points']))
        self.assertIn('PP1_HHT_CURACAO_PHENOTYPE_CONFLICT', [n['reasonCode'] for n in scored['needs']])

    def test_source_claim_requires_a_complete_assertion_and_distinguishes_possible_hht(self):
        sentences = [
            ('II:2 had recurrent spontaneous epistaxis.', ('hhtManifestation','EPISTAXIS')),
            ('II:2 had multiple telangiectases on the lips.', ('hhtManifestation','TELANGIECTASES')),
            ('II:2 had a pulmonary arteriovenous malformation.', ('hhtManifestation','VISCERAL_LESIONS')),
            ('II:2 fulfilled the Curaçao criteria for definite HHT.', ('hhtCuracaoDefinite',True)),
            ('II:2 had no recurrent spontaneous epistaxis.', ('hhtManifestationAbsent','EPISTAXIS')),
            ('II:2 had epistaxis.', None), ('II:2 was diagnosed with HHT based on genetic testing.', None),
            ('II:2 had recurrent spontaneous epistaxis?', None),
            ('II:2 had recurrent spontaneous epistaxis according to an unverified report.', None),
        ]
        for text, outcome in sentences:
            with self.subTest(text=text):
                self.assertEqual([outcome] if outcome else [], list(source_facts(word('II:2'), text, text)))
                self.assertEqual([], list(source_facts(word('II:3'), text, text)))

    def test_official_attachment_section_and_mutations(self):
        path = Path('local/development/handoffs/pp1-t08-meiosis-20261108/source-audit/HHT-phenotype_v1.1.docx')
        if not path.is_file():
            self.skipTest('pinned release attachment fixture not present')
        with ZipFile(path) as archive:
            xml = archive.read('word/document.xml')
        self.assertEqual(hht_policy()['policy']['phenotypeQualification'], compile_attachment(xml))
        # Extra conditions in the normative section must fail closed.
        extra = '<w:p><w:r><w:t>' + escape('Unexpected clinical exclusion.') + '</w:t></w:r></w:p>'
        self.assertIsNone(compile_attachment(xml.replace(b'</w:body>', extra.encode() + b'</w:body>')))
