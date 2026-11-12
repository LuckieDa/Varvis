package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopNAcmgContextPlannerTest {

    private ObjectMapper mapper;
    private RuntimeEvidenceV2Adapter contextContract;
    private TopNAcmgContextPlanner planner;
    private AnalysisContext analysisContext;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        contextContract = new RuntimeEvidenceV2Adapter(mapper);
        planner = new TopNAcmgContextPlanner(mapper);
        analysisContext = new AnalysisContext("analysis-e4-a0", GenomeAssembly.GRCh38, AnalysisMode.WES);
    }

    @Test
    void keepsInputOrderWhenNIsLessThanEqualToOrGreaterThanCandidateCount() {
        List<VariantInterpretation> candidates = List.of(
                candidate(1, "1", 100, "A", "G", "GENEA", "AUTOSOMAL_DOMINANT"),
                candidate(2, "2", 200, "C", "T", "GENEB", "AUTOSOMAL_RECESSIVE"),
                candidate(3, "3", 300, "G", "A", "GENEC", "X_LINKED_DOMINANT")
        );
        List<TopNAcmgContextPlanner.ContextSeed> seeds = List.of(
                seed(candidates.get(0), "HGNC:101", "MONDO:0000101", "Disease A", "ENST00000000101", 1),
                seed(candidates.get(1), "HGNC:102", "MONDO:0000102", "Disease B", "ENST00000000102", 2),
                seed(candidates.get(2), "HGNC:103", "MONDO:0000103", "Disease C", "ENST00000000103", 3)
        );

        assertEquals(
                List.of(candidates.get(0).getCandidateId(), candidates.get(1).getCandidateId()),
                planner.plan(analysisContext, candidates, 2, seeds).candidateIds()
        );
        assertEquals(
                candidateIds(candidates),
                planner.plan(analysisContext, candidates, 3, seeds).candidateIds()
        );
        TopNAcmgContextPlanner.PlanResult greater = planner.plan(analysisContext, candidates, 5, seeds);
        assertEquals(candidateIds(candidates), greater.candidateIds());
        assertEquals(5, greater.requestedN());
        assertEquals(3, greater.selectedCount());
        assertFalse(greater.candidateOrderingChanged());
    }

    @Test
    void preservesSameAlleleCandidateRowsAndMultipleDiseaseContexts() {
        VariantInterpretation first = candidate(
                1, "4", 400, "T", "C", "GENEA", "AUTOSOMAL_DOMINANT"
        );
        VariantInterpretation second = candidate(
                2, "4", 400, "T", "C", "GENEB", "AUTOSOMAL_RECESSIVE"
        );
        List<TopNAcmgContextPlanner.ContextSeed> seeds = List.of(
                seed(first, "HGNC:201", "MONDO:0000201", "Disease A1", "ENST00000000201", 1),
                seed(first, "HGNC:201", "MONDO:0000202", "Disease A2", "ENST00000000201", 1),
                seed(second, "HGNC:202", "MONDO:0000203", "Disease B", "ENST00000000202", 2)
        );

        TopNAcmgContextPlanner.PlanResult result = planner.plan(
                analysisContext, List.of(first, second), 2, seeds
        );

        assertEquals(List.of(first.getCandidateId(), second.getCandidateId()), result.candidateIds());
        assertEquals(2, result.candidates().get(0).readyContexts().size());
        assertEquals(1, result.candidates().get(1).readyContexts().size());
        assertEquals(
                Set.of("MONDO:0000201", "MONDO:0000202"),
                result.candidates().get(0).readyContexts().stream()
                        .map(context -> context.path("disease").path("mondoId").asText())
                        .collect(Collectors.toSet())
        );
        assertEquals("GENEB", result.candidates().get(1).readyContexts().get(0)
                .path("gene").path("symbol").asText());
    }

    @Test
    void createsTheFrozenCanonicalContextDeterministically() throws IOException {
        ObjectNode frozenContext;
        try (InputStream input = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            frozenContext = (ObjectNode) mapper.readTree(input).path("context").deepCopy();
        }
        VariantInterpretation candidate = candidate(
                1, "1", 100000, "A", "G", "SYNTH1", "AUTOSOMAL_DOMINANT"
        );
        ObjectNode draft = contextDraft(frozenContext);
        TopNAcmgContextPlanner.ContextSeed seed = new TopNAcmgContextPlanner.ContextSeed(
                candidate.getCandidateId(), draft
        );

        ObjectNode first = planner.plan(analysisContext, List.of(candidate), 1, List.of(seed))
                .candidates().get(0).readyContexts().get(0);
        ObjectNode second = planner.plan(analysisContext, List.of(candidate), 1, List.of(seed))
                .candidates().get(0).readyContexts().get(0);

        frozenContext.withObjectProperty("variant").put("legacyCanonicalVariantId", candidate.getCanonicalVariantId());
        assertEquals(frozenContext, first);
        assertEquals(first.path("contextId"), second.path("contextId"));
        assertEquals(
                "context-sha256-e91f157819704c59f8f2b2abe63156d425d2bd33e2addad009317c37d7c6385e",
                first.path("contextId").asText()
        );
        contextContract.validateCompleteContext(first);
    }

    @Test
    void acceptsSingletonProbandWithoutFamilyAndPreservesTrioIdentity() throws IOException {
        ObjectNode frozenContext;
        try (InputStream input = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            frozenContext = (ObjectNode) mapper.readTree(input).path("context").deepCopy();
        }
        VariantInterpretation candidate = candidate(
                1, "1", 100000, "A", "G", "SYNTH1", "AUTOSOMAL_DOMINANT"
        );
        ObjectNode singletonDraft = contextDraft(frozenContext);
        singletonDraft.withObjectProperty("patientContext").remove("familyContextId");
        TopNAcmgContextPlanner.ContextSeed singletonSeed = new TopNAcmgContextPlanner.ContextSeed(
                candidate.getCandidateId(), singletonDraft
        );

        TopNAcmgContextPlanner.CandidatePlan singletonPlan = planner.plan(
                analysisContext, List.of(candidate), 1, List.of(singletonSeed)
        ).candidates().get(0);
        assertTrue(singletonPlan.blockers().isEmpty());
        assertEquals(1, singletonPlan.readyContexts().size());
        ObjectNode singleton = singletonPlan.readyContexts().get(0);
        ObjectNode repeated = planner.plan(analysisContext, List.of(candidate), 1, List.of(singletonSeed))
                .candidates().get(0).readyContexts().get(0);
        assertEquals(singleton, repeated);
        assertFalse(singleton.path("patientContext").has("familyContextId"));
        assertEquals("PROBAND", singleton.path("patientContext").path("role").asText());
        assertEquals(
                "context-sha256-b1f4db9677072df6809f9468075c624e7332ba5c52c2ed4f91041d6f0a973ea2",
                singleton.path("contextId").asText()
        );
        assertEquals(frozenContext.path("variant").path("alleleId"), singleton.path("variant").path("alleleId"));
        contextContract.validateCompleteContext(singleton);

        ObjectNode trio = planner.plan(
                analysisContext,
                List.of(candidate),
                1,
                List.of(new TopNAcmgContextPlanner.ContextSeed(candidate.getCandidateId(), contextDraft(frozenContext)))
        ).candidates().get(0).readyContexts().get(0);
        assertEquals("family-context-synthetic-e1-family", trio.path("patientContext").path("familyContextId").asText());
        assertEquals(frozenContext.path("contextId"), trio.path("contextId"));
        assertEquals(singleton.path("variant").path("alleleId"), trio.path("variant").path("alleleId"));
        contextContract.validateCompleteContext(trio);

        String output = System.getProperty("varvis.e4a1a.contextOutput");
        if (output != null && !output.isBlank()) {
            ObjectNode contexts = mapper.createObjectNode();
            contexts.set("singleton", singleton);
            contexts.set("trio", trio);
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(output), contexts);
        }
    }

    @Test
    void reportsMissingAmbiguousAndCandidateConflictWithoutProducingContext() {
        VariantInterpretation candidate = candidate(
                1, "5", 500, "A", "C", "GENED", "AUTOSOMAL_DOMINANT"
        );
        ObjectNode missing = draft(candidate, "HGNC:301", "MONDO:0000301", "Disease D", "ENST00000000301", 1);
        missing.withObjectProperty("disease").remove("mondoId");
        ObjectNode ambiguous = draft(candidate, "HGNC:301", "MONDO:0000301", "Disease D", "ENST00000000301", 1);
        ArrayNode alternatives = mapper.createArrayNode().add("ENST00000000301").add("ENST00000000302");
        ambiguous.withObjectProperty("transcript").set("accession", alternatives);
        ObjectNode conflict = draft(candidate, "HGNC:301", "MONDO:0000301", "Disease D", "ENST00000000301", 1);
        conflict.withObjectProperty("variant").withObjectProperty("normalizedAllele").put("alternate", "G");

        TopNAcmgContextPlanner.CandidatePlan result = planner.plan(
                analysisContext,
                List.of(candidate),
                1,
                List.of(
                        new TopNAcmgContextPlanner.ContextSeed(candidate.getCandidateId(), missing),
                        new TopNAcmgContextPlanner.ContextSeed(candidate.getCandidateId(), ambiguous),
                        new TopNAcmgContextPlanner.ContextSeed(candidate.getCandidateId(), conflict)
                )
        ).candidates().get(0);

        assertTrue(result.readyContexts().isEmpty());
        assertTrue(result.blockers().stream()
                .allMatch(blocker -> TopNAcmgContextPlanner.CONTEXT_NOT_READY.equals(blocker.status())));
        assertEquals(
                Set.of(
                        "CONTEXT_REQUIRED_FIELD_MISSING",
                        "CONTEXT_FIELD_AMBIGUOUS",
                        "CANDIDATE_NORMALIZED_ALLELE_CONFLICT"
                ),
                result.blockers().stream()
                        .map((TopNAcmgContextPlanner.ContextBlocker value) -> value.reasonCode())
                        .collect(Collectors.toSet())
        );
    }

    @Test
    void isolatesAValidContextFromAnInvalidSiblingSeed() {
        VariantInterpretation candidate = candidate(
                1, "5", 510, "G", "A", "GENED", "AUTOSOMAL_DOMINANT"
        );
        ObjectNode valid = draft(
                candidate, "HGNC:302", "MONDO:0000302", "Disease D1", "ENST00000000302", 1
        );
        ObjectNode invalid = draft(
                candidate, "HGNC:302", "MONDO:0000303", "Disease D2", "ENST00000000302", 1
        );
        invalid.withObjectProperty("transcript").remove("version");

        TopNAcmgContextPlanner.CandidatePlan result = planner.plan(
                analysisContext,
                List.of(candidate),
                1,
                List.of(
                        new TopNAcmgContextPlanner.ContextSeed(candidate.getCandidateId(), valid),
                        new TopNAcmgContextPlanner.ContextSeed(candidate.getCandidateId(), invalid)
                )
        ).candidates().get(0);

        assertEquals(1, result.readyContexts().size());
        assertEquals("MONDO:0000302", result.readyContexts().get(0).path("disease").path("mondoId").asText());
        assertEquals(1, result.blockers().size());
        assertEquals("CONTEXT_REQUIRED_FIELD_MISSING", result.blockers().get(0).reasonCode());
        assertEquals(2, result.blockers().get(0).seedOrdinal());
    }

    @Test
    void keepsNotReadyTopNRowInsteadOfBackfillingFromNextCandidate() {
        VariantInterpretation first = candidate(
                1, "6", 600, "C", "G", "GENEE", "AUTOSOMAL_DOMINANT"
        );
        VariantInterpretation second = candidate(
                2, "7", 700, "G", "T", "GENEF", "AUTOSOMAL_RECESSIVE"
        );
        TopNAcmgContextPlanner.ContextSeed secondSeed = seed(
                second, "HGNC:402", "MONDO:0000402", "Disease F", "ENST00000000402", 1
        );

        TopNAcmgContextPlanner.PlanResult result = planner.plan(
                analysisContext, List.of(first, second), 1, List.of(secondSeed)
        );

        assertEquals(1, result.selectedCount());
        assertEquals(List.of(first.getCandidateId()), result.candidateIds());
        assertTrue(result.candidates().get(0).readyContexts().isEmpty());
        assertEquals("CONTEXT_SEED_MISSING", result.candidates().get(0).blockers().get(0).reasonCode());
    }

    @Test
    void ignoresLegacySuggestionPresenceAndContent() {
        VariantInterpretation candidate = candidate(
                1, "8", 800, "T", "A", "GENEG", "AUTOSOMAL_DOMINANT"
        );
        TopNAcmgContextPlanner.ContextSeed seed = seed(
                candidate, "HGNC:501", "MONDO:0000501", "Disease G", "ENST00000000501", 4
        );
        TopNAcmgContextPlanner.PlanResult withoutLegacy = planner.plan(
                analysisContext, List.of(candidate), 1, List.of(seed)
        );
        candidate.applyAcmgEvidenceSuggestions(List.of(new AcmgEvidenceSuggestion(
                "BA1",
                "STANDALONE",
                "SUGGESTED",
                "legacy",
                "legacy content must not affect E4-A0",
                true,
                "acmg-ready-v1",
                List.of("legacy warning")
        )));

        TopNAcmgContextPlanner.PlanResult withLegacy = planner.plan(
                analysisContext, List.of(candidate), 1, List.of(seed)
        );

        assertEquals(withoutLegacy, withLegacy);
        String serialized = withLegacy.toString();
        assertFalse(serialized.contains("criterion-evaluation"));
        assertFalse(serialized.contains("evidence-bundle"));
    }

    @Test
    void acceptsNormalizedExomiserMoiAndAConcreteSmallInsertion() {
        VariantInterpretation candidate = exomiserCandidate(
                1,
                "9",
                900,
                "A",
                "AT",
                "GENEH",
                "AD",
                "AUTOSOMAL_DOMINANT"
        );
        ObjectNode draft = draft(
                candidate, "HGNC:601", "MONDO:0000601", "Disease H", "ENST00000000601", 2
        );
        draft.put("moi", "AUTOSOMAL_DOMINANT");

        TopNAcmgContextPlanner.CandidatePlan result = planner.plan(
                analysisContext,
                List.of(candidate),
                1,
                List.of(new TopNAcmgContextPlanner.ContextSeed(candidate.getCandidateId(), draft))
        ).candidates().get(0);

        assertTrue(result.blockers().isEmpty());
        assertEquals(1, result.readyContexts().size());
        assertEquals(
                "SMALL_INSERTION",
                result.readyContexts().get(0).path("variant").path("variantType").asText()
        );
        assertEquals("AUTOSOMAL_DOMINANT", result.readyContexts().get(0).path("moi").asText());
    }

    @Test
    void normalizesRawCandidateMoiButRejectsAnExplicitlyUnsupportedMoi() {
        VariantInterpretation rawAd = candidate(1, "9", 910, "C", "T", "GENEH", "AD");
        ObjectNode rawAdDraft = draft(
                rawAd, "HGNC:602", "MONDO:0000602", "Disease H1", "ENST00000000602", 1
        );
        rawAdDraft.put("moi", "AUTOSOMAL_DOMINANT");
        TopNAcmgContextPlanner.CandidatePlan ready = planner.plan(
                analysisContext,
                List.of(rawAd),
                1,
                List.of(new TopNAcmgContextPlanner.ContextSeed(rawAd.getCandidateId(), rawAdDraft))
        ).candidates().get(0);

        VariantInterpretation mitochondrial = candidate(
                2, "10", 920, "A", "G", "GENEI", "MITOCHONDRIAL"
        );
        ObjectNode mitochondrialDraft = draft(
                mitochondrial, "HGNC:603", "MONDO:0000603", "Disease H2", "ENST00000000603", 1
        );
        mitochondrialDraft.put("moi", "AUTOSOMAL_DOMINANT");
        TopNAcmgContextPlanner.CandidatePlan blocked = planner.plan(
                analysisContext,
                List.of(mitochondrial),
                1,
                List.of(new TopNAcmgContextPlanner.ContextSeed(
                        mitochondrial.getCandidateId(), mitochondrialDraft
                ))
        ).candidates().get(0);

        assertEquals(1, ready.readyContexts().size());
        assertTrue(ready.blockers().isEmpty());
        assertTrue(blocked.readyContexts().isEmpty());
        assertEquals("CANDIDATE_MOI_UNSUPPORTED", blocked.blockers().get(0).reasonCode());
    }

    @Test
    void keepsDuplicateCandidateRowsButRejectsAmbiguousSeedBinding() {
        VariantInterpretation originalFirst = candidate(
                1, "10", 1000, "A", "G", "GENEI", "AUTOSOMAL_DOMINANT"
        );
        VariantInterpretation originalSecond = candidate(
                2, "11", 1100, "C", "T", "GENEJ", "AUTOSOMAL_RECESSIVE"
        );
        CandidateIdentity firstIdentity = originalFirst.getCandidateIdentity().withCandidateId("duplicate-row-id");
        CandidateIdentity secondIdentity = originalSecond.getCandidateIdentity().withCandidateId("duplicate-row-id");
        VariantInterpretation first = new VariantInterpretation(
                firstIdentity,
                originalFirst.getObservation(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        VariantInterpretation second = new VariantInterpretation(
                secondIdentity,
                originalSecond.getObservation(),
                Collections.emptyList(),
                Collections.emptyList()
        );

        TopNAcmgContextPlanner.PlanResult result = planner.plan(
                analysisContext,
                List.of(first, second),
                2,
                List.of(seed(first, "HGNC:701", "MONDO:0000701", "Disease I", "ENST00000000701", 1))
        );

        assertEquals(List.of("duplicate-row-id", "duplicate-row-id"), result.candidateIds());
        assertTrue(result.candidates().stream().allMatch(plan -> plan.readyContexts().isEmpty()));
        assertTrue(result.candidates().stream().allMatch(plan ->
                "CANDIDATE_ID_AMBIGUOUS".equals(plan.blockers().get(0).reasonCode())));
    }

    @Test
    void requiresAnExplicitPositiveN() {
        assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(analysisContext, Collections.emptyList(), 0, Collections.emptyList())
        );
    }

    private TopNAcmgContextPlanner.ContextSeed seed(VariantInterpretation candidate,
                                                    String hgncId,
                                                    String mondoId,
                                                    String diseaseLabel,
                                                    String transcript,
                                                    int transcriptVersion) {
        return new TopNAcmgContextPlanner.ContextSeed(
                candidate.getCandidateId(),
                draft(candidate, hgncId, mondoId, diseaseLabel, transcript, transcriptVersion)
        );
    }

    private ObjectNode draft(VariantInterpretation candidate,
                             String hgncId,
                             String mondoId,
                             String diseaseLabel,
                             String transcript,
                             int transcriptVersion) {
        NormalizedVariantKey key = candidate.getObservation().getKey();
        ObjectNode draft = mapper.createObjectNode();
        draft.put("dataClass", "SYNTHETIC");
        draft.putObject("scope")
                .put("analysisDomain", "GERMLINE")
                .put("diseaseModel", "MENDELIAN")
                .put("variantScope", "SMALL_SEQUENCE_VARIANT");
        ObjectNode variant = draft.putObject("variant");
        variant.put("variantType", evidenceVariantType(key));
        variant.set("inputAllele", allele(key));
        variant.set("normalizedAllele", allele(key));
        variant.set("evidenceQueryAllele", allele(key));
        variant.putObject("normalization")
                .put("status", "UNCHANGED")
                .put("method", "synthetic-left-normalizer")
                .put("methodVersion", "1.0.0")
                .put("referenceSequenceSha256", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
                .put("refValidation", "MATCH");
        variant.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        draft.putObject("gene")
                .put("hgncId", hgncId)
                .put("symbol", candidate.getCandidateIdentity().getGeneSymbol());
        draft.putObject("disease")
                .put("mondoId", mondoId)
                .put("label", diseaseLabel);
        draft.put("moi", candidate.getCandidateIdentity().getModeOfInheritance());
        draft.putObject("transcript")
                .put("accession", transcript)
                .put("version", transcriptVersion)
                .put("selectionPolicy", "SYNTHETIC_PINNED_TRANSCRIPT")
                .put("annotationSourceVersion", "synthetic-1");
        draft.putObject("patientContext")
                .put("patientContextId", "patient-context-synthetic-e4a0-proband")
                .put("familyContextId", "family-context-synthetic-e4a0-family")
                .put("role", "PROBAND")
                .put("affectedStatus", "AFFECTED")
                .put("sex", "UNKNOWN");
        return draft;
    }

    private ObjectNode contextDraft(ObjectNode context) {
        ObjectNode draft = context.deepCopy();
        draft.remove(List.of("schemaVersion", "contextId", "canonicalizationProfile", "contextCompleteness"));
        draft.withObjectProperty("variant").remove("alleleId");
        draft.withObjectProperty("variant").remove("legacyCanonicalVariantId");
        return draft;
    }

    private ObjectNode allele(NormalizedVariantKey key) {
        return mapper.createObjectNode()
                .put("assembly", key.getGenomeAssembly())
                .put("contig", key.getChromosome())
                .put("position", key.getPosition())
                .put("reference", key.getReference())
                .put("alternate", key.getAlternate());
    }

    private VariantInterpretation candidate(int sourceOrdinal,
                                            String contig,
                                            int position,
                                            String reference,
                                            String alternate,
                                            String gene,
                                            String moi) {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, contig, position, reference, alternate
        );
        CandidateIdentity identity = CandidateIdentity.exomiser(
                key.canonicalId(), sourceOrdinal, gene, moi, sourceOrdinal
        );
        VariantQcObservation observation = new VariantQcObservation(
                key,
                "synthetic-sample",
                "0/1",
                30,
                60,
                "15,15",
                0.5d,
                "PASS",
                coarseVariantType(key),
                contig + "|" + position + "|" + reference + "|" + alternate + "|1"
        );
        return new VariantInterpretation(identity, observation, Collections.emptyList(), Collections.emptyList());
    }

    private VariantInterpretation exomiserCandidate(int sourceOrdinal,
                                                    String contig,
                                                    int position,
                                                    String reference,
                                                    String alternate,
                                                    String gene,
                                                    String rawMoi,
                                                    String normalizedMoi) {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, contig, position, reference, alternate
        );
        CandidateIdentity identity = CandidateIdentity.exomiser(
                key.canonicalId(), sourceOrdinal, gene, rawMoi, sourceOrdinal
        );
        VariantQcObservation observation = new VariantQcObservation(
                key,
                "synthetic-sample",
                "0/1",
                30,
                60,
                "15,15",
                0.5d,
                "PASS",
                coarseVariantType(key),
                contig + "|" + position + "|" + reference + "|" + alternate + "|1"
        );
        ExomiserCandidateFeatures features = new ExomiserCandidateFeatures(
                ExomiserCandidateFeatures.CURRENT_SCHEMA_VERSION,
                "15.1.0",
                gene,
                "123",
                rawMoi,
                normalizedMoi,
                sourceOrdinal,
                ExomiserCandidateFeatures.GENE_MOI_GROUP_RANK_SEMANTICS,
                "synthetic-record",
                1.0d,
                1.0d,
                1.0d,
                1.0d,
                "MISSENSE_VARIANT",
                null,
                true,
                "synthetic-variant.tsv",
                "synthetic-gene.tsv",
                sourceOrdinal,
                sourceOrdinal,
                "SYNTHETIC_EXACT_GENE_MOI",
                "EXOMISER_VARIANT_SCORE",
                Collections.emptyList(),
                Collections.emptyList(),
                Map.of()
        );
        return new VariantInterpretation(
                identity,
                observation,
                Collections.emptyList(),
                Collections.emptyList(),
                features
        );
    }

    private String coarseVariantType(NormalizedVariantKey key) {
        if (key.getReference().length() == 1 && key.getAlternate().length() == 1) {
            return "SNV";
        }
        return key.getReference().length() == key.getAlternate().length() ? "MNV" : "INDEL";
    }

    private String evidenceVariantType(NormalizedVariantKey key) {
        int referenceLength = key.getReference().length();
        int alternateLength = key.getAlternate().length();
        if (referenceLength == alternateLength) {
            return referenceLength == 1 ? "SNV" : "MNV";
        }
        if (referenceLength == 1) {
            return "SMALL_INSERTION";
        }
        if (alternateLength == 1) {
            return "SMALL_DELETION";
        }
        return "SMALL_DELINS";
    }

    private List<String> candidateIds(List<VariantInterpretation> candidates) {
        return candidates.stream().map((VariantInterpretation value) -> value.getCandidateId()).toList();
    }
}
