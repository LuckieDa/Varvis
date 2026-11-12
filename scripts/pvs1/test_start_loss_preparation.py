import copy
import json
from pathlib import Path
import tempfile
import unittest

from assemble_materials import canonical, sha
from build_transcript_model import reverse_complement
from start_loss_sequence import resolve, genomic_position, coding_intervals
from prepare_start_loss_materials import prepare
from start_loss_native_sources import NativeSources


def fixture(strand=1, cds='ATGAAACCCATGAAATAA', split=False):
    exons = [{'ordinal': 1, 'genomicStart': 100, 'genomicEnd': 99+len(cds), 'cdsStart': 1, 'cdsEnd': len(cds)}]
    if split:
        exons = [{'ordinal': 1, 'genomicStart': 100, 'genomicEnd': 101, 'cdsStart': 1, 'cdsEnd': 2},
                 {'ordinal': 2, 'genomicStart': 200, 'genomicEnd': 199+len(cds)-2, 'cdsStart': 3, 'cdsEnd': len(cds)}]
    model = {'assembly': 'GRCh38', 'contig': '1', 'hgncId': 'HGNC:123', 'geneSymbol': 'GENE_TEST',
             'transcript': 'ENST000001.1', 'strand': strand, 'cdsLength': len(cds), 'exons': exons}
    bases = {}
    for i, base in enumerate(cds, 1):
        bases[genomic_position(model, i)] = base if strand == 1 else reverse_complement(base)
    ctx = {'background': {'hgncId': model['hgncId'], 'mondoId': 'MONDO:0000001',
                           'moi': 'AUTOSOMAL_DOMINANT', 'transcript': model['transcript']},
           'allele': {'assembly': 'GRCh38', 'contig': '1', 'position': genomic_position(model, 1),
                      'reference': 'A' if strand == 1 else 'T', 'alternate': 'G' if strand == 1 else 'C'}}
    def read(contig, start, end):
        assert contig == '1'
        return ''.join(bases[i] for i in range(start, end+1))
    inv = {'identity': {'functionalCoverageComplete': False, 'coverage': 'INDEXED_CODING_TRANSCRIPTS_ONLY',
                       'source': {'url': 'https://www.gencodegenes.org/human/'}},
           'hgncId': 'HGNC:123', 'transcripts': [copy.deepcopy(model)]}
    return ctx, model, inv, read


def summary(**kwargs):
    row = {'gene': 'GENE_TEST', 'mondoId': 'MONDO:0000001', 'moi': 'Autosomal dominant inheritance',
           'hgvs': ['ENST000001.1:c.4A>T'], 'classification': 'Pathogenic', 'retracted': False,
           'approvedDate': '2020-01-01', 'publishedDate': '2020-01-02'}
    row.update(kwargs)
    return row


def source(rows):
    return {'sourceId': 'expert', 'sourceUrl': 'https://erepo.genome.network/evrepo/api/summary/classifications?columns=gene&matchTypes=exact&values=GENE_TEST',
            'raw': canonical({'data': rows})}


