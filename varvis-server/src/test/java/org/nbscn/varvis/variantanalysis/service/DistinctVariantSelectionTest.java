package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.persistence.*;

class DistinctVariantSelectionTest {
    private PersistedRankingCandidate row(String id, String assembly, String chr, int pos, String alt) {
        return new PersistedRankingCandidate(id, id, PersistedRankingCandidate.key(assembly, chr, pos, "A", alt), 1.0, 0);
    }
    @Test void topNCountsAllelesAcrossPageBoundariesWithoutChangingOrder() {
        var repo = mock(VariantAnalysisJobRepository.class);
        var first = new ArrayList<PersistedRankingCandidate>();
        for (int i=0;i<500;i++) first.add(row("moi-"+i,"GRCh38","1",100,"G"));
        when(repo.findRankingPage("job",0,500)).thenReturn(first);
        when(repo.findRankingPage("job",500,500)).thenReturn(List.of(row("second","GRCh38","1",101,"G")));
        var result = DistinctVariantSelection.top(repo,"job",2);
        assertEquals(List.of("moi-0","second"),result.stream().map(mappedItem -> mappedItem.getCandidateId()).toList());
        assertEquals(500,first.size());
    }
    @Test void assemblyAndAlternateRemainDistinctAndChrPrefixIsEquivalent() {
        assertEquals(DistinctVariantSelection.identity(row("a","GRCh38","chr1",1,"G").getKey(),"a"),
                DistinctVariantSelection.identity(row("b","GRCh38","1",1,"G").getKey(),"b"));
        assertNotEquals(DistinctVariantSelection.identity(row("a","GRCh38","1",1,"G").getKey(),"a"),
                DistinctVariantSelection.identity(row("b","GRCh37","1",1,"G").getKey(),"b"));
        assertNotEquals(DistinctVariantSelection.identity(row("a","GRCh38","1",1,"G").getKey(),"a"),
                DistinctVariantSelection.identity(row("b","GRCh38","1",1,"C").getKey(),"b"));
        assertNotEquals(DistinctVariantSelection.identity(null,"a"), DistinctVariantSelection.identity(null,"b"));
    }
    @Test void groupsRetainAllInheritanceRows() {
        var a=mock(VariantInterpretation.class); var b=mock(VariantInterpretation.class);
        var observation=mock(VariantQcObservation.class);
        when(observation.getKey()).thenReturn(row("a","GRCh38","1",100,"G").getKey());
        when(a.getObservation()).thenReturn(observation); when(b.getObservation()).thenReturn(observation);
        when(a.getCandidateId()).thenReturn("AD"); when(b.getCandidateId()).thenReturn("AR");
        var groups=DistinctVariantSelection.groups(List.of(a,b));
        assertEquals(1,groups.size()); assertEquals(List.of(a,b),groups.values().iterator().next());
    }
    @Test void repeatedRowIdentityFailsRatherThanSilentlyDroppingSourceCorruption() {
        var repo=mock(VariantAnalysisJobRepository.class); var a=row("a","GRCh38","1",100,"G");
        when(repo.findRankingPage("job",0,500)).thenReturn(List.of(a,a));
        assertThrows(IllegalStateException.class,()->DistinctVariantSelection.top(repo,"job",2));
    }
}
