package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VepResourceProfileResolution {

    private final GenomeAssembly requestedAssembly;
    private final VepResourceProfile profile;
    private final String status;
    private final String reason;
    private final List<String> warnings;

    private VepResourceProfileResolution(GenomeAssembly requestedAssembly,
                                         VepResourceProfile profile,
                                         String status,
                                         String reason,
                                         List<String> warnings) {
        this.requestedAssembly = requestedAssembly;
        this.profile = profile;
        this.status = status;
        this.reason = reason;
        this.warnings = warnings == null || warnings.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public static VepResourceProfileResolution resolved(GenomeAssembly requestedAssembly,
                                                        VepResourceProfile profile,
                                                        List<String> warnings) {
        return new VepResourceProfileResolution(requestedAssembly, profile, "RESOLVED", null, warnings);
    }

    public static VepResourceProfileResolution unavailable(GenomeAssembly requestedAssembly,
                                                           String reason,
                                                           List<String> warnings) {
        return new VepResourceProfileResolution(requestedAssembly, null, "UNAVAILABLE", reason, warnings);
    }

    public static VepResourceProfileResolution unsupported(String reason) {
        return new VepResourceProfileResolution(null, null, "ASSEMBLY_UNSUPPORTED", reason,
                Collections.<String>emptyList());
    }

    public GenomeAssembly getRequestedAssembly() {
        return requestedAssembly;
    }

    public VepResourceProfile getProfile() {
        return profile;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public boolean isResolved() {
        return profile != null && "RESOLVED".equals(status);
    }
}
