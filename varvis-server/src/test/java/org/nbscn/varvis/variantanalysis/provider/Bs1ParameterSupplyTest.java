package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

public class Bs1ParameterSupplyTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    final Bs1KnowledgeCatalogTest factory = new Bs1KnowledgeCatalogTest();
    public static ObjectNode background(ObjectMapper mapper, String gene, String disease, String mode) {
        var b = mapper.createObjectNode().put("hgncId", gene).put("mondoId", disease).put("moi", mode)
                .put("reasonCode", "BS1_DISEASE_PARAMETERS_MISSING");
        b.putArray("assertions").addObject().put("hgncId", gene).put("mondoId", disease).put("moi", mode)
                .put("classification", "Strong").put("sourceId", "synthetic-parameters");
        var s = b.putObject("parameterSupply").put("schemaVersion", Bs1ParameterSupply.VERSION)
                .put("sourceScope", "ONLY_PINNED_LOADED_INPUTS").put("modelExecutable", false).put("scientificallyAdopted", false);
        s.putObject("scope").put("hgncId", gene).put("mondoId", disease).put("moi", mode);
        s.putArray("blockingReasons").add("CROSS_PARAMETER_APPLICABILITY_AND_ADOPTION_NOT_ASSESSED");
        var fields = s.putObject("fields");
        for (String name : Bs1ParameterSupply.FIELDS) {
            boolean prevalence = name.equals("prevalence");
            var f = fields.putObject(name).put("status", prevalence ? "CANDIDATE_AVAILABLE" : "SOURCE_GAP")
                    .put("structuredStatus", prevalence ? "CANDIDATE_AVAILABLE" : "SOURCE_GAP")
                    .put("observationCount", prevalence ? 1 : 0).put("textProposalCount", 0)
                    .put("evidenceCount", prevalence ? 1 : 0).put("omittedEvidenceCount", 0);
            f.putArray("textStatuses"); f.putArray("reasonCodes"); var evidence = f.putArray("evidence");
            if (prevalence) {
                var e = evidence.addObject().put("factId", "b".repeat(64)).put("locator", "SYNTHETIC:prevalence")
                        .put("kind", "PREPARATION_OBSERVATION").put("valueKind", "POINT").put("valueText", "0.001")
                        .put("statistic", "POINT_PREVALENCE").put("population", "SYNTHETIC_POPULATION");
                e.putObject("source").put("sourceId", "synthetic-parameters").put("url", "https://example.org/synthetic")
                        .put("version", "SYNTHETIC-1").put("sha256", "a".repeat(64));
            }
        }
        return b;
    }
    ObjectNode bundle() {
        var b = factory.bundle(); b.withArray("/backgrounds").add(background(mapper, "HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT")); return b;
    }
    Bs1KnowledgeCatalog load(ObjectNode b) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(b); Path file = temporary.resolve("knowledge.json"); Files.write(file, raw);
        return Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(raw));
    }
    @Test void explanationIsBoundNonExecutableAndCannotMutateCatalog() throws Exception {
        var catalog = load(bundle()); var gap = catalog.explain("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT");
        assertEquals("BS1_DISEASE_PARAMETERS_MISSING", gap.path("reasonCode").asText());
        assertFalse(gap.at("/parameterSupply/modelExecutable").asBoolean(true));
        assertEquals("CANDIDATE_AVAILABLE", gap.at("/parameterSupply/fields/prevalence/status").asText());
        gap.withObject("/parameterSupply/fields/prevalence").put("status", "SOURCE_GAP");
        assertEquals("CANDIDATE_AVAILABLE", catalog.explain("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT").at("/parameterSupply/fields/prevalence/status").asText());
        assertFalse(catalog.explain("HGNC:999001", "MONDO:0999002", "AUTOSOMAL_DOMINANT").has("parameterSupply"));
        assertTrue(catalog.merge(List.of()).isEmpty());
    }
    @Test void legacyAbsenceAndChangedSummaryHaveDistinctSnapshotIdentity() throws Exception {
        var b = bundle(); var first = load(b).identity();
        ((ObjectNode)b.at("/backgrounds/0/parameterSupply/fields/prevalence/evidence/0")).put("valueText", "0.002");
        assertNotEquals(first, load(b).identity());
        ((ObjectNode)b.at("/backgrounds/0")).remove("parameterSupply");
        var legacy = load(b); assertFalse(legacy.identity().has("parameterSupplyVersion"));
        assertEquals("NOT_RECORDED_IN_THIS_SNAPSHOT", legacy.explain("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT").path("parameterSupplyStatus").asText());
    }
    @ParameterizedTest @ValueSource(strings={"SCOPE", "SOURCE", "EXECUTABLE", "ADOPTED", "FIELD", "COUNT", "STATE", "VERSION"})
    void damagedSummaryIsRejected(String fault) throws Exception {
        var b = bundle(); var s = (ObjectNode)b.at("/backgrounds/0/parameterSupply");
        switch (fault) {
            case "SCOPE" -> s.withObject("/scope").put("mondoId", "MONDO:0999002");
            case "SOURCE" -> s.withObject("/fields/prevalence/evidence/0/source").put("sha256", "c".repeat(64));
            case "EXECUTABLE" -> s.put("modelExecutable", true);
            case "ADOPTED" -> s.put("scientificallyAdopted", true);
            case "FIELD" -> s.withObject("/fields").remove("penetrance");
            case "COUNT" -> s.withObject("/fields/prevalence").put("omittedEvidenceCount", 9);
            case "STATE" -> s.withObject("/fields/prevalence").put("status", "READY");
            case "VERSION" -> s.put("schemaVersion", "unknown");
            default -> throw new AssertionError(fault);
        }
        assertThrows(IllegalArgumentException.class, () -> load(b));
    }
    @Test void summaryDoesNotChangeAdoptedPolicyOrExpertPriority() throws Exception {
        var record = factory.expertRecord("HGNC:999001", "MONDO:0999001", "SYNTHETIC");
        var b = factory.bundle(record); var original = load(b).merge(List.of());
        b.withArray("/backgrounds").add(background(mapper, "HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT"));
        assertEquals(original, load(b).merge(List.of()));
        b.putArray("expertScopes").addObject().put("hgncId", "HGNC:999001").put("mondoId", "MONDO:0999001")
                .put("specificationId", "GN900").put("version", "1").put("sourceId", "synthetic-parameters").put("status", "STRUCTURING_REQUIRED");
        assertEquals("BS1_EXPERT_SPECIFICATION_REQUIRES_STRUCTURING", load(b).explain("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT").path("reasonCode").asText());
    }
    @Test void configuredSummaryExplainsEveryBackgroundWithoutChangingAnyPolicy() throws Exception {
        String file = System.getProperty("bs1.knowledge.test-file", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(file.isBlank(), "No task snapshot requested");
        var current = Bs1KnowledgeCatalog.load(Path.of(file), System.getProperty("bs1.knowledge.test-sha"));
        Path previousPath = Path.of(System.getProperty("bs1.knowledge.previous-file"));
        var previous = Bs1KnowledgeCatalog.load(previousPath, Bs1KnowledgeCatalog.sha(Files.readAllBytes(previousPath)));
        assertEquals(previous.merge(List.of()), current.merge(List.of()));
        assertEquals(previous.relationships(), current.relationships());
        var doc = mapper.readTree(Path.of(file).toFile()); int count = 0;
        for (var row : doc.path("backgrounds")) {
            String gene = row.path("hgncId").asText(), disease = row.path("mondoId").asText(), mode = row.path("moi").asText();
            var gap = current.explain(gene, disease, mode);
            assertEquals(row.path("parameterSupply"), gap.path("parameterSupply"));
            assertEquals(previous.explain(gene, disease, mode).path("reasonCode"), gap.path("reasonCode")); count++;
        }
        assertEquals(3669, count);
    }
}
