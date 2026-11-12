package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

/** External author/reviewer test double. All records are invented SYNTHETIC / TEST_ONLY. */
public final class MissensePreparationFixture {
    public final Ps1TestCatalog fixture;
    public final ObjectMapper mapper;
    public final Path root, manifestPath;
    public final ObjectNode manifest;
    public MissensePreparationFixture(Path root, Ps1TestCatalog fixture, boolean pm5) throws Exception {
        this.root = root; this.fixture = fixture; mapper = fixture.mapper; Files.createDirectories(root);
        if (pm5) fixture.configurePm5();
        manifestPath = root.resolve("manifest.json");
        manifest = mapper.createObjectNode().put("schemaVersion", "missense-preparation-manifest-v1")
                .put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY").put("criterion", pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1);
        ObjectNode source = mapper.createObjectNode().put("schemaVersion", "synthetic-missense-source-v1").put("purpose", "TEST_ONLY")
                .put("content", "Invented sequences, independent assertions and paired RNA review; no scientific adoption");
        ObjectNode descriptor = file("source.json", source); String sourceSha = descriptor.path("canonicalSha256").asText();
        ObjectNode record = mapper.createObjectNode().put("sourceId", "TEST_ONLY").put("version", "synthetic-snapshot-v1")
                .put("url", "https://example.invalid/ps1-synthetic").put("sha256", sourceSha);
        descriptor.put("format", "JSON").put("sourceDigestKind", "CANONICAL_JSON").set("sourceRecord", record);
        manifest.putArray("sources").add(descriptor);
        manifest.set("comparatorProfile", file("profile.json", ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper)));
        ObjectNode header = mapper.createObjectNode().put("schemaVersion", pm5 ? "reviewed-rule-bundle-pm5-v4" : "reviewed-rule-bundle-ps1-v3")
                .put("bundleId", "TEST_ONLY_PREPARED_" + (pm5 ? "PM5" : "PS1")).put("bundleVersion", "1");
        header.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "TEST_ONLY_EXTERNAL");
        header.putObject("activation").put("automaticTrigger", false).put("classificationEnabled", false);
        manifest.set("bundleHeader", file("header.json", header));
        ObjectNode catalog = mapper.createObjectNode().put("schemaVersion", "acmg-specification-catalog-v1").put("catalogId", "TEST_ONLY_PREPARATION")
                .put("catalogVersion", "1").put("searchedAt", "2026-09-23T00:00:00Z").put("searchScope", "SYNTHETIC two exact backgrounds");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY_EXTERNAL"); catalog.putArray("sources").add(record);
        ArrayNode backgrounds = manifest.putArray("backgrounds"), entries = catalog.putArray("entries");
        for (int i = 0; i < 2; i++) {
            ObjectNode m = fixture.materials.get(i), p = fixture.policies.get(i);
            p.putArray("sources").add(record.deepCopy()); ((ObjectNode) m.path("nativeModel")).putArray("sourceRecords").add(record.deepCopy());
            // Synthetic raw runner and prepared proof use the same actual source snapshot digest.
            ((ObjectNode) p.path("splicePolicy")).put("resourceSha256", sourceSha);
            ((ObjectNode) m.path("spliceAnnotationProof")).put("resourceSha256", sourceSha).put("modelSha256", MissenseMaterialPreparation.DERIVE);
            for (String pin : List.of("nativeModelSha256", "annotationResourceSha256", "materialsSha256", "spliceAnnotationProofSha256", "comparatorProfileSha256")) p.put(pin, MissenseMaterialPreparation.DERIVE);
            if (m.has("substitutionMatrix")) ((ObjectNode) p.at("/rules/PM5")).put("matrixSha256", MissenseMaterialPreparation.DERIVE);
            ObjectNode b = backgrounds.addObject(), identity = b.putObject("background");
            for (String field : List.of("assembly", "contig", "hgncId", "geneSymbol", "transcript", "protein")) identity.set(field, m.path("nativeModel").path(field));
            identity.set("mondoId", p.at("/scope/mondoId")); identity.set("moi", p.at("/scope/moi"));
            ObjectNode metadata = m.deepCopy(); metadata.retain("schemaVersion", "materialId", "version", "review", "scope");
            b.set("material", file("material-" + i + ".json", metadata)); b.set("policy", file("policy-" + i + ".json", p));
            for (String component : List.of("nativeModel", "annotationResource", "referenceAudit", "rnaReview", "spliceAnnotationProof", "substitutionMatrix"))
                if (m.has(component)) b.set(component, file(component + "-" + i + ".json", m.path(component)));
            if (m.has("aminoAcidRelations")) { ObjectNode triples = mapper.createObjectNode(); triples.set("relations", m.path("aminoAcidRelations")); b.set("aminoAcidRelations", file("triples-" + i + ".json", triples)); }
            ObjectNode entry = entries.addObject().put("entryId", "TEST_ONLY_" + i).put("criterionId", manifest.path("criterion").asText())
                    .put("searchStatus", "COMPLETE").put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "TEST_ONLY");
            ObjectNode scope = p.path("scope").deepCopy(); scope.put("transcriptMode", "EXACT_ACCESSIONS"); entry.set("scope", scope);
            entry.putArray("sourceIds").add("TEST_ONLY"); entry.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY_EXTERNAL");
            entry.putObject("policyBinding").put("policyId", p.path("policyId").asText()).put("policyVersion", p.path("policyVersion").asText()).put("policySha256", MissenseMaterialPreparation.DERIVE);
        }
        fixture.spliceResourceSha256 = sourceSha; manifest.set("catalog", file("catalog.json", catalog)); save();
    }
    public ObjectNode file(String name, JsonNode value) throws Exception {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value); Files.write(root.resolve(name), bytes);
        return mapper.createObjectNode().put("path", name).put("rawSha256", Pm4MaterialPreparation.sha(bytes)).put("canonicalSha256", fixture.canonicalSha256(value));
    }
    public void replace(int index, String field, java.util.function.Consumer<ObjectNode> change) throws Exception {
        ObjectNode item = (ObjectNode) manifest.path("backgrounds").get(index); String name = item.path(field).path("path").asText();
        ObjectNode value = (ObjectNode) mapper.readTree(root.resolve(name).toFile()); change.accept(value); item.set(field, file(name, value)); save();
    }
    public void save() throws Exception { mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest); }
    public String pin() throws Exception { return Pm4MaterialPreparation.sha(Files.readAllBytes(manifestPath)); }
    public MissenseMaterialPreparation.Result prepare(Path output) throws Exception { save(); return new MissenseMaterialPreparation().prepare(manifestPath, pin(), output); }
    /** Test-authored external declaration binds a candidate excluding this declaration itself. */
    public void approve(Path draft) throws Exception {
        ObjectNode report = (ObjectNode) mapper.readTree(draft.resolve("preparation-report.json").toFile());
        if (!report.has("candidateSha256")) throw new IllegalStateException(report.toPrettyString());
        ObjectNode approval = mapper.createObjectNode().put("schemaVersion", "missense-preparation-approval-v1").put("status", "REVIEWED_IMPLEMENTED")
                .put("reviewId", "TEST_ONLY_EXTERNAL_REVIEWER").put("dataClass", "SYNTHETIC").put("criterion", manifest.path("criterion").asText())
                .put("candidateSha256", report.path("candidateSha256").asText());
        approval.set("backgrounds", report.path("backgrounds")); manifest.set("approval", file("approval.json", approval)); save();
    }
    public Path release(String name) throws Exception {
        manifest.remove("approval"); Path draft = root.resolve(name + "-draft"); var pending = prepare(draft);
        if (pending.ready() || !"EXTERNAL_APPROVAL_MISSING".equals(pending.report().path("reasonCode").asText())) throw new IllegalStateException(pending.report().toPrettyString());
        approve(draft); Path released = root.resolve(name); var result = prepare(released);
        if (!result.ready()) throw new IllegalStateException(result.report().toPrettyString()); return released.resolve("bundle.json");
    }
}
