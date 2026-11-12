package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Adapts source-replayed core facts to the existing calculator, with exact policy/selection checks. */
final class DeNovoCoreFacts {
    record Read(Facts facts, DeNovoApplicabilityPolicy.Match phenotype, List<String> issues, String eventId,
                @Nullable String eventAuthority) {
        Read(Facts facts, DeNovoApplicabilityPolicy.Match phenotype, List<String> issues, String eventId) {
            this(facts, phenotype, issues, eventId, null);
        }
    }

    static Read read(JsonNode core, JsonNode allele, Group group, DeNovoApplicabilityPolicy policy) {
        return read(core, allele, group, policy, null);
    }

    static Read read(JsonNode core, JsonNode allele, Group group, DeNovoApplicabilityPolicy policy,
                     @Nullable DeNovoSpecialEvent.RegionSupply regions) {
        var issues = new ArrayList<String>();
        boolean extended = "denovo-core-evaluation-intake-1.1.0".equals(core.path("schemaVersion").asText());
        if (!extended && !"denovo-core-evaluation-intake-1.0.0".equals(core.path("schemaVersion").asText())) issues.add("DENOVO_CORE_SCHEMA_INVALID");
        if (!extended && core.has("specialEvent")) issues.add("DENOVO_CORE_SCHEMA_INVALID");
        if (!"READY_FOR_EVALUATION".equals(core.path("status").asText())) issues.add("DENOVO_CORE_FACTS_NOT_READY");
        for (var b : core.path("blockers")) {
            issues.add("DENOVO_CORE:" + b.path("category").asText() + ":" + b.path("path").asText() + ":" + b.path("code").asText());
            b.path("causeCodes").forEach(c -> issues.add(c.asText()));
        }
        JsonNode v = core.path("values");
        if (!allele.equals(v.path("/context/allele")) || !allele.equals(v.path("/groups/variant/resolvedAllele"))) issues.add("DENOVO_CORE_EXACT_ALLELE_MISMATCH");
        if (!group.hgncId().equals(v.path("/context/hgncId").asText())
                || !group.diseaseId().equals(v.path("/context/diseaseId").asText())
                || !group.inheritance().equals(v.path("/context/inheritance").asText())) issues.add("DENOVO_CORE_BACKGROUND_MISMATCH");
        var p = policy.snapshot();
        if (!policy.sha256().equals(core.path("policySha256").asText())
                || !p.path("policyId").equals(v.path("/context/policyId"))
                || !p.path("version").equals(v.path("/context/policyVersion"))
                || !p.path("allowAssumedParentage").equals(v.path("/context/allowAssumedParentage"))
                || !p.path("expertPolicyStatus").equals(v.path("/context/expertPolicyStatus"))) issues.add("DENOVO_CORE_POLICY_MISMATCH");
        if (!"APPLICABLE".equals(v.path("/context/geneDiseaseApplicability").asText())) issues.add("DENOVO_CORE_BACKGROUND_NOT_QUALIFIED");
        var features = new ArrayList<String>();
        var negatives = new ArrayList<String>();
        v.path("/groups/phenotype/positive").forEach(f -> features.add(f.asText()));
        v.path("/groups/phenotype/negative").forEach(f -> negatives.add(f.asText()));
        var phenotype = policy.matchLiterature(group, features, negatives); issues.addAll(phenotype.issues());
        if (!phenotype.phenotype().name().equals(v.path("/context/phenotypeCategory").asText())) issues.add("DENOVO_CORE_PHENOTYPE_MISMATCH");
        boolean target = v.path("/groups/testing/qualifiedForTarget").asBoolean(false);
        boolean specialCandidate = extended && core.has("specialEvent");
        if (specialCandidate && !policy.permitsSyntheticReports() && !"PUBLIC_PAPER_RESEARCH".equals(core.path("dataClass").asText()))
            issues.add("DENOVO_CORE_SPECIAL_DATA_CLASS_NOT_ALLOWED");
        if (!specialCandidate && !target) issues.add("DENOVO_CORE_TARGET_ASSAY_NOT_QUALIFIED");
        if (!"AUTHOR_COHORT".equals(v.path("/groups/case/origin").asText())
                || (!specialCandidate && !"UNIQUE_WITHIN_CHECKED_SCOPE".equals(v.path("/groups/overlap/independence").asText())))
            issues.add("DENOVO_CORE_EVENT_NOT_QUALIFIED");
        DeNovoSpecialEvent.Verified special = null;
        String specialSourceSha = null;
        if (extended && core.has("specialEvent")) {
            String sourceSha = specialSource(core);
            if (sourceSha == null) issues.add("DENOVO_CORE_SPECIAL_EVENT_PROOF_INVALID");
            else if (issues.isEmpty()) {
                DeNovoPartnerEvidence.Verified partnerEvidence = null;
                if ("AR".equals(group.inheritance()) && "INDEPENDENT_P_LP".equals(core.at("/specialEvent/partner/status").asText())) {
                    var partner = DeNovoPartnerEvidence.read(core, allele, core.at("/specialEvent/partner/allele"), group);
                    partnerEvidence = partner.value(); issues.addAll(partner.issues());
                }
                var read = DeNovoSpecialEvent.read(core.path("specialEvent"), sourceSha, allele, group, policy.specialEvents(), regions, partnerEvidence);
                special = read.value(); issues.addAll(read.issues());
                if (special != null) special = special.withCoreOrigins(originalFields(core),
                        core.path("sourcePins").path("special-event-original").path("role").asText());
                if (special != null) issues.addAll(DeNovoTrioReconciliation.core(special, core));
                specialSourceSha = sourceSha;
            }
        }
        if (special == null && !"AD".equals(group.inheritance())) issues.add("DENOVO_SPECIAL_EVENT_REQUIRED");
        Facts facts = new Facts(group, target(v.path("/groups/proband/targetResult"), target),
                target(v.path("/groups/mother/targetResult"), target), target(v.path("/groups/father/targetResult"), target),
                relation(v.path("/groups/parentage/maternity")), relation(v.path("/groups/parentage/paternity")),
                phenotype.phenotype(), familyHistory(v), special);
        String eventId = v.path("/groups/overlap/eventId").asText();
        String authority = special == null ? null : eventAuthority(core);
        if (special != null && specialSourceSha != null) {
            String namespace = authority == null ? specialSourceSha : authority;
            eventId = "source-event:" + FamilyDeNovoJson.sha256((namespace + ":" + special.eventId()).getBytes(StandardCharsets.UTF_8));
        }
        return new Read(facts, phenotype, List.copyOf(new java.util.LinkedHashSet<>(issues)), eventId, authority);
    }

