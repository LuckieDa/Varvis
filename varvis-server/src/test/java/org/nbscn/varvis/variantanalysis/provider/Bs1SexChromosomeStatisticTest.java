package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independently fixed synthetic thresholds and population counterexamples; no scientific adoption. */
class Bs1SexChromosomeStatisticTest {
    final ObjectMapper mapper = new ObjectMapper();
    static final String SOURCE = "a".repeat(64);

    ObjectNode policy(boolean af, String... karyotypes) {
        var p = mapper.createObjectNode().put("schemaVersion", Bs1Policy.SCHEMA).put("criterionId", Bs1Policy.CRITERION)
                .put("policyId", "SYNTHETIC_SEX_EXPERT").put("version", "1").put("activation", Bs1KnowledgeCatalog.ACTIVATION)
                .put("sourceCompleteness", "COMPLETE").put("semanticSupport", "SUPPORTED").put("identityResolution", "RESOLVED");
        p.putArray("sources").addObject().put("sourceId", "synthetic-sex-expert").put("url", "https://example.org/synthetic-sex-expert")
                .put("version", "SYNTHETIC-1").put("sha256", SOURCE).put("locator", "SYNTHETIC: complete BS1 clause");
        var scope = p.putObject("scope").put("hgncId", "HGNC:999001").put("mondoId", "MONDO:0999001").put("modeSelection", "EXACT_DECLARED_MODE");
        scope.putArray("modes").add("X_LINKED_DOMINANT"); scope.putArray("variantTypes").add("SNV").add("SMALL_DELETION").add("SMALL_INSERTION");
        var sex = scope.putObject("sexChromosome").put("regionCatalogSha256", SexChromosomeRegionResolver.CATALOG_SHA256)
                .put("requiredDataset", "EXOMES").put("sourceId", "synthetic-sex-expert").put("sourceLocator", "SYNTHETIC: X non-PAR scope");
        sex.putArray("assemblies").add("GRCh38"); sex.putArray("contigs").add("X"); sex.putArray("regions").add("NON_PAR");
        var statistic = p.putObject("statistic").put("metricId", af ? Bs1SexChromosomePolicy.AF : Bs1SexChromosomePolicy.FAF95)
                .put("unit", "fraction").put("confidence", af ? "POINT_ESTIMATE" : "95_PERCENT_LOWER_BOUND")
                .put("sourceTerm", af ? "AF" : "FAF95").put("interpretation", af ? "TARGET_ALT_AF" : "TARGET_ALT_FAF95")
                .put("interpretationBasis", "SYNTHETIC: explicit source-defined ancestry and karyotype allele denominator")
                .put("sourceId", "synthetic-sex-expert").put("locator", "SYNTHETIC: population statistic");
        statistic.putArray("ancestries").add("nfe"); var groups = statistic.putArray("karyotypes");
        for (String group : karyotypes) groups.add(group);
        statistic.putObject("eligibleAn").put("operator", "GTE").put("decimal", "2000");
        var tier = p.putArray("tiers").addObject().put("tierId", "SYNTHETIC_DIRECT_THRESHOLD").put("priority", 1)
                .put("moi", "X_LINKED_DOMINANT").put("strength", "STRONG").put("sourceLocator", "SYNTHETIC: BS1 threshold");
        tier.putArray("conditions").addObject().put("fact", af ? "AF" : "FAF95").put("operator", "GT").put("decimal", "0.002")
                .put("unit", "fraction").put("basis", "OFFICIAL_CONDITION").put("originalText", "SYNTHETIC: frequency > 0.002")
                .put("sourceLocator", "SYNTHETIC: BS1 threshold");
        p.putArray("commonConditions");
        p.putObject("exceptionSet").put("setId", "SYNTHETIC_EMPTY_EXCEPTIONS").put("version", "1").put("criterionId", Bs1Policy.CRITERION)
                .put("status", "COMPLETE_IN_PINNED_SCOPE").put("sourceLocator", "SYNTHETIC: no exceptions in this fixed scope").putArray("entries");
        p.set("qualityContract", Bs1LocalExomes.qualityContract()); p.set("dataScope", Bs1LocalExomes.scope(mapper));
        p.putObject("requiredContext").put("transcript", "NOT_REQUIRED_BY_POLICY");
        p.putObject("knowledgeDecision").put("basis", "EXPERT_POLICY").put("clinicalAdoption", "NOT_ESTABLISHED");
        adopt(p); return p;
    }

