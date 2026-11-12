package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrioEvidenceReportMapperTest {

    private final TrioEvidenceReportMapper mapper = new TrioEvidenceReportMapper();

    @Test
    void mapsEvidenceIntoReportWithoutRankingEffect() {
        SegregationEvidence deNovo = new SegregationEvidence(
                "v1",
                "GENE1",
                InheritanceModelCandidate.DE_NOVO_CANDIDATE,
                SegregationConfidence.SUPPORTED,
                ParentalOrigin.NONE,
                Collections.<String>emptyList(),
                Arrays.asList("Proband carries the allele and both parents are confirmed reference."),
                Collections.<String>emptyList());
        SegregationEvidence inherited = new SegregationEvidence(
                "v2",
                "GENE2",
                InheritanceModelCandidate.INHERITED_CANDIDATE,
                SegregationConfidence.POSSIBLE,
                ParentalOrigin.MATERNAL,
                Collections.<String>emptyList(),
                Arrays.asList("The alternate allele is observed in at least one parent."),
                Collections.<String>emptyList());

        TrioEvidenceReport report = mapper.toReport(null, Arrays.asList(deNovo, inherited));

        assertEquals(2, report.getEntries().size());
        assertEquals(Integer.valueOf(1), report.getModelCounts().get(InheritanceModelCandidate.DE_NOVO_CANDIDATE));
        assertEquals(Integer.valueOf(1), report.getModelCounts().get(InheritanceModelCandidate.INHERITED_CANDIDATE));
        assertEquals(TrioEvidenceReport.RANKING_EFFECT, report.getRankingEffect());
        assertEquals(TrioEvidenceReport.HARD_GATE_POLICY, report.getHardGatePolicy());
        assertTrue(report.getEntries().get(0).getExplanation().contains("confirmed reference"));
    }
}
