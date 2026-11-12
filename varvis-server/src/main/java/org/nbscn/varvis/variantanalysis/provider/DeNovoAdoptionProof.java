package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Explanation of an executed, qualified method. Never an ingestion or scoring API. */
final class DeNovoAdoptionProof {
    static final String SCHEMA = "denovo-adoption-proof-v1";
    private static final int MAX_EVENTS = 100, MAX_SOURCES = 128;
    private DeNovoAdoptionProof() { }

    static ObjectNode build(ObjectMapper mapper, DeNovoApplicabilityPolicy policy, List<Observation> observations, Result result) {
        var out = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("projectionStatus", "COMPLETE")
                .put("status", "NOT_ADOPTED").put("basis", "EXECUTED_QUALIFIED_OBSERVATIONS")
                .put("calculationMethod", result.calculationMethod()).put("selectedCriterion", result.code())
                .put("appliedStrength", result.strength()).put("reasonCode", result.reasonCode())
                .put("totalEventCount", result.events().size());
        out.putArray("limitations");
        var events = out.putArray("events");
        var specialPolicy = policy.specialEvents();
        if (specialPolicy == null || !specialPolicy.tissueRule() && !specialPolicy.combinedPs2()
                && !specialPolicy.rasopathyPoints() && !specialPolicy.phenotypeOnly() && !specialPolicy.publishedPoints()) {
            out.put("status", "NOT_APPLICABLE"); return out;
        }
        if ("MET".equals(result.status())) out.put("status", "ADOPTED");
        var rawPolicy = policy.snapshot();
        var p = out.putObject("policy").put("eventMode", specialPolicy.mode().name())
                .put("phenotypePolicySha256", policy.sha256()).put("materialClass", rawPolicy.path("materialClass").asText());
        if (specialPolicy.publishedPoints()) p.put("maximumPm6Strength", specialPolicy.maximumPm6Strength().name());
        safeLabel(out, p, "policyId", rawPolicy.path("policyId")); safeLabel(out, p, "policyVersion", rawPolicy.path("version"));
        var clauses = p.putArray("sources");
        for (var source : rawPolicy.path("sources")) if (specialPolicy.sourceIds().contains(source.path("sourceId").asText())) {
            var clause = clauses.addObject().put("sourceSha256", source.path("sha256").asText());
            safeLabel(out, clause, "sourceId", source.path("sourceId")); safeLabel(out, clause, "locator", source.path("locator"));
        }
        // The existing reviewed contract has no clauseId. These exact pins + mode are its actual identity.
        p.put("clauseIdentityMethod", "POLICY_SHA256_MODE_SOURCE_LOCATOR");
        for (var resultEvent : result.events()) {
            if (events.size() >= MAX_EVENTS) { incomplete(out, "PUBLIC_EVENT_LIMIT"); break; }
            var event = events.addObject().put("ordinal", events.size()).put("disposition", resultEvent.disposition())
                    .put("sourceKind", resultEvent.sourceKind()).put("reasonCode", resultEvent.reasonCode())
                    .put("status", "NOT_ADOPTED");
            var matched = observations.stream().filter(o -> o.observationId().equals(resultEvent.observationId())
                    && o.eventId().equals(resultEvent.eventId())).toList();
            if (matched.size() != 1) { incomplete(out, "PUBLIC_OBSERVATION_BINDING_UNAVAILABLE"); continue; }
            var observation = matched.get(0); var originalFacts = observation.facts(); var special = originalFacts.special();
            var facts = special == null ? originalFacts : special.eventFacts(originalFacts);
            boolean counted = "COUNTED".equals(resultEvent.disposition());
            if (!counted && !"ZERO_SUPPORT".equals(resultEvent.disposition())) continue;
            if (counted) event.put("status", "QUALIFIED_CONTRIBUTION");
            event.put("phenotypeCategory", facts.phenotype().name());
            if (specialPolicy.tissueRule()) {
                if (special == null) { incomplete(out, "PUBLIC_SPECIAL_FACTS_UNAVAILABLE"); continue; }
                appendTissue(out, event, special, facts, counted);
            } else if (counted) {
                var combined = event.putObject("combined").put("status", "MET")
                        .put("clauseMode", specialPolicy.mode().name()).put("allowAssumedParentage", rawPolicy.path("allowAssumedParentage").asBoolean())
                        .put("confirmedParentage", facts.confirmed()).put("maternity", facts.maternity().name())
                        .put("paternity", facts.paternity().name()).put("probandTarget", facts.proband().name())
                        .put("motherTarget", facts.mother().name()).put("fatherTarget", facts.father().name())
                        .put("phenotypeCategory", facts.phenotype().name()).put("rawQuarterPoints", resultEvent.rawQuarterPoints());
                event.put("conditionBranch", specialPolicy.rasopathyPoints() ? "SOURCE_BOUND_RASOPATHY_POINTS" : "SOURCE_BOUND_COMBINED_PS2");
                if (specialPolicy.publishedPoints()) {
                    event.put("conditionBranch", "SOURCE_BOUND_PUBLISHED_SVI_POINTS");
                    combined.put("familyHistory", facts.familyHistory().name());
                }
                var sources = combined.putArray("sources");
                if (special != null) sources(out, sources, special, List.of("/trio/", "/kind"));
                else {
                    combined.put("sourceMappingStatus", "QUALIFIED_OBSERVATION_LEVEL");
                    for (String ref : observation.sourceRefs()) if (ref.matches("[0-9a-f]{64}#(?:/[A-Za-z0-9_~/-]*)?")
                            && (!(specialPolicy.rasopathyPoints() || specialPolicy.publishedPoints()) || !ref.endsWith("#"))) {
                        if (sources.size() >= MAX_SOURCES) { incomplete(out, "PUBLIC_SOURCE_LIMIT"); break; }
                        sources.addObject().put("sourceSha256", ref.substring(0, 64)).put("role", "QUALIFIED_OBSERVATION")
                                .put("kind", "JSON_POINTER").put("locator", ref.substring(65));
                    }
                }
            }
        }
        return out;
    }

