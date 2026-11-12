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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClinVarTabixProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void localClinVarHitReturnsTraceableEvidence() throws Exception {
        EvidenceItem evidence = evidence(provider(lines(pathogenicLine())).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("Pathogenic", evidence.getLabel());
        assertEquals("LOCAL_CLINVAR", evidence.getDetails().get("source"));
        assertEquals("exact_allele", evidence.getDetails().get("matchType"));
        assertEquals("Pathogenic", evidence.getDetails().get("clinicalSignificance"));
        assertEquals("criteria_provided", evidence.getDetails().get("reviewStatus"));
        assertEquals("GENE1", evidence.getDetails().get("geneSymbol"));
        assertEquals("MedGen:C000000", evidence.getDetails().get("diseaseIds"));
        assertEquals("VCV000001", evidence.getDetails().get("accession"));
        assertEquals("SUCCEEDED", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("HIT", evidence.getDetails().get("queryOutcomeStatus"));
    }

    @Test
    void samePositionDifferentAlternateDoesNotMisannotate() throws Exception {
        EvidenceItem evidence = evidence(provider(lines(pathogenicLine())).annotate(
                Collections.singletonList(variant(key("C"))),
                context()
        ), key("C"));

        assertEquals("NO_RECORD", evidence.getApplicability());
        assertEquals("position_only_rejected", evidence.getDetails().get("matchType"));
        assertNull(evidence.getRawScore());
    }

    @Test
    void multiallelicRecordMatchesExactAlternate() throws Exception {
        String line = "chr1\t100\tVCV000002\tA\tT,G\t.\tPASS\t"
                + "ALLELEID=2;CLNSIG=Likely_pathogenic;CLNREVSTAT=reviewed_by_expert_panel";
        EvidenceItem evidence = evidence(provider(lines(line)).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("Likely_pathogenic", evidence.getDetails().get("clinicalSignificance"));
        assertEquals("exact_allele", evidence.getDetails().get("matchType"));
    }

    @Test
    void conflictingSignificanceIsMarkedConflicting() throws Exception {
        String line = "chr1\t100\tVCV000003\tA\tG\t.\tPASS\t"
                + "ALLELEID=3;CLNSIG=Pathogenic/Likely_benign;CLNREVSTAT=criteria_provided";
        EvidenceItem evidence = evidence(provider(lines(line)).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("CONFLICTING", evidence.getApplicability());
        assertEquals("Conflicting interpretations", evidence.getLabel());
        assertEquals("LOCAL_CLINVAR", evidence.getDetails().get("source"));
        assertEquals("CONFLICTING_INTERPRETATIONS", evidence.getDetails().get("conflictState"));
    }

    @Test
    void explicitClinVarConflictClassificationIsMarkedConflicting() throws Exception {
        String line = "chr1\t100\tVCV000004\tA\tG\t.\tPASS\t"
                + "ALLELEID=4;CLNSIG=Conflicting_classifications_of_pathogenicity;"
                + "CLNREVSTAT=criteria_provided,_conflicting_classifications;"
                + "CLNSIGCONF=Likely_pathogenic(4)|Uncertain_significance(1)";
        EvidenceItem evidence = evidence(provider(lines(line)).annotate(
                Collections.singletonList(variant(key("G"))), context()
        ), key("G"));

        assertEquals("CONFLICTING", evidence.getApplicability());
        assertEquals("CONFLICTING_INTERPRETATIONS", evidence.getDetails().get("conflictState"));
    }

    @Test
    void conditionIdentifierGroupsAndContributingSubmissionIdentifiersRemainGrouped() throws Exception {
        String line = "chr1\t100\t13961\tA\tG\t.\tPASS\t"
                + "ALLELEID=15041;CLNSIG=Conflicting_classifications_of_pathogenicity;"
                + "CLNREVSTAT=criteria_provided,_conflicting_classifications;"
                + "CLNDN=Disease_A|Disease_B|Disease_C;"
                + "CLNDISDB=MONDO:0000001,MedGen:C000001|.|OMIM:000002,Orphanet:3;"
                + "CLNSIGCONF=Pathogenic(1)|Likely_benign(1);"
                + "CLNSIGSCV=SCV000000001|SCV000000002";
        EvidenceItem evidence = evidence(provider(lines(line)).annotate(
                Collections.singletonList(variant(key("G"))), context()
        ), key("G"));

        List<?> conditions = (List<?>) evidence.getDetails().get("conditions");
        assertEquals(3, conditions.size());
        Map<?, ?> first = (Map<?, ?>) conditions.get(0);
        Map<?, ?> second = (Map<?, ?>) conditions.get(1);
        Map<?, ?> third = (Map<?, ?>) conditions.get(2);
        assertEquals("Disease_A", first.get("name"));
        assertEquals(List.of("MONDO:0000001", "MedGen:C000001"), first.get("identifiers"));
        assertEquals("Disease_B", second.get("name"));
        assertFalse(second.containsKey("identifiers"));
        assertEquals("Disease_C", third.get("name"));
        assertEquals(List.of("OMIM:000002", "Orphanet:3"), third.get("identifiers"));
        assertEquals(List.of("SCV000000001", "SCV000000002"),
                evidence.getDetails().get("contributingSubmissionIdentifiers"));
    }

    @Test
    void noLocalRecordReturnsNoRecordNotUnavailable() throws Exception {
        EvidenceItem evidence = evidence(provider(Collections.emptyList()).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("NO_RECORD", evidence.getApplicability());
        assertEquals("no_record", evidence.getDetails().get("matchType"));
        assertEquals("SUCCEEDED", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("NO_RECORD", evidence.getDetails().get("queryOutcomeStatus"));
    }

    @Test
    void preservesDistinctRecordsAndRemovesOnlyExactDuplicates() throws Exception {
        String secondRecord = "chr1\t100\tVCV000002\tA\tG\t.\tPASS\t"
                + "ALLELEID=2;CLNSIG=Likely_pathogenic;CLNREVSTAT=reviewed_by_expert_panel;"
                + "CLNDN=Disease_B;CLNDISDB=MedGen:C000001;CLNACC=VCV000002";
        Map<NormalizedVariantKey, List<EvidenceItem>> result = provider(
                lines(pathogenicLine(), pathogenicLine(), secondRecord)
        ).annotate(Collections.singletonList(variant(key("G"))), context());

        List<EvidenceItem> evidence = result.get(key("G"));
        assertEquals(2, evidence.size());
        assertEquals("VCV000001", evidence.get(0).getDetails().get("accession"));
        assertEquals("VCV000002", evidence.get(1).getDetails().get("accession"));
        assertTrue(evidence.get(0).getStableIdentityKey().contains("VCV000001"));
        assertTrue(evidence.get(1).getStableIdentityKey().contains("VCV000002"));
    }

    @Test
    void malformedRecordReturnsParseErrorWithoutFailingLookup() throws Exception {
        EvidenceItem evidence = evidence(provider(lines("chr1\t100\tbroken")).annotate(
                Collections.singletonList(variant(key("G"))), context()
        ), key("G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("PARSE_ERROR", evidence.getStatus());
        assertEquals("PARSE_ERROR", evidence.getDetails().get("status"));
    }

    @Test
    void unsupportedAssemblyIsExplicit() throws Exception {
        NormalizedVariantKey unsupported = key("GRCh36", "G");
        EvidenceItem evidence = evidence(provider(lines(pathogenicLine())).annotate(
                Collections.singletonList(variant(unsupported)), context()
        ), unsupported);

        assertEquals("ASSEMBLY_UNSUPPORTED", evidence.getApplicability());
        assertEquals("ASSEMBLY_UNSUPPORTED", evidence.getDetails().get("status"));
    }

    @Test
    void missingClinVarFileReturnsUnavailableWithoutFailingJob() throws Exception {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setGrch38File(tempDir.resolve("missing.vcf.gz").toString());

        EvidenceItem evidence = evidence(new ClinVarTabixProvider(properties).annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
    }

    @Test
    void lookupFailureReturnsFailedWithoutBecomingNoRecord() throws Exception {
        ClinVarTabixProvider provider = provider((file, key) -> {
            throw new IOException("simulated failure");
        });

        EvidenceItem evidence = evidence(provider.annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("FAILED", evidence.getStatus());
        assertEquals("FAILED", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", evidence.getDetails().get("queryOutcomeStatus"));
    }

    @Test
    void lookupTimeoutRemainsTimeoutWithoutBecomingNoRecord() throws Exception {
        ClinVarTabixProvider provider = provider((file, key) -> {
            throw new IOException("simulated lookup timed out");
        });

        EvidenceItem evidence = evidence(provider.annotate(
                Collections.singletonList(variant(key("G"))), context()
        ), key("G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("TIMEOUT", evidence.getStatus());
        assertEquals("TIMEOUT", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", evidence.getDetails().get("queryOutcomeStatus"));
    }

    @Test
    void grch37QueryUsesNativeClinVarResource() throws Exception {
        Path resource = indexedFile("clinvar.grch37.vcf.gz");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setVersion("ClinVar legacy combined");
        properties.getClinVar().setGrch37Version("ClinVar 2026-07-06 GRCh37");
        properties.getClinVar().setGrch38Version("ClinVar 2026-03-21 GRCh38");
        properties.getClinVar().setGrch37File(resource.toString());
        ClinVarTabixProvider provider = new ClinVarTabixProvider(properties, (file, key) -> {
            assertEquals(resource, file);
            return lines(pathogenicLine());
        });
        NormalizedVariantKey grch37 = key("GRCh37", "G");

        EvidenceItem evidence = evidence(provider.annotate(
                Collections.singletonList(variant(grch37)),
                context(GenomeAssembly.GRCh37)
        ), grch37);

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("GRCh37", evidence.getDetails().get("requestedAssembly"));
        assertEquals("GRCh37", evidence.getDetails().get("configuredResourceAssembly"));
        assertEquals("ClinVar 2026-07-06 GRCh37", evidence.getEffectiveResourceVersion());
        assertEquals("Pathogenic", evidence.getLabel());
    }

    @Test
    void grch37QueryWithoutNativeResourceReturnsUnavailableNotAssemblyUnsupported() throws Exception {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setGrch38File(indexedFile("clinvar.grch38.vcf.gz").toString());
        NormalizedVariantKey grch37 = key("GRCh37", "G");

        EvidenceItem evidence = evidence(new ClinVarTabixProvider(properties).annotate(
                Collections.singletonList(variant(grch37)),
                context(GenomeAssembly.GRCh37)
        ), grch37);

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("GRCh37", evidence.getDetails().get("requestedAssembly"));
        assertEquals("GRCh37", evidence.getDetails().get("configuredResourceAssembly"));
    }

    @Test
    void oneAssemblyLookupFailureDoesNotHideOtherAssemblyEvidence() throws Exception {
        Path grch37 = indexedFile("clinvar.grch37.vcf.gz");
        Path grch38 = indexedFile("clinvar.grch38.vcf.gz");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setGrch37File(grch37.toString());
        properties.getClinVar().setGrch38File(grch38.toString());
        ClinVarTabixProvider provider = new ClinVarTabixProvider(properties, (file, key) -> {
            if (file.equals(grch37)) {
                throw new IOException("simulated GRCh37 failure");
            }
            return lines(pathogenicLine());
        });
        NormalizedVariantKey grch37Key = key("GRCh37", "G");
        NormalizedVariantKey grch38Key = key("GRCh38", "G");

        Map<NormalizedVariantKey, List<EvidenceItem>> result = provider.annotate(
                Arrays.asList(variant(grch37Key), variant(grch38Key)),
                context(GenomeAssembly.GRCh38)
        );

        assertEquals("UNAVAILABLE", evidence(result, grch37Key).getApplicability());
        assertEquals("FAILED", evidence(result, grch37Key).getStatus());
        assertEquals("APPLICABLE", evidence(result, grch38Key).getApplicability());
    }

    @Test
    void boundedComparatorDiscoveryUsesOneQueryAndReturnsOnlySimpleNonTargetSnvs() throws Exception {
        AtomicInteger queryCount = new AtomicInteger();
        ClinVarTabixProvider provider = boundedProvider((file, region) -> {
            queryCount.incrementAndGet();
            assertEquals("1:98-102", region);
            return lines(
                    "1\t97\t10\tATTT\tA\t.\tPASS\tCLNSIG=Pathogenic",
                    "1\t99\t11\tA\tC,G\t.\tPASS\tCLNSIG=Pathogenic",
                    "1\t99\t12\tA\t<DEL>\t.\tPASS\tCLNSIG=Pathogenic",
                    "1\t100\t13\tA\tG\t.\tPASS\tCLNSIG=Pathogenic",
                    "1\t100\t14\tA\tC\t.\tPASS\tCLNSIG=Pathogenic;"
                            + "CLNSIGSCV=SCV000000014.1|SCV000000015.2",
                    "1\t101\t15\tT\tC\t.\tPASS\tCLNSIG=Likely_pathogenic"
            );
        });

        ClinVarTabixProvider.ComparatorDiscoveryResult result =
                provider.discoverBoundedComparators(key("G"), 98, 102);

        assertEquals(1, queryCount.get());
        assertEquals("SUCCEEDED", result.getResourceExecutionStatus());
        assertEquals("AVAILABLE", result.getResourceAvailabilityStatus());
        assertEquals("HIT", result.getQueryOutcomeStatus());
        assertEquals("APPLICABLE", result.getResourceApplicabilityStatus());
        assertEquals("READY", result.getReadinessStatus());
        assertNull(result.getReasonCode());
        assertEquals(2, result.getCandidates().size());
        ClinVarTabixProvider.ComparatorCandidate first = result.getCandidates().get(0);
        assertEquals(new NormalizedVariantKey("GRCh38", "1", 100, "A", "C"),
                first.getExactAllele());
        assertEquals(14L, first.getVariationId());
        assertEquals(List.of("SCV000000014.1", "SCV000000015.2"),
                first.getContributingSubmissionIdentifiers());
        assertEquals("ClinVar:VariationID:14", first.getRecordLocator());
        assertFalse(first.getRecordLocator().contains("/"));
        assertFalse(first.getRecordLocator().contains("\\"));
    }

    @Test
    void boundedComparatorDiscoveryRejectsMultipleVariationIdsForOneExactAllele() throws Exception {
        ClinVarTabixProvider provider = boundedProvider((file, region) -> lines(
                "1\t100\t14\tA\tC\t.\tPASS\tCLNSIG=Pathogenic",
                "1\t100\t15\tA\tC\t.\tPASS\tCLNSIG=Likely_pathogenic"
        ));

        ClinVarTabixProvider.ComparatorDiscoveryResult result =
                provider.discoverBoundedComparators(key("G"), 99, 101);

        assertEquals("SUCCEEDED", result.getResourceExecutionStatus());
        assertEquals("HIT", result.getQueryOutcomeStatus());
        assertEquals("NOT_EVALUATED", result.getReadinessStatus());
        assertEquals("CLINVAR_COMPARATOR_ALLELE_AMBIGUOUS", result.getReasonCode());
        assertTrue(result.getCandidates().isEmpty());
    }

    @Test
    void boundedComparatorDiscoveryFailsWhenVariationIdCannotBeParsed() throws Exception {
        ClinVarTabixProvider provider = boundedProvider((file, region) -> lines(
                "1\t100\tVCV000014\tA\tC\t.\tPASS\tCLNSIG=Pathogenic"
        ));

        ClinVarTabixProvider.ComparatorDiscoveryResult result =
                provider.discoverBoundedComparators(key("G"), 99, 101);

        assertEquals("FAILED", result.getResourceExecutionStatus());
        assertEquals("AVAILABLE", result.getResourceAvailabilityStatus());
        assertEquals("HIT", result.getQueryOutcomeStatus());
        assertEquals("APPLICABLE", result.getResourceApplicabilityStatus());
        assertEquals("NOT_EVALUATED", result.getReadinessStatus());
        assertEquals("CLINVAR_VARIATION_ID_INVALID", result.getReasonCode());
        assertTrue(result.getCandidates().isEmpty());
    }

    @Test
    void boundedComparatorDiscoveryRejectsUnsupportedInputsWithoutQuerying() throws Exception {
        AtomicInteger queryCount = new AtomicInteger();
        ClinVarTabixProvider provider = boundedProvider((file, region) -> {
            queryCount.incrementAndGet();
            return Collections.emptyList();
        });

        ClinVarTabixProvider.ComparatorDiscoveryResult assembly =
                provider.discoverBoundedComparators(key("GRCh37", "G"), 99, 101);
        ClinVarTabixProvider.ComparatorDiscoveryResult nonSnv =
                provider.discoverBoundedComparators(
                        new NormalizedVariantKey("GRCh38", "1", 100, "A", "AT"), 99, 101);
        ClinVarTabixProvider.ComparatorDiscoveryResult oversized =
                provider.discoverBoundedComparators(key("G"), 50, 151);

        assertEquals(0, queryCount.get());
        assertEquals("ASSEMBLY_UNSUPPORTED", assembly.getReasonCode());
        assertEquals("SIMPLE_SNV_REQUIRED", nonSnv.getReasonCode());
        assertEquals("BOUNDED_REGION_INVALID", oversized.getReasonCode());
        assertEquals("NOT_EVALUATED", assembly.getReadinessStatus());
        assertEquals("NOT_EVALUATED", nonSnv.getReadinessStatus());
        assertEquals("NOT_EVALUATED", oversized.getReadinessStatus());
    }

    @Test
    void boundedComparatorDiscoveryKeepsSuccessfulEmptyQueryDistinctFromFailure() throws Exception {
        ClinVarTabixProvider emptyProvider = boundedProvider(
                (file, region) -> Collections.emptyList());
        ClinVarTabixProvider failedProvider = boundedProvider((file, region) -> {
            throw new IOException("simulated bounded lookup failure");
        });

        ClinVarTabixProvider.ComparatorDiscoveryResult empty =
                emptyProvider.discoverBoundedComparators(key("G"), 99, 101);
        ClinVarTabixProvider.ComparatorDiscoveryResult failed =
                failedProvider.discoverBoundedComparators(key("G"), 99, 101);

        assertEquals("SUCCEEDED", empty.getResourceExecutionStatus());
        assertEquals("NO_RECORD", empty.getQueryOutcomeStatus());
        assertEquals("READY", empty.getReadinessStatus());
        assertTrue(empty.getCandidates().isEmpty());
        assertEquals("FAILED", failed.getResourceExecutionStatus());
        assertEquals("NOT_QUERIED", failed.getQueryOutcomeStatus());
        assertEquals("NOT_EVALUATED", failed.getReadinessStatus());
        assertEquals("CLINVAR_BOUNDED_QUERY_FAILED", failed.getReasonCode());
    }

    private ClinVarTabixProvider provider(List<String> lines) throws IOException {
        return provider((file, key) -> lines);
    }

    private ClinVarTabixProvider provider(ClinVarTabixProvider.LineLookup lookup) throws IOException {
        Path file = indexedFile("clinvar.vcf.gz");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setVersion("ClinVar tiny GRCh38");
        properties.getClinVar().setGrch38File(file.toString());
        return new ClinVarTabixProvider(properties, lookup);
    }

    private ClinVarTabixProvider boundedProvider(
            ClinVarTabixProvider.BoundedLineLookup lookup) throws IOException {
        Path file = indexedFile("clinvar.bounded.vcf.gz");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setVersion("ClinVar tiny GRCh38");
        properties.getClinVar().setGrch38File(file.toString());
        return new ClinVarTabixProvider(properties, null, lookup);
    }

    private List<String> lines(String... values) {
        return Arrays.asList(values);
    }

    private String pathogenicLine() {
        return "chr1\t100\tVCV000001\tA\tG\t.\tPASS\t"
                + "ALLELEID=1;CLNSIG=Pathogenic;CLNREVSTAT=criteria_provided;"
                + "CLNDN=Disease_A;CLNDISDB=MedGen:C000000;GENEINFO=GENE1:1234;"
                + "CLNHGVS=NC_000001.11:g.100A>G;CLNACC=VCV000001;CLNDATE=2026-01-01";
    }

    private Path indexedFile(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[] {1});
        Files.write(tempDir.resolve(name + ".tbi"), new byte[] {1});
        return file;
    }

    private EvidenceItem evidence(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                  NormalizedVariantKey key) {
        return result.get(key).get(0);
    }

    private NormalizedVariantKey key(String alternate) {
        return key("GRCh38", alternate);
    }

    private NormalizedVariantKey key(String assembly, String alternate) {
        return new NormalizedVariantKey(assembly, "1", 100, "A", alternate);
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