    /** Only called after the owned reader's exact fact/basis replay has been accepted. */
    private static List<DeNovoSpecialEvent.FieldSource> originalFields(JsonNode core) {
        var fields = new ArrayList<DeNovoSpecialEvent.FieldSource>();
        var source = core.path("specialEventSource"); var discovery = source.path("discovery");
        var basis = core.path("fieldProofs").path("/extensions/specialEvent").path("check").path("basis");
        for (var record : core.path("sourceDiscovery").path("records")) {
            if (!record.path("sourceSha256").equals(discovery.path("sourceSha256"))
                    || !record.path("recordLocator").equals(discovery.path("recordLocator"))) continue;
            for (var field : record.path("fields")) for (var citation : basis) {
                var pin = core.path("sourcePins").path(citation.path("sourceId").asText());
                if (pin.path("sha256").equals(field.path("sourceSha256"))
                        && citation.path("kind").equals(field.path("kind")) && citation.path("locator").equals(field.path("locator"))
                        && citation.path("quote").equals(field.path("quote")))
                    fields.add(new DeNovoSpecialEvent.FieldSource(field.path("path").asText(), field.path("sourceSha256").asText(),
                            pin.path("role").asText(), field.path("kind").asText(), field.path("locator").asText()));
            }
        }
        // Native original attachments have a direct path; arbitrary older derived mappings stay unknown.
        if ("ORIGINAL_JSON_ATTACHMENT".equals(source.path("sourceClass").asText())) for (var citation : basis)
            if ("special-event-original".equals(citation.path("sourceId").asText())
                    && "JSON_POINTER".equals(citation.path("kind").asText()) && citation.path("locator").asText().startsWith("/specialEvent/"))
                fields.add(new DeNovoSpecialEvent.FieldSource(citation.path("locator").asText().substring("/specialEvent".length()),
                        source.path("sourceSha256").asText(), "PAPER_ATTACHMENT", "JSON_POINTER", citation.path("locator").asText()));
        return fields.stream().distinct().toList();
    }

