package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Service-owned configuration and current local dependency identity for cache reuse. */
final class DeNovoCoreContextBinding {
    static final java.util.List<String> FILES = java.util.List.of("core_material_review.py", "core_source_review.py",
        "core_case_completion.py", "core_material_extraction.py", "core_material_contract.py", "core_material_schema.py",
        "prepare_literature.py", "extract_literature_cases.py", "literature_case_structure.py", "qualify_literature_cases.py",
        "literature_allele_identity.py", "project_core_material.py", "core_evaluation_intake.py", "core_context_review.py",
        "core_case_semantics.py", "core_context_policy.py", "core_local_identity.py", "refseq_identity.py",
        "single_case_scope.py", "supplement_case_review.py", "literature_explanation.py", "core_special_event.py", "core_source_event_discovery.py",
        "core_special_partner.py", "pm3_partner_sources.py", "pm3_partner_qualification.py", "pm3_partner_dependencies.py", "model_literature_contract.py",
        "../ps1_pm5/prepare_reference_evidence.py", "../ps1_pm5/prepare_expert_reference_evidence.py");
    private final ObjectMapper mapper;
    private final Path root, config;
    private final String pin;

    DeNovoCoreContextBinding(ObjectMapper mapper, Path root, Path config, String pin) throws IOException {
        this.mapper = mapper; this.root = root.toRealPath(); this.config = config.toRealPath(); this.pin = pin;
        if (!this.config.startsWith(this.root.resolve("local")) || pin == null || !pin.matches("[a-f0-9]{64}"))
            throw new IOException("DENOVO_CORE_CONFIG_PATH_OR_PIN");
    }

    ObjectNode snapshot() throws IOException {
        byte[] raw = bytes(config, 65536);
        if (!FamilyDeNovoJson.sha256(raw).equals(pin)) throw new IOException("DENOVO_CORE_CONFIG_CHANGED");
        JsonNode c = mapper.readTree(raw);
        if (!"denovo-core-context-config-v1".equals(c.path("schemaVersion").asText())) throw new IOException("DENOVO_CORE_CONFIG_SCHEMA");
        var result = mapper.createObjectNode().put("root", root.toString())
                .put("path", root.relativize(config).toString()).put("sha256", pin);
        ObjectNode state = result.putObject("dependencies");
        pins(c, state);
        var identity = c.path("identity");
        JsonNode mappings = identity.path("refseqMappings");
        if (!mappings.isMissingNode() && (!mappings.isArray() || mappings.size() > 256))
            throw new IOException("DENOVO_CORE_REFSEQ_MAPPING_LIMIT");
        for (JsonNode mapping : mappings) {
            JsonNode manifest = mapper.readTree(bytes(local(mapping.at("/manifest/path").asText()), 65536));
            if (!supportedRefseqMapping(mapping, manifest))
                throw new IOException("DENOVO_CORE_REFSEQ_MAPPING_SCHEMA");
            pins(manifest.path("sources"), state);
        }
        if (identity.has("gencodeData")) stamp(local(identity.path("gencodeData").asText()), state);
        if (identity.has("reference")) {
            var ref = identity.path("reference");
            JsonNode receipt = mapper.readTree(bytes(local(ref.at("/receipt/path").asText()), 1048576)).at("/records/reference");
            Path data = local(ref.path("resourceRoot").asText() + "/" + receipt.path("referenceArtifactLocator").asText());
            JsonNode claim = receipt.path("actualChecksumReceipt");
            if (!ref.path("sha256").asText().equals(claim.path("observedSha256").asText())
                    || Files.size(data) != claim.path("byteLength").asLong(-1)
                    || Files.getLastModifiedTime(data).to(TimeUnit.NANOSECONDS) != claim.path("mtimeNs").asLong(-1))
                throw new IOException("DENOVO_CORE_REFERENCE_RECEIPT_NOT_APPLICABLE");
            stamp(data, state);
            Path fai = Path.of(data.toString() + ".fai");
            if (!FamilyDeNovoJson.sha256(bytes(fai, 1048576)).equals(claim.path("indexSha256").asText()))
                throw new IOException("DENOVO_CORE_REFERENCE_INDEX_CHANGED");
            stamp(fai, state);
        }
        for (String name : java.util.List.of("normalize_public_panel.py", "build_transcript_model.py"))
            state.put("support:" + name, FamilyDeNovoJson.sha256(bytes(root.resolve("scripts/acmg-generalization").resolve(name), 262144)));
        return result;
    }

    private static boolean supportedRefseqMapping(JsonNode mapping, JsonNode manifest) {
        String schema = manifest.path("schemaVersion").asText();
        if ("denovo-refseq-snv-cache-v1".equals(schema)) return true;
        // The shared configuration also supplies short deletion identity to PM3.
        // Bind its originals without treating the deletion as a PS2/PM6 SNV.
        var transcript = mapping.path("transcript");
        var coding = mapping.path("coding");
        return "denovo-refseq-deletion-cache-v1".equals(schema)
                && transcript.asText().matches("NM_[0-9]+\\.[1-9][0-9]*")
                && coding.asText().matches("c\\.[1-9][0-9]*(?:_[1-9][0-9]*)?del")
                && transcript.equals(manifest.path("transcript"))
                && coding.equals(manifest.path("coding"))
                && manifest.path("sources").isObject();
    }

    private void pins(JsonNode node, ObjectNode state) throws IOException {
        if (node.isObject() && node.has("path") && node.has("sha256")) {
            Path path = local(node.path("path").asText());
            String actual = FamilyDeNovoJson.sha256(bytes(path, 16777216));
            if (!actual.equals(node.path("sha256").asText())) throw new IOException("DENOVO_CORE_DEPENDENCY_CHANGED");
            state.put(root.relativize(path).toString(), actual);
        } else if (node.isContainerNode()) for (JsonNode child : node) pins(child, state);
    }
    private Path local(String relative) throws IOException {
        Path p = Path.of(relative);
        if (p.isAbsolute()) throw new IOException("DENOVO_CORE_RELATIVE_PATH_REQUIRED");
        Path actual = root.resolve(p).toRealPath();
        if (!actual.startsWith(root.resolve("local"))) throw new IOException("DENOVO_CORE_LOCAL_PATH_REQUIRED");
        return actual;
    }
    private void stamp(Path path, ObjectNode state) throws IOException {
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        state.putObject(root.relativize(path).toString()).put("bytes", attributes.size())
                .put("mtimeNs", attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)).put("fileKey", String.valueOf(attributes.fileKey()));
    }
    private static byte[] bytes(Path path, int maximum) throws IOException {
        try (var in = Files.newInputStream(path)) {
            byte[] b = in.readNBytes(maximum + 1);
            if (b.length == 0 || b.length > maximum) throw new IOException("DENOVO_CORE_FILE_LIMIT");
            return b;
        }
    }
}
