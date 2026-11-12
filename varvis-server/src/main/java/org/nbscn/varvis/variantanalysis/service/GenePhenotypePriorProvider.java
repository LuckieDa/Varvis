package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;

public interface GenePhenotypePriorProvider {

    GenePhenotypePriorSummary summarize(PhenotypeAwareSummary phenotypeSummary);
}
