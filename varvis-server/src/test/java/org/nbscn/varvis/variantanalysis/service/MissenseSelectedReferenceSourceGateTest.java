package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.ClinVarConditionAwareComparatorInput;
import org.nbscn.varvis.variantanalysis.provider.MissenseSelectedReferencePreparation;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Original preparation/normalization, with synthetic source bytes; no production resources or services. */
class MissenseSelectedReferenceSourceGateTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realSelectedPreparationBlocksStaleAnnotationsAndSourceFailuresBeforeDiscovery(boolean sourceBlocked) throws Exception {
        var fixture = new SelectedEvidencePreparationTest(); fixture.temporary = temporary;
        var input = fixture.prepare(false, sourceBlocked, !sourceBlocked, 1, true);
        var sources = input.mapper().createObjectNode().put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY");
        for (String role : List.of("CLINVAR_VCF", "SIDECAR", "VEP")) sources.putObject(role).put("resourceId", "SYNTHETIC_" + role)
                .put("resourceVersion", "116.0").put("identityFingerprintSha256", "a".repeat(64));
        var prep = new MissenseSelectedReferencePreparation(input.mapper(),
                new ClinVarConditionAwareComparatorInput(null, null, null, null), null);
        var result = prep.prepare(input.result(), sources, temporary.resolve("reference-preparation"));
        assertEquals(0, result.prepared().size());
        assertEquals(sourceBlocked ? "SYNTHETIC_SOURCE_CHANGED" : "QUERY_TRANSCRIPT_REANNOTATION_REQUIRED",
                result.report().at("/candidates/0/attempts/0/reasonCode").asText(), result.report().toPrettyString());
        assertEquals(0, result.report().at("/providerCalls/discoveryCalls").asInt());
        assertEquals(0, result.report().at("/providerCalls/annotationCalls").asInt());
        assertEquals(input.before(), input.mapper().valueToTree(input.original()));
    }
}
