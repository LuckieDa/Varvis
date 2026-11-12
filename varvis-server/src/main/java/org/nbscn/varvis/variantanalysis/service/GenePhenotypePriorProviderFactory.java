package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

import java.util.Locale;

public final class GenePhenotypePriorProviderFactory {

    private static final String PHEN2GENE_IMPORT = "PHEN2GENE_IMPORT";
    private static final String PHEN2GENE_LOCAL_KB = "PHEN2GENE_LOCAL_KB";

    private GenePhenotypePriorProviderFactory() {
    }

    public static GenePhenotypePriorProvider create(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.PhenotypePrior phenotypePrior =
                properties == null ? null : properties.getPhenotypePrior();
        String source = phenotypePrior == null ? null : phenotypePrior.getSource();
        if (PHEN2GENE_IMPORT.equals(normalize(source))) {
            return new Phen2GeneImportGenePhenotypePriorProvider(phenotypePrior);
        }
        if (PHEN2GENE_LOCAL_KB.equals(normalize(source))) {
            return new Phen2GeneKnowledgebaseGenePhenotypePriorProvider(phenotypePrior);
        }
        return new LocalGenePhenotypePriorProvider(phenotypePrior);
    }

    private static String normalize(String value) {
        return value == null || value.trim().isEmpty()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }
}
