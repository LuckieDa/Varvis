package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.util.BlockCompressedInputStream;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.index.tabix.TabixIndexCreator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;

/** Independent, fixed SYNTHETIC / TEST_ONLY sources; no reviewed-material-to-provider conversion. */
public final class GeneratedMissenseReferenceFixture {
    public enum Scenario { NORMAL, SOURCE_VUS, PM5_INCOMPLETE, INDEPENDENT, ADOPTED, REUSED_EXPERT, DIRECT_RESIDUE, SOURCE_AUTOMATED }
    public final Path root;
    public final ObjectMapper mapper;
    public final boolean pm5;
    public final Scenario scenario;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ArrayNode reads;
    private final Path sourceRoot;
    private final List<ObjectNode> drafts = new ArrayList<>();
    private final List<ClinVarConditionAwareComparatorInput.SnapshotCapture> captures = new ArrayList<>();
    private final List<JsonNode> selectedReferences = new ArrayList<>(), selectedAssertions = new ArrayList<>();
    private int captureDiscovery, captureAnnotation, runtimeDiscovery, runtimeAnnotation, spliceCalls, supplierCalls;
    private String spliceIdentity = "", releasedSha = "";

    public GeneratedMissenseReferenceFixture(Path root, ObjectMapper mapper, boolean pm5) throws Exception {
        this(root, mapper, pm5, Scenario.NORMAL);
    }

    public GeneratedMissenseReferenceFixture(Path root, ObjectMapper mapper, boolean pm5, Scenario scenario) throws Exception {
        this.root = root; this.mapper = mapper; this.pm5 = pm5; this.scenario = scenario;
        adapter = new RuntimeEvidenceV2Adapter(mapper); reads = mapper.createArrayNode();
        sourceRoot = root.resolve("independent-sources"); Files.createDirectories(sourceRoot);
        // Both backgrounds, annotations, assertions and raw scores exist before any external review or seed.
        for (int i = 0; i < 2; i++) write(sourceRoot.resolve("background-" + i + ".json"), inventedSource(i));
        writeVcf(sourceRoot.resolve("clinvar.vcf.gz"));
        writeSidecar(sourceRoot.resolve("assertions.tsv.gz"), false);
        ObjectNode manifest = mapper.createObjectNode().put("schemaVersion", "generated-reference-independent-source-manifest-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY")
                .put("resourceIdentitySemantics", "CONTROLLED_TEST_PROVIDER_BINDING_NOT_REAL_RESOURCE_VERIFICATION");
        ArrayNode files = manifest.putArray("files");
        for (String name : List.of("background-0.json", "background-1.json", "clinvar.vcf.gz", "clinvar.vcf.gz.tbi", "assertions.tsv.gz", "assertions.tsv.gz.tbi"))
            files.add(sourcePin(sourceRoot.resolve(name), name.endsWith(".json")));
        write(sourceRoot.resolve("source-manifest.json"), manifest);
    }

