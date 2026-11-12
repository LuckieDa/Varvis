package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ClinVarAssertionDateTest {
    @ParameterizedTest @CsvSource(delimiter='|',value={"May 28, 2019|2019-05-28","Jan 02, 2026|2026-01-02","Feb 29, 2024|2024-02-29","2026-01-02|2026-01-02"})
    void exactFormatsNormalize(String input,String expected){assertEquals(expected,ClinVarAssertionDate.iso(input));}
    @ParameterizedTest @ValueSource(strings={"Feb 29, 2025","Feb 30, 2024","Apr 31, 2026","2026-02-30","2026-1-02","01/02/2026","May 28, 2019 extra",""})
    void invalidOrAmbiguousDatesAreNeverRepaired(String input){assertEquals("",ClinVarAssertionDate.iso(input));}
}
