package org.nbscn.varvis.variantanalysis.benchmark.selection;

import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkDatasetCase;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkPilotEligibilityStatus;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthPolicyAssessment;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkTruthVariant;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.PublicPilotTruthPolicy;
import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketEligibilityStatus;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, result-independent selector for a proposed public development pilot. */
public class PublicBenchmarkPilotSelector {
    public static final String ALGORITHM_VERSION = "public-pilot-selector-v1";
    public static final String DETERMINISTIC_SEED = "stage2b5-public-pilot-proposal-v1";
    private static final String CONTRACT = "main=25;backup=5;GRCh38;adapter-eligible;single-gene;observed-HPO;"
            + "SNV-small-indel;truth-policy-v1;all-truth-in-BED;exclude-smoke-by-case-checksum-truth-truthset;"
            + "deduplicate-case-checksum-truth-truthset;unique-gene;greedy-chromosome-type-HPO-band-source;"
            + "tie=sha256(seed,case,truth,source);no-rank-clinvar-disease-fame";

    public PilotSelectionResult select(List<PublicBenchmarkSmokeCandidate> input,
                                       PublicBenchmarkPilotExclusionRegistry prior,
                                       int mainTarget, int backupTarget) {
        List<PublicBenchmarkSmokeCandidate> candidates = input == null
                ? new ArrayList<PublicBenchmarkSmokeCandidate>()
                : new ArrayList<PublicBenchmarkSmokeCandidate>(input);
        Collections.sort(candidates, Comparator.comparing(this::stableKey));
        Map<String, Integer> caseCounts = counts(candidates, IdentityKind.CASE);
        Map<String, Integer> sourceCounts = counts(candidates, IdentityKind.SOURCE);
        Map<String, Integer> truthCounts = counts(candidates, IdentityKind.TRUTH);
        Map<String, Integer> truthSetCounts = counts(candidates, IdentityKind.TRUTH_SET);
        List<PilotSelectionDecision> decisions = new ArrayList<PilotSelectionDecision>();
        List<PilotSelectionDecision> eligible = new ArrayList<PilotSelectionDecision>();
        for (PublicBenchmarkSmokeCandidate candidate : candidates) {
            PilotSelectionDecision decision = assess(candidate, prior, caseCounts, sourceCounts,
                    truthCounts, truthSetCounts);
            decisions.add(decision);
            if (decision.getReasons().isEmpty()) {
                decision.setRole(PilotCandidateRole.ELIGIBLE_NOT_SELECTED);
                eligible.add(decision);
            } else {
                decision.setRole(candidate == null || candidate.getImported() == null
                        ? PilotCandidateRole.INVALID : PilotCandidateRole.EXCLUDED);
            }
        }
        choose(eligible, mainTarget, backupTarget);
        PilotSelectionResult result = new PilotSelectionResult();
        result.setAlgorithmVersion(ALGORITHM_VERSION);
        result.setAlgorithmFingerprint(sha256(CONTRACT));
        result.setDeterministicSeed(DETERMINISTIC_SEED);
        result.setDecisions(decisions);
        result.setComplete(countRole(decisions, PilotCandidateRole.MAIN) == mainTarget
                && countRole(decisions, PilotCandidateRole.BACKUP) == backupTarget
                && selected(decisions).stream().anyMatch(d -> "SNV".equals(d.getVariantStratum()))
                && selected(decisions).stream().anyMatch(d -> d.getVariantStratum().contains("INDEL")));
        return result;
    }

