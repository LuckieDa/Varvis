package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessRuleScope;
import org.nbscn.varvis.variantanalysis.provider.DeNovoBatchFixture;
import org.nbscn.varvis.variantanalysis.provider.DeNovoSelectedBatch;
import org.nbscn.varvis.variantanalysis.provider.DeNovoStructuredReport;
import org.nbscn.varvis.variantanalysis.provider.LocalEvidenceBatchRuntime;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Normal publication/request/projection skeleton; original family, knowledge and upstream engine/annotation are synthetic.
 * The de novo reader/evaluator and local H2 repositories are real. This is not a full clinical, history or export acceptance. */
class DeNovoAdoptionBusinessContractTest {
    @TempDir Path temporary;
    private AcmgBusinessIntegrationTest base;
    private final List<DeNovoSelectedBatch> modules = new ArrayList<>();

    @AfterEach void close() { if (base != null) base.closeDatabase(); }

    private void setup(String mode) throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        base.variantTsvOverride = tsv("1\t11\t2\tT\tA\tCDH1\t999\tAD\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, null);
        var f = new DeNovoBatchFixture(temporary.resolve("m"));
        byte[] knowledge = ("SYNTHETIC ONLY: invented reusable engineering policy, no clinical or gene adoption. "
                + "HP:0001250 is consistent in this test only. Tissue C1 is qualified proband presence, parental absence and confirmed parentage; "
                + "C2 is adequate different tissue measurements from the same person, affected fraction greater than comparison. "
                + "C1 plus C2 assigns fixed Strong, without SVI points. Combined PS2 accepts qualified assumed parentage at SVI weight.")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(f.root.resolve("source.txt"), knowledge);
        String sourceSha = DeNovoBatchFixture.sha(knowledge);
        ((ObjectNode) f.manifest.at("/sources/0")).put("sha256", sourceSha);
        ((ObjectNode) f.catalog.at("/sources/0")).put("sha256", sourceSha);
        ((ObjectNode) f.phenotype.at("/sources/0")).put("sha256", sourceSha).put("locator", "synthetic knowledge paragraph 1");
        f.phenotype.put("expertPolicyStatus", "APPLICABLE_IMPLEMENTED");
        f.phenotype.putObject("specialEvents").put("mode", mode).putArray("sourceIds").add("s");
        f.report.put("schemaVersion", DeNovoStructuredReport.SCHEMA_V2);
        f.report.set("specialEvent", specialEvent(f, mode));
        for (var entry : f.catalog.path("entries")) ((ObjectNode) entry).put("disposition", "SPECIALIZED_IMPLEMENTED");
        if (mode.equals("TISSUE_DIFFERENTIAL_PS2")) ((ObjectNode) f.report.path("clinical")).put("mosaicism", "KNOWN");
        else for (var relation : f.report.path("parentage"))
            ((ObjectNode) relation).put("result", "NOT_TESTED").put("method", "NOT_DONE").put("basis", "SYNTHETIC explicit parentage not tested");
        f.save();
        base.request.put("topN", 1).put("declarationVersion", AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        ((ArrayNode) base.request.path("hypotheses")).remove(1);
        ((ObjectNode) base.request.path("patient")).put("familyContextId", DeNovoBatchFixture.FAMILY);
        ((ObjectNode) base.request.at("/hypotheses/0/hypothesis")).put("hgncId", "HGNC:1748").put("mondoId", "MONDO:0007648").put("moi", "AD");
        base.recreateService(base.runtime());
        base.service = AcmgBusinessService.localBatch(base.service, () -> {
            try {
                var module = f.load(); modules.add(module);
                return new LocalEvidenceBatchRuntime(base.mapper, base.mapper.createObjectNode().put("fixture", "SYNTHETIC_ROUND4_DENOVO_ADOPTION"),
                        null, null, null, null, null, null, null, null, null, null, null, false, module);
            } catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
        });
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
        confirmRecommendation();
    }

    private ObjectNode specialEvent(DeNovoBatchFixture f, String mode) {
        boolean tissue = mode.equals("TISSUE_DIFFERENTIAL_PS2");
        var event = f.mapper.createObjectNode().put("schemaVersion", "denovo-special-event-v1")
                .put("kind", tissue ? "POSTZYGOTIC" : "CONSTITUTIONAL")
                .put("familyId", DeNovoBatchFixture.FAMILY).put("eventId", "SYNTHETIC-local-origin")
                .put("personId", DeNovoBatchFixture.PATIENT).put("probandId", DeNovoBatchFixture.PATIENT)
                .put("sex", "FEMALE").put("ploidy", "DIPLOID").put("genotype", "HETEROZYGOUS").put("affectedStatus", "AFFECTED")
                .put("parentalAvailability", "AVAILABLE").put("otherAffectedMales", "NONE_IN_CHECKED_SCOPE").put("eventOrigin", "UNKNOWN");
        event.putObject("trio").put("personId", DeNovoBatchFixture.PATIENT).put("motherId", "mother").put("fatherId", "father")
                .put("subjectTarget", "PRESENT").put("motherTarget", "ABSENT").put("fatherTarget", "ABSENT")
                .put("maternity", tissue ? "CONFIRMED" : "ASSUMED_NOT_TESTED").put("paternity", tissue ? "CONFIRMED" : "ASSUMED_NOT_TESTED")
                .put("maternityMethod", tissue ? "SNP_PANEL" : "NOT_TESTED").put("paternityMethod", tissue ? "SNP_PANEL" : "NOT_TESTED")
                .put("method", "SANGER").put("targetCoverage", "EXPLICIT_TARGET").put("detectionCapability", "ADEQUATE").put("quality", "VALID");
        var partner = event.putObject("partner").put("status", "NONE_IN_CHECKED_SCOPE").put("phase", "UNKNOWN");
        partner.putNull("allele"); partner.putArray("sourceRefs"); event.putArray("siblings");
        var observations = event.putArray("tissues");
        if (tissue) {
            observations.addObject().put("personId", DeNovoBatchFixture.PATIENT).put("tissueId", "SYNTHETIC-affected").put("affected", true)
                    .put("altReads", 20).put("totalReads", 100).put("limit", 0.01).put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
            observations.addObject().put("personId", DeNovoBatchFixture.PATIENT).put("tissueId", "SYNTHETIC-comparison").put("affected", false)
                    .put("altReads", 0).put("totalReads", 100).put("limit", 0.01).put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
        }
        return event;
    }

    private void confirmRecommendation() {
        var options = base.service.inputOptions(base.analysisId, 1);
        var recommendation = options.at("/candidates/0/recommendation");
        var hypothesis = (ObjectNode) base.request.at("/hypotheses/0/hypothesis");
        hypothesis.set("hypothesisId", recommendation.path("hypothesisId"));
        JsonNode relation = null, annotation = null;
        for (var row : recommendation.path("relationships")) if (row.path("mondoId").equals(hypothesis.path("mondoId"))) relation = row;
        for (var row : recommendation.path("annotations")) if (row.path("annotationId").equals(recommendation.path("recommendedAnnotationId"))) annotation = row;
        assertNotNull(relation, recommendation.toPrettyString());
        hypothesis.set("moi", relation.path("moi"));
        var reference = base.request.putArray("recommendations").addObject();
        for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) reference.set(field, recommendation.path(field));
        reference.set("stableRelationshipKey", relation.path("stableRelationshipKey"));
        if (annotation == null) {
            ((ObjectNode) base.request.at("/hypotheses/0")).remove("transcript"); reference.putNull("annotationId"); return;
        }
        var transcript = (ObjectNode) base.request.at("/hypotheses/0/transcript");
        String accession = annotation.path("versionedTranscriptId").asText(); int dot = accession.lastIndexOf('.');
        transcript.put("accession", accession.substring(0, dot)).put("version", Integer.parseInt(accession.substring(dot + 1)))
                .put("selectionPolicy", recommendation.path("policyId").asText() + "@" + recommendation.path("policyVersion").asText());
        transcript.set("annotationSource", annotation.path("annotationSource")); transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
        ((ObjectNode) transcript.path("source")).put("recordLocator", "recommendations/" + recommendation.path("recommendationId").asText() + "/selection");
        reference.set("annotationId", recommendation.path("recommendedAnnotationId"));
    }

