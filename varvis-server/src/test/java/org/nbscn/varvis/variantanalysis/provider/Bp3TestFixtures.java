package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

/** TEST_ONLY: invented 21-base CDS (MEPGFK*), synthetic policy and region assertions. */
public final class Bp3TestFixtures {
    public final ObjectMapper mapper;
    public final RuntimeEvidenceV2Adapter adapter;
    public final ObjectNode model, regions, policy;
    public ObjectNode context;
    public VariantInterpretation candidate;
    public static final String CDS = "ATGGAACCTGGCTTTAAATAA";

    public Bp3TestFixtures(ObjectMapper mapper, int strand) {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        model = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "TEST_ONLY_BP3_MODEL").put("modelVersion", "1").put("assembly", "GRCh38")
                .put("contig", "11").put("strand", strand).put("hgncId", "HGNC:999001").put("geneSymbol", "TEST_BP3_PLUS")
                .put("transcript", "ENST000001.1").put("protein", "ENSP000001.1")
                .put("cdsSequence", CDS).put("cdsLength", 21).put("proteinLength", 6);
        model.putArray("sourceRecords").add(source());
        model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 120)
                .put("cdsStart", 1).put("cdsEnd", 21);
        regions = mapper.createObjectNode().put("schemaVersion", "bp3-region-material-v1")
                .put("materialId", "TEST_ONLY_BP3_REGIONS").put("materialVersion", "1");
        regions.putArray("sourceRecords").add(source());
        regions.putArray("intervals").addObject().put("regionId", "TEST_ONLY_REPEAT").put("start", 2).put("end", 5)
                .put("repeatStatus", "REPEAT").put("functionalStatus", "NO_KNOWN_FUNCTION");
        policy = mapper.createObjectNode().put("schemaVersion", "bp3-simple-inframe-policy-v1")
                .put("policyId", "TEST_ONLY_BP3_POLICY").put("policyVersion", "1")
                .put("branch", "SIMPLE_INFRAME_INDEL_REVIEWED_REPEAT").put("appliedStrength", "SUPPORTING");
        policy.putArray("criterionIds").add(Bp3CriterionEvaluator.CRITERION);
        policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY_ENGINEERING_NOT_SCIENTIFIC_APPROVAL");
        policy.putArray("scientificSources").add(source());
        policy.putArray("authoritySourceIds").add("authority-acmg-amp-2015");
        policy.putObject("scope").put("hgncId", "HGNC:999001").put("mondoId", "MONDO:0999001")
                .put("moi", "AUTOSOMAL_DOMINANT").put("assembly", "GRCh38")
                .put("variantType", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT").put("transcriptMode", "EXACT_ACCESSIONS")
                .putArray("transcripts").addObject().put("accession", "ENST000001").put("version", 1);
        regions.put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE");
        policy.put("allowBp3", true);
        // Published general authority identity; all region/model/applicability assertions remain TEST_ONLY.
        policy.withArray("scientificSources").add(mapper.createObjectNode().put("sourceId", "authority-acmg-amp-2015-richards")
                .put("version", "2015").put("url", "https://pmc.ncbi.nlm.nih.gov/articles/PMC4544753/")
                .put("sha256", "7ec25cf2d54ce079251bdcfee92dcbf12b25117ab93998e54e86e8e9186145b3")
                .put("locator", "PMCID:PMC4544753 Tables 3 and 4"));
        policy.putArray("authoritySourceIds").add("authority-acmg-amp-2015-richards");
        if (strand == -1) {
            model.put("cdsSequence", "ATGGAACCTCCTGGCTTTAAATAA").put("cdsLength", 24).put("proteinLength", 7)
                    .put("contig", "13").put("hgncId", "HGNC:999002").put("geneSymbol", "TEST_BP3_MINUS")
                    .put("transcript", "ENST000002.1").put("protein", "ENSP000002.1").put("modelId", "TEST_ONLY_BP3_MINUS");
            ((ObjectNode) model.at("/exons/0")).put("genomicEnd", 123).put("cdsEnd", 24);
            policy.put("policyId", "TEST_ONLY_BP3_MINUS_POLICY").put("policyVersion", "2");
            policy.withObject("scope").put("hgncId", "HGNC:999002").put("mondoId", "MONDO:0999002");
            ((ObjectNode) policy.at("/scope/transcripts/0")).put("accession", "ENST000002");
            regions.put("materialId", "TEST_ONLY_BP3_MINUS_REGIONS");
        }
        bind(); review(); bind();
    }

    public void review() {
        for (var value : regions.path("intervals")) {
            ObjectNode row = (ObjectNode) value;
            row.putArray("sourceIds").add("TEST_ONLY_BP3_SOURCE");
            ObjectNode content = regions.deepCopy(); content.remove("intervals");
            ObjectNode facts = row.deepCopy(); facts.remove("functionalReview");
            content.set("region", facts); content.set("scope", policy.path("scope").deepCopy());
            row.putObject("functionalReview").put("status", "REVIEWED_APPLICABLE")
                    .put("reviewer", "TEST_ONLY_SYNTHETIC_REVIEWER_NOT_EXPERT_APPROVAL")
                    .put("evaluatedAt", "2026-09-27").put("basis", "TEST_ONLY independent applicability declaration")
                    .put("contentSha256", adapter.canonicalSha256(content)).putArray("sourceIds").add("TEST_ONLY_BP3_SOURCE");
        }
    }
    public void bind() {
        policy.put("cdsSha256", ProteinLengthChangeObservation.sha(model.path("cdsSequence").asText()))
                .put("proteinSha256", ProteinLengthChangeObservation.sha(ProteinLengthChangeObservation.translate(model.path("cdsSequence").asText())));
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
        doc.put("schemaVersion", "reviewed-rule-bundle-bp3-v7").put("bundleId", "TEST_ONLY_BP3_BUNDLE");
        doc.withArray("policies").add(policy.deepCopy());
        ObjectNode materials = (ObjectNode) doc.path("materials");
        materials.putObject("transcriptModels").set(adapter.canonicalSha256(model), model.deepCopy());
        materials.putObject("bp3Regions").set(adapter.canonicalSha256(regions), regions.deepCopy());
        ObjectNode profile = Hnf1aPvs1Runtime.loadProfile(mapper).deepCopy();
        profile.retain("resources", "registryBinding"); profile.put("vepResourceId", "VEP_116_GRCH38");
        try {
            String path = "/acmg/rule-registry-v2/acmg-rule-registry-bp3-inframe-2026-09-27.json";
            byte[] bytes; try (var input = java.util.Objects.requireNonNull(getClass().getResourceAsStream(path))) { bytes = input.readAllBytes(); }
            profile.putObject("registryBinding").put("registryClasspathResource", path)
                    .put("registryId", "acmg-rule-registry-bp3-inframe-2026-09-27")
                    .put("registrySha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .putArray("bp3AuthoritySourceIds").add("authority-acmg-amp-2015-richards");
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
        materials.set("bp3ResourceProfile", profile);
        ObjectNode catalog = (ObjectNode) doc.path("catalog");
        catalog.putArray("sources").add(source());
        ObjectNode entry = catalog.withArray("entries").addObject().put("entryId", "TEST_ONLY_BP3_CLAUSE")
                .put("criterionId", Bp3CriterionEvaluator.CRITERION).put("searchStatus", "COMPLETE")
                .put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "TEST_ONLY engineering clause");
        entry.set("scope", policy.path("scope").deepCopy());
        entry.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
        entry.putArray("sourceIds").add("TEST_ONLY_BP3_SOURCE");
        entry.putObject("policyBinding").put("policyId", policy.path("policyId").asText())
                .put("policyVersion", policy.path("policyVersion").asText()).put("policySha256", adapter.canonicalSha256(policy));
        doc.put("catalogSha256", adapter.canonicalSha256(catalog));
        return doc;
    }

    public ReviewedRuleBundle bundle(ObjectNode doc) { return new ReviewedRuleBundle(mapper, doc, adapter.canonicalSha256(doc)); }
    public String canonicalSha256(com.fasterxml.jackson.databind.JsonNode node) { return adapter.canonicalSha256(node); }
    public ObjectNode evaluate() { return bundle(document()).evaluate(Bp3CriterionEvaluator.CRITERION, context, candidate); }
    private ObjectNode source() {
        return mapper.createObjectNode().put("sourceId", "TEST_ONLY_BP3_SOURCE").put("version", "1")
                .put("locator", "TEST_ONLY_RECORD_1").put("url", "https://example.invalid/test-only-bp3").put("sha256", "a".repeat(64));
    }
}
