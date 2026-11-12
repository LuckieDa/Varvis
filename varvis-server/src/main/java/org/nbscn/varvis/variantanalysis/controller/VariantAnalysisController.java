package org.nbscn.varvis.variantanalysis.controller;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisExportBundleResponse;
import org.nbscn.varvis.variantanalysis.dto.CandidateRankingExplanationResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisJobResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisHistoryResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisPreflightResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisProviderStatusResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisVariantPageResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantEnhancementTaskResponse;
import org.nbscn.varvis.variantanalysis.dto.PhenotypeGenePriorLookupResponse;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.nbscn.varvis.variantanalysis.dto.VcfSampleDetectionResult;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisExportService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisPreflightService;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisService;
import org.nbscn.varvis.variantanalysis.service.VcfHeaderSampleDetectionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/variant-analyses")
public class VariantAnalysisController {

    private final VariantAnalysisService variantAnalysisService;
    private final VariantAnalysisExportService variantAnalysisExportService;
    private final VariantAnalysisPreflightService variantAnalysisPreflightService;
    private final VcfHeaderSampleDetectionService vcfHeaderSampleDetectionService;
    private final VariantAnalysisProperties properties;

    public VariantAnalysisController(VariantAnalysisService variantAnalysisService,
                                     VariantAnalysisExportService variantAnalysisExportService,
                                     VariantAnalysisPreflightService variantAnalysisPreflightService,
                                     VcfHeaderSampleDetectionService vcfHeaderSampleDetectionService,
                                     VariantAnalysisProperties properties) {
        this.variantAnalysisService = variantAnalysisService;
        this.variantAnalysisExportService = variantAnalysisExportService;
        this.variantAnalysisPreflightService = variantAnalysisPreflightService;
        this.vcfHeaderSampleDetectionService = vcfHeaderSampleDetectionService;
        this.properties = properties;
    }

    @GetMapping("/providers")
    public VariantAnalysisProviderStatusResponse providers() {
        return VariantAnalysisProviderStatusResponse.from(properties);
    }

    @GetMapping("/preflight")
    public VariantAnalysisPreflightResponse preflight(
            @RequestParam(name = "genomeAssembly", defaultValue = "GRCh38") String genomeAssembly) {
        return variantAnalysisPreflightService.check(genomeAssembly);
    }

