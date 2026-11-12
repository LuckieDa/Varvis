package org.nbscn.varvis.utils;

import org.nbscn.varvis.dto.variant.response.SmallVariantDTO;
import org.nbscn.varvis.dto.variant.response.StructuralVariantDTO;

import java.util.Map;

public final class VariantAssembler {

    private VariantAssembler() {
    }

    public static SmallVariantDTO toSmallVariant(Map<String, Object> row) {
        SmallVariantDTO dto = new SmallVariantDTO();
        dto.setVariantId(stringValue(firstNonNull(row, "variant_id", "id")));
        dto.setChromosome(stringValue(firstNonNull(row, "chromosome", "chr")));
        dto.setPosition(longValue(firstNonNull(row, "position", "pos", "start_bp")));
        dto.setRef(stringValue(firstNonNull(row, "ref", "ref_allele")));
        dto.setAlt(stringValue(firstNonNull(row, "alt", "alt_allele")));
        dto.setGeneSymbol(stringValue(firstNonNull(row, "gene_symbol", "gene")));
        dto.setVariantType(stringValue(firstNonNull(row, "variant_type", "type")));
        dto.setClinicalSignificance(stringValue(firstNonNull(row, "clinical_significance", "clin_sig")));
        dto.setKnowledgeMatched(boolValue(firstNonNull(row, "knowledge_matched", "knowledgeMatched")));
        dto.setPatientOnly(boolValue(firstNonNull(row, "patient_only", "patientOnly")));
        dto.setKnowledgeClinicalSignificance(
                stringValue(firstNonNull(row, "knowledge_clinical_significance", "knowledgeClinicalSignificance"))
        );
        dto.setHgvsC(stringValue(firstNonNull(row, "hgvs_c")));
        dto.setHgvsP(stringValue(firstNonNull(row, "hgvs_p")));
        dto.setGenotype(stringValue(firstNonNull(row, "genotype")));
        return dto;
    }

    public static StructuralVariantDTO toStructuralVariant(Map<String, Object> row) {
        StructuralVariantDTO dto = new StructuralVariantDTO();
        dto.setVariantId(stringValue(firstNonNull(row, "variant_id", "id")));
        dto.setChromosome(stringValue(firstNonNull(row, "chromosome", "chr")));
        dto.setStartBp(longValue(firstNonNull(row, "start_bp", "start")));
        dto.setEndBp(longValue(firstNonNull(row, "end_bp", "end")));
        dto.setSvType(stringValue(firstNonNull(row, "sv_type", "variant_type")));
        dto.setCopyNumber(intValue(firstNonNull(row, "copy_number")));
        dto.setGeneSymbol(stringValue(firstNonNull(row, "gene_symbol", "gene")));
        dto.setClinicalSignificance(stringValue(firstNonNull(row, "clinical_significance", "clin_sig")));
        dto.setKnowledgeMatched(boolValue(firstNonNull(row, "knowledge_matched", "knowledgeMatched")));
        dto.setPatientOnly(boolValue(firstNonNull(row, "patient_only", "patientOnly")));
        dto.setKnowledgeClinicalSignificance(
                stringValue(firstNonNull(row, "knowledge_clinical_significance", "knowledgeClinicalSignificance"))
        );
        return dto;
    }

    private static Object firstNonNull(Map<String, Object> row, String... keys) {
        if (row == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key != null && row.containsKey(key) && row.get(key) != null) {
                return row.get(key);
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean boolValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = value.toString().trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text)) {
            return false;
        }
        return null;
    }
}
