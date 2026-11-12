package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real CURRENT/publication/source recovery/HTTP/H2; reference and population transports are synthetic. */
class CandidateAlleleCallBusinessTest {
    @TempDir Path temporary;
    private Bs1KnowledgeBusinessTest population;
    private AcmgBusinessIntegrationTest base;

    @AfterEach void close() { if (base != null) base.closeDatabase(); }

    private void publish(String genotype, boolean withPopulation) throws Exception {
        if (withPopulation) {
            population = new Bs1KnowledgeBusinessTest(); population.temporary = temporary; population.setup();
            base = population.local.fixture.base;
        } else {
            base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        }
        base.publish(false, GenomeAssembly.GRCh38, vcf(
                "chr11\t2\tPRIVATE_RECORD_NOT_IN_SUMMARY\tT\tA\t.\tPASS\t.\tGT\t" + genotype,
                "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t0/1"));
        if (withPopulation) {
            population.local.fixture.wire();
            base.request.putArray("hypotheses");
            population.wire();
        }
    }

    private JsonNode options() throws Exception {
        return base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", "2").param("workflow", "CURRENT")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    @ParameterizedTest @ValueSource(strings = {"0/0", "0/1", "1/.", "./."})
    void normalCurrentReportsTypedSourceFactsWithoutChangingIndependentBs1(String genotype) throws Exception {
        publish(genotype, true);
        var inputs = options();
        var option = inputs.at("/candidates/0");
        var summary = option.path("sampleCallSummary");
        assertEquals("VERIFIED_ORIGINAL_ANALYSIS_SOURCE", option.path("sampleCallAssociationStatus").asText());
        assertEquals("candidate-allele-call-summary-v1", summary.path("schemaVersion").asText());
        if (genotype.equals("./.")) assertTrue(summary.path("targetAltReported").isNull());
        else assertEquals(!genotype.equals("0/0"), summary.path("targetAltReported").asBoolean());
        assertEquals(!genotype.contains("."), summary.path("genotypeComplete").asBoolean());
        assertEquals("GRCh38", summary.at("/source/originalAnalysisAssembly").asText());
        assertTrue(summary.path("usageLimitations").toString().contains("REPORTED_ALT_IS_NOT_CLINICAL_OR_SCIENTIFIC_ADOPTION"));
        assertFalse(summary.toString().contains("PRIVATE_RECORD_NOT_IN_SUMMARY"));
        for (String forbidden : List.of("sampleId", "rawRecord", "score", "strength", "clinicalApproval", "diagnosis"))
            assertFalse(summary.has(forbidden));
        var saved = base.postRequest(base.request);
        var row = saved.at("/candidates/0");
        assertEquals(summary, row.path("sampleCallSummary"));
        assertEquals(option.path("sampleCallAssociationStatus"), row.path("sampleCallAssociationStatus"));
        assertEquals(option.path("sampleCallSourceSnapshotSha256"), row.path("sampleCallSourceSnapshotSha256"));
        assertEquals(saved.path("executionSourceSha256"), row.path("sampleCallSourceSnapshotSha256"));
        boolean bs1Met = false;
        for (var attempt : row.path("attempts"))
            bs1Met |= "MET".equals(attempt.at("/bs1/evaluation/evaluationResult/status").asText());
        assertTrue(bs1Met, "Sample GT does not suppress the independently applicable population evidence: " + row.toPrettyString());
        String queries = Files.readString(temporary.resolve("queries.txt"));
        assertEquals(saved, base.postRequest(base.request));
        String run = saved.path("runId").asText();
        String root = "/variant-analyses/" + base.analysisId + "/acmg/runs/" + run;
        var index = base.mapper.readTree(base.http.perform(get(root + "/view")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(summary, index.at("/candidates/0/sampleCallSummary"));
        // Population-only views deliberately return the complete response. Exercise the
        // same index/background whitelist used by local-batch workbenches directly.
        String viewSha = org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(saved.toString());
        var projectedIndex = AcmgResultViews.index(saved, viewSha);
        assertEquals(summary, projectedIndex.at("/candidates/0/sampleCallSummary"));
        var detail = AcmgResultViews.background(saved, viewSha, row.path("candidateId").asText(), 0);
        assertEquals(summary, detail.at("/candidates/0/sampleCallSummary"));
        // Remove the saved source and rebuild the reader with no scientific runtime.
        // Existing results must remain byte-equivalent; a GET must not recover/reparse source facts.
        new JdbcTemplate(requireNonNull(base.dataSource)).update("DELETE FROM variant_analysis_acmg_source WHERE analysis_id=?", base.analysisId);
        base.closeDatabase(); base.recreateRepositories();
        base.recreateService(() -> { throw new AssertionError("History must not open a scientific runtime"); });
        assertEquals(saved, base.service.read(base.analysisId, run));
        var export = base.mapper.readTree(base.http.perform(get(root)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(saved, export);
        assertEquals(summary, base.mapper.readTree(base.http.perform(get(root + "/view"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).at("/candidates/0/sampleCallSummary"));
        assertEquals(queries, Files.readString(temporary.resolve("queries.txt")));
        artifact(genotype.replace('/', '_').replace('.', 'N'), inputs, saved, export);
    }

    @Test void changedOriginalRecordCannotPublishTrustedCallWhileOtherCandidateRemainsAvailable() throws Exception {
        publish("0/1", false);
        var source = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        ((ObjectNode) source.at("/candidates/0/matchedRecord")).put("recordOrdinal", 99);
        base.replaceSnapshot(source);
        var inputs = options();
        assertFalse(inputs.at("/candidates/0").has("sampleCallSummary"));
        assertFalse(inputs.at("/candidates/0").has("sampleCallAssociationStatus"));
        assertTrue(inputs.at("/candidates/0/blockers").toString().contains("ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED"));
        assertTrue(inputs.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean());
        var saved = base.postRequest(base.request);
        assertFalse(saved.at("/candidates/0").has("sampleCallSummary"));
        assertTrue(saved.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean());
        artifact("source-conflict", inputs, saved, base.service.read(base.analysisId, saved.path("runId").asText()));
    }

    @Test void missingSourceCannotBorrowRankedCallOrInventTrustedFacts() throws Exception {
        publish("0/1", false);
        assertNotNull(base.jobs.findInterpretations(base.analysisId, 0, 2).get(0).getAnalysisVcfCall());
        new JdbcTemplate(requireNonNull(base.dataSource)).update("DELETE FROM variant_analysis_acmg_source WHERE analysis_id=?", base.analysisId);
        var inputs = options();
        for (var row : inputs.path("candidates")) assertFalse(row.has("sampleCallSummary"));
        var saved = base.postRequest(base.request);
        for (var row : saved.path("candidates")) {
            assertFalse(row.has("sampleCallSummary")); assertFalse(row.has("sampleCallAssociationStatus"));
        }
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());
        artifact("source-missing", inputs, saved, base.service.read(base.analysisId, saved.path("runId").asText()));
    }

    @ParameterizedTest @ValueSource(strings = {"record-null", "record-missing", "alt-missing", "alt-wrong", "ordinal-missing",
            "original-allele", "original-assembly", "record-contig", "record-reference", "record-alternate", "call-alt-missing"})
    void typedMatchedCallRequiresItsOwnOriginalRecordAndCompleteAlleleAssociation(String fault) throws Exception {
        publish("0/1", false);
        var source = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        var row = (ObjectNode) source.at("/candidates/0");
        var record = (ObjectNode) row.path("matchedRecord");
        switch (fault) {
            case "record-null" -> row.putNull("matchedRecord");
            case "record-missing" -> row.remove("matchedRecord");
            case "alt-missing" -> record.remove("selectedAltIndex");
            case "alt-wrong" -> record.put("selectedAltIndex", 2);
            case "ordinal-missing" -> record.remove("recordOrdinal");
            case "original-allele" -> ((ObjectNode) row.at("/engineCandidate/observation/key")).put("position", 999);
            case "original-assembly" -> ((ObjectNode) row.at("/engineCandidate/observation/key")).put("genomeAssembly", "GRCh37");
            case "record-contig" -> record.put("contig", "13");
            case "record-reference" -> record.put("reference", "A");
            case "record-alternate" -> record.putArray("alternates").add("G");
            case "call-alt-missing" -> ((ObjectNode) row.at("/engineCandidate/analysisVcfCall")).remove("targetAlleleIndex");
            default -> throw new AssertionError(fault);
        }
        // Deliberately rehash the source: a digest alone cannot establish a missing
        // or incompatible original record/call association.
        base.replaceSnapshot(source);
        var inputs = options();
        assertFalse(inputs.at("/candidates/0").has("sampleCallSummary"), fault);
        assertFalse(inputs.at("/candidates/0").has("sampleCallAssociationStatus"), fault);
        assertTrue(inputs.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean(), fault);
        var saved = base.postRequest(base.request);
        assertFalse(saved.at("/candidates/0").has("sampleCallSummary"), fault);
        assertFalse(saved.at("/candidates/0").has("sampleCallAssociationStatus"), fault);
        assertTrue(saved.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean(), fault);
        artifact(fault, inputs, saved, base.service.read(base.analysisId, saved.path("runId").asText()));
    }

    @Test void ambiguousOriginalVcfCallRetainsUnknownReportWithoutVerifiedAssociation() throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        base.publish(false, GenomeAssembly.GRCh38, vcf(
                "chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t0/1",
                "chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t1/1",
                "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t0/1"));
        var inputs = options();
        var first = inputs.at("/candidates/0");
        assertFalse(first.has("sampleCallAssociationStatus"));
        assertTrue(first.at("/sampleCallSummary/targetAltReported").isMissingNode()
                || first.at("/sampleCallSummary/targetAltReported").isNull());
        assertTrue(inputs.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean());
        var saved = base.postRequest(base.request);
        assertFalse(saved.at("/candidates/0").has("sampleCallAssociationStatus"));
        assertTrue(saved.at("/candidates/0/sampleCallSummary/targetAltReported").isMissingNode()
                || saved.at("/candidates/0/sampleCallSummary/targetAltReported").isNull());
        assertTrue(saved.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean());
        artifact("ambiguous-call", inputs, saved, base.service.read(base.analysisId, saved.path("runId").asText()));
    }

    @Test void originalCandidateCannotBorrowAnotherGenesSameAlleleRecord() throws Exception {
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        base.variantTsvOverride = tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8", "2\t11\t2\tT\tA\tGJB2\t2706\tAR\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t0/1"));
        var source = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        assertEquals(source.at("/candidates/0/matchedRecord"), source.at("/candidates/1/matchedRecord"));
        ((ObjectNode) source.at("/candidates/0")).putNull("matchedRecord");
        base.replaceSnapshot(source);
        var inputs = options();
        assertFalse(inputs.at("/candidates/0").has("sampleCallSummary"));
        assertTrue(inputs.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean());
        var saved = base.postRequest(base.request);
        assertFalse(saved.at("/candidates/0").has("sampleCallSummary"));
        assertTrue(saved.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean());
        artifact("same-allele-other-gene", inputs, saved, base.service.read(base.analysisId, saved.path("runId").asText()));
    }

    @Test void replacedEngineCallCannotTurnPublishedGtZeroIntoTargetAltReported() throws Exception {
        publish("0/0", false);
        var source = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        var call = (ObjectNode) source.at("/candidates/0/engineCandidate/analysisVcfCall");
        call.put("targetAlleleCall", "HET_TARGET").put("candidateRelativeGenotype", "REF/TARGET_ALT");
        assertEquals("HOM_REF", source.at("/candidates/0/published/analysisVcfCall/targetAlleleCall").asText());
        base.replaceSnapshot(source);
        var inputs = options();
        assertFalse(inputs.at("/candidates/0").has("sampleCallSummary"));
        assertFalse(inputs.at("/candidates/0").has("sampleCallAssociationStatus"));
        var saved = base.postRequest(base.request);
        assertFalse(saved.at("/candidates/0").has("sampleCallSummary"));
        assertTrue(saved.at("/candidates/1/sampleCallSummary/targetAltReported").asBoolean());
        artifact("engine-published-call-conflict", inputs, saved, base.service.read(base.analysisId, saved.path("runId").asText()));
    }

    @Test void freshSubmissionDoesNotReuseHistoricalUnboundCallResultWhileOldHistoryStaysReadable() throws Exception {
        publish("0/1", false);
        var snapshot = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
        ((ObjectNode) snapshot.at("/candidates/0")).putNull("matchedRecord"); base.replaceSnapshot(snapshot);
        var saved = base.postRequest(base.request); String oldRun = saved.path("runId").asText();
        var stored = base.store.run(base.analysisId, oldRun).orElseThrow();
        var oldIdentity = (ObjectNode) base.mapper.readTree(stored.requestJson());
        assertEquals("ORIGINAL_CANDIDATE_MATCHED_RECORD_V1", oldIdentity.path("sampleCallSourceAssociationCapability").asText());
        oldIdentity.remove("sampleCallSourceAssociationCapability");
        String oldHash = new org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts(base.mapper).requestHash(oldIdentity);
        var historical = saved.deepCopy(); historical.put("requestSha256", oldHash);
        var row = (ObjectNode) historical.at("/candidates/0");
        // Reconstruct the older publication bug inside this isolated fixture: the
        // immutable history remains readable, but is not authority for a fresh run.
        row.set("sampleCallSummary", base.mapper.valueToTree(CandidateAlleleCallSummary.summarize(
                base.jobs.findInterpretations(base.analysisId, 0, 2).get(0))));
        row.put("sampleCallAssociationStatus", "VERIFIED_ORIGINAL_ANALYSIS_SOURCE");
        String historicalJson = base.mapper.writeValueAsString(historical);
        new JdbcTemplate(requireNonNull(base.dataSource)).update("UPDATE variant_analysis_acmg_run SET request_json=?,request_sha256=?,response_json=?,response_sha256=? WHERE run_id=?",
                base.mapper.writeValueAsString(oldIdentity), oldHash, historicalJson,
                org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(historicalJson), oldRun);
        assertEquals(historical, base.service.read(base.analysisId, oldRun));
        assertTrue(historical.at("/candidates/0/sampleCallSummary/targetAltReported").asBoolean());
        int before = base.invocations.get();
        var fresh = base.postRequest(base.request);
        assertNotEquals(oldRun, fresh.path("runId").asText());
        assertNotEquals(oldHash, fresh.path("requestSha256").asText());
        assertTrue(base.invocations.get() > before, "New capability must execute instead of returning old cache");
        assertFalse(fresh.at("/candidates/0").has("sampleCallSummary"));
        assertEquals(fresh, base.postRequest(base.request));
        assertEquals(historical, base.service.read(base.analysisId, oldRun));
        artifact("old-cache-new-capability", options(), fresh, base.service.read(base.analysisId, fresh.path("runId").asText()));
    }

    @Test void legacySavedResultWithoutCallSummaryStaysAbsentInReadViewsAndExport() throws Exception {
        publish("0/1", false);
        var saved = base.postRequest(base.request);
        var legacy = saved.deepCopy();
        for (var row : legacy.path("candidates")) ((ObjectNode) row).remove(List.of("sampleCallSummary", "sampleCallAssociationStatus", "sampleCallSourceSnapshotSha256"));
        String run = saved.path("runId").asText(), raw = base.mapper.writeValueAsString(legacy);
        new JdbcTemplate(requireNonNull(base.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=?,response_sha256=? WHERE run_id=?",
                raw, org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(raw), run);
        assertEquals(legacy, base.service.read(base.analysisId, run));
        var view = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}/view", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        for (var row : view.path("candidates")) assertFalse(row.has("sampleCallSummary"));
        assertEquals(legacy, base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
    }

    private void artifact(String scenario, JsonNode options, JsonNode saved, JsonNode exported) throws Exception {
        String output = System.getProperty("varvis.sample.call.output", "");
        if (output.isBlank()) return;
        Path folder = Path.of(output).resolve(scenario); Files.createDirectories(folder);
        Files.write(folder.resolve("options.json"), base.mapper.writeValueAsBytes(options));
        Files.write(folder.resolve("saved.json"), base.mapper.writeValueAsBytes(saved));
        Files.write(folder.resolve("export.json"), base.mapper.writeValueAsBytes(exported));
    }
}
