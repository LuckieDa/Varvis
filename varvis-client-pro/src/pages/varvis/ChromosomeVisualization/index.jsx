import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
    Alert,
    Button,
    CircularProgress,
    IconButton,
    Paper,
    Stack,
    Tooltip,
    Typography,
} from '@mui/material';
import HubRoundedIcon from '@mui/icons-material/HubRounded';
import BiotechRoundedIcon from '@mui/icons-material/BiotechRounded';
import TravelExploreRoundedIcon from '@mui/icons-material/TravelExploreRounded';
import SummarizeRoundedIcon from '@mui/icons-material/SummarizeRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import MenuRoundedIcon from '@mui/icons-material/MenuRounded';
import ChevronLeftRoundedIcon from '@mui/icons-material/ChevronLeftRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import { useCytobandData } from './hooks/useCytobandData';
import OverviewIdeogram from './components/OverviewIdeogram';
import ChromosomeDetail from './components/ChromosomeDetail';
import DiseaseDetailDrawer from './components/DiseaseDetailDrawer';
import VariantAnalysisDrawer from './components/VariantAnalysisDrawer';
import { fetchDiseaseDetail, removeCaseVcf, uploadCaseVcf } from './services/cytobands';
import './styles.less';
import './ui-polish.less';

const CHROMOSOMES = [
    '1',
    '2',
    '3',
    '4',
    '5',
    '6',
    '7',
    '8',
    '9',
    '10',
    '11',
    '12',
    '13',
    '14',
    '15',
    '16',
    '17',
    '18',
    '19',
    '20',
    '21',
    '22',
    'X',
    'Y',
];

const formatCaseValue = (value) => {
    if (value == null || value === '') return '-';
    return String(value);
};

const formatCaseCount = (value) => {
    const number = Number(value);
    if (!Number.isFinite(number)) return '-';
    return number.toLocaleString();
};

