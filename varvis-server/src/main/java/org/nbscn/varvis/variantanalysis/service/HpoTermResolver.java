package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.HpoOntologyTermStatus;

/** Read-only HPO identifier lookup. Implementations must never perform network access. */
public interface HpoTermResolver {

    Resolution resolve(String hpoId);

    boolean isAvailable();

    String getDataVersion();

    final class Resolution {

        private final String requestedHpoId;
        private final String effectiveHpoId;
        private final String replacementHpoId;
        private final String label;
        private final HpoOntologyTermStatus status;
        private final boolean usable;
        private final String warningCode;

        public Resolution(String requestedHpoId,
                          String effectiveHpoId,
                          String replacementHpoId,
                          String label,
                          HpoOntologyTermStatus status,
                          boolean usable,
                          String warningCode) {
            this.requestedHpoId = requestedHpoId;
            this.effectiveHpoId = effectiveHpoId;
            this.replacementHpoId = replacementHpoId;
            this.label = label;
            this.status = status;
            this.usable = usable;
            this.warningCode = warningCode;
        }

        public String getRequestedHpoId() {
            return requestedHpoId;
        }

        public String getEffectiveHpoId() {
            return effectiveHpoId;
        }

        public String getReplacementHpoId() {
            return replacementHpoId;
        }

        public String getLabel() {
            return label;
        }

        public HpoOntologyTermStatus getStatus() {
            return status;
        }

        public boolean isUsable() {
            return usable;
        }

        public String getWarningCode() {
            return warningCode;
        }
    }
}
