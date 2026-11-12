package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.VariantEvidenceProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Grch37EvidenceProjectionServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingProjectionResourcesRemainUnavailableRatherThanNoRecord() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLiftOver().setEvidenceProjectionEnabled(true);
        VariantInterpretation interpretation = grch37Variant();
        RecordingProvider gnomad = new RecordingProvider("GNOMAD", 0.001d);
        RecordingProvider caddProvider = new RecordingProvider("CADD", 20d);

        new Grch37EvidenceProjectionService(properties, Arrays.asList(gnomad, caddProvider))
                .apply(Collections.singletonList(interpretation), grch37Context());

        EvidenceItem cadd = evidence(interpretation, "CADD");
        EvidenceItem gnomadEvidence = evidence(interpretation, "GNOMAD");
        assertEquals("UNAVAILABLE", cadd.getApplicability());
        assertEquals("LIFTOVER_UNAVAILABLE", cadd.getDetails().get("status"));
        assertEquals("GRCh37", cadd.getDetails().get("requestedAssembly"));
        assertEquals("UNAVAILABLE", gnomadEvidence.getApplicability());
        assertNull(gnomad.queryAssembly);
        assertNull(caddProvider.queryAssembly);
    }

    @Test
    void verifiedProjectionQueriesGrch38ProviderAndKeepsOriginalCandidateContext() throws Exception {
        Path chain = temporaryDirectory.resolve("identity.chain");
        Files.write(chain, Collections.singletonList(
                "chain 1 chr1 100 + 0 100 chr1 100 + 0 100 1\n100"
        ), StandardCharsets.UTF_8);
        Path fasta = temporaryDirectory.resolve("grch38.fa");
        String bases = repeat('A', 100);
        Files.write(fasta, Collections.singletonList(">chr1\n" + bases), StandardCharsets.UTF_8);
        Files.write(fasta.resolveSibling("grch38.fa.fai"), Collections.singletonList(
                "chr1\t100\t6\t100\t101"
        ), StandardCharsets.UTF_8);

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLiftOver().setEvidenceProjectionEnabled(true);
        properties.getLiftOver().setHg19ToHg38Chain(chain.toString());
        properties.getNormalization().setGrch38Reference(fasta.toString());
        RecordingCaddProvider provider = new RecordingCaddProvider();
        VariantInterpretation interpretation = grch37Variant();

        new Grch37EvidenceProjectionService(properties, Collections.<VariantEvidenceProvider>singletonList(provider))
                .apply(Collections.singletonList(interpretation), grch37Context());

        assertEquals(GenomeAssembly.GRCh38, provider.queryAssembly);
        assertEquals("GRCh38", provider.key.getGenomeAssembly());
        assertEquals(10, provider.key.getPosition());
        EvidenceItem cadd = evidence(interpretation, "CADD");
        assertEquals("APPLICABLE", cadd.getApplicability());
        assertEquals("GRCH37_TO_GRCH38_VERIFIED", cadd.getDetails().get("coordinateProjection"));
        assertEquals("HTSJDK_LIFTOVER", cadd.getDetails().get("projectionMethod"));
        assertEquals("htsjdk-liftover-v1", cadd.getDetails().get("projectionMethodVersion"));
        assertEquals("VERIFIED", cadd.getDetails().get("projectionStatus"));
        assertEquals("GRCh37", cadd.getDetails().get("requestedAssembly"));
        assertEquals("GRCh37", cadd.getDetails().get("inputAssembly"));
        assertEquals("GRCh38", cadd.getDetails().get("queryAssembly"));
        assertEquals("GRCh38", cadd.getDetails().get("evidenceAssembly"));
        assertTrue(cadd.getCanonicalVariantId().startsWith("GRCh37|"));
    }

    @Test
    void verifiedProjectionQueriesGnomadAndCaddWithGrch38Keys() throws Exception {
        ProjectionResources resources = projectionResources("identity", 'A');
        VariantAnalysisProperties properties = projectionProperties(resources);
        RecordingProvider gnomad = new RecordingProvider("GNOMAD", 0.001d);
        RecordingCaddProvider cadd = new RecordingCaddProvider();
        VariantInterpretation interpretation = grch37Variant();

        new Grch37EvidenceProjectionService(properties, Arrays.asList(gnomad, cadd))
                .apply(Collections.singletonList(interpretation), grch37Context());

        assertEquals(GenomeAssembly.GRCh38, gnomad.queryAssembly);
        assertEquals(GenomeAssembly.GRCh38, cadd.queryAssembly);
        assertEquals("GRCh38", gnomad.key.getGenomeAssembly());
        assertEquals("GRCh38", cadd.key.getGenomeAssembly());
        assertEquals("APPLICABLE", evidence(interpretation, "GNOMAD").getApplicability());
        assertEquals("APPLICABLE", evidence(interpretation, "CADD").getApplicability());
    }

    @Test
    void duplicateCandidateRowsForTheSameAlleleShareProjectedEvidenceWithoutAmbiguity()
            throws Exception {
        ProjectionResources resources = projectionResources("duplicate-candidates", 'A');
        VariantAnalysisProperties properties = projectionProperties(resources);
        properties.getLiftOver().setEvidenceProjectionMaxCandidates(1);
        RecordingProvider cadd = new RecordingProvider("CADD", 20d);
        VariantInterpretation dominant = grch37Candidate(1, "AUTOSOMAL_DOMINANT");
        VariantInterpretation recessive = grch37Candidate(2, "AUTOSOMAL_RECESSIVE");

        new Grch37EvidenceProjectionService(properties, Collections.singletonList(cadd))
                .apply(Arrays.asList(dominant, recessive), grch37Context());

        assertEquals(1, cadd.variantCount);
        assertEquals("APPLICABLE", evidence(dominant, "CADD").getApplicability());
        assertEquals("APPLICABLE", evidence(recessive, "CADD").getApplicability());
        assertEquals("exomiser-v1-00000001", dominant.getCandidateId());
        assertEquals("exomiser-v1-00000002", recessive.getCandidateId());
        assertEquals(
                "AUTOSOMAL_DOMINANT",
                dominant.getCandidateIdentity().getModeOfInheritance()
        );
        assertEquals(
                "AUTOSOMAL_RECESSIVE",
                recessive.getCandidateIdentity().getModeOfInheritance()
        );
    }

    @Test
    void negativeStrandProjectionReverseComplementsAllelesBeforeProviderLookup() throws Exception {
        Path chain = temporaryDirectory.resolve("negative.chain");
        Files.write(chain, Collections.singletonList(
                "chain 1 chr1 100 + 0 100 chr1 100 - 0 100 1\n100"
        ), StandardCharsets.UTF_8);
        Path fasta = temporaryDirectory.resolve("negative.fa");
        Files.write(fasta, Collections.singletonList(">chr1\n" + repeat('T', 100)), StandardCharsets.UTF_8);
        Files.write(fasta.resolveSibling("negative.fa.fai"), Collections.singletonList(
                "chr1\t100\t6\t100\t101"
        ), StandardCharsets.UTF_8);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLiftOver().setEvidenceProjectionEnabled(true);
        properties.getLiftOver().setHg19ToHg38Chain(chain.toString());
        properties.getNormalization().setGrch38Reference(fasta.toString());
        RecordingProvider provider = new RecordingProvider("CADD", 20d);

        new Grch37EvidenceProjectionService(properties, Collections.singletonList(provider))
                .apply(Collections.singletonList(grch37Variant()), grch37Context());

        assertEquals(GenomeAssembly.GRCh38, provider.queryAssembly);
        assertEquals("T", provider.key.getReference());
        assertEquals("A", provider.key.getAlternate());
    }

    @Test
    void indelProjectionUsesFullReferenceIntervalAndKeepsOriginalIdentity() throws Exception {
        ProjectionResources resources = projectionResources("indel", 'A');
        VariantAnalysisProperties properties = projectionProperties(resources);
        RecordingProvider provider = new RecordingProvider("CADD", 20d);
        VariantInterpretation interpretation = grch37Variant("AA", "A");

        new Grch37EvidenceProjectionService(properties, Collections.singletonList(provider))
                .apply(Collections.singletonList(interpretation), grch37Context());

        assertEquals("AA", provider.key.getReference());
        assertEquals("A", provider.key.getAlternate());
        assertTrue(evidence(interpretation, "CADD").getCanonicalVariantId().startsWith("GRCh37|"));
    }

    @Test
    void nativeGrch38ContextBypassesProjectionAndDoesNotCallProviders() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLiftOver().setEvidenceProjectionEnabled(true);
        RecordingProvider provider = new RecordingProvider("CADD", 20d);
        VariantInterpretation interpretation = grch37Variant();
        AnalysisContext grch38 = new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES);

        new Grch37EvidenceProjectionService(properties, Collections.singletonList(provider))
                .apply(Collections.singletonList(interpretation), grch38);

        assertNull(provider.queryAssembly);
        assertTrue(interpretation.getEvidence().isEmpty());
    }

    @Test
    void clinvarIsNeverIncludedInProjectedProviderCalls() throws Exception {
        ProjectionResources resources = projectionResources("clinvar", 'A');
        VariantAnalysisProperties properties = projectionProperties(resources);
        RecordingProvider clinvar = new RecordingProvider("CLINVAR", null);

        new Grch37EvidenceProjectionService(properties, Collections.singletonList(clinvar))
                .apply(Collections.singletonList(grch37Variant()), grch37Context());

        assertNull(clinvar.queryAssembly);
    }

    @Test
    void referenceMismatchDoesNotQueryGrch38Provider() throws Exception {
        Path chain = temporaryDirectory.resolve("mismatch.chain");
        Files.write(chain, Collections.singletonList(
                "chain 1 chr1 100 + 0 100 chr1 100 + 0 100 1\n100"
        ), StandardCharsets.UTF_8);
        Path fasta = temporaryDirectory.resolve("mismatch.fa");
        Files.write(fasta, Collections.singletonList(">chr1\n" + repeat('A', 100)), StandardCharsets.UTF_8);
        Files.write(fasta.resolveSibling("mismatch.fa.fai"), Collections.singletonList(
                "chr1\t100\t6\t100\t101"
        ), StandardCharsets.UTF_8);

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLiftOver().setEvidenceProjectionEnabled(true);
        properties.getLiftOver().setHg19ToHg38Chain(chain.toString());
        properties.getNormalization().setGrch38Reference(fasta.toString());
        RecordingCaddProvider provider = new RecordingCaddProvider();
        VariantInterpretation interpretation = grch37Variant("C", "T");

        new Grch37EvidenceProjectionService(properties, Collections.<VariantEvidenceProvider>singletonList(provider))
                .apply(Collections.singletonList(interpretation), grch37Context());

        assertNull(provider.queryAssembly);
        EvidenceItem cadd = evidence(interpretation, "CADD");
        assertEquals("UNAVAILABLE", cadd.getApplicability());
        assertEquals("LIFTOVER_REF_MISMATCH", cadd.getDetails().get("status"));
    }

    @Test
    void unmappedProjectionDoesNotQueryGnomadOrCadd() throws Exception {
        Path chain = temporaryDirectory.resolve("partial.chain");
        Files.write(chain, Collections.singletonList(
                "chain 1 chr1 50 + 0 50 chr1 50 + 0 50 1\n50"
        ), StandardCharsets.UTF_8);
        Path fasta = temporaryDirectory.resolve("partial.fa");
        Files.write(fasta, Collections.singletonList(">chr1\n" + repeat('A', 100)), StandardCharsets.UTF_8);
        Files.write(fasta.resolveSibling("partial.fa.fai"), Collections.singletonList(
                "chr1\t100\t6\t100\t101"
        ), StandardCharsets.UTF_8);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLiftOver().setEvidenceProjectionEnabled(true);
        properties.getLiftOver().setHg19ToHg38Chain(chain.toString());
        properties.getNormalization().setGrch38Reference(fasta.toString());
        RecordingProvider gnomad = new RecordingProvider("GNOMAD", 0.001d);
        RecordingProvider cadd = new RecordingProvider("CADD", 20d);
        VariantInterpretation interpretation = grch37VariantAt(60, "A", "T");

        new Grch37EvidenceProjectionService(properties, Arrays.asList(gnomad, cadd))
                .apply(Collections.singletonList(interpretation), grch37Context());

        assertNull(gnomad.queryAssembly);
        assertNull(cadd.queryAssembly);
        assertEquals("LIFTOVER_UNMAPPED", evidence(interpretation, "GNOMAD").getDetails().get("status"));
        assertEquals("LIFTOVER_UNMAPPED", evidence(interpretation, "CADD").getDetails().get("status"));
    }

    private VariantInterpretation grch37Variant() {
        return grch37Variant("A", "T");
    }

    private VariantInterpretation grch37Variant(String reference, String alternate) {
        return grch37VariantAt(10, reference, alternate);
    }

    private VariantInterpretation grch37VariantAt(int position, String reference, String alternate) {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh37, "chr1", position, reference, alternate);
        VariantQcObservation observation = new VariantQcObservation(
                key, "proband", "0/1", 30, 99, "15,15", 0.5d, "PASS", "SNV", null
        );
        return new VariantInterpretation("test", observation, Collections.emptyList(), Collections.emptyList());
    }

    private VariantInterpretation grch37Candidate(int sourceOrdinal, String modeOfInheritance) {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh37, "chr1", 10, "A", "T");
        VariantQcObservation observation = new VariantQcObservation(
                key, "proband", "0/1", 30, 99, "15,15", 0.5d, "PASS", "SNV", null
        );
        return new VariantInterpretation(
                CandidateIdentity.exomiser(
                        key.canonicalId(),
                        sourceOrdinal,
                        "GENE1",
                        modeOfInheritance,
                        1
                ),
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private AnalysisContext grch37Context() {
        return new AnalysisContext("test", GenomeAssembly.GRCh37, AnalysisMode.WES);
    }

    private EvidenceItem evidence(VariantInterpretation interpretation, String source) {
        for (EvidenceItem item : interpretation.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        throw new AssertionError("Missing evidence source " + source);
    }

    private String repeat(char character, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(character);
        }
        return builder.toString();
    }

    private ProjectionResources projectionResources(String name, char base) throws Exception {
        Path chain = temporaryDirectory.resolve(name + ".chain");
        Files.write(chain, Collections.singletonList(
                "chain 1 chr1 100 + 0 100 chr1 100 + 0 100 1\n100"
        ), StandardCharsets.UTF_8);
        Path fasta = temporaryDirectory.resolve(name + ".fa");
        Files.write(fasta, Collections.singletonList(">chr1\n" + repeat(base, 100)), StandardCharsets.UTF_8);
        Files.write(fasta.resolveSibling(name + ".fa.fai"), Collections.singletonList(
                "chr1\t100\t6\t100\t101"
        ), StandardCharsets.UTF_8);
        return new ProjectionResources(chain, fasta);
    }

    private VariantAnalysisProperties projectionProperties(ProjectionResources resources) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLiftOver().setEvidenceProjectionEnabled(true);
        properties.getLiftOver().setHg19ToHg38Chain(resources.chain.toString());
        properties.getNormalization().setGrch38Reference(resources.fasta.toString());
        return properties;
    }

    private static final class ProjectionResources {
        private final Path chain;
        private final Path fasta;

        private ProjectionResources(Path chain, Path fasta) {
            this.chain = chain;
            this.fasta = fasta;
        }
    }

    private static final class RecordingCaddProvider implements VariantEvidenceProvider {

        private GenomeAssembly queryAssembly;
        private NormalizedVariantKey key;

        @Override
        public String source() {
            return "CADD";
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants,
                                                                        AnalysisContext context) {
            queryAssembly = context.getGenomeAssembly();
            key = variants.get(0).getObservation().getKey();
            Map<NormalizedVariantKey, List<EvidenceItem>> result =
                    new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
            result.put(key, Collections.singletonList(new EvidenceItem(
                    source(), version(), "COMPUTATIONAL", "APPLICABLE", 20d,
                    "CADD PHRED 20", "Synthetic projected lookup", Collections.emptyMap()
            )));
            return result;
        }
    }

    private static final class RecordingProvider implements VariantEvidenceProvider {

        private final String source;
        private final Double score;
        private GenomeAssembly queryAssembly;
        private NormalizedVariantKey key;
        private int variantCount;

        private RecordingProvider(String source, Double score) {
            this.source = source;
            this.score = score;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants,
                                                                        AnalysisContext context) {
            queryAssembly = context.getGenomeAssembly();
            key = variants.get(0).getObservation().getKey();
            variantCount = variants.size();
            Map<NormalizedVariantKey, List<EvidenceItem>> result =
                    new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
            result.put(key, Collections.singletonList(new EvidenceItem(
                    source(), version(), "TEST", "APPLICABLE", score,
                    source + " test evidence", "Synthetic projected lookup", Collections.emptyMap()
            )));
            return result;
        }
    }
}
