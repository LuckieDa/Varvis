package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

/** Server-pinned, task-specific checksum receipt. Never accepted from an HTTP declaration.
 * Large files are hashed by the controlled preflight, not repeatedly by variant queries. */
public final class PredictionLocalResourceBinding {
    private final ObjectNode manifest;
    private final String manifestSha;
    private PredictionLocalResourceBinding(ObjectNode manifest, String sha) { this.manifest = manifest; manifestSha = sha; }
    public static PredictionLocalResourceBinding load(ObjectMapper mapper, Path file, String expectedSha) {
        try {
            if (file == null || expectedSha == null || !expectedSha.matches("[a-f0-9]{64}") || Files.size(file) > 65536)
                throw new IllegalArgumentException("PREDICTION_LOCAL_BINDING_INVALID");
            byte[] bytes = Files.readAllBytes(file);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!sha.equals(expectedSha) || !(mapper.readTree(bytes) instanceof ObjectNode value)
                    || !"prediction-local-binding-v1".equals(value.path("schemaVersion").asText()))
                throw new IllegalArgumentException("PREDICTION_LOCAL_BINDING_INVALID");
            return new PredictionLocalResourceBinding(value.deepCopy(), sha);
        } catch (Exception invalid) { throw new IllegalArgumentException("PREDICTION_LOCAL_BINDING_INVALID", invalid); }
    }
    public ObjectNode identity() {
        var resource = ((ObjectNode) manifest.path("resource")).deepCopy();
        resource.put("localBindingSha256", manifestSha); return resource;
    }
    /** Metadata only; does not reopen the score table or FASTA. */
    JsonNode savedVerification() { return manifest.path("verification").deepCopy(); }
    String savedPurpose() { return manifest.path("purpose").asText(); }
    public java.util.List<NormalizedVariantKey> registeredControls() {
        var keys = new java.util.ArrayList<NormalizedVariantKey>();
        for (var c : manifest.path("controls")) keys.add(new NormalizedVariantKey(c.path("genomeAssembly").asText(),
                c.path("chromosome").asText(), c.path("position").asInt(), c.path("reference").asText(), c.path("alternate").asText()));
        return java.util.List.copyOf(keys);
    }
    public String issue(Path data, NormalizedVariantKey key, JsonNode resource, String dataClass) {
        return issue(data, key, resource, dataClass, false);
    }
    /** Explicit successor purpose; never grants the historical saved-control calibration entry new scope. */
    String generalIssue(Path data, NormalizedVariantKey key, JsonNode resource, String dataClass) {
        return issue(data, key, resource, dataClass, true);
    }
    private String issue(Path data, NormalizedVariantKey key, JsonNode resource, String dataClass, boolean general) {
        if (!identity().equals(resource)) return "PREDICTION_RESOURCE_BINDING_MISMATCH";
        if (general ? !(java.util.Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(dataClass)
                && "NONCOMMERCIAL_LOCAL_RESEARCH_SELECTED_VARIANTS".equals(manifest.path("purpose").asText()))
                : !"SYNTHETIC".equals(dataClass) || !"NONCOMMERCIAL_LOCAL_RESEARCH_PUBLIC_CONTROLS_ONLY".equals(manifest.path("purpose").asText()))
            return "USE_RESTRICTION_CONFLICT";
        if (!"CC-BY-NC-SA-4.0".equals(resource.at("/license/id").asText())
                || !resource.at("/license/textSha256").asText().matches("[a-f0-9]{64}")
                || !manifest.at("/verification/rawHeader").toString().contains("Licensed under CC BY-NC-SA 4.0 license")) return "LICENSE_SOURCE_UNBOUND";
        var proof = manifest.path("verification");
        if (!resource.path("sha256").equals(proof.at("/runtimeDataArtifact/sha256"))
                || !resource.path("indexSha256").equals(proof.at("/runtimeIndexArtifact/sha256"))
                || !proof.at("/origin/runtimeDataArtifact/sha256").equals(resource.path("sha256"))
                || !proof.at("/origin/runtimeIndexArtifact/sha256").equals(resource.path("indexSha256"))) return "PREDICTION_CHECKSUM_RECEIPT_MISMATCH";
        boolean allowed = false;
        for (var control : manifest.path("controls")) if (key.getGenomeAssembly().equals(control.path("genomeAssembly").asText())
                && key.getChromosome().equals(control.path("chromosome").asText()) && key.getPosition() == control.path("position").asLong()
                && key.getReference().equals(control.path("reference").asText()) && key.getAlternate().equals(control.path("alternate").asText())) allowed = true;
        if (!general && !allowed) return "PREDICTION_PUBLIC_CONTROL_NOT_REGISTERED";
        try {
            if (data == null || !stamp(data, proof.path("runtimeDataArtifact"))
                    || !stamp(data.resolveSibling(data.getFileName() + ".tbi"), proof.path("runtimeIndexArtifact"))) return "PREDICTION_RESOURCE_FILE_CHANGED";
            Path reference = Path.of(manifest.path("referencePath").asText());
            if (!stamp(reference, manifest.path("reference")) || !stamp(Path.of(reference + ".fai"), manifest.path("referenceIndex")))
                return "PREDICTION_REFERENCE_FILE_CHANGED";
            try (var fasta = new IndexedFastaSequenceFile(reference)) {
                String contig = key.getChromosome();
                if (!fasta.getIndex().hasIndexEntry(contig)) contig = "chr" + contig;
                if (!fasta.getIndex().hasIndexEntry(contig)) return "PREDICTION_REFERENCE_CONTIG_MISSING";
                String ref = new String(fasta.getSubsequenceAt(contig, key.getPosition(), key.getPosition()).getBases(), java.nio.charset.StandardCharsets.US_ASCII);
                if (!key.getReference().equalsIgnoreCase(ref)) return "PREDICTION_REFERENCE_ALLELE_MISMATCH";
            }
            return null;
        } catch (Exception unavailable) { return "PREDICTION_RESOURCE_OR_REFERENCE_UNREADABLE"; }
    }
    private static boolean stamp(Path file, JsonNode expected) throws java.io.IOException {
        return Files.isRegularFile(file) && Files.isReadable(file) && Files.size(file) == expected.path("sizeBytes").asLong(-1)
                && Files.getLastModifiedTime(file).toMillis() == expected.path("mtimeMillis").asLong(-1);
    }
}
