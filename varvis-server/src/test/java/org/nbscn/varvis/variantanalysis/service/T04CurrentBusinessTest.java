package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual submit/HTTP/H2/current-source/actual-core/Python/read/full-export chain.
 * Ranking, annotation, reference, clinical originals and bounded population transport are
 * explicitly SYNTHETIC engineering inputs. No source seam supplies a verdict or an award.
 * This is product-route developer verification, not user browser/download acceptance.
 */
@EnabledIfSystemProperty(named = "varvis.t04.guidance-xml", matches = ".+")
@EnabledIfSystemProperty(named = "varvis.pp1.python", matches = ".+")
class T04CurrentBusinessTest {
    private static final String PP1 = "ACMG_AMP_2015:PP1";
    private static final Set<String> POPULATION = Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1");
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;

    @Test void pp1SingleUsesActualSubmitArchiveReadAndReuse() throws Exception {
        try (var f = new BusinessFixture(temporary.resolve("single"))) {
            verify(f, "single", List.of(PP1));
            rejectCoherentlyResavedMissingArchive(f);
        }
    }
    @Test void relatedAndCurrentEighteenUseTheSameRegisteredScientificPipeline() throws Exception {
        try (var f = new BusinessFixture(temporary.resolve("related-all"))) {
            verify(f, "related", List.of(PP1, "ACMG_AMP_2015:PS2", "ACMG_AMP_2015:PM6"));
            verify(f, "all", null);
            verifyNonPp1Selection(f);
        }
    }

    @Test void standardNativeAssociationUsesTheSameSingleRelatedAndEighteenPath() throws Exception {
        try(var f=new BusinessFixture(temporary.resolve("native-standard"),true)) {
            verify(f,"native-single",List.of(PP1));verify(f,"native-related",List.of(PP1,"ACMG_AMP_2015:PS2","ACMG_AMP_2015:PM6"));verify(f,"native-all",null);
            assertTrue(f.registered.identity().has("clinicalSampleSource"));
            assertEquals(11,f.registered.archiveArtifacts().size());
        }
    }

    @Test void nativeSourceNamespaceUsesActualSingleRelatedAndEighteenBusinessExecution() throws Exception {
        try(var f=new BusinessFixture(temporary.resolve("native-ids"),true,true)) {
            verify(f,"alias-single",List.of(PP1));verify(f,"alias-related",List.of(PP1,"ACMG_AMP_2015:PS2","ACMG_AMP_2015:PM6"));verify(f,"alias-all",null);
        }
    }