    /** The trusted producer supplies exact canonical bytes so decimal JSON is not re-serialized differently. */
    @Nullable private static String specialSource(JsonNode core) {
        JsonNode event = core.path("specialEvent");
        JsonNode proof = core.path("fieldProofs").path("/extensions/specialEvent");
        JsonNode fact = proof.path("fact"), check = proof.path("check");
        JsonNode pin = core.path("sourcePins").path("special-event-original");
        JsonNode source = core.path("specialEventSource");
        boolean discovered = "BOUNDED_REGISTERED_EVENT_DISCOVERY_REPLAY".equals(check.path("method").asText());
        var discovery = source.path("discovery");
        if (discovered && (!Set.of("JSON", "JATS").contains(discovery.path("format").asText())
                || !"REGISTERED_STRUCTURED_REPORT".equals(source.path("sourceClass").asText())
                || !discovery.path("sourceSha256").asText().matches("[0-9a-f]{64}")
                || !discovery.path("sourceSha256").equals(core.path("sourcePins").path(discovery.path("sourceId").asText()).path("sha256")))) return null;
        String sha = pin.path("sha256").asText();
        if (!event.isObject() || !fact.path("value").equals(event)
                || !fact.path("caseKey").equals(core.path("caseKey"))
                || !"VERIFIED".equals(fact.path("verification").asText())
                || !"PROGRAM_DERIVED".equals(fact.path("origin").asText())
                || !"REPORTED".equals(fact.path("availability").asText())
                || !(discovered || "PINNED_SPECIAL_EVENT_SOURCE_REPLAY".equals(check.path("method").asText()))
                || !sha.matches("[0-9a-f]{64}") || !sha.equals(source.path("sourceSha256").asText())
                || !Set.of("PAPER_ATTACHMENT", "DERIVED_FACTS").contains(pin.path("role").asText())
                || !"EXACT_REGISTERED_SOURCE_VALUES".equals(source.path("replayStatus").asText())) return null;
        if (!("PAPER_ATTACHMENT".equals(pin.path("role").asText())
                    && "ORIGINAL_JSON_ATTACHMENT".equals(source.path("sourceClass").asText()))
                && !("DERIVED_FACTS".equals(pin.path("role").asText())
                    && "REGISTERED_STRUCTURED_REPORT".equals(source.path("sourceClass").asText()))) return null;
        if (!check.path("basis").isArray() || !fact.path("citations").equals(check.path("basis"))) return null;
        for (var basis : check.path("basis")) {
            JsonNode original = core.path("sourcePins").path(basis.path("sourceId").asText());
            boolean jats = discovered && "JATS".equals(discovery.path("format").asText())
                    && "JATS_UNIT".equals(basis.path("kind").asText()) && "paper".equals(basis.path("sourceId").asText())
                    && "PAPER_PRIMARY".equals(original.path("role").asText()) && discovery.path("sourceSha256").equals(original.path("sha256"))
                    && !basis.path("locator").asText().isBlank() && !basis.path("quote").asText().isBlank();
            if (!original.path("sha256").asText().matches("[0-9a-f]{64}")
                    || !Set.of("PAPER_PRIMARY", "PAPER_ATTACHMENT", "DERIVED_FACTS").contains(original.path("role").asText())
                    || !(jats || "JSON_POINTER".equals(basis.path("kind").asText()) && basis.path("locator").asText().startsWith("/"))) return null;
        }
        String canonical = proof.path("factCanonicalUtf8").asText();
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (!FamilyDeNovoJson.sha256(bytes).equals(check.path("factSha256").asText())) return null;
        var json = new FamilyDeNovoJson("DENOVO_CORE_SPECIAL_EVENT_");
        var lossless = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        try {
            if (!sameSourceValue(json.parse(lossless, bytes), fact)) return null;
            boolean citationFound = false;
            for (var citation : fact.path("citations")) {
                if (!"special-event-original".equals(citation.path("sourceId").asText())
                        || !"JSON_POINTER".equals(citation.path("kind").asText())
                        || !"/specialEvent".equals(citation.path("locator").asText())) continue;
                if (!sameSourceValue(json.parse(lossless, citation.path("quote").asText().getBytes(StandardCharsets.UTF_8)), event)) return null;
                for (var basis : check.path("basis")) if (basis.equals(citation)) citationFound = true;
            }
            if (!citationFound) return null;
        } catch (IllegalArgumentException invalid) { return null; }
        JsonNode parents = source.path("parentSourceSha256s");
        if (!parents.isArray() || ("DERIVED_FACTS".equals(pin.path("role").asText()) && parents.isEmpty())) return null;
        for (var parent : parents) {
            if (!parent.isTextual() || !parent.asText().matches("[0-9a-f]{64}")) return null;
            boolean found = false;
            for (var original : core.path("sourcePins")) if (parent.equals(original.path("sha256"))
                    && Set.of("PAPER_PRIMARY", "PAPER_ATTACHMENT").contains(original.path("role").asText())) found = true;
            if (!found) return null;
        }
        if (discovered && (parents.size() != 1 || !discovery.path("sourceSha256").equals(parents.get(0)))) return null;
        return sha;
    }

