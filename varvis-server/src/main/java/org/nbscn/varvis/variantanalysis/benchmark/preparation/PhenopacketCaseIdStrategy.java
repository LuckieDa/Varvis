package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.databind.JsonNode;

public interface PhenopacketCaseIdStrategy {
    String derive(JsonNode root, PhenopacketImportContext context);
}
