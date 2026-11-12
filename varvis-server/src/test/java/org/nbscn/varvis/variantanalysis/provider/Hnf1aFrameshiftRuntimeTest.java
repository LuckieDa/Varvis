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
import org.nbscn.varvis.variantanalysis.service.AcmgRecommendations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Public VEP annotations are frozen fixtures; synthetic sequence boundaries are not real-resource acceptance. */
class Hnf1aFrameshiftRuntimeTest {
    private static final String TRANSCRIPT = "ENST00000257555.11";
    private static final String PROTEIN = "ENSP00000257555.5";
    // Independently translated standard-code CDS, fixed before runtime execution on 2026-09-10.
    // Public normalized insertion is c.864_865insC; VEP's equivalent 3-prime expression is c.872dup.
    private static final Fixture INSERTION = new Fixture(120994314, "G", "GC", "872dup",
            "Gly292ArgfsTer25", "4/10", "288-289", "-/X", "-/C", 1, 292, 25,
            946, 948, 1769, 821, 945, 947,
            "e007bc9ec14fa0d30ad1887ab5138eddec6fa22c7747dea4440f22b75df6cc27");
    // Public deletion removes c.585; repeat equivalence gives VEP c.586del and a later protein change.
    private static final Fixture DELETION = new Fixture(120993577, "CA", "C", "586del",
            "Thr196ProfsTer37", "3/10", "195", "P/X", "ccA/cc", -1, 196, 37,
            694, 696, 1767, 1071, 695, 697,
            "a6df5942bfa6b6e1d81ea3627b9fa37af6fe424d486f503f0a02ef6b428d75d4");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final Hnf1aPvs1Runtime runtime = new Hnf1aPvs1Runtime(mapper);

    @ParameterizedTest
    @CsvSource({"'',-", "C,X", "TCAC,SX", "ACCT,TX", "TAAC,*", "TAA,*", "TGAAAAC,*KX", "ACCTGAAAAC,T*KX"})
    void localPeptideUsesAllCompleteTripletsAndOnlyTheOfficialPartialCodonException(String codons, String expected) {
        // Ensembl release/116 peptide(): complete triplets continue through '*'; only an entire '*' omits residual X.
        assertEquals(expected, ReflectionTestUtils.invokeMethod(Hnf1aFrameshiftResolver.class, "localPeptide", codons));
    }

    @ParameterizedTest
    @CsvSource({"TAC,TCAC,Y/SX", "TAC,TAAC,Y/*", "'',ACCT,-/TX", "TAC,TAT,Y", "TAC,'',Y/-"})
    void localAminoAllelesPreserveEmptyPeptidesAndCollapseOnlyIdenticalPeptides(String reference, String alternate, String expected) {
        assertEquals(expected, ReflectionTestUtils.invokeMethod(Hnf1aFrameshiftResolver.class, "localAminoAcids", reference, alternate));
    }

