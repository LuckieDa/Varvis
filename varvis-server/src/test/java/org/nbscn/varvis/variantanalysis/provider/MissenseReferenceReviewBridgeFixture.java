package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.index.tabix.TabixIndexCreator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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


/** TEST_ONLY external author: invented upstream facts and separately explicit scientific judgments. */
final class MissenseReferenceReviewBridgeFixture {
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Path root, manifestPath, draftDir;
    final int backgroundIndex;
    final ObjectNode manifest, declaration, referenceDraft, rnaDraft, draftReport;
    final JsonNode selectedReference, selectedAssertion, selectedPair;
    final MissensePreparationFixture seed;
    final Upstream upstream;

    MissenseReferenceReviewBridgeFixture(Path root, int background) throws Exception {
        this(root, background, "2026-02-03");
    }

    MissenseReferenceReviewBridgeFixture(Path root, int background, String selectedDate) throws Exception {
        this(root, background, selectedDate, false);
    }

    MissenseReferenceReviewBridgeFixture(Path root, int background, String selectedDate, boolean sameScvConflict) throws Exception {
        this(root, background, selectedDate, sameScvConflict, 0);
    }

    MissenseReferenceReviewBridgeFixture(Path root, int background, String selectedDate, boolean sameScvConflict, int annotationPadding) throws Exception {
        this.root = root; backgroundIndex = background; Files.createDirectories(root);
        manifestPath = root.resolve("bridge-manifest.json"); draftDir = root.resolve("draft");
        upstream = new Upstream(root.resolve("upstream"), background, selectedDate, sameScvConflict, annotationPadding);
        var exported = new MissenseReferenceSnapshotExport().export(upstream.context, upstream.target,
                upstream.selected, upstream.builder, upstream.sources, root.resolve("snapshot"));
        if (!exported.exported()) throw new IllegalStateException(exported.report().toPrettyString());
        var drafted = new MissenseReferenceDraftPreparation().prepare(exported.manifestPath(), exported.manifestSha256(), draftDir);
        if (!drafted.generated()) throw new IllegalStateException(drafted.report().toPrettyString());
        referenceDraft = json(draftDir.resolve("reference-audit.draft.json"));
        rnaDraft = json(draftDir.resolve("rna-pairs.draft.json")); draftReport = json(draftDir.resolve("draft-report.json"));
        selectedReference = referenceDraft.path("references").get(0);
        JsonNode chosen = null;
        for (JsonNode assertion : selectedReference.path("assertions"))
            if ("SCV000000002.2".equals(assertion.at("/sourceAssertion/scv").asText())
                    && "Uncertain significance".equals(assertion.at("/sourceAssertion/classification").asText())) chosen = assertion;
        selectedAssertion = Objects.requireNonNull(chosen); selectedPair = rnaDraft.path("pairs").get(0);
        seed = new MissensePreparationFixture(root.resolve("seed"), new Ps1TestCatalog(mapper), background == 1);
        manifest = mapper.createObjectNode().put("schemaVersion", "missense-reference-review-bridge-manifest-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY");
        manifest.set("background", seed.manifest.path("backgrounds").get(background).path("background").deepCopy());
        manifest.set("referenceDraft", descriptor(draftDir.resolve("reference-audit.draft.json")));
        manifest.set("rnaDraft", descriptor(draftDir.resolve("rna-pairs.draft.json")));
        manifest.set("draftReport", descriptor(draftDir.resolve("draft-report.json")));
        manifest.set("preparationSeed", descriptor(seed.manifestPath));
        ObjectNode factSources = manifest.putObject("factSources");
        for (String role : List.of("referenceDraft", "rnaDraft", "draftReport", "review"))
            factSources.set(role, source("FACT_" + role, referenceDraft.path("draftId").asText()));
        ObjectNode evidence = mapper.createObjectNode().put("schemaVersion", "test-only-external-review-evidence-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY")
                .put("content", "Invented independent PS3 evidence and explicitly reviewed exact RNA pair; no scientific adoption");
        ObjectNode evidenceDescriptor = file("external-evidence.json", evidence);
        evidenceDescriptor.put("format", "JSON").put("sourceDigestKind", "CANONICAL_JSON")
                .set("sourceRecord", source("REVIEW_EVIDENCE", "TEST_ONLY@1"));
        manifest.putArray("reviewEvidence").add(evidenceDescriptor);
        declaration = mapper.createObjectNode().put("schemaVersion", "missense-reference-review-declaration-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY");
        declaration.set("background", manifest.path("background").deepCopy());
        declaration.putObject("referenceAudit").put("status", "REVIEWED").put("reviewId", "TEST_ONLY_EXTERNAL_REFERENCE_REVIEW")
                .putArray("sourceIds").add("FACT_referenceDraft").add("REVIEW_EVIDENCE");
        ArrayNode decisions = declaration.putArray("references");
        decisions.addObject().put("referenceDraftId", selectedReference.path("referenceDraftId").asText())
                .put("assertionDraftId", selectedReference.at("/assertions/0/assertionDraftId").asText())
                .put("decision", "DO_NOT_ADOPT").put("reason", "TEST_ONLY external reviewer explicitly chooses the other versioned submission");
        ObjectNode adopted = decisions.addObject().put("referenceDraftId", selectedReference.path("referenceDraftId").asText())
                .put("assertionDraftId", selectedAssertion.path("assertionDraftId").asText()).put("decision", "ADOPT");
        ObjectNode scientificReview = adopted.putObject("review").put("reviewId", "TEST_ONLY_EXTERNAL_REFERENCE_SELECTION")
                .put("classification", "PATHOGENIC").put("assertionStatus", "ACTIVE")
                .put("conflictState", "EXTERNALLY_REVIEWED_TEST_ONLY");
        for (String field : List.of("hgncId", "geneSymbol", "mondoId", "moi"))
            scientificReview.set(field, manifest.path("background").path(field));
        scientificReview.putArray("sourceIds").add("FACT_referenceDraft").add("REVIEW_EVIDENCE");
        ObjectNode independence = scientificReview.putObject("independence")
                .put("status", "REVIEWED_SUFFICIENT_WITHOUT_PS1_PM5").put("reviewId", "TEST_ONLY_EXTERNAL_INDEPENDENCE")
                .put("originalSpecificationVersion", "TEST_ONLY@1").put("classificationAfterExclusion", "PATHOGENIC")
                .put("dependencyReview", "REVIEWED_NO_COMPARISON_DEPENDENCY");
        independence.putArray("sourceIds").add("REVIEW_EVIDENCE"); independence.putArray("criteria").add("PS3");
        independence.putArray("dependencyAlleles");
        ObjectNode rnaReview = declaration.putObject("rnaReview").put("status", "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED")
                .put("rnaEffect", "UNKNOWN").put("normalRnaClaimed", false).put("reviewId", "TEST_ONLY_EXTERNAL_RNA");
        rnaReview.putArray("sourceIds").add("REVIEW_EVIDENCE"); rnaReview.putArray("knownConcerns");
        ObjectNode pairReview = declaration.putArray("rnaComparisons").addObject()
                .put("pairDraftId", selectedPair.path("pairDraftId").asText()).putObject("review")
                .put("status", "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED").put("reviewId", "TEST_ONLY_EXTERNAL_EXACT_PAIR");
        pairReview.putArray("sourceIds").add("REVIEW_EVIDENCE"); pairReview.putArray("knownConcerns");
        bindDrafts(); save();
    }

