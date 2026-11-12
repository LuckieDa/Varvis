"""Explicitly synthetic importer/error fixtures; no network or scientific approval."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from register_materials import snapshot, write_snapshot, sha, files

PAPER = b'<article><front><article-meta><article-id pub-id-type="pmcid">PMC999991</article-id><article-id pub-id-type="pmid">999991</article-id><title-group><article-title>SYNTHETIC</article-title></title-group></article-meta></front><body><p>SYNTHETIC ONLY</p></body></article>'


class RegistrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.base = self.root/'base'
        self.base.mkdir()
        for name in ['catalog.json', 'selector.json', 'policy.json']:
            (self.base/name).write_bytes(b'{}')
        self.manifest = {'schemaVersion': 'denovo-selected-materials-v1', 'materialClass': 'SYNTHETIC', 'sources': [], 'reports': [],
                         'phenotypePolicies': [self.bind('policy.json')], 'catalog': self.bind('catalog.json'), 'selectorPolicies': self.bind('selector.json')}
        self.save()
        self.paper = self.root/'paper.xml'; self.paper.write_bytes(PAPER)
        self.report = self.root/'report.json'
        self.report.write_text(json.dumps({'schemaVersion': 'denovo-local-report-v1', 'sourceClass': 'SYNTHETIC',
                                          'caseId': 'case-a', 'familyId': 'family-a', 'members': [{'role': 'PROBAND', 'memberId': 'person-a'}],
                                          'target': {'assembly': 'GRCh38'}}), encoding='utf-8')

    def bind(self, name):
        return {'path': name, 'sha256': sha((self.base/name).read_bytes())}

    def save(self):
        self.file = self.base/'manifest.json'
        self.file.write_text(json.dumps(self.manifest), encoding='utf-8')
        self.pin = sha(self.file.read_bytes())

    def run_snapshot(self, **kwargs):
        return snapshot(self.file, self.pin, **kwargs)

    def test_base_and_originals_unchanged_no_artificial_literature_scope(self):
        before = self.file.read_bytes()
        m, blobs, r = self.run_snapshot()
        self.assertEqual(before, self.file.read_bytes())
        self.assertNotIn('literature', m)
        self.assertEqual({'{}'}, {b.decode() for b in blobs.values()})
        self.assertEqual('NOT_GRANTED', r['scientificApproval'])
        self.assertFalse(r['completeLiteratureSearch'])
        self.assertEqual(0, r['networkCalls'])

    def test_report_dedup_preserves_bytes_and_requires_explicit_binding(self):
        m, blobs, r = self.run_snapshot(reports=[self.report, self.report])
        self.assertEqual(1, len(m['reports'])); self.assertEqual(1, len(r['caseBindings']))
        self.assertEqual(self.report.read_bytes(), blobs[m['reports'][0]['sha256']])
        self.assertEqual('EXPLICIT_CURRENT_CASE_BINDING_REQUIRED', r['caseBindings'][0]['bindingStatus'])
        self.assertEqual('person-a', r['caseBindings'][0]['probandId'])

    def test_new_paper_is_not_identity_proof(self):
        m, blobs, r = self.run_snapshot(papers=[self.paper])
        self.assertEqual('PMC999991', m['literature'][0]['pmcid'])
        self.assertEqual([], m['literature'][0]['alleles'])
        self.assertEqual('LITERATURE_ALLELE_IDENTITY_SOURCE_REQUIRED', r['issues'][0]['reasonCode'])
        self.assertEqual(PAPER, blobs[m['sources'][0]['sha256']])

    def test_variant_without_paper_is_registered_for_later_selected_supply(self):
        from test_literature_source_assertions import RECORD, VCV
        variant=self.root/'vcv.xml';variant.write_bytes(RECORD)
        m,blobs,r=self.run_snapshot(variants=[variant,variant])
        self.assertEqual([{'sourceId':'clinvar-'+VCV,'vcv':VCV}],m['citationRecords'])
        self.assertNotIn('literature',m);self.assertEqual('NOT_GRANTED',r['scientificApproval'])
        dest=self.root/'registered';write_snapshot(dest,m,blobs,r)
        again,_,_=snapshot(dest/'manifest.json',sha((dest/'manifest.json').read_bytes()))
        self.assertEqual(m,again)

    def test_duplicate_paper_only_registers_once(self):
        m, blobs, r = self.run_snapshot(papers=[self.paper, self.paper])
        self.assertEqual(1, len(m['literature'])); self.assertEqual(1, len(m['sources']))

    def test_same_pmc_different_original_is_conflict(self):
        changed=self.root/'changed.xml';changed.write_bytes(PAPER.replace(b'SYNTHETIC ONLY',b'CHANGED'))
        with self.assertRaisesRegex(ValueError,'PAPER_VERSION_CONFLICT'):
            self.run_snapshot(papers=[self.paper,changed])

    def test_copies_are_verified_and_output_cannot_be_overwritten(self):
        m,b,r=self.run_snapshot(papers=[self.paper]);dest=self.root/'proposal'
        receipt=write_snapshot(dest,m,b,r)
        self.assertEqual(sha((dest/'manifest.json').read_bytes()),receipt['manifestSha256'])
        for pin,data in b.items(): self.assertEqual(data,(dest/'originals'/pin).read_bytes())
        with self.assertRaises(FileExistsError):write_snapshot(dest,m,b,r)

    def test_changed_base_pin_rejected(self):
        self.file.write_bytes(self.file.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'PIN_MISMATCH'):self.run_snapshot()

    def test_changed_original_pin_rejected(self):
        (self.base/'policy.json').write_bytes(b'{"changed":true}')
        with self.assertRaisesRegex(ValueError,'PIN_MISMATCH'):self.run_snapshot()

    def test_parent_path_rejected(self):
        self.manifest['catalog']['path']='../report.json';self.save()
        with self.assertRaisesRegex(ValueError,'PATH_OUTSIDE_BASE'):self.run_snapshot()

    def test_absolute_path_rejected(self):
        self.manifest['catalog']['path']=str(self.report);self.save()
        with self.assertRaisesRegex(ValueError,'PATH_OUTSIDE_BASE'):self.run_snapshot()

    def test_unknown_base_claim_rejected(self):
        self.manifest['clinicalApproval']='APPROVED';self.save()
        with self.assertRaisesRegex(ValueError,'BASE_SCHEMA'):self.run_snapshot()

    def test_model_draft_not_a_report(self):
        self.report.write_text('{"schemaVersion":"llm-draft-v1","sourceClass":"LOCAL_REPORT"}')
        with self.assertRaisesRegex(ValueError,'REPORT_SCHEMA'):self.run_snapshot(reports=[self.report])

    def test_synthetic_not_silently_promoted_to_research(self):
        self.manifest['materialClass']='SOURCE_ASSESSED_RESEARCH';self.save()
        with self.assertRaisesRegex(ValueError,'SYNTHETIC_REPORT_IN_RESEARCH'):self.run_snapshot(reports=[self.report])

    def test_duplicate_json_keys_rejected(self):
        self.report.write_text('{"schemaVersion":"denovo-local-report-v1","schemaVersion":"forged"}')
        with self.assertRaisesRegex(ValueError,'DUPLICATE_JSON_KEY'):self.run_snapshot(reports=[self.report])

    def test_entity_declaration_rejected(self):
        self.paper.write_bytes(b'<!DOCTYPE article [<!ENTITY a SYSTEM "file:///private">]>'+PAPER)
        with self.assertRaisesRegex(ValueError,'XML_ENTITY_FORBIDDEN'):self.run_snapshot(papers=[self.paper])

    def test_unbounded_counts_and_bytes_rejected(self):
        with self.assertRaisesRegex(ValueError,'INPUT_COUNT_LIMIT'):self.run_snapshot(reports=[self.report]*101)
        with patch('register_materials.MAX_TOTAL',1):
            with self.assertRaisesRegex(ValueError,'TOTAL_SIZE_LIMIT'):self.run_snapshot()
        self.paper.write_bytes(b'x'*1_048_577)
        with self.assertRaisesRegex(ValueError,'FILE_SIZE_LIMIT'):self.run_snapshot(papers=[self.paper])

    def test_directory_typo_not_empty_search_and_no_recursion(self):
        with self.assertRaisesRegex(ValueError,'DIRECTORY_UNAVAILABLE'):files(self.root/'missing','.json',100)
        self.assertEqual([self.report],files(self.root,'.json',100))

    def test_only_precise_variant_citation_links_a_paper(self):
        variant=self.root/'vcv.xml';variant.write_bytes(b'<VariationArchive Accession="VCV000000001"/>')
        for pmid, expected in [('999991',1),('999992',0)]:
            # Synthetic identity-reader output: does not test or bypass production XML qualification.
            with patch('register_materials.read_record',return_value={'pubmed':[{'pmid':pmid}]}):
                m,blobs,r=self.run_snapshot(papers=[self.paper],variants=[variant,variant])
            self.assertEqual(expected,len(m['literature'][0]['alleles']))
            self.assertEqual(2,len(m['sources']))

    def test_missing_proband_identifier_is_not_a_case_binding(self):
        n=json.loads(self.report.read_bytes());n['members'][0]['memberId']=None;self.report.write_text(json.dumps(n))
        with self.assertRaisesRegex(ValueError,'REPORT_CASE_IDENTITY'):self.run_snapshot(reports=[self.report])

    def test_failed_import_does_not_create_output(self):
        self.paper.write_bytes(b'not XML')
        with self.assertRaises(Exception):self.run_snapshot(papers=[self.paper])
        self.assertFalse((self.root/'proposal').exists())


if __name__=='__main__':unittest.main()
