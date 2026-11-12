"""Synthetic boundaries for full-source extraction; no clinical assertions."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

import prepare_reference_evidence as subject

ALLELE = dict(assembly='GRCh38', contig='1', position=101, reference='G', alternate='A')
XML = b'''<ClinVarResult-Set><VariationArchive VariationID="123" Accession="VCV000000123" Version="4" RecordType="classified">
<RecordStatus>current</RecordStatus><ClassifiedRecord><SimpleAllele VariationID="123">
<GeneList><Gene Symbol="TEST"/></GeneList><Location><SequenceLocation Assembly="GRCh38" Chr="1" positionVCF="101" referenceAlleleVCF="G" alternateAlleleVCF="A"/></Location>
<HGVSlist><HGVS Type="coding"><NucleotideExpression MANESelect="true"><Expression>NM_000001.1:c.1G&gt;A</Expression></NucleotideExpression><ProteinExpression><Expression>NP_000001.1:p.Gly1Arg</Expression></ProteinExpression></HGVS></HGVSlist>
</SimpleAllele><Classifications><GermlineClassification><ReviewStatus>reviewed by expert panel</ReviewStatus></GermlineClassification></Classifications>
<ClinicalAssertionList><ClinicalAssertion><ClinVarAccession Accession="SCV000001" Version="2" SubmitterName="Synthetic lab"/>
<RecordStatus>current</RecordStatus><Classification DateLastEvaluated="2020-01-02"><ReviewStatus>criteria provided, single submitter</ReviewStatus><GermlineClassification>Pathogenic</GermlineClassification><Comment>Fictional evidence mentions PS1; no independence inferred.</Comment><Citation><ID Source="PubMed">123</ID></Citation></Classification>
<AttributeSet><Attribute Type="AssertionMethod">Fictional criteria</Attribute><Citation><ID Source="PubMed">456</ID></Citation></AttributeSet>
<TraitSet><Trait><Name><ElementValue>fictional condition</ElementValue></Name><XRef DB="MedGen" ID="C123"/><Citation><ID Source="PubMed">789</ID></Citation></Trait></TraitSet>
<ObservedInList><ObservedIn><ObservedData><Attribute Type="Description">synthetic observation</Attribute></ObservedData><Citation><ID Source="PubMed">321</ID></Citation></ObservedIn></ObservedInList>
</ClinicalAssertion></ClinicalAssertionList></ClassifiedRecord></VariationArchive></ClinVarResult-Set>'''


class ReferenceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.original = dict(scv='SCV000001.2', classification='Pathogenic',
            reviewStatus='criteria provided, single submitter', dateLastEvaluated='Jan 2, 2020',
            geneSymbol='TEST', condition=dict(submittedRaw='MedGen:C123', reportedRaw='fictional condition'))
        self.draft = dict(schemaVersion='missense-reference-audit-draft-v1', draft=True,
            reviewStatus='UNREVIEWED', dataClass='SYNTHETIC', draftId='synthetic-draft', references=[dict(
                referenceDraftId='ref-1', variationId='123', transcript='ENST000001.1', allele=ALLELE,
                annotation=dict(proteinPosition=1), assertions=[dict(assertionDraftId='assertion-1',
                    sourceAssertion=self.original)])])

    def manifest(self, xml=XML):
        draft = json.dumps(self.draft).encode()
        (self.root / 'draft.json').write_bytes(draft)
        (self.root / 'source.xml').write_bytes(xml)
        return dict(schemaVersion='missense-full-reference-evidence-manifest-v1',
            referenceDraft=dict(path='draft.json', rawSha256=subject.sha(draft)), sources=[dict(
                variationId='123', path='source.xml', rawSha256=subject.sha(xml),
                url='https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&id=VCV000000123&rettype=vcv')])

    def run_manifest(self, manifest):
        raw = json.dumps(manifest).encode()
        path = self.root / 'input.json'
        path.write_bytes(raw)
        return subject.prepare(path, subject.sha(raw), self.root / 'output')

    def test_complete_evidence_keeps_original_and_does_not_approve(self):
        result = self.run_manifest(self.manifest())
        binding = result['references'][0]['assertionBindings'][0]
        self.assertEqual('EXACT_VERSION_BOUND', binding['sourceBindingStatus'])
        self.assertEqual(self.original, binding['sourceAssertion'])
        self.assertEqual('SYNTHETIC', result['dataClass'])
        self.assertEqual('UNREVIEWED', result['reviewStatus'])
        self.assertFalse(result['scientificAdoption'])
        self.assertEqual('NOT_EVALUATED', binding['independence'])
        self.assertEqual(XML, (self.root / 'output/source-000.xml').read_bytes())
        descriptor = json.loads((self.root / 'output/review-evidence-sources.json').read_text())['reviewEvidence'][0]
        self.assertEqual('BYTES', descriptor['format'])
        self.assertEqual('RAW_BYTES', descriptor['sourceDigestKind'])
        self.assertEqual(subject.sha(XML), descriptor['sourceRecord']['sha256'])
        self.assertEqual('VCV000000123.4', descriptor['sourceRecord']['version'])

    def test_citations_are_scoped_and_protein_transcript_pair_retained(self):
        parsed = subject.parse_vcv(XML, '123', ALLELE)
        assertion = parsed['assertions'][0]
        self.assertEqual({'CLASSIFICATION', 'OBSERVATION', 'CONDITION_BACKGROUND', 'ASSERTION_ATTRIBUTE_OR_METHOD'},
                         {c['scope'] for c in assertion['citations']})
        self.assertEqual(1, len(assertion['observations']))
        self.assertIn('NP_000001.1:p.Gly1Arg', json.dumps(parsed['hgvs']))
        self.assertIn('MANESelect', json.dumps(parsed['hgvs']))
        # Aggregate expert status must not relabel this SCV.
        self.assertEqual('criteria provided, single submitter', assertion['reviewStatus'])

    def test_wrong_allele_not_bound_even_with_correct_variation_id(self):
        for key, value in [('position', 102), ('reference', 'T'), ('alternate', 'C'), ('contig', '2')]:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'ALLELE_MISMATCH'):
                subject.parse_vcv(XML, '123', dict(ALLELE, **{key: value}))

    def test_other_assembly_or_complex_allele_refused(self):
        for replacement in [dict(assembly='GRCh37'), dict(reference='GG'), dict(position=True)]:
            with self.subTest(replacement=replacement), self.assertRaisesRegex(ValueError, 'SNV_SCOPE'):
                subject.parse_vcv(XML, '123', dict(ALLELE, **replacement))

    def test_wrong_id_accession_or_missing_version_refused(self):
        for old, new, reason in [(b'VariationID="123"', b'VariationID="999"', 'ID_MISMATCH'),
                (b'Accession="VCV000000123"', b'Accession="VCV000000999"', 'ACCESSION_MISMATCH'),
                (b' Version="4"', b'', 'VERSION_REQUIRED')]:
            with self.subTest(reason=reason), self.assertRaisesRegex(ValueError, reason):
                subject.parse_vcv(XML.replace(old, new, 1), '123', ALLELE)

    def test_new_scv_version_not_substituted(self):
        result = self.run_manifest(self.manifest(XML.replace(b'Version="2"', b'Version="3"')))
        binding = result['references'][0]['assertionBindings'][0]
        self.assertEqual('PARTIAL', result['status'])
        self.assertEqual(['EXACT_SCV_VERSION_NOT_PRESENT'], binding['reasons'])
        self.assertIsNone(binding['fullAssertion'])
        self.assertEqual(['SCV000001.3'], binding['availableSameAccessionVersions'])
        self.assertEqual('SCV000001.2', binding['sourceAssertion']['scv'])

    def test_inconsistent_scv_metadata_not_bound(self):
        parsed = subject.parse_vcv(XML, '123', ALLELE)
        for field, value in [('classification', 'Benign'), ('reviewStatus', 'reviewed by expert panel'),
                             ('dateLastEvaluated', 'Jan 3, 2020'), ('geneSymbol', 'OTHER')]:
            with self.subTest(field=field):
                reasons = subject.bind_assertion(dict(self.original, **{field: value}),
                    parsed['assertions'][0], parsed['recordStatus'], parsed['geneSymbols'])
                self.assertEqual(1, len(reasons))

    def test_withdrawn_scv_or_archive_not_current(self):
        result = self.run_manifest(self.manifest(XML.replace(b'>current<', b'>deleted<')))
        self.assertEqual(['SOURCE_RECORD_NOT_CURRENT'], result['references'][0]['assertionBindings'][0]['reasons'])

    def test_duplicate_scv_and_vcv_refused(self):
        root = ET.fromstring(XML)
        assertions = root.find('.//ClinicalAssertionList')
        assertions.append(copy.deepcopy(assertions[0]))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_SCV'):
            subject.parse_vcv(ET.tostring(root), '123', ALLELE)
        root = ET.fromstring(XML)
        root.append(copy.deepcopy(root[0]))
        with self.assertRaisesRegex(ValueError, 'ONE_VCV'):
            subject.parse_vcv(ET.tostring(root), '123', ALLELE)

    def test_haplotype_not_treated_as_single_variant(self):
        with self.assertRaisesRegex(ValueError, 'SINGLE_ALLELE'):
            subject.parse_vcv(XML.replace(b'<ClassifiedRecord>', b'<ClassifiedRecord><Haplotype/>'), '123', ALLELE)

    def test_empty_http_success_not_called_no_record(self):
        with self.assertRaisesRegex(ValueError, 'ONE_VCV'):
            subject.parse_vcv(b'<ClinVarResult-Set><set/></ClinVarResult-Set>', '123', ALLELE)

    def test_entities_and_dtd_refused(self):
        for prefix in [b'<!DOCTYPE x>', b'<!ENTITY x "y">']:
            with self.subTest(prefix=prefix), self.assertRaisesRegex(ValueError, 'DTD_OR_ENTITY'):
                subject.parse_vcv(prefix + XML, '123', ALLELE)

    def test_invalid_dates_do_not_roll_over(self):
        for value in ['Feb 30, 2020', '2020-02-30', '01/02/2020', None]:
            with self.subTest(value=value), self.assertRaises((ValueError, TypeError)):
                subject.iso_date(value)

    def test_pinned_source_bytes_required(self):
        manifest = self.manifest()
        (self.root / 'source.xml').write_bytes(XML + b' ')
        with self.assertRaisesRegex(ValueError, 'SHA256_MISMATCH'):
            self.run_manifest(manifest)

    def test_output_cannot_be_overwritten(self):
        manifest = self.manifest()
        self.run_manifest(manifest)
        with self.assertRaisesRegex(ValueError, 'REFUSE_OVERWRITE'):
            self.run_manifest(manifest)

    def test_missing_source_is_explicit_partial(self):
        manifest = self.manifest()
        manifest['sources'] = []
        result = self.run_manifest(manifest)
        self.assertEqual('PARTIAL', result['status'])
        self.assertEqual(['FULL_SOURCE_NOT_PROVIDED'], result['references'][0]['assertionBindings'][0]['reasons'])

    def test_unrelated_or_duplicate_source_refused(self):
        manifest = self.manifest()
        manifest['sources'].append(copy.deepcopy(manifest['sources'][0]))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_SOURCE'):
            self.run_manifest(manifest)
        manifest = self.manifest()
        manifest['sources'][0]['variationId'] = '999'
        with self.assertRaisesRegex(ValueError, 'UNBOUND_SOURCE'):
            self.run_manifest(manifest)

    def test_source_url_identity_and_explicit_version_checked(self):
        for url in ['https://example.org/x',
                    'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&rettype=vcv&id=VCV000000999',
                    'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&rettype=vcv&id=VCV000000123.2']:
            with self.subTest(url=url):
                manifest = self.manifest()
                manifest['sources'][0]['url'] = url
                with self.assertRaises(ValueError):
                    self.run_manifest(manifest)

    def test_oversize_and_duplicate_json_refused(self):
        with self.assertRaisesRegex(ValueError, 'SIZE_LIMIT'):
            subject.parse_vcv(b' ' * (subject.MAX_FILE + 1), '123', ALLELE)
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_KEY'):
            subject.parse_json(b'{"x":1,"x":2}')

    def test_annotation_and_draft_scv_unchanged(self):
        before = copy.deepcopy(self.draft)
        result = self.run_manifest(self.manifest())
        self.assertEqual(before, self.draft)
        self.assertEqual(before['references'][0]['annotation'], result['references'][0]['originalAnnotation'])

    def test_new_conflicting_scv_is_visible_but_not_attached_to_old_assertion(self):
        root = ET.fromstring(XML)
        assertions = root.find('.//ClinicalAssertionList')
        negative = copy.deepcopy(assertions[0])
        negative.find('ClinVarAccession').set('Accession', 'SCV000002')
        negative.find('Classification/GermlineClassification').text = 'Benign'
        assertions.append(negative)
        result = self.run_manifest(self.manifest(ET.tostring(root)))
        ref = result['references'][0]
        self.assertEqual(2, len(ref['fullSource']['assertions']))
        self.assertEqual('Benign', ref['fullSource']['assertions'][1]['classification'])
        self.assertEqual(1, len(ref['assertionBindings']))
        self.assertEqual('NOT_EVALUATED', ref['assertionBindings'][0]['independence'])

    def test_scv_submission_identity_and_direct_archive_root_preserved(self):
        root = ET.fromstring(XML)
        statement = root.find('.//ClinicalAssertion')
        ET.SubElement(statement, 'ClinVarSubmissionID', dict(localKey='synthetic-expert-uuid', submittedAssembly='GRCh37'))
        parsed = subject.parse_vcv(ET.tostring(root[0]), '123', ALLELE)
        assertion = parsed['assertions'][0]
        self.assertEqual('synthetic-expert-uuid', assertion['submissionIdentity'][0]['attributes']['localKey'])
        self.assertTrue(assertion['xpath'].startswith('/VariationArchive[1]/'))


if __name__ == '__main__':
    unittest.main()
