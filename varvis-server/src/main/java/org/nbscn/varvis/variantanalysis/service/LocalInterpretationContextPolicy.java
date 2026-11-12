package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.Arrays;
import java.util.Objects;

/** Separates a ranking hypothesis from an explicitly chosen allele interpretation context.
 * This is not a diagnosis, segregation assessment, or a relaxation of source/evidence checks. */
public final class LocalInterpretationContextPolicy {
    public static final String ID = "LOCAL_ALLELE_INTERPRETATION_CONTEXT";
    public static final String VERSION = "1.1.0";

    private LocalInterpretationContextPolicy() { }

    public record Assessment(String policyId, String policyVersion, String rankingMoi, String interpretationMoi,
                             boolean requiresExplicitSelection, String genotypeCheck, String reasonCode,
                             boolean diagnosisClaimed) { }

    /** XL is a broader catalog scope, not an alias for XR or XD. Offer only the unchanged
     * precise ranking context, require explicit confirmation, and preserve the original XL row. */
    static Assessment assessCatalog(VariantInterpretation candidate, String catalogMoi) {
        if (!"XL".equals(catalogMoi))
            return assess(candidate, PatientDiseaseContextBindingResolver.normalizeCatalogMoi(catalogMoi));
        var identity = candidate.getCandidateIdentity();
        String ranking = identity == null ? null : PatientDiseaseContextBindingResolver.normalizeMoi(identity.getModeOfInheritance());
        var base = assess(candidate, ranking);
        String issue = base.reasonCode();
        if (issue == null && !xLinked(ranking)) issue = "INTERPRETATION_X_LINKED_CONTEXT_REQUIRED";
        var observation = candidate.getObservation(); var key = observation == null ? null : observation.getKey();
        if (issue == null && (key == null || !"GRCh38".equals(key.getGenomeAssembly()) || !"X".equals(key.getChromosome())
                || key.getPosition() <= 0)) issue = "INTERPRETATION_NATIVE_X_ALLELE_REQUIRED";
        var call = candidate.getAnalysisVcfCall();
        if (issue == null && (call == null || call.getMatchStatus() != AnalysisVcfCall.MatchStatus.MATCHED
                || !"GRCh38".equals(call.getAssembly()))) issue = "INTERPRETATION_ANALYSIS_VCF_CALL_REQUIRED";
        if (issue == null && call != null && !call.getInvalidFields().isEmpty()) issue = "INTERPRETATION_ANALYSIS_VCF_CALL_INVALID";
        if (issue == null && call != null) issue = switch (call.getTargetAlleleCall()) {
            case HET_TARGET, HOM_TARGET, HEMI_TARGET, TARGET_WITH_OTHER_ALT -> null;
            default -> "INTERPRETATION_TARGET_ALT_NOT_PRESENT";
        };
        return new Assessment(ID, VERSION, base.rankingMoi(), xLinked(ranking) ? ranking : null, true,
                issue == null ? "X_LINKED_CATALOG_SCOPE_EXPLICIT_CONTEXT_NOT_DIAGNOSIS" : "NOT_VERIFIED", issue, false);
    }

    static boolean catalogCovers(String catalogMoi, String selectedMoi) {
        return Objects.equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(catalogMoi), selectedMoi)
                && selectedMoi != null || "XL".equals(catalogMoi) && xLinked(selectedMoi);
    }

    private static boolean xLinked(String moi) {
        return "X_LINKED_DOMINANT".equals(moi) || "X_LINKED_RECESSIVE".equals(moi);
    }

    public static Assessment assess(VariantInterpretation candidate, String selectedMoi) {
        String interpretation = PatientDiseaseContextBindingResolver.normalizeMoi(selectedMoi);
        var identity = candidate.getCandidateIdentity();
        var features = candidate.getExomiserCandidateFeatures();
        String[] raw = {identity == null ? null : identity.getModeOfInheritance(),
                features == null ? null : features.getRawModeOfInheritance(),
                features == null ? null : features.getNormalizedModeOfInheritance()};
        String[] normalized = Arrays.stream(raw).map(PatientDiseaseContextBindingResolver::normalizeMoi).toArray(String[]::new);
        String ranking = normalized[0];
        String blocker = null;
        if (Arrays.stream(raw).anyMatch(v -> v == null || v.isBlank())) blocker = "CANDIDATE_MOI_REQUIRED";
        else if (Arrays.stream(normalized).anyMatch(Objects::isNull)) blocker = "CANDIDATE_MOI_UNMAPPABLE";
        else if (Arrays.stream(normalized).distinct().count() != 1) blocker = "CANDIDATE_MOI_CONFLICT";
        else if (interpretation == null) blocker = "HYPOTHESIS_MOI_UNMAPPABLE";
        boolean different = ranking != null && interpretation != null && !ranking.equals(interpretation);
        String check = "NOT_REQUIRED_SAME_RANKING_CONTEXT";
        if (blocker == null && different) {
            blocker = !autosomalMoi(ranking) || !autosomalMoi(interpretation)
                    ? "INTERPRETATION_MOI_SCOPE_UNSUPPORTED" : alleleBlocker(candidate);
            check = blocker == null ? "DIPLOID_TARGET_ALT_PRESENT_NOT_DIAGNOSIS" : "NOT_VERIFIED";
        } else if (blocker != null) check = "NOT_VERIFIED";
        return new Assessment(ID, VERSION, ranking, interpretation, different, check, blocker, false);
    }

    private static boolean autosomalMoi(String moi) {
        return "AUTOSOMAL_DOMINANT".equals(moi) || "AUTOSOMAL_RECESSIVE".equals(moi);
    }

    private static String alleleBlocker(VariantInterpretation candidate) {
        var observation = candidate.getObservation();
        var key = observation == null ? null : observation.getKey();
        if (key == null || !"GRCh38".equals(key.getGenomeAssembly()) || key.getPosition() <= 0
                || key.getChromosome() == null || !key.getChromosome().matches("(?:[1-9]|1[0-9]|2[0-2])")
                || key.getReference() == null || !key.getReference().matches("[ACGT]")
                || key.getAlternate() == null || !key.getAlternate().matches("[ACGT]")
                || key.getReference().equals(key.getAlternate())) return "INTERPRETATION_NATIVE_AUTOSOMAL_SNV_REQUIRED";
        // The adapter's exact analysis-VCF witness carries GT; the ranking observation intentionally does not.
        // Business source recovery independently binds this immutable witness to the candidate and original ALT index.
        var call = candidate.getAnalysisVcfCall();
        if (call == null || call.getMatchStatus() != AnalysisVcfCall.MatchStatus.MATCHED
                || !key.getGenomeAssembly().equals(call.getAssembly())) return "INTERPRETATION_ANALYSIS_VCF_CALL_REQUIRED";
        if (!call.getInvalidFields().isEmpty()) return "INTERPRETATION_ANALYSIS_VCF_CALL_INVALID";
        String gt = call.getCandidateRelativeGenotype();
        if (gt == null || !gt.matches("(?:REF|TARGET_ALT|OTHER_ALT)[/|](?:REF|TARGET_ALT|OTHER_ALT)"))
            return "INTERPRETATION_CALLED_DIPLOID_GT_REQUIRED";
        return switch (call.getTargetAlleleCall()) {
            case HET_TARGET, HOM_TARGET, TARGET_WITH_OTHER_ALT -> null;
            default -> "INTERPRETATION_TARGET_ALT_NOT_PRESENT";
        };
    }
}
