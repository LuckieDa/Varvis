package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class Pm3LiteraturePopulationContextTest {
    final ObjectMapper mapper=new ObjectMapper();
    final Pm3LiteraturePopulationContext factory=new Pm3LiteraturePopulationContext(mapper);
    ObjectNode row() {
        var row=mapper.createObjectNode().put("selectedTargetBinding","MATCHED");
        row.putObject("reportedObservation").put("geneSymbol","PAH").put("reportedAffectedStatus","AFFECTED");
        row.putObject("diseaseContext").put("qualification","VERIFIED").put("inheritance","AR").put("diseaseId","MONDO:0009861");
        var i=row.putObject("identities").putObject("target").put("status","LOCAL_IDENTITY_VERIFIED")
                .put("geneSymbol","PAH").put("hgncId","HGNC:8582");
        i.putObject("allele").put("assembly","GRCh38").put("contig","12").put("position",102840507).put("reference","G").put("alternate","A");
        i.putObject("referenceIdentity").putObject("actualChecksumReceipt").put("assembly","GRCh38")
                .put("hashStatus","VERIFIED").put("observedSha256","a".repeat(64)).put("expectedSha256","a".repeat(64));
        i.putArray("referenceReads").addObject().put("contig","12").put("start",102840507).put("end",102840507)
                .put("normalizedCaseBasesSha256",FamilyDeNovoJson.sha256(new byte[]{'G'}));
        return row;
    }
    @Test void literatureCaseHasIndependentContextAndNoInventedPatientGenotype() {
        var row=row();var c=factory.build("PMC123","b".repeat(64),"Case 1",row,"target");
        new RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(c);
        assertEquals("PRIVACY_BOUNDED_RUNTIME",c.path("dataClass").asText());
        assertEquals("UNKNOWN",c.at("/patientContext/sex").asText());
        assertFalse(c.path("patientContext").has("familyContextId"));
        assertNull(Pm3LiteraturePopulationContext.queryView(c).getObservation().getGenotype());
        assertNotEquals(c.path("contextId"),factory.build("PMC123","b".repeat(64),"Case 2",row,"target").path("contextId"));
    }
    @Test void targetMatchAndAffectedDiseaseAreRequiredBeforePopulationContext() {
        for(String field:new String[]{"selectedTargetBinding","diseaseContext","reportedObservation"}) {
            var row=row();row.remove(field);
            assertThrows(IllegalArgumentException.class,()->factory.build("PMC123","b".repeat(64),"Case 1",row,"target"));
        }
    }
    @Test void wrongReferenceBaseOrMissingPartnerCannotBorrowTargetProof() {
        var row=row();row.withObject("/identities/target/allele").put("reference","T");
        var error=assertThrows(IllegalArgumentException.class,()->factory.build("PMC123","b".repeat(64),"Case 1",row,"target"));
        assertEquals("PM3_POPULATION_REFERENCE_BASE_NOT_VERIFIED",error.getMessage());
        assertThrows(IllegalArgumentException.class,()->factory.build("PMC123","b".repeat(64),"Case 1",row(),"partner"));
    }
}