class SequencePreparationTest(unittest.TestCase):
    def test_each_start_base_both_strands_matches_independent_oracle(self):
        path = Path(__file__).resolve().parents[2] / 'varvis-server/src/test/resources/acmg/pvs1-start-loss-expectations-v1.json'
        oracle = json.loads(path.read_bytes())
        for case in oracle['cases'][:2]:
            strand = case['overrides']['strand']
            ctx, model, _, read = fixture(strand)
            for variant in case['variants']:
                with self.subTest(strand=strand, pos=variant['cdsPosition']):
                    ctx['allele'].update(position=genomic_position(model, variant['cdsPosition']),
                                         reference=variant['ref'], alternate=variant['alt'])
                    result = resolve(ctx, model, read)
                    self.assertEqual('AVAILABLE', result['status'])
                    self.assertEqual(variant['mutantCodon'], result['alternateCodon'])
                    self.assertEqual(10, result['candidateRestartCdsPosition'])
                    self.assertEqual(4, result['candidateRestartProteinPosition'])
                    self.assertFalse(result['scientificEvaluationPerformed'])

    def test_split_start_codon_and_interval_cross_exons(self):
        ctx, model, _, read = fixture(split=True)
        ctx['allele'].update(position=200, reference='G', alternate='A')
        result = resolve(ctx, model, read)
        self.assertEqual('ATA', result['alternateCodon'])
        self.assertEqual([100, 101, 200], result['startGenomicPositions'])
        self.assertEqual([(100, 101), (200, 206)], [(x['start'], x['end']) for x in result['upstreamIntervals']])

    def test_negative_strand_upstream_is_larger_genomic_coordinate(self):
        ctx, model, _, read = fixture(-1)
        result = resolve(ctx, model, read)
        self.assertEqual(117, ctx['allele']['position'])
        self.assertEqual([{'contig': '1', 'start': 109, 'end': 117, 'cdsStart': 1, 'cdsEnd': 9}], result['upstreamIntervals'])

    def test_out_of_frame_atg_is_not_a_restart(self):
        ctx, model, _, read = fixture(cds='ATGAATGAATAA')
        result = resolve(ctx, model, read)
        self.assertEqual('AVAILABLE', result['status'])
        self.assertIsNone(result['candidateRestartCdsPosition'])
        self.assertEqual([], result['upstreamIntervals'])

    def test_non_start_target_and_complex_allele_are_separate(self):
        ctx, model, _, read = fixture()
        ctx['allele'].update(position=103, reference='A', alternate='T')
        self.assertEqual('NOT_APPLICABLE', resolve(ctx, model, read)['status'])
        ctx['allele'].update(alternate='AT')
        self.assertEqual('UNSUPPORTED', resolve(ctx, model, read)['status'])

    def test_wrong_base_model_and_annotation_are_rejected(self):
        ctx, model, _, read = fixture()
        for change in ('base', 'model', 'annotation'):
            with self.subTest(change=change):
                c, m = copy.deepcopy(ctx), copy.deepcopy(model)
                annotation = None
                if change == 'base': c['allele']['reference'] = 'T'
                if change == 'model': m['transcript'] = 'ENST000001.2'
                if change == 'annotation': annotation = {'transcript': m['transcript'], 'hgvsc': m['transcript']+':c.2T>C', 'consequence': 'start_lost'}
                self.assertEqual('CONFLICT', resolve(c, m, read, annotation)['status'])

    def test_metadata_tag_does_not_change_sequence(self):
        ctx, model, _, read = fixture()
        annotation = {'transcript': model['transcript'], 'hgvsc': model['transcript']+':c.1A>G', 'consequence': 'start_lost&NMD_transcript_variant'}
        result = resolve(ctx, model, read, annotation)
        self.assertEqual('AVAILABLE', result['status'])
        self.assertEqual('AGREES', result['annotationAgreement'])
        self.assertNotIn('nmdPrediction', result)

    def test_incomplete_sequence_and_io_failure_do_not_become_no_candidate(self):
        ctx, model, _, _ = fixture()
        self.assertEqual('CONFLICT', resolve(ctx, model, lambda *args: 'N'*18)['status'])
        def unavailable(*args): raise OSError('unavailable')
        result = resolve(ctx, model, unavailable)
        self.assertEqual('UNRESOLVED', result['status'])
        self.assertEqual(['START_LOSS_REFERENCE_SOURCE_UNAVAILABLE'], result['issues'])
        self.assertNotIn('candidateRestartCdsPosition', result)

    def test_invalid_topology_and_nonstandard_start_rejected(self):
        ctx, model, _, read = fixture()
        model['exons'][0]['cdsEnd'] -= 1
        self.assertEqual('CONFLICT', resolve(ctx, model, read)['status'])
        ctx, model, _, read = fixture(cds='CTGAAATAA')
        ctx['allele'].update(reference='C', alternate='T')
        self.assertEqual('UNSUPPORTED', resolve(ctx, model, read)['status'])


