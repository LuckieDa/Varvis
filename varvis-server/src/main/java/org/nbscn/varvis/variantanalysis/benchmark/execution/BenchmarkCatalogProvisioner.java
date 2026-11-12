package org.nbscn.varvis.variantanalysis.benchmark.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Provisions only the approved empty benchmark catalog; it never submits an analysis. */
public final class BenchmarkCatalogProvisioner {

    public static final String CATALOG_ID = "varvis_benchmark_pilot_0_1_27";
    private static final List<String> REQUIRED_TABLES = Collections.unmodifiableList(Arrays.asList(
            "variant_analysis_job", "variant_analysis_observation", "variant_analysis_event",
            "variant_analysis_interpretation", "variant_analysis_enhancement",
            "variant_transcript_annotation_cache"
    ));

    private BenchmarkCatalogProvisioner() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        if (!CATALOG_ID.equals(arguments.catalog)) {
            throw new IllegalArgumentException("BENCHMARK_CATALOG_ID_MISMATCH");
        }
        Map<String, Object> report = provision(arguments.properties, arguments.catalog);
        writeReport(arguments.report, report);
        System.out.println("{\"status\":\"READY_EMPTY\",\"logicalCatalogId\":\""
                + CATALOG_ID + "\"}");
    }

    static Map<String, Object> provision(Path propertiesPath, String catalog) throws Exception {
        if (!CATALOG_ID.equals(catalog)) {
            throw new IllegalArgumentException("BENCHMARK_CATALOG_ID_MISMATCH");
        }
        Properties properties = loadProperties(propertiesPath);
        String sourceUrl = required(properties, "varvis.datasource.jdbc-url", "varvis.datasource.url");
        String username = required(properties, "varvis.datasource.username");
        String password = properties.getProperty("varvis.datasource.password", "");
        Class.forName("com.mysql.cj.jdbc.Driver");

        boolean existed;
        try (Connection source = DriverManager.getConnection(sourceUrl, username, password)) {
            existed = catalogExists(source, catalog);
            if (!existed) {
                try (Statement statement = source.createStatement()) {
                    statement.execute("CREATE DATABASE `" + catalog + "` CHARACTER SET utf8mb4");
                }
            }
        }

        try (Connection target = Objects.requireNonNull(DriverManager.getConnection(
                replaceCatalog(sourceUrl, catalog), username, password))) {
            new ResourceDatabasePopulator(new ClassPathResource("db/variant-analysis-schema.sql"))
                    .populate(target);
            Set<String> tables = tables(target);
            if (!tables.containsAll(REQUIRED_TABLES)) {
                throw new IllegalStateException("BENCHMARK_CATALOG_SCHEMA_INCOMPLETE");
            }
            verifyRollback(target);
            int analysisRows = rowCount(target, "variant_analysis_job");
            if (analysisRows != 0) {
                throw new IllegalStateException("BENCHMARK_CATALOG_NOT_EMPTY");
            }
            DatabaseMetaData metadata = target.getMetaData();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("schemaVersion", "benchmark-catalog-provenance-v1");
            result.put("logicalCatalogId", CATALOG_ID);
            result.put("status", "READY_EMPTY");
            result.put("createdThisInvocation", !existed);
            result.put("schemaFingerprint", schemaFingerprint());
            result.put("databaseProduct", metadata.getDatabaseProductName());
            result.put("databaseProductVersion", metadata.getDatabaseProductVersion());
            result.put("tableCount", tables.size());
            result.put("requiredTables", new ArrayList<String>(REQUIRED_TABLES));
            result.put("tableIndexCounts", indexCounts(target, tables));
            result.put("analysisResultRowCount", analysisRows);
            result.put("rollbackPersistenceCheck", "PASSED");
            result.put("timeZoneStatus", "CONNECTION_AVAILABLE");
            result.put("validatedAt", Instant.now().toString());
            return result;
        }
    }

    static String replaceCatalog(String jdbcUrl, String catalog) {
        if (jdbcUrl == null || !jdbcUrl.matches("jdbc:mysql://[^/]+/[^?]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("UNSUPPORTED_MYSQL_JDBC_URL");
        }
        int slash = jdbcUrl.indexOf('/', "jdbc:mysql://".length());
        int question = jdbcUrl.indexOf('?', slash);
        String suffix = question < 0 ? "" : jdbcUrl.substring(question);
        return jdbcUrl.substring(0, slash + 1) + catalog + suffix;
    }

    private static Properties loadProperties(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("DATABASE_CONFIGURATION_MISSING");
        }
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(path.toFile())) {
            properties.load(input);
        }
        return properties;
    }

    private static String required(Properties properties, String... keys) {
        for (String key : keys) {
            String value = properties.getProperty(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("DATABASE_CONFIGURATION_REQUIRED");
    }

    private static boolean catalogExists(Connection connection, String catalog) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, catalog);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Set<String> tables(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<String>();
        try (ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
            }
        }
        return tables;
    }

    private static Map<String, Integer> indexCounts(Connection connection, Set<String> tables) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String table : REQUIRED_TABLES) {
            if (!tables.contains(table)) {
                continue;
            }
            Set<String> names = new LinkedHashSet<String>();
            try (ResultSet result = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
                while (result.next()) {
                    String name = result.getString("INDEX_NAME");
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
            counts.put(table, names.size());
        }
        return counts;
    }

    private static int rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM `" + table + "`")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void verifyRollback(Connection connection) throws SQLException {
        String id = "00000000-0000-0000-0000-000000000001";
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO variant_analysis_job (id, original_file_name, stored_file_name, file_sha256, genome_assembly, analysis_mode, status, normalization_applied, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())")) {
            insert.setString(1, id);
            insert.setString(2, "synthetic-catalog-check.vcf");
            insert.setString(3, "synthetic-catalog-check.vcf");
            insert.setString(4, "0000000000000000000000000000000000000000000000000000000000000000");
            insert.setString(5, "GRCh38");
            insert.setString(6, "WES");
            insert.setString(7, "READY");
            insert.setBoolean(8, false);
            insert.executeUpdate();
            if (rowCount(connection, "variant_analysis_job") != 1) {
                throw new IllegalStateException("BENCHMARK_CATALOG_ROLLBACK_WRITE_FAILED");
            }
        } finally {
            connection.rollback();
            connection.setAutoCommit(originalAutoCommit);
        }
        if (rowCount(connection, "variant_analysis_job") != 0) {
            throw new IllegalStateException("BENCHMARK_CATALOG_ROLLBACK_NOT_EMPTY");
        }
    }

    private static String schemaFingerprint() throws Exception {
        byte[] content;
        try (InputStream input = new ClassPathResource("db/variant-analysis-schema.sql").getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            content = output.toByteArray();
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder value = new StringBuilder();
        for (byte item : digest) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }

    private static void writeReport(Path destination, Map<String, Object> value) throws Exception {
        Files.createDirectories(destination.getParent());
        byte[] encoded = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        if (Files.exists(destination)) {
            Map<?, ?> existing = new ObjectMapper().readValue(destination.toFile(), Map.class);
            if (!CATALOG_ID.equals(existing.get("logicalCatalogId"))
                    || !"READY_EMPTY".equals(existing.get("status"))) {
                throw new IllegalStateException("CATALOG_PROVENANCE_OUTPUT_CONFLICT");
            }
        }
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, encoded);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class Arguments {
        private final Path properties;
        private final String catalog;
        private final Path report;

        private Arguments(Path properties, String catalog, Path report) {
            this.properties = properties;
            this.catalog = catalog;
            this.report = report;
        }

        private static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length || !args[index].startsWith("--")) {
                    throw new IllegalArgumentException("CATALOG_PROVISION_ARGUMENTS_INVALID");
                }
                values.put(args[index].substring(2), args[index + 1]);
            }
            return new Arguments(java.nio.file.Paths.get(required(values, "properties")),
                    required(values, "catalog"), java.nio.file.Paths.get(required(values, "report")));
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("CATALOG_PROVISION_ARGUMENT_REQUIRED:" + key);
            }
            return value.trim();
        }
    }
}
