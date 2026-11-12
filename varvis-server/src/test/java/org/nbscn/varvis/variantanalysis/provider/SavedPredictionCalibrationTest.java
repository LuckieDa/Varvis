package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Fabricated exports only: never reads the two real controls or executes a provider. */
class SavedPredictionCalibrationTest {
    static final ObjectMapper M = new ObjectMapper();
    static final RuntimeEvidenceV2Adapter HASH = new RuntimeEvidenceV2Adapter(M);
    @TempDir Path temp;
    static Path repo() { return Path.of(System.getProperty("varvis.repo", "..")).toAbsolutePath().normalize(); }
    static Path supplement() { return repo().resolve("docs/execution/pp3-exact-record-20260918/Bergquist_2025_supplement_author_copy_full.pdf"); }
    static ObjectNode contract() throws Exception {
        Path sources = repo().resolve("docs/execution/pp3-calibration-source-20260918");
        return PredictionCalibration.loadStandard(M, Files.readAllBytes(repo().resolve("varvis-server/src/main/resources/acmg/prediction-standard-offline/am-standard-1.0.0.json")),
                "20d9dfe35874fe4b90370fd26ad0259dc9821b87b0084893a7fa79888992b489",
                Files.readAllBytes(sources.resolve("discounted_comparator_tools_w3pts_bootstrap10k_thrs.txt")), Files.readAllBytes(sources.resolve("results-README.txt")));
    }
    static ObjectNode object() { return M.createObjectNode(); }
    static String write(Path path, JsonNode n) throws Exception {
        byte[] bytes = M.writeValueAsBytes(n); Files.write(path, bytes); return PredictionCalibration.sha256(bytes);
    }
    static void seal(ObjectNode n, String field, String prefix) { n.remove(field); n.put(field, prefix + HASH.canonicalSha256(n)); }

