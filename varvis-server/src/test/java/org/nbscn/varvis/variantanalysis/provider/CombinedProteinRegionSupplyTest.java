package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CombinedProteinRegionSupplyTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ObjectNode annotations, doc;
    void setup() throws Exception {
        var f = new UniProtProteinRegionSupplyTest(); f.root = Files.createDirectory(root.resolve("source"));
        annotations = f.supply(f.raw("P12345")).mapModel(f.model(), 2, 3);
        doc = GeneralPm4TestSupport.combined(mapper, annotations);
        var region = ((ObjectNode)doc.at("/results/0/facts")).withArray("regions").addObject().put("source", "InterPro")
                .put("entry", "IPR000001").put("category", "REPEAT").put("kind", "repeat").put("locator", "/results/0/proteins/0/entry_protein_locations/0");
        region.putObject("metadata").put("type", "repeat").put("accession", "IPR000001");
        region.putArray("fragments").addObject().put("start", 2).put("end", 3).put("discontinuity", "CONTINUOUS");
        region.putObject("raw").putArray("fragments").addObject().put("start", 2).put("end", 3).put("dc-status", "CONTINUOUS");
    }
    CombinedProteinRegionSupply supply() throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(doc); Path file = root.resolve("combined.json"); Files.write(file, bytes);
        return new CombinedProteinRegionSupply(mapper, file, CodingTranscriptMaterialSupply.sha(bytes));
    }
    @Test void actualFragmentsOverlapWithoutInferringNegativeFromOtherRegions() throws Exception {
        setup(); var out = supply().resolve(annotations);
        assertEquals("READY", out.path("status").asText(), out.toString()); assertEquals(1, out.path("repeatOverlaps").size());
        ((ObjectNode)annotations.path("queryRange")).put("start", 5).put("end", 6);
        out = supply().resolve(annotations); assertTrue(out.path("repeatOverlaps").isEmpty()); assertFalse(out.path("nonRepeatQualified").asBoolean(true));
    }
    @Test void bp3ProjectionPreservesDomainLeadAndNativeFragmentButDoesNotChangePm4Projection() throws Exception {
        setup(); var region = (ObjectNode) doc.at("/results/0/facts/regions/0");
        region.put("kind", "domain").put("category", "DOMAIN_LEAD"); ((ObjectNode)region.path("metadata")).put("type", "domain");
        var supply = supply(); var pm4 = supply.resolve(annotations); var bp3 = supply.resolveForBp3(annotations);
        assertEquals("READY", bp3.path("status").asText()); assertTrue(bp3.path("repeatOverlaps").isEmpty());
        assertEquals(1, bp3.path("functionalLeads").size()); assertEquals("domain", bp3.at("/functionalLeads/0/kind").asText());
        assertEquals(region.at("/raw/fragments/0"), bp3.at("/functionalLeads/0/raw"));
        assertFalse(pm4.has("functionalLeads")); assertEquals(pm4, supply.resolve(annotations));
        ((ObjectNode)region.at("/raw/fragments/0")).put("start", 1);
        assertEquals("UNAVAILABLE", supply().resolveForBp3(annotations).path("status").asText());
    }
    @Test void familyMembershipIsNotFabricatedAsALocalFunctionalSite() throws Exception {
        setup(); var region = (ObjectNode) doc.at("/results/0/facts/regions/0");
        region.put("kind", "family").put("category", "PROTEIN_CONTEXT"); ((ObjectNode)region.path("metadata")).put("type", "family");
        var out = supply().resolveForBp3(annotations);
        assertEquals("READY", out.path("status").asText()); assertTrue(out.path("functionalLeads").isEmpty());
        assertEquals("family", out.at("/entryContexts/0/kind").asText());
    }
    @Test void agreeingButInvalidNativeDiscontinuityAndMissingLocatorDoNotQualifyBp3() throws Exception {
        setup(); var region = (ObjectNode) doc.at("/results/0/facts/regions/0");
        ((ObjectNode)region.at("/fragments/0")).put("discontinuity", "UNKNOWN");
        ((ObjectNode)region.at("/raw/fragments/0")).put("dc-status", "UNKNOWN");
        assertEquals("BP3_COMBINED_DISCONTINUITY_INVALID", supply().resolveForBp3(annotations).path("reasonCode").asText());
        region.remove("locator"); assertEquals("BP3_COMBINED_SOURCE_LOCATOR_INVALID", supply().resolveForBp3(annotations).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"protein", "uniprot", "length", "raw-start", "raw-type", "raw-accession", "large", "discontinuity", "duplicate", "bytes"})
    void exactIdentityAndNativeProjectionAreRequired(String fault) throws Exception {
        setup(); var facts = (ObjectNode)doc.at("/results/0/facts"); var region = (ObjectNode)facts.at("/regions/0");
        switch (fault) {
            case "protein" -> facts.put("sequenceSha256", "b".repeat(64));
            case "uniprot" -> ((ObjectNode)facts.at("/sources/pins/uniprot")).put("sha256", "b".repeat(64));
            case "length" -> facts.put("length", 99);
            case "raw-start" -> ((ObjectNode)region.at("/raw/fragments/0")).put("start", 1);
            case "raw-type" -> ((ObjectNode)region.path("metadata")).put("type", "domain");
            case "raw-accession" -> ((ObjectNode)region.path("metadata")).put("accession", "IPR999999");
            case "large" -> ((ObjectNode)region.at("/fragments/0")).put("end", 4294967299L);
            case "discontinuity" -> ((ObjectNode)region.at("/fragments/0")).put("discontinuity", "ALTERED");
            case "duplicate" -> doc.withArray("results").add(doc.at("/results/0").deepCopy());
            case "bytes" -> { }
            default -> fail();
        }
        var supply = supply(); if (fault.equals("bytes")) Files.writeString(root.resolve("combined.json"), "{}");
        assertEquals("UNAVAILABLE", supply.resolve(annotations).path("status").asText());
    }
}
