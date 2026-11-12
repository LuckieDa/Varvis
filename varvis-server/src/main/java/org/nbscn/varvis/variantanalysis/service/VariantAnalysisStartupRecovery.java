package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

@Component
public class VariantAnalysisStartupRecovery implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantAnalysisStartupRecovery.class);

    private final VariantAnalysisJobRepository repository;
    private final ExomiserCompletionRecoveryService exomiserCompletionRecoveryService;

    @Autowired
    public VariantAnalysisStartupRecovery(VariantAnalysisJobRepository repository,
                                          ExomiserCompletionRecoveryService exomiserCompletionRecoveryService) {
        this.repository = repository;
        this.exomiserCompletionRecoveryService = exomiserCompletionRecoveryService;
    }

    VariantAnalysisStartupRecovery(VariantAnalysisJobRepository repository) {
        this(repository, null);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            recoverInterruptedJobs();
        } catch (TransientDataAccessException unavailable) {
            // Recovery of old work must not close the HTTP server and interrupt new uploads.
            // Do not retry the unscoped recovery pass after startup: it could fail new live jobs.
            LOGGER.warn("VARIANT_ANALYSIS_STARTUP_RECOVERY_DEFERRED: historical job recovery encountered {}. "
                            + "The service will remain available; resolve the database contention before "
                            + "a subsequent recovery attempt. No analysis was replayed.",
                    unavailable.getClass().getSimpleName());
        }
    }

    private void recoverInterruptedJobs() {
        if (exomiserCompletionRecoveryService != null) {
            int recovered = exomiserCompletionRecoveryService.recoverInterruptedExomiserFinalization();
            if (recovered > 0) {
                LOGGER.info("Recovered {} completed Exomiser persistence checkpoint(s) before startup failure closure", recovered);
            }
        }
        int recovered = repository.failInterruptedJobs();
        if (recovered > 0) {
            LOGGER.warn("Marked {} interrupted variant analysis job(s) as failed", recovered);
        }
    }
}
