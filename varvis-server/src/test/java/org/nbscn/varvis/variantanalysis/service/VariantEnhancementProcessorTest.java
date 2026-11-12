package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.provider.AlphaGenomeProvider;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantEnhancementProcessorTest {

    @Test
    void sendsOnlyCaddPrescreenedEligibleCandidatesToAlphaGenome() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getAlphaGenome().setEnabled(true);
        AtomicInteger calls = new AtomicInteger();
        AlphaGenomeProvider provider = new AlphaGenomeProvider(properties, key -> {
            calls.incrementAndGet();
            return Collections.<String, Object>singletonMap("strongestEffect", "RNA_SEQ decrease");
        });
        InMemoryVariantAnalysisJobRepository jobs = new InMemoryVariantAnalysisJobRepository();
        InMemoryVariantEnhancementRepository enhancements = new InMemoryVariantEnhancementRepository();
        List<VariantInterpretation> interpretations = Arrays.asList(
                variant("eligible", 30.0d, "intron_variant", cadd()),
                variant("coding", 40.0d, "missense_variant", cadd()),
                variant("no-cadd", 20.0d, "intergenic_variant")
        );
        jobs.replaceInterpretations("analysis", interpretations);
        VariantEnhancementTask task = new VariantEnhancementTask("enhancement", "analysis", "ALPHAGENOME", 20);
        enhancements.save(task);

        new VariantEnhancementProcessor(
                jobs,
                enhancements,
                provider,
                new TransparentVariantRankingService(properties)
        ).process(task.getId());

        assertEquals(VariantEnhancementStatus.READY, task.getStatus());
        assertEquals(1, task.getSelectedCandidateCount());
        assertEquals(1, task.getCompletedCount());
        assertEquals(0, task.getUnavailableCount());
        assertEquals(1, calls.get());
        assertTrue(jobs.findInterpretation("analysis", "eligible").orElseThrow(AssertionError::new)
                .getEvidence().stream().anyMatch(value -> "ALPHAGENOME".equals(value.getSource())));
    }

    @Test
    void recoveryMarksInterruptedEnhancementAsFailed() {
        InMemoryVariantEnhancementRepository enhancements = new InMemoryVariantEnhancementRepository();
        VariantEnhancementTask task = new VariantEnhancementTask("enhancement", "analysis", "ALPHAGENOME", 20);
        enhancements.save(task);

        assertEquals(1, enhancements.failInterruptedTasks());
        assertEquals(VariantEnhancementStatus.FAILED, task.getStatus());
    }

    private VariantInterpretation variant(String id,
                                          double rankingScore,
                                          String consequence,
                                          EvidenceItem... evidence) {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", id.hashCode(), "A", "G");
        VariantInterpretation variant = new VariantInterpretation(
                id,
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "chr1|" + id + "|A|G|1"
                ),
                Collections.singletonList(new TranscriptConsequence(null, null, null, null, null, consequence)),
                Arrays.asList(evidence)
        );
        variant.applyRanking("HIGH_PRIORITY", rankingScore, Collections.emptyList(), Collections.emptyList());
        return variant;
    }

    private EvidenceItem cadd() {
        return new EvidenceItem(
                "CADD",
                "v1.7",
                "COMPUTATIONAL",
                "APPLICABLE",
                20.0d,
                "CADD PHRED",
                "test evidence",
                Collections.emptyMap()
        );
    }
}
