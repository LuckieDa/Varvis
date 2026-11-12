package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;
/** Two different fictional truncating mechanisms and exact variant applicability, policies, transcripts, CDS and strands. */
public final class Bp1BusinessFixture {
    public final List<ObjectNode> models=new ArrayList<>(),policies=new ArrayList<>(),annotations=new ArrayList<>(),expected=new ArrayList<>();
    public final List<NormalizedVariantKey> alleles=new ArrayList<>();
    public final List<Bp1TestFixtures> fixtures=new ArrayList<>();
    private final ObjectMapper mapper;
    public Bp1BusinessFixture(ObjectMapper mapper)throws Exception{this.mapper=mapper;for(int strand:List.of(1,-1)){
        var f=new Bp1TestFixtures(mapper,strand);fixtures.add(f);models.add(f.model);policies.add(f.policy);annotations.add(f.annotation);expected.add(f.expected);alleles.add(f.candidate.getObservation().getKey());}}
    public TranscriptConsequence annotation(int i)throws Exception{return mapper.treeToValue(annotations.get(i),TranscriptConsequence.class);}
    public String canonical(JsonNode node){return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(node);}
    public ObjectNode document()throws Exception{
        for(var f:fixtures){f.bind();f.reviewConditions();f.bind();}
        ObjectNode d=fixtures.get(0).document(),other=fixtures.get(1).document();d.withArray("policies").add(other.at("/policies/0").deepCopy());
        for(String key:List.of("transcriptModels","bp1Mechanisms"))((ObjectNode)d.path("materials").path(key)).setAll((ObjectNode)other.at("/materials/"+key));
        d.withObject("catalog").withArray("sources").add(other.at("/catalog/sources/0").deepCopy());
        d.withObject("catalog").withArray("entries").add(other.at("/catalog/entries/0").deepCopy());d.put("catalogSha256",canonical(d.path("catalog")));return d;
    }
}
