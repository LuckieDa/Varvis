package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkRunGroup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds G0/G1/G2 groups without allowing the adapter to manufacture G2. */
public final class PhenopacketHpoArmFactory {

    private PhenopacketHpoArmFactory() {
    }

    public static BenchmarkRunGroup g0(String caseId, String configurationFingerprint) {
        return group("G0", caseId, "NONE", Collections.<String>emptyList(),
                "NO_HPO", configurationFingerprint);
    }

    public static BenchmarkRunGroup g1(PhenopacketImportResult sourceResult,
                                       String configurationFingerprint) {
        if (sourceResult == null || !sourceResult.isEligible() || sourceResult.getCaseData() == null) {
            throw new IllegalArgumentException("ELIGIBLE_SOURCE_PHENOPACKET_REQUIRED");
        }
        List<String> terms = new ArrayList<String>();
        for (PhenopacketHpoTerm term : sourceResult.getCaseData().getObservedHpo()) terms.add(term.getId());
        return group("G1", sourceResult.getCaseData().getCaseId(), "SOURCE_PROVIDED", terms,
                "PHENOPACKET_SOURCE", configurationFingerprint);
    }

    public static BenchmarkRunGroup g2(String caseId, HumanReviewedHpoInput humanInput,
                                       String configurationFingerprint) {
        if (humanInput == null || humanInput.getTerms() == null
                || humanInput.getTerms().isEmpty() || !hasText(humanInput.getProvenance())) {
            throw new IllegalArgumentException("EXPLICIT_HUMAN_REVIEWED_HPO_REQUIRED");
        }
        List<String> terms = new ArrayList<String>(humanInput.getTerms());
        Collections.sort(terms);
        return group("G2", caseId, "HUMAN_REVIEWED", terms,
                humanInput.getProvenance(), configurationFingerprint);
    }

    private static BenchmarkRunGroup group(String groupId, String caseId, String mode,
                                           List<String> terms, String provenance, String config) {
        BenchmarkRunGroup group = new BenchmarkRunGroup();
        group.setGroupId(groupId);
        group.setCaseId(caseId);
        group.setHpoInputMode(mode);
        group.setHpoTerms(terms);
        group.setHpoInputChecksum(sha256(String.join("\n", terms)));
        group.setHpoProvenance(provenance);
        group.setTransformationProvenance("stable-sort-and-deduplicate");
        group.setConfigurationFingerprint(config);
        return group;
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(64);
            for (byte b : bytes) value.append(String.format("%02x", b & 0xff));
            return value.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
