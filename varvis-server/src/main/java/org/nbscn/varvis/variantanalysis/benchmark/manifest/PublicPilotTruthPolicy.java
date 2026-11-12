package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class PublicPilotTruthPolicy {

    public static final String POLICY_VERSION = "public-singleton-truth-policy-v1";

    private PublicPilotTruthPolicy() {
    }

    public static BenchmarkTruthPolicyAssessment assess(BenchmarkDatasetCase datasetCase) {
        BenchmarkTruthPolicyAssessment result = new BenchmarkTruthPolicyAssessment();
        result.setCaseId(datasetCase == null ? null : datasetCase.getCaseId());
        if (datasetCase == null) {
            invalid(result, BenchmarkTruthSetSemantics.INCOMPLETE_CANONICAL_TRUTH,
                    BenchmarkPilotExclusionReason.INCOMPLETE_CANONICAL_ALLELE);
            result.setTruthSetFingerprint(sha256("missing-case"));
            return result;
        }

        String relationship = normalizeRelationship(datasetCase.getTruthRelationship());
        BenchmarkTruthSetSemantics semantics = semantics(relationship);
        result.setSourceRelationship(relationship);
        result.setRelationshipProvenance(datasetCase.getTruthProvenance());
        result.setSemantics(semantics);

        List<BenchmarkTruthVariant> truths = datasetCase.getTruthVariants() == null
                ? Collections.<BenchmarkTruthVariant>emptyList() : datasetCase.getTruthVariants();
        List<String> identities = new ArrayList<String>();
        Set<String> unique = new HashSet<String>();
        boolean canonicalValid = true;
        boolean assemblyValid = true;
        for (BenchmarkTruthVariant truth : truths) {
            NormalizedVariantKey key = truth == null ? null : truth.getCanonicalIdentity();
            if (!complete(key)) {
                canonicalValid = false;
                continue;
            }
            NormalizedVariantKey completeKey = Objects.requireNonNull(key);
            String canonicalId = completeKey.canonicalId();
            identities.add(canonicalId);
            if (!unique.add(canonicalId)) {
                add(result, BenchmarkPilotExclusionReason.DUPLICATE_TRUTH);
            }
            if (!"GRCh38".equals(completeKey.getGenomeAssembly())
                    || !completeKey.getGenomeAssembly().equals(datasetCase.getAssembly())) {
                assemblyValid = false;
            }
        }
        Collections.sort(identities);
        result.setTruthSetFingerprint(sha256(POLICY_VERSION + "\n" + semantics.name()
                + "\n" + join(identities)));

        if (!canonicalValid || identities.isEmpty()) {
            invalid(result, BenchmarkTruthSetSemantics.INCOMPLETE_CANONICAL_TRUTH,
                    BenchmarkPilotExclusionReason.INCOMPLETE_CANONICAL_ALLELE);
        }
        if (!assemblyValid) {
            invalid(result, result.getSemantics(), BenchmarkPilotExclusionReason.ASSEMBLY_MISMATCH);
        }
        if (!result.getExclusionReasons().isEmpty()) {
            result.setEligibilityStatus(BenchmarkPilotEligibilityStatus.INVALID);
            return result;
        }
        if (!hasText(datasetCase.getTruthProvenance())) {
            invalid(result, semantics, BenchmarkPilotExclusionReason.RELATIONSHIP_PROVENANCE_REQUIRED);
            return result;
        }

        int count = identities.size();
        if ((semantics == BenchmarkTruthSetSemantics.SINGLE_REQUIRED_ALLELE && count != 1)
                || (semantics != BenchmarkTruthSetSemantics.SINGLE_REQUIRED_ALLELE
                && semantics != BenchmarkTruthSetSemantics.UNSUPPORTED_VARIATION && count < 2)) {
            invalid(result, semantics, BenchmarkPilotExclusionReason.INVALID_TRUTH_CARDINALITY);
            return result;
        }

        switch (semantics) {
            case SINGLE_REQUIRED_ALLELE:
            case EXPLICIT_ALTERNATIVES:
                result.setEligibilityStatus(BenchmarkPilotEligibilityStatus.ELIGIBLE);
                break;
            case EXPLICIT_REQUIRED_SET:
                excluded(result, BenchmarkPilotExclusionReason.REQUIRED_SET_RESERVED_FOR_FUTURE_STRATUM);
                break;
            case PHASED_REQUIRED_SET:
                excluded(result, BenchmarkPilotExclusionReason.PHASED_REQUIRED_SET_RESERVED_FOR_FUTURE_STRATUM);
                break;
            case UNSUPPORTED_VARIATION:
                excluded(result, BenchmarkPilotExclusionReason.UNSUPPORTED_VARIATION_TYPE);
                break;
            case MULTI_TRUTH_RELATIONSHIP_UNRESOLVED:
            case INCOMPLETE_CANONICAL_TRUTH:
            default:
                excluded(result, BenchmarkPilotExclusionReason.MULTI_TRUTH_RELATIONSHIP_UNRESOLVED);
                break;
        }
        return result;
    }

    private static BenchmarkTruthSetSemantics semantics(String relationship) {
        if ("SINGLE_VARIANT".equals(relationship) || "SINGLE_REQUIRED_ALLELE".equals(relationship)) {
            return BenchmarkTruthSetSemantics.SINGLE_REQUIRED_ALLELE;
        }
        if ("EXPLICIT_ALTERNATIVES".equals(relationship)) {
            return BenchmarkTruthSetSemantics.EXPLICIT_ALTERNATIVES;
        }
        if ("EXPLICIT_REQUIRED_SET".equals(relationship)) {
            return BenchmarkTruthSetSemantics.EXPLICIT_REQUIRED_SET;
        }
        if ("PHASED_REQUIRED_SET".equals(relationship)) {
            return BenchmarkTruthSetSemantics.PHASED_REQUIRED_SET;
        }
        if ("UNSUPPORTED_VARIATION".equals(relationship)) {
            return BenchmarkTruthSetSemantics.UNSUPPORTED_VARIATION;
        }
        return BenchmarkTruthSetSemantics.MULTI_TRUTH_RELATIONSHIP_UNRESOLVED;
    }

    private static String normalizeRelationship(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT)
                : "MULTI_VARIANT_RELATIONSHIP_UNSPECIFIED";
    }

    private static boolean complete(NormalizedVariantKey key) {
        return key != null && hasText(key.getGenomeAssembly()) && hasText(key.getChromosome())
                && key.getPosition() > 0 && hasText(key.getReference()) && hasText(key.getAlternate());
    }

    private static void invalid(BenchmarkTruthPolicyAssessment result,
                                BenchmarkTruthSetSemantics semantics,
                                BenchmarkPilotExclusionReason reason) {
        result.setSemantics(semantics);
        add(result, reason);
        result.setEligibilityStatus(BenchmarkPilotEligibilityStatus.INVALID);
    }

    private static void excluded(BenchmarkTruthPolicyAssessment result,
                                 BenchmarkPilotExclusionReason reason) {
        add(result, reason);
        result.setEligibilityStatus(BenchmarkPilotEligibilityStatus.EXCLUDED);
    }

    private static void add(BenchmarkTruthPolicyAssessment result,
                            BenchmarkPilotExclusionReason reason) {
        if (!result.getExclusionReasons().contains(reason)) {
            result.getExclusionReasons().add(reason);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static String join(List<String> values) {
        StringBuilder value = new StringBuilder();
        for (String item : values) {
            if (value.length() > 0) {
                value.append('\n');
            }
            value.append(item);
        }
        return value.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
