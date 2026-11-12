package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Ps1SpliceKnowledgeTest {
    @TempDir Path dir;
    final ObjectMapper m = new ObjectMapper();
    static final String TEXT="Independent clinical observations. ACMG/AMP criteria applied, as specified by the TEST panel (specification version 1.1, approved 2024):PVS1, PP1_Strong, PS1_Supporting.";
    String assertion(String scv,String cls,String text) {
        return "<ClinicalAssertion><RecordStatus>current</RecordStatus><ClinVarAccession Accession=\""+scv+"\" Version=\"1\" OrgID=\"1\"/>"
            +"<Classification DateLastEvaluated=\"2024-01-02\"><ReviewStatus>reviewed by expert panel</ReviewStatus><GermlineClassification>"+cls+"</GermlineClassification><Comment>"+text+"</Comment></Classification>"
            +"<TraitSet><Trait><XRef DB=\"MONDO\" ID=\"MONDO:1234567\"/></Trait></TraitSet></ClinicalAssertion>";
    }
    String xml(String assertion) {return "<ClinVarResult-Set><VariationArchive VariationID=\"1\"><RecordStatus>current</RecordStatus><Gene Symbol=\"TEST\"/><SequenceLocation Assembly=\"GRCh38\" Chr=\"1\" positionVCF=\"200\" referenceAlleleVCF=\"G\" alternateAlleleVCF=\"A\"/>"+assertion+"</VariationArchive></ClinVarResult-Set>";}
    ObjectNode input(String xml) throws Exception {
        Files.writeString(dir.resolve("source.xml"),xml);Files.writeString(dir.resolve("policy.html"),"PS1 splice policy test fixture");
        var k=m.createObjectNode().put("schemaVersion","ps1-splice-knowledge-v1").put("purpose","PROJECT_RESEARCH").put("scientificApprovalGranted",false);
        var p=k.putArray("policies").addObject().put("policyId","TEST").put("strengthPolicy","CLINGEN_SVI_2023_TABLE2").put("similarityMode","NO_WEAKER")
            .put("eventScope","SINGLE_NATIVE_SITE_LOSS").put("referenceQualification","SOURCE_ORIGINAL_ACMG2015_EXCLUDE_PS1_PM5")
            .put("sourceUrl","https://example.org/test-fixture").put("sourceFile","policy.html").put("sourceSha256",CodingTranscriptMaterialSupply.sha(Files.readAllBytes(dir.resolve("policy.html")))).put("sourceClause","PS1 splice policy test fixture");
        p.putArray("supportedOriginalVersions").add("1.1");p.putObject("scope").put("hgncId","HGNC:1").put("geneSymbol","TEST").put("mondoId","MONDO:1234567").put("moi","AUTOSOMAL_DOMINANT").put("transcript","ENST00000000001.1").put("expertOrgId","1");
        var r=k.putArray("references").addObject().put("variationId","1").put("scv","SCV000000001.1").put("geneSymbol","TEST").put("xmlFile","source.xml")
            .put("xmlSha256",CodingTranscriptMaterialSupply.sha(xml.getBytes(StandardCharsets.UTF_8))).put("expertOrgId","1").put("classification","PATHOGENIC")
            .put("evaluationDate","2024-01-02").put("mondoId","MONDO:1234567").put("originalText",TEXT).put("rawSuffix","PVS1, PP1_Strong, PS1_Supporting.").put("originalVersion","1.1");
        r.putObject("allele").put("assembly","GRCh38").put("contig","1").put("position",200).put("reference","G").put("alternate","A");
        r.putArray("criteria").add("PVS1").add("PP1_Strong").add("PS1_Supporting");return k;
    }
    Ps1SpliceKnowledge load(ObjectNode k) throws Exception {
        byte[] bytes=m.writeValueAsBytes(k);Path f=dir.resolve("knowledge.json");Files.write(f,bytes);return Ps1SpliceKnowledge.load(m,f,CodingTranscriptMaterialSupply.sha(bytes));
    }
    @Test void completeSourceLoadsWithoutChangingOriginalOrGrantingApproval() throws Exception {
        var k=input(xml(assertion("SCV000000001","Pathogenic",TEXT)));var original=k.deepCopy();var loaded=load(k);
        assertEquals(original,k);assertEquals(k.path("references"),loaded.references());
        var ctx=m.createObjectNode().put("moi","AUTOSOMAL_DOMINANT");ctx.putObject("gene").put("hgncId","HGNC:1").put("symbol","TEST");ctx.putObject("disease").put("mondoId","MONDO:1234567");
        assertEquals("TEST",loaded.select(ctx,m.createObjectNode().put("transcript","ENST00000000001.1")).path("policyId").asText());
        ctx.withObject("/disease").put("mondoId","MONDO:7654321");assertThrows(IllegalArgumentException.class,()->loaded.select(ctx,m.createObjectNode().put("transcript","ENST00000000001.1")));
    }
    @ParameterizedTest @ValueSource(strings={"gene","assembly","allele","date","scv","disease","suffix","criteria","version","hash","scope-tolerance"})
    void repinningExtractedMetadataCannotChangeRawEvidence(String mutation) throws Exception {
        var k=input(xml(assertion("SCV000000001","Pathogenic",TEXT)));var r=(ObjectNode)k.at("/references/0");
        switch(mutation) {
            case "gene" -> r.put("geneSymbol","OTHER");
            case "assembly" -> ((ObjectNode)r.path("allele")).put("assembly","GRCh37");
            case "allele" -> ((ObjectNode)r.path("allele")).put("alternate","C");
            case "date" -> r.put("evaluationDate","2025-01-02");
            case "scv" -> r.put("scv","SCV000000002.1");
            case "disease" -> r.put("mondoId","MONDO:7654321");
            case "suffix" -> {r.put("rawSuffix","PP1_Strong, PS1_Supporting.");r.putArray("criteria").add("PP1_Strong").add("PS1_Supporting");}
            case "criteria" -> r.putArray("criteria").add("PVS1").add("PS1_Supporting");
            case "version" -> r.put("originalVersion","2.0");
            case "hash" -> r.put("xmlSha256","0".repeat(64));
            case "scope-tolerance" -> ((ObjectNode)k.at("/policies/0")).put("similarityMode","PUBLISHED_RELATIVE_TEN_PERCENT");
        }
        assertThrows(IllegalArgumentException.class,()->load(k));
    }
    @Test void sameDiseaseConflictingExpertIsNotIgnored() throws Exception {
        var k=input(xml(assertion("SCV000000001","Pathogenic",TEXT)+assertion("SCV000000002","Benign","Conflicting expert finding.")));
        assertEquals("PS1_SPLICE_XML_EXPERT_CONFLICT",assertThrows(IllegalArgumentException.class,()->load(k)).getMessage());
    }
    @Test void diseaseXrefOutsideConditionDoesNotEstablishContext() throws Exception {
        var k=input(xml(assertion("SCV000000001","Pathogenic",TEXT).replace("<TraitSet><Trait>","<Citation>").replace("</Trait></TraitSet>","</Citation>")));
        assertEquals("PS1_SPLICE_XML_DISEASE_MISMATCH",assertThrows(IllegalArgumentException.class,()->load(k)).getMessage());
    }
    @Test void duplicateJsonNotLastValueWins() throws Exception {
        var k=input(xml(assertion("SCV000000001","Pathogenic",TEXT)));String text=m.writeValueAsString(k).replace("\"purpose\":\"PROJECT_RESEARCH\"","\"purpose\":\"PATIENT\",\"purpose\":\"PROJECT_RESEARCH\"");
        Path p=dir.resolve("knowledge.json");Files.writeString(p,text);assertThrows(Exception.class,()->Ps1SpliceKnowledge.load(m,p,CodingTranscriptMaterialSupply.sha(Files.readAllBytes(p))));
    }

    ObjectNode baselineModel() {
        var model=new PublishedCriterionKnowledgeTest().bridgedModel();model.put("transcript","ENST00000000001.1").put("geneSymbol","TEST").put("hgncId","HGNC:1").put("contig","1").put("strand",1).put("cdsLength",9);
        model.withObject("/transcriptBridge").put("ensemblTranscript","ENST00000000001.1");
        ((ObjectNode)model.at("/sourceRecords/1")).put("sourceId","Ensembl:ENST00000000001.1");
        var exons=model.putArray("exons");exons.addObject().put("genomicStart",196).put("genomicEnd",199).put("cdsStart",1).put("cdsEnd",4);
        exons.addObject().put("genomicStart",300).put("genomicEnd",304).put("cdsStart",5).put("cdsEnd",9);return model;
    }
    ObjectNode baselineInput(ObjectNode model) throws Exception {
        String original="For NM_000001.2. The change is c.4+1G>A. "+TEXT;
        String xml=xml(assertion("SCV000000001","Pathogenic",original.replace(">","&gt;"))).replace("</VariationArchive>",
                "<NucleotideExpression sequenceAccessionVersion=\"NM_000001.2\" change=\"c.4+1G&gt;A\"><Expression>NM_000001.2:c.4+1G&gt;A</Expression></NucleotideExpression></VariationArchive>");
        var k=input(xml);((ObjectNode)k.at("/references/0")).put("originalText",original);
        ((ObjectNode)k.at("/policies/0")).put("targetBaselineMode","SOURCE_ORIGINAL_EXPERT_PVS1").put("sourceTranscript","NM_000001.2");
        byte[] raw=m.writeValueAsBytes(model);Files.write(dir.resolve("model.json"),raw);
        k.putArray("transcriptModels").addObject().put("sourceTranscript","NM_000001.2").put("file","model.json").put("sha256",CodingTranscriptMaterialSupply.sha(raw));return k;
    }
    ObjectNode baselineContext(ObjectNode k) {
        var ctx=m.createObjectNode().put("moi","AUTOSOMAL_DOMINANT");ctx.putObject("gene").put("hgncId","HGNC:1").put("symbol","TEST");
        ctx.putObject("disease").put("mondoId","MONDO:1234567");ctx.putObject("variant").set("evidenceQueryAllele",k.at("/references/0/allele").deepCopy());return ctx;
    }
    @Test void exactOriginalTargetAssertionSuppliesBaselineWithoutCreatingPvs1Evaluation() throws Exception {
        var model=baselineModel();var k=baselineInput(model);var loaded=load(k);var before=k.deepCopy();
        var b=loaded.targetBaseline(m,baselineContext(k),model,k.at("/policies/0"));
        assertEquals("PVS1",b.path("strength").asText());assertFalse(b.path("currentPvs1EvaluationCreated").asBoolean());
        assertEquals(k.at("/references/0"),b.at("/assertions/0/original"));assertEquals(before,k);assertFalse(b.has("evaluation"));
    }
    @ParameterizedTest @ValueSource(strings={"OTHER_ALLELE","OTHER_DISEASE","OTHER_MOI","OTHER_TRANSCRIPT","CHANGED_EXON","CHANGED_RNA","CHANGED_CDS","NO_BASELINE_MODE"})
    void targetSourceCannotBeTransferredToOtherIdentities(String mutation) throws Exception {
        var model=baselineModel();var k=baselineInput(model);var loaded=load(k);var ctx=baselineContext(k);var p=(ObjectNode)k.at("/policies/0");
        switch(mutation){
            case "OTHER_ALLELE"->ctx.withObject("/variant/evidenceQueryAllele").put("alternate","T");
            case "OTHER_DISEASE"->ctx.withObject("/disease").put("mondoId","MONDO:7654321");
            case "OTHER_MOI"->ctx.put("moi","AUTOSOMAL_RECESSIVE");
            case "OTHER_TRANSCRIPT"->model.put("transcript","ENST00000000001.2");
            case "CHANGED_EXON"->((ObjectNode)model.at("/exons/0")).put("genomicStart",195);
            case "CHANGED_RNA"->model.withObject("/sequenceVerification").put("fullSplicedTranscriptSha256","f".repeat(64));
            case "CHANGED_CDS"->model.put("cdsSequence","ATGACATAA");
            case "NO_BASELINE_MODE"->p.remove("targetBaselineMode");
        }
        assertThrows(IllegalArgumentException.class,()->loaded.targetBaseline(m,ctx,model,p));
    }
    @Test void sourceHgvsAndOriginalNarrativeAreBothRequired() throws Exception {
        var model=baselineModel();var k=baselineInput(model);var r=(ObjectNode)k.at("/references/0");
        String changed=Files.readString(dir.resolve("source.xml")).replace("c.4+1G&gt;A","c.4+2G&gt;A");
        Files.writeString(dir.resolve("source.xml"),changed);r.put("xmlSha256",CodingTranscriptMaterialSupply.sha(changed));r.put("originalText",r.path("originalText").asText().replace("c.4+1G>A","c.4+2G>A"));
        var loaded=load(k);assertEquals("PS1_SPLICE_BASELINE_SOURCE_TRANSCRIPT_MISMATCH",assertThrows(IllegalArgumentException.class,()->loaded.targetBaseline(m,baselineContext(k),model,k.at("/policies/0"))).getMessage());
    }
}
