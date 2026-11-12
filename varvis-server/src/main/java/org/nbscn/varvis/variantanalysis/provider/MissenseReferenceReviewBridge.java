package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.lang.Nullable;

/** Bounded offline file bridge. All scientific decisions and final candidate approval are external. */
public final class MissenseReferenceReviewBridge {
    private static final int MAX_FILE = 4_000_000, MAX_TOTAL = 32_000_000;
    private static final List<String> FACT_ROLES = List.of("referenceDraft", "rnaDraft", "draftReport", "review");
    private static final List<String> COMPONENTS = List.of("material", "nativeModel", "annotationResource",
            "referenceAudit", "rnaReview", "spliceAnnotationProof", "policy", "substitutionMatrix", "aminoAcidRelations");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    public record Result(boolean bridged, @Nullable Path manifestPath, @Nullable String manifestSha256, ObjectNode report) { }

    /** A fresh invocation reads every pinned input; neither upstream discovery nor the preparer is run. */
    public Result prepare(Path manifestPath, String manifestRawSha256, Path output) {
        return new Invocation().run(manifestPath, manifestRawSha256, output);
    }

    private final class Invocation {
        private final ArrayNode inputs = mapper.createArrayNode(), written = mapper.createArrayNode();
        private final LinkedHashMap<String, Source> sources = new LinkedHashMap<>();
        private final LinkedHashMap<String, Document> files = new LinkedHashMap<>();
        private int bytesRead;

