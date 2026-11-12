package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual export -> draft -> bridge -> original prepare/loader, entirely SYNTHETIC / TEST_ONLY. */
class MissenseReferenceReviewBridgeTest {
    @Test void reviewedConditionAndIndependentEvidenceReachOriginalPreparerWithUnresolvedSourceRetained() throws Exception {
        var f = fixture("mapped-expert", 0);
        var chosen = f.selectedReference.at("/assertions/0");
        ((ObjectNode) f.declaration.at("/references/0")).put("assertionDraftId", f.selectedAssertion.path("assertionDraftId").asText());
        ((ObjectNode) f.declaration.at("/references/1")).put("assertionDraftId", chosen.path("assertionDraftId").asText());
        var review = (ObjectNode) f.declaration.at("/references/1/review");
        review.put("expertEvidenceReferenceId", "TEST_ONLY_EXPERT");
        var mapping = review.putArray("conditionMappings").addObject()
                .put("schemaVersion", "reviewed-clinvar-condition-mapping-v1").put("status", "REVIEWED_EXACT")
                .put("relation", "EXACT_DISEASE").put("reviewId", "TEST_ONLY_MAPPING")
                .put("mondoId", f.manifest.at("/background/mondoId").asText());
        mapping.putObject("sourceCondition")
                .put("submittedRaw", chosen.at("/sourceAssertion/condition/submittedRaw").asText())
                .put("reportedRaw", chosen.at("/sourceAssertion/condition/reportedRaw").asText());
        mapping.putArray("sourceIds").add("REVIEW_EVIDENCE").add("FACT_referenceDraft");
        var q = review.putObject("qualification").put("schemaVersion", "missense-independent-reference-v1")
                .put("status", "REVIEWED").put("reviewId", "TEST_ONLY_INDEPENDENT")
                .put("combination", "ACMG_2015_NON_LOF_V1").put("specificationVersion", "TEST_ONLY@1")
                .put("sourceAssertionSha256", adapter.canonicalSha256(chosen.path("sourceAssertion")))
                .put("conflictReview", "REVIEWED_NO_UNRESOLVED_CONFLICT").put("independenceRationale", "Synthetic independent assay and families");
        q.putArray("sourceIds").add("REVIEW_EVIDENCE");
        for (String code : List.of("PS3", "PM3")) q.withArray("evidence").addObject().put("code", code)
                .put("strength", "STRONG").put("independenceGroup", code).put("sourceLocator", "TEST_ONLY:" + code)
                .put("rationale", "Invented engineering observation").putArray("sourceIds").add("REVIEW_EVIDENCE");
        ((ObjectNode) review.path("independence")).putArray("criteria").add("PS3").add("PM3");
        var packet = mapper.createObjectNode().put("schemaVersion", "missense-expert-reference-evidence-v1")
                .put("draft", true).put("reviewStatus", "UNREVIEWED").put("scientificAdoption", false);
        var row = MissenseReviewedReuseFixture.expertRow(mapper, f.selectedReference, chosen.path("sourceAssertion"),
                f.manifest.at("/background/mondoId").asText(), f.manifest.at("/background/geneSymbol").asText(), "REVIEW_EVIDENCE");
        row.put("bindingStatus", "EXACT_RECORD_BOUND_CONDITION_UNRESOLVED");
        row.withArray("gapReasons").add("EXACT_SCV_DISEASE_MAPPING_UNRESOLVED");
        packet.putArray("references").add(row); f.manifest.set("expertEvidence", f.file("expert.json", packet));
        var originalPacket = Files.readAllBytes(f.root.resolve("expert.json"));
        var policyPath = componentFile(f.seed.root, f.seed.manifest, 0, "policy"); var policy = json(policyPath);
        policy.putObject("referenceQualification").put("mode", "EXPERT_OR_REVIEWED_INDEPENDENT")
                .put("combination", "ACMG_2015_NON_LOF_V1").put("specificationVersion", "TEST_ONLY@1")
                .putArray("sourceIds").add(policy.at("/sources/0/sourceId").asText());
        mapper.writerWithDefaultPrettyPrinter().writeValue(policyPath.toFile(), policy);
        ((ObjectNode) f.seed.manifest.at("/backgrounds/0/policy")).put("rawSha256", raw(policyPath))
                .put("canonicalSha256", adapter.canonicalSha256(policy));
        f.seed.save(); f.manifest.set("preparationSeed", f.descriptor(f.seed.manifestPath)); f.save();
        var bridged = f.bridge(temporary.resolve("mapped-expert-bridge")); assertBridged(bridged);
        var candidate = temporary.resolve("mapped-expert-candidate");
        assertMissingApproval(new MissenseMaterialPreparation().prepare(bridged.manifestPath(), bridged.manifestSha256(), candidate), candidate);
        assertEquals(row, component(java.util.Objects.requireNonNull(bridged.manifestPath()).getParent(), json(bridged.manifestPath()), 0, "referenceAudit").at("/references/0/expertEvidence/record"));
        assertArrayEquals(originalPacket, Files.readAllBytes(f.root.resolve("expert.json")));
        mapping.put("status", "UNREVIEWED"); f.save();
        assertFalse(f.bridge(temporary.resolve("unreviewed-condition-refused")).bridged());
    }