    @PostMapping(value = "/sample-detection", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VcfSampleDetectionResult detectSamples(@RequestPart("vcfFile") MultipartFile vcfFile) {
        return vcfHeaderSampleDetectionService.detect(vcfFile);
    }

    @PostMapping(value = "/history", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VariantAnalysisHistoryResponse history(@RequestPart("vcfFile") MultipartFile vcfFile) throws IOException {
        return VariantAnalysisHistoryResponse.from(variantAnalysisService.findHistory(vcfFile));
    }

    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VariantAnalysisJobResponse> submit(
            @RequestPart("vcfFile") MultipartFile vcfFile,
            @RequestPart(name = "fatherVcfFile", required = false) MultipartFile fatherVcfFile,
            @RequestPart(name = "motherVcfFile", required = false) MultipartFile motherVcfFile,
            @RequestParam("genomeAssembly") String genomeAssembly,
            @RequestParam("analysisMode") String analysisMode,
            @RequestParam(name = "analysisScope", required = false) String analysisScope,
            @RequestParam(name = "createNewAnalysis", defaultValue = "false") boolean createNewAnalysis,
            @RequestParam(name = "sampleId", required = false) String sampleId,
            @RequestParam(name = "hpoTerms", required = false) List<String> hpoTerms,
            @RequestParam(name = "hpoProvenance", required = false) List<String> hpoProvenance,
            @RequestParam(name = "excludedHpoTerms", required = false) List<String> excludedHpoTerms,
            @RequestParam(name = "trioEnabled", required = false, defaultValue = "false")
                    Boolean trioEnabled,
            @RequestParam(name = "trioInputMode", required = false) String trioInputMode,
            @RequestParam(name = "trioEvidenceOnly", required = false, defaultValue = "true")
                    Boolean trioEvidenceOnly,
            @RequestParam(name = "trioProbandSampleId", required = false) String trioProbandSampleId,
            @RequestParam(name = "trioFatherSampleId", required = false) String trioFatherSampleId,
            @RequestParam(name = "trioMotherSampleId", required = false) String trioMotherSampleId,
            @RequestParam(name = "trioProbandSex", required = false) String trioProbandSex,
            @RequestParam(name = "trioFatherSex", required = false) String trioFatherSex,
            @RequestParam(name = "trioMotherSex", required = false) String trioMotherSex,
            @RequestParam(name = "trioProbandAffected", required = false) Boolean trioProbandAffected,
            @RequestParam(name = "trioFatherAffected", required = false) Boolean trioFatherAffected,
            @RequestParam(name = "trioMotherAffected", required = false) Boolean trioMotherAffected,
            @RequestParam(name = "trioMinDepthForConfirmedRef", required = false)
                    Integer trioMinDepthForConfirmedRef,
            @RequestParam(name = "trioMinGqForConfirmedRef", required = false)
                    Integer trioMinGqForConfirmedRef)
            throws IOException {
        TrioAnalysisInputRequest trioInput = new TrioAnalysisInputRequest(
                Boolean.TRUE.equals(trioEnabled),
                trioInputMode,
                trioEvidenceOnly,
                trioProbandSampleId,
                trioFatherSampleId,
                trioMotherSampleId,
                trioProbandSex,
                trioFatherSex,
                trioMotherSex,
                trioProbandAffected,
                trioFatherAffected,
                trioMotherAffected,
                trioMinDepthForConfirmedRef,
                trioMinGqForConfirmedRef,
                vcfFile != null && !vcfFile.isEmpty(),
                fatherVcfFile != null && !fatherVcfFile.isEmpty(),
                motherVcfFile != null && !motherVcfFile.isEmpty()
        );
        VariantAnalysisJob job = variantAnalysisService.submit(vcfFile, genomeAssembly, analysisMode, sampleId, hpoTerms,
                hpoProvenance, excludedHpoTerms, trioInput, fatherVcfFile, motherVcfFile, analysisScope, createNewAnalysis);
        if (job.getStatus() == VariantAnalysisStatus.READY) {
            return ResponseEntity.ok(VariantAnalysisJobResponse.from(
                    job,
                    variantAnalysisService.countInterpretations(job.getId())
            ));
        }
        return ResponseEntity.accepted().body(VariantAnalysisJobResponse.from(job));
    }

    @GetMapping("/{id}")
    public VariantAnalysisJobResponse get(@PathVariable("id") String id) {
        VariantAnalysisJob job = variantAnalysisService.get(id);
        if (job.getStatus() != VariantAnalysisStatus.READY) {
            return VariantAnalysisJobResponse.from(job);
        }
        return VariantAnalysisJobResponse.from(job, variantAnalysisService.countInterpretations(id));
    }

    @GetMapping("/{id}/diagnostics")
    public VariantAnalysisDiagnosticResponse diagnostics(@PathVariable("id") String id) {
        return variantAnalysisService.diagnostics(id);
    }

    @GetMapping("/{id}/events")
    public List<VariantAnalysisEvent> events(@PathVariable("id") String id) {
        return variantAnalysisService.listEvents(id);
    }

    @GetMapping("/{id}/phenotype-prior")
    public PhenotypeGenePriorLookupResponse lookupPhenotypePriorGene(
            @PathVariable("id") String id,
            @RequestParam("gene") String gene) {
        return variantAnalysisService.lookupPhenotypeGenePrior(id, gene);
    }

    @GetMapping("/{id}/variants")
    public VariantAnalysisVariantPageResponse listVariants(
            @PathVariable("id") String id,
            @RequestParam(name = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "200") Integer pageSize,
            @RequestParam(name = "view", required = false, defaultValue = "CANDIDATES") String view,
            @RequestParam(name = "tier", required = false) String tier,
            @RequestParam(name = "hideFilteredCommon", required = false, defaultValue = "false")
                    Boolean hideFilteredCommon,
            @RequestParam(name = "hideFilteredQc", required = false, defaultValue = "false")
                    Boolean hideFilteredQc,
            @RequestParam(name = "clinvarClass", required = false) String clinvarClass,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "acmgCode", required = false) String acmgCode,
            @RequestParam(name = "acmgStatus", required = false) String acmgStatus,
            @RequestParam(name = "manualReviewRequired", required = false) Boolean manualReviewRequired,
            @RequestParam(name = "hasAcmgSuggestion", required = false) Boolean hasAcmgSuggestion,
            @RequestParam(name = "hasPhenotypeSupport", required = false) Boolean hasPhenotypeSupport,
            @RequestParam(name = "phenotypeGene", required = false) String phenotypeGene,
            @RequestParam(name = "minPhenotypeScore", required = false) Double minPhenotypeScore,
            @RequestParam(name = "analysisBucket", required = false) String analysisBucket,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "summaryOnly", required = false) Boolean summaryOnly,
            @RequestParam(name = "largeFileMode", required = false) Boolean largeFileMode,
            @RequestParam(name = "expensiveAnnotationSkipped", required = false)
                    Boolean expensiveAnnotationSkipped) {
        return variantAnalysisService.listVariants(
                id,
                page,
                pageSize,
                parseView(view),
                parseFilter(
                        tier,
                        hideFilteredCommon,
                        hideFilteredQc,
                        clinvarClass,
                        search,
                        acmgCode,
                        acmgStatus,
                        manualReviewRequired,
                        hasAcmgSuggestion,
                        hasPhenotypeSupport,
                        phenotypeGene,
                        minPhenotypeScore,
                        mergeAnalysisBuckets(analysisBucket, bucket),
                        summaryOnly,
                        largeFileMode,
                        expensiveAnnotationSkipped
                )
        );
    }

