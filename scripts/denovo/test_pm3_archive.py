"""Synthetic source envelopes: provenance and boundaries, not clinical proof."""
import tempfile
import unittest
from pathlib import Path
from copy import deepcopy
from test_literature_acquisition import zipped, xlsx, jats
from literature_attachments import parse_attachment
from model_literature_contract import digest
from acquire_public_literature import source_bundle
from paper_library_registration import register
from prepare_literature import prepare
from bundle_model_contract import load, cite
from literature_work_units import plans
from pm3_observations import ownership, cell_position, pm3_row_label


class Archive(unittest.TestCase):
    def test_opt_in_and_member_pins(self):
        raw = zipped([('one.xlsx', xlsx()), ('nested.zip', zipped([('x.csv', b'x')]))])
        self.assertEqual('ARCHIVED_NOT_PARSED', parse_attachment('s.zip', raw)['status'])
        read = parse_attachment('s.zip', raw, allow_archive=True)
        self.assertEqual('ARCHIVE_MEMBERS_READ', read['status'])
        self.assertEqual(digest(xlsx()), read['members'][0]['sha256'])
        self.assertEqual('ARCHIVED_NOT_PARSED', read['members'][1]['status'])
        self.assertTrue(read['members'][0]['sheets'][0]['cells'][0]['mergedCell'])

    def test_bad_path_duplicate_and_budget_refused(self):
        for items in [[('../x.csv', b'x')], [('a.csv', b'x'), ('A.csv', b'y')]]:
            self.assertEqual('ARCHIVED_NOT_PARSED', parse_attachment('s.zip', zipped(items), allow_archive=True)['status'])
        read = parse_attachment('s.zip', zipped([('a.xlsx', xlsx())]), [1], allow_archive=True)
        self.assertEqual('ARCHIVED_NOT_PARSED', read['status'])
        self.assertNotIn('members', read)

    def test_complete_source_and_same_sheet_name_do_not_cross_members(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raw = jats(True).replace(b'table.csv', b's.zip')
            archive = zipped([('a.csv', b'Case,Gene\n#1,GENE1\n'),
                              ('b.csv', 'Case,Gene\nN°1,GENE2\n'.encode())])
            package = zipped([('s.zip', archive)])
            class Transport:
                def get(self, *args): return package
            files, bundle = source_bundle({'pmcid':'PMC123'}, raw, prepare(raw, 'PMC123', digest(raw)), Transport(), True)
            record = register(root, 'PMC123', files, bundle, {'sha256':digest(raw),
                'acquisitionUrl':'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'}, criteria=('PM3',))
            source = load(root, 'PMC123', record['bundleSha256'], 'PM3')
            units = [u for u in source['units'] if u.get('archiveMemberName')]
            self.assertEqual(8, len(units))
            for work in plans(source):
                members = {u['archiveMemberName'] for u in units if u['unitId'] in work['targetUnitIds']}
                self.assertLessEqual(len(members), 1)
            scope = ownership(source, '1')
            gene_units = [u for u in units if u['text'] in ('GENE1','GENE2')]
            self.assertEqual(2, len({scope[u['unitId']]['container'] for u in gene_units}))
            for unit in gene_units:
                citation = cite(source, {'unitId':unit['unitId'], 'quote':unit['text']})
                self.assertEqual(digest(archive), citation['sourceSha256'])
                self.assertEqual(unit['archiveMemberSha256'], citation['archiveMemberSha256'])
                self.assertEqual(unit['archiveMemberName'], citation['archiveMemberName'])

    def test_number_prefix_is_not_general_fuzzy_identity(self):
        self.assertEqual('17', pm3_row_label('#17', 'Proband'))
        self.assertEqual('17', pm3_row_label('N°17', 'Case'))
        self.assertIsNone(pm3_row_label('17 and 18', 'Case'))
        self.assertEqual((12, 27), cell_position('AA12'))
        self.assertEqual((12, 27), cell_position('R12C27'))

    def test_sparse_sheet_numeric_cell_does_not_disqualify_literal_diagnosis(self):
        source = {'main':{'units':[], 'tables':[]}, 'units':[], 'raw':b'<article/>'}
        for cell, text, semantics in [('A2','Case','TEXT'),('B2','Diagnosis','TEXT'),('C2','Age','TEXT'),
                                      ('A3','#1','TEXT'),('B3','Reported disease','TEXT'),('C3','12','RAW_TYPED_VALUE_NOT_INTERPRETED')]:
            source['units'].append({'unitId':cell, 'kind':'SUPPLEMENT_CELL', 'sourceSha256':'a'*64,
                                    'sheet':'Cases','cell':cell,'text':text,'cellSemantics':semantics})
        owned = ownership(source, '1')
        self.assertIn('B3', owned); self.assertNotIn('C3', owned)
        bad = deepcopy(source); bad['units'][3]['mergedCell'] = True
        self.assertEqual({}, ownership(bad, '1'))


if __name__ == '__main__': unittest.main()
