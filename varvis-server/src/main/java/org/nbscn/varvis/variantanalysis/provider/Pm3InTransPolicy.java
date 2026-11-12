package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Pure six-record segregation and reviewed-material gates, not a clinical classification service. */
final class Pm3InTransPolicy {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private static final List<String> ROLES = List.of("PROBAND", "FATHER", "MOTHER");
    private static final List<String> ALLELES = List.of("target", "partner");

    @Nullable String validate(ObjectNode context, ObjectNode observation, ObjectNode policy) {
        JsonNode facts = observation.path("facts"), binding = facts.path("binding"), assessment = observation.path("assessment");
        Set<String> allSources = new HashSet<>();
        Map<String, Set<String>> partSources = new HashMap<>();
        for (String part : Pm3InTransBundle.PARTS) {
            JsonNode document = observation.path(part);
            Set<String> local = new HashSet<>(); partSources.put(part, local);
            String issue = sourceIssue(document, local, allSources);
            if (issue != null) return issue;
            if (!eq(binding, document.path("binding"))) return "PM3_PART_BINDING_MISMATCH";
            String hash = adapter.canonicalSha256(document);
            if (!hash.equals(assessment.path("partPins").path(part).asText())
                    || !hash.equals(policy.path("partPins").path(part).asText())) return "PM3_PART_PIN_MISMATCH";
        }
        ObjectNode unsigned = ((ObjectNode) assessment).deepCopy(); unsigned.remove("contentSha256");
        if (!adapter.canonicalSha256(unsigned).equals(assessment.path("contentSha256").asText())
                || !adapter.canonicalSha256(assessment).equals(policy.path("assessmentSha256").asText())) return "PM3_ASSESSMENT_PIN_MISMATCH";
        if (!review(assessment.path("review"), allSources)) return "PM3_ASSESSMENT_UNREVIEWED";
        if (!"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || !"SINGLE_CONFIRMED_TRANS_PAIR".equals(policy.path("mode").asText())
                || policy.path("criterionIds").size() != 1
                || !Pm3InTransCriterionEvaluator.CRITERION.equals(policy.at("/criterionIds/0").asText())) return "PM3_POLICY_INVALID";
        if (!"AUTOSOMAL_RECESSIVE".equals(context.path("moi").asText())
                || !"AFFECTED".equals(context.at("/patientContext/affectedStatus").asText())
                || !"PROBAND".equals(context.at("/patientContext/role").asText())) return "PM3_CONTEXT_OUTSIDE_SINGLE_AFFECTED_AR";
        for (var field : Map.of("observationId", "observationId", "occurrenceId", "occurrenceId", "caseId", "caseId", "familyContextId", "familyId").entrySet())
            if (!eq(binding.path(field.getKey()), facts.path(field.getValue()))) return "PM3_FACT_IDENTITY_MISMATCH";
        // Optional in generic v3, required by this single-family PM3 branch before canonical comparison.
        JsonNode familyContextId = context.at("/patientContext/familyContextId");
        if (!familyContextId.isTextual() || familyContextId.asText().isBlank()) return "PM3_FAMILY_CONTEXT_REQUIRED";
        for (var field : Map.of("contextId", "/contextId", "targetAlleleId", "/variant/alleleId", "hgncId", "/gene/hgncId",
                "mondoId", "/disease/mondoId", "moi", "/moi", "patientContextId", "/patientContext/patientContextId",
                "familyContextId", "/patientContext/familyContextId").entrySet())
            if (!eq(binding.path(field.getKey()), context.at(field.getValue()))) return "PM3_CONTEXT_BINDING_MISMATCH";
        if (!eq(facts.at("/alleles/target"), context.path("variant"))) return "PM3_TARGET_IDENTITY_MISMATCH";
        for (String alleleRole : ALLELES) {
            JsonNode variant = facts.at("/alleles/" + alleleRole);
            ObjectNode check = context.deepCopy(); check.set("variant", variant.deepCopy());
            try {
                check.put("contextId", adapter.canonicalVersionedContextId(check)); adapter.validateVersionedContext(check);
            } catch (IllegalArgumentException invalid) { return "PM3_ALLELE_IDENTITY_INVALID"; }
            JsonNode allele = variant.path("normalizedAllele");
            if (!"GRCh38".equals(allele.path("assembly").asText()) || !allele.path("contig").asText().matches("([1-9]|1[0-9]|2[0-2])")
                    || !"SNV".equals(variant.path("variantType").asText()) || !"NATIVE".equals(variant.at("/assemblyLineage/mappingStatus").asText())
                    || !eq(allele, variant.path("inputAllele")) || !eq(allele, variant.path("evidenceQueryAllele"))
                    || !allele.path("reference").asText().matches("[ACGT]") || !allele.path("alternate").asText().matches("[ACGT]")
                    || eq(allele.path("reference"), allele.path("alternate"))) return "PM3_NATIVE_AUTOSOMAL_BIALLELIC_SNV_REQUIRED";
            if (!eq(variant.path("alleleId"), binding.path(alleleRole + "AlleleId"))) return "PM3_ALLELE_ROLE_MISMATCH";
        }
        JsonNode target = facts.at("/alleles/target/normalizedAllele"), partner = facts.at("/alleles/partner/normalizedAllele");
        if (!eq(target.path("contig"), partner.path("contig")) || eq(target.path("position"), partner.path("position"))
                || eq(binding.path("targetAlleleId"), binding.path("partnerAlleleId"))) return "PM3_DISTINCT_LOCI_REQUIRED";
        if (!pairId(binding).equals(binding.path("pairId").asText())) return "PM3_PAIR_IDENTITY_MISMATCH";
        Set<String> factSources = partSources.get("facts");
        Map<String, JsonNode> members = new HashMap<>(); Set<String> memberIds = new HashSet<>(), samples = new HashSet<>();
        for (JsonNode m : facts.path("members")) {
            String role = m.path("role").asText();
            if (!ROLES.contains(role) || members.put(role, m) != null || !memberIds.add(nfc(m.path("memberId")))
                    || !samples.add(nfc(m.path("sampleId")))) return "PM3_MEMBERS_NOT_DISTINCT";
        }
        if (members.size() != 3) return "PM3_COMPLETE_TRIO_REQUIRED";
        JsonNode proband = members.get("PROBAND");
        if (!eq(proband.path("memberId"), binding.path("patientContextId")) || !"AFFECTED".equals(proband.path("affectedStatus").asText())) return "PM3_PROBAND_BINDING_MISMATCH";
        JsonNode clinical = facts.path("clinical");
        if (!eq(clinical.path("binding"), binding) || !eq(clinical.path("probandMemberId"), proband.path("memberId"))
                || !"APPLICABLE".equals(clinical.path("diseaseApplicability").asText()) || !"AFFECTED".equals(clinical.path("affectedStatus").asText())
                || !"REVIEWED_NONMOSAIC".equals(clinical.path("mosaicism").asText()) || clinical.path("observations").isEmpty()
                || !review(clinical.path("review"), factSources)) return "PM3_CLINICAL_APPLICABILITY_INCOMPLETE";
        Set<String> parentRoles = new HashSet<>();
        for (JsonNode relation : facts.path("parentage")) {
            String role = relation.path("parentRole").asText(); JsonNode parent = members.get(role);
            if (!Set.of("FATHER", "MOTHER").contains(role) || parent == null || !parentRoles.add(role)
                    || !eq(binding, relation.path("binding")) || !eq(parent.path("memberId"), relation.path("parentMemberId"))
                    || !eq(parent.path("sampleId"), relation.path("parentSampleId")) || !eq(proband.path("memberId"), relation.path("probandMemberId"))
                    || !eq(proband.path("sampleId"), relation.path("probandSampleId")) || !"COMPLETED".equals(relation.path("testStatus").asText())
                    || !"CONFIRMED".equals(relation.path("conclusion").asText()) || !"RELATIONSHIP_SUPPORTED".equals(relation.path("result").asText())
                    || !"INDEPENDENT_RELATIONSHIP_TEST".equals(relation.path("method").asText())
                    || !refs(relation.path("sourceIds"), factSources) || !review(relation.path("review"), factSources)) return "PM3_PARENTAGE_NOT_CONFIRMED";
        }
        if (parentRoles.size() != 2) return "PM3_BOTH_PARENTAGE_CONFIRMATIONS_REQUIRED";
        Map<String, JsonNode> detections = new HashMap<>(), records = new HashMap<>();
        for (JsonNode d : facts.path("detections")) {
            String role = d.path("role").asText(), locus = d.path("alleleRole").asText(); JsonNode member = members.get(role);
            if (member == null || !ALLELES.contains(locus) || detections.put(role + ":" + locus, d) != null
                    || !eq(binding, d.path("binding")) || !eq(member.path("memberId"), d.path("memberId")) || !eq(member.path("sampleId"), d.path("sampleId"))
                    || !BigInteger.ONE.equals(d.path("targetAltIndex").bigIntegerValue()) || !BigInteger.TWO.equals(d.path("ploidy").bigIntegerValue())
                    || !"COVERED".equals(d.path("coverage").asText()) || !"TARGET_DETECTION".equals(d.path("capability").asText())
                    || !"VALID".equals(d.path("quality").asText()) || !"SEQUENCE_ASSAY".equals(d.path("method").asText())
                    || !refs(d.path("sourceIds"), factSources) || !review(d.path("review"), factSources)) return "PM3_DETECTION_INCOMPLETE_OR_CONFLICT";
        }
        for (JsonNode record : facts.path("records")) {
            String role = record.path("role").asText(); JsonNode member = members.get(role);
            String locus = matchingLocus(record.path("variantKey"), facts.path("alleles"));
            if (member == null || locus == null || !eq(record.path("sampleId"), member.path("sampleId"))
                    || !eq(record.path("geneSymbol"), context.at("/gene/symbol")) || records.put(role + ":" + locus, record) != null) return "PM3_RECORD_BINDING_CONFLICT";
            JsonNode detection = detections.get(role + ":" + locus);
            if (detection == null) return "PM3_DETECTION_INCOMPLETE_OR_CONFLICT";
            String issue = callIssue(record, detection); if (issue != null) return issue;
        }
        if (detections.size() != 6 || records.size() != 6) return "PM3_SIX_RECORDS_REQUIRED";
        if (!"HET_ALT".equals(call(records, "PROBAND", "target")) || !"HET_ALT".equals(call(records, "PROBAND", "partner"))) return "PM3_TWO_PROBAND_HET_CALLS_REQUIRED";
        String targetParent = transmittingParent(records, "target"), partnerParent = transmittingParent(records, "partner");
        if (targetParent == null || partnerParent == null) return "PM3_PHASE_UNRESOLVED";
        if (targetParent.equals(partnerParent)) return "PM3_CIS_OUTSIDE_IMPLEMENTED_SCOPE";
        JsonNode phase = observation.path("phase");
        if (!"CONFIRMED_TRANS".equals(phase.path("conclusion").asText()) || !"TRIO_SEGREGATION".equals(phase.path("method").asText())
                || !targetParent.equals(phase.path("targetParent").asText()) || !partnerParent.equals(phase.path("partnerParent").asText())
                || !review(phase.path("review"), partSources.get("phase"))) return "PM3_PHASE_DECLARATION_CONFLICT";
        Set<String> locators = new HashSet<>();
        for (JsonNode ref : phase.path("recordRefs")) {
            String key = ref.path("role").asText() + ":" + ref.path("alleleRole").asText(); JsonNode record = records.get(key);
            if (record == null || !eq(ref.path("sampleId"), record.path("sampleId")) || !locators.add(key)) return "PM3_PHASE_RECORD_LOCATOR_CONFLICT";
        }
        if (locators.size() != 6) return "PM3_PHASE_RECORD_LOCATOR_INCOMPLETE";
        String issue = classificationIssue(observation.path("partnerClassification"), binding, partSources.get("partnerClassification"));
        if (issue != null) return issue;
        for (String role : ALLELES) {
            issue = rarityIssue(observation.path(role + "Rarity"), role, binding, policy.path("scope"), partSources.get(role + "Rarity"));
            if (issue != null) return issue;
        }
        return null;
    }

