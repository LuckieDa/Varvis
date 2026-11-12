package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual CURRENT composition; publication, reference, parameters and transport are synthetic.
 * Scientific event observations are read from registered originals by the owned Python reader. */
final class DeNovoCurrentBusinessFixture implements AutoCloseable {
    final Path temporary, python, scripts;
    final AcmgBusinessIntegrationTest base;
    final Ba1BusinessIntegrationTest populationFixture;
    final Bs1KnowledgeCatalog populationKnowledge;
    final List<DeNovoSelectedBatch> modules = new ArrayList<>();
    final List<DeNovoLiteratureReader> readers = new ArrayList<>();
    AcmgBusinessService population, current, legacy;
    AcmgBusinessWorkflows workflows;
    DeNovoBatchFixture materials;
    JsonNode source;

    DeNovoCurrentBusinessFixture(Path temporary, Path python, Path scripts) throws Exception {
        this.temporary = temporary; this.python = python.toAbsolutePath().normalize(); this.scripts = scripts.toAbsolutePath().normalize();
        Files.createDirectories(temporary);
        base = new AcmgBusinessIntegrationTest(); base.temporary = temporary; base.setup();
        var normalization = new TopNSelectedNormalizationTest(); normalization.temporary = temporary;
        base.producer = normalization.fixture("ATCG").producer();
        base.variantTsvOverride = tsv("1\t11\t2\tT\tA\tCDH1\t999\tAD\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7");
        base.syntheticPublicationEnrichment = rows -> {
            for (var candidate : rows) if ("CDH1".equals(candidate.getExomiserCandidateFeatures().getGeneSymbol()))
                candidate.applyTranscriptAnnotations(List.of(new TranscriptConsequence("CDH1", "999", "NM_004360.5",
                        "NM_004360.5:c.20A>T", "NP_004351.1:p.Glu7Val", "missense_variant")
                        .withAnnotationMetadata("VEP_SYNTHETIC", "COMPLETED", "1", java.time.Instant.parse("2026-09-01T00:00:00Z"))));
        };
        base.publish(false, GenomeAssembly.GRCh38, null);
        populationFixture = new Ba1BusinessIntegrationTest(); populationFixture.base = base; populationFixture.temporary = temporary;
        populationFixture.am = new BusinessAlphaMissenseFixture();
        var snapshot = populationFixture.syntheticCatalog("SYNTHETIC-CURRENT-DENOVO");
        var mappings = snapshot.putArray("geneMappings");
        for (var candidate : base.jobs.findInterpretations(base.analysisId, 0, 2)) {
            String symbol = candidate.getExomiserCandidateFeatures().getGeneSymbol();
            String gene = "CDH1".equals(symbol) ? "HGNC:1748" : "HGNC:4284";
            var mapping = mappings.addObject().put("hgncId", gene); mapping.putArray("geneSymbols").add(symbol);
            var ids = mapping.putArray("ensemblGeneIds");
            for (var annotation : candidate.getTranscriptAnnotations()) {
                String id = annotation.getGeneId();
                if (id != null && !id.isBlank() && !ids.findValuesAsText("").contains(id)) ids.add(id);
            }
        }
        snapshot.putObject("geneMappingSource").put("status", "COMPLETE").put("rawSha256", "b".repeat(64))
                .put("url", "https://example.test/synthetic-current-denovo-mapping").put("recordCount", mappings.size());
        var catalog = new Ba1SpecificationCatalog(base.mapper, snapshot);
        populationFixture.configureCatalog(catalog);
        // Existing bounded local-exomes transport pattern; these files are deliberately not real gnomAD resources.
        Path command = temporary.resolve("synthetic-tabix.cmd");
        Files.writeString(command, Files.readString(command).replace("_joint", "")
                .replace("faf95=0.054", "faf95=0.055").replace("faf95_nfe=0.054", "faf95_nfe=0.055"));
        var settings = populationFixture.populationSettings(command.toString()); settings.getGnomad().setBa1LocalExomesEnabled(true);
        var files = new ArrayList<String>();
        for (String contig : List.of("11", "13")) {
            Path vcf = temporary.resolve("gnomad.exomes.v4.1.sites.chr" + contig + ".vcf.bgz");
            Files.writeString(vcf, "SYNTHETIC bounded population transport"); Files.writeString(Path.of(vcf + ".tbi"), "SYNTHETIC index");
            files.add(vcf.toString());
        }
        settings.getGnomad().setGrch38Files(String.join(",", files));
        populationFixture.ba1 = new Ba1AlleleRuntime(base.mapper, settings, catalog);
        populationFixture.ba1Binding = populationFixture.ba1.identity(); populationFixture.wire();
        // The CURRENT population component is genuine, but no new BS1 disease threshold is invented for this test.
        Path knowledge = temporary.resolve("current-population-knowledge.json");
        byte[] knowledgeBytes = base.mapper.writeValueAsBytes(new Bs1KnowledgeCatalogTest().bundle()); Files.write(knowledge, knowledgeBytes);
        populationKnowledge = Bs1KnowledgeCatalog.load(knowledge, Bs1KnowledgeCatalog.sha(knowledgeBytes));
        population = AcmgBusinessService.formalPopulation(base.service,
                () -> Bs1Runtime.localExomes(base.mapper, new VariantAnalysisProperties(), true, populationKnowledge));
        materials = new DeNovoBatchFixture(temporary.resolve("initial-materials"));
        install(); confirmCurrent();
    }

