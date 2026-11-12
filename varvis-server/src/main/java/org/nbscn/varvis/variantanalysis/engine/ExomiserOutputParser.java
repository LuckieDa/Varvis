package org.nbscn.varvis.variantanalysis.engine;

import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ArtifactKind;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ScanException;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ScanResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Maps Exomiser TSV artifacts into the existing VariantInterpretation model. */
final class ExomiserOutputParser {

    /** Engine provenance is not a clinical triage tier. */
    private static final String ENGINE_RANKED_TIER = "REVIEW";
    private static final String ENGINE_RANKED_TIER_REASON =
            "Candidate is ranked for review; inspect the retained evidence before clinical interpretation.";

    private final ExomiserTsvScanner scanner;

    ExomiserOutputParser() {
        this(new ExomiserTsvScanner());
    }

    ExomiserOutputParser(ExomiserTsvScanner scanner) {
        if (scanner == null) {
            throw new IllegalArgumentException("EXOMISER_TSV_SCANNER_REQUIRED");
        }
        this.scanner = scanner;
    }

    List<VariantInterpretation> parse(Path outputDirectory, String sampleId, String version) throws IOException {
        return parseVerified(outputDirectory, sampleId, version).interpretations();
    }

    ParsedOutput parseVerified(Path outputDirectory, String sampleId, String version) throws IOException {
        return parseVerified(outputDirectory, sampleId, version, null);
    }

