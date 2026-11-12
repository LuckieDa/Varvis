package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pm4MaterialPreparationTest {
    @TempDir Path temp;
    @Test void repeatablePreparedArtifactsLoadWithoutChangingModelReviewOrPolicy() throws Exception {
        var f = new Pm4PreparationFixtures(temp.resolve("inputs"));
        var a = f.prepare(temp.resolve("a")); var b = f.prepare(temp.resolve("b"));
        assertTrue(a.ready(), a.report().toPrettyString()); assertTrue(b.ready());
        assertEquals(a.report().path("bundleSha256"), b.report().path("bundleSha256"));
        var bundle = ReviewedRuleBundle.load(f.mapper, temp.resolve("a/bundle.json"), a.report().path("bundleSha256").asText()); assertTrue(bundle.supportsPm4());
        assertEquals(f.fixtures.get(0).model, f.mapper.readTree(temp.resolve("a/model-0.json").toFile()));
        var doc = f.mapper.readTree(temp.resolve("a/bundle.json").toFile());
        assertEquals(f.fixtures.get(0).policy, doc.path("policies").get(0));
        assertFalse(a.report().path("scientificAdoption").asBoolean(true));
        assertThrows(IllegalArgumentException.class, () -> f.prepare(temp.resolve("a")));
        String artifacts = System.getProperty("varvis.pm4.preparation.inputs");
        if (artifacts != null) new Pm4PreparationFixtures(Path.of(artifacts));
    }
    @ParameterizedTest @ValueSource(strings={"MODEL_BYTES", "SOURCE_BYTES", "CANONICAL", "ASSEMBLY", "GENE", "MOI", "VERSION", "UNREVIEWED", "NO_APPROVAL", "SEARCH", "POLICY_BINDING", "STALE_REVIEW", "OVERLAP", "SOURCE_CONTENT", "REGION_UNKNOWN_UNREVIEWED", "MODEL_MISSING", "DUPLICATE_JSON", "LIMIT"})
    void integrityAndReviewFailuresCannotPublish(String fault) throws Exception {
        var f = new Pm4PreparationFixtures(temp.resolve("inputs")); String expected;
        switch (fault) {
            case "MODEL_BYTES", "SOURCE_BYTES" -> { Files.writeString(f.root.resolve(fault.equals("MODEL_BYTES") ? "model-0.json" : "source-0.json"), " ", StandardOpenOption.APPEND); expected = "INPUT_RAW_SHA_MISMATCH"; }
            case "CANONICAL" -> { ((ObjectNode) f.manifest.at("/backgrounds/0/model")).put("canonicalSha256", "f".repeat(64)); expected = "INPUT_CANONICAL_SHA_MISMATCH"; }
            case "ASSEMBLY", "GENE", "MOI", "VERSION" -> {
                ((ObjectNode) f.manifest.at("/backgrounds/0/background")).put(switch (fault) { case "ASSEMBLY" -> "assembly"; case "GENE" -> "hgncId"; case "MOI" -> "moi"; default -> "transcript"; },
                        switch (fault) { case "ASSEMBLY" -> "GRCh37"; case "GENE" -> "HGNC:999"; case "MOI" -> "AUTOSOMAL_DOMINANT"; default -> "ENST00000647020.2"; });
                expected = fault.equals("ASSEMBLY") ? "BACKGROUND_ASSEMBLY_UNSUPPORTED" : fault.equals("MOI") ? "POLICY_BACKGROUND_IDENTITY_MISMATCH" : "MODEL_BACKGROUND_IDENTITY_MISMATCH";
            }
            case "UNREVIEWED" -> { f.approval.put("status", "PENDING"); f.manifest.set("approval", f.pinned("approval.json", f.approval)); expected = "PREPARATION_REVIEW_REQUIRED"; }
            case "NO_APPROVAL" -> { f.manifest.remove("approval"); expected = "APPROVAL_INPUT_MISSING"; }
            case "SEARCH", "POLICY_BINDING" -> {
                if (fault.equals("SEARCH")) ((ObjectNode) f.catalog.at("/entries/0")).put("searchStatus", "INCOMPLETE");
                else ((ObjectNode) f.catalog.at("/entries/0/policyBinding")).put("policySha256", "f".repeat(64));
                f.manifest.set("catalog", f.pinned("catalog.json", f.catalog)); expected = fault.equals("SEARCH") ? "CATALOG_SEARCH_INCOMPLETE" : "CATALOG_POLICY_BINDING_MISMATCH";
            }
            case "STALE_REVIEW" -> { ((ObjectNode) f.approval.at("/bindings/0")).put("modelSha256", "f".repeat(64)); f.manifest.set("approval", f.pinned("approval.json", f.approval)); expected = "REVIEW_MATERIAL_BINDING_MISMATCH"; }
            case "OVERLAP" -> { f.fixtures.get(0).regions.withArray("intervals").addObject().put("start", 105).put("end", 106).put("repeatStatus", "REPEAT").put("functionalContext", "SUPPORTED"); f.publishReviewedInputs(); expected = "REGION_OVERLAP_CONFLICT"; }
            case "SOURCE_CONTENT" -> {
                var a = (ObjectNode) f.mapper.readTree(f.root.resolve("assertions-0.json").toFile());
                ((ObjectNode) a.at("/assertions/0")).put("repeatStatus", "REPEAT");
                ((ObjectNode) f.manifest.at("/backgrounds/0")).set("regionAssertions", f.pinned("assertions-0.json", a));
                expected = "REGION_SOURCE_CONTENT_CONFLICT";
            }
            case "REGION_UNKNOWN_UNREVIEWED" -> {
                ((ObjectNode) f.fixtures.get(0).regions.at("/intervals/0")).put("repeatStatus", "UNKNOWN"); f.publishReviewedInputs();
                ((ObjectNode) f.approval.at("/bindings/0")).put("allowUnknownCoverage", false); f.manifest.set("approval", f.pinned("approval.json", f.approval)); expected = "REGION_UNKNOWN_COVERAGE_NOT_REVIEWED";
            }
            case "MODEL_MISSING" -> { ((ObjectNode) f.manifest.at("/backgrounds/0/model")).put("path", "absent.json"); expected = "INPUT_FILE_MISSING"; }
            case "DUPLICATE_JSON" -> {
                Path file = f.root.resolve("model-0.json"); Files.writeString(file, "{\"schemaVersion\":\"one\",\"schemaVersion\":\"two\"}");
                ((ObjectNode) f.manifest.at("/backgrounds/0/model")).put("rawSha256", Pm4MaterialPreparation.sha(Files.readAllBytes(file))); expected = "INPUT_JSON_INVALID_OR_DUPLICATE_KEY";
            }
            case "LIMIT" -> { Files.write(f.root.resolve("model-0.json"), new byte[4_000_001]); expected = "INPUT_SIZE_LIMIT"; }
            default -> throw new IllegalArgumentException(fault);
        }
        f.saveManifest(); var result = f.prepare(temp.resolve("out"));
        assertFalse(result.ready()); assertFalse(Files.exists(temp.resolve("out/bundle.json")));
        String actual = result.report().at("/backgrounds/0/reasonCode").asText(result.report().path("reasonCode").asText());
        assertEquals(expected, actual, result.report().toPrettyString());
        assertTrue(Files.exists(temp.resolve("out/artifact-manifest.json")));
    }
}
