"""Stage4 source and adapter controls; synthetic inputs, no network/model calls."""
from copy import deepcopy
import base64
import gzip
from pathlib import Path
import tempfile
import unittest
from test_core_context_review import LocalFixture, policy
from test_bundle_qualification import article, synthetic_intake, registered, candidate
from test_literature_source_assertions import RECORD, VCV
from bundle_candidate_intake import replay_intake
from core_case_completion import event_source, phenotype_matrix
from core_evaluation_intake import explain
from model_literature_contract import canonical, digest, parse


def config_for(root):
    f = LocalFixture(root)
    f.config['clinvar'] = [{**f.put('clinvar.xml', RECORD), 'accession': VCV}]
    p = policy()
    p['scope']['hgncId'] = 'HGNC:999999997'
    p['literatureTerms'][0]['aliases'] = ['Synthetic trait']
    spec = {'scope': p['scope'], 'policy': f.put('policy.json', canonical(p)),
            'sources': {'paper': f.put('knowledge.txt', b'fixed knowledge')}}
    config = {'schemaVersion': 'denovo-core-context-config-v1', 'identity': f.config, 'contexts': [spec]}
    pin = f.put('core-config.json', canonical(config))
    return {'root': str(root), **pin}


class CoreEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def run_case(self, raw=None, config_change=None):
        raw = article() if raw is None else raw
        source, intake = synthetic_intake(self.root, raw)
        options = config_for(self.root)
        if config_change:
            config = parse((self.root / options['path']).read_bytes()); config_change(config)
            (self.root / options['path']).write_bytes(canonical(config)); options['sha256'] = digest(canonical(config))
        facts = replay_intake(raw, source['pmcid'], digest(raw), intake, work_root=self.root, core_context=options)
        return facts['cases'][0]['coreEvaluation'], facts, intake, source, options

    def test_primary_event_ready_replays_deterministically(self):
        core, facts, intake, source, options = self.run_case()
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual('AUTHOR_COHORT', core['values']['/groups/case/origin'])
        self.assertTrue(core['values']['/groups/overlap/eventId'].startswith('literature:'))
        again = replay_intake(source['raw'], source['pmcid'], digest(source['raw']), intake, work_root=self.root, core_context=options)
        self.assertEqual(facts, again)
        self.assertEqual('NOT_RUN', core['criterionDecision'])
        archive = facts['coreAuditArchive']; raw = gzip.decompress(base64.b64decode(archive['payload']))
        self.assertEqual(archive['sha256'], digest(raw)); self.assertEqual(archive['uncompressedBytes'], len(raw))
        saved = parse(raw)
        for pin, encoded in saved['originalsBySha256'].items():
            self.assertEqual(pin, digest(base64.b64decode(encoded)))
        for case in facts['cases']:
            for item in case['coreEvaluation']['sourcePins'].values():
                self.assertIn(item['sha256'], saved['originalsBySha256'])

    def test_assumed_parentage_remains_assumed_and_ready(self):
        raw = article().replace(b'SNP analysis confirmed both maternity and paternity.', b'No parentage testing was performed.')
        core, *_ = self.run_case(raw)
        self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
        self.assertEqual('ASSUMED_NOT_TESTED', core['values']['/groups/parentage/maternity'])

    def test_display_keeps_all_groups_unknowns_and_provenance_without_paths(self):
        core, *_ = self.run_case()
        view = core['display']
        self.assertEqual('NOT_RUN', view['criterionDecision'])
        self.assertEqual('SYNTHETIC', view['dataClass'])
        self.assertEqual(12, len({f['path'].split('/')[2] for f in view['fields'] if f['path'].startswith('/groups/')}))
        self.assertTrue(any(f['availability'] == 'NOT_EXTRACTED' for f in view['fields']))
        self.assertEqual(core['documentSha256'], view['documentSha256'])
        self.assertNotIn(str(self.root), str(view))
        for field in view['fields']:
            for c in field['citations']:
                if c['role'] not in ('PAPER_PRIMARY', 'PAPER_ATTACHMENT'):
                    self.assertEqual('', c['quote'])
                    self.assertEqual('INTERNAL_REVIEW', c['locator'])

    def test_display_missing_context_never_turns_into_negative_result(self):
        core, *_ = self.run_case(config_change=lambda c: c.update(contexts=[]))
        view = core['display']
        self.assertEqual('INSUFFICIENT_DATA', view['status'])
        self.assertTrue(any(b['category'] == 'KNOWLEDGE_MISSING' for b in view['blockers']))
        self.assertFalse(any(k in view for k in ('score', 'appliedStrength', 'clinicalApproval')))

    def test_missing_policy_cannot_borrow_old_facts(self):
        core, *_ = self.run_case(config_change=lambda c: c.update(contexts=[]))
        self.assertEqual('INSUFFICIENT_DATA', core['status'])
        self.assertTrue(any(b['category'] == 'KNOWLEDGE_MISSING' for b in core['blockers']))

    def test_prior_report_does_not_become_new_event(self):
        core, *_ = self.run_case(article().replace(b'Case A was studied.', b'Case A was studied. This patient was previously reported elsewhere.'))
        self.assertEqual('INSUFFICIENT_DATA', core['status'])
        self.assertIsNone(core['values']['/groups/overlap/eventId'])

    def test_source_contradiction_blocks_ready(self):
        core, *_ = self.run_case(article().replace(b'Case A was studied.', b'Case A was studied. The variant was inherited from the mother.'))
        self.assertNotEqual('READY_FOR_EVALUATION', core['status'])

    def test_changed_config_pin_cannot_reuse_qualified_result(self):
        _, _, intake, source, options = self.run_case()
        (self.root / options['path']).write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError, 'PIN_MISMATCH'):
            replay_intake(source['raw'], source['pmcid'], digest(source['raw']), intake, work_root=self.root, core_context=options)

    def test_matrix_positive_minus_not_assumed_normal_and_no_aggregate(self):
        extra = b'<table-wrap><table><thead><tr><th>Patient characteristics</th><th>A</th><th>Literature cohort</th></tr></thead><tbody><tr><td>Seizures</td><td>+</td><td>9</td></tr><tr><td>Hearing loss</td><td>-</td><td>+</td></tr></tbody></table></table-wrap>'
        _, source = registered(self.root, article().replace(b'</body>', extra + b'</body>'))
        result = phenotype_matrix(source['main'], 'A')
        self.assertEqual(['Seizures'], [r[0] for r in result])
        self.assertEqual([], phenotype_matrix(source['main'], 'B'))

    def test_primary_origin_is_reconstructed_not_model_flag(self):
        _, source = registered(self.root, article().replace(b'Patients were recruited for this study.', b'Patients were not recruited for this study.'))
        result = event_source(source['main'], 'A', [])
        self.assertFalse(result['primary'])

    def test_not_extracted_is_never_paper_absence(self):
        result = explain({'blockers': [{'path': '/x', 'code': 'NOT_EXTRACTED', 'category': 'DATA_MISSING'}]}, {'issues': []})
        self.assertEqual('EXTRACTION_OR_REVIEW_INCOMPLETE', result[0]['category'])
        self.assertEqual('NO_CLAIM_OF_SOURCE_ABSENCE', result[0]['meaning'])


