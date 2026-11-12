package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** ClinGen SVI 2023 Table 2 strength selection with explicit, bounded research comparison scopes.
 * Scores describe predictions, never measured exon skipping or normal RNA. */
public final class Ps1SpliceComparison {
    public static final String VERSION = "1.1.0";
    public static final String CRITERION = "ACMG_AMP_2015:PS1";
    private static final BigDecimal IMPACT = new BigDecimal("0.2"), QUIET = new BigDecimal("0.1");
    private Ps1SpliceComparison() { }

    /** The model must already be bound to the selected transcript and qualified reference. */
    static ObjectNode motif(ObjectMapper mapper, JsonNode model, int position) {
        ObjectNode found = null;
        int strand = model.path("strand").asInt();
        require(strand == 1 || strand == -1, "PS1_SPLICE_TRANSCRIPT_INVALID");
        JsonNode exons = model.path("exons");
        for (int i = 0; i + 1 < exons.size(); i++) {
            int donor = exons.get(i).path(strand == 1 ? "genomicEnd" : "genomicStart").asInt();
            int acceptor = exons.get(i + 1).path(strand == 1 ? "genomicStart" : "genomicEnd").asInt();
            for (String kind : List.of("DONOR", "ACCEPTOR")) {
                int boundary = "DONOR".equals(kind) ? donor : acceptor;
                long offset = ((long) position - boundary) * strand;
                int low = "DONOR".equals(kind) ? -2 : -20, high = "DONOR".equals(kind) ? 6 : 0;
                if (offset < low || offset > high) continue;
                require(found == null, "PS1_SPLICE_MOTIF_AMBIGUOUS");
                boolean canonical = "DONOR".equals(kind) ? offset == 1 || offset == 2 : offset == -1 || offset == -2;
                int a = boundary + low * strand, b = boundary + high * strand;
                found = mapper.createObjectNode().put("kind", kind).put("boundary", boundary)
                        .put("donorBoundary", donor).put("acceptorBoundary", acceptor).put("intronOrdinal", i + 1)
                        .put("start", Math.min(a, b)).put("end", Math.max(a, b)).put("strand", strand)
                        .put("offset", offset).put("canonical", canonical)
                        .put("exonic", "DONOR".equals(kind) ? offset <= 0 : offset >= 0);
            }
        }
        require(found != null, "PS1_SPLICE_OUTSIDE_SUPPORTED_MOTIF");
        return found;
    }

    /** All four raw scores/positions are inspected. Competing or cryptic events are not collapsed to max DS. */
    static ObjectNode event(ObjectMapper mapper, JsonNode score, JsonNode allele, JsonNode motif) {
        return event(mapper, score, allele, motif, "SINGLE_NATIVE_SITE_LOSS");
    }

    static ObjectNode event(ObjectMapper mapper, JsonNode score, JsonNode allele, JsonNode motif, String scope) {
        require(Set.of("SINGLE_NATIVE_SITE_LOSS", "MATCHED_NATIVE_LOSS_PROFILE").contains(scope), "PS1_SPLICE_PROFILE_SCOPE_UNSUPPORTED");
        boolean full = "MATCHED_NATIVE_LOSS_PROFILE".equals(scope);
        var profile = mapper.createObjectNode();
        String main = "DONOR".equals(motif.path("kind").asText()) ? "DL" : "AL";
        BigDecimal mainScore = null;
        for (String kind : List.of("AG", "AL", "DG", "DL")) {
            JsonNode ds = score.path("deltaScores").path("DS_" + kind), dp = score.path("deltaPositions").path("DP_" + kind);
            BigDecimal value = PredictionCalibration.decimal(ds.asText());
            if (!(ds.isTextual() && value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0
                    && dp.isIntegralNumber() && dp.canConvertToInt() && Math.abs((long) dp.asInt()) <= 4999)) {
                throw new IllegalArgumentException("PS1_SPLICE_SCORE_INVALID");
            }
            long position = (long) allele.path("position").asInt() + dp.asInt();
            require(position > 0 && position <= Integer.MAX_VALUE, "PS1_SPLICE_SCORE_INVALID");
            profile.putObject(kind).put("score", ds.asText()).put("offset", dp.asInt()).put("position", (int) position);
            if (kind.equals(main)) {
                require(value.compareTo(IMPACT) > 0, "PS1_SPLICE_PREDICTION_BELOW_THRESHOLD");
                require((long) allele.path("position").asInt() + dp.asInt() == motif.path("boundary").asInt(),
                        "PS1_SPLICE_EVENT_POSITION_MISMATCH");
                mainScore = value;
            } else if (!full) require(value.compareTo(QUIET) <= 0, "PS1_SPLICE_MULTIPLE_OR_CRYPTIC_EVENTS_UNSUPPORTED");
        }
        require(mainScore != null, "PS1_SPLICE_SCORE_INVALID");
        var out = mapper.createObjectNode().put("event", "NATIVE_" + motif.path("kind").asText() + "_LOSS")
                .put("position", motif.path("boundary").asInt()).put("score", java.util.Objects.requireNonNull(mainScore).toPlainString())
                .put("observedRna", false).put("inferredExonSkipping", false);
        if (full) out.set("profile", profile);
        return out;
    }