    void adopt(ObjectNode p) {
        var expert = p.deepCopy(); expert.remove(List.of("knowledgeRecord", "knowledgeDecision"));
        var record = mapper.createObjectNode().put("recordId", "SYNTHETIC_SEX_EXPERT").put("version", "1")
                .put("kind", "EXPERT_POLICY").put("publicationStatus", "Released");
        var scope = ((ObjectNode) p.path("scope")).deepCopy(); scope.remove(List.of("variantTypes", "modeSelection"));
        record.set("scope", scope); record.set("sources", p.path("sources").deepCopy()); record.set("expertPolicy", expert);
        record.set("exceptionSet", p.path("exceptionSet").deepCopy()); qualify(record);
        p.set("knowledgeRecord", record);
    }

    static void qualify(ObjectNode record) {
        Bs1KnowledgeQualificationTest.qualify(record);
        if (!Bs1SexChromosomePolicy.isStatistic(record.path("expertPolicy"))) return;
        var bindings = record.withObject("qualification").withArray("sourceBindings");
        var scopes = java.util.List.of(record.at("/expertPolicy/scope/sexChromosome"), record.at("/expertPolicy/statistic"));
        for (var source : scopes) {
            String id = source.path("sourceId").asText();
            String locator = source.has("sourceLocator") ? source.path("sourceLocator").asText() : source.path("locator").asText();
            boolean present = false;
            for (var binding : bindings) present |= id.equals(binding.path("sourceId").asText()) && locator.equals(binding.path("locator").asText());
            if (!present) bindings.addObject().put("sourceId", id).put("locator", locator).put("contentSha256", SOURCE);
        }
        var official = new java.util.HashSet<String>();
        for (var tier : record.at("/expertPolicy/tiers")) {
            official.add(tier.path("sourceLocator").asText());
            for (var condition : tier.path("conditions")) if ("OFFICIAL_CONDITION".equals(condition.path("basis").asText()))
                official.add(condition.path("sourceLocator").asText());
        }
        for (var condition : record.at("/expertPolicy/commonConditions")) if ("OFFICIAL_CONDITION".equals(condition.path("basis").asText()))
            official.add(condition.path("sourceLocator").asText());
        for (String locator : official) {
            boolean present = false; for (var binding : bindings) present |= locator.equals(binding.path("locator").asText());
            if (!present) bindings.addObject().put("sourceId", record.at("/sources/0/sourceId").asText())
                    .put("locator", locator).put("contentSha256", SOURCE);
        }
        for (var exception : record.at("/exceptionSet/entries")) for (var source : exception.path("mappingSource"))
            bindings.addObject().put("sourceId", source.path("sourceId").asText()).put("locator", source.path("locator").asText()).put("contentSha256", SOURCE);
        new Bs1KnowledgeCatalogTest().review(record);
    }

    ObjectNode derived() {
        var d = mapper.createObjectNode().put("schemaVersion", GnomadSexStratifiedObservation.VERSION).put("dataset", "EXOMES")
                .put("release", "4.1").put("statisticalQualification", "NOT_EVALUATED").put("status", "OBSERVED")
                .put("recordFilter", "PASS").put("observationId", "SYNTHETIC_DERIVED_OBSERVATION");
        d.putArray("strata"); return d;
    }

    ObjectNode row(ObjectNode d, String ancestry, String karyotype, String ac, String an, String af, String faf) {
        var r = d.withArray("strata").addObject().put("ancestry", ancestry).put("karyotype", karyotype)
                .put("denominatorUnit", "ALLELES").put("individualPloidy", "NOT_ESTABLISHED").put("status", "OBSERVED");
        var measures = r.putObject("measurements");
        for (var entry : Map.of("AC", ac, "AN", an, "AF", af, "faf95", faf).entrySet())
            measures.putObject(entry.getKey()).put("state", "PRESENT").put("rawValue", entry.getValue())
                    .put("unit", List.of("AC", "AN").contains(entry.getKey()) ? "alleles" : "fraction");
        measures.withObject("faf95").put("applicability", "APPLICABLE").put("confidence", "95_PERCENT_LOWER_BOUND");
        return r;
    }

    ObjectNode select(ObjectNode d, ObjectNode p) { return Bs1SexChromosomeStatistic.selectVerified(mapper, d, p); }
    String reason(JsonNode result) { return result.at("/issues/0/reasonCode").asText(); }
    String condition(ObjectNode result, ObjectNode p) {
        var facts = new LinkedHashMap<String, BigDecimal>();
        for (String key : List.of("AC", "AN", "AF", "FAF95")) {
            String text = result.path("facts").path(key).asText(); if (!text.isBlank()) facts.put(key, new BigDecimal(text));
        }
        return Bs1SexChromosomeStatistic.condition(p.at("/tiers/0/conditions/0"), facts);
    }

