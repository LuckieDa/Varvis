package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pm3LiteratureScoring.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class Pm3LiteratureScoringTest {
    @Test void simultaneousGapsAreAllReportedAndHomozygotesDoNotRequireAPartner() {
        var input=new Observation("1","case","target","partner",false,Phase.TRANS,Classification.UNKNOWN,
                Check.UNKNOWN,Check.UNKNOWN,Check.VERIFIED,Check.VERIFIED,Check.UNKNOWN,Check.CONFLICT,
                Check.UNKNOWN,List.of("paper:table:case"));
        var result=calculate("target",List.of(input));
        assertEquals(0,result.quarterPoints());assertEquals("CONFLICT",result.contributions().get(0).qualificationStatus());
        assertEquals(6,result.contributions().get(0).requirements().size());
        assertTrue(result.issues().contains("PM3_PARTNER_CLASSIFICATION_MISSING"));
        assertTrue(result.issues().contains("PM3_TARGET_RARITY_NOT_ESTABLISHED"));
        var homo=new Observation("h","case2","target",null,true,Phase.UNKNOWN,Classification.UNKNOWN,
                Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.UNKNOWN,Check.UNKNOWN,Check.UNKNOWN,
                Check.UNKNOWN,List.of("paper:table:case2"));
        var checked=calculate("target",List.of(homo));
        assertEquals(List.of("PM3_TARGET_RARITY_NOT_ESTABLISHED"),checked.issues());
    }
    private Observation row(String id,String occurrence,String partner,boolean homo,Phase phase,Classification classification) {
        return new Observation(id,occurrence,"target",partner,homo,phase,classification,
                Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,
                Check.VERIFIED,List.of("paper:table:case-"+occurrence));
    }
    @Test void fiveBranches() {
        assertEquals(4,calculate("target",List.of(row("1","a","b",false,Phase.TRANS,Classification.LIKELY_PATHOGENIC))).quarterPoints());
        assertEquals(2,calculate("target",List.of(row("1","a","b",false,Phase.UNKNOWN,Classification.PATHOGENIC))).quarterPoints());
        assertEquals(1,calculate("target",List.of(row("1","a","b",false,Phase.UNKNOWN,Classification.LIKELY_PATHOGENIC))).quarterPoints());
        assertEquals(1,calculate("target",List.of(row("1","a","b",false,Phase.TRANS,Classification.VUS))).quarterPoints());
        assertEquals(2,calculate("target",List.of(row("1","a",null,true,Phase.UNKNOWN,Classification.UNKNOWN))).quarterPoints());
    }
    @Test void homoAndVusCapsAndStrengths() {
        var homo=new ArrayList<Observation>();var vus=new ArrayList<Observation>();var trans=new ArrayList<Observation>();
        for(int i=0;i<5;i++) {
            homo.add(row("h"+i,"h"+i,null,true,Phase.UNKNOWN,Classification.UNKNOWN));
            vus.add(row("v"+i,"v"+i,"b",false,Phase.TRANS,Classification.VUS));
            trans.add(row("t"+i,"t"+i,"b",false,Phase.TRANS,Classification.PATHOGENIC));
        }
        assertEquals(4,calculate("target",homo).quarterPoints());
        assertEquals(2,calculate("target",vus).quarterPoints());
        assertEquals("VERY_STRONG",calculate("target",trans).strength());
        assertEquals("STRONG",calculate("target",trans.subList(0,2)).strength());
    }
    @Test void unknownPhaseNeedsDistinctPartnersForFullPoint() {
        var a=row("1","a","b",false,Phase.UNKNOWN,Classification.PATHOGENIC);
        var b=row("2","b","b",false,Phase.UNKNOWN,Classification.PATHOGENIC);
        assertEquals(2,calculate("target",List.of(a,b)).quarterPoints());
        b=row("2","b","c",false,Phase.UNKNOWN,Classification.PATHOGENIC);
        assertEquals(4,calculate("target",List.of(a,b)).quarterPoints());
    }
    @Test void repeatedPublicationDoesNotAddPointsAndConflictIsNotChosen() {
        var a=row("1","case1","partner",false,Phase.TRANS,Classification.PATHOGENIC);
        var b=row("2","case1","partner",false,Phase.TRANS,Classification.PATHOGENIC);
        assertEquals(4,calculate("target",List.of(a,b)).quarterPoints());
        b=row("2","case1","partner",false,Phase.CIS,Classification.PATHOGENIC);
        var result=calculate("target",List.of(a,b));
        assertEquals(0,result.quarterPoints()); assertTrue(result.issues().contains("PM3_OCCURRENCE_CONFLICT"));
    }

    @Test void independentTransCaseCountsOnceWithCisAndUnresolvedCasesInTheSameBatch() {
        var trans = row("1", "trans-case", "partner", false, Phase.TRANS, Classification.PATHOGENIC);
        var duplicate = row("2", "trans-case", "partner", false, Phase.TRANS, Classification.PATHOGENIC);
        var cis = row("3", "cis-case", "partner", false, Phase.CIS, Classification.PATHOGENIC);
        var unresolved = row("4", "unresolved-case", "other-partner", false, Phase.TRANS, Classification.UNKNOWN);

        var result = calculate("target", List.of(trans, duplicate, cis, unresolved));

        assertEquals("MET", result.status());
        assertEquals("MODERATE", result.strength());
        assertEquals(4, result.quarterPoints());
        assertEquals(List.of(4, 0, 0, 0), result.contributions().stream().map(c -> Objects.requireNonNull(c).awardedQuarterPoints()).toList());
        assertEquals(List.of("PM3_OBSERVATION_COUNTED", "PM3_DUPLICATE_OCCURRENCE", "PM3_CIS_NOT_APPLICABLE",
                "PM3_PARTNER_CLASSIFICATION_MISSING"), result.contributions().stream().map(c -> Objects.requireNonNull(c).reason()).toList());
    }
    @Test void incompleteQualificationCanBeCompletedWithoutDoubleCountingOrHidingKnownContradictions() {
        var complete=row("2","same-case","partner",false,Phase.TRANS,Classification.PATHOGENIC);
        var partial=new Observation("1","same-case","target","partner",false,Phase.TRANS,Classification.PATHOGENIC,
                Check.UNKNOWN,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,List.of("original-source"));
        assertEquals(4,calculate("target",List.of(partial,complete)).quarterPoints());
        var falseContext=new Observation("3","same-case","target","partner",false,Phase.TRANS,Classification.PATHOGENIC,
                Check.FAILED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,List.of("contradictory-source"));
        var rows=new ArrayList<>(List.of(partial,complete,falseContext));
        for(int i=0;i<3;i++) {
            java.util.Collections.rotate(rows,1);var result=calculate("target",rows);
            assertEquals(0,result.quarterPoints());assertTrue(result.issues().contains("PM3_OCCURRENCE_CONFLICT"));
        }
    }
    @Test void absentRarityOrClassificationCannotBeConvertedToZeroNegative() {
        var a=row("1","case1","partner",false,Phase.TRANS,Classification.PATHOGENIC);
        var unknown=new Observation(a.id(),a.independentOccurrenceId(),a.targetAlleleId(),a.partnerAlleleId(),false,a.phase(),
                a.partnerClassification(),a.caseIdentity(),a.affectedRecessiveContext(),a.targetIdentity(),a.partnerIdentity(),
                Check.UNKNOWN,a.partnerRare(),a.independentClassification(),a.sourceReferences());
        assertEquals("NOT_EVALUATED",calculate("target",List.of(unknown)).status());
        assertEquals("NOT_EVALUATED",calculate("target",List.of()).status());
        assertEquals("NOT_MET",calculate("target",List.of(row("1","a","b",false,Phase.UNKNOWN,Classification.VUS))).status());
    }
    @Test void permutationsDoNotChangeResultAndDuplicateIdsReject() {
        var a=row("a","case1","b",false,Phase.TRANS,Classification.PATHOGENIC);
        var b=row("b","case2","c",false,Phase.TRANS,Classification.VUS);
        assertEquals(calculate("target",List.of(a,b)),calculate("target",List.of(b,a)));
        assertThrows(IllegalArgumentException.class,()->calculate("target",List.of(a,a)));
    }
    @Test void everyUnqualifiedPrerequisitePreventsScoring() {
        for(int field=0;field<7;field++) for(Check state:List.of(Check.UNKNOWN,Check.CONFLICT,Check.FAILED)) {
            Check[] checks={Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,
                    Check.VERIFIED,Check.VERIFIED,Check.VERIFIED};checks[field]=state;
            var o=new Observation("x","case","target","partner",false,Phase.TRANS,Classification.PATHOGENIC,
                    checks[0],checks[1],checks[2],checks[3],checks[4],checks[5],checks[6],List.of("source"));
            assertEquals(0,calculate("target",List.of(o)).quarterPoints(),field+":"+state);
        }
    }
    @Test void cisIsNotMissingData() {
        var cis=new Observation("a","case","target","partner",false,Phase.CIS,Classification.UNKNOWN,
                Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,Check.VERIFIED,
                Check.UNKNOWN,Check.UNKNOWN,Check.UNKNOWN,List.of("source"));
        var result=calculate("target",List.of(cis));
        assertEquals("NOT_APPLICABLE",result.status());
        assertEquals(0,result.quarterPoints());
        assertEquals("NOT_APPLICABLE",result.contributions().get(0).qualificationStatus());
        assertEquals(List.of(new Requirement("PM3_CIS_NOT_APPLICABLE","NOT_APPLICABLE")),
                result.contributions().get(0).requirements());
    }
}
