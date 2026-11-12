package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic contract tests. Real retained source bytes are checked separately by the compiler. */
class Pvs1SourceQualificationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "varvis.bp1.structured.materials", matches = ".+")
    void retainedPublicCatalogProvidesOnlyAnExactScopeFactWithoutRunningPvs1() throws Exception {
        Path materials = Path.of(System.getProperty("varvis.bp1.structured.materials", ""));
        Path qualified = materials.resolve("pvs1-source-qualified-20261016");
        var knowledge = Pvs1KnowledgeCatalog.load(mapper, materials.resolve("eleven-v1/g2p-mechanism-candidates.json"),
                "6399590f4f5fc7d3bd33d9d183633fdf69a3f5bc03582d155e7af0bc605adf67",
                qualified.resolve("knowledge-adoption.json"), "5c8273c94f5fa5ea16ebc4f6722bee0d3c4cb35f296e81455ad6ce2dc46a84d0");
        var material = new Pvs1TranscriptMaterialSupplyTest();
        material.root = root;
        var bundle = ReviewedRuleBundle.loadPvs1Selected(mapper, qualified.resolve("bundle.json"),
                "dcd894558eadd5da854cdb5e00edb15503e6e0b14041d16f254b14f1087d30fb",
                "cc6453ec04ec892c5f57dd6766a8d2de46951f653b66d782ee6f3f86e1a21024",
                new Pvs1TranscriptMaterialSupply(mapper, material.setup("STOP", 1)), knowledge);

        // Synthetic execution context exercises admission against the unchanged public catalog.
        var context = material.data.context.deepCopy();
        context.withObject("/gene").put("hgncId", "HGNC:8582").put("symbol", "PAH");
        context.withObject("/disease").put("mondoId", "MONDO:0009861").put("label", "Phenylketonuria");
        context.withObject("/transcript").put("accession", "ENST00000553106").put("version", 6);
        context.put("moi", "AUTOSOMAL_RECESSIVE");
        context.put("contextId", adapter.canonicalContextId(context));
        var fact = bundle.establishedLofForBp1(context);
        assertNotNull(fact);
        assertEquals("VERIFIED_ESTABLISHED_LOF", fact.path("status").asText());
        assertEquals("MONDO:0009861", fact.at("/scope/mondoId").asText());
        assertEquals(adapter.canonicalSha256(context), fact.path("contextSha256").asText());
        assertFalse(fact.path("mechanismSources").isEmpty());
        assertFalse(fact.has("appliedStrength"));

        context.withObject("/disease").put("mondoId", "MONDO:0021060").put("label", "RASopathy");
        assertNull(bundle.establishedLofForBp1(context), "Reject changed scope with stale identity");
        context.put("contextId", adapter.canonicalContextId(context));
        assertNull(bundle.establishedLofForBp1(context), "Reject changed scope with valid identity");
        assertEquals(0, bundle.pvs1RuntimeCalls());
        assertEquals(0, bundle.pvs1EvaluatorCalls());
    }
    Pvs1KnowledgeCatalog load(ObjectNode facts,ObjectNode use) throws Exception {
        var helper=new Pvs1ReviewedSourceSupplyTest();helper.root=root;return helper.load(facts,use);
    }
    void rewriteSource(ObjectNode facts,String field,String value) {
        new Pvs1ReviewedSourceSupplyTest().rewriteSource(facts,field,value);
    }
    static ObjectNode qualified(com.fasterxml.jackson.databind.ObjectMapper mapper, ObjectNode facts, List<ObjectNode> policies) {
        var use = Pvs1ReviewedSourceSupplyTest.use(mapper, facts, policies);
        use.put("schemaVersion", Pvs1SourceQualification.SCHEMA).put("sourceDataClass", "PUBLIC_REFERENCE");
        var rule = use.putObject("qualificationPolicy").put("policyId", "TEST_ONLY_REUSE")
                .put("version", "1.0.0").put("purpose", "PROJECT_RESEARCH")
                .put("clinicalAcceptance", false).put("createsExpertReview", false);
        rule.putArray("allowedDataClasses").add("SYNTHETIC").add("PRIVACY_BOUNDED_RUNTIME");
        for (int i=0; i<policies.size(); i++) {
            var p=policies.get(i); var e=(ObjectNode)use.at("/entries/"+i);
            e.remove("review");e.put("purpose", "PROJECT_RESEARCH");
            e.set("allowedDataClasses", rule.path("allowedDataClasses").deepCopy());
            var q=e.putObject("qualification").put("method", "UNCHANGED_SOURCE_POLICY_CONDITIONS")
                    .put("reviewWitness", "INLINE_REVIEW_IN_PINNED_ORIGINAL")
                    .put("originalCandidateRawSha256", "a".repeat(64)).put("createsExpertReview", false).put("clinicalAcceptance", false);
            q.set("originalPolicyReview", p.path("review").deepCopy());
            var knowledge=q.putObject("knowledge");
            for (String f:List.of("mechanism", "nmdScope", "escapeScope")) if (p.has(f)) knowledge.set(f,p.path(f).deepCopy());
            knowledge.set("maximumStrength",p.path("appliedStrength").deepCopy());
            var proofs=q.putArray("sourceVerification");
            for(var source:p.path("scientificSources")) proofs.addObject().put("status", "RAW_SHA_MATCH").put("bytes", 42).set("source", source.deepCopy());
        }
        return use;
    }

    @Test void normalResearchReusesKnowledgeAcrossAllelesAndDataDefinedBackgrounds() throws Exception {
        var runtime=new TruncatingNmdPvs1RuntimeTest();
        var first=runtime.nonsense(1,1,31);var second=runtime.nonsense(1,1,199);var third=runtime.nonsense(2,-1,199);
        assertEquals(first.policy,second.policy);
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(first.policy,third.policy));
        var use=qualified(mapper,facts,List.of(first.policy,third.policy));var catalog=load(facts,use);
        for(var data:List.of(first,second,third)) {
            data.context.put("dataClass","PRIVACY_BOUNDED_RUNTIME");
            data.context.put("contextId", adapter.canonicalContextId(data.context));
            data.selection.put("contextId", data.context.path("contextId").asText());
            data.selection.remove("selectionId");
            data.selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(data.selection));
            var result=catalog.assess(data.context,data.policy);
            assertEquals("READY",result.path("status").asText(),result.toPrettyString());
            assertEquals("PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND",result.path("reasonCode").asText());
            assertFalse(result.at("/adoption/qualification/createsExpertReview").asBoolean(true));
            assertFalse(result.path("adoption").has("review"));
            assertEquals("PRIVACY_BOUNDED_RUNTIME",data.context.path("dataClass").asText());
            var evaluated=new TruncatingNmdPvs1Runtime(mapper,runtime.resourceProfile(),data.model)
                    .evaluate(data.context,data.candidate,data.policy,data.selection);
            assertEquals("MET",evaluated.at("/evaluation/evaluationResult/status").asText(),evaluated.toPrettyString());
        }
    }

    @ParameterizedTest @ValueSource(strings={"REVIEW","SOURCE_BYTES","SOURCE_IDENTITY","CONDITIONS","POLICY_PIN","MODEL_PIN","EXON_PIN","USE_SCOPE","DATA_CLASS","SPLICE","RESTRICTED_SOURCE"})
    void qualificationCannotChangeScienceOrHideMissingBytes(String change) throws Exception {
        var d=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31);
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(d.policy));var use=qualified(mapper,facts,List.of(d.policy));
        var e=(ObjectNode)use.at("/entries/0");String reason;
        switch(change) {
            case "REVIEW" -> {((ObjectNode)e.at("/qualification/originalPolicyReview")).put("reviewId","invented");reason="PVS1_SOURCE_QUALIFICATION_ORIGINAL_REVIEW_MISMATCH";}
            case "SOURCE_BYTES" -> {((ObjectNode)e.at("/qualification/sourceVerification/0")).put("bytes",0);reason="PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED";}
            case "SOURCE_IDENTITY" -> {((ObjectNode)e.at("/qualification/sourceVerification/0/source")).put("sha256","0".repeat(64));reason="PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED";}
            case "CONDITIONS" -> {((ObjectNode)e.at("/qualification/knowledge/nmdScope")).put("minimumDistanceToLastJunction",0);reason="PVS1_SOURCE_QUALIFICATION_CONDITIONS_MISMATCH";}
            case "POLICY_PIN","MODEL_PIN","EXON_PIN" -> {e.put(change.equals("POLICY_PIN")?"policySha256":change.equals("MODEL_PIN")?"modelSha256":"exonNmdScopeSha256","0".repeat(64));reason="PVS1_KNOWLEDGE_POLICY_OR_EXON_BINDING_MISMATCH";}
            case "USE_SCOPE" -> {e.put("purpose","CLINICAL");reason="PVS1_KNOWLEDGE_DATA_USE_NOT_QUALIFIED";}
            case "DATA_CLASS" -> {d.context.put("dataClass","CLINICAL");reason="PVS1_KNOWLEDGE_DATA_USE_NOT_QUALIFIED";}
            case "SPLICE" -> {
                d.policy.put("schemaVersion","pvs1-truncating-nmd-policy-v4");e.put("policySha256", adapter.canonicalSha256(d.policy));
                reason="PVS1_SOURCE_QUALIFICATION_SPLICE_MODEL_MISMATCH";
            }
            case "RESTRICTED_SOURCE" -> {d.policy.withObject("review").put("kind","IMPLEMENTER_SOURCE_TRANSLATION_SYNTHETIC_RESEARCH_ONLY");reason="PVS1_SOURCE_QUALIFICATION_ORIGINAL_USE_RESTRICTED";}
            default -> throw new AssertionError(change);
        }
        assertEquals(reason,load(facts,use).assess(d.context,d.policy).path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings={"FABRICATED_REVIEW","EXTRA_FLAG","CLINICAL","NEW_REVIEW","MISSING_PROOF","LEGACY_SCHEMA"})
    void invalidSchemaDoesNotLoad(String change) throws Exception {
        var d=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31);
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(d.policy));var use=qualified(mapper,facts,List.of(d.policy));
        var e=(ObjectNode)use.at("/entries/0");
        switch(change) {
            case "FABRICATED_REVIEW" -> e.putObject("review").put("status","REVIEWED_FOR_PVS1_NMD");
            case "EXTRA_FLAG" -> use.put("ignoreMissingSources",true);
            case "CLINICAL" -> ((ObjectNode)use.path("qualificationPolicy")).put("clinicalAcceptance",true);
            case "NEW_REVIEW" -> ((ObjectNode)e.path("qualification")).put("createsExpertReview",true);
            case "MISSING_PROOF" -> ((ObjectNode)e.path("qualification")).remove("sourceVerification");
            case "LEGACY_SCHEMA" -> use.put("schemaVersion","pvs1-knowledge-adoption-v2");
            default -> throw new AssertionError(change);
        }
        assertThrows(IllegalArgumentException.class,()->load(facts,use));
    }

    @ParameterizedTest @ValueSource(strings={"LOF","NONE","WRONG_MOI","GAIN","WEAK"})
    void noExecutablePolicyShowsAvailableFactsWithoutInventingApplicability(String kind) throws Exception {
        var d=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31);
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(d.policy));String expected;
        switch(kind) {
            case "NONE" -> {rewriteSource(facts,"disease MONDO","MONDO:9999999");expected="PVS1_MECHANISM_SOURCE_NOT_FOUND";}
            case "WRONG_MOI" -> {rewriteSource(facts,"allelic requirement","biallelic_autosomal");expected="PVS1_MECHANISM_INHERITANCE_NOT_COVERED";}
            case "GAIN" -> {rewriteSource(facts,"molecular mechanism","gain of function");expected="PVS1_LOF_SOURCE_NOT_ESTABLISHED";}
            case "WEAK" -> {rewriteSource(facts,"confidence","limited");expected="PVS1_MECHANISM_SOURCE_STRENGTH_REQUIRES_REVIEW";}
            default -> expected="PVS1_SOURCE_POLICY_CONDITIONS_MISSING";
        }
        var use=qualified(mapper,facts,List.of(d.policy));use.putArray("entries");
        var result=load(facts,use).assess(d.context,null);
        assertEquals(expected,result.path("reasonCode").asText());
        assertEquals("UNAVAILABLE",result.path("status").asText());
        assertEquals(3,result.path("missingExecutableConditions").size());
        assertTrue(result.path("backgroundSourceRecords").isArray());
    }

    @Test void completeDataDefinedGeneralClauseUsesTheSameCoreWithoutGeneSpecificImplementation() throws Exception {
        var fixture=new Pvs1NmdBusinessFixture(mapper,false);var doc=fixture.document();
        var d=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31);
        String sequenceSha = CodingTranscriptMaterialSupply.sha(d.model.path("cdsSequence").asText());
        d.model.putObject("sequenceVerification").put("cdsSha256", sequenceSha).put("fullSplicedTranscriptSha256", sequenceSha);
        d.policy.withObject("transcriptModelBinding").put("canonicalSha256", adapter.canonicalSha256(d.model));
        doc.withArray("policies").removeAll().add(d.policy);
        doc.withObject("materials").withObject("transcriptModels").set(adapter.canonicalSha256(d.model),d.model);
        var catalog=doc.withObject("catalog");var e=((ObjectNode)catalog.at("/entries/0")).deepCopy();
        catalog.putArray("entries").add(e);catalog.set("sources",d.policy.path("scientificSources"));
        e.set("scope",d.policy.path("scope"));e.put("disposition","GENERAL_CLAUSE_ALLOWED");
        e.putObject("generalPrerequisites").put("status","COMPLETE").putArray("sourceIds").add("synthetic-source");
        e.putObject("policyBinding").put("policyId",d.policy.path("policyId").asText())
                .put("policyVersion",d.policy.path("policyVersion").asText()).put("policySha256",adapter.canonicalSha256(d.policy));
        doc.put("catalogSha256",adapter.canonicalSha256(catalog));
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,List.of(d.policy));
        var use=qualified(mapper,facts,List.of(d.policy));var bundle=loadBundle(doc,load(facts,use));
        var result=bundle.evaluate("ACMG_AMP_2015:PVS1",d.context,d.candidate);
        assertEquals("GENERAL_POLICY_SELECTED",result.at("/policySelection/outcome").asText(),result.toPrettyString());
        assertEquals("MET",result.at("/evaluation/evaluationResult/status").asText(),result.toPrettyString());
        assertEquals("READY",result.at("/knowledgeAssessment/status").asText());
    }

    @ParameterizedTest @ValueSource(strings={"UNCOVERED","SPECIALIZED_UNIMPLEMENTED","SPECIALIZED_VERSION_CONFLICT"})
    void normalBundleExplainsMissingConditionsButPreservesSpecialistRestrictions(String mode) throws Exception {
        var fixture=new Pvs1NmdBusinessFixture(mapper,false);var doc=fixture.document();
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,fixture.policies);
        var use=qualified(mapper,facts,fixture.policies);use.putArray("entries");var knowledge=load(facts,use);
        var context=new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31).context;
        var policy=fixture.policies.get(0);
        context.withObject("gene").set("hgncId",policy.at("/scope/hgncId"));
        context.withObject("disease").set("mondoId",policy.at("/scope/mondoId"));
        context.set("moi",policy.at("/scope/moi"));
        context.withObject("transcript").set("accession",policy.at("/scope/transcripts/0/accession"));
        context.withObject("transcript").set("version",policy.at("/scope/transcripts/0/version"));
        context.put("contextId",adapter.canonicalContextId(context));
        if (mode.equals("UNCOVERED")) doc.withObject("catalog").putArray("entries");
        else ((ObjectNode)doc.at("/catalog/entries/0")).put("disposition",mode);
        doc.put("catalogSha256",adapter.canonicalSha256(doc.path("catalog")));
        var bundle=loadBundle(doc,knowledge);
        var result=bundle.evaluate("ACMG_AMP_2015:PVS1",context,null);
        String expected=mode.equals("UNCOVERED")?"PVS1_SOURCE_POLICY_CONDITIONS_MISSING":mode.equals("SPECIALIZED_UNIMPLEMENTED")
                ?"APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED":"APPLICABLE_SPECIALIZED_VERSION_CONFLICT";
        assertEquals(expected,result.at("/evaluationResult/reasonCode").asText(),result.toPrettyString());
        assertEquals(3,result.at("/knowledgeAssessment/missingExecutableConditions").size());
    }

    private ReviewedRuleBundle loadBundle(ObjectNode doc,Pvs1KnowledgeCatalog knowledge) throws Exception {
        var manifest=mapper.createObjectNode().put("schemaVersion","portable-native-transcript-model-derivation-v1");var entries=manifest.putArray("entries");
        var models = doc.at("/materials/transcriptModels");
        for (var model : models) {
            String tx = model.path("transcript").asText(); byte[] raw = mapper.writeValueAsBytes(model);
            java.nio.file.Files.write(root.resolve(tx+".model.json"),raw);
            entries.addObject().put("transcript",tx).put("file",tx+".model.json").put("fileBytes",raw.length)
                    .put("fileSha256",CodingTranscriptMaterialSupply.sha(raw)).put("canonicalSha256",adapter.canonicalSha256(model))
                    .set("modelId",model.path("modelId"));
            ((ObjectNode)entries.get(entries.size()-1)).set("modelVersion",model.path("modelVersion"));
        }
        var manifestBytes=mapper.writeValueAsBytes(manifest);java.nio.file.Files.write(root.resolve("manifest.json"),manifestBytes);
        var referenceSha = new TruncatingNmdPvs1RuntimeTest().nonsense(1,1,31).context.at("/variant/normalization/referenceSequenceSha256");
        var supply=new Pvs1TranscriptMaterialSupply(mapper,new CodingTranscriptMaterialSupply(mapper,root,CodingTranscriptMaterialSupply.sha(manifestBytes),
                (c,s,l)->{
                    for(var model:models) if(c.equals(model.path("contig").asText()) && CodingTranscriptMapping.cdsPosition(model,s)>0) {
                        var bases=new StringBuilder();
                        for(int i=s;i<s+l;i++) {
                            int pos=CodingTranscriptMapping.cdsPosition(model,i);
                            bases.append(CodingTranscriptMapping.orientAllele(model,model.path("cdsSequence").asText().substring(pos-1,pos)));
                        }
                        return new CodingTranscriptMaterialSupply.ReferenceSpan(bases.toString(),mapper.createObjectNode().set("expectedSha256", referenceSha));
                    }
                    throw new AssertionError("UNEXPECTED_REFERENCE_REGION");
                }));
        byte[] bytes=mapper.writeValueAsBytes(doc);var path=root.resolve("bundle.json");java.nio.file.Files.write(path,bytes);
        return ReviewedRuleBundle.loadPvs1Selected(mapper,path,CodingTranscriptMaterialSupply.sha(bytes),adapter.canonicalSha256(doc),supply,knowledge);
    }
}
