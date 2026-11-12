"""Synthetic identities and sources only; no clinical approval in these fixtures."""
import hashlib
import unittest
import xml.etree.ElementTree as ET
from prepare_literature import prepare, verify_citation
from extract_literature_cases import extract
from literature_allele_identity import read_record, resolve, MAX_RECORD_BYTES
from qualify_literature_cases import qualify

PMCID = "PMC999999997"
PMID = "999999997"
VCV = "VCV999999997"
ARTICLE = '''<article><front><article-meta><article-id pub-id-type="pmcid">PMC999999997</article-id>
<article-id pub-id-type="pmid">999999997</article-id><title-group><article-title>SYNTHETIC only</article-title></title-group>
</article-meta></front><body><sec><title>Results</title>
<table-wrap><table><tr><th></th><th>Case A</th></tr><tr><td>Gene</td><td>EXAMPLE</td></tr>
<tr><td>Nucleotide</td><td>123A&gt;G</td></tr><tr><td>Protein</td><td>T41A</td></tr>
<tr><td>Main clinical features</td><td>Synthetic trait</td></tr><tr><td>Family history</td><td>None</td></tr></table></table-wrap>
<sec><title>Case A: T41A in EXAMPLE</title><p>The EXAMPLE variant c.123A&gt;G, p.T41A, was observed.
Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.
<fig><caption><p>(A) Other panel. (B) Sanger sequencing of the patient and parents to show that the heterozygous mutation is de novo.</p></caption></fig>
SNP analysis confirmed both maternity and paternity.</p></sec></sec></body></article>'''.encode()
RECORD = '''<ClinVarResult-Set><VariationArchive VariationID="999999997" Accession="VCV999999997" Version="1">
<RecordStatus>current</RecordStatus><Species>Homo sapiens</Species><ClassifiedRecord>
<SimpleAllele VariationID="999999997"><GeneList><Gene Symbol="EXAMPLE" HGNC_ID="HGNC:999999997">
<Location><SequenceLocation Assembly="GRCh38" Chr="2" positionVCF="999" referenceAlleleVCF="G" alternateAlleleVCF="T"/></Location>
</Gene></GeneList><Location><SequenceLocation Assembly="GRCh38" Chr="1" positionVCF="123" referenceAlleleVCF="A" alternateAlleleVCF="G"/></Location>
<HGVSlist><HGVS Type="coding"><NucleotideExpression sequenceAccessionVersion="NM_999999997.1" change="c.123A&gt;G">
<Expression>NM_999999997.1:c.123A&gt;G</Expression></NucleotideExpression>
<ProteinExpression sequenceAccessionVersion="NP_999999997.1" change="p.Thr41Ala"><Expression>NP_999999997.1:p.Thr41Ala</Expression></ProteinExpression>
</HGVS></HGVSlist></SimpleAllele><Classifications><GermlineClassification><Description>Uncertain significance</Description>
<Citation><ID Source="PubMed">999999997</ID></Citation></GermlineClassification></Classifications>
</ClassifiedRecord></VariationArchive></ClinVarResult-Set>'''.encode()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def record(raw=RECORD):
    return read_record(raw, sha(raw), VCV)


def run(raw=ARTICLE, vcv=RECORD):
    return qualify(raw, PMCID, sha(raw), [(vcv, sha(vcv), VCV)])


def case(raw=ARTICLE):
    return extract(prepare(raw, PMCID, sha(raw)))["cases"][0]


