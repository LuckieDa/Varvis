package org.nbscn.varvis.variantanalysis.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.Objects;

/** Creates the additive Exomiser metadata table without applying legacy Varvis schema DDL. */
@Configuration
@ConditionalOnProperty(
        prefix = "varvis.datasource",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ExomiserAnalysisPersistenceConfig {

    @Bean
    public DataSourceInitializer exomiserAnalysisDataSourceInitializer(
            @Qualifier("varvisDataSource") DataSource dataSource
    ) {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/exomiser-analysis-schema.sql"));
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(Objects.requireNonNull(dataSource, "dataSource"));
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
