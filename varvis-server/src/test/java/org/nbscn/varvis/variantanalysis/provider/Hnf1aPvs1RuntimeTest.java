package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Hnf1aPvs1RuntimeTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Hnf1aPvs1Runtime runtime = new Hnf1aPvs1Runtime(mapper);

    @ParameterizedTest @CsvSource({"120989017,511,171,2,CGA,TGA,R", "120993678,685,229,3,CGA,TGA,R"})
    void twoIndependentPublicNonsensePositionsUseDerivedNmd(int g,int c,int p,int exon,String ref,String alt,String aa) throws Exception {
        var candidate = candidate(g,c,p,exon,ref,alt,aa); var context = context(candidate);
        var first = runtime.evaluate(context,candidate);var second = runtime.evaluate(context,candidate);
        assertEquals("MET",first.at("/evaluation/evaluationResult/status").asText());
        assertEquals("VERY_STRONG",first.at("/evaluation/appliedStrength").asText());
        assertEquals(1768-p*3,first.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals("SINGLE_EXACT_MATCH",first.at("/policySelection/outcome").asText());
        assertEquals(first,second);assertEquals(0,first.path("providerCalls").asInt());
        var selection=(ObjectNode)first.path("policySelection").deepCopy();selection.remove("selectionId");
        assertEquals("cspec-selection-sha256-"+adapter.canonicalSha256(selection),first.at("/policySelection/selectionId").asText());
        var evaluation=(ObjectNode)first.path("evaluation").deepCopy();evaluation.remove(List.of("evaluationId","conflict","review"));
        assertEquals("evaluation-sha256-"+adapter.canonicalSha256(evaluation),first.at("/evaluation/evaluationId").asText());
    }

    @ParameterizedTest @CsvSource({"HGNC:1,MONDO:0015967,AUTOSOMAL_DOMINANT", "HGNC:11621,MONDO:0010894,AUTOSOMAL_DOMINANT", "HGNC:11621,MONDO:0015967,AUTOSOMAL_RECESSIVE"})
    void policyRequiresExactGeneDiseaseAndMoi(String gene,String disease,String moi) throws Exception {
        var v=positive();var ctx=context(v);((ObjectNode)ctx.path("gene")).put("hgncId",gene);
        ((ObjectNode)ctx.path("disease")).put("mondoId",disease);ctx.put("moi",moi);refresh(ctx);
        var result=runtime.evaluate(ctx,v);assertEquals("PVS1_POLICY_NOT_APPLICABLE",result.at("/evaluationResult/reasonCode").asText());
        assertEquals("NOT_APPLICABLE",result.at("/criterionApplicability/status").asText());assertEquals(0,runtime.evaluatorCalls());
    }

    @Test void missingOrDuplicatedStoredTranscriptCannotBeEvaluated() throws Exception {
        var v=positive();var ctx=context(v);var t=v.getTranscriptAnnotations().get(0);
        v.applyTranscriptAnnotations(List.of(t,t));assertDisposition(runtime.evaluate(ctx,v),"PVS1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        v.applyTranscriptAnnotations(List.of());assertDisposition(runtime.evaluate(ctx,v),"PVS1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
    }
    @Test void populationOnlyConfirmationCannotAuthorizePvs1ForAnOtherwiseEligibleTranscript() throws Exception {
        var candidate = positive();
        for (String selection : List.of(org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.PM2_CONTEXT_SELECTION,
                "PM2_CONTEXT_ONLY")) {
            var context = context(candidate);
            ((ObjectNode) context.path("transcript")).put("selectionPolicy", selection); refresh(context);
            var result = runtime.evaluate(context, candidate);
            assertDisposition(result, "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED");
            assertEquals("UNKNOWN", result.at("/criterionApplicability/status").asText());
            assertFalse(result.has("evidence")); assertFalse(result.has("evaluation"));
            assertEquals(0, runtime.storedVepProjections()); assertEquals(0, runtime.evaluatorCalls());
        }
        var explicit = context(candidate);
        ((ObjectNode) explicit.path("transcript")).put("selectionPolicy",
                org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.POLICY_ID + "@"
                        + org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.VERSION); refresh(explicit);
        var result = runtime.evaluate(explicit, candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        assertEquals(1, runtime.storedVepProjections()); assertEquals(1, runtime.evaluatorCalls());
    }
    @Test void annotationVersionAndContextIdentityAreRequired() throws Exception {
        var v=positive();var ctx=context(v);((ObjectNode)ctx.path("transcript")).put("version",10);refresh(ctx);
        assertDisposition(runtime.evaluate(ctx,v),"PVS1_TRANSCRIPT_NOT_SUPPORTED");
        ctx.put("contextId","context-sha256-"+"0".repeat(64));assertDisposition(runtime.evaluate(ctx,v),"PVS1_CONTEXT_IDENTITY_INVALID");
    }
    @Test void nativeAssemblyLineageIsRequired() throws Exception {
        var v=positive();var ctx=context(v);((ObjectNode)ctx.at("/variant/assemblyLineage")).put("mappingStatus","MAPPED");
        var result=runtime.evaluate(ctx,v);assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());
        assertEquals(0,runtime.evaluatorCalls());
    }
    @ParameterizedTest @CsvSource({"hgvsCoding,ENST00000257555.11:c.512C>T,PVS1_GENOMIC_CDS_MAPPING_MISMATCH", "exon,3/10,PVS1_EXON_STRUCTURE_MISMATCH", "codons,CAA/TAA,PVS1_PROTEIN_CODON_MISMATCH", "hgvsProtein,ENSP00000257555.4:p.Arg171Ter,PVS1_PROTEIN_CODON_MISMATCH", "aminoAcids,Q/*,PVS1_PROTEIN_CODON_MISMATCH", "proteinPosition,172,PVS1_PROTEIN_CODON_MISMATCH", "consequence,stop_gained&splice_region_variant,PVS1_SPLICE_BRANCH_NOT_IMPLEMENTED"})
    void inconsistentOrOutOfScopeAnnotationsNeverProduceMet(String field,String value,String reason) throws Exception {
        var v=positive();var ctx=context(v);var json=(ObjectNode)mapper.valueToTree(v.getTranscriptAnnotations().get(0));json.put(field,value);
        v.applyTranscriptAnnotations(List.of(annotation(json)));
        var result=runtime.evaluate(ctx,v);assertEquals("NOT_EVALUATED",result.at("/evaluation/evaluationResult/status").asText());
        assertEquals(reason,result.at("/evaluation/evaluationResult/reasonCode").asText());assertEquals("NONE",result.at("/evaluation/appliedStrength").asText());
    }
    @Test void missingCodonsDoNotBecomeAssumedStopGain() throws Exception {
        var v=positive();var ctx=context(v);var json=(ObjectNode)mapper.valueToTree(v.getTranscriptAnnotations().get(0));json.putNull("codons");
        v.applyTranscriptAnnotations(List.of(annotation(json)));
        assertEquals("PVS1_PROTEIN_CODON_MISMATCH",runtime.evaluate(ctx,v).at("/evaluation/evaluationResult/reasonCode").asText());
    }
    @Test void nonStopGainLabelCannotOverrideTheSequence() throws Exception {
        var v=candidate(120989017,511,171,2,"CGA","GGA","R");
        assertEquals("PVS1_STOP_GAIN_NOT_CONFIRMED",runtime.evaluate(context(v),v).at("/evaluation/evaluationResult/reasonCode").asText());
    }
    @Test void differentAlleleCannotReuseStoredAnnotation() throws Exception {
        var v=positive();var other=candidate(120993678,685,229,3,"CGA","TGA","R");
        assertDisposition(runtime.evaluate(context(other),v),"PVS1_VEP_SOURCE_IDENTITY_INVALID");
    }
    @Test void preservesOldFixedPointAndBusinessSuccessorIdentities() throws Exception {
        assertNotEquals(new Pvs1CriterionEvaluator(mapper).getProfileSha256(),Hnf1aPvs1Runtime.PROFILE_SHA256);
        assertTrue(new AcmgExecutionProfile(mapper).criteria().contains(Hnf1aPvs1Runtime.CRITERION));
        assertFalse(Hnf1aPvs1Runtime.loadProfile(mapper).at("/activation/classificationEnabled").asBoolean(true));
    }
    @ParameterizedTest @CsvSource({"120999567,1708,570,9,MET", "120999585,1726,576,9,NOT_EVALUATED", "121001169,1873,625,10,NOT_EVALUATED"})
    void canonicalNonsensePositionsStraddleNmdAndTerminalBranches(int g,int c,int p,int exon,String expected) throws Exception {
        var v=candidate(g,c,p,exon,"CAG","TAG","Q");var r=runtime.evaluate(context(v),v);
        assertEquals(expected,r.at("/evaluation/evaluationResult/status").asText());
        if (!"MET".equals(expected)) assertEquals("PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED",r.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals(1768-p*3,r.at("/resolvedInput/distanceToLastJunction").asInt());
    }
    private void assertDisposition(ObjectNode result,String reason) {
        assertEquals("NOT_EVALUATED",result.at("/evaluationResult/status").asText());
        assertEquals(reason,result.at("/evaluationResult/reasonCode").asText());
    }
    @Test void upstreamOfInternalMet118RemainsUnimplemented() throws Exception {
        var v=candidate(120978799,31,11,1,"GAG","TAG","E");
        var result=runtime.evaluate(context(v),v);
        assertEquals("NOT_EVALUATED",result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("PVS1_START_PROXIMAL_BRANCH_NOT_IMPLEMENTED",result.at("/evaluation/evaluationResult/reasonCode").asText());
    }
    private TranscriptConsequence annotation(ObjectNode json) throws Exception {
        // Jackson exposes derived getters too; the constructor accepts only the persisted fields.
        json.retain(List.of("intron", "consequence", "lofFlags", "hgvsCoding", "hgvsProtein", "entrezGeneId",
                "geneSymbol", "transcriptAccession", "geneId", "transcriptId", "exon", "canonical", "timestamp",
                "manePlusClinical", "lofFilter", "codons", "lof", "impact", "proteinPosition", "transcriptSource",
                "status", "aminoAcids", "source", "maneSelect", "version"));
        return mapper.treeToValue(json, TranscriptConsequence.class);
    }
    VariantInterpretation positive() {return candidate(120989017,511,171,2,"CGA","TGA","R");}
    VariantInterpretation candidate(int g,int c,int p,int exon,String refCodon,String altCodon,String aa) {
        int offset=(c-1)%3;
        var key=new NormalizedVariantKey("GRCh38","12",g,refCodon.substring(offset,offset+1),altCodon.substring(offset,offset+1));
        var v=RuntimeEvidenceV2ProviderWiringTest.variant(key);
        v.applyTranscriptAnnotations(List.of(new TranscriptConsequence("HNF1A","ENSG00000135100","ENST00000257555",
                "ENST00000257555.11:c."+c+key.getReference()+">"+key.getAlternate(),"ENSP00000257555.5:p."+Hnf1aPvs1Runtime.aminoName(aa.charAt(0))+p+"Ter",
                "stop_gained","ENSG00000135100","ENST00000257555","Ensembl","HIGH",exon+"/10",null,
                true,true,false,Integer.toString(p),aa+"/*",refCodon+"/"+altCodon,null,null,null,"VEP_CACHED","COMPLETED","116.0",Instant.parse("2026-09-09T00:00:00Z"))));
        return v;
    }
    ObjectNode context(VariantInterpretation v) throws Exception {
        var ctx=RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper,adapter,v.getObservation().getKey(),"ENST00000257555");
        ((ObjectNode)ctx.path("gene")).put("hgncId","HGNC:11621").put("symbol","HNF1A");
        ((ObjectNode)ctx.path("disease")).put("mondoId","MONDO:0015967").put("label","monogenic diabetes");ctx.put("moi","AUTOSOMAL_DOMINANT");
        ((ObjectNode)ctx.path("transcript")).put("version",11).put("annotationSourceVersion","116.0");refresh(ctx);return ctx;
    }
    void refresh(ObjectNode ctx) {ctx.put("contextId",adapter.canonicalContextId(ctx));}
}
