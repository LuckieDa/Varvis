package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageTest {

    private static final String TOKEN_A = "0123456789abcdef01234567";
    private static final String TOKEN_B = "89abcdef0123456789abcdef";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roundTripsAWithinAssemblyMappedLineageWithoutPrivateContext() throws Exception {
        NormalizedVariantKey source = key(GenomeAssembly.GRCh38, "1", 100, "AA", "AG");
        NormalizedVariantKey canonical = key(GenomeAssembly.GRCh38, "1", 99, "A", "G");
        AnalysisAlleleLineage lineage = AnalysisAlleleLineage.mapped(
                TOKEN_A, 4_294_967_296L, 2, 2,
                GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                source, null, canonical
        );

        String json = objectMapper.writeValueAsString(lineage);
        AnalysisAlleleLineage restored = objectMapper.readValue(json, AnalysisAlleleLineage.class);
        JsonNode tree = objectMapper.readTree(json);

        assertEquals(lineage, restored);
        assertEquals("analysis-allele-lineage-core-v1", restored.getSchemaVersion());
        assertEquals(AnalysisAlleleLineage.MappingStatus.MAPPED, restored.getMappingStatus());
        assertEquals(Long.valueOf(4_294_967_296L), restored.getSourceRecordOrdinal());
        assertEquals(Integer.valueOf(2), restored.getSourceAlternateIndex());
        assertEquals(Integer.valueOf(2), restored.getSourceAlternateCount());
        assertEquals("GRCh38", restored.getSourceAssembly());
        assertEquals("GRCh38", restored.getAnalysisAssembly());
        assertNull(restored.getLiftedKey());
        assertNull(restored.getLossCode());
        assertFalse(tree.has("sampleId"));
        assertFalse(tree.has("path"));
        assertFalse(tree.has("fileName"));
        assertFalse(tree.has("rawGenotype"));
        assertFalse(tree.has("phaseSet"));
        assertFalse(tree.has("errorMessage"));
    }

    @Test
    void recordsAValidatedCrossAssemblyMapping() {
        NormalizedVariantKey source = key(GenomeAssembly.GRCh37, "1", 100, "A", "T");
        NormalizedVariantKey lifted = key(GenomeAssembly.GRCh38, "1", 110, "A", "T");
        NormalizedVariantKey canonical = key(GenomeAssembly.GRCh38, "1", 110, "A", "T");

        AnalysisAlleleLineage lineage = AnalysisAlleleLineage.mapped(
                TOKEN_A, 1, 1, 1,
                GenomeAssembly.GRCh37, GenomeAssembly.GRCh38,
                source, lifted, canonical
        );

        assertEquals(lifted, lineage.getLiftedKey());
        assertEquals(canonical, lineage.getCanonicalKey());
        assertNull(lineage.getLossCode());
    }

    @Test
    void sourceGrch37DoesNotImplyLiftoverWhenAnalysisAssemblyIsAlsoGrch37() {
        NormalizedVariantKey source = key(GenomeAssembly.GRCh37, "1", 100, "AA", "AG");
        NormalizedVariantKey canonical = key(GenomeAssembly.GRCh37, "1", 99, "A", "G");

        AnalysisAlleleLineage lineage = AnalysisAlleleLineage.mapped(
                TOKEN_A, 1, 1, 1,
                GenomeAssembly.GRCh37, GenomeAssembly.GRCh37,
                source, null, canonical
        );

        assertEquals("GRCh37", lineage.getAnalysisAssembly());
        assertNull(lineage.getLiftedKey());
        assertEquals(canonical, lineage.getCanonicalKey());
    }

    @Test
    void distinctSourceTokensAreNotCollapsedWhenTheyConvergeOnOneCanonicalKey() {
        NormalizedVariantKey sourceA = key(GenomeAssembly.GRCh38, "1", 100, "AA", "A");
        NormalizedVariantKey sourceB = key(GenomeAssembly.GRCh38, "1", 99, "AAA", "AA");
        NormalizedVariantKey canonicalA = key(GenomeAssembly.GRCh38, "1", 99, "AA", "A");

        AnalysisAlleleLineage firstSource = AnalysisAlleleLineage.mapped(
                TOKEN_A, 3, 1, 1,
                GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                sourceA, null, canonicalA
        );
        AnalysisAlleleLineage secondSource = AnalysisAlleleLineage.mapped(
                TOKEN_B, 4, 1, 1,
                GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                sourceB, null, canonicalA
        );
        List<AnalysisAlleleLineage> mappings = Arrays.asList(firstSource, secondSource);

        assertEquals(TOKEN_A, mappings.get(0).getSourceAlleleToken());
        assertEquals(TOKEN_B, mappings.get(1).getSourceAlleleToken());
        assertEquals(canonicalA, mappings.get(0).getCanonicalKey());
        assertEquals(canonicalA, mappings.get(1).getCanonicalKey());
        assertFalse(mappings.get(0).equals(mappings.get(1)));
    }

    @Test
    void liftoverRejectionCarriesOnlyAStableRejectCode() {
        AnalysisAlleleLineage lineage = AnalysisAlleleLineage.liftoverRejected(
                TOKEN_A,
                5,
                1,
                1,
                GenomeAssembly.GRCh37,
                GenomeAssembly.GRCh38,
                key(GenomeAssembly.GRCh37, "2", 200, "C", "T"),
                AnalysisAlleleLineage.LossCode.LIFTOVER_NO_TARGET
        );

        assertEquals(AnalysisAlleleLineage.MappingStatus.LIFTOVER_REJECTED,
                lineage.getMappingStatus());
        assertEquals(AnalysisAlleleLineage.LossCode.LIFTOVER_NO_TARGET,
                lineage.getLossCode());
        assertNull(lineage.getLiftedKey());
        assertNull(lineage.getCanonicalKey());
    }

    @Test
    void normalizationRejectionRetainsTheLiftedKeyButNeverClaimsCanonicalSuccess() {
        AnalysisAlleleLineage lineage = AnalysisAlleleLineage.normalizationRejected(
                TOKEN_A,
                5,
                1,
                1,
                GenomeAssembly.GRCh37,
                GenomeAssembly.GRCh38,
                key(GenomeAssembly.GRCh37, "2", 200, "C", "T"),
                key(GenomeAssembly.GRCh38, "2", 220, "C", "T"),
                AnalysisAlleleLineage.LossCode.NORMALIZATION_REFERENCE_MISMATCH
        );

        assertEquals(AnalysisAlleleLineage.MappingStatus.NORMALIZATION_REJECTED,
                lineage.getMappingStatus());
        assertEquals("GRCh38", lineage.getLiftedKey().getGenomeAssembly());
        assertNull(lineage.getCanonicalKey());
    }

    @Test
    void legacyUnknownLineageIsExplicitlyUnresolved() {
        AnalysisAlleleLineage lineage = AnalysisAlleleLineage.unresolved(
                TOKEN_A,
                1,
                1,
                1,
                GenomeAssembly.GRCh38,
                GenomeAssembly.GRCh38,
                key(GenomeAssembly.GRCh38, "3", 300, "G", "A"),
                null,
                AnalysisAlleleLineage.LossCode.LEGACY_LINEAGE_UNAVAILABLE
        );

        assertEquals(AnalysisAlleleLineage.MappingStatus.UNRESOLVED, lineage.getMappingStatus());
        assertEquals(AnalysisAlleleLineage.LossCode.LEGACY_LINEAGE_UNAVAILABLE,
                lineage.getLossCode());
        assertNull(lineage.getCanonicalKey());
    }

    @Test
    void rejectsPrivateOrNonOpaqueSourceTokens() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisAlleleLineage.mapped(
                        "sample-17|uploaded-case.vcf",
                        1,
                        1,
                        1,
                        GenomeAssembly.GRCh38,
                        GenomeAssembly.GRCh38,
                        key(GenomeAssembly.GRCh38, "1", 100, "A", "T"),
                        null,
                        key(GenomeAssembly.GRCh38, "1", 100, "A", "T")
                )
        );

        assertEquals("ANALYSIS_ALLELE_LINEAGE_SOURCE_TOKEN_INVALID", failure.getMessage());
    }

    @Test
    void rejectsMissingOrNonPositiveSourceCoordinates() {
        NormalizedVariantKey source = key(GenomeAssembly.GRCh38, "1", 100, "A", "T");

        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_RECORD_ORDINAL_INVALID",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A, 0, 1, 1,
                        GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                        source, null, source
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_ALTERNATE_INDEX_INVALID",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A, 1, 0, 1,
                        GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                        source, null, source
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_ALTERNATE_COUNT_INVALID",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A, 1, 1, 0,
                        GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                        source, null, source
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_ALTERNATE_INDEX_OUT_OF_RANGE",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A, 1, 2, 1,
                        GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                        source, null, source
                )
        );
    }

    @Test
    void rejectsAssemblyAndKeyInconsistencies() {
        NormalizedVariantKey grch37 = key(GenomeAssembly.GRCh37, "1", 100, "A", "T");
        NormalizedVariantKey grch38 = key(GenomeAssembly.GRCh38, "1", 110, "A", "T");

        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_ASSEMBLY_MISMATCH",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A, 1, 1, 1,
                        GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                        grch37, null, grch38
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_LIFTED_KEY_REQUIRED",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A, 1, 1, 1,
                        GenomeAssembly.GRCh37, GenomeAssembly.GRCh38,
                        grch37, null, grch38
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_LIFTED_ASSEMBLY_MISMATCH",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A, 1, 1, 1,
                        GenomeAssembly.GRCh37, GenomeAssembly.GRCh38,
                        grch37, grch37, grch38
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_CANONICAL_ASSEMBLY_MISMATCH",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A, 1, 1, 1,
                        GenomeAssembly.GRCh37, GenomeAssembly.GRCh38,
                        grch37, grch38, grch37
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_LIFTOVER_NOT_REQUIRED",
                () -> AnalysisAlleleLineage.liftoverRejected(
                        TOKEN_A, 1, 1, 1,
                        GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                        grch38,
                        AnalysisAlleleLineage.LossCode.LIFTOVER_NO_TARGET
                )
        );
    }

    @Test
    void rejectsNonCanonicalKeyObjectsInsteadOfSilentlyRepairingThem() {
        NormalizedVariantKey nonCanonical = new NormalizedVariantKey(
                "GRCh38", "chr1", 100, "a", "t"
        );

        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_KEY_INVALID",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A,
                        1,
                        1,
                        1,
                        GenomeAssembly.GRCh38,
                        GenomeAssembly.GRCh38,
                        nonCanonical,
                        null,
                        key(GenomeAssembly.GRCh38, "1", 100, "A", "T")
                )
        );
    }

    @Test
    void rejectsNonSequenceAndNoOpAllelesAtTheLineageBoundary() {
        assertInvalidSourceAllele("A", "<DEL>");
        assertInvalidSourceAllele("A", "*");
        assertInvalidSourceAllele("A", "A]2:321]");
        assertInvalidSourceAllele("A", "-");
        assertInvalidSourceAllele("A", "A");
    }

    @Test
    void rejectsUploaderControlledContigTextBeforeItCanEnterThePrivateDomain() {
        NormalizedVariantKey unsafe = key(
                GenomeAssembly.GRCh38,
                "patient_17",
                100,
                "A",
                "T"
        );

        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_KEY_INVALID",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A,
                        1,
                        1,
                        1,
                        GenomeAssembly.GRCh38,
                        GenomeAssembly.GRCh38,
                        unsafe,
                        null,
                        key(GenomeAssembly.GRCh38, "1", 100, "A", "T")
                )
        );
    }

    @Test
    void acceptsBoundedReferenceContigShapesPendingRuntimeDictionaryBinding() {
        NormalizedVariantKey accession = key(
                GenomeAssembly.GRCh37,
                "GL000191.1",
                100,
                "A",
                "T"
        );
        NormalizedVariantKey ucscAuxiliary = key(
                GenomeAssembly.GRCh37,
                "chr1_gl000191_random",
                100,
                "A",
                "T"
        );

        assertEquals(accession, AnalysisAlleleLineage.mapped(
                TOKEN_A, 1, 1, 1,
                GenomeAssembly.GRCh37, GenomeAssembly.GRCh37,
                accession, null, accession
        ).getCanonicalKey());
        assertEquals(ucscAuxiliary, AnalysisAlleleLineage.mapped(
                TOKEN_B, 2, 1, 1,
                GenomeAssembly.GRCh37, GenomeAssembly.GRCh37,
                ucscAuxiliary, null, ucscAuxiliary
        ).getCanonicalKey());
    }

    @Test
    void enforcesTheCoreAlleleLengthCeilingAtTheExactBoundary() {
        NormalizedVariantKey boundary = key(
                GenomeAssembly.GRCh38,
                "1",
                100,
                "A".repeat(16_384),
                "C"
        );
        NormalizedVariantKey tooLong = key(
                GenomeAssembly.GRCh38,
                "1",
                100,
                "A".repeat(16_385),
                "C"
        );

        assertEquals(boundary, AnalysisAlleleLineage.mapped(
                TOKEN_A, 1, 1, 1,
                GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                boundary, null, boundary
        ).getCanonicalKey());
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_KEY_INVALID",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_B, 2, 1, 1,
                        GenomeAssembly.GRCh38, GenomeAssembly.GRCh38,
                        tooLong, null, key(GenomeAssembly.GRCh38, "1", 100, "A", "T")
                )
        );
    }

    @Test
    void mappedAndLossStatesAreMutuallyExclusive() {
        NormalizedVariantKey key = key(GenomeAssembly.GRCh38, "1", 100, "A", "T");

        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_MAPPED_LOSS_CODE_PRESENT",
                () -> contract(
                        AnalysisAlleleLineage.MappingStatus.MAPPED,
                        key,
                        null,
                        key,
                        AnalysisAlleleLineage.LossCode.UNKNOWN_MAPPING_FAILURE
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_CANONICAL_KEY_REQUIRED",
                () -> contract(
                        AnalysisAlleleLineage.MappingStatus.MAPPED,
                        key,
                        null,
                        null,
                        null
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_REJECTED_KEY_PRESENT",
                () -> contract(
                        AnalysisAlleleLineage.MappingStatus.LIFTOVER_REJECTED,
                        key,
                        null,
                        key,
                        AnalysisAlleleLineage.LossCode.LIFTOVER_NO_TARGET
                )
        );
    }

    @Test
    void lossCodesCannotBeUsedWithTheWrongFailureStage() {
        NormalizedVariantKey key = key(GenomeAssembly.GRCh38, "1", 100, "A", "T");

        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_LIFTOVER_LOSS_CODE_INVALID",
                () -> contract(
                        AnalysisAlleleLineage.MappingStatus.LIFTOVER_REJECTED,
                        key,
                        null,
                        null,
                        AnalysisAlleleLineage.LossCode.NORMALIZATION_PROCESS_FAILED
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_NORMALIZATION_LOSS_CODE_INVALID",
                () -> contract(
                        AnalysisAlleleLineage.MappingStatus.NORMALIZATION_REJECTED,
                        key,
                        null,
                        null,
                        AnalysisAlleleLineage.LossCode.LIFTOVER_OTHER_REJECT
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_UNRESOLVED_LOSS_CODE_INVALID",
                () -> contract(
                        AnalysisAlleleLineage.MappingStatus.UNRESOLVED,
                        key,
                        null,
                        null,
                        AnalysisAlleleLineage.LossCode.NORMALIZATION_INVALID_ALLELE
                )
        );
    }

    @Test
    void rejectsUnknownSchemaRatherThanTreatingItAsCurrent() {
        NormalizedVariantKey key = key(GenomeAssembly.GRCh38, "1", 100, "A", "T");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new AnalysisAlleleLineage(
                        "analysis-allele-lineage-core-v0",
                        AnalysisAlleleLineage.MappingStatus.MAPPED,
                        TOKEN_A,
                        1L,
                        1,
                        1,
                        "GRCh38",
                        "GRCh38",
                        key,
                        null,
                        key,
                        null
                )
        );

        assertEquals("ANALYSIS_ALLELE_LINEAGE_SCHEMA_INVALID", failure.getMessage());
    }

    @Test
    void rejectsMissingSchemaAndAnalysisAssembly() {
        NormalizedVariantKey key = key(GenomeAssembly.GRCh38, "1", 100, "A", "T");

        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SCHEMA_REQUIRED",
                () -> new AnalysisAlleleLineage(
                        null,
                        AnalysisAlleleLineage.MappingStatus.MAPPED,
                        TOKEN_A,
                        1L,
                        1,
                        1,
                        "GRCh38",
                        "GRCh38",
                        key,
                        null,
                        key,
                        null
                )
        );
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_ANALYSIS_ASSEMBLY_INVALID",
                () -> new AnalysisAlleleLineage(
                        AnalysisAlleleLineage.CURRENT_SCHEMA_VERSION,
                        AnalysisAlleleLineage.MappingStatus.MAPPED,
                        TOKEN_A,
                        1L,
                        1,
                        1,
                        "GRCh38",
                        null,
                        key,
                        null,
                        key,
                        null
                )
        );
    }

    private AnalysisAlleleLineage contract(AnalysisAlleleLineage.MappingStatus status,
                                            NormalizedVariantKey sourceKey,
                                            NormalizedVariantKey liftedKey,
                                            NormalizedVariantKey canonicalKey,
                                            AnalysisAlleleLineage.LossCode lossCode) {
        String analysisAssembly = status == AnalysisAlleleLineage.MappingStatus.LIFTOVER_REJECTED
                ? GenomeAssembly.GRCh37.name()
                : sourceKey.getGenomeAssembly();
        return new AnalysisAlleleLineage(
                AnalysisAlleleLineage.CURRENT_SCHEMA_VERSION,
                status,
                TOKEN_A,
                1L,
                1,
                1,
                sourceKey.getGenomeAssembly(),
                analysisAssembly,
                sourceKey,
                liftedKey,
                canonicalKey,
                lossCode
        );
    }

    private NormalizedVariantKey key(GenomeAssembly assembly,
                                     String chromosome,
                                     int position,
                                     String reference,
                                     String alternate) {
        return NormalizedVariantKey.canonical(
                assembly,
                chromosome,
                position,
                reference,
                alternate
        );
    }

    private void assertInvalidSourceAllele(String reference, String alternate) {
        assertFailure(
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_KEY_INVALID",
                () -> AnalysisAlleleLineage.mapped(
                        TOKEN_A,
                        1,
                        1,
                        1,
                        GenomeAssembly.GRCh38,
                        GenomeAssembly.GRCh38,
                        key(GenomeAssembly.GRCh38, "1", 100, reference, alternate),
                        null,
                        key(GenomeAssembly.GRCh38, "1", 100, "A", "T")
                )
        );
    }

    private void assertFailure(String expectedCode, Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals(expectedCode, failure.getMessage());
    }
}
