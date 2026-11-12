import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.IndexFactory;
import htsjdk.variant.vcf.VCFCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Synthetic BGZF + real htsjdk Tabix indexing; no real ClinVar or scientific calls. */
public final class LocalClinVarPoolTest {
    static final String HEADER = "##fileformat=VCFv4.1\n##fileDate=20260321\n"
            + "##contig=<ID=1,length=1000000>\n"
            + "##INFO=<ID=GENEINFO,Number=.,Type=String,Description=\"Synthetic genes\">\n"
            + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
    static final List<String> ROWS = List.of(
            "1\t100\t10\tA\tG\t1\tPASS\tGENEINFO=GENE:1;CLNSIG=SECRET_PATHOGENIC;CLNDN=SECRET_DISEASE;CLNREVSTAT=SECRET_STARS;MC=SO:0001583|missense_variant;AF_TGP=0.01",
            "1\t100\t11\tA\tG\t99\tPASS\tGENEINFO=GENE:1;CLNSIGCONF=SECRET_CONFLICT;CLNSIGINCL=SECRET_INCLUDED",
            "1\t110\t12\tC\tT\t.\tPASS\tGENEINFO=GENE2:2",
            "1\t120\t13\tG\tA\t.\tPASS\tGENEINFO=GENE:1|OTHER:2",
            "1\t125\t14\tA\tC,G\t.\tPASS\tGENEINFO=GENE:1",
            "1\t126\t141\tA\tC,G\t.\tPASS\tGENEINFO=GENE:1|OTHER:2",
            "1\t140\t15\tA\tAT\t.\tPASS\tGENEINFO=GENE:1",
            "1\t145\t16\tAAAAAAAAAAAAAAAAAAAA\tA\t.\tPASS\tGENEINFO=GENE:1",
            "1\t160\t17\tAT\tA\t.\tPASS\tGENEINFO=GENE:1",
            "1\t170\t18\tAC\tGT\t.\tPASS\tGENEINFO=GENE:1",
            "1\t175\t19\tA\t<DEL>\t.\tPASS\tGENEINFO=GENE:1;END=180",
            "1\t176\t20\tA\tG\t.\tPASS\tGENEINFO=GENE:not_an_ncbi_id");
    static int assertions;
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    record Fixture(Path root, Path vcf, Path index, ObjectNode plan) { }
    static Fixture fixture(Path root, List<String> rows) throws Exception {
        return fixture(root, rows, HEADER);
    }
    static Fixture fixture(Path root, List<String> rows, String sourceHeader) throws Exception {
        Files.createDirectory(root);
        Path vcf = root.resolve("synthetic.vcf.gz");
        try (var out = new BlockCompressedOutputStream(vcf.toFile())) {
            out.write((sourceHeader + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        IndexFactory.createTabixIndex(vcf, new VCFCodec(), null).writeBasedOnFeaturePath(vcf);
        Path index = root.resolve("synthetic.vcf.gz.tbi");
        var p = LocalClinVarPool.JSON.createObjectNode().put("schemaVersion", "prospective-local-public-allele-query-plan-v1")
                .put("planId", "SYNTHETIC_ONLY").put("status", "INPUT_QUERY_PLAN_FIXED_BEFORE_EXTRACTION_AND_IMPLEMENTATION_OUTPUTS");
        p.putObject("operation").put("networkCalls", 0).put("providerCalls", 0).put("sourceMutation", false);
        p.putObject("frameBinding").put("requiredDistinctGenes", 1).put("requiredPublicAlleles", 10).put("noQuotaReduction", true);
        p.putObject("budgets").put("maximumWindowBases", 25000).put("maximumSingleRawLineUtf8Bytes", 1048576)
                .put("maximumRowsPerGene", 20000).put("maximumRawBytesPerGene", 33554432)
                .put("maximumTotalUniqueRawRows", 150000).put("maximumTotalRawUtf8Bytes", 134217728)
                .put("maximumTotalArtifactBytes", 268435456).put("noExternalTransfer", true);
        p.putArray("sources").addObject().put("sourceId", "SYNTHETIC_CLINVAR_SHAPED")
                .put("assembly", "GRCh38").put("format", "VCFv4.1 BGZF").putNull("wholeDataSha256")
                .put("dataBytes", Files.size(vcf)).put("indexBytes", Files.size(index)).put("indexSha256", LocalClinVarPool.fileSha(index))
                .put("headerUtf8Bytes", sourceHeader.getBytes(StandardCharsets.UTF_8).length)
                .put("headerSha256", LocalClinVarPool.sha(sourceHeader.getBytes(StandardCharsets.UTF_8))).put("headerFileDate", "2026-03-21");
        var gene = p.putArray("geneQueries").addObject().put("geneSymbol", "GENE").put("anchorPaddingBases", 1)
                .put("quota", 10).put("maximumUniqueRawRows", 20000).put("maximumRawUtf8Bytes", 33554432);
        gene.putObject("sourceGeneInterval").put("assembly", "GRCh38").put("chromosome", "1")
                .put("start", 101).put("end", 180).put("coordinateConvention", "1_BASED_INCLUSIVE");
        var windows = gene.putArray("queryWindows");
        windows.addObject().put("start", 100).put("end", 149).put("tabixRegion", "1:100-149");
        windows.addObject().put("start", 150).put("end", 181).put("tabixRegion", "1:150-181");
        p.putObject("totals").put("genes", 1).put("queryWindows", 2).put("queriedBasesIncludingPadding", 82).put("quota", 10);
        return new Fixture(root, vcf, index, p);
    }
    static ObjectNode run(Fixture f) throws Exception {
        Path plan = f.root().resolve("plan.json");
        byte[] bytes = LocalClinVarPool.JSON.writeValueAsBytes(f.plan());
        Files.write(plan, bytes);
        return LocalClinVarPool.extract(plan, LocalClinVarPool.sha(bytes), f.vcf(), f.index(), f.root().resolve("out"));
    }
    static void rejected(Fixture f, String expected) throws Exception {
        try { run(f); throw new AssertionError("Expected " + expected); }
        catch (IllegalArgumentException failure) { check(failure.getMessage().contains(expected), failure.toString()); }
    }
    static Fixture partitioned(Fixture fixture) {
        ObjectNode plan = fixture.plan();
        plan.put("schemaVersion", "prospective-local-public-allele-query-plan-v2");
        ((ObjectNode) plan.path("budgets")).put("maximumRowsPerGene", 80000).put("maximumRawBytesPerGene", 134217728)
                .put("maximumRowObservationsPerPartition", 20000).put("maximumRawUtf8BytesPerPartition", 33554432);
        ObjectNode gene = (ObjectNode) plan.at("/geneQueries/0");
        gene.put("maximumUniqueRawRows", 80000).put("maximumRawUtf8Bytes", 134217728);
        var parts = gene.putArray("queryPartitions");
        for (int i = 1; i <= 2; i++) parts.addObject().put("partitionId", "FIXED_WINDOW_" + i)
                .put("firstWindowOrdinal", i).put("lastWindowOrdinal", i).put("maximumRowObservations", 2).put("maximumRawUtf8Bytes", 33554432);
        ((ObjectNode) plan.path("totals")).put("queryPartitions", 2);
        return fixture;
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 1, "An explicit new private test directory is required");
        Path root = Path.of(args[0]); Files.createDirectory(root);
        var positive = fixture(root.resolve("positive"), ROWS);
        String originalData = LocalClinVarPool.fileSha(positive.vcf()), originalIndex = LocalClinVarPool.fileSha(positive.index());
        var complete = run(positive);
        check("COMPLETE".equals(complete.path("status").asText()) && complete.path("samplingAllowed").asBoolean(), "Complete pool");
        check(complete.path("uniqueRawRows").asInt() == ROWS.size(), "Only identical original lines deduplicated");
        check(complete.path("retainedRowObservations").asInt() > ROWS.size(), "Long REF crossing query boundary must retain multiple locators");
        String projection = Files.readString(positive.root().resolve("out/sampling-projection.jsonl"));
        String raw = Files.readString(positive.root().resolve("out/raw-pool.vcf"));
        String occurrences = Files.readString(positive.root().resolve("out/query-occurrences.jsonl"));
        check(!projection.contains("SECRET") && !projection.contains("CLNSIG") && !projection.contains("CLNDN")
                && !projection.contains("CLNREVSTAT") && !projection.contains("QUAL"), "Classification and QUAL not exposed");
        check(projection.contains(complete.path("sourceSnapshotIdentitySha256").asText()) && projection.contains("\"AF_TGP\":\"0.01\""),
                "Projection references exact source identity and retains source AF as observed text");
        check(raw.equals(HEADER + String.join("\n", ROWS) + "\n"), "Original complete rows preserved exactly");
        check(occurrences.contains("QUERY_GENE_NOT_EXACTLY_MATCHED") && occurrences.contains("MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED")
                && occurrences.contains("MULTIALLELIC_SOURCE_REVIEW_REQUIRED") && occurrences.contains("COMPLEX_REPLACEMENT_EXCLUDED")
                && occurrences.contains("GENEINFO_MISSING_OR_MALFORMED"), "Exclusions remain explicit");
        check(occurrences.contains("\"ncbiGeneId\":\"1\"") && !occurrences.contains("HGNC"), "NCBI identifiers never become HGNC");
        boolean combinedAmbiguity = false;
        for (String line : occurrences.lines().toList()) {
            var item = LocalClinVarPool.JSON.readTree(line);
            if (item != null && "MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED".equals(item.path("geneMembershipStatus").asText())
                    && "MULTIALLELIC_SOURCE_REVIEW_REQUIRED".equals(item.path("alleleEnvelopeStatus").asText())) combinedAmbiguity = true;
        }
        check(combinedAmbiguity, "Multiple-gene and multiple-allele reasons coexist rather than hide one another");
        check(complete.path("selectedAlleles").asInt(-1) == 0 && !complete.path("referenceNormalizationPerformed").asBoolean(true), "No scientific/sample-selection output");
        check(originalData.equals(LocalClinVarPool.fileSha(positive.vcf())) && originalIndex.equals(LocalClinVarPool.fileSha(positive.index())), "Source untouched");
        String unicodeRow = ROWS.get(0) + ";CLNDNINCL=SECRET\u2013UTF8";
        var unicode = fixture(root.resolve("unicode"), List.of(unicodeRow));
        var unicodeResult = run(unicode);
        check("COMPLETE".equals(unicodeResult.path("status").asText()), "Valid source UTF-8 is not an ASCII-only failure");
        check(Files.readString(unicode.root().resolve("out/raw-pool.vcf")).equals(HEADER + unicodeRow + "\n"), "Original indexed UTF-8 bytes preserved without double encoding");
        String maskedUnicode = Files.readString(unicode.root().resolve("out/sampling-projection.jsonl"));
        check(!maskedUnicode.contains("SECRET") && maskedUnicode.contains(LocalClinVarPool.sha(unicodeRow.getBytes(StandardCharsets.UTF_8))), "UTF-8 row SHA is original bytes and clinical text stays masked");
        try { LocalClinVarPool.decodeSourceLine(new byte[] {(byte) 0xc3, 0x28}); throw new AssertionError("Malformed UTF-8 must fail"); }
        catch (java.nio.charset.CharacterCodingException expected) { check(true, "No replacement-character decoding"); }
        try { LocalClinVarPool.originalLineBytes("\u2013"); throw new AssertionError("Changed library string contract must fail"); }
        catch (IllegalArgumentException expected) { check(true, "No silent interpretation after library decoding changes"); }
        List<String> partitionRows = List.of(ROWS.get(0), ROWS.get(8));
        var partitioned = partitioned(fixture(root.resolve("partitioned"), partitionRows));
        var partitionResult = run(partitioned);
        check("COMPLETE".equals(partitionResult.path("status").asText()) && partitionResult.path("samplingAllowed").asBoolean(), "Every full partition completed before pool use");
        check(partitionResult.at("/genes/0/retainedRowObservations").asInt() == 2
                && partitionResult.at("/genes/0/partitions/0/retainedRowObservations").asInt() == 1
                && partitionResult.at("/genes/0/partitions/1/retainedRowObservations").asInt() == 1, "Partition counters reset, gene/global counters do not");
        check(partitionResult.at("/plannedTotals/quota").asInt() == 10 && partitionResult.path("selectedAlleles").asInt(-1) == 0, "Partitions do not duplicate quota or select records");
        for (String cap : List.of("rows", "bytes", "global")) {
            var f = partitioned(fixture(root.resolve("partitionCap-" + cap), partitionRows));
            if (cap.equals("rows")) ((ObjectNode) f.plan().at("/geneQueries/0/queryPartitions/0")).put("maximumRowObservations", 1);
            else if (cap.equals("bytes")) ((ObjectNode) f.plan().at("/geneQueries/0/queryPartitions/0")).put("maximumRawUtf8Bytes", 30);
            else ((ObjectNode) f.plan().path("budgets")).put("maximumTotalUniqueRawRows", 2);
            ObjectNode result = run(f);
            check("CAPPED".equals(result.path("status").asText()) && !result.path("samplingAllowed").asBoolean(), "No partial partition union may be used: " + cap);
        }
        var overlapPartition = partitioned(fixture(root.resolve("partitionOverlap"), partitionRows));
        ((ObjectNode) overlapPartition.plan().at("/geneQueries/0/queryPartitions/1")).put("firstWindowOrdinal", 1);
        rejected(overlapPartition, "PARTITION_WINDOW_COVERAGE_INVALID");
        var missingPartition = partitioned(fixture(root.resolve("partitionMissing"), partitionRows));
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingPartition.plan().at("/geneQueries/0/queryPartitions")).remove(1);
        rejected(missingPartition, "PARTITION_WINDOW_COVERAGE_INVALID");
        var duplicatePartition = partitioned(fixture(root.resolve("partitionDuplicate"), partitionRows));
        ((ObjectNode) duplicatePartition.plan().at("/geneQueries/0/queryPartitions/1")).put("partitionId", "FIXED_WINDOW_1");
        rejected(duplicatePartition, "PARTITION_ID_INVALID");
        var filteredPartition = partitioned(fixture(root.resolve("partitionFilter"), partitionRows));
        ((ObjectNode) filteredPartition.plan().at("/geneQueries/0/queryPartitions/0")).put("clinicalSignificance", "Pathogenic");
        rejected(filteredPartition, "PARTITION_FIELDS_INVALID");
        var implicitPartition = partitioned(fixture(root.resolve("partitionVersion"), partitionRows));
        implicitPartition.plan().put("schemaVersion", "prospective-local-public-allele-query-plan-v1");
        rejected(implicitPartition, "UNKNOWN_BUDGET_CONDITION");
        for (String cap : List.of("geneRows", "totalRows", "geneBytes", "totalBytes", "singleLine")) {
            var f = fixture(root.resolve(cap), ROWS);
            var budget = (ObjectNode) f.plan().path("budgets"); var gene = (ObjectNode) f.plan().at("/geneQueries/0");
            switch (cap) {
                case "geneRows" -> { budget.put("maximumRowsPerGene", 2); gene.put("maximumUniqueRawRows", 2); }
                case "totalRows" -> budget.put("maximumTotalUniqueRawRows", 2);
                case "geneBytes" -> { budget.put("maximumRawBytesPerGene", 30); gene.put("maximumRawUtf8Bytes", 30); }
                case "totalBytes" -> budget.put("maximumTotalRawUtf8Bytes", 30);
                case "singleLine" -> budget.put("maximumSingleRawLineUtf8Bytes", 30);
                default -> throw new AssertionError();
            }
            var result = run(f);
            check("CAPPED".equals(result.path("status").asText()) && !result.path("samplingAllowed").asBoolean(), "Cap cannot produce usable first-N sample: " + cap);
            check(Files.exists(f.root().resolve("out/manifest.json")), "Cap manifest retained");
        }
        var artifactCap = fixture(root.resolve("artifactCap"), List.of(ROWS.get(0) + ";CLNDNINCL=" + "X".repeat(50000)));
        ((ObjectNode) artifactCap.plan().path("budgets")).put("maximumTotalArtifactBytes", 300000);
        check("CAPPED".equals(run(artifactCap).path("status").asText()), "Artifact cap before writes");
        try (var files = Files.list(artifactCap.root().resolve("out"))) {
            check(files.mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { throw new IllegalStateException(e); } }).sum() <= 300000,
                    "All retained artifacts plus manifest within cap");
        }
        var badHeader = fixture(root.resolve("badHeader"), ROWS);
        ((ObjectNode) badHeader.plan().at("/sources/0")).put("headerSha256", "a".repeat(64)); rejected(badHeader, "HEADER_SHA_MISMATCH");
        var hyphenDate = fixture(root.resolve("hyphenDate"), ROWS, HEADER.replace("20260321", "2026-03-21"));
        check("COMPLETE".equals(run(hyphenDate).path("status").asText()), "Original ClinVar hyphen date format is accepted without changing header bytes");
        var wrongDate = fixture(root.resolve("wrongDate"), ROWS, HEADER.replace("20260321", "2026-03-22"));
        rejected(wrongDate, "HEADER_DATE_MISMATCH");
        var invalidDate = fixture(root.resolve("invalidDate"), ROWS, HEADER.replace("20260321", "2026-02-30"));
        rejected(invalidDate, "HEADER_DATE_INVALID");
        var badIndex = fixture(root.resolve("badIndex"), ROWS);
        ((ObjectNode) badIndex.plan().at("/sources/0")).put("indexSha256", "b".repeat(64)); rejected(badIndex, "INDEX_SHA_MISMATCH");
        var badWindow = fixture(root.resolve("badWindow"), ROWS);
        ((ObjectNode) badWindow.plan().at("/geneQueries/0/queryWindows/1")).put("start", 149); rejected(badWindow, "WINDOW_ORDER");
        var unknownBudget = fixture(root.resolve("unknownBudget"), ROWS);
        ((ObjectNode) unknownBudget.plan().path("budgets")).put("maximumPerWindowRows", 1); rejected(unknownBudget, "UNKNOWN_BUDGET_CONDITION");
        var unknownWindow = fixture(root.resolve("unknownWindow"), ROWS);
        ((ObjectNode) unknownWindow.plan().at("/geneQueries/0/queryWindows/0")).put("requireExonOverlap", true); rejected(unknownWindow, "UNKNOWN_WINDOW_CONDITION");
        var secondGene = fixture(root.resolve("secondGene"), ROWS);
        ((ObjectNode) secondGene.plan().at("/geneQueries/0")).put("geneSymbol", "GENE2");
        check("COMPLETE".equals(run(secondGene).path("status").asText()), "Same extractor supports a different exact gene without a whitelist");
        rejected(positive, "NEW_OUTPUT_DIRECTORY_REQUIRED");
        check(originalData.equals(LocalClinVarPool.fileSha(positive.vcf())), "Existing output rejection preserves source");
        var corrupt = fixture(root.resolve("corruptIndex"), ROWS);
        byte[] indexBytes = new byte[(int) Files.size(corrupt.index())]; Files.write(corrupt.index(), indexBytes);
        ((ObjectNode) corrupt.plan().at("/sources/0")).put("indexSha256", LocalClinVarPool.sha(indexBytes));
        var failed = run(corrupt);
        check("FAILED".equals(failed.path("status").asText()) && !failed.path("samplingAllowed").asBoolean(), "Query initialization failure is structured");
        check(Files.exists(corrupt.root().resolve("out/manifest.json")), "Failure artifacts retained");
        System.out.println("Synthetic indexed ClinVar-shaped extraction assertions passed: " + assertions);
    }
}
