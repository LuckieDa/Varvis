package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Source-qualified normal path with independent synthetic reference reads, never clinical approval. */
class Pvs1CodingSupplyIntegrationTest {
    @TempDir Path root;

    static ObjectNode document(Pvs1TranscriptMaterialSupplyTest f, ObjectNode bound) throws Exception {
        var business = new Pvs1NmdBusinessFixture(f.mapper, false);
        business.models.clear(); business.policies.clear();
        // document() constructs two entries; retain exactly one after obtaining the common envelope.
        business.models.add(bound); business.models.add(bound);
        f.data.policy.putObject("transcriptModelBinding").put("modelId", bound.path("modelId").asText())
                .put("modelVersion", bound.path("modelVersion").asText()).put("canonicalSha256", f.adapter.canonicalSha256(bound));
        business.policies.add(f.data.policy); business.policies.add(f.data.policy);
        var doc = business.document();
        doc.withArray("policies").remove(1); doc.withObject("catalog").withArray("entries").remove(1);
        doc.put("catalogSha256", f.adapter.canonicalSha256(doc.path("catalog")));
        return doc;
    }

    static ReviewedRuleBundle load(Path root, Pvs1TranscriptMaterialSupplyTest f, ObjectNode bound,
                                   CodingTranscriptMaterialSupply common) throws Exception {
        var doc = document(f, bound);
        var facts = Pvs1KnowledgeCatalogTest.facts(f.mapper, List.of(f.data.policy));
        var helper = new Pvs1ReviewedSourceSupplyTest(); helper.root = root;
        var knowledge = helper.load(facts, Pvs1SourceQualificationTest.qualified(f.mapper, facts, List.of(f.data.policy)));
        byte[] bytes = f.mapper.writeValueAsBytes(doc); Path file = root.resolve("bundle.json"); Files.write(file, bytes);
        return ReviewedRuleBundle.loadPvs1Selected(f.mapper, file, CodingTranscriptMaterialSupply.sha(bytes),
                f.adapter.canonicalSha256(doc), new Pvs1TranscriptMaterialSupply(f.mapper, common), knowledge);
    }

