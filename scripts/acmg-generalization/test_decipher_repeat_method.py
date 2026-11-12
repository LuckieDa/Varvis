import copy
import tempfile
import unittest
from pathlib import Path
from decipher_repeat_method import parse_output, validate_sequence, verify_runtime
from protein_repeat_method import save, sha


class DecipherParsingTest(unittest.TestCase):
    sequence = 'ACDEACDE'
    raw = b'BEGIN\tPROT\t3.8.1\tFALSE\nGROUP\t1\t1\t8\t9.1\nUNIT\t1\t1\t1\t4\tACDE\nUNIT\t1\t2\t5\t8\tACDE\nEND\tPROT\t1\n'

    def parse(self, raw=None):
        return parse_output(self.raw if raw is None else raw, 'PROT', self.sequence, False)

    def test_exact_units_with_no_adoption(self):
        result = self.parse()
        self.assertEqual(result['groups'][0]['units'][1]['start'], 5)
        self.assertFalse(result['nonRepeatQualified'])
        self.assertFalse(result['clinicalAdoption'])

    def test_no_hit_is_not_negative_qualification(self):
        result = self.parse(b'BEGIN\tPROT\t3.8.1\tFALSE\nEND\tPROT\t0\n')
        self.assertEqual(result['state'], 'NO_REPEAT_DETECTED')
        self.assertFalse(result['nonRepeatQualified'])

    def test_truncation_identity_mode_and_completion(self):
        for raw in [self.raw[:-12], self.raw.replace(b'PROT', b'ELSE'), self.raw.replace(b'FALSE', b'TRUE'),
                    self.raw.replace(b'3.8.1', b'3.9.4'), self.raw.replace(b'END\tPROT\t1', b'END\tPROT\t0'),
                    self.raw + b'junk\n']:
            with self.subTest(raw=raw), self.assertRaises(ValueError): self.parse(raw)

    def test_malformed_intervals_and_sequences_rejected(self):
        for old, new in [(b'5\t8\tACDE', b'4\t7\tEACD'), (b'5\t8\tACDE', b'5\t9\tACDE'),
                         (b'5\t8\tACDE', b'5\t8\tAAAA'), (b'UNIT\t1\t2', b'UNIT\t1\t1'),
                         (b'GROUP\t1', b'GROUP\t2'), (b'\t9.1', b'\tnan'), (b'\t9.1', b'\t7.99')]:
            with self.subTest(new=new), self.assertRaises(ValueError): self.parse(self.raw.replace(old,new))

    def test_single_unit_and_uncovered_hull_rejected(self):
        for raw in [self.raw.replace(b'UNIT\t1\t2\t5\t8\tACDE\n',b''),
                    self.raw.replace(b'GROUP\t1\t1',b'GROUP\t1\t2')]:
            with self.assertRaises(ValueError): self.parse(raw)

    def test_bounds(self):
        for name, seq in [('CON','A'), ('../escape','A'), ('X','A'*6001), ('X','AX'), ('X','')]:
            with self.assertRaises(ValueError): validate_sequence(name,seq)
        validate_sequence('LARGE','A'*6000)

    def test_runtime_pin_inventory_and_changed_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); (root/'library').mkdir(); (root/'R-4.6.1').mkdir()
            file=root/'library/a'; file.write_bytes(b'A')
            manifest=root/'manifest.json'
            save(manifest,dict(files=[dict(path='library/a',sha256=sha(b'A'))]))
            pin=sha(manifest.read_bytes())
            verify_runtime(root,manifest,pin)
            with self.assertRaises(ValueError): verify_runtime(root,manifest,'0'*64)
            file.write_bytes(b'B')
            with self.assertRaises(ValueError): verify_runtime(root,manifest,pin)
            file.write_bytes(b'A'); (root/'library/new').write_bytes(b'new')
            with self.assertRaises(ValueError): verify_runtime(root,manifest,pin)


if __name__ == '__main__': unittest.main()