    void install() throws Exception {
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population, (effects, pin) -> {
            try {
                DeNovoSelectedBatch module;
                if (source == null) module = materials.load();
                else {
                    Path index = materials.root.resolve("model-artifacts.json");
                    var reader = new DeNovoLiteratureReader(base.mapper, python, scripts, temporary.resolve("reader-cache"),
                            index, DeNovoBatchFixture.sha(Files.readAllBytes(index)))
                            .withCoreContext(materials.root, materials.root.resolve(source.at("/coreContext/path").asText()),
                                    source.at("/coreContext/sha256").asText());
                    readers.add(reader);
                    Path manifest = materials.root.resolve("manifest.json");
                    module = DeNovoSelectedBatch.load(base.mapper, manifest, DeNovoBatchFixture.sha(Files.readAllBytes(manifest)),
                            DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, reader);
                }
                modules.add(module);
                var bp7 = new Bp7SelectedBatch(base.mapper, new SplicingExperimentChecks(base.mapper,
                        new ClassPathResource("acmg/public-splicing-experiments-v1.json"), temporary.resolve("experiments"),
                        (analysis, run) -> { throw new AssertionError("Catalog capture must not start analysis"); }).captureCatalog());
                return new LocalEvidenceBatchRuntime(base.mapper, base.mapper.createObjectNode().put("syntheticTest", "denovo-current-business"),
                        null, null, null, null, bp7, null, null, null, null, null, null, false, module);
            } catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
        }, temporary.resolve("reviews"));
        legacy = local;
        current = AcmgBusinessService.unifiedLocal(population, local); workflows = new AcmgBusinessWorkflows(current, legacy);
        base.service = current; base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows)).build();
    }

    void confirmCurrent() throws Exception {
        base.request.put("topN", 1).put("declarationVersion", AcmgBusinessRuleScope.UNIFIED_LOCAL_DECLARATION);
        ((ObjectNode) base.request.path("patient")).put("familyContextId", DeNovoBatchFixture.FAMILY);
        var options = workflows.inputOptions(base.analysisId, 1, "CURRENT");
        var choices = base.request.putArray("recommendations"); var hypotheses = base.request.putArray("hypotheses");
        assertEquals(1, options.path("candidates").size(), options.toPrettyString());
        var row = options.path("candidates").get(0); var rec = row.path("recommendation");
        JsonNode relation = null, annotation = null;
        for (var r : rec.path("relationships")) if (r.path("selectable").asBoolean()
                && "AD".equals(r.path("moi").asText()) && "HGNC:1748".equals(r.path("hgncId").asText())) { relation = r; break; }
        for (var a : rec.path("annotations")) if (a.path("contextSelectable").asBoolean()) { annotation = a; break; }
        assertNotNull(relation, rec.toPrettyString()); assertNotNull(annotation, rec.toPrettyString());
        var choice = choices.addObject();
        for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) choice.set(key, rec.path(key));
        choice.set("stableRelationshipKey", relation.path("stableRelationshipKey")); choice.set("annotationId", annotation.path("annotationId"));
        var declarationSource = base.mapper.createObjectNode().put("source", "EXPLICIT_USER_DECLARATION").put("version", "1.0.0")
                .put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
        var item = hypotheses.addObject().put("affectedStatus", "AFFECTED");
        var h = item.putObject("hypothesis"); h.set("candidateId", row.path("candidateId")); h.set("hypothesisId", rec.path("hypothesisId"));
        for (String key : List.of("hgncId", "mondoId", "moi")) h.set(key, relation.path(key)); h.set("provenance", declarationSource);
        String transcript = annotation.path("versionedTranscriptId").asText(); int dot = transcript.lastIndexOf('.');
        var tx = item.putObject("transcript").put("accession", transcript.substring(0, dot)).put("version", Integer.parseInt(transcript.substring(dot + 1)))
                .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                .put("selectionReason", "SYNTHETIC explicit CURRENT context; evidence source qualifications remain independent");
        tx.set("annotationSource", annotation.path("annotationSource")); tx.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
        tx.set("source", declarationSource);
    }

    ObjectNode target() {
        var candidate = base.jobs.findInterpretations(base.analysisId, 0, 1).get(0); var allele = candidate.getObservation().getKey();
        var h = base.request.at("/hypotheses/0/hypothesis");
        var target = base.mapper.createObjectNode().put("hgncId", h.path("hgncId").asText())
                .put("geneSymbol", candidate.getExomiserCandidateFeatures().getGeneSymbol())
                .put("diseaseId", h.path("mondoId").asText()).put("inheritance", "AD");
        target.putObject("allele").put("assembly", allele.getGenomeAssembly()).put("contig", allele.getChromosome())
                .put("position", allele.getPosition()).put("reference", allele.getReference()).put("alternate", allele.getAlternate());
        return target;
    }

    void prepare(String kind, String scenario) throws Exception {
        Path targetFile = temporary.resolve("current-source-target.json"); Files.write(targetFile, base.mapper.writeValueAsBytes(target()));
        Path output = temporary.resolve("m");
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                "from test_current_business_fixture import write_java_fixture; import sys; write_java_fixture(sys.argv[1],sys.argv[2],kind=sys.argv[3],scenario=sys.argv[4])",
                output.toString(), targetFile.toString(), kind, scenario), scripts, Duration.ofSeconds(60));
        assertTrue(process.isSuccess(), process.getOutput());
        source = base.mapper.readTree(Files.readAllBytes(output.resolve("java-round4-fixture.json")));
        assertEquals("NOT_RUN", source.path("criterionDecision").asText(), "Source preparation must not fabricate an evaluation");
        materials = new DeNovoBatchFixture(output); materials.phenotype.removeAll().setAll((ObjectNode) source.path("policy"));
        var scope = source.at("/policy/scope"); var allele = target().path("allele");
        var reportTarget = (ObjectNode) materials.report.path("target"); reportTarget.removeAll().setAll((ObjectNode) allele);
        reportTarget.put("hgncId", scope.path("hgncId").asText());
        String expert = materials.phenotype.path("expertPolicyStatus").asText();
        for (var entry : materials.catalog.path("entries")) {
            var object = (ObjectNode) entry; setScope((ObjectNode) object.path("scope"), scope, allele);
            object.put("disposition", "APPLICABLE_IMPLEMENTED".equals(expert) ? "SPECIALIZED_IMPLEMENTED" : "GENERAL_CLAUSE_ALLOWED");
            object.putArray("sourceIds").add("paper");
            ((ObjectNode) object.path("generalPrerequisites")).putArray("sourceIds").add("paper");
        }
        for (var policy : materials.selectorPolicies.path("policies")) setScope((ObjectNode) policy.path("scope"), scope, allele);
        byte[] knowledge = source.at("/policySources/paper").asText().getBytes(StandardCharsets.UTF_8);
        Files.write(output.resolve("source.txt"), knowledge); String pin = DeNovoBatchFixture.sha(knowledge);
        materials.catalog.putArray("sources").addObject().put("sourceId", "paper").put("url", "https://example.invalid/SYNTHETIC-current-rule")
                .put("version", "1").put("sha256", pin);
        var sources = materials.manifest.putArray("sources");
        sources.addObject().put("sourceId", "paper").put("path", "source.txt").put("sha256", pin);
        sources.addObject().put("sourceId", "registered-paper").put("path", "synthetic-literature.xml").put("sha256", source.path("sourceSha256").asText());
        materials.save();
        assertEquals(source.at("/core/policySha256"), materials.manifest.at("/phenotypePolicies/0/sha256"));
        materials.manifest.putArray("reports");
        materials.manifest.putArray("literature").addObject().put("sourceId", "registered-paper").put("pmcid", source.path("pmcid").asText()).putArray("alleles");
        materials.writeManifest();
        install(); confirmCurrent();
    }

    private static void setScope(ObjectNode target, JsonNode source, JsonNode allele) {
        target.put("hgncId", source.path("hgncId").asText()).put("mondoId", source.path("diseaseId").asText())
                .put("moi", "AUTOSOMAL_DOMINANT").put("assembly", allele.path("assembly").asText());
    }

    int evaluations() { return modules.stream().mapToInt(mappedItem -> mappedItem.evaluations()).sum(); }
    int readerExecutions() { return readers.stream().mapToInt(mappedItem -> mappedItem.executions()).sum(); }
    @Override public void close() { base.closeDatabase(); }
}
