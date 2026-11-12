package org.nbscn.varvis.variantanalysis.engine;

import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Truth-free result of one Exomiser engine attempt. */
public final class EngineExecutionResult {

    private final EngineMetadata metadata;
    private final List<VariantInterpretation> interpretations;
    private final Path workDirectory;
    private final String requestSha256;
    private final String commandSha256;
    private final String pedigreeSha256;
    private final Integer exitCode;
    private final String failureCode;
    private final InputSource inputSource;

    private EngineExecutionResult(EngineMetadata metadata,
                                  List<VariantInterpretation> interpretations,
                                  Path workDirectory,
                                  String requestSha256,
                                  String commandSha256,
                                  String pedigreeSha256,
                                  Integer exitCode,
                                  String failureCode) {
        this(metadata, interpretations, workDirectory, requestSha256, commandSha256, pedigreeSha256,
                exitCode, failureCode, null);
    }

    private EngineExecutionResult(EngineMetadata metadata, List<VariantInterpretation> interpretations,
                                  Path workDirectory, String requestSha256, String commandSha256,
                                  String pedigreeSha256, Integer exitCode, String failureCode, InputSource inputSource) {
        this.metadata = metadata;
        this.interpretations = interpretations == null
                ? Collections.<VariantInterpretation>emptyList()
                : Collections.unmodifiableList(new ArrayList<VariantInterpretation>(interpretations));
        this.workDirectory = workDirectory;
        this.requestSha256 = requestSha256 == null ? "" : requestSha256;
        this.commandSha256 = commandSha256 == null ? "" : commandSha256;
        this.pedigreeSha256 = pedigreeSha256 == null ? "" : pedigreeSha256;
        this.exitCode = exitCode;
        this.failureCode = failureCode == null ? "" : failureCode;
        this.inputSource = inputSource;
    }

