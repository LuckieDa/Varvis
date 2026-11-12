package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Explicit project-local scope. Shares verified population facts, never BA1 disease decisions. */
final class Bs1LocalExomes {
    static final String VERSION = "bs1-local-exomes-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode RELEASE = load();
    private Bs1LocalExomes() { }

    private static JsonNode load() {
        try (var in = Bs1LocalExomes.class.getResourceAsStream("/acmg/bs1-local-exomes-release-v1.json")) {
            if (in == null) throw new java.io.IOException("missing release");
            var value = JSON.readTree(in);
            if (!"bs1-local-exomes-release-v1".equals(value.path("schemaVersion").asText())
                    || !value.path("policies").isArray() || value.path("policies").isEmpty())
                throw new IllegalArgumentException("BS1_LOCAL_RELEASE_INVALID");
            return value;
        } catch (java.io.IOException invalid) { throw new IllegalStateException("BS1_LOCAL_RELEASE_UNAVAILABLE", invalid); }
    }
    static ObjectNode scope(ObjectMapper mapper) { return ((ObjectNode) RELEASE.path("dataScope")).deepCopy(); }
    static ObjectNode qualityContract() {
        return JSON.createObjectNode().put("contractId", "bs1-local-exomes-quality-v1").put("version", "1.0.0")
                .put("dataset", "EXOMES").put("release", "4.1").put("minimumAn", "2000")
                .put("minimumAnBasis", "LOCAL_ENGINEERING_SAFEGUARD").put("filterRequired", "PASS")
                .put("scopePolicyId", "BS1_LOCAL_EXOMES_V1");
    }
    static List<JsonNode> policies(ObjectMapper mapper) {
        var result = new ArrayList<JsonNode>();
        var ids = new java.util.HashSet<String>();
        for (var policy : RELEASE.path("policies")) {
            if (!validPolicyScope(policy) || !ids.add(policy.path("policyId").asText()))
                throw new IllegalArgumentException("BS1_LOCAL_RELEASE_INVALID");
            result.add(policy.deepCopy());
        }
        return List.copyOf(result);
    }
    static boolean isPolicy(JsonNode p) { return "LOCAL_EXOMES_BUSINESS_V1".equals(p.path("activation").asText()) || Bs1KnowledgeCatalog.ACTIVATION.equals(p.path("activation").asText()); }
    static boolean validPolicyScope(JsonNode p) {
        if (Bs1KnowledgeCatalog.isPolicy(p)) return Bs1KnowledgeCatalog.issue(p) == null;
        var resource = GnomadPopulationInputProfile.loadLocalBa1().getDocument().path("resources").get(0);
        if (!isPolicy(p) || !RELEASE.path("dataScope").equals(p.path("dataScope"))
                || !qualityContract().equals(p.path("qualityContract"))
                || !resource.path("resourceVersion").equals(p.at("/resourceCompatibility/resourceVersion"))
                || !resource.path("identityFingerprintSha256").equals(p.at("/resourceCompatibility/resourceSha256"))) return false;
        // Pin the source policy; adaptation cannot silently substitute a different threshold or exception set.
        for (var source : Bs1Runtime.policies(JSON)) if (source.path("policyId").equals(p.path("sourcePolicyId"))) {
            if (!source.path("version").equals(p.path("sourcePolicyVersion"))
                    || !new RuntimeEvidenceV2Adapter(JSON).canonicalSha256(source).equals(p.path("sourcePolicySha256").asText())) return false;
            for (String key : List.of("scope", "tiers", "commonConditions", "exceptionSet", "sources", "requiredContext"))
                if (!source.path(key).equals(p.path(key))) return false;
            return true;
        }
        return false;
    }