        Result run(Path manifestPath, String manifestPin, Path output) {
            ObjectNode report = mapper.createObjectNode().put("schemaVersion", "missense-reference-review-bridge-report-v1")
                    .put("status", "BLOCKED").put("bridgeStatus", "BLOCKED").put("staticPreparation", "NOT_RUN")
                    .put("finalApproval", "NOT_PROVIDED").put("runtimeExecution", "NOT_RUN").put("scientificAdoption", false);
            boolean created = false;
            try {
                require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS), "OUTPUT_EXISTS_REFUSE_OVERWRITE");
                Files.createDirectory(output); created = true;
                Document manifestFile = read(manifestPath, manifestPin, "manifest", true);
                ObjectNode manifest = object(manifestFile.value(), "MANIFEST_OBJECT_REQUIRED");
                require("missense-reference-review-bridge-manifest-v1".equals(manifest.path("schemaVersion").asText()), "MANIFEST_VERSION_UNSUPPORTED");
                String dataClass = text(manifest, "dataClass"), purpose = text(manifest, "purpose");
                require(Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(dataClass), "DATA_CLASS_UNSUPPORTED");
                require(!"SYNTHETIC".equals(dataClass) || "TEST_ONLY".equals(purpose), "SYNTHETIC_TEST_ONLY_REQUIRED");
                report.put("dataClass", dataClass).put("purpose", purpose);
                Path root = Objects.requireNonNull(manifestPath.toAbsolutePath().normalize().getParent());
                Map<String, Document> facts = new LinkedHashMap<>();
                for (String role : FACT_ROLES.subList(0, 3)) facts.put(role, input(root, manifest.path(role), role, true));
                require(manifest.has("review") != manifest.has("reusableReview"), "EXACTLY_ONE_REVIEW_INPUT_REQUIRED");
                Document reusable = null;
                if (manifest.has("reusableReview")) {
                    reusable = input(root, manifest.path("reusableReview"), "reusableReview", true);
                    ObjectNode binding = mapper.createObjectNode().put("draftId", facts.get("referenceDraft").value().path("draftId").asText());
                    for (var entry : facts.entrySet()) binding.set(entry.getKey(), entry.getValue().pins());
                    ObjectNode expanded = new MissenseReusableReview(mapper).expand(reusable.value(),
                            facts.get("referenceDraft").value(), facts.get("rnaDraft").value(), binding);
                    facts.put("review", document(expanded));
                    files.put("reusable-review-library.json", reusable);
                    report.set("reviewReuse", expanded.path("reuseProvenance").deepCopy());
                } else facts.put("review", input(root, manifest.path("review"), "review", true));
                Document seedFile = input(root, manifest.path("preparationSeed"), "preparationSeed", true);
                ObjectNode seed = object(seedFile.value(), "SEED_OBJECT_REQUIRED");
                require("missense-preparation-manifest-v1".equals(seed.path("schemaVersion").asText()), "SEED_SCHEMA_UNSUPPORTED");
                require(!seed.has("approval"), "SEED_APPROVAL_FORBIDDEN");
                require(dataClass.equals(seed.path("dataClass").asText()) && purpose.equals(seed.path("purpose").asText()), "SEED_DATA_CLASS_OR_PURPOSE_MISMATCH");
                String criterion = text(seed, "criterion");
                require(Set.of(MissenseComparisonRuntime.PS1, MissenseComparisonRuntime.PM5).contains(criterion), "CRITERION_UNSUPPORTED");
                ObjectNode background = object(manifest.path("background"), "BACKGROUND_REQUIRED");
                ArrayNode backgrounds = array(seed.path("backgrounds"), 32, "BACKGROUND_COUNT_INVALID");
                require(!backgrounds.isEmpty(), "BACKGROUND_COUNT_INVALID");
                int target = -1; Set<String> seen = new HashSet<>();
                for (int i = 0; i < backgrounds.size(); i++) {
                    ObjectNode current = object(backgrounds.get(i).path("background"), "BACKGROUND_REQUIRED");
                    require(seen.add(adapter.canonicalSha256(current)), "BACKGROUND_DUPLICATE");
                    if (background.equals(current)) target = i;
                }
                require(target >= 0, "TARGET_BACKGROUND_NOT_FOUND");
                checkBinding(manifest, facts, background);
                Path seedRoot = Objects.requireNonNull(root.resolve(text(manifest.path("preparationSeed"), "path")).toAbsolutePath().normalize().getParent());
                ArrayNode seedSources = array(seed.path("sources"), 64, "SOURCE_COUNT_INVALID");
                require(!seedSources.isEmpty(), "SOURCE_COUNT_INVALID");
                Set<String> seedIds = new HashSet<>();
                for (JsonNode source : seedSources) {
                    String id = text(source.path("sourceRecord"), "sourceId");
                    require(seedIds.add(id), "SOURCE_ID_DUPLICATE");
                    register(source(seedRoot, source, false));
                }
                ArrayNode additions = mapper.createArrayNode(); Set<String> factIds = new HashSet<>(), evidenceIds = new HashSet<>();
                for (String role : FACT_ROLES) {
                    Document fact = Objects.requireNonNull(facts.get(role));
                    ObjectNode record = sourceRecord(manifest.path("factSources").path(role), fact.canonical());
                    String id = text(record, "sourceId"); require(factIds.add(id), "FACT_SOURCE_ID_DUPLICATE");
                    register(new Source(record, fact, "JSON", "CANONICAL_JSON")); additions.add(record);
                }
                ArrayNode evidence = array(manifest.path("reviewEvidence"), 64, "REVIEW_EVIDENCE_REQUIRED");
                require(!evidence.isEmpty(), "REVIEW_EVIDENCE_REQUIRED");
                for (JsonNode descriptor : evidence) {
                    Source source = source(root, descriptor, true); String id = text(source.record(), "sourceId");
                    require(!factIds.contains(id), "FACT_SOURCE_CANNOT_BE_REVIEW_EVIDENCE");
                    String evidenceCanonical = comparableCanonical(source.document());
                    for (Document fact : facts.values()) require(!fact.raw().equals(source.document().raw())
                            && !fact.canonical().equals(evidenceCanonical), "FACT_SOURCE_CANNOT_BE_REVIEW_EVIDENCE");
                    require(reusable == null || (!reusable.raw().equals(source.document().raw())
                            && !reusable.canonical().equals(evidenceCanonical)), "FACT_SOURCE_CANNOT_BE_REVIEW_EVIDENCE");
                    require(evidenceIds.add(id), "REVIEW_EVIDENCE_ID_DUPLICATE"); register(source); additions.add(source.record());
                }
                if (reusable != null) new MissenseReusableReview(mapper).checkSources(reusable.value(),
                        sources.values().stream().map(mappedItem -> mappedItem.record()).toList());
                var mapping = new MissenseReferenceReviewMapping(mapper).map(
                        object(Objects.requireNonNull(facts.get("referenceDraft")).value(), "REFERENCE_DRAFT_REQUIRED"),
                        object(Objects.requireNonNull(facts.get("rnaDraft")).value(), "RNA_DRAFT_REQUIRED"),
                        object(Objects.requireNonNull(facts.get("draftReport")).value(), "DRAFT_REPORT_REQUIRED"),
                        object(Objects.requireNonNull(facts.get("review")).value(), "REVIEW_REQUIRED"),
                        background, sources.keySet(), evidenceIds);
                if (manifest.has("exportReusableReview")) {
                    require(manifest.path("exportReusableReview").isBoolean(), "REUSABLE_EXPORT_FLAG_INVALID");
                    if (manifest.path("exportReusableReview").asBoolean()) files.put("exported-reusable-review.json",
                            document(new MissenseReusableReview(mapper).capture(facts.get("review").value(),
                                    facts.get("referenceDraft").value(), facts.get("rnaDraft").value(),
                                    sources.values().stream().map(mappedItem -> mappedItem.record()).toList())));
                }
                Document expert = null;
                if (manifest.has("expertEvidence")) {
                    expert = input(root, manifest.path("expertEvidence"), "expertEvidence", true);
                    MissenseExpertReferenceEvidence.bind(mapping.referenceAudit(), expert.value(), expert.pins(), evidenceIds, sources.keySet());
                    files.put("expert-evidence.UNREVIEWED.json", expert);
                } else {
                    for (JsonNode reference : mapping.referenceAudit().path("references"))
                        require(!reference.has("expertEvidenceReferenceId"), "EXPERT_EVIDENCE_PACKET_REQUIRED");
                }
                // A declaration may explicitly cite another already pinned seed source as well.
                collectDeclaredSources(mapping.referenceAudit(), additions);
                for (JsonNode reference : mapping.referenceAudit().path("references")) {
                    collectDeclaredSources(reference, additions);
                    collectDeclaredSources(reference.path("independence"), additions);
                    for (JsonNode condition : reference.path("conditionMappings")) collectDeclaredSources(condition, additions);
                    if (reference.has("qualification")) {
                        collectDeclaredSources(reference.path("qualification"), additions);
                        for (JsonNode item : reference.at("/qualification/evidence")) collectDeclaredSources(item, additions);
                    }
                }
                collectDeclaredSources(mapping.rnaReview(), additions);
                for (JsonNode comparison : mapping.rnaReview().path("comparisons")) collectDeclaredSources(comparison, additions);
                ObjectNode identity = mapper.createObjectNode();
                identity.put("manifestRawSha256", manifestFile.raw()).put("manifestCanonicalSha256", manifestFile.canonical());
                for (var entry : facts.entrySet()) identity.set(entry.getKey(), entry.getValue().pins());
                identity.set("preparationSeed", seedFile.pins()); identity.set("background", background.deepCopy());
                if (expert != null) identity.set("expertEvidence", expert.pins());
                if (reusable != null) identity.set("reusableReview", reusable.pins());
                String bridgeId = "missense-review-bridge-sha256-" + adapter.canonicalSha256(identity);
                report.put("bridgeId", bridgeId).put("draftId", facts.get("referenceDraft").value().path("draftId").asText())
                        .put("criterion", criterion).put("targetBackgroundIndex", target);
                report.set("background", background.deepCopy()); report.set("binding", identity);
                ObjectNode assembled = assemble(seed, seedRoot, target, mapping, additions);
                ObjectNode selection = mapping.selectionMap().deepCopy().put("bridgeId", bridgeId);
                Document complete = document(assembled);
                int consumption = complete.bytes().length;
                // Each descriptor is read by the original preparer, even if two descriptors name the same file.
                for (JsonNode source : assembled.path("sources")) consumption = consumption(consumption, source);
                for (String role : List.of("comparatorProfile", "bundleHeader", "catalog")) consumption = consumption(consumption, assembled.path(role));
                for (JsonNode row : assembled.path("backgrounds")) for (String role : COMPONENTS)
                    if (row.has(role)) consumption = consumption(consumption, row.path(role));
                report.put("preparerInputBytes", consumption).put("actualInputBytes", bytesRead);
                report.put("originalDraftReportSourceId", text(manifest.at("/factSources/draftReport"), "sourceId"));
                report.put("originalDraftReportFile", sourceName(text(manifest.at("/factSources/draftReport"), "sourceId")));
                report.put("gapAndSearchSemantics", "PRESERVED_IN_PINNED_ORIGINAL_REPORT_NO_COMPLETENESS_INFERRED");
                for (var entry : files.entrySet()) write(output, entry.getKey(), entry.getValue());
                write(output, "selection-map.json", document(selection));
                write(output, "manifest.pending.json", complete);
                report.put("status", "BRIDGED").put("bridgeStatus", "BRIDGED").put("externalReviewBinding", "MATCHED_EXTERNAL_DECLARATION")
                        .put("completionEntry", "manifest.json").put("manifestRawSha256", complete.raw());
                write(output, "bridge-report.json", document(report));
                ArrayNode completed = written.deepCopy();
                for (JsonNode item : completed) if ("manifest.pending.json".equals(item.path("file").asText())) ((ObjectNode) item).put("file", "manifest.json");
                write(output, "artifact-manifest.json", document(receipt(report, completed, output)));
                // The consumable manifest is the sole completion entry and is published only after all receipts.
                Files.move(output.resolve("manifest.pending.json"), output.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE);
                return new Result(true, output.resolve("manifest.json"), complete.raw(), report);
            } catch (IOException | RuntimeException failure) {
                report.put("status", "BLOCKED").put("bridgeStatus", "BLOCKED").put("reasonCode", reason(failure))
                        .put("residualFilesAreIncomplete", true).put("actualInputBytes", bytesRead);
                if (report.path("reasonCode").asText().startsWith("DECLARED_PIN_MISMATCH_"))
                    report.put("requiredAction", "EXTERNALLY_REPREPARE_SEED_WITH_MATCHING_FIXED_PINS_OR_ORIGINALLY_PERMITTED_DERIVE");
                report.remove(List.of("manifestRawSha256", "completionEntry", "externalReviewBinding"));
                if (created) {
                    try {
                        Document failed = document(report);
                        Files.write(output.resolve("bridge-report.json"), failed.bytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        ArrayNode incomplete = mapper.createArrayNode();
                        for (JsonNode row : written) if (!Set.of("bridge-report.json", "artifact-manifest.json").contains(row.path("file").asText())) incomplete.add(row.deepCopy());
                        incomplete.add(failed.pins().put("file", "bridge-report.json").put("bytes", failed.bytes().length));
                        Files.write(output.resolve("artifact-manifest.json"), document(receipt(report, incomplete, output)).bytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException | RuntimeException receiptFailure) { report.put("receiptStatus", "WRITE_FAILED"); }
                }
                return new Result(false, null, null, report);
            }
        }

        private ObjectNode assemble(ObjectNode seed, Path root, int target, MissenseReferenceReviewMapping.Mapping mapping,
                                    ArrayNode additions) throws IOException {
            ObjectNode result = seed.deepCopy(); ArrayNode descriptions = result.putArray("sources");
            for (Source source : sources.values()) {
                String name = sourceName(text(source.record(), "sourceId")); files.put(name, source.document());
                ObjectNode d = source.document().descriptor(name).put("format", source.format()).put("sourceDigestKind", source.kind());
                d.set("sourceRecord", source.record().deepCopy()); descriptions.add(d);
            }
            Map<String, ObjectNode> common = new HashMap<>();
            for (String role : List.of("comparatorProfile", "bundleHeader", "catalog")) {
                Document d = input(root, seed.path(role), role, true); common.put(role, object(d.value(), "INPUT_OBJECT_REQUIRED"));
                result.set(role, addFile(role + ".json", d));
            }
            ArrayNode checkedPolicies = mapper.createArrayNode();
            var originalPins = new MissenseMaterialPreparation();
            for (int i = 0; i < seed.path("backgrounds").size(); i++) {
                JsonNode seedRow = seed.path("backgrounds").get(i); ObjectNode row = object(result.path("backgrounds").get(i), "BACKGROUND_REQUIRED");
                Map<String, ObjectNode> components = new HashMap<>();
                for (String role : COMPONENTS) {
                    if (!seedRow.has(role) && Set.of("substitutionMatrix", "aminoAcidRelations").contains(role)) continue;
                    Document d = input(root, seedRow.path(role), "background-" + i + "-" + role, true);
                    ObjectNode value = object(d.value(), "INPUT_OBJECT_REQUIRED");
                    if (i == target) {
                        if ("referenceAudit".equals(role)) d = document(mapping.referenceAudit());
                        else if ("rnaReview".equals(role)) d = document(mapping.rnaReview());
                        else if ("policy".equals(role)) {
                            value = value.deepCopy(); ArrayNode records = array(value.path("sources"), 64, "SOURCE_RECORDS_REQUIRED");
                            Set<String> present = new HashSet<>();
                            for (JsonNode source : records) { verifyRegistered(source); require(present.add(text(source, "sourceId")), "SOURCE_ID_DUPLICATE"); }
                            for (JsonNode source : additions) if (present.add(text(source, "sourceId"))) records.add(source.deepCopy());
                            d = document(value);
                        }
                    }
                    value = object(d.value(), "INPUT_OBJECT_REQUIRED"); MissenseMaterialPreparation.noPrivatePaths(value);
                    components.put(role, value.deepCopy());
                    String name = i == target && "referenceAudit".equals(role) ? "reference-audit.json"
                            : i == target && "rnaReview".equals(role) ? "rna-review.json"
                            : String.format(Locale.ROOT, "bg-%03d-%s.json", i, role);
                    row.set(role, addFile(name, d));
                }
                // Validate fixed dependent pins using the original pure bind rules on throwaway copies.
                // The exported seed keeps its original $DERIVE/fixed values; no static candidate is approved here.
                ObjectNode material = Objects.requireNonNull(components.get("material"));
                for (String role : List.of("nativeModel", "annotationResource", "referenceAudit", "rnaReview", "spliceAnnotationProof")) material.set(role, components.get(role));
                require(material.at("/referenceAudit/references").isArray() && material.at("/referenceAudit/references").size() <= 256, "REFERENCE_COUNT_INVALID");
                require(material.at("/rnaReview/comparisons").isArray() && material.at("/rnaReview/comparisons").size() <= 1024, "RNA_PAIR_COUNT_INVALID");
                ObjectNode policy = Objects.requireNonNull(components.get("policy"));
                for (JsonNode source : policy.path("sources")) verifyRegistered(source);
                for (JsonNode source : material.at("/nativeModel/sourceRecords")) verifyRegistered(source);
                String criterion = text(seed, "criterion"); ObjectNode rule = object(policy.path("rules").path(criterion.substring("ACMG_AMP_2015:".length())), "RULE_REQUIRED");
                if ("SUBSTITUTION_MATRIX_SINGLE_REFERENCE".equals(rule.path("mode").asText())) {
                    material.set("substitutionMatrix", object(components.get("substitutionMatrix"), "MATRIX_REQUIRED"));
                    originalPins.bind(rule, "matrixSha256", material.path("substitutionMatrix"));
                } else if ("AUDITED_AMINO_ACID_TRIPLES_SINGLE_REFERENCE".equals(rule.path("mode").asText())) {
                    ObjectNode triples = object(components.get("aminoAcidRelations"), "TRIPLES_REQUIRED");
                    material.set("aminoAcidRelations", array(triples.path("relations"), 8000, "TRIPLES_INVALID"));
                }
                originalPins.bind(object(material.path("spliceAnnotationProof"), "SPLICE_PROOF_REQUIRED"), "modelSha256", material.path("nativeModel"));
                for (String role : List.of("nativeModel", "annotationResource", "spliceAnnotationProof")) originalPins.bind(policy, role + "Sha256", material.path(role));
                originalPins.bind(policy, "comparatorProfileSha256", Objects.requireNonNull(common.get("comparatorProfile")));
                originalPins.bind(policy, "materialsSha256", material); checkedPolicies.add(policy);
            }
            ObjectNode catalog = Objects.requireNonNull(common.get("catalog")).deepCopy();
            for (JsonNode source : catalog.path("sources")) verifyRegistered(source);
            originalPins.bindCatalog(catalog, checkedPolicies, text(seed, "criterion"));
            return result;
        }

        private void checkBinding(ObjectNode manifest, Map<String, Document> facts, ObjectNode background) {
            ObjectNode reference = object(Objects.requireNonNull(facts.get("referenceDraft")).value(), "REFERENCE_DRAFT_REQUIRED");
            String id = text(reference, "draftId");
            String[] schemas = {"missense-reference-audit-draft-v1", "missense-rna-pairs-draft-v1", "missense-reference-draft-report-v1"};
            for (int i = 0; i < 3; i++) {
                JsonNode node = Objects.requireNonNull(facts.get(FACT_ROLES.get(i))).value();
                require(schemas[i].equals(node.path("schemaVersion").asText()), "DRAFT_SCHEMA_UNSUPPORTED");
                require(id.equals(node.path("draftId").asText()) && manifest.path("dataClass").equals(node.path("dataClass")), "DRAFT_BINDING_MISMATCH");
                require(node.path("draft").isBoolean() && node.path("draft").booleanValue() && "UNREVIEWED".equals(node.path("reviewStatus").asText()), "UNREVIEWED_DRAFT_REQUIRED");
            }
            require("DRAFT_GENERATED".equals(Objects.requireNonNull(facts.get("draftReport")).value().path("extractionStatus").asText()), "DRAFT_REPORT_NOT_GENERATED");
            JsonNode review = Objects.requireNonNull(facts.get("review")).value();
            require("missense-reference-review-declaration-v1".equals(review.path("schemaVersion").asText()), "REVIEW_SCHEMA_UNSUPPORTED");
            require(manifest.path("dataClass").equals(review.path("dataClass")) && manifest.path("purpose").equals(review.path("purpose")), "REVIEW_DATA_CLASS_OR_PURPOSE_MISMATCH");
            require(background.equals(review.path("background")), "REVIEW_BACKGROUND_MISMATCH");
            require(id.equals(review.at("/draftBinding/draftId").asText()), "REVIEW_DRAFT_BINDING_MISMATCH");
            for (String role : FACT_ROLES.subList(0, 3)) require(Objects.requireNonNull(facts.get(role)).pins().equals(review.path("draftBinding").path(role)), "REVIEW_DRAFT_BINDING_MISMATCH");
        }

        private Source source(Path root, JsonNode descriptor, boolean derive) throws IOException {
            String format = text(descriptor, "format"), kind = text(descriptor, "sourceDigestKind");
            require(("JSON".equals(format) && Set.of("CANONICAL_JSON", "RAW_BYTES").contains(kind))
                    || ("BYTES".equals(format) && "RAW_BYTES".equals(kind)), "SOURCE_FORMAT_INVALID");
            Document document = input(root, descriptor, "source:" + text(descriptor.path("sourceRecord"), "sourceId"), "JSON".equals(format));
            String digest = "CANONICAL_JSON".equals(kind) ? document.canonical() : document.raw();
            ObjectNode record = object(descriptor.path("sourceRecord"), "SOURCE_RECORD_REQUIRED");
            require(derive || record.has("sha256"), "SOURCE_DIGEST_REQUIRED");
            return new Source(sourceRecord(record, digest), document, format, kind);
        }
        private ObjectNode sourceRecord(JsonNode metadata, String actual) {
            ObjectNode record = object(metadata, "SOURCE_RECORD_REQUIRED").deepCopy();
            if (record.has("sha256")) require(actual.equals(record.path("sha256").asText()), "SOURCE_DIGEST_MISMATCH");
            record.put("sha256", actual);
            require(MissenseComparisonPolicy.sourcesValid(mapper.createArrayNode().add(record)), "SOURCE_RECORD_INVALID"); return record;
        }
        private String comparableCanonical(Document document) {
            if (!document.canonical().isEmpty()) return document.canonical();
            // A raw byte evidence descriptor cannot give a reformatted factual JSON a new role.
            try {
                JsonNode parsed = mapper.readTree(document.bytes());
                return parsed == null || parsed.isMissingNode() ? "" : adapter.canonicalSha256(parsed);
            } catch (IOException notJsonEvidence) { return ""; }
        }
        private void register(Source source) {
            String id = text(source.record(), "sourceId"); Source prior = sources.putIfAbsent(id, source);
            require(prior == null || (prior.record().equals(source.record()) && prior.document().raw().equals(source.document().raw())
                    && prior.format().equals(source.format()) && prior.kind().equals(source.kind())), "SOURCE_ID_CONFLICT");
            require(sources.size() <= 64, "SOURCE_COUNT_INVALID");
        }
        private void verifyRegistered(JsonNode record) {
            Source source = sources.get(text(record, "sourceId"));
            require(source != null && source.record().equals(record), "SOURCE_NOT_EXTERNALLY_PINNED");
        }
        private void collectDeclaredSources(JsonNode node, ArrayNode records) {
            if (node.isObject() && node.has("sourceIds")) for (JsonNode id : node.path("sourceIds")) {
                Source source = sources.get(id.asText());
                require(source != null, "SOURCE_NOT_EXTERNALLY_PINNED");
                if (source != null) records.add(source.record());
            }
        }
        private String sourceName(String id) {
            int index = 0;
            for (var entry : sources.entrySet()) {
                if (id.equals(entry.getKey())) return String.format(Locale.ROOT, "source-%03d.%s", index, "JSON".equals(entry.getValue().format()) ? "json" : "bin");
                index++;
            }
            throw new IllegalArgumentException("SOURCE_NOT_EXTERNALLY_PINNED");
        }
        private ObjectNode addFile(String name, Document document) { require(files.putIfAbsent(name, document) == null, "OUTPUT_NAME_CONFLICT"); return document.descriptor(name); }
        private int consumption(int total, JsonNode descriptor) {
            Document d = Objects.requireNonNull(files.get(text(descriptor, "path")));
            require(d.bytes().length <= MAX_TOTAL - total, "OUTPUT_PREPARER_SIZE_LIMIT"); return total + d.bytes().length;
        }
        private Document input(Path root, JsonNode descriptor, String role, boolean json) throws IOException {
            Document result = read(root.resolve(text(descriptor, "path")), text(descriptor, "rawSha256"), role, json);
            if (json) require(result.canonical().equals(descriptor.path("canonicalSha256").asText()), "INPUT_CANONICAL_SHA_MISMATCH");
            return result;
        }
        private Document read(Path path, String expected, String role, boolean json) throws IOException {
            require(expected.matches("[0-9a-f]{64}"), "INPUT_RAW_PIN_REQUIRED");
            require(Files.isRegularFile(path), "INPUT_FILE_MISSING"); require(Files.size(path) <= MAX_FILE, "INPUT_SIZE_LIMIT");
            byte[] data; try (var stream = Files.newInputStream(path)) { data = stream.readNBytes(Math.min(MAX_FILE, MAX_TOTAL - bytesRead) + 1); }
            bytesRead += data.length; require(data.length <= MAX_FILE && bytesRead <= MAX_TOTAL, "INPUT_SIZE_LIMIT");
            String raw = Pm4MaterialPreparation.sha(data);
            ObjectNode row = inputs.addObject().put("role", role).put("path", path.toAbsolutePath().normalize().toString()).put("bytes", data.length).put("rawSha256", raw);
            require(raw.equals(expected), "INPUT_RAW_SHA_MISMATCH");
            JsonNode value = json ? mapper.readTree(data) : NullNode.instance;
            require(!json || (value != null && !value.isMissingNode()), "INPUT_JSON_REQUIRED");
            String canonical = json ? adapter.canonicalSha256(value) : "";
            if (json) row.put("canonicalSha256", canonical);
            return new Document(data, raw, canonical, value);
        }
        private void write(Path output, String name, Document document) throws IOException {
            Files.write(output.resolve(name), document.bytes(), StandardOpenOption.CREATE_NEW);
            written.add(document.pins().put("file", name).put("bytes", document.bytes().length));
        }
        private ObjectNode receipt(ObjectNode report, ArrayNode outputs, Path output) {
            ObjectNode result = mapper.createObjectNode().put("schemaVersion", "missense-reference-review-bridge-artifacts-v1")
                    .put("generatedAt", Instant.now().toString()).put("outputDirectory", output.toAbsolutePath().normalize().toString())
                    .put("status", report.path("status").asText()).put("completionEntry", "manifest.json").put("completionRequiresManifest", true);
            result.set("inputs", inputs.deepCopy()); result.set("outputs", outputs); return result;
        }
    }

