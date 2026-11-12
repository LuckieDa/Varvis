package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

/** Actual CURRENT publication, POST, service, source parsing, public views and isolated file-H2.
 * The X:2/HBB disease topology, tiny reference readiness, population transport and source
 * BS1 qualification are explicitly SYNTHETIC controls, never GN149 observation interpretation
 * or real disease adoption. The native-count source definition is actually pinned; numerical
 * population inputs remain SYNTHETIC. FAF inputs are not recomputed confidence estimates.
 * No evaluated output, capability list, context-v2 or saved result is manufactured here.
 */
class T03FrequencyConditionsBusinessTest {
    private static final String BA1 = "ACMG_AMP_2015:BA1", BS1 = "ACMG_AMP_2015:BS1";
    private static final String AC_LOCATOR = "SYNTHETIC: explicit target alternate allele AC > 1 before maximum selection";
    private static final Set<String> ALL_CODES = Set.of("BA1", "BS1", "PM2", "PP3", "BP4", "PS1", "PM5", "BP7",
            "PVS1", "PM4", "BP3", "PM1", "PP2", "BP1", "PS2", "PM6", "PM3", "PP1");
    @TempDir Path temporary;
    private Ba1BusinessIntegrationTest fixture;
    private AcmgBusinessIntegrationTest base;
    private Bs1KnowledgeCatalog knowledge;
    private VariantAnalysisProperties exomes, joint;
    private ObjectNode record;
    private String dataset;
    private boolean fileH2Closed;
    private Pvs1UnifiedEighteenFixture companionPvs1;
    private UnifiedEighteenCompanionFixtures companions;
    private Path literatureWorkflow;
    private final Map<String, Integer> factories = new LinkedHashMap<>();
    private final List<Bs1Runtime> bs1Runtimes = new ArrayList<>();

    @AfterEach void close() { if (fixture != null && !fileH2Closed) fixture.close(); }

    private void prepare(String dataset, boolean af, boolean eighteen, String fault) throws Exception {
        this.dataset = dataset;
        fixture = new Ba1BusinessIntegrationTest(); fixture.temporary = temporary; fixture.setup(); base = fixture.base;
        base.producer = tinyReference(); base.expectedPublishedCount = 1;
        base.variantTsvOverride = tsv("1\tX\t2\tT\tA\tHBB\t3043\tXD\t0.8");
        base.publish(false, GenomeAssembly.GRCh38,
                vcf("chrX\t2\tSYNTHETIC_T03_TARGET\tT\tA\t.\tPASS\t.\tGT\t0/1"));
        record = qualifiedControlRecord(dataset, af);
        byte[] bytes = base.mapper.writeValueAsBytes(Bs1RoutedPopulationTestMaterials.bundle(base.mapper, record));
        Path catalog = temporary.resolve("SYNTHETIC-t03-knowledge.json"); Files.write(catalog, bytes);
        knowledge = Bs1KnowledgeCatalog.load(catalog, Bs1KnowledgeCatalog.sha(bytes));
        base.fixture.resolver = base.fixture.resolver.withBs1Knowledge(base.mapper, knowledge);
        exomes = populationSettings("EXOMES", "EXOMES".equals(dataset) ? fault : "NONE");
        joint = populationSettings("JOINT", "JOINT".equals(dataset) ? fault : "NONE");
        // Observation-only spy: every implementation is real; no method is stubbed.
        fixture.ba1 = spy(new Ba1AlleleRuntime(base.mapper, joint, Ba1SpecificationCatalog.bundled(base.mapper)));
        fixture.ba1Binding = fixture.ba1.identity();
        if (eighteen) prepareCompanions();
        wire(eighteen);
    }

