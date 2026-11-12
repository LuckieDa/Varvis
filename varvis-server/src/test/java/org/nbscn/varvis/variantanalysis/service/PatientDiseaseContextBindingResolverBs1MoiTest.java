package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog;
import org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalogTest;
import org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeQualificationTest;
import org.nbscn.varvis.variantanalysis.provider.SexChromosomeRegionResolver;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic source envelopes only; no real disease or frequency adoption. */
class PatientDiseaseContextBindingResolverBs1MoiTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String GENE = "HGNC:999001", SYMBOL = "SYNTHETIC_GENE", DISEASE = "MONDO:0999001";
    private static final String SOURCE = "synthetic-parameters";

    @Test void onlyLosslessCatalogModesNormalizeAndGenericLabelsStayUnresolved() {
        for (String mode : List.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE", "Y_LINKED"))
            assertEquals(mode, PatientDiseaseContextBindingResolver.normalizeCatalogMoi(mode));
        assertEquals("AUTOSOMAL_DOMINANT", PatientDiseaseContextBindingResolver.normalizeCatalogMoi("AD"));
        assertEquals("AUTOSOMAL_RECESSIVE", PatientDiseaseContextBindingResolver.normalizeCatalogMoi("AR"));
        for (String unsupported : List.of("XL", "XD", "XR", "X_DOMINANT", "X_RECESSIVE", "X-linked", "hemizygous", "MT", "SD", "UD", "X_LINKED", "XLR_GUESSED"))
            assertNull(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(unsupported));
        assertNull(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(null));
    }

    @Test void backgroundEntryPreservesEveryExactModeAndDoesNotConfuseModelSupportWithRelationshipConfidence() throws Exception {
        for (String mode : List.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE", "Y_LINKED")) {
            var source = knowledge(mapper.createArrayNode().add(background(mode, DISEASE, "Definitive")));
            var base = resolver(mapper.createArrayNode());
            var expanded = base.withBs1Knowledge(mapper, source);
            var candidate = candidate(mode);
            var rows = expanded.referenceOptions(mapper, candidate).path("relationships");
            assertEquals(1, rows.size());
            assertEquals(mode, PatientDiseaseContextBindingResolver.normalizeCatalogMoi(rows.get(0).path("moi").asText()));
            assertEquals("Definitive", rows.get(0).path("classification").asText());
            assertTrue(rows.get(0).path("classificationIsGeneValidity").asBoolean());
            assertEquals(source.identity(), rows.get(0).path("relationshipSource"));
            assertEquals("READY", bind(expanded, mode, DISEASE).status());
            assertEquals(rows, expanded.withBs1Knowledge(mapper, source).referenceOptions(mapper, candidate).path("relationships"));
            assertTrue(base.referenceOptions(mapper, candidate).path("relationships").isEmpty());
        }
    }

    @Test void expertEntryKeepsCanonicalXldXlrAndYDistinctWithoutNullKeys() throws Exception {
        var xld = expert("X_LINKED_DOMINANT", "MONDO:0999101", SYMBOL);
        var xlr = expert("X_LINKED_RECESSIVE", "MONDO:0999102", SYMBOL);
        var y = expert("Y_LINKED", "MONDO:0999103", SYMBOL);
        var source = knowledge(mapper.createArrayNode(), xld, xlr, y);
        assertEquals(3, source.expertRelationships().size(), "Fixture must pass the actual policy/qualification entrance");
        var baseRows = mapper.createArrayNode().add(relationship("XL", "Definitive", "MONDO:0999101"));
        var expanded = resolver(baseRows).withBs1Knowledge(mapper, source);
        var rows = expanded.referenceOptions(mapper, candidate("X_LINKED_DOMINANT")).path("relationships");
        assertEquals(4, rows.size());
        for (var row : rows) if ("BS1_EXPERT_SPECIFICATION_SCOPE".equals(row.path("relationshipType").asText())) {
            assertFalse(row.path("classificationIsGeneValidity").asBoolean(true));
            assertTrue(PatientDiseaseContextBindingResolver.bs1ExpertScope(row));
        }
        assertEquals("READY", bind(expanded, "X_LINKED_DOMINANT", "MONDO:0999101").status());
        assertEquals("READY", bind(expanded, "X_LINKED_RECESSIVE", "MONDO:0999102").status());
        assertEquals("READY", bind(expanded, "Y_LINKED", "MONDO:0999103").status());
        assertEquals(rows, expanded.withBs1Knowledge(mapper, source).referenceOptions(mapper, candidate("X_LINKED_DOMINANT")).path("relationships"));
    }

    @Test void exactSourceWinsOverBroadXWithoutRewritingBroadOriginal() throws Exception {
        var original = relationship("XL", "Definitive", DISEASE);
        var source = knowledge(mapper.createArrayNode().add(background("X_LINKED_RECESSIVE", DISEASE, "Strong")));
        var expanded = resolver(mapper.createArrayNode().add(original)).withBs1Knowledge(mapper, source).withLocalInterpretationContexts();
        var binding = bind(expanded, "X_LINKED_RECESSIVE", DISEASE);
        assertEquals("READY", binding.status());
        assertEquals(1, binding.provenance().relationshipRecords().size());
        assertEquals("X_LINKED_RECESSIVE", binding.provenance().relationshipRecords().get(0).path("moi").asText());
        assertEquals(source.identity(), binding.provenance().clingenResourceIdentity());
        assertEquals(original, expanded.referenceOptions(mapper, candidate("X_LINKED_RECESSIVE")).path("relationships").get(0));
    }

    @Test void genericSourceAloneDoesNotCreateCanonicalBs1KnowledgeOrAR() throws Exception {
        var empty = knowledge(mapper.createArrayNode());
        var expanded = resolver(mapper.createArrayNode().add(relationship("XL", "Definitive", DISEASE))).withBs1Knowledge(mapper, empty);
        var rows = expanded.referenceOptions(mapper, candidate("X_LINKED_RECESSIVE")).path("relationships");
        assertEquals(1, rows.size()); assertEquals("XL", rows.get(0).path("moi").asText());
        assertEquals("CATALOG_MOI_UNMAPPABLE", bind(expanded, "X_LINKED_RECESSIVE", DISEASE).reasonCode());
        assertTrue(empty.expertRelationships().isEmpty());
    }

    @Test void refutedOrUnresolvedSourceCannotBeOverriddenByPublishedExpertScope() throws Exception {
        var scope = expert("X_LINKED_RECESSIVE", DISEASE, SYMBOL);
        var source = knowledge(mapper.createArrayNode(), scope);
        var base = resolver(mapper.createArrayNode().add(relationship("X_LINKED_RECESSIVE", "Refuted", DISEASE)));
        var expanded = base.withBs1Knowledge(mapper, source);
        var binding = bind(expanded, "X_LINKED_RECESSIVE", DISEASE);
        assertEquals("NOT_READY", binding.status()); assertEquals("CLINGEN_RELATIONSHIP_CONFLICT", binding.reasonCode());
        assertEquals(2, binding.provenance().relationshipRecords().size());
        assertEquals(2, expanded.withBs1Knowledge(mapper, source).referenceOptions(mapper, candidate("X_LINKED_RECESSIVE")).path("relationships").size());
        var broadRefuted = resolver(mapper.createArrayNode().add(relationship("XL", "Refuted", DISEASE)))
                .withBs1Knowledge(mapper, source).withLocalInterpretationContexts();
        assertEquals("CLINGEN_RELATIONSHIP_CONFLICT", bind(broadRefuted, "X_LINKED_RECESSIVE", DISEASE).reasonCode());
        var rejected = assertThrows(IllegalArgumentException.class, () ->
                knowledge(mapper.createArrayNode().add(background("X_LINKED_RECESSIVE", DISEASE, "Definitive", "Refuted")), scope));
        assertEquals("BS1_KNOWLEDGE_RELATIONSHIP_CONFLICT", rejected.getMessage());
    }

    @Test void explicitOpposedXModesRemainVisibleAndGenericXCannotBypassConflict() throws Exception {
        var source = knowledge(mapper.createArrayNode().add(background("X_LINKED_RECESSIVE", DISEASE, "Definitive")));
        var rows = mapper.createArrayNode().add(relationship("XL", "Definitive", DISEASE))
                .add(relationship("X_LINKED_DOMINANT", "Definitive", DISEASE));
        var expanded = resolver(rows).withBs1Knowledge(mapper, source).withLocalInterpretationContexts();
        assertEquals(3, expanded.referenceOptions(mapper, candidate("X_LINKED_RECESSIVE")).path("relationships").size());
        for (String mode : List.of("X_LINKED_RECESSIVE", "X_LINKED_DOMINANT")) {
            var binding = bind(expanded, mode, DISEASE);
            assertEquals("NOT_READY", binding.status()); assertEquals("CATALOG_MOI_CONFLICT", binding.reasonCode());
            assertEquals(3, binding.provenance().relationshipRecords().size());
        }
        var broadFallback = resolver(rows).withLocalInterpretationContexts();
        assertEquals("CATALOG_MOI_CONFLICT", bind(broadFallback, "X_LINKED_RECESSIVE", DISEASE).reasonCode());
    }

    @Test void validDifferentDiseaseAndAutosomalModesAreNotMergedOrBlocked() throws Exception {
        var backgrounds = mapper.createArrayNode().add(background("AUTOSOMAL_DOMINANT", DISEASE, "Definitive"))
                .add(background("AUTOSOMAL_RECESSIVE", DISEASE, "Strong"))
                .add(background("X_LINKED_DOMINANT", "MONDO:0999101", "Definitive"))
                .add(background("X_LINKED_RECESSIVE", "MONDO:0999102", "Definitive"));
        var source = knowledge(backgrounds);
        var expanded = resolver(mapper.createArrayNode()).withBs1Knowledge(mapper, source);
        assertEquals(4, expanded.referenceOptions(mapper, candidate("AUTOSOMAL_DOMINANT")).path("relationships").size());
        for (String mode : List.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE")) assertEquals("READY", bind(expanded, mode, DISEASE).status());
        assertEquals("READY", bind(expanded, "X_LINKED_DOMINANT", "MONDO:0999101").status());
        assertEquals("READY", bind(expanded, "X_LINKED_RECESSIVE", "MONDO:0999102").status());
        assertEquals("CLINGEN_COVERAGE_ABSENT", bind(expanded, "X_LINKED_RECESSIVE", "MONDO:0999199").reasonCode());
    }

    @Test void wrongGeneSymbolOrUntrustedSourceCannotBeUsedAsExactScope() throws Exception {
        var source = knowledge(mapper.createArrayNode(), expert("X_LINKED_RECESSIVE", DISEASE, "WRONG_SYMBOL"));
        assertTrue(resolver(mapper.createArrayNode()).withBs1Knowledge(mapper, source)
                .referenceOptions(mapper, candidate("X_LINKED_RECESSIVE")).path("relationships").isEmpty());
        var wrongGene = background("X_LINKED_RECESSIVE", DISEASE, "Definitive");
        wrongGene.put("hgncId", "HGNC:999002"); ((ObjectNode) wrongGene.at("/assertions/0")).put("hgncId", "HGNC:999002");
        assertTrue(resolver(mapper.createArrayNode()).withBs1Knowledge(mapper, knowledge(mapper.createArrayNode().add(wrongGene)))
                .referenceOptions(mapper, candidate("X_LINKED_RECESSIVE")).path("relationships").isEmpty());
        var bad = background("X_LINKED_RECESSIVE", DISEASE, "Definitive");
        ((ObjectNode) bad.at("/assertions/0")).put("sourceId", "unbound");
        assertThrows(IllegalArgumentException.class, () -> knowledge(mapper.createArrayNode().add(bad)));
    }

    @Test void knowledgeAdditionKeepsLocalContextFlagAndBothCallOrdersEquivalent() throws Exception {
        var source = knowledge(mapper.createArrayNode().add(background("AUTOSOMAL_DOMINANT", DISEASE, "Definitive")));
        var base = resolver(mapper.createArrayNode());
        var before = base.withLocalInterpretationContexts().withBs1Knowledge(mapper, source);
        var after = base.withBs1Knowledge(mapper, source).withLocalInterpretationContexts();
        var first = bind(before, "AUTOSOMAL_DOMINANT", DISEASE);
        var second = bind(after, "AUTOSOMAL_DOMINANT", DISEASE);
        assertEquals("READY", first.status());
        assertEquals(LocalInterpretationContextPolicy.ID, first.provenance().intakePolicyId());
        assertEquals(mapper.valueToTree(first), mapper.valueToTree(second));
        var ordinary = bind(base.withBs1Knowledge(mapper, source), "AUTOSOMAL_DOMINANT", DISEASE);
        assertEquals(PatientDiseaseContextBindingResolver.PROFILE_ID, ordinary.provenance().intakePolicyId());
        assertFalse(base.pp1SelectionContexts());
        assertFalse(before.pp1SelectionContexts());
    }

    @Test void knowledgeAdditionPreservesPp1SelectionWithoutOpeningItOnOrdinaryResolver() throws Exception {
        var source = knowledge(mapper.createArrayNode().add(background("AUTOSOMAL_DOMINANT", DISEASE, "Definitive")));
        var base = resolver(mapper.createArrayNode());
        var before = base.withPp1SelectionContexts().withBs1Knowledge(mapper, source);
        var after = base.withBs1Knowledge(mapper, source).withPp1SelectionContexts();
        var first = bind(before, "AUTOSOMAL_DOMINANT", "MONDO:0999199");
        var second = bind(after, "AUTOSOMAL_DOMINANT", "MONDO:0999199");
        assertTrue(before.pp1SelectionContexts()); assertTrue(after.pp1SelectionContexts());
        assertEquals("DECLARED_SCOPE_ONLY", first.status());
        assertEquals("CLINGEN_COVERAGE_ABSENT", first.reasonCode());
        assertTrue(before.acceptsPreparation(first));
        assertEquals(mapper.valueToTree(first), mapper.valueToTree(second));
        var ordinary = base.withBs1Knowledge(mapper, source);
        assertFalse(ordinary.pp1SelectionContexts()); assertFalse(ordinary.acceptsPreparation(first));
        assertEquals("NOT_READY", bind(ordinary, "AUTOSOMAL_DOMINANT", "MONDO:0999199").status());
        assertFalse(base.pp1SelectionContexts());
    }

    private ObjectNode background(String mode, String disease, String... classifications) {
        var b = mapper.createObjectNode().put("hgncId", GENE).put("mondoId", disease).put("moi", mode)
                .put("reasonCode", mode.startsWith("AUTOSOMAL_") ? "BS1_DISEASE_PARAMETERS_MISSING" : "BS1_MODEL_MOI_UNSUPPORTED");
        var assertions = b.putArray("assertions");
        for (String classification : classifications) assertions.add(relationship(mode, classification, disease).put("sourceId", SOURCE));
        return b;
    }

    private ObjectNode expert(String mode, String disease, String symbol) {
        var r = new Bs1KnowledgeCatalogTest().expertRecord(GENE, disease, symbol);
        r.put("recordId", "SYNTHETIC-" + disease + "-" + mode);
        r.withObject("/scope").putArray("modes").add(mode);
        var sex = r.withObject("/scope").putObject("sexChromosome").put("regionCatalogSha256", SexChromosomeRegionResolver.CATALOG_SHA256)
                .put("requiredDataset", "EXOMES").put("sourceId", SOURCE).put("sourceLocator", "/synthetic");
        sex.putArray("assemblies").add("GRCh38"); sex.putArray("contigs").add("Y_LINKED".equals(mode) ? "Y" : "X"); sex.putArray("regions").add("NON_PAR");
        var policy = r.withObject("expertPolicy");
        policy.withObject("scope").putArray("modes").add(mode); policy.withObject("scope").set("sexChromosome", sex.deepCopy());
        var statistic = policy.putObject("statistic").put("metricId", "gnomad.exomes.sex_ancestry_max_faf95")
                .put("unit", "fraction").put("confidence", "95_PERCENT_LOWER_BOUND").put("sourceTerm", "FAF95")
                .put("interpretation", "TARGET_ALT_FAF95").put("interpretationBasis", "SYNTHETIC explicitly selected source statistic")
                .put("sourceId", SOURCE).put("locator", "/synthetic");
        statistic.putArray("ancestries").add("nfe"); statistic.putArray("karyotypes").add("ALL");
        statistic.putObject("eligibleAn").put("operator", "GTE").put("decimal", "2000");
        var tier = policy.withObject("/tiers/0").put("moi", mode).put("sourceLocator", "/synthetic");
        tier.putArray("conditions").addObject().put("fact", "FAF95").put("operator", "GT").put("decimal", "0.002")
                .put("unit", "fraction").put("basis", "OFFICIAL_CONDITION").put("originalText", "SYNTHETIC finite frequency control")
                .put("sourceLocator", "/synthetic");
        policy.putArray("commonConditions");
        r.set("exceptionSet", policy.path("exceptionSet").deepCopy());
        Bs1KnowledgeQualificationTest.qualify(r);
        return r;
    }

    private Bs1KnowledgeCatalog knowledge(ArrayNode backgrounds, ObjectNode... records) throws Exception {
        var b = new Bs1KnowledgeCatalogTest().bundle(records); b.set("backgrounds", backgrounds);
        byte[] raw = mapper.writeValueAsBytes(b); Path path = temporary.resolve(UUID.randomUUID() + "-SYNTHETIC-knowledge.json");
        Files.write(path, raw); return Bs1KnowledgeCatalog.load(path, Bs1KnowledgeCatalog.sha(raw));
    }

    private ObjectNode relationship(String mode, String classification, String disease) {
        return mapper.createObjectNode().put("hgncId", GENE).put("symbol", SYMBOL).put("mondoId", disease)
                .put("diseaseLabel", "SYNTHETIC exact disease").put("moi", mode).put("classification", classification)
                .put("classificationDate", "2026-11-03").put("gcep", "SYNTHETIC_SOURCE")
                .put("reportLocator", "https://example.org/SYNTHETIC/source").put("sourceRow", 1);
    }

    @Test void exactYSourceIsRetainedWhileCurrentRankingNormalizationBoundaryStaysExplicit() throws Exception {
        var source=knowledge(mapper.createArrayNode().add(background("Y_LINKED",DISEASE,"Definitive")));
        var expanded=resolver(mapper.createArrayNode()).withBs1Knowledge(mapper,source).withLocalInterpretationContexts();
        var rows=expanded.referenceOptions(mapper,candidate("Y_LINKED")).path("relationships");
        assertTrue(rows.findValues("moi").stream().anyMatch(value->"Y_LINKED".equals(value.asText())));
        var result=bind(expanded,"Y_LINKED",DISEASE);
        assertEquals("NOT_READY",result.status());
        // Shared ExomiserCandidateFeatures preserves raw Y but currently has no normalized Y enum.
        // This is a software capability boundary for the chief, not missing independent MOI evidence.
        assertEquals("CANDIDATE_MOI_REQUIRED",result.reasonCode());
    }

    private VariantInterpretation candidate(String mode) {
        // Exomiser's finite vocabulary is independent of the patient/source canonical MOI.
        String rankingMoi = switch (mode) {
            case "X_LINKED_DOMINANT" -> "X_DOMINANT";
            case "X_LINKED_RECESSIVE" -> "X_RECESSIVE";
            default -> mode;
        };
        // The current Exomiser domain retains an unrecognized Y source token with no normalized value.
        String normalizedMoi = "Y_LINKED".equals(rankingMoi) ? null : rankingMoi;
        return PatientDiseaseContextBindingResolverTest.candidate(1, SYMBOL, "16", rankingMoi, rankingMoi, normalizedMoi);
    }

    private DiseaseContextBinding bind(PatientDiseaseContextBindingResolver resolver, String mode, String disease) {
        var candidate = candidate(mode);
        return resolver.resolve(candidate, List.of(PatientDiseaseContextBindingResolverTest.hypothesis(candidate,
                "SYNTHETIC-hypothesis", GENE, disease, mode))).bindings().get(0);
    }

    private PatientDiseaseContextBindingResolver resolver(ArrayNode rows) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        var crosswalk = mapper.createArrayNode();
        crosswalk.addObject().put("hgncId", GENE).put("ncbiGeneId", "16").put("symbol", SYMBOL).putArray("sourceRows").add(1);
        var manifest = mapper.createObjectNode().put("profileId", "PATIENT_DISEASE_BINDING_INTAKE").put("profileVersion", "1.0.0").put("parserVersion", "1.0.0");
        manifest.set("intakePolicy", mapper.readTree("""
                {"policyId":"PATIENT_DISEASE_BINDING_INTAKE","version":"1.0.0",
                 "readyClassifications":["Definitive","Strong","Moderate"],"reviewRequiredClassifications":["Limited"],
                 "notReadyClassifications":["Disputed","Refuted","No Known Disease Relationship",""],
                 "catalogMoiMappings":{"AD":"AUTOSOMAL_DOMINANT","AR":"AUTOSOMAL_RECESSIVE"},
                 "unmappedCatalogMoi":["XL","SD","UD","MT"],"claim":"VARVIS_BINDING_INTAKE_ONLY_NOT_PATIENT_DIAGNOSIS"}
                """));
        var resources = manifest.putObject("resources");
        resources.set("mane", resource(directory, crosswalk, "mane.json", true));
        resources.set("clingen", resource(directory, rows, "catalog.json", false));
        mapper.writeValue(directory.resolve("manifest.json").toFile(), manifest);
        return PatientDiseaseContextBindingResolver.load(mapper, directory.resolve("manifest.json"), directory.resolve("mane.json"), directory.resolve("catalog.json"));
    }

    private ObjectNode resource(Path directory, ArrayNode rows, String filename, boolean mane) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(rows); Files.write(directory.resolve(filename), bytes);
        var r = mapper.createObjectNode().put("resourceId", mane ? "MANE_GRCh38_SUMMARY" : "CLINGEN_GENE_DISEASE_VALIDITY")
                .put("resourceVersion", mane ? "1.5" : "SYNTHETIC-2026-11-03")
                .put("sourceUrl", mane ? "https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/release_1.5/MANE.GRCh38.v1.5.summary.txt.gz"
                        : "https://search.clinicalgenome.org/kb/gene-validity/download")
                .put("retrievedAt", "2026-11-03T00:00:00Z").put("fileDate", "2026-11-03")
                .put("rawSha256", "a".repeat(64)).put("headerSha256", "b".repeat(64))
                .put("derivedSha256", Bs1KnowledgeCatalog.sha(bytes)).put("derivedRecordCount", rows.size()).put("parserVersion", "1.0.0");
        if (mane) r.put("assembly", "GRCh38");
        return r;
    }
}