    public GeneratedMissenseReferenceFixture build() throws Exception {
        var peripheral = new Ps1TestCatalog(mapper);
        var seed = new MissensePreparationFixture(root.resolve("seed"), peripheral, pm5);
        if (scenario == Scenario.DIRECT_RESIDUE || scenario == Scenario.SOURCE_AUTOMATED && pm5) {
            require(pm5, "Direct residue mode is PM5 only");
            for (int i = 0; i < 2; i++) seed.replace(i, "policy", policy -> {
                ObjectNode rule = (ObjectNode) policy.at("/rules/PM5");
                rule.put("mode", MissenseComparisonPolicy.DIRECT_RESIDUE);
                rule.remove(List.of("matrixKind", "matrixSha256", "comparison", "failureStrengths"));
            });
        }
        if (scenario == Scenario.INDEPENDENT || scenario == Scenario.ADOPTED) {
            for (int i = 0; i < 2; i++) {
                ObjectNode row = (ObjectNode) seed.manifest.path("backgrounds").get(i);
                ObjectNode policy = json(seed.root.resolve(row.at("/policy/path").asText()));
                policy.putObject("referenceQualification").put("mode", "EXPERT_OR_REVIEWED_INDEPENDENT")
                        .put("combination", "ACMG_2015_NON_LOF_V1").put("specificationVersion", "TEST_ONLY@1")
                        .putArray("sourceIds").add("TEST_ONLY");
                if (scenario == Scenario.ADOPTED) ((ObjectNode) policy.path("referenceQualification"))
                        .put("classificationHandling", MissenseIndependentReference.RETAIN_OR_DOWNGRADE);
                row.set("policy", seed.file("policy-" + i + ".json", policy));
            }
            seed.save();
        }
        spliceIdentity = peripheral.spliceResourceSha256;
        if (scenario == Scenario.REUSED_EXPERT || scenario == Scenario.SOURCE_AUTOMATED) {
            for (int i = 0; i < 2; i++) {
                ObjectNode row = (ObjectNode) seed.manifest.path("backgrounds").get(i);
                ObjectNode policy = json(seed.root.resolve(row.at("/policy/path").asText()));
                policy.putObject("expertSourceAdoption").put("mode", MissenseExpertClassificationAdoption.MODE)
                        .putArray("sourceIds").add("TEST_ONLY");
                if (scenario == Scenario.SOURCE_AUTOMATED) {
                    policy.withObject("expertSourceAdoption").put("mode", MissenseExpertClassificationAdoption.SOURCE_MODE)
                            .put("versionHandling", "PRESERVE_ORIGINAL_NOT_RECLASSIFIED");
                    policy.putObject("rnaApplicability").put("mode", MissenseRnaApplicability.MODE)
                            .put("sourceCoverage", MissenseRnaApplicability.COVERAGE).putArray("sourceIds").add("TEST_ONLY");
                }
                row.set("policy", seed.file("policy-" + i + ".json", policy));
            }
            seed.save();
        }
        // Keep the model fixed with the sources; its source record is the seed's actual pinned source.
        for (int i = 0; i < 2; i++) {
            JsonNode model = source(i).path("nativeModel");
            ObjectNode existing = json(seed.root.resolve(seed.manifest.at("/backgrounds/" + i + "/nativeModel/path").asText()));
            ObjectNode structural = existing.deepCopy(); structural.remove("sourceRecords");
            require(model.equals(structural), "Independent native model differs from peripheral seed");
        }
        Path currentSeed = seed.manifestPath;
        for (int i = 0; i < 2; i++) {
            Path stage = root.resolve("background-" + i); Files.createDirectory(stage);
            var captured = comparator("CAPTURE", sourceRoot.resolve("assertions.tsv.gz"))
                    .buildWithSnapshots(context(i), target(i), targetAnnotation(i));
            captures.add(captured);
            var exporter = new MissenseReferenceSnapshotExport();
            var export = exporter.write(exporter.capture(captured, snapshotSources()), stage.resolve("snapshot"));
            require(export.exported(), export.report().toPrettyString());
            Path exportManifest = Objects.requireNonNull(export.manifestPath());
            var draft = new MissenseReferenceDraftPreparation().prepare(exportManifest, Objects.requireNonNull(export.manifestSha256()), stage.resolve("draft"));
            require(draft.generated(), draft.report().toPrettyString());
            ObjectNode referenceDraft = json(stage.resolve("draft/reference-audit.draft.json")); drafts.add(referenceDraft);
            JsonNode fixed = source(i), selected = null;
            for (JsonNode reference : referenceDraft.path("references"))
                if (fixed.path("referenceAllele").equals(reference.path("allele")) && fixed.path("variationId").equals(reference.path("variationId"))) selected = reference;
            JsonNode selectedReference = Objects.requireNonNull(selected, "Fixed reference was not exported");
            selectedReferences.add(selectedReference);
            JsonNode assertion = null;
            for (JsonNode item : selectedReference.path("assertions"))
                if (fixed.path("sourceAssertion").equals(item.path("sourceAssertion"))) assertion = item;
            selectedAssertions.add(Objects.requireNonNull(assertion, "Fixed assertion was not exported"));
            Path bridgeManifest = authorReview(stage, i, currentSeed);
            var bridge = new MissenseReferenceReviewBridge().prepare(bridgeManifest, raw(bridgeManifest), stage.resolve("bridge"));
            require(bridge.bridged(), bridge.report().toPrettyString());
            currentSeed = Objects.requireNonNull(bridge.manifestPath());
        }
        // Phase two is deliberately separate: retain the original preparer's unapproved candidate.
        var candidate = new MissenseMaterialPreparation().prepare(currentSeed, raw(currentSeed), root.resolve("candidate-without-final-approval"));
        require(!candidate.ready() && "EXTERNAL_APPROVAL_MISSING".equals(candidate.report().path("reasonCode").asText()), candidate.report().toPrettyString());
        ObjectNode approval = mapper.createObjectNode().put("schemaVersion", "missense-preparation-approval-v1")
                .put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY_SEPARATE_FINAL_CANDIDATE_AUTHOR")
                .put("dataClass", "SYNTHETIC").put("criterion", criterion())
                .put("candidateSha256", candidate.report().path("candidateSha256").asText());
        approval.set("backgrounds", candidate.report().path("backgrounds").deepCopy());
        Path approvalPath = root.resolve("external-final-approval.json"); write(approvalPath, approval);
        ObjectNode approvedManifest = json(currentSeed); Path seedDirectory = Objects.requireNonNull(currentSeed.getParent());
        approvedManifest.set("approval", descriptor(seedDirectory, approvalPath));
        Path approvedPath = seedDirectory.resolve("manifest-with-external-final-approval.json"); write(approvedPath, approvedManifest);
        var released = new MissenseMaterialPreparation().prepare(approvedPath, raw(approvedPath), root.resolve("final"));
        require(released.ready(), released.report().toPrettyString());
        releasedSha = adapter.canonicalSha256(json(bundlePath()));
        return this;
    }

