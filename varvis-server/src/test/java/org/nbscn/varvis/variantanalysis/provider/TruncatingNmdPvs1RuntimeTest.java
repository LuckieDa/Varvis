package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.AcmgRecommendations;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic algorithm development. No real PTEN/HNF1A resource, scientific policy or page acceptance. */
class TruncatingNmdPvs1RuntimeTest {
    private static final String CRITERION = "ACMG_AMP_2015:PVS1";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @ParameterizedTest @CsvSource({"1,1", "2,-1"})
    void independentGeneAndStrandContextsUseTheSameStopRuntime(int gene, int strand) throws Exception {
        Inputs data = nonsense(gene, strand, 31);
        ObjectNode first = evaluate(data);
        assertMet(first);
        assertEquals(31, first.at("/resolvedInput/ptcStart").asInt());
        assertEquals(33, first.at("/resolvedInput/ptcEnd").asInt());
        assertEquals(417, first.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals(first, evaluate(data));
        assertEquals(0, first.path("providerCalls").asInt());
        assertFalse(first.path("classificationEnabled").asBoolean(true));
        assertEquals(TruncatingNmdPvs1Runtime.EVALUATOR_ID, first.at("/evaluation/deterministicTrace/evaluatorId").asText());
        assertNotEquals("hnf1a-truncating-nmd-pvs1", first.at("/evaluation/deterministicTrace/evaluatorId").asText());
    }

    @ParameterizedTest @CsvSource({"true,1", "false,1", "true,-1", "false,-1"})
    void frameshiftUsesAlteredPtcJunctionAndStrictVepLocalPeptide(boolean insertion, int strand) throws Exception {
        Inputs data = frameshift(insertion, strand, 120);
        ObjectNode result = evaluate(data);
        assertMet(result);
        assertEquals(insertion ? 64 : 61, result.at("/resolvedInput/ptcStart").asInt());
        assertEquals(insertion ? 66 : 63, result.at("/resolvedInput/ptcEnd").asInt());
        assertEquals(insertion ? 121 : 119, result.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
        assertEquals(insertion ? 55 : 56, result.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals("GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE", result.at("/resolvedInput/sequenceDerivationStatus").asText());
        mutateAnnotation(data, "aminoAcids", insertion ? "-/G" : "P/H");
        assertReason(evaluate(data), "PVS1_FRAMESHIFT_PROTEIN_MISMATCH");
    }

    @ParameterizedTest @CsvSource({"STOP,1", "STOP,-1", "INSERTION,1", "INSERTION,-1", "DELETION,1", "DELETION,-1"})
    void completeFivePrimeUtrExonPreservesPtcNmdDistanceAndTrueExonOrdinals(String kind, int strand) throws Exception {
        Inputs data = "STOP".equals(kind) ? nonsense(1, strand, 31) : frameshift("INSERTION".equals(kind), strand, 120);
        ObjectNode original = evaluate(data);
        assertMet(original);
        var codingExons = data.model.withArray("exons").deepCopy();
        var exons = data.model.putArray("exons");
        exons.addObject().put("ordinal", 1).put("genomicStart", strand == 1 ? 1000 : 21000)
                .put("genomicEnd", strand == 1 ? 1029 : 21029).put("cdsStart", -29).put("cdsEnd", 0);
        for (JsonNode exon : codingExons) exons.add(((ObjectNode) exon).deepCopy().put("ordinal", exon.path("ordinal").asInt() + 1));
        String oldExon = data.candidate.getTranscriptAnnotations().get(0).getExon();
        int originalOrdinal = Integer.parseInt(oldExon.substring(0, oldExon.indexOf('/')));
        mutateAnnotation(data, "exon", (originalOrdinal + 1) + "/" + exons.size());
        ObjectNode nmd = (ObjectNode) data.policy.path("nmdScope");
        for (String field : List.of("relevantExonOrdinals", "engineeringSupportedExonOrdinals")) {
            var originalOrdinals = nmd.withArray(field).deepCopy();
            var updated = nmd.putArray(field);
            for (JsonNode ordinal : originalOrdinals) updated.add(ordinal.asInt() + 1);
        }
        bind(data);
        ObjectNode updated = evaluate(data);
        assertMet(updated);
        for (String field : List.of("ptcStart", "ptcEnd", "distanceToLastJunction"))
            assertEquals(original.path("resolvedInput").path(field), updated.path("resolvedInput").path(field), field);
        assertEquals(originalOrdinal + 1, updated.at("/resolvedInput/exonOrdinal").asInt());
        assertEquals("1.0.4", updated.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        mutateAnnotation(data, "exon", oldExon);
        assertEquals("NOT_EVALUATED", evaluate(data).at("/evaluation/evaluationResult/status").asText());
    }

    @ParameterizedTest @CsvSource({"51,117,MET", "51,116,NOT_EVALUATED", "55,121,MET", "55,120,NOT_EVALUATED"})
    void policyDistancesUseActualShiftedJunctionNotAHardCodedGeneThreshold(int minimum, int junction, String status) throws Exception {
        Inputs data = frameshift(true, 1, junction - 1);
        ((ObjectNode) data.policy.path("nmdScope")).put("minimumDistanceToLastJunction", minimum);
        bind(data);
        ObjectNode result = evaluate(data);
        assertEquals(status, result.at("/evaluation/evaluationResult/status").asText());
        assertEquals(junction - 66, result.at("/resolvedInput/distanceToLastJunction").asInt());
        if (!"MET".equals(status)) {
            assertReason(result, "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED");
            assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"hgvsc", "hgvsp", "codons", "proteinPosition", "aminoAcids", "exon"})
    void sequenceDisagreementNeverGrantsStopEvidence(String field) throws Exception {
        Inputs data = nonsense(1, 1, 31);
        String key = "hgvsc".equals(field) ? "hgvsCoding" : "hgvsp".equals(field) ? "hgvsProtein" : field;
        mutateAnnotation(data, key, "invalid");
        ObjectNode result = evaluate(data);
        assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"DRAFT", "PENDING_REVIEW"})
    void unreviewedPolicyCannotBeActivatedByAReceipt(String status) throws Exception {
        Inputs data = nonsense(1, 1, 31);
        ((ObjectNode) data.policy.path("review")).put("status", status); bind(data);
        assertReason(evaluate(data), "PVS1_REVIEWED_POLICY_REQUIRED");
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "GAIN_OF_FUNCTION", "CLINVAR_PATHOGENIC_ONLY"})
    void consequenceAndLabelsCannotSubstituteForLofMechanism(String mechanism) throws Exception {
        Inputs data = nonsense(1, 1, 31);
        ((ObjectNode) data.policy.path("mechanism")).put("status", mechanism); bind(data);
        assertReason(evaluate(data), "PVS1_LOF_MECHANISM_NOT_ESTABLISHED");
    }

    @Test void policyAndSelectionMustBindExactIdentityAndContext() throws Exception {
        Inputs data = nonsense(1, 1, 31);
        data.selection.put("policyVersion", "other");
        assertReason(evaluate(data), "PVS1_POLICY_SELECTION_IDENTITY_INVALID");
        bind(data); data.policy.put("policyVersion", "2");
        assertReason(evaluate(data), "PVS1_POLICY_SELECTION_IDENTITY_INVALID");
        bind(data); ((ObjectNode) data.policy.path("scope")).put("moi", "AUTOSOMAL_RECESSIVE"); bind(data);
        assertReason(evaluate(data), "PVS1_POLICY_NOT_APPLICABLE");
        assertEquals("UNKNOWN", evaluate(data).at("/criterionApplicability/status").asText());
    }

    @Test void modelHashAndSequenceExonAndJunctionContradictionsFailClosed() throws Exception {
        Inputs data = nonsense(1, 1, 31);
        data.model.put("cdsSequence", data.model.path("cdsSequence").asText().replaceFirst("CGA", "TGA"));
        assertReason(evaluate(data), "PVS1_TRANSCRIPT_MODEL_IDENTITY_INVALID");
        bind(data); assertReason(evaluate(data), "PVS1_TRANSCRIPT_MODEL_SEQUENCE_INVALID");
        data = nonsense(1, 1, 31);
        data.model.put("lastJunctionCdsPosition", 500); bind(data);
        assertReason(evaluate(data), "PVS1_TRANSCRIPT_MODEL_JUNCTION_INVALID");
        data = nonsense(1, 1, 31);
        ((ObjectNode) data.model.path("exons").path(1)).put("genomicStart", 1001); bind(data);
        assertReason(evaluate(data), "PVS1_TRANSCRIPT_MODEL_EXON_INVALID");
    }

    @Test void populationOnlyConfirmationAndNonNativeLineageRemainClosed() throws Exception {
        Inputs data = nonsense(1, 1, 31);
        ((ObjectNode) data.context.path("transcript")).put("selectionPolicy", AcmgRecommendations.PM2_CONTEXT_SELECTION);
        refresh(data); assertReason(evaluate(data), "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED");
        data = nonsense(1, 1, 31);
        ((ObjectNode) data.context.at("/variant/assemblyLineage")).put("mappingStatus", "MAPPED");
        assertEquals("NOT_EVALUATED", evaluate(data).at("/evaluationResult/status").asText());
    }

    @Test void spliceStartLossAndTerminalPtcRemainSeparatedBranches() throws Exception {
        Inputs data = nonsense(1, 1, 31);
        mutateAnnotation(data, "consequence", "stop_gained&splice_region_variant");
        assertReason(evaluate(data), "PVS1_SPLICE_BRANCH_NOT_IMPLEMENTED");
        mutateAnnotation(data, "consequence", "start_lost");
        assertReason(evaluate(data), "PVS1_START_LOSS_NOT_START_CODON");
        data = nonsense(1, 1, 499);
        assertReason(evaluate(data), "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED");
        assertEquals("UNKNOWN", evaluate(data).at("/evaluation/criterionApplicability/status").asText());
    }

    @Test void scientificRelevanceIsDistinctFromSupportedEngineeringExons() throws Exception {
        Inputs data = nonsense(1, 1, 199);
        ((ObjectNode) data.policy.path("nmdScope")).putArray("engineeringSupportedExonOrdinals").add(1).add(3);
        bind(data); assertReason(evaluate(data), "PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED");
        ((ObjectNode) data.policy.path("nmdScope")).putArray("engineeringSupportedExonOrdinals").add(1).add(2).add(3);
        bind(data); assertMet(evaluate(data));
        assertEquals(4, evaluate(data).at("/resolvedInput/scientificRelevantExonOrdinals").size());
        assertEquals(3, evaluate(data).at("/resolvedInput/engineeringSupportedExonOrdinals").size());
    }

    @ParameterizedTest @CsvSource({"STOP,1", "STOP,-1", "INSERTION,1", "INSERTION,-1", "DELETION,1", "DELETION,-1"})
    void engineeringLastExonCannotMasqueradeAsNmdEscape(String kind, int strand) throws Exception {
        Inputs data = "STOP".equals(kind) ? nonsense(1, strand, 199)
                : frameshift("INSERTION".equals(kind), strand, 300);
        ObjectNode included = evaluate(data);
        assertMet(included);
        assertEquals("PREDICTED_NMD", included.at("/resolvedInput/nmdPrediction").asText());
        var supported = ((ObjectNode) data.policy.path("nmdScope")).putArray("engineeringSupportedExonOrdinals");
        supported.add(1);
        if (!"STOP".equals(kind)) supported.add(2).add(3);
        bind(data);
        ObjectNode excluded = evaluate(data);
        assertReason(excluded, "PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED");
        assertFalse(excluded.path("resolvedInput").has("nmdPrediction"));
        assertEquals("UNKNOWN", excluded.at("/evaluation/criterionApplicability/status").asText());
        assertEquals("NONE", excluded.at("/evaluation/appliedStrength").asText());
    }

    @Test void noUnreviewedDefaultForStartPositionLongExonsOrMissingScientificSource() throws Exception {
        Inputs data = nonsense(1, 1, 31);
        ((ObjectNode) data.policy.path("nmdScope")).remove("minimumCdsPosition"); bind(data);
        assertReason(evaluate(data), "PVS1_NMD_SCOPE_NOT_REVIEWED");
        data = nonsense(1, 1, 31);
        ((ObjectNode) data.policy.path("nmdScope")).put("longExonScope", "ASSUME_SAFE"); bind(data);
        assertReason(evaluate(data), "PVS1_NMD_SCOPE_NOT_REVIEWED");
        data = nonsense(1, 1, 31);
        ((ObjectNode) data.policy.path("mechanism")).putArray("sourceIds").add("unbound-source"); bind(data);
        assertReason(evaluate(data), "PVS1_LOF_MECHANISM_NOT_ESTABLISHED");
    }

    @Test void snapshotsAndAlgorithmIdentityAreDeterministicAndDoNotMutateCallers() throws Exception {
        Inputs data = nonsense(1, 1, 31);
        ObjectNode profile = resourceProfile();
        TruncatingNmdPvs1Runtime runtime = new TruncatingNmdPvs1Runtime(mapper, profile, data.model);
        ObjectNode beforePolicy = data.policy.deepCopy();
        ObjectNode beforeSelection = data.selection.deepCopy();
        ObjectNode beforeContext = data.context.deepCopy();
        ObjectNode first = runtime.evaluate(data.context, data.candidate, data.policy, data.selection);
        profile.removeAll(); data.model.removeAll();
        assertEquals(first, runtime.evaluate(data.context, data.candidate, data.policy, data.selection));
        assertEquals(beforePolicy, data.policy); assertEquals(beforeSelection, data.selection); assertEquals(beforeContext, data.context);
        ObjectNode evaluation = ((ObjectNode) first.path("evaluation")).deepCopy();
        evaluation.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + adapter.canonicalSha256(evaluation), first.at("/evaluation/evaluationId").asText());
    }

    Inputs nonsense(int gene, int strand, int c) throws Exception {
        String cds = "ATG" + "CGA".repeat(198) + "TAA";
        Inputs data = inputs(gene, strand, cds, 150, 300, 450);
        int g = genomic(data.model, c);
        String ref = strand == 1 ? "C" : "G";
        String alt = strand == 1 ? "T" : "A";
        int protein = (c - 1) / 3 + 1;
        ObjectNode annotation = annotation(data, "stop_gained", c + "C>T", "Arg" + protein + "Ter",
                ((c - 1) / 150 + 1) + "/4", Integer.toString(protein), "R/*", "CGA/TGA");
        candidate(data, g, ref, alt, annotation); return data;
    }

    @ParameterizedTest @CsvSource({
            "1,1,A,G,GTG", "1,2,T,C,ACG", "1,3,G,A,ATA",
            "-1,1,A,G,GTG", "-1,2,T,C,ACG", "-1,3,G,A,ATA"})
    void startLossUsesIndependentSequenceRouteAndNeverNmdStrength(
            int strand, int cdsPosition, String codingRef, String codingAlt, String codon) throws Exception {
        Inputs data = inputs(1, strand, "ATGAAACCCATGAAATAA", 9);
        int position = genomic(data.model, cdsPosition);
        String alternateCodon = data.model.path("cdsSequence").asText().substring(0, cdsPosition - 1)
                + codingAlt + data.model.path("cdsSequence").asText().substring(cdsPosition);
        alternateCodon = alternateCodon.substring(0, 3);
        String proteinAlt = switch (alternateCodon) { case "GTG" -> "Val"; case "ACG" -> "Thr"; case "ATA" -> "Ile"; default -> throw new IllegalStateException(); };
        ObjectNode annotation = annotation(data, "start_lost&NMD_transcript_variant",
                cdsPosition + codingRef + ">" + codingAlt, "Met1" + proteinAlt,
                "1/2", "1", "M/" + proteinAlt.substring(0, 1), "ATG/" + alternateCodon);
        String genomicRef = strand == 1 ? codingRef : complementBase(codingRef);
        String genomicAlt = strand == 1 ? codingAlt : complementBase(codingAlt);
        candidate(data, position, genomicRef, genomicAlt, annotation);
        ObjectNode result = evaluate(data);
        assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("PVS1_START_LOSS_POLICY_UNRESOLVED",
                result.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals(codon, result.at("/startLossFacts/alternateCodon").asText());
        assertEquals(10, result.at("/startLossFacts/candidateRestartCdsPosition").asInt());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("pvs1-start-loss", result.at("/evaluation/deterministicTrace/evaluatorId").asText());
        assertFalse(result.at("/startLossFacts/clinicalScoringPerformed").asBoolean(true));
    }

    @Test void nmdPolicyCannotDeclareStartLossPolicyDispositionThroughAnUnvalidatedField() throws Exception {
        Inputs data = inputs(1, 1, "ATGAAACCCATGAAATAA", 9);
        ObjectNode annotation = annotation(data, "start_lost", "1A>G", "Met1Val",
                "1/2", "1", "M/V", "ATG/GTG");
        candidate(data, genomic(data.model, 1), "A", "G", annotation);
        data.policy.putObject("startLossAssessment").put("status", "PROHIBITED")
                .putObject("review").put("status", "REVIEWED_IMPLEMENTED");
        bind(data);

        ObjectNode result = evaluate(data);
        assertEquals("PVS1_START_LOSS_POLICY_UNRESOLVED", result.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals("INSUFFICIENT_INFORMATION", result.at("/startLossFacts/decisionOutcome").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
    }

    @Test void reviewedStartLossPolicyProhibitionMapsToNotApplicableWithoutBenignClassification() throws Exception {
        Inputs data = inputs(1, 1, "ATGAAACCCATGAAATAA", 9);
        ObjectNode annotation = annotation(data, "start_lost", "1A>G", "Met1Val",
                "1/2", "1", "M/V", "ATG/GTG");
        candidate(data, genomic(data.model, 1), "A", "G", annotation);
        data.policy.put("schemaVersion", "pvs1-start-loss-policy-v1");
        data.policy.putObject("startLossAssessment").put("status", "PROHIBITED")
                .putObject("review").put("status", "REVIEWED_IMPLEMENTED");
        bind(data);

        ObjectNode result = evaluate(data);
        assertEquals("NOT_APPLICABLE", result.at("/evaluation/criterionApplicability/status").asText());
        assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("PVS1_START_LOSS_POLICY_PROHIBITS_CRITERION",
                result.at("/evaluation/evaluationResult/reasonCode").asText());
    }

    private static String complementBase(String base) {
        return switch (base) { case "A" -> "T"; case "T" -> "A"; case "C" -> "G"; case "G" -> "C"; default -> throw new IllegalArgumentException(base); };
    }

    @Test void startLossReferenceAlleleConflictIsNotConvertedToNotApplicable() throws Exception {
        Inputs data = inputs(1, 1, "ATGAAACCCATGAAATAA", 9);
        ObjectNode annotation = annotation(data, "start_lost", "1A>G", "Met1Val", "1/2", "1", "M/V", "ATG/GTG");
        candidate(data, genomic(data.model, 1), "C", "G", annotation);
        ObjectNode result = evaluate(data);
        assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("PVS1_START_LOSS_REFERENCE_ALLELE_CONFLICT", result.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals("CONFLICT", result.at("/startLossFacts/status").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
    }

    Inputs frameshift(boolean insertion, int strand, int lastJunction) throws Exception {
        return frameshift(insertion, strand, new int[]{12, 24, 36, 48, 60, 72, 84, 96, lastJunction});
    }

    private Inputs frameshift(boolean insertion, int strand, int[] ends) throws Exception {
        // Fixed toy facts independent of runtime: +G after c30 yields stop64..66;
        // deleting one of repeated C bases at c31/c32 yields stop61..63.
        String cds = "ATG" + "CCA".repeat(19) + (insertion ? "CCTGAA" : "CTAACC") + "CCA".repeat(177) + "TAA";
        Inputs data = inputs(strand == 1 ? 1 : 2, strand, cds, ends);
        int c = strand == 1 ? 30 : insertion ? 31 : 33;
        int g = genomic(data.model, c);
        String ref = strand == 1 ? (insertion ? "A" : "AC") : (insertion ? "G" : "TG");
        String alt = strand == 1 ? (insertion ? "AG" : "A") : (insertion ? "GC" : "T");
        ObjectNode annotation = annotation(data, "frameshift_variant", insertion ? "30_31insG" : "31delC",
                insertion ? "Pro11AlafsTer12" : "Pro11HisfsTer11", "3/10",
                insertion ? "10-11" : "11", insertion ? "-/X" : "P/X", insertion ? "-/G" : "CCA/CA");
        candidate(data, g, ref, alt, annotation); return data;
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void simpleFrameshiftStopSpansTwoExonsBeforeTheLastJunction(int strand) throws Exception {
        // The insertion shifts the c.64 boundary to 65. TGA at 64–66 uses both exons.
        Inputs data = frameshift(true, strand, new int[]{12, 24, 36, 48, 64, 72, 84, 96, 120});
        var positive = evaluate(data);
        assertEquals("MET", positive.at("/evaluation/evaluationResult/status").asText(), positive::toPrettyString);
        assertEquals("VERY_STRONG", positive.at("/evaluation/appliedStrength").asText());
        assertEquals(mapper.createArrayNode().add(5).add(6), positive.at("/resolvedInput/ptcExonOrdinals"));
        assertEquals(121, positive.at("/resolvedInput/lastJunctionCdsPosition").asInt());
        assertEquals(55, positive.at("/resolvedInput/distanceToLastJunction").asInt());

        var nmd = data.policy.withObject("nmdScope");
        nmd.putArray("engineeringSupportedExonOrdinals").add(3).add(5);
        bind(data);
        assertReason(evaluate(data), "PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED");
        nmd.putArray("relevantExonOrdinals").add(3).add(5);
        bind(data);
        assertReason(evaluate(data), "PVS1_EXON_RELEVANCE_UNRESOLVED");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aSourceBoundNonRelevantEventExonIsNegativeAndOmissionRemainsUnknown(boolean frameshift) throws Exception {
        Inputs data = frameshift ? frameshift(true, 1, 120) : nonsense(1, 1, 199);
        int exon = frameshift ? 3 : 2;
        var nmd = data.policy.withObject("nmdScope");
        for (String name : List.of("relevantExonOrdinals", "engineeringSupportedExonOrdinals")) {
            var ordinals = (com.fasterxml.jackson.databind.node.ArrayNode) nmd.path(name);
            for (int index = 0; index < ordinals.size(); index++) {
                if (ordinals.path(index).asInt() == exon) { ordinals.remove(index); break; }
            }
        }
        bind(data);
        assertReason(evaluate(data), "PVS1_EXON_RELEVANCE_UNRESOLVED");
        nmd.putArray("nonRelevantExonOrdinals").add(exon);
        bind(data);
        var result = evaluate(data);
        assertEquals("NOT_MET", result.at("/evaluation/evaluationResult/status").asText());
        assertEquals("PVS1_EXON_NOT_RELEVANT", result.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        nmd.withArray("relevantExonOrdinals").add(exon);
        bind(data);
        assertReason(evaluate(data), "PVS1_EXON_RELEVANCE_CONFLICT");
    }

    Inputs inputs(int gene, int strand, String cds, int... ends) {
        Inputs data = new Inputs();
        String transcript = "NM_00000" + gene + ".1";
        data.model = mapper.createObjectNode().put("schemaVersion", "pvs1-transcript-model-v1")
                .put("modelId", "synthetic-gene-" + gene).put("modelVersion", "1").put("assembly", "GRCh38")
                .put("contig", Integer.toString(gene)).put("strand", strand).put("hgncId", "HGNC:" + gene)
                .put("geneSymbol", "SYN_GENE_" + gene).put("transcript", transcript).put("refseqTranscript", transcript)
                .put("protein", "NP_00000" + gene + ".1").put("cdsSequence", cds)
                .put("cdsLength", cds.length()).put("proteinLength", cds.length() / 3 - 1)
                .put("lastJunctionCdsPosition", ends[ends.length - 1]);
        data.model.putArray("sourceRecords").add(source());
        var exons = data.model.putArray("exons"); int start = 1;
        for (int i = 0; i <= ends.length; i++) {
            int end = i == ends.length ? cds.length() : ends[i];
            int genomic = strand == 1 ? 2000 + i * 1000 : 20000 - i * 1000;
            exons.addObject().put("ordinal", i + 1).put("cdsStart", start).put("cdsEnd", end)
                    .put("genomicStart", genomic).put("genomicEnd", genomic + end - start); start = end + 1;
        }
        data.policy = mapper.createObjectNode().put("schemaVersion", "pvs1-truncating-nmd-policy-v1")
                .put("policyId", "synthetic-nmd-gene-" + gene).put("policyVersion", "1").put("appliedStrength", "VERY_STRONG");
        data.policy.putArray("criterionIds").add(CRITERION);
        data.policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "SYNTHETIC_ENGINEERING_ONLY");
        ObjectNode scope = data.policy.putObject("scope").put("hgncId", "HGNC:" + gene)
                .put("mondoId", "MONDO:000000" + gene).put("moi", "AUTOSOMAL_DOMINANT").put("assembly", "GRCh38")
                .put("variantType", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT").put("transcriptMode", "EXACT_ACCESSIONS");
        scope.putArray("transcripts").addObject().put("accession", transcript.substring(0, transcript.lastIndexOf('.'))).put("version", 1);
        data.policy.putArray("scientificSources").add(source());
        data.policy.putArray("authoritySourceIds").add("authority-clingen-svi-pvs1-2018");
        data.policy.putObject("mechanism").put("status", "ESTABLISHED_LOSS_OF_FUNCTION")
                .put("mechanismId", "SYNTHETIC_MECHANISM_ONLY").putArray("sourceIds").add("synthetic-source");
        ObjectNode nmd = data.policy.putObject("nmdScope").put("minimumCdsPosition", 4).put("minimumDistanceToLastJunction", 51)
                .put("startProximalScope", "REVIEWED_FOR_INCLUDED_RANGE").put("engineeringScopeRationale", "Synthetic test-only domain; not scientific acceptance")
                .put("exonRelevance", "SYNTHETIC_EXON_RELEVANCE").put("longExonScope", "EXCLUDE_ABOVE_MAXIMUM").put("maximumExonLength", 400);
        nmd.putArray("sourceIds").add("synthetic-source");
        var relevant = nmd.putArray("relevantExonOrdinals"); var supported = nmd.putArray("engineeringSupportedExonOrdinals");
        for (int i = 1; i <= exons.size(); i++) { relevant.add(i); if (i < exons.size()) supported.add(i); }
        return data;
    }

    private ObjectNode source() {
        return mapper.createObjectNode().put("sourceId", "synthetic-source").put("version", "1")
                .put("url", "https://example.invalid/synthetic-only").put("sha256", "a".repeat(64));
    }

    void candidate(Inputs data, int genomic, String ref, String alt, ObjectNode annotation) throws Exception {
        var key = new NormalizedVariantKey("GRCh38", data.model.path("contig").asText(), genomic, ref, alt);
        data.candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        String transcript = data.model.path("transcript").asText();
        data.context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key, transcript.substring(0, transcript.lastIndexOf('.')));
        ((ObjectNode) data.context.path("gene")).put("hgncId", data.model.path("hgncId").asText()).put("symbol", data.model.path("geneSymbol").asText());
        ((ObjectNode) data.context.path("disease")).put("mondoId", data.policy.at("/scope/mondoId").asText()).put("label", "Synthetic development condition");
        data.context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) data.context.path("transcript")).put("version", 1).put("annotationSourceVersion", "116.0");
        ((ObjectNode) data.context.path("variant")).put("variantType", ref.length() == alt.length() ? "SNV" : ref.length() > alt.length() ? "SMALL_DELETION" : "SMALL_INSERTION");
        refresh(data);
    }

    ObjectNode annotation(Inputs data, String consequence, String coding, String protein, String exon, String position, String amino, String codons) {
        return mapper.createObjectNode().put("geneSymbol", data.model.path("geneSymbol").asText())
                .put("transcriptAccession", data.model.path("transcript").asText())
                .put("hgvsCoding", data.model.path("transcript").asText() + ":c." + coding)
                .put("hgvsProtein", data.model.path("protein").asText() + ":p." + protein)
                .put("consequence", consequence).put("exon", exon).put("proteinPosition", position)
                .put("aminoAcids", amino).put("codons", codons).put("source", "VEP_SYNTHETIC").put("status", "COMPLETED")
                .put("version", "116.0").put("timestamp", Instant.parse("2026-09-13T00:00:00Z").toString()).put("maneSelect", true);
    }

    void mutateAnnotation(Inputs data, String field, String value) throws Exception {
        ObjectNode annotation = mapper.valueToTree(data.candidate.getTranscriptAnnotations().get(0));
        annotation.retain(List.of("intron", "consequence", "lofFlags", "hgvsCoding", "hgvsProtein", "entrezGeneId", "geneSymbol",
                "transcriptAccession", "geneId", "transcriptId", "exon", "canonical", "timestamp", "manePlusClinical", "lofFilter",
                "codons", "lof", "impact", "proteinPosition", "transcriptSource", "status", "aminoAcids", "source", "maneSelect", "version"));
        annotation.put(field, value); data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
    }

    private void refresh(Inputs data) { data.context.put("contextId", adapter.canonicalContextId(data.context)); bind(data); }
    void bind(Inputs data) {
        data.policy.putObject("transcriptModelBinding").put("modelId", data.model.path("modelId").asText())
                .put("modelVersion", data.model.path("modelVersion").asText()).put("canonicalSha256", adapter.canonicalSha256(data.model));
        data.selection = mapper.createObjectNode().put("schemaVersion", "acmg-specification-selection-v1")
                .put("outcome", "SPECIALIZED_POLICY_SELECTED").put("contextId", data.context.path("contextId").asText())
                .put("policyId", data.policy.path("policyId").asText()).put("policyVersion", data.policy.path("policyVersion").asText())
                .put("policySha256", adapter.canonicalSha256(data.policy)).put("catalogId", "synthetic-catalog").put("catalogVersion", "1")
                .put("catalogEntryId", "synthetic-clause").put("catalogSha256", "b".repeat(64))
                .put("searchedAt", "2026-09-13T00:00:00Z").put("searchScope", "Synthetic engineering only");
        data.selection.putArray("criterionIds").add(CRITERION); data.selection.putArray("sourceIds").add("synthetic-source");
        data.selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(data.selection));
    }

    int genomic(ObjectNode model, int cds) {
        for (JsonNode exon : model.path("exons")) if (cds >= exon.path("cdsStart").asInt() && cds <= exon.path("cdsEnd").asInt())
            return model.path("strand").asInt() == 1 ? exon.path("genomicStart").asInt() + cds - exon.path("cdsStart").asInt()
                    : exon.path("genomicEnd").asInt() - cds + exon.path("cdsStart").asInt();
        throw new IllegalArgumentException("Synthetic CDS outside exons");
    }

    ObjectNode resourceProfile() {
        // Only the existing VEP resource and general criterion registry identity are reused.
        ObjectNode result = Hnf1aPvs1Runtime.loadProfile(mapper).deepCopy();
        result.retain("resources", "registryBinding"); result.put("vepResourceId", "VEP_116_GRCH38"); return result;
    }
    ObjectNode evaluate(Inputs data) {
        return new TruncatingNmdPvs1Runtime(mapper, resourceProfile(), data.model)
                .evaluate(data.context, data.candidate, data.policy, data.selection);
    }
    private void assertMet(ObjectNode result) { assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString()); }
    private void assertReason(ObjectNode result, String reason) {
        JsonNode status = result.has("evaluation") ? result.at("/evaluation/evaluationResult") : result.path("evaluationResult");
        assertEquals("NOT_EVALUATED", status.path("status").asText(), result.toPrettyString());
        assertEquals(reason, status.path("reasonCode").asText(), result.toPrettyString());
    }
    static final class Inputs {
        ObjectNode model, policy, selection, context;
        VariantInterpretation candidate;
    }
}
