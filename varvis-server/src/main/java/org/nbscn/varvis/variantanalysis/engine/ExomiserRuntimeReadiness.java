package org.nbscn.varvis.variantanalysis.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Credentials-safe readiness result for the configured Exomiser runtime. */
public class ExomiserRuntimeReadiness {

    private final boolean enabled;
    private final boolean ready;
    private final String status;
    private final String runtimePlatform;
    private final String message;
    private final List<String> missingResources;

    public ExomiserRuntimeReadiness(boolean enabled,
                                    boolean ready,
                                    String status,
                                    String runtimePlatform,
                                    String message,
                                    List<String> missingResources) {
        this.enabled = enabled;
        this.ready = ready;
        this.status = status;
        this.runtimePlatform = runtimePlatform;
        this.message = message;
        this.missingResources = missingResources == null || missingResources.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(missingResources));
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

    public String getRuntimePlatform() {
        return runtimePlatform;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getMissingResources() {
        return missingResources;
    }
}
