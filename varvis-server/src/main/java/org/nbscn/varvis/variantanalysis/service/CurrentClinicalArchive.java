package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.nbscn.varvis.variantanalysis.provider.Pp4PrimaryTableReplay;
import org.nbscn.varvis.variantanalysis.provider.Pp4PhenopacketReplay;
import org.nbscn.varvis.variantanalysis.provider.Pp4NativeClinicalProjection;
import org.nbscn.varvis.variantanalysis.provider.Pp4ServerRegisteredInputs;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;

/** Run-private content archive. Verification restores historical bytes, never a native capability.
 * Persistence and its outer request/public/private SHA chain remain owned by the normal business store.
 */
public final class CurrentClinicalArchive {
    public static final String VERSION = "current-clinical-archive-v1";
    public static final String EXPORT_VERSION = "current-clinical-archive-export-v1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "historyOnly", "authorityRestorationSupported",
            "sessionIdentity", "sessionIdentitySha256", "registeredInputsIdentity", "registeredInputsIdentitySha256",
            "registrationIdentity", "registrationSha256", "ruleInputsIdentity", "ruleInputsSha256",
            "inventorySnapshotSha256", "rawArtifacts", "rawBindings", "materialOriginals", "materialOriginalSha256s", "archiveSha256");
    private CurrentClinicalArchive() { }

    /** Only the actual current native inventory and server registration may be captured.
     * The returned JSON is inert storage content, with no constructor for a Session, Grant or Outcome.
     */
    public static ObjectNode capture(ObjectMapper mapper, CurrentAcmgEvaluationSession session,
            Pp4ServerRegisteredInputs registeredInputs, CurrentAdoptionInventory inventory) {
        Objects.requireNonNull(mapper); Objects.requireNonNull(session); Objects.requireNonNull(registeredInputs); Objects.requireNonNull(inventory);
        require(inventory.matches(session), "CURRENT_CLINICAL_ARCHIVE_NATIVE_INVENTORY_REQUIRED");
        var registration = registeredInputs.registration(); var rules = registeredInputs.rules();
        ObjectNode inventoryAudit = inventory.snapshot();
        require(registration.sha256().equals(inventory.producerIdentity().path("registrationSha256").asText())
                && rules.sha256().equals(inventory.producerIdentity().path("registeredRuleInputsSha256").asText()),
                "CURRENT_CLINICAL_ARCHIVE_REGISTRATION_MISMATCH");
        var archive = mapper.createObjectNode().put("schemaVersion", VERSION).put("historyOnly", true)
                .put("authorityRestorationSupported", false);
        archive.set("sessionIdentity", session.identity()); archive.put("sessionIdentitySha256", canonical(mapper, session.identity()));
        archive.set("registeredInputsIdentity", registeredInputs.identity());
        archive.put("registeredInputsIdentitySha256", canonical(mapper, registeredInputs.identity()));
        archive.set("registrationIdentity", registration.identity()); archive.put("registrationSha256", registration.sha256());
        archive.set("ruleInputsIdentity", rules.identity()); archive.put("ruleInputsSha256", rules.sha256());
        archive.put("inventorySnapshotSha256", canonical(mapper, inventoryAudit));
        var raw = archive.putObject("rawArtifacts");
        for (var artifact : new TreeMap<>(registeredInputs.archiveArtifacts()).entrySet()) {
            byte[] bytes = artifact.getValue();
            if (!(hash(artifact.getKey()) && bytes != null && artifact.getKey().equals(rawSha(bytes)))) {
                throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_RAW_PIN_MISMATCH");
            }
            raw.putObject(artifact.getKey()).put("rawSha256", artifact.getKey()).put("byteLength", bytes.length)
                    .put("encoding", "BASE64").put("base64", Base64.getEncoder().encodeToString(bytes));
        }
        for (var artifact : new TreeMap<>(inventory.coreArtifacts()).entrySet()) {
            byte[] bytes = artifact.getValue();
            if (!(hash(artifact.getKey()) && bytes != null && artifact.getKey().equals(rawSha(bytes)))) {
                throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_RAW_PIN_MISMATCH");
            }
            if (raw.has(artifact.getKey())) require(raw.path(artifact.getKey()).path("base64").asText().equals(Base64.getEncoder().encodeToString(bytes)), "CURRENT_CLINICAL_ARCHIVE_RAW_PIN_MISMATCH");
            else raw.putObject(artifact.getKey()).put("rawSha256", artifact.getKey()).put("byteLength", bytes.length)
                    .put("encoding", "BASE64").put("base64", Base64.getEncoder().encodeToString(bytes));
        }
        archive.set("rawBindings", bindings(mapper, archive.path("registeredInputsIdentity"), archive.path("registrationIdentity"),
                archive.path("ruleInputsIdentity"), inventoryAudit));
        var originals = archive.putObject("materialOriginals");
        var originalPins = archive.putArray("materialOriginalSha256s");
        for (var material : new TreeMap<>(inventory.materialOriginals()).entrySet()) {
            require(hash(material.getKey()) && material.getKey().equals(canonical(mapper, material.getValue())), "CURRENT_CLINICAL_ARCHIVE_MATERIAL_PIN_MISMATCH");
            originals.set(material.getKey(), material.getValue().deepCopy()); originalPins.add(material.getKey());
        }
        archive.put("archiveSha256", canonical(mapper, archive));
        verifyAndExpand(mapper, archive, inventoryAudit, session.identity().path("analysisId").asText(), session.identity().path("runId").asText());
        return archive;
    }

    /** Historical integrity/read/export only: no producer, source loader or current-use issuer runs here.
     * The caller supplies the saved inventory from the independently verified private run document.
     */
    public static ObjectNode verifyAndExpand(ObjectMapper mapper, JsonNode savedArchive, JsonNode inventoryAudit,
            String expectedAnalysisId, String expectedRunId) {
        if (!(savedArchive != null && savedArchive.isObject())) {
            throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_REQUIRED");
        }
        ObjectNode archive = ((ObjectNode) savedArchive).deepCopy();
        var names = new HashSet<String>(); archive.fieldNames().forEachRemaining(names::add);
        require(names.equals(FIELDS) && VERSION.equals(archive.path("schemaVersion").asText())
                && archive.path("historyOnly").isBoolean() && archive.path("historyOnly").asBoolean()
                && archive.path("authorityRestorationSupported").isBoolean() && !archive.path("authorityRestorationSupported").asBoolean(),
                "CURRENT_CLINICAL_ARCHIVE_HEADER_INVALID");
        ObjectNode content = archive.deepCopy(); content.remove("archiveSha256");
        require(hash(archive.path("archiveSha256").asText()) && canonical(mapper, content).equals(archive.path("archiveSha256").asText()),
                "CURRENT_CLINICAL_ARCHIVE_CONTENT_PIN_MISMATCH");
        JsonNode session = archive.path("sessionIdentity");
        require(expectedAnalysisId != null && expectedRunId != null && expectedAnalysisId.equals(session.path("analysisId").asText())
                && expectedRunId.equals(session.path("runId").asText()) && "current-acmg-evaluation-session-v1".equals(session.path("schemaVersion").asText())
                && canonical(mapper, session).equals(archive.path("sessionIdentitySha256").asText()), "CURRENT_CLINICAL_ARCHIVE_TARGET_MISMATCH");
        if (!(inventoryAudit != null && inventoryAudit.isObject() && session.equals(inventoryAudit.path("sessionIdentity"))
                && canonical(mapper, inventoryAudit).equals(archive.path("inventorySnapshotSha256").asText()))) {
            throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_INVENTORY_MISMATCH");
        }
        verifySelfPin(mapper, inventoryAudit, "inventorySha256", "CURRENT_CLINICAL_ARCHIVE_INVENTORY_MISMATCH");
        require(canonical(mapper, archive.path("registeredInputsIdentity")).equals(archive.path("registeredInputsIdentitySha256").asText())
                && canonical(mapper, archive.path("registrationIdentity")).equals(archive.path("registrationSha256").asText())
                && canonical(mapper, archive.path("ruleInputsIdentity")).equals(archive.path("ruleInputsSha256").asText())
                && archive.path("registrationSha256").equals(inventoryAudit.at("/producerIdentity/registrationSha256"))
                && archive.path("ruleInputsSha256").equals(inventoryAudit.at("/producerIdentity/registeredRuleInputsSha256")),
                "CURRENT_CLINICAL_ARCHIVE_REGISTRATION_MISMATCH");
        var raw = readRaw(archive.path("rawArtifacts"));
        ArrayNode expectedBindings = bindings(mapper, archive.path("registeredInputsIdentity"), archive.path("registrationIdentity"), archive.path("ruleInputsIdentity"), inventoryAudit);
        require(expectedBindings.equals(archive.path("rawBindings")), "CURRENT_CLINICAL_ARCHIVE_BINDING_MISMATCH");
        verifyRegisteredIndex(mapper, archive, raw);
        var referencedRaw = new TreeSet<String>(); var primary = new TreeMap<String, JsonNode>();
        for (JsonNode binding : expectedBindings) {
            String pin = binding.path("rawSha256").asText(); referencedRaw.add(pin);
            require(raw.containsKey(pin), "CURRENT_CLINICAL_ARCHIVE_RAW_REQUIRED");
            if (binding.has("byteLength")) require(binding.path("byteLength").canConvertToInt() && binding.path("byteLength").asInt() == raw.get(pin).length, "CURRENT_CLINICAL_ARCHIVE_RAW_PIN_MISMATCH");
            if (binding.has("canonicalSha256")) {
                try {
                    JsonNode rule = mapper.readTree(raw.get(pin));
                    require(rule != null && rule.isObject() && canonical(mapper, rule).equals(binding.path("canonicalSha256").asText()), "CURRENT_CLINICAL_ARCHIVE_RULE_PIN_MISMATCH");
                } catch (IOException malformed) { throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_RULE_PIN_MISMATCH", malformed); }
            }
            if ("PRIMARY".equals(binding.path("kind").asText())) require(primary.put(binding.path("role").asText(), binding) == null, "CURRENT_CLINICAL_ARCHIVE_BINDING_MISMATCH");
        }
        require(referencedRaw.equals(raw.keySet()), "CURRENT_CLINICAL_ARCHIVE_RAW_SCOPE_MISMATCH");
        JsonNode originals = archive.path("materialOriginals");
        require(originals.isObject(), "CURRENT_CLINICAL_ARCHIVE_MATERIAL_REQUIRED");
        var originalPins = new TreeSet<String>(); originals.fieldNames().forEachRemaining(originalPins::add);
        require(stringPins(archive.path("materialOriginalSha256s")).equals(originalPins)
                && stringPins(inventoryAudit.path("materialOriginalSha256s")).equals(originalPins), "CURRENT_CLINICAL_ARCHIVE_MATERIAL_REFERENCES_MISMATCH");
        var tableReplayCache = new TreeMap<String, JsonNode>();
        for (String pin : originalPins) {
            JsonNode original = originals.path(pin);
            require(hash(pin) && original.isObject() && pin.equals(canonical(mapper, original))
                    && "pp4-native-source-material-originals-v1".equals(original.path("schemaVersion").asText())
                    && archive.path("registrationSha256").equals(original.path("registrationSha256"))
                    && canonical(mapper, original.path("nativeFacts")).equals(original.path("nativeFactsSha256").asText())
                    && archive.at("/registrationIdentity/registeredInputs/originals").equals(original.path("rawArtifactIndex")),
                    "CURRENT_CLINICAL_ARCHIVE_MATERIAL_PIN_MISMATCH");
            verifyTableReplays(mapper, original.at("/nativeFacts/originalTableReplays"), primary, raw,
                    archive.at("/registrationIdentity/registeredInputs/nativeCodebook"), tableReplayCache);
        }
        verifyCoreArtifacts(mapper, inventoryAudit, raw, originals);
        var referencedOriginals = new TreeSet<String>();
        for (JsonNode attempt : inventoryAudit.path("attempts")) {
            JsonNode receipt = attempt.path("terminalReceipt");
            if (receipt.isObject()) {
                verifySelfPin(mapper, receipt, "receiptSha256", "CURRENT_CLINICAL_ARCHIVE_RECEIPT_PIN_MISMATCH");
                require(session.equals(receipt.path("sessionIdentity")), "CURRENT_CLINICAL_ARCHIVE_TARGET_MISMATCH");
            }
            if (attempt.hasNonNull("materialOriginalsSha256")) {
                String pin = attempt.path("materialOriginalsSha256").asText();
                require(originals.has(pin) && attempt.path("materialOriginalsSha256").equals(receipt.path("materialOriginalsSha256"))
                        && pin.equals(receipt.at("/material/originalsSha256").asText())
                        && originals.path(pin).at("/nativeFacts/context").equals(attempt.at("/binding/identity/preparedContext"))
                        && originals.path(pin).path("nativeFactsSha256").equals(receipt.at("/material/nativeFactsSha256"))
                        && originals.path(pin).at("/nativeFacts/registeredInputs").equals(archive.at("/registrationIdentity/registeredInputs"))
                        && receipt.path("materialSha256").equals(receipt.at("/material/materialSha256")), "CURRENT_CLINICAL_ARCHIVE_MATERIAL_REFERENCES_MISMATCH");
                verifySelfPin(mapper, receipt.path("material"), "materialSha256", "CURRENT_CLINICAL_ARCHIVE_MATERIAL_PIN_MISMATCH");
                verifyNativeIdentityBridge(mapper,archive,inventoryAudit,attempt,receipt,originals.path(pin).path("nativeFacts"));
                referencedOriginals.add(pin);
            } else require(!receipt.hasNonNull("materialOriginalsSha256"), "CURRENT_CLINICAL_ARCHIVE_MATERIAL_REFERENCES_MISMATCH");
        }
        require(referencedOriginals.equals(originalPins), "CURRENT_CLINICAL_ARCHIVE_MATERIAL_REFERENCES_MISMATCH");
        var expanded = mapper.createObjectNode().put("schemaVersion", EXPORT_VERSION).put("historyOnly", true)
                .put("authorityRestorationSupported", false).put("sourceArchiveSha256", archive.path("archiveSha256").asText());
        for (String key : java.util.List.of("sessionIdentity", "registeredInputsIdentity", "registrationIdentity", "ruleInputsIdentity", "inventorySnapshotSha256"))
            expanded.set(key, archive.path(key).deepCopy());
        if(archive.path("registeredInputsIdentity").has("clinicalSampleSource")) {
            var nativeSource=archive.at("/registeredInputsIdentity/clinicalSampleSource");
            try {
                var replay=Pp4PhenopacketReplay.read(raw.get(nativeSource.path("rawSha256").asText()),
                        nativeSource.path("rawSha256").asText(),nativeSource.path("kind").asText());
                expanded.set("nativeClinicalFacts",replay.snapshot());
                var details=replay.detailFacts();
                for(String field:List.of("sourceId","locator","acquiredAt")) ((ObjectNode)details.path("source")).set(field,nativeSource.path(field).deepCopy());
                expanded.set("nativeClinicalDetails",details);
                for(String field:List.of("nativeClinicalSourceQuality","nativeClinicalProjectionFailure"))
                    if(archive.path("registeredInputsIdentity").has(field)) expanded.set(field,archive.path("registeredInputsIdentity").path(field).deepCopy());
                if(archive.path("registeredInputsIdentity").has("clinicalProjection")) expanded.set("nativeClinicalReviewFacts",
                        parseClinicalProjection(raw.get(archive.at("/registeredInputsIdentity/clinicalProjection/rawSha256").asText())));
            } catch(IOException invalid) { throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_NATIVE_SOURCE_MISMATCH",invalid); }
        }
        var rawExpanded = expanded.putArray("rawBindings");
        for (JsonNode binding : expectedBindings) {
            var row = ((ObjectNode) binding).deepCopy(); row.set("artifact", archive.path("rawArtifacts").path(binding.path("rawSha256").asText()).deepCopy()); rawExpanded.add(row);
        }
        var materialExpanded = expanded.putArray("materialReferences");
        for (String pin : originalPins) materialExpanded.addObject().put("sha256", pin).set("originals", originals.path(pin).deepCopy());
        return expanded;
    }

    private static ArrayNode bindings(ObjectMapper mapper, JsonNode server, JsonNode registration, JsonNode rules, JsonNode inventory) {
        var bindings = mapper.createArrayNode();
        bindings.addObject().put("kind", "SERVER_CONFIGURATION").put("rawSha256", server.path("manifestRawSha256").asText());
        bindings.addObject().put("kind", "GUIDANCE").put("rawSha256", registration.at("/registeredInputs/guidanceRawSha256").asText())
                .put("pmcid", "PMC10806742").put("tableId", "tbl2");
        bindings.addObject().put("kind", "STRICT_RULES").put("rawSha256", rules.path("templateRawSha256").asText())
                .put("canonicalSha256", rules.path("templateCanonicalSha256").asText());
        if(server.has("clinicalSampleSource")) {
            var standard=((ObjectNode)server.path("clinicalSampleSource")).deepCopy();standard.put("kindOfSourceRecord",standard.path("kind").asText());
            standard.put("kind","CLINICAL_STANDARD");bindings.add(standard);
        }
        if(server.has("clinicalProjection")) bindings.addObject().put("kind","CLINICAL_PROJECTION")
                .put("rawSha256",server.at("/clinicalProjection/rawSha256").asText())
                .put("canonicalSha256",server.at("/clinicalProjection/canonicalSha256").asText())
                .put("sourceRawSha256",server.at("/clinicalProjection/sourceRawSha256").asText());
        var primary = new TreeMap<String, JsonNode>();
        for (JsonNode artifact : registration.at("/registeredInputs/originals"))
            require(artifact.isObject() && primary.put(artifact.path("role").asText(), artifact) == null, "CURRENT_CLINICAL_ARCHIVE_BINDING_MISMATCH");
        for (JsonNode artifact : primary.values()) {
            var binding = ((ObjectNode) artifact).deepCopy(); binding.put("kind", "PRIMARY"); bindings.add(binding);
        }
        for (JsonNode attempt : inventory.path("attempts")) {
            JsonNode receipt = attempt.path("terminalReceipt"), core = receipt.path("coreExecution");
            if (!core.isObject()) continue;
            require("pp4-actual-core-artifacts-v1".equals(core.at("/artifactIndex/schemaVersion").asText())
                    && core.at("/artifactIndex/artifacts").isArray(), "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH");
            for (JsonNode artifact : core.at("/artifactIndex/artifacts")) {
                var binding = ((ObjectNode) artifact).deepCopy(); binding.put("artifactKind", artifact.path("kind").asText()).put("kind", "CORE_ARTIFACT")
                        .put("coreExecutionSha256", receipt.path("coreExecutionSha256").asText());
                for (String key : java.util.List.of("candidateId", "hypothesisId", "bindingIdentitySha256")) binding.set(key, attempt.path(key).deepCopy());
                bindings.add(binding);
            }
        }
        for (JsonNode binding : bindings) require(hash(binding.path("rawSha256").asText()), "CURRENT_CLINICAL_ARCHIVE_BINDING_MISMATCH");
        return bindings;
    }

    private static ObjectNode parseClinicalProjection(byte[] bytes) {
        try { return Pp4NativeClinicalProjection.readSavedProjection(bytes); }
        catch(IOException invalid) { throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_NATIVE_PROJECTION_MISMATCH",invalid); }
    }

    private static void verifyRegisteredIndex(ObjectMapper mapper, ObjectNode archive, Map<String, byte[]> raw) {
        JsonNode server = archive.path("registeredInputsIdentity"), registration = archive.path("registrationIdentity"), rules = archive.path("ruleInputsIdentity");
        require("pp4-server-registered-inputs-v1".equals(server.path("schemaVersion").asText())
                && server.path("registrationSha256").equals(archive.path("registrationSha256"))
                && server.path("registeredRuleInputsSha256").equals(archive.path("ruleInputsSha256"))
                && server.path("archiveIndex").isArray(), "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
        var expected = new TreeMap<String, String>(); expected.put("MANIFEST", server.path("manifestRawSha256").asText());
        expected.put("GUIDANCE", registration.at("/registeredInputs/guidanceRawSha256").asText()); expected.put("RULES", rules.path("templateRawSha256").asText());
        for (JsonNode primary : registration.at("/registeredInputs/originals"))
            require(expected.put(primary.path("role").asText(), primary.path("rawSha256").asText()) == null, "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
        if(server.has("clinicalSampleSource")) expected.put("CLINICAL_STANDARD",server.at("/clinicalSampleSource/rawSha256").asText());
        if(server.has("clinicalProjection")) expected.put("CLINICAL_PROJECTION",server.at("/clinicalProjection/rawSha256").asText());
        require(server.has("clinicalProjection")==registration.path("registeredInputs").has("nativeClinicalProjection")
                && (!server.has("clinicalProjection")||server.has("clinicalSampleSource")),"CURRENT_CLINICAL_ARCHIVE_NATIVE_PROJECTION_MISMATCH");
        for(String field:List.of("nativeClinicalSourceQuality","nativeClinicalProjectionFailure"))
            require(server.has(field)==registration.path("registeredInputs").has(field)
                    && (!server.has(field)||(server.has("clinicalSampleSource")&&server.path(field).equals(registration.path("registeredInputs").path(field)))),
                    "CURRENT_CLINICAL_ARCHIVE_NATIVE_QUALITY_MISMATCH");
        require(!server.has("nativeClinicalProjectionFailure")||(!server.has("clinicalProjection")&&server.has("nativeClinicalSourceQuality")),
                "CURRENT_CLINICAL_ARCHIVE_NATIVE_QUALITY_MISMATCH");
        var present = new HashSet<String>();
        for (JsonNode entry : server.path("archiveIndex")) {
            String kind = entry.path("kind").asText(), pin = entry.path("rawSha256").asText();
            require(present.add(kind) && expected.containsKey(kind) && expected.get(kind).equals(pin) && raw.containsKey(pin)
                    && entry.path("byteLength").isIntegralNumber() && entry.path("byteLength").canConvertToInt()
                    && entry.path("byteLength").asInt() == raw.get(pin).length, "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
            if (entry.has("canonicalSha256")) {
                JsonNode document = "CLINICAL_PROJECTION".equals(kind) ? parseClinicalProjection(raw.get(pin)) : parse(mapper, raw.get(pin));
                require(canonical(mapper, document).equals(entry.path("canonicalSha256").asText()), "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
            }
        }
        require(present.equals(expected.keySet()), "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
        JsonNode manifest = parse(mapper, raw.get(server.path("manifestRawSha256").asText()));
        require(canonical(mapper, manifest).equals(server.path("manifestCanonicalSha256").asText())
                && "pp4-server-registered-inputs-v1".equals(manifest.path("schemaVersion").asText())
                && manifest.at("/guidance/rawSha256").asText().equals(expected.get("GUIDANCE"))
                && manifest.at("/rules/rawSha256").asText().equals(expected.get("RULES"))
                && manifest.at("/rules/canonicalSha256").equals(rules.path("templateCanonicalSha256")), "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
        require(manifest.has("clinicalSampleSource")==server.has("clinicalSampleSource"),"CURRENT_CLINICAL_ARCHIVE_NATIVE_SOURCE_MISMATCH");
        if(server.has("clinicalSampleSource")) {
            JsonNode descriptor=manifest.path("clinicalSampleSource");
            require(expected.get("CLINICAL_STANDARD").equals(descriptor.path("rawSha256").asText()),"CURRENT_CLINICAL_ARCHIVE_NATIVE_SOURCE_MISMATCH");
            try {
                var replay=Pp4PhenopacketReplay.read(raw.get(expected.get("CLINICAL_STANDARD")),expected.get("CLINICAL_STANDARD"),descriptor.path("kind").asText());
                var verified=Pp4ServerRegisteredInputs.verifiedNativeIdentity(descriptor,replay);
                require(verified.equals(server.path("clinicalSampleSource"))
                        && replay.sourceIdentity().equals(registration.at("/registeredInputs/nativeClinicalRecord"))
                        && !java.time.Instant.parse(descriptor.path("acquiredAt").asText()).isAfter(java.time.Instant.parse(server.path("registeredAt").asText())),
                        "CURRENT_CLINICAL_ARCHIVE_NATIVE_SOURCE_MISMATCH");
                // Only recheck markers saved by the originating writer. Historical
                // records without this separately versioned audit retain their exact facts.
                if(server.has("nativeClinicalSourceQuality")) {
                    var quality=Pp4NativeClinicalProjection.sourceQuality(replay);
                    require(quality.equals(server.path("nativeClinicalSourceQuality")),"CURRENT_CLINICAL_ARCHIVE_NATIVE_QUALITY_MISMATCH");
                    if(server.has("nativeClinicalProjectionFailure")) {
                        IOException recordedFailure=null;
                        try { Pp4NativeClinicalProjection.read(replay); }
                        catch(IOException unavailable) { recordedFailure=unavailable; }
                        require(recordedFailure!=null,"CURRENT_CLINICAL_ARCHIVE_NATIVE_QUALITY_MISMATCH");
                        if(recordedFailure==null) throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_NATIVE_QUALITY_MISMATCH");
                        require(Pp4NativeClinicalProjection.unavailableProjection(quality,recordedFailure).equals(server.path("nativeClinicalProjectionFailure")),
                                "CURRENT_CLINICAL_ARCHIVE_NATIVE_QUALITY_MISMATCH");
                    } else require(server.has("clinicalProjection"),"CURRENT_CLINICAL_ARCHIVE_NATIVE_QUALITY_MISMATCH");
                }
                if(server.has("clinicalProjection")) {
                    var projection=Pp4NativeClinicalProjection.read(replay);JsonNode saved=parseClinicalProjection(raw.get(expected.get("CLINICAL_PROJECTION")));
                    require(canonical(mapper,projection.snapshot()).equals(canonical(mapper,saved))
                            && projection.identity().equals(registration.at("/registeredInputs/nativeClinicalProjection"))
                            && projection.identity().path("sourceRawSha256").equals(server.at("/clinicalProjection/sourceRawSha256"))
                            && canonical(mapper,saved).equals(server.at("/clinicalProjection/canonicalSha256").asText()),"CURRENT_CLINICAL_ARCHIVE_NATIVE_PROJECTION_MISMATCH");
                    var savedIdentity=((ObjectNode)server.path("clinicalProjection")).deepCopy();savedIdentity.remove(List.of("rawSha256","canonicalSha256"));
                    require(savedIdentity.equals(projection.identity()),"CURRENT_CLINICAL_ARCHIVE_NATIVE_PROJECTION_MISMATCH");
                }
            } catch(IOException|java.time.DateTimeException invalid) { throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_NATIVE_SOURCE_MISMATCH",invalid); }
        }
        for (String key : Set.of("registrationId", "registrationVersion", "registeredAt", "dataClass"))
            require(manifest.path(key).equals(server.path(key)), "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
        var originalManifest = new TreeMap<String, JsonNode>();
        for (JsonNode original : manifest.path("originals"))
            require(originalManifest.put(original.path("role").asText(), original) == null, "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
        require(originalManifest.size() == registration.at("/registeredInputs/originals").size(), "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
        for (JsonNode primary : registration.at("/registeredInputs/originals")) {
            JsonNode original = originalManifest.get(primary.path("role").asText());
            if (original == null) {
                throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
            }
            for (String key : Set.of("role", "sourceId", "pmcid", "rawSha256", "locator", "acquiredAt", "tableIds"))
                require(primary.path(key).equals(original.path(key)), "CURRENT_CLINICAL_ARCHIVE_REGISTERED_SOURCE_MISMATCH");
        }
    }

    private static void verifyCoreArtifacts(ObjectMapper mapper, JsonNode inventory, Map<String, byte[]> raw, JsonNode originals) {
        for (JsonNode attempt : inventory.path("attempts")) {
            JsonNode receipt = attempt.path("terminalReceipt"), core = receipt.path("coreExecution");
            if (receipt.has("currentUseGrant")) verifyGrant(mapper, inventory, attempt, receipt);
            if (!core.isObject()) {
                require(!receipt.has("coreExecution") && !receipt.hasNonNull("coreExecutionSha256")
                        && !receipt.has("currentAdoption") && !receipt.path("complete").asBoolean(false),
                        "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH");
                continue;
            }
            require(hash(receipt.path("coreExecutionSha256").asText())
                    && canonical(mapper, core).equals(receipt.path("coreExecutionSha256").asText()), "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH");
            var artifacts = new TreeMap<String, JsonNode>();
            for (JsonNode entry : core.at("/artifactIndex/artifacts")) {
                String kind = entry.path("kind").asText();
                require(Set.of("INPUT", "SOURCE_RESULT", "EVALUATION").contains(kind) && artifacts.put(kind, entry) == null,
                        "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH");
            }
            require(artifacts.keySet().equals(Set.of("INPUT", "SOURCE_RESULT", "EVALUATION")), "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH");
            JsonNode input = parse(mapper, raw.get(artifacts.get("INPUT").path("rawSha256").asText()));
            JsonNode source = parse(mapper, raw.get(artifacts.get("SOURCE_RESULT").path("rawSha256").asText()));
            JsonNode evaluation = parse(mapper, raw.get(artifacts.get("EVALUATION").path("rawSha256").asText()));
            require(core.path("inputRawSha256").equals(artifacts.get("INPUT").path("rawSha256"))
                    && core.path("inputCanonicalSha256").equals(artifacts.get("INPUT").path("canonicalSha256"))
                    && core.path("sourceResultSha256").equals(artifacts.get("SOURCE_RESULT").path("canonicalSha256"))
                    && core.path("evaluationSha256").equals(artifacts.get("EVALUATION").path("canonicalSha256"))
                    && source.path("sourceBundle").equals(input) && source.path("criterionEvaluations").isArray()
                    && source.path("criterionEvaluations").size() == 1 && source.at("/criterionEvaluations/0").equals(evaluation)
                    && input.path("context").equals(attempt.at("/binding/identity/preparedContext"))
                    && "ACMG_AMP_2015:PP4".equals(source.path("criterionId").asText())
                    && "CORE_RESULT_ONLY".equals(source.path("resultMode").asText())
                    && source.path("adoptionSupported").isBoolean() && !source.path("adoptionSupported").asBoolean()
                    && source.path("classificationEnabled").isBoolean() && !source.path("classificationEnabled").asBoolean(),
                    "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH");
            JsonNode grant = receipt.path("currentUseGrant"), context = attempt.at("/binding/identity/preparedContext");
            require("pp4-actual-core-execution-v1".equals(core.path("schemaVersion").asText())
                    && "ACTUAL_CORE_EXECUTED".equals(receipt.path("coreExecutionStatus").asText())
                    && grant.isObject() && "RESERVED_NOT_AWARDED".equals(grant.path("status").asText())
                    && "COMPLETE".equals(grant.at("/consumerClosure/status").asText())
                    && core.path("grantSha256").equals(grant.path("grantSha256"))
                    && canonical(mapper, grant).equals(core.path("grantSnapshotSha256").asText())
                    && core.path("materialSha256").equals(receipt.path("materialSha256"))
                    && context.isObject() && context.equals(receipt.at("/material/context"))
                    && core.path("contextId").equals(context.path("contextId"))
                    && "ACMG_AMP_2015:PP4".equals(evaluation.path("criterionId").asText())
                    && core.path("actualStatus").equals(evaluation.at("/evaluationResult/status"))
                    && core.path("actualReasonCode").equals(evaluation.at("/evaluationResult/reasonCode"))
                    && (evaluation.has("appliedStrength") ? core.path("appliedStrength").equals(evaluation.path("appliedStrength"))
                            : core.path("appliedStrength").isNull())
                    && (!source.has("evaluation") || source.path("evaluation").equals(evaluation))
                    && "CORE_RESULT_ONLY".equals(core.path("legacyResultMode").asText())
                    && core.path("legacyAdoptionSupported").isBoolean() && !core.path("legacyAdoptionSupported").asBoolean()
                    && receipt.path("evidenceSelected").equals(grant.path("evidenceSelected")),
                    "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH");
            var allocation = mapper.createObjectNode();
            for (String field : Set.of("usedEvidence", "usedEvidenceStatus", "clinicalUse", "clinicalUseStatus")) {
                JsonNode value = input.at("/observations/0/locusAttribution").path(field);
                require(!value.isMissingNode(), "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH"); allocation.set(field, value);
            }
            require(canonical(mapper, allocation).equals(grant.path("legacyAllocationSha256").asText()),
                    "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH");
            verifyAdoption(mapper, receipt, core, source, evaluation, originals);
        }
    }

    /** Relational history checks only: these JSON records never mint a current native Grant. */
    /** This is mechanical historical correspondence checking, never a native handle issuer. */
    private static void verifyNativeIdentityBridge(ObjectMapper mapper,JsonNode archive,JsonNode inventory,JsonNode attempt,JsonNode receipt,JsonNode facts) {
        String issue="CURRENT_CLINICAL_ARCHIVE_NATIVE_IDENTITY_MISMATCH";JsonNode bridge=facts.path("sourceIdentityBridge");
        if(!facts.has("sourceIdentityBridge")) { require(!receipt.path("material").has("sourceIdentityBridge")&&!receipt.path("currentUseGrant").has("nativeClinicalIdentity"),issue);return; }
        verifySelfPin(mapper,bridge,"identitySha256",issue);JsonNode source=bridge.path("sourceSampleLink"),call=bridge.path("nativeCallProof"),c=facts.path("case"),context=attempt.at("/binding/identity/preparedContext");
        require("current-native-clinical-identity-v1".equals(bridge.path("schemaVersion").asText())
                &&"SELECTED_PROBAND_IDENTITY_ONLY_NO_CLINICAL_QUALIFICATION_OR_PARENTAGE".equals(bridge.path("scope").asText())
                &&bridge.path("clinicalQualificationGranted").isBoolean()&&!bridge.path("clinicalQualificationGranted").asBoolean()
                &&bridge.path("familyRelationshipProven").isBoolean()&&!bridge.path("familyRelationshipProven").asBoolean()
                &&bridge.path("registrationSha256").equals(archive.path("registrationSha256"))
                &&bridge.path("registeredInputsSha256").asText().equals(canonical(mapper,archive.path("registeredInputsIdentity")))
                &&bridge.path("currentSessionIdentitySha256").asText().equals(canonical(mapper,inventory.path("sessionIdentity")))
                &&bridge.path("sourceSampleLinkSha256").asText().equals(canonical(mapper,source))
                &&bridge.path("nativeCallProofSha256").asText().equals(canonical(mapper,call))
                &&bridge.path("sourceCaseId").equals(c.path("case_id"))&&bridge.path("sourceCaseId").equals(source.path("caseId"))
                &&bridge.path("sourcePersonId").equals(c.path("person_id"))&&bridge.path("sourcePersonId").equals(source.path("personId"))
                &&bridge.path("sourceFamilyId").equals(c.path("family_id"))&&bridge.path("sourceFamilyId").equals(source.path("familyId"))
                &&bridge.path("sourceSampleId").equals(c.path("sample_id"))&&bridge.path("sourceSampleId").equals(source.path("clinicalSampleId"))
                &&bridge.path("currentPatientContextId").equals(context.at("/patientContext/patientContextId"))
                &&bridge.path("currentFamilyContextId").equals(context.at("/patientContext/familyContextId"))
                &&bridge.equals(receipt.at("/material/sourceIdentityBridge")),issue);
        for(String field:List.of("candidateId","hypothesisId","bindingIdentitySha256","bindingStatus")) require(bridge.path("attemptIdentity").path(field).equals(attempt.path(field)),issue);
        require(call.path("analysisId").equals(inventory.at("/sessionIdentity/analysisId"))
                &&source.path("analysisAssembly").equals(call.path("assembly"))&&source.path("vcfSampleId").equals(call.path("vcfSampleId"))
                &&call.path("sampleOrdinal").asInt(0)>0,issue);
        if(source.hasNonNull("nativeAnalysisVcfUriSha256")) require(source.path("nativeAnalysisVcfUriSha256").equals(call.path("analysisVcfUriSha256")),issue);
        else require(source.path("vcfRawSha256").equals(call.path("vcfRawSha256"))&&source.path("sampleOrdinal").equals(call.path("sampleOrdinal")),issue);
        if(receipt.has("currentUseGrant")) require(bridge.equals(receipt.at("/currentUseGrant/nativeClinicalIdentity"))
                &&source.equals(receipt.at("/currentUseGrant/originalSampleLink"))&&call.equals(receipt.at("/currentUseGrant/nativeCallProof")),issue);
    }

    private static void verifyGrant(ObjectMapper mapper, JsonNode inventory, JsonNode attempt, JsonNode receipt) {
        JsonNode grant = receipt.path("currentUseGrant"); String issue = "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH";
        verifySelfPin(mapper, grant, "grantSha256", issue);
        require("current-native-clinical-grant-v1".equals(grant.path("schemaVersion").asText())
                && inventory.path("sessionIdentity").equals(grant.path("sessionIdentity"))
                && receipt.path("sessionIdentity").equals(grant.path("sessionIdentity"))
                && grant.path("materialSha256").equals(receipt.path("materialSha256"))
                && hash(grant.path("materialSha256").asText()) && hash(grant.path("nativeConsumerExecutionSha256").asText())
                && Set.of("RESERVED_NOT_AWARDED", "UNAVAILABLE").contains(grant.path("status").asText())
                && grant.path("awardedPointsKnown").isBoolean() && !grant.path("awardedPointsKnown").asBoolean()
                && grant.path("awardedPoints").isNull() && grant.path("allocatedEventIds").isArray() && grant.path("allocatedEventIds").isEmpty()
                && grant.path("allocatedFactIds").isArray() && grant.path("allocatedFactIds").isEmpty()
                && grant.path("reservedEventIds").isArray() && grant.path("reservedFactIds").isArray()
                && grant.path("evidenceSelected").isBoolean() && inventory.at("/sessionIdentity/enabledCriteria").isArray(), issue);
        boolean selected = false;
        for (JsonNode criterion : inventory.at("/sessionIdentity/enabledCriteria")) if ("ACMG_AMP_2015:PP4".equals(criterion.asText())) selected = true;
        require(grant.path("evidenceSelected").asBoolean() == selected, issue);
        for (String field : Set.of("candidateId", "hypothesisId", "bindingIdentitySha256"))
            require(attempt.path(field).equals(grant.path("attemptIdentity").path(field))
                    && attempt.path(field).equals(receipt.path("attemptIdentity").path(field)), issue);
        require(attempt.path("bindingStatus").equals(grant.at("/attemptIdentity/bindingStatus")), issue);
    }

    private static void verifyAdoption(ObjectMapper mapper, JsonNode receipt, JsonNode core, JsonNode source,
            JsonNode evaluation, JsonNode originals) {
        String issue = "CURRENT_CLINICAL_ARCHIVE_CORE_ARTIFACT_MISMATCH"; JsonNode adoption = receipt.path("currentAdoption");
        if (!receipt.has("currentAdoption")) { require(!receipt.path("complete").asBoolean(false), issue); return; }
        verifySelfPin(mapper, adoption, "adoptionSha256", issue); JsonNode grant = receipt.path("currentUseGrant");
        require("current-native-clinical-adoption-v1".equals(adoption.path("schemaVersion").asText())
                && adoption.path("grantSha256").equals(grant.path("grantSha256"))
                && adoption.path("coreExecutionSha256").equals(receipt.path("coreExecutionSha256"))
                && adoption.path("nativeConsumerExecutionSha256").equals(grant.path("nativeConsumerExecutionSha256"))
                && adoption.path("complete").isBoolean() && adoption.path("complete").equals(receipt.path("complete"))
                && adoption.path("kind").equals(receipt.path("kind")) && adoption.path("reasonCode").equals(receipt.path("reasonCode"))
                && adoption.path("awardedPoints").equals(receipt.path("finalPoints"))
                && adoption.path("allocatedEventIds").isArray() && adoption.path("allocatedEventIds").equals(receipt.path("eventIds"))
                && adoption.path("allocatedFactIds").isArray() && adoption.path("allocatedFactIds").equals(receipt.path("exclusiveFactIds")), issue);
        if (!adoption.path("complete").asBoolean()) {
            require("UNAVAILABLE".equals(adoption.path("kind").asText()) && adoption.path("awardedPoints").isNull()
                    && adoption.path("allocatedEventIds").isEmpty() && adoption.path("allocatedFactIds").isEmpty()
                    && receipt.path("sourceResultSha256").isNull() && receipt.path("evaluationSha256").isNull(), issue); return;
        }
        String kind = adoption.path("kind").asText(); boolean selected = grant.path("evidenceSelected").asBoolean();
        require(Set.of("ADOPTED", "NOT_ADOPTED", "NOT_SELECTED").contains(kind)
                && (selected ? !"NOT_SELECTED".equals(kind) : "NOT_SELECTED".equals(kind)), issue);
        if ("NOT_SELECTED".equals(kind)) require(receipt.path("sourceResultSha256").isNull() && receipt.path("evaluationSha256").isNull(), issue);
        else require(receipt.path("sourceResultSha256").equals(core.path("sourceResultSha256"))
                && receipt.path("evaluationSha256").equals(core.path("evaluationSha256")), issue);
        if ("ADOPTED".equals(kind)) require(adoption.path("awardedPoints").isTextual()
                && adoption.path("awardedPoints").equals(source.path("appliedPoints"))
                && adoption.path("allocatedEventIds").equals(grant.path("reservedEventIds"))
                && adoption.path("allocatedFactIds").equals(grant.path("reservedFactIds")), issue);
        else require("0".equals(adoption.path("awardedPoints").asText())
                && adoption.path("allocatedEventIds").isEmpty() && adoption.path("allocatedFactIds").isEmpty(), issue);
        if ("MET".equals(core.path("actualStatus").asText())) {
            require(!"NOT_ADOPTED".equals(kind) && !adoption.has("knownNoAdoptionBasis")
                    && "COMPLETED".equals(evaluation.at("/deterministicTrace/executionStatus").asText())
                    && "APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText()), issue);
        } else {
            JsonNode basis = adoption.path("knownNoAdoptionBasis"), facts = originals.path(receipt.path("materialOriginalsSha256").asText()).path("nativeFacts");
            require(!"ADOPTED".equals(kind) && "NOT_EVALUATED".equals(core.path("actualStatus").asText())
                    && "PP4_PHENOTYPE_REQUIREMENT_NOT_MET".equals(core.path("actualReasonCode").asText())
                    && "NOT_EVALUATED".equals(source.at("/evaluationResult/status").asText())
                    && "PP4_PHENOTYPE_REQUIREMENT_NOT_MET".equals(source.at("/evaluationResult/reasonCode").asText())
                    && "current-native-pp4-known-nonadoption-v1".equals(basis.path("schemaVersion").asText())
                    && "COMPLETE_CHECKED_NATIVE_ROSTER_EXPLICIT_OPPOSITE_PRESENCE".equals(basis.path("basis").asText())
                    && basis.path("originalCoreStatus").equals(core.path("actualStatus"))
                    && basis.path("originalCoreReasonCode").equals(core.path("actualReasonCode"))
                    && canonical(mapper, facts).equals(basis.path("nativeFactsSha256").asText())
                    && canonical(mapper, facts.path("requirements")).equals(basis.path("requirementsSha256").asText())
                    && canonical(mapper, facts.path("observations")).equals(basis.path("observationsSha256").asText())
                    && canonical(mapper, source.at("/sourceBundle/observations/0/phenotypeReview")).equals(basis.path("actualCorePhenotypeReviewSha256").asText()), issue);
        }
    }
    private static JsonNode parse(ObjectMapper mapper, byte[] bytes) {
        require(bytes != null, "CURRENT_CLINICAL_ARCHIVE_RAW_REQUIRED");
        try {
            JsonNode node = mapper.readTree(bytes); require(node != null && node.isObject(), "CURRENT_CLINICAL_ARCHIVE_DOCUMENT_INVALID"); return node;
        } catch (IOException invalid) { throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_DOCUMENT_INVALID", invalid); }
    }

    private static TreeMap<String, byte[]> readRaw(JsonNode artifacts) {
        require(artifacts.isObject(), "CURRENT_CLINICAL_ARCHIVE_RAW_REQUIRED"); var raw = new TreeMap<String, byte[]>();
        artifacts.properties().forEach(entry -> {
            JsonNode blob = entry.getValue();
            require(hash(entry.getKey()) && blob.isObject() && blob.size() == 4 && entry.getKey().equals(blob.path("rawSha256").asText())
                    && "BASE64".equals(blob.path("encoding").asText()) && blob.path("byteLength").isIntegralNumber()
                    && blob.path("byteLength").canConvertToInt() && blob.path("byteLength").asInt() >= 0 && blob.path("base64").isTextual(),
                    "CURRENT_CLINICAL_ARCHIVE_RAW_INVALID");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(blob.path("base64").asText()); }
            catch (IllegalArgumentException malformed) { throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_RAW_INVALID", malformed); }
            require(blob.path("byteLength").asInt() == bytes.length && entry.getKey().equals(rawSha(bytes))
                    && Base64.getEncoder().encodeToString(bytes).equals(blob.path("base64").asText()), "CURRENT_CLINICAL_ARCHIVE_RAW_PIN_MISMATCH");
            raw.put(entry.getKey(), bytes);
        });
        return raw;
    }

    private static void verifyTableReplays(ObjectMapper mapper, JsonNode replays, Map<String, JsonNode> primary, Map<String, byte[]> raw,
            JsonNode nativeCodebook, Map<String, JsonNode> cache) {
        require(replays.isObject(), "CURRENT_CLINICAL_ARCHIVE_TABLE_REPLAY_MISMATCH"); var expected = new TreeSet<String>();
        for (var role : primary.entrySet()) {
            JsonNode binding = role.getValue();
            require(binding.path("tableIds").isObject(), "CURRENT_CLINICAL_ARCHIVE_BINDING_MISMATCH");
            var tables = binding.path("tableIds").properties().iterator();
            while (tables.hasNext()) {
                var table = tables.next(); String key = role.getKey() + ":" + table.getKey();
                if (nativeCodebook.has(table.getKey())) expected.add(key);
                JsonNode saved = replays.path(key);
                if (nativeCodebook.has(table.getKey())) require(binding.path("sourceId").equals(saved.path("sourceId")) && binding.path("rawSha256").equals(saved.path("rawSha256")),
                        "CURRENT_CLINICAL_ARCHIVE_TABLE_REPLAY_MISMATCH");
                try {
                    String cacheKey = binding.path("rawSha256").asText() + ":" + binding.path("pmcid").asText() + ":" + table.getValue().asText();
                    JsonNode actual = cache.get(cacheKey);
                    if (actual == null) {
                        actual = mapper.valueToTree(Pp4PrimaryTableReplay.replay(raw.get(binding.path("rawSha256").asText()), binding.path("rawSha256").asText(),
                                binding.path("pmcid").asText(), table.getValue().asText()));
                        cache.put(cacheKey, actual);
                    }
                    // Non-scientific acquired tables (e.g. sample_links) are still replayed,
                    // but are not invented inside the historical qualified-material projection.
                    if (nativeCodebook.has(table.getKey())) require(actual.equals(saved.path("table")), "CURRENT_CLINICAL_ARCHIVE_TABLE_REPLAY_MISMATCH");
                } catch (IOException malformed) { throw new IllegalArgumentException("CURRENT_CLINICAL_ARCHIVE_TABLE_REPLAY_MISMATCH", malformed); }
            }
        }
        var present = new TreeSet<String>(); replays.fieldNames().forEachRemaining(present::add);
        require(expected.equals(present), "CURRENT_CLINICAL_ARCHIVE_TABLE_REPLAY_MISMATCH");
    }
    private static Set<String> stringPins(JsonNode values) {
        require(values.isArray(), "CURRENT_CLINICAL_ARCHIVE_MATERIAL_REFERENCES_MISMATCH"); var pins = new TreeSet<String>();
        for (JsonNode value : values) require(value.isTextual() && hash(value.asText()) && pins.add(value.asText()), "CURRENT_CLINICAL_ARCHIVE_MATERIAL_REFERENCES_MISMATCH");
        return pins;
    }
    private static void verifySelfPin(ObjectMapper mapper, JsonNode value, String field, String reason) {
        require(value.isObject(), reason); var content = ((ObjectNode) value).deepCopy(); content.remove(field);
        require(hash(value.path(field).asText()) && canonical(mapper, content).equals(value.path(field).asText()), reason);
    }
    private static String canonical(ObjectMapper mapper, JsonNode value) { return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value); }
    private static String rawSha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
