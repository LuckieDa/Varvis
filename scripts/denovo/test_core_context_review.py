"""Synthetic identity/policy attacks and package provenance. Zero live calls."""
from copy import deepcopy
import gzip
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from model_literature_contract import canonical, digest, parse
from core_local_identity import LocalIdentity, identifiers
from core_context_policy import match_features, read_policy, resolve_context
from core_context_review import context_package, load_config, reason
from core_material_review import review_package
from core_material_extraction import write_package
from core_material_contract import validate, digest as object_digest
from project_core_material import blank
from test_bundle_qualification import article, registered, candidate
from bundle_model_contract import review
from qualify_bundle_candidates import qualify


class LocalFixture:
    def __init__(self, root, strand='+', split=False):
        self.root = root; self.local = root / 'local'; self.local.mkdir(exist_ok=True)
        self.annotation = {'assembly': 'GRCh38', 'transcript': 'ENST000001.1', 'protein': 'ENSP000001.1',
            'hgncId': 'HGNC:1', 'geneSymbol': 'EXAMPLE', 'strand': 1 if strand == '+' else -1, 'contig': '1', 'cdsLength': 3,
            'rawGtfSha256': 'b' * 64, 'exons': [{'cdsStart': 1, 'cdsEnd': 3, 'genomicStart': 100, 'genomicEnd': 102}]}
        seq = list('A' * 300); seq[99:102] = list('ACC' if strand == '+' else 'GGT')
        if split:
            self.annotation['exons'] = [{'cdsStart': 1, 'cdsEnd': 1, 'genomicStart': 100, 'genomicEnd': 100},
                                        {'cdsStart': 2, 'cdsEnd': 3, 'genomicStart': 200, 'genomicEnd': 201}]
            seq[99] = 'A'; seq[199:201] = list('CC')
        raw = b'>chr1\n' + b'\n'.join(''.join(seq)[i:i + 50].encode() for i in range(0, 300, 50)) + b'\n'
        fasta = self.put('reference.fa', raw); self.fasta = self.local / 'reference.fa'
        fai = self.put('reference.fa.fai', b'chr1\t300\t6\t50\t51\n')
        receipt = {'source': 'SYNTHETIC_TEST_ONLY', 'version': '1', 'records': {'reference': {
            'kind': 'REFERENCE_IDENTITY', 'assembly': 'GRCh38', 'resourceDataClass': 'PUBLIC',
            'verificationStatus': 'VERIFIED', 'verificationMethod': 'SHA-256',
            'observedSha256': fasta['sha256'], 'expectedSha256': fasta['sha256'], 'referenceArtifactLocator': 'reference.fa',
            'actualChecksumReceipt': {'resourceId': 'SYNTHETIC', 'resourceVersion': '1', 'assembly': 'GRCh38',
                'logicalName': 'reference.fa', 'expectedSha256': fasta['sha256'], 'observedSha256': fasta['sha256'],
                'hashStatus': 'VERIFIED', 'hashScope': 'RAW_BYTES', 'hashAlgorithm': 'SHA-256', 'unchangedDuringHash': True,
                'byteLength': len(raw), 'mtimeNs': self.fasta.stat().st_mtime_ns, 'indexSha256': fai['sha256'],
                'startedAt': '2020-01-01T00:00:00Z', 'completedAt': '2020-01-01T00:00:01Z'}}}}
        names = ['RefSeq_nuc', 'symbol', 'HGNC_ID', 'Ensembl_nuc', 'Ensembl_prot', 'chr_strand']
        self.mane_text = '\t'.join(names) + '\n' + '\t'.join(['NM_000001.1', 'EXAMPLE', 'HGNC:1', 'ENST000001.1', 'ENSP000001.1', strand]) + '\n'
        mane = self.put('mane.gz', gzip.compress(self.mane_text.encode(), mtime=0))
        self.config = {'contextManifest': self.put('context-manifest.json', canonical({'resources': {'mane': {'rawSha256': mane['sha256']}}})),
            'mane': mane, 'reference': {'receipt': self.put('receipt.json', canonical(receipt)), 'resourceRoot': 'local',
                'sha256': fasta['sha256'], 'aliases': self.put('aliases.txt', b'1 chr1\n')}}
        self.seal_annotation()
        self.doc = blank('PMC123#A', 'SYNTHETIC')
        for f, value in [(self.doc['groups']['gene']['symbol'], 'EXAMPLE'),
                         (self.doc['groups']['variant']['representations'], ['NM_000001.1', 'c.2C>G', 'p.Thr1Ser'])]:
            f.update(value=value, verification='VERIFIED', availability='REPORTED')

    def put(self, name, raw):
        (self.local / name).write_bytes(raw)
        return {'path': 'local/' + name, 'sha256': digest(raw)}

    def seal_annotation(self):
        raw = canonical(self.annotation)
        self.put('data.jsonl', raw)
        index = self.put('index.tsv', ('ENST000001.1\t0\t' + str(len(raw)) + '\t' + digest(raw) + '\n').encode())
        self.config.update(gencodeIndex=index, gencodeData='local/data.jsonl',
            gencodeManifest=self.put('gencode-manifest.json', canonical({'schemaVersion': 'gencode-transcript-index-v1',
                'assembly': 'GRCh38', 'data': {'bytes': len(raw)}, 'index': {'sha256': index['sha256']},
                'source': {'sha256': 'a' * 64}})))

    def resolver(self):
        return LocalIdentity(self.root, self.config)


