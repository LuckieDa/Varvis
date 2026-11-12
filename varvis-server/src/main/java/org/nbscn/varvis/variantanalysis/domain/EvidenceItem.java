package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceItem {

    public static final String CURRENT_SCHEMA_VERSION = "evidence-item-v1";
    public static final String LEGACY_SCHEMA_VERSION = "evidence-item-v0";
    public static final String INDETERMINATE_STRENGTH = "INDETERMINATE";

    private final String schemaVersion;
    private final String id;
    private final String canonicalVariantId;
    private final String normalizedContig;
    private final Integer normalizedPosition;
    private final String normalizedReference;
    private final String normalizedAlternate;
    private final String geneId;
    private final String transcriptId;
    private final String diseaseId;
    private final String source;
    private final String sourceRecordId;
    private final String sourceVersion;
    private final String resourceVersion;
    private final String resourceSha256;
    private final String assembly;
    private final String evidenceType;
    private final String applicability;
    private final String status;
    private final Double rawScore;
    private final String rawValue;
    private final String normalizedValue;
    private final String calibrationProfile;
    private final String evidenceStrength;
    private final String label;
    private final String summary;
    private final Map<String, Object> provenance;
    private final String retrievedAt;
    private final String explanationCode;
    private final Map<String, Object> details;

    public EvidenceItem(String source,
                        String sourceVersion,
                        String evidenceType,
                        String applicability,
                        Double rawScore,
                        String label,
                        String summary,
                        Map<String, Object> details) {
        this(
                CURRENT_SCHEMA_VERSION,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                source,
                null,
                sourceVersion,
                sourceVersion,
                null,
                null,
                evidenceType,
                applicability,
                applicability,
                rawScore,
                rawScore == null ? null : rawScore.toString(),
                null,
                null,
                INDETERMINATE_STRENGTH,
                label,
                summary,
                null,
                null,
                null,
                details
        );
    }

    @JsonCreator
    public EvidenceItem(@JsonProperty("schemaVersion") String schemaVersion,
                        @JsonProperty("id") String id,
                        @JsonProperty("canonicalVariantId") String canonicalVariantId,
                        @JsonProperty("normalizedContig") String normalizedContig,
                        @JsonProperty("normalizedPosition") Integer normalizedPosition,
                        @JsonProperty("normalizedReference") String normalizedReference,
                        @JsonProperty("normalizedAlternate") String normalizedAlternate,
                        @JsonProperty("geneId") String geneId,
                        @JsonProperty("transcriptId") String transcriptId,
                        @JsonProperty("diseaseId") String diseaseId,
                        @JsonProperty("source") String source,
                        @JsonProperty("sourceRecordId") String sourceRecordId,
                        @JsonProperty("sourceVersion") String sourceVersion,
                        @JsonProperty("resourceVersion") String resourceVersion,
                        @JsonProperty("resourceSha256") String resourceSha256,
                        @JsonProperty("assembly") String assembly,
                        @JsonProperty("evidenceType") String evidenceType,
                        @JsonProperty("applicability") String applicability,
                        @JsonProperty("status") String status,
                        @JsonProperty("rawScore") Double rawScore,
                        @JsonProperty("rawValue") String rawValue,
                        @JsonProperty("normalizedValue") String normalizedValue,
                        @JsonProperty("calibrationProfile") String calibrationProfile,
                        @JsonProperty("evidenceStrength") String evidenceStrength,
                        @JsonProperty("label") String label,
                        @JsonProperty("summary") String summary,
                        @JsonProperty("provenance") Map<String, Object> provenance,
                        @JsonProperty("retrievedAt") String retrievedAt,
                        @JsonProperty("explanationCode") String explanationCode,
                        @JsonProperty("details") Map<String, Object> details) {
        this.schemaVersion = textOrDefault(schemaVersion, LEGACY_SCHEMA_VERSION);
        this.id = trimmedOrNull(id);
        this.canonicalVariantId = trimmedOrNull(canonicalVariantId);
        this.normalizedContig = trimmedOrNull(normalizedContig);
        this.normalizedPosition = normalizedPosition;
        this.normalizedReference = trimmedOrNull(normalizedReference);
        this.normalizedAlternate = trimmedOrNull(normalizedAlternate);
        this.geneId = trimmedOrNull(geneId);
        this.transcriptId = trimmedOrNull(transcriptId);
        this.diseaseId = trimmedOrNull(diseaseId);
        this.source = source;
        this.sourceRecordId = trimmedOrNull(sourceRecordId);
        this.sourceVersion = sourceVersion;
        this.resourceVersion = trimmedOrNull(resourceVersion);
        this.resourceSha256 = trimmedOrNull(resourceSha256);
        this.assembly = trimmedOrNull(assembly);
        this.evidenceType = evidenceType;
        this.applicability = applicability;
        this.status = trimmedOrNull(status);
        this.rawScore = rawScore;
        this.rawValue = rawValue;
        this.normalizedValue = normalizedValue;
        this.calibrationProfile = trimmedOrNull(calibrationProfile);
        this.evidenceStrength = trimmedOrNull(evidenceStrength);
        this.label = label;
        this.summary = summary;
        this.provenance = immutableMap(provenance);
        this.retrievedAt = trimmedOrNull(retrievedAt);
        this.explanationCode = trimmedOrNull(explanationCode);
        this.details = immutableMap(details);
    }

    public static EvidenceItem unavailable(String source, String version, String evidenceType, String summary) {
        return new EvidenceItem(
                source,
                version,
                evidenceType,
                "UNAVAILABLE",
                null,
                "Unavailable",
                summary,
                Collections.emptyMap()
        );
    }

    public static EvidenceItem notApplicable(String source, String version, String evidenceType, String summary) {
        return new EvidenceItem(
                source,
                version,
                evidenceType,
                "NOT_APPLICABLE",
                null,
                "Not applicable",
                summary,
                Collections.emptyMap()
        );
    }

    public EvidenceItem withVariantContext(String variantId, String genomeAssembly) {
        return withVariantContext(
                variantId,
                genomeAssembly,
                normalizedContig,
                normalizedPosition,
                normalizedReference,
                normalizedAlternate
        );
    }

    public EvidenceItem withVariantContext(NormalizedVariantKey key) {
        if (!validVariantContext(key)) {
            return withVariantContext(null, null);
        }
        return withVariantContext(
                key.canonicalId(),
                key.getGenomeAssembly(),
                key.getChromosome(),
                key.getPosition(),
                key.getReference(),
                key.getAlternate()
        );
    }

    private EvidenceItem withVariantContext(String variantId,
                                            String genomeAssembly,
                                            String contig,
                                            Integer position,
                                            String reference,
                                            String alternate) {
        boolean resolvedContext = hasText(variantId) && hasText(genomeAssembly);
        return new EvidenceItem(
                migratedSchemaVersion(),
                id,
                resolvedContext ? variantId : canonicalVariantId,
                resolvedContext ? contig : normalizedContig,
                resolvedContext ? position : normalizedPosition,
                resolvedContext ? reference : normalizedReference,
                resolvedContext ? alternate : normalizedAlternate,
                geneId,
                transcriptId,
                diseaseId,
                source,
                sourceRecordId,
                sourceVersion,
                textOrDefault(resourceVersion, sourceVersion),
                resourceSha256,
                resolvedContext ? genomeAssembly : assembly,
                evidenceType,
                applicability,
                textOrDefault(status, applicability),
                rawScore,
                rawValue == null && rawScore != null ? rawScore.toString() : rawValue,
                normalizedValue,
                calibrationProfile,
                textOrDefault(evidenceStrength, INDETERMINATE_STRENGTH),
                label,
                summary,
                provenance,
                retrievedAt,
                explanationCode,
                details
        );
    }

    public EvidenceItem withPresentation(String compactLabel,
                                         String compactSummary,
                                         Map<String, Object> compactDetails) {
        return new EvidenceItem(
                schemaVersion,
                id,
                canonicalVariantId,
                normalizedContig,
                normalizedPosition,
                normalizedReference,
                normalizedAlternate,
                geneId,
                transcriptId,
                diseaseId,
                source,
                sourceRecordId,
                sourceVersion,
                resourceVersion,
                resourceSha256,
                assembly,
                evidenceType,
                applicability,
                status,
                rawScore,
                rawValue,
                normalizedValue,
                calibrationProfile,
                evidenceStrength,
                compactLabel,
                compactSummary,
                provenance,
                retrievedAt,
                explanationCode,
                compactDetails
        );
    }

    public String getSchemaVersion() { return schemaVersion; }
    public String getId() { return id; }
    public String getCanonicalVariantId() { return canonicalVariantId; }
    public String getNormalizedContig() { return normalizedContig; }
    public Integer getNormalizedPosition() { return normalizedPosition; }
    public String getNormalizedReference() { return normalizedReference; }
    public String getNormalizedAlternate() { return normalizedAlternate; }
    public String getGeneId() { return geneId; }
    public String getTranscriptId() { return transcriptId; }
    public String getDiseaseId() { return diseaseId; }
    public String getSource() { return source; }
    public String getSourceRecordId() { return sourceRecordId; }
    public String getSourceVersion() { return sourceVersion; }
    public String getResourceVersion() { return resourceVersion; }
    public String getResourceSha256() { return resourceSha256; }
    public String getAssembly() { return assembly; }
    public String getEvidenceType() { return evidenceType; }
    public String getApplicability() { return applicability; }
    public String getStatus() { return status; }
    public Double getRawScore() { return rawScore; }
    public String getRawValue() { return rawValue; }
    public String getNormalizedValue() { return normalizedValue; }
    public String getCalibrationProfile() { return calibrationProfile; }
    public String getEvidenceStrength() { return evidenceStrength; }
    public String getLabel() { return label; }
    public String getSummary() { return summary; }
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, Object> getProvenance() { return provenance; }
    public String getRetrievedAt() { return retrievedAt; }
    public String getExplanationCode() { return explanationCode; }
    public Map<String, Object> getDetails() { return details; }

    @JsonIgnore
    public String getEffectiveStatus() {
        return textOrDefault(status, applicability);
    }

    @JsonIgnore
    public String getEffectiveResourceVersion() {
        return textOrDefault(resourceVersion, sourceVersion);
    }

    @JsonIgnore
    public String getStableIdentityKey() {
        if (hasText(id)) {
            return "ID|" + id;
        }
        if (!hasText(sourceRecordId)) {
            return null;
        }
        return "RECORD|"
                + safeIdentityPart(source) + "|"
                + safeIdentityPart(sourceRecordId) + "|"
                + safeIdentityPart(evidenceType) + "|"
                + safeIdentityPart(canonicalVariantId) + "|"
                + safeIdentityPart(geneId) + "|"
                + safeIdentityPart(transcriptId) + "|"
                + safeIdentityPart(diseaseId);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(source));
    }

    private static String textOrDefault(String value, String fallback) {
        String normalized = trimmedOrNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String trimmedOrNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String migratedSchemaVersion() {
        return LEGACY_SCHEMA_VERSION.equals(schemaVersion) ? CURRENT_SCHEMA_VERSION : schemaVersion;
    }

    private static boolean validVariantContext(NormalizedVariantKey key) {
        return key != null
                && hasText(key.getGenomeAssembly())
                && hasText(key.getChromosome())
                && key.getPosition() > 0
                && hasText(key.getReference())
                && hasText(key.getAlternate());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String safeIdentityPart(String value) {
        String normalized = trimmedOrNull(value);
        return normalized == null ? "0:" : normalized.length() + ":" + normalized;
    }
}
