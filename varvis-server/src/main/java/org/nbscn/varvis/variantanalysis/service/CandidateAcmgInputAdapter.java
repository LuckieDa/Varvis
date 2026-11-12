package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.provider.ExplicitAlphaMissenseDispatcher;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Blocker;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.ExplicitInput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit coordination of already hydrated/ranked candidates and existing context inputs.
 * No file scan, normalization, transcript selection or patient hypothesis production occurs here.
 * The upload SHA is an existing task identity, never a reference-sequence verification receipt.
 */
public final class CandidateAcmgInputAdapter {
    private final ObjectMapper mapper;
    private final List<ExplicitContextSourceLedger> sources;
    private final ExplicitEvidenceContextAssembler assembler;
    private final ExplicitAlphaMissenseDispatcher dispatcher;

    public CandidateAcmgInputAdapter(ObjectMapper mapper, PatientDiseaseContextBindingResolver resolver,
                                     List<ExplicitContextSourceLedger> sources, ExplicitAlphaMissenseDispatcher dispatcher) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.assembler = new ExplicitEvidenceContextAssembler(mapper, resolver, this.sources);
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public Result dispatch(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                            AnalysisContext analysis, List<VariantInterpretation> rankedCandidates, int requestedN,
                            List<ExplicitInput> inputs) {
        return dispatch(job, conversion, execution, analysis, rankedCandidates, requestedN, inputs, input -> List.of());
    }

    /** Explicit producer output. Its ledgers must be supplied alongside the explicit patient/transcript ledgers. */
    public Result dispatch(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                           AnalysisContext analysis, List<VariantInterpretation> rankedCandidates, int requestedN,
                           NativeSnvSourceProducer.Production production) {
        return dispatch(job, conversion, execution, analysis, rankedCandidates, requestedN, production.inputs(), input ->
                production.belongsTo(execution) ? production.blockers(input) : List.of(blocker("PRODUCED_SOURCE_EXECUTION_CONFLICT", "execution")));
    }

    private Result dispatch(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                            AnalysisContext analysis, List<VariantInterpretation> rankedCandidates, int requestedN,
                            List<ExplicitInput> inputs, java.util.function.Function<ExplicitInput, List<Blocker>> sourceGate) {
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(rankedCandidates, "rankedCandidates");
        Blocker taskIssue = taskIssue(job, conversion, execution, analysis);
        var assembled = assembler.assemble(analysis, rankedCandidates, requestedN, inputs, (candidate, input) -> {
            if (candidate == null) {
                var declarationIssues = sourceGate.apply(input);
                return declarationIssues.isEmpty() ? List.of(blocker("CANDIDATE_ID_REQUIRED", "candidateId")) : declarationIssues;
            }
            if (taskIssue != null) return List.of(taskIssue);
            var sourceIssues = sourceGate.apply(input);
            if (!sourceIssues.isEmpty()) return sourceIssues;
            try {
                verifyCandidate(candidate, input, execution);
                return List.of();
            } catch (InputBlocked blocked) {
                return List.of(blocker(blocked.reason, blocked.field));
            }
        });
        return new Result(provenance(conversion, execution, rankedCandidates, requestedN),
                dispatcher.dispatch(assembled, rankedCandidates, analysis));
    }

    private static Blocker taskIssue(VariantAnalysisJob job, BuildConversionResult conversion,
                              EngineExecutionResult execution, AnalysisContext analysis) {
        return taskIssue(job, conversion, execution, analysis, false);
    }

