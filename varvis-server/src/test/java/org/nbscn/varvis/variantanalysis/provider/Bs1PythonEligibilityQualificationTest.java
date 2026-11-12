package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Actual existing Python CLI to Java catalog/qualification, with entirely synthetic source
 * and scientific reviews. This is cross-language engineering proof, not a genuine policy,
 * normal selected business, individual evidence score, store or patient analysis.
 */
@EnabledIfSystemProperty(named = "varvis.t03.bs1.python.qualified", matches = ".+")
class Bs1PythonEligibilityQualificationTest {
    private static final String SHA = "18bbf81dd7b8ae6b811ad48390b6b9e941c9c6795b594e2cffe57bcdf183a4cb";
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temporary;

    private Path source() {
        String configured = System.getProperty("varvis.t03.bs1.python.qualified");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("Explicit fixture path required");
        Path path = Path.of(configured);
        assertTrue(path.isAbsolute());
        return path;
    }

    @Test void actualPythonQualifiedBundleLoadsAndPreservesSourceBoundAcInJava() throws Exception {
        Path file = source();
        byte[] raw = Files.readAllBytes(file);
        assertEquals(SHA, Bs1KnowledgeCatalog.sha(raw));
        var original = mapper.readTree(raw).path("records").path(0);
        assertNull(Bs1KnowledgeQualification.issue(original));
        var catalog = Bs1KnowledgeCatalog.load(file, SHA);
        var policies = catalog.merge(List.of());
        assertEquals(1, policies.size());
        var policy = policies.get(0);
        assertFalse(policy.path("knowledgeDecision").has("reasonCode"), policy.path("knowledgeDecision").toString());
        assertEquals("EXPERT_POLICY", policy.at("/knowledgeDecision/basis").asText());
        assertNull(Bs1Policy.issue(policy));
        assertNull(Bs1SexChromosomePolicy.issue(policy));
        assertEquals(original.at("/expertPolicy/statistic/eligibleAc"), policy.at("/statistic/eligibleAc"));
        assertEquals("5", policy.at("/statistic/eligibleAc/decimal").asText());
        assertEquals("GTE", policy.at("/statistic/eligibleAc/operator").asText());
        assertEquals("bs1-sex-stratified-frequency-v2", catalog.identity().path("sexChromosomeStatisticVersion").asText());
        assertArrayEquals(raw, Files.readAllBytes(file));
    }

    @Test void recomputingBundleHashCannotRequalifyATamperedAcRequirement() throws Exception {
        var changed = (ObjectNode) mapper.readTree(Files.readAllBytes(source()));
        var record = (ObjectNode) changed.path("records").path(0);
        record.withObject("/expertPolicy/statistic/eligibleAc").put("decimal", "6");
        assertNotNull(Bs1KnowledgeQualification.issue(record));
        byte[] raw = mapper.writeValueAsBytes(changed);
        Path path = temporary.resolve("synthetic-tampered-bundle.json");
        Files.write(path, raw);
        var policies = Bs1KnowledgeCatalog.load(path, Bs1KnowledgeCatalog.sha(raw)).merge(List.of());
        assertEquals(1, policies.size());
        assertEquals("BS1_KNOWLEDGE_REVIEW_REQUIRED", policies.get(0).at("/knowledgeDecision/reasonCode").asText());
        assertNotNull(Bs1KnowledgeCatalog.issue(policies.get(0)));
        var failure = assertThrows(IllegalArgumentException.class, () -> Bs1KnowledgeCatalog.load(source(), "0".repeat(64)));
        assertEquals("BS1_KNOWLEDGE_SHA_MISMATCH", failure.getMessage());
    }
}
