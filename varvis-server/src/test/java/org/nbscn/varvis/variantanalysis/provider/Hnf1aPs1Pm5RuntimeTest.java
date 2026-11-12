package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Hnf1aPs1Pm5RuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode context;
    private ObjectNode audit;
    private ObjectNode rna;
    private TranscriptConsequence selected;
    private final AtomicInteger rawCalls = new AtomicInteger();
    private double targetScore = 0.01;
    private double referenceScore = 0.02;
    private boolean rawMissing;

    @BeforeEach void setup() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json")) {
            assertNotNull(in); JsonNode doc = mapper.readTree(in); assertNotNull(doc); context = (ObjectNode) doc.path("context").deepCopy();
        }
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0015967");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("accession", "ENST00000257555").put("version", 11).put("annotationSourceVersion", "116.0");
        ((ObjectNode) context.path("variant")).put("variantType", "SNV").putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        audit = Hnf1aPs1Pm5Runtime.read(mapper, "reference-audit.json"); rna = Hnf1aPs1Pm5Runtime.read(mapper, "rna-review.json");
        target(false); rawCalls.set(0);
    }

    private void target(boolean pm5) throws Exception {
        allele().put("assembly", "GRCh38").put("contig", "12").put("position", pm5 ? 120993611 : 120978826).put("reference", "G").put("alternate", "C");
        selected = tx(pm5 ? "c.618G>C" : "c.58G>C", pm5 ? "Trp206Cys" : "Gly20Arg", pm5 ? "3/10" : "1/10", pm5 ? "206" : "20", pm5 ? "W/C" : "G/R", pm5 ? "tgG/tgC" : "Ggg/Cgg");
    }
    private TranscriptConsequence tx(String c, String p, String exon, String pos, String aa, String codons) throws Exception {
        return mapper.treeToValue(mapper.createObjectNode().put("geneSymbol", "HNF1A").put("transcriptAccession", Hnf1aPs1Pm5Runtime.TRANSCRIPT)
                .put("transcriptId", Hnf1aPs1Pm5Runtime.TRANSCRIPT).put("hgvsCoding", Hnf1aPs1Pm5Runtime.TRANSCRIPT + ":" + c)
                .put("hgvsProtein", "ENSP00000257555.5:p." + p).put("consequence", "missense_variant").put("source", "VEP")
                .put("version", "116.0").put("status", "COMPLETED").put("exon", exon).put("proteinPosition", pos).put("aminoAcids", aa).put("codons", codons), TranscriptConsequence.class);
    }
    private ObjectNode allele() { return (ObjectNode) context.at("/variant/evidenceQueryAllele"); }
    private ObjectNode ref(boolean pm5) { return (ObjectNode) audit.path("references").path(pm5 ? 1 : 0); }

    @Test void independentPReferenceGivesPs1StrongWithoutRevelOrAmInput() {
        ObjectNode result = assess(atomic(false));
        assertEquals("MET", status(result, "PS1")); assertEquals("STRONG", eval(result, "PS1").path("appliedStrength").asText());
        assertReason(result, "PM5", "PS1_PM5_OVERLAPPING_SUPPORT_NOT_INDEPENDENT");
        assertEquals(2, result.path("spliceEvidence").size()); assertEquals(1, rawCalls.get());
        assertEquals("SINGLE_SELECTABLE_MATCH", result.at("/selection/outcome").asText());
        assertFalse(result.at("/policy/implementation/revelOrAmRequired").asBoolean(true));
        assertEquals(125, result.at("/references/0/targetGrantham").asInt());
    }
    @Test void lpReferenceGivesPm5SupportingAndActualGrantham() throws Exception {
        target(true); ObjectNode result = assess(atomic(true));
        assertEquals("MET", status(result, "PM5")); assertEquals("SUPPORTING", eval(result, "PM5").path("appliedStrength").asText());
        assertEquals("NOT_MET", status(result, "PS1"));
        assertEquals(215, result.at("/references/0/targetGrantham").asInt()); assertEquals(101, result.at("/references/0/referenceGrantham").asInt());
    }
    @Test void verifiedCachedVepUsesSameStrictIdentityGates() throws Exception {
        selected=selected.withAnnotationMetadata("VEP_CACHED","COMPLETED","116.0",java.time.Instant.parse("2026-09-11T01:00:00Z"));
        assertEquals("MET",status(assess(atomic(false)),"PS1"));
        selected=selected.withAnnotationMetadata("VEP_CACHED","COMPLETED","115.0",java.time.Instant.parse("2026-09-11T01:00:00Z"));
        assertReason(assess(atomic(false)),"PS1","SELECTED_TRANSCRIPT_VERSION_MISMATCH");
    }
    @Test void cachedVepStillRequiresExactProteinAndDoesNotAcceptArbitrarySources() throws Exception {
        selected=tx("c.58G>C","Gly20Trp","1/10","20","G/R","Ggg/Cgg")
                .withAnnotationMetadata("VEP_CACHED","COMPLETED","116.0",java.time.Instant.parse("2026-09-11T01:00:00Z"));
        assertReason(assess(atomic(false)),"PS1","HNF1A_PROTEIN_CODON_MISMATCH");
        selected=selected.withAnnotationMetadata("VEP_UNVERIFIED","COMPLETED","116.0",java.time.Instant.parse("2026-09-11T01:00:00Z"));
        assertReason(assess(atomic(false)),"PS1","SELECTED_TRANSCRIPT_VERSION_MISMATCH");
    }
    @Test void ps1ModerateIsCarriedThroughExistingEvaluator() {
        ref(false).put("classification", "Likely pathogenic"); ObjectNode result = assess(atomic(false));
        assertEquals("MET", status(result, "PS1")); assertEquals("MODERATE", eval(result, "PS1").path("appliedStrength").asText());
    }
    @Test void pm5ModerateIsCarriedThroughExistingEvaluator() throws Exception {
        target(true); ref(true).put("classification", "Pathogenic");
        assertEquals("MODERATE", eval(assess(atomic(true)), "PM5").path("appliedStrength").asText());
    }
    @ParameterizedTest @CsvSource({"PS1,Pathogenic,125,125,STRONG", "PS1,Likely pathogenic,125,125,MODERATE",
            "PM5,Pathogenic,124,125,SUPPORTING", "PM5,Pathogenic,125,125,MODERATE", "PM5,Pathogenic,126,125,MODERATE",
            "PM5,Likely pathogenic,124,125,SUPPORTING", "PM5,Likely pathogenic,125,125,SUPPORTING", "PM5,Likely pathogenic,126,125,SUPPORTING"})
    void strengthBoundaries(String code, String classification, int target, int reference, String expected) {
        assertEquals(expected, Hnf1aPs1Pm5Runtime.strength(code, classification, target, reference));
    }
    @Test void matrixIsSymmetricWithNoDistanceBetweenIdenticalResidues() {
        var runtime = runtime();
        for (char a : "ARNDCQEGHILKMFPSTWYV".toCharArray()) for (char b : "ARNDCQEGHILKMFPSTWYV".toCharArray())
            assertEquals(runtime.distance(""+a, ""+b), runtime.distance(""+b, ""+a));
        assertEquals(0, runtime.distance("G", "G"));
        assertThrows(IllegalArgumentException.class, () -> runtime.distance("X", "G"));
    }
    @Test void noAuditedReferenceIsNotEvaluated() { assertReason(assess(), "PS1", "INDEPENDENT_REFERENCE_EVIDENCE_MISSING"); assertEquals(0, rawCalls.get()); }
    @ParameterizedTest @ValueSource(strings={"PS1", "PS1_Moderate", "PM5", "PM5_Supporting"})
    void circularReferenceCannotBeRescored(String code) { ref(false).withArray("independentCriteria").add(code); assertReason(assess(atomic(false)), "PS1", "REFERENCE_NOT_INDEPENDENT"); assertEquals(0, rawCalls.get()); }
    @Test void referenceIndependenceCannotBeLabelOnly() { ref(false).put("independentCriteriaSufficient", false); assertReason(assess(atomic(false)), "PS1", "REFERENCE_NOT_INDEPENDENT"); }
    @Test void selfComparisonCannotSupplyPs1() throws Exception {
        allele().put("alternate", "A"); selected = tx("c.58G>A", "Gly20Arg", "1/10", "20", "G/R", "Ggg/Agg");
        assertReason(assess(atomic(false)), "PS1", "REFERENCE_SELF_COMPARISON");
    }
    @Test void sourceVersionMismatchCannotReuseReferenceAudit() { ObjectNode a = atomic(false); metric(a, "clinvar.assertion.scv", "SCV002032337.2"); assertReason(assess(a), "PS1", "INDEPENDENT_REFERENCE_EVIDENCE_MISSING"); }
    @Test void dateMismatchCannotUseCurrentAudit() { ObjectNode a = atomic(false); metric(a, "clinvar.assertion.dateLastEvaluated", "2026-01-01"); assertReason(assess(a), "PS1", "REFERENCE_ASSERTION_VERSION_MISMATCH"); }
    @Test void submittedDiseaseConflictCannotHideBehindMatchingReportedDisease() { ObjectNode a = atomic(false); metric(a, "clinvar.assertion.condition.submittedRaw", "MONDO:0000001"); assertReason(assess(a), "PS1", "REFERENCE_DISEASE_MISMATCH"); }
    @Test void auditDiseaseMismatchIsRejected() { ref(false).put("mondoId", "MONDO:0000001"); assertReason(assess(atomic(false)), "PS1", "REFERENCE_DISEASE_MISMATCH"); }
    @Test void wrongReferenceTranscriptIsRejected() { ObjectNode a = atomic(false); metric(a, "vep.comparator.transcriptId", "ENST00000257555.10"); assertReason(assess(a), "PS1", "REFERENCE_TRANSCRIPT_MISMATCH"); }
    @Test void auditedReferenceTranscriptVersionCannotBeIgnored() { ref(false).put("refseqTranscript", "NM_000545.7"); assertReason(assess(atomic(false)), "PS1", "REFERENCE_TRANSCRIPT_MISMATCH"); }
    @Test void referenceFullProteinHgvsMustMatchCds() { ((ObjectNode) ref(false).path("selectedVep")).put("hgvsp", "ENSP00000257555.4:p.Gly20Arg"); assertReason(assess(atomic(false)), "PS1", "REFERENCE_PROTEIN_MISMATCH"); }
    @Test void referenceAuditCodingIdentityMustMatchCds() { ref(false).put("codingChange", "NM_000545.8:c.58G>C"); assertReason(assess(atomic(false)), "PS1", "REFERENCE_PROTEIN_MISMATCH"); }
    @Test void nonNativeReferenceAssemblyIsRejected() { ((ObjectNode) ref(false).path("allele")).put("assembly", "GRCh37"); assertReason(assess(atomic(false)), "PS1", "REFERENCE_IDENTITY_MISMATCH"); }
    @Test void wrongTargetProteinRelationIsRejected() { ObjectNode a = atomic(false); metric(a, "vep.target.alternateAminoAcid", "W"); assertReason(assess(a), "PS1", "REFERENCE_PROTEIN_MISMATCH"); }
    @Test void wrongReferenceDnaIsRejected() { ObjectNode a = atomic(false); metric(a, "clinvar.comparator.alternate", "T"); assertReason(assess(a), "PS1", "REFERENCE_IDENTITY_MISMATCH"); }
    @Test void wrongFullCdsCodonCannotPassMatchingProteinString() throws Exception {
        selected = tx("c.58G>C", "Gly20Arg", "1/10", "20", "G/R", "GGA/CGA");
        assertReason(assess(atomic(false)), "PS1", "HNF1A_PROTEIN_CODON_MISMATCH");
    }
    @Test void missingSpliceIsNotBiologicalNegative() { rawMissing = true; assertReason(assess(atomic(false)), "PS1", "SPLICE_EVIDENCE_UNAVAILABLE"); }
    @Test void spliceAtInclusiveBoundaryIsAccepted() { targetScore = 0.1; referenceScore = 0.1; assertEquals("MET", status(assess(atomic(false)), "PS1")); }
    @Test void highTargetSpliceRetainsUnresolvedMechanism() { targetScore = 0.1001; assertReason(assess(atomic(false)), "PS1", "HNF1A_UNRESOLVED_SPLICE_MECHANISM"); }
    @Test void highReferenceSpliceRetainsUnresolvedMechanism() { referenceScore = 0.2; assertReason(assess(atomic(false)), "PS1", "HNF1A_UNRESOLVED_SPLICE_MECHANISM"); }
    @Test void missingRnaReviewDoesNotMeanNormalRna() { rna.putArray("reviewedProteinRelations"); assertReason(assess(atomic(false)), "PS1", "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON"); assertEquals(0, rawCalls.get()); }
    @Test void normalRnaClaimCannotBeInferredFromLiteratureSearch() { rna.put("normalRnaClaimed", true); assertReason(assess(atomic(false)), "PS1", "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON"); }
    @Test void unresolvedRnaConflictBlocksBeforeModel() { rna.withArray("knownConcerns").addObject().set("allele", allele().deepCopy()); assertReason(assess(atomic(false)), "PS1", "PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW"); assertEquals(0, rawCalls.get()); }
    @Test void duplicateSubmissionsDoNotAddReferenceStrengthOrQueries() {
        ObjectNode a = atomic(false), b = a.deepCopy().put("evidenceId", "evidence-sha256-"+"b".repeat(64));
        ObjectNode r = assess(a, b); assertEquals(1, r.path("references").size()); assertEquals(2, r.path("spliceEvidence").size());
        assertEquals(2, eval(r, "PS1").path("evidenceIds").size()); assertEquals(1, r.at("/scientificDecisions/PS1/evidenceIds").size());
    }
    @Test void contradictoryExpertAssertionCannotBeSilentlySkipped() {
        ObjectNode a = atomic(false), b = a.deepCopy().put("evidenceId", "evidence-sha256-"+"b".repeat(64));
        metric(b, "clinvar.assertion.scv", "SCV_OTHER"); metric(b, "clinvar.assertion.classification", "Benign");
        assertReason(assess(a,b), "PS1", "REFERENCE_ASSERTION_CONFLICT"); assertEquals(0, rawCalls.get());
    }
    @Test void incidentalLowReviewVusDoesNotEraseExpertReferenceButStaysVisible() {
        ObjectNode a = atomic(false), b = a.deepCopy().put("evidenceId", "evidence-sha256-"+"b".repeat(64));
        metric(b, "clinvar.assertion.scv", "SCV_OTHER"); metric(b, "clinvar.assertion.classification", "Uncertain significance"); metric(b, "clinvar.assertion.reviewStatus", "criteria provided, single submitter");
        ObjectNode r = assess(a,b); assertEquals("MET", status(r,"PS1")); assertEquals(2,r.path("referenceAssessments").size());
    }
    @Test void auditRevisionChangesEvaluationIdentity() {
        ObjectNode a = atomic(false), before = assess(a); audit.put("reviewVersion", "engineering-next"); ObjectNode after = assess(a);
        assertNotEquals(eval(before,"PS1").path("evaluationId"), eval(after,"PS1").path("evaluationId"));
    }
    @Test void sameInputsHaveStableEvaluationIdentity() { ObjectNode a = atomic(false); assertEquals(eval(assess(a),"PS1"),eval(assess(a),"PS1")); }
    @Test void hnf1aRoutingNeverLoadsBrafPolicyForMissingComparator() {
        ObjectNode r = new ClinVarMissenseBusinessRuntime(mapper,null,(SpliceAiRawBatchRunner)null).evaluate(context,null,selected);
        assertEquals("HNF1A_GN017_3_1_PS1_PM5",r.at("/policy/profileId").asText()); assertEquals(0,r.path("providerCalls").asInt());
    }
    @Test void outsideDiseaseDoesNotEnterHnf1aAdapter() { ((ObjectNode)context.path("disease")).put("mondoId","MONDO:0000001"); assertFalse(Hnf1aPs1Pm5Runtime.matchesContext(context)); }

    private ObjectNode assess(ObjectNode... atomics) {
        var v = (ObjectNode) context.path("variant"); v.set("inputAllele", allele().deepCopy()); v.set("normalizedAllele", allele().deepCopy());
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        v.put("alleleId", adapter.canonicalAlleleId(allele(), v.path("normalization")));
        context.put("contextId", adapter.canonicalContextId(context));
        for (var a : atomics) a.put("contextId", context.path("contextId").asText());
        var r = runtime(); return r.assess(r.base(context),List.of(atomics),selected);
    }
    private Hnf1aPs1Pm5Runtime runtime() {
        return new Hnf1aPs1Pm5Runtime(mapper,null,queries -> {
            rawCalls.incrementAndGet(); if(rawMissing) return SpliceAiRawBatchRunner.BatchResult.failed("TEST_MISSING",null);
            ObjectNode raw = mapper.createObjectNode().put("status","SUCCEEDED"); var rows=raw.putArray("results");
            for(var q:queries) {
                var k=q.getKey(); var row=rows.addObject().put("queryId",q.getQueryId()).put("status","HIT").put("exactAlleleMatch",true);
                row.putObject("allele").put("assembly",k.getGenomeAssembly()).put("contig",k.getChromosome()).put("position",k.getPosition()).put("reference",k.getReference()).put("alternate",k.getAlternate());
                var s=row.putArray("annotations").addObject().put("geneSymbol","HNF1A").putObject("deltaScores");
                for(String f:List.of("DS_AG","DS_AL","DS_DG","DS_DL")) s.put(f,Double.toString("TARGET".equals(q.getQueryId())?targetScore:referenceScore));
            }
            return SpliceAiRawBatchRunner.BatchResult.succeeded(raw,0);
        },Hnf1aPs1Pm5Runtime.loadProfile(mapper),audit,rna);
    }
    private ObjectNode atomic(boolean pm5) {
        JsonNode ref=ref(pm5); var a=mapper.createObjectNode().put("schemaVersion","atomic-evidence-v2").put("evidenceId","evidence-sha256-"+"a".repeat(64))
                .put("contextId",context.path("contextId").asText()).put("canonicalizationProfile","varvis-json-c14n-v1").put("evidenceType","CLINICAL_DATABASE");
        a.putObject("provenance").put("producerId",RuntimeEvidenceV2Adapter.PRODUCER_ID);
        var source=a.putObject("source");var locators=source.putArray("sourceLocators");
        for(JsonNode res:ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources")) {
            if(res.path("resourceId").asText().startsWith("CLINVAR_COMPARATOR")) source.put("sourceId",res.path("resourceId").asText()).put("resourceVersion",res.path("resourceVersion").asText()).put("resourceSha256",res.path("identityFingerprintSha256").asText());
            locators.addObject().put("locatorValue","resource-identity:"+res.path("resourceId").asText()+"@GRCh38").put("artifactSha256",res.path("identityFingerprintSha256").asText());
        }
        a.putObject("resourceExecution").put("status","SUCCEEDED");a.putObject("resourceAvailability").put("status","AVAILABLE");a.putObject("queryOutcome").put("status","HIT");a.putObject("resourceApplicability").put("status","APPLICABLE");
        for(String field:List.of("assembly","contig","position","reference","alternate")) metric(a,"clinvar.comparator."+field,ref.path("allele").path(field).asText());
        metric(a,"clinvar.comparator.geneSymbols","HNF1A");metric(a,"clinvar.comparator.variationId",ref.path("variationId").asText());
        metric(a,"clinvar.assertion.scv",ref.path("scv").asText());metric(a,"clinvar.assertion.classification",ref.path("classification").asText());
        metric(a,"clinvar.assertion.reviewStatus","reviewed by expert panel");metric(a,"clinvar.assertion.dateLastEvaluated",ref.path("evaluationDate").asText());
        metric(a,"clinvar.assertion.condition.submittedRaw","MONDO:0015967");metric(a,"clinvar.assertion.condition.reportedRaw","MONDO:0015967");
        metric(a,"clinvar.assertion.source.logicalName","submission_summary_2026-03.txt.gz");metric(a,"clinvar.assertion.source.line","1");
        metric(a,"vep.proteinRelation",pm5?"SAME_RESIDUE_DIFFERENT_AMINO_ACID":"SAME_AMINO_ACID_CHANGE");
        for(String role:List.of("target","comparator")) {
            metric(a,"vep."+role+".transcriptId",Hnf1aPs1Pm5Runtime.TRANSCRIPT);metric(a,"vep."+role+".proteinPosition",ref.path("proteinPosition").asText());
            metric(a,"vep."+role+".referenceAminoAcid",ref.path("referenceAminoAcid").asText());metric(a,"vep."+role+".alternateAminoAcid",pm5&&"target".equals(role)?"C":ref.path("alternateAminoAcid").asText());
        }
        return a;
    }
    private static void metric(ObjectNode a,String name,String value) {
        for(JsonNode m:a.path("measurements")) if(name.equals(m.path("metricId").asText())) {((ObjectNode)m).put("rawValue",value);return;}
        a.withArray("measurements").addObject().put("metricId",name).put("rawValue",value);
    }
    private static JsonNode eval(JsonNode r,String code) { for(JsonNode e:r.path("criterionEvaluations")) if(("ACMG_AMP_2015:"+code).equals(e.path("criterionId").asText())) return e; throw new AssertionError("Missing criterion"); }
    private static String status(JsonNode r,String code) {return eval(r,code).at("/evaluationResult/status").asText();}
    private static void assertReason(JsonNode r,String code,String reason) {assertEquals("NOT_EVALUATED",status(r,code));assertEquals(reason,eval(r,code).at("/evaluationResult/reasonCode").asText());}
}
