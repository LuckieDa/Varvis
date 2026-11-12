package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeNovoPrerequisiteFeedbackTest {
    private static final Group GROUP = new Group("GRCh38:1:100:A:G", "HGNC:SYNTHETIC", "MONDO:SYNTHETIC", "AD");
    private static final Policy POLICY = new Policy("SYNTHETIC", true, false);
    @Test void allMissingFactsAreReportedWithoutChangingFirstFailure() {
        var f=new Facts(GROUP,TargetTest.UNKNOWN,TargetTest.UNKNOWN,TargetTest.UNKNOWN,
                Parentage.INCONCLUSIVE,Parentage.INCONCLUSIVE,Phenotype.UNKNOWN,FamilyHistory.UNKNOWN);
        assertEquals(List.of("DENOVO_PARENTAGE_INCONCLUSIVE","DENOVO_FAMILY_HISTORY_UNRESOLVED",
                "DENOVO_PROBAND_TARGET_TEST_REQUIRED","DENOVO_MATERNAL_TARGET_TEST_REQUIRED",
                "DENOVO_PATERNAL_TARGET_TEST_REQUIRED","DENOVO_PHENOTYPE_BASIS_REQUIRED"),prerequisiteFailures(f,POLICY));
        assertEquals("DENOVO_PARENTAGE_INCONCLUSIVE",prerequisite(f,POLICY));
        assertThrows(UnsupportedOperationException.class,()->prerequisiteFailures(f,POLICY).clear());
    }
    @Test void assumedBranchesRequireNoInventedConfirmationAndKeepOriginalScore() {
        for(var parent:List.of(Parentage.ASSUMED_NOT_TESTED,Parentage.ASSUMED_NOT_REPORTED,Parentage.ASSUMED_NOT_CONFIRMED)) {
            var f=new Facts(GROUP,TargetTest.PRESENT,TargetTest.ABSENT_QUALIFIED,TargetTest.ABSENT_QUALIFIED,
                    parent,parent,Phenotype.CONSISTENT,FamilyHistory.COMPATIBLE);
            assertTrue(prerequisiteFailures(f,POLICY).isEmpty());assertNull(prerequisite(f,POLICY));
            var o=new Observation("observation","event",SourceKind.LITERATURE_CASE,f,Independence.ESTABLISHED,List.of("synthetic#1"),List.of());
            var result=new DeNovoEvidenceCalculator().evaluate(GROUP,POLICY,List.of(o));
            assertEquals("PM6",result.code());assertEquals(2,result.effectiveQuarterPoints());
            assertEquals(List.of("DENOVO_ASSUMED_PARENTAGE_POLICY_REQUIRED"),prerequisiteFailures(f,new Policy("NO_ASSUMED",false,false)));
        }
    }
    @Test void knownNegativeIsNotInventedMissingInformation() {
        var f=new Facts(GROUP,TargetTest.PRESENT,TargetTest.PRESENT,TargetTest.ABSENT_QUALIFIED,
                Parentage.CONFIRMED,Parentage.CONFIRMED,Phenotype.NOT_CONSISTENT,FamilyHistory.COMPATIBLE);
        assertTrue(prerequisiteFailures(f,POLICY).isEmpty());
        var o=new Observation("observation","event",SourceKind.LITERATURE_CASE,f,Independence.ESTABLISHED,List.of("synthetic#1"),List.of());
        assertEquals("NOT_MET",new DeNovoEvidenceCalculator().evaluate(GROUP,POLICY,List.of(o)).status());
    }
    @Test void conflictQualityAndUnsupportedAreNotHiddenBehindEarlierFailure() {
        var f=new Facts(GROUP,TargetTest.QUALITY_FAILED,TargetTest.CONFLICT,TargetTest.UNKNOWN,
                Parentage.EXCLUDED,Parentage.INCONCLUSIVE,Phenotype.UNKNOWN,FamilyHistory.MOSAIC_OR_SIBLING_RECURRENCE);
        assertEquals("DENOVO_PARENTAGE_EXCLUDED",prerequisite(f,POLICY));
        assertEquals(List.of("DENOVO_PARENTAGE_EXCLUDED","DENOVO_PARENTAGE_INCONCLUSIVE",
                "DENOVO_SPECIAL_FAMILY_MODEL_NOT_SUPPORTED","DENOVO_FAMILY_HISTORY_UNRESOLVED",
                "DENOVO_TARGET_TEST_CONFLICT","DENOVO_TARGET_TEST_QUALITY_FAILED",
                "DENOVO_PATERNAL_TARGET_TEST_REQUIRED","DENOVO_PHENOTYPE_BASIS_REQUIRED"),prerequisiteFailures(f,POLICY));
    }
}