    static ObjectNode sharedObservation(ObjectMapper mapper, JsonNode context, JsonNode ba1, String sourceSnapshot) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var query = context.at("/variant/evidenceQueryAllele");
        var norm = context.at("/variant/normalization");
        if (!"BA1_LOCAL_EXOMES_V1".equals(ba1.at("/dataScope/policyId").asText())
                || !sourceSnapshot.equals(ba1.path("executionSourceSha256").asText())
                || !query.equals(ba1.path("evidenceQueryAllele"))
                || !ba1.path("alleleId").asText().equals(adapter.canonicalAlleleId(query, ba1.path("normalization"))))
            throw new IllegalArgumentException("BS1_SHARED_POPULATION_IDENTITY_MISMATCH");
        // Native contexts must use exactly the same normalization method/reference as the shared query.
        if ("GRCh38".equals(context.at("/variant/inputAllele/assembly").asText()))
            for (String key : List.of("method", "methodVersion", "referenceSequenceSha256"))
                if (!norm.path(key).equals(ba1.path("normalization").path(key)))
                    throw new IllegalArgumentException("BS1_SHARED_NORMALIZATION_MISMATCH");
        if (!(ba1.path("evidence") instanceof ObjectNode carrier))
            throw new IllegalArgumentException("BS1_LOCAL_POPULATION_UNAVAILABLE");
        var observation = PopulationObservation.seal(mapper, carrier, ba1.path("rawPopulationRecord").asText(),
                GnomadPopulationInputProfile.loadLocalBa1(), sourceSnapshot, context.path("dataClass").asText(), "SHARED_BA1_LOCAL_FACTS");
        PopulationObservation.verify(mapper, observation, context, sourceSnapshot, GnomadPopulationInputProfile.loadLocalBa1());
        return observation;
    }
    static ObjectNode evaluate(ObjectMapper mapper, JsonNode context, JsonNode observation, JsonNode selection) {
        var policy = selection.path("policy");
        if (!Bs1Policy.select(mapper, List.of(policy), context).equals(selection)
                || !"READY".equals(selection.path("status").asText())) throw new IllegalArgumentException("BS1_SELECTION_INVALID");
        PopulationObservation.verify(mapper, observation, context, observation.path("sourceSnapshot").asText(), GnomadPopulationInputProfile.loadLocalBa1());
        var quality = Bs1SexChromosomePolicy.isStatistic(policy)
                ? Bs1SexChromosomeStatistic.select(mapper, observation, context, observation.path("sourceSnapshot").asText(), policy)
                : quality(mapper, observation, policy);
        var evidence = new RuntimeEvidenceV2Adapter(mapper).adaptVersioned(context, (ObjectNode) observation.path("carrier"));
        var result = mapper.createObjectNode().put("schemaVersion", "bs1-rule-result-v1").put("criterionId", Bs1Policy.CRITERION);
        result.set("context", context); result.set("preparation", selection); result.set("observation", observation);
        result.set("qualityDecision", quality); result.set("evidence", evidence); result.set("dataScope", policy.path("dataScope").deepCopy());
        result.set("evaluation", Bs1CriterionEvaluator.evaluate(mapper, context, evidence, policy, selection, quality));
        result.put("overlapPolicy", "BA1_PRECEDENCE_NO_ADDITIVE_POPULATION_STRENGTH").put("classificationEnabled", false);
        return result;
    }
    static ObjectNode quality(ObjectMapper mapper, JsonNode observation, JsonNode policy) {
        var r = mapper.createObjectNode().put("schemaVersion", "bs1-quality-decision-v2").put("adapterVersion", VERSION)
                .put("contractSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(qualityContract()));
        r.set("contract", qualityContract()); r.set("dataScope", policy.path("dataScope").deepCopy());
        var issues = r.putArray("issues"); var facts = r.putObject("facts"); var carrier = observation.path("carrier");
        if (!"SUCCEEDED".equals(carrier.at("/resourceExecution/status").asText())
                || !"AVAILABLE".equals(carrier.at("/resourceAvailability/status").asText())
                || !"VERIFIED".equals(observation.path("outputCompleteness").asText())) {
            issue(issues, "BS1_RESOURCE_EXECUTION_UNAVAILABLE", "resourceExecution"); return finish(r, issues);
        }
        if (!"HIT".equals(carrier.at("/queryOutcome/status").asText())) {
            issue(issues, "BS1_NO_RECORD", "queryOutcome"); return finish(r, issues);
        }
        String filter = Bs1Quality.raw(observation.path("rawRecord").asText()).get("FILTER");
        r.putObject("filterCondition").put("basis", "LOCAL_EXOMES_NATIVE_FILTER").put("observedFilter", filter)
                .put("status", "PASS".equals(filter) ? "SATISFIED" : "FAILED");
        if (!"PASS".equals(filter)) issue(issues, "BS1_LOCAL_FILTER_NOT_PASS", "FILTER");
        if (Bs1FrequencyStatistic.isAf(policy)) return Bs1FrequencyStatistic.select(r, carrier, policy);
        String ancestry = Bs1Quality.measure(carrier, "gnomad.exomes.faf95max.ancestry");
        if (ancestry == null) issue(issues, "BS1_FAF_ANCESTRY_MISSING", "fafmax_faf95_max_gen_anc");
        else if (!Bs1Policy.contains(policy.at("/statistic/ancestries"), ancestry))
            issue(issues, "BS1_FAF_ANCESTRY_UNSUPPORTED", "fafmax_faf95_max_gen_anc");
        else {
            facts.put("ancestry", ancestry); String prefix = "gnomad.exomes.ancestry." + ancestry + ".";
            BigDecimal faf = Bs1Quality.number(Bs1Quality.measure(carrier, "gnomad.exomes.faf95max"));
            BigDecimal linked = Bs1Quality.number(Bs1Quality.measure(carrier, prefix + "faf95"));
            BigDecimal an = Bs1Quality.number(Bs1Quality.measure(carrier, prefix + "an"));
            BigDecimal ac = Bs1Quality.number(Bs1Quality.measure(carrier, prefix + "ac"));
            BigDecimal af = Bs1Quality.number(Bs1Quality.measure(carrier, prefix + "af"));
            if (faf == null || linked == null || !Bs1Quality.fraction(faf) || faf.compareTo(linked) != 0)
                issue(issues, "BS1_FAF_ANCESTRY_MISMATCH_OR_MISSING", "FAF95");
            else facts.put("FAF95", faf.toPlainString());
            if (an == null || an.signum() <= 0 || an.stripTrailingZeros().scale() > 0)
                issue(issues, "BS1_SELECTED_AN_INVALID", "AN");
            else { facts.put("AN", an.toPlainString()); if (an.compareTo(new BigDecimal("2000")) < 0) issue(issues, "BS1_LOCAL_AN_INSUFFICIENT", "AN"); }
            if (ac == null || ac.signum() < 0 || ac.stripTrailingZeros().scale() > 0 || an == null || ac.compareTo(an) > 0)
                issue(issues, "BS1_SELECTED_AC_INVALID", "AC");
            else facts.put("AC", ac.toPlainString());
            if (af == null || !Bs1Quality.fraction(af) || faf == null || faf.compareTo(af) > 0)
                issue(issues, "BS1_SELECTED_AF_INVALID", "AF");
            else { facts.put("AF", af.toPlainString()); if (an != null && ac != null && an.signum() > 0 && !Bs1Quality.countsAgree(ac, an, af))
                issue(issues, "BS1_COUNT_AF_CONFLICT", "AF/AC/AN"); }
        }
        return finish(r, issues);
    }
    private static void issue(ArrayNode issues, String reason, String field) { issues.addObject().put("reasonCode", reason).put("field", field); }
    private static ObjectNode finish(ObjectNode r, ArrayNode issues) { return r.put("status", issues.isEmpty() ? "READY" : "BLOCKED"); }
}
