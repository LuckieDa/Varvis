package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Versioned quality decisions, distinct from observations and threshold arithmetic. */
final class Bs1Quality {
    private Bs1Quality() { }
    static String issue(JsonNode q) {
        if ("bs1-local-exomes-quality-v1".equals(q.path("contractId").asText()))
            return Bs1LocalExomes.qualityContract().equals(q) ? null : "BS1_LOCAL_QUALITY_CONTRACT_INVALID";
        if (!Bs1Policy.keys(q, Set.of("contractId", "version", "officialConditions", "releaseSemantics", "engineering"))
                || q.path("contractId").asText().isBlank() || q.path("version").asText().isBlank()) return "BS1_QUALITY_CONTRACT_INVALID";
        var official = q.path("officialConditions"); var release = q.path("releaseSemantics"); var engineering = q.path("engineering");
        if (!Bs1Policy.keys(official, Set.of("rejectFilteredCalls", "sourceLocator")) || !official.path("rejectFilteredCalls").asBoolean()
                || official.path("sourceLocator").asText().isBlank()) return "BS1_QUALITY_OFFICIAL_CONDITION_UNSUPPORTED";
        if (!Bs1Policy.keys(release, Set.of("version", "headerSha256", "sourceLocator", "subtypeDot", "missingSubtype", "pValueField", "pValueThreshold", "pMissing", "notApplicableBasis", "jointFilterDefinition"))
                || !Bs1Policy.sha(release.path("headerSha256")) || release.path("sourceLocator").asText().isBlank()
                || !Set.of("UNKNOWN_REQUIRES_NATIVE", "RAW_UNKNOWN_FILTER_BY_BOUND_JOINT").contains(release.path("subtypeDot").asText())
                || !Set.of("REQUIRED", "OPTIONAL_WITH_NATIVE_PROOF", "OPTIONAL_WITH_BOUND_JOINT_FILTER").contains(release.path("missingSubtype").asText())
                || !Set.of("REQUIRED_OR_UNKNOWN", "ONE_DATASET_ABSENT_WITH_NATIVE_PROOF").contains(release.path("pMissing").asText())
                || !"stat_union_p_value".equals(release.path("pValueField").asText())) return "BS1_RELEASE_SEMANTICS_UNSUPPORTED";
        if (release.has("jointFilterDefinition") && !"GNOMAD_4_1_JOINT_FILTER".equals(release.path("jointFilterDefinition").asText())
                || ("OPTIONAL_WITH_BOUND_JOINT_FILTER".equals(release.path("missingSubtype").asText())
                    || "RAW_UNKNOWN_FILTER_BY_BOUND_JOINT".equals(release.path("subtypeDot").asText())) && !release.has("jointFilterDefinition"))
            return "BS1_RELEASE_SEMANTICS_UNSUPPORTED";
        try { if (new BigDecimal(release.path("pValueThreshold").asText()).compareTo(new BigDecimal("0.0001")) != 0) return "BS1_DISCORDANCE_THRESHOLD_UNSUPPORTED"; }
        catch (NumberFormatException bad) { return "BS1_DISCORDANCE_THRESHOLD_UNSUPPORTED"; }
        if (!Bs1Policy.keys(engineering, Set.of("kind", "discordance", "afCountPrecision", "sourceLocator"))
                || !"LOCAL_ENGINEERING_SAFEGUARD".equals(engineering.path("kind").asText())
                || !"ABSTAIN_SIGNIFICANT_UNEXPLAINED".equals(engineering.path("discordance").asText())
                || !"VCF_FLOAT32_WITH_DECIMAL_ROUNDING".equals(engineering.path("afCountPrecision").asText())
                || engineering.path("sourceLocator").asText().isBlank()) return "BS1_ENGINEERING_SAFEGUARD_UNSUPPORTED";
        return null;
    }

    static ObjectNode assess(ObjectMapper mapper, JsonNode observation, JsonNode policy, JsonNode nativeFacts) {
        return assess(mapper, observation, policy, nativeFacts, true);
    }

