package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;

/** Offline exact transcript annotation preparation through the existing reader. No reference scan or adoption. */
public final class Pvs1StructureBatchCli {
    private Pvs1StructureBatchCli() { }

    public static ObjectNode assemble(ObjectMapper mapper, ObjectNode plan, String planSha, GencodeTranscriptIndex index) {
        if (!"pvs1-material-plan-v1".equals(plan.path("schemaVersion").asText())
                || !"1.0.0".equals(plan.path("methodVersion").asText())
                || !plan.path("runtimeEligible").isBoolean() || plan.path("runtimeEligible").asBoolean()
                || !plan.path("clinicalAcceptance").isBoolean() || plan.path("clinicalAcceptance").asBoolean()
                || !planSha.matches("[0-9a-f]{64}") || !plan.path("structureRequests").isArray()
                || plan.path("structureRequests").size() > 10000)
            throw new IllegalArgumentException("PVS1_STRUCTURE_PLAN_INVALID");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var ids = new HashSet<String>();
        // Validate all request identities before any indexed record is read.
        for (var request : plan.path("structureRequests")) {
            if (!(request.path("context") instanceof ObjectNode)
                    || !adapter.canonicalSha256(request.path("context")).equals(request.path("requestId").asText())
                    || !ids.add(request.path("requestId").asText()))
                throw new IllegalArgumentException("PVS1_STRUCTURE_REQUEST_IDENTITY_INVALID");
        }
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-structure-batch-v1").put("methodVersion", "1.0.0")
                .put("planRawSha256", planSha).put("runtimeEligible", false).put("clinicalAcceptance", false)
                .put("sequenceVerified", false).put("coreExecutions", 0);
        out.set("indexBinding", index.identity());
        var results = out.putArray("results");
        for (var request : plan.path("structureRequests")) {
            var item = results.addObject().put("requestId", request.path("requestId").asText());
            var context = (ObjectNode) request.path("context"); item.set("context", context.deepCopy());
            try {
                var annotation = index.annotation(context);
                item.put("status", "AVAILABLE").put("reasonCode", "STRUCTURE_ONLY_RELEVANCE_AND_SEQUENCE_UNVERIFIED")
                        .put("annotationCanonicalSha256", adapter.canonicalSha256(annotation));
                item.set("annotation", annotation);
            } catch (IllegalArgumentException unavailable) {
                String reason = unavailable.getMessage();
                if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{1,120}")) reason = "PVS1_STRUCTURAL_ANNOTATION_INVALID";
                item.put("status", "UNAVAILABLE").put("reasonCode", reason);
            } catch (java.io.IOException unavailable) {
                item.put("status", "UNAVAILABLE").put("reasonCode", "PVS1_STRUCTURAL_ANNOTATION_IO_UNAVAILABLE");
            }
        }
        out.set("historicalChecks", historicalChecks(mapper, plan.path("historicalMaterials")));
        return out;
    }

    /** Data/model compatibility only: no patient context, selection receipt or policy adoption is fabricated. */
    static ArrayNode historicalChecks(ObjectMapper mapper, JsonNode materials) {
        if (!materials.isArray() || materials.size() > 1000)
            throw new IllegalArgumentException("PVS1_HISTORICAL_MATERIALS_INVALID");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var results = mapper.createArrayNode(); var ids = new HashSet<String>();
        for (var material : materials) {
            var policy = material.path("policy"); var original = material.path("originalCandidate");
            String policySha = adapter.canonicalSha256(policy);
            if (!(policy instanceof ObjectNode) || !policySha.equals(material.path("policySha256").asText())
                    || !("pvs1-material-" + policySha).equals(material.path("materialId").asText())
                    || !ids.add(material.path("materialId").asText())
                    || !(original.path("catalog") instanceof ObjectNode catalog)
                    || !(original.path("policies") instanceof ArrayNode policies)
                    || !java.util.stream.StreamSupport.stream(policies.spliterator(), false).anyMatch(policy::equals))
                throw new IllegalArgumentException("PVS1_HISTORICAL_MATERIAL_IDENTITY_INVALID");
            var result = results.addObject().put("materialId", material.path("materialId").asText())
                    .put("scope", "OFFLINE_MODEL_AND_CATALOG_COMPATIBILITY_ONLY").put("runtimeEligible", false);
            String issue = new AcmgSpecificationSelector(mapper, catalog,
                    original.path("catalogSha256").asText(), policies).configurationIssue();
            result.put("catalogReasonCode", issue == null ? "CATALOG_CONFIGURATION_VALID" : issue);
            var model = material.at("/modelMaterial/model");
            String modelIssue = "POLICY_MODEL_BYTES_NOT_VERIFIED";
            if (model instanceof ObjectNode) {
                String modelSha = adapter.canonicalSha256(model);
                modelIssue = "POLICY_MODEL_BINDING_MISMATCH";
                if (modelSha.equals(material.at("/modelMaterial/canonicalSha256").asText())
                        && modelSha.equals(policy.at("/transcriptModelBinding/canonicalSha256").asText())) {
                    // Only the fields required by the existing model validator. No allele/disease evaluation.
                    var context = mapper.createObjectNode();
                    context.putObject("gene").set("hgncId", policy.at("/scope/hgncId"));
                    context.withObject("gene").set("symbol", model.path("geneSymbol"));
                    context.putObject("variant").putObject("evidenceQueryAllele").set("assembly", policy.at("/scope/assembly"));
                    context.withObject("/variant/evidenceQueryAllele").set("contig", model.path("contig"));
                    modelIssue = "POLICY_MODEL_TRANSCRIPT_SCOPE_MISMATCH";
                    for (var transcript : policy.at("/scope/transcripts")) {
                        if ((transcript.path("accession").asText() + "." + transcript.path("version").asText())
                                .equals(model.path("transcript").asText())) {
                            context.set("transcript", transcript.deepCopy());
                            modelIssue = TruncatingPvs1Model.issue(model, context);
                            break;
                        }
                    }
                }
            }
            result.put("modelReasonCode", modelIssue == null ? "MODEL_STRUCTURE_AND_STORED_SEQUENCE_VALID" : modelIssue);
        }
        return results;
    }

    /** Arguments: plan, plan RAW SHA, index directory, manifest RAW SHA, new output. */
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("PVS1_STRUCTURE_ARGUMENTS_REQUIRED");
        var mapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        Path planPath = Path.of(args[0]), output = Path.of(args[4]);
        if (Files.exists(output) || Files.size(planPath) > 64_000_000)
            throw new IllegalArgumentException("PVS1_STRUCTURE_OUTPUT_EXISTS_OR_PLAN_TOO_LARGE");
        byte[] raw; try (var in = Files.newInputStream(planPath)) { raw = in.readNBytes(64_000_001); }
        if (raw.length > 64_000_000 || !CodingTranscriptMaterialSupply.sha(raw).equals(args[1]))
            throw new IllegalArgumentException("PVS1_STRUCTURE_PLAN_SHA_MISMATCH");
        if (!(mapper.readTree(raw) instanceof ObjectNode plan)) throw new IllegalArgumentException("PVS1_STRUCTURE_PLAN_INVALID");
        var index = new GencodeTranscriptIndex(mapper, Path.of(args[2]), args[3]);
        var result = assemble(mapper, plan, args[1], index);
        Files.write(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result), StandardOpenOption.CREATE_NEW);
        System.out.println("PVS1 structure preparation: " + result.path("results").size() + " records; no scientific execution");
    }
}
