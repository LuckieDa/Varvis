package org.nbscn.varvis.variantanalysis.provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpliceAiEvidenceSupport {

    public static final String SOURCE_LOCAL = "LOCAL_SPLICEAI";
    public static final String SOURCE_VCF_INFO = "VCF_INFO_SPLICEAI";

    private SpliceAiEvidenceSupport() {
    }

    public static List<Prediction> predictionsFromInfoValue(String infoValue, String alternate) {
        if (!hasText(infoValue)) {
            return Collections.emptyList();
        }
        List<Prediction> predictions = new ArrayList<Prediction>();
        for (String rawPrediction : infoValue.split(",")) {
            Prediction prediction = predictionFromPipeValue(rawPrediction, alternate);
            if (prediction != null) {
                predictions.add(prediction);
            }
        }
        return predictions;
    }

    public static Prediction predictionFromPipeValue(String rawPrediction, String alternate) {
        if (!hasText(rawPrediction)) {
            return null;
        }
        String[] values = rawPrediction.split("\\|", -1);
        if (values.length < 10 || !sameAllele(alternate, values[0])) {
            return null;
        }
        return predictionFromScores(
                values[0],
                values[1],
                values[2],
                values[3],
                values[4],
                values[5],
                values[6],
                values[7],
                values[8],
                values[9]
        );
    }

    public static Prediction predictionFromScores(String allele,
                                                  String geneSymbol,
                                                  String acceptorGainScore,
                                                  String acceptorLossScore,
                                                  String donorGainScore,
                                                  String donorLossScore,
                                                  String acceptorGainPosition,
                                                  String acceptorLossPosition,
                                                  String donorGainPosition,
                                                  String donorLossPosition) {
        Prediction prediction = new Prediction(
                allele,
                blankToNull(geneSymbol),
                parseDouble(acceptorGainScore),
                parseDouble(acceptorLossScore),
                parseDouble(donorGainScore),
                parseDouble(donorLossScore),
                blankToNull(acceptorGainPosition),
                blankToNull(acceptorLossPosition),
                blankToNull(donorGainPosition),
                blankToNull(donorLossPosition)
        );
        return prediction.getMaximumDeltaScore() == null ? null : prediction;
    }

    public static Prediction best(List<Prediction> predictions) {
        Prediction best = null;
        if (predictions == null) {
            return null;
        }
        for (Prediction prediction : predictions) {
            if (prediction == null || prediction.getMaximumDeltaScore() == null) {
                continue;
            }
            if (best == null
                    || best.getMaximumDeltaScore() == null
                    || prediction.getMaximumDeltaScore() > best.getMaximumDeltaScore()) {
                best = prediction;
            }
        }
        return best;
    }

    public static Map<String, Object> baseDetails(String source,
                                                  String status,
                                                  String interpretation) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", source);
        details.put("status", status);
        details.put("interpretation", interpretation);
        details.put("timestamp", Instant.now().toString());
        return details;
    }

    public static Map<String, Object> applicableDetails(String source,
                                                        Prediction best,
                                                        List<Prediction> predictions,
                                                        String interpretation) {
        Map<String, Object> details = baseDetails(source, "APPLICABLE", interpretation);
        details.put("value", best == null ? null : best.getMaximumDeltaScore());
        if (best != null) {
            details.putAll(best.toDetails());
        }
        if (predictions != null && !predictions.isEmpty()) {
            List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
            for (Prediction prediction : predictions) {
                values.add(prediction.toDetails());
            }
            details.put("predictions", values);
        }
        return details;
    }

    public static String interpretation(Double score,
                                        double highThreshold,
                                        double moderateThreshold,
                                        double lowThreshold) {
        if (score == null) {
            return "SpliceAI evidence has no usable delta score; absence is not interpreted as low risk";
        }
        if (score >= highThreshold) {
            return "SpliceAI maximum delta score supports high-priority splice-impact review; this is not a clinical classification";
        }
        if (score >= moderateThreshold) {
            return "SpliceAI maximum delta score supports moderate splice-impact review; this is not a clinical classification";
        }
        if (score >= lowThreshold) {
            return "SpliceAI maximum delta score provides low computational splice support; this is not benign evidence";
        }
        return "SpliceAI maximum delta score is below the configured low threshold; this is not benign evidence";
    }

    private static boolean sameAllele(String expected, String observed) {
        return hasText(expected) && expected.equalsIgnoreCase(observed);
    }

    private static Double parseDouble(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static final class Prediction {

        private final String allele;
        private final String geneSymbol;
        private final Double acceptorGainDeltaScore;
        private final Double acceptorLossDeltaScore;
        private final Double donorGainDeltaScore;
        private final Double donorLossDeltaScore;
        private final String acceptorGainDeltaPosition;
        private final String acceptorLossDeltaPosition;
        private final String donorGainDeltaPosition;
        private final String donorLossDeltaPosition;
        private final Double maximumDeltaScore;
        private final String maximumDeltaEvent;
        private final String maximumDeltaPosition;

        Prediction(String allele,
                   String geneSymbol,
                   Double acceptorGainDeltaScore,
                   Double acceptorLossDeltaScore,
                   Double donorGainDeltaScore,
                   Double donorLossDeltaScore,
                   String acceptorGainDeltaPosition,
                   String acceptorLossDeltaPosition,
                   String donorGainDeltaPosition,
                   String donorLossDeltaPosition) {
            this.allele = allele;
            this.geneSymbol = geneSymbol;
            this.acceptorGainDeltaScore = acceptorGainDeltaScore;
            this.acceptorLossDeltaScore = acceptorLossDeltaScore;
            this.donorGainDeltaScore = donorGainDeltaScore;
            this.donorLossDeltaScore = donorLossDeltaScore;
            this.acceptorGainDeltaPosition = acceptorGainDeltaPosition;
            this.acceptorLossDeltaPosition = acceptorLossDeltaPosition;
            this.donorGainDeltaPosition = donorGainDeltaPosition;
            this.donorLossDeltaPosition = donorLossDeltaPosition;
            Maximum maximum = maximum();
            this.maximumDeltaScore = maximum.score;
            this.maximumDeltaEvent = maximum.event;
            this.maximumDeltaPosition = maximum.position;
        }

        public Double getMaximumDeltaScore() {
            return maximumDeltaScore;
        }

        public Map<String, Object> toDetails() {
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("allele", allele);
            details.put("geneSymbol", geneSymbol);
            details.put("gene", geneSymbol);
            details.put("acceptorGainDeltaScore", acceptorGainDeltaScore);
            details.put("acceptorLossDeltaScore", acceptorLossDeltaScore);
            details.put("donorGainDeltaScore", donorGainDeltaScore);
            details.put("donorLossDeltaScore", donorLossDeltaScore);
            details.put("DS_AG", acceptorGainDeltaScore);
            details.put("DS_AL", acceptorLossDeltaScore);
            details.put("DS_DG", donorGainDeltaScore);
            details.put("DS_DL", donorLossDeltaScore);
            details.put("acceptorGainDeltaPosition", acceptorGainDeltaPosition);
            details.put("acceptorLossDeltaPosition", acceptorLossDeltaPosition);
            details.put("donorGainDeltaPosition", donorGainDeltaPosition);
            details.put("donorLossDeltaPosition", donorLossDeltaPosition);
            details.put("DP_AG", acceptorGainDeltaPosition);
            details.put("DP_AL", acceptorLossDeltaPosition);
            details.put("DP_DG", donorGainDeltaPosition);
            details.put("DP_DL", donorLossDeltaPosition);
            details.put("maximumDeltaScore", maximumDeltaScore);
            details.put("maxDeltaScore", maximumDeltaScore);
            details.put("maximumDeltaEvent", maximumDeltaEvent);
            details.put("maxEvent", maximumDeltaEvent);
            details.put("maximumDeltaPosition", maximumDeltaPosition);
            details.put("maxDeltaPosition", maximumDeltaPosition);
            return details;
        }

        private Maximum maximum() {
            Maximum maximum = new Maximum();
            maximum.consider(acceptorGainDeltaScore, "acceptor_gain", acceptorGainDeltaPosition);
            maximum.consider(acceptorLossDeltaScore, "acceptor_loss", acceptorLossDeltaPosition);
            maximum.consider(donorGainDeltaScore, "donor_gain", donorGainDeltaPosition);
            maximum.consider(donorLossDeltaScore, "donor_loss", donorLossDeltaPosition);
            return maximum;
        }
    }

    private static final class Maximum {

        private Double score;
        private String event;
        private String position;

        private void consider(Double candidateScore, String candidateEvent, String candidatePosition) {
            if (candidateScore == null) {
                return;
            }
            if (score == null || candidateScore > score) {
                score = candidateScore;
                event = candidateEvent;
                position = candidatePosition;
            }
        }
    }
}
