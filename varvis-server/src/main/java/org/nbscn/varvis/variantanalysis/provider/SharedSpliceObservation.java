package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;

import java.util.ArrayList;
import java.util.List;

/** Immutable source capture for prediction and comparator consumers. Never grants calibration or RNA approval. */
public final class SharedSpliceObservation {
    public static final String SOURCE_SCHEMA = "shared-splice-observation-source-v1";
    private final ObjectMapper mapper;
    private final ObjectNode snapshot;

    private SharedSpliceObservation(ObjectMapper mapper, ObjectNode snapshot) {
        this.mapper = mapper;
        this.snapshot = snapshot.deepCopy();
    }

    /** The source record carries queryAllele, resource, records and the four native process states. */
    public static SharedSpliceObservation capture(ObjectMapper mapper, JsonNode queryAllele,
            SourceRef reference, List<ExplicitContextSourceLedger> trustedSources) {
        var matches = new ArrayList<JsonNode>();
        for (var source : trustedSources) try { matches.add(source.resolve(reference)); } catch (IllegalArgumentException ignored) { }
        require(matches.size() == 1, "SPLICE_SOURCE_REFERENCE_AMBIGUOUS_OR_MISSING");
        JsonNode raw = matches.get(0);
        require(SOURCE_SCHEMA.equals(raw.path("schemaVersion").asText()), "SPLICE_SOURCE_SCHEMA_INVALID");
        require(queryAllele.isObject() && queryAllele.equals(raw.path("queryAllele"))
                && "GRCh38".equals(queryAllele.path("assembly").asText())
                && queryAllele.path("position").isIntegralNumber() && queryAllele.path("position").canConvertToInt()
                && queryAllele.path("position").asInt() > 0
                && !queryAllele.path("contig").asText().isBlank()
                && queryAllele.path("reference").asText().matches("[ACGT]{1,50}")
                && queryAllele.path("alternate").asText().matches("[ACGT]{1,50}")
                && !queryAllele.path("reference").equals(queryAllele.path("alternate")), "SPLICE_QUERY_IDENTITY_MISMATCH");
        require(raw.path("resource").isObject() && raw.path("records").isArray(), "SPLICE_SOURCE_FIELDS_MISSING");
        for (String field : List.of("resourceExecution", "resourceAvailability", "resourceApplicability", "queryOutcome"))
            require(raw.path(field).isObject() && !raw.path(field).path("status").asText().isBlank(), "SPLICE_SOURCE_FIELDS_MISSING");
        var snapshot = mapper.createObjectNode().put("schemaVersion", "shared-splice-observation-v1")
                .put("applicationStatus", "NOT_EVALUATED").put("rnaApproval", "NOT_ESTABLISHED");
        snapshot.set("sourceReference", mapper.valueToTree(reference));
        snapshot.set("sourceRecord", raw.deepCopy());
        snapshot.put("sourceRecordCanonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(raw));
        return new SharedSpliceObservation(mapper, snapshot);
    }

    public ObjectNode snapshot() { return snapshot.deepCopy(); }

    /** Lossless original decimal strings and signed offsets; parsing is distinct from compatibility/application. */
    public ObjectNode events(int index) {
        var raw = snapshot.path("sourceRecord");
        require(index >= 0 && index < raw.path("records").size(), "SPLICE_RECORD_INDEX_INVALID");
        if (!"SUCCEEDED".equals(raw.at("/resourceExecution/status").asText())
                || !"AVAILABLE".equals(raw.at("/resourceAvailability/status").asText())
                || !"APPLICABLE".equals(raw.at("/resourceApplicability/status").asText())
                || !"HIT".equals(raw.at("/queryOutcome/status").asText()))
            return mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_OBSERVATION_NOT_AVAILABLE");
        var record = raw.path("records").get(index);
        if (!raw.at("/queryAllele/alternate").equals(record.path("allele")))
            return mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_RECORD_ALLELE_MISMATCH");
        for (String kind : List.of("AG", "AL", "DG", "DL"))
            if (!record.path("DS_" + kind).isTextual())
                return mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_SCORE_ORIGINAL_PRECISION_REQUIRED");
        return PredictionObservationAssociation.spliceEvents(mapper, record, raw.at("/queryAllele/position").asLong());
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
