package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.trio.CaseFamilyObservation;

/** Explicit, immutable PM3 bytes boundary; v1 de novo loaders remain unchanged. */
public final class Pm3InTransBundle {
    static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("PM3_");
    static final List<String> PARTS = List.of("facts", "phase", "partnerClassification", "targetRarity", "partnerRarity");
    private final ObjectNode snapshot;
    private final String rawSha256;
    private Pm3InTransBundle(ObjectNode snapshot, String rawSha256) {
        this.snapshot = snapshot.deepCopy(); this.rawSha256 = rawSha256;
    }
    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    public String rawSha256() { return rawSha256; }
    public List<CaseFamilyObservation> observations() {
        var result = new java.util.ArrayList<CaseFamilyObservation>();
        for (JsonNode item : snapshot.path("observations")) result.add(new CaseFamilyObservation((ObjectNode) item.path("facts")));
        return List.copyOf(result);
    }
    public static Pm3InTransBundle load(ObjectMapper ignoredCallerMapper, byte[] bytes, String rawPin, String canonicalPin) {
        // Neither parser flags nor caller numeric/deserializer settings define the identity profile.
        ObjectMapper mapper = new ObjectMapper();
        if (!FamilyDeNovoJson.sha256(bytes).equals(rawPin)) throw JSON.invalid("RAW_PIN_MISMATCH");
        ObjectNode root = JSON.parse(mapper, bytes);
        try {
            if (!new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(root).equals(canonicalPin)) throw JSON.invalid("CANONICAL_PIN_MISMATCH");
        } catch (IllegalArgumentException invalid) { throw JSON.invalid("CANONICAL_INPUT_INVALID"); }
        JSON.shape(root, "schemaVersion:s context:o observations:a policy:o catalog:o catalogSha256:s registryBinding:o");
        JSON.equal(root, "schemaVersion", "pm3-in-trans-core-bundle-v1");
        for (JsonNode o : root.path("observations")) {
            JSON.shape(o, "facts:o phase:o partnerClassification:o targetRarity:o partnerRarity:o assessment:o");
            facts(o.path("facts"));
            JsonNode phase = o.path("phase");
            JSON.shape(phase, "schemaVersion:s binding:o conclusion:s method:s targetParent:s partnerParent:s recordRefs:a review:o sources:a");
            JSON.equal(phase, "schemaVersion", "pm3-phase-v1"); binding(phase.path("binding")); review(phase.path("review")); sources(phase.path("sources"));
            for (JsonNode ref : phase.path("recordRefs")) JSON.shape(ref, "role:s alleleRole:s sampleId:s");
            JsonNode classification = o.path("partnerClassification");
            JSON.shape(classification, "schemaVersion:s binding:o alleleId:s classification:s assertion:o independence:o review:o sources:a");
            JSON.equal(classification, "schemaVersion", "pm3-partner-classification-v1"); binding(classification.path("binding"));
            JSON.shape(classification.path("assertion"), "classification:s conflict:s status:s sourceIds:a"); JSON.strings(classification.at("/assertion/sourceIds"));
            JsonNode independence = classification.path("independence");
            JSON.shape(independence, "conclusion:s targetAlleleId:s pairId:s caseId:s targetContribution:s pairContribution:s caseContribution:s pm3Contribution:s dependencies:a review:o");
            for (JsonNode dependency : independence.path("dependencies")) JSON.shape(dependency, "kind:s identity:s");
            review(independence.path("review")); review(classification.path("review")); sources(classification.path("sources"));
            rarity(o.path("targetRarity")); rarity(o.path("partnerRarity"));
            JsonNode a = o.path("assessment");
            JSON.shape(a, "schemaVersion:s assessmentId:s assessmentVersion:s partPins:o review:o contentSha256:s");
            JSON.equal(a, "schemaVersion", "pm3-assessment-v1"); pins(a.path("partPins")); review(a.path("review"));
        }
        JsonNode p = root.path("policy");
        JSON.shape(p, "schemaVersion:s policyId:s policyVersion:s mode:s criterionIds:a scope:o review:o partPins:o assessmentSha256:s authoritySourceIds:a scientificSources:a");
        JSON.equal(p, "schemaVersion", "pm3-reviewed-in-trans-policy-v1"); pins(p.path("partPins")); JSON.scope(p.path("scope"));
        JSON.shape(p.path("review"), "status:s reviewId:s"); JSON.strings(p.path("criterionIds")); JSON.strings(p.path("authoritySourceIds"));
        for (JsonNode source : p.path("scientificSources")) JSON.shape(source, "sourceId:s version:s url:s sha256:s");
        JSON.catalog(root.path("catalog")); JSON.shape(root.path("registryBinding"), "registryId:s registrySha256:s registryClasspathResource:s");
        return new Pm3InTransBundle(root, rawPin);
    }
    private static void pins(JsonNode n) { JSON.shape(n, "facts:s phase:s partnerClassification:s targetRarity:s partnerRarity:s"); }
    static void binding(JsonNode n) {
        JSON.shape(n, "observationId:s occurrenceId:s caseId:s contextId:s targetAlleleId:s partnerAlleleId:s pairId:s hgncId:s mondoId:s moi:s patientContextId:s familyContextId:s");
    }
    private static void review(JsonNode n) {
        JSON.shape(n, "status:s reviewer:s date:s basis:s sourceIds:a"); JSON.strings(n.path("sourceIds"));
    }
    private static void sources(JsonNode n) {
        for (JsonNode source : n) JSON.shape(source, "sourceId:s recordId:s version:s locator:s acquiredAt:s rawUtf8:s rawSha256:s canonicalSha256:s");
    }
    private static void facts(JsonNode n) {
        JSON.shape(n, "schemaVersion:s observationId:s observationVersion:s occurrenceId:s caseId:s familyId:s binding:o alleles:o members:a records:a detections:a parentage:a clinical:o sources:a");
        JSON.equal(n, "schemaVersion", CaseFamilyObservation.TWO_ALLELE_SCHEMA); binding(n.path("binding"));
        JSON.shape(n.path("alleles"), "target:o partner:o");
        for (String role : List.of("target", "partner")) variant(n.at("/alleles/" + role));
        for (JsonNode m : n.path("members")) {
            JSON.shape(m, "memberId:s sampleId:s role:s affectedStatus:s");
            JSON.enumeration(m, "role", "PROBAND FATHER MOTHER"); JSON.enumeration(m, "affectedStatus", "AFFECTED UNAFFECTED UNKNOWN");
        }
        for (JsonNode r : n.path("records")) {
            JSON.shape(r, "variantKey:o role:s sampleId:s geneSymbol:?s recordFilter:?s sampleFilter:?s rawSampleValue:?s observation:?o");
            JSON.shape(r.path("variantKey"), "assembly:s chromosome:s position:i reference:s alternate:s");
            if (!r.path("observation").isNull()) JSON.shape(r.path("observation"), "role:s genotype:?s call:s depth:?i genotypeQuality:?i alleleDepth:?s phased:b");
        }
        for (JsonNode d : n.path("detections")) {
            JSON.shape(d, "binding:o role:s alleleRole:s memberId:s sampleId:s targetAltIndex:i ploidy:i sampleFormat:s method:s coverage:s capability:s quality:s basis:s limitations:s sourceIds:a review:o");
            binding(d.path("binding")); JSON.strings(d.path("sourceIds")); review(d.path("review"));
        }
        for (JsonNode p : n.path("parentage")) {
            JSON.shape(p, "binding:o parentRole:s parentMemberId:s probandMemberId:s parentSampleId:s probandSampleId:s testStatus:s conclusion:s method:s result:s limitations:s sourceIds:a review:o");
            binding(p.path("binding")); JSON.strings(p.path("sourceIds")); review(p.path("review"));
        }
        JsonNode c = n.path("clinical");
        JSON.shape(c, "binding:o probandMemberId:s diseaseApplicability:s affectedStatus:s mosaicism:s observations:a review:o");
        binding(c.path("binding")); JSON.strings(c.path("observations")); review(c.path("review")); sources(n.path("sources"));
    }
    private static void variant(JsonNode n) {
        JSON.shape(n, "alleleId:s variantType:s inputAllele:o normalizedAllele:o evidenceQueryAllele:o normalization:o assemblyLineage:o");
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) JSON.shape(n.path(field), "assembly:s contig:s position:i reference:s alternate:s");
        JSON.shape(n.path("normalization"), "status:s method:s methodVersion:s referenceSequenceSha256:s refValidation:s");
        JSON.shape(n.path("assemblyLineage"), "mappingStatus:s");
    }
    private static void rarity(JsonNode n) {
        JSON.shape(n, "schemaVersion:s binding:o alleleRole:s alleleId:s conclusion:s resourceVersion:s availability:s queryOutcome:s coverage:s quality:s applicability:s observedAf:s frequencyPolicy:o review:o sources:a");
        JSON.equal(n, "schemaVersion", "pm3-pm2-rarity-v1"); binding(n.path("binding"));
        JsonNode p = n.path("frequencyPolicy");
        JSON.shape(p, "policyId:s policyVersion:s criterionId:s scope:o comparison:s maximumAf:s review:o");
        JSON.scope(p.path("scope")); review(p.path("review")); review(n.path("review")); sources(n.path("sources"));
    }
}
