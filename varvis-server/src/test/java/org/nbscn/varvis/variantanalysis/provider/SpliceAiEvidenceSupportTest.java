package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpliceAiEvidenceSupportTest {

    @Test
    void parsesAlleleMatchedPredictionAndMaximumEvent() {
        List<SpliceAiEvidenceSupport.Prediction> predictions =
                SpliceAiEvidenceSupport.predictionsFromInfoValue(
                        "G|GENE1|0.01|0.02|0.91|0.04|1|2|-3|4",
                        "G"
                );

        SpliceAiEvidenceSupport.Prediction prediction = predictions.get(0);
        assertEquals(0.91d, prediction.getMaximumDeltaScore());
        assertEquals("donor_gain", prediction.toDetails().get("maxEvent"));
        assertEquals("-3", prediction.toDetails().get("maxDeltaPosition"));
        assertEquals("GENE1", prediction.toDetails().get("gene"));
        assertEquals(0.01d, prediction.toDetails().get("DS_AG"));
        assertEquals(0.02d, prediction.toDetails().get("DS_AL"));
        assertEquals(0.91d, prediction.toDetails().get("DS_DG"));
        assertEquals(0.04d, prediction.toDetails().get("DS_DL"));
        assertEquals("1", prediction.toDetails().get("DP_AG"));
        assertEquals("2", prediction.toDetails().get("DP_AL"));
        assertEquals("-3", prediction.toDetails().get("DP_DG"));
        assertEquals("4", prediction.toDetails().get("DP_DL"));
    }

    @Test
    void rejectsSamePositionDifferentAltAndMalformedValues() {
        assertTrue(SpliceAiEvidenceSupport.predictionsFromInfoValue(
                "T|GENE1|0.99|0.02|0.01|0.01|1|2|3|4",
                "G"
        ).isEmpty());
        assertTrue(SpliceAiEvidenceSupport.predictionsFromInfoValue("malformed", "G").isEmpty());
    }

    @Test
    void classifiesThresholdInterpretationWithoutClinicalConclusion() {
        assertTrue(SpliceAiEvidenceSupport.interpretation(0.81d, 0.8d, 0.5d, 0.2d)
                .contains("high-priority"));
        assertTrue(SpliceAiEvidenceSupport.interpretation(0.10d, 0.8d, 0.5d, 0.2d)
                .contains("not benign"));
    }
}
