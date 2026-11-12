package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.DeNovoSelectedBatch.PublishedBackground;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.CandidateBindingResult;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.HypothesisProvenance;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** All local resource envelopes and patient data in this test are explicitly synthetic. */
class PatientDiseaseContextBindingResolverTest {

    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void pp1UnlistedDiseaseIsOnlyASelectionAndDoesNotChangeOrdinaryBindings() throws Exception {
        var original = resolver(defaultCrosswalk(), mapper.createArrayNode());
        var pp1 = original.withPp1SelectionContexts();
        var candidate = defaultCandidate(); var hypothesis = defaultHypothesis(candidate);
        var binding = pp1.resolve(candidate, List.of(hypothesis)).bindings().get(0);
        assertEquals("DECLARED_SCOPE_ONLY", binding.status());
        assertEquals("CLINGEN_COVERAGE_ABSENT", binding.reasonCode());
        assertNull(binding.clingenClassification());
        assertTrue(binding.provenance().relationshipRecords().isEmpty());
        assertTrue(pp1.acceptsPreparation(binding));
        assertFalse(original.acceptsPreparation(binding));
        assertEquals("NOT_READY", original.resolve(candidate, List.of(hypothesis)).bindings().get(0).status());
    }

    @Test void pp1SelectionCannotMaskRefutedRelationshipsOrInheritanceConflicts() throws Exception {
        var candidate = defaultCandidate();
        for (String classification : List.of("Refuted", "Disputed", "Limited")) {
            var rows = mapper.createArrayNode().add(relationship("HGNC:20", "GENEA", "MONDO:0013212", "AD", classification, 1));
            var pp1 = resolver(defaultCrosswalk(), rows).withPp1SelectionContexts();
            var binding = pp1.resolve(candidate, List.of(defaultHypothesis(candidate))).bindings().get(0);
            assertEquals("NOT_READY", binding.status()); assertFalse(pp1.acceptsPreparation(binding));
        }
        var pp1 = resolver(defaultCrosswalk(), defaultCatalog()).withPp1SelectionContexts();
        var wrongMode = pp1.resolve(candidate, List.of(hypothesis(candidate,"h","HGNC:20","MONDO:0013212","AR"))).bindings().get(0);
        assertEquals("NOT_READY", wrongMode.status()); assertFalse(pp1.acceptsPreparation(wrongMode));
        var wrongGene = pp1.resolve(candidate, List.of(hypothesis(candidate,"h","HGNC:999999","MONDO:9999999","AD"))).bindings().get(0);
        assertEquals("NOT_READY", wrongGene.status()); assertFalse(pp1.acceptsPreparation(wrongGene));
    }

    @Test void explicitXLinkedContextConsumesOriginalBroadRowWithoutChangingIt() throws Exception {
        var fixture = new XLinkedCatalogRecommendationTest();
        var candidate = fixture.candidate("X_LINKED_RECESSIVE");
        org.mockito.Mockito.when(candidate.getExomiserCandidateFeatures().getEntrezGeneId()).thenReturn("16");
        var crosswalk = defaultCrosswalk();
        ((ObjectNode) crosswalk.get(0)).put("symbol", "HBB").put("hgncId", "HGNC:4827");
        var catalog = mapper.createArrayNode().add(relationship("HGNC:4827", "HBB", "MONDO:0013212", "XL", "Definitive", 7));
        var original = catalog.deepCopy();
        var legacy = resolver(crosswalk, catalog); var current = legacy.withLocalInterpretationContexts();
        var h = hypothesis(candidate, "x", "HGNC:4827", "MONDO:0013212", "X_LINKED_RECESSIVE");
        assertEquals("NOT_READY", legacy.resolve(candidate, List.of(h)).bindings().get(0).status());
        var bound = current.resolve(candidate, List.of(h)).bindings().get(0);
        assertEquals("READY", bound.status(), bound.toString());
        assertEquals("X_LINKED_RECESSIVE", bound.normalizedMoi());
        assertEquals(original, current.referenceOptions(mapper, candidate).path("relationships"));
        assertTrue(current.resolve(candidate, List.of()).bindings().isEmpty());
        assertEquals("NOT_READY", current.resolve(candidate, List.of(hypothesis(candidate, "wrong", "HGNC:4827", "MONDO:0013212", "X_LINKED_DOMINANT"))).bindings().get(0).status());
        org.mockito.Mockito.when(candidate.getAnalysisVcfCall()).thenReturn(null);
        assertEquals("INTERPRETATION_ANALYSIS_VCF_CALL_REQUIRED", current.resolve(candidate, List.of(h)).bindings().get(0).reasonCode());
    }

