package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;
import org.nbscn.varvis.variantanalysis.dto.PhenotypeGenePriorLookupResponse;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisVariantPageResponse;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface VariantAnalysisService {

    /** Read saved jobs for identical file bytes; does not attest current execution equivalence. */
    List<VariantAnalysisJob> findHistory(MultipartFile vcfFile) throws IOException;

    VariantAnalysisJob submit(MultipartFile vcfFile,
                              String genomeAssembly,
                              String analysisMode,
                              String sampleId,
                              List<String> hpoTerms) throws IOException;

    VariantAnalysisJob submit(MultipartFile vcfFile,
                              String genomeAssembly,
                              String analysisMode,
                              String sampleId,
                              List<String> hpoTerms,
                              List<String> hpoProvenance) throws IOException;

    VariantAnalysisJob submit(MultipartFile vcfFile,
                              String genomeAssembly,
                              String analysisMode,
                              String sampleId,
                              List<String> hpoTerms,
                              List<String> hpoProvenance,
                              TrioAnalysisInputRequest trioInput,
                              MultipartFile fatherVcfFile,
                              MultipartFile motherVcfFile) throws IOException;

    VariantAnalysisJob submit(MultipartFile vcfFile,
                              String genomeAssembly,
                              String analysisMode,
                              String sampleId,
                              List<String> hpoTerms,
                              List<String> hpoProvenance,
                              List<String> excludedHpoTerms,
                              TrioAnalysisInputRequest trioInput,
                              MultipartFile fatherVcfFile,
                              MultipartFile motherVcfFile) throws IOException;

    default VariantAnalysisJob submit(MultipartFile vcfFile, String genomeAssembly, String analysisMode, String sampleId,
                                     List<String> hpoTerms, List<String> hpoProvenance, List<String> excludedHpoTerms,
                                     TrioAnalysisInputRequest trioInput, MultipartFile fatherVcfFile, MultipartFile motherVcfFile,
                                     String analysisScope) throws IOException {
        return submit(vcfFile, genomeAssembly, analysisMode, sampleId, hpoTerms, hpoProvenance, excludedHpoTerms,
                trioInput, fatherVcfFile, motherVcfFile, analysisScope, false);
    }

    VariantAnalysisJob submit(MultipartFile vcfFile, String genomeAssembly, String analysisMode, String sampleId,
                              List<String> hpoTerms, List<String> hpoProvenance, List<String> excludedHpoTerms,
                              TrioAnalysisInputRequest trioInput, MultipartFile fatherVcfFile, MultipartFile motherVcfFile,
                              String analysisScope, boolean createNewAnalysis) throws IOException;

    VariantAnalysisJob submitBenchmark(MultipartFile vcfFile,
                                       String genomeAssembly,
                                       String analysisMode,
                                       String sampleId,
                                       List<String> hpoTerms,
                                       List<String> hpoProvenance,
                                       BenchmarkTelemetryCollector telemetry) throws IOException;

    VariantAnalysisJob get(String id);

    VariantAnalysisDiagnosticResponse diagnostics(String id);

    List<VariantAnalysisEvent> listEvents(String id);

    VariantInterpretationCounts countInterpretations(String id);

    VariantAnalysisVariantPageResponse listVariants(String id,
                                                    Integer page,
                                                    Integer pageSize,
                                                    VariantInterpretationView view,
                                                    VariantInterpretationFilter filter);

    default VariantAnalysisVariantPageResponse listDistinctVariants(String id, Integer page, Integer pageSize,
            VariantInterpretationView view, VariantInterpretationFilter filter) {
        throw new UnsupportedOperationException("DISTINCT_SELECTION_NOT_AVAILABLE");
    }

    VariantInterpretation getVariant(String id, String variantId);

    VariantInterpretation getCandidate(String id, String candidateId);

    CandidateRankingExplanationResponse explainVariantRanking(String id, String variantId);

    CandidateRankingExplanationResponse explainCandidateRanking(String id, String candidateId);

    PhenotypeGenePriorLookupResponse lookupPhenotypeGenePrior(String id, String gene);

    VariantEnhancementTask submitEnhancement(String id, String provider, Integer candidateLimit);

    VariantEnhancementTask getEnhancement(String id, String enhancementId);

    void delete(String id) throws IOException;
}
