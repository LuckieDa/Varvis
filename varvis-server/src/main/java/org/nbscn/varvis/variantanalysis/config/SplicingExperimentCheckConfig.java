package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

@Configuration
@ConditionalOnProperty(name = "varvis.datasource.enabled", havingValue = "true", matchIfMissing = true)
public class SplicingExperimentCheckConfig {
    @Bean
    public SplicingExperimentChecks splicingExperimentChecks(ObjectMapper mapper, ResourceLoader resources,
            Environment environment, VariantAnalysisProperties properties, AcmgBusinessService business) {
        String location = environment.getProperty("variant-analysis.acmg-business.splicing-experiment-catalog",
                "classpath:acmg/public-splicing-experiments-v1.json");
        return new SplicingExperimentChecks(mapper, resources.getResource(java.util.Objects.requireNonNull(location)),
                properties.getStorageRoot().resolve("splicing-experiment-checks"), business::read);
    }
}
