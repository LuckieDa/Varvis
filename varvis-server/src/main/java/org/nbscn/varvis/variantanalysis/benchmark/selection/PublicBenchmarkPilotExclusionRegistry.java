package org.nbscn.varvis.variantanalysis.benchmark.selection;

import java.util.LinkedHashSet;
import java.util.Set;

/** Identity-union exclusion boundary for the already used development-smoke cases. */
public class PublicBenchmarkPilotExclusionRegistry {
    private final Set<String> caseIds = new LinkedHashSet<String>();
    private final Set<String> sourceChecksums = new LinkedHashSet<String>();
    private final Set<String> truthCanonicalIdentities = new LinkedHashSet<String>();
    private final Set<String> truthSetFingerprints = new LinkedHashSet<String>();

    public Set<String> getCaseIds() { return caseIds; }
    public Set<String> getSourceChecksums() { return sourceChecksums; }
    public Set<String> getTruthCanonicalIdentities() { return truthCanonicalIdentities; }
    public Set<String> getTruthSetFingerprints() { return truthSetFingerprints; }
}