    private PilotSelectionDecision assess(PublicBenchmarkSmokeCandidate candidate,
                                           PublicBenchmarkPilotExclusionRegistry prior,
                                           Map<String, Integer> caseCounts,
                                           Map<String, Integer> sourceCounts,
                                           Map<String, Integer> truthCounts,
                                           Map<String, Integer> truthSetCounts) {
        PilotSelectionDecision result = new PilotSelectionDecision();
        result.setCandidate(candidate);
        result.setCaseId(caseId(candidate));
        BenchmarkDatasetCase datasetCase = datasetCase(candidate);
        BenchmarkTruthPolicyAssessment policy = PublicPilotTruthPolicy.assess(datasetCase);
        result.setTruthPolicyAssessment(policy);
        result.setVariantStratum(variantStratum(datasetCase));
        result.setHpoCountBand(hpoBand(candidate));
        if (candidate == null || candidate.getImported() == null
                || candidate.getImported().getStatus() != PhenopacketEligibilityStatus.ELIGIBLE
                || candidate.getImported().getCaseData() == null) {
            add(result, PilotSelectionExclusionReason.ADAPTER_NOT_ELIGIBLE);
            return result;
        }
        if (datasetCase == null || !"GRCh38".equals(datasetCase.getAssembly())) add(result, PilotSelectionExclusionReason.NON_GRCH38);
        if (candidate.getImported().getCaseData().getTruthGenes() == null
                || candidate.getImported().getCaseData().getTruthGenes().size() != 1) add(result, PilotSelectionExclusionReason.NOT_SINGLE_GENE);
        if (candidate.getImported().getCaseData().getObservedHpo() == null
                || candidate.getImported().getCaseData().getObservedHpo().isEmpty()) add(result, PilotSelectionExclusionReason.MISSING_OBSERVED_HPO);
        if (!safeRelative(candidate.getSourceRelativePath())) add(result, PilotSelectionExclusionReason.UNSAFE_SOURCE_REFERENCE);
        if (candidate.getSourceSha256() == null || !candidate.getSourceSha256().matches("[0-9a-f]{64}")) add(result, PilotSelectionExclusionReason.INVALID_SOURCE_CHECKSUM);
        if (!smallVariants(datasetCase)) add(result, PilotSelectionExclusionReason.UNSUPPORTED_SMALL_VARIANT);
        if (!candidate.isAllTruthInsideBenchmarkRegion()) add(result, PilotSelectionExclusionReason.TRUTH_OUTSIDE_BENCHMARK_REGION);
        if (policy.getEligibilityStatus() != BenchmarkPilotEligibilityStatus.ELIGIBLE) add(result, PilotSelectionExclusionReason.TRUTH_POLICY_NOT_ELIGIBLE);
        addDuplicateReasons(result, candidate, policy, caseCounts, sourceCounts, truthCounts, truthSetCounts);
        addPriorReasons(result, candidate, policy, prior);
        return result;
    }

    private void choose(List<PilotSelectionDecision> eligible, int mainTarget, int backupTarget) {
        List<PilotSelectionDecision> remaining = new ArrayList<PilotSelectionDecision>(eligible);
        Map<String, Integer> chromosomeCounts = new HashMap<String, Integer>();
        Map<String, Integer> typeCounts = new HashMap<String, Integer>();
        Map<String, Integer> hpoCounts = new HashMap<String, Integer>();
        Map<String, Integer> sourceCounts = new HashMap<String, Integer>();
        Set<String> genes = new HashSet<String>();
        int total = mainTarget + backupTarget;
        for (int order = 1; order <= total; order++) {
            PilotSelectionDecision best = null;
            long bestScore = Long.MIN_VALUE;
            for (PilotSelectionDecision candidate : remaining) {
                String gene = gene(candidate.getCandidate());
                if (gene == null || genes.contains(gene)) continue;
                String chromosome = chromosome(candidate.getCandidate());
                String type = candidate.getVariantStratum();
                String hpoBand = candidate.getHpoCountBand();
                String source = sourceStratum(candidate.getCandidate());
                long score = (value(chromosomeCounts, chromosome) == 0 ? 1_000_000L : 0L)
                        + scarcity(typeCounts, type, 100_000L)
                        + scarcity(hpoCounts, hpoBand, 10_000L)
                        + (value(sourceCounts, source) == 0 ? 1_000L : 0L)
                        - value(chromosomeCounts, chromosome) * 100L;
                if (best == null || score > bestScore
                        || (score == bestScore && tieKey(candidate).compareTo(tieKey(best)) < 0)) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best == null) break;
            best.setRole(order <= mainTarget ? PilotCandidateRole.MAIN : PilotCandidateRole.BACKUP);
            best.setSelectionOrder(order);
            remaining.remove(best);
            genes.add(gene(best.getCandidate()));
            increment(chromosomeCounts, chromosome(best.getCandidate()));
            increment(typeCounts, best.getVariantStratum());
            increment(hpoCounts, best.getHpoCountBand());
            increment(sourceCounts, sourceStratum(best.getCandidate()));
        }
    }

