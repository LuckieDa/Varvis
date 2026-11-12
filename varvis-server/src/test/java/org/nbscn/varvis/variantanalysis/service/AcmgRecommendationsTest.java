package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All query identities and candidate annotations here are explicit synthetic engineering fixtures. */
class AcmgRecommendationsTest {
    @Test void missingDiseaseCatalogPrecedesTranscriptAndPolicyExplanations() throws Exception {
        var raw = mapper.createObjectNode().put("geneSymbol", "HBB").put("transcriptAccession", "ENST00000335295.4")
                .put("consequence", "stop_gained").put("source", "VEP_CACHED").put("version", "116.0")
                .put("status", "COMPLETED").put("timestamp", "2026-09-07T00:00:00Z").put("maneSelect", true);
        var c = candidate("HBB", mapper.treeToValue(raw, TranscriptConsequence.class));
        var absent = catalog();
        ((ObjectNode) absent.path("relationshipSource")).put("resourceId", "SYNTHETIC_GENE_DISEASE")
                .put("resourceVersion", "1").put("derivedSha256", "a".repeat(64));
        var p = new AcmgRecommendations(mapper, ignored -> { throw new AssertionError("No scientific lookup"); }, clock);
        var r = p.recommend("analysis", c, absent, "source");
        var d = r.at("/pm2ContextPreparation/diagnostics");
        assertEquals("PM2_DISEASE_CATALOG_NO_RECORD", d.path("reasonCode").asText());
        assertEquals(0, d.at("/relationshipCheck/catalogRecordCount").asInt(-1));
        assertEquals(1, d.at("/transcriptCheck/sameGeneCompletedVepCount").asInt());
        assertEquals("DISEASE_RELATIONSHIP_REQUIRED_BEFORE_CONTEXT_SELECTION", d.at("/transcriptCheck/status").asText());
        assertEquals(0, d.at("/policyCheck/geneRecordCount").asInt(-1));
        assertFalse(r.at("/pm2ContextPreparation/available").asBoolean());
        assertEquals(d, mapper.readTree(mapper.writeValueAsBytes(r)).at("/pm2ContextPreparation/diagnostics"));
        var choice = selection(r, null, null);
        p.verify(choice, r, List.of());
        var request = new AcmgSubmission(1, "ordinary-ui-v3", null, "sample", "SYNTHETIC", null, List.of(), List.of(choice));
        assertTrue(p.withPopulationContexts(request, mapper.createArrayNode().add(r)).isEmpty());
        // A real catalog relationship is independent of whether the finite PM2 policy pack covers it.
        var related = p.recommend("analysis", c, catalog("Definitive"), "source");
        assertEquals(1, related.at("/pm2ContextPreparation/diagnostics/relationshipCheck/selectableRecordCount").asInt());
        assertEquals("PM2_CONTEXT_POLICY_NO_MATCH", related.at("/pm2ContextPreparation/diagnostics/reasonCode").asText());
        assertEquals(1, related.path("annotations").size());
        assertEquals("PM2_EXECUTION_SOURCE_NOT_READY", p.recommend("analysis", c, absent, null)
                .at("/pm2ContextPreparation/diagnostics/reasonCode").asText());
    }

    @Test void diagnosticsAndConfirmationStayBoundToCandidateAndBackground() throws Exception {
        var first = candidate("HBB", annotation("HBB", "ENST00000335295.4", true));
        var second = candidate("HBB", annotation("HBB", "ENST00000335295.4", true));
        when(first.getCandidateId()).thenReturn("same-allele-background-a");
        when(second.getCandidateId()).thenReturn("same-allele-background-b");
        var p = policy();
        var a = p.recommend("analysis", first, catalog(), "source");
        var b = p.recommend("analysis", second, catalog("Definitive"), "source");
        assertEquals(first.getObservation().getKey(), second.getObservation().getKey());
        assertNotEquals(a.path("hypothesisId"), b.path("hypothesisId"));
        assertEquals(0, a.at("/pm2ContextPreparation/diagnostics/relationshipCheck/catalogRecordCount").asInt(-1));
        assertEquals(1, b.at("/pm2ContextPreparation/diagnostics/relationshipCheck/catalogRecordCount").asInt());
        assertThrows(IllegalArgumentException.class, () -> p.verify(selection(a, null, null), b, List.of()));
        assertEquals("PM2_DISEASE_CATALOG_NO_RECORD", a.at("/pm2ContextPreparation/diagnostics/reasonCode").asText());
    }

    ObjectNode ba1Snapshot(String version) {
        var snapshot = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        var metadata = snapshot.putObject("metadata").put("snapshotId", version).put("sourceBase", "https://example.test/cspec")
                .put("status", "COMPLETE").put("publicationScope", "Released").put("declaredEntityCount", 1)
                .put("enumeratedEntityCount", 1).put("entryCount", 1).put("selectedReleasedCount", 1);
        metadata.putArray("pages").addObject().put("page", 0).put("returnedCount", 1).put("rawSha256", "a".repeat(64));
        metadata.put("compilerVersion", "test").put("captureManifestSha256", "a".repeat(64));
        metadata.putObject("batchReview").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ONLY")
                .put("compilerVersion", "test").put("sourceCaptureSha256", "a".repeat(64));
        var entry = snapshot.putArray("entries").addObject().put("specificationId", "SYNTHETIC_BA1_BACKGROUND")
                .put("version", "1.0").put("publicationStatus", "Released").put("rawSha256", "b".repeat(64));
        var scope = entry.putObject("scope").put("status", "RESOLVED").put("transcriptScope", "UNRESTRICTED");
        scope.putArray("hgncIds").add("HGNC:4827"); scope.putArray("mondoIds").add("MONDO:0000001");
        scope.putArray("moi").add("AUTOSOMAL_RECESSIVE");
        entry.putObject("ba1").put("disposition", "GENERAL").putArray("sourceRefs")
                .addObject().put("url", "https://example.test/synthetic-ba1").put("sha256", "b".repeat(64));
        snapshot.putArray("geneMappings").addObject().put("hgncId", "HGNC:4827").putArray("geneSymbols").add("HBB");
        snapshot.putObject("geneMappingSource").put("status", "COMPLETE").put("rawSha256", "d".repeat(64))
                .put("url", "https://example.test/hgnc").put("recordCount", snapshot.path("geneMappings").size());
        return snapshot;
    }

