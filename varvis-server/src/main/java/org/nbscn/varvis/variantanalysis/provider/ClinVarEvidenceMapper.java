package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared semantic mapping for compact-index and native Tabix ClinVar records. */
final class ClinVarEvidenceMapper {

    private ClinVarEvidenceMapper() {
    }

    static EvidenceItem record(NormalizedVariantKey key, Record record, String matchType) {
        String conflictState = conflictState(record);
        String status = "CONFLICTING_INTERPRETATIONS".equals(conflictState)
                ? "CONFLICTING" : "APPLICABLE";
        String sourceRecordId = first(record.accession, record.variationId, record.alleleId, record.rsId);
        String stableRecordIdentity = sourceRecordId == null
                ? recordFingerprint(record) : sourceRecordId;
        String conditionIdentity = conditionIdentity(record.conditions);
        String stableId = "clinvar|" + identityPart(key == null ? null : key.getGenomeAssembly())
                + "|" + identityPart(stableRecordIdentity)
                + "|" + identityPart(conditionIdentity)
                + "|" + identityPart(record.sourceVersion);
        Map<String, Object> details = baseDetails(record, status, matchType);
        putRuntimeAxes(details, status);
        putIfPresent(details, "aggregateClassification", record.aggregateClassification);
        putIfPresent(details, "clinicalSignificance", record.aggregateClassification);
        putIfPresent(details, "significance", record.aggregateClassification);
        putIfPresent(details, "reviewStatus", record.reviewStatus);
        if (StringUtils.hasText(record.reviewStatus)) {
            details.put("reviewLevel", reviewLevel(record.reviewStatus));
        }
        putIfPresent(details, "conflictState", conflictState);
        List<Map<String, Object>> conditionMaps = conditionMaps(record.conditions);
        if (!conditionMaps.isEmpty()) details.put("conditions", conditionMaps);
        putIfPresent(details, "conditionName", firstConditionName(record.conditions));
        putIfPresent(details, "conditionNames", joinConditionNames(record.conditions));
        putIfPresent(details, "diseaseName", firstConditionName(record.conditions));
        putIfPresent(details, "diseaseIds", joinConditionIds(record.conditions));
        putIfPresent(details, "variationId", record.variationId);
        putIfPresent(details, "accession", record.accession);
        putIfPresent(details, "alleleId", record.alleleId);
        putIfPresent(details, "rsId", record.rsId);
        putIfPresent(details, "lastEvaluated", record.lastEvaluated);
        putIfPresent(details, "geneSymbol", record.geneSymbol);
        putIfPresent(details, "geneInfo", record.geneInfo);
        if (!record.contributingSubmissionIdentifiers.isEmpty()) {
            details.put("contributingSubmissionIdentifiers",
                    new ArrayList<String>(record.contributingSubmissionIdentifiers));
        }
        if (key != null) {
            details.put("requestedAssembly", key.getGenomeAssembly());
            details.put("evidenceAssembly", record.evidenceAssembly);
            details.put("configuredResourceAssembly", record.evidenceAssembly);
            details.put("chromosome", key.getChromosome());
            details.put("position", key.getPosition());
            details.put("reference", key.getReference());
            details.put("alternate", key.getAlternate());
        }
        Map<String, Object> provenance = new LinkedHashMap<String, Object>();
        provenance.put("provider", "CLINVAR");
        provenance.put("recordSource", record.recordSource);
        provenance.put("matchType", matchType);
        provenance.put("evidenceAssembly", record.evidenceAssembly);
        return new EvidenceItem(
                EvidenceItem.CURRENT_SCHEMA_VERSION,
                stableId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                firstConditionId(record.conditions),
                "CLINVAR",
                sourceRecordId,
                record.sourceVersion,
                record.sourceVersion,
                null,
                record.evidenceAssembly,
                "CLINICAL",
                status,
                status,
                null,
                record.aggregateClassification,
                null,
                null,
                EvidenceItem.INDETERMINATE_STRENGTH,
                "CONFLICTING_INTERPRETATIONS".equals(conflictState)
                        ? "Conflicting interpretations" : label(record.aggregateClassification),
                "ClinVar " + record.recordSource + " exact allele record",
                provenance,
                null,
                null,
                details
        );
    }