    @ParameterizedTest
    @CsvSource({"TISSUE_DIFFERENTIAL_PS2,PS2", "TISSUE_DIFFERENTIAL_PS2,PM6", "TISSUE_DIFFERENTIAL_PS2,BOTH",
            "SVI_1_1_COMBINED_PS2,PS2", "SVI_1_1_COMBINED_PS2,PM6", "SVI_1_1_COMBINED_PS2,BOTH"})
    void actualLegacyBusinessPreservesBothProofsAndRejectsUnsupportedSingleSelection(String mode, String selection) throws Exception {
        setup(mode);
        if (!selection.equals("BOTH")) {
            base.request.putArray("selectedCriteria").add("ACMG_AMP_2015:" + selection);
            var response = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).contentType("application/json")
                    .content(requireNonNull(base.mapper.writeValueAsBytes(base.request)))).andReturn().getResponse();
            assertEquals(400, response.getStatus());
            assertEquals("ACMG_SELECTION_REQUIRES_CURRENT_WORKBENCH", response.getErrorMessage());
            assertEquals(0, modules.stream().mapToInt(mappedItem -> mappedItem.evaluations()).sum());
            assertEquals(0, modules.stream().mapToInt(mappedItem -> mappedItem.sourceSupplyCalls()).sum());
            assertTrue(base.store.runs(base.analysisId).isEmpty(), "Rejected selection must not create a run or grant an unselected criterion");
            saveActualRejection(mode + "-" + selection, response.getStatus(), response.getErrorMessage());
            return;
        }
        // This legacy entry genuinely supports its default scope; selected runs
        // require the central current-workbench factory and are not bypassed here.
        base.request.remove("selectedCriteria");
        var ids = DeNovoSelectedBatch.CRITERIA;
        var response = base.postRequest(base.request);
        var saved = base.store.run(base.analysisId, response.path("runId").asText()).orElseThrow();
        var privateData = base.mapper.readTree(saved.privateJson());
        var module = privateData.at("/localBatch/modules/deNovo/results/0");
        assertTrue(module.isObject(), privateData.toPrettyString());
        var summary = module.path("publicSummary");
        assertEquals("ADOPTED", summary.at("/adoptionProof/status").asText(), module.toPrettyString());
        assertEquals("COMPLETE", summary.at("/adoptionProof/projectionStatus").asText());
        assertEquals(mode, summary.at("/adoptionProof/policy/eventMode").asText());
        boolean tissue = mode.equals("TISSUE_DIFFERENTIAL_PS2");
        assertEquals(tissue ? "STRONG" : "SUPPORTING", summary.at("/adoptionProof/appliedStrength").asText());
        assertEquals(tissue ? 0 : 2, summary.path("effectiveQuarterPoints").asInt());
        assertEquals(tissue, summary.at("/events/0/confirmedParentage").asBoolean());
        assertEquals(ids.size(), summary.path("specificationSelections").size());
        assertEquals(ids.size(), summary.path("specializedPolicyAdoptions").size());
        assertEquals(ids.size(), summary.path("calculationBindings").size());
        var actualIds = new LinkedHashSet<String>();
        var publicCriteria = response.at("/candidates/0/attempts/0/localBatch/criteria");
        for (var criterion : publicCriteria) {
            String id = criterion.path("criterionId").asText();
            if (!ids.contains(id)) continue;
            actualIds.add(id);
            assertEquals(summary, criterion.at("/details/deNovoFacts"), "Public projection must preserve the exact actual module summary");
            assertEquals("ACMG_AMP_2015:PS2".equals(id) ? "MET" : "NOT_EVALUATED", criterion.path("status").asText());
            assertEquals("ACMG_AMP_2015:PS2".equals(id) ? tissue ? "STRONG" : "SUPPORTING" : "NONE", criterion.path("appliedStrength").asText());
        }
        assertEquals(new LinkedHashSet<>(ids), actualIds, "Both de novo criteria in the legacy default scope must preserve their own actual result");
        assertEquals(ids.size(), module.path("criteria").size());
        assertEquals(1, modules.stream().mapToInt(mappedItem -> mappedItem.evaluations()).sum());
        assertFalse(response.toString().contains("originalsByRawSha256"));
        saveActualSample(mode + "-" + selection, response, module);
    }

    private void saveActualSample(String name, ObjectNode response, JsonNode module) throws Exception {
        String output = System.getProperty("denovo.round4.outputDir", "");
        if (output.isBlank()) return;
        Path directory = Path.of(output).toAbsolutePath().normalize(); Files.createDirectories(directory);
        var sample = base.mapper.createObjectNode().put("evidenceLevel", "SYNTHETIC_PUBLICATION_LOCAL_REPORT_REAL_BUSINESS_AND_H2_SKELETON")
                .put("clinicalApproval", "NONE").put("fullHistoryExportAcceptance", "NOT_RUN");
        sample.set("publicResult", response); sample.set("privateModuleResult", module);
        base.mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("BUSINESS-" + name + ".json").toFile(), sample);
    }

    private void saveActualRejection(String name, int status, String actualReason) throws Exception {
        String output = System.getProperty("denovo.round4.outputDir", "");
        if (output.isBlank()) return;
        Path directory = Path.of(output).toAbsolutePath().normalize(); Files.createDirectories(directory);
        var sample = base.mapper.createObjectNode().put("evidenceLevel", "ACTUAL_LEGACY_BUSINESS_REJECTION_SYNTHETIC_REQUEST")
                .put("centralSelectionIntegration", "NOT_RUN").put("httpStatus", status).put("actualErrorReason", actualReason)
                .put("deNovoEvaluations", modules.stream().mapToInt(mappedItem -> mappedItem.evaluations()).sum())
                .put("sourceSupplyCalls", modules.stream().mapToInt(mappedItem -> mappedItem.sourceSupplyCalls()).sum());
        sample.set("actualRequest", base.request);
        base.mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("BUSINESS-" + name + ".json").toFile(), sample);
    }
}
