package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VcfInfoBasicVariantAnnotatorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsTranscriptClinvarAndPopulationEvidenceFromInfoFields() throws IOException {
        Path vcf = tempDir.resolve("annotated.vcf");
        Files.write(
                vcf,
                ("##fileformat=VCFv4.2\n"
                        + "##INFO=<ID=ANN,Number=.,Type=String,Description=\"Annotation\">\n"
                        + "##INFO=<ID=CLNSIG,Number=.,Type=String,Description=\"ClinVar significance\">\n"
                        + "##INFO=<ID=CLNREVSTAT,Number=.,Type=String,Description=\"ClinVar review\">\n"
                        + "##INFO=<ID=gnomAD_AF,Number=A,Type=Float,Description=\"gnomAD allele frequency\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr17\t43071077\trs1234\tA\tG\t.\tPASS\t"
                        + "ANN=G|missense_variant|MODERATE|BRCA1|672|transcript|NM_007294.4|protein_coding|"
                        + "1/20|c.100A>G|p.Lys34Arg;"
                        + "CLNSIG=Likely_pathogenic;CLNREVSTAT=criteria_provided;gnomAD_AF=0.00001\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        VariantQcObservation observation = observation();

        VariantInterpretation interpretation = new VcfInfoBasicVariantAnnotator()
                .annotate(vcf, Collections.singletonList(observation))
                .get(0);

        assertEquals("BRCA1", interpretation.getConsequences().get(0).getGeneSymbol());
        assertEquals("NM_007294.4", interpretation.getConsequences().get(0).getTranscriptAccession());
        assertEquals("MODERATE", interpretation.getConsequences().get(0).getImpact());
        assertEquals("ANN", interpretation.getConsequences().get(0).getSource());
        assertEquals("COMPLETED", interpretation.getConsequences().get(0).getStatus());
        assertEquals("Likely_pathogenic", evidence(interpretation, "CLINVAR").getLabel());
        assertEquals("VCF_INFO_CLINVAR", evidence(interpretation, "CLINVAR").getDetails().get("source"));
        assertEquals(0.00001d, evidence(interpretation, "GNOMAD").getRawScore());
        assertEquals("VCF_INFO_GNOMAD", evidence(interpretation, "GNOMAD").getDetails().get("source"));
        assertEquals("rs1234", evidence(interpretation, "VCF_IDENTIFIER").getDetails().get("rsId"));
    }

    @Test
    void extractsAlleleMatchedSpliceAiFromPipeInfoField() throws IOException {
        Path vcf = tempDir.resolve("spliceai-info.vcf");
        Files.write(
                vcf,
                ("##fileformat=VCFv4.2\n"
                        + "##INFO=<ID=ANN,Number=.,Type=String,Description=\"Annotation\">\n"
                        + "##INFO=<ID=SpliceAI,Number=.,Type=String,Description=\"SpliceAI\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr17\t43071077\t.\tA\tG,T\t.\tPASS\t"
                        + "ANN=T|splice_donor_variant|HIGH|BRCA1|672|transcript|NM_007294.4|protein_coding|"
                        + "1/20|c.100A>T|p.?;"
                        + "SpliceAI=G|GENE1|0.90|0.01|0.02|0.03|5|6|7|8,"
                        + "T|GENE2|0.01|0.02|0.03|0.85|1|2|3|-4\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        VariantQcObservation observation = observation("T");

        VariantInterpretation interpretation = new VcfInfoBasicVariantAnnotator()
                .annotate(vcf, Collections.singletonList(observation))
                .get(0);

        EvidenceItem spliceAi = evidence(interpretation, "SPLICEAI");
        assertEquals("APPLICABLE", spliceAi.getApplicability());
        assertEquals(0.85d, spliceAi.getRawScore());
        assertEquals("VCF_INFO_SPLICEAI", spliceAi.getDetails().get("source"));
        assertEquals("donor_loss", spliceAi.getDetails().get("maxEvent"));
        assertEquals("-4", spliceAi.getDetails().get("maxDeltaPosition"));
        assertEquals("GENE2", spliceAi.getDetails().get("gene"));
    }

    @Test
    void extractsSpliceAiFromSeparateDeltaScoreFields() throws IOException {
        Path vcf = tempDir.resolve("spliceai-ds-fields.vcf");
        Files.write(
                vcf,
                ("##fileformat=VCFv4.2\n"
                        + "##INFO=<ID=ANN,Number=.,Type=String,Description=\"Annotation\">\n"
                        + "##INFO=<ID=DS_AG,Number=A,Type=Float,Description=\"SpliceAI AG\">\n"
                        + "##INFO=<ID=DS_AL,Number=A,Type=Float,Description=\"SpliceAI AL\">\n"
                        + "##INFO=<ID=DS_DG,Number=A,Type=Float,Description=\"SpliceAI DG\">\n"
                        + "##INFO=<ID=DS_DL,Number=A,Type=Float,Description=\"SpliceAI DL\">\n"
                        + "##INFO=<ID=DP_AG,Number=A,Type=Integer,Description=\"SpliceAI AG position\">\n"
                        + "##INFO=<ID=DP_AL,Number=A,Type=Integer,Description=\"SpliceAI AL position\">\n"
                        + "##INFO=<ID=DP_DG,Number=A,Type=Integer,Description=\"SpliceAI DG position\">\n"
                        + "##INFO=<ID=DP_DL,Number=A,Type=Integer,Description=\"SpliceAI DL position\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr17\t43071077\t.\tA\tG\t.\tPASS\t"
                        + "ANN=G|splice_region_variant|LOW|BRCA1|672|transcript|NM_007294.4|protein_coding|"
                        + "1/20|c.100A>G|p.?;"
                        + "DS_AG=0.10;DS_AL=0.20;DS_DG=0.70;DS_DL=0.05;DP_AG=1;DP_AL=2;DP_DG=-3;DP_DL=4\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        VariantInterpretation interpretation = new VcfInfoBasicVariantAnnotator()
                .annotate(vcf, Collections.singletonList(observation()))
                .get(0);

        EvidenceItem spliceAi = evidence(interpretation, "SPLICEAI");
        assertEquals("APPLICABLE", spliceAi.getApplicability());
        assertEquals(0.70d, spliceAi.getRawScore());
        assertEquals("donor_gain", spliceAi.getDetails().get("maxEvent"));
        assertEquals("-3", spliceAi.getDetails().get("maxDeltaPosition"));
    }

    @Test
    void reportsNoRecordWhenSpliceAiInfoIsMalformedOrAbsent() throws IOException {
        Path vcf = tempDir.resolve("spliceai-no-record.vcf");
        Files.write(
                vcf,
                ("##fileformat=VCFv4.2\n"
                        + "##INFO=<ID=SpliceAI,Number=.,Type=String,Description=\"SpliceAI\">\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        + "chr17\t43071077\t.\tA\tG\t.\tPASS\tSpliceAI=malformed\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        VariantInterpretation interpretation = new VcfInfoBasicVariantAnnotator()
                .annotate(vcf, Collections.singletonList(observation()))
                .get(0);

        EvidenceItem spliceAi = evidence(interpretation, "SPLICEAI");
        assertEquals("NO_RECORD", spliceAi.getApplicability());
        assertEquals("VCF_INFO_SPLICEAI", spliceAi.getDetails().get("source"));
    }

    private VariantQcObservation observation() {
        return observation("G");
    }

    private VariantQcObservation observation(String alternate) {
        return new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "17", 43071077, "A", alternate),
                "proband",
                "0/1",
                30,
                99,
                "18,12",
                0.4d,
                "PASS",
                "SNV",
                "chr17|43071077|A|" + alternate + "|1"
        );
    }

    private EvidenceItem evidence(VariantInterpretation interpretation, String source) {
        for (EvidenceItem evidence : interpretation.getEvidence()) {
            if (source.equals(evidence.getSource())) {
                return evidence;
            }
        }
        throw new AssertionError("Missing evidence source " + source + " in " + Arrays.toString(
                interpretation.getEvidence().toArray()
        ));
    }
}
