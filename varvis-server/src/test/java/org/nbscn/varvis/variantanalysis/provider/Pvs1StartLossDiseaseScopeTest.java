package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossDiseaseScopeTest {
    @TempDir Path temporary;
    final Pvs1StartLossExpertFactsTest helper = new Pvs1StartLossExpertFactsTest();
    ObjectNode record() {
        var r = helper.mapper.createObjectNode().put("recordId", "G2P00001:1");
        r.putObject("sourceRecord").put("gene symbol", "TEST").put("hgnc id", "1")
                .put("disease MONDO", "MONDO:0000001").put("allelic requirement", "monoallelic_autosomal")
                .put("confidence", "definitive").put("molecular mechanism support", "inferred")
                .put("date of last review", "2026-01-01 00:00:00+00:00");
        return r;
    }
    @ParameterizedTest @ValueSource(strings={"missing", "null", "unknown", "empty", "conflict", "otherModes", "otherDisease", "otherGene", "otherClause", "mixedRecords", "restricted", "limited", "wrongContext", "wrongTranscript", "productionSynthetic"})
    void onlyMissingInheritanceCanBeJoinedWithoutChangingOriginalOrGrantingMechanism(String change) {
        var c = helper.context().put("dataClass", "SYNTHETIC");
        var e = new Pvs1StartLossPublishedRuleTest().entity("PVS1_Supporting is applicable for initiation codon variants in TEST.");
        var disease = (ObjectNode) e.at("/ld/RuleSet/0/entContent/genes/0/diseases/0");
        disease.remove("preferredModeOfInheritance"); var r = record(); var records = new java.util.ArrayList<ObjectNode>(); records.add(r);
        switch (change) {
            case "null" -> disease.putNull("preferredModeOfInheritance");
            case "unknown" -> disease.put("preferredModeOfInheritance", "Unknown");
            case "empty" -> disease.put("preferredModeOfInheritance", "");
            case "conflict" -> disease.put("preferredModeOfInheritance", "Autosomal recessive inheritance");
            case "otherModes" -> disease.putArray("modesOfInheritance").addObject().put("modeOfInheritance", "Autosomal recessive inheritance");
            case "otherDisease" -> disease.put("preferredMondoId", "MONDO:0000002");
            case "otherGene" -> ((ObjectNode) e.at("/ld/Gene/0/entContent/HGNC")).put("hgnc_id", "HGNC:2");
            case "otherClause" -> ((ObjectNode) e.at("/ld/CriteriaCode/0/entContent")).putArray("gene").add("OTHER");
            case "mixedRecords" -> { var other = record(); other.withObject("sourceRecord").put("allelic requirement", "biallelic_autosomal"); records.add(other); }
            case "restricted" -> r.withObject("sourceRecord").put("comments", "Only a subset");
            case "limited" -> r.withObject("sourceRecord").put("confidence", "limited");
            case "productionSynthetic" -> c.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
            default -> { }
        }
        var scope = new Pvs1StartLossDiseaseScope(helper.mapper, c, helper.mapper.createObjectNode().put("sourceDataClass", "SYNTHETIC"), records);
        if (change.equals("wrongContext")) c.withObject("disease").put("mondoId", "MONDO:0000002");
        if (change.equals("wrongTranscript")) c.withObject("transcript").put("version", "2");
        var original = e.deepCopy();
        var audit = Pvs1StartLossExpertFacts.read(helper.mapper, e, helper.scope(), c, scope);
        var rule = Pvs1StartLossPublishedRule.read(helper.mapper, e, c, audit);
        assertEquals(original, e);
        assertEquals(change.equals("missing") ? "QUALIFIED" : "UNRESOLVED", rule.path("status").asText(), audit.toPrettyString());
        if (change.equals("missing")) {
            assertEquals("QUALIFIED", audit.at("/inheritanceScopeJoin/status").asText());
            assertFalse(audit.at("/inheritanceScopeJoin/mechanismEstablished").asBoolean(true));
            assertFalse(audit.at("/inheritanceScopeJoin/originalSourceModified").asBoolean(true));
            assertEquals(r, audit.at("/inheritanceScopeJoin/records/0"));
        }
    }

    @Test void pinnedNativeDiseaseJoinCompilesOriginalClauseAndSurvivesSeparateProcessHistory() throws Exception {
        String configured = System.getProperty("pvs1.nativeExpertRoot"); org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        Path root = Path.of(configured); var props = new java.util.Properties();
        try (var reader = Files.newBufferedReader(root.resolve("local/runtime/bp7-evidence-workbench/evidence-overrides.properties"))) { props.load(reader); }
        String prefix = "variant-analysis.acmg-business.";
        var catalog = Pvs1KnowledgeCatalog.load(helper.mapper,
                Path.of(props.getProperty(prefix + "pvs1-knowledge-source-file")), props.getProperty(prefix + "pvs1-knowledge-source-sha256"),
                Path.of(props.getProperty(prefix + "pvs1-knowledge-adoption-file")), props.getProperty(prefix + "pvs1-knowledge-adoption-sha256"));
        var c = helper.context().put("dataClass", "PRIVACY_BOUNDED_RUNTIME").put("moi", "AUTOSOMAL_RECESSIVE");
        c.withObject("gene").put("hgncId", "HGNC:4136").put("symbol", "GAMT");
        c.withObject("disease").put("mondoId", "MONDO:0012999");
        c.withObject("transcript").put("accession", "NM_000156").put("version", 6);
        var guard = new Pvs1StartLossPublishedRuleTest().nativeGuard();
        var unjoined = guard.startLossAssertions(helper.mapper, c);
        assertEquals("UNRESOLVED", unjoined.at("/entries/0/startLossRule/status").asText());
        var joined = guard.startLossAssertions(helper.mapper, c, catalog.startLossDiseaseScope(c));
        assertEquals("QUALIFIED", joined.at("/entries/0/inheritanceScopeJoin/status").asText(), joined.toPrettyString());
        assertEquals("QUALIFIED", joined.at("/entries/0/startLossRule/status").asText(), joined.toPrettyString());
        assertEquals("MODERATE", joined.at("/entries/0/startLossRule/strength").asText());
        assertEquals(42, joined.at("/entries/0/startLossRule/requiredRestartProteinPosition").asInt());
        assertEquals(unjoined.path("sourceDocuments"), joined.path("sourceDocuments"));
        var view = joined.deepCopy(); view.remove("sourceDocuments");
        Pvs1StartLossAutomaticPreparation.summarizeExpertAssertions(helper.mapper, view);
        assertFalse(view.at("/entries/0/inheritanceScopeJoin/records/0").has("sourceRecord"));
        assertTrue(joined.at("/entries/0/inheritanceScopeJoin/records/0").has("sourceRecord"));
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(helper.mapper.writeValueAsString(view), helper.mapper.writeValueAsString(joined));
    }
}
