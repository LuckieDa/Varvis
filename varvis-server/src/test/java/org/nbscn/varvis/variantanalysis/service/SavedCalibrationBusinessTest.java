package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Normal synthetic publication/raw/association + real H2 calibration claim and shared archive gate. */
class SavedCalibrationBusinessTest {
    @TempDir Path temp;
    final Path repo = Path.of(System.getProperty("varvis.repo", "..")).toAbsolutePath().normalize();
    SavedPredictionAssociationBusinessTest fixture;
    AcmgBusinessIntegrationTest base;
    PredictionLocalResourceBinding local;
    ObjectNode child, declaration, evidence, contract;
    SavedCalibrationBusiness runtime;
    Map<String, Path> paths = new LinkedHashMap<>();
    String write(Path path, JsonNode value) throws Exception {
        String json = base.mapper.writeValueAsString(value); Files.writeString(path, json); return AcmgBusinessStore.sha(json);
    }
    String hash(JsonNode value) { return new AcmgBusinessArtifacts(base.mapper).requestHash(value); }
    void seal(ObjectNode value, String field) { value.remove(field); value.put(field, hash(value)); }
    @BeforeEach void setup() throws Exception {
        fixture = new SavedPredictionAssociationBusinessTest() {
            @Override PredictionDevelopmentRuntime runtime() {
                try {
                    SavedCalibrationBusinessTest.this.base = this.base;
                    if (local == null) {
                        var manifest = base.mapper.createObjectNode().put("schemaVersion", "prediction-local-binding-v1")
                                .put("purpose", "NONCOMMERCIAL_LOCAL_RESEARCH_PUBLIC_CONTROLS_ONLY");
                        var resourceNode = manifest.putObject("resource").put("model", "ALPHAMISSENSE").put("dataVersion", "2023-08-03")
                                .put("sourceLocator", "https://storage.googleapis.com/dm_alphamissense/AlphaMissense_hg38.tsv.gz")
                                .put("sha256", "1".repeat(64)).put("indexSha256", "2".repeat(64)).put("assembly", "GRCh38")
                                .put("scoreField", "am_pathogenicity").put("scorePrecision", "ORIGINAL_DECIMAL_STRING").put("calibrationCompatibility", "UNVERIFIED");
                        resourceNode.putObject("license").put("purpose", "DECLARED_RESEARCH").put("status", "PERMITTED")
                                .put("sourceLocator", "synthetic:license").put("textSha256", "3".repeat(64));
                        var controls = manifest.putArray("controls");
                        for (var c : base.jobs.findInterpretations(base.analysisId, 0, 2)) controls.add(base.mapper.valueToTree(c.getObservation().getKey()));
                        manifest.putObject("verification").putArray("rawHeader").add("#CHROM\tPOS\tREF\tALT\tgenome\tuniprot_id\ttranscript_id\tprotein_variant\tam_pathogenicity\tam_class");
                        Path file = temp.resolve("binding.json"); local = PredictionLocalResourceBinding.load(base.mapper, file, write(file, manifest));
                    }
                    resource = local.identity();
                    var resources = base.mapper.createObjectNode(); resources.set("ALPHAMISSENSE", resource);
                    return new PredictionDevelopmentRuntime(base.mapper, PredictionDevelopmentRuntime.load(base.mapper, "prediction-core-policy-v1.json"),
                            PredictionDevelopmentRuntime.load(base.mapper, "prediction-am-calibration-v1.json"), resources, query -> {
                        lookups.incrementAndGet(); var key = query.path("queryAllele");
                        var out = base.mapper.createObjectNode().put("schemaVersion", "prediction-observation-v1").put("status", "HIT")
                                .put("reasonCode", "RAW_RECORDS_OBTAINED").put("referenceVerified", true).put("transportAttempts", 1)
                                .put("observedAt", "2026-01-01T00:00:00Z").put("scoreOrigin", "REAL_LOCAL_PRECOMPUTED_RESOURCE");
                        for (String field : List.of("queryAllele", "resource", "dataClass")) out.set(field, query.path(field));
                        var rows = out.putArray("rows");
                        for (var c : base.jobs.findInterpretations(base.analysisId, 0, 2)) if (base.mapper.valueToTree(c.getObservation().getKey()).equals(key)) {
                            var tx = c.getTranscriptAnnotations().get(0);
                            String score = rows.isEmpty() && "11".equals(key.path("chromosome").asText()) ? "0.2229" : "0.1558";
                            rows.addObject().put("sourceRow", String.join("\t", "chr" + key.path("chromosome").asText(), key.path("position").asText(),
                                    key.path("reference").asText(), key.path("alternate").asText(), "hg38", "SYNTHETIC", tx.getVersionedTranscriptId(), tx.getAminoAcidChange(), score, "fixture"))
                                    .put("transcriptId", tx.getVersionedTranscriptId()).put("proteinVariant", tx.getAminoAcidChange()).put("uniprotId", "SYNTHETIC")
                                    .put("rawScore", score).put("rawClass", "fixture");
                        }
                        seal(out, "observationSha256"); return out;
                    }, true);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
        fixture.temporary = temp; fixture.isoPublicationDates = true;
        fixture.beforePublication = b -> {
            // Deliberately fabricated single-consequence annotations for tiny synthetic coordinates.
            // Do not normalize the retained real annotations to make the scientific gate pass.
            b.fixture.annotations = Map.of("public-hbb-compatible-e7v", List.of(syntheticAnnotation(b, "HBB", "3043", "ENST00000000001.1")),
                    "public-gjb2-20189473-ct", List.of(syntheticAnnotation(b, "GJB2", "2706", "ENST00000000002.1")));
            b.syntheticPublicationEnrichment = rows -> {
                for (var row : rows) {
                    // VCF_INFO is retained in the full payload; the real summary mapper reconstructs
                    // the relational CADD display under the configured resource version.
                    row.addEvidence(List.of(new EvidenceItem("CADD", "VCF_INFO", "COMPUTATIONAL", "APPLICABLE",
                            14.4, "Original CADD", "Synthetic full evidence", Map.of("rawScore", 0.4, "originalDetail", "retained"))));
                    row.applyRanking(row.getPriorityTier(), row.getRankingScore(), Map.of("synthetic", 2.0),
                            Map.of("synthetic", new ScoreBreakdownComponent(2, List.of("full explanation"))), "full tier reason",
                            List.of("positive"), List.of(), List.of("full rationale"), List.of("full warning"), List.of());
                }
            };
        };
        fixture.setup(); base = fixture.base;
        child = fixture.submit();
        var execution = base.store.source(base.analysisId).orElseThrow(); var snapshot = base.mapper.readTree(execution.json());
        var sourceDir = repo.resolve("docs/execution/pp3-calibration-source-20260918");
        contract = PredictionCalibration.loadStandard(base.mapper, Files.readAllBytes(repo.resolve("varvis-server/src/main/resources/acmg/prediction-standard-offline/am-standard-1.0.0.json")),
                "20d9dfe35874fe4b90370fd26ad0259dc9821b87b0084893a7fa79888992b489", Files.readAllBytes(sourceDir.resolve("discounted_comparator_tools_w3pts_bootstrap10k_thrs.txt")), Files.readAllBytes(sourceDir.resolve("results-README.txt")));
        evidence = base.mapper.createObjectNode().put("schemaVersion", "prediction-standard-resource-compatibility-v2").put("overallStatus", "PARTIAL")
                .put("authorFullMappingAndAggregationReproduced", false).put("analysisId", base.analysisId).put("parentRunId", fixture.parent.path("runId").asText())
                .put("childRunId", child.path("runId").asText()).put("executionSourceSha256", execution.sha256())
                .put("calibrationId", contract.path("calibrationId").asText()).put("calibrationVersion", contract.path("version").asText()).put("calibrationCanonicalSha256", hash(contract));
        evidence.putObject("localAdoption").put("id", "am-exact-single-row-local-adoption-20260918").put("version", "1.0.0").put("scope", "EXACT_SINGLE_ROW").put("status", "VERIFIED");
        evidence.putObject("mappingSource").put("sha256", "14143482fe4611f45837b7917cd9e5e07a8ba18229d28377d11ba9d20d4afde6").put("page", 2)
                .put("url", "https://www.ccs.neu.edu/home/radivojac/papers/bergquist_genetmed_2025_supp.pdf");
        evidence.set("runtimeResourceIdentity", local.identity());
        for (String fact : List.of("modelAndScore", "productAndDerivation", "purpose")) evidence.withObject("/checks").putObject(fact)
                .put("status", "PASS").put("sha256", fact.equals("purpose") ? "3".repeat(64) : local.identity().path("localBindingSha256").asText());
        var allowed = evidence.putArray("allowedObservations"); var frozen = base.mapper.createObjectNode(); var selections = frozen.putArray("selections");
        var candidates = base.mapper.createObjectNode().put("analysisId", base.analysisId); candidates.set("variants", summaries());
        for (var row : snapshot.path("candidates")) {
            String id = row.path("candidateId").asText(); var p = SavedCalibrationBusiness.prediction(child, id); var raw = SavedCalibrationBusiness.prediction(fixture.parent, id);
            allowed.addObject().put("candidateId", id).put("childResultId", p.path("resultId").asText()).put("parentResultId", raw.path("resultId").asText())
                    .put("observationSha256", p.at("/observation/observationSha256").asText()).put("selectionCanonicalSha256", hash(p.path("selection")));
            var f = selections.addObject().put("candidateId", id); var selected = f.putObject("selected");
            selected.set("annotationId", p.at("/selection/selectedAnnotationId")); selected.set("annotationSourceSha256", p.at("/selection/annotationSourceSha256"));
            selected.set("versionedTranscriptId", p.at("/selection/versionedTranscriptId"));
            for (var option : fixture.options.path("candidates")) if (id.equals(option.path("candidateId").asText()))
                f.set("actualSavedAnnotation", row.at("/published/transcriptAnnotations").get(option.at("/associationOptions/0/annotationIndex").asInt()));
        }
        var job = base.mapper.createObjectNode().put("id", base.analysisId).put("selectedSampleId", fixture.request.path("sampleId").asText())
                .put("genomeAssembly", "GRCh38").put("analysisAssembly", "GRCh38").put("liftoverApplied", false).put("liftoverStatus", "NOT_APPLICABLE");
        var files = Map.of("parent", fixture.parent, "child", child, "request", fixture.request, "selections", frozen, "job", job, "candidates", candidates);
        for (var entry : files.entrySet()) { var path = temp.resolve(entry.getKey() + ".json"); paths.put(entry.getKey(), path); evidence.withObject("/inputArtifacts").put(entry.getKey(), write(path, entry.getValue())); }
        configure();
        declaration = base.mapper.createObjectNode().put("topN", 2).put("declarationVersion", SavedCalibrationBusiness.DECLARATION)
                .put("sampleId", fixture.request.path("sampleId").asText()).put("dataClass", "SYNTHETIC").putNull("patient");
        declaration.putArray("hypotheses"); declaration.set("calibration", runtime.reference());
    }
    static org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence syntheticAnnotation(AcmgBusinessIntegrationTest b, String gene, String id, String tx) {
        var node = b.mapper.createObjectNode().put("geneSymbol", gene).put("entrezGeneId", id).put("geneId", id)
                .put("transcriptAccession", tx).put("transcriptId", tx).put("transcriptSource", "Ensembl")
                .put("hgvsCoding", tx + ":c.5C>T").put("hgvsProtein", "ENSP00000000001.1:p.Ala2Val")
                .put("consequence", "missense_variant").put("proteinPosition", "2").put("aminoAcids", "A/V")
                .put("source", "VEP").put("status", "COMPLETED").put("version", "116.0").put("timestamp", "2026-01-01T00:00:00Z");
        return b.mapper.convertValue(node, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class);
    }
    void configure() throws Exception {
        var path = temp.resolve("adoption.json"); var binding = PredictionResourceCompatibility.StandardBinding.loadExact(base.mapper, path, write(path, evidence),
                repo.resolve("docs/execution/pp3-exact-record-20260918/Bergquist_2025_supplement_author_copy_full.pdf"));
        runtime = new SavedCalibrationBusiness(base.mapper, binding, local, contract, paths, child.path("requestSha256").asText(), fixture.request.path("sampleId").asText());
        base.service = AcmgBusinessService.savedPredictionCalibration(fixture.original, runtime);
    }
    @AfterEach void close() { if (fixture != null) fixture.close(); }
    JsonNode summaries() { return base.mapper.valueToTree(base.jobs.findInterpretationSummaries(base.analysisId, 0, 2,
            VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty())); }
    ObjectNode submit() throws Exception { return base.service.submit(base.analysisId, base.mapper.writeValueAsBytes(declaration)); }
    @Test void syntheticFixturePassesUnmodifiedSharedGate() throws Exception {
        var reader = runtime.load();
        for (String candidate : runtime.candidates()) assertNotNull(reader.prepare(candidate));
    }
    @Test void completePairRepeatReverseOrderRestartAndMissingArchivesUseSavedResult() throws Exception {
        var old = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2));
        var out = submit(); assertEquals("COMPLETED_WITH_BLOCKERS", out.path("status").asText(), out.toPrettyString());
        assertEquals(2, out.path("calibrationComparisons").asInt()); assertEquals(2, out.path("calibratedMechanisms").asInt());
        var first = SavedCalibrationBusiness.prediction(out, runtime.candidates().get(0));
        assertEquals("APPLICATION", first.at("/stages/firstUnresolvedStage").asText());
        for (String id : runtime.candidates()) {
            var p = SavedCalibrationBusiness.prediction(out, id); var prior = SavedCalibrationBusiness.prediction(child, id);
            for (String field : List.of("observation", "selection", "association", "associationInput", "criteria")) assertEquals(prior.path(field), p.path(field));
            assertNotEquals(prior.path("resultId"), p.path("resultId")); assertEquals("NONE", p.at("/calibration/appliedStrength").asText());
            boolean uninformative = "0.2229".equals(p.at("/calibration/rawScore").asText());
            assertEquals(uninformative ? "UNINFORMATIVE" : "BENIGN", p.at("/calibration/direction").asText());
            assertEquals(uninformative ? "NONE" : "SUPPORTING", p.at("/calibration/calibratedStrength").asText());
        }
        assertEquals(out, submit());
        var refs = declaration.withObject("/calibration").putArray("results"); var originals = runtime.reference().path("results"); refs.add(originals.get(1)); refs.add(originals.get(0));
        assertEquals(out, submit());
        configure(); for (Path path : paths.values()) Files.delete(path);
        assertEquals(out, submit()); assertEquals(out, base.service.read(base.analysisId, out.path("runId").asText()));
        assertEquals(child, base.service.read(base.analysisId, child.path("runId").asText())); assertEquals(2, fixture.lookups.get());
        assertEquals(old, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2)));
    }
    @Test void concurrentUniqueClaim() throws Exception {
        byte[] bytes = base.mapper.writeValueAsBytes(declaration); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> base.service.submit(base.analysisId, bytes)); var b = pool.submit(() -> base.service.submit(base.analysisId, bytes));
            var x = a.get(30, TimeUnit.SECONDS); var y = b.get(30, TimeUnit.SECONDS); assertEquals(x.path("runId"), y.path("runId"));
            var done = base.service.read(base.analysisId, x.path("runId").asText()); assertEquals(2, done.path("calibrationComparisons").asInt(), done.toString());
            assertEquals(3, base.service.list(base.analysisId).size());
        } finally { pool.shutdownNow(); }
    }
    @Test void badArchiveFinishesFailureAndNeverRetriesAfterRepair() throws Exception {
        var path = paths.get("child"); String old = Files.readString(path); Files.writeString(path, "{}");
        var failed = submit(); assertEquals("FAILED", failed.path("status").asText()); assertEquals("ARCHIVE_LOAD", failed.at("/executionFailure/stage").asText());
        assertEquals(0, failed.path("calibrationPrepares").asInt()); Files.writeString(path, old); assertEquals(failed, submit());
    }
    @Test void privateDataAndSourceCorruptionFailBeforePrepare() throws Exception {
        var jdbc = new JdbcTemplate(Objects.requireNonNull(base.dataSource));
        jdbc.update("UPDATE variant_analysis_acmg_run SET private_json=? WHERE run_id=?", "{}", child.path("runId").asText());
        var failed = submit(); assertEquals("FAILED", failed.path("status").asText()); assertEquals("SAVED_SOURCE_READ", failed.at("/executionFailure/stage").asText());
        assertEquals(0, failed.path("calibrationPrepares").asInt()); assertEquals(failed, submit());
    }
    @Test void clientCannotInjectScoreResultPathOrChangeParentOrContext() throws Exception {
        for (String key : List.of("rawScore", "sidecar", "path", "trusted", "observation")) {
            var changed = declaration.deepCopy(); changed.withObject("/calibration").put(key, "forged");
            byte[] bytes = base.mapper.writeValueAsBytes(changed); assertThrows(ResponseStatusException.class, () -> base.service.submit(base.analysisId, bytes));
        }
        for (String key : List.of("sourceRunId", "sourceRequestSha256", "adoptionSha256")) {
            var changed = declaration.deepCopy(); changed.withObject("/calibration").put(key, "forged");
            byte[] bytes = base.mapper.writeValueAsBytes(changed); assertThrows(ResponseStatusException.class, () -> base.service.submit(base.analysisId, bytes));
        }
        declaration.put("retryOf", child.path("runId").asText()); assertThrows(ResponseStatusException.class, this::submit);
        assertEquals(2, base.service.list(base.analysisId).size());
    }
    void replaceChild(ObjectNode response, JsonNode privateData) throws Exception {
        String privateJson = base.mapper.writeValueAsString(privateData);
        response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
        String json = base.mapper.writeValueAsString(response);
        new JdbcTemplate(Objects.requireNonNull(base.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=?,response_sha256=?,private_json=? WHERE run_id=?",
                json, AcmgBusinessStore.sha(json), privateJson, child.path("runId").asText());
        evidence.withObject("/inputArtifacts").put("child", write(paths.get("child"), response));
        child = response; configure(); declaration.set("calibration", runtime.reference());
    }
    @Test void recomputedPrivateDigestDoesNotMakeUnmatchedPlansTrusted() throws Exception {
        var record = base.store.run(base.analysisId, child.path("runId").asText()).orElseThrow();
        var privateData = (ObjectNode) base.mapper.readTree(record.privateJson());
        ((ObjectNode) privateData.at("/verifiedAssociationPlans/0")).put("candidateId", "wrong-candidate");
        replaceChild(child.deepCopy(), privateData);
        var failed = submit(); assertEquals("CALIBRATION_PRIVATE_PUBLIC_PLAN_MISMATCH", failed.at("/executionFailure/reasonCode").asText());
        assertEquals(0, failed.path("calibrationPrepares").asInt());
    }
    @Test void secondPrepareFailureRetainsFirstComparisonAndDurablyStops() throws Exception {
        String second = runtime.candidates().get(1);
        ObjectNode changed = child.deepCopy(); var prediction = SavedCalibrationBusiness.prediction(changed, second);
        prediction.withObject("/association").put("rawScore", "0.9"); prediction.remove("resultId"); prediction.put("resultId", "prediction-result-sha256-" + hash(prediction));
        for (var row : changed.path("candidates")) if (second.equals(row.path("candidateId").asText())) ((ObjectNode) row.at("/attempts/0")).set("prediction", prediction);
        for (var allowed : evidence.path("allowedObservations")) if (second.equals(allowed.path("candidateId").asText())) ((ObjectNode) allowed).set("childResultId", prediction.path("resultId"));
        var record = base.store.run(base.analysisId, child.path("runId").asText()).orElseThrow();
        replaceChild(changed, base.mapper.readTree(record.privateJson()));
        var failed = submit(); assertEquals("FAILED", failed.path("status").asText(), failed.toPrettyString());
        assertEquals(2, failed.path("calibrationPrepares").asInt(), failed.toPrettyString());
        assertEquals(1, failed.path("calibrationComparisons").asInt()); assertEquals(1, failed.path("calibratedMechanisms").asInt());
        assertEquals(1, failed.path("candidates").size()); assertEquals(failed, submit());
    }
    @Test void alteredSourceAndDifferentAnalysisAreRejected() throws Exception {
        var json = (ObjectNode) base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json()); json.put("sampleId", "other");
        base.replaceSnapshot(json); var failed = submit(); assertEquals("FAILED", failed.path("status").asText()); assertEquals(0, failed.path("calibrationPrepares").asInt());
        base.publish(false, org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38, null);
        base.service = AcmgBusinessService.savedPredictionCalibration(fixture.original, runtime);
        assertThrows(ResponseStatusException.class, this::submit);
    }
    @Test void dbFormattingDifferencesRetainSemanticsButChangedContentFails() throws Exception {
        var record = base.store.run(base.analysisId, child.path("runId").asText()).orElseThrow();
        String compact = base.mapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(child);
        new JdbcTemplate(Objects.requireNonNull(base.dataSource)).update("UPDATE variant_analysis_acmg_run SET response_json=?,response_sha256=? WHERE run_id=?",
                compact, AcmgBusinessStore.sha(compact), record.id());
        var out = submit(); assertEquals(2, out.path("calibratedMechanisms").asInt(), out.toPrettyString());
    }
    @Test void calibrationRouteGuardBlocksReadSideEffectsAndWrites() {
        String prefix = "/variant-analyses/" + base.analysisId;
        assertFalse(org.nbscn.varvis.variantanalysis.config.SavedObservationOnlyConfig.calibrationAllowed("GET", "/variant-analyses/preflight"));
        assertFalse(org.nbscn.varvis.variantanalysis.config.SavedObservationOnlyConfig.calibrationAllowed("GET", prefix + "/llm-shadow-preview"));
        assertFalse(org.nbscn.varvis.variantanalysis.config.SavedObservationOnlyConfig.calibrationAllowed("POST", "/variant-analyses"));
        assertTrue(org.nbscn.varvis.variantanalysis.config.SavedObservationOnlyConfig.calibrationAllowed("POST", prefix + "/acmg/runs"));
        assertTrue(org.nbscn.varvis.variantanalysis.config.SavedObservationOnlyConfig.calibrationAllowed("GET", prefix + "/acmg/runs/" + child.path("runId").asText()));
    }
    @Test void semanticPublicationComparisonPreservesAliasesAndAcceptsNumberFormattingOnly() throws Exception {
        assertTrue(SavedCalibrationBusiness.semanticEquals(base.mapper.readTree("{\"score\":1.0,\"alias\":\"x\"}"), base.mapper.readTree("{\"alias\":\"x\",\"score\":1}")));
        assertFalse(SavedCalibrationBusiness.semanticEquals(base.mapper.readTree("{\"score\":1.0}"), base.mapper.readTree("{\"score\":0.99}")));
    }
    @Test void actualRepositorySummaryHasDifferentPresentationAndPreservesIdentity() throws Exception {
        var source = base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json()).path("candidates");
        var current = summaries(); var full = source.get(0).path("published"); var summary = current.get(0);
        assertFalse(SavedCalibrationBusiness.semanticEquals(full, summary));
        for (String field : List.of("scoreBreakdown", "scoreComponents", "positiveReasons", "rationale", "warnings")) {
            assertFalse(full.path(field).isEmpty(), field); assertTrue(summary.path(field).isEmpty(), field);
        }
        JsonNode originalCadd = null;
        for (var item : full.path("evidence")) if ("CADD".equals(item.path("source").asText())) originalCadd = item;
        assertNotNull(originalCadd);
        assertEquals("retained", originalCadd.at("/details/originalDetail").asText());
        assertEquals("CADD", summary.at("/evidence/0/source").asText());
        assertFalse(summary.at("/evidence/0/details").has("originalDetail"));
        assertEquals(full.path("rankingScore"), summary.path("rankingScore"));
        assertEquals(full.path("transcriptAnnotations"), summary.path("transcriptAnnotations"));
        var verified = SavedCalibrationRepresentations.verify(base.mapper, current, current, source, runtime.candidates());
        assertEquals("CANDIDATES", verified.path("view").asText());
        var out = submit(); assertEquals(2, out.path("calibratedMechanisms").asInt(), out.toPrettyString());
        assertEquals(verified, out.at("/candidates/0/attempts/0/prediction/businessProvenance/representationVerification"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"full", "evidence", "rationale", "order"})
    void resealedSyntheticArchiveCannotBypassSummaryComparison(String mutation) throws Exception {
        var archived = (ObjectNode) base.mapper.readTree(paths.get("candidates").toFile());
        var rows = (com.fasterxml.jackson.databind.node.ArrayNode) archived.path("variants");
        if (mutation.equals("full")) {
            rows.removeAll(); for (var c : base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json()).path("candidates")) rows.add(c.path("published"));
        } else if (mutation.equals("evidence")) ((ObjectNode) rows.get(0).at("/evidence/0/details")).put("forged", "value");
        else if (mutation.equals("rationale")) ((ObjectNode) rows.get(0)).putArray("rationale").add("forged explanation");
        else { var first = rows.get(0); rows.set(0, rows.get(1)); rows.set(1, first); }
        evidence.withObject("/inputArtifacts").put("candidates", write(paths.get("candidates"), archived));
        configure(); declaration.set("calibration", runtime.reference());
        var failed = submit(); assertEquals("FAILED", failed.path("status").asText());
        assertEquals(mutation.equals("order") ? "CALIBRATION_REPRESENTATION_COUNT_OR_ORDER" : "CALIBRATION_SUMMARY_VIEW_MISMATCH",
                failed.at("/executionFailure/reasonCode").asText(), failed.toPrettyString());
        assertEquals(0, failed.path("calibrationPrepares").asInt());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ranking_score", "cadd_score"})
    void changedRelationalFieldsCannotHideBehindUnchangedFullPayload(String column) throws Exception {
        new JdbcTemplate(Objects.requireNonNull(base.dataSource)).update("UPDATE variant_analysis_interpretation SET " + column
                + "=? WHERE analysis_id=? AND candidate_id=?", 999.0, base.analysisId, runtime.candidates().get(0));
        var failed = submit(); assertEquals(column.equals("ranking_score") ? "FORMAL_CANDIDATE_ORDER_UNAVAILABLE" : "CALIBRATION_SUMMARY_VIEW_MISMATCH",
                failed.at("/executionFailure/reasonCode").asText(), failed.toPrettyString());
        assertEquals(0, failed.path("calibrationPrepares").asInt());
    }
    @Test void crossRepresentationIdentityTypesMissingFieldsAndAnnotationsAreStrict() throws Exception {
        var full = base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json()).path("candidates");
        var current = summaries();
        for (String field : List.of("variantId", "canonicalVariantId", "candidateIdentity", "transcriptAnnotations", "analysisVcfCall",
                "exomiserCandidateFeatures", "rankingScore", "totalScore", "priorityTier", "tier", "observation")) {
            var changed = current.deepCopy(); ((ObjectNode) changed.get(0)).putNull(field);
            assertThrows(SavedCalibrationRepresentations.Failure.class,
                    () -> SavedCalibrationRepresentations.verify(base.mapper, changed, changed, full, runtime.candidates()), field);
            var bothMissing = full.deepCopy(); ((ObjectNode) bothMissing.get(0).path("published")).remove(field);
            ((ObjectNode) changed.get(0)).remove(field);
            assertThrows(SavedCalibrationRepresentations.Failure.class,
                    () -> SavedCalibrationRepresentations.verify(base.mapper, changed, changed, bothMissing, runtime.candidates()), field);
        }
        var changed = current.deepCopy(); ((ObjectNode) changed.get(0).at("/transcriptAnnotations/0")).put("geneId", "wrong");
        var failure = assertThrows(SavedCalibrationRepresentations.Failure.class,
                () -> SavedCalibrationRepresentations.verify(base.mapper, changed, changed, full, runtime.candidates()));
        assertEquals("CALIBRATION_REPRESENTATION_IDENTITY_MISMATCH", failure.getMessage());
        assertEquals("/transcriptAnnotations", failure.fieldPath());
        for (String field : List.of("genotype", "filterStatus")) {
            var nullable = current.deepCopy(); var fullNullable = full.deepCopy();
            ((ObjectNode) nullable.get(0).path("observation")).putNull(field);
            ((ObjectNode) fullNullable.get(0).at("/published/observation")).putNull(field);
            assertDoesNotThrow(() -> SavedCalibrationRepresentations.verify(base.mapper, nullable, nullable, fullNullable, runtime.candidates()));
            ((ObjectNode) nullable.get(0).path("observation")).remove(field);
            ((ObjectNode) fullNullable.get(0).at("/published/observation")).remove(field);
            assertThrows(SavedCalibrationRepresentations.Failure.class,
                    () -> SavedCalibrationRepresentations.verify(base.mapper, nullable, nullable, fullNullable, runtime.candidates()));
        }
    }
    @Test void fullPayloadCorruptionStillFailsSourceRecovery() throws Exception {
        var value = (ObjectNode) base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 2).get(0));
        value.withArray("/warnings").add("corrupted full payload");
        new JdbcTemplate(Objects.requireNonNull(base.dataSource)).update("UPDATE variant_analysis_interpretation SET payload_json=? WHERE analysis_id=? AND candidate_id=?",
                base.mapper.writeValueAsString(value), base.analysisId, runtime.candidates().get(0));
        var failed = submit(); assertEquals("EXECUTION_SOURCE_RECOVERY", failed.at("/executionFailure/stage").asText(), failed.toPrettyString());
        assertEquals(0, failed.path("calibrationPrepares").asInt());
    }
    @Test void differentRepresentationContractDoesNotGrantSecondAttempt() throws Exception {
        var saved = submit(); var record = base.store.run(base.analysisId, saved.path("runId").asText()).orElseThrow();
        var request = (ObjectNode) base.mapper.readTree(record.requestJson());
        request.withObject("/executionRoot/representationContract").put("version", "older-synthetic-version");
        String requestSha = hash(request); saved.put("requestSha256", requestSha); String response = base.mapper.writeValueAsString(saved);
        new JdbcTemplate(Objects.requireNonNull(base.dataSource)).update("UPDATE variant_analysis_acmg_run SET request_json=?,request_sha256=?,response_json=?,response_sha256=? WHERE run_id=?",
                base.mapper.writeValueAsString(request), requestSha, response, AcmgBusinessStore.sha(response), record.id());
        for (Path path : paths.values()) Files.delete(path);
        assertEquals(saved, submit()); assertEquals(3, base.service.list(base.analysisId).size());
    }
}
