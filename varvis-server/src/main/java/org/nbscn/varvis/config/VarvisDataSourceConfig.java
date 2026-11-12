package org.nbscn.varvis.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Objects;

/**
 * Data source config for variant knowledge base (varvis schema).
 */
@Configuration
@EnableTransactionManagement
@ConditionalOnProperty(
        prefix = "varvis.datasource",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@MapperScan(
        basePackages = "org.nbscn.varvis.mapper.varvis",
        sqlSessionFactoryRef = "varvisSqlSessionFactory"
)
public class VarvisDataSourceConfig {

    private final Environment env;

    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration DEFAULT_MAX_LIFETIME = Duration.ofMinutes(2);
    private static final Duration DEFAULT_KEEPALIVE_TIME = Duration.ofMinutes(1);
    private static final Duration EMBEDDED_MAX_LIFETIME = Duration.ZERO;
    private static final Duration EMBEDDED_KEEPALIVE_TIME = Duration.ZERO;
    private static final Duration DEFAULT_JDBC_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_JDBC_SOCKET_TIMEOUT = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_POOL_SIZE = 30;
    private static final int DEFAULT_MIN_IDLE = 8;

    public VarvisDataSourceConfig(Environment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    @Bean(name = "varvisDataSource")
    public HikariDataSource varvisDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        String jdbcUrl = env.getProperty("varvis.datasource.jdbc-url");
        if (jdbcUrl == null) {
            jdbcUrl = env.getProperty("varvis.datasource.url");
        }
        dataSource.setJdbcUrl(requireProperty(jdbcUrl, "varvis.datasource.url", "VARVIS_DB_URL"));
        dataSource.setUsername(requireProperty(
                env.getProperty("varvis.datasource.username"),
                "varvis.datasource.username",
                "VARVIS_DB_USERNAME"
        ));
        dataSource.setPassword(env.getProperty("varvis.datasource.password"));
        String driverClassName = env.getProperty("varvis.datasource.driver-class-name");
        if (driverClassName == null) {
            driverClassName = env.getProperty("varvis.datasource.driverClassName");
        }
        dataSource.setDriverClassName(driverClassName);
        dataSource.setPoolName("varvis-hikari");
        applyConnectionLifecycleSettings(dataSource);
        return dataSource;
    }

    @Bean(name = "varvisTxManager")
    public DataSourceTransactionManager varvisTxManager(@Qualifier("varvisDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Bean(name = "varvisSqlSessionFactory")
    public SqlSessionFactory varvisSqlSessionFactory(@Qualifier("varvisDataSource") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(dataSource);
        sessionFactoryBean.setTypeAliasesPackage("org.nbscn.varvis.entity.variant,org.nbscn.varvis.dto.variant.response");
        sessionFactoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/varvis/*.xml")
        );
        return sessionFactoryBean.getObject();
    }

    @Bean(name = "varvisNamedParameterJdbcTemplate")
    public NamedParameterJdbcTemplate varvisNamedParameterJdbcTemplate(
            @Qualifier("varvisDataSource") DataSource dataSource
    ) {
        return new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Bean(name = "varvisJdbcTemplate")
    public JdbcTemplate varvisJdbcTemplate(@Qualifier("varvisDataSource") DataSource dataSource) {
        return new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    private void applyConnectionLifecycleSettings(HikariDataSource dataSource) {
        boolean embeddedDatabase = isEmbeddedDatabase(dataSource.getJdbcUrl());
        dataSource.setConnectionTimeout(
                getDurationProperty("varvis.datasource.connection-timeout", DEFAULT_CONNECTION_TIMEOUT).toMillis()
        );
        dataSource.setValidationTimeout(
                getDurationProperty("varvis.datasource.validation-timeout", DEFAULT_VALIDATION_TIMEOUT).toMillis()
        );
        dataSource.setIdleTimeout(
                getDurationProperty("varvis.datasource.idle-timeout", DEFAULT_IDLE_TIMEOUT).toMillis()
        );
        dataSource.setMaxLifetime(
                getDurationProperty(
                        "varvis.datasource.max-lifetime",
                        embeddedDatabase ? EMBEDDED_MAX_LIFETIME : DEFAULT_MAX_LIFETIME
                ).toMillis()
        );
        dataSource.setKeepaliveTime(
                getDurationProperty(
                        "varvis.datasource.keepalive-time",
                        embeddedDatabase ? EMBEDDED_KEEPALIVE_TIME : DEFAULT_KEEPALIVE_TIME
                ).toMillis()
        );
        if (dataSource.getJdbcUrl() != null
                && dataSource.getJdbcUrl().toLowerCase(java.util.Locale.ROOT)
                .startsWith("jdbc:mysql:")) {
            dataSource.addDataSourceProperty(
                    "connectTimeout",
                    Long.toString(getDurationProperty(
                            "varvis.datasource.jdbc-connect-timeout",
                            DEFAULT_JDBC_CONNECT_TIMEOUT
                    ).toMillis())
            );
            // Connector/J's URL/property parser ignores non-String Properties values. A Long
            // silently becomes socketTimeout=0 (unbounded), even if Hikari reports our number.
            dataSource.addDataSourceProperty(
                    "socketTimeout",
                    Long.toString(getDurationProperty(
                            "varvis.datasource.jdbc-socket-timeout",
                            DEFAULT_JDBC_SOCKET_TIMEOUT
                    ).toMillis())
            );
            dataSource.addDataSourceProperty(
                    "rewriteBatchedStatements",
                    getBooleanProperty("varvis.datasource.rewrite-batched-statements", true)
            );
        }
        int maxPoolSize = getIntProperty("varvis.datasource.maximum-pool-size", DEFAULT_MAX_POOL_SIZE);
        int minIdle = getIntProperty("varvis.datasource.minimum-idle", DEFAULT_MIN_IDLE);
        if (maxPoolSize > 0) {
            dataSource.setMaximumPoolSize(maxPoolSize);
        }
        if (minIdle > 0) {
            dataSource.setMinimumIdle(Math.min(minIdle, dataSource.getMaximumPoolSize()));
        }
    }

    private boolean isEmbeddedDatabase(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        String normalized = jdbcUrl.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("jdbc:h2:file:") || normalized.startsWith("jdbc:h2:mem:");
    }

    private boolean getBooleanProperty(String key, boolean fallback) {
        String raw = env.getProperty(Objects.requireNonNull(key, "key"));
        return raw == null || raw.trim().isEmpty() ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private int getIntProperty(String key, int fallback) {
        String raw = env.getProperty(Objects.requireNonNull(key, "key"));
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Duration getDurationProperty(String key, Duration fallback) {
        String raw = env.getProperty(Objects.requireNonNull(key, "key"));
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Duration.parse(raw.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String requireProperty(String value, String propertyName, String environmentVariable) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing Varvis database configuration '" + propertyName + "'. "
                            + "Set " + environmentVariable + " or create config/database-local.properties "
                            + "from config/database-local.properties.example."
            );
        }
        return value.trim();
    }
}