    ObjectNode source(String id, String version) {
        return mapper.createObjectNode().put("sourceId", id).put("version", version).put("url", "https://example.invalid/test-only/" + id);
    }
    ObjectNode json(Path path) throws Exception { return (ObjectNode) mapper.readTree(path.toFile()); }
    ObjectNode descriptor(Path path) throws Exception {
        return mapper.createObjectNode().put("path", root.relativize(path).toString().replace('\\', '/'))
                .put("rawSha256", Pm4MaterialPreparation.sha(Files.readAllBytes(path)))
                .put("canonicalSha256", adapter.canonicalSha256(json(path)));
    }
    ObjectNode file(String name, JsonNode node) throws Exception {
        Path path = root.resolve(name); mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), node); return descriptor(path);
    }
    void bindDrafts() {
        ObjectNode binding = declaration.putObject("draftBinding").put("draftId", referenceDraft.path("draftId").asText());
        for (String role : List.of("referenceDraft", "rnaDraft", "draftReport")) {
            ObjectNode pins = binding.putObject(role);
            pins.set("rawSha256", manifest.path(role).path("rawSha256")); pins.set("canonicalSha256", manifest.path(role).path("canonicalSha256"));
        }
    }
    void refreshDrafts() throws Exception {
        manifest.set("referenceDraft", file("draft/reference-audit.draft.json", referenceDraft));
        manifest.set("rnaDraft", file("draft/rna-pairs.draft.json", rnaDraft));
        manifest.set("draftReport", file("draft/draft-report.json", draftReport)); bindDrafts(); save();
    }
    void save() throws Exception {
        manifest.set("review", file("review.json", declaration));
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
    }
    String pin() throws Exception { return Pm4MaterialPreparation.sha(Files.readAllBytes(manifestPath)); }
    MissenseReferenceReviewBridge.Result bridge(Path output) throws Exception {
        return new MissenseReferenceReviewBridge().prepare(manifestPath, pin(), output);
    }
    /** Inputs are invented upstream facts; this fixture never assembles a normal export or reviewed material. */
    final class Upstream {
        final Path root;
        final int background;
        final String gene, transcript, protein;
        final NormalizedVariantKey targetKey, referenceKey;
        final ObjectNode context, sources;
        final ArrayNode assertions = mapper.createArrayNode(), summaries = mapper.createArrayNode();
        final VariantInterpretation target;
        final TranscriptConsequence selected, batchTarget;
        final ClinVarConditionAwareComparatorInput builder;
        final List<String> batchAlleles = new ArrayList<>();
        String referenceExon;
        Runnable annotationHook = () -> { };
        TranscriptConsequence actualReference;
        int discoveryCalls, annotationCalls;

        Upstream(Path root, int background, String selectedDate, boolean sameScvConflict, int annotationPadding) throws Exception {
            String scenario = "NORMAL", exon = annotationPadding == 0 ? "17/83" : "TEST_ONLY_" + "X".repeat(annotationPadding);
            this.root = root; this.background = background; this.referenceExon = exon; Files.createDirectory(root);
            String contig = background == 0 ? "11" : "13";
            gene = background == 0 ? "HBB" : "GJB2"; transcript = background == 0 ? "ENST00000647020.1" : "ENST00000382848.5"; protein = background == 0 ? "ENSP000001.1" : "ENSP000002.1";
            targetKey = new NormalizedVariantKey("GRCh38", contig, 105, "A", "C");
            referenceKey = background == 0 ? new NormalizedVariantKey("GRCh38", contig, 105, "A", "T") : new NormalizedVariantKey("GRCh38", contig, 103, "G", "A");
            try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/e2/runtime-evidence-v2-fixture.json"))) { context = (ObjectNode) mapper.readTree(stream).path("context").deepCopy(); }
            context.putObject("gene").put("symbol", gene).put("hgncId", background == 0 ? "HGNC:4827" : "HGNC:4284");
            context.putObject("disease").put("mondoId", background == 0 ? "MONDO:0013517" : "MONDO:0019497"); context.put("moi", "AUTOSOMAL_RECESSIVE");
            int dot = transcript.lastIndexOf('.'); context.putObject("transcript").put("accession", transcript.substring(0, dot)).put("version", Integer.parseInt(transcript.substring(dot + 1))).put("annotationSourceVersion", "116.0");
            ObjectNode identity = (ObjectNode) context.path("variant");
            for (String role : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) identity.set(role, allele(targetKey));
            identity.put("alleleId", adapter.canonicalAlleleId(allele(targetKey), identity.path("normalization"))); context.put("contextId", adapter.canonicalContextId(context));
            sources = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY");
            sources.set("CLINVAR_VCF", source("TEST_ONLY_VCF", "synthetic-1", 'a')); sources.set("SIDECAR", source("TEST_ONLY_SIDECAR", "synthetic-1", 'b')); sources.set("VEP", source("TEST_ONLY_VEP", "116.0", 'c'));
            selected = dto(annotation(false).put("impact", "SELECTED_TARGET_ONLY").put("exon", "2/17"));
            batchTarget = dto(annotation(false).put("impact", "BATCH_TARGET_ONLY").put("exon", "3/17"));
            target = new VariantInterpretation(targetKey.canonicalId(), new VariantQcObservation(targetKey, null, null, null, null, null, null, "PASS", "SNV", null), List.of(), List.of());
            Path vcf = root.resolve("synthetic.vcf.gz"); Files.write(vcf, new byte[]{1}); Files.write(Path.of(vcf + ".tbi"), new byte[]{1});
            var config = new VariantAnalysisProperties(); config.getClinVar().setEnabled(true); config.getClinVar().setGrch38File(vcf.toString());
            var clinvar = new ClinVarTabixProvider(config, null, (file, region) -> {
                discoveryCalls++;
                if (scenario.equals("NO_RECORD")) return List.of();
                if (scenario.equals("DISCOVERY_FAILED")) throw new java.io.IOException("SYNTHETIC query failure");
                String row = contig + "\t" + referenceKey.getPosition() + "\t" + (background + 1) + "\t" + referenceKey.getReference() + "\t" + referenceKey.getAlternate() + "\t.\tPASS\tCLNSIGSCV=SCV000000001.1|SCV000000002.2";
                return scenario.equals("PARTIAL") ? List.of(contig + "\t105\t9\tA\tG\t.\tPASS\tCLNSIGSCV=SCV9.1", row) : List.of(row);
            });
            var vep = new VepTranscriptAnnotationProvider(new VariantAnalysisProperties()) {
                @Override public Map<NormalizedVariantKey, List<TranscriptConsequence>> annotateBatch(List<VariantInterpretation> variants, AnalysisContext analysis) {
                    annotationCalls++; annotationHook.run(); Map<NormalizedVariantKey, List<TranscriptConsequence>> result = new LinkedHashMap<>();
                    List<VariantInterpretation> ordered = new ArrayList<>(variants); if (scenario.equals("REORDERED")) Collections.reverse(ordered);
                    for (VariantInterpretation variant : ordered) {
                        NormalizedVariantKey key = variant.getObservation().getKey(); batchAlleles.add(key.canonicalId());
                        if (scenario.equals("ANNOTATION_OMITTED") && key.equals(referenceKey)) continue;
                        if (scenario.equals("PARTIAL") && key.getAlternate().equals("G")) continue;
                        if (key.equals(referenceKey)) {
                            actualReference = dto(annotation(true).put("exon", referenceExon));
                            TranscriptConsequence unrelated = dto(annotation(true).put("transcriptAccession", "ENST99999999999.7").put("hgvsCoding", "ENST99999999999.7:c.6A>T").put("exon", "NOT_SELECTED"));
                            result.put(key, scenario.equals("ANNOTATION_AMBIGUOUS") ? List.of(actualReference, actualReference) : scenario.equals("REORDERED") ? List.of(actualReference, unrelated) : List.of(unrelated, actualReference));
                        } else result.put(key, List.of(batchTarget));
                    }
                    return result;
                }
            };
            int count = scenario.equals("ASSERTION_LIMIT") ? 1025 : 2;
            for (int i = 1; i <= count; i++) {
                ObjectNode scv = assertions.addObject().put("scv", i == 1 ? "SCV000000001.1" : "SCV000000002.2").put("classification", i == 1 ? "Pathogenic" : "Uncertain significance")
                        .put("dateLastEvaluated", i == 1 ? "2026-01-02" : selectedDate).put("reviewStatus", "criteria provided, single submitter");
                scv.putObject("condition").put("name", "TEST_ONLY condition " + i).put("submittedRaw", "SYNTHETIC raw condition " + i);
                scv.putObject("sourceLocator").put("logicalName", "SYNTHETIC-scv.xml").put("record", i);
            }
            if (sameScvConflict) {
                ObjectNode conflicting = assertions.get(1).deepCopy(); conflicting.put("classification", "Benign");
                assertions.add(conflicting);
            }
            for (int i = 1; i <= 2; i++) summaries.addObject().put("rcv", "RCV00000000" + i + ".1").put("classification", "Conflicting classifications of pathogenicity").putObject("condition").put("name", "TEST_ONLY condition " + i);
            Path sidecarPath = root.resolve("synthetic-sidecar.tsv.gz"); var index = new TabixIndexCreator(new TabixFormat(0, 1, 2, 3, '#', 0));
            try (var stream = new BlockCompressedOutputStream(sidecarPath.toFile())) {
                int id = background + 1;
                index.addFeature(new htsjdk.tribble.Feature() { public String getContig() { return "VID"; } public int getStart() { return id; } public int getEnd() { return id; } }, stream.getFilePointer());
                String row = "VID\t" + id + "\t" + id + "\t" + id + "\tGRCh38\t" + contig + "\t" + referenceKey.getPosition() + "\t" + referenceKey.getReference() + "\t" + referenceKey.getAlternate() + "\t[\"" + gene + "\"]\tCONFLICTING\t" + summaries + "\t" + assertions + "\n";
                stream.write(row.getBytes(StandardCharsets.UTF_8)); index.finalizeIndex(stream.getFilePointer()).writeBasedOnFeaturePath(sidecarPath);
            }
            var sidecar = new ClinVarComparatorSidecar(mapper, scenario.equals("SIDECAR_MISSING") ? root.resolve("absent.tsv.gz") : sidecarPath, "TEST_ONLY_SIDECAR", "synthetic-1", "b".repeat(64));
            builder = new ClinVarConditionAwareComparatorInput(clinvar, vep, sidecar, new VepProteinRelationClassifier());
            mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("synthetic-context.json").toFile(), context);
            mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("synthetic-source-declarations.json").toFile(), sources);
        }
        private TranscriptConsequence dto(ObjectNode value) {
            try { return Objects.requireNonNull(mapper.treeToValue(value, TranscriptConsequence.class)); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        private ObjectNode annotation(boolean reference) {
            boolean different = reference && background == 1;
            return mapper.createObjectNode().put("geneSymbol", gene).put("transcriptAccession", transcript)
                    .put("hgvsCoding", transcript + (different ? ":c.4G>A" : reference ? ":c.6A>T" : ":c.6A>C"))
                    .put("hgvsProtein", protein + (different ? ":p.Glu2Lys" : ":p.Glu2Asp")).put("proteinPosition", "2").put("aminoAcids", different ? "E/K" : "E/D")
                    .put("codons", different ? "Gaa/Aaa" : reference ? "gaA/gaT" : "gaA/gaC").put("consequence", "missense_variant")
                    .put("status", "COMPLETED").put("source", "VEP").put("version", "116.0").put("timestamp", "2026-09-26T00:00:00Z");
        }
        private ObjectNode source(String id, String version, char pin) { return mapper.createObjectNode().put("resourceId", id).put("resourceVersion", version).put("identityFingerprintSha256", String.valueOf(pin).repeat(64)); }
        private ObjectNode allele(NormalizedVariantKey key) { return mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome()).put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate()); }
    }

}