    @Test void requestedExpertPacketCannotBeOmittedOrReplacedAfterPinning() throws Exception {
        var f = fixture("expert-packet-pins", 0);
        ((ObjectNode) f.declaration.at("/references/1/review")).put("expertEvidenceReferenceId", "synthetic-source");
        f.save();
        var missing = f.bridge(temporary.resolve("expert-missing"));
        assertFalse(missing.bridged()); assertEquals("EXPERT_EVIDENCE_PACKET_REQUIRED", missing.report().path("reasonCode").asText());
        var packet = mapper.createObjectNode().put("schemaVersion", "missense-expert-reference-evidence-v1");
        f.manifest.set("expertEvidence", f.file("expert.json", packet)); f.save();
        Files.writeString(f.root.resolve("expert.json"), "{}");
        var drift = f.bridge(temporary.resolve("expert-drift"));
        assertFalse(drift.bridged()); assertEquals("INPUT_RAW_SHA_MISMATCH", drift.report().path("reasonCode").asText());
    }
    @Test void pinnedXmlEvidenceUsesExistingBridgeWithoutGrantingFinalApproval() throws Exception {
        var f = fixture("xml-evidence", 0);
        byte[] xml = "<VariationArchive><Comment>SYNTHETIC source, not approval</Comment></VariationArchive>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(f.root.resolve("source-000.xml"), xml);
        String digest = Pm4MaterialPreparation.sha(xml);
        String sourceId = "clinvar-full-VCV000000001.1-" + digest.substring(0, 16);
        ObjectNode descriptor = f.manifest.withArray("reviewEvidence").addObject()
                .put("path", "source-000.xml").put("rawSha256", digest)
                .put("format", "BYTES").put("sourceDigestKind", "RAW_BYTES");
        descriptor.putObject("sourceRecord").put("sourceId", sourceId).put("version", "VCV000000001.1")
                .put("url", "https://example.invalid/synthetic-source").put("sha256", digest);
        ((ObjectNode) f.declaration.at("/references/1/review")).withArray("sourceIds").add(sourceId);
        f.save();
        var result = f.bridge(temporary.resolve("xml-bridged"));
        assertTrue(result.bridged(), result.report().toPrettyString());
        Path resultManifest = Objects.requireNonNull(result.manifestPath());
        JsonNode manifest = json(resultManifest);
        JsonNode copied = null;
        for (JsonNode source : manifest.path("sources"))
            if (sourceId.equals(source.at("/sourceRecord/sourceId").asText())) copied = source;
        assertNotNull(copied);
        JsonNode copiedSource = Objects.requireNonNull(copied);
        assertEquals("BYTES", copiedSource.path("format").asText());
        assertArrayEquals(xml, Files.readAllBytes(resultManifest.getParent().resolve(copiedSource.path("path").asText())));
        assertFalse(manifest.has("approval"));
        assertEquals("NOT_PROVIDED", result.report().path("finalApproval").asText());
        assertMissingApproval(new MissenseMaterialPreparation().prepare(resultManifest,
                Objects.requireNonNull(result.manifestSha256()), temporary.resolve("xml-candidate")), temporary.resolve("xml-candidate"));
    }

    @Test void externalConditionMappingIsBoundThroughTheOriginalPreparationBridge() throws Exception {
        var f=fixture("condition-map",0);var review=(ObjectNode)f.declaration.at("/references/1/review");
        var row=review.putArray("conditionMappings").addObject().put("schemaVersion","reviewed-clinvar-condition-mapping-v1")
                .put("status","REVIEWED_EXACT").put("relation","EXACT_DISEASE").put("reviewId","SYNTHETIC_EXACT_MAPPING")
                .put("mondoId",f.manifest.at("/background/mondoId").asText());
        row.putObject("sourceCondition").put("submittedRaw",f.selectedAssertion.at("/sourceAssertion/condition/submittedRaw").asText())
                .put("reportedRaw",f.selectedAssertion.at("/sourceAssertion/condition/reportedRaw").asText());
        row.putArray("sourceIds").add("REVIEW_EVIDENCE");f.save();
        var result=f.bridge(temporary.resolve("mapped-bridge"));assertTrue(result.bridged(),result.report().toPrettyString());
        var manifest=mapper.readTree(java.util.Objects.requireNonNull(result.manifestPath()).toFile());
        var audit=mapper.readTree(java.util.Objects.requireNonNull(result.manifestPath()).getParent().resolve(manifest.at("/backgrounds/0/referenceAudit/path").asText()).toFile());
        assertEquals(review.path("conditionMappings"),audit.at("/references/0/conditionMappings"));
        row.putArray("sourceIds").add("FACT_referenceDraft");f.save();
        var refused=f.bridge(temporary.resolve("fact-only-bridge"));assertFalse(refused.bridged());
        assertEquals("SCIENTIFIC_REVIEW_EVIDENCE_SOURCE_REQUIRED",refused.report().path("reasonCode").asText());
    }
    @Test void realSourceDateFormatIsNormalizedWithoutRewritingSourceAssertion() throws Exception {
        var f=new MissenseReferenceReviewBridgeFixture(temporary.resolve("english-date"),0,"May 28, 2019");
        var result=f.bridge(temporary.resolve("bridged-english-date"));assertTrue(result.bridged(),result.report().toPrettyString());
        var manifest=mapper.readTree(java.util.Objects.requireNonNull(result.manifestPath()).toFile());var background=manifest.path("backgrounds").get(0);
        var audit=mapper.readTree(java.util.Objects.requireNonNull(result.manifestPath()).getParent().resolve(background.at("/referenceAudit/path").asText()).toFile());
        var ref=audit.path("references").get(0);assertEquals("2019-05-28",ref.path("evaluationDate").asText());
        assertEquals(f.selectedAssertion.path("sourceAssertion"),ref.path("sourceAssertion"));
        assertEquals("NOT_PROVIDED",result.report().path("finalApproval").asText());
    }
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void actualUpstreamFactsAndIndependentDeclarationsReachOriginalPreparerWithoutPatching(int background) throws Exception {
        var f = fixture("input", background);
        Map<String, byte[]> immutable = new LinkedHashMap<>();
        for (String name : List.of("reference-audit.draft.json", "rna-pairs.draft.json", "draft-report.json"))
            immutable.put(name, Files.readAllBytes(f.draftDir.resolve(name)));
        Path baseline = temporary.resolve("seed-candidate");
        assertMissingApproval(f.seed.prepare(baseline), baseline);
        Path output = temporary.resolve("bridged"), again = temporary.resolve("repeat");
        var bridge = f.bridge(output); assertBridged(bridge);
        var repeat = f.bridge(again); assertBridged(repeat);
        ObjectNode manifest = json(bridge.manifestPath());
        assertFalse(manifest.has("approval")); assertFalse(Files.exists(output.resolve("bundle.json")));
        assertArrayEquals(Files.readAllBytes(bridge.manifestPath()), Files.readAllBytes(repeat.manifestPath()));
        for (String role : List.of("referenceAudit", "rnaReview"))
            assertArrayEquals(Files.readAllBytes(componentFile(output, manifest, background, role)),
                    Files.readAllBytes(componentFile(again, json(repeat.manifestPath()), background, role)));
        for (Map.Entry<String, byte[]> source : immutable.entrySet())
            assertArrayEquals(source.getValue(), Files.readAllBytes(f.draftDir.resolve(source.getKey())));
        assertOutputFileGraph(f, output, manifest);
        JsonNode selectionMap = json(output.resolve("selection-map.json"));
        assertEquals(2, selectionMap.path("references").size());
        assertEquals("DO_NOT_ADOPT", selectionMap.at("/references/0/decision").asText());
        assertFalse(selectionMap.at("/references/0/reason").asText().isBlank());
        assertEquals(f.selectedAssertion.path("assertionDraftId"), selectionMap.at("/references/1/assertionDraftId"));
        Path candidate = temporary.resolve("unapproved");
        var pending = new MissenseMaterialPreparation().prepare(bridge.manifestPath(), bridge.manifestSha256(), candidate);
        assertMissingApproval(pending, candidate);
        Path approvedInput = temporary.resolve("approved-input");
        Path approvedManifest = withExternalApproval(output, candidate, approvedInput);
        Path released = temporary.resolve("release");
        var prepared = new MissenseMaterialPreparation().prepare(approvedManifest, raw(approvedManifest), released);
        assertTrue(prepared.ready(), prepared.report().toPrettyString());
        assertEquals("MATCHED_EXTERNAL_DECLARATION", prepared.report().path("externalReviewBinding").asText());
        assertEquals("NOT_RUN", prepared.report().path("runtimeExecution").asText());
        Path bundleFile = released.resolve("bundle.json");
        ReviewedRuleBundle.load(mapper, bundleFile, prepared.report().path("bundleSha256").asText());
        assertFinalFactsAndPreservedScience(f, json(bundleFile), json(baseline.resolve("candidate.json")));
        assertEquals(1, f.upstream.discoveryCalls); assertEquals(1, f.upstream.annotationCalls);
        receipt("background-" + background + "-observed-calls", mapper.createObjectNode()
                .put("upstreamDiscoveryCalls", f.upstream.discoveryCalls).put("upstreamAnnotationCalls", f.upstream.annotationCalls)
                .put("runtimeExecution", "NOT_RUN").put("callCountScope", "Instrumented synthetic builder providers only; no runtime counter claimed"));
        for (Path path : List.of(f.root, output, again, baseline, candidate, approvedInput, released))
            archive("background-" + background + "-" + path.getFileName(), path);
    }

