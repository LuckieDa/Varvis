package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile;
import org.nbscn.varvis.variantanalysis.provider.AcmgHistoricalExecutionProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Joint assembly checks; candidate annotations below are synthetic engineering fixtures. */
class Bs1Ps3JointAssemblyTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void jointRetainsTenCriteriaAndAddsPm1WithoutClassificationOrBs3() {
        var execution = new AcmgExecutionProfile(mapper);
        var expected = Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BA1",
                "ACMG_AMP_2015:BP7", "ACMG_AMP_2015:PM2", "ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5",
                "ACMG_AMP_2015:PVS1", "ACMG_AMP_2015:BS1", "ACMG_AMP_2015:PS3", "ACMG_AMP_2015:PM1");
        assertEquals(11, execution.criteria().size());
        assertEquals(expected, new HashSet<>(execution.criteria()));
        var document = execution.document();
        assertEquals("acmg-explicit-business-joint-v8", document.path("profileId").asText());
        assertEquals("1.7.0", document.path("manifestVersion").asText());
        assertFalse(document.path("automaticTrigger").asBoolean(true));
        assertFalse(document.path("classificationEnabled").asBoolean(true));
        for (var slice : document.path("slices")) for (var id : slice.path("criterionIds")) {
            assertNotEquals("ACMG_AMP_2015:BS3", id.asText());
        }
    }

    @Test void successorPreservesEveryLatestMainSliceAndTheExactPm1BranchSlice() throws Exception {
        var successor = new AcmgExecutionProfile(mapper).document();
        try (var mainInput = java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                    "/acmg/acmg-business-execution-profile-joint-v7.json"));
             var pm1Input = java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                    "/acmg/acmg-business-execution-profile-pm1-hnf1a-v1.json"))) {
            var previousMain = mapper.readTree(mainInput);
            var previousPm1 = mapper.readTree(pm1Input);
            for (var inherited : previousMain.path("slices"))
                assertEquals(inherited, slice(successor, inherited.path("profileId").asText()));
            for (var inherited : previousPm1.path("slices"))
                if (inherited.path("criterionIds").toString().equals("[\"ACMG_AMP_2015:PM1\"]"))
                    assertEquals(inherited, slice(successor, inherited.path("profileId").asText()));
            assertEquals(10, previousMain.path("enabledCriteria").size());
            assertEquals(9, previousPm1.path("enabledCriteria").size());
        }
    }

    @Test void everyInheritedSliceRetainsItsWholeScientificAndResourceIdentity() {
        var joint = new AcmgExecutionProfile(mapper).document();
        var main = AcmgHistoricalExecutionProfiles.load(mapper, "acmg-explicit-business-joint-v6",
                "28f28fd0b08ceeca7aa00515a77f004e7f45885091bc0c4110774e0388a29760");
        var bs1 = AcmgHistoricalExecutionProfiles.load(mapper, "acmg-explicit-business-joint-v4",
                "57a357360ea9312ae7f4a3c37f9bf3ec9da30c9ed43716cdbccc01a09874e4c1");
        var ps3 = AcmgHistoricalExecutionProfiles.load(mapper, "acmg-explicit-business-joint-v1",
                "f71fde7c4265a393dec414235421fdb855d449162f17602e642d6334af20f798");
        assertEquals(9, main.path("slices").size());
        assertEquals(12, joint.path("slices").size());
        for (var inherited : main.path("slices"))
            assertEquals(inherited, slice(joint, inherited.path("profileId").asText()));
        assertEquals(slice(bs1, "GNOMAD_HNF1A_DISEASE_FREQUENCY"), slice(joint, "GNOMAD_HNF1A_DISEASE_FREQUENCY"));
        assertEquals(slice(ps3, "HNF1A_OXFORD_FUNCTIONAL_PS3"), slice(joint, "HNF1A_OXFORD_FUNCTIONAL_PS3"));
        assertEquals("2.0.1", slice(joint, "HNF1A_TRUNCATING_NMD_PVS1").path("profileVersion").asText());
        assertEquals("1.0.0", slice(joint, "HNF1A_GN017_3_1_REVEL").path("profileVersion").asText());
        assertEquals("1.0.1", slice(joint, "HNF1A_GN017_3_1_PS1_PM5").path("profileVersion").asText());
        var functional = slice(joint, "HNF1A_OXFORD_FUNCTIONAL_PS3");
        assertEquals("1.0.1", functional.path("profileVersion").asText());
        assertEquals("aecb00a1f5f0c65cba3484e0d1dc4b62856fa6e7d66f7219eb1915bf4044970a",
                functional.path("profileCanonicalSha256").asText());
        var retained = AcmgHistoricalExecutionProfiles.loadScientificSnapshot(mapper,
                functional.path("profileCanonicalSha256").asText());
        assertFalse(retained.at("/policy/bs3/enabled").asBoolean(true));
        assertEquals("a70b1e1640293bd43897a7a8d8517fbc1b3fdcb9e0b45ff6bb24113bf341a427",
                retained.at("/factPackage/canonicalSha256").asText());
        assertEquals(main.path("spliceBusinessBinding"), joint.path("spliceBusinessBinding"));
        assertEquals(main.path("synonymousPositionPolicy"), joint.path("synonymousPositionPolicy"));
    }

    @Test void currentJointBindsAllThreePredecessorsByFullIdentity() throws Exception {
        var bindings = new AcmgExecutionProfile(mapper).document().path("historicalPredecessors");
        assertEquals(3, bindings.size());
        AcmgHistoricalExecutionProfiles.validatePredecessors(mapper, bindings);
        var expectedCommits = Set.of("5847abcc83758d1ccbcdea0c511ff1830d64500f",
                "335c8acdf6aebcc3e7cff0129dfa72c1534f3bcf", "ee7289cdc7e2516efb830970e26be87a74660fae");
        var observedCommits = new HashSet<String>();
        var catalog = resource("/acmg/history/execution-profile-history-v1.json");
        for (var binding : bindings) {
            assertEquals(6, binding.size());
            observedCommits.add(binding.path("sourceCommit").asText());
            int exactMatches = 0;
            for (var registered : catalog.path("profiles")) if (registered.equals(binding)) exactMatches++;
            assertEquals(1, exactMatches);
        }
        assertEquals(expectedCommits, observedCommits);
    }

    @ParameterizedTest @CsvSource({"missense_variant,HNF1A_REVEL", "stop_gained,PVS1_STOP_GAINED_CONTEXT"})
    void frequencyOnlyPreparationDoesNotChooseAnOtherwiseAvailablePredictionTranscript(
            String consequence, String availablePath) throws Exception {
        boolean stop = "stop_gained".equals(consequence);
        var annotation = mapper.createObjectNode().put("geneSymbol", "HNF1A")
                .put("transcriptAccession", "ENST00000257555.11")
                .put("hgvsCoding", stop ? "ENST00000257555.11:c.511C>T" : "ENST00000257555.11:c.335C>T")
                .put("hgvsProtein", stop ? "ENSP00000257555.5:p.Arg171Ter" : "ENSP00000257555.5:p.Pro112Leu")
                .put("consequence", consequence).put("source", "VEP").put("version", "116.0")
                .put("status", "COMPLETED").put("timestamp", "2026-09-11T01:00:00Z").put("maneSelect", true);
        var candidate = mock(VariantInterpretation.class);
        var features = mock(ExomiserCandidateFeatures.class);
        var identity = mock(CandidateIdentity.class);
        var observation = mock(VariantQcObservation.class);
        when(candidate.getCandidateId()).thenReturn("synthetic-joint-candidate");
        when(candidate.getExomiserCandidateFeatures()).thenReturn(features);
        when(candidate.getCandidateIdentity()).thenReturn(identity);
        when(candidate.getObservation()).thenReturn(observation);
        when(observation.getKey()).thenReturn(new NormalizedVariantKey("GRCh38", "12", stop ? 120989017 : 120988841, "C", "T"));
        when(features.getGeneSymbol()).thenReturn("HNF1A");
        when(features.getRawModeOfInheritance()).thenReturn("AD");
        when(features.getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        when(identity.getModeOfInheritance()).thenReturn("AD");
        when(candidate.getTranscriptAnnotations()).thenReturn(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        var catalog = mapper.createObjectNode().put("hgncId", "HGNC:11621");
        catalog.putObject("relationshipSource").put("sha256", "synthetic-joint-catalog");
        catalog.putArray("relationships").addObject().put("hgncId", "HGNC:11621").put("symbol", "HNF1A")
                .put("mondoId", "MONDO:0015967").put("moi", "AD").put("classification", "Definitive")
                .put("classificationDate", "2026-01-01").put("gcep", "Synthetic expert panel")
                .put("reportLocator", "synthetic-joint/relationship");
        var policy = new AcmgRecommendations(mapper,
                ignored -> { fail("Frequency confirmation must not query AlphaMissense"); return null; },
                Clock.fixed(Instant.parse("2026-09-11T01:00:00Z"), ZoneOffset.UTC));
        var recommendation = policy.recommend("synthetic-joint-analysis", candidate, catalog, "a".repeat(64));
        assertEquals(availablePath, recommendation.at("/resourceCompatibleTranscript/predictionPath").asText());
        assertTrue(recommendation.at("/hnf1aFrequencyContextPreparation/available").asBoolean());
        assertEquals("HNF1A_DISEASE_BA1_BS1_AND_PM2_NOT_PREDICTION_CONFIRMATION",
                recommendation.at("/hnf1aFrequencyContextPreparation/purpose").asText());
        var choice = new AcmgRecommendations.Selection(candidate.getCandidateId(),
                recommendation.path("recommendationId").asText(), AcmgRecommendations.VERSION,
                recommendation.path("policyHash").asText(), null, recommendation.path("stableRelationshipKey").asText(), null);
        policy.verify(choice, recommendation, List.of());
        var request = new AcmgSubmission(1, "synthetic-joint-v1", null, "synthetic-sample", "SYNTHETIC",
                null, List.of(), List.of(choice));
        var prepared = policy.withPopulationContexts(request, mapper.createArrayNode().add(recommendation));
        assertEquals(1, prepared.size());
        assertEquals(AcmgRecommendations.FREQUENCY_CONTEXT_SELECTION, prepared.get(0).transcript().selectionPolicy());
        assertTrue(AcmgRecommendations.populationOnlySelection(prepared.get(0).transcript().selectionPolicy()));
        assertFalse(AcmgRecommendations.populationOnlySelection(AcmgRecommendations.POLICY_ID + "@" + AcmgRecommendations.VERSION));
        assertTrue(request.hypotheses().isEmpty());
        assertNull(choice.annotationId());
    }

    private JsonNode slice(JsonNode profile, String id) {
        var matches = new java.util.ArrayList<JsonNode>();
        for (var entry : profile.path("slices")) if (id.equals(entry.path("profileId").asText())) matches.add(entry);
        assertEquals(1, matches.size(), id);
        return matches.get(0);
    }

    private JsonNode resource(String path) throws Exception {
        try (var input = getClass().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing resource " + path);
            var value = mapper.readTree(input);
            if (value == null) throw new IllegalStateException("Missing JSON " + path);
            return value;
        }
    }
}
