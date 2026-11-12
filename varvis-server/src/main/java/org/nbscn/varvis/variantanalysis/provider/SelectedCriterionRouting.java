package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.BoundedSequenceDeletion;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Conservative dispatch only. A retained route still needs its original scientific checks.
 * Missing, ambiguous or unbound annotations never establish non-applicability. */
public final class SelectedCriterionRouting {
    public static final String VERSION = "selected-criterion-routing-v1";
    public static final List<String> CODES = List.of("BA1", "BS1", "PM2", "PP3", "BP4", "PS1", "PM5", "BP7",
            "PVS1", "PM4", "BP3", "PM1", "PP2", "BP1", "PS2", "PM6", "PM3", "PP1");
    private static final Set<String> KNOWN = Set.of("missense_variant", "synonymous_variant", "intron_variant",
            "inframe_insertion", "inframe_deletion", "frameshift_variant", "stop_gained", "stop_lost",
            "start_lost", "splice_donor_variant", "splice_acceptor_variant", "splice_region_variant",
            "splice_donor_region_variant", "splice_polypyrimidine_tract_variant", "NMD_transcript_variant",
            "non_coding_transcript_exon_variant", "5_prime_UTR_variant", "3_prime_UTR_variant");
    private final ObjectMapper mapper;
    public SelectedCriterionRouting(ObjectMapper mapper) { this.mapper = mapper; }

    /** Execution capability only; retaining a route never establishes a scientific result. */
    public static ObjectNode executionIdentity(ObjectMapper mapper) {
        return mapper.createObjectNode().put("schemaVersion", "selected-routing-execution-v1")
                .put("bp1Dispatch", "SOURCE_RULE_CHECK_INCLUDING_NON_MISSENSE_V1")
                .put("pm5PtcTableSha256", PublishedPm5PtcTable.identity());
    }

