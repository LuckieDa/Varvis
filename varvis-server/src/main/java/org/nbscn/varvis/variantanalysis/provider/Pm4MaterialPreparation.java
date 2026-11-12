package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Bounded offline assembly of externally pinned and reviewed PM4 inputs. No acquisition or review inference. */
public final class Pm4MaterialPreparation {
    public static final String VERSION = "1.0.0";
    private static final int MAX_BYTES = 4_000_000, MAX_TOTAL = 32_000_000;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final ArrayNode inputs = mapper.createArrayNode(), outputs = mapper.createArrayNode();
    private int bytesRead;

    public record Result(boolean ready, ObjectNode report) { }
    private record Sources(ArrayNode records, Map<String, ObjectNode> json) { }

    /** One instance per execution; output must not exist, including for blocked/draft preparations. */
    public Result prepare(Path manifestFile, String manifestRawSha256, Path output) throws IOException {
        if (Files.exists(output)) throw new IllegalArgumentException("OUTPUT_EXISTS_REFUSE_OVERWRITE");
        Files.createDirectory(output);
        ObjectNode report = mapper.createObjectNode().put("schemaVersion", "pm4-preparation-report-v1")
                .put("toolVersion", VERSION).put("scientificAdoption", false).put("status", "BLOCKED");
        ArrayNode backgrounds = report.putArray("backgrounds");
        try {
            require(bytesRead == 0, "PREPARER_INSTANCE_ALREADY_USED");
            ObjectNode manifest = json(read(manifestFile, manifestRawSha256, "manifest"));
            require("pm4-preparation-manifest-v1".equals(manifest.path("schemaVersion").asText()), "PREPARATION_MANIFEST_VERSION_UNSUPPORTED");
            String dataClass = text(manifest, "dataClass");
            require(Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(dataClass), "DATA_CLASS_UNSUPPORTED");
            report.put("dataClass", dataClass).put("purpose", manifest.path("purpose").asText());
            require(!"SYNTHETIC".equals(dataClass) || "TEST_ONLY".equals(manifest.path("purpose").asText()), "SYNTHETIC_TEST_ONLY_REQUIRED");
            Path root = Objects.requireNonNull(manifestFile.toAbsolutePath().getParent());
            ObjectNode profile = input(root, manifest.path("resourceProfile"), "resourceProfile");
            ObjectNode catalog = input(root, manifest.path("catalog"), "catalog");
            JsonNode items = manifest.path("backgrounds");
            require(items.isArray() && !items.isEmpty() && items.size() <= 32, "BACKGROUND_COUNT_INVALID");
            var policies = mapper.createArrayNode(); var models = mapper.createObjectNode(); var regions = mapper.createObjectNode();
            var prepared = new ArrayList<ObjectNode>(); var seen = new HashSet<String>();
            for (int i = 0; i < items.size(); i++) {
                ObjectNode receipt = backgrounds.addObject().put("index", i).put("status", "BLOCKED");
                try {
                    JsonNode item = items.get(i); ObjectNode background = object(item.path("background"), "BACKGROUND_INVALID");
                    receipt.set("background", background.deepCopy());
                    require(seen.add(adapter.canonicalSha256(background)), "BACKGROUND_DUPLICATE");
                    ObjectNode model = input(root, item.path("model"), "model-" + i);
                    ObjectNode assertions = input(root, item.path("regionAssertions"), "assertions-" + i);
                    ObjectNode policy = input(root, item.path("policy"), "policy-" + i);
                    Sources sources = sources(root, item.path("sources"), "source-" + i);
                    receipt.put("modelSha256", adapter.canonicalSha256(model)).put("regionAssertionsSha256", adapter.canonicalSha256(assertions))
                            .put("policySha256", adapter.canonicalSha256(policy));
                    receipt.set("sourceRecords", sources.records().deepCopy());
                    ObjectNode region = region(assertions, model, sources, receipt);
                    write(output, "model-" + i + ".json", model); write(output, "region-candidate-" + i + ".json", region);
                    receipt.put("modelSha256", adapter.canonicalSha256(model)).put("regionAssertionsSha256", adapter.canonicalSha256(assertions))
                            .put("regionMaterialSha256", adapter.canonicalSha256(region)).put("policySha256", adapter.canonicalSha256(policy))
                            .put("backgroundSha256", adapter.canonicalSha256(background));
                    checkBackground(background, model, policy);
                    for (JsonNode source : model.path("sourceRecords")) require(contains(sources.records(), source), "MODEL_SOURCE_NOT_EXTERNALLY_PINNED");
                    for (JsonNode source : policy.path("scientificSources")) require(contains(sources.records(), source), "POLICY_SOURCE_NOT_EXTERNALLY_PINNED");
                    ObjectNode context = context(background);
                    String issue = CodingTranscriptMapping.modelIssue(model, context);
                    require(issue == null, issue == null ? "MODEL_INVALID" : issue);
                    ObjectNode selection = mapper.createObjectNode().put("schemaVersion", AcmgSpecificationSelector.SCHEMA)
                            .put("contextId", context.path("contextId").asText()).put("outcome", "SPECIALIZED_POLICY_SELECTED")
                            .put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText())
                            .put("policySha256", adapter.canonicalSha256(policy));
                    selection.set("criterionIds", policy.path("criterionIds").deepCopy());
                    selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(selection));
                    issue = new Pm4CriterionEvaluator(mapper).policyIssue(context, policy, selection, model, region);
                    require(issue == null, issue == null ? "POLICY_INVALID" : issue);
                    policies.add(policy); models.set(adapter.canonicalSha256(model), model); regions.set(adapter.canonicalSha256(region), region);
                    prepared.add(receipt); receipt.put("status", "MATERIALS_VALIDATED_PENDING_EXTERNAL_REVIEW");
                } catch (IllegalArgumentException | IOException invalid) { receipt.put("reasonCode", code(invalid)); }
            }
            require(prepared.size() == items.size(), "BACKGROUND_PREPARATION_BLOCKED");
            checkCatalog(catalog, policies, prepared);
            require(new Pm4CriterionEvaluator(mapper).validRegistry(profile), "PM4_REGISTRY_IDENTITY_INVALID");
            var resource = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).bind(profile, profile.path("vepResourceId").asText());
            require("VEP".equals(resource.getProvider()) && "116.0".equals(resource.getProviderVersion()), "PM4_ANNOTATION_SOURCE_INVALID");
            ObjectNode approval = input(root, manifest.path("approval"), "approval");
            require("pm4-preparation-approval-v1".equals(approval.path("schemaVersion").asText())
                    && "REVIEWED_IMPLEMENTED".equals(approval.path("status").asText()) && !text(approval, "reviewId").isBlank(), "PREPARATION_REVIEW_REQUIRED");
            require(dataClass.equals(approval.path("dataClass").asText()), "REVIEW_DATA_CLASS_MISMATCH");
            require(adapter.canonicalSha256(catalog).equals(approval.path("catalogSha256").asText())
                    && adapter.canonicalSha256(profile).equals(approval.path("resourceProfileSha256").asText()), "REVIEW_GLOBAL_BINDING_MISMATCH");
            JsonNode bindings = approval.path("bindings"); require(bindings.isArray() && bindings.size() == prepared.size(), "REVIEW_BACKGROUND_BINDING_MISMATCH");
            for (ObjectNode receipt : prepared) {
                List<JsonNode> matches = new ArrayList<>();
                for (JsonNode binding : bindings) if (receipt.path("backgroundSha256").equals(binding.path("backgroundSha256"))) matches.add(binding);
                require(matches.size() == 1, "REVIEW_BACKGROUND_BINDING_MISMATCH");
                JsonNode binding = matches.get(0);
                for (String field : List.of("modelSha256", "regionAssertionsSha256", "regionMaterialSha256", "policySha256"))
                    require(receipt.path(field).equals(binding.path(field)), "REVIEW_MATERIAL_BINDING_MISMATCH");
                require(binding.path("allowUnknownCoverage").isBoolean(), "REVIEW_UNKNOWN_COVERAGE_DECISION_MISSING");
                require(!receipt.path("hasUnknownOrGap").asBoolean() || binding.path("allowUnknownCoverage").asBoolean(), "REGION_UNKNOWN_COVERAGE_NOT_REVIEWED");
                receipt.put("reviewId", approval.path("reviewId").asText());
            }
            ObjectNode bundle = object(approval.path("bundle"), "REVIEWED_BUNDLE_HEADER_MISSING").deepCopy();
            require("reviewed-rule-bundle-pm4-v2".equals(bundle.path("schemaVersion").asText())
                    && only(bundle, Set.of("schemaVersion", "bundleId", "bundleVersion", "review", "activation")), "REVIEWED_BUNDLE_HEADER_INVALID");
            bundle.set("catalog", catalog); bundle.put("catalogSha256", adapter.canonicalSha256(catalog)); bundle.set("policies", policies);
            ObjectNode materials = bundle.putObject("materials"); materials.set("transcriptModels", models); materials.set("pm4Regions", regions); materials.set("pm4ResourceProfile", profile);
            noPrivatePaths(bundle);
            new ReviewedRuleBundle(mapper, bundle, adapter.canonicalSha256(bundle));
            require(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle).length <= 8_000_000, "BUNDLE_SIZE_LIMIT");
            write(output, "bundle.json", bundle);
            ReviewedRuleBundle.load(mapper, output.resolve("bundle.json"), adapter.canonicalSha256(bundle));
            report.put("status", "READY").put("bundleSha256", adapter.canonicalSha256(bundle)).put("reviewId", approval.path("reviewId").asText());
            prepared.forEach(r -> r.put("status", "ENGINEERING_VALIDATED_EXTERNALLY_REVIEW_BOUND"));
        } catch (IllegalArgumentException | IOException invalid) { report.put("reasonCode", code(invalid)); }
        write(output, "preparation-report.json", report);
        ObjectNode external = mapper.createObjectNode().put("schemaVersion", "pm4-preparation-artifacts-v1").put("toolVersion", VERSION)
                .put("completedAt", Instant.now().toString()).put("dataClass", report.path("dataClass").asText())
                .put("status", report.path("status").asText()).put("scientificAdoption", false);
        external.set("inputs", inputs); external.set("outputs", outputs.deepCopy());
        write(output, "artifact-manifest.json", external);
        return new Result("READY".equals(report.path("status").asText()), report);
    }

    private void checkCatalog(ObjectNode catalog, ArrayNode policies, List<ObjectNode> prepared) {
        String issue = new AcmgSpecificationSelector(mapper, catalog, adapter.canonicalSha256(catalog), policies).configurationIssue();
        require(issue == null, issue == null ? "CATALOG_INVALID" : issue);
        require(catalog.path("entries").size() == policies.size(), "CATALOG_BACKGROUND_COUNT_MISMATCH");
        Set<String> seen = new HashSet<>();
        for (JsonNode policy : policies) {
            String key = policy.path("policyId").asText() + "@" + policy.path("policyVersion").asText();
            require(seen.add(key), "POLICY_AMBIGUOUS"); List<JsonNode> matches = new ArrayList<>();
            for (JsonNode entry : catalog.path("entries")) if (entry.path("scope").equals(policy.path("scope"))) matches.add(entry);
            require(matches.size() == 1, "CATALOG_SCOPE_AMBIGUOUS_OR_MISSING"); JsonNode entry = matches.get(0);
            require(Pm4CriterionEvaluator.CRITERION.equals(entry.path("criterionId").asText()), "CATALOG_CRITERION_MISMATCH");
            require("COMPLETE".equals(entry.path("searchStatus").asText()), "CATALOG_SEARCH_INCOMPLETE");
            require("SPECIALIZED_IMPLEMENTED".equals(entry.path("disposition").asText())
                    && "REVIEWED".equals(entry.at("/review/status").asText()) && !entry.at("/review/reviewId").asText().isBlank()
                    && !entry.path("baseRuleRelationship").asText().isBlank(), "CATALOG_CLAUSE_REVIEW_REQUIRED");
            require(!entry.path("sourceIds").isEmpty(), "CATALOG_SOURCE_MISSING");
            for (JsonNode id : entry.path("sourceIds")) require(hasSource(catalog.path("sources"), id.asText()), "CATALOG_SOURCE_MISSING");
            require(policy.path("policyId").equals(entry.at("/policyBinding/policyId"))
                    && policy.path("policyVersion").equals(entry.at("/policyBinding/policyVersion"))
                    && adapter.canonicalSha256(policy).equals(entry.at("/policyBinding/policySha256").asText()), "CATALOG_POLICY_BINDING_MISMATCH");
        }
        for (JsonNode source : catalog.path("sources")) {
            boolean bound = false; for (JsonNode row : prepared) if (contains(row.path("sourceRecords"), source)) bound = true;
            require(bound, "CATALOG_SOURCE_NOT_EXTERNALLY_PINNED");
        }
    }

    private void checkBackground(ObjectNode b, ObjectNode model, ObjectNode policy) {
        require("GRCh38".equals(b.path("assembly").asText()), "BACKGROUND_ASSEMBLY_UNSUPPORTED");
        require(text(b, "transcript").matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*"), "BACKGROUND_TRANSCRIPT_VERSION_REQUIRED");
        for (String field : List.of("assembly", "contig", "hgncId", "geneSymbol", "transcript", "protein"))
            require(!text(b, field).isBlank() && b.path(field).equals(model.path(field)), "MODEL_BACKGROUND_IDENTITY_MISMATCH");
        for (String field : List.of("assembly", "hgncId", "mondoId", "moi"))
            require(!text(b, field).isBlank() && b.path(field).equals(policy.path("scope").path(field)), "POLICY_BACKGROUND_IDENTITY_MISMATCH");
    }

    private ObjectNode context(ObjectNode b) {
        ObjectNode c = mapper.createObjectNode().put("contextId", "preparation-background-" + adapter.canonicalSha256(b)).put("moi", b.path("moi").asText());
        c.putObject("gene").set("hgncId", b.path("hgncId")); ((ObjectNode) c.path("gene")).set("symbol", b.path("geneSymbol"));
        c.putObject("disease").set("mondoId", b.path("mondoId"));
        String tx = b.path("transcript").asText(); int dot = tx.lastIndexOf('.');
        c.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)));
        ObjectNode allele = c.putObject("variant").putObject("evidenceQueryAllele"); allele.set("assembly", b.path("assembly")); allele.set("contig", b.path("contig"));
        return c;
    }

    private ObjectNode region(ObjectNode a, ObjectNode model, Sources sources, ObjectNode receipt) {
        require("pm4-region-assertions-v1".equals(a.path("schemaVersion").asText())
                && "ONE_BASED_CLOSED".equals(a.path("coordinateSystem").asText()), "REGION_COORDINATE_FORMAT_UNSUPPORTED");
        ObjectNode r = mapper.createObjectNode().put("schemaVersion", "pm4-region-material-v1")
                .put("materialId", text(a, "materialId")).put("materialVersion", text(a, "materialVersion"))
                .put("modelSha256", adapter.canonicalSha256(model));
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein")) {
            require(a.path(field).equals(model.path(field)), "REGION_MODEL_IDENTITY_MISMATCH"); r.set(field, model.path(field).deepCopy());
        }
        int start = position(a.path("coverage"), "start"), end = position(a.path("coverage"), "end");
        require(start <= end, "REGION_COVERAGE_INVALID");
        receipt.set("declaredCoverage", a.path("coverage").deepCopy());
        JsonNode records = a.path("assertions"); require(records.isArray() && records.size() <= 20_000, "REGION_ASSERTION_LIMIT_OR_FORMAT");
        List<JsonNode> sorted = new ArrayList<>(); records.forEach(sorted::add);
        for (JsonNode row : sorted) {
            require(position(row, "start") >= start && position(row, "end") <= end && position(row, "start") <= position(row, "end"), "REGION_INTERVAL_INVALID");
            require(only(row, Set.of("start", "end", "repeatStatus", "functionalContext", "sourceIds", "sourceLocator", "reviewId")), "REGION_ASSERTION_FIELDS_UNSUPPORTED");
            require(Set.of("NON_REPEAT", "REPEAT", "UNKNOWN").contains(row.path("repeatStatus").asText())
                    && Set.of("SUPPORTED", "NOT_SUPPORTED", "UNKNOWN").contains(row.path("functionalContext").asText()), "REGION_STATUS_MISSING_OR_INVALID");
            require(!text(row, "sourceLocator").isBlank() && !text(row, "reviewId").isBlank(), "REGION_SOURCE_OR_REVIEW_MISSING");
            require(row.path("sourceIds").isArray() && !row.path("sourceIds").isEmpty(), "REGION_SOURCE_MISSING");
            for (JsonNode id : row.path("sourceIds")) {
                require(hasSource(sources.records(), id.asText()), "REGION_SOURCE_MISSING");
                ObjectNode source = sources.json().get(id.asText());
                require(source != null, "REGION_JSON_SOURCE_REQUIRED");
                if (source == null) throw new IllegalArgumentException("REGION_JSON_SOURCE_REQUIRED");
                require("pm4-region-source-v1".equals(source.path("schemaVersion").asText())
                        && source.path("assembly").equals(a.path("assembly")) && source.path("contig").equals(a.path("contig")), "REGION_SOURCE_IDENTITY_CONFLICT");
                String locator = text(row, "sourceLocator"); require(locator.matches("/assertedIntervals/[0-9]+"), "REGION_SOURCE_LOCATOR_INVALID");
                JsonNode original = source.at(locator);
                for (String field : List.of("start", "end", "repeatStatus", "functionalContext"))
                    require(row.path(field).equals(original.path(field)), "REGION_SOURCE_CONTENT_CONFLICT");
            }
        }
        sorted.sort(Comparator.comparingInt(row -> row.path("start").asInt()));
        long next = start; boolean unknown = false; JsonNode previous = mapper.nullNode();
        ArrayNode intervals = r.putArray("intervals"), mapping = receipt.putArray("regionSourceMapping"), gaps = receipt.putArray("coverageGaps");
        Set<String> used = new TreeSet<>();
        for (JsonNode row : sorted) {
            int lo = row.path("start").asInt(), hi = row.path("end").asInt();
            require(lo >= next, previous.path("repeatStatus").equals(row.path("repeatStatus")) && previous.path("functionalContext").equals(row.path("functionalContext"))
                    ? "REGION_OVERLAP_AMBIGUOUS" : "REGION_OVERLAP_CONFLICT");
            if (lo > next) gaps.addObject().put("start", next).put("end", lo - 1);
            next = (long) hi + 1; previous = row;
            unknown |= "UNKNOWN".equals(row.path("repeatStatus").asText()) || "UNKNOWN".equals(row.path("functionalContext").asText());
            ObjectNode interval = intervals.addObject(); for (String f : List.of("start", "end", "repeatStatus", "functionalContext")) interval.set(f, row.path(f));
            mapping.addObject().put("intervalIndex", intervals.size() - 1).set("assertion", row.deepCopy());
            row.path("sourceIds").forEach(id -> used.add(id.asText()));
        }
        if (next <= end) gaps.addObject().put("start", next).put("end", end);
        receipt.put("hasUnknownOrGap", unknown || !gaps.isEmpty()).put("hasUnknown", unknown);
        ArrayNode sourceRecords = r.putArray("sourceRecords");
        // Empty coverage remains explicit; still retain declared source provenance, never infer NON_REPEAT.
        for (JsonNode source : sources.records()) if (used.contains(source.path("sourceId").asText()) || records.isEmpty()) sourceRecords.add(source.deepCopy());
        return r;
    }

    private Sources sources(Path root, JsonNode descriptors, String role) throws IOException {
        require(descriptors.isArray() && !descriptors.isEmpty() && descriptors.size() <= 64, "SOURCE_COUNT_INVALID");
        ArrayNode result = mapper.createArrayNode(); Set<String> ids = new HashSet<>(); Map<String, ObjectNode> documents = new HashMap<>();
        for (JsonNode d : descriptors) {
            ObjectNode source = object(d.path("sourceRecord"), "SOURCE_RECORD_MISSING");
            require(ids.add(text(source, "sourceId")), "SOURCE_IDENTITY_AMBIGUOUS");
            byte[] bytes = read(root.resolve(text(d, "path")), text(d, "rawSha256"), role + ":" + source.path("sourceId").asText());
            String digest; ObjectNode parsed = null;
            if ("JSON".equals(d.path("format").asText())) {
                parsed = json(bytes); String canonical = adapter.canonicalSha256(parsed);
                require(canonical.equals(d.path("canonicalSha256").asText()), "SOURCE_CANONICAL_SHA_MISMATCH");
                ((ObjectNode) inputs.get(inputs.size() - 1)).put("canonicalSha256", canonical);
                documents.put(source.path("sourceId").asText(), parsed);
                if ("pm4-region-source-v1".equals(parsed.path("schemaVersion").asText()))
                    require(source.path("sourceId").equals(parsed.path("sourceId")) && source.path("version").equals(parsed.path("version")), "REGION_SOURCE_IDENTITY_CONFLICT");
            } else require("BYTES".equals(d.path("format").asText()), "SOURCE_FORMAT_REQUIRED");
            if ("CANONICAL_JSON".equals(d.path("sourceDigestKind").asText())) {
                if (parsed == null) throw new IllegalArgumentException("SOURCE_JSON_REQUIRED");
                digest = adapter.canonicalSha256(parsed);
            } else { require("RAW_BYTES".equals(d.path("sourceDigestKind").asText()), "SOURCE_DIGEST_KIND_REQUIRED"); digest = sha(bytes); }
            require(digest.equals(source.path("sha256").asText()), "SOURCE_RECORD_DIGEST_MISMATCH");
            noPrivatePaths(source); result.add(source.deepCopy());
        }
        return new Sources(result, documents);
    }

    private ObjectNode input(Path root, JsonNode descriptor, String role) throws IOException {
        require(descriptor.isObject(), role.toUpperCase(Locale.ROOT).replace('-', '_') + "_INPUT_MISSING");
        ObjectNode value = json(read(root.resolve(text(descriptor, "path")), text(descriptor, "rawSha256"), role));
        require(adapter.canonicalSha256(value).equals(descriptor.path("canonicalSha256").asText()), "INPUT_CANONICAL_SHA_MISMATCH");
        ((ObjectNode) inputs.get(inputs.size() - 1)).put("canonicalSha256", adapter.canonicalSha256(value));
        noPrivatePaths(value); return value;
    }
    private byte[] read(Path path, String expected, String role) throws IOException {
        require(expected.matches("[0-9a-f]{64}"), "INPUT_RAW_PIN_REQUIRED");
        require(Files.isRegularFile(path), "INPUT_FILE_MISSING"); require(Files.size(path) <= MAX_BYTES, "INPUT_SIZE_LIMIT");
        byte[] bytes;
        try (var stream = Files.newInputStream(path)) { bytes = stream.readNBytes(MAX_BYTES + 1); }
        bytesRead += bytes.length; require(bytes.length <= MAX_BYTES && bytesRead <= MAX_TOTAL, "INPUT_SIZE_LIMIT");
        inputs.addObject().put("role", role).put("path", path.toAbsolutePath().normalize().toString()).put("byteLength", bytes.length)
                .put("rawSha256", sha(bytes)).put("expectedRawSha256", expected);
        require(expected.equals(sha(bytes)), "INPUT_RAW_SHA_MISMATCH"); return bytes;
    }
    private ObjectNode json(byte[] bytes) throws IOException { return object(mapper.readTree(bytes), "INPUT_JSON_OBJECT_REQUIRED"); }
    private void write(Path output, String name, ObjectNode value) throws IOException {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.write(output.resolve(name), bytes, StandardOpenOption.CREATE_NEW);
        outputs.addObject().put("file", name).put("byteLength", bytes.length).put("rawSha256", sha(bytes)).put("canonicalSha256", adapter.canonicalSha256(value));
    }
    private static ObjectNode object(JsonNode node, String reason) { require(node instanceof ObjectNode, reason); return (ObjectNode) node; }
    private static String text(JsonNode node, String field) { require(node.path(field).isTextual() && !node.path(field).asText().isBlank(), "REQUIRED_FIELD_MISSING_" + field); return node.path(field).asText(); }
    private static int position(JsonNode node, String field) { require(node.path(field).isIntegralNumber() && node.path(field).canConvertToInt() && node.path(field).asInt() > 0, "REGION_COORDINATE_INVALID"); return node.path(field).asInt(); }
    private static boolean contains(JsonNode nodes, JsonNode expected) { for (JsonNode node : nodes) if (node.equals(expected)) return true; return false; }
    private static boolean hasSource(JsonNode sources, String id) { for (JsonNode source : sources) if (id.equals(source.path("sourceId").asText())) return true; return false; }
    private static boolean only(JsonNode node, Set<String> fields) { if (!node.isObject()) return false; var names = node.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) return false; return true; }
    private static void noPrivatePaths(JsonNode node) {
        if (node.isTextual()) {
            String s = node.asText(); require(!s.matches("^[A-Za-z]:[\\\\/].*") && !s.startsWith("\\\\") && !s.startsWith("file:")
                    && (!s.startsWith("/") || s.startsWith("/acmg/") || s.matches("/assertedIntervals/[0-9]+")), "PRIVATE_PATH_IN_SCIENTIFIC_MATERIAL");
        } else node.forEach(Pm4MaterialPreparation::noPrivatePaths);
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
    private static String code(Exception exception) {
        if (exception instanceof com.fasterxml.jackson.core.JsonProcessingException) return "INPUT_JSON_INVALID_OR_DUPLICATE_KEY";
        if (exception instanceof IOException) return "INPUT_OUTPUT_IO_FAILURE";
        String message = exception.getMessage(); return message != null && message.matches("[A-Za-z0-9_]+") ? message : "PREPARATION_INPUT_INVALID";
    }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** No Spring startup. Exit 0 = ready, 2 = blocked/error. Pins must come from the external handoff. */
    public static void main(String[] args) {
        try {
            if (args.length != 6 || !"--manifest".equals(args[0]) || !"--manifest-sha256".equals(args[2]) || !"--output".equals(args[4]))
                throw new IllegalArgumentException("USAGE_MANIFEST_MANIFEST_SHA256_OUTPUT");
            Result result = new Pm4MaterialPreparation().prepare(Path.of(args[1]), args[3], Path.of(args[5]));
            System.out.println(result.report().toPrettyString()); if (!result.ready()) System.exit(2);
        } catch (Exception invalid) { System.err.println(code(invalid)); System.exit(2); }
    }
}