    private static Blocker taskIssue(VariantAnalysisJob job, BuildConversionResult conversion,
                              EngineExecutionResult execution, AnalysisContext analysis, boolean allowVerifiedConversion) {
        if (job == null || execution == null || execution.getInputSource() == null) {
            return blocker("EXECUTION_INPUT_SOURCE_REQUIRED", "execution.inputSource");
        }
        var source = execution.getInputSource();
        if (!Objects.equals(job.getId(), source.analysisId()) || !Objects.equals(analysis.getAnalysisId(), source.analysisId())
                || job.getAnalysisMode() != source.analysisMode() || analysis.getAnalysisMode() != source.analysisMode()
                || job.getGenomeAssembly() != source.inputAssembly()
                || !Objects.equals(job.getFileSha256(), source.uploadSha256())
                || !Objects.equals(path(job.getUploadedFile()), source.uploadedFile())
                || !Objects.equals(sample(job), source.sampleId())) {
            return blocker("ANALYSIS_TASK_SOURCE_CONFLICT", "execution.inputSource");
        }
        if (source.inputAssembly() == null) return blocker("ORIGINAL_INPUT_ASSEMBLY_REQUIRED", "job.genomeAssembly");
        if (conversion == null) return blocker("BUILD_CONVERSION_SOURCE_REQUIRED", "conversion");
        if (!Objects.equals(path(conversion.getOutputFile()), source.analysisVcf())) {
            return blocker("ANALYSIS_VCF_SOURCE_CONFLICT", "conversion.outputFile");
        }
        boolean converted = source.inputAssembly() == GenomeAssembly.GRCh37 && conversion.isConversionApplied();
        if (converted && allowVerifiedConversion) {
            try {
                var proof = conversion.getSourceProof();
                if (proof == null) return blocker("CONVERSION_SOURCE_PROOF_MISSING", "conversion");
                org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.verifyTask(proof, job, source.analysisVcf(), source.observedVcfSha256());
            } catch (Exception invalid) {
                return blocker(org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.code(invalid), "conversion");
            }
        } else if (source.inputAssembly() != GenomeAssembly.GRCh38 || conversion.isConversionApplied()) {
            return blocker("PRE_RANKING_LIFTOVER_UNSUPPORTED", "conversion");
        }
        if (source.analysisAssembly() != GenomeAssembly.GRCh38 || analysis.getGenomeAssembly() != source.analysisAssembly()
                || !converted && (!Objects.equals(source.uploadedFile(), source.analysisVcf()) || !"NONE".equals(conversion.getEngine())
                || conversion.getRejectedFile() != null || conversion.getRejectedRecordCount() != 0)) {
            return blocker("NATIVE_ANALYSIS_INPUT_NOT_ESTABLISHED", "conversion");
        }
        if (execution.getMetadata() == null || execution.getMetadata().getEngine() != AnalysisEngine.EXOMISER
                || execution.getMetadata().getStatus() != EngineExecutionStatus.RANKED
                || !Integer.valueOf(0).equals(execution.getExitCode()) || !execution.getFailureCode().isEmpty()
                || !sha(source.uploadSha256()) || !sha(execution.getRequestSha256()) || !sha(execution.getCommandSha256())
                || !sha(execution.getMetadata().getOutputSha256())) {
            return blocker("EXOMISER_EXECUTION_SOURCE_INCOMPLETE", "execution");
        }
        return null;
    }

