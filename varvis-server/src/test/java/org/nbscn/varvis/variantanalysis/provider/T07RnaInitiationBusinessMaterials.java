package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.config.Pm4Bp3ComplexPublicFixture;
import org.springframework.mock.env.MockEnvironment;

/** Only invented non-rescue initiation baselines. No functional-product source or evaluator output is created. */
public final class T07RnaInitiationBusinessMaterials {
    public static final String REFERENCE = "CCATGAATGCCATGTTTGAATAAGGGTGA";
    public static final String PRODUCT = "CCAATGCCATGTTTGAATAAGGGTGA";
    public enum Baseline { SUPPORTING, MODERATE, PARENT_CAP, POSITION_SCOPE, UPSTREAM_INCOMPLETE, WRONG_CONTEXT, SOURCE_OUTSIDE_REVIEW }
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter hashes;
    private final Path root;
    private final MockEnvironment env;
    private final Pvs1NmdBusinessFixture business;
    private final List<ObjectNode> originals = new ArrayList<>(), bindings = new ArrayList<>();
    private final List<Map<String, byte[]>> policyOriginals = new ArrayList<>();

    public T07RnaInitiationBusinessMaterials(ObjectMapper mapper, Path root, Pm4Bp3ComplexPublicFixture transport,
            JsonNode choices) throws Exception {
        this.mapper = mapper; this.hashes = new RuntimeEvidenceV2Adapter(mapper);
        this.root = Files.createDirectories(root); this.env = transport.env();
        business = new Pvs1NmdBusinessFixture(mapper, false); business.models.clear(); business.policies.clear();
        for (int i = 0; i < 2; i++) {
            var model = transport.model(i == 0 ? "HBB" : "GJB2"); var choice = choices.get(i);
            var policy = new Pvs1SpliceConsequenceTest().data(1, false, false).input().policy.deepCopy();
            policy.put("policyId", "SYNTHETIC_T07_INITIATION_" + i);
            var scope = policy.withObject("scope");
            scope.set("hgncId", model.path("hgncId")); scope.set("assembly", model.path("assembly"));
            scope.set("mondoId", choice.path("syntheticHypothesisMondoId")); scope.set("moi", choice.path("syntheticHypothesisMoi"));
            String tx = model.path("transcript").asText(); int dot = tx.lastIndexOf('.');
            scope.putArray("transcripts").addObject().put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)));
            var nmd = policy.withObject("nmdScope").put("minimumCdsPosition", 4).put("minimumDistanceToLastJunction", 51)
                    .put("longExonScope", "REVIEWED_WITHOUT_LENGTH_LIMIT");
            nmd.putArray("relevantExonOrdinals").add(1).add(2).add(3).add(4); nmd.putArray("engineeringSupportedExonOrdinals").add(1).add(2).add(3);
            nmd.putObject("utrJunctionApplicability").put("status", "REVIEWED_FOR_3_PRIME_UTR_JUNCTION")
                    .put("basis", "Invented engineering UTR applicability").putArray("sourceIds").add("synthetic-source");
            var escape = Pvs1NmdEscapeTest.scope(policy, 4);
            Pvs1NmdEscapeTest.critical(escape, 2, 6, 1, "STRONG");
            policy.put("schemaVersion", Pvs1SpliceConsequence.SCHEMA);
            var sourceBytes = new LinkedHashMap<String, byte[]>();
            for (var value : policy.path("scientificSources")) {
                var source = (ObjectNode) value; String id = source.path("sourceId").asText();
                byte[] raw = ("SYNTHETIC TEST ONLY non-rescue initiation rule source: " + id).getBytes(StandardCharsets.UTF_8);
                source.put("sha256", CodingTranscriptMaterialSupply.sha(raw)); sourceBytes.put(id, raw);
            }
            policy.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                    .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", hashes.canonicalSha256(model));
            policyOriginals.add(sourceBytes); business.models.add(model); business.policies.add(policy);
            var original = mapper.createObjectNode().put("schemaVersion", Pvs1SourceQualification.FULL_RNA_ORIGINAL_SCHEMA)
                    .put("reviewId", "SYNTHETIC_T07_START_" + i).put("basis", "Invented complete RNA; no actual human experiment");
            var nativeScope = original.putObject("scope");
            for (String field : List.of("assembly", "contig", "hgncId", "geneSymbol", "transcript")) nativeScope.set(field, model.path(field));
            nativeScope.put("modelSha256", hashes.canonicalSha256(model)).put("referenceRnaSha256", CodingTranscriptMaterialSupply.sha(REFERENCE));
            var allele = nativeScope.putObject("allele").put("assembly", "GRCh38").put("contig", model.path("contig").asText())
                    .put("position", 1008).put("reference", "A").put("alternate", "G");
            original.putObject("use").put("purpose", "PROJECT_RESEARCH").put("dataClass", "SYNTHETIC")
                    .put("clinicalAcceptance", false).put("createsExpertReview", false);
            original.putObject("assay").put("evidenceKind", "OBSERVED_RNA").put("alleleAttribution", "ASSESSED")
                    .put("assayQuality", "ASSESSED").put("tissueRelevance", "ASSESSED")
                    .put("residualNormalTranscript", "NOT_DETECTED_IN_ASSESSED_SCOPE").put("outcomeMultiplicity", "SINGLE_SUPPORTED_OUTCOME")
                    .put("basis", "Entirely invented engineering RNA assay; no real functional qualification");
            original.putObject("physicalAllele").put("status", "SINGLE_PHYSICAL_ALLELE").put("alleleSha256", hashes.canonicalSha256(allele))
                    .put("basis", "Invented one-physical-allele declaration");
            original.putObject("events").put("coordinateSystem", CodingTranscriptEdits.RNA_COORDINATES)
                    .put("sequenceSemantics", "REFERENCE_RNA_EDIT_DELTAS").putArray("rnaEdits").addObject()
                    .put("eventId", "SYNTHETIC_REMOVE_NATIVE_ATG").put("start0", 2).put("end0", 5).put("expectedReference", "ATG").put("insertedSequence", "");
            original.putObject("sequence").put("completeAlteredRnaWitness", PRODUCT).put("alteredRnaSha256", CodingTranscriptMaterialSupply.sha(PRODUCT));
            original.putObject("topology").put("completeness", "COMPLETE").put("basis", "Invented complete four-exon RNA product")
                    .putArray("junctions0").add(5).add(14).add(22);
            originals.add(original);
            var binding = mapper.createObjectNode().put("relativePath", "rna-" + i + ".json");
            binding.putObject("sourceRecord").put("sourceId", "SYNTHETIC_T07_START_" + i).put("version", "TEST_ONLY")
                    .put("url", "https://example.test/SYNTHETIC/t07/start-" + i).put("sha256", "0".repeat(64)); bindings.add(binding);
        }
        writeKnowledge(); writeBundle();
    }

    public void configureCompanions(Path python) throws Exception {
        Path denovo = root.resolve("denovo"); new DeNovoBatchFixture(denovo); String prefix = Pm4Bp3ComplexPublicFixture.PREFIX;
        env.withProperty(prefix + "local-batch-denovo-enabled", "true").withProperty(prefix + "local-batch-denovo-use", "SYNTHETIC_VALIDATION")
                .withProperty(prefix + "local-batch-denovo-material-file", Objects.requireNonNull(denovo.resolve("manifest.json").toString()))
                .withProperty(prefix + "local-batch-denovo-material-sha256", Objects.requireNonNull(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(denovo.resolve("manifest.json")))))
                .withProperty(prefix + "local-batch-pm3-literature-enabled", "true").withProperty(prefix + "local-batch-pp1-literature-enabled", "true");
        Path workflow = UnifiedEighteenCompanionFixtures.prepareEmptyLiteratureWorkflow(mapper, root.resolve("literature"), Path.of("../scripts/denovo"), python);
        env.withProperty("varvis.literature-workflow-config", Objects.requireNonNull(workflow.toString()));
        for (String code : List.of("pm1", "pp2", "bp1")) {
            Path knowledge = Path.of("D:/LSD/Project/Code/local/runtime/bp7-evidence-workbench/materials/fourteen-v1").resolve(code + "-knowledge.json");
            env.withProperty(prefix + "local-batch-" + code + "-enabled", "true"); pin(code + "-published-knowledge", knowledge);
        }
    }

    /** Only mechanical/context identities come from a real neutral normal submission; none of its criterion outputs are an oracle. */
    public void baseline(JsonNode neutralBatch, Baseline baseline) throws Exception {
        for (int i = 0; i < 2; i++) {
            var model = business.models.get(i); var policy = business.policies.get(i); var original = originals.get(i);
            String tx = model.path("transcript").asText(); JsonNode material = null, context = null;
            for (var raw : neutralBatch.at("/modules/pvs1/results")) {
                var ctx = raw.path("context"); String selected = ctx.at("/transcript/accession").asText() + "." + ctx.at("/transcript/version").asText();
                if (tx.equals(selected)) { if (context != null) throw new IllegalStateException("AMBIGUOUS_ACTUAL_CONTEXT"); context = ctx; }
            }
            for (var item : neutralBatch.at("/fullRnaArchive/materialsBySha256")) {
                var raw = neutralBatch.at("/fullRnaArchive/originalsByRawSha256").path(item.path("originalReference").asText());
                var source = mapper.readTree(raw.path("originalUtf8").asText());
                if (tx.equals(source.at("/scope/transcript").asText())) { if (material != null) throw new IllegalStateException("AMBIGUOUS_ACTUAL_MATERIAL"); material = item; }
            }
            if (material == null || context == null) throw new IllegalStateException("ACTUAL_NEUTRAL_CONTEXT_AND_MATERIAL_REQUIRED");
            String effect = material.at("/consequenceFacts/mechanicalEffectSha256").asText();
            if (!effect.matches("[0-9a-f]{64}")) throw new IllegalStateException("ACTUAL_MECHANICAL_IDENTITY_REQUIRED");
            if (!CodingTranscriptMaterialSupply.sha(PRODUCT).equals(material.at("/consequenceFacts/binding/alteredRnaSha256").asText())
                    || !CodingTranscriptMaterialSupply.sha(REFERENCE).equals(material.at("/consequenceFacts/binding/referenceRnaSha256").asText()))
                throw new IllegalStateException("FIXED_RNA_ORACLE_DOES_NOT_MATCH_ACTUAL_PREPARATION");
            policy.put("appliedStrength", baseline == Baseline.PARENT_CAP ? "SUPPORTING" : "VERY_STRONG");
            var nmdScope = policy.withObject("nmdScope");
            nmdScope.remove("positionConditions");
            if (baseline == Baseline.POSITION_SCOPE) {
                nmdScope.putArray("positionConditions").addObject().put("conditionId", "synthetic-event-range")
                        .put("coordinate", "REFERENCE_EVENT_SPAN").put("minimumInclusive", 1).put("maximumExclusive", i == 0 ? 4 : 2)
                        .put("basis", "Invented bounds for the same reference CDS 1–3 event").putArray("sourceIds").add("synthetic-source");
            }
            var sourceArray = policy.withArray("scientificSources");
            for (int n = sourceArray.size() - 1; n >= 0; n--) if (sourceArray.get(n).path("sourceId").asText().startsWith("init-")) sourceArray.remove(n);
            var initiation = policy.withObject("spliceScope").putObject("initiationScope")
                    .put("status", "REVIEWED_FOR_FULL_RNA_INITIATION").put("method", Pvs1RnaInitiation.ALGORITHM)
                    .put("methodVersion", Pvs1RnaInitiation.VERSION).put("reviewId", "SYNTHETIC_T07_NON_RESCUE_SCOPE")
                    .put("basis", "Invented non-rescue engineering scope; not clinical approval").put("maximumStrength", "MODERATE");
            initiation.putArray("sourceIds").add("synthetic-source");
            initiation.putArray("assessedTranscripts").add(tx).add("ENST000002.1"); initiation.putArray("sourceBindings");
            policyOriginals.get(i).keySet().removeIf(key -> key.startsWith("init-"));
            var assessment = original.putObject("pvs1Assessment").put("method", "SOURCE_BOUND_FULL_RNA_PVS1").put("mechanicalEffectSha256", effect);
            assessment.putObject("parentReview").put("status", "REVIEWED_FOR_PVS1_SPLICE").put("reviewId", "SYNTHETIC_NON_RESCUE_PARENT")
                    .put("basis", "Invented complete non-rescue baseline; no clinical acceptance").put("maximumStrength", "VERY_STRONG")
                    .put("functionalRescue", "EXCLUDED_IN_REVIEWED_SCOPE").put("outcomeMultiplicity", "SINGLE_SUPPORTED_OUTCOME")
                    .put("residualNormalTranscript", "NOT_DETECTED_IN_ASSESSED_SCOPE").putArray("sourceIds").add("synthetic-source").add("SYNTHETIC_T07_START_" + i);
            var review = assessment.putObject("initiationReview").put("schemaVersion", "pvs1-rna-initiation-review-v1")
                    .put("reviewId", "SYNTHETIC_NON_RESCUE_INITIATION").put("basis", "Invented finite source-reviewed putative start, not observed translation");
            String primary = "SYNTHETIC TEST ONLY: complete transcript review; target affects the reviewed other transcript; source-selected putative RNA9 MFE* with unknown function; bounded complete upstream review. "
                    + (baseline == Baseline.MODERATE || baseline == Baseline.PARENT_CAP ? "Independent Pathogenic reference c4A>G established without this start-loss inference. " : "No qualifying upstream pathogenic reference in the declared reviewed scope. ")
                    + "No actual human experiment or clinical approval.";
            publish(i, "init-primary", "PRIMARY_EVIDENCE", primary, "text/plain", review);
            var transcripts = document("TRANSCRIPT_FUNCTION_REVIEW", context, model, material, effect, primary)
                    .put("complete", true).put("truncated", false).put("unresolvedCount", 0);
            transcripts.putArray("findings").addObject().put("transcript", tx);
            var other = transcripts.withArray("findings").addObject().put("transcript", "ENST000002.1")
                    .put("differentStart", false).put("functionallyRelevant", true).put("targetUnaffected", false)
                    .put("qualification", "REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE"); quote(other, primary);
            publish(i, "init-transcripts", "TRANSCRIPT_FUNCTION_REVIEW", mapper.writeValueAsString(transcripts), "application/json", review);
            var restart = document("RESTART_REVIEW", context, model, material, effect, primary)
                    .put("startStatus", "SOURCE_REVIEWED_PUTATIVE").put("alteredStartRna1", 9).put("proteinThroughFirstStop", "MFE*").put("functionFinding", "UNKNOWN");
            if (baseline == Baseline.WRONG_CONTEXT) restart.put("contextSha256", "0".repeat(64));
            publish(i, "init-restart", "RESTART_REVIEW", mapper.writeValueAsString(restart), "application/json", review);
            var upstream = document("UPSTREAM_REVIEW", context, model, material, effect, primary)
                    .put("complete", baseline != Baseline.UPSTREAM_INCOMPLETE).put("truncated", false).put("unresolvedCount", 0).put("cdsStart", 1).put("cdsEnd", 9);
            var records = upstream.putArray("records");
            if (baseline == Baseline.MODERATE || baseline == Baseline.PARENT_CAP) {
                var record = records.addObject().put("recordId", "SYNTHETIC_INDEPENDENT_C4").put("hgncId", context.at("/gene/hgncId").asText())
                        .put("mondoId", context.at("/disease/mondoId").asText()).put("moi", context.path("moi").asText())
                        .put("classification", "PATHOGENIC").put("withdrawn", false).put("conflicting", false)
                        .put("classificationBasis", "INDEPENDENT_OF_START_LOSS_INFERENCE");
                record.putObject("allele").put("assembly", "GRCh38").put("contig", model.path("contig").asText())
                        .put("position", 1005).put("reference", "A").put("alternate", "G"); quote(record, primary);
            }
            publish(i, "init-upstream", "UPSTREAM_REVIEW", mapper.writeValueAsString(upstream), "application/json", review);
            var ids = review.putArray("sourceIds");
            for (var source : policy.path("scientificSources")) {
                String id = source.path("sourceId").asText();
                if (baseline != Baseline.SOURCE_OUTSIDE_REVIEW || !"init-primary".equals(id)) ids.add(id);
            }
            assessment.put("policySha256", hashes.canonicalSha256(policy));
        }
        writeKnowledge(); writeBundle();
    }

    private ObjectNode document(String role, JsonNode context, JsonNode model, JsonNode material, String effect, String primary) {
        var doc = mapper.createObjectNode().put("schemaVersion", "pvs1-rna-initiation-reviewed-source-v1").put("role", role)
                .put("reviewId", "SYNTHETIC_" + role).put("dataClass", "SYNTHETIC").put("clinicalAcceptance", false)
                .put("contextSha256", hashes.canonicalSha256(context));
        doc.putObject("scope").put("modelSha256", hashes.canonicalSha256(model))
                .put("alteredRnaSha256", material.at("/consequenceFacts/binding/alteredRnaSha256").asText())
                .put("mechanicalEffectSha256", effect).put("selectedTranscript", model.path("transcript").asText());
        var review = doc.putObject("review").put("status", "REVIEWED_FOR_THIS_RNA_PRODUCT").put("basis", "Invented engineering baseline, no experiment was performed");
        quote(review, primary); return doc;
    }
    private void quote(ObjectNode node, String primary) {
        node.putArray("sourceQuotes").addObject().put("sourceId", "init-primary").put("start", 0).put("end", primary.length()).put("text", primary);
    }
    private void publish(int index, String id, String role, String raw, String media, ObjectNode review) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8); String sha = CodingTranscriptMaterialSupply.sha(bytes);
        business.policies.get(index).withArray("scientificSources").addObject().put("sourceId", id).put("version", "TEST_ONLY")
                .put("url", "https://example.test/SYNTHETIC/" + id).put("sha256", sha);
        business.policies.get(index).withObject("spliceScope").withObject("initiationScope").withArray("sourceBindings").addObject()
                .put("sourceId", id).put("role", role).put("sha256", sha).put("version", "TEST_ONLY").put("url", "https://example.test/SYNTHETIC/" + id)
                .put("reviewId", "SYNTHETIC_" + role).put("mediaType", media);
        review.withObject("sourceDocuments").putObject(id).put("rawText", raw); policyOriginals.get(index).put(id, bytes);
    }
    private void writeKnowledge() throws Exception {
        var facts = Pvs1KnowledgeCatalogTest.facts(mapper, business.policies); Path factFile = write("facts.json", facts);
        var adoption = Pvs1SpliceSourceQualificationTest.adoption(mapper, facts, business.policies);
        adoption.put("sourceCandidatesSha256", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(factFile)));
        for (int i = 0; i < 2; i++) ((ObjectNode) adoption.at("/entries/" + i)).set("qualification",
                Pvs1SpliceSourceQualificationTest.qualification(mapper, root.resolve("knowledge-" + i), business.policies.get(i), business.models.get(i), List.of(), policyOriginals.get(i)));
        pin("pvs1-knowledge-source", factFile); pin("pvs1-knowledge-adoption", write("adoption.json", adoption));
        env.withProperty(Pm4Bp3ComplexPublicFixture.PREFIX + "pvs1-selected-knowledge-enabled", "true")
                .withProperty(Pm4Bp3ComplexPublicFixture.PREFIX + "pvs1-local-material-supply-enabled", "true");
    }
    private void writeBundle() throws Exception {
        var bundle = business.document(); var index = bundle.withObject("materials").putArray("pvs1FullRnaOriginals");
        for (int i = 0; i < 2; i++) {
            byte[] raw = mapper.writeValueAsBytes(originals.get(i)); Files.write(originalFile(i), raw);
            bindings.get(i).withObject("sourceRecord").put("sha256", CodingTranscriptMaterialSupply.sha(raw));
            var row = index.addObject().put("entryId", "SYNTHETIC_NON_RESCUE_INITIATION_" + i).put("transcript", business.models.get(i).path("transcript").asText());
            row.set("allele", originals.get(i).at("/scope/allele")); row.set("binding", bindings.get(i));
        }
        Path file = write("bundle.json", bundle); String prefix = Pm4Bp3ComplexPublicFixture.PREFIX;
        env.withProperty(prefix + "pvs1-bundle-file", Objects.requireNonNull(file.toString()))
                .withProperty(prefix + "pvs1-bundle-file-sha256", Objects.requireNonNull(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file))))
                .withProperty(prefix + "pvs1-bundle-sha256", Objects.requireNonNull(hashes.canonicalSha256(bundle)));
    }
    public void declareMultipleOutcomes(boolean alleleBound) throws Exception {
        for (var original : originals) {
            original.withObject("assay").put("outcomeMultiplicity", "MULTIPLE_OUTCOMES");
            if (!alleleBound) original.withObject("physicalAllele").put("status", "UNKNOWN");
        }
        writeBundle();
    }
    public Path originalFile(int index) { return root.resolve("rna-" + index + ".json"); }
    private Path write(String name, JsonNode value) throws Exception { Path file = root.resolve(name); Files.write(file, mapper.writeValueAsBytes(value)); return file; }
    private void pin(String key, Path file) throws Exception {
        env.withProperty(Pm4Bp3ComplexPublicFixture.PREFIX + key + "-file", Objects.requireNonNull(file.toString()))
                .withProperty(Pm4Bp3ComplexPublicFixture.PREFIX + key + "-sha256", Objects.requireNonNull(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file))));
    }
}
