package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpliceAiProviderRealResourceLocalIntegrationTest {

    @Test
    void queriesRealMaskedHg38SnvResourceAndParsesDeltaScores() {
        VariantAnalysisProperties properties = properties();
        properties.getSpliceAi().setSnvResourcePath(resource("VARVIS_SPLICEAI_SNV_RESOURCE").toString());
        properties.getSpliceAi().setSnvIndexPath(resource("VARVIS_SPLICEAI_SNV_INDEX").toString());

        EvidenceItem evidence = evidence(new SpliceAiProvider(properties).annotate(
                Collections.singletonList(variant("1", 925775, "G", "T")),
                context()
        ));

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("LOCAL_SPLICEAI", evidence.getDetails().get("source"));
        assertEquals(0.96d, evidence.getRawScore());
        assertEquals(0.00d, evidence.getDetails().get("DS_AG"));
        assertEquals(0.00d, evidence.getDetails().get("DS_AL"));
        assertEquals(0.96d, evidence.getDetails().get("DS_DG"));
        assertEquals(0.44d, evidence.getDetails().get("DS_DL"));
        assertEquals("-30", evidence.getDetails().get("DP_AG"));
        assertEquals("-10", evidence.getDetails().get("DP_AL"));
        assertEquals("-2", evidence.getDetails().get("DP_DG"));
        assertEquals("25", evidence.getDetails().get("DP_DL"));
        assertEquals("donor_gain", evidence.getDetails().get("maxEvent"));
    }

    @Test
    void queriesRealMaskedHg38IndelResourceAndParsesDeltaScores() {
        VariantAnalysisProperties properties = properties();
        properties.getSpliceAi().setIndelResourcePath(resource("VARVIS_SPLICEAI_INDEL_RESOURCE").toString());
        properties.getSpliceAi().setIndelIndexPath(resource("VARVIS_SPLICEAI_INDEL_INDEX").toString());

        EvidenceItem evidence = evidence(new SpliceAiProvider(properties).annotate(
                Collections.singletonList(variant("1", 925775, "G", "GT")),
                context()
        ));

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("LOCAL_SPLICEAI", evidence.getDetails().get("source"));
        assertEquals(0.94d, evidence.getRawScore());
        assertEquals(0.00d, evidence.getDetails().get("DS_AG"));
        assertEquals(0.00d, evidence.getDetails().get("DS_AL"));
        assertEquals(0.94d, evidence.getDetails().get("DS_DG"));
        assertEquals(0.45d, evidence.getDetails().get("DS_DL"));
        assertEquals("-33", evidence.getDetails().get("DP_AG"));
        assertEquals("-10", evidence.getDetails().get("DP_AL"));
        assertEquals("-1", evidence.getDetails().get("DP_DG"));
        assertEquals("25", evidence.getDetails().get("DP_DL"));
        assertEquals("donor_gain", evidence.getDetails().get("maxEvent"));
    }

    private VariantAnalysisProperties properties() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getSpliceAi().setEnabled(true);
        properties.getSpliceAi().setVersion("v1.3.1");
        properties.getSpliceAi().setGenomeBuild("GRCh38");
        return properties;
    }

    private Path resource(String variable) {
        String value = System.getenv(variable);
        Assumptions.assumeTrue(value != null && !value.trim().isEmpty(), variable + " is not configured");
        Path path = Paths.get(value);
        Assumptions.assumeTrue(Files.isRegularFile(path), variable + " is not a file");
        return path;
    }

    private EvidenceItem evidence(Map<NormalizedVariantKey, List<EvidenceItem>> result) {
        return result.values().iterator().next().get(0);
    }

    private VariantInterpretation variant(String chromosome, int position, String reference, String alternate) {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", chromosome, position, reference, alternate);
        return new VariantInterpretation(
                "GRCh38:" + chromosome + ":" + position + ":" + reference + ":" + alternate,
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", reference.length() == 1 && alternate.length() == 1 ? "SNV" : "INDEL",
                        chromosome + "|" + position + "|" + reference + "|" + alternate + "|1"
                ),
                Collections.singletonList(new TranscriptConsequence(null, null, null, null, null, "splice_donor_variant")),
                Collections.emptyList()
        );
    }

    private AnalysisContext context() {
        return new AnalysisContext("spliceai-real-local", GenomeAssembly.GRCh38, AnalysisMode.WES);
    }
}
