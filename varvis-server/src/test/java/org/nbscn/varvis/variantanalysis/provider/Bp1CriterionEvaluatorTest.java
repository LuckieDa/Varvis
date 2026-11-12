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

/** Fixed codon expectations and exact reasons distinguish reached gates from unrelated fixture failures. */
class Bp1CriterionEvaluatorTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @ParameterizedTest @ValueSource(ints={1,-1})
    void independentSequencesAndThreeReviews(int strand) throws Exception {
        var f=new Bp1TestFixtures(mapper,strand);var before=f.document();var r=f.evaluate();
        check(f,r,"MET","BP1_REVIEWED_TRUNCATING_MECHANISM_AND_VARIANT_APPLICABILITY","positive-"+strand);
        assertEquals("SUPPORTING",r.at("/evaluation/appliedStrength").asText());assertEquals(1,r.path("sequenceCalculations").asInt());
        assertEquals(strand==1?4:5,r.at("/resolvedInput/cdsPosition").asInt());
        for(String key:List.of("refCodon","altCodon","proteinVariantKey"))assertEquals(f.expected.path(key),r.at("/resolvedInput/"+key));
        assertEquals(before,f.document());assertEquals(f.mechanism,r.path("mechanismMaterial"));
        assertEquals(f.context.at("/variant/evidenceQueryAllele"),r.at("/resolvedInput/allele"));
        assertEquals("GENERAL_POLICY_SELECTED",r.at("/policySelection/outcome").asText());
    }
    @ParameterizedTest @ValueSource(strings={"mechanism","exception"})
    void completeNegativeNeverInfersOtherRule(String fault) throws Exception {
        var f=new Bp1TestFixtures(mapper,1);negative(f,fault);resign(f);
        var r=f.evaluate();check(f,r,"NOT_MET",fault.equals("mechanism")?"BP1_TRUNCATING_MECHANISM_NOT_SUPPORTED":"BP1_MISSENSE_EXCEPTION_APPLIES","negative-"+fault);
        assertEquals("NONE",r.at("/evaluation/appliedStrength").asText());assertEquals(1,r.path("sequenceCalculations").asInt());
        assertFalse(r.toString().contains("ACMG_AMP_2015:PP2"));assertFalse(r.toString().contains("ACMG_AMP_2015:PVS1"));
    }
    @ParameterizedTest @ValueSource(strings={"truncatingMechanism","exceptions","splicing"})
    void IncompleteFactsDominateKnownNegative(String key) throws Exception {
        for(String fault:List.of("UNKNOWN","CONFLICT","review","source","digest")){
            var f=new Bp1TestFixtures(mapper,1);negative(f,key.equals("truncatingMechanism")?"exception":"mechanism");
            String reason;
            if(Set.of("UNKNOWN","CONFLICT").contains(fault)){f.row(key).put("conclusion",fault);reason="BP1_CONDITION_UNKNOWN_OR_CONFLICT";}
            else if(fault.equals("source")){f.row(key).putArray("sourceIds").add("MISSING");reason="BP1_CONDITION_SOURCE_MISSING";}
            else reason=fault.equals("digest")?"BP1_REVIEW_IDENTITY_MISMATCH":"BP1_"+key.toUpperCase(Locale.ROOT)+"_REVIEW_MISSING";
            resign(f);
            if(fault.equals("review"))f.row(key).remove("review");
            if(fault.equals("digest"))f.row(key).withObject("review").put("contentSha256","0".repeat(64));
            f.bind();var r=f.evaluate();check(f,r,"NOT_EVALUATED",reason,"incomplete-"+key+"-"+fault);
            if(fault.equals("UNKNOWN"))assertFalse(r.path("criterionEvaluations").path(0).has("appliedStrength"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"PP2_NOT_MET","PVS1_MET","LOF","LOEUF","REVEL","NO_PATHOGENIC_MISSENSE"})
    void unrelatedSurrogatesCannotApproveTruncatingPredominance(String surrogate) throws Exception {
        var f=new Bp1TestFixtures(mapper,1);f.row("truncatingMechanism").put("basisType",surrogate);resign(f);
        check(f,f.evaluate(),"NOT_EVALUATED","BP1_MECHANISM_BASIS_UNSUPPORTED","surrogate-"+surrogate);
    }
    @ParameterizedTest @ValueSource(strings={"search","domains","splice","method","mixed","GOF","region","emptyExceptions","statistics","bias","source","strength","extra","semantics","disabled","authority"})
    void scientificAndSourceBoundaries(String fault) throws Exception {
        var f=new Bp1TestFixtures(mapper,fault.equals("statistics")||fault.equals("bias")?-1:1);String reason;
        switch(fault){
            case "search" -> {f.row("exceptions").put("searchStatus","UNKNOWN");reason="BP1_EXCEPTION_SEARCH_INCOMPLETE";}
            case "domains" -> {f.row("exceptions").putArray("searchedDomains");reason="BP1_EXCEPTION_SEARCH_INCOMPLETE";}
            case "splice" -> {f.row("splicing").put("conclusion","KNOWN_SPLICE_IMPACT");negative(f,"mechanism");reason="BP1_SPLICE_IMPACT_UNSUPPORTED";}
            case "method" -> {f.row("splicing").put("method","");reason="BP1_SPLICING_REVIEW_INCOMPLETE";}
            case "mixed" -> {f.mechanism.put("mechanismStatus","MIXED_UNRESOLVED");reason="BP1_MECHANISM_UNRESOLVED";}
            case "GOF" -> {negative(f,"exception");((ObjectNode)f.row("exceptions").at("/findings/0")).put("type","GOF_DN_UNRESOLVED");reason="BP1_EXCEPTION_MECHANISM_UNSUPPORTED";}
            case "region" -> {f.row("exceptions").put("conclusion","UNKNOWN");reason="BP1_CONDITION_UNKNOWN_OR_CONFLICT";}
            case "emptyExceptions" -> {f.row("exceptions").put("conclusion","EXCEPTION_APPLIES");reason="BP1_EXCEPTION_FACT_CONFLICT";}
            case "statistics" -> {f.row("truncatingMechanism").withObject("statistics").put("count",13);reason="BP1_STATISTICS_INVALID";}
            case "bias" -> {f.row("truncatingMechanism").withObject("statistics").put("selectionBiasReview","UNKNOWN");reason="BP1_STATISTICS_APPLICABILITY_UNREVIEWED";}
            case "source" -> {((ObjectNode)f.mechanism.at("/sourceRecords/0")).put("sha256","0".repeat(64));reason="BP1_SOURCE_INVALID";}
            case "strength" -> {f.policy.put("appliedStrength","STRONG");reason="BP1_POLICY_INVALID";}
            case "extra" -> {f.policy.withObject("specification").withArray("extraConditions").add("UNIMPLEMENTED");reason="BP1_ADDITIONAL_CONDITIONS_UNSUPPORTED";}
            case "semantics" -> {f.policy.withObject("specification").put("semantics","TRUNCATING_IN_GOF_DISEASE");reason="BP1_ADDITIONAL_CONDITIONS_UNSUPPORTED";}
            case "disabled" -> {f.policy.withObject("specification").put("bp1Allowed",false);reason="BP1_SPECIFICATION_DISABLED_UNSUPPORTED";}
            default -> {f.policy.withObject("specification").put("version","WRONG");reason="BP1_SPECIFICATION_AUTHORITY_INVALID";}
        }
        resign(f);check(f,f.evaluate(),"NOT_EVALUATED",reason,"boundary-"+fault);
    }
    @ParameterizedTest @ValueSource(strings={"allele","transcript","protein","hgvsc","hgvsp","proteinVariantKey","crossBackground","modelSha256"})
    void reviewedApplicabilityRequiresResolvedExactAlleleAndModel(String field) throws Exception {
        var f=new Bp1TestFixtures(mapper,1);ObjectNode id=f.mechanism.withObject("reviewedVariantApplicability").withObject("identity");
        if(field.equals("allele"))id.withObject("allele").put("alternate","T");
        else if(field.equals("crossBackground"))id.setAll((ObjectNode)new Bp1TestFixtures(mapper,-1).mechanism.at("/reviewedVariantApplicability/identity"));
        else id.put(field,"OTHER");
        // Do not bind() before review here: it intentionally refreshes the model identity in ordinary fixtures.
        f.reviewConditions();f.policy.withObject("mechanismMaterialBinding").put("canonicalSha256",f.adapter.canonicalSha256(f.mechanism));
        var r=f.evaluate();check(f,r,"NOT_EVALUATED","BP1_VARIANT_APPLICABILITY_IDENTITY_MISMATCH","applicability-"+field);
        assertEquals(1,r.path("sequenceCalculations").asInt());assertEquals("E2K",r.at("/resolvedInput/proteinVariantKey").asText());
    }
    @Test void sameProteinChangeDifferentNucleotideCannotBorrowReview() throws Exception {
        var f=new Bp1TestFixtures(mapper,-1);
        // TTC -> TTA is F2L via c6 C>A; TTC -> CTC also F2L via c4 T>C. Same protein, distinct allele.
        var identity=f.mechanism.withObject("reviewedVariantApplicability").withObject("identity");
        identity.withObject("allele").put("position",106).put("reference","G").put("alternate","T");
        identity.put("hgvsc","ENST000102.1:c.6C>A").put("hgvsp","ENSP000102.1:p.Phe2Leu").put("proteinVariantKey","F2L");
        f.annotation.put("hgvsCoding","ENST000102.1:c.4T>C").put("hgvsProtein","ENSP000102.1:p.Phe2Leu").put("codons","TTC/CTC").put("aminoAcids","F/L");
        var key=new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey("GRCh38","13",108,"A","G");
        f.context.set("variant",RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper,f.adapter,key,"ENST000102").path("variant"));
        f.context.put("contextId",f.adapter.canonicalContextId(f.context));
        f.candidate=RuntimeEvidenceV2ProviderWiringTest.variant(key);f.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(f.annotation,TranscriptConsequence.class)));resign(f);
        var r=f.evaluate();check(f,r,"NOT_EVALUATED","BP1_VARIANT_APPLICABILITY_IDENTITY_MISMATCH","same-protein-different-nucleotide");
        assertEquals("F2L",r.at("/resolvedInput/proteinVariantKey").asText());assertEquals(1,r.path("sequenceCalculations").asInt());
    }
    @ParameterizedTest @ValueSource(strings={"disease","moi","tx","search","specialized","unimplemented","prerequisite","authority"})
    void generalOnlySelectionDoesNotFallback(String fault) throws Exception {
        var f=new Bp1TestFixtures(mapper,1);ObjectNode d=f.document();ObjectNode entry=(ObjectNode)d.at("/catalog/entries/0");String reason;
        switch(fault){
            case "disease" -> {f.context.withObject("disease").put("mondoId","MONDO:0999999");reason="CLAUSE_SEARCH_SCOPE_NOT_COVERED";}
            case "moi" -> {f.context.put("moi","AUTOSOMAL_RECESSIVE");reason="CLAUSE_SEARCH_SCOPE_NOT_COVERED";}
            case "tx" -> {f.context.withObject("transcript").put("version",2);reason="CLAUSE_SEARCH_SCOPE_NOT_COVERED";}
            case "search" -> {entry.put("searchStatus","INCOMPLETE");reason="CLAUSE_SEARCH_INCOMPLETE";}
            case "specialized" -> {entry.put("disposition","SPECIALIZED_IMPLEMENTED");reason="BP1_SPECIALIZED_POLICY_UNSUPPORTED";}
            case "unimplemented" -> {entry.put("disposition","SPECIALIZED_UNIMPLEMENTED");reason="APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED";}
            case "prerequisite" -> {entry.remove("generalPrerequisites");reason="GENERAL_RULE_PREREQUISITES_MISSING";}
            default -> {f.policy.putArray("authoritySourceIds");resign(f);d=f.document();reason="BP1_SELECTION_OR_AUTHORITY_INVALID";}
        }
        f.context.put("contextId",f.adapter.canonicalContextId(f.context));d.put("catalogSha256",f.adapter.canonicalSha256(d.path("catalog")));
        f.archiveBeforeEvaluation(d);
        var r=new ReviewedRuleBundle(mapper,d,f.adapter.canonicalSha256(d)).evaluate(Bp1CriterionEvaluator.CRITERION,f.context,f.candidate);
        check(f,r,"NOT_EVALUATED",reason,"selector-"+fault);assertFalse(r.has("evaluation"));archive("selector-"+fault+"-actual-document",d);
    }
    @ParameterizedTest @ValueSource(strings={"hgvsCoding","hgvsProtein","codons","reference","splice","stop","synonymous","MNV","indel","projected"})
    void actualSequenceAndRepresentationGates(String fault) throws Exception {
        var f=new Bp1TestFixtures(mapper,1);String reason;
        switch(fault){
            case "hgvsCoding" -> {f.annotation.put("hgvsCoding","ENST000101.1:c.5G>A");reason="BP1_SEQUENCE_GENOMIC_CODING_IDENTITY_MISMATCH";}
            case "hgvsProtein" -> {f.annotation.put("hgvsProtein","ENSP000101.1:p.Glu2Arg");reason="BP1_SEQUENCE_CODING_PROTEIN_IDENTITY_MISMATCH";}
            case "codons" -> {f.annotation.put("codons","GAA/CAA");reason="BP1_SEQUENCE_CODING_PROTEIN_IDENTITY_MISMATCH";}
            case "reference" -> {f.model.put("cdsSequence","ATGCAACTGTAA");resign(f);reason="BP1_SEQUENCE_CDS_REFERENCE_MISMATCH";}
            case "splice" -> {f.annotation.put("consequence","missense_variant&splice_region_variant");reason="BP1_PURE_MISSENSE_SNV_REQUIRED";}
            case "stop" -> {f.annotation.put("consequence","stop_gained");reason="BP1_PURE_MISSENSE_SNV_REQUIRED";}
            case "synonymous" -> {f.annotation.put("consequence","synonymous_variant");reason="BP1_PURE_MISSENSE_SNV_REQUIRED";}
            default -> {
                if(fault.equals("projected")) {
                    JsonNode target=f.context.at("/variant/evidenceQueryAllele").deepCopy();
                    var key=new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey("GRCh37","11",103,"G","A");
                    f.context.set("variant",RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper,f.adapter,key,"ENST000101").path("variant"));
                    f.context.withObject("variant").set("evidenceQueryAllele",target);
                    var mapping=f.context.withObject("variant").withObject("assemblyLineage").put("mappingStatus","MAPPED").putObject("coordinateMapping")
                            .put("method","TEST_ONLY").put("methodVersion","1").put("refAltValidation","MATCH");
                    mapping.set("sourceAllele",f.context.at("/variant/normalizedAllele").deepCopy());mapping.set("targetAllele",target);
                } else {
                    f.expected.put("ref",fault.equals("MNV")?"GA":"G").put("alt",fault.equals("MNV")?"AC":"GA");f.variant();
                    f.context.withObject("variant").put("variantType",fault.equals("MNV")?"MNV":"SMALL_INSERTION");
                }
                f.context.put("contextId",f.adapter.canonicalContextId(f.context));
                f.adapter.validateCompleteContext(f.context);
                check(f,f.evaluate(),"NOT_EVALUATED","POLICY_CONTEXT_SCOPE_MISMATCH","sequence-"+fault);return;
            }
        }
        f.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(f.annotation,TranscriptConsequence.class)));var r=f.evaluate();
        check(f,r,"NOT_EVALUATED",reason,"sequence-"+fault);
        assertEquals(Set.of("splice","stop","synonymous").contains(fault)?0:1,r.path("sequenceCalculations").asInt());
    }
    @Test void everyReviewBindsOtherFactsHeaderExactScopeAndPolicy() throws Exception {
        for(String fault:List.of("allele","reviewer","source","header","policy","splicing")){
            var f=new Bp1TestFixtures(mapper,1);
            switch(fault){
                case "allele" -> f.mechanism.withObject("reviewedVariantApplicability").withObject("identity").withObject("allele").put("position",104);
                case "reviewer" -> f.row("splicing").withObject("review").put("reviewer","OTHER_TEST_ONLY");
                case "source" -> f.row("splicing").put("locator","OTHER_SOURCE_LOCATION");
                case "header" -> f.mechanism.put("materialId","OTHER_ID");
                case "policy" -> f.policy.withObject("specification").put("locator","OTHER_CLAUSE");
                default -> f.row("splicing").put("conclusion","KNOWN_SPLICE_IMPACT");
            }
            f.bind();check(f,f.evaluate(),"NOT_EVALUATED","BP1_REVIEW_IDENTITY_MISMATCH","digest-coverage-"+fault);
        }
    }
    private void negative(Bp1TestFixtures f,String type){
        if(type.equals("mechanism"))f.row("truncatingMechanism").put("conclusion","NOT_SUPPORTED");
        else {var row=f.row("exceptions").put("conclusion","EXCEPTION_APPLIES");row.putArray("findings").addObject().put("type","KNOWN_PATHOGENIC_MISSENSE").put("locator","TEST_ONLY exact pathogenic missense counterevidence").putArray("sourceIds").add(f.mechanism.at("/sourceRecords/0/sourceId").asText());}
    }
    @ParameterizedTest @ValueSource(strings={"model-missing","model-content","mechanism-missing","mechanism-content","model-source","registry"})
    void namedMaterialsCannotEscapeTheirContentPins(String fault)throws Exception{
        var f=new Bp1TestFixtures(mapper,1);ObjectNode d=f.document();String reason;
        String model=f.adapter.canonicalSha256(f.model),material=f.adapter.canonicalSha256(f.mechanism);
        switch(fault){
            case "model-missing" -> {((ObjectNode)d.at("/materials/transcriptModels")).removeAll();reason="BP1_MODEL_MISSING";}
            case "model-content" -> {((ObjectNode)d.at("/materials/transcriptModels/"+model)).put("modelVersion","OTHER");reason="BP1_MODEL_SHA_MISMATCH";}
            case "mechanism-missing" -> {((ObjectNode)d.at("/materials/bp1Mechanisms")).removeAll();reason="BP1_MECHANISM_MISSING";}
            case "mechanism-content" -> {((ObjectNode)d.at("/materials/bp1Mechanisms/"+material)).put("materialVersion","OTHER");reason="BP1_MECHANISM_SHA_MISMATCH";}
            case "model-source" -> {((ObjectNode)f.model.at("/sourceRecords/0")).put("sha256","0".repeat(64));resign(f);d=f.document();reason="BP1_SOURCE_INVALID";}
            default -> {((ObjectNode)d.at("/materials/bp1ResourceProfile/registryBinding")).put("registrySha256","0".repeat(64));reason="BP1_REGISTRY_IDENTITY_INVALID";}
        }
        f.archiveBeforeEvaluation(d);
        var r=new ReviewedRuleBundle(mapper,d,f.adapter.canonicalSha256(d)).evaluate(Bp1CriterionEvaluator.CRITERION,f.context,f.candidate);
        check(f,r,"NOT_EVALUATED",reason,"content-pin-"+fault);archive("content-pin-"+fault+"-actual-document",d);
    }
    private void resign(Bp1TestFixtures f){f.bind();f.reviewConditions();f.bind();}
    private void check(Bp1TestFixtures f,ObjectNode result,String status,String reason,String name)throws Exception{
        archive(name+"-input",f.document());archive(name+"-context",f.context);archive(name+"-annotation",f.annotation);archive(name+"-result",result);
        archive(name+"-expected",mapper.createObjectNode().put("status",status).put("reason",reason));
        var actual=result.has("evaluation")?result.at("/evaluation/evaluationResult"):result.path("evaluationResult");
        assertEquals(status,actual.path("status").asText(),result.toPrettyString());assertEquals(reason,actual.path("reasonCode").asText(),result.toPrettyString());
    }
    private void archive(String name,JsonNode value)throws Exception {String dir=System.getProperty("varvis.bp1.artifacts");if(dir==null)return;Path out=Path.of(dir).resolve("core");Files.createDirectories(out);Files.writeString(out.resolve(name+".json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n");}
}
