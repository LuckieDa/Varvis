package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Automatic facts and qualification gaps, distinct from the legacy reviewed genomic-region bundle.
 * Does not synthesize reviews, policy selections, functionalContext=SUPPORTED, or rule evaluations.
 */
public final class Pm4AutomaticProteinMaterialPreparation {
    public static final String VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final CodingTranscriptMaterialSupply sequence;
    private final UniProtProteinRegionSupply regions;
    private final Pm4ProteinRegionAssessment assessment;

    public Pm4AutomaticProteinMaterialPreparation(ObjectMapper mapper, CodingTranscriptMaterialSupply sequence,
            UniProtProteinRegionSupply regions, Pm4ProteinRegionAssessment assessment) {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.sequence = Objects.requireNonNull(sequence); this.regions = Objects.requireNonNull(regions);
        this.assessment = Objects.requireNonNull(assessment);
    }

    public ObjectNode prepare(ObjectNode context, VariantInterpretation candidate) {
        var result = mapper.createObjectNode().put("schemaVersion", "pm4-automatic-protein-material-draft-v1")
                .put("methodVersion", VERSION).put("reviewStatus", "UNREVIEWED").put("scientificEvaluationPerformed", false)
                .put("status", "BLOCKED").put("clinicalAdoption", false);
        var material = sequence.resolveProteinLengthChange(context, candidate);
        result.set("sequenceMaterial", material.snapshot());
        if (!material.ready()) return finish(result, material.snapshot().path("reasonCode").asText());
        var model = Objects.requireNonNull(material.model());
        result.set("model", model);
        var annotations = regions.resolve(material);
        result.set("proteinAnnotations", annotations);
        if (!"READY".equals(annotations.path("status").asText())) return finish(result, annotations.path("reasonCode").asText());
        var region = assessment.assess(model, annotations);
        result.set("regionAssessment", region);
        result.put("status", "UNKNOWN".equals(region.path("regionState").asText()) ? "PREPARED_WITH_GAPS" : "REGION_FACTS_PREPARED");
        result.putArray("remainingQualification").add("GENERAL_PM4_POLICY_AND_DISEASE_APPLICABILITY")
                .add("RULE_AND_BUSINESS_INTEGRATION_NOT_PERFORMED");
        return finish(result, region.path("reasonCode").asText());
    }

    private ObjectNode finish(ObjectNode result, String reason) {
        result.put("reasonCode", reason);
        result.put("draftId", "pm4-protein-draft-sha256-" + adapter.canonicalSha256(result));
        return result;
    }
}
