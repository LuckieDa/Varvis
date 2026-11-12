import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.util.BlockCompressedInputStream;
import htsjdk.tribble.readers.TabixReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline identity-pool extraction only: no selection, normalization or evidence evaluation. */
public final class LocalClinVarPool {
    static final String VERSION = "1.1.0";
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final Set<String> INFO = Set.of("GENEINFO", "MC", "CLNHGVS", "ALLELEID", "RS", "AF_ESP", "AF_EXAC", "AF_TGP");
    static final long MANIFEST_RESERVE = 262144;

    private LocalClinVarPool() { }
    static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }
    static long number(JsonNode node, String field, long maximum) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0 && value.asLong() <= maximum,
                "INVALID_LIMIT_OR_COORDINATE:" + field);
        return value.asLong();
    }
    static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String fileSha(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = new byte[65536];
                int size;
                while ((size = input.read(bytes)) >= 0) digest.update(bytes, 0, size);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static Path localFile(Path file) throws IOException {
        require(!file.toString().startsWith("\\\\") && !file.toString().startsWith("//"), "NETWORK_PATH_FORBIDDEN");
        Path resolved = file.toRealPath();
        require(Files.isRegularFile(resolved) && !resolved.toString().startsWith("\\\\"), "LOCAL_REGULAR_FILE_REQUIRED");
        return resolved;
    }
    static byte[] header(Path file, long expectedBytes) throws IOException {
        require(expectedBytes > 0 && expectedBytes <= 1048576, "HEADER_SIZE_INVALID");
        var bytes = new ByteArrayOutputStream();
        var line = new ByteArrayOutputStream();
        try (var stream = new BlockCompressedInputStream(file.toFile())) {
            while (bytes.size() < expectedBytes) {
                int next = stream.read();
                require(next >= 0, "HEADER_TRUNCATED");
                bytes.write(next); line.write(next);
                if (next == '\n') {
                    String text = line.toString(StandardCharsets.UTF_8);
                    require(text.startsWith("#"), "HEADER_ENDED_WITHOUT_COLUMNS");
                    if (text.startsWith("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO")) {
                        require(bytes.size() == expectedBytes, "HEADER_SIZE_MISMATCH");
                        return bytes.toByteArray();
                    }
                    line.reset();
                }
            }
        }
        throw new IllegalArgumentException("HEADER_COLUMNS_OR_TERMINATING_LF_MISSING");
    }
    static void verifyHeaderDate(String header, String expected) {
        List<String> dates = header.lines().filter(line -> line.startsWith("##fileDate=")).toList();
        require(dates.size() == 1, "HEADER_DATE_MISSING_OR_DUPLICATE");
        String value = dates.get(0).substring("##fileDate=".length());
        require(value.matches("[0-9]{8}|[0-9]{4}-[0-9]{2}-[0-9]{2}"), "HEADER_DATE_FORMAT_UNSUPPORTED");
        try {
            var actual = java.time.LocalDate.parse(value, value.length() == 8
                    ? java.time.format.DateTimeFormatter.BASIC_ISO_DATE : java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            require(actual.equals(java.time.LocalDate.parse(expected)), "HEADER_DATE_MISMATCH");
        } catch (java.time.format.DateTimeParseException invalid) { throw new IllegalArgumentException("HEADER_DATE_INVALID", invalid); }
    }

    record Limits(long line, long geneRows, long geneBytes, long totalRows, long totalBytes, long artifacts,
                  boolean partitioned, long partitionRows, long partitionBytes) { }
    static Limits validatePlan(JsonNode plan) {
        boolean partitioned = "prospective-local-public-allele-query-plan-v2".equals(plan.path("schemaVersion").asText());
        require((partitioned || "prospective-local-public-allele-query-plan-v1".equals(plan.path("schemaVersion").asText()))
                && "INPUT_QUERY_PLAN_FIXED_BEFORE_EXTRACTION_AND_IMPLEMENTATION_OUTPUTS".equals(plan.path("status").asText())
                && plan.path("planId").isTextual() && !plan.path("planId").asText().isBlank(), "PLAN_IDENTITY_INVALID");
        require(plan.at("/operation/networkCalls").asInt(-1) == 0 && plan.at("/operation/providerCalls").asInt(-1) == 0
                && plan.at("/operation/sourceMutation").isBoolean() && !plan.at("/operation/sourceMutation").asBoolean()
                && plan.at("/budgets/noExternalTransfer").asBoolean(), "PLAN_OPERATION_UNSUPPORTED");
        JsonNode budget = plan.path("budgets");
        Set<String> budgetFields = new HashSet<>(Set.of("maximumWindowBases", "maximumSingleRawLineUtf8Bytes", "maximumRowsPerGene",
                "maximumRawBytesPerGene", "maximumTotalUniqueRawRows", "maximumTotalRawUtf8Bytes", "maximumTotalArtifactBytes",
                "capHandling", "noExternalTransfer"));
        if (partitioned) budgetFields.addAll(Set.of("maximumRowObservationsPerPartition", "maximumRawUtf8BytesPerPartition"));
        budget.fieldNames().forEachRemaining(name -> require(budgetFields.contains(name), "UNKNOWN_BUDGET_CONDITION"));
        long windowMax = number(budget, "maximumWindowBases", 25000);
        var limits = new Limits(number(budget, "maximumSingleRawLineUtf8Bytes", 1048576),
                number(budget, "maximumRowsPerGene", partitioned ? 80000 : 20000), number(budget, "maximumRawBytesPerGene", partitioned ? 134217728 : 33554432),
                number(budget, "maximumTotalUniqueRawRows", 150000), number(budget, "maximumTotalRawUtf8Bytes", 134217728),
                number(budget, "maximumTotalArtifactBytes", 268435456), partitioned,
                partitioned ? number(budget, "maximumRowObservationsPerPartition", 20000) : 20000,
                partitioned ? number(budget, "maximumRawUtf8BytesPerPartition", 33554432) : 33554432);
        require(limits.artifacts() > MANIFEST_RESERVE + 4096, "ARTIFACT_MANIFEST_RESERVE_MISSING");
        JsonNode genes = plan.path("geneQueries");
        require(genes.isArray() && !genes.isEmpty() && genes.size() <= 100, "GENE_QUERY_SET_INVALID");
        long windows = 0, bases = 0, quota = 0, partitionCount = 0;
        Set<String> names = new HashSet<>();
        for (JsonNode gene : genes) {
            require(gene.path("geneSymbol").asText().matches("[A-Za-z0-9_-]+") && names.add(gene.path("geneSymbol").asText()),
                    "GENE_SYMBOL_MISSING_OR_DUPLICATE");
            JsonNode interval = gene.path("sourceGeneInterval");
            require("GRCh38".equals(interval.path("assembly").asText())
                    && "1_BASED_INCLUSIVE".equals(interval.path("coordinateConvention").asText())
                    && interval.path("chromosome").asText().matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)"), "INTERVAL_IDENTITY_INVALID");
            long start = number(interval, "start", Integer.MAX_VALUE), end = number(interval, "end", Integer.MAX_VALUE);
            long pad = number(gene, "anchorPaddingBases", 1000);
            require(end >= start && gene.path("queryWindows").isArray() && !gene.path("queryWindows").isEmpty(), "WINDOWS_MISSING");
            long cursor = Math.max(1, start - pad);
            for (JsonNode window : gene.path("queryWindows")) {
                require(window.isObject() && window.size() == 3 && window.has("start") && window.has("end") && window.has("tabixRegion"),
                        "UNKNOWN_WINDOW_CONDITION");
                long lo = number(window, "start", Integer.MAX_VALUE), hi = number(window, "end", Integer.MAX_VALUE);
                require(lo == cursor && hi >= lo && hi - lo + 1 <= windowMax && hi <= end + pad
                        && window.path("tabixRegion").asText().equals(interval.path("chromosome").asText() + ":" + lo + "-" + hi),
                        "WINDOW_ORDER_COVERAGE_OR_BOUND_INVALID");
                cursor = hi + 1; windows++; bases += hi - lo + 1;
            }
            require(cursor == end + pad + 1, "GENE_WINDOW_COVERAGE_INCOMPLETE");
            number(gene, "maximumUniqueRawRows", limits.geneRows());
            number(gene, "maximumRawUtf8Bytes", limits.geneBytes());
            partitionCount += partitions(gene, limits).size();
            quota += number(gene, "quota", 1000);
        }
        if (partitioned) require(plan.at("/totals/queryPartitions").asLong() == partitionCount, "PARTITION_TOTAL_MISMATCH");
        require(windows <= 1000 && plan.at("/totals/genes").asLong() == genes.size()
                && plan.at("/totals/queryWindows").asLong() == windows && plan.at("/totals/queriedBasesIncludingPadding").asLong() == bases
                && plan.at("/totals/quota").asLong() == quota
                && plan.at("/frameBinding/requiredDistinctGenes").asLong() == genes.size()
                && plan.at("/frameBinding/requiredPublicAlleles").asLong() == quota && plan.at("/frameBinding/noQuotaReduction").asBoolean(),
                "PLAN_TOTALS_OR_FRAME_MISMATCH");
        return limits;
    }

    static ArrayNode partitions(JsonNode gene, Limits limits) {
        if (!limits.partitioned()) {
            require(!gene.has("queryPartitions"), "PARTITIONED_PLAN_VERSION_REQUIRED");
            ArrayNode result = JSON.createArrayNode();
            result.addObject().put("partitionId", "LEGACY_COMPLETE_GENE").put("firstWindowOrdinal", 1)
                    .put("lastWindowOrdinal", gene.path("queryWindows").size())
                    .put("maximumRowObservations", gene.path("maximumUniqueRawRows").asLong())
                    .put("maximumRawUtf8Bytes", gene.path("maximumRawUtf8Bytes").asLong());
            return result;
        }
        require(gene.path("queryPartitions") instanceof ArrayNode, "PARTITIONS_REQUIRED");
        ArrayNode result = (ArrayNode) gene.path("queryPartitions");
        require(!result.isEmpty(), "PARTITIONS_REQUIRED");
        int cursor = 1, windowCount = gene.path("queryWindows").size();
        Set<String> ids = new HashSet<>();
        Set<String> fields = Set.of("partitionId", "firstWindowOrdinal", "lastWindowOrdinal", "maximumRowObservations", "maximumRawUtf8Bytes");
        for (JsonNode part : result) {
            require(part.isObject() && part.size() == fields.size(), "PARTITION_FIELDS_INVALID");
            part.fieldNames().forEachRemaining(name -> require(fields.contains(name), "PARTITION_FIELDS_INVALID"));
            require(part.path("partitionId").isTextual() && !part.path("partitionId").asText().isBlank()
                    && ids.add(part.path("partitionId").asText()), "PARTITION_ID_INVALID");
            long first = number(part, "firstWindowOrdinal", windowCount), last = number(part, "lastWindowOrdinal", windowCount);
            require(first == cursor && last >= first, "PARTITION_WINDOW_COVERAGE_INVALID");
            cursor = (int) last + 1;
            number(part, "maximumRowObservations", limits.partitionRows());
            number(part, "maximumRawUtf8Bytes", limits.partitionBytes());
        }
        require(cursor == windowCount + 1, "PARTITION_WINDOW_COVERAGE_INVALID");
        return result;
    }

    // htsjdk 2.24.1 TabixReader.readLine casts each source byte directly to a char.
    // Recover those bytes before UTF-8 decoding or hashing; getBytes(UTF_8) on its
    // returned String would silently double-encode non-ASCII source text.
    static byte[] originalLineBytes(String tabixLine) {
        require(tabixLine.chars().allMatch(c -> c <= 255), "TABIX_BYTE_CHARACTER_CONTRACT_CHANGED");
        return tabixLine.getBytes(StandardCharsets.ISO_8859_1);
    }
    static String decodeSourceLine(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    static ObjectNode projection(String line, String hash, String snapshot) {
        require(line.codePoints().allMatch(c -> !Character.isISOControl(c) || c == '\t'), "SOURCE_LINE_CONTROL_CHARACTER_UNSUPPORTED");
        String[] fields = line.split("\t", -1);
        require(fields.length >= 8 && fields[1].matches("[1-9][0-9]*"), "MALFORMED_VCF_ROW");
        long position = Long.parseLong(fields[1]);
        require(position <= Integer.MAX_VALUE, "VCF_POSITION_OUT_OF_RANGE");
        var value = JSON.createObjectNode().put("CHROM", fields[0]).put("POS", position).put("ID", fields[2])
                .put("REF", fields[3]).put("ALT", fields[4]).put("FILTER", fields[6])
                .put("sourceRawLineSha256", hash).put("sourceSnapshotIdentity", snapshot);
        Set<String> seen = new HashSet<>();
        for (String part : fields[7].split(";", -1)) {
            if (part.equals(".") || part.isEmpty()) continue;
            String[] pair = part.split("=", 2);
            require(seen.add(pair[0]), "DUPLICATE_INFO_KEY");
            if (INFO.contains(pair[0])) {
                require(pair.length == 2, "PROJECTED_INFO_VALUE_MISSING");
                value.put(pair[0], pair[1]);
            }
        }
        // Deliberate allowlist: no arbitrary INFO keys, QUAL, phenotype, clinical assertions or stars.
        return value;
    }
    static ObjectNode membership(ObjectNode row, String symbol) {
        var value = JSON.createObjectNode();
        var pairs = value.putArray("sourceGeneInfoPairs");
        Set<String> unique = new HashSet<>();
        boolean valid = row.has("GENEINFO"), matches = false;
        for (String token : row.path("GENEINFO").asText().split("\\|", -1)) {
            String[] pair = token.split(":", -1);
            if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]+") || !pair[1].matches("[1-9][0-9]*") || !unique.add(token)) {
                valid = false; continue;
            }
            pairs.addObject().put("symbol", pair[0]).put("ncbiGeneId", pair[1]);
            matches |= pair[0].equals(symbol);
        }
        String geneStatus = !valid ? "GENEINFO_MISSING_OR_MALFORMED" : !matches ? "QUERY_GENE_NOT_EXACTLY_MATCHED"
                : pairs.size() != 1 ? "MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED" : "EXACT_SINGLE_GENE_MATCH";
        String alleleStatus = envelope(row);
        String status = "EXACT_SINGLE_GENE_MATCH".equals(geneStatus) ? alleleStatus : geneStatus;
        return value.put("geneMembershipStatus", geneStatus).put("alleleEnvelopeStatus", alleleStatus)
                .put("membershipStatus", status).put("samplingEligibility", status.equals("ELIGIBLE_IDENTITY_ONLY")
                ? "PENDING_REFERENCE_NORMALIZATION_AND_INDEPENDENT_STRATUM" : "EXCLUDED_OR_SOURCE_REVIEW_REQUIRED");
    }
    static String envelope(ObjectNode row) {
        String ref = row.path("REF").asText(), alt = row.path("ALT").asText();
        if (alt.contains(",")) return "MULTIALLELIC_SOURCE_REVIEW_REQUIRED";
        if (!ref.matches("[ACGT]{1,50}") || !alt.matches("[ACGT]{1,50}") || ref.equals(alt)) return "ALLELE_ENVELOPE_EXCLUDED";
        if (!(ref.length() == 1 && alt.length() == 1 || ref.length() == 1 && alt.startsWith(ref)
                || alt.length() == 1 && ref.startsWith(alt))) return "COMPLEX_REPLACEMENT_EXCLUDED";
        if (!row.path("CHROM").asText().matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)")) return "NONPRIMARY_CONTIG_EXCLUDED";
        return "ELIGIBLE_IDENTITY_ONLY";
    }
    static final class Stop extends IOException {
        private static final long serialVersionUID = 1L;
        final boolean global;
        Stop(String code, boolean global) { super(code); this.global = global; }
    }
    static final class Artifacts implements AutoCloseable {
        final Map<String, OutputStream> outputs = new LinkedHashMap<>();
        final long cap;
        long bytes;
        Artifacts(Path root, long maximum) throws IOException {
            cap = maximum - MANIFEST_RESERVE;
            try {
                for (String name : List.of("raw-pool.vcf", "sampling-projection.jsonl", "query-occurrences.jsonl"))
                    outputs.put(name, Files.newOutputStream(root.resolve(name), StandardOpenOption.CREATE_NEW));
            } catch (IOException failure) { close(); throw failure; }
        }
        void write(Map<String, byte[]> parts) throws IOException {
            long amount = parts.values().stream().mapToLong(p -> p.length).sum();
            if (amount > cap - bytes) throw new Stop("TOTAL_ARTIFACT_BYTES_CAPPED", true);
            for (var part : parts.entrySet()) {
                OutputStream output = outputs.get(part.getKey());
                if (output == null) throw new IllegalArgumentException("UNKNOWN_ARTIFACT");
                output.write(part.getValue()); bytes += part.getValue().length;
            }
        }
        public void close() throws IOException {
            IOException failure = null;
            for (var stream : outputs.values()) try { stream.close(); } catch (IOException e) { failure = e; }
            if (failure != null) throw failure;
        }
    }
    static byte[] jsonLine(JsonNode node) throws IOException {
        return (JSON.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static ObjectNode extract(Path planPath, String expectedPlanSha, Path dataPath, Path indexPath, Path outputPath) throws IOException {
        Path planFile = localFile(planPath), dataFile = localFile(dataPath), indexFile = localFile(indexPath);
        require(Files.size(planFile) <= 1048576 && expectedPlanSha.matches("[0-9a-f]{64}"), "PLAN_HASH_OR_SIZE_INVALID");
        byte[] planBytes = Files.readAllBytes(planFile);
        require(sha(planBytes).equals(expectedPlanSha), "PLAN_SHA_MISMATCH");
        JsonNode plan = JSON.readTree(planBytes);
        if (plan == null) throw new IllegalArgumentException("PLAN_MISSING");
        Limits limits = validatePlan(plan);
        JsonNode source = plan.at("/sources/0");
        long dataModified = Files.getLastModifiedTime(dataFile).toMillis(), indexModified = Files.getLastModifiedTime(indexFile).toMillis();
        require("GRCh38".equals(source.path("assembly").asText()) && source.path("wholeDataSha256").isNull()
                && source.path("sourceId").isTextual() && source.path("format").asText().startsWith("VCFv4.1 BGZF"), "SOURCE_IDENTITY_UNSUPPORTED");
        require(Files.size(dataFile) == number(source, "dataBytes", Long.MAX_VALUE)
                && Files.size(indexFile) == number(source, "indexBytes", 16777216), "SOURCE_SIZE_MISMATCH");
        require(fileSha(indexFile).equals(source.path("indexSha256").asText()), "INDEX_SHA_MISMATCH");
        byte[] header = header(dataFile, number(source, "headerUtf8Bytes", 1048576));
        require(sha(header).equals(source.path("headerSha256").asText()), "HEADER_SHA_MISMATCH");
        String headerText = new String(header, StandardCharsets.UTF_8);
        require(headerText.startsWith("##fileformat=VCFv4.1\n"), "HEADER_FORMAT_MISMATCH");
        verifyHeaderDate(headerText, source.path("headerFileDate").asText());
        require(!outputPath.toString().startsWith("\\\\") && !outputPath.toString().startsWith("//"), "NETWORK_OUTPUT_FORBIDDEN");
        Path absolute = outputPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || Files.exists(absolute)) throw new IllegalArgumentException("NEW_OUTPUT_DIRECTORY_REQUIRED");
        Path root = parent.toRealPath().resolve(absolute.getFileName());
        require(!root.toString().startsWith("\\\\") && !dataFile.startsWith(root) && !indexFile.startsWith(root)
                && !planFile.startsWith(root), "OUTPUT_INPUT_PATH_OVERLAP");
        Files.createDirectory(root);
        Files.writeString(root.resolve("RUNNING.txt"), "No COMPLETE manifest means unusable/incomplete.\n", StandardOpenOption.CREATE_NEW);
        var manifest = JSON.createObjectNode().put("schemaVersion", "local-public-allele-pool-manifest-v1")
                .put("extractorVersion", VERSION).put("planSha256", expectedPlanSha).put("startedAt", Instant.now().toString())
                .put("status", "RUNNING").put("sourceIdentityVerified", true).put("wholeDataChecksumComputed", false)
                .put("networkCalls", 0).put("providerCalls", 0).put("criterionEvaluations", 0).put("selectedAlleles", 0)
                .put("referenceNormalizationPerformed", false).put("classificationFieldsExposedToSampler", false);
        manifest.set("sourceSnapshotIdentity", source.deepCopy()); manifest.set("budgets", plan.path("budgets").deepCopy());
        String snapshot = sha(JSON.writeValueAsBytes(source));
        manifest.put("sourceSnapshotIdentitySha256", snapshot).put("sourceRawLineHashConvention", "SHA256_ORIGINAL_UTF8_BYTES_EXCLUDING_LINE_TERMINATOR")
                .put("sourceLineDecoding", "HTSJDK_2_24_1_BYTE_CHAR_RECOVERY_THEN_STRICT_UTF8");
        manifest.set("frameBinding", plan.path("frameBinding").deepCopy()); manifest.set("plannedTotals", plan.path("totals").deepCopy());
        manifest.put("readBoundary", "TabixReader materializes a line before validation; limits bound retained UTF-8 payloads. Returned observations including duplicates are separately bounded with the same conservative row/byte ceilings. No compressed I/O or heap bound is claimed.");
        var genes = manifest.putArray("genes");
        Set<String> globalUnique = new HashSet<>();
        long totalRows = 0, totalBytes = 0, rawUniqueBytes = 0;
        boolean globalStop = false;
        try (var artifacts = new Artifacts(root, limits.artifacts()); var reader = new TabixReader(dataFile.toString(), indexFile.toString())) {
            artifacts.write(Map.of("raw-pool.vcf", header));
            for (JsonNode gene : plan.path("geneQueries")) {
                String symbol = gene.path("geneSymbol").asText();
                ObjectNode state = genes.addObject().put("geneSymbol", symbol).put("status", "NOT_STARTED");
                ArrayNode windows = state.putArray("windows");
                ArrayNode partitionStates = state.putArray("partitions");
                for (JsonNode definition : partitions(gene, limits))
                    partitionStates.add(((ObjectNode) definition).deepCopy().put("status", "NOT_STARTED")
                            .put("retainedRowObservations", 0).put("retainedRawUtf8BytesIncludingDuplicateReturns", 0));
                if (globalStop) continue;
                long geneRows = 0, geneBytes = 0;
                Set<String> unique = new HashSet<>();
                state.put("status", "RUNNING");
                long maxRows = number(gene, "maximumUniqueRawRows", limits.geneRows());
                long maxBytes = number(gene, "maximumRawUtf8Bytes", limits.geneBytes());
                int windowOrdinal = 0, partitionOrdinal = 0;
                ObjectNode partitionState = (ObjectNode) partitionStates.get(0);
                try {
                    for (JsonNode window : gene.path("queryWindows")) {
                        windowOrdinal++;
                        if (windowOrdinal > partitionState.path("lastWindowOrdinal").asInt())
                            partitionState = (ObjectNode) partitionStates.get(++partitionOrdinal);
                        partitionState.put("status", "RUNNING");
                        String region = window.path("tabixRegion").asText();
                        ObjectNode windowState = windows.addObject().put("queryRegion", region).put("status", "RUNNING")
                                .put("partitionId", partitionState.path("partitionId").asText());
                        require(reader.chr2tid(gene.at("/sourceGeneInterval/chromosome").asText()) >= 0, "SOURCE_CONTIG_NOT_INDEXED");
                        var iterator = reader.query(region);
                        long ordinal = 0;
                        while (iterator != null) {
                            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("EXTRACTION_CANCELLED");
                            if (geneRows >= maxRows) throw new Stop("GENE_ROW_OBSERVATIONS_CAPPED", false);
                            if (totalRows >= limits.totalRows()) throw new Stop("TOTAL_ROW_OBSERVATIONS_CAPPED", true);
                            if (partitionState.path("retainedRowObservations").asLong() >= partitionState.path("maximumRowObservations").asLong())
                                throw new Stop("PARTITION_ROW_OBSERVATIONS_CAPPED", false);
                            String tabixLine = iterator.next();
                            if (tabixLine == null) break;
                            ordinal++;
                            byte[] original = originalLineBytes(tabixLine);
                            String hash = sha(original);
                            byte[] raw = java.util.Arrays.copyOf(original, original.length + 1);
                            raw[original.length] = '\n';
                            windowState.put("lastReturnedOrdinal", ordinal).put("lastReturnedRawLineSha256", hash);
                            long partitionBytesLeft = partitionState.path("maximumRawUtf8Bytes").asLong()
                                    - partitionState.path("retainedRawUtf8BytesIncludingDuplicateReturns").asLong();
                            if (raw.length > limits.line() || raw.length > maxBytes - geneBytes || raw.length > limits.totalBytes() - totalBytes
                                    || raw.length > partitionBytesLeft) {
                                windowState.put("unretainedLookaheadUtf8Bytes", raw.length).put("unretainedLookaheadRawLineSha256", hash);
                                throw new Stop(raw.length > limits.line() ? "SINGLE_LINE_BYTES_CAPPED"
                                        : raw.length > limits.totalBytes() - totalBytes ? "TOTAL_RAW_BYTES_CAPPED"
                                        : raw.length > maxBytes - geneBytes ? "GENE_RAW_BYTES_CAPPED" : "PARTITION_RAW_BYTES_CAPPED",
                                        raw.length > limits.totalBytes() - totalBytes);
                            }
                            ObjectNode projected = projection(decodeSourceLine(original), hash, snapshot);
                            ObjectNode occurrence = membership(projected, symbol);
                            occurrence.put("sourceRawLineSha256", hash).put("queryGeneSymbol", symbol).put("queryRegion", region)
                                    .put("queryReturnOrdinal", ordinal);
                            Map<String, byte[]> parts = new LinkedHashMap<>();
                            boolean first = !globalUnique.contains(hash);
                            if (first) {
                                parts.put("raw-pool.vcf", raw);
                                parts.put("sampling-projection.jsonl", jsonLine(projected));
                            }
                            parts.put("query-occurrences.jsonl", jsonLine(occurrence));
                            artifacts.write(parts);
                            totalRows++; geneRows++; totalBytes += raw.length; geneBytes += raw.length;
                            partitionState.put("retainedRowObservations", partitionState.path("retainedRowObservations").asLong() + 1)
                                    .put("retainedRawUtf8BytesIncludingDuplicateReturns", partitionState.path("retainedRawUtf8BytesIncludingDuplicateReturns").asLong() + raw.length);
                            unique.add(hash);
                            if (globalUnique.add(hash)) rawUniqueBytes += raw.length;
                            windowState.put("retainedObservations", ordinal);
                        }
                        windowState.put("status", "COMPLETE");
                        if (windowOrdinal == partitionState.path("lastWindowOrdinal").asInt()) partitionState.put("status", "COMPLETE");
                    }
                    state.put("status", "COMPLETE");
                } catch (Stop cap) {
                    state.put("status", "CAPPED").put("reasonCode", cap.getMessage());
                    partitionState.put("status", "CAPPED").put("reasonCode", cap.getMessage());
                    if (!windows.isEmpty()) ((ObjectNode) windows.get(windows.size() - 1)).put("status", "CAPPED");
                    globalStop = cap.global;
                } finally {
                    state.put("retainedRowObservations", geneRows).put("retainedRawUtf8BytesIncludingDuplicateReturns", geneBytes)
                            .put("uniqueRawRows", unique.size()).put("plannedWindows", gene.path("queryWindows").size());
                }
            }
            manifest.put("status", "COMPLETE");
            for (JsonNode gene : genes) if (!"COMPLETE".equals(gene.path("status").asText())) manifest.put("status", "CAPPED");
        } catch (IOException | RuntimeException failure) {
            manifest.put("status", failure instanceof java.io.InterruptedIOException ? "CANCELLED" : "FAILED")
                    .put("reasonCode", failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage()));
        }
        for (JsonNode state : genes) {
            if ("RUNNING".equals(state.path("status").asText())) ((ObjectNode) state).put("status", manifest.path("status").asText());
            for (JsonNode window : state.path("windows")) if ("RUNNING".equals(window.path("status").asText()))
                ((ObjectNode) window).put("status", manifest.path("status").asText());
            for (JsonNode partition : state.path("partitions")) if ("RUNNING".equals(partition.path("status").asText()))
                ((ObjectNode) partition).put("status", manifest.path("status").asText());
        }
        while (genes.size() < plan.path("geneQueries").size()) {
            JsonNode skipped = plan.path("geneQueries").get(genes.size());
            genes.addObject().put("geneSymbol", skipped.path("geneSymbol").asText()).put("status", "NOT_STARTED")
                    .put("plannedWindows", skipped.path("queryWindows").size());
        }
        // Snapshot mutation during extraction invalidates the entire pool; whole data hashing is deliberately absent.
        try {
            if (Files.size(dataFile) != source.path("dataBytes").asLong() || Files.getLastModifiedTime(dataFile).toMillis() != dataModified
                    || Files.size(indexFile) != source.path("indexBytes").asLong() || Files.getLastModifiedTime(indexFile).toMillis() != indexModified
                    || !fileSha(indexFile).equals(source.path("indexSha256").asText())
                    || !sha(header(dataFile, source.path("headerUtf8Bytes").asLong())).equals(source.path("headerSha256").asText()))
                manifest.put("status", "FAILED").put("reasonCode", "SOURCE_CHANGED_DURING_EXTRACTION");
        } catch (IOException | RuntimeException unavailable) {
            manifest.put("status", "FAILED").put("reasonCode", "SOURCE_RECHECK_FAILED");
        }
        manifest.put("finishedAt", Instant.now().toString()).put("uniqueRawRows", globalUnique.size())
                .put("uniqueRawUtf8Bytes", rawUniqueBytes).put("retainedRowObservations", totalRows)
                .put("retainedRawUtf8BytesIncludingDuplicateReturns", totalBytes)
                .put("samplingAllowed", "COMPLETE".equals(manifest.path("status").asText()));
        var files = manifest.putArray("artifacts");
        long artifactBytes = 0;
        for (String name : List.of("RUNNING.txt", "raw-pool.vcf", "sampling-projection.jsonl", "query-occurrences.jsonl")) {
            Path file = root.resolve(name);
            if (Files.exists(file)) {
                long bytes = Files.size(file); artifactBytes += bytes;
                files.addObject().put("file", name).put("bytes", bytes).put("sha256", fileSha(file));
            }
        }
        manifest.put("artifactBytesExcludingManifest", artifactBytes);
        byte[] finalBytes = jsonLine(manifest);
        require(finalBytes.length <= MANIFEST_RESERVE && artifactBytes + finalBytes.length <= limits.artifacts(), "MANIFEST_RESERVE_EXCEEDED");
        Files.write(root.resolve("manifest.json"), finalBytes, StandardOpenOption.CREATE_NEW);
        return manifest;
    }
    public static void main(String[] args) {
        try {
            Map<String, String> options = new LinkedHashMap<>();
            require(args.length == 10, "EXPECTED_PLAN_HASH_SOURCE_INDEX_OUTPUT_OPTIONS");
            for (int i = 0; i < args.length; i += 2) require(options.put(args[i], args[i + 1]) == null, "DUPLICATE_OPTION");
            require(options.keySet().equals(Set.of("--plan", "--plan-sha256", "--source", "--index", "--output")), "UNKNOWN_OPTION");
            ObjectNode result = extract(Path.of(options.get("--plan")), options.get("--plan-sha256"), Path.of(options.get("--source")),
                    Path.of(options.get("--index")), Path.of(options.get("--output")));
            System.out.println(JSON.writeValueAsString(JSON.createObjectNode().put("status", result.path("status").asText())
                    .put("uniqueRawRows", result.path("uniqueRawRows").asLong()).put("samplingAllowed", result.path("samplingAllowed").asBoolean())));
            if (!"COMPLETE".equals(result.path("status").asText())) System.exit(2);
        } catch (Exception failure) {
            System.err.println("NOT_EXTRACTED: " + failure.getMessage()); System.exit(2);
        }
    }
}
