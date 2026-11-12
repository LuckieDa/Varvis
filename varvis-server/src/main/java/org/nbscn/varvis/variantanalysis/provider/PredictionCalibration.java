package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Exact decimal intervals. A printed table is not permission to round a raw score. */
public final class PredictionCalibration {
    public static final String STANDARD_ID = "am-standard-strength-20260918";
    public static final String THRESHOLD_SHA = "77ca28d340758ae1b3316403afd5e56e0f9b1be3e9d262902b9efe19afb69612";
    public static final String README_SHA = "edb3dd5e601014d0953b1ac55c0eefacaa79d6637f6a0df6d285043a06c106bc";
    private PredictionCalibration() { }

    /** Explicit offline successor. Does not change v1 printed-grid or application semantics. */
    public static ObjectNode loadStandard(ObjectMapper mapper, byte[] contract, String expectedSha,
            byte[] thresholds, byte[] readme) throws java.io.IOException {
        if (!sha256(contract).equals(expectedSha)) throw new IllegalArgumentException("CALIBRATION_CONTRACT_DIGEST_MISMATCH");
        ObjectNode source = alphaThresholds(mapper, thresholds, readme);
        JsonNode loaded = mapper.readTree(contract);
        if (!(loaded instanceof ObjectNode out)) throw new IllegalArgumentException("CALIBRATION_CONTRACT_INVALID");
        String issue = standardIssue(out);
        if (issue != null) throw new IllegalArgumentException(issue);
        if (!source.equals(out.path("sourceThresholds"))) throw new IllegalArgumentException("CALIBRATION_SOURCE_COLUMNS_MISMATCH");
        return out;
    }

