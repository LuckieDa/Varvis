package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Offline assembly with explicit external approval or source-verified research qualification. */
public final class MissenseMaterialPreparation {
    public static final String DERIVE = "$DERIVE";
    private static final int MAX_FILE = 4_000_000, MAX_TOTAL = 32_000_000;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final ArrayNode inputs = mapper.createArrayNode(), outputs = mapper.createArrayNode();
    private int bytesRead;
    public record Result(boolean ready, ObjectNode report) { }

    public Result prepare(Path manifestPath, String manifestRawSha256, Path output) throws IOException {
        require(bytesRead == 0, "PREPARER_ALREADY_USED");
        require(!Files.exists(output), "OUTPUT_EXISTS_REFUSE_OVERWRITE");
        Files.createDirectory(output);
        ObjectNode report = mapper.createObjectNode().put("schemaVersion", "missense-preparation-report-v1")
                .put("status", "BLOCKED").put("scientificAdoption", false).put("runtimeExecution", "NOT_RUN");
        report.putArray("runtimeGatesRemaining").add("BOUNDED_DISCOVERY_COMPLETENESS_AND_SOURCE_IDENTITY")
                .add("TARGET_AND_REFERENCE_EXACT_PROTEIN_AND_ALLELE_IDENTITY")
                .add("INDEPENDENCE_SELF_REFERENCE_CIRCULARITY_AND_CONFLICT")
                .add("TARGET_REFERENCE_RNA_PAIR_AND_BOTH_SPLICE_GATES");
        ArrayNode bindings = report.putArray("backgrounds");
        Path staging = output.resolve(".release-validation.tmp");
        try {
            ObjectNode manifest = object(mapper.readTree(read(manifestPath, manifestRawSha256, "manifest")), "MANIFEST_OBJECT_REQUIRED");
            require("missense-preparation-manifest-v1".equals(manifest.path("schemaVersion").asText()), "MANIFEST_VERSION_UNSUPPORTED");
            boolean automated = manifest.has("releaseMode");
            require(!automated || MissenseResearchQualification.MODE.equals(manifest.path("releaseMode").asText()), "RELEASE_MODE_UNSUPPORTED");
            require(!automated || !manifest.has("approval"), "MISSENSE_RELEASE_ROUTE_AMBIGUOUS");
            String criterion = text(manifest, "criterion");
            require(Set.of(MissenseComparisonRuntime.PS1, MissenseComparisonRuntime.PM5).contains(criterion), "CRITERION_UNSUPPORTED");
            String dataClass = text(manifest, "dataClass");
            require(Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(dataClass), "DATA_CLASS_UNSUPPORTED");
            require(!"SYNTHETIC".equals(dataClass) || "TEST_ONLY".equals(manifest.path("purpose").asText()), "SYNTHETIC_TEST_ONLY_REQUIRED");
            report.put("criterion", criterion).put("dataClass", dataClass);
            Path root = Objects.requireNonNull(manifestPath.toAbsolutePath().getParent());
            ArrayNode sources = sourceRecords(root, manifest.path("sources"));
            ObjectNode profile = input(root, manifest.path("comparatorProfile"), "comparatorProfile");
            require(profile.equals(ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper)), "COMPARATOR_PROFILE_MISMATCH");
            ObjectNode header = input(root, manifest.path("bundleHeader"), "bundleHeader");
            require(MissenseComparisonPolicy.only(header, Set.of("schemaVersion", "bundleId", "bundleVersion", "review", "activation")), "BUNDLE_HEADER_INVALID");
            require((MissenseComparisonRuntime.PS1.equals(criterion) ? "reviewed-rule-bundle-ps1-v3" : "reviewed-rule-bundle-pm5-v4")
                    .equals(header.path("schemaVersion").asText()), "BUNDLE_CRITERION_MISMATCH");
            ObjectNode catalog = input(root, manifest.path("catalog"), "catalog");
            checkSources(catalog.path("sources"), sources);
            JsonNode backgrounds = manifest.path("backgrounds");
            require(backgrounds.isArray() && !backgrounds.isEmpty() && backgrounds.size() <= 32, "BACKGROUND_COUNT_INVALID");
            ArrayNode policies = mapper.createArrayNode(); ObjectNode directory = mapper.createObjectNode();
            Set<String> seen = new HashSet<>(), materialIds = new HashSet<>(), policyIds = new HashSet<>();
            for (int i = 0; i < backgrounds.size(); i++) {
                JsonNode item = backgrounds.get(i);
                ObjectNode background = object(item.path("background"), "BACKGROUND_REQUIRED");
                require(seen.add(adapter.canonicalSha256(background)), "BACKGROUND_DUPLICATE");
                ObjectNode material = input(root, item.path("material"), "material-" + i);
                require(MissenseComparisonPolicy.only(material, Set.of("schemaVersion", "materialId", "version", "review", "scope")), "MATERIAL_METADATA_ONLY_REQUIRED");
                require(materialIds.add(text(material, "materialId") + "@" + text(material, "version")), "MATERIAL_ID_DUPLICATE");
                for (String component : List.of("nativeModel", "annotationResource", "referenceAudit", "rnaReview", "spliceAnnotationProof"))
                    material.set(component, input(root, item.path(component), component + "-" + i));
                ObjectNode policy = input(root, item.path("policy"), "policy-" + i);
                require(policyIds.add(text(policy, "policyId") + "@" + text(policy, "policyVersion")), "POLICY_ID_DUPLICATE");
                require(policy.path("criterionIds").equals(mapper.createArrayNode().add(criterion)), "POLICY_CRITERION_MISMATCH");
                require(material.path("scope").equals(policy.path("scope")), "MISSENSE_MATERIAL_SCOPE_MISMATCH");
                checkSources(policy.path("sources"), sources);
                checkSources(material.at("/nativeModel/sourceRecords"), sources);
                checkBackground(background, material.path("nativeModel"), policy.path("scope"));
                issue(CodingTranscriptMapping.modelIssue(material.path("nativeModel"), context(background)));
                require(material.at("/referenceAudit/references").isArray()
                        && material.at("/referenceAudit/references").size() <= 256, "REFERENCE_COUNT_INVALID");
                require(material.at("/rnaReview/comparisons").isArray()
                        && material.at("/rnaReview/comparisons").size() <= 1024, "RNA_PAIR_COUNT_INVALID");
                String code = criterion.substring("ACMG_AMP_2015:".length());
                ObjectNode rule = object(policy.path("rules").path(code), "RULE_REQUIRED");
                if ("SUBSTITUTION_MATRIX_SINGLE_REFERENCE".equals(rule.path("mode").asText())) {
                    material.set("substitutionMatrix", input(root, item.path("substitutionMatrix"), "matrix-" + i));
                    bind(rule, "matrixSha256", material.path("substitutionMatrix"));
                } else if ("AUDITED_AMINO_ACID_TRIPLES_SINGLE_REFERENCE".equals(rule.path("mode").asText())) {
                    ObjectNode triples = input(root, item.path("aminoAcidRelations"), "triples-" + i);
                    require(triples.path("relations").isArray() && triples.path("relations").size() <= 8000, "TRIPLES_INVALID");
                    for (JsonNode triple : triples.path("relations")) {
                        require(MissenseComparisonPolicy.text(triple.path("reviewId"))
                                && MissenseComparisonPolicy.boundSources(policy, triple.path("sourceIds")), "TRIPLES_REVIEW_MISSING");
                    }
                    material.set("aminoAcidRelations", triples.path("relations"));
                }
                bind(object(material.path("spliceAnnotationProof"), "SPLICE_PROOF_REQUIRED"), "modelSha256", material.path("nativeModel"));
                for (String field : List.of("nativeModel", "annotationResource", "spliceAnnotationProof")) bind(policy, field + "Sha256", material.path(field));
                bind(policy, "comparatorProfileSha256", profile); bind(policy, "materialsSha256", material);
                issue(MissenseComparisonPolicy.issue(policy, criterion));
                issue(MissenseComparisonPolicy.materialsIssue(policy, material, criterion, adapter));
                checkResourceBindings(policy, material, profile, criterion);
                directory.set(adapter.canonicalSha256(material), material); policies.add(policy);
                ObjectNode row = bindings.addObject(); row.set("background", background.deepCopy());
                row.put("backgroundSha256", adapter.canonicalSha256(background)).put("materialSha256", adapter.canonicalSha256(material))
                        .put("policySha256", adapter.canonicalSha256(policy));
                write(output, "material-candidate-" + i + ".json", material);
                write(output, "policy-candidate-" + i + ".json", policy);
            }
            bindCatalog(catalog, policies, criterion);
            issue(new AcmgSpecificationSelector(mapper, catalog, adapter.canonicalSha256(catalog), policies).configurationIssue());
            ObjectNode candidate = mapper.createObjectNode().put("schemaVersion", "missense-preparation-candidate-v1")
                    .put("draft", true).put("dataClass", dataClass).put("purpose", manifest.path("purpose").asText()).put("criterion", criterion);
            candidate.set("bundleHeader", header); candidate.set("catalog", catalog); candidate.set("policies", policies);
            candidate.set("missenseMaterials", directory); candidate.set("backgrounds", bindings.deepCopy());
            candidate.set("sources", sources); candidate.set("comparatorProfile", profile);
            if (automated) candidate.put("releaseMode", MissenseResearchQualification.MODE);
            if (manifest.has("contextUse")) {
                MissenseMaterialQualification.validateUse(manifest.path("contextUse"), dataClass, manifest.path("purpose").asText());
                candidate.set("contextUse", manifest.path("contextUse").deepCopy());
            }
            noPrivatePaths(candidate);
            String candidateSha = adapter.canonicalSha256(candidate);
            write(output, "candidate.json", candidate); report.put("candidateSha256", candidateSha).put("staticEngineeringValidation", "PASS");
            ObjectNode qualification;
            if (automated) {
                qualification = MissenseResearchQualification.preserve(mapper, candidate);
                report.put("qualificationMode", MissenseResearchQualification.MODE).put("scientificApprovalGranted", false);
            } else {
                report.putArray("pendingReview").add("EXTERNAL_APPROVAL_OF_CANDIDATE_AND_EXACT_BACKGROUNDS");
                require(manifest.has("approval"), "EXTERNAL_APPROVAL_MISSING");
                ObjectNode approval = input(root, manifest.path("approval"), "approval");
                require("missense-preparation-approval-v1".equals(approval.path("schemaVersion").asText())
                        && "REVIEWED_IMPLEMENTED".equals(approval.path("status").asText())
                        && MissenseComparisonPolicy.text(approval.path("reviewId")), "EXTERNAL_APPROVAL_REQUIRED");
                require(candidateSha.equals(approval.path("candidateSha256").asText()) && dataClass.equals(approval.path("dataClass").asText())
                        && criterion.equals(approval.path("criterion").asText()) && bindings.equals(approval.path("backgrounds")), "EXTERNAL_APPROVAL_BINDING_MISMATCH");
                qualification = MissenseMaterialQualification.preserve(mapper, candidate, approval);
                report.put("externalReviewBinding", "MATCHED_EXTERNAL_DECLARATION").put("reviewId", approval.path("reviewId").asText());
            }
            ObjectNode bundle = header.deepCopy(); bundle.set("catalog", catalog); bundle.put("catalogSha256", adapter.canonicalSha256(catalog));
            bundle.set("policies", policies); bundle.putObject("materials").set("missenseMaterials", directory);
            bundle.set("materialQualification", qualification);
            String bundleSha = adapter.canonicalSha256(bundle);
            new ReviewedRuleBundle(mapper, bundle, bundleSha); // No runtime factory is constructed or called.
            byte[] encoded = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle);
            require(encoded.length <= 8_000_000, "BUNDLE_SIZE_LIMIT");
            Files.write(staging, encoded, StandardOpenOption.CREATE_NEW);
            ReviewedRuleBundle.load(mapper, staging, bundleSha);
            outputRecord("bundle.json", encoded, bundle);
            report.put("status", "PREPARED").put("bundleSha256", bundleSha);
            report.putArray("pendingReview");
        } catch (IllegalArgumentException | IOException invalid) {
            report.put("reasonCode", reason(invalid)); Files.deleteIfExists(staging);
        }
        try {
            write(output, "preparation-report.json", report);
            ObjectNode artifacts = mapper.createObjectNode().put("schemaVersion", "missense-preparation-artifacts-v1")
                    .put("completedAt", Instant.now().toString()).put("status", report.path("status").asText());
            artifacts.set("inputs", inputs); artifacts.set("outputs", outputs.deepCopy());
            write(output, "artifact-manifest.json", artifacts);
            // Publish last: failed report/manifest writes cannot leave a successful release file.
            if ("PREPARED".equals(report.path("status").asText())) Files.move(staging, output.resolve("bundle.json"));
        } finally { Files.deleteIfExists(staging); }
        return new Result("PREPARED".equals(report.path("status").asText()), report);
    }

    // Pure checks shared by the offline review bridge; callers pass copies, never release a candidate here.
    void bindCatalog(ObjectNode catalog, ArrayNode policies, String criterion) {
        require(catalog.path("entries").isArray() && catalog.path("entries").size() == policies.size(), "CATALOG_BACKGROUND_COUNT_MISMATCH");
        Set<String> used = new HashSet<>(), usedPolicies = new HashSet<>();
        for (JsonNode node : catalog.path("entries")) {
            ObjectNode entry = object(node, "CATALOG_ENTRY_INVALID"), binding = object(entry.path("policyBinding"), "CATALOG_BINDING_MISSING");
            require(used.add(text(entry, "entryId")), "CATALOG_ENTRY_DUPLICATE");
            require(MissenseComparisonPolicy.boundSources(catalog, entry.path("sourceIds")), "CATALOG_ENTRY_SOURCE_INVALID");
            require(MissenseComparisonPolicy.text(entry.path("baseRuleRelationship")), "CATALOG_BASE_RULE_RELATIONSHIP_MISSING");
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode policy : policies) if (policy.path("policyId").equals(binding.path("policyId")) && policy.path("policyVersion").equals(binding.path("policyVersion"))) matches.add(policy);
            require(matches.size() == 1, "CATALOG_POLICY_MISMATCH"); JsonNode policy = matches.get(0);
            require(usedPolicies.add(adapter.canonicalSha256(policy)), "CATALOG_POLICY_DUPLICATE");
            ObjectNode scope = object(entry.path("scope"), "CATALOG_SCOPE_INVALID").deepCopy();
            require("EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText()), "CATALOG_TRANSCRIPT_MODE_INVALID"); scope.remove("transcriptMode");
            require(scope.equals(policy.path("scope")) && criterion.equals(entry.path("criterionId").asText()), "CATALOG_SCOPE_MISMATCH");
            require("COMPLETE".equals(entry.path("searchStatus").asText()) && "SPECIALIZED_IMPLEMENTED".equals(entry.path("disposition").asText()), "CATALOG_CLAUSE_NOT_IMPLEMENTED_OR_INCOMPLETE");
            require("REVIEWED".equals(entry.at("/review/status").asText()) && MissenseComparisonPolicy.text(entry.at("/review/reviewId")), "CATALOG_REVIEW_REQUIRED");
            bind(binding, "policySha256", policy);
        }
    }

    private void checkResourceBindings(JsonNode policy, JsonNode material, JsonNode profile, String criterion) {
        String resource = policy.at("/registryBinding/registryClasspathResource").asText();
        ObjectNode registry;
        try {
            require(policy.at("/registryBinding/registrySha256").asText().equals(ClinVarMissenseBusinessArtifacts.resourceSha(resource)), "REGISTRY_PIN_MISMATCH");
            registry = ClinVarMissenseBusinessRuntime.read(mapper, resource);
        } catch (IllegalStateException unavailable) {
            // These resource helpers wrap missing, unreadable or invalid classpath content.
            throw new IllegalArgumentException("REGISTRY_RESOURCE_UNAVAILABLE_OR_INVALID", unavailable);
        }
        require(policy.at("/registryBinding/registryId").equals(registry.path("registryId")), "REGISTRY_ID_MISMATCH");
        boolean found = false;
        for (JsonNode rule : registry.path("criteria")) if (criterion.equals(rule.path("criterionId").asText())) found = true;
        require(found, "REGISTRY_CRITERION_MISSING"); found = false;
        JsonNode annotation = material.path("annotationResource");
        require("VEP".equals(annotation.path("provider").asText()), "ANNOTATION_PROVIDER_INVALID");
        for (JsonNode entry : profile.path("resources")) if (entry.path("resourceId").equals(annotation.path("resourceId"))
                && entry.path("identityFingerprintSha256").equals(annotation.path("identityFingerprintSha256"))) found = true;
        require(found, "ANNOTATION_PROFILE_MISMATCH");
    }

    private ObjectNode context(JsonNode b) {
        ObjectNode c = mapper.createObjectNode(); c.putObject("gene").put("hgncId", b.path("hgncId").asText()).put("symbol", b.path("geneSymbol").asText());
        String tx = text(b, "transcript"); int dot = tx.lastIndexOf('.');
        c.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)));
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", b.path("assembly").asText()).put("contig", b.path("contig").asText());
        return c;
    }
    private void checkBackground(JsonNode b, JsonNode model, JsonNode scope) {
        require("GRCh38".equals(b.path("assembly").asText()), "ASSEMBLY_UNSUPPORTED");
        require(text(b, "transcript").matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*"), "TRANSCRIPT_VERSION_REQUIRED");
        for (String field : List.of("assembly", "contig", "hgncId", "geneSymbol", "transcript", "protein"))
            require(!text(b, field).isBlank() && b.path(field).equals(model.path(field)), "MODEL_BACKGROUND_MISMATCH");
        for (String field : List.of("assembly", "hgncId", "geneSymbol", "protein", "mondoId", "moi"))
            require(!text(b, field).isBlank() && b.path(field).equals(scope.path(field)), "POLICY_BACKGROUND_MISMATCH");
        require(b.path("transcript").asText().equals(scope.at("/transcripts/0/accession").asText() + "." + scope.at("/transcripts/0/version").asInt()), "POLICY_TRANSCRIPT_MISMATCH");
    }
    void bind(ObjectNode node, String field, JsonNode value) {
        String actual = adapter.canonicalSha256(value), declared = node.path(field).asText();
        require(DERIVE.equals(declared) || actual.equals(declared), "DECLARED_PIN_MISMATCH_" + field);
        node.put(field, actual);
    }
    private ArrayNode sourceRecords(Path root, JsonNode descriptions) throws IOException {
        require(descriptions.isArray() && !descriptions.isEmpty() && descriptions.size() <= 64, "SOURCE_COUNT_INVALID");
        ArrayNode records = mapper.createArrayNode(); Set<String> ids = new HashSet<>();
        for (JsonNode d : descriptions) {
            ObjectNode record = object(d.path("sourceRecord"), "SOURCE_RECORD_REQUIRED");
            require(ids.add(text(record, "sourceId")), "SOURCE_ID_DUPLICATE");
            byte[] bytes = read(root.resolve(text(d, "path")), text(d, "rawSha256"), "source:" + record.path("sourceId").asText());
            String digest = Pm4MaterialPreparation.sha(bytes), kind = text(d, "sourceDigestKind");
            if ("JSON".equals(d.path("format").asText())) {
                JsonNode value = mapper.readTree(bytes); String canonical = adapter.canonicalSha256(value);
                require(canonical.equals(d.path("canonicalSha256").asText()), "INPUT_CANONICAL_SHA_MISMATCH");
                ((ObjectNode) inputs.get(inputs.size() - 1)).put("canonicalSha256", canonical);
                if ("CANONICAL_JSON".equals(kind)) digest = canonical;
            } else require("BYTES".equals(d.path("format").asText()) && "RAW_BYTES".equals(kind), "SOURCE_FORMAT_INVALID");
            require(Set.of("RAW_BYTES", "CANONICAL_JSON").contains(kind) && digest.equals(record.path("sha256").asText()), "SOURCE_DIGEST_MISMATCH");
            ((ObjectNode) inputs.get(inputs.size() - 1)).put("sourceId", record.path("sourceId").asText()).put("sourceDigestKind", kind);
            records.add(record.deepCopy());
        }
        require(MissenseComparisonPolicy.sourcesValid(records), "SOURCE_RECORD_INVALID"); return records;
    }
    private void checkSources(JsonNode records, JsonNode sources) {
        require(records.isArray() && !records.isEmpty(), "SOURCE_RECORDS_REQUIRED");
        for (JsonNode record : records) { boolean found = false; for (JsonNode source : sources) if (source.equals(record)) found = true;
            require(found, "SOURCE_NOT_EXTERNALLY_PINNED"); }
    }
    private ObjectNode input(Path root, JsonNode descriptor, String role) throws IOException {
        ObjectNode value = object(mapper.readTree(read(root.resolve(text(descriptor, "path")), text(descriptor, "rawSha256"), role)), "INPUT_OBJECT_REQUIRED");
        String canonical = adapter.canonicalSha256(value);
        require(canonical.equals(descriptor.path("canonicalSha256").asText()), "INPUT_CANONICAL_SHA_MISMATCH");
        ((ObjectNode) inputs.get(inputs.size() - 1)).put("canonicalSha256", canonical); noPrivatePaths(value); return value;
    }
    private byte[] read(Path path, String expected, String role) throws IOException {
        require(expected.matches("[0-9a-f]{64}"), "INPUT_RAW_PIN_REQUIRED");
        require(Files.isRegularFile(path), "INPUT_FILE_MISSING"); require(Files.size(path) <= MAX_FILE, "INPUT_SIZE_LIMIT");
        byte[] bytes; try (var stream = Files.newInputStream(path)) { bytes = stream.readNBytes(MAX_FILE + 1); }
        bytesRead += bytes.length; require(bytes.length <= MAX_FILE && bytesRead <= MAX_TOTAL, "INPUT_SIZE_LIMIT");
        String actual = Pm4MaterialPreparation.sha(bytes);
        inputs.addObject().put("role", role).put("path", path.toAbsolutePath().normalize().toString()).put("bytes", bytes.length).put("rawSha256", actual);
        require(actual.equals(expected), "INPUT_RAW_SHA_MISMATCH"); return bytes;
    }
    private void write(Path output, String name, ObjectNode value) throws IOException {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.write(output.resolve(name), bytes, StandardOpenOption.CREATE_NEW); outputRecord(name, bytes, value);
    }
    private void outputRecord(String name, byte[] bytes, JsonNode value) {
        outputs.addObject().put("file", name).put("rawSha256", Pm4MaterialPreparation.sha(bytes)).put("canonicalSha256", adapter.canonicalSha256(value));
    }
    static void noPrivatePaths(JsonNode node) {
        noPrivatePaths(node, "");
    }
    private static void noPrivatePaths(JsonNode node, String field) {
        if (node.isTextual() && sourceLocator(field, node.asText())) return;
        if (node.isTextual()) { String s = node.asText(); require(!s.matches("^[A-Za-z]:[\\\\/].*") && !s.startsWith("\\\\") && !s.startsWith("file:")
                && (!s.startsWith("/") || s.startsWith("/acmg/")), "PRIVATE_PATH_IN_SCIENTIFIC_MATERIAL"); }
        else if (node.isObject()) node.properties().forEach(entry -> noPrivatePaths(entry.getValue(), entry.getKey()));
        else node.forEach(value -> noPrivatePaths(value, field));
    }
    private static boolean sourceLocator(String field, String value) {
        if (Set.of("xpath", "narrativeMatchLocators").contains(field))
            return value.matches("/ClinVarResult-Set\\[1\\](?:/[A-Za-z][A-Za-z0-9_-]*\\[[1-9][0-9]*\\])*");
        return "pointer".equals(field) && value.matches("/data(?:/(?:[A-Za-z][A-Za-z0-9_-]*|[0-9]+))+");
    }
    private static void issue(@org.springframework.lang.Nullable String issue) { if (issue != null) throw new IllegalArgumentException(issue); }
    private static ObjectNode object(JsonNode value, String reason) { require(value instanceof ObjectNode, reason); return (ObjectNode) value; }
    private static String text(JsonNode value, String field) { require(MissenseComparisonPolicy.text(value.path(field)), "REQUIRED_FIELD_" + field); return value.path(field).asText(); }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
    private static String reason(Exception error) {
        if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) return "INPUT_JSON_INVALID_OR_DUPLICATE_KEY";
        if (error instanceof IOException) return "INPUT_OUTPUT_IO_FAILURE";
        String message = error.getMessage(); return message != null && message.matches("[A-Za-z0-9_]+") ? message : "PREPARATION_INVALID";
    }
    public static void main(String[] args) {
        try {
            require(args.length == 6 && "--manifest".equals(args[0]) && "--manifest-sha256".equals(args[2]) && "--output".equals(args[4]), "USAGE_MANIFEST_MANIFEST_SHA256_OUTPUT");
            Result result = new MissenseMaterialPreparation().prepare(Path.of(args[1]), args[3], Path.of(args[5]));
            System.out.println(result.report().toPrettyString()); if (!result.ready()) System.exit(2);
        } catch (Exception invalid) { System.err.println(reason(invalid)); System.exit(2); }
    }
}