    @Test void expertBatchAddsExplicitScopeWithoutClaimingGeneValidityOrOverridingExistingEvidence() throws Exception {
        var factory = new org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalogTest();
        var newScope = factory.expertRecord("HGNC:20", "MONDO:0999999", "GENEA");
        var oldScope = factory.expertRecord("HGNC:20", "MONDO:0013212", "GENEA");
        var wrongSymbol = factory.expertRecord("HGNC:21022", "MONDO:0999998", "NOT_GENEB");
        byte[] raw = mapper.writeValueAsBytes(factory.bundle(newScope, oldScope, wrongSymbol));
        Path file = temporary.resolve("expert.json"); Files.write(file, raw);
        var knowledge = org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog.load(file,
                org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog.sha(raw));
        var base = resolver(defaultCrosswalk(), defaultCatalog()); var candidate = defaultCandidate();
        var expanded = base.withBs1Knowledge(mapper, knowledge);
        var rows = expanded.referenceOptions(mapper, candidate).path("relationships");
        assertEquals(2, rows.size()); assertEquals("Definitive", rows.get(0).path("classification").asText());
        assertTrue(PatientDiseaseContextBindingResolver.bs1ExpertScope(rows.get(1)));
        assertFalse(PatientDiseaseContextBindingResolver.vcepScope(rows.get(1))); // No PS1/PM5 eligibility expansion.
        assertFalse(rows.get(1).path("classificationIsGeneValidity").asBoolean(true));
        assertTrue(expanded.resolve(candidate, List.of()).bindings().isEmpty());
        var result = expanded.resolve(candidate, List.of(hypothesis(candidate, "new", "HGNC:20", "MONDO:0999999", "AD"))).bindings().get(0);
        assertEquals("READY", result.status()); assertEquals(knowledge.identity(), result.provenance().clingenResourceIdentity());
        assertEquals(2, expanded.withBs1Knowledge(mapper, knowledge).referenceOptions(mapper, candidate).path("relationships").size());
        var wrong = candidate(1, "GENEB", "57505", "AR", "AR", "AUTOSOMAL_RECESSIVE");
        assertEquals(base.referenceOptions(mapper, wrong), expanded.referenceOptions(mapper, wrong));
    }

    @Test void publishedDeNovoScopeFillsOnlyAnUncoveredExactBackground() throws Exception {
        var candidate = defaultCandidate();
        var hypothesis = defaultHypothesis(candidate);
        var scope = new PublishedBackground("HGNC:20", "GENEA", "MONDO:0013212", "Synthetic disease", "AUTOSOMAL_DOMINANT",
                "https://cspec.genome.network/cspec/ui/svi/doc/GN999999?version=1.0", "1.0", "2026-01-01T00:00:00Z",
                "Synthetic VCEP", "a".repeat(64), "b".repeat(64));
        var base = resolver(defaultCrosswalk(), mapper.createArrayNode());
        var binding = base.withDeNovoPublishedBackgrounds(mapper, List.of(scope)).resolve(candidate, List.of(hypothesis)).bindings().get(0);
        assertEquals("READY", binding.status());
        assertEquals("VCEP_APPLICABLE", binding.clingenClassification());
        assertFalse(binding.provenance().relationshipRecords().get(0).path("classificationIsGeneValidity").asBoolean(true));
        assertEquals(scope.releaseSha256(), binding.provenance().clingenResourceIdentity().path("snapshotSha256").asText());
        assertEquals("CLINGEN_COVERAGE_ABSENT", base.resolve(candidate, List.of(hypothesis)).bindings().get(0).reasonCode());

        var disputed = mapper.createArrayNode().add(relationship("HGNC:20", "GENEA", "MONDO:0013212", "AD", "Disputed", 1));
        var contrary = resolver(defaultCrosswalk(), disputed).withDeNovoPublishedBackgrounds(mapper, List.of(scope));
        assertEquals("CLINGEN_CLASSIFICATION_NOT_ACCEPTED", contrary.resolve(candidate, List.of(hypothesis)).bindings().get(0).reasonCode());
    }

    @Test void batchKnowledgeAddsDataDefinedRelationshipWithoutCreatingPatientDiagnosis() throws Exception {
        var existing = resolver(defaultCrosswalk(), defaultCatalog());
        var b = mapper.createObjectNode().put("schemaVersion", "bs1-knowledge-bundle-v1").put("version", "SYNTHETIC");
        b.putArray("sources").addObject().put("sourceId", "synthetic").put("url", "https://example.org/synthetic").put("version", "1").put("sha256", "a".repeat(64));
        b.putArray("records");
        var row = b.putArray("backgrounds").addObject().put("hgncId", "HGNC:20").put("mondoId", "MONDO:0999999").put("moi", "AUTOSOMAL_DOMINANT").put("reasonCode", "BS1_DISEASE_PARAMETERS_MISSING");
        var assertion = relationship("HGNC:20", "GENEA", "MONDO:0999999", "AUTOSOMAL_DOMINANT", "Strong", 1).put("sourceId", "synthetic");
        row.putArray("assertions").add(assertion);
        byte[] bytes = mapper.writeValueAsBytes(b); Path file = temporary.resolve("knowledge.json"); Files.write(file, bytes);
        var catalog = org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog.load(file,
                org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog.sha(bytes));
        var expanded = existing.withBs1Knowledge(mapper, catalog); var candidate = defaultCandidate();
        assertEquals(2, expanded.referenceOptions(mapper, candidate).path("relationships").size());
        assertTrue(expanded.resolve(candidate, List.of()).bindings().isEmpty());
        var bound = expanded.resolve(candidate, List.of(hypothesis(candidate, "new", "HGNC:20", "MONDO:0999999", "AD"))).bindings().get(0);
        assertEquals("READY", bound.status()); assertEquals(catalog.identity(), bound.provenance().clingenResourceIdentity());
        assertEquals("BS1_DISEASE_PARAMETERS_MISSING", catalog.explain("HGNC:20", "MONDO:0999999", "AUTOSOMAL_DOMINANT").path("reasonCode").asText());
        assertEquals(2, expanded.withBs1Knowledge(mapper, catalog).referenceOptions(mapper, candidate).path("relationships").size());
    }

