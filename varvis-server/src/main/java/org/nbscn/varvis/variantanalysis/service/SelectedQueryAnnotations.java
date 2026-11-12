package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.util.*;

/** One request-scoped annotation batch for changed, validated query alleles only.
 * Reuses the existing VEP provider; never normalizes, changes ranking or chooses a disease. */
public final class SelectedQueryAnnotations {
    @FunctionalInterface
    public interface Provider {
        Map<NormalizedVariantKey, List<TranscriptConsequence>> annotate(
                List<VariantInterpretation> candidates, AnalysisContext context);
    }

    private final ObjectMapper mapper;
    private final Map<NormalizedVariantKey, ExplicitContextSourceLedger> records = new LinkedHashMap<>();

    SelectedQueryAnnotations(ObjectMapper mapper) {
        // Preserve nanoseconds through JSON trees/receipts without floating-point timestamp conversion.
        this.mapper = mapper.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    void prepare(List<VariantInterpretation> candidates, AnalysisContext context, String dataClass, Provider provider) {
        var unique = new LinkedHashMap<NormalizedVariantKey, VariantInterpretation>();
        for (var candidate : candidates) unique.putIfAbsent(candidate.getObservation().getKey(), candidate);
        if (unique.isEmpty()) return;
        Map<NormalizedVariantKey, List<TranscriptConsequence>> response = Map.of();
        String failure = provider == null ? "QUERY_ANNOTATION_NOT_CONFIGURED" : null;
        if (provider != null) try {
            response = provider.annotate(List.copyOf(unique.values()), context);
            if (response == null) response = Map.of();
        } catch (RuntimeException unavailable) { failure = "QUERY_ANNOTATION_FAILED"; }
        for (var key : unique.keySet()) {
            var annotations = response.get(key);
            var record = mapper.createObjectNode().put("method", "EXACT_QUERY_ALLELE_VEP_BATCH_V1");
            record.set("queryAllele", mapper.valueToTree(key));
            record.set("annotations", mapper.valueToTree(annotations == null ? List.of() : annotations));
            String reason = failure;
            if (reason == null && (annotations == null || annotations.isEmpty())) {
                reason = "QUERY_ANNOTATION_NO_RECORD";
            } else if (reason == null && annotations != null
                    && annotations.stream().noneMatch(t -> t != null && "COMPLETED".equals(t.getStatus()))) {
                var status = annotations.stream().filter(Objects::nonNull).map(mappedItem -> mappedItem.getStatus())
                        .filter(Objects::nonNull).distinct().toList();
                reason = "QUERY_ANNOTATION_" + (status.size() == 1 && Set.of("DISABLED", "UNAVAILABLE", "TIMEOUT", "FAILED", "NO_RECORD", "ASSEMBLY_UNSUPPORTED").contains(status.get(0))
                        ? status.get(0) : "FAILED");
            }
            record.put("reasonCode", reason);
            var envelope = mapper.createObjectNode().put("source", "SELECTED_QUERY_VEP_ANNOTATION")
                    .put("version", "1.0.0").put("dataClass", dataClass);
            envelope.putObject("records").set("annotation", record);
            try { records.put(key, ExplicitContextSourceLedger.create(mapper, envelope)); }
            catch (java.io.IOException invalid) { throw new IllegalArgumentException("QUERY_ANNOTATION_RECEIPT_INVALID", invalid); }
        }
    }

    List<ExplicitContextSourceLedger> sources() { return List.copyOf(records.values()); }

    ExplicitContextSourceLedger.SourceRef reference(NormalizedVariantKey key) {
        var ledger = records.get(key);
        return ledger == null ? null : ledger.reference("annotation");
    }

    JsonNode annotations(NormalizedVariantKey key, ObjectNode context, JsonNode originalAnnotations) {
        var ledger = records.get(key);
        if (ledger == null) throw new IllegalArgumentException("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED");
        var record = ledger.resolve(ledger.reference("annotation"));
        if (!record.path("reasonCode").isNull()) throw new IllegalArgumentException(record.path("reasonCode").asText());
        String transcript = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        var originals = new ArrayList<JsonNode>();
        for (var a : originalAnnotations) if (transcript.equals(versionedId(a))) originals.add(a);
        if (originals.size() != 1) throw new IllegalArgumentException("SELECTED_TRANSCRIPT_ANNOTATION_UNAVAILABLE");
        var original = originals.get(0);
        var matches = new ArrayList<JsonNode>();
        for (var a : record.path("annotations")) if (transcript.equals(versionedId(a))) matches.add(a);
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty()
                ? "QUERY_ANNOTATION_TRANSCRIPT_MISSING" : "QUERY_ANNOTATION_TRANSCRIPT_AMBIGUOUS");
        var match = matches.get(0);
        // The existing offline provider labels itself VEP_CACHED; ranking enrichment labels
        // the same provider family VEP. Preserve both original labels in the receipt.
        if (!"COMPLETED".equals(match.path("status").asText())
                || !Set.of("VEP", "VEP_CACHED").contains(match.path("source").asText())
                || !Set.of("VEP", "VEP_CACHED").contains(original.path("source").asText())
                || !match.path("version").asText().equals(context.at("/transcript/annotationSourceVersion").asText())
                || original.path("geneSymbol").asText().isBlank() || !match.path("geneSymbol").equals(original.path("geneSymbol")))
            throw new IllegalArgumentException("QUERY_ANNOTATION_IDENTITY_MISMATCH");
        // Return only the selected transcript: no other gene/transcript can enter a consumer by accident.
        return mapper.createArrayNode().add(match.deepCopy());
    }

    private String versionedId(JsonNode annotation) {
        try {
            return mapper.readerFor(TranscriptConsequence.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .<TranscriptConsequence>readValue(annotation).getVersionedTranscriptId();
        } catch (java.io.IOException malformed) { return null; }
    }
}
