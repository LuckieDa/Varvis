package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClinVarConditionIdentityTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void usesOnlyExplicitEquivalencesAndPreservesTheSource() throws Exception {
        var identity = load(ontology());
        var result = resolve(identity, "OMIM:123456", "C1234567:Unrelated display spelling");
        assertEquals("EXACT", result.path("status").asText());
        assertEquals("MONDO:0012345", result.path("mondoId").asText());
        assertEquals(2, result.path("mappings").size());
        assertTrue(result.at("/mappings/0/sourceLine").asInt() > 0);
        assertEquals("MONDO:equivalentTo", result.at("/mappings/1/relation").asText());
        assertEquals("2026-11-03", identity.identity(mapper).path("release").asText());
        assertEquals("EXACT", resolve(identity, "MedGen:12345", "").path("status").asText());
        assertEquals("EXACT", resolve(identity, "MedGen:C1234567", "").path("status").asText());
        assertEquals("EXACT", resolve(identity, "MONDO:MONDO:0012345", "").path("status").asText());
    }

    @Test void namesRelatedCrossReferencesAndParentDiseaseDoNotBecomeExactMatches() throws Exception {
        var identity = load(ontology());
        assertEquals("UNVERIFIED", resolve(identity, "TEST disease", "").path("status").asText());
        assertEquals("UNVERIFIED", resolve(identity, "OMIM:234567", "").path("status").asText());
        assertEquals("UNVERIFIED", resolve(identity, "OMIM:345678", "").path("status").asText());
        assertEquals("DIFFERENT", resolve(identity, "MONDO:0012346", "").path("status").asText());
        assertEquals("DIFFERENT", resolve(identity, "OMIM:456789", "").path("status").asText());
    }

    @Test void conflictingUnknownAndAmbiguousIdentifiersRemainUnverified() throws Exception {
        var identity = load(ontology());
        assertEquals("CONDITION_IDENTIFIERS_DISAGREE", resolve(identity, "OMIM:123456", "MONDO:0012346").path("reasonCode").asText());
        assertEquals("CONDITION_IDENTIFIER_UNMAPPED", resolve(identity, "OMIM:123456|OMIM:999999", "").path("reasonCode").asText());
        assertEquals("CONDITION_MAPPING_AMBIGUOUS", resolve(identity, "OMIM:567890", "").path("reasonCode").asText());
        assertEquals("UNVERIFIED", resolve(identity, "MedGen:CN517202", "C3661900:not provided").path("status").asText());
    }

    @Test void obsoleteTermsAndUnscopedMentionsAreNotResurrected() throws Exception {
        var identity = load(ontology());
        assertEquals("UNVERIFIED", resolve(identity, "MONDO:0012347", "").path("status").asText());
        assertEquals("UNVERIFIED", resolve(identity, "OMIM:678901", "").path("status").asText());
        assertEquals("UNVERIFIED", resolve(identity, "OTHER:MONDO:0012345", "").path("status").asText());
        assertEquals("UNVERIFIED", resolve(identity, "OMIM:1234567", "").path("status").asText());
    }

    @Test void invalidPinsDuplicateTermsAndOversizedConditionsFailExplicitly() throws Exception {
        var file = Files.writeString(root.resolve("invalid.obo"), ontology());
        assertThrows(IllegalArgumentException.class, () -> ClinVarConditionIdentity.load(file, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> ClinVarConditionIdentity.load(file, ""));
        assertThrows(IllegalArgumentException.class, () -> load(ontology() + "\n[Term]\nid: MONDO:0012345\n"));
        assertEquals("CONDITION_TEXT_LIMIT", resolve(load(ontology()), "x".repeat(4097), "").path("reasonCode").asText());
    }

    @Test void unavailableOntologyPreservesDirectMondoButDoesNotInventExternalMappings() {
        var identity = ClinVarConditionIdentity.unavailable("CONDITION_MAPPING_SOURCE_INVALID");
        assertEquals("EXACT", resolve(identity, "MONDO:0012345", "").path("status").asText());
        assertEquals("CONDITION_MAPPING_UNAVAILABLE", resolve(identity, "OMIM:123456", "").path("reasonCode").asText());
        assertEquals("CONDITION_MAPPING_SOURCE_INVALID", identity.identity(mapper).path("reasonCode").asText());
    }

    private ObjectNode resolve(ClinVarConditionIdentity identity, String submitted, String reported) {
        return identity.resolve(mapper, mapper.createObjectNode().put("submittedRaw", submitted).put("reportedRaw", reported), "MONDO:0012345");
    }

    private ClinVarConditionIdentity load(String text) throws Exception {
        var file = Files.writeString(root.resolve("mondo.obo"), text);
        return ClinVarConditionIdentity.load(file, CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file)));
    }

    static String ontology() {
        return """
                format-version: 1.2
                data-version: releases/2026-11-03
                [Term]
                id: MONDO:0012345
                name: TEST disease
                xref: OMIM:123456 {source="MONDO:equivalentTo"}
                xref: UMLS:C1234567 {source="MEDGEN:12345", source="MONDO:equivalentTo"}
                xref: MEDGEN:12345 {source="MONDO:equivalentTo"}
                xref: OMIM:234567
                xref: OMIM:345678 {source="MONDO:relatedTo"}
                xref: OMIM:567890 {source="MONDO:equivalentTo"}
                is_a: MONDO:0012346 ! TEST parent
                [Term]
                id: MONDO:0012346
                name: TEST parent
                xref: OMIM:456789 {source="MONDO:equivalentTo"}
                xref: OMIM:567890 {source="MONDO:equivalentTo"}
                [Term]
                id: MONDO:0012347
                xref: OMIM:678901 {source="MONDO:equivalentTo"}
                is_obsolete: true
                """;
    }
}
