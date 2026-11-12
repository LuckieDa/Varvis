package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlphaMissenseProviderTest {

    private final VariantAnalysisProperties properties = new VariantAnalysisProperties();
    private final AlphaMissenseProvider provider = new AlphaMissenseProvider(properties);
    private final NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");

    @Test
    void exactSelectionDoesNotTakeAnotherTranscriptsHigherScore() {
        TranscriptConsequence selected = selected("ENST000001.4", "ENSP000001.2", "34", "K/R");

        EvidenceItem evidence = provider.resolveExactRecords(Arrays.asList(
                row("ENST000002.9", "Q34W", "0.9999"),
                row("ENST000001.4", "K34R", "0.1200")
        ), key, selected);

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("0.1200", evidence.getRawValue());
        assertEquals(0.12d, evidence.getRawScore());
        assertEquals("ENST000001.4", evidence.getTranscriptId());
        assertEquals("ENST000001.4", evidence.getDetails().get("transcriptId"));
        assertEquals("K34R", evidence.getDetails().get("proteinVariant"));
        assertEquals("ENSP000001.2", evidence.getDetails().get("selectedProteinIdentity"));
        assertTrue((Boolean) evidence.getDetails().get("exactAlleleMatch"));
        assertTrue((Boolean) evidence.getDetails().get("transcriptProteinMatch"));
    }

    @Test
    void exactSelectionRejectsTranscriptOrProteinMismatch() {
        EvidenceItem transcriptMismatch = provider.resolveExactRecords(
                Collections.singletonList(row("ENST000001.4", "K34R", "0.8000")),
                key,
                selected("ENST000002.4", "ENSP000002.2", "34", "K/R")
        );
        EvidenceItem proteinMismatch = provider.resolveExactRecords(
                Collections.singletonList(row("ENST000001.4", "K34R", "0.8000")),
                key,
                selected("ENST000001.4", "ENSP000001.2", "35", "K/R")
        );

        assertEquals("UNAVAILABLE", transcriptMismatch.getApplicability());
        assertEquals("HIT", transcriptMismatch.getDetails().get("queryOutcomeStatus"));
        assertEquals("TRANSCRIPT_PROTEIN_MISMATCH",
                transcriptMismatch.getDetails().get("resourceApplicabilityReasonCode"));
        assertTrue((Boolean) transcriptMismatch.getDetails().get("exactAlleleMatch"));
        assertFalse((Boolean) transcriptMismatch.getDetails().get("transcriptProteinMatch"));
        assertEquals("ALPHAMISSENSE:GRCh38|1|100|A|G:ALLELE_RECORDS",
                transcriptMismatch.getSourceRecordId());
        assertEquals(Collections.singletonList("ENST000001.4"),
                transcriptMismatch.getDetails().get("observedTranscriptIds"));
        assertNull(transcriptMismatch.getRawValue());
        assertEquals("UNAVAILABLE", proteinMismatch.getApplicability());
        assertEquals("HIT", proteinMismatch.getDetails().get("queryOutcomeStatus"));
    }

    @Test
    void noAlleleRecordRemainsNoRecord() {
        EvidenceItem evidence = provider.resolveExactRecords(
                Collections.singletonList(
                        "chr1\t101\tA\tG\thg38\tP12345\tENST000001.4\tK34R\t0.8000\tlikely_pathogenic"),
                key,
                selected("ENST000001.4", "ENSP000001.2", "34", "K/R")
        );

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("NO_RECORD", evidence.getDetails().get("queryOutcomeStatus"));
        assertFalse((Boolean) evidence.getDetails().get("exactAlleleMatch"));
    }

    @Test
    void duplicateExactMatchesAreNotCollapsedOrResolvedByScore() {
        TranscriptConsequence selected = selected("ENST000001.4", "ENSP000001.2", "34", "K/R");

        EvidenceItem evidence = provider.resolveExactRecords(Arrays.asList(
                row("ENST000001.4", "K34R", "0.1000"),
                row("ENST000001.4", "K34R", "0.9000")
        ), key, selected);

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("HIT", evidence.getDetails().get("queryOutcomeStatus"));
        assertEquals("ALPHAMISSENSE_MATCH_CONFLICT",
                evidence.getDetails().get("resourceApplicabilityReasonCode"));
    }

    @Test
    void explicitPathRejectsMissenseSubstringBeforeResourceLookup() {
        properties.getAlphaMissense().setEnabled(true);
        TranscriptConsequence selected = selected(
                "ENST000001.4", "ENSP000001.2", "34", "K/R", "not_missense_variant");
        VariantInterpretation nonMissense = variant("not_missense_variant");

        EvidenceItem evidence = provider.annotateSelectedTranscripts(
                Collections.singletonList(nonMissense),
                new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES),
                Collections.singletonMap(key, selected)
        ).get(key).get(0);

        assertEquals("NOT_APPLICABLE", evidence.getApplicability());
    }

    @Test
    void explicitPathAcceptsMissenseAmongVepTerms() {
        TranscriptConsequence selected = selected(
                "ENST000001.4", "ENSP000001.2", "34", "K/R",
                "missense_variant&splice_region_variant");

        EvidenceItem evidence = provider.annotateSelectedTranscripts(
                Collections.singletonList(variant("missense_variant&splice_region_variant")),
                new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES),
                Collections.singletonMap(key, selected)
        ).get(key).get(0);

        assertEquals("DISABLED", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("UNKNOWN", evidence.getDetails().get("resourceApplicabilityStatus"));
    }

    private String row(String transcriptId, String proteinVariant, String score) {
        return "chr1\t100\tA\tG\thg38\tP12345\t" + transcriptId + "\t"
                + proteinVariant + "\t" + score + "\tlikely_pathogenic";
    }

    private TranscriptConsequence selected(String transcriptId,
                                           String proteinId,
                                           String proteinPosition,
                                           String aminoAcids) {
        return selected(transcriptId, proteinId, proteinPosition, aminoAcids, "missense_variant");
    }

    private TranscriptConsequence selected(String transcriptId,
                                           String proteinId,
                                           String proteinPosition,
                                           String aminoAcids,
                                           String consequence) {
        String baseTranscript = transcriptId.substring(0, transcriptId.lastIndexOf('.'));
        return new TranscriptConsequence(
                "GENE1", "ENSG000001", baseTranscript,
                transcriptId + ":c.100A>G", proteinId + ":p.Lys34Arg", consequence,
                "ENSG000001", baseTranscript, "protein_coding", "MODERATE",
                "1/10", null, true, true, null,
                proteinPosition, aminoAcids, "aAa/aGa",
                null, null, null,
                "VEP_CACHED", "COMPLETED", "116.0", Instant.parse("2026-09-02T00:00:00Z")
        );
    }

    private VariantInterpretation variant(String consequence) {
        return new VariantInterpretation(
                key.canonicalId(),
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "chr1|100|A|G|1"
                ),
                Collections.singletonList(new TranscriptConsequence(
                        null, null, null, null, null, consequence
                )),
                Collections.emptyList()
        );
    }
}