    private void verifyCandidate(VariantInterpretation candidate, ExplicitInput input, EngineExecutionResult execution) {
        verifyCandidateSource(mapper, candidate, execution);
        AnalysisVcfCall call = candidate.getAnalysisVcfCall();
        // GT/DP/GQ/AD/phase completeness is not an AlphaMissense input gate.
        require(input.normalization() != null, "NORMALIZATION_PROOF_REQUIRED", "normalization");
        require(Objects.equals(call.getTargetAlleleIndex(), input.normalization().selectedAltIndex()),
                "ANALYSIS_VCF_TARGET_ALT_CONFLICT", "normalization.selectedAltIndex");
        JsonNode receipt = resolve(input.normalization().source());
        if (receipt == null) { throw new InputBlocked("SOURCE_RECORD_NOT_VERIFIED", "normalization.source"); }
        JsonNode recordOrdinal = receipt.path("analysisVcfRecordOrdinal");
        require(receipt.path("analysisVcfArtifactSha256").asText().equals(execution.getInputSource().uploadSha256())
                        && recordOrdinal.isIntegralNumber() && recordOrdinal.canConvertToInt()
                        && recordOrdinal.intValue() > 0 && recordOrdinal.intValue() == call.getMatchedRecordOrdinal(),
                "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED", "normalization.source.analysisVcf");
        if (NativeSnvSourceProducer.METHOD.equals(input.normalization().method())
                || NativeSnvSourceProducer.SMALL_VARIANT_METHOD.equals(input.normalization().method())) {
            // Generated receipts retain their execution binding even when consumed through the compatible explicit-input entry.
            var source = execution.getInputSource();
            JsonNode recorded = receipt.path("execution");
            var matched = source.matchedRecord(candidate.getObservation().getKey());
            JsonNode byteLength = recorded.path("analysisVcfByteLength"), mtime = recorded.path("analysisVcfMtimeNs");
            require(NativeSnvSourceProducer.VERSION.equals(input.normalization().methodVersion())
                            && source.unchangedDuringExecution() && source.fullVcfScans() == 1
                            && source.uploadSha256().equals(source.observedVcfSha256())
                            && execution.getRequestSha256().equals(recorded.path("requestSha256").asText())
                            && execution.getCommandSha256().equals(recorded.path("commandSha256").asText())
                            && execution.getMetadata().getOutputSha256().equals(recorded.path("outputSha256").asText())
                            && byteLength.isIntegralNumber() && byteLength.canConvertToLong() && byteLength.longValue() == source.observedVcfByteLength()
                            && mtime.isIntegralNumber() && mtime.canConvertToLong() && mtime.longValue() == source.observedVcfMtimeNs()
                            && recorded.path("unchangedDuringExecution").isBoolean() && recorded.path("unchangedDuringExecution").booleanValue()
                            && recorded.path("fullVcfScans").isIntegralNumber() && recorded.path("fullVcfScans").canConvertToInt()
                            && recorded.path("fullVcfScans").intValue() == 1
                            && matched != null && mapper.valueToTree(matched).equals(receipt.path("matchedRecord"))
                            && matched.mappingToken().equals(input.normalization().originalRecord()),
                    "PRODUCED_SOURCE_EXECUTION_CONFLICT", "normalization.source.execution");
        }
    }

    static Blocker sourceIssue(ObjectMapper mapper, VariantAnalysisJob job, BuildConversionResult conversion,
                               EngineExecutionResult execution, AnalysisContext analysis, VariantInterpretation candidate) {
        return sourceIssue(mapper, job, conversion, execution, analysis, candidate, false);
    }

    static Blocker sourceIssue(ObjectMapper mapper, VariantAnalysisJob job, BuildConversionResult conversion,
                               EngineExecutionResult execution, AnalysisContext analysis, VariantInterpretation candidate,
                               boolean allowVerifiedConversion) {
        Blocker issue = taskIssue(job, conversion, execution, analysis, allowVerifiedConversion);
        if (issue != null) return issue;
        try {
            verifyCandidateSource(mapper, candidate, execution);
            return null;
        } catch (InputBlocked blocked) { return blocker(blocked.reason, blocked.field); }
    }

    private static void verifyCandidateSource(ObjectMapper mapper, VariantInterpretation candidate, EngineExecutionResult execution) {
        var matches = execution.getInterpretations().stream().filter(Objects::nonNull)
                .filter(row -> Objects.equals(row.getCandidateId(), candidate.getCandidateId())).toList();
        require(matches.size() == 1, matches.isEmpty() ? "ENGINE_CANDIDATE_NOT_FOUND" : "ENGINE_CANDIDATE_AMBIGUOUS", "candidateId");
        VariantInterpretation original = matches.get(0);
        require(candidate.getObservation() != null && original.getObservation() != null
                        && Objects.equals(candidate.getObservation().getKey(), original.getObservation().getKey())
                        && Objects.equals(candidate.getObservation().getSampleId(), execution.getInputSource().sampleId())
                        && Objects.equals(original.getObservation().getSampleId(), execution.getInputSource().sampleId())
                        && mapper.valueToTree(candidate.getCandidateIdentity()).equals(mapper.valueToTree(original.getCandidateIdentity()))
                        && mapper.valueToTree(candidate.getExomiserCandidateFeatures()).equals(mapper.valueToTree(original.getExomiserCandidateFeatures())),
                "ENGINE_CANDIDATE_SOURCE_CONFLICT", "candidate");
        AnalysisVcfCall call = candidate.getAnalysisVcfCall();
        if (call == null) { throw new InputBlocked("ANALYSIS_VCF_CALL_REQUIRED", "analysisVcfCall"); }
        require(mapper.valueToTree(call).equals(mapper.valueToTree(original.getAnalysisVcfCall())),
                "ANALYSIS_VCF_CALL_SOURCE_CONFLICT", "analysisVcfCall");
        require(call.getMatchStatus() == AnalysisVcfCall.MatchStatus.MATCHED,
                "ANALYSIS_VCF_" + call.getMatchStatus().name(), "analysisVcfCall.matchStatus");
        require("GRCh38".equals(call.getAssembly()), "ANALYSIS_VCF_ASSEMBLY_CONFLICT", "analysisVcfCall.assembly");
    }

