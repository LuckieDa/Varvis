package org.nbscn.varvis.variantanalysis.trio;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;

import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestBackedTrioPipelineInputProviderTest {

    @Test
    void registeredInputIsReturnedUntilTheJobReleasesIt() {
        RequestBackedTrioPipelineInputProvider provider = new RequestBackedTrioPipelineInputProvider();
        VariantAnalysisJob job = new VariantAnalysisJob(
                "job-1",
                "joint.vcf",
                "sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "proband",
                Paths.get("workspace"),
                Paths.get("workspace").resolve("joint.vcf")
        );
        TrioPipelineInput input = TrioPipelineInput.jointVcf(
                Paths.get("joint.vcf"),
                new TrioVcfSampleMapping("proband", "father", "mother"),
                Collections.<TrioMember>emptyList()
        );

        provider.register(job.getId(), input);

        assertTrue(provider.inputFor(job).isPresent());
        assertTrue(provider.inputFor(job).isPresent());
        provider.release(job);
        assertFalse(provider.inputFor(job).isPresent());
    }
}
