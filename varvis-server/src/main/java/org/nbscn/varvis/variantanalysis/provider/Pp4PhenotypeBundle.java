package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Opt-in strict bytes boundary. No rewriting, default registration, business services or inferred facts. */
public final class Pp4PhenotypeBundle {
    static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("PP4_");
    static final List<String> PARTS = List.of("facts", "phenotypeReview", "diagnosticYield", "locusAttribution");
    private final ObjectNode value;
    private final String raw;
    private Pp4PhenotypeBundle(ObjectNode value, String raw) { this.value = value.deepCopy(); this.raw = raw; }
    public ObjectNode snapshot() { return value.deepCopy(); }
    public String rawSha256() { return raw; }
    public static Pp4PhenotypeBundle load(ObjectMapper ignored, byte[] bytes, String rawPin, String canonicalPin) {
        if (!FamilyDeNovoJson.sha256(bytes).equals(rawPin)) throw JSON.invalid("RAW_PIN_MISMATCH");
        var m = new ObjectMapper(); var root = JSON.parse(m, bytes);
        String canonical;
        try { canonical = new RuntimeEvidenceV2Adapter(m).canonicalSha256(root); }
        catch (IllegalArgumentException invalid) { throw JSON.invalid("CANONICAL_INPUT_INVALID"); }
        if (!canonical.equals(canonicalPin)) throw JSON.invalid("CANONICAL_PIN_MISMATCH");
        JSON.shape(root, "schemaVersion:s context:o observations:a policy:o catalog:o catalogSha256:s registryBinding:o");
        JSON.equal(root, "schemaVersion", "pp4-phenotype-core-bundle-v1");
        for (var o : root.path("observations")) {
            JSON.shape(o, "facts:o phenotypeReview:o diagnosticYield:o locusAttribution:o assessment:o");
            JSON.facts(o.path("facts"));
            for (String part : PARTS.subList(1, 4)) {
                JSON.binding(o.path(part).path("binding")); review(o.path(part).path("review"));
                new NuclearFamilyShape(JSON).sources(o.path(part).path("sources"));
            }
            var p = o.path("phenotypeReview");
            JSON.shape(p, "schemaVersion:s binding:o caseId:s occurrenceId:s probandMemberId:s definition:o ageYears:?i ageUnit:s method:s testVersion:s populationId:s findings:a rosterStatus:s alternatives:a linkage:s geneDiseaseBasis:o review:o sources:a");
            JSON.equal(p, "schemaVersion", "pp4-phenotype-review-v1"); definition(p.path("definition"));
            for (var f : p.path("findings")) {
                JSON.shape(f, "findingId:s featureId:s presence:s observedAt:s examination:s ageApplicable:s method:s sourceIds:a"); JSON.strings(f.path("sourceIds"));
            }
            for (var a : p.path("alternatives")) {
                JSON.shape(a, "kind:s caseId:s investigation:s detected:b unresolvedFindings:a method:s basis:s sourceIds:a");
                JSON.strings(a.path("unresolvedFindings")); JSON.strings(a.path("sourceIds"));
            }
            JSON.shape(p.path("geneDiseaseBasis"), "hgncId:s mondoId:s mechanism:s validity:s basisId:s independence:s excludedAlleleIds:a sourceIds:a");
            JSON.strings(p.at("/geneDiseaseBasis/excludedAlleleIds")); JSON.strings(p.at("/geneDiseaseBasis/sourceIds"));
            var y = o.path("diagnosticYield");
            JSON.shape(y, "schemaVersion:s binding:o cohortId:s cohortVersion:s ascertainment:s ascertainmentDetails:!o statistic:s unit:s denominatorDefinition:s geneSpecificDiagnosedCount:i eligibleTestedCount:i definition:o method:s testVersion:s populationId:s comparability:s independence:s rosterStatus:s cases:a declaredYield:!s declaredRawHalfPoints:!i review:o sources:a");
            JSON.equal(y, "schemaVersion", "pp4-diagnostic-yield-v1"); definition(y.path("definition"));
            // Legacy reports remain readable for precise NE; absence never supplies a reviewed mode.
            if (y.has("ascertainmentDetails")) {
                var d = y.path("ascertainmentDetails");
                JSON.shape(d, "schemaVersion:s description:s eligibility:s testing:s resultSelection:s basis:s sourceIds:a");
                JSON.equal(d, "schemaVersion", "pp4-cohort-ascertainment-v1");
                JSON.strings(d.path("sourceIds"));
            }
            for (var c : y.path("cases")) {
                JSON.shape(c, "caseId:s personId:s familyId:s phenotypeEligible:b tested:b diagnosedInGene:b classificationBasis:s diagnosticAlleleIds:a sourceIds:a");
                JSON.strings(c.path("diagnosticAlleleIds")); JSON.strings(c.path("sourceIds"));
            }
            var l = o.path("locusAttribution");
            JSON.shape(l, "schemaVersion:s binding:o caseId:s method:s testVersion:s coverage:s rosterStatus:s candidates:a allocation:s usedEvidenceStatus:s usedEvidence:a clinicalUseStatus:s clinicalUse:a review:o sources:a");
            JSON.equal(l, "schemaVersion", "pp4-locus-attribution-v1");
            for (var c : l.path("candidates")) {
                JSON.shape(c, "candidateId:s alleleId:s hgncId:s disposition:s exclusion:s basis:s sourceIds:a"); JSON.strings(c.path("sourceIds"));
            }
            for (var e : l.path("usedEvidence")) { JSON.shape(e, "criterionId:s evidenceGroupId:s status:s basis:s sourceIds:a"); JSON.strings(e.path("sourceIds")); }
            for (var e : l.path("clinicalUse")) { JSON.shape(e, "criterionId:s caseId:s personId:s clinicalSourceIds:a status:s basis:s sourceIds:a"); JSON.strings(e.path("sourceIds")); JSON.strings(e.path("clinicalSourceIds")); }
            var a = o.path("assessment");
            JSON.shape(a, "schemaVersion:s assessmentId:s assessmentVersion:s partPins:o factsReview:o review:o contentSha256:s");
            JSON.equal(a, "schemaVersion", "pp4-assessment-v1"); pins(a.path("partPins")); review(a.path("review")); review(a.path("factsReview"));
        }
        var p = root.path("policy");
        JSON.shape(p, "schemaVersion:s policyId:s policyVersion:s mode:s criterionIds:a scope:o review:o partPins:o assessmentSha256:s authoritySourceIds:a scientificSources:a qualityProfile:s");
        JSON.equal(p, "schemaVersion", "pp4-reviewed-phenotype-policy-v1"); JSON.scope(p.path("scope")); pins(p.path("partPins"));
        JSON.shape(p.path("review"), "status:s reviewId:s"); JSON.strings(p.path("criterionIds")); JSON.strings(p.path("authoritySourceIds"));
        for (var s : p.path("scientificSources")) JSON.shape(s, "sourceId:s version:s url:s sha256:s publicationDate:s publicationStatus:s normativeEligibility:s authorityCutoff:s");
        JSON.catalog(root.path("catalog")); JSON.shape(root.path("registryBinding"), "registryId:s registrySha256:s registryClasspathResource:s");
        return new Pp4PhenotypeBundle(root, rawPin);
    }
    private static void definition(JsonNode n) {
        JSON.shape(n, "definitionId:s version:s hgncId:s mondoId:s mechanism:s subtype:s mode:s ageMinimumYears:i ageMaximumYears:i requirements:a");
        for (var r : n.path("requirements")) JSON.shape(r, "featureId:s presence:s");
    }
    private static void pins(JsonNode n) { JSON.shape(n, "facts:s phenotypeReview:s diagnosticYield:s locusAttribution:s"); }
    private static void review(JsonNode n) { new NuclearFamilyShape(JSON).review(n); }
}
