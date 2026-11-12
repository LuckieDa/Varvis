package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import java.util.List;

/** Neutral, sealed resource facts. Neither a PM2 result nor a disease-context evaluation. */
public final class PopulationObservation {
    public static final String VERSION = "population-observation-v1";
    private PopulationObservation() { }

    public static ObjectNode acquire(ObjectMapper mapper, VariantEvidenceProvider provider, VariantInterpretation candidate,
            AnalysisContext analysis, GnomadPopulationInputProfile profile, String sourceSnapshot, String dataClass) {
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper));
        String[] raw = {""};
        var transport = mapper.createObjectNode();
        ObjectNode carrier = wiring.queryNeutralPopulationCarrier(provider, candidate, analysis,
                wiring.bind(profile.getDocument(), profile.getPopulationResourceId()), value -> raw[0] = value,
                details -> {
                    for (String key : List.of("querySource", "resourceUri", "resourceIndexUri", "sourceDataset", "gnomadVersion"))
                        if (details.get(key) instanceof String text) transport.put(key, text);
                });
        if (provider instanceof GnomadTabixProvider tabix) transport.set("diagnostics", mapper.valueToTree(tabix.queryDiagnostics()));
        return withTransport(mapper, seal(mapper, carrier, raw[0], profile, sourceSnapshot, dataClass, "NEW_PROVIDER_EXECUTION"), transport);
    }

    /** Call only after the original store/source identity was recovered and verified. */
    public static ObjectNode restore(ObjectMapper mapper, JsonNode original, String sourceSnapshot, String dataClass) {
        return restore(mapper, original, sourceSnapshot, dataClass, GnomadPopulationInputProfile.loadBusinessPm2Supporting(),
                GnomadPopulationInputProfile.loadExecutableV2());
    }

    /** Explicit profiles; resource and field meaning must match before RAW reparsing can succeed. */
    public static ObjectNode restore(ObjectMapper mapper, JsonNode original, String sourceSnapshot, String dataClass,
            GnomadPopulationInputProfile oldProfile, GnomadPopulationInputProfile targetProfile) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var reparsed = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).reparsePopulationObservation(
                original.path("context"), original, sourceSnapshot, oldProfile, targetProfile);
        if (!dataClass.equals(original.at("/context/dataClass").asText())) throw new IllegalArgumentException("POPULATION_DATA_CLASS_CONFLICT");
        return seal(mapper, reparsed.carrier(), original.path("rawPopulationRecord").asText(""), targetProfile,
                sourceSnapshot, dataClass, "REPARSED_VERIFIED_ARCHIVED_OBSERVATION");
    }

    /** Existing neutral observations (including v3 consumers); check trusted source and explicit profile before reuse. */
    public static ObjectNode restoreSealed(ObjectMapper mapper, JsonNode original, JsonNode context, String sourceSnapshot,
            GnomadPopulationInputProfile profile) {
        verify(mapper, original, context, sourceSnapshot, profile);
        return ((ObjectNode) original).deepCopy();
    }

    static ObjectNode seal(ObjectMapper mapper, ObjectNode carrier, String raw, GnomadPopulationInputProfile profile,
            String sourceSnapshot, String dataClass, String origin) {
        if (sourceSnapshot == null || !sourceSnapshot.matches("[0-9a-f]{64}")
                || !List.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(dataClass))
            throw new IllegalArgumentException("POPULATION_SOURCE_IDENTITY_REQUIRED");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        new RuntimeEvidenceV2ProviderWiring(mapper, adapter).verifyPopulationRecord(carrier, carrier.path("queryAllele"), raw, profile);
        var value = mapper.createObjectNode().put("schemaVersion", VERSION).put("origin", origin)
                .put("sourceSnapshot", sourceSnapshot).put("dataClass", dataClass)
                .put("profileSha256", profile.getProfileSha256()).put("rawRecord", raw)
                .put("rawRecordSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(raw))
                .put("observedAt", carrier.path("producedAt").asText());
        value.set("resourceContract", profile.getDocument().path("resources").get(0));
        value.set("headerContract", profile.isLocalBa1() ? profile.getDocument().path("fieldContract")
                : Bs1NativeFilterSupplement.contract(mapper).path("jointHeader"));
        value.set("queryAllele", carrier.path("queryAllele").deepCopy()); value.set("carrier", carrier.deepCopy());
        value.put("outputCompleteness", "SUCCEEDED".equals(carrier.at("/resourceExecution/status").asText()) ? "VERIFIED" : "NOT_ESTABLISHED");
        value.put("observationId", "population-observation-sha256-" + adapter.canonicalSha256(value));
        return value;
    }

    static ObjectNode withTransport(ObjectMapper mapper, ObjectNode observation, JsonNode transport) {
        observation.remove("observationId"); observation.set("transport", transport.deepCopy());
        observation.put("observationId", "population-observation-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(observation));
        return observation;
    }

    public static void verify(ObjectMapper mapper, JsonNode value, JsonNode context, String sourceSnapshot) {
        verify(mapper, value, context, sourceSnapshot, GnomadPopulationInputProfile.loadExecutableV2());
    }
    public static void verify(ObjectMapper mapper, JsonNode value, JsonNode context, String sourceSnapshot, GnomadPopulationInputProfile profile) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        adapter.validateVersionedContext(context);
        if (value == null || !value.isObject() || !VERSION.equals(value.path("schemaVersion").asText())
                || !sourceSnapshot.equals(value.path("sourceSnapshot").asText())
                || !context.path("dataClass").equals(value.path("dataClass"))
                || !context.at("/variant/evidenceQueryAllele").equals(value.path("queryAllele")))
            throw new IllegalArgumentException("POPULATION_OBSERVATION_BINDING_INVALID");
        var carrier = value.path("carrier");
        if (!(carrier instanceof ObjectNode object)) throw new IllegalArgumentException("POPULATION_CARRIER_REQUIRED");
        ObjectNode recomputed = seal(mapper, object, value.path("rawRecord").asText(), profile,
                sourceSnapshot, value.path("dataClass").asText(), value.path("origin").asText());
        if (value.path("transport").isObject()) withTransport(mapper, recomputed, value.path("transport"));
        if (!recomputed.equals(value)) throw new IllegalArgumentException("POPULATION_OBSERVATION_INTEGRITY_INVALID");
    }
}