    private void wire(boolean eighteen) throws Exception {
        fixture.wire();
        var population = AcmgBusinessService.formalPopulation(base.service, () -> {
            var runtime = Bs1Runtime.routed(base.mapper, true, exomes, joint, true, knowledge, null);
            bs1Runtimes.add(runtime); return runtime;
        });
        Files.createDirectories(temporary.resolve("reviews"));
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population,
                (effects, pin) -> localRuntime(effects, pin, eighteen), temporary.resolve("reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(new AcmgBusinessWorkflows(base.service, null))).build();
    }

    private ObjectNode qualifiedControlRecord(String dataset, boolean af) {
        var row = Bs1RoutedPopulationTestMaterials.record("JOINT".equals(dataset));
        var statistic = row.withObject("/expertPolicy/statistic");
        statistic.put("metricId", "gnomad." + dataset.toLowerCase(java.util.Locale.ROOT) + ".sex_ancestry_max_" + (af ? "af" : "faf95"))
                .put("confidence", af ? "POINT_ESTIMATE" : "95_PERCENT_LOWER_BOUND")
                .put("sourceTerm", af ? "AF" : "FAF95").put("interpretation", af ? "TARGET_ALT_AF" : "TARGET_ALT_FAF95");
        statistic.putArray("ancestries").add("nfe").add("afr");
        statistic.putObject("eligibleAc").put("operator", "GT").put("decimal", "1").put("unit", "alleles")
                .put("sourceTerm", "AC").put("sourceId", row.at("/sources/0/sourceId").asText()).put("locator", AC_LOCATOR);
        row.withObject("/expertPolicy/tiers/0/conditions/0").put("fact", af ? "AF" : "FAF95")
                .put("decimal", "0.0001").put("originalText", "SYNTHETIC: target " + (af ? "AF" : "FAF95") + " > 0.0001");
        Bs1RoutedPopulationTestMaterials.qualify(row);
        // The existing fixture seals the finite policy; the additional AC atom must
        // separately bind to its explicit SYNTHETIC source location, then reseal review.
        row.withArray("/qualification/sourceBindings").addObject().put("sourceId", row.at("/sources/0/sourceId").asText())
                .put("locator", AC_LOCATOR).put("contentSha256", row.at("/sources/0/sha256").asText());
        var review = ((ObjectNode) row.path("review")).deepCopy(); row.remove("review");
        review.put("contentSha256", new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(row)); row.set("review", review);
        return row;
    }

    private void prepareCompanions() throws Exception {
        // Existing TEST_ONLY sequence and policy inventories retain their unrelated identity.
        // They are capabilities for the real CURRENT18 composition, not a source for X:2.
        companionPvs1 = new Pvs1UnifiedEighteenFixture(base.mapper, temporary.resolve("pvs1-companion"), "NMD");
        companions = new UnifiedEighteenCompanionFixtures(base.mapper, temporary.resolve("companions"), companionPvs1);
        Path python = Path.of(Objects.requireNonNull(System.getProperty("varvis.pp1.python"), "Explicit existing Python required for CURRENT18"));
        Path scripts = Path.of(System.getProperty("varvis.t03.denovo.scripts", "../scripts/denovo"));
        literatureWorkflow = companions.prepareEmptyLiteratureWorkflow(scripts, python);
    }

    private <T> T opened(String code, T runtime) {
        Integer previous = factories.get(code); factories.put(code, previous == null ? 1 : previous + 1); return runtime;
    }

    private LocalEvidenceBatchRuntime localRuntime(java.util.function.Function<JsonNode, JsonNode> effects,
                                                   String effectPin, boolean eighteen) {
        var identity = base.mapper.createObjectNode().put("dataClass", "SYNTHETIC_TEST_ONLY")
                .put("fixture", "T03_EXPLICIT_AC_ELIGIBILITY_NOT_GN149")
                .put("knowledgeSha256", knowledge.identity().path("rawSha256").asText());
        if (!eighteen) return new LocalEvidenceBatchRuntime(base.mapper, identity, null, null, null, null, bp7());
        var settings = new VariantAnalysisProperties();
        settings.getClinVar().setEnabled(true); settings.getClinVar().setGrch38File(temporary.resolve("missing-clinvar.vcf.gz").toString());
        settings.getSpliceAi().setEnabled(true); settings.getSpliceAi().setGrch38File(temporary.resolve("missing-spliceai.vcf.gz").toString());
        settings.getAlphaMissense().setEnabled(true); settings.getAlphaMissense().setGrch38File(temporary.resolve("missing-alphamissense.tsv.gz").toString());
        var rarity = new Pm3LiteratureRaritySupply.Factory() {
            public ObjectNode identity() { return fixture.ba1.identity(); }
            public Pm2LocalExomesRuntime create() { return new Pm2LocalExomesRuntime(base.mapper, exomes); }
        };
        try {
            return new LocalEvidenceBatchRuntime(base.mapper, identity,
                    () -> opened("PM2", companions.pm2Factory(exomes).get()), companions.spliceFactory(settings),
                    companions.predictionFactory(settings, effects), companions.reference(settings), companions.bp7(),
                    () -> opened("PM4", companions.pm4()), () -> opened("PVS1", companionPvs1.selectedBatch()),
                    () -> opened("BP3", companions.bp3()), () -> opened("PM1", companions.pm1()),
                    () -> opened("PP2", companions.pp2(false)), () -> opened("BP1", companions.bp1(effects, effectPin)),
                    false, companions.deNovo(), companions.pm3(literatureWorkflow, rarity), companions.pp1(literatureWorkflow));
        } catch (Exception invalid) { throw new IllegalArgumentException("T03_TEST_ONLY_CURRENT18_CONFIGURATION_FAILED", invalid); }
    }

    private Bp7SelectedBatch bp7() {
        return new Bp7SelectedBatch(base.mapper, new SplicingExperimentChecks(base.mapper,
                new ClassPathResource("acmg/public-splicing-experiments-v1.json"), temporary.resolve("experiments"),
                (analysis, run) -> { throw new AssertionError("Catalog capture cannot execute saved business"); }).captureCatalog());
    }

    private ObjectNode confirm(String selection) throws Exception {
        var options = (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", "1").param("workflow", "CURRENT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertTrue(options.path("executionSourceRecovered").asBoolean(), options.toPrettyString());
        assertEquals(0, queries("EXOMES")); assertEquals(0, queries("JOINT")); assertTrue(factories.isEmpty());
        base.request.put("topN", 1).put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
        base.request.putArray("hypotheses");
        if ("ALL".equals(selection)) {
            var codes = new java.util.HashSet<String>();
            for (var id : options.path("enabledCriteria")) codes.add(id.asText().split(":")[1]);
            assertEquals(ALL_CODES, codes, "CURRENT18 must derive from real module composition");
            assertEquals(18, options.path("enabledCriteria").size());
            base.request.set("selectedCriteria", options.path("enabledCriteria").deepCopy());
        } else base.request.set("selectedCriteria", base.mapper.valueToTree("PAIR".equals(selection) ? List.of(BA1, BS1)
                : "BA1_ONLY".equals(selection) ? List.of(BA1) : List.of(BS1)));
        var recommendation = options.at("/candidates/0/recommendation");
        assertTrue(recommendation.at("/bs1ContextPreparation/available").asBoolean(), recommendation.toPrettyString());
        String mondo = "JOINT".equals(dataset) ? "MONDO:0999001" : "MONDO:0999002";
        JsonNode key = null;
        for (var relationship : recommendation.path("relationships")) if (mondo.equals(relationship.path("mondoId").asText()))
            for (var eligible : recommendation.at("/bs1ContextPreparation/eligibleRelationshipKeys"))
                if (eligible.equals(relationship.path("stableRelationshipKey"))) key = eligible;
        assertNotNull(key, recommendation.toPrettyString());
        var choice = base.request.putArray("recommendations").addObject();
        for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) choice.set(field, recommendation.path(field));
        choice.set("stableRelationshipKey", key); choice.putNull("annotationId");
        return options;
    }

    @ParameterizedTest @CsvSource({"JOINT,true,SINGLE", "EXOMES,true,SINGLE", "JOINT,false,PAIR", "EXOMES,false,PAIR",
            "JOINT,false,ALL", "EXOMES,false,ALL"})
    void eligibleCountRunsBeforeMaximumThroughSingleRelatedAndAllCurrentSelections(String dataset, boolean af, String selection) throws Exception {
        prepare(dataset, af, "ALL".equals(selection), "NONE"); var options = confirm(selection);
        var formal = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        var result = base.postRequest(base.request); var bs1 = bs1(result);
        assertNotEquals("FAILED", result.path("status").asText(), result.toPrettyString());
        assertEquals(base.request.path("selectedCriteria"), result.path("enabledCriteria"));
        assertEquals("MET", bs1.at("/evaluation/evaluationResult/status").asText(), bs1.toPrettyString());
        assertEquals("STRONG", bs1.at("/evaluation/appliedStrength").asText());
        assertEquals("afr", bs1.at("/qualityDecision/facts/ancestry").asText());
        assertEquals("2", bs1.at("/qualityDecision/facts/AC").asText());
        assertEquals("10000", bs1.at("/qualityDecision/facts/AN").asText());
        assertEquals(dataset, bs1.at("/qualityDecision/sexStratifiedObservation/dataset").asText());
        assertEquals("bs1-" + ("JOINT".equals(dataset) ? "joint-" : "") + "sex-stratified-frequency-v2",
                bs1.at("/qualityDecision/adapterVersion").asText());
        assertExcludedNfe(bs1);
        assertEquals(1, result.at("/bs1Calls/evaluator").asInt());
        if (!"ALL".equals(selection)) {
            assertEquals(1, queries(dataset));
            // The existing BA1 fixture is JOINT. EXOMES BS1 and JOINT BA1 have
            // distinct sealed profiles, so a related request correctly queries each once.
            int otherCalls = "EXOMES".equals(dataset) && "PAIR".equals(selection) ? 1 : 0;
            assertEquals(otherCalls, queries("JOINT".equals(dataset) ? "EXOMES" : "JOINT"));
            assertTrue(factories.isEmpty(), "BS1 and BA1 cannot initialize unrelated evidence modules");
        } else {
            assertEquals(options.path("enabledCriteria"), result.path("enabledCriteria"));
            assertEquals(16, result.at("/candidates/0/attempts/0/localBatch/criteria").size());
        }
        if ("SINGLE".equals(selection)) assertFalse(result.at("/candidates/0").has("ba1"));
        else {
            var ba1 = result.at("/candidates/0/ba1");
            assertEquals(BA1, ba1.at("/evaluation/criterionId").asText());
            assertEquals("HIT", ba1.at("/evidence/queryOutcome/status").asText());
            if ("JOINT".equals(dataset)) assertEquals(bs1.at("/observation/carrier/source"), ba1.at("/evidence/source"));
        }
        assertEquals(formal, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10)), "Independent allele evidence cannot rewrite patient calls or ranking");
        assertSavedViewsAndIdempotence(result);
        archive(dataset + "-" + (af ? "af" : "faf95") + "-" + selection.toLowerCase(java.util.Locale.ROOT), result);
    }

    @ParameterizedTest @CsvSource({"JOINT,MISSING_AC,BS1_SEX_AC_INVALID_OR_MISSING", "EXOMES,MISSING_AC,BS1_SEX_AC_INVALID_OR_MISSING",
            "JOINT,OVER_AN,BS1_SEX_AC_INVALID_OR_MISSING", "EXOMES,OVER_AN,BS1_SEX_AC_INVALID_OR_MISSING",
            "JOINT,AF_CONFLICT,BS1_SEX_COUNT_AF_CONFLICT", "EXOMES,AF_CONFLICT,BS1_SEX_COUNT_AF_CONFLICT",
            "JOINT,ALL_LOW,BS1_SEX_NO_ELIGIBLE_POPULATION", "EXOMES,ALL_LOW,BS1_SEX_NO_ELIGIBLE_POPULATION",
            "JOINT,NO_RECORD,BS1_NO_RECORD", "EXOMES,NO_RECORD,BS1_NO_RECORD"})
    void missingConflictingOrExcludedSourceRemainsUnknownRatherThanZero(String dataset, String fault, String reason) throws Exception {
        prepare(dataset, false, false, fault); confirm("SINGLE");
        var result = base.postRequest(base.request); var bs1 = bs1(result);
        assertEquals("NOT_EVALUATED", bs1.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(reason, bs1.at("/evaluation/evaluationResult/reasonCode").asText());
        var facts = bs1.at("/qualityDecision/facts");
        if (facts.has("AF")) assertNotEquals("0", facts.path("AF").asText(), "An observed eligible stratum may remain diagnostic, never an invented AF=0");
        if (Set.of("ALL_LOW", "NO_RECORD").contains(fault)) assertTrue(facts.isEmpty(), "No frequency fact exists when no population is eligible or no record was returned");
        assertEquals("COMPLETED_WITH_BLOCKERS", result.path("status").asText());
        assertEquals(1, queries(dataset)); assertEquals(0, queries("JOINT".equals(dataset) ? "EXOMES" : "JOINT"));
        assertFalse(result.at("/candidates/0").has("ba1")); assertTrue(factories.isEmpty());
        assertSavedViewsAndIdempotence(result);
        archive(dataset + "-" + fault.toLowerCase(java.util.Locale.ROOT), result);
    }

    @ParameterizedTest @CsvSource({"JOINT", "EXOMES"})
    void actualPostStoreAndFreshJvmHistoryPreservePublicPrivateAndFullHttpBytes(String dataset) throws Exception {
        prepare(dataset, false, false, "NONE"); confirm("PAIR");
        var result = base.postRequest(base.request);
        assertEquals("MET", bs1(result).at("/evaluation/evaluationResult/status").asText());
        assertExcludedNfe(bs1(result)); assertSavedViewsAndIdempotence(result);
        String run = result.path("runId").asText(); var stored = base.store.run(base.analysisId, run).orElseThrow();
        byte[] full = fullGet(run);
        Files.writeString(temporary.resolve("expected-public.json"), stored.responseJson());
        Files.writeString(temporary.resolve("expected-private.json"), stored.privateJson());
        Files.write(temporary.resolve("expected-full-get.json"), full);
        writeSourceInputPins();
        var before = new LinkedHashMap<String, String>();
        for (String source : List.of("JOINT", "EXOMES")) before.put(source, queryText(source));
        archive(dataset + "-before-fresh-jvm", result);
        base.closeDatabase(); fileH2Closed = true;
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(javaExecutable)) javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temporary.resolve("fresh-jvm-reader.log");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-cp", classpath, FreshJvmHistoryReader.class.getName(),
                temporary.toString(), base.analysisId, run).directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = process.waitFor(45, TimeUnit.SECONDS);
        if (!ended) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertTrue(ended, "Independent normal Service history reader timed out");
        assertEquals(0, process.exitValue(), Files.readString(log));
        assertArrayEquals(full, Files.readAllBytes(temporary.resolve("fresh-jvm-full-get.json")));
        assertEquals(stored.privateJson(), Files.readString(temporary.resolve("fresh-jvm-private.json")));
        for (String source : before.keySet()) assertEquals(before.get(source), queryText(source));
        String output = System.getProperty("varvis.t03.frequency.business.output");
        if (output != null && !output.isBlank()) {
            Path target = Files.createDirectories(Path.of(output).resolve(dataset.toLowerCase(java.util.Locale.ROOT) + "-fresh-jvm"));
            Files.copy(temporary.resolve("fresh-jvm-full-get.json"), target.resolve("full-get.json"));
            Files.copy(temporary.resolve("fresh-jvm-private.json"), target.resolve("private.json"));
            Files.copy(log, target.resolve("reader.log"));
        }
    }

    @ParameterizedTest @CsvSource({"true,BA1_ONLY", "false,BA1_ONLY", "true,PAIR", "false,PAIR", "true,ALL", "false,ALL"})
    void completeOrReachesNormalCurrentConsumerWithoutAdoptingRawHomozygotes(boolean highFrequency, String selection) throws Exception {
        checkNativeConditional(highFrequency, selection, "NONE");
    }

    @ParameterizedTest @CsvSource({"AN_LOW,BA1_ONLY", "AN_LOW,PAIR", "AN_LOW,ALL",
            "MISSING_FAF,BA1_ONLY", "MISSING_FAF,PAIR", "MISSING_FAF,ALL",
            "MISSING_HOMO,BA1_ONLY", "BELOW_TEN,PAIR", "FILTER,ALL"})
    void nativeCountSourceGapsAndFrequencyArmAbsenceKeepTheirIndependentMeanings(String fault, String selection) throws Exception {
        checkNativeConditional(false, selection, fault);
    }

    @ParameterizedTest @CsvSource({"HEMI,BA1_ONLY", "SUM,PAIR", "RAW_BOUND,ALL", "RAW_UNKNOWN_BOUND,PAIR", "MISSING_RAW_AN,BA1_ONLY"})
    void sourceQualifiedHemiAndWholeRawBoundsReachNormalSelectedBusiness(String fault, String selection) throws Exception {
        checkNativeConditional(false, selection, fault);
    }

    @Test void completeRawScopeCanResolveCountWithoutExactClassCounts() throws Exception {
        checkNativeConditional(false, "BA1_ONLY", "RAW_ONLY_BOUND");
    }

    /** Invoked inside a separate Writer JVM by the process-persistence test. */
    void runNativeProcessCase(String selection, String fault) throws Exception { checkNativeConditional(false, selection, fault); }

    private void checkNativeConditional(boolean highFrequency, String selection, String fault) throws Exception {
        boolean eighteen = "ALL".equals(selection);
        prepare("JOINT", false, eighteen, "NONE");
        Path script = temporary.resolve("SYNTHETIC-t03-joint-tabix.cmd"); String body = Files.readString(script);
        // Internally coherent aggregate counts; XY remains the independent BS1
        // population. Actual fixed-source native qualification supplies only the
        // diploid homo lower bound, never hemi, complete coverage or an upper bound.
        var replacements = new LinkedHashMap<String, String>();
        replacements.put("AC_joint", "120"); replacements.put("AF_joint", "0.01");
        replacements.put("AC_grpmax_joint", "40"); replacements.put("AF_grpmax_joint", "0.02");
        replacements.put("AC_joint_nfe", "40"); replacements.put("AF_joint_nfe", "0.02");
        replacements.put("AC_joint_afr", "80"); replacements.put("AF_joint_afr", "0.008");
        replacements.put("fafmax_faf95_max_joint", highFrequency ? "0.003" : "0.001");
        replacements.put("faf95_joint_nfe", highFrequency ? "0.003" : "0.001");
        for (String field : List.of("nhomalt_joint", "nhomalt_grpmax_joint", "nhomalt_joint_nfe"))
            replacements.put(field, highFrequency || Set.of("HEMI", "RAW_BOUND", "RAW_ONLY_BOUND", "RAW_UNKNOWN_BOUND", "MISSING_RAW_AN").contains(fault)
                    ? "0" : "SUM".equals(fault) ? "4" : "BELOW_TEN".equals(fault) ? "9" : "10");
        boolean rawScope = Set.of("RAW_BOUND", "RAW_ONLY_BOUND", "RAW_UNKNOWN_BOUND", "MISSING_RAW_AN").contains(fault);
        if (rawScope) {
            replacements.put("AC_joint", "6"); replacements.put("AF_joint", "0.0005");
            replacements.put("AC_grpmax_joint", "2"); replacements.put("AF_grpmax_joint", "0.001");
            replacements.put("AC_joint_nfe", "2"); replacements.put("AF_joint_nfe", "0.001");
            replacements.put("AC_joint_afr", "4"); replacements.put("AF_joint_afr", "0.0004");
        }
        if ("AN_LOW".equals(fault)) {
            replacements.put("AN_joint_nfe", "1999"); replacements.put("AN_grpmax_joint", "1999");
            replacements.put("AF_joint_nfe", "0.02001000500250125"); replacements.put("AF_grpmax_joint", "0.02001000500250125");
        }
        for (var replacement : replacements.entrySet()) {
            String regex = "(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(replacement.getKey()) + "=[^;\\r\\n]*";
            assertTrue(java.util.regex.Pattern.compile(regex).matcher(body).find(), replacement.getKey());
            body = body.replaceAll(regex, replacement.getKey() + "=" + replacement.getValue());
        }
        if ("MISSING_HOMO".equals(fault)) body = body.replace("nhomalt_joint=10;", "");
        if ("MISSING_FAF".equals(fault)) body = body.replace("fafmax_faf95_max_joint=0.001;", "");
        if ("FILTER".equals(fault)) body = body.replace("\tPASS\t", "\tLowQual\t");
        if (rawScope || Set.of("HEMI", "SUM").contains(fault)) {
            String xy = "HEMI".equals(fault) ? "10" : "SUM".equals(fault) ? "6" : "3";
            String extra = ";AC_joint_XY=" + xy + ";AN_joint_XY=10000;AF_joint_XY="
                    + new java.math.BigDecimal(xy).divide(java.math.BigDecimal.valueOf(10000)).toPlainString() + ";nhomalt_joint_XY=0";
            if (rawScope) {
                int rawAc = "RAW_UNKNOWN_BOUND".equals(fault) ? 13 : 9;
                extra += ";AC_joint_raw=" + rawAc + ";AF_joint_raw=" + java.math.BigDecimal.valueOf(rawAc)
                        .divide(java.math.BigDecimal.valueOf(15000), java.math.MathContext.DECIMAL64).toPlainString();
                if (!"MISSING_RAW_AN".equals(fault)) extra += ";AN_joint_raw=15000";
            }
            body = body.replace("\r\nexit /b", extra + "\r\nexit /b");
        }
        if ("RAW_ONLY_BOUND".equals(fault)) body = body.replace("nhomalt_joint=0;", "").replace(";AC_joint_XY=3;", ";");
        Files.writeString(script, body);
        var catalog = syntheticConditionalCatalog();
        fixture.ba1 = spy(new Ba1AlleleRuntime(base.mapper, joint, catalog)); fixture.ba1Binding = fixture.ba1.identity(); wire(eighteen);
        var actualBinding = fixture.ba1.identity();
        assertEquals("2.4.0-catalog-ba1-native-bounds", actualBinding.path("conditionalConsumerVersion").asText());
        assertEquals("ba1-native-individual-count-supply-v1", actualBinding.at("/nativeIndividualCounts/schemaVersion").asText());
        assertEquals("2.0.0", actualBinding.at("/nativeIndividualCounts/version").asText());
        var options = confirm(selection); confirmAvailableTranscript(options);
        var formal = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10));
        var result = base.postRequest(base.request); assertNotEquals("FAILED", result.path("status").asText(), result.toPrettyString());
        // unifiedLocal copies this actual binding to the profile root, while retaining
        // its population profile. The selected manifest must seal the new identity too.
        assertEquals(actualBinding, result.at("/executionProfile/ba1RuntimeBinding"));
        assertEquals("2.4.0-catalog-ba1-native-bounds", result.at("/executionProfile/ba1RuntimeBinding/conditionalConsumerVersion").asText());
        assertEquals(actualBinding.path("nativeIndividualCounts"), result.at("/executionProfile/ba1RuntimeBinding/nativeIndividualCounts"));
        var executionProfile = ((ObjectNode) result.path("executionProfile")).deepCopy();
        String manifest = executionProfile.remove("manifestSha256").asText();
        assertEquals(new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(executionProfile), manifest,
                "The selected manifest must bind the actual conditional consumer, not an older runtime identity");
        var effective = result.at("/candidates/0/ba1/contextResults/0"); var evaluated = effective.path("evaluation");
        assertEquals("CONDITIONAL_SELECTED", effective.at("/selection/status").asText(), result.toPrettyString());
        assertEquals("SYNTHETIC_T03_ADMITTED_OR_NOT_GN027_PUBLICATION", effective.at("/selection/specification/specificationId").asText());
        assertEquals("a01-ba1-faf-or-individual-count-v1", effective.at("/selection/policy/conditionRuntimeContract").asText());
        boolean sharedFailure = "FILTER".equals(fault);
        boolean countMet = !highFrequency && Set.of("NONE", "AN_LOW", "MISSING_FAF", "HEMI", "SUM").contains(fault);
        boolean countFalse = Set.of("RAW_BOUND", "RAW_ONLY_BOUND").contains(fault);
        boolean met = highFrequency || countMet;
        assertEquals(met ? "MET" : countFalse ? "NOT_MET" : "NOT_EVALUATED", evaluated.at("/evaluationResult/status").asText(), evaluated.toPrettyString());
        assertEquals("2.4.0-catalog-ba1-native-bounds", evaluated.at("/deterministicTrace/evaluatorVersion").asText());
        var condition = evaluated.path("ruleConditionEvaluation");
        assertEquals("ba1-condition-evaluation-v1", condition.path("schemaVersion").asText());
        assertEquals("a01-ba1-faf-or-individual-count-v1", condition.path("contractVersion").asText());
        assertEquals(met ? "TRUE" : countFalse ? "FALSE" : "UNKNOWN", condition.path("truth").asText());
        String frequencyTruth = sharedFailure || Set.of("AN_LOW", "MISSING_FAF").contains(fault) ? "UNKNOWN" : highFrequency ? "TRUE" : "FALSE";
        assertEquals(frequencyTruth, condition.path("frequencyTruth").asText());
        assertEquals(countMet ? "TRUE" : countFalse ? "FALSE" : "UNKNOWN", condition.path("countTruth").asText());
        if (sharedFailure) {
            assertEquals("VARIANT_FILTER_NOT_PASS", evaluated.at("/evaluationResult/reasonCode").asText());
            assertEquals("VARIANT_FILTER_NOT_PASS", condition.path("countReasonCode").asText());
        } else {
            assertNativeCountDetails(result, condition, actualBinding, highFrequency, fault);
            assertEquals(countFalse ? "INDIVIDUAL_COUNT_TOTAL_UPPER_BOUND_BELOW_THRESHOLD"
                    : "SUM".equals(fault) ? "INDIVIDUAL_COUNT_DISJOINT_SUM_THRESHOLD_SATISFIED"
                    : rawScope ? "INDIVIDUAL_COUNT_COVERAGE_INCOMPLETE"
                    : countMet ? "INDIVIDUAL_COUNT_SINGLE_CLASS_THRESHOLD_SATISFIED"
                    : "MISSING_HOMO".equals(fault) ? "BA1_NATIVE_COUNT_HOMOZYGOTES_MISSING" : "INDIVIDUAL_COUNT_CLASS_MISSING",
                    condition.path("countReasonCode").asText());
            assertFalse(condition.path("frequencyRequirement").isEmpty()); assertFalse(condition.path("individualCountRequirement").isEmpty());
            assertFalse(condition.path("frequencyReadings").isEmpty());
            if (!met && !countFalse) assertEquals("BA1_CONDITIONAL_REQUIRED_FACTS_UNRESOLVED", evaluated.at("/evaluationResult/reasonCode").asText());
        }
        if ("BA1_ONLY".equals(selection)) {
            assertFalse(result.at("/candidates/0/attempts/0").has("bs1")); assertTrue(factories.isEmpty());
        } else if ("NONE".equals(fault)) assertEquals("MET", bs1(result).at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(1, queries("JOINT"), "Selected conditional BA1 and JOINT BS1 must share one sealed population acquisition");
        if (!eighteen) assertEquals(0, queries("EXOMES"));
        assertEquals(formal, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 10)));
        assertSavedViewsAndIdempotence(result);
        // The public background card includes the unchanged effective BA1 context, not only its status.
        String run = result.path("runId").asText(); var index = base.service.readView(base.analysisId, run, null, 0, null);
        var detail = base.service.readView(base.analysisId, run, result.at("/candidates/0/candidateId").asText(), 0, index.path("sourceViewSha256").asText());
        assertEquals(condition, detail.at("/candidates/0/ba1/contextResults/0/evaluation/ruleConditionEvaluation"));
        archive("conditional-native-" + (highFrequency ? "faf-met" : "homo-lower-bound") + "-" + fault + "-" + selection, result);
        verifyFreshJvm(result, "conditional-native-" + highFrequency + "-" + fault + "-" + selection);
    }

    private void assertNativeCountDetails(JsonNode result, JsonNode condition, JsonNode runtimeIdentity,
                                          boolean highFrequency, String fault) {
        var material = condition.path("countSourceQualification");
        boolean missingHomo = Set.of("MISSING_HOMO", "RAW_ONLY_BOUND").contains(fault);
        assertEquals("ba1-native-individual-count-material-v1", material.path("schemaVersion").asText());
        boolean rawScope = Set.of("RAW_BOUND", "RAW_ONLY_BOUND", "RAW_UNKNOWN_BOUND", "MISSING_RAW_AN").contains(fault);
        boolean upperAvailable = Set.of("RAW_BOUND", "RAW_ONLY_BOUND", "RAW_UNKNOWN_BOUND").contains(fault);
        assertEquals("2.0.0", material.path("version").asText());
        assertEquals("GNOMAD_JOINT_4_1_SOURCE_BOUND_INDIVIDUAL_COUNT_BOUNDS", material.path("qualificationScope").asText());
        assertEquals(missingHomo ? upperAvailable ? "QUALIFIED_UPPER_BOUND" : "UNRESOLVED"
                : upperAvailable ? "QUALIFIED_BOUNDS" : "QUALIFIED_LOWER_BOUND", material.path("status").asText());
        assertEquals("UNVERIFIED_OR_PARTIAL", condition.path("countScopeCompleteness").asText());
        assertEquals(runtimeIdentity.path("nativeIndividualCounts"), material.path("sourceContract"));
        assertFalse(material.at("/sourceContract/diseasePolicyApprovalGranted").asBoolean(true));
        assertTrue(material.path("required").toString().contains("UNIQUE_JOINT_ADJ_DIPLOID_HOMOZYGOUS_INDIVIDUALS"));
        assertTrue(material.path("available").toString().contains("ORIGINAL_RAW_AND_MEASUREMENTS_REPLAYED"));
        assertTrue(material.path("available").toString().contains("SOURCE_NONOVERLAPPING_SAMPLE_SETS_AND_ALL_ADJ_HOMOZYGOTE_DEFINITION"));
        assertTrue(material.path("missing").toString().contains("COMPLETE_EXACT_HOMO_HEMI_COUNT_COVERAGE"), material.toPrettyString());
        assertTrue(material.path("notInferred").toString().contains("UPPER_BOUND_AS_EXACT_OR_POSITIVE_PERSON_COUNT"));
        assertTrue(material.path("notInferred").toString().contains("UNQUALIFIED_AC_XY_AS_PERSON_COUNT"));
        var evidence = result.at("/candidates/0/ba1/evidence"); var binding = material.path("binding");
        assertEquals(runtimeIdentity.path("populationInputProfileSha256"), binding.path("profileSha256"));
        assertEquals(evidence.path("queryAllele"), binding.path("queryAllele"));
        assertEquals(evidence.path("source"), binding.path("source")); assertEquals(evidence.path("producedAt"), binding.path("producedAt"));
        assertEquals(new RuntimeEvidenceV2Adapter(base.mapper).canonicalSha256(evidence), binding.path("carrierSha256").asText());
        assertEquals(AcmgBusinessStore.sha(result.at("/candidates/0/ba1/rawPopulationRecord").asText()), binding.path("rawRecordSha256").asText());
        assertTrue(binding.path("alleleId").asText().matches("allele-sha256-[0-9a-f]{64}"));
        assertEquals("JOINT", binding.path("dataset").asText()); assertEquals("4.1", binding.path("release").asText());
        assertEquals("ALL", binding.path("ancestry").asText()); assertEquals("ALL", binding.path("sex").asText());
        assertEquals("ADJ", binding.path("genotypeQualityScope").asText());
        assertEquals("SOURCE_DIPLOID_HOMO_AND_QUALIFIED_XY_NONPAR_HAPLOID", binding.path("ploidyScope").asText());
        assertFalse(binding.path("region").isEmpty());
        var requiredSourcePins = Set.of(
                "0cc05a2d50c3c12641682565b0c844422bbc698a1a3a4469ad7e0f0bc0cb650e",
                "ec6c697ab318ac29cdf18103313f0cef841d5b161c1e701f29edaf0bcb95627b",
                "b5a8bf3fd2d3cf1d7a1e6a9dbc984eac74734ffc43d4cc1b67ce30d6c0f7b693",
                "ab17b6ba4771be37ee2eadfe754ce1070ec24c220bbdc0edf5a8d957d2a6b766",
                "e78f7579c08cf0e1fcd01fb93093097280b9fd7f9c20ff8ff2964a6d4a513718",
                "1835918aa6f9b7f325f5b0eb9a59830be80e86cd976fe0e31245ca13c465696e",
                "8f0b9add7e8e8eb715fc6407b9c1d0b992bc8341f72ae1e720333de2f5dccc46");
        var sourcePins = new java.util.HashSet<String>();
        for (var source : material.at("/sourceContract/sources")) {
            assertTrue(source.path("url").asText().startsWith("https://")); assertFalse(source.path("locator").asText().isBlank());
            sourcePins.add(source.path("sha256").asText());
        }
        assertEquals(requiredSourcePins, sourcePins, "Count qualification must retain the exact original release/builder/export/Hail source identities");
        if (missingHomo) {
            assertTrue(material.path("missing").toString().contains("DIPLOID_HOMOZYGOTE_INDIVIDUAL_STATISTIC"));
            assertFalse(material.path("observed").has("homozygotes"));
            assertEquals("UNRESOLVED", condition.at("/observedHomozygoteStatistic/qualificationStatus").asText());
            assertFalse(condition.path("observedHomozygoteStatistic").has("rawValue"), "Missing ALL homo cannot borrow ancestry counts or become zero");
        } else {
            String expectedHomo = highFrequency || rawScope || "HEMI".equals(fault) ? "0"
                    : "SUM".equals(fault) ? "4" : "BELOW_TEN".equals(fault) ? "9" : "10";
            assertEquals("QUALIFIED_LOWER_BOUND", condition.at("/observedHomozygoteStatistic/qualificationStatus").asText());
            assertEquals(expectedHomo, condition.at("/observedHomozygoteStatistic/rawValue").asText());
            assertEquals(expectedHomo, material.at("/observed/homozygotes").asText());
            assertEquals("individuals", material.at("/observed/unit").asText());
            assertEquals(rawScope ? "6" : "120", material.at("/observed/AC").asText()); assertEquals("12000", material.at("/observed/AN").asText());
            if (rawScope || Set.of("HEMI", "SUM").contains(fault)) {
                assertEquals("HEMI".equals(fault) ? "10" : "SUM".equals(fault) ? "6" : "3", material.at("/observed/hemizygoteLowerBound").asText());
                assertEquals(upperAvailable ? "QUALIFIED_UPPER_BOUND" : "UNAVAILABLE", material.at("/additionalNativeBounds/personUpperBound/status").asText());
            }
        }
    }

    /** A fictional admitted rule demonstrates the consumer. GN027's complete publication
     * remains independently UNSUPPORTED; this test never edits or reclassifies it. */
    private Ba1SpecificationCatalog syntheticConditionalCatalog() throws Exception {
        var snapshot = fixture.syntheticCatalog("SYNTHETIC_T03_OR_NORMAL_CONSUMER");
        // No bundled CSpec catalog is installed in this test checkout. This
        // explicit SYNTHETIC mapping fixture binds identifiers from the fixture's
        // retained annotation input, which CURRENT restores during prepare. Formal
        // repository rows before prepare are not a complete annotation inventory.
        // This is not a recovered HGNC source or real policy adoption.
        snapshot.putObject("geneMappingSource").put("status", "COMPLETE").put("rawSha256", "a".repeat(64))
                .put("url", "https://example.test/SYNTHETIC/T03/gene-identifiers").put("recordCount", 1);
        var mapping = snapshot.withArray("geneMappings").addObject().put("hgncId", "HGNC:4827");
        mapping.putArray("geneSymbols").add("HBB"); var identifiers = mapping.putArray("ensemblGeneIds");
        var seen = new java.util.HashSet<String>();
        for (var annotation : base.fixture.annotations.get("public-hbb-compatible-e7v")) {
            // The retained source also contains overlapping unnamed RNA genes.
            // Do not relabel them as HBB; normal exact-context validation remains authoritative.
            if (!"HBB".equals(annotation.getGeneSymbol())) continue;
            String id = annotation.getGeneId(); if (id != null && !id.isBlank() && seen.add(id)) identifiers.add(id);
        }
        assertFalse(identifiers.isEmpty(), "The retained annotation source must supply target identifier inputs for this mapping control");
        snapshot.withObject("metadata").put("entryCount", 1).put("selectedReleasedCount", 1);
        var entry = snapshot.withArray("entries").addObject().put("specificationId", "SYNTHETIC_T03_ADMITTED_OR_NOT_GN027_PUBLICATION")
                .put("version", "1").put("publicationStatus", "Released").put("rawSha256", "a".repeat(64));
        var scope = entry.putObject("scope").put("status", "RESOLVED").put("transcriptScope", "UNRESTRICTED");
        scope.putArray("hgncIds").add("HGNC:4827"); scope.putArray("mondoIds").add("MONDO:0999001"); scope.putArray("moi").add("X_LINKED_DOMINANT");
        var clause = (ObjectNode) base.mapper.readTree("""
                {"disposition":"CONDITIONAL","reasonCode":"CSPEC_BA1_COMPLETE_FAF_OR_INDIVIDUAL_COUNT_FAMILY",
                 "semanticFamily":"FAF95_OR_HOMO_HEMIZYGOTE_COUNT_V1","originalMetric":"FAF",
                 "originalStatisticText":"Grpmax Filtering Allele Frequency",
                 "originalText":"Applicable when Grpmax Filtering Allele Frequency is ≥0.002 (0.2%) OR ≥10 homo- or hemizygotes (or the sum of total hemi- and homozygotes) are present in the most current version of gnomAD available at the time of curation.",
                 "conditions":[],"derivedPolicyVersion":"a01-ba1-faf-or-individual-count-v1",
                 "expression":{"operator":"ANY","frequency":{"metric":"gnomad.joint.faf95max","comparator":"GREATER_THAN_OR_EQUAL",
                   "value":"0.002","unit":"fraction","minimumAn":"2000","minimumAnComparator":"GREATER_THAN_OR_EQUAL",
                   "ancestries":["afr","amr","eas","mid","nfe","sas"]},
                   "individualCount":{"operator":"GREATER_THAN_OR_EQUAL","value":"10","unit":"individuals",
                    "populationScope":"SAME_DEDUPLICATED_SOURCE","counts":["HOMOZYGOUS","HEMIZYGOUS"],"sumOnlyIfMutuallyExclusive":true}},
                 "migration":{"status":"REVIEWED","reviewId":"SYNTHETIC_ONLY_NOT_APPROVAL","basis":"EXPLICIT_FAF_DEFINITION_ALIGNMENT",
                   "reviewRole":"PROJECT_SOURCE_DERIVATION_NOT_NEW_VCEP_OR_CLINICAL_APPROVAL"}}
                """);
        var source = base.mapper.createObjectNode().put("url", "https://example.test/SYNTHETIC/T03/rule")
                .put("sha256", "a".repeat(64)).put("jsonPointer", "/SYNTHETIC/BA1/text");
        clause.putArray("sourceRefs").add(source);
        clause.withObject("migration").put("reviewSha256", "b".repeat(64)).putArray("sources")
                .add(base.mapper.createObjectNode().put("url", "https://example.test/SYNTHETIC/guidance").put("sha256", "c".repeat(64)))
                .add(source.deepCopy()); entry.set("ba1", clause);
        byte[] bytes = base.mapper.writeValueAsBytes(snapshot); Path file = temporary.resolve("SYNTHETIC-admitted-conditional-catalog.json"); Files.write(file, bytes);
        var catalog = Ba1SpecificationCatalog.load(base.mapper, file, Bs1KnowledgeCatalog.sha(bytes));
        assertEquals("READY", catalog.identity().path("status").asText(), catalog.identity().toPrettyString()); return catalog;
    }

    private void confirmAvailableTranscript(JsonNode options) {
        var recommendation = options.at("/candidates/0/recommendation");
        var choice = (ObjectNode) base.request.at("/recommendations/0");
        JsonNode relation = null, annotation = null;
        for (var row : recommendation.path("relationships"))
            if (row.path("selectable").asBoolean() && row.path("stableRelationshipKey").equals(choice.path("stableRelationshipKey"))) relation = row;
        for (var row : recommendation.path("annotations")) if (row.path("contextSelectable").asBoolean()) {
            if (annotation == null || row.path("maneSelect").asBoolean()) annotation = row;
            if (row.path("maneSelect").asBoolean()) break;
        }
        assertNotNull(relation, recommendation.toPrettyString()); assertNotNull(annotation, recommendation.toPrettyString());
        // Same explicit UI declaration contract as the existing CURRENT18 tests.
        // The source-restored selectable annotation supplies every transcript field;
        // no complete context, normalization proof or mapping result is supplied.
        var source = base.declaredSource("recommendations/" + recommendation.path("recommendationId").asText() + "/selection");
        var item = base.request.putArray("hypotheses").addObject().put("affectedStatus", "UNKNOWN");
        var hypothesis = item.putObject("hypothesis");
        hypothesis.set("candidateId", recommendation.path("candidateId")); hypothesis.set("hypothesisId", recommendation.path("hypothesisId"));
        for (String key : List.of("hgncId", "mondoId", "moi")) hypothesis.set(key, relation.path(key)); hypothesis.set("provenance", source);
        String transcriptId = annotation.path("versionedTranscriptId").asText(); int dot = transcriptId.lastIndexOf('.');
        assertTrue(dot > 0, annotation.toPrettyString());
        var transcript = item.putObject("transcript").put("accession", transcriptId.substring(0, dot))
                .put("version", Integer.parseInt(transcriptId.substring(dot + 1)))
                .put("selectionPolicy", recommendation.path("policyId").asText() + "@" + recommendation.path("policyVersion").asText())
                .put("selectionReason", "SYNTHETIC research case: explicitly confirm an actually offered source-restored transcript");
        transcript.set("annotationSource", annotation.path("annotationSource"));
        transcript.set("annotationSourceVersion", annotation.path("annotationSourceVersion")); transcript.set("source", source);
        choice.set("annotationId", annotation.path("annotationId"));
    }

    private void verifyFreshJvm(ObjectNode result, String scenario) throws Exception {
        String run = result.path("runId").asText(); var stored = base.store.run(base.analysisId, run).orElseThrow(); byte[] full = fullGet(run);
        Files.writeString(temporary.resolve("expected-public.json"), stored.responseJson()); Files.writeString(temporary.resolve("expected-private.json"), stored.privateJson());
        Files.write(temporary.resolve("expected-full-get.json"), full);
        writeSourceInputPins();
        String jointCalls = queryText("JOINT"), exomeCalls = queryText("EXOMES");
        base.closeDatabase(); fileH2Closed = true;
        if (Boolean.getBoolean("varvis.t03.separateWriter")) {
            var checkpoint = base.mapper.createObjectNode().put("analysisId", base.analysisId).put("runId", run)
                    .put("writerPid", ProcessHandle.current().pid()).put("databaseClosed", true);
            Files.write(temporary.resolve("writer-checkpoint.json"), base.mapper.writeValueAsBytes(checkpoint));
            return; // The parent starts its Reader only after this Writer JVM actually exits.
        }
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(executable)) executable = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = temporary.resolve("fresh-jvm-reader.log");
        var process = new ProcessBuilder(executable.toString(), "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                FreshJvmHistoryReader.class.getName(), temporary.toString(), base.analysisId, run)
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = process.waitFor(45, TimeUnit.SECONDS);
        if (!ended) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertTrue(ended, "Independent conditional-history reader timed out"); assertEquals(0, process.exitValue(), Files.readString(log));
        assertArrayEquals(full, Files.readAllBytes(temporary.resolve("fresh-jvm-full-get.json")));
        assertEquals(stored.privateJson(), Files.readString(temporary.resolve("fresh-jvm-private.json")));
        assertEquals(jointCalls, queryText("JOINT")); assertEquals(exomeCalls, queryText("EXOMES"));
        String output = System.getProperty("varvis.t03.frequency.business.output");
        if (output != null && !output.isBlank()) {
            Path target = Files.createDirectories(Path.of(output).resolve(scenario.toLowerCase(java.util.Locale.ROOT) + "-fresh-jvm"));
            Files.copy(temporary.resolve("fresh-jvm-full-get.json"), target.resolve("full-get.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(temporary.resolve("fresh-jvm-private.json"), target.resolve("private.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(log, target.resolve("reader.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private JsonNode bs1(JsonNode result) { return result.at("/candidates/0/attempts/0/bs1"); }

    private void assertExcludedNfe(JsonNode bs1) {
        JsonNode excluded = null;
        for (var population : bs1.at("/qualityDecision/frequencySelection/populations"))
            if ("nfe".equals(population.path("ancestry").asText())) excluded = population;
        assertNotNull(excluded, bs1.toPrettyString());
        assertEquals("EXCLUDED", excluded.path("status").asText());
        assertEquals("BS1_SEX_AC_INSUFFICIENT", excluded.path("reasonCode").asText());
    }

    private byte[] fullGet(String run) throws Exception {
        return base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private void assertSavedViewsAndIdempotence(ObjectNode result) throws Exception {
        var priorFactories = new LinkedHashMap<>(factories);
        var priorExecution = executionCounts();
        String jointCalls = queryText("JOINT"), exomeCalls = queryText("EXOMES");
        String run = result.path("runId").asText(); var saved = base.store.run(base.analysisId, run).orElseThrow();
        assertEquals(result, base.mapper.readTree(saved.responseJson()));
        assertEquals(AcmgBusinessStore.sha(saved.responseJson()), saved.responseSha256());
        assertEquals(AcmgBusinessStore.sha(saved.privateJson()), result.path("privateArtifactSha256").asText());
        assertEquals(result, base.postRequest(base.request)); assertEquals(result, base.service.read(base.analysisId, run));
        assertEquals(result, base.mapper.readTree(fullGet(run)));
        var index = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}/view", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(AcmgResultViews.INDEX, index.path("viewKind").asText());
        String candidate = result.at("/candidates/0/candidateId").asText(), sha = index.path("sourceViewSha256").asText();
        var detail = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}/view/background", base.analysisId, run)
                .param("candidateId", candidate).param("backgroundIndex", "0").param("sourceSha256", sha))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(AcmgResultViews.BACKGROUND, detail.path("viewKind").asText());
        assertEquals(result.at("/candidates/0/attempts/0"), detail.at("/candidates/0/attempts/0"));
        assertEquals(bs1(result), bs1(detail), "Public detail must retain selected facts, excluded populations, policy and exact reason");
        assertEquals(result.path("enabledCriteria"), detail.path("enabledCriteria"));
        base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}/view/background", base.analysisId, run)
                .param("candidateId", candidate).param("backgroundIndex", "0").param("sourceSha256", "0".repeat(64)))
                .andExpect(status().isConflict());
        assertEquals(saved, base.store.run(base.analysisId, run).orElseThrow());
        assertEquals(priorExecution, executionCounts(), "Saved reads, public details and exact repeated POST must not invoke an evaluator again");
        assertEquals(priorFactories, factories); assertEquals(jointCalls, queryText("JOINT")); assertEquals(exomeCalls, queryText("EXOMES"));
    }

    private Map<String, Long> executionCounts() {
        long ba1 = mockingDetails(fixture.ba1).getInvocations().stream()
                .filter(call -> call.getMethod().getName().startsWith("evaluate")).count();
        long bs1 = bs1Runtimes.stream().mapToLong(runtime -> runtime.counts().path("evaluator").asLong()).sum();
        return Map.of("ba1ActualEvaluateInvocations", ba1, "bs1ActualEvaluatorCalls", bs1);
    }

    private void writeSourceInputPins() throws Exception {
        var pins = base.mapper.createArrayNode();
        for (String name : List.of("SYNTHETIC-t03.fa", "SYNTHETIC-t03.fa.fai", "SYNTHETIC-t03-joint-tabix.cmd",
                "SYNTHETIC-t03-exomes-tabix.cmd", "SYNTHETIC-t03-knowledge.json", "SYNTHETIC-admitted-conditional-catalog.json")) {
            Path file = temporary.resolve(name);
            if (Files.exists(file)) pins.addObject().put("file", name).put("bytes", Files.size(file))
                    .put("sha256", Bs1KnowledgeCatalog.sha(Files.readAllBytes(file))).put("dataClass", "SYNTHETIC_TEST_ONLY_INPUT");
        }
        Files.write(temporary.resolve("expected-source-pins.json"), base.mapper.writeValueAsBytes(pins));
    }

    private void archive(String scenario, ObjectNode result) throws Exception {
        String output = System.getProperty("varvis.t03.frequency.business.output");
        if (output == null || output.isBlank()) return;
        Path target = Files.createDirectories(Path.of(output).resolve(scenario.toLowerCase(java.util.Locale.ROOT)));
        var saved = base.store.run(base.analysisId, result.path("runId").asText()).orElseThrow();
        Files.writeString(target.resolve("public.json"), saved.responseJson()); Files.writeString(target.resolve("private.json"), saved.privateJson());
        Files.write(target.resolve("full-get.json"), fullGet(saved.id()));
        Files.write(target.resolve("synthetic-control-record.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
        writeSourceInputPins();
        Files.copy(temporary.resolve("expected-source-pins.json"), target.resolve("source-input-pins.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path inputs = Files.createDirectories(target.resolve("synthetic-inputs"));
        for (var pin : base.mapper.readTree(Files.readAllBytes(temporary.resolve("expected-source-pins.json")))) {
            String file = pin.path("file").asText();
            Files.copy(temporary.resolve(file), inputs.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        var receipt = base.mapper.createObjectNode().put("layer", "SYNTHETIC_QUALIFICATION_ACTUAL_CURRENT_HTTP_SERVICE_STORE_NOT_REAL_DISEASE_ADOPTION")
                .put("scenario", scenario).put("dataset", dataset).put("clinicalAdoption", false).put("gn149ObservationInterpretation", false)
                .put("publicSha256", saved.responseSha256()).put("privateSha256", AcmgBusinessStore.sha(saved.privateJson()));
        Files.write(target.resolve("receipt.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
    }

    private int queries(String source) throws Exception { String text = queryText(source); return text.isEmpty() ? 0 : text.lines().toList().size(); }
    private String queryText(String source) throws Exception {
        Path path = temporary.resolve(source.toLowerCase(java.util.Locale.ROOT) + "-queries.txt"); return Files.exists(path) ? Files.readString(path) : "";
    }

    /** Deterministic transport only; the actual provider owns ALT parsing and sealed provenance. */
    private VariantAnalysisProperties populationSettings(String source, String fault) throws Exception {
        boolean combined = "JOINT".equals(source); String suffix = combined ? "_joint" : "";
        var values = new LinkedHashMap<String, String>();
        values.put("AC" + suffix, "3"); values.put("AN" + suffix, "12000"); values.put("AF" + suffix, "0.00025"); values.put("nhomalt" + suffix, "0");
        values.put("grpmax" + suffix, "nfe"); values.put("AC_grpmax" + suffix, "1"); values.put("AN_grpmax" + suffix, "2000");
        values.put("AF_grpmax" + suffix, "0.0005"); values.put("nhomalt_grpmax" + suffix, "0");
        values.put("faf95" + suffix, "0.0002"); values.put("fafmax_faf95_max" + suffix, "0.0003"); values.put("fafmax_faf95_max_gen_anc" + suffix, "nfe");
        for (String ancestry : List.of("nfe", "afr")) for (String group : List.of("", "_XY")) {
            boolean high = "nfe".equals(ancestry); String tail = "_" + ancestry + group;
            values.put("AC" + suffix + tail, high ? "1" : "2"); values.put("AN" + suffix + tail, high ? "2000" : "10000");
            values.put("AF" + suffix + tail, high ? "0.0005" : "0.0002"); values.put("faf95" + suffix + tail, high ? "0.0003" : "0.00015");
            values.put("nhomalt" + suffix + tail, "0");
        }
        values.put("exomes_filters", "PASS"); values.put("genomes_filters", "PASS");
        values.put("stat_union_p_value", "0.5"); values.put("stat_union_test_name", "cmh"); values.put("stat_union_gen_ancs", "nfe");
        switch (fault) {
            case "MISSING_AC" -> values.remove("AC" + suffix + "_nfe_XY");
            case "OVER_AN" -> values.put("AC" + suffix + "_nfe_XY", "2001");
            case "AF_CONFLICT" -> values.put("AF" + suffix + "_afr_XY", "0.9");
            case "ALL_LOW" -> { values.put("AC" + suffix + "_afr_XY", "1"); values.put("AF" + suffix + "_afr_XY", "0.0001"); }
            case "NONE", "NO_RECORD" -> { }
            default -> throw new IllegalArgumentException("Unknown explicit source fault " + fault);
        }
        String info = String.join(";", values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList());
        String label = source.toLowerCase(java.util.Locale.ROOT); Path script = temporary.resolve("SYNTHETIC-t03-" + label + "-tabix.cmd");
        Files.writeString(script, "@echo off\r\necho query>>\"%~dp0" + label + "-queries.txt\"\r\n"
                + ("NO_RECORD".equals(fault) ? "" : "echo chrX\t2\tSYNTHETIC_T03_RAW\tT\tA\t.\tPASS\t" + info + "\r\n") + "exit /b 0\r\n");
        var settings = new VariantAnalysisProperties(); var population = settings.getGnomad();
        population.setEnabled(true); population.setPopulationInputEnabled(true); population.setVersion("gnomAD " + label + " v4.1 GRCh38");
        population.setTabixExecutable(script.toString()); population.setTimeout(Duration.ofSeconds(5)); population.setRemoteTotalTimeout(Duration.ofSeconds(5));
        population.setRemoteMaxAttempts(1); population.setParallelism(1); population.setLocalPopulationInProcessEnabled(false);
        population.setAllowFullVcfFallback(false); population.setAllowVcfInfoFallback(false);
        if (!combined) {
            population.setBa1LocalExomesEnabled(true); Path file = temporary.resolve("gnomad.exomes.v4.1.sites.chrX.vcf.bgz");
            Files.writeString(file, "SYNTHETIC transport locator only; not a real bgzip resource"); Files.writeString(Path.of(file + ".tbi"), "SYNTHETIC transport locator only");
            population.setGrch38Files(file.toString());
        }
        return settings;
    }

    private NativeSnvSourceProducer tinyReference() throws Exception {
        Path fasta = temporary.resolve("SYNTHETIC-t03.fa"), index = Path.of(fasta + ".fai");
        Files.writeString(fasta, ">chr11\nATCG\n>chr13\nAACT\n>chrX\nATCG\n");
        Files.writeString(index, "chr11\t4\t7\t4\t5\nchr13\t4\t19\t4\t5\nchrX\t4\t30\t4\t5\n");
        String sha = AcmgBusinessStore.sha(Files.readString(fasta));
        var receipt = base.mapper.createObjectNode().put("resourceId", "SYNTHETIC_T03_TINY_FASTA").put("resourceVersion", "1")
                .put("assembly", "GRCh38").put("logicalName", fasta.getFileName().toString()).put("expectedSha256", sha).put("observedSha256", sha)
                .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true)
                .put("byteLength", Files.size(fasta)).put("mtimeNs", Files.getLastModifiedTime(fasta).to(TimeUnit.NANOSECONDS))
                .put("startedAt", "2026-11-04T00:00:00Z").put("completedAt", "2026-11-04T00:00:01Z")
                .put("indexSha256", AcmgBusinessStore.sha(Files.readString(index))).put("resourceDataClass", "SYNTHETIC");
        var envelope = base.mapper.createObjectNode().put("source", "SYNTHETIC_T03_REFERENCE_CHECKSUM").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        var ledger = ExplicitContextSourceLedger.create(base.mapper, envelope); base.fixture.sources.add(ledger);
        var settings = new VariantAnalysisProperties(); settings.getNormalization().setGrch38Reference(fasta.toString());
        settings.getNormalization().setGrch38ReferenceSha256(sha); settings.getNormalization().setGrch38ProfileId("SYNTHETIC_T03_TINY_REFERENCE");
        var readiness = new NormalizationResourceValidator(settings) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly, "READY", "SYNTHETIC_T03_TINY_REFERENCE", fasta.getFileName().toString(),
                        sha, true, true, 3, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_TINY_REFERENCE_READINESS_ONLY"));
            }
        };
        return new NativeSnvSourceProducer(base.mapper, settings, temporary, base.fixture.sources, ledger.reference("checksum"), readiness);
    }

    /** A separate Reader process reconstructs normal persistence/service/controller after
     * the still-running parent test JVM closes H2. This does not prove Writer-process exit. */
    public static final class FreshJvmHistoryReader {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]); String analysis = args[1], run = args[2];
            var mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("business-db").toString().replace('\\', '/')
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            try {
                verifySourceInputs(directory, mapper);
                var settings = new VariantAnalysisProperties(); settings.setStorageRoot(directory.resolve("jobs")); settings.getPersistence().setRetryBackoff(Duration.ZERO);
                var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(ds), new DataSourceTransactionManager(ds), settings, mapper);
                var store = new AcmgBusinessStore(ds); var sources = new AcmgExecutionSources(mapper, store);
                var service = new AcmgBusinessService(mapper, jobs, store, sources,
                        () -> { throw new AssertionError("Historical reading cannot acquire an evidence runtime"); });
                var saved = store.run(analysis, run).orElseThrow();
                require(Files.readString(directory.resolve("expected-public.json")).equals(saved.responseJson()), "SAVED_PUBLIC_BYTES_CHANGED");
                require(Files.readString(directory.resolve("expected-private.json")).equals(saved.privateJson()), "SAVED_PRIVATE_BYTES_CHANGED");
                require(AcmgBusinessStore.sha(saved.responseJson()).equals(saved.responseSha256()), "SAVED_PUBLIC_SHA_CHANGED");
                var result = service.read(analysis, run);
                require(mapper.readTree(saved.responseJson()).equals(result), "NORMAL_SERVICE_READ_CHANGED");
                require(service.list(analysis).stream().anyMatch(row -> run.equals(row.path("runId").asText())), "NORMAL_SERVICE_HISTORY_MISSING_RUN");
                var http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
                byte[] full = http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysis, run))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                require(Arrays.equals(Files.readAllBytes(directory.resolve("expected-full-get.json")), full), "NORMAL_FULL_GET_BYTES_CHANGED");
                var index = service.readView(analysis, run, null, 0, null);
                String candidate = result.at("/candidates/0/candidateId").asText();
                var detail = service.readView(analysis, run, candidate, 0, index.path("sourceViewSha256").asText());
                require(result.at("/candidates/0/attempts/0").equals(detail.at("/candidates/0/attempts/0")), "NORMAL_DETAIL_CHANGED");
                require(saved.equals(store.run(analysis, run).orElseThrow()), "READ_REWROTE_PERSISTENCE");
                verifySourceInputs(directory, mapper);
                Files.write(directory.resolve("fresh-jvm-full-get.json"), full, StandardOpenOption.CREATE_NEW);
                Files.writeString(directory.resolve("fresh-jvm-private.json"), saved.privateJson(), StandardOpenOption.CREATE_NEW);
                System.out.println("PASS: actual POST/store then separate Reader JVM Service/list/detail/full GET after parent DB close; parent Writer JVM remains alive; no evidence execution");
            } finally { new JdbcTemplate(ds).execute("SHUTDOWN"); }
        }
        private static void verifySourceInputs(Path directory, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
            Path inventory = directory.resolve("expected-source-pins.json");
            if (!Files.exists(inventory)) return; // Other callers may supply their own, separately verified input inventory.
            Path root = directory.toAbsolutePath().normalize();
            for (var pin : mapper.readTree(Files.readAllBytes(inventory))) {
                Path file = root.resolve(pin.path("file").asText()).normalize();
                require(file.startsWith(root) && Files.isRegularFile(file) && Files.size(file) <= 1024 * 1024, "TEST_SOURCE_INPUT_SCOPE_INVALID");
                require(Files.size(file) == pin.path("bytes").asLong()
                        && Bs1KnowledgeCatalog.sha(Files.readAllBytes(file)).equals(pin.path("sha256").asText()), "TEST_SOURCE_INPUT_BYTES_CHANGED");
            }
        }
        private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    }
}
