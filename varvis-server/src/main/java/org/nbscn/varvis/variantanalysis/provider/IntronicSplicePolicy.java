package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import java.util.*;

/** Shared intronic/synonymous consequence-first business boundary. All distance thresholds come from the frozen BP7 profile. */
public final class IntronicSplicePolicy {
    private final int donor;
    private final int acceptor;
    public IntronicSplicePolicy(ObjectMapper mapper) {
        var p = SpliceAiCriterionEvaluator.loadBp7Profile(mapper).at("/bp7PositionRules/intronicVariant");
        donor = p.path("donorMinimumSignedOffsetInclusive").intValue();
        acceptor = p.path("acceptorMaximumSignedOffsetInclusive").intValue();
    }
    public static boolean intronic(TranscriptConsequence t) { return terms(t).contains("intron_variant"); }
    public static boolean synonymous(TranscriptConsequence t) { return terms(t).contains("synonymous_variant"); }
    public static boolean spliceCandidate(TranscriptConsequence t) { return intronic(t) || synonymous(t); }
    public static String predictionPath(TranscriptConsequence t) {
        return synonymous(t) ? "SPLICEAI_SYNONYMOUS" : intronic(t) ? "SPLICEAI_INTRONIC" : "ALPHAMISSENSE_MISSENSE";
    }
    public static boolean splicePath(String path) { return "SPLICEAI_INTRONIC".equals(path) || "SPLICEAI_SYNONYMOUS".equals(path); }
    public static boolean missense(TranscriptConsequence t) { return terms(t).contains("missense_variant"); }
    private static Set<String> terms(TranscriptConsequence t) {
        return t == null || t.getConsequence() == null ? Set.of() : new HashSet<>(Arrays.asList(t.getConsequence().split("&")));
    }
    public String blocker(TranscriptConsequence t) {
        var terms = terms(t);
        if (terms.contains("intron_variant") && terms.contains("synonymous_variant")) return "BP7_POSITION_CLASS_AMBIGUOUS";
        if (!spliceCandidate(t) || terms.contains("missense_variant") || terms.contains("splice_donor_variant")
                || terms.contains("splice_acceptor_variant")) return "BP7_CONSEQUENCE_NOT_APPLICABLE";
        if (t == null) return "TRANSCRIPT_RELATIVE_POSITION_MISSING";
        if (synonymous(t)) {
            if (!Set.of("synonymous_variant", "splice_region_variant", "splice_donor_region_variant")
                    .containsAll(terms)) return "BP7_CONSEQUENCE_NOT_APPLICABLE";
            var proof = t.getSynonymousExonPosition();
            if (proof == null) return "TRANSCRIPT_RELATIVE_POSITION_MISSING";
            if (!proof.transcriptId().equals(t.getVersionedTranscriptId()) || !proof.annotationVersion().equals(t.getVersion()))
                return "SYNONYMOUS_POSITION_TRANSCRIPT_MISMATCH";
            // Boundary exclusions belong to BP7; PP3/BP4 can still assess a splice signal.
            return null;
        }
        Integer offset = t.getBp7IntronicSignedOffset();
        if (offset == null) return "TRANSCRIPT_RELATIVE_POSITION_MISSING";
        return offset >= donor || offset <= acceptor ? null : "BP7_INTRONIC_SPLICE_PROXIMAL";
    }
    public static boolean notApplicable(String reason) {
        return Set.of("BP7_CONSEQUENCE_NOT_APPLICABLE", "BP7_INTRONIC_SPLICE_PROXIMAL").contains(reason == null ? "" : reason);
    }
}
