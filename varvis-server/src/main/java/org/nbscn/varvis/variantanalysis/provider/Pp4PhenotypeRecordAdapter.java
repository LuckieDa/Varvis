package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.trio.*;

/** Maps v1 facts to the existing rule-neutral raw parser, then the immutable case adapter. No extra patient model. */
public final class Pp4PhenotypeRecordAdapter {
    private final ObjectMapper m = new ObjectMapper();
    public CaseFamilyObservation adapt(ObjectNode facts) {
        var r = facts.path("records").get(0); var member = facts.path("members").get(0); var d = member.path("detection");
        if (r == null || member == null || !r.path("observation").isObject()) throw Pp4PhenotypeBundle.JSON.invalid("RECORD_REQUIRED");
        ObjectNode bridge = m.createObjectNode().put("recordId", facts.at("/sources/0/recordId").asText())
                .put("memberId", member.path("memberId").asText()).put("sourcePersonId", member.path("memberId").asText())
                .put("sampleId", r.path("sampleId").asText()).put("geneSymbol", r.path("geneSymbol").asText())
                .put("locator", facts.at("/sources/0/locator").asText()).put("method", d.path("method").asText())
                .put("format", d.path("sampleFormat").asText()).put("rawSample", r.path("rawSampleValue").asText())
                .put("recordFilter", r.path("recordFilter").asText());
        var k = r.path("variantKey");
        bridge.putObject("allele").put("assembly", k.path("assembly").asText()).put("contig", k.path("chromosome").asText())
                .set("position", k.path("position").deepCopy());
        ((ObjectNode) bridge.path("allele")).put("reference", k.path("reference").asText()).put("alternate", k.path("alternate").asText());
        bridge.set("sourceIds", d.path("sourceIds").deepCopy());
        ObjectNode declared = ((ObjectNode) r.path("observation")).deepCopy(); declared.remove("role");
        declared.put("sampleFilter", r.path("sampleFilter").asText()); declared.set("targetAltIndex", d.path("targetAltIndex")); declared.set("ploidy", d.path("ploidy")); bridge.set("observation", declared);
        var parsed = new NuclearFamilyRecordAdapter(Pp4PhenotypeBundle.JSON).adapt(bridge);
        var role = TrioRole.PROBAND;
        var observation = new TrioGenotypeObservation(role, parsed.path("genotype").asText(), TrioGenotypeCall.valueOf(parsed.path("call").asText()),
                parsed.path("depth").intValue(), parsed.path("genotypeQuality").intValue(), parsed.path("alleleDepth").asText(), parsed.path("phased").asBoolean());
        var key = new TrioVcfVariantKey(k.path("assembly").asText(), k.path("chromosome").asText(), k.path("position").longValue(), k.path("reference").asText(), k.path("alternate").asText());
        var record = new TrioVcfGenotypeRecord(key, role, r.path("sampleId").asText(), r.path("geneSymbol").asText(), r.path("recordFilter").asText(), parsed.path("sampleFilter").asText(), r.path("rawSampleValue").asText(), observation);
        var adapted = new CaseFamilyObservationAdapter(m).adapt(facts, List.of(record));
        var identity = new RuntimeEvidenceV2Adapter(m);
        if (!identity.canonicalSha256(adapted.snapshot()).equals(identity.canonicalSha256(facts)))
            throw Pp4PhenotypeBundle.JSON.invalid("RECORD_RAW_PROJECTION_CONFLICT");
        return adapted;
    }
}