class LiteratureAlleleIdentityTest(unittest.TestCase):
    def test_paired_alias_uses_allele_not_gene_location_and_not_classification(self):
        result = resolve(case(), PMID, [record()])
        self.assertEqual("ALIAS_RESOLVED_REFERENCE_CHECK_PENDING", result["status"])
        self.assertEqual({"assembly": "GRCh38", "contig": "1", "position": 123, "reference": "A", "alternate": "G"}, result["allele"])
        self.assertNotIn("classification", result)
        changed = resolve(case(), PMID, [record(RECORD.replace(b'Uncertain significance', b'Benign'))])
        for key in ('status', 'allele', 'hgncId', 'issues'):
            self.assertEqual(result[key], changed[key])
        self.assertNotEqual(result['matches'][0]['sourceSha256'], changed['matches'][0]['sourceSha256'])

    def test_wrong_gene_coding_protein_and_pmid_are_not_matches(self):
        for before, after in [(b'EXAMPLE', b'OTHER'), (b'123A&gt;G', b'124A&gt;G'), (b'Thr41Ala', b'Thr42Ala'),
                              (b'>999999997</ID>', b'>111</ID>')]:
            with self.subTest(after=after):
                self.assertEqual("LITERATURE_ALLELE_ALIAS_NOT_FOUND", resolve(case(), PMID, [record(RECORD.replace(before, after))])["issues"][0])

    def test_no_cross_pair_hgvs_join(self):
        root = ET.fromstring(RECORD)
        hgvslist = root.find('.//HGVSlist')
        first = hgvslist[0]
        second = ET.fromstring(ET.tostring(first))
        for n in first.findall('ProteinExpression'):
            n.set('change', 'p.Thr42Ala'); n.find('Expression').text = 'NP_999999997.1:p.Thr42Ala'
        for n in second.findall('NucleotideExpression'):
            n.set('change', 'c.124A>G'); n.find('Expression').text = 'NM_999999997.1:c.124A>G'
        hgvslist.append(second)
        self.assertEqual('UNRESOLVED', resolve(case(), PMID, [record(ET.tostring(root))])['status'])

    def test_exact_transcript_version_is_respected(self):
        for tx, expected in [('NM_999999997.1', 'ALIAS_RESOLVED_REFERENCE_CHECK_PENDING'), ('NM_999999997.2', 'UNRESOLVED'),
                             ('NM_999999997', 'UNRESOLVED')]:
            raw = ARTICLE.replace(b'<td>123A&gt;G</td>', ('<td>' + tx + ':c.123A&gt;G</td>').encode())
            self.assertEqual(expected, resolve(case(raw), PMID, [record()])['status'])

    def test_background_pmid_cannot_link_variant_to_paper(self):
        raw = RECORD.replace(b'<Citation>', b'<TraitSet><Trait><Citation>').replace(b'</Citation>', b'</Citation></Trait></TraitSet>')
        self.assertEqual('UNRESOLVED', resolve(case(), PMID, [record(raw)])['status'])

    def test_multiple_matching_alleles_rejected(self):
        other = RECORD.replace(b'positionVCF="123"', b'positionVCF="124"')
        result = resolve(case(), PMID, [record(), record(other)])
        self.assertEqual(['LITERATURE_ALLELE_ALIAS_AMBIGUOUS'], result['issues'])

    def test_identity_and_raw_checks_fail_closed(self):
        with self.assertRaisesRegex(ValueError, 'SHA_MISMATCH'): read_record(RECORD, '0' * 64, VCV)
        for before, after, reason in [(b'VCV999999997', b'VCV999999998', 'VCV_ID_MISMATCH'),
                                      (b'>current<', b'>replaced<', 'NOT_CURRENT'),
                                      (b'Homo sapiens', b'Mus musculus', 'SPECIES_UNSUPPORTED'),
                                      (b'HGNC:999999997', b'unknown', 'GENE_IDENTITY_AMBIGUOUS')]:
            with self.subTest(reason=reason), self.assertRaisesRegex(ValueError, reason): record(RECORD.replace(before, after))

    def test_location_cannot_be_gene_only_or_ambiguous(self):
        root = ET.fromstring(RECORD); simple = root.find('.//SimpleAllele')
        location = simple.find('Location'); simple.remove(location)
        with self.assertRaisesRegex(ValueError, 'LOCATION_AMBIGUOUS'): record(ET.tostring(root))
        simple.append(location); location.append(ET.fromstring(ET.tostring(location[0])))
        with self.assertRaisesRegex(ValueError, 'LOCATION_AMBIGUOUS'): record(ET.tostring(root))

    def test_xml_bounds_entities_mnv_sex_chromosomes_rejected(self):
        for raw, reason in [(b'<!DOCTYPE x>' + RECORD, 'DTD_FORBIDDEN'), (b'x' * (MAX_RECORD_BYTES + 1), 'SIZE_LIMIT'),
                            (RECORD.replace(b'Chr="1"', b'Chr="X"'), 'SCOPE_UNSUPPORTED'),
                            (RECORD.replace(b'referenceAlleleVCF="A"', b'referenceAlleleVCF="AT"').replace(b'alternateAlleleVCF="G"', b'alternateAlleleVCF="GC"'), 'SCOPE_UNSUPPORTED')]:
            with self.subTest(reason=reason), self.assertRaisesRegex(ValueError, reason): record(raw)

    def test_simple_short_indel_identity_is_supported_without_normalizing_it(self):
        raw = RECORD.replace(b'alternateAlleleVCF="G"', b'alternateAlleleVCF="AG"')
        self.assertEqual('AG', record(raw)['allele']['alternate'])


