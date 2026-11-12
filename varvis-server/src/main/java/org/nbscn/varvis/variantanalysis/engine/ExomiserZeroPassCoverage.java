package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/** Pinned 15.1.0 NON_DEFAULT GeneScore semantics; never infers a score from rounded TSV alone. */
final class ExomiserZeroPassCoverage {
    static final String PARSER = "exomiser-zero-pass-parser-v1";
    static final String MANIFEST = "output-coverage-v1.json";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Map<String,String> MOI = Map.of("AD", "AUTOSOMAL_DOMINANT", "AR", "AUTOSOMAL_RECESSIVE",
            "XD", "X_DOMINANT", "XR", "X_RECESSIVE", "MT", "MITOCHONDRIAL", "ANY", "ANY");
    record GeneRow(Map<String,String> row, int ordinal) { }
    record Addition(Map<String,String> row, GeneRow gene, JsonNode variant, JsonNode score, int jsonLine) { }
    record Result(List<Addition> additions, ExomiserTsvScanner.ScanResult scan) { }

    static Result read(Path directory, ExomiserTsvScanner scanner, List<GeneRow> genes,
                       Set<String> existing, String version) throws java.io.IOException {
        require("15.1.0".equals(version), "VERSION_UNSUPPORTED");
        Map<String,GeneRow> index = new LinkedHashMap<>();
        for (var gene : genes) {
            var r = gene.row();
            String moi = MOI.get(required(r,"MOI"));
            require(moi != null, "MOI_INVALID");
            String key = required(r,"GENE_SYMBOL") + "|" + required(r,"ENTREZ_GENE_ID") + "|" + moi;
            require(index.putIfAbsent(key, gene) == null, "GENE_AMBIGUOUS");
        }
        Set<String> seenGenes = new HashSet<>(), seenScores = new HashSet<>(), seenCandidates = new HashSet<>();
        List<Addition> additions = new ArrayList<>();
        String[] failure = {null};
        ExomiserTsvScanner.ScanResult scan;
        try {
            scan = scanner.scan(directory.resolve("result.jsonl"), ExomiserTsvScanner.ArtifactKind.JSONL, (line, ordinal) -> {
                try {
                    JsonNode gene = JSON.readTree(line.get("JSONL"));
                    require(gene.isObject(), "JSON_SHAPE_INVALID");
                    JsonNode id = gene.path("geneIdentifier");
                    String symbol = text(id,"geneSymbol"), entrez = text(id,"entrezId");
                    require(text(gene,"geneSymbol").equals(symbol) && id.isObject() && id.hasNonNull("geneId"), "GENE_IDENTITY_CONFLICT");
                    require(seenGenes.add(symbol + "|" + entrez), "GENE_AMBIGUOUS");
                    require(gene.path("geneScores").isArray() && gene.path("variantEvaluations").isArray(), "JSON_SHAPE_INVALID");
                    Map<String,JsonNode> variants = new LinkedHashMap<>();
                    for (var v : gene.path("variantEvaluations")) require(variants.putIfAbsent(allele(v), v) == null, "ALLELE_AMBIGUOUS");
                    for (var score : gene.path("geneScores")) {
                        require(id.equals(score.path("geneIdentifier")), "GENE_IDENTITY_CONFLICT");
                        String moi = text(score,"modeOfInheritance");
                        String group = symbol + "|" + entrez + "|" + moi;
                        require(seenScores.add(group), "MOI_AMBIGUOUS");
                        GeneRow match = index.get(group);
                        if (match == null) continue; // Original gene output settings did not publish this MOI.
                        // These are primitive doubles in the pinned record; NON_DEFAULT omits exactly zero.
                        double combined = numberOrDefault(score,"combinedScore");
                        double phenotype = numberOrDefault(score,"phenotypeScore");
                        double variantScore = numberOrDefault(score,"variantScore");
                        require(roundedMatches(combined, required(match.row(),"EXOMISER_GENE_COMBINED_SCORE"))
                                && roundedMatches(phenotype, required(match.row(),"EXOMISER_GENE_PHENO_SCORE"))
                                && roundedMatches(variantScore, required(match.row(),"EXOMISER_GENE_VARIANT_SCORE"))
                                && roundedMatches(numberOrDefault(score,"pValue"),required(match.row(),"P-VALUE")), "SCORE_CONFLICT");
                        if (combined != 0.0) continue; // Including small nonzero values rendered 0.0000 in TSV.
                        require(!score.has("contributingVariants") || score.path("contributingVariants").isArray(), "CONTRIBUTION_UNPROVEN");
                        Set<String> contributors = new HashSet<>();
                        for (var contributor : score.path("contributingVariants")) {
                            String key = allele(contributor);
                            require(contributors.add(key), "ALLELE_AMBIGUOUS");
                            require(contributor.equals(variants.get(key)), "ALLELE_IDENTITY_CONFLICT");
                        }
                        // Actual adapter uses pinned analyse defaults: contributingVariantsOnly=false.
                        // Still prove each recorded contribution in both directions; never invent one.
                        for (var v : variants.values()) {
                            String allele = allele(v);
                            require(!v.has("genomeAssembly") || "GRCh38".equals(v.path("genomeAssembly").asText()), "ASSEMBLY_CONFLICT");
                            require(!v.has("geneSymbol") || symbol.equals(v.path("geneSymbol").asText()), "GENE_IDENTITY_CONFLICT");
                            require(!v.has("geneId") || id.path("geneId").equals(v.path("geneId")), "GENE_IDENTITY_CONFLICT");
                            if (!"PASSED".equals(v.path("filterStatus").asText())) continue;
                            if (!contains(v.path("compatibleInheritanceModes"),moi)) continue;
                            require(!v.has("contributesToGeneScore") || v.path("contributesToGeneScore").isBoolean(), "CONTRIBUTION_UNPROVEN");
                            require(!v.has("contributingInheritanceModes") || v.path("contributingInheritanceModes").isArray(), "CONTRIBUTION_UNPROVEN");
                            boolean contributing = contains(v.path("contributingInheritanceModes"),moi);
                            require(contributing == contributors.contains(allele), "CONTRIBUTION_CONFLICT");
                            require(v.path("contributesToGeneScore").asBoolean(false)
                                    == (v.path("contributingInheritanceModes").isArray() && !v.path("contributingInheritanceModes").isEmpty()),
                                    "CONTRIBUTION_CONFLICT");
                            require(v.path("passedFilterTypes").isArray() && !v.path("passedFilterTypes").isEmpty()
                                    && v.path("failedFilterTypes").isMissingNode(), "FILTER_STATE_CONFLICT");
                            Map<String,String> row = new LinkedHashMap<>();
                            row.put("#RANK", required(match.row(),"#RANK"));
                            require(Integer.parseInt(row.get("#RANK")) > 0, "RANK_INVALID");
                            row.put("GENE_SYMBOL",symbol); row.put("ENTREZ_GENE_ID",entrez); row.put("MOI",required(match.row(),"MOI"));
                            row.put("CONTIG",text(v,"contigName")); row.put("START",v.path("start").asText());
                            row.put("REF",text(v,"ref")); row.put("ALT",text(v,"alt"));
                            if (v.hasNonNull("id")) row.put("ID",text(v,"id"));
                            row.put("EXOMISER_VARIANT_SCORE",Double.toString(numberOrDefault(v,"variantScore")));
                            row.put("FILTER","PASS"); row.put("CONTRIBUTING_VARIANT",Boolean.toString(contributing));
                            row.put("EXOMISER_GENE_COMBINED_SCORE",Double.toString(combined));
                            row.put("EXOMISER_GENE_PHENO_SCORE",Double.toString(phenotype));
                            row.put("EXOMISER_GENE_VARIANT_SCORE",Double.toString(variantScore));
                            // Do not borrow ClinVar consequence/HGVS or call gene variantScore the allele score.
                            String key = key(row);
                            require(seenCandidates.add(key), "ALLELE_AMBIGUOUS");
                            if (!existing.contains(key)) additions.add(new Addition(row,match,v,score,ordinal));
                            require(additions.size() + existing.size() <= 50_000, "CANDIDATE_LIMIT");
                        }
                    }
                } catch (Exception invalid) {
                    failure[0] = invalid.getMessage() != null && invalid.getMessage().startsWith("EXOMISER_OUTPUT_COVERAGE_")
                            ? invalid.getMessage() : "EXOMISER_OUTPUT_COVERAGE_JSON_INVALID";
                    throw new IllegalArgumentException(failure[0]);
                }
            });
        } catch (ExomiserTsvScanner.ScanException invalid) {
            throw new IllegalArgumentException(failure[0] != null ? failure[0] : "EXOMISER_OUTPUT_COVERAGE_JSON_" + invalid.code());
        }
        for (var key : index.keySet()) require(seenScores.contains(key), "GENE_SOURCE_MISSING");
        additions.sort(Comparator.comparingInt((Addition a) -> Integer.parseInt(a.row().get("#RANK")))
                .thenComparing(a -> a.row().get("GENE_SYMBOL")).thenComparing(a -> a.row().get("MOI"))
                .thenComparing(a -> a.row().get("CONTIG")).thenComparingInt(a -> Integer.parseInt(a.row().get("START")))
                .thenComparing(a -> a.row().get("REF")).thenComparing(a -> a.row().get("ALT")));
        return new Result(List.copyOf(additions),scan);
    }
    static String key(Map<String,String> r) {
        return required(r,"CONTIG").replaceFirst("^chr", "") + ":" + required(r,"START") + ":" + required(r,"REF") + ":" + required(r,"ALT")
                + "|" + required(r,"GENE_SYMBOL") + "|" + required(r,"ENTREZ_GENE_ID") + "|" + required(r,"MOI");
    }
    private static String allele(JsonNode v) {
        require(v.isObject() && v.path("start").isIntegralNumber() && v.path("start").canConvertToInt()
                && v.path("start").intValue() > 0 && v.path("end").isIntegralNumber(), "ALLELE_INVALID");
        if ("SNV".equals(v.path("type").asText()))
            require(v.path("start").equals(v.path("end")) && text(v,"ref").matches("[ACGT]")
                    && text(v,"alt").matches("[ACGT]") && !text(v,"ref").equals(text(v,"alt")), "ALLELE_INVALID");
        return text(v,"contigName") + ":" + v.path("start") + ":" + v.path("end") + ":" + text(v,"ref") + ":" + text(v,"alt");
    }
    private static boolean contains(JsonNode array, String value) {
        if (!array.isArray()) return false;
        for (var item : array) if (item.isTextual() && item.textValue().equals(value)) return true;
        return false;
    }
    private static double numberOrDefault(JsonNode node, String name) {
        if (!node.has(name)) return 0.0;
        require(node.path(name).isNumber() && Double.isFinite(node.path(name).doubleValue()), "SCORE_INVALID");
        return node.path(name).doubleValue();
    }
    private static boolean roundedMatches(double value, String tsv) {
        // TsvGeneResultsWriter 15.1.0 formats the double itself (not BigDecimal.valueOf).
        // Pin UK symbols without changing the JVM locale; DecimalFormat is mutable, so keep it local.
        var format = new DecimalFormat("0.0000", DecimalFormatSymbols.getInstance(Locale.UK));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return new BigDecimal(format.format(value)).compareTo(new BigDecimal(tsv)) == 0;
    }
    private static String text(JsonNode n,String k) { require(n.path(k).isTextual() && !n.path(k).textValue().isBlank(), "JSON_SHAPE_INVALID"); return n.path(k).textValue(); }
    private static String required(Map<String,String> r,String k) { require(r.get(k) != null && !r.get(k).isBlank(), "GENE_FIELDS_MISSING"); return r.get(k); }
    private static void require(boolean ok,String code) { if (!ok) throw new IllegalArgumentException("EXOMISER_OUTPUT_COVERAGE_" + code); }
}
