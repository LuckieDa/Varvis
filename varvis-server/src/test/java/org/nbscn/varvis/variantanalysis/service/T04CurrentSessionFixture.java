package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.AssemblyResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Attempt;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Blocker;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.CandidateAssembly;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual H2 claim and MAIN session issuer, with explicitly synthetic execution inputs.
 * The TSV runner and tiny-reference assembly-length readiness seam are synthetic;
 * the VCF parser, sample fields, reference bytes/REF check, store and session code run normally.
 * A session proves current input identity, never clinical source qualification.
 */
public final class T04CurrentSessionFixture implements AutoCloseable {
    public record AttemptSpec(String hypothesisId, ObjectNode context, List<Blocker> blockers) {
        public AttemptSpec { context = context == null ? null : context.deepCopy(); blockers = List.copyOf(blockers); }
        @Override public ObjectNode context() { return context == null ? null : context.deepCopy(); }
        public static AttemptSpec ready(String hypothesisId, ObjectNode context) { return new AttemptSpec(hypothesisId, context, List.of()); }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final String analysisId;
    private final String runId;
    private final List<String> criteria;
    private final JdbcTemplate jdbc;
    private final AcmgBusinessStore store;
    private final ParsedCandidateFixture.Run execution;
    private final NativeSnvSourceProducer.Production production;
    private final byte[] vcfBytes;
    private final String referenceSha256;
    private SelectedEvidencePreparation prepared;
    private SelectedEvidencePreparation generalPrepared;
    private String requestSha256;
    private List<VariantInterpretation> currentRanking;

    public T04CurrentSessionFixture(Path temporary, String analysisId, String runId, List<String> criteria) throws Exception {
        this(temporary, analysisId, runId, criteria, false, null);
    }

    public T04CurrentSessionFixture(Path temporary, String analysisId, String runId, List<String> criteria,
            boolean recoverablePublication) throws Exception {
        this(temporary, analysisId, runId, criteria, recoverablePublication, null);
    }

