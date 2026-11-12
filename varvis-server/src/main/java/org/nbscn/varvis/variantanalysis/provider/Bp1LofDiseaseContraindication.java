package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.lang.Nullable;

/** A named, positive LoF-disease relation from an admitted complete source.
 * Unknown is never absence, and neither sequence truncation nor this fact awards BP1. */
final class Bp1LofDiseaseContraindication {
    static final String VERSION = "1.0.0";
    static final String CRITERION = "ACMG_AMP_2015:BP1";
    private static final String PREFIX = "BP1_LOF_CONTRAINDICATION_";
    private static final Pattern NAMED_RELATION = Pattern.compile(
            "\\(e\\.g\\. loss of function variants in ([A-Za-z0-9_.-]{1,100}) related to ([^()]{1,200})\\)");
    private Bp1LofDiseaseContraindication() { }

    static final class Assessment {
        private final ObjectNode value;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private Assessment(ObjectNode value) { this.value = value.deepCopy(); }
        boolean established() { return "CONTRAINDICATION_ESTABLISHED".equals(value.path("status").asText()); }
        boolean sourceChecked() { return "CHECKED".equals(value.path("sourceCheckStatus").asText()); }
        ObjectNode snapshot() { return value.deepCopy(); }
    }

    static Assessment assess(ObjectMapper mapper, @Nullable PublishedCriterionKnowledge catalog, ObjectNode context,
            @Nullable CodingTranscriptMaterialSupply.Material actualStructureMaterial,
            @Nullable Bp1TruncatingConsequence.Observation actualSequenceObservation) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp1-lof-disease-contraindication-v1")
                .put("methodVersion", VERSION).put("status", "UNKNOWN")
                .put("reasonCode", PREFIX + "SOURCE_OR_SEQUENCE_REQUIRED").put("sourceCheckStatus", "UNAVAILABLE")
                .put("absenceQualificationGranted", false).put("scoringPermissionGranted", false)
                .put("lossOfFunctionEstablishedForQueryAllele", false).put("clinicalValidationCompleted", false);
        try {
            if (catalog == null) throw new IllegalArgumentException(PREFIX + "SOURCE_REQUIRED");
            if (actualStructureMaterial == null) throw new IllegalArgumentException(PREFIX + "STRUCTURE_REQUIRED");
            if (actualSequenceObservation == null) throw new IllegalArgumentException(PREFIX + "SEQUENCE_OBSERVATION_REQUIRED");
            require(actualStructureMaterial.ready(), "STRUCTURE_REQUIRED");
            require(actualSequenceObservation.ready() && actualSequenceObservation.sequenceConfirmed(), "SEQUENCE_OBSERVATION_REQUIRED");
            out.put("sourceCheckStatus", "INVALID");
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            adapter.validateCompleteContext(context);
            require(adapter.canonicalContextId(context).equals(context.path("contextId").asText()), "CONTEXT_ID_MISMATCH");
            var audit = actualStructureMaterial.snapshot(); var model = actualStructureMaterial.model();
            if (model == null) throw new IllegalArgumentException(PREFIX + "STRUCTURE_REQUIRED");
            require("READY".equals(audit.path("status").asText())
                    && "EXACT_TRANSCRIPT_LOCAL_STRUCTURE_SUPPLY".equals(audit.path("method").asText())
                    && CodingTranscriptMaterialSupply.STRUCTURE_VERSION.equals(audit.path("version").asText())
                    && "EXACT_MODEL_REFERENCE_AND_SELECTED_ANNOTATION_BOUND".equals(audit.path("reasonCode").asText())
                    && audit.path("alleleEffectVerified").isBoolean() && !audit.path("alleleEffectVerified").asBoolean(),
                    "STRUCTURE_PURPOSE_MISMATCH");
            require(CodingTranscriptMapping.modelIssue(model, context) == null, "MODEL_CONTEXT_MISMATCH");
            String modelSha = adapter.canonicalSha256(model), structureSha = adapter.canonicalSha256(audit);
            String contextSha = adapter.canonicalSha256(context);
            var proof = audit.path("verification"); var allele = context.at("/variant/evidenceQueryAllele");
            require(modelSha.equals(audit.path("modelCanonicalSha256").asText())
                    && CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()).equals(proof.path("cdsSha256").asText())
                    && proof.path("fullSplicedTranscriptSha256").asText().matches("[a-f0-9]{64}")
                    && proof.path("fullSplicedTranscriptSha256").equals(model.at("/sequenceVerification/fullSplicedTranscriptSha256"))
                    && proof.at("/referenceIdentity/expectedSha256").asText().matches("[a-f0-9]{64}")
                    && proof.at("/referenceIdentity/expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")),
                    "REFERENCE_BINDING_MISMATCH");
            var sequence = actualSequenceObservation.snapshot(); var sb = sequence.path("binding");
            require("TRUNCATION_CANDIDATE".equals(sequence.path("status").asText())
                    && context.path("contextId").equals(sequence.path("contextId"))
                    && contextSha.equals(sequence.path("contextCanonicalSha256").asText())
                    && allele.equals(sequence.path("queryAllele"))
                    && modelSha.equals(sequence.path("nativeModelSha256").asText())
                    && structureSha.equals(sequence.path("structureMaterialSha256").asText())
                    && context.path("contextId").equals(sb.path("contextId"))
                    && contextSha.equals(sb.path("contextSha256").asText())
                    && adapter.canonicalSha256(allele).equals(sb.path("alleleSha256").asText())
                    && modelSha.equals(sb.path("modelCanonicalSha256").asText())
                    && structureSha.equals(sb.path("structureMaterialSha256").asText())
                    && model.path("transcript").equals(sb.path("transcript"))
                    && model.path("protein").equals(sb.path("protein"))
                    && proof.path("cdsSha256").equals(sb.path("referenceCdsSha256"))
                    && proof.path("fullSplicedTranscriptSha256").equals(sb.path("fullSplicedReferenceTranscriptSha256"))
                    && context.at("/variant/normalization/referenceSequenceSha256").equals(sb.path("referenceSequenceSha256"))
                    && !sequence.path("candidateId").asText().isBlank()
                    && sequence.path("candidateId").equals(sb.path("candidateId")), "SEQUENCE_BINDING_MISMATCH");
            out.set("sequenceBinding", sb.deepCopy());
            out.set("queryAllele", allele.deepCopy());
            out.put("sourceCheckStatus", "UNAVAILABLE");
            var selection = catalog.select(CRITERION, context, model);
            out.set("sourceSelectionBinding", selection.path("binding").deepCopy());
            String status = selection.path("status").asText();
            require("READY".equals(status) || "BLOCKED".equals(status)
                    && "UNSUPPORTED_CONDITIONS".equals(selection.at("/record/status").asText())
                    && "BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED".equals(selection.path("reasonCode").asText()),
                    "SOURCE_SELECTION_UNAVAILABLE");
            Bp1PublishedPolicy.validateTruncatingSource(selection);
            var record = selection.path("record"); var specification = selection.path("specification");
            var original = specification.path("original"); var source = specification.path("source");
            out.put("sourceCheckStatus", "INVALID");
            require(source.path("rawSha256").asText().matches("[a-f0-9]{64}")
                    && admittedSourceUrl(source.path("sourceUrl").asText(), original.path("entId").asText()), "SOURCE_ORIGIN_UNSUPPORTED");
            require(adapter.canonicalSha256(original).equals(specification.path("entitySha256").asText())
                    && adapter.canonicalSha256(original.at(record.path("clausePointer").asText()))
                    .equals(record.path("clauseSha256").asText()), "SOURCE_PIN_MISMATCH");
            String notes = original.at("/entContent/notes").asText();
            String scopePointer = record.path("scopePointer").asText();
            var binding = out.putObject("sourceBinding");
            binding.put("catalogRawSha256", catalog.identity().path("rawSha256").asText())
                    .put("catalogPolicyId", catalog.identity().path("policyId").asText())
                    .put("catalogPolicyVersion", catalog.identity().path("policyVersion").asText())
                    .put("sourceRawSha256", source.path("rawSha256").asText())
                    .put("sourceUrl", source.path("sourceUrl").asText())
                    .put("sourceJsonPointer", source.path("jsonPointer").asText());
            binding.put("recordId", record.path("recordId").asText()).put("specificationKey", specification.path("specificationKey").asText())
                    .put("entitySha256", specification.path("entitySha256").asText())
                    .put("clausePointer", record.path("clausePointer").asText()).put("clauseSha256", record.path("clauseSha256").asText())
                    .put("scopePointer", scopePointer).put("relationPointer", "/entContent/notes")
                    .put("relationTextUtf8Sha256", CodingTranscriptMaterialSupply.sha(notes));
            var boundedScope = binding.putObject("scope");
            for (String field : java.util.List.of("hgncId", "mondoId", "moi", "assembly", "transcript"))
                boundedScope.put(field, record.path("scope").path(field).asText());
            var named = NAMED_RELATION.matcher(normalize(notes));
            require(named.find(), "NAMED_RELATION_UNSUPPORTED");
            String subject = named.group(1), disease = named.group(2);
            require(!named.find(), "NAMED_RELATION_AMBIGUOUS");
            var scopedGene = original.at(scopePointer.substring(0, scopePointer.lastIndexOf("/diseases/")));
            out.putObject("sourceMention").put("namedGeneSymbol", subject).put("diseaseLabel", disease)
                    .put("selectedGeneSymbol", scopedGene.path("gene").asText())
                    .put("subjectMatchesSelectedScope", subject.equals(scopedGene.path("gene").asText()));
            if (!subject.equals(scopedGene.path("gene").asText()))
                return new Assessment(out.put("sourceCheckStatus", "CHECKED")
                        .put("reasonCode", PREFIX + "NAMED_SUBJECT_SCOPE_MISMATCH"));
            String hgnc = ""; int subjects = 0;
            for (var gene : original.at("/ld/Gene"))
                if (subject.equals(gene.at("/entContent/HGNC/symbol").asText())) {
                    subjects++; hgnc = gene.at("/entContent/HGNC/hgnc_id").asText();
                }
            require(subjects == 1 && hgnc.matches("HGNC:[1-9][0-9]*"), "NAMED_SUBJECT_IDENTITY_AMBIGUOUS");
            require(subject.equals(context.at("/gene/symbol").asText()) && hgnc.equals(context.at("/gene/hgncId").asText())
                    && hgnc.equals(record.at("/scope/hgncId").asText()), "NAMED_SUBJECT_CONTEXT_MISMATCH");
            out.putObject("relation").put("hgncId", hgnc).put("geneSymbol", subject)
                    .put("molecularMechanism", "LOSS_OF_FUNCTION").put("diseaseLabel", disease)
                    .put("basis", "EXPLICIT_NAMED_RELATION_IN_COMPLETE_PUBLISHED_SOURCE")
                    .put("sourceInterpretationVersion", VERSION);
            return new Assessment(out.put("sourceCheckStatus", "CHECKED").put("status", "CONTRAINDICATION_ESTABLISHED")
                    .put("reasonCode", PREFIX + "EXPLICIT_NAMED_LOF_DISEASE_RELATION"));
        } catch (IllegalArgumentException invalid) {
            String reason = invalid.getMessage();
            return new Assessment(out.put("reasonCode", reason != null && reason.matches("[A-Z][A-Z0-9_]+")
                    ? reason : PREFIX + "INVALID_INPUT"));
        }
    }

    private static boolean admittedSourceUrl(String value, String specification) {
        try {
            var uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"cspec.genome.network".equals(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) return false;
            String base = "/cspec/SequenceVariantInterpretation/id";
            if ((base + "/" + specification).equals(uri.getPath())) return true;
            if (!base.equals(uri.getPath()) || uri.getRawQuery() == null) return false;
            for (String parameter : uri.getRawQuery().split("&")) {
                int split = parameter.indexOf('=');
                if (split > 0 && "ids".equals(URLDecoder.decode(parameter.substring(0, split), StandardCharsets.UTF_8)))
                    return Arrays.asList(URLDecoder.decode(parameter.substring(split + 1), StandardCharsets.UTF_8).split(",", -1)).contains(specification);
            }
            return false;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static String normalize(String text) { return text.replace('\u00a0', ' ').strip().replaceAll("\\s+", " "); }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(PREFIX + reason);
    }
}
