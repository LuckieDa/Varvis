package org.nbscn.varvis.variantanalysis.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VepReadinessReport {

    private final boolean enabled;
    private final boolean executionEnabled;
    private final boolean executableConfigured;
    private final boolean executableExists;
    private final boolean executableRunnable;
    private final boolean cacheDirConfigured;
    private final boolean cacheDirExists;
    private final boolean fastaConfigured;
    private final boolean fastaExists;
    private final boolean fastaIndexExists;
    private final boolean pluginsDirConfigured;
    private final boolean pluginsDirExists;
    private final boolean lofteeEnabled;
    private final boolean lofteePluginPresent;
    private final String vepVersion;
    private final String ensemblCacheVersion;
    private final String maneVersion;
    private final String lofteeVersion;
    private final String status;
    private final List<String> missingResources;
    private final List<String> warnings;
    private final String explanation;

    public VepReadinessReport(boolean enabled,
                              boolean executionEnabled,
                              boolean executableConfigured,
                              boolean executableExists,
                              boolean executableRunnable,
                              boolean cacheDirConfigured,
                              boolean cacheDirExists,
                              boolean fastaConfigured,
                              boolean fastaExists,
                              boolean fastaIndexExists,
                              boolean pluginsDirConfigured,
                              boolean pluginsDirExists,
                              boolean lofteeEnabled,
                              boolean lofteePluginPresent,
                              String vepVersion,
                              String ensemblCacheVersion,
                              String maneVersion,
                              String lofteeVersion,
                              String status,
                              List<String> missingResources,
                              List<String> warnings,
                              String explanation) {
        this.enabled = enabled;
        this.executionEnabled = executionEnabled;
        this.executableConfigured = executableConfigured;
        this.executableExists = executableExists;
        this.executableRunnable = executableRunnable;
        this.cacheDirConfigured = cacheDirConfigured;
        this.cacheDirExists = cacheDirExists;
        this.fastaConfigured = fastaConfigured;
        this.fastaExists = fastaExists;
        this.fastaIndexExists = fastaIndexExists;
        this.pluginsDirConfigured = pluginsDirConfigured;
        this.pluginsDirExists = pluginsDirExists;
        this.lofteeEnabled = lofteeEnabled;
        this.lofteePluginPresent = lofteePluginPresent;
        this.vepVersion = vepVersion;
        this.ensemblCacheVersion = ensemblCacheVersion;
        this.maneVersion = maneVersion;
        this.lofteeVersion = lofteeVersion;
        this.status = status;
        this.missingResources = immutableCopy(missingResources);
        this.warnings = immutableCopy(warnings);
        this.explanation = explanation;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isExecutionEnabled() {
        return executionEnabled;
    }

    public boolean isExecutableConfigured() {
        return executableConfigured;
    }

    public boolean isExecutableExists() {
        return executableExists;
    }

    public boolean isExecutableRunnable() {
        return executableRunnable;
    }

    public boolean isCacheDirConfigured() {
        return cacheDirConfigured;
    }

    public boolean isCacheDirExists() {
        return cacheDirExists;
    }

    public boolean isFastaConfigured() {
        return fastaConfigured;
    }

    public boolean isFastaExists() {
        return fastaExists;
    }

    public boolean isFastaIndexExists() {
        return fastaIndexExists;
    }

    public boolean isPluginsDirConfigured() {
        return pluginsDirConfigured;
    }

    public boolean isPluginsDirExists() {
        return pluginsDirExists;
    }

    public boolean isLofteeEnabled() {
        return lofteeEnabled;
    }

    public boolean isLofteePluginPresent() {
        return lofteePluginPresent;
    }

    public String getVepVersion() {
        return vepVersion;
    }

    public String getEnsemblCacheVersion() {
        return ensemblCacheVersion;
    }

    public String getManeVersion() {
        return maneVersion;
    }

    public String getLofteeVersion() {
        return lofteeVersion;
    }

    public String getStatus() {
        return status;
    }

    public List<String> getMissingResources() {
        return missingResources;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public String getExplanation() {
        return explanation;
    }

    private static List<String> immutableCopy(List<String> source) {
        return source == null || source.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(source));
    }
}
