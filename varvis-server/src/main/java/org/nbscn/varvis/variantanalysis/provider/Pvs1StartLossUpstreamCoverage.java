package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Fixed source scope, evaluated only on internally acquired, untruncated records.
 * Literature follows unresolved source leads; this is never an internet-wide
 * absence claim. No reference's independence is inferred by this class. */
final class Pvs1StartLossUpstreamCoverage {
    static final String PLAN = "LOCAL_CLINVAR_INTERVAL_AND_EREPO_GENE_WITH_LEAD_FOLLOWUP_V1";
    private static final Set<String> EXCLUSIONS = Set.of("TARGET_EXCLUDED", "WITHDRAWN_EXCLUDED",
            "OUTSIDE_UPSTREAM_INTERVAL", "BACKGROUND_MISMATCH", "BASELINE_PATHOGENIC_REQUIRED");
    private Pvs1StartLossUpstreamCoverage() { }

    static ObjectNode reconcile(JsonNode context, JsonNode model, int restart, ObjectNode upstream, JsonNode clinvar) {
        return reconcile(context, model, restart, upstream, clinvar, null);
    }
    static ObjectNode reconcile(JsonNode context, JsonNode model, int restart, ObjectNode upstream, JsonNode clinvar,
            @org.springframework.lang.Nullable Pvs1StartLossPublicSources sources) {
        var out = upstream.deepCopy().put("complete", false).put("negativeCoverageGranted", false);
        if (Set.of("NO_QUALIFYING_REFERENCE_WITHIN_SCOPE", "WITNESS_VALIDATED").contains(out.path("status").asText()))
            out.put("status", "INCOMPLETE").put("reasonCode", "PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
        for (var record : out.path("records")) if (record instanceof ObjectNode row
                && Set.of("QUALIFYING", "DUPLICATE_QUALIFYING").contains(row.path("disposition").asText())) {
            row.put("disposition", "UNRESOLVED").put("reasonCode", "CLINVAR_LOCAL_EQUIVALENCE_RECHECK_REQUIRED");
            if (row.at("/nativeAssessment/clinvarAssertions") instanceof ObjectNode linked)
                linked.put("localEquivalentRecordReviewPending", true).put("localEquivalentRecordReviewComplete", false);
            if (row.path("nativeAssessment") instanceof ObjectNode assessment) assessment.put("independenceEstablished", false);
        }
        var coverage = out.putObject("coverage").put("planId", PLAN).put("globalAbsenceClaimed", false)
                .put("literatureMode", "FOLLOW_RELEVANT_SOURCE_LEADS");
        coverage.putArray("requiredSources").add("LOCAL_CLINVAR_CODING_INTERVALS_ALL_ALLELE_TYPES").add("EREPO_STABLE_GENE_PAGES");
        var missing = coverage.putArray("unresolvedReasons");
        boolean localComplete = "ENUMERATED".equals(clinvar.path("status").asText())
                && clinvar.path("complete").isBoolean() && clinvar.path("complete").asBoolean()
                && "LOCAL_CLINVAR_CODING_INTERVALS_ALL_ALLELE_TYPES".equals(clinvar.path("scope").asText())
                && clinvar.path("records").isArray() && clinvar.path("records").size() <= 10000
                && !clinvar.at("/resource/version").asText().isBlank()
                && clinvar.path("sourceSha256").asText().matches("[a-f0-9]{64}");
        try {
            var expected = Pvs1StartLossClinVarSearch.intervals(model, restart).stream()
                    .map(r -> context.at("/variant/evidenceQueryAllele/contig").asText() + ":" + r.start() + "-" + r.end()).toList();
            localComplete &= clinvar.path("regions").isArray() && clinvar.path("regions").size() == expected.size();
            for (int i = 0; i < expected.size(); i++) localComplete &= expected.get(i).equals(clinvar.path("regions").path(i).asText());
        } catch (IllegalArgumentException invalid) { localComplete = false; }
        if (!localComplete) missing.add("CLINVAR_REQUIRED_INTERVAL_NOT_COMPLETE");
        int witnesses = localComplete ? qualifyWitnesses(context, model, out, clinvar, sources) : 0;
        out.put("qualifyingReferenceCount", witnesses);
        // A nonempty interval can be resolved only by per-candidate provenance,
        // identity and individual submission review, never aggregate VCF labels.
        boolean candidatesResolved = clinvar.path("records").isEmpty() || candidatesExcluded(clinvar);
        if (localComplete && !candidatesResolved) missing.add("CLINVAR_REFERENCE_QUALIFICATION_REQUIRED");
        boolean erepoComplete = "AVAILABLE".equals(upstream.at("/source/status").asText())
                && upstream.path("sourceEnumerationComplete").asBoolean()
                && upstream.at("/source/sourceEnumerationComplete").asBoolean()
                && upstream.at("/source/sha256").asText().matches("[a-f0-9]{64}")
                && upstream.path("records").isArray() && upstream.path("records").size() <= 10000
                && upstream.at("/source/recordCount").isIntegralNumber()
                && upstream.at("/source/recordCount").asInt() == upstream.path("records").size()
                && upstream.path("cdsStart").asInt() == 1 && upstream.path("cdsEnd").asInt() == restart - 1;
        if (!erepoComplete) missing.add("EREPO_REQUIRED_GENE_SEARCH_NOT_COMPLETE");
        int unresolved = 0;
        for (var row : upstream.path("records"))
            if (!"EXCLUDED".equals(row.path("disposition").asText()) || !EXCLUSIONS.contains(row.path("reasonCode").asText())) unresolved++;
        if (unresolved > 0) missing.add("EREPO_REFERENCE_QUALIFICATION_REQUIRED");
        coverage.put("clinvarEnumerationComplete", localComplete).put("clinvarCandidatesResolved", localComplete && candidatesResolved)
                .put("erepoEnumerationComplete", erepoComplete)
                .put("unresolvedErepoRecords", unresolved).put("complete", missing.isEmpty());
        if (witnesses > 0) out.put("status", "WITNESS_VALIDATED").put("reasonCode", "PVS1_START_LOSS_INDEPENDENT_REFERENCE_VALIDATED");
        else if (missing.isEmpty()) out.put("status", "NO_QUALIFYING_REFERENCE_WITHIN_SCOPE").put("complete", true)
                .put("negativeCoverageGranted", true).put("reasonCode", "PVS1_START_LOSS_NO_REFERENCE_IN_DECLARED_SOURCES");
        return out;
    }

    private static boolean candidatesExcluded(JsonNode scan) {
        var review = scan.path("candidateReview");
        if (!"ALL_CANDIDATES_EXCLUDED".equals(review.path("status").asText()) || !review.path("complete").asBoolean()
                || !scan.path("sourceSha256").equals(review.path("sourceSha256"))
                || !review.path("records").isArray() || review.path("records").size() != scan.path("records").size()) return false;
        for (int i = 0; i < review.path("records").size(); i++) {
            var row = review.path("records").get(i);
            if (!"EXCLUDED".equals(row.path("disposition").asText())
                    || !Set.of("TARGET_EXCLUDED", "OUTSIDE_UPSTREAM_INTERVAL", "CLINVAR_NO_CURRENT_PATHOGENIC_ASSERTION_IN_SCOPE").contains(row.path("reasonCode").asText())
                    || !row.path("variationId").equals(scan.path("records").get(i).path("variationId"))
                    || !("/records/" + i).equals(row.path("sourceLocator").asText())) return false;
        }
        return true;
    }

    /** The source pipeline has checked original curated grounds and the linked
     * VCV's individual SCVs. Inspect local aliases of that allele before adopting
     * a witness; unrelated unresolved alleles do not negate an existence proof. */
    private static int qualifyWitnesses(JsonNode context, JsonNode model, ObjectNode out, JsonNode clinvar,
            @org.springframework.lang.Nullable Pvs1StartLossPublicSources sources) {
        var adopted = new java.util.HashSet<String>();
        for (var record : out.path("records")) {
            if (!(record instanceof ObjectNode row) || !"UNRESOLVED".equals(row.path("disposition").asText())
                    || !"Pathogenic".equals(row.path("classification").asText())) continue;
            var assessment = row.path("nativeAssessment"); var linked = assessment.path("clinvarAssertions");
            String key = row.path("alleleKey").asText();
            if (!key.matches("[0-9a-f]{64}") || !key.equals(linked.path("codingAlleleKey").asText())
                    || !"INDEPENDENT_CURATED_BASIS_ESTABLISHED".equals(assessment.at("/curatedGrounds/status").asText())
                    || !"CONSISTENT_WITH_EREPO".equals(linked.path("status").asText())
                    || !linked.path("localEquivalentRecordReviewPending").asBoolean()) continue;
            String issue = null;
            var aliasIssues = ((ObjectNode) linked).putArray("localEquivalentRecordIssues");
            var aliasReviews = ((ObjectNode) linked).putArray("localEquivalentRecordReviews");
            for (var other : out.path("records")) if (key.equals(other.path("alleleKey").asText())
                    && !Set.of("WITHDRAWN_EXCLUDED", "TARGET_EXCLUDED").contains(other.path("reasonCode").asText())
                    && ("CONFLICT".equals(other.path("disposition").asText())
                    || !Set.of("Pathogenic", "Likely Pathogenic").contains(other.path("classification").asText())))
                issue = "REFERENCE_CLASSIFICATION_CONFLICT_OR_UNRESOLVED";
            JsonNode allele = linked.path("allele");
            int localIndex = 0;
            for (var local : clinvar.path("records")) {
                String aliasIssue = null;
                var mapping = Pvs1StartLossReferenceMapping.genomic(model, local.path("position").asInt(),
                        local.path("reference").asText(), local.path("alternate").asText());
                boolean sameId = row.path("clinvarVariationId").equals(local.path("variationId"));
                if (mapping.mapped()) {
                    if (key.equals(mapping.key()) && !sameId) {
                        aliasIssue = "CLINVAR_EQUIVALENT_SECONDARY_RECORD_REQUIRES_REVIEW";
                        if (sources != null) {
                            String id = local.path("variationId").asText();
                            var reviewed = Pvs1StartLossClinVarAssertions.equivalentRecord(
                                    new com.fasterxml.jackson.databind.ObjectMapper(), context, id, sources.clinvarRecord(id));
                            var sourceAllele = reviewed.path("allele");
                            var sourceMapping = Pvs1StartLossReferenceMapping.genomic(model, sourceAllele.path("position").asInt(),
                                    sourceAllele.path("reference").asText(), sourceAllele.path("alternate").asText());
                            reviewed.put("variationId", id).put("localSourceLocator", "/records/" + localIndex)
                                    .put("codingAlleleKey", sourceMapping.key());
                            aliasReviews.add(reviewed);
                            if (!sourceMapping.mapped() || !key.equals(sourceMapping.key()))
                                aliasIssue = "CLINVAR_LOCAL_SOURCE_ALLELE_MISMATCH";
                            else if ("NO_ADVERSE_CURRENT_ASSERTION".equals(reviewed.path("status").asText())) aliasIssue = null;
                            else aliasIssue = reviewed.path("reasonCode").asText("CLINVAR_REFERENCE_POTENTIAL_CONFLICT_UNRESOLVED");
                        }
                    }
                    else if (sameId && !key.equals(mapping.key())) aliasIssue = "CLINVAR_LOCAL_ALLELE_ID_CONFLICT";
                } else {
                    long lo = local.path("position").asLong(-1), hi = lo + local.path("reference").asText().length() - 1;
                    long witnessLo = allele.path("position").asLong(), witnessHi = witnessLo + allele.path("reference").asText().length() - 1;
                    boolean referenceIndel = allele.path("reference").asText().length() != allele.path("alternate").asText().length();
                    // Equivalent edits must have the same net length change,
                    // regardless of left/right anchoring. An overlapping large
                    // deletion is not an alias of a SNV merely because mapping
                    // that deletion exceeds this bounded implementation.
                    String ref = local.path("reference").asText(), alt = local.path("alternate").asText();
                    boolean differentLengthChange = ref.matches("[ACGT]+") && alt.matches("[ACGT]+")
                            && alt.length() - ref.length() != allele.path("alternate").asText().length() - allele.path("reference").asText().length();
                    if (sameId || !differentLengthChange && (referenceIndel || lo <= witnessHi && hi >= witnessLo))
                        aliasIssue = "CLINVAR_POTENTIALLY_EQUIVALENT_ALLELE_UNRESOLVED";
                }
                if (aliasIssue != null) {
                    issue = aliasIssue;
                    aliasIssues.addObject().put("variationId", local.path("variationId").asText()).put("sourceLocator", "/records/" + localIndex)
                            .put("reasonCode", aliasIssue).put("mappingReason", mapping.reason());
                }
                localIndex++;
            }
            if (issue != null) { row.put("reasonCode", issue); continue; }
            ((ObjectNode) linked).put("localEquivalentRecordReviewPending", false).put("localEquivalentRecordReviewComplete", true);
            ((ObjectNode) assessment).put("independenceEstablished", true);
            row.put("disposition", adopted.add(key) ? "QUALIFYING" : "DUPLICATE_QUALIFYING")
                    .put("reasonCode", "PVS1_START_LOSS_INDEPENDENT_REFERENCE_VALIDATED");
        }
        return adopted.size();
    }
}
