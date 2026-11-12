package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Bp2TestMaterials.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** BP2-specific scientific subset gates; every vector retains valid surrounding review/pins. */
class Bp2PhaseApplicabilityTest {
    private interface Change { void apply(ObjectNode root) throws Exception; }
    private record Vector(String name, String part, String reason, Change change) { }
    private static void ne(ObjectNode result, String reason) {
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result::toPrettyString);
        assertEquals(reason, result.at("/evaluationResult/reasonCode").asText());
        assertEquals("UNKNOWN", result.at("/criterionApplicability/status").asText());
        for (String field : List.of("evidence", "evaluation", "evaluationId", "evidenceId", "appliedStrength", "pm3Points", "bp2Points", "deNovoPoints", "deterministicTrace")) assertFalse(result.has(field), field);
        assertEquals(3, result.at("/criterionEvaluations/0").size());
    }
    @TestFactory Stream<DynamicTest> reachableDiseaseAndIndependenceGates() {
        List<Vector> cases = new ArrayList<>();
        for (String field : List.of("applicability", "phenotypeConsistency", "additionalEffect", "mechanism", "affectedStatus"))
            cases.add(new Vector("disease-" + field, "diseaseApplicability", "BP2_DISEASE_APPLICABILITY_INCOMPLETE", r -> object(observation(r), "/diseaseApplicability").put(field, "UNKNOWN")));
        for (String value : List.of("PARTIAL", "UNKNOWN", "CONFLICT", "NOT_PENETRANT"))
            cases.add(new Vector("penetrance-" + value, "diseaseApplicability", "BP2_FULL_PENETRANCE_APPLICABILITY_REQUIRED", r -> object(observation(r), "/diseaseApplicability/penetrance").put("conclusion", value)));
        cases.add(new Vector("penetrance-unreviewed", "diseaseApplicability", "BP2_FULL_PENETRANCE_APPLICABILITY_REQUIRED", r -> object(observation(r), "/diseaseApplicability/penetrance/review").put("status", "UNREVIEWED")));
        cases.add(new Vector("patient-not-applicable", "diseaseApplicability", "BP2_FULL_PENETRANCE_APPLICABILITY_REQUIRED", r -> object(observation(r), "/diseaseApplicability/penetrance").put("patientApplicability", "OUT_OF_SCOPE")));
        for (int index : List.of(0, 1, 2)) for (String value : List.of("UNKNOWN", "NOT_SATISFIED", "CONFLICT"))
            cases.add(new Vector("condition-" + index + "-" + value, "diseaseApplicability", "BP2_PENETRANCE_CONDITIONS_UNRESOLVED", r -> object(observation(r), "/diseaseApplicability/penetrance/conditions/" + index).put("conclusion", value)));
        cases.add(new Vector("condition-requirement-conflict", "diseaseApplicability", "BP2_PENETRANCE_CONDITIONS_CONFLICT", r -> object(observation(r), "/diseaseApplicability/penetrance/conditions/1").put("requirement", "different age boundary")));
        cases.add(new Vector("missing-conditions", "diseaseApplicability", "BP2_PENETRANCE_CONDITIONS_UNRESOLVED", r -> object(observation(r), "/diseaseApplicability/penetrance").putArray("conditions")));
        for (String field : List.of("alleleId", "hgncId", "conclusion"))
            cases.add(new Vector("allele-gene-" + field, "diseaseApplicability", "BP2_TWO_ALLELE_GENE_APPLICABILITY_REQUIRED", r -> object(observation(r), "/diseaseApplicability/alleleApplicability/1").put(field, "OTHER")));
        cases.add(new Vector("gene-symbol-only", "diseaseApplicability", "BP2_TWO_ALLELE_GENE_APPLICABILITY_REQUIRED", r -> object(observation(r), "/diseaseApplicability").putArray("alleleApplicability")));
        cases.add(new Vector("empty-independent-evidence", "partnerClassification", "BP2_CLASSIFICATION_INDEPENDENCE_UNRESOLVED", r -> object(observation(r), "/partnerClassification/independence").putArray("dependencies")));
        cases.add(new Vector("compound-only-pathogenicity", "partnerClassification", "BP2_CLASSIFICATION_DEPENDENT_OR_UNKNOWN", r -> object(observation(r), "/partnerClassification/independence").put("compoundContribution", "PRESENT")));
        cases.add(new Vector("phase-mode-conflict", "phase", "BP2_PHASE_DECLARATION_CONFLICT", r -> object(observation(r), "/phase").put("conclusion", "CONFIRMED_CIS")));
        return cases.stream().map(v -> DynamicTest.dynamicTest(v.name(), () -> {
            ObjectNode input = base("beta"); assertTrue(evaluate(input).has("evaluation"), "valid baseline before mutation");
            v.change().apply(input); reissue(input, v.part()); ObjectNode before = input.deepCopy(), result = evaluate(input);
            artifact(v.name(), input, result); ne(result, v.reason()); assertEquals(before, input);
        }));
    }
    @TestFactory Stream<DynamicTest> parentsAndGtOrientations() {
        return Stream.of("alpha", "beta", "gamma").flatMap(tag -> Stream.of("0/1", "1/0", "0|1", "1|0").map(gt -> DynamicTest.dynamicTest(tag + "-reverse-parents-" + gt, () -> {
            ObjectNode input = base(tag), f = facts(input);
            for (int locus : List.of(0, 1)) {
                String father = f.at("/records/" + (2 + locus) + "/observation/genotype").asText();
                String mother = f.at("/records/" + (4 + locus) + "/observation/genotype").asText();
                call(object(f, "/records/" + (2 + locus)), mother);
                call(object(f, "/records/" + (4 + locus)), father);
            }
            for (JsonNode rec : f.path("records")) if ("HET_ALT".equals(rec.at("/observation/call").asText())) call((ObjectNode) rec, gt);
            ObjectNode phase = object(observation(input), "/phase");
            for (String field : List.of("targetParent", "partnerParent")) phase.put(field, "FATHER".equals(phase.path(field).asText()) ? "MOTHER" : "FATHER");
            reissue(input, "facts", "phase"); ObjectNode result = evaluate(input);
            assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result::toPrettyString);
            assertEquals("SUPPORTING", result.at("/evaluation/appliedStrength").asText());
            assertEquals(M.writeValueAsString(result), M.writeValueAsString(evaluate(input))); artifact(tag + "-reverse-" + gt.replace('|', 'p').replace('/', 'u'), input, result);
        })));
    }
    @Test void cisDoesNotRequirePenetrance() throws Exception {
        ObjectNode r = base("alpha"); object(observation(r), "/diseaseApplicability").remove("penetrance");
        reissue(r, "diseaseApplicability"); ObjectNode result = evaluate(r); assertTrue(result.has("evaluation"));
        assertEquals("UNAFFECTED", facts(r).at("/clinical/affectedStatus").asText()); artifact("cis-no-penetrance-no-BS2", r, result);
    }
    @Test void transMissingPenetranceIsMinimalNe() throws Exception {
        ObjectNode r = base("beta"); object(observation(r), "/diseaseApplicability").remove("penetrance");
        reissue(r, "diseaseApplicability"); ObjectNode result = evaluate(r);
        ne(result, "BP2_FULL_PENETRANCE_APPLICABILITY_REQUIRED"); artifact("trans-missing-penetrance", r, result);
    }
    @TestFactory Stream<DynamicTest> transScopeRequiresAffectedAd() {
        return Stream.of("AR", "UNAFFECTED").map(name -> DynamicTest.dynamicTest(name, () -> {
            ObjectNode r = base("beta");
            if (name.equals("AR")) { object(r, "/context").put("moi", "AUTOSOMAL_RECESSIVE"); object(r, "/policy/scope").put("moi", "AUTOSOMAL_RECESSIVE"); object(r, "/catalog/entries/0/scope").put("moi", "AUTOSOMAL_RECESSIVE"); }
            else { object(r, "/context/patientContext").put("affectedStatus", "UNAFFECTED"); object(facts(r), "/members/0").put("affectedStatus", "UNAFFECTED"); object(facts(r), "/clinical").put("affectedStatus", "UNAFFECTED"); object(observation(r), "/diseaseApplicability").put("affectedStatus", "UNAFFECTED"); }
            rebindFamily(r); ObjectNode result = evaluate(r); artifact("trans-outside-" + name, r, result); ne(result, "BP2_TRANS_AFFECTED_AD_REQUIRED");
        }));
    }
    @TestFactory Stream<DynamicTest> embeddedSourceBoundary() {
        return PARTS.stream().flatMap(part -> Stream.of("float", "pin", "facts", "raw", "date").map(kind -> DynamicTest.dynamicTest(part + "-" + kind, () -> {
            ObjectNode r = base("beta"), source = object(observation(r), "/" + part + "/sources/0");
            String reason;
            if (kind.equals("date")) { source.put("acquiredAt", "2026-99-99"); reason = "BP2_SOURCE_DATE_INVALID"; }
            else if (kind.equals("raw")) { source.put("rawSha256", "0".repeat(64)); reason = "BP2_SOURCE_RAW_PIN_MISMATCH"; }
            else if (kind.equals("pin")) { source.put("canonicalSha256", "0".repeat(64)); reason = "BP2_SOURCE_CANONICAL_PIN_MISMATCH"; }
            else {
                ObjectNode report = (ObjectNode) M.readTree(source.path("rawUtf8").asText());
                if (kind.equals("float")) { report.put("unexpected", 0.25); reason = "BP2_SOURCE_DOCUMENT_INVALID"; }
                else { report.put("unexpected", "changed"); source.put("canonicalSha256", hash(report)); reason = "BP2_SOURCE_FACTS_MISMATCH"; }
                String bytes = M.writeValueAsString(report); source.put("rawUtf8", bytes).put("rawSha256", raw(bytes.getBytes(StandardCharsets.UTF_8)));
            }
            pinsOnly(r); ObjectNode result = evaluate(r); artifact("source-" + part + "-" + kind, r, result); ne(result, reason);
        })));
    }
    @Test void absentFamilyIsNotCanonicalException() throws Exception {
        ObjectNode r = base("alpha"); object(r, "/context/patientContext").remove("familyContextId"); rebindFamily(r);
        ObjectNode result = evaluate(r); ne(result, "BP2_FAMILY_CONTEXT_REQUIRED"); artifact("missing-family", r, result);
    }
    @Test void transportCannotRereviewChangedContent() throws Exception {
        ObjectNode r = base("beta"); object(observation(r), "/diseaseApplicability/penetrance").put("conclusion", "PARTIAL");
        source(object(observation(r), "/diseaseApplicability")); ObjectNode result = evaluate(r);
        ne(result, "BP2_PART_PIN_MISMATCH"); artifact("new-penetrance-old-review", r, result);
    }
    @TestFactory Stream<DynamicTest> localQualityCannotBeOverriddenByReview() {
        return Stream.of("low-dp", "low-gq", "AD-total", "AD-balance", "HOM_REF-alt", "missing-FT", "failed-FT", "duplicate-FORMAT")
                .map(name -> DynamicTest.dynamicTest(name, () -> {
                    ObjectNode r = base("alpha"), record = object(facts(r), "/records/" + (name.equals("HOM_REF-alt") ? 4 : 0));
                    ObjectNode g = object(record, "/observation"); String reason;
                    switch (name) {
                        case "low-dp" -> { g.put("depth", 18).put("alleleDepth", "9,9"); record.put("rawSampleValue", "0/1:18:60:9,9:PASS"); reason = "BP2_RAW_QUALITY_INVALID"; }
                        case "low-gq" -> { g.put("genotypeQuality", 29); record.put("rawSampleValue", "0/1:44:29:22,22:PASS"); reason = "BP2_RAW_QUALITY_INVALID"; }
                        case "AD-total" -> { g.put("alleleDepth", "20,20"); record.put("rawSampleValue", "0/1:44:60:20,20:PASS"); reason = "BP2_AD_DEPTH_CONFLICT"; }
                        case "AD-balance" -> { g.put("alleleDepth", "43,1"); record.put("rawSampleValue", "0/1:44:60:43,1:PASS"); reason = "BP2_ALLELE_BALANCE_INVALID"; }
                        case "HOM_REF-alt" -> { String ad = (g.path("depth").asInt() - 1) + ",1"; g.put("alleleDepth", ad); record.put("rawSampleValue", "0/0:" + g.path("depth").asText() + ":" + g.path("genotypeQuality").asText() + ":" + ad + ":PASS"); reason = "BP2_HOM_REF_ALT_READS_OUTSIDE_PROFILE"; }
                        case "missing-FT" -> { object(facts(r), "/detections/0").put("sampleFormat", "GT:DP:GQ:AD"); record.put("rawSampleValue", "0/1:44:60:22,22"); reason = "BP2_RAW_FT_MISSING_OR_FAILED"; }
                        case "failed-FT" -> { record.put("rawSampleValue", "0/1:44:60:22,22:LowQual"); reason = "BP2_RAW_FT_MISSING_OR_FAILED"; }
                        default -> { object(facts(r), "/detections/0").put("sampleFormat", "GT:DP:DP:AD:FT"); reason = "BP2_RAW_FORMAT_CONFLICT"; }
                    }
                    reissue(r, "facts"); ObjectNode result = evaluate(r); artifact("quality-" + name, r, result); ne(result, reason);
                }));
    }
    @TestFactory Stream<DynamicTest> completeScientificSourceIdentity() {
        return Stream.of("sourceId", "version", "url", "sha256").map(field -> DynamicTest.dynamicTest(field, () -> {
            ObjectNode r = base("beta"); object(r, "/policy/scientificSources/0").put(field, "wrong-source-identity"); catalogPin(r);
            ObjectNode result = evaluate(r); artifact("authority-" + field, r, result); ne(result, "BP2_REGISTRY_OR_AUTHORITY_INVALID");
        }));
    }
}