    public Path bundlePath() { return root.resolve("final/bundle.json"); }
    public String bundleSha256() { require(!releasedSha.isBlank(), "Build first"); return releasedSha; }
    public String canonicalSha256(JsonNode value) { return adapter.canonicalSha256(value); }
    public ObjectNode bundleDocument() throws Exception { return json(bundlePath()); }
    public String criterion() { return pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1; }
    public ObjectNode source(int i) throws Exception { return json(sourceRoot.resolve("background-" + i + ".json")); }
    public ObjectNode rawSources() throws Exception { return json(sourceRoot.resolve("source-manifest.json")); }
    public ObjectNode referenceDraft(int i) { return drafts.get(i).deepCopy(); }
    public JsonNode selectedReference(int i) { return selectedReferences.get(i).deepCopy(); }
    public JsonNode selectedAssertion(int i) { return selectedAssertions.get(i).deepCopy(); }
    public ArrayNode sourceReads() { return reads.deepCopy(); }
    public ObjectNode counts() {
        return mapper.createObjectNode().put("captureDiscovery", captureDiscovery).put("captureAnnotation", captureAnnotation)
                .put("runtimeDiscovery", runtimeDiscovery).put("runtimeAnnotation", runtimeAnnotation)
                .put("splice", spliceCalls).put("runtimeSupplier", supplierCalls);
    }
    public ObjectNode context(int i) throws Exception { return (ObjectNode) source(i).path("context").deepCopy(); }
    public TranscriptConsequence targetAnnotation(int i) throws Exception {
        ObjectNode fixed = readJsonSource(sourceRoot.resolve("background-" + i + ".json"), "TARGET_READ", "target-" + i);
        return dto(fixed.path("targetAnnotation"));
    }
    public VariantInterpretation target(int i) throws Exception {
        NormalizedVariantKey key = key(source(i).path("targetAllele"));
        var result = new VariantInterpretation(key.canonicalId(), observation(key), List.of(), List.of());
        result.applyTranscriptAnnotations(List.of(targetAnnotation(i))); return result;
    }

    public ReviewedRuleBundle loadBundle() throws Exception { return load(sourceRoot.resolve("assertions.tsv.gz")); }
    public ReviewedRuleBundle loadBundleWithCapturedInput(int index) throws Exception {
        return load(sourceRoot.resolve("assertions.tsv.gz"), captures.get(index));
    }
    public ReviewedRuleBundle loadBundleWithDateDrift() throws Exception {
        Path drift = root.resolve("runtime-date-drift"); Files.createDirectory(drift);
        ObjectNode baseline = source(0), changed = baseline.deepCopy();
        ((ObjectNode) changed.path("sourceAssertion")).put("dateLastEvaluated", "2026-03-04");
        write(drift.resolve("background-0-date-only.json"), changed);
        writeSidecar(drift.resolve("assertions.tsv.gz"), true);
        ObjectNode receipt = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY")
                .put("changedField", "/sourceAssertion/dateLastEvaluated").put("fixedBundleCanonicalSha256", bundleSha256());
        receipt.set("original", sourcePin(sourceRoot.resolve("background-0.json"), true));
        receipt.set("updated", sourcePin(drift.resolve("background-0-date-only.json"), true));
        receipt.set("originalSidecar", sourcePin(sourceRoot.resolve("assertions.tsv.gz"), false));
        receipt.set("updatedSidecar", sourcePin(drift.resolve("assertions.tsv.gz"), false));
        write(drift.resolve("drift-receipt.json"), receipt);
        return load(drift.resolve("assertions.tsv.gz"));
    }

    private ReviewedRuleBundle load(Path sidecar) throws Exception {
        return load(sidecar, null);
    }

