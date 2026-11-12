package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.trio.CaseFamilyObservation;

/** Explicit, immutable BP2 bytes boundary; v1 de novo loaders remain unchanged. */
public final class Bp2PhaseBundle {
    static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("BP2_");
    static final List<String> PARTS = List.of("facts", "phase", "partnerClassification", "diseaseApplicability");
    private final ObjectNode snapshot;
    private final String rawSha256;
    private Bp2PhaseBundle(ObjectNode snapshot, String rawSha256) {
        this.snapshot = snapshot.deepCopy(); this.rawSha256 = rawSha256;
    }
    public ObjectNode snapshot() { return snapshot.deepCopy(); }
    public String rawSha256() { return rawSha256; }
    public List<CaseFamilyObservation> observations() {
        var result = new java.util.ArrayList<CaseFamilyObservation>();
        for (JsonNode item : snapshot.path("observations")) result.add(new CaseFamilyObservation((ObjectNode) item.path("facts")));
        return List.copyOf(result);
    }
    public static Bp2PhaseBundle load(ObjectMapper ignoredCallerMapper, byte[] bytes, String rawPin, String canonicalPin) {
        // Neither parser flags nor caller numeric/deserializer settings define the identity profile.
        ObjectMapper mapper = new ObjectMapper();
        if (!FamilyDeNovoJson.sha256(bytes).equals(rawPin)) throw JSON.invalid("RAW_PIN_MISMATCH");
        ObjectNode root = JSON.parse(mapper, bytes);
        try {
            if (!new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(root).equals(canonicalPin)) throw JSON.invalid("CANONICAL_PIN_MISMATCH");
        } catch (IllegalArgumentException invalid) { throw JSON.invalid("CANONICAL_INPUT_INVALID"); }
        JSON.shape(root, "schemaVersion:s context:o observations:a policy:o catalog:o catalogSha256:s registryBinding:o");
        JSON.equal(root, "schemaVersion", "bp2-phase-core-bundle-v1");
        for (JsonNode o : root.path("observations")) {
            JSON.shape(o, "facts:o phase:o partnerClassification:o diseaseApplicability:o assessment:o");
            facts(o.path("facts"));
            JsonNode phase = o.path("phase");
            JSON.shape(phase, "schemaVersion:s binding:o conclusion:s method:s targetParent:s partnerParent:s recordRefs:a review:o sources:a");
            JSON.equal(phase, "schemaVersion", "bp2-phase-v1"); binding(phase.path("binding")); review(phase.path("review")); sources(phase.path("sources"));
            for (JsonNode ref : phase.path("recordRefs")) JSON.shape(ref, "role:s alleleRole:s sampleId:s");
            JsonNode classification = o.path("partnerClassification");
            JSON.shape(classification, "schemaVersion:s binding:o alleleId:s classification:s assertion:o independence:o review:o sources:a");
            JSON.equal(classification, "schemaVersion", "bp2-partner-classification-v1"); binding(classification.path("binding"));
            JSON.shape(classification.path("assertion"), "classification:s conflict:s status:s sourceIds:a"); JSON.strings(classification.at("/assertion/sourceIds"));
            JsonNode independence = classification.path("independence");
            JSON.shape(independence, "conclusion:s targetAlleleId:s pairId:s caseId:s targetContribution:s pairContribution:s caseContribution:s bp2Contribution:s compoundContribution:s exclusionBasis:s dependencies:a review:o");
            for (JsonNode dependency : independence.path("dependencies")) JSON.shape(dependency, "kind:s identity:s");
            review(independence.path("review")); review(classification.path("review")); sources(classification.path("sources"));
            disease(o.path("diseaseApplicability"));
            JsonNode a = o.path("assessment");
            JSON.shape(a, "schemaVersion:s assessmentId:s assessmentVersion:s partPins:o review:o contentSha256:s");
            JSON.equal(a, "schemaVersion", "bp2-assessment-v1"); pins(a.path("partPins")); review(a.path("review"));
        }
        JsonNode p = root.path("policy");
        JSON.shape(p, "schemaVersion:s policyId:s policyVersion:s mode:s criterionIds:a scope:o review:o partPins:o assessmentSha256:s authoritySourceIds:a scientificSources:a qualityProfile:s");
        JSON.equal(p, "schemaVersion", "bp2-reviewed-phase-policy-v1"); pins(p.path("partPins")); JSON.scope(p.path("scope"));
        JSON.shape(p.path("review"), "status:s reviewId:s"); JSON.strings(p.path("criterionIds")); JSON.strings(p.path("authoritySourceIds"));
        for (JsonNode source : p.path("scientificSources")) JSON.shape(source, "sourceId:s version:s url:s sha256:s");
        JSON.catalog(root.path("catalog")); JSON.shape(root.path("registryBinding"), "registryId:s registrySha256:s registryClasspathResource:s");
        return new Bp2PhaseBundle(root, rawPin);
    }
    private static void pins(JsonNode n) { JSON.shape(n, "facts:s phase:s partnerClassification:s diseaseApplicability:s"); }
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
    private static void disease(JsonNode n) {
        boolean executable = "bp2-disease-applicability-v2".equals(n.path("schemaVersion").asText());
        JSON.shape(n, "schemaVersion:s binding:o mode:s applicability:s mechanism:s affectedStatus:s phenotypeConsistency:s additionalEffect:s alleleApplicability:a penetrance:!o review:o sources:a" + (executable ? " materialVersion:s" : ""));
        if (!executable) JSON.equal(n, "schemaVersion", "bp2-disease-applicability-v1");
        binding(n.path("binding")); review(n.path("review")); sources(n.path("sources"));
        for (JsonNode a : n.path("alleleApplicability")) {
            JSON.shape(a, "alleleRole:s alleleId:s hgncId:s conclusion:s annotationVersion:s basis:s sourceIds:a"); JSON.strings(a.path("sourceIds"));
        }
        // Cis does not require a penetrance claim or placeholder. Trans requires it in policy.
        if (!n.has("penetrance")) return;
        JsonNode p = n.path("penetrance");
        JSON.shape(p, "conclusion:s patientApplicability:s population:s ageCondition:s sexCondition:s limitations:s basis:s conditions:a review:o"); review(p.path("review"));
        for (JsonNode c : p.path("conditions")) {
            JSON.shape(c, "kind:s requirement:s patientFact:s conclusion:s basis:s sourceIds:a" + (executable ? " constraint:o patient:o" : "")); JSON.strings(c.path("sourceIds"));
            if (executable) condition(c);
        }
    }
    private static void condition(JsonNode c) {
        JsonNode constraint = c.path("constraint"), patient = c.path("patient");
        String patientShape = "binding:o mechanism:s materialVersion:s";
        binding(patient.path("binding"));
        if ("UNRESTRICTED".equals(constraint.path("mode").asText())) {
            // No hidden limit or patient value may accompany the no-restriction declaration.
            JSON.shape(constraint, "mode:s"); JSON.shape(patient, patientShape); return;
        }
        JSON.equal(constraint, "mode", "RESTRICTED");
        switch (c.path("kind").asText()) {
            case "AGE" -> {
                JSON.shape(constraint, "mode:s operator:s unit:s threshold:i");
                JSON.shape(patient, patientShape + " status:s unit:s value:?i");
            }
            case "SEX" -> {
                JSON.shape(constraint, "mode:s operator:s values:a"); JSON.strings(constraint.path("values"));
                JSON.shape(patient, patientShape + " status:s value:s");
            }
            case "POPULATION" -> {
                JSON.shape(constraint, "mode:s operator:s populationSystem:s populationIds:a"); JSON.strings(constraint.path("populationIds"));
                JSON.shape(patient, patientShape + " status:s populationSystem:s populationId:s");
            }
            default -> throw JSON.invalid("CONDITION_KIND_INVALID");
        }
    }
}