    private record Source(ObjectNode record, Document document, String format, String kind) { }
    private record Document(byte[] bytes, String raw, String canonical, JsonNode value) {
        ObjectNode pins() {
            ObjectNode pins = JsonNodeFactory.instance.objectNode().put("rawSha256", raw);
            if (!canonical.isEmpty()) pins.put("canonicalSha256", canonical); return pins;
        }
        ObjectNode descriptor(String name) { return pins().put("path", name); }
    }
    private Document document(JsonNode value) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (OutputStream bounded = new OutputStream() {
            @Override public void write(int value) { require(buffer.size() < MAX_FILE, "OUTPUT_SIZE_LIMIT"); buffer.write(value); }
            @Override public void write(byte[] values, int offset, int length) { require(length <= MAX_FILE - buffer.size(), "OUTPUT_SIZE_LIMIT"); buffer.write(values, offset, length); }
        }) { mapper.writerWithDefaultPrettyPrinter().writeValue(bounded, value); }
        byte[] bytes = buffer.toByteArray(); return new Document(bytes, Pm4MaterialPreparation.sha(bytes), adapter.canonicalSha256(value), value);
    }
    private static ObjectNode object(@Nullable JsonNode node, String reason) { require(node instanceof ObjectNode, reason); return (ObjectNode) node; }
    private static ArrayNode array(JsonNode node, int max, String reason) { require(node instanceof ArrayNode && node.size() <= max, reason); return (ArrayNode) node; }
    private static String text(JsonNode node, String field) { require(MissenseComparisonPolicy.text(node.path(field)), "REQUIRED_FIELD_" + field); return node.path(field).asText(); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    private static String reason(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause instanceof IllegalArgumentException && message != null && message.matches("[A-Za-z0-9_]+")) return message;
        }
        if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException) return "INPUT_JSON_INVALID_OR_DUPLICATE_KEY";
        return failure instanceof IOException ? "INPUT_OUTPUT_IO_FAILURE" : "BRIDGE_INPUT_INVALID";
    }
    public static void main(String[] args) {
        try {
            require(args.length == 6 && "--manifest".equals(args[0]) && "--manifest-sha256".equals(args[2]) && "--output".equals(args[4]), "USAGE_MANIFEST_MANIFEST_SHA256_OUTPUT");
            Result result = new MissenseReferenceReviewBridge().prepare(Path.of(args[1]), args[3], Path.of(args[5]));
            System.out.println(result.report().toPrettyString()); if (!result.bridged()) System.exit(2);
        } catch (Exception failure) { System.err.println(reason(failure)); System.exit(2); }
    }
}
