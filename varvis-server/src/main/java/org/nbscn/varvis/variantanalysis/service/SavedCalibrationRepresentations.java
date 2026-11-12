package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Pure representation check shared by claim-owned execution and read-only preflight. No I/O. */
public final class SavedCalibrationRepresentations {
    private SavedCalibrationRepresentations() { }
    public static ObjectNode contract(ObjectMapper mapper) {
        return mapper.createObjectNode().put("contractId", "saved-calibration-candidate-representations")
                .put("version", "1.0.0").put("view", "CANDIDATES").put("filter", "EMPTY")
                .put("offset", 0).put("limit", 2).put("summarySource", "JdbcVariantAnalysisJobRepository.findInterpretationSummaries")
                .put("fullSource", "AcmgExecutionSources.recover");
    }
    public static final class Failure extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int candidateIndex;
        private final String fieldPath;
        Failure(String reason, int index, String path) {
            super(reason); candidateIndex = index;
            fieldPath = path.matches("[A-Za-z0-9_/-]{1,160}") ? path : "/";
        }
        public int candidateIndex() { return candidateIndex; }
        public String fieldPath() { return fieldPath; }
    }
    private static void check(boolean valid, String reason, int index, String path) {
        if (!valid) throw new Failure(reason, index, path);
    }
    /** publishedRows are the fixed source's candidate wrappers, already checked against full DB payloads. */
    public static ObjectNode verify(ObjectMapper mapper, JsonNode archived, JsonNode current,
            JsonNode publishedRows, List<String> fixedOrder) {
        check(fixedOrder.size() == 2 && new HashSet<>(fixedOrder).size() == 2,
                "CALIBRATION_REPRESENTATION_COUNT_OR_ORDER", -1, "/candidateIds");
        for (var rows : List.of(archived, current, publishedRows)) {
            check(rows.isArray() && rows.size() == fixedOrder.size(), "CALIBRATION_REPRESENTATION_COUNT_OR_ORDER", -1, "/candidates");
            for (int i = 0; i < fixedOrder.size(); i++)
                check(rows.get(i).isObject() && rows.get(i).path("candidateId").isTextual()
                        && fixedOrder.get(i).equals(rows.get(i).path("candidateId").textValue()),
                        "CALIBRATION_REPRESENTATION_COUNT_OR_ORDER", i, "/candidateId");
        }
        for (int i = 0; i < fixedOrder.size(); i++) {
            JsonNode summary = current.get(i), full = publishedRows.get(i).path("published");
            check(full.isObject(), "CALIBRATION_REPRESENTATION_IDENTITY_INVALID", i, "/published");
            String mismatch = firstDifference(archived.get(i), summary, "");
            check(mismatch == null, "CALIBRATION_SUMMARY_VIEW_MISMATCH", i, mismatch == null ? "/" : mismatch);
            for (String field : List.of("candidateId", "variantId", "canonicalVariantId", "priorityTier", "tier"))
                same(summary, full, "/" + field, "TEXT", i);
            for (String field : List.of("candidateIdentity", "analysisVcfCall", "exomiserCandidateFeatures"))
                same(summary, full, "/" + field, "OBJECT", i);
            same(summary, full, "/transcriptAnnotations", "ARRAY", i);
            for (String field : List.of("rankingScore", "totalScore")) same(summary, full, "/" + field, "NUMBER", i);
            for (var row : List.of(summary, full)) {
                check(SavedCalibrationBusiness.semanticEquals(row.path("rankingScore"), row.path("totalScore"))
                        && row.path("priorityTier").equals(row.path("tier")), "CALIBRATION_REPRESENTATION_IDENTITY_MISMATCH", i, "/rankingScore");
            }
            for (String field : List.of("genomeAssembly", "chromosome", "reference", "alternate"))
                same(summary, full, "/observation/key/" + field, "TEXT", i);
            same(summary, full, "/observation/key/position", "POSITION", i);
            same(summary, full, "/observation/sampleId", "TEXT", i);
            same(summary, full, "/observation/variantType", "TEXT", i);
            for (String field : List.of("genotype", "filterStatus")) same(summary, full, "/observation/" + field, "NULLABLE_TEXT", i);
        }
        var receipt = contract(mapper).put("status", "VERIFIED").put("count", fixedOrder.size());
        receipt.set("candidateIds", mapper.valueToTree(fixedOrder));
        return receipt;
    }
    private static void same(JsonNode summary, JsonNode full, String path, String type, int index) {
        JsonNode a = summary.at(path), b = full.at(path);
        check(validType(a, type) && validType(b, type), "CALIBRATION_REPRESENTATION_IDENTITY_INVALID", index, path);
        check(SavedCalibrationBusiness.semanticEquals(a, b), "CALIBRATION_REPRESENTATION_IDENTITY_MISMATCH", index, path);
    }
    private static boolean validType(JsonNode value, String type) {
        return switch (type) {
            case "TEXT" -> value.isTextual() && !value.textValue().isBlank();
            case "NULLABLE_TEXT" -> value.isNull() || value.isTextual();
            case "OBJECT" -> value.isObject() && !value.isEmpty();
            case "ARRAY" -> value.isArray() && !value.isEmpty();
            case "NUMBER" -> value.isNumber();
            case "POSITION" -> value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0;
            default -> false;
        };
    }
    private static String firstDifference(JsonNode a, JsonNode b, String path) {
        if (SavedCalibrationBusiness.semanticEquals(a, b)) return null;
        if (a.isObject() && b.isObject()) {
            var keys = new TreeSet<String>(); a.fieldNames().forEachRemaining(keys::add); b.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                String next = path + "/" + key;
                if (!a.has(key) || !b.has(key)) return next;
                String diff = firstDifference(a.path(key), b.path(key), next); if (diff != null) return diff;
            }
        }
        if (a.isArray() && b.isArray() && a.size() == b.size())
            for (int i = 0; i < a.size(); i++) { String diff = firstDifference(a.get(i), b.get(i), path + "/" + i); if (diff != null) return diff; }
        return path.isEmpty() ? "/" : path;
    }
}
