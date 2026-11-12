package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Bounded, display-only facts from an unresolved PVS1 branch. */
final class Pvs1UnresolvedAssessment {
    private static final String SCHEMA = "pvs1-unresolved-assessment-v1";
    private static final int NEARBY_NUCLEOTIDES = 20;
    private static final Set<String> DONOR_MOTIFS = Set.of("GT", "GC");
    private static final Set<String> ACCEPTOR_MOTIFS = Set.of("AG");

    private Pvs1UnresolvedAssessment() { }

    /** The caller has verified the outcome against the qualification and live reference. */
    @Nullable
    static ObjectNode splice(ObjectMapper mapper, RuntimeEvidenceV2Adapter adapter, JsonNode context,
            VariantInterpretation candidate, JsonNode model, JsonNode outcome, String reason) {
        var event = outcome.path("event");
        int exonOrdinal = event.path("exonOrdinal").asInt();
        var exon = model.path("exons").path(exonOrdinal - 1);
        var window = outcome.path("referenceWindow");
        String kind = outcome.path("evidenceKind").asText();
        boolean predicted = "PREDICTED_CANONICAL".equals(kind);
        if (predicted) {
            var annotation = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (annotation == null || Pvs1SpliceConsequence.canonicalIssue(context, annotation, model, exon, window, event) != null)
                return null;
        }
        var detail = create(mapper, "SPLICE", reason, model);
        var review = outcome.path("review");
        int start = event.path("deleteStartCds").asInt(), end = event.path("deleteEndCds").asInt();
        int length = end - start + 1;
        detail.put("evidenceKind", kind).put("outcomeId", outcome.path("outcomeId").asText())
                .put("outcomeMultiplicity", review.path("outcomeMultiplicity").asText())
                .put("functionalRescue", review.path("functionalRescue").asText())
                .put("residualNormalTranscript", review.path("residualNormalTranscript").asText());
        var product = detail.putObject("candidateProduct");
        product.put("eventType", event.path("type").asText()).put("exonOrdinal", exonOrdinal)
                .put("deleteStartCds", start).put("deleteEndCds", end).put("deletedCodingBases", length)
                .put("readingFrame", length % 3 == 0 ? "PRESERVED" : "ALTERED");
        var missing = detail.putArray("missingFacts");
        if (!"SINGLE_SUPPORTED_OUTCOME".equals(review.path("outcomeMultiplicity").asText()))
            missing.add("SPLICE_PRODUCT_DISTINCTION");
        if (!"EXCLUDED_IN_REVIEWED_SCOPE".equals(review.path("functionalRescue").asText()))
            missing.add("SPLICE_FUNCTIONAL_RESCUE");
        if (!"NOT_DETECTED_IN_ASSESSED_SCOPE".equals(review.path("residualNormalTranscript").asText()))
            missing.add("RESIDUAL_NORMAL_TRANSCRIPT");
        if (predicted) addNearbySites(context, model, exon, window, detail);
        publicSources(detail, outcome.path("sourceRecords"), null);
        return detail;
    }

    private static void addNearbySites(JsonNode context, JsonNode model, JsonNode exon, JsonNode window, ObjectNode detail) {
        int strand = model.path("strand").asInt(), windowStart = window.path("start").asInt();
        var allele = context.at("/variant/evidenceQueryAllele");
        int position = allele.path("position").asInt();
        int exonStart = exon.path("genomicStart").asInt(), exonEnd = exon.path("genomicEnd").asInt();
        int donor = strand == 1 ? exonEnd + 1 : exonStart - 1;
        boolean isDonor = position == donor || position == donor + strand;
        int motifFirstBase = isDonor ? donor : strand == 1 ? exonStart - 2 : exonEnd + 2;
        int editOffset = position - windowStart;
        String reference = window.path("sequence").asText();
        int center = strand == 1 ? motifFirstBase - windowStart : reference.length() - 1 - (motifFirstBase - windowStart);
        String changed = CodingTranscriptMapping.orientAllele(model, reference.substring(0, editOffset)
                + allele.path("alternate").asText() + reference.substring(editOffset + 1));
        var candidates = detail.putArray("nearbyInFrameSites");
        var motifs = isDonor ? DONOR_MOTIFS : ACCEPTOR_MOTIFS;
        int maximumInFrameOffset = NEARBY_NUCLEOTIDES / 3 * 3;
        for (int offset = -maximumInFrameOffset; offset <= maximumInFrameOffset; offset += 3) {
            int index = center + offset;
            if (offset == 0 || index < 0 || index + 2 > changed.length()
                    || (isDonor ? -offset : offset) >= exonEnd - exonStart + 1) continue;
            String motif = changed.substring(index, index + 2);
            if (motifs.contains(motif)) candidates.addObject().put("siteType", isDonor ? "DONOR" : "ACCEPTOR")
                    .put("offsetNt", offset).put("motif", motif).put("status", "SEQUENCE_CANDIDATE");
        }
    }

