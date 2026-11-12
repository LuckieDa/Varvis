package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Blocker;
import java.nio.file.Path;
import java.util.*;

/** Private publication snapshot from the existing single VCF scan; recovery reads metadata only. */
public final class AcmgExecutionSources {
    public static final int MAX_CANDIDATES = 100;
    public static final String VERSION = "acmg-execution-source-v1";
    public static final String CONVERTED_VERSION = "acmg-execution-source-v2-conversion";
    private final ObjectMapper mapper;
    private final AcmgBusinessStore store;
    public AcmgExecutionSources(ObjectMapper mapper, AcmgBusinessStore store) {
        this.mapper = mapper.copy().findAndRegisterModules()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.store = store;
    }
    public void savePublicationAnnotations(String analysisId, List<VariantInterpretation> selected,
                                           Map<String, List<TranscriptConsequence>> annotations) {
        for (var candidate : selected) {
            var values = annotations.get(candidate.getVariantId());
            if (values == null) continue;
            candidate.applyTranscriptAnnotations(values);
            try {
                store.saveCandidateAnnotationPayload(analysisId, candidate.getCandidateId(), mapper.writeValueAsString(candidate));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalArgumentException("ACMG_ANNOTATION_SERIALIZATION_FAILED", invalid);
            }
        }
    }
    public void publish(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult result,
                        List<VariantInterpretation> persisted) throws Exception {
        var s = result.getInputSource();
        if (!(s != null && Objects.equals(job.getId(), s.analysisId()))) { throw new IllegalArgumentException("EXECUTION_SOURCE_UNAVAILABLE"); }
        ObjectNode value = mapper.createObjectNode().put("version", conversion.isConversionApplied() ? CONVERTED_VERSION : VERSION).put("analysisId", job.getId())
                .put("inputAssembly", s.inputAssembly().name()).put("analysisAssembly", s.analysisAssembly().name())
                .put("analysisMode", s.analysisMode().name()).put("sampleId", s.sampleId())
                .put("uploadSha256", s.uploadSha256()).put("uploadedFile", s.uploadedFile().toString())
                .put("analysisVcf", s.analysisVcf().toString()).put("observedVcfSha256", s.observedVcfSha256())
                .put("byteLength", s.observedVcfByteLength()).put("mtimeNs", s.observedVcfMtimeNs())
                .put("fileKey", s.observedVcfFileKey())
                .put("fullVcfScans", s.fullVcfScans()).put("unchangedDuringExecution", s.unchangedDuringExecution())
                .put("conversionApplied", conversion.isConversionApplied()).put("conversionEngine", conversion.getEngine())
                .put("rejectedRecordCount", conversion.getRejectedRecordCount()).put("sourceRecordCount", conversion.getSourceRecordCount())
                .put("analysisRecordCount", conversion.getAnalysisRecordCount())
                .put("requestSha256", result.getRequestSha256()).put("commandSha256", result.getCommandSha256())
                .put("outputSha256", result.getMetadata().getOutputSha256()).put("engineVersion", result.getMetadata().getEngineVersion())
                .put("resourceFingerprint", result.getMetadata().getResourceFingerprint()).put("rankingMode", result.getMetadata().getRankingMode())
                .put("exitCode", result.getExitCode()).put("engineStatus", result.getMetadata().getStatus().name())
                .put("engine", result.getMetadata().getEngine().name()).put("workDirectory", result.getWorkDirectory().toString());
        if (conversion.isConversionApplied()) {
            try { value.set("conversionProof", ConversionSourceProof.publish(mapper, job, conversion, result, persisted)); }
            catch (Exception unavailable) { value.put("conversionSourceBlocker", code(unavailable)); }
        }
        var policy = AnalysisScopePolicy.read(job);
        if (policy != null && policy.supplementsZeroPassed()) {
            Path receipt = result.getWorkDirectory().resolve(ExomiserZeroPassCoverage.MANIFEST);
            require(java.nio.file.Files.size(receipt) <= 65536, "EXECUTION_OUTPUT_COVERAGE_INVALID");
            var coverage = mapper.readTree(java.nio.file.Files.readString(receipt));
            require(AcmgBusinessStore.sha(coverage.toString()).equals(result.getMetadata().getOutputSha256())
                    && policy.policySha256().equals(coverage.path("policySha256").asText()), "EXECUTION_OUTPUT_COVERAGE_INVALID");
            value.set("outputCoverage",coverage);
        }
        var rows = value.putArray("candidates");
        if (s.hasSampleFilters()) value.put("sampleFilterCapability", PublishedSampleFilter.VERSION);
        for (var candidate : persisted.stream().limit(MAX_CANDIDATES).toList()) {
            var row = rows.addObject().put("candidateId", candidate.getCandidateId());
            row.set("published", mapper.valueToTree(candidate)); // Includes actual selected annotation objects, privately.
            var originals = result.getInterpretations().stream().filter(c -> Objects.equals(c.getCandidateId(), candidate.getCandidateId())).toList();
            if (originals.size() != 1 || candidate.getObservation() == null) {
                row.put("sourceBlocker", "ENGINE_CANDIDATE_ASSOCIATION_INVALID");
                continue;
            }
            row.set("engineCandidate", mapper.valueToTree(originals.get(0)));
            row.set("matchedRecord", mapper.valueToTree(s.matchedRecord(candidate.getObservation().getKey())));
            var filter = s.sampleFilter(candidate.getObservation().getKey());
            if (filter != null) row.set("sampleFilterWitness", mapper.valueToTree(filter));
        }
        String json = mapper.writeValueAsString(value);
        require(json.length() <= 16 * 1024 * 1024, "EXECUTION_SNAPSHOT_SIZE_LIMIT");
        store.saveSource(job.getId(), json);
    }
    public record Recovered(EngineExecutionResult execution, BuildConversionResult conversion,
                            Map<String, Blocker> candidateBlockers, String snapshotSha256) { }
    public Recovered recover(VariantAnalysisJob job, List<VariantInterpretation> ranked) throws Exception {
        return recover(job, ranked, store.source(job.getId()).orElseThrow(() -> new IllegalArgumentException("EXECUTION_SOURCE_UNAVAILABLE")));
    }
    /** Validate and reuse one already captured store snapshot without a second read. */
    public Recovered recover(VariantAnalysisJob job, List<VariantInterpretation> ranked, AcmgBusinessStore.Source stored) throws Exception {
        require(stored.json().length() <= 16 * 1024 * 1024 && AcmgBusinessStore.sha(stored.json()).equals(stored.sha256()), "EXECUTION_SNAPSHOT_HASH_MISMATCH");
        JsonNode v = mapper.readTree(stored.json());
        require(VERSION.equals(v.path("version").asText()) || CONVERTED_VERSION.equals(v.path("version").asText()), "EXECUTION_SNAPSHOT_VERSION_MISMATCH");
        require(Objects.equals(job.getId(), v.path("analysisId").asText())
                && Objects.equals(job.getFileSha256(), v.path("uploadSha256").asText())
                && Objects.equals(job.getGenomeAssembly().name(), v.path("inputAssembly").asText())
                && Objects.equals(job.getAnalysisMode().name(), v.path("analysisMode").asText())
                && Objects.equals(sample(job), v.path("sampleId").asText())
                && Objects.equals(job.getUploadedFile().toAbsolutePath().normalize(), Path.of(v.path("uploadedFile").asText())), "EXECUTION_SNAPSHOT_TASK_MISMATCH");
        require("EXOMISER".equals(v.path("engine").asText()) && "RANKED".equals(v.path("engineStatus").asText())
                && integer(v.path("exitCode"), 0) == 0, "EXECUTION_SNAPSHOT_STATUS_INVALID");
        var policy = AnalysisScopePolicy.read(job);
        if (policy != null && policy.supplementsZeroPassed() || v.has("outputCoverage")) {
            require(policy != null && policy.supplementsZeroPassed()
                    && policy.policySha256().equals(v.path("outputCoverage").path("policySha256").asText())
                    && policy.outputCoveragePolicy().equals(v.path("outputCoverage").path("policy").asText())
                    && AcmgBusinessStore.sha(v.path("outputCoverage").toString()).equals(v.path("outputSha256").asText()),
                    "EXECUTION_OUTPUT_COVERAGE_INVALID");
        }
        var currentEngine = job.getEngineMetadata();
        require(currentEngine != null && currentEngine.getEngine() == AnalysisEngine.EXOMISER
                && currentEngine.getStatus() == EngineExecutionStatus.RANKED
                && !currentEngine.isFallbackUsed()
                && Objects.equals(currentEngine.getOutputSha256(), v.path("outputSha256").asText())
                && Objects.equals(currentEngine.getEngineVersion(), v.path("engineVersion").asText())
                && Objects.equals(currentEngine.getResourceFingerprint(), v.path("resourceFingerprint").asText())
                && Objects.equals(currentEngine.getRankingMode(), v.path("rankingMode").asText()), "EXECUTION_SNAPSHOT_EXECUTION_MISMATCH");
        for (String key : List.of("requestSha256", "commandSha256", "outputSha256", "observedVcfSha256"))
            require(v.path(key).isTextual() && v.path(key).asText().matches("[0-9a-f]{64}"), "EXECUTION_SNAPSHOT_IDENTITY_INVALID");
        require(v.path("conversionApplied").isBoolean() && v.path("unchangedDuringExecution").isBoolean(), "EXECUTION_SNAPSHOT_TYPE_INVALID");
        Path analysisVcf = Path.of(v.path("analysisVcf").asText());
        if (v.path("conversionApplied").booleanValue()) {
            require(CONVERTED_VERSION.equals(v.path("version").asText()), "CONVERSION_SOURCE_VERSION_UNSUPPORTED");
            require(!v.has("conversionSourceBlocker"), v.path("conversionSourceBlocker").asText("CONVERSION_SOURCE_PROOF_MISSING"));
            ConversionSourceProof.verifyTask(v.path("conversionProof"), job, analysisVcf, v.path("observedVcfSha256").asText());
        }
        var stamp = AnalysisVcfAlleleIndex.ArtifactStamp.capture(analysisVcf);
        require(positiveLong(v.path("byteLength")) == stamp.byteLength() && positiveLong(v.path("mtimeNs")) == stamp.mtimeNs(), "EXECUTION_VCF_ARTIFACT_CHANGED");
        require(v.has("fileKey") && Objects.equals(v.path("fileKey").isNull() ? null : v.path("fileKey").asText(),
                Objects.toString(stamp.fileKey(), null)), "EXECUTION_VCF_ARTIFACT_CHANGED");
        int scans = integer(v.path("fullVcfScans"), 0);
        require(scans == 1 && v.path("unchangedDuringExecution").booleanValue(), "EXECUTION_SCAN_NOT_VERIFIED");
        require(v.path("candidates").isArray() && v.path("candidates").size() <= MAX_CANDIDATES, "EXECUTION_SNAPSHOT_CANDIDATES_INVALID");
        if (v.has("sampleFilterCapability")) require(v.path("sampleFilterCapability").isTextual()
                && PublishedSampleFilter.VERSION.equals(v.path("sampleFilterCapability").textValue()), "EXECUTION_SAMPLE_FILTER_CAPABILITY_INVALID");
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (var row : v.path("candidates")) require(rows.put(row.path("candidateId").asText(), row) == null, "EXECUTION_SNAPSHOT_DUPLICATE_CANDIDATE");
        var originals = new ArrayList<VariantInterpretation>();
        var records = new LinkedHashMap<NormalizedVariantKey, EngineExecutionResult.MatchedRecord>();
        var filters = new LinkedHashMap<NormalizedVariantKey, PublishedSampleFilter>();
        var issues = new LinkedHashMap<String, Blocker>();
        for (var candidate : ranked) {
            JsonNode row = rows.get(candidate.getCandidateId());
            try {
                if (row == null) { throw new IllegalArgumentException("EXECUTION_SNAPSHOT_CANDIDATE_MISSING"); }
                require(!row.has("sourceBlocker"), "ENGINE_CANDIDATE_ASSOCIATION_INVALID");
                // Jackson's tree round trip can render 1.0 as 1. Compare exact decimal values,
                // retaining strict integer/type checks for execution witnesses below.
                require(row.path("published").equals((left, right) ->
                                left.isNumber() && right.isNumber()
                                        ? left.decimalValue().compareTo(right.decimalValue())
                                        : left.equals(right) ? 0 : 1,
                        mapper.readTree(mapper.writeValueAsBytes(candidate))), "EXECUTION_SNAPSHOT_CANDIDATE_CHANGED");
                JsonNode originalCall = row.path("engineCandidate").path("analysisVcfCall");
                if ("MATCHED".equals(originalCall.path("matchStatus").asText())) {
                    integer(originalCall.path("matchedRecordOrdinal"), 1);
                    integer(originalCall.path("targetAlleleIndex"), 1);
                }
                var original = mapper.treeToValue(row.path("engineCandidate"), VariantInterpretation.class);
                require(Objects.equals(original.getCandidateId(), candidate.getCandidateId()), "EXECUTION_SNAPSHOT_CANDIDATE_MISMATCH");
                originals.add(original);
                var r = row.path("matchedRecord");
                if (!r.isNull()) {
                    // Preserve R1's strict positive int model before Jackson can coerce any number.
                    int ordinal = integer(r.path("recordOrdinal"), 1), alt = integer(r.path("selectedAltIndex"), 1);
                    int position = integer(r.path("position"), 1);
                    require(r.path("alternates").isArray() && alt <= r.path("alternates").size(), "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED");
                    List<String> alts = new ArrayList<>();
                    for (var a : r.path("alternates")) { require(a.isTextual(), "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED"); alts.add(a.textValue()); }
                    var record = new EngineExecutionResult.MatchedRecord(r.path("contig").asText(), position, r.path("reference").asText(), alts, alt, ordinal);
                    require(Objects.equals(original.getAnalysisVcfCall().getMatchedRecordOrdinal(), ordinal)
                            && Objects.equals(original.getAnalysisVcfCall().getTargetAlleleIndex(), alt), "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED");
                    require(NormalizationAlleleMapping.IDENTITY.equals(NormalizationAlleleMapping.from(record.mappingToken(), candidate.getObservation().getKey()).getStatus()),
                            "EXECUTION_SNAPSHOT_ALLELE_CONFLICT");
                    var old = records.putIfAbsent(candidate.getObservation().getKey(), record);
                    require(old == null || old.equals(record), "EXECUTION_SNAPSHOT_ALLELE_CONFLICT");
                    if (row.has("sampleFilterWitness")) {
                        require(v.has("sampleFilterCapability"), "EXECUTION_SAMPLE_FILTER_CAPABILITY_INVALID");
                        filters.put(candidate.getObservation().getKey(), PublishedSampleFilter.recover(row.path("sampleFilterWitness"),
                                v.path("sampleId").asText(), v.path("analysisAssembly").asText(), record));
                    }
                } else {
                    require(!row.has("sampleFilterWitness"), "EXECUTION_SAMPLE_FILTER_BINDING_CONFLICT");
                }
            } catch (Exception invalid) { issues.put(candidate.getCandidateId(), new Blocker("CONTEXT_NOT_READY", code(invalid), "executionSource.candidate")); }
        }
        var metadata = EngineMetadata.exomiserRanked(v.path("engineVersion").asText(), v.path("resourceFingerprint").asText(), v.path("outputSha256").asText(), v.path("rankingMode").asText());
        var indexed = new AnalysisVcfAlleleIndex.IndexedCalls(Map.of(), Map.copyOf(records), v.path("observedVcfSha256").asText(), stamp, scans, Map.copyOf(filters));
        var execution = EngineExecutionResult.completed(metadata, originals, Path.of(v.path("workDirectory").asText()), v.path("requestSha256").asText(), v.path("commandSha256").asText(), 0)
                .withRecoveredInputSource(job, analysisVcf, GenomeAssembly.valueOf(v.path("analysisAssembly").asText()), v.path("sampleId").asText(), indexed, true);
        return new Recovered(execution, new BuildConversionResult(analysisVcf, v.path("conversionApplied").booleanValue(), v.path("conversionEngine").asText(), List.of(), null,
                integer(v.path("rejectedRecordCount"), 0), nonnegativeLong(v.path("sourceRecordCount")), nonnegativeLong(v.path("analysisRecordCount")),
                v.path("conversionProof") instanceof ObjectNode proof ? proof : null), Map.copyOf(issues), stored.sha256());
    }
    private static String sample(VariantAnalysisJob job) { return job.getSelectedSampleId() == null ? job.getRequestedSampleId() : job.getSelectedSampleId(); }
    private static int integer(JsonNode n, int minimum) {
        require(n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= minimum, "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED"); return n.intValue();
    }
    private static long positiveLong(JsonNode n) { require(n.isIntegralNumber() && n.canConvertToLong() && n.longValue() > 0, "EXECUTION_SNAPSHOT_TYPE_INVALID"); return n.longValue(); }
    private static long nonnegativeLong(JsonNode n) { require(n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 0, "EXECUTION_SNAPSHOT_TYPE_INVALID"); return n.longValue(); }
    public static String code(Exception ex) { return ex.getMessage() != null && ex.getMessage().matches("[A-Z][A-Z0-9_]+") ? ex.getMessage() : "EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE"; }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