    private ReviewedRuleBundle load(Path sidecar, ClinVarConditionAwareComparatorInput.SnapshotCapture capture) throws Exception {
        boolean drift = !sidecar.equals(sourceRoot.resolve("assertions.tsv.gz"));
        ObjectNode binding = mapper.createObjectNode().put("schemaVersion", "generated-reference-runtime-source-binding-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY")
                .put("sourceVersion", drift ? "EXPLICIT_RUNTIME_DATE_DRIFT" : "ORIGINAL_FIXED_SOURCES")
                .put("fixedBundleCanonicalSha256", bundleSha256());
        binding.set("originalSourceManifest", sourcePin(sourceRoot.resolve("source-manifest.json"), true));
        binding.set("actualSidecar", sourcePin(sidecar, false));
        binding.set("actualSidecarIndex", sourcePin(Path.of(sidecar + ".tbi"), false));
        if (drift) binding.set("driftReceipt", sourcePin(root.resolve("runtime-date-drift/drift-receipt.json"), true));
        write(root.resolve(drift ? "runtime-source-binding-date-drift.json" : "runtime-source-binding-original.json"), binding);
        var runtime = new MissenseComparisonRuntime(mapper, comparator("RUNTIME", sidecar), queries -> {
            spliceCalls++; ArrayNode requested = mapper.createArrayNode();
            ObjectNode response = mapper.createObjectNode().put("resultSchemaVersion", SpliceAiRawBatchRunner.RESULT_SCHEMA_VERSION).put("status", "SUCCEEDED");
            ArrayNode rows = response.putArray("results");
            for (var query : queries) {
                NormalizedVariantKey key = query.getKey(); int index = "11".equals(key.getChromosome()) ? 0 : 1;
                ObjectNode fixed;
                try { fixed = readJsonSource(sourceRoot.resolve("background-" + index + ".json"), "RUNTIME_SPLICE", key.canonicalId()); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                JsonNode value = fixed.path("spliceScores").path(key.canonicalId());
                require(value.isObject(), "No independently fixed splice response for " + key.canonicalId());
                ObjectNode request = requested.addObject().put("queryId", query.getQueryId()); request.set("allele", allele(key));
                request.put("geneSymbol", query.getGeneSymbol()).put("selectedTranscriptId", query.getSelectedTranscriptId());
                require(value.path("geneSymbol").asText().equals(query.getGeneSymbol()) && value.path("transcript").asText().equals(query.getSelectedTranscriptId()), "Fixed splice scope mismatch");
                ObjectNode row = rows.addObject().put("queryId", query.getQueryId()).put("status", "HIT").put("exactAlleleMatch", true);
                row.set("allele", value.path("allele").deepCopy());
                row.putArray("annotations").addObject().put("geneSymbol", value.path("geneSymbol").asText()).set("deltaScores", value.path("deltaScores").deepCopy());
            }
            try { response.put("requestId", "spliceai-batch-sha256-" + AcmgBusinessStore.sha(mapper.writeValueAsString(requested))); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return SpliceAiRawBatchRunner.BatchResult.succeeded(response, 0);
        }, spliceIdentity);
        var bound = capture == null ? runtime : runtime.withCapturedInput(capture);
        return ReviewedRuleBundle.load(mapper, bundlePath(), bundleSha256(), () -> { supplierCalls++; return bound; },
                mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("sourceSnapshotSha256", adapter.canonicalSha256(binding)));
    }

    private ClinVarConditionAwareComparatorInput comparator(String phase, Path sidecarPath) throws Exception {
        Path vcf = sourceRoot.resolve("clinvar.vcf.gz");
        var settings = new VariantAnalysisProperties(); settings.getClinVar().setEnabled(true); settings.getClinVar().setGrch38File(vcf.toString());
        var clinvar = new ClinVarTabixProvider(settings, null, (file, region) -> {
            if ("CAPTURE".equals(phase)) captureDiscovery++; else runtimeDiscovery++;
            recordRead(vcf, phase + "_DISCOVERY", region, false);
            // Sidecar access itself remains the original tabix reader; record the exact files it is bound to.
            recordRead(sidecarPath, phase + "_SIDECAR_BINDING", region, false);
            recordRead(Path.of(sidecarPath + ".tbi"), phase + "_SIDECAR_BINDING", region, false);
            List<String> result = new ArrayList<>(); String chromosome = region.substring(0, region.indexOf(':'));
            try (var stream = new BlockCompressedInputStream(vcf.toFile())) {
                String line; while ((line = stream.readLine()) != null) if (!line.startsWith("#") && line.startsWith(chromosome + "\t")) result.add(line);
            }
            return result;
        });
        var vep = new VepTranscriptAnnotationProvider(new VariantAnalysisProperties()) {
            @Override public Map<NormalizedVariantKey, List<TranscriptConsequence>> annotateBatch(List<VariantInterpretation> variants, AnalysisContext context) {
                if ("CAPTURE".equals(phase)) captureAnnotation++; else runtimeAnnotation++;
                Map<NormalizedVariantKey, List<TranscriptConsequence>> result = new LinkedHashMap<>();
                for (VariantInterpretation variant : variants) {
                    NormalizedVariantKey key = variant.getObservation().getKey(); int index = "11".equals(key.getChromosome()) ? 0 : 1;
                    try {
                        ObjectNode fixed = readJsonSource(sourceRoot.resolve("background-" + index + ".json"), phase + "_ANNOTATION", key.canonicalId());
                        JsonNode annotation = fixed.path("annotations").path(key.canonicalId());
                        if (annotation.isObject()) result.put(key, List.of(dto(annotation)));
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                }
                return result;
            }
        };
        JsonNode identity = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources").get(2);
        var sidecar = new ClinVarComparatorSidecar(mapper, sidecarPath, identity.path("resourceId").asText(),
                identity.path("resourceVersion").asText(), identity.path("identityFingerprintSha256").asText());
        return new ClinVarConditionAwareComparatorInput(clinvar, vep, sidecar, new VepProteinRelationClassifier());
    }

    private Path authorReview(Path stage, int index, Path seedPath) throws Exception {
        ObjectNode seed = json(seedPath), manifest = mapper.createObjectNode().put("schemaVersion", "missense-reference-review-bridge-manifest-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY");
        manifest.set("background", seed.at("/backgrounds/" + index + "/background").deepCopy());
        manifest.set("preparationSeed", descriptor(stage, seedPath));
        for (String[] role : List.of(new String[]{"referenceDraft", "reference-audit.draft.json"}, new String[]{"rnaDraft", "rna-pairs.draft.json"}, new String[]{"draftReport", "draft-report.json"}))
            manifest.set(role[0], descriptor(stage, stage.resolve("draft").resolve(role[1])));
        String prefix = "TEST_ONLY_BG" + index + "_";
        for (String role : List.of("referenceDraft", "rnaDraft", "draftReport", "review"))
            manifest.withObject("factSources").set(role, record(prefix + role, "SYNTHETIC@1"));
        ObjectNode evidence = mapper.createObjectNode().put("schemaVersion", "test-only-independent-review-evidence-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY").put("background", index)
                .put("statement", "Invented standalone PS3 evidence, explicit absence of contradictory exact-pair RNA report; no professional or scientific approval");
        Path evidencePath = stage.resolve("external-review-evidence.json"); write(evidencePath, evidence);
        ObjectNode evidenceFile = descriptor(stage, evidencePath).put("format", "JSON").put("sourceDigestKind", "CANONICAL_JSON");
        evidenceFile.set("sourceRecord", record(prefix + "EVIDENCE", "SYNTHETIC@1")); manifest.putArray("reviewEvidence").add(evidenceFile);
        ObjectNode review = mapper.createObjectNode().put("schemaVersion", "missense-reference-review-declaration-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY"); review.set("background", manifest.path("background"));
        ObjectNode binding = review.putObject("draftBinding").put("draftId", drafts.get(index).path("draftId").asText());
        for (String role : List.of("referenceDraft", "rnaDraft", "draftReport")) {
            ObjectNode pin = binding.putObject(role); pin.set("rawSha256", manifest.path(role).path("rawSha256")); pin.set("canonicalSha256", manifest.path(role).path("canonicalSha256"));
        }
        ObjectNode audit = review.putObject("referenceAudit").put("status", "REVIEWED").put("reviewId", prefix + "EXTERNAL_REFERENCE_AUTHOR");
        audit.putArray("sourceIds").add(prefix + "referenceDraft").add(prefix + "EVIDENCE");
        ObjectNode judgment = review.putArray("references").addObject()
                .put("referenceDraftId", selectedReference(index).path("referenceDraftId").asText())
                .put("assertionDraftId", selectedAssertion(index).path("assertionDraftId").asText()).put("decision", "ADOPT").putObject("review")
                .put("reviewId", prefix + "EXTERNAL_SELECTION").put("classification", index == 0 ? "PATHOGENIC" : "LIKELY_PATHOGENIC")
                .put("assertionStatus", "ACTIVE").put("conflictState", "EXTERNALLY_REVIEWED_TEST_ONLY");
        for (String field : List.of("hgncId", "geneSymbol", "mondoId", "moi")) judgment.set(field, manifest.path("background").path(field));
        judgment.putArray("sourceIds").add(prefix + "referenceDraft").add(prefix + "EVIDENCE");
        ObjectNode independence = judgment.putObject("independence").put("status", "REVIEWED_SUFFICIENT_WITHOUT_PS1_PM5")
                .put("reviewId", prefix + "EXTERNAL_INDEPENDENCE").put("originalSpecificationVersion", "TEST_ONLY@1")
                .put("classificationAfterExclusion", index == 0 ? "PATHOGENIC" : "LIKELY_PATHOGENIC")
                .put("dependencyReview", "REVIEWED_NO_COMPARISON_DEPENDENCY");
        independence.putArray("sourceIds").add(prefix + "EVIDENCE"); independence.putArray("criteria").add("PS3"); independence.putArray("dependencyAlleles");
        if (scenario == Scenario.INDEPENDENT || scenario == Scenario.ADOPTED) {
            independence.withArray("criteria").add("PS4");
            ObjectNode q = judgment.putObject("qualification").put("schemaVersion", "missense-independent-reference-v1")
                    .put("status", "REVIEWED").put("reviewId", prefix + "TEST_ONLY_INDEPENDENT")
                    .put("combination", "ACMG_2015_NON_LOF_V1").put("specificationVersion", "TEST_ONLY@1")
                    .put("sourceAssertionSha256", adapter.canonicalSha256(selectedAssertion(index).path("sourceAssertion")))
                    .put("conflictReview", "REVIEWED_NO_UNRESOLVED_CONFLICT").put("independenceRationale", "TEST_ONLY unrelated synthetic assay and cases; no comparison dependency");
            q.putArray("sourceIds").add(prefix + "EVIDENCE");
            ArrayNode criteria = q.putArray("evidence");
            for (String code : List.of("PS3", "PS4")) criteria.addObject().put("code", code)
                    .put("strength", (index == 1 || scenario == Scenario.ADOPTED) && "PS4".equals(code) ? "MODERATE" : "STRONG")
                    .put("independenceGroup", code).put("sourceLocator", "TEST_ONLY:" + code)
                    .put("rationale", "Invented observation reviewed for this test only").putArray("sourceIds").add(prefix + "EVIDENCE");
            if (scenario == Scenario.ADOPTED) {
                q.put("schemaVersion", "missense-independent-reference-v2").put("adoptedClassification", "LIKELY_PATHOGENIC");
                independence.put("classificationAfterExclusion", "LIKELY_PATHOGENIC");
                judgment.put("expertEvidenceReferenceId", "synthetic-expert-" + index);
                ObjectNode packet = mapper.createObjectNode().put("schemaVersion", "missense-expert-reference-evidence-v1")
                        .put("draft", true).put("reviewStatus", "UNREVIEWED").put("scientificAdoption", false);
                ObjectNode row = packet.putArray("references").addObject().put("referenceId", "synthetic-expert-" + index)
                        .put("bindingStatus", "EXACT_SOURCE_BOUND").put("reviewStatus", "UNREVIEWED").put("scientificAdoption", false);
                row.set("allele", selectedReference(index).path("allele").deepCopy());
                row.set("variationId", selectedReference(index).path("variationId").deepCopy());
                row.set("originalAssertion", selectedAssertion(index).path("sourceAssertion").deepCopy());
                row.putArray("geneSymbols").add(manifest.at("/background/geneSymbol").asText());
                row.putArray("sourceIds").add(prefix + "EVIDENCE");
                row.putArray("appliedComparisonCriteria").add("PS1");
                row.putArray("gapReasons").add("SYNTHETIC_TEST_ONLY_NOT_SCIENTIFIC_ADOPTION");
                row.putArray("criterionAssessments").addObject().put("code", "PS1").put("comment", "Invented old comparison; excluded from current evidence");
                Path packetPath = stage.resolve("expert.UNREVIEWED.json"); write(packetPath, packet);
                manifest.set("expertEvidence", descriptor(stage, packetPath));
            }
        }
        ObjectNode rna = review.putObject("rnaReview").put("status", "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED")
                .put("rnaEffect", "UNKNOWN").put("normalRnaClaimed", false).put("reviewId", prefix + "EXTERNAL_RNA");
        rna.putArray("sourceIds").add(prefix + "EVIDENCE"); rna.putArray("knownConcerns");
        ArrayNode pairs = review.putArray("rnaComparisons");
        for (JsonNode pair : json(stage.resolve("draft/rna-pairs.draft.json")).path("pairs"))
            if (source(index).path("referenceAllele").equals(pair.path("referenceAllele"))) {
                ObjectNode paired = pairs.addObject().put("pairDraftId", pair.path("pairDraftId").asText()).putObject("review")
                        .put("status", "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED").put("reviewId", prefix + "EXTERNAL_EXACT_RNA_PAIR");
                paired.putArray("sourceIds").add(prefix + "EVIDENCE"); paired.putArray("knownConcerns");
            }
        require(pairs.size() == 1, "No unique fixed RNA pair");
        if (scenario == Scenario.REUSED_EXPERT || scenario == Scenario.SOURCE_AUTOMATED) {
            judgment.put("expertClassificationAdoption", MissenseExpertClassificationAdoption.MODE)
                    .put("expertEvidenceReferenceId", "TEST_ONLY_EXPERT");
            ObjectNode row = MissenseReviewedReuseFixture.expertRow(mapper, selectedReference(index),
                    selectedAssertion(index).path("sourceAssertion"), manifest.at("/background/mondoId").asText(),
                    manifest.at("/background/geneSymbol").asText(), prefix + "EVIDENCE");
            ObjectNode packet = mapper.createObjectNode().put("schemaVersion", "missense-expert-reference-evidence-v1")
                    .put("draft", true).put("reviewStatus", "UNREVIEWED").put("scientificAdoption", false);
            packet.putArray("references").add(row);
            Path packetPath = stage.resolve("expert.UNREVIEWED.json"); write(packetPath, packet);
            manifest.set("expertEvidence", descriptor(stage, packetPath));
            ObjectNode library = MissenseReviewedReuseFixture.library(mapper, review, selectedReference(index), selectedAssertion(index),
                    json(stage.resolve("draft/rna-pairs.draft.json")).path("pairs").get(0));
            if (scenario == Scenario.SOURCE_AUTOMATED) {
                library.put("schemaVersion", "missense-reusable-review-v2").put("status", "POLICY_QUALIFIED");
                library.withObject("referenceAudit").put("status", "SOURCE_VERIFIED");
                ObjectNode selectedReview = (ObjectNode) library.at("/references/0/review");
                selectedReview.put("expertClassificationAdoption", MissenseExpertClassificationAdoption.SOURCE_MODE).remove("independence");
                library.withObject("rnaReview").put("mode", MissenseRnaApplicability.MODE)
                        .put("status", MissenseRnaApplicability.STATUS).put("sourceCoverage", MissenseRnaApplicability.COVERAGE);
                library.putArray("rnaComparisons");
            }
            MissenseReviewedReuseFixture.sourceBindings(library, manifest);
            Path libraryPath = stage.resolve("prior-reviewed-library.json"); write(libraryPath, library);
            manifest.set("reusableReview", descriptor(stage, libraryPath));
            Path file = stage.resolve("bridge-manifest.json"); write(file, manifest); return file;
        }
        Path declaration = stage.resolve("external-reference-rna-review.json"); write(declaration, review);
        manifest.set("review", descriptor(stage, declaration)); Path file = stage.resolve("bridge-manifest.json"); write(file, manifest); return file;
    }

    private ObjectNode inventedSource(int index) throws Exception {
        String contig = index == 0 ? "11" : "13", gene = index == 0 ? "HBB" : "GJB2";
        String tx = index == 0 ? "ENST00000647020.1" : "ENST00000382848.5", protein = index == 0 ? "ENSP000001.1" : "ENSP000002.1";
        String hgnc = index == 0 ? "HGNC:4827" : "HGNC:4284", mondo = index == 0 ? "MONDO:0013517" : "MONDO:0019497";
        boolean different = pm5 && !(scenario == Scenario.PM5_INCOMPLETE && index == 0);
        NormalizedVariantKey target = new NormalizedVariantKey("GRCh38", contig, 105, "A", "C");
        NormalizedVariantKey reference = new NormalizedVariantKey("GRCh38", contig, different ? 103 : 105, different ? "G" : "A", different ? "A" : "T");
        ObjectNode fixed = mapper.createObjectNode().put("schemaVersion", "independent-generated-reference-source-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY").put("backgroundIndex", index)
                .put("variationId", Integer.toString(index + 1));
        fixed.set("targetAllele", allele(target)); fixed.set("referenceAllele", allele(reference));
        ObjectNode assertion = fixed.putObject("sourceAssertion").put("scv", "SCV00000000" + (index + 1) + ".1")
                .put("classification", scenario == Scenario.SOURCE_VUS && index == 0 ? "Uncertain significance" : index == 0 ? "Pathogenic" : "Likely pathogenic")
                .put("reviewStatus", scenario == Scenario.INDEPENDENT || scenario == Scenario.ADOPTED ? "criteria provided, single submitter" : "reviewed by expert panel")
                .put("dateLastEvaluated", index == 0 ? "2026-01-02" : "2026-02-03").put("geneSymbol", gene);
        assertion.putObject("condition").put("submittedRaw", mondo).put("reportedRaw", mondo);
        assertion.putObject("sourceLocator").put("logicalName", "TEST_ONLY_independent_variant_summary.txt").put("line", index + 1);
        ObjectNode targetAnnotation = fullAnnotation(gene, tx, protein, 105, "C");
        ObjectNode referenceAnnotation = fullAnnotation(gene, tx, protein, different ? 103 : 105, different ? "A" : "T");
        fixed.set("targetAnnotation", targetAnnotation); fixed.set("referenceAnnotation", referenceAnnotation);
        ObjectNode annotations = fixed.putObject("annotations"); annotations.set(target.canonicalId(), targetAnnotation);
        annotations.set(reference.canonicalId(), referenceAnnotation);
        annotations.set(new NormalizedVariantKey("GRCh38", contig, 103, "G", "A").canonicalId(), fullAnnotation(gene, tx, protein, 103, "A"));
        annotations.set(new NormalizedVariantKey("GRCh38", contig, 104, "A", "C").canonicalId(), fullAnnotation(gene, tx, protein, 104, "C"));
        ObjectNode scores = fixed.putObject("spliceScores");
        for (NormalizedVariantKey key : List.of(target, reference)) {
            ObjectNode score = scores.putObject(key.canonicalId()).put("geneSymbol", gene).put("transcript", tx); score.set("allele", allele(key));
            ObjectNode rawScores = score.putObject("deltaScores"); for (String field : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) rawScores.put(field, "0.01");
        }
        ObjectNode model = fixed.putObject("nativeModel").put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "TEST_ONLY_" + (index + 1)).put("modelVersion", "1").put("assembly", "GRCh38").put("contig", contig)
                .put("strand", 1).put("hgncId", hgnc).put("geneSymbol", gene).put("transcript", tx).put("protein", protein)
                .put("cdsSequence", "ATGGAACCTGGCTTTAAATAA").put("cdsLength", 21).put("proteinLength", 6);
        model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 120).put("cdsStart", 1).put("cdsEnd", 21);
        ObjectNode context;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json"))) { context = (ObjectNode) mapper.readTree(stream).path("context").deepCopy(); }
        context.putObject("gene").put("symbol", gene).put("hgncId", hgnc); context.putObject("disease").put("mondoId", mondo).put("label", "TEST_ONLY_" + gene); context.put("moi", "AUTOSOMAL_RECESSIVE");
        int dot = tx.lastIndexOf('.'); context.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                .put("selectionPolicy", "TEST_ONLY_FIXED_TRANSCRIPT@1").put("annotationSourceVersion", "116.0");
        ObjectNode variant = (ObjectNode) context.path("variant");
        for (String role : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) variant.set(role, allele(target));
        variant.put("alleleId", adapter.canonicalAlleleId(allele(target), variant.path("normalization"))); context.put("contextId", adapter.canonicalContextId(context));
        fixed.set("context", context); return fixed;
    }

    private ObjectNode fullAnnotation(String gene, String tx, String protein, int position, String alternate) throws Exception {
        String coding = position == 103 ? "c.4G>A" : position == 104 ? "c.5A>C" : "c.6A>" + alternate;
        String proteinChange = position == 103 ? "p.Glu2Lys" : position == 104 ? "p.Glu2Ala" : "p.Glu2Asp";
        String amino = position == 103 ? "E/K" : position == 104 ? "E/A" : "E/D";
        String codons = position == 103 ? "Gaa/Aaa" : position == 104 ? "gAa/gCa" : "gaA/ga" + alternate;
        ObjectNode annotation = mapper.createObjectNode().put("geneSymbol", gene).put("transcriptAccession", tx).put("hgvsCoding", tx + ":" + coding)
                .put("hgvsProtein", protein + ":" + proteinChange).put("consequence", "missense_variant").put("exon", "1/1")
                .put("proteinPosition", "2").put("aminoAcids", amino).put("codons", codons).put("source", "VEP")
                .put("status", "COMPLETED").put("version", "116.0").put("timestamp", "2026-09-26T00:00:00Z").put("maneSelect", true).put("canonical", true)
                .put("impact", "MODERATE").put("transcriptSource", "Ensembl");
        return mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).valueToTree(dto(annotation));
    }

