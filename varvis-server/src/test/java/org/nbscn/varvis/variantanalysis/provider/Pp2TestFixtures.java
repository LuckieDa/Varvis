package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Fictional exact backgrounds; fixed sequence expectations live in source fixtures, not in the evaluator. */
public final class Pp2TestFixtures {
    public final ObjectMapper mapper;
    public final RuntimeEvidenceV2Adapter adapter;
    public final ObjectNode model, spectrum, policy, expected;
    public ObjectNode context, annotation;
    public VariantInterpretation candidate;
    public Pp2TestFixtures(ObjectMapper mapper, int strand) throws Exception {
        this.mapper=mapper;adapter=new RuntimeEvidenceV2Adapter(mapper);
        JsonNode fixed=mapper.readTree(bytes("/pp2-test-only-sequences.json"));
        expected=((ObjectNode)Objects.requireNonNull(fixed).path("rows").path(strand==1?0:1)).deepCopy();
        String id=expected.path("id").asText(),cds=expected.path("cds").asText();
        model=mapper.createObjectNode().put("schemaVersion","native-coding-transcript-model-v1")
                .put("modelId","TEST_ONLY_PP2_MODEL_"+id).put("modelVersion",strand==1?"1":"2").put("assembly","GRCh38")
                .put("strand",strand).put("cdsSequence",cds).put("cdsLength",cds.length()).put("proteinLength",expected.path("referenceProtein").asText().length());
        for(String field:List.of("contig","hgncId","geneSymbol","transcript","protein"))model.set(field,expected.path(field));
        model.putArray("exons").addObject().put("ordinal",1).put("genomicStart",100).put("genomicEnd",99+cds.length()).put("cdsStart",1).put("cdsEnd",cds.length());
        JsonNode sourceFacts=Objects.requireNonNull(mapper.readTree(bytes("/pp2-test-only-source-"+id+".json")));
        ObjectNode source=mapper.createObjectNode().put("sourceId","TEST_ONLY_PP2_SOURCE_"+id).put("version","1")
                .put("locator","pp2-test-only-source-"+id+".json#/conditions").put("url","https://example.invalid/pp2/"+id)
                .put("sha256",sha(bytes("/pp2-test-only-source-"+id+".json")));
        model.putArray("sourceRecords").add(source.deepCopy());
        policy=mapper.createObjectNode().put("schemaVersion","pp2-reviewed-missense-policy-v1").put("policyId","TEST_ONLY_PP2_POLICY_"+id)
                .put("policyVersion",strand==1?"1":"2").put("appliedStrength","SUPPORTING");
        policy.putArray("criterionIds").add(Pp2CriterionEvaluator.CRITERION);
        policy.putObject("review").put("status","REVIEWED_IMPLEMENTED").put("reviewId","TEST_ONLY_NOT_SCIENTIFIC_APPROVAL");
        ObjectNode scope=policy.putObject("scope").put("hgncId",expected.path("hgncId").asText()).put("mondoId",expected.path("mondoId").asText())
                .put("moi",expected.path("moi").asText()).put("assembly","GRCh38").put("variantType","NATIVE_NONPROJECTED_SNV").put("transcriptMode","EXACT_ACCESSIONS");
        String tx=expected.path("transcript").asText();scope.putArray("transcripts").addObject().put("accession",tx.substring(0,tx.lastIndexOf('.'))).put("version",1);
        policy.putArray("scientificSources").add(source.deepCopy()).add(mapper.createObjectNode().put("sourceId","authority-acmg-amp-2015-richards")
                .put("version","2015").put("url","https://pmc.ncbi.nlm.nih.gov/articles/PMC4544753/")
                .put("sha256","7ec25cf2d54ce079251bdcfee92dcbf12b25117ab93998e54e86e8e9186145b3").put("locator","PMCID:PMC4544753 Table 3 PP2"));
        policy.putArray("authoritySourceIds").add("authority-acmg-amp-2015-richards");
        ObjectNode spec=policy.putObject("specification").put("authoritySourceId","authority-acmg-amp-2015-richards").put("version","2015")
                .put("locator","Table 3 PP2; TEST_ONLY scope-specific applicability, not a real approval").put("pp2Allowed",true).put("strengthCap","SUPPORTING");
        spec.putArray("exceptions");spec.putArray("extraConditions");spec.set("exceptionReview",review(source.path("sourceId").asText(),"REVIEWED_NO_APPLICABLE_EXCEPTIONS"));
        spectrum=mapper.createObjectNode().put("schemaVersion","pp2-reviewed-spectrum-v1").put("materialId","TEST_ONLY_PP2_SPECTRUM_"+id).put("materialVersion","1")
                .put("coverage","WHOLE_GENE_EXACT_DISEASE_MOI_TRANSCRIPT").put("mechanismStatus","SINGLE_REVIEWED_MECHANISM");
        spectrum.putArray("sourceRecords").add(source.deepCopy());ObjectNode conditions=spectrum.putObject("conditions");
        for(String key:List.of("missenseMechanism","lowBenignMissense")){
            ObjectNode row=conditions.putObject(key).put("conclusion",sourceFacts.at("/conditions/"+key+"/conclusion").asText()).put("basisType",expected.path("basisType").asText());
            row.putArray("sourceIds").add(source.path("sourceId").asText());
            if(strand==1)row.put("clauseLocator",sourceFacts.at("/conditions/"+key+"/locator").asText());
            else row.set("statistics",sourceFacts.at("/conditions/"+key+"/statistics").deepCopy());
        }
        bind();reviewConditions();bind();variant();
    }
    private ObjectNode review(String source,String status){ObjectNode r=mapper.createObjectNode().put("status",status).put("reviewer","TEST_ONLY_FICTIONAL_REVIEWER_NOT_EXPERT")
            .put("evaluatedAt","2026-09-28").put("basis","TEST_ONLY independently stated applicability and limitations; no real approval");r.putArray("sourceIds").add(source);return r;}
    public void bind(){
        policy.putObject("transcriptModelBinding").put("modelId",model.path("modelId").asText()).put("modelVersion",model.path("modelVersion").asText()).put("canonicalSha256",adapter.canonicalSha256(model));
        spectrum.put("modelSha256",adapter.canonicalSha256(model));spectrum.set("scope",policy.path("scope").deepCopy());
        for(String key:List.of("assembly","contig","hgncId","transcript","protein"))spectrum.set(key,model.path(key));
        policy.putObject("spectrumMaterialBinding").put("materialId",spectrum.path("materialId").asText()).put("materialVersion",spectrum.path("materialVersion").asText()).put("canonicalSha256",adapter.canonicalSha256(spectrum));
    }
    public void reviewConditions(){
        for(String key:List.of("missenseMechanism","lowBenignMissense")){
            // Independently construct the documented review projection; never ask the SUT to approve its own input.
            ObjectNode content=spectrum.deepCopy();content.remove("conditions");ObjectNode p=policy.deepCopy();p.remove("spectrumMaterialBinding");
            ObjectNode row=(ObjectNode)spectrum.path("conditions").path(key),facts=row.deepCopy();facts.remove("review");
            content.set("policy",p);content.put("condition",key);content.set("facts",facts);
            row.set("review",review(spectrum.at("/sourceRecords/0/sourceId").asText(),"REVIEWED_APPLICABLE").put("contentSha256",adapter.canonicalSha256(content)));
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
        doc.put("schemaVersion","reviewed-rule-bundle-pp2-v8").put("bundleId","TEST_ONLY_PP2_BUNDLE");doc.withArray("policies").add(policy.deepCopy());
        ObjectNode m=(ObjectNode)doc.path("materials");m.putObject("transcriptModels").set(adapter.canonicalSha256(model),model.deepCopy());
        m.putObject("pp2Spectra").set(adapter.canonicalSha256(spectrum),spectrum.deepCopy());
        ObjectNode profile=Hnf1aPvs1Runtime.loadProfile(mapper).deepCopy();profile.retain("resources");profile.put("vepResourceId","VEP_116_GRCH38");
        String resource="/acmg/rule-registry-v2/acmg-rule-registry-pp2-missense-2026-09-28.json";
        profile.putObject("registryBinding").put("registryClasspathResource",resource).put("registryId","acmg-rule-registry-pp2-missense-2026-09-28").put("registrySha256",sha(bytes(resource)));
        m.set("pp2ResourceProfile",profile);ObjectNode catalog=(ObjectNode)doc.path("catalog");catalog.set("sources",spectrum.path("sourceRecords").deepCopy());
        ObjectNode entry=catalog.withArray("entries").addObject().put("entryId","TEST_ONLY_PP2_CLAUSE_"+expected.path("id").asText())
                .put("criterionId",Pp2CriterionEvaluator.CRITERION).put("searchStatus","COMPLETE").put("disposition","SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship","TEST_ONLY engineering clause");
        entry.set("scope",policy.path("scope").deepCopy());entry.putObject("review").put("status","REVIEWED").put("reviewId","TEST_ONLY");
        entry.putArray("sourceIds").add(spectrum.at("/sourceRecords/0/sourceId").asText());
        entry.putObject("policyBinding").put("policyId",policy.path("policyId").asText()).put("policyVersion",policy.path("policyVersion").asText()).put("policySha256",adapter.canonicalSha256(policy));
        doc.put("catalogSha256",adapter.canonicalSha256(catalog));return doc;
    }
    public ObjectNode evaluate() throws Exception {var d=document();return new ReviewedRuleBundle(mapper,d,adapter.canonicalSha256(d)).evaluate(Pp2CriterionEvaluator.CRITERION,context,candidate);}
    private byte[] bytes(String name)throws Exception{try(var input=Objects.requireNonNull(getClass().getResourceAsStream(name))){return input.readAllBytes();}}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
