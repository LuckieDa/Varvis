package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** TEST_ONLY: two independently fixed tiny CDSs, manual substitutions and invented reviewed clauses. */
public final class Pm1GeneralTestFixture {
    public static final String FIXED_RESOURCE = "/acmg/pm1-general-fixed-facts-test-only.json";
    public final ObjectMapper mapper;
    public final List<ObjectNode> policies = new ArrayList<>(), models = new ArrayList<>(), regions = new ArrayList<>();
    public final ObjectNode profile;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode fixed;

    public Pm1GeneralTestFixture(ObjectMapper mapper) throws Exception {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        try (var input = getClass().getResourceAsStream(FIXED_RESOURCE)) {
            fixed = (ObjectNode) mapper.readTree(Objects.requireNonNull(input, FIXED_RESOURCE));
        }
        profile = Hnf1aPvs1Runtime.loadProfile(mapper).deepCopy();
        // Only the unchanged shared registry/VEP identity is reused; no HNF1A scientific policy.
        profile.retain("resources", "registryBinding"); profile.put("vepResourceId", "VEP_116_GRCH38");
        for (int i = 0; i < fixed.path("backgrounds").size(); i++) add(i);
    }

    private void add(int index) {
        JsonNode background = background(index), declaration = background.path("scientificDeclaration");
        ObjectNode source = source(index);
        ObjectNode model = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", background.path("name").asText() + "_MODEL").put("modelVersion", "1")
                .put("assembly", "GRCh38").put("contig", background.path("contig").asText())
                .put("strand", background.path("strand").asInt()).put("hgncId", background.path("hgnc").asText())
                .put("geneSymbol", background.path("gene").asText()).put("transcript", background.path("transcript").asText())
                .put("protein", background.path("protein").asText()).put("cdsSequence", background.path("cds").asText())
                .put("cdsLength", background.path("cds").asText().length()).put("proteinLength", background.path("proteinLength").asInt());
        model.putArray("sourceRecords").add(source.deepCopy());
        model.putArray("exons").addObject().put("ordinal", 1)
                .put("genomicStart", background.path("genomicStart").asInt()).put("genomicEnd", background.path("genomicEnd").asInt())
                .put("cdsStart", 1).put("cdsEnd", background.path("cds").asText().length());
        models.add(model);
        ObjectNode policy = mapper.createObjectNode().put("schemaVersion", "pm1-protein-region-policy-v1")
                .put("policyId", background.path("name").asText() + "_POLICY").put("policyVersion", "1")
                .put("branch", "NATIVE_MISSENSE_PROTEIN_REGION")
                .put("mechanism", declaration.path("mechanism").asText()).put("consequenceScope", declaration.path("consequenceScope").asText())
                .put("protein", background.path("protein").asText());
        policy.putArray("criterionIds").add("ACMG_AMP_2015:PM1");
        policy.putArray("scientificSources").add(source.deepCopy());
        policy.putArray("authoritySourceIds").add(source.path("sourceId").asText());
        String transcript = background.path("transcript").asText(); int dot = transcript.lastIndexOf('.');
        policy.putObject("scope").put("hgncId", background.path("hgnc").asText()).put("mondoId", background.path("mondo").asText())
                .put("moi", "AUTOSOMAL_RECESSIVE").put("assembly", "GRCh38")
                .put("variantType", "NATIVE_NONPROJECTED_SNV").put("transcriptMode", "EXACT_ACCESSIONS")
                .putArray("transcripts").addObject().put("accession", transcript.substring(0, dot))
                .put("version", Integer.parseInt(transcript.substring(dot + 1)));
        policies.add(policy);
        ObjectNode material = mapper.createObjectNode().put("schemaVersion", "pm1-protein-region-material-v1")
                .put("materialId", background.path("name").asText() + "_REGIONS").put("materialVersion", "1");
        material.putArray("sourceRecords").add(source.deepCopy());
        ArrayNode coverage = material.putArray("reviewedCoverage");
        for (JsonNode row : background.path("coverage")) coverage.addObject().put("start", row.path(0).asInt()).put("end", row.path(1).asInt());
        ArrayNode rules = material.putArray("rules");
        for (JsonNode row : background.path("rules")) {
            ObjectNode rule = rules.addObject().put("ruleId", row.path(0).asText()).put("start", row.path(1).asInt()).put("end", row.path(2).asInt())
                    .put("regionType", row.path(4).asText()).put("effect", row.path(5).asText()).put("strength", row.path(6).asText())
                    .put("qualification", declaration.path("qualification").asText())
                    .put("pathogenicDistribution", declaration.path("pathogenicDistribution").asText())
                    .put("benignDistribution", declaration.path("benignDistribution").asText());
            if (!row.path(3).isNull()) rule.put("referenceAminoAcid", row.path(3).asText());
            review(rule, rule.deepCopy(), index, "TEST_ONLY fixed clause " + row.path(0).asText());
        }
        ArrayNode precedence = material.putArray("precedence");
        for (JsonNode row : background.path("precedence")) {
            ObjectNode relation = precedence.addObject().put("higherRuleId", row.path(0).asText()).put("lowerRuleId", row.path(1).asText());
            review(relation, relation.deepCopy(), index, "TEST_ONLY explicit exclusion takes priority");
        }
        regions.add(material); bind(index);
    }

