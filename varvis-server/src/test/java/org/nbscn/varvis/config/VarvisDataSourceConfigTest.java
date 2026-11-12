package org.nbscn.varvis.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VarvisDataSourceConfigTest {

    @Test
    void configuresMysqlBatchRewriteAndJdbcTimeouts() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("varvis.datasource.jdbc-url", "jdbc:mysql://localhost:3306/Varvis")
                .withProperty("varvis.datasource.username", "test")
                .withProperty("varvis.datasource.password", "test")
                .withProperty("varvis.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")
                .withProperty("varvis.datasource.jdbc-connect-timeout", "PT12S")
                .withProperty("varvis.datasource.jdbc-socket-timeout", "PT2M")
                .withProperty("varvis.datasource.rewrite-batched-statements", "true");
        VarvisDataSourceConfig config = new VarvisDataSourceConfig(environment);

        try (HikariDataSource dataSource = config.varvisDataSource()) {
            Properties properties = dataSource.getDataSourceProperties();
            assertEquals("12000", properties.getProperty("connectTimeout"));
            assertEquals("120000", properties.getProperty("socketTimeout"));
            assertEquals(true, properties.get("rewriteBatchedStatements"));
            assertDriverTimeouts(dataSource, 12000, 120000);
            assertEquals(120000L, dataSource.getMaxLifetime());
            assertEquals(60000L, dataSource.getKeepaliveTime());
        }
    }

    @Test
    void mysqlDriverHonorsDefaultTimeoutsInsteadOfSilentlyUsingZero() {
        var environment = new MockEnvironment()
                .withProperty("varvis.datasource.jdbc-url", "jdbc:mysql://localhost:3306/Varvis")
                .withProperty("varvis.datasource.username", "test")
                .withProperty("varvis.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        try (var dataSource = new VarvisDataSourceConfig(environment).varvisDataSource()) {
            assertDriverTimeouts(dataSource, 30000, 300000);
        }
    }

    private static void assertDriverTimeouts(HikariDataSource dataSource, int connectMillis, int socketMillis) {
        // Exercise the installed driver's actual parsing path, without opening a database connection.
        // Checking only the Hikari Properties map missed the Long -> discarded property regression.
        var normalized = com.mysql.cj.conf.ConnectionUrl.getConnectionUrlInstance(
                dataSource.getJdbcUrl(), dataSource.getDataSourceProperties()).getMainHost().exposeAsProperties();
        var actual = new com.mysql.cj.conf.DefaultPropertySet();
        actual.initializeProperties(normalized);
        assertEquals(connectMillis, actual.getIntegerProperty(com.mysql.cj.conf.PropertyKey.connectTimeout).getValue());
        assertEquals(socketMillis, actual.getIntegerProperty(com.mysql.cj.conf.PropertyKey.socketTimeout).getValue());
    }

    @Test
    void connectsToH2WithoutPassingMysqlOnlyDriverProperties() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("varvis.datasource.jdbc-url",
                        "jdbc:h2:mem:varvis_config_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                .withProperty("varvis.datasource.username", "sa")
                .withProperty("varvis.datasource.password", "")
                .withProperty("varvis.datasource.driver-class-name", "org.h2.Driver");
        VarvisDataSourceConfig config = new VarvisDataSourceConfig(environment);

        try (HikariDataSource dataSource = config.varvisDataSource()) {
            Properties properties = dataSource.getDataSourceProperties();
            assertFalse(properties.containsKey("connectTimeout"));
            assertFalse(properties.containsKey("socketTimeout"));
            assertFalse(properties.containsKey("rewriteBatchedStatements"));
            assertEquals(0L, dataSource.getMaxLifetime());
            assertEquals(0L, dataSource.getKeepaliveTime());
            try (java.sql.Connection connection = dataSource.getConnection()) {
                assertTrue(connection.isValid(2));
            }
        }
    }

    @Test
    void keepsFileBackedH2AvailableAfterDelayedExternalStage(@TempDir Path temporary) throws Exception {
        String database = temporary.resolve("long-running-analysis").toString().replace('\\', '/');
        MockEnvironment environment = new MockEnvironment()
                .withProperty("varvis.datasource.jdbc-url",
                        "jdbc:h2:file:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                .withProperty("varvis.datasource.username", "sa")
                .withProperty("varvis.datasource.password", "")
                .withProperty("varvis.datasource.driver-class-name", "org.h2.Driver")
                .withProperty("varvis.datasource.maximum-pool-size", "2")
                .withProperty("varvis.datasource.minimum-idle", "1");

        try (HikariDataSource dataSource = new VarvisDataSourceConfig(environment).varvisDataSource()) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE analysis_checkpoint (id INT PRIMARY KEY, status VARCHAR(16))");
            }

            // Represents a long external Exomiser stage without holding a JDBC connection.
            Thread.sleep(100L);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO analysis_checkpoint (id, status) VALUES (1, 'READY')");
                try (ResultSet result = statement.executeQuery(
                        "SELECT status FROM analysis_checkpoint WHERE id = 1")) {
                    assertTrue(result.next());
                    assertEquals("READY", result.getString(1));
                }
            }
            assertEquals(0L, dataSource.getMaxLifetime());
            assertEquals(0L, dataSource.getKeepaliveTime());
        }
    }
}
