package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.util.*;
import java.util.function.Function;

/** One raw computation batch, then separate immutable evidence per selected context. */
public final class SpliceAiBusinessRuntime {
    /** Offline adapter only: this method never starts a runner or loads a BP7 policy. */
    public static ObjectNode calibrateDevelopment(ObjectMapper mapper, com.fasterxml.jackson.databind.JsonNode record,
            com.fasterxml.jackson.databind.JsonNode expectedResource, com.fasterxml.jackson.databind.JsonNode actualResource,
            TranscriptConsequence transcript, NormalizedVariantKey allele) {
        var result = mapper.createObjectNode().put("status", "NOT_EVALUATED").put("appliedStrength", "NONE")
                .put("calibrationId", DEVELOPMENT_CALIBRATION_ID)
                .put("sourceLocator", "https://www.clinicalgenome.org/docs/clingen-svi-splicing-subgroup-response-to-feedback/");
        String issue = PredictionResourceCompatibility.issue(expectedResource, actualResource);
        if (issue != null) return result.put("reasonCode", issue);
        if (!"CLINGEN_SVI_SPLICE_2024_03_22_SECTION_5".equals(actualResource.at("/compatibilityEvidence/calibrationId").asText()))
            return result.put("reasonCode", "CALIBRATION_RESOURCE_IDENTITY_MISMATCH");
        return calibrateAssociatedDevelopment(mapper, record, transcript, allele);
    }
    /** Caller must establish exact annotation/reference association and current runtime identity first. */
    static ObjectNode calibrateAssociatedDevelopment(ObjectMapper mapper, com.fasterxml.jackson.databind.JsonNode record,
            TranscriptConsequence transcript, NormalizedVariantKey allele) {
        var result = mapper.createObjectNode().put("status", "NOT_EVALUATED").put("appliedStrength", "NONE")
                .put("calibrationId", "CLINGEN_SVI_SPLICE_2024_03_22_SECTION_5")
                .put("sourceLocator", "https://www.clinicalgenome.org/docs/clingen-svi-splicing-subgroup-response-to-feedback/");
        String consequence = transcript.getConsequence();
        if (consequence == null || consequence.contains("splice_donor_variant") || consequence.contains("splice_acceptor_variant")
                || !(IntronicSplicePolicy.missense(transcript) || IntronicSplicePolicy.spliceCandidate(transcript)))
            return result.put("reasonCode", "SPLICE_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE");
        if (IntronicSplicePolicy.intronic(transcript) && (transcript.getBp7IntronicSignedOffset() == null
                || Math.abs((long) transcript.getBp7IntronicSignedOffset()) <= 2))
            return result.put("reasonCode", "SPLICE_NONCANONICAL_POSITION_UNVERIFIED");
        if (!"GRCh38".equals(allele.getGenomeAssembly()) || !record.path("queryAllele").equals(mapper.valueToTree(allele))
                || transcript.getVersionedTranscriptId() == null || transcript.getGeneId() == null
                || !record.path("transcriptId").asText().equals(transcript.getVersionedTranscriptId())
                || !record.path("geneId").asText().equals(transcript.getGeneId())) return result.put("reasonCode", "SPLICE_RECORD_IDENTITY_UNRESOLVED");
        for (String kind : List.of("AG", "AL", "DG", "DL")) {
            var delta = record.path("DP_" + kind);
            if (delta.isIntegralNumber() && delta.canConvertToLong()
                    && (delta.asLong() < -SpliceAiRawBatchRunner.DISTANCE_BP || delta.asLong() > SpliceAiRawBatchRunner.DISTANCE_BP))
                return result.put("reasonCode", "SPLICE_EVENT_OUTSIDE_CONFIGURED_WINDOW");
        }
        var events = PredictionObservationAssociation.spliceEvents(mapper, record, allele.getPosition()); result.set("observation", events);
        if (!"PARSED".equals(events.path("status").asText())) return result.put("reasonCode", events.path("reasonCode").asText());
        var score = PredictionCalibration.decimal(events.path("rawScore").asText());
        if (score == null) return result.put("reasonCode", "SPLICE_EVENT_FIELDS_INCOMPLETE");
        String direction = developmentDirection(score);
        return result.put("status", "CALIBRATED").put("direction", direction).put("rawScore", events.path("rawScore").asText())
                .put("appliedStrength", "UNINFORMATIVE".equals(direction) ? "NONE" : "SUPPORTING")
                .put("reasonCode", "UNINFORMATIVE".equals(direction) ? "SCORE_UNINFORMATIVE" : "CALIBRATED_INTERVAL");
    }
    private static final String DEVELOPMENT_CALIBRATION_ID = "CLINGEN_SVI_SPLICE_2024_03_22_SECTION_5";
    private static final java.math.BigDecimal BENIGN_MAXIMUM = new java.math.BigDecimal("0.1");
    private static final java.math.BigDecimal HARMFUL_MINIMUM = new java.math.BigDecimal("0.2");