    static ObjectNode compare(ObjectMapper mapper, JsonNode target, JsonNode reference, JsonNode tm, JsonNode rm,
            JsonNode targetEvent, JsonNode referenceEvent, String classification, boolean clinicalSupport,
            String baseline, String similarityMode) {
        require(!target.equals(reference), "PS1_SPLICE_SELF_REFERENCE");
        for (String field : List.of("assembly", "contig")) require(target.path(field).equals(reference.path(field)), "PS1_SPLICE_IDENTITY_MISMATCH");
        for (String field : List.of("kind", "boundary", "strand", "intronOrdinal"))
            require(tm.path(field).equals(rm.path(field)), "PS1_SPLICE_DIFFERENT_MOTIF");
        require(targetEvent.path("event").equals(referenceEvent.path("event"))
                && targetEvent.path("position").equals(referenceEvent.path("position")), "PS1_SPLICE_DIFFERENT_EVENT");
        require(Set.of("PATHOGENIC", "LIKELY_PATHOGENIC").contains(classification), "PS1_SPLICE_REFERENCE_NOT_QUALIFIED");
        BigDecimal t = new BigDecimal(targetEvent.path("score").asText()), r = new BigDecimal(referenceEvent.path("score").asText());
        require(Set.of("NO_WEAKER", "PUBLISHED_RELATIVE_TEN_PERCENT").contains(similarityMode), "PS1_SPLICE_SIMILARITY_POLICY_UNSUPPORTED");
        if (targetEvent.has("profile") || referenceEvent.has("profile")) compareProfiles(targetEvent, referenceEvent, similarityMode);
        // A numeric tolerance is only selected by a separately source-bound published clause.
        BigDecimal floor = "PUBLISHED_RELATIVE_TEN_PERCENT".equals(similarityMode) ? r.multiply(new BigDecimal("0.9")) : r;
        require(t.compareTo(floor) >= 0, "PS1_SPLICE_TARGET_WEAKER_THAN_REFERENCE");
        boolean pathogenic = "PATHOGENIC".equals(classification), canonical = tm.path("canonical").asBoolean();
        String strength;
        if (!canonical) {
            require("PP3".equals(baseline), "PS1_SPLICE_BASELINE_REQUIRED");
            if (!pathogenic && rm.path("canonical").asBoolean()) require(clinicalSupport, "PS1_SPLICE_REFERENCE_CLINICAL_SUPPORT_MISSING");
            boolean sameBase = target.path("position").equals(reference.path("position"));
            strength = sameBase ? pathogenic ? "STRONG" : "MODERATE" : pathogenic ? "MODERATE" : "SUPPORTING";
        } else {
            require(Set.of("PVS1", "PVS1_STRONG", "PVS1_MODERATE", "PVS1_SUPPORTING").contains(baseline),
                    "PS1_SPLICE_PVS1_BASELINE_REQUIRED");
            if (rm.path("canonical").asBoolean()) {
                require(pathogenic, "PS1_SPLICE_CANONICAL_LP_REFERENCE_NOT_APPLICABLE");
                strength = "PVS1".equals(baseline) ? "SUPPORTING" : "STRONG";
            } else strength = "PVS1".equals(baseline) || !pathogenic ? "SUPPORTING" : "MODERATE";
        }
        var out = mapper.createObjectNode().put("status", "MET").put("reasonCode", "PS1_SAME_PREDICTED_NATIVE_SPLICE_LOSS")
                .put("appliedStrength", strength).put("baseline", baseline).put("similarityMode", similarityMode)
                .put("methodVersion", VERSION).put("observedRna", false);
        out.set("targetEvent", targetEvent.deepCopy()); out.set("referenceEvent", referenceEvent.deepCopy());
        return out;
    }

