package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.AlphaMissenseProvider;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Synthetic intake envelopes; the opt-in VCEP scope itself comes from the pinned public source. */
class Ps1Pm5ContextBindingTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void explicitBrafRasopathyBindingPreservesVcepSourceWithoutInventingGeneValidity() throws Exception {
        var resolver = resolver(false).withMissenseSpecification(mapper);
        var candidate = candidate();
        var result = binding(resolver, candidate, "HGNC:1097", "MONDO:0021060", "AD");
        assertEquals("READY", result.status());
        assertEquals("VCEP_APPLICABLE", result.clingenClassification());
        assertTrue(result.clingenClassificationDate().startsWith("2024-12-03"));
        assertEquals("CLINGEN_VCEP_SPECIFICATION_SCOPE_GN049",
                result.provenance().clingenResourceIdentity().path("resourceId").asText());
        JsonNode relationship = result.provenance().relationshipRecords().get(0);
        assertEquals("VCEP_SPECIFICATION_SCOPE", relationship.path("relationshipType").asText());
        assertFalse(relationship.path("classificationIsGeneValidity").asBoolean(true));
        assertTrue(relationship.at("/relationshipSource/identityFingerprintSha256").asText().matches("[0-9a-f]{64}"));
        assertEquals("MONDO:0021060", result.provenance().patientHypothesis().mondoId());
        JsonNode saved = mapper.valueToTree(result);
        assertEquals(saved, mapper.valueToTree(mapper.treeToValue(saved, DiseaseContextBinding.class)));
    }

    @Test
    void scopeRequiresExplicitOptInAndNeverSubstitutesForOtherDiseases() throws Exception {
        var base = resolver(true);
        var candidate = candidate();
        assertEquals("CLINGEN_COVERAGE_ABSENT", binding(base, candidate,
                "HGNC:1097", "MONDO:0021060", "AD").reasonCode());
        var expanded = base.withMissenseSpecification(mapper);
        var prior = binding(expanded, candidate, "HGNC:1097", "MONDO:0020297", "AD");
        assertEquals("Definitive", prior.clingenClassification());
        assertEquals("CLINGEN_GENE_DISEASE_VALIDITY", prior.provenance().clingenResourceIdentity().path("resourceId").asText());
        assertEquals("CLINGEN_COVERAGE_ABSENT", binding(expanded, candidate,
                "HGNC:1097", "MONDO:0000001", "AD").reasonCode());
        assertEquals(2, expanded.referenceOptions(mapper, candidate).path("relationships").size());
    }

    @Test
    void wrongGeneMoiAndMissingUserSourceCannotBecomeReady() throws Exception {
        var resolver = resolver(false).withMissenseSpecification(mapper);
        var candidate = candidate();
        assertEquals("HYPOTHESIS_HGNC_CONFLICT", binding(resolver, candidate,
                "HGNC:673", "MONDO:0021060", "AD").reasonCode());
        assertEquals("CANDIDATE_HYPOTHESIS_MOI_CONFLICT", binding(resolver, candidate,
                "HGNC:1097", "MONDO:0021060", "AR").reasonCode());
        var missing = new PatientDiseaseHypothesis(candidate.getCandidateId(), "unconfirmed", "HGNC:1097",
                "MONDO:0021060", "AD", null);
        assertEquals("HYPOTHESIS_PROVENANCE_REQUIRED", resolver.resolve(candidate, List.of(missing)).bindings().get(0).reasonCode());
    }

    @Test
    void malformedOrMissingVcepSourceCannotBecomeSelectable() throws Exception {
        var resolver = resolver(false).withMissenseSpecification(mapper);
        var candidate = candidate();
        var catalog = (ObjectNode) resolver.referenceOptions(mapper, candidate);
        for (String field : List.of("resourceId", "resourceVersion", "sourceUrl", "snapshotSha256", "identityFingerprintSha256")) {
            ObjectNode changed = catalog.deepCopy();
            ObjectNode relation = (ObjectNode) changed.path("relationships").get(0);
            ((ObjectNode) relation.path("relationshipSource")).remove(field);
            assertFalse(PatientDiseaseContextBindingResolver.vcepScope(relation), field);
            assertTrue(recommendations().recommend("analysis", candidate, changed, "source").path("relationships").isEmpty(), field);
        }
        for (String field : List.of("snapshotSha256", "identityFingerprintSha256")) {
            ObjectNode relation = (ObjectNode) catalog.path("relationships").get(0).deepCopy();
            ((ObjectNode) relation.path("relationshipSource")).put(field, "0".repeat(64));
            assertFalse(PatientDiseaseContextBindingResolver.vcepScope(relation), field);
        }
        for (String field : List.of("hgncId", "moi", "mondoId")) {
            ObjectNode relation = (ObjectNode) catalog.path("relationships").get(0).deepCopy();
            relation.put(field, "mismatch");
            assertFalse(PatientDiseaseContextBindingResolver.vcepScope(relation), field);
        }
        ObjectNode mislabeled = (ObjectNode) catalog.path("relationships").get(0).deepCopy();
        mislabeled.put("classificationIsGeneValidity", true);
        assertFalse(PatientDiseaseContextBindingResolver.vcepScope(mislabeled));
    }

    @Test
    void normalRecommendationIsConfirmableButDoesNotCreatePatientHypothesis() throws Exception {
        var resolver = resolver(false).withMissenseSpecification(mapper);
        var candidate = candidate();
        var result = recommendations().recommend("analysis", candidate, resolver.referenceOptions(mapper, candidate), "source");
        assertEquals("READY_FOR_CONFIRMATION", result.path("status").asText());
        assertEquals("VCEP_APPLICABLE", result.at("/relationships/0/classification").asText());
        assertTrue(result.at("/relationships/0/selectable").asBoolean());
        assertFalse(result.path("patientSpecific").asBoolean());
        assertNotNull(result.path("stableRelationshipKey").textValue());
        assertEquals("DISEASE_HYPOTHESIS_REQUIRED", resolver.resolve(candidate, List.of()).blockers().get(0).reasonCode());
        assertNull(binding(resolver, candidate, "HGNC:1097", "MONDO:0021060", "AD").reasonCode());
    }

    @Test
    void existingDiseaseAndVcepScopeRemainSeparateChoices() throws Exception {
        var resolver = resolver(true).withMissenseSpecification(mapper);
        var candidate = candidate();
        var result = recommendations().recommend("analysis", candidate, resolver.referenceOptions(mapper, candidate), "source");
        assertEquals("REVIEW_REQUIRED", result.path("relationshipStatus").asText());
        assertEquals(2, result.path("relationships").size());
        assertTrue(result.path("stableRelationshipKey").isNull());
        assertEquals("MONDO:0020297", result.at("/relationships/0/mondoId").asText());
        assertEquals("MONDO:0021060", result.at("/relationships/1/mondoId").asText());
    }

    @Test
    void amTranscriptMismatchStillAllowsExactVcepSelectionWithoutClaimingAmCompatibility() throws Exception {
        var resolver = resolver(false).withMissenseSpecification(mapper);
        var candidate = fallbackCandidate("GRCh38", "ENST00000646891.2", "ENSP00000493543.1", "116.0");
        var policy = mismatchedAmRecommendations();
        var result = policy.recommend("analysis", candidate, resolver.referenceOptions(mapper, candidate), "source");
        assertEquals("READY_FOR_CONFIRMATION", result.path("status").asText());
        assertFalse(result.at("/annotations/0/predictionCompatible").asBoolean());
        assertEquals("POPULATION_ONLY", result.at("/annotations/0/predictionPath").asText());
        assertFalse(result.at("/annotations/0").has("alphaMissenseUniprotId"));
        assertTrue(result.path("resourceCompatibleTranscript").isNull());
        assertTrue(result.at("/annotations/0/ps1Pm5Compatible").asBoolean());
        assertTrue(result.at("/annotations/0/contextSelectable").asBoolean());
        assertEquals(result.at("/annotations/0/annotationId"), result.path("recommendedAnnotationId"));
        assertEquals("ENST00000646891.2", result.at("/ps1Pm5CompatibleTranscript/versionedTranscriptId").asText());
        var selection = selection(result, result.at("/relationships/0/stableRelationshipKey").asText());
        assertDoesNotThrow(() -> policy.verify(selection, result, List.of()));
        assertTrue(policy.missingHypothesisBlockers(result, selection).isEmpty());
    }

    @Test
    void vcepFallbackCannotBeBorrowedByNoonanChoiceOrCatalogWithoutVcep() throws Exception {
        var candidate = fallbackCandidate("GRCh38", "ENST00000646891.2", "ENSP00000493543.1", "116.0");
        var policy = mismatchedAmRecommendations();
        var base = resolver(true);
        var oldOnly = policy.recommend("analysis", candidate, base.referenceOptions(mapper, candidate), "source");
        assertFalse(oldOnly.at("/annotations/0/ps1Pm5Compatible").asBoolean());
        assertTrue(oldOnly.at("/annotations/0/contextSelectable").asBoolean());
        assertEquals("POPULATION_ONLY", oldOnly.at("/annotations/0/predictionPath").asText());
        assertTrue(oldOnly.path("ps1Pm5CompatibleTranscript").isNull());
        var both = policy.recommend("analysis", candidate,
                base.withMissenseSpecification(mapper).referenceOptions(mapper, candidate), "source");
        assertEquals("MONDO:0020297", both.at("/relationships/0/mondoId").asText());
        assertEquals("MONDO:0021060", both.at("/relationships/1/mondoId").asText());
        assertTrue(both.at("/annotations/0/ps1Pm5Compatible").asBoolean());
        // A Noonan context remains eligible for independent population processing, not PS1/PM5.
        assertFalse(PatientDiseaseContextBindingResolver.vcepScope(both.at("/relationships/0")));
        assertDoesNotThrow(() -> policy.verify(selection(both,
                both.at("/relationships/0/stableRelationshipKey").asText()), both, List.of()));
        assertDoesNotThrow(() -> policy.verify(selection(both,
                both.at("/relationships/1/stableRelationshipKey").asText()), both, List.of()));
    }

    @Test
    void fallbackRejectsWrongTranscriptProteinVepVersionAndAssembly() throws Exception {
        var resolver = resolver(false).withMissenseSpecification(mapper);
        for (String[] values : List.of(
                new String[]{"GRCh38", "ENST00000646891.3", "ENSP00000493543.1", "116.0"},
                new String[]{"GRCh38", "ENST00000646891.2", "ENSP00000493543.2", "116.0"},
                new String[]{"GRCh38", "ENST00000646891.2", "ENSP00000000001.1", "116.0"},
                new String[]{"GRCh38", "ENST00000646891.2", "ENSP00000493543.1", "115.0"},
                new String[]{"GRCh37", "ENST00000646891.2", "ENSP00000493543.1", "116.0"})) {
            var candidate = fallbackCandidate(values[0], values[1], values[2], values[3]);
            var result = mismatchedAmRecommendations().recommend("analysis", candidate,
                    resolver.referenceOptions(mapper, candidate), "source");
            assertFalse(result.at("/annotations/0/ps1Pm5Compatible").asBoolean(), String.join("/", values));
            assertFalse(result.at("/annotations/0/predictionCompatible").asBoolean(), String.join("/", values));
            assertEquals("POPULATION_ONLY", result.at("/annotations/0/predictionPath").asText());
            assertTrue(result.path("ps1Pm5CompatibleTranscript").isNull());
        }
    }

    @Test
    void fallbackRejectsDuplicateAnnotationsDuplicateVcepRecordsAndInvalidVcepSource() throws Exception {
        var resolver = resolver(false).withMissenseSpecification(mapper);
        var candidate = fallbackCandidate("GRCh38", "ENST00000646891.2", "ENSP00000493543.1", "116.0");
        var policy = mismatchedAmRecommendations();
        var catalog = (ObjectNode) resolver.referenceOptions(mapper, candidate);
        ObjectNode duplicated = catalog.deepCopy();
        ArrayNode relations = (ArrayNode) duplicated.path("relationships");
        relations.add(relations.get(0).deepCopy());
        var duplicateResult = policy.recommend("analysis", candidate, duplicated, "source");
        assertFalse(duplicateResult.at("/annotations/0/ps1Pm5Compatible").asBoolean());
        assertFalse(duplicateResult.at("/annotations/0/contextSelectable").asBoolean());
        ObjectNode invalid = catalog.deepCopy();
        ((ObjectNode) invalid.at("/relationships/0/relationshipSource")).put("snapshotSha256", "0".repeat(64));
        var invalidResult = policy.recommend("analysis", candidate, invalid, "source");
        assertFalse(invalidResult.at("/annotations/0/ps1Pm5Compatible").asBoolean());
        assertFalse(invalidResult.at("/annotations/0/contextSelectable").asBoolean());
        var annotation = candidate.getTranscriptAnnotations().get(0);
        candidate.applyTranscriptAnnotations(List.of(annotation, annotation));
        var duplicateAnnotations = policy.recommend("analysis", candidate, catalog, "source");
        assertTrue(duplicateAnnotations.path("annotations").isEmpty());
        assertTrue(duplicateAnnotations.path("ps1Pm5CompatibleTranscript").isNull());
    }

    private AcmgRecommendations.Selection selection(ObjectNode result, String relationshipKey) {
        return new AcmgRecommendations.Selection(result.path("candidateId").asText(),
                result.path("recommendationId").asText(), AcmgRecommendations.VERSION,
                result.path("policyHash").asText(), result.path("generatedAt").asText(),
                relationshipKey, result.path("recommendedAnnotationId").asText());
    }

    private VariantInterpretation fallbackCandidate(String assembly, String transcript, String protein, String vepVersion) throws Exception {
        var original = candidate();
        var key = new NormalizedVariantKey(assembly, "7", 140801537, "T", "A");
        var observation = new VariantQcObservation(key, "synthetic-public-control", "0/1", 30, 99,
                "15,15", null, "PASS", "SNV", null);
        var identity = CandidateIdentity.exomiser(key.canonicalId(), 1, "BRAF", "AD", 1);
        var candidate = new VariantInterpretation(identity, observation, List.of(), List.of(), original.getExomiserCandidateFeatures());
        var annotation = mapper.createObjectNode().put("geneSymbol", "BRAF")
                .put("transcriptAccession", transcript).put("hgvsCoding", transcript + ":c.735A>T")
                .put("hgvsProtein", protein + ":p.Leu245Phe").put("proteinPosition", "245")
                .put("aminoAcids", "L/F").put("consequence", "missense_variant").put("source", "VEP_SYNTHETIC")
                .put("version", vepVersion).put("status", "COMPLETED").put("timestamp", "2026-09-09T00:00:00Z")
                .put("maneSelect", true);
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        return candidate;
    }

    private AcmgRecommendations mismatchedAmRecommendations() {
        return new AcmgRecommendations(mapper, candidate -> new AlphaMissenseProvider.IdentityLookup(
                "AVAILABLE", "SYNTHETIC_IDENTITY_ONLY", "synthetic", "synthetic",
                List.of(new AlphaMissenseProvider.IdentityRecord("ENST00000646891.1", "L245F", "P15056"))),
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));
    }

    private VariantInterpretation candidate() throws Exception {
        var candidate = PatientDiseaseContextBindingResolverTest.candidate(1, "BRAF", "673", "AD", "AD", "AUTOSOMAL_DOMINANT");
        var annotation = mapper.createObjectNode().put("geneSymbol", "BRAF")
                .put("transcriptAccession", "ENST00000646891.2").put("hgvsCoding", "ENST00000646891.2:c.735A>T")
                .put("hgvsProtein", "ENSP00000493543.1:p.Leu245Phe").put("proteinPosition", "245")
                .put("aminoAcids", "L/F").put("consequence", "missense_variant").put("source", "VEP_SYNTHETIC")
                .put("version", "116.0").put("status", "COMPLETED").put("timestamp", "2026-09-09T00:00:00Z")
                .put("maneSelect", true);
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        return candidate;
    }

    private AcmgRecommendations recommendations() {
        return new AcmgRecommendations(mapper, candidate -> new AlphaMissenseProvider.IdentityLookup(
                "AVAILABLE", "SYNTHETIC_IDENTITY_ONLY", "synthetic", "synthetic",
                List.of(new AlphaMissenseProvider.IdentityRecord("ENST00000646891.2", "L245F", "P15056"))),
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));
    }

    private DiseaseContextBinding binding(PatientDiseaseContextBindingResolver resolver, VariantInterpretation candidate,
                                         String hgnc, String mondo, String moi) {
        return resolver.resolve(candidate, List.of(PatientDiseaseContextBindingResolverTest.hypothesis(
                candidate, "explicit-hypothesis", hgnc, mondo, moi))).bindings().get(0);
    }

    private PatientDiseaseContextBindingResolver resolver(boolean oldDisease) throws Exception {
        ArrayNode crosswalk = mapper.createArrayNode();
        crosswalk.addObject().put("ncbiGeneId", "673").put("hgncId", "HGNC:1097").put("symbol", "BRAF")
                .putArray("sourceRows").add(1);
        ArrayNode catalog = mapper.createArrayNode();
        if (oldDisease) catalog.addObject().put("hgncId", "HGNC:1097").put("symbol", "BRAF")
                .put("mondoId", "MONDO:0020297").put("diseaseLabel", "Synthetic pre-existing Noonan catalog relationship")
                .put("moi", "AD").put("classification", "Definitive").put("sourceRow", 1)
                .put("classificationDate", "2024-01-01").put("gcep", "SYNTHETIC_GCEP")
                .put("reportLocator", "https://example.invalid/synthetic/Noonan");
        ObjectNode manifest = mapper.createObjectNode().put("profileId", "PATIENT_DISEASE_BINDING_INTAKE")
                .put("profileVersion", "1.0.0").put("parserVersion", "1.0.0");
        manifest.set("intakePolicy", mapper.readTree("""
                {"policyId":"PATIENT_DISEASE_BINDING_INTAKE","version":"1.0.0",
                 "readyClassifications":["Definitive","Strong","Moderate"],
                 "reviewRequiredClassifications":["Limited"],
                 "notReadyClassifications":["Disputed","Refuted","No Known Disease Relationship",""],
                 "catalogMoiMappings":{"AD":"AUTOSOMAL_DOMINANT","AR":"AUTOSOMAL_RECESSIVE"},
                 "unmappedCatalogMoi":["XL","SD","UD","MT"],
                 "claim":"VARVIS_BINDING_INTAKE_ONLY_NOT_PATIENT_DIAGNOSIS"}
                """));
        ObjectNode resources = manifest.putObject("resources");
        resources.set("mane", resource(crosswalk, "mane.json", true));
        resources.set("clingen", resource(catalog, "clingen.json", false));
        mapper.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
        return PatientDiseaseContextBindingResolver.load(mapper, temporary.resolve("manifest.json"),
                temporary.resolve("mane.json"), temporary.resolve("clingen.json"));
    }

    private ObjectNode resource(ArrayNode rows, String file, boolean mane) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(rows);
        Files.write(temporary.resolve(file), bytes);
        ObjectNode resource = mapper.createObjectNode().put("resourceId", mane ? "MANE_GRCh38_SUMMARY" : "CLINGEN_GENE_DISEASE_VALIDITY")
                .put("resourceVersion", mane ? "1.5" : "synthetic")
                .put("sourceUrl", mane ? "https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/release_1.5/MANE.GRCh38.v1.5.summary.txt.gz"
                        : "https://search.clinicalgenome.org/kb/gene-validity/download")
                .put("retrievedAt", "2026-09-09T00:00:00Z").put("fileDate", "2026-09-09")
                .put("rawSha256", "a".repeat(64)).put("headerSha256", "b".repeat(64))
                .put("derivedSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("derivedRecordCount", rows.size()).put("parserVersion", "1.0.0");
        if (mane) resource.put("assembly", "GRCh38");
        return resource;
    }
}
