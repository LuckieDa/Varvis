package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fictional reviewed facts test the engineering contract, never real scientific acceptance. */
class Pvs1StartLossRuntimeTest {
    @org.junit.jupiter.api.io.TempDir Path automaticRoot;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final TruncatingNmdPvs1RuntimeTest helper = new TruncatingNmdPvs1RuntimeTest();
    record Fixture(TruncatingNmdPvs1RuntimeTest.Inputs data, ObjectNode material, ObjectNode transcripts, ObjectNode upstream) { }

    Fixture fixture(int strand) throws Exception {
        var d = helper.inputs(strand == 1 ? 1 : 2, strand, "ATGAAACCCATGAAATAA", 9);
        helper.candidate(d, helper.genomic(d.model, 1), strand == 1 ? "A" : "T", strand == 1 ? "G" : "C",
                helper.annotation(d, "start_lost", "1A>G", "Met1Val", "1/2", "1", "M/V", "ATG/GTG"));
        d.policy.put("schemaVersion", Pvs1StartLossPolicy.SCHEMA).put("appliedStrength", "MODERATE");
        d.policy.putObject("startLossAssessment").put("schemaVersion", Pvs1StartLossPolicy.SCHEMA)
                .put("status", "PERMITS").put("algorithmId", Pvs1StartLossPolicy.ALGORITHM)
                .putObject("review").put("status", "REVIEWED_IMPLEMENTED");
        d.policy.withObject("startLossAssessment").putArray("assessedTranscripts").add(d.model.path("transcript").asText()).add("NM_999999.1");
        helper.bind(d);
        // Independently authored expected sequence facts; not copied from evaluated output.
        ObjectNode facts = mapper.createObjectNode().put("selectedTranscript", d.model.path("transcript").asText())
                .put("strand", strand).put("cdsPosition", 1).put("referenceCodon", "ATG").put("alternateCodon", "GTG")
                .put("candidateRestartCdsPosition", 10);
        ObjectNode m = mapper.createObjectNode().put("schemaVersion", Pvs1StartLossRuntimeMaterial.SCHEMA)
                .put("runtimeEligible", true).put("clinicalAcceptance", false);
        m.putObject("contextBinding").put("contextSha256", Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, d.context));
        m.set("sequenceFacts", facts);
        var t = source(d, facts, "TRANSCRIPT_REVIEW");
        t.putArray("findings").addObject().put("transcript", d.model.path("transcript").asText());
        t.withArray("findings").addObject().put("transcript", "NM_999999.1").put("functionallyRelevant", true)
                .put("differentStart", false).put("targetUnaffected", false);
        var u = source(d, facts, "UPSTREAM_REVIEW");
        u.put("cdsStart", 1).put("cdsEnd", 9).putArray("records");
        Fixture f = new Fixture(d, m, t, u); pin(f); return f;
    }

    private ObjectNode source(TruncatingNmdPvs1RuntimeTest.Inputs d, ObjectNode facts, String role) {
        var s = mapper.createObjectNode().put("schemaVersion", Pvs1StartLossRuntimeMaterial.SOURCE_SCHEMA)
                .put("role", role).put("reviewId", "SYNTHETIC_ONLY_NO_SCIENTIFIC_REVIEW")
                .put("contextSha256", Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, d.context)).put("complete", true).put("truncated", false).put("unresolvedCount", 0);
        s.set("sequenceFacts", facts.deepCopy()); return s;
    }

    void pin(Fixture f) throws Exception {
        var bindings = f.data.policy.withObject("startLossAssessment").putArray("sourceBindings");
        var originals = f.material.putObject("sourceDocuments");
        for (var s : List.of(f.transcripts, f.upstream)) {
            String id = s.path("role").asText(), raw = mapper.writeValueAsString(s);
            originals.putObject(id).put("rawJson", raw);
            bindings.addObject().put("sourceId", id).put("role", id).put("url", "https://example.invalid/synthetic/" + id)
                    .put("reviewId", s.path("reviewId").asText()).put("sha256", sha(raw));
        }
        f.material.putObject("policyBinding").put("policySha256", Pvs1StartLossRuntimeMaterial.policyIdentitySha256(adapter, f.data.policy));
        bindMaterial(f);
    }

    void bindMaterial(Fixture f) {
        f.data.policy.putObject("startLossMaterialBinding").put("canonicalSha256", adapter.canonicalSha256(f.material));
        helper.bind(f.data);
    }

    ObjectNode run(Fixture f) {
        return new TruncatingNmdPvs1Runtime(mapper, helper.resourceProfile(), f.data.model)
                .evaluate(f.data.context, f.data.candidate, f.data.policy, f.data.selection, mapper.missingNode(), f.material);
    }

    ObjectNode record(Fixture f, int cds) {
        String ref = f.data.model.path("cdsSequence").asText().substring(cds - 1, cds);
        String alt = "A".equals(ref) ? "G" : "A";
        var r = mapper.createObjectNode().put("recordId", "SYNTHETIC_REFERENCE_" + cds)
                .put("classification", "PATHOGENIC").put("withdrawn", false).put("conflicting", false)
                .put("classificationBasis", "INDEPENDENT_OF_START_LOSS_INFERENCE");
        r.set("hgncId", f.data.context.at("/gene/hgncId")); r.set("mondoId", f.data.context.at("/disease/mondoId")); r.set("moi", f.data.context.path("moi"));
        r.putObject("allele").put("assembly", "GRCh38").put("contig", f.data.model.path("contig").asText())
                .put("position", helper.genomic(f.data.model, cds)).put("reference", CodingTranscriptMapping.orientAllele(f.data.model, ref))
                .put("alternate", CodingTranscriptMapping.orientAllele(f.data.model, alt));
        return r;
    }

    @Test void sharedStartDoesNotRequireAnUnrelatedFunctionalAssertion() throws Exception {
        var f = fixture(1);
        ((ObjectNode) f.transcripts.path("findings").get(1)).remove("functionallyRelevant");
        pin(f);
        assertEquals("SUPPORTING", run(f).at("/startLossFacts/decisionStrength").asText());
    }

    @Test void verifiedAlternativeDoesNotRequireAnExhaustiveNegativeSearch() throws Exception {
        var f = fixture(1);
        f.transcripts.put("complete", false).put("unresolvedCount", 1);
        ((ObjectNode) f.transcripts.path("findings").get(1)).put("differentStart", true).put("targetUnaffected", true);
        pin(f);
        var result = run(f);
        assertEquals("NOT_APPLICABLE", result.at("/startLossFacts/decisionOutcome").asText(), result.toPrettyString());
        assertFalse(result.at("/startLossFacts/transcriptReviewComplete").asBoolean());
    }

    @Test void qualifiedReferenceProvesExistenceButIncompleteEmptySearchCannotProveAbsence() throws Exception {
        var f = fixture(1); f.upstream.put("complete", false).put("unresolvedCount", 2);
        pin(f);
        assertEquals("NOT_EVALUATED", run(f).at("/evaluation/evaluationResult/status").asText());
        f.upstream.withArray("records").add(record(f, 4)); pin(f);
        var result = run(f);
        assertEquals("MODERATE", result.at("/startLossFacts/decisionStrength").asText(), result.toPrettyString());
        assertFalse(result.at("/startLossFacts/upstreamSearchComplete").asBoolean());
        assertEquals(2, result.at("/startLossFacts/sourceBindings").size());
    }

    @ParameterizedTest @ValueSource(strings = {"CONFLICT", "CIRCULAR", "BACKGROUND", "MAPPING"})
    void unrelatedReferenceProblemsAreIsolatedAndNeverBecomeNegativeCoverage(String problem) throws Exception {
        var f = fixture(-1); var good = record(f, 4); var other = record(f, 7);
        switch (problem) {
            case "CONFLICT" -> other.put("conflicting", true);
            case "CIRCULAR" -> other.put("classificationBasis", "START_LOSS_INFERENCE");
            case "BACKGROUND" -> other.put("mondoId", "MONDO:9999999");
            default -> other.withObject("allele").put("reference", "N");
        }
        for (boolean reverse : new boolean[]{false, true}) {
            var rows = f.upstream.putArray("records"); rows.add(reverse ? other : good).add(reverse ? good : other); pin(f);
            var result = run(f);
            assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText(), result.toPrettyString());
            assertFalse(result.at("/startLossFacts/upstreamSearchComplete").asBoolean());
            assertEquals(1, result.at("/startLossFacts/qualifyingReferenceCount").asInt());
            assertEquals(2, result.at("/startLossFacts/materialEvidence/referenceReviews").size());
            artifact("ISOLATED_" + problem + "_" + reverse, result);
        }
        f.upstream.putArray("records").add(other); pin(f);
        var result = run(f); assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals(1, result.at("/startLossFacts/materialEvidence/referenceReviews").size());
    }

    @ParameterizedTest @ValueSource(strings = {"EXPLICIT", "BENIGN", "UNKNOWN"})
    void sameAlleleConflictCannotBeBypassedWithAnotherSubmission(String kind) throws Exception {
        var f = fixture(1); var good = record(f, 4); var other = good.deepCopy().put("recordId", "SECOND_SUBMISSION");
        switch (kind) {
            case "EXPLICIT" -> other.put("conflicting", true);
            case "BENIGN" -> other.put("classification", "BENIGN");
            default -> other.remove("withdrawn");
        }
        for (boolean reverse : new boolean[]{false, true}) {
            f.upstream.putArray("records").add(reverse ? other : good).add(reverse ? good : other); pin(f);
            assertEquals("NONE", run(f).at("/evaluation/appliedStrength").asText());
        }
    }

    @ParameterizedTest @CsvSource({"1,false,SUPPORTING", "-1,false,SUPPORTING", "1,true,MODERATE", "-1,true,MODERATE"})
    void boundSourcesProduceOnlyTheAuthoredStrength(int strand, boolean upstream, String expected) throws Exception {
        var f = fixture(strand);
        if (upstream) f.upstream.withArray("records").add(record(f, 4));
        pin(f); var result = run(f);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(expected, result.at("/evaluation/appliedStrength").asText());
        assertEquals(upstream ? 1 : 0, result.at("/startLossFacts/qualifyingReferenceCount").asInt());
        assertEquals(result, run(f));
        assertEquals(f.material, result.path("startLossMaterial"));
        artifact(expected + (strand == 1 ? "_PLUS" : "_MINUS"), result);
    }

    @ParameterizedTest @ValueSource(strings = {"TARGET", "AT_RESTART", "DOWNSTREAM", "LP", "WITHDRAWN", "DUPLICATE"})
    void referenceQualificationIsNotAPathogenicLabelLookup(String kind) throws Exception {
        var f = fixture(-1);
        var r = record(f, switch (kind) { case "TARGET" -> 1; case "AT_RESTART" -> 10; case "DOWNSTREAM" -> 13; default -> 4; });
        if ("LP".equals(kind)) r.put("classification", "LIKELY_PATHOGENIC");
        if ("WITHDRAWN".equals(kind)) r.put("withdrawn", true);
        f.upstream.withArray("records").add(r);
        if ("DUPLICATE".equals(kind)) f.upstream.withArray("records").add(r.deepCopy().put("recordId", "ALIAS"));
        pin(f); var result = run(f);
        assertEquals("DUPLICATE".equals(kind) ? "MODERATE" : "SUPPORTING", result.at("/evaluation/appliedStrength").asText(), result.toPrettyString());
        assertEquals("DUPLICATE".equals(kind) ? 1 : 0, result.at("/startLossFacts/qualifyingReferenceCount").asInt());
    }

    @ParameterizedTest @ValueSource(strings = {"MATERIAL_PIN", "CONTEXT", "POLICY", "SEQUENCE", "SOURCE_BYTES", "SOURCE_MISSING", "SOURCE_CONTEXT", "INCOMPLETE", "TRUNCATED", "UNKNOWN_FUNCTION", "OMITTED_TRANSCRIPT", "WRONG_BACKGROUND", "CIRCULAR", "CONFLICT", "INTERVAL"})
    void incompleteOrChangedSourcesNeverGrantStrength(String kind) throws Exception {
        var f = fixture(1);
        switch (kind) {
            case "MATERIAL_PIN" -> f.material.put("added", true);
            case "CONTEXT" -> f.material.withObject("contextBinding").put("contextSha256", "f".repeat(64));
            case "POLICY" -> f.material.withObject("policyBinding").put("policySha256", "f".repeat(64));
            case "SEQUENCE" -> f.material.withObject("sequenceFacts").put("candidateRestartCdsPosition", 13);
            case "SOURCE_BYTES" -> f.material.withObject("sourceDocuments").withObject("TRANSCRIPT_REVIEW").put("rawJson", "{}");
            case "SOURCE_MISSING" -> f.material.withObject("sourceDocuments").remove("UPSTREAM_REVIEW");
            case "SOURCE_CONTEXT" -> f.upstream.put("contextSha256", "f".repeat(64));
            case "INCOMPLETE" -> f.upstream.put("complete", false);
            case "TRUNCATED" -> f.upstream.put("truncated", true);
            case "UNKNOWN_FUNCTION" -> ((ObjectNode) f.transcripts.path("findings").path(1)).put("differentStart", true).putNull("functionallyRelevant");
            case "OMITTED_TRANSCRIPT" -> f.transcripts.withArray("findings").remove(1);
            case "INTERVAL" -> f.upstream.put("cdsEnd", 10);
            default -> {
                var r = record(f, 4);
                if ("WRONG_BACKGROUND".equals(kind)) r.put("mondoId", "MONDO:9999999");
                if ("CIRCULAR".equals(kind)) r.put("classificationBasis", "SAME_START_LOSS_INFERENCE");
                if ("CONFLICT".equals(kind)) r.put("conflicting", true);
                f.upstream.withArray("records").add(r);
            }
        }
        if (List.of("SOURCE_CONTEXT", "INCOMPLETE", "TRUNCATED", "UNKNOWN_FUNCTION", "OMITTED_TRANSCRIPT", "WRONG_BACKGROUND", "CIRCULAR", "CONFLICT", "INTERVAL").contains(kind)) pin(f);
        else if (!"MATERIAL_PIN".equals(kind)) bindMaterial(f);
        var result = run(f);
        assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText(), kind + result.toPrettyString());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertTrue(result.at("/evaluation/evaluationResult/reasonCode").asText().startsWith("PVS1_START_LOSS_RUNTIME_"));
        if ("CONFLICT".equals(kind)) assertEquals("CONFLICT", result.at("/startLossFacts/decisionOutcome").asText());
        if ("SOURCE_MISSING".equals(kind)) assertEquals("SOURCE_UNAVAILABLE", result.at("/startLossFacts/decisionOutcome").asText());
        artifact(kind, result);
    }

    @Test void alternativeAndProhibitionDoNotNeedAnUpstreamNegative() throws Exception {
        var f = fixture(1);
        ((ObjectNode) f.transcripts.path("findings").path(1)).put("differentStart", true).put("targetUnaffected", true);
        f.upstream.put("complete", false); pin(f);
        var result = run(f);
        assertEquals("NOT_APPLICABLE", result.at("/evaluation/criterionApplicability/status").asText());
        assertEquals("NOT_REQUIRED", result.at("/startLossFacts/upstreamReferenceReviewStatus").asText());
        artifact("ALTERNATIVE", result);
        f.material.withObject("sourceDocuments").remove("UPSTREAM_REVIEW"); bindMaterial(f);
        assertEquals("NOT_APPLICABLE", run(f).at("/evaluation/criterionApplicability/status").asText());
        f.data.policy.withObject("startLossAssessment").put("status", "PROHIBITED");
        f.material.removeAll(); helper.bind(f.data);
        result = run(f);
        assertEquals("NOT_APPLICABLE", result.at("/evaluation/criterionApplicability/status").asText());
        artifact("PROHIBITED", result);
    }

    @Test void reviewLabelsAndHashesCannotReplaceOriginalsAndDuplicateJsonKeysAreRejected() throws Exception {
        var f = fixture(1);
        f.material.remove("sourceDocuments");
        f.material.putObject("transcriptReview").put("complete", true).put("semanticReviewStatus", "REVIEWED");
        f.material.putObject("upstreamReferenceReview").put("complete", true).put("status", "NONE_IN_ASSESSED_SOURCES");
        bindMaterial(f);
        assertEquals("PVS1_START_LOSS_RUNTIME_SOURCE_UNAVAILABLE", run(f).at("/evaluation/evaluationResult/reasonCode").asText());
        pin(f);
        String raw = mapper.writeValueAsString(f.transcripts);
        raw = "{\"complete\":false," + raw.substring(1);
        f.material.withObject("sourceDocuments").withObject("TRANSCRIPT_REVIEW").put("rawJson", raw);
        ((ObjectNode) f.data.policy.at("/startLossAssessment/sourceBindings/0")).put("sha256", sha(raw));
        f.material.withObject("policyBinding").put("policySha256", Pvs1StartLossRuntimeMaterial.policyIdentitySha256(adapter, f.data.policy));
        bindMaterial(f);
        assertEquals("PVS1_START_LOSS_RUNTIME_SOURCE_BYTES_INVALID", run(f).at("/evaluation/evaluationResult/reasonCode").asText());
    }

    @Test void materialCannotBypassSelectionOrModelBindingAndMetadataDoesNotChangeDecision() throws Exception {
        var f = fixture(1); var original = run(f);
        helper.mutateAnnotation(f.data, "consequence", "start_lost&splice_region_variant&NMD_transcript_variant");
        assertEquals(original.path("startLossFacts"), run(f).path("startLossFacts"));
        f.data.selection.put("policySha256", "f".repeat(64));
        assertEquals("PVS1_START_LOSS_POLICY_SELECTION_IDENTITY_INVALID", run(f).at("/evaluationResult/reasonCode").asText());
        helper.bind(f.data); f.data.model.put("cdsSequence", "ATGCCCACCATGAAATAA");
        assertEquals("PVS1_START_LOSS_MODEL_BINDING_MISMATCH", run(f).at("/evaluationResult/reasonCode").asText());
    }

    @Test void realCompetingSpliceMaterialCannotBeIgnoredByStartLossRoute() throws Exception {
        var f = fixture(1);
        var records = mapper.createArrayNode();
        var splice = records.addObject().put("hgncId", f.data.context.at("/gene/hgncId").asText())
                .put("mondoId", f.data.context.at("/disease/mondoId").asText()).put("moi", f.data.context.path("moi").asText())
                .put("transcript", f.data.model.path("transcript").asText()).put("transcriptModelSha256", adapter.canonicalSha256(f.data.model));
        splice.set("allele", f.data.context.at("/variant/evidenceQueryAllele").deepCopy());
        var result = new TruncatingNmdPvs1Runtime(mapper, helper.resourceProfile(), f.data.model)
                .evaluate(f.data.context, f.data.candidate, f.data.policy, f.data.selection, records, f.material);
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("PVS1_START_LOSS_COMBINED_MECHANISM_CONFLICT", result.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals(splice, result.path("spliceConsequenceMaterial"));
        helper.mutateAnnotation(f.data, "consequence", "start_lost&splice_donor_variant");
        assertEquals("PVS1_START_LOSS_COMBINED_MECHANISM_CONFLICT", run(f).at("/evaluation/evaluationResult/reasonCode").asText());
    }

    @Test void mitochondrialAndNonAtgMechanismsRemainExplicitlyUnsupported() throws Exception {
        var f = fixture(1);
        f.data.model.put("cdsSequence", "CTGAAACCCATGAAATAA"); helper.bind(f.data);
        assertEquals("PVS1_START_LOSS_NON_ATG_REFERENCE_UNSUPPORTED", run(f).at("/evaluation/evaluationResult/reasonCode").asText());
        f = fixture(1); f.data.model.put("contig", "MT");
        helper.candidate(f.data, helper.genomic(f.data.model, 1), "A", "G",
                helper.annotation(f.data, "start_lost", "1A>G", "Met1Val", "1/2", "1", "M/V", "ATG/GTG"));
        assertEquals("PVS1_START_LOSS_SCOPE_UNSUPPORTED", run(f).at("/evaluationResult/reasonCode").asText());
    }

    @Test void reviewedBundleAndDirectEntryReplayTheSameFactsAndRejectChangedMaterial() throws Exception {
        var f = fixture(1); var doc = bundle(f);
        var result = new ReviewedRuleBundle(mapper, doc, adapter.canonicalSha256(doc)).evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertEquals(run(f).path("startLossFacts"), result.path("startLossFacts"), result.toPrettyString());
        String pin = f.data.policy.at("/startLossMaterialBinding/canonicalSha256").asText();
        doc.withObject("materials").withObject("pvs1StartLossMaterials").withObject(pin).put("tampered", true);
        result = new ReviewedRuleBundle(mapper, doc, adapter.canonicalSha256(doc)).evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertEquals("PVS1_START_LOSS_RUNTIME_MATERIAL_BINDING_MISMATCH", result.at("/evaluation/evaluationResult/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void singleAndAllEvidenceSelectionPreserveTheSameStartLossFactsAndProvenance(boolean automatic) throws Exception {
        var f = fixture(1);
        f.data.context.withObject("transcript").put("selectionPolicy",
                org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.POLICY_ID + "@1.0.0-unified-local-fourteen");
        f.data.context.put("contextId", adapter.canonicalContextId(f.data.context)); helper.bind(f.data);
        var doc = bundle(f);
        Pvs1StartLossAutomaticPreparation preparation = null;
        if (automatic) {
            doc.withObject("materials").remove("pvs1StartLossMaterials");
            var nativeFixture = new Pvs1StartLossAutomaticPreparationTest(); nativeFixture.root = automaticRoot;
            var technical = nativeFixture.technicalSupply(f);
            preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> technical,
                    nativeFixture.sources(nativeFixture.document(), mapper.createObjectNode()));
        }
        var boundBundle = spy(new ReviewedRuleBundle(mapper, doc, adapter.canonicalSha256(doc)));
        // The source qualification fixture is synthetic. Only batch admission is stubbed;
        // selector, material validation, sequence replay, decision and public projection are real.
        doReturn(true).when(boundBundle).selectedPvs1Enabled();
        var pvs1 = new Pvs1SelectedBatch(mapper, boundBundle, preparation);
        var batchFixture = new LocalEvidenceBatchRuntimeTest();
        org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation selected =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(batchFixture, "selected", "one", f.data.context, f.data.candidate);
        assertNotNull(selected);
        JsonNode first = null;
        for (boolean all : new boolean[]{false, true}) {
            var denovo = mock(DeNovoSelectedBatch.class); when(denovo.identity()).thenReturn(mapper.createObjectNode());
            var pm3 = mock(Pm3SelectedBatch.class); when(pm3.identity()).thenReturn(mapper.createObjectNode());
            when(pm3.originals()).thenReturn(mapper.createObjectNode());
            var pp1 = mock(Pp1SelectedBatch.class); when(pp1.identity()).thenReturn(mapper.createObjectNode());
            when(pp1.originals()).thenReturn(mapper.createObjectNode());
            var bp7 = mock(Bp7SelectedBatch.class); when(bp7.identity()).thenReturn(mapper.createObjectNode());
            var runtime = new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode(), null, null, null, null, bp7,
                    () -> { throw new IllegalStateException("SYNTHETIC_UNAVAILABLE"); }, () -> pvs1,
                    () -> { throw new IllegalStateException("SYNTHETIC_UNAVAILABLE"); },
                    () -> { throw new IllegalStateException("SYNTHETIC_UNAVAILABLE"); },
                    () -> { throw new IllegalStateException("SYNTHETIC_UNAVAILABLE"); },
                    () -> { throw new IllegalStateException("SYNTHETIC_UNAVAILABLE"); }, false, denovo, pm3, pp1);
            runtime.bindExecutionCriteria(all ? SelectedCriterionRouting.CODES.stream().map(c -> "ACMG_AMP_2015:" + c).toList()
                    : List.of(TruncatingNmdPvs1Runtime.CRITERION));
            var output = runtime.execute(selected, batchFixture.analysis, batchFixture.sourceSha);
            var view = runtime.publicView(output);
            assertEquals(all ? 16 : 1, view.at("/results/0/localBatch/criteria").size(), view.toPrettyString());
            JsonNode criterion = null;
            for (var row : view.at("/results/0/localBatch/criteria"))
                if (TruncatingNmdPvs1Runtime.CRITERION.equals(row.path("criterionId").asText())) criterion = row;
            assertNotNull(criterion);
            assertEquals(automatic ? "NOT_EVALUATED" : "MET", criterion.path("status").asText(), criterion.toPrettyString());
            if (first == null) first = criterion; else assertEquals(first, criterion);
            if (automatic) {
                assertEquals("INCOMPLETE", criterion.at("/details/pvs1Facts/automaticPreparation/status").asText());
                assertEquals(5, output.at("/modules/pvs1/startLossSourceDocuments").size());
                assertEquals(4, output.at("/modules/pvs1/startLossAcquisition/httpRequests").asInt());
                assertFalse(view.at("/modules/pvs1").has("startLossSourceDocuments"), "raw originals belong in full export only");
            } else assertEquals(f.material, output.at("/modules/pvs1/results/0/startLossMaterial"));
            int calls = boundBundle.pvs1EvaluatorCalls();
            runtime.execute(selected, batchFixture.analysis, batchFixture.sourceSha);
            assertEquals(calls, boundBundle.pvs1EvaluatorCalls(), "transport repeat must reuse the same result");
        }
    }

    ObjectNode bundle(Fixture f) throws Exception {
        var base = new Pvs1NmdBusinessFixture(mapper, false);
        base.models.set(0, f.data.model); base.policies.set(0, f.data.policy);
        var doc = base.document(); doc.withArray("policies").remove(1); doc.withObject("catalog").withArray("entries").remove(1);
        doc.put("catalogSha256", adapter.canonicalSha256(doc.path("catalog")));
        doc.withObject("materials").putObject("pvs1StartLossMaterials")
                .set(Pvs1StartLossRuntimeMaterial.materialSha256(adapter, f.data.policy, f.data.context), f.material.deepCopy());
        return doc;
    }

    @Test void failedExpertAcquisitionCannotFallBackToAnOtherwiseScoringBundle() throws Exception {
        var f = fixture(1);
        f.data.context.withObject("transcript").put("selectionPolicy",
                org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.POLICY_ID + "@1.1.0-local-batch1");
        f.data.context.put("contextId", adapter.canonicalContextId(f.data.context)); helper.bind(f.data);
        var document = bundle(f); var bound = spy(new ReviewedRuleBundle(mapper, document, adapter.canonicalSha256(document)));
        doReturn(true).when(bound).selectedPvs1Enabled();
        var technical = new Pvs1StartLossAutomaticPreparationTest(); technical.root = automaticRoot;
        var supply = technical.technicalSupply(f);
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> supply,
                technical.sources(technical.document(), mapper.createObjectNode()), () -> { throw new IllegalArgumentException("UNAVAILABLE"); });
        var batch = new Pvs1SelectedBatch(mapper, bound, preparation);
        var business = new LocalEvidenceBatchRuntimeTest();
        org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation selected =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(business, "selected", "one", f.data.context, f.data.candidate);
        assertNotNull(selected);
        // The synthetic selection helper uses these stable identifiers.
        var result = batch.evaluate(selected, "one", "hypothesis", business.sourceSha);
        assertEquals("PVS1_START_LOSS_EXPERT_RULE_UNRESOLVED", result.at("/evaluationResult/reasonCode").asText(), result.toPrettyString());
        verify(bound, never()).evaluate(anyString(), any(), any());
    }

    @Test void onePolicyAndSourceDatasetCanServeDifferentAllelesWithoutCaseSpecificRuleChanges() throws Exception {
        var first = fixture(1); var second = fixture(1);
        helper.candidate(second.data, helper.genomic(second.data.model, 2), "T", "C",
                helper.annotation(second.data, "start_lost", "2T>C", "Met1Thr", "1/2", "1", "M/T", "ATG/ACG"));
        String key1 = Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, first.data.context);
        String key2 = Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, second.data.context);
        second.material.withObject("contextBinding").put("contextSha256", key2);
        second.material.withObject("sequenceFacts").put("cdsPosition", 2).put("alternateCodon", "ACG");
        for (var source : List.of(second.transcripts, second.upstream)) {
            source.put("contextSha256", key2);
            source.set("sequenceFacts", second.material.path("sequenceFacts").deepCopy());
        }
        second.upstream.withArray("records").add(record(second, 4));
        var policy = first.data.policy.deepCopy(); policy.remove("startLossMaterialBinding");
        var bindings = policy.withObject("startLossAssessment").putArray("sourceBindings");
        var originals = mapper.createObjectNode();
        for (int i = 0; i < 2; i++) {
            var a = i == 0 ? first.transcripts : first.upstream;
            var b = i == 0 ? second.transcripts : second.upstream;
            var dataset = a.deepCopy(); dataset.remove(List.of("contextSha256", "sequenceFacts", "findings", "records", "complete", "truncated", "unresolvedCount", "cdsStart", "cdsEnd"));
            dataset.putObject("contexts").set(key1, a); dataset.withObject("contexts").set(key2, b);
            String id = dataset.path("role").asText(), raw = mapper.writeValueAsString(dataset);
            originals.putObject(id).put("rawJson", raw);
            bindings.addObject().put("sourceId", id).put("role", id).put("url", "https://example.invalid/synthetic/" + id)
                    .put("reviewId", dataset.path("reviewId").asText()).put("sha256", sha(raw));
        }
        var index = policy.putObject("startLossMaterialBindings");
        for (var f : List.of(first, second)) {
            f.material.set("sourceDocuments", originals.deepCopy());
            f.material.withObject("policyBinding").put("policySha256", Pvs1StartLossRuntimeMaterial.policyIdentitySha256(adapter, policy));
            index.put(Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, f.data.context), adapter.canonicalSha256(f.material));
        }
        first.data.policy = policy.deepCopy(); second.data.policy = policy.deepCopy();
        helper.bind(first.data); helper.bind(second.data);
        assertEquals("SUPPORTING", run(first).at("/evaluation/appliedStrength").asText());
        assertEquals("MODERATE", run(second).at("/evaluation/appliedStrength").asText());
        assertEquals(first.data.policy, second.data.policy);
        var doc = bundle(first);
        doc.withObject("materials").withObject("pvs1StartLossMaterials")
                .set(adapter.canonicalSha256(second.material), second.material.deepCopy());
        var runtime = new ReviewedRuleBundle(mapper, doc, adapter.canonicalSha256(doc));
        assertEquals("SUPPORTING", runtime.evaluate(TruncatingNmdPvs1Runtime.CRITERION, first.data.context, first.data.candidate).at("/evaluation/appliedStrength").asText());
        assertEquals("MODERATE", runtime.evaluate(TruncatingNmdPvs1Runtime.CRITERION, second.data.context, second.data.candidate).at("/evaluation/appliedStrength").asText());
        artifact("MULTI_CONTEXT_SUPPORTING", run(first)); artifact("MULTI_CONTEXT_MODERATE", run(second));
        var wrong = second.material.deepCopy(); wrong.setAll(first.material);
        var result = new TruncatingNmdPvs1Runtime(mapper, helper.resourceProfile(), second.data.model)
                .evaluate(second.data.context, second.data.candidate, second.data.policy, second.data.selection, mapper.missingNode(), wrong);
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
    }

    void artifact(String name, ObjectNode result) throws Exception {
        String root = System.getProperty("varvis.pvs1.startloss.output"); if (root == null) return;
        Files.createDirectories(Path.of(root));
        ObjectNode row = mapper.createObjectNode().put("criterionId", TruncatingNmdPvs1Runtime.CRITERION)
                .put("status", result.at("/evaluation/evaluationResult/status").asText())
                .put("appliedStrength", result.at("/evaluation/appliedStrength").asText())
                .put("reasonCode", result.at("/evaluation/evaluationResult/reasonCode").asText());
        row.putObject("details").set("pvs1Facts", Pvs1SelectedBatch.publicSummary(mapper, result));
        Files.writeString(Path.of(root).resolve(name + ".json"), mapper.writeValueAsString(row), StandardCharsets.UTF_8);
    }

    static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