    static final class Fixture {
        final Path root;
        final Map<String, ObjectNode> files = new LinkedHashMap<>();
        final Map<String, Path> paths = new LinkedHashMap<>();
        final ObjectNode calibration = contract(), evidence = object(), local = object(), allele = object(), tx = object();
        final ObjectNode observation = object(), row = object(), selection = object(), input = object(), association = object();
        final ObjectNode raw = object(), child = object();
        PredictionLocalResourceBinding localBinding;
        Fixture(Path root) throws Exception {
            this.root = root;
            allele.put("genomeAssembly", "GRCh38").put("chromosome", "1").put("position", 100).put("reference", "C").put("alternate", "T");
            local.put("schemaVersion", "prediction-local-binding-v1").put("purpose", "NONCOMMERCIAL_LOCAL_RESEARCH_PUBLIC_CONTROLS_ONLY");
            var resource = local.putObject("resource").put("model", "ALPHAMISSENSE").put("dataVersion", "2023-08-03")
                    .put("sourceLocator", "https://storage.googleapis.com/dm_alphamissense/AlphaMissense_hg38.tsv.gz")
                    .put("sha256", "1".repeat(64)).put("indexSha256", "2".repeat(64)).put("assembly", "GRCh38")
                    .put("scoreField", "am_pathogenicity").put("scorePrecision", "ORIGINAL_DECIMAL_STRING").put("calibrationCompatibility", "UNVERIFIED");
            resource.putObject("license").put("purpose", "DECLARED_RESEARCH").put("status", "PERMITTED")
                    .put("sourceLocator", "synthetic:license").put("textSha256", "3".repeat(64));
            local.putArray("controls").add(allele.deepCopy());
            local.putObject("verification").putArray("rawHeader").add("#CHROM\tPOS\tREF\tALT\tgenome\tuniprot_id\ttranscript_id\tprotein_variant\tam_pathogenicity\tam_class");
            evidence.put("schemaVersion", PredictionResourceCompatibility.StandardBinding.EXACT_SCHEMA)
                    .put("overallStatus", "PARTIAL").put("authorFullMappingAndAggregationReproduced", false)
                    .put("analysisId", "synthetic-analysis").put("parentRunId", "synthetic-parent").put("childRunId", "synthetic-child")
                    .put("executionSourceSha256", "4".repeat(64)).put("calibrationId", calibration.path("calibrationId").asText())
                    .put("calibrationVersion", calibration.path("version").asText()).put("calibrationCanonicalSha256", HASH.canonicalSha256(calibration));
            evidence.putObject("localAdoption").put("id", PredictionResourceCompatibility.StandardBinding.EXACT_ID)
                    .put("version", "1.0.0").put("scope", "EXACT_SINGLE_ROW").put("status", "VERIFIED");
            evidence.putObject("mappingSource").put("sha256", PredictionResourceCompatibility.StandardBinding.SUPPLEMENT_SHA).put("page", 2)
                    .put("url", "https://www.ccs.neu.edu/home/radivojac/papers/bergquist_genetmed_2025_supp.pdf");
            tx.put("transcriptAccession", "ENST00000000001.1").put("consequence", "missense_variant")
                    .put("source", "VEP").put("status", "COMPLETED").put("version", "116").put("proteinPosition", "2").put("aminoAcids", "A/V")
                    .put("hgvsCoding", "ENST00000000001.1:c.5C>T").put("hgvsProtein", "ENSP00000000001.1:p.Ala2Val");
            tx.setAll((ObjectNode) M.valueToTree(M.treeToValue(tx, TranscriptConsequence.class)));
            row.put("sourceRow", "chr1\t100\tC\tT\thg38\tSYNTHETIC\tENST00000000001.1\tA2V\t0.2\tfixture")
                    .put("uniprotId", "SYNTHETIC").put("transcriptId", "ENST00000000001.1").put("proteinVariant", "A2V").put("rawScore", "0.2").put("rawClass", "fixture");
            observation.put("schemaVersion", "prediction-observation-v1").put("status", "HIT").put("reasonCode", "RAW_RECORDS_OBTAINED")
                    .put("referenceVerified", true).put("scoreOrigin", "REAL_LOCAL_PRECOMPUTED_RESOURCE").put("transportAttempts", 1)
                    .put("dataClass", "SYNTHETIC").put("observedAt", "2026-01-01T00:00:00Z");
            observation.set("queryAllele", allele); observation.putArray("rows").add(row);
            selection.put("candidateId", "synthetic-candidate").put("selectedAnnotationId", "synthetic-annotation").put("versionedTranscriptId", "ENST00000000001.1");
            input.put("transcriptId", "ENST00000000001.1").put("proteinPosition", "2").put("aminoAcids", "A/V")
                    .put("annotationSource", "VEP").put("annotationSourceVersion", "116").put("route", "EXACT_VERSIONED_TRANSCRIPT")
                    .put("executionSourceSha256", "4".repeat(64)); input.set("queryAllele", allele);
            association.put("status", "ASSOCIATED").put("rawScore", "0.2"); association.putArray("matchedRowIndices").add(0);
            raw.putObject("runtimeIdentity").put("version", "2.0.0-development.3");
            for (var p : new ObjectNode[] {raw, child}) {
                p.set("observation", observation);
                var plan = p.putObject("plan").put("candidateId", "synthetic-candidate").put("executionSourceSha256", "4".repeat(64)); plan.set("queryAllele", allele);
            }
            raw.withObject("/plan").put("planMode", "OBSERVATION_ONLY").put("rawAcquisitionAllowed", true).set("runtimeIdentity", raw.path("runtimeIdentity"));
            child.set("selection", selection); child.set("associationInput", input); child.set("association", association);
            for (String code : new String[] {"PP3", "BP4"}) child.withObject("/criteria").putObject(code).put("status", "NOT_EVALUATED").put("appliedStrength", "NONE");
            for (String name : new String[] {"parent", "child"}) {
                var run = object().put("analysisId", "synthetic-analysis").put("dataClass", "SYNTHETIC").put("status", "COMPLETED_WITH_BLOCKERS")
                        .put("executionSourceSha256", "4".repeat(64)).put("runId", "synthetic-" + name);
                run.putArray("candidates").addObject().put("candidateId", "synthetic-candidate").putArray("attempts").addObject().set("prediction", name.equals("parent") ? raw : child);
                files.put(name, run);
            }
            files.get("parent").put("declarationVersion", PredictionDevelopmentRuntime.DECLARATION).put("requestSha256", "5".repeat(64));
            files.get("child").put("declarationVersion", SavedPredictionAssociation.DECLARATION).put("operation", SavedPredictionAssociation.OPERATION).put("sourceRunId", "synthetic-parent");
            var request = object().put("declarationVersion", SavedPredictionAssociation.DECLARATION).put("dataClass", "SYNTHETIC").put("sampleId", "synthetic-sample");
            request.putObject("association").put("sourceRunId", "synthetic-parent").put("sourceRequestSha256", "5".repeat(64)).putArray("selections").add(selection); files.put("request", request);
            var frozen = object(); frozen.putArray("selections").addObject().put("candidateId", "synthetic-candidate").set("actualSavedAnnotation", tx); files.put("selections", frozen);
            files.put("job", object().put("id", "synthetic-analysis").put("selectedSampleId", "synthetic-sample").put("genomeAssembly", "GRCh38")
                    .put("analysisAssembly", "GRCh38").put("liftoverApplied", false).put("liftoverStatus", "NOT_APPLICABLE"));
            var candidates = object().put("analysisId", "synthetic-analysis"); var candidate = candidates.putArray("variants").addObject().put("candidateId", "synthetic-candidate");
            candidate.putObject("observation").put("variantType", "SNV").put("sampleId", "synthetic-sample").set("key", allele);
            candidate.putArray("transcriptAnnotations").add(tx); files.put("candidates", candidates);
        }
        void prepareHashes() throws Exception {
            localBinding = PredictionLocalResourceBinding.load(M, root.resolve("local.json"), write(root.resolve("local.json"), local));
            var resource = localBinding.identity(); evidence.set("runtimeResourceIdentity", resource); observation.set("resource", resource);
            raw.withObject("/plan/resources").set("ALPHAMISSENSE", resource);
            var checks = evidence.putObject("checks");
            for (String fact : new String[] {"modelAndScore", "productAndDerivation", "purpose"}) checks.putObject(fact).put("status", "PASS")
                    .put("sha256", fact.equals("purpose") ? "3".repeat(64) : resource.path("localBindingSha256").asText());
            seal(observation, "observationSha256", ""); selection.set("sourceObservationSha256", observation.path("observationSha256"));
            selection.put("annotationSourceSha256", HASH.canonicalSha256(tx)); input.set("annotationSourceSha256", selection.path("annotationSourceSha256"));
            var option = ((ObjectNode) files.get("selections").at("/selections/0")).putObject("selected");
            option.set("annotationId", selection.path("selectedAnnotationId")); option.set("annotationSourceSha256", selection.path("annotationSourceSha256")); option.set("versionedTranscriptId", selection.path("versionedTranscriptId"));
            child.withObject("/plan").set("selection", selection); child.withObject("/plan").set("parentReference", files.get("request").path("association"));
            resign();
        }
        void resign() {
            seal(raw.withObject("/plan"), "planId", "prediction-plan-sha256-"); seal(raw, "resultId", "prediction-result-sha256-");
            seal(child.withObject("/plan"), "planId", "saved-association-plan-sha256-"); seal(child, "resultId", "prediction-result-sha256-");
            var allowed = evidence.putArray("allowedObservations").addObject().put("candidateId", "synthetic-candidate");
            allowed.set("childResultId", child.path("resultId")); allowed.set("parentResultId", raw.path("resultId")); allowed.set("observationSha256", observation.path("observationSha256"));
            allowed.put("selectionCanonicalSha256", HASH.canonicalSha256(selection));
        }
        PredictionResourceCompatibility.StandardBinding binding() throws Exception {
            var digests = evidence.putObject("inputArtifacts");
            for (var entry : files.entrySet()) { Path p = root.resolve(entry.getKey() + ".json"); paths.put(entry.getKey(), p); digests.put(entry.getKey(), write(p, entry.getValue())); }
            Path p = root.resolve("adoption.json"); return PredictionResourceCompatibility.StandardBinding.loadExact(M, p, write(p, evidence), supplement());
        }
        SavedPredictionCalibration.VerifiedInput verified() throws Exception {
            return SavedPredictionCalibration.load(M, binding(), localBinding, calibration, paths).prepare("synthetic-candidate");
        }
    }

