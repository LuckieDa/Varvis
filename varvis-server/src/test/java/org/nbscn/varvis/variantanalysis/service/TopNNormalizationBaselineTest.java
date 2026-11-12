package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;

/** The old API remains deliberately bounded for all callers except the new BA1 path. */
class TopNNormalizationBaselineTest {
    @TempDir Path temporary;
    @Test void frozenOldRejections() throws Exception {
        var f = new ExplicitCaseInputCoordinatorTest(); f.temporary = temporary; f.setup();
        var producer = f.tinyReference(r -> {}).producer();
        var output = new StringBuilder();
        for (String[] a : new String[][]{{"TC", "TA"}, {"T", "TT"}}) {
            var run = ParsedCandidateFixture.run(temporary.resolve(a[1]), "normalization-baseline-" + a[1], GenomeAssembly.GRCh38,
                    vcf("chr11\t2\t.\t" + a[0] + "\t" + a[1] + "\t.\tPASS\t.\tGT\t0|1"),
                    tsv("1\t11\t2\t" + a[0] + "\t" + a[1] + "\tHBB\t3043\tAR\t0.8"));
            var analysis = new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES);
            var result = producer.produceAllele(run.job(), run.conversion(), run.execution(), analysis,
                    run.execution().getInterpretations().get(0), "SYNTHETIC");
            assertEquals("SMALL_VARIANT_NORMALIZATION_REQUIRED", result.blockers(result.inputs().get(0)).get(0).reasonCode());
            output.append(a[0]).append('>').append(a[1]).append(" SMALL_VARIANT_NORMALIZATION_REQUIRED\n");
        }
        String evidence = System.getProperty("varvis.n01.evidence");
        if (evidence != null) Files.writeString(Path.of(evidence).resolve("old-chain-rejections.txt"), output);
    }
}
