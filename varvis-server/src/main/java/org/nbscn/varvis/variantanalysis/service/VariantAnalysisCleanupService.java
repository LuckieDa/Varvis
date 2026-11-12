package org.nbscn.varvis.variantanalysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.springframework.stereotype.Service;

/**
 * Compatibility boundary for the retired age-based job cleanup.
 * Formal jobs, candidates, ACMG history and source workspaces are persistent.
 * Disposable intermediates remain owned and cleaned by their producing tool;
 * this component never scans workspaces or writes to the repository.
 */
@Service
public class VariantAnalysisCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VariantAnalysisCleanupService.class);

    public VariantAnalysisCleanupService(VariantAnalysisJobRepository repository,
                                         VariantAnalysisProperties properties) {
        LOGGER.info("Analysis history retention: persistent. Age-based job/workspace deletion is disabled; "
                + "legacy retention and cleanup-delay settings do not enable it.");
    }

    /** Kept so an old caller cannot accidentally re-enable destructive expiry. No scheduled invocation. */
    @Deprecated
    public void cleanupExpiredJobs() {
        LOGGER.debug("Ignored legacy job-expiry cleanup: formal analysis history is persistent.");
    }
}