    /** Bounded research operator, not an inferred RNA product. A secondary same-kind gain
     * must match position and magnitude, because a larger gain need not mean greater damage.
     * Quiet values are retained; gray or mixed donor/acceptor profiles remain unresolved. */
    private static void compareProfiles(JsonNode target, JsonNode reference, String similarity) {
        require(target.path("profile").isObject() && reference.path("profile").isObject(), "PS1_SPLICE_PROFILE_INCOMPLETE");
        String main = "NATIVE_DONOR_LOSS".equals(target.path("event").asText()) ? "DL" : "AL";
        String gain = "DL".equals(main) ? "DG" : "AG";
        for (String kind : List.of("AG", "AL", "DG", "DL")) {
            var t = target.path("profile").path(kind); var r = reference.path("profile").path(kind);
            BigDecimal ts = PredictionCalibration.decimal(t.path("score").asText()), rs = PredictionCalibration.decimal(r.path("score").asText());
            if (!(ts != null && rs != null && ts.signum() >= 0 && rs.signum() >= 0
                    && ts.compareTo(BigDecimal.ONE) <= 0 && rs.compareTo(BigDecimal.ONE) <= 0)) {
                throw new IllegalArgumentException("PS1_SPLICE_PROFILE_INCOMPLETE");
            }
            if (kind.equals(main)) {
                require(ts.compareTo(new BigDecimal(target.path("score").asText())) == 0
                        && rs.compareTo(new BigDecimal(reference.path("score").asText())) == 0
                        && t.path("position").asLong() == target.path("position").asLong()
                        && r.path("position").asLong() == reference.path("position").asLong(), "PS1_SPLICE_PROFILE_INCOMPLETE");
                continue;
            }
            require(!(ts.compareTo(QUIET) > 0 && ts.compareTo(IMPACT) <= 0)
                    && !(rs.compareTo(QUIET) > 0 && rs.compareTo(IMPACT) <= 0), "PS1_SPLICE_PROFILE_GRAY_ZONE");
            boolean ta = ts.compareTo(IMPACT) > 0, ra = rs.compareTo(IMPACT) > 0;
            require(ta == ra, "PS1_SPLICE_PROFILE_EVENT_SET_MISMATCH");
            if (!ta) continue;
            require(kind.equals(gain), "PS1_SPLICE_MIXED_PROFILE_UNSUPPORTED");
            require(t.path("position").equals(r.path("position")), "PS1_SPLICE_PROFILE_POSITION_MISMATCH");
            boolean tolerant = "PUBLISHED_RELATIVE_TEN_PERCENT".equals(similarity);
            BigDecimal floor = tolerant ? rs.multiply(new BigDecimal("0.9")) : rs;
            BigDecimal ceiling = tolerant ? rs.multiply(new BigDecimal("1.1")) : rs;
            require(ts.compareTo(floor) >= 0 && ts.compareTo(ceiling) <= 0, "PS1_SPLICE_SECONDARY_SCORE_INCOMPATIBLE");
        }
    }

    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
