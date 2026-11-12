package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic sealed population facts; BA1 independently owns its verdict and never trusts a BS1 result. */
class Ba1NeutralPopulationReuseTest {
    final ObjectMapper mapper=new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter=new RuntimeEvidenceV2Adapter(mapper);
    final GnomadJointSexStratifiedObservationTest fixture=new GnomadJointSexStratifiedObservationTest();
    static final String SOURCE="a".repeat(64);
    ObjectNode envelope(ObjectNode parent,ObjectNode context) {
        var value=mapper.createObjectNode();value.set("populationObservation",parent);value.set("context",context);return value;
    }
    ObjectNode evaluate(Ba1AlleleRuntime runtime,ObjectNode context,ObjectNode parent) {
        var q=context.at("/variant/evidenceQueryAllele");
        var key=new NormalizedVariantKey(q.path("assembly").asText(),q.path("contig").asText(),q.path("position").asInt(),q.path("reference").asText(),q.path("alternate").asText());
        return runtime.evaluate(context.at("/variant/alleleId").asText(),RuntimeEvidenceV2ProviderWiringTest.variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),envelope(parent,context),SOURCE);
    }
    @ParameterizedTest @ValueSource(strings={"PASS","AS_VQSR","NO_RECORD"})
    void sharedNeutralFactsDoNotDependOnAnotherCriterionVerdict(String state) throws Exception {
        var context=fixture.context();ObjectNode parent;
        if("NO_RECORD".equals(state)) {
            var carrier=fixture.carrier(context);carrier.withObject("queryOutcome").put("status","NO_RECORD").put("recordCount",0);
            carrier.withArray("/source/sourceRecords").removeAll();
            parent=PopulationObservation.seal(mapper,carrier,"",fixture.profile,SOURCE,"SYNTHETIC","SYNTHETIC_NO_RECORD");
        } else parent=fixture.parent(context,fixture.info(),state);
        var before=parent.deepCopy();var provider=mock(VariantEvidenceProvider.class);
        var runtime=new Ba1AlleleRuntime(mapper,fixture.profile,provider);
        var result=evaluate(runtime,context,parent);
        assertEquals(0,result.path("providerCalls").asInt());verifyNoInteractions(provider);
        assertEquals(parent.path("observationId"),result.at("/observationIdentity/parentObservationId"));
        assertEquals(before,parent);PopulationObservation.verify(mapper,parent,context,SOURCE);
        assertTrue(result.path("evaluation").isObject());
    }
    @ParameterizedTest @ValueSource(strings={"RAW","SNAPSHOT","PROFILE","NORMALIZATION"})
    void sharedBindingFailuresCannotSilentlyFallBackToAnotherQuery(String fault) throws Exception {
        var context=fixture.context();var parent=fixture.parent(context,fixture.info(),"PASS");
        if("RAW".equals(fault))parent.put("rawRecord",parent.path("rawRecord").asText()+";UNKNOWN=1");
        else if("SNAPSHOT".equals(fault))parent.put("sourceSnapshot","b".repeat(64));
        else if("PROFILE".equals(fault))parent.put("profileSha256",GnomadPopulationInputProfile.loadLocalBa1().getProfileSha256());
        else {
            context.withObject("/variant/normalization").put("referenceSequenceSha256","c".repeat(64));
            // A different valid context cannot change this caller's already established allele identity.
            context.put("contextId",adapter.canonicalVersionedContextId(context));
        }
        var provider=mock(VariantEvidenceProvider.class);var runtime=new Ba1AlleleRuntime(mapper,fixture.profile,provider);
        assertThrows(IllegalArgumentException.class,()->evaluate(runtime,context,parent));verifyNoInteractions(provider);
    }
    @Test void wrongDatasetNeverBecomesACompatibleParent() throws Exception {
        var context=fixture.context();var exome=new GnomadSexStratifiedObservationTest().parent(context,java.util.Map.of());
        var provider=mock(VariantEvidenceProvider.class);var runtime=new Ba1AlleleRuntime(mapper,fixture.profile,provider);
        assertThrows(IllegalArgumentException.class,()->evaluate(runtime,context,exome));verifyNoInteractions(provider);
    }
    @Test void localExomesNeutralReuseKeepsItsExistingScope() throws Exception {
        var context=fixture.context();var exome=new GnomadSexStratifiedObservationTest().parent(context,java.util.Map.of());
        var provider=mock(VariantEvidenceProvider.class);var runtime=new Ba1AlleleRuntime(mapper,GnomadPopulationInputProfile.loadLocalBa1(),provider);
        var result=evaluate(runtime,context,exome);assertEquals(0,result.path("providerCalls").asInt());verifyNoInteractions(provider);
        assertEquals("EXOMES",result.at("/dataScope/dataset").asText());
        assertEquals(exome.path("observationId"),result.at("/observationIdentity/parentObservationId"));
    }
}
