package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Private source witness, never part of AnalysisVcfCall or candidate identity. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PublishedSampleFilter(String schemaVersion, String sampleId, String assembly,
        EngineExecutionResult.MatchedRecord record, State state, String rawValue) {
    public static final String VERSION = "selected-sample-ft-v1";
    public enum State { PASS, FILTERED, FORMAT_ABSENT, DOT, EMPTY, VALUE_MISSING, SAMPLE_MISSING, RAW_UNAVAILABLE, MALFORMED }
    public PublishedSampleFilter {
        if (!VERSION.equals(schemaVersion) || sampleId == null || sampleId.isBlank()
                || !Set.of("GRCh37", "GRCh38").contains(assembly == null ? "" : assembly)
                || record == null || state == null || !validValue(state, rawValue))
            throw new IllegalArgumentException("EXECUTION_SAMPLE_FILTER_INVALID");
    }
    private static boolean validValue(State state, String raw) {
        return switch (state) {
            case PASS -> "PASS".equals(raw);
            case DOT -> ".".equals(raw);
            case EMPTY -> "".equals(raw);
            case FILTERED -> raw != null && !raw.isBlank() && raw.length() <= 4096 && !Set.of("PASS", ".").contains(raw);
            default -> raw == null;
        };
    }
    /** Capture the original row before HTSJDK decodes genotypes: decoded filters collapse PASS and '.'.
     * The string belongs to the same iterator and digest stream; no second VCF read occurs.
     * Only the selected sample's FT is retained. BCF/eager input without raw text stays unproven.
     */
    static PublishedSampleFilter capture(String text, int sampleOffset, String sample, String assembly,
                                         EngineExecutionResult.MatchedRecord record) {
        State state; String raw = null;
        if (text == null) state = State.RAW_UNAVAILABLE;
        else {
            int firstTab = text.indexOf('\t');
            String format = firstTab < 0 ? text : text.substring(0, firstTab);
            String[] keys = format.split(":", -1); int ft = -1; boolean duplicate = false;
            for (int i = 0; i < keys.length; i++) if ("FT".equals(keys[i])) { if (ft >= 0) duplicate = true; ft = i; }
            if (duplicate) state = State.MALFORMED;
            else if (ft < 0) state = State.FORMAT_ABSENT;
            else {
                int start = firstTab;
                for (int i = 0; i < sampleOffset && start >= 0; i++) start = text.indexOf('\t', start + 1);
                if (start < 0) state = State.SAMPLE_MISSING;
                else {
                    int end = text.indexOf('\t', start + 1);
                    String column = text.substring(start + 1, end < 0 ? text.length() : end);
                    String[] values = column.split(":", -1);
                    if (column.isEmpty() || ".".equals(column)) state = State.SAMPLE_MISSING;
                    else if (values.length > keys.length) state = State.MALFORMED;
                    else if (ft >= values.length) state = State.VALUE_MISSING;
                    else {
                        raw = values[ft];
                        if (raw.length() > 4096 || (!raw.isEmpty() && raw.isBlank())) { state = State.MALFORMED; raw = null; }
                        else state = "PASS".equals(raw) ? State.PASS : ".".equals(raw) ? State.DOT : raw.isEmpty() ? State.EMPTY : State.FILTERED;
                    }
                }
            }
        }
        return new PublishedSampleFilter(VERSION, sample, assembly, record, state, raw);
    }
    /** Strict optional-extension recovery; parent snapshot binds analysis, input digest and sample. */
    static PublishedSampleFilter recover(JsonNode node, String sample, String assembly, EngineExecutionResult.MatchedRecord record) {
        try {
            if (!node.isObject() || node.size() != 6) throw new IllegalArgumentException();
            for (String field : List.of("schemaVersion", "sampleId", "assembly", "state")) if (!node.path(field).isTextual()) throw new IllegalArgumentException();
            if (!node.has("rawValue") || !(node.path("rawValue").isNull() || node.path("rawValue").isTextual())) throw new IllegalArgumentException();
            JsonNode r = node.path("record");
            if (!r.isObject() || r.size() != 6 || !r.path("contig").isTextual() || !r.path("reference").isTextual()
                    || !r.path("alternates").isArray()) throw new IllegalArgumentException();
            for (String field : List.of("position", "selectedAltIndex", "recordOrdinal"))
                if (!r.path(field).isIntegralNumber() || !r.path(field).canConvertToInt() || r.path(field).intValue() < 1) throw new IllegalArgumentException();
            var alts = new ArrayList<String>();
            for (var alt : r.path("alternates")) { if (!alt.isTextual()) throw new IllegalArgumentException(); alts.add(alt.textValue()); }
            var actualRecord = new EngineExecutionResult.MatchedRecord(r.path("contig").textValue(), r.path("position").intValue(), r.path("reference").textValue(),
                    alts, r.path("selectedAltIndex").intValue(), r.path("recordOrdinal").intValue());
            var witness = new PublishedSampleFilter(node.path("schemaVersion").textValue(), node.path("sampleId").textValue(), node.path("assembly").textValue(),
                    actualRecord, State.valueOf(node.path("state").textValue()), node.path("rawValue").isNull() ? null : node.path("rawValue").textValue());
            if (!Objects.equals(sample, witness.sampleId()) || !Objects.equals(assembly, witness.assembly()) || !Objects.equals(record, actualRecord))
                throw new IllegalArgumentException("EXECUTION_SAMPLE_FILTER_BINDING_CONFLICT");
            return witness;
        } catch (IllegalArgumentException invalid) {
            if ("EXECUTION_SAMPLE_FILTER_BINDING_CONFLICT".equals(invalid.getMessage())) throw invalid;
            throw new IllegalArgumentException("EXECUTION_SAMPLE_FILTER_INVALID");
        }
    }
}
