package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Metadata-only request/cache regression. No scientific source or evaluation is created or opened. */
class T07FullRnaCompletionIdentityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger opened = new AtomicInteger();

    private <T> T unopened() {
        opened.incrementAndGet();
        throw new AssertionError("Identity construction must not open material or scientific factories");
    }

    private LocalEvidenceBatchRuntime runtime(boolean fullRna) {
        var bp7 = mock(Bp7SelectedBatch.class);
        when(bp7.identity()).thenReturn(mapper.createObjectNode().put("fixture", "ENGINEERING_METADATA_ONLY"));
        var runtime = new LocalEvidenceBatchRuntime(mapper, mapper.createObjectNode(), this::unopened,
                this::unopened, ignored -> unopened(), null, bp7, this::unopened, this::unopened, this::unopened);
        if (fullRna) runtime.withFullRnaPreparation(this::unopened,
                mapper.createObjectNode().put("fixture", "ENGINEERING_DECLARED_CONFIGURATION_ONLY"));
        return runtime;
    }

    private ObjectNode requestIdentity(ObjectNode profile, String criterion) {
        var request = mapper.createObjectNode();
        request.putObject("declaration").put("operationId", "explicit-engineering-run")
                .putArray("selectedCriteria").add(criterion);
        request.set("executionProfile", profile);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"PVS1", "PM4", "BP3"})
    void explicitRnaRequestKeyChangesThroughActualSelectedExecutionProfile(String code) {
        String criterion = "ACMG_AMP_2015:" + code;
        var current = runtime(true).identity();
        var beforeCompletionFix = current.deepCopy();
        ((ObjectNode) beforeCompletionFix.path("fullRnaExecution")).remove("completionVersion");
        var oldSnapshot = beforeCompletionFix.deepCopy();

        var profile = AcmgExecutionProfile.localBatch(mapper, current).select(mapper, List.of(criterion)).document();
        var oldProfile = AcmgExecutionProfile.localBatch(mapper, beforeCompletionFix)
                .select(mapper, List.of(criterion)).document();
        String path = "/moduleBindings/" + code.toLowerCase(java.util.Locale.ROOT)
                + "/runtimeBinding/fullRnaExecution";
        assertEquals("full-rna-completion-digest-v1", profile.at(path + "/completionVersion").asText());
        assertFalse(oldProfile.at(path).has("completionVersion"));
        assertNotEquals(profile.path("manifestSha256"), oldProfile.path("manifestSha256"));
        var artifacts = new AcmgBusinessArtifacts(mapper);
        assertNotEquals(artifacts.requestHash(requestIdentity(oldProfile, criterion)),
                artifacts.requestHash(requestIdentity(profile, criterion)),
                "Actual pre-byRequest request canonicalization must distinguish the previous completion contract");
        assertEquals(oldSnapshot, beforeCompletionFix, "Reading an old binding must not add current capability metadata");
        assertEquals(0, opened.get());
    }

    @Test
    void capabilityIsLazyStableAndDoesNotChangeScientificOrApplicationVersions() {
        var first = runtime(true);
        var second = runtime(true);
        var identity = first.identity();
        var capability = identity.path("fullRnaExecution");
        assertEquals("full-rna-completion-digest-v1", capability.path("completionVersion").asText());
        assertEquals(FullRnaCurrentUse.VERSION, capability.path("methodVersion").asText());
        assertEquals(TruncatingNmdPvs1Runtime.FULL_RNA_EVALUATOR_VERSION, capability.path("pvs1Version").asText());
        assertEquals(GeneralPm4Runtime.FULL_RNA_VERSION, capability.path("pm4Version").asText());
        assertEquals(GeneralBp3Runtime.FULL_RNA_VERSION, capability.path("bp3Version").asText());
        assertEquals(FullRnaEvidenceArchive.VERSION, capability.path("archiveVersion").asText());
        assertEquals(identity, second.identity());
        String criterion = "ACMG_AMP_2015:PVS1";
        var firstProfile = AcmgExecutionProfile.localBatch(mapper, identity).select(mapper, List.of(criterion)).document();
        var secondProfile = AcmgExecutionProfile.localBatch(mapper, second.identity()).select(mapper, List.of(criterion)).document();
        var artifacts = new AcmgBusinessArtifacts(mapper);
        assertEquals(artifacts.requestHash(requestIdentity(firstProfile, criterion)),
                artifacts.requestHash(requestIdentity(secondProfile, criterion)));
        assertEquals(0, opened.get());
    }

    @ParameterizedTest @ValueSource(strings = {"PVS1", "PM4", "BP3"})
    void atomicNeProjectionFixChangesTheSelectedRequestKeyWithoutRewritingOldBindings(String code) {
        var current = runtime(true).identity(); var old = current.deepCopy();
        ((ObjectNode) old.path("fullRnaExecution")).remove("publicProjectionVersion"); var before = old.deepCopy();
        String criterion = "ACMG_AMP_2015:" + code;
        var profile = AcmgExecutionProfile.localBatch(mapper, current).select(mapper, List.of(criterion)).document();
        var legacy = AcmgExecutionProfile.localBatch(mapper, old).select(mapper, List.of(criterion)).document();
        String path = "/moduleBindings/" + code.toLowerCase(java.util.Locale.ROOT) + "/runtimeBinding/fullRnaExecution";
        assertEquals(FullRnaCurrentUse.PUBLIC_PROJECTION_VERSION, profile.at(path + "/publicProjectionVersion").asText());
        assertFalse(legacy.at(path).has("publicProjectionVersion"));
        var artifacts = new AcmgBusinessArtifacts(mapper);
        assertNotEquals(artifacts.requestHash(requestIdentity(profile, criterion)), artifacts.requestHash(requestIdentity(legacy, criterion)));
        assertEquals(before, old); assertEquals(0, opened.get());
    }

    @Test
    void absentRnaCapabilityAndCapturedLegacyProfileAreNotRetrofitted() throws Exception {
        var withoutRna = runtime(false);
        var withoutIdentity = withoutRna.identity();
        assertFalse(withoutIdentity.has("fullRnaExecution"));
        assertEquals(withoutIdentity, withoutRna.withFullRnaPreparation(null).identity());

        var legacyBinding = runtime(true).identity();
        ((ObjectNode) legacyBinding.path("fullRnaExecution")).remove("completionVersion");
        var capturedProfile = AcmgExecutionProfile.localBatch(mapper, legacyBinding)
                .select(mapper, List.of("ACMG_AMP_2015:PM4")).document();
        byte[] saved = mapper.writeValueAsBytes(capturedProfile);
        runtime(true).identity();
        assertEquals(capturedProfile, mapper.readTree(saved));
        assertFalse(capturedProfile.at("/moduleBindings/pm4/runtimeBinding/fullRnaExecution").has("completionVersion"));
        assertEquals(0, opened.get());
    }
}
