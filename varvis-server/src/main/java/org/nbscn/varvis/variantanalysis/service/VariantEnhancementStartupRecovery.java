package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class VariantEnhancementStartupRecovery implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantEnhancementStartupRecovery.class);

    private final VariantEnhancementRepository repository;

    public VariantEnhancementStartupRecovery(VariantEnhancementRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = repository.failInterruptedTasks();
        if (recovered > 0) {
            LOGGER.warn("Marked {} interrupted variant enhancement task(s) as failed", recovered);
        }
    }
}
