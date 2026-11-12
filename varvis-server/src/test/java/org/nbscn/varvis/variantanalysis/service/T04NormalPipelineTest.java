package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;

/** Native local-runtime chain, actual configured source modules and actual offline Python; no clinical fixture claims. */
@EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
@EnabledIfSystemProperty(named = "varvis.pp1.python", matches = ".+")
class T04NormalPipelineTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;
    private static final String PP1 = "ACMG_AMP_2015:PP1";
    static Path python() { return Path.of(System.getProperty("varvis.pp1.python")); }
    static Path scripts() {
        Path here = Path.of("").toAbsolutePath(); Path scripts = here.resolve("scripts/denovo");
        if (!Files.isDirectory(scripts)) scripts = here.getParent().resolve("scripts/denovo");
        assertTrue(Files.isDirectory(scripts)); return scripts;
    }
    private T04NormalPipelineFixture fixture(String name, List<String> criteria, String mode, boolean source) throws Exception {
        return new T04NormalPipelineFixture(temporary.resolve(name), python(), scripts(), criteria, mode, source, null);
    }
    private static JsonNode evaluation(ObjectNode result) { return result.at("/modules/pp1/results/0/publicSummary/evaluation"); }

    private static String diagnostic(T04NormalPipelineFixture fixture) {
        var out = fixture.json.createObjectNode().put("debugDirectory", fixture.debugDirectory().toString());
        var audit = fixture.consumerAudit(); var inventory = fixture.inventoryAudit();
        out.set("deNovoRegistry", audit.at("/registry/deNovo").deepCopy());
        // Identity/provenance bodies remain in private files; terminal codes alone are bounded log diagnostics.
        ((ObjectNode) out.path("deNovoRegistry")).remove("identity");
        var actual = audit.at("/attempts/0/moduleResults/deNovo"); var terminals = out.putObject("actualDeNovo");
        for (String code : List.of("PS2", "PM6")) {
            terminals.putObject(code).put("status", actual.at("/criteria/" + code + "/evaluationResult/status").asText())
                    .put("strength", actual.at("/criteria/" + code + "/appliedStrength").asText())
                    .put("reasonCode", actual.at("/criteria/" + code + "/evaluationResult/reasonCode").asText());
        }
        out.put("actualCoreReason", actual.at("/qualifiedResult/result/reasonCode").asText());
        var qualificationCodes = out.putArray("actualQualificationCodes");
        int qualifiers = 0;
        for (var qualification : actual.at("/qualifiedResult/qualifications")) {
            if (qualifiers++ >= 4) break;
            int codes = 0; for (var code : qualification.path("reasons")) { if (codes++ >= 6) break; qualificationCodes.add(code.asText()); }
        }
        out.put("grantReason", inventory.at("/attempts/0/terminalReceipt/currentUseGrant/reasonCode").asText());
        out.set("consumerClosure", inventory.at("/attempts/0/terminalReceipt/currentUseGrant/consumerClosure").deepCopy());
        return out.toString();
    }
    @Test void actualInheritedConsumerAndPythonPositivePreserveSingleAndRelatedPublicScopeAndReplayOnce() throws Exception {
        for (var criteria : List.of(List.of(PP1), List.of(PP1, "ACMG_AMP_2015:PS2", "ACMG_AMP_2015:PM6"))) {
            try (var f = fixture("positive-" + criteria.size(), criteria, "positive", true)) {
                var result = f.execute(); var pp1 = evaluation(result); var inventory = f.inventoryAudit();
                assertTrue(inventory.path("complete").asBoolean(), () -> diagnostic(f));
                JsonNode receipt = inventory.at("/attempts/0/terminalReceipt");
                assertEquals("NOT_SELECTED", receipt.path("kind").asText()); assertEquals("0", receipt.path("finalPoints").asText());
                assertTrue(receipt.path("sourceResultSha256").isNull()); assertTrue(receipt.path("evaluationSha256").isNull());
                assertEquals("MET", receipt.at("/coreExecution/actualStatus").asText());
                assertEquals("MODERATE", receipt.at("/coreExecution/appliedStrength").asText());
                assertEquals("0", receipt.at("/currentAdoption/awardedPoints").asText());
                assertTrue(receipt.at("/currentAdoption/allocatedFactIds").isEmpty());
                assertEquals("RESERVED_NOT_AWARDED", receipt.at("/currentUseGrant/status").asText());
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                        () -> f.json.treeToValue(receipt.path("currentUseGrant"), CurrentClinicalAllocation.Grant.class));
                assertTrue(receipt.at("/currentUseGrant/awardedPoints").isNull());
                assertFalse(receipt.at("/currentUseGrant/reservedFactIds").isEmpty());
                assertEquals(1, receipt.at("/currentUseGrant/nativeCallProof/sampleOrdinal").asInt());
                assertEquals(1, receipt.at("/currentUseGrant/nativeCallProof/recordOrdinal").asInt());
                assertEquals(1, receipt.at("/currentUseGrant/nativeCallProof/targetAlleleIndex").asInt());
                assertEquals("0/1", receipt.at("/currentUseGrant/nativeCallProof/genotype").asText());
                assertEquals("PASS", receipt.at("/currentUseGrant/nativeCallProof/sampleFilter").asText());
                assertEquals("KNOWN_EMPTY", pp1.at("/ledger/status").asText()); assertEquals("0", pp1.at("/ledger/pp4Points").asText());
                assertEquals("5", pp1.path("remainingLocusCapacity").asText());
                // Independently: two concordant sibling genotypes each have 3/4 under the causal model vs 1/2 under null.
                // BF=(3/4)^2/(1/2)^2=9/4; (52/25)^1 <= 9/4 < (52/25)^2 => exactly one PP1 point.
                assertEquals("9", pp1.at("/bayesFactor/numerator").asText()); assertEquals("4", pp1.at("/bayesFactor/denominator").asText());
                assertEquals("MET", pp1.path("status").asText()); assertEquals("SUPPORTING", pp1.path("strength").asText());
                assertEquals(1, pp1.path("awardedPP1Points").asInt(-1));
                assertEquals(criteria.size(), result.at("/results/0/localBatch/criteria").size(), "dependency execution must not create public evidence rows");
                assertEquals(1, f.callbacks.get()); assertEquals(1, f.pp1Source.evaluations()); assertEquals(1, f.deNovo.evaluations());
                assertEquals("NOT_MET", result.at("/modules/deNovo/results/0/criteria/PS2/evaluationResult/status").asText());
                assertEquals("NOT_MET", result.at("/modules/deNovo/results/0/criteria/PM6/evaluationResult/status").asText());
                var archiveBeforeReuse = f.archive(); var inventoryBeforeReuse = f.inventoryAudit();
                var again = f.execute();
                assertEquals("EXECUTED", result.path("executionMode").asText());
                assertEquals("REQUEST_RESULT_REUSE", again.path("executionMode").asText());
                assertEquals(0, again.path("newProviderInvocations").asInt(-1)); assertEquals(0, again.path("newEvaluatorInvocations").asInt(-1));
                var firstScience = result.deepCopy(); var reusedScience = again.deepCopy();
                firstScience.remove(List.of("executionMode", "newProviderInvocations", "newEvaluatorInvocations"));
                reusedScience.remove(List.of("executionMode", "newProviderInvocations", "newEvaluatorInvocations"));
                assertEquals(firstScience, reusedScience, "only the three established transport reuse markers may differ");
                assertEquals(archiveBeforeReuse, f.archive()); assertEquals(inventoryBeforeReuse, f.inventoryAudit());
                assertEquals(1, f.callbacks.get()); assertEquals(1, f.pp1Source.evaluations()); assertEquals(1, f.deNovo.evaluations());
                var expanded = CurrentClinicalArchive.verifyAndExpand(f.json, f.archive(), inventory, f.nativeFixture.analysisId(), f.nativeFixture.runId());
                assertEquals(12, f.archive().path("rawArtifacts").size(), "nine originals plus actual input/result/evaluation bytes once");
                assertEquals(12, expanded.path("rawBindings").size());
            }
        }
    }

    @Test void anActualNegativeFamilyIsZeroWhileIncompleteFormalParametersRemainUnresolved() throws Exception {
        for (String mode : List.of("negative", "missing")) {
            try (var f = fixture(mode, List.of(PP1), mode, true)) {
                var result = f.execute(); var pp1 = evaluation(result);
                assertTrue(f.inventoryAudit().path("complete").asBoolean());
                assertEquals("NOT_SELECTED", f.inventoryAudit().at("/attempts/0/terminalReceipt/kind").asText());
                assertEquals("0", f.inventoryAudit().at("/attempts/0/terminalReceipt/finalPoints").asText());
                assertEquals("KNOWN_EMPTY", pp1.at("/ledger/status").asText());
                assertEquals(0, pp1.path("awardedPP1Points").asInt(-1));
                if (mode.equals("negative")) {
                    // An affected noncarrier has posterior1/4 vs null1/2: independent BF1/2, not pathogenic PP1 evidence.
                    assertEquals("1", pp1.at("/bayesFactor/numerator").asText()); assertEquals("2", pp1.at("/bayesFactor/denominator").asText());
                    assertEquals("NOT_MET", pp1.path("status").asText());
                } else {
                    assertEquals("NOT_EVALUATED", pp1.path("status").asText());
                    assertTrue(pp1.path("needs").findValuesAsText("reasonCode").contains("PP1_FORMAL_DOSAGE_PROBABILITIES_REQUIRED"));
                }
            }
        }
    }

    @Test void missingActualConsumerTerminalLeavesUnknownAndDoesNotTurnAnUnselectedDependencyIntoZero() throws Exception {
        try (var f = fixture("missing-consumer", List.of(PP1), "positive", false)) {
            var pp1 = evaluation(f.execute()); var inventory = f.inventoryAudit();
            assertFalse(inventory.path("complete").asBoolean());
            assertEquals("PP1_CURRENT_CLINICAL_CONSUMER_TERMINAL_INCOMPLETE", inventory.at("/attempts/0/reasonCode").asText());
            assertEquals("UNAVAILABLE", inventory.at("/attempts/0/terminalReceipt/kind").asText());
            assertTrue(inventory.at("/attempts/0/terminalReceipt/finalPoints").isNull());
            assertFalse(inventory.at("/attempts/0/terminalReceipt").has("coreExecution"));
            assertEquals("UNKNOWN", pp1.at("/ledger/status").asText()); assertTrue(pp1.at("/ledger/pp4Points").isNull());
            assertEquals("NOT_EVALUATED", pp1.path("status").asText()); assertEquals(0, pp1.path("awardedPP1Points").asInt(-1));
        }
    }

    @Test void sameAlleleCannotBorrowAnotherSampleOrOrdinalAndTamperedCallProjectionCannotAward() throws Exception {
        for (String mutation : List.of("sample", "sampleOrdinal", "sampleFT", "recordOrdinal", "altIndex", "genotype", "filter")) {
            try (var f = new T04NormalPipelineFixture(temporary.resolve("tamper-" + mutation), python(), scripts(), List.of(PP1), "positive", true, null,
                    original -> {
                        if (mutation.equals("sample")) original.set("CLINICAL", "sample_links", 0, "vcf_sample_id", "another-sample-at-same-allele");
                        if (mutation.equals("sampleOrdinal")) original.set("CLINICAL", "sample_links", 0, "vcf_sample_ordinal", "2");
                        if (mutation.equals("sampleFT")) original.set("CLINICAL", "case", 0, "sample_filter", "SOURCE_FILTER_FAIL");
                    }, false, nativeFixture -> {
                        if (!List.of("sample", "sampleOrdinal", "sampleFT").contains(mutation)) {
                            var candidate = nativeFixture.ranked().get(0); var mapper = new ObjectMapper();
                            ObjectNode call = mapper.valueToTree(candidate.getAnalysisVcfCall());
                            if (mutation.equals("recordOrdinal")) call.put("matchedRecordOrdinal", 2);
                            if (mutation.equals("altIndex")) call.put("targetAlleleIndex", 2);
                            if (mutation.equals("genotype")) {
                                // Keep the caller projection internally well typed so the actual
                                // published-source comparison, rather than its JSON constructor, rejects it.
                                call.put("candidateRelativeGenotype", "TARGET_ALT/TARGET_ALT").put("targetAlleleCall", "HOM_TARGET");
                            }
                            if (mutation.equals("filter")) call.put("filterEvaluation", "FILTERED");
                            try { nativeFixture.replaceRankedForTamperTest(0, candidate.withAnalysisVcfCall(mapper.treeToValue(call, AnalysisVcfCall.class))); }
                            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException(invalid); }
                        }
                    })) {
                var pp1 = evaluation(f.execute()); var inventory = f.inventoryAudit();
                assertFalse(inventory.path("complete").asBoolean(), mutation);
                assertTrue(inventory.at("/attempts/0/terminalReceipt/finalPoints").isNull(), mutation);
                assertFalse(inventory.at("/attempts/0/terminalReceipt").has("coreExecution"), mutation);
                String actualReason = inventory.at("/attempts/0/reasonCode").asText();
                if (List.of("sample", "sampleOrdinal").contains(mutation))
                    assertEquals("PP1_CURRENT_CLINICAL_ORIGINAL_SAMPLE_LINK_UNPROVEN", actualReason, mutation);
                else if (mutation.equals("sampleFT")) assertEquals("PP1_CURRENT_CLINICAL_ORIGINAL_CALL_MISMATCH", actualReason, mutation);
                else {
                    // The normal published-source recoverer can reject the altered ranked call first.
                    // If recovery retained it, the current native source must reject the published candidate mismatch.
                    assertTrue(java.util.Set.of("PP1_CURRENT_CLINICAL_CANDIDATE_SOURCE_BLOCKED",
                            "PP1_CURRENT_CLINICAL_PUBLISHED_CANDIDATE_MISMATCH").contains(actualReason),
                            "unexpected source rejection layer for " + mutation + ": " + actualReason);
                }
                assertEquals("UNKNOWN", pp1.at("/ledger/status").asText(), mutation); assertTrue(pp1.at("/ledger/pp4Points").isNull(), mutation);
                assertEquals(0, pp1.path("awardedPP1Points").asInt(-1), mutation);
            }
        }
    }
    @Test void duplicateCurrentClinicalFactsWithholdEveryParticipantInsteadOfChoosingATraversalWinner() throws Exception {
        try (var f = new T04NormalPipelineFixture(temporary.resolve("duplicate-current-facts"), python(), scripts(), List.of(PP1),
                "positive", true, null, ignored -> { }, true)) {
            var result = f.execute(); var inventory = f.inventoryAudit();
            assertEquals(2, inventory.path("attempts").size()); assertFalse(inventory.path("complete").asBoolean());
            for (var attempt : inventory.path("attempts")) {
                assertEquals("PP1_CURRENT_CLINICAL_FACT_ALLOCATION_CONFLICT", attempt.path("reasonCode").asText());
                assertTrue(attempt.path("pp4Points").isNull()); assertTrue(attempt.at("/terminalReceipt/finalPoints").isNull());
                assertFalse(attempt.path("terminalReceipt").has("coreExecution"));
            }
            for (var pp1 : result.at("/modules/pp1/results")) {
                assertEquals("UNKNOWN", pp1.at("/publicSummary/evaluation/ledger/status").asText());
                assertEquals(0, pp1.at("/publicSummary/evaluation/awardedPP1Points").asInt(-1));
            }
        }
    }    @Test void writerExitsBeforeAnotherJvmReadsAndExpandsActualNormalArchiveWithoutReplayingTheAnalysis() throws Exception {
        Path directory = temporary.resolve("separate-normal-history"); Files.createDirectories(directory);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var base = new java.util.ArrayList<String>(List.of(javaExecutable, "-Dvarvis.t04.guidance-xml=" + System.getProperty("varvis.t04.guidance-xml"),
                "-cp", classpath, T04NormalHistoryProcess.class.getName()));
        var writer = new java.util.ArrayList<>(base); writer.addAll(List.of("write", directory.toString(), python().toString(), scripts().toString()));
        runChild(writer, directory.resolve("writer.log"));
        assertTrue(Files.isRegularFile(directory.resolve("business-db.mv.db"))); assertFalse(Files.exists(directory.resolve("business-db.lock.db")));
        Path evaluations = directory.resolve("work/pp1-project/local/work/pp1-evaluations");
        long before; try (var folders = Files.list(evaluations)) { before = folders.filter(Files::isDirectory).count(); }
        assertEquals(1, before);
        var reader = new java.util.ArrayList<>(base); reader.addAll(List.of("read", directory.toString()));
        runChild(reader, directory.resolve("reader.log"));
        assertTrue(Files.isRegularFile(directory.resolve("full-export.json")));
        try (var folders = Files.list(evaluations)) { assertEquals(before, folders.filter(Files::isDirectory).count(), "history must not launch Python again"); }
        assertFalse(Files.exists(directory.resolve("business-db.lock.db")));
    }
    private static void runChild(List<String> command, Path log) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
            while (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) assertTrue(System.nanoTime() < deadline, "owned test subprocess timeout: " + log);
            assertEquals(0, process.exitValue(), Files.readString(log)); assertFalse(process.isAlive());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly()); process.destroyForcibly();
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }    @Test void aCompleteExplicitPhenotypeFailureRetainsOriginalCoreNeButEstablishesNoActualPp4Adoption() throws Exception {
        try (var f = new T04NormalPipelineFixture(temporary.resolve("known-phenotype-no-adoption"), python(), scripts(), List.of(PP1),
                "positive", true, null, source -> source.set("CLINICAL", "observations", 0, "presence", "ABSENT"))) {
            var pp1 = evaluation(f.execute()); var inventory = f.inventoryAudit();
            assertTrue(inventory.path("complete").asBoolean(), () -> diagnostic(f));
            var receipt = inventory.at("/attempts/0/terminalReceipt");
            assertEquals("NOT_SELECTED", receipt.path("kind").asText()); assertEquals("0", receipt.path("finalPoints").asText());
            assertEquals("NOT_EVALUATED", receipt.at("/coreExecution/actualStatus").asText());
            assertEquals("PP4_PHENOTYPE_REQUIREMENT_NOT_MET", receipt.at("/coreExecution/actualReasonCode").asText());
            assertEquals("KNOWN_EMPTY", pp1.at("/ledger/status").asText()); assertEquals("0", pp1.at("/ledger/pp4Points").asText());
            assertEquals("MET", pp1.path("status").asText()); assertEquals(1, pp1.path("awardedPP1Points").asInt(-1));
        }
    }}
