package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VepJsonOutputParser {

    private final ObjectMapper objectMapper;

    public VepJsonOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<TranscriptConsequence> parse(Path outputJson, String source, String version) throws IOException {
        List<TranscriptConsequence> annotations = new ArrayList<TranscriptConsequence>();
        try (BufferedReader reader = Files.newBufferedReader(outputJson, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        addConsequences(item, source, version, annotations);
                    }
                } else {
                    addConsequences(node, source, version, annotations);
                }
            }
        }
        return annotations;
    }

    public VepParseResult parseByVariantId(Path outputJson, String source, String version) throws IOException {
        Map<String, List<TranscriptConsequence>> annotationsByVariantId =
                new LinkedHashMap<String, List<TranscriptConsequence>>();
        List<String> warnings = new ArrayList<String>();
        try (BufferedReader reader = Files.newBufferedReader(outputJson, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(line);
                    if (node.isArray()) {
                        for (JsonNode item : node) {
                            addRecordByVariantId(item, source, version, annotationsByVariantId, warnings);
                        }
                    } else {
                        addRecordByVariantId(node, source, version, annotationsByVariantId, warnings);
                    }
                } catch (IOException ex) {
                    warnings.add("Could not parse VEP JSON line " + lineNumber + ": " + ex.getMessage());
                }
            }
        }
        return new VepParseResult(annotationsByVariantId, warnings);
    }

    private void addRecordByVariantId(JsonNode record,
                                      String source,
                                      String version,
                                      Map<String, List<TranscriptConsequence>> annotationsByVariantId,
                                      List<String> warnings) {
        String variantId = text(record, "id");
        if (!StringUtils.hasText(variantId)) {
            warnings.add("VEP JSON record has no VCF ID; it cannot be mapped back to a candidate.");
            return;
        }
        List<TranscriptConsequence> annotations = new ArrayList<TranscriptConsequence>();
        addConsequences(record, source, version, annotations);
        if (annotations.isEmpty()) {
            warnings.add("VEP JSON record for " + variantId + " contains no transcript consequences.");
            return;
        }
        List<TranscriptConsequence> existing = annotationsByVariantId.get(variantId);
        if (existing == null) {
            annotationsByVariantId.put(variantId, annotations);
        } else {
            existing.addAll(annotations);
        }
    }

    private void addConsequences(JsonNode record,
                                 String source,
                                 String version,
                                 List<TranscriptConsequence> annotations) {
        JsonNode transcriptConsequences = record.get("transcript_consequences");
        if (transcriptConsequences == null || !transcriptConsequences.isArray()) {
            return;
        }
        for (JsonNode transcript : transcriptConsequences) {
            var consequence = new TranscriptConsequence(
                    text(transcript, "gene_symbol"),
                    text(transcript, "gene_id"),
                    text(transcript, "transcript_id"),
                    text(transcript, "hgvsc"),
                    text(transcript, "hgvsp"),
                    consequence(transcript),
                    text(transcript, "gene_id"),
                    text(transcript, "transcript_id"),
                    text(transcript, "biotype"),
                    text(transcript, "impact"),
                    text(transcript, "exon"),
                    text(transcript, "intron"),
                    bool(transcript, "canonical"),
                    presentFlag(transcript, "mane_select", "MANE_SELECT"),
                    presentFlag(transcript, "mane_plus_clinical", "MANE_PLUS_CLINICAL"),
                    proteinPosition(transcript),
                    text(transcript, "amino_acids"),
                    text(transcript, "codons"),
                    text(transcript, "lof", "LoF"),
                    text(transcript, "lof_filter", "LoF_filter"),
                    text(transcript, "lof_flags", "LoF_flags"),
                    source,
                    "COMPLETED",
                    version,
                    Instant.now()
            );
            annotations.add(synonymousPosition(record, transcript, consequence));
        }
    }

    private TranscriptConsequence synonymousPosition(JsonNode record, JsonNode transcript, TranscriptConsequence consequence) {
        if (!IntronicSplicePolicy.synonymous(consequence) || !"116.0".equals(consequence.getVersion())) return consequence;
        try {
            String raw = text(transcript, "nearestexonjb", "NearestExonJB", "nearest_exon_jb");
            if (raw == null) return consequence;
            String[] fields = raw.split("\\|", -1);
            if (fields.length != 4 || !fields[1].matches("[0-9]+") || !fields[3].matches("[1-9][0-9]*")) return consequence;
            long distance = Long.parseLong(fields[1]), length = Long.parseLong(fields[3]);
            if (distance > (length - 1) / 2 || length > Integer.MAX_VALUE) return consequence;
            long startOffset;
            if ("start".equals(fields[2])) startOffset = distance;
            else if ("end".equals(fields[2])) startOffset = length - 1 - distance;
            else if ("start_end".equals(fields[2]) && distance * 2 == length - 1) startOffset = distance;
            else return consequence;
            JsonNode start = record.path("start"), end = record.path("end"), strand = transcript.path("strand");
            if (!start.isIntegralNumber() || !end.isIntegralNumber() || !strand.isIntegralNumber()
                    || start.longValue() != end.longValue() || record.path("strand").asInt() != 1) return consequence;
            String alleles = text(record, "allele_string");
            if (alleles == null || !alleles.matches("[ACGT]/[ACGT]")) return consequence;
            String alt = alleles.substring(2);
            if (!alt.equals(text(transcript, "variant_allele"))) return consequence;
            var proof = new org.nbscn.varvis.variantanalysis.domain.SynonymousExonPosition(
                    text(record, "assembly_name"), text(record, "seq_region_name"), start.longValue(),
                    alleles.substring(0, 1), alt, consequence.getVersionedTranscriptId(), fields[0], strand.intValue(),
                    start.longValue() - startOffset, start.longValue() - startOffset + length - 1,
                    raw, consequence.getVersion(), org.nbscn.varvis.variantanalysis.domain.SynonymousExonPosition.PLUGIN_SHA256);
            return consequence.withSynonymousExonPosition(proof);
        } catch (IllegalArgumentException invalid) {
            return consequence; // No guessed or partially valid position enters a BP7 decision.
        }
    }

    private String consequence(JsonNode transcript) {
        JsonNode terms = transcript.get("consequence_terms");
        if (terms == null) {
            return null;
        }
        if (terms.isArray()) {
            List<String> values = new ArrayList<String>();
            Iterator<JsonNode> iterator = terms.elements();
            while (iterator.hasNext()) {
                String value = iterator.next().asText(null);
                if (StringUtils.hasText(value)) {
                    values.add(value);
                }
            }
            return values.isEmpty() ? null : String.join("&", values);
        }
        return terms.asText(null);
    }

    private String proteinPosition(JsonNode transcript) {
        String start = text(transcript, "protein_start");
        String end = text(transcript, "protein_end");
        if (!StringUtils.hasText(start)) {
            return end;
        }
        if (!StringUtils.hasText(end) || start.equals(end)) {
            return start;
        }
        return start + "-" + end;
    }

    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return "1".equals(text)
                || "YES".equalsIgnoreCase(text)
                || "true".equalsIgnoreCase(text);
    }

    private Boolean presentFlag(JsonNode node, String... fields) {
        String value = text(node, fields);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if ("0".equals(value) || "false".equalsIgnoreCase(value) || "NO".equalsIgnoreCase(value)) {
            return false;
        }
        return true;
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }
}
