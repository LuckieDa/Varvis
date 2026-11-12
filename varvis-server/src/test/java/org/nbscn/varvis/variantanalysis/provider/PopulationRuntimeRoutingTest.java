package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic provider boundaries; real catalog, routing, acquisition, parent verification and evaluators. */
class PopulationRuntimeRoutingTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Bs1LocalExomesTest local = new Bs1LocalExomesTest();
    final Bs1JointSexChromosomeRuntimeTest joint = new Bs1JointSexChromosomeRuntimeTest();
    @TempDir Path temporary;
    static final String SOURCE = "a".repeat(64);

    final class Source implements VariantEvidenceProvider {
        final GnomadPopulationInputProfile profile;
        final Map<String,String> fields;
        final AtomicInteger calls = new AtomicInteger();
        boolean fail;
        @org.springframework.lang.Nullable VariantInterpretation lastCandidate;
        @org.springframework.lang.Nullable AnalysisContext lastAnalysis;
        Source(boolean isJoint, Map<String,String> fields) { profile = isJoint ? GnomadPopulationInputProfile.loadExecutableV2() : GnomadPopulationInputProfile.loadLocalBa1(); this.fields = fields; }
        @Override public String source() { return "GNOMAD"; }
        @Override public String version() { return profile.getDocument().path("resources").path(0).path("resourceVersion").asText(); }
        @Override public Map<NormalizedVariantKey,List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext analysis) {
            calls.incrementAndGet(); if (fail) throw new IllegalStateException("SYNTHETIC_SOURCE_FAILURE");
            var result = new LinkedHashMap<NormalizedVariantKey,List<EvidenceItem>>();
            for (var candidate : variants) {
                lastCandidate=candidate; lastAnalysis=analysis;
                var q = candidate.getObservation().getKey(); var resource = profile.getDocument().path("resources").path(0);
                String raw = "chr" + q.getChromosome() + "\t" + q.getPosition() + "\t.\t" + q.getReference() + "\t" + q.getAlternate() + "\t.\tPASS\t"
                        + String.join(";", fields.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
                var details = new LinkedHashMap<String,Object>();
                details.put("resourceExecutionStatus", "SUCCEEDED"); details.put("resourceAvailabilityStatus", "AVAILABLE");
                details.put("queryOutcomeStatus", "HIT"); details.put("resourceApplicabilityStatus", "APPLICABLE");
                details.put("populationResourceId", resource.path("resourceId").asText()); details.put("populationResourceIdentityFingerprintSha256", resource.path("identityFingerprintSha256").asText());
                details.put("populationInputProfileId", profile.getProfileId()); details.put("populationInputProfileVersion", profile.getProfileVersion()); details.put("populationInputProfileSha256", profile.getProfileSha256());
                details.put("populationMeasurements", profile.parse("PASS", fields, 0, 1).getMeasurements()); details.put("rawPopulationRecord", raw);
                details.put("gnomadVersion", version());
                if ("JOINT".equals(profile.getDatasetType())) {
                    details.put("querySource", "remote-joint-vcf-population-input"); details.put("sourceDataset", "joint");
                    details.put("resourceUri", profile.getRemoteJointUri(q.getChromosome())); details.put("resourceIndexUri", profile.getRemoteJointIndexUri(q.getChromosome()));
                }
                result.put(q, List.of(new EvidenceItem("GNOMAD", version(), "POPULATION", "APPLICABLE", null, "SYNTHETIC", "SYNTHETIC_ROUTING_CONTROL", details)));
            }
            return result;
        }
    }
    VariantInterpretation candidate(ObjectNode context) {
        var q=context.at("/variant/evidenceQueryAllele");
        return RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey(q.path("assembly").asText(),q.path("contig").asText(),q.path("position").asInt(),q.path("reference").asText(),q.path("alternate").asText()));
    }
    ObjectNode evaluate(Bs1Runtime runtime, ObjectNode context, String snapshot, JsonNode shared) {
        var candidate=candidate(context); return runtime.evaluateRouted(context,candidate,RuntimeEvidenceV2ProviderWiringTest.analysisContext(candidate.getObservation().getKey()),snapshot,shared);
    }
    Bs1Runtime runtime(List<JsonNode> policies, Source exomes, Source jointSource) { return Bs1Runtime.routedDevelopment(mapper,policies,exomes,jointSource,null); }
    JsonNode jointPolicy() throws Exception { joint.temporary=temporary; return joint.runtime(joint.record(true)).policyRecords().get(0); }
    String status(JsonNode result) { return result.at("/evaluation/evaluationResult/status").asText(); }
    String reason(JsonNode result) { return result.at("/evaluation/evaluationResult/reasonCode").asText(); }

    @Test void policyMetricDatasetAndResourceMustAgreeBeforeAnyQuery() throws Exception {
        var exome=local.policy("GN017"); var jp=jointPolicy();
        assertEquals("EXOMES",PopulationRuntimeRouting.route(mapper,exome).path("dataset").asText());
        assertEquals("JOINT",PopulationRuntimeRouting.route(mapper,jp).path("dataset").asText());
        for (String fault:List.of("DATASET","METRIC","RESOURCE","VERSION")) {
            var wrong=(ObjectNode)jp.deepCopy();
            switch(fault) {
                case "DATASET" -> wrong.withObject("/scope/sexChromosome").put("requiredDataset","EXOMES");
                case "METRIC" -> wrong.withObject("/statistic").put("metricId","gnomad.exomes.sex_ancestry_max_af");
                case "RESOURCE" -> wrong.withObject("/resourceCompatibility").put("resourceSha256",GnomadPopulationInputProfile.loadLocalBa1().getDocument().at("/resources/0/identityFingerprintSha256").asText());
                case "VERSION" -> wrong.withObject("/resourceCompatibility").put("resourceVersion","gnomAD joint v4.0 GRCh38");
                default -> throw new AssertionError(fault);
            }
            assertEquals("BLOCKED",PopulationRuntimeRouting.route(mapper,wrong).path("status").asText(),fault);
        }
    }
    @Test void exomesAcquireOnceAcrossDifferentDiseaseBackgroundsAndRetainNeutralParent() throws Exception {
        var exomes=new Source(false,local.facts.info("0.00004","10000")); var js=new Source(true,joint.fields("21","10000"));
        var runtime=runtime(Bs1LocalExomes.policies(mapper),exomes,js);
        var first=evaluate(runtime,local.context("GN017"),SOURCE,null); var second=evaluate(runtime,local.context("COCH"),SOURCE,null);
        assertEquals("MET",status(first)); assertEquals("NOT_MET",status(second));
        assertEquals(1,exomes.calls.get()); assertEquals(0,js.calls.get());
        assertTrue(second.path("observationReusedWithinRun").asBoolean());
        assertEquals(first.at("/observation/observationId"),second.at("/observation/observationId"));
        assertEquals("EXOMES",first.at("/sourceRouting/dataset").asText());
        assertFalse(first.path("observation").has("evaluation"));
        assertEquals(1,runtime.counts().path("provider").asInt()); assertEquals(2,runtime.counts().path("evaluator").asInt());
    }
    @Test void jointUsesItsOwnProviderAndNeverConsumesExomesSharedFacts() throws Exception {
        var p=jointPolicy(); var c=joint.context(joint.record(true));
        var exomes=new Source(false,local.facts.info("0.2","10000")); var js=new Source(true,joint.fields("19","10000"));
        var runtime=runtime(List.of(p),exomes,js);
        var oldShared=local.shared(c,local.facts.info("0.2","10000"),"PASS");
        var first=evaluate(runtime,c,SOURCE,oldShared); var second=evaluate(runtime,c,SOURCE,first.path("observation"));
        assertEquals("NOT_MET",status(first),first.toString()); assertEquals("NOT_MET",status(second));
        assertEquals(0,exomes.calls.get()); assertEquals(1,js.calls.get());
        assertEquals("JOINT",first.at("/sourceRouting/dataset").asText());
        assertEquals(GnomadPopulationInputProfile.loadExecutableV2().getProfileSha256(),first.at("/observation/profileSha256").asText());
        assertTrue(second.path("observationReusedWithinRun").asBoolean());
    }
    @Test void validExomesEnvelopeAndSealedParentAreVerifiedWithoutAnyQuery() throws Exception {
        var c=local.context("GN017"); var exomes=new Source(false,local.facts.info("0.00004","10000")); var js=new Source(true,joint.fields("21","10000"));
        var runtime=runtime(Bs1LocalExomes.policies(mapper),exomes,js); var shared=local.shared(c,exomes.fields,"PASS");
        var first=evaluate(runtime,c,SOURCE,shared); var second=evaluate(runtime,c,SOURCE,first.path("observation"));
        assertEquals("MET",status(first)); assertEquals("MET",status(second)); assertEquals(0,exomes.calls.get()); assertEquals(0,js.calls.get());
        assertTrue(first.path("observationReusedFromBa1").asBoolean());
    }
    @Test void sameProfileTamperingIsNotHiddenByAnExistingCacheEntryOrRequery() throws Exception {
        var c=local.context("GN017"); var exomes=new Source(false,local.facts.info("0.00004","10000")); var js=new Source(true,joint.fields("21","10000"));
        var runtime=runtime(Bs1LocalExomes.policies(mapper),exomes,js); var first=evaluate(runtime,c,SOURCE,null);
        var forged=(ObjectNode)first.path("observation").deepCopy(); forged.put("observationId","population-observation-sha256-"+"b".repeat(64));
        var failed=evaluate(runtime,c,SOURCE,forged);
        assertEquals("NOT_EVALUATED",status(failed)); assertEquals("BS1_SHARED_POPULATION_VERIFICATION_FAILED",reason(failed)); assertEquals(1,exomes.calls.get());
    }
    @Test void datasetCachesAreSeparateAtTheSameNormalizedAllele() throws Exception {
        joint.temporary=temporary; var jointRecord=joint.record(true); var jc=joint.context(jointRecord);
        var exomeRecord=joint.old.record("GT"); exomeRecord.withObject("/scope").put("hgncId","HGNC:2"); exomeRecord.withObject("/expertPolicy/scope").put("hgncId","HGNC:2");
        Bs1SexChromosomeStatisticTest.qualify(exomeRecord); joint.old.temporary=temporary; var ep=joint.old.catalog(exomeRecord).merge(List.of()).get(0);
        var ec=joint.old.context(exomeRecord); var fields=new LinkedHashMap<>(local.facts.info("0.00004","10000")); fields.put("AC_nfe_XY","21"); fields.put("AN_nfe_XY","10000"); fields.put("AF_nfe_XY","0.0021");
        var exomes=new Source(false,fields); var js=new Source(true,joint.fields("19","10000"));
        var runtime=runtime(List.of(ep,joint.runtime(jointRecord).policyRecords().get(0)),exomes,js);
        var er=evaluate(runtime,ec,SOURCE,null); var jr=evaluate(runtime,jc,SOURCE,er.path("observation"));
        assertEquals("MET",status(er),er.toString()); assertEquals("NOT_MET",status(jr),jr.toString());
        assertEquals(1,exomes.calls.get()); assertEquals(1,js.calls.get());
        assertNotEquals(er.at("/observation/profileSha256"),jr.at("/observation/profileSha256"));
    }
    @Test void sourceFailureIsCachedPerDatasetAndDoesNotSuppressOtherSource() throws Exception {
        var c=local.context("GN017"); var jp=jointPolicy(); var jc=joint.context(joint.record(true));
        var exomes=new Source(false,local.facts.info("0.00004","10000")); exomes.fail=true; var js=new Source(true,joint.fields("21","10000"));
        var policies=new ArrayList<>(Bs1LocalExomes.policies(mapper)); policies.add(jp); var runtime=runtime(policies,exomes,js);
        assertEquals("NOT_EVALUATED",status(evaluate(runtime,c,SOURCE,null))); assertEquals("NOT_EVALUATED",status(evaluate(runtime,c,SOURCE,null)));
        assertEquals("MET",status(evaluate(runtime,jc,SOURCE,null))); assertEquals(1,exomes.calls.get()); assertEquals(1,js.calls.get());
    }
    @Test void snapshotAndNormalizationIdentitySplitTheRunCache() throws Exception {
        var c=local.context("GN017"); var exomes=new Source(false,local.facts.info("0.00004","10000")); var js=new Source(true,joint.fields("21","10000"));
        var runtime=runtime(Bs1LocalExomes.policies(mapper),exomes,js); evaluate(runtime,c,SOURCE,null); evaluate(runtime,c,"b".repeat(64),null);
        var other=c.deepCopy(); other.withObject("/variant/normalization").put("referenceSequenceSha256","c".repeat(64));
        other.withObject("/variant").put("alleleId",adapter.canonicalAlleleId(other.at("/variant/normalizedAllele"),other.at("/variant/normalization")));
        other.put("contextId",adapter.canonicalVersionedContextId(other)); evaluate(runtime,other,SOURCE,null);
        assertEquals(3,exomes.calls.get());
    }
    @Test void ambiguousPoliciesDoNotQueryOrPickTheMoreFavorableDataset() throws Exception {
        var jp=jointPolicy(); var c=joint.context(joint.record(true)); var duplicate=(ObjectNode)jp.deepCopy(); duplicate.put("policyId","SECOND_SYNTHETIC_POLICY");
        var exomes=new Source(false,local.facts.info("0.2","10000")); var js=new Source(true,joint.fields("21","10000"));
        var runtime=runtime(List.of(jp,duplicate),exomes,js); var result=evaluate(runtime,c,SOURCE,null);
        assertEquals("BS1_POLICY_AMBIGUOUS",reason(result)); assertEquals(0,exomes.calls.get()); assertEquals(0,js.calls.get());
    }
    @Test void productionBindingsPreserveGatesAndHashEffectiveSettingsWithoutLeakingPaths() throws Exception {
        joint.temporary=temporary; var rec=joint.record(true); joint.old.temporary=temporary; var knowledge=joint.old.catalog(rec);
        var exomes=new VariantAnalysisProperties(); var settings=new VariantAnalysisProperties(); settings.getGnomad().setEnabled(false); settings.getGnomad().setPopulationInputEnabled(false);
        settings.getGnomad().setTabixExecutable(temporary.resolve("private-secret-command").toString());
        var runtime=Bs1Runtime.routed(mapper,true,exomes,settings,true,knowledge,null);
        var result=evaluate(runtime,joint.context(rec),SOURCE,null);
        assertEquals("BS1_POPULATION_SOURCE_DISABLED",reason(result)); assertEquals(0,runtime.counts().path("provider").asInt());
        assertEquals("POLICY_ROUTED_POPULATION_V1",runtime.identity().path("activationContract").asText());
        assertFalse(runtime.identity().toString().contains("private-secret-command"));
        var before=runtime.identity(); settings.getGnomad().setTimeout(Duration.ofSeconds(2));
        assertEquals(before,runtime.identity());
        var changed=Bs1Runtime.routed(mapper,true,exomes,settings,true,knowledge,null); assertNotEquals(before,changed.identity());
        var off=Bs1Runtime.routed(mapper,true,exomes,settings,false,knowledge,null);
        assertEquals("BS1_BUSINESS_EXECUTION_DISABLED",reason(evaluate(off,joint.context(rec),SOURCE,null)));
    }
    @Test void bothDatasetsHonorEnabledAndPopulationInputGatesBeforeQuery() throws Exception {
        joint.temporary=temporary; joint.old.temporary=temporary; var rec=joint.record(true); var knowledge=joint.old.catalog(rec);
        for (String dataset:List.of("EXOMES","JOINT")) for (String gate:List.of("SOURCE","INPUT")) {
            var exomes=new VariantAnalysisProperties(); var remote=new VariantAnalysisProperties();
            for (var settings:List.of(exomes,remote)) { settings.getGnomad().setEnabled(true); settings.getGnomad().setPopulationInputEnabled(true); }
            var disabled="EXOMES".equals(dataset) ? exomes.getGnomad() : remote.getGnomad();
            if ("SOURCE".equals(gate)) disabled.setEnabled(false); else disabled.setPopulationInputEnabled(false);
            var runtime=Bs1Runtime.routed(mapper,true,exomes,remote,true,knowledge,null);
            var context="EXOMES".equals(dataset) ? local.context("GN017") : joint.context(rec);
            var result=evaluate(runtime,context,SOURCE,null);
            assertEquals("SOURCE".equals(gate) ? "BS1_POPULATION_SOURCE_DISABLED" : "BS1_POPULATION_INPUT_DISABLED",reason(result),dataset+gate);
            assertEquals(0,runtime.counts().path("provider").asInt());
            assertEquals("SOURCE".equals(gate) ? false : true,result.at("/sourceRouting/effectiveSource/enabled").asBoolean());
            if ("EXOMES".equals(dataset)) {
                var other=evaluate(runtime,local.context("COCH"),SOURCE,null);
                assertEquals(reason(result),reason(other)); assertTrue(other.at("/sourceRouting/observationReusedWithinRun").asBoolean());
                assertEquals(local.policy("COCH").path("policyId"),other.at("/sourceRouting/policyId"));
                assertEquals(adapter.canonicalSha256(local.policy("COCH")),other.at("/sourceRouting/policySha256").asText());
                assertEquals(0,runtime.counts().path("provider").asInt());
            }
        }
    }
    @Test void invalidSnapshotIsRejectedBeforeQueryAndCannotSeedACacheEntry() throws Exception {
        var context=local.context("GN017"); var exomes=new Source(false,local.facts.info("0.00004","10000")); var js=new Source(true,joint.fields("21","10000"));
        var runtime=runtime(Bs1LocalExomes.policies(mapper),exomes,js);
        for (String snapshot:List.of("","wrong-source","A".repeat(64),"a".repeat(63))) {
            var result=evaluate(runtime,context,snapshot,null);
            assertEquals("BS1_POPULATION_SOURCE_SNAPSHOT_INVALID",reason(result)); assertEquals(0,exomes.calls.get());
        }
        assertEquals("MET",status(evaluate(runtime,context,SOURCE,null))); assertEquals(1,exomes.calls.get());
    }
    @Test void normalizedTransportUsesVerifiedContextWithoutMutatingRankedCandidate() throws Exception {
        var context=local.context("GN017"); var query=context.at("/variant/evidenceQueryAllele");
        context.withObject("/variant/inputAllele").put("position",query.path("position").asInt()-1).put("reference","CG").put("alternate","CA");
        context.withObject("/variant/normalization").put("status","NORMALIZED"); context.put("contextId",adapter.canonicalVersionedContextId(context));
        var input=context.at("/variant/inputAllele"); var key=new NormalizedVariantKey(input.path("assembly").asText(),input.path("contig").asText(),input.path("position").asInt(),input.path("reference").asText(),input.path("alternate").asText());
        var candidate=RuntimeEvidenceV2ProviderWiringTest.variant(key); var analysis=RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);
        var exomes=new Source(false,local.facts.info("0.00004","10000")); var js=new Source(true,joint.fields("21","10000"));
        var runtime=runtime(Bs1LocalExomes.policies(mapper),exomes,js); var result=runtime.evaluateRouted(context,candidate,analysis,SOURCE);
        assertEquals("MET",status(result),result.toString()); assertEquals(key,candidate.getObservation().getKey());
        var observedCandidate=exomes.lastCandidate; assertNotNull(observedCandidate);
        assertEquals(candidate.getCandidateId(),observedCandidate.getCandidateId());
        assertEquals(query.path("position").asInt(),observedCandidate.getObservation().getKey().getPosition());
        assertEquals(candidate.getObservation().getGenotype(),observedCandidate.getObservation().getGenotype());
        assertEquals(candidate.getObservation().getOriginalRecord(),observedCandidate.getObservation().getOriginalRecord());
        assertEquals("VERIFIED_CONTEXT_QUERY",result.at("/queryTransport/mode").asText());
        assertEquals(1,exomes.calls.get());
    }
    @Test void verifiedProjectionChangesOnlyTheDetachedQueryAssemblyAndRejectsUnrelatedSource() throws Exception {
        var record=joint.record(true); var context=joint.context(record); var query=context.at("/variant/evidenceQueryAllele").deepCopy();
        for (String path:List.of("/variant/inputAllele","/variant/normalizedAllele")) context.withObject(path).put("assembly","GRCh37").put("position",153800000);
        var lineage=context.withObject("/variant/assemblyLineage"); lineage.removeAll(); lineage.put("mappingStatus","MAPPED");
        var mapping=lineage.putObject("coordinateMapping").put("method","VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT").put("methodVersion","1.0.0").put("refAltValidation","MATCH");
        mapping.set("sourceAllele",context.at("/variant/normalizedAllele").deepCopy()); mapping.set("targetAllele",query);
        context.withObject("/variant").put("alleleId",adapter.canonicalAlleleId(context.at("/variant/normalizedAllele"),context.at("/variant/normalization")));
        context.put("contextId",adapter.canonicalVersionedContextId(context));
        var input=context.at("/variant/inputAllele"); var key=new NormalizedVariantKey("GRCh37",input.path("contig").asText(),input.path("position").asInt(),input.path("reference").asText(),input.path("alternate").asText());
        var candidate=RuntimeEvidenceV2ProviderWiringTest.variant(key); var analysis=RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);
        var exomes=new Source(false,local.facts.info("0.00004","10000")); var js=new Source(true,joint.fields("21","10000"));
        var runtime=runtime(List.of(jointPolicy()),exomes,js); var result=runtime.evaluateRouted(context,candidate,analysis,SOURCE);
        assertEquals("MET",status(result),result.toString()); assertEquals("GRCh37",candidate.getObservation().getKey().getGenomeAssembly());
        var queryCandidate=js.lastCandidate; var queryAnalysis=js.lastAnalysis; assertNotNull(queryCandidate); assertNotNull(queryAnalysis);
        assertEquals("GRCh38",queryCandidate.getObservation().getKey().getGenomeAssembly()); assertEquals("GRCh38",queryAnalysis.getGenomeAssembly().name());
        assertEquals(analysis.getAnalysisId(),queryAnalysis.getAnalysisId()); assertSame(analysis.getPreflightSummary(),queryAnalysis.getPreflightSummary());
        var unverified=context.deepCopy(); unverified.withObject("/variant/assemblyLineage/coordinateMapping").put("method","UNVERIFIED_LABEL"); unverified.put("contextId",adapter.canonicalVersionedContextId(unverified));
        assertEquals("BS1_QUERY_MAPPING_UNVERIFIED",reason(runtime.evaluateRouted(unverified,candidate,analysis,SOURCE)));
        var unrelated=RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh37","X",153800001,"C","T"));
        assertEquals("BS1_CANDIDATE_ALLELE_CONFLICT",reason(runtime.evaluateRouted(context,unrelated,analysis,SOURCE)));
        assertEquals(1,js.calls.get());
    }
    @Test void developmentDualProviderCannotGainFormalActivationAndOldLocalIdentityStaysStable() {
        var exomes=new Source(false,local.facts.info("0.00004","10000")); var js=new Source(true,joint.fields("21","10000"));
        var development=runtime(Bs1Runtime.formalPolicies(mapper),exomes,js);
        assertFalse(development.identity().path("formalEnabled").asBoolean());
        assertThrows(IllegalArgumentException.class,()->development.withFormalActivation(true));
        var old=Bs1Runtime.localExomes(mapper,new VariantAnalysisProperties(),true);
        assertEquals("LOCAL_EXOMES_BUSINESS_V1",old.identity().path("activationContract").asText()); assertFalse(old.isPolicyRouted());
    }
}
