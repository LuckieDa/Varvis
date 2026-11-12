package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPilotTruthPolicyTest {

    private final BenchmarkManifestCodec codec = new BenchmarkManifestCodec();

    @Test
    void acceptsSingleRequiredAlleleAndExplicitAlternatives() {
        BenchmarkTruthPolicyAssessment single = PublicPilotTruthPolicy.assess(
                datasetCase("single", "SINGLE_VARIANT", key("GRCh38", "1", 100)));
        assertEquals(BenchmarkTruthSetSemantics.SINGLE_REQUIRED_ALLELE, single.getSemantics());
        assertEquals(BenchmarkPilotEligibilityStatus.ELIGIBLE, single.getEligibilityStatus());

        BenchmarkTruthPolicyAssessment alternatives = PublicPilotTruthPolicy.assess(
                datasetCase("alternatives", "EXPLICIT_ALTERNATIVES",
                        key("GRCh38", "2", 200), key("GRCh38", "2", 300)));
        assertEquals(BenchmarkTruthSetSemantics.EXPLICIT_ALTERNATIVES, alternatives.getSemantics());
        assertEquals(BenchmarkPilotEligibilityStatus.ELIGIBLE, alternatives.getEligibilityStatus());
        assertTrue(alternatives.getExclusionReasons().isEmpty());
    }

    @Test
    void reservesRequiredAndPhasedSetsForFutureFamilyStratum() {
        BenchmarkTruthPolicyAssessment required = PublicPilotTruthPolicy.assess(
                datasetCase("required", "EXPLICIT_REQUIRED_SET",
                        key("GRCh38", "3", 100), key("GRCh38", "3", 200)));
        assertEquals(BenchmarkPilotEligibilityStatus.EXCLUDED, required.getEligibilityStatus());
        assertEquals(Collections.singletonList(
                BenchmarkPilotExclusionReason.REQUIRED_SET_RESERVED_FOR_FUTURE_STRATUM),
                required.getExclusionReasons());

        BenchmarkTruthPolicyAssessment phased = PublicPilotTruthPolicy.assess(
                datasetCase("phased", "PHASED_REQUIRED_SET",
                        key("GRCh38", "4", 100), key("GRCh38", "4", 200)));
        assertEquals(BenchmarkPilotEligibilityStatus.EXCLUDED, phased.getEligibilityStatus());
        assertEquals(Collections.singletonList(
                BenchmarkPilotExclusionReason.PHASED_REQUIRED_SET_RESERVED_FOR_FUTURE_STRATUM),
                phased.getExclusionReasons());
    }

    @Test
    void unresolvedMultiTruthAndUnsupportedVariationAreStructuredExclusions() {
        BenchmarkTruthPolicyAssessment unresolved = PublicPilotTruthPolicy.assess(
                datasetCase("unresolved", "MULTI_VARIANT_SOURCE_REPORTED",
                        key("GRCh38", "5", 100), key("GRCh38", "5", 200)));
        assertEquals(BenchmarkPilotEligibilityStatus.EXCLUDED, unresolved.getEligibilityStatus());
        assertEquals(BenchmarkPilotExclusionReason.MULTI_TRUTH_RELATIONSHIP_UNRESOLVED,
                unresolved.getExclusionReasons().get(0));

        BenchmarkTruthPolicyAssessment unsupported = PublicPilotTruthPolicy.assess(
                datasetCase("unsupported", "UNSUPPORTED_VARIATION", key("GRCh38", "6", 100)));
        assertEquals(BenchmarkPilotEligibilityStatus.EXCLUDED, unsupported.getEligibilityStatus());
        assertEquals(BenchmarkPilotExclusionReason.UNSUPPORTED_VARIATION_TYPE,
                unsupported.getExclusionReasons().get(0));
    }

    @Test
    void rejectsInvalidCardinalityDuplicateAssemblyAndIncompleteCanonicalAlleles() {
        BenchmarkTruthPolicyAssessment cardinality = PublicPilotTruthPolicy.assess(
                datasetCase("cardinality", "SINGLE_REQUIRED_ALLELE",
                        key("GRCh38", "7", 100), key("GRCh38", "7", 200)));
        assertInvalid(cardinality, BenchmarkPilotExclusionReason.INVALID_TRUTH_CARDINALITY);

        NormalizedVariantKey duplicate = key("GRCh38", "8", 100);
        BenchmarkTruthPolicyAssessment duplicates = PublicPilotTruthPolicy.assess(
                datasetCase("duplicates", "EXPLICIT_ALTERNATIVES", duplicate, duplicate));
        assertInvalid(duplicates, BenchmarkPilotExclusionReason.DUPLICATE_TRUTH);

        BenchmarkDatasetCase assemblyCase = datasetCase("assembly", "SINGLE_VARIANT",
                key("GRCh37", "9", 100));
        assemblyCase.setAssembly("GRCh38");
        assertInvalid(PublicPilotTruthPolicy.assess(assemblyCase),
                BenchmarkPilotExclusionReason.ASSEMBLY_MISMATCH);

        BenchmarkDatasetCase incomplete = datasetCase("incomplete", "SINGLE_VARIANT",
                new NormalizedVariantKey("GRCh38", "10", 100, "A", null));
        assertInvalid(PublicPilotTruthPolicy.assess(incomplete),
                BenchmarkPilotExclusionReason.INCOMPLETE_CANONICAL_ALLELE);
    }

    @Test
    void missingRelationshipOrProvenanceCannotBecomeEligible() {
        BenchmarkTruthPolicyAssessment missingRelationship = PublicPilotTruthPolicy.assess(
                datasetCase("missing-relationship", null,
                        key("GRCh38", "11", 100), key("GRCh38", "11", 200)));
        assertEquals(BenchmarkPilotEligibilityStatus.EXCLUDED, missingRelationship.getEligibilityStatus());
        assertEquals(BenchmarkPilotExclusionReason.MULTI_TRUTH_RELATIONSHIP_UNRESOLVED,
                missingRelationship.getExclusionReasons().get(0));

        BenchmarkDatasetCase missingProvenance = datasetCase("missing-provenance", "EXPLICIT_ALTERNATIVES",
                key("GRCh38", "12", 100), key("GRCh38", "12", 200));
        missingProvenance.setTruthProvenance(null);
        assertInvalid(PublicPilotTruthPolicy.assess(missingProvenance),
                BenchmarkPilotExclusionReason.RELATIONSHIP_PROVENANCE_REQUIRED);
    }

    @Test
    void fingerprintIsStableAcrossTruthOrderAndManifestRoundTrips() throws Exception {
        BenchmarkTruthPolicyAssessment first = PublicPilotTruthPolicy.assess(
                datasetCase("stable", "EXPLICIT_ALTERNATIVES",
                        key("GRCh38", "13", 100), key("GRCh38", "13", 200)));
        BenchmarkTruthPolicyAssessment second = PublicPilotTruthPolicy.assess(
                datasetCase("stable", "EXPLICIT_ALTERNATIVES",
                        key("GRCh38", "13", 200), key("GRCh38", "13", 100)));
        assertEquals(first.getTruthSetFingerprint(), second.getTruthSetFingerprint());

        BenchmarkTruthPolicyManifest manifest = manifest(first);
        String json = codec.toJson(manifest);
        assertEquals(json, codec.toJson(codec.readTruthPolicyJson(json)));
        assertEquals("benchmark-truth-policy-manifest-v1",
                codec.readTruthPolicyJson(json).getSchemaVersion());
    }

    @Test
    void validatesSchemaReasonsAndFrozenOverwrite(@TempDir Path directory) throws Exception {
        BenchmarkTruthPolicyManifest unsupported = manifest(PublicPilotTruthPolicy.assess(
                datasetCase("case", "SINGLE_VARIANT", key("GRCh38", "14", 100))));
        unsupported.setSchemaVersion("benchmark-truth-policy-manifest-v2");
        assertValidationCode(unsupported, "UNSUPPORTED_SCHEMA_VERSION");

        BenchmarkTruthPolicyManifest conflict = manifest(PublicPilotTruthPolicy.assess(
                datasetCase("case", "SINGLE_VARIANT", key("GRCh38", "14", 100))));
        conflict.getAssessments().get(0).getExclusionReasons().add(
                BenchmarkPilotExclusionReason.DUPLICATE_TRUTH);
        assertValidationCode(conflict, "ELIGIBILITY_REASON_CONFLICT");

        BenchmarkTruthPolicyManifest frozen = manifest(PublicPilotTruthPolicy.assess(
                datasetCase("case", "SINGLE_VARIANT", key("GRCh38", "14", 100))));
        BenchmarkManifestWriter.writeTruthPolicy(directory, frozen, codec);
        IOException exception = assertThrows(IOException.class,
                () -> BenchmarkManifestWriter.writeTruthPolicy(directory, frozen, codec));
        assertTrue(exception.getMessage().contains("COMPLETED_MANIFEST_EXISTS"));
    }

    private BenchmarkTruthPolicyManifest manifest(BenchmarkTruthPolicyAssessment assessment) {
        BenchmarkTruthPolicyManifest manifest = new BenchmarkTruthPolicyManifest();
        manifest.setDatasetId("synthetic-policy-dataset");
        manifest.setDatasetVersion("1");
        manifest.setCreatedAt("2026-07-17T00:00:00Z");
        manifest.setStatus("FROZEN");
        manifest.setAssessments(Collections.singletonList(assessment));
        return manifest;
    }

    private BenchmarkDatasetCase datasetCase(String caseId, String relationship,
                                             NormalizedVariantKey... identities) {
        BenchmarkDatasetCase value = new BenchmarkDatasetCase();
        value.setCaseId(caseId);
        value.setAssembly("GRCh38");
        value.setTruthRelationship(relationship);
        value.setTruthProvenance("explicit synthetic source interpretation");
        BenchmarkTruthVariant[] truths = new BenchmarkTruthVariant[identities.length];
        for (int i = 0; i < identities.length; i++) {
            truths[i] = new BenchmarkTruthVariant();
            truths[i].setCanonicalIdentity(identities[i]);
            truths[i].setTruthProvenance("synthetic source");
            truths[i].setConfidence("SOURCE_REPORTED");
            truths[i].setRelationId("truth-" + (i + 1));
        }
        value.setTruthVariants(Arrays.asList(truths));
        return value;
    }

    private NormalizedVariantKey key(String assembly, String chromosome, int position) {
        return new NormalizedVariantKey(assembly, chromosome, position, "A", "G");
    }

    private void assertInvalid(BenchmarkTruthPolicyAssessment value,
                               BenchmarkPilotExclusionReason reason) {
        assertEquals(BenchmarkPilotEligibilityStatus.INVALID, value.getEligibilityStatus());
        assertTrue(value.getExclusionReasons().contains(reason), value.getExclusionReasons().toString());
    }

    private void assertValidationCode(BenchmarkTruthPolicyManifest value, String code) {
        BenchmarkManifestValidationException exception = assertThrows(
                BenchmarkManifestValidationException.class, () -> codec.toJson(value));
        assertTrue(exception.getErrors().stream().anyMatch(error -> code.equals(error.getCode())),
                exception.getErrors().toString());
    }
}
