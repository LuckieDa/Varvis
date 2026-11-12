package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Hnf1aPm1RuntimeTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Hnf1aPm1Runtime runtime = new Hnf1aPm1Runtime(mapper);
    final JsonNode model;
    Hnf1aPm1RuntimeTest() throws Exception {
        try (var stream = getClass().getResourceAsStream("/acmg/pvs1-hnf1a-transcript-model-v1.json")) {
            assertNotNull(stream); model = mapper.readTree(stream);
        }
    }

    @ParameterizedTest
    @CsvSource({"130,Q", "131,R", "132,E", "143,H", "144,L", "145,S", "146,Q", "147,H", "148,L", "149,N",
            "155,K", "156,T", "157,Q", "158,K", "203,R", "204,F", "205,K", "206,W", "263,R", "264,V", "265,Y",
            "270,N", "271,R", "272,R", "273,K"})
    void officialResiduesMatchSequenceAndOverrideSupportingOnce(int p, String expectedRef) throws Exception {
        var v = atProtein(p); var result = runtime.evaluate(context(v), v);
        assertEquals(expectedRef, result.at("/resolvedInput/refAminoAcid").asText());
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("GN017_DIRECT_DNA_BINDING_RESIDUE", result.at("/resolvedInput/matchedPolicy").asText());
        assertEquals(1, result.at("/evaluation/evidenceIds").size());
        assertEquals(1, runtime.evaluatorCalls()); assertEquals(0, result.path("providerCalls").asInt());
    }
    @Test void completeOfficialResidueSetHasNoOmissionsDuplicatesOrAdditions() {
        var residues = Hnf1aPm1Runtime.loadProfile(mapper).at("/policy/moderateResidues");
        assertEquals(25, residues.size());
        var positions = new java.util.TreeSet<Integer>(); residues.forEach(r -> positions.add(r.path("position").asInt()));
        assertEquals(new java.util.TreeSet<>(List.of(130,131,132,143,144,145,146,147,148,149,155,156,157,158,203,204,205,206,263,264,265,270,271,272,273)), positions);
    }
    @ParameterizedTest
    @CsvSource({"2,SUPPORTING", "11,SUPPORTING", "32,SUPPORTING", "33,NONE", "106,NONE", "107,SUPPORTING", "109,SUPPORTING",
            "174,SUPPORTING", "175,NONE", "200,NONE", "201,SUPPORTING", "280,SUPPORTING", "281,NONE", "400,NONE", "631,NONE"})
    void closedRegionEdgesAndTadExclusion(int position, String strength) throws Exception {
        var v = atProtein(position); var result = runtime.evaluate(context(v), v);
        assertEquals(strength, result.at("/evaluation/appliedStrength").asText());
        assertEquals(strength.equals("NONE") ? "NOT_MET" : "MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("APPLICABLE", result.at("/evaluation/criterionApplicability/status").asText());
    }
    @Test void firstExonUtrAndSplicedCodonAreNotPvs1NmdGates() throws Exception {
        var first = atCoding(31, 'A'); var r = runtime.evaluate(context(first), first);
        assertEquals(120978799, first.getObservation().getKey().getPosition());
        assertEquals("GAG", r.at("/resolvedInput/refCodon").asText());
        assertEquals("K", r.at("/resolvedInput/altAminoAcid").asText());
        assertEquals("SUPPORTING", r.at("/evaluation/appliedStrength").asText());
        // c.325 and c.326 are in exon 1; c.327 is in exon 2, 9,739 bases away.
        var split = atCoding(327, 'C'); var s = runtime.evaluate(context(split), split);
        assertEquals(120988833, split.getObservation().getKey().getPosition());
        assertEquals(109, s.at("/resolvedInput/proteinPosition").asInt());
        assertEquals(2, s.at("/resolvedInput/exonOrdinal").asInt());
        assertEquals("SUPPORTING", s.at("/evaluation/appliedStrength").asText());
    }
    @ParameterizedTest @CsvSource({"hgvsCoding,ENST00000257555.11:c.609G>A,PM1_GENOMIC_CDS_MAPPING_MISMATCH",
            "hgvsProtein,ENSP00000257555.4:p.Arg203His,PM1_PROTEIN_CODON_MISMATCH", "aminoAcids,Q/H,PM1_PROTEIN_CODON_MISMATCH",
            "codons,AAA/ACA,PM1_PROTEIN_CODON_MISMATCH", "proteinPosition,204,PM1_PROTEIN_CODON_MISMATCH",
            "exon,1/10,PM1_EXON_STRUCTURE_MISMATCH", "consequence,stop_gained,PM1_CONSEQUENCE_NOT_APPLICABLE",
            "consequence,missense_variant&splice_acceptor_variant,PM1_SPLICE_MECHANISM_UNRESOLVED"})
    void inconsistentAnnotationIsNeverAnAssumedLocationMatch(String key, String value, String reason) throws Exception {
        var v = atCoding(608, 'A'); var c = context(v); mutate(v, key, value);
        var r = runtime.evaluate(c, v);
        assertEquals("NOT_EVALUATED", r.at("/evaluation/evaluationResult/status").asText());
        assertEquals(reason, r.at("/evaluation/evaluationResult/reasonCode").asText());
    }
    @Test void spliceProximityDoesNotClaimNormalSplicingOrRequireAPredictionScore() throws Exception {
        var v = atCoding(608, 'A'); mutate(v, "consequence", "missense_variant&splice_region_variant");
        var r = runtime.evaluate(context(v), v);
        assertEquals("MODERATE", r.at("/evaluation/appliedStrength").asText());
        assertFalse(r.has("prediction")); assertFalse(r.has("finalClassification"));
    }
    @ParameterizedTest @ValueSource(strings={"PM2_CONTEXT_ONLY", "PM2_ONLY_VERIFIED_CLINICAL_ANNOTATION@1.0.0"})
    void populationOnlyAcknowledgementCannotAuthorizePm1(String policy) throws Exception {
        var v = atProtein(130); var c = context(v); ((ObjectNode)c.path("transcript")).put("selectionPolicy",policy); refresh(c);
        assertEquals("PM1_TRANSCRIPT_CONFIRMATION_REQUIRED", runtime.evaluate(c,v).at("/evaluationResult/reasonCode").asText());
        assertEquals(0, runtime.evaluatorCalls());
    }
    @Test void wrongDiseaseMoiTranscriptAndSourceCannotBeReused() throws Exception {
        var v = atProtein(130); var c = context(v); ((ObjectNode)c.path("disease")).put("mondoId","MONDO:0010894"); refresh(c);
        assertEquals("PM1_POLICY_NOT_APPLICABLE", runtime.evaluate(c,v).at("/evaluationResult/reasonCode").asText());
        c = context(v); c.put("moi","AUTOSOMAL_RECESSIVE"); refresh(c);
        assertEquals("PM1_POLICY_NOT_APPLICABLE", runtime.evaluate(c,v).at("/evaluationResult/reasonCode").asText());
        c = context(v); ((ObjectNode)c.path("transcript")).put("version",10); refresh(c);
        assertEquals("PM1_TRANSCRIPT_NOT_SUPPORTED", runtime.evaluate(c,v).at("/evaluationResult/reasonCode").asText());
        assertEquals("PM1_VEP_SOURCE_IDENTITY_INVALID", runtime.evaluate(context(atProtein(131)),v).at("/evaluationResult/reasonCode").asText());
    }
    @Test void duplicateTranscriptAndMissingCodonsAreExplicitBlockers() throws Exception {
        var v = atProtein(130); var c = context(v); var tx = v.getTranscriptAnnotations().get(0);
        v.applyTranscriptAnnotations(List.of(tx,tx));
        assertEquals("PM1_SELECTED_TRANSCRIPT_NOT_UNIQUE", runtime.evaluate(c,v).at("/evaluationResult/reasonCode").asText());
        v.applyTranscriptAnnotations(List.of(tx)); mutate(v,"codons",null);
        assertEquals("PM1_PROTEIN_CODON_MISMATCH", runtime.evaluate(c,v).at("/evaluation/evaluationResult/reasonCode").asText());
    }
    @Test void deterministicPolicyTraceAndEvaluationIdentities() throws Exception {
        var v=atProtein(130); var c=context(v); var a=runtime.evaluate(c,v); assertEquals(a,runtime.evaluate(c,v));
        var selection=(ObjectNode)a.path("policySelection").deepCopy(); selection.remove("selectionId");
        assertEquals("cspec-selection-sha256-"+adapter.canonicalSha256(selection),a.at("/policySelection/selectionId").asText());
        var evaluation=(ObjectNode)a.path("evaluation").deepCopy(); evaluation.remove(List.of("evaluationId","conflict","review"));
        assertEquals("evaluation-sha256-"+adapter.canonicalSha256(evaluation),a.at("/evaluation/evaluationId").asText());
    }
    @Test void threeFixtureEnvelopesCanBeIndependentlyVerified() throws Exception {
        var rows=mapper.createArrayNode();
        for(var v:List.of(atCoding(335,'T'),atCoding(608,'A'),atCoding(1721,'A'))) rows.add(runtime.evaluate(context(v),v));
        assertEquals("SUPPORTING",rows.get(0).at("/evaluation/appliedStrength").asText());
        assertEquals("MODERATE",rows.get(1).at("/evaluation/appliedStrength").asText());
        assertEquals("NOT_MET",rows.get(2).at("/evaluation/evaluationResult/status").asText());
        String output=System.getProperty("varvis.pm1.fixtures");
        if(output!=null) mapper.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of(output).toFile(),rows);
    }

    VariantInterpretation atProtein(int p) {
        String cds=model.path("cdsSequence").asText(); String codon=cds.substring((p-1)*3,p*3);
        for(int offset=0;offset<3;offset++) for(char alt:"ACGT".toCharArray()) {
            var changed=new StringBuilder(codon); changed.setCharAt(offset,alt);
            char amino=Hnf1aPvs1Runtime.translate(changed.toString());
            if(amino!='*' && amino!=Hnf1aPvs1Runtime.translate(codon)) return atCoding((p-1)*3+offset+1,alt);
        }
        throw new IllegalArgumentException("NO_MISSENSE_FIXTURE");
    }
    VariantInterpretation atCoding(int c,char alt) {
        String cds=model.path("cdsSequence").asText(); int start=(c-1)/3*3,p=start/3+1;
        String codon=cds.substring(start,start+3); var changed=new StringBuilder(codon); changed.setCharAt((c-1)%3,alt);
        String altered=changed.toString(); char refAa=Hnf1aPvs1Runtime.translate(codon),altAa=Hnf1aPvs1Runtime.translate(altered);
        JsonNode exon=null; for(var e:model.path("exons")) if(c>=e.path("cdsStart").asInt()&&c<=e.path("cdsEnd").asInt()) exon=e;
        assertNotNull(exon); int g=exon.path("genomicStart").asInt()+c-exon.path("cdsStart").asInt();
        var key=new NormalizedVariantKey("GRCh38","12",g,cds.substring(c-1,c),Character.toString(alt));
        var v=RuntimeEvidenceV2ProviderWiringTest.variant(key);
        v.applyTranscriptAnnotations(List.of(new TranscriptConsequence("HNF1A","ENSG00000135100","ENST00000257555",
                "ENST00000257555.11:c."+c+key.getReference()+">"+alt,"ENSP00000257555.5:p."+Hnf1aPvs1Runtime.aminoName(refAa)+p+Hnf1aPvs1Runtime.aminoName(altAa),
                "missense_variant","ENSG00000135100","ENST00000257555","Ensembl","MODERATE",exon.path("ordinal").asInt()+"/10",null,
                true,true,false,Integer.toString(p),refAa+"/"+altAa,codon+"/"+altered,null,null,null,"VEP_CACHED","COMPLETED","116.0",Instant.parse("2026-09-10T00:00:00Z"))));
        return v;
    }
    ObjectNode context(VariantInterpretation v) throws Exception {
        var c=RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper,adapter,v.getObservation().getKey(),"ENST00000257555");
        ((ObjectNode)c.path("gene")).put("hgncId","HGNC:11621").put("symbol","HNF1A");
        ((ObjectNode)c.path("disease")).put("mondoId","MONDO:0015967").put("label","monogenic diabetes"); c.put("moi","AUTOSOMAL_DOMINANT");
        ((ObjectNode)c.path("transcript")).put("version",11).put("annotationSourceVersion","116.0"); refresh(c); return c;
    }
    void refresh(ObjectNode c) { c.put("contextId",adapter.canonicalContextId(c)); }
    void mutate(VariantInterpretation v,String key,String value) throws Exception {
        ObjectNode n=mapper.valueToTree(v.getTranscriptAnnotations().get(0)); n.put(key,value);
        n.retain(List.of("intron","consequence","lofFlags","hgvsCoding","hgvsProtein","entrezGeneId","geneSymbol","transcriptAccession","geneId","transcriptId","exon","canonical","timestamp","manePlusClinical","lofFilter","codons","lof","impact","proteinPosition","transcriptSource","status","aminoAcids","source","maneSelect","version"));
        v.applyTranscriptAnnotations(List.of(mapper.treeToValue(n,TranscriptConsequence.class)));
    }
}