    private JsonNode resolve(SourceRef ref) {
        List<JsonNode> matches = new ArrayList<>();
        for (var source : sources) {
            try { matches.add(source.resolve(ref)); } catch (IllegalArgumentException ignored) { }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private ObjectNode provenance(BuildConversionResult conversion, EngineExecutionResult execution,
                                    List<VariantInterpretation> ranked, int requestedN) {
        ObjectNode value = mapper.createObjectNode().put("source", "EXOMISER_CANDIDATE_INPUT_ASSOCIATION")
                .put("version", "1.0.0").put("acceptanceScope", ExplicitEvidenceContextAssembler.ACCEPTANCE_SCOPE)
                .put("vcfRescanned", false).put("referenceValidationProduced", false);
        if (execution != null && execution.getInputSource() != null) {
            var source = execution.getInputSource();
            value.put("analysisId", source.analysisId()).put("uploadArtifactSha256", source.uploadSha256())
                    .put("uploadHashSource", "EXISTING_UPLOAD_TASK_IDENTITY_NOT_REHASHED")
                    .put("originalInputAssembly", source.inputAssembly() == null ? null : source.inputAssembly().name())
                    .put("analysisAssembly", source.analysisAssembly() == null ? null : source.analysisAssembly().name())
                    .put("analysisVcfIsUploadedFile", Objects.equals(source.uploadedFile(), source.analysisVcf()))
                    .put("requestSha256", execution.getRequestSha256()).put("commandSha256", execution.getCommandSha256())
                    .put("outputSha256", execution.getMetadata().getOutputSha256());
        }
        if (conversion != null) {
            value.put("conversionApplied", conversion.isConversionApplied())
                    .put("conversionEngine", portable(conversion.getEngine()))
                    .put("rejectedRecordCount", conversion.getRejectedRecordCount())
                    .put("sourceRecordCount", conversion.getSourceRecordCount()).put("analysisRecordCount", conversion.getAnalysisRecordCount());
        }
        var rows = value.putArray("candidateAssociations");
        for (var candidate : ranked.subList(0, Math.min(requestedN, ranked.size()))) {
            var row = rows.addObject();
            row.put("candidateId", candidate == null ? null : candidate.getCandidateId());
            if (candidate != null) {
                row.put("canonicalVariantId", candidate.getCanonicalVariantId());
                row.set("analysisVcfCall", mapper.valueToTree(candidate.getAnalysisVcfCall()));
            }
        }
        return value;
    }

    private static Path path(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }
    private static String sample(VariantAnalysisJob job) {
        String selected = job.getSelectedSampleId();
        String value = selected == null || selected.isBlank() ? job.getRequestedSampleId() : selected;
        return value == null ? null : value.trim();
    }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String portable(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]+") ? value : "UNSPECIFIED_OR_REJECTED";
    }
    private static Blocker blocker(String reason, String field) { return new Blocker("CONTEXT_NOT_READY", reason, field); }
    private static void require(boolean condition, String reason, String field) {
        if (!condition) throw new InputBlocked(reason, field);
    }
    public record Result(ObjectNode inputProvenance, ExplicitAlphaMissenseDispatcher.DispatchResult dispatch) {
        public Result { inputProvenance = inputProvenance.deepCopy(); }
        @Override public ObjectNode inputProvenance() { return inputProvenance.deepCopy(); }
    }
    private static final class InputBlocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;
        private final String field;
        private InputBlocked(String reason, String field) { this.reason = reason; this.field = field; }
    }
}