    /** Source quality only; the caller must verify the sealed parent and qualify its own statistic separately. */
    static ObjectNode assessSource(ObjectMapper mapper, JsonNode observation, JsonNode policy, JsonNode nativeFacts) {
        return assess(mapper, observation, policy, nativeFacts, false);
    }

    private static ObjectNode assess(ObjectMapper mapper, JsonNode observation, JsonNode policy, JsonNode nativeFacts,
            boolean aggregateStatistic) {
        var result = mapper.createObjectNode().put("schemaVersion", "bs1-quality-decision-v2")
                .put("adapterVersion", Bs1JointFilterSource.VERSION)
                .put("contractSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(policy.path("qualityContract")));
        result.set("contract", policy.path("qualityContract")); var issues = result.putArray("issues");
        var facts = result.putObject("facts"); var fieldStates = result.putObject("rawFieldStates");
        JsonNode carrier = observation.path("carrier");
        if (!policy.at("/qualityContract/releaseSemantics/headerSha256").equals(observation.at("/headerContract/sha256"))
                || !policy.at("/qualityContract/releaseSemantics/version").equals(observation.at("/resourceContract/identity/release"))
                || !observation.at("/headerContract/resourceFingerprintSha256").equals(carrier.at("/source/resourceSha256")))
            add(issues, "BS1_HEADER_RELEASE_BINDING_INVALID", "headerContract");
        if (!"SUCCEEDED".equals(carrier.at("/resourceExecution/status").asText())
                || !"AVAILABLE".equals(carrier.at("/resourceAvailability/status").asText())
                || !"VERIFIED".equals(observation.path("outputCompleteness").asText())) {
            add(issues, "BS1_RESOURCE_EXECUTION_UNAVAILABLE", "resourceExecution"); return finish(result, issues);
        }
        if (!"HIT".equals(carrier.at("/queryOutcome/status").asText())) {
            add(issues, "BS1_NO_RECORD", "queryOutcome"); return finish(result, issues);
        }
        var parsed = raw(observation.path("rawRecord").asText());
        if (!"PASS".equals(parsed.get("FILTER"))) add(issues, "BS1_JOINT_FILTER_NOT_PASS", "FILTER");
        if (aggregateStatistic) {
            String ancestry = measure(carrier, "gnomad.joint.faf95max.ancestry");
            if (ancestry == null || !Bs1Policy.contains(policy.at("/statistic/ancestries"), ancestry)) add(issues, "BS1_FAF_ANCESTRY_UNSUPPORTED", "fafmax_faf95_max_gen_anc_joint");
            else {
                facts.put("ancestry", ancestry);
                BigDecimal faf = number(measure(carrier, "gnomad.joint.faf95max"));
                String prefix = "gnomad.joint.ancestry." + ancestry + ".";
                BigDecimal linked = number(measure(carrier, prefix + "faf95"));
                BigDecimal an = number(measure(carrier, prefix + "an")), ac = number(measure(carrier, prefix + "ac"));
                BigDecimal af = number(measure(carrier, prefix + "af"));
                if (faf == null || linked == null || !fraction(faf) || faf.compareTo(linked) != 0) add(issues, "BS1_FAF_ANCESTRY_MISMATCH_OR_MISSING", "FAF95");
                else facts.put("FAF95", faf.toPlainString());
                if (an == null || an.signum() <= 0 || an.stripTrailingZeros().scale() > 0) add(issues, "BS1_SELECTED_AN_INVALID", "AN");
                else facts.put("AN", an.toPlainString());
                if (ac == null || ac.signum() < 0 || ac.stripTrailingZeros().scale() > 0 || an == null || ac.compareTo(an) > 0) add(issues, "BS1_SELECTED_AC_INVALID", "AC");
                else facts.put("AC", ac.toPlainString());
                if (af == null || !fraction(af) || faf == null || faf.compareTo(af) > 0) add(issues, "BS1_SELECTED_AF_INVALID", "AF");
                else if (an != null && ac != null && an.signum() > 0 && !countsAgree(ac, an, af)) add(issues, "BS1_COUNT_AF_CONFLICT", "AF/AC/AN");
            }
        }
        var release = policy.at("/qualityContract/releaseSemantics");
        boolean jointDefinitionRequested = release.has("jointFilterDefinition");
        boolean bound = jointDefinitionRequested && Bs1JointFilterSource.bound(mapper, observation, release);
        boolean jointPass = "PASS".equals(parsed.get("FILTER"));
        if (jointDefinitionRequested && !bound) add(issues, "BS1_JOINT_FILTER_DEFINITION_UNBOUND", "source");
        var filterCheck = result.putObject("filterCondition").put("observedJointFilter", parsed.get("FILTER"))
                .put("basis", bound ? "JOINT_FILTER_DEFINITION" : "EXPLICIT_SUBTYPE_OR_NATIVE_FACTS");
        filterCheck.putArray("sourceEvidenceIds").add(observation.path("observationId").asText());
        if (bound) {
            filterCheck.set("sourceContract", Bs1JointFilterSource.forObservation(mapper, observation));
            filterCheck.put("limitation", "Joint PASS does not prove exact-variant presence in both datasets");
        }
        boolean conflict = false, filterUnknown = jointDefinitionRequested && !bound;
        int absent = 0, present = 0;
        for (String subtype : List.of("exomes", "genomes")) {
            String key = subtype + "_filters", value = parsed.get(key), state = fieldState(parsed, key);
            var field = fieldStates.putObject(key).put("state", state).put("raw", value);
            String nativeState = Bs1NativeFilterSupplement.verifiedState(mapper, nativeFacts.path(subtype), subtype, observation, policy);
            if (nativeFacts.has(subtype)) {
                field.put("nativeStatus", nativeState);
                if (Set.of("PASS", "FILTERED", "NO_RECORD").contains(nativeState))
                    filterCheck.withArray("/sourceEvidenceIds").add(nativeFacts.path(subtype).path("factId").asText());
                else { add(issues, "BS1_NATIVE_FACT_UNRESOLVED", key); filterUnknown = true; }
            }
            if ("NO_RECORD".equals(nativeState)) absent++;
            else if ("PASS".equals(nativeState) || "PASS".equals(state)) present++;
            if ("FILTERED".equals(state) || "FILTERED".equals(nativeState)) {
                conflict |= jointPass;
                add(issues, jointPass ? "BS1_JOINT_SUBTYPE_FILTER_CONFLICT" : "BS1_SUBTYPE_FILTER_NOT_PASS", key);
                continue;
            }
            if ("PASS".equals(state) && "NO_RECORD".equals(nativeState)) {
                conflict = true; add(issues, "BS1_SUBTYPE_PRESENCE_CONFLICT", key); continue;
            }
            if ("EMPTY".equals(state)) { add(issues, "BS1_REQUIRED_FILTER_INFO_MISSING", key); filterUnknown = true; continue; }
            if ("PASS".equals(state)) continue;
            if (bound && jointPass && ("MISSING_KEY".equals(state) && "OPTIONAL_WITH_BOUND_JOINT_FILTER".equals(release.path("missingSubtype").asText())
                    || "DOT".equals(state) && "RAW_UNKNOWN_FILTER_BY_BOUND_JOINT".equals(release.path("subtypeDot").asText()))) continue;
            if ("MISSING_KEY".equals(state) && "REQUIRED".equals(release.path("missingSubtype").asText())) {
                add(issues, "BS1_REQUIRED_FILTER_INFO_MISSING", key); filterUnknown = true; continue;
            }
            if (!Set.of("NO_RECORD", "PASS").contains(nativeState)) {
                add(issues, "BS1_SUBTYPE_QUALITY_UNRESOLVED", key); filterUnknown = true;
            }
        }
        if (absent == 2) { conflict = true; add(issues, "BS1_JOINT_NATIVE_PRESENCE_CONFLICT", "datasets"); }
        filterCheck.put("status", conflict ? "CONFLICT" : !jointPass
                        ? parsed.get("FILTER") == null || Set.of("", ".").contains(parsed.get("FILTER")) ? "UNKNOWN" : "FAILED"
                        : filterUnknown ? "UNKNOWN" : "SATISFIED")
                .put("decision", bound && jointPass && !conflict && !filterUnknown
                        ? "SATISFIED_BY_JOINT_FILTER_DEFINITION" : "SEE_FILTER_CHECKS");
        String pKey = release.path("pValueField").asText();
        String selectedP = selectedAltField(observation, parsed.get(pKey));
        BigDecimal p = number(selectedP);
        String pState = fieldState(parsed, pKey);
        boolean missingP = "MISSING_KEY".equals(pState) || ".".equals(selectedP);
        var statistic = result.putObject("discordance").put("rawState", "FILTERED".equals(pState) ? "VALUE" : pState).put("field", pKey)
                .put("testName", selectedAltField(observation, parsed.get("stat_union_test_name"))).put("ancestries", parsed.get("stat_union_gen_ancs"))
                .put("signalThreshold", release.path("pValueThreshold").asText())
                .put("dispositionBasis", "LOCAL_ENGINEERING_SAFEGUARD");
        if (p == null) {
            boolean notApplicable = missingP && absent == 1 && present == 1 && "ONE_DATASET_ABSENT_WITH_NATIVE_PROOF".equals(release.path("pMissing").asText())
                    && !release.path("notApplicableBasis").asText().isBlank();
            statistic.put("applicability", notApplicable ? "NOT_APPLICABLE" : "UNKNOWN");
            if (!notApplicable) add(issues, missingP ? "BS1_DISCORDANCE_APPLICABILITY_OR_INPUT_UNKNOWN" : "BS1_DISCORDANCE_INPUT_INVALID", pKey);
        } else if (!fraction(p)) add(issues, "BS1_DISCORDANCE_INPUT_INVALID", pKey);
        else {
            boolean signal = p.compareTo(new BigDecimal(release.path("pValueThreshold").asText())) < 0;
            statistic.put("applicability", "APPLICABLE").put("observedP", p.toPlainString()).put("signal", signal);
            if (signal) add(issues, "BS1_LOCAL_DISCORDANCE_REVIEW_REQUIRED", pKey);
        }
        result.set("nativeFacts", nativeFacts);
        return finish(result, issues);
    }

    static Set<String> supplementNeeds(JsonNode observation, JsonNode policy) {
        if (!"VERIFIED".equals(observation.path("outputCompleteness").asText()) || !"HIT".equals(observation.at("/carrier/queryOutcome/status").asText())) return Set.of();
        var raw = raw(observation.path("rawRecord").asText());
        if (!"PASS".equals(raw.get("FILTER"))) return Set.of();
        // Legacy metrics retain aggregate qualification; sex statistics are qualified by their own consumer.
        boolean sexJoint = Set.of("gnomad.joint.sex_ancestry_max_af", "gnomad.joint.sex_ancestry_max_faf95")
                .contains(policy.at("/statistic/metricId").asText());
        var preliminary = sexJoint
                ? assessSource(new ObjectMapper(), observation, policy, new ObjectMapper().createObjectNode())
                : assess(new ObjectMapper(), observation, policy, new ObjectMapper().createObjectNode());
        for (var issue : preliminary.path("issues"))
            if (!Set.of("BS1_SUBTYPE_QUALITY_UNRESOLVED", "BS1_DISCORDANCE_APPLICABILITY_OR_INPUT_UNKNOWN")
                    .contains(issue.path("reasonCode").asText())) return Set.of();
        if ("READY".equals(preliminary.path("status").asText())) return Set.of();
        boolean boundJoint = "OPTIONAL_WITH_BOUND_JOINT_FILTER".equals(policy.at("/qualityContract/releaseSemantics/missingSubtype").asText());
        boolean unresolvedSubtype = false;
        for (var issue : preliminary.path("issues"))
            unresolvedSubtype |= "BS1_SUBTYPE_QUALITY_UNRESOLVED".equals(issue.path("reasonCode").asText());
        if (boundJoint && !unresolvedSubtype && !"ONE_DATASET_ABSENT_WITH_NATIVE_PROOF".equals(policy.at("/qualityContract/releaseSemantics/pMissing").asText())) return Set.of();
        boolean needsPresence = "UNKNOWN".equals(preliminary.at("/discordance/applicability").asText())
                && "ONE_DATASET_ABSENT_WITH_NATIVE_PROOF".equals(policy.at("/qualityContract/releaseSemantics/pMissing").asText());
        var needs = new LinkedHashSet<String>();
        for (String subtype : List.of("exomes", "genomes")) {
            String state = fieldState(raw, subtype + "_filters");
            if ("EMPTY".equals(state) || "MISSING_KEY".equals(state) && "REQUIRED".equals(policy.at("/qualityContract/releaseSemantics/missingSubtype").asText())) return Set.of();
            if (!needsPresence && Bs1JointFilterSource.bound(new ObjectMapper(), observation, policy.at("/qualityContract/releaseSemantics"))
                    && ("MISSING_KEY".equals(state) && boundJoint || "DOT".equals(state)
                        && "RAW_UNKNOWN_FILTER_BY_BOUND_JOINT".equals(policy.at("/qualityContract/releaseSemantics/subtypeDot").asText()))) continue;
            if ("DOT".equals(state) || "MISSING_KEY".equals(state)) needs.add(subtype);
        }
        return needs;
    }
    static Map<String, String> raw(String raw) {
        var fields = new LinkedHashMap<String, String>(); String[] columns = raw.split("\t", -1);
        if (columns.length < 8) return fields;
        fields.put("FILTER", columns[6]);
        for (String f : columns[7].split(";")) { String[] p = f.split("=", 2); fields.put(p[0], p.length == 2 ? p[1] : "true"); }
        return fields;
    }
    private static String selectedAltField(JsonNode observation, String rawValue) {
        if (rawValue == null) return null;
        String[] columns = observation.path("rawRecord").asText().split("\t", -1);
        if (columns.length < 8) return null;
        String[] alts = columns[4].split(",", -1), values = rawValue.split(",", -1);
        if (values.length != alts.length) return null;
        for (int index = 0; index < alts.length; index++)
            if (alts[index].equals(observation.at("/queryAllele/alternate").asText())) return values[index];
        return null;
    }
    static String fieldState(Map<String, String> raw, String key) {
        if (!raw.containsKey(key)) return "MISSING_KEY";
        String value = raw.get(key);
        return value.isEmpty() ? "EMPTY" : ".".equals(value) ? "DOT" : "PASS".equals(value) ? "PASS" : "FILTERED";
    }
    static String measure(JsonNode carrier, String metric) {
        String value = null;
        for (var m : carrier.path("measurements")) if (metric.equals(m.path("metricId").asText())) {
            if (value != null) return null; value = m.path("rawValue").asText();
        }
        return value;
    }
    static BigDecimal number(String text) { try { return text == null ? null : new BigDecimal(text); } catch (NumberFormatException invalid) { return null; } }
    static boolean fraction(BigDecimal v) { return v.signum() >= 0 && v.compareTo(BigDecimal.ONE) <= 0; }
    static boolean countsAgree(BigDecimal ac, BigDecimal an, BigDecimal af) {
        if (ac.signum() == 0 && af.signum() > 0 || ac.signum() > 0 && af.signum() == 0) return false;
        BigDecimal decimalHalfUlp = af.ulp().divide(BigDecimal.valueOf(2));
        BigDecimal binaryHalfUlp = new BigDecimal((double) Math.ulp(af.add(decimalHalfUlp).floatValue())).divide(BigDecimal.valueOf(2));
        return af.multiply(an).subtract(ac).abs().compareTo(decimalHalfUlp.add(binaryHalfUlp).multiply(an)) <= 0;
    }
    private static void add(ArrayNode issues, String code, String field) { issues.addObject().put("reasonCode", code).put("field", field); }
    private static ObjectNode finish(ObjectNode result, ArrayNode issues) { return result.put("status", issues.isEmpty() ? "READY" : "BLOCKED"); }
}
