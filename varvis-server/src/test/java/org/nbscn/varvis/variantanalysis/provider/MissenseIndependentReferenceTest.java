package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MissenseIndependentReferenceTest {
    @ParameterizedTest @CsvSource({
            "2,0,0,PATHOGENIC", "1,3,0,PATHOGENIC", "1,2,2,PATHOGENIC", "1,1,4,PATHOGENIC",
            "1,1,0,LIKELY_PATHOGENIC", "1,0,2,LIKELY_PATHOGENIC", "0,3,0,LIKELY_PATHOGENIC",
            "0,2,2,LIKELY_PATHOGENIC", "0,1,4,LIKELY_PATHOGENIC", "2,3,4,PATHOGENIC",
            "1,0,1,", "0,2,1,", "0,1,3,", "0,0,4,", "0,0,0,"})
    void independentNonLofCombinationBoundaries(int strong, int moderate, int supporting, String expected) {
        assertEquals(expected, MissenseIndependentReference.combine(strong, moderate, supporting));
    }
}
