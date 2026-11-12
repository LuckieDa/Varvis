package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationAlleleMapping;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VcfQcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VcfQcServiceImplTest {

    private final VcfQcServiceImpl service = new VcfQcServiceImpl();

    @TempDir
    Path tempDir;

    @Test
    void extractsQcAndSplitsMultiAllelicSites() throws IOException {
        Path vcf = write(
                "single.vcf",
                "##fileformat=VCFv4.2\n"
                        + "##FILTER=<ID=q10,Description=\"Low quality\">\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Depth\">\n"
                        + "##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Genotype quality\">\n"
                        + "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Allele depths\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\t.\tA\tG,T\t.\tPASS\t.\tGT:DP:GQ:AD\t1/2:30:90:10,12,8\n"
                        + "chr2\t200\t.\tAT\tA\t.\tq10\t.\tGT:DP:GQ:AD\t0/1:20:40:8,12\n"
        );

        VcfQcResult result = service.extract(vcf, GenomeAssembly.GRCh38, null);

        assertEquals("proband", result.getSelectedSampleId());
        assertEquals(2, result.getSummary().getInputSiteCount());
        assertEquals(3, result.getSummary().getVariantCount());
        assertEquals(1, result.getSummary().getMultiAllelicSiteCount());
        assertEquals(2, result.getSummary().getSnvCount());
        assertEquals(1, result.getSummary().getIndelCount());
        assertEquals(2, result.getSummary().getPassCount());
        assertEquals(1, result.getSummary().getFilteredCount());
        VariantQcObservation first = result.getObservations().get(0);
        assertEquals("1", first.getKey().getChromosome());
        assertEquals("1/2", first.getGenotype());
        assertEquals("10,12,8", first.getAlleleDepths());
        assertEquals(12.0 / 22.0, first.getAlleleBalance(), 0.0001);
        assertEquals("chr1|100|A|G,T|1", first.getOriginalRecord());
        NormalizationAlleleMapping firstMapping = first.normalizationMapping();
        NormalizationAlleleMapping secondMapping = result.getObservations().get(1).normalizationMapping();
        assertEquals("chr1|100|A|G,T", firstMapping.getRawRecordId());
        assertEquals(firstMapping.getRawRecordId(), secondMapping.getRawRecordId());
        assertEquals(Integer.valueOf(1), firstMapping.getRawAlternateIndex());
        assertEquals(Integer.valueOf(2), secondMapping.getRawAlternateIndex());
        assertEquals("GRCh38|1|100|A|G", firstMapping.getCanonicalVariantId());
        assertEquals(NormalizationAlleleMapping.IDENTITY, firstMapping.getStatus());
    }

    @Test
    void rejectsMultiSampleVcfWithoutExplicitSelection() throws IOException {
        Path vcf = write(
                "multiple.vcf",
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\tsibling\n"
                        + "1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t0/1\t0/0\n"
        );

        InvalidVcfUploadException exception = assertThrows(
                InvalidVcfUploadException.class,
                () -> service.extract(vcf, GenomeAssembly.GRCh38, null)
        );
        assertTrue(exception.getMessage().contains("proband"));
        assertTrue(exception.getMessage().contains("sibling"));
        assertEquals("sibling", service.extract(vcf, GenomeAssembly.GRCh38, "sibling").getSelectedSampleId());
    }

    @Test
    void preservesBcftoolsOriginalRecordMappingWithMultipleOriginalAlternates() throws IOException {
        Path vcf = write(
                "bcftools-normalized.vcf",
                "##fileformat=VCFv4.2\n"
                        + "##INFO=<ID=VARVIS_ORIGINAL_VARIANT,Number=.,Type=String,Description=\"Original variant\">\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\t.\tA\tG\t.\tPASS\tVARVIS_ORIGINAL_VARIANT=chr1|100|A|G,T|1\tGT\t0/1\n"
        );

        VcfQcResult result = service.extract(vcf, GenomeAssembly.GRCh38, null);

        assertEquals("chr1|100|A|G,T|1", result.getObservations().get(0).getOriginalRecord());
    }

    @Test
    void storesAlleleIndexGenotypeForLongAlleles() throws IOException {
        Path vcf = write(
                "long-allele.vcf",
                "##fileformat=VCFv4.2\n"
                        + "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                        + "chr1\t100\t.\tA\t"
                        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                        + "\t.\tPASS\t.\tGT\t0/1\n"
        );

        VcfQcResult result = service.extract(vcf, GenomeAssembly.GRCh38, null);

        assertEquals("0/1", result.getObservations().get(0).getGenotype());
    }

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