    @ParameterizedTest @CsvSource({"STOP,1", "STOP,-1", "INSERTION,1", "INSERTION,-1", "DELETION,1", "DELETION,-1"})
    void localSequenceSuppliesOriginalPolicyWithoutPerAlleleRebinding(String kind, int strand) throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root = root;
        f.setup(kind, strand); var bound = f.data.model.deepCopy();
        f.data.model.put("modelId", "INDEPENDENT_TECHNICAL_FORMAT").put("modelVersion", "2");
        f.data.model.remove("lastJunctionCdsPosition");
        var bundle = load(root, f, bound, f.write()); var originalPolicy = f.data.policy.deepCopy();
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertTrue(f.reads.get() > 0, "Bundled material must not bypass current reference verification");
        assertEquals("EXACT_FULL_TRANSCRIPT_EQUIVALENCE", result.at("/transcriptMaterialSupply/bindingMethod").asText());
        assertEquals(originalPolicy, result.path("policy"));
        assertEquals("STOP".equals(kind) ? 31 : "INSERTION".equals(kind) ? 64 : 61, result.at("/resolvedInput/ptcStart").asInt());
        assertEquals("STOP".equals(kind) ? 417 : "INSERTION".equals(kind) ? 55 : 56, result.at("/resolvedInput/distanceToLastJunction").asInt());
        var repeated = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertTrue(repeated.at("/transcriptMaterialSupply/structure/modelCacheHit").asBoolean());
        assertEquals(result.path("evaluation"), repeated.path("evaluation"));
    }

    @Test void currentReferenceFailureCannotUseBundledSequenceAsFallback() throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root = root;
        var common = f.setup("STOP", 1); var bundle = load(root, f, f.data.model.deepCopy(), common);
        f.invalidReference = true;
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(0, result.path("evaluatorCalls").asInt());
        assertTrue(result.path("transcriptMaterialSupply").has("reasonCode"));
    }

    @ParameterizedTest @ValueSource(strings={"RNA", "CDS", "EXON", "PROTEIN", "VERSION", "PROOF"})
    void sourceQualifiedPolicyDoesNotAuthorizeNonEquivalentTechnicalMaterial(String kind) throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root = root;
        var common = f.setup("STOP", -1); var bound = f.data.model.deepCopy();
        String reason;
        switch (kind) {
            case "RNA" -> { bound.withObject("sequenceVerification").put("fullSplicedTranscriptSha256", "b".repeat(64)); reason = "PVS1_SUPPLIED_TRANSCRIPT_SEQUENCE_MISMATCH"; }
            case "CDS" -> { bound.withObject("sequenceVerification").put("cdsSha256", "b".repeat(64)); reason = "PVS1_SUPPLIED_TRANSCRIPT_SEQUENCE_MISMATCH"; }
            case "EXON" -> {
                for (var exon : bound.path("exons")) {
                    var e = (ObjectNode) exon; e.put("genomicStart", e.path("genomicStart").asInt()+1);
                    e.put("genomicEnd", e.path("genomicEnd").asInt()+1);
                }
                reason = "PVS1_SUPPLIED_TRANSCRIPT_STRUCTURE_MISMATCH";
            }
            case "PROTEIN" -> { bound.put("protein", "NP_999999.9"); reason = "PVS1_SUPPLIED_TRANSCRIPT_IDENTITY_MISMATCH"; }
            case "VERSION" -> { bound.put("transcript", bound.path("transcript").asText().replace(".1", ".2")); reason = "PVS1_TRANSCRIPT_MODEL_CONTEXT_MISMATCH"; }
            case "PROOF" -> { bound.withObject("sequenceVerification").remove("fullSplicedTranscriptSha256"); reason = "PVS1_BOUND_FULL_TRANSCRIPT_PROOF_MISSING"; }
            default -> throw new AssertionError(kind);
        }
        var bundle = load(root, f, bound, common);
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertEquals(reason, result.at("/evaluationResult/reasonCode").asText(), result.toPrettyString());
        assertEquals(0, result.path("evaluatorCalls").asInt());
    }

    @Test void cachedStructureStillRequiresCurrentReferenceAndAllele() throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root = root;
        var common = f.setup("INSERTION", 1); var bundle = load(root, f, f.data.model.deepCopy(), common);
        var first = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertEquals("MET", first.at("/evaluation/evaluationResult/status").asText());
        int reads = f.reads.get(); f.invalidReference = true;
        var second = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertEquals("NOT_EVALUATED", second.at("/evaluationResult/status").asText());
        assertTrue(f.reads.get() > reads); assertEquals(0, second.path("evaluatorCalls").asInt());
    }

    @Test void legacyModeRetainsItsOriginalIdentityAndDoesNotReadCurrentReference() throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root = root;
        f.setup("STOP", 1); var doc = document(f, f.data.model);
        var facts = Pvs1KnowledgeCatalogTest.facts(f.mapper, List.of(f.data.policy));
        var helper = new Pvs1ReviewedSourceSupplyTest(); helper.root = root;
        var knowledge = helper.load(facts, Pvs1ReviewedSourceSupplyTest.use(f.mapper, facts, List.of(f.data.policy)));
        byte[] bytes = f.mapper.writeValueAsBytes(doc); var file = root.resolve("bundle.json"); Files.write(file, bytes);
        var supply = new Pvs1TranscriptMaterialSupply(f.mapper, f.write());
        var bundle = ReviewedRuleBundle.loadPvs1Selected(f.mapper, file, CodingTranscriptMaterialSupply.sha(bytes),
                f.adapter.canonicalSha256(doc), supply, knowledge);
        assertEquals(supply.identity(), bundle.identity().path("transcriptMaterialSupply"));
        f.invalidReference = true;
        assertEquals("MET", bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate)
                .at("/evaluation/evaluationResult/status").asText());
        assertEquals(0, f.reads.get());
    }

    @ParameterizedTest @ValueSource(strings={"CONFIRMATION", "POLICY_OPERATOR", "INVALID_STRENGTH", "DISEASE"})
    void missingScientificOrUserPrerequisiteStopsBeforeReferenceReads(String kind) throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root = root;
        var common = f.setup("STOP", 1); String expected;
        switch (kind) {
            case "CONFIRMATION" -> { f.data.context.withObject("transcript").put("selectionPolicy",
                    org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.PM2_CONTEXT_SELECTION); expected = "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED"; }
            case "POLICY_OPERATOR" -> { f.data.policy.withObject("nmdScope").put("unimplementedScientificCondition",true); expected = "PVS1_POLICY_OPERATOR_UNSUPPORTED"; }
            case "INVALID_STRENGTH" -> { f.data.policy.put("appliedStrength","NONE"); expected = "PVS1_POLICY_STRENGTH_CONDITIONS_REQUIRED"; }
            case "DISEASE" -> { f.data.context.withObject("disease").put("mondoId","MONDO:9999999"); expected = "PVS1_MECHANISM_SOURCE_NOT_FOUND"; }
            default -> throw new AssertionError(kind);
        }
        f.data.context.put("contextId",f.adapter.canonicalContextId(f.data.context));
        var bundle = load(root, f, f.data.model.deepCopy(), common);
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, f.data.context, f.data.candidate);
        assertEquals(expected,result.at("/evaluationResult/reasonCode").asText(),result.toPrettyString());
        assertEquals(0,f.reads.get()); assertEquals(0,result.path("evaluatorCalls").asInt());
    }

    @ParameterizedTest @CsvSource({"STRONG,STOP,1", "MODERATE,STOP,-1", "SUPPORTING,STOP,1",
            "STRONG,INSERTION,-1", "MODERATE,INSERTION,1", "SUPPORTING,INSERTION,-1",
            "STRONG,DELETION,1", "MODERATE,DELETION,-1", "SUPPORTING,DELETION,1"})
    void qualifiedPolicyStrengthIsAnUpperBoundInsteadOfAForcedVeryStrong(String strength, String kind, int strand) throws Exception {
        var f = new Pvs1TranscriptMaterialSupplyTest(); f.root = root;
        var common = f.setup(kind,strand); f.data.policy.put("appliedStrength",strength);
        var bundle = load(root,f,f.data.model.deepCopy(),common);
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION,f.data.context,f.data.candidate);
        assertEquals("MET",result.at("/evaluation/evaluationResult/status").asText(),result.toPrettyString());
        assertEquals(strength,result.at("/evaluation/appliedStrength").asText());
        assertEquals("VERY_STRONG",result.at("/evaluation/baseStrength").asText());
        assertEquals("1.4.0",result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        assertEquals("1.4.0",bundle.identity().at("/algorithms/transcript-truncating-nmd-pvs1").asText());
        assertEquals(f.data.policy,result.path("policy"));
    }

    @Test void strengthCeilingCannotRaiseAnEscapeResult() throws Exception {
        var f = new Pvs1NmdEscapeTest(); var d = f.fixture.nonsense(1,1,499);
        var scope = Pvs1NmdEscapeTest.scope(d.policy,4);
        Pvs1NmdEscapeTest.critical(scope,160,190,10,"MODERATE");
        d.policy.put("appliedStrength","STRONG");
        assertEquals("MODERATE",f.run(d).at("/evaluation/appliedStrength").asText());
        d.policy.put("appliedStrength","SUPPORTING");
        assertEquals("SUPPORTING",f.run(d).at("/evaluation/appliedStrength").asText());
    }
}
