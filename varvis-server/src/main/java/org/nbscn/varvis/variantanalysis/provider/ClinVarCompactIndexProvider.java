package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ClinVarCompactIndexProvider implements VariantEvidenceProvider {

    static final String DETAILS_SOURCE = "LOCAL_CLINVAR_COMPACT";
    static final String EXACT_MATCH_TYPE = "EXACT_ALLELE_COMPACT_INDEX";

    private final VariantAnalysisProperties properties;
    private volatile LoadedIndex loadedIndex;

    public ClinVarCompactIndexProvider(VariantAnalysisProperties properties) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
    }

    @Override
    public String source() { return "CLINVAR"; }

    @Override
    public String version() {
        String sourceVersion = properties.getClinVar().getCompactIndex().getSourceVersion();
        return StringUtils.hasText(sourceVersion)
                ? sourceVersion
                : properties.getClinVar().versionFor("GRCh38");
    }

    @Override
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
            List<VariantInterpretation> variants, AnalysisContext context) {
        Map<NormalizedVariantKey, List<EvidenceItem>> result =
                new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
        if (!ProviderAssemblySupport.supportsOnlyGrch38(variants, context)) {
            addStatus(result, variants, "ASSEMBLY_UNSUPPORTED",
                    "ClinVar compact exact allele index supports GRCh38 only");
            return result;
        }
        if (!properties.getClinVar().isEnabled()) {
            addStatus(result, variants, "DISABLED", "ClinVar compact lookup is disabled because ClinVar is disabled");
            return result;
        }
        if (!properties.getClinVar().getCompactIndex().isEnabled()) {
            addStatus(result, variants, "DISABLED", "ClinVar compact exact allele index is disabled");
            return result;
        }
        Path file = properties.getClinVar().getCompactIndex().getFile();
        if (!available(file)) {
            addStatus(result, variants, "UNAVAILABLE", "Configured ClinVar compact exact allele index is unavailable");
            return result;
        }
        LoadedIndex index;
        try {
            index = load(file);
        } catch (IOException | RuntimeException ex) {
            addStatus(result, variants, "PARSE_ERROR", "ClinVar compact index could not be parsed: "
                    + SpecialistProviderSupport.rootMessage(ex));
            return result;
        }
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            String lookupKey = keyString(key);
            List<CompactRecord> records = index.records.get(lookupKey);
            if (records != null && !records.isEmpty()) {
                List<EvidenceItem> evidence = new ArrayList<EvidenceItem>();
                for (CompactRecord record : records) {
                    evidence.add(ClinVarEvidenceMapper.record(key, record.toSemanticRecord(), EXACT_MATCH_TYPE));
                }
                result.put(key, evidence);
            } else if (index.parseErrors.containsKey(lookupKey)) {
                result.put(key, Collections.singletonList(ClinVarEvidenceMapper.status(
                        key, version(), DETAILS_SOURCE, "GRCh38", "PARSE_ERROR",
                        index.parseErrors.get(lookupKey), "compact_record_parse_error")));
            } else {
                result.put(key, Collections.singletonList(ClinVarEvidenceMapper.status(
                        key, version(), DETAILS_SOURCE, "GRCh38", "NO_RECORD",
                        "Variant has no exact allele match in the configured ClinVar compact index", "no_record")));
            }
        }
        return result;
    }

    boolean isAvailable() {
        return properties.getClinVar().getCompactIndex().isEnabled()
                && available(properties.getClinVar().getCompactIndex().getFile());
    }

    private boolean available(Path file) { return file != null && Files.isRegularFile(file); }

    private LoadedIndex load(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        long modified = Files.getLastModifiedTime(normalized).toMillis();
        long size = Files.size(normalized);
        LoadedIndex current = loadedIndex;
        if (current != null && normalized.equals(current.file) && modified == current.modified && size == current.size) {
            return current;
        }
        Map<String, List<CompactRecord>> records = new LinkedHashMap<String, List<CompactRecord>>();
        Map<String, String> parseErrors = new LinkedHashMap<String, String>();
        try (BufferedReader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
            String line;
            Map<String, Integer> header = null;
            while ((line = reader.readLine()) != null) {
                if (!StringUtils.hasText(line) || line.startsWith("#")) continue;
                String[] columns = stripBom(line).split("\\t", -1);
                if (isHeader(columns)) {
                    header = header(columns);
                    continue;
                }
                ParsedRecord parsed = parseRecord(columns, header);
                if (parsed.record != null) {
                    List<CompactRecord> sameKey = records.get(parsed.record.key);
                    if (sameKey == null) {
                        sameKey = new ArrayList<CompactRecord>();
                        records.put(parsed.record.key, sameKey);
                    }
                    if (!containsSameRecord(sameKey, parsed.record)) sameKey.add(parsed.record);
                } else if (parsed.key != null) {
                    parseErrors.put(parsed.key, parsed.error);
                }
            }
        }
        LoadedIndex loaded = new LoadedIndex(normalized, modified, size, records, parseErrors);
        loadedIndex = loaded;
        return loaded;
    }

    private ParsedRecord parseRecord(String[] columns, Map<String, Integer> header) {
        String chromosome = value(columns, header, "chrom", 0);
        String positionText = value(columns, header, "pos", 1);
        String reference = normalizeAllele(value(columns, header, "ref", 2));
        String alternate = normalizeAllele(value(columns, header, "alt", 3));
        String key = keyString(chromosome, positionText, reference, alternate);
        if (key == null) return new ParsedRecord(null, null, null);
        try {
            Integer.parseInt(positionText);
        } catch (NumberFormatException ex) {
            return new ParsedRecord(null, key, "ClinVar compact record has an invalid position");
        }
        String significance = value(columns, header, "significance", 4);
        if (columns.length < 11 && header == null) {
            return new ParsedRecord(null, key, "ClinVar compact record has fewer than 11 required columns");
        }
        return new ParsedRecord(new CompactRecord(
                key, significance,
                value(columns, header, "reviewstatus", 5),
                value(columns, header, "gene", 6),
                value(columns, header, "variationid", 7),
                value(columns, header, "conditionname", 8),
                value(columns, header, "lastevaluated", 9),
                value(columns, header, "sourceversion", 10),
                value(columns, header, "accession", -1),
                value(columns, header, "alleleid", -1),
                value(columns, header, "rsid", -1),
                value(columns, header, "conditionid", -1),
                value(columns, header, "conflictstate", -1)
        ), null, null);
    }

    private boolean containsSameRecord(List<CompactRecord> records, CompactRecord candidate) {
        for (CompactRecord record : records) {
            if (record.identity().equals(candidate.identity())) return true;
        }
        return false;
    }

    private Map<String, Integer> header(String[] columns) {
        Map<String, Integer> values = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < columns.length; index++) {
            values.put(normalizeHeader(columns[index]), index);
        }
        return values;
    }

    private boolean isHeader(String[] columns) {
        return columns.length >= 4 && "chrom".equalsIgnoreCase(stripBom(columns[0]))
                && "pos".equalsIgnoreCase(columns[1]) && "ref".equalsIgnoreCase(columns[2])
                && "alt".equalsIgnoreCase(columns[3]);
    }

    private String value(String[] columns, Map<String, Integer> header, String name, int legacyIndex) {
        Integer index = header == null ? null : header.get(normalizeHeader(name));
        int resolved = index == null ? legacyIndex : index;
        return resolved >= 0 && resolved < columns.length ? blankToNull(columns[resolved]) : null;
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String keyString(NormalizedVariantKey key) {
        return keyString(key.getChromosome(), String.valueOf(key.getPosition()), key.getReference(), key.getAlternate());
    }

    private String keyString(String chromosome, String position, String reference, String alternate) {
        String normalizedChromosome = CoordinateUtils.normalizeChromosome(chromosome);
        if (!StringUtils.hasText(normalizedChromosome) || !StringUtils.hasText(position)
                || !StringUtils.hasText(reference) || !StringUtils.hasText(alternate)) return null;
        return normalizedChromosome + ":" + position + ":" + reference + ":" + alternate;
    }

    private String normalizeAllele(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) && !".".equals(value.trim()) ? value.trim() : null;
    }

    private String stripBom(String value) {
        return value != null && value.length() > 0 && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    private void addStatus(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                           List<VariantInterpretation> variants, String status, String summary) {
        for (VariantInterpretation variant : variants) {
            NormalizedVariantKey key = variant.getObservation().getKey();
            result.put(key, Collections.singletonList(ClinVarEvidenceMapper.status(
                    key, version(), DETAILS_SOURCE, "GRCh38", status, summary, "compact_index")));
        }
    }

    private static class LoadedIndex {
        private final Path file; private final long modified; private final long size;
        private final Map<String, List<CompactRecord>> records; private final Map<String, String> parseErrors;
        private LoadedIndex(Path file, long modified, long size, Map<String, List<CompactRecord>> records,
                            Map<String, String> parseErrors) {
            this.file = file; this.modified = modified; this.size = size;
            this.records = records; this.parseErrors = parseErrors;
        }
    }

    private static class ParsedRecord {
        private final CompactRecord record; private final String key; private final String error;
        private ParsedRecord(CompactRecord record, String key, String error) {
            this.record = record; this.key = key; this.error = error;
        }
    }

    private class CompactRecord {
        private final String key, significance, reviewStatus, gene, variationId, conditionName,
                lastEvaluated, sourceVersion, accession, alleleId, rsId, conditionId, conflictState;
        private CompactRecord(String key, String significance, String reviewStatus, String gene,
                              String variationId, String conditionName, String lastEvaluated,
                              String sourceVersion, String accession, String alleleId, String rsId,
                              String conditionId, String conflictState) {
            this.key = key; this.significance = significance; this.reviewStatus = reviewStatus;
            this.gene = gene; this.variationId = variationId; this.conditionName = conditionName;
            this.lastEvaluated = lastEvaluated; this.sourceVersion = sourceVersion;
            this.accession = accession; this.alleleId = alleleId; this.rsId = rsId;
            this.conditionId = conditionId; this.conflictState = conflictState;
        }
        private String identity() {
            return String.valueOf(variationId) + "|" + String.valueOf(accession) + "|"
                    + String.valueOf(alleleId) + "|" + String.valueOf(conditionName) + "|" + String.valueOf(conditionId);
        }
        private ClinVarEvidenceMapper.Record toSemanticRecord() {
            List<String> names = ClinVarEvidenceMapper.splitConditionGroups(conditionName);
            List<String> ids = ClinVarEvidenceMapper.splitConditionGroups(conditionId);
            List<ClinVarEvidenceMapper.Condition> conditions = new ArrayList<ClinVarEvidenceMapper.Condition>();
            int count = Math.max(names.size(), ids.size());
            for (int index = 0; index < count; index++) {
                conditions.add(new ClinVarEvidenceMapper.Condition(
                        index < names.size() ? names.get(index) : null,
                        index < ids.size() ? ids.get(index) : null));
            }
            return new ClinVarEvidenceMapper.Record(DETAILS_SOURCE,
                    StringUtils.hasText(sourceVersion) ? sourceVersion : version(), "GRCh38",
                    significance, ClinVarEvidenceMapper.splitClassifications(significance), reviewStatus,
                    conditions, variationId, accession, alleleId, rsId, lastEvaluated, gene, gene,
                    "CONFLICTING".equalsIgnoreCase(conflictState)
                            || "CONFLICTING_INTERPRETATIONS".equalsIgnoreCase(conflictState));
        }
    }
}
