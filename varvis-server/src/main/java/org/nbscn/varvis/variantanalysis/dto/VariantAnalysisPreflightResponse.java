package org.nbscn.varvis.variantanalysis.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VariantAnalysisPreflightResponse {

    private final boolean applicationReady;
    private final String readinessStatus;
    private final String defaultEngine;
    private final String runtimePlatform;
    private final List<ComponentStatus> requiredComponents;
    private final List<ComponentStatus> optionalComponents;
    private final String analysisAssemblyPolicy;
    private final String requestedInputAssembly;
    private final String effectiveAnalysisAssembly;
    private final boolean liftoverRequired;
    private final boolean liftoverAvailable;
    private final VepProfileStatus grch37VepProfile;
    private final VepProfileStatus grch38VepProfile;
    private final String selectedVepProfile;
    private final VepProfileStatus selectedVepProfileStatus;
    private final List<ProviderCapabilityStatus> providerCapabilities;
    private final int preRankingCandidateCap;
    private final String truncationRisk;
    private final boolean cacheWritable;
    private final boolean reportDirectoryWritable;
    private final Long diskFreeBytes;
    private final List<String> blockingErrors;
    private final List<String> warnings;

    public VariantAnalysisPreflightResponse(boolean applicationReady,
                                            String readinessStatus,
                                            String defaultEngine,
                                            String runtimePlatform,
                                            List<ComponentStatus> requiredComponents,
                                            List<ComponentStatus> optionalComponents,
                                            String analysisAssemblyPolicy,
                                            String requestedInputAssembly,
                                            String effectiveAnalysisAssembly,
                                            boolean liftoverRequired,
                                            boolean liftoverAvailable,
                                            VepProfileStatus grch37VepProfile,
                                            VepProfileStatus grch38VepProfile,
                                            String selectedVepProfile,
                                            VepProfileStatus selectedVepProfileStatus,
                                            List<ProviderCapabilityStatus> providerCapabilities,
                                            int preRankingCandidateCap,
                                            String truncationRisk,
                                            boolean cacheWritable,
                                            boolean reportDirectoryWritable,
                                            Long diskFreeBytes,
                                            List<String> blockingErrors,
                                            List<String> warnings) {
        this.applicationReady = applicationReady;
        this.readinessStatus = readinessStatus;
        this.defaultEngine = defaultEngine;
        this.runtimePlatform = runtimePlatform;
        this.requiredComponents = immutableComponentCopy(requiredComponents);
        this.optionalComponents = immutableComponentCopy(optionalComponents);
        this.analysisAssemblyPolicy = analysisAssemblyPolicy;
        this.requestedInputAssembly = requestedInputAssembly;
        this.effectiveAnalysisAssembly = effectiveAnalysisAssembly;
        this.liftoverRequired = liftoverRequired;
        this.liftoverAvailable = liftoverAvailable;
        this.grch37VepProfile = grch37VepProfile;
        this.grch38VepProfile = grch38VepProfile;
        this.selectedVepProfile = selectedVepProfile;
        this.selectedVepProfileStatus = selectedVepProfileStatus;
        this.providerCapabilities = immutableProviderCopy(providerCapabilities);
        this.preRankingCandidateCap = preRankingCandidateCap;
        this.truncationRisk = truncationRisk;
        this.cacheWritable = cacheWritable;
        this.reportDirectoryWritable = reportDirectoryWritable;
        this.diskFreeBytes = diskFreeBytes;
        this.blockingErrors = immutableCopy(blockingErrors);
        this.warnings = immutableCopy(warnings);
    }

    public boolean isApplicationReady() {
        return applicationReady;
    }

    public String getReadinessStatus() {
        return readinessStatus;
    }

    public String getDefaultEngine() {
        return defaultEngine;
    }

    public String getRuntimePlatform() {
        return runtimePlatform;
    }

    public List<ComponentStatus> getRequiredComponents() {
        return requiredComponents;
    }

    public List<ComponentStatus> getOptionalComponents() {
        return optionalComponents;
    }

    public String getAnalysisAssemblyPolicy() {
        return analysisAssemblyPolicy;
    }

    public String getRequestedInputAssembly() {
        return requestedInputAssembly;
    }

    public String getEffectiveAnalysisAssembly() {
        return effectiveAnalysisAssembly;
    }

    public boolean isLiftoverRequired() {
        return liftoverRequired;
    }

    public boolean isLiftoverAvailable() {
        return liftoverAvailable;
    }

    public VepProfileStatus getGrch37VepProfile() {
        return grch37VepProfile;
    }

    public VepProfileStatus getGrch38VepProfile() {
        return grch38VepProfile;
    }

    public String getSelectedVepProfile() {
        return selectedVepProfile;
    }

    public VepProfileStatus getSelectedVepProfileStatus() {
        return selectedVepProfileStatus;
    }

    public List<ProviderCapabilityStatus> getProviderCapabilities() {
        return providerCapabilities;
    }

    public int getPreRankingCandidateCap() {
        return preRankingCandidateCap;
    }

    public String getTruncationRisk() {
        return truncationRisk;
    }

    public boolean isCacheWritable() {
        return cacheWritable;
    }

    public boolean isReportDirectoryWritable() {
        return reportDirectoryWritable;
    }

    public Long getDiskFreeBytes() {
        return diskFreeBytes;
    }

    public List<String> getBlockingErrors() {
        return blockingErrors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    private static List<String> immutableCopy(List<String> values) {
        return values == null || values.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    private static List<ProviderCapabilityStatus> immutableProviderCopy(List<ProviderCapabilityStatus> values) {
        return values == null || values.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<ProviderCapabilityStatus>(values));
    }

    private static List<ComponentStatus> immutableComponentCopy(List<ComponentStatus> values) {
        return values == null || values.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<ComponentStatus>(values));
    }

    public static class ComponentStatus {

        private final String component;
        private final boolean required;
        private final boolean enabled;
        private final boolean ready;
        private final String status;
        private final String message;
        private final List<String> missingResources;

        public ComponentStatus(String component,
                               boolean required,
                               boolean enabled,
                               boolean ready,
                               String status,
                               String message,
                               List<String> missingResources) {
            this.component = component;
            this.required = required;
            this.enabled = enabled;
            this.ready = ready;
            this.status = status;
            this.message = message;
            this.missingResources = immutableCopy(missingResources);
        }

        public String getComponent() {
            return component;
        }

        public boolean isRequired() {
            return required;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isReady() {
            return ready;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public List<String> getMissingResources() {
            return missingResources;
        }
    }

    public static class VepProfileStatus {

        private final String assembly;
        private final boolean enabled;
        private final boolean executionEnabled;
        private final String status;
        private final String providerVersion;
        private final String cacheVersion;
        private final boolean cacheReady;
        private final boolean fastaReady;
        private final boolean fastaIndexReady;
        private final List<String> missingResources;
        private final List<String> warnings;

        public VepProfileStatus(String assembly,
                                boolean enabled,
                                boolean executionEnabled,
                                String status,
                                String providerVersion,
                                String cacheVersion,
                                boolean cacheReady,
                                boolean fastaReady,
                                boolean fastaIndexReady,
                                List<String> missingResources,
                                List<String> warnings) {
            this.assembly = assembly;
            this.enabled = enabled;
            this.executionEnabled = executionEnabled;
            this.status = status;
            this.providerVersion = providerVersion;
            this.cacheVersion = cacheVersion;
            this.cacheReady = cacheReady;
            this.fastaReady = fastaReady;
            this.fastaIndexReady = fastaIndexReady;
            this.missingResources = immutableCopy(missingResources);
            this.warnings = immutableCopy(warnings);
        }

        public String getAssembly() {
            return assembly;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isExecutionEnabled() {
            return executionEnabled;
        }

        public String getStatus() {
            return status;
        }

        public String getProviderVersion() {
            return providerVersion;
        }

        public String getCacheVersion() {
            return cacheVersion;
        }

        public boolean isCacheReady() {
            return cacheReady;
        }

        public boolean isFastaReady() {
            return fastaReady;
        }

        public boolean isFastaIndexReady() {
            return fastaIndexReady;
        }

        public List<String> getMissingResources() {
            return missingResources;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }

    public static class ProviderCapabilityStatus {

        private final String provider;
        private final List<String> supportedAssemblies;
        private final String configuredResourceAssembly;
        private final String queryAssembly;
        private final String behavior;
        private final String unsupportedStatus;
        private final boolean noRecordOnlyAfterValidQuery;

        public ProviderCapabilityStatus(String provider,
                                        List<String> supportedAssemblies,
                                        String configuredResourceAssembly,
                                        String queryAssembly,
                                        String behavior,
                                        String unsupportedStatus,
                                        boolean noRecordOnlyAfterValidQuery) {
            this.provider = provider;
            this.supportedAssemblies = immutableCopy(supportedAssemblies);
            this.configuredResourceAssembly = configuredResourceAssembly;
            this.queryAssembly = queryAssembly;
            this.behavior = behavior;
            this.unsupportedStatus = unsupportedStatus;
            this.noRecordOnlyAfterValidQuery = noRecordOnlyAfterValidQuery;
        }

        public String getProvider() {
            return provider;
        }

        public List<String> getSupportedAssemblies() {
            return supportedAssemblies;
        }

        public String getConfiguredResourceAssembly() {
            return configuredResourceAssembly;
        }

        public String getQueryAssembly() {
            return queryAssembly;
        }

        public String getBehavior() {
            return behavior;
        }

        public String getUnsupportedStatus() {
            return unsupportedStatus;
        }

        public boolean isNoRecordOnlyAfterValidQuery() {
            return noRecordOnlyAfterValidQuery;
        }
    }
}