const ChromosomeVisualization = () => {
    const { loading, error, cytobands, chromosomeSummaries, retry } = useCytobandData();
    const [selectedChr, setSelectedChr] = useState(null);
    const [focusedBand, setFocusedBand] = useState(null);
    const [activeDisease, setActiveDisease] = useState(null);
    const [hoverContext, setHoverContext] = useState(null);
    const [activeCase, setActiveCase] = useState(null);
    const [analysisDrawerOpen, setAnalysisDrawerOpen] = useState(false);
    const [activeOverviewTool, setActiveOverviewTool] = useState(null);
    const [navigationCollapsed, setNavigationCollapsed] = useState(false);
    const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
    const caseGenomeAssembly = 'GRCh38';
    const [caseUploadState, setCaseUploadState] = useState({
        loading: false,
        error: '',
    });
    const [geneSelection, setGeneSelection] = useState(null);
    const [segmentSearchState, setSegmentSearchState] = useState({
        chromosome: null,
        start: '',
        end: '',
        results: [],
        error: '',
        searched: false,
    });
    const [segmentFocus, setSegmentFocus] = useState(null);
    const [geneFocus, setGeneFocus] = useState(null);
    const [variantFocus, setVariantFocus] = useState(null);
    const [visualVariantSelection, setVisualVariantSelection] = useState(null);
    const [focusNotice, setFocusNotice] = useState('');
    const caseFileInputRef = useRef(null);

    useEffect(() => {
        if (!mobileNavigationOpen && !activeOverviewTool) return undefined;
        const handleKeyDown = (event) => {
            if (event.key !== 'Escape' || event.defaultPrevented) return;
            if (mobileNavigationOpen) setMobileNavigationOpen(false);
            else setActiveOverviewTool(null);
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [activeOverviewTool, mobileNavigationOpen]);

    const currentBands = useMemo(
        () => (selectedChr ? cytobands[selectedChr] || [] : []),
        [cytobands, selectedChr],
    );

    const diseaseCounts = useMemo(() => {
        const counts = new Map();
        Object.entries(chromosomeSummaries || {}).forEach(([chr, summary]) => {
            const value = summary?.disease_count ?? summary?.diseaseCount ?? 0;
            counts.set(String(chr), value);
        });
        return CHROMOSOMES.map((ch) => ({
            chromosome: ch,
            count: counts.get(ch) || 0,
        }));
    }, [chromosomeSummaries]);

    const normalizeChromosomeLabel = useCallback((value) => {
        if (value == null) return '';
        const text = String(value).trim().toUpperCase();
        if (!text) return '';
        return text.replace(/^CHR/, '');
    }, []);

    const resolveBandByRange = useCallback(
        ({ chromosome, start, end }) => {
            const chr = normalizeChromosomeLabel(chromosome);
            if (!chr) return null;
            const bands = Array.isArray(cytobands?.[chr]) ? cytobands[chr] : [];
            if (!bands.length) return null;
            if (!Number.isFinite(start) || !Number.isFinite(end)) {
                return bands[0]?.name || null;
            }
            let best = null;
            let bestOverlap = -1;
            const rangeStart = Math.min(start, end);
            const rangeEnd = Math.max(start, end);
            bands.forEach((band) => {
                const bandStart = Number(band?.start);
                const bandEnd = Number(band?.stop);
                if (!Number.isFinite(bandStart) || !Number.isFinite(bandEnd)) return;
                const overlap = Math.min(rangeEnd, bandEnd) - Math.max(rangeStart, bandStart);
                if (overlap >= 0 && overlap > bestOverlap) {
                    best = band;
                    bestOverlap = overlap;
                }
            });
            if (best?.name) return best.name;
            const center = (rangeStart + rangeEnd) / 2;
            const fallback = bands.find((band) => {
                const bandStart = Number(band?.start);
                const bandEnd = Number(band?.stop);
                return (
                    Number.isFinite(bandStart) &&
                    Number.isFinite(bandEnd) &&
                    center >= bandStart &&
                    center <= bandEnd
                );
            });
            return fallback?.name || bands[0]?.name || null;
        },
        [cytobands, normalizeChromosomeLabel],
    );

    const resetToOverview = useCallback(() => {
        setSelectedChr(null);
        setFocusedBand(null);
        setActiveDisease(null);
        setHoverContext(null);
        setGeneFocus(null);
        setVariantFocus(null);
        setFocusNotice('');
    }, []);

    const navigateToChromosome = useCallback((chromosome, bandName = null) => {
        if (!chromosome) return;
        setActiveOverviewTool(null);
        setSelectedChr(chromosome);
        setFocusedBand(bandName || null);
        setActiveDisease(null);
        setHoverContext(null);
    }, []);

    const selectChromosomeOnly = useCallback(
        (ch) => {
            setSegmentFocus(null);
            setGeneFocus(null);
            setVariantFocus(null);
            setFocusNotice('');
            navigateToChromosome(ch, null);
        },
        [navigateToChromosome],
    );

    const handleSegmentNavigate = useCallback(
        ({ chromosome, bandName, segmentRange, notice }) => {
            const start = Number(segmentRange?.start);
            const end = Number(segmentRange?.end);
            setGeneFocus(null);
            setVariantFocus(null);
            setFocusNotice(
                notice === 'matched_without_anchor'
                    ? 'Matched variants were found in this range, but no exact anchor is available yet.'
                    : '',
            );
            if (Number.isFinite(start) && Number.isFinite(end) && end > start) {
                setSegmentFocus({
                    chromosome: String(chromosome),
                    start: Math.round(start),
                    end: Math.round(end),
                    requestId: Date.now(),
                });
            } else {
                setSegmentFocus(null);
            }
            navigateToChromosome(chromosome, bandName || null);
        },
        [navigateToChromosome],
    );

    const handleGeneNavigate = useCallback(
        ({ geneSymbol, chromosome, start, end }) => {
            const chr = normalizeChromosomeLabel(chromosome);
            const normalizedStart = Number(start);
            const normalizedEnd = Number(end);
            if (!chr || !Number.isFinite(normalizedStart) || !Number.isFinite(normalizedEnd)) {
                return;
            }
            const bandName =
                resolveBandByRange({
                    chromosome: chr,
                    start: normalizedStart,
                    end: normalizedEnd,
                }) || null;
            setSegmentFocus(null);
            setVariantFocus(null);
            setFocusNotice('');
            setGeneFocus({
                geneSymbol: String(geneSymbol || '')
                    .trim()
                    .toUpperCase(),
                chromosome: chr,
                start: Math.round(Math.min(normalizedStart, normalizedEnd)),
                end: Math.round(Math.max(normalizedStart, normalizedEnd)),
                bandName,
                requestId: Date.now(),
            });
            navigateToChromosome(chr, bandName);
        },
        [navigateToChromosome, normalizeChromosomeLabel, resolveBandByRange],
    );

    const handleVariantNavigate = useCallback(
        ({ chromosome, bandName, segmentRange, variantAnchor }) => {
            const chr = normalizeChromosomeLabel(chromosome);
            if (!chr) return;
            const focusMode = variantAnchor?.focusMode === 'preview' ? 'preview' : 'locate';
            const startRaw = Number(segmentRange?.start ?? variantAnchor?.start);
            const endRaw = Number(segmentRange?.end ?? variantAnchor?.end);
            const hasRange = Number.isFinite(startRaw) && Number.isFinite(endRaw);
            const normalizedStart = hasRange ? Math.round(Math.min(startRaw, endRaw)) : null;
            const normalizedEnd = hasRange ? Math.round(Math.max(startRaw, endRaw)) : null;
            const targetBand =
                bandName ||
                (hasRange
                    ? resolveBandByRange({
                          chromosome: chr,
                          start: normalizedStart,
                          end: normalizedEnd,
                      })
                    : null);

            setSegmentFocus(null);
            setGeneFocus(null);
            setFocusNotice(
                focusMode === 'preview'
                    ? 'Previewing the selected Analysis Lab candidate. Use Locate for explicit focus.'
                    : 'Located the selected Analysis Lab candidate in the visualization.',
            );
            setVariantFocus({
                chromosome: chr,
                bandName: targetBand || null,
                geneSymbol:
                    String(variantAnchor?.geneSymbol || '')
                        .trim()
                        .toUpperCase() || null,
                start: normalizedStart,
                end: normalizedEnd,
                exactKey: variantAnchor?.exactKey || null,
                analysisVariantId: variantAnchor?.analysisVariantId || null,
                focusMode,
                variantType: variantAnchor?.variantType || null,
                ref: variantAnchor?.ref || null,
                alt: variantAnchor?.alt || null,
                requestId: Date.now(),
            });
            navigateToChromosome(chr, targetBand || null);
        },
        [navigateToChromosome, normalizeChromosomeLabel, resolveBandByRange],
    );

    const handleVariantPreview = useCallback(
        (payload) => handleVariantNavigate(payload),
        [handleVariantNavigate],
    );

    const handleVisualVariantSelect = useCallback((variant) => {
        if (!variant) return;
        setActiveOverviewTool(null);
        setAnalysisDrawerOpen(true);
        setVisualVariantSelection({
            ...variant,
            requestId: Date.now(),
        });
    }, []);

    const handleDiseaseClick = useCallback(async (disease) => {
        if (!disease) return;
        const diseaseId = disease.disease_id || disease.id || disease.orpha || disease.name;
        try {
            const detail = await fetchDiseaseDetail(diseaseId);
            if (detail) {
                setActiveDisease({
                    ...detail,
                    name: detail.disease_name || disease.name,
                    orpha: detail.orpha_number || disease.orpha,
                    locus: detail.locus || disease.locus,
                    locus_type: detail.locus_type || disease.locus_type,
                    definition: detail.definition || detail.definition_text || disease.definition,
                    moi: detail.moi || disease.moi,
                    onset: detail.onset || disease.onset,
                    genes: Array.isArray(detail.genes)
                        ? detail.genes
                              .map((item) => item?.gene_symbol || item?.geneSymbol)
                              .filter(Boolean)
                        : disease.genes || [],
                });
                return;
            }
        } catch (err) {
            // fall through to basic payload
        }
        setActiveDisease(disease);
    }, []);

    const handleCaseUpload = useCallback(
        async (file) => {
            if (!file || caseUploadState.loading) return;
            setCaseUploadState({
                loading: true,
                error: '',
            });
            try {
                const uploaded = await uploadCaseVcf(file, caseGenomeAssembly);
                setActiveCase(uploaded || null);
                setCaseUploadState({
                    loading: false,
                    error: '',
                });
            } catch (uploadError) {
                console.warn('[chromosomeVisualization] failed to upload VCF', uploadError);
                setCaseUploadState({
                    loading: false,
                    error: 'Failed to upload VCF. Please retry.',
                });
            }
        },
        [caseGenomeAssembly, caseUploadState.loading],
    );

    const handleCaseClear = useCallback(async () => {
        if (!activeCase?.caseId || caseUploadState.loading) return;
        const confirmed = window.confirm(
            'Clear current case VCF from the homepage view? This will remove the case overlay from the current page, but it will not delete existing Analysis Lab jobs.',
        );
        if (!confirmed) return;
        setCaseUploadState((prev) => ({
            ...prev,
            loading: true,
            error: '',
        }));
        try {
            await removeCaseVcf(activeCase.caseId);
        } catch (clearError) {
            console.warn('[chromosomeVisualization] failed to clear case', clearError);
        }
        setActiveCase(null);
        setCaseUploadState({
            loading: false,
            error: '',
        });
    }, [activeCase?.caseId, caseUploadState.loading]);

    const hasCaseData = Boolean(activeCase?.caseId);

    const handleTopCaseUploadTrigger = useCallback(() => {
        if (caseUploadState?.loading) return;
        caseFileInputRef.current?.click?.();
    }, [caseUploadState?.loading]);

    const handleTopCaseFileSelected = useCallback(
        async (event) => {
            const file = event?.target?.files?.[0];
            if (!file) return;
            await handleCaseUpload(file);
            if (event?.target) {
                event.target.value = '';
            }
        },
        [handleCaseUpload],
    );

    const toggleOverviewTool = useCallback((toolName) => {
        setActiveOverviewTool((prev) => (prev === toolName ? null : toolName));
        setMobileNavigationOpen(false);
    }, []);

    const handleOverviewToolClick = useCallback(
        (toolName) => {
            if (selectedChr) resetToOverview();
            toggleOverviewTool(toolName);
        },
        [resetToOverview, selectedChr, toggleOverviewTool],
    );

    const openAnalysisDrawer = useCallback(() => {
        setActiveOverviewTool(null);
        setMobileNavigationOpen(false);
        setAnalysisDrawerOpen(true);
    }, []);

    return (
        <Stack className="cv-shell">
            <div className="cv-page-top-nav">
                <input
                    ref={caseFileInputRef}
                    type="file"
                    accept=".vcf,.vcf.gz,.bcf"
                    style={{ display: 'none' }}
                    onChange={handleTopCaseFileSelected}
                />
                <div className="cv-page-top-nav-content">
                    <div className="cv-page-top-brand">
                        <Tooltip title="Open workspace navigation">
                            <IconButton
                                className="cv-mobile-nav-trigger"
                                aria-label="Open workspace navigation"
                                aria-expanded={mobileNavigationOpen}
                                aria-controls="cv-workspace-navigation"
                                onClick={() => setMobileNavigationOpen(true)}
                            >
                                <MenuRoundedIcon />
                            </IconButton>
                        </Tooltip>
                        <span className="cv-page-top-brand-icon-wrap">
                            <HubRoundedIcon className="cv-page-top-brand-icon" />
                        </span>
                        <span className="cv-page-top-brand-copy">
                            <span className="cv-page-top-brand-text">Varvis</span>
                        </span>
                    </div>
                    <div className="cv-page-context" aria-label="Current workspace context">
                        <span className="cv-page-context-label">
                            {selectedChr ? `Chromosome ${selectedChr}` : 'Genome overview'}
                        </span>
                        <span className="cv-page-context-status">
                            {analysisDrawerOpen ? 'Analysis Lab open' : 'Evidence workspace'}
                        </span>
                    </div>
                </div>
            </div>

            <Stack
                direction="row"
                className={`cv-root ${analysisDrawerOpen ? 'has-analysis-workbench' : ''}`}
            >
                <button
                    type="button"
                    className={`cv-navigation-scrim${mobileNavigationOpen ? ' is-visible' : ''}`}
                    aria-label="Close workspace navigation"
                    tabIndex={mobileNavigationOpen ? 0 : -1}
                    onClick={() => setMobileNavigationOpen(false)}
                />
                <aside
                    id="cv-workspace-navigation"
                    className={`cv-page-left-menu${!selectedChr ? ' has-overview-tools' : ''}${
                        navigationCollapsed ? ' is-collapsed' : ''
                    }${mobileNavigationOpen ? ' is-mobile-open' : ''}`}
                    aria-label="Workspace navigation"
                >
                    <div className="cv-navigation-heading">
                        <span>Workspace</span>
                        <Tooltip
                            title={
                                navigationCollapsed ? 'Expand navigation' : 'Collapse navigation'
                            }
                        >
                            <IconButton
                                size="small"
                                className="cv-navigation-collapse"
                                aria-label={
                                    navigationCollapsed
                                        ? 'Expand workspace navigation'
                                        : 'Collapse workspace navigation'
                                }
                                onClick={() => setNavigationCollapsed((current) => !current)}
                            >
                                {navigationCollapsed ? (
                                    <ChevronRightRoundedIcon fontSize="small" />
                                ) : (
                                    <ChevronLeftRoundedIcon fontSize="small" />
                                )}
                            </IconButton>
                        </Tooltip>
                        <IconButton
                            size="small"
                            className="cv-navigation-mobile-close"
                            aria-label="Close workspace navigation"
                            onClick={() => setMobileNavigationOpen(false)}
                        >
                            <CloseRoundedIcon fontSize="small" />
                        </IconButton>
                    </div>
                    <div className="cv-left-tool-group" aria-label="Overview tools">
                        <Tooltip
                            title="Genome Navigator"
                            placement="right"
                            disableHoverListener={!navigationCollapsed}
                        >
                            <Button
                                type="button"
                                className={`cv-left-tool-btn${
                                    activeOverviewTool === 'navigator' ? ' is-active' : ''
                                }`}
                                startIcon={<TravelExploreRoundedIcon />}
                                onClick={() => handleOverviewToolClick('navigator')}
                                disabled={analysisDrawerOpen}
                                aria-pressed={activeOverviewTool === 'navigator'}
                            >
                                <span className="cv-left-tool-label">Genome Navigator</span>
                            </Button>
                        </Tooltip>
                        <Tooltip
                            title="Dataset Summary"
                            placement="right"
                            disableHoverListener={!navigationCollapsed}
                        >
                            <Button
                                type="button"
                                className={`cv-left-tool-btn${
                                    activeOverviewTool === 'summary' ? ' is-active' : ''
                                }`}
                                startIcon={<SummarizeRoundedIcon />}
                                onClick={() => handleOverviewToolClick('summary')}
                                disabled={analysisDrawerOpen}
                                aria-pressed={activeOverviewTool === 'summary'}
                            >
                                <span className="cv-left-tool-label">Dataset Summary</span>
                            </Button>
                        </Tooltip>
                        <Tooltip
                            title="VCF Analysis"
                            placement="right"
                            disableHoverListener={!navigationCollapsed}
                        >
                            <Button
                                type="button"
                                className={`cv-left-tool-btn${
                                    activeOverviewTool === 'vcf' ? ' is-active' : ''
                                }`}
                                startIcon={<BiotechRoundedIcon />}
                                onClick={() => handleOverviewToolClick('vcf')}
                                disabled={analysisDrawerOpen}
                                aria-pressed={activeOverviewTool === 'vcf'}
                            >
                                <span className="cv-left-tool-label">VCF Analysis</span>
                            </Button>
                        </Tooltip>
                    </div>
                </aside>

                <Paper
                    className={`cv-main ${selectedChr ? 'is-detail-view' : 'is-overview-view'}`}
                    square
                >
                    {loading && (
                        <div className="cv-loading cv-loading-state" role="status">
                            <CircularProgress size={28} thickness={4} />
                            <div>
                                <Typography variant="subtitle2">
                                    Loading genome workspace
                                </Typography>
                                <Typography variant="body2" color="text.secondary">
                                    Preparing chromosome and cytoband data.
                                </Typography>
                            </div>
                        </div>
                    )}
                    {error && (
                        <div className="cv-error cv-error-state" role="alert">
                            <Alert severity="error">
                                <Stack gap={1}>
                                    <Typography variant="body2">
                                        Chromosome knowledge-base data could not be loaded. Check
                                        the backend and knowledge-base connection.
                                    </Typography>
                                    <Typography variant="body2">
                                        Open Analysis Lab to check VCF analysis readiness. Its task
                                        and ACMG results do not require the chromosome overview.
                                    </Typography>
                                    <Button variant="contained" onClick={retry} disabled={loading}>
                                        Retry loading chromosomes
                                    </Button>
                                    <Button variant="outlined" onClick={openAnalysisDrawer}>
                                        Open Analysis Lab
                                    </Button>
                                </Stack>
                            </Alert>
                        </div>
                    )}

                    {!loading && !error && !selectedChr && (
                        <OverviewIdeogram
                            cytobands={cytobands}
                            chromosomes={CHROMOSOMES}
                            diseaseCounts={diseaseCounts}
                            initialGeneSelection={geneSelection}
                            onGeneSelectionChange={setGeneSelection}
                            initialSegmentState={segmentSearchState}
                            onSegmentStateChange={setSegmentSearchState}
                            onChromosomeSelect={selectChromosomeOnly}
                            onSegmentNavigate={handleSegmentNavigate}
                            onGeneNavigate={handleGeneNavigate}
                            onVariantNavigate={handleVariantNavigate}
                            activeCase={activeCase}
                            hideAuxiliaryPanels={analysisDrawerOpen}
                            activeToolPanel={
                                !analysisDrawerOpen &&
                                ['navigator', 'summary'].includes(activeOverviewTool)
                                    ? activeOverviewTool
                                    : null
                            }
                            onToolPanelClose={() => setActiveOverviewTool(null)}
                        />
                    )}

                    {!loading &&
                    !error &&
                    !selectedChr &&
                    !analysisDrawerOpen &&
                    activeOverviewTool === 'vcf' ? (
                        <div
                            className="cv-vcf-floating-panel"
                            role="dialog"
                            aria-modal="false"
                            aria-label="VCF Analysis panel"
                        >
                            <div className="cv-vcf-panel-header">
                                <div>
                                    <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                                        VCF Analysis
                                    </Typography>
                                    <Typography variant="caption" color="text.secondary">
                                        Case overlay upload and Analysis Lab entry point.
                                    </Typography>
                                </div>
                                <IconButton
                                    type="button"
                                    size="small"
                                    className="cv-vcf-panel-close"
                                    aria-label="Close VCF Analysis panel"
                                    onClick={() => setActiveOverviewTool(null)}
                                >
                                    <CloseRoundedIcon fontSize="small" />
                                </IconButton>
                            </div>
                            <div className="cv-vcf-panel-body">
                                <section className="cv-vcf-panel-section">
                                    <div className="cv-vcf-section-head">
                                        <Typography variant="subtitle2">Case VCF</Typography>
                                        <span
                                            className={`cv-vcf-status-pill${
                                                hasCaseData ? ' is-loaded' : ''
                                            }`}
                                        >
                                            {hasCaseData ? 'Loaded' : 'No case'}
                                        </span>
                                    </div>
                                    <Typography
                                        className="cv-vcf-helper-text"
                                        variant="body2"
                                        color="text.secondary"
                                    >
                                        Upload a case VCF for homepage visualization overlay.
                                        Analysis Lab ranking uses its own upload workflow.
                                    </Typography>
                                    <div className="cv-vcf-case-status">
                                        {hasCaseData ? (
                                            <>
                                                <div>
                                                    <span>Case ID</span>
                                                    <strong>
                                                        {formatCaseValue(activeCase.caseId)}
                                                    </strong>
                                                </div>
                                                <div>
                                                    <span>File</span>
                                                    <strong>
                                                        {formatCaseValue(activeCase.fileName)}
                                                    </strong>
                                                </div>
                                                <div>
                                                    <span>Genome build</span>
                                                    <strong>
                                                        {formatCaseValue(
                                                            activeCase.genomeAssembly ||
                                                                caseGenomeAssembly,
                                                        )}
                                                    </strong>
                                                </div>
                                                <div>
                                                    <span>Total variants</span>
                                                    <strong>
                                                        {formatCaseCount(activeCase.totalVariants)}
                                                    </strong>
                                                </div>
                                            </>
                                        ) : (
                                            <div className="cv-vcf-empty-status">
                                                No case VCF loaded
                                            </div>
                                        )}
                                    </div>
                                    <Button
                                        type="button"
                                        variant="contained"
                                        disableElevation
                                        className="cv-vcf-panel-primary"
                                        onClick={handleTopCaseUploadTrigger}
                                        disabled={caseUploadState.loading}
                                    >
                                        {caseUploadState.loading
                                            ? 'Uploading...'
                                            : 'Upload case VCF'}
                                    </Button>
                                    {caseUploadState.error ? (
                                        <Typography
                                            className="cv-vcf-upload-error"
                                            variant="caption"
                                            color="error"
                                        >
                                            {caseUploadState.error}
                                        </Typography>
                                    ) : null}
                                </section>
                                <section className="cv-vcf-panel-section">
                                    <Typography variant="subtitle2">Analysis Lab</Typography>
                                    <Typography
                                        className="cv-vcf-helper-text"
                                        variant="body2"
                                        color="text.secondary"
                                    >
                                        Run variant ranking, review evidence, and export candidate
                                        results.
                                    </Typography>
                                    <Button
                                        type="button"
                                        variant="outlined"
                                        startIcon={<BiotechRoundedIcon />}
                                        className="cv-vcf-panel-action"
                                        data-testid="open-analysis-lab-button"
                                        onClick={openAnalysisDrawer}
                                    >
                                        Open Analysis Lab
                                    </Button>
                                </section>
                                <section className="cv-vcf-panel-section cv-vcf-danger-zone">
                                    <Typography variant="subtitle2">Case actions</Typography>
                                    <Typography
                                        className="cv-vcf-helper-text"
                                        variant="body2"
                                        color="text.secondary"
                                    >
                                        Clears only the homepage case overlay. Existing Analysis Lab
                                        jobs are not deleted.
                                    </Typography>
                                    <Button
                                        type="button"
                                        variant="outlined"
                                        className="cv-vcf-clear-btn"
                                        onClick={handleCaseClear}
                                        disabled={!hasCaseData || caseUploadState.loading}
                                    >
                                        Clear case
                                    </Button>
                                </section>
                            </div>
                        </div>
                    ) : null}

                    {!loading && !error && selectedChr && (
                        <ChromosomeDetail
                            key={selectedChr}
                            chromosome={selectedChr}
                            bands={currentBands}
                            hoverContext={hoverContext}
                            focusedBand={focusedBand}
                            segmentFocus={
                                segmentFocus &&
                                String(segmentFocus.chromosome) === String(selectedChr)
                                    ? segmentFocus
                                    : null
                            }
                            geneFocus={
                                geneFocus && String(geneFocus.chromosome) === String(selectedChr)
                                    ? geneFocus
                                    : null
                            }
                            variantFocus={
                                variantFocus &&
                                String(variantFocus.chromosome) === String(selectedChr)
                                    ? variantFocus
                                    : null
                            }
                            navigationNotice={focusNotice}
                            activeCase={activeCase}
                            onVariantSelect={handleVisualVariantSelect}
                            onBack={resetToOverview}
                            onBandFocus={(band) => {
                                setGeneFocus(null);
                                setVariantFocus(null);
                                setFocusNotice('');
                                setFocusedBand(band);
                                setActiveDisease(null);
                                if (!band) {
                                    setHoverContext(null);
                                }
                            }}
                            onDiseaseClick={handleDiseaseClick}
                            onHoverContextChange={setHoverContext}
                        />
                    )}
                </Paper>

                <VariantAnalysisDrawer
                    open={analysisDrawerOpen}
                    onClose={() => setAnalysisDrawerOpen(false)}
                    onVariantNavigate={handleVariantNavigate}
                    onVariantPreview={handleVariantPreview}
                    visualVariantSelection={visualVariantSelection}
                />
            </Stack>

            <DiseaseDetailDrawer disease={activeDisease} onClose={() => setActiveDisease(null)} />
        </Stack>
    );
};

export default ChromosomeVisualization;