    private ObjectNode snapshotSources() {
        JsonNode profile = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper);
        ObjectNode sources = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY");
        sources.set("CLINVAR_VCF", profile.path("resources").get(0).deepCopy());
        sources.set("SIDECAR", profile.path("resources").get(2).deepCopy());
        ObjectNode vep = profile.path("resources").get(1).deepCopy(); vep.put("resourceVersion", "116.0"); sources.set("VEP", vep); return sources;
    }

    private void writeVcf(Path file) throws Exception {
        var index = new TabixIndexCreator(TabixFormat.VCF);
        try (var output = new BlockCompressedOutputStream(file.toFile())) {
            output.write(("##fileformat=VCFv4.2\n##source=TEST_ONLY_INDEPENDENT_FIXED_FACTS\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n").getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 2; i++) {
                JsonNode fixed = source(i), allele = fixed.path("referenceAllele"); String chromosome = allele.path("contig").asText(); int position = allele.path("position").asInt();
                index.addFeature(feature(chromosome, position), output.getFilePointer());
                output.write(vcfRow(allele, i + 1, fixed.at("/sourceAssertion/scv").asText()).getBytes(StandardCharsets.UTF_8));
                if (scenario == Scenario.PM5_INCOMPLETE && i == 0) {
                    ObjectNode missing = allele.deepCopy(); missing.put("position", 105).put("reference", "A").put("alternate", "G");
                    index.addFeature(feature(chromosome, 105), output.getFilePointer());
                    output.write(vcfRow(missing, 3, "SCV000000003.1").getBytes(StandardCharsets.UTF_8));
                }
            }
            index.finalizeIndex(output.getFilePointer()).writeBasedOnFeaturePath(file);
        }
    }

    private void writeSidecar(Path file, boolean drift) throws Exception {
        var index = new TabixIndexCreator(new TabixFormat(0, 1, 2, 3, '#', 0));
        try (var output = new BlockCompressedOutputStream(file.toFile())) {
            for (int i = 0; i < 2; i++) {
                JsonNode fixed = drift && i == 0 ? json(root.resolve("runtime-date-drift/background-0-date-only.json")) : source(i);
                JsonNode allele = fixed.path("referenceAllele"); int id = i + 1;
                index.addFeature(feature("VID", id), output.getFilePointer());
                String row = "VID\t" + id + "\t" + id + "\t" + id + "\tGRCh38\t" + allele.path("contig").asText() + "\t" + allele.path("position").asInt()
                        + "\t" + allele.path("reference").asText() + "\t" + allele.path("alternate").asText() + "\t" + mapper.createArrayNode().add(fixed.at("/sourceAssertion/geneSymbol"))
                        + "\tNO_CONFLICT\t[]\t" + mapper.createArrayNode().add(fixed.path("sourceAssertion")) + "\n";
                output.write(row.getBytes(StandardCharsets.UTF_8));
            }
            index.finalizeIndex(output.getFilePointer()).writeBasedOnFeaturePath(file);
        }
    }

    private String vcfRow(JsonNode allele, int id, String scv) { return allele.path("contig").asText() + "\t" + allele.path("position").asInt() + "\t" + id + "\t" + allele.path("reference").asText() + "\t" + allele.path("alternate").asText() + "\t.\tPASS\tCLNSIGSCV=" + scv + "\n"; }
    private htsjdk.tribble.Feature feature(String contig, int position) { return new htsjdk.tribble.Feature() { public String getContig() { return contig; } public int getStart() { return position; } public int getEnd() { return position; } }; }
    private ObjectNode record(String id, String version) { return mapper.createObjectNode().put("sourceId", id).put("version", version).put("url", "https://example.invalid/generated-reference/" + id); }
    private ObjectNode descriptor(Path directory, Path file) throws Exception {
        return mapper.createObjectNode().put("path", directory.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                .put("rawSha256", raw(file)).put("canonicalSha256", adapter.canonicalSha256(json(file)));
    }
    private ObjectNode sourcePin(Path file, boolean json) throws IOException {
        ObjectNode result = mapper.createObjectNode().put("path", root.relativize(file).toString().replace('\\', '/')).put("rawSha256", raw(file));
        if (json) result.put("canonicalSha256", adapter.canonicalSha256(json(file))); return result;
    }
    private ObjectNode readJsonSource(Path file, String phase, String query) throws IOException {
        byte[] bytes = Files.readAllBytes(file); ObjectNode value = (ObjectNode) mapper.readTree(bytes);
        reads.addObject().put("phase", phase).put("path", root.relativize(file).toString().replace('\\', '/')).put("query", query)
                .put("rawSha256", Pm4MaterialPreparation.sha(bytes)).put("canonicalSha256", adapter.canonicalSha256(value)); return value;
    }
    private void recordRead(Path file, String phase, String query, boolean json) throws IOException { ObjectNode row = sourcePin(file, json); row.put("phase", phase).put("query", query); reads.add(row); }
    private ObjectNode json(Path file) throws IOException { return (ObjectNode) mapper.readTree(file.toFile()); }
    private String raw(Path file) throws IOException { return Pm4MaterialPreparation.sha(Files.readAllBytes(file)); }
    private void write(Path file, JsonNode value) throws IOException { mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value); }
    private TranscriptConsequence dto(JsonNode annotation) throws Exception {
        ObjectNode parsed = ((ObjectNode) annotation).deepCopy();
        for (var alias : Map.of("hgvsc", "hgvsCoding", "hgvsp", "hgvsProtein").entrySet()) if (parsed.has(alias.getKey())) {
            require(parsed.path(alias.getKey()).equals(parsed.path(alias.getValue())), "Annotation alias mismatch");
            parsed.remove(alias.getKey());
        }
        return Objects.requireNonNull(mapper.treeToValue(parsed, TranscriptConsequence.class));
    }
    private NormalizedVariantKey key(JsonNode allele) { return new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(), allele.path("position").asInt(), allele.path("reference").asText(), allele.path("alternate").asText()); }
    private ObjectNode allele(NormalizedVariantKey key) { return mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome()).put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate()); }
    private VariantQcObservation observation(NormalizedVariantKey key) { return new VariantQcObservation(key, null, null, null, null, null, null, "PASS", "SNV", null); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