    @Test void stableIdsChooseNonFirstSubmissionAfterDeclarationOrderChanges() throws Exception {
        var f = fixture("input", 0);
        var original = f.bridge(temporary.resolve("original-order")); assertBridged(original);
        JsonNode first = f.declaration.path("references").get(0).deepCopy();
        JsonNode second = f.declaration.path("references").get(1).deepCopy();
        f.declaration.putArray("references").add(second).add(first);
        JsonNode firstAssertion = f.referenceDraft.at("/references/0/assertions/0").deepCopy();
        JsonNode secondAssertion = f.referenceDraft.at("/references/0/assertions/1").deepCopy();
        ((ObjectNode) f.referenceDraft.at("/references/0")).putArray("assertions").add(secondAssertion).add(firstAssertion);
        f.refreshDrafts();
        Path output = temporary.resolve("bridged"); var result = f.bridge(output); assertBridged(result);
        assertNotEquals(original.report().path("bridgeId"), result.report().path("bridgeId"));
        JsonNode selected = component(output, json(result.manifestPath()), 0, "referenceAudit").at("/references/0");
        assertEquals(f.selectedAssertion.path("sourceAssertion"), selected.path("sourceAssertion"));
        assertEquals("SCV000000002.2", selected.path("scv").asText());
        assertEquals("Uncertain significance", selected.at("/sourceAssertion/classification").asText());
        assertEquals("PATHOGENIC", selected.path("classification").asText(), "External judgment is explicit and separate from source label");
        assertEquals(2, f.referenceDraft.at("/references/0/assertions").size());
        assertTrue(f.draftReport.path("gaps").toString().contains("SOURCE_CONFLICT_REQUIRES_REVIEW"));
        archive("reordered-input", f.root); archive("reordered-output", output); archive("original-order-output", temporary.resolve("original-order"));
    }