    private void verify(BusinessFixture f, String mode, List<String> selected) throws Exception {
        var base = f.base; ObjectNode request = base.request.deepCopy(); request.remove("selectedCriteria");
        if (selected != null) request.set("selectedCriteria", base.mapper.valueToTree(selected));
        var expected = selected == null ? new LinkedHashSet<>(base.service.configuredCriteria()) : new LinkedHashSet<>(selected);
        var rankingBefore = base.mapper.readTree(base.mapper.writeValueAsBytes(base.jobs.findInterpretations(base.analysisId, 0, 10)));
        String preclaimSelectedRanking = base.mapper.writeValueAsString(base.jobs.findInterpretations(base.analysisId, 0, request.path("topN").asInt()));
        int priorPp1 = f.pp1Source.evaluations(), priorDeNovo = f.deNovoEvaluations(), priorRuns = base.store.runs(base.analysisId).size();
        ObjectNode result = f.submit(request);
        assertNotEquals("FAILED", result.path("status").asText(), result.toPrettyString());
        assertEquals(priorRuns + 1, base.store.runs(base.analysisId).size());
        assertEquals(priorPp1 + 1, f.pp1Source.evaluations(), "One actual Python replay for this claimed background");
        assertEquals(priorDeNovo + 1, f.deNovoEvaluations(), "One actual shared de novo core; dependency is not a second criterion worker");
        var offered = new LinkedHashSet<String>(); result.path("enabledCriteria").forEach(id -> offered.add(id.asText()));
        assertEquals(expected, offered); assertFalse(offered.contains("ACMG_AMP_2015:PP4"));
        if (selected == null) assertEquals(18, offered.size(), "The ordinary composition, not a filled profile, supplies CURRENT18");
        var expectedLocal = new LinkedHashSet<>(expected); expectedLocal.removeAll(POPULATION);
        var localRows = new LinkedHashSet<String>();
        for (var row : result.at("/candidates/0/attempts/0/localBatch/criteria")) assertTrue(localRows.add(row.path("criterionId").asText()), "No duplicate criterion rows");
        assertEquals(expectedLocal, localRows); assertFalse(localRows.contains("ACMG_AMP_2015:PP4"));

        JsonNode pp1 = f.pp1(result);
        f.diagnoseFailure(mode, request, result, pp1, preclaimSelectedRanking);
        // Independent expectation: two concordant siblings give BF=(3/4)^2/(1/2)^2=9/4;
        // (52/25)^1 <= 9/4 < (52/25)^2, so exactly one PP1 Supporting point.
        assertEquals("9", pp1.at("/bayesFactor/numerator").asText(), pp1.toPrettyString());
        assertEquals("4", pp1.at("/bayesFactor/denominator").asText());
        assertEquals("MET", pp1.path("status").asText()); assertEquals("SUPPORTING", pp1.path("strength").asText());
        assertEquals(1, pp1.path("awardedPP1Points").asInt(-1));
        assertEquals("KNOWN_EMPTY", pp1.at("/ledger/status").asText()); assertEquals("0", pp1.at("/ledger/pp4Points").asText());

        String runId = result.path("runId").asText(); var stored = base.store.run(base.analysisId, runId).orElseThrow();
        JsonNode savedPrivate = base.mapper.readTree(stored.privateJson());
        JsonNode inventory = savedPrivate.path("currentClinicalInventory"), receipt = inventory.at("/attempts/0/terminalReceipt");
        assertTrue(inventory.path("complete").asBoolean(), inventory.toPrettyString());
        assertEquals("NOT_SELECTED", receipt.path("kind").asText()); assertEquals("0", receipt.path("finalPoints").asText());
        assertTrue(receipt.path("sourceResultSha256").isNull()); assertTrue(receipt.path("evaluationSha256").isNull());
        assertEquals("MET", receipt.at("/coreExecution/actualStatus").asText());
        assertEquals("0", receipt.at("/currentAdoption/awardedPoints").asText());
        assertTrue(receipt.at("/currentAdoption/allocatedEventIds").isEmpty()); assertTrue(receipt.at("/currentAdoption/allocatedFactIds").isEmpty());
        assertEquals("RESERVED_NOT_AWARDED", receipt.at("/currentUseGrant/status").asText());
        assertTrue(receipt.at("/currentUseGrant/awardedPoints").isNull()); assertFalse(receipt.at("/currentUseGrant/reservedFactIds").isEmpty());
        JsonNode call = receipt.at("/currentUseGrant/nativeCallProof");
        assertEquals(1, call.path("sampleOrdinal").asInt()); assertEquals(1, call.path("recordOrdinal").asInt());
        assertEquals(1, call.path("targetAlleleIndex").asInt()); assertEquals("0/1", call.path("genotype").asText());
        assertEquals(2, call.path("ploidy").asInt()); assertEquals(40, call.path("depth").asInt());
        assertEquals(60, call.path("genotypeQuality").asInt()); assertEquals("20,20", call.path("alleleDepth").asText());
        assertEquals("PASS", call.path("recordFilter").asText()); assertEquals("PASS", call.path("sampleFilter").asText());
        assertEquals(f.nativeVcfSha, call.path("vcfRawSha256").asText());
        assertEquals("ALL_ATTEMPTS_STAGE1_COMPLETE_BEFORE_PP1", savedPrivate.at("/localBatch/currentConsumerExecution/phase").asText());
        assertEquals(0, savedPrivate.at("/localBatch/currentConsumerExecution/pp1EvaluatorCallsBeforeBind").asInt(-1));
        assertEquals(1, savedPrivate.at("/localBatch/modules/pp1/evaluatorCalls").asInt(-1));
        JsonNode archive = savedPrivate.path("currentClinicalArchive");
        assertEquals(archive.path("archiveSha256"), result.path("currentClinicalArchiveSha256"));
        ObjectNode expanded = CurrentClinicalArchive.verifyAndExpand(base.mapper, archive, inventory, base.analysisId, runId);
        assertEquals(expanded, result.path("currentClinicalEvidence"));
        assertTrue(expanded.path("historyOnly").asBoolean()); assertFalse(expanded.path("authorityRestorationSupported").asBoolean(true));
        assertFalse(archive.path("materialOriginals").isEmpty()); assertFalse(archive.path("rawArtifacts").isEmpty());
        assertEquals(rankingBefore, base.mapper.readTree(base.mapper.writeValueAsBytes(base.jobs.findInterpretations(base.analysisId, 0, 10))));

        int callsBeforeRead = f.pp1Source.evaluations(), deNovoBeforeRead = f.deNovoEvaluations();
        long pythonBeforeRead = f.pythonEvaluations(); String queriesBeforeRead = f.populationQueries();
        assertEquals(result, f.submit(request)); assertEquals(result, base.service.read(base.analysisId, runId));
        String root = "/variant-analyses/" + base.analysisId + "/acmg/runs/" + runId;
        byte[] full = base.http.perform(get(root)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(result, base.mapper.readTree(full));
        JsonNode index = base.mapper.readTree(base.http.perform(get(root + "/view")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String sourceSha = index.path("sourceViewSha256").asText(); assertTrue(sourceSha.matches("[a-f0-9]{64}"));
        JsonNode background = base.mapper.readTree(base.http.perform(get(root + "/view/background")
                .param("candidateId", result.at("/candidateIds/0").asText()).param("backgroundIndex", "0").param("sourceSha256", sourceSha))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        AcmgSavedViewAssertions.attempt(result.at("/candidates/0/attempts/0"), background.at("/candidates/0/attempts/0"), result);
        base.http.perform(get(root + "/view/background").param("candidateId", result.at("/candidateIds/0").asText())
                .param("backgroundIndex", "0").param("sourceSha256", "0".repeat(64))).andExpect(status().isConflict());
        byte[] pinnedFull = base.http.perform(get(root).param("sourceViewSha256", sourceSha)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(full, pinnedFull, "Full export is the same verified saved document");
        base.http.perform(get(root).param("sourceViewSha256", "0".repeat(64))).andExpect(status().isConflict());
        assertEquals(stored, base.store.run(base.analysisId, runId).orElseThrow(), "Read/reuse/export must preserve saved public and private bytes");
        assertEquals(priorRuns + 1, base.store.runs(base.analysisId).size());
        assertEquals(callsBeforeRead, f.pp1Source.evaluations()); assertEquals(deNovoBeforeRead, f.deNovoEvaluations());
        assertEquals(pythonBeforeRead, f.pythonEvaluations()); assertEquals(queriesBeforeRead, f.populationQueries());
        f.record(mode, request, result, stored, index, background, full);
    }

    private void verifyNonPp1Selection(BusinessFixture f) throws Exception {
        var base = f.base;
        f.composition.owned.current = base.service; f.composition.owned.workflows = f.workflows; f.composition.owned.confirmCurrent();
        // The reused CURRENT10 confirmation helper declares its older narrow profile.
        // Confirm this request against the actual CURRENT18 composition being submitted.
        String declaration = f.workflows.inputOptions(base.analysisId, 1, "CURRENT").path("requiredDeclaration").asText();
        assertFalse(declaration.isBlank()); base.request.put("declarationVersion", declaration);
        ObjectNode request = base.request.deepCopy(); request.putArray("selectedCriteria").add("ACMG_AMP_2015:PM6");
        int pp1Before = f.pp1Source.evaluations(), deNovoBefore = f.deNovoEvaluations(); long pythonBefore = f.pythonEvaluations();
        var result = f.submit(request); assertNotEquals("FAILED", result.path("status").asText(), result.toPrettyString());
        assertEquals(List.of("ACMG_AMP_2015:PM6"), base.mapper.convertValue(result.path("enabledCriteria"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        JsonNode rows = result.at("/candidates/0/attempts/0/localBatch/criteria"); assertEquals(1, rows.size());
        assertEquals("ACMG_AMP_2015:PM6", rows.get(0).path("criterionId").asText());
        var stored = base.store.run(base.analysisId, result.path("runId").asText()).orElseThrow();
        JsonNode privateData = base.mapper.readTree(stored.privateJson());
        assertFalse(privateData.has("currentClinicalArchive")); assertFalse(privateData.has("currentClinicalInventory"));
        assertFalse(privateData.has("currentClinicalUse")); assertFalse(result.has("currentClinicalArchiveSha256")); assertFalse(result.has("currentClinicalEvidence"));
        assertEquals(pp1Before, f.pp1Source.evaluations()); assertEquals(pythonBefore, f.pythonEvaluations());
        assertEquals(deNovoBefore + 1, f.deNovoEvaluations());
        assertEquals(result, base.service.read(base.analysisId, stored.id())); assertEquals(result, f.submit(request));
        String root = "/variant-analyses/" + base.analysisId + "/acmg/runs/" + stored.id();
        byte[] full = base.http.perform(get(root)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(result, base.mapper.readTree(full));
        JsonNode index = base.mapper.readTree(base.http.perform(get(root + "/view")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode background = base.mapper.readTree(base.http.perform(get(root + "/view/background").param("candidateId", result.at("/candidateIds/0").asText())
                .param("backgroundIndex", "0").param("sourceSha256", index.path("sourceViewSha256").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(stored, base.store.run(base.analysisId, stored.id()).orElseThrow());
        assertEquals(pp1Before, f.pp1Source.evaluations()); assertEquals(pythonBefore, f.pythonEvaluations()); assertEquals(deNovoBefore + 1, f.deNovoEvaluations());
        f.record("non-pp1-selection", request, result, stored, index, background, full);
    }

    /** Corrupt an already genuinely completed synthetic record, not a source capability:
     * recompute the ordinary storage hashes so only the required archive semantics can reject it.
     */
    private void rejectCoherentlyResavedMissingArchive(BusinessFixture f) throws Exception {
        var base = f.base; var selected = base.store.runs(base.analysisId);
        assertEquals(1, selected.size()); String runId = selected.get(0).id();
        var original = base.store.run(base.analysisId, runId).orElseThrow();
        ObjectNode response = (ObjectNode) base.mapper.readTree(original.responseJson());
        ObjectNode privateData = (ObjectNode) base.mapper.readTree(original.privateJson());
        assertTrue(privateData.path("currentClinicalInventory").path("complete").asBoolean());
        assertTrue(privateData.has("currentClinicalArchive")); assertTrue(response.has("currentClinicalArchiveSha256"));
        privateData.remove("currentClinicalArchive"); response.remove("currentClinicalArchiveSha256");
        String newPrivate = base.mapper.writeValueAsString(privateData);
        response.put("privateArtifactSha256", AcmgBusinessStore.sha(newPrivate));
        String newPublic = base.mapper.writeValueAsString(response);
        assertEquals(1, new org.springframework.jdbc.core.JdbcTemplate(java.util.Objects.requireNonNull(base.dataSource)).update(
                "UPDATE variant_analysis_acmg_run SET response_json=?,response_sha256=?,private_json=? WHERE analysis_id=? AND run_id=?",
                newPublic, AcmgBusinessStore.sha(newPublic), newPrivate, base.analysisId, runId));
        int pp1Before = f.pp1Source.evaluations(), deNovoBefore = f.deNovoEvaluations(); long pythonBefore = f.pythonEvaluations();
        var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> base.service.read(base.analysisId, runId));
        assertEquals(503, failure.getStatusCode().value()); assertEquals("ACMG_SAVED_RESULT_INTEGRITY_FAILURE", failure.getReason());
        String root = "/variant-analyses/" + base.analysisId + "/acmg/runs/" + runId;
        // The earlier normal read populated the compact-view cache. Live stored-byte
        // fingerprints must invalidate it and must not hide this coherent missing archive.
        base.http.perform(get(root)).andExpect(status().isServiceUnavailable());
        base.http.perform(get(root + "/view")).andExpect(status().isServiceUnavailable());
        assertEquals(pp1Before, f.pp1Source.evaluations()); assertEquals(deNovoBefore, f.deNovoEvaluations());
        assertEquals(pythonBefore, f.pythonEvaluations()); assertEquals(1, base.store.runs(base.analysisId).size());
        assertEquals(newPublic, base.store.run(base.analysisId, runId).orElseThrow().responseJson());
        assertEquals(newPrivate, base.store.run(base.analysisId, runId).orElseThrow().privateJson());
    }

    static final class BusinessFixture implements AutoCloseable {
        final DeNovoUnifiedEighteenFixture composition;
        final AcmgBusinessIntegrationTest base;
        final Path directory, python, scripts, pp1Project;
        final T04Pp1ReplaySource pp1Source;
        final Pp4ServerRegisteredInputs registered;
        final ObjectNode context;
        final String nativeVcfSha;
        final ObjectNode serializerWitness;
        final List<DeNovoSelectedBatch> deNovoModules = new ArrayList<>();
        AcmgBusinessWorkflows workflows;
        BusinessFixture(Path directory) throws Exception { this(directory,false); }
        BusinessFixture(Path directory,boolean nativeStandard) throws Exception { this(directory,nativeStandard,false); }
        BusinessFixture(Path directory,boolean nativeStandard,boolean nativeAliases) throws Exception { this(directory,nativeStandard,nativeAliases,false); }
        BusinessFixture(Path directory,boolean nativeStandard,boolean nativeAliases,boolean clinicalDecimal) throws Exception {
            this(directory,nativeStandard,nativeAliases,clinicalDecimal,ignored->{});
        }
        BusinessFixture(Path directory,boolean nativeStandard,boolean nativeAliases,boolean clinicalDecimal,
                java.util.function.Consumer<ObjectNode> nativeMutation) throws Exception {
            this(directory,nativeStandard,nativeAliases,clinicalDecimal,nativeMutation,ignored->{});
        }
        BusinessFixture(Path directory,boolean nativeStandard,boolean nativeAliases,boolean clinicalDecimal,
                java.util.function.Consumer<ObjectNode> nativeMutation,java.util.function.Consumer<T04NativeOriginalFixture> sourceMutation) throws Exception {
            this.directory = directory; Files.createDirectories(directory);
            python = Path.of(System.getProperty("varvis.pp1.python")); scripts = T04NormalPipelineTest.scripts();
            composition = new DeNovoUnifiedEighteenFixture(directory, python, scripts); base = composition.base;
            // Reuse actual parser/publication/H2, replacing the old no-call input with an
            // explicit raw sample. This is source data, not a forged AnalysisVcfCall/receipt.
            String vcf = ParsedCandidateFixture.vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT:DP:GQ:AD:FT\t0/1:40:60:20,20:PASS",
                    "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT:DP:GQ:AD:FT\t0/1:40:60:20,20:PASS")
                    .replace("#CHROM", "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Synthetic depth\">\n"
                            + "##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Synthetic genotype quality\">\n"
                            + "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Synthetic allele depths\">\n"
                            + "##FORMAT=<ID=FT,Number=1,Type=String,Description=\"Synthetic sample filter\">\n#CHROM");
            base.publish(false, GenomeAssembly.GRCh38, vcf);
            base.service = composition.current; base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(composition.workflows)).build();
            composition.confirmCurrent();
            String candidateId = base.request.at("/hypotheses/0/hypothesis/candidateId").asText(), hypothesisId = base.request.at("/hypotheses/0/hypothesis/hypothesisId").asText();
            var preparation = base.service.preparePp1Literature(base.analysisId, base.mapper.writeValueAsBytes(base.request), candidateId, hypothesisId);
            context = ((ObjectNode) preparation.binding().at("/identity/preparedContext")).deepCopy();
            var candidate = base.jobs.findInterpretations(base.analysisId, 0, 1).get(0);
            serializerWitness = base.mapper.createObjectNode();
            base.mapper.valueToTree(candidate); serializerWitness.put("actualConfiguredMapperSupportsRichDto", true);
            try { new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(candidate); serializerWitness.put("plainDefaultMapperSupportsRichDto", true); }
            catch (IllegalArgumentException unsupported) {
                serializerWitness.put("plainDefaultMapperSupportsRichDto", false).put("plainFailureType", unsupported.getClass().getName());
                String message = java.util.Objects.toString(unsupported.getMessage(), "");
                serializerWitness.put("plainFailureMessage", message.substring(0, Math.min(message.length(), 2048)));
            }
            assertEquals(40, candidate.getAnalysisVcfCall().getDepth()); assertEquals(60, candidate.getAnalysisVcfCall().getGenotypeQuality());
            byte[] nativeBytes = Files.readAllBytes(base.jobs.findById(base.analysisId).orElseThrow().getUploadedFile());
            nativeVcfSha = T04NativeOriginalFixture.rawSha(nativeBytes);
            var raw = new T04NativeOriginalFixture(candidateId, candidate.getExomiserCandidateFeatures().getGeneSymbol(), context);
            raw.nativeSampleLink(nativeVcfSha, base.request.path("sampleId").asText(), 1, context);
            if(nativeAliases) T04NativeClinicalIdentityTest.relabelSources(raw);
            sourceMutation.accept(raw);
            Path manifest=raw.writeServerBundle(directory.resolve("registered"),context);
            if(nativeStandard) {
                var published=base.mapper.readTree(base.store.source(base.analysisId).orElseThrow().json());
                String uri=Path.of(published.path("analysisVcf").asText()).toAbsolutePath().normalize().toUri().toASCIIString();
                manifest=T04PhenopacketOriginalFixture.register(context,uri,base.request.path("sampleId").asText(),manifest,doc->{if(nativeAliases) T04NativeClinicalIdentityTest.relabelNativeDocument(doc);if(clinicalDecimal) T04PhenopacketOriginalFixture.addClinicalNumberAndUnrecordedRequiredDose(doc,true);nativeMutation.accept(doc);});
            }
            registered = Pp4ServerRegisteredInputs.load(manifest);
            pp1Project = directory.resolve("pp1-project");
            pp1Source = T04Pp1ReplaySource.generate(pp1Project, python, scripts, preparation.binding(), "positive");
            install();
            composition.owned.current = base.service; composition.owned.workflows = workflows; composition.owned.confirmCurrent();
            base.request.put("declarationVersion", workflows.inputOptions(base.analysisId, 1, "CURRENT").path("requiredDeclaration").asText());
            var finalPreparation = base.service.preparePp1Literature(base.analysisId, base.mapper.writeValueAsBytes(base.request), candidateId, hypothesisId);
            assertEquals(preparation.binding(), finalPreparation.binding(), "Changing source configuration cannot change the already verified patient/allele background");
            assertEquals(18, base.service.configuredCriteria().size());
        }
        private void install() throws Exception {
            var populationSettings = composition.populationFixture.populationSettings(directory.resolve("synthetic-tabix.cmd").toString());
            populationSettings.getGnomad().setBa1LocalExomesEnabled(true);
            populationSettings.getGnomad().setGrch38Files(String.join(",", List.of("11", "13").stream()
                    .map(c -> directory.resolve("gnomad.exomes.v4.1.sites.chr" + c + ".vcf.bgz").toString()).toList()));
            var localSettings = new VariantAnalysisProperties();
            localSettings.getClinVar().setEnabled(true); localSettings.getClinVar().setGrch38File(directory.resolve("missing-clinvar.vcf.gz").toString());
            localSettings.getSpliceAi().setEnabled(true); localSettings.getSpliceAi().setGrch38File(directory.resolve("missing-spliceai.vcf.gz").toString());
            localSettings.getAlphaMissense().setEnabled(true); localSettings.getAlphaMissense().setGrch38File(directory.resolve("missing-alphamissense.tsv.gz").toString());
            var rarity = new Pm3LiteratureRaritySupply.Factory() {
                public ObjectNode identity() { return composition.populationFixture.ba1.identity(); }
                public Pm2LocalExomesRuntime create() { return new Pm2LocalExomesRuntime(base.mapper, populationSettings); }
            };
            var local = AcmgBusinessService.localBatchWithEffectIdentity(composition.population, (effects, pin) -> {
                try {
                    var c = new UnifiedEighteenCompanionFixtures(base.mapper, directory.resolve("c"), composition.pvs1);
                    var deNovo = T04NativeOriginalFixture.inheritedDeNovo(directory.resolve("inherited-source"), context); deNovoModules.add(deNovo);
                    var pp1 = Pp1SelectedBatch.withCurrentFactory(base.mapper, pp1Source,
                            new Pp1CurrentContributionSource.Factory(new Pp4CurrentAdoptionProducer(registered)));
                    return new LocalEvidenceBatchRuntime(base.mapper, base.mapper.createObjectNode().put("dataClass", "SYNTHETIC_TEST_ONLY")
                            .put("fixture", "T04_ACTUAL_CURRENT18_BUSINESS")
                            .put("pvs1BundleRawSha256", composition.pvs1.bundleRawSha256).put("pvs1KnowledgeSha256", composition.pvs1.knowledgeAdoptionSha256),
                            c.pm2Factory(populationSettings), c.spliceFactory(localSettings), c.predictionFactory(localSettings, effects), c.reference(localSettings), c.bp7(),
                            c::pm4, () -> composition.pvs1.selectedBatch(), c::bp3, c::pm1, () -> c.pp2(false), () -> c.bp1(effects, pin),
                            false, deNovo, c.pm3(composition.literatureWorkflow, rarity), pp1);
                } catch (Exception invalid) { throw new IllegalArgumentException("T04_TEST_CURRENT18_CONFIGURATION_FAILED", invalid); }
            }, directory.resolve("t04-reviews"));
            base.service = AcmgBusinessService.unifiedLocal(composition.population, local);
            workflows = new AcmgBusinessWorkflows(base.service, local);
            base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows)).build();
        }
        ObjectNode submit(ObjectNode request) throws Exception {
            var response = base.http.perform(post("/variant-analyses/{id}/acmg/runs", base.analysisId).param("workflow", "CURRENT")
                    .contentType("application/json").content(java.util.Objects.requireNonNull(base.mapper.writeValueAsBytes(request)))).andReturn().getResponse();
            if (response.getStatus() != 200) {
                String body = response.getContentAsString(), reason = java.util.Objects.toString(response.getErrorMessage(), "");
                String bounded = "status=" + response.getStatus() + " reason=" + reason.substring(0, Math.min(reason.length(), 512))
                        + " body=" + body.substring(0, Math.min(body.length(), 2048));
                System.out.println("T04_BUSINESS_HTTP_FAILURE " + bounded);
                assertEquals(200, response.getStatus(), bounded);
            }
            return (ObjectNode) base.mapper.readTree(response.getContentAsByteArray());
        }
        JsonNode pp1(JsonNode result) {
            for (var row : result.at("/candidates/0/attempts/0/localBatch/criteria")) if (PP1.equals(row.path("criterionId").asText())) return row.at("/details/pp1Facts/evaluation");
            throw new AssertionError("PP1 result missing: " + result.toPrettyString());
        }
        int deNovoEvaluations() { return deNovoModules.stream().mapToInt(value -> java.util.Objects.requireNonNull(value).evaluations()).sum(); }
        long pythonEvaluations() throws Exception {
            try (var entries = Files.list(pp1Project.resolve("local/work/pp1-evaluations"))) { return entries.filter(Files::isDirectory).count(); }
        }
        String populationQueries() throws Exception { Path path = directory.resolve("queries.txt"); return Files.exists(path) ? Files.readString(path) : ""; }
        void diagnoseFailure(String mode, ObjectNode request, ObjectNode result, JsonNode pp1, String preclaimRanked) throws Exception {
            if (pp1.path("needs").isEmpty() && !pp1.at("/bayesFactor/numerator").isMissingNode()) return;
            var stored = base.store.run(base.analysisId, result.path("runId").asText()).orElseThrow();
            JsonNode privateData = base.mapper.readTree(stored.privateJson());
            String output = System.getProperty("varvis.t04.business-output", "");
            Path target = output.isBlank() ? directory.resolve("failed-" + mode) : Path.of(output).resolve(mode); Files.createDirectories(target);
            Files.write(target.resolve("failed-result.json"), base.mapper.writeValueAsBytes(result));
            Files.writeString(target.resolve("failed-stored-private.json"), stored.privateJson()); Files.writeString(target.resolve("failed-stored-public.json"), stored.responseJson());
            Files.writeString(target.resolve("failed-preclaim-ranked.json"), preclaimRanked);
            var diagnostic = serializerWitness.deepCopy().put("actualPreclaimRawSha256", AcmgBusinessStore.sha(preclaimRanked));
            diagnostic.set("savedRankingRawSha256", base.mapper.readTree(stored.requestJson()).at("/localBatchPublishedInputIdentity/candidatesRawSha256"));
            diagnostic.set("nativeUseAudit", privateData.path("currentClinicalUse"));
            diagnostic.set("nativeInventory", privateData.path("currentClinicalInventory"));
            Files.write(target.resolve("failed-native-diagnostic.json"), base.mapper.writeValueAsBytes(diagnostic));
            JsonNode attempt = privateData.at("/currentClinicalInventory/attempts/0"), use = privateData.at("/currentClinicalUse/attempts/0");
            System.out.println("T04_BUSINESS_NATIVE_DIAGNOSTIC mode=" + mode + " reason=" + attempt.path("reasonCode").asText()
                    + " global=" + privateData.at("/currentClinicalUse/globalNativeIssue").asText()
                    + " native=" + use.path("nativeCallIssue").asText() + " defaultMapper=" + serializerWitness.path("plainDefaultMapperSupportsRichDto").asBoolean()
                    + " files=" + target);
        }
        void record(String mode, ObjectNode request, ObjectNode result, AcmgBusinessStore.Run stored, JsonNode index, JsonNode background, byte[] full) throws Exception {
            String output = System.getProperty("varvis.t04.business-output", ""); if (output.isBlank()) return;
            Path target = Path.of(output).resolve(mode); Files.createDirectories(target);
            Files.write(target.resolve("request.json"), base.mapper.writeValueAsBytes(request)); Files.write(target.resolve("response.json"), base.mapper.writeValueAsBytes(result));
            Files.writeString(target.resolve("stored-private.json"), stored.privateJson()); Files.writeString(target.resolve("stored-public.json"), stored.responseJson());
            Files.write(target.resolve("index.json"), base.mapper.writeValueAsBytes(index)); Files.write(target.resolve("background.json"), base.mapper.writeValueAsBytes(background));
            Files.write(target.resolve("full-export.json"), full);
            Files.write(target.resolve("serializer-witness.json"), base.mapper.writeValueAsBytes(serializerWitness));
            Files.write(target.resolve("counts.json"), base.mapper.writeValueAsBytes(base.mapper.createObjectNode().put("dataClass", "SYNTHETIC")
                    .put("pp1ActualPythonEvaluations", pp1Source.evaluations()).put("deNovoActualEvaluations", deNovoEvaluations())
                    .put("historyReuseExportAddedWorkers", false).put("nativeVcfRawSha256", nativeVcfSha).put("actualHttp", true)));
        }
        @Override public void close() { composition.close(); }
    }
}
