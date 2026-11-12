package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Common source, detection and clinical prerequisites, preserving legacy PS2 order/reasons. */
final class FamilyDeNovoPrerequisites {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final String prefix;
    private final String mode;
    FamilyDeNovoPrerequisites(ObjectMapper mapper, String prefix, String mode) { this.mapper=mapper; this.adapter=new RuntimeEvidenceV2Adapter(mapper); this.prefix=prefix; this.mode=mode; }
    @FunctionalInterface interface ParentageCheck { @Nullable String validate(JsonNode facts, Map<String, JsonNode> members, JsonNode proband, Set<String> sourceIds); }
    @Nullable String validate(JsonNode context, ObjectNode facts, ObjectNode assessment, ObjectNode policy, ParentageCheck parentage) {
        if (!mode.equals(policy.path("mode").asText())) return prefix + "POLICY_MODE_UNSUPPORTED";
        if (!adapter.canonicalSha256(facts).equals(assessment.path("factsSha256").asText())
                || !adapter.canonicalSha256(facts).equals(policy.path("factsSha256").asText())) return prefix + "FACTS_PIN_MISMATCH";
        ObjectNode reviewProjection = assessment.deepCopy(); reviewProjection.remove("contentSha256");
        if (!adapter.canonicalSha256(reviewProjection).equals(assessment.path("contentSha256").asText())
                || !adapter.canonicalSha256(assessment).equals(policy.path("assessmentSha256").asText())) return prefix + "ASSESSMENT_PIN_MISMATCH";
        JsonNode b = facts.path("binding"), allele = context.at("/variant/evidenceQueryAllele");
        if (!b.path("observationId").equals(facts.path("observationId"))
                || !b.path("contextId").equals(context.path("contextId"))
                || !b.path("alleleId").equals(context.at("/variant/alleleId"))
                || !b.path("allele").equals(allele) || !b.path("hgncId").equals(context.at("/gene/hgncId"))
                || !b.path("mondoId").equals(context.at("/disease/mondoId")) || !b.path("moi").equals(context.path("moi"))
                || !b.path("patientContextId").equals(context.at("/patientContext/patientContextId"))
                || !b.path("familyContextId").equals(context.at("/patientContext/familyContextId"))
                || !facts.path("familyId").equals(b.path("familyContextId"))) return prefix + "OBSERVATION_CONTEXT_MISMATCH";
        if (!"GRCh38".equals(allele.path("assembly").asText()) || !allele.path("contig").asText().matches("(?:[1-9]|1[0-9]|2[0-2])")
                || !"AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !"SNV".equals(context.at("/variant/variantType").asText())
                || !allele.path("reference").asText().matches("[ACGT]") || !allele.path("alternate").asText().matches("[ACGT]"))
            return prefix + "NATIVE_AUTOSOMAL_AD_SNV_SCOPE_UNSUPPORTED";
        Set<String> sourceIds = new HashSet<>();
        ObjectNode sourceProjection = facts.deepCopy(); sourceProjection.remove("sources");
        for (JsonNode source : facts.path("sources")) {
            if (!sourceIds.add(source.path("sourceId").asText())) return prefix + "SOURCE_ID_CONFLICT";
            byte[] raw = source.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8);
            if (!FamilyDeNovoJson.sha256(raw).equals(source.path("rawSha256").asText())) return prefix + "SOURCE_RAW_PIN_MISMATCH";
            String documentSha256;
            try {
                ObjectNode document = new FamilyDeNovoJson(prefix).parse(mapper, raw);
                documentSha256 = adapter.canonicalSha256(document);
            }
            catch (IllegalArgumentException invalid) { return prefix + "SOURCE_DOCUMENT_INVALID"; }
            if (!documentSha256.equals(source.path("canonicalSha256").asText())) return prefix + "SOURCE_CANONICAL_PIN_MISMATCH";
            if (!documentSha256.equals(adapter.canonicalSha256(sourceProjection))) return prefix + "SOURCE_FACTS_MISMATCH";
            if (!date(source.path("acquiredAt"))) return prefix + "SOURCE_DATE_INVALID";
        }
        if (sourceIds.isEmpty()) return prefix + "SOURCE_MISSING";
        for (String section : List.of("quality", "parentage", "clinical", "scope")) {
            JsonNode review = assessment.path(section);
            if (!"REVIEWED".equals(review.path("status").asText()) || !date(review.path("date"))
                    || !sources(review.path("sourceIds"), sourceIds)) return prefix + section.toUpperCase(java.util.Locale.ROOT) + "_ASSESSMENT_INCOMPLETE";
        }
        if (facts.path("members").size() != 3 || facts.path("records").size() != 3) return prefix + "THREE_DETECTIONS_REQUIRED";
        Map<String, JsonNode> members = new HashMap<>(), records = new HashMap<>();
        Set<String> memberIds = new HashSet<>(), sampleIds = new HashSet<>();
        for (JsonNode m : facts.path("members")) {
            if (members.put(m.path("role").asText(), m) != null
                    || !memberIds.add(Normalizer.normalize(m.path("memberId").asText(), Normalizer.Form.NFC))
                    || !sampleIds.add(Normalizer.normalize(m.path("sampleId").asText(), Normalizer.Form.NFC)))
                return prefix + "MEMBER_OR_SAMPLE_CONFLICT";
        }
        if (!members.keySet().equals(Set.of("PROBAND", "MOTHER", "FATHER"))) return prefix + "THREE_DETECTIONS_REQUIRED";
        JsonNode proband = members.get("PROBAND");
        if (proband == null || !proband.path("memberId").equals(b.path("patientContextId"))
                || !proband.path("affectedStatus").equals(context.at("/patientContext/affectedStatus"))) return prefix + "PROBAND_IDENTITY_MISMATCH";
        for (JsonNode r : facts.path("records")) if (records.put(r.path("role").asText(), r) != null) return prefix + "RECORD_ROLE_CONFLICT";
        for (String role : List.of("PROBAND", "MOTHER", "FATHER")) {
            JsonNode m = members.get(role), r = records.get(role);
            if (m == null || r == null) return prefix + "THREE_DETECTIONS_REQUIRED";
            JsonNode d = m.path("detection"), g = r.path("observation"), key = r.path("variantKey");
            if (!b.equals(d.path("binding")) || !d.path("memberId").equals(m.path("memberId"))
                    || !r.path("sampleId").equals(m.path("sampleId")) || !role.equals(g.path("role").asText())) return prefix + "DETECTION_MEMBER_MISMATCH";
            for (String field : List.of("assembly", "position", "reference", "alternate"))
                if (!key.path(field).equals(allele.path(field))) return prefix + "RECORD_ALLELE_MISMATCH";
            if (!key.path("chromosome").equals(allele.path("contig"))) return prefix + "RECORD_ALLELE_MISMATCH";
            // The strict loader accepts long integers; never narrow them before checking this bounded scope.
            if (d.path("targetAltIndex").longValue() != 1L || d.path("ploidy").longValue() != 2L) return prefix + "ALLELIC_SCOPE_UNSUPPORTED";
            if (!"COVERED".equals(d.path("coverage").asText()) || !"TARGET_DETECTION".equals(d.path("capability").asText())
                    || !"VALID".equals(d.path("quality").asText()) || !sources(d.path("sourceIds"), sourceIds)
                    || !"PASS".equals(r.path("recordFilter").asText()) || !"PASS".equals(r.path("sampleFilter").asText())) return prefix + "DETECTION_QUALITY_INCOMPLETE";
            String issue = genotype(g, r.path("rawSampleValue").asText(), d.path("sampleFormat").asText(), r.path("sampleFilter").asText());
            if (issue != null) return issue;
            if ("PROBAND".equals(role) && "HOM_ALT".equals(g.path("call").asText())) return prefix + "PROBAND_HOM_ALT_UNSUPPORTED";
        }
        String parentageIssue = parentage.validate(facts, members, proband, sourceIds);
        if (parentageIssue != null) return parentageIssue;
        JsonNode c = facts.path("clinical");
        if (!b.equals(c.path("binding")) || !proband.path("memberId").equals(c.path("probandMemberId"))) return prefix + "CLINICAL_BINDING_MISMATCH";
        if (!sources(c.path("sourceIds"), sourceIds) || !date(c.path("observedAt")) || c.path("phenotypeObservations").isEmpty()
                || !"AFFECTED".equals(proband.path("affectedStatus").asText()) || !"REVIEWED".equals(c.path("familyHistory").asText())) return prefix + "CLINICAL_OR_FAMILY_HISTORY_INCOMPLETE";
        // Review completion is an action, not an absence/applicability conclusion. The whole object is
        // bound by source facts, clinical binding and the existing facts -> assessment -> policy pins.
        JsonNode conclusion = c.path("applicability");
        if (conclusion.isMissingNode()) return prefix + "CLINICAL_APPLICABILITY_MISSING";
        if (!sources(conclusion.path("sourceIds"), sourceIds) || !date(conclusion.path("date")))
            return prefix + "CLINICAL_APPLICABILITY_REVIEW_INCOMPLETE";
        if (!"ABSENT".equals(conclusion.path("relatedFamilyHistory").asText())) return prefix + "RELATED_FAMILY_HISTORY_NOT_ABSENT";
        if (!"APPLICABLE".equals(conclusion.path("singleTrioScope").asText())) return prefix + "SINGLE_TRIO_CLINICAL_SCOPE_NOT_APPLICABLE";
        if (!"REVIEWED_NONMOSAIC".equals(c.path("mosaicism").asText())) return prefix + "MOSAICISM_SCOPE_UNSUPPORTED";
        if (!"REVIEWED_ABSENT".equals(c.path("siblingRecurrence").asText())) return prefix + "SIBLING_RECURRENCE_SCOPE_UNSUPPORTED";
        if ("UNKNOWN".equals(c.path("phenotypeCategory").asText()) || "UNKNOWN".equals(c.path("heterogeneity").asText())) return prefix + "PHENOTYPE_ASSESSMENT_UNKNOWN";
        return null;
    }

    @Nullable private String genotype(JsonNode g, String rawSample, String format, String sampleFilter) {
        String call = g.path("call").asText(), gt = g.path("genotype").asText();
        if (Set.of("UNOBSERVED", "UNCONFIRMED_HOM_REF", "NO_CALL", "OTHER").contains(call)) return prefix + "GT_UNINFORMATIVE";
        if (!gt.matches("[01][/|][01]")) return prefix + "GT_PARTIAL_OR_MULTIALLELIC_OR_PLOIDY_UNSUPPORTED";
        String[] keys = format.split(":", -1), values = rawSample.split(":", -1);
        if (keys.length != values.length) return prefix + "RAW_SAMPLE_CONFLICT";
        Map<String, String> raw = new HashMap<>();
        for (int i = 0; i < keys.length; i++) if (raw.put(keys[i], values[i]) != null) return prefix + "RAW_SAMPLE_CONFLICT";
        // An absent FORMAT/FT keeps the existing independently reviewed detection boundary. An
        // explicit FT must have a value consistent with the structured filter; never fill in PASS.
        if (raw.containsKey("FT")) {
            String ft = raw.get("FT");
            if (ft == null || ft.isBlank() || ".".equals(ft)) return prefix + "RAW_FT_MISSING";
            if (!ft.equals(sampleFilter)) return prefix + "RAW_FT_CONFLICT";
            if (!"PASS".equals(ft)) return prefix + "DETECTION_QUALITY_INCOMPLETE";
        }
        if (!gt.equals(raw.get("GT")) || g.path("phased").asBoolean() != gt.contains("|")) return prefix + "RAW_GT_CONFLICT";
        for (var field : Map.of("DP", "depth", "GQ", "genotypeQuality", "AD", "alleleDepth").entrySet()) {
            String value = raw.get(field.getKey()); JsonNode parsed = g.path(field.getValue());
            if (value == null || ".".equals(value)) { if (!parsed.isNull()) return prefix + "RAW_SAMPLE_CONFLICT"; }
            else if (!value.equals(parsed.asText())) return prefix + "RAW_SAMPLE_CONFLICT";
        }
        String expected = gt.charAt(0) != gt.charAt(2) ? "HET_ALT" : gt.charAt(0) == '1' ? "HOM_ALT" : "HOM_REF";
        if (!expected.equals(call)) return prefix + "GT_CALL_CONFLICT";
        for (String metric : List.of("depth", "genotypeQuality"))
            if (g.path(metric).isNumber() && g.path(metric).asLong() < 0) return prefix + "GT_QUALITY_CONFLICT";
        if (g.path("depth").isNumber() && g.path("depth").asLong() == 0) return prefix + "GT_QUALITY_CONFLICT";
        if (!g.path("alleleDepth").isNull()) {
            String[] ad = g.path("alleleDepth").asText().split(",", -1);
            if (ad.length != 2 || !ad[0].matches("[0-9]+") || !ad[1].matches("[0-9]+")) return prefix + "AD_CONFLICT";
            try {
                long ref = Long.parseLong(ad[0]), alt = Long.parseLong(ad[1]);
                if (("HET_ALT".equals(call) && (ref == 0 || alt == 0)) || ("HOM_ALT".equals(call) && alt == 0)
                        || ("HOM_REF".equals(call) && ref == 0)) return prefix + "AD_CONFLICT";
            } catch (NumberFormatException invalid) { return prefix + "AD_CONFLICT"; }
        }
        return null;
    }
    static boolean date(JsonNode n) {
        try { Instant.parse(n.asText()); return true; } catch (java.time.format.DateTimeParseException invalid) { return false; }
    }
    static boolean sources(JsonNode n, Set<String> ids) {
        if (!n.isArray() || n.isEmpty()) return false;
        for (JsonNode id : n) if (!ids.contains(id.asText())) return false;
        return true;
    }
}
