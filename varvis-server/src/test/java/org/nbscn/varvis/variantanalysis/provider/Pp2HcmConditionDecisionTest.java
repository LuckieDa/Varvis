package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Decision-contract controls only. In particular, no source currently issues a clear outcome here. */
class Pp2HcmConditionDecisionTest {
    @ParameterizedTest
    @CsvSource({"SUPPORTS_OTHER_PHENOTYPE,NOT_MET,PP2_HCM_OTHER_PHENOTYPE_SUPPORTED",
        "NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE,MET,PP2_HCM_NO_OTHER_PHENOTYPE_SUPPORT_IN_ASSESSED_SCOPE",
        "UNKNOWN,NOT_EVALUATED,PP2_HCM_OTHER_PHENOTYPE_REVIEW_INCOMPLETE",
        "CONFLICT,NOT_EVALUATED,PP2_HCM_OTHER_PHENOTYPE_CONFLICT",
        "INVALID,NOT_EVALUATED,PP2_HCM_OTHER_PHENOTYPE_REVIEW_INVALID"})
    void outcomesRemainDistinctWithoutInventingSourceQualification(Pp2OtherPhenotypeSource.Outcome outcome,
            String status, String reasonCode) {
        var decision = Pp2HcmConditionDecision.evaluate(outcome);
        assertEquals(status, decision.status()); assertEquals(reasonCode, decision.reasonCode());
    }
}
