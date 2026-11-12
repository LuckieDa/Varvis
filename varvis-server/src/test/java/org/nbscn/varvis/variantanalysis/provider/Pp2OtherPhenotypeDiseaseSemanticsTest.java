package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure semantic controls only, not original clinical records or a restored source authority. */
class Pp2OtherPhenotypeDiseaseSemanticsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Pp2OtherPhenotypeDiseaseSemantics resolver = new Pp2OtherPhenotypeDiseaseSemantics(mapper);
    private ObjectNode facts(String label, String id) {
        var value = mapper.createObjectNode(); value.putObject("disease").put("id", id).put("label", label);
        value.putObject("originalExpertSummary").put("condition", label);
        var traitset = value.putObject("originalAssertion").putArray("condition").addObject().put("tag", "TraitSet");
        var trait = traitset.putArray("children").addObject().put("tag", "Trait");
        trait.putArray("children").addObject().put("tag", "XRef").putObject("attributes").put("DB", "MONDO").put("ID", id);
        return value;
    }
    private Pp2OtherPhenotypeSource.Relation resolve(ObjectNode facts) {
        var context = mapper.createObjectNode(); context.putObject("disease").put("mondoId", "MONDO:0005045");
        return resolver.resolve(facts, context, mapper.createObjectNode());
    }
    @ParameterizedTest @ValueSource(strings = {"primary dilated cardiomyopathy", "familial dilated cardiomyopathy",
        "familial isolated dilated cardiomyopathy", "dilated cardiomyopathy 1Y", "dilated cardiomyopathy type 1II"})
    void uniqueExplicitPrimaryDcmDiseaseIsDistinctWithinQualifiedExpertRoute(String label) {
        assertEquals(Pp2OtherPhenotypeSource.Relation.DISTINCT, resolve(facts(label, "MONDO:0012744")));
    }
    @Test void knownGenericDcmConceptAndItsExactNameAreDistinctButUnknownIdAloneIsNot() {
        assertEquals(Pp2OtherPhenotypeSource.Relation.DISTINCT, resolve(facts("dilated cardiomyopathy", "MONDO:0005021")));
        assertEquals(Pp2OtherPhenotypeSource.Relation.UNRESOLVED, resolve(facts("dilated cardiomyopathy", "MONDO:1234567")));
    }
    @ParameterizedTest @ValueSource(strings = {"ventricular dilation", "dilated cardiomyopathy and hypertrophic cardiomyopathy",
        "end-stage hypertrophic cardiomyopathy with ventricular dilation", "dilated-phase hypertrophic cardiomyopathy",
        "cardiomyopathy", "DCM", "familial dilated cardiomyopathy with hypertrophic cardiomyopathy"})
    void clinicalDilationOrMixedOrUnmappedDiseaseDoesNotBecomeOtherPhenotype(String label) {
        assertEquals(Pp2OtherPhenotypeSource.Relation.UNRESOLVED, resolve(facts(label, "MONDO:1234567")));
    }
    @Test void knownHcmConceptWithExplicitDcmDiseaseNameIsConflictBeforeEqualityShortcut() {
        assertEquals(Pp2OtherPhenotypeSource.Relation.CONFLICT, resolve(facts("primary dilated cardiomyopathy", "MONDO:0005045")));
        assertEquals(Pp2OtherPhenotypeSource.Relation.CONFLICT, resolve(facts("dilated cardiomyopathy", "MONDO:0005045")));
        // Bare generic DCM plus the known HCM concept is itself an explicit contradiction.
        assertEquals(Pp2OtherPhenotypeSource.Relation.CONFLICT, resolve(facts("dilated cardiomyopathy 1Y", "MONDO:0005045")));
    }
    @Test void hcmFamilyNameNeverBecomesOtherFromAnotherIdAndKnownOppositeConceptIsConflict() {
        assertEquals(Pp2OtherPhenotypeSource.Relation.SAME_HCM_FAMILY, resolve(facts("hypertrophic cardiomyopathy 2", "MONDO:1234567")));
        assertEquals(Pp2OtherPhenotypeSource.Relation.CONFLICT, resolve(facts("hypertrophic cardiomyopathy", "MONDO:0005021")));
    }
    @Test void mixedTraitsetsAndMismatchedSummaryCannotBorrowDcmName() {
        var mixed = facts("primary dilated cardiomyopathy", "MONDO:0012744");
        ((com.fasterxml.jackson.databind.node.ArrayNode)mixed.at("/originalAssertion/condition")).add(mixed.at("/originalAssertion/condition/0").deepCopy());
        assertEquals(Pp2OtherPhenotypeSource.Relation.UNRESOLVED, resolve(mixed));
        var different = facts("primary dilated cardiomyopathy", "MONDO:0012744");
        ((ObjectNode)different.path("originalExpertSummary")).put("condition", "hypertrophic cardiomyopathy");
        assertEquals(Pp2OtherPhenotypeSource.Relation.UNRESOLVED, resolve(different));
    }
    @Test void methodIdentityInvalidatesNewObservationWithoutChangingStoredOldIdentity() {
        var old = resolver.identity().deepCopy().put("methodVersion", "pp2-native-expert-disease-semantics-v1");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        assertNotEquals(adapter.canonicalSha256(old), adapter.canonicalSha256(resolver.identity()));
        assertEquals("pp2-native-expert-disease-semantics-v1", old.path("methodVersion").asText());
        assertFalse(resolver.identity().path("usesGeneOrVariantAllowlist").asBoolean());
    }
}