    /** A file-backed URL is used only by the isolated Writer/Reader regression, never the application database. */
    public T04CurrentSessionFixture(Path temporary, String analysisId, String runId, List<String> criteria,
            boolean recoverablePublication, String isolatedJdbcUrl) throws Exception {
        this.analysisId = analysisId; this.runId = runId; this.criteria = List.copyOf(criteria);
        Files.createDirectories(temporary);
        var ds = new DriverManagerDataSource(isolatedJdbcUrl == null ? "jdbc:h2:mem:t04-current-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1" : isolatedJdbcUrl, "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE variant_analysis_acmg_source (analysis_id VARCHAR(64) PRIMARY KEY, snapshot_sha256 VARCHAR(64), snapshot_json CLOB)");
        jdbc.execute("CREATE TABLE variant_analysis_acmg_run (run_id VARCHAR(64) PRIMARY KEY, analysis_id VARCHAR(64), request_sha256 VARCHAR(64), request_json CLOB, status VARCHAR(32), created_at VARCHAR(40), response_sha256 VARCHAR(64), response_json CLOB, private_json CLOB, UNIQUE(analysis_id,request_sha256))");
        jdbc.execute("CREATE TABLE variant_analysis_acmg_chunk (run_id VARCHAR(64), column_name VARCHAR(16), chunk_index INT, payload_sha256 VARCHAR(64), payload_text CLOB)");
        store = new AcmgBusinessStore(ds);
        String vcf = ParsedCandidateFixture.vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT:DP:GQ:AD:FT\t0/1:40:60:20,20:PASS")
                .replace("#CHROM", "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Synthetic depth\">\n"
                        + "##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Synthetic genotype quality\">\n"
                        + "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Synthetic allele depths\">\n"
                        + "##FORMAT=<ID=FT,Number=1,Type=String,Description=\"Synthetic sample filter\">\n#CHROM");
        // The actual coding-identity resolver accepts gene nomenclature characters, not underscores.
        // Keep the legacy scope-only fixture unchanged; normal raw-source replay uses a valid fictional symbol.
        String syntheticSymbol = recoverablePublication ? "SYNTHETICGENE" : "SYNTHETIC_GENE";
        execution = ParsedCandidateFixture.run(temporary.resolve("execution"), analysisId, GenomeAssembly.GRCh38,
                vcf, ParsedCandidateFixture.tsv("1\t11\t2\tT\tA\t" + syntheticSymbol + "\t1\tAD\t0.8"));
        currentRanking = execution.execution().getInterpretations();
        vcfBytes = Files.readAllBytes(execution.job().getUploadedFile());
        var existing = new ExplicitCaseInputCoordinatorTest();
        existing.temporary = temporary.resolve("reference"); Files.createDirectories(existing.temporary);
        var nativeProducer = existing.tinyReference(receipt -> { }).producer();
        production = nativeProducer.produce(execution.job(), execution.conversion(), execution.execution(),
                new AnalysisContext(analysisId, GenomeAssembly.GRCh38, AnalysisMode.WES), execution.execution().getInterpretations(), 1, List.of());
        referenceSha256 = ParsedCandidateFixture.sha(Files.readAllBytes(existing.temporary.resolve("tiny.fa")));
        var source = json.createObjectNode().put("schemaVersion", "t04-synthetic-native-session-fixture-v1")
                .put("dataClass", "SYNTHETIC").put("uploadedVcfRawSha256", ParsedCandidateFixture.sha(vcfBytes));
        // InputSource is intentionally an opaque native handle with record-style accessors,
        // not a Jackson bean. Keep actual source facts/pins explicitly; do not alter mapper safety.
        var nativeInput = execution.execution().getInputSource();
        assertTrue(nativeInput != null, "actual parsed adapter must publish a native input source");
        var inputAudit = source.putObject("inputSource").put("analysisId", nativeInput.analysisId())
                .put("inputAssembly", nativeInput.inputAssembly().name()).put("analysisAssembly", nativeInput.analysisAssembly().name())
                .put("analysisMode", nativeInput.analysisMode().name()).put("sampleId", nativeInput.sampleId())
                .put("uploadSha256", nativeInput.uploadSha256()).put("observedVcfSha256", nativeInput.observedVcfSha256())
                .put("byteLength", nativeInput.observedVcfByteLength()).put("fullVcfScans", nativeInput.fullVcfScans())
                .put("unchangedDuringExecution", nativeInput.unchangedDuringExecution());
        assertTrue(ParsedCandidateFixture.sha(vcfBytes).equals(inputAudit.path("uploadSha256").asText())
                && ParsedCandidateFixture.sha(vcfBytes).equals(inputAudit.path("observedVcfSha256").asText()),
                "both actual adapter pins must retain the original VCF bytes");
        source.putObject("enginePublication").put("requestSha256", execution.execution().getRequestSha256())
                .put("commandSha256", execution.execution().getCommandSha256())
                .put("outputSha256", execution.execution().getMetadata().getOutputSha256());
        source.set("nativeProductionProvenance", production.provenance());
        if (recoverablePublication) {
            // The normal processor records the real adapter terminal metadata before publication.
            // Reuse that completed-engine API; no version/fingerprint/score is invented by this fixture.
            execution.job().applyEngineMetadata(execution.execution().getMetadata());
            new AcmgExecutionSources(json, store).publish(execution.job(), execution.conversion(), execution.execution(), ranked());
        } else store.saveSource(analysisId, json.writeValueAsString(source));
    }