    @GetMapping("/{id}/variants/grouped")
    public VariantAnalysisVariantPageResponse listDistinctVariants(
            @PathVariable("id") String id,
            @RequestParam(name = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(name = "pageSize", required = false, defaultValue = "200") Integer pageSize,
            @RequestParam(name = "view", required = false, defaultValue = "CANDIDATES") String view,
            @RequestParam(name = "tier", required = false) String tier,
            @RequestParam(name = "hideFilteredCommon", required = false, defaultValue = "false")
                    Boolean hideFilteredCommon,
            @RequestParam(name = "hideFilteredQc", required = false, defaultValue = "false")
                    Boolean hideFilteredQc,
            @RequestParam(name = "clinvarClass", required = false) String clinvarClass,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "acmgCode", required = false) String acmgCode,
            @RequestParam(name = "acmgStatus", required = false) String acmgStatus,
            @RequestParam(name = "manualReviewRequired", required = false) Boolean manualReviewRequired,
            @RequestParam(name = "hasAcmgSuggestion", required = false) Boolean hasAcmgSuggestion,
            @RequestParam(name = "hasPhenotypeSupport", required = false) Boolean hasPhenotypeSupport,
            @RequestParam(name = "phenotypeGene", required = false) String phenotypeGene,
            @RequestParam(name = "minPhenotypeScore", required = false) Double minPhenotypeScore,
            @RequestParam(name = "analysisBucket", required = false) String analysisBucket,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "summaryOnly", required = false) Boolean summaryOnly,
            @RequestParam(name = "largeFileMode", required = false) Boolean largeFileMode,
            @RequestParam(name = "expensiveAnnotationSkipped", required = false)
                    Boolean expensiveAnnotationSkipped) {
        return variantAnalysisService.listDistinctVariants(
                id,
                page,
                pageSize,
                parseView(view),
                parseFilter(
                        tier,
                        hideFilteredCommon,
                        hideFilteredQc,
                        clinvarClass,
                        search,
                        acmgCode,
                        acmgStatus,
                        manualReviewRequired,
                        hasAcmgSuggestion,
                        hasPhenotypeSupport,
                        phenotypeGene,
                        minPhenotypeScore,
                        mergeAnalysisBuckets(analysisBucket, bucket),
                        summaryOnly,
                        largeFileMode,
                        expensiveAnnotationSkipped
                )
        );
    }

    public VariantAnalysisVariantPageResponse listVariants(
            String id,
            Integer page,
            Integer pageSize,
            String view,
            String tier,
            Boolean hideFilteredCommon,
            Boolean hideFilteredQc,
            String clinvarClass,
            String search,
            String acmgCode,
            String acmgStatus,
            Boolean manualReviewRequired,
            Boolean hasAcmgSuggestion,
            Boolean hasPhenotypeSupport,
            String phenotypeGene,
            Double minPhenotypeScore,
            String analysisBucket,
            Boolean summaryOnly,
            Boolean largeFileMode,
            Boolean expensiveAnnotationSkipped) {
        return listVariants(
                id,
                page,
                pageSize,
                view,
                tier,
                hideFilteredCommon,
                hideFilteredQc,
                clinvarClass,
                search,
                acmgCode,
                acmgStatus,
                manualReviewRequired,
                hasAcmgSuggestion,
                hasPhenotypeSupport,
                phenotypeGene,
                minPhenotypeScore,
                analysisBucket,
                null,
                summaryOnly,
                largeFileMode,
                expensiveAnnotationSkipped
        );
    }

    @GetMapping(value = "/{id}/exports/csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(
            @PathVariable("id") String id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "view", required = false, defaultValue = "CANDIDATES") String view,
            @RequestParam(name = "tier", required = false) String tier,
            @RequestParam(name = "hideFilteredCommon", required = false, defaultValue = "false")
                    Boolean hideFilteredCommon,
            @RequestParam(name = "hideFilteredQc", required = false, defaultValue = "false")
                    Boolean hideFilteredQc,
            @RequestParam(name = "clinvarClass", required = false) String clinvarClass,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "acmgCode", required = false) String acmgCode,
            @RequestParam(name = "acmgStatus", required = false) String acmgStatus,
            @RequestParam(name = "manualReviewRequired", required = false) Boolean manualReviewRequired,
            @RequestParam(name = "hasAcmgSuggestion", required = false) Boolean hasAcmgSuggestion,
            @RequestParam(name = "hasPhenotypeSupport", required = false) Boolean hasPhenotypeSupport,
            @RequestParam(name = "phenotypeGene", required = false) String phenotypeGene,
            @RequestParam(name = "minPhenotypeScore", required = false) Double minPhenotypeScore,
            @RequestParam(name = "analysisBucket", required = false) String analysisBucket,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "summaryOnly", required = false) Boolean summaryOnly,
            @RequestParam(name = "largeFileMode", required = false) Boolean largeFileMode,
            @RequestParam(name = "expensiveAnnotationSkipped", required = false)
                    Boolean expensiveAnnotationSkipped) {
        String csv = variantAnalysisExportService.exportCsv(
                id,
                limit,
                parseView(view),
                parseFilter(
                        tier,
                        hideFilteredCommon,
                        hideFilteredQc,
                        clinvarClass,
                        search,
                        acmgCode,
                        acmgStatus,
                        manualReviewRequired,
                        hasAcmgSuggestion,
                        hasPhenotypeSupport,
                        phenotypeGene,
                        minPhenotypeScore,
                        mergeAnalysisBuckets(analysisBucket, bucket),
                        summaryOnly,
                        largeFileMode,
                        expensiveAnnotationSkipped
                )
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(id, view, "csv"))
                .body(csv);
    }

    public ResponseEntity<String> exportCsv(
            String id,
            Integer limit,
            String view,
            String tier,
            Boolean hideFilteredCommon,
            Boolean hideFilteredQc,
            String clinvarClass,
            String search,
            String acmgCode,
            String acmgStatus,
            Boolean manualReviewRequired,
            Boolean hasAcmgSuggestion,
            Boolean hasPhenotypeSupport,
            String phenotypeGene,
            Double minPhenotypeScore,
            String analysisBucket,
            Boolean summaryOnly,
            Boolean largeFileMode,
            Boolean expensiveAnnotationSkipped) {
        return exportCsv(
                id,
                limit,
                view,
                tier,
                hideFilteredCommon,
                hideFilteredQc,
                clinvarClass,
                search,
                acmgCode,
                acmgStatus,
                manualReviewRequired,
                hasAcmgSuggestion,
                hasPhenotypeSupport,
                phenotypeGene,
                minPhenotypeScore,
                analysisBucket,
                null,
                summaryOnly,
                largeFileMode,
                expensiveAnnotationSkipped
        );
    }

    @GetMapping(value = "/{id}/exports/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VariantAnalysisExportBundleResponse> exportJson(
            @PathVariable("id") String id,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "view", required = false, defaultValue = "CANDIDATES") String view,
            @RequestParam(name = "tier", required = false) String tier,
            @RequestParam(name = "hideFilteredCommon", required = false, defaultValue = "false")
                    Boolean hideFilteredCommon,
            @RequestParam(name = "hideFilteredQc", required = false, defaultValue = "false")
                    Boolean hideFilteredQc,
            @RequestParam(name = "clinvarClass", required = false) String clinvarClass,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "acmgCode", required = false) String acmgCode,
            @RequestParam(name = "acmgStatus", required = false) String acmgStatus,
            @RequestParam(name = "manualReviewRequired", required = false) Boolean manualReviewRequired,
            @RequestParam(name = "hasAcmgSuggestion", required = false) Boolean hasAcmgSuggestion,
            @RequestParam(name = "hasPhenotypeSupport", required = false) Boolean hasPhenotypeSupport,
            @RequestParam(name = "phenotypeGene", required = false) String phenotypeGene,
            @RequestParam(name = "minPhenotypeScore", required = false) Double minPhenotypeScore,
            @RequestParam(name = "analysisBucket", required = false) String analysisBucket,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "summaryOnly", required = false) Boolean summaryOnly,
            @RequestParam(name = "largeFileMode", required = false) Boolean largeFileMode,
            @RequestParam(name = "expensiveAnnotationSkipped", required = false)
                    Boolean expensiveAnnotationSkipped) {
        VariantAnalysisExportBundleResponse bundle = variantAnalysisExportService.exportJson(
                id,
                limit,
                parseView(view),
                parseFilter(
                        tier,
                        hideFilteredCommon,
                        hideFilteredQc,
                        clinvarClass,
                        search,
                        acmgCode,
                        acmgStatus,
                        manualReviewRequired,
                        hasAcmgSuggestion,
                        hasPhenotypeSupport,
                        phenotypeGene,
                        minPhenotypeScore,
                        mergeAnalysisBuckets(analysisBucket, bucket),
                        summaryOnly,
                        largeFileMode,
                        expensiveAnnotationSkipped
                )
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.APPLICATION_JSON_VALUE))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(id, view, "json"))
                .body(bundle);
    }

    public ResponseEntity<VariantAnalysisExportBundleResponse> exportJson(
            String id,
            Integer limit,
            String view,
            String tier,
            Boolean hideFilteredCommon,
            Boolean hideFilteredQc,
            String clinvarClass,
            String search,
            String acmgCode,
            String acmgStatus,
            Boolean manualReviewRequired,
            Boolean hasAcmgSuggestion,
            Boolean hasPhenotypeSupport,
            String phenotypeGene,
            Double minPhenotypeScore,
            String analysisBucket,
            Boolean summaryOnly,
            Boolean largeFileMode,
            Boolean expensiveAnnotationSkipped) {
        return exportJson(
                id,
                limit,
                view,
                tier,
                hideFilteredCommon,
                hideFilteredQc,
                clinvarClass,
                search,
                acmgCode,
                acmgStatus,
                manualReviewRequired,
                hasAcmgSuggestion,
                hasPhenotypeSupport,
                phenotypeGene,
                minPhenotypeScore,
                analysisBucket,
                null,
                summaryOnly,
                largeFileMode,
                expensiveAnnotationSkipped
        );
    }

    private VariantInterpretationView parseView(String view) {
        try {
            return VariantInterpretationView.from(view);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported variant list view: " + view);
        }
    }

    private VariantInterpretationFilter parseFilter(String tier,
                                                    Boolean hideFilteredCommon,
                                                    Boolean hideFilteredQc,
                                                    String clinvarClass,
                                                    String search,
                                                    String acmgCode,
                                                    String acmgStatus,
                                                    Boolean manualReviewRequired,
                                                    Boolean hasAcmgSuggestion,
                                                    Boolean hasPhenotypeSupport,
                                                    String phenotypeGene,
                                                    Double minPhenotypeScore,
                                                    String analysisBucket,
                                                    Boolean summaryOnly,
                                                    Boolean largeFileMode,
                                                    Boolean expensiveAnnotationSkipped) {
        if (clinvarClass != null && !clinvarClass.trim().isEmpty()) {
            String normalized = clinvarClass.trim().toUpperCase();
            if (!"PLP".equals(normalized) && !"VUS".equals(normalized)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported clinvarClass: " + clinvarClass
                );
            }
        }
        if (acmgStatus != null && !acmgStatus.trim().isEmpty()) {
            String normalized = acmgStatus.trim().toUpperCase();
            if (!"SUGGESTED".equals(normalized)
                    && !"NOT_EVALUATED".equals(normalized)
                    && !"NOT_APPLICABLE".equals(normalized)
                    && !"UNAVAILABLE".equals(normalized)
                    && !"CONFLICTING".equals(normalized)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported acmgStatus: " + acmgStatus
                    );
            }
        }
        if (minPhenotypeScore != null
                && (Double.isNaN(minPhenotypeScore) || Double.isInfinite(minPhenotypeScore))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported minPhenotypeScore: " + minPhenotypeScore
            );
        }
        validateAnalysisBucket(analysisBucket);
        return new VariantInterpretationFilter(
                tier,
                Boolean.TRUE.equals(hideFilteredCommon),
                Boolean.TRUE.equals(hideFilteredQc),
                clinvarClass,
                search,
                acmgCode,
                acmgStatus,
                manualReviewRequired,
                hasAcmgSuggestion,
                hasPhenotypeSupport,
                phenotypeGene,
                minPhenotypeScore,
                analysisBucket,
                summaryOnly,
                largeFileMode,
                expensiveAnnotationSkipped
        );
    }

    private void validateAnalysisBucket(String analysisBucket) {
        if (analysisBucket == null || analysisBucket.trim().isEmpty()) {
            return;
        }
        for (String token : analysisBucket.split(",")) {
            String value = token == null ? "" : token.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                VariantAnalysisBucket.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported analysisBucket: " + value
                );
            }
        }
    }

    private String mergeAnalysisBuckets(String analysisBucket, String bucket) {
        String normalizedAnalysisBucket =
                analysisBucket == null ? "" : analysisBucket.trim();
        String normalizedBucket = bucket == null ? "" : bucket.trim();
        boolean hasAnalysisBucket = !normalizedAnalysisBucket.isEmpty();
        boolean hasBucket = !normalizedBucket.isEmpty();
        if (hasAnalysisBucket && hasBucket) {
            return normalizedAnalysisBucket + "," + normalizedBucket;
        }
        if (hasAnalysisBucket) {
            return normalizedAnalysisBucket;
        }
        return hasBucket ? normalizedBucket : null;
    }

    private String contentDisposition(String id, String view, String extension) {
        String safeId = id == null || id.length() < 8 ? "analysis" : id.substring(0, 8);
        String safeView = view == null ? "candidates" : view.toLowerCase().replaceAll("[^a-z0-9-]+", "-");
        return "attachment; filename=\"varvis-" + safeId + "-" + safeView + "-top-export." + extension + "\"";
    }

    @GetMapping("/{id}/variants/{variantId}")
    public VariantInterpretation getVariant(
            @PathVariable("id") String id,
            @PathVariable("variantId") String variantId) {
        return variantAnalysisService.getVariant(id, variantId);
    }

    @GetMapping("/{id}/candidates/{candidateId}")
    public VariantInterpretation getCandidate(
            @PathVariable("id") String id,
            @PathVariable("candidateId") String candidateId) {
        return variantAnalysisService.getCandidate(id, candidateId);
    }

    @GetMapping("/{id}/variants/{variantId}/ranking-explanation")
    public CandidateRankingExplanationResponse explainVariantRanking(
            @PathVariable("id") String id,
            @PathVariable("variantId") String variantId) {
        return variantAnalysisService.explainVariantRanking(id, variantId);
    }

    @GetMapping("/{id}/candidates/{candidateId}/ranking-explanation")
    public CandidateRankingExplanationResponse explainCandidateRanking(
            @PathVariable("id") String id,
            @PathVariable("candidateId") String candidateId) {
        return variantAnalysisService.explainCandidateRanking(id, candidateId);
    }

    @PostMapping("/{id}/enhancements")
    public ResponseEntity<VariantEnhancementTaskResponse> submitEnhancement(
            @PathVariable("id") String id,
            @RequestParam(name = "provider", required = false, defaultValue = "ALPHAGENOME") String provider,
            @RequestParam(name = "candidateLimit", required = false) Integer candidateLimit) {
        return ResponseEntity.accepted().body(VariantEnhancementTaskResponse.from(
                variantAnalysisService.submitEnhancement(id, provider, candidateLimit)
        ));
    }

    @GetMapping("/{id}/enhancements/{enhancementId}")
    public VariantEnhancementTaskResponse getEnhancement(
            @PathVariable("id") String id,
            @PathVariable("enhancementId") String enhancementId) {
        return VariantEnhancementTaskResponse.from(variantAnalysisService.getEnhancement(id, enhancementId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws IOException {
        variantAnalysisService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
