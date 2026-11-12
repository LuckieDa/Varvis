package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** PVS1-only factory wiring and additive identities; all sources and alleles are synthetic. */
class ReviewedPvs1CapabilitiesTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final TruncatingNmdPvs1RuntimeTest fixture = new TruncatingNmdPvs1RuntimeTest();
    @TempDir Path temporary;

    @Test void unchangedNmdBundleKeepsItsIdentityAndSimpleRuntime() throws Exception {
        var data = fixture.nonsense(1, 1, 31);
        var bundle = bundle(data, true, mapper.createObjectNode());
        assertFalse(bundle.identity().has("pvs1Capabilities"));
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("1.0.4", result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        assertEquals(result, bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate));
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void pvs1FactoryExplicitlyEnablesTheA5ConsumerOnBothStrands(int strand) throws Exception {
        var data = complex(strand);
        var bundle = bundle(data, true, mapper.createObjectNode());
        var identity = bundle.identity();
        assertEquals("CODING_TRANSCRIPT_EDITS", identity.at("/pvs1Capabilities/codingTranscriptEdits/method").asText());
        assertEquals("1.0.0", identity.at("/pvs1Capabilities/codingTranscriptEdits/version").asText());
        assertEquals(50, identity.at("/pvs1Capabilities/codingTranscriptEdits/maximumAlleleLengthIncludingAnchor").asInt());
        assertEquals(data.policy.path("policyId"), identity.at("/pvs1Capabilities/codingTranscriptEdits/policyIds/0"));
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(61, result.at("/resolvedInput/ptcStart").asInt());
        assertEquals(63, result.at("/resolvedInput/ptcEnd").asInt());
        assertEquals(119, result.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
        assertTrue(result.at("/resolvedInput/codingEditDerivation").isObject());
        assertEquals(TruncatingNmdPvs1Runtime.SPLICE_MODULE_VERSION,result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        var summary=Pvs1SelectedBatch.publicSummary(mapper,result);
        assertEquals(Pvs1SelectedBatch.SPLICE_VERSION,summary.path("methodVersion").asText());
        assertEquals("SOURCE_BOUND_CODING_EDITS",summary.path("method").asText());
        assertFalse(summary.toString().contains("insertedSequence"));
        assertEquals(result, bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate));
        identity.removeAll();
        assertTrue(bundle.identity().path("pvs1Capabilities").isObject());
    }

    @Test void legacyCombinedFactoryNeverImplicitlyEnablesComplexMechanics() throws Exception {
        var data = complex(1);
        var bundle = bundle(data, false, mapper.createObjectNode());
        assertFalse(bundle.identity().has("pvs1Capabilities"));
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);
        assertNotEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
        assertFalse(result.at("/resolvedInput").has("codingEditDerivation"));
    }

    @Test void unreviewedOrUnboundCapabilityIsNeitherAdvertisedNorExecuted() throws Exception {
        var data = complex(1);
        data.policy.withObject("codingEditScope").putArray("sourceIds").add("unbound");
        fixture.bind(data);
        var bundle = bundle(data, true, mapper.createObjectNode());
        assertFalse(bundle.identity().has("pvs1Capabilities"));
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);
        assertEquals("PVS1_COMPLEX_EDIT_SCOPE_NOT_REVIEWED", result.at("/evaluationResult/reasonCode").asText());
        assertEquals(0, result.path("evaluatorCalls").asInt());
        assertFalse(result.has("evidence"));
    }

    @Test void spliceCapabilityIsAdditiveAndDoesNotRewriteTheOldSimpleSpliceTrace() throws Exception {
        var splice = new Pvs1SpliceConsequenceTest().data(1, false, false);
        var data = splice.input();
        fixture.bind(data);
        var extra = mapper.createObjectNode();
        extra.putArray("pvs1SpliceOutcomes").add(splice.material());
        var bundle = bundle(data, true, extra);
        var identity = bundle.identity();
        assertEquals("1.3.0", identity.at("/algorithms/transcript-truncating-nmd-pvs1").asText());
        assertEquals("1.7.0", identity.at("/pvs1Capabilities/spliceRuntimeAdapter/version").asText());
        assertEquals(Pvs1SpliceConsequence.MODULE_VERSION,
                identity.at("/pvs1Capabilities/spliceRuntimeAdapter/moduleVersion").asText());
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("1.3.0", result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        assertFalse(identity.path("pvs1Capabilities").has("codingTranscriptEdits"));
    }

    @ParameterizedTest @CsvSource({"STOP,1", "STOP,-1", "INSERTION,1", "INSERTION,-1", "DELETION,1", "DELETION,-1"})
    void sourceQualifiedV4WithEmptySpliceInventoryKeepsOrdinaryNmd(String kind, int strand) throws Exception {
        var material = new Pvs1TranscriptMaterialSupplyTest();
        material.root = Files.createDirectory(temporary.resolve("material"));
        material.setup(kind, strand);
        var bundle = sourceQualifiedEmptySpliceBundle(material);
        var data = material.data;

        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);

        assertEquals("READY", result.at("/knowledgeAssessment/status").asText(), result.toPrettyString());
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        assertFalse(result.at("/resolvedInput").has("spliceAssessment"));
        assertTrue(material.reads.get() > 0, "An empty RNA inventory must not bypass current reference checks");
        assertEquals(1, bundle.pvs1RuntimeCalls());
    }

    @Test void sourceQualifiedV4SpliceAnnotationStillRequiresAnOutcomeInTheRuntime() throws Exception {
        var material = new Pvs1TranscriptMaterialSupplyTest();
        material.root = Files.createDirectory(temporary.resolve("material"));
        material.setup("STOP", 1);
        fixture.mutateAnnotation(material.data, "consequence", "stop_gained&splice_region_variant");
        var bundle = sourceQualifiedEmptySpliceBundle(material);
        var data = material.data;

        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, data.context, data.candidate);

        assertEquals("READY", result.at("/knowledgeAssessment/status").asText(), result.toPrettyString());
        assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED", result.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals(1, bundle.pvs1RuntimeCalls(), "Actual annotation dispatch owns the missing-outcome decision");
    }

    private ReviewedRuleBundle sourceQualifiedEmptySpliceBundle(Pvs1TranscriptMaterialSupplyTest material) throws Exception {
        var data = material.data;
        data.policy.put("schemaVersion", "pvs1-truncating-nmd-policy-v4");
        var scope = data.policy.putObject("spliceScope").put("status", "REVIEWED_FOR_PVS1_SPLICE")
                .put("reviewId", "SYNTHETIC_EMPTY_SPLICE_INVENTORY").put("maximumStrength", "VERY_STRONG")
                .put("basis", "Synthetic policy also covers ordinary truncating alleles; no RNA event is asserted");
        scope.putArray("sourceIds").add("synthetic-source");
        scope.putArray("evidenceKinds").add("OBSERVED_RNA").add("PREDICTED_CANONICAL");
        scope.putArray("eventTypes").add("EXON_SKIPPING").add("EXON_SHORTENING");
        var originals = Pvs1SpliceSourceQualificationTest.configureOriginals(mapper, data.policy, data.model, List.of());
        fixture.bind(data);
        var doc = Pvs1CodingSupplyIntegrationTest.document(material, data.model);
        doc.withObject("materials").putArray("pvs1SpliceOutcomes");
        var facts = Pvs1KnowledgeCatalogTest.facts(mapper, List.of(data.policy));
        var adoption = Pvs1SpliceSourceQualificationTest.adoption(mapper, facts, List.of(data.policy));
        ((ObjectNode) adoption.at("/entries/0")).set("qualification", Pvs1SpliceSourceQualificationTest.qualification(
                mapper, temporary.resolve("qualified-originals"), data.policy, data.model, List.of(), originals));
        var loader = new Pvs1ReviewedSourceSupplyTest();
        loader.root = Files.createDirectory(temporary.resolve("knowledge"));
        var knowledge = loader.load(facts, adoption);
        assertTrue(knowledge.sourceQualified());
        byte[] bytes = mapper.writeValueAsBytes(doc);
        Path file = temporary.resolve("empty-splice-bundle.json");
        Files.write(file, bytes);
        return ReviewedRuleBundle.loadPvs1Selected(mapper, file, CodingTranscriptMaterialSupply.sha(bytes),
                adapter.canonicalSha256(doc), new Pvs1TranscriptMaterialSupply(mapper, material.write()), knowledge);
    }

    private TruncatingNmdPvs1RuntimeTest.Inputs complex(int strand) throws Exception {
        var data = fixture.frameshift(false, strand, 120);
        var annotation = fixture.annotation(data, "frameshift_variant", "31_32delinsG", "Pro11AspfsTer11",
                "3/10", "11", "P/X", "CCA/GA");
        fixture.candidate(data, fixture.genomic(data.model, strand == 1 ? 31 : 32),
                strand == 1 ? "CC" : "GG", strand == 1 ? "G" : "C", annotation);
        TruncatingNmdPolicyComplexScopeTest.enable(data.policy, 50);
        fixture.bind(data);
        return data;
    }

    private ReviewedRuleBundle bundle(TruncatingNmdPvs1RuntimeTest.Inputs data, boolean pvs1Only, ObjectNode extraMaterials) {
        ObjectNode doc = mapper.createObjectNode().put("schemaVersion", pvs1Only ? "reviewed-rule-bundle-pvs1-v6" : "reviewed-rule-bundle-v1")
                .put("bundleId", "SYNTHETIC_CAPABILITY_TEST_ONLY").put("bundleVersion", "1");
        doc.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "SYNTHETIC_ONLY");
        doc.putObject("activation").put("automaticTrigger", false).put("classificationEnabled", false);
        ObjectNode materials = extraMaterials.deepCopy();
        materials.set("pvs1ResourceProfile", fixture.resourceProfile());
        materials.putObject("transcriptModels").set(adapter.canonicalSha256(data.model), data.model);
        doc.set("materials", materials);
        doc.putArray("policies").add(data.policy.deepCopy());
        var catalog = doc.putObject("catalog").put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "SYNTHETIC_CAPABILITY_TEST_ONLY").put("catalogVersion", "1")
                .put("searchedAt", "2026-09-13T16:00:00Z").put("searchScope", "Synthetic engineering fixture only");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ONLY");
        var entry = catalog.putArray("entries").addObject().put("entryId", "synthetic-capability-clause")
                .put("criterionId", TruncatingNmdPvs1Runtime.CRITERION).put("searchStatus", "COMPLETE")
                .put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "Synthetic engineering fixture only");
        entry.set("scope", data.policy.path("scope").deepCopy());
        entry.putObject("review").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ONLY");
        entry.putArray("sourceIds").add("synthetic-source");
        entry.putObject("policyBinding").put("policyId", data.policy.path("policyId").asText())
                .put("policyVersion", data.policy.path("policyVersion").asText()).put("policySha256", adapter.canonicalSha256(data.policy));
        catalog.set("sources", data.policy.path("scientificSources").deepCopy());
        doc.put("catalogSha256", adapter.canonicalSha256(catalog));
        return new ReviewedRuleBundle(mapper, doc, adapter.canonicalSha256(doc));
    }
}
