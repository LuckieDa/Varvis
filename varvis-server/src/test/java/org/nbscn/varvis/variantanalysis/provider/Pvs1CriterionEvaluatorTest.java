package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class Pvs1CriterionEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-05T01:00:00Z"), ZoneOffset.UTC);

    private Pvs1CriterionEvaluator evaluator;
    private ObjectNode context;
    private ObjectNode atomic;
    private ObjectNode selection;

    @BeforeEach
    void setUp() throws Exception {
        evaluator = new Pvs1CriterionEvaluator(mapper);
        NormalizedVariantKey key = new NormalizedVariantKey(
                "GRCh38", "12", 120989032, "C", "T");
        context = hnf1aContext(key);
        RuntimeEvidenceV2ProviderWiring wiring = new RuntimeEvidenceV2ProviderWiring(
                mapper, adapter, clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                vepManifest(), "VEP_116_GRCH38");
        atomic = wiring.annotateTranscriptProvider(
                context, fixedVepProvider(),
                RuntimeEvidenceV2ProviderWiringTest.variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), binding).get(0);
        selection = evaluator.createZeroMatchSelection(context);
    }

    @Test
    void exactFixedPointProducesOnlyPvs1VeryStrongMet() throws Exception {
        ObjectNode result = evaluator.evaluate(context, atomic, selection);

        assertEquals("ACMG_AMP_2015:PVS1", result.path("criterionId").asText());
        assertEquals("APPLICABLE",
                result.path("criterionApplicability").path("status").asText());
        assertEquals("MET", result.path("evaluationResult").path("status").asText());
        assertEquals("VERY_STRONG", result.path("baseStrength").asText());
        assertEquals("VERY_STRONG", result.path("appliedStrength").asText());
        assertEquals("NONE", result.path("conflict").path("status").asText());
        assertEquals("NOT_REQUIRED", result.path("review").path("status").asText());
        assertEquals(List.of(atomic.path("evidenceId").asText()),
                mapper.convertValue(result.path("evidenceIds"), List.class));
        assertEquals("ZERO_MATCH", selection.path("outcome").asText());
        assertEquals(0, selection.path("candidateOverlayIds").size());
        assertStableIds(result, selection);
    }

    @Test
    void selectorMismatchIsUnknownAndNeverNotMet() throws Exception {
        ObjectNode mismatch = context.deepCopy();
        ((ObjectNode) mismatch.path("disease")).put("mondoId", "MONDO:0000001");
        refreshContextId(mismatch);
        ObjectNode mismatchSelection = evaluator.createZeroMatchSelection(mismatch);

        ObjectNode result = evaluator.evaluate(mismatch, atomic, mismatchSelection);

        assertNotEvaluated(result, "CONTEXT_MONDO_MISMATCH");
    }

    @Test
    void unavailableAndMissingVepInputsCannotProduceMetOrNotMet() {
        ObjectNode unavailable = atomic.deepCopy();
        ((ObjectNode) unavailable.path("resourceAvailability")).put("status", "UNAVAILABLE");
        assertNotEvaluated(evaluator.evaluate(context, unavailable, selection),
                "VEP_RESOURCE_NOT_AVAILABLE");

        ObjectNode missing = atomic.deepCopy();
        for (int index = missing.path("measurements").size() - 1; index >= 0; index--) {
            if ("vep.exon".equals(missing.path("measurements").get(index)
                    .path("metricId").asText())) {
                ((com.fasterxml.jackson.databind.node.ArrayNode)
                        missing.path("measurements")).remove(index);
            }
        }
        assertNotEvaluated(evaluator.evaluate(context, missing, selection),
                "VEP_EXON_MISSING");
    }

    @Test
    void forgedSelectionCannotProduceMet() {
        ObjectNode forged = selection.deepCopy();
        forged.put("selectionId", "cspec-selection-sha256-" + "0".repeat(64));

        ObjectNode result = evaluator.evaluate(context, atomic, forged);

        assertNotEvaluated(result, "CSPEC_SELECTION_IDENTITY_MISMATCH");
        assertNotEquals(selection.path("selectionId").asText(),
                result.path("ruleBinding").path("cspecSelectionId").asText());
    }

    @Test
    void selectionRegistryMismatchCannotProduceMet() throws Exception {
        ObjectNode mismatch = selection.deepCopy();
        mismatch.put("overlayRegistrySha256", "0".repeat(64));
        refreshSelectionId(mismatch);

        assertNotEvaluated(evaluator.evaluate(context, atomic, mismatch),
                "CSPEC_SELECTION_REGISTRY_MISMATCH");
    }

    @Test
    void evaluationAndSelectionAreDeterministicAndNeverEmitNotMet() {
        ObjectNode secondSelection = evaluator.createZeroMatchSelection(context);
        ObjectNode first = evaluator.evaluate(context, atomic, selection);
        ObjectNode second = evaluator.evaluate(context, atomic, secondSelection);

        assertEquals(selection.path("selectionId").asText(),
                secondSelection.path("selectionId").asText());
        assertEquals(first.path("deterministicTrace").path("traceSha256").asText(),
                second.path("deterministicTrace").path("traceSha256").asText());
        assertEquals(first.path("evaluationId").asText(),
                second.path("evaluationId").asText());
        assertFalse(first.toString().contains("NOT_MET"));
        assertFalse(evaluator.evaluate(context, null, selection)
                .toString().contains("NOT_MET"));
    }

    private void assertNotEvaluated(ObjectNode result, String reason) {
        assertEquals("UNKNOWN",
                result.path("criterionApplicability").path("status").asText());
        assertEquals(reason,
                result.path("criterionApplicability").path("reasonCode").asText());
        assertEquals("NOT_EVALUATED",
                result.path("evaluationResult").path("status").asText());
        assertEquals(reason,
                result.path("evaluationResult").path("reasonCode").asText());
        assertEquals("NONE", result.path("appliedStrength").asText());
        assertFalse(result.toString().contains("NOT_MET"));
    }

    private void assertStableIds(ObjectNode evaluation, ObjectNode candidateSelection)
            throws IOException {
        ObjectNode selectionProjection = candidateSelection.deepCopy();
        selectionProjection.remove(List.of("selectionId", "conflict", "review"));
        assertEquals("cspec-selection-sha256-"
                        + adapter.canonicalSha256(selectionProjection),
                candidateSelection.path("selectionId").asText());

        ObjectNode traceProjection = evaluation.path("deterministicTrace").deepCopy();
        traceProjection.remove("traceSha256");
        assertEquals(adapter.canonicalSha256(traceProjection),
                evaluation.path("deterministicTrace").path("traceSha256").asText());

        ObjectNode evaluationProjection = evaluation.deepCopy();
        evaluationProjection.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + adapter.canonicalSha256(evaluationProjection),
                evaluation.path("evaluationId").asText());
    }

    private ObjectNode hnf1aContext(NormalizedVariantKey key) throws IOException {
        ObjectNode value = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(
                mapper, adapter, key, "ENST00000257555");
        ((ObjectNode) value.path("gene"))
                .put("hgncId", "HGNC:11621")
                .put("symbol", "HNF1A");
        ((ObjectNode) value.path("disease"))
                .put("mondoId", "MONDO:0015967")
                .put("label", "monogenic diabetes");
        value.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) value.path("transcript"))
                .put("accession", "ENST00000257555")
                .put("version", 11)
                .put("selectionPolicy", "PINNED_MANE_SELECT")
                .put("annotationSourceVersion", "116.0");
        refreshContextId(value);
        return value;
    }

    private void refreshContextId(ObjectNode value) throws IOException {
        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", value.path("scope").deepCopy());
        projection.put("alleleId", value.path("variant").path("alleleId").asText());
        projection.put("hgncId", value.path("gene").path("hgncId").asText());
        projection.put("mondoId", value.path("disease").path("mondoId").asText());
        projection.put("moi", value.path("moi").asText());
        ObjectNode transcript = projection.putObject("transcript");
        transcript.put("accession", value.path("transcript").path("accession").asText());
        transcript.put("version", value.path("transcript").path("version").asInt());
        projection.put("patientContextId",
                value.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId",
                value.path("patientContext").path("familyContextId").deepCopy());
        value.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
    }

    private void refreshSelectionId(ObjectNode value) throws IOException {
        ObjectNode projection = value.deepCopy();
        projection.remove(List.of("selectionId", "conflict", "review"));
        value.put("selectionId", "cspec-selection-sha256-"
                + adapter.canonicalSha256(projection));
    }

    private TranscriptAnnotationProvider fixedVepProvider() {
        TranscriptConsequence consequence = new TranscriptConsequence(
                "HNF1A", "ENSG00000135100", "ENST00000257555",
                "ENST00000257555.11:c.526C>T", "ENSP00000257555.5:p.Gln176Ter",
                "stop_gained&splice_region_variant", "ENSG00000135100",
                "ENST00000257555", "Ensembl", "HIGH", "2/10", null,
                true, true, false, "176", "Q/*", "Caa/Taa",
                null, null, null, "VEP_CACHED", "COMPLETED", "116.0",
                clock.instant());
        return new TranscriptAnnotationProvider() {
            @Override
            public String source() {
                return "VEP_CACHED";
            }

            @Override
            public String version() {
                return "116.0";
            }

            @Override
            public List<TranscriptConsequence> annotate(
                    VariantInterpretation variant, AnalysisContext analysisContext) {
                return Collections.singletonList(consequence);
            }
        };
    }

    private ObjectNode vepManifest() throws IOException {
        Path path = Files.isRegularFile(Paths.get("..", "docs", "execution",
                "E2_RESOURCE_IDENTITY_MANIFEST.json"))
                ? Paths.get("..", "docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json")
                : Paths.get("docs", "execution", "E2_RESOURCE_IDENTITY_MANIFEST.json");
        return (ObjectNode) mapper.readTree(path.toFile());
    }
}
