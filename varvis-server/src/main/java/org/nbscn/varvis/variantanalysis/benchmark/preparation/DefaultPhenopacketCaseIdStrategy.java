package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.databind.JsonNode;

final class DefaultPhenopacketCaseIdStrategy implements PhenopacketCaseIdStrategy {
    @Override
    public String derive(JsonNode root, PhenopacketImportContext context) {
        if (root != null && root.get("id") != null && !root.get("id").asText().trim().isEmpty()) {
            return root.get("id").asText();
        }
        return null;
    }
}