    /** Exact JSON values across lossless application/parser number representations; never a tolerance or text coercion.
     * Raw canonical bytes and SHA are verified above, independently of this structural value comparison. */
    private static boolean sameSourceValue(JsonNode original, JsonNode actual) {
        if (original.isNumber() || actual.isNumber())
            return original.isNumber() && actual.isNumber() && original.decimalValue().compareTo(actual.decimalValue()) == 0;
        if (original.isObject() && actual.isObject()) {
            if (original.size() != actual.size()) return false;
            for (var fields = original.properties().iterator(); fields.hasNext();) {
                var field = fields.next();
                if (!actual.has(field.getKey()) || !sameSourceValue(field.getValue(), actual.path(field.getKey()))) return false;
            }
            return true;
        }
        if (original.isArray() && actual.isArray()) {
            if (original.size() != actual.size()) return false;
            for (int i = 0; i < original.size(); i++) if (!sameSourceValue(original.path(i), actual.path(i))) return false;
            return true;
        }
        return original.equals(actual);
    }

    /** A family/event label is unique only within the original that supplied both identity fields. */
    @Nullable private static String eventAuthority(JsonNode core) {
        JsonNode identity = core.at("/specialEventSource/eventIdentityProof");
        if (!identity.isObject() || identity.size() != 5) return null;
        String authority = identity.path("authoritySha256").asText();
        if (!authority.matches("[0-9a-f]{64}")
                || !authority.equals(identity.path("familySourceSha256").asText())
                || !authority.equals(identity.path("eventSourceSha256").asText())) return null;
        return identityCitation(core, authority, identity.path("familyLocator").asText(), core.at("/specialEvent/familyId").asText())
                && identityCitation(core, authority, identity.path("eventLocator").asText(), core.at("/specialEvent/eventId").asText()) ? authority : null;
    }

    private static boolean identityCitation(JsonNode core, String sourceSha, String locator, String value) {
        if (locator.isBlank() || value.isBlank()) return false;
        boolean discovered = "BOUNDED_REGISTERED_EVENT_DISCOVERY_REPLAY".equals(core.path("fieldProofs").path("/extensions/specialEvent").at("/check/method").asText());
        for (var citation : core.path("fieldProofs").path("/extensions/specialEvent").path("fact").path("citations")) {
            JsonNode pin = core.path("sourcePins").path(citation.path("sourceId").asText());
            boolean json = locator.startsWith("/") && "PAPER_ATTACHMENT".equals(pin.path("role").asText()) && "JSON_POINTER".equals(citation.path("kind").asText());
            boolean jats = discovered && "PAPER_PRIMARY".equals(pin.path("role").asText()) && "paper".equals(citation.path("sourceId").asText())
                    && "JATS_UNIT".equals(citation.path("kind").asText()) && core.at("/specialEventSource/discovery/sourceSha256").asText().equals(sourceSha);
            if (sourceSha.equals(pin.path("sha256").asText()) && (json || jats) && locator.equals(citation.path("locator").asText())
                    && value.equals(citation.path("quote").asText())) return true;
        }
        return false;
    }
    private static FamilyHistory familyHistory(JsonNode values) {
        if ("CONFLICT".equals(values.path("/groups/family/compatibility").asText())) return FamilyHistory.CONFLICT;
        if (Set.of("MOSAIC", "SIBLING_RECURRENCE").contains(values.path("/conditions/specialFamilyModel").asText()))
            return FamilyHistory.MOSAIC_OR_SIBLING_RECURRENCE;
        return "COMPATIBLE".equals(values.path("/groups/family/compatibility").asText()) ? FamilyHistory.COMPATIBLE : FamilyHistory.UNKNOWN;
    }
    static TargetTest target(JsonNode value, boolean qualified) {
        if ("CONFLICT".equals(value.asText())) return TargetTest.CONFLICT;
        if ("QUALITY_FAILED".equals(value.asText())) return TargetTest.QUALITY_FAILED;
        if (!qualified) return TargetTest.UNKNOWN;
        return switch (value.asText()) {
            case "PRESENT" -> TargetTest.PRESENT;
            case "ABSENT" -> TargetTest.ABSENT_QUALIFIED;
            case "QUALITY_FAILED" -> TargetTest.QUALITY_FAILED;
            case "CONFLICT" -> TargetTest.CONFLICT;
            default -> TargetTest.UNKNOWN;
        };
    }
    static Parentage relation(JsonNode value) {
        return switch (value.asText()) {
            case "CONFIRMED" -> Parentage.CONFIRMED;
            case "ASSUMED_NOT_CONFIRMED" -> Parentage.ASSUMED_NOT_CONFIRMED;
            case "ASSUMED_NOT_TESTED" -> Parentage.ASSUMED_NOT_TESTED;
            case "ASSUMED_NOT_REPORTED" -> Parentage.ASSUMED_NOT_REPORTED;
            case "EXCLUDED" -> Parentage.EXCLUDED;
            default -> Parentage.INCONCLUSIVE;
        };
    }
}
