package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.tabix.*;
import java.nio.file.*;
import java.util.*;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Invented upstream VCF/sidecar/annotation facts. Never constructed from a reviewed referenceAudit. */
final class MissenseReferenceDraftFixture {
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Path root, manifestPath;
    final ObjectNode manifest, context, target, built, annotationSnapshot, sourceSnapshot;
    final int background;
    int discoveryCalls, annotationCalls;
    MissenseReferenceDraftFixture(Path root, int background, String scenario) throws Exception {
        this.root=root; this.background=background; Files.createDirectories(root); manifestPath=root.resolve("manifest.json");
        String contig=background==0?"11":"13", gene=background==0?"HBB":"GJB2", tx=background==0?"ENST00000647020.1":"ENST00000382848.5";
        String protein=background==0?"ENSP000001.1":"ENSP000002.1";
        var targetKey=new NormalizedVariantKey("GRCh38",contig,105,"A","C");
        var referenceKey=background==0?new NormalizedVariantKey("GRCh38",contig,105,"A","T"):new NormalizedVariantKey("GRCh38",contig,103,"G","A");
        ObjectNode targetAnn=annotation(gene,tx,protein,false), referenceAnn=annotation(gene,tx,protein,true);
        try(var input=Objects.requireNonNull(getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json"))){context=(ObjectNode)mapper.readTree(input).path("context").deepCopy();}
        context.putObject("gene").put("symbol",gene).put("hgncId",background==0?"HGNC:4827":"HGNC:4284");
        context.putObject("disease").put("mondoId",background==0?"MONDO:0013517":"MONDO:0019497");context.put("moi","AUTOSOMAL_RECESSIVE");
        int dot=tx.lastIndexOf('.'); context.putObject("transcript").put("accession",tx.substring(0,dot)).put("version",Integer.parseInt(tx.substring(dot+1))).put("annotationSourceVersion","116.0");
        ObjectNode variantIdentity=(ObjectNode)context.path("variant");
        for(String field:List.of("inputAllele","normalizedAllele","evidenceQueryAllele"))variantIdentity.set(field,allele(targetKey));
        variantIdentity.put("alleleId",adapter.canonicalAlleleId(allele(targetKey),variantIdentity.path("normalization")));
        context.put("contextId",adapter.canonicalContextId(context));
        sourceSnapshot=mapper.createObjectNode().put("dataClass","SYNTHETIC").put("purpose","TEST_ONLY");
        sourceSnapshot.set("CLINVAR_VCF",source("TEST_ONLY_VCF","synthetic-1",'a'));
        sourceSnapshot.set("SIDECAR",source("TEST_ONLY_SIDECAR","synthetic-1",'b'));
        sourceSnapshot.set("VEP",source("TEST_ONLY_VEP","116.0",'c'));
        Path vcf=root.resolve("synthetic.vcf.gz"); Files.write(vcf,new byte[]{1}); Files.write(Path.of(vcf+".tbi"),new byte[]{1});
        var config=new VariantAnalysisProperties(); config.getClinVar().setEnabled(true); config.getClinVar().setGrch38File(vcf.toString());
        var provider=new ClinVarTabixProvider(config,null,(file,region)->{
            discoveryCalls++;
            if("NO_RECORD".equals(scenario)) return List.of();
            if("DISCOVERY_FAILED".equals(scenario)) throw new java.io.IOException("SYNTHETIC query failure");
            String row=contig+"\t"+referenceKey.getPosition()+"\t"+(background+1)+"\t"+referenceKey.getReference()+"\t"+referenceKey.getAlternate()+"\t.\tPASS\tCLNSIGSCV=SCV000000001.1|SCV000000002.2";
            return "PARTIAL".equals(scenario)?List.of(row,contig+"\t105\t9\tA\tG\t.\tPASS\tCLNSIGSCV=SCV9.1"):List.of(row);
        });
        var vep=new VepTranscriptAnnotationProvider(new VariantAnalysisProperties()) {
            @Override public Map<NormalizedVariantKey,List<TranscriptConsequence>> annotateBatch(List<VariantInterpretation> variants,AnalysisContext analysis) {
                annotationCalls++; Map<NormalizedVariantKey,List<TranscriptConsequence>> result=new LinkedHashMap<>();
                for(var v:variants){var key=v.getObservation().getKey();
                    if("ANNOTATION_OMITTED".equals(scenario)&&key.equals(referenceKey))continue;
                    if("PARTIAL".equals(scenario)&&key.getPosition()==105&&key.getAlternate().equals("G"))continue;
                    try{result.put(key,List.of(Objects.requireNonNull(mapper.treeToValue(key.equals(referenceKey)?referenceAnn:targetAnn,TranscriptConsequence.class))));}
                    catch(Exception invalid){throw new IllegalStateException(invalid);}
                }return result;
            }
        };
        Path sidecarPath=root.resolve("synthetic-sidecar.tsv.gz");
        var index=new TabixIndexCreator(new TabixFormat(0,1,2,3,'#',0));
        try(var output=new BlockCompressedOutputStream(sidecarPath.toFile())){
            int id=background+1;
            index.addFeature(new htsjdk.tribble.Feature(){public String getContig(){return "VID";}public int getStart(){return id;}public int getEnd(){return id;}},output.getFilePointer());
            ArrayNode assertions=mapper.createArrayNode();
            for(int i=1;i<=2;i++){
                ObjectNode scv=assertions.addObject().put("scv",i==1?"SCV000000001.1":"SCV000000002.2")
                        .put("classification",i==1?"Pathogenic":"Uncertain significance").put("dateLastEvaluated",i==1?"2026-01-02":"2026-02-03")
                        .put("reviewStatus","criteria provided, single submitter");
                scv.putObject("condition").put("name","TEST_ONLY condition "+i).put("submittedRaw","SYNTHETIC raw condition "+i);
                scv.putObject("sourceLocator").put("logicalName","SYNTHETIC-scv.xml").put("record",i);
            }
            ArrayNode summaries=mapper.createArrayNode();
            for(int i=1;i<=2;i++)summaries.addObject().put("rcv","RCV00000000"+i+".1").put("classification","Conflicting classifications of pathogenicity").putObject("condition").put("name","TEST_ONLY condition "+i);
            String row="VID\t"+id+"\t"+id+"\t"+id+"\tGRCh38\t"+contig+"\t"+referenceKey.getPosition()+"\t"+referenceKey.getReference()+"\t"+referenceKey.getAlternate()+"\t[\""+gene+"\"]\tCONFLICTING\t"+summaries+"\t"+assertions+"\n";
            output.write(row.getBytes(java.nio.charset.StandardCharsets.UTF_8)); index.finalizeIndex(output.getFilePointer()).writeBasedOnFeaturePath(sidecarPath);
        }
        var sidecar=new ClinVarComparatorSidecar(mapper,"SIDECAR_MISSING".equals(scenario)?root.resolve("absent.tsv.gz"):sidecarPath,"TEST_ONLY_SIDECAR","synthetic-1","b".repeat(64));
        var builder=new ClinVarConditionAwareComparatorInput(provider,vep,sidecar,new VepProteinRelationClassifier());
        var variant=new VariantInterpretation(targetKey.canonicalId(),new VariantQcObservation(targetKey,null,null,null,null,null,null,"PASS","SNV",null),List.of(),List.of());
        built=mapper.valueToTree(builder.build(context,variant,mapper.treeToValue(targetAnn,TranscriptConsequence.class)));
        target=mapper.createObjectNode().put("schemaVersion","missense-target-annotation-snapshot-v1").put("contextId",context.path("contextId").asText()).put("transcript",tx);
        target.set("allele",allele(targetKey)); target.set("annotation",targetAnn); target.set("source",sourceSnapshot.path("VEP"));
        annotationSnapshot=mapper.createObjectNode().put("schemaVersion","missense-reference-annotation-snapshot-v1").put("contextId",context.path("contextId").asText()).put("transcript",tx).put("variationId",String.valueOf(background+1));
        annotationSnapshot.set("targetAllele",allele(targetKey)); annotationSnapshot.set("referenceAllele",allele(referenceKey)); annotationSnapshot.set("annotation",referenceAnn); annotationSnapshot.set("source",sourceSnapshot.path("VEP"));
        manifest=mapper.createObjectNode().put("schemaVersion","missense-reference-draft-manifest-v1").put("dataClass","SYNTHETIC").put("purpose","TEST_ONLY");
        refresh();
    }
    ObjectNode annotation(String gene,String tx,String protein,boolean reference){
        boolean different=reference&&background==1;
        return mapper.createObjectNode().put("geneSymbol",gene).put("transcriptAccession",tx).put("hgvsCoding",tx+(different?":c.4G>A":reference?":c.6A>T":":c.6A>C"))
                .put("hgvsProtein",protein+(different?":p.Glu2Lys":":p.Glu2Asp")).put("proteinPosition","2").put("aminoAcids",different?"E/K":"E/D")
                .put("codons",different?"Gaa/Aaa":reference?"gaA/gaT":"gaA/gaC").put("consequence","missense_variant").put("exon","1/1")
                .put("status","COMPLETED").put("source","VEP").put("version","116.0").put("timestamp","2026-09-26T00:00:00Z");
    }
    ObjectNode source(String id,String version,char pin){return mapper.createObjectNode().put("resourceId",id).put("resourceVersion",version).put("identityFingerprintSha256",String.valueOf(pin).repeat(64));}
    ObjectNode allele(NormalizedVariantKey key){return mapper.createObjectNode().put("assembly",key.getGenomeAssembly()).put("contig",key.getChromosome()).put("position",key.getPosition()).put("reference",key.getReference()).put("alternate",key.getAlternate());}
    void refresh()throws Exception{
        manifest.set("context",file("context.json",context));manifest.set("target",file("target.json",target));manifest.set("comparatorInput",file("comparators.json",built));manifest.set("sourceSnapshot",file("sources.json",sourceSnapshot));
        var a=manifest.putArray("referenceAnnotations");if(!built.path("comparators").isEmpty())a.add(file("reference.json",annotationSnapshot));save();
    }
    ObjectNode file(String name,JsonNode value)throws Exception{byte[] bytes=mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);Files.write(root.resolve(name),bytes);return mapper.createObjectNode().put("path",name).put("rawSha256",Pm4MaterialPreparation.sha(bytes)).put("canonicalSha256",adapter.canonicalSha256(value));}
    void save()throws Exception{mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(),manifest);}
    String pin()throws Exception{return Pm4MaterialPreparation.sha(Files.readAllBytes(manifestPath));}
    MissenseReferenceDraftPreparation.Result prepare(Path out)throws Exception{save();return new MissenseReferenceDraftPreparation().prepare(manifestPath,pin(),out);}
}
