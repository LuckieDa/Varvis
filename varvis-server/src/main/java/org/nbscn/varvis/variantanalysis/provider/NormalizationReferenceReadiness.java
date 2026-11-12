package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NormalizationReferenceReadiness {

    private final GenomeAssembly assembly;
    private final String status;
    private final String profileId;
    private final String referenceFileName;
    private final String configuredSha256;
    private final boolean referenceExists;
    private final boolean fastaIndexExists;
    private final int contigCount;
    private final String contigStyle;
    private final List<String> missingResources;
    private final List<String> warnings;

    public NormalizationReferenceReadiness(GenomeAssembly assembly,
                                           String status,
                                           String profileId,
                                           String referenceFileName,
                                           String configuredSha256,
                                           boolean referenceExists,
                                           boolean fastaIndexExists,
                                           int contigCount,
                                           String contigStyle,
                                           List<String> missingResources,
                                           List<String> warnings) {
        this.assembly = assembly;
        this.status = status;
        this.profileId = profileId;
        this.referenceFileName = referenceFileName;
        this.configuredSha256 = configuredSha256;
        this.referenceExists = referenceExists;
        this.fastaIndexExists = fastaIndexExists;
        this.contigCount = Math.max(0, contigCount);
        this.contigStyle = contigStyle;
        this.missingResources = immutableCopy(missingResources);
        this.warnings = immutableCopy(warnings);
    }

    private static List<String> immutableCopy(List<String> values) {
        return values == null || values.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public GenomeAssembly getAssembly() { return assembly; }
    public String getStatus() { return status; }
    public String getProfileId() { return profileId; }
    public String getReferenceFileName() { return referenceFileName; }
    public String getConfiguredSha256() { return configuredSha256; }
    public boolean isReferenceExists() { return referenceExists; }
    public boolean isFastaIndexExists() { return fastaIndexExists; }
    public int getContigCount() { return contigCount; }
    public String getContigStyle() { return contigStyle; }
    public List<String> getMissingResources() { return missingResources; }
    public List<String> getWarnings() { return warnings; }
    public boolean isReady() { return "READY".equals(status); }
}
