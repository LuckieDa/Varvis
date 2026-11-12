import tempfile
from pathlib import Path
import unittest
from pm3_partner_sources import PartnerSources
from model_literature_contract import digest

class Sources(unittest.TestCase):
    def test_exact_source_not_label_based_qualification(self):
        raw=b'''<VariationArchive VariationID="123" Accession="VCV000000123" Version="1" RecordType="classified">
        <RecordStatus>current</RecordStatus><Species>Homo sapiens</Species><ClassifiedRecord>
        <SimpleAllele VariationID="123"><GeneList><Gene Symbol="TEST" HGNC_ID="HGNC:1"/></GeneList>
        <Location><SequenceLocation Assembly="GRCh38" Chr="1" positionVCF="101" referenceAlleleVCF="G" alternateAlleleVCF="A"/></Location></SimpleAllele>
        <ClinicalAssertionList><ClinicalAssertion><ClinVarAccession Accession="SCV000001" Version="1"/><RecordStatus>current</RecordStatus>
        <Classification DateLastEvaluated="2025-01-01"><GermlineClassification>Pathogenic</GermlineClassification><ReviewStatus>reviewed by expert panel</ReviewStatus>
        <Comment>ACMG/AMP criteria applied: PS3, PM3, PP3.</Comment></Classification>
        <TraitSet><Trait><XRef DB="MONDO" ID="0000001"/></Trait></TraitSet></ClinicalAssertion></ClinicalAssertionList></ClassifiedRecord></VariationArchive>'''
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'local').mkdir();source=root/'local/source.xml';source.write_bytes(raw)
            spec={'path':'local/source.xml','sha256':digest(raw),'accession':'VCV000000123'}
            supply=PartnerSources(root,{'clinvar':[spec]})
            allele={'assembly':'GRCh38','contig':'1','position':101,'reference':'G','alternate':'A'}
            result=supply.lookup(allele,'TEST');self.assertEqual('EXACT_SOURCE_ASSERTIONS_AVAILABLE',result['status'])
            row=result['assertions'][0];self.assertEqual('Pathogenic',row['sourceClassification'])
            self.assertEqual('SCV000001',row['sourceAccession']['Accession'])
            self.assertEqual('1',row['sourceAccession']['Version'])
            self.assertEqual('COMPARISON_EVIDENCE_REQUIRES_TARGET_CHECK',row['dependencyAssessment'])
            self.assertEqual('NOT_ESTABLISHED',row['independenceStatus']);self.assertTrue(row['sourceLocator'])
            self.assertEqual('NOT_FOUND_IN_CONFIGURED_RECORDS',supply.lookup({**allele,'position':102},'TEST')['status'])
            self.assertEqual('NOT_FOUND_IN_CONFIGURED_RECORDS',supply.lookup(allele,'OTHER')['status'])
            source.write_bytes(raw+b' ')
            bad=PartnerSources(root,{'clinvar':[spec]}).lookup(allele,'TEST')
            self.assertEqual('SOURCE_READ_INCOMPLETE',bad['status']);self.assertFalse(bad['assertions'])

if __name__=='__main__':unittest.main()
