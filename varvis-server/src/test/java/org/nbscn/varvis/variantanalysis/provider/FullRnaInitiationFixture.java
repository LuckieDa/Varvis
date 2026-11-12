package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Actual pinned source/curation byte replay on invented data. No clinical evidence or scorer stub. */
final class FullRnaInitiationFixture {
    static final String REFERENCE="CCATGAATGCCATGTTTGAATAAGGGTGA";
    static final String PRODUCT="CCAATGCCATGTTTGAATAAGGGTGA";
    static final String PRIMARY="SYNTHETIC TEST ONLY: complete transcript review; source-selected RNA start 9, MFE*; independent pathogenic reference before CDS10; bounded complete upstream review. This is not a real experiment.";
    final Pvs1FullRnaCriterionTest base=new Pvs1FullRnaCriterionTest();
    final Pvs1FullRnaCriterionTest.Data data;
    final FullRnaCriterionFixture rna;
    final LinkedHashMap<String,ObjectNode> documents=new LinkedHashMap<>();
    String primaryText=PRIMARY;
    FullRnaInitiationFixture(Path root,int strand) throws Exception {this(root,strand,REFERENCE);}
    FullRnaInitiationFixture(Path root,int strand,String reference) throws Exception {
        base.root=root;data=base.data(strand,"start-loss",0);rna=data.rna();
        rna.f.configure(reference,3,23,8,17,25,29);rna.intronicAndScope();rna.edit("remove-native-atg",2,5,"");
        rna.product(reference.substring(0,2)+reference.substring(5),5,14,22);
        data.policy().withObject("transcriptModelBinding").put("canonicalSha256",rna.f.token.modelSha256());
        var neutral=rna.source();
        var transcripts=doc("TRANSCRIPT_FUNCTION_REVIEW",neutral);
        transcripts.put("complete",true).put("truncated",false).put("unresolvedCount",0);
        transcripts.putArray("findings").addObject().put("transcript",rna.f.model.path("transcript").asText());
        var other=transcripts.withArray("findings").addObject().put("transcript","ENST000002.1").put("differentStart",false)
                .put("functionallyRelevant",true).put("targetUnaffected",false)
                .put("qualification","REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE");quote(other,"sourceQuotes");
        documents.put("init-transcripts",transcripts);
        var restart=doc("RESTART_REVIEW",neutral).put("startStatus","SOURCE_REVIEWED_PUTATIVE").put("alteredStartRna1",9)
                .put("proteinThroughFirstStop","MFE*").put("functionFinding","UNKNOWN");documents.put("init-restart",restart);
        var upstream=doc("UPSTREAM_REVIEW",neutral).put("complete",true).put("truncated",false).put("unresolvedCount",0)
                .put("cdsStart",1).put("cdsEnd",9);upstream.putArray("records");documents.put("init-upstream",upstream);
        var scope=data.policy().withObject("spliceScope").putObject("initiationScope").put("status","REVIEWED_FOR_FULL_RNA_INITIATION")
                .put("method",Pvs1RnaInitiation.ALGORITHM).put("methodVersion",Pvs1RnaInitiation.VERSION)
                .put("reviewId","SYNTHETIC_RNA_INITIATION_POLICY").put("basis","Explicit invented engineering scope, not clinical approval")
                .put("maximumStrength","MODERATE");
        scope.putArray("sourceIds").add("synthetic-source");scope.putArray("assessedTranscripts").add(rna.f.model.path("transcript").asText()).add("ENST000002.1");
        scope.putArray("sourceBindings");publish("init-primary","PRIMARY_EVIDENCE",PRIMARY,"text/plain");
        for(var entry:documents.entrySet())publish(entry.getKey(),entry.getValue().path("role").asText(),rna.f.mapper.writeValueAsString(entry.getValue()),"application/json");
        rebind();
    }
    private ObjectNode doc(String role,Pvs1SourceQualification.SourceBoundFullRna source) {
        var d=rna.f.mapper.createObjectNode().put("schemaVersion","pvs1-rna-initiation-reviewed-source-v1").put("role",role)
                .put("reviewId","SYNTHETIC_"+role).put("dataClass","SYNTHETIC").put("clinicalAcceptance",false)
                .put("contextSha256",rna.f.adapter.canonicalSha256(rna.f.context));
        d.putObject("scope").put("modelSha256",rna.f.token.modelSha256()).put("alteredRnaSha256",source.result().alteredRnaSha256())
                .put("mechanicalEffectSha256",source.facts().mechanicalEffectSha256()).put("selectedTranscript",rna.f.model.path("transcript").asText());
        var review=d.putObject("review").put("status","REVIEWED_FOR_THIS_RNA_PRODUCT").put("basis","SYNTHETIC facts only; no experiment was performed");
        quote(review,"sourceQuotes");return d;
    }
    void quote(ObjectNode owner,String key) {owner.putArray(key).addObject().put("sourceId","init-primary").put("start",0).put("end",primaryText.length()).put("text",primaryText);}
    void publish(String id,String role,String raw,String media) {
        var sources=data.policy().withArray("scientificSources");
        for(int i=sources.size()-1;i>=0;i--)if(id.equals(sources.get(i).path("sourceId").asText()))sources.remove(i);
        String sha=CodingTranscriptMaterialSupply.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sources.addObject().put("sourceId",id).put("version","TEST_ONLY").put("url","https://example.test/"+id).put("sha256",sha);
        var bindings=data.policy().withObject("spliceScope").withObject("initiationScope").withArray("sourceBindings");
        for(int i=bindings.size()-1;i>=0;i--)if(id.equals(bindings.get(i).path("sourceId").asText()))bindings.remove(i);
        bindings.addObject().put("sourceId",id).put("role",role).put("sha256",sha).put("version","TEST_ONLY")
                .put("url","https://example.test/"+id).put("reviewId","SYNTHETIC_"+role).put("mediaType",media);
        rna.original.withObject("pvs1Assessment").withObject("initiationReview").withObject("sourceDocuments").putObject(id).put("rawText",raw);
    }
    void republish(String id) throws Exception {var d=documents.get(id);publish(id,d.path("role").asText(),rna.f.mapper.writeValueAsString(d),"application/json");rebind();}
    void rebind() throws Exception {
        var former=rna.original.at("/pvs1Assessment/initiationReview").deepCopy();
        String rescue=rna.original.at("/pvs1Assessment/parentReview/functionalRescue").asText("EXCLUDED_IN_REVIEWED_SCOPE");
        base.bind(data);var review=former.isObject()?(ObjectNode)former:rna.f.mapper.createObjectNode();
        review.put("schemaVersion","pvs1-rna-initiation-review-v1").put("reviewId","SYNTHETIC_RNA_INITIATION_REVIEW").put("basis","Invented reviewed source records, real byte checks");
        var ids=review.putArray("sourceIds");for(var s:data.policy().path("scientificSources"))ids.add(s.path("sourceId"));
        rna.original.withObject("pvs1Assessment").set("initiationReview",review);
        rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue",rescue);
    }
    ObjectNode run() throws Exception {var out=rna.f.mapper.createObjectNode();base.run(data,out);return out;}
    void functionalAlternative() throws Exception {
        var alternative=(ObjectNode)documents.get("init-transcripts").at("/findings/1");
        alternative.put("differentStart",true).put("functionallyRelevant",true).put("targetUnaffected",true)
                .put("qualification","REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE");
        rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue","ASSESSED_IN_INITIATION_REVIEW");republish("init-transcripts");
    }
    void pathogenicReference() throws Exception {pathogenicReference(4,"A","G");}
    void pathogenicReference(int cdsPosition,String reference,String alternate) throws Exception {
        var allele=rna.f.mapper.createObjectNode().put("assembly",rna.f.model.path("assembly").asText()).put("contig",rna.f.model.path("contig").asText())
                .put("position",genomicCds(cdsPosition)).put("reference",CodingTranscriptMapping.orientAllele(rna.f.model,reference))
                .put("alternate",CodingTranscriptMapping.orientAllele(rna.f.model,alternate));
        var record=documents.get("init-upstream").withArray("records").addObject().put("recordId","SYNTHETIC_INDEPENDENT_C"+cdsPosition)
                .put("hgncId",rna.f.context.at("/gene/hgncId").asText()).put("mondoId",rna.f.context.at("/disease/mondoId").asText())
                .put("moi",rna.f.context.path("moi").asText()).put("classification","PATHOGENIC").put("withdrawn",false).put("conflicting",false)
                .put("classificationBasis","INDEPENDENT_OF_START_LOSS_INFERENCE");record.set("allele",allele);quote(record,"sourceQuotes");republish("init-upstream");
    }
    void editedEarlierMutantMet() throws Exception {
        rna.original.withObject("events").putArray("rnaEdits");rna.edit("remove-start-create-earlier-met",2,8,"CATG");
        rna.product("CCCATGGCCATGTTTGAATAAGGGTGA",6,15,23);
        var source=rna.source();
        synchronizeScientificSources(source,10,"SYNTHETIC TEST ONLY: full altered RNA CCCATGGCCATGTTTGAATAAGGGTGA; source-specified RNA10 MFE*, earlier edit-created RNA4 ATG. All observations, classifications and functional records in this control are invented, not human evidence.");
    }
    void unchangedUpstreamUtrAtg() throws Exception {
        rna.f.configure("ATGA"+REFERENCE,7,27,12,21,29,33);rna.intronicAndScope();rna.edit("remove-native-only",6,9,"");
        rna.product("ATGA"+PRODUCT,9,18,26);
        data.policy().withObject("transcriptModelBinding").put("canonicalSha256",rna.f.token.modelSha256());
        var source=rna.source();
        synchronizeScientificSources(source,13,"SYNTHETIC TEST ONLY: original and altered RNA retain the same 5prime UTR ATG at RNA1; native coding start is removed, source-selected putative start RNA13 MFE*. All facts are invented controls, not observed translation.");
    }
    private void synchronizeScientificSources(Pvs1SourceQualification.SourceBoundFullRna source,int start,String primary) throws Exception {
        primaryText=primary;publish("init-primary","PRIMARY_EVIDENCE",primaryText,"text/plain");
        documents.get("init-restart").put("alteredStartRna1",start);
        for(var entry:documents.entrySet()) {
            var d=entry.getValue();d.put("contextSha256",rna.f.adapter.canonicalSha256(rna.f.context));
            d.withObject("scope").put("modelSha256",rna.f.token.modelSha256()).put("alteredRnaSha256",source.result().alteredRnaSha256())
                    .put("mechanicalEffectSha256",source.facts().mechanicalEffectSha256());
            quote(d.withObject("review"),"sourceQuotes");
            for(var finding:d.path("findings"))if(finding instanceof ObjectNode row && row.has("sourceQuotes"))quote(row,"sourceQuotes");
            for(var record:d.path("records"))if(record instanceof ObjectNode row)quote(row,"sourceQuotes");
            publish(entry.getKey(),d.path("role").asText(),rna.f.mapper.writeValueAsString(d),"application/json");
        }
        rebind();
    }
    void captureGuardProvider(String scenario,ObjectNode module) throws Exception {
        String directory=System.getProperty("varvis.t07.guard.artifacts","");if(directory.isBlank())return;
        var source=rna.source();var runtime=new TruncatingNmdPvs1Runtime(rna.f.mapper,new TruncatingNmdPvs1RuntimeTest().resourceProfile(),rna.f.model,true);
        var actual=runtime.evaluateFullRna(rna.f.context,rna.f.candidate,data.policy(),data.selection(),source);
        org.junit.jupiter.api.Assertions.assertEquals(module.path("fullRnaAssessment"),actual.at("/resolvedInput/fullRnaAssessment"),actual::toPrettyString);
        var target=Path.of(directory).resolve(scenario+"-"+rna.f.model.path("strand").asInt());java.nio.file.Files.createDirectories(target);
        java.nio.file.Files.write(target.resolve("original.json"),source.originalBytes());
        var sample=rna.f.mapper.createObjectNode().put("layer","SYNTHETIC_ACTUAL_RNA_SOURCE_AND_PROVIDER_NOT_NORMAL_BUSINESS")
                .put("sourceOriginalSha256",source.originalSha256()).put("humanClinicalSourceValidation",false);
        sample.set("context",rna.f.context);sample.set("model",rna.f.model);sample.set("policy",data.policy());sample.set("selection",data.selection());
        sample.set("runtimeResult",actual);rna.f.mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("provider-result.json").toFile(),sample);
    }
    private int genomicCds(int position) {
        for(var exon:rna.f.model.path("exons"))if(position>=exon.path("cdsStart").asInt()&&position<=exon.path("cdsEnd").asInt())
            return rna.f.model.path("strand").asInt()==1?exon.path("genomicStart").asInt()+position-exon.path("cdsStart").asInt()
                    :exon.path("genomicEnd").asInt()-position+exon.path("cdsStart").asInt();
        throw new IllegalArgumentException("Synthetic reference CDS coordinate missing");
    }
}