    private static void appendTissue(ObjectNode out, ObjectNode event, DeNovoSpecialEvent.Verified special, Facts facts, boolean counted) {
        var assessment = special.tissueAssessment(facts); var raw = special.snapshot();
        event.put("conditionBranch", assessment.c1() && assessment.c2() ? "C1_AND_C2" : assessment.c1() ? "C1_ONLY"
                : assessment.c2() ? "PARENTS_UNAVAILABLE_AND_C2" : "CONDITIONS_NOT_ESTABLISHED");
        if (counted) event.put("adoptedStrength", assessment.level() == 2 ? "STRONG" : "MODERATE");
        var c1 = event.putObject("c1").put("status", assessment.c1() ? "MET" : "NOT_ESTABLISHED")
                .put("probandTarget", facts.proband().name()).put("motherTarget", facts.mother().name()).put("fatherTarget", facts.father().name())
                .put("maternity", facts.maternity().name()).put("paternity", facts.paternity().name()).put("confirmedParentage", facts.confirmed());
        for (String key : List.of("maternityMethod", "paternityMethod", "method", "targetCoverage", "detectionCapability", "quality"))
            c1.set(key, raw.path("trio").path(key).deepCopy());
        sources(out, c1.putArray("sources"), special, List.of("/trio/"));
        var availability = event.putObject("parentalAvailability").put("value", raw.path("parentalAvailability").asText());
        sources(out, availability.putArray("sources"), special, List.of("/parentalAvailability"));
        var c2 = event.putObject("c2").put("status", assessment.c2() ? "MET" : "NOT_ESTABLISHED")
                .put("comparisonMethod", "QUALIFIED_AFFECTED_FRACTION_GREATER_THAN_COMPARISON");
        var measurements = c2.putArray("observations");
        if (assessment.c2()) {
            measurement(out, measurements, special, assessment.affectedIndex(), "AFFECTED");
            measurement(out, measurements, special, assessment.comparisonIndex(), "COMPARISON");
            c2.put("samePerson", true).put("distinctTissues", true).put("detectionAdequate", true);
        } else {
            // Preserve the reason C2 is unestablished. These measurements are not evidence of adoption.
            for (int i = 0; i < raw.path("tissues").size(); i++) measurement(out, measurements, special, i,
                    raw.path("tissues").get(i).path("affected").asBoolean() ? "AFFECTED" : "COMPARISON");
            c2.put("limitation", "NO_QUALIFIED_COMPARISON_ESTABLISHED_NOT_PROVEN_ABSENT");
        }
    }

