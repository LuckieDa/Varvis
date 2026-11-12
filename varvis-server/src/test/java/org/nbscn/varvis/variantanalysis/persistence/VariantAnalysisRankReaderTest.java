package org.nbscn.varvis.variantanalysis.persistence;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VariantAnalysisRankReaderTest {

    @Test
    void readsTruthBeyondFiveThousandWithoutCsvOrPageLimit() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        List<VariantInterpretation> values = new ArrayList<VariantInterpretation>();
        for (int index = 0; index < 5001; index++) {
            values.add(variant("variant-" + index, "1", index + 1, 1.0d));
        }
        repository.replaceInterpretations("analysis", values);

        VariantRankLookup lookup = new VariantAnalysisRankReader(repository, 127).find(
                "analysis",
                key("GRCh38", "1", 5001)
        );

        assertEquals(RankStatus.RANKED, lookup.getStatus());
        assertEquals(Integer.valueOf(5001), lookup.getRank());
        assertEquals(5001, lookup.getRankingUniverseSize());
        assertEquals(5001, lookup.getScannedCount());
    }

    @Test
    void pageSizeDoesNotChangeRankAndAssemblyMustMatch() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        repository.replaceInterpretations(
                "analysis",
                java.util.Arrays.asList(
                        variant("first", "1", 10, 20.0d),
                        variant("second", "1", 11, 10.0d)
                )
        );

        VariantRankLookup smallPage = new VariantAnalysisRankReader(repository, 1).find(
                "analysis", key("GRCh38", "1", 11)
        );
        VariantRankLookup largePage = new VariantAnalysisRankReader(repository, 100).find(
                "analysis", key("GRCh38", "1", 11)
        );
        VariantRankLookup wrongAssembly = new VariantAnalysisRankReader(repository, 100).find(
                "analysis", key("GRCh37", "1", 11)
        );

        assertEquals(Integer.valueOf(2), smallPage.getRank());
        assertEquals(Integer.valueOf(2), largePage.getRank());
        assertEquals(RankStatus.NOT_IN_RANKING_UNIVERSE, wrongAssembly.getStatus());
        assertNull(wrongAssembly.getRank());
    }

    @Test
    void duplicateCanonicalIdentityIsNotAssignedAnArbitraryRank() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        repository.replaceInterpretations(
                "analysis",
                java.util.Arrays.asList(
                        variant("first", "1", 10, 20.0d),
                        variant("duplicate", "1", 10, 10.0d)
                )
        );

        VariantRankLookup lookup = new VariantAnalysisRankReader(repository).find(
                "analysis", key("GRCh38", "1", 10)
        );

        assertEquals(RankStatus.RANK_UNAVAILABLE, lookup.getStatus());
        assertNull(lookup.getRank());
    }

    @Test
    void emptyPageForNonemptyUniverseIsUnavailableRatherThanNotFound() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository() {
            @Override
            public List<PersistedRankingCandidate> findRankingPage(String id, int offset, int limit) {
                return Collections.emptyList();
            }
        };
        repository.replaceInterpretations(
                "analysis",
                Collections.singletonList(variant("one", "1", 10, 1.0d))
        );

        VariantRankLookup lookup = new VariantAnalysisRankReader(repository).find(
                "analysis", key("GRCh38", "1", 10)
        );

        assertEquals(RankStatus.RANK_UNAVAILABLE, lookup.getStatus());
        assertNull(lookup.getRank());
    }

    private VariantInterpretation variant(String id, String chromosome, int position, Double score) {
        return new VariantInterpretation(
                id,
                new VariantQcObservation(
                        key("GRCh38", chromosome, position),
                        "synthetic-sample", "0/1", 30, 99, null, 0.5d,
                        "PASS", "SNV", null
                ),
                Collections.emptyList(),
                Collections.emptyList(),
                "CANDIDATE", score, Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyList()
        );
    }

    private NormalizedVariantKey key(String assembly, String chromosome, int position) {
        return new NormalizedVariantKey(assembly, chromosome, position, "A", "G");
    }
}