    static void recordEscape(ObjectMapper mapper, JsonNode model, JsonNode reviews, ObjectNode result) {
        var evaluation = result.at("/evaluation/evaluationResult");
        var policy = result.path("policy");
        if (!"NOT_EVALUATED".equals(evaluation.path("status").asText()) || policy.has("escapeScope")
                || !"PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED".equals(evaluation.path("reasonCode").asText())
                || !(result.path("resolvedInput") instanceof ObjectNode input)) return;
        // A stricter policy distance limit alone does not establish physical escape.
        var distance = input.path("distanceToLastJunction");
        if (!distance.isIntegralNumber() || !distance.canConvertToInt() || distance.asInt() >= 51) return;
        int first = input.path("firstChangedProteinPosition").asInt(input.path("proteinPosition").asInt());
        int length = model.path("proteinLength").asInt();
        int stop = input.path("ptcStart").asInt();
        if (first < 1 || first > length || stop < 1 || (stop - 1) % 3 != 0 || first > (stop - 1) / 3 + 1) return;
        var detail = create(mapper, "ESCAPE", evaluation.path("reasonCode").asText(), model)
                .put("scopeStatus", "NO_ADOPTED_ESCAPE_SCOPE")
                .put("firstAlteredResidue", first).put("referenceProteinLength", length)
                .put("alteredProteinLength", (stop - 1) / 3);
        var affected = detail.putArray("affectedExonOrdinals");
        for (var exon : model.path("exons"))
            if (exon.path("cdsEnd").asInt() >= first * 3 - 2 && exon.path("cdsStart").asInt() <= length * 3)
                affected.add(exon.path("ordinal").asInt());
        if (affected.isEmpty()) return;
        detail.putArray("missingFacts").add("ESCAPE_SCOPE_NOT_ADOPTED");
        addEscapeReview(detail, affected, policy, input, reviews.path(result.path("policySha256").asText()));
        result.set("unresolvedAssessment", detail);
    }

    private static void addEscapeReview(ObjectNode detail, ArrayNode affected, JsonNode policy, ObjectNode input, JsonNode review) {
        var conditions = review.path("positionConditions");
        if (!"UNREVIEWED".equals(review.path("status").asText())
                || !review.path("candidateRawSha256").asText().matches("[a-f0-9]{64}")
                || !review.path("transcriptModelSha256").equals(policy.at("/transcriptModelBinding/canonicalSha256"))
                || !Pvs1NmdConditions.validConditions(policy, conditions)
                || Pvs1NmdConditions.evaluateConditions(conditions, input.deepCopy(), "checks") != null) return;
        var missing = detail.withArray("missingFacts");
        for (var row : review.path("exonAssessments")) {
            var exon = row.path("ordinal");
            boolean relevant = false;
            for (var ordinal : affected) if (ordinal.equals(exon)) relevant = true;
            if (!relevant || !"PRESENT".equals(row.path("biologicalRelevance").asText())
                    || !"UNKNOWN".equals(row.path("populationLof").asText())) continue;
            detail.withArray("exonAssessments").addObject().put("ordinal", exon.asInt())
                    .put("biologicalRelevance", "PRESENT").put("populationLof", "UNKNOWN");
        }
        if (!detail.has("exonAssessments")) return;
        detail.put("candidateReviewStatus", "UNREVIEWED")
                .put("candidateRawSha256", review.path("candidateRawSha256").asText());
        missing.add("EXON_POPULATION_LOF_TOLERANCE");
        publicSources(detail, policy.path("scientificSources"), review.path("sourceIds"));
    }

    private static ObjectNode create(ObjectMapper mapper, String kind, String reason, JsonNode model) {
        return mapper.createObjectNode().put("schemaVersion", SCHEMA).put("kind", kind)
                .put("reasonCode", reason).put("transcript", model.path("transcript").asText());
    }

    private static void publicSources(ObjectNode detail, JsonNode sources, @Nullable JsonNode selectedIds) {
        var output = detail.putArray("sources");
        for (var source : sources) {
            boolean selected = selectedIds == null;
            if (selectedIds != null) for (var id : selectedIds) if (id.equals(source.path("sourceId"))) selected = true;
            if (!selected) continue;
            var projected = output.addObject();
            for (String field : List.of("sourceId", "version", "url", "sha256"))
                if (source.path(field).isTextual()) projected.set(field, source.path(field).deepCopy());
        }
    }
}
