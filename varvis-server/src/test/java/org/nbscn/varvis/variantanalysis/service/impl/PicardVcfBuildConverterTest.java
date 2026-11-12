package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PicardVcfBuildConverterTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsInputWhenAlreadyGrch38() throws IOException {
        Path input = Files.createFile(tempDir.resolve("input.vcf"));
        PicardVcfBuildConverter converter = new PicardVcfBuildConverter(new VariantAnalysisProperties());

        BuildConversionResult result = converter.convert(
                input,
                tempDir,
                GenomeAssembly.GRCh38,
                GenomeAssembly.GRCh38
        );

        assertSame(input, result.getOutputFile());
        assertFalse(result.isConversionApplied());
        assertEquals("NONE", result.getEngine());
        assertEquals(0L, result.getSourceRecordCount());
        assertEquals(0L, result.getAnalysisRecordCount());
    }

    @Test
    void rejectsGrch37WhenLiftoverIsNotConfigured() throws IOException {
        Path input = Files.createFile(tempDir.resolve("input.vcf"));
        PicardVcfBuildConverter converter = new PicardVcfBuildConverter(new VariantAnalysisProperties());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> converter.convert(input, tempDir, GenomeAssembly.GRCh37, GenomeAssembly.GRCh38)
        );

        assertTrue(exception.getMessage().contains("GRCh37/hg19 input requires liftOver"));
    }

    @Test
    void createsPrivateUCSCContigCopyForNumericGrch37InputWhenChainRequiresIt() throws IOException {
        Path input = tempDir.resolve("input.vcf");
        Files.write(input, Arrays.asList(
                "##fileformat=VCFv4.2",
                "##contig=<ID=7,length=159345973>",
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO",
                "7\t140453136\t.\tA\tT\t60\tPASS\t."
        ));
        Path chain = tempDir.resolve("hg19ToHg38.over.chain");
        Files.write(chain, Arrays.asList("chain 1 chr7 1 + 0 1 chr7 1 + 0 1 1", "1"));

        Path adapted = new PicardVcfBuildConverter(new VariantAnalysisProperties())
                .adaptInputContigsForChain(input, tempDir, chain);

        assertFalse(input.equals(adapted));
        assertEquals(Arrays.asList(
                "##fileformat=VCFv4.2",
                "##contig=<ID=chr7,length=159345973>",
                "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO",
                "chr7\t140453136\t.\tA\tT\t60\tPASS\t."
        ), Files.readAllLines(adapted));
        assertEquals("7\t140453136\t.\tA\tT\t60\tPASS\t.", Files.readAllLines(input).get(3));
    }
}
