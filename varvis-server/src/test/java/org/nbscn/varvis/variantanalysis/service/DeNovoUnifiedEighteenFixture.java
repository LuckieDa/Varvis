package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Genuine normal CURRENT18 composition around the accepted owned-reader CURRENT fixture.
 * The source observations, reference, publication and bounded population transport remain
 * explicitly synthetic. Companion inventories supply capabilities, never missing facts or
 * fabricated scores. The unrelated fictional PVS1 toy inventory retains its original gene,
 * transcript and allele identity: it provides no PVS1 source for the CDH1 test candidate.
 */
final class DeNovoUnifiedEighteenFixture implements AutoCloseable {
    final Path temporary, python, scripts;
    final DeNovoCurrentBusinessFixture owned;
    final AcmgBusinessIntegrationTest base;
    final Ba1BusinessIntegrationTest populationFixture;
    final AcmgBusinessService population;
    final Pvs1UnifiedEighteenFixture pvs1;
    final UnifiedEighteenCompanionFixtures companions;
    final Path literatureWorkflow;
    final List<DeNovoSelectedBatch> modules;
    final List<DeNovoLiteratureReader> readers;
    final Map<String, Integer> factories = new LinkedHashMap<>();
    AcmgBusinessService current, legacy;
    AcmgBusinessWorkflows workflows;
    DeNovoBatchFixture materials;
    JsonNode source;

    DeNovoUnifiedEighteenFixture(Path temporary, Path python, Path scripts) throws Exception {
        owned = new DeNovoCurrentBusinessFixture(temporary, python, scripts);
        this.temporary = owned.temporary; this.python = owned.python; this.scripts = owned.scripts;
        base = owned.base; populationFixture = owned.populationFixture; population = owned.population;
        modules = owned.modules; readers = owned.readers; materials = owned.materials;
        // A valid, source-qualified existing test inventory is reused without relabelling
        // its fictional gene/models as the unrelated CURRENT CDH1 target.
        pvs1 = new Pvs1UnifiedEighteenFixture(base.mapper, temporary.resolve("p"), "NMD");
        for (var model : pvs1.data.models)
            if ("HGNC:1748".equals(model.path("hgncId").asText())
                    || "NM_004360.5".equals(model.path("transcript").asText()))
                throw new IllegalArgumentException("TEST_ONLY_COMPANION_INVENTORY_MUST_NOT_ASSERT_CDH1_SOURCE");
        companions = new UnifiedEighteenCompanionFixtures(base.mapper, temporary.resolve("c"), pvs1);
        literatureWorkflow = companions.prepareEmptyLiteratureWorkflow(this.scripts, this.python);
        install(); confirmCurrent();
    }

    void prepare(String kind, String scenario) throws Exception {
        // The accepted source writer creates originals and invokes LocalIdentity before
        // registration/replay. No source/core facts are rewritten to obtain a result.
        owned.prepare(kind, scenario); materials = owned.materials; source = owned.source;
        install(); confirmCurrent();
    }

    void install() throws Exception {
        var populationSettings = populationFixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString());
        populationSettings.getGnomad().setBa1LocalExomesEnabled(true);
        populationSettings.getGnomad().setGrch38Files(String.join(",", List.of("11", "13").stream()
                .map(c -> temporary.resolve("gnomad.exomes.v4.1.sites.chr" + c + ".vcf.bgz").toString()).toList()));
        var localSettings = new VariantAnalysisProperties();
        localSettings.getClinVar().setEnabled(true);
        localSettings.getClinVar().setGrch38File(temporary.resolve("missing-clinvar.vcf.gz").toString());
        localSettings.getSpliceAi().setEnabled(true);
        localSettings.getSpliceAi().setGrch38File(temporary.resolve("missing-spliceai.vcf.gz").toString());
        localSettings.getAlphaMissense().setEnabled(true);
        localSettings.getAlphaMissense().setGrch38File(temporary.resolve("missing-alphamissense.tsv.gz").toString());
        var rarity = new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity() { return populationFixture.ba1.identity(); }
            public Pm2LocalExomesRuntime create() { return new Pm2LocalExomesRuntime(base.mapper, populationSettings); }
        };
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
                    module = DeNovoSelectedBatch.load(base.mapper, manifest,
                            DeNovoBatchFixture.sha(Files.readAllBytes(manifest)),
                            DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, reader);
                }
                modules.add(module);
                var c = new UnifiedEighteenCompanionFixtures(base.mapper, temporary.resolve("c"), pvs1);
                return new LocalEvidenceBatchRuntime(base.mapper,
                        base.mapper.createObjectNode().put("dataClass", "SYNTHETIC_TEST_ONLY")
                                .put("fixture", "DENOVO_CURRENT18_NORMAL_CONSTRUCTION")
                                .put("pvs1BundleRawSha256", pvs1.bundleRawSha256)
                                .put("pvs1KnowledgeSha256", pvs1.knowledgeAdoptionSha256),
                        () -> opened("PM2", c.pm2Factory(populationSettings).get()),
                        c.spliceFactory(localSettings), c.predictionFactory(localSettings, effects),
                        c.reference(localSettings), c.bp7(),
                        () -> opened("PM4", c.pm4()), () -> opened("PVS1", pvs1.selectedBatch()),
                        () -> opened("BP3", c.bp3()), () -> opened("PM1", c.pm1()),
                        () -> opened("PP2", c.pp2(false)), () -> opened("BP1", c.bp1(effects, pin)),
                        false, module, c.pm3(literatureWorkflow, rarity), c.pp1(literatureWorkflow));
            } catch (Exception invalid) { throw new IllegalArgumentException("TEST_ONLY_CURRENT18_CONFIGURATION_FAILED", invalid); }
        }, temporary.resolve("reviews18"));
        legacy = local; current = AcmgBusinessService.unifiedLocal(population, local);
        if (current.configuredCriteria().size() != 18)
            throw new IllegalArgumentException("TEST_ONLY_NORMAL_CURRENT18_COMPOSITION_REQUIRED");
        workflows = new AcmgBusinessWorkflows(current, legacy);
        base.service = current; base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows)).build();
    }

    private <T> T opened(String name, T value) {
        factories.merge(name, 1, (existingCount, addedCount) -> existingCount + addedCount); return value;
    }

    void confirmCurrent() throws Exception {
        // Its confirmation uses actual options from the current service, not a hand-filled
        // profile or synthetic recommendation. Keep the delegate on the real composition.
        owned.current = current; owned.legacy = legacy; owned.workflows = workflows;
        owned.confirmCurrent();
        // CURRENT10's helper declares its narrower scope; the expanded composition must
        // explicitly confirm the declaration returned by its own normal input-options.
        var options = workflows.inputOptions(base.analysisId, 1, "CURRENT");
        String declaration = options.path("requiredDeclaration").asText();
        if (declaration.isBlank()) throw new IllegalArgumentException("TEST_ONLY_CURRENT18_DECLARATION_REQUIRED");
        base.request.put("declarationVersion", declaration);
    }

    ObjectNode target() { return owned.target(); }
    int evaluations() { return owned.evaluations(); }
    int readerExecutions() { return owned.readerExecutions(); }
    long pp1Evaluations() throws Exception {
        Path directory = companions.root.resolve("empty-literature-project/local/work/pp1-evaluations");
        if (!Files.isDirectory(directory)) return 0;
        try (var paths = Files.list(directory)) { return paths.filter(Files::isDirectory).count(); }
    }
    @Override public void close() { owned.close(); }
}
