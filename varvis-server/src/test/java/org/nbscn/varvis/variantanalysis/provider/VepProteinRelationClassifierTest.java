package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VepProteinRelationClassifierTest {

    private final VepProteinRelationClassifier classifier = new VepProteinRelationClassifier();

    @Test
    void classifiesTheFourProteinRelationsAndPreservesExactTuples() {
        TranscriptConsequence target = consequence("ENST00000646891.2", "600", "V/E");

        assertEquals(VepProteinRelationClassifier.Relation.SAME_AMINO_ACID_CHANGE,
                classifier.classify(target,
                        consequence("ENST00000646891.2", "600", "V/E")).getRelation());
        assertEquals(VepProteinRelationClassifier.Relation.SAME_RESIDUE_DIFFERENT_AMINO_ACID,
                classifier.classify(target,
                        consequence("ENST00000646891.2", "600", "V/G")).getRelation());
        assertEquals(VepProteinRelationClassifier.Relation.DIFFERENT_TRANSCRIPT,
                classifier.classify(target,
                        consequence("ENST00000288602.12", "600", "V/E")).getRelation());
        assertEquals(VepProteinRelationClassifier.Relation.DIFFERENT_PROTEIN_POSITION,
                classifier.classify(target,
                        consequence("ENST00000646891.2", "601", "V/E")).getRelation());

        VepProteinRelationClassifier.Result result = classifier.classify(
                target, consequence("ENST00000646891.2", "600", "V/G"));
        assertTrue(result.isEvaluated());
        assertEquals("ENST00000646891.2", result.getTarget().getTranscriptId());
        assertEquals("600", result.getComparator().getProteinPosition());
        assertEquals("V", result.getComparator().getReferenceAminoAcid());
        assertEquals("G", result.getComparator().getAlternateAminoAcid());
    }

    @Test
    void incompleteOrInconsistentProteinTupleIsNotEvaluated() {
        TranscriptConsequence target = consequence("ENST00000646891.2", "600", "V/E");
        VepProteinRelationClassifier.Result incomplete = classifier.classify(
                target, consequence("ENST00000646891.2", null, "V/G"));
        assertFalse(incomplete.isEvaluated());
        assertEquals("PROTEIN_RELATION_CONTEXT_INCOMPLETE", incomplete.getReasonCode());

        VepProteinRelationClassifier.Result inconsistent = classifier.classify(
                target, consequence("ENST00000646891.2", "600", "L/G"));
        assertFalse(inconsistent.isEvaluated());
        assertEquals("REFERENCE_AMINO_ACID_MISMATCH", inconsistent.getReasonCode());
    }

    private TranscriptConsequence consequence(String transcript,
                                               String proteinPosition,
                                               String aminoAcids) {
        return TranscriptConsequence.ann(
                "BRAF",
                "ENSG00000157764",
                transcript,
                "Ensembl",
                "missense_variant",
                "MODERATE",
                transcript + ":c.1799T>A",
                "ENSP00000493543.1:p.Val600Glu",
                "15/18",
                null,
                proteinPosition,
                aminoAcids,
                "gTg/gAg",
                Instant.EPOCH);
    }
}