    @ParameterizedTest @CsvSource({"19,FALSE", "20,FALSE", "21,TRUE"})
    void exactAfBoundaryUsesCountsAndSourceOperator(String ac, String expected) {
        var p = policy(true, "XY"); assertNull(Bs1SexChromosomePolicy.issue(p)); var d = derived();
        row(d, "nfe", "XY", ac, "10000", new BigDecimal(ac).movePointLeft(4).toPlainString(), "0");
        var result = select(d, p); assertEquals("READY", result.path("status").asText()); assertEquals(expected, condition(result, p));
        p.withObject("/tiers/0/conditions/0").put("operator", "GTE"); adopt(p);
        assertEquals(Integer.parseInt(ac) >= 20 ? "TRUE" : "FALSE", condition(select(d, p), p));
    }

    @Test void maximumUsesExactCountsAndDeterministicStratumTieBreak() {
        var p = policy(true, "XY", "XX"); var d = derived();
        row(d, "nfe", "XX", "20", "10000", "0.002", "0");
        row(d, "nfe", "XY", "200002", "100000000", "0.0020000", "0");
        var result = select(d, p); assertEquals("XY", result.at("/facts/karyotype").asText()); assertEquals("TRUE", condition(result, p));
        d = derived(); row(d, "nfe", "XY", "20", "10000", "0.002", "0"); row(d, "nfe", "XX", "20", "10000", "0.002", "0");
        assertEquals("XX", select(d, p).at("/facts/karyotype").asText());
    }

    @Test void knownLowAnIsExcludedBeforeUnusedInputsButUnknownEligibilityBlocks() {
        var p = policy(true, "XX", "XY"); var d = derived();
        var low = row(d, "nfe", "XX", "0", "1999", "0", "0"); low.withObject("measurements").remove("AF");
        row(d, "nfe", "XY", "21", "10000", "0.0021", "0");
        var result = select(d, p); assertEquals("READY", result.path("status").asText());
        assertEquals("EXCLUDED", result.at("/frequencySelection/populations/0/status").asText());
        low.withObject("measurements").remove("AN"); assertEquals("BS1_SEX_AN_INVALID_OR_MISSING", reason(select(d, p)));
    }

    @ParameterizedTest @CsvSource({"1999,BLOCKED", "2000,READY", "2001,READY"})
    void declaredAlleleNumberOperatorControlsItsExactBoundary(String an, String expected) {
        var p = policy(true, "XY"); var d = derived(); row(d, "nfe", "XY", "0", an, "0", "0");
        assertEquals(expected, select(d, p).path("status").asText());
        p.withObject("/statistic/eligibleAn").put("operator", "GT"); adopt(p);
        assertEquals(Integer.parseInt(an) > 2000 ? "READY" : "BLOCKED", select(d, p).path("status").asText());
    }

    @Test void aggregateAllDoesNotSupplyMissingXyOrImplyIndividualPloidy() {
        var d = derived(); row(d, "nfe", "ALL", "30", "10000", "0.003", "0.0025");
        assertEquals("BS1_SEX_STRATUM_MISSING", reason(select(d, policy(true, "XY"))));
        var result = select(d, policy(true, "ALL")); assertEquals("READY", result.path("status").asText());
        assertEquals("ALL", result.at("/facts/karyotype").asText());
        assertEquals("NOT_ESTABLISHED", result.at("/frequencySelection/populations/0/sourceStratum/individualPloidy").asText());
    }

    @Test void afDoesNotUseOrRequireFafAndFafNeedsSameQualifiedStratum() {
        var d = derived(); var source = row(d, "nfe", "XY", "30", "10000", "0.003", "0.0025");
        source.withObject("measurements").remove("faf95"); d.put("status", "INCOMPLETE");
        assertEquals("READY", select(d, policy(true, "XY")).path("status").asText());
        assertEquals("BS1_SEX_FAF_APPLICABILITY_UNRESOLVED", reason(select(d, policy(false, "XY"))));
        source.withObject("/measurements/faf95").put("state", "PRESENT").put("rawValue", "0.0031").put("unit", "fraction")
                .put("applicability", "APPLICABLE").put("confidence", "95_PERCENT_LOWER_BOUND");
        assertEquals("BS1_SEX_FAF_INVALID_OR_MISSING", reason(select(d, policy(false, "XY"))));
        source.withObject("/measurements/faf95").put("rawValue", "0.0025").put("applicability", "NOT_APPLICABLE");
        assertEquals("BS1_SEX_FAF_APPLICABILITY_UNRESOLVED", reason(select(d, policy(false, "XY"))));
    }

