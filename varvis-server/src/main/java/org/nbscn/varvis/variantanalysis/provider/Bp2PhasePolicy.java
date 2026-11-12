package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
final class Bp2PhasePolicy {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private static final List<String> ROLES = List.of("PROBAND", "FATHER", "MOTHER");
    private static final List<String> ALLELES = List.of("target", "partner");

    @Nullable String validate(ObjectNode context, ObjectNode observation, ObjectNode policy) {
        JsonNode facts = observation.path("facts"), binding = facts.path("binding"), assessment = observation.path("assessment");
        Set<String> allSources = new HashSet<>();
        Map<String, Set<String>> partSources = new HashMap<>();
        for (String part : Bp2PhaseBundle.PARTS) {
            JsonNode document = observation.path(part);
            Set<String> local = new HashSet<>(); partSources.put(part, local);
            String issue = sourceIssue(document, local, allSources);
            if (issue != null) return issue;
            if (!eq(binding, document.path("binding"))) return "BP2_PART_BINDING_MISMATCH";
            String hash = adapter.canonicalSha256(document);
            if (!hash.equals(assessment.path("partPins").path(part).asText())
                    || !hash.equals(policy.path("partPins").path(part).asText())) return "BP2_PART_PIN_MISMATCH";
        }
        ObjectNode unsigned = ((ObjectNode) assessment).deepCopy(); unsigned.remove("contentSha256");
        if (!adapter.canonicalSha256(unsigned).equals(assessment.path("contentSha256").asText())
                || !adapter.canonicalSha256(assessment).equals(policy.path("assessmentSha256").asText())) return "BP2_ASSESSMENT_PIN_MISMATCH";
        if (!review(assessment.path("review"), allSources)) return "BP2_ASSESSMENT_UNREVIEWED";
        if (!"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || !Set.of("CONFIRMED_CIS", "CONFIRMED_TRANS_FULLY_PENETRANT_AD").contains(policy.path("mode").asText())
                || !"bp2-six-record-quality-v1".equals(policy.path("qualityProfile").asText())
                || policy.path("criterionIds").size() != 1
                || !Bp2PhaseCriterionEvaluator.CRITERION.equals(policy.at("/criterionIds/0").asText())) return "BP2_POLICY_INVALID";
        if (!Set.of("AUTOSOMAL_RECESSIVE", "AUTOSOMAL_DOMINANT").contains(context.path("moi").asText())
                || !Set.of("AFFECTED", "UNAFFECTED").contains(context.at("/patientContext/affectedStatus").asText())
                || !"PROBAND".equals(context.at("/patientContext/role").asText())) return "BP2_CONTEXT_OUTSIDE_IMPLEMENTED_SCOPE";
        for (var field : Map.of("observationId", "observationId", "occurrenceId", "occurrenceId", "caseId", "caseId", "familyContextId", "familyId").entrySet())
            if (!eq(binding.path(field.getKey()), facts.path(field.getValue()))) return "BP2_FACT_IDENTITY_MISMATCH";
        // Optional in generic v3, required by this single-family BP2 branch before canonical comparison.
        JsonNode familyContextId = context.at("/patientContext/familyContextId");
        if (!familyContextId.isTextual() || familyContextId.asText().isBlank()) return "BP2_FAMILY_CONTEXT_REQUIRED";
        for (var field : Map.of("contextId", "/contextId", "targetAlleleId", "/variant/alleleId", "hgncId", "/gene/hgncId",
                "mondoId", "/disease/mondoId", "moi", "/moi", "patientContextId", "/patientContext/patientContextId",
                "familyContextId", "/patientContext/familyContextId").entrySet())
            if (!eq(binding.path(field.getKey()), context.at(field.getValue()))) return "BP2_CONTEXT_BINDING_MISMATCH";
        if (!eq(facts.at("/alleles/target"), context.path("variant"))) return "BP2_TARGET_IDENTITY_MISMATCH";
        for (String alleleRole : ALLELES) {
            JsonNode variant = facts.at("/alleles/" + alleleRole);
            ObjectNode check = context.deepCopy(); check.set("variant", variant.deepCopy());
            try {
                check.put("contextId", adapter.canonicalVersionedContextId(check)); adapter.validateVersionedContext(check);
            } catch (IllegalArgumentException invalid) { return "BP2_ALLELE_IDENTITY_INVALID"; }
            JsonNode allele = variant.path("normalizedAllele");
            if (!"GRCh38".equals(allele.path("assembly").asText()) || !allele.path("contig").asText().matches("([1-9]|1[0-9]|2[0-2])")
                    || !"SNV".equals(variant.path("variantType").asText()) || !"NATIVE".equals(variant.at("/assemblyLineage/mappingStatus").asText())
                    || !eq(allele, variant.path("inputAllele")) || !eq(allele, variant.path("evidenceQueryAllele"))
                    || !allele.path("reference").asText().matches("[ACGT]") || !allele.path("alternate").asText().matches("[ACGT]")
                    || eq(allele.path("reference"), allele.path("alternate"))) return "BP2_NATIVE_AUTOSOMAL_BIALLELIC_SNV_REQUIRED";
            if (!eq(variant.path("alleleId"), binding.path(alleleRole + "AlleleId"))) return "BP2_ALLELE_ROLE_MISMATCH";
        }
        JsonNode target = facts.at("/alleles/target/normalizedAllele"), partner = facts.at("/alleles/partner/normalizedAllele");
        if (!eq(target.path("contig"), partner.path("contig")) || eq(target.path("position"), partner.path("position"))
                || eq(binding.path("targetAlleleId"), binding.path("partnerAlleleId"))) return "BP2_DISTINCT_LOCI_REQUIRED";
        if (!pairId(binding).equals(binding.path("pairId").asText())) return "BP2_PAIR_IDENTITY_MISMATCH";
        Set<String> factSources = partSources.get("facts");
        Map<String, JsonNode> members = new HashMap<>(); Set<String> memberIds = new HashSet<>(), samples = new HashSet<>();
        for (JsonNode m : facts.path("members")) {
            String role = m.path("role").asText();
            if (!ROLES.contains(role) || members.put(role, m) != null || !memberIds.add(nfc(m.path("memberId")))
                    || !samples.add(nfc(m.path("sampleId")))) return "BP2_MEMBERS_NOT_DISTINCT";
        }
        if (members.size() != 3) return "BP2_COMPLETE_TRIO_REQUIRED";
        JsonNode proband = members.get("PROBAND");
        if (!eq(proband.path("memberId"), binding.path("patientContextId")) || !eq(context.at("/patientContext/affectedStatus"), proband.path("affectedStatus"))) return "BP2_PROBAND_BINDING_MISMATCH";
        JsonNode clinical = facts.path("clinical");
        if (!eq(clinical.path("binding"), binding) || !eq(clinical.path("probandMemberId"), proband.path("memberId"))
                || !"APPLICABLE".equals(clinical.path("diseaseApplicability").asText()) || !eq(proband.path("affectedStatus"), clinical.path("affectedStatus"))
                || !"REVIEWED_NONMOSAIC".equals(clinical.path("mosaicism").asText()) || clinical.path("observations").isEmpty()
                || !review(clinical.path("review"), factSources)) return "BP2_CLINICAL_APPLICABILITY_INCOMPLETE";
        Set<String> parentRoles = new HashSet<>();
        for (JsonNode relation : facts.path("parentage")) {
            String role = relation.path("parentRole").asText(); JsonNode parent = members.get(role);
            if (!Set.of("FATHER", "MOTHER").contains(role) || parent == null || !parentRoles.add(role)
                    || !eq(binding, relation.path("binding")) || !eq(parent.path("memberId"), relation.path("parentMemberId"))
                    || !eq(parent.path("sampleId"), relation.path("parentSampleId")) || !eq(proband.path("memberId"), relation.path("probandMemberId"))
                    || !eq(proband.path("sampleId"), relation.path("probandSampleId")) || !"COMPLETED".equals(relation.path("testStatus").asText())
                    || !"CONFIRMED".equals(relation.path("conclusion").asText()) || !"RELATIONSHIP_SUPPORTED".equals(relation.path("result").asText())
                    || !"INDEPENDENT_RELATIONSHIP_TEST".equals(relation.path("method").asText())
                    || !refs(relation.path("sourceIds"), factSources) || !review(relation.path("review"), factSources)) return "BP2_PARENTAGE_NOT_CONFIRMED";
        }
        if (parentRoles.size() != 2) return "BP2_BOTH_PARENTAGE_CONFIRMATIONS_REQUIRED";
        Map<String, JsonNode> detections = new HashMap<>(), records = new HashMap<>();
        for (JsonNode d : facts.path("detections")) {
            String role = d.path("role").asText(), locus = d.path("alleleRole").asText(); JsonNode member = members.get(role);
            if (member == null || !ALLELES.contains(locus) || detections.put(role + ":" + locus, d) != null
                    || !eq(binding, d.path("binding")) || !eq(member.path("memberId"), d.path("memberId")) || !eq(member.path("sampleId"), d.path("sampleId"))
                    || !BigInteger.ONE.equals(d.path("targetAltIndex").bigIntegerValue()) || !BigInteger.TWO.equals(d.path("ploidy").bigIntegerValue())
                    || !"COVERED".equals(d.path("coverage").asText()) || !"TARGET_DETECTION".equals(d.path("capability").asText())
                    || !"VALID".equals(d.path("quality").asText()) || !"SEQUENCE_ASSAY".equals(d.path("method").asText())
                    || !refs(d.path("sourceIds"), factSources) || !review(d.path("review"), factSources)) return "BP2_DETECTION_INCOMPLETE_OR_CONFLICT";
        }
        for (JsonNode record : facts.path("records")) {
            String role = record.path("role").asText(); JsonNode member = members.get(role);
            String locus = matchingLocus(record.path("variantKey"), facts.path("alleles"));
            if (member == null || locus == null || !eq(record.path("sampleId"), member.path("sampleId"))
                    || !eq(record.path("geneSymbol"), context.at("/gene/symbol")) || records.put(role + ":" + locus, record) != null) return "BP2_RECORD_BINDING_CONFLICT";
            JsonNode detection = detections.get(role + ":" + locus);
            if (detection == null) return "BP2_DETECTION_INCOMPLETE_OR_CONFLICT";
            String issue = callIssue(record, detection); if (issue != null) return issue;
        }
        if (detections.size() != 6 || records.size() != 6) return "BP2_SIX_RECORDS_REQUIRED";
        if (!"HET_ALT".equals(call(records, "PROBAND", "target")) || !"HET_ALT".equals(call(records, "PROBAND", "partner"))) return "BP2_TWO_PROBAND_HET_CALLS_REQUIRED";
        String targetParent = transmittingParent(records, "target"), partnerParent = transmittingParent(records, "partner");
        if (targetParent == null || partnerParent == null) return "BP2_PHASE_UNRESOLVED";
        String derivedPhase = targetParent.equals(partnerParent) ? "CONFIRMED_CIS" : "CONFIRMED_TRANS";
        String expectedMode = targetParent.equals(partnerParent) ? "CONFIRMED_CIS" : "CONFIRMED_TRANS_FULLY_PENETRANT_AD";
        if (!expectedMode.equals(policy.path("mode").asText())) return "BP2_PHASE_MODE_CONFLICT";
        JsonNode phase = observation.path("phase");
        if (!derivedPhase.equals(phase.path("conclusion").asText()) || !"TRIO_SEGREGATION".equals(phase.path("method").asText())
                || !targetParent.equals(phase.path("targetParent").asText()) || !partnerParent.equals(phase.path("partnerParent").asText())
                || !review(phase.path("review"), partSources.get("phase"))) return "BP2_PHASE_DECLARATION_CONFLICT";
        Set<String> locators = new HashSet<>();
        for (JsonNode ref : phase.path("recordRefs")) {
            String key = ref.path("role").asText() + ":" + ref.path("alleleRole").asText(); JsonNode record = records.get(key);
            if (record == null || !eq(ref.path("sampleId"), record.path("sampleId")) || !locators.add(key)) return "BP2_PHASE_RECORD_LOCATOR_CONFLICT";
        }
        if (locators.size() != 6) return "BP2_PHASE_RECORD_LOCATOR_INCOMPLETE";
        String issue = classificationIssue(observation.path("partnerClassification"), binding, partSources.get("partnerClassification"));
        if (issue != null) return issue;
        return diseaseIssue(observation.path("diseaseApplicability"), binding, context, expectedMode, partSources.get("diseaseApplicability"));
    }

