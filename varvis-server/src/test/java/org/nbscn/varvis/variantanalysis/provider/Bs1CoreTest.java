package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic observations only. Threshold expectations are declared independently of evaluator output. */
class Bs1CoreTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final PopulationFrequencyRuntimeTest fixture = new PopulationFrequencyRuntimeTest();
    static final String SOURCE = "a".repeat(64);
    ObjectNode policy() {
        var policy = (ObjectNode) Bs1Runtime.policies(mapper).get(0).deepCopy();
        policy.withObject("/qualityContract/releaseSemantics").remove("jointFilterDefinition");
        policy.withObject("/qualityContract/releaseSemantics").put("missingSubtype", "REQUIRED").put("subtypeDot", "UNKNOWN_REQUIRES_NATIVE");
        return policy;
    }
    ObjectNode context() throws Exception {
        var context = fixture.diseaseContext("HNF1A");
        context.put("schemaVersion", "evidence-context-v3"); context.remove("transcript"); refresh(context); return context;
    }
    void refresh(ObjectNode context) { context.put("contextId", adapter.canonicalVersionedContextId(context)); }
    Map<String, String> info(String faf, String an, String p) {
        var info = fixture.jointInfo(faf, an, p); info.put("exomes_filters", "PASS"); info.put("genomes_filters", "PASS"); return info;
    }
    ObjectNode observation(Map<String, String> info, String filter) throws Exception {
        var old = fixture.observation(fixture.diseaseContext("HNF1A"), info, filter);
        return PopulationObservation.restore(mapper, old, SOURCE, old.at("/context/dataClass").asText());
    }
    ObjectNode evaluate(ObjectNode context, ObjectNode policy, ObjectNode observation, ObjectNode nativeFacts) {
        var selected = AcmgSpecificationSelector.selectBs1(mapper, List.of(policy), context);
        assertEquals("READY", selected.path("status").asText(), selected.toString());
        return new PopulationFrequencyRuntime(mapper).evaluateBs1(context, observation, SOURCE, selected, nativeFacts);
    }
    ObjectNode evaluate(String faf, String an, ObjectNode policy) throws Exception {
        return evaluate(context(), policy, observation(info(faf, an, "0.5"), "PASS"), mapper.createObjectNode());
    }
    String status(JsonNode result) { return result.at("/evaluation/evaluationResult/status").asText(); }
    String reason(JsonNode result) { return result.at("/evaluation/evaluationResult/reasonCode").asText(); }
    @Test void noTranscriptNoPm2NoBa1ExceptionStillEvaluatesAndV2IdentityStaysFixed() throws Exception {
        var old = fixture.diseaseContext("HNF1A"); String oldId = old.path("contextId").asText();
        adapter.validateCompleteContext(old); assertEquals(oldId, adapter.canonicalContextId(old));
        var context = context(); adapter.validateVersionedContext(context);
        assertThrows(IllegalArgumentException.class, () -> adapter.validateCompleteContext(context));
        assertNotEquals(oldId, context.path("contextId").asText());
        var observation = observation(info("0.00004428", "1999", "0.5"), "PASS");
        var result = new Bs1Runtime(mapper, List.of(policy()), mock(VariantEvidenceProvider.class), null).replay(context, observation, SOURCE);
        assertEquals("MET", status(result)); assertFalse(result.has("pm2"));
        assertEquals("STRONG", result.at("/evaluation/appliedStrength").asText());
        assertEquals("NOT_REQUIRED_BY_POLICY", result.at("/preparation/exceptionResolution/status").asText());
        assertEquals("1999", result.at("/qualityDecision/facts/AN").asText());
        assertEquals("REPARSED_VERIFIED_ARCHIVED_OBSERVATION", observation.path("origin").asText());
        assertEquals("2026-09-02T00:00:00Z", observation.path("observedAt").asText());
        assertEquals(result, mapper.readTree(mapper.writeValueAsBytes(result)));
        var identity = ((ObjectNode) result.path("evaluation")).deepCopy();
        String id = identity.remove("evaluationId").asText(); identity.remove(List.of("conflict", "review"));
        assertEquals("evaluation-sha256-" + adapter.canonicalSha256(identity), id);
    }
    @Test void exactDecimalBoundariesZeroAndOfficialAnAreIndependent() throws Exception {
        for (String[] row : List.of(new String[]{"0.000032999999", "NOT_MET"}, new String[]{"0.000033", "MET"},
                new String[]{"0.000033000001", "MET"}, new String[]{"0", "NOT_MET"})) assertEquals(row[1], status(evaluate(row[0], "1999", policy())));
        var greater = policy(); greater.withObject("/tiers/0/conditions/0").put("operator", "GT");
        assertEquals("NOT_MET", status(evaluate("0.000033", "1999", greater)));
        var official = policy(); var condition = official.withArray("/commonConditions").addObject();
        condition.put("fact", "AN").put("operator", "GTE").put("decimal", "2000").put("unit", "alleles")
                .put("basis", "OFFICIAL_CONDITION").put("originalText", "SYNTHETIC minimum 2000").put("sourceLocator", "SYNTHETIC TEST ONLY");
        assertEquals("NOT_EVALUATED", status(evaluate("0.00004428", "1999", official)));
        assertEquals("BS1_COMMON_CONDITION_NOT_SATISFIED", reason(evaluate("0.00004428", "1999", official)));
        official.withObject("/tiers/0/conditions/0").put("unknownConstraint", true);
        assertEquals("BS1_POLICY_TIER_UNSUPPORTED", Bs1Policy.issue(official));
    }
    @Test void multiTierPriorityUpperBoundsAndUnknownDoNotFallThrough() throws Exception {
        var policy = policy(); var strong = policy.withObject("/tiers/0");
        strong.withObject("/conditions/0").put("decimal", "0.003");
        var supporting = strong.deepCopy().put("tierId", "synthetic-supporting").put("priority", 2).put("strength", "SUPPORTING");
        supporting.withObject("/conditions/0").put("decimal", "0.0007"); policy.withArray("/tiers").add(supporting);
        for (String[] row : List.of(new String[]{"0.003", "MET", "STRONG"}, new String[]{"0.0007", "MET", "SUPPORTING"}, new String[]{"0.00069999", "NOT_MET", "NONE"})) {
            var result = evaluate(row[0], "10000", policy); assertEquals(row[1], status(result)); assertEquals(row[2], result.at("/evaluation/appliedStrength").asText());
        }
        var upper = supporting.withObject("/conditions/0").deepCopy().put("operator", "LT").put("decimal", "0.002");
        supporting.withArray("/conditions").add(upper);
        assertEquals("NOT_MET", status(evaluate("0.002", "10000", policy)));
        upper.put("operator", "LTE"); assertEquals("MET", status(evaluate("0.002", "10000", policy)));
        var result = evaluate("0.001", "10000", policy); var q = (ObjectNode) result.path("qualityDecision").deepCopy(); q.withObject("/facts").remove("AC");
        strong.withArray("/conditions").addObject().put("fact", "AC").put("operator", "GTE").put("decimal", "1")
                .put("unit", "alleles").put("basis", "OFFICIAL_CONDITION").put("originalText", "SYNTHETIC").put("sourceLocator", "SYNTHETIC");
        var unknown = Bs1CriterionEvaluator.evaluate(mapper, context(), (ObjectNode) result.path("evidence"), policy,
                AcmgSpecificationSelector.selectBs1(mapper, List.of(policy), context()), q);
        assertEquals("NOT_EVALUATED", unknown.at("/evaluationResult/status").asText());
        assertEquals("BS1_TIER_INPUT_UNKNOWN", unknown.at("/evaluationResult/reasonCode").asText());
        var tierAn = strong.withArray("/conditions").addObject();
        tierAn.put("fact", "AN").put("operator", "GTE").put("decimal", "20000").put("unit", "alleles")
                .put("basis", "OFFICIAL_CONDITION").put("originalText", "SYNTHETIC tier sample requirement").put("sourceLocator", "SYNTHETIC");
        assertEquals("NOT_EVALUATED", status(evaluate("0.001", "10000", policy)));
    }
    @Test void gn005FullSourceRowsPendingCompatibilityAndDualModeSemantics() throws Exception {
        var all = Bs1Runtime.policies(mapper); assertEquals(12, all.size());
        for (var p : all.subList(1, all.size())) {
            assertEquals(10, p.at("/exceptionSet/entries").size());
            assertEquals("PENDING_COMPATIBILITY_REVIEW", p.at("/resourceCompatibility/status").asText());
            for (var e : p.at("/exceptionSet/entries")) assertFalse(e.path("clinvarId").asText().isBlank());
            for (var tier : p.path("tiers")) {
                boolean ar = "AUTOSOMAL_RECESSIVE".equals(tier.path("moi").asText());
                assertEquals(ar ? "SUPPORTING".equals(tier.path("strength").asText()) ? "0.0007" : "0.003" : "0.0002",
                        tier.at("/conditions/0/decimal").asText());
                assertEquals("GTE", tier.at("/conditions/0/operator").asText());
            }
        }
        var pending = (ObjectNode) all.get(1).deepCopy(); var context = context();
        context.withObject("/gene").put("hgncId", pending.at("/scope/hgncId").asText());
        context.withObject("/disease").put("mondoId", pending.at("/scope/mondoId").asText());
        context.put("moi", "AUTOSOMAL_RECESSIVE"); refresh(context);
        assertEquals("BS1_RESOURCE_COMPATIBILITY_PENDING", AcmgSpecificationSelector.selectBs1(mapper, List.of(pending), context).path("reasonCode").asText());
        // Mechanical conditional semantics, explicitly synthetic; does not activate the source policy.
        var dual = policy(); dual.withObject("/scope").put("modeSelection", "DUAL_MODE_USE_AR");
        dual.withObject("/scope").putArray("geneModes").add("AUTOSOMAL_DOMINANT").add("AUTOSOMAL_RECESSIVE");
        dual.withObject("/tiers/0").put("moi", "AUTOSOMAL_RECESSIVE");
        var selected = AcmgSpecificationSelector.selectBs1(mapper, List.of(dual), context());
        assertEquals("AUTOSOMAL_RECESSIVE", selected.path("selectedMode").asText());
        assertEquals("READY", selected.path("status").asText());
        var wrongDiseaseMode = context(); wrongDiseaseMode.put("moi", "AUTOSOMAL_RECESSIVE"); refresh(wrongDiseaseMode);
        assertEquals("BS1_POLICY_NO_RECORD", AcmgSpecificationSelector.selectBs1(mapper, List.of(dual), wrongDiseaseMode).path("reasonCode").asText());
    }
    @Test void scopeNeedsTranscriptAndUnknownConditionsRejectWithoutQuery() throws Exception {
        var policy = policy(); policy.withObject("/requiredContext").put("transcript", "REQUIRED_BY_SCOPE");
        policy.withArray("/scope/transcripts").addObject().put("accession", "NM_000545").put("version", 8);
        var selected = AcmgSpecificationSelector.selectBs1(mapper, List.of(policy), context());
        assertEquals("NEEDS_INPUT", selected.path("status").asText());
        policy.putObject("unimplementedRule").put("condition", "unknown"); assertEquals("BS1_POLICY_UNKNOWN_CONDITION", Bs1Policy.issue(policy));
        var context = context(); context.putNull("transcript");
        assertThrows(IllegalArgumentException.class, () -> adapter.validateVersionedContext(context));
    }
    @Test void exceptionIdentityScopeAllelesAndCriterionStaySeparate() throws Exception {
        var policy = policy(); var set = policy.withObject("/exceptionSet");
        var entry = set.withArray("/entries").addObject().put("entryId", "SYNTHETIC_EXCEPTION").put("hgncId", "HGNC:11621")
                .put("mondoId", "MONDO:0015967").put("identityStatus", "UNRESOLVED");
        assertEquals("UNRESOLVED", Bs1Policy.exceptions(mapper, policy, context()).path("status").asText());
        entry.put("hgncId", "HGNC:1"); assertEquals("OUT_OF_SCOPE", Bs1Policy.exceptions(mapper, policy, context()).at("/checks/0/status").asText());
        entry.put("hgncId", "HGNC:11621").put("identityStatus", "VERIFIED_ALLELE").put("disposition", "NOT_APPLICABLE");
        entry.set("mappingSource", policy.path("sources").deepCopy()); entry.set("queryAllele", context().at("/variant/evidenceQueryAllele").deepCopy());
        assertEquals("HIT", Bs1Policy.exceptions(mapper, policy, context()).path("status").asText());
        var result = new Bs1Runtime(mapper, List.of(policy), mock(VariantEvidenceProvider.class), null).replay(context(), mapper.createObjectNode(), SOURCE);
        assertEquals("NOT_APPLICABLE", result.at("/evaluation/criterionApplicability/status").asText());
        entry.withObject("/queryAllele").put("alternate", "T");
        assertEquals("NOT_HIT_IN_VERIFIED_SCOPE", Bs1Policy.exceptions(mapper, policy, context()).path("status").asText());
        entry.withObject("/queryAllele").put("assembly", "GRCh37"); assertEquals("UNRESOLVED", Bs1Policy.exceptions(mapper, policy, context()).path("status").asText());
        set.put("criterionId", "ACMG_AMP_2015:BA1"); assertEquals("UNRESOLVED", Bs1Policy.exceptions(mapper, policy, context()).path("status").asText());
    }
    @Test void rawStatesNativeFactsMissingKeysAndDiscordanceRemainDistinct() throws Exception {
        var policy = policy();
        for (String state : List.of(".", "", "AS_VQSR", "MISSING")) {
            var info = info("0.00004428", "10000", "0.5");
            if (state.equals("MISSING")) info.remove("exomes_filters"); else info.put("exomes_filters", state);
            var observation = observation(info, "PASS"); var nativeFacts = mapper.createObjectNode();
            nativeFacts.set("exomes", Bs1NativeFilterSupplement.proof(mapper, "exomes", observation, "chr12\t120988847\t.\tG\tA\t.\tPASS\t.\n"));
            var result = evaluate(context(), policy, observation, nativeFacts);
            assertEquals(state.equals(".") ? "MET" : "NOT_EVALUATED", status(result));
            assertEquals(state.equals("MISSING") ? "MISSING_KEY" : state.isEmpty() ? "EMPTY" : state.equals(".") ? "DOT" : "FILTERED",
                    result.at("/qualityDecision/rawFieldStates/exomes_filters/state").asText());
            if (!state.equals(".")) assertTrue(Bs1Quality.supplementNeeds(observation, policy).isEmpty());
        }
        var lowP = evaluate(context(), policy, observation(info("0.00004428", "10000", "0.00001"), "PASS"), mapper.createObjectNode());
        assertEquals("BS1_LOCAL_DISCORDANCE_REVIEW_REQUIRED", reason(lowP));
        assertEquals("LOCAL_ENGINEERING_SAFEGUARD", lowP.at("/qualityDecision/discordance/dispositionBasis").asText());
        var absentInfo = info("0.00004428", "10000", "."); absentInfo.put("exomes_filters", ".");
        var observation = observation(absentInfo, "PASS"); var facts = mapper.createObjectNode(); facts.set("exomes", Bs1NativeFilterSupplement.proof(mapper, "exomes", observation, ""));
        assertEquals("NOT_EVALUATED", status(evaluate(context(), policy, observation, facts)));
        policy.withObject("/qualityContract/releaseSemantics").put("pMissing", "ONE_DATASET_ABSENT_WITH_NATIVE_PROOF").put("notApplicableBasis", "SYNTHETIC release branch contract");
        var noComparison = evaluate(context(), policy, observation, facts);
        assertEquals("MET", status(noComparison)); assertEquals("NOT_APPLICABLE", noComparison.at("/qualityDecision/discordance/applicability").asText());
        facts.withObject("/exomes/queryAllele").put("alternate", "T"); assertEquals("NOT_EVALUATED", status(evaluate(context(), policy, observation, facts)));
    }
    @Test void selectedAncestryMissingFafAndIdentityTamperingNeverBecomeZero() throws Exception {
        var info = info("0.00004428", "1999", "0.5"); info.put("grpmax_joint", "afr"); info.put("AN_grpmax_joint", "1000000");
        var observation = observation(info, "PASS"); assertEquals("1999", evaluate(context(), policy(), observation, mapper.createObjectNode()).at("/qualityDecision/facts/AN").asText());
        info.remove("faf95_joint_nfe"); assertEquals("NOT_EVALUATED", status(evaluate(context(), policy(), observation(info, "PASS"), mapper.createObjectNode())));
        observation.put("sourceSnapshot", "b".repeat(64)); assertThrows(IllegalArgumentException.class, () -> PopulationObservation.verify(mapper, observation, context(), SOURCE));
        var context = context(); context.withObject("/disease").put("mondoId", "MONDO:0000001");
        assertThrows(IllegalArgumentException.class, () -> adapter.validateVersionedContext(context));
    }
    @Test void exportSuccessorContractExamplesWhenExplicitlyRequested() throws Exception {
        var examples = mapper.createArrayNode();
        examples.add(evaluate("0.00004428", "1999", policy())); examples.add(evaluate("0", "1999", policy()));
        examples.add(evaluate(context(), policy(), observation(info("0.00004428", "10000", "0.00001"), "PASS"), mapper.createObjectNode()));
        String path = System.getProperty("varvis.bs1.contractOutput");
        if (path != null) java.nio.file.Files.writeString(java.nio.file.Path.of(path), mapper.writeValueAsString(examples));
        assertEquals(3, examples.size());
    }
    @Test void pValueAndFrequencyAreTakenFromTheMatchedAltAndNotItsSibling() throws Exception {
        var info = info("0.00004428", "1999", "0.5"); var profile = GnomadPopulationInputProfile.loadExecutableV2();
        for (var f : profile.getDocument().at("/fieldContract/fields")) {
            String key = f.path("infoId").asText();
            if ("A".equals(f.path("number").asText()) && info.containsKey(key)) info.put(key, info.get(key) + "," + info.get(key));
        }
        for (var f : profile.getDocument().at("/fieldContract/ancestryFieldTemplates")) {
            String key = f.path("infoId").asText().replace("{ancestry}", "nfe");
            if ("A".equals(f.path("number").asText()) && info.containsKey(key)) info.put(key, info.get(key) + "," + info.get(key));
        }
        info.put("stat_union_p_value", "0.000001,0.5"); info.put("stat_union_test_name", "cmh,cmh");
        var original = observation(info("0.00004428", "1999", "0.5"), "PASS");
        var carrier = (ObjectNode) original.path("carrier").deepCopy();
        carrier.set("measurements", mapper.valueToTree(profile.parse("PASS", info, 1, 2).getMeasurements()));
        String raw = "chr12\t120988847\t.\tG\tT,A\t.\tPASS\t" + info.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(";"));
        var observation = PopulationObservation.seal(mapper, carrier, raw, profile, SOURCE, "SYNTHETIC", "SYNTHETIC_MULTIALLELIC_TEST");
        var result = evaluate(context(), policy(), observation, mapper.createObjectNode());
        assertEquals("MET", status(result), result.path("qualityDecision").toString()); assertEquals("0.5", result.at("/qualityDecision/discordance/observedP").asText());
    }
}