    @Test void fafSelectsItsOwnSourceMaximumInsteadOfMaximumAf() {
        var d = derived(); row(d, "nfe", "XX", "30", "10000", "0.003", "0.0025"); row(d, "nfe", "XY", "40", "10000", "0.004", "0.002");
        var p = policy(false, "XX", "XY"); var result = select(d, p);
        assertEquals("XX", result.at("/facts/karyotype").asText()); assertEquals("0.0025", result.at("/facts/FAF95").asText());
        assertEquals("TRUE", condition(result, p)); assertEquals("SOURCE_STRATUM_FAF95", result.at("/frequencySelection/comparisonBasis").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"NO_RECORD", "FILTER", "COUNT", "AC", "AN", "AF", "DUPLICATE", "UNIT"})
    void failuresNeverBecomeNegativeFrequencyConclusions(String fault) {
        var d = derived(); var r = row(d, "nfe", "XY", "30", "10000", "0.003", "0.0025");
        switch (fault) {
            case "NO_RECORD" -> d.put("status", "NO_RECORD");
            case "FILTER" -> d.put("recordFilter", "LowQual");
            case "COUNT" -> r.withObject("/measurements/AF").put("rawValue", "0.03");
            case "AC" -> r.withObject("/measurements/AC").put("rawValue", "10001");
            case "AN" -> r.withObject("/measurements/AN").put("state", "MISSING_KEY");
            case "AF" -> r.withObject("measurements").remove("AF");
            case "DUPLICATE" -> d.withArray("strata").add(r.deepCopy());
            case "UNIT" -> r.put("denominatorUnit", "PEOPLE");
        }
        var result = select(d, policy(true, "XY")); assertEquals("BLOCKED", result.path("status").asText()); assertFalse(reason(result).isBlank());
        assertFalse(result.has("evaluationResult"));
    }

    @Test void expertOnlySourceConfidenceAndRequiredDatasetAreExplicit() {
        var p = policy(true, "ALL"); assertNull(Bs1SexChromosomePolicy.issue(p));
        p.withObject("/scope/sexChromosome").put("requiredDataset", "JOINT"); adopt(p);
        assertEquals("BS1_SEX_DATASET_STATISTIC_MISMATCH", Bs1SexChromosomePolicy.issue(p));
        p = policy(true, "ALL"); p.withObject("/statistic").put("confidence", "95_PERCENT_LOWER_BOUND"); adopt(p);
        assertEquals("BS1_SEX_STATISTIC_INVALID", Bs1SexChromosomePolicy.issue(p));
        p = policy(true, "ALL"); p.withObject("/knowledgeRecord").put("kind", "MAX_CREDIBLE_AF");
        assertEquals("BS1_SEX_EXPERT_POLICY_REQUIRED", Bs1SexChromosomePolicy.issue(p));
        p = policy(true, "ALL"); p.withObject("/knowledgeRecord").remove("qualification");
        assertEquals("BS1_SEX_QUALIFICATION_REQUIRED", Bs1SexChromosomePolicy.issue(p));
        p = policy(true, "ALL"); p.withObject("/tiers/0/conditions/0").put("basis", "REVIEWED_MODEL_CONDITION"); adopt(p);
        assertEquals("BS1_SEX_TIER_UNSUPPORTED", Bs1SexChromosomePolicy.issue(p));
    }

    @Test void boundScopeDoesNotAcceptUnknownCatalogModeOrReusedQualification() {
        var p = policy(true, "XY"); p.withObject("/scope/sexChromosome").put("regionCatalogSha256", "b".repeat(64)); adopt(p);
        assertEquals("BS1_SEX_REGION_SCOPE_INVALID", Bs1SexChromosomePolicy.issue(p));
        p = policy(true, "XY"); p.withObject("scope").putArray("modes").add("AUTOSOMAL_DOMINANT");
        p.withObject("/tiers/0").put("moi", "AUTOSOMAL_DOMINANT"); adopt(p);
        assertEquals("BS1_SEX_MOI_REGION_CONFLICT", Bs1SexChromosomePolicy.issue(p));
        p = policy(true, "XY"); p.withObject("/knowledgeRecord/expertPolicy/statistic").put("locator", "SYNTHETIC: changed source");
        assertEquals("BS1_QUALIFICATION_BINDING_INVALID", Bs1SexChromosomePolicy.issue(p));
    }