def policy():
    return {'schemaVersion': 'denovo-phenotype-policy-v1', 'policyId': 'test', 'version': '1', 'materialClass': 'SYNTHETIC',
        'scope': {'hgncId': 'HGNC:1', 'diseaseId': 'MONDO:1', 'inheritance': 'AD'}, 'allowAssumedParentage': True,
        'expertPolicyStatus': 'GENERAL_ALLOWED', 'sources': [{'sourceId': 'paper', 'sha256': digest(b'fixed knowledge'), 'locator': '/', 'quote': 'fixed knowledge'}],
        'conditions': [{'conditionId': 'a', 'category': 'CONSISTENT_HIGH_HETEROGENEITY', 'presentAll': ['HP:0001251'], 'absentAll': [], 'sourceIds': ['paper']}],
        'literatureTerms': [{'hpoId': 'HP:0001251', 'aliases': ['Ataxia'], 'sourceIds': ['paper']}]}


class IdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.f = LocalFixture(self.root)

    def test_plus_strand_exact_coding_ref_and_protein(self):
        result = self.f.resolver().resolve(self.f.doc, '1')
        self.assertEqual({'assembly': 'GRCh38', 'contig': '1', 'position': 101, 'reference': 'C', 'alternate': 'G'}, result['allele'])
        self.assertEqual('ACC', result['codon']); self.assertEqual(3, len(result['referenceReads']))

    def test_minus_strand_complements_without_reversing_codon_order(self):
        f = LocalFixture(self.root, '-')
        result = f.resolver().resolve(f.doc, '1')
        self.assertEqual('G', result['allele']['reference']); self.assertEqual('C', result['allele']['alternate'])
        self.assertEqual('T1S', result['proteinChange']); self.assertEqual('ACC', result['codon'])

    def test_codon_crosses_exon_boundary(self):
        f = LocalFixture(self.root, split=True)
        self.assertEqual(200, f.resolver().resolve(f.doc, '1')['allele']['position'])

    def test_wrong_protein_and_ref_stop_without_querying_alternate_key(self):
        for variant in (['NM_000001.1', 'c.2A>G', 'p.Thr1Ser'], ['NM_000001.1', 'c.2C>G', 'p.Thr1Arg']):
            self.f.doc['groups']['variant']['representations']['value'] = variant
            with self.assertRaisesRegex(ValueError, 'MISMATCH'):
                self.f.resolver().resolve(self.f.doc, '1')

    def test_exact_transcript_version_not_substituted(self):
        self.f.doc['groups']['variant']['representations']['value'][0] = 'NM_000001.2'
        with self.assertRaisesRegex(ValueError, 'EXACT_MANE_MAPPING_UNAVAILABLE') as caught:
            self.f.resolver().resolve(self.f.doc, '1')
        self.assertEqual('RESOURCE_UNAVAILABLE', reason(caught.exception)['category'])

    def test_ambiguous_transcript_mapping_is_a_conflict_not_absence(self):
        resolver = self.f.resolver(); resolver.load()
        resolver.mane += list(resolver.mane)
        with self.assertRaisesRegex(ValueError, 'EXACT_MANE_MAPPING_AMBIGUOUS') as caught:
            resolver.resolve(self.f.doc, '1')
        self.assertEqual('DATA_CONFLICT', reason(caught.exception)['category'])

    def test_no_mane_default_for_unreported_transcript(self):
        self.f.doc['groups']['variant']['representations']['value'].pop(0)
        with self.assertRaisesRegex(ValueError, 'EXACT_TRANSCRIPT_UNAVAILABLE'):
            self.f.resolver().resolve(self.f.doc, '1')

    def test_existing_clinvar_alias_requires_fixed_accession_pmid_and_current_ref(self):
        from test_literature_source_assertions import RECORD, VCV, PMID
        self.f.config['clinvar'] = [{**self.f.put('clinvar.xml', RECORD), 'accession': VCV}]
        self.f.doc['groups']['variant']['representations']['value'] = ['123A>G', 'T41A']
        result = self.f.resolver().resolve(self.f.doc, PMID)
        self.assertEqual(123, result['allele']['position'])
        self.assertEqual('A', result['allele']['reference'])
        with self.assertRaisesRegex(ValueError, 'EXACT_TRANSCRIPT_UNAVAILABLE'):
            self.f.resolver().resolve(self.f.doc, '1')
        self.f.config['clinvar'][0]['accession'] = 'VCV000000001'
        with self.assertRaises(ValueError): self.f.resolver().resolve(self.f.doc, PMID)

    def test_ambiguous_mane_mapping_rejected(self):
        raw = gzip.compress((self.f.mane_text + self.f.mane_text.splitlines()[1] + '\n').encode(), mtime=0)
        self.f.config['mane'] = self.f.put('mane.gz', raw)
        self.f.config['contextManifest'] = self.f.put('context-manifest.json', canonical({'resources': {'mane': {'rawSha256': digest(raw)}}}))
        with self.assertRaisesRegex(ValueError, 'AMBIGUOUS'):
            self.f.resolver().resolve(self.f.doc, '1')

    def test_reference_receipt_mtime_and_index_tamper_rejected(self):
        import os
        s = self.f.fasta.stat(); os.utime(self.f.fasta, ns=(s.st_atime_ns, s.st_mtime_ns + 1000000))
        with self.assertRaisesRegex(ValueError, 'RECEIPT_NOT_CURRENTLY_APPLICABLE'):
            self.f.resolver().resolve(self.f.doc, '1')

    def test_pinned_annotation_corruption_rejected(self):
        p = self.f.local / 'data.jsonl'; b = p.read_bytes(); p.write_bytes(b.replace(b'EXAMPLE', b'EXAMPLX'))
        with self.assertRaisesRegex(ValueError, 'RECORD_PIN_MISMATCH'):
            self.f.resolver().resolve(self.f.doc, '1')

    def test_reference_changes_after_first_base_rejected(self):
        r = self.f.resolver(); original = r.base
        def mutate(*args):
            value = original(*args); self.f.fasta.write_bytes(self.f.fasta.read_bytes()); return value
        with patch.object(r, 'base', side_effect=mutate), self.assertRaisesRegex(ValueError, 'REFERENCE_CHANGED'):
            r.resolve(self.f.doc, '1')

    def test_gene_or_strand_mapping_conflict(self):
        self.f.annotation['hgncId'] = 'HGNC:2'; self.f.seal_annotation()
        with self.assertRaisesRegex(ValueError, 'MAPPING_CONFLICT'):
            self.f.resolver().resolve(self.f.doc, '1')

    def test_multiallelic_and_unsupported_hgvs_do_not_silently_choose(self):
        for extra in ('c.2C>A', 'c.2del', 'NM_000002.1'):
            d = deepcopy(self.f.doc); d['groups']['variant']['representations']['value'].append(extra)
            with self.assertRaises(ValueError): identifiers(d)

    def test_unverified_model_identity_not_consumed(self):
        self.f.doc['groups']['gene']['symbol']['verification'] = 'UNVERIFIED'
        with self.assertRaisesRegex(ValueError, 'REVIEWED_IDENTIFIERS_REQUIRED'):
            self.f.resolver().resolve(self.f.doc, '1')

    def test_missing_gencode_version_is_explicit(self):
        self.f.put('index.tsv', b''); self.f.config['gencodeIndex']['sha256'] = digest(b'')
        p = parse((self.f.local / 'gencode-manifest.json').read_bytes()); p['index']['sha256'] = digest(b'')
        self.f.config['gencodeManifest'] = self.f.put('gencode-manifest.json', canonical(p))
        with self.assertRaisesRegex(ValueError, 'EXACT_GENCODE_TRANSCRIPT_UNAVAILABLE'):
            self.f.resolver().resolve(self.f.doc, '1')