    @Test void ba1DiseaseOnlyConfirmationUsesActualAnnotationAndCatalogBoundIdentity() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000335295.4", true));
        var snapshot = ba1Snapshot("synthetic-v1");
        var source = new Ba1SpecificationCatalog(mapper, snapshot);
        assertEquals("READY", source.identity().path("status").asText());
        var p = new AcmgRecommendations(mapper, ignored -> new AlphaMissenseProvider.IdentityLookup(
                "UNAVAILABLE", "SYNTHETIC_NO_AM", null, null, List.of()), clock, null, null, source);
        var r = p.recommend("analysis", c, catalog("Definitive"), "a".repeat(64));
        assertTrue(r.at("/ba1DiseaseContextPreparation/available").asBoolean(), r.toPrettyString());
        assertFalse(r.at("/ba1DiseaseContextPreparation/transcriptConfirmed").asBoolean(true));
        var selection = new AcmgRecommendations.Selection(r.path("candidateId").asText(), r.path("recommendationId").asText(),
                r.path("policyVersion").asText(), r.path("policyHash").asText(), null, r.path("stableRelationshipKey").asText(), null);
        var request = new AcmgSubmission(1, "ordinary-ui-v3", null, "sample", "SYNTHETIC", null, List.of(), List.of(selection));
        p.verify(selection, r, List.of());
        var prepared = p.withPopulationContexts(request, mapper.createArrayNode().add(r));
        assertEquals(1, prepared.size());
        assertEquals(AcmgRecommendations.BA1_CONTEXT_SELECTION, prepared.get(0).transcript().selectionPolicy());
        assertEquals("ENST00000335295", prepared.get(0).transcript().accession());
        assertEquals(4, prepared.get(0).transcript().version());
        assertEquals("VEP", prepared.get(0).transcript().annotationSource());
        assertTrue(AcmgRecommendations.populationOnlySelection(prepared.get(0).transcript().selectionPolicy()));
        assertTrue(request.hypotheses().isEmpty());
        var changed = new AcmgRecommendations(mapper, ignored -> new AlphaMissenseProvider.IdentityLookup(
                "UNAVAILABLE", "SYNTHETIC_NO_AM", null, null, List.of()), clock, null, null,
                new Ba1SpecificationCatalog(mapper, ba1Snapshot("synthetic-v2")));
        var current = changed.recommend("analysis", c, catalog("Definitive"), "a".repeat(64));
        assertNotEquals(r.path("recommendationId"), current.path("recommendationId"));
        assertThrows(IllegalArgumentException.class, () -> changed.verify(selection, current, List.of()));
        assertFalse(p.recommend("analysis", c, catalog("Definitive"), null).at("/ba1DiseaseContextPreparation/available").asBoolean());
        var withoutDisease = catalog("Definitive"); ((ObjectNode) withoutDisease.at("/relationships/0")).put("mondoId", "MONDO:0000002");
        assertFalse(p.recommend("analysis", c, withoutDisease, "a".repeat(64)).at("/ba1DiseaseContextPreparation/available").asBoolean());
        var duplicate = candidate("HBB", annotation("HBB", "ENST00000335295.4", true), annotation("HBB", "ENST00000335295.4", true));
        assertFalse(p.recommend("analysis", duplicate, catalog("Definitive"), "a".repeat(64)).at("/ba1DiseaseContextPreparation/available").asBoolean());
    }

    @Test void ba1BackgroundAvailabilityDoesNotAuthorizeExactTranscriptOrChangeLegacyNullRecommendations() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000335295.4", true));
        var snapshot = ba1Snapshot("exact-background");
        ((ObjectNode) snapshot.at("/entries/0/scope")).put("transcriptScope", "EXACT").putArray("transcripts").add("NM_000518.5");
        var unavailable = new AlphaMissenseProvider.IdentityLookup("UNAVAILABLE", "SYNTHETIC_NO_AM", null, null, List.of());
        var p = new AcmgRecommendations(mapper, ignored -> unavailable, clock, null, null, new Ba1SpecificationCatalog(mapper, snapshot));
        var r = p.recommend("analysis", c, catalog("Definitive"), "a".repeat(64));
        assertTrue(r.at("/ba1DiseaseContextPreparation/available").asBoolean());
        assertEquals("EXACT", r.at("/ba1DiseaseContextPreparation/catalogBackgrounds/0/transcriptScope").asText());
        assertFalse(r.at("/ba1DiseaseContextPreparation/transcriptConfirmed").asBoolean(true));
        for (String incompatibleMoi : List.of("AUTOSOMAL_DOMINANT", "XL")) {
            var incompatible = snapshot.deepCopy();
            ((ObjectNode) incompatible.at("/entries/0/scope")).putArray("moi").add(incompatibleMoi);
            var rejected = new AcmgRecommendations(mapper, ignored -> unavailable, clock, null, null,
                    new Ba1SpecificationCatalog(mapper, incompatible));
            assertFalse(rejected.recommend("analysis", c, catalog("Definitive"), "a".repeat(64))
                    .at("/ba1DiseaseContextPreparation/available").asBoolean(), incompatibleMoi);
        }
        var old = new AcmgRecommendations(mapper, ignored -> unavailable, clock);
        var disabled = new AcmgRecommendations(mapper, ignored -> unavailable, clock, null, null, null);
        assertEquals(old.recommend("analysis", c, catalog("Definitive"), "a".repeat(64)),
                disabled.recommend("analysis", c, catalog("Definitive"), "a".repeat(64)));
    }

    @Test void predictionContextConfirmationNeverQueriesAmIdentityIncludingUnavailableBundle() throws Exception {
        var c = candidate("GENE", annotation("GENE", "ENST000001.4", true));
        var cat = catalog("Definitive");
        cat.put("hgncId", "HGNC:1");
        ((ObjectNode) cat.at("/relationships/0")).put("symbol", "GENE").put("hgncId", "HGNC:1").put("moi", "AD");
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        var context = ReviewedPredictionBusinessRuntime.unavailable(mapper, "a".repeat(64), "b".repeat(64)).contexts();
        var policy = new AcmgRecommendations(mapper, ignored -> { throw new AssertionError("No confirmation-time identity query"); }, clock, null, context);
        var result = policy.recommend("analysis", c, cat, "source");
        assertEquals("NOT_QUERIED", result.at("/resourceIdentity/status").asText());
        assertTrue(result.at("/annotations/0/predictionContextReady").asBoolean(), result.toPrettyString());
        assertFalse(result.at("/annotations/0/identityCompatible").asBoolean());
        assertFalse(result.at("/annotations/0/predictionCompatible").asBoolean());
        assertEquals(context.selectionPolicy(), result.at("/annotations/0/predictionContextSelectionPolicy").asText());
        assertEquals("PREDICTION_CONTEXT_RECOMMENDATION", result.path("transcriptStatus").asText());
        assertEquals("READY_FOR_CONFIRMATION", result.path("status").asText());
    }
    @Test void hnf1aReferenceComparisonPreparationDoesNotRequirePredictionScores() throws Exception {
        var tx = mapper.treeToValue(mapper.createObjectNode().put("geneSymbol", "HNF1A")
                .put("transcriptAccession", "ENST00000257555.11").put("hgvsCoding", "ENST00000257555.11:c.58G>C")
                .put("hgvsProtein", "ENSP00000257555.5:p.Gly20Arg").put("proteinPosition", "20")
                .put("aminoAcids", "G/R").put("codons", "Ggg/Cgg").put("exon", "1/10")
                .put("consequence", "missense_variant").put("source", "VEP").put("version", "116.0")
                .put("status", "COMPLETED").put("timestamp", "2026-09-11T01:00:00Z").put("maneSelect", true), TranscriptConsequence.class);
        var c = candidate("HNF1A", tx);
        when(c.getObservation().getKey()).thenReturn(new NormalizedVariantKey("GRCh38", "12", 120978826, "G", "C"));
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        var cat = catalog("Definitive"); cat.put("hgncId", "HGNC:11621");
        ((ObjectNode) cat.at("/relationships/0")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A")
                .put("mondoId", "MONDO:0015967").put("moi", "AD");
        var policy = new AcmgRecommendations(mapper, ignored -> { fail("Reference preparation must not query AM"); return null; }, clock);
        var result = policy.recommend("analysis", c, cat, "source");
        assertTrue(result.at("/annotations/0/ps1Pm5Compatible").asBoolean());
        assertFalse(result.at("/annotations/0").has("revelScore"));
        assertEquals("READY_FOR_CONFIRMATION", result.path("status").asText());
    }

    @Test void hnf1aRevelContextSelectionDoesNotReadAmOrPredictionScores() throws Exception {
        var c = candidate("HNF1A", annotation("HNF1A", "ENST00000257555.11", true),
                annotation("HNF1A", "ENST00000540108.1", false));
        when(c.getObservation().getKey()).thenReturn(new NormalizedVariantKey("GRCh38", "12", 120978497, "A", "G"));
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        var cat = catalog("Definitive"); cat.put("hgncId", "HGNC:11621");
        ((ObjectNode)cat.at("/relationships/0")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A")
                .put("mondoId", "MONDO:0015967").put("moi", "AD");
        var policy = new AcmgRecommendations(mapper, ignored -> { fail("REVEL context selection must not query AM"); return null; }, clock);
        var result = policy.recommend("analysis", c, cat, "source");
        assertEquals("READY_FOR_CONFIRMATION", result.path("status").asText());
        var primary = java.util.stream.StreamSupport.stream(result.path("annotations").spliterator(), false)
                .filter(a -> "ENST00000257555.11".equals(a.path("versionedTranscriptId").asText())).findFirst().orElseThrow();
        var alternative = java.util.stream.StreamSupport.stream(result.path("annotations").spliterator(), false)
                .filter(a -> "ENST00000540108.1".equals(a.path("versionedTranscriptId").asText())).findFirst().orElseThrow();
        assertEquals("HNF1A_REVEL", primary.path("predictionPath").asText());
        assertEquals("HNF1A_REVEL_TRANSCRIPT_CONTEXT", primary.path("identityReason").asText());
        assertTrue(primary.path("predictionCompatible").asBoolean());
        assertTrue(primary.path("pm1Compatible").asBoolean(), "PM1 transcript eligibility uses no score/provider availability");
        assertFalse(alternative.path("pm1Compatible").asBoolean());
        assertEquals(primary.path("annotationId"), result.at("/pm1CompatibleTranscript/annotationId"));
        assertFalse(primary.has("alphaMissenseUniprotId"));
        assertTrue(result.path("annotations").findValuesAsText("predictionPath").stream().noneMatch("ALPHAMISSENSE_MISSENSE"::equals));
        assertEquals("REVEL_TRANSCRIPT_NOT_SUPPORTED", alternative.path("predictionPolicyReason").asText());
        assertFalse(result.path("transcriptReasonCode").asText().contains("ALPHAMISSENSE"));
        assertDoesNotThrow(() -> policy.verify(selection(result, result.path("stableRelationshipKey").asText(),
                result.path("recommendedAnnotationId").asText()), result, List.of()));
    }

    @Test void pm2OnlyPreparationDoesNotImplicitlyConfirmTheNewRevelPredictionContext() throws Exception {
        var clinical = annotation("HNF1A", "ENST00000257555.11", true);
        var intronic = mapper.createObjectNode().put("geneSymbol", "HNF1A").put("source", "VEP").put("version", "116.0")
                .put("status", "COMPLETED").put("timestamp", "2026-09-07T00:00:00Z").put("consequence", "intron_variant")
                .put("transcriptAccession", "ENST00000540108.1").put("hgvsCoding", "ENST00000540108.1:c.327-4673G>A");
        var a = mapper.treeToValue(intronic, TranscriptConsequence.class);
        intronic.put("transcriptAccession", "ENST00000544574.5").put("hgvsCoding", "ENST00000544574.5:c.73-7770G>A");
        var variant = candidate("HNF1A", clinical, a, mapper.treeToValue(intronic, TranscriptConsequence.class));
        when(variant.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        when(variant.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        when(variant.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        var c = catalog("Definitive"); c.put("hgncId", "HGNC:11621");
        ((ObjectNode)c.path("relationships").get(0)).put("hgncId", "HGNC:11621").put("symbol", "HNF1A").put("moi", "AD").put("mondoId", "MONDO:0015967");
        var p = AcmgRecommendations.unavailable(mapper);
        var r = p.recommend("analysis", variant, c, "a".repeat(64));
        assertEquals("HNF1A_REVEL", r.at("/resourceCompatibleTranscript/predictionPath").asText());
        assertEquals("ENST00000257555.11", r.at("/pm2ContextPreparation/clinicalAnnotation/versionedTranscriptId").asText());
        assertTrue(r.at("/pm2ContextPreparation/available").asBoolean());
        assertEquals("PM2_CONTEXT_CONFIRMATION_REQUIRED", r.at("/pm2ContextPreparation/diagnostics/reasonCode").asText());
        var selection = new AcmgRecommendations.Selection(r.path("candidateId").asText(), r.path("recommendationId").asText(), AcmgRecommendations.VERSION,
                r.path("policyHash").asText(), null, r.path("stableRelationshipKey").asText(), null);
        var request = new AcmgSubmission(1, "ordinary-ui-v3", null, "sample", "SYNTHETIC", null, List.of(), List.of(selection));
        p.verify(selection, r, List.of());
        var prepared = p.withPopulationContexts(request, mapper.createArrayNode().add(r));
        assertEquals(1, prepared.size());
        assertTrue(r.at("/hnf1aFrequencyContextPreparation/available").asBoolean());
        assertEquals(AcmgRecommendations.FREQUENCY_CONTEXT_SELECTION, prepared.get(0).transcript().selectionPolicy());
        assertTrue(request.hypotheses().isEmpty()); assertEquals("HNF1A_REVEL", r.at("/resourceCompatibleTranscript/predictionPath").asText());
        var none = new AcmgRecommendations.Selection(selection.candidateId(), selection.recommendationId(), selection.policyVersion(), selection.policyHash(), null, null, null);
        assertTrue(p.withPopulationContexts(new AcmgSubmission(1, "ordinary-ui-v3", null, "sample", "SYNTHETIC", null, List.of(), List.of(none)), mapper.createArrayNode().add(r)).isEmpty());
        assertFalse(p.recommend("analysis", variant, c, null).at("/pm2ContextPreparation/available").asBoolean());
        when(variant.getCandidateIdentity().getModeOfInheritance()).thenReturn("AR");
        assertFalse(p.recommend("analysis", variant, c, "a".repeat(64)).at("/pm2ContextPreparation/available").asBoolean());
    }

    @Test void transcriptSpecificPolicyCannotUseIndependentClinicalPreparation() {
        var policy = mapper.createObjectNode().put("maximumAlleleCount", 1);
        assertTrue(AcmgRecommendations.transcriptIndependentPopulationPolicy(policy));
        policy.putObject("transcriptRequirement").put("accession", "ENST00000257555");
        assertFalse(AcmgRecommendations.transcriptIndependentPopulationPolicy(policy));
    }
    @Test void localInterpretationCanBeChosenWithoutRewritingRankingInheritance() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000335295.4", true));
        when(c.getAnalysisVcfCall()).thenReturn(LocalInterpretationContextPolicyTest.call("REF|TARGET_ALT",
                AnalysisVcfCall.TargetAlleleCall.HET_TARGET));
        var cat = catalog("Definitive"); ((ObjectNode)cat.at("/relationships/0")).put("moi", "AD");
        var legacy = policy(record("ENST00000335295.4", "0.1"));
        assertFalse(legacy.recommend("a", c, cat, "source").at("/relationships/0/selectable").asBoolean());
        var current = legacy.withLocalBatch(mapper.createObjectNode()).recommend("a", c, cat, "source");
        assertTrue(current.at("/relationships/0/selectable").asBoolean(), current.toPrettyString());
        assertTrue(current.path("stableRelationshipKey").isNull());
        assertEquals("REVIEW_REQUIRED", current.path("relationshipStatus").asText());
        assertEquals("AR", c.getCandidateIdentity().getModeOfInheritance());
        var noSource = legacy.withLocalBatch(mapper.createObjectNode()).recommend("a", c, cat, null);
        assertFalse(noSource.at("/relationships/0/selectable").asBoolean());
        assertEquals("INTERPRETATION_PUBLISHED_SOURCE_REQUIRED", noSource.at("/relationships/0/reasonCode").asText());
    }

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Clock clock = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC);
    final NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "11", 5227002, "T", "A");
    TranscriptConsequence annotation(String gene, String tx, boolean mane) throws Exception {
        var a = mapper.createObjectNode().put("geneSymbol", gene).put("transcriptAccession", tx)
                .put("hgvsCoding", tx + ":c.20A>T").put("hgvsProtein", "ENSP00000494175.1:p.Glu7Val")
                .put("proteinPosition", "7").put("aminoAcids", "E/V").put("consequence", "missense_variant")
                .put("source", "VEP").put("version", "116.0").put("status", "COMPLETED")
                .put("timestamp", "2026-09-07T00:00:00Z").put("maneSelect", mane);
        return mapper.treeToValue(a, TranscriptConsequence.class);
    }
    VariantInterpretation candidate(String gene, TranscriptConsequence... annotations) {
        var candidate = mock(VariantInterpretation.class); var f = mock(ExomiserCandidateFeatures.class);
        var identity = mock(CandidateIdentity.class);
        when(identity.getModeOfInheritance()).thenReturn("AR"); when(candidate.getCandidateIdentity()).thenReturn(identity);
        when(f.getRawModeOfInheritance()).thenReturn("AR"); when(f.getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_RECESSIVE");
        var observation = mock(VariantQcObservation.class);
        when(f.getGeneSymbol()).thenReturn(gene); when(candidate.getCandidateId()).thenReturn("candidate-" + gene);
        when(candidate.getExomiserCandidateFeatures()).thenReturn(f); when(candidate.getObservation()).thenReturn(observation);
        when(observation.getKey()).thenReturn(key); when(candidate.getTranscriptAnnotations()).thenReturn(List.of(annotations)); return candidate;
    }
    ObjectNode catalog(String... classifications) {
        var c = mapper.createObjectNode().put("hgncId", "HGNC:4827");
        c.putObject("relationshipSource").put("sha256", "synthetic-catalog-v1"); var rows = c.putArray("relationships");
        for (int i=0; i<classifications.length; i++) rows.addObject().put("hgncId", "HGNC:4827").put("symbol", "HBB")
                .put("mondoId", String.format("MONDO:%07d", i+1)).put("moi", "AR").put("classification", classifications[i])
                .put("classificationDate", "2026-01-01").put("gcep", "Synthetic expert panel").put("reportLocator", "synthetic/record-"+i);
        return c;
    }
    @ParameterizedTest @ValueSource(strings = {"missense_variant", "synonymous_variant", "intron_variant"})
    void populationPolicyAllowsVerifiedVepContextWithoutAnAmRecord(String consequence) throws Exception {
        var c = catalog("Definitive"); c.put("hgncId", "HGNC:12687");
        var relation = (ObjectNode)c.path("relationships").get(0);
        relation.put("hgncId", "HGNC:12687").put("symbol", "VHL").put("mondoId", "MONDO:0008667").put("moi", "AD");
        var annotation = mapper.createObjectNode().put("geneSymbol", "VHL").put("transcriptAccession", "ENST00000256474.3")
                .put("hgvsCoding", "ENST00000256474.3:c.100+1A>G").put("consequence", consequence)
                .put("source", "VEP").put("version", "116.0").put("status", "COMPLETED")
                .put("timestamp", "2026-09-07T00:00:00Z").put("maneSelect", true);
        var a = mapper.treeToValue(annotation, TranscriptConsequence.class);
        var variant = candidate("VHL", a);
        // Use the existing candidate MOI source contract, independent of the synthetic coordinate.
        var features = variant.getExomiserCandidateFeatures();
        when(features.getRawModeOfInheritance()).thenReturn("AD");
        when(features.getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        when(variant.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        var observed = policy().recommend("analysis", variant, c, "a".repeat(64));
        assertEquals("POPULATION_ONLY", observed.at("/resourceCompatibleTranscript/predictionPath").asText());
        assertEquals("PM2_CONTEXT_TRANSCRIPT_ONLY", observed.at("/resourceCompatibleTranscript/identityReason").asText());
        assertFalse(observed.at("/resourceCompatibleTranscript").has("alphaMissenseUniprotId"));
    }
    String record(String tx, String score) { return "11\t5227002\tT\tA\thg38\tP68871\t"+tx+"\tE7V\t"+score+"\tignored-class"; }
    @Test void synonymousPositionAndTranscriptChangesInvalidateRecommendationIdentity() throws Exception {
        var policy=new AcmgRecommendations(mapper,c->{fail("Synonymous input must not query AM");return null;},clock);
        var ids=new HashSet<String>();
        for(int i=0;i<3;i++) {
            String tx=i==2?"ENST000001.5":"ENST000001.4";
            var node=mapper.createObjectNode().put("geneSymbol","HBB").put("transcriptAccession",tx)
                    .put("hgvsCoding",tx+":c.30T>A").put("consequence","synonymous_variant").put("source","VEP_SYNTHETIC")
                    .put("version","116.0").put("status","COMPLETED").put("timestamp","2026-09-01T00:00:00Z").put("maneSelect",true);
            var plain=mapper.treeToValue(node,TranscriptConsequence.class);
            var missing=policy.recommend("analysis",candidate("HBB",plain),catalog("Definitive"),"source");
            assertEquals("READY_FOR_CONFIRMATION",missing.path("status").asText());
            assertEquals("POPULATION_ONLY", missing.at("/resourceCompatibleTranscript/predictionPath").asText());
            assertFalse(missing.at("/resourceCompatibleTranscript/predictionCompatible").asBoolean());
            assertFalse(missing.at("/resourceCompatibleTranscript/positionReason").isNull());
            int distance=i==1?9:8;
            var proof=new SynonymousExonPosition("GRCh38","11",key.getPosition(),"T","A",tx,"ENSE000001",1,
                    key.getPosition()-1,key.getPosition()+distance,"ENSE000001|1|start|"+(distance+2),"116.0",SynonymousExonPosition.PLUGIN_SHA256);
            var r=policy.recommend("analysis",candidate("HBB",plain.withSynonymousExonPosition(proof)),catalog("Definitive"),"source");
            assertEquals("READY_FOR_CONFIRMATION",r.path("status").asText());
            assertNotEquals(missing.path("recommendationId"),r.path("recommendationId"));
            assertTrue(ids.add(r.path("recommendationId").asText()));
            var again=policy.recommend("analysis",candidate("HBB",plain.withSynonymousExonPosition(proof)),catalog("Definitive"),"source");
            assertEquals(r.path("recommendationId"),again.path("recommendationId"));
        }
    }
    AcmgRecommendations policy(String... rows) {
        return new AcmgRecommendations(mapper, c -> BusinessAlphaMissenseFixture.identityFixture(key, List.of(rows)), clock);
    }
    @ParameterizedTest @CsvSource({"7,SPLICEAI_INTRONIC", "-21,SPLICEAI_INTRONIC", "6,POPULATION_ONLY", "-20,POPULATION_ONLY"})
    void intronicRecommendationsUsePositionAndNeverReadAmIdentity(int offset, String expectedPath) throws Exception {
        var a = mapper.createObjectNode().put("geneSymbol","HBB").put("transcriptAccession","ENST000001.4")
                .put("hgvsCoding","ENST000001.4:c.100"+(offset>0?"+":"")+offset+"A>G")
                .put("consequence","intron_variant").put("source","VEP_SYNTHETIC").put("version","116.0")
                .put("status","COMPLETED").put("timestamp","2026-09-01T00:00:00Z").put("maneSelect",true);
        var policy = new AcmgRecommendations(mapper,c->{fail("Intronic selection must not query AM");return null;},clock);
        var r = policy.recommend("analysis",candidate("HBB",mapper.treeToValue(a,TranscriptConsequence.class)),catalog("Definitive"),"source");
        assertEquals("READY_FOR_CONFIRMATION",r.path("status").asText());
        assertEquals(expectedPath,r.at("/annotations/0/predictionPath").asText());
        assertTrue(r.at("/annotations/0/hgvsp").isNull());
    }
    @Test void unconfiguredGenePolicyDoesNotBlockPopulationContextPreparation() throws Exception {
        var variant = candidate("HBB", annotation("HBB", "ENST00000335295.4", true));
        var recommendation = AcmgRecommendations.unavailable(mapper)
                .recommend("analysis", variant, catalog("Definitive"), "a".repeat(64));
        assertEquals("READY_FOR_CONFIRMATION", recommendation.path("status").asText());
        assertEquals("POPULATION_ONLY", recommendation.at("/resourceCompatibleTranscript/predictionPath").asText());
        assertFalse(recommendation.at("/resourceCompatibleTranscript").has("alphaMissenseUniprotId"));
    }
    @Test void uniqueRelationshipAndResourceCompatibleHbbRemainSeparateFromMane() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000335295.4", true), annotation("HBB", "ENST00000647020.1", false));
        var r = policy(record("ENST00000647020.1", "0.99")).recommend("analysis", c, catalog("Definitive"), "source");
        assertEquals("READY_FOR_CONFIRMATION", r.path("status").asText());
        assertEquals("ENST00000335295.4", r.at("/clinicalPreferredTranscript/versionedTranscriptId").asText());
        assertEquals("ENST00000647020.1", r.at("/resourceCompatibleTranscript/versionedTranscriptId").asText());
        assertFalse(r.path("patientSpecific").asBoolean()); assertTrue(r.path("scoreIndependent").asBoolean());
        assertFalse(r.toString().contains("0.99")); assertFalse(r.toString().contains("ignored-class"));
    }
    @ParameterizedTest @CsvSource({
            "MONDO:0015967,stop_gained,ENST00000257555.11,116.0,PVS1_STOP_GAINED_CONTEXT",
            "MONDO:0010894,stop_gained,ENST00000257555.11,116.0,POPULATION_ONLY",
            "MONDO:0015967,frameshift_variant,ENST00000257555.11,116.0,PVS1_FRAMESHIFT_CONTEXT",
            "MONDO:0015967,frameshift_variant|splice_region_variant,ENST00000257555.11,116.0,PVS1_FRAMESHIFT_CONTEXT",
            "MONDO:0010894,frameshift_variant,ENST00000257555.11,116.0,POPULATION_ONLY",
            "MONDO:0015967,frameshift_variant,ENST00000257555.10,116.0,POPULATION_ONLY",
            "MONDO:0015967,frameshift_variant,ENST00000257555.11,115.0,POPULATION_ONLY",
            "MONDO:0015967,inframe_insertion,ENST00000257555.11,116.0,POPULATION_ONLY" })
    void hnf1aTruncatingContextRequiresExactDiseaseTranscriptAndVepWithoutAm(
            String disease, String consequence, String transcript, String version, String expected) throws Exception {
        boolean stopGained = "stop_gained".equals(consequence);
        var json = mapper.createObjectNode().put("geneSymbol", "HNF1A").put("transcriptAccession", transcript)
                .put("hgvsCoding", transcript + (stopGained ? ":c.511C>T" : ":c.1159dup"))
                .put("hgvsProtein", stopGained ? "ENSP00000257555.5:p.Arg171Ter" : "ENSP00000257555.5:p.Gln388ProfsTer8")
                .put("consequence", consequence).put("source", "VEP").put("version", version).put("status", "COMPLETED")
                .put("timestamp", "2026-09-09T00:00:00Z").put("maneSelect", true);
        var c = candidate("HNF1A", mapper.treeToValue(json, TranscriptConsequence.class));
        when(c.getObservation().getKey()).thenReturn(stopGained
                ? new NormalizedVariantKey("GRCh38", "12", 120989017, "C", "T")
                : new NormalizedVariantKey("GRCh38", "12", 120994310, "G", "GC"));
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        var cat = catalog("Definitive"); cat.put("hgncId", "HGNC:11621");
        ((ObjectNode)cat.at("/relationships/0")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A").put("mondoId", disease).put("moi", "AD");
        var policy = new AcmgRecommendations(mapper, ignored -> { fail("PVS1 must not query AlphaMissense"); return null; }, clock);
        var recommendation = policy.recommend("analysis", c, cat, "source");
        assertEquals("READY_FOR_CONFIRMATION", recommendation.path("status").asText());
        assertEquals(expected, recommendation.at("/annotations/0/predictionPath").asText());
        assertFalse(recommendation.at("/annotations/0/predictionCompatible").asBoolean());
        assertFalse(recommendation.at("/annotations/0/ps1Pm5Compatible").asBoolean());
        assertTrue(recommendation.at("/annotations/0/contextSelectable").asBoolean());
        assertFalse(recommendation.at("/annotations/0").has("alphaMissenseUniprotId"));
        if (expected.startsWith("PVS1_"))
            assertEquals("PVS1_DISEASE_TRANSCRIPT_CONTEXT_ONLY", recommendation.at("/annotations/0/identityReason").asText());
        var choice = selection(recommendation, recommendation.path("stableRelationshipKey").asText(),
                recommendation.path("recommendedAnnotationId").asText());
        assertDoesNotThrow(() -> policy.verify(choice, recommendation, List.of()));
        assertEquals(recommendation.path("recommendationId"), policy.recommend("analysis", c, cat, "source").path("recommendationId"));
        var previousPolicy = new AcmgRecommendations.Selection(choice.candidateId(), choice.recommendationId(),
                "1.5.0-acmg-joint.1", choice.policyHash(), null, choice.stableRelationshipKey(), choice.annotationId());
        assertThrows(IllegalArgumentException.class, () -> policy.verify(previousPolicy, recommendation, List.of()));
    }
    @Test void relationshipOrderIsDeterministicAndNeverUsesArrayIndexForIdentity() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000647020.1", false)); var catalog = catalog("Moderate", "Strong", "Definitive", "Limited", "Refuted", "Disputed", "No Known Disease Relationship");
        var p = policy(record("ENST00000647020.1", "0.1")); var first = p.recommend("analysis", c, catalog, "source");
        var reversed = mapper.createArrayNode(); for (int i=catalog.path("relationships").size()-1;i>=0;i--) reversed.add(catalog.path("relationships").get(i));
        catalog.set("relationships", reversed); var second = p.recommend("analysis", c, catalog, "source");
        assertEquals(first, second); assertEquals("REVIEW_REQUIRED", first.path("relationshipStatus").asText());
        assertEquals(4, first.path("relationships").size()); assertTrue(first.path("stableRelationshipKey").isNull());
        assertEquals("Definitive", first.at("/relationships/0/classification").asText());
        assertFalse(first.at("/relationships/3/selectable").asBoolean());
    }
    @Test void duplicateRelationshipAndDuplicateAnnotationsRequireReview() throws Exception {
        var a = annotation("HBB", "ENST00000647020.1", true); var c = candidate("HBB", a, a); var cat = catalog("Strong");
        ((ArrayNode)cat.path("relationships")).add(cat.path("relationships").get(0).deepCopy());
        var r = policy(record("ENST00000647020.1", "0.5")).recommend("analysis", c, cat, "source");
        assertEquals("REVIEW_REQUIRED", r.path("relationshipStatus").asText()); assertTrue(r.path("annotations").isEmpty());
        assertFalse(r.at("/relationships/0/selectable").asBoolean());
    }
    @Test void predictionScoresAndClassesCannotInfluenceRecommendations() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000647020.1", false));
        var first = policy(record("ENST00000647020.1", "0.001")).recommend("analysis", c, catalog("Strong"), "source");
        for (String suffix : List.of("0.999", "not-a-score", "", "NaN"))
            assertEquals(first, policy(record("ENST00000647020.1", suffix)).recommend("analysis", c, catalog("Strong"), "source"));
    }
    @Test void gjb2CompatibleAndThreeMismatchedGeneIdentitiesStayExplicit() throws Exception {
        var good = candidate("GJB2", annotation("GJB2", "ENST00000382848.5", true));
        assertEquals("RESOURCE_COMPATIBLE_RECOMMENDATION", policy(record("ENST00000382848.5", "ignored")).recommend("a", good, catalog("Strong"), "source").path("transcriptStatus").asText());
        for (var pair : Map.of("KRAS", "ENST00000311936.8", "TP53", "ENST00000269305.9", "BRAF", "ENST00000646891.2").entrySet()) {
            var c = candidate(pair.getKey(), annotation(pair.getKey(), pair.getValue(), true));
            var r = policy(record(pair.getValue().substring(0, pair.getValue().lastIndexOf('.')) + ".1", "ignored")).recommend("a", c, catalog("Strong"), "source");
            assertEquals("NO_COMPATIBLE_TRANSCRIPT", r.path("transcriptStatus").asText()); assertTrue(r.path("recommendedAnnotationId").isNull());
        }
    }
    @Test void multipleCompatibleRecordsNeedReviewUnlessMetadataUniquelyPrefersOne() throws Exception {
        var a = annotation("HBB", "ENST00000000001.1", false); var b = annotation("HBB", "ENST00000000002.1", false);
        var p = policy(record(a.getVersionedTranscriptId(), "0.1"), record(b.getVersionedTranscriptId(), "0.9"));
        assertEquals("REVIEW_REQUIRED", p.recommend("a", candidate("HBB", a, b), catalog("Strong"), "source").path("transcriptStatus").asText());
        assertEquals(a.getVersionedTranscriptId(), p.recommend("a", candidate("HBB", annotation("HBB", a.getVersionedTranscriptId(), true), b), catalog("Strong"), "source").at("/resourceCompatibleTranscript/versionedTranscriptId").asText());
    }
    @Test void generatedAtIsDisplayOnlyWhileChangedSourceAndPolicyRemainStale() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000647020.1", true)); var p = policy(record("ENST00000647020.1", "0.1"));
        var r = p.recommend("a", c, catalog("Strong"), "source");
        for (String generated : Arrays.asList("2026-09-05T10:00:00Z", "invalid", "2026-09-08T10:00:00Z", null))
            assertDoesNotThrow(() -> p.verify(selection(r, generated), r, List.of()));
        assertEquals("CURRENT_SERVER_CONTENT_ONLY", r.path("validity").asText());
        assertEquals("DISPLAY_ONLY", r.path("generatedAtSemantics").asText());
        assertThrows(IllegalArgumentException.class, () -> p.verify(selection(r, null), p.recommend("a", c, catalog("Definitive"), "source"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> p.verify(selection(r, r.path("generatedAt").asText()), p.recommend("a", c, catalog("Strong"), "new-source"), List.of()));
        r.put("policyHash", "forged"); assertThrows(IllegalArgumentException.class, () -> p.verify(selection(r, r.path("generatedAt").asText()), p.recommend("a", c, catalog("Strong"), "source"), List.of()));
    }
    AcmgRecommendations.Selection selection(ObjectNode r, String at) {
        return new AcmgRecommendations.Selection(r.path("candidateId").asText(), r.path("recommendationId").asText(), AcmgRecommendations.VERSION, r.path("policyHash").asText(), at, null, null);
    }
    @Test void ambiguousCatalogMoiOrDifferentGeneCannotBeAutomaticallyFilled() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000647020.1", true)); var p = policy(record("ENST00000647020.1", "ignored"));
        for (var change : List.of(Map.entry("moi", "XL"), Map.entry("moi", "MT"), Map.entry("symbol", "KRAS"))) {
            var catalog = catalog("Definitive"); ((ObjectNode)catalog.path("relationships").get(0)).put(change.getKey(), change.getValue());
            var r = p.recommend("a", c, catalog, "source");
            assertEquals("REVIEW_REQUIRED", r.path("relationshipStatus").asText());
            assertFalse(r.at("/relationships/0/selectable").asBoolean()); assertTrue(r.path("stableRelationshipKey").isNull());
        }
    }
    @ParameterizedTest @CsvSource({
            "AR,AR,AUTOSOMAL_RECESSIVE,AD,CANDIDATE_RELATIONSHIP_MOI_CONFLICT",
            "AD,AD,AUTOSOMAL_DOMINANT,AR,CANDIDATE_RELATIONSHIP_MOI_CONFLICT",
            ",,,AR,CANDIDATE_MOI_REQUIRED", ",AR,AUTOSOMAL_RECESSIVE,AR,CANDIDATE_MOI_REQUIRED",
            "AR,,AUTOSOMAL_RECESSIVE,AR,CANDIDATE_MOI_REQUIRED", "AR,AR,,AR,CANDIDATE_MOI_REQUIRED",
            "XL,AR,AUTOSOMAL_RECESSIVE,AR,CANDIDATE_MOI_UNMAPPABLE",
            "AR,XL,AUTOSOMAL_RECESSIVE,AR,CANDIDATE_MOI_UNMAPPABLE", "AR,AR,UNSUPPORTED,AR,CANDIDATE_MOI_UNMAPPABLE",
            "AD,AR,AUTOSOMAL_RECESSIVE,AR,CANDIDATE_MOI_CONFLICT",
            "AR,AD,AUTOSOMAL_RECESSIVE,AR,CANDIDATE_MOI_CONFLICT", "AR,AR,AUTOSOMAL_DOMINANT,AR,CANDIDATE_MOI_CONFLICT" })
    void candidateMoiSourcesMustAllSupportTheRelationship(String identity, String raw, String normalized, String relationship, String reason) throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000647020.1", true));
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn(identity);
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn(raw);
        when(c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn(normalized);
        var cat = catalog("Definitive"); ((ObjectNode)cat.path("relationships").get(0)).put("moi", relationship);
        var r = policy(record("ENST00000647020.1", "ignored")).recommend("a", c, cat, "source");
        assertEquals("REVIEW_REQUIRED", r.path("relationshipStatus").asText());
        assertEquals("REVIEW_REQUIRED", r.path("status").asText());
        assertFalse(r.at("/relationships/0/selectable").asBoolean()); assertTrue(r.path("stableRelationshipKey").isNull());
        assertEquals(reason, r.at("/relationships/0/reasonCode").asText());
        assertEquals(reason, missingReasons(r).get(0).path("reasonCode").asText());
    }
    @Test void consistentAdMoiAndChangesToCandidateOrAnnotationAreContentBound() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000647020.1", true));
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        when(c.getExomiserCandidateFeatures().getRawModeOfInheritance()).thenReturn("AD");
        when(c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        var cat = catalog("Strong"); ((ObjectNode)cat.path("relationships").get(0)).put("moi", "AD");
        var p = policy(record("ENST00000647020.1", "ignored")); var original = p.recommend("a", c, cat, "source");
        assertEquals("AUTO_RECOMMENDED", original.path("relationshipStatus").asText());
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AR");
        assertThrows(IllegalArgumentException.class, () -> p.verify(selection(original, null), p.recommend("a", c, cat, "source"), List.of()));
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AUTOSOMAL_DOMINANT");
        when(c.getTranscriptAnnotations()).thenReturn(List.of(annotation("HBB", "ENST00000647020.2", true)));
        assertThrows(IllegalArgumentException.class, () -> p.verify(selection(original, null), p.recommend("a", c, cat, "source"), List.of()));
    }
    ArrayNode missingReasons(JsonNode r) { return AcmgRecommendations.unavailable(mapper).missingHypothesisBlockers(r, null); }
    List<String> codes(JsonNode blockers) {
        var values = new ArrayList<String>(); for (var b : blockers) values.add(b.path("reasonCode").asText()); return values;
    }
    @ParameterizedTest @CsvSource({
            "NO_ELIGIBLE_RELATIONSHIP,RESOURCE_COMPATIBLE_RECOMMENDATION,,NO_ELIGIBLE_RELATIONSHIP",
            "REVIEW_REQUIRED,RESOURCE_COMPATIBLE_RECOMMENDATION,,DISEASE_RELATIONSHIP_REVIEW_REQUIRED",
            "AUTO_RECOMMENDED,REVIEW_REQUIRED,,TRANSCRIPT_REVIEW_REQUIRED",
            "AUTO_RECOMMENDED,NO_COMPATIBLE_TRANSCRIPT,,NO_COMPATIBLE_TRANSCRIPT",
            "AUTO_RECOMMENDED,IDENTITY_CHECK_UNAVAILABLE,ALPHAMISSENSE_IDENTITY_QUERY_FAILED,ALPHAMISSENSE_IDENTITY_QUERY_FAILED",
            "AUTO_RECOMMENDED,IDENTITY_CHECK_UNAVAILABLE,,IDENTITY_CHECK_UNAVAILABLE" })
    void missingHypothesisUsesSpecificServerStatus(String relationship, String transcript, String resourceReason, String expected) {
        var current = mapper.createObjectNode().put("status", "REVIEW_REQUIRED").put("relationshipStatus", relationship)
                .put("transcriptStatus", transcript).put("transcriptReasonCode", resourceReason);
        assertEquals(List.of(expected), codes(missingReasons(current)));
    }
    @Test void missingHypothesisReasonsAreOrderedDeduplicatedAndSourceContentBound() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000647020.1", true));
        when(c.getCandidateIdentity().getModeOfInheritance()).thenReturn("AD");
        var p = policy();
        var source = mapper.createArrayNode();
        source.addObject().put("reasonCode", "EXECUTION_SOURCE_UNAVAILABLE").put("field", "executionSource");
        source.add(source.get(0).deepCopy());
        var r = p.recommend("a", c, catalog("Strong"), null, source);
        var blockers = p.missingHypothesisBlockers(r, null);
        assertEquals(List.of("CANDIDATE_MOI_CONFLICT", "NO_COMPATIBLE_TRANSCRIPT", "EXECUTION_SOURCE_UNAVAILABLE"), codes(blockers));
        assertEquals(blockers, p.missingHypothesisBlockers(r, null));
        assertEquals("executionSource", blockers.get(2).path("field").asText());
        ((ObjectNode)source.get(0)).put("reasonCode", "EXECUTION_SNAPSHOT_HASH_MISMATCH");
        var changed = p.recommend("a", c, catalog("Strong"), null, source);
        assertNotEquals(r.path("recommendationId"), changed.path("recommendationId"));
        assertThrows(IllegalArgumentException.class, () -> p.verify(selection(r, null), changed, List.of()));
    }
    @Test void readyOrAbsentRecommendationDoesNotInventMissingInputReason() throws Exception {
        var c = candidate("HBB", annotation("HBB", "ENST00000647020.1", true));
        var p = policy(record("ENST00000647020.1", "ignored"));
        var current = p.recommend("a", c, catalog("Strong"), "source");
        assertEquals("READY_FOR_CONFIRMATION", current.path("status").asText());
        assertTrue(p.missingHypothesisBlockers(current, null).isEmpty());
        assertTrue(p.missingHypothesisBlockers(mapper.createObjectNode(), null).isEmpty());
    }
    AcmgRecommendations.Selection selection(ObjectNode current, String relationshipKey, String annotationId) {
        return new AcmgRecommendations.Selection(current.path("candidateId").asText(), current.path("recommendationId").asText(),
                AcmgRecommendations.VERSION, current.path("policyHash").asText(), null, relationshipKey, annotationId);
    }
    @ParameterizedTest @ValueSource(booleans = { true, false })
    void confirmedCompatibleAnnotationOnlySuppressesTranscriptReview(boolean selected) throws Exception {
        var a = annotation("HBB", "ENST00000000001.1", false); var b = annotation("HBB", "ENST00000000002.1", false);
        var p = policy(record(a.getVersionedTranscriptId(), "ignored"), record(b.getVersionedTranscriptId(), "ignored"));
        var current = p.recommend("a", candidate("HBB", a, b), catalog("Strong", "Strong"), "source");
        assertEquals("REVIEW_REQUIRED", current.path("transcriptStatus").asText());
        var choice = selection(current, null, selected ? current.at("/annotations/0/annotationId").asText() : null);
        assertDoesNotThrow(() -> p.verify(choice, current, List.of()));
        assertEquals(selected ? List.of("DISEASE_RELATIONSHIP_REVIEW_REQUIRED")
                : List.of("DISEASE_RELATIONSHIP_REVIEW_REQUIRED", "TRANSCRIPT_REVIEW_REQUIRED"),
                codes(p.missingHypothesisBlockers(current, choice)));
    }
    @ParameterizedTest @CsvSource({ "relationships,false", "relationships,true", "annotations,false", "annotations,true" })
    void omittedHypothesisCannotConfirmDisabledOrDuplicateSelection(String collection, boolean duplicate) throws Exception {
        var p = policy(record("ENST00000647020.1", "ignored"));
        var current = p.recommend("a", candidate("HBB", annotation("HBB", "ENST00000647020.1", true)), catalog("Strong"), "source");
        String relationshipKey = current.path("stableRelationshipKey").asText();
        String annotationId = current.path("recommendedAnnotationId").asText();
        var rows = (ArrayNode) current.path(collection);
        if (duplicate) rows.add(rows.get(0).deepCopy());
        else ((ObjectNode) rows.get(0)).put(collection.equals("relationships") ? "selectable" : "identityCompatible", false);
        // Bind the selection to this server fixture's new identity so rejection tests the choice, not a stale hash.
        current.remove(List.of("recommendationId", "generatedAt"));
        current.put("recommendationId", "recommendation-" + new AcmgBusinessArtifacts(mapper).requestHash(current));
        var choice = selection(current, relationshipKey, annotationId);
        var error = assertThrows(IllegalArgumentException.class, () -> p.verify(choice, current, List.of()));
        assertEquals("RECOMMENDATION_STALE", error.getMessage());
    }
    @ParameterizedTest @ValueSource(strings = {"stop_gained", "frameshift_variant", "missense_variant"})
    void optionalRuleContextCanPrepareMissingPredictionIdentityWithoutClaimingCompatibility(String consequence) throws Exception {
        var a = annotation("HBB", "ENST00000335295.4", true);
        ObjectNode raw = mapper.valueToTree(a);
        raw.retain("geneSymbol", "transcriptAccession", "hgvsCoding", "hgvsProtein", "proteinPosition", "aminoAcids",
                "consequence", "source", "version", "status", "timestamp", "maneSelect");
        raw.put("consequence", consequence);
        a = mapper.treeToValue(raw, TranscriptConsequence.class);
        var candidate = candidate("HBB", a);
        var capability = new ReviewedRuleContextPreparation(mapper, ReviewedRuleContextPreparationTest.binding(mapper));
        java.util.function.Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> absent = c ->
                new AlphaMissenseProvider.IdentityLookup("UNAVAILABLE", "SYNTHETIC_NO_IDENTITY", null, null, List.of());
        var legacy = new AcmgRecommendations(mapper, absent, clock);
        assertEquals(legacy.recommend("a", candidate, catalog("Strong"), "source"),
                new AcmgRecommendations(mapper, absent, clock, null).recommend("a", candidate, catalog("Strong"), "source"));
        var current = new AcmgRecommendations(mapper, absent, clock, capability);
        var result = current.recommend("a", candidate, catalog("Strong"), "source");
        var selected = result.path("resourceCompatibleTranscript");
        assertEquals("REVIEWED_RULE_CONTEXT", selected.path("predictionPath").asText());
        assertEquals("RULE_CONTEXT_RECOMMENDATION", result.path("transcriptStatus").asText());
        assertTrue(selected.path("ruleContextReady").asBoolean());
        assertFalse(selected.path("identityCompatible").asBoolean());
        assertFalse(selected.path("predictionCompatible").asBoolean());
        assertEquals(capability.selectionPolicy(), selected.path("ruleContextSelectionPolicy").asText());
        assertNotEquals(legacy.recommend("a", candidate, catalog("Strong"), "source").path("recommendationId"), result.path("recommendationId"));
        assertFalse(current.recommend("a", candidate, catalog("Strong"), null).path("annotations").findValuesAsText("predictionPath").contains("REVIEWED_RULE_CONTEXT"));
        var duplicate = candidate("HBB", a, a);
        assertTrue(current.recommend("a", duplicate, catalog("Strong"), "source").path("annotations").isEmpty());
    }

}
