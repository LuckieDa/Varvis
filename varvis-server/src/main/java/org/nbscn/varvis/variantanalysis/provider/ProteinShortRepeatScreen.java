package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Exact sequence observations, not a clinically calibrated repeat/non-repeat classifier. */
public final class ProteinShortRepeatScreen {
    public static final String VERSION = "1.0.0";
    private ProteinShortRepeatScreen() { }

    public static ObjectNode scan(ObjectMapper mapper, String protein, int start, int end) {
        var out = mapper.createObjectNode().put("methodId", "exact-short-tandem-candidate")
                .put("methodVersion", VERSION).put("minimumCopies", 3).put("minimumResidues", 6)
                .put("maximumPeriod", 12).put("maximumProteinLength", 6000)
                .put("coordinateSystem", "ONE_BASED_CLOSED").put("nonRepeatQualified", false)
                .put("clinicalCalibration", "NOT_ESTABLISHED").put("status", "UNAVAILABLE");
        var hits = out.putArray("hits");
        if (protein.length() > 6000 || !protein.matches("[ACDEFGHIKLMNPQRSTVWY]+"))
            return out.put("reasonCode", "PM4_SHORT_REPEAT_SEQUENCE_UNSUPPORTED");
        if (start < 1 || end < start || end > protein.length())
            return out.put("reasonCode", "PM4_SHORT_REPEAT_RANGE_INVALID");
        out.put("sequenceSha256", CodingTranscriptMaterialSupply.sha(protein)).put("start", start).put("end", end);
        for (int period = 1; period <= 12; period++) for (int i = 0; i + period * 3 <= protein.length(); i++) {
            if (Thread.currentThread().isInterrupted()) return out.put("reasonCode", "PM4_SHORT_REPEAT_INTERRUPTED");
            // A left-extendible rotation is represented by its maximal interval, never clipped to the edit.
            if (i > 0 && protein.charAt(i - 1) == protein.charAt(i + period - 1)) continue;
            String motif = protein.substring(i, i + period);
            boolean primitive = true;
            for (int p = 1; p < period; p++) if (period % p == 0 && motif.equals(motif.substring(0, p).repeat(period / p))) primitive = false;
            if (!primitive) continue;
            int j = i + period;
            while (j < protein.length() && protein.charAt(j) == protein.charAt(j - period)) j++;
            if (j - i < Math.max(6, period * 3) || i + 1 > end || j < start) continue;
            hits.addObject().put("start", i + 1).put("end", j).put("period", period)
                    .put("motif", motif).put("completeCopies", (j - i) / period).put("trailingResidues", (j - i) % period);
        }
        return out.put("status", "COMPLETE").put("reasonCode", hits.isEmpty()
                ? "PM4_SHORT_REPEAT_NO_CANDIDATE_NOT_NEGATIVE_PROOF" : "PM4_SHORT_REPEAT_CANDIDATE_REQUIRES_QUALIFICATION");
    }
}
