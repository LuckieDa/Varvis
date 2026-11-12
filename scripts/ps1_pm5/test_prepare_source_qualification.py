import copy
import unittest

import prepare_source_qualification as compiler


class QualificationTest(unittest.TestCase):
    def setUp(self):
        self.allele = dict(assembly='GRCh38', contig='1', position=42, reference='A', alternate='C')
        assertion = dict(scv='SCV0001.1', classification='Pathogenic', reviewStatus='reviewed by expert panel',
                         recordStatus='current', comments=[dict(xpath='/Comment[1]', text='Final criteria: PS3, PM3.')])
        self.row = dict(allele=self.allele, variationId='1', originalAssertion=assertion, referenceId='ref-1',
                        sourceIds=['SOURCE'], disease=dict(id='MONDO:0000001'))
        self.packet = dict(reviewStatus='UNREVIEWED', scientificAdoption=False, references=[self.row])
        self.draft = dict(draftId='draft-1', references=[dict(referenceDraftId='r1', allele=self.allele, variationId='1',
            assertions=[dict(assertionDraftId='a1', sourceAssertion=assertion)])])
        self.profile = dict(mode=compiler.MODE, rnaMode=compiler.RNA_MODE, sourceIds=['SOURCE'], dataClass='SYNTHETIC', purpose='TEST_ONLY',
            background=dict(hgncId='HGNC:1', geneSymbol='TEST', mondoId='MONDO:0000001', moi='AUTOSOMAL_RECESSIVE'))
        self.pins = {k: dict(rawSha256='a'*64, canonicalSha256='b'*64) for k in ('referenceDraft', 'rnaDraft', 'draftReport')}

    def compile(self):
        return compiler.compile_review(self.draft, dict(pairs=[]), {}, self.packet, self.profile, self.pins,
            [dict(allele=self.allele, sourceIds=['SOURCE'], assertions=[self.row['originalAssertion']])])

    def test_derives_source_fields_without_human_independence_or_pair_approval(self):
        before = copy.deepcopy(self.packet)
        review, report = self.compile()
        self.assertEqual(self.packet, before)
        selected = review['references'][0]['review']
        self.assertEqual(selected['classification'], 'PATHOGENIC')
        self.assertNotIn('independence', selected)
        self.assertNotIn('qualification', selected)
        self.assertEqual(review['rnaComparisons'], [])
        self.assertFalse(report['scientificAdoption'])
        self.assertFalse(report['finalApprovalCreated'])

    def test_new_target_draft_uses_same_source_facts(self):
        first, _ = self.compile()
        self.draft['draftId'] = 'other-target-draft'
        self.draft['references'][0]['referenceDraftId'] = 'new-ref-id'
        second, _ = self.compile()
        self.assertEqual(first['references'][0]['review'], second['references'][0]['review'])
        self.assertNotEqual(first['draftBinding'], second['draftBinding'])

    def test_all_nonexpert_or_unbound_assertions_remain_unselected(self):
        for fault in ('VUS', 'SCV', 'GENE_DISEASE', 'EXPERT', 'RETRACTED', 'DUPLICATE', 'OPT_IN', 'PACKET'):
            with self.subTest(fault=fault):
                self.setUp()
                if fault == 'VUS': self.row['originalAssertion']['classification'] = 'Uncertain significance'
                if fault == 'SCV': self.row['originalAssertion'] = dict(self.row['originalAssertion'], scv='SCV0001.2')
                if fault == 'GENE_DISEASE': self.row['disease']['id'] = 'MONDO:0000002'
                if fault == 'EXPERT': self.row['originalAssertion']['reviewStatus'] = 'criteria provided, single submitter'
                if fault == 'RETRACTED': self.row['originalAssertion']['recordStatus'] = 'replaced'
                if fault == 'DUPLICATE': self.packet['references'].append(copy.deepcopy(self.row))
                if fault == 'OPT_IN': self.profile['mode'] = 'ASSUME'
                if fault == 'PACKET': self.packet['scientificAdoption'] = True
                with self.assertRaises(ValueError): self.compile()

    def test_rna_mentions_are_conservative_flags_not_normal_claims(self):
        for text in ('RNA assay', 'abnormal splicing', 'no effect on splicing', 'exon skipping', 'intron retention'):
            with self.subTest(text=text):
                self.row['originalAssertion']['comments'][0]['text'] = text
                review, report = self.compile()
                self.assertEqual(len(review['rnaReview']['knownConcerns']), 1)
                self.assertEqual(report['sourceScreen'][0]['matches'][0]['text'], text)
                self.assertFalse(review['rnaReview']['normalRnaClaimed'])

    def test_substring_does_not_invent_splicing_concern(self):
        self.row['originalAssertion']['comments'][0]['text'] = 'alternative amino acid'
        review, report = self.compile()
        self.assertEqual(review['rnaReview']['knownConcerns'], [])
        self.assertEqual(report['sourceScreen'][0]['conclusion'], 'NO_EXPLICIT_MENTION_IN_RETAINED_COMMENTS')

    def test_missing_comments_not_silently_screened(self):
        del self.row['originalAssertion']['comments']
        with self.assertRaisesRegex(ValueError, 'SOURCE_COMMENTS_REQUIRED'): self.compile()

    def test_only_exact_registered_definition_is_ignored_and_adjacent_concern_survives(self):
        # A test-only definition exercises exact dispatch; production contains only the fixed published definition digest.
        text = 'Test definition mentioning splicing.'
        digest = compiler.hashlib.sha256(text.casefold().encode()).hexdigest()
        compiler.DEFINITION_HASHES[digest] = 'TEST_ONLY'
        try:
            self.row['originalAssertion']['comments'][0]['text'] = text
            review, report = self.compile()
            self.assertEqual(review['rnaReview']['knownConcerns'], [])
            self.assertEqual(len(report['sourceScreen'][0]['criterionDefinitionMentions']), 1)
            for changed in (text + '; observed abnormal splicing.', text + ' observed abnormal splicing.'):
                self.row['originalAssertion']['comments'][0]['text'] = changed
                self.assertEqual(len(self.compile()[0]['rnaReview']['knownConcerns']), 1)
        finally:
            del compiler.DEFINITION_HASHES[digest]


if __name__ == '__main__':
    unittest.main()
