package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Fictional exact backgrounds; fixed sequence expectations live in source fixtures, not in the evaluator. */
public final class Bp1TestFixtures {
    public final ObjectMapper mapper;
    public final RuntimeEvidenceV2Adapter adapter;
    public final ObjectNode model, mechanism, policy, expected;
    public ObjectNode context, annotation;
    public VariantInterpretation candidate;
    public Bp1TestFixtures(ObjectMapper mapper, int strand) throws Exception {
        this.mapper=mapper;adapter=new RuntimeEvidenceV2Adapter(mapper);
        JsonNode fixed=mapper.readTree(bytes("/bp1-test-only-sequences.json"));
        expected=((ObjectNode)Objects.requireNonNull(fixed).path("rows").path(strand==1?0:1)).deepCopy();
        String id=expected.path("id").asText(),cds=expected.path("cds").asText();
        model=mapper.createObjectNode().put("schemaVersion","native-coding-transcript-model-v1")
                .put("modelId","TEST_ONLY_BP1_MODEL_"+id).put("modelVersion",strand==1?"1":"2").put("assembly","GRCh38")
                .put("strand",strand).put("cdsSequence",cds).put("cdsLength",cds.length()).put("proteinLength",expected.path("referenceProtein").asText().length());
        for(String field:List.of("contig","hgncId","geneSymbol","transcript","protein"))model.set(field,expected.path(field));
        model.putArray("exons").addObject().put("ordinal",1).put("genomicStart",100).put("genomicEnd",99+cds.length()).put("cdsStart",1).put("cdsEnd",cds.length());
        JsonNode sourceFacts=Objects.requireNonNull(mapper.readTree(bytes("/bp1-test-only-source-"+id+".json")));
        ObjectNode source=mapper.createObjectNode().put("sourceId","TEST_ONLY_BP1_SOURCE_"+id).put("version","1")
                .put("locator","bp1-test-only-source-"+id+".json#").put("url","https://example.invalid/bp1/"+id)
                .put("sha256",sha(bytes("/bp1-test-only-source-"+id+".json")));
        model.putArray("sourceRecords").add(source.deepCopy());
        policy=mapper.createObjectNode().put("schemaVersion","bp1-reviewed-mechanism-policy-v1").put("policyId","TEST_ONLY_BP1_POLICY_"+id)
                .put("policyVersion",strand==1?"1":"2").put("appliedStrength","SUPPORTING");
        policy.putArray("criterionIds").add(Bp1CriterionEvaluator.CRITERION);
        policy.putObject("review").put("status","REVIEWED_IMPLEMENTED").put("reviewId","TEST_ONLY_NOT_SCIENTIFIC_APPROVAL");
        ObjectNode scope=policy.putObject("scope").put("hgncId",expected.path("hgncId").asText()).put("mondoId",expected.path("mondoId").asText())
                .put("moi",expected.path("moi").asText()).put("assembly","GRCh38").put("variantType","NATIVE_NONPROJECTED_SNV").put("transcriptMode","EXACT_ACCESSIONS");
        String tx=expected.path("transcript").asText();scope.putArray("transcripts").addObject().put("accession",tx.substring(0,tx.lastIndexOf('.'))).put("version",1);
        policy.putArray("scientificSources").add(source.deepCopy()).add(mapper.createObjectNode().put("sourceId","authority-acmg-amp-2015-richards")
                .put("version","2015").put("url","https://pmc.ncbi.nlm.nih.gov/articles/PMC4544753/")
                .put("sha256","7ec25cf2d54ce079251bdcfee92dcbf12b25117ab93998e54e86e8e9186145b3").put("locator","PMCID:PMC4544753 Table 3 BP1"));
        policy.putArray("authoritySourceIds").add("authority-acmg-amp-2015-richards");
        ObjectNode spec=policy.putObject("specification").put("authoritySourceId","authority-acmg-amp-2015-richards").put("version","2015")
                .put("locator","Table 3 BP1; TEST_ONLY scope-specific applicability, not a real approval").put("bp1Allowed",true).put("strengthCap","SUPPORTING");
        spec.put("semantics","GENERAL_MISSENSE_IN_TRUNCATING_DOMINANT_DISEASE"); spec.putArray("extraConditions");
        mechanism=mapper.createObjectNode().put("schemaVersion","bp1-reviewed-mechanism-v1").put("materialId","TEST_ONLY_BP1_MECHANISM_"+id).put("materialVersion","1")
                .put("mechanismStatus","SINGLE_REVIEWED_MECHANISM");
        mechanism.putArray("sourceRecords").add(source.deepCopy());
        mechanism.set("truncatingMechanism",sourceFacts.path("truncatingMechanism").deepCopy());
        ObjectNode applicability=mechanism.putObject("reviewedVariantApplicability");
        applicability.set("exceptions",sourceFacts.path("exceptions").deepCopy());applicability.set("splicing",sourceFacts.path("splicing").deepCopy());
        ObjectNode identity=applicability.putObject("identity").put("transcript",model.path("transcript").asText()).put("protein",model.path("protein").asText())
                .put("hgvsc",tx+":c."+expected.path("coding").asText()).put("hgvsp",model.path("protein").asText()+":p."+expected.path("proteinChange").asText())
                .put("proteinVariantKey",expected.path("proteinVariantKey").asText());
        identity.putObject("allele").put("assembly","GRCh38").put("contig",model.path("contig").asText()).put("position",expected.path("pos").asInt())
                .put("reference",expected.path("ref").asText()).put("alternate",expected.path("alt").asText());
        for(String key:List.of("truncatingMechanism","exceptions","splicing")) row(key).putArray("sourceIds").add(source.path("sourceId").asText());
        bind();reviewConditions();bind();variant();
    }
    public ObjectNode row(String key){return (ObjectNode)(key.equals("truncatingMechanism")?mechanism.path(key):mechanism.path("reviewedVariantApplicability").path(key));}
    private ObjectNode review(String source){ObjectNode r=mapper.createObjectNode().put("status","REVIEWED_APPLICABLE").put("reviewer","TEST_ONLY_FICTIONAL_REVIEWER_NOT_EXPERT")
            .put("evaluatedAt","2026-09-28").put("basis","TEST_ONLY independently stated applicability; no real approval");r.putArray("sourceIds").add(source);return r;}
    public void bind(){
        policy.putObject("transcriptModelBinding").put("modelId",model.path("modelId").asText()).put("modelVersion",model.path("modelVersion").asText()).put("canonicalSha256",adapter.canonicalSha256(model));
        mechanism.put("modelSha256",adapter.canonicalSha256(model));mechanism.set("scope",policy.path("scope").deepCopy());
        for(String key:List.of("assembly","contig","hgncId","transcript","protein"))mechanism.set(key,model.path(key));
        mechanism.withObject("reviewedVariantApplicability").withObject("identity").put("modelSha256",adapter.canonicalSha256(model));
        policy.putObject("mechanismMaterialBinding").put("materialId",mechanism.path("materialId").asText()).put("materialVersion",mechanism.path("materialVersion").asText()).put("canonicalSha256",adapter.canonicalSha256(mechanism));
    }
    public void reviewConditions(){
        for(String key:List.of("truncatingMechanism","exceptions","splicing"))row(key).set("review",review(mechanism.at("/sourceRecords/0/sourceId").asText()));
        for(String key:List.of("truncatingMechanism","exceptions","splicing")){
            // Test-side specification projection; production reviewProjection is never called.
            ObjectNode content=mechanism.deepCopy(),p=policy.deepCopy();p.remove("mechanismMaterialBinding");
            ((ObjectNode)content.at("/truncatingMechanism/review")).remove("contentSha256");
            for(String name:List.of("exceptions","splicing"))((ObjectNode)content.at("/reviewedVariantApplicability/"+name+"/review")).remove("contentSha256");
            content.set("policy",p);content.put("reviewedCondition",key);
            row(key).withObject("review").put("contentSha256",adapter.canonicalSha256(content));
        }
    }
    public void variant() throws Exception {
        String tx=model.path("transcript").asText();
        annotation=mapper.createObjectNode().put("geneSymbol",model.path("geneSymbol").asText()).put("transcriptAccession",tx)
                .put("hgvsCoding",tx+":c."+expected.path("coding").asText()).put("hgvsProtein",model.path("protein").asText()+":p."+expected.path("proteinChange").asText())
                .put("consequence","missense_variant").put("exon","1/1").put("proteinPosition","2").put("aminoAcids",expected.path("aminoAcids").asText())
                .put("codons",expected.path("refCodon").asText()+"/"+expected.path("altCodon").asText()).put("source","VEP").put("status","COMPLETED")
                .put("version","116.0").put("timestamp","2026-09-28T00:00:00Z").put("maneSelect",true);
        var key=new NormalizedVariantKey("GRCh38",model.path("contig").asText(),expected.path("pos").asInt(),expected.path("ref").asText(),expected.path("alt").asText());
        candidate=RuntimeEvidenceV2ProviderWiringTest.variant(key);candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation,TranscriptConsequence.class)));
        context=RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper,adapter,key,tx.substring(0,tx.lastIndexOf('.')));
        context.withObject("gene").put("hgncId",model.path("hgncId").asText()).put("symbol",model.path("geneSymbol").asText());
        context.withObject("disease").put("mondoId",policy.at("/scope/mondoId").asText()).put("label","TEST_ONLY");context.put("moi",policy.at("/scope/moi").asText());
        context.withObject("transcript").put("version",1).put("annotationSourceVersion","116.0");
        context.put("contextId",adapter.canonicalContextId(context));
    }
    public ObjectNode document() throws Exception {
        ObjectNode doc=ReviewedRuleContextPreparationTest.emptyBundleDocument(mapper,"1");
        doc.put("schemaVersion","reviewed-rule-bundle-bp1-v9").put("bundleId","TEST_ONLY_BP1_BUNDLE");doc.withArray("policies").add(policy.deepCopy());
        ObjectNode m=(ObjectNode)doc.path("materials");m.putObject("transcriptModels").set(adapter.canonicalSha256(model),model.deepCopy());
        m.putObject("bp1Mechanisms").set(adapter.canonicalSha256(mechanism),mechanism.deepCopy());
        ObjectNode profile=Hnf1aPvs1Runtime.loadProfile(mapper).deepCopy();profile.retain("resources");profile.put("vepResourceId","VEP_116_GRCH38");
        String resource="/acmg/rule-registry-v2/acmg-rule-registry-bp1-missense-2026-09-28.json";
        profile.putObject("registryBinding").put("registryClasspathResource",resource).put("registryId","acmg-rule-registry-bp1-missense-2026-09-28").put("registrySha256",sha(bytes(resource)));
        m.set("bp1ResourceProfile",profile);ObjectNode catalog=(ObjectNode)doc.path("catalog");catalog.set("sources",mechanism.path("sourceRecords").deepCopy());
        ObjectNode entry=catalog.withArray("entries").addObject().put("entryId","TEST_ONLY_BP1_CLAUSE_"+expected.path("id").asText())
                .put("criterionId",Bp1CriterionEvaluator.CRITERION).put("searchStatus","COMPLETE").put("disposition","NO_APPLICABLE_SPECIALIZATION").put("baseRuleRelationship","TEST_ONLY engineering clause");
        entry.putObject("generalPrerequisites").put("status","COMPLETE").putArray("sourceIds").add(mechanism.at("/sourceRecords/0/sourceId").asText());
        entry.set("scope",policy.path("scope").deepCopy());entry.putObject("review").put("status","REVIEWED").put("reviewId","TEST_ONLY");
        entry.putArray("sourceIds").add(mechanism.at("/sourceRecords/0/sourceId").asText());
        entry.putObject("policyBinding").put("policyId",policy.path("policyId").asText()).put("policyVersion",policy.path("policyVersion").asText()).put("policySha256",adapter.canonicalSha256(policy));
        doc.put("catalogSha256",adapter.canonicalSha256(catalog));return doc;
    }
    private static final java.util.concurrent.atomic.AtomicInteger artifactNumber=new java.util.concurrent.atomic.AtomicInteger();
    public void archiveBeforeEvaluation(ObjectNode document) throws Exception {
        String directory=System.getProperty("varvis.bp1.artifacts");if(directory==null)return;
        java.nio.file.Path out=java.nio.file.Path.of(directory).resolve("pre-evaluation").resolve(Integer.toString(artifactNumber.incrementAndGet()));
        java.nio.file.Files.createDirectories(out);
        ObjectNode receipt=mapper.createObjectNode().put("timing","BEFORE_BUNDLE_EVALUATE");
        Map<String,JsonNode> inputs=new LinkedHashMap<>();inputs.put("bundle",document);inputs.put("context",context);
        inputs.put("candidate",mapper.valueToTree(candidate));inputs.put("independent-sequence-expected",expected);
        for(var entry:inputs.entrySet()){
            byte[] raw=mapper.writeValueAsBytes(entry.getValue());java.nio.file.Files.write(out.resolve(entry.getKey()+".json"),raw);
            ObjectNode item=receipt.putObject(entry.getKey()).put("bytes",raw.length).put("rawSha256",sha(raw));
            if(entry.getKey().equals("candidate"))item.put("canonicalization","NOT_APPLIED_FULL_PUBLISHED_INPUT_USES_RAW_BYTES");
            else item.put("canonicalSha256",adapter.canonicalSha256(entry.getValue()));
        }
        java.nio.file.Files.write(out.resolve("receipt.json"),mapper.writeValueAsBytes(receipt));
    }
    public ObjectNode evaluate() throws Exception {var d=document();archiveBeforeEvaluation(d);return new ReviewedRuleBundle(mapper,d,adapter.canonicalSha256(d)).evaluate(Bp1CriterionEvaluator.CRITERION,context,candidate);}
    private byte[] bytes(String name)throws Exception{try(var input=Objects.requireNonNull(getClass().getResourceAsStream(name))){return input.readAllBytes();}}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
