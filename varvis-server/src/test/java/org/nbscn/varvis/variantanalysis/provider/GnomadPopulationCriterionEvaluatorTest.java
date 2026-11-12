package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GnomadPopulationCriterionEvaluatorTest {

    @ParameterizedTest
    @CsvSource({
        "0.0000999999999999999999,NOT_MET,MET", "0.0001,MET,MET", "1.000000000000000001e-4,MET,MET",
        "0.0000329999999999999999,NOT_MET,NOT_MET", "0.000033,NOT_MET,MET", "0.0000330000000000000001,NOT_MET,MET",
        "0.0000332,NOT_MET,MET", "0.000003,NOT_MET,NOT_MET"
    })
    void hnf1aUsesPublishedDecimalInclusiveBoundaries(String frequency, String ba1, String bs1) throws Exception {
        profile = GnomadPopulationInputProfile.loadHnf1aFrequency(); registry = profile.getSuccessorRegistryDocument();
        var context = hnf1aContext();
        var evidence = atomic(context, hitCarrier(context, profile.parse("PASS", jointInfo(frequency, "5000", "0.1"), 0, 1)));
        var results = evaluator.evaluate(context, evidence, profile, registry, SELECTION);
        assertEquals(ba1, status(results.get(0)), results.toString()); assertEquals(bs1, status(results.get(1)), results.toString());
        assertEquals("GREATER_THAN_OR_EQUAL", parameter(results.get(0), "comparison.operator"));
        assertEquals("0.000033", parameter(results.get(1), "threshold.raw"));
        assertEquals(frequency, parameter(results.get(1), "frequency.raw"));
        results.forEach(this::assertE1Hashes);
    }

    @ParameterizedTest
    @CsvSource({"0.0000029999999999999999,MET", "0.000003,MET", "0.0000030000000000000001,NOT_MET", "0.000003002,NOT_MET"})
    void hnf1aPm2LiteralAndReciprocalRoundingRemainUnchanged(String frequency, String expected) throws Exception {
        var context = hnf1aContext();
        var production = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var evidence = atomic(context, hitCarrier(context, production.parse("PASS", jointInfo(frequency, "5000", "0.1"), 0, 1)));
        assertEquals(expected, status(evaluator.evaluatePm2Supporting(context, evidence, null, null,
                production, production.getSuccessorRegistryDocument(), SELECTION)));
    }

    @Test void hnf1aBs1DoesNotBorrowBa1MinimumAnAndQualityUnknownNeverMeansNegative() throws Exception {
        profile = GnomadPopulationInputProfile.loadHnf1aFrequency(); registry = profile.getSuccessorRegistryDocument();
        var context = hnf1aContext();
        var evidence = atomic(context, hitCarrier(context, profile.parse("PASS", jointInfo("0.00004", "1999", "0.1"), 0, 1)));
        assertEquals("SELECTED_ANCESTRY_AN_BELOW_MINIMUM", reason(criterion(context, evidence, BA1)));
        assertEquals("MET", status(criterion(context, evidence, BS1)));
        for (String fault : List.of("NO_P", "DISCORDANT", "FILTER", "AN_ZERO", "AC_BAD", "FAF_ANCESTRY", "FOUNDER", "NO_FAF", "SUBTYPE")) {
            var info = jointInfo("0.00004", "5000", "0.1"); String filter = "PASS";
            switch (fault) {
                case "NO_P" -> info.remove("stat_union_p_value");
                case "DISCORDANT" -> info.put("stat_union_p_value", "0.0000999999");
                case "FILTER" -> filter = "EXOMES_FILTERED";
                case "AN_ZERO" -> info.put("AN_joint_nfe", "0");
                case "AC_BAD" -> info.put("AC_joint_nfe", "5001");
                case "FAF_ANCESTRY" -> info.put("faf95_joint_nfe", "0.00003");
                case "FOUNDER" -> info.put("fafmax_faf95_max_gen_anc_joint", "fin");
                case "NO_FAF" -> info.remove("fafmax_faf95_max_joint");
                case "SUBTYPE" -> info.put("exomes_filters", "AC0");
                default -> throw new AssertionError(fault);
            }
            var invalid = atomic(context, hitCarrier(context, profile.parse(filter, info, 0, 1)));
            for (var e : evaluator.evaluate(context, invalid, profile, registry, SELECTION)) assertEquals("NOT_EVALUATED", status(e), fault + e);
        }
        var boundary = atomic(context, hitCarrier(context, profile.parse("PASS", jointInfo("0.00004", "5000", "0.0001"), 0, 1)));
        assertEquals("MET", status(criterion(context, boundary, BS1)));
    }

    @Test void hnf1aDeclarationScopeAndNoRecordAreNotInferredFromPm2() throws Exception {
        profile = GnomadPopulationInputProfile.loadHnf1aFrequency(); registry = profile.getSuccessorRegistryDocument();
        for (String fault : List.of("HGNC", "MONDO", "MOI", "PM2_ONLY", "PROJECTED", "INDEL")) {
            var context = hnf1aContext();
            switch (fault) {
                case "HGNC" -> ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:1");
                case "MONDO" -> ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0000001");
                case "MOI" -> context.put("moi", "AUTOSOMAL_RECESSIVE");
                case "PM2_ONLY" -> ((ObjectNode) context.path("transcript")).put("selectionPolicy", org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.PM2_CONTEXT_SELECTION);
                case "PROJECTED" -> ((ObjectNode) context.at("/variant/assemblyLineage")).put("mappingStatus", "MAPPED");
                case "INDEL" -> ((ObjectNode) context.path("variant")).put("variantType", "INDEL");
                default -> throw new AssertionError(fault);
            }
            refreshContextId(context);
            // Scope failures are tested at the evaluator boundary; invalid lineage need not become a valid atomic carrier.
            var valid = hnf1aContext();
            var evidence = atomic(valid, hitCarrier(valid, profile.parse("PASS", jointInfo("0.0001", "5000", "0.1"), 0, 1)));
            assertEquals("NOT_EVALUATED", status(criterion(context, evidence, BS1)), fault);
        }
        var context = hnf1aContext();
        assertEquals("NO_RECORD_IS_NOT_FREQUENCY_ZERO", reason(criterion(context, atomic(context, failedCarrier(context, "NO_RECORD")), BS1)));
    }

    private ObjectNode hnf1aContext() throws IOException {
        var context = context("12", 120988847, "G", "A");
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0015967");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("selectionPolicy", org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.FREQUENCY_CONTEXT_SELECTION);
        refreshContextId(context); return context;
    }

    @Test void hnf1aUnresolvedOrUnknownExceptionsBlockBothFrequencyRules() throws Exception {
        profile = GnomadPopulationInputProfile.loadHnf1aFrequency(); registry = profile.getSuccessorRegistryDocument();
        var context = hnf1aContext();
        var parsed = profile.parse("PASS", jointInfo("0.0001", "5000", "0.1"), 0, 1);
        for (boolean unresolved : List.of(false, true)) {
            var document = profile.getDocument();
            var review = (ObjectNode) document.at("/diseaseFrequencyPolicy/exceptionReview");
            if (unresolved) review.withArray("unresolvedAlleles").add(context.at("/variant/evidenceQueryAllele").deepCopy());
            else review.put("status", "UNKNOWN");
            var changed = syntheticProfile(document);
            var evidence = atomic(context, hitCarrier(context, parsed), changed);
            for (var evaluation : evaluator.evaluate(context, evidence, changed, registry, SELECTION)) {
                assertEquals("NOT_EVALUATED", status(evaluation));
                assertEquals("HNF1A_EXCEPTION_REVIEW_REQUIRED", reason(evaluation));
            }
        }
    }

    @Test void rawObservationIsReparsedForIndependentProfilesAndTamperingIsRejected() throws Exception {
        var context = hnf1aContext();
        var source = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var target = GnomadPopulationInputProfile.loadHnf1aFrequency();
        var info = jointInfo("4.42800e-05", "1180056", "0.1");
        var raw = "chr12\t120988847\t.\tG\tA\t.\tPASS\t" + info.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(";"));
        var evidence = atomic(context, hitCarrier(context, source.parse("PASS", info, 0, 1)));
        var observation = mapper.createObjectNode().put("executionSourceSha256", "a".repeat(64))
                .put("profileSha256", source.getProfileSha256()).put("rawPopulationRecord", raw);
        observation.set("context", context); observation.set("evidence", evidence);
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        var parsed = wiring.reparsePopulationObservation(context, observation, "a".repeat(64), source, target);
        var reparsed = adapter.adapt(context, parsed.carrier());
        assertNotEquals(evidence.path("evidenceId"), reparsed.path("evidenceId"));
        assertEquals(target.getProfileSha256(), measurement(reparsed, "gnomad.inputProfileSha256").path("rawValue").asText());
        var runtime = new Hnf1aPopulationRuntime(mapper);
        var result = runtime.evaluate(context, observation, "a".repeat(64));
        assertEquals("MET", result.at("/criterionEvaluations/1/evaluationResult/status").asText(), result.toString());
        assertEquals(1, result.path("populationStrengthCount").asInt());
        assertEquals(0, result.path("providerCalls").asInt());
        assertEquals(2, result.path("evaluatorCalls").asInt());
        assertEquals(evidence, observation.path("evidence"));
        var highInfo = jointInfo("0.0001", "1180056", "0.1");
        var high = observation.deepCopy();
        high.put("rawPopulationRecord", raw.replace("4.42800e-05", "0.0001"));
        high.set("evidence", atomic(context, hitCarrier(context, source.parse("PASS", highInfo, 0, 1))));
        var overlapping = runtime.evaluate(context, high, "a".repeat(64));
        assertEquals("MET", overlapping.at("/criterionEvaluations/0/evaluationResult/status").asText());
        assertEquals("MET", overlapping.at("/criterionEvaluations/1/evaluationResult/status").asText());
        assertEquals(BA1, overlapping.path("preferredPopulationCriterion").asText());
        assertEquals(1, overlapping.path("populationStrengthCount").asInt());
        for (String fault : List.of("ALT", "DUPLICATE_ALT", "RAW_VALUE", "SOURCE", "EXECUTION", "PROFILE", "CONTEXT", "DATA_CLASS")) {
            var changed = observation.deepCopy();
            switch (fault) {
                case "ALT" -> changed.put("rawPopulationRecord", raw.replace("\tG\tA\t", "\tG\tC\t"));
                case "DUPLICATE_ALT" -> changed.put("rawPopulationRecord", raw.replace("\tG\tA\t", "\tG\tA,A\t"));
                case "RAW_VALUE" -> changed.put("rawPopulationRecord", raw.replace("4.42800e-05", "0.00001"));
                case "SOURCE" -> ((ObjectNode) changed.at("/evidence/source")).put("resourceSha256", "f".repeat(64));
                case "EXECUTION" -> changed.put("executionSourceSha256", "b".repeat(64));
                case "PROFILE" -> changed.put("profileSha256", target.getProfileSha256());
                case "CONTEXT" -> ((ObjectNode) changed.path("context")).put("contextId", "context-sha256-" + "f".repeat(64));
                case "DATA_CLASS" -> ((ObjectNode) changed.path("context")).put("dataClass", "PATIENT");
                default -> throw new AssertionError(fault);
            }
            assertEquals("NOT_EVALUATED", runtime.evaluate(context, changed, "a".repeat(64)).at("/criterionEvaluations/1/evaluationResult/status").asText(), fault);
        }
        var changedContext = context.deepCopy(); ((ObjectNode) changedContext.path("disease")).put("mondoId", "MONDO:0000001");
        refreshContextId(changedContext);
        assertEquals("HNF1A_FREQUENCY_POLICY_NOT_APPLICABLE", runtime.evaluate(changedContext, observation, "a".repeat(64))
                .at("/criterionEvaluations/1/evaluationResult/reasonCode").asText());
        var key = RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "12", 120988847, "G", "A");
        var provider = mock(VariantEvidenceProvider.class);
        var ba1 = new Ba1AlleleRuntime(mapper, profile, provider).evaluate(context.at("/variant/alleleId").asText(),
                RuntimeEvidenceV2ProviderWiringTest.variant(key), RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), observation, "a".repeat(64));
        assertEquals(0, ba1.path("providerCalls").asInt(), ba1.toString());
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    private static final String BA1 = "ACMG_AMP_2015:BA1";
    private static final String BS1 = "ACMG_AMP_2015:BS1";
    private static final String SELECTION = "cspec-selection-sha256-"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private ObjectMapper mapper;
    private RuntimeEvidenceV2Adapter adapter;
    private GnomadPopulationCriterionEvaluator evaluator;
    private GnomadPopulationInputProfile profile;
    private ObjectNode registry;
    private GnomadPopulationInputProfile pm2Profile;
    private ObjectNode pm2Registry;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        evaluator = new GnomadPopulationCriterionEvaluator(mapper);
        profile = GnomadPopulationInputProfile.loadExecutableV2();
        try (InputStream input = getClass().getResourceAsStream(
                "/acmg/rule-registry-v2/acmg-rule-registry-e3-p1a-2026-09-02.json")) {
            registry = (ObjectNode) mapper.readTree(input);
        }
        pm2Profile = GnomadPopulationInputProfile.loadExecutablePm2Supporting();
        pm2Registry = pm2Profile.getSuccessorRegistryDocument();
    }

    @ParameterizedTest
    @CsvSource({
            "0.0499999999999999999999, NOT_MET",
            "0.05, NOT_MET",
            "0.0500000000000000000001, MET"
    })
    void ba1UsesStrictProfileBoundary(String frequency, String expected) throws IOException {
        ObjectNode context = context("1", 100000, "A", "G");
        ObjectNode result = criterion(context, atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo(frequency, "2000", "0.1"), 0, 1))), BA1);

        assertEquals(expected, status(result));
        assertEquals("MET".equals(expected) ? "STANDALONE" : "NONE",
                result.path("appliedStrength").asText());
    }

    @Test
    void ba1SelectedAncestryGateAndE1IdentityProjectionsAreStable() throws IOException {
        ObjectNode context = context("1", 100000, "A", "G");

        ObjectNode equal = criterion(context, atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo("0.05", "2000", "0.1"), 0, 1))), BA1);
        assertEquals("NOT_MET", status(equal));

        ObjectNode belowAn = criterion(context, atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo("0.051", "1999", "0.1"), 0, 1))), BA1);
        assertEquals("NOT_EVALUATED", status(belowAn));
        assertEquals("SELECTED_ANCESTRY_AN_BELOW_MINIMUM", reason(belowAn));

        ObjectNode repeated = criterion(context, atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo("0.05", "2000", "0.1"), 0, 1))), BA1);
        assertEquals(equal.path("evaluationId"), repeated.path("evaluationId"));
        assertE1Hashes(equal);
        assertE1Hashes(belowAn);
        List<String> parameterNames = parameterNames(equal);
        List<String> sortedNames = new java.util.ArrayList<>(parameterNames);
        sortedNames.sort((left, right) -> left.compareTo(right));
        assertEquals(sortedNames, parameterNames);
    }

    @Test
    void ba1ExceptionHitUnknownAndAmbiguousAreNeverMet() throws IOException {
        ObjectNode baseDocument = profile.getDocument();
        JsonNode first = ba1Rule(baseDocument).path("exceptionPolicy").path("entries").get(0);
        ObjectNode context = context(first.path("contig").asText(), first.path("position").asInt(),
                first.path("reference").asText(), first.path("alternate").asText());
        GnomadPopulationInputProfile.Result parsed =
                profile.parse("PASS", jointInfo("0.2", "5000", "0.1"), 0, 1);
        ObjectNode evidence = atomic(context, hitCarrier(context, parsed));

        ObjectNode hit = criterion(context, evidence, BA1);
        assertEquals("NOT_EVALUATED", status(hit));
        assertEquals("BA1_EXCEPTION_HIT", reason(hit));
        assertEquals("NOT_APPLICABLE", hit.path("criterionApplicability").path("status").asText());

        ObjectNode unknownDocument = baseDocument.deepCopy();
        ((ObjectNode) ba1Rule(unknownDocument).path("exceptionPolicy")).put("snapshotStatus", "UNKNOWN");
        GnomadPopulationInputProfile unknownProfile = syntheticProfile(unknownDocument);
        ObjectNode unknown = criterion(context, atomic(context, hitCarrier(context, parsed), unknownProfile),
                unknownProfile, BA1);
        assertEquals("NOT_EVALUATED", status(unknown));
        assertEquals("BA1_EXCEPTION_SNAPSHOT_UNKNOWN", reason(unknown));

        ObjectNode ambiguousDocument = baseDocument.deepCopy();
        ObjectNode policy = (ObjectNode) ba1Rule(ambiguousDocument).path("exceptionPolicy");
        ArrayNode entries = (ArrayNode) policy.path("entries");
        entries.set(1, entries.get(0).deepCopy());
        policy.put("entrySetSha256", adapter.canonicalSha256(entries));
        GnomadPopulationInputProfile ambiguousProfile = syntheticProfile(ambiguousDocument);
        ObjectNode ambiguous = criterion(context, atomic(context, hitCarrier(context, parsed), ambiguousProfile),
                ambiguousProfile, BA1);
        assertEquals("NOT_EVALUATED", status(ambiguous));
        assertEquals("BA1_EXCEPTION_MATCH_AMBIGUOUS", reason(ambiguous));
    }

    @ParameterizedTest
    @CsvSource({
            "0.0099999999999999999, GREATER_THAN, NOT_MET",
            "0.01, GREATER_THAN, NOT_MET",
            "0.0100000000000000001, GREATER_THAN, MET",
            "0.0099999999999999999, GREATER_THAN_OR_EQUAL, NOT_MET",
            "0.01, GREATER_THAN_OR_EQUAL, MET",
            "0.0100000000000000001, GREATER_THAN_OR_EQUAL, MET"
    })
    void bs1UsesTheVersionedRecordComparatorAtBelowEqualAndAboveBoundary(
            String frequency, String comparator, String expected) throws IOException {
        ObjectNode context = context("1", 100000, "A", "G");
        GnomadPopulationInputProfile exactProfile = syntheticProfile(withThresholds(
                threshold("bs1-synthetic-ad", "1.0.0", "HGNC:1", "MONDO:0000001",
                        "AUTOSOMAL_DOMINANT", "0.01", comparator)));
        ObjectNode evidence = atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo(frequency, "5000", "0.1"), 0, 1)), exactProfile);
        ObjectNode result = criterion(context, evidence, exactProfile, BS1);

        assertEquals(expected, status(result));
        assertEquals("MET".equals(expected) ? "STRONG" : "NONE",
                result.path("appliedStrength").asText());
        assertEquals(registryAuthorities(BS1),
                new java.util.TreeSet<>(textValues(result.path("ruleBinding").path("authoritySourceIds"))));
        assertFalse(textValues(result.path("ruleBinding").path("authoritySourceIds"))
                .contains("authority-synthetic-bs1-threshold"));
        assertEquals("authority-synthetic-bs1-threshold",
                parameter(result, "bs1.thresholdAuthoritySourceIds"));
        assertE1Hashes(result);
    }

    @Test
    void bs1RequiresOneExactGeneMondoMoiThresholdAndProductionHasNoDefault() throws IOException {
        ObjectNode context = context("1", 100000, "A", "G");
        ObjectNode productionEvidence = atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo("0.02", "5000", "0.1"), 0, 1)));
        ObjectNode noThreshold = criterion(context, productionEvidence, BS1);
        assertEquals("NOT_EVALUATED", status(noThreshold));
        assertEquals("BS1_THRESHOLD_NOT_FOUND", reason(noThreshold));

        GnomadPopulationInputProfile exactProfile = syntheticProfile(withThresholds(
                threshold("bs1-synthetic-ad", "1.0.0", "HGNC:1", "MONDO:0000001",
                        "AUTOSOMAL_DOMINANT", "0.01")));
        ObjectNode equalEvidence = atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo("0.01", "5000", "0.1"), 0, 1)), exactProfile);

        ObjectNode otherDisease = context.deepCopy();
        ((ObjectNode) otherDisease.path("disease")).put("mondoId", "MONDO:0000002");
        refreshContextId(otherDisease);
        ObjectNode otherEvidence = atomic(otherDisease, hitCarrier(otherDisease,
                profile.parse("PASS", jointInfo("0.01", "5000", "0.1"), 0, 1)), exactProfile);
        ObjectNode missing = criterion(otherDisease, otherEvidence, exactProfile, BS1);
        assertEquals("NOT_EVALUATED", status(missing));
        assertEquals("BS1_THRESHOLD_NOT_FOUND", reason(missing));

        GnomadPopulationInputProfile ambiguousProfile = syntheticProfile(withThresholds(
                threshold("bs1-synthetic-a", "1.0.0", "HGNC:1", "MONDO:0000001",
                        "AUTOSOMAL_DOMINANT", "0.01"),
                threshold("bs1-synthetic-b", "1.0.0", "HGNC:1", "MONDO:0000001",
                        "AUTOSOMAL_DOMINANT", "0.02")));
        ObjectNode ambiguousEvidence = atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo("0.01", "5000", "0.1"), 0, 1)), ambiguousProfile);
        ObjectNode ambiguous = criterion(context, ambiguousEvidence, ambiguousProfile, BS1);
        assertEquals("NOT_EVALUATED", status(ambiguous));
        assertEquals("BS1_THRESHOLD_MATCH_AMBIGUOUS", reason(ambiguous));

        ObjectNode incomplete = context.deepCopy();
        incomplete.remove("moi");
        refreshContextId(incomplete);
        assertEquals("ATOMIC_EVIDENCE_CONTEXT_MISMATCH",
                reason(criterion(incomplete, equalEvidence, exactProfile, BS1)));
    }

    @Test
    void noRecordFailuresTimeoutNonPassMissingAndIdentityMismatchNeverProduceMet() throws IOException {
        ObjectNode context = context("1", 100000, "A", "G");
        GnomadPopulationInputProfile bs1Profile = syntheticProfile(withThresholds(
                threshold("bs1-synthetic", "1.0.0", "HGNC:1", "MONDO:0000001",
                        "AUTOSOMAL_DOMINANT", "0.001")));

        for (String failure : List.of("NO_RECORD", "FAILED", "TIMEOUT", "UNAVAILABLE")) {
            assertNoMet(context, atomic(context, failedCarrier(context, failure), bs1Profile), bs1Profile);
        }

        ObjectNode nonPass = hitCarrier(context,
                profile.parse("AS_VQSR", jointInfo("0.2", "5000", "0.1"), 0, 1));
        assertNoMet(context, atomic(context, nonPass, bs1Profile), bs1Profile);

        ObjectNode exactFalse = hitCarrier(context,
                profile.parse("PASS", jointInfo("0.2", "5000", "0.1"), 0, 1));
        ObjectNode exact = measurement(exactFalse, "gnomad.exactAlleleMatch");
        exact.put("rawValue", "false").put("normalizedValue", "false");
        assertNoMet(context, atomic(context, exactFalse, bs1Profile), bs1Profile);

        ObjectNode missingFrequency = hitCarrier(context,
                profile.parse("PASS", jointInfo("0.2", "5000", "0.1"), 0, 1));
        removeMeasurement(missingFrequency, "gnomad.joint.faf95max");
        assertNoMet(context, atomic(context, missingFrequency, bs1Profile), bs1Profile);

        ObjectNode missingSelectedAc = hitCarrier(context,
                profile.parse("PASS", jointInfo("0.2", "5000", "0.1"), 0, 1));
        removeMeasurement(missingSelectedAc, "gnomad.joint.ancestry.nfe.ac");
        assertNoMet(context, atomic(context, missingSelectedAc, bs1Profile), bs1Profile);

        ObjectNode wrongCalibration = hitCarrier(context,
                profile.parse("PASS", jointInfo("0.2", "5000", "0.1"), 0, 1));
        measurement(wrongCalibration, "gnomad.exactAlleleMatch")
                .put("calibrationProfileId", "GNOMAD_POPULATION_INPUT_V2@wrong");
        List<ObjectNode> profileMismatch = evaluations(context, atomic(context, wrongCalibration), profile);
        assertAllNotEvaluated(profileMismatch);
        assertEquals("PROFILE_CALIBRATION_MISMATCH", reason(profileMismatch.get(0)));

        ObjectNode wrongProfileHash = hitCarrier(context,
                profile.parse("PASS", jointInfo("0.2", "5000", "0.1"), 0, 1));
        ObjectNode profileHash = measurement(wrongProfileHash, "gnomad.inputProfileSha256");
        profileHash.put("rawValue", "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
                .put("normalizedValue", "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        List<ObjectNode> hashMismatch = evaluations(context, atomic(context, wrongProfileHash), profile);
        assertAllNotEvaluated(hashMismatch);
        assertEquals("PROFILE_EVIDENCE_BINDING_MISMATCH", reason(hashMismatch.get(0)));

        ObjectNode wrongIdentity = hitCarrier(context,
                profile.parse("PASS", jointInfo("0.2", "5000", "0.1"), 0, 1));
        ((ObjectNode) wrongIdentity.path("source")).put("resourceSha256",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        List<ObjectNode> identityMismatch = evaluations(context, atomic(context, wrongIdentity), profile);
        assertAllNotEvaluated(identityMismatch);
        assertEquals("RESOURCE_IDENTITY_MISMATCH", reason(identityMismatch.get(0)));

        ObjectNode tamperedRegistry = registry.deepCopy();
        tamperedRegistry.put("authorityCatalogSha256",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        List<ObjectNode> registryMismatch = evaluator.evaluate(context,
                atomic(context, hitCarrier(context,
                        profile.parse("PASS", jointInfo("0.2", "5000", "0.1"), 0, 1))),
                profile, tamperedRegistry, SELECTION);
        assertAllNotEvaluated(registryMismatch);
        assertEquals("RULE_REGISTRY_BINDING_MISMATCH", reason(registryMismatch.get(0)));
    }

    @Test
    void frequencyDiscordanceRemainsQcOnlyAndDoesNotChangeBa1Conclusion() throws IOException {
        ObjectNode context = context("1", 100000, "A", "G");
        ObjectNode discordant = criterion(context, atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo("0.051", "5000", "9e-5"), 0, 1))), BA1);
        ObjectNode concordant = criterion(context, atomic(context, hitCarrier(context,
                profile.parse("PASS", jointInfo("0.051", "5000", "0.1"), 0, 1))), BA1);

        assertEquals("MET", status(discordant));
        assertEquals("MET", status(concordant));
        assertEquals("DISCORDANT", parameter(discordant, "frequency.discordanceQc"));
        assertEquals("NOT_DISCORDANT", parameter(concordant, "frequency.discordanceQc"));
        assertEquals("NONE", discordant.path("conflict").path("status").asText());
        assertNotEquals(discordant.path("evaluationId"), concordant.path("evaluationId"));
    }

    @Test
    void pm2ProductionSnapshotIsEmptyAndDoesNotEvaluateARealContext() throws IOException {
        ObjectNode context = context("1", 12591, "C", "A");
        ObjectNode joint = atomic(context, pm2JointNoRecordCarrier(context));

        ObjectNode result = evaluator.evaluatePm2Supporting(context, joint, null, null,
                pm2Profile, pm2Registry, SELECTION);

        assertEquals("NOT_EVALUATED", status(result));
        assertEquals("PM2_POLICY_NOT_FOUND_OR_AMBIGUOUS", reason(result));
        assertEquals("NONE", result.path("appliedStrength").asText());
    }

    @Test
    void pm2RejectsAtomicEvidenceForAnotherExactAllele() throws IOException {
        ObjectNode context = context("1", 12591, "C", "A");
        GnomadPopulationInputProfile synthetic = pm2SyntheticProfile(
                pm2Policy("0.001", "LESS_THAN", "2000", List.of()));
        ObjectNode joint = atomic(context, pm2JointNoRecordCarrier(context));
        ObjectNode otherAllele = context.path("variant").path("evidenceQueryAllele").deepCopy();
        otherAllele.put("alternate", "G");
        ObjectNode otherQuery = mapper.createObjectNode();
        otherQuery.put("resourceId", GnomadPopulationInputProfile.JOINT_RESOURCE_ID);
        otherQuery.set("queryAllele", otherAllele);
        ((ObjectNode) joint.path("queryOutcome")).put("queryFingerprint",
                adapter.canonicalSha256(otherQuery));

        ObjectNode result = evaluator.evaluatePm2Supporting(context, joint, null, null,
                synthetic, pm2Registry, SELECTION);

        assertEquals("NOT_EVALUATED", status(result));
        assertEquals("EVIDENCE_QUERY_ALLELE_MISMATCH", reason(result));
    }

    @Test
    void pm2WellCoveredAbsenceUsesEitherIndependentDatasetWithoutCreatingAfZero()
            throws IOException {
        ObjectNode context = context("1", 12591, "C", "A");
        GnomadPopulationInputProfile synthetic = pm2SyntheticProfile(
                pm2Policy("0.001", "LESS_THAN", "2000", List.of(
                        "outside_broad_capture_region")));
        ObjectNode joint = atomic(context, pm2JointNoRecordCarrier(context));
        ObjectNode exome = atomic(context, allSitesCarrier(context, true, "HIT", "2000",
                Map.of("outside_broad_capture_region", false)));
        ObjectNode genomeNoRecord = atomic(context,
                allSitesCarrier(context, false, "NO_RECORD", null, Map.of()));

        ObjectNode met = evaluator.evaluatePm2Supporting(context, joint, exome, genomeNoRecord,
                synthetic, pm2Registry, SELECTION);
        assertEquals("MET", status(met));
        assertEquals("SUPPORTING", met.path("appliedStrength").asText());
        assertE1Hashes(met);
        var expectedIds = List.of(joint.path("evidenceId").asText(), exome.path("evidenceId").asText(),
                genomeNoRecord.path("evidenceId").asText()).stream().sorted().toList();
        assertEquals(mapper.valueToTree(expectedIds), met.path("evidenceIds"));
        assertEquals(mapper.valueToTree(expectedIds), met.at("/deterministicTrace/inputEvidenceIds"));
        assertEquals(mapper.valueToTree(expectedIds), met.at("/deterministicTrace/steps/4/inputEvidenceIds"));
        assertTrue(joint.path("measurements").isEmpty());
        assertTrue(genomeNoRecord.path("measurements").isEmpty());
        assertFalse(joint.toString().contains("AF=0"));
        ObjectNode failedGenome = atomic(context, allSitesCarrier(context, false, "FAILED", null, Map.of()));
        assertEquals("MET", status(evaluator.evaluatePm2Supporting(context, joint, exome, failedGenome,
                synthetic, pm2Registry, SELECTION)), "A separately sufficient exome observation survives another dataset failure");

        ObjectNode lowExome = atomic(context, allSitesCarrier(context, true, "HIT", "1999",
                Map.of("outside_broad_capture_region", false)));
        ObjectNode lowGenome = atomic(context, allSitesCarrier(context, false, "HIT", "1999",
                Map.of()));
        ObjectNode low = evaluator.evaluatePm2Supporting(context, joint, lowExome, lowGenome,
                synthetic, pm2Registry, SELECTION);
        assertEquals("NOT_EVALUATED", status(low));
        assertEquals("PM2_CALLABILITY_NOT_SUFFICIENT", reason(low));

        ObjectNode bothMissing = evaluator.evaluatePm2Supporting(context, joint,
                atomic(context, allSitesCarrier(context, true, "NO_RECORD", null, Map.of())),
                genomeNoRecord, synthetic, pm2Registry, SELECTION);
        assertEquals("NOT_EVALUATED", status(bothMissing));
        assertEquals("PM2_CALLABILITY_NOT_SUFFICIENT", reason(bothMissing));
    }

    @Test
    void pm2ExomeFlagsAreAppliedOnlyWhenTheSelectedPolicyNamesThem() throws IOException {
        ObjectNode context = context("1", 12591, "C", "A");
        GnomadPopulationInputProfile synthetic = pm2SyntheticProfile(
                pm2Policy("0.001", "LESS_THAN", "2000",
                        List.of("outside_broad_capture_region")));
        ObjectNode joint = atomic(context, pm2JointNoRecordCarrier(context));
        ObjectNode excludedExome = atomic(context, allSitesCarrier(context, true, "HIT", "5000",
                Map.of("outside_broad_capture_region", true)));
        ObjectNode sufficientGenome = atomic(context, allSitesCarrier(context, false, "HIT", "2000",
                Map.of()));

        ObjectNode result = evaluator.evaluatePm2Supporting(context, joint, excludedExome,
                sufficientGenome, synthetic, pm2Registry, SELECTION);

        assertEquals("MET", status(result));
        assertEquals("EXCLUDED_BY_POLICY", parameter(result, "callability.exomes.status"));
        assertEquals("SUFFICIENT", parameter(result, "callability.genomes.status"));
    }

    @ParameterizedTest
    @CsvSource({
            "0.0009999999999999999, MET",
            "0.001, NOT_MET",
            "0.0010000000000000001, NOT_MET"
    })
    void pm2HitUsesTheExactContextPolicyComparator(String frequency, String expected)
            throws IOException {
        ObjectNode context = context("1", 12591, "C", "A");
        GnomadPopulationInputProfile synthetic = pm2SyntheticProfile(
                pm2Policy("0.001", "LESS_THAN", "2000", List.of()));
        ObjectNode joint = atomic(context, hitCarrier(context,
                pm2Profile.parse("PASS", jointInfo(frequency, "5000", "0.1"), 0, 1)));

        ObjectNode result = evaluator.evaluatePm2Supporting(context, joint, null, null,
                synthetic, pm2Registry, SELECTION);

        assertEquals(expected, status(result));
    }

    @Test
    void pm2HitWithoutPolicyAndAllSitesFailuresOrTimeoutsNeverMeet() throws IOException {
        ObjectNode context = context("1", 12591, "C", "A");
        ObjectNode hit = atomic(context, hitCarrier(context,
                pm2Profile.parse("PASS", jointInfo("0.0001", "5000", "0.1"), 0, 1)));
        ObjectNode noPolicy = evaluator.evaluatePm2Supporting(context, hit, null, null,
                pm2Profile, pm2Registry, SELECTION);
        assertEquals("NOT_EVALUATED", status(noPolicy));
        assertEquals("PM2_POLICY_NOT_FOUND_OR_AMBIGUOUS", reason(noPolicy));

        GnomadPopulationInputProfile synthetic = pm2SyntheticProfile(
                pm2Policy("0.001", "LESS_THAN", "2000", List.of()));
        ObjectNode joint = atomic(context, pm2JointNoRecordCarrier(context));
        for (String failure : List.of("FAILED", "TIMEOUT")) {
            ObjectNode exome = atomic(context,
                    allSitesCarrier(context, true, failure, null, Map.of()));
            ObjectNode genome = atomic(context,
                    allSitesCarrier(context, false, failure, null, Map.of()));
            ObjectNode result = evaluator.evaluatePm2Supporting(context, joint, exome, genome,
                    synthetic, pm2Registry, SELECTION);
            assertEquals("NOT_EVALUATED", status(result));
            assertFalse("MET".equals(status(result)));
        }
    }

    @ParameterizedTest
    @CsvSource({"0.000001559999999999, MET", "0.00000156, MET", "0.000001560000000001, NOT_MET"})
    void vhlProductionPolicyUsesExplicitFafInclusiveBoundary(String frequency, String expected) throws IOException {
        var production = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var context = vhlContext();
        var evidence = atomic(context, hitCarrier(context, production.parse("PASS", jointInfo(frequency, "5000", "0.1"), 0, 1)));
        var result = evaluator.evaluatePm2Supporting(context, evidence, null, null, production, pm2Registry, SELECTION);
        assertEquals(expected, status(result));
        assertEquals("MET".equals(expected) ? "SUPPORTING" : "NONE", result.path("appliedStrength").asText());
        assertEquals("gnomad.joint.faf95max", parameter(result, "frequency.metricId"));
        assertEquals("0.00000156", parameter(result, "threshold.raw"));
        assertEquals("nfe", parameter(result, "frequency.selectedAncestry"));
        assertE1Hashes(result);
    }
    @Test void vhlObservedInputsMustHavePassAndConsistentAncestryCounts() throws IOException {
        var production = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var context = vhlContext();
        for (String fault : List.of("FILTER", "AN", "AC", "ANCESTRY", "FAF", "UNIT", "FAILED")) {
            var evidence = atomic(context, hitCarrier(context, production.parse("PASS", jointInfo("0.000001", "5000", "0.1"), 0, 1)));
            switch (fault) {
                case "FILTER" -> measurement(evidence, "gnomad.joint.filter").put("rawValue", "FAIL").put("normalizedValue", "FAIL");
                case "AN" -> measurement(evidence, "gnomad.joint.ancestry.nfe.an").put("rawValue", "0").put("normalizedValue", "0");
                case "AC" -> measurement(evidence, "gnomad.joint.ancestry.nfe.ac").put("rawValue", "0").put("normalizedValue", "0");
                case "ANCESTRY" -> measurement(evidence, "gnomad.joint.faf95max.ancestry").put("rawValue", "fin").put("normalizedValue", "fin");
                case "FAF" -> removeMeasurement(evidence, "gnomad.joint.faf95max");
                case "UNIT" -> measurement(evidence, "gnomad.joint.faf95max").put("unit", "percent");
                case "FAILED" -> ((ObjectNode) evidence.path("resourceExecution")).put("status", "FAILED");
                default -> throw new AssertionError(fault);
            }
            var result = evaluator.evaluatePm2Supporting(context, evidence, null, null, production, pm2Registry, SELECTION);
            assertEquals("NOT_EVALUATED", status(result), fault);
        }
    }
    @Test void vhlPolicyDoesNotTreatAbsenceAsZeroAndHistoricalProfileRemainsEmpty() throws IOException {
        var production = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var context = vhlContext();
        var result = evaluator.evaluatePm2Supporting(context, atomic(context, pm2JointNoRecordCarrier(context)), null, null,
                production, pm2Registry, SELECTION);
        assertEquals("NOT_EVALUATED", status(result));
        assertEquals("PM2_ABSENCE_DETECTION_INSUFFICIENT", reason(result));
        assertTrue(pm2Rule(pm2Profile.getDocument()).at("/policySnapshot/records").isEmpty());
        assertNotEquals(pm2Profile.getProfileSha256(), production.getProfileSha256());
    }
    @Test void vhlPolicyRequiresUniqueExactGeneDiseaseMoiAndVersionedAuthority() throws IOException {
        var production = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var context = vhlContext();
        var evidence = atomic(context, hitCarrier(context, production.parse("PASS", jointInfo("0.000001", "5000", "0.1"), 0, 1)));
        for (String fault : List.of("GENE", "DISEASE", "MOI", "AMBIGUOUS", "AUTHORITY", "UNIT")) {
            var document = production.getDocument();
            var snapshot = (ObjectNode) document.at("/evaluation/criteria/2/populationRule/policySnapshot");
            var policy = (ObjectNode) snapshot.path("records").get(0);
            switch (fault) {
                case "GENE" -> ((ObjectNode) policy.path("gene")).put("hgncId", "HGNC:1");
                case "DISEASE" -> ((ObjectNode) policy.path("disease")).put("mondoId", "MONDO:0000001");
                case "MOI" -> policy.put("moi", "AUTOSOMAL_RECESSIVE");
                case "AMBIGUOUS" -> ((ArrayNode) snapshot.path("records")).add(policy.deepCopy());
                case "AUTHORITY" -> ((ObjectNode) policy.path("authority")).put("snapshotSha256", "f".repeat(64));
                case "UNIT" -> ((ObjectNode) policy.path("maximumAlleleFrequency")).put("unit", "percent");
                default -> throw new AssertionError(fault);
            }
            var projection = snapshot.deepCopy(); projection.remove("sha256"); snapshot.put("sha256", adapter.canonicalSha256(projection));
            var changed = mock(GnomadPopulationInputProfile.class);
            when(changed.getDocument()).thenReturn(document);
            when(changed.getProfileSha256()).thenReturn(adapter.canonicalSha256(document));
            when(changed.getProfileId()).thenReturn(production.getProfileId()); when(changed.getProfileVersion()).thenReturn(production.getProfileVersion());
            when(changed.getSuccessorRegistryDocument()).thenReturn(pm2Registry);
            var result = evaluator.evaluatePm2Supporting(context, evidence, null, null, changed, pm2Registry, SELECTION);
            assertEquals("NOT_EVALUATED", status(result), fault);
        }
    }
    @Test void reviewedCrossGenePoliciesUseTheirOwnMetricAndComparator() throws IOException {
        var production = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        for (var policy : production.getDocument().at("/evaluation/criteria/2/populationRule/policySnapshot/records")) {
            var context = context("1", 100000, "A", "G");
            context.set("gene", mapper.createObjectNode().put("symbol", "SYNTHETIC").set("hgncId", policy.at("/gene/hgncId")));
            ((ObjectNode) context.path("disease")).set("mondoId", policy.at("/disease/mondoId"));
            context.set("moi", policy.path("moi")); refreshContextId(context);
            var rule = policy.path("maximumAlleleFrequency");
            String frequency = rule.path("rawValue").asText("0.000001");
            var evidence = atomic(context, hitCarrier(context, production.parse("PASS", jointInfo(frequency, "5000", "0.1"), 0, 1)));
            for (String metric : List.of("gnomad.joint.an", "gnomad.joint.nhomalt")) {
                String value = metric.endsWith(".an") ? "5000" : "0";
                measurement(evidence, metric).put("rawValue", value).put("normalizedValue", value);
            }
            var result = evaluator.evaluatePm2Supporting(context, evidence, null, null, production, pm2Registry, SELECTION);
            String expected = "LESS_THAN".equals(rule.path("comparisonOperator").asText()) ? "NOT_MET" : "MET";
            assertEquals(expected, status(result), policy.path("policyId").asText() + ": " + result);
            assertE1Hashes(result);
            if (policy.has("maximumHomozygoteCount")) {
                measurement(evidence, "gnomad.joint.nhomalt").put("rawValue", "1").put("normalizedValue", "1");
                assertEquals("NOT_EVALUATED", status(evaluator.evaluatePm2Supporting(context, evidence, null, null, production, pm2Registry, SELECTION)));
                measurement(evidence, "gnomad.joint.ac").put("rawValue", "2").put("normalizedValue", "2");
                assertEquals("NOT_MET", status(evaluator.evaluatePm2Supporting(context, evidence, null, null, production, pm2Registry, SELECTION)));
            }
        }
    }

    @Test void singletonWithoutAnyFafRequiresConsistentCounts() throws IOException {
        var production = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var context = vhlContext();
        var evidence = atomic(context, hitCarrier(context, production.parse("PASS", jointInfo("0.000001", "5000", "0.1"), 0, 1)));
        var measurements = (ArrayNode) evidence.path("measurements");
        for (int i = measurements.size() - 1; i >= 0; i--)
            if (measurements.get(i).path("metricId").asText().contains("faf95")) measurements.remove(i);
        assertEquals("MET", status(evaluator.evaluatePm2Supporting(context, evidence, null, null, production, pm2Registry, SELECTION)));
        measurement(evidence, "gnomad.joint.nhomalt").put("rawValue", "1").put("normalizedValue", "1");
        assertEquals("NOT_EVALUATED", status(evaluator.evaluatePm2Supporting(context, evidence, null, null, production, pm2Registry, SELECTION)));
    }

    @Test void countPolicyUsesObservedCountsWithoutRequiringFafAndHonoursSampleBoundary() throws IOException {
        var production = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        JsonNode policy = null;
        for (var row : production.getDocument().at("/evaluation/criteria/2/populationRule/policySnapshot/records"))
            if (row.has("maximumAlleleCount")) { policy = row; break; }
        if (policy == null) throw new IllegalStateException("Reviewed count policy missing");
        var context = context("1", 100000, "A", "G");
        context.set("gene", mapper.createObjectNode().put("symbol", "SYNTHETIC").set("hgncId", policy.at("/gene/hgncId")));
        ((ObjectNode) context.path("disease")).set("mondoId", policy.at("/disease/mondoId"));
        context.set("moi", policy.path("moi")); refreshContextId(context);
        for (String[] values : new String[][]{{"1", "1999", "NOT_EVALUATED"}, {"1", "2000", "MET"}, {"2", "2000", "NOT_MET"}}) {
            var evidence = atomic(context, hitCarrier(context, production.parse("PASS", jointInfo("0.000001", "5000", "0.1"), 0, 1)));
            var measurements = (ArrayNode) evidence.path("measurements");
            for (int i = measurements.size() - 1; i >= 0; i--)
                if (measurements.get(i).path("metricId").asText().contains("faf95")) measurements.remove(i);
            measurement(evidence, "gnomad.joint.ac").put("rawValue", values[0]).put("normalizedValue", values[0]);
            measurement(evidence, "gnomad.joint.an").put("rawValue", values[1]).put("normalizedValue", values[1]);
            measurement(evidence, "gnomad.joint.nhomalt").put("rawValue", "0").put("normalizedValue", "0");
            var result = evaluator.evaluatePm2Supporting(context, evidence, null, null, production, pm2Registry, SELECTION);
            assertEquals(values[2], status(result)); assertE1Hashes(result);
        }
    }

    private ObjectNode vhlContext() throws IOException {
        var context = context("3", 1014200, "C", "T");
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:12687");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0008667");
        context.put("moi", "AUTOSOMAL_DOMINANT"); refreshContextId(context);
        return context;
    }
    private ObjectNode pm2JointNoRecordCarrier(ObjectNode context) {
        ObjectNode carrier = baseCarrier(context);
        ((ArrayNode) carrier.path("source").path("sourceRecords")).removeAll();
        ((ArrayNode) carrier.path("measurements")).removeAll();
        ObjectNode query = (ObjectNode) carrier.path("queryOutcome");
        query.put("status", "NO_RECORD").put("recordCount", 0);
        return carrier;
    }

    private ObjectNode allSitesCarrier(ObjectNode context,
                                       boolean exomes,
                                       String outcome,
                                       String adjAn,
                                       Map<String, Boolean> flagOverrides) {
        String resourceId = exomes
                ? GnomadPopulationInputProfile.EXOME_ALL_SITES_RESOURCE_ID
                : GnomadPopulationInputProfile.GENOME_ALL_SITES_RESOURCE_ID;
        JsonNode resource = null;
        for (JsonNode value : pm2Profile.getDocument().path("resources")) {
            if (resourceId.equals(value.path("resourceId").asText())) resource = value;
        }
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", "POPULATION_FREQUENCY");
        carrier.set("queryAllele", context.path("variant").path("evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source");
        source.put("sourceId", resourceId);
        java.util.Objects.requireNonNull(resource, "Test resource must exist in the registry");
        source.put("resourceVersion", resource.path("resourceVersion").asText());
        source.put("resourceSha256", resource.path("identityFingerprintSha256").asText());
        source.put("retrievedAt", "2026-09-02T00:00:00Z");
        ArrayNode records = source.putArray("sourceRecords");
        String recordId = resourceId + ":GRCh38:1:12591";
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "GENOMIC_REGION");
        locator.put("locatorValue", recordId);
        locator.put("artifactSha256", resource.path("identityFingerprintSha256").asText());
        locator.put("retrievedAt", "2026-09-02T00:00:00Z");
        if ("HIT".equals(outcome)) {
            locator.put("sourceRecordId", recordId);
            ObjectNode record = records.addObject();
            record.put("recordId", recordId);
            record.set("locator", locator.deepCopy());
        }
        source.putArray("sourceLocators").add(locator);

        boolean completed = Set.of("HIT", "NO_RECORD").contains(outcome);
        ObjectNode execution = carrier.putObject("resourceExecution");
        execution.put("status", completed ? "SUCCEEDED" : outcome);
        execution.put("attemptedAt", "2026-09-01T23:59:58Z");
        execution.put("completedAt", "2026-09-01T23:59:59Z");
        if (!completed) execution.put("reasonCode", "PROVIDER_" + outcome);
        ObjectNode availability = carrier.putObject("resourceAvailability");
        availability.put("status", completed ? "AVAILABLE" : "UNKNOWN");
        if (!completed) availability.put("reasonCode", "NOT_CHECKED");
        ObjectNode query = carrier.putObject("queryOutcome");
        query.put("status", completed ? outcome : "NOT_QUERIED");
        query.put("recordCount", "HIT".equals(outcome) ? 1 : 0);
        if (completed) {
            JsonNode allele = context.path("variant").path("evidenceQueryAllele");
            ObjectNode fingerprint = mapper.createObjectNode();
            fingerprint.put("resourceId", resourceId);
            fingerprint.put("identityFingerprintSha256",
                    resource.path("identityFingerprintSha256").asText());
            fingerprint.set("assembly", allele.path("assembly").deepCopy());
            fingerprint.set("canonicalContig", allele.path("contig").deepCopy());
            fingerprint.set("position", allele.path("position").deepCopy());
            query.put("queryFingerprint", adapter.canonicalSha256(fingerprint));
        }
        ObjectNode applicability = carrier.putObject("resourceApplicability");
        applicability.put("status", completed ? "APPLICABLE" : "UNKNOWN");
        if (!completed) applicability.put("reasonCode", "NOT_EVALUATED");

        ArrayNode measurements = carrier.putArray("measurements");
        if ("HIT".equals(outcome)) {
            addPm2Measurement(measurements,
                    exomes ? "gnomad.exomes.allSites.adjAn"
                            : "gnomad.genomes.allSites.adjAn",
                    adjAn, "alleles");
            if (exomes) {
                for (String flag : List.of(
                        "outside_broad_capture_region", "outside_ukb_capture_region",
                        "outside_broad_calling_region", "outside_ukb_calling_region")) {
                    boolean value = flagOverrides.getOrDefault(flag, false);
                    ObjectNode measurement = measurements.addObject();
                    measurement.put("metricId", "gnomad.exomes.allSites.regionFlag." + flag);
                    measurement.put("rawValue", value).put("normalizedValue", value)
                            .put("unit", "boolean")
                            .put("calibrationProfileId", pm2Profile.getProfileId()
                                    + "@" + pm2Profile.getProfileVersion());
                }
            }
        }
        carrier.put("producedAt", "2026-09-02T00:00:00Z");
        carrier.put("sourcePayloadSha256",
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        return carrier;
    }

    private void addPm2Measurement(ArrayNode values, String metricId, String raw, String unit) {
        ObjectNode value = values.addObject();
        value.put("metricId", metricId).put("rawValue", raw).put("normalizedValue", raw)
                .put("unit", unit).put("calibrationProfileId",
                        pm2Profile.getProfileId() + "@" + pm2Profile.getProfileVersion());
    }

    private GnomadPopulationInputProfile pm2SyntheticProfile(ObjectNode policy) {
        ObjectNode document = pm2Profile.getDocument();
        ObjectNode snapshot = (ObjectNode) pm2Rule(document).path("policySnapshot");
        snapshot.putArray("records").add(policy);
        ObjectNode projection = snapshot.deepCopy();
        projection.remove("sha256");
        snapshot.put("sha256", adapter.canonicalSha256(projection));
        GnomadPopulationInputProfile value = mock(GnomadPopulationInputProfile.class);
        when(value.getDocument()).thenReturn(document);
        when(value.getProfileSha256()).thenReturn(adapter.canonicalSha256(document));
        when(value.getProfileId()).thenReturn(pm2Profile.getProfileId());
        when(value.getProfileVersion()).thenReturn(pm2Profile.getProfileVersion());
        when(value.getSuccessorRegistryDocument()).thenReturn(pm2Registry.deepCopy());
        return value;
    }

    private ObjectNode pm2Policy(String frequency,
                                 String frequencyComparator,
                                 String minimumAn,
                                 List<String> excludedExomeFlags) {
        ObjectNode policy = mapper.createObjectNode();
        policy.put("policyId", "pm2-synthetic-policy").put("version", "1.0.0");
        policy.putObject("gene").put("hgncId", "HGNC:1");
        policy.putObject("disease").put("mondoId", "MONDO:0000001");
        policy.put("moi", "AUTOSOMAL_DOMINANT");
        ArrayNode releases = policy.putArray("resourceReleaseBindings");
        for (JsonNode resource : pm2Profile.getDocument().path("resources")) {
            ObjectNode release = releases.addObject();
            release.put("resourceId", resource.path("resourceId").asText());
            release.put("resourceVersion", resource.path("resourceVersion").asText());
            release.put("identityFingerprintSha256",
                    resource.path("identityFingerprintSha256").asText());
        }
        policy.putObject("maximumAlleleFrequency")
                .put("metricId", "gnomad.joint.faf95max")
                .put("rawValue", frequency)
                .put("comparisonOperator", frequencyComparator);
        policy.put("allowWellCoveredAbsence", true);
        ObjectNode callability = policy.putObject("allSitesCallability");
        callability.put("satisfactionMode", "ANY_INDEPENDENT");
        callability.putArray("independentlySufficientDatasets")
                .add("EXOMES").add("GENOMES");
        ObjectNode exomes = callability.putObject("exomes");
        exomes.put("resourceId", GnomadPopulationInputProfile.EXOME_ALL_SITES_RESOURCE_ID);
        exomes.putObject("minimumAdjAlleleNumber").put("rawValue", minimumAn)
                .put("comparisonOperator", "GREATER_THAN_OR_EQUAL");
        ArrayNode excluded = exomes.putArray("excludedWhenAnyFlagsTrue");
        excludedExomeFlags.forEach(excluded::add);
        ObjectNode genomes = callability.putObject("genomes");
        genomes.put("resourceId", GnomadPopulationInputProfile.GENOME_ALL_SITES_RESOURCE_ID);
        genomes.putObject("minimumAdjAlleleNumber").put("rawValue", minimumAn)
                .put("comparisonOperator", "GREATER_THAN_OR_EQUAL");
        policy.putObject("authority")
                .put("authoritySourceId", "authority-clingen-svi-pm2-v1.0")
                .put("authorityVersion", "1.0")
                .put("snapshotSha256",
                        "30745e928c7146a479afdfb7f22230192a8d1d66f9239a463c53667dd9598c59");
        return policy;
    }

    private JsonNode pm2Rule(JsonNode document) {
        return document.path("evaluation").path("criteria").get(2).path("populationRule");
    }

    private List<ObjectNode> evaluations(ObjectNode context, ObjectNode evidence,
                                         GnomadPopulationInputProfile selectedProfile) {
        return evaluator.evaluate(context, evidence, selectedProfile, registry, SELECTION);
    }

    private ObjectNode criterion(ObjectNode context, ObjectNode evidence, String criterionId) {
        return criterion(context, evidence, profile, criterionId);
    }

    private ObjectNode criterion(ObjectNode context, ObjectNode evidence,
                                 GnomadPopulationInputProfile selectedProfile, String criterionId) {
        return evaluations(context, evidence, selectedProfile).stream()
                .filter(value -> criterionId.equals(value.path("criterionId").asText()))
                .findFirst().orElseThrow();
    }

    private ObjectNode atomic(ObjectNode context, ObjectNode carrier) {
        return adapter.adapt(context, carrier);
    }

    private ObjectNode atomic(ObjectNode context, ObjectNode carrier,
                              GnomadPopulationInputProfile selectedProfile) {
        for (JsonNode value : carrier.path("measurements"))
            if ("gnomad.inputProfileSha256".equals(value.path("metricId").asText())) {
                ((ObjectNode) value).put("rawValue", selectedProfile.getProfileSha256())
                        .put("normalizedValue", selectedProfile.getProfileSha256());
            }
        return adapter.adapt(context, carrier);
    }

    private ObjectNode hitCarrier(ObjectNode context, GnomadPopulationInputProfile.Result parsed) {
        ObjectNode carrier = baseCarrier(context);
        carrier.set("measurements", mapper.valueToTree(parsed.getMeasurements()));
        return carrier;
    }

    private ObjectNode baseCarrier(ObjectNode context) {
        JsonNode resource = profile.getDocument().path("resources").get(0);
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", "POPULATION_FREQUENCY");
        carrier.set("queryAllele", context.path("variant").path("evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source");
        source.put("sourceId", resource.path("resourceId").asText());
        java.util.Objects.requireNonNull(resource, "Test resource must exist in the registry");
        source.put("resourceVersion", resource.path("resourceVersion").asText());
        source.put("resourceSha256", resource.path("identityFingerprintSha256").asText());
        source.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "GENOMIC_REGION");
        locator.put("locatorValue", "GNOMAD_JOINT_V4_1_GRCH38_SITES:GRCh38:exact-allele");
        locator.put("sourceRecordId", "GNOMAD_JOINT_V4_1_GRCH38_SITES:exact-allele");
        locator.put("artifactSha256", resource.path("identityFingerprintSha256").asText());
        locator.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode record = mapper.createObjectNode();
        record.put("recordId", "GNOMAD_JOINT_V4_1_GRCH38_SITES:exact-allele");
        record.set("locator", locator.deepCopy());
        source.putArray("sourceRecords").add(record);
        source.putArray("sourceLocators").add(locator);
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED")
                .put("attemptedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        ObjectNode queryFingerprint = mapper.createObjectNode();
        queryFingerprint.put("resourceId", resource.path("resourceId").asText());
        queryFingerprint.set("queryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                .put("queryFingerprint", adapter.canonicalSha256(queryFingerprint));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putArray("measurements");
        carrier.put("producedAt", "2026-09-02T00:00:00Z");
        carrier.put("sourcePayloadSha256",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return carrier;
    }

    private ObjectNode failedCarrier(ObjectNode context, String failure) {
        ObjectNode carrier = baseCarrier(context);
        ((ArrayNode) carrier.path("source").path("sourceRecords")).removeAll();
        ((ArrayNode) carrier.path("measurements")).removeAll();
        ObjectNode query = (ObjectNode) carrier.path("queryOutcome");
        if ("NO_RECORD".equals(failure)) {
            query.put("status", "NO_RECORD").put("recordCount", 0);
            return carrier;
        }
        query.removeAll();
        query.put("status", "NOT_QUERIED").put("recordCount", 0);
        ObjectNode execution = (ObjectNode) carrier.path("resourceExecution");
        execution.removeAll();
        execution.put("status", "UNAVAILABLE".equals(failure) ? "NOT_RUN" : failure)
                .put("reasonCode", "UNAVAILABLE".equals(failure) ? "RESOURCE_UNAVAILABLE" : "PROVIDER_" + failure);
        ObjectNode availability = (ObjectNode) carrier.path("resourceAvailability");
        availability.removeAll();
        availability.put("status", "UNAVAILABLE".equals(failure) ? "UNAVAILABLE" : "UNKNOWN")
                .put("reasonCode", "RESOURCE_UNAVAILABLE");
        ObjectNode applicability = (ObjectNode) carrier.path("resourceApplicability");
        applicability.removeAll();
        applicability.put("status", "UNKNOWN").put("reasonCode", "NOT_EVALUATED");
        return carrier;
    }

    private Map<String, String> jointInfo(String faf95max, String selectedAn, String pValue) {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("AC_joint", "1");
        info.put("AN_joint", "5000");
        info.put("AF_joint", "0.0002");
        info.put("nhomalt_joint", "0");
        info.put("grpmax_joint", "nfe");
        info.put("AC_grpmax_joint", "1");
        info.put("AN_grpmax_joint", selectedAn);
        info.put("AF_grpmax_joint", "0.0005");
        info.put("nhomalt_grpmax_joint", "0");
        info.put("faf95_joint", faf95max);
        info.put("fafmax_faf95_max_joint", faf95max);
        info.put("fafmax_faf95_max_gen_anc_joint", "nfe");
        info.put("AC_joint_nfe", "1");
        info.put("AN_joint_nfe", selectedAn);
        info.put("AF_joint_nfe", "0.0005");
        info.put("nhomalt_joint_nfe", "0");
        info.put("faf95_joint_nfe", faf95max);
        info.put("stat_union_p_value", pValue);
        info.put("stat_union_test_name", "cmh");
        info.put("stat_union_gen_ancs", "nfe");
        return info;
    }

    private ObjectNode context(String contig, int position, String reference, String alternate)
            throws IOException {
        ObjectNode context;
        try (InputStream input = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            context = (ObjectNode) mapper.readTree(input).path("context").deepCopy();
        }
        ObjectNode allele = mapper.createObjectNode();
        allele.put("assembly", "GRCh38").put("contig", contig).put("position", position)
                .put("reference", reference).put("alternate", alternate);
        ObjectNode variant = (ObjectNode) context.path("variant");
        variant.set("inputAllele", allele.deepCopy());
        variant.set("normalizedAllele", allele.deepCopy());
        variant.set("evidenceQueryAllele", allele.deepCopy());
        ObjectNode normalization = (ObjectNode) variant.path("normalization");
        ObjectNode alleleProjection = mapper.createObjectNode();
        alleleProjection.set("normalizedAllele", allele.deepCopy());
        ObjectNode normalizer = alleleProjection.putObject("normalizer");
        for (String field : List.of("method", "methodVersion", "referenceSequenceSha256", "refValidation"))
            normalizer.set(field, normalization.path(field).deepCopy());
        variant.put("alleleId", "allele-sha256-" + adapter.canonicalSha256(alleleProjection));
        refreshContextId(context);
        return context;
    }

    private void refreshContextId(ObjectNode context) {
        ObjectNode variant = (ObjectNode) context.path("variant");
        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", context.path("scope").deepCopy());
        projection.put("alleleId", variant.path("alleleId").asText());
        projection.put("hgncId", context.path("gene").path("hgncId").asText());
        projection.put("mondoId", context.path("disease").path("mondoId").asText());
        projection.put("moi", context.path("moi").asText());
        ObjectNode transcript = projection.putObject("transcript");
        transcript.put("accession", context.path("transcript").path("accession").asText());
        transcript.set("version", context.path("transcript").path("version").deepCopy());
        projection.put("patientContextId", context.path("patientContext").path("patientContextId").asText());
        projection.set("familyContextId", context.path("patientContext").path("familyContextId").deepCopy());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
    }

    private ObjectNode withThresholds(ObjectNode... records) {
        ObjectNode document = profile.getDocument();
        ObjectNode snapshot = (ObjectNode) bs1Rule(document).path("thresholdSnapshot");
        ArrayNode values = snapshot.putArray("records");
        for (ObjectNode record : records) values.add(record);
        ObjectNode projection = snapshot.deepCopy();
        projection.remove("sha256");
        snapshot.put("sha256", adapter.canonicalSha256(projection));
        return document;
    }

    private ObjectNode threshold(String id, String version, String gene, String mondo, String moi,
                                 String raw) {
        return threshold(id, version, gene, mondo, moi, raw, "GREATER_THAN");
    }

    private ObjectNode threshold(String id, String version, String gene, String mondo, String moi,
                                 String raw, String comparator) {
        ObjectNode record = mapper.createObjectNode();
        record.put("thresholdId", id).put("version", version).put("geneId", gene)
                .put("mondoId", mondo).put("moi", moi)
                .put("metricId", "gnomad.joint.faf95max").put("comparator", comparator)
                .put("thresholdRaw", raw);
        record.putArray("authoritySourceIds").add("authority-synthetic-bs1-threshold");
        return record;
    }

    private GnomadPopulationInputProfile syntheticProfile(ObjectNode document) {
        GnomadPopulationInputProfile value = mock(GnomadPopulationInputProfile.class);
        when(value.getDocument()).thenReturn(document);
        when(value.getProfileSha256()).thenReturn(adapter.canonicalSha256(document));
        when(value.getProfileId()).thenReturn(profile.getProfileId());
        when(value.getProfileVersion()).thenReturn(profile.getProfileVersion());
        when(value.getSuccessorRegistryDocument()).thenReturn(registry.deepCopy());
        return value;
    }

    private JsonNode ba1Rule(JsonNode document) {
        return document.path("evaluation").path("criteria").get(0).path("frequencyRule");
    }

    private JsonNode bs1Rule(JsonNode document) {
        return document.path("evaluation").path("criteria").get(1).path("frequencyRule");
    }

    private ObjectNode measurement(ObjectNode carrier, String metricId) {
        for (JsonNode value : carrier.path("measurements"))
            if (metricId.equals(value.path("metricId").asText())) return (ObjectNode) value;
        throw new AssertionError("Missing measurement " + metricId);
    }

    private void removeMeasurement(ObjectNode carrier, String metricId) {
        ArrayNode values = (ArrayNode) carrier.path("measurements");
        for (int i = 0; i < values.size(); i++)
            if (metricId.equals(values.get(i).path("metricId").asText())) {
                values.remove(i);
                return;
            }
    }

    private void assertNoMet(ObjectNode context, ObjectNode evidence,
                             GnomadPopulationInputProfile selectedProfile) {
        List<ObjectNode> values = evaluations(context, evidence, selectedProfile);
        assertAllNotEvaluated(values);
        assertFalse(values.toString().contains("\"status\":\"MET\""));
    }

    private void assertAllNotEvaluated(List<ObjectNode> values) {
        assertEquals(2, values.size());
        values.forEach(value -> {
            assertEquals("NOT_EVALUATED", status(value));
            assertEquals("NONE", value.path("appliedStrength").asText());
            assertEquals("NOT_RUN", value.path("deterministicTrace").path("executionStatus").asText());
        });
    }

    private String status(JsonNode evaluation) {
        return evaluation.path("evaluationResult").path("status").asText();
    }

    private String reason(JsonNode evaluation) {
        return evaluation.path("evaluationResult").path("reasonCode").asText();
    }

    private String parameter(JsonNode evaluation, String name) {
        for (JsonNode value : evaluation.path("deterministicTrace").path("parameters"))
            if (name.equals(value.path("name").asText())) return value.path("value").asText();
        throw new AssertionError("Missing parameter " + name);
    }

    private List<String> textValues(JsonNode values) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private java.util.SortedSet<String> registryAuthorities(String criterionId) {
        JsonNode criterion = null;
        for (JsonNode value : registry.path("criteria"))
            if (criterionId.equals(value.path("criterionId").asText())) criterion = value;
        java.util.Objects.requireNonNull(criterion, "Test criterion must exist in the registry");
        return new java.util.TreeSet<>(textValues(criterion.path("currentGeneral").path("authoritySourceIds")));
    }

    private List<String> parameterNames(JsonNode evaluation) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        evaluation.path("deterministicTrace").path("parameters")
                .forEach(value -> result.add(value.path("name").asText()));
        return result;
    }

    private void assertE1Hashes(ObjectNode evaluation) {
        JsonNode trace = evaluation.path("deterministicTrace");
        if ("COMPLETED".equals(trace.path("executionStatus").asText())) {
            ObjectNode projection = ((ObjectNode) trace).deepCopy();
            String declared = projection.remove("traceSha256").asText();
            assertEquals(adapter.canonicalSha256(projection), declared);
        }
        ObjectNode projection = evaluation.deepCopy();
        String declared = projection.remove("evaluationId").asText()
                .replace("evaluation-sha256-", "");
        projection.remove(List.of("conflict", "review"));
        assertEquals(adapter.canonicalSha256(projection), declared);
    }
}