    @Test
    void bindsIndependentGenesWithoutMutatingCandidateRankingIdentityOrLegacyPayload() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation first = candidate(2, "GENEA", "16", "AD", "AD", "AUTOSOMAL_DOMINANT");
        VariantInterpretation second = candidate(1, "GENEB", "GeneID:57505", "AR", "AR", "AUTOSOMAL_RECESSIVE");
        first.applyRanking("TIER_2", 0.2, List.of("synthetic rank A"), List.of());
        second.applyRanking("TIER_1", 0.9, List.of("synthetic rank B"), List.of());
        first.applyAcmgEvidenceSuggestions(List.of(new AcmgEvidenceSuggestion("BA1", "STANDALONE", "SUGGESTED",
                "synthetic-legacy", "Synthetic legacy suggestion A", true, "acmg-ready-v1", List.of())));
        second.applyAcmgEvidenceSuggestions(List.of(new AcmgEvidenceSuggestion("BA1", "STANDALONE", "NOT_SUGGESTED",
                "synthetic-legacy", "Synthetic legacy suggestion B", true, "acmg-ready-v1", List.of())));
        JsonNode before = mapper.valueToTree(List.of(first, second));
        List<CandidateBindingResult> results = resolver.resolveCandidates(List.of(first, second), List.of(
                hypothesis(first, "h-a", "HGNC:20", "MONDO:0013212", "AD"),
                hypothesis(second, "h-b", "HGNC:21022", "MONDO:0044970", "AUTOSOMAL_RECESSIVE")));
        assertEquals(List.of(first.getCandidateId(), second.getCandidateId()),
                results.stream().map((CandidateBindingResult value) -> value.candidateId()).toList());
        for (CandidateBindingResult result : results) {
            DiseaseContextBinding binding = result.bindings().get(0);
            assertEquals("READY", binding.status());
            assertNull(binding.reasonCode());
            assertEquals("SYNTHETIC_PATIENT_HYPOTHESIS", binding.provenance().patientHypothesis().provenance().source());
            assertEquals("MANE_GRCh38_SUMMARY", binding.provenance().maneResourceIdentity().path("resourceId").asText());
            assertEquals(1, binding.provenance().relationshipRecords().size());
            assertNotNull(binding.clingenReportLocator());
        }
        assertEquals(before, mapper.valueToTree(List.of(first, second)));
        assertEquals(mapper.valueToTree(results), mapper.valueToTree(resolver.resolveCandidates(
                List.of(first, second), List.of(hypothesis(first, "h-a", "HGNC:20", "MONDO:0013212", "AD"),
                        hypothesis(second, "h-b", "HGNC:21022", "MONDO:0044970", "AUTOSOMAL_RECESSIVE")))));
        first.applyRanking("TIER_1", 0.99, List.of("changed synthetic rank"), List.of());
        first.applyAcmgEvidenceSuggestions(List.of(new AcmgEvidenceSuggestion("BA1", "STANDALONE", "NOT_SUGGESTED",
                "synthetic-legacy", "Changed synthetic legacy suggestion", true, "acmg-ready-v1", List.of())));
        JsonNode changedBefore = mapper.valueToTree(List.of(first, second));
        assertEquals(mapper.valueToTree(results), mapper.valueToTree(resolver.resolveCandidates(
                List.of(first, second), List.of(hypothesis(first, "h-a", "HGNC:20", "MONDO:0013212", "AD"),
                        hypothesis(second, "h-b", "HGNC:21022", "MONDO:0044970", "AUTOSOMAL_RECESSIVE")))));
        assertEquals(changedBefore, mapper.valueToTree(List.of(first, second)));
    }

    @Test
    void onlyExplicitHypothesesExpandAndTwoExplicitHypothesesRemainDeterministic() throws Exception {
        ArrayNode catalog = defaultCatalog();
        catalog.add(relationship("HGNC:20", "GENEA", "MONDO:0000002", "AD", "Moderate", 10));
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), catalog);
        VariantInterpretation candidate = defaultCandidate();
        PatientDiseaseHypothesis first = defaultHypothesis(candidate);
        PatientDiseaseHypothesis second = hypothesis(candidate, "h-second", "HGNC:20", "MONDO:0000002", "AD");
        assertEquals(1, resolver.resolve(candidate, List.of(first)).bindings().size());
        CandidateBindingResult two = resolver.resolve(candidate, List.of(first, second));
        assertEquals(List.of("MONDO:0013212", "MONDO:0000002"),
                two.bindings().stream().map((DiseaseContextBinding value) -> value.mondoId()).toList());
        assertTrue(two.bindings().stream().allMatch(binding -> "READY".equals(binding.status())));
        assertEquals(mapper.valueToTree(two), mapper.valueToTree(resolver.resolve(candidate, List.of(first, second))));
    }

    @Test
    void catalogAloneNeverCreatesPatientHypothesis() throws Exception {
        CandidateBindingResult result = resolver(defaultCrosswalk(), defaultCatalog()).resolve(defaultCandidate(), List.of());
        assertTrue(result.bindings().isEmpty());
        assertEquals("DISEASE_HYPOTHESIS_REQUIRED", result.blockers().get(0).reasonCode());
        assertEquals("NOT_READY", result.blockers().get(0).status());
    }

    @Test
    void missingDuplicateAndMismatchedCandidateIdsBlock() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation candidate = defaultCandidate();
        PatientDiseaseHypothesis hypothesis = defaultHypothesis(candidate);
        assertEquals("CANDIDATE_ID_REQUIRED", resolver.resolve(null, List.of(hypothesis)).blockers().get(0).reasonCode());
        assertThrows(IllegalArgumentException.class, () -> new CandidateIdentity(null, " ", "GRCh38:1:1:A:G",
                "EXOMISER", 1, "GENEA", "AD", 1));
        for (CandidateBindingResult result : resolver.resolveCandidates(List.of(candidate, candidate), List.of(hypothesis))) {
            assertTrue(result.bindings().isEmpty());
            assertEquals("CANDIDATE_ID_AMBIGUOUS", result.blockers().get(0).reasonCode());
        }
        PatientDiseaseHypothesis wrong = new PatientDiseaseHypothesis("other", "h1", "HGNC:20", "MONDO:0013212", "AD", source());
        assertReason(resolver, candidate, wrong, "CANDIDATE_ID_MISMATCH");
        List<CandidateBindingResult> batch = resolver.resolveCandidates(List.of(candidate), List.of(wrong));
        assertEquals(2, batch.size());
        assertEquals("CANDIDATE_ID_MISMATCH", batch.get(1).blockers().get(0).reasonCode());
        PatientDiseaseHypothesis otherDisease = new PatientDiseaseHypothesis("other", "h2", "HGNC:20", "MONDO:0000002", "AD", source());
        List<CandidateBindingResult> unknownTargets = resolver.resolveCandidates(List.of(candidate), List.of(wrong, otherDisease));
        assertEquals(wrong, unknownTargets.get(1).blockers().get(0).hypotheses().get(0));
        assertEquals(otherDisease, unknownTargets.get(2).blockers().get(0).hypotheses().get(0));
        assertReason(resolver, candidate, new PatientDiseaseHypothesis(null, "h1", "HGNC:20", "MONDO:0013212", "AD", source()),
                "HYPOTHESIS_CANDIDATE_ID_REQUIRED");
    }

    @Test
    void hypothesisIdentityAndProvenanceMustBeExplicitAndUnambiguous() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation candidate = defaultCandidate();
        PatientDiseaseHypothesis hypothesis = defaultHypothesis(candidate);
        assertReason(resolver, candidate, new PatientDiseaseHypothesis(candidate.getCandidateId(), null, "HGNC:20", "MONDO:0013212", "AD", source()),
                "HYPOTHESIS_ID_REQUIRED");
        assertTrue(resolver.resolve(candidate, List.of(hypothesis, hypothesis)).bindings().stream()
                .allMatch(binding -> "HYPOTHESIS_ID_AMBIGUOUS".equals(binding.reasonCode())));
        assertReason(resolver, candidate, new PatientDiseaseHypothesis(candidate.getCandidateId(), "h1", "HGNC:20", "MONDO:0013212", "AD", null),
                "HYPOTHESIS_PROVENANCE_REQUIRED");
        assertReason(resolver, candidate, new PatientDiseaseHypothesis(candidate.getCandidateId(), "h1", "HGNC:20", "MONDO:0013212", "AD",
                new HypothesisProvenance("synthetic", "", "record")), "HYPOTHESIS_PROVENANCE_REQUIRED");
        for (String locator : List.of("D:\\patient\\record.json", "\\\\host\\patient\\record", "file:///patient/record")) {
            assertReason(resolver, candidate, new PatientDiseaseHypothesis(candidate.getCandidateId(), "h1", "HGNC:20", "MONDO:0013212", "AD",
                    new HypothesisProvenance("synthetic", "v1", locator)), "HYPOTHESIS_PROVENANCE_LOCAL_PATH");
        }
    }

    @Test
    void geneIdsAndSymbolsAreExactAndCannotBeGuessed() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation candidate = defaultCandidate();
        for (String invalid : new String[] {null, "", "HGNC:20", "NCBIGene:16", "GeneID:016", "16.0"}) {
            VariantInterpretation missing = candidate(1, "GENEA", invalid, "AD", "AD", "AUTOSOMAL_DOMINANT");
            assertReason(resolver, missing, defaultHypothesis(missing), "CANDIDATE_NCBI_GENE_REQUIRED");
        }
        VariantInterpretation missingSymbol = candidate(1, null, "16", "AD", "AD", "AUTOSOMAL_DOMINANT");
        assertReason(resolver, missingSymbol, defaultHypothesis(missingSymbol), "CANDIDATE_SYMBOL_REQUIRED");
        VariantInterpretation lower = candidate(1, "genea", "16", "AD", "AD", "AUTOSOMAL_DOMINANT");
        assertReason(resolver, lower, defaultHypothesis(lower), "MANE_GENE_IDENTITY_CONFLICT");
        assertEquals("EXOMISER_FEATURE_GENE_SYMBOL_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> new VariantInterpretation(CandidateIdentity.exomiser(candidate.getCanonicalVariantId(),
                        1, "OTHER", "AD", 1), null, List.of(), List.of(), candidate.getExomiserCandidateFeatures())).getMessage());
        for (String invalid : new String[] {null, "", "20", "GeneID:16"}) {
            assertReason(resolver, candidate, hypothesis(candidate, "h1", invalid, "MONDO:0013212", "AD"), "HYPOTHESIS_HGNC_REQUIRED");
        }
        assertReason(resolver, candidate, hypothesis(candidate, "h1", "HGNC:21", "MONDO:0013212", "AD"), "HYPOTHESIS_HGNC_CONFLICT");
        assertReason(resolver(mapper.createArrayNode(), defaultCatalog()), candidate, defaultHypothesis(candidate), "MANE_CROSSWALK_NO_RECORD");
    }

    @Test
    void posixAbsoluteHypothesisProvenanceIsRejectedAndRedactedInEveryField() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation candidate = defaultCandidate();
        String localPath = "/var/synthetic/hypotheses.json";
        for (int field = 0; field < 3; field++) {
            String[] values = {"synthetic", "v1", "synthetic-patient/hypotheses"};
            values[field] = localPath;
            PatientDiseaseHypothesis hypothesis = new PatientDiseaseHypothesis(candidate.getCandidateId(),
                    "h1", "HGNC:20", "MONDO:0013212", "AD",
                    new HypothesisProvenance(values[0], values[1], values[2]));
            CandidateBindingResult result = resolver.resolve(candidate, List.of(hypothesis));
            DiseaseContextBinding binding = result.bindings().get(0);
            assertEquals("NOT_READY", binding.status());
            assertEquals("HYPOTHESIS_PROVENANCE_LOCAL_PATH", binding.reasonCode());
            values[field] = "[LOCAL_PATH_REJECTED]";
            assertEquals(new HypothesisProvenance(values[0], values[1], values[2]),
                    binding.provenance().patientHypothesis().provenance());
            assertFalse(mapper.writeValueAsString(result).contains(localPath));
            assertFalse(mapper.writeValueAsString(resolver.resolve(null, List.of(hypothesis))).contains(localPath));
        }
    }

    @Test
    void relativeAndHttpsHypothesisProvenanceRemainReadyAndUnchanged() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation candidate = defaultCandidate();
        for (String locator : List.of("synthetic-patient/hypotheses", "https://example.invalid/synthetic/hypotheses")) {
            PatientDiseaseHypothesis hypothesis = new PatientDiseaseHypothesis(candidate.getCandidateId(),
                    "h1", "HGNC:20", "MONDO:0013212", "AD",
                    new HypothesisProvenance(locator, locator, locator));
            DiseaseContextBinding binding = resolver.resolve(candidate, List.of(hypothesis)).bindings().get(0);
            assertEquals("READY", binding.status());
            assertEquals(hypothesis, binding.provenance().patientHypothesis());
        }
    }

    @Test
    void crosswalkAmbiguityAndIdentityConflictDoNotPickFirstRow() throws Exception {
        VariantInterpretation candidate = defaultCandidate();
        ArrayNode ambiguous = defaultCrosswalk();
        ambiguous.add(crosswalk("16", "HGNC:21", "GENEA", 11));
        assertReason(resolver(ambiguous, defaultCatalog()), candidate, defaultHypothesis(candidate), "MANE_HGNC_AMBIGUOUS");
        ArrayNode conflict = defaultCrosswalk();
        conflict.add(crosswalk("17", "HGNC:20", "OTHER", 12));
        assertReason(resolver(conflict, defaultCatalog()), candidate, defaultHypothesis(candidate), "MANE_GENE_IDENTITY_CONFLICT");
        ArrayNode ncbiConflict = defaultCrosswalk();
        ncbiConflict.add(crosswalk("16", "HGNC:21", "OTHER", 13));
        assertReason(resolver(ncbiConflict, defaultCatalog()), candidate, defaultHypothesis(candidate), "MANE_GENE_IDENTITY_CONFLICT");
    }

    @Test
    void omimOrOrphaNeverStandInForMondoAndNoRecordIsCoverageAbsent() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation candidate = defaultCandidate();
        for (String invalid : new String[] {null, "", "OMIM:123456", "ORPHA:123", "MONDO:123"}) {
            assertReason(resolver, candidate, hypothesis(candidate, "h1", "HGNC:20", invalid, "AD"), "EXACT_MONDO_REQUIRED");
        }
        DiseaseContextBinding absent = assertReason(resolver, candidate,
                hypothesis(candidate, "h1", "HGNC:20", "MONDO:9999999", "AD"), "CLINGEN_COVERAGE_ABSENT");
        assertTrue(absent.provenance().relationshipRecords().isEmpty());
    }

    @Test
    void allCandidateMoiFieldsMustAgreeLosslessly() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation candidate = defaultCandidate();
        assertReason(resolver, candidate, hypothesis(candidate, "h1", "HGNC:20", "MONDO:0013212", "AR"), "CANDIDATE_HYPOTHESIS_MOI_CONFLICT");
        assertEquals("EXOMISER_FEATURE_MOI_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> candidate(1, "GENEA", "16", "AR", "AD", "AUTOSOMAL_DOMINANT")).getMessage());
        VariantInterpretation rawUnknown = candidate(1, "GENEA", "16", "XL", "XL", null);
        assertReason(resolver, rawUnknown, defaultHypothesis(rawUnknown), "CANDIDATE_MOI_UNMAPPABLE");
        VariantInterpretation missing = candidate(1, "GENEA", "16", null, null, null);
        assertReason(resolver, missing, defaultHypothesis(missing), "CANDIDATE_MOI_REQUIRED");
        for (String unsupported : new String[] {null, "XL", "SD", "UD", "MT", "ad"}) {
            assertReason(resolver, candidate, hypothesis(candidate, "h1", "HGNC:20", "MONDO:0013212", unsupported), "HYPOTHESIS_MOI_UNMAPPABLE");
        }
    }

    @Test
    void catalogMoiNeverExpandsOrGuessesXLinkedOrSemidominance() throws Exception {
        VariantInterpretation candidate = defaultCandidate();
        for (String moi : List.of("XL", "SD", "UD", "MT", "", "XD")) {
            ArrayNode catalog = mapper.createArrayNode().add(relationship("HGNC:20", "GENEA", "MONDO:0013212", moi, "Definitive", 7));
            DiseaseContextBinding blocked = assertReason(resolver(defaultCrosswalk(), catalog), candidate, defaultHypothesis(candidate), "CATALOG_MOI_UNMAPPABLE");
            assertEquals(moi, blocked.provenance().relationshipRecords().get(0).path("moi").asText());
        }
        ArrayNode different = mapper.createArrayNode().add(relationship("HGNC:20", "GENEA", "MONDO:0013212", "AR", "Definitive", 7));
        assertReason(resolver(defaultCrosswalk(), different), candidate, defaultHypothesis(candidate), "CATALOG_MOI_CONFLICT");
        VariantInterpretation xd = candidate(1, "GENEA", "16", "XD", "XD", "X_DOMINANT");
        ArrayNode generic = mapper.createArrayNode().add(relationship("HGNC:20", "GENEA", "MONDO:0013212", "XL", "Definitive", 7));
        assertReason(resolver(defaultCrosswalk(), generic), xd,
                hypothesis(xd, "h1", "HGNC:20", "MONDO:0013212", "X_LINKED_DOMINANT"), "CATALOG_MOI_UNMAPPABLE");
    }

    @Test
    void intakePolicyKeepsClinGenClassificationSeparateFromPatientDiagnosis() throws Exception {
        VariantInterpretation candidate = defaultCandidate();
        for (String classification : List.of("Definitive", "Strong", "Moderate", "Limited", "Disputed", "Refuted", "No Known Disease Relationship", "")) {
            ArrayNode catalog = mapper.createArrayNode().add(relationship("HGNC:20", "GENEA", "MONDO:0013212", "AD", classification, 7));
            DiseaseContextBinding binding = resolver(defaultCrosswalk(), catalog).resolve(candidate, List.of(defaultHypothesis(candidate))).bindings().get(0);
            assertEquals(classification, binding.clingenClassification());
            assertEquals(classification, binding.provenance().relationshipRecords().get(0).path("classification").asText());
            if (List.of("Definitive", "Strong", "Moderate").contains(classification)) {
                assertEquals("READY", binding.status());
            } else {
                assertEquals("NOT_READY", binding.status());
                assertEquals("Limited".equals(classification) ? "REVIEW_REQUIRED" : "CLINGEN_CLASSIFICATION_NOT_ACCEPTED", binding.reasonCode());
            }
        }
    }

    @Test
    void duplicateConflictAndCatalogSymbolConflictAreStructuredAndKeepAllSourceRows() throws Exception {
        VariantInterpretation candidate = defaultCandidate();
        ArrayNode duplicate = defaultCatalog();
        duplicate.add(relationship("HGNC:20", "GENEA", "MONDO:0013212", "AD", "Definitive", 13));
        DiseaseContextBinding duplicated = assertReason(resolver(defaultCrosswalk(), duplicate), candidate, defaultHypothesis(candidate), "CLINGEN_RELATIONSHIP_DUPLICATE");
        assertEquals(2, duplicated.provenance().relationshipRecords().size());
        assertNull(duplicated.clingenClassification());
        ArrayNode conflict = defaultCatalog();
        conflict.add(relationship("HGNC:20", "GENEA", "MONDO:0013212", "AD", "Disputed", 13));
        assertReason(resolver(defaultCrosswalk(), conflict), candidate, defaultHypothesis(candidate), "CLINGEN_RELATIONSHIP_CONFLICT");
        ArrayNode symbols = mapper.createArrayNode().add(relationship("HGNC:20", "OTHER", "MONDO:0013212", "AD", "Definitive", 13));
        assertReason(resolver(defaultCrosswalk(), symbols), candidate, defaultHypothesis(candidate), "CLINGEN_GENE_IDENTITY_CONFLICT");
    }

    @Test
    void missingRelationshipProvenanceCannotProduceReadyBinding() throws Exception {
        ArrayNode catalog = defaultCatalog();
        ((ObjectNode) catalog.get(0)).put("reportLocator", "");
        VariantInterpretation candidate = defaultCandidate();
        assertReason(resolver(defaultCrosswalk(), catalog), candidate, defaultHypothesis(candidate), "CLINGEN_RELATIONSHIP_PROVENANCE_REQUIRED");
    }

    @Test
    void verifiesDerivedHashCountAndPinnedResourceIdentityBeforeLoading() throws Exception {
        writeResources(defaultCrosswalk(), defaultCatalog());
        Files.writeString(temporary.resolve("mane-crosswalk.json"), "[]");
        assertEquals("BINDING_RESOURCE_HASH_MISMATCH", assertThrows(IllegalArgumentException.class, this::load).getMessage());
        writeResources(defaultCrosswalk(), defaultCatalog());
        ObjectNode manifest = (ObjectNode) mapper.readTree(temporary.resolve("manifest.json").toFile());
        ((ObjectNode) manifest.path("resources").path("mane")).put("derivedRecordCount", 100);
        mapper.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
        assertEquals("BINDING_RESOURCE_COUNT_MISMATCH", assertThrows(IllegalArgumentException.class, this::load).getMessage());
        ((ObjectNode) manifest.path("resources").path("mane")).put("resourceVersion", "1.4");
        mapper.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
        assertEquals("MANE_RESOURCE_VERSION_INVALID", assertThrows(IllegalArgumentException.class, this::load).getMessage());
        writeResources(defaultCrosswalk(), defaultCatalog());
        manifest = (ObjectNode) mapper.readTree(temporary.resolve("manifest.json").toFile());
        ((ObjectNode) manifest.path("resources").path("clingen")).remove("rawSha256");
        mapper.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
        assertTrue(assertThrows(IllegalArgumentException.class, this::load).getMessage().startsWith("BINDING_RESOURCE_IDENTITY_REQUIRED"));
        writeResources(defaultCrosswalk(), defaultCatalog());
        manifest = (ObjectNode) mapper.readTree(temporary.resolve("manifest.json").toFile());
        ((ObjectNode) manifest.path("resources").path("clingen")).put("sourceUrl", "https://example.invalid/replacement");
        mapper.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
        assertEquals("BINDING_RESOURCE_SOURCE_IDENTITY_INVALID", assertThrows(IllegalArgumentException.class, this::load).getMessage());
        writeResources(defaultCrosswalk(), defaultCatalog());
        manifest = (ObjectNode) mapper.readTree(temporary.resolve("manifest.json").toFile());
        ((ObjectNode) manifest.path("intakePolicy").path("catalogMoiMappings")).put("XL", "X_LINKED_DOMINANT");
        mapper.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
        assertEquals("BINDING_RESOURCE_INTAKE_POLICY_INVALID", assertThrows(IllegalArgumentException.class, this::load).getMessage());
    }

    @Test
    void returnedJsonProvenanceCannotMutateResolverSnapshot() throws Exception {
        PatientDiseaseContextBindingResolver resolver = resolver(defaultCrosswalk(), defaultCatalog());
        VariantInterpretation candidate = defaultCandidate();
        DiseaseContextBinding first = resolver.resolve(candidate, List.of(defaultHypothesis(candidate))).bindings().get(0);
        ((ObjectNode) first.provenance().maneResourceIdentity()).put("resourceId", "modified");
        ((ObjectNode) first.provenance().relationshipRecords().get(0)).put("classification", "Refuted");
        DiseaseContextBinding again = resolver.resolve(candidate, List.of(defaultHypothesis(candidate))).bindings().get(0);
        assertEquals("READY", again.status());
        assertEquals("MANE_GRCh38_SUMMARY", again.provenance().maneResourceIdentity().path("resourceId").asText());
        assertFalse(again.provenance().relationshipRecords().isEmpty());
    }

    @Test
    void resourceLoadRequiresValidUtcRetrievalTimestamps() throws Exception {
        writeResources(defaultCrosswalk(), defaultCatalog());
        ObjectNode original = (ObjectNode) mapper.readTree(temporary.resolve("manifest.json").toFile());
        for (String resourceName : List.of("mane", "clingen")) {
            for (String invalid : List.of("2026-09-05", "2026-09-05T08:00:00",
                    "2026-09-05T08:00:00+08:00", "2026-09-05T08:00:00-01:00",
                    "2026-02-30T08:00:00Z", "2026-09-05T24:00:00Z",
                    "2026-09-05 08:00:00Z", "not-a-dateZ", "0000-01-01T00:00:00Z",
                    "2026-09-05T08:00:00.1234567890Z")) {
                ObjectNode changed = original.deepCopy();
                ((ObjectNode) changed.path("resources").path(resourceName)).put("retrievedAt", invalid);
                mapper.writeValue(temporary.resolve("manifest.json").toFile(), changed);
                assertEquals("BINDING_RESOURCE_RETRIEVAL_UTC_REQUIRED",
                        assertThrows(IllegalArgumentException.class, this::load).getMessage());
            }
        }
        for (String valid : List.of("2026-09-05T08:00:00Z", "2026-09-05T08:00:00+00:00",
                "2026-09-05T08:00:00.123456789Z")) {
            ObjectNode changed = original.deepCopy();
            for (String resourceName : List.of("mane", "clingen")) {
                ((ObjectNode) changed.path("resources").path(resourceName)).put("retrievedAt", valid);
            }
            mapper.writeValue(temporary.resolve("manifest.json").toFile(), changed);
            VariantInterpretation candidate = defaultCandidate();
            DiseaseContextBinding binding = load().resolve(candidate, List.of(defaultHypothesis(candidate))).bindings().get(0);
            assertEquals("READY", binding.status());
            assertEquals(valid, binding.provenance().maneResourceIdentity().path("retrievedAt").asText());
            assertEquals(valid, binding.provenance().clingenResourceIdentity().path("retrievedAt").asText());
        }
    }

    private DiseaseContextBinding assertReason(PatientDiseaseContextBindingResolver resolver,
                                               VariantInterpretation candidate, PatientDiseaseHypothesis hypothesis,
                                               String reason) {
        DiseaseContextBinding binding = resolver.resolve(candidate, List.of(hypothesis)).bindings().get(0);
        assertEquals("NOT_READY", binding.status());
        assertEquals(reason, binding.reasonCode());
        assertNotNull(binding.provenance().maneResourceIdentity());
        if ("HYPOTHESIS_PROVENANCE_LOCAL_PATH".equals(reason)) {
            assertEquals("[LOCAL_PATH_REJECTED]", binding.provenance().patientHypothesis().provenance().recordLocator());
        } else {
            assertEquals(hypothesis, binding.provenance().patientHypothesis());
        }
        return binding;
    }

    private PatientDiseaseContextBindingResolver resolver(ArrayNode crosswalk, ArrayNode catalog) throws Exception {
        writeResources(crosswalk, catalog);
        return load();
    }

    private PatientDiseaseContextBindingResolver load() throws Exception {
        return PatientDiseaseContextBindingResolver.load(mapper, temporary.resolve("manifest.json"),
                temporary.resolve("mane-crosswalk.json"), temporary.resolve("clingen-catalog.json"));
    }

    private void writeResources(ArrayNode crosswalk, ArrayNode catalog) throws Exception {
        ObjectNode manifest = mapper.createObjectNode().put("profileId", "PATIENT_DISEASE_BINDING_INTAKE")
                .put("profileVersion", "1.0.0").put("parserVersion", "1.0.0")
                .put("testDataClass", "SYNTHETIC_RESOURCE_ENVELOPE_NOT_DOWNLOADED");
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
        resources.set("mane", resource("MANE_GRCh38_SUMMARY", "1.5", "GRCh38", crosswalk, "mane-crosswalk.json"));
        resources.set("clingen", resource("CLINGEN_GENE_DISEASE_VALIDITY", "synthetic-2026-09-05", null, catalog, "clingen-catalog.json"));
        mapper.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
    }

    private ObjectNode resource(String id, String version, String assembly, ArrayNode rows, String filename) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(rows);
        Files.write(temporary.resolve(filename), bytes);
        ObjectNode resource = mapper.createObjectNode().put("resourceId", id).put("resourceVersion", version)
                .put("sourceUrl", id.equals("MANE_GRCh38_SUMMARY")
                        ? "https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/release_1.5/MANE.GRCh38.v1.5.summary.txt.gz"
                        : "https://search.clinicalgenome.org/kb/gene-validity/download")
                .put("retrievedAt", "2026-09-05T00:00:00Z")
                .put("fileDate", "2026-09-05").put("bytes", bytes.length).put("rawSha256", "a".repeat(64))
                .put("headerSha256", "b".repeat(64)).put("parserVersion", "1.0.0").put("sourceCount", rows.size())
                .put("acceptedCount", rows.size()).put("rejectedCount", 0).put("filterPolicy", "SYNTHETIC_TEST_ONLY")
                .put("derivedSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("derivedRecordCount", rows.size()).put("rawRelativePath", "synthetic-raw").put("derivedRelativePath", filename);
        resource.putArray("header").add("SYNTHETIC");
        if (assembly != null) { resource.put("assembly", assembly); }
        return resource;
    }

    private ArrayNode defaultCrosswalk() {
        return mapper.createArrayNode().add(crosswalk("16", "HGNC:20", "GENEA", 2))
                .add(crosswalk("57505", "HGNC:21022", "GENEB", 3));
    }

    private ObjectNode crosswalk(String ncbi, String hgnc, String symbol, int row) {
        ObjectNode result = mapper.createObjectNode().put("ncbiGeneId", ncbi).put("hgncId", hgnc).put("symbol", symbol);
        result.putArray("sourceRows").add(row);
        return result;
    }

    private ArrayNode defaultCatalog() {
        return mapper.createArrayNode().add(relationship("HGNC:20", "GENEA", "MONDO:0013212", "AD", "Definitive", 7))
                .add(relationship("HGNC:21022", "GENEB", "MONDO:0044970", "AR", "Strong", 8));
    }

    private ObjectNode relationship(String hgnc, String symbol, String mondo, String moi, String classification, int row) {
        return mapper.createObjectNode().put("hgncId", hgnc).put("symbol", symbol).put("mondoId", mondo)
                .put("diseaseLabel", "Synthetic disease " + mondo).put("moi", moi).put("classification", classification)
                .put("classificationDate", "2026-09-05").put("gcep", "SYNTHETIC_GCEP")
                .put("reportLocator", "https://example.invalid/synthetic-report/" + row).put("sourceRow", row).put("sop", "synthetic");
    }

    static VariantInterpretation candidate(int ordinal, String symbol, String ncbi, String identityMoi,
                                           String rawMoi, String normalizedMoi) {
        CandidateIdentity identity = CandidateIdentity.exomiser("GRCh38:1:100:A:G", ordinal, symbol, identityMoi, ordinal);
        ExomiserCandidateFeatures features = new ExomiserCandidateFeatures(
                ExomiserCandidateFeatures.CURRENT_SCHEMA_VERSION, "15.1.0", symbol, ncbi, rawMoi, normalizedMoi,
                ordinal, ExomiserCandidateFeatures.GENE_MOI_GROUP_RANK_SEMANTICS, "synthetic-candidate-" + ordinal,
                0.9, 0.8, 0.7, 0.6, "MISSENSE_VARIANT", null, true,
                "synthetic-variants.tsv", "synthetic-genes.tsv", ordinal, ordinal,
                "SYNTHETIC_EXACT_GENE_MOI", "EXOMISER_VARIANT_SCORE", List.of(), List.of(), Map.of());
        return new VariantInterpretation(identity, null, List.of(), List.of(), features);
    }

    static HypothesisProvenance source() {
        return new HypothesisProvenance("SYNTHETIC_PATIENT_HYPOTHESIS", "e4-a1a-test-v1", "synthetic-patient/hypotheses");
    }

    static PatientDiseaseHypothesis hypothesis(VariantInterpretation candidate, String id, String hgnc, String mondo, String moi) {
        return new PatientDiseaseHypothesis(candidate.getCandidateId(), id, hgnc, mondo, moi, source());
    }

    private VariantInterpretation defaultCandidate() {
        return candidate(1, "GENEA", "16", "AD", "AD", "AUTOSOMAL_DOMINANT");
    }

    private PatientDiseaseHypothesis defaultHypothesis(VariantInterpretation candidate) {
        return hypothesis(candidate, "h1", "HGNC:20", "MONDO:0013212", "AD");
    }
}