    @Test void withinCodonInsertionAcceptsTheCompleteLocalPeptideWithoutRewritingVep() throws Exception {
        // Fixed engineering allele, confirmed in the single seven-record local VEP 116 batch on 2026-09-10.
        var candidate = candidate(120988870, "T", "TC", withinCodonAnnotation());
        var result = runtime.evaluate(context(candidate), candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        assertEquals("Y/SX", result.at("/storedAnnotation/aminoAcids").asText());
        assertEquals("tac/tCac", result.at("/storedAnnotation/codons").asText());
        assertEquals(122, result.at("/resolvedInput/firstChangedProteinPosition").asInt());
        assertEquals(66, result.at("/resolvedInput/terminationDistance").asInt());
        assertEquals(559, result.at("/resolvedInput/ptcStart").asInt());
        assertEquals(561, result.at("/resolvedInput/ptcEnd").asInt());
        assertEquals(1769, result.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
        assertEquals(1208, result.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals("PREDICTED_NMD", result.at("/resolvedInput/nmdPrediction").asText());
    }

    @ParameterizedTest @CsvSource({
            "aminoAcids,Y/X", "aminoAcids,Y/S", "aminoAcids,Y/SXX",
            "codons,tac/tCcc", "proteinPosition,123",
            "hgvsProtein,ENSP00000257555.5:p.Tyr122SerfsTer65"})
    void newlyCompatiblePeptideDoesNotRelaxTheIndependentSequenceOrProteinChecks(String field, String value) throws Exception {
        // TAC/TCCC and TAC/TCAC both locally encode Y/SX; only the latter agrees with this genomic insertion.
        var candidate = candidate(120988870, "T", "TC", withinCodonAnnotation().put(field, value));
        assertUnresolved(runtime.evaluate(context(candidate), candidate), "PVS1_FRAMESHIFT_PROTEIN_MISMATCH");
    }

    @Test void fourBaseInsertionAtCodonBoundaryKeepsItsCompleteThrAndResidualX() throws Exception {
        var json = annotationJson(INSERTION).put("hgvsCoding", TRANSCRIPT + ":c.864_865insACCT")
                .put("hgvsProtein", PROTEIN + ":p.Pro289ThrfsTer29")
                .put("aminoAcids", "-/TX").put("codons", "-/ACCT");
        var candidate = candidate(120994314, "G", "GACCT", json);
        var result = runtime.evaluate(context(candidate), candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        assertEquals("-/TX", result.at("/storedAnnotation/aminoAcids").asText());
        assertEquals("288-289", result.at("/storedAnnotation/proteinPosition").asText());
        assertEquals(289, result.at("/resolvedInput/firstChangedProteinPosition").asInt());
        assertEquals(29, result.at("/resolvedInput/terminationDistance").asInt());
        assertEquals(949, result.at("/resolvedInput/ptcStart").asInt());
        assertEquals(951, result.at("/resolvedInput/ptcEnd").asInt());
        assertEquals(1772, result.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
        assertEquals(821, result.at("/resolvedInput/distanceToLastJunction").asInt());
        var corrupted = candidate(120994314, "G", "GACCT", json.put("aminoAcids", "-/X"));
        assertUnresolved(runtime.evaluate(context(corrupted), corrupted), "PVS1_FRAMESHIFT_PROTEIN_MISMATCH");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void publicInsertionAndDeletionMatchIndependentSequenceAndNmdFacts(boolean insertion) throws Exception {
        Fixture f = insertion ? INSERTION : DELETION;
        var candidate = candidate(f);
        var context = context(candidate);
        var result = runtime.evaluate(context, candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        JsonNode input = result.path("resolvedInput");
        assertEquals("ALTERED_CDS", input.path("coordinateSystem").asText());
        assertEquals(f.delta(), input.path("netLengthChange").asInt());
        assertEquals(f.first(), input.path("firstChangedProteinPosition").asInt());
        assertEquals(f.relativeStop(), input.path("terminationDistance").asInt());
        assertEquals(f.ptcStart(), input.path("ptcStart").asInt());
        assertEquals(f.ptcEnd(), input.path("ptcEnd").asInt());
        assertEquals(1768, input.path("referenceLastJunctionCdsPosition").asInt());
        assertEquals(f.junction(), input.path("mutantLastJunctionCdsPosition").asInt());
        assertEquals(f.distance(), input.path("distanceToLastJunction").asInt());
        assertEquals(f.referencePtcStart(), input.path("ptcReferenceStart").asInt());
        assertEquals(f.referencePtcEnd(), input.path("ptcReferenceEnd").asInt());
        assertEquals("REFERENCE_BASES", input.path("ptcReferenceMapping").asText());
        assertEquals(f.cdsSha(), input.path("alteredCdsSha256").asText());
        assertEquals("e168ca6710db1a6292ce0963327b7637443384c54809b209d934fef1c2b86d06", input.path("referenceCdsSha256").asText());
        assertTrue(input.path("hgvscSequenceEquivalent").asBoolean());
        assertEquals("GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE", input.path("sequenceDerivationStatus").asText());
        assertEquals("PREDICTED_NMD", input.path("nmdPrediction").asText());
        assertEquals(f.rawPosition(), result.at("/storedAnnotation/proteinPosition").asText());
        assertEquals(f.rawAmino(), result.at("/storedAnnotation/aminoAcids").asText());
        assertEquals(f.rawCodons(), result.at("/storedAnnotation/codons").asText());
        assertEquals(0, result.path("providerCalls").asInt());
        assertEquals(1, result.path("evaluatorCalls").asInt());
        assertEquals(result, runtime.evaluate(context, candidate));
        var identity = (ObjectNode) result.path("evaluation").deepCopy();
        identity.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + adapter.canonicalSha256(identity), result.at("/evaluation/evaluationId").asText());
    }

    @ParameterizedTest @CsvSource({"true,864_865insC", "true,865dupC", "true,872dupC", "false,585delA", "false,586delA"})
    void repeatedSequenceHgvsIsAcceptedByWholeCdsEquivalence(boolean insertion, String coding) throws Exception {
        Fixture f = insertion ? INSERTION : DELETION;
        var json = annotationJson(f).put("hgvsCoding", TRANSCRIPT + ":c." + coding);
        var candidate = candidate(f.position(), f.ref(), f.alt(), json);
        var result = runtime.evaluate(context(candidate), candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(f.cdsSha(), result.at("/resolvedInput/alteredCdsSha256").asText());
    }

    @ParameterizedTest @CsvSource({
            "hgvsCoding,ENST00000257555.11:c.864_865insA,PVS1_HGVSC_SEQUENCE_MISMATCH",
            "hgvsCoding,ENST00000257555.11:c.872dupA,PVS1_CODING_INDEL_MAPPING_UNRESOLVED",
            "hgvsProtein,ENSP00000257555.5:p.Gly292ArgfsTer24,PVS1_FRAMESHIFT_PROTEIN_MISMATCH",
            "hgvsProtein,ENSP00000257555.4:p.Gly292ArgfsTer25,PVS1_FRAMESHIFT_PROTEIN_MISMATCH",
            "proteinPosition,292,PVS1_FRAMESHIFT_PROTEIN_MISMATCH",
            "aminoAcids,G/R,PVS1_FRAMESHIFT_PROTEIN_MISMATCH",
            "codons,-/A,PVS1_FRAMESHIFT_PROTEIN_MISMATCH",
            "exon,5/10,PVS1_EXON_STRUCTURE_MISMATCH",
            "consequence,frameshift_variant&splice_region_variant,PVS1_SPLICE_BRANCH_NOT_IMPLEMENTED",
            "consequence,inframe_insertion,PVS1_CONSEQUENCE_NOT_APPLICABLE"})
    void annotationDisagreementNeverBecomesMet(String field, String value, String reason) throws Exception {
        var candidate = candidate(INSERTION.position(), INSERTION.ref(), INSERTION.alt(), annotationJson(INSERTION).put(field, value));
        assertUnresolved(runtime.evaluate(context(candidate), candidate), reason);
    }

    @ParameterizedTest @ValueSource(strings = {"hgvsProtein", "proteinPosition", "aminoAcids", "codons"})
    void incompleteProteinFactsRemainUnknown(String field) throws Exception {
        var candidate = candidate(INSERTION.position(), INSERTION.ref(), INSERTION.alt(), annotationJson(INSERTION).putNull(field));
        assertUnresolved(runtime.evaluate(context(candidate), candidate), "PVS1_FRAMESHIFT_ANNOTATION_INCOMPLETE");
    }

    @ParameterizedTest @CsvSource({
            "120994314,A,AC,PVS1_CDS_REFERENCE_MISMATCH",
            "120993577,CG,C,PVS1_CDS_REFERENCE_MISMATCH",
            "120994314,G,GG,PVS1_SIMPLE_SHORT_INDEL_REQUIRED",
            "120994314,G,AC,PVS1_SIMPLE_SHORT_INDEL_REQUIRED",
            "120994314,G,GCAT,PVS1_FRAMESHIFT_NOT_CONFIRMED",
            "120994314,GCAT,G,PVS1_FRAMESHIFT_NOT_CONFIRMED",
            "120994404,CGT,C,PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED",
            "120994405,G,GC,PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED"})
    void refAnchorInFrameAndCrossExonGatesAreIndependent(int position, String ref, String alt, String reason) throws Exception {
        var candidate = candidate(position, ref, alt, annotationJson(INSERTION));
        assertUnresolved(runtime.evaluate(context(candidate), candidate), reason);
    }

    @Test void smallVariantLengthBoundaryPreservesExistingEvidenceContract() throws Exception {
        var tooLong = candidate(INSERTION.position(), "G", "G" + "C".repeat(50), annotationJson(INSERTION));
        assertUnresolved(runtime.evaluate(context(tooLong), tooLong), "PVS1_CONTEXT_IDENTITY_INVALID");
        var candidate = candidate(INSERTION);
        var context = context(candidate);
        ((ObjectNode) context.path("variant")).put("variantType", "SNV"); refresh(context);
        assertUnresolved(runtime.evaluate(context, candidate), "PVS1_NATIVE_SMALL_VARIANT_REQUIRED");
        ((ObjectNode) context.path("variant")).put("variantType", "SMALL_DELETION"); refresh(context);
        assertUnresolved(runtime.evaluate(context, candidate), "PVS1_SIMPLE_SHORT_INDEL_REQUIRED");
    }

    @Test void transcriptMoiAndPm2OnlyConfirmationCannotAuthorizeFrameshift() throws Exception {
        var candidate = candidate(INSERTION);
        var wrongTranscript = context(candidate);
        ((ObjectNode) wrongTranscript.path("transcript")).put("version", 10); refresh(wrongTranscript);
        assertUnresolved(runtime.evaluate(wrongTranscript, candidate), "PVS1_TRANSCRIPT_NOT_SUPPORTED");
        var wrongMoi = context(candidate); wrongMoi.put("moi", "AUTOSOMAL_RECESSIVE"); refresh(wrongMoi);
        assertUnresolved(runtime.evaluate(wrongMoi, candidate), "PVS1_POLICY_NOT_APPLICABLE");
        var populationOnly = context(candidate);
        ((ObjectNode) populationOnly.path("transcript")).put("selectionPolicy", AcmgRecommendations.PM2_CONTEXT_SELECTION); refresh(populationOnly);
        assertUnresolved(runtime.evaluate(populationOnly, candidate), "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED");
        assertEquals(0, runtime.storedVepProjections()); assertEquals(0, runtime.evaluatorCalls());
    }

    @Test void savedVepCannotCrossAllelesAndMissingAnnotationIsNotInvented() throws Exception {
        var insertion = candidate(INSERTION); var deletion = candidate(DELETION);
        assertUnresolved(runtime.evaluate(context(deletion), insertion), "PVS1_VEP_SOURCE_IDENTITY_INVALID");
        var context = context(insertion); insertion.applyTranscriptAnnotations(List.of());
        assertUnresolved(runtime.evaluate(context, insertion), "PVS1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
    }

    @Test void realCdsWithoutAnAlteredStopDoesNotAssumeTheNativeTerminalStop() throws Exception {
        // Synthetic annotation on the real CDS: inserting A after c.1653 removes all in-CDS stops.
        var json = annotationJson(INSERTION).put("hgvsCoding", TRANSCRIPT + ":c.1653_1654insA")
                .put("hgvsProtein", PROTEIN + ":p.Glu552ArgfsTer?").put("exon", "9/10")
                .put("proteinPosition", "551-552").put("codons", "-/A");
        var candidate = candidate(120999512, "T", "TA", json);
        var result = runtime.evaluate(context(candidate), candidate);
        assertUnresolved(result, "PVS1_TERMINATION_UNRESOLVED");
        assertFalse(result.path("resolvedInput").has("ptcStart"));
    }

    @Test void insertedMiddleBaseIsNotReportedAsThreeReferenceBases() throws Exception {
        // Actual VEP 116 reports BOTH terms, c.365dup, and Y/*; the immediate stop is not a fabricated frameshift route.
        // Reference TAC at c.364..366 plus inserted A gives TAA at altered c.364..366.
        var json = annotationJson(INSERTION).put("hgvsCoding", TRANSCRIPT + ":c.365dup")
                .put("hgvsProtein", PROTEIN + ":p.Tyr122Ter").put("exon", "2/10")
                .put("consequence", "stop_gained&frameshift_variant")
                .put("proteinPosition", "122").put("aminoAcids", "Y/*").put("codons", "tac/tAac");
        var candidate = candidate(120988870, "T", "TA", json);
        var result = runtime.evaluate(context(candidate), candidate);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        assertEquals("stop_gained&frameshift_variant", result.at("/storedAnnotation/consequence").asText());
        assertEquals("Y/*", result.at("/storedAnnotation/aminoAcids").asText());
        JsonNode input = result.path("resolvedInput");
        assertEquals(364, input.path("ptcStart").asInt()); assertEquals(366, input.path("ptcEnd").asInt());
        assertEquals(364, input.path("ptcReferenceStart").asInt());
        assertTrue(input.path("ptcReferenceMiddle").isNull());
        assertEquals(365, input.path("ptcReferenceEnd").asInt());
        assertEquals("CONTAINS_INSERTED_BASES", input.path("ptcReferenceMapping").asText());
        assertEquals(1, input.path("terminationDistance").asInt());
        assertEquals(1403, input.path("distanceToLastJunction").asInt());
        assertEquals("PREDICTED_NMD", input.path("nmdPrediction").asText());
        for (String incompatible : List.of("Y/X", "Y/*X")) {
            var corrupted = candidate(120988870, "T", "TA", json.deepCopy().put("aminoAcids", incompatible));
            assertUnresolved(runtime.evaluate(context(corrupted), corrupted), "PVS1_FRAMESHIFT_PROTEIN_MISMATCH");
        }
        // Removing the actual frameshift term cannot authorize a non-SNV via the original SNV-only stop-gained route.
        var stopOnly = candidate(120988870, "T", "TA", json.deepCopy().put("consequence", "stop_gained"));
        assertUnresolved(runtime.evaluate(context(stopOnly), stopOnly), "PVS1_CONSEQUENCE_NOT_APPLICABLE");
    }

    @ParameterizedTest @CsvSource({"true,120,55", "true,119,54", "false,119,55", "false,118,54"})
    void syntheticFiftyFiveNtBoundaryUsesShiftedJunctionAndDerivedPtc(boolean insertion, int junction, int distance) throws Exception {
        // Exact arithmetic from explicit toy sequences, not production translation:
        // +G after c.30: first Pro11->Ala, stop22 / CDS64..66. -C at c.31: Pro11->His, stop21 / CDS61..63.
        var model = syntheticModel(insertion, 12, 24, 36, 48, 60, 72, 84, 96, junction);
        var input = mapper.createObjectNode();
        var candidate = syntheticCandidate(insertion);
        String issue = new Hnf1aFrameshiftResolver(model, syntheticPolicy()).resolve(context(candidate), candidate.getTranscriptAnnotations().get(0), input);
        assertEquals(distance == 55 ? null : "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED", issue, input.toPrettyString());
        assertEquals(insertion ? 64 : 61, input.path("ptcStart").asInt());
        assertEquals(insertion ? 66 : 63, input.path("ptcEnd").asInt());
        assertEquals(junction + (insertion ? 1 : -1), input.path("mutantLastJunctionCdsPosition").asInt());
        assertEquals(distance, input.path("distanceToLastJunction").asInt());
        // Looking only at the variant would incorrectly select NMD in the 54-nt negative cases.
        assertTrue(input.path("mutantLastJunctionCdsPosition").asInt() - input.path("cdsPosition").asInt() > 55);
        assertEquals(3, input.path("exonOrdinal").asInt());
        assertNotEquals(3, input.path("ptcExonOrdinal").asInt());
        if (distance < 55) assertFalse(input.has("nmdPrediction"));
    }

    @Test void syntheticPtcSpanningAnExonJunctionRemainsUnimplemented() throws Exception {
        var model = syntheticModel(true, 12, 24, 36, 48, 60, 64, 84, 96, 120);
        var candidate = syntheticCandidate(true); var input = mapper.createObjectNode();
        assertEquals("PVS1_EXON_BOUNDARY_BRANCH_NOT_IMPLEMENTED", new Hnf1aFrameshiftResolver(model, syntheticPolicy())
                .resolve(context(candidate), candidate.getTranscriptAnnotations().get(0), input));
        assertEquals(64, input.path("ptcStart").asInt()); assertEquals(66, input.path("ptcEnd").asInt());
        assertFalse(input.has("nmdPrediction"));
    }

    @Test void syntheticStartProximalAndLongExonBranchesRemainClosed() throws Exception {
        var candidate = syntheticCandidate(true); var input = mapper.createObjectNode();
        var regular = syntheticModel(true, 12, 24, 36, 48, 60, 72, 84, 96, 120);
        assertEquals("PVS1_START_PROXIMAL_BRANCH_NOT_IMPLEMENTED", new Hnf1aFrameshiftResolver(regular,
                Hnf1aPvs1Runtime.loadProfile(mapper).path("policy")).resolve(context(candidate), candidate.getTranscriptAnnotations().get(0), input));
        var longExon = syntheticModel(true, 12, 24, 425, 437, 449, 461, 473, 485, 540);
        assertEquals("PVS1_LONG_EXON_BRANCH_NOT_IMPLEMENTED", new Hnf1aFrameshiftResolver(longExon, syntheticPolicy())
                .resolve(context(candidate), candidate.getTranscriptAnnotations().get(0), mapper.createObjectNode()));
    }

    private ObjectNode syntheticPolicy() {
        // Only the start-coordinate gate differs to exercise a short artificial CDS; never installed as a runtime policy.
        return ((ObjectNode) Hnf1aPvs1Runtime.loadProfile(mapper).path("policy")).deepCopy()
                .put("minimumCdsPosition", 1);
    }

    private ObjectNode syntheticModel(boolean insertion, int... ends) {
        // Reference has 200 complete codons, including native terminal TAA, and ten disjoint coding exons.
        String cds = "ATG" + "CCA".repeat(19) + (insertion ? "CCTGAA" : "CTAACC") + "CCA".repeat(177) + "TAA";
        var model = mapper.createObjectNode().put("strand", 1).put("protein", PROTEIN).put("refseqTranscript", "NM_000545.8")
                .put("cdsSequence", cds).put("lastJunctionCdsPosition", ends[ends.length - 1]);
        var exons = model.putArray("exons"); int start = 1;
        for (int i = 0; i <= ends.length; i++) {
            int end = i == ends.length ? cds.length() : ends[i];
            int genomic = 2000 + i * 1000;
            exons.addObject().put("ordinal", i + 1).put("cdsStart", start).put("cdsEnd", end)
                    .put("genomicStart", genomic).put("genomicEnd", genomic + end - start);
            start = end + 1;
        }
        return model;
    }

    private VariantInterpretation syntheticCandidate(boolean insertion) throws Exception {
        var json = annotationJson(INSERTION).put("hgvsCoding", TRANSCRIPT + (insertion ? ":c.30_31insG" : ":c.31delC"))
                .put("hgvsProtein", PROTEIN + (insertion ? ":p.Pro11AlafsTer12" : ":p.Pro11HisfsTer11"))
                .put("exon", "3/10").put("source", "VEP_SYNTHETIC")
                .put("proteinPosition", insertion ? "10-11" : "11").put("aminoAcids", insertion ? "-/X" : "P/X")
                .put("codons", insertion ? "-/G" : "CCA/CA");
        return candidate(4005, insertion ? "A" : "AC", insertion ? "AG" : "A", json);
    }

    private ObjectNode annotationJson(Fixture f) {
        return mapper.createObjectNode().put("geneSymbol", "HNF1A").put("geneId", "ENSG00000135100")
                .put("transcriptAccession", TRANSCRIPT).put("hgvsCoding", TRANSCRIPT + ":c." + f.coding())
                .put("hgvsProtein", PROTEIN + ":p." + f.protein()).put("consequence", "frameshift_variant")
                .put("exon", f.exon()).put("proteinPosition", f.rawPosition()).put("aminoAcids", f.rawAmino())
                .put("codons", f.rawCodons()).put("source", "VEP_CACHED").put("status", "COMPLETED")
                .put("version", "116.0").put("timestamp", "2026-09-10T00:00:00Z").put("maneSelect", true);
    }

    private ObjectNode withinCodonAnnotation() {
        return annotationJson(INSERTION).put("hgvsCoding", TRANSCRIPT + ":c.364_365insC")
                .put("hgvsProtein", PROTEIN + ":p.Tyr122SerfsTer66").put("exon", "2/10")
                .put("proteinPosition", "122").put("aminoAcids", "Y/SX").put("codons", "tac/tCac");
    }

    private VariantInterpretation candidate(Fixture f) throws Exception {
        return candidate(f.position(), f.ref(), f.alt(), annotationJson(f));
    }

    private VariantInterpretation candidate(int position, String ref, String alt, ObjectNode annotation) throws Exception {
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh38", "12", position, ref, alt));
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        return candidate;
    }

    private ObjectNode context(VariantInterpretation candidate) throws Exception {
        var key = candidate.getObservation().getKey();
        var context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key, "ENST00000257555");
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0015967").put("label", "monogenic diabetes");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("version", 11).put("annotationSourceVersion", "116.0");
        ((ObjectNode) context.path("variant")).put("variantType", key.getAlternate().length() > key.getReference().length() ? "SMALL_INSERTION" : "SMALL_DELETION");
        assertEquals(adapter.canonicalAlleleId(context.at("/variant/normalizedAllele"), context.at("/variant/normalization")), context.at("/variant/alleleId").asText());
        refresh(context); return context;
    }

    private void refresh(ObjectNode context) { context.put("contextId", adapter.canonicalContextId(context)); }

    private void assertUnresolved(ObjectNode result, String reason) {
        JsonNode evaluation = result.has("evaluation") ? result.path("evaluation") : result;
        assertEquals("NOT_EVALUATED", evaluation.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(reason, evaluation.at("/evaluationResult/reasonCode").asText(), result.toPrettyString());
        assertFalse(result.path("resolvedInput").has("nmdPrediction"));
        if (result.has("evaluation")) assertEquals("NONE", evaluation.path("appliedStrength").asText());
    }

    private record Fixture(int position, String ref, String alt, String coding, String protein, String exon,
                           String rawPosition, String rawAmino, String rawCodons, int delta, int first, int relativeStop,
                           int ptcStart, int ptcEnd, int junction, int distance, int referencePtcStart, int referencePtcEnd,
                           String cdsSha) { }
}
