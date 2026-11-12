"""Synthetic transport controls; no clinical or reviewed source is created."""
import base64
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from test_prepare_expert_reference_evidence import CV, UUID

import pp2_other_phenotype_workflow as workflow
from pp2_discovery_scope import FrozenTransport, canonical
from literature_search_transport import endpoint
from prepare_literature import sha


def blob(raw):
    return dict(rawSha256=sha(raw), bytes=len(raw), base64=base64.b64encode(raw).decode('ascii'))


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.cache = self.root / 'cache'
        self.cache.mkdir()

    def packet(self):
        raw = canonical(dict(status=dict(code=200), data=[]))
        page = dict(status='AVAILABLE', sha256=sha(raw), page=1,
            sourceUrl='https://erepo.genome.network/evrepo/api/summary/classifications'
                      '?columns=gene&matchTypes=exact&values=EXAMPLE&pgSize=100&pg=1')
        gene = dict(geneSymbol='EXAMPLE', sourceEnumerationComplete=True,
                    pages=[dict(page, **{'pass': 1}), dict(page, **{'pass': 2})])
        target = dict(targetId='a-target', candidateId='candidate', hypothesisId='HCM',
            genomicAllele=dict(assembly='GRCh38', contig='1', position=101, reference='G', alternate='A'),
            geneSymbol='EXAMPLE', hgncId='HGNC:123', mondoId='MONDO:0005045', moi='AD',
            formalCandidate=dict(transcriptAnnotations=[dict(geneSymbol='EXAMPLE',
                versionedTranscriptId='NM_123.1', hgvsCoding='NM_123.1:c.101G>A', hgvsProtein='p.T41A')]))
        return dict(schemaVersion='pp2-other-phenotype-auto-input-v1', originals={sha(raw): blob(raw)},
                    targets=[target], nativeGenes=[gene], nativeRecords=[])

    def transport(self, responses):
        cache = self.cache
        class Transport(FrozenTransport):
            def __init__(self, *unused):
                super().__init__(responses)
            def __enter__(self):
                return self
            def __exit__(self, *unused):
                return None
            def _get(self, source, query, cursor):
                raw = super()._get(source, query, cursor)
                (cache / (sha(raw) + '.body')).write_bytes(raw)
                return raw
        return Transport

    def empty_responses(self):
        return {endpoint('litvar', 'EXAMPLE ' + term, 'autocomplete')[0]: b'[]'
                for term in ('NM_123.1:c.101G>A', 'p.T41A')}

    def test_actual_empty_native_and_alias_queries_only_close_their_declared_operational_scopes(self):
        out = workflow.observe(self.packet(), self.root, self.cache,
                               transport_factory=self.transport(self.empty_responses()))
        self.assertEqual('COMPLETE', out['nativeCoverage'][0]['status'])
        scope = out['discoveryScopes'][0]
        self.assertEqual('COMPLETE', scope['status'])
        self.assertEqual('PREPARED_TRANSCRIPT_VERIFICATION_REQUIRED', scope['aliasIdentity'])
        self.assertFalse(scope['retrieval']['scientificAdoption'])
        self.assertNotIn('outcome', out)
        self.assertFalse(out['scientificAdoption'])
        self.assertEqual([], out['expertScopes'])
        self.assertTrue(all(sha(base64.b64decode(v['base64'])) == pin
                            for pin, v in out['observedOriginals'].items()))

    def test_incomplete_native_enumeration_cannot_close_even_with_zero_queries(self):
        packet = self.packet()
        packet['nativeGenes'][0]['sourceEnumerationComplete'] = False
        out = workflow.observe(packet, self.root, self.cache,
                               transport_factory=self.transport(self.empty_responses()))
        self.assertEqual('INCOMPLETE', out['nativeCoverage'][0]['status'])
        self.assertEqual('COMPLETE', out['discoveryScopes'][0]['status'])
        self.assertTrue(out['nativeCoverage'][0]['pending'])

    def test_two_pass_originals_not_transport_claims_establish_native_coverage(self):
        for change in ('mismatch', 'gap', 'wrong-url', 'missing-original'):
            with self.subTest(change=change):
                packet = self.packet()
                if change == 'mismatch':
                    raw = canonical(dict(status=dict(code=200), data=[dict(uuid='00000000-0000-0000-0000-000000000001', gene='EXAMPLE')]))
                    packet['originals'][sha(raw)] = blob(raw)
                    packet['nativeGenes'][0]['pages'][1]['sha256'] = sha(raw)
                elif change == 'gap':
                    packet['nativeGenes'][0]['pages'][1]['page'] = 2
                elif change == 'wrong-url':
                    packet['nativeGenes'][0]['pages'][1]['sourceUrl'] += '&other=true'
                else:
                    packet['originals'] = {}
                unused, coverage, unused2 = workflow.native_read(packet, {
                    k: base64.b64decode(v['base64']) for k, v in packet['originals'].items()})
                self.assertEqual('INCOMPLETE', coverage[0]['status'])

    def test_only_exact_grch38_accession_and_one_atomic_snv_can_exclude_a_summary_row(self):
        row = dict(hgvs=['NC_000001.10:g.101G>A', 'NC_000001.11:g.102G>A'])
        self.assertEqual(102, workflow.summary_allele(row)['position'])
        for expressions in (['NC_000001.10:g.101G>A'], ['NC_000001.11:g.101del'],
                            ['NC_000001.11:g.101G>A', 'NC_000001.11:g.102G>A'],
                            ['NC_000001.11:gX101G>A'], []):
            with self.subTest(expressions=expressions), self.assertRaises(ValueError):
                workflow.summary_allele(dict(hgvs=expressions))

    def test_non_target_summary_is_terminal_only_from_its_actual_allele(self):
        packet = self.packet()
        row = dict(uuid='00000000-0000-0000-0000-000000000001', gene='EXAMPLE',
                   hgvs=['NC_000001.11:g.102G>A'])
        raw = canonical(dict(status=dict(code=200), data=[row]))
        packet['originals'] = {sha(raw): blob(raw)}
        for page in packet['nativeGenes'][0]['pages']:
            page['sha256'] = sha(raw)
        unused, coverage, unused2 = workflow.native_read(packet, {sha(raw): raw})
        self.assertEqual('COMPLETE', coverage[0]['status'])
        self.assertEqual('EXCLUDED_PRECISE_NON_TARGET_ALLELE', coverage[0]['rows'][0]['status'])
        row['hgvs'] = ['NC_000001.10:g.101G>A']
        raw = canonical(dict(status=dict(code=200), data=[row]))
        for page in packet['nativeGenes'][0]['pages']:
            page['sha256'] = sha(raw)
        unused, coverage, unused2 = workflow.native_read(packet, {sha(raw): raw})
        self.assertEqual('INCOMPLETE', coverage[0]['status'])

    def test_missing_alias_response_is_precise_unknown_not_complete(self):
        responses = self.empty_responses()
        del responses[next(iter(responses))]
        out = workflow.observe(self.packet(), self.root, self.cache,
                               transport_factory=self.transport(responses))
        self.assertEqual('INCOMPLETE', out['discoveryScopes'][0]['status'])
        self.assertTrue(out['discoveryScopes'][0]['retrieval']['remaining'])

    def test_foreign_gene_hints_do_not_generate_an_alias_plan(self):
        packet = self.packet()
        packet['targets'][0]['formalCandidate']['transcriptAnnotations'][0]['geneSymbol'] = 'OTHER'
        out = workflow.observe(packet, self.root, self.cache,
                               transport_factory=self.transport(self.empty_responses()))
        self.assertEqual('INCOMPLETE', out['discoveryScopes'][0]['status'])
        self.assertEqual('PP2_AUTO_TARGET_ALIASES_MISSING_OR_TECHNICAL_LIMIT', out['discoveryScopes'][0]['reasonCode'])

    def test_original_sha_mismatch_rejected_without_any_source_or_query_qualification(self):
        packet = self.packet()
        packet['originals'][next(iter(packet['originals']))]['base64'] = base64.b64encode(b'{}').decode()
        with self.assertRaises(ValueError):
            workflow.observe(packet, self.root, self.cache,
                             transport_factory=self.transport(self.empty_responses()))

    def native_packet(self):
        # Existing synthetic parser-boundary bytes, extended with literal
        # technical HGVS metadata. No reviewed format or real case is created.
        packet = self.packet()
        xml = (CV.replace(b'<ClassifiedRecord>', b'<Species>Homo sapiens</Species><ClassifiedRecord>', 1)
            .replace(b'<Gene Symbol="TEST"/>', b'<Gene Symbol="EXAMPLE" HGNC_ID="HGNC:123"/>')
            .replace(b'<NucleotideExpression MANESelect="true">',
                     b'<NucleotideExpression MANESelect="true" sequenceAccessionVersion="NM_000001.1" change="c.1G&gt;A">')
            .replace(b'<ProteinExpression>', b'<ProteinExpression sequenceAccessionVersion="NP_000001.1" change="p.Gly1Arg">'))
        row = dict(uuid=UUID, docVersion='1.0.0', cvId='123', gene='EXAMPLE',
                   hgvs=['NC_000001.11:g.101G>A'], classification='Pathogenic',
                   mondoId='MONDO:0000001', retracted=False, condition='fictional condition')
        raw = canonical(dict(status=dict(code=200), data=[row]))
        packet['originals'] = {sha(raw): blob(raw), sha(xml): blob(xml)}
        for page in packet['nativeGenes'][0]['pages']:
            page['sha256'] = sha(raw)
        packet['nativeRecords'] = [dict(variationId='123', rawSha256=sha(xml), status='AVAILABLE',
            sourceUrl='https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&rettype=vcv&id=VCV000000123')]
        return packet, xml

    def test_known_native_citations_remain_pending_when_every_litvar_alias_returns_empty(self):
        packet, unused = self.native_packet()
        originals = {pin: base64.b64decode(value['base64']) for pin, value in packet['originals'].items()}
        scopes, coverage, aliases = workflow.native_read(packet, originals)
        self.assertTrue(scopes)
        self.assertEqual('COMPLETE', coverage[0]['status'])
        signals = workflow.search_signals(packet['targets'][0], aliases['a-target'])
        responses = {endpoint('litvar', 'EXAMPLE ' + term, 'autocomplete')[0]: b'[]'
                     for term in signals['variantAliases']}
        out = workflow.observe(packet, self.root, self.cache, transport_factory=self.transport(responses))
        scope = out['discoveryScopes'][0]
        self.assertEqual('COMPLETE', scope['retrieval']['status'])
        self.assertEqual([], scope['retrieval']['candidates'])
        self.assertEqual('RELATIONSHIP_REVIEW_PENDING', scope['status'])
        self.assertTrue(any(r['pmid'] == '321' for r in scope['knownCitationReferences']))
        self.assertTrue(all(r['sourceRawSha256'] in out['observedOriginals'] for r in scope['knownCitationReferences']))
        self.assertFalse(out['scientificAdoption'])

    def test_non_current_top_level_vcv_cannot_complete_native_scope(self):
        packet, xml = self.native_packet()
        old = xml.replace(b'<RecordStatus>current</RecordStatus>', b'<RecordStatus>replaced</RecordStatus>', 1)
        packet['originals'][sha(old)] = blob(old)
        packet['nativeRecords'][0]['rawSha256'] = sha(old)
        unused, coverage, aliases = workflow.native_read(packet, {
            pin: base64.b64decode(value['base64']) for pin, value in packet['originals'].items()})
        self.assertEqual('INCOMPLETE', coverage[0]['status'])
        self.assertTrue(any(g['reasonCode'] == 'PP2_AUTO_NATIVE_VCV_NOT_CURRENT' for g in coverage[0]['pending']))
        self.assertFalse(aliases)

    def test_partial_traversal_preserves_independent_native_support_but_cannot_clear(self):
        packet, unused = self.native_packet()
        packet['nativeGenes'][0]['sourceEnumerationComplete'] = False
        packet['nativeGenes'][0]['pages'][1] = dict(status='UNAVAILABLE', **{'pass': 2}, page=1)
        scopes, coverage, unused = workflow.native_read(packet, {
            pin: base64.b64decode(value['base64']) for pin, value in packet['originals'].items()})
        self.assertTrue(scopes)
        self.assertEqual('INCOMPLETE', coverage[0]['status'])
        self.assertTrue(coverage[0]['pending'])

    def test_native_owner_refuses_out_of_scope_alleles_even_when_its_gene_enumeration_is_empty(self):
        for changes in (dict(assembly='GRCh37'), dict(reference='GA'), dict(alternate=''),
                        dict(contig='MT'), dict(position=True), dict(reference='G', alternate='G')):
            with self.subTest(changes=changes):
                packet = self.packet()
                packet['targets'][0]['genomicAllele'].update(changes)
                scopes, coverage, aliases = workflow.native_read(packet, {
                    pin: base64.b64decode(value['base64']) for pin, value in packet['originals'].items()})
                self.assertEqual('SOFTWARE_UNSUPPORTED', coverage[0]['status'])
                self.assertEqual(['PP2_AUTO_NATIVE_GRCH38_SNV_SCOPE_UNSUPPORTED'],
                                 [p['reasonCode'] for p in coverage[0]['pending']])
                self.assertFalse(scopes)
                self.assertFalse(aliases)

    def test_native_failure_retains_actual_transport_cause_without_leaking_paths_or_diagnosing_patient(self):
        packet = self.packet()
        packet['nativeGenes'][0]['sourceEnumerationComplete'] = False
        packet['nativeGenes'][0]['coverageReason'] = 'EREPO_GENE_PAGE_UNAVAILABLE_OR_INVALID'
        packet['nativeGenes'][0]['pages'][0] = dict(status='UNAVAILABLE', **{'pass': 1}, page=1,
            reasonCode='PUBLIC_NETWORK_DISABLED')
        unused, coverage, unused2 = workflow.native_read(packet, {
            pin: base64.b64decode(value['base64']) for pin, value in packet['originals'].items()})
        codes = {p['reasonCode'] for p in coverage[0]['pending']}
        self.assertIn('PUBLIC_NETWORK_DISABLED', codes)
        self.assertIn('EREPO_GENE_PAGE_UNAVAILABLE_OR_INVALID', codes)
        self.assertEqual('INCOMPLETE', coverage[0]['status'])


if __name__ == '__main__':
    unittest.main()
