import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisSchemaMigrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Explicit maintenance only. Never run by application startup or an upload. */
class MigrateVarvisCandidateSchema {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"--apply-Varvis-only".equals(args[1])) {
            throw new IllegalArgumentException(
                    "Usage: <private-database.properties> --apply-Varvis-only <new-private-audit-directory>");
        }
        Path audit = Path.of(args[2]);
        Files.createDirectory(audit); // Never overwrite an earlier audit.
        Properties properties = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { properties.load(input); }
        String url = properties.getProperty("varvis.datasource.jdbc-url",
                properties.getProperty("varvis.datasource.url"));
        if (url == null || !url.startsWith("jdbc:mysql://")
                || !"/Varvis".equals(java.net.URI.create(url.substring(5)).getPath())) {
            throw new IllegalArgumentException("Only the explicitly authorized MySQL Varvis catalog is allowed");
        }
        try (var connection = DriverManager.getConnection(url,
                properties.getProperty("varvis.datasource.username"),
                properties.getProperty("varvis.datasource.password"))) {
            if (!"Varvis".equals(connection.getCatalog())) {
                throw new IllegalStateException("Connected catalog is not Varvis");
            }
            // Pin every migration operation to this verified connection/catalog.
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var tables = java.util.List.of("variant_analysis_interpretation",
                    "variant_transcript_annotation_cache", "variant_analysis_job");
            var counts = new java.util.LinkedHashMap<String, Long>();
            StringBuilder before = new StringBuilder("catalog=Varvis\n");
            for (String table : tables) {
                before.append(jdbc.queryForMap("SHOW CREATE TABLE " + table)).append('\n');
                counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
            }
            before.append("rowCounts=").append(counts).append('\n');
            Files.writeString(audit.resolve("before.txt"), before);
            new VariantAnalysisSchemaMigrator(jdbc.getDataSource(), jdbc).migrate();
            StringBuilder after = new StringBuilder("catalog=Varvis\n");
            for (String table : tables) {
                after.append(jdbc.queryForMap("SHOW CREATE TABLE " + table)).append('\n');
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
                if (!java.util.Objects.equals(counts.get(table), count)) {
                    throw new IllegalStateException("Row count changed during migration: " + table);
                }
            }
            after.append("rowCounts=").append(counts).append('\n');
            Files.writeString(audit.resolve("after.txt"), after);
            System.out.println("MIGRATED Varvis only; row counts preserved: " + counts);
        }
    }
}
