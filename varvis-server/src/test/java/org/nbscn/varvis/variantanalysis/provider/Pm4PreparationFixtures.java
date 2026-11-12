package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** External author/reviewer stand-in only: SYNTHETIC / TEST_ONLY, never an approval operation in production. */
public final class Pm4PreparationFixtures {
    public final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    public final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    public final List<Pm4TestFixtures> fixtures = new ArrayList<>();
    public final Path root;
    public ObjectNode manifest, approval, catalog;
    public Pm4PreparationFixtures(Path root) throws Exception {
        this.root = root; Files.createDirectories(root);
        for (int i = 0; i < 2; i++) {
            var f = new Pm4TestFixtures(mapper, 1); fixtures.add(f);
            f.model.put("schemaVersion", "pvs1-transcript-model-v1").put("reviewStatus", "MECHANICALLY_VERIFIED_PENDING_POLICY_REVIEW")
                    .put("modelId", "TEST_ONLY_MODEL_" + i).put("contig", i == 0 ? "11" : "13")
                    .put("hgncId", i == 0 ? "HGNC:4827" : "HGNC:4284").put("geneSymbol", i == 0 ? "HBB" : "GJB2")
                    .put("transcript", i == 0 ? "ENST00000647020.1" : "ENST00000382848.5").put("protein", "ENSP00000" + (i + 1) + ".1");
            f.regions.put("materialId", "TEST_ONLY_REGION_" + i); f.policy.put("policyId", "TEST_ONLY_POLICY_" + i);
            ObjectNode scope = (ObjectNode) f.policy.path("scope");
            scope.put("hgncId", f.model.path("hgncId").asText()).put("mondoId", i == 0 ? "MONDO:0013517" : "MONDO:0019497");
            scope.putArray("transcripts").addObject().put("accession", i == 0 ? "ENST00000647020" : "ENST00000382848").put("version", i == 0 ? 1 : 5);
            var rawSource = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY")
                    .put("sourceType", "INVENTED_LOCAL_MODEL_AND_REGION_ASSERTION_SOURCE").put("cdsSequence", Pm4TestFixtures.CDS);
            rawSource.set("assertedIntervals", f.regions.path("intervals").deepCopy());
            write("source-" + i + ".json", rawSource);
            var source = mapper.createObjectNode().put("sourceId", "TEST_ONLY_SOURCE_" + i).put("version", "1")
                    .put("url", "https://example.invalid/test-only-pm4/" + i)
                    .put("sha256", Pm4MaterialPreparation.sha(Files.readAllBytes(root.resolve("source-" + i + ".json"))));
            f.model.putArray("sourceRecords").add(source); f.regions.putArray("sourceRecords").add(source.deepCopy());
            f.policy.putArray("scientificSources").add(source.deepCopy()); f.bind();
        }
        publishReviewedInputs();
    }
    public void publishReviewedInputs() throws Exception {
        manifest = mapper.createObjectNode().put("schemaVersion", "pm4-preparation-manifest-v1").put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY");
        approval = mapper.createObjectNode().put("schemaVersion", "pm4-preparation-approval-v1").put("status", "REVIEWED_IMPLEMENTED")
                .put("reviewId", "TEST_ONLY_EXTERNAL_REVIEW").put("dataClass", "SYNTHETIC");
        ObjectNode header = approval.putObject("bundle").put("schemaVersion", "reviewed-rule-bundle-pm4-v2")
                .put("bundleId", "TEST_ONLY_PREPARED_PM4").put("bundleVersion", "1");
        header.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY_EXTERNAL_REVIEW");
        header.putObject("activation").put("automaticTrigger", false).put("classificationEnabled", false);
        catalog = mapper.createObjectNode().put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "TEST_ONLY_PM4_PREPARATION").put("catalogVersion", "1")
                .put("searchedAt", "2026-09-24T00:00:00Z").put("searchScope", "TEST_ONLY_TWO_EXACT_BACKGROUNDS");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY_EXTERNAL_REVIEW");
        catalog.putArray("sources"); catalog.putArray("entries");
        for (int i = 0; i < fixtures.size(); i++) {
            var f = fixtures.get(i);
            // External source author updates the snapshot first; the preparer never performs this step.
            ObjectNode source = ((ObjectNode) f.model.at("/sourceRecords/0")).deepCopy();
            var sourceDocument = mapper.createObjectNode().put("schemaVersion", "pm4-region-source-v1").put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY")
                    .put("sourceId", source.path("sourceId").asText()).put("version", source.path("version").asText())
                    .put("assembly", "GRCh38").put("contig", f.model.path("contig").asText()).put("cdsSequence", f.model.path("cdsSequence").asText());
            sourceDocument.set("assertedIntervals", f.regions.path("intervals").deepCopy());
            write("source-" + i + ".json", sourceDocument);
            source.put("sha256", Pm4MaterialPreparation.sha(Files.readAllBytes(root.resolve("source-" + i + ".json"))));
            f.model.putArray("sourceRecords").add(source); f.regions.putArray("sourceRecords").add(source.deepCopy());
            f.policy.putArray("scientificSources").add(source.deepCopy()); f.bind();
            ObjectNode b = mapper.createObjectNode();
            for (String key : List.of("assembly", "contig", "hgncId", "geneSymbol", "transcript", "protein")) b.set(key, f.model.path(key));
            b.set("mondoId", f.policy.at("/scope/mondoId")); b.set("moi", f.policy.at("/scope/moi"));
            ObjectNode a = mapper.createObjectNode().put("schemaVersion", "pm4-region-assertions-v1").put("coordinateSystem", "ONE_BASED_CLOSED")
                    .put("materialId", f.regions.path("materialId").asText()).put("materialVersion", f.regions.path("materialVersion").asText());
            for (String key : List.of("assembly", "contig", "hgncId", "transcript", "protein")) a.set(key, f.model.path(key));
            a.putObject("coverage").put("start", 100).put("end", 120);
            for (JsonNode row : f.regions.path("intervals")) {
                var assertion = ((ObjectNode) row).deepCopy().put("sourceLocator", "/assertedIntervals/" + a.withArray("assertions").size())
                        .put("reviewId", "TEST_ONLY_EXTERNAL_REVIEW");
                assertion.putArray("sourceIds").add("TEST_ONLY_SOURCE_" + i); a.withArray("assertions").add(assertion);
            }
            if (!a.has("assertions")) a.putArray("assertions");
            ObjectNode entry = manifest.withArray("backgrounds").addObject(); entry.set("background", b);
            entry.set("model", pinned("model-" + i + ".json", f.model)); entry.set("regionAssertions", pinned("assertions-" + i + ".json", a));
            entry.set("policy", pinned("policy-" + i + ".json", f.policy));
            entry.putArray("sources").addObject().put("path", "source-" + i + ".json").put("sourceDigestKind", "RAW_BYTES")
                    .put("rawSha256", source.path("sha256").asText()).put("format", "JSON").put("canonicalSha256", hash(sourceDocument)).set("sourceRecord", source.deepCopy());
            catalog.withArray("sources").add(source.deepCopy());
            ObjectNode clause = catalog.withArray("entries").addObject().put("entryId", "TEST_ONLY_" + i).put("criterionId", Pm4CriterionEvaluator.CRITERION)
                    .put("searchStatus", "COMPLETE").put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "TEST_ONLY");
            clause.set("scope", f.policy.path("scope").deepCopy()); clause.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY_EXTERNAL_REVIEW");
            clause.putArray("sourceIds").add(source.path("sourceId"));
            clause.putObject("policyBinding").put("policyId", f.policy.path("policyId").asText()).put("policyVersion", f.policy.path("policyVersion").asText()).put("policySha256", hash(f.policy));
            approval.withArray("bindings").addObject().put("backgroundSha256", hash(b)).put("modelSha256", hash(f.model))
                    .put("regionAssertionsSha256", hash(a)).put("regionMaterialSha256", hash(f.regions)).put("policySha256", hash(f.policy)).put("allowUnknownCoverage", true);
        }
        ObjectNode profile = Hnf1aPvs1Runtime.loadProfile(mapper).deepCopy(); profile.retain("resources", "registryBinding"); profile.put("vepResourceId", "VEP_116_GRCH38");
        manifest.set("resourceProfile", pinned("resource-profile.json", profile));
        manifest.set("catalog", pinned("catalog.json", catalog)); approval.put("catalogSha256", hash(catalog)).put("resourceProfileSha256", hash(profile));
        manifest.set("approval", pinned("approval.json", approval)); saveManifest();
    }
    public String hash(JsonNode value) { return adapter.canonicalSha256(value); }
    public ObjectNode pinned(String name, ObjectNode value) throws Exception {
        write(name, value); return mapper.createObjectNode().put("path", name).put("rawSha256", Pm4MaterialPreparation.sha(Files.readAllBytes(root.resolve(name))))
                .put("canonicalSha256", hash(value));
    }
    public void write(String name, ObjectNode value) throws Exception { Files.write(root.resolve(name), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    public void saveManifest() throws Exception { write("preparation.json", manifest); }
    public String manifestSha() throws Exception { return Pm4MaterialPreparation.sha(Files.readAllBytes(root.resolve("preparation.json"))); }
    public Pm4MaterialPreparation.Result prepare(Path output) throws Exception { return new Pm4MaterialPreparation().prepare(root.resolve("preparation.json"), manifestSha(), output); }
}
