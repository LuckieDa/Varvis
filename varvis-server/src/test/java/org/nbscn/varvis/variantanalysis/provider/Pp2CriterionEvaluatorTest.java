package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Exact refusal reasons prove each control reaches the intended gate; no clinical materials. */
class Pp2CriterionEvaluatorTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @ParameterizedTest @ValueSource(ints={1,-1})
    void independentSequenceAndTwoConditions(int strand)throws Exception{
        var f=new Pp2TestFixtures(mapper,strand);var before=f.document();var result=f.evaluate();check(f,result,"MET","PP2_REVIEWED_MECHANISM_AND_LOW_BENIGN_BACKGROUND","positive-"+strand);
        assertEquals("SUPPORTING",result.at("/evaluation/appliedStrength").asText());assertEquals(1,result.path("criterionEvaluations").size());
        assertEquals(5,result.at("/resolvedInput/cdsPosition").asInt());assertEquals(2,result.at("/resolvedInput/proteinPosition").asInt());
        for(String key:List.of("refCodon","altCodon","proteinVariantKey"))assertEquals(f.expected.path(key),result.at("/resolvedInput/"+key));
        assertEquals(f.expected.path("transcript").asText()+":c."+f.expected.path("coding").asText(),result.at("/resolvedInput/hgvsc").asText());
        assertEquals(f.expected.path("protein").asText()+":p."+f.expected.path("proteinChange").asText(),result.at("/resolvedInput/hgvsp").asText());
        assertEquals(before,f.document());assertEquals(f.spectrum.path("conditions"),result.at("/resolvedInput/reviewedConditions"));
        assertEquals(f.adapter.canonicalSha256(f.model),result.at("/resolvedInput/nativeModelSha256").asText());
    }
    @ParameterizedTest @ValueSource(strings={"missenseMechanism","lowBenignMissense"})
    void eachReviewedNegativeIsNotMetNeverBp1(String key)throws Exception{
        var f=new Pp2TestFixtures(mapper,1);f.spectrum.withObject("conditions").withObject(key).put("conclusion","NOT_SUPPORTED");resign(f);
        var r=f.evaluate();check(f,r,"NOT_MET",key.equals("missenseMechanism")?"PP2_MISSENSE_MECHANISM_NOT_SUPPORTED":"PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED","negative-"+key);
        assertEquals("NONE",r.at("/evaluation/appliedStrength").asText());assertFalse(r.toString().contains("ACMG_AMP_2015:BP1"));
    }
    @ParameterizedTest @ValueSource(strings={"missenseMechanism","lowBenignMissense"})
    void unknownConflictUnreviewedOrMissingSourceNeverBecomeNegative(String key)throws Exception{
        for(String fault:List.of("UNKNOWN","CONFLICT","review","source","locator","hash")){
            var f=new Pp2TestFixtures(mapper,1);var row=f.spectrum.withObject("conditions").withObject(key);
            String other=key.equals("missenseMechanism")?"lowBenignMissense":"missenseMechanism";
            f.spectrum.withObject("conditions").withObject(other).put("conclusion","NOT_SUPPORTED");
            String expected;
            if(Set.of("UNKNOWN","CONFLICT").contains(fault)){row.put("conclusion",fault);expected="PP2_CONDITION_UNKNOWN_OR_CONFLICT";}
            else if(fault.equals("source")){row.putArray("sourceIds").add("MISSING");expected="PP2_CONDITION_SOURCE_MISSING";}
            else if(fault.equals("locator")){row.put("clauseLocator","");expected="PP2_CLAUSE_LOCATOR_MISSING";}
            else expected=fault.equals("hash")?"PP2_CONDITION_REVIEW_IDENTITY_MISMATCH":"PP2_CONDITION_REVIEW_MISSING";
            resign(f);
            if(fault.equals("review"))row.withObject("review").put("status","UNREVIEWED");
            if(fault.equals("hash"))row.withObject("review").put("contentSha256","0".repeat(64));f.bind();
            check(f,f.evaluate(),"NOT_EVALUATED",expected,"incomplete-"+key+"-"+fault);
        }
    }
    @ParameterizedTest @ValueSource(strings={"selectionBiasReview","scopeApplicability","population","benignClassificationBasis","denominator","countLocator"})
    void cohortCompletenessAndBiasAreIndependentOfConclusion(String field)throws Exception{
        var f=new Pp2TestFixtures(mapper,-1);var s=f.spectrum.withObject("conditions").withObject("lowBenignMissense").withObject("statistics");String reason;
        if(field.equals("denominator")){s.put("denominator",0);reason="PP2_STATISTICS_INVALID";}
        else if(field.equals("countLocator")){s.remove(List.of("count","denominator"));reason="PP2_STATISTICS_INCOMPLETE";}
        else {s.put(field,"");reason=field.equals("selectionBiasReview")||field.equals("scopeApplicability")?"PP2_STATISTICS_APPLICABILITY_UNREVIEWED":"PP2_STATISTICS_INCOMPLETE";}
        resign(f);check(f,f.evaluate(),"NOT_EVALUATED",reason,"cohort-"+field);
    }
    @ParameterizedTest @ValueSource(strings={"LOEUF","missenseZ","REVEL","AlphaMissense","PM1","NO_BENIGN_RECORDS"})
    void scoresAndRegionalEvidenceAreNotMechanismOrBenignApproval(String surrogate)throws Exception{
        var f=new Pp2TestFixtures(mapper,1);f.spectrum.withObject("conditions").withObject("lowBenignMissense").put("basisType",surrogate);resign(f);
        check(f,f.evaluate(),"NOT_EVALUATED","PP2_CONDITION_BASIS_UNSUPPORTED","surrogate-"+surrogate);
    }
    @ParameterizedTest @ValueSource(strings={"coverage","mechanism","extra","exceptions","exceptionReview","disabled","strength","authorityVersion","model","source"})
    void materialScopeAndScientificConditionsFailClosed(String fault)throws Exception{
        var f=new Pp2TestFixtures(mapper,1);String reason;
        switch(fault){
            case "coverage" -> {f.spectrum.put("coverage","REGION_ONLY");reason="PP2_COVERAGE_UNSUPPORTED";}
            case "mechanism" -> {f.spectrum.put("mechanismStatus","MIXED_UNRESOLVED");reason="PP2_MECHANISM_UNRESOLVED";}
            case "extra" -> {f.policy.withObject("specification").withArray("extraConditions").add("UNIMPLEMENTED_SCIENCE");reason="PP2_ADDITIONAL_CONDITIONS_UNSUPPORTED";}
            case "exceptions" -> {f.policy.withObject("specification").withArray("exceptions").add("UNRESOLVED");reason="PP2_ADDITIONAL_CONDITIONS_UNSUPPORTED";}
            case "exceptionReview" -> {f.policy.withObject("specification").withObject("exceptionReview").put("status","UNKNOWN");reason="PP2_EXCEPTION_REVIEW_MISSING";}
            case "disabled" -> {f.policy.withObject("specification").put("pp2Allowed",false);reason="PP2_SPECIFICATION_DISABLED";}
            case "strength" -> {f.policy.put("appliedStrength","STRONG");reason="PP2_POLICY_INVALID";}
            case "authorityVersion" -> {f.policy.withObject("specification").put("version","wrong");reason="PP2_SPECIFICATION_AUTHORITY_INVALID";}
            case "source" -> {((ObjectNode)f.spectrum.at("/sourceRecords/0")).put("locator","");reason="PP2_SOURCE_INVALID";}
            default -> {f.model.put("cdsSequence","ATGTAAGAATTTTAA");reason="PP2_MODEL_SEQUENCE_INVALID";}
        }
        resign(f);check(f,f.evaluate(),"NOT_EVALUATED",reason,"scope-"+fault);
    }
    @ParameterizedTest @ValueSource(strings={"disease","moi","transcript","search","conflict","specialized","authority"})
    void selectorNeverFallsBackToGeneralOrOldScope(String fault)throws Exception{
        var f=new Pp2TestFixtures(mapper,1);ObjectNode d=f.document();String reason;
        switch(fault){
            case "disease" -> {f.context.withObject("disease").put("mondoId","MONDO:0999999");reason="CLAUSE_SEARCH_SCOPE_NOT_COVERED";}
            case "moi" -> {f.context.put("moi","AUTOSOMAL_RECESSIVE");reason="CLAUSE_SEARCH_SCOPE_NOT_COVERED";}
            case "transcript" -> {f.context.withObject("transcript").put("version",2);reason="CLAUSE_SEARCH_SCOPE_NOT_COVERED";}
            case "search" -> {((ObjectNode)d.at("/catalog/entries/0")).put("searchStatus","INCOMPLETE");reason="CLAUSE_SEARCH_INCOMPLETE";}
            case "conflict" -> {d.withObject("catalog").withArray("entries").add(((ObjectNode)d.at("/catalog/entries/0")).deepCopy().put("entryId","OTHER"));reason="CLAUSE_SELECTION_CONFLICT";}
            case "specialized" -> {((ObjectNode)d.at("/catalog/entries/0")).put("disposition","SPECIALIZED_UNIMPLEMENTED");reason="APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED";}
            default -> {f.policy.putArray("authoritySourceIds");resign(f);d=f.document();reason="PP2_SELECTION_OR_AUTHORITY_INVALID";}
        }
        f.context.put("contextId",f.adapter.canonicalContextId(f.context));d.put("catalogSha256",f.adapter.canonicalSha256(d.path("catalog")));
        var result=new ReviewedRuleBundle(mapper,d,f.adapter.canonicalSha256(d)).evaluate(Pp2CriterionEvaluator.CRITERION,f.context,f.candidate);
        check(f,result,"NOT_EVALUATED",reason,"selector-"+fault);assertFalse(result.has("evaluation"));archive("selector-"+fault+"-actual-bundle",d);
    }
    @ParameterizedTest @ValueSource(strings={"hgvsCoding","hgvsProtein","codons","proteinPosition","splice","reference","sourceVersion"})
    void storedAnnotationAndSequenceParticipate(String fault)throws Exception{
        var f=new Pp2TestFixtures(mapper,1);String reason;
        switch(fault){
            case "splice" -> {f.annotation.put("consequence","missense_variant&splice_region_variant");reason="PP2_PURE_MISSENSE_SNV_REQUIRED";}
            case "reference" -> {f.model.put("cdsSequence","ATGCCAGAATTTTAA");resign(f);reason="PP2_SEQUENCE_CDS_REFERENCE_MISMATCH";}
            case "sourceVersion" -> {f.annotation.put("version","115.0");reason="PP2_SELECTED_TRANSCRIPT_NOT_UNIQUE";}
            default -> {f.annotation.put(fault,"wrong");reason=fault.equals("hgvsCoding")?"PP2_SEQUENCE_GENOMIC_CODING_IDENTITY_MISMATCH":"PP2_SEQUENCE_CODING_PROTEIN_IDENTITY_MISMATCH";}
        }
        f.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(f.annotation,TranscriptConsequence.class)));
        check(f,f.evaluate(),"NOT_EVALUATED",reason,"sequence-"+fault);
    }
    @Test void missingContextAndBadRegistryDoNotInventEvaluationIdentity()throws Exception{
        var f=new Pp2TestFixtures(mapper,1);var d=f.document();var b=new ReviewedRuleBundle(mapper,d,f.adapter.canonicalSha256(d));
        var r=b.evaluate(Pp2CriterionEvaluator.CRITERION,mapper.createObjectNode(),f.candidate);check(f,r,"NOT_EVALUATED","CONTEXT_IDENTITY_INCOMPLETE_OR_INVALID","missing-context");assertFalse(r.has("evaluation"));
        ((ObjectNode)d.at("/materials/pp2ResourceProfile/registryBinding")).put("registrySha256","0".repeat(64));
        r=new ReviewedRuleBundle(mapper,d,f.adapter.canonicalSha256(d)).evaluate(Pp2CriterionEvaluator.CRITERION,f.context,f.candidate);
        check(f,r,"NOT_EVALUATED","PP2_REGISTRY_IDENTITY_INVALID","registry");assertFalse(r.has("evaluation"));archive("registry-actual-bundle",d);
    }
    @ParameterizedTest @ValueSource(strings={"synonymous_variant","stop_gained","start_lost","frameshift_variant"})
    void nonMissenseAnnotationsNeverEnterTheSequenceRule(String consequence)throws Exception{
        var f=new Pp2TestFixtures(mapper,1);f.annotation.put("consequence",consequence);
        f.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(f.annotation,TranscriptConsequence.class)));
        check(f,f.evaluate(),"NOT_EVALUATED","PP2_PURE_MISSENSE_SNV_REQUIRED","effect-"+consequence);
    }
    @ParameterizedTest @ValueSource(strings={"model-missing","model-content","spectrum-missing","spectrum-content","model-source"})
    void changedMaterialCannotReuseItsNameOrOldHash(String fault)throws Exception{
        var f=new Pp2TestFixtures(mapper,1);ObjectNode d=f.document();String reason;
        String modelSha=f.adapter.canonicalSha256(f.model),spectrumSha=f.adapter.canonicalSha256(f.spectrum);
        switch(fault){
            case "model-missing" -> {((ObjectNode)d.at("/materials/transcriptModels")).removeAll();reason="PP2_MODEL_MISSING";}
            case "model-content" -> {((ObjectNode)d.at("/materials/transcriptModels/"+modelSha)).put("modelVersion","changed");reason="PP2_MODEL_SHA_MISMATCH";}
            case "spectrum-missing" -> {((ObjectNode)d.at("/materials/pp2Spectra")).removeAll();reason="PP2_SPECTRUM_MISSING";}
            case "spectrum-content" -> {((ObjectNode)d.at("/materials/pp2Spectra/"+spectrumSha)).put("materialVersion","changed");reason="PP2_SPECTRUM_SHA_MISMATCH";}
            default -> {((ObjectNode)f.model.at("/sourceRecords/0")).put("sha256","0".repeat(64));resign(f);d=f.document();reason="PP2_SOURCE_INVALID";}
        }
        var r=new ReviewedRuleBundle(mapper,d,f.adapter.canonicalSha256(d)).evaluate(Pp2CriterionEvaluator.CRITERION,f.context,f.candidate);
        check(f,r,"NOT_EVALUATED",reason,"identity-"+fault);archive("identity-"+fault+"-actual-bundle",d);
    }
    @ParameterizedTest @ValueSource(strings={"MNV","SMALL_INSERTION"})
    void nonSnvHasACompleteContextButNoMatchingPolicy(String kind)throws Exception{
        var f=new Pp2TestFixtures(mapper,1);f.expected.put("ref",kind.equals("MNV")?"GA":"G").put("alt",kind.equals("MNV")?"AC":"GA");f.variant();
        f.context.withObject("variant").put("variantType",kind);f.context.put("contextId",f.adapter.canonicalContextId(f.context));
        f.adapter.validateCompleteContext(f.context);
        check(f,f.evaluate(),"NOT_EVALUATED","POLICY_CONTEXT_SCOPE_MISMATCH","variant-"+kind);
    }
    private void resign(Pp2TestFixtures f){f.bind();f.reviewConditions();f.bind();}
    private void check(Pp2TestFixtures f,ObjectNode result,String status,String reason,String name)throws Exception{
        archive(name+"-bundle",f.document());archive(name+"-context",f.context);archive(name+"-candidate",mapper.valueToTree(f.candidate));archive(name+"-expected",mapper.createObjectNode().put("status",status).put("reason",reason));archive(name+"-actual",result);
        JsonNode r=result.has("evaluation")?result.at("/evaluation/evaluationResult"):result.path("evaluationResult");
        assertEquals(status,r.path("status").asText(),result.toPrettyString());assertEquals(reason,r.path("reasonCode").asText(),result.toPrettyString());
        if(!status.equals("MET"))assertNotEquals("SUPPORTING",result.at("/evaluation/appliedStrength").asText());
    }
    private void archive(String name,JsonNode value)throws Exception{String dir=System.getProperty("varvis.pp2.artifacts");if(dir==null)return;Path p=Path.of(dir).resolve("core");Files.createDirectories(p);Files.writeString(p.resolve(name+".json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
}