    public ObjectNode prepare(SelectedEvidencePreparation selected, String candidate, String hypothesis) {
        ObjectNode context;
        try { context = selected.context(candidate, hypothesis); }
        catch (RuntimeException unavailable) { return decide(null, null, safe(unavailable, "SELECTED_CONTEXT_UNAVAILABLE")); }
        try {
            var projection = selected.legacyProjection(candidate, hypothesis);
            var tx = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper))
                    .selectedVepConsequence(projection.context(), projection.queryCandidate());
            return decide(context, tx, "ROUTING_TRANSCRIPT_ANNOTATION_UNRESOLVED");
        } catch (RuntimeException unavailable) {
            return decide(context, null, safe(unavailable, "ROUTING_TRANSCRIPT_ANNOTATION_UNRESOLVED"));
        }
    }

    ObjectNode decide(JsonNode context, TranscriptConsequence tx, String missingReason) {
        var plan = mapper.createObjectNode().put("schemaVersion", VERSION).put("scientificApproval", false);
        plan.put("contextId", context == null ? "" : context.path("contextId").asText());
        var rules = plan.putObject("criteria");
        for (String code : CODES) rules.putObject(code).put("status", "REQUIRES_RULE_CHECK")
                .put("reasonCode", "ROUTING_ORIGINAL_RULE_CHECK_REQUIRED").put("skipSpecializedAcquisition", false);
        String moi = context == null ? "" : context.path("moi").asText();
        plan.put("moi", moi);
        if (Set.of("AUTOSOMAL_DOMINANT", "X_LINKED_DOMINANT", "MITOCHONDRIAL").contains(moi)) {
            stop(rules, "PM3", "NOT_APPLICABLE", "ROUTING_PM3_NON_RECESSIVE_CONTEXT");
            // Disease inheritance alone establishes this exclusion, including when no
            // transcript annotation is available. Keep its exact interpretation scope.
            ((ObjectNode) rules.path("PM3")).put("contextId", plan.path("contextId").asText()).put("moi", moi);
        } else if (!"AUTOSOMAL_RECESSIVE".equals(moi))
            defer(rules, "PM3", "PM3_SELECTED_RECESSIVE_DISEASE_CONTEXT_REQUIRED");
        if (tx == null || tx.getConsequence() == null || tx.getConsequence().isBlank()) {
            for (String code : List.of("PP3", "BP4", "PS1", "PM5", "BP7", "PVS1", "PM4", "BP3", "PM1", "PP2", "BP1"))
                defer(rules, code, missingReason);
            return restrictExtendedDeletion(plan, context);
        }
        plan.put("transcript", tx.getVersionedTranscriptId()).put("annotationVersion", tx.getVersion());
        Set<String> terms;
        try { terms = consequenceTerms(tx.getConsequence()); }
        catch (IllegalArgumentException invalid) {
            for (String code : List.of("PP3", "BP4", "PS1", "PM5", "BP7", "PVS1", "PM4", "BP3", "PM1", "PP2", "BP1"))
                defer(rules, code, "ROUTING_CONSEQUENCE_NOT_RECOGNIZED");
            return restrictExtendedDeletion(plan, context);
        }
        plan.set("consequenceTerms", mapper.valueToTree(terms));
        if (!KNOWN.containsAll(terms) || terms.equals(Set.of("NMD_transcript_variant"))
                || (terms.contains("missense_variant") && terms.contains("synonymous_variant"))) {
            for (String code : List.of("PM5", "BP7", "PM4", "BP3", "PM1", "PP2", "BP1"))
                defer(rules, code, "ROUTING_CONSEQUENCE_NOT_RECOGNIZED");
            return restrictExtendedDeletion(plan, context);
        }
        routePm5(rules, context, tx, terms);
        if (!terms.contains("missense_variant")) {
            stop(rules, "PP2", "NOT_APPLICABLE", "ROUTING_MISSENSE_REQUIRED");
            // Published BP1 subtypes can concern truncating changes. Consequence alone
            // cannot exclude them; the source-bound runtime checks the exact subtype.
            if (PublishedPm1Deletion.includesDeletion(terms) && PublishedPm1Deletion.scope(context) != null)
                ((ObjectNode) rules.path("PM1")).put("reasonCode", "PM1_PUBLISHED_DELETION_RULE_CHECK");
            else
                stop(rules, "PM1", "SOFTWARE_UNSUPPORTED", "ROUTING_PM1_IMPLEMENTED_MISSENSE_SCOPE");
        }
        if (Set.of("missense_variant", "synonymous_variant", "NMD_transcript_variant").containsAll(terms)
                && (terms.contains("missense_variant") || terms.contains("synonymous_variant"))) {
            for (String code : List.of("PM4", "BP3"))
                stop(rules, code, "NOT_APPLICABLE", "ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE");
        }
        String bp7 = new IntronicSplicePolicy(mapper).blocker(tx);
        if (IntronicSplicePolicy.notApplicable(bp7)) stop(rules, "BP7", "NOT_APPLICABLE", bp7);
        for (var decision : rules) if (decision.path("skipSpecializedAcquisition").asBoolean()) {
            ((ObjectNode) decision).put("contextId", plan.path("contextId").asText()).put("moi", moi)
                    .put("transcript", tx.getVersionedTranscriptId()).put("annotationVersion", tx.getVersion())
                    .set("consequenceTerms", mapper.valueToTree(terms));
        }
        return restrictExtendedDeletion(plan, context);
    }

    static boolean extendedDeletion(@Nullable JsonNode context) {
        return context != null && BoundedSequenceDeletion.NORMALIZATION_METHOD.equals(context.at("/variant/normalization/method").asText());
    }

    private static ObjectNode restrictExtendedDeletion(ObjectNode plan, @Nullable JsonNode context) {
        if (extendedDeletion(context)) {
            var rules = (ObjectNode) plan.path("criteria");
            for (String code : CODES) if (!Set.of("BA1", "BS1", "PM1").contains(code))
                stop(rules, code, "SOFTWARE_UNSUPPORTED", "ROUTING_EXTENDED_DELETION_PM1_ONLY");
        }
        return plan;
    }

    private static void routePm5(ObjectNode rules, JsonNode context, TranscriptConsequence tx, Set<String> terms) {
        var source = PublishedPm5PtcTable.select(context);
        if (source == null) {
            if (!terms.contains("missense_variant"))
                stop(rules, "PM5", "NOT_APPLICABLE", "ROUTING_MISSENSE_REQUIRED");
            return;
        }
        var route = (ObjectNode) rules.path("PM5");
        route.put("branch", "GENOMIC_PTC").put("specification", source.specification())
                .put("ruleSourceUrl", source.specificationUrl()).put("ruleTableSha256", source.tableSha());
        String exclusion = publishedPm5ExclusionReason(terms);
        if (!source.matchesTranscript(tx.getVersionedTranscriptId())) {
            stop(rules, "PM5", "UNRESOLVED", "PM5_PTC_TRANSCRIPT_IDENTITY_UNRESOLVED");
        } else if (exclusion != null) {
            stop(rules, "PM5", "NOT_APPLICABLE", exclusion);
        } else if (PublishedPm5PtcTable.directAnnotation(terms)) {
            route.put("reasonCode", "PM5_PTC_DIRECT_GENOMIC_RULE_CHECK");
        } else {
            stop(rules, "PM5", "UNRESOLVED", "PM5_PTC_CONSEQUENCE_MECHANISM_UNRESOLVED");
        }
    }

    private static String publishedPm5ExclusionReason(Set<String> terms) {
        boolean directStop = terms.contains("stop_gained") || terms.contains("frameshift_variant");
        if (terms.contains("splice_donor_variant") || terms.contains("splice_acceptor_variant")
                || !directStop && terms.stream().anyMatch(term -> term.contains("splice")))
            return "PM5_PTC_SPLICE_MECHANISM_EXCLUDED";
        return directStop ? null : "PM5_PTC_DIRECT_GENOMIC_STOP_REQUIRED";
    }

    public static boolean isPublishedPm5Exclusion(JsonNode route, JsonNode context) {
        var scope = PublishedPm5PtcTable.select(context);
        if (scope == null || !"GENOMIC_PTC".equals(route.path("branch").asText())
                || !scope.matchesTranscript(route.path("transcript").asText())
                || !scope.specification().equals(route.path("specification").asText())
                || !scope.tableSha().equals(route.path("ruleTableSha256").asText())
                || !route.path("consequenceTerms").isArray()) return false;
        var terms = new LinkedHashSet<String>();
        route.path("consequenceTerms").forEach(term -> terms.add(term.asText()));
        return !terms.isEmpty() && KNOWN.containsAll(terms)
                && route.path("reasonCode").asText().equals(publishedPm5ExclusionReason(terms));
    }
    private static void defer(ObjectNode rules, String code, String reason) {
        ((ObjectNode) rules.path(code)).put("status", "UNRESOLVED").put("reasonCode", reason);
    }
    private static void stop(ObjectNode rules, String code, String status, String reason) {
        ((ObjectNode) rules.path(code)).put("status", status).put("reasonCode", reason).put("skipSpecializedAcquisition", true);
    }
    public static boolean skip(JsonNode plan, String code) { return plan.path("criteria").path(code).path("skipSpecializedAcquisition").asBoolean(false); }
    static Set<String> consequenceTerms(String raw) {
        var terms = new LinkedHashSet<String>();
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("ROUTING_CONSEQUENCE_NOT_RECOGNIZED");
        for (String part : raw.split("&", -1)) {
            String term = part.trim();
            if (!term.matches("[A-Za-z][A-Za-z0-9_]*") || !terms.add(term))
                throw new IllegalArgumentException("ROUTING_CONSEQUENCE_NOT_RECOGNIZED");
        }
        return terms;
    }
    static boolean proteinMissenseOnly(String raw) {
        try {
            var terms = consequenceTerms(raw);
            return terms.contains("missense_variant") && Set.of("missense_variant", "NMD_transcript_variant").containsAll(terms);
        } catch (IllegalArgumentException malformed) { return false; }
    }
    private static String safe(RuntimeException failure, String fallback) {
        String reason = failure.getMessage();
        return reason != null && reason.matches("[A-Z][A-Z0-9_]{1,160}") ? reason : fallback;
    }
}
