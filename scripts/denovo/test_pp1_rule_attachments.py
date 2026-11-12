"""Exercise attachment identity, format and fail-closed scope checks."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from model_literature_contract import canonical, digest
from pp1_rule_attachments import VERSION, load_manifest
from pp1_specification_context import select_specifications
from test_pp1_meiosis import meiosis_spec, pedigree, evaluate
from test_pp1_specification_context import chosen


class RuleAttachments(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.local = self.root / 'local'
        self.local.mkdir()
        self.spec = meiosis_spec()
        self.original = self.spec['original']
        criterion = self.original['ld']['CriteriaCode'][0]
        criterion['entContent']['instructionsToUse'] = 'See Eligibility document in attachments for affected status.'
        asset = self.local / 'eligibility.docx'
        with ZipFile(asset, 'w') as archive:
            archive.writestr('[Content_Types].xml', '<Types/>')
            archive.writestr('word/document.xml', '<document/>')
        self.release = {'entId':'GN99999','entContent':{'namespace':'GN99999','version':'1.0.0'},'ld':{
            'RuleSet':[{'entContent':deepcopy(self.original['ld']['RuleSet'][0]['entContent']),
                        'ld':{'CriteriaCode':[deepcopy(criterion)], 'File':[{'entType':'File',
                        'entId':'aabbccdd-1122-3344-5566-aabbccddeeff','entContent':{'fileLabel':'Eligibility',
                        'fileName':'eligibility.docx','public':True,'type':'docx','size':str(asset.stat().st_size)}}]}}]}}
        self.entry = {'specificationKey': self.spec['specificationKey'], 'scopePointer':'/ld/RuleSet/0/entContent/genes/0/diseases/0',
                      'clauseSha256':digest(canonical(criterion)), 'label':'Eligibility',
                      'metadataUrl':'https://zenodo.org/records/1/files/eligibility.json', 'filePointer':'/ld/RuleSet/0/ld/File/0',
                      'file':self.pin(asset)}
        self.register_release()

    def pin(self, path):
        return {'path':path.relative_to(self.root).as_posix(), 'sha256':digest(path.read_bytes())}

    def register_release(self):
        path = self.local / 'release.json'
        path.write_bytes(canonical(self.release))
        self.entry['releaseMetadata'] = self.pin(path)

    def check(self, records):
        return select_specifications([self.spec], {}, chosen(), root=self.root, attachment_records=records)

    def test_registered_metadata_and_verified_bytes_are_distinct_from_rule_adoption(self):
        required = self.check(())
        self.assertEqual(('METHOD_UNSUPPORTED','PP1_RULE_ATTACHMENT_REQUIRED'), (required['status'],required['reasonCode']))
        self.assertEqual('REFERENCE_NOT_REGISTERED', required['ruleAttachments'][0]['status'])
        missing = self.check([{**self.entry,'file':None}])
        self.assertEqual('REGISTERED_FILE_UNAVAILABLE', missing['ruleAttachments'][0]['status'])
        manifest = self.local / 'manifest.json'
        manifest.write_bytes(canonical({'schemaVersion':VERSION, 'entries':[self.entry]}))
        checked = self.check(load_manifest(self.root, self.pin(manifest)))
        self.assertEqual('PP1_RULE_ATTACHMENT_CONDITIONS_NOT_IMPLEMENTED', checked['reasonCode'])
        self.assertEqual('VERIFIED_CONDITIONS_NOT_IMPLEMENTED', checked['ruleAttachments'][0]['status'])
        self.assertEqual(self.entry['file']['sha256'], checked['ruleAttachments'][0]['fileSha256'])
        self.assertEqual('https://cspec.genome.network/cspec/File/id/aabbccdd-1122-3344-5566-aabbccddeeff/data',
                         checked['ruleAttachments'][0]['fileSourceUrl'])
        run = evaluate(pedigree(7), checked)
        self.assertEqual(('NOT_EVALUATED', 0), (run['status'],run['awardedPP1Points']))

    def test_wrong_version_scope_clause_and_modified_file_refused(self):
        original = deepcopy(self.release)
        for reason in ('VERSION_MISMATCH','SCOPE_MISMATCH','CLAUSE_MISMATCH'):
            with self.subTest(reason=reason):
                self.release = deepcopy(original)
                if reason == 'VERSION_MISMATCH': self.release['entContent']['version'] = '1.1'
                if reason == 'SCOPE_MISMATCH': self.release['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredMondoId'] = 'MONDO:0000002'
                if reason == 'CLAUSE_MISMATCH': self.release['ld']['RuleSet'][0]['ld']['CriteriaCode'][0]['entContent']['additionalComments'] = 'Unknown limit.'
                self.register_release()
                self.assertTrue(self.check([self.entry])['ruleAttachments'][0]['reasonCode'].endswith(reason))
        self.release = original
        self.register_release()
        path = self.root / self.entry['file']['path']
        path.write_bytes(path.read_bytes() + b'changed')
        self.assertEqual('PP1_RULE_ATTACHMENT_VERIFICATION_FAILED', self.check([self.entry])['reasonCode'])
        self.assertEqual('PP1_RULE_ATTACHMENT_VERIFICATION_FAILED', self.check(None)['reasonCode'])
