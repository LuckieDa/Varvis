package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Request-owned automatic acquisition configuration. No source/model is opened by static identity. */
final class Pp2OtherPhenotypeWorkflow {
    static final String VERSION = "pp2-other-phenotype-workflow-v2";
    private static final int MAX_FILE = 4 * 1024 * 1024, MAX_TOTAL = 32 * 1024 * 1024;
    private static final List<String> FILES = List.of("scripts/ps1_pm5/pp2_other_phenotype_workflow.py",
            "scripts/ps1_pm5/pp2_discovery_scope.py", "scripts/ps1_pm5/read_pp2_other_phenotype_sources.py",
            "scripts/ps1_pm5/prepare_expert_reference_evidence.py", "scripts/ps1_pm5/prepare_reference_evidence.py",
            "scripts/denovo/discover_public_literature.py", "scripts/denovo/literature_search_transport.py",
            "scripts/denovo/literature_allele_identity.py", "scripts/denovo/litvar_literature.py",
            "scripts/denovo/prepare_literature.py", "scripts/denovo/select_public_papers.py", "scripts/denovo/fetch_public_literature.py",
            "scripts/ps1_pm5/assess_pp2_other_phenotype_literature.py", "scripts/ps1_pm5/pp2_relation_reading.py",
            "scripts/ps1_pm5/pp2_bounded_literature_qualification.py",
            "scripts/denovo/qualify_literature_cases.py", "scripts/denovo/core_local_identity.py",
            "scripts/denovo/extract_literature_cases.py", "scripts/denovo/single_case_scope.py",
            "scripts/acmg-generalization/normalize_public_panel.py", "scripts/acmg-generalization/build_transcript_model.py",
            "scripts/denovo/run_bundle_model.py", "scripts/denovo/bundle_model_contract.py", "scripts/denovo/core_material_contract.py",
            "scripts/denovo/acquire_public_literature.py", "scripts/denovo/paper_library_registration.py",
            "scripts/denovo/bundle_replay_profiles.json");
    private final Path configuration;
    private final String expectedSha256;
    private final Path work;
    private final Supplier<Pvs1StartLossPublicSources> nativeFactory;
    Pp2OtherPhenotypeWorkflow(Path configuration, String expectedSha256, Path work, Supplier<Pvs1StartLossPublicSources> nativeFactory) {
        this.configuration = configuration.toAbsolutePath().normalize(); this.expectedSha256 = expectedSha256;
        this.work = work.toAbsolutePath().normalize(); this.nativeFactory = Objects.requireNonNull(nativeFactory);
    }
    ObjectNode identity(ObjectMapper mapper) {
        return mapper.createObjectNode().put("methodVersion", VERSION).put("workflowConfigurationRawSha256", expectedSha256)
                .put("targetRole", "DISCOVERY_ONLY_NOT_SCIENTIFIC_CONTEXT").put("paidModelBeforeClaim", false)
                .put("nativeSourceVersion", Pvs1StartLossPublicSources.VERSION)
                .put("scopePreparation", "CURRENT_NATIVE_EXPERT_AND_DECLARED_TARGET_LITVAR_SCOPE")
                .put("scopeMeaning", "CURRENT_DECLARED_SOURCE_SCOPE_NOT_GLOBAL_ABSENCE");
    }
    record Prepared(ObjectNode originals, ObjectNode methodSources, ObjectNode reader, ArrayNode readDispositions,
            ObjectNode configurationObservation) { }
    Prepared prepare(ObjectMapper mapper, List<Pp2OtherPhenotypeSource.DiscoveryTarget> targets) {
        if (targets.isEmpty()) return new Prepared(mapper.createObjectNode(), mapper.createObjectNode().put("workflowVersion", VERSION),
                mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-native-reader-v1")
                        .put("status", "NOT_SELECTED").put("reasonCode", "PP2_SOURCE_DISCOVERY_TARGETS_REQUIRED"),
                mapper.createArrayNode(), mapper.createObjectNode().put("status", "NOT_OBSERVED"));
        var settings = mapper.createObjectNode().put("status", "UNAVAILABLE").put("expectedRawSha256", expectedSha256);
        String reason = "PP2_AUTOMATIC_SOURCE_SCOPE_UNAVAILABLE";
        var originals = mapper.createObjectNode(); var methods = mapper.createObjectNode().put("workflowVersion", VERSION);
        var reads = mapper.createArrayNode();
        try {
            byte[] bytes;
            try (var stream = Files.newInputStream(configuration)) { bytes = stream.readNBytes(65537); }
            if (bytes.length > 65536) throw new IllegalArgumentException("PP2_WORKFLOW_CONFIGURATION_TECHNICAL_LIMIT");
            String actual = CodingTranscriptMaterialSupply.sha(bytes); settings.put("observedRawSha256", actual);
            if (!actual.equals(expectedSha256)) throw new IllegalArgumentException("PP2_WORKFLOW_CONFIGURATION_PIN_MISMATCH");
            var config = mapper.readTree(bytes);
            if (config == null || !"denovo-public-literature-workflow-v1".equals(config.path("schemaVersion").asText()))
                throw new IllegalArgumentException("PP2_WORKFLOW_CONFIGURATION_INVALID");
            settings.put("status", "READ").put("allowPublicNetwork", config.path("allowPublicNetwork").asBoolean())
                    .put("allowModel", config.path("allowModel").asBoolean());
            if (!config.path("enabled").asBoolean()) throw new IllegalArgumentException("PP2_WORKFLOW_NOT_ENABLED");
            Path project = Path.of(config.path("projectRoot").asText()).toRealPath();
            Path python = configuredPath(project, config.path("python").asText()).toRealPath();
            Path cache = configuredPath(project, config.path("searchCache").asText());
            for (String file : FILES) methods.put(file, CodingTranscriptMaterialSupply.sha(Files.readAllBytes(project.resolve(file))));
            var packet = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-auto-input-v1");
            var declared = packet.putArray("targets");
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            for (var target : targets) {
                var row = declared.addObject().put("targetId", "target-" + CodingTranscriptMaterialSupply.sha(
                        (target.candidateId() + "\u0000" + target.hypothesisId()).getBytes(StandardCharsets.UTF_8)).substring(0, 40))
                        .put("candidateId", target.candidateId()).put("hypothesisId", target.hypothesisId())
                        .put("hgncId", target.hgncId()).put("geneSymbol", target.geneSymbol()).put("mondoId", target.mondoId()).put("moi", target.moi());
                row.set("genomicAllele", target.genomicAllele()); row.set("formalCandidate", target.formalCandidate());
            }
            var nativeSources = Objects.requireNonNull(nativeFactory.get());
            var genes = packet.putArray("nativeGenes"); var snapshots = new LinkedHashMap<String, Pvs1StartLossPublicSources.Snapshot>();
            var wanted = new java.util.TreeSet<String>();
            for (var target : targets) {
                var gene = snapshots.computeIfAbsent(target.geneSymbol(), nativeSources::genePages);
                for (var value : target.formalCandidate().path("clinvarVariationIds"))
                    if (value.isTextual() && value.asText().matches("[1-9][0-9]{0,8}")) wanted.add(value.asText());
                for (var row : gene.document().path("data")) if (mayMatch(row, target.genomicAllele())) {
                    String vid = row.path("cvId").asText(); if (vid.matches("[1-9][0-9]{0,8}")) wanted.add(vid);
                }
            }
            for (var entry : snapshots.entrySet()) {
                var snapshot = entry.getValue();
                var row = genes.addObject().put("geneSymbol", entry.getKey())
                        .put("sourceEnumerationComplete", snapshot.document().path("sourceEnumerationComplete").asBoolean())
                        .put("coverageReason", snapshot.document().path("coverageReason").asText(snapshot.receipt().path("reasonCode").asText()));
                var pages = row.putArray("pages");
                for (var page : snapshot.document().path("pages")) {
                    var pin = pages.addObject();
                    for (String key : List.of("sourceUrl", "status", "sha256", "pass", "page", "reasonCode", "semanticEmptyGeneQuery"))
                        if (page.has(key)) pin.set(key, page.path(key).deepCopy());
                }
            }
            var records = packet.putArray("nativeRecords");
            for (String vid : wanted) {
                var snapshot = nativeSources.clinvarRecord(vid); var receipt = snapshot.receipt();
                records.addObject().put("variationId", vid).put("status", receipt.path("status").asText())
                        .put("rawSha256", receipt.path("sha256").asText()).put("sourceUrl", receipt.path("sourceUrl").asText())
                        .put("reasonCode", receipt.path("reasonCode").asText());
            }
            for (var entry : nativeSources.originals().properties()) {
                var value = entry.getValue();
                if ("DERIVED_PAGINATED_EREPO_GENE_SNAPSHOT".equals(value.at("/receipt/kind").asText())) continue;
                String raw = value.has("rawXml") ? value.path("rawXml").asText() : value.path("rawJson").asText();
                retain(originals, reads, entry.getKey(), raw.getBytes(StandardCharsets.UTF_8), value.at("/receipt/sourceUrl").asText());
                var retained = (ObjectNode) originals.path(entry.getKey());
                var provenance = retained.putArray("provenance");
                for (var capture : value.path("captures")) {
                    var receipt = provenance.addObject();
                    for (String key : List.of("sourceUrl", "status", "sha256", "capturedAt", "bytes", "format", "httpStatus", "semanticEmptyGeneQuery"))
                        if (capture.has(key)) receipt.set(key, capture.path(key).deepCopy());
                }
            }
            var readerOriginals = originals.deepCopy();
            for (var value : readerOriginals) if (value instanceof ObjectNode original) original.remove("provenance");
            packet.set("originals", readerOriginals);
            byte[] inputBytes = mapper.writeValueAsBytes(packet); String inputSha = CodingTranscriptMaterialSupply.sha(inputBytes);
            Files.createDirectories(work); Path directory = Files.createTempDirectory(work, "observe-");
            Path input = directory.resolve("input.json"), output = directory.resolve("facts.json");
            Files.write(input, inputBytes, StandardOpenOption.CREATE_NEW);
            var command = new java.util.ArrayList<>(List.of(python.toString(), project.resolve(FILES.get(0)).toString(),
                    "--input", input.toString(), "--output", output.toString(), "--work", directory.toString(), "--cache", cache.toString()));
            if (config.path("allowPublicNetwork").asBoolean()) command.add("--allow-public-network");
            var execution = new SystemExternalProcessRunner().run(command, project, Duration.ofSeconds(240));
            if (!execution.isSuccess()) throw new IllegalArgumentException(execution.isTimedOut()
                    ? "PP2_AUTOMATIC_SOURCE_OBSERVATION_TIMEOUT" : "PP2_AUTOMATIC_SOURCE_OBSERVATION_FAILED");
            byte[] resultBytes;
            try (var stream = Files.newInputStream(output)) { resultBytes = stream.readNBytes(2 * MAX_TOTAL + 1); }
            if (resultBytes.length > 2 * MAX_TOTAL) throw new IllegalArgumentException("PP2_AUTOMATIC_SOURCE_OUTPUT_TECHNICAL_LIMIT");
            var reader = mapper.readTree(resultBytes);
            if (!(reader instanceof ObjectNode facts) || !inputSha.equals(facts.path("inputRawSha256").asText())
                    || !"pp2-other-phenotype-native-reader-v1".equals(facts.path("schemaVersion").asText())
                    || !facts.path("scientificAdoption").isBoolean() || facts.path("scientificAdoption").asBoolean())
                throw new IllegalArgumentException("PP2_AUTOMATIC_SOURCE_RESULT_INVALID");
            for (var entry : facts.path("observedOriginals").properties()) {
                var value = entry.getValue(); byte[] raw = Base64.getDecoder().decode(value.path("base64").asText());
                if (!entry.getKey().equals(value.path("rawSha256").asText()) || raw.length != value.path("bytes").asInt())
                    throw new IllegalArgumentException("PP2_AUTOMATIC_SOURCE_ORIGINAL_METADATA_INVALID");
                retain(originals, reads, entry.getKey(), raw, "PUBLIC_QUERY_OR_TECHNICAL_DECLARATION");
                if (value.path("provenance").isArray()) ((ObjectNode) originals.path(entry.getKey()))
                        .set("provenance", value.path("provenance").deepCopy());
            }
            facts.remove("observedOriginals");
            var implementation = facts.path("readingMethodSources");
            if (!implementation.isObject() || implementation.isEmpty()) throw new IllegalArgumentException("PP2_READING_METHOD_IDENTITY_UNAVAILABLE");
            verifyReadingImplementation(project, implementation);
            methods.set("derivedReaderImplementation", implementation.deepCopy());
            for (String file : FILES) if (!methods.path(file).asText().equals(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(project.resolve(file)))))
                throw new IllegalArgumentException("PP2_AUTOMATIC_SOURCE_METHOD_CHANGED_DURING_OBSERVATION");
            settings.set("derivedCacheObservation", observeDerivedCache(mapper, project, config));
            settings.put("readerInputRawSha256", inputSha).put("readerFactsSha256", adapter.canonicalSha256(facts));
            return new Prepared(originals, methods, facts, reads, settings);
        } catch (Exception unavailable) {
            String message = unavailable.getMessage();
            reason = message != null && message.matches("[A-Z][A-Z0-9_]{1,120}") ? message : "PP2_WORKFLOW_CONFIGURATION_UNAVAILABLE";
            settings.put("reasonCode", reason);
        }
        var reader = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-native-reader-v1")
                .put("status", "UNAVAILABLE").put("reasonCode", reason);
        // This is configuration, not public evidence. Do not copy credential-bearing config bytes into evidence originals.
        return new Prepared(originals, methods, reader, reads, settings);
    }
    private static void verifyReadingImplementation(Path project, com.fasterxml.jackson.databind.JsonNode implementation) throws java.io.IOException {
        if (!implementation.isObject() || implementation.isEmpty()) throw new IllegalArgumentException("PP2_READING_METHOD_IDENTITY_UNAVAILABLE");
        for (var entry : implementation.properties()) {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]*\\.(?:py|json)") || !entry.getValue().asText().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("PP2_READING_METHOD_IDENTITY_INVALID");
            Path code = project.resolve("scripts").resolve("pp2_relationship_material.py".equals(entry.getKey()) ? "ps1_pm5" : "denovo").resolve(entry.getKey());
            if (!entry.getValue().asText().equals(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(code))))
                throw new IllegalArgumentException("PP2_READING_OBSERVED_IMPLEMENTATION_CHANGED");
        }
    }
    private static ObjectNode observeDerivedCache(ObjectMapper mapper, Path project, com.fasterxml.jackson.databind.JsonNode config) {
        var out = mapper.createObjectNode();
        for (String kind : List.of("library", "modelBudget", "modelConfig")) {
            var row = out.putObject(kind);
            try {
                Path root = configuredPath(project, config.path(kind).asText());
                if ("library".equals(kind)) {
                    for (String criterion : List.of("PP2", "PS2")) cachePin(row.putObject(criterion), root.resolve("evidence").resolve(criterion).resolve("index.json"), 1048576);
                } else cachePin(row, "modelBudget".equals(kind) ? root.resolve("budget.json") : root, 1048576);
            } catch (Exception missing) { row.put("status", "UNAVAILABLE").put("reasonCode", "PP2_DERIVED_CACHE_OBSERVATION_UNAVAILABLE"); }
        }
        return out;
    }
    private static void cachePin(ObjectNode row, Path path, int maximum) throws java.io.IOException {
        if (!Files.exists(path)) { row.put("status", "NOT_PRESENT"); return; }
        byte[] raw;
        try (var stream = Files.newInputStream(path)) { raw = stream.readNBytes(maximum + 1); }
        if (raw.length > maximum) { row.put("status", "UNAVAILABLE").put("reasonCode", "PP2_DERIVED_CACHE_OBSERVATION_TECHNICAL_LIMIT"); return; }
        row.put("status", "READ").put("rawSha256", CodingTranscriptMaterialSupply.sha(raw)).put("bytes", raw.length);
    }
    record Reading(ObjectNode ledger, Path controlledWork, @org.springframework.lang.Nullable Path attemptedOutput,
            @org.springframework.lang.Nullable ObjectNode boundedReview) {
        Reading(ObjectNode ledger, Path controlledWork) { this(ledger, controlledWork, null, null); }
        Reading(ObjectNode ledger, Path controlledWork, @org.springframework.lang.Nullable Path attemptedOutput) { this(ledger, controlledWork, attemptedOutput, null); }
    }
    Reading readLiterature(ObjectMapper mapper, ObjectNode methodSources, com.fasterxml.jackson.databind.JsonNode candidates,
            com.fasterxml.jackson.databind.JsonNode known) {
        return readLiterature(mapper, methodSources, candidates, known, null);
    }
    Reading readLiterature(ObjectMapper mapper, ObjectNode methodSources, com.fasterxml.jackson.databind.JsonNode candidates,
            com.fasterxml.jackson.databind.JsonNode known, @org.springframework.lang.Nullable ObjectNode technicalTarget) {
        var failed = mapper.createObjectNode().put("schemaVersion", "pp2-relation-reading-v1").put("methodVersion", "1.1.0")
                .put("status", "UNAVAILABLE").put("relationQualification", "NOT_GRANTED").put("scientificAdoption", false).put("readingComplete", false);
        failed.putArray("papers"); failed.putObject("originals"); failed.putObject("unretainedOriginals");
        Path attemptedOutput = null;
        try {
            byte[] configBytes;
            try (var stream = Files.newInputStream(configuration)) { configBytes = stream.readNBytes(65537); }
            if (configBytes.length > 65536 || !expectedSha256.equals(CodingTranscriptMaterialSupply.sha(configBytes)))
                throw new IllegalArgumentException("PP2_READING_CONFIGURATION_PIN_CHANGED");
            var config = Objects.requireNonNull(mapper.readTree(configBytes)); Path project = Path.of(config.path("projectRoot").asText()).toRealPath();
            for (String file : FILES) if (!methodSources.path(file).asText().equals(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(project.resolve(file)))))
                throw new IllegalArgumentException("PP2_READING_OBSERVED_METHOD_CHANGED");
            verifyReadingImplementation(project, methodSources.path("derivedReaderImplementation"));
            Files.createDirectories(work); Path folder = Files.createTempDirectory(work, "assess-");
            var input = mapper.createObjectNode().put("schemaVersion", "pp2-after-claim-reading-input-v1")
                    .put("workflowConfigurationPath", configuration.toString()).put("workflowConfigurationRawSha256", expectedSha256);
            input.set("candidates", candidates.deepCopy()); input.set("knownCitationReferences", known.deepCopy());
            if (technicalTarget != null) input.set("technicalTarget", technicalTarget.deepCopy());
            byte[] inputRaw = mapper.writeValueAsBytes(input); Path inputPath = folder.resolve("input.json"), outputPath = folder.resolve("output.json"); attemptedOutput = outputPath;
            Files.write(inputPath, inputRaw, StandardOpenOption.CREATE_NEW);
            var result = new SystemExternalProcessRunner().run(List.of(configuredPath(project, config.path("python").asText()).toString(),
                    project.resolve("scripts/ps1_pm5/assess_pp2_other_phenotype_literature.py").toString(), "--input", inputPath.toString(),
                    "--output", outputPath.toString(), "--work", folder.resolve("reading").toString()), project, Duration.ofSeconds(255));
            if (!result.isSuccess()) throw new IllegalArgumentException(result.isTimedOut() ? "PP2_READING_TIMEOUT" : "PP2_READING_EXECUTION_FAILED");
            byte[] outputRaw;
            try (var stream = Files.newInputStream(outputPath)) { outputRaw = stream.readNBytes(MAX_TOTAL + 1); }
            if (outputRaw.length > MAX_TOTAL) throw new IllegalArgumentException("PP2_READING_RESULT_TECHNICAL_LIMIT");
            var value = mapper.readTree(outputRaw);
            if (value == null || !"pp2-after-claim-reading-result-v1".equals(value.path("schemaVersion").asText())
                    || !CodingTranscriptMaterialSupply.sha(inputRaw).equals(value.path("inputRawSha256").asText())
                    || !value.path("scientificAdoption").isBoolean() || value.path("scientificAdoption").asBoolean()
                    || !(value.path("readingLedger") instanceof ObjectNode ledger)) throw new IllegalArgumentException("PP2_READING_RESULT_BINDING_INVALID");
            byte[] afterConfig;
            try (var stream = Files.newInputStream(configuration)) { afterConfig = stream.readNBytes(65537); }
            if (afterConfig.length > 65536 || !expectedSha256.equals(CodingTranscriptMaterialSupply.sha(afterConfig)))
                throw new IllegalArgumentException("PP2_READING_CONFIGURATION_PIN_CHANGED");
            for (String file : FILES) if (!methodSources.path(file).asText().equals(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(project.resolve(file)))))
                throw new IllegalArgumentException("PP2_READING_OBSERVED_METHOD_CHANGED");
            verifyReadingImplementation(project, methodSources.path("derivedReaderImplementation"));
            ObjectNode bounded = null;
            if (technicalTarget != null && value.path("boundedLiteratureReview") instanceof ObjectNode observedReview
                    && "pp2-bounded-literature-review-v1".equals(observedReview.path("schemaVersion").asText())
                    && Pp2OtherPhenotypeSource.QUALIFICATION_VERSION.equals(observedReview.path("qualificationVersion").asText())
                    && observedReview.path("targetAllele").equals(technicalTarget.path("allele"))
                    && observedReview.path("positiveOtherPhenotypeGranted").isBoolean() && !observedReview.path("positiveOtherPhenotypeGranted").asBoolean()) {
                boolean bound = true;
                for (String key : List.of("nativeModelSha256", "alleleAssociationSha256", "contextSha256", "hcmPolicyBindingSha256"))
                    if (!technicalTarget.path(key).equals(observedReview.path("materialIdentity").path(key))) bound = false;
                if (bound) bounded = observedReview.deepCopy();
            }
            return new Reading(ledger, folder.resolve("reading"), null, bounded);
        } catch (Exception error) {
            String code = error.getMessage();
            String reason = code != null && code.matches("[A-Z][A-Z0-9_]{1,120}") ? code : "PP2_READING_SOURCE_OR_EXECUTION_UNAVAILABLE";
            failed = Pp2LiteratureAssessment.emptyLedger(mapper, reason); failed.put("status", "UNAVAILABLE"); failed.remove("ledgerSha256");
            failed.put("ledgerSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(failed));
            return new Reading(failed, work, attemptedOutput);
        }
    }
    private static boolean mayMatch(com.fasterxml.jackson.databind.JsonNode row, ObjectNode allele) {
        var parsed = new java.util.HashSet<String>();
        for (var value : row.path("hgvs")) {
            var match = java.util.regex.Pattern.compile("(NC_[0-9]+\\.[0-9]+):g\\.([1-9][0-9]*)([ACGT])>([ACGT])").matcher(value.asText());
            if (!match.matches()) continue;
            String contig = PublishedCriterionKnowledge.grch38PrimaryContig(match.group(1)); if (contig.isEmpty()) continue;
            parsed.add(contig + ":" + match.group(2) + ":" + match.group(3) + ":" + match.group(4));
        }
        return parsed.size() != 1 || parsed.contains(allele.path("contig").asText() + ":" + allele.path("position").asText()
                + ":" + allele.path("reference").asText() + ":" + allele.path("alternate").asText());
    }
    private static Path configuredPath(Path project, String value) {
        if (value.isBlank()) throw new IllegalArgumentException("PP2_WORKFLOW_CONFIGURED_PATH_MISSING");
        Path path = Path.of(value); return (path.isAbsolute() ? path : project.resolve(path)).toAbsolutePath().normalize();
    }
    private static void retain(ObjectNode originals, ArrayNode reads, String pin, byte[] raw, String source) {
        if (raw.length > MAX_FILE || !pin.equals(CodingTranscriptMaterialSupply.sha(raw)))
            throw new IllegalArgumentException("PP2_AUTOMATIC_SOURCE_RAW_PIN_INVALID");
        if (originals.has(pin)) return;
        long total = raw.length; for (var item : originals) total += item.path("bytes").asLong();
        if (total > MAX_TOTAL) throw new IllegalArgumentException("PP2_SOURCE_TECHNICAL_TOTAL_LIMIT");
        originals.putObject(pin).put("rawSha256", pin).put("bytes", raw.length).put("base64", Base64.getEncoder().encodeToString(raw));
        reads.addObject().put("readId", "automatic:" + pin).put("sourceId", source).put("expectedRawSha256", pin)
                .put("observedRawSha256", pin).put("archivedRawSha256", pin).put("status", "READ");
    }
    Path work() { return work; }
    Pvs1StartLossPublicSources nativeSources() { return nativeFactory.get(); }
}
