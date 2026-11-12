package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.CanonicalVariantIdentity;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CanonicalVariantIdentityFactoryTest {

    @Test
    void bindsNormalizedAlleleToAReferenceProfileWithoutClaimingVrsIdentity() {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, "chr4", 100, "AT", "A"
        );

        CanonicalVariantIdentity identity = CanonicalVariantIdentityFactory.create(
                key, "grch38-profile-v1"
        );

        assertEquals(CanonicalVariantIdentity.REFERENCE_PROFILE_BOUND, identity.getStatus());
        assertEquals("GRCh38|4|100|AT|A", identity.getCanonicalVariantId());
        assertEquals(99, identity.getInterbaseStart());
        assertEquals(101, identity.getInterbaseEnd());
        assertNotNull(identity.getVarvisComputedIdentifier());
        assertNull(identity.getRefgetSequenceDigest());
        assertNull(identity.getVrsComputedIdentifier());
        assertEquals("REFERENCE_SEQUENCE_DIGEST_REQUIRED", identity.getVrsCompatibilityStatus());
    }

    @Test
    void doesNotInventAProfileBoundIdentifierWhenProfileIsUnknown() {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh37, "1", 100, "A", "G"
        );

        CanonicalVariantIdentity identity = CanonicalVariantIdentityFactory.create(
                key, "NOT_RECORDED"
        );

        assertEquals(CanonicalVariantIdentity.REFERENCE_PROFILE_UNRESOLVED, identity.getStatus());
        assertNull(identity.getReferenceProfileId());
        assertNull(identity.getVarvisComputedIdentifier());
        assertEquals("REFERENCE_PROFILE_REQUIRED", identity.getVrsCompatibilityStatus());
    }

    @Test
    void profileChangesProduceDifferentInternalComputedIdentifiers() {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38, "1", 100, "A", "G"
        );

        CanonicalVariantIdentity first = CanonicalVariantIdentityFactory.create(key, "profile-a");
        CanonicalVariantIdentity second = CanonicalVariantIdentityFactory.create(key, "profile-b");

        assertNotEquals(first.getVarvisComputedIdentifier(), second.getVarvisComputedIdentifier());
    }
}