    private void addDuplicateReasons(PilotSelectionDecision result, PublicBenchmarkSmokeCandidate candidate,
                                     BenchmarkTruthPolicyAssessment policy, Map<String, Integer> cases,
                                     Map<String, Integer> sources, Map<String, Integer> truths,
                                     Map<String, Integer> truthSets) {
        if (value(cases, caseId(candidate)) > 1) add(result, PilotSelectionExclusionReason.DUPLICATE_CASE_ID);
        if (value(sources, candidate.getSourceSha256()) > 1) add(result, PilotSelectionExclusionReason.DUPLICATE_SOURCE_CHECKSUM);
        for (String truth : truthIds(candidate)) if (value(truths, truth) > 1) { add(result, PilotSelectionExclusionReason.DUPLICATE_TRUTH_IDENTITY); break; }
        if (value(truthSets, policy.getTruthSetFingerprint()) > 1) add(result, PilotSelectionExclusionReason.DUPLICATE_TRUTH_SET);
    }

    private void addPriorReasons(PilotSelectionDecision result, PublicBenchmarkSmokeCandidate candidate,
                                 BenchmarkTruthPolicyAssessment policy,
                                 PublicBenchmarkPilotExclusionRegistry prior) {
        if (prior == null) return;
        if (prior.getCaseIds().contains(caseId(candidate))) add(result, PilotSelectionExclusionReason.PRIOR_DEVELOPMENT_SMOKE_CASE_ID);
        if (prior.getSourceChecksums().contains(candidate.getSourceSha256())) add(result, PilotSelectionExclusionReason.PRIOR_DEVELOPMENT_SMOKE_SOURCE_CHECKSUM);
        for (String truth : truthIds(candidate)) if (prior.getTruthCanonicalIdentities().contains(truth)) { add(result, PilotSelectionExclusionReason.PRIOR_DEVELOPMENT_SMOKE_TRUTH_IDENTITY); break; }
        if (prior.getTruthSetFingerprints().contains(policy.getTruthSetFingerprint())) add(result, PilotSelectionExclusionReason.PRIOR_DEVELOPMENT_SMOKE_TRUTH_SET);
    }

