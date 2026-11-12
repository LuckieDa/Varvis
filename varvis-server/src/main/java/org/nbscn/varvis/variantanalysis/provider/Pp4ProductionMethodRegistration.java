package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** MAIN-injected, immutable original-input registration, never an HTTP/JSON approval receipt.
 * Registration establishes the implemented method and acquired byte scope, not case eligibility.
 */
public final class Pp4ProductionMethodRegistration {
    public static final String PROFILE = "PP4_NATIVE_CLINICAL_COHORT_V1";
    public static final String GUIDANCE_SHA256 = "187c67f0de556fbc9e1bbc0ea1d09f80ecbc53b1e1f07f806b04966dbdf236a1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Set<String>> TABLES = Map.of(
            "CLINICAL", Set.of("case", "observations", "candidates", "sample_links"),
            "DEFINITION", Set.of("definition", "requirements"),
            "COHORT", Set.of("enrollment", "observations", "closure"),
            "CLINICIAN", Set.of("reviews"),
            "GENE_DISEASE_BASIS", Set.of("assertion", "evidence"),
            "DIAGNOSTIC_REPORTS", Set.of("reports", "variants"));

    public record PrimaryArtifact(String role, String sourceId, String pmcid, Map<String, String> tableIds,
            byte[] raw, String expectedRawSha256, String locator, String acquiredAt) {
        public PrimaryArtifact {
            Objects.requireNonNull(raw); raw = raw.clone(); tableIds = Map.copyOf(tableIds);
        }
        @Override public byte[] raw() { return raw.clone(); }
    }

    /** The server configuration supplies acquisition pins before request hashing.
     * Public original bytes can be replayed, but arbitrary JSON cannot manufacture this instance.
     * Even a loaded configuration may be incomplete and cannot by itself issue an adoption.
     */
    public static final class RegisteredInputConfiguration {
        private final Map<String, PrimaryArtifact> originals;
        private final ObjectNode identity;
        private final byte[] guidance;
        private final @org.springframework.lang.Nullable Pp4NativeClinicalProjection.Facts clinicalProjection;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private RegisteredInputConfiguration(Map<String, PrimaryArtifact> originals, ObjectNode identity, byte[] guidance,
                @org.springframework.lang.Nullable Pp4NativeClinicalProjection.Facts clinicalProjection) {
            this.originals = Map.copyOf(originals); this.identity = identity.deepCopy(); this.guidance = guidance.clone();this.clinicalProjection=clinicalProjection;
        }
        public static RegisteredInputConfiguration load(byte[] guidance, List<PrimaryArtifact> artifacts,
                String dataClass) throws IOException {
            return load(guidance,artifacts,dataClass,null);
        }
        /** Native originals can be registered without inventing PMC tables. Missing medical
         * qualification sources remain unavailable; this does not grant a complete material.
         */
        public static RegisteredInputConfiguration loadWithNativeClinicalRecord(byte[] guidance,List<PrimaryArtifact> artifacts,
                String dataClass,Pp4PhenopacketReplay.Replay nativeRecord) throws IOException {
            return load(guidance,artifacts,dataClass,Objects.requireNonNull(nativeRecord));
        }
        private static RegisteredInputConfiguration load(byte[] guidance,List<PrimaryArtifact> artifacts,String dataClass,
                @org.springframework.lang.Nullable Pp4PhenopacketReplay.Replay nativeRecord) throws IOException {
            need(guidance != null, "PP4_REGISTERED_GUIDANCE_PIN_MISMATCH");
            if (guidance == null) throw new IOException("PP4_REGISTERED_GUIDANCE_PIN_MISMATCH");
            guidance = guidance.clone();
            need(GUIDANCE_SHA256.equals(rawSha(guidance)), "PP4_REGISTERED_GUIDANCE_PIN_MISMATCH");
            Pp4PrimaryTableReplay.replay(guidance, GUIDANCE_SHA256, "PMC10806742", "tbl2");
            need(dataClass != null && Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(dataClass), "PP4_REGISTERED_DATA_CLASS_INVALID");
            need(artifacts != null && (!artifacts.isEmpty() || nativeRecord!=null) && artifacts.size() <= 6, "PP4_REGISTERED_ORIGINALS_REQUIRED");
            artifacts = List.copyOf(artifacts);
            var originals = new LinkedHashMap<String, PrimaryArtifact>();
            var sourceIds = new java.util.HashSet<String>();
            var identity = JSON.createObjectNode().put("schemaVersion", "pp4-registered-native-inputs-v1")
                    .put("profile", PROFILE).put("dataClass", dataClass)
                    .put("guidancePmcid", "PMC10806742").put("guidancePmid", "38103548")
                    .put("guidanceDoi", "10.1016/j.ajhg.2023.11.009").put("guidanceRawSha256", GUIDANCE_SHA256);
            var pins = identity.putArray("originals");
            for (var artifact : artifacts) {
                if (!(artifact != null && artifact.role() != null && TABLES.containsKey(artifact.role()) && text(artifact.sourceId())
                        && artifact.pmcid() != null && artifact.pmcid().matches("PMC[1-9][0-9]{0,15}")
                        && hash(artifact.expectedRawSha256()) && artifact.expectedRawSha256().equals(rawSha(artifact.raw()))
                        && artifact.raw().length <= 1048576 && TABLES.get(artifact.role()).containsAll(artifact.tableIds().keySet()))) {
                    throw new IOException("PP4_REGISTERED_ORIGINAL_IDENTITY_INVALID");
                }
                need(originals.putIfAbsent(artifact.role(), artifact) == null && sourceIds.add(artifact.sourceId()), "PP4_REGISTERED_ROLE_OR_SOURCE_AMBIGUOUS");
                try {
                    need(artifact.acquiredAt() != null && artifact.locator() != null, "PP4_REGISTERED_ACQUISITION_INVALID");
                    Instant.parse(artifact.acquiredAt()); URI url = URI.create(artifact.locator());
                    need(url.getScheme() != null && Set.of("https", "urn").contains(url.getScheme()) && url.getUserInfo() == null
                            && url.getFragment() == null && url.getRawQuery() == null && (url.getScheme().equals("urn") || url.getHost() != null),
                            "PP4_REGISTERED_LOCATOR_INVALID");
                } catch (IllegalArgumentException invalid) { throw new IOException("PP4_REGISTERED_ACQUISITION_INVALID", invalid); }
                for (var entry : artifact.tableIds().entrySet()) {
                    need(text(entry.getValue()), "PP4_REGISTERED_TABLE_REQUIRED");
                    Pp4PrimaryTableReplay.replay(artifact.raw(), artifact.expectedRawSha256(), artifact.pmcid(), entry.getValue());
                }
                var pin = pins.addObject().put("role", artifact.role()).put("sourceId", artifact.sourceId())
                        .put("pmcid", artifact.pmcid()).put("rawSha256", artifact.expectedRawSha256())
                        .put("locator", artifact.locator()).put("acquiredAt", artifact.acquiredAt());
                pin.set("tableIds", JSON.valueToTree(new java.util.TreeMap<>(artifact.tableIds())));
            }
            Pp4NativeClinicalProjection.Facts clinicalProjection=null;
            if(nativeRecord!=null) {
                identity.set("nativeClinicalRecord",nativeRecord.sourceIdentity());
                var quality=Pp4NativeClinicalProjection.sourceQuality(nativeRecord);
                identity.set("nativeClinicalSourceQuality",quality);
                try {
                    clinicalProjection=Pp4NativeClinicalProjection.read(nativeRecord);
                    identity.set("nativeClinicalProjection",clinicalProjection.identity());
                } catch(IOException unavailable) {
                    // This optional, nonscoring projection is not the independent material
                    // qualifier. Bad supplemental measurements must not become numbers or
                    // erase the separately verified native sample association.
                    identity.set("nativeClinicalProjectionFailure",Pp4NativeClinicalProjection.unavailableProjection(quality,unavailable));
                }
            }
            identity.set("nativeCodebook", JSON.valueToTree(Pp4NativeMaterialVerifier.headers()));
            return new RegisteredInputConfiguration(originals, identity, guidance,clinicalProjection);
        }
        public ObjectNode identity() { return identity.deepCopy(); }
        public byte[] guidanceOriginal() { return guidance.clone(); }
        public java.util.Optional<Pp4NativeClinicalProjection.Facts> nativeClinicalProjection() { return java.util.Optional.ofNullable(clinicalProjection); }
        /** Raw originals are stored once by MAIN under their content pin, never per attempt. */
        public List<PrimaryArtifact> originalArtifacts() { return originals.values().stream().sorted(java.util.Comparator.comparing(value -> Objects.requireNonNull(value).role())).toList(); }
        PrimaryArtifact artifact(String role) { return originals.get(role); }
    }

    private final RegisteredInputConfiguration inputs;
    private final ObjectNode identity;
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    private Pp4ProductionMethodRegistration(RegisteredInputConfiguration inputs, ObjectNode identity) {
        this.inputs = inputs; this.identity = identity.deepCopy();
    }
    public static Pp4ProductionMethodRegistration register(RegisteredInputConfiguration inputs) throws IOException {
        Objects.requireNonNull(inputs);
        var id = JSON.createObjectNode().put("schemaVersion", "pp4-production-method-registration-v1")
                .put("methodId", "NATIVE_SOURCE_QUALIFIED_SINGLE_CASE_AD_PP4")
                .put("methodVersion", "1.0.0").put("nativeProfile", PROFILE)
                .put("legacyEvaluatorVersion", Pp4PhenotypeCriterionEvaluator.VERSION)
                .put("yieldAlgorithmVersion", Pp4DiagnosticYield.VERSION)
                .put("scope", "NATIVE_GRCH38_AUTOSOMAL_AD_SINGLE_AFFECTED_HET_SNV_SOLE_TARGET")
                .put("legacyFlagsUnchanged", true).put("clinicalValidationCompleted", false)
                .put("externalConclusionAdmission", "MAIN_REGISTERED_ORIGINAL_DIAGNOSTIC_REPORTS")
                .put("externalClassificationRecalculated", false);
        id.set("registeredInputs", inputs.identity());
        var code = id.putObject("implementationPins");
        for (var type : List.of(Pp4ProductionMethodRegistration.class, Pp4NativeMaterialVerifier.class,
                Pp4QualifiedMaterial.class, Pp4RegisteredRuleInputs.class, Pp4ServerRegisteredInputs.class, Pp4ServerRegisteredInputs.SampleLink.class, Pp4LegacyMaterialAdapter.class, Pp4CurrentAdoptionProducer.class, Pp4PrimaryTableReplay.class,
                Pp4PhenotypePolicy.class, Pp4DiagnosticYield.class, Pp4PhenotypeCriterionEvaluator.class, Pp4PhenopacketReplay.class, Pp4PhenopacketReplay.Replay.class, Pp4NativeClinicalProjection.class, Pp4NativeClinicalProjection.Facts.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                need(stream != null, "PP4_REGISTERED_IMPLEMENTATION_REQUIRED");
                if (stream == null) throw new IOException("PP4_REGISTERED_IMPLEMENTATION_REQUIRED");
                code.put(type.getSimpleName(), rawSha(stream.readAllBytes()));
            }
        }
        return new Pp4ProductionMethodRegistration(inputs, id);
    }
    public ObjectNode identity() { return identity.deepCopy(); }
    public String sha256() { return canonical(identity); }
    public RegisteredInputConfiguration registeredInputs() { return inputs; }
    RegisteredInputConfiguration inputs() { return inputs; }
    static String canonical(com.fasterxml.jackson.databind.JsonNode value) { return new RuntimeEvidenceV2Adapter(JSON).canonicalSha256(value); }
    static String rawSha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static boolean text(String value) { return value != null && !value.isBlank() && value.length() <= 256 && value.codePoints().noneMatch(Character::isISOControl); }
    static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    static void need(boolean condition, String reason) throws IOException { if (!condition) throw new IOException(reason); }
}
