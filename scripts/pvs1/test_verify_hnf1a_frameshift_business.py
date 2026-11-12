"""Focused tests for the independent verifier, not real-resource acceptance."""
import copy
from decimal import Decimal
import unittest

from scripts.pvs1.verify_hnf1a_frameshift_business import (
    RES, coding_edit, derive_science, e1, read, timestamp_seconds, translate, vep_local_peptide,
    vep_peptide_alleles, verify_execution_profile, verify_profile, verify_vep_measurements,
)


class IndependentFrameshiftVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = read(RES / 'pvs1-hnf1a-transcript-model-v1.json')
        cls.policy = read(RES / 'pvs1-hnf1a-business-profile-v2.json')['policy']

    def insertion(self):
        return ({'assembly': 'GRCh38', 'contig': '12', 'position': 120994314,
                 'reference': 'G', 'alternate': 'GC'},
                {'exon': '4/10', 'consequence': 'frameshift_variant',
                 'hgvsCoding': 'ENST00000257555.11:c.872dup',
                 'hgvsProtein': 'ENSP00000257555.5:p.Gly292ArgfsTer25',
                 'proteinPosition': '288-289', 'aminoAcids': '-/X', 'codons': '-/C'})

    def deletion(self):
        return ({'assembly': 'GRCh38', 'contig': '12', 'position': 120993577,
                 'reference': 'CA', 'alternate': 'C'},
                {'exon': '3/10', 'consequence': 'frameshift_variant',
                 'hgvsCoding': 'ENST00000257555.11:c.586del',
                 'hgvsProtein': 'ENSP00000257555.5:p.Thr196ProfsTer37',
                 'proteinPosition': '195', 'aminoAcids': 'P/X', 'codons': 'ccA/cc'})

    def within_codon_insertion(self):
        # Engineered reference-sequence example, not a clinical control. Fixed
        # expectations follow local TAC -> TCAC and independent full-CDS math.
        return (dict(assembly='GRCh38', contig='12', position=120988870, reference='T', alternate='TC'),
                dict(exon='2/10', consequence='frameshift_variant',
                     hgvsCoding='ENST00000257555.11:c.364_365insC',
                     hgvsProtein='ENSP00000257555.5:p.Tyr122SerfsTer66',
                     proteinPosition='122', aminoAcids='Y/SX', codons='tac/tCac'))

    def derive(self, allele, annotation):
        return derive_science(allele, annotation, self.model, self.policy)

    def test_vep116_local_triplets_remainder_stop_and_empty_semantics(self):
        # Official peptide()/pep_allele_string() behavior, not Java output.
        for ref, alt, expected in [('TAC', 'TCAC', 'Y/SX'), ('TAC', 'TAAC', 'Y/*'),
                                   ('-', 'ACCT', '-/TX'), ('-', 'TAAACCT', '-/*TX'),
                                   ('TAC', 'TAAACC', 'Y/*T'), ('-', 'TAAACC', '-/*T'),
                                   ('TAC', 'TACAAAC', 'Y/YKX'), ('TAC', '', 'Y/-'),
                                   ('', '', '-'), ('TAC', 'TAT', 'Y'),
                                   ('TAA', 'TAAC', '*'), ('TAA', 'TAATAAC', '*/**X')]:
            with self.subTest(reference=ref, alternate=alt):
                self.assertEqual(vep_peptide_alleles(ref, alt), expected)
        self.assertEqual(translate('TAAACC'), '*')
        self.assertEqual(vep_local_peptide('TAAACC'), '*T')
        for invalid in ('ACNT', '--', 'A-X'):
            with self.assertRaisesRegex(ValueError, 'unambiguous local codon'):
                vep_local_peptide(invalid)

    def test_legitimate_local_sx_reconstructs_full_cds_and_nmd(self):
        allele, annotation = self.within_codon_insertion()
        before = copy.deepcopy((allele, annotation))
        value = self.derive(allele, annotation)
        self.assertEqual((value['firstChangedProteinPosition'], value['terminationDistance']), (122, 66))
        self.assertEqual((value['ptcStart'], value['ptcEnd']), (559, 561))
        self.assertEqual((value['mutantLastJunctionCdsPosition'], value['distanceToLastJunction']), (1769, 1208))
        self.assertEqual(value['nmdPrediction'], 'PREDICTED_NMD')
        self.assertEqual((allele, annotation), before)

    def test_local_sx_does_not_relax_independent_annotation_gates(self):
        for field, bad, message in (
                ('aminoAcids', 'Y/X', 'raw VEP amino acids'),
                ('aminoAcids', 'Y/S', 'raw VEP amino acids'),
                ('aminoAcids', 'Y/SXX', 'raw VEP amino acids'),
                # TCAT also translates to SX: agreement between two supplied
                # local fields is insufficient without the actual allele/CDS.
                ('codons', 'TAC/TCAT', 'raw VEP local codons'),
                ('codons', 'TAT/TCAC', 'raw VEP local codons'),
                ('proteinPosition', '122-123', 'raw VEP protein span'),
                ('hgvsProtein', 'ENSP00000257555.5:p.Tyr122SerfsTer65', 'HGVSp'),
                ('hgvsCoding', 'ENST00000257555.11:c.364_365insA', 'complete HGVS/VCF CDS equivalence')):
            with self.subTest(field=field, value=bad):
                allele, annotation = self.within_codon_insertion()
                annotation[field] = bad
                with self.assertRaisesRegex(ValueError, message):
                    self.derive(allele, annotation)

    def test_four_base_boundary_insertion_uses_local_tx(self):
        allele = dict(assembly='GRCh38', contig='12', position=120994314, reference='G', alternate='GACCT')
        annotation = dict(exon='4/10', consequence='frameshift_variant',
                          hgvsCoding='ENST00000257555.11:c.864_865insACCT',
                          hgvsProtein='ENSP00000257555.5:p.Pro289ThrfsTer29',
                          proteinPosition='288-289', aminoAcids='-/TX', codons='-/ACCT')
        value = self.derive(allele, annotation)
        self.assertEqual((value['firstChangedProteinPosition'], value['terminationDistance']), (289, 29))
        self.assertEqual((value['ptcStart'], value['ptcEnd']), (949, 951))
        self.assertEqual((value['mutantLastJunctionCdsPosition'], value['distanceToLastJunction']), (1772, 821))
        self.assertEqual(value['nmdPrediction'], 'PREDICTED_NMD')
        annotation['aminoAcids'] = '-/X'
        with self.assertRaisesRegex(ValueError, 'raw VEP amino acids'):
            self.derive(allele, annotation)

    def test_policy_lineage_and_other_seven_rules_preserved(self):
        profile, _ = verify_profile()
        self.assertEqual(profile['profileVersion'], '2.0.1')
        previous, _ = verify_profile('4869683da641fbf3b0adc3bb494d638a61decc9ba10baa6a0d952358e14ae6bc')
        self.assertEqual(previous['profileVersion'], '2.0.0')
        self.assertEqual(profile['policy'], previous['policy'])
        self.assertNotEqual(e1.canonical_sha256(profile), e1.canonical_sha256(previous))
        with self.assertRaisesRegex(ValueError, 'unregistered saved PVS1 profile identity'):
            verify_profile('0' * 64)

    def test_saved_execution_profile_must_match_its_original_revision(self):
        for revision in (2, 3):
            profile = read(RES / f'pvs1-hnf1a-business-profile-v{revision}.json')
            document = read(RES / f'acmg-business-execution-profile-joint-v{revision}.json')
            document['manifestSha256'] = e1.canonical_sha256(document)
            before = copy.deepcopy(document)
            verify_execution_profile(document, profile)
            self.assertEqual(document, before)
            other_profile = read(RES / f'pvs1-hnf1a-business-profile-v{5 - revision}.json')
            with self.assertRaisesRegex(ValueError, 'saved execution profile'):
                verify_execution_profile(document, other_profile)
            document['manifestSha256'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'saved execution manifest hash'):
                verify_execution_profile(document, profile)

    def test_real_vep_measurements_allow_raw_only_and_preserve_normalized_checks(self):
        # Actual HTTP VEP shape: ordinary textual facts are raw-only; exon
        # additionally has normalizedValue. Neither field may contradict facts.
        rows = [{'metricId': 'hgvsc', 'rawValue': 'ENST00000257555.11:c.872dup'},
                {'metricId': 'vep.exon', 'rawValue': '4/10', 'normalizedValue': '4/10'}]
        expected = {'hgvsc': 'ENST00000257555.11:c.872dup', 'vep.exon': '4/10'}
        verify_vep_measurements(rows, expected)
        rows[1]['normalizedValue'] = '3/10'
        with self.assertRaisesRegex(ValueError, 'normalized vep.exon'):
            verify_vep_measurements(rows, expected)

    def test_formal_iso_and_decimal_timestamp_preserve_nanoseconds(self):
        iso = '2026-09-10T10:56:20.818084600Z'
        epoch = Decimal('1789037780.818084600')
        self.assertEqual(timestamp_seconds(iso), timestamp_seconds(epoch))
        self.assertEqual(timestamp_seconds(iso), timestamp_seconds(iso.replace('600Z', '6Z')))
        self.assertNotEqual(timestamp_seconds(iso), timestamp_seconds(iso.replace('600Z', '601Z')))
        with self.assertRaisesRegex(ValueError, 'preserve exact'):
            timestamp_seconds(float(epoch))

    def test_public_insertion_matches_independent_preflight_translation(self):
        allele, annotation = self.insertion()
        original = copy.deepcopy((allele, annotation))
        value = self.derive(allele, annotation)
        self.assertEqual((value['firstChangedProteinPosition'], value['terminationDistance']), (292, 25))
        self.assertEqual((value['ptcStart'], value['ptcEnd'], value['mutantLastJunctionCdsPosition']), (946, 948, 1769))
        self.assertEqual((value['ptcReferenceStart'], value['ptcReferenceEnd'], value['distanceToLastJunction']), (945, 947, 821))
        self.assertEqual(value['alteredCdsSha256'], 'e007bc9ec14fa0d30ad1887ab5138eddec6fa22c7747dea4440f22b75df6cc27')
        self.assertEqual((allele, annotation), original)

    def test_public_deletion_matches_independent_preflight_translation(self):
        value = self.derive(*self.deletion())
        self.assertEqual((value['firstChangedProteinPosition'], value['terminationDistance']), (196, 37))
        self.assertEqual((value['ptcStart'], value['ptcEnd'], value['mutantLastJunctionCdsPosition']), (694, 696, 1767))
        self.assertEqual((value['ptcReferenceStart'], value['ptcReferenceEnd'], value['distanceToLastJunction']), (695, 697, 1071))
        self.assertEqual(value['alteredCdsSha256'], 'a6df5942bfa6b6e1d81ea3627b9fa37af6fe424d486f503f0a02ef6b428d75d4')

    def test_both_historical_nonsense_controls(self):
        for genomic, coding, amino, exon, distance in ((120989017, 511, 171, 2, 1255),
                                                      (120993678, 685, 229, 3, 1081)):
            with self.subTest(coding=coding):
                allele = dict(assembly='GRCh38', contig='12', position=genomic, reference='C', alternate='T')
                annotation = dict(exon=f'{exon}/10', consequence='stop_gained',
                                  hgvsCoding=f'ENST00000257555.11:c.{coding}C>T',
                                  hgvsProtein=f'ENSP00000257555.5:p.Arg{amino}Ter',
                                  proteinPosition=str(amino), aminoAcids='R/*', codons='CGA/TGA')
                value = self.derive(allele, annotation)
                self.assertEqual(value['distanceToLastJunction'], distance)

    def test_repeated_sequence_hgvs_equivalence_not_coordinate_equality(self):
        allele, annotation = self.insertion()
        original = self.derive(allele, annotation)
        annotation['hgvsCoding'] = 'ENST00000257555.11:c.864_865insC'
        self.assertEqual(self.derive(allele, annotation)['alteredCdsSequence'], original['alteredCdsSequence'])
        annotation['hgvsCoding'] = 'ENST00000257555.11:c.873dup'
        with self.assertRaisesRegex(ValueError, 'complete HGVS/VCF CDS equivalence'):
            self.derive(allele, annotation)

    def test_full_reference_anchor_is_checked(self):
        allele, annotation = self.deletion()
        allele.update(reference='GA', alternate='G')
        with self.assertRaisesRegex(ValueError, 'full CDS REF'):
            self.derive(allele, annotation)

    def test_transcript_version_and_in_frame_indels_are_rejected(self):
        allele, annotation = self.insertion()
        annotation['hgvsCoding'] = 'ENST00000257555.10:c.872dup'
        with self.assertRaisesRegex(ValueError, 'versioned coding HGVS'):
            self.derive(allele, annotation)
        annotation['hgvsCoding'] = 'ENST00000257555.11:c.872dup'
        allele['alternate'] = 'GCCC'
        with self.assertRaisesRegex(ValueError, 'in-frame indel'):
            self.derive(allele, annotation)

    def test_hgvsp_relative_count_and_raw_codons_both_validated(self):
        for field, bad, message in (('hgvsProtein', 'ENSP00000257555.5:p.Gly292ArgfsTer26', 'HGVSp'),
                                    ('proteinPosition', '292', 'raw VEP protein span'),
                                    ('codons', '-/A', 'raw VEP local codons')):
            with self.subTest(field=field):
                allele, annotation = self.insertion()
                annotation[field] = bad
                with self.assertRaisesRegex(ValueError, message):
                    self.derive(allele, annotation)

    def test_hgvs_equivalence_cannot_cross_exons(self):
        with self.assertRaisesRegex(ValueError, 'HGVS exon boundary'):
            coding_edit('ENST00000257555.11:c.955_956insA', self.model, 4)
        with self.assertRaisesRegex(ValueError, 'HGVS exon boundary'):
            coding_edit('ENST00000257555.11:c.954_956del', self.model, 4)

    def test_ptc_middle_inserted_base_is_retained_in_provenance(self):
        # The bounded real VEP 116 batch reports BOTH consequence terms for this
        # engineered reference-sequence allele. TAC -> TAAC yields local '*',
        # while the inserted middle base of the full-CDS PTC has no reference ID.
        allele = dict(assembly='GRCh38', contig='12', position=120988870, reference='T', alternate='TA')
        annotation = dict(exon='2/10', consequence='stop_gained&frameshift_variant',
                          hgvsCoding='ENST00000257555.11:c.365dup',
                          hgvsProtein='ENSP00000257555.5:p.Tyr122Ter',
                          proteinPosition='122', aminoAcids='Y/*', codons='tac/tAac')
        value = self.derive(allele, annotation)
        self.assertEqual((value['ptcStart'], value['ptcEnd']), (364, 366))
        self.assertEqual((value['ptcReferenceStart'], value['ptcReferenceEnd']), (364, 365))
        self.assertIsNone(value['ptcReferenceMiddle'])
        self.assertEqual(value['ptcReferenceMapping'], 'CONTAINS_INSERTED_BASES')
        self.assertEqual((value['nmdPrediction'], value['variantMechanism']), ('PREDICTED_NMD', 'FRAMESHIFT'))
        self.assertEqual((value['mutantLastJunctionCdsPosition'], value['distanceToLastJunction']), (1769, 1403))
        for wrong_amino in ('Y/X', 'Y/*X'):
            annotation['aminoAcids'] = wrong_amino
            with self.assertRaisesRegex(ValueError, 'raw VEP amino acids'):
                self.derive(allele, annotation)


if __name__ == '__main__':
    unittest.main()
