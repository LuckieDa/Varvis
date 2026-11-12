package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks.CatalogSnapshot;
import org.springframework.lang.Nullable;

/** Request-owned BP7 adapter. Receives the original prediction preparation, never a result's plan. */
public final class Bp7SelectedBatch {
    public static final String CRITERION = "ACMG_AMP_2015:BP7";
    public static final String VERSION = "1.0.2";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Bp7SelectedBatch.class);
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter hashes;
    private final CatalogSnapshot catalog;
    private final Bp7ComputationalRuntime core;
    @Nullable private final LiteratureSourceAssets archive;
    private int evaluations;

    public Bp7SelectedBatch(ObjectMapper mapper, CatalogSnapshot catalog) {
        this(mapper,catalog,null);
    }
    public Bp7SelectedBatch(ObjectMapper mapper, CatalogSnapshot catalog, @Nullable LiteratureSourceAssets archive) {
        this.mapper = Objects.requireNonNull(mapper); this.catalog = Objects.requireNonNull(catalog);
        this.archive = archive;
        hashes = new RuntimeEvidenceV2Adapter(mapper); core = new Bp7ComputationalRuntime(mapper);
    }
    public ObjectNode identity() {
        return mapper.createObjectNode().put("method", "BP7_SELECTED_SHARED_PREDICTION").put("version", VERSION)
                .put("coreVersion", Bp7ComputationalRuntime.VERSION).put("positionVersion", Bp7PositionAssessment.VERSION)
                .put("predictionDependencyVersion", Bp7PredictionDependency.VERSION)
                .put("policySha256", hashes.canonicalSha256(new Bp7PredictionDependency(mapper).policy()))
                .put("experimentSnapshotId", catalog.content().path("snapshotId").asText())
                .put("experimentSourceStatus", catalog.content().at("/material/status").asText())
                .put("clinicalApproval", false);
    }
    public int evaluatorCalls() { return evaluations; }

    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, @Nullable String hypothesisId,
                               JsonNode originalPreparation, @Nullable ObjectNode savedPrediction) {
        try {
            ObjectNode plan = null;
            for (var candidate : originalPreparation.path("plans")) if (candidateId.equals(candidate.path("candidateId").asText())
                    && Objects.equals(hypothesisId, candidate.path("hypothesisId").asText(null))) {
                if (plan != null) throw new IllegalArgumentException("BP7_PREPARED_PLAN_AMBIGUOUS");
                if (!(candidate instanceof ObjectNode object)) throw new IllegalArgumentException("BP7_PREPARED_PLAN_UNAVAILABLE");
                plan = object;
            }
            if (plan == null) throw new IllegalArgumentException("BP7_PREPARED_PLAN_UNAVAILABLE");
            var projection = selected.legacyProjection(candidateId, hypothesisId);
            // Early blocked prediction plans intentionally have no prepared context/projection.
            // Decide non-applicability only from the independently validated selected annotation.
            if (!"PREPARED".equals(plan.path("status").asText())) {
                var tx = new RuntimeEvidenceV2ProviderWiring(mapper, hashes)
                        .selectedVepConsequence(projection.context(), projection.queryCandidate());
                if (tx == null) throw new IllegalArgumentException("BP7_SELECTED_TRANSCRIPT_NOT_UNIQUE");
                var terms = new java.util.HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]")));
                if (terms.contains("synonymous_variant") && terms.contains("intron_variant"))
                    throw new IllegalArgumentException("BP7_CONSEQUENCE_AMBIGUOUS");
                boolean applicable = Bp7PositionAssessment.consequenceApplicable(terms);
                var blocked = mapper.createObjectNode().put("criterionId", CRITERION)
                        .put("status", applicable ? "NOT_EVALUATED" : "NOT_APPLICABLE").put("appliedStrength", "NONE")
                        .put("reasonCode", applicable ? "BP7_PREDICTION_PREPARATION_BLOCKED" : "BP7_CONSEQUENCE_NOT_APPLICABLE");
                if (applicable && plan.path("reasonCode").asText().matches("[A-Z][A-Z0-9_]+"))
                    blocked.set("sourceReasonCode", plan.path("reasonCode"));
                return blocked;
            }
            if (!plan.path("context").isObject() || !plan.path("sourceProjection").isObject())
                throw new IllegalArgumentException("BP7_PREPARED_PLAN_UNAVAILABLE");
            if (!hashes.canonicalSha256(projection.context()).equals(hashes.canonicalSha256(plan.path("context")))
                    || !hashes.canonicalSha256(projection.snapshot()).equals(hashes.canonicalSha256(plan.path("sourceProjection"))))
                throw new IllegalArgumentException("BP7_SELECTED_SOURCE_MISMATCH");
            evaluations++;
            var result = core.evaluate(plan.deepCopy(), projection.queryCandidate(), savedPrediction, catalog);
            if (archive != null) for (var match : result.at("/experimentalConflictCheck/matches")) {
                if (!"EXACT_ALLELE_GENE_TRANSCRIPT".equals(match.path("molecularMatch").asText())) continue;
                try { archive.retainExperiment(match.path("record")); }
                catch (java.io.IOException unavailable) {
                    LOGGER.warn("Could not retain public RNA experiment {}", match.at("/record/recordId").asText());
                }
            }
            return result;
        } catch (RuntimeException invalid) {
            String reason = invalid.getMessage();
            if (reason == null || !reason.matches("[A-Z][A-Z0-9_]+"))
                LOGGER.warn("BP7 selected execution failed for candidate {}", candidateId, invalid);
            return mapper.createObjectNode().put("criterionId", CRITERION).put("status", "NOT_EVALUATED").put("appliedStrength", "NONE")
                    .put("reasonCode", reason != null && reason.matches("[A-Z][A-Z0-9_]+") ? reason : "BP7_SELECTED_EXECUTION_UNAVAILABLE");
        }
    }

    /** Compact whitelist for ordinary transport; complete context/raw rows remain in private storage. */
    public static ObjectNode publicSummary(ObjectMapper mapper, JsonNode raw) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp7-selected-summary-v1")
                .put("coreStatus", raw.path("status").asText("NOT_EVALUATED"))
                .put("reviewRequired", raw.path("reviewRequired").asBoolean())
                .put("additionalPredictionPerformed", false).put("experimentalNormalityEstablished", false)
                .put("externalExperimentsCompletelyCovered", false).put("clinicalAdoption", false);
        for (String field : List.of("resultId", "policySha256")) if (raw.path(field).isTextual()) out.set(field, raw.path(field));
        out.put("policyId", raw.at("/policy/policyId").asText()).put("policyVersion", raw.at("/policy/version").asText());
        var position = raw.at("/computationalChecks/position");
        out.put("positionStatus", position.path("status").asText("NOT_EVALUATED"));
        if (position.path("selectedTranscriptId").asText().matches("ENST[0-9]+\\.[0-9]+")) out.set("selectedTranscriptId", position.path("selectedTranscriptId"));
        if (position.path("geometry").isObject()) out.set("geometry", position.path("geometry").deepCopy());
        var positionRule = position.path("positionRules");
        if ("SYNONYMOUS".equals(position.path("consequenceClass").asText())
                && positionRule.at("/synonymous/minimumZeroBasedDistanceFromExonEndInclusive").isIntegralNumber()) {
            out.putObject("positionRule").put("consequenceClass", "SYNONYMOUS")
                    .put("minimumFromExonEnd", positionRule.at("/synonymous/minimumZeroBasedDistanceFromExonEndInclusive").asInt())
                    .put("excludeFirstExonBase", positionRule.at("/synonymous/firstExonBaseExcluded").asBoolean());
        } else if ("INTRONIC".equals(position.path("consequenceClass").asText())
                && positionRule.at("/intronic/donorDistanceMinimumInclusive").isIntegralNumber()
                && positionRule.at("/intronic/acceptorDistanceMinimumInclusive").isIntegralNumber()) {
            out.putObject("positionRule").put("consequenceClass", "INTRONIC")
                    .put("minimumDonorDistance", positionRule.at("/intronic/donorDistanceMinimumInclusive").asInt())
                    .put("minimumAcceptorDistance", positionRule.at("/intronic/acceptorDistanceMinimumInclusive").asInt());
        }
        var prediction = raw.at("/computationalChecks/predictionDependency/dependency");
        var splice = prediction.path("calibration");
        if (SpliceAiBusinessRuntime.recordedDevelopmentInterval(mapper, splice).isObject()) {
            out.putObject("spliceCondition").put("rawScore", splice.path("rawScore").asText())
                    .put("bp4Status", prediction.at("/bp4/status").asText())
                    .put("benignMaximum", SpliceAiBusinessRuntime.developmentBenignMaximum());
        }
        var experiments = raw.path("experimentalConflictCheck");
        out.put("experimentCheckStatus", experiments.path("status").asText("UNAVAILABLE"))
                .put("functionalCheckStatus", raw.at("/functionalEffectCheck/status").asText("UNAVAILABLE"));
        var material = experiments.at("/sourceSnapshot/material");
        out.put("catalogSourceStatus", material.path("status").asText("UNAVAILABLE"));
        for (String field : List.of("contentSha256", "version")) if (material.at("/catalog/"+field).isTextual())
            out.put("catalog" + (field.equals("version") ? "Version" : "Sha256"), material.at("/catalog/"+field).asText());
        var records = out.putArray("matchedRecordIds");
        for (var match : experiments.path("matches")) {
            String id = match.at("/record/recordId").asText();
            records.add(id.matches("[A-Za-z0-9_-]{1,160}") ? id : "SOURCE_RECORD_ID_REDACTED");
            if ("EXACT_ALLELE_GENE_TRANSCRIPT".equals(match.path("molecularMatch").asText())) {
                var source=match.at("/record/source");
                var row=out.withArray("/experimentSources").addObject();
                for (String field:List.of("pmid","url","locator"))
                    if (source.path(field).isTextual()) row.set(field,source.path(field));
            }
        }
        out.put("sharedPredictionResultId", raw.at("/sharedPrediction/selectedPredictionResultId").asText());
        return out;
    }
}
