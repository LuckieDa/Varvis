package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Read-only record facts, never a patient-detection, scientific-adoption or diagnosis gate.
 * The caller must first verify the original candidate/call against the trusted analysis source.
 * AnalysisVcfCall has no allele coordinates, so this helper cannot independently prove that
 * association. Pass the original analysis allele, never a normalized/projected query copy.
 */
public final class CandidateAlleleCallSummary {
    public static final String VERSION = "candidate-allele-call-summary-v1";
    private CandidateAlleleCallSummary() { }

    public static Summary summarize(@Nullable VariantInterpretation originalCandidate) {
        var observation = originalCandidate == null ? null : originalCandidate.getObservation();
        return summarize(originalCandidate == null ? null : originalCandidate.getAnalysisVcfCall(),
                observation == null ? null : observation.getKey());
    }

    public static Summary summarize(@Nullable AnalysisVcfCall call,
                                    @Nullable NormalizedVariantKey originalAnalysisAllele) {
        boolean validAllele = completeAllele(originalAnalysisAllele);
        String originalAssembly = originalAnalysisAllele == null ? null : originalAnalysisAllele.getGenomeAssembly();
        var source = new CallSource(call == null ? null : call.getSchemaVersion(),
                call == null ? null : call.getMatchStatus(), call == null ? null : call.getAssembly(),
                validAssembly(originalAssembly) ? originalAssembly : null,
                validAllele && originalAnalysisAllele != null ? keySha256(originalAnalysisAllele) : null,
                call == null ? null : call.getTargetAlleleIndex(),
                call == null ? null : call.getMatchingRecordCount(),
                call == null ? null : call.getMatchedRecordOrdinal(),
                call == null ? null : call.getArtifactRole(), call == null ? null : call.getProvenance());
        var quality = call == null ? new Quality(null, null, null, null, null, null, List.of(), List.of())
                : new Quality(call.getFilterEvaluation(), call.getDepth(), call.getGenotypeQuality(),
                        call.getReferenceDepth(), call.getTargetAlleleDepth(), call.getAlleleBalance(),
                        call.getMissingFields(), call.getInvalidFields());
        var facts = facts(call, validAllele, originalAssembly);
        var limits = new ArrayList<String>();
        limits.add("ORIGINAL_SOURCE_ASSOCIATION_REQUIRES_CALLER_VERIFICATION");
        limits.add("REPORTED_ALT_IS_NOT_CLINICAL_OR_SCIENTIFIC_ADOPTION");
        limits.add("INDEPENDENT_ALLELE_EVALUATION_IS_SEPARATE");
        if (Boolean.FALSE.equals(facts.reported())) limits.add("NO_PATIENT_TARGET_DETECTION_FROM_THIS_RECORD");
        if (facts.reported() == null) limits.add("SAMPLE_TARGET_REPORT_UNRESOLVED");
        if (Boolean.FALSE.equals(facts.complete())) limits.add("GENOTYPE_INCOMPLETE");
        if (quality.filterEvaluation() == AnalysisVcfCall.FilterEvaluation.FILTERED) limits.add("SOURCE_FILTERED");
        if (quality.filterEvaluation() == AnalysisVcfCall.FilterEvaluation.UNASSESSED) limits.add("SOURCE_FILTER_UNASSESSED");
        if (!quality.missingFields().isEmpty()) limits.add("SOURCE_FIELDS_MISSING");
        if (!quality.invalidFields().isEmpty()) limits.add("SOURCE_FIELDS_INVALID");
        return new Summary(VERSION, "ANALYSIS_VCF_SOURCE_REPORTED_FACTS_ONLY", source,
                call == null ? null : call.getTargetAlleleCall(),
                call == null ? null : call.getCandidateRelativeGenotype(),
                facts.reported(), facts.complete(), facts.reason(), quality, List.copyOf(limits));
    }

