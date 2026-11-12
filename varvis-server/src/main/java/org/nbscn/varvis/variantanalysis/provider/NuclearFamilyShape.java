package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.JsonNode;
/** Rule-neutral strict shape only. No scientific policy or criterion inference. */
final class NuclearFamilyShape {
 private final FamilyDeNovoJson json;
 NuclearFamilyShape(FamilyDeNovoJson json) { this.json = json; }
    void binding(JsonNode n) {
        json.shape(n, "observationId:s occurrenceId:s caseId:s contextId:s alleleId:s hgncId:s mondoId:s moi:s patientContextId:s familyContextId:s");
    }
    void review(JsonNode n) {
        json.shape(n, "status:s reviewer:s date:s basis:s sourceIds:a");
        json.strings(n.path("sourceIds"));
    }
    void sources(JsonNode n) {
        for (var s : n) json.shape(s, "sourceId:s recordId:s version:s locator:s acquiredAt:s rawUtf8:s rawSha256:s canonicalSha256:s");
    }
    void recordShape(JsonNode n) {
        json.shape(n, "recordId:s memberId:s sourcePersonId:s sampleId:s allele:o geneSymbol:s locator:s method:s format:s rawSample:s recordFilter:s observation:o sourceIds:a");
        json.shape(n.path("allele"), "assembly:s contig:s position:i reference:s alternate:s");
        json.strings(n.path("sourceIds"));
        json.shape(n.path("observation"), "genotype:s phased:b depth:i genotypeQuality:i alleleDepth:s sampleFilter:s call:s targetAltIndex:i ploidy:i");
    }
    void factsShape(JsonNode n) {
        json.shape(n, "schemaVersion:s observationId:s observationVersion:s occurrenceId:s caseId:s familyId:s binding:o variant:o structure:s parentRelatedness:s members:a records:a parentage:a sources:a");
        json.equal(n, "schemaVersion", NuclearFamilyObservation.SCHEMA);
        binding(n.path("binding"));
        sources(n.path("sources"));
        for (var member : n.path("members")) {
            json.shape(member, "memberId:s sourcePersonId:s sampleId:s role:s proband:b fatherId:?s motherId:?s independence:s affectedStatus:s clinical:o detection:o");
            json.enumeration(member, "role", "FATHER MOTHER CHILD");
            json.enumeration(member, "affectedStatus", "AFFECTED UNAFFECTED UNKNOWN");
            var c = member.path("clinical");
            json.shape(c, "observedAt:s ageYears:i mondoId:s mechanism:s diagnosis:s sourceIds:a review:o");
            json.strings(c.path("sourceIds"));
            review(c.path("review"));
            var d = member.path("detection");
            json.shape(d, "status:s method:s coverage:s mosaicism:s observedAt:s sourceIds:a review:o");
            json.strings(d.path("sourceIds"));
            review(d.path("review"));
        }
        for (var record : n.path("records")) recordShape(record);
        for (var r : n.path("parentage")) {
            json.shape(r, "relationshipId:s parentMemberId:s childMemberId:s relationship:s status:s method:s sourceIds:a review:o");
            json.strings(r.path("sourceIds"));
            review(r.path("review"));
        }
    }
}
