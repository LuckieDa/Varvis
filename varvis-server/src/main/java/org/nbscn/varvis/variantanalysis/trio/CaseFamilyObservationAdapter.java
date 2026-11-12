package org.nbscn.varvis.variantanalysis.trio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

/** Copies actual caller records verbatim. It never supplies parentage, unaffected status or reviews. */
public final class CaseFamilyObservationAdapter {
    private final ObjectMapper mapper;
    public CaseFamilyObservationAdapter(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    public CaseFamilyObservation adapt(ObjectNode supplementalFacts, List<TrioVcfGenotypeRecord> records) {
        ObjectNode snapshot = Objects.requireNonNull(supplementalFacts).deepCopy();
        var target = snapshot.putArray("records");
        for (TrioVcfGenotypeRecord record : Objects.requireNonNull(records)) {
            Objects.requireNonNull(record);
            ObjectNode copy = target.addObject();
            copy.set("variantKey", mapper.valueToTree(record.getVariantKey()));
            copy.put("role", record.getRole().name()).put("sampleId", record.getSampleId())
                    .put("geneSymbol", record.getGeneSymbol()).put("recordFilter", record.getRecordFilter())
                    .put("sampleFilter", record.getSampleFilter()).put("rawSampleValue", record.getRawSampleValue());
            TrioGenotypeObservation observation = record.getObservation();
            if (observation == null) copy.putNull("observation");
            else copy.putObject("observation").put("role", observation.getRole().name())
                    .put("genotype", observation.getGenotype()).put("call", observation.getCall().name())
                    .put("depth", observation.getDepth()).put("genotypeQuality", observation.getGenotypeQuality())
                    .put("alleleDepth", observation.getAlleleDepth()).put("phased", observation.isPhased());
        }
        return new CaseFamilyObservation(snapshot);
    }
}