    // Only the actual adapter publication path attaches this snapshot. Old/manual results remain unproven.
    EngineExecutionResult withInputSource(VariantAnalysisJob job, Path analysisVcf,
                                          GenomeAssembly analysisAssembly, String sampleId, AnalysisVcfAlleleIndex.IndexedCalls indexed,
                                          boolean unchangedDuringExecution) {
        return new EngineExecutionResult(metadata, interpretations, workDirectory, requestSha256, commandSha256,
                pedigreeSha256, exitCode, failureCode, new InputSource(job, analysisVcf, analysisAssembly, sampleId, indexed, unchangedDuringExecution));
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public InputSource getInputSource() { return inputSource; }

    // Only the server snapshot verifier in this package may re-establish a persisted witness.
    EngineExecutionResult withRecoveredInputSource(VariantAnalysisJob job, Path analysisVcf,
            GenomeAssembly assembly, String sampleId, AnalysisVcfAlleleIndex.IndexedCalls indexed, boolean unchanged) {
        return new EngineExecutionResult(metadata, interpretations, workDirectory, requestSha256, commandSha256,
                pedigreeSha256, exitCode, failureCode, new InputSource(job, analysisVcf, assembly, sampleId, indexed, unchanged));
    }

    /** In-memory task association, not normalization/REF proof or a public payload. No file is reread. */
    public static final class InputSource {
        private final String analysisId;
        private final GenomeAssembly inputAssembly;
        private final GenomeAssembly analysisAssembly;
        private final AnalysisMode analysisMode;
        private final String uploadSha256;
        private final String sampleId;
        private final Path uploadedFile;
        private final Path analysisVcf;
        private final AnalysisVcfAlleleIndex.IndexedCalls indexed;
        private final boolean unchangedDuringExecution;

        private InputSource(VariantAnalysisJob job, Path analysisVcf, GenomeAssembly analysisAssembly, String sampleId,
                            AnalysisVcfAlleleIndex.IndexedCalls indexed, boolean unchangedDuringExecution) {
            this.analysisId = job.getId();
            this.inputAssembly = job.getGenomeAssembly();
            this.analysisAssembly = analysisAssembly;
            this.analysisMode = job.getAnalysisMode();
            this.uploadSha256 = job.getFileSha256();
            this.sampleId = sampleId;
            this.uploadedFile = job.getUploadedFile() == null ? null : job.getUploadedFile().toAbsolutePath().normalize();
            this.analysisVcf = analysisVcf.toAbsolutePath().normalize();
            this.indexed = indexed;
            this.unchangedDuringExecution = unchangedDuringExecution;
        }

        public String analysisId() { return analysisId; }
        public GenomeAssembly inputAssembly() { return inputAssembly; }
        public GenomeAssembly analysisAssembly() { return analysisAssembly; }
        public AnalysisMode analysisMode() { return analysisMode; }
        public String uploadSha256() { return uploadSha256; }
        public String sampleId() { return sampleId; }
        public Path uploadedFile() { return uploadedFile; }
        public Path analysisVcf() { return analysisVcf; }
        public String observedVcfSha256() { return indexed.rawSha256(); }
        public long observedVcfByteLength() { return indexed.stamp().byteLength(); }
        public long observedVcfMtimeNs() { return indexed.stamp().mtimeNs(); }
        String observedVcfFileKey() { return java.util.Objects.toString(indexed.stamp().fileKey(), null); }
        public int fullVcfScans() { return indexed.fullScans(); }
        public boolean unchangedDuringExecution() { return unchangedDuringExecution; }
        public MatchedRecord matchedRecord(NormalizedVariantKey key) { return indexed.records().get(key); }
        public PublishedSampleFilter sampleFilter(NormalizedVariantKey key) { return indexed.sampleFilters().get(key); }
        boolean hasSampleFilters() { return !indexed.sampleFilters().isEmpty(); }
    }

    /** Internal allele-only summary from the existing scan: no ID, INFO, FORMAT or sample data. */
    public record MatchedRecord(String contig, int position, String reference, List<String> alternates,
                                int selectedAltIndex, int recordOrdinal) {
        public MatchedRecord { alternates = List.copyOf(alternates); }
        public String mappingToken() {
            return contig + "|" + position + "|" + reference + "|" + String.join(",", alternates) + "|" + selectedAltIndex;
        }
    }

    public static EngineExecutionResult unavailable(EngineMetadata metadata) {
        return new EngineExecutionResult(metadata, Collections.<VariantInterpretation>emptyList(), null, "", "", "", null,
                metadata == null ? "EXOMISER_UNAVAILABLE" : metadata.getUnavailableReason());
    }

    public static EngineExecutionResult completed(EngineMetadata metadata,
                                                  List<VariantInterpretation> interpretations,
                                                  Path workDirectory,
                                                  String requestSha256,
                                                  String commandSha256,
                                                  int exitCode) {
        return new EngineExecutionResult(metadata, interpretations, workDirectory, requestSha256, commandSha256, "", exitCode, "");
    }

    public static EngineExecutionResult completed(EngineMetadata metadata,
                                                  List<VariantInterpretation> interpretations,
                                                  Path workDirectory,
                                                  String requestSha256,
                                                  String commandSha256,
                                                  String pedigreeSha256,
                                                  int exitCode) {
        return new EngineExecutionResult(metadata, interpretations, workDirectory, requestSha256, commandSha256,
                pedigreeSha256, exitCode, "");
    }

    public static EngineExecutionResult failed(EngineMetadata metadata,
                                               Path workDirectory,
                                               String requestSha256,
                                               String commandSha256,
                                               Integer exitCode,
                                               String failureCode) {
        return new EngineExecutionResult(metadata, Collections.<VariantInterpretation>emptyList(), workDirectory,
                requestSha256, commandSha256, "", exitCode, failureCode);
    }

    public EngineMetadata getMetadata() {
        return metadata;
    }

    public List<VariantInterpretation> getInterpretations() {
        return interpretations;
    }

    public Path getWorkDirectory() {
        return workDirectory;
    }

    public String getRequestSha256() {
        return requestSha256;
    }

    public String getCommandSha256() {
        return commandSha256;
    }

    public String getPedigreeSha256() {
        return pedigreeSha256;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
