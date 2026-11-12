package org.nbscn.varvis.variantanalysis.service;

import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.persistence.*;

/** A read projection: preserve every source row, select the first formal occurrence of each allele. */
public final class DistinctVariantSelection {
    public static final String POLICY = "DISTINCT_ALLELE_FIRST_FORMAL_OCCURRENCE_V1";
    private DistinctVariantSelection() {}

    public static String identity(NormalizedVariantKey key, String candidateId) {
        var normalized = key == null ? null : PersistedRankingCandidate.key(key.getGenomeAssembly(),
                key.getChromosome(), key.getPosition(), key.getReference(), key.getAlternate());
        // Missing identity never licenses merging two unknown variants.
        return normalized == null ? "UNRESOLVED:" + Objects.requireNonNull(candidateId) : normalized.canonicalId();
    }

    public static List<PersistedRankingCandidate> top(VariantAnalysisJobRepository repository, String id, int n) {
        var selected = new LinkedHashMap<String, PersistedRankingCandidate>();
        var ids = new HashSet<String>();
        for (int offset = 0; selected.size() < n; offset += 500) {
            var page = repository.findRankingPage(id, offset, 500);
            for (var row : page) {
                if (row.getCandidateId() == null || !ids.add(row.getCandidateId()))
                    throw new IllegalStateException("FORMAL_CANDIDATE_ORDER_UNAVAILABLE");
                selected.putIfAbsent(identity(row.getKey(), row.getCandidateId()), row);
                if (selected.size() == n) break;
            }
            if (page.size() < 500) break;
        }
        return List.copyOf(selected.values());
    }

    public static LinkedHashMap<String, List<VariantInterpretation>> groups(List<VariantInterpretation> rows) {
        var result = new LinkedHashMap<String, List<VariantInterpretation>>();
        for (var row : rows) result.computeIfAbsent(identity(row.getObservation() == null ? null : row.getObservation().getKey(),
                row.getCandidateId()), ignored -> new ArrayList<>()).add(row);
        return result;
    }

    public static List<VariantInterpretation> summaries(VariantAnalysisJobRepository repository, String id,
            VariantInterpretationView view, VariantInterpretationFilter filter) {
        var rows = new ArrayList<VariantInterpretation>();
        for (int offset = 0; ; offset += 500) {
            var page = repository.findInterpretationSummaries(id, offset, 500, view, filter);
            rows.addAll(page);
            if (page.size() < 500) return rows;
        }
    }
}
