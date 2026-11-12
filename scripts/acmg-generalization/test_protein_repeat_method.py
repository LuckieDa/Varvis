import json
import sys
import tempfile
import unittest
from pathlib import Path
import protein_repeat_method as m


SEQUENCE = 'ACDEFGHIKACDE'
POSITIVE = b'''>TEST
REPEAT_TYPE 1
REPEAT_LENGTH 4
1 4 # Repeat 1
11 3 # Repeat 2
>Repeat 1
ACDE
>Repeat 2
CDE-
# end of the protein TEST
//
'''


def profile_and_panel():
    profile = dict(schemaVersion='protein-repeat-method-profile-v1', method='TRUST',
                   upstreamReadmeVersion='1.0', sourceArchiveSha256=m.ARCHIVE_SHA,
                   parameters=m.PARAMETERS[:], matrix='BLOSUM62', matrixSha256=m.MATRIX_SHA,
                   heapMiB=512, perProteinSeconds=30, totalSeconds=300, maximumProteins=50,
                   maximumProteinLength=3000, maximumTotalResidues=50000,
                   adoption='VALIDATION_ONLY_NO_NEGATIVE_QUALIFICATION')
    raw = json.dumps(profile).encode()
    panel = dict(profileSha256=m.sha(raw), cases=[dict(id='TEST', sequence=SEQUENCE,
                 sequenceSha256=m.sha(SEQUENCE.encode()))])
    return profile, raw, panel


class TrustParserTest(unittest.TestCase):
    def test_preserves_units_and_does_not_fill_gap(self):
        facts = m.parse_trust(POSITIVE, 'TEST', SEQUENCE)
        self.assertEqual([(1, 4), (11, 13)], [(u['start'], u['end']) for u in facts['groups'][0]['units']])
        self.assertEqual('REPEAT_CANDIDATE', facts['algorithmState'])
        self.assertFalse(facts['clinicalAdoption'])
        self.assertFalse(facts['nonRepeatQualified'])
        self.assertFalse(facts['noKnownFunctionQualified'])

    def test_no_hit_is_not_negative_qualification(self):
        for middle in (b'', b'# no repeats found\n'):
            facts = m.parse_trust(b'>TEST\n' + middle + b'# end of the protein TEST\n//\n', 'TEST', SEQUENCE)
            self.assertEqual('NO_REPEAT_DETECTED', facts['algorithmState'])
            self.assertFalse(facts['nonRepeatQualified'])

    def test_missing_completion_cannot_be_no_hit(self):
        for raw in (b'', b'>TEST\n', b'>TEST\n# no repeats found\n', POSITIVE.replace(b'//', b'')):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                m.parse_trust(raw, 'TEST', SEQUENCE)

    def test_wrong_header_or_completion_identity(self):
        for raw in (POSITIVE.replace(b'>TEST', b'>OTHER'), POSITIVE.replace(b'protein TEST', b'protein OTHER')):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                m.parse_trust(raw, 'TEST', SEQUENCE)

    def test_incomplete_group_or_alignment(self):
        for raw in (POSITIVE.replace(b'>Repeat 2\nCDE-\n', b''),
                    POSITIVE.replace(b'CDE-\n', b''), POSITIVE.replace(b'REPEAT_LENGTH 4\n', b'')):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                m.parse_trust(raw, 'TEST', SEQUENCE)

    def test_sequence_mismatch_rejected(self):
        with self.assertRaisesRegex(ValueError, 'SEQUENCE_MISMATCH'):
            m.parse_trust(POSITIVE.replace(b'CDE-', b'CDF-'), 'TEST', SEQUENCE)

    def test_insertions_keep_sequence_and_bounds(self):
        raw = POSITIVE.replace(b'1 4 #', b'1 5 #').replace(b'ACDE\n', b'ACdEF\n')
        self.assertEqual('ACdEF', m.parse_trust(raw, 'TEST', SEQUENCE)['groups'][0]['units'][0]['alignment'])

    def test_coordinate_number_and_profile_boundaries(self):
        replacements = [(b'1 4 #', b'0 4 #'), (b'11 3 #', b'11 4 #'),
                        (b'11 3 #', b'999999 3 #'), (b'# Repeat 2', b'# Repeat 3'),
                        (b'>Repeat 2', b'>Repeat 1'), (b'REPEAT_LENGTH 4', b'REPEAT_LENGTH 3001')]
        for before, after in replacements:
            with self.subTest(after=after), self.assertRaises(ValueError):
                m.parse_trust(POSITIVE.replace(before, after), 'TEST', SEQUENCE)

    def test_upstream_error_and_unknown_comment_rejected(self):
        for middle in (b'# the protein contains characters which are not amino acids', b'# future format'):
            with self.subTest(middle=middle), self.assertRaises(ValueError):
                m.parse_trust(b'>TEST\n' + middle + b'\n# end of the protein TEST\n//', 'TEST', SEQUENCE)

    def test_conflicting_empty_claim_and_duplicate_groups(self):
        for raw in (POSITIVE.replace(b'REPEAT_TYPE', b'# no repeats found\nREPEAT_TYPE'),
                    POSITIVE.replace(b'# end of', b'REPEAT_TYPE 1\n# end of')):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                m.parse_trust(raw, 'TEST', SEQUENCE)

    def test_no_foreign_extra_record_after_completion(self):
        with self.assertRaises(ValueError):
            m.parse_trust(POSITIVE + POSITIVE, 'TEST', SEQUENCE)


class TrustBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='trust-unit-')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def test_scientific_parameters_cannot_silently_change(self):
        profile, raw, panel = profile_and_panel()
        m.validate_panel(panel, profile, raw)
        profile['parameters'] = ['-force']
        with self.assertRaisesRegex(ValueError, 'UNSUPPORTED_METHOD_PROFILE'):
            m.validate_panel(panel, profile, raw)

    def test_profile_and_sequence_pins(self):
        profile, raw, panel = profile_and_panel()
        with self.assertRaisesRegex(ValueError, 'PROFILE_PIN'):
            m.validate_panel(panel, profile, raw + b' ')
        panel['cases'][0]['sequence'] = 'A'
        with self.assertRaisesRegex(ValueError, 'SEQUENCE_PIN'):
            m.validate_panel(panel, profile, raw)

    def test_windows_case_collision(self):
        profile, raw, panel = profile_and_panel()
        panel['cases'].append(dict(panel['cases'][0], id='test'))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_ID'):
            m.validate_panel(panel, profile, raw)

    def test_unsupported_sequence_not_truncated_or_normalized(self):
        for sequence in ('', 'A' * 3001, 'ABCDE', 'acd', 'ACD*', 'AC\nD'):
            with self.subTest(sequence=sequence[:20]), self.assertRaises(ValueError):
                m.validate_sequence('TEST', sequence)
        with self.assertRaises(ValueError):
            m.validate_sequence('../TEST', 'ACD')
        for identifier in ('CON', 'con', 'LPT1', 'COM9', 'NUL'):
            with self.subTest(identifier=identifier), self.assertRaises(ValueError):
                m.validate_sequence(identifier, 'ACD')

    def test_panel_budgets_before_execution(self):
        profile, raw, panel = profile_and_panel()
        panel['cases'] = [dict(id='P' + str(i), sequence='A' * 3000, sequenceSha256=m.sha(b'A' * 3000)) for i in range(17)]
        with self.assertRaisesRegex(ValueError, 'RESIDUE_BUDGET'):
            m.validate_panel(panel, profile, raw)
        panel['cases'] *= 3
        with self.assertRaisesRegex(ValueError, 'PANEL_SIZE'):
            m.validate_panel(panel, profile, raw)

    def test_no_overwrite(self):
        path = self.directory / 'record.json'
        m.save(path, {'original': True})
        with self.assertRaises(FileExistsError):
            m.save(path, {'original': False})
        self.assertEqual({'original': True}, json.loads(path.read_bytes()))

    def test_manifest_pin_before_tool_access(self):
        path = self.directory / 'manifest.json'
        path.write_bytes(b'[]')
        with self.assertRaisesRegex(ValueError, 'MANIFEST_PIN'):
            m.verify_tool(self.directory, path, '0' * 64)
        with self.assertRaisesRegex(ValueError, 'ARCHIVE_OR_MATRIX'):
            m.verify_tool(self.directory, path, m.sha(b'[]'))

    def test_manifest_path_escape(self):
        raw = json.dumps([{'path': '../outside.class', 'sha256': '0' * 64}]).encode()
        path = self.directory / 'manifest.json'
        path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, 'UNSAFE_TOOL_MANIFEST_PATH'):
            m.verify_tool(self.directory, path, m.sha(raw))

    def test_real_owned_timeout_kill_and_wait(self):
        receipt = m.run_process([sys.executable, '-c', 'import time; time.sleep(5)'], self.directory, 0.1)
        self.assertEqual('TIMEOUT', receipt['status'])
        self.assertTrue(receipt['reaped'])
        self.assertNotEqual(0, receipt['exitCode'])
        self.assertLess(receipt['elapsedSeconds'], 3)

    def test_nonzero_exit_does_not_parse(self):
        receipt = m.run_process([sys.executable, '-c', 'raise SystemExit(7)'], self.directory, 3)
        self.assertEqual('NONZERO_EXIT', receipt['status'])
        self.assertEqual(7, receipt['exitCode'])

    def test_output_limit_on_fast_exit(self):
        receipt = m.run_process([sys.executable, '-c', 'print("x"*2000)'], self.directory, 3, stdout_limit=100)
        self.assertEqual('OUTPUT_LIMIT', receipt['status'])
        self.assertTrue(receipt['reaped'])


if __name__ == '__main__':
    unittest.main()
