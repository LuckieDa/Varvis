package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import static org.junit.jupiter.api.Assertions.*;

/** Full module wiring with synthetic policy qualification and counts, never scientific adoption. */
class Bs1SexChromosomeRuntimeTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Bs1LocalExomesTest local = new Bs1LocalExomesTest();
    @TempDir Path temporary;
    static final String SOURCE = "a".repeat(64);

    ObjectNode record(String operator) {
        var policy = new Bs1SexChromosomeStatisticTest().policy(true, "XY");
        var record = ((ObjectNode) policy.path("knowledgeRecord")).deepCopy();
        record.withObject("/expertPolicy/tiers/0/conditions/0").put("operator", operator)
                .put("originalText", "SYNTHETIC: target AF " + operator + " 0.002");
        Bs1SexChromosomeStatisticTest.qualify(record);
        return record;
    }

    ObjectNode bundle(ObjectNode record) {
        var bundle = mapper.createObjectNode().put("schemaVersion", "bs1-knowledge-bundle-v1").put("version", "SYNTHETIC_A10_RUNTIME_1");
        // The source identifier is the record's actual identifier, not the unrelated model fixture's default.
        bundle.set("sources", record.path("sources").deepCopy());
        bundle.putArray("records").add(record.deepCopy()); bundle.putArray("backgrounds"); return bundle;
    }

    Bs1KnowledgeCatalog catalog(ObjectNode record) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(bundle(record)); var file = temporary.resolve(UUID.randomUUID() + ".json");
        Files.write(file, bytes); return Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(bytes));
    }

    Bs1KnowledgeCatalog load(ObjectNode document) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(document); var file = temporary.resolve(UUID.randomUUID() + ".json");
        Files.write(file, bytes); return Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(bytes));
    }

    Bs1Runtime runtime(ObjectNode record) throws Exception {
        return Bs1Runtime.localExomes(mapper, new VariantAnalysisProperties(), true, catalog(record));
    }

    ObjectNode context(ObjectNode record) throws Exception {
        var context = new Bs1CoreTest().context();
        context.withObject("/gene").put("hgncId", record.at("/scope/hgncId").asText()).put("symbol", "SYNTHETICX");
        context.withObject("/disease").put("mondoId", record.at("/scope/mondoId").asText()).put("label", "Synthetic X frequency control");
        context.put("moi", record.at("/scope/modes/0").asText());
        context.withObject("/variant/evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "X")
                .put("position", 154535004).put("reference", "C").put("alternate", "T");
        refresh(context); return context;
    }

    void refresh(ObjectNode context) {
        var allele = context.withObject("/variant/evidenceQueryAllele");
        context.withObject("/variant/inputAllele").setAll(allele); context.withObject("/variant/normalizedAllele").setAll(allele);
        context.withObject("/variant").put("alleleId", adapter.canonicalAlleleId(allele, context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalVersionedContextId(context));
    }

    Map<String, String> values(String ac) {
        var values = new LinkedHashMap<>(local.facts.info("0.00004", "10000"));
        // Aggregate nfe AF is 0.02: borrowing it would turn all three boundary controls positive.
        values.put("AC_nfe_XY", ac); values.put("AN_nfe_XY", "10000");
        values.put("AF_nfe_XY", new BigDecimal(ac).movePointLeft(4).toPlainString());
        return values;
    }

    ObjectNode shared(ObjectNode context, String ac) { return local.shared(context, values(ac), "PASS"); }
    String status(JsonNode result) { return result.at("/evaluation/evaluationResult/status").asText(); }
    String reason(JsonNode result) { return result.at("/evaluation/evaluationResult/reasonCode").asText(); }

    @ParameterizedTest @CsvSource({"GT,19,NOT_MET", "GT,20,NOT_MET", "GT,21,MET", "GTE,20,MET"})
    void catalogToRuntimeUsesIndependentTargetAfBoundary(String operator, String ac, String expected) throws Exception {
        var record = record(operator); var runtime = runtime(record); var context = context(record); var shared = shared(context, ac);
        var before = shared.deepCopy(); String beforeSha = adapter.canonicalSha256(shared);
        var result = runtime.evaluateLocal(context, shared, SOURCE);
        assertEquals(expected, status(result), result.path("evaluation").toString());
        assertEquals("READY", result.at("/qualityDecision/status").asText());
        assertEquals(Bs1SexChromosomeStatistic.VERSION, result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        assertEquals("nfe", result.at("/qualityDecision/facts/ancestry").asText());
        assertEquals("XY", result.at("/qualityDecision/facts/karyotype").asText());
        assertEquals(ac, result.at("/qualityDecision/facts/AC").asText());
        assertEquals("10000", result.at("/qualityDecision/facts/AN").asText());
        assertEquals(record, result.at("/preparation/policy/knowledgeRecord"));
        assertEquals(before, shared); assertEquals(beforeSha, adapter.canonicalSha256(shared));
        assertEquals(result.at("/observation/observationId"), result.at("/qualityDecision/sexStratifiedObservation/parentObservationId"));
        assertEquals(result.at("/observation/rawRecordSha256"), result.at("/qualityDecision/sexStratifiedObservation/rawRecordSha256"));
        assertEquals("NOT_EVALUATED", result.at("/qualityDecision/sexStratifiedObservation/statisticalQualification").asText());
        assertEquals(0, runtime.counts().path("provider").asInt()); assertEquals(1, runtime.counts().path("evaluator").asInt());
        assertFalse(result.path("classificationEnabled").asBoolean());
    }

    @Test void jsonPersistenceRecomputesSameResultAndKeepsEverySourceBinding() throws Exception {
        var record = record("GT"); var context = context(record); var shared = shared(context, "21");
        var first = runtime(record).evaluateLocal(context, shared, SOURCE);
        assertEquals("MET", status(first), first.toPrettyString());
        var saved = mapper.createObjectNode(); saved.set("record", record); saved.set("context", context); saved.set("shared", shared); saved.set("result", first);
        var file = temporary.resolve("synthetic-full-module-originals.json"); byte[] bytes = mapper.writeValueAsBytes(saved); Files.write(file, bytes);
        var restored = mapper.readTree(Files.readAllBytes(file));
        var second = runtime((ObjectNode) restored.path("record")).evaluateLocal(restored.path("context"), restored.path("shared"), SOURCE);
        assertEquals(first, restored.path("result")); assertEquals(first, second);
        var derived = second.at("/qualityDecision/sexStratifiedObservation");
        GnomadSexStratifiedObservation.verify(mapper, derived, second.path("observation"), restored.path("context"), SOURCE, GnomadSexStratifiedObservation.loadPinnedHeader());
        assertEquals(GnomadSexStratifiedObservation.HEADER_SHA256, derived.path("headerSha256").asText());
        assertEquals(SexChromosomeRegionResolver.CATALOG_SHA256, derived.at("/region/sourceCatalogSha256").asText());
        assertEquals("NON_PAR", derived.at("/region/region").asText());
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void requiredJointCannotFallThroughToLocalExomesOrGenericThreshold() throws Exception {
        var record = record("GT");
        record.withObject("/scope/sexChromosome").put("requiredDataset", "JOINT");
        record.withObject("/expertPolicy/scope/sexChromosome").put("requiredDataset", "JOINT");
        Bs1SexChromosomeStatisticTest.qualify(record);
        var runtime = runtime(record); var context = context(record); var result = runtime.evaluateLocal(context, shared(context, "21"), SOURCE);
        assertEquals("NOT_EVALUATED", status(result)); assertEquals("BS1_SEX_DATASET_STATISTIC_MISMATCH", reason(result), result.path("evaluation").toString());
        assertFalse(result.has("observation")); assertEquals(0, runtime.counts().path("evaluator").asInt());
        assertEquals(0, runtime.counts().path("provider").asInt());
    }

    @Test void oldAutosomalPolicyCannotRunOnXOrAcquireSexStratifiedMeaning() throws Exception {
        var factory = new Bs1KnowledgeCatalogTest(); var record = factory.record("HGNC:999002", "MONDO:0999002", "AUTOSOMAL_DOMINANT");
        Bs1SexChromosomeStatisticTest.qualify(record);
        var runtime = runtime(record); var context = context(record); var result = runtime.evaluateLocal(context, shared(context, "21"), SOURCE);
        assertEquals("NOT_EVALUATED", status(result)); assertEquals("BS1_SEX_CHROMOSOME_NOT_IMPLEMENTED", reason(result), result.path("evaluation").toString());
        assertEquals(0, runtime.counts().path("evaluator").asInt()); assertFalse(result.has("qualityDecision"));
    }

    @Test void grch37PolicyAndContextCannotScoreARecordBoundToGrch38Resource() throws Exception {
        var record = record("GT");
        record.withObject("/scope/sexChromosome").putArray("assemblies").add("GRCh37");
        record.withObject("/expertPolicy/scope/sexChromosome").putArray("assemblies").add("GRCh37");
        Bs1SexChromosomeStatisticTest.qualify(record);
        var context = context(record); context.withObject("/variant/evidenceQueryAllele").put("assembly", "GRCh37"); refresh(context);
        var result = runtime(record).evaluateLocal(context, shared(context, "21"), SOURCE);
        assertEquals("NOT_EVALUATED", status(result), result.toPrettyString());
        assertEquals("BLOCKED", result.at("/qualityDecision/status").asText());
        assertEquals("BS1_SEX_SOURCE_VERIFICATION_FAILED", reason(result));
        assertEquals("GNOMAD_SEX_RESOURCE_ASSEMBLY_MISMATCH", result.at("/qualityDecision/sourceFailureCode").asText());
        assertEquals("NOT_RUN", result.at("/evaluation/deterministicTrace/executionStatus").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
    }

    @ParameterizedTest @CsvSource({"AN_nfe_XY,BS1_SEX_AN_INVALID_OR_MISSING", "AC_nfe_XY,BS1_SEX_AC_INVALID_OR_MISSING", "AF_nfe_XY,BS1_SEX_AF_INVALID_OR_MISSING"})
    void missingRequiredStratumNeverBorrowsAvailableAggregate(String field, String expected) throws Exception {
        var record = record("GT"); var context = context(record); var values = values("21"); values.remove(field);
        var result = runtime(record).evaluateLocal(context, local.shared(context, values, "PASS"), SOURCE);
        assertEquals("NOT_EVALUATED", status(result)); assertEquals(expected, reason(result), result.path("evaluation").toString());
        assertEquals("BLOCKED", result.at("/qualityDecision/status").asText());
        assertTrue(result.at("/observation/rawRecord").asText().contains("AF_nfe=0.02"));
        assertEquals("XY", result.at("/qualityDecision/frequencySelection/statistic/karyotypes/0").asText());
    }

    @ParameterizedTest @CsvSource({"CATALOG,BS1_SEX_REGION_SCOPE_INVALID", "UNSUPPORTED_PHASE,BS1_SEX_STATISTIC_INVALID", "PAR,BS1_SEX_REGION_OUT_OF_SCOPE", "CROSSING,BS1_SEX_REGION_UNRESOLVED"})
    void wrongCatalogUnsupportedPhaseOrActualRegionBlocksBeforeEvaluation(String fault, String expected) throws Exception {
        var record = record("GT");
        if ("CATALOG".equals(fault)) {
            record.withObject("/scope/sexChromosome").put("regionCatalogSha256", "b".repeat(64));
            record.withObject("/expertPolicy/scope/sexChromosome").put("regionCatalogSha256", "b".repeat(64));
        } else if ("UNSUPPORTED_PHASE".equals(fault)) record.withObject("/expertPolicy/statistic").put("phase", "PHASED");
        Bs1SexChromosomeStatisticTest.qualify(record);
        var runtime = runtime(record); var context = context(record);
        if ("PAR".equals(fault)) context.withObject("/variant/evidenceQueryAllele").put("position", 10001);
        else if ("CROSSING".equals(fault)) {
            context.withObject("/variant/evidenceQueryAllele").put("position", 2781478).put("reference", "CAA").put("alternate", "C");
            context.withObject("/variant").put("variantType", "SMALL_DELETION");
        }
        refresh(context); var result = runtime.evaluateLocal(context, shared(context, "21"), SOURCE);
        assertEquals("NOT_EVALUATED", status(result)); assertEquals(expected, reason(result), result.path("evaluation").toString());
        assertEquals(0, runtime.counts().path("evaluator").asInt());
    }

    @ParameterizedTest @ValueSource(strings = {"RAW", "SOURCE", "QUALIFICATION"})
    void tamperingCannotReuseSuccessfulSourceOrQualification(String fault) throws Exception {
        var record = record("GT");
        if ("QUALIFICATION".equals(fault)) {
            record.withObject("/expertPolicy/tiers/0/conditions/0").put("decimal", "0.001");
            new Bs1KnowledgeCatalogTest().review(record); // Deliberately do not requalify changed scientific contents.
        }
        var context = context(record); var shared = shared(context, "21");
        // Freshly parsed sex fields are not old v1 measurements; source numerics must conflict,
        // while mutation of an already sealed derived object is separately rejected by verify.
        if ("RAW".equals(fault)) shared.put("rawPopulationRecord", shared.path("rawPopulationRecord").asText().replace("AC_nfe_XY=21", "AC_nfe_XY=22"));
        else if ("SOURCE".equals(fault)) shared.put("executionSourceSha256", "b".repeat(64));
        var result = runtime(record).evaluateLocal(context, shared, SOURCE);
        assertEquals("NOT_EVALUATED", status(result));
        assertEquals(switch (fault) {
            case "RAW" -> "BS1_SEX_COUNT_AF_CONFLICT";
            case "SOURCE" -> "BS1_SHARED_POPULATION_IDENTITY_MISMATCH";
            default -> "BS1_QUALIFICATION_BINDING_INVALID";
        }, reason(result), result.path("evaluation").toString());
    }

    @Test void unknownDeclaredScopeAndUnboundScientificLocatorCannotBeIgnored() throws Exception {
        var extra = record("GT"); extra.withObject("scope").put("unimplementedSexConstraint", true);
        Bs1SexChromosomeStatisticTest.qualify(extra);
        assertEquals("BS1_SEX_RECORD_SCOPE_UNSUPPORTED", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(extra)));
        var unbound = record("GT");
        String locator = unbound.at("/expertPolicy/statistic/locator").asText();
        var bindings = unbound.withObject("qualification").withArray("sourceBindings");
        for (int i = bindings.size() - 1; i >= 0; i--) if (locator.equals(bindings.path(i).path("locator").asText())) bindings.remove(i);
        new Bs1KnowledgeCatalogTest().review(unbound);
        assertEquals("BS1_SEX_SOURCE_LOCATOR_UNBOUND", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(unbound)));
    }

    @Test void thresholdLocatorCannotBeReplacedByAnUnqualifiedSourceLocation() {
        var record = record("GT");
        record.withObject("/expertPolicy/tiers/0").put("sourceLocator", "/not-in-reviewed-source");
        Bs1KnowledgeQualificationTest.qualify(record);
        // Rebind the declared statistic/region only; the invented threshold stays outside the reviewed source.
        var bindings = record.withObject("qualification").withArray("sourceBindings");
        for (var source : java.util.List.of(record.at("/expertPolicy/scope/sexChromosome"), record.at("/expertPolicy/statistic")))
            bindings.addObject().put("sourceId", source.path("sourceId").asText())
                    .put("locator", source.has("sourceLocator") ? source.path("sourceLocator").asText() : source.path("locator").asText())
                    .put("contentSha256", SOURCE);
        new Bs1KnowledgeCatalogTest().review(record);
        assertEquals("BS1_SEX_THRESHOLD_SOURCE_LOCATOR_UNBOUND", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(record)));
    }

    @Test void unsupportedGenericXModelDoesNotHideAnEstablishedRelationshipOrAuthorizeAConflictingOne() throws Exception {
        var record = record("GT"); var b = bundle(record);
        var background = b.withArray("backgrounds").addObject().put("hgncId", record.at("/scope/hgncId").asText())
                .put("mondoId", record.at("/scope/mondoId").asText()).put("moi", "X_LINKED_DOMINANT")
                .put("reasonCode", "BS1_MODEL_MOI_UNSUPPORTED");
        background.putArray("assertions").addObject().put("hgncId", background.path("hgncId").asText())
                .put("mondoId", background.path("mondoId").asText()).put("moi", "X_LINKED_DOMINANT")
                .put("sourceId", record.at("/sources/0/sourceId").asText()).put("classification", "Definitive");
        var catalog = load(b); assertEquals(1, catalog.relationships().size());
        assertEquals("BS1_MODEL_MOI_UNSUPPORTED", catalog.relationships().get(0).path("reasonCode").asText());
        ((ObjectNode) background.path("assertions").get(0)).put("classification", "Disputed");
        assertThrows(IllegalArgumentException.class, () -> load(b));
    }

    @Test void sexExceptionIdentityRequiresCanonicalContigAndExactQualifiedLocator() {
        var exact = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "X")
                .put("position", 154535004).put("reference", "C").put("alternate", "T");
        assertTrue(Bs1Policy.validScopedAllele(exact, true));
        assertFalse(Bs1Policy.validScopedAllele(exact.deepCopy().put("contig", "chrX"), true));
        assertFalse(Bs1Policy.validScopedAllele(exact, false));
    }

    @Test @EnabledIfSystemProperty(named = "varvis.a10.bs1.python.bundle", matches = ".+")
    void actualPythonQualificationBundleIsConsumedByTheJavaRuntime() throws Exception {
        var file = Path.of(System.getProperty("varvis.a10.bs1.python.bundle"));
        byte[] raw = Files.readAllBytes(file);
        assertEquals("19086936ce53028ccbcb4bdd621bfeb0c9c8392ee05fc59a9898ad37774f272b", Bs1KnowledgeCatalog.sha(raw));
        var document = mapper.readTree(raw); var record = (ObjectNode) document.path("records").get(0);
        assertTrue(record.at("/review/reviewer").asText().contains("SYNTHETIC"));
        var catalog = Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(raw));
        var runtime = Bs1Runtime.localExomes(mapper, new VariantAnalysisProperties(), true, catalog);
        var context = context(record);
        for (String part : java.util.List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele"))
            context.withObject("/variant/" + part).put("position", 3000000).put("reference", "A").put("alternate", "G");
        refresh(context);
        for (String ac : java.util.List.of("19", "20", "21")) {
            var result = runtime.evaluateLocal(context, shared(context, ac), SOURCE);
            assertEquals("21".equals(ac) ? "MET" : "NOT_MET", status(result), result.path("evaluation").toString());
            assertEquals("21".equals(ac) ? "STRONG" : "NONE", result.at("/evaluation/appliedStrength").asText());
            assertEquals("XY", result.at("/qualityDecision/facts/karyotype").asText());
        }
        assertEquals(0, runtime.counts().path("provider").asInt());
        assertArrayEquals(raw, Files.readAllBytes(file));
    }

    @Test @EnabledIfSystemProperty(named = "varvis.a10.gnomad.record", matches = ".+")
    void realPublicRecordWithSyntheticThresholdRemainsExplicitlyEngineeringOnly() throws Exception {
        String raw = Files.readString(Path.of(System.getProperty("varvis.a10.gnomad.record")), StandardCharsets.UTF_8);
        assertEquals("afa7296ccc10242d8f159e5ef94f936082c2576af82605c65b19803d90d87dda", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(raw));
        String[] columns = raw.split("\t", -1); var values = new LinkedHashMap<String, String>();
        for (String field : columns[7].split(";")) { String[] pair = field.split("=", 2); values.put(pair[0], pair.length == 2 ? pair[1] : "true"); }
        var record = record("GT"); var context = context(record); var shared = local.shared(context, values, columns[6]);
        shared.put("rawPopulationRecord", raw); // Preserve the original rs identifier and every other source column.
        var result = runtime(record).evaluateLocal(context, shared, SOURCE);
        assertEquals("NOT_MET", status(result), result.path("evaluation").toString());
        assertEquals("1", result.at("/qualityDecision/facts/AC").asText());
        assertEquals("79012", result.at("/qualityDecision/facts/AN").asText());
        assertEquals("SYNTHETIC_TEST_NOT_CLINICAL_APPROVAL", result.at("/preparation/policy/knowledgeRecord/review/reviewer").asText());
        assertEquals("NOT_ESTABLISHED", result.at("/preparation/policy/knowledgeDecision/clinicalAdoption").asText());
        assertEquals(raw, result.at("/observation/rawRecord").asText());
    }
}
