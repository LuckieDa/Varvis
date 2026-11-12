package org.nbscn.varvis.variantanalysis.persistence;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateIdentityRepositoryTest {

    @Test
    void exactCandidateLookupSeparatesDuplicateCanonicalRows() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantInterpretation dominant = candidate(1, "AUTOSOMAL_DOMINANT");
        VariantInterpretation recessive = candidate(2, "AUTOSOMAL_RECESSIVE");
        repository.replaceInterpretations("analysis", Arrays.asList(dominant, recessive));

        assertEquals(dominant, repository.findCandidate(
                "analysis", "exomiser-v1-00000001").orElseThrow(AssertionError::new));
        assertEquals(recessive, repository.findCandidate(
                "analysis", "exomiser-v1-00000002").orElseThrow(AssertionError::new));
        assertEquals(2, repository.findInterpretationsByVariantId(
                "analysis", "GRCh38|1|100|A|T").size());
        assertThrows(
                AmbiguousVariantIdException.class,
                () -> repository.findInterpretation("analysis", "GRCh38|1|100|A|T")
        );
    }

    @Test
    void transcriptAnnotationsUpdateEveryCandidateWithoutChangingIdentity() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantInterpretation dominant = candidate(1, "AUTOSOMAL_DOMINANT");
        VariantInterpretation recessive = candidate(2, "AUTOSOMAL_RECESSIVE");
        repository.replaceInterpretations("analysis", Arrays.asList(dominant, recessive));
        TranscriptConsequence annotation = new TranscriptConsequence(
                "GENE1", null, "NM_SYNTHETIC.1", null, null, "missense_variant"
        );
        Map<String, List<TranscriptConsequence>> annotations =
                new LinkedHashMap<String, List<TranscriptConsequence>>();
        annotations.put("GRCh38|1|100|A|T", Collections.singletonList(annotation));

        repository.updateTranscriptAnnotations("analysis", annotations);

        VariantInterpretation restoredDominant = repository.findCandidate(
                "analysis", "exomiser-v1-00000001").orElseThrow(AssertionError::new);
        VariantInterpretation restoredRecessive = repository.findCandidate(
                "analysis", "exomiser-v1-00000002").orElseThrow(AssertionError::new);
        assertEquals(1, restoredDominant.getTranscriptAnnotations().size());
        assertEquals(1, restoredRecessive.getTranscriptAnnotations().size());
        assertEquals("AUTOSOMAL_DOMINANT",
                restoredDominant.getCandidateIdentity().getModeOfInheritance());
        assertEquals("AUTOSOMAL_RECESSIVE",
                restoredRecessive.getCandidateIdentity().getModeOfInheritance());
    }

    @Test
    void duplicateLegacyRowsReceiveShortPersistedRowIds() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantQcObservation observation = observation();
        VariantInterpretation first = new VariantInterpretation(
                "legacy-variant", observation, Collections.emptyList(), Collections.emptyList());
        VariantInterpretation second = new VariantInterpretation(
                "legacy-variant", observation, Collections.emptyList(), Collections.emptyList());

        repository.replaceInterpretations("analysis", Arrays.asList(first, second));
        List<VariantInterpretation> restored = repository.findInterpretations("analysis", 0, 10);

        assertEquals(2, restored.size());
        assertNotEquals(restored.get(0).getCandidateId(), restored.get(1).getCandidateId());
        assertTrue(restored.get(0).getCandidateId().matches("legacy-v1-row-[0-9]{10}"));
        assertTrue(restored.get(1).getCandidateId().matches("legacy-v1-row-[0-9]{10}"));
        assertThrows(
                AmbiguousVariantIdException.class,
                () -> repository.findInterpretation("analysis", "legacy-variant")
        );
    }

    private VariantInterpretation candidate(int ordinal, String moi) {
        return new VariantInterpretation(
                CandidateIdentity.exomiser(
                        "GRCh38|1|100|A|T", ordinal, "GENE1", moi, ordinal
                ),
                observation(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private VariantQcObservation observation() {
        return new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "T"),
                "sample",
                "0/1",
                30,
                99,
                "15,15",
                0.5d,
                "PASS",
                "SNV",
                null
        );
    }
}
