package org.nbscn.varvis.variantanalysis.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.Objects;

@Configuration
@ConditionalOnExpression(
        "'${varvis.datasource.enabled:true}' == 'true' && "
                + "'${variant-analysis.persistence.legacy-schema-bootstrap-enabled:true}' == 'true'"
)
public class VariantAnalysisPersistenceConfig {

    @Bean
    public DataSourceInitializer variantAnalysisDataSourceInitializer(
            @Qualifier("varvisDataSource") DataSource dataSource
    ) {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/variant-analysis-schema.sql"));
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(Objects.requireNonNull(dataSource, "dataSource"));
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