    ObjectNode context() throws Exception {
        var context = new Bs1CoreTest().context(); var p = policy(true, "XY");
        context.withObject("gene").put("hgncId", p.at("/scope/hgncId").asText());
        context.withObject("disease").put("mondoId", p.at("/scope/mondoId").asText()); context.put("moi", "X_LINKED_DOMINANT");
        var allele = context.withObject("/variant/evidenceQueryAllele"); allele.put("contig", "X").put("position", 3000000).put("reference", "C").put("alternate", "T");
        context.withObject("/variant/inputAllele").setAll(allele); context.withObject("/variant/normalizedAllele").setAll(allele);
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        context.withObject("variant").put("alleleId", adapter.canonicalAlleleId(allele, context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalVersionedContextId(context)); return context;
    }

    @Test void regionChecksUseBoundAssemblyFullEventAndActualCatalog() throws Exception {
        var p = policy(true, "XY"); var c = context(); assertNull(Bs1SexChromosomePolicy.regionIssue(mapper, p, c));
        c.withObject("/variant/evidenceQueryAllele").put("assembly", "GRCh37");
        assertEquals("BS1_SEX_ASSEMBLY_OUT_OF_SCOPE", Bs1SexChromosomePolicy.regionIssue(mapper, p, c));
        c.withObject("/variant/evidenceQueryAllele").put("assembly", "GRCh38").put("position", 2781479);
        assertEquals("BS1_SEX_REGION_OUT_OF_SCOPE", Bs1SexChromosomePolicy.regionIssue(mapper, p, c));
        c.withObject("/variant/evidenceQueryAllele").put("position", 2781478).put("reference", "CAA").put("alternate", "C");
        assertEquals("BS1_SEX_REGION_UNRESOLVED", Bs1SexChromosomePolicy.regionIssue(mapper, p, c));
    }

    @Test void parAutosomalModeAndYLinkedRequireTheirExplicitExpertScope() throws Exception {
        var p = policy(true, "ALL"); p.withObject("scope").putArray("modes").add("AUTOSOMAL_RECESSIVE");
        p.withObject("/tiers/0").put("moi", "AUTOSOMAL_RECESSIVE"); p.withObject("/scope/sexChromosome").putArray("regions").add("PAR"); adopt(p);
        assertNull(Bs1SexChromosomePolicy.issue(p)); var c = context(); c.put("moi", "AUTOSOMAL_RECESSIVE");
        c.withObject("/variant/evidenceQueryAllele").put("position", 2781479);
        assertNull(Bs1SexChromosomePolicy.regionIssue(mapper, p, c));
        p = policy(true, "XY"); p.withObject("scope").putArray("modes").add("Y_LINKED"); p.withObject("/tiers/0").put("moi", "Y_LINKED");
        p.withObject("/scope/sexChromosome").putArray("contigs").add("Y"); adopt(p); assertNull(Bs1SexChromosomePolicy.issue(p));
        c.put("moi", "Y_LINKED"); c.withObject("/variant/evidenceQueryAllele").put("contig", "Y").put("position", 20000000);
        assertNull(Bs1SexChromosomePolicy.regionIssue(mapper, p, c));
    }

    @Test void productionBoundaryRederivesRawAndPreservesOldParent() throws Exception {
        var p = policy(true, "XY"); var c = context(); var fixture = new Bs1LocalExomesTest();
        var info = new LinkedHashMap<>(fixture.facts.info("0.00004", "10000"));
        info.putAll(Map.of("AC_nfe_XY", "21", "AN_nfe_XY", "10000", "AF_nfe_XY", "0.0021"));
        var shared = fixture.shared(c, info, "PASS"); var parent = Bs1LocalExomes.sharedObservation(mapper, c, shared, SOURCE); var before = parent.deepCopy();
        var result = Bs1SexChromosomeStatistic.select(mapper, parent, c, SOURCE, p);
        assertEquals("READY", result.path("status").asText(), result.toPrettyString()); assertEquals("TRUE", condition(result, p));
        assertEquals(before, parent); assertEquals(parent.path("observationId"), result.at("/sexStratifiedObservation/parentObservationId"));
        parent.put("rawRecord", parent.path("rawRecord").asText().replace("AC_nfe_XY=21", "AC_nfe_XY=22"));
        assertEquals("BS1_SEX_SOURCE_VERIFICATION_FAILED", reason(Bs1SexChromosomeStatistic.select(mapper, parent, c, SOURCE, p)));
    }
}