    static EvidenceItem status(NormalizedVariantKey key,
                               String sourceVersion,
                               String recordSource,
                               String evidenceAssembly,
                               String status,
                               String summary,
                               String matchType) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", recordSource);
        details.put("status", status);
        details.put("matchType", matchType);
        details.put("sourceVersion", sourceVersion);
        details.put("evidenceAssembly", evidenceAssembly);
        details.put("configuredResourceAssembly", evidenceAssembly);
        putRuntimeAxes(details, status);
        details.put("interpretation", "ClinVar " + status
                + " is not interpreted as benign or low risk");
        if (key != null) {
            details.put("requestedAssembly", key.getGenomeAssembly());
            details.put("chromosome", key.getChromosome());
            details.put("position", key.getPosition());
            details.put("reference", key.getReference());
            details.put("alternate", key.getAlternate());
        }
        Map<String, Object> provenance = new LinkedHashMap<String, Object>();
        provenance.put("provider", "CLINVAR");
        provenance.put("recordSource", recordSource);
        provenance.put("matchType", matchType);
        provenance.put("evidenceAssembly", evidenceAssembly);
        return new EvidenceItem(
                EvidenceItem.CURRENT_SCHEMA_VERSION,
                "clinvar|" + identityPart(key == null ? null : key.getGenomeAssembly())
                        + "|" + status + "|" + identityPart(matchType),
                null, null, null, null, null, null, null, null,
                "CLINVAR", null, sourceVersion, sourceVersion, null, evidenceAssembly,
                "CLINICAL", legacyApplicability(status), status, null, null, null, null,
                EvidenceItem.INDETERMINATE_STRENGTH,
                labelForStatus(status), summary, provenance, null, null, details
        );
    }

    private static String legacyApplicability(String status) {
        if ("FAILED".equals(status) || "TIMEOUT".equals(status)
                || "DISABLED".equals(status) || "PARSE_ERROR".equals(status)) {
            return "UNAVAILABLE";
        }
        return status;
    }

    static List<String> splitValues(String raw) {
        if (!StringUtils.hasText(raw) || ".".equals(raw)) {
            return Collections.emptyList();
        }
        Set<String> values = new LinkedHashSet<String>();
        for (String value : raw.split("[|,]")) {
            String normalized = normalizeValue(value);
            if (normalized != null) {
                values.add(normalized);
            }
        }
        return new ArrayList<String>(values);
    }

    /**
     * Splits ClinVar condition fields only at their top-level group delimiter.
     * Null placeholders are retained so CLNDN and CLNDISDB remain positionally
     * aligned when one condition has no reported identifier.
     */
    static List<String> splitConditionGroups(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        for (String value : raw.split("\\|", -1)) {
            values.add(normalizeValue(value));
        }
        return values;
    }

    static List<String> splitConditionIdentifiers(String rawGroup) {
        if (!StringUtils.hasText(rawGroup) || ".".equals(rawGroup.trim())) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        for (String value : rawGroup.split(",", -1)) {
            String normalized = normalizeValue(value);
            if (normalized != null) values.add(normalized);
        }
        return values;
    }

    static List<String> splitClassifications(String raw) {
        if (!StringUtils.hasText(raw) || ".".equals(raw)) {
            return Collections.emptyList();
        }
        Set<String> values = new LinkedHashSet<String>();
        for (String value : raw.split("[|,/]+")) {
            String normalized = normalizeValue(value);
            if (normalized != null) {
                values.add(normalized);
            }
        }
        return new ArrayList<String>(values);
    }

    private static Map<String, Object> baseDetails(Record record, String status, String matchType) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", record.recordSource);
        details.put("status", status);
        details.put("matchType", matchType);
        details.put("sourceVersion", record.sourceVersion);
        details.put("resourceVersion", record.sourceVersion);
        details.put("recordIdentifiers", identifiers(record));
        details.put("interpretation", "ClinVar source evidence; review before clinical use");
        return details;
    }

    private static void putRuntimeAxes(Map<String, Object> details, String status) {
        if ("APPLICABLE".equals(status) || "CONFLICTING".equals(status)) {
            details.put("resourceExecutionStatus", "SUCCEEDED");
            details.put("resourceAvailabilityStatus", "AVAILABLE");
            details.put("queryOutcomeStatus", "HIT");
            details.put("resourceApplicabilityStatus", "APPLICABLE");
            return;
        }
        if ("NO_RECORD".equals(status)) {
            details.put("resourceExecutionStatus", "SUCCEEDED");
            details.put("resourceAvailabilityStatus", "AVAILABLE");
            details.put("queryOutcomeStatus", "NO_RECORD");
            details.put("resourceApplicabilityStatus", "APPLICABLE");
            return;
        }
        details.put("queryOutcomeStatus", "NOT_QUERIED");
        if ("ASSEMBLY_UNSUPPORTED".equals(status)) {
            details.put("resourceExecutionStatus", "NOT_RUN");
            details.put("resourceExecutionReasonCode", "ASSEMBLY_UNSUPPORTED");
            details.put("resourceAvailabilityStatus", "AVAILABLE");
            details.put("resourceApplicabilityStatus", "NOT_APPLICABLE");
            details.put("resourceApplicabilityReasonCode", "ASSEMBLY_UNSUPPORTED");
        } else if ("DISABLED".equals(status)) {
            details.put("resourceExecutionStatus", "DISABLED");
            details.put("resourceExecutionReasonCode", "PROVIDER_DISABLED");
            details.put("resourceAvailabilityStatus", "UNKNOWN");
            details.put("resourceAvailabilityReasonCode", "NOT_CHECKED");
            details.put("resourceApplicabilityStatus", "UNKNOWN");
            details.put("resourceApplicabilityReasonCode", "NOT_EVALUATED");
        } else if ("TIMEOUT".equals(status)) {
            details.put("resourceExecutionStatus", "TIMEOUT");
            details.put("resourceExecutionReasonCode", "PROVIDER_TIMEOUT");
            details.put("resourceAvailabilityStatus", "AVAILABLE");
            details.put("resourceApplicabilityStatus", "UNKNOWN");
            details.put("resourceApplicabilityReasonCode", "NOT_EVALUATED");
        } else if ("FAILED".equals(status) || "PARSE_ERROR".equals(status)) {
            details.put("resourceExecutionStatus", "FAILED");
            details.put("resourceExecutionReasonCode",
                    "PARSE_ERROR".equals(status) ? "PROVIDER_PAYLOAD_INVALID" : "PROVIDER_FAILED");
            details.put("resourceAvailabilityStatus", "AVAILABLE");
            details.put("resourceApplicabilityStatus", "UNKNOWN");
            details.put("resourceApplicabilityReasonCode", "NOT_EVALUATED");
        } else {
            details.put("resourceExecutionStatus", "NOT_RUN");
            details.put("resourceExecutionReasonCode", "RESOURCE_UNAVAILABLE");
            details.put("resourceAvailabilityStatus", "UNAVAILABLE");
            details.put("resourceAvailabilityReasonCode", "RESOURCE_UNAVAILABLE");
            details.put("resourceApplicabilityStatus", "UNKNOWN");
            details.put("resourceApplicabilityReasonCode", "NOT_EVALUATED");
        }
    }

    private static Map<String, Object> identifiers(Record record) {
        Map<String, Object> identifiers = new LinkedHashMap<String, Object>();
        putIfPresent(identifiers, "variationId", record.variationId);
        putIfPresent(identifiers, "accession", record.accession);
        putIfPresent(identifiers, "alleleId", record.alleleId);
        putIfPresent(identifiers, "rsId", record.rsId);
        if (!record.contributingSubmissionIdentifiers.isEmpty()) {
            identifiers.put("contributingSubmissionIdentifiers",
                    new ArrayList<String>(record.contributingSubmissionIdentifiers));
        }
        return identifiers;
    }

    private static String conflictState(Record record) {
        if (record.explicitConflict) {
            return "CONFLICTING_INTERPRETATIONS";
        }
        if (record.classifications.isEmpty()) {
            return null;
        }
        boolean pathogenic = false;
        boolean benign = false;
        for (String classification : record.classifications) {
            String normalized = classification.toLowerCase(Locale.ROOT)
                    .replace('_', ' ').replace('-', ' ');
            if (normalized.contains("pathogenic")) {
                pathogenic = true;
            }
            if (normalized.contains("benign")) {
                benign = true;
            }
        }
        return pathogenic && benign ? "CONFLICTING_INTERPRETATIONS" : "NO_CONFLICT";
    }

    private static String reviewLevel(String reviewStatus) {
        String value = reviewStatus == null ? "" : reviewStatus.toLowerCase(Locale.ROOT)
                .replace('_', ' ').replace('-', ' ');
        if (value.contains("practice guideline")) return "PRACTICE_GUIDELINE";
        if (value.contains("expert panel")) return "EXPERT_PANEL";
        if (value.contains("multiple submitters") && value.contains("no conflicts")) return "MULTIPLE_SUBMITTERS_NO_CONFLICT";
        if (value.contains("criteria provided")) return "CRITERIA_PROVIDED";
        if (value.contains("single submitter")) return "SINGLE_SUBMITTER";
        if (value.contains("no assertion")) return "NO_ASSERTION_CRITERIA";
        return StringUtils.hasText(reviewStatus) ? "OTHER" : "NOT_REPORTED";
    }

    private static List<Map<String, Object>> conditionMaps(List<Condition> conditions) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Condition condition : conditions) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            putIfPresent(value, "name", condition.name);
            putIfPresent(value, "id", condition.id);
            List<String> identifiers = splitConditionIdentifiers(condition.id);
            if (!identifiers.isEmpty()) value.put("identifiers", identifiers);
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private static String conditionIdentity(List<Condition> conditions) {
        List<String> values = new ArrayList<String>();
        for (Condition condition : conditions) values.add(first(condition.id, condition.name));
        return join(values);
    }

    private static String recordFingerprint(Record record) {
        List<String> values = new ArrayList<String>();
        values.add(record.aggregateClassification);
        values.add(record.reviewStatus);
        values.add(record.lastEvaluated);
        values.add(record.geneSymbol);
        values.add(record.geneInfo);
        return joinWithPipe(values);
    }

    private static String firstConditionName(List<Condition> conditions) {
        return conditions.isEmpty() ? null : conditions.get(0).name;
    }

    private static String firstConditionId(List<Condition> conditions) {
        return conditions.isEmpty() ? null : conditions.get(0).id;
    }

    private static String joinConditionNames(List<Condition> conditions) {
        List<String> values = new ArrayList<String>();
        for (Condition condition : conditions) if (condition.name != null) values.add(condition.name);
        return join(values);
    }

    private static String joinConditionIds(List<Condition> conditions) {
        List<String> values = new ArrayList<String>();
        for (Condition condition : conditions) if (condition.id != null) values.add(condition.id);
        return join(values);
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? null : joinWithPipe(values);
    }

    private static String joinWithPipe(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (!StringUtils.hasText(value)) continue;
            if (result.length() > 0) result.append('|');
            result.append(value);
        }
        return result.length() == 0 ? null : result.toString();
    }

    private static String label(String aggregateClassification) {
        return StringUtils.hasText(aggregateClassification) ? aggregateClassification : "ClinVar record";
    }

    private static String labelForStatus(String status) {
        if ("NO_RECORD".equals(status)) return "No record";
        if ("ASSEMBLY_UNSUPPORTED".equals(status)) return "Assembly unsupported";
        if ("PARSE_ERROR".equals(status)) return "Parse error";
        if ("TIMEOUT".equals(status)) return "Timeout";
        return "Unavailable";
    }

    private static String first(String... values) {
        for (String value : values) {
            String normalized = normalizeValue(value);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (normalizeValue(value) != null) values.put(key, value);
    }

    private static String normalizeValue(String value) {
        return StringUtils.hasText(value) && !".".equals(value.trim()) ? value.trim() : null;
    }

    private static String identityPart(String value) {
        return value == null ? "0:" : value.length() + ":" + value;
    }

    static final class Record {
        final String recordSource;
        final String sourceVersion;
        final String evidenceAssembly;
        final String aggregateClassification;
        final List<String> classifications;
        final String reviewStatus;
        final List<Condition> conditions;
        final String variationId;
        final String accession;
        final String alleleId;
        final String rsId;
        final String lastEvaluated;
        final String geneSymbol;
        final String geneInfo;
        final List<String> contributingSubmissionIdentifiers;
        final boolean explicitConflict;

        Record(String recordSource, String sourceVersion, String evidenceAssembly,
               String aggregateClassification, List<String> classifications, String reviewStatus,
               List<Condition> conditions, String variationId, String accession, String alleleId,
               String rsId, String lastEvaluated, String geneSymbol, String geneInfo,
               boolean explicitConflict) {
            this(recordSource, sourceVersion, evidenceAssembly, aggregateClassification,
                    classifications, reviewStatus, conditions, variationId, accession, alleleId,
                    rsId, lastEvaluated, geneSymbol, geneInfo,
                    Collections.<String>emptyList(), explicitConflict);
        }

        Record(String recordSource, String sourceVersion, String evidenceAssembly,
               String aggregateClassification, List<String> classifications, String reviewStatus,
               List<Condition> conditions, String variationId, String accession, String alleleId,
               String rsId, String lastEvaluated, String geneSymbol, String geneInfo,
               List<String> contributingSubmissionIdentifiers, boolean explicitConflict) {
            this.recordSource = recordSource;
            this.sourceVersion = sourceVersion;
            this.evidenceAssembly = evidenceAssembly;
            this.aggregateClassification = aggregateClassification;
            this.classifications = classifications == null ? Collections.<String>emptyList() : classifications;
            this.reviewStatus = reviewStatus;
            this.conditions = conditions == null ? Collections.<Condition>emptyList() : conditions;
            this.variationId = variationId;
            this.accession = accession;
            this.alleleId = alleleId;
            this.rsId = rsId;
            this.lastEvaluated = lastEvaluated;
            this.geneSymbol = geneSymbol;
            this.geneInfo = geneInfo;
            this.contributingSubmissionIdentifiers = contributingSubmissionIdentifiers == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(
                    new ArrayList<String>(contributingSubmissionIdentifiers));
            this.explicitConflict = explicitConflict;
        }
    }

    static final class Condition {
        final String name;
        final String id;

        Condition(String name, String id) {
            this.name = normalizeValue(name);
            this.id = normalizeValue(id);
        }
    }
}