    @Test void changedDateOrJudgmentChangesCandidateAndRejectsBothKindsOfOldBinding() throws Exception {
        var original = fixture("original", 0);
        var initial = original.bridge(temporary.resolve("initial")); assertBridged(initial);
        Path firstCandidate = temporary.resolve("first-candidate");
        assertMissingApproval(new MissenseMaterialPreparation().prepare(initial.manifestPath(), initial.manifestSha256(), firstCandidate), firstCandidate);
        ObjectNode firstReport = json(firstCandidate.resolve("preparation-report.json"));
        ObjectNode oldApproval = approval(json(initial.manifestPath()), firstReport);

        var changed = new MissenseReferenceReviewBridgeFixture(temporary.resolve("changed-date"), 0, "2026-04-05");
        assertNotEquals(original.referenceDraft.path("draftId"), changed.referenceDraft.path("draftId"));
        ObjectNode currentBinding = changed.declaration.path("draftBinding").deepCopy();
        changed.declaration.set("draftBinding", original.declaration.path("draftBinding").deepCopy()); changed.save();
        Path staleDraft = temporary.resolve("stale-draft-binding"); var oldBinding = changed.bridge(staleDraft); assertBlocked(oldBinding, staleDraft);
        assertEquals("REVIEW_DRAFT_BINDING_MISMATCH", oldBinding.report().path("reasonCode").asText());
        changed.declaration.set("draftBinding", currentBinding); changed.save();
        var dateBridge = changed.bridge(temporary.resolve("date-bridge")); assertBridged(dateBridge);
        Path dateCandidate = temporary.resolve("date-candidate");
        assertMissingApproval(new MissenseMaterialPreparation().prepare(dateBridge.manifestPath(), dateBridge.manifestSha256(), dateCandidate), dateCandidate);
        assertNotEquals(firstReport.path("candidateSha256"), json(dateCandidate.resolve("preparation-report.json")).path("candidateSha256"));
        assertNotEquals(initial.report().path("bridgeId"), dateBridge.report().path("bridgeId"));
        JsonNode dateReference = component(Objects.requireNonNull(dateBridge.manifestPath()).getParent(), json(dateBridge.manifestPath()), 0, "referenceAudit").at("/references/0");
        assertEquals("2026-04-05", dateReference.path("evaluationDate").asText());
        assertOldApprovalRejected(dateBridge, oldApproval, "date");

        ((ObjectNode) original.declaration.at("/references/1/review")).put("reviewId", "TEST_ONLY_REVISED_EXTERNAL_JUDGMENT");
        original.save(); var judgmentBridge = original.bridge(temporary.resolve("judgment-bridge")); assertBridged(judgmentBridge);
        assertNotEquals(initial.report().path("bridgeId"), judgmentBridge.report().path("bridgeId"));
        assertOldApprovalRejected(judgmentBridge, oldApproval, "judgment");
        for (Path path : List.of(original.root, changed.root, Objects.requireNonNull(initial.manifestPath()).getParent(), firstCandidate, staleDraft,
                Objects.requireNonNull(dateBridge.manifestPath()).getParent(), dateCandidate, Objects.requireNonNull(judgmentBridge.manifestPath()).getParent()))
            archive("binding-" + path.getFileName(), path);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_REVIEW", "MISSING_RNA", "UNKNOWN_REFERENCE", "UNKNOWN_ASSERTION", "DUPLICATE_SELECTION",
            "SAME_SCV_DIFFERENT_ASSERTION", "UNKNOWN_PAIR", "DUPLICATE_PAIR", "WRONG_PAIR", "WRONG_BACKGROUND", "STALE_DRAFT", "FACT_OVERRIDE",
            "MISSING_DATE", "INVALID_DATE", "SOURCE_COLLISION", "UNREGISTERED_SOURCE", "FACT_AS_INDEPENDENCE", "UNPINNED_EVIDENCE", "RAW_PIN",
            "CANONICAL_PIN", "DUPLICATE_JSON", "TRAILING_JSON", "FILE_LIMIT", "TOTAL_LIMIT", "SOURCE_LIMIT", "REFERENCE_LIMIT", "PAIR_LIMIT",
            "SEED_APPROVAL", "UNKNOWN_SCHEMA", "UNKNOWN_DATA_CLASS", "PURPOSE", "HGVS_ALIAS", "UNKNOWN_ANNOTATION_FIELD", "MISSING_PAIR_REVIEW",
            "FACT_REFORMATTED_EVIDENCE_JSON", "FACT_REFORMATTED_EVIDENCE_BYTES"})
    void invalidNewBoundaryBlocksWithoutConsumableManifest(String fault) throws Exception {
        var f = new MissenseReferenceReviewBridgeFixture(temporary.resolve("input"), 0,
                fault.equals("MISSING_DATE") ? null : fault.equals("INVALID_DATE") ? "2026-02-30" : "2026-02-03", fault.equals("SAME_SCV_DIFFERENT_ASSERTION"));
        ObjectNode selected = (ObjectNode) f.declaration.at("/references/1");
        switch (fault) {
            case "MISSING_REVIEW" -> f.declaration.remove("referenceAudit");
            case "MISSING_RNA" -> f.declaration.remove("rnaReview");
            case "UNKNOWN_REFERENCE" -> selected.put("referenceDraftId", "UNKNOWN_REFERENCE");
            case "UNKNOWN_ASSERTION" -> selected.put("assertionDraftId", "UNKNOWN_ASSERTION");
            case "DUPLICATE_SELECTION" -> f.declaration.withArray("references").add(selected.deepCopy());
            case "SAME_SCV_DIFFERENT_ASSERTION" -> {
                JsonNode conflicting = f.selectedReference.at("/assertions/2");
                ObjectNode duplicate = selected.deepCopy().put("assertionDraftId", conflicting.path("assertionDraftId").asText());
                f.declaration.withArray("references").add(duplicate);
            }
            case "UNKNOWN_PAIR" -> ((ObjectNode) f.declaration.at("/rnaComparisons/0")).put("pairDraftId", "UNKNOWN_PAIR");
            case "DUPLICATE_PAIR" -> f.declaration.withArray("rnaComparisons").add(f.declaration.at("/rnaComparisons/0").deepCopy());
            case "WRONG_PAIR" -> { ((ObjectNode) f.rnaDraft.at("/pairs/0/targetAllele")).put("position", 999); f.refreshDrafts(); }
            case "WRONG_BACKGROUND" -> ((ObjectNode) f.declaration.path("background")).put("hgncId", "HGNC:9999");
            case "STALE_DRAFT" -> ((ObjectNode) f.declaration.path("draftBinding")).put("draftId", "OLD_DRAFT");
            case "FACT_OVERRIDE" -> ((ObjectNode) selected.path("review")).put("evaluationDate", "2026-09-26");
            case "SOURCE_COLLISION" -> ((ObjectNode) f.manifest.at("/factSources/referenceDraft")).put("sourceId", "TEST_ONLY");
            case "UNREGISTERED_SOURCE" -> ((ObjectNode) selected.path("review")).putArray("sourceIds").add("UNREGISTERED");
            case "FACT_AS_INDEPENDENCE" -> ((ObjectNode) selected.at("/review/independence")).putArray("sourceIds").add("FACT_referenceDraft");
            case "UNPINNED_EVIDENCE" -> ((ObjectNode) f.manifest.at("/reviewEvidence/0")).remove("rawSha256");
            case "RAW_PIN" -> ((ObjectNode) f.manifest.path("referenceDraft")).put("rawSha256", "f".repeat(64));
            case "CANONICAL_PIN" -> ((ObjectNode) f.manifest.path("referenceDraft")).put("canonicalSha256", "f".repeat(64));
            case "TOTAL_LIMIT" -> addLargeEvidence(f);
            case "SOURCE_LIMIT" -> {
                ArrayNode sources = f.manifest.withArray("reviewEvidence"); JsonNode original = sources.get(0);
                while (sources.size() < 65) {
                    ObjectNode next = original.deepCopy(); ((ObjectNode) next.path("sourceRecord")).put("sourceId", "TEST_ONLY_LIMIT_" + sources.size()); sources.add(next);
                }
            }
            case "REFERENCE_LIMIT" -> {
                ArrayNode references = (ArrayNode) f.referenceDraft.path("references");
                while (references.size() < 257) references.add(references.get(0).deepCopy()); f.refreshDrafts();
            }
            case "PAIR_LIMIT" -> {
                ArrayNode pairs = (ArrayNode) f.rnaDraft.path("pairs");
                while (pairs.size() < 1025) pairs.add(pairs.get(0).deepCopy()); f.refreshDrafts();
            }
            case "SEED_APPROVAL" -> {
                f.seed.manifest.putObject("approval").put("path", "old-approval.json"); f.seed.save();
                f.manifest.set("preparationSeed", f.descriptor(f.seed.manifestPath));
            }
            case "UNKNOWN_SCHEMA" -> f.manifest.put("schemaVersion", "unknown-bridge-version");
            case "UNKNOWN_DATA_CLASS" -> f.manifest.put("dataClass", "UNKNOWN");
            case "PURPOSE" -> f.manifest.put("purpose", "SCIENTIFIC_ADOPTION");
            case "HGVS_ALIAS" -> { ((ObjectNode) f.referenceDraft.at("/references/0/annotation")).put("hgvsc", "ENST99999999999.1:c.1A>G"); f.refreshDrafts(); }
            case "UNKNOWN_ANNOTATION_FIELD" -> { ((ObjectNode) f.referenceDraft.at("/references/0/annotation")).put("inventedUnknownAnnotationProperty", "TEST_ONLY"); f.refreshDrafts(); }
            case "MISSING_PAIR_REVIEW" -> ((ObjectNode) f.declaration.at("/rnaComparisons/0")).remove("review");
            case "FACT_REFORMATTED_EVIDENCE_JSON", "FACT_REFORMATTED_EVIDENCE_BYTES" -> {
                Path path = f.root.resolve("external-evidence.json"); mapper.writeValue(path.toFile(), f.referenceDraft);
                ObjectNode descriptor = f.descriptor(path).put("format", fault.endsWith("BYTES") ? "BYTES" : "JSON")
                        .put("sourceDigestKind", fault.endsWith("BYTES") ? "RAW_BYTES" : "CANONICAL_JSON");
                descriptor.set("sourceRecord", f.source("REVIEW_EVIDENCE", "TEST_ONLY@1"));
                f.manifest.putArray("reviewEvidence").add(descriptor);
                assertNotEquals(f.manifest.at("/referenceDraft/rawSha256").asText(), raw(path));
                assertEquals(f.manifest.at("/referenceDraft/canonicalSha256"), descriptor.path("canonicalSha256"));
            }
            default -> { }
        }
        f.save();
        if (fault.equals("DUPLICATE_JSON")) Files.writeString(f.manifestPath, "{\"schemaVersion\":1,\"schemaVersion\":2}");
        if (fault.equals("TRAILING_JSON")) Files.writeString(f.manifestPath, " {}", StandardOpenOption.APPEND);
        if (fault.equals("FILE_LIMIT")) Files.writeString(f.manifestPath, " ".repeat(4_000_001));
        Path output = temporary.resolve("blocked"); var result = f.bridge(output); assertBlocked(result, output);
        assertEquals(expectedReason(fault), result.report().path("reasonCode").asText(), result.report().toPrettyString());
        receipt("negative-" + fault, result.report()); archive("negative-output-" + fault, output);
        archive("negative-input-" + fault, f.root);
    }

    @Test void refusesExistingDirectoryAndActualFilesystemFailure() throws Exception {
        var f = fixture("input", 0); Path existing = temporary.resolve("existing"); Files.createDirectory(existing);
        Path sentinel = existing.resolve("user.txt"); Files.writeString(sentinel, "USER_CONTENT");
        var exists = f.bridge(existing); assertBlocked(exists, existing);
        assertEquals("OUTPUT_EXISTS_REFUSE_OVERWRITE", exists.report().path("reasonCode").asText()); assertEquals("USER_CONTENT", Files.readString(sentinel));
        Path parentFile = temporary.resolve("parent-file"); Files.writeString(parentFile, "USER_CONTENT");
        var failed = f.bridge(parentFile.resolve("output")); assertBlocked(failed, parentFile.resolve("output"));
        assertEquals("INPUT_OUTPUT_IO_FAILURE", failed.report().path("reasonCode").asText());
        assertEquals("USER_CONTENT", Files.readString(parentFile));
        receipt("filesystem-failure", failed.report()); archive("existing-directory", existing);
    }

    @Test void oldFixedScientificPinIsRejectedWithoutRewritingIt() throws Exception {
        var f = fixture("input", 0);
        f.seed.replace(0, "policy", policy -> policy.put("materialsSha256", "a".repeat(64)));
        f.manifest.set("preparationSeed", f.descriptor(f.seed.manifestPath)); f.save();
        Path output = temporary.resolve("bridge"); var bridge = f.bridge(output); assertBlocked(bridge, output);
        assertEquals("DECLARED_PIN_MISMATCH_materialsSha256", bridge.report().path("reasonCode").asText());
        assertEquals("a".repeat(64), component(f.seed.root, f.seed.manifest, 0, "policy").path("materialsSha256").asText());
        archive("fixed-pin-input", f.root); archive("fixed-pin-bridge", output);
    }

    @Test void existingPinnedSeedSourceExplicitlyUsedByReviewerIsAddedOnlyToSelectedPolicy() throws Exception {
        var f = fixture("input", 0);
        ObjectNode sourceDocument = mapper.createObjectNode().put("purpose", "TEST_ONLY").put("content", "Existing independently delivered review context");
        ObjectNode source = f.seed.file("existing-review-context.json", sourceDocument);
        ObjectNode record = f.source("EXISTING_REVIEW_CONTEXT", "TEST_ONLY@1").put("sha256", source.path("canonicalSha256").asText());
        source.put("format", "JSON").put("sourceDigestKind", "CANONICAL_JSON").set("sourceRecord", record);
        f.seed.manifest.withArray("sources").add(source); f.seed.save();
        f.manifest.set("preparationSeed", f.descriptor(f.seed.manifestPath));
        ((ObjectNode) f.declaration.at("/references/1/review")).withArray("sourceIds").add("EXISTING_REVIEW_CONTEXT"); f.save();
        Path output = temporary.resolve("bridge"); var bridge = f.bridge(output); assertBridged(bridge);
        JsonNode targetSources = component(output, json(bridge.manifestPath()), 0, "policy").path("sources");
        boolean found = false; for (JsonNode entry : targetSources) if (entry.equals(record)) found = true;
        assertTrue(found); assertEquals(component(f.seed.root, f.seed.manifest, 1, "policy"), component(output, json(bridge.manifestPath()), 1, "policy"));
        Path candidate = temporary.resolve("candidate");
        assertMissingApproval(new MissenseMaterialPreparation().prepare(bridge.manifestPath(), bridge.manifestSha256(), candidate), candidate);
        Path approved = withExternalApproval(output, candidate, temporary.resolve("approved"));
        Path release = temporary.resolve("released"); var prepared = new MissenseMaterialPreparation().prepare(approved, raw(approved), release);
        assertTrue(prepared.ready(), prepared.report().toPrettyString());
        for (Path path : List.of(f.root, output, candidate, approved.getParent(), release)) archive("existing-source-" + path.getFileName(), path);
    }

    @ParameterizedTest @ValueSource(strings = {"SINGLE_FILE", "PREPARER_TOTAL"})
    void boundedInputsCannotPublishOversizedNewScientificFilesOrUnconsumableGraph(String limit) throws Exception {
        int padding = limit.equals("SINGLE_FILE") ? 2_050_000 : 1_900_000;
        var f = new MissenseReferenceReviewBridgeFixture(temporary.resolve("input"), 0, "2026-02-03", false, padding);
        ((ObjectNode) f.declaration.at("/references/1/review/independence")).putArray("criteria").add("TEST_ONLY_" + "S".repeat(padding));
        if (limit.equals("PREPARER_TOTAL")) addLargeEvidence(f, 7);
        f.save(); Path output = temporary.resolve("blocked"); var result = f.bridge(output); assertBlocked(result, output);
        assertEquals(limit.equals("SINGLE_FILE") ? "OUTPUT_SIZE_LIMIT" : "OUTPUT_PREPARER_SIZE_LIMIT", result.report().path("reasonCode").asText(), result.report().toPrettyString());
        assertTrue(result.report().path("actualInputBytes").asLong() <= 32_000_000);
        receipt("output-limit-" + limit, result.report()); archive("output-limit-input-" + limit, f.root); archive("output-limit-result-" + limit, output);
    }

    @Test void actualBridgeAndOriginalPrepareCliConsumeGoodAndExpiredApprovalFiles() throws Exception {
        var f = fixture("input", 1); Path bridged = temporary.resolve("cli-bridge");
        assertEquals(0, cli(MissenseReferenceReviewBridge.class, f.manifestPath, f.pin(), bridged));
        Path badBridge = temporary.resolve("cli-bad-bridge");
        assertEquals(2, cli(MissenseReferenceReviewBridge.class, f.manifestPath, "f".repeat(64), badBridge));
        assertFalse(Files.exists(badBridge.resolve("manifest.json")));
        Path manifest = bridged.resolve("manifest.json"), candidate = temporary.resolve("cli-candidate");
        assertEquals(2, cli(MissenseMaterialPreparation.class, manifest, raw(manifest), candidate));
        assertEquals("EXTERNAL_APPROVAL_MISSING", json(candidate.resolve("preparation-report.json")).path("reasonCode").asText());
        Path approved = withExternalApproval(bridged, candidate, temporary.resolve("cli-approved"));
        Path released = temporary.resolve("cli-release"); assertEquals(0, cli(MissenseMaterialPreparation.class, approved, raw(approved), released));
        assertTrue(Files.exists(released.resolve("bundle.json")));
        ((ObjectNode) f.declaration.at("/references/1/review")).put("reviewId", "TEST_ONLY_CHANGED_AFTER_CANDIDATE_REVIEW"); f.save();
        Path updated = temporary.resolve("cli-updated"); assertEquals(0, cli(MissenseReferenceReviewBridge.class, f.manifestPath, f.pin(), updated));
        Path staleInput = temporary.resolve("cli-stale-input"); copyTree(updated, staleInput);
        addApproval(staleInput, json(approved.getParent().resolve("external-final-approval.json")));
        Path staleOutput = temporary.resolve("cli-stale-output"); Path staleManifest = staleInput.resolve("manifest.json");
        assertEquals(2, cli(MissenseMaterialPreparation.class, staleManifest, raw(staleManifest), staleOutput));
        assertEquals("EXTERNAL_APPROVAL_BINDING_MISMATCH", json(staleOutput.resolve("preparation-report.json")).path("reasonCode").asText());
        assertFalse(Files.exists(staleOutput.resolve("bundle.json")));
        for (Path path : List.of(f.root, bridged, badBridge, candidate, approved.getParent(), released, updated, staleInput, staleOutput))
            archive("process-" + path.getFileName(), path);
    }

    private MissenseReferenceReviewBridgeFixture fixture(String name, int background) throws Exception {
        return new MissenseReferenceReviewBridgeFixture(temporary.resolve(name), background);
    }
    private ObjectNode json(Path path) throws Exception { return (ObjectNode) mapper.readTree(path.toFile()); }
    private String raw(Path path) throws Exception { return Pm4MaterialPreparation.sha(Files.readAllBytes(path)); }
    private Path componentFile(Path root, JsonNode manifest, int background, String role) {
        return root.resolve(manifest.path("backgrounds").get(background).path(role).path("path").asText());
    }
    private ObjectNode component(Path root, JsonNode manifest, int background, String role) throws Exception {
        return json(componentFile(root, manifest, background, role));
    }
    private void assertBridged(MissenseReferenceReviewBridge.Result result) {
        assertTrue(result.bridged(), result.report().toPrettyString()); assertNotNull(result.manifestPath()); assertNotNull(result.manifestSha256());
        assertEquals("BRIDGED", result.report().path("bridgeStatus").asText());
        assertEquals("NOT_RUN", result.report().path("runtimeExecution").asText()); assertFalse(result.report().path("scientificAdoption").asBoolean(true));
    }
    private void assertBlocked(MissenseReferenceReviewBridge.Result result, Path output) {
        assertFalse(result.bridged(), result.report().toPrettyString()); assertNull(result.manifestPath()); assertNull(result.manifestSha256());
        assertEquals("BLOCKED", result.report().path("bridgeStatus").asText()); assertFalse(result.report().path("reasonCode").asText().isBlank());
        assertFalse(Files.exists(output.resolve("manifest.json"))); assertFalse(Files.exists(output.resolve("bundle.json")));
    }
    private void assertMissingApproval(MissenseMaterialPreparation.Result result, Path output) {
        assertFalse(result.ready(), result.report().toPrettyString());
        assertEquals("EXTERNAL_APPROVAL_MISSING", result.report().path("reasonCode").asText(), result.report().toPrettyString());
        assertEquals("PASS", result.report().path("staticEngineeringValidation").asText());
        assertTrue(Files.isRegularFile(output.resolve("candidate.json"))); assertFalse(Files.exists(output.resolve("bundle.json")));
    }
    private void assertOutputFileGraph(MissenseReferenceReviewBridgeFixture f, Path root, ObjectNode manifest) throws Exception {
        assertEquals(f.seed.manifest.path("criterion"), manifest.path("criterion"));
        for (String role : List.of("referenceDraft", "rnaDraft", "draftReport", "review")) {
            JsonNode descriptor = null;
            for (JsonNode source : manifest.path("sources"))
                if (("FACT_" + role).equals(source.at("/sourceRecord/sourceId").asText())) descriptor = source;
            assertNotNull(descriptor, "All fixed draft facts, report gaps and external declaration remain actual source files");
            assertArrayEquals(Files.readAllBytes(f.root.resolve(f.manifest.path(role).path("path").asText())),
                    Files.readAllBytes(root.resolve(descriptor.path("path").asText())));
        }
        for (String role : List.of("bundleHeader", "catalog", "comparatorProfile"))
            assertEquals(json(f.seed.root.resolve(f.seed.manifest.path(role).path("path").asText())), json(root.resolve(manifest.path(role).path("path").asText())));
        for (int i = 0; i < 2; i++) {
            JsonNode original = f.seed.manifest.path("backgrounds").get(i), updated = manifest.path("backgrounds").get(i);
            assertEquals(original.path("background"), updated.path("background"));
            Iterator<String> roles = original.fieldNames();
            while (roles.hasNext()) {
                String role = roles.next(); if (role.equals("background")) continue;
                JsonNode before = json(f.seed.root.resolve(original.path(role).path("path").asText()));
                JsonNode after = json(root.resolve(updated.path(role).path("path").asText()));
                if (i != f.backgroundIndex || !List.of("referenceAudit", "rnaReview", "policy").contains(role)) assertEquals(before, after, role);
                else if (role.equals("policy")) {
                    ObjectNode scienceBefore = before.deepCopy(), scienceAfter = after.deepCopy();
                    scienceBefore.remove("sources"); scienceAfter.remove("sources"); assertEquals(scienceBefore, scienceAfter);
                    assertTrue(after.path("sources").size() > before.path("sources").size());
                }
            }
        }
        assertDescriptorDigests(root, manifest);
    }
    private void assertDescriptorDigests(Path root, JsonNode node) throws Exception {
        if (node.isObject() && node.has("path") && node.has("rawSha256")) {
            Path file = root.resolve(node.path("path").asText()); assertTrue(Files.isRegularFile(file));
            assertFalse(Path.of(node.path("path").asText()).isAbsolute()); assertEquals(raw(file), node.path("rawSha256").asText());
            if (node.has("canonicalSha256")) assertEquals(adapter.canonicalSha256(json(file)), node.path("canonicalSha256").asText());
        } else for (JsonNode child : node) assertDescriptorDigests(root, child);
    }
    private void assertFinalFactsAndPreservedScience(MissenseReferenceReviewBridgeFixture f, JsonNode bundle, JsonNode baseline) throws Exception {
        JsonNode target = null;
        for (JsonNode material : bundle.at("/materials/missenseMaterials"))
            if (material.path("materialId").equals(f.seed.fixture.materials.get(f.backgroundIndex).path("materialId"))) target = material;
        assertNotNull(target);
        JsonNode reference = target.at("/referenceAudit/references/0"), assertion = f.selectedAssertion.path("sourceAssertion");
        assertEquals(f.selectedReference.path("referenceDraftId"), reference.path("referenceId"));
        assertEquals(f.selectedReference.path("referenceDraftId"), reference.path("sourceReferenceDraftId"));
        assertEquals(f.selectedAssertion.path("assertionDraftId"), reference.path("sourceAssertionDraftId"));
        assertEquals(f.selectedReference.path("allele"), reference.path("allele"));
        assertEquals(f.selectedReference.path("variationId"), reference.path("variationId"));
        assertEquals(f.selectedReference.path("annotation"), reference.path("annotation"));
        assertEquals(mapper.readTree(f.mapper.writeValueAsBytes(f.upstream.actualReference)), reference.path("annotation"));
        assertTrue(reference.path("annotation").has("hgvsc")); assertTrue(reference.path("annotation").has("hgvsp"));
        assertEquals(assertion, reference.path("sourceAssertion")); assertEquals(assertion.path("scv"), reference.path("scv"));
        assertEquals(assertion.path("dateLastEvaluated"), reference.path("evaluationDate"));
        String date = reference.path("evaluationDate").asText(); assertFalse(date.isBlank()); assertEquals(date, LocalDate.parse(date).toString());
        JsonNode pair = target.at("/rnaReview/comparisons/0");
        assertEquals(f.selectedPair.path("pairDraftId"), pair.path("sourcePairDraftId"));
        for (String field : List.of("targetAllele", "referenceAllele", "transcript")) assertEquals(f.selectedPair.path(field), pair.path(field));
        assertEquals("UNKNOWN", target.at("/rnaReview/rnaEffect").asText()); assertFalse(target.at("/rnaReview/normalRnaClaimed").asBoolean(true));
        assertEquals(f.declaration.path("rnaReview").path("knownConcerns"), target.at("/rnaReview/knownConcerns"));
        for (JsonNode material : bundle.at("/materials/missenseMaterials")) {
            JsonNode prior = null; for (JsonNode old : baseline.path("missenseMaterials")) if (old.path("materialId").equals(material.path("materialId"))) prior = old;
            assertNotNull(prior);
            if (!material.equals(target)) assertEquals(prior, material);
            else {
                ObjectNode before = prior.deepCopy(), after = material.deepCopy(); before.remove(List.of("referenceAudit", "rnaReview")); after.remove(List.of("referenceAudit", "rnaReview")); assertEquals(before, after);
            }
        }
        for (int i = 0; i < bundle.path("policies").size(); i++) {
            ObjectNode old = baseline.path("policies").get(i).deepCopy(), current = bundle.path("policies").get(i).deepCopy();
            if (i == f.backgroundIndex) { old.remove(List.of("sources", "materialsSha256")); current.remove(List.of("sources", "materialsSha256")); }
            assertEquals(old, current);
        }
        for (String field : List.of("schemaVersion", "bundleId", "bundleVersion", "review", "activation")) assertEquals(baseline.path("bundleHeader").path(field), bundle.path(field));
    }
    private ObjectNode approval(JsonNode manifest, JsonNode report) {
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "missense-preparation-approval-v1")
                .put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY_SEPARATE_FINAL_CANDIDATE_REVIEWER")
                .put("dataClass", "SYNTHETIC").put("criterion", manifest.path("criterion").asText()).put("candidateSha256", report.path("candidateSha256").asText());
        result.set("backgrounds", report.path("backgrounds").deepCopy()); return result;
    }
    private Path withExternalApproval(Path bridged, Path candidate, Path destination) throws Exception {
        copyTree(bridged, destination); return addApproval(destination, approval(json(destination.resolve("manifest.json")), json(candidate.resolve("preparation-report.json"))));
    }
    /** Second stage only adds a final approval file and its descriptor; no scientific file is edited. */
    private Path addApproval(Path root, ObjectNode approval) throws Exception {
        Path file = root.resolve("external-final-approval.json"); mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), approval);
        Path manifestPath = root.resolve("manifest.json"); ObjectNode manifest = json(manifestPath);
        manifest.putObject("approval").put("path", file.getFileName().toString()).put("rawSha256", raw(file)).put("canonicalSha256", adapter.canonicalSha256(approval));
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest); return manifestPath;
    }
    private void assertOldApprovalRejected(MissenseReferenceReviewBridge.Result bridged, ObjectNode approval, String name) throws Exception {
        Path input = temporary.resolve(name + "-old-approval"); copyTree(Objects.requireNonNull(bridged.manifestPath()).getParent(), input); Path manifest = addApproval(input, approval);
        Path output = temporary.resolve(name + "-approval-blocked");
        var result = new MissenseMaterialPreparation().prepare(manifest, raw(manifest), output);
        assertFalse(result.ready()); assertEquals("EXTERNAL_APPROVAL_BINDING_MISMATCH", result.report().path("reasonCode").asText());
        assertFalse(Files.exists(output.resolve("bundle.json"))); archive("binding-" + input.getFileName(), input); archive("binding-" + output.getFileName(), output);
    }
    private void addLargeEvidence(MissenseReferenceReviewBridgeFixture f) throws Exception { addLargeEvidence(f, 9); }
    private void addLargeEvidence(MissenseReferenceReviewBridgeFixture f, int count) throws Exception {
        byte[] bytes = new byte[3_700_000]; ArrayNode sources = f.manifest.withArray("reviewEvidence");
        for (int i = 0; i < count; i++) {
            String name = "large-evidence-" + i + ".bin"; Path file = f.root.resolve(name); Files.write(file, bytes);
            ObjectNode descriptor = sources.addObject().put("path", name).put("rawSha256", raw(file)).put("format", "BYTES").put("sourceDigestKind", "RAW_BYTES");
            descriptor.set("sourceRecord", f.source("LARGE_TEST_ONLY_" + i, "1"));
        }
    }
    private int cli(Class<?> mainClass, Path manifest, String pin, Path output) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp", System.getProperty("java.class.path"),
                mainClass.getName(), "--manifest", manifest.toString(), "--manifest-sha256", pin, "--output", output.toString());
        Path log = temporary.resolve(output.getFileName() + ".log"); Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertTrue(finished, "Actual CLI must finish within its bounded timeout");
        int exit = process.exitValue(); receipt(output.getFileName() + "-command", mapper.valueToTree(Map.of("command", command, "exitCode", exit, "log", Files.readString(log)))); return exit;
    }
    private void archive(String name, Path from) throws Exception {
        String root = System.getProperty("varvis.missense.bridge.artifacts"); if (root != null && Files.exists(from)) copyTree(from, Path.of(root).resolve(name));
    }
    private void copyTree(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path path : files.toList()) {
                Path destination = to.resolve(from.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    private void receipt(String name, JsonNode value) throws Exception {
        String root = System.getProperty("varvis.missense.bridge.artifacts"); if (root == null) return;
        Files.createDirectories(Path.of(root)); mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(root, name + ".json").toFile(), value);
    }

    private String expectedReason(String fault) {
        return switch (fault) {
            case "MISSING_REVIEW" -> "REFERENCE_REVIEW_REQUIRED";
            case "MISSING_RNA" -> "RNA_REVIEW_REQUIRED";
            case "UNKNOWN_REFERENCE" -> "REFERENCE_SELECTION_UNKNOWN";
            case "UNKNOWN_ASSERTION" -> "ASSERTION_SELECTION_UNKNOWN";
            case "DUPLICATE_SELECTION" -> "REFERENCE_SELECTION_DUPLICATE";
            case "SAME_SCV_DIFFERENT_ASSERTION" -> "REFERENCE_MATERIAL_IDENTITY_DUPLICATE";
            case "UNKNOWN_PAIR" -> "RNA_SELECTION_UNKNOWN";
            case "DUPLICATE_PAIR" -> "RNA_SELECTION_DUPLICATE";
            case "WRONG_PAIR" -> "RNA_DRAFT_IDENTITY_INVALID";
            case "WRONG_BACKGROUND" -> "REVIEW_BACKGROUND_MISMATCH";
            case "STALE_DRAFT" -> "REVIEW_DRAFT_BINDING_MISMATCH";
            case "FACT_OVERRIDE" -> "REDUNDANT_SOURCE_FACT_CONFLICT";
            case "MISSING_DATE" -> "REQUIRED_FIELD_dateLastEvaluated";
            case "INVALID_DATE" -> "SOURCE_EVALUATION_DATE_INVALID";
            case "SOURCE_COLLISION" -> "SOURCE_ID_CONFLICT";
            case "UNREGISTERED_SOURCE" -> "REVIEW_SOURCE_ID_UNBOUND_OR_DUPLICATE";
            case "FACT_AS_INDEPENDENCE" -> "SCIENTIFIC_REVIEW_EVIDENCE_SOURCE_REQUIRED";
            case "UNPINNED_EVIDENCE" -> "REQUIRED_FIELD_rawSha256";
            case "RAW_PIN" -> "INPUT_RAW_SHA_MISMATCH";
            case "CANONICAL_PIN" -> "INPUT_CANONICAL_SHA_MISMATCH";
            case "DUPLICATE_JSON", "TRAILING_JSON", "UNKNOWN_ANNOTATION_FIELD" -> "INPUT_JSON_INVALID_OR_DUPLICATE_KEY";
            case "FILE_LIMIT", "TOTAL_LIMIT" -> "INPUT_SIZE_LIMIT";
            case "SOURCE_LIMIT" -> "REVIEW_EVIDENCE_REQUIRED";
            case "REFERENCE_LIMIT" -> "REFERENCE_DRAFT_LIMIT_OR_ARRAY_INVALID";
            case "PAIR_LIMIT" -> "RNA_DRAFT_LIMIT_OR_ARRAY_INVALID";
            case "SEED_APPROVAL" -> "SEED_APPROVAL_FORBIDDEN";
            case "UNKNOWN_SCHEMA" -> "MANIFEST_VERSION_UNSUPPORTED";
            case "UNKNOWN_DATA_CLASS" -> "DATA_CLASS_UNSUPPORTED";
            case "PURPOSE" -> "SYNTHETIC_TEST_ONLY_REQUIRED";
            case "HGVS_ALIAS" -> "ANNOTATION_HGVS_ALIAS_MISMATCH";
            case "MISSING_PAIR_REVIEW" -> "RNA_PAIR_REVIEW_REQUIRED";
            case "FACT_REFORMATTED_EVIDENCE_JSON", "FACT_REFORMATTED_EVIDENCE_BYTES" -> "FACT_SOURCE_CANNOT_BE_REVIEW_EVIDENCE";
            default -> throw new AssertionError("No expected reason for " + fault);
        };
    }
}
