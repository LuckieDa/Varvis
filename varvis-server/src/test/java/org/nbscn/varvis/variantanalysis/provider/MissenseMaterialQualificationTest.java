package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Invented public/private envelopes exercise technical binding only; no real approval or patient data. */
class MissenseMaterialQualificationTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    MissensePreparationFixture releasedFixture;
    MissensePreparationFixture fixture(boolean pm5) throws Exception {
        return new MissensePreparationFixture(root.resolve("inputs"), new Ps1TestCatalog(mapper), pm5);
    }
    ObjectNode use(String dataClass) {
        var use = mapper.createObjectNode().put("schemaVersion", "missense-context-use-v1").put("purpose", "PROJECT_RESEARCH");
        use.putArray("allowedDataClasses").add(dataClass); return use;
    }
    ObjectNode releasePublic(boolean pm5) throws Exception {
        var f = fixture(pm5); releasedFixture = f;
        f.manifest.put("dataClass", "PUBLIC_REFERENCE").put("purpose", "TEST_FIXTURE_FOR_PUBLIC_REFERENCE_USE");
        f.manifest.set("contextUse", use("PRIVACY_BOUNDED_RUNTIME"));
        var pending = f.prepare(root.resolve("draft")); assertFalse(pending.ready());
        assertEquals("EXTERNAL_APPROVAL_MISSING", pending.report().path("reasonCode").asText());
        f.approve(root.resolve("draft"));
        var approval = (ObjectNode) mapper.readTree(f.root.resolve("approval.json").toFile());
        approval.put("dataClass", "PUBLIC_REFERENCE").set("contextUse", f.manifest.path("contextUse").deepCopy());
        f.manifest.set("approval", f.file("approval.json", approval));
        var result = f.prepare(root.resolve("released")); assertTrue(result.ready(), result.report().toString());
        return (ObjectNode) mapper.readTree(root.resolve("released/bundle.json").toFile());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void explicitExternalScopeAndCandidateSurviveTheProductionPackage(boolean pm5) throws Exception {
        var document = releasePublic(pm5);
        var runtime = new ReviewedRuleBundle(mapper, document, adapter.canonicalSha256(document));
        assertNull(runtime.missenseUseIssue(mapper.createObjectNode().put("dataClass", "PRIVACY_BOUNDED_RUNTIME")));
        assertEquals("MISSENSE_CONTEXT_DATA_CLASS_NOT_APPROVED", runtime.missenseUseIssue(mapper.createObjectNode().put("dataClass", "SYNTHETIC")));
        assertTrue(runtime.identity().path("materialQualificationSha256").asText().matches("[0-9a-f]{64}"));
        assertEquals("TEST_ONLY_EXTERNAL_REVIEWER", document.at("/materialQualification/externalApproval/reviewId").asText());
        assertEquals(pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1,
                document.at("/materialQualification/externalApproval/criterion").asText());
    }
    @Test void syntheticApprovalNeverGrantsPrivateUseAndLegacyPackagesRemainUnqualified() throws Exception {
        var f = fixture(false); var file = f.release("released");
        var document = (ObjectNode) mapper.readTree(file.toFile());
        var bundle = new ReviewedRuleBundle(mapper, document, adapter.canonicalSha256(document));
        assertNull(bundle.missenseUseIssue(mapper.createObjectNode().put("dataClass", "SYNTHETIC")));
        assertEquals("MISSENSE_CONTEXT_USE_NOT_APPROVED", bundle.missenseUseIssue(mapper.createObjectNode().put("dataClass", "PRIVACY_BOUNDED_RUNTIME")));
        document.remove("materialQualification");
        var legacy = new ReviewedRuleBundle(mapper, document, adapter.canonicalSha256(document));
        assertEquals("REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE", legacy.missenseUseIssue(mapper.createObjectNode().put("dataClass", "PRIVACY_BOUNDED_RUNTIME")));
    }
    @ParameterizedTest @ValueSource(strings={"material", "catalog", "approval", "use", "background", "purpose"})
    void rehashingTheOuterBundleCannotHideChangesToApprovedContents(String target) throws Exception {
        var document = releasePublic(false);
        switch (target) {
            case "material" -> ((ObjectNode) document.at("/materials/missenseMaterials")).removeAll();
            case "catalog" -> ((ObjectNode) document.path("catalog")).put("catalogVersion", "changed");
            case "approval" -> ((ObjectNode) document.at("/materialQualification/externalApproval")).put("reviewId", "changed");
            case "use" -> ((ObjectNode) document.at("/materialQualification/candidateMetadata/contextUse")).putArray("allowedDataClasses").add("SYNTHETIC");
            case "background" -> ((ObjectNode) document.at("/materialQualification/externalApproval")).putArray("backgrounds");
            default -> ((ObjectNode) document.at("/materialQualification/candidateMetadata")).put("purpose", "TEST_ONLY");
        }
        assertThrows(IllegalArgumentException.class, () -> new ReviewedRuleBundle(mapper, document, adapter.canonicalSha256(document)));
    }
    @Test void addingAnUnapprovedUseAfterTheCandidateWasReviewedDoesNotRelease() throws Exception {
        var f = fixture(false); f.prepare(root.resolve("first")); f.approve(root.resolve("first"));
        f.manifest.set("contextUse", use("SYNTHETIC"));
        var result = f.prepare(root.resolve("changed")); assertFalse(result.ready());
        assertEquals("EXTERNAL_APPROVAL_BINDING_MISMATCH", result.report().path("reasonCode").asText());
    }
    @Test void syntheticOrTestOnlyFactsCannotRequestPrivateScope() throws Exception {
        var f = fixture(false); f.manifest.set("contextUse", use("PRIVACY_BOUNDED_RUNTIME"));
        var result = f.prepare(root.resolve("forbidden")); assertFalse(result.ready());
        assertEquals("MISSENSE_SYNTHETIC_RUNTIME_USE_FORBIDDEN", result.report().path("reasonCode").asText());
    }
    @Test void scopeHasToBeRepeatedInExternalApprovalNotJustRequestedInManifest() throws Exception {
        var f = fixture(false); f.manifest.set("contextUse", use("SYNTHETIC"));
        f.prepare(root.resolve("draft")); f.approve(root.resolve("draft"));
        var result = f.prepare(root.resolve("no-scope")); assertFalse(result.ready());
        assertEquals("MISSENSE_CONTEXT_USE_NOT_APPROVED", result.report().path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void qualifiedScopeReachesOriginalEvaluatorWithoutReplacingItsScientificChecks(boolean pm5) throws Exception {
        var document = releasePublic(pm5);
        Path executionRoot = java.nio.file.Files.createDirectory(root.resolve("execution"));
        var catalog = releasedFixture.fixture;
        var bundle = catalog.loadPrepared(executionRoot, root.resolve("released/bundle.json"), adapter.canonicalSha256(document));
        var target = new MissenseReferenceSnapshotExportTest().new Fixture(root.resolve("facts"), 0, "NORMAL", "1/1");
        target.context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
        ((ObjectNode) target.context.path("disease")).put("label", "TEST_ONLY invented disease");
        ((ObjectNode) target.context.path("transcript")).put("selectionPolicy", "SYNTHETIC_PINNED_TRANSCRIPT");
        target.context.put("contextId", adapter.canonicalContextId(target.context));
        adapter.validateCompleteContext(target.context);
        var annotation = mapper.findAndRegisterModules().treeToValue(catalog.annotation(0, false), org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class);
        target.target.applyTranscriptAnnotations(List.of(annotation));
        String criterion = pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1;
        var result = bundle.evaluate(criterion, target.context, target.target);
        assertEquals("MET", result.at("/criterionEvaluations/0/evaluationResult/status").asText(), result.toString());
        int calls = catalog.discoveryCalls + catalog.annotationCalls + catalog.spliceCalls;
        target.context.put("dataClass", "SYNTHETIC");
        var denied = bundle.evaluate(criterion, target.context, target.target);
        assertEquals("MISSENSE_CONTEXT_DATA_CLASS_NOT_APPROVED", denied.at("/evaluationResult/reasonCode").asText());
        assertEquals(calls, catalog.discoveryCalls + catalog.annotationCalls + catalog.spliceCalls);
    }
}
