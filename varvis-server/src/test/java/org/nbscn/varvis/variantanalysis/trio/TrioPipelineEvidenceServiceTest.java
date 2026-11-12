package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioPipelineEvidenceServiceTest {

    @Test
    void disabledPipelineLeavesInterpretationsUnchanged() throws Exception {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantInterpretation interpretation = interpretation("denovo-1", "GRCh37", "1", 100, "A", "G");

        TrioPipelineResult result = new TrioPipelineEvidenceService(properties)
                .attachEvidence(
                        Collections.singletonList(interpretation),
                        GenomeAssembly.GRCh37,
                        joint("joint_trio_de_novo.synthetic.vcf", TrioSex.FEMALE));

        assertEquals("DISABLED", result.getStatus());
        assertFalse(result.isEnabled());
        assertNull(evidence(interpretation, TrioPipelineEvidenceService.SOURCE));
    }

    @Test
    void attachesTrioEvidenceWithoutChangingRankingOrHardGateBucket() throws Exception {
        VariantAnalysisProperties properties = enabledProperties();
        VariantInterpretation interpretation = interpretation("denovo-1", "GRCh37", "1", 100, "A", "G");
        interpretation.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_COMMON,
                true,
                false,
                false,
                null,
                Collections.singletonList("common allele hard gate"));
        Double originalScore = interpretation.getRankingScore();

        TrioPipelineResult result = new TrioPipelineEvidenceService(properties)
                .attachEvidence(
                        Collections.singletonList(interpretation),
                        GenomeAssembly.GRCh37,
                        joint("joint_trio_de_novo.synthetic.vcf", TrioSex.FEMALE));

        assertEquals("ATTACHED", result.getStatus());
        assertEquals(1, result.getAttachments().size());
        assertEquals(originalScore, interpretation.getRankingScore());
        assertEquals(VariantAnalysisBucket.FILTERED_COMMON, interpretation.getAnalysisBucket());
        assertTrue(interpretation.getCheapGateReasons().contains("common allele hard gate"));

        EvidenceItem trio = evidence(interpretation, TrioPipelineEvidenceService.SOURCE);
        assertNotNull(trio);
        assertEquals("SEGREGATION", trio.getEvidenceType());
        assertEquals("APPLICABLE", trio.getApplicability());
        assertNull(trio.getRawScore());
        assertEquals("DE_NOVO_CANDIDATE", trio.getDetails().get("model"));
        assertEquals(TrioEvidenceReport.RANKING_EFFECT, trio.getDetails().get("rankingEffect"));
        assertEquals(TrioEvidenceReport.HARD_GATE_POLICY, trio.getDetails().get("hardGatePolicy"));
        Map<?, ?> contribution = contributionDetails(trio);
        assertEquals("TRIO", contribution.get("source"));
        assertEquals("trio_segregation_support", contribution.get("componentKey"));
        assertEquals(Boolean.FALSE, contribution.get("enabled"));
        assertEquals(Boolean.FALSE, contribution.get("applied"));
        assertEquals("NEUTRAL", contribution.get("direction"));
        assertEquals("DISABLED", contribution.get("level"));
        assertEquals(0.0d, contribution.get("contributionScore"));
        assertEquals(0.0d, contribution.get("maxContributionCap"));
        assertEquals("FEATURE_DISABLED", contribution.get("notAppliedReason"));
        assertEquals(Boolean.FALSE, contribution.get("hardGateProtected"));
        assertEquals(TrioEvidenceReport.RANKING_EFFECT, contribution.get("rankingEffect"));
        assertEquals(TrioEvidenceReport.HARD_GATE_POLICY, contribution.get("hardGatePolicy"));
    }

    @Test
    void weightedContributionDetailsExposeAppliedBoostWithoutRawEvidenceScore() throws Exception {
        VariantAnalysisProperties properties = enabledProperties();
        properties.getTrio().setRankingEnabled(true);
        properties.getTrio().setRankingMaxScoreContribution(2.0d);
        VariantInterpretation interpretation = interpretation("denovo-1", "GRCh37", "1", 100, "A", "G");

        TrioPipelineResult result = new TrioPipelineEvidenceService(properties)
                .attachEvidence(
                        Collections.singletonList(interpretation),
                        GenomeAssembly.GRCh37,
                        joint("joint_trio_de_novo.synthetic.vcf", TrioSex.FEMALE));

        assertEquals("ATTACHED", result.getStatus());
        assertEquals(12.0d, interpretation.getRankingScore());
        assertEquals(2.0d, interpretation.getScoreBreakdown().get("trio_segregation_support"));
        EvidenceItem trio = evidence(interpretation, TrioPipelineEvidenceService.SOURCE);
        assertNotNull(trio);
        assertNull(trio.getRawScore());
        assertEquals(Boolean.FALSE, trio.getDetails().get("evidenceOnly"));
        assertEquals("FEATURE_FLAGGED_TRIO_WEIGHTED_RANKING_APPLIED",
                trio.getDetails().get("rankingEffect"));
        Map<?, ?> contribution = contributionDetails(trio);
        assertEquals(Boolean.TRUE, contribution.get("enabled"));
        assertEquals(Boolean.TRUE, contribution.get("applied"));
        assertEquals("BOOST", contribution.get("direction"));
        assertEquals("STRONG_BOOST", contribution.get("level"));
        assertEquals(2.0d, contribution.get("contributionScore"));
        assertEquals(2.0d, contribution.get("maxContributionCap"));
        assertNull(contribution.get("notAppliedReason"));
        assertEquals(Boolean.FALSE, contribution.get("hardGateProtected"));
        assertEquals("DE_NOVO_CANDIDATE", contribution.get("model"));
        assertEquals("SUPPORTED", contribution.get("confidence"));
    }

    @Test
    void hardGatedWeightedContributionDetailsRemainNeutralAndProtected() throws Exception {
        VariantAnalysisProperties properties = enabledProperties();
        properties.getTrio().setRankingEnabled(true);
        properties.getTrio().setRankingMaxScoreContribution(2.0d);
        VariantInterpretation interpretation = interpretation("denovo-1", "GRCh37", "1", 100, "A", "G");
        interpretation.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_QC,
                true,
                false,
                false,
                null,
                Collections.singletonList("QC hard gate"));
        Double originalScore = interpretation.getRankingScore();

        TrioPipelineResult result = new TrioPipelineEvidenceService(properties)
                .attachEvidence(
                        Collections.singletonList(interpretation),
                        GenomeAssembly.GRCh37,
                        joint("joint_trio_de_novo.synthetic.vcf", TrioSex.FEMALE));

        assertEquals("ATTACHED", result.getStatus());
        assertEquals(originalScore, interpretation.getRankingScore());
        assertFalse(interpretation.getScoreBreakdown().containsKey("trio_segregation_support"));
        EvidenceItem trio = evidence(interpretation, TrioPipelineEvidenceService.SOURCE);
        assertNotNull(trio);
        assertNull(trio.getRawScore());
        assertEquals(TrioEvidenceReport.RANKING_EFFECT, trio.getDetails().get("rankingEffect"));
        Map<?, ?> contribution = contributionDetails(trio);
        assertEquals(Boolean.TRUE, contribution.get("enabled"));
        assertEquals(Boolean.FALSE, contribution.get("applied"));
        assertEquals("NEUTRAL", contribution.get("direction"));
        assertEquals(0.0d, contribution.get("contributionScore"));
        assertEquals("HARD_GATE_PROTECTED", contribution.get("notAppliedReason"));
        assertEquals(Boolean.TRUE, contribution.get("hardGateProtected"));
    }

    @Test
    void coordinateFallbackCanAttachWhenVariantIdDiffers() throws Exception {
        VariantAnalysisProperties properties = enabledProperties();
        VariantInterpretation interpretation =
                interpretation("analysis-generated-id", "GRCh37", "1", 100, "A", "G");

        TrioPipelineResult result = new TrioPipelineEvidenceService(properties)
                .attachEvidence(
                        Collections.singletonList(interpretation),
                        GenomeAssembly.GRCh37,
                        joint("joint_trio_de_novo.synthetic.vcf", TrioSex.FEMALE));

        assertEquals("ATTACHED", result.getStatus());
        assertEquals(1, result.getAttachments().size());
        assertNotNull(evidence(interpretation, TrioPipelineEvidenceService.SOURCE));
    }

    @Test
    void compoundHetEvidenceCarriesPartnerIdsButNoRankingScore() throws Exception {
        VariantAnalysisProperties properties = enabledProperties();
        List<VariantInterpretation> interpretations = Arrays.asList(
                interpretation("compound-pat", "GRCh37", "4", 200, "A", "G"),
                interpretation("compound-mat", "GRCh37", "4", 220, "C", "T"));
        for (VariantInterpretation interpretation : interpretations) {
            interpretation.applyTranscriptAnnotations(Collections.singletonList(
                    new TranscriptConsequence("VEP_GENE", null, "NM_TEST", null, null,
                            "missense_variant")));
        }

        TrioPipelineResult result = new TrioPipelineEvidenceService(properties)
                .attachEvidence(
                        interpretations,
                        GenomeAssembly.GRCh37,
                        joint("compound_het.synthetic.vcf", TrioSex.FEMALE));

        assertEquals(2, result.getAttachments().size());
        EvidenceItem paternal = evidence(interpretations.get(0), TrioPipelineEvidenceService.SOURCE);
        EvidenceItem maternal = evidence(interpretations.get(1), TrioPipelineEvidenceService.SOURCE);
        assertEquals("COMPOUND_HETEROZYGOUS_CANDIDATE", paternal.getDetails().get("model"));
        assertEquals("COMPOUND_HETEROZYGOUS_CANDIDATE", maternal.getDetails().get("model"));
        assertEquals("VEP_GENE", paternal.getDetails().get("geneSymbol"));
        assertEquals("VEP_GENE", maternal.getDetails().get("geneSymbol"));
        assertEquals(Collections.singletonList("compound-mat"), paternal.getDetails().get("partnerVariantIds"));
        assertEquals(Collections.singletonList("compound-pat"), maternal.getDetails().get("partnerVariantIds"));
        assertNull(paternal.getRawScore());
        assertNull(maternal.getRawScore());
    }

    @Test
    void failOpenKeepsAnalysisAvailableWhenParserReportsErrors() throws Exception {
        VariantAnalysisProperties properties = enabledProperties();
        properties.getTrio().setFailOpen(true);
        VariantInterpretation interpretation =
                interpretation("malformed-too-few-columns", "GRCh37", "1", 900, "A", "G");

        TrioPipelineResult result = new TrioPipelineEvidenceService(properties)
                .attachEvidence(
                        Collections.singletonList(interpretation),
                        GenomeAssembly.GRCh37,
                        joint("malformed.synthetic.vcf", TrioSex.FEMALE));

        assertEquals("PARSE_ERROR", result.getStatus());
        assertTrue(result.isFailOpenUsed());
        assertTrue(result.getIssues().get(0).contains("TRIO_VCF_RECORD_MALFORMED"));
        assertNull(evidence(interpretation, TrioPipelineEvidenceService.SOURCE));
    }

    @Test
    void failClosedRaisesClearErrorWhenParserReportsErrors() throws Exception {
        VariantAnalysisProperties properties = enabledProperties();
        properties.getTrio().setFailOpen(false);
        VariantInterpretation interpretation =
                interpretation("malformed-too-few-columns", "GRCh37", "1", 900, "A", "G");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                new TrioPipelineEvidenceService(properties)
                        .attachEvidence(
                                Collections.singletonList(interpretation),
                                GenomeAssembly.GRCh37,
                                joint("malformed.synthetic.vcf", TrioSex.FEMALE)));

        assertTrue(thrown.getMessage().contains("Trio evidence PARSE_ERROR"));
        assertTrue(thrown.getMessage().contains("TRIO_VCF_RECORD_MALFORMED"));
    }

    private VariantAnalysisProperties enabledProperties() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getTrio().setEnabled(true);
        properties.getTrio().setPipelineEnabled(true);
        return properties;
    }

    private VariantInterpretation interpretation(String variantId,
                                                 String assembly,
                                                 String chromosome,
                                                 int position,
                                                 String reference,
                                                 String alternate) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey(assembly, chromosome, position, reference, alternate),
                "proband",
                "0/1",
                40,
                99,
                "20,20",
                0.5d,
                "PASS",
                "SNV",
                null);
        VariantInterpretation interpretation = new VariantInterpretation(
                variantId,
                observation,
                Collections.emptyList(),
                Collections.emptyList());
        interpretation.applyRanking(
                "REVIEW",
                10.0d,
                Collections.singletonMap("base", 10.0d),
                Collections.singletonList("Base ranking"),
                Collections.emptyList());
        return interpretation;
    }

    private TrioPipelineInput joint(String resourceName, TrioSex probandSex) throws Exception {
        return TrioPipelineInput.jointVcf(
                resource(resourceName),
                new TrioVcfSampleMapping("proband", "father", "mother"),
                members(probandSex));
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

    private EvidenceItem evidence(VariantInterpretation interpretation, String source) {
        for (EvidenceItem item : interpretation.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        return null;
    }

    private Map<?, ?> contributionDetails(EvidenceItem trio) {
        Object contribution = trio.getDetails().get("trioRankingContribution");
        assertNotNull(contribution);
        return (Map<?, ?>) contribution;
    }
}
