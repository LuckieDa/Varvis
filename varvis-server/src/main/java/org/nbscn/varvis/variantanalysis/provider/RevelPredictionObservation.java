package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Re-reads supplied publisher rows; it neither loads a dataset nor replaces a missing REVEL score. */
final class RevelPredictionObservation {
    static final String INPUT_PROFILE = "REVEL_RAW_1_3_NATIVE_CDS_VERIFIED@1.0.0";
    private RevelPredictionObservation() { }

    static PredictionModelInput read(JsonNode context, TranscriptConsequence tx, ObjectNode observation,
                                      JsonNode policy, ObjectNode resolved, RuntimeEvidenceV2Adapter adapter) {
        JsonNode model = observation.path("transcriptModel"), binding = policy.path("transcriptModelBinding");
        if (!model.isObject() || !model.path("modelId").equals(binding.path("modelId"))
                || !model.path("modelVersion").equals(binding.path("modelVersion"))
                || !adapter.canonicalSha256(model).equals(binding.path("canonicalSha256").asText()))
            return blocked("REVEL_CODING_MODEL_IDENTITY_MISMATCH");
        String issue = CodingMissenseResolver.resolve(context, tx, model, resolved, adapter);
        if (issue != null) return blocked(issue);
        JsonNode evidence = observation.path("evidence"), rows = observation.path("rawRecords");
        if (!"SUCCEEDED".equals(evidence.at("/resourceExecution/status").asText())
                || !"AVAILABLE".equals(evidence.at("/resourceAvailability/status").asText()))
            return blocked("REVEL_RESOURCE_NOT_READY");
        if ("NO_RECORD".equals(evidence.at("/queryOutcome/status").asText())) return blocked("REVEL_NO_RECORD");
        if (!"HIT".equals(evidence.at("/queryOutcome/status").asText())
                || !"APPLICABLE".equals(evidence.at("/resourceApplicability/status").asText())
                || !rows.isArray() || rows.isEmpty() || rows.size() > 1000
                || evidence.at("/queryOutcome/recordCount").asInt() != rows.size()
                || evidence.at("/source/sourceRecords").size() != rows.size()
                || !adapter.canonicalSha256(rows).equals(evidence.at("/provenance/sourcePayloadSha256").asText()))
            return blocked("REVEL_RAW_RECORD_IDENTITY_INVALID");
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        String sourceTranscript = policy.at("/revelTranscriptBinding/accession").asText();
        String artifact = policy.at("/resourceBinding/sourceArtifactSha256").asText();
        BigDecimal score = null;
        String originalPosition = null;
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rows) {
            String recordId = row.path("recordId").asText(), raw = row.path("rawCsv").asText();
            if (recordId.isBlank() || !ids.add(recordId) || raw.length() > 16384 || raw.contains("\n") || raw.contains("\r"))
                return blocked("REVEL_RAW_RECORD_IDENTITY_INVALID");
            boolean sourceBound = false;
            for (JsonNode source : evidence.at("/source/sourceRecords"))
                if (recordId.equals(source.path("recordId").asText()) && artifact.equals(source.at("/locator/artifactSha256").asText())) sourceBound = true;
            if (!sourceBound) return blocked("REVEL_SOURCE_RECORD_BINDING_MISMATCH");
            String[] cells = raw.split(",", -1);
            if (cells.length != 9 || !cells[0].equals(allele.path("contig").asText())
                    || !cells[2].equals(allele.path("position").asText())
                    || !cells[3].equals(allele.path("reference").asText()) || !cells[4].equals(allele.path("alternate").asText()))
                return blocked("REVEL_PUBLISHED_ALLELE_MISMATCH");
            if (!cells[1].matches("[1-9][0-9]{0,8}") || originalPosition != null && !originalPosition.equals(cells[1]))
                return blocked("REVEL_PUBLISHED_MAPPING_MISSING_OR_AMBIGUOUS");
            originalPosition = cells[1];
            if (!List.of(cells[8].split("[;&]", -1)).contains(sourceTranscript)) return blocked("REVEL_SOURCE_TRANSCRIPT_MISMATCH");
            if (!cells[5].equals(resolved.path("refAminoAcid").asText()) || !cells[6].equals(resolved.path("altAminoAcid").asText()))
                return blocked("REVEL_SOURCE_AMINO_ACID_MISMATCH");
            BigDecimal value = PredictionClausePolicy.decimal(com.fasterxml.jackson.databind.node.TextNode.valueOf(cells[7]));
            if (value == null) return blocked("REVEL_SCORE_MISSING_OR_INVALID");
            if (score != null && score.compareTo(value) != 0) return blocked("REVEL_RECORD_CONFLICT");
            score = value;
        }
        JsonNode measurement = null;
        for (JsonNode candidate : evidence.path("measurements")) if ("revel.score".equals(candidate.path("metricId").asText())) {
            if (measurement != null) return blocked("REVEL_SCORE_MEASUREMENT_AMBIGUOUS");
            measurement = candidate;
        }
        if (score == null || measurement == null || !INPUT_PROFILE.equals(measurement.path("calibrationProfileId").asText())
                || !"dimensionless".equals(measurement.path("unit").asText())
                || PredictionClausePolicy.decimal(measurement.path("rawValue")) == null
                || score.compareTo(new BigDecimal(measurement.path("rawValue").asText())) != 0
                || !score.stripTrailingZeros().toPlainString().equals(measurement.path("normalizedValue").asText()))
            return blocked("REVEL_RAW_SCORE_MEASUREMENT_MISMATCH");
        resolved.put("sourceOriginalAssembly", "GRCh37").put("sourceOriginalPosition", originalPosition)
                .put("sourcePublishedAssembly", "GRCh38").put("sourceMappingMethod", "REVEL_publisher_liftOver")
                .put("sourceTranscriptAccession", sourceTranscript)
                .put("sourceTranscriptVersionStatus", "UNVERSIONED_SOURCE_NO_VERSION_EQUIVALENCE_CLAIM")
                .put("proteinIdentityMappingStatus", "CURRENT_VERSIONED_CDS_GENOMIC_CODON_AND_STORED_VEP_VERIFIED");
        return new PredictionModelInput(score, null);
    }
    private static PredictionModelInput blocked(String issue) { return new PredictionModelInput(null, issue); }
}
