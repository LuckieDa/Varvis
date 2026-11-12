package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture;

/** The typed raw witness is verified input, never a replacement context or JSON permission. */
class T04TypedRankingSessionTest {
    @TempDir Path temporary;
    @Test void originalRawWitnessPassesButMissingExtraAndChangedRankedInputsCannotIssue() throws Exception {
        var json=new ObjectMapper();
        try(var f=new T04CurrentSessionFixture(temporary,"typed-ranking-analysis","typed-ranking-run",List.of("ACMG_AMP_2015:PP1"))) {
            var baseline=f.issue(T04NativeOriginalFixture.context(f));
            var run=f.store().run(f.analysisId(),f.runId()).orElseThrow();
            var actual=CurrentAcmgEvaluationSession.issueAfterClaim(json,f.store(),f.analysisId(),f.runId(),run.requestSha256(),f.prepared(),f.ranked());
            assertEquals(baseline.attempts(),actual.attempts());
            assertEquals(baseline.identity().path("selectedBindingsSha256"),actual.identity().path("selectedBindingsSha256"));
            assertNotEquals(baseline.identity().path("evaluationId"),actual.identity().path("evaluationId"));
            var candidate=f.ranked().get(0);
            for(var wrong:List.of(List.<org.nbscn.varvis.variantanalysis.domain.VariantInterpretation>of(),
                    List.of(candidate,candidate),List.of(candidate.withAnalysisVcfCall(null)))) {
                var failure=assertThrows(IllegalArgumentException.class,()->CurrentAcmgEvaluationSession.issueAfterClaim(
                        json,f.store(),f.analysisId(),f.runId(),run.requestSha256(),f.prepared(),wrong));
                assertEquals("CURRENT_SESSION_SELECTED_RANKING_MISMATCH",failure.getMessage());
            }
        }
    }
}
