package org.nbscn.varvis.variantanalysis.domain;

import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.springframework.util.StringUtils;

public enum GenomeAssembly {
    GRCh37,
    GRCh38;

    public static GenomeAssembly require(String value) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidVcfUploadException("genomeAssembly is required; use GRCh38 or GRCh37");
        }
        String normalized = value.trim().replace("_", "").replace("-", "").toLowerCase();
        if ("grch38".equals(normalized) || "hg38".equals(normalized)) {
            return GRCh38;
        }
        if ("grch37".equals(normalized) || "hg19".equals(normalized) || "b37".equals(normalized)) {
            return GRCh37;
        }
        throw new InvalidVcfUploadException("genomeAssembly must be GRCh38/hg38 or GRCh37/hg19");
    }

    public boolean requiresLiftOverTo(GenomeAssembly target) {
        return this != target;
    }
}
