package org.nbscn.varvis.variantanalysis.provider;

import htsjdk.tribble.readers.TabixReader;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ClinVarTabixProvider implements VariantEvidenceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClinVarTabixProvider.class);
    private static final int MAX_COMPARATOR_WINDOW_BASES = 101;

    private final VariantAnalysisProperties properties;
    private final ConcurrentMap<String, List<EvidenceItem>> cache = new ConcurrentHashMap<String, List<EvidenceItem>>();
    private final LineLookup lineLookup;
    private final BoundedLineLookup boundedLineLookup;

    public ClinVarTabixProvider(VariantAnalysisProperties properties) {
        this(properties, null);
    }

    ClinVarTabixProvider(VariantAnalysisProperties properties, LineLookup lineLookup) {
        this(properties, lineLookup, null);
    }

    ClinVarTabixProvider(VariantAnalysisProperties properties,
                         LineLookup lineLookup,
                         BoundedLineLookup boundedLineLookup) {
        this.properties = properties;
        this.lineLookup = lineLookup;
        this.boundedLineLookup = boundedLineLookup;
    }

    @Override
    public String source() {
        return "CLINVAR";
    }

    @Override
    public String version() {
        return properties.getClinVar().getVersion();
    }

    @Override
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
            List<VariantInterpretation> variants,
            AnalysisContext context) {
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (!properties.getClinVar().isEnabled()) {
            addStatus(result, variants, "DISABLED", "ClinVar local lookup is disabled");
            return result;
        }
        Map<Path, List<VariantInterpretation>> variantsByResource =
                new LinkedHashMap<Path, List<VariantInterpretation>>();
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            if (!supportsAssembly(key)) {
                result.put(key, Collections.singletonList(ClinVarEvidenceMapper.status(
                        key, versionFor(key), "LOCAL_CLINVAR", key.getGenomeAssembly(),
                        "ASSEMBLY_UNSUPPORTED", "ClinVar local lookup supports GRCh37 and GRCh38 only",
                        "native_tabix")));
                continue;
            }
            Path file = fileFor(key);
            if (!indexedFileAvailable(file)) {
                result.put(key, Collections.singletonList(unavailableEvidence(
                        "Configured ClinVar " + resourceAssembly(key) + " file or Tabix index is unavailable",
                        key
                )));
                continue;
            }
            List<VariantInterpretation> grouped = variantsByResource.get(file);
            if (grouped == null) {
                grouped = new ArrayList<VariantInterpretation>();
                variantsByResource.put(file, grouped);
            }
            grouped.add(variant);
        }
        if (lineLookup != null) {
            for (Map.Entry<Path, List<VariantInterpretation>> entry : variantsByResource.entrySet()) {
                try {
                    Path file = entry.getKey();
                    for (VariantInterpretation variant : entry.getValue()) {
                        NormalizedVariantKey key = variant.getObservation().getKey();
                        String cacheKey = cacheKey(file, key);
                        List<EvidenceItem> evidence = cache.get(cacheKey);
                        if (evidence == null) {
                            evidence = lookup(lineLookup.query(file, key), key);
                            cache.put(cacheKey, evidence);
                        }
                        result.put(key, evidence);
                    }
                } catch (IOException | RuntimeException ex) {
                    LOGGER.warn("ClinVar local lookup failed: {}", SpecialistProviderSupport.rootMessage(ex));
                    addFailure(result, entry.getValue(), ex);
                }
            }
            return result;
        }
        for (Map.Entry<Path, List<VariantInterpretation>> entry : variantsByResource.entrySet()) {
            try {
                Path file = entry.getKey();
                try (TabixReader reader = new TabixReader(file.toAbsolutePath().normalize().toString())) {
                    for (VariantInterpretation variant : entry.getValue()) {
                        NormalizedVariantKey key = variant.getObservation().getKey();
                        String cacheKey = cacheKey(file, key);
                        List<EvidenceItem> evidence = cache.get(cacheKey);
                        if (evidence == null) {
                            evidence = lookup(reader, key);
                            cache.put(cacheKey, evidence);
                        }
                        result.put(key, evidence);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                LOGGER.warn("ClinVar local lookup failed: {}", SpecialistProviderSupport.rootMessage(ex));
                addFailure(result, entry.getValue(), ex);
            }
        }
        return result;
    }

    /**
     * Performs one small GRCh38 Tabix region query for possible missense-SNV
     * comparators. Protein relation and ACMG criteria are intentionally outside
     * this provider boundary.
     */
    public ComparatorDiscoveryResult discoverBoundedComparators(
            NormalizedVariantKey target,
            int start,
            int end) {
        String inputReason = boundedInputReason(target, start, end);
        if (inputReason != null) {
            return ComparatorDiscoveryResult.notEvaluated(inputReason);
        }
        if (!properties.getClinVar().isEnabled()) {
            return ComparatorDiscoveryResult.notRun(
                    "DISABLED", "UNKNOWN", "UNKNOWN", "PROVIDER_DISABLED");
        }
        Path file = properties.getClinVar().getGrch38File();
        if (!indexedFileAvailable(file)) {
            return ComparatorDiscoveryResult.notRun(
                    "NOT_RUN", "UNAVAILABLE", "UNKNOWN", "RESOURCE_UNAVAILABLE");
        }
        String region = target.getChromosome() + ":" + start + "-" + end;
        try {
            List<String> lines;
            if (boundedLineLookup != null) {
                lines = boundedLineLookup.query(file, region);
            } else {
                lines = new ArrayList<String>();
                try (TabixReader reader = new TabixReader(file.toAbsolutePath().normalize().toString())) {
                    TabixReader.Iterator iterator = reader.query(region);
                    String line;
                    while (iterator != null && (line = iterator.next()) != null) {
                        lines.add(line);
                    }
                }
            }
            return comparatorResult(lines, target, start, end, region);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("ClinVar bounded comparator lookup failed: {}",
                    SpecialistProviderSupport.rootMessage(failure));
            return ComparatorDiscoveryResult.failed("CLINVAR_BOUNDED_QUERY_FAILED", region);
        }
    }

    private String boundedInputReason(NormalizedVariantKey target, int start, int end) {
        if (target == null) return "TARGET_VARIANT_REQUIRED";
        if (!"GRCh38".equalsIgnoreCase(target.getGenomeAssembly())) {
            return "ASSEMBLY_UNSUPPORTED";
        }
        if (!simpleSnv(target.getReference(), target.getAlternate())) {
            return "SIMPLE_SNV_REQUIRED";
        }
        long width = (long) end - start + 1L;
        if (start < 1 || end < start || target.getPosition() < start
                || target.getPosition() > end || width > MAX_COMPARATOR_WINDOW_BASES) {
            return "BOUNDED_REGION_INVALID";
        }
        return null;
    }

    private ComparatorDiscoveryResult comparatorResult(List<String> lines,
                                                       NormalizedVariantKey target,
                                                       int start,
                                                       int end,
                                                       String region) {
        Map<String, ComparatorCandidate> candidates =
                new LinkedHashMap<String, ComparatorCandidate>();
        if (lines == null) lines = Collections.emptyList();
        for (String line : lines) {
            if (!StringUtils.hasText(line) || line.startsWith("#")) continue;
            String[] columns = line.split("\t", -1);
            if (columns.length < 8) {
                return ComparatorDiscoveryResult.payloadFailed(
                        "CLINVAR_RECORD_PARSE_FAILED", region);
            }
            int position;
            try {
                position = Integer.parseInt(columns[1]);
            } catch (NumberFormatException failure) {
                return ComparatorDiscoveryResult.payloadFailed(
                        "CLINVAR_RECORD_PARSE_FAILED", region);
            }
            String chromosome = org.nbscn.varvis.utils.CoordinateUtils.normalizeChromosome(columns[0]);
            if (!target.getChromosome().equals(chromosome) || position < start || position > end) continue;
            if (columns[4].contains(",") || !simpleSnv(columns[3], columns[4])) continue;

            String reference = columns[3].toUpperCase(Locale.ROOT);
            String alternate = columns[4].toUpperCase(Locale.ROOT);
            NormalizedVariantKey candidateKey = new NormalizedVariantKey(
                    "GRCh38", chromosome, position, reference, alternate);
            if (sameAllele(target, candidateKey)) continue;

            long variationId;
            try {
                if (!columns[2].matches("[1-9][0-9]*")) {
                    return ComparatorDiscoveryResult.payloadFailed(
                            "CLINVAR_VARIATION_ID_INVALID", region);
                }
                variationId = Long.parseLong(columns[2]);
            } catch (NumberFormatException failure) {
                return ComparatorDiscoveryResult.payloadFailed(
                        "CLINVAR_VARIATION_ID_INVALID", region);
            }
            Map<String, String> info = parseInfo(columns[7]);
            ComparatorCandidate candidate = new ComparatorCandidate(
                    candidateKey,
                    variationId,
                    splitPipeValues(info.get("CLNSIGSCV")),
                    "ClinVar:VariationID:" + variationId
            );
            String alleleId = candidateKey.canonicalId();
            ComparatorCandidate existing = candidates.get(alleleId);
            if (existing != null && existing.variationId != variationId) {
                return ComparatorDiscoveryResult.ambiguous(
                        "CLINVAR_COMPARATOR_ALLELE_AMBIGUOUS", region);
            }
            candidates.put(alleleId, existing == null ? candidate : existing.merge(candidate));
        }
        return ComparatorDiscoveryResult.ready(
                new ArrayList<ComparatorCandidate>(candidates.values()), region);
    }

    private boolean sameAllele(NormalizedVariantKey left, NormalizedVariantKey right) {
        return left.getChromosome().equals(right.getChromosome())
                && left.getPosition() == right.getPosition()
                && left.getReference().equalsIgnoreCase(right.getReference())
                && left.getAlternate().equalsIgnoreCase(right.getAlternate());
    }

    private boolean simpleSnv(String reference, String alternate) {
        return reference != null && alternate != null
                && reference.length() == 1 && alternate.length() == 1
                && "ACGTacgt".indexOf(reference.charAt(0)) >= 0
                && "ACGTacgt".indexOf(alternate.charAt(0)) >= 0
                && !reference.equalsIgnoreCase(alternate);
    }

    private List<EvidenceItem> lookup(TabixReader reader, NormalizedVariantKey key) throws IOException {
        List<String> lines = new ArrayList<String>();
        for (String region : SpecialistProviderSupport.tabixRegions(key)) {
            TabixReader.Iterator iterator = reader.query(region);
            String line;
            while (iterator != null && (line = iterator.next()) != null) {
                lines.add(line);
            }
        }
        return lookup(lines, key);
    }

    private List<EvidenceItem> lookup(List<String> lines, NormalizedVariantKey key) {
        boolean positionOnlyRejected = false;
        boolean parseError = false;
        List<EvidenceItem> evidence = new ArrayList<EvidenceItem>();
        for (String line : lines) {
            if (line.startsWith("#")) {
                continue;
            }
            MatchResult match = parseLine(line, key);
            evidence.addAll(match.exactEvidence);
            positionOnlyRejected = positionOnlyRejected || match.positionOnlyRejected;
            parseError = parseError || match.parseError;
        }
        if (!evidence.isEmpty()) return deduplicate(evidence);
        if (parseError) return Collections.singletonList(ClinVarEvidenceMapper.status(
                key, versionFor(key), "LOCAL_CLINVAR", resourceAssembly(key), "PARSE_ERROR",
                "ClinVar record could not be parsed", "tabix_record_parse_error"));
        return Collections.singletonList(noRecordEvidence(
                key, positionOnlyRejected ? "position_only_rejected" : "no_record"));
    }

    private EvidenceItem noRecordEvidence(NormalizedVariantKey key, String matchType) {
        return ClinVarEvidenceMapper.status(
                key, versionFor(key), "LOCAL_CLINVAR", resourceAssembly(key), "NO_RECORD",
                "Variant has no exact allele match in the configured ClinVar "
                        + resourceAssembly(key) + " file", matchType);
    }

    private MatchResult parseLine(String line, NormalizedVariantKey key) {
        String[] columns = line.split("\t");
        if (columns.length < 8) {
            return MatchResult.parseError();
        }
        String[] alternates = columns[4].split(",");
        int alternateIndex = -1;
        for (int index = 0; index < alternates.length; index++) {
            if (SpecialistProviderSupport.matchesCoordinates(
                    columns[0],
                    columns[1],
                    columns[3],
                    alternates[index],
                    key
            )) {
                alternateIndex = index;
                break;
            }
        }
        if (alternateIndex < 0) {
            boolean samePosition = key.getChromosome().equals(
                    org.nbscn.varvis.utils.CoordinateUtils.normalizeChromosome(columns[0])
            ) && String.valueOf(key.getPosition()).equals(columns[1]);
            return MatchResult.noMatch(samePosition);
        }
        Map<String, String> info = parseInfo(columns[7]);
        String significance = info.get("CLNSIG");
        List<ClinVarEvidenceMapper.Condition> conditions = conditions(info.get("CLNDN"), info.get("CLNDISDB"));
        ClinVarEvidenceMapper.Record record = new ClinVarEvidenceMapper.Record(
                "LOCAL_CLINVAR", versionFor(key), resourceAssembly(key), significance,
                ClinVarEvidenceMapper.splitClassifications(significance), info.get("CLNREVSTAT"), conditions,
                firstPresent(info.get("CLNVID"), info.get("VariationID"), columns[2]),
                firstPresent(info.get("CLNACC"), info.get("RCVACC"), info.get("VCV")),
                info.get("ALLELEID"), info.get("RS"),
                firstPresent(info.get("CLNDATE"), info.get("LastEvaluated")),
                geneSymbol(info.get("GENEINFO")), info.get("GENEINFO"),
                splitPipeValues(info.get("CLNSIGSCV")),
                hasExplicitConflict(significance, info.get("CLNREVSTAT"), info.get("CLNSIGCONF"))
        );
        return MatchResult.exact(ClinVarEvidenceMapper.record(key, record, "exact_allele"));
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value) && !".".equals(value)) {
                return value;
            }
        }
        return null;
    }

    private String geneSymbol(String geneInfo) {
        if (!StringUtils.hasText(geneInfo)) {
            return null;
        }
        String first = geneInfo.split("[|,]", -1)[0];
        int separator = first.indexOf(':');
        return separator > 0 ? first.substring(0, separator) : first;
    }

    private List<ClinVarEvidenceMapper.Condition> conditions(String names, String ids) {
        List<String> nameValues = ClinVarEvidenceMapper.splitConditionGroups(names);
        List<String> idValues = ClinVarEvidenceMapper.splitConditionGroups(ids);
        List<ClinVarEvidenceMapper.Condition> conditions =
                new ArrayList<ClinVarEvidenceMapper.Condition>();
        int count = Math.max(nameValues.size(), idValues.size());
        for (int index = 0; index < count; index++) {
            conditions.add(new ClinVarEvidenceMapper.Condition(
                    index < nameValues.size() ? nameValues.get(index) : null,
                    index < idValues.size() ? idValues.get(index) : null));
        }
        return conditions;
    }

    private List<String> splitPipeValues(String raw) {
        List<String> groups = ClinVarEvidenceMapper.splitConditionGroups(raw);
        List<String> values = new ArrayList<String>();
        for (String group : groups) {
            if (group != null) values.add(group);
        }
        return values;
    }

    private boolean hasExplicitConflict(String significance, String reviewStatus, String submittedClassifications) {
        if (containsExactStatus(significance, "conflicting classifications of pathogenicity")) {
            return true;
        }
        if (containsExactStatus(reviewStatus, "conflicting classifications")) {
            return true;
        }
        List<String> submissions = ClinVarEvidenceMapper.splitClassifications(submittedClassifications);
        return hasPathogenicAndBenign(submissions);
    }

    private boolean containsExactStatus(String value, String expected) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String token : value.split("[|,]")) {
            String normalized = token.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace('_', ' ').replace('-', ' ');
            if (expected.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPathogenicAndBenign(List<String> classifications) {
        boolean pathogenic = false;
        boolean benign = false;
        for (String classification : classifications) {
            String normalized = classification.toLowerCase(java.util.Locale.ROOT)
                    .replace('_', ' ').replace('-', ' ');
            pathogenic = pathogenic || normalized.contains("pathogenic");
            benign = benign || normalized.contains("benign");
        }
        return pathogenic && benign;
    }

    private Map<String, String> parseInfo(String value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (!StringUtils.hasText(value)) {
            return result;
        }
        for (String token : value.split(";")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            int separator = token.indexOf('=');
            if (separator < 0) {
                result.put(token, "true");
            } else {
                result.put(token.substring(0, separator), token.substring(separator + 1));
            }
        }
        return result;
    }

    private void addFailure(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                            List<VariantInterpretation> variants,
                            Throwable failure) {
        String message = SpecialistProviderSupport.rootMessage(failure);
        String status = message != null && message.toLowerCase().contains("timed out")
                ? "TIMEOUT" : "FAILED";
        addStatus(result, variants, status, "ClinVar local lookup failed: " + message);
    }

    private void addStatus(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                           List<VariantInterpretation> variants,
                           String status,
                           String summary) {
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            result.put(
                    key,
                    Collections.singletonList(ClinVarEvidenceMapper.status(
                            key, versionFor(key), "LOCAL_CLINVAR", resourceAssembly(key), status, summary,
                            "native_tabix"))
            );
        }
    }

    private EvidenceItem unavailableEvidence(String summary, NormalizedVariantKey key) {
        return ClinVarEvidenceMapper.status(
                key, versionFor(key), "LOCAL_CLINVAR", resourceAssembly(key), "UNAVAILABLE", summary,
                "native_tabix");
    }

    private String versionFor(NormalizedVariantKey key) {
        return properties.getClinVar().versionFor(key == null ? null : key.getGenomeAssembly());
    }

    private boolean indexedFileAvailable(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        Path tbi = file.resolveSibling(file.getFileName() + ".tbi");
        Path csi = file.resolveSibling(file.getFileName() + ".csi");
        return Files.isRegularFile(tbi) || Files.isRegularFile(csi);
    }

    private Path fileFor(NormalizedVariantKey key) {
        return "GRCh37".equalsIgnoreCase(key.getGenomeAssembly())
                ? properties.getClinVar().getGrch37File()
                : properties.getClinVar().getGrch38File();
    }

    private String resourceAssembly(NormalizedVariantKey key) {
        return "GRCh37".equalsIgnoreCase(key.getGenomeAssembly()) ? "GRCh37" : "GRCh38";
    }

    private boolean supportsAssembly(NormalizedVariantKey key) {
        return "GRCh37".equalsIgnoreCase(key.getGenomeAssembly())
                || "GRCh38".equalsIgnoreCase(key.getGenomeAssembly());
    }

    private String cacheKey(Path file, NormalizedVariantKey key) {
        return file.toAbsolutePath().normalize() + "|"
                + key.getGenomeAssembly() + "|"
                + key.getChromosome() + "|"
                + key.getPosition() + "|"
                + key.getReference() + "|"
                + key.getAlternate();
    }

    private List<EvidenceItem> deduplicate(List<EvidenceItem> evidence) {
        Map<String, EvidenceItem> unique = new LinkedHashMap<String, EvidenceItem>();
        for (EvidenceItem item : evidence) {
            String identity = item == null ? null : item.getStableIdentityKey();
            if (identity != null && !unique.containsKey(identity)) {
                unique.put(identity, item);
            }
        }
        return new ArrayList<EvidenceItem>(unique.values());
    }

    interface LineLookup {
        List<String> query(Path file, NormalizedVariantKey key) throws IOException;
    }

    interface BoundedLineLookup {
        List<String> query(Path file, String region) throws IOException;
    }

    public static final class ComparatorDiscoveryResult {
        private final String resourceExecutionStatus;
        private final String resourceAvailabilityStatus;
        private final String queryOutcomeStatus;
        private final String resourceApplicabilityStatus;
        private final String readinessStatus;
        private final String reasonCode;
        private final String queriedRegion;
        private final List<ComparatorCandidate> candidates;

        private ComparatorDiscoveryResult(String resourceExecutionStatus,
                                          String resourceAvailabilityStatus,
                                          String queryOutcomeStatus,
                                          String resourceApplicabilityStatus,
                                          String readinessStatus,
                                          String reasonCode,
                                          String queriedRegion,
                                          List<ComparatorCandidate> candidates) {
            this.resourceExecutionStatus = resourceExecutionStatus;
            this.resourceAvailabilityStatus = resourceAvailabilityStatus;
            this.queryOutcomeStatus = queryOutcomeStatus;
            this.resourceApplicabilityStatus = resourceApplicabilityStatus;
            this.readinessStatus = readinessStatus;
            this.reasonCode = reasonCode;
            this.queriedRegion = queriedRegion;
            this.candidates = Collections.unmodifiableList(
                    new ArrayList<ComparatorCandidate>(candidates));
        }

        private static ComparatorDiscoveryResult ready(
                List<ComparatorCandidate> candidates, String region) {
            return new ComparatorDiscoveryResult(
                    "SUCCEEDED", "AVAILABLE", candidates.isEmpty() ? "NO_RECORD" : "HIT",
                    "APPLICABLE", "READY", null, region, candidates);
        }

        private static ComparatorDiscoveryResult ambiguous(String reasonCode, String region) {
            return new ComparatorDiscoveryResult(
                    "SUCCEEDED", "AVAILABLE", "HIT", "APPLICABLE",
                    "NOT_EVALUATED", reasonCode, region,
                    Collections.<ComparatorCandidate>emptyList());
        }

        private static ComparatorDiscoveryResult failed(String reasonCode, String region) {
            return new ComparatorDiscoveryResult(
                    "FAILED", "AVAILABLE", "NOT_QUERIED", "UNKNOWN",
                    "NOT_EVALUATED", reasonCode, region,
                    Collections.<ComparatorCandidate>emptyList());
        }

        private static ComparatorDiscoveryResult payloadFailed(String reasonCode, String region) {
            return new ComparatorDiscoveryResult(
                    "FAILED", "AVAILABLE", "HIT", "APPLICABLE",
                    "NOT_EVALUATED", reasonCode, region,
                    Collections.<ComparatorCandidate>emptyList());
        }

        private static ComparatorDiscoveryResult notEvaluated(String reasonCode) {
            return notRun("NOT_RUN", "UNKNOWN", "NOT_APPLICABLE", reasonCode);
        }

        private static ComparatorDiscoveryResult notRun(String execution,
                                                         String availability,
                                                         String applicability,
                                                         String reasonCode) {
            return new ComparatorDiscoveryResult(
                    execution, availability, "NOT_QUERIED", applicability,
                    "NOT_EVALUATED", reasonCode, null,
                    Collections.<ComparatorCandidate>emptyList());
        }

        public String getResourceExecutionStatus() { return resourceExecutionStatus; }
        public String getResourceAvailabilityStatus() { return resourceAvailabilityStatus; }
        public String getQueryOutcomeStatus() { return queryOutcomeStatus; }
        public String getResourceApplicabilityStatus() { return resourceApplicabilityStatus; }
        public String getReadinessStatus() { return readinessStatus; }
        public String getReasonCode() { return reasonCode; }
        public String getQueriedRegion() { return queriedRegion; }
        public List<ComparatorCandidate> getCandidates() { return candidates; }
    }

    public static final class ComparatorCandidate {
        private final NormalizedVariantKey exactAllele;
        private final long variationId;
        private final List<String> contributingSubmissionIdentifiers;
        private final String recordLocator;

        private ComparatorCandidate(NormalizedVariantKey exactAllele,
                                    long variationId,
                                    List<String> contributingSubmissionIdentifiers,
                                    String recordLocator) {
            this.exactAllele = exactAllele;
            this.variationId = variationId;
            this.contributingSubmissionIdentifiers = Collections.unmodifiableList(
                    new ArrayList<String>(contributingSubmissionIdentifiers));
            this.recordLocator = recordLocator;
        }

        private ComparatorCandidate merge(ComparatorCandidate other) {
            Set<String> identifiers = new LinkedHashSet<String>(contributingSubmissionIdentifiers);
            identifiers.addAll(other.contributingSubmissionIdentifiers);
            return new ComparatorCandidate(exactAllele, variationId,
                    new ArrayList<String>(identifiers), recordLocator);
        }

        public NormalizedVariantKey getExactAllele() { return exactAllele; }
        public long getVariationId() { return variationId; }
        public List<String> getContributingSubmissionIdentifiers() {
            return contributingSubmissionIdentifiers;
        }
        public String getRecordLocator() { return recordLocator; }
    }

    private static class MatchResult {

        private final List<EvidenceItem> exactEvidence;
        private final boolean positionOnlyRejected;
        private final boolean parseError;

        private MatchResult(List<EvidenceItem> exactEvidence,
                            boolean positionOnlyRejected,
                            boolean parseError) {
            this.exactEvidence = exactEvidence;
            this.positionOnlyRejected = positionOnlyRejected;
            this.parseError = parseError;
        }

        private static MatchResult exact(EvidenceItem evidence) {
            return new MatchResult(Collections.singletonList(evidence), false, false);
        }

        private static MatchResult noMatch(boolean positionOnlyRejected) {
            return new MatchResult(Collections.<EvidenceItem>emptyList(), positionOnlyRejected, false);
        }

        private static MatchResult parseError() {
            return new MatchResult(Collections.<EvidenceItem>emptyList(), false, true);
        }
    }
}