    private @Nullable String sourceIssue(JsonNode document, Set<String> local, Set<String> all) {
        if (document.path("sources").isEmpty()) return "PM3_SOURCE_MISSING";
        ObjectNode projection = ((ObjectNode) document).deepCopy(); projection.remove("sources");
        for (JsonNode source : document.path("sources")) {
            String id = nfc(source.path("sourceId"));
            if (!local.add(id) || !all.add(id)) return "PM3_SOURCE_ID_COLLISION";
            if (!FamilyDeNovoPrerequisites.date(source.path("acquiredAt"))) return "PM3_SOURCE_DATE_INVALID";
            byte[] bytes = source.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8);
            if (!FamilyDeNovoJson.sha256(bytes).equals(source.path("rawSha256").asText())) return "PM3_SOURCE_RAW_PIN_MISMATCH";
            ObjectNode report; String canonical;
            try { report = Pm3InTransBundle.JSON.parse(mapper, bytes); canonical = adapter.canonicalSha256(report); }
            catch (IllegalArgumentException invalid) { return "PM3_SOURCE_DOCUMENT_INVALID"; }
            if (!canonical.equals(source.path("canonicalSha256").asText())) return "PM3_SOURCE_CANONICAL_PIN_MISMATCH";
            if (!eq(report, projection)) return "PM3_SOURCE_FACTS_MISMATCH";
        }
        return null;
    }
    private @Nullable String classificationIssue(JsonNode c, JsonNode binding, Set<String> sources) {
        if (!eq(c.path("alleleId"), binding.path("partnerAlleleId")) || !Set.of("PATHOGENIC", "LIKELY_PATHOGENIC").contains(c.path("classification").asText())
                || !eq(c.path("classification"), c.at("/assertion/classification")) || !"NONE".equals(c.at("/assertion/conflict").asText())
                || !"REVIEWED".equals(c.at("/assertion/status").asText()) || !refs(c.at("/assertion/sourceIds"), sources) || !review(c.path("review"), sources)) return "PM3_PARTNER_CLASSIFICATION_UNSUITABLE";
        JsonNode independence = c.path("independence");
        if (!"INDEPENDENT".equals(independence.path("conclusion").asText()) || !review(independence.path("review"), sources)) return "PM3_CLASSIFICATION_INDEPENDENCE_UNRESOLVED";
        for (String field : List.of("targetAlleleId", "pairId", "caseId")) if (!eq(independence.path(field), binding.path(field))) return "PM3_CLASSIFICATION_INDEPENDENCE_BINDING_MISMATCH";
        for (String field : List.of("targetContribution", "pairContribution", "caseContribution", "pm3Contribution"))
            if (!"REVIEWED_EXCLUDED".equals(independence.path(field).asText())) return "PM3_CLASSIFICATION_DEPENDENT_OR_UNKNOWN";
        for (JsonNode d : independence.path("dependencies")) {
            if (!Set.of("INDEPENDENT_FUNCTIONAL_STUDY", "INDEPENDENT_POPULATION_STUDY", "UNRELATED_CASE").contains(d.path("kind").asText())
                    || List.of("targetAlleleId", "pairId", "caseId", "occurrenceId", "patientContextId", "familyContextId").stream().anyMatch(k -> eq(d.path("identity"), binding.path(k)))
                    || "ACMG_AMP_2015:PM3".equals(d.path("identity").asText())) return "PM3_CLASSIFICATION_CIRCULAR";
        }
        return null;
    }
    private @Nullable String rarityIssue(JsonNode r, String role, JsonNode binding, JsonNode scope, Set<String> sources) {
        if (!role.equals(r.path("alleleRole").asText()) || !eq(binding.path(role + "AlleleId"), r.path("alleleId"))) return "PM3_RARITY_ALLELE_MISMATCH";
        for (var expected : Map.of("conclusion", "SATISFIED", "availability", "AVAILABLE", "queryOutcome", "HIT", "coverage", "COVERED", "quality", "VALID", "applicability", "APPLICABLE").entrySet())
            if (!expected.getValue().equals(r.path(expected.getKey()).asText())) return "PM3_" + role.toUpperCase(java.util.Locale.ROOT) + "_RARITY_INCOMPLETE";
        JsonNode p = r.path("frequencyPolicy");
        if (!"ACMG_AMP_2015:PM2".equals(p.path("criterionId").asText()) || !"LESS_THAN_OR_EQUAL".equals(p.path("comparison").asText())
                || !eq(scope, p.path("scope")) || !review(p.path("review"), sources) || !review(r.path("review"), sources)) return "PM3_RARITY_POLICY_INVALID";
        String actual = r.path("observedAf").asText(), limit = p.path("maximumAf").asText();
        if (!actual.matches("(0|1)(\\.[0-9]{1,18})?") || !limit.matches("(0|1)(\\.[0-9]{1,18})?")) return "PM3_RARITY_DECIMAL_INVALID";
        BigDecimal af = new BigDecimal(actual), maximum = new BigDecimal(limit);
        if (af.compareTo(BigDecimal.ONE) > 0 || maximum.compareTo(BigDecimal.ONE) > 0 || af.compareTo(maximum) > 0) return "PM3_RARITY_POLICY_CONTRADICTION";
        return null;
    }
    private @Nullable String matchingLocus(JsonNode key, JsonNode alleles) {
        for (String role : ALLELES) {
            ObjectNode allele = ((ObjectNode) alleles.path(role).path("normalizedAllele")).deepCopy();
            allele.set("chromosome", allele.remove("contig"));
            if (eq(key, allele)) return role;
        }
        return null;
    }
    private @Nullable String callIssue(JsonNode record, JsonNode detection) {
        JsonNode g = record.path("observation");
        if (g.isNull() || !eq(g.path("role"), record.path("role"))) return "PM3_CALL_MISSING_OR_ROLE_CONFLICT";
        if (!"PASS".equals(record.path("recordFilter").asText()) || !"PASS".equals(record.path("sampleFilter").asText())) return "PM3_FILTER_FAILED";
        String gt = g.path("genotype").asText();
        if (!gt.matches("[01][/|][01]")) return "PM3_GT_INCOMPLETE_OR_UNSUPPORTED";
        String[] keys = detection.path("sampleFormat").asText().split(":", -1), values = record.path("rawSampleValue").asText().split(":", -1);
        if (keys.length != values.length) return "PM3_RAW_FORMAT_CONFLICT";
        Map<String, String> raw = new HashMap<>();
        for (int i = 0; i < keys.length; i++) if (keys[i].isBlank() || raw.put(keys[i], values[i]) != null) return "PM3_RAW_FORMAT_CONFLICT";
        if (!gt.equals(raw.get("GT")) || g.path("phased").asBoolean() != gt.contains("|")) return "PM3_RAW_GT_CONFLICT";
        // This bounded detection profile requires explicit FT. Absence never becomes PASS.
        if (!"PASS".equals(raw.get("FT"))) return "PM3_RAW_FT_MISSING_OR_FAILED";
        for (var field : Map.of("DP", "depth", "GQ", "genotypeQuality", "AD", "alleleDepth").entrySet())
            if (g.path(field.getValue()).isNull() || !g.path(field.getValue()).asText().equals(raw.get(field.getKey()))) return "PM3_RAW_QUALITY_MISSING_OR_CONFLICT";
        if (g.path("depth").bigIntegerValue().signum() <= 0 || g.path("genotypeQuality").bigIntegerValue().signum() < 0) return "PM3_RAW_QUALITY_INVALID";
        String expected = gt.charAt(0) != gt.charAt(2) ? "HET_ALT" : gt.charAt(0) == '1' ? "HOM_ALT" : "HOM_REF";
        if (!expected.equals(g.path("call").asText())) return "PM3_GT_CALL_CONFLICT";
        String[] ad = g.path("alleleDepth").asText().split(",", -1);
        if (ad.length != 2 || !ad[0].matches("[0-9]{1,18}") || !ad[1].matches("[0-9]{1,18}")) return "PM3_AD_INVALID";
        BigInteger ref = new BigInteger(ad[0]), alt = new BigInteger(ad[1]);
        if (("HET_ALT".equals(expected) && (ref.signum() == 0 || alt.signum() == 0))
                || ("HOM_REF".equals(expected) && ref.signum() == 0) || ("HOM_ALT".equals(expected) && alt.signum() == 0)) return "PM3_AD_CALL_CONFLICT";
        return null;
    }
    private static String call(Map<String, JsonNode> records, String role, String locus) { return records.get(role + ":" + locus).at("/observation/call").asText(); }
    private static @Nullable String transmittingParent(Map<String, JsonNode> records, String locus) {
        if ("HET_ALT".equals(call(records, "FATHER", locus)) && "HOM_REF".equals(call(records, "MOTHER", locus))) return "FATHER";
        if ("HOM_REF".equals(call(records, "FATHER", locus)) && "HET_ALT".equals(call(records, "MOTHER", locus))) return "MOTHER";
        return null;
    }
    private boolean eq(JsonNode a, JsonNode b) { return adapter.canonicalSha256(a).equals(adapter.canonicalSha256(b)); }
    static String nfc(JsonNode n) { return Normalizer.normalize(n.asText(), Normalizer.Form.NFC); }
    private static boolean refs(JsonNode references, Set<String> sources) {
        if (!references.isArray() || references.isEmpty()) return false;
        Set<String> seen = new HashSet<>();
        for (JsonNode ref : references) if (!sources.contains(nfc(ref)) || !seen.add(nfc(ref))) return false;
        return true;
    }
    private static boolean review(JsonNode review, Set<String> sources) {
        return "REVIEWED".equals(review.path("status").asText()) && FamilyDeNovoPrerequisites.date(review.path("date")) && refs(review.path("sourceIds"), sources);
    }
    String pairId(JsonNode binding) {
        var ids = new java.util.ArrayList<>(List.of(nfc(binding.path("targetAlleleId")), nfc(binding.path("partnerAlleleId"))));
        java.util.Collections.sort(ids);
        return "pair-sha256-" + adapter.canonicalSha256(mapper.valueToTree(ids));
    }
}
