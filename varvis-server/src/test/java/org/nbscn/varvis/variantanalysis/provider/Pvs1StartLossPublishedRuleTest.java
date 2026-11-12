package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossPublishedRuleTest {
    @TempDir Path temporary;
    final Pvs1StartLossExpertFactsTest helper = new Pvs1StartLossExpertFactsTest();
    ObjectNode entity(String text) {
        var e = helper.entity(""); var code = (ObjectNode) e.at("/ld/CriteriaCode/0/entContent");
        code.put("applicability", "Applicable").put("defaultStrength", "Pathogenic Very Strong");
        code.putArray("strengthDescriptor").addObject().put("applicability", "Applicable").put("status", "approved")
                .put("strength", "Supporting").put("text", text);
        return e;
    }
    ObjectNode read(ObjectNode entity) {
        var scope = Pvs1StartLossExpertFacts.read(helper.mapper, entity, helper.scope(), helper.context());
        return Pvs1StartLossPublishedRule.read(helper.mapper, entity, helper.context(), scope);
    }
    @Test void explicitStrengthComesFromAWholeApplicableClauseNeverTheDefault() {
        var e = entity("Null variant in a gene where loss of function is a known mechanism of disease.\n\n* PVS1\\_Supporting is applicable for initiation codon variants in _TEST._");
        var result = read(e); assertEquals("QUALIFIED", result.path("status").asText(), result.toPrettyString());
        assertEquals("SUPPORTING", result.path("strength").asText());
        assertTrue(result.at("/claims/0/sourceLocator").asText().endsWith("/strengthDescriptor/0/text"));
        ((ObjectNode) e.at("/ld/CriteriaCode/0/entContent/strengthDescriptor/0")).put("strength", "Moderate");
        assertEquals("CONFLICT", read(e).path("status").asText());
        assertEquals("UNRESOLVED", read(entity("Initiation codon variants")).path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings = {
        "If the alternative isoform is absent, PVS1_Supporting is applicable for initiation codon variants in TEST.",
        "PVS1_Supporting is applicable for initiation codon variants in TEST. Except for c.1A>G.",
        "PVS1_Supporting is applicable for initiation codon variants in TEST.\n\nExcept for c.1A>G.",
        "Only after confirming the functional result.\n\nPVS1_Supporting is applicable for initiation codon variants in TEST.",
        "PVS1_Supporting is applicable for initiation codon variants in TEST.\n\nInitiation codon variants also require functional evidence.",
        "PVS1_Supporting is applicable for initiation codon variants in OTHER.",
        "PVS1_Very Strong is applicable for initiation codon variants in TEST."})
    void conditionsNegationsOtherGenesAndUnimplementedStrengthCannotBeStripped(String text) {
        assertEquals("UNRESOLVED", read(entity(text)).path("status").asText());
    }
    @Test void sourceRestartConditionIsCheckedAgainstVerifiedSequenceAndProtein() {
        var e = entity("**Initiator codon variants**  \n\\* All initiator codon variants will meet PVS1\\_Moderate. The next in-frame methionine is at amino acid position 4 (based on NP\\_000001.1).");
        ((ObjectNode) e.at("/ld/CriteriaCode/0/entContent/strengthDescriptor/0")).put("strength", "Moderate");
        var rule = read(e); assertEquals("QUALIFIED", rule.path("status").asText(), rule.toPrettyString());
        var assertions = helper.mapper.createObjectNode(); assertions.putArray("entries").addObject().set("startLossRule", rule);
        var sequence = helper.mapper.createObjectNode().put("status", "AVAILABLE").put("candidateRestartCdsPosition", 10);
        var model = helper.mapper.createObjectNode().put("protein", "NP_000001.1");
        assertEquals("QUALIFIED", Pvs1StartLossPublishedRule.resolve(helper.mapper, assertions, sequence, model).path("status").asText());
        model.put("protein", "NP_000001.2");
        assertEquals("PVS1_START_LOSS_PUBLISHED_SEQUENCE_CONDITION_MISMATCH", Pvs1StartLossPublishedRule.resolve(helper.mapper, assertions, sequence, model).path("reasonCode").asText());
        model.put("protein", "NP_000001.1"); sequence.put("candidateRestartCdsPosition", 13);
        assertEquals("UNRESOLVED", Pvs1StartLossPublishedRule.resolve(helper.mapper, assertions, sequence, model).path("status").asText());
        sequence.put("candidateRestartCdsPosition", 10); assertions.withArray("entries").addObject().put("policyDisposition", "PROHIBITED");
        assertEquals("PVS1_START_LOSS_PUBLISHED_RULE_CONFLICT", Pvs1StartLossPublishedRule.resolve(helper.mapper, assertions, sequence, model).path("reasonCode").asText());
    }
    PredictionSpecializationGuard nativeGuard() throws Exception {
        String configured = System.getProperty("pvs1.nativeExpertRoot"); org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        Path root = Path.of(configured);
        return PredictionSpecializationGuard.load(helper.mapper, root.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                root.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
    }
    @Test void pinnedReleasedOriginalsCompilePredeterminedModerateAndSupportingClauses() throws Exception {
        var guard = nativeGuard();
        for (String[] input : new String[][] {
            {"HGNC:4175", "GATM", "MONDO:0012996", "AUTOSOMAL_RECESSIVE", "NM_001482", "3", "MODERATE", "130"},
            {"HGNC:11634", "TCF4", "MONDO:0012589", "AUTOSOMAL_DOMINANT", "NM_001083962", "1", "SUPPORTING", "0"}}) {
            var context = helper.context(); context.put("moi", input[3]);
            context.withObject("gene").put("hgncId", input[0]).put("symbol", input[1]);
            context.withObject("disease").put("mondoId", input[2]);
            context.withObject("transcript").put("accession", input[4]).put("version", Integer.parseInt(input[5]));
            var source = guard.startLossAssertions(helper.mapper, context);
            assertEquals(1, source.path("entries").size()); var rule = source.at("/entries/0/startLossRule");
            assertEquals("QUALIFIED", rule.path("status").asText(), source.toPrettyString());
            assertEquals(input[6], rule.path("strength").asText());
            assertEquals(Integer.parseInt(input[7]), rule.path("requiredRestartProteinPosition").asInt());
            context.withObject("transcript").put("version", 999);
            assertEquals("UNRESOLVED", guard.startLossAssertions(helper.mapper, context).at("/entries/0/startLossRule/status").asText());
        }
    }
    @Test void explicitPermitAndAlternativeExclusionCannotCoexist() {
        var e = entity("PVS1_Supporting is applicable for initiation codon variants in TEST.");
        ((ObjectNode) e.at("/ld/CriteriaCode/0/entContent")).put("instructionsToUse",
                "Initiation codon variants are not applicable due to the TEST_A alternative isoform that excludes exon 1 with an alternative start codon.");
        assertEquals("CONFLICT", read(e).path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"MODERATE", "SUPPORTING"})
    void nativeRuleDrivesSyntheticAllelePreparationAndSurvivesHistoryWithoutUpstreamRequests(String strength) throws Exception {
        // Real released source, synthetic CDS/geometry: engineering integration,
        // not either of the two final real-target validation examples.
        var runtime = new Pvs1StartLossRuntimeTest(); boolean moderate = strength.equals("MODERATE");
        String cds = moderate ? "ATG" + "AAA".repeat(128) + "ATGTAA" : "ATGAAACCCATGAAATAA";
        var data = runtime.helper.inputs(1, 1, cds, 6);
        String tx = moderate ? "NM_001482.3" : "NM_001083962.1";
        data.model.put("hgncId", moderate ? "HGNC:4175" : "HGNC:11634").put("geneSymbol", moderate ? "GATM" : "TCF4")
                .put("transcript", tx).put("refseqTranscript", tx).put("protein", moderate ? "NP_001473.2" : "NP_001077431.1");
        runtime.helper.candidate(data, runtime.helper.genomic(data.model, 1), "A", "G",
                runtime.helper.annotation(data, "start_lost", "1A>G", "Met1Val", "1/2", "1", "M/V", "ATG/GTG"));
        data.context.withObject("transcript").put("version", moderate ? 3 : 1);
        data.context.withObject("disease").put("mondoId", moderate ? "MONDO:0012996" : "MONDO:0012589");
        data.context.put("moi", moderate ? "AUTOSOMAL_RECESSIVE" : "AUTOSOMAL_DOMINANT");
        data.context.put("contextId", runtime.adapter.canonicalContextId(data.context));
        var f = new Pvs1StartLossRuntimeTest.Fixture(data, helper.mapper.createObjectNode(), helper.mapper.createObjectNode(), helper.mapper.createObjectNode());
        var technical = new Pvs1StartLossAutomaticPreparationTest(); technical.root = temporary;
        var supply = technical.technicalSupply(f); var guard = nativeGuard();
        var source = new Pvs1StartLossPublicSources(helper.mapper, true, (url, timeout, max) -> {
            throw new AssertionError("A compiled unconditional specialist rule does not require generic upstream acquisition");
        });
        var preparation = new Pvs1StartLossAutomaticPreparation(helper.mapper, () -> supply, source, () -> guard);
        var prepared = preparation.prepareForEvaluation(data.context, data.candidate); var result = prepared.decision();
        assertNotNull(result, prepared.preparation().toPrettyString());
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals(strength, result.at("/evaluation/appliedStrength").asText());
        assertEquals(0, source.counts().path("httpRequests").asInt());
        result.set("startLossAutomaticPreparation", prepared.preparation());
        var archive = helper.mapper.createObjectNode(); archive.set("result", result); archive.set("originals", preparation.originals());
        assertTrue(preparation.originals().has(prepared.preparation().at("/publishedRuleCheck/verifiedModelSha256").asText()));
        var publicView = Pvs1SelectedBatch.publicSummary(helper.mapper, result);
        assertEquals("1.4.0", publicView.path("methodVersion").asText());
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = java.nio.file.Files.createDirectory(temporary.resolve("history"));
        persistence.saveAndRead(helper.mapper.writeValueAsString(publicView), helper.mapper.writeValueAsString(archive));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            var row = helper.mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("status", "MET")
                    .put("appliedStrength", strength).put("reasonCode", "PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE");
            row.putObject("details").set("pvs1Facts", publicView);
            java.nio.file.Files.createDirectories(Path.of(output));
            java.nio.file.Files.writeString(Path.of(output).resolve("NATIVE_RULE_SYNTHETIC_" + strength + ".json"), helper.mapper.writeValueAsString(row));
        }
        selectedBatch(data, supply, preparation, strength);
    }

    private void selectedBatch(TruncatingNmdPvs1RuntimeTest.Inputs data, CodingTranscriptMaterialSupply supply,
            Pvs1StartLossAutomaticPreparation preparation, String strength) throws Exception {
        // Load genuine pinned runtime configuration; do not stub PVS1 admission,
        // rule selection, qualification, evaluation or projection. Only the
        // other evidence modules are isolated for the all-selection comparison.
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot")); var mapper = helper.mapper;
        var properties = new java.util.Properties();
        try (var reader = java.nio.file.Files.newBufferedReader(root.resolve("local/runtime/bp7-evidence-workbench/evidence-overrides.properties"))) {
            properties.load(reader);
        }
        String prefix = "variant-analysis.acmg-business.";
        var knowledge = Pvs1KnowledgeCatalog.load(mapper,
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-source-file")), properties.getProperty(prefix + "pvs1-knowledge-source-sha256"),
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-adoption-file")), properties.getProperty(prefix + "pvs1-knowledge-adoption-sha256"));
        var bundle = ReviewedRuleBundle.loadPvs1Selected(mapper,
                Path.of(properties.getProperty(prefix + "pvs1-bundle-file")), properties.getProperty(prefix + "pvs1-bundle-file-sha256"),
                properties.getProperty(prefix + "pvs1-bundle-sha256"), new Pvs1TranscriptMaterialSupply(mapper, supply), knowledge);
        assertTrue(bundle.selectedPvs1Enabled());
        assertSelectedBatch(data, bundle, preparation, strength, temporary);
    }

    void assertSelectedBatch(TruncatingNmdPvs1RuntimeTest.Inputs data, ReviewedRuleBundle bundle,
            Pvs1StartLossAutomaticPreparation preparation, String strength, Path temporary) throws Exception {
        var mapper = helper.mapper;
        data.context.withObject("transcript").put("selectionPolicy",
                org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.POLICY_ID + "@1.0.0-unified-local-fourteen");
        data.context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(data.context));
        var pvs1 = new Pvs1SelectedBatch(mapper, bundle, preparation); var business = new LocalEvidenceBatchRuntimeTest();
        org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation selected =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(business, "selected", "one", data.context, data.candidate);
        assertNotNull(selected); com.fasterxml.jackson.databind.JsonNode first = null;
        for (boolean all : new boolean[]{false, true}) {
            var denovo = org.mockito.Mockito.mock(DeNovoSelectedBatch.class);
            org.mockito.Mockito.when(denovo.identity()).thenReturn(mapper.createObjectNode());
            var pm3 = org.mockito.Mockito.mock(Pm3SelectedBatch.class);
            org.mockito.Mockito.when(pm3.identity()).thenReturn(mapper.createObjectNode());
            org.mockito.Mockito.when(pm3.originals()).thenReturn(mapper.createObjectNode());
            var pp1 = org.mockito.Mockito.mock(Pp1SelectedBatch.class);
            org.mockito.Mockito.when(pp1.identity()).thenReturn(mapper.createObjectNode());
            org.mockito.Mockito.when(pp1.originals()).thenReturn(mapper.createObjectNode());
            var bp7 = org.mockito.Mockito.mock(Bp7SelectedBatch.class);
            org.mockito.Mockito.when(bp7.identity()).thenReturn(mapper.createObjectNode());
            var runtime = new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode(), null, null, null, null, bp7,
                    () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); }, () -> pvs1,
                    () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); }, () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); },
                    () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); }, () -> { throw new IllegalStateException("ISOLATED_OTHER_MODULE"); },
                    false, denovo, pm3, pp1);
            runtime.bindExecutionCriteria(all ? SelectedCriterionRouting.CODES.stream().map(c -> "ACMG_AMP_2015:" + c).toList()
                    : java.util.List.of("ACMG_AMP_2015:PVS1"));
            var full = runtime.execute(selected, business.analysis, business.sourceSha); var view = runtime.publicView(full);
            com.fasterxml.jackson.databind.JsonNode criterion = null;
            for (var row : view.at("/results/0/localBatch/criteria")) if ("ACMG_AMP_2015:PVS1".equals(row.path("criterionId").asText())) criterion = row;
            assertNotNull(criterion, view.toPrettyString());
            assertEquals("MET", criterion.path("status").asText(), criterion.toPrettyString());
            assertEquals(strength, criterion.path("appliedStrength").asText());
            if (first == null) first = criterion; else assertEquals(first, criterion);
            assertFalse(full.at("/modules/pvs1/startLossSourceDocuments").isEmpty());
            assertFalse(view.at("/modules/pvs1").has("startLossSourceDocuments"));
            if (all) {
                var persistence = new Pvs1StartLossPersistenceTest();
                persistence.temporary = java.nio.file.Files.createDirectory(temporary.resolve("batch-history"));
                persistence.saveAndRead(mapper.writeValueAsString(view), mapper.writeValueAsString(full));
            }
        }
    }
}