class LiteratureSourceAssertionsTest(unittest.TestCase):
    def test_explicit_target_and_parentage_preserve_sources_without_scoring(self):
        result = run(); first = result['cases'][0]
        self.assertEqual('PUBLISHED_TRIO_SANGER_DENOVO', first['targetTesting']['status'])
        self.assertEqual('PUBLISHED_BOTH_PARENTS_CONFIRMED', first['parentage']['status'])
        self.assertEqual('REPORTED_NONE', first['familyHistory'])
        self.assertEqual([], first['issues'])
        self.assertEqual('NOT_ASSESSED', first['phenotypeApplicability'])
        self.assertEqual('NOT_ASSESSED', first['independence'])
        self.assertEqual('NONE', result['clinicalAdoption'])
        self.assertEqual('NOT_RUN_SOURCE_FACTS_ONLY', result['evaluationStatus'])
        self.assertEqual(0, result['modelCalls'])
        for proof in first['targetTesting']['citations'] + first['parentage']['citations']:
            self.assertEqual('CITATION_MATCHED_SEMANTICS_NOT_VERIFIED', verify_citation(ARTICLE, PMCID, sha(ARTICLE), proof['citation']))

    def test_de_novo_label_and_negative_gt_do_not_supply_target_tests(self):
        raw = ARTICLE.replace(b'Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.', b'The variant is de novo. The father genotype is 0/0.')
        start, end = raw.index(b'<fig>'), raw.index(b'</fig>') + len(b'</fig>')
        result = run(raw[:start] + raw[end:])['cases'][0]
        self.assertEqual('UNKNOWN', result['targetTesting']['status'])
        self.assertIn('LITERATURE_PARENTAL_TARGET_TESTS_NOT_RESOLVED', result['issues'])

    def test_not_confirmed_parentage_is_never_confirmed(self):
        for phrase in (b'SNP analysis did not confirm maternity or paternity.', b'SNP analysis may confirm both maternity and paternity.',
                       b'SNP analysis confirmed paternity.', b'SNP analysis confirmed neither maternity nor paternity.'):
            first = run(ARTICLE.replace(b'SNP analysis confirmed both maternity and paternity.', phrase))['cases'][0]
            self.assertEqual('UNKNOWN', first['parentage']['status'])

    def test_references_abstract_and_other_case_cannot_supply_parentage(self):
        phrase = b'SNP analysis confirmed both maternity and paternity.'
        raw = ARTICLE.replace(phrase, b'Parentage remains unresolved.')
        variants = [raw.replace(b'</article-meta>', b'<abstract><p>' + phrase + b'</p></abstract></article-meta>'),
                    raw.replace(b'</article>', b'<back><ref-list><ref><p>' + phrase + b'</p></ref></ref-list></back></article>'),
                    raw.replace(b'</body>', b'<sec><title>Results</title><sec><title>Case B</title><p>' + phrase + b'</p></sec></sec></body>')]
        for raw in variants:
            self.assertEqual('UNKNOWN', run(raw)['cases'][0]['parentage']['status'])

    def test_different_target_anchor_and_separate_figure_cannot_supply_trio(self):
        first = run(ARTICLE.replace(b'variant c.123A&gt;G', b'variant c.124A&gt;G'))['cases'][0]
        self.assertEqual('UNKNOWN', first['targetTesting']['status'])
        self.assertIn('LITERATURE_CASE_TARGET_LINK_UNRESOLVED', first['issues'])
        raw = ARTICLE.replace(b'Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.', b'')
        raw = raw.replace(b'<fig>', b'</p><p><fig>').replace(b'</fig>', b'</fig></p><p>')
        self.assertEqual('UNKNOWN', run(raw)['cases'][0]['targetTesting']['status'])

    def test_multiple_target_or_same_label_cohort_needs_resolution(self):
        raw = ARTICLE.replace(b'SNP analysis', b'A second mutation c.124A&gt;G, p.T42A, was found. SNP analysis')
        first = run(raw)['cases'][0]
        self.assertEqual('UNKNOWN', first['targetTesting']['status'])
        self.assertIn('LITERATURE_CASE_MULTIPLE_TARGETS_UNRESOLVED', first['issues'])
        table = ARTICLE[ARTICLE.index(b'<table-wrap>'):ARTICLE.index(b'</table-wrap>') + len(b'</table-wrap>')]
        result = run(ARTICLE.replace(b'</body>', table + b'</body>'))
        self.assertEqual(2, len(result['cases']))
        self.assertTrue(all('LITERATURE_CASE_COHORT_AMBIGUOUS' in c['issues'] for c in result['cases']))

    def test_possible_mosaic_or_inherited_statement_holds_assertions(self):
        for statement in (b'Paternal mosaicism was observed.', b'No mosaicism was identified.', b'The mutation was inherited.',
                          b'The variant was detected in the mother.', b'Parentage was inconclusive.', b'There was an affected sibling.',
                          b'The father was not tested.'):
            raw = ARTICLE.replace(b'</p></sec>', statement + b'</p></sec>')
            first = run(raw)['cases'][0]
            self.assertIn('LITERATURE_SPECIAL_OR_CONFLICT_TEXT_NOT_AUTOMATICALLY_RESOLVED', first['issues'])
            self.assertEqual('UNKNOWN', first['targetTesting']['status'])

    def test_unresolved_alias_does_not_hide_observed_source_assertions(self):
        result = qualify(ARTICLE, PMCID, sha(ARTICLE))['cases'][0]
        self.assertEqual('UNRESOLVED', result['alleleIdentity']['status'])
        self.assertEqual('PUBLISHED_TRIO_SANGER_DENOVO', result['targetTesting']['status'])
        self.assertIn('LITERATURE_ALLELE_ALIAS_NOT_FOUND', result['issues'])

    def test_case_table_protein_and_phenotype_are_distinct(self):
        fields = case()['fields']
        self.assertEqual('T41A', next(f['sourceValue'] for f in fields if f['field'] == 'proteinChange'))
        self.assertEqual('Synthetic trait', next(f['sourceValue'] for f in fields if f['field'] == 'reportedPhenotype'))

    def test_parser_rebuilds_pinned_source_and_is_deterministic(self):
        self.assertEqual(run(), run())
        with self.assertRaisesRegex(ValueError, 'SOURCE_SHA_MISMATCH'): qualify(ARTICLE, PMCID, '0' * 64)

    def test_snp_consistency_sentence_requires_both_parents_and_affirmative_test(self):
        sentence = (b'Bi-directional Sanger sequencing confirmed the next-generation sequencing findings (Fig. 3B) '
                    b'and analyses of other rare SNPs in the proband and his parents were consistent with the stated '
                    b'parentage (Fig. 2), confirming that the mutation arose de novo in the patient.')
        raw = ARTICLE.replace(b'SNP analysis confirmed both maternity and paternity.', sentence)
        self.assertEqual('PUBLISHED_BOTH_PARENTS_CONFIRMED', run(raw)['cases'][0]['parentage']['status'])
        for before, after in [(b'his parents', b'his mother'), (b'were consistent', b'were not consistent'),
                              (b'confirmed the next-generation', b'did not confirm the next-generation')]:
            self.assertEqual('UNKNOWN', run(raw.replace(before, after))['cases'][0]['parentage']['status'])

    def test_negated_or_hypothetical_trio_sentence_is_not_used(self):
        for before, after in [(b'Sanger sequencing', b'No Sanger sequencing'),
                              (b'confirmed the heterozygous', b'may confirm the heterozygous'),
                              (b'is de novo', b'may be de novo')]:
            raw = ARTICLE.replace(before, after)
            # Eliminate the other independent positive grammar for each mutation.
            if before == b'confirmed the heterozygous':
                raw = raw.replace(b'to show that the heterozygous mutation is de novo', b'was requested')
            elif before == b'is de novo':
                raw = raw.replace(b'confirmed the heterozygous', b'may confirm the heterozygous')
            self.assertEqual('UNKNOWN', run(raw)['cases'][0]['targetTesting']['status'])

    def test_unrecognized_family_sentence_remains_a_blocker(self):
        raw = ARTICLE.replace(b'</p></sec>', b"The father's reported result was contradicted.</p></sec>")
        first = run(raw)['cases'][0]
        self.assertEqual('UNRESOLVED', first['contextVerification'])
        self.assertIn('LITERATURE_UNRECOGNIZED_CLINICAL_NARRATIVE_NOT_VERIFIED', first['pendingChecks'])
        self.assertTrue(first['unresolvedContext'])

    def test_unrelated_functional_prose_does_not_require_case_approval(self):
        first = run(ARTICLE.replace(b'</p></sec>', b' The channel was functionally investigated.</p></sec>'))['cases'][0]
        self.assertEqual('SUPPORTED_EXPLICIT_FAMILY_STATEMENTS', first['contextVerification'])
        self.assertNotIn('LITERATURE_UNRECOGNIZED_CLINICAL_NARRATIVE_NOT_VERIFIED', first['pendingChecks'])
        self.assertEqual('SOURCE_ASSERTIONS_ONLY_NOT_CLINICALLY_QUALIFIED', first['qualification'])

    def test_explicit_untested_and_unreported_are_distinct_from_inconclusive(self):
        for sentence, expected in [(b'No parentage testing was performed.', 'PUBLISHED_PARENTAGE_NOT_TESTED'),
                                    (b'Parentage was not reported.', 'PUBLISHED_PARENTAGE_NOT_REPORTED'),
                                    (b'Parentage was inconclusive.', 'UNKNOWN')]:
            first = run(ARTICLE.replace(b'SNP analysis confirmed both maternity and paternity.', sentence))['cases'][0]
            self.assertEqual(expected, first['parentage']['status'])
        raw = ARTICLE.replace(b'</p></sec>', b' No parentage testing was performed.</p></sec>')
        self.assertIn('LITERATURE_PARENTAGE_CONFLICT', run(raw)['cases'][0]['issues'])

    def test_descriptive_caption_is_not_a_source_of_parental_test_results(self):
        heading = b'A novel de novo mutation predicted to affect a protein domain. '
        raw = ARTICLE.replace(b'<caption><p>', b'<caption><p>' + heading)
        self.assertEqual('SUPPORTED_EXPLICIT_FAMILY_STATEMENTS', run(raw)['cases'][0]['contextVerification'])
        raw = raw.replace(b'Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.', b'')
        raw = raw.replace(b'(B) Sanger sequencing of the patient and parents to show that the heterozygous mutation is de novo.', b'')
        self.assertEqual('UNKNOWN', run(raw)['cases'][0]['targetTesting']['status'])

    def test_caption_with_unresolved_family_statement_is_not_ignored(self):
        raw = ARTICLE.replace(b'<caption><p>', b'<caption><p>A novel de novo mutation predicted to affect the father. ')
        self.assertEqual('UNRESOLVED', run(raw)['cases'][0]['contextVerification'])


if __name__ == '__main__':
    unittest.main()