    private static void measurement(ObjectNode out, ArrayNode observations, DeNovoSpecialEvent.Verified special, int index, String role) {
        var raw = special.snapshot().path("tissues").get(index);
        var view = observations.addObject().put("observationOrdinal", index + 1).put("role", role).put("personRole", "EVENT_SUBJECT");
        for (String key : List.of("altReads", "totalReads", "limit", "method", "quality")) view.set(key, raw.path(key).deepCopy());
        sources(out, view.putArray("sources"), special, List.of("/tissues/" + index + "/"));
    }

    private static void sources(ObjectNode out, ArrayNode target, DeNovoSpecialEvent.Verified special, List<String> paths) {
        // This is the retained fact artifact; its role must never be mislabeled a primary paper.
        for (String path : paths) {
            if (target.size() >= MAX_SOURCES) { incomplete(out, "PUBLIC_SOURCE_LIMIT"); return; }
            target.addObject().put("fieldPath", path).put("sourceSha256", special.sourceSha256()).put("role", special.factRole())
                    .put("kind", "JSON_POINTER").put("locator", "/specialEvent" + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path))
                    .put("mapping", "RETAINED_FACT");
        }
        for (var field : special.originalFields()) if (paths.stream().anyMatch(path -> path.endsWith("/")
                ? field.fieldPath().startsWith(path) : field.fieldPath().equals(path))) {
            if (target.size() >= MAX_SOURCES) { incomplete(out, "PUBLIC_SOURCE_LIMIT"); return; }
            if (!Set.of("PAPER_PRIMARY", "PAPER_ATTACHMENT").contains(field.role()) || !safeLocator(field.locator())) {
                incomplete(out, "PUBLIC_ORIGINAL_FIELD_NOT_PROJECTABLE"); continue;
            }
            target.addObject().put("fieldPath", field.fieldPath()).put("sourceSha256", field.sourceSha256()).put("role", field.role())
                    .put("kind", field.kind()).put("locator", field.locator()).put("mapping", "REPLAYED_ORIGINAL_FIELD");
        }
        if (!"LOCAL_REPORT".equals(special.factRole()) && target.findValuesAsText("mapping").stream().noneMatch("REPLAYED_ORIGINAL_FIELD"::equals))
            incomplete(out, "PUBLIC_ORIGINAL_CONDITION_MAPPING_UNAVAILABLE");
    }

    private static boolean safeLocator(String text) {
        return !text.isBlank() && text.length() <= 240 && !text.contains(":") && !text.contains("\\")
                && text.chars().noneMatch(c -> c < 32);
    }
    private static void safeLabel(ObjectNode out, ObjectNode to, String key, JsonNode value) {
        String text = value.asText();
        if (!text.isBlank() && text.length() <= 240 && text.chars().noneMatch(c -> c < 32)
                && !text.matches("(?i).*(?:[a-z]:[/\\\\]|file:|https?:).*")) to.put(key, text);
        else incomplete(out, "PUBLIC_POLICY_IDENTITY_NOT_PROJECTABLE");
    }
    private static void incomplete(ObjectNode out, String code) {
        out.put("projectionStatus", "INCOMPLETE");
        var limitations = out.withArray("limitations");
        for (var item : limitations) if (code.equals(item.asText())) return;
        limitations.add(code);
    }
}