class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.f = LocalFixture(self.root)
        self.p = policy(); self.spec = {'scope': self.p['scope'], 'policy': self.f.put('policy.json', canonical(self.p)),
                                       'sources': {'paper': self.f.put('knowledge.txt', b'fixed knowledge')}}

    def test_existing_policy_and_case_insensitive_alias(self):
        p, _, _ = read_policy(self.root, self.spec, 'SYNTHETIC')
        self.assertEqual('CONSISTENT_HIGH_HETEROGENEITY', match_features(p, [' ataxia '], [])['category'])

    def test_absence_unreported_is_not_negative(self):
        p = policy(); p['conditions'][0]['absentAll'] = ['HP:0001250']
        with self.assertRaisesRegex(ValueError, 'NOT_ESTABLISHED'): match_features(p, ['Ataxia'], [])

    def test_conflicting_alias_or_categories_rejected(self):
        p = policy(); p['literatureTerms'].append({'hpoId': 'HP:0001250', 'aliases': ['Ataxia']})
        with self.assertRaisesRegex(ValueError, 'ALIAS_AMBIGUOUS'): match_features(p, ['Ataxia'], [])
        p = policy(); c = deepcopy(p['conditions'][0]); c.update(conditionId='b', category='HIGHLY_SPECIFIC'); p['conditions'].append(c)
        with self.assertRaisesRegex(ValueError, 'POLICY_CONFLICT'): match_features(p, ['Ataxia'], [])

    def test_positive_and_negative_same_trait_rejected(self):
        with self.assertRaisesRegex(ValueError, 'SOURCE_CONFLICT'): match_features(policy(), ['Ataxia'], ['ataxia'])

    def test_knowledge_tamper_and_use_class_rejected(self):
        with self.assertRaisesRegex(ValueError, 'USE_MISMATCH'): read_policy(self.root, self.spec, 'PUBLIC_PAPER_RESEARCH')
        (self.f.local / 'knowledge.txt').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'PIN_MISMATCH'): read_policy(self.root, self.spec, 'SYNTHETIC')

    def test_background_not_borrowed_or_chosen_from_multiple(self):
        ident = {'hgncId': 'HGNC:1'}; self.spec['scope'] = {**self.p['scope'], 'diseaseId': 'MONDO:2'}
        with self.assertRaisesRegex(ValueError, 'BACKGROUND_MISMATCH'):
            resolve_context(self.root, {'contexts': [self.spec]}, ident, self.f.doc)
        with self.assertRaisesRegex(ValueError, 'SELECTION_AMBIGUOUS'):
            resolve_context(self.root, {'contexts': [self.spec, self.spec]}, ident, self.f.doc)
        with self.assertRaisesRegex(ValueError, 'NOT_CONFIGURED'):
            resolve_context(self.root, {'contexts': []}, ident, self.f.doc)

    def test_missing_phenotype_is_not_policy_failure_or_negative(self):
        result, _, _ = resolve_context(self.root, {'contexts': [self.spec]}, {'hgncId': 'HGNC:1'}, self.f.doc)
        self.assertIsNone(result['phenotype']); self.assertIn('REVIEWED_PHENOTYPE_UNAVAILABLE', result['issues'][0])


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        _, self.source = registered(self.root, article())
        checked = review(self.source, candidate(self.source)); checked['workUnit'] = {'targetUnitIds': [checked['cases'][0]['caseCitation']['unitId']]}
        native = qualify(self.source, [checked])
        write_package(self.source, native, [checked], self.root / 'input', data_class='SYNTHETIC')
        review_package(self.source, self.root / 'input', digest((self.root / 'input/index.json').read_bytes()), self.root / 'review')
        self.pin = digest((self.root / 'review/index.json').read_bytes())

    def run_package(self, name='output', config=None):
        return context_package(self.source, self.root / 'review', self.pin, self.root / name, self.root, config)

    def test_no_configuration_produces_specific_gap_without_scoring(self):
        result = self.run_package(); row = result['cases'][0]
        self.assertTrue(any('NOT_CONFIGURED' in i['code'] for i in row['issues']))
        self.assertFalse(result['businessPublishable']); self.assertEqual('NOT_RUN', result['criterionDecision'])
        self.assertEqual('INSUFFICIENT_DATA', row['readiness'])
        d = parse((self.root / 'output' / row['path']).read_bytes()); ledger = parse((self.root / 'output' / row['ledgerPath']).read_bytes())
        validate(d, self.root / 'output', ledger, object_digest(ledger))

    def test_repeated_offline_input_deterministic_and_original_unchanged(self):
        before = {str(p.relative_to(self.root / 'review')): digest(p.read_bytes()) for p in (self.root / 'review').rglob('*') if p.is_file()}
        a = self.run_package(); b = self.run_package('again'); self.assertEqual(a, b)
        self.assertEqual(before, {str(p.relative_to(self.root / 'review')): digest(p.read_bytes()) for p in (self.root / 'review').rglob('*') if p.is_file()})

    def test_source_review_forgery_cannot_be_resigned_by_caller(self):
        index = parse((self.root / 'review/index.json').read_bytes()); row = index['cases'][0]
        p = self.root / 'review' / row['path']; doc = parse(p.read_bytes()); doc['groups']['mother']['targetResult']['value'] = 'PRESENT'
        p.write_bytes(canonical(doc)); row['sha256'] = digest(p.read_bytes()); raw = canonical(index)
        (self.root / 'review/index.json').write_bytes(raw); self.pin = digest(raw)
        with self.assertRaisesRegex(ValueError, 'REPLAY_MISMATCH'): self.run_package()

    def test_wrong_index_and_stale_reader_rejected(self):
        self.pin = '0' * 64
        with self.assertRaisesRegex(ValueError, 'INDEX_PIN'): self.run_package()
        index = parse((self.root / 'review/index.json').read_bytes()); index['implementation'] = {}
        raw = canonical(index); (self.root / 'review/index.json').write_bytes(raw); self.pin = digest(raw)
        with self.assertRaisesRegex(ValueError, 'VERSION_OR_IDENTITY'): self.run_package()

    def test_identity_success_never_asserts_event_independence(self):
        identity = {'status': 'LOCAL_IDENTITY_VERIFIED', 'hgncId': 'HGNC:1', 'allele':
                    {'assembly': 'GRCh38', 'contig': '1', 'position': 100, 'reference': 'A', 'alternate': 'G'}}
        with patch('core_context_review.LocalIdentity.resolve', return_value=identity):
            result = self.run_package(config={'identity': {'synthetic': True}})
        row = result['cases'][0]; d = parse((self.root / 'output' / row['path']).read_bytes())
        self.assertEqual('VERIFIED', d['groups']['variant']['resolvedAllele']['verification'])
        self.assertEqual('VERIFIED', d['groups']['testing']['qualifiedForTarget']['verification'])
        self.assertEqual('UNVERIFIED', d['groups']['overlap']['independence']['verification'])
        self.assertEqual('INSUFFICIENT_DATA', row['readiness'])

    def test_existing_context_policy_enters_package_with_raw_knowledge(self):
        f = LocalFixture(self.root); p = policy(); p['literatureTerms'][0]['aliases'] = ['Synthetic trait']
        config = {'identity': {'synthetic': True}, 'contexts': [{'scope': p['scope'],
            'policy': f.put('policy.json', canonical(p)), 'sources': {'paper': f.put('knowledge.txt', b'fixed knowledge')}}]}
        identity = {'status': 'LOCAL_IDENTITY_VERIFIED', 'hgncId': 'HGNC:1', 'allele':
                    {'assembly': 'GRCh38', 'contig': '1', 'position': 100, 'reference': 'A', 'alternate': 'G'}}
        with patch('core_context_review.LocalIdentity.resolve', return_value=identity):
            result = self.run_package(config=config)
        row = result['cases'][0]; doc = parse((self.root / 'output' / row['path']).read_bytes())
        self.assertEqual('CONSISTENT_HIGH_HETEROGENEITY', doc['context']['phenotypeCategory']['value'])
        self.assertEqual('VERIFIED', doc['context']['phenotypeCategory']['verification'])
        self.assertEqual('MONDO:1', doc['context']['diseaseId']['value'])
        self.assertEqual(b'fixed knowledge', (self.root / 'output' / doc['sources']['knowledge-0']['path']).read_bytes())
        self.assertEqual('INSUFFICIENT_DATA', row['readiness'])

    def test_wrong_configured_policy_does_not_erase_verified_identity(self):
        f = LocalFixture(self.root); p = policy()
        config = {'identity': {'synthetic': True}, 'contexts': [{'scope': {**p['scope'], 'diseaseId': 'MONDO:WRONG'},
            'policy': f.put('policy.json', canonical(p)), 'sources': {'paper': f.put('knowledge.txt', b'fixed knowledge')}}]}
        identity = {'status': 'LOCAL_IDENTITY_VERIFIED', 'hgncId': 'HGNC:1', 'allele':
                    {'assembly': 'GRCh38', 'contig': '1', 'position': 100, 'reference': 'A', 'alternate': 'G'}}
        with patch('core_context_review.LocalIdentity.resolve', return_value=identity):
            result = self.run_package(config=config)
        row = result['cases'][0]; doc = parse((self.root / 'output' / row['path']).read_bytes())
        self.assertEqual('VERIFIED', doc['groups']['variant']['resolvedAllele']['verification'])
        self.assertEqual('UNVERIFIED', doc['context']['diseaseId']['verification'])
        self.assertTrue(any('BACKGROUND_MISMATCH' in i['code'] for i in row['issues']))

    def test_context_config_cannot_read_outside_local_or_wrong_pin(self):
        with self.assertRaises(ValueError): load_config(self.root, {'path': 'review/index.json', 'sha256': self.pin})
        local = self.root / 'local'; local.mkdir()
        p = local / 'context.json'; p.write_bytes(canonical({'schemaVersion': 'denovo-core-context-config-v1'}))
        with self.assertRaisesRegex(ValueError, 'PIN_MISMATCH'):
            load_config(self.root, {'path': 'local/context.json', 'sha256': '0' * 64})

    def test_missing_assay_and_family_are_not_resource_outages(self):
        self.assertEqual('DATA_MISSING', reason(ValueError('DENOVO_IDENTITY_TARGET_ASSAY_QUALIFICATION_UNRESOLVED'))['category'])
        self.assertEqual('REVIEW_REQUIRED', reason(ValueError('DENOVO_IDENTITY_FAMILY_COMPATIBILITY_UNRESOLVED'))['category'])
        self.assertEqual('SOFTWARE_UNSUPPORTED', reason(ValueError('DENOVO_IDENTITY_FAMILY_NARRATIVE_READER_UNSUPPORTED'))['category'])
        self.assertEqual('SOFTWARE_UNSUPPORTED', reason(ValueError('DENOVO_FAMILY_REVIEW_SCOPE_LIMIT'))['category'])
        self.assertEqual('DATA_MISSING', reason(ValueError('DENOVO_FAMILY_HISTORY_NOT_ESTABLISHED'))['category'])
        self.assertEqual('REVIEW_REQUIRED', reason(ValueError('DENOVO_FAMILY_HISTORY_CASE_ASSIGNMENT_UNRESOLVED'))['category'])
        self.assertEqual('REVIEW_REQUIRED', reason(ValueError('DENOVO_FAMILY_SPECIAL_OR_CONFLICT_REVIEW_REQUIRED'))['category'])
        self.assertEqual('KNOWLEDGE_MISSING', reason(ValueError('DENOVO_IDENTITY_APPLICABILITY_CONTEXT_NOT_CONFIGURED'))['category'])
        self.assertEqual('RESOURCE_UNAVAILABLE', reason(FileNotFoundError())['category'])


if __name__ == '__main__':
    unittest.main()