    public String analysisId() { return analysisId; }
    public String runId() { return runId; }
    public String sampleId() { return execution.execution().getInputSource().sampleId(); }
    public AcmgBusinessStore store() { return store; }
    public VariantAnalysisJob job() { return execution.job(); }
    public List<VariantInterpretation> ranked() { return currentRanking; }
    /** Adversarial caller projection only; native publication and actual VCF bytes remain original. */
    public void replaceRankedForTamperTest(int index, VariantInterpretation replacement) {
        var changed = new ArrayList<>(currentRanking); changed.set(index, replacement); currentRanking = List.copyOf(changed);
    }
    public SelectedEvidencePreparation prepared() {
        if (prepared == null) throw new IllegalStateException("T04_FIXTURE_CLAIM_REQUIRED");
        return prepared;
    }
    /** Same actual claim inputs, with the ordinary context path retained separately from PP1-only selection. */
    public SelectedEvidencePreparation generalPrepared() {
        if (generalPrepared == null) throw new IllegalStateException("T04_FIXTURE_CLAIM_REQUIRED");
        return generalPrepared;
    }
    public AcmgExecutionSources.Recovered recovered() throws Exception {
        return new AcmgExecutionSources(json, store).recover(execution.job(), ranked());
    }
    public String candidateId() { return execution.execution().getInterpretations().get(0).getCandidateId(); }
    public String geneSymbol() { return execution.execution().getInterpretations().get(0).getExomiserCandidateFeatures().getGeneSymbol(); }
    public String referenceSha256() { return referenceSha256; }
    public String executionSourceSha256() { return store.source(analysisId).orElseThrow().sha256(); }
    public byte[] originalVcfBytes() { return vcfBytes.clone(); }

    public CurrentAcmgEvaluationSession issue(ObjectNode context) throws Exception {
        return issue(List.of(AttemptSpec.ready("hypothesis", context)));
    }

    public CurrentAcmgEvaluationSession issue(ObjectNode context, ObjectNode actualRuntimeIdentity) throws Exception {
        return issue(List.of(AttemptSpec.ready("hypothesis", context)), actualRuntimeIdentity);
    }

    public CurrentAcmgEvaluationSession issue(List<AttemptSpec> specifications) throws Exception {
        return issue(specifications, null);
    }

    public CurrentAcmgEvaluationSession issue(List<AttemptSpec> specifications, ObjectNode actualRuntimeIdentity) throws Exception {
        if (prepared != null) throw new IllegalStateException("T04_FIXTURE_ALREADY_CLAIMED");
        var attempts = new ArrayList<Attempt>();
        for (var spec : specifications) attempts.add(new Attempt(candidateId(), spec.hypothesisId(), null, null, null, spec.context(), spec.blockers()));
        var assembly = new AssemblyResult(analysisId, 1, 1, List.of(candidateId()),
                List.of(new CandidateAssembly(candidateId(), attempts)), List.of(), false);
        prepared = new SelectedEvidencePreparation(json, assembly, List.of(), production, execution.execution(),
                currentRanking, true);
        generalPrepared = new SelectedEvidencePreparation(json, assembly, List.of(), production, execution.execution(),
                currentRanking, false);
        var request = json.createObjectNode().put("dataClass", "SYNTHETIC").put("fixtureRun", runId);
        request.putObject("executionProfile").set("enabledCriteria", json.valueToTree(criteria));
        if (actualRuntimeIdentity != null) request.withObject("/executionProfile").set("actualRuntimeIdentity", actualRuntimeIdentity.deepCopy());
        request.putObject("localBatchPublishedInputIdentity").put("executionSourceSha256", executionSourceSha256())
                .put("candidatesRawSha256", AcmgBusinessStore.sha(json.writeValueAsString(currentRanking)));
        request.set("testOnlyPreparedAttempts", json.valueToTree(attempts));
        requestSha256 = new AcmgBusinessArtifacts(json).requestHash(request);
        assertTrue(store.claim(new AcmgBusinessStore.Run(runId, analysisId, requestSha256, json.writeValueAsString(request),
                "RUNNING", "2026-11-04T00:00:00Z", null, null, null)), "must claim a real isolated H2 run before issuance");
        return reissueSameClaim();
    }

    /** A new native capability for the same real claim is a cross-session negative control. */
    public CurrentAcmgEvaluationSession reissueSameClaim() {
        if (prepared == null) throw new IllegalStateException("T04_FIXTURE_CLAIM_REQUIRED");
        return CurrentAcmgEvaluationSession.issueAfterClaim(json, store, analysisId, runId, requestSha256, prepared);
    }

    @Override public void close() { jdbc.execute("SHUTDOWN"); }
}
