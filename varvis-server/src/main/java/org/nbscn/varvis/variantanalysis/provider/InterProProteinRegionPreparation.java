package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.require;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.positive;

/** Native single-page adapter, the same scope as combined_protein_region_facts.py 1.1.0. No adoption inference. */
final class InterProProteinRegionPreparation {
    static final String VERSION = "JDK_DEMAND_1.0.0";
    private static final Set<String> TYPES = Set.of("Domain", "Region", "Active site", "Binding site", "Site", "Motif",
            "DNA binding", "Zinc finger", "Transmembrane", "Topological domain", "Compositional bias", "Repeat");
    static ObjectNode combine(ObjectMapper mapper, String accession, String sequence, JsonNode uni,
            JsonNode protein, JsonNode entries, ObjectNode sources) {
        var meta = protein.path("metadata");
        require(accession.equals(uni.path("primaryAccession").asText()) && accession.equals(meta.path("accession").asText())
                && "9606".equals(meta.at("/source_organism/taxId").asText()) && meta.path("is_fragment").isBoolean()
                && !meta.path("is_fragment").asBoolean() && "reviewed".equals(meta.path("source_database").asText()), "PROTEIN_REGION_INTERPRO_IDENTITY_INVALID");
        require(sequence.equals(meta.path("sequence").asText()) && sequence.equals(uni.at("/sequence/value").asText())
                && positive(meta.path("length")) && meta.path("length").asInt() == sequence.length()
                && positive(uni.at("/sequence/length")) && uni.at("/sequence/length").asInt() == sequence.length(), "PROTEIN_REGION_INTERPRO_SEQUENCE_MISMATCH");
        require(entries.has("next") && entries.path("next").isNull() && entries.has("previous") && entries.path("previous").isNull()
                && entries.path("results").isArray() && entries.path("count").isIntegralNumber() && entries.path("count").canConvertToInt()
                && entries.path("count").asInt() == entries.path("results").size() && entries.path("results").size() <= 200,
                "PROTEIN_REGION_INTERPRO_PAGINATION_INCOMPLETE");
        require(uni.path("features").isArray(), "PROTEIN_REGION_UNIPROT_FEATURES_MISSING");
        var out = mapper.createObjectNode().put("schemaVersion", "combined-protein-region-facts-v1").put("adapterVersion", VERSION)
                .put("accession", accession).put("sequenceSha256", CodingTranscriptMaterialSupply.sha(sequence)).put("length", sequence.length())
                .put("coordinateSystem", "ONE_BASED_CLOSED").put("reviewStatus", "UNREVIEWED").put("clinicalAdoption", false)
                .put("nonRepeatQualified", false).put("noKnownFunctionQualified", false)
                .put("sourceScope", "UNIPROT_FEATURES_AND_INTEGRATED_INTERPRO_ENTRIES_ONLY");
        out.set("sources", sources.deepCopy()); var regions = out.putArray("regions"); var unplaced = out.putArray("unplaced"); var context = out.putArray("context");
        int i = 0;
        for (var feature : uni.path("features")) {
            String pointer = "/features/" + i++, type = feature.path("type").asText();
            if (!TYPES.contains(type)) continue;
            var location = feature.path("location");
            if (!location.path("sequence").asText().isEmpty() || !"EXACT".equals(location.at("/start/modifier").asText())
                    || !"EXACT".equals(location.at("/end/modifier").asText())) {
                unplaced.addObject().put("source", "UniProt").put("locator", pointer).put("reason", "ISOFORM_OR_IMPRECISE_LOCATION").set("raw", feature.deepCopy());
                continue;
            }
            var row = regions.addObject().put("source", "UniProt").put("locator", pointer).put("kind", type)
                    .put("category", "Repeat".equals(type) ? "REPEAT" : "FUNCTION_LEAD");
            row.putArray("fragments").add(span(mapper, location.at("/start/value"), location.at("/end/value"), sequence.length())); row.set("raw", feature.deepCopy());
        }
        i = 0; var seen = new HashSet<String>();
        for (var entry : entries.path("results")) {
            var em = entry.path("metadata"); String id = em.path("accession").asText(), kind = em.path("type").asText();
            require(id.matches("IPR[0-9]{6}") && seen.add(id) && "interpro".equals(em.path("source_database").asText())
                    && !kind.isBlank() && entry.path("proteins").isArray() && entry.path("proteins").size() == 1,
                    "PROTEIN_REGION_INTERPRO_ENTRY_INVALID");
            var p = entry.path("proteins").get(0);
            require(accession.equalsIgnoreCase(p.path("accession").asText()) && "9606".equals(p.path("organism").asText())
                    && positive(p.path("protein_length")) && p.path("protein_length").asInt() == sequence.length()
                    && p.path("entry_protein_locations").isArray() && p.path("entry_protein_locations").size() <= 10000,
                    "PROTEIN_REGION_INTERPRO_LOCATION_IDENTITY_INVALID");
            String pointer = "/results/" + i++; int j = 0;
            for (var location : p.path("entry_protein_locations")) {
                require(location.path("fragments").isArray() && !location.path("fragments").isEmpty()
                        && location.path("fragments").size() <= 10000 && regions.size() < 25000, "PROTEIN_REGION_INTERPRO_FRAGMENT_LIMIT");
                var row = regions.addObject().put("source", "InterPro").put("entry", id).put("kind", kind)
                        .put("category", "repeat".equals(kind) ? "REPEAT" : "domain".equals(kind) ? "DOMAIN_LEAD" : "PROTEIN_CONTEXT")
                        .put("locator", pointer + "/proteins/0/entry_protein_locations/" + j++);
                row.set("metadata", em.deepCopy()); row.set("raw", location.deepCopy()); var fragments = row.putArray("fragments");
                for (var f : location.path("fragments")) {
                    String dc = f.path("dc-status").asText();
                    require(Set.of("CONTINUOUS", "N_TERMINAL_DISC", "C_TERMINAL_DISC", "NC_TERMINAL_DISC").contains(dc), "PROTEIN_REGION_INTERPRO_DISCONTINUITY_INVALID");
                    fragments.add(span(mapper, f.path("start"), f.path("end"), sequence.length()).put("discontinuity", dc));
                }
            }
            context.addObject().put("source", "InterPro").put("entry", id).put("locator", pointer + "/metadata")
                    .put("scope", "ENTRY_ASSOCIATION_NOT_LOCAL_FUNCTION_PROOF").set("raw", em.deepCopy());
        }
        out.putObject("sourceCompleteness").put("uniprotFeaturesComplete", true).put("interproEntriesComplete", true)
                .put("exactFullSequenceMatch", true).put("uniprotFeatureCount", uni.path("features").size()).put("interproEntryCount", entries.path("count").asInt());
        return out;
    }
    private static ObjectNode span(ObjectMapper mapper, JsonNode start, JsonNode end, int length) {
        require(positive(start) && positive(end) && start.asInt() <= end.asInt() && end.asInt() <= length, "PROTEIN_REGION_INTERPRO_COORDINATES_INVALID");
        return mapper.createObjectNode().put("start", start.asInt()).put("end", end.asInt());
    }
}
