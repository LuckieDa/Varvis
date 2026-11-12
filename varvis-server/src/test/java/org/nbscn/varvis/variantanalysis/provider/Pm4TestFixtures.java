package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

/** TEST_ONLY: invented 21-base CDS (MEPGFK*), synthetic policy and region assertions. */
public final class Pm4TestFixtures {
    public final ObjectMapper mapper;
    public final RuntimeEvidenceV2Adapter adapter;
    public final ObjectNode model, regions, policy;
    public ObjectNode context;
    public VariantInterpretation candidate;
    public static final String CDS = "ATGGAACCTGGCTTTAAATAA";

    public Pm4TestFixtures(ObjectMapper mapper, int strand) {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        model = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "TEST_ONLY_PM4_MODEL").put("modelVersion", "1").put("assembly", "GRCh38")
                .put("contig", "11").put("strand", strand).put("hgncId", "HGNC:4827").put("geneSymbol", "HBB")
                .put("transcript", "ENST000001.1").put("protein", "ENSP000001.1")
                .put("cdsSequence", CDS).put("cdsLength", 21).put("proteinLength", 6);
        model.putArray("sourceRecords").add(source());
        model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 120)
                .put("cdsStart", 1).put("cdsEnd", 21);
        regions = mapper.createObjectNode().put("schemaVersion", "pm4-region-material-v1")
                .put("materialId", "TEST_ONLY_PM4_REGIONS").put("materialVersion", "1");
        regions.putArray("sourceRecords").add(source());
        regions.putArray("intervals").addObject().put("start", 100).put("end", 120)
                .put("repeatStatus", "NON_REPEAT").put("functionalContext", "SUPPORTED");
        policy = mapper.createObjectNode().put("schemaVersion", "pm4-simple-inframe-policy-v1")
                .put("policyId", "TEST_ONLY_PM4_POLICY").put("policyVersion", "1")
                .put("branch", "SIMPLE_INFRAME_INDEL_NON_REPEAT").put("appliedStrength", "MODERATE");
        policy.putArray("criterionIds").add(Pm4CriterionEvaluator.CRITERION);
        policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY_ENGINEERING_NOT_SCIENTIFIC_APPROVAL");
        policy.putArray("scientificSources").add(source());
        policy.putArray("authoritySourceIds").add("authority-acmg-amp-2015");
        policy.putObject("scope").put("hgncId", "HGNC:4827").put("mondoId", "MONDO:0000001")
                .put("moi", "AUTOSOMAL_RECESSIVE").put("assembly", "GRCh38")
                .put("variantType", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT").put("transcriptMode", "EXACT_ACCESSIONS")
                .putArray("transcripts").addObject().put("accession", "ENST000001").put("version", 1);
        bind();
    }

    public void bind() {
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein")) regions.set(field, model.path(field));
        regions.put("modelSha256", adapter.canonicalSha256(model));
        policy.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", adapter.canonicalSha256(model));
        policy.putObject("regionMaterialBinding").put("materialId", regions.path("materialId").asText())
                .put("materialVersion", regions.path("materialVersion").asText()).put("canonicalSha256", adapter.canonicalSha256(regions));
    }

    public ObjectNode annotation(String coding, String protein, String consequence) {
        return mapper.createObjectNode().put("geneSymbol", model.path("geneSymbol").asText())
                .put("transcriptAccession", model.path("transcript").asText())
                .put("hgvsCoding", model.path("transcript").asText() + ":c." + coding)
                .put("hgvsProtein", protein == null ? null : model.path("protein").asText() + ":p." + protein)
                .put("consequence", consequence).put("exon", "1/1").put("source", "VEP_SYNTHETIC")
                .put("status", "COMPLETED").put("version", "116.0").put("timestamp", "2026-09-23T00:00:00Z").put("maneSelect", true);
    }

    public void variant(int genomic, String ref, String alt, String coding, String protein) throws Exception {
        var key = new NormalizedVariantKey("GRCh38", model.path("contig").asText(), genomic, ref, alt);
        candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation(coding, protein,
                ref.length() < alt.length() ? "inframe_insertion" : "inframe_deletion"), TranscriptConsequence.class)));
        String tx = model.path("transcript").asText(); int dot = tx.lastIndexOf('.');
        context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key, tx.substring(0, dot));
        ((ObjectNode) context.path("gene")).put("hgncId", model.path("hgncId").asText()).put("symbol", model.path("geneSymbol").asText());
        ((ObjectNode) context.path("disease")).put("mondoId", policy.at("/scope/mondoId").asText()).put("label", "TEST_ONLY");
        context.put("moi", policy.at("/scope/moi").asText());
        ((ObjectNode) context.path("transcript")).put("version", Integer.parseInt(tx.substring(dot + 1))).put("annotationSourceVersion", "116.0");
        ((ObjectNode) context.path("variant")).put("variantType", ref.length() == alt.length() ? "SNV" : ref.length() > alt.length() ? "SMALL_DELETION" : "SMALL_INSERTION");
        context.put("contextId", adapter.canonicalContextId(context));
    }

    public ObjectNode document() {
        ObjectNode doc = ReviewedRuleContextPreparationTest.emptyBundleDocument(mapper, "1");
        doc.put("schemaVersion", "reviewed-rule-bundle-pm4-v2").put("bundleId", "TEST_ONLY_PM4_BUNDLE");
        doc.withArray("policies").add(policy.deepCopy());
        ObjectNode materials = (ObjectNode) doc.path("materials");
        materials.putObject("transcriptModels").set(adapter.canonicalSha256(model), model.deepCopy());
        materials.putObject("pm4Regions").set(adapter.canonicalSha256(regions), regions.deepCopy());
        ObjectNode profile = Hnf1aPvs1Runtime.loadProfile(mapper).deepCopy();
        profile.retain("resources", "registryBinding"); profile.put("vepResourceId", "VEP_116_GRCH38");
        materials.set("pm4ResourceProfile", profile);
        ObjectNode catalog = (ObjectNode) doc.path("catalog");
        catalog.putArray("sources").add(source());
        ObjectNode entry = catalog.withArray("entries").addObject().put("entryId", "TEST_ONLY_PM4_CLAUSE")
                .put("criterionId", Pm4CriterionEvaluator.CRITERION).put("searchStatus", "COMPLETE")
                .put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "TEST_ONLY engineering clause");
        entry.set("scope", policy.path("scope").deepCopy());
        entry.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
        entry.putArray("sourceIds").add("TEST_ONLY_PM4_SOURCE");
        entry.putObject("policyBinding").put("policyId", policy.path("policyId").asText())
                .put("policyVersion", policy.path("policyVersion").asText()).put("policySha256", adapter.canonicalSha256(policy));
        doc.put("catalogSha256", adapter.canonicalSha256(catalog));
        return doc;
    }

    public ReviewedRuleBundle bundle(ObjectNode doc) { return new ReviewedRuleBundle(mapper, doc, adapter.canonicalSha256(doc)); }
    public String canonicalSha256(com.fasterxml.jackson.databind.JsonNode node) { return adapter.canonicalSha256(node); }
    public ObjectNode evaluate() { return bundle(document()).evaluate(Pm4CriterionEvaluator.CRITERION, context, candidate); }
    private ObjectNode source() {
        return mapper.createObjectNode().put("sourceId", "TEST_ONLY_PM4_SOURCE").put("version", "1")
                .put("url", "https://example.invalid/test-only-pm4").put("sha256", "a".repeat(64));
    }
}
