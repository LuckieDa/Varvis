import unittest
from scripts.pm1.verify_hnf1a_pm1 import RESIDUES, location, verify_policy, instant_seconds
from decimal import Decimal
from scripts.pm1.pm1_network_acceptance import validate_vcf


class Pm1VerifierTest(unittest.TestCase):
    def test_regions_are_closed_and_do_not_extend_into_adjacent_residues(self):
        for position,expected in ((2,'SUPPORTING'),(32,'SUPPORTING'),(33,'NONE'),(106,'NONE'),(107,'SUPPORTING'),
                                  (174,'SUPPORTING'),(175,'NONE'),(200,'NONE'),(201,'SUPPORTING'),(280,'SUPPORTING'),(281,'NONE'),(631,'NONE')):
            self.assertEqual(expected,location(position,'A')[0])

    def test_all_moderate_positions_override_their_containing_region(self):
        for position,reference in RESIDUES.items(): self.assertEqual('MODERATE',location(position,reference)[0])

    def test_reference_mismatch_is_not_a_location_match(self):
        with self.assertRaises(ValueError): location(203,'Q')

    def test_source_policy_and_sequence_bindings(self):
        profile,model=verify_policy()
        self.assertEqual(-225,model['exons'][0]['cdsStart'])
        self.assertEqual(['MODERATE','SUPPORTING'],profile['policy']['strengthPrecedence'])

    def test_network_client_rejects_arbitrary_vcf_before_upload(self):
        with self.assertRaises(ValueError):validate_vcf(b'##fileformat=VCFv4.2\n#CHROM\tPOS\n')

    def test_http_iso_and_decimal_instants_preserve_nanoseconds(self):
        stamp='2026-09-10T15:48:40.624988600Z'
        self.assertEqual(Decimal('1789055320.624988600'),instant_seconds(stamp))
        self.assertEqual(instant_seconds(stamp),instant_seconds(Decimal('1789055320.624988600')))
        self.assertNotEqual(instant_seconds(stamp),instant_seconds('2026-09-10T15:48:40.624988601Z'))

    def test_timestamp_does_not_round_binary_floats_or_accept_ambiguous_zones(self):
        for value in (1789055320.6249886,True,'2026-09-10T15:48:40','2026-09-10T15:48:40.1234567890Z'):
            with self.assertRaises(ValueError):instant_seconds(value)


if __name__=='__main__': unittest.main()
