import gzip
import tempfile
from copy import deepcopy
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import Mock, patch
import subprocess
from pm3_clinvar_supply import ClinVarSupply, vcv_endpoint
from pm3_partner_sources import PartnerSources
from model_literature_contract import digest

ALLELE={'assembly':'GRCh38','contig':'1','position':101,'reference':'G','alternate':'A'}
RAW=b'''<VariationArchive VariationID="123" Accession="VCV000000123" Version="1" RecordType="classified">
<RecordStatus>current</RecordStatus><Species>Homo sapiens</Species><ClassifiedRecord>
<SimpleAllele VariationID="123"><GeneList><Gene Symbol="TEST" HGNC_ID="HGNC:1"/></GeneList>
<Location><SequenceLocation Assembly="GRCh38" Chr="1" positionVCF="101" referenceAlleleVCF="G" alternateAlleleVCF="A"/></Location></SimpleAllele>
<ClinicalAssertionList><ClinicalAssertion><ClinVarAccession Accession="SCV000001" Version="1"/><RecordStatus>current</RecordStatus>
<Classification DateLastEvaluated="2025-01-01"><GermlineClassification>Pathogenic</GermlineClassification><ReviewStatus>reviewed by expert panel</ReviewStatus>
<Comment>ACMG/AMP criteria applied: PS3, PM2, PP3.</Comment></Classification>
<TraitSet><Trait><XRef DB="MONDO" ID="0000001"/></Trait></TraitSet></ClinicalAssertion></ClinicalAssertionList></ClassifiedRecord></VariationArchive>'''


