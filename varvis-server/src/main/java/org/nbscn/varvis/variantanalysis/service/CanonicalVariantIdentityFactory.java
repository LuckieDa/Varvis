package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.CanonicalVariantIdentity;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CanonicalVariantIdentityFactory {

    private CanonicalVariantIdentityFactory() {
    }

    public static CanonicalVariantIdentity create(NormalizedVariantKey key,
                                                  String referenceProfileId) {
        if (key == null) {
            return null;
        }
        String profileId = normalizedProfileId(referenceProfileId);
        boolean profileBound = profileId != null;
        String canonicalId = key.canonicalId();
        String identityDigest = profileBound
                ? sha256(CanonicalVariantIdentity.SCHEMA_VERSION + "|" + profileId + "|" + canonicalId)
                : null;
        int interbaseStart = Math.max(0, key.getPosition() - 1);
        int referenceLength = key.getReference() == null ? 0 : key.getReference().length();
        return new CanonicalVariantIdentity(
                profileBound
                        ? CanonicalVariantIdentity.REFERENCE_PROFILE_BOUND
                        : CanonicalVariantIdentity.REFERENCE_PROFILE_UNRESOLVED,
                key.getGenomeAssembly(),
                profileId,
                canonicalId,
                identityDigest == null ? null : "varvis:canonical:" + identityDigest,
                key.getChromosome(),
                key.getPosition(),
                key.getReference(),
                key.getAlternate(),
                interbaseStart,
                interbaseStart + referenceLength,
                null,
                null,
                profileBound
                        ? "REFERENCE_SEQUENCE_DIGEST_REQUIRED"
                        : "REFERENCE_PROFILE_REQUIRED"
        );
    }

    private static String normalizedProfileId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return "NOT_RECORDED".equalsIgnoreCase(normalized) ? null : normalized;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format("%02x", current & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