    private Map<String, Integer> counts(List<PublicBenchmarkSmokeCandidate> candidates, IdentityKind kind) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (PublicBenchmarkSmokeCandidate candidate : candidates) {
            List<String> values = new ArrayList<String>();
            if (kind == IdentityKind.CASE) values.add(caseId(candidate));
            if (kind == IdentityKind.SOURCE) values.add(candidate == null ? null : candidate.getSourceSha256());
            if (kind == IdentityKind.TRUTH) values.addAll(truthIds(candidate));
            if (kind == IdentityKind.TRUTH_SET) values.add(PublicPilotTruthPolicy.assess(datasetCase(candidate)).getTruthSetFingerprint());
            for (String item : values) if (item != null) increment(result, item);
        }
        return result;
    }

    private boolean smallVariants(BenchmarkDatasetCase datasetCase) {
        if (datasetCase == null || datasetCase.getTruthVariants() == null || datasetCase.getTruthVariants().isEmpty()) return false;
        for (BenchmarkTruthVariant truth : datasetCase.getTruthVariants()) {
            NormalizedVariantKey key = truth == null ? null : truth.getCanonicalIdentity();
            if (key == null || !key.getReference().matches("[ACGT]+") || !key.getAlternate().matches("[ACGT]+")) return false;
        }
        return true;
    }

    private String variantStratum(BenchmarkDatasetCase datasetCase) {
        boolean snv = false;
        boolean indel = false;
        if (datasetCase != null && datasetCase.getTruthVariants() != null) for (BenchmarkTruthVariant truth : datasetCase.getTruthVariants()) {
            NormalizedVariantKey key = truth == null ? null : truth.getCanonicalIdentity();
            if (key == null || key.getReference() == null || key.getAlternate() == null) continue;
            if (key.getReference().length() == 1 && key.getAlternate().length() == 1) snv = true; else indel = true;
        }
        return snv && indel ? "MIXED_SNV_INDEL" : indel ? "SMALL_INDEL" : "SNV";
    }

    private String hpoBand(PublicBenchmarkSmokeCandidate candidate) {
        int count = candidate == null || candidate.getImported() == null || candidate.getImported().getCaseData() == null
                || candidate.getImported().getCaseData().getObservedHpo() == null ? 0
                : candidate.getImported().getCaseData().getObservedHpo().size();
        return count <= 3 ? "HPO_1_3" : count <= 8 ? "HPO_4_8" : "HPO_9_PLUS";
    }

    private String tieKey(PilotSelectionDecision decision) { return sha256(DETERMINISTIC_SEED + "|" + stableKey(decision.getCandidate())); }
    private String stableKey(PublicBenchmarkSmokeCandidate candidate) { return String.valueOf(caseId(candidate)) + "|" + String.join(",", truthIds(candidate)) + "|" + (candidate == null ? "" : String.valueOf(candidate.getSourceSha256())); }
    private String caseId(PublicBenchmarkSmokeCandidate candidate) { return candidate == null || candidate.getImported() == null || candidate.getImported().getCaseData() == null ? null : candidate.getImported().getCaseData().getCaseId(); }
    private BenchmarkDatasetCase datasetCase(PublicBenchmarkSmokeCandidate candidate) { return candidate == null || candidate.getImported() == null || candidate.getImported().getCaseData() == null ? null : candidate.getImported().getCaseData().getDatasetCase(); }
    private String gene(PublicBenchmarkSmokeCandidate candidate) { return candidate == null || candidate.getImported() == null || candidate.getImported().getCaseData() == null || candidate.getImported().getCaseData().getTruthGenes() == null || candidate.getImported().getCaseData().getTruthGenes().size() != 1 ? null : candidate.getImported().getCaseData().getTruthGenes().get(0); }
    private String chromosome(PublicBenchmarkSmokeCandidate candidate) { BenchmarkDatasetCase value = datasetCase(candidate); return value == null || value.getTruthVariants().isEmpty() || value.getTruthVariants().get(0).getCanonicalIdentity() == null ? "UNAVAILABLE" : value.getTruthVariants().get(0).getCanonicalIdentity().getChromosome(); }
    private String sourceStratum(PublicBenchmarkSmokeCandidate candidate) { String path = candidate == null ? null : candidate.getSourceRelativePath(); if (path == null) return "UNAVAILABLE"; int slash = path.indexOf('/'); return slash < 0 ? "ROOT" : path.substring(0, slash); }
    private List<String> truthIds(PublicBenchmarkSmokeCandidate candidate) { List<String> result = new ArrayList<String>(); BenchmarkDatasetCase value = datasetCase(candidate); if (value != null && value.getTruthVariants() != null) for (BenchmarkTruthVariant truth : value.getTruthVariants()) if (truth != null && truth.getCanonicalIdentity() != null) result.add(truth.getCanonicalIdentity().canonicalId()); Collections.sort(result); return result; }
    private boolean safeRelative(String value) { return value != null && !value.startsWith("/") && !value.startsWith("\\") && !value.matches("^[A-Za-z]:.*") && !value.contains(".."); }
    private long scarcity(Map<String, Integer> counts, String key, long weight) { int minimum = counts.isEmpty() ? 0 : Collections.min(counts.values()); return value(counts, key) <= minimum ? weight : 0L; }
    private int value(Map<String, Integer> counts, String key) { Integer value = key == null ? null : counts.get(key); return value == null ? 0 : value; }
    private void increment(Map<String, Integer> counts, String key) { if (key != null) counts.put(key, value(counts, key) + 1); }
    private void add(PilotSelectionDecision decision, PilotSelectionExclusionReason reason) { if (!decision.getReasons().contains(reason)) decision.getReasons().add(reason); }
    private long countRole(List<PilotSelectionDecision> values, PilotCandidateRole role) { return values.stream().filter(value -> value.getRole() == role).count(); }
    private List<PilotSelectionDecision> selected(List<PilotSelectionDecision> values) { List<PilotSelectionDecision> result = new ArrayList<PilotSelectionDecision>(); for (PilotSelectionDecision value : values) if (value.getRole() == PilotCandidateRole.MAIN || value.getRole() == PilotCandidateRole.BACKUP) result.add(value); return result; }
    private String sha256(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte item : digest) result.append(String.format("%02x", item & 0xff)); return result.toString(); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private enum IdentityKind { CASE, SOURCE, TRUTH, TRUTH_SET }
}
