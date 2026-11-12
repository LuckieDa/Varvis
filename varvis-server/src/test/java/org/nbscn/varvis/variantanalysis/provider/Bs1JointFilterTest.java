package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicitly synthetic transport facts; official release semantics are exercised, not network availability. */
class Bs1JointFilterTest {
    final Bs1CoreTest fixture = new Bs1CoreTest();
    final com.fasterxml.jackson.databind.ObjectMapper mapper = fixture.mapper;
    static final String SOURCE = Bs1CoreTest.SOURCE;
    ObjectNode context() throws Exception { return fixture.context(); }
    Map<String, String> info(String faf, String an, String p) { return fixture.info(faf, an, p); }
    ObjectNode evaluate(ObjectNode context, ObjectNode policy, ObjectNode observation, ObjectNode facts) { return fixture.evaluate(context, policy, observation, facts); }
    String status(com.fasterxml.jackson.databind.JsonNode r) { return fixture.status(r); }
    String reason(com.fasterxml.jackson.databind.JsonNode r) { return fixture.reason(r); }
    @TempDir Path temporary;
    ObjectNode policy() { return (ObjectNode) Bs1Runtime.policies(mapper).get(0).deepCopy(); }
    ObjectNode bound(Map<String, String> fields, String filter) throws Exception {
        var observation = fixture.observation(fields, filter);
        var transport = mapper.createObjectNode().put("querySource", "remote-joint-vcf-population-input")
                .put("sourceDataset", "joint").put("gnomadVersion", "gnomAD joint v4.1 GRCh38");
        String uri = Bs1JointFilterSource.contract(mapper).at("/headers/12/url").asText();
        transport.put("resourceUri", uri).put("resourceIndexUri", uri + ".tbi");
        return PopulationObservation.withTransport(mapper, observation, transport);
    }
    Map<String, String> missing(String p) {
        var info = info("0.00004428", "1999", p); info.remove("exomes_filters"); info.remove("genomes_filters"); return info;
    }
    ObjectNode result(ObjectNode observation) throws Exception { return evaluate(context(), policy(), observation, mapper.createObjectNode()); }
    @Test void boundPassDoesNotInventSubtypePresenceOrQueryNative() throws Exception {
        var o = bound(missing("0.5"), "PASS"); var before = o.deepCopy(); var r = result(o);
        assertEquals("MET", status(r)); assertEquals(before, o);
        assertEquals(r, mapper.readTree(mapper.writeValueAsBytes(r)));
        assertEquals("SATISFIED_BY_JOINT_FILTER_DEFINITION", r.at("/qualityDecision/filterCondition/decision").asText());
        assertEquals("MISSING_KEY", r.at("/qualityDecision/rawFieldStates/exomes_filters/state").asText());
        assertEquals(1, r.at("/qualityDecision/filterCondition/sourceEvidenceIds").size());
        var supplement = mock(Bs1NativeFilterSupplement.class);
        var runtime = new Bs1Runtime(mapper, List.of(policy()), mock(VariantEvidenceProvider.class), supplement);
        runtime.replay(context(), o, SOURCE); verifyNoInteractions(supplement);
        assertEquals(0, runtime.counts().path("native").asInt());
    }
    @Test void sameBytesWithoutActualSourceBindingRemainUnevaluated() throws Exception {
        assertEquals("BS1_JOINT_FILTER_DEFINITION_UNBOUND", reason(result(fixture.observation(missing("0.5"), "PASS"))));
        var o = bound(missing("0.5"), "PASS");
        o.withObject("/transport").put("sourceDataset", "exomes"); PopulationObservation.withTransport(mapper, o, o.path("transport"));
        assertEquals("NOT_EVALUATED", status(result(o)));
        var policy = policy(); policy.withObject("/qualityContract/releaseSemantics").put("jointFilterDefinition", "UNKNOWN");
        assertEquals("BS1_RELEASE_SEMANTICS_UNSUPPORTED", Bs1Policy.issue(policy));
    }
    @Test void nativeAndExplicitConflictsCannotBeHiddenByPass() throws Exception {
        var fields = missing("0.5"); fields.put("exomes_filters", "AS_VQSR");
        assertEquals("CONFLICT", result(bound(fields, "PASS")).at("/qualityDecision/filterCondition/status").asText());
        for (String rawState : List.of("PASS", "MISSING")) {
            fields = missing("0.5"); if (rawState.equals("PASS")) fields.put("exomes_filters", "PASS");
            var o = bound(fields, "PASS"); var facts = mapper.createObjectNode();
            facts.set("exomes", Bs1NativeFilterSupplement.proof(mapper, "exomes", o, "chr12\t120988847\t.\tG\tA\t.\tAS_VQSR\t.\n"));
            var r = evaluate(context(), policy(), o, facts); assertEquals("NOT_EVALUATED", status(r));
            assertEquals("CONFLICT", r.at("/qualityDecision/filterCondition/status").asText());
        }
        var o = bound(missing("0.5"), "PASS"); var facts = mapper.createObjectNode();
        for (String subtype : List.of("exomes", "genomes")) facts.set(subtype, Bs1NativeFilterSupplement.proof(mapper, subtype, o, ""));
        assertEquals("BS1_JOINT_NATIVE_PRESENCE_CONFLICT", reason(evaluate(context(), policy(), o, facts)));
    }
    @Test void jointFailuresAndDotNeverPassAndNativeCannotWashThem() throws Exception {
        for (String filter : List.of("EXOMES_FILTERED", "GENOMES_FILTERED", "BOTH_FILTERED", ".")) {
            var o = bound(missing("0.5"), filter); var facts = mapper.createObjectNode();
            for (String subtype : List.of("exomes", "genomes")) facts.set(subtype, Bs1NativeFilterSupplement.proof(mapper, subtype, o, "chr12\t120988847\t.\tG\tA\t.\tPASS\t.\n"));
            assertEquals("BS1_JOINT_FILTER_NOT_PASS", reason(evaluate(context(), policy(), o, facts)));
            assertTrue(Bs1Quality.supplementNeeds(o, policy()).isEmpty());
        }
        var fields = missing("0.5"); fields.put("exomes_filters", ".");
        var r = result(bound(fields, "PASS")); assertEquals("MET", status(r));
        assertEquals("DOT", r.at("/qualityDecision/rawFieldStates/exomes_filters/state").asText());
    }
    @Test void numbersAndPRemainIndependentAndGn017UnknownIsNotExempt() throws Exception {
        for (String key : List.of("fafmax_faf95_max_joint", "faf95_joint_nfe", "AN_joint_nfe", "AC_joint_nfe", "AF_joint_nfe", "fafmax_faf95_max_gen_anc_joint")) {
            var fields = missing("0.5"); fields.remove(key); assertEquals("NOT_EVALUATED", status(result(bound(fields, "PASS"))), key);
        }
        assertEquals("BS1_LOCAL_DISCORDANCE_REVIEW_REQUIRED", reason(result(bound(missing("0.00001"), "PASS"))));
        var unknown = bound(missing("."), "PASS");
        assertEquals("BS1_DISCORDANCE_APPLICABILITY_OR_INPUT_UNKNOWN", reason(result(unknown)));
        assertTrue(Bs1Quality.supplementNeeds(unknown, policy()).isEmpty());
        var extra = policy(); extra.withObject("/qualityContract/releaseSemantics").put("missingSubtype", "OPTIONAL_WITH_NATIVE_PROOF");
        assertEquals(Set.of("exomes", "genomes"), Bs1Quality.supplementNeeds(bound(missing("0.5"), "PASS"), extra));
        var dotRequired = policy(); dotRequired.withObject("/qualityContract/releaseSemantics").put("subtypeDot", "UNKNOWN_REQUIRES_NATIVE");
        var dotFields = missing("0.5"); dotFields.put("exomes_filters", ".");
        var dot = bound(dotFields, "PASS");
        assertEquals("NOT_EVALUATED", status(evaluate(context(), dotRequired, dot, mapper.createObjectNode())));
        assertEquals(Set.of("exomes"), Bs1Quality.supplementNeeds(dot, dotRequired));
    }
    @Test void rawProjectionWrongAltAndTamperingRejectBeforeJointInterpretation() throws Exception {
        var o = bound(missing("0.5"), "PASS");
        for (String raw : List.of(o.path("rawRecord").asText().replace("AN_joint_nfe=1999;", ""),
                o.path("rawRecord").asText().replace("\tG\tA\t", "\tG\tT\t"),
                o.path("rawRecord").asText() + "\n", o.path("rawRecord").asText() + ";AN_joint_nfe=bad")) {
            assertThrows(IllegalArgumentException.class, () -> PopulationObservation.seal(mapper, (ObjectNode) o.path("carrier"), raw,
                    GnomadPopulationInputProfile.loadExecutableV2(), SOURCE, "SYNTHETIC", "SYNTHETIC_CORRUPTION"));
        }
        o.withObject("/transport").put("resourceUri", "unknown");
        assertThrows(IllegalArgumentException.class, () -> PopulationObservation.verify(mapper, o, context(), SOURCE));
    }
    @Test void nativeBudgetIsOnePerRequestedSubtypeAndKeepsNoRecordOrFailure() throws Exception {
        var settings = new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        var script = temporary.resolve("native.cmd"); var calls = temporary.resolve("calls.txt");
        Files.writeString(script, "@echo off\r\necho call>>\"" + calls + "\"\r\nexit /b 0\r\n");
        settings.getGnomad().setTabixExecutable(script.toString()); settings.getGnomad().setTimeout(java.time.Duration.ofSeconds(5));
        settings.getGnomad().setRemoteIndexCacheDir(temporary.resolve("index").toString());
        var nativeProvider = new Bs1NativeFilterSupplement(mapper, settings, temporary);
        var o = bound(missing("0.5"), "PASS");
        var facts = nativeProvider.supplement(o, Set.of("exomes", "genomes")); assertEquals(2, Files.readAllLines(calls).size());
        assertEquals("NO_RECORD", Bs1NativeFilterSupplement.verifiedState(mapper, facts.path("exomes"), "exomes", o, policy()));
        assertEquals("NOT_EVALUATED", status(evaluate(context(), policy(), o, facts)));
        Files.writeString(script, "@echo off\r\necho call>>\"" + calls + "\"\r\nexit /b 1\r\n");
        var failed = nativeProvider.supplement(o, Set.of("exomes")); assertEquals(3, Files.readAllLines(calls).size());
        assertEquals("NOT_EVALUATED", status(evaluate(context(), policy(), o, failed)));
    }
}
