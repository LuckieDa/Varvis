package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DeNovoSelectedBatchTest {
    @TempDir Path temporary;
    @ParameterizedTest @ValueSource(strings={"confirmed", "assumed", "father-missing", "inherited"})
    void eachSingleCriterionMatchesSharedDecisionWithoutForcingTheRequestedAward(String scenario) throws Exception {
        var f = new DeNovoBatchFixture(temporary);
        if (scenario.equals("assumed")) f.report.putArray("parentage");
        if (scenario.equals("father-missing")) ((ArrayNode) f.report.path("tests")).remove(2);
        if (scenario.equals("inherited")) ((ObjectNode) f.report.at("/tests/2")).put("result", "PRESENT");
        f.save(); var all = f.load().evaluateBound(f.context(), f.mapper.createObjectNode());
        for (String code : List.of("PS2", "PM6")) {
            var one = f.load().evaluateBound(f.context(), f.mapper.createObjectNode(), List.of("ACMG_AMP_2015:" + code));
            assertEquals(1, one.path("criteria").size());
            assertEquals(all.at("/criteria/" + code), one.at("/criteria/" + code));
            assertEquals(all.path("qualifiedResult"), one.path("qualifiedResult"), "Selection cannot change shared scientific calculation");
        }
    }
    @ParameterizedTest @ValueSource(strings={"confirmed", "assumed", "father-missing", "inherited"})
    void frozenSharedOutcomes(String scenario) throws Exception {
        var f = new DeNovoBatchFixture(temporary);
        if (scenario.equals("assumed")) f.report.putArray("parentage");
        if (scenario.equals("father-missing")) ((ArrayNode) f.report.path("tests")).remove(2);
        if (scenario.equals("inherited")) ((ObjectNode) f.report.at("/tests/2")).put("result", "PRESENT");
        f.save(); var module = f.load(); var result = module.evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals(1, module.evaluations());
        String chosen = scenario.equals("confirmed") ? "PS2" : scenario.equals("assumed") ? "PM6" : "NONE";
        String expected = scenario.equals("inherited") ? "NOT_MET" : scenario.equals("father-missing") ? "NOT_EVALUATED" : "MET";
        assertEquals(chosen, result.at("/qualifiedResult/result/code").asText(), result.toPrettyString());
        assertEquals(expected, result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
        long met = result.path("criteria").findValues("status").stream().filter(n -> "MET".equals(n.asText())).count();
        assertEquals(expected.equals("MET") ? 1 : 0, met);
        for (var event : result.at("/qualifiedResult/result/events")) for (var ref : event.path("sourceRefs")) {
            String[] parts = ref.asText().split("#", 2); var bytes = Base64.getDecoder().decode(module.originals().path(parts[0]).asText());
            assertEquals(parts[0], DeNovoBatchFixture.sha(bytes));
            assertFalse(f.mapper.readTree(bytes).at(parts.length == 2 ? parts[1] : "").isMissingNode());
        }
    }
    @ParameterizedTest @ValueSource(strings={"SPECIALIZED_UNIMPLEMENTED", "SPECIALIZED_IMPLEMENTED", "SPECIALIZED_UNRESOLVED", "SPECIALIZED_VERSION_CONFLICT"})
    void applicableSpecializedRuleNeverFallsBackToGeneric(String disposition) throws Exception {
        var f = new DeNovoBatchFixture(temporary); ((ObjectNode) f.catalog.at("/entries/1")).put("disposition", disposition); f.save();
        var runtime = f.load(); var result = runtime.evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals(0, runtime.evaluations());
        assertEquals("NOT_EVALUATED", result.at("/criteria/PS2/evaluationResult/status").asText());
        assertEquals("NOT_EVALUATED", result.at("/criteria/PM6/evaluationResult/status").asText());
        assertTrue(result.toString().contains("SPECIALIZED"));
    }
    @Test void unknownCatalogScopeAndAmbiguityBlockBeforeCore() throws Exception {
        var f = new DeNovoBatchFixture(temporary); var context = f.context();
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0099999");
        context.put("contextId", new RuntimeEvidenceV2Adapter(f.mapper).canonicalVersionedContextId(context));
        var runtime = f.load(); var result = runtime.evaluateBound(context, f.mapper.createObjectNode());
        assertTrue(result.toString().contains("CLAUSE_SEARCH_SCOPE_NOT_COVERED")); assertEquals(0, runtime.evaluations());
        var duplicate = ((ObjectNode) f.catalog.at("/entries/0")).deepCopy().put("entryId", "conflict");
        ((ArrayNode) f.catalog.path("entries")).add(duplicate); f.save();
        assertTrue(f.load().evaluateBound(f.context(), f.mapper.createObjectNode()).toString().contains("CLAUSE_SELECTION_CONFLICT"));
    }
    @Test void syntheticCannotLoadForResearchOrEvaluatePatient() throws Exception {
        var f = new DeNovoBatchFixture(temporary);
        assertThrows(IllegalArgumentException.class, () -> f.load(DeNovoApplicabilityPolicy.Use.RESEARCH));
        var c = f.context(); c.put("dataClass", "PATIENT");
        assertTrue(f.load().evaluateBound(c, f.mapper.createObjectNode()).toString().contains("DENOVO_SYNTHETIC_MATERIAL_CONTEXT_FORBIDDEN"));
    }
    @Test void tamperingAndPathEscapeAreRejectedWhileLoadedSnapshotIsImmutable() throws Exception {
        var f = new DeNovoBatchFixture(temporary); var loaded = f.load(); var original = loaded.originals(); original.removeAll();
        assertFalse(loaded.originals().isEmpty()); Files.writeString(temporary.resolve("report.json"), "{}");
        assertThrows(IllegalArgumentException.class, f::load);
        assertEquals("MET", loaded.evaluateBound(f.context(), f.mapper.createObjectNode()).at("/criteria/PS2/evaluationResult/status").asText());
        ((ObjectNode) f.manifest.at("/reports/0")).put("path", "../outside.json"); f.writeManifest();
        assertThrows(IllegalArgumentException.class, f::load);
    }
    @Test void configuredRootResolvesPinnedSharedFilesAndContainsTheManifest() throws Exception {
        Path root = temporary.resolve("local");
        var f = new DeNovoBatchFixture(root.resolve("materials"));
        var expected = f.load().evaluateBound(f.context(), f.mapper.createObjectNode());
        Path shared = Files.createDirectory(root.resolve("library"));
        Files.move(f.root.resolve("source.txt"), shared.resolve("source.txt"));
        ((ObjectNode) f.manifest.at("/sources/0")).put("path", "library/source.txt");
        for (String pointer : List.of("/reports/0", "/phenotypePolicies/0", "/catalog", "/selectorPolicies")) {
            var binding = (ObjectNode) f.manifest.at(pointer);
            binding.put("path", "materials/" + binding.path("path").asText());
        }
        f.writeManifest();

        var relocated = loadFromRoot(f, root).evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals(expected.path("qualifiedResult"), relocated.path("qualifiedResult"));
        assertEquals(expected.path("criteria"), relocated.path("criteria"));
        assertThrows(NoSuchFileException.class, f::load);
        assertThrows(IllegalArgumentException.class, () -> loadFromRoot(f, shared));
        Files.writeString(shared.resolve("source.txt"), "changed");
        assertThrows(IllegalArgumentException.class, () -> loadFromRoot(f, root));
    }
    @Test void configuredRootRejectsAbsoluteBindingsAndTraversal() throws Exception {
        Path root = temporary.resolve("local");
        var f = new DeNovoBatchFixture(root.resolve("materials"));
        Path outside = Files.move(f.root.resolve("source.txt"), temporary.resolve("outside.txt"));
        for (String path : List.of(outside.toString(), "../outside.txt")) {
            ((ObjectNode) f.manifest.at("/sources/0")).put("path", path);
            f.writeManifest();
            var failure = assertThrows(IllegalArgumentException.class, () -> loadFromRoot(f, root));
            assertEquals("DENOVO_MATERIAL_PATH_OUTSIDE_ROOT", failure.getMessage());
        }
    }
    private static DeNovoSelectedBatch loadFromRoot(DeNovoBatchFixture fixture, Path root) throws Exception {
        Path manifest = fixture.root.resolve("manifest.json");
        return DeNovoSelectedBatch.load(fixture.mapper, manifest, DeNovoBatchFixture.sha(Files.readAllBytes(manifest)),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, null, null, root);
    }
    @Test void materialIdentityChangesAndOldProfileIsUnchangedWithoutOptIn() throws Exception {
        var f = new DeNovoBatchFixture(temporary); var old = f.load().identity();
        f.report.put("reportVersion", "2"); f.save(); assertNotEquals(old, f.load().identity());
        var binding = f.mapper.createObjectNode(); var legacy = AcmgExecutionProfile.localBatch(f.mapper, binding);
        assertFalse(legacy.criteria().contains("ACMG_AMP_2015:PS2")); binding.set("deNovo", f.load().identity());
        var profile = AcmgExecutionProfile.localBatch(f.mapper, binding);
        assertTrue(profile.criteria().containsAll(DeNovoSelectedBatch.CRITERIA));
        assertNotEquals(profile.document(), legacy.document());
    }
    @Test void declaredCatalogSourceNeedsRawBytesAndSameSelectedCalculationBinding() throws Exception {
        var f = new DeNovoBatchFixture(temporary);
        ((ObjectNode) f.catalog.at("/sources/0")).put("sha256", "a".repeat(64)); f.save();
        assertThrows(IllegalArgumentException.class, f::load);
    }
    @Test void combinedCatalogDoesNotWidenLegacySelectorModes() throws Exception {
        var f = new DeNovoBatchFixture(temporary); var hash = new RuntimeEvidenceV2Adapter(f.mapper).canonicalSha256(f.catalog);
        var policies = (ArrayNode) f.selectorPolicies.path("policies");
        assertNotNull(new AcmgSpecificationSelector(f.mapper, f.catalog, hash, policies).configurationIssue());
        assertNotNull(AcmgSpecificationSelector.forPs2Versioned(f.mapper, f.catalog, hash, policies).configurationIssue());
        assertNotNull(AcmgSpecificationSelector.forPm6Versioned(f.mapper, f.catalog, hash, policies).configurationIssue());
        assertNull(AcmgSpecificationSelector.forDeNovoSelected(f.mapper, f.catalog, hash, policies).configurationIssue());
    }
}
