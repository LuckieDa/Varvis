package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GnomadTabixProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsChromosomeFromConfiguredGnomadFileNames() {
        assertEquals(
                "1",
                GnomadTabixProvider.chromosomeFromFile(
                        Paths.get("gnomad.exomes.v4.1.sites.chr1.vcf.bgz")
                )
        );
        assertEquals(
                "22",
                GnomadTabixProvider.chromosomeFromFile(
                        Paths.get("D:/data/gnomad.exomes.v4.1.sites.chr22.vcf.bgz")
                )
        );
        assertEquals(
                "X",
                GnomadTabixProvider.chromosomeFromFile(
                        Paths.get("gnomad.exomes.v4.1.sites.chrX.vcf.bgz")
                )
        );
        assertEquals(
                "MT",
                GnomadTabixProvider.chromosomeFromFile(
                        Paths.get("gnomad.genomes.sites.chrM.vcf.bgz")
                )
        );
        assertNull(GnomadTabixProvider.chromosomeFromFile(Paths.get("gnomad-sites.vcf.bgz")));
    }

    @Test
    void queriesCompactFileInOneBatchAndMatchesAlternateAllele() throws Exception {
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path countFile = tempDir.resolve("count.txt");
        Path tabix = tabixScript(
                "echo x>> \"%~dp0count.txt\"\r\n"
                        + "echo chr1\t100\tA\tG\t0.120\t0.500\t4\t8\t1\texomes\tgnomAD v4.1\tPASS\r\n"
                        + "echo chr1\t100\tA\tT\t0.010\t0.020\t1\t50\t0\texomes\tgnomAD v4.1\tPASS\r\n"
                        + "exit /b 0\r\n"
        );
        VariantAnalysisProperties properties = properties(compact, tabix);
        GnomadTabixProvider provider = new GnomadTabixProvider(properties);

        NormalizedVariantKey matched = key("G");
        NormalizedVariantKey samePositionDifferentAlt = key("C");
        Map<NormalizedVariantKey, List<EvidenceItem>> result = provider.annotate(
                Arrays.asList(variant(matched), variant(samePositionDifferentAlt)),
                context()
        );

        assertEquals("APPLICABLE", evidence(result, matched).getApplicability());
        assertEquals(0.5d, evidence(result, matched).getRawScore());
        assertEquals("LOCAL_GNOMAD", evidence(result, matched).getDetails().get("source"));
        assertEquals("compact", evidence(result, matched).getDetails().get("querySource"));
        assertEquals("SUCCEEDED", evidence(result, matched).getDetails().get("resourceExecutionStatus"));
        assertEquals("HIT", evidence(result, matched).getDetails().get("queryOutcomeStatus"));
        assertEquals("NO_RECORD", evidence(result, samePositionDifferentAlt).getApplicability());
        assertEquals("NO_RECORD", evidence(result, samePositionDifferentAlt).getDetails().get("queryOutcomeStatus"));
        assertEquals(1, byteCount(countFile, (byte) 'x'));
    }

    @Test
    void returnsUnavailableWhenCompactIndexIsMissingWithoutFullVcfFallback() throws Exception {
        Path missingCompact = tempDir.resolve("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path fullVcf = indexedFile("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        Path tabix = tabixScript("echo should-not-run\r\nexit /b 0\r\n");
        VariantAnalysisProperties properties = properties(missingCompact, tabix);
        properties.getGnomad().setGrch38Files(fullVcf.toString());

        EvidenceItem evidence = evidence(new GnomadTabixProvider(properties).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("NOT_RUN", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("UNAVAILABLE", evidence.getDetails().get("resourceAvailabilityStatus"));
    }

    @Test
    void returnsNoRecordWhenCompactLookupFindsNoMatchingAllele() throws Exception {
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path tabix = tabixScript(
                "echo chr1\t100\tA\tT\t0.010\t0.020\t1\t50\t0\texomes\tgnomAD v4.1\tPASS\r\n"
                        + "exit /b 0\r\n"
        );
        VariantAnalysisProperties properties = properties(compact, tabix);

        EvidenceItem evidence = evidence(new GnomadTabixProvider(properties).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("NO_RECORD", evidence.getApplicability());
        assertEquals(null, evidence.getRawScore());
        assertEquals("LOCAL_GNOMAD", evidence.getDetails().get("source"));
    }

    @Test
    void returnsFailedWhenCompactLookupCommandFails() throws Exception {
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path tabix = tabixScript("exit /b 7\r\n");
        VariantAnalysisProperties properties = properties(compact, tabix);

        EvidenceItem evidence = evidence(new GnomadTabixProvider(properties).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("FAILED", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", evidence.getDetails().get("queryOutcomeStatus"));
    }

    @Test
    void returnsTimeoutWhenCompactLookupTimesOut() throws Exception {
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path tabix = tabixScript("echo simulated lookup timed out 1>&2\r\nexit /b 7\r\n");
        VariantAnalysisProperties properties = properties(compact, tabix);

        EvidenceItem evidence = evidence(new GnomadTabixProvider(properties).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("TIMEOUT", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", evidence.getDetails().get("queryOutcomeStatus"));
    }

    @Test
    void interruptedParallelLookupMarksEveryUnresolvedVariantFailedAndPreservesInterrupt() throws Exception {
        Path chromosome1 = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path chromosome2 = indexedFile("gnomad.exomes.v4.1.max-af.chr2.tsv.gz");
        Path tabix = tabixScript("exit /b 0\r\n");
        VariantAnalysisProperties properties = properties(chromosome1, tabix);
        properties.getGnomad().setGrch38CompactFiles(chromosome1 + "," + chromosome2);
        properties.getGnomad().setParallelism(2);
        NormalizedVariantKey key1 = key("GRCh38", "1", "G");
        NormalizedVariantKey key2 = key("GRCh38", "2", "T");

        Thread.currentThread().interrupt();
        try {
            Map<NormalizedVariantKey, List<EvidenceItem>> result =
                    new GnomadTabixProvider(properties).annotate(
                            Arrays.asList(variant(key1), variant(key2)),
                            context()
                    );

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, result.size());
            assertInterruptedFailure(result, key1);
            assertInterruptedFailure(result, key2);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void grch37QueryReturnsAssemblyUnsupportedWithoutTabixLookup() throws Exception {
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path countFile = tempDir.resolve("count.txt");
        Path tabix = tabixScript("echo x>> \"%~dp0count.txt\"\r\nexit /b 0\r\n");
        VariantAnalysisProperties properties = properties(compact, tabix);
        NormalizedVariantKey grch37 = key("GRCh37", "G");

        EvidenceItem evidence = evidence(new GnomadTabixProvider(properties).annotate(
                Collections.singletonList(variant(grch37)),
                context(GenomeAssembly.GRCh37)
        ), grch37);

        assertEquals("ASSEMBLY_UNSUPPORTED", evidence.getApplicability());
        assertEquals("GRCh37", evidence.getDetails().get("requestedAssembly"));
        assertEquals("GRCh38", evidence.getDetails().get("configuredResourceAssembly"));
        assertEquals(Boolean.FALSE, evidence.getDetails().get("providerInvoked"));
        assertEquals("NOT_RUN", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", evidence.getDetails().get("queryOutcomeStatus"));
        assertEquals("NOT_APPLICABLE", evidence.getDetails().get("resourceApplicabilityStatus"));
        assertEquals(false, Files.exists(countFile));
    }

    private VariantAnalysisProperties properties(Path compact, Path tabix) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getGnomad().setEnabled(true);
        properties.getGnomad().setVersion("gnomAD exomes v4.1 GRCh38");
        properties.getGnomad().setGrch38CompactFiles(compact.toString());
        properties.getGnomad().setTabixExecutable(tabix.toString());
        properties.getGnomad().setTabixCacheMb(0);
        return properties;
    }

    private Path indexedFile(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[] {1});
        Files.write(tempDir.resolve(name + ".tbi"), new byte[] {1});
        return file;
    }

    private Path tabixScript(String body) throws IOException {
        Path script = tempDir.resolve("tabix.cmd");
        Files.write(script, ("@echo off\r\n" + body).getBytes(StandardCharsets.UTF_8));
        return script;
    }

    private int byteCount(Path file, byte expected) throws IOException {
        int count = 0;
        for (byte value : Files.readAllBytes(file)) {
            if (value == expected) {
                count++;
            }
        }
        return count;
    }

    private EvidenceItem evidence(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                  NormalizedVariantKey key) {
        return result.get(key).get(0);
    }

    private void assertInterruptedFailure(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                          NormalizedVariantKey key) {
        EvidenceItem evidence = evidence(result, key);
        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("FAILED", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("PROVIDER_INTERRUPTED", evidence.getDetails().get("resourceExecutionReasonCode"));
        assertEquals("NOT_QUERIED", evidence.getDetails().get("queryOutcomeStatus"));
    }

    private NormalizedVariantKey key(String alternate) {
        return key("GRCh38", alternate);
    }

    private NormalizedVariantKey key(String assembly, String alternate) {
        return key(assembly, "1", alternate);
    }

    private NormalizedVariantKey key(String assembly, String chromosome, String alternate) {
        return new NormalizedVariantKey(assembly, chromosome, 100, "A", alternate);
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        return new VariantInterpretation(
                key.getGenomeAssembly() + ":" + key.getChromosome() + ":" + key.getPosition()
                        + ":" + key.getReference() + ":" + key.getAlternate(),
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "chr1|100|" + key.getReference()
                        + "|" + key.getAlternate() + "|1"
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private AnalysisContext context() {
        return context(GenomeAssembly.GRCh38);
    }

    private AnalysisContext context(GenomeAssembly assembly) {
        return new AnalysisContext("analysis", assembly, AnalysisMode.WES);
    }
}
