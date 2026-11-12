package org.nbscn.varvis.variantanalysis.domain;

import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.springframework.util.StringUtils;

public enum AnalysisMode {
    WES,
    WGS,
    PANEL;

    public static AnalysisMode require(String value) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidVcfUploadException("analysisMode is required; use WES, WGS or PANEL");
        }
        for (AnalysisMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new InvalidVcfUploadException("analysisMode must be WES, WGS or PANEL");
    }
}