    public ObjectNode fixedFacts() { return fixed.deepCopy(); }
    public JsonNode background(int index) { return fixed.path("backgrounds").path(index).deepCopy(); }
    public String genomicSequence(int index) { return background(index).path("genomicSequence").asText(); }
    public ObjectNode row(String id) {
        for (JsonNode row : fixed.path("variants")) if (id.equals(row.path("id").asText())) return (ObjectNode) row.deepCopy();
        throw new IllegalArgumentException("Unknown fixed row " + id);
    }
    public String targetId(int index) { return background(index).path("target").asText(); }
    public TranscriptConsequence annotation(int index) throws Exception { return annotation(targetId(index)); }
    public TranscriptConsequence annotation(String id) throws Exception { return mapper.treeToValue(row(id).path("annotation"), TranscriptConsequence.class); }
    public VariantInterpretation target(int index) throws Exception { return variant(targetId(index)); }
    public VariantInterpretation variant(String id) throws Exception {
        ObjectNode row = row(id); JsonNode allele = row.path("allele");
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key(allele));
        candidate.applyTranscriptAnnotations(List.of(annotation(id))); return candidate;
    }
    public ObjectNode context(int index) throws Exception { return context(targetId(index)); }
    public ObjectNode context(String id) throws Exception {
        JsonNode row = row(id), background = background(row.path("background").asInt());
        String transcript = background.path("transcript").asText(); int dot = transcript.lastIndexOf('.');
        ObjectNode result = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key(row.path("allele")), transcript.substring(0, dot));
        ((ObjectNode) result.path("gene")).put("hgncId", background.path("hgnc").asText()).put("symbol", background.path("gene").asText());
        ((ObjectNode) result.path("disease")).put("mondoId", background.path("mondo").asText()).put("label", "TEST_ONLY invented PM1 scope");
        result.put("moi", "AUTOSOMAL_RECESSIVE");
        ((ObjectNode) result.path("transcript")).put("version", Integer.parseInt(transcript.substring(dot + 1)))
                .put("annotationSourceVersion", "116.0");
        refreshContext(result); return result;
    }
    public void refreshContext(ObjectNode context) { context.put("contextId", adapter.canonicalContextId(context)); }
    public String canonicalSha256(JsonNode node) { return adapter.canonicalSha256(node); }
    public void bind(int index) {
        ObjectNode model = models.get(index), material = regions.get(index), policy = policies.get(index);
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein")) material.set(field, model.path(field).deepCopy());
        material.put("modelSha256", canonicalSha256(model));
        ObjectNode reviewedScope = material.deepCopy(); reviewedScope.retain("modelSha256", "assembly", "contig", "hgncId", "transcript", "protein", "reviewedCoverage");
        if (!material.has("review")) review(material, reviewedScope, index, "TEST_ONLY coverage and all fixed protein regions");
        else ((ObjectNode) material.path("review")).put("scopeSha256", canonicalSha256(reviewedScope));
        if (!policy.has("review")) review(policy, policy.path("scope"), index, "TEST_ONLY exact disease and inheritance scope");
        else ((ObjectNode) policy.path("review")).put("scopeSha256", canonicalSha256(policy.path("scope")));
        policy.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", canonicalSha256(model));
        policy.putObject("regionMaterialBinding").put("materialId", material.path("materialId").asText())
                .put("materialVersion", material.path("materialVersion").asText()).put("canonicalSha256", canonicalSha256(material));
    }
    /** Explicit test author re-review after an intentional mutation; not a production approval function. */
    public void reReview(ObjectNode rule, int index) {
        ObjectNode scoped = rule.deepCopy(); scoped.remove("review");
        review(rule, scoped, index, "TEST_ONLY explicitly mutated negative-control clause");
    }
    private void review(ObjectNode node, JsonNode scope, int index, String clause) {
        node.putObject("review").put("status", background(index).at("/scientificDeclaration/reviewStatus").asText())
                .put("reviewId", "TEST_ONLY_NOT_SCIENTIFIC_APPROVAL_" + index).put("clause", clause)
                .put("scopeSha256", canonicalSha256(scope)).putArray("sourceIds").add(source(index).path("sourceId").asText());
    }
    public ObjectNode source(int index) {
        return mapper.createObjectNode().put("sourceId", background(index).at("/scientificDeclaration/authoritySourceId").asText()).put("version", "1")
                .put("url", "https://example.invalid/pm1-test-only/background-" + index)
                .put("locator", "pm1-general-fixed-facts-test-only.json#/backgrounds/" + index)
                .put("sha256", canonicalSha256(background(index)));
    }
    public ObjectNode document() {
        ObjectNode result = ReviewedRuleContextPreparationTest.emptyBundleDocument(mapper, "1");
        result.put("schemaVersion", "reviewed-rule-bundle-pm1-v5").put("bundleId", "TEST_ONLY_PM1_TWO_STRANDS");
        ObjectNode materials = (ObjectNode) result.path("materials");
        ObjectNode modelMap = materials.putObject("transcriptModels"), regionMap = materials.putObject("pm1Regions");
        materials.set("pm1ResourceProfile", profile.deepCopy());
        ObjectNode catalog = (ObjectNode) result.path("catalog"); ArrayNode sources = catalog.putArray("sources");
        for (int index = 0; index < models.size(); index++) {
            bind(index); ObjectNode policy = policies.get(index);
            sources.add(source(index)); result.withArray("policies").add(policy.deepCopy());
            modelMap.set(canonicalSha256(models.get(index)), models.get(index).deepCopy());
            regionMap.set(canonicalSha256(regions.get(index)), regions.get(index).deepCopy());
            ObjectNode entry = catalog.withArray("entries").addObject().put("entryId", "TEST_ONLY_PM1_ENTRY_" + index)
                    .put("criterionId", "ACMG_AMP_2015:PM1").put("searchStatus", "COMPLETE")
                    .put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "TEST_ONLY reviewed bounded protein clause");
            entry.set("scope", policy.path("scope").deepCopy());
            entry.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
            entry.putArray("sourceIds").add(source(index).path("sourceId").asText());
            entry.putObject("policyBinding").put("policyId", policy.path("policyId").asText())
                    .put("policyVersion", policy.path("policyVersion").asText()).put("policySha256", canonicalSha256(policy));
        }
        result.put("catalogSha256", canonicalSha256(catalog)); return result;
    }
    public ReviewedRuleBundle load(Path root, ObjectNode document) throws Exception {
        Files.createDirectories(root); Path file = root.resolve("test-only-pm1-bundle.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
        return ReviewedRuleBundle.load(mapper, file, canonicalSha256(document));
    }
    public ReviewedRuleBundle bundle(ObjectNode document) { return new ReviewedRuleBundle(mapper, document, canonicalSha256(document)); }
    private static NormalizedVariantKey key(JsonNode allele) {
        return new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(),
                allele.path("position").asInt(), allele.path("reference").asText(), allele.path("alternate").asText());
    }
}