    private static Facts facts(@Nullable AnalysisVcfCall call, boolean validAllele, @Nullable String assembly) {
        if (call == null) return new Facts(null, null, "ANALYSIS_VCF_CALL_MISSING");
        if (!validAllele) return new Facts(null, null, "ORIGINAL_ANALYSIS_ALLELE_MISSING_OR_INCOMPLETE");
        if (!call.getAssembly().equals(assembly)) return new Facts(null, null, "ANALYSIS_ALLELE_ASSEMBLY_MISMATCH");
        if (call.getMatchStatus() != AnalysisVcfCall.MatchStatus.MATCHED)
            return new Facts(null, null, "ANALYSIS_VCF_" + call.getMatchStatus().name());
        var type = call.getTargetAlleleCall();
        if (type == null) return new Facts(null, null, "SOURCE_GENOTYPE_FACTS_MISSING");
        if (type == AnalysisVcfCall.TargetAlleleCall.NO_CALL) return new Facts(null, false, "SOURCE_NO_CALL");
        if (type == AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED) return new Facts(null, false, "SOURCE_GENOTYPE_UNSUPPORTED");
        if (call.getInvalidFields().contains("GT")
                || call.getMissingFields().contains("GT") && type != AnalysisVcfCall.TargetAlleleCall.PARTIAL_TARGET)
            return new Facts(null, null, "SOURCE_GENOTYPE_FLAGS_INCOMPATIBLE");
        return switch (type) {
            case HET_TARGET, HOM_TARGET, HEMI_TARGET, TARGET_WITH_OTHER_ALT ->
                    new Facts(true, true, "SOURCE_REPORTS_TARGET_ALT");
            case HOM_REF, HEMI_REF, OTHER_ALT_ONLY -> new Facts(false, true, "SOURCE_DOES_NOT_REPORT_TARGET_ALT");
            case PARTIAL_TARGET -> new Facts(true, false, "SOURCE_REPORTS_TARGET_ALT_WITH_INCOMPLETE_GENOTYPE");
            case NO_CALL, UNSUPPORTED -> throw new IllegalStateException("UNREACHABLE_CALL_FACT_BRANCH");
        };
    }

    private static boolean completeAllele(@Nullable NormalizedVariantKey allele) {
        return allele != null && validAssembly(allele.getGenomeAssembly()) && allele.getPosition() > 0
                && text(allele.getChromosome()) && text(allele.getReference()) && text(allele.getAlternate());
    }

    private static boolean validAssembly(@Nullable String assembly) {
        return "GRCh37".equals(assembly) || "GRCh38".equals(assembly);
    }

    private static boolean text(@Nullable String value) { return value != null && !value.isBlank(); }

    /** Digest of the exact key tuple, not a file digest, normalization or trusted-source proof. */
    private static String keySha256(NormalizedVariantKey key) {
        try {
            // Length-prefix fields to keep the tuple unambiguous even for noncanonical legacy keys.
            StringBuilder tuple = new StringBuilder();
            for (String value : List.of(key.getGenomeAssembly(), key.getChromosome(), Integer.toString(key.getPosition()),
                    key.getReference(), key.getAlternate())) tuple.append(value.length()).append(':').append(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(tuple.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE", impossible); }
    }

    private record Facts(@Nullable Boolean reported, @Nullable Boolean complete, String reason) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(String schemaVersion, String scope, CallSource source,
                          @Nullable AnalysisVcfCall.TargetAlleleCall targetAlleleCall,
                          @Nullable String candidateRelativeGenotype, @Nullable Boolean targetAltReported,
                          @Nullable Boolean genotypeComplete, String reasonCode, Quality quality,
                          List<String> usageLimitations) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CallSource(@Nullable String schemaVersion, @Nullable AnalysisVcfCall.MatchStatus matchStatus,
                             @Nullable String assembly, @Nullable String originalAnalysisAssembly,
                             @Nullable String originalAnalysisAlleleKeySha256, @Nullable Integer targetAlleleIndex,
                             @Nullable Integer matchingRecordCount, @Nullable Integer matchedRecordOrdinal,
                             @Nullable String artifactRole, @Nullable String provenance) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Quality(@Nullable AnalysisVcfCall.FilterEvaluation filterEvaluation,
                          @Nullable Integer depth, @Nullable Integer genotypeQuality,
                          @Nullable Integer referenceDepth, @Nullable Integer targetAlleleDepth,
                          @Nullable Double alleleBalance, List<String> missingFields, List<String> invalidFields) {
        public Quality {
            missingFields = List.copyOf(missingFields);
            invalidFields = List.copyOf(invalidFields);
        }
    }
}
