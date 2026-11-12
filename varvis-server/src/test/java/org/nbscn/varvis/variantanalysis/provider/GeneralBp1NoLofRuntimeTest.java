package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Normal GeneralBp1 consumer with SYNTHETIC source originals and actual tiny-reference material.
 * The old project template must not acquire scientific authority through normal dispatch. */
class GeneralBp1NoLofRuntimeTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
    @TempDir Path temporary;

    private final class Run {
        final Bp1NoEstablishedLofSupplyTest.Fixture source;
        final GeneralBp1RuntimeTest effectsFixture = new GeneralBp1RuntimeTest();
        final AtomicInteger effectCalls = new AtomicInteger();
        Consumer<ObjectNode> effectChange = ignored -> { };
        boolean effectsMissing;
        Clock clock = NOW;
        Run(String symbol, String hgnc) throws Exception { source = new Bp1NoEstablishedLofSupplyTest.Fixture(temporary, symbol, hgnc); }
        Run() throws Exception { this("MAP2K1", "HGNC:6840"); }
        ObjectNode effects(JsonNode plan) {
            effectCalls.incrementAndGet(); var references = effectsFixture.effects(plan).deepCopy();
            effectChange.accept(references); return references;
        }
        ObjectNode evaluate(String label) throws Exception {
            // The shared producer serializes/replays the actual raw original, with a fixed scientific-validity clock.
            var runtime = new GeneralBp1Runtime(source.mapper, source.base.supply, source.catalog(clock),
                    (MissenseNativeSpliceBridge) null, null, effectsMissing ? null : this::effects, "b".repeat(64));
            var candidate = source.base.sequence.candidate;
            var out = runtime.evaluate(source.context, candidate, source.mapper.createObjectNode()
                    .put("candidateId", candidate.getCandidateId()).put("syntheticTransport", true));
            assertEquals(1, runtime.evaluations());
            assertFalse(out.has("rawSpliceExecution"), "Truncating dispatch must not borrow the old missense RAW branch");
            artifact(label, out); return out;
        }
        void notes(String text) throws Exception { source.reviewOriginal.withObject("/entContent").put("notes", text); source.rebindRaw(); }
        void artifact(String label, ObjectNode result) throws Exception {
            String configured = System.getProperty("t05.noLof.runtimeArtifacts");
            if (configured == null || configured.isBlank()) return;
            Path directory = Path.of(configured); Files.createDirectories(directory);
            Path file = directory.resolve("general-bp1-noLof-" + label + ".json");
            if (Files.exists(file)) file = directory.resolve("general-bp1-noLof-" + label + "-" + UUID.randomUUID() + ".json");
            var document = source.mapper.createObjectNode().put("schemaVersion", "t05-bp1-noLof-runtime-engineering-artifact-v1")
                    .put("dataClass", "SYNTHETIC_SOURCE_AND_REFERENCE").put("realReportedVariant", false)
                    .put("realNoLofQualification", false).put("clinicalValidationCompleted", false)
                    .put("clock", clock.instant().toString()).put("testVector", label).put("functionalProviderCalls", effectCalls.get());
            document.set("moduleResult", result.deepCopy());
            Files.write(file, source.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document), StandardOpenOption.CREATE_NEW);
        }
    }

    private static JsonNode qualification(ObjectNode out) { return out.at("/truncatingConditionCheck/noEstablishedLofQualification"); }
    private static ObjectNode projectTruncating(JsonNode check) {
        ObjectNode projected = ReflectionTestUtils.invokeMethod(GeneralBp1Runtime.class, "boundedTruncatingCheck", check);
        if (projected == null) throw new IllegalStateException("Public truncating projection missing");
        return projected;
    }

    @Test void publicSourceTransportSoftwareAndBiologicalReviewCoverageRemainSeparate() throws Exception {
        var run = new Run();var out = run.evaluate("separate-source-dimensions");
        var actual = out.at("/publicSummary/truncatingConditionCheck/noEstablishedLofQualification");
        assertEquals("CHECKED", actual.at("/sourceTransport/status").asText());
        assertFalse(actual.at("/sourceTransport/isBiologicalAbsenceConclusion").asBoolean(true));
        assertEquals("UNSUPPORTED", actual.at("/softwareCapability/status").asText());
        assertEquals("NOT_ESTABLISHED", actual.at("/reviewCoverage/status").asText());
        assertFalse(actual.at("/reviewCoverage/sameGeneOtherDiseasesChecked").asBoolean(true));
        assertFalse(actual.at("/reviewCoverage/absenceFromCatalogMeansNoLof").asBoolean(true));
        assertEquals(qualification(out).at("/sourceReviews/0/sourceBinding"), actual.at("/sourceReviews/0/sourceBinding"));
        assertEquals(qualification(out).path("reasonCode"), actual.at("/softwareCapability/reasonCode"));
        assertEquals("UNKNOWN", actual.path("status").asText());
        assertEquals("NOT_EVALUATED", out.path("status").asText());assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals(0, run.effectCalls.get());assertFalse(actual.path("absenceQualificationGranted").asBoolean(true));
    }

    @Test void legacySourceWithoutSavedReviewFieldsKeepsItsOriginalMissingInformation() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();var check = mapper.createObjectNode().put("status", "NOT_EVALUATED")
                .put("reasonCode", "BP1_NO_ESTABLISHED_LOF_DISEASE_CORRELATION_NOT_VERIFIED");
        check.putObject("noEstablishedLofQualification").put("status", "UNKNOWN").put("sourceCheckStatus", "UNAVAILABLE")
                .put("reasonCode", "BP1_NO_LOF_SOURCE_NOT_CONFIGURED").put("absenceQualificationGranted", false);
        check.putArray("missing").addObject().put("conditionId", "GENE_LOF_DISEASE_CORRELATION")
                .put("required", "SOURCE_QUALIFIED_NO_ESTABLISHED_CORRELATION").put("availableReasonCode", "BP1_NO_LOF_SOURCE_NOT_CONFIGURED");
        var projected = projectTruncating(check);var actual = projected.path("noEstablishedLofQualification");
        assertEquals(check.path("missing"), projected.path("missing"));assertEquals(check.path("reasonCode"), projected.path("reasonCode"));
        assertFalse(actual.has("reviewCoverage"));assertFalse(actual.has("sourceReviews"));assertFalse(actual.has("softwareCapability"));
        assertFalse(actual.path("absenceQualificationGranted").asBoolean(true));assertEquals("NONE", projected.path("appliedStrength").asText());
    }

    @Test void publicReviewLocationsAreBoundedWithoutCopyingOriginalParagraphsOrInventingMechanisms() throws Exception {
        var run = new Run();var out = run.evaluate("source-location-projection");
        var check = ((ObjectNode) out.path("truncatingConditionCheck")).deepCopy();
        var q = (ObjectNode) check.at("/publicSummary/noEstablishedLofQualification");
        q.put("rawJsonUtf8", "PRIVATE_RAW_DO_NOT_COPY").put("notes", "HI0 GoF PRIVATE_PARAGRAPH_DO_NOT_COPY");
        var prototype = q.at("/sourceReviews/0").deepCopy();var reviews = q.putArray("sourceReviews");
        for (int i = 0; i < 20; i++) {
            var review = ((ObjectNode) prototype).deepCopy().put("statementText", "PRIVATE_PARAGRAPH_DO_NOT_COPY");
            review.withObject("sourceBinding").put("specificationKey", "TEST_LOCATION_" + i)
                    .put("rawJsonUtf8", "PRIVATE_RAW_DO_NOT_COPY");reviews.add(review);
        }
        var actual = projectTruncating(check).path("noEstablishedLofQualification");
        assertEquals(16, actual.path("sourceReviews").size());assertEquals(20, actual.path("sourceReviewsTotal").asInt());
        assertTrue(actual.path("sourceReviewsTruncated").asBoolean());
        assertEquals("TEST_LOCATION_0", actual.at("/sourceReviews/0/sourceBinding/specificationKey").asText());
        String text = actual.toString();assertFalse(text.contains("PRIVATE_"));assertFalse(text.contains("HI0"));assertFalse(text.contains("GoF"));
        assertFalse(actual.has("biologicalMechanism"));assertFalse(actual.path("absenceQualificationGranted").asBoolean(true));
        assertEquals(20, q.path("sourceReviews").size());assertTrue(q.path("rawJsonUtf8").isTextual());
    }
    private static void unadopted(ObjectNode out) {
        assertEquals("NOT_EVALUATED", out.path("status").asText(), out::toPrettyString);
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertNotEquals("QUALIFIED", qualification(out).path("status").asText());
    }

    @Test void projectAuthoredFutureTemplateCannotScoreOrCallTheLaterFunctionalProvider() throws Exception {
        var run = new Run(); var out = run.evaluate("legacy-template-rejected"); unadopted(out);
        assertEquals("BP1_NO_LOF_LEGACY_PROJECT_TEMPLATE_NOT_SCIENTIFIC_REVIEW", out.path("reasonCode").asText());
        assertEquals("CHECKED", qualification(out).path("sourceCheckStatus").asText());
        assertEquals(0, run.effectCalls.get());
        assertFalse(out.path("publicSummary").toString().contains("rawJsonUtf8"));
        assertFalse(out.path("publicSummary").toString().contains("cdsSequence"));
    }

    @Test void noSourceDoesNotMakeSourceRequirementsOrCallerFlagsIntoAbsence() throws Exception {
        var run = new Run(); run.source.doc.remove("bp1LofAssociationRawSources");
        run.source.doc.put("noEstablishedLofCorrelation", true).put("hiScore", 0).put("mechanism", "GAIN_OF_FUNCTION");
        unadopted(run.evaluate("missing-raw")); assertEquals(0, run.effectCalls.get());
    }

    @Test void anEstablishedOtherLofDiseaseRemainsAnIndependentContraindication() throws Exception {
        var run = new Run("PTPN11", "HGNC:9644"); var out = run.evaluate("known-other-lof-disease");
        assertEquals("NOT_MET", out.path("status").asText(), out::toPrettyString);
        assertEquals("BP1_GENE_HAS_ESTABLISHED_LOF_DISEASE_CORRELATION", out.path("reasonCode").asText());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText()); assertEquals(0, run.effectCalls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"native", "HI0", "GOF", "not-recorded", "scope-only"})
    void nativeTransportWithoutImplementedReviewCoverageIsAnExplicitSoftwareGap(String notes) throws Exception {
        var run = new Run(); run.notes("native".equals(notes) ? Pp2PublishedAssessment.NOTES : notes);
        var out = run.evaluate("uninterpreted-" + notes); unadopted(out);
        assertEquals("BP1_NO_LOF_NATIVE_REVIEW_SCOPE_NOT_INTERPRETED", out.path("reasonCode").asText());
        assertEquals("SOFTWARE_UNSUPPORTED", qualification(out).at("/reviewCoverage/status").asText());
        assertEquals(0, run.effectCalls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"2020-01-01T00:00:00Z", "2026-11-04T12:00:00Z", "2030-01-01T00:00:00Z"})
    void knowledgeHasNoArtificialDailyExpiryOrQualificationWindow(String instant) throws Exception {
        var run = new Run(); run.clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        var out = run.evaluate("date-independent-" + instant.substring(0,10)); unadopted(out);
        assertEquals("BP1_NO_LOF_LEGACY_PROJECT_TEMPLATE_NOT_SCIENTIFIC_REVIEW", out.path("reasonCode").asText());
        assertFalse(out.path("policy").toString().contains("reviewValidityDate"));
    }

    @Test void selectedSourceConflictCannotBeRescuedByAnIndependentProjectTemplate() throws Exception {
        var run = new Run(); ((ObjectNode) run.source.doc.at("/records/0")).put("status", "SOURCE_CONFLICT")
                .put("reasonCode", "BP1_SOURCE_APPLICABILITY_CONFLICT");
        var out = run.evaluate("selected-source-conflict");
        assertEquals("NOT_EVALUATED", out.path("status").asText()); assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("SOURCE_CONFLICT", out.at("/publicSummary/sourceDisposition").asText()); assertEquals(0, run.effectCalls.get());
    }
    @Test void verifiedPureMissenseStillUsesTheTruncatingRuleNonApplicabilityGate() throws Exception {
        var run = new Run(); var sequence = run.source.base.sequence;
        sequence.setAllele(sequence.genomic(182), "G", "A", "SNV");
        sequence.annotation.put("hgvsCoding", "ENST000001.4:c.182G>A").put("hgvsProtein", "NP_000001.1:p.Arg61Gln")
                .put("consequence", "missense_variant").put("proteinPosition", "61").put("aminoAcids", "R/Q").put("codons", "CGA/CAA");
        sequence.applyAnnotation();
        var out = run.evaluate("verified-missense-not-truncating");
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("BP1_TRUNCATING_RULE_NOT_APPLICABLE_TO_PURE_MISSENSE", out.path("reasonCode").asText());
        assertEquals("NOT_APPLICABLE", out.at("/evaluation/criterionApplicability/status").asText()); assertEquals(0, run.effectCalls.get());
    }

    @Test void aQualifiedReviewCannotRescueConflictingCurrentCodingAnnotation() throws Exception {
        var run = new Run(); var sequence = run.source.base.sequence;
        sequence.annotation.put("hgvsCoding", "ENST000001.4:c.182G>T"); sequence.applyAnnotation();
        var out = run.evaluate("qualified-review-wrong-hgvs");
        assertEquals("NOT_EVALUATED", out.path("status").asText(), out::toPrettyString);
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText()); assertEquals(0, run.effectCalls.get());
        assertEquals("BP1_TRUNCATING_HGVSC_SEQUENCE_MISMATCH", out.path("reasonCode").asText());
        assertFalse(out.at("/truncatingConditionCheck/sequenceConsequence/sequenceTruncationCandidate").asBoolean(true));
    }
}