    static String developmentBenignMaximum() { return BENIGN_MAXIMUM.toPlainString(); }

    private static String developmentDirection(java.math.BigDecimal score) {
        if (score.compareTo(HARMFUL_MINIMUM) >= 0) return "HARMFUL";
        if (score.compareTo(BENIGN_MAXIMUM) <= 0) return "BENIGN";
        return "UNINFORMATIVE";
    }

    static JsonNode recordedDevelopmentInterval(ObjectMapper mapper, JsonNode mechanism) {
        if (!DEVELOPMENT_CALIBRATION_ID.equals(mechanism.path("calibrationId").asText())
                || !"CALIBRATED".equals(mechanism.path("status").asText())) return mapper.missingNode();
        var score = PredictionCalibration.decimal(mechanism.path("rawScore").asText());
        if (score == null || score.signum() < 0 || score.compareTo(java.math.BigDecimal.ONE) > 0
                || !developmentDirection(score).equals(mechanism.path("direction").asText())) return mapper.missingNode();
        String lower = BENIGN_MAXIMUM.toPlainString(), upper = HARMFUL_MINIMUM.toPlainString();
        return switch (mechanism.path("direction").asText()) {
            case "BENIGN" -> interval(mapper, "0", lower, true);
            case "HARMFUL" -> interval(mapper, upper, "1", true);
            default -> interval(mapper, lower, upper, false);
        };
    }

    private static ObjectNode interval(ObjectMapper mapper, String lower, String upper, boolean inclusive) {
        return mapper.createObjectNode().put("lower", lower).put("upper", upper)
                .put("lowerInclusive", inclusive).put("upperInclusive", inclusive);
    }

    private final Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> run;
    private final SpliceAiProvider provider;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding splice;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding vep;
    private final SpliceAiCriterionEvaluator evaluator;
    private final ObjectMapper mapper;
    private ObjectNode batchLineage;