class MaterialPreparationTest(unittest.TestCase):
    def test_native_structure_never_proves_functional_relevance(self):
        ctx, model, inv, read = fixture()
        other = copy.deepcopy(model); other['transcript'] = 'ENST000002.1'
        other['exons'][0].update(genomicStart=200, genomicEnd=217)
        inv['transcripts'].append(other)
        result = prepare(ctx, model, inv, read)
        review = result['material']['transcriptReview']
        self.assertEqual('UNRESOLVED', review['status'])
        self.assertFalse(review['complete'])
        self.assertTrue(review['findings'][1]['differentStart'])
        self.assertIsNone(review['findings'][1]['targetUnaffected'])
        self.assertIsNone(review['findings'][1]['functionallyRelevant'])

    def test_public_p_label_is_a_lead_not_a_qualified_reference(self):
        ctx, model, inv, read = fixture()
        result = prepare(ctx, model, inv, read, [source([summary()])])
        upstream = result['material']['upstreamReview']
        self.assertEqual('INCOMPLETE', upstream['status'])
        self.assertEqual('UNRESOLVED', upstream['records'][0]['eligibility'])
        self.assertEqual('PARTIAL', upstream['queries'][0]['status'])
        self.assertEqual(0, result['contractCheck']['declaredEligibleReferenceCount'])
        self.assertEqual(0, result['networkCalls'])

    def test_target_boundary_disease_and_lp_exclusions(self):
        ctx, model, inv, read = fixture()
        rows = [summary(hgvs=['ENST000001.1:c.1A>G']), summary(hgvs=['ENST000001.1:c.10A>G']),
                summary(mondoId='MONDO:0000002'), summary(classification='Likely Pathogenic')]
        result = prepare(ctx, model, inv, read, [source(rows)])
        self.assertEqual(['REFERENCE_IS_TARGET', 'REFERENCE_OUTSIDE_UPSTREAM_INTERVAL',
                          'REFERENCE_BACKGROUND_MISMATCH', 'REFERENCE_BASELINE_PATHOGENIC_REQUIRED'],
                         [r['reason'] for r in result['material']['upstreamReview']['records']])
        self.assertEqual(rows, json.loads(result['originals']['expert'])['data'])
        self.assertEqual('MONDO:0000002', result['material']['upstreamReview']['records'][2]['background']['mondoId'])

    def test_malformed_source_is_isolated_and_original_bytes_retained(self):
        ctx, model, inv, read = fixture()
        for raw in (b'{broken', b'\xff', b'[]', b'{"data":null}'):
            with self.subTest(raw=raw):
                snapshot = source([]); snapshot['raw'] = raw
                result = prepare(ctx, model, inv, read, [snapshot])
                self.assertEqual('AVAILABLE', result['preparation']['status'])
                self.assertEqual('INCOMPLETE', result['material']['upstreamReview']['status'])
                self.assertEqual('UNAVAILABLE', result['material']['upstreamReview']['queries'][0]['status'])
                retained = result['originals'].get('expert', result['rejectedOriginals'].get('expert'))
                self.assertEqual(raw, retained)

    def test_partial_search_cannot_be_declared_complete_negative(self):
        from start_loss_material_contract import validate
        ctx, model, inv, read = fixture()
        result = prepare(ctx, model, inv, read, [source([])])
        result['material']['upstreamReview'].update(status='NONE_IN_ASSESSED_SOURCES', issues=[])
        with self.assertRaisesRegex(ValueError, 'NEGATIVE_REFERENCE_SEARCH_INCOMPLETE'):
            validate(result['material'], ctx, result['originals'])

    def test_exact_transcript_version_and_reference_base_not_guessed(self):
        ctx, model, inv, read = fixture()
        result = prepare(ctx, model, inv, read, [source([summary(hgvs=['ENST000001.2:c.4A>T']),
                                                      summary(hgvs=['ENST000001.1:c.4G>T'])])])
        self.assertEqual([], result['material']['upstreamReview']['records'])
        self.assertEqual(2, len(result['referenceLeads']))

    def test_negative_strand_reference_maps_without_genomic_order_guess(self):
        ctx, model, inv, read = fixture(-1)
        result = prepare(ctx, model, inv, read, [source([summary()])])
        a = result['material']['upstreamReview']['records'][0]['allele']
        self.assertEqual((114, 'T', 'A'), (a['position'], a['reference'], a['alternate']))

    def test_empty_snapshot_and_source_failure_are_not_negative_searches(self):
        ctx, model, inv, read = fixture()
        for snapshot in (source([]), {'sourceId': 'offline', 'sourceUrl': 'https://erepo.genome.network/', 'reason': 'TIMEOUT'}):
            with self.subTest(snapshot=snapshot['sourceId']):
                result = prepare(ctx, model, inv, read, [snapshot])
                self.assertEqual('INCOMPLETE', result['material']['upstreamReview']['status'])

    def test_wrong_source_gene_scope_never_adopted(self):
        ctx, model, inv, read = fixture()
        wrong = source([summary()]); wrong['sourceUrl'] = wrong['sourceUrl'].replace('values=GENE_TEST', 'values=OTHER')
        result = prepare(ctx, model, inv, read, [wrong])
        self.assertEqual([], result['material']['upstreamReview']['records'])
        self.assertEqual('UNAVAILABLE', result['material']['upstreamReview']['queries'][0]['status'])

    def test_duplicate_rows_not_independent_and_originals_remain(self):
        ctx, model, inv, read = fixture()
        result = prepare(ctx, model, inv, read, [source([summary(), summary()])])
        self.assertEqual(1, len(result['material']['upstreamReview']['records']))
        self.assertEqual(2, len(json.loads(result['originals']['expert'])['data']))


class NativeInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); folder = self.root/'local'; folder.mkdir()
        _, model, _, _ = fixture()
        raw = canonical(model) + b'\n'; (folder/'data').write_bytes(raw)
        index = f"{model['transcript']}\t0\t{len(raw)}\t{sha(raw)}\n".encode()
        (folder/'index').write_bytes(index)
        manifest = {'schemaVersion':'gencode-transcript-index-v1', 'assembly':'GRCh38',
                    'index':{'sha256':sha(index)}, 'data':{'bytes':len(raw),'sha256':sha(raw)},
                    'counts':{'indexed':1},'source':{'url':'https://www.gencodegenes.org/'}}
        mr = canonical(manifest); (folder/'manifest').write_bytes(mr)
        self.config = {'gencodeManifest':{'path':'local/manifest','sha256':sha(mr)},
                       'gencodeIndex':{'path':'local/index','sha256':sha(index)}, 'gencodeData':'local/data'}

    def test_one_inventory_reuses_gene_rows_without_claiming_complete_functional_set(self):
        source = NativeSources(self.root, self.config, {'HGNC:123'})
        self.assertEqual(1, len(source.inventory('HGNC:123')['transcripts']))
        self.assertFalse(source.identity['functionalCoverageComplete'])
        self.assertEqual(1, source.identity['inventoryPasses'])
        self.assertEqual('ENST000001.1', source.model('HGNC:123','ENST000001.1')['transcript'])
        with self.assertRaisesRegex(ValueError,'EXACT_TRANSCRIPT_UNAVAILABLE'):
            source.model('HGNC:123','ENST000001.2')

    def test_same_size_changed_raw_is_not_accepted(self):
        file = self.root/'local/data'; file.write_bytes(file.read_bytes().replace(b'GENE_TEST', b'GENE_EVIL'))
        with self.assertRaisesRegex(ValueError,'ROW_INDEX_MISMATCH'):
            NativeSources(self.root,self.config,{'HGNC:123'})

    def test_change_after_load_invalidates_cached_inventory(self):
        source = NativeSources(self.root,self.config,{'HGNC:123'})
        file = self.root/'local/data'; file.write_bytes(file.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'DATA_CHANGED'):
            source.inventory('HGNC:123')


if __name__ == '__main__':
    unittest.main()
