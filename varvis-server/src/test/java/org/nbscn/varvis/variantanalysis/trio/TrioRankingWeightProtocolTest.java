package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.trio.ranking.TrioRankingContribution;
import org.nbscn.varvis.variantanalysis.trio.ranking.TrioRankingContributionService;
import org.nbscn.varvis.variantanalysis.trio.ranking.TrioRankingNotAppliedReason;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrioRankingWeightProtocolTest {

    private static final String TRIO_SCORE_COMPONENT = "trio_segregation_support";

    @TempDir
    Path tempDir;

    @Test
    void evidenceOnlyBaselineLeavesRankingUnchangedForSupportedDeNovo() throws Exception {
        VariantInterpretation interpretation = interpretation("denovo-1", "1", 100, "A", "G");
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(interpretation, joint("joint_trio_de_novo.synthetic.vcf"));

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(before, interpretation);
        EvidenceItem trio = trioEvidence(interpretation);
        assertEquals("DE_NOVO_CANDIDATE", trio.getDetails().get("model"));
        assertEquals("SUPPORTED", trio.getDetails().get("confidence"));
        assertEvidenceOnly(trio);
        assertFalse(interpretation.getScoreComponents().containsKey(TRIO_SCORE_COMPONENT));
    }

    @Test
    void scoreBreakdownSkeletonDoesNotChangeScoreWhenRankingFlagIsDisabled() throws Exception {
        VariantInterpretation interpretation = interpretation("denovo-1", "1", 100, "A", "G");
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(interpretation, joint("joint_trio_de_novo.synthetic.vcf"));
        EvidenceItem trio = trioEvidence(interpretation);
        TrioRankingContribution contribution = new TrioRankingContributionService(enabledProperties())
                .calculate(interpretation, trio);

        assertEquals("ATTACHED", result.getStatus());
        assertEquals(TrioRankingNotAppliedReason.FEATURE_DISABLED, contribution.getNotAppliedReason());
        assertEquals(0.0d, contribution.getContributionScore());
        assertFalse(contribution.isApplied());
        assertNull(trio.getRawScore());
        assertFalse(interpretation.getScoreComponents().containsKey(TRIO_SCORE_COMPONENT));
        assertRankingUnchanged(before, interpretation);
    }

    @Test
    void flagEnabledWithZeroCapStillDoesNotChangeFinalScoreOrRawScore() throws Exception {
        VariantInterpretation interpretation = interpretation("denovo-1", "1", 100, "A", "G");
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(interpretation, joint("joint_trio_de_novo.synthetic.vcf"));
        EvidenceItem trio = trioEvidence(interpretation);
        VariantAnalysisProperties properties = enabledProperties();
        properties.getTrio().setRankingEnabled(true);
        TrioRankingContribution contribution = new TrioRankingContributionService(properties)
                .calculate(interpretation, trio);

        assertEquals("ATTACHED", result.getStatus());
        assertEquals(TrioRankingNotAppliedReason.MAX_CONTRIBUTION_ZERO, contribution.getNotAppliedReason());
        assertEquals(0.0d, contribution.getContributionScore());
        assertFalse(contribution.isApplied());
        assertNull(trio.getRawScore());
        assertFalse(interpretation.getScoreComponents().containsKey(TRIO_SCORE_COMPONENT));
        assertRankingUnchanged(before, interpretation);
    }

    @Test
    void hardGateStatusIsNotOverriddenBySupportedTrioEvidence() throws Exception {
        VariantInterpretation interpretation =
                interpretation("denovo-1", "1", 100, "A", "G");
        interpretation.applyRanking(
                "FILTERED_COMMON",
                -1000.0d,
                Collections.singletonMap("population_frequency", -1000.0d),
                Collections.emptyMap(),
                "gnomAD population frequency crosses the rare-disease candidate gate",
                Collections.emptyList(),
                Collections.singletonList("gnomAD AF_popmax is above the configured gate"),
                Collections.singletonList("Hard-gated by population frequency"),
                Collections.emptyList(),
                Collections.singletonList("AF_popmax >= 0.01")
        );
        interpretation.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_COMMON,
                true,
                false,
                false,
                null,
                Collections.singletonList("common allele hard gate")
        );
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(interpretation, joint("joint_trio_de_novo.synthetic.vcf"));

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(before, interpretation);
        assertEquals("FILTERED_COMMON", interpretation.getPriorityTier());
        assertEquals(VariantAnalysisBucket.FILTERED_COMMON, interpretation.getAnalysisBucket());
        assertEquals(Collections.singletonList("AF_popmax >= 0.01"), interpretation.getFilterReasons());
        EvidenceItem trio = trioEvidence(interpretation);
        assertEquals("DE_NOVO_CANDIDATE", trio.getDetails().get("model"));
        assertEvidenceOnly(trio);
    }

    @Test
    void unobservedParentRemainsNeutralAndDoesNotSupportDeNovoBoost() throws Exception {
        VariantInterpretation interpretation =
                interpretation("three-single-1", "4", 500, "A", "G");
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(
                interpretation,
                TrioPipelineInput.threeSingleSampleVcfs(
                        resource("three_single_proband.synthetic.vcf"),
                        resource("three_single_father_missing.synthetic.vcf"),
                        resource("three_single_mother.synthetic.vcf"),
                        mapping(),
                        members(TrioSex.FEMALE)
                )
        );

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(before, interpretation);
        EvidenceItem trio = trioEvidence(interpretation);
        assertEquals("UNINFORMATIVE", trio.getDetails().get("model"));
        assertEquals("UNINFORMATIVE", trio.getDetails().get("confidence"));
        assertEquals("UNOBSERVED", trio.getDetails().get("fatherGenotype"));
        assertFalse("DE_NOVO_CANDIDATE".equals(trio.getDetails().get("model")));
        assertEvidenceOnly(trio);
    }

    @Test
    void noCallParentRemainsNeutralAndDoesNotSupportDeNovoBoost() throws Exception {
        VariantInterpretation interpretation =
                interpretation("parent-nocall-1", "1", 110, "A", "G");
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(interpretation, joint("joint_trio_parent_nocall.synthetic.vcf"));

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(before, interpretation);
        EvidenceItem trio = trioEvidence(interpretation);
        assertEquals("UNINFORMATIVE", trio.getDetails().get("model"));
        assertEquals("NO_CALL (./.)", trio.getDetails().get("fatherGenotype"));
        assertFalse("SUPPORTED".equals(trio.getDetails().get("confidence")));
        assertEvidenceOnly(trio);
    }

    @Test
    void unconfirmedHomRefParentRemainsNeutralAndDoesNotSupportDeNovoBoost() throws Exception {
        VariantInterpretation interpretation =
                interpretation("low-quality-parent-ref-1", "3", 400, "T", "C");
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(interpretation, joint("low_quality_parent_ref.synthetic.vcf"));

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(before, interpretation);
        EvidenceItem trio = trioEvidence(interpretation);
        assertEquals("UNINFORMATIVE", trio.getDetails().get("model"));
        assertEquals("UNCONFIRMED_HOM_REF (0/0)", trio.getDetails().get("fatherGenotype"));
        assertFalse("SUPPORTED".equals(trio.getDetails().get("confidence")));
        assertEvidenceOnly(trio);
    }

    @Test
    void sameParentHeterozygousPairDoesNotCreateSupportedCompoundHetBoost() throws Exception {
        Path vcf = writeJointVcf(
                "same-parent-compound.synthetic.vcf",
                "chr4\t600\tsame-parent-1\tA\tG\t.\tPASS\tGENE=GENE_SAME\tGT:DP:GQ:AD\t0/1:40:99:20,20\t0/1:35:99:17,18\t0/0:34:99:34,0\n"
                        + "chr4\t620\tsame-parent-2\tC\tT\t.\tPASS\tGENE=GENE_SAME\tGT:DP:GQ:AD\t0/1:42:99:21,21\t0/1:36:99:18,18\t0/0:33:99:33,0\n"
        );
        VariantInterpretation first = interpretation("same-parent-1", "4", 600, "A", "G");
        VariantInterpretation second = interpretation("same-parent-2", "4", 620, "C", "T");
        RankingSnapshot firstBefore = RankingSnapshot.capture(first);
        RankingSnapshot secondBefore = RankingSnapshot.capture(second);

        TrioPipelineResult result = attach(Arrays.asList(first, second), TrioPipelineInput.jointVcf(
                vcf,
                mapping(),
                members(TrioSex.FEMALE)
        ));

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(firstBefore, first);
        assertRankingUnchanged(secondBefore, second);
        EvidenceItem firstTrio = trioEvidence(first);
        EvidenceItem secondTrio = trioEvidence(second);
        assertEquals("INHERITED_CANDIDATE", firstTrio.getDetails().get("model"));
        assertEquals("INHERITED_CANDIDATE", secondTrio.getDetails().get("model"));
        assertFalse("COMPOUND_HETEROZYGOUS_CANDIDATE".equals(firstTrio.getDetails().get("model")));
        assertFalse("COMPOUND_HETEROZYGOUS_CANDIDATE".equals(secondTrio.getDetails().get("model")));
        assertEvidenceOnly(firstTrio);
        assertEvidenceOnly(secondTrio);
    }

    @Test
    void sharedUnaffectedParentVariantIsContextEvidenceNotAutomaticExclusion() throws Exception {
        Path vcf = writeJointVcf(
                "shared-parent.synthetic.vcf",
                "chr2\t700\tshared-parent-1\tG\tA\t.\tPASS\tGENE=GENE_SHARED\tGT:DP:GQ:AD\t0/1:40:99:20,20\t0/1:36:99:18,18\t0/0:35:99:35,0\n"
        );
        VariantInterpretation interpretation =
                interpretation("shared-parent-1", "2", 700, "G", "A");
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(interpretation, TrioPipelineInput.jointVcf(
                vcf,
                mapping(),
                members(TrioSex.FEMALE)
        ));

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(before, interpretation);
        assertEquals("REVIEW", interpretation.getPriorityTier());
        assertEquals(VariantAnalysisBucket.CANDIDATE, interpretation.getAnalysisBucket());
        EvidenceItem trio = trioEvidence(interpretation);
        assertEquals("INHERITED_CANDIDATE", trio.getDetails().get("model"));
        assertEquals("POSSIBLE", trio.getDetails().get("confidence"));
        assertFalse(String.valueOf(trio.getLabel()).toLowerCase().contains("pathogenic"));
        assertFalse(String.valueOf(trio.getSummary()).toLowerCase().contains("diagnosis"));
        assertEvidenceOnly(trio);
    }

    @Test
    void supportedDeNovoAppliesStrongBoostWhenRankingEnabled() throws Exception {
        VariantInterpretation interpretation = interpretation("denovo-1", "1", 100, "A", "G");

        TrioPipelineResult result = attach(
                interpretation,
                joint("joint_trio_de_novo.synthetic.vcf"),
                weightedProperties(2.0d));

        assertEquals("ATTACHED", result.getStatus());
        assertEquals(12.0d, interpretation.getRankingScore());
        assertEquals("REVIEW", interpretation.getPriorityTier());
        assertEquals(VariantAnalysisBucket.CANDIDATE, interpretation.getAnalysisBucket());
        assertEquals(2.0d, interpretation.getScoreBreakdown().get(TRIO_SCORE_COMPONENT));
        EvidenceItem trio = trioEvidence(interpretation);
        assertWeightedTrio(trio, 2.0d);
    }

    @Test
    void compoundHetComplementaryOriginAppliesBoostWithPartnerBreakdown() throws Exception {
        VariantInterpretation first = interpretation("compound-pat", "4", 200, "A", "G");
        VariantInterpretation second = interpretation("compound-mat", "4", 220, "C", "T");

        TrioPipelineResult result = attach(Arrays.asList(first, second),
                joint("compound_het.synthetic.vcf"),
                weightedProperties(2.0d));

        assertEquals("ATTACHED", result.getStatus());
        assertEquals(12.0d, first.getRankingScore());
        assertEquals(12.0d, second.getRankingScore());
        assertEquals(2.0d, first.getScoreBreakdown().get(TRIO_SCORE_COMPONENT));
        assertEquals(2.0d, second.getScoreBreakdown().get(TRIO_SCORE_COMPONENT));
        assertWeightedTrio(trioEvidence(first), 2.0d);
        assertWeightedTrio(trioEvidence(second), 2.0d);
    }

    @Test
    void hardGatedCompoundHetIsNotRescuedByWeightedTrioBoost() throws Exception {
        VariantInterpretation hardGated = interpretation("compound-pat", "4", 200, "A", "G");
        hardGated.applyRanking(
                "FILTERED_QC",
                -1000.0d,
                Collections.singletonMap("qc_filter", -1000.0d),
                Collections.emptyMap(),
                "QC hard gate",
                Collections.emptyList(),
                Collections.singletonList("Variant failed QC hard gate"),
                Collections.singletonList("Hard-gated by QC"),
                Collections.emptyList(),
                Collections.singletonList("FILTER != PASS")
        );
        hardGated.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_QC,
                true,
                false,
                false,
                null,
                Collections.singletonList("QC hard gate")
        );
        VariantInterpretation partner = interpretation("compound-mat", "4", 220, "C", "T");
        RankingSnapshot before = RankingSnapshot.capture(hardGated);

        TrioPipelineResult result = attach(Arrays.asList(hardGated, partner),
                joint("compound_het.synthetic.vcf"),
                weightedProperties(2.0d));

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(before, hardGated);
        assertFalse(hardGated.getScoreBreakdown().containsKey(TRIO_SCORE_COMPONENT));
        EvidenceItem trio = trioEvidence(hardGated);
        assertEquals("COMPOUND_HETEROZYGOUS_CANDIDATE", trio.getDetails().get("model"));
        assertEvidenceOnly(trio);
    }

    @Disabled("Future P5.13.x: weighted trio ranking is not implemented yet")
    @Test
    void futureHomozygousRecessiveCompatibleParentsApplyBoost() {
    }

    @Disabled("Future P5.13.x: weighted trio ranking is not implemented yet")
    @Test
    void futureXLinkedCompatibleModelAppliesBoostOnlyWhenSexContextIsAvailable() {
    }

    @Disabled("Future P5.13.x: weighted trio ranking is not implemented yet")
    @Test
    void futureSharedUnaffectedParentPenaltyIsBoundedAndDiseaseModelAware() {
    }

    @Disabled("Future P5.13.x: weighted trio ranking is not implemented yet")
    @Test
    void futureRecessiveCarrierParentsDoNotTriggerSharedParentPenalty() {
    }

    @Test
    void maxContributionCapAndHardGateProtectionAreEnforced() throws Exception {
        VariantInterpretation capped = interpretation("denovo-1", "1", 100, "A", "G");
        TrioPipelineResult cappedResult = attach(
                capped,
                joint("joint_trio_de_novo.synthetic.vcf"),
                weightedProperties(1.25d));

        assertEquals("ATTACHED", cappedResult.getStatus());
        assertEquals(11.25d, capped.getRankingScore());
        assertEquals(1.25d, capped.getScoreBreakdown().get(TRIO_SCORE_COMPONENT));

        VariantInterpretation hardGated = interpretation("denovo-1", "1", 100, "A", "G");
        hardGated.applyRanking(
                "FILTERED_COMMON",
                -1000.0d,
                Collections.singletonMap("population_frequency", -1000.0d),
                Collections.emptyMap(),
                "gnomAD population frequency crosses the rare-disease candidate gate",
                Collections.emptyList(),
                Collections.singletonList("gnomAD AF_popmax is above the configured gate"),
                Collections.singletonList("Hard-gated by population frequency"),
                Collections.emptyList(),
                Collections.singletonList("AF_popmax >= 0.01")
        );
        hardGated.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_COMMON,
                true,
                false,
                false,
                null,
                Collections.singletonList("common allele hard gate")
        );
        RankingSnapshot before = RankingSnapshot.capture(hardGated);

        TrioPipelineResult hardGateResult = attach(
                hardGated,
                joint("joint_trio_de_novo.synthetic.vcf"),
                weightedProperties(2.0d));

        assertEquals("ATTACHED", hardGateResult.getStatus());
        assertRankingUnchanged(before, hardGated);
        assertFalse(hardGated.getScoreBreakdown().containsKey(TRIO_SCORE_COMPONENT));
        assertEvidenceOnly(trioEvidence(hardGated));
    }

    @Test
    void featureFlagRollbackReturnsToEvidenceOnlyBehavior() throws Exception {
        VariantInterpretation interpretation = interpretation("denovo-1", "1", 100, "A", "G");
        RankingSnapshot before = RankingSnapshot.capture(interpretation);

        TrioPipelineResult result = attach(interpretation, joint("joint_trio_de_novo.synthetic.vcf"));

        assertEquals("ATTACHED", result.getStatus());
        assertRankingUnchanged(before, interpretation);
        assertFalse(interpretation.getScoreBreakdown().containsKey(TRIO_SCORE_COMPONENT));
        assertEvidenceOnly(trioEvidence(interpretation));
    }

    private TrioPipelineResult attach(VariantInterpretation interpretation,
                                      TrioPipelineInput input) {
        return attach(Collections.singletonList(interpretation), input);
    }

    private TrioPipelineResult attach(List<VariantInterpretation> interpretations,
                                      TrioPipelineInput input) {
        return new TrioPipelineEvidenceService(enabledProperties())
                .attachEvidence(interpretations, GenomeAssembly.GRCh37, input);
    }

    private TrioPipelineResult attach(VariantInterpretation interpretation,
                                      TrioPipelineInput input,
                                      VariantAnalysisProperties properties) {
        return attach(Collections.singletonList(interpretation), input, properties);
    }

    private TrioPipelineResult attach(List<VariantInterpretation> interpretations,
                                      TrioPipelineInput input,
                                      VariantAnalysisProperties properties) {
        return new TrioPipelineEvidenceService(properties)
                .attachEvidence(interpretations, GenomeAssembly.GRCh37, input);
    }

    private VariantAnalysisProperties enabledProperties() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getTrio().setEnabled(true);
        properties.getTrio().setPipelineEnabled(true);
        properties.getTrio().setFailOpen(false);
        return properties;
    }

    private VariantAnalysisProperties weightedProperties(double maxContribution) {
        VariantAnalysisProperties properties = enabledProperties();
        properties.getTrio().setRankingEnabled(true);
        properties.getTrio().setRankingMaxScoreContribution(maxContribution);
        return properties;
    }

    private VariantInterpretation interpretation(String variantId,
                                                 String chromosome,
                                                 int position,
                                                 String reference,
                                                 String alternate) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh37", chromosome, position, reference, alternate),
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
                Collections.emptyMap(),
                "Base review tier",
                Collections.singletonList("Base evidence"),
                Collections.emptyList(),
                Collections.singletonList("Base rationale"),
                Collections.emptyList(),
                Collections.emptyList()
        );
        return interpretation;
    }

    private TrioPipelineInput joint(String resourceName) throws Exception {
        return TrioPipelineInput.jointVcf(
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

    private Path writeJointVcf(String fileName, String records) throws Exception {
        Path path = tempDir.resolve(fileName);
        Files.write(
                path,
                ("##fileformat=VCFv4.2\n"
                        + "##source=VarvisTrioRankingProtocolTest\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\tfather\tmother\n"
                        + records).getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private EvidenceItem trioEvidence(VariantInterpretation interpretation) {
        for (EvidenceItem item : interpretation.getEvidence()) {
            if (TrioPipelineEvidenceService.SOURCE.equals(item.getSource())) {
                return item;
            }
        }
        throw new AssertionError("Missing TRIO evidence");
    }

    private void assertEvidenceOnly(EvidenceItem trio) {
        assertNotNull(trio);
        assertEquals("SEGREGATION", trio.getEvidenceType());
        assertNull(trio.getRawScore());
        assertEquals(TrioEvidenceReport.RANKING_EFFECT, trio.getDetails().get("rankingEffect"));
        assertEquals(TrioEvidenceReport.HARD_GATE_POLICY, trio.getDetails().get("hardGatePolicy"));
        assertEquals(Boolean.TRUE, trio.getDetails().get("evidenceOnly"));
    }

    private void assertWeightedTrio(EvidenceItem trio, double score) {
        assertNotNull(trio);
        assertEquals("SEGREGATION", trio.getEvidenceType());
        assertNull(trio.getRawScore());
        assertEquals(TrioEvidenceReport.HARD_GATE_POLICY, trio.getDetails().get("hardGatePolicy"));
        assertEquals("FEATURE_FLAGGED_TRIO_WEIGHTED_RANKING_APPLIED",
                trio.getDetails().get("rankingEffect"));
        assertEquals(Boolean.FALSE, trio.getDetails().get("evidenceOnly"));
        Map<?, ?> contribution = (Map<?, ?>) trio.getDetails().get("trioRankingContribution");
        assertNotNull(contribution);
        assertEquals(Boolean.TRUE, contribution.get("applied"));
        assertEquals(score, contribution.get("score"));
    }

    private void assertRankingUnchanged(RankingSnapshot before, VariantInterpretation after) {
        assertEquals(before.priorityTier, after.getPriorityTier());
        assertEquals(before.rankingScore, after.getRankingScore());
        assertEquals(before.analysisBucket, after.getAnalysisBucket());
        assertEquals(before.scoreBreakdown, after.getScoreBreakdown());
        assertEquals(before.scoreComponents, after.getScoreComponents());
        assertEquals(before.filterReasons, after.getFilterReasons());
    }

    private static final class RankingSnapshot {
        private final String priorityTier;
        private final Double rankingScore;
        private final VariantAnalysisBucket analysisBucket;
        private final Map<String, Double> scoreBreakdown;
        private final Map<String, ?> scoreComponents;
        private final List<String> filterReasons;

        private RankingSnapshot(String priorityTier,
                                Double rankingScore,
                                VariantAnalysisBucket analysisBucket,
                                Map<String, Double> scoreBreakdown,
                                Map<String, ?> scoreComponents,
                                List<String> filterReasons) {
            this.priorityTier = priorityTier;
            this.rankingScore = rankingScore;
            this.analysisBucket = analysisBucket;
            this.scoreBreakdown = scoreBreakdown;
            this.scoreComponents = scoreComponents;
            this.filterReasons = filterReasons;
        }

        private static RankingSnapshot capture(VariantInterpretation interpretation) {
            return new RankingSnapshot(
                    interpretation.getPriorityTier(),
                    interpretation.getRankingScore(),
                    interpretation.getAnalysisBucket(),
                    new LinkedHashMap<String, Double>(interpretation.getScoreBreakdown()),
                    new LinkedHashMap<String, Object>(interpretation.getScoreComponents()),
                    Collections.unmodifiableList(interpretation.getFilterReasons())
            );
        }
    }
}
