package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class GeneralBp3RuntimeTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ProteinLengthChangeMaterialSupplyTest variant;
    ProteinLengthRegionFactSupply facts;
    Bp3ProteinRegionAssessmentTest knowledge;
    void setup() throws Exception {
        variant = new ProteinLengthChangeMaterialSupplyTest(); variant.root = Files.createDirectory(root.resolve("variant"));
        variant.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var source = new UniProtProteinRegionSupplyTest(); source.root = Files.createDirectory(root.resolve("source"));
        var raw = source.raw("P12345"); raw.putArray("features").add(source.feature("Repeat", 1, 6));
        var uni = source.supply(raw); var mapped = uni.mapModel(variant.data.model, 2, 3);
        var doc = GeneralPm4TestSupport.combined(mapper, mapped); byte[] bytes = mapper.writeValueAsBytes(doc);
        var combinedPath = root.resolve("combined.json"); Files.write(combinedPath, bytes);
        facts = new ProteinLengthRegionFactSupply(mapper, variant.prebuilt(), uni,
                new CombinedProteinRegionSupply(mapper, combinedPath, CodingTranscriptMaterialSupply.sha(bytes)), null);
        var actual = facts.resolve(variant.data.context, variant.data.candidate, true);
        assertTrue(actual.material().ready(), actual.snapshot().toPrettyString());
        knowledge = new Bp3ProteinRegionAssessmentTest(); knowledge.root = root;
        knowledge.context = variant.data.context; knowledge.model = actual.material().model();
        knowledge.annotations = (ObjectNode) actual.snapshot().path("proteinAnnotations");
        knowledge.extra = (ObjectNode) actual.snapshot().path("combinedRegions");
    }
    PredictionSpecializationGuard guard(boolean known) throws Exception {
        var doc = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        doc.putObject("metadata").put("status", "COMPLETE").put("entryCount", 1).put("snapshotId", "SYNTHETIC_TEST_ONLY");
        doc.putArray("entries").addObject().putObject("scope").putArray("hgncIds").add(known ? "HGNC:4827" : "HGNC:999999");
        byte[] bytes = mapper.writeValueAsBytes(doc); var file = root.resolve("catalog.json"); Files.write(file, bytes);
        return PredictionSpecializationGuard.load(mapper, file, CodingTranscriptMaterialSupply.sha(bytes));
    }
    @ParameterizedTest @CsvSource({"NONE,NOT_EVALUATED,NONE", "NO_KNOWN_FUNCTION,MET,SUPPORTING", "KNOWN_FUNCTION,NOT_MET,NONE"})
    void commonSequenceFlowRetainsIndependentFunctionalOutcomes(String function, String status, String strength) throws Exception {
        setup(); var assessment = new Bp3ProteinRegionAssessment(mapper);
        if (!function.equals("NONE")) {
            var doc = knowledge.qualified(); ((ObjectNode)doc.at("/records/0/intervals/0")).put("start",1).put("end",6).put("functionalStatus",function);
            assessment = knowledge.accepted(doc);
        }
        var engine = new GeneralBp3Runtime(mapper, facts, assessment, guard(false));
        var before = mapper.valueToTree(variant.data.candidate); var contextBefore = variant.data.context.deepCopy();
        var out = engine.evaluate(variant.data.context, variant.data.candidate, mapper.createObjectNode());
        assertEquals(status, out.path("status").asText(), out.toPrettyString());
        assertEquals(strength, out.at("/evaluation/appliedStrength").asText());
        assertEquals("ACMG_AMP_2015:BP3", out.at("/evaluation/criterionId").asText());
        assertEquals(before, mapper.valueToTree(variant.data.candidate)); assertEquals(contextBefore, variant.data.context);
        assertFalse(out.path("publicSummary").toString().contains("cdsSequence"));
        assertArrayEquals(mapper.writeValueAsBytes(out), mapper.writeValueAsBytes(mapper.readTree(mapper.writeValueAsBytes(out))));
        String output = System.getProperty("varvis.bp3.artifacts");
        if (output != null) { Path p = Files.createDirectories(Path.of(output)); mapper.writeValue(p.resolve(function+".json").toFile(), out); }
    }
    @Test void knownSpecializationStopsBeforeSourceConsumption() throws Exception {
        setup(); var engine = new GeneralBp3Runtime(mapper, facts, new Bp3ProteinRegionAssessment(mapper), guard(true));
        var out = engine.evaluate(variant.data.context, variant.data.candidate, mapper.createObjectNode());
        assertEquals("BP3_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION",out.path("reasonCode").asText());
        assertFalse(out.has("sequenceMaterial"));
    }
    @Test void changedCombinedNativeBytesAreNotBorrowedFromPm4OrApprovedAssertions() throws Exception {
        setup(); var doc = knowledge.qualified(); var engine = new GeneralBp3Runtime(mapper, facts, knowledge.accepted(doc), guard(false));
        Files.writeString(root.resolve("combined.json"), "{}");
        var out = engine.evaluate(variant.data.context, variant.data.candidate, mapper.createObjectNode());
        assertEquals("NOT_EVALUATED", out.path("status").asText()); assertEquals("PM4_COMBINED_BYTES_CHANGED", out.path("reasonCode").asText());
    }
}