    @ParameterizedTest @CsvSource({"0.2,UNINFORMATIVE,NONE", "0.1,BENIGN,SUPPORTING"})
    void completeSyntheticSavedSourceSealsScoreAndNeverAppliesCriteria(String score, String direction, String strength) throws Exception {
        var f = new Fixture(temp); f.row.put("rawScore", score).put("sourceRow", f.row.path("sourceRow").asText().replace("\t0.2\t", "\t" + score + "\t"));
        f.association.put("rawScore", score); f.prepareHashes(); var input = f.verified(); var original = input.provenance();
        input.provenance().removeAll(); f.row.put("rawScore", "0.99"); // neither a returned copy nor later mutation replaces the sealed score
        var out = PredictionClauseRuntime.calibrateStandard(M, input);
        assertEquals("CALIBRATED", out.path("status").asText()); assertEquals(score, out.path("rawScore").asText());
        assertEquals(direction, out.path("direction").asText()); assertEquals(strength, out.path("calibratedStrength").asText()); assertEquals(original, out.path("savedSource"));
        assertEquals(3, out.path("remainingApplicationConditions").size());
        assertEquals("NOT_EVALUATED", out.path("applicationStatus").asText()); assertEquals("NONE", out.path("appliedStrength").asText());
        assertTrue(out.path("sourcePointValue").isNull()); assertEquals("UNVERIFIED", f.localBinding.identity().path("calibrationCompatibility").asText());
        assertEquals("TRUSTED_SAVED_CALIBRATION_ENTRY_REQUIRED", PredictionClauseRuntime.calibrateStandard(M, f.calibration, f.binding(), f.localBinding,
                f.localBinding.identity(), f.input, "0.99").path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"chromosome", "position", "unregistered", "version", "sameAA", "fakeNative", "annotation", "observation", "parent",
            "rawScore", "transcriptId", "proteinVariant", "uniprotId", "coordinate", "rawClass", "multipleSame", "multipleDifferent", "bridge", "isoform",
            "partial", "scope", "adoptionVersion", "contract", "resource", "source", "timeout", "indices", "incomplete", "purpose", "outsideAllowlist", "parentObservation", "associationScore", "hgvsAlias"})
    void syntheticSemanticFailuresCannotBeAuthorizedByResigningExports(String fault) throws Exception {
        var f = new Fixture(temp);
        switch (fault) {
            case "chromosome", "position" -> f.allele.remove(fault);
            case "unregistered" -> f.allele.put("position", 101);
            case "version" -> f.input.put("transcriptId", "ENST00000000001.2");
            case "sameAA" -> f.tx.put("aminoAcids", "A/A");
            case "fakeNative" -> f.files.get("job").put("genomeAssembly", "GRCh37");
            case "parent" -> f.files.get("child").put("sourceRunId", "wrong-parent");
            case "rawScore", "transcriptId", "proteinVariant", "uniprotId", "rawClass" -> f.row.put(fault, "mismatch");
            case "coordinate" -> f.row.put("sourceRow", f.row.path("sourceRow").asText().replace("chr1\t100", "chr1\t101"));
            case "multipleSame", "multipleDifferent" -> f.observation.withArray("/rows").add(f.row.deepCopy().put("rawScore", fault.equals("multipleSame") ? "0.2" : "0.8"));
            case "bridge" -> f.input.put("route", "UNIPROT_BRIDGE");
            case "isoform" -> f.local.withObject("/resource").put("sourceLocator", "synthetic:isoforms");
            case "partial" -> f.evidence.withObject("/localAdoption").put("status", "PARTIAL");
            case "scope" -> f.evidence.withObject("/localAdoption").put("scope", "ANY");
            case "adoptionVersion" -> f.evidence.withObject("/localAdoption").put("version", "2.0.0");
            case "contract" -> f.evidence.put("calibrationCanonicalSha256", "0".repeat(64));
            case "source" -> f.evidence.withObject("/mappingSource").put("sha256", "0".repeat(64));
            case "timeout" -> f.observation.put("reasonCode", "PREDICTION_QUERY_LIMIT_OR_TIMEOUT");
            case "indices" -> f.association.putArray("matchedRowIndices").add(1);
            case "incomplete" -> f.observation.put("referenceVerified", false);
            case "purpose" -> f.local.put("purpose", "OTHER");
            case "associationScore" -> f.association.put("rawScore", "0.8");
            case "hgvsAlias" -> f.tx.put("hgvsp", "ENSP00000000001.1:p.Ala2Gly");
            default -> { }
        }
        f.prepareHashes();
        switch (fault) {
            case "annotation" -> f.selection.put("annotationSourceSha256", "0".repeat(64));
            case "observation" -> f.observation.put("observationSha256", "0".repeat(64));
            case "resource" -> f.observation.set("resource", f.localBinding.identity().put("sha256", "0".repeat(64)));
            case "parentObservation" -> f.raw.set("observation", f.observation.deepCopy().put("observedAt", "2026-01-02T00:00:00Z"));
            default -> { }
        }
        f.resign();
        if (fault.equals("outsideAllowlist")) f.evidence.withArray("/allowedObservations").removeAll();
        assertThrows(IllegalArgumentException.class, f::verified, fault);
    }

    @Test void fileIntegrityAndOldPartialSchemaRemainIndependentGates() throws Exception {
        var f = new Fixture(temp); f.prepareHashes(); var binding = f.binding(); Files.writeString(f.paths.get("child"), "{}");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SavedPredictionCalibration.load(M, binding, f.localBinding, f.calibration, f.paths)).getMessage().contains("ARTIFACT_DIGEST_MISMATCH"));
        f.evidence.put("schemaVersion", "prediction-standard-resource-compatibility-v1");
        assertEquals("EXACT_ADOPTION_SUCCESSOR_REQUIRED", assertThrows(IllegalArgumentException.class, f::binding).getMessage());
        assertThrows(IllegalArgumentException.class, () -> SavedPredictionCalibration.load(M, binding, f.localBinding, f.calibration, Map.of()));
    }
}
