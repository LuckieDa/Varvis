package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.require;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.positive;

/** Consumes the pinned offline combined-protein-region-facts-v1 export. Absence never qualifies a negative. */
public final class CombinedProteinRegionSupply {
    public static final String VERSION = "1.1.0";
    public static final String BP3_PROJECTION_VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final Path file;
    private final String pin;
    public CombinedProteinRegionSupply(ObjectMapper mapper, Path file, String sha256) {
        this.mapper = UniProtProteinRegionSupply.strictMapper(mapper); this.file = file; this.pin = sha256;
        require(pin.matches("[0-9a-f]{64}"), "PM4_COMBINED_PIN_REQUIRED");
    }
    public ObjectNode identity() { return mapper.createObjectNode().put("version", VERSION).put("sha256", pin); }
    ObjectNode resolve(ObjectNode annotations) {
        return resolve(annotations, false);
    }
    ObjectNode resolveForBp3(ObjectNode annotations) { return resolve(annotations, true); }
    private ObjectNode resolve(ObjectNode annotations, boolean bp3) {
        var out = mapper.createObjectNode().put("status", "UNAVAILABLE").put("nonRepeatQualified", false).put("sha256", pin);
        var overlaps = out.putArray("repeatOverlaps");
        var functional = mapper.createArrayNode();
        var contexts = mapper.createArrayNode();
        if (bp3) { out.put("bp3ProjectionVersion", BP3_PROJECTION_VERSION); out.set("functionalLeads", functional); out.set("entryContexts", contexts); }
        try {
            byte[] bytes = UniProtProteinRegionSupply.readBytes(file, 32_000_000);
            require(pin.equals(CodingTranscriptMaterialSupply.sha(bytes)), "PM4_COMBINED_BYTES_CHANGED");
            var doc = mapper.readTree(bytes);
            if (!(doc != null && doc.path("results").isArray() && doc.path("results").size() <= 10000)) {
                throw new IllegalArgumentException("PM4_COMBINED_DOCUMENT_INVALID");
            }
            com.fasterxml.jackson.databind.JsonNode facts = null;
            for (var row : doc.path("results")) if (row.path("accession").equals(annotations.at("/source/accession"))) {
                require(facts == null && "FACTS_READY".equals(row.path("status").asText()), "PM4_COMBINED_SOURCE_AMBIGUOUS_OR_UNREADY");
                facts = row.path("facts");
            }
            require(facts != null, "PM4_COMBINED_ACCESSION_NOT_IN_SNAPSHOT");
            var f = java.util.Objects.requireNonNull(facts);
            require("combined-protein-region-facts-v1".equals(f.path("schemaVersion").asText())
                    && "ONE_BASED_CLOSED".equals(f.path("coordinateSystem").asText())
                    && f.path("accession").equals(annotations.at("/source/accession"))
                    && f.path("sequenceSha256").equals(annotations.path("proteinSequenceSha256WithoutStop"))
                    && f.path("length").asInt(-1) == annotations.path("proteinLength").asInt(-2)
                    && f.at("/sources/pins/uniprot/sha256").equals(annotations.at("/source/rawSha256"))
                    && f.path("regions").isArray() && f.path("unplaced").isArray(), "PM4_COMBINED_IDENTITY_INVALID");
            int start = annotations.at("/queryRange/start").asInt(), end = annotations.at("/queryRange/end").asInt();
            if (bp3) require(f.path("regions").size() <= 25000, "BP3_COMBINED_REGION_LIMIT");
            for (var region : f.path("regions")) {
                // UniProt was independently parsed by the exact-model supply. Add only native InterPro repeats.
                if (!"InterPro".equals(region.path("source").asText())) continue;
                require(region.path("kind").equals(region.at("/metadata/type")), "PM4_COMBINED_KIND_MISMATCH");
                boolean repeat = "repeat".equals(region.path("kind").asText());
                if (!repeat && !bp3) continue;
                require(!region.path("kind").asText().isBlank()
                        && (repeat ? "REPEAT" : "domain".equals(region.path("kind").asText()) ? "DOMAIN_LEAD" : "PROTEIN_CONTEXT")
                        .equals(region.path("category").asText()) && region.path("entry").asText().matches("IPR[0-9]+")
                        && region.path("entry").equals(region.at("/metadata/accession"))
                        && region.path("fragments").isArray() && !region.path("fragments").isEmpty()
                        && region.path("fragments").size() == region.at("/raw/fragments").size(), "PM4_COMBINED_REPEAT_INVALID");
                if (bp3) require(region.path("fragments").size() <= 10000
                        && region.path("locator").asText().matches("/results/[0-9]+/proteins/0/entry_protein_locations/[0-9]+"),
                        "BP3_COMBINED_SOURCE_LOCATOR_INVALID");
                int i = 0;
                for (var fragment : region.path("fragments")) {
                    var raw = region.at("/raw/fragments/" + i++);
                    require(positive(fragment.path("start")) && positive(fragment.path("end"))
                            && fragment.path("start").asInt() <= fragment.path("end").asInt()
                            && fragment.path("end").asInt() <= f.path("length").asInt()
                            && fragment.path("start").equals(raw.path("start")) && fragment.path("end").equals(raw.path("end"))
                            && fragment.path("discontinuity").equals(raw.path("dc-status")), "PM4_COMBINED_COORDINATES_INVALID");
                    if (bp3) require(java.util.Set.of("CONTINUOUS", "N_TERMINAL_DISC", "C_TERMINAL_DISC", "NC_TERMINAL_DISC")
                            .contains(raw.path("dc-status").asText()), "BP3_COMBINED_DISCONTINUITY_INVALID");
                    if (fragment.path("start").asInt() <= end && fragment.path("end").asInt() >= start) {
                        // Family association alone is not a local functional site. Preserve it separately.
                        boolean contextOnly = java.util.Set.of("family", "homologous_superfamily").contains(region.path("kind").asText());
                        var hit = (repeat ? overlaps : contextOnly ? contexts : functional).addObject().put("source", "InterPro").put("entry", region.path("entry").asText())
                                .put("locator", region.path("locator").asText()).put("start", fragment.path("start").asInt())
                                .put("end", fragment.path("end").asInt());
                        hit.set("raw", raw.deepCopy());
                        if (!repeat) hit.put("kind", region.path("kind").asText());
                    }
                }
            }
            out.set("sources", f.path("sources").deepCopy());
            var complete = f.path("sourceCompleteness");
            boolean ready = java.util.Set.of("1.1.0", InterProProteinRegionPreparation.VERSION).contains(f.path("adapterVersion").asText())
                    && "UNIPROT_FEATURES_AND_INTEGRATED_INTERPRO_ENTRIES_ONLY".equals(f.path("sourceScope").asText())
                    && complete.path("uniprotFeaturesComplete").isBoolean() && complete.path("uniprotFeaturesComplete").asBoolean()
                    && complete.path("interproEntriesComplete").isBoolean() && complete.path("interproEntriesComplete").asBoolean()
                    && complete.path("exactFullSequenceMatch").isBoolean() && complete.path("exactFullSequenceMatch").asBoolean()
                    && complete.path("uniprotFeatureCount").isIntegralNumber()
                    && complete.path("uniprotFeatureCount").equals(annotations.at("/featureSummary/sourceFeatureCount"))
                    && complete.path("interproEntryCount").isIntegralNumber() && complete.path("interproEntryCount").asInt(-1) >= 0
                    && complete.path("interproEntryCount").asInt() <= 200
                    && f.path("context").isArray() && complete.path("interproEntryCount").asInt() == f.path("context").size()
                    && f.at("/sources/uniprotRelease").isTextual()
                    && f.at("/sources/uniprotRelease").equals(annotations.at("/source/release"))
                    && !f.at("/sources/interproRelease").asText().isBlank() && !f.at("/sources/interproMinor").asText().isBlank();
            for (String source : java.util.List.of("root", "rootReceipt", "protein", "proteinReceipt", "entries", "entriesReceipt", "uniprot", "uniprotAcquisition"))
                ready &= f.at("/sources/pins/" + source + "/sha256").asText().matches("[0-9a-f]{64}");
            out.put("operationalSourceComplete", ready); out.set("sourceCompleteness", complete.deepCopy());
            return out.put("status", "READY").put("unplacedCount", f.path("unplaced").size());
        } catch (IllegalArgumentException bad) { return out.put("reasonCode", bad.getMessage()); }
        catch (IOException bad) { return out.put("reasonCode", "PM4_COMBINED_IO_OR_JSON_INVALID"); }
    }
}