    /** Paired tabs occur in the original header only; empty data cells must not shift columns. */
    public static ObjectNode alphaThresholds(ObjectMapper mapper, byte[] thresholds, byte[] readme) {
        if (!THRESHOLD_SHA.equals(sha256(thresholds)) || !README_SHA.equals(sha256(readme)))
            throw new IllegalArgumentException("CALIBRATION_SOURCE_DIGEST_MISMATCH");
        String[] lines = new String(thresholds, StandardCharsets.UTF_8).split("\\R");
        List<String> header = Arrays.stream(lines[0].split("\\t", -1)).filter(s -> !s.isEmpty()).toList();
        List<String> expected = List.of("Method", "PP3_VeryStrong", "PP3_Strong", "PP3_+3", "PP3_Moderate",
                "PP3_Supporting", "BP4_VeryStrong", "BP4_Strong", "BP4_-3", "BP4_Moderate", "BP4_Supporting");
        if (!expected.equals(header)) throw new IllegalArgumentException("CALIBRATION_SOURCE_HEADER_INVALID");
        ObjectNode row = null;
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split("\\t", -1);
            if (!"AlphaMissense".equals(cells[0])) continue;
            if (row != null || cells.length != header.size()) throw new IllegalArgumentException("CALIBRATION_SOURCE_ROW_INVALID");
            row = mapper.createObjectNode();
            for (int j = 1; j < cells.length; j++) {
                if ("NaN".equals(cells[j])) row.putNull(header.get(j));
                else if (decimal(cells[j]) != null) row.put(header.get(j), cells[j]);
                else throw new IllegalArgumentException("CALIBRATION_SOURCE_VALUE_INVALID");
            }
        }
        if (row == null) throw new IllegalArgumentException("CALIBRATION_SOURCE_ROW_MISSING");
        return row;
    }

    public static String standardIssue(JsonNode c) {
        if (!"prediction-standard-calibration-v1".equals(c.path("schemaVersion").asText())
                || !STANDARD_ID.equals(c.path("calibrationId").asText()) || !"1.0.0".equals(c.path("version").asText())
                || !"OFFLINE_ONLY".equals(c.path("activation").asText()) || !"UNIT_INTERVAL".equals(c.path("domain").asText())
                || !"ALPHAMISSENSE".equals(c.path("model").asText()) || !"am_pathogenicity".equals(c.path("scoreField").asText())
                || !c.path("fullSourcePointBandingVerified").isBoolean() || c.path("fullSourcePointBandingVerified").asBoolean()
                || !THRESHOLD_SHA.equals(c.at("/sources/thresholdSha256").asText())
                || !README_SHA.equals(c.at("/sources/readmeSha256").asText())
                || !"0.973".equals(c.at("/sourceThresholds/PP3_+3").asText())
                || !"0.070".equals(c.at("/sourceThresholds/BP4_-3").asText())
                || !"0.972".equals(c.at("/discrepancy/final2025Plus3Lower").asText())
                || !"UNRESOLVED".equals(c.at("/discrepancy/status").asText())) return "STANDARD_CALIBRATION_CONTRACT_INVALID";
        JsonNode bands = c.path("bands");
        if (!bands.isArray() || bands.size() != 6) return "STANDARD_CALIBRATION_BANDS_INVALID";
        String[] cuts = {"0", "0.099", "0.169", "0.792", "0.906", "0.990", "1"};
        String[] directions = {"BENIGN", "BENIGN", "UNINFORMATIVE", "HARMFUL", "HARMFUL", "HARMFUL"};
        String[] strengths = {"MODERATE", "SUPPORTING", "NONE", "SUPPORTING", "MODERATE", "STRONG"};
        for (int i = 0; i < 6; i++) {
            JsonNode b = bands.get(i);
            BigDecimal lo = decimal(b.path("lower").asText()), hi = decimal(b.path("upper").asText());
            if (lo == null || hi == null || lo.compareTo(new BigDecimal(cuts[i])) != 0
                    || hi.compareTo(new BigDecimal(cuts[i + 1])) != 0
                    || !b.path("lowerInclusive").isBoolean() || !b.path("upperInclusive").isBoolean()
                    || b.path("lowerInclusive").asBoolean() != (i == 0 || i >= 3)
                    || b.path("upperInclusive").asBoolean() != (i < 2 || i == 5)
                    || !directions[i].equals(b.path("direction").asText()) || !strengths[i].equals(b.path("calibratedStrength").asText())
                    || !b.path("sourcePointValue").isNull()) return "STANDARD_CALIBRATION_BANDS_INVALID";
        }
        return null;
    }

    /** Pure comparison only. Resource authorization belongs to the runtime overload. */
    static ObjectNode evaluateStandard(ObjectMapper mapper, JsonNode c, String rawScore) {
        var out = standardResult(mapper, c, rawScore);
        String issue = standardIssue(c);
        if (issue != null) return refused(out, issue);
        BigDecimal score = decimal(rawScore);
        if (score == null) return refused(out, rawScore == null || rawScore.isBlank() || ".".equals(rawScore)
                ? "MISSING_VALUE" : "CALIBRATION_SCORE_INVALID");
        return compareStrengthIntervals(c, score, out);
    }

    /** Common exact comparison for independently validated, standard-strength interval contracts. */
    static ObjectNode compareStrengthIntervals(JsonNode c, BigDecimal score, ObjectNode out) {
        for (JsonNode band : c.path("bands")) {
            int lo = score.compareTo(new BigDecimal(band.path("lower").asText()));
            int hi = score.compareTo(new BigDecimal(band.path("upper").asText()));
            if ((lo > 0 || lo == 0 && band.path("lowerInclusive").asBoolean())
                    && (hi < 0 || hi == 0 && band.path("upperInclusive").asBoolean())) {
                out.set("band", band.deepCopy());
                return out.put("status", "CALIBRATED").put("direction", band.path("direction").asText())
                        .put("calibratedStrength", band.path("calibratedStrength").asText())
                        .put("reasonCode", "UNINFORMATIVE".equals(band.path("direction").asText()) ? "SCORE_UNINFORMATIVE" : "CALIBRATED_INTERVAL");
            }
        }
        return refused(out, "STANDARD_CALIBRATION_BANDS_INVALID");
    }

    static ObjectNode standardResult(ObjectMapper mapper, JsonNode c, String rawScore) {
        var out = mapper.createObjectNode().put("schemaVersion", "prediction-standard-calibration-result-v1")
                .put("operation", "OFFLINE_CALIBRATION_VALIDATION").put("rawScore", rawScore)
                .put("calibrationId", c.path("calibrationId").asText()).put("calibrationVersion", c.path("version").asText())
                .put("calibratedStrength", "NONE").put("appliedStrength", "NONE").put("applicationStatus", "NOT_EVALUATED")
                .putNull("sourcePointValue").put("sourcePointStatus", "NOT_ASSIGNED_BY_STANDARD_STRENGTH_CONTRACT")
                .put("fullSourcePointBandingVerified", false).put("newAcquisition", 0).put("newTransport", 0);
        out.set("sources", c.path("sources").deepCopy()); out.set("discrepancy", c.path("discrepancy").deepCopy());
        return out;
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    public static String issue(JsonNode calibration) {
        if (!"prediction-calibration-v1".equals(calibration.path("schemaVersion").asText())
                || !"UNIT_INTERVAL".equals(calibration.path("domain").asText())
                || !calibration.path("bands").isArray() || calibration.path("bands").isEmpty()) return "CALIBRATION_CONTRACT_INVALID";
        BigDecimal previous = null; boolean previousInclusive = false;
        for (var band : calibration.path("bands")) {
            BigDecimal lower = decimal(band.path("lower").asText()), upper = decimal(band.path("upper").asText());
            if (lower == null || upper == null || lower.compareTo(upper) > 0
                    || !band.path("lowerInclusive").isBoolean() || !band.path("upperInclusive").isBoolean()
                    || lower.compareTo(upper) == 0 && !(band.path("lowerInclusive").asBoolean() && band.path("upperInclusive").asBoolean())
                    || !band.path("sourcePointValue").isIntegralNumber() || !band.path("sourcePointValue").canConvertToInt()) return "CALIBRATION_BAND_INVALID";
            if (previous != null && (lower.compareTo(previous) < 0 || lower.compareTo(previous) == 0
                    && previousInclusive && band.path("lowerInclusive").asBoolean())) return "CALIBRATION_BAND_OVERLAP";
            int points = band.path("sourcePointValue").asInt();
            if (points < -3 || points > 4 || !strength(points).equals(band.path("localAppliedStrength").asText()))
                return "CALIBRATION_LOCAL_STRENGTH_MAPPING_INVALID";
            previous = upper; previousInclusive = band.path("upperInclusive").asBoolean();
        }
        String semantics = calibration.path("comparisonSemantics").asText();
        if (!Set.of("DISPLAY_INTERVALS_REGISTERED", "VERIFIED_DECIMAL_INTERVALS").contains(semantics)) return "CALIBRATION_COMPARISON_SEMANTICS_INVALID";
        if ("VERIFIED_DECIMAL_INTERVALS".equals(semantics)
                && (!calibration.at("/precisionEvidence/sha256").asText().matches("[0-9a-f]{64}")
                || calibration.at("/precisionEvidence/sourceLocator").asText().isBlank())) return "CALIBRATION_PRECISION_EVIDENCE_MISSING";
        return null;
    }
    public static ObjectNode evaluate(ObjectMapper mapper, JsonNode calibration, String rawScore, boolean syntheticGrid) {
        var out = mapper.createObjectNode().put("schemaVersion", "prediction-calibration-result-v1")
                .put("syntheticGrid", syntheticGrid).put("rawScore", rawScore).put("appliedStrength", "NONE");
        out.set("calibration", calibration.deepCopy());
        String issue = issue(calibration);
        if (issue != null) return refused(out, issue);
        BigDecimal score = decimal(rawScore);
        if (score == null) return refused(out, rawScore == null || rawScore.isBlank() || ".".equals(rawScore) ? "MISSING_VALUE" : "CALIBRATION_SCORE_INVALID");
        out.put("exactScore", score.toPlainString());
        boolean executable = "VERIFIED_DECIMAL_INTERVALS".equals(calibration.path("comparisonSemantics").asText());
        // Synthetic tests may exercise the printed grid only; they never certify the real precision.
        if (!executable && (!syntheticGrid || score.stripTrailingZeros().scale() > 3)) return refused(out, "CALIBRATION_PRECISION_UNRESOLVED");
        for (var band : calibration.path("bands")) {
            BigDecimal lower = new BigDecimal(band.path("lower").asText()), upper = new BigDecimal(band.path("upper").asText());
            int l = score.compareTo(lower), u = score.compareTo(upper);
            if ((l > 0 || l == 0 && band.path("lowerInclusive").asBoolean())
                    && (u < 0 || u == 0 && band.path("upperInclusive").asBoolean())) {
                int points = band.path("sourcePointValue").asInt();
                out.set("band", band.deepCopy());
                return out.put("status", "CALIBRATED").put("direction", points > 0 ? "HARMFUL" : points < 0 ? "BENIGN" : "UNINFORMATIVE")
                        .put("sourcePointValue", points).put("sourceStrength", band.path("sourceStrength").asText())
                        .put("appliedStrength", strength(points)).put("reasonCode", points == 0 ? "SCORE_UNINFORMATIVE" : "CALIBRATED_INTERVAL");
            }
        }
        return refused(out, "CALIBRATION_PRECISION_UNRESOLVED");
    }
    static ObjectNode refused(ObjectNode out, String reason) { return out.put("status", "NOT_EVALUATED").put("reasonCode", reason); }
    public static BigDecimal decimal(String raw) {
        if (raw == null || raw.length() > 64 || !raw.matches("(?:0(?:\\.[0-9]+)?|1(?:\\.0+)?)")) return null;
        try { return new BigDecimal(raw); } catch (NumberFormatException invalid) { return null; }
    }
    private static String strength(int points) {
        return switch (Math.abs(points)) { case 0 -> "NONE"; case 1 -> "SUPPORTING"; case 2, 3 -> "MODERATE"; case 4 -> "STRONG"; default -> "INVALID"; };
    }
}