    ParsedOutput parseVerified(Path outputDirectory, String sampleId, String version, AnalysisScopePolicy.Policy policy) throws IOException {
        boolean supplement = policy != null && policy.supplementsZeroPassed();
        if (outputDirectory == null) {
            throw parseFailure();
        }
        Path variants = outputDirectory.resolve("result.variants.tsv");
        Path genes = outputDirectory.resolve("result.genes.tsv");
        try {
            scanner.verifyRequiredNonEmpty(variants);
        } catch (ScanException error) {
            throw mappedScanFailure(error, true);
        }

        List<ExomiserZeroPassCoverage.GeneRow> coverageGenes = new ArrayList<>();
        GeneRowIndex availableGeneRows = new GeneRowIndex(true);
        GeneRowIndex geneRows;
        ScanResult geneScan;
        try {
            geneScan = scanner.scan(genes, ArtifactKind.GENES, (row, ordinal) -> {
                availableGeneRows.add(row, ordinal);
                if (supplement) coverageGenes.add(new ExomiserZeroPassCoverage.GeneRow(row, ordinal));
            });
            geneRows = availableGeneRows;
        } catch (ScanException error) {
            if (error.code() == ExomiserTsvScanner.Code.FILE_MISSING) {
                if (supplement) throw new IllegalArgumentException("EXOMISER_OUTPUT_COVERAGE_GENES_MISSING");
                geneScan = null;
                geneRows = new GeneRowIndex(false);
            } else {
                throw mappedScanFailure(error, false);
            }
        }

        boolean geneArtifactAvailable = geneScan != null;
        final GeneRowIndex resolvedGeneRows = geneRows;
        List<VariantInterpretation> values = new ArrayList<VariantInterpretation>();
        Set<String> existing = new LinkedHashSet<>();
        ScanResult variantScan;
        try {
            variantScan = scanner.scan(variants, ArtifactKind.VARIANTS, (row, sourceOrdinal) -> {
                    if (supplement) existing.add(ExomiserZeroPassCoverage.key(row));
                    values.add(toInterpretation(
                            row,
                            resolvedGeneRows.find(row),
                            sourceOrdinal,
                            sampleId,
                            version,
                            variants.getFileName().toString(),
                            geneArtifactAvailable ? genes.getFileName().toString() : null, null
                    )); }
            );
        } catch (ScanException error) {
            throw mappedScanFailure(error, true);
        }
        try {
            if (geneScan == null) {
                scanner.verifyAbsent(genes);
            } else {
                scanner.verifyUnchanged(genes, geneScan);
            }
        } catch (ScanException error) {
            throw mappedScanFailure(error, false);
        }
        ScanResult jsonScan = null;
        com.fasterxml.jackson.databind.node.ObjectNode manifest = null;
        if (policy != null && policy.supplementsZeroPassed()) {
            var verifiedGeneScan = Objects.requireNonNull(geneScan, "EXOMISER_OUTPUT_COVERAGE_GENES_MISSING");
            var coverage = ExomiserZeroPassCoverage.read(outputDirectory, scanner, coverageGenes, existing, version);
            if (variantScan.dataRowCount() + coverage.additions().size() > 50_000)
                throw new IllegalArgumentException("EXOMISER_OUTPUT_COVERAGE_CANDIDATE_LIMIT");
            jsonScan = coverage.scan();
            manifest = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("policy", policy.outputCoveragePolicy()).put("policySha256", policy.policySha256())
                    .put("parser", ExomiserZeroPassCoverage.PARSER).put("engineVersion", version)
                    .put("outputContributingVariantsOnly", false).put("numberOfGenesToShow", 0).put("minExomiserGeneScore", 0)
                    .put("order", "existing TSV order, then gene/MOI rank,symbol,MOI,contig,position,REF,ALT")
                    .put("originalVariantRows", variantScan.dataRowCount()).put("supplementedRows", coverage.additions().size());
            var artifacts = manifest.putObject("artifacts");
            artifacts.putObject("result.variants.tsv").put("sha256",variantScan.rawSha256()).put("bytes",variantScan.byteCount());
            artifacts.putObject("result.genes.tsv").put("sha256",verifiedGeneScan.rawSha256()).put("bytes",verifiedGeneScan.byteCount());
            artifacts.putObject("result.jsonl").put("sha256",jsonScan.rawSha256()).put("bytes",jsonScan.byteCount());
            for (var addition : coverage.additions()) {
                Map<String,Object> provenance = new LinkedHashMap<>();
                provenance.put("outputCoverage", manifest.deepCopy());
                provenance.put("jsonlLineOrdinal",addition.jsonLine());
                provenance.put("jsonlVariantEvaluation",addition.variant());
                provenance.put("jsonlGeneScore",addition.score());
                provenance.put("derivedPublicationOrdinal", values.size()+1);
                provenance.put("ordinalSemantics", "DERIVED_APPEND_ORDER_NOT_UPSTREAM_VARIANT_RANK");
                values.add(toInterpretation(addition.row(),
                        new GeneRowMatch(new SourceRow(addition.gene().row(),addition.gene().ordinal()), "EXACT_JSONL_GENE_MOI"),
                        values.size()+1,sampleId,version,"result.jsonl","result.genes.tsv",provenance));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("EXOMISER_OUTPUT_VARIANTS_EMPTY");
        }
        return new ParsedOutput(values, variantScan, geneScan, jsonScan, manifest);
    }

    /**
     * Performs the metadata-only final publication check after downstream hydration.
     * The digest and parsed rows still come exclusively from the original single content opens.
     */
    void verifyStillCurrent(Path outputDirectory, ParsedOutput parsedOutput) {
        if (outputDirectory == null || parsedOutput == null) {
            throw parseFailure();
        }
        Path variants = outputDirectory.resolve("result.variants.tsv");
        Path genes = outputDirectory.resolve("result.genes.tsv");
        try {
            scanner.verifyUnchanged(variants, parsedOutput.variantScan());
            if (parsedOutput.jsonScan() != null) scanner.verifyUnchanged(outputDirectory.resolve("result.jsonl"),parsedOutput.jsonScan());
            if (parsedOutput.geneScan() == null) {
                scanner.verifyAbsent(genes);
            } else {
                scanner.verifyUnchanged(genes, parsedOutput.geneScan());
            }
        } catch (ScanException error) {
            throw parseFailure();
        }
    }

    private IllegalArgumentException mappedScanFailure(ScanException error,
                                                       boolean variantArtifact) {
        if (variantArtifact && (error.code() == ExomiserTsvScanner.Code.FILE_MISSING
                || error.code() == ExomiserTsvScanner.Code.FILE_INVALID
                || error.code() == ExomiserTsvScanner.Code.HEADER_MISSING)) {
            return new IllegalArgumentException("EXOMISER_OUTPUT_VARIANTS_MISSING");
        }
        if (error.code() == ExomiserTsvScanner.Code.TSV_COLUMN_COUNT_MISMATCH) {
            return new IllegalArgumentException("TSV_COLUMN_COUNT_MISMATCH");
        }
        return parseFailure();
    }

    private IllegalArgumentException parseFailure() {
        return new IllegalArgumentException("EXOMISER_OUTPUT_PARSE_FAILED");
    }

    record ParsedOutput(List<VariantInterpretation> interpretations,
                        ScanResult variantScan,
                        ScanResult geneScan, ScanResult jsonScan,
                        com.fasterxml.jackson.databind.node.ObjectNode manifest) {

        String outputSha256() { return manifest == null ? variantScan.rawSha256()
                : org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(manifest.toString()); }

        ParsedOutput {
            interpretations = List.copyOf(Objects.requireNonNull(
                    interpretations, "interpretations"));
            variantScan = Objects.requireNonNull(variantScan, "variantScan");
        }
    }

    private VariantInterpretation toInterpretation(Map<String, String> row,
                                                   GeneRowMatch geneMatch,
                                                   int sourceOrdinal,
                                                   String sampleId,
                                                   String version,
                                                   String variantArtifactName,
                                                   String geneArtifactName, Map<String,Object> provenance) {
        try {
            NormalizedVariantKey key = NormalizedVariantKey.canonical(
                    GenomeAssembly.GRCh38,
                    required(row, "CONTIG"),
                    Integer.parseInt(required(row, "START")),
                    required(row, "REF"),
                    required(row, "ALT")
            );
            ParsedFeatures parsedFeatures = features(
                    row,
                    geneMatch,
                    sourceOrdinal,
                    version,
                    variantArtifactName,
                    geneArtifactName
            );
            Double score = parsedFeatures.rankingScore;
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            if (provenance == null) details.put("variant", new LinkedHashMap<String, String>(row));
            else { details.putAll(provenance); details.put("normalizedJsonlFields",new LinkedHashMap<>(row)); }
            if (geneMatch.row != null) {
                details.put("gene", new LinkedHashMap<String, String>(geneMatch.row.values));
            }
            details.put("rank", required(row, "#RANK"));
            details.put("geneSymbol", parsedFeatures.features.getGeneSymbol());
            EvidenceItem evidence = new EvidenceItem(
                    "EXOMISER", version, "EXOMISER_RANK", "AVAILABLE", score,
                    "Engine-ranked candidate", provenance == null ? "Raw ranking TSV fields are retained in details."
                            : "Passed zero-score candidate from same-run JSONL and gene TSV; derived append order.", details
            ).withVariantContext(key);
            VariantQcObservation observation = new VariantQcObservation(
                    key, sampleId, null, null, null, null, null, value(row, "FILTER"),
                    variantType(key.getReference(), key.getAlternate()), null
            );
            CandidateIdentity candidateIdentity = CandidateIdentity.exomiser(
                    key.canonicalId(),
                    sourceOrdinal,
                    parsedFeatures.features.getGeneSymbol(),
                    parsedFeatures.features.getRawModeOfInheritance(),
                    integer(row, "#RANK")
            );
            VariantInterpretation interpretation = new VariantInterpretation(
                    candidateIdentity,
                    observation,
                    Collections.emptyList(),
                    Collections.singletonList(evidence),
                    parsedFeatures.features
            );
            Map<String, Double> scoreBreakdown = score == null
                    ? Collections.<String, Double>emptyMap()
                    : Collections.singletonMap("exomiserRawScore", score);
            interpretation.applyRanking(
                    ENGINE_RANKED_TIER,
                    score,
                    scoreBreakdown,
                    Collections.<String, ScoreBreakdownComponent>emptyMap(),
                    ENGINE_RANKED_TIER_REASON,
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.singletonList(
                            "Engine gene/MOI group rank " + required(row, "#RANK")
                                    + " (native Exomiser rank; not a global variant rank)."
                    ),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList()
            );
            return interpretation;
        } catch (RuntimeException ignored) {
            throw parseFailure();
        }
    }

    private ParsedFeatures features(Map<String, String> variantRow,
                                    GeneRowMatch geneMatch,
                                    int variantSourceOrdinal,
                                    String engineVersion,
                                    String variantArtifactName,
                                    String geneArtifactName) {
        Set<String> missingFields = new LinkedHashSet<String>();
        Set<String> invalidFields = new LinkedHashSet<String>();
        Map<String, String> fieldSources = new LinkedHashMap<String, String>();
        SourceRow geneRow = geneMatch.row;

        String geneSymbol = textField(
                "geneSymbol", "GENE_SYMBOL", variantRow, geneRow, missingFields, fieldSources
        );
        String entrezGeneId = textField(
                "entrezGeneId", "ENTREZ_GENE_ID", variantRow, geneRow, missingFields, fieldSources
        );
        String rawMoi = textField(
                "rawModeOfInheritance", "MOI", variantRow, geneRow, missingFields, fieldSources
        );
        String normalizedMoi = normalizeMoi(rawMoi);
        if (normalizedMoi != null) {
            fieldSources.put("normalizedModeOfInheritance", "DERIVED:MOI");
        } else if (rawMoi != null) {
            invalidFields.add("MOI:UNRECOGNIZED");
        }

        String rankValue = value(variantRow, "#RANK");
        Integer geneMoiGroupRank = integer(variantRow, "#RANK");
        if (rankValue == null) {
            missingFields.add("#RANK");
        } else {
            fieldSources.put("geneMoiGroupRank", "VARIANT_TSV:#RANK");
            if (geneMoiGroupRank == null) {
                invalidFields.add("#RANK");
            }
        }

        String sourceRecordId = variantTextField(
                "sourceRecordId", "ID", variantRow, missingFields, fieldSources
        );
        Double variantScore = numericField(
                "variantScore", "EXOMISER_VARIANT_SCORE", variantRow, null,
                missingFields, invalidFields, fieldSources
        );
        Double geneCombinedScore = numericField(
                "geneCombinedScore", "EXOMISER_GENE_COMBINED_SCORE", variantRow, geneRow,
                missingFields, invalidFields, fieldSources
        );
        Double genePhenotypeScore = numericField(
                "genePhenotypeScore", "EXOMISER_GENE_PHENO_SCORE", variantRow, geneRow,
                missingFields, invalidFields, fieldSources
        );
        Double geneVariantScore = numericField(
                "geneVariantScore", "EXOMISER_GENE_VARIANT_SCORE", variantRow, geneRow,
                missingFields, invalidFields, fieldSources
        );
        String functionalClass = variantTextField(
                "functionalClass", "FUNCTIONAL_CLASS", variantRow, missingFields, fieldSources
        );
        String hgvs = variantTextField(
                "hgvs", "HGVS", variantRow, missingFields, fieldSources
        );
        Boolean contributingVariant = booleanField(
                "contributingVariant", "CONTRIBUTING_VARIANT", variantRow,
                missingFields, invalidFields, fieldSources
        );
        RankingScore rankingScore = "result.jsonl".equals(variantArtifactName)
                ? new RankingScore(geneCombinedScore,"JSONL:GeneScore.combinedScore")
                : rankingScore(variantRow, invalidFields, fieldSources);

        fieldSources.put("engineVersion", "CONFIG:EXOMISER_VERSION");
        fieldSources.put("variantSourceOrdinal", "VARIANT_TSV:ROW_ORDINAL");
        fieldSources.put("variantArtifactName", "OUTPUT_ARTIFACT:TSV_VARIANT");
        fieldSources.put("geneMoiGroupRankSemantics", "CONTRACT:RANK_SEMANTICS");
        if (geneRow != null) {
            fieldSources.put("geneSourceOrdinal", "GENE_TSV:ROW_ORDINAL");
        }
        if (geneArtifactName != null) {
            fieldSources.put("geneArtifactName", "OUTPUT_ARTIFACT:TSV_GENE");
        }
        if (geneMatch.isProblem()) {
            invalidFields.add("GENE_ROW_MATCH:" + geneMatch.strategy);
        }
        if (!"result.jsonl".equals(variantArtifactName)) addCrossArtifactConflicts(variantRow, geneRow, invalidFields);

        boolean jsonSource = "result.jsonl".equals(variantArtifactName);
        if (jsonSource) {
            fieldSources.replaceAll((key, source) -> source.replace("VARIANT_TSV:", "JSONL_NORMALIZED:"));
            fieldSources.put("geneMoiGroupRank", "GENE_TSV:#RANK");
            fieldSources.put("rankingScore", "JSONL:GeneScore.combinedScore; appended coverage order");
            fieldSources.put("variantScore", "JSONL:VariantEvaluation.variantScore:15.1.0_NON_DEFAULT");
            fieldSources.put("variantArtifactName", "OUTPUT_ARTIFACT:JSONL");
            fieldSources.put("variantSourceOrdinal", "DERIVED:APPEND_PUBLICATION_ORDINAL_NOT_TSV_ROW; see evidence jsonlLineOrdinal");
            for (String name : List.of("geneCombinedScore","genePhenotypeScore","geneVariantScore"))
                fieldSources.put(name,"JSONL:GeneScore:15.1.0_NON_DEFAULT; cross-checked GENE_TSV");
        }
        ExomiserCandidateFeatures candidateFeatures = new ExomiserCandidateFeatures(
                ExomiserCandidateFeatures.CURRENT_SCHEMA_VERSION,
                engineVersion,
                geneSymbol,
                entrezGeneId,
                rawMoi,
                normalizedMoi,
                geneMoiGroupRank,
                ExomiserCandidateFeatures.GENE_MOI_GROUP_RANK_SEMANTICS,
                sourceRecordId,
                variantScore,
                geneCombinedScore,
                genePhenotypeScore,
                geneVariantScore,
                functionalClass,
                hgvs,
                contributingVariant,
                variantArtifactName,
                geneArtifactName,
                variantSourceOrdinal,
                geneRow == null ? null : geneRow.sourceOrdinal,
                geneMatch.strategy,
                rankingScore.sourceColumn,
                new ArrayList<String>(missingFields),
                new ArrayList<String>(invalidFields),
                fieldSources
        );
        return new ParsedFeatures(candidateFeatures, rankingScore.score);
    }

    private String textField(String property,
                             String column,
                             Map<String, String> variantRow,
                             SourceRow geneRow,
                             Set<String> missingFields,
                             Map<String, String> fieldSources) {
        String variantValue = value(variantRow, column);
        if (variantValue != null) {
            fieldSources.put(property, "VARIANT_TSV:" + column);
            return variantValue;
        }
        String geneValue = geneRow == null ? null : value(geneRow.values, column);
        if (geneValue != null) {
            fieldSources.put(property, "GENE_TSV:" + column);
            return geneValue;
        }
        missingFields.add(column);
        return null;
    }

    private String variantTextField(String property,
                                    String column,
                                    Map<String, String> variantRow,
                                    Set<String> missingFields,
                                    Map<String, String> fieldSources) {
        String selected = value(variantRow, column);
        if (selected == null) {
            missingFields.add(column);
            return null;
        }
        fieldSources.put(property, "VARIANT_TSV:" + column);
        return selected;
    }

    private Double numericField(String property,
                                String column,
                                Map<String, String> variantRow,
                                SourceRow geneRow,
                                Set<String> missingFields,
                                Set<String> invalidFields,
                                Map<String, String> fieldSources) {
        String selected = value(variantRow, column);
        String source = "VARIANT_TSV:" + column;
        if (selected == null && geneRow != null) {
            selected = value(geneRow.values, column);
            source = "GENE_TSV:" + column;
        }
        if (selected == null) {
            missingFields.add(column);
            return null;
        }
        Double parsed = finiteNumber(selected);
        if (parsed == null) {
            invalidFields.add(column);
            return null;
        }
        fieldSources.put(property, source);
        return parsed;
    }

    private Boolean booleanField(String property,
                                 String column,
                                 Map<String, String> row,
                                 Set<String> missingFields,
                                 Set<String> invalidFields,
                                 Map<String, String> fieldSources) {
        String selected = value(row, column);
        if (selected == null) {
            missingFields.add(column);
            return null;
        }
        if ("true".equalsIgnoreCase(selected) || "1".equals(selected)) {
            fieldSources.put(property, "VARIANT_TSV:" + column);
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(selected) || "0".equals(selected)) {
            fieldSources.put(property, "VARIANT_TSV:" + column);
            return Boolean.FALSE;
        }
        invalidFields.add(column);
        return null;
    }

    private RankingScore rankingScore(Map<String, String> row,
                                      Set<String> invalidFields,
                                      Map<String, String> fieldSources) {
        for (String column : new String[] {
                "EXOMISER_VARIANT_SCORE", "VARIANT_SCORE", "COMBINED_SCORE", "SCORE"
        }) {
            String selected = value(row, column);
            if (selected == null) {
                continue;
            }
            Double score = finiteNumber(selected);
            if (score == null) {
                invalidFields.add(column);
                continue;
            }
            fieldSources.put("rankingScore", "VARIANT_TSV:" + column);
            return new RankingScore(score, column);
        }
        return new RankingScore(null, null);
    }

    private Double finiteNumber(String raw) {
        try {
            Double parsed = Double.valueOf(raw);
            return parsed.isNaN() || parsed.isInfinite() ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void addCrossArtifactConflicts(Map<String, String> variantRow,
                                           SourceRow geneRow,
                                           Set<String> invalidFields) {
        if (geneRow == null) {
            return;
        }
        addTextConflict(variantRow, geneRow.values, "GENE_SYMBOL", false, invalidFields);
        addTextConflict(variantRow, geneRow.values, "ENTREZ_GENE_ID", true, invalidFields);
        String variantMoi = moiJoinKey(value(variantRow, "MOI"));
        String geneMoi = moiJoinKey(value(geneRow.values, "MOI"));
        if (variantMoi != null && geneMoi != null && !variantMoi.equals(geneMoi)) {
            invalidFields.add("MOI:SOURCE_CONFLICT");
        }
        for (String score : new String[] {
                "EXOMISER_GENE_COMBINED_SCORE",
                "EXOMISER_GENE_PHENO_SCORE",
                "EXOMISER_GENE_VARIANT_SCORE"
        }) {
            String variantRaw = value(variantRow, score);
            String geneRaw = value(geneRow.values, score);
            Double variantValue = variantRaw == null ? null : finiteNumber(variantRaw);
            Double geneValue = geneRaw == null ? null : finiteNumber(geneRaw);
            if (variantValue != null && geneValue != null
                    && Double.compare(variantValue, geneValue) != 0) {
                invalidFields.add(score + ":SOURCE_CONFLICT");
            }
        }
    }

    private void addTextConflict(Map<String, String> variantRow,
                                 Map<String, String> geneRow,
                                 String column,
                                 boolean caseSensitive,
                                 Set<String> invalidFields) {
        String variantValue = value(variantRow, column);
        String geneValue = value(geneRow, column);
        if (variantValue == null || geneValue == null) {
            return;
        }
        boolean equal = caseSensitive
                ? variantValue.equals(geneValue)
                : variantValue.equalsIgnoreCase(geneValue);
        if (!equal) {
            invalidFields.add(column + ":SOURCE_CONFLICT");
        }
    }

    private String normalizeMoi(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (normalized) {
            case "AD":
                return "AUTOSOMAL_DOMINANT";
            case "AR":
                return "AUTOSOMAL_RECESSIVE";
            case "XD":
                return "X_DOMINANT";
            case "XR":
                return "X_RECESSIVE";
            case "MT":
                return "MITOCHONDRIAL";
            case "AUTOSOMAL_DOMINANT":
            case "AUTOSOMAL_RECESSIVE":
            case "X_DOMINANT":
            case "X_RECESSIVE":
            case "Y_LINKED":
            case "MITOCHONDRIAL":
                return normalized;
            default:
                return null;
        }
    }

    private String moiJoinKey(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = normalizeMoi(raw);
        return normalized == null
                ? raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_')
                : normalized;
    }

    private Integer integer(Map<String, String> row, String key) {
        String value = value(row, key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String required(Map<String, String> row, String key) {
        String value = value(row, key);
        if (value == null) {
            throw new IllegalArgumentException("TSV_FIELD_MISSING:" + key);
        }
        return value;
    }

    private String value(Map<String, String> row, String key) {
        String raw = row.get(key);
        if (raw == null) {
            return null;
        }
        String selected = raw.trim();
        if (selected.isEmpty() || ".".equals(selected)
                || "NA".equalsIgnoreCase(selected)
                || "N/A".equalsIgnoreCase(selected)
                || "NULL".equalsIgnoreCase(selected)) {
            return null;
        }
        return selected;
    }

    private final class GeneRowIndex {

        private final boolean artifactAvailable;
        private final Map<String, List<SourceRow>> rankEntrezMoi =
                new LinkedHashMap<String, List<SourceRow>>();
        private final Map<String, List<SourceRow>> rankSymbolMoi =
                new LinkedHashMap<String, List<SourceRow>>();
        private final Map<String, List<SourceRow>> rankEntrez =
                new LinkedHashMap<String, List<SourceRow>>();
        private final Map<String, List<SourceRow>> rankSymbol =
                new LinkedHashMap<String, List<SourceRow>>();

        private GeneRowIndex(boolean artifactAvailable) {
            this.artifactAvailable = artifactAvailable;
        }

        private void add(Map<String, String> row, int sourceOrdinal) {
            SourceRow sourceRow = new SourceRow(row, sourceOrdinal);
            String rank = value(row, "#RANK");
            String entrez = value(row, "ENTREZ_GENE_ID");
            String symbol = value(row, "GENE_SYMBOL");
            String moi = moiJoinKey(value(row, "MOI"));
            add(rankEntrezMoi, key(rank, entrez, moi), sourceRow);
            add(rankSymbolMoi, key(rank, symbol, moi), sourceRow);
            add(rankEntrez, key(rank, entrez), sourceRow);
            add(rankSymbol, key(rank, symbol), sourceRow);
        }

        private GeneRowMatch find(Map<String, String> variantRow) {
            if (!artifactAvailable) {
                return GeneRowMatch.unmatched("GENE_ARTIFACT_MISSING");
            }
            String rank = value(variantRow, "#RANK");
            String entrez = value(variantRow, "ENTREZ_GENE_ID");
            String symbol = value(variantRow, "GENE_SYMBOL");
            String moi = moiJoinKey(value(variantRow, "MOI"));
            if (rank == null) {
                return GeneRowMatch.unmatched("NO_MATCH_RANK_MISSING");
            }
            if (entrez != null && moi != null) {
                return rejectOverlappingIdentityConflicts(variantRow, exactOrConflict(
                        rankEntrezMoi.get(key(rank, entrez, moi)),
                        rankEntrez.get(key(rank, entrez)),
                        "RANK_ENTREZ_MOI"
                ));
            }
            if (entrez == null && symbol != null && moi != null) {
                return rejectOverlappingIdentityConflicts(variantRow, exactOrConflict(
                        rankSymbolMoi.get(key(rank, symbol, moi)),
                        rankSymbol.get(key(rank, symbol)),
                        "RANK_SYMBOL_MOI"
                ));
            }
            if (moi == null && entrez != null) {
                return rejectOverlappingIdentityConflicts(variantRow, uniqueOrStatus(
                        rankEntrez.get(key(rank, entrez)),
                        "RANK_ENTREZ_UNIQUE_MOI_MISSING"
                ));
            }
            if (moi == null && entrez == null && symbol != null) {
                return rejectOverlappingIdentityConflicts(variantRow, uniqueOrStatus(
                        rankSymbol.get(key(rank, symbol)),
                        "RANK_SYMBOL_UNIQUE_MOI_MISSING"
                ));
            }
            return GeneRowMatch.unmatched("NO_MATCH_GENE_IDENTITY_MISSING");
        }

        private GeneRowMatch rejectOverlappingIdentityConflicts(
                Map<String, String> variantRow,
                GeneRowMatch match) {
            if (match == null || match.row == null) {
                return match;
            }
            Map<String, String> geneRow = match.row.values;
            if (conflicts(value(variantRow, "GENE_SYMBOL"), value(geneRow, "GENE_SYMBOL"), true)) {
                return GeneRowMatch.unmatched("CONFLICT_" + match.strategy + "_GENE_SYMBOL");
            }
            if (conflicts(value(variantRow, "ENTREZ_GENE_ID"), value(geneRow, "ENTREZ_GENE_ID"), false)) {
                return GeneRowMatch.unmatched("CONFLICT_" + match.strategy + "_ENTREZ_GENE_ID");
            }
            if (conflicts(
                    moiJoinKey(value(variantRow, "MOI")),
                    moiJoinKey(value(geneRow, "MOI")),
                    false
            )) {
                return GeneRowMatch.unmatched("CONFLICT_" + match.strategy + "_MOI");
            }
            return match;
        }

        private boolean conflicts(String variantValue, String geneValue, boolean ignoreCase) {
            if (variantValue == null || geneValue == null) {
                return false;
            }
            return ignoreCase
                    ? !variantValue.equalsIgnoreCase(geneValue)
                    : !variantValue.equals(geneValue);
        }

        private GeneRowMatch exactOrConflict(List<SourceRow> exactMatches,
                                             List<SourceRow> weakerMatches,
                                             String strategy) {
            if (exactMatches != null && exactMatches.size() == 1) {
                return GeneRowMatch.matched(exactMatches.get(0), strategy);
            }
            if (exactMatches != null && exactMatches.size() > 1) {
                return GeneRowMatch.unmatched("AMBIGUOUS_" + strategy);
            }
            if (weakerMatches != null && !weakerMatches.isEmpty()) {
                return GeneRowMatch.unmatched("CONFLICT_" + strategy);
            }
            return GeneRowMatch.unmatched("NO_MATCH_" + strategy);
        }

        private GeneRowMatch uniqueOrStatus(List<SourceRow> matches, String strategy) {
            if (matches != null && matches.size() == 1) {
                return GeneRowMatch.matched(matches.get(0), strategy);
            }
            if (matches != null && matches.size() > 1) {
                return GeneRowMatch.unmatched("AMBIGUOUS_" + strategy);
            }
            return GeneRowMatch.unmatched("NO_MATCH_" + strategy);
        }

        private void add(Map<String, List<SourceRow>> values,
                         String candidateKey,
                         SourceRow row) {
            if (candidateKey == null) {
                return;
            }
            values.computeIfAbsent(
                    candidateKey,
                    ignored -> new ArrayList<SourceRow>()
            ).add(row);
        }

        private String key(String... fields) {
            StringBuilder candidateKey = new StringBuilder();
            for (String fieldValue : fields) {
                if (fieldValue == null) {
                    return null;
                }
                if (candidateKey.length() > 0) {
                    candidateKey.append('\u0000');
                }
                candidateKey.append(fieldValue);
            }
            return candidateKey.toString();
        }
    }

    private static final class SourceRow {
        private final Map<String, String> values;
        private final int sourceOrdinal;

        private SourceRow(Map<String, String> values, int sourceOrdinal) {
            this.values = values;
            this.sourceOrdinal = sourceOrdinal;
        }
    }

    private static final class GeneRowMatch {
        private final SourceRow row;
        private final String strategy;

        private GeneRowMatch(SourceRow row, String strategy) {
            this.row = row;
            this.strategy = strategy;
        }

        private static GeneRowMatch matched(SourceRow row, String strategy) {
            return new GeneRowMatch(row, strategy);
        }

        private static GeneRowMatch unmatched(String strategy) {
            return new GeneRowMatch(null, strategy);
        }

        private boolean isProblem() {
            return strategy != null
                    && (strategy.startsWith("AMBIGUOUS_") || strategy.startsWith("CONFLICT_"));
        }
    }

    private static final class RankingScore {
        private final Double score;
        private final String sourceColumn;

        private RankingScore(Double score, String sourceColumn) {
            this.score = score;
            this.sourceColumn = sourceColumn;
        }
    }

    private static final class ParsedFeatures {
        private final ExomiserCandidateFeatures features;
        private final Double rankingScore;

        private ParsedFeatures(ExomiserCandidateFeatures features, Double rankingScore) {
            this.features = features;
            this.rankingScore = rankingScore;
        }
    }

    /**
     * Local evidence providers route SNVs and indels to different reference datasets.
     * Engine provenance belongs in the EXOMISER evidence item, not in variantType.
     */
    private String variantType(String reference, String alternate) {
        if (reference == null || alternate == null || reference.isEmpty() || alternate.isEmpty()
                || alternate.startsWith("<") || alternate.indexOf('[') >= 0 || alternate.indexOf(']') >= 0) {
            return "OTHER";
        }
        if (reference.length() == 1 && alternate.length() == 1) {
            return "SNV";
        }
        if (reference.length() != alternate.length()) {
            return "INDEL";
        }
        return reference.length() > 1 ? "MNV" : "OTHER";
    }
}
