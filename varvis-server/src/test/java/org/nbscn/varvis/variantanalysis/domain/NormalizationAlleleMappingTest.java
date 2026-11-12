package org.nbscn.varvis.variantanalysis.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizationAlleleMappingTest {

    @Test
    void treatsChromosomeAliasesAsTheSameIdentityRepresentation() {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh37, "4", 106367589, "t", "c"
        );

        NormalizationAlleleMapping mapping = NormalizationAlleleMapping.from(
                "chr4|106367589|T|C|1",
                key
        );

        assertTrue(mapping.isResolved());
        assertEquals(NormalizationAlleleMapping.IDENTITY, mapping.getStatus());
        assertEquals("GRCh37|4|106367589|T|C", mapping.getCanonicalVariantId());
    }

    @Test
    void recordsAChangedRepresentationAsNormalized() {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, "1", 99, "A", "G"
        );

        NormalizationAlleleMapping mapping = NormalizationAlleleMapping.from(
                "chr1|100|AA|AG|1",
                key
        );

        assertEquals(NormalizationAlleleMapping.NORMALIZED, mapping.getStatus());
        assertEquals("chr1|100|AA|AG", mapping.getRawRecordId());
        assertEquals(Integer.valueOf(1), mapping.getRawAlternateIndex());
    }

    @Test
    void acceptsBcftoolsSingleAltOldRecordTagWithoutExplicitAlleleIndex() {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, "1", 99, "A", "G"
        );

        NormalizationAlleleMapping mapping = NormalizationAlleleMapping.from(
                "chr1|100|AA|AG",
                key
        );

        assertTrue(mapping.isResolved());
        assertEquals(Integer.valueOf(1), mapping.getRawAlternateIndex());
        assertEquals("chr1|100|AA|AG", mapping.getRawRecordId());
    }

    @Test
    void keepsCanonicalIdentityButMarksMalformedOriginalTagUnresolved() {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, "chr1", 100, "A", "G"
        );

        NormalizationAlleleMapping mapping = NormalizationAlleleMapping.from(
                "not-a-bcftools-old-record-tag",
                key
        );

        assertFalse(mapping.isResolved());
        assertEquals(NormalizationAlleleMapping.UNRESOLVED_ORIGINAL, mapping.getStatus());
        assertEquals("GRCh38|1|100|A|G", mapping.getCanonicalVariantId());
        assertNull(mapping.getRawRecordId());
    }
}
