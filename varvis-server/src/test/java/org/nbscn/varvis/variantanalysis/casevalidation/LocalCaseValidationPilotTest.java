package org.nbscn.varvis.variantanalysis.casevalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.service.PhenotypeAwareRerankingService;
import org.nbscn.varvis.variantanalysis.service.PhenotypeInputNormalizer;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalCaseValidationPilotTest {

    private static final String RUN_ENV = "VARVIS_RUN_LOCAL_CASE_VALIDATION_PILOT";
    private static final String CASE_ENV = "VARVIS_LOCAL_CASE_JSON";
    private static final String OUTPUT_ENV = "VARVIS_LOCAL_CASE_OUTPUT_DIR";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CaseValidationService validationService = new CaseValidationService(objectMapper);
    private final CaseValidationReportWriter reportWriter = new CaseValidationReportWriter(objectMapper);

    @Test
    void localPilotIsDisabledByDefaultAndLocalFolderIsIgnored() throws IOException {
        assertTrue(isLocalFolderIgnored(), "varvis-server/local/ must remain git-ignored for pilot artifacts");
        if (!pilotEnabled()) {
            assertFalse(StringUtils.hasText(System.getenv(CASE_ENV))
                    && StringUtils.hasText(System.getenv(OUTPUT_ENV))
                    && pilotEnabled());
        }
    }

    @Test
    void localPilotCaseValidationSmoke() throws Exception {
        assumeTrue(pilotEnabled(), "Set " + RUN_ENV + "=true to run the local pilot smoke.");

        Path caseJson = requiredExistingPath(CASE_ENV);
        Path outputDir = requiredOutputDirectory(OUTPUT_ENV);
        CaseValidationCase validationCase = validationService.load(caseJson);
        validationService.validateCase(validationCase);

        CaseValidationResult result = validationService.validate(
                validationCase,
                noHpoRun(validationCase),
                withHpoRun(validationCase)
        );
        CaseValidationReportWriter.ReportPaths paths = reportWriter.write(outputDir, result);

        assertTrue(Files.size(paths.getJson()) > 0);
        assertTrue(Files.size(paths.getCsv()) > 0);
        assertTrue(Files.size(paths.getMarkdown()) > 0);
        String markdown = new String(Files.readAllBytes(paths.getMarkdown()), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("No-HPO vs With-HPO Case Validation"));
        assertFalse(markdown.contains(caseJson.toString()), "Report must not expose local case JSON path");
    }

    private CaseValidationRun noHpoRun(CaseValidationCase validationCase) {
        List<VariantInterpretation> variants = pilotVariants(validationCase, false);
        return new CaseValidationRun(
                "local-pilot-no-hpo",
                variants,
                PhenotypeAwareSummary.disabled(),
                0,
                0,
                0,
                "local-pilot-derived",
                "local-pilot-no-real-analysis",
                Collections.singletonList("Local pilot smoke uses a de-identified case JSON and derived tiny candidates.")
        );
    }

    private CaseValidationRun withHpoRun(CaseValidationCase validationCase) {
        List<VariantInterpretation> variants = pilotVariants(validationCase, true);
        PhenotypeAwareSummary summary = enabledReranker().apply(variants, phenotypeSummary(validationCase));
        return new CaseValidationRun(
                "local-pilot-with-hpo",
                variants,
                summary,
                0,
                0,
                0,
                "local-pilot-derived",
                "local-pilot-no-real-analysis",
                Collections.singletonList("Local pilot smoke does not run VEP, Exomiser, LIRICAL, or benchmark analysis.")
        );
    }

    private List<VariantInterpretation> pilotVariants(CaseValidationCase validationCase, boolean withHpo) {
        List<VariantInterpretation> variants = new ArrayList<VariantInterpretation>();
        int index = 0;
        for (CaseValidationCase.TruthVariant truth : validationCase.getTruthVariants()) {
            index++;
            String gene = StringUtils.hasText(truth.getGeneSymbol())
                    ? truth.getGeneSymbol()
                    : firstTruthGene(validationCase);
            variants.add(variant(
                    validationCase.getAssembly(),
                    "local-pilot-decoy-" + index,
                    "chrLocal" + index,
                    100000 + index,
                    "A",
                    "G",
                    "LOCAL_DECOY_" + index,
                    withHpo ? 25.0d : 25.0d
            ));
            variants.add(variant(
                    validationCase.getAssembly(),
                    "local-pilot-truth-" + index,
                    truth.getChrom(),
                    truth.getPos(),
                    truth.getRef(),
                    truth.getAlt(),
                    gene,
                    withHpo ? 20.0d : 20.0d
            ));
        }
        if (variants.isEmpty()) {
            int geneIndex = 0;
            for (CaseValidationCase.TruthGene truthGene : validationCase.getTruthGenes()) {
                geneIndex++;
                variants.add(variant(
                        validationCase.getAssembly(),
                        "local-pilot-gene-truth-" + geneIndex,
                        String.valueOf(geneIndex),
                        200000 + geneIndex,
                        "C",
                        "T",
                        truthGene.getGeneSymbol(),
                        withHpo ? 20.0d : 20.0d
                ));
            }
        }
        return variants;
    }

    private VariantInterpretation variant(String assembly,
                                          String id,
                                          String chrom,
                                          int pos,
                                          String ref,
                                          String alt,
                                          String gene,
                                          double score) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey(assembly, chrom, pos, ref, alt),
                "local-pilot-proband",
                "0/1",
                30,
                90,
                "15,15",
                0.5d,
                "PASS",
                "SNV",
                "local-pilot-redacted-record"
        );
        VariantInterpretation variant = new VariantInterpretation(
                id,
                observation,
                StringUtils.hasText(gene)
                        ? Collections.singletonList(new TranscriptConsequence(
                                gene,
                                "LOCAL_" + gene,
                                "LOCAL_TX_" + gene,
                                "c.1A>G",
                                "p.Mock1",
                                "missense_variant"
                        ))
                        : Collections.<TranscriptConsequence>emptyList(),
                Collections.emptyList()
        );
        variant.applyRanking("REVIEW", score, Collections.singletonMap("base", score),
                Collections.singletonList("local pilot base ranking"), Collections.emptyList());
        return variant;
    }

    private PhenotypeAwareSummary phenotypeSummary(CaseValidationCase validationCase) {
        Set<String> genes = truthGenes(validationCase);
        List<GenePhenotypePrior> priors = new ArrayList<GenePhenotypePrior>();
        int rank = 1;
        for (String gene : genes) {
            priors.add(new GenePhenotypePrior(
                    gene,
                    "LOCAL_" + gene,
                    Math.max(0.1d, 1.0d - ((rank - 1) * 0.01d)),
                    rank,
                    "LOCAL_PILOT_PRIOR",
                    "local-pilot-v1",
                    validationCase.getHpoTerms(),
                    Collections.singletonList("local pilot phenotype-gene support"),
                    GenePhenotypePriorStatus.AVAILABLE,
                    Instant.parse("2026-06-30T00:00:00Z"),
                    Collections.emptyList()
            ));
            rank++;
        }
        return PhenotypeInputNormalizer.summarize(validationCase.getHpoTerms())
                .withGenePriorSummary(new GenePhenotypePriorSummary(
                        true,
                        "LOCAL_PILOT_PRIOR",
                        "local-pilot-v1",
                        priors.isEmpty() ? GenePhenotypePriorStatus.NO_PRIOR : GenePhenotypePriorStatus.AVAILABLE,
                        validationCase.getHpoTerms().size(),
                        validationCase.getHpoTerms().size(),
                        priors.size(),
                        priors,
                        Collections.emptyList(),
                        Instant.parse("2026-06-30T00:00:00Z")
                ));
    }

    private PhenotypeAwareRerankingService enabledReranker() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypeRanking().setEnabled(true);
        properties.getPhenotypeRanking().setMaxScoreBoost(8.0d);
        return new PhenotypeAwareRerankingService(properties);
    }

    private Set<String> truthGenes(CaseValidationCase validationCase) {
        Set<String> genes = new LinkedHashSet<String>();
        for (CaseValidationCase.TruthGene truthGene : validationCase.getTruthGenes()) {
            if (StringUtils.hasText(truthGene.getGeneSymbol())) {
                genes.add(truthGene.getGeneSymbol());
            }
        }
        for (CaseValidationCase.TruthVariant truth : validationCase.getTruthVariants()) {
            if (StringUtils.hasText(truth.getGeneSymbol())) {
                genes.add(truth.getGeneSymbol());
            }
        }
        return genes;
    }

    private String firstTruthGene(CaseValidationCase validationCase) {
        Set<String> genes = truthGenes(validationCase);
        return genes.isEmpty() ? null : genes.iterator().next();
    }

    private Path requiredExistingPath(String envName) {
        String value = System.getenv(envName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(envName + " is required when " + RUN_ENV + "=true");
        }
        Path path = Paths.get(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(envName + " does not point to a readable file: " + path);
        }
        return path;
    }

    private Path requiredOutputDirectory(String envName) throws IOException {
        String value = System.getenv(envName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(envName + " is required when " + RUN_ENV + "=true");
        }
        Path path = Paths.get(value).toAbsolutePath().normalize();
        if (!isUnderLocalFolder(path)) {
            throw new IllegalArgumentException(envName + " must stay under varvis-server/local/");
        }
        Files.createDirectories(path);
        return path;
    }

    private boolean pilotEnabled() {
        return "true".equalsIgnoreCase(System.getenv(RUN_ENV));
    }

    private boolean isLocalFolderIgnored() throws IOException {
        Path gitignore = Paths.get("..", ".gitignore").toAbsolutePath().normalize();
        String content = new String(Files.readAllBytes(gitignore), StandardCharsets.UTF_8);
        return content.contains("varvis-server/local/");
    }

    private boolean isUnderLocalFolder(Path path) {
        Path local = Paths.get("local").toAbsolutePath().normalize();
        return path.startsWith(local);
    }
}
