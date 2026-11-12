package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed scope/identity expectations. Synthetic qualification is not scientific adoption. */
class TruncatingNmdPolicyComplexScopeTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final TruncatingNmdPvs1RuntimeTest fixture = new TruncatingNmdPvs1RuntimeTest();

    @ParameterizedTest @CsvSource({"C,T,SNV", "C,CG,SMALL_INSERTION", "CG,C,SMALL_DELETION"})
    void unchangedSmallPolicyKeepsItsExistingSimpleAlleles(String ref, String alt, String type) throws Exception {
        var data = allele(ref, alt, type);
        assertNull(issue(data));
        assertFalse(TruncatingNmdPolicy.complexAllelesEnabled(data.policy));
        assertEquals(50, TruncatingNmdPolicy.mechanics(data.policy, data.model)
                .at("/frameshift/maximumAlleleLengthIncludingAnchor").asInt());
    }

    @ParameterizedTest @CsvSource({"CC,GT,MNV", "C,GG,SMALL_INSERTION", "CC,G,SMALL_DELETION", "CC,GGA,SMALL_DELINS"})
    void complexCarrierTypesRequireExplicitReviewedScope(String ref, String alt, String type) throws Exception {
        var data = allele(ref, alt, type);
        assertEquals("PVS1_NATIVE_SMALL_VARIANT_REQUIRED", issue(data));
        enable(data.policy, 50); fixture.bind(data);
        assertNull(issue(data));
        assertTrue(TruncatingNmdPolicy.complexAllelesEnabled(data.policy));
    }

    @ParameterizedTest @ValueSource(strings = {"status", "method", "methodVersion", "basis", "sourceIds"})
    void incompleteOrUnknownEditScopeCannotEnableTheConsumer(String field) throws Exception {
        var data = allele("CC", "GGA", "SMALL_DELINS");
        enable(data.policy, 50).remove(field); fixture.bind(data);
        assertEquals("PVS1_COMPLEX_EDIT_SCOPE_NOT_REVIEWED", issue(data));
        assertFalse(TruncatingNmdPolicy.complexAllelesEnabled(data.policy));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 51})
    void policyCannotExpandTheFiftyBaseEngineeringLimit(int maximum) throws Exception {
        var data = allele("CC", "GGA", "SMALL_DELINS");
        enable(data.policy, maximum); fixture.bind(data);
        assertEquals("PVS1_COMPLEX_EDIT_SCOPE_NOT_REVIEWED", issue(data));
        assertFalse(TruncatingNmdPolicy.complexAllelesEnabled(data.policy));
    }

    @Test void tighterReviewedBoundIsUsedByBothGateAndMechanics() throws Exception {
        var data = allele("CC", "GGA", "SMALL_DELINS");
        enable(data.policy, 2); fixture.bind(data);
        assertEquals("PVS1_NATIVE_SMALL_VARIANT_REQUIRED", issue(data));
        assertEquals(2, TruncatingNmdPolicy.mechanics(data.policy, data.model)
                .at("/frameshift/maximumAlleleLengthIncludingAnchor").asInt());
    }

    @Test void reviewMustBindItsScientificSourceAndCannotOverrideSnvOnlyPolicy() throws Exception {
        var data = allele("CC", "GGA", "SMALL_DELINS");
        enable(data.policy, 50).putArray("sourceIds").add("unbound"); fixture.bind(data);
        assertEquals("PVS1_COMPLEX_EDIT_SCOPE_NOT_REVIEWED", issue(data));
        assertFalse(TruncatingNmdPolicy.complexAllelesEnabled(data.policy));
        enable(data.policy, 50);
        data.policy.withObject("scope").put("variantType", "NATIVE_NONPROJECTED_SNV"); fixture.bind(data);
        assertEquals("PVS1_COMPLEX_EDIT_SCOPE_NOT_REVIEWED", issue(data));
        assertFalse(TruncatingNmdPolicy.complexAllelesEnabled(data.policy));
    }

    @ParameterizedTest @CsvSource({"CC,GT,SMALL_DELINS", "CC,GGA,MNV", "C,C,SNV", "N,G,SNV", "CC,<DEL>,SMALL_DELETION"})
    void explicitReviewDoesNotPermitWrongTypeNoOpOrSymbolicAlleles(String ref, String alt, String type) throws Exception {
        var data = allele(ref, alt, type);
        enable(data.policy, 50); fixture.bind(data);
        assertEquals("PVS1_NATIVE_SMALL_VARIANT_REQUIRED", issue(data));
    }

    @Test void explicitReviewDoesNotBypassModelMechanismSelectionOrNativeLineage() throws Exception {
        var data = allele("CC", "GGA", "SMALL_DELINS");
        enable(data.policy, 50); fixture.bind(data);
        data.selection.put("policyVersion", "wrong");
        assertEquals("PVS1_POLICY_SELECTION_IDENTITY_INVALID", issue(data));
        fixture.bind(data); data.model.put("modelVersion", "wrong");
        assertEquals("PVS1_TRANSCRIPT_MODEL_IDENTITY_INVALID", issue(data));
        fixture.bind(data); data.policy.withObject("mechanism").put("status", "UNKNOWN"); fixture.bind(data);
        assertEquals("PVS1_LOF_MECHANISM_NOT_ESTABLISHED", issue(data));
        data.policy.withObject("mechanism").put("status", "ESTABLISHED_LOSS_OF_FUNCTION");
        ((ObjectNode) data.context.at("/variant/assemblyLineage")).put("mappingStatus", "MAPPED"); fixture.bind(data);
        assertEquals("PVS1_NATIVE_SMALL_VARIANT_REQUIRED", issue(data));
    }

    @Test void reviewCannotCarryUnimplementedExtraOperators() throws Exception {
        var data = allele("CC", "GGA", "SMALL_DELINS");
        enable(data.policy, 50).put("allowAllTypes", true); fixture.bind(data);
        assertEquals("PVS1_COMPLEX_EDIT_SCOPE_NOT_REVIEWED", issue(data));
        assertFalse(TruncatingNmdPolicy.complexAllelesEnabled(data.policy));
    }

    static ObjectNode enable(ObjectNode policy, int maximum) {
        var scope = policy.putObject("codingEditScope").put("status", "REVIEWED_FOR_COMPLEX_SMALL_ALLELES")
                .put("method", "CODING_TRANSCRIPT_EDITS").put("methodVersion", "1.0.0")
                .put("maximumAlleleLengthIncludingAnchor", maximum)
                .put("basis", "Synthetic explicit engineering scope; no scientific approval");
        scope.putArray("sourceIds").add("synthetic-source");
        return scope;
    }

    private TruncatingNmdPvs1RuntimeTest.Inputs allele(String ref, String alt, String type) throws Exception {
        var data = fixture.nonsense(1, 1, 31);
        data.policy.withObject("scope").put("variantType", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT");
        for (String field : java.util.List.of("evidenceQueryAllele", "normalizedAllele"))
            ((ObjectNode) data.context.at("/variant/" + field)).put("reference", ref).put("alternate", alt);
        data.context.withObject("variant").put("variantType", type);
        data.context.put("contextId", adapter.canonicalContextId(data.context));
        fixture.bind(data);
        return data;
    }

    private String issue(TruncatingNmdPvs1RuntimeTest.Inputs data) {
        return TruncatingNmdPolicy.issue(data.context, data.policy, data.selection, data.model, adapter);
    }
}