    public SpliceAiBusinessRuntime(ObjectMapper mapper, SpliceAiRawBatchRunner runner) { this(mapper, runner::run); }
    SpliceAiBusinessRuntime(ObjectMapper mapper, Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> run) {
        this.mapper = mapper;
        this.run = run;
        provider = new SpliceAiProvider(new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties());
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper));
        evaluator = new SpliceAiCriterionEvaluator(mapper);
        splice = wiring.bind(SpliceAiCriterionEvaluator.loadProfile(mapper), "SPLICEAI_V1_3_1_RAW_GRCH38_LOCAL_COMPUTE");
        try (var input = getClass().getResourceAsStream("/acmg/spliceai-business-vep-identity-v1.json")) {
            vep = wiring.bind(mapper.readTree(input), "VEP_116_GRCH38");
            if (!vep.getIdentityFingerprintSha256().equals(SpliceAiCriterionEvaluator.loadBp7Profile(mapper)
                    .at("/bp7PositionRules/resourceBinding/identityFingerprintSha256").asText())) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new IllegalArgumentException("VEP_RESOURCE_IDENTITY_MISMATCH", invalid); }
    }
    record Input(ObjectNode context, VariantInterpretation candidate, TranscriptConsequence transcript, String selectionId) { }
    record Output(List<ObjectNode> atomic, List<ObjectNode> evaluations) { }
    SpliceAiRawBatchRunner.BatchResult compute(List<Input> inputs) {
        var queries = new LinkedHashMap<NormalizedVariantKey, SpliceAiRawBatchRunner.Query>();
        for (var i : inputs) {
            var key = i.candidate().getObservation().getKey();
            queries.putIfAbsent(key, new SpliceAiRawBatchRunner.Query(SpliceAiRawBatchRunner.stableQueryId(key),
                    key, i.transcript().getGeneSymbol(), i.transcript().getVersionedTranscriptId()));
        }
        var started = java.time.Instant.now(); long clock = System.nanoTime();
        SpliceAiRawBatchRunner.BatchResult result;
        try { result = Objects.requireNonNull(run.apply(List.copyOf(queries.values()))); }
        catch (RuntimeException failed) { result = SpliceAiRawBatchRunner.BatchResult.failed("SPLICEAI_BATCH_EXECUTION_FAILED", null); }
        batchLineage = mapper.createObjectNode().put("startedAt", started.toString()).put("completedAt", java.time.Instant.now().toString())
                .put("elapsedMillis", (System.nanoTime() - clock) / 1_000_000).put("uniqueAlleles", queries.size())
                .put("contextCount", inputs.size()).put("batchInvocations", 1).put("status", result.getStatus())
                .put("rawResultCanonicalSha256", result.getResult() == null ? null : new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(result.getResult()))
                .put("distanceArgument", SpliceAiRawBatchRunner.DISTANCE_BP).put("maskArgument", SpliceAiRawBatchRunner.MASK);
        return result;
    }
    ObjectNode batchLineage() { return batchLineage == null ? null : batchLineage.deepCopy(); }
    Output evaluate(Input input, AnalysisContext analysis, SpliceAiRawBatchRunner.BatchResult batch) {
        return evaluate(input, analysis, batch, Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BP7"));
    }
    Output evaluate(Input input, AnalysisContext analysis, SpliceAiRawBatchRunner.BatchResult batch, Set<String> clauses) {
        if (!Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BP7").containsAll(clauses))
            throw new IllegalArgumentException("SPLICEAI_CLAUSE_MASK_INVALID");
        if (clauses.isEmpty()) return new Output(List.of(), List.of());
        var atomic = new ArrayList<>(List.of(observe(input, analysis, batch)));
        var pair = new HashSet<>(clauses); pair.remove("ACMG_AMP_2015:BP7");
        var evaluations = new ArrayList<>(evaluator.evaluateBusinessClauses(input.context(), atomic.get(0), input.transcript().getConsequence(), input.selectionId(), pair));
        if (clauses.contains("ACMG_AMP_2015:BP7")) {
            var position = wiring.projectStoredTranscript(input.context(), input.candidate(), input.transcript(), vep);
            var bp4 = evaluations.stream().filter(e -> "ACMG_AMP_2015:BP4".equals(e.path("criterionId").asText())).findFirst().orElse(null);
            evaluations.add(evaluator.evaluateBusinessBp7(input.context(), atomic.get(0), position, bp4, input.selectionId()));
            atomic.add(position);
        }
        return new Output(atomic, evaluations);
    }
    /** Source projection only; this path does not execute any legacy prediction criterion. */
    ObjectNode observe(Input input, AnalysisContext analysis, SpliceAiRawBatchRunner.BatchResult batch) {
        var rawProvider = new VariantEvidenceProvider() {
            public String source() { return "SPLICEAI"; }
            public String version() { return splice.getResourceVersion(); }
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext context) {
                return Map.of(input.candidate().getObservation().getKey(), provider.projectRawResult(
                        input.candidate(), input.transcript(), batch, splice.getResourceVersion()));
            }
        };
        var atomic = new ArrayList<>(wiring.annotateVariantProvider(input.context(), rawProvider, input.candidate(), analysis, splice));
        if (atomic.size() != 1) throw new IllegalArgumentException("ATOMIC_EVIDENCE_INSTANCE_INVALID");
        return atomic.get(0);
    }
}
