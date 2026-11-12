import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Read-only JDBC metadata probe used by Test-VarvisDatabaseReadiness.ps1.
 *
 * The probe never prints the JDBC URL, username, password, row contents or
 * patient data. It emits tab-separated metadata records for the PowerShell
 * wrapper to validate and serialize.
 */
public final class DatabaseReadinessProbe {

    private static final Set<String> EXPECTED_TABLES = new HashSet<String>(Arrays.asList(
            "chromosome",
            "cytoband",
            "disease",
            "disease_band_map",
            "gene",
            "disease_gene_map",
            "disease_moi",
            "disease_onset",
            "disease_gene_link",
            "gene_coordinate",
            "small_variant",
            "structural_variant",
            "clingen_gene_curation",
            "clingen_region_curation",
            "recurrent_cnv_region",
            "variant_analysis_job",
            "variant_analysis_observation",
            "variant_analysis_event",
            "variant_analysis_interpretation",
            "variant_analysis_enhancement",
            "variant_transcript_annotation_cache",
            "exomiser_analysis_job"
    ));

    private DatabaseReadinessProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            emit("ERROR", "ARGUMENTS", "EXPECTED_CONFIG_PATH");
            System.exit(2);
        }

        Properties local = new Properties();
        try (InputStream input = new FileInputStream(args[0])) {
            local.load(input);
        } catch (Exception ex) {
            emit("ERROR", "CONFIG_READ", ex.getClass().getSimpleName());
            System.exit(3);
        }

        String jdbcUrl = firstNonBlank(
                local.getProperty("varvis.datasource.jdbc-url"),
                local.getProperty("varvis.datasource.url")
        );
        String username = trimToNull(local.getProperty("varvis.datasource.username"));
        String password = local.getProperty("varvis.datasource.password", "");
        if (jdbcUrl == null || username == null) {
            emit("ERROR", "CONFIG_REQUIRED", "JDBC_URL_OR_USERNAME_MISSING");
            System.exit(4);
        }

        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", username);
        connectionProperties.setProperty("password", password);
        connectionProperties.setProperty("connectTimeout", "10000");
        connectionProperties.setProperty("socketTimeout", "15000");
        connectionProperties.setProperty("useInformationSchema", "true");

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            DriverManager.setLoginTimeout(10);
        } catch (Exception ex) {
            emit("ERROR", "DRIVER", ex.getClass().getSimpleName());
            System.exit(5);
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, connectionProperties)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            emit("META", "connection", "READY");
            emit("META", "catalog", safe(connection.getCatalog()));
            emit("META", "readOnly", Boolean.toString(connection.isReadOnly()));

            DatabaseMetaData metadata = connection.getMetaData();
            emit("META", "databaseProduct", safe(metadata.getDatabaseProductName()));
            emit("META", "databaseVersion", safe(metadata.getDatabaseProductVersion()));
            emit("META", "driverVersion", safe(metadata.getDriverVersion()));

            emitTables(connection);
            emitTableEstimates(connection);
            connection.rollback();
        } catch (SQLException ex) {
            emit("ERROR", "SQL", safe(ex.getSQLState()), Integer.toString(ex.getErrorCode()));
            System.exit(6);
        }
    }

    private static void emitTables(Connection connection) throws SQLException {
        emitQuery(connection,
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'",
                "TABLE");
        emitExpectedTableQuery(connection,
                "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "ORDER BY TABLE_NAME, ORDINAL_POSITION",
                "COLUMN");
        emitExpectedTableQuery(connection,
                "SELECT TABLE_NAME, COLUMN_NAME, SEQ_IN_INDEX FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME = 'PRIMARY' "
                        + "ORDER BY TABLE_NAME, SEQ_IN_INDEX",
                "PRIMARY_KEY");
        emitExpectedTableQuery(connection,
                "SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX",
                "INDEX");
        emitExpectedTableQuery(connection,
                "SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, "
                        + "REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL "
                        + "ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION",
                "FOREIGN_KEY");
    }

    private static void emitQuery(Connection connection, String sql, String recordType)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            int columnCount = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                String[] fields = new String[columnCount + 1];
                fields[0] = recordType;
                for (int index = 1; index <= columnCount; index++) {
                    fields[index] = safe(rows.getString(index));
                }
                emit(fields);
            }
        }
    }

    private static void emitExpectedTableQuery(
            Connection connection,
            String sql,
            String recordType
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            int columnCount = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                String table = rows.getString(1);
                if (!EXPECTED_TABLES.contains(table.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String[] fields = new String[columnCount + 1];
                fields[0] = recordType;
                for (int index = 1; index <= columnCount; index++) {
                    fields[index] = safe(rows.getString(index));
                }
                emit(fields);
            }
        }
    }

    private static void emitTableEstimates(Connection connection) {
        String sql = "SELECT TABLE_NAME, ENGINE, TABLE_ROWS "
                + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                emit(
                        "TABLE_STATS",
                        safe(rows.getString("TABLE_NAME")),
                        safe(rows.getString("ENGINE")),
                        Long.toString(rows.getLong("TABLE_ROWS"))
                );
            }
        } catch (SQLException ex) {
            emit("META", "tableStats", "UNAVAILABLE_" + safe(ex.getSQLState()));
        }
    }

    private static String firstNonBlank(String first, String second) {
        String value = trimToNull(first);
        return value == null ? trimToNull(second) : value;
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static void emit(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                line.append('\t');
            }
            line.append(safe(fields[index]));
        }
        System.out.println(line.toString());
    }
}
