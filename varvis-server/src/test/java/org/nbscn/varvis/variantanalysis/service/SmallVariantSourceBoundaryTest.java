package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SmallVariantSourceBoundaryTest {
    @ParameterizedTest
    @CsvSource({"A,T,true", "A,AT,true", "AT,A,true", "AC,TG,true",
            "A,AA,false", "AT,T,false", "AC,AT,false", "AT,GT,false",
            "N,A,false", "A,<DEL>,false", "A,A,false"})
    void acceptsOnlySmallAllelesWhoseMinimalRepresentationIsEstablished(String ref, String alt, boolean expected) {
        assertEquals(expected, NativeSnvSourceProducer.isProvablyMinimal(ref, alt));
    }
}
