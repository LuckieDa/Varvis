package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ClinVarMissenseBusinessRuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode context;
    private ObjectNode audit;
    private ObjectNode profile;
    private TranscriptConsequence selected;
    private final AtomicInteger rawCalls = new AtomicInteger();
    private double targetScore;
    private double referenceScore;
    private boolean rawMissing;

    @BeforeEach void setUp() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            assertNotNull(input); JsonNode parsed = mapper.readTree(input); assertNotNull(parsed);
            context = (ObjectNode) parsed.path("context").deepCopy();
        }
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:1097").put("symbol", "BRAF");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0021060");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("accession", "ENST00000646891").put("version", 2);
        ((ObjectNode) context.path("variant")).put("variantType", "SNV").putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("assembly", "GRCh38").put("contig", "7").put("position", 140801537).put("reference", "T").put("alternate", "A");
        selected = mapper.treeToValue(mapper.createObjectNode().put("geneSymbol", "BRAF").put("transcriptId", "ENST00000646891.2")
                .put("transcriptAccession", "ENST00000646891.2").put("hgvsProtein", "ENSP00000493543.1:p.Leu245Phe")
                .put("consequence", "missense_variant").put("source", "VEP").put("status", "COMPLETED"), TranscriptConsequence.class);
        audit = ClinVarMissenseBusinessRuntime.loadAudit(mapper); profile = ClinVarMissenseBusinessRuntime.loadProfile(mapper);
        targetScore = 0.01; referenceScore = 0.02; rawMissing = false; rawCalls.set(0);
    }

    @Test void ps1UsesIndependentReferenceAndBothRealQueryInputs() {
        ObjectNode atomic = atomic(0, true); ObjectNode result = assess(atomic);
        assertEquals("MET", status(result, "PS1")); assertEquals("STRONG", evaluation(result, "PS1").path("appliedStrength").asText());
        assertEquals("NOT_MET", status(result, "PM5")); assertEquals(1, rawCalls.get());
        assertEquals(2, result.path("spliceEvidence").size());
        assertEquals(atomic, result.path("atomicEvidence").get(0));
        assertEquals("SINGLE_SELECTABLE_MATCH", result.at("/selection/outcome").asText());
    }
    @Test void pm5UsesAuditedProteinTripleAndModerateCap() {
        ObjectNode result = assess(atomic(1, false)); assertEquals("MET", status(result, "PM5"));
        assertEquals("MODERATE", evaluation(result, "PM5").path("appliedStrength").asText()); assertEquals("NOT_MET", status(result, "PS1"));
    }
    @Test void addedThrRelationshipUsesIndependentReferenceAndBoundedRnaReview() {
        ObjectNode result = assess(thr241Atomic());
        assertEquals("MET", status(result, "PM5"));
        assertEquals("MODERATE", evaluation(result, "PM5").path("appliedStrength").asText());
        assertEquals("NOT_MET", status(result, "PS1"));
        assertEquals(1, rawCalls.get());
        assertEquals("UNKNOWN", result.at("/references/0/rnaReview/rnaEffect").asText());
        assertFalse(result.at("/references/0/rnaReview/normalRnaClaimed").asBoolean(true));
    }
    @Test void newReferenceWithoutRnaReviewIsNotEvaluatedBeforeModel() {
        ((ObjectNode) profile.path("rnaEvidenceReview")).putArray("reviews");
        assertReason(assess(thr241Atomic()), "PM5", "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON");
        assertEquals(0, rawCalls.get());
    }
    @Test void omittingRequiredReferenceListCannotExemptNewReference() {
        ((ObjectNode) profile.path("rnaEvidenceReview")).putArray("requiredReferenceScvs");
        ((ObjectNode) profile.path("rnaEvidenceReview")).putArray("reviews");
        assertReason(assess(thr241Atomic()), "PM5", "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON");
    }
    @Test void reviewOfAnotherTranscriptDoesNotApply() {
        ((ObjectNode) profile.at("/rnaEvidenceReview/scope")).put("transcript", "ENST00000646891.1");
        assertReason(assess(thr241Atomic()), "PM5", "RNA_REVIEW_SCOPE_MISMATCH");
    }
    @Test void noReportIsNotProofOfNormalRna() {
        ((ObjectNode) profile.at("/rnaEvidenceReview/reviews/0")).put("normalRnaClaimed", true);
        assertReason(assess(thr241Atomic()), "PM5", "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON");
    }
    @Test void unresolvedTargetSpliceConcernBlocksProteinPathBeforeModel() {
        ObjectNode atomic = thr241Atomic();
        ObjectNode concern = (ObjectNode) profile.at("/rnaEvidenceReview/knownConcerns/0");
        concern.set("allele", context.at("/variant/evidenceQueryAllele").deepCopy());
        assertReason(assess(atomic), "PM5", "PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW");
        assertEquals(0, rawCalls.get());
    }
    @Test void unresolvedReferenceSpliceConcernBlocksProteinPathBeforeModel() {
        ObjectNode atomic = thr241Atomic();
        ((ObjectNode) profile.at("/rnaEvidenceReview/knownConcerns/0")).set("allele", audit.at("/references/2/allele").deepCopy());
        assertReason(assess(atomic), "PM5", "REFERENCE_PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW");
        assertEquals(0, rawCalls.get());
    }
    @Test void newReferenceCannotBorrowAnUnauditedThrReplacement() {
        ObjectNode atomic = thr241Atomic(); metric(atomic, "vep.target.alternateAminoAcid", "M");
        assertReason(assess(atomic), "PM5", "PM5_AMINO_ACID_RELATION_NOT_AUDITED");
        assertEquals(0, rawCalls.get());
    }
    @Test void addedReferenceStillCannotUseCircularClassification() {
        ObjectNode atomic = thr241Atomic();
        ((ObjectNode) audit.path("references").get(2)).withArray("independentCriteria").add("PM5");
        assertReason(assess(atomic), "PM5", "REFERENCE_NOT_INDEPENDENT");
    }
    @Test void existingF247VRemainsSpliceNegativeAtObservedScore() {
        targetScore = 0.20; referenceScore = 0.08;
        ObjectNode result = assess(atomic(1, false));
        assertEquals("NOT_MET", status(result, "PM5"));
        assertEquals("SPLICE_CONDITION_NOT_MET", result.at("/scientificDecisions/PM5/reasonCode").asText());
    }
    @Test void rnaReviewRevisionChangesEvaluationIdentity() {
        ObjectNode atomic = thr241Atomic(); ObjectNode before = assess(atomic);
        ((ObjectNode) profile.path("rnaEvidenceReview")).put("reviewVersion", "future-fixture-revision");
        ObjectNode after = assess(atomic);
        assertNotEquals(evaluation(before, "PM5").path("evaluationId"), evaluation(after, "PM5").path("evaluationId"));
    }
    @Test void spliceAboveGateTargetIsEvaluatedNegative() { targetScore = 0.1001; assertEquals("NOT_MET", status(assess(atomic(0, true)), "PS1")); }
    @Test void spliceAboveGateReferenceIsEvaluatedNegative() { referenceScore = 0.11; assertEquals("NOT_MET", status(assess(atomic(1, false)), "PM5")); }
    @Test void inclusiveSpliceBoundaryIsPredefined() { targetScore = 0.1; referenceScore = 0.1; assertEquals("MET", status(assess(atomic(0, true)), "PS1")); }
    @Test void missingSpliceIsUnknownNotNegative() { rawMissing = true; assertReason(assess(atomic(0, true)), "PS1", "SPLICE_EVIDENCE_UNAVAILABLE"); }
    @Test void independentEvidenceCannotBeLabelOnly() { ((ObjectNode) audit.path("references").get(0)).put("independentCriteriaSufficient", false); assertReason(assess(atomic(0, true)), "PS1", "REFERENCE_NOT_INDEPENDENT"); assertEquals(0, rawCalls.get()); }
    @ParameterizedTest @ValueSource(strings = {"PS1", "PM5"}) void circularReferenceIsRejected(String criterion) {
        ((ObjectNode) audit.path("references").get(0)).withArray("independentCriteria").add(criterion);
        assertReason(assess(atomic(0, true)), "PS1", "REFERENCE_NOT_INDEPENDENT");
    }
    @Test void erepoDiseaseMustExactlyMatch() { ((ObjectNode) audit.path("references").get(0)).put("mondoId", "MONDO:0020297"); assertReason(assess(atomic(0, true)), "PS1", "REFERENCE_DISEASE_MISMATCH"); }
    @Test void historicalClinvarConditionIsNotRewritten() {
        ObjectNode atomic = atomic(0, true); metric(atomic, "clinvar.assertion.condition.submittedRaw", "MONDO:0020297");
        assertEquals("MET", status(assess(atomic), "PS1")); assertEquals("MONDO:0020297", value(atomic, "clinvar.assertion.condition.submittedRaw"));
    }
    @Test void cannotCompareAlleleWithItself() {
        ((ObjectNode) context.path("variant")).set("evidenceQueryAllele", audit.path("references").get(0).path("allele").deepCopy());
        assertReason(assess(atomic(0, true)), "PS1", "REFERENCE_SELF_COMPARISON");
    }
    @Test void transcriptMismatchCannotBeAccepted() { ObjectNode atomic = atomic(0, true); metric(atomic, "vep.comparator.transcriptId", "ENST00000646891.1"); assertReason(assess(atomic), "PS1", "REFERENCE_TRANSCRIPT_MISMATCH"); }
    @Test void allegedProteinRelationIsCrossChecked() { ObjectNode atomic = atomic(0, true); metric(atomic, "vep.target.proteinPosition", "246"); assertReason(assess(atomic), "PS1", "REFERENCE_PROTEIN_MISMATCH"); }
    @Test void unreviewedAminoAcidTripleCannotBeMet() { ObjectNode atomic = atomic(1, false); metric(atomic, "vep.target.alternateAminoAcid", "A"); assertReason(assess(atomic), "PM5", "PM5_AMINO_ACID_RELATION_NOT_AUDITED"); }
    @Test void exactReferenceVersionRequired() { ObjectNode atomic = atomic(0, true); metric(atomic, "clinvar.assertion.scv", "SCV000927045.2"); assertReason(assess(atomic), "PS1", "INDEPENDENT_REFERENCE_EVIDENCE_MISSING"); }
    @Test void sourceIdentityMismatchOverridesScientificMet() { ObjectNode atomic = atomic(0, true); ((ObjectNode) atomic.path("source")).put("resourceSha256", "f".repeat(64)); assertReason(assess(atomic), "PS1", "RESOURCE_IDENTITY_MISMATCH"); }
    @Test void contradictoryExpertAssertionBlocksBoth() {
        ObjectNode atomic = atomic(0, true); ObjectNode contradictory = atomic.deepCopy().put("evidenceId", "evidence-sha256-" + "b".repeat(64));
        metric(contradictory, "clinvar.assertion.scv", "SCV_OTHER"); metric(contradictory, "clinvar.assertion.classification", "Benign");
        var runtime = runtime(); ObjectNode result = runtime.assess(runtime.base(context), List.of(atomic, contradictory), selected);
        assertReason(result, "PS1", "REFERENCE_ASSERTION_CONFLICT"); assertEquals(0, rawCalls.get());
    }
    @Test void unrelatedLowReviewSubmissionWithoutDateDoesNotPoisonAuditedReference() {
        ObjectNode trusted = atomic(1, false);
        ObjectNode incidental = trusted.deepCopy().put("evidenceId", "evidence-sha256-" + "b".repeat(64));
        metric(incidental, "clinvar.assertion.scv", "SCV000965945.1");
        metric(incidental, "clinvar.assertion.reviewStatus", "no assertion criteria provided");
        metric(incidental, "clinvar.assertion.dateLastEvaluated", "");
        var runtime = runtime();
        ObjectNode result = runtime.assess(runtime.base(context), List.of(incidental, trusted), selected);
        assertEquals("MET", status(result, "PM5"));
        assertEquals(2, result.path("atomicEvidence").size());
        assertEquals(incidental, result.path("atomicEvidence").get(0));
        assertEquals(1, rawCalls.get());
    }
    @Test void mixedPublicAtomicReplayProducesCompleteBusinessBundle() throws Exception {
        ObjectNode fixture;
        try (InputStream input = getClass().getResourceAsStream("/acmg/ps1-pm5-mixed-atomic-replay-v1.json")) {
            assertNotNull(input); JsonNode parsed = mapper.readTree(input); assertNotNull(parsed);
            fixture = (ObjectNode) parsed;
        }
        var runtime = runtime();
        java.util.ArrayList<ObjectNode> atomics = new java.util.ArrayList<>();
        for (JsonNode atomic : fixture.path("atomicEvidence")) atomics.add((ObjectNode) atomic);
        ObjectNode result = runtime.assess(runtime.base((ObjectNode) fixture.path("context")), atomics, selected);
        assertEquals("MET", status(result, "PM5"));
        assertEquals(2, evaluation(result, "PM5").path("evidenceIds").size());
        assertEquals(evaluation(result, "PM5").path("evidenceIds"), evaluation(result, "PM5").at("/deterministicTrace/inputEvidenceIds"));
        assertEquals(1, result.at("/scientificDecisions/PM5/evidenceIds").size());
        java.nio.file.Path directory = java.nio.file.Path.of("target/ps1-pm5-contract-fixture");
        java.nio.file.Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("saved-mixed.json").toFile(), result);
    }
    @Test void auditedReferenceStillRequiresEveryAssertionField() {
        ObjectNode trusted = atomic(1, false);
        metric(trusted, "clinvar.assertion.dateLastEvaluated", "");
        assertReason(assess(trusted), "PM5", "ASSERTION_REQUIRED_FIELD_MISSING");
        assertEquals(0, rawCalls.get());
    }
    @Test void incompleteHighReviewOpposingSubmissionStillTriggersConflict() {
        ObjectNode trusted = atomic(1, false);
        ObjectNode opposing = trusted.deepCopy().put("evidenceId", "evidence-sha256-" + "b".repeat(64));
        metric(opposing, "clinvar.assertion.scv", "SCV_OTHER");
        metric(opposing, "clinvar.assertion.classification", "Benign");
        metric(opposing, "clinvar.assertion.dateLastEvaluated", "");
        var runtime = runtime();
        ObjectNode result = runtime.assess(runtime.base(context), List.of(trusted, opposing), selected);
        assertReason(result, "PM5", "REFERENCE_ASSERTION_CONFLICT");
        assertEquals(0, rawCalls.get());
    }
    @Test void evaluatorRechecksAcceptedReferenceCompletenessBeforeMet() {
        ObjectNode trusted = atomic(1, false);
        ObjectNode valid = assess(trusted);
        metric(trusted, "clinvar.assertion.dateLastEvaluated", "");
        List<ObjectNode> evaluations = new ClinVarPs1Pm5CriterionEvaluator(mapper).evaluateBusiness(context, List.of(trusted),
                valid.at("/selection/selectionId").asText(), profile, (ObjectNode) valid.path("scientificDecisions"));
        ObjectNode result = mapper.createObjectNode(); result.set("criterionEvaluations", mapper.valueToTree(evaluations));
        assertReason(result, "PM5", "ASSERTION_REQUIRED_FIELD_MISSING");
    }
    @Test void changedScientificInputChangesEvaluationIdentity() {
        ObjectNode atomic = atomic(0, true); ObjectNode before = assess(atomic); referenceScore = 0.03; ObjectNode after = assess(atomic);
        assertNotEquals(evaluation(before, "PS1").path("evaluationId"), evaluation(after, "PS1").path("evaluationId"));
    }
    @Test void dispositionDoesNotCallProviderOrEvaluator() {
        ObjectNode result = ClinVarMissenseBusinessRuntime.disposition(mapper, context, "OUTSIDE_BRAF_RASOPATHY_POLICY", "NOT_APPLICABLE");
        assertEquals(0, result.path("providerCalls").asInt()); assertEquals(0, result.path("evaluatorCalls").asInt());
        assertEquals("NOT_APPLICABLE", evaluation(result, "PS1").at("/criterionApplicability/status").asText());
    }

    private ClinVarMissenseBusinessRuntime runtime() {
        return new ClinVarMissenseBusinessRuntime(mapper, null, queries -> {
            rawCalls.incrementAndGet(); if (rawMissing) return SpliceAiRawBatchRunner.BatchResult.failed("MISSING", null);
            ObjectNode raw = mapper.createObjectNode().put("status", "SUCCEEDED").put("requestId", syntheticBatchRequestId(queries)); ArrayNode rows = raw.putArray("results");
            for (var query : queries) {
                var key = query.getKey(); ObjectNode row = rows.addObject().put("queryId", query.getQueryId()).put("status", "HIT").put("exactAlleleMatch", true);
                row.putObject("allele").put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome()).put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
                ObjectNode scores = row.putArray("annotations").addObject().put("geneSymbol", "BRAF").putObject("deltaScores");
                for (String field : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) scores.put(field, Double.toString("TARGET".equals(query.getQueryId()) ? targetScore : referenceScore));
            }
            return SpliceAiRawBatchRunner.BatchResult.succeeded(raw, 0);
        }, profile, audit);
    }
    private ObjectNode thr241Atomic() {
        ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("position", 140801550).put("reference", "G").put("alternate", "C");
        ObjectNode result = atomic(2, false);
        metric(result, "vep.target.alternateAminoAcid", "R");
        return result;
    }
    private String syntheticBatchRequestId(List<SpliceAiRawBatchRunner.Query> queries) {
        ArrayNode variants = mapper.createArrayNode();
        for (var query : queries) {
            var key = query.getKey(); ObjectNode variant = variants.addObject().put("queryId", query.getQueryId());
            variant.putObject("allele").put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                    .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
            variant.put("geneSymbol", query.getGeneSymbol()).put("selectedTranscriptId", query.getSelectedTranscriptId());
        }
        try {
            return "spliceai-batch-sha256-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(variants)));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private ObjectNode assess(ObjectNode atomic) { var runtime = runtime(); return runtime.assess(runtime.base(context), List.of(atomic), selected); }
    private String status(JsonNode result, String rule) { return evaluation(result, rule).at("/evaluationResult/status").asText(); }
    private JsonNode evaluation(JsonNode result, String rule) { for (JsonNode evaluation : result.path("criterionEvaluations")) if (("ACMG_AMP_2015:" + rule).equals(evaluation.path("criterionId").asText())) return evaluation; throw new AssertionError(rule); }
    private void assertReason(JsonNode result, String rule, String reason) { assertEquals("NOT_EVALUATED", status(result, rule)); assertEquals(reason, evaluation(result, rule).at("/evaluationResult/reasonCode").asText()); }
    private ObjectNode atomic(int index, boolean same) {
        JsonNode ref = audit.path("references").get(index); ObjectNode atomic = mapper.createObjectNode().put("schemaVersion", "atomic-evidence-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("evidenceType", "CLINICAL_DATABASE").put("evidenceId", "evidence-sha256-" + (same ? "a" : "c").repeat(64));
        atomic.putObject("provenance").put("producerId", RuntimeEvidenceV2Adapter.PRODUCER_ID);
        ObjectNode source = atomic.putObject("source"); ArrayNode locators = source.putArray("sourceLocators");
        for (JsonNode resource : ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources")) {
            if (resource.path("resourceId").asText().startsWith("CLINVAR_COMPARATOR")) source.put("sourceId", resource.path("resourceId").asText()).put("resourceVersion", resource.path("resourceVersion").asText()).put("resourceSha256", resource.path("identityFingerprintSha256").asText());
            locators.addObject().put("locatorValue", "resource-identity:" + resource.path("resourceId").asText() + "@GRCh38").put("artifactSha256", resource.path("identityFingerprintSha256").asText());
        }
        atomic.putObject("resourceExecution").put("status", "SUCCEEDED"); atomic.putObject("resourceAvailability").put("status", "AVAILABLE");
        atomic.putObject("queryOutcome").put("status", "HIT"); atomic.putObject("resourceApplicability").put("status", "APPLICABLE"); atomic.putArray("measurements");
        for (String field : List.of("assembly", "contig", "position", "reference", "alternate")) metric(atomic, "clinvar.comparator." + field, ref.path("allele").path(field).asText());
        metric(atomic, "clinvar.comparator.geneSymbols", "BRAF");
        metric(atomic, "clinvar.assertion.source.logicalName", "submission_summary_2026-03.txt.gz");
        metric(atomic, "clinvar.assertion.source.line", "1");
        metric(atomic, "clinvar.comparator.variationId", ref.path("clinvarVariationId").asText());
        metric(atomic, "clinvar.assertion.scv", ref.path("scv").asText()); metric(atomic, "clinvar.assertion.classification", "Pathogenic");
        metric(atomic, "clinvar.assertion.reviewStatus", "reviewed by expert panel"); metric(atomic, "clinvar.assertion.dateLastEvaluated", ref.path("dateLastEvaluated").asText());
        metric(atomic, "vep.proteinRelation", same ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID");
        for (String role : List.of("target", "comparator")) {
            metric(atomic, "vep." + role + ".transcriptId", "ENST00000646891.2"); metric(atomic, "vep." + role + ".proteinPosition", ref.path("proteinPosition").asText());
            metric(atomic, "vep." + role + ".referenceAminoAcid", ref.path("referenceAminoAcid").asText());
            metric(atomic, "vep." + role + ".alternateAminoAcid", "target".equals(role) && !same ? "V" : ref.path("alternateAminoAcid").asText());
        }
        return atomic;
    }
    private void metric(ObjectNode atomic, String name, String value) {
        for (JsonNode row : atomic.path("measurements")) if (name.equals(row.path("metricId").asText())) { ((ObjectNode) row).put("rawValue", value); return; }
        atomic.withArray("measurements").addObject().put("metricId", name).put("rawValue", value);
    }
    private String value(ObjectNode atomic, String name) { for (JsonNode row : atomic.path("measurements")) if (name.equals(row.path("metricId").asText())) return row.path("rawValue").asText(); return ""; }
}
