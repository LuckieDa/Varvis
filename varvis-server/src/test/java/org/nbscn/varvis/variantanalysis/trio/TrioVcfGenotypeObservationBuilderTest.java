package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioVcfGenotypeObservationBuilderTest {

    private final TrioVcfGenotypeObservationBuilder builder = new TrioVcfGenotypeObservationBuilder();

    @Test
    void parsesJointVcfIntoTrioObservationsAndDeNovoEvidence() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("joint_trio_de_novo.synthetic.vcf"));

        assertFalse(result.hasErrors());
        assertEquals(1, result.getAnalysisInput().getVariants().size());
        TrioVariantObservation variant = result.getAnalysisInput().getVariants().get(0);
        assertEquals(TrioGenotypeCall.HET_ALT, variant.genotype(TrioRole.PROBAND).getCall());
        assertEquals(TrioGenotypeCall.HOM_REF, variant.genotype(TrioRole.FATHER).getCall());
        assertEquals(TrioGenotypeCall.HOM_REF, variant.genotype(TrioRole.MOTHER).getCall());
        assertEquals(Integer.valueOf(35), variant.genotype(TrioRole.FATHER).getDepth());
        assertEquals(Integer.valueOf(99), variant.genotype(TrioRole.FATHER).getGenotypeQuality());
        assertEquals("35,0", variant.genotype(TrioRole.FATHER).getAlleleDepth());

        TrioPreflightReport preflight = new TrioPreflightService()
                .preflight(TrioInputMode.JOINT_VCF, result.getAnalysisInput());
        assertTrue(preflight.isValid());

        SegregationEvidence evidence = evidence(new TrioSegregationService()
                .evaluate(result.getAnalysisInput()), "denovo-1");
        assertEquals(InheritanceModelCandidate.DE_NOVO_CANDIDATE, evidence.getModel());

        TrioEvidenceReport report = new TrioEvidenceReportMapper().toReport(preflight, Arrays.asList(evidence));
        assertEquals(TrioEvidenceReport.RANKING_EFFECT, report.getRankingEffect());
        assertEquals(TrioEvidenceReport.HARD_GATE_POLICY, report.getHardGatePolicy());
    }

    @Test
    void recordsIssueWhenMappedSampleIsMissing() throws Exception {
        TrioVcfParseRequest request = TrioVcfParseRequest.joint(
                "GRCh37",
                resource("joint_trio_de_novo.synthetic.vcf"),
                new TrioVcfSampleMapping("missing-proband", "father", "mother"),
                members(TrioSex.FEMALE));

        TrioVcfParseResult result = builder.parse(request);

        assertTrue(result.hasErrors());
        assertTrue(hasIssue(result, "TRIO_VCF_SAMPLE_MISSING"));
    }

    @Test
    void filtersJointVcfToRequestedCandidateVariantKeys() throws Exception {
        Map<String, String> targetVariants = new LinkedHashMap<String, String>();
        targetVariants.put(
                new TrioVcfVariantKey("GRCh37", "4", 220L, "C", "T").stableVariantId(),
                "ANNOTATED_GENE"
        );
        TrioVcfParseRequest request = joint("compound_het.synthetic.vcf")
                .withTargetVariants(targetVariants);

        TrioVcfParseResult result = builder.parse(request);

        assertFalse(result.hasErrors());
        assertEquals(1, result.getAnalysisInput().getVariants().size());
        assertEquals("compound-mat", result.getAnalysisInput().getVariants().get(0).getVariantId());
        assertEquals("ANNOTATED_GENE", result.getAnalysisInput().getVariants().get(0).getGeneSymbol());
        assertEquals(3, result.getGenotypeRecords().size());
    }

    @Test
    void mapsNoCallParentWithoutCallingDeNovo() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("joint_trio_parent_nocall.synthetic.vcf"));

        TrioVariantObservation variant = result.getAnalysisInput().getVariants().get(0);
        assertEquals(TrioGenotypeCall.NO_CALL, variant.genotype(TrioRole.FATHER).getCall());
        SegregationEvidence evidence = evidence(new TrioSegregationService()
                .evaluate(result.getAnalysisInput()), "parent-nocall-1");

        assertEquals(InheritanceModelCandidate.UNINFORMATIVE, evidence.getModel());
        assertTrue(evidence.getWarnings().get(0).contains("not treated as confirmed 0/0"));
    }

    @Test
    void lowQualityParentReferenceIsNotConfirmedReference() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("low_quality_parent_ref.synthetic.vcf"));

        TrioVariantObservation variant = result.getAnalysisInput().getVariants().get(0);
        assertEquals(TrioGenotypeCall.UNCONFIRMED_HOM_REF, variant.genotype(TrioRole.FATHER).getCall());
        assertFalse(variant.genotype(TrioRole.FATHER).isConfirmedReference());

        SegregationEvidence evidence = evidence(new TrioSegregationService()
                .evaluate(result.getAnalysisInput()), "low-quality-parent-ref-1");
        assertEquals(InheritanceModelCandidate.UNINFORMATIVE, evidence.getModel());
    }

    @Test
    void preservesRecordAndSampleFiltersInGenotypeRecord() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("joint_trio_de_novo.synthetic.vcf"));

        TrioVcfGenotypeRecord fatherRecord = record(result, "denovo-1", TrioRole.FATHER);

        assertEquals("PASS", fatherRecord.getRecordFilter());
        assertEquals("PASS", fatherRecord.getSampleFilter());
        assertEquals("0/0:35:99:35,0:PASS", fatherRecord.getRawSampleValue());
    }

    @Test
    void parsesPhasedGenotypesAndSampleOrderByName() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("phased_sample_order.synthetic.vcf"));

        assertFalse(result.hasErrors());
        TrioVariantObservation phasedHet = variant(result, "phased-het");
        TrioVariantObservation phasedHom = variant(result, "phased-hom");

        assertEquals(TrioGenotypeCall.HET_ALT, phasedHet.genotype(TrioRole.PROBAND).getCall());
        assertTrue(phasedHet.genotype(TrioRole.PROBAND).isPhased());
        assertEquals(TrioGenotypeCall.HOM_REF, phasedHet.genotype(TrioRole.FATHER).getCall());
        assertEquals(TrioGenotypeCall.HOM_REF, phasedHet.genotype(TrioRole.MOTHER).getCall());

        assertEquals(TrioGenotypeCall.HOM_ALT, phasedHom.genotype(TrioRole.PROBAND).getCall());
        assertTrue(phasedHom.genotype(TrioRole.PROBAND).isPhased());
    }

    @Test
    void missingDpGqDoesNotConfirmReference() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("missing_format_quality.synthetic.vcf"));

        TrioVariantObservation variant = variant(result, "missing-format-quality");

        assertEquals(TrioGenotypeCall.UNCONFIRMED_HOM_REF, variant.genotype(TrioRole.FATHER).getCall());
        assertEquals(TrioGenotypeCall.UNCONFIRMED_HOM_REF, variant.genotype(TrioRole.MOTHER).getCall());
        assertTrue(hasIssue(result, "TRIO_VCF_REFERENCE_QUALITY_MISSING"));

        SegregationEvidence evidence = evidence(new TrioSegregationService()
                .evaluate(result.getAnalysisInput()), "missing-format-quality");
        assertEquals(InheritanceModelCandidate.UNINFORMATIVE, evidence.getModel());
    }

    @Test
    void adAltSupportDoesNotConfirmReference() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("ad_noise_parent_ref.synthetic.vcf"));

        TrioVariantObservation variant = variant(result, "ad-noise-parent-ref");

        assertEquals(TrioGenotypeCall.UNCONFIRMED_HOM_REF, variant.genotype(TrioRole.FATHER).getCall());
        assertTrue(hasIssue(result, "TRIO_VCF_REFERENCE_ALT_SUPPORT"));
    }

    @Test
    void malformedLinesAndFormatSampleMismatchReturnParseIssues() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("malformed.synthetic.vcf"));

        assertTrue(result.hasErrors());
        assertTrue(hasIssue(result, "TRIO_VCF_RECORD_MALFORMED"));
        assertTrue(hasIssue(result, "TRIO_VCF_FORMAT_SAMPLE_LENGTH_MISMATCH"));
    }

    @Test
    void parsesHaploidAndPartialHaploidXGenotypes() throws Exception {
        TrioVcfParseResult result = builder.parse(TrioVcfParseRequest.joint(
                "GRCh37",
                resource("haploid_x.synthetic.vcf"),
                mapping(),
                members(TrioSex.MALE)));

        TrioVariantObservation haploid = variant(result, "haploid-x-1");
        TrioVariantObservation partial = variant(result, "haploid-x-partial");

        assertEquals(TrioGenotypeCall.HEMI_ALT, haploid.genotype(TrioRole.PROBAND).getCall());
        assertEquals(TrioGenotypeCall.HEMI_ALT, partial.genotype(TrioRole.PROBAND).getCall());
        assertTrue(hasIssue(result, "TRIO_VCF_HAPLOID_PARTIAL_GT"));
    }

    @Test
    void parsesMultiAltTargetAlleleWithoutTreatingOtherAltAsReference() throws Exception {
        TrioVcfParseResult result = builder.parse(joint("multialt.synthetic.vcf"));

        TrioVariantObservation firstAlt = variantContaining(result, ":5:700:G>A");
        TrioVariantObservation secondAlt = variantContaining(result, ":5:700:G>C");
        TrioVariantObservation complexFirstAlt = variantContaining(result, ":5:710:G>A");
        TrioVariantObservation complexSecondAlt = variantContaining(result, ":5:710:G>C");

        assertEquals(TrioGenotypeCall.OTHER, firstAlt.genotype(TrioRole.PROBAND).getCall());
        assertEquals(TrioGenotypeCall.HET_ALT, secondAlt.genotype(TrioRole.PROBAND).getCall());
        assertEquals(TrioGenotypeCall.HET_ALT, complexFirstAlt.genotype(TrioRole.PROBAND).getCall());
        assertEquals(TrioGenotypeCall.HET_ALT, complexSecondAlt.genotype(TrioRole.PROBAND).getCall());
        assertTrue(hasIssue(result, "TRIO_VCF_MULTIALLELIC_OTHER_ALT"));
        assertTrue(hasIssue(result, "TRIO_VCF_MULTIALLELIC_COMPLEX_GT"));
    }

    private TrioVcfParseRequest joint(String resourceName) throws Exception {
        return TrioVcfParseRequest.joint(
                "GRCh37",
                resource(resourceName),
                mapping(),
                members(TrioSex.FEMALE));
    }

    private TrioVcfSampleMapping mapping() {
        return new TrioVcfSampleMapping("proband", "father", "mother");
    }

    private List<TrioMember> members(TrioSex probandSex) {
        return Arrays.asList(
                new TrioMember(TrioRole.PROBAND, "proband", probandSex, true),
                new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false));
    }

    private Path resource(String resourceName) throws URISyntaxException {
        return Paths.get(getClass().getResource("/trio/" + resourceName).toURI());
    }

    private SegregationEvidence evidence(List<SegregationEvidence> evidenceList, String variantId) {
        for (SegregationEvidence evidence : evidenceList) {
            if (variantId.equals(evidence.getVariantId())) {
                return evidence;
            }
        }
        throw new AssertionError("Missing evidence for " + variantId);
    }

    private TrioVariantObservation variant(TrioVcfParseResult result, String variantId) {
        for (TrioVariantObservation variant : result.getAnalysisInput().getVariants()) {
            if (variantId.equals(variant.getVariantId())) {
                return variant;
            }
        }
        throw new AssertionError("Missing variant " + variantId);
    }

    private TrioVariantObservation variantContaining(TrioVcfParseResult result, String text) {
        for (TrioVariantObservation variant : result.getAnalysisInput().getVariants()) {
            if (variant.getVariantId() != null && variant.getVariantId().contains(text)) {
                return variant;
            }
        }
        throw new AssertionError("Missing variant containing " + text);
    }

    private TrioVcfGenotypeRecord record(TrioVcfParseResult result, String variantId, TrioRole role) {
        for (TrioVcfGenotypeRecord record : result.getGenotypeRecords()) {
            if (role == record.getRole() && variantId.equals(findVariantId(result, record.getVariantKey()))) {
                return record;
            }
        }
        throw new AssertionError("Missing genotype record for " + variantId + " " + role);
    }

    private String findVariantId(TrioVcfParseResult result, TrioVcfVariantKey key) {
        for (TrioVariantObservation variant : result.getAnalysisInput().getVariants()) {
            if (variant.getPosition() == key.getPosition()) {
                return variant.getVariantId();
            }
        }
        return null;
    }

    private boolean hasIssue(TrioVcfParseResult result, String code) {
        for (TrioVcfParseIssue issue : result.getIssues()) {
            if (code.equals(issue.getCode())) {
                return true;
            }
        }
        return false;
    }
}
