package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;
/** TEST_ONLY two fictional backgrounds and independently fixed literal sequence expectations. */
public final class Bp3BusinessFixture {
    public final List<ObjectNode> models = new ArrayList<>(), policies = new ArrayList<>(), annotations = new ArrayList<>(), expected = new ArrayList<>();
    public final List<NormalizedVariantKey> alleles = new ArrayList<>();
    public final List<Bp3TestFixtures> fixtures = new ArrayList<>();
    private final ObjectMapper mapper;
    public Bp3BusinessFixture(ObjectMapper mapper, boolean alternate) throws Exception {
        this.mapper = mapper;
        JsonNode fixed;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/bp3-test-only-sequences.json"))) { fixed = mapper.readTree(input); }
        for (String id : List.of(alternate ? "plus-phase1" : "plus-phase0", "minus-repeat-delete")) {
            ObjectNode row = null;
            for (var item : Objects.requireNonNull(fixed).path("rows")) if (id.equals(item.path("id").asText())) row = (ObjectNode) item;
            if (row == null) throw new AssertionError("Fixed input missing");
            var f = new Bp3TestFixtures(mapper, row.path("strand").asInt());
            f.variant(row.path("pos").asInt(), row.path("ref").asText(), row.path("alt").asText(), row.path("coding").asText(), row.path("protein").asText());
            fixtures.add(f); models.add(f.model); policies.add(f.policy); expected.add(row.deepCopy());
            annotations.add(f.annotation(row.path("coding").asText(), row.path("protein").asText(), row.path("net").asInt() > 0 ? "inframe_insertion" : "inframe_deletion"));
            alleles.add(f.candidate.getObservation().getKey());
        }
    }
    public TranscriptConsequence annotation(int index) throws Exception { return mapper.treeToValue(annotations.get(index), TranscriptConsequence.class); }
    public String canonical(JsonNode node) { return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(node); }
    public ObjectNode document() {
        for (var f : fixtures) { f.bind(); f.review(); f.bind(); }
        ObjectNode doc = fixtures.get(0).document(); ObjectNode other = fixtures.get(1).document();
        doc.withArray("policies").add(other.at("/policies/0").deepCopy());
        ((ObjectNode) doc.path("materials").path("transcriptModels")).setAll((ObjectNode) other.at("/materials/transcriptModels"));
        ((ObjectNode) doc.path("materials").path("bp3Regions")).setAll((ObjectNode) other.at("/materials/bp3Regions"));
        doc.withObject("catalog").withArray("entries").add(((ObjectNode) other.at("/catalog/entries/0")).deepCopy().put("entryId", "TEST_ONLY_BP3_SECOND_CLAUSE"));
        doc.put("catalogSha256", canonical(doc.path("catalog"))); return doc;
    }
}