class Supply(TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory(); self.addCleanup(folder.cleanup)
        self.root=Path(folder.name);(self.root/'local').mkdir()
        self.header=b'##fileformat=VCFv4.1\n##source=ClinVar\n##reference=GRCh38\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n'
        vcf=self.root/'local/source.gz';vcf.write_bytes(gzip.compress(self.header))
        (self.root/'local/source.gz.tbi').write_bytes(b'index');(self.root/'local/tabix').write_bytes(b'tool')
        self.spec={'vcf':{'path':'local/source.gz','size':vcf.stat().st_size,'mtimeNs':vcf.stat().st_mtime_ns},
                   'index':{'path':'local/source.gz.tbi','sha256':digest(b'index')},
                   'tabix':{'path':'local/tabix','sha256':digest(b'tool')},'headerSha256':digest(self.header)}
        self.rows=b'1\t101\t123\tG\tA\t.\t.\tGENEINFO=TEST:1;CLNSIG=Pathogenic\n'
        self.transport=Mock();self.transport.get_clinvar.return_value=RAW
        self.supply=ClinVarSupply(self.root,self.spec,self.root/'local/out',self.transport)
        self.process=patch('pm3_clinvar_supply.subprocess.run',side_effect=self.run_process).start()
        self.addCleanup(patch.stopall)
    def run_process(self,command,**kwargs):
        self.assertEqual('1:101-101',command[-1]);kwargs['stdout'].write(self.rows)
        return Mock(returncode=0)
    def test_exact_local_id_full_source_and_duplicate_reuse(self):
        sources=PartnerSources(self.root,{},self.supply)
        out=sources.lookup(ALLELE,'TEST')
        self.assertEqual('EXACT_SOURCE_ASSERTIONS_AVAILABLE',out['status'])
        self.assertEqual('Pathogenic',out['assertions'][0]['sourceClassification'])
        self.assertTrue(out['assertions'][0]['comments']);self.assertEqual('NOT_ESTABLISHED',out['independenceStatus'])
        self.assertEqual(out,sources.lookup(ALLELE,'TEST'));self.assertEqual(1,self.process.call_count)
        self.transport.get_clinvar.assert_called_once_with('VCV000000123')
    def test_missing_snapshot_record_is_not_global_database_absence(self):
        self.rows=self.rows.replace(b'\tA\t',b'\tT\t')
        result=self.supply.lookup(ALLELE,'TEST')
        self.assertEqual('NOT_FOUND_IN_LOCAL_SNAPSHOT',result['status']);self.transport.get_clinvar.assert_not_called()
    def test_wrong_gene_rejected_without_fetch(self):
        self.rows=self.rows.replace(b'TEST:1',b'OTHER:1')
        self.assertIn('PM3_CLINVAR_EXACT_GENE_MISMATCH_OR_AMBIGUOUS',self.supply.lookup(ALLELE,'TEST')['issues'])
        self.transport.get_clinvar.assert_not_called()
    def test_remote_wrong_allele_not_used(self):
        self.transport.get_clinvar.return_value=RAW.replace(b'positionVCF="101"',b'positionVCF="102"')
        out=PartnerSources(self.root,{},self.supply).lookup(ALLELE,'TEST')
        self.assertEqual('SOURCE_READ_INCOMPLETE',out['status']);self.assertEqual([],out['assertions'])
    def test_acquired_pin_failure_is_not_relabelled_as_missing_configuration(self):
        supplier=Mock();supplier.lookup.return_value={'status':'EXACT_RECORDS_ACQUIRED','issues':[],
            'records':[{'path':'local/missing.xml','sha256':'a'*64,'accession':'VCV000000123'}]}
        out=PartnerSources(self.root,{},supplier).lookup(ALLELE,'TEST')
        self.assertEqual('SOURCE_READ_INCOMPLETE',out['status'])
        self.assertEqual(['PM3_PARTNER_SOURCE_READ_FAILED'],out['issues'])
    def test_failure_is_memoized_not_retried_for_each_case(self):
        self.transport.get_clinvar.side_effect=ValueError('LITERATURE_SEARCH_RATE_LIMIT')
        for _ in range(3): self.assertEqual('SOURCE_READ_INCOMPLETE',self.supply.lookup(ALLELE,'TEST')['status'])
        self.assertEqual(1,self.transport.get_clinvar.call_count)
    def test_replaced_local_snapshot_no_query(self):
        self.spec['vcf']['mtimeNs']+=1
        self.assertIn('PM3_CLINVAR_LOCAL_SNAPSHOT_CHANGED',self.supply.lookup(ALLELE,'TEST')['issues'])
        self.process.assert_not_called()
    def test_indel_or_other_build_is_explicit_not_wrong_key(self):
        for change in ({'reference':'GA'},{'assembly':'GRCh37'}):
            self.assertIn('PM3_CLINVAR_SNV_SCOPE_REQUIRED',self.supply.lookup({**ALLELE,**change},'TEST')['issues'])
        self.process.assert_not_called()
    def test_local_timeout_never_fetches(self):
        self.process.side_effect=subprocess.TimeoutExpired('tabix',15)
        self.assertIn('PM3_CLINVAR_LOCAL_TIMEOUT',self.supply.lookup(ALLELE,'TEST')['issues'])
        self.transport.get_clinvar.assert_not_called()
    def test_identity_incomplete_never_triggers_acquisition(self):
        row={'status':'IDENTITY_INCOMPLETE','identities':{'partner':{'allele':ALLELE}},'reportedObservation':{'geneSymbol':'TEST'}}
        PartnerSources(self.root,{},self.supply).attach({'cases':[{'resolvedObservations':[row]}]})
        self.assertNotIn('partnerClassificationSources',row);self.process.assert_not_called()
    def test_endpoint_fixed_host_and_identity_validation(self):
        url,decode=vcv_endpoint('VCV000000123');self.assertEqual('VCV000000123.1',decode(RAW)['vcv'])
        self.assertTrue(url.startswith('https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?'))
        with self.assertRaises(ValueError):vcv_endpoint('https://evil.invalid')
        with self.assertRaises(ValueError):decode(RAW.replace(b'VCV000000123',b'VCV000000124'))

if __name__=='__main__':main()
