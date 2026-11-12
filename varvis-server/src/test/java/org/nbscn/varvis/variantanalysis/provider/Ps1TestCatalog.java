package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.tabix.*;
import java.nio.file.*;
import java.util.*;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;

/** SYNTHETIC / TEST_ONLY: invented sequences, assertions and raw splice responses; no real resource access. */
public final class Ps1TestCatalog {
    public static final String CDS = "ATGGAACCTGGCTTTAAATAA";
    public static final String SPLICE = "e2f652e52955a0c6aaed434a3424cb69bb29413908d7dba8aaded016aa537542";
    public final ObjectMapper mapper;
    public final RuntimeEvidenceV2Adapter adapter;
    public final List<ObjectNode> policies = new ArrayList<>(), materials = new ArrayList<>();
    public int discoveryCalls, annotationCalls, spliceCalls;
    public boolean missingSplice, different, incomplete;
    public boolean incidentalAssertion;
    public String spliceResourceSha256 = SPLICE;
    private boolean pm5;
    public String canonicalSha256(JsonNode value) { return adapter.canonicalSha256(value); }
    public Ps1TestCatalog(ObjectMapper mapper) throws Exception {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        add("HBB", "HGNC:4827", "MONDO:0013517", "11", "ENST00000647020.1", "ENSP000001.1", 1);
        add("GJB2", "HGNC:4284", "MONDO:0019497", "13", "ENST00000382848.5", "ENSP000002.1", 2);
    }
    /** Two backgrounds share the same directory/discovery harness; all scores are invented TEST_ONLY data. */
    public void configurePm5() {
        pm5 = true; different = true;
        for (int i = 0; i < materials.size(); i++) {
            ObjectNode p = policies.get(i), m = materials.get(i);
            p.putArray("criterionIds").add(MissenseComparisonRuntime.PM5);
            ObjectNode rule = p.putObject("rules").putObject("PM5"); sources(rule);
            rule.putObject("classificationStrengths").put("PATHOGENIC", "MODERATE").put("LIKELY_PATHOGENIC", "SUPPORTING");
            if (i == 0) {
                rule.put("mode", "SUBSTITUTION_MATRIX_SINGLE_REFERENCE").put("matrixKind", "GRANTHAM").put("comparison", "TARGET_GTE_REFERENCE");
                rule.putObject("failureStrengths").put("PATHOGENIC", "NONE").put("LIKELY_PATHOGENIC", "NONE");
                ObjectNode matrix = m.putObject("substitutionMatrix").put("matrixKind", "GRANTHAM").put("reviewId", "TEST_ONLY_INVENTED_MATRIX"); sources(matrix);
                ObjectNode values = matrix.putObject("matrix");
                for (String a : "A C D E F G H I K L M N P Q R S T V W Y".split(" ")) {
                    ObjectNode row = values.putObject(a);
                    for (String b : "A C D E F G H I K L M N P Q R S T V W Y".split(" ")) row.put(b, a.equals(b) ? 0 : 40);
                }
                ((ObjectNode) values.path("E")).put("D", 80); ((ObjectNode) values.path("D")).put("E", 80);
            } else {
                rule.put("mode", "AUDITED_AMINO_ACID_TRIPLES_SINGLE_REFERENCE");
                ObjectNode triple = m.putArray("aminoAcidRelations").addObject().put("referenceAminoAcid", "E")
                        .put("targetAlternate", "D").put("comparatorAlternate", "K").put("reviewId", "TEST_ONLY"); sources(triple);
            }
            ObjectNode ref = (ObjectNode) m.at("/referenceAudit/references/0");
            ref.set("allele", allele(m.at("/nativeModel/contig").asText(), 103, "G", "A"));
            ref.set("annotation", annotation(i, true));
            ((ObjectNode) m.at("/rnaReview/comparisons/0")).set("referenceAllele", ref.path("allele").deepCopy());
        }
    }
    private ObjectNode source() { return mapper.createObjectNode().put("sourceId", "TEST_ONLY")
            .put("version", "synthetic-snapshot-v1").put("url", "https://example.invalid/ps1-synthetic").put("sha256", "a".repeat(64)); }
    private void sources(ObjectNode node) { node.putArray("sourceIds").add("TEST_ONLY"); }
    private void add(String gene, String hgnc, String mondo, String contig, String tx, String protein, int id) throws Exception {
        ObjectNode p = mapper.createObjectNode().put("schemaVersion", "missense-comparison-policy-v1")
                .put("policyId", "TEST_ONLY_" + id).put("policyVersion", "1"); policies.add(p);
        p.putArray("criterionIds").add(MissenseComparisonRuntime.PS1);
        p.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY");
        ObjectNode scope = p.putObject("scope").put("hgncId", hgnc).put("geneSymbol", gene).put("mondoId", mondo)
                .put("moi", "AUTOSOMAL_RECESSIVE").put("assembly", "GRCh38").put("protein", protein)
                .put("variantType", "NATIVE_NONPROJECTED_SNV").put("supportedEffects", "MISSENSE_EXCLUDING_START_AND_SPLICE");
        int dot = tx.lastIndexOf('.'); scope.putArray("transcripts").addObject().put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)));
        p.putArray("sources").add(source());
        p.set("registryBinding", ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("registryBinding").deepCopy());
        ((ObjectNode) p.path("registryBinding")).retain("registryId", "registryClasspathResource", "registrySha256");
        p.put("comparatorProfileSha256", adapter.canonicalSha256(ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper)))
                .put("overlapPolicy", "PS1_PRECEDENCE_NO_ADDITIVE_REFERENCE_SUPPORT");
        ObjectNode sp = p.putObject("splicePolicy").put("method", "SpliceAI_RAW").put("operator", "LTE").put("maximumScore", "0.1")
                .put("distanceBp", 4999).put("mask", 0).put("aboveThresholdOutcome", "NOT_EVALUATED").put("resourceSha256", SPLICE).put("basis", "TEST_ONLY"); sources(sp);
        ObjectNode rule = p.putObject("rules").putObject("PS1").put("mode", "SAME_PROTEIN_CHANGE_SINGLE_REFERENCE"); sources(rule);
        rule.putObject("classificationStrengths").put("PATHOGENIC", "STRONG").put("LIKELY_PATHOGENIC", "MODERATE");
        ObjectNode m = mapper.createObjectNode().put("schemaVersion", "reviewed-missense-materials-v1").put("materialId", "TEST_ONLY_" + id).put("version", "1"); materials.add(m);
        m.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY"); m.set("scope", scope.deepCopy());
        ObjectNode model = m.putObject("nativeModel").put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "TEST_ONLY_" + id).put("modelVersion", "1").put("assembly", "GRCh38").put("contig", contig)
                .put("strand", 1).put("hgncId", hgnc).put("geneSymbol", gene).put("transcript", tx).put("protein", protein)
                .put("cdsSequence", CDS).put("cdsLength", 21).put("proteinLength", 6);
        model.putArray("sourceRecords").add(source());
        model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 120).put("cdsStart", 1).put("cdsEnd", 21);
        try (var in = getClass().getResourceAsStream("/acmg/spliceai-business-vep-identity-v1.json")) {
            m.set("annotationResource", mapper.readTree(Objects.requireNonNull(in)).at("/resources/0").deepCopy());
        }
        ObjectNode audit = m.putObject("referenceAudit").put("status", "REVIEWED").put("reviewId", "TEST_ONLY"); sources(audit);
        ObjectNode ref = audit.putArray("references").addObject().put("referenceId", "TEST_ONLY_REF_" + id).put("scv", "SCV00000000" + id + ".1")
                .put("variationId", Integer.toString(id)).put("classification", id == 1 ? "Pathogenic" : "Likely pathogenic")
                .put("evaluationDate", "2026-09-23").put("hgncId", hgnc).put("geneSymbol", gene).put("mondoId", mondo)
                .put("assertionStatus", "ACTIVE").put("moi", "AUTOSOMAL_RECESSIVE"); sources(ref);
        ref.set("allele", allele(contig, 105, "A", "T")); ref.set("annotation", annotation(id - 1, true));
        ObjectNode independence = ref.putObject("independence").put("status", "REVIEWED_SUFFICIENT_WITHOUT_PS1_PM5")
                .put("reviewId", "TEST_ONLY").put("originalSpecificationVersion", "SYNTHETIC@1")
                .put("classificationAfterExclusion", ref.path("classification").asText()).put("dependencyReview", "REVIEWED_NO_COMPARISON_DEPENDENCY");
        sources(independence); independence.putArray("criteria").add("PS3").add("PP1"); independence.putArray("dependencyAlleles");
        ObjectNode rna = m.putObject("rnaReview").put("status", "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED")
                .put("rnaEffect", "UNKNOWN").put("normalRnaClaimed", false).put("reviewId", "TEST_ONLY"); sources(rna); rna.putArray("knownConcerns");
        ObjectNode comparison = rna.putArray("comparisons").addObject().put("status", "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED").put("reviewId", "TEST_ONLY"); sources(comparison);
        comparison.set("targetAllele", allele(contig, 105, "A", "C")); comparison.set("referenceAllele", ref.path("allele").deepCopy());
        ObjectNode proof = m.putObject("spliceAnnotationProof").put("status", "FULL_EXON_BOUNDARIES_MATCH").put("reviewId", "TEST_ONLY")
                .put("resourceSha256", SPLICE).put("transcript", tx); sources(proof); proof.set("exons", model.path("exons").deepCopy());
    }
    public ObjectNode allele(String contig, int pos, String ref, String alt) { return mapper.createObjectNode().put("assembly", "GRCh38").put("contig", contig).put("position", pos).put("reference", ref).put("alternate", alt); }
    public ObjectNode annotation(int i, boolean reference) {
        JsonNode model = materials.get(i).path("nativeModel"); String tx = model.path("transcript").asText();
        return mapper.createObjectNode().put("geneSymbol", model.path("geneSymbol").asText()).put("transcriptAccession", tx)
                .put("hgvsCoding", tx + (reference && different ? ":c.4G>A" : reference ? ":c.6A>T" : ":c.6A>C"))
                .put("hgvsProtein", model.path("protein").asText() + (reference && different ? ":p.Glu2Lys" : ":p.Glu2Asp"))
                .put("consequence", "missense_variant").put("exon", "1/1").put("proteinPosition", "2")
                .put("aminoAcids", reference && different ? "E/K" : "E/D").put("codons", reference && different ? "Gaa/Aaa" : reference ? "gaA/gaT" : "gaA/gaC")
                .put("source", "VEP").put("status", "COMPLETED").put("version", "116.0").put("timestamp", "2026-09-23T00:00:00Z").put("maneSelect", true);
    }
    public ObjectNode document() {
        ObjectNode d = ReviewedRuleContextPreparationTest.emptyBundleDocument(mapper, "1");
        d.put("schemaVersion", pm5 ? "reviewed-rule-bundle-pm5-v4" : "reviewed-rule-bundle-ps1-v3").put("bundleId", pm5 ? "TEST_ONLY_MULTI_BACKGROUND_PM5" : "TEST_ONLY_MULTI_BACKGROUND_PS1");
        ObjectNode c = (ObjectNode) d.path("catalog"); c.putArray("sources").add(source());
        ObjectNode directory = ((ObjectNode) d.path("materials")).putObject("missenseMaterials");
        for (int i = 0; i < materials.size(); i++) {
            ObjectNode m = materials.get(i), p = policies.get(i);
            if (m.has("substitutionMatrix")) ((ObjectNode) p.at("/rules/PM5")).put("matrixSha256", adapter.canonicalSha256(m.path("substitutionMatrix")));
            ((ObjectNode) m.path("spliceAnnotationProof")).put("modelSha256", adapter.canonicalSha256(m.path("nativeModel")));
            p.put("nativeModelSha256", adapter.canonicalSha256(m.path("nativeModel"))).put("annotationResourceSha256", adapter.canonicalSha256(m.path("annotationResource")))
                    .put("spliceAnnotationProofSha256", adapter.canonicalSha256(m.path("spliceAnnotationProof"))).put("materialsSha256", adapter.canonicalSha256(m));
            directory.set(adapter.canonicalSha256(m), m.deepCopy()); d.withArray("policies").add(p.deepCopy());
            ObjectNode entry = c.withArray("entries").addObject().put("entryId", "TEST_ONLY_" + i).put("criterionId", pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1)
                    .put("searchStatus", "COMPLETE").put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "TEST_ONLY");
            entry.set("scope", p.path("scope").deepCopy()); sources(entry); entry.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
            ((ObjectNode) entry.path("scope")).put("transcriptMode", "EXACT_ACCESSIONS");
            entry.putObject("policyBinding").put("policyId", p.path("policyId").asText()).put("policyVersion", "1").put("policySha256", adapter.canonicalSha256(p));
        }
        d.put("catalogSha256", adapter.canonicalSha256(c)); return d;
    }
    public ReviewedRuleBundle load(Path root, ObjectNode document) throws Exception {
        var runtime = runtime(root); Path file = root.resolve("test-only-ps1-directory.json"); mapper.writeValue(file.toFile(), document);
        return ReviewedRuleBundle.load(mapper, file, adapter.canonicalSha256(document), () -> runtime,
                mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("sourceSnapshotSha256", adapter.canonicalSha256(document)));
    }
    /** Consume the production preparer's actual file without regenerating or patching it. */
    public ReviewedRuleBundle loadPrepared(Path root, Path file, String sha) throws Exception {
        JsonNode prepared = mapper.readTree(file.toFile());
        pm5 = "reviewed-rule-bundle-pm5-v4".equals(prepared.path("schemaVersion").asText());
        policies.clear(); materials.clear();
        for (JsonNode p : prepared.path("policies")) {
            policies.add((ObjectNode) p.deepCopy());
            materials.add((ObjectNode) prepared.at("/materials/missenseMaterials").path(p.path("materialsSha256").asText()).deepCopy());
        }
        spliceResourceSha256 = prepared.at("/policies/0/splicePolicy/resourceSha256").asText();
        var execution = runtime(root);
        return ReviewedRuleBundle.load(mapper, file, sha, () -> execution,
                mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("sourceSnapshotSha256", sha));
    }
    private MissenseComparisonRuntime runtime(Path root) throws Exception {
        Path vcf = root.resolve("test-only-discovery.vcf.gz"); Files.write(vcf, new byte[]{1}); Files.write(Path.of(vcf + ".tbi"), new byte[]{1});
        var settings = new VariantAnalysisProperties(); settings.getClinVar().setEnabled(true); settings.getClinVar().setGrch38File(vcf.toString());
        var provider = new ClinVarTabixProvider(settings, null, (file, region) -> {
            discoveryCalls++; List<String> rows = new ArrayList<>();
            for (ObjectNode m : materials) if (region.startsWith(m.at("/nativeModel/contig").asText() + ":")) {
                JsonNode ref = m.at("/referenceAudit/references/0"), a = ref.path("allele");
                rows.add(a.path("contig").asText() + "\t" + a.path("position").asInt() + "\t" + ref.path("variationId").asText() + "\t" + a.path("reference").asText() + "\t" + a.path("alternate").asText() + "\t.\tPASS\tCLNSIGSCV=" + ref.path("scv").asText());
                if (incomplete && "1".equals(ref.path("variationId").asText()))
                    rows.add(a.path("contig").asText() + "\t105\t3\tA\tT\t.\tPASS\tCLNSIGSCV=SCV000000003.1");
            } return rows;
        });
        var vep = new VepTranscriptAnnotationProvider(new VariantAnalysisProperties()) {
            @Override public Map<NormalizedVariantKey, List<TranscriptConsequence>> annotateBatch(List<VariantInterpretation> candidates, AnalysisContext context) {
                annotationCalls++; Map<NormalizedVariantKey, List<TranscriptConsequence>> result = new LinkedHashMap<>();
                for (var candidate : candidates) {
                    var k = candidate.getObservation().getKey();
                    for (int i = 0; i < materials.size(); i++) if (k.getChromosome().equals(materials.get(i).at("/nativeModel/contig").asText())) {
                        try { result.put(k, List.of(mapper.treeToValue(annotation(i, k.getAlternate().equals("T") || different && k.getPosition() == 103), TranscriptConsequence.class))); }
                        catch (Exception invalid) { throw new IllegalStateException(invalid); }
                    }
                } return result;
            }
        };
        var comparator = new ClinVarConditionAwareComparatorInput(provider, vep, sidecar(root), new VepProteinRelationClassifier());
        return new MissenseComparisonRuntime(mapper, comparator, queries -> {
            spliceCalls++; if (missingSplice) return SpliceAiRawBatchRunner.BatchResult.failed("TEST_ONLY_MISSING", null);
            ArrayNode requested = mapper.createArrayNode(); ObjectNode raw = mapper.createObjectNode().put("resultSchemaVersion", SpliceAiRawBatchRunner.RESULT_SCHEMA_VERSION).put("status", "SUCCEEDED");
            ArrayNode rows = raw.putArray("results");
            for (var q : queries) {
                var k = q.getKey(); var a = allele(k.getChromosome(), k.getPosition(), k.getReference(), k.getAlternate());
                ObjectNode request = requested.addObject().put("queryId", q.getQueryId()); request.set("allele", a);
                request.put("geneSymbol", q.getGeneSymbol()).put("selectedTranscriptId", q.getSelectedTranscriptId());
                ObjectNode row = rows.addObject().put("queryId", q.getQueryId()).put("status", "HIT").put("exactAlleleMatch", true); row.set("allele", a);
                ObjectNode scores = row.putArray("annotations").addObject().put("geneSymbol", q.getGeneSymbol()).putObject("deltaScores");
                for (String f : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) scores.put(f, "0.01");
            }
            try { raw.put("requestId", "spliceai-batch-sha256-" + AcmgBusinessStore.sha(mapper.writeValueAsString(requested))); }
            catch (Exception invalid) { throw new IllegalStateException(invalid); }
            return SpliceAiRawBatchRunner.BatchResult.succeeded(raw, 0);
        }, spliceResourceSha256);
    }
    private ClinVarComparatorSidecar sidecar(Path root) throws Exception {
        Path file = root.resolve("test-only-sidecar.tsv.gz"); var index = new TabixIndexCreator(new TabixFormat(0, 1, 2, 3, '#', 0));
        try (var output = new BlockCompressedOutputStream(file.toFile())) {
            for (ObjectNode m : materials) {
                JsonNode ref = m.at("/referenceAudit/references/0"), a = ref.path("allele"); int id = ref.path("variationId").asInt();
                index.addFeature(new htsjdk.tribble.Feature() { public String getContig() { return "VID"; } public int getStart() { return id; } public int getEnd() { return id; } }, output.getFilePointer());
                ObjectNode assertion = mapper.createObjectNode().put("scv", ref.path("scv").asText()).put("classification", ref.path("classification").asText())
                        .put("reviewStatus", "reviewed by expert panel").put("dateLastEvaluated", "2026-09-23").put("geneSymbol", ref.path("geneSymbol").asText());
                assertion.putObject("condition").put("submittedRaw", ref.path("mondoId").asText()).put("reportedRaw", ref.path("mondoId").asText());
                assertion.putObject("sourceLocator").put("logicalName", "TEST_ONLY_variant_summary.txt").put("line", id);
                var assertions = mapper.createArrayNode().add(assertion);
                if (incidentalAssertion) assertions.add(assertion.deepCopy().put("scv", "SCV000000099.1")
                        .put("classification", "Uncertain significance").put("reviewStatus", "criteria provided, single submitter"));
                String row = "VID\t" + id + "\t" + id + "\t" + id + "\tGRCh38\t" + a.path("contig").asText() + "\t" + a.path("position").asInt()
                        + "\t" + a.path("reference").asText() + "\t" + a.path("alternate").asText() + "\t" + mapper.createArrayNode().add(ref.path("geneSymbol"))
                        + "\tNO_CONFLICT\t[]\t" + assertions + "\n";
                output.write(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } index.finalizeIndex(output.getFilePointer()).writeBasedOnFeaturePath(file);
        }
        JsonNode source = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources").get(2);
        return new ClinVarComparatorSidecar(mapper, file, source.path("resourceId").asText(), source.path("resourceVersion").asText(), source.path("identityFingerprintSha256").asText());
    }
}