def write_java_fixture(root):
    """Prepare a local-only reference/config for the existing Java synthetic material."""
    root = Path(root)
    f = LocalFixture(root)
    # Existing fixture allele chr11:2 T>A, independently bind the matching reference base.
    fasta = f.local / 'reference.fa'; raw = fasta.read_bytes().replace(b'>chr1\n', b'>chr11\n')
    raw = raw[:8] + b'T' + raw[9:]; fasta.write_bytes(raw)
    fai = b'chr11\t300\t7\t50\t51\n'; (f.local / 'reference.fa.fai').write_bytes(fai)
    f.put('aliases.txt', b'11 chr11\n')
    receipt = parse((f.local / 'receipt.json').read_bytes()); r = receipt['records']['reference']; claim = r['actualChecksumReceipt']
    r.update(expectedSha256=digest(raw), observedSha256=digest(raw))
    claim.update(expectedSha256=digest(raw), observedSha256=digest(raw), byteLength=len(raw), mtimeNs=fasta.stat().st_mtime_ns, indexSha256=digest(fai))
    ref = f.config['reference']; ref['receipt'] = f.put('receipt.json', canonical(receipt)); ref['sha256'] = digest(raw); ref['aliases'] = f.put('aliases.txt', b'11 chr11\n')
    cv = (root / 'synthetic-literature-clinvar.xml').read_bytes()
    f.config['clinvar'] = [{**f.put('clinvar.xml', cv), 'accession': 'VCV999999997'}]
    raw_policy = (root / 'phenotype.json').read_bytes(); p = parse(raw_policy)
    spec = {'scope': p['scope'], 'policy': f.put('policy.json', raw_policy), 'sources': {'s': f.put('knowledge.txt', (root / 'source.txt').read_bytes())}}
    config = {'schemaVersion': 'denovo-core-context-config-v1', 'identity': f.config, 'contexts': [spec]}
    f.put('core-config.json', canonical(config))
    support = root / 'scripts/acmg-generalization'; support.mkdir(parents=True)
    for name in ('normalize_public_panel.py', 'build_transcript_model.py'):
        (support / name).write_bytes((Path(__file__).resolve().parents[1] / 'acmg-generalization' / name).read_bytes())
