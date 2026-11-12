package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizationResourceValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesIndependentGrch37AndGrch38Profiles() throws Exception {
        Path grch37 = reference("grch37.fa", "1", 249250621L);
        Path grch38 = reference("grch38.fa", "chr1", 248956422L);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setGrch37Reference(grch37.toString());
        properties.getNormalization().setGrch38Reference(grch38.toString());
        properties.getNormalization().setGrch37ProfileId("test-grch37");
        properties.getNormalization().setGrch38ProfileId("test-grch38");

        NormalizationResourceValidator validator = new NormalizationResourceValidator(properties);
        NormalizationReferenceReadiness grch37Report = validator.validate(GenomeAssembly.GRCh37);
        NormalizationReferenceReadiness grch38Report = validator.validate(GenomeAssembly.GRCh38);

        assertEquals("READY", grch37Report.getStatus());
        assertEquals("NO_CHR_PREFIX", grch37Report.getContigStyle());
        assertEquals("grch37.fa", grch37Report.getReferenceFileName());
        assertEquals("READY", grch38Report.getStatus());
        assertEquals("CHR_PREFIXED", grch38Report.getContigStyle());
        assertEquals("grch38.fa", grch38Report.getReferenceFileName());
        assertTrue(grch37Report.getWarnings().toString().contains("SHA-256"));
    }

    @Test
    void rejectsCrossAssemblyReferenceWithoutFallback() throws Exception {
        Path grch38 = reference("wrong-for-grch37.fa", "chr1", 248956422L);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getNormalization().setGrch37Reference(grch38.toString());

        NormalizationReferenceReadiness report =
                new NormalizationResourceValidator(properties).validate(GenomeAssembly.GRCh37);

        assertEquals("ASSEMBLY_UNSUPPORTED", report.getStatus());
        assertFalse(report.isReady());
        assertTrue(report.getWarnings().toString().contains("Chromosome 1 length"));
    }

    @Test
    void missingAssemblyProfileIsResourceUnavailable() {
        NormalizationReferenceReadiness report = new NormalizationResourceValidator(
                new VariantAnalysisProperties()).validate(GenomeAssembly.GRCh37);

        assertEquals("RESOURCE_UNAVAILABLE", report.getStatus());
        assertTrue(report.getMissingResources().contains("normalization.GRCh37.reference"));
    }

    private Path reference(String name, String chromosome, long length) throws Exception {
        Path fasta = tempDir.resolve(name);
        Files.write(fasta, (">" + chromosome + "\nA\n").getBytes(StandardCharsets.UTF_8));
        Files.write(
                tempDir.resolve(name + ".fai"),
                (chromosome + "\t" + length + "\t3\t1\t2\n").getBytes(StandardCharsets.UTF_8)
        );
        return fasta;
    }
}
