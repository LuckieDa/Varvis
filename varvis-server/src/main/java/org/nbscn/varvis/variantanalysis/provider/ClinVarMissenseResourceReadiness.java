package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Verify the actual bounded-query resources before attaching their registered identities. */
public final class ClinVarMissenseResourceReadiness {
    private ClinVarMissenseResourceReadiness() { }
    private static final Map<Path, Verified> VERIFIED = new HashMap<>();
    private record Verified(long bytes, java.nio.file.attribute.FileTime modified, String sha256) { }

    public static ObjectNode identityPolicy(ObjectMapper mapper) {
        try (var in = ClinVarMissenseResourceReadiness.class.getResourceAsStream("/acmg/ps1-pm5-resource-identities-v1.json")) {
            return (ObjectNode) mapper.readTree(Objects.requireNonNull(in));
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PS1_PM5_RESOURCE_IDENTITY_POLICY_UNAVAILABLE", invalid); }
    }
    public static void verifyRawCommand(ObjectMapper mapper, List<String> command, Path reference, VariantAnalysisProperties properties) throws Exception {
        require(command.size() == 10 && "--".equals(command.get(3)) && "bash".equals(command.get(4))
                && "--timeout-seconds".equals(command.get(8)) && command.get(9).matches("[1-9][0-9]*"));
        var identity = identityPolicy(mapper).path("rawBusinessInvocation");
        Path wrapper = localCommandFile(command.get(5)), script = localCommandFile(command.get(7));
        require(identity.path("wrapperSha256").asText().equals(hash(wrapper)) && identity.path("scriptSha256").asText().equals(hash(script)));
        Path scripts = Objects.requireNonNull(script.getParent().getParent());
        require(identity.path("rawModuleSha256").asText().equals(hash(scripts.resolve("e3_c2r/spliceai_raw_resource_probe.py"))));
        require(identity.path("rawProfileSha256").asText().equals(hash(scripts.getParent().resolve("docs/execution/E3_C2R_SPLICEAI_RAW_RESOURCE_PROFILE.json"))));
        var resolution = new VepResourceProfileResolver(properties).resolve(GenomeAssembly.GRCh38);
        require(resolution.isResolved());
        require(reference.toRealPath().equals(Objects.requireNonNull(resolution.getProfile()).getFasta().toRealPath()));
    }
    private static Path localCommandFile(String argument) {
        if (argument.matches("/mnt/[a-z]/.+")) return Path.of(argument.substring(5, 6).toUpperCase(Locale.ROOT) + ":/" + argument.substring(7));
        return Path.of(argument);
    }

    public static ObjectNode verify(ObjectMapper mapper, VariantAnalysisProperties properties, Path sidecar, Path manifest) throws Exception {
        return verify(mapper, properties, sidecar, manifest, true);
    }

    /** The same registered ClinVar pair, without a VEP dependency for CDS-based discovery. */
    public static void verifyClinVar(ObjectMapper mapper, VariantAnalysisProperties properties, Path sidecar, Path manifest) throws Exception {
        verify(mapper, properties, sidecar, manifest, false);
    }

    private static ObjectNode verify(ObjectMapper mapper, VariantAnalysisProperties properties, Path sidecar, Path manifest,
            boolean verifyVep) throws Exception {
        ObjectNode pinned = identityPolicy(mapper);
        var legacy = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper);
        byte[] manifestBytes = Files.readAllBytes(manifest);
        var sidecarIdentity = (ObjectNode) mapper.readTree(manifestBytes);
        verifyManifestIdentity(mapper, manifestBytes, legacy);
        var output = sidecarIdentity.path("output");
        require(Files.size(sidecar) == output.path("byteLength").asLong(-1));
        require(output.path("sha256").asText().equals(hash(sidecar)));
        require(output.path("indexSha256").asText().equals(hash(Path.of(sidecar + ".tbi"))));
        for (var entry : pinned.path("resources")) {
            var identity = entry.path("identity");
            if ("CLINVAR".equals(entry.path("provider").asText())) {
                Path vcf = Objects.requireNonNull(properties.getClinVar().getGrch38File());
                require(properties.getClinVar().isEnabled() && !properties.getClinVar().isAllowVcfInfoFallback());
                require(entry.path("resourceVersion").asText().equals(properties.getClinVar().versionFor("GRCh38")));
                require(Files.size(vcf) == identity.path("dataByteLength").asLong(-1));
                require(identity.path("indexSha256").asText().equals(hash(Path.of(vcf + ".tbi"))));
                try (var reader = new htsjdk.variant.vcf.VCFFileReader(vcf, true)) {
                    var header = reader.getFileHeader();
                    require(header.getOtherHeaderLine("fileDate") != null
                            && "20260321".equals(header.getOtherHeaderLine("fileDate").getValue().replace("-", "")));
                    require(header.getOtherHeaderLine("reference") != null
                            && "GRCh38".equals(header.getOtherHeaderLine("reference").getValue()));
                }
            } else if (verifyVep && "VEP".equals(entry.path("provider").asText())) {
                var resolution = new VepResourceProfileResolver(properties).resolve(GenomeAssembly.GRCh38);
                require(resolution.isResolved()); var vep = Objects.requireNonNull(resolution.getProfile());
                require("READY".equals(new VepResourceValidator(properties).validate(GenomeAssembly.GRCh38).getStatus()));
                require("116.0".equals(vep.getVersion()) && !properties.getVep().isLofteeEnabled());
                require(identity.at("/cache/infoSha256").asText().equals(hash(vep.getCacheDir().resolve("homo_sapiens/116_GRCh38/info.txt"))));
                require(Files.size(vep.getFasta()) == identity.at("/fasta/byteLength").asLong(-1));
                require(identity.at("/fasta/indexSha256").asText().equals(hash(Path.of(vep.getFasta() + ".fai"))));
                require(identity.path("wrapperSha256").asText().equals(hash(Path.of(vep.getExecutable()))));
            }
        }
        sidecarIdentity.put("observedManifestRawSha256", hash(manifest));
        return sidecarIdentity;
    }
    static void verifyManifestIdentity(ObjectMapper mapper, byte[] bytes, com.fasterxml.jackson.databind.JsonNode profile) throws Exception {
        // Text transport may change LF to CRLF; both registered content identities must still match.
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        byte[] normalized = text.replace("\r\n", "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        require(profile.at("/lineage/comparatorManifestRawSha256").asText().equals(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized))));
        require(profile.at("/lineage/comparatorManifestCanonicalSha256").asText().equals(
                new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(mapper.readTree(bytes))));
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("PS1_PM5_RESOURCE_IDENTITY_MISMATCH"); }
    private static synchronized String hash(Path file) throws Exception {
        Path key = file.toAbsolutePath().normalize(); long bytes = Files.size(key); var modified = Files.getLastModifiedTime(key);
        var cached = VERIFIED.get(key);
        if (cached != null && cached.bytes() == bytes && cached.modified().equals(modified)) return cached.sha256();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(key)) { byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read); }
        require(bytes == Files.size(key) && modified.equals(Files.getLastModifiedTime(key)));
        String sha = HexFormat.of().formatHex(digest.digest()); VERIFIED.put(key, new Verified(bytes, modified, sha)); return sha;
    }
}
