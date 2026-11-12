package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Privacy-safe, immutable projection of one candidate allele's sample call in the
 * exact VCF submitted to Exomiser.
 *
 * <p>This object deliberately stores neither the sample identifier nor raw VCF
 * fields.  Genotypes use candidate-relative tokens so another alternate allele
 * can never be mistaken for the reference allele.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class AnalysisVcfCall {

    public static final String CURRENT_SCHEMA_VERSION = "analysis-vcf-call-v1";
    public static final String ARTIFACT_ROLE = "EXOMISER_ANALYSIS_VCF";
    public static final String PROVENANCE = "ANALYSIS_VCF_EXACT_ALLELE";

    public enum MatchStatus {
        MATCHED,
        NOT_FOUND,
        AMBIGUOUS
    }

    public enum TargetAlleleCall {
        HOM_REF,
        HET_TARGET,
        HOM_TARGET,
        HEMI_REF,
        HEMI_TARGET,
        TARGET_WITH_OTHER_ALT,
        OTHER_ALT_ONLY,
        PARTIAL_TARGET,
        NO_CALL,
        UNSUPPORTED
    }

    public enum FilterEvaluation {
        PASS,
        FILTERED,
        UNASSESSED
    }

    public enum PhasingStatus {
        PHASE_SET,
        RECORD_ONLY,
        UNPHASED,
        UNKNOWN
    }

    private final String schemaVersion;
    private final MatchStatus matchStatus;
    private final TargetAlleleCall targetAlleleCall;
    private final String candidateRelativeGenotype;
    private final Integer targetAlleleIndex;
    private final Integer depth;
    private final Integer genotypeQuality;
    private final Integer referenceDepth;
    private final Integer targetAlleleDepth;
    private final Double alleleBalance;
    private final FilterEvaluation filterEvaluation;
    private final PhasingStatus phasingStatus;
    private final String phaseSetToken;
    private final Integer matchingRecordCount;
    private final Integer matchedRecordOrdinal;
    private final String assembly;
    private final String artifactRole;
    private final String provenance;
    private final List<String> missingFields;
    private final List<String> invalidFields;

    @JsonCreator
    public AnalysisVcfCall(
            @JsonProperty("schemaVersion") String schemaVersion,
            @JsonProperty("matchStatus") MatchStatus matchStatus,
            @JsonProperty("targetAlleleCall") TargetAlleleCall targetAlleleCall,
            @JsonProperty("candidateRelativeGenotype") String candidateRelativeGenotype,
            @JsonProperty("targetAlleleIndex") Integer targetAlleleIndex,
            @JsonProperty("depth") Integer depth,
            @JsonProperty("genotypeQuality") Integer genotypeQuality,
            @JsonProperty("referenceDepth") Integer referenceDepth,
            @JsonProperty("targetAlleleDepth") Integer targetAlleleDepth,
            @JsonProperty("alleleBalance") Double alleleBalance,
            @JsonProperty("filterEvaluation") FilterEvaluation filterEvaluation,
            @JsonProperty("phasingStatus") PhasingStatus phasingStatus,
            @JsonProperty("phaseSetToken") String phaseSetToken,
            @JsonProperty("matchingRecordCount") Integer matchingRecordCount,
            @JsonProperty("matchedRecordOrdinal") Integer matchedRecordOrdinal,
            @JsonProperty("assembly") String assembly,
            @JsonProperty("artifactRole") String artifactRole,
            @JsonProperty("provenance") String provenance,
            @JsonProperty("missingFields") List<String> missingFields,
            @JsonProperty("invalidFields") List<String> invalidFields) {
        this.schemaVersion = fixedValue(
                schemaVersion,
                CURRENT_SCHEMA_VERSION,
                "ANALYSIS_VCF_CALL_SCHEMA_INVALID"
        );
        if (matchStatus == null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_MATCH_STATUS_REQUIRED");
        }
        this.matchStatus = matchStatus;
        this.targetAlleleCall = targetAlleleCall;
        this.candidateRelativeGenotype = trimToNull(candidateRelativeGenotype);
        this.targetAlleleIndex = nonNegativeOrNull(targetAlleleIndex, "TARGET_ALLELE_INDEX");
        this.depth = nonNegativeOrNull(depth, "DEPTH");
        this.genotypeQuality = nonNegativeOrNull(genotypeQuality, "GENOTYPE_QUALITY");
        this.referenceDepth = nonNegativeOrNull(referenceDepth, "REFERENCE_DEPTH");
        this.targetAlleleDepth = nonNegativeOrNull(targetAlleleDepth, "TARGET_ALLELE_DEPTH");
        this.alleleBalance = alleleBalance(alleleBalance);
        this.filterEvaluation = filterEvaluation;
        this.phasingStatus = phasingStatus;
        this.phaseSetToken = trimToNull(phaseSetToken);
        this.matchingRecordCount = nonNegativeOrNull(matchingRecordCount, "MATCHING_RECORD_COUNT");
        this.matchedRecordOrdinal = nonNegativeOrNull(matchedRecordOrdinal, "MATCHED_RECORD_ORDINAL");
        this.assembly = validatedAssembly(assembly);
        this.artifactRole = fixedValue(
                artifactRole,
                ARTIFACT_ROLE,
                "ANALYSIS_VCF_CALL_ARTIFACT_ROLE_INVALID"
        );
        this.provenance = fixedValue(
                provenance,
                PROVENANCE,
                "ANALYSIS_VCF_CALL_PROVENANCE_INVALID"
        );
        this.missingFields = immutableCodes(missingFields, "MISSING_FIELDS");
        this.invalidFields = immutableCodes(invalidFields, "INVALID_FIELDS");
        validateState();
    }

    public static AnalysisVcfCall matched(TargetAlleleCall targetAlleleCall,
                                          String candidateRelativeGenotype,
                                          int targetAlleleIndex,
                                          Integer depth,
                                          Integer genotypeQuality,
                                          Integer referenceDepth,
                                          Integer targetAlleleDepth,
                                          Double alleleBalance,
                                          FilterEvaluation filterEvaluation,
                                          PhasingStatus phasingStatus,
                                          String phaseSetToken,
                                          int matchedRecordOrdinal,
                                          GenomeAssembly assembly,
                                          List<String> missingFields,
                                          List<String> invalidFields) {
        return new AnalysisVcfCall(
                CURRENT_SCHEMA_VERSION,
                MatchStatus.MATCHED,
                targetAlleleCall,
                candidateRelativeGenotype,
                targetAlleleIndex,
                depth,
                genotypeQuality,
                referenceDepth,
                targetAlleleDepth,
                alleleBalance,
                filterEvaluation,
                phasingStatus,
                phaseSetToken,
                1,
                matchedRecordOrdinal,
                assembly == null ? null : assembly.name(),
                ARTIFACT_ROLE,
                PROVENANCE,
                missingFields,
                invalidFields
        );
    }

    public static AnalysisVcfCall notFound(GenomeAssembly assembly) {
        return unmatched(MatchStatus.NOT_FOUND, 0, assembly);
    }

    public static AnalysisVcfCall ambiguous(GenomeAssembly assembly, int matchingRecordCount) {
        return unmatched(MatchStatus.AMBIGUOUS, matchingRecordCount, assembly);
    }

    private static AnalysisVcfCall unmatched(MatchStatus status,
                                             int matchingRecordCount,
                                             GenomeAssembly assembly) {
        return new AnalysisVcfCall(
                CURRENT_SCHEMA_VERSION,
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                matchingRecordCount,
                null,
                assembly == null ? null : assembly.name(),
                ARTIFACT_ROLE,
                PROVENANCE,
                Collections.<String>emptyList(),
                Collections.<String>emptyList()
        );
    }

    public String getSchemaVersion() { return schemaVersion; }
    public MatchStatus getMatchStatus() { return matchStatus; }
    public TargetAlleleCall getTargetAlleleCall() { return targetAlleleCall; }
    public String getCandidateRelativeGenotype() { return candidateRelativeGenotype; }
    public Integer getTargetAlleleIndex() { return targetAlleleIndex; }
    public Integer getDepth() { return depth; }
    public Integer getGenotypeQuality() { return genotypeQuality; }
    public Integer getReferenceDepth() { return referenceDepth; }
    public Integer getTargetAlleleDepth() { return targetAlleleDepth; }
    public Double getAlleleBalance() { return alleleBalance; }
    public FilterEvaluation getFilterEvaluation() { return filterEvaluation; }
    public PhasingStatus getPhasingStatus() { return phasingStatus; }
    public String getPhaseSetToken() { return phaseSetToken; }
    public Integer getMatchingRecordCount() { return matchingRecordCount; }
    public Integer getMatchedRecordOrdinal() { return matchedRecordOrdinal; }
    public String getAssembly() { return assembly; }
    public String getArtifactRole() { return artifactRole; }
    public String getProvenance() { return provenance; }
    public List<String> getMissingFields() { return missingFields; }
    public List<String> getInvalidFields() { return invalidFields; }

    private void validateState() {
        if (matchStatus == MatchStatus.MATCHED) {
            validateMatchedState();
            return;
        }
        int expectedMinimum = matchStatus == MatchStatus.AMBIGUOUS ? 2 : 0;
        int expectedMaximum = matchStatus == MatchStatus.NOT_FOUND ? 0 : Integer.MAX_VALUE;
        if (matchingRecordCount == null
                || matchingRecordCount < expectedMinimum
                || matchingRecordCount > expectedMaximum) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_MATCH_COUNT_INCONSISTENT");
        }
        if (targetAlleleCall != null
                || candidateRelativeGenotype != null
                || targetAlleleIndex != null
                || depth != null
                || genotypeQuality != null
                || referenceDepth != null
                || targetAlleleDepth != null
                || alleleBalance != null
                || filterEvaluation != null
                || phasingStatus != null
                || phaseSetToken != null
                || matchedRecordOrdinal != null
                || !missingFields.isEmpty()
                || !invalidFields.isEmpty()) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_UNMATCHED_DETAILS_PRESENT");
        }
    }

    private void validateMatchedState() {
        if (matchingRecordCount == null || matchingRecordCount != 1) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_MATCH_COUNT_INCONSISTENT");
        }
        if (targetAlleleIndex == null || targetAlleleIndex <= 0) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_TARGET_ALLELE_INDEX_REQUIRED");
        }
        if (matchedRecordOrdinal == null || matchedRecordOrdinal <= 0) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_RECORD_ORDINAL_REQUIRED");
        }
        if (targetAlleleCall == null || candidateRelativeGenotype == null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_GENOTYPE_REQUIRED");
        }
        if (filterEvaluation == null || phasingStatus == null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_EVALUATION_REQUIRED");
        }
        GenotypeTokens genotype = genotypeTokens(candidateRelativeGenotype);
        validateCallAgainstGenotype(genotype);
        validatePhasing(genotype);
        validateAlleleBalance();
    }

    private void validateCallAgainstGenotype(GenotypeTokens genotype) {
        if (targetAlleleCall != inferredCall(genotype)) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_GENOTYPE_CLASS_MISMATCH");
        }
    }

    private TargetAlleleCall inferredCall(GenotypeTokens genotype) {
        if (genotype.missing == genotype.size) {
            return TargetAlleleCall.NO_CALL;
        }
        if (genotype.target > 0 && genotype.missing > 0) {
            return TargetAlleleCall.PARTIAL_TARGET;
        }
        if (genotype.target > 0 && genotype.other > 0) {
            return TargetAlleleCall.TARGET_WITH_OTHER_ALT;
        }
        if (genotype.size == 1 && genotype.target == 1) {
            return TargetAlleleCall.HEMI_TARGET;
        }
        if (genotype.size == 1 && genotype.reference == 1) {
            return TargetAlleleCall.HEMI_REF;
        }
        if (genotype.size > 1 && genotype.target == genotype.size) {
            return TargetAlleleCall.HOM_TARGET;
        }
        if (genotype.size > 1 && genotype.reference == genotype.size) {
            return TargetAlleleCall.HOM_REF;
        }
        if (genotype.target > 0
                && genotype.reference > 0
                && genotype.other == 0
                && genotype.missing == 0) {
            return TargetAlleleCall.HET_TARGET;
        }
        if (genotype.target == 0 && genotype.other > 0 && genotype.missing == 0) {
            return TargetAlleleCall.OTHER_ALT_ONLY;
        }
        return TargetAlleleCall.UNSUPPORTED;
    }

    private void validatePhasing(GenotypeTokens genotype) {
        if (targetAlleleCall == TargetAlleleCall.NO_CALL
                || targetAlleleCall == TargetAlleleCall.UNSUPPORTED) {
            if (phasingStatus != PhasingStatus.UNKNOWN || phaseSetToken != null) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CALL_PHASE_STATE_INCONSISTENT");
            }
            return;
        }
        if (phasingStatus == PhasingStatus.UNKNOWN) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_PHASE_STATE_INCONSISTENT");
        }
        if (phasingStatus == PhasingStatus.PHASE_SET) {
            if (!genotype.phased
                    || phaseSetToken == null
                    || !phaseSetToken.matches("[0-9a-f]{24}")) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CALL_PHASE_STATE_INCONSISTENT");
            }
            return;
        }
        if (phaseSetToken != null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_PHASE_TOKEN_UNEXPECTED");
        }
        if (phasingStatus == PhasingStatus.RECORD_ONLY && !genotype.phased) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_PHASE_STATE_INCONSISTENT");
        }
        if (phasingStatus == PhasingStatus.UNPHASED && genotype.phased) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_PHASE_STATE_INCONSISTENT");
        }
    }

    private void validateAlleleBalance() {
        if (alleleBalance == null) {
            return;
        }
        if (referenceDepth == null || targetAlleleDepth == null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_ALLELE_BALANCE_DEPTH_REQUIRED");
        }
        long denominator = (long) referenceDepth + targetAlleleDepth;
        if (denominator <= 0L) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_ALLELE_BALANCE_DENOMINATOR_INVALID");
        }
        double expected = (double) targetAlleleDepth / (double) denominator;
        if (Math.abs(expected - alleleBalance) > 1.0e-12d) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_ALLELE_BALANCE_INCONSISTENT");
        }
    }

    private static GenotypeTokens genotypeTokens(String value) {
        boolean phased = value.indexOf('|') >= 0;
        if (phased && value.indexOf('/') >= 0) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_GENOTYPE_INVALID");
        }
        String[] tokens = value.split(phased ? "\\|" : "/", -1);
        if (tokens.length == 0) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_GENOTYPE_INVALID");
        }
        int reference = 0;
        int target = 0;
        int other = 0;
        int missing = 0;
        for (String token : tokens) {
            if ("REF".equals(token)) {
                reference++;
            } else if ("TARGET_ALT".equals(token)) {
                target++;
            } else if ("OTHER_ALT".equals(token)) {
                other++;
            } else if (".".equals(token)) {
                missing++;
            } else {
                throw new IllegalArgumentException("ANALYSIS_VCF_CALL_GENOTYPE_INVALID");
            }
        }
        return new GenotypeTokens(tokens.length, reference, target, other, missing, phased);
    }

    private static String validatedAssembly(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_ASSEMBLY_REQUIRED");
        }
        try {
            return GenomeAssembly.valueOf(normalized).name();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_ASSEMBLY_INVALID");
        }
    }

    private static Integer nonNegativeOrNull(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_" + field + "_INVALID");
        }
        return value;
    }

    private static Double alleleBalance(Double value) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value.isInfinite() || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CALL_ALLELE_BALANCE_INVALID");
        }
        return value;
    }

    private static String fixedValue(String value, String expected, String errorCode) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return expected;
        }
        if (!expected.equals(normalized)) {
            throw new IllegalArgumentException(errorCode);
        }
        return normalized;
    }

    private static List<String> immutableCodes(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> selected = new LinkedHashSet<String>();
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized == null || !isKnownFieldCode(normalized)) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CALL_" + field + "_INVALID");
            }
            selected.add(normalized);
        }
        return Collections.unmodifiableList(new ArrayList<String>(selected));
    }

    private static boolean isKnownFieldCode(String value) {
        return "GT".equals(value)
                || "DP".equals(value)
                || "GQ".equals(value)
                || "AD".equals(value)
                || "AB".equals(value)
                || "PS".equals(value)
                || "AD:REF".equals(value)
                || "AD:TARGET".equals(value)
                || "PS:UNPHASED".equals(value);
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static final class GenotypeTokens {
        private final int size;
        private final int reference;
        private final int target;
        private final int other;
        private final int missing;
        private final boolean phased;

        private GenotypeTokens(int size,
                               int reference,
                               int target,
                               int other,
                               int missing,
                               boolean phased) {
            this.size = size;
            this.reference = reference;
            this.target = target;
            this.other = other;
            this.missing = missing;
            this.phased = phased;
        }
    }
}