    private @Nullable String sourceIssue(JsonNode document, Set<String> local, Set<String> all) {
        if (document.path("sources").isEmpty()) return "BP2_SOURCE_MISSING";
        ObjectNode projection = ((ObjectNode) document).deepCopy(); projection.remove("sources");
        for (JsonNode source : document.path("sources")) {
            String id = nfc(source.path("sourceId"));
            if (!local.add(id) || !all.add(id)) return "BP2_SOURCE_ID_COLLISION";
            if (!FamilyDeNovoPrerequisites.date(source.path("acquiredAt"))) return "BP2_SOURCE_DATE_INVALID";
            byte[] bytes = source.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8);
            if (!FamilyDeNovoJson.sha256(bytes).equals(source.path("rawSha256").asText())) return "BP2_SOURCE_RAW_PIN_MISMATCH";
            ObjectNode report; String canonical;
            try { report = Bp2PhaseBundle.JSON.parse(mapper, bytes); canonical = adapter.canonicalSha256(report); }
            catch (IllegalArgumentException invalid) { return "BP2_SOURCE_DOCUMENT_INVALID"; }
            if (!canonical.equals(source.path("canonicalSha256").asText())) return "BP2_SOURCE_CANONICAL_PIN_MISMATCH";
            if (!eq(report, projection)) return "BP2_SOURCE_FACTS_MISMATCH";
        }
        return null;
    }
    private @Nullable String classificationIssue(JsonNode c, JsonNode binding, Set<String> sources) {
        if (!eq(c.path("alleleId"), binding.path("partnerAlleleId")) || !"PATHOGENIC".equals(c.path("classification").asText())
                || !eq(c.path("classification"), c.at("/assertion/classification")) || !"NONE".equals(c.at("/assertion/conflict").asText())
                || !"REVIEWED".equals(c.at("/assertion/status").asText()) || !refs(c.at("/assertion/sourceIds"), sources) || !review(c.path("review"), sources)) return "BP2_PARTNER_CLASSIFICATION_UNSUITABLE";
        JsonNode independence = c.path("independence");
        if (!"INDEPENDENT".equals(independence.path("conclusion").asText()) || !review(independence.path("review"), sources)) return "BP2_CLASSIFICATION_INDEPENDENCE_UNRESOLVED";
        for (String field : List.of("targetAlleleId", "pairId", "caseId")) if (!eq(independence.path(field), binding.path(field))) return "BP2_CLASSIFICATION_INDEPENDENCE_BINDING_MISMATCH";
        for (String field : List.of("targetContribution", "pairContribution", "caseContribution", "bp2Contribution", "compoundContribution"))
            if (!"REVIEWED_EXCLUDED".equals(independence.path(field).asText())) return "BP2_CLASSIFICATION_DEPENDENT_OR_UNKNOWN";
        if (independence.path("dependencies").isEmpty()) return "BP2_CLASSIFICATION_INDEPENDENCE_UNRESOLVED";
        for (JsonNode d : independence.path("dependencies")) {
            if (!Set.of("INDEPENDENT_FUNCTIONAL_STUDY", "INDEPENDENT_POPULATION_STUDY", "UNRELATED_CASE").contains(d.path("kind").asText())
                    || List.of("targetAlleleId", "pairId", "caseId", "occurrenceId", "patientContextId", "familyContextId").stream().anyMatch(k -> eq(d.path("identity"), binding.path(k)))
                    || "ACMG_AMP_2015:BP2".equals(d.path("identity").asText())) return "BP2_CLASSIFICATION_CIRCULAR";
        }
        return null;
    }
    private @Nullable String diseaseIssue(JsonNode d, JsonNode binding, JsonNode context, String mode, Set<String> sources) {
        if (!mode.equals(d.path("mode").asText()) || !"APPLICABLE".equals(d.path("applicability").asText())
                || !eq(context.at("/patientContext/affectedStatus"), d.path("affectedStatus"))
                || !"REVIEWED_CONSISTENT".equals(d.path("phenotypeConsistency").asText())
                || !"REVIEWED_EXCLUDED".equals(d.path("additionalEffect").asText())
                || !Set.of("LOSS_OF_FUNCTION", "GAIN_OF_FUNCTION", "DOMINANT_NEGATIVE").contains(d.path("mechanism").asText())
                || !review(d.path("review"), sources)) return "BP2_DISEASE_APPLICABILITY_INCOMPLETE";
        Set<String> roles = new HashSet<>();
        for (JsonNode a : d.path("alleleApplicability")) {
            String role = a.path("alleleRole").asText();
            if (!ALLELES.contains(role) || !roles.add(role) || !eq(a.path("alleleId"), binding.path(role + "AlleleId"))
                    || !eq(a.path("hgncId"), binding.path("hgncId")) || !"APPLICABLE".equals(a.path("conclusion").asText())
                    || !refs(a.path("sourceIds"), sources)) return "BP2_TWO_ALLELE_GENE_APPLICABILITY_REQUIRED";
        }
        if (roles.size() != 2) return "BP2_TWO_ALLELE_GENE_APPLICABILITY_REQUIRED";
        if ("CONFIRMED_CIS".equals(mode)) return null; // Cis has no fully penetrant disease prerequisite.
        if (!"AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())
                || !"AFFECTED".equals(context.at("/patientContext/affectedStatus").asText())) return "BP2_TRANS_AFFECTED_AD_REQUIRED";
        JsonNode p = d.path("penetrance");
        if (!"FULLY_PENETRANT".equals(p.path("conclusion").asText()) || !"APPLICABLE".equals(p.path("patientApplicability").asText())
                || !review(p.path("review"), sources)) return "BP2_FULL_PENETRANCE_APPLICABILITY_REQUIRED";
        if (!"bp2-disease-applicability-v2".equals(d.path("schemaVersion").asText())) return "BP2_PENETRANCE_CONDITIONS_NOT_ESTABLISHED";
        Set<String> kinds = new HashSet<>();
        for (JsonNode c : p.path("conditions")) {
            String kind = c.path("kind").asText();
            if (!Set.of("POPULATION", "AGE", "SEX").contains(kind) || !kinds.add(kind)
                    || !Set.of("SATISFIED", "REVIEWED_UNRESTRICTED").contains(c.path("conclusion").asText())
                    || !refs(c.path("sourceIds"), sources)) return "BP2_PENETRANCE_CONDITIONS_UNRESOLVED";
            String field = switch (kind) { case "POPULATION" -> "population"; case "AGE" -> "ageCondition"; default -> "sexCondition"; };
            if (!eq(c.path("requirement"), p.path(field))) return "BP2_PENETRANCE_CONDITIONS_CONFLICT";
            String issue = conditionIssue(c, d, binding, context);
            if (issue != null) return issue;
        }
        return kinds.size() == 3 ? null : "BP2_PENETRANCE_CONDITIONS_UNRESOLVED";
    }
    private @Nullable String conditionIssue(JsonNode c, JsonNode disease, JsonNode binding, JsonNode context) {
        JsonNode rule = c.path("constraint"), patient = c.path("patient");
        if (!eq(binding, patient.path("binding")) || !eq(disease.path("mechanism"), patient.path("mechanism"))
                || !eq(disease.path("materialVersion"), patient.path("materialVersion"))) return "BP2_PENETRANCE_PATIENT_BINDING_CONFLICT";
        if ("UNRESTRICTED".equals(rule.path("mode").asText()))
            return "REVIEWED_UNRESTRICTED".equals(c.path("conclusion").asText()) ? null : "BP2_PENETRANCE_REVIEW_CONTRADICTION";
        if (!"SATISFIED".equals(c.path("conclusion").asText())) return "BP2_PENETRANCE_REVIEW_CONTRADICTION";
        if (!"KNOWN".equals(patient.path("status").asText())) return "BP2_PENETRANCE_PATIENT_FACT_UNKNOWN";
        switch (c.path("kind").asText()) {
            case "AGE": {
                if (!"GTE".equals(rule.path("operator").asText()) || !"YEARS".equals(rule.path("unit").asText())
                        || !"YEARS".equals(patient.path("unit").asText())) return "BP2_PENETRANCE_AGE_PROFILE_UNSUPPORTED";
                JsonNode age = patient.path("value"), minimum = rule.path("threshold");
                if (!boundedAge(age) || !boundedAge(minimum)) return "BP2_PENETRANCE_AGE_VALUE_INVALID";
                return age.bigIntegerValue().compareTo(minimum.bigIntegerValue()) >= 0 ? null : "BP2_PENETRANCE_AGE_NOT_SATISFIED";
            }
            case "SEX": {
                String actual = patient.path("value").asText();
                if (!Set.of("MALE", "FEMALE").contains(actual)) return "BP2_PENETRANCE_PATIENT_FACT_UNKNOWN";
                if (!eq(patient.path("value"), context.at("/patientContext/sex"))) return "BP2_PENETRANCE_CONTEXT_SEX_CONFLICT";
                Set<String> allowed = new HashSet<>();
                for (JsonNode value : rule.path("values")) if (!Set.of("MALE", "FEMALE").contains(value.asText()) || !allowed.add(value.asText())) return "BP2_PENETRANCE_SEX_CONDITION_INVALID";
                if (!"IN".equals(rule.path("operator").asText()) || allowed.isEmpty()) return "BP2_PENETRANCE_SEX_CONDITION_INVALID";
                return allowed.contains(actual) ? null : "BP2_PENETRANCE_SEX_NOT_SATISFIED";
            }
            case "POPULATION": {
                if (!knownIdentity(patient.path("populationId")) || !knownIdentity(patient.path("populationSystem"))) return "BP2_PENETRANCE_PATIENT_FACT_UNKNOWN";
                if (!knownIdentity(rule.path("populationSystem")) || !eq(rule.path("populationSystem"), patient.path("populationSystem"))) return "BP2_PENETRANCE_POPULATION_SYSTEM_CONFLICT";
                Set<String> allowed = new HashSet<>();
                for (JsonNode id : rule.path("populationIds")) if (!knownIdentity(id) || !allowed.add(nfc(id))) return "BP2_PENETRANCE_POPULATION_CONDITION_INVALID";
                if (!"IN".equals(rule.path("operator").asText()) || allowed.isEmpty()) return "BP2_PENETRANCE_POPULATION_CONDITION_INVALID";
                return allowed.contains(nfc(patient.path("populationId"))) ? null : "BP2_PENETRANCE_POPULATION_NOT_SATISFIED";
            }
            default: return "BP2_PENETRANCE_CONDITIONS_UNRESOLVED";
        }
    }
    private static boolean boundedAge(JsonNode value) {
        return value.isIntegralNumber() && value.bigIntegerValue().signum() >= 0
                && value.bigIntegerValue().compareTo(BigInteger.valueOf(150)) <= 0;
    }
    private static boolean knownIdentity(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() && !"UNKNOWN".equals(nfc(value));
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
        if (g.isNull() || !eq(g.path("role"), record.path("role"))) return "BP2_CALL_MISSING_OR_ROLE_CONFLICT";
        if (!"PASS".equals(record.path("recordFilter").asText()) || !"PASS".equals(record.path("sampleFilter").asText())) return "BP2_FILTER_FAILED";
        String gt = g.path("genotype").asText();
        if (!gt.matches("[01][/|][01]")) return "BP2_GT_INCOMPLETE_OR_UNSUPPORTED";
        String[] keys = detection.path("sampleFormat").asText().split(":", -1), values = record.path("rawSampleValue").asText().split(":", -1);
        if (keys.length != values.length) return "BP2_RAW_FORMAT_CONFLICT";
        Map<String, String> raw = new HashMap<>();
        for (int i = 0; i < keys.length; i++) if (keys[i].isBlank() || raw.put(keys[i], values[i]) != null) return "BP2_RAW_FORMAT_CONFLICT";
        if (!gt.equals(raw.get("GT")) || g.path("phased").asBoolean() != gt.contains("|")) return "BP2_RAW_GT_CONFLICT";
        // This bounded detection profile requires explicit FT. Absence never becomes PASS.
        if (!"PASS".equals(raw.get("FT"))) return "BP2_RAW_FT_MISSING_OR_FAILED";
        for (var field : Map.of("DP", "depth", "GQ", "genotypeQuality", "AD", "alleleDepth").entrySet())
            if (g.path(field.getValue()).isNull() || !g.path(field.getValue()).asText().equals(raw.get(field.getKey()))) return "BP2_RAW_QUALITY_MISSING_OR_CONFLICT";
        if (g.path("depth").bigIntegerValue().compareTo(BigInteger.valueOf(20)) < 0 || g.path("genotypeQuality").bigIntegerValue().compareTo(BigInteger.valueOf(30)) < 0) return "BP2_RAW_QUALITY_INVALID";
        String expected = gt.charAt(0) != gt.charAt(2) ? "HET_ALT" : gt.charAt(0) == '1' ? "HOM_ALT" : "HOM_REF";
        if (!expected.equals(g.path("call").asText())) return "BP2_GT_CALL_CONFLICT";
        String[] ad = g.path("alleleDepth").asText().split(",", -1);
        if (ad.length != 2 || !ad[0].matches("[0-9]{1,18}") || !ad[1].matches("[0-9]{1,18}")) return "BP2_AD_INVALID";
        BigInteger ref = new BigInteger(ad[0]), alt = new BigInteger(ad[1]);
        if (("HET_ALT".equals(expected) && (ref.signum() == 0 || alt.signum() == 0))
                || ("HOM_REF".equals(expected) && ref.signum() == 0) || ("HOM_ALT".equals(expected) && alt.signum() == 0)) return "BP2_AD_CALL_CONFLICT";
        if (!ref.add(alt).equals(g.path("depth").bigIntegerValue())) return "BP2_AD_DEPTH_CONFLICT";
        if ("HOM_REF".equals(expected) && alt.signum() != 0) return "BP2_HOM_REF_ALT_READS_OUTSIDE_PROFILE";
        if ("HET_ALT".equals(expected) && (alt.multiply(BigInteger.valueOf(5)).compareTo(ref.add(alt)) < 0
                || ref.multiply(BigInteger.valueOf(5)).compareTo(ref.add(alt)) < 0)) return "BP2_ALLELE_BALANCE_INVALID";
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
