import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Box, Button, Stack, Tooltip, Typography } from '@mui/material';
import Ideogram from 'ideogram';
import {
    bandSummary,
    buildBandLookup,
    buildChrBands,
    getBandDiseases,
    resolveBandRange,
} from '../utils/bandHelpers';
import BandZoom from './BandZoom';
import GeneZoom from './GeneZoom';
import LocusZoom from './VariantZoom';
import {
    buildBandGenes,
    deriveSpanFromVariants,
    extractGenesFromDisease,
    normalizeVariantRecords,
    pickImportantGenes,
    withGeneCoordinateHints,
} from '../utils/geneHelpers';
import { fetchVariantsByGene } from '../services/geneZoom';
import {
    fetchBandDiseases,
    fetchBandGenes,
    fetchAllBandDiseaseCounts,
    fetchBandDiseaseCountsByChromosome,
    fetchDiseaseGenes,
    fetchGeneCoordinates,
    fetchCaseSmallVariantsByRegion,
    fetchCaseStructuralVariantsByRegion,
    fetchCaseOverlapBandsByChromosome,
    fetchKnowledgeSmallVariantsByRegion,
    fetchKnowledgeStructuralVariantsByRegion,
} from '../services/cytobands';

const SCALE_CANDIDATES = [10, 20, 25, 50, 100, 200, 250, 500, 1000];
const PREFETCH_IMPORTANT_GENE_LIMIT = 10;
const DISEASE_GENE_ENRICH_CONCURRENCY = 2;
const DISEASE_GENE_ENRICH_LIMIT = 12;
const CHR_DISEASE_PREFETCH_CONCURRENCY = 1;
const GENE_COORDINATE_RETRY_LIMIT = 1;
const GENE_COORDINATE_LOADING_MIN_MS = 1200;
const FALLBACK_GENE_MIN_SPAN_BP = 5000;
const CASE_REGION_PAGE_SIZE = 5000;
const CASE_GENE_CONCURRENCY = 2;
const LINKED_DISEASE_MAX_LOCUS_ITEMS = 5;
const LINKED_DISEASE_MAX_GENE_ITEMS = 6;

const toFiniteNumber = (value) => {
    if (value == null || value === '') return NaN;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : NaN;
};

const normalizeRangeWithMinSpan = ({
    start,
    stop,
    minSpan = 1,
    bandStart = NaN,
    bandStop = NaN,
}) => {
    if (!Number.isFinite(start) || !Number.isFinite(stop)) return null;
    let nextStart = Math.min(start, stop);
    let nextStop = Math.max(start, stop);
    const hasBandRange =
        Number.isFinite(bandStart) && Number.isFinite(bandStop) && bandStop > bandStart;
    const bandSpan = hasBandRange ? Math.max(1, bandStop - bandStart) : Number.POSITIVE_INFINITY;
    const safeMinSpan = Math.max(1, Math.min(minSpan, bandSpan));
    if (nextStop - nextStart < safeMinSpan) {
        const center = (nextStart + nextStop) / 2;
        nextStart = center - safeMinSpan / 2;
        nextStop = nextStart + safeMinSpan;
    }
    if (hasBandRange) {
        if (nextStart < bandStart) {
            const shift = bandStart - nextStart;
            nextStart += shift;
            nextStop += shift;
        }
        if (nextStop > bandStop) {
            const shift = nextStop - bandStop;
            nextStart -= shift;
            nextStop -= shift;
        }
        nextStart = Math.max(bandStart, nextStart);
        nextStop = Math.min(bandStop, nextStop);
        if (nextStop - nextStart < 1) {
            const anchor = Math.max(bandStart, Math.min(bandStop, (nextStart + nextStop) / 2));
            nextStart = Math.max(bandStart, anchor - safeMinSpan / 2);
            nextStop = Math.min(bandStop, nextStart + safeMinSpan);
            if (nextStop - nextStart < 1) {
                nextStart = Math.max(bandStart, Math.min(anchor, bandStop - 1));
                nextStop = Math.min(bandStop, nextStart + 1);
            }
        }
    }
    const roundedStart = Math.round(nextStart);
    const roundedStop = Math.max(roundedStart + 1, Math.round(nextStop));
    return { start: roundedStart, stop: roundedStop };
};

const runWithConcurrency = async (items, limit, worker) => {
    if (!items.length) return [];
    const results = new Array(items.length);
    let cursor = 0;
    const concurrency = Math.max(1, Math.min(limit, items.length));
    const runners = Array.from({ length: concurrency }).map(async () => {
        let running = true;
        while (running) {
            const idx = cursor;
            cursor += 1;
            if (idx >= items.length) {
                running = false;
            } else {
                results[idx] = await worker(items[idx], idx);
            }
        }
    });
    await Promise.all(runners);
    return results;
};

const normalizeGeneSymbolKey = (value) =>
    String(value || '')
        .trim()
        .toUpperCase();
const formatBpLabel = (value) =>
    Number.isFinite(Number(value)) ? `${Math.round(Number(value)).toLocaleString()} bp` : '--';
const overlapsRange = (startA, stopA, startB, stopB) =>
    Number.isFinite(startA) &&
    Number.isFinite(stopA) &&
    Number.isFinite(startB) &&
    Number.isFinite(stopB) &&
    Math.max(startA, startB) <= Math.min(stopA, stopB);

const normalizeChromosomeKey = (value) =>
    String(value || '')
        .trim()
        .toUpperCase()
        .replace(/^CHR/, '');
const normalizeAlleleKey = (value) =>
    String(value || '')
        .trim()
        .toUpperCase();
const normalizeSvTypeKey = (value) =>
    String(value || '')
        .trim()
        .toUpperCase()
        .replace(/\s+/g, '_');

const isUnknownClinicalText = (value) => {
    const text = String(value || '')
        .trim()
        .toLowerCase();
    return (
        !text || text === 'not classified' || text === 'unknown' || text === '-' || text === '--'
    );
};

const buildSmallExactMatchKey = (row, chromosomeFallback = '') => {
    const chr = normalizeChromosomeKey(row?.chromosome || chromosomeFallback);
    const position = toFiniteNumber(row?.position);
    const ref = normalizeAlleleKey(row?.ref);
    const alt = normalizeAlleleKey(row?.alt);
    if (!chr || !Number.isFinite(position) || !ref || !alt) return '';
    return `${chr}:${Math.round(position)}:${ref}:${alt}`;
};

const resolveStructuralBounds = (row) => {
    const start = toFiniteNumber(
        row?.start ??
            row?.start_bp ??
            row?.startBp ??
            row?.raw?.start ??
            row?.raw?.start_bp ??
            row?.raw?.startBp ??
            row?.position,
    );
    const end = toFiniteNumber(
        row?.end ??
            row?.end_bp ??
            row?.endBp ??
            row?.raw?.end ??
            row?.raw?.end_bp ??
            row?.raw?.endBp ??
            start,
    );
    if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
    return {
        start: Math.round(Math.min(start, end)),
        end: Math.round(Math.max(start, end)),
    };
};

const buildStructuralExactMatchKey = (row, chromosomeFallback = '') => {
    const chr = normalizeChromosomeKey(row?.chromosome || chromosomeFallback);
    const bounds = resolveStructuralBounds(row);
    if (!chr || !bounds) return '';
    const svType = normalizeSvTypeKey(
        row?.svType ||
            row?.sv_type ||
            row?.variantType ||
            row?.variant_type ||
            row?.type ||
            row?.raw?.svType ||
            row?.raw?.sv_type ||
            row?.raw?.variantType ||
            row?.raw?.variant_type,
    );
    return `${chr}:${bounds.start}:${bounds.end}:${svType}`;
};

const buildVariantExactMatchKey = (row, chromosomeFallback = '') => {
    const smallKey = buildSmallExactMatchKey(row, chromosomeFallback);
    if (smallKey) return smallKey;
    return buildStructuralExactMatchKey(row, chromosomeFallback);
};

const isKnowledgeMatchedVariant = (item) => {
    const matchedRaw = item?.knowledgeMatched;
    if (matchedRaw != null) return matchedRaw === true;
    const patientOnlyRaw = item?.patientOnly;
    if (patientOnlyRaw != null) return patientOnlyRaw !== true;
    return false;
};

const syncMatchedCaseVariantsToKnowledge = (knowledgeRows, caseRows, chromosomeFallback = '') => {
    const knowledgeList = Array.isArray(knowledgeRows) ? knowledgeRows : [];
    const caseList = Array.isArray(caseRows) ? caseRows : [];
    if (!caseList.length) return knowledgeList;

    const existingKeys = new Set();
    knowledgeList.forEach((item) => {
        const key = buildVariantExactMatchKey(item, chromosomeFallback);
        if (key) existingKeys.add(key);
    });

    const appended = [];
    caseList.forEach((item) => {
        const matchedRaw = item?.knowledgeMatched;
        const patientOnlyRaw = item?.patientOnly;
        const matched =
            matchedRaw != null
                ? Boolean(matchedRaw)
                : patientOnlyRaw != null
                  ? !patientOnlyRaw
                  : false;
        if (!matched) return;

        const key = buildVariantExactMatchKey(item, chromosomeFallback);
        if (!key || existingKeys.has(key)) return;
        existingKeys.add(key);
        appended.push({
            ...item,
            dataSource: 'knowledge',
            patientOnly: false,
            knowledgeMatched: true,
            clinicalSignificance:
                item?.knowledgeClinicalSignificance ||
                item?.clinicalSignificance ||
                item?.clinical_significance ||
                item?.clinSigSimple ||
                null,
        });
    });

    if (!appended.length) return knowledgeList;
    return [...knowledgeList, ...appended];
};

const ChromosomeDetail = ({
    chromosome,
    bands,
    hoverContext,
    focusedBand,
    segmentFocus,
    geneFocus,
    variantFocus,
    navigationNotice,
    activeCase,
    onBack,
    onBandFocus,
    onLayerChange,
    onVariantSelect,
    _onDiseaseClick,
    onHoverContextChange,
}) => {
    const hostRef = useRef(null);
    const hoverBandRef = useRef(null);
    const legendRef = useRef(null);
    const svgRef = useRef(null);
    const containerId = `cv-ideo-detail-${chromosome}`;
    const [ribbonBand, setRibbonBand] = useState('Band: --');
    const [cursorPos, setCursorPos] = useState(null);
    const [scaleInfo, setScaleInfo] = useState({ width: 120, label: '50 Mb' });
    const [hoverPreview, setHoverPreview] = useState(null);
    const [focusLayer, setFocusLayer] = useState('band');
    const [selectedGene, setSelectedGene] = useState(null);
    const [pendingGeneFocus, setPendingGeneFocus] = useState(null);
    const [pendingVariantFocus, setPendingVariantFocus] = useState(null);
    const [preferredVariantExactKey, setPreferredVariantExactKey] = useState('');
    const [variantState, setVariantState] = useState({
        loading: false,
        error: '',
        variants: [],
        knowledgeVariants: [],
        caseVariants: [],
    });
    const [locusWindowRange, setLocusWindowRange] = useState(null);
    const [geneCoordinateMap, setGeneCoordinateMap] = useState(new Map());
    const variantCacheRef = useRef(new Map());
    const activeVariantRequestRef = useRef(0);
    const activeGeneCoordinateRequestRef = useRef(0);
    const geneCoordinateCacheRef = useRef(new Map());
    const geneCoordinateBandRef = useRef(new Set());
    const geneCoordinateMissingRef = useRef(new Set());
    const geneCoordinateRetryAttemptsRef = useRef(new Map());
    const geneCoordinateRetryTimerRef = useRef(null);
    const geneSpanLoadingStartRef = useRef(0);
    const [geneCoordinateRetryNonce, setGeneCoordinateRetryNonce] = useState(0);
    const [bandDiseasesMap, setBandDiseasesMap] = useState({});
    const [bandGenesMap, setBandGenesMap] = useState({});
    const [bandGenesReadyMap, setBandGenesReadyMap] = useState({});
    const [bandSummaryMap, setBandSummaryMap] = useState({});
    const [focusedBandLoading, setFocusedBandLoading] = useState(false);
    const [focusedBandGeneLoading, setFocusedBandGeneLoading] = useState(false);
    const [focusedBandGeneRows, setFocusedBandGeneRows] = useState([]);
    const [caseChromosomeState, setCaseChromosomeState] = useState({
        loading: false,
        error: '',
        variants: [],
    });
    const [caseGeneTrackState, setCaseGeneTrackState] = useState({
        loading: false,
        error: '',
        genes: [],
    });
    const [geneSpanPrefetchState, setGeneSpanPrefetchState] = useState({
        loading: false,
        total: 0,
        loaded: 0,
        failed: 0,
    });
    const bandDiseasesRef = useRef({});
    const bandGenesRef = useRef({});
    const bandSummaryRef = useRef({});
    const bandDiseasesLoadingRef = useRef(new Set());
    const bandDiseasesPromiseRef = useRef(new Map());
    const bandGenesLoadingRef = useRef(new Set());
    const bandGenesPromiseRef = useRef(new Map());
    const bandDiseasesErrorRef = useRef(new Map());
    const diseaseGeneCacheRef = useRef(new Map());
    const diseaseGeneLoadingRef = useRef(new Set());
    const hoverDiseaseTimerRef = useRef(null);
    const diseaseBandCountsRef = useRef(new Map());
    const caseBandCountsRef = useRef(new Map());
    const [caseOverlapBandCounts, setCaseOverlapBandCounts] = useState(new Map());
    const caseOverlapBandCountsRef = useRef(new Map());
    const diseaseMarkerSignatureRef = useRef('');
    const caseChromosomeVariantCacheRef = useRef(new Map());
    const activateHoverRef = useRef(null);
    const setRibbonBandTextRef = useRef(null);
    const onBandFocusRef = useRef(onBandFocus);
    const chrSpanRef = useRef({ start: 0, stop: 0 });
    const updateScaleRef = useRef(null);
    const chrBandsRef = useRef({});
    const effectiveCase = activeCase || null;
    const hasCaseData = Boolean(effectiveCase?.caseId);
    const segmentRange = useMemo(() => {
        const start = Number(segmentFocus?.start);
        const end = Number(segmentFocus?.end);
        if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null;
        return {
            start: Math.round(Math.min(start, end)),
            end: Math.round(Math.max(start, end)),
        };
    }, [segmentFocus]);

    useEffect(() => {
        if (typeof onLayerChange !== 'function') return;
        if (!focusedBand) {
            onLayerChange('chromosome');
            return;
        }
        onLayerChange(focusLayer === 'locus' ? 'locus' : focusLayer === 'gene' ? 'gene' : 'band');
    }, [focusLayer, focusedBand, onLayerChange]);

    useEffect(() => {
        bandDiseasesRef.current = bandDiseasesMap;
    }, [bandDiseasesMap]);

    useEffect(() => {
        bandSummaryRef.current = bandSummaryMap;
    }, [bandSummaryMap]);

    useEffect(() => {
        bandGenesRef.current = bandGenesMap;
    }, [bandGenesMap]);

    useEffect(() => {
        setBandGenesMap({});
        setBandGenesReadyMap({});
        setFocusedBandGeneRows([]);
        setPendingGeneFocus(null);
        setPendingVariantFocus(null);
        setPreferredVariantExactKey('');
        setCaseChromosomeState({
            loading: false,
            error: '',
            variants: [],
        });
        setCaseGeneTrackState({
            loading: false,
            error: '',
            genes: [],
        });
        setCaseOverlapBandCounts(new Map());
        caseBandCountsRef.current = new Map();
        caseOverlapBandCountsRef.current = new Map();
        caseChromosomeVariantCacheRef.current = new Map();
        bandGenesLoadingRef.current = new Set();
        bandGenesPromiseRef.current = new Map();
        bandDiseasesLoadingRef.current = new Set();
        bandDiseasesPromiseRef.current = new Map();
        if (hoverDiseaseTimerRef.current) {
            clearTimeout(hoverDiseaseTimerRef.current);
            hoverDiseaseTimerRef.current = null;
        }
        if (geneCoordinateRetryTimerRef.current) {
            clearTimeout(geneCoordinateRetryTimerRef.current);
            geneCoordinateRetryTimerRef.current = null;
        }
        geneCoordinateRetryAttemptsRef.current = new Map();
    }, [chromosome]);

    const normalizedBands = useMemo(
        () =>
            (bands || []).map((band) => {
                const { start, stop } = resolveBandRange(band);
                return {
                    ...band,
                    start,
                    stop,
                };
            }),
        [bands],
    );
    const bandStructureKey = useMemo(
        () =>
            normalizedBands
                .map((item) => `${item?.name ?? ''}:${item?.start ?? ''}-${item?.stop ?? ''}`)
                .join('|'),
        [normalizedBands],
    );
    const chrBands = useMemo(
        () => buildChrBands(chromosome, normalizedBands),
        [normalizedBands, chromosome],
    );
    const bandLookup = useMemo(() => buildBandLookup(normalizedBands), [normalizedBands]);
    const resolveFocusedBandByRange = useCallback(
        (startValue, endValue) => {
            const start = toFiniteNumber(startValue);
            const end = toFiniteNumber(endValue);
            if (!Number.isFinite(start) || !Number.isFinite(end) || !normalizedBands.length) {
                return null;
            }
            const rangeStart = Math.min(start, end);
            const rangeEnd = Math.max(start, end);
            let bestBand = null;
            let bestOverlap = -1;
            normalizedBands.forEach((band) => {
                const bandStart = toFiniteNumber(band?.start);
                const bandStop = toFiniteNumber(band?.stop);
                if (!Number.isFinite(bandStart) || !Number.isFinite(bandStop)) return;
                const overlap = Math.min(rangeEnd, bandStop) - Math.max(rangeStart, bandStart);
                if (overlap >= 0 && overlap > bestOverlap) {
                    bestBand = band;
                    bestOverlap = overlap;
                }
            });
            if (bestBand?.name) return bestBand.name;
            const center = (rangeStart + rangeEnd) / 2;
            const centerBand = normalizedBands.find((band) => {
                const bandStart = toFiniteNumber(band?.start);
                const bandStop = toFiniteNumber(band?.stop);
                return (
                    Number.isFinite(bandStart) &&
                    Number.isFinite(bandStop) &&
                    center >= bandStart &&
                    center <= bandStop
                );
            });
            return centerBand?.name || normalizedBands[0]?.name || null;
        },
        [normalizedBands],
    );
    useEffect(() => {
        chrBandsRef.current = chrBands || {};
    }, [chrBands]);
    const focusedBandData = useMemo(
        () => (focusedBand ? normalizedBands.find((b) => b.name === focusedBand) || null : null),
        [focusedBand, normalizedBands],
    );
    const focusedBandId = focusedBandData?.bandId;
    const focusedBandWithAll = useMemo(
        () => (focusedBandData ? { ...focusedBandData, allBands: normalizedBands } : null),
        [normalizedBands, focusedBandData],
    );
    const focusedBandMeta = useMemo(() => bandSummary(focusedBandData), [focusedBandData]);
    const isFocusedView = Boolean(focusedBandData);
    const focusedBandDiseases = useMemo(
        () => getBandDiseases({ band: focusedBandData, bandDiseases: bandDiseasesMap }),
        [focusedBandData, bandDiseasesMap],
    );
    const focusedBandDiseaseCount = useMemo(() => {
        if (!focusedBandData?.bandId) return focusedBandDiseases.length;
        const summaryCount = resolveDiseaseCount(bandSummaryMap?.[focusedBandData.bandId]);
        if (Number.isFinite(summaryCount)) return summaryCount;
        return focusedBandDiseases.length;
    }, [bandSummaryMap, focusedBandData?.bandId, focusedBandDiseases.length]);
    const baseFocusedBandGenes = useMemo(() => {
        const fallbackGenes = buildBandGenes(focusedBandDiseases, focusedBandMeta);
        if (Array.isArray(focusedBandGeneRows) && focusedBandGeneRows.length) {
            const bandStart = Number(focusedBandMeta?.start);
            const bandStop = Number(focusedBandMeta?.stop);
            const hasBandRange =
                Number.isFinite(bandStart) && Number.isFinite(bandStop) && bandStop > bandStart;
            const slotSpan = hasBandRange
                ? (bandStop - bandStart) / focusedBandGeneRows.length
                : null;
            const estimatedMinSpan = Number.isFinite(slotSpan)
                ? Math.max(FALLBACK_GENE_MIN_SPAN_BP, Math.round(slotSpan * 0.25))
                : FALLBACK_GENE_MIN_SPAN_BP;
            const fallbackMap = new Map(
                fallbackGenes.map((item) => {
                    const key = (item?.name || item?.gene || '').toString().trim().toUpperCase();
                    return [key, item];
                }),
            );
            const merged = focusedBandGeneRows
                .map((row, index) => {
                    const symbol = (
                        row?.geneSymbol ||
                        row?.gene_symbol ||
                        row?.geneName ||
                        row?.gene_name ||
                        ''
                    )
                        .toString()
                        .trim()
                        .toUpperCase();
                    if (!symbol) return null;
                    const fallback = fallbackMap.get(symbol) || null;
                    const rawStart = toFiniteNumber(row?.startBp ?? row?.start_bp ?? row?.start);
                    const rawStop = toFiniteNumber(row?.endBp ?? row?.end_bp ?? row?.stop);
                    const estimatedRowStart = hasBandRange
                        ? Math.round(bandStart + index * slotSpan + slotSpan * 0.15)
                        : NaN;
                    const estimatedRowStop = hasBandRange
                        ? Math.round(bandStart + (index + 1) * slotSpan - slotSpan * 0.15)
                        : NaN;
                    const estimatedStart = toFiniteNumber(fallback?.start);
                    const estimatedStop = toFiniteNumber(fallback?.stop);
                    const hasRowCoordinate = Number.isFinite(rawStart) && Number.isFinite(rawStop);
                    const estimateRange = normalizeRangeWithMinSpan({
                        start: Number.isFinite(estimatedStart) ? estimatedStart : estimatedRowStart,
                        stop: Number.isFinite(estimatedStop) ? estimatedStop : estimatedRowStop,
                        minSpan: estimatedMinSpan,
                        bandStart,
                        bandStop,
                    });
                    const rowRange = hasRowCoordinate
                        ? normalizeRangeWithMinSpan({
                              start: rawStart,
                              stop: rawStop,
                              minSpan: estimatedMinSpan,
                              bandStart,
                              bandStop,
                          })
                        : null;
                    let start = estimateRange?.start;
                    let stop = estimateRange?.stop;
                    let coordinateMode = fallback?.coordinateMode || 'estimated_band_slot';
                    if (rowRange) {
                        const rawSpan = Math.abs(rawStop - rawStart);
                        const rowOverlapsBand =
                            !hasBandRange ||
                            (Math.max(rawStart, rawStop) >= bandStart &&
                                Math.min(rawStart, rawStop) <= bandStop);
                        const rowLooksReliable =
                            rowOverlapsBand &&
                            rawSpan >= Math.max(2, Math.round(estimatedMinSpan * 0.1));
                        if (rowLooksReliable || !estimateRange) {
                            start = rowRange.start;
                            stop = rowRange.stop;
                            coordinateMode = 'band_gene_row_fallback';
                        }
                    }
                    if (!Number.isFinite(start) || !Number.isFinite(stop)) return null;
                    const rowDiseaseCount = Number(row?.diseaseCount ?? row?.disease_count ?? NaN);
                    return {
                        id: row?.geneId ?? fallback?.id ?? `${symbol}-${index + 1}`,
                        name: symbol,
                        gene: symbol,
                        diseaseCount: Number.isFinite(rowDiseaseCount)
                            ? rowDiseaseCount
                            : Number(fallback?.diseaseCount ?? 0) || 0,
                        start,
                        stop,
                        coordinateMode,
                        bandRowStart: Number.isFinite(rawStart) ? rawStart : null,
                        bandRowStop: Number.isFinite(rawStop) ? rawStop : null,
                        estimatedStart: Number.isFinite(estimateRange?.start)
                            ? estimateRange.start
                            : null,
                        estimatedStop: Number.isFinite(estimateRange?.stop)
                            ? estimateRange.stop
                            : null,
                    };
                })
                .filter(Boolean);
            const seen = new Set(
                merged.map((item) =>
                    (item?.name || item?.gene || '').toString().trim().toUpperCase(),
                ),
            );
            const extras = fallbackGenes.filter((item) => {
                const key = (item?.name || item?.gene || '').toString().trim().toUpperCase();
                if (!key) return false;
                return !seen.has(key);
            });
            const combined = [...merged, ...extras];
            if (!combined.length) return fallbackGenes;
            return combined.sort((a, b) => {
                const delta = (Number(b?.diseaseCount) || 0) - (Number(a?.diseaseCount) || 0);
                if (delta !== 0) return delta;
                return String(a?.name || a?.gene || '').localeCompare(
                    String(b?.name || b?.gene || ''),
                );
            });
        }
        return fallbackGenes;
    }, [focusedBandDiseases, focusedBandGeneRows, focusedBandMeta]);
    const baseFocusedBandGenesWithCoord = useMemo(
        () => withGeneCoordinateHints(baseFocusedBandGenes, geneCoordinateMap),
        [baseFocusedBandGenes, geneCoordinateMap],
    );
    const focusedBandGenes = useMemo(
        () => baseFocusedBandGenesWithCoord,
        [baseFocusedBandGenesWithCoord],
    );
    const importantGeneSymbols = useMemo(
        () =>
            pickImportantGenes(baseFocusedBandGenes, PREFETCH_IMPORTANT_GENE_LIMIT)
                .map((item) => (item?.name || item?.gene || '').toString().trim().toUpperCase())
                .filter(Boolean),
        [baseFocusedBandGenes],
    );
    const genesForActiveView = useMemo(() => focusedBandGenes, [focusedBandGenes]);
    const caseGenesForCompare = useMemo(
        () => (Array.isArray(caseGeneTrackState?.genes) ? caseGeneTrackState.genes : []),
        [caseGeneTrackState?.genes],
    );
    const caseHitGeneSymbols = useMemo(() => {
        const next = new Set();
        caseGenesForCompare.forEach((item) => {
            const matchedVariantCount = Number(
                item?.matchedVariantCount ?? item?.knowledgeMatchedCount ?? 0,
            );
            const symbol = String(item?.name || item?.gene || '')
                .trim()
                .toUpperCase();
            if (!symbol) return;
            if (!Number.isFinite(matchedVariantCount) || matchedVariantCount <= 0) return;
            next.add(symbol);
        });
        return next;
    }, [caseGenesForCompare]);
    const importantGeneSymbolsForActiveView = useMemo(
        () =>
            pickImportantGenes(genesForActiveView, PREFETCH_IMPORTANT_GENE_LIMIT)
                .map((item) => (item?.name || item?.gene || '').toString().trim().toUpperCase())
                .filter(Boolean),
        [genesForActiveView],
    );
    const coordinatePrefetchSymbols = useMemo(() => {
        const genesBySymbol = new Map(
            baseFocusedBandGenes.map((item) => {
                const key = (item?.name || item?.gene || '').toString().trim().toUpperCase();
                return [key, item];
            }),
        );
        const seed = [];
        importantGeneSymbols.forEach((symbol) => {
            if (symbol) seed.push(symbol);
        });
        if (selectedGene) {
            const key = String(selectedGene || '')
                .trim()
                .toUpperCase();
            if (key) seed.push(key);
        }
        const candidates = seed.length ? seed : Array.from(genesBySymbol.keys());
        const symbols = candidates
            .map((symbol) =>
                String(symbol || '')
                    .trim()
                    .toUpperCase(),
            )
            .filter(Boolean)
            .filter((symbol) => {
                const item = genesBySymbol.get(symbol);
                if (!item) return false;
                const mode = String(item?.coordinateMode || '').toLowerCase();
                return mode !== 'gene_coordinate';
            });
        return Array.from(new Set(symbols)).sort((a, b) => a.localeCompare(b));
    }, [baseFocusedBandGenes, importantGeneSymbols, selectedGene]);
    const coordinatePrefetchKey = useMemo(
        () => coordinatePrefetchSymbols.map(normalizeGeneSymbolKey).filter(Boolean).join('|'),
        [coordinatePrefetchSymbols],
    );
    const geneSpanTotal = Number(geneSpanPrefetchState?.total) || 0;
    const geneSpanLoaded = Number(geneSpanPrefetchState?.loaded) || 0;
    const geneSpanFailed = Number(geneSpanPrefetchState?.failed) || 0;
    const geneSpanLoading = Boolean(geneSpanPrefetchState?.loading);
    const geneSpanCompleted =
        geneSpanTotal === 0 ? !geneSpanLoading : geneSpanLoaded + geneSpanFailed >= geneSpanTotal;
    const locusEntryLocked =
        focusLayer === 'gene' &&
        (focusedBandGeneLoading ||
            !geneSpanCompleted ||
            geneSpanLoading ||
            !genesForActiveView.length);
    const effectiveGeneSpanPrefetchState = geneSpanPrefetchState;
    const selectedGeneData = useMemo(() => {
        if (!selectedGene) return null;
        const normalized = String(selectedGene || '')
            .trim()
            .toUpperCase();
        if (!normalized) return null;
        const findBySymbol = (rows) =>
            (Array.isArray(rows) ? rows : []).find((item) => {
                const symbol = String(item?.name || item?.gene || '')
                    .trim()
                    .toUpperCase();
                return symbol && symbol === normalized;
            }) || null;
        return (
            findBySymbol(genesForActiveView) ||
            findBySymbol(focusedBandGenes) ||
            findBySymbol(caseGenesForCompare) ||
            null
        );
    }, [caseGenesForCompare, focusedBandGenes, genesForActiveView, selectedGene]);
    useEffect(() => {
        if (!selectedGene) return;
        if (selectedGeneData) return;
        if (focusLayer === 'locus') return;
        setSelectedGene(null);
    }, [focusLayer, selectedGene, selectedGeneData]);
    const locusHeaderRange = useMemo(() => {
        const stateStart = Number(locusWindowRange?.start);
        const stateStop = Number(locusWindowRange?.stop);
        if (Number.isFinite(stateStart) && Number.isFinite(stateStop) && stateStop >= stateStart) {
            return { start: stateStart, stop: stateStop };
        }
        const geneStart = Number(selectedGeneData?.locusStart ?? selectedGeneData?.start);
        const geneStop = Number(selectedGeneData?.locusStop ?? selectedGeneData?.stop);
        if (Number.isFinite(geneStart) && Number.isFinite(geneStop) && geneStop >= geneStart) {
            return { start: geneStart, stop: geneStop };
        }
        return null;
    }, [
        locusWindowRange?.start,
        locusWindowRange?.stop,
        selectedGeneData?.locusStart,
        selectedGeneData?.locusStop,
        selectedGeneData?.start,
        selectedGeneData?.stop,
    ]);
    const focusHeaderTitle = useMemo(() => {
        const base = `Chr ${chromosome} - Band ${focusedBandData?.name || '--'}`;
        if (focusLayer === 'gene') return `${base} - Key genes`;
        if (focusLayer !== 'locus') return base;
        const geneLabel =
            selectedGene || selectedGeneData?.name || selectedGeneData?.gene || 'Gene';
        const startLabel = formatBpLabel(locusHeaderRange?.start);
        const stopLabel = formatBpLabel(locusHeaderRange?.stop);
        return `${base} - ${geneLabel} - Window: ${startLabel} - ${stopLabel}`;
    }, [
        chromosome,
        focusLayer,
        focusedBandData?.name,
        locusHeaderRange?.start,
        locusHeaderRange?.stop,
        selectedGene,
        selectedGeneData?.gene,
        selectedGeneData?.name,
    ]);
    const diseaseBandCounts = useMemo(() => {
        const counts = new Map();
        normalizedBands.forEach((band) => {
            if (!band) return;
            if (band.chromosome && String(band.chromosome) !== String(chromosome)) return;
            const key = String(band.name || '').toLowerCase();
            if (!key) return;
            const summary = bandSummaryMap?.[band.bandId];
            const summaryCount = resolveDiseaseCount(summary);
            if (Number.isFinite(summaryCount)) {
                counts.set(key, summaryCount);
                return;
            }
            const diseases = getBandDiseases({ band, bandDiseases: bandDiseasesMap }) || [];
            counts.set(key, diseases.length);
        });
        return counts;
    }, [bandDiseasesMap, bandSummaryMap, chromosome, normalizedBands]);
    useEffect(() => {
        diseaseBandCountsRef.current = diseaseBandCounts;
    }, [diseaseBandCounts]);
    const caseBandCounts = useMemo(() => {
        const counts = new Map();
        if (!hasCaseData) return counts;
        const variants = Array.isArray(caseChromosomeState?.variants)
            ? caseChromosomeState.variants
            : [];
        if (!variants.length) return counts;
        normalizedBands.forEach((band) => {
            const key = String(band?.name || '').toLowerCase();
            if (key) counts.set(key, 0);
        });
        variants.forEach((row) => {
            const start = toFiniteNumber(row?.position ?? row?.start_bp ?? row?.startBp);
            const end = toFiniteNumber(
                row?.end_bp ?? row?.endBp ?? row?.position ?? row?.start_bp ?? row?.startBp,
            );
            if (!Number.isFinite(start) || !Number.isFinite(end)) return;
            normalizedBands.forEach((band) => {
                const bandStart = toFiniteNumber(band?.start);
                const bandStop = toFiniteNumber(band?.stop);
                if (!overlapsRange(start, end, bandStart, bandStop)) return;
                const key = String(band?.name || '').toLowerCase();
                if (!key) return;
                counts.set(key, (counts.get(key) || 0) + 1);
            });
        });
        return counts;
    }, [caseChromosomeState?.variants, hasCaseData, normalizedBands]);
    useEffect(() => {
        caseBandCountsRef.current = caseBandCounts;
    }, [caseBandCounts]);

    useEffect(() => {
        caseOverlapBandCountsRef.current = caseOverlapBandCounts;
    }, [caseOverlapBandCounts]);

    useEffect(() => {
        const caseId = effectiveCase?.caseId;
        if (!hasCaseData || !caseId || !chromosome) {
            setCaseOverlapBandCounts(new Map());
            return undefined;
        }
        let cancelled = false;
        fetchCaseOverlapBandsByChromosome({
            caseId,
            chromosome,
        })
            .then((rows) => {
                if (cancelled) return;
                const next = new Map();
                (Array.isArray(rows) ? rows : []).forEach((item) => {
                    const key = String(item?.bandName || item?.band_name || '')
                        .trim()
                        .toLowerCase();
                    if (!key) return;
                    const count = Number(item?.overlapCount ?? item?.overlap_count ?? 0);
                    if (Number.isFinite(count) && count > 0) {
                        next.set(key, count);
                    }
                });
                setCaseOverlapBandCounts(next);
            })
            .catch((error) => {
                if (cancelled) return;
                console.warn('[chromosomeDetail] failed to load case overlap bands', error);
                setCaseOverlapBandCounts(new Map());
            });
        return () => {
            cancelled = true;
        };
    }, [chromosome, effectiveCase?.caseId, hasCaseData]);

    const focusedBandCaseVariantCount = useMemo(() => {
        if (!focusedBandData?.name) return 0;
        return Number(caseBandCounts.get(String(focusedBandData.name).toLowerCase()) || 0);
    }, [caseBandCounts, focusedBandData?.name]);
    const focusedBandCaseOverlapCount = useMemo(() => {
        if (!focusedBandData?.name) return 0;
        return Number(caseOverlapBandCounts.get(String(focusedBandData.name).toLowerCase()) || 0);
    }, [caseOverlapBandCounts, focusedBandData?.name]);
    const chrSpan = useMemo(() => {
        const valid = normalizedBands
            .map((item) => [Number(item?.start), Number(item?.stop)])
            .filter(([start, stop]) => Number.isFinite(start) && Number.isFinite(stop));
        if (!valid.length) return { start: 0, stop: 0 };
        return {
            start: Math.min(...valid.map(([start]) => start)),
            stop: Math.max(...valid.map(([, stop]) => stop)),
        };
    }, [normalizedBands]);
    useEffect(() => {
        chrSpanRef.current = chrSpan;
    }, [chrSpan]);
    const segmentSummaryText = useMemo(() => {
        if (!segmentRange) return '';
        const span = Math.max(1, segmentRange.end - segmentRange.start + 1);
        return `Located segment: ${segmentRange.start.toLocaleString()} - ${segmentRange.end.toLocaleString()} bp (${span.toLocaleString()} bp)`;
    }, [segmentRange]);

    useEffect(() => {
        const requestId = Number(geneFocus?.requestId);
        const geneSymbol = normalizeGeneSymbolKey(geneFocus?.geneSymbol || geneFocus?.symbol);
        if (!requestId || !geneSymbol) return;
        const start = toFiniteNumber(geneFocus?.start);
        const end = toFiniteNumber(geneFocus?.end);
        const bandByRange =
            Number.isFinite(start) && Number.isFinite(end)
                ? resolveFocusedBandByRange(start, end)
                : null;
        const targetBand = bandByRange || (geneFocus?.bandName ? String(geneFocus.bandName) : null);
        setPendingVariantFocus(null);
        setPreferredVariantExactKey('');
        setPendingGeneFocus((prev) => {
            if (prev?.requestId === requestId) return prev;
            return {
                requestId,
                geneSymbol,
                start,
                end,
                bandName: targetBand,
            };
        });
        if (targetBand && focusedBandData?.name !== targetBand) {
            onBandFocusRef.current?.(targetBand);
        }
    }, [
        focusedBandData?.name,
        geneFocus?.bandName,
        geneFocus?.geneSymbol,
        geneFocus?.requestId,
        geneFocus?.start,
        geneFocus?.symbol,
        geneFocus?.end,
        resolveFocusedBandByRange,
    ]);

    useEffect(() => {
        const requestId = Number(variantFocus?.requestId);
        const geneSymbol = normalizeGeneSymbolKey(variantFocus?.geneSymbol || variantFocus?.symbol);
        if (!requestId || !geneSymbol) return;
        const start = toFiniteNumber(variantFocus?.start);
        const end = toFiniteNumber(variantFocus?.end);
        const bandByRange =
            Number.isFinite(start) && Number.isFinite(end)
                ? resolveFocusedBandByRange(start, end)
                : null;
        const targetBand =
            bandByRange || (variantFocus?.bandName ? String(variantFocus.bandName) : null);
        setPendingGeneFocus(null);
        setPendingVariantFocus((prev) => {
            if (prev?.requestId === requestId) return prev;
            return {
                requestId,
                geneSymbol,
                start,
                end,
                bandName: targetBand,
                exactKey: variantFocus?.exactKey || null,
                ref: variantFocus?.ref || null,
                alt: variantFocus?.alt || null,
                variantType: variantFocus?.variantType || null,
            };
        });
        if (targetBand && focusedBandData?.name !== targetBand) {
            onBandFocusRef.current?.(targetBand);
        }
    }, [
        focusedBandData?.name,
        resolveFocusedBandByRange,
        variantFocus?.bandName,
        variantFocus?.exactKey,
        variantFocus?.geneSymbol,
        variantFocus?.ref,
        variantFocus?.alt,
        variantFocus?.requestId,
        variantFocus?.start,
        variantFocus?.symbol,
        variantFocus?.variantType,
        variantFocus?.end,
    ]);

    useEffect(() => {
        if (!pendingGeneFocus?.requestId) return;
        if (!focusedBandData) return;
        const targetBand = pendingGeneFocus.bandName;
        if (targetBand && focusedBandData.name !== targetBand) return;
        let cancelled = false;
        const activateGeneFocus = async () => {
            const geneSymbol = normalizeGeneSymbolKey(pendingGeneFocus.geneSymbol);
            if (!geneSymbol) {
                if (!cancelled) setPendingGeneFocus(null);
                return;
            }
            setSelectedGene(geneSymbol);
            setLocusWindowRange(null);
            setFocusLayer('gene');
            const bandId = focusedBandData?.bandId;
            if (bandId) {
                try {
                    const rows = await ensureBandGenes(bandId);
                    if (!cancelled && Array.isArray(rows) && rows.length) {
                        setFocusedBandGeneRows(rows);
                    }
                } catch (error) {
                    // keep focus transition even if gene rows fail to load
                }
            }
            if (!cancelled) {
                setPendingGeneFocus(null);
            }
        };
        activateGeneFocus();
        return () => {
            cancelled = true;
        };
    }, [ensureBandGenes, focusedBandData, pendingGeneFocus]);

    useEffect(() => {
        if (!pendingVariantFocus?.requestId) return;
        if (!focusedBandData) return;
        const targetBand = pendingVariantFocus.bandName;
        if (targetBand && focusedBandData.name !== targetBand) return;
        let cancelled = false;
        const activateVariantFocus = async () => {
            const geneSymbol = normalizeGeneSymbolKey(pendingVariantFocus.geneSymbol);
            if (!geneSymbol) {
                if (!cancelled) setPendingVariantFocus(null);
                return;
            }

            const requestedStart = toFiniteNumber(pendingVariantFocus.start);
            const requestedEnd = toFiniteNumber(pendingVariantFocus.end);
            if (Number.isFinite(requestedStart) && Number.isFinite(requestedEnd)) {
                setLocusWindowRange({
                    start: Math.round(Math.min(requestedStart, requestedEnd)),
                    stop: Math.round(Math.max(requestedStart, requestedEnd)),
                });
            } else {
                setLocusWindowRange(null);
            }
            setSelectedGene(geneSymbol);
            setFocusLayer('gene');
            setPreferredVariantExactKey(String(pendingVariantFocus.exactKey || '').trim());

            let geneRecord = null;
            const bandId = focusedBandData?.bandId;
            if (bandId) {
                try {
                    const rows = await ensureBandGenes(bandId);
                    if (!cancelled && Array.isArray(rows) && rows.length) {
                        setFocusedBandGeneRows(rows);
                    }
                    if (Array.isArray(rows) && rows.length) {
                        geneRecord =
                            rows.find((row) => {
                                const symbol = normalizeGeneSymbolKey(
                                    row?.geneSymbol ||
                                        row?.gene_symbol ||
                                        row?.geneName ||
                                        row?.gene_name ||
                                        row?.name ||
                                        row?.gene,
                                );
                                return symbol === geneSymbol;
                            }) || null;
                    }
                } catch (error) {
                    // Continue with locus transition even if gene list fetch fails.
                }
            }

            if (cancelled) return;
            setFocusLayer('locus');
            try {
                await loadGeneVariants(
                    geneSymbol,
                    geneRecord,
                    hasCaseData ? 'combined' : 'knowledge',
                );
            } catch (error) {
                // loadGeneVariants already sets fallback error state.
            } finally {
                if (!cancelled) {
                    setPendingVariantFocus(null);
                }
            }
        };
        activateVariantFocus();
        return () => {
            cancelled = true;
        };
    }, [ensureBandGenes, focusedBandData, hasCaseData, loadGeneVariants, pendingVariantFocus]);

    const loadCaseVariantsInRegion = useCallback(
        async ({ chromosomeValue, start, end }) => {
            const caseId = effectiveCase?.caseId;
            if (!caseId || !chromosomeValue) return [];
            const cacheKey = `${caseId}:${chromosomeValue}:${start}:${end}`;
            const cached = caseChromosomeVariantCacheRef.current.get(cacheKey);
            if (cached) return cached;

            const pageSize = CASE_REGION_PAGE_SIZE;
            const collectPaged = async (fetcher) => {
                let page = 1;
                const rows = [];
                let keep = true;
                while (keep) {
                    // eslint-disable-next-line no-await-in-loop
                    const chunk = await fetcher(page, pageSize);
                    const safe = Array.isArray(chunk) ? chunk : [];
                    if (safe.length) rows.push(...safe);
                    if (safe.length < pageSize || page >= 200) {
                        keep = false;
                    } else {
                        page += 1;
                    }
                }
                return rows;
            };

            const [smallRows, structuralRows] = await Promise.all([
                collectPaged((page, pageSizeValue) =>
                    fetchCaseSmallVariantsByRegion({
                        caseId,
                        chromosome: chromosomeValue,
                        start,
                        end,
                        page,
                        pageSize: pageSizeValue,
                    }),
                ),
                collectPaged((page, pageSizeValue) =>
                    fetchCaseStructuralVariantsByRegion({
                        caseId,
                        chromosome: chromosomeValue,
                        start,
                        end,
                        page,
                        pageSize: pageSizeValue,
                    }),
                ),
            ]);
            const merged = normalizeVariantRecords(
                [...smallRows, ...structuralRows],
                chromosomeValue,
            );
            caseChromosomeVariantCacheRef.current.set(cacheKey, merged);
            return merged;
        },
        [effectiveCase?.caseId],
    );

    useEffect(() => {
        if (!hasCaseData) {
            setCaseChromosomeState({
                loading: false,
                error: '',
                variants: [],
            });
            return;
        }
        if (
            !Number.isFinite(chrSpan.start) ||
            !Number.isFinite(chrSpan.stop) ||
            chrSpan.stop <= chrSpan.start
        ) {
            return;
        }

        let cancelled = false;
        setCaseChromosomeState((prev) => ({
            ...prev,
            loading: true,
            error: '',
        }));
        loadCaseVariantsInRegion({
            chromosomeValue: chromosome,
            start: Math.round(chrSpan.start),
            end: Math.round(chrSpan.stop),
        })
            .then((rows) => {
                if (cancelled) return;
                setCaseChromosomeState({
                    loading: false,
                    error: '',
                    variants: Array.isArray(rows) ? rows : [],
                });
            })
            .catch((error) => {
                if (cancelled) return;
                console.warn('[chromosomeDetail] failed to load case chromosome variants', error);
                setCaseChromosomeState({
                    loading: false,
                    error: 'Failed to load patient variants for this chromosome.',
                    variants: [],
                });
            });

        return () => {
            cancelled = true;
        };
    }, [chromosome, chrSpan.start, chrSpan.stop, hasCaseData, loadCaseVariantsInRegion]);

    const ensureBandDiseases = useCallback(async (band) => {
        const bandId = band?.bandId;
        if (!bandId) return [];
        const key = String(bandId);
        const lastErrorAt = bandDiseasesErrorRef.current.get(bandId);
        if (lastErrorAt && Date.now() - lastErrorAt < 15000) {
            return [];
        }
        if (Object.prototype.hasOwnProperty.call(bandDiseasesRef.current, key)) {
            return bandDiseasesRef.current[key] || [];
        }
        if (bandDiseasesPromiseRef.current.has(key)) {
            return bandDiseasesPromiseRef.current.get(key);
        }
        bandDiseasesLoadingRef.current.add(key);
        const loader = (async () => {
            try {
                const rows = await fetchBandDiseases(bandId, 1, 80);
                const normalized = (Array.isArray(rows) ? rows : [])
                    .map(normalizeDiseaseRecord)
                    .filter(Boolean);
                bandDiseasesErrorRef.current.delete(bandId);
                bandDiseasesRef.current = {
                    ...bandDiseasesRef.current,
                    [key]: normalized,
                };
                setBandDiseasesMap((prev) => ({ ...prev, [key]: normalized }));
                return normalized;
            } catch (error) {
                console.warn('[chromosomeDetail] failed to fetch band diseases', error);
                bandDiseasesErrorRef.current.set(bandId, Date.now());
                return [];
            } finally {
                bandDiseasesLoadingRef.current.delete(key);
                bandDiseasesPromiseRef.current.delete(key);
            }
        })();
        bandDiseasesPromiseRef.current.set(key, loader);
        return loader;
    }, []);

    const ensureBandGenes = useCallback(async (bandId) => {
        if (!bandId) return [];
        const key = String(bandId);
        if (Object.prototype.hasOwnProperty.call(bandGenesRef.current, key)) {
            return bandGenesRef.current[key] || [];
        }
        if (bandGenesPromiseRef.current.has(key)) {
            return bandGenesPromiseRef.current.get(key);
        }
        bandGenesLoadingRef.current.add(key);
        const loader = (async () => {
            try {
                const rows = await fetchBandGenes(bandId);
                const safeRows = Array.isArray(rows) ? rows : [];
                setBandGenesMap((prev) => ({ ...prev, [key]: safeRows }));
                setBandGenesReadyMap((prev) => ({ ...prev, [key]: true }));
                return safeRows;
            } catch (error) {
                console.warn('[chromosomeDetail] failed to fetch band genes', error);
                setBandGenesReadyMap((prev) => ({ ...prev, [key]: false }));
                return [];
            } finally {
                bandGenesLoadingRef.current.delete(key);
                bandGenesPromiseRef.current.delete(key);
            }
        })();
        bandGenesPromiseRef.current.set(key, loader);
        return loader;
    }, []);

    const ensureDiseaseGenes = useCallback(async (diseaseId) => {
        if (!diseaseId) return [];
        if (diseaseGeneCacheRef.current.has(diseaseId)) {
            return diseaseGeneCacheRef.current.get(diseaseId) || [];
        }
        if (diseaseGeneLoadingRef.current.has(diseaseId)) return [];
        diseaseGeneLoadingRef.current.add(diseaseId);
        try {
            const rows = await fetchDiseaseGenes(diseaseId, 1, 200);
            const symbols = extractGeneSymbols(rows);
            diseaseGeneCacheRef.current.set(diseaseId, symbols);
            return symbols;
        } catch (error) {
            console.warn('[chromosomeDetail] failed to fetch disease genes', error);
            return [];
        } finally {
            diseaseGeneLoadingRef.current.delete(diseaseId);
        }
    }, []);

    const enrichBandDiseasesWithGenes = useCallback(
        async (bandId, diseases) => {
            if (!bandId || !Array.isArray(diseases) || !diseases.length) return;
            const needsGene = diseases.some((dz) => !Array.isArray(dz?.genes));
            if (!needsGene) return;
            const patched = new Map();
            const pending = diseases
                .map((dz, idx) => ({ dz, idx }))
                .filter((item) => !Array.isArray(item?.dz?.genes))
                .slice(0, DISEASE_GENE_ENRICH_LIMIT);

            await runWithConcurrency(pending, DISEASE_GENE_ENRICH_CONCURRENCY, async (item) => {
                const diseaseId = resolveDiseaseId(item?.dz);
                if (!diseaseId) {
                    patched.set(item.idx, []);
                    return;
                }
                const genes = await ensureDiseaseGenes(diseaseId);
                patched.set(item.idx, genes);
            });

            const enriched = diseases.map((dz, idx) => {
                if (Array.isArray(dz?.genes)) return dz;
                if (!patched.has(idx)) return dz;
                return {
                    ...dz,
                    genes: patched.get(idx) || [],
                };
            });
            bandDiseasesRef.current = {
                ...bandDiseasesRef.current,
                [bandId]: enriched,
            };
            setBandDiseasesMap((prev) => ({ ...prev, [bandId]: enriched }));
        },
        [ensureDiseaseGenes],
    );

    useEffect(() => {
        let cancelled = false;
        const bandIds = new Set(
            normalizedBands.map((band) => band?.bandId).filter((id) => id != null),
        );
        if (!bandIds.size || !chromosome) return undefined;

        const applyCounts = (rows) => {
            const updates = {};
            (Array.isArray(rows) ? rows : []).forEach((item) => {
                const id = item?.band_id ?? item?.bandId;
                if (id == null) return;
                if (!bandIds.has(id) && !bandIds.has(Number(id))) return;
                updates[String(id)] = {
                    disease_count: item?.disease_count ?? item?.diseaseCount ?? 0,
                };
            });
            if (!Object.keys(updates).length) return;
            bandSummaryRef.current = {
                ...bandSummaryRef.current,
                ...updates,
            };
            setBandSummaryMap((prev) => ({
                ...prev,
                ...updates,
            }));
        };

        const load = async () => {
            try {
                const allCounts = await fetchAllBandDiseaseCounts();
                if (cancelled) return;
                if (Array.isArray(allCounts) && allCounts.length) {
                    applyCounts(allCounts);
                    return;
                }
            } catch (error) {
                // ignore and fallback
            }

            try {
                const rows = await fetchBandDiseaseCountsByChromosome(chromosome);
                if (cancelled) return;
                applyCounts(rows);
            } catch (error) {
                console.warn('[chromosomeDetail] failed to load band disease counts', error);
            }
        };

        load();
        return () => {
            cancelled = true;
        };
    }, [chromosome, normalizedBands]);

    useEffect(() => {
        let cancelled = false;
        const bandQueue = normalizedBands.filter((band) => {
            if (band?.bandId == null) return false;
            const summaryCount = resolveDiseaseCount(bandSummaryRef.current?.[band.bandId]);
            if (Number.isFinite(summaryCount)) {
                return summaryCount > 0;
            }
            return true;
        });
        if (!bandQueue.length) return undefined;

        const warmup = async () => {
            await runWithConcurrency(bandQueue, CHR_DISEASE_PREFETCH_CONCURRENCY, async (band) => {
                if (cancelled) return;
                await ensureBandDiseases(band);
            });
        };

        warmup();
        return () => {
            cancelled = true;
        };
    }, [chromosome, ensureBandDiseases, normalizedBands]);

    useEffect(() => {
        if (!focusedBandId) {
            setFocusedBandLoading(false);
            setFocusedBandGeneLoading(false);
            setFocusedBandGeneRows([]);
            return;
        }
        let cancelled = false;
        setFocusedBandLoading(true);
        setFocusedBandGeneLoading(true);
        const load = async () => {
            const bandId = focusedBandId;
            const cachedRows = bandGenesRef.current[String(bandId)];
            if (Array.isArray(cachedRows) && cachedRows.length) {
                setFocusedBandGeneRows(cachedRows);
            }
            const diseasePromise = ensureBandDiseases({ bandId }).catch(() => []);
            let geneRows = [];
            try {
                const rows = await ensureBandGenes(bandId);
                geneRows = Array.isArray(rows) ? rows : [];
            } catch (error) {
                geneRows = [];
            }
            if (cancelled) return;
            setFocusedBandGeneRows(geneRows);
            setFocusedBandGeneLoading(false);

            const diseases = await diseasePromise;
            if (cancelled) return;
            try {
                await enrichBandDiseasesWithGenes(bandId, Array.isArray(diseases) ? diseases : []);
            } catch (error) {
                // ignore enrichment failures to avoid blocking gene layer
            }
            if (!cancelled) {
                setFocusedBandLoading(false);
            }
        };
        load().catch((_error) => {
            if (!cancelled) {
                setFocusedBandGeneLoading(false);
                setFocusedBandLoading(false);
            }
        });
        return () => {
            cancelled = true;
        };
    }, [ensureBandDiseases, ensureBandGenes, enrichBandDiseasesWithGenes, focusedBandId]);

    const reconcileCaseVariantsWithKnowledge = useCallback(async (rows, chromosomeFallback) => {
        const sourceRows = Array.isArray(rows) ? rows : [];
        if (!sourceRows.length) return sourceRows;

        const smallTargets = new Map();
        const structuralTargets = new Map();

        sourceRows.forEach((item) => {
            const smallKey = buildSmallExactMatchKey(item, chromosomeFallback);
            if (smallKey) {
                const [chr, posText] = smallKey.split(':');
                const pos = Number(posText);
                if (chr && Number.isFinite(pos)) {
                    smallTargets.set(`${chr}:${pos}`, { chromosome: chr, position: pos });
                }
                return;
            }
            const chr = normalizeChromosomeKey(item?.chromosome || chromosomeFallback);
            const bounds = resolveStructuralBounds(item);
            if (!chr || !bounds) return;
            structuralTargets.set(`${chr}:${bounds.start}:${bounds.end}`, {
                chromosome: chr,
                start: bounds.start,
                end: bounds.end,
            });
        });

        const knowledgeSmallByKey = new Map();
        const smallTargetList = Array.from(smallTargets.values());
        if (smallTargetList.length) {
            await runWithConcurrency(smallTargetList, 8, async (target) => {
                const hits = await fetchKnowledgeSmallVariantsByRegion({
                    chromosome: target.chromosome,
                    start: target.position,
                    end: target.position,
                    page: 1,
                    pageSize: 200,
                }).catch(() => []);
                const normalizedHits = normalizeVariantRecords(hits, target.chromosome);
                normalizedHits.forEach((hit) => {
                    const key = buildSmallExactMatchKey(hit, target.chromosome);
                    if (!key || knowledgeSmallByKey.has(key)) return;
                    knowledgeSmallByKey.set(key, {
                        clinicalSignificance: hit?.clinicalSignificance || null,
                    });
                });
            });
        }

        const knowledgeStructuralByKey = new Map();
        const structuralTargetList = Array.from(structuralTargets.values());
        if (structuralTargetList.length) {
            await runWithConcurrency(structuralTargetList, 4, async (target) => {
                const hits = await fetchKnowledgeStructuralVariantsByRegion({
                    chromosome: target.chromosome,
                    start: target.start,
                    end: target.end,
                    page: 1,
                    pageSize: 200,
                }).catch(() => []);
                const normalizedHits = normalizeVariantRecords(hits, target.chromosome);
                normalizedHits.forEach((hit) => {
                    const key = buildStructuralExactMatchKey(hit, target.chromosome);
                    if (!key || knowledgeStructuralByKey.has(key)) return;
                    knowledgeStructuralByKey.set(key, {
                        clinicalSignificance: hit?.clinicalSignificance || null,
                    });
                });
            });
        }

        return sourceRows.map((item) => {
            const smallKey = buildSmallExactMatchKey(item, chromosomeFallback);
            const structuralKey = smallKey
                ? ''
                : buildStructuralExactMatchKey(item, chromosomeFallback);
            const hit = smallKey
                ? knowledgeSmallByKey.get(smallKey)
                : knowledgeStructuralByKey.get(structuralKey);
            const knowledgeMatched = Boolean(hit);
            const currentClinical =
                item?.clinicalSignificance ??
                item?.clinical_significance ??
                item?.clinSigSimple ??
                '';
            const resolvedClinical =
                isUnknownClinicalText(currentClinical) && hit?.clinicalSignificance
                    ? hit.clinicalSignificance
                    : currentClinical;
            return {
                ...item,
                knowledgeMatched,
                patientOnly: !knowledgeMatched,
                knowledgeClinicalSignificance:
                    hit?.clinicalSignificance || item?.knowledgeClinicalSignificance || null,
                clinicalSignificance: resolvedClinical || item?.clinicalSignificance || null,
            };
        });
    }, []);

    const loadGeneVariants = useCallback(
        async (geneSymbol, geneRecord = null, mode = 'auto') => {
            const normalizedGeneSymbol = normalizeGeneSymbolKey(geneSymbol);
            if (!normalizedGeneSymbol) {
                return {
                    variants: [],
                    knowledgeVariants: [],
                    caseVariants: [],
                };
            }
            const normalizedMode = ['knowledge', 'case', 'combined'].includes(mode) ? mode : 'auto';
            const resolvedMode =
                normalizedMode === 'auto'
                    ? hasCaseData
                        ? 'combined'
                        : 'knowledge'
                    : normalizedMode === 'case' && !effectiveCase?.caseId
                      ? 'knowledge'
                      : normalizedMode;
            const rawGeneStart = toFiniteNumber(
                geneRecord?.locusStart ??
                    geneRecord?.start ??
                    selectedGeneData?.locusStart ??
                    selectedGeneData?.start,
            );
            const rawGeneStop = toFiniteNumber(
                geneRecord?.locusStop ??
                    geneRecord?.stop ??
                    selectedGeneData?.locusStop ??
                    selectedGeneData?.stop,
            );
            const bandStart = toFiniteNumber(focusedBandMeta?.start);
            const bandStop = toFiniteNumber(focusedBandMeta?.stop);
            const hasGeneRange = Number.isFinite(rawGeneStart) && Number.isFinite(rawGeneStop);
            const hasBandRange = Number.isFinite(bandStart) && Number.isFinite(bandStop);
            const fallbackStart = hasBandRange ? Math.min(bandStart, bandStop) : NaN;
            const fallbackStop = hasBandRange ? Math.max(bandStart, bandStop) : NaN;
            const scopedStart = hasGeneRange ? Math.min(rawGeneStart, rawGeneStop) : fallbackStart;
            const scopedStop = hasGeneRange ? Math.max(rawGeneStart, rawGeneStop) : fallbackStop;
            const resolvedStart = Number.isFinite(scopedStart) ? Math.round(scopedStart) : null;
            const resolvedStop = Number.isFinite(scopedStop) ? Math.round(scopedStop) : null;

            const fetchScopedSource = async (source) => {
                const caseId = source === 'case' ? effectiveCase?.caseId || null : null;
                if (source === 'case' && !caseId) return [];
                const cacheKey = `km3:${source}:${caseId || 'kb'}:${normalizedGeneSymbol}:${chromosome}:${resolvedStart}:${resolvedStop}`;
                const cached = variantCacheRef.current.get(cacheKey);
                if (cached) return cached;
                const rawRows = await fetchVariantsByGene(normalizedGeneSymbol, {
                    caseId,
                    chromosome,
                    start: resolvedStart,
                    end: resolvedStop,
                    pageSize: caseId ? 1200 : 120,
                });
                let normalized = normalizeVariantRecords(rawRows, chromosome);
                if (source === 'case') {
                    normalized = await reconcileCaseVariantsWithKnowledge(normalized, chromosome);
                    normalized = normalized.map((item) => {
                        const patientOnlyRaw = item?.patientOnly;
                        const knowledgeMatchedRaw = item?.knowledgeMatched;
                        const patientOnly =
                            patientOnlyRaw != null
                                ? Boolean(patientOnlyRaw)
                                : knowledgeMatchedRaw != null
                                  ? !knowledgeMatchedRaw
                                  : true;
                        return {
                            ...item,
                            patientOnly,
                            dataSource: 'case',
                        };
                    });
                } else {
                    normalized = normalized.map((item) => ({
                        ...item,
                        patientOnly: false,
                        dataSource: 'knowledge',
                    }));
                }
                variantCacheRef.current.set(cacheKey, normalized);
                return normalized;
            };

            activeVariantRequestRef.current += 1;
            const requestId = activeVariantRequestRef.current;
            setVariantState({
                loading: true,
                error: '',
                variants: [],
                knowledgeVariants: [],
                caseVariants: [],
            });

            try {
                let knowledgeVariants = [];
                let caseVariants = [];
                if (resolvedMode === 'case') {
                    caseVariants = await fetchScopedSource('case');
                } else if (resolvedMode === 'knowledge') {
                    knowledgeVariants = await fetchScopedSource('knowledge');
                } else {
                    const [knowledgeRows, caseRows] = await Promise.all([
                        fetchScopedSource('knowledge'),
                        fetchScopedSource('case'),
                    ]);
                    knowledgeVariants = knowledgeRows;
                    caseVariants = caseRows;
                }
                if (caseVariants.length) {
                    knowledgeVariants = syncMatchedCaseVariantsToKnowledge(
                        knowledgeVariants,
                        caseVariants,
                        chromosome,
                    );
                }
                const merged = [...knowledgeVariants, ...caseVariants];
                if (requestId !== activeVariantRequestRef.current) {
                    return {
                        variants: merged,
                        knowledgeVariants,
                        caseVariants,
                    };
                }
                setVariantState({
                    loading: false,
                    error: '',
                    variants: merged,
                    knowledgeVariants,
                    caseVariants,
                });
                return {
                    variants: merged,
                    knowledgeVariants,
                    caseVariants,
                };
            } catch (error) {
                if (requestId !== activeVariantRequestRef.current) {
                    return {
                        variants: [],
                        knowledgeVariants: [],
                        caseVariants: [],
                    };
                }
                setVariantState({
                    loading: false,
                    error: 'Unable to load variants for this gene.',
                    variants: [],
                    knowledgeVariants: [],
                    caseVariants: [],
                });
                return {
                    variants: [],
                    knowledgeVariants: [],
                    caseVariants: [],
                };
            }
        },
        [
            chromosome,
            effectiveCase?.caseId,
            focusedBandMeta?.start,
            focusedBandMeta?.stop,
            hasCaseData,
            reconcileCaseVariantsWithKnowledge,
            selectedGeneData?.locusStart,
            selectedGeneData?.locusStop,
            selectedGeneData?.start,
            selectedGeneData?.stop,
        ],
    );

    const loadCaseGenesForBand = useCallback(async () => {
        if (!effectiveCase?.caseId || !focusedBandData) {
            setCaseGeneTrackState({
                loading: false,
                error: '',
                genes: [],
            });
            return [];
        }
        const candidates = (Array.isArray(focusedBandGenes) ? focusedBandGenes : [])
            .filter((item) => {
                const start = toFiniteNumber(item?.locusStart ?? item?.start);
                const stop = toFiniteNumber(item?.locusStop ?? item?.stop);
                return Number.isFinite(start) && Number.isFinite(stop) && stop >= start;
            })
            .map((item) => ({
                ...item,
                symbol: String(item?.name || item?.gene || '')
                    .trim()
                    .toUpperCase(),
            }))
            .filter((item) => item.symbol);

        if (!candidates.length) {
            setCaseGeneTrackState({
                loading: false,
                error: '',
                genes: [],
            });
            return [];
        }

        setCaseGeneTrackState({
            loading: true,
            error: '',
            genes: [],
        });
        try {
            const rows = await runWithConcurrency(
                candidates,
                CASE_GENE_CONCURRENCY,
                async (gene) => {
                    const geneStart = Math.round(toFiniteNumber(gene?.locusStart ?? gene?.start));
                    const geneStop = Math.round(toFiniteNumber(gene?.locusStop ?? gene?.stop));
                    const rawRows = await fetchVariantsByGene(gene.symbol, {
                        caseId: effectiveCase.caseId,
                        chromosome,
                        start: geneStart,
                        end: geneStop,
                        pageSize: 1200,
                    });
                    const normalized = normalizeVariantRecords(rawRows, chromosome);
                    if (!normalized.length) return null;
                    const matchedVariantCount = normalized.filter(isKnowledgeMatchedVariant).length;
                    const span = deriveSpanFromVariants(normalized, {
                        chromosome,
                        rangeStart: geneStart,
                        rangeStop: geneStop,
                    });
                    const start = Math.min(geneStart, geneStop);
                    const stop = Math.max(start + 1, Math.max(geneStart, geneStop));
                    return {
                        ...gene,
                        name: gene.symbol,
                        gene: gene.symbol,
                        start,
                        stop,
                        locusStart: start,
                        locusStop: stop,
                        variantCount: normalized.length,
                        coordinateMode:
                            String(gene?.coordinateMode || '').toLowerCase() === 'gene_coordinate'
                                ? 'gene_coordinate'
                                : 'knowledge_track_coordinate',
                        matchedVariantCount,
                        matchedKnowledgeStart: start,
                        matchedKnowledgeStop: stop,
                        variantSpanStart: Number.isFinite(span?.start) ? span.start : null,
                        variantSpanStop: Number.isFinite(span?.stop) ? span.stop : null,
                    };
                },
            );
            const genes = rows
                .filter(Boolean)
                .sort((a, b) => (Number(b?.variantCount) || 0) - (Number(a?.variantCount) || 0));
            setCaseGeneTrackState({
                loading: false,
                error: '',
                genes,
            });
            return genes;
        } catch (error) {
            console.warn('[chromosomeDetail] failed to build case gene track', error);
            setCaseGeneTrackState({
                loading: false,
                error: 'Failed to build patient gene track for this band.',
                genes: [],
            });
            return [];
        }
    }, [chromosome, effectiveCase?.caseId, focusedBandData, focusedBandGenes]);

    useEffect(() => {
        let cancelled = false;
        const allowCaseTrackLayer =
            focusLayer === 'band' || focusLayer === 'gene' || focusLayer === 'locus';
        if (!allowCaseTrackLayer || !hasCaseData) {
            setCaseGeneTrackState({
                loading: false,
                error: '',
                genes: [],
            });
            return undefined;
        }

        loadCaseGenesForBand().then((rows) => {
            if (cancelled) return;
            if (!Array.isArray(rows)) return;
        });
        return () => {
            cancelled = true;
        };
    }, [hasCaseData, focusedBandData?.bandId, focusLayer, loadCaseGenesForBand]);

    const handleOpenGeneLayer = useCallback(async () => {
        const bandId = focusedBandData?.bandId;
        if (!bandId) return;
        const readyKey = String(bandId);
        let loaded = Boolean(bandGenesReadyMap[readyKey]);
        if (!loaded) {
            try {
                const rows = await ensureBandGenes(bandId);
                if (Array.isArray(rows)) {
                    setFocusedBandGeneRows(rows);
                }
                loaded = true;
            } catch (error) {
                loaded = false;
            }
        }
        if (!loaded && !focusedBandGeneRows.length) {
            return;
        }
        setLocusWindowRange(null);
        setPreferredVariantExactKey('');
        setFocusLayer('gene');
    }, [bandGenesReadyMap, ensureBandGenes, focusedBandData?.bandId, focusedBandGeneRows.length]);

    const handleOpenBandLayer = useCallback(() => {
        setFocusLayer('band');
        setSelectedGene(null);
        setPendingVariantFocus(null);
        setPreferredVariantExactKey('');
        setLocusWindowRange(null);
        setVariantState({
            loading: false,
            error: '',
            variants: [],
            knowledgeVariants: [],
            caseVariants: [],
        });
    }, []);

    const handleGeneSelect = useCallback(
        async (geneRecord) => {
            const geneSymbol = (geneRecord?.name || geneRecord?.gene || '')
                .toString()
                .trim()
                .toUpperCase();
            if (!geneSymbol) return;
            setSelectedGene(geneSymbol);

            if (focusLayer !== 'gene') {
                setPreferredVariantExactKey('');
                setFocusLayer('gene');
                return;
            }

            if (locusEntryLocked) {
                return;
            }

            setLocusWindowRange(null);
            setPreferredVariantExactKey('');
            setFocusLayer('locus');
            try {
                await loadGeneVariants(
                    geneSymbol,
                    geneRecord,
                    hasCaseData ? 'combined' : 'knowledge',
                );
            } catch (error) {
                setVariantState({
                    loading: false,
                    error: 'Unable to load variants for this gene.',
                    variants: [],
                    knowledgeVariants: [],
                    caseVariants: [],
                });
            }
        },
        [focusLayer, hasCaseData, loadGeneVariants, locusEntryLocked],
    );

    useEffect(() => {
        if (!focusedBandId) {
            setFocusLayer('band');
            setSelectedGene(null);
            setPendingVariantFocus(null);
            setPreferredVariantExactKey('');
            setLocusWindowRange(null);
            setFocusedBandGeneLoading(false);
            setVariantState({
                loading: false,
                error: '',
                variants: [],
                knowledgeVariants: [],
                caseVariants: [],
            });
            setGeneCoordinateMap(new Map());
            setFocusedBandGeneRows([]);
            setGeneSpanPrefetchState({
                loading: false,
                total: 0,
                loaded: 0,
                failed: 0,
            });
            variantCacheRef.current = new Map();
            geneCoordinateCacheRef.current = new Map();
            geneCoordinateBandRef.current = new Set();
            geneCoordinateMissingRef.current = new Set();
            geneCoordinateRetryAttemptsRef.current = new Map();
            if (geneCoordinateRetryTimerRef.current) {
                clearTimeout(geneCoordinateRetryTimerRef.current);
                geneCoordinateRetryTimerRef.current = null;
            }
            return;
        }
        setFocusLayer('band');
        setSelectedGene(null);
        setPendingVariantFocus(null);
        setPreferredVariantExactKey('');
        setLocusWindowRange(null);
        setFocusedBandGeneLoading(false);
        setVariantState({
            loading: false,
            error: '',
            variants: [],
            knowledgeVariants: [],
            caseVariants: [],
        });
        setGeneCoordinateMap(new Map());
        setFocusedBandGeneRows([]);
        setGeneSpanPrefetchState({
            loading: false,
            total: 0,
            loaded: 0,
            failed: 0,
        });
        variantCacheRef.current = new Map();
        geneCoordinateCacheRef.current = new Map();
        geneCoordinateBandRef.current = new Set();
        geneCoordinateMissingRef.current = new Set();
        geneCoordinateRetryAttemptsRef.current = new Map();
        if (geneCoordinateRetryTimerRef.current) {
            clearTimeout(geneCoordinateRetryTimerRef.current);
            geneCoordinateRetryTimerRef.current = null;
        }
    }, [focusedBandId, chromosome]);

    useEffect(() => {
        variantCacheRef.current = new Map();
        if (focusLayer === 'locus' && selectedGene) {
            loadGeneVariants(
                selectedGene,
                selectedGeneData,
                hasCaseData ? 'combined' : 'knowledge',
            ).catch(() => {
                setVariantState({
                    loading: false,
                    error: 'Unable to load variants for this gene.',
                    variants: [],
                    knowledgeVariants: [],
                    caseVariants: [],
                });
            });
        }
    }, [
        effectiveCase?.caseId,
        focusLayer,
        hasCaseData,
        loadGeneVariants,
        selectedGene,
        selectedGeneData,
    ]);

    useEffect(() => {
        const bandId = focusedBandData?.bandId;
        const prefetchSymbols = coordinatePrefetchKey
            ? coordinatePrefetchKey.split('|').map(normalizeGeneSymbolKey).filter(Boolean)
            : [];
        if (geneCoordinateCacheRef.current?.size) {
            const normalizedCache = new Map();
            geneCoordinateCacheRef.current.forEach((value, key) => {
                const normalized = normalizeGeneSymbolKey(
                    key || value?.geneSymbol || value?.gene_symbol || value?.gene,
                );
                if (!normalized) return;
                if (!normalizedCache.has(normalized)) {
                    normalizedCache.set(normalized, value);
                }
            });
            geneCoordinateCacheRef.current = normalizedCache;
        }
        if (geneCoordinateMissingRef.current?.size) {
            const normalizedMissing = new Set();
            geneCoordinateMissingRef.current.forEach((key) => {
                const normalized = normalizeGeneSymbolKey(key);
                if (normalized) normalizedMissing.add(normalized);
            });
            geneCoordinateMissingRef.current = normalizedMissing;
        }
        activeGeneCoordinateRequestRef.current += 1;
        const requestId = activeGeneCoordinateRequestRef.current;
        if (geneCoordinateRetryTimerRef.current) {
            clearTimeout(geneCoordinateRetryTimerRef.current);
            geneCoordinateRetryTimerRef.current = null;
        }
        if (!bandId) {
            setGeneSpanPrefetchState({
                loading: false,
                total: 0,
                loaded: 0,
                failed: 0,
            });
            return undefined;
        }
        if (!prefetchSymbols.length) {
            setGeneSpanPrefetchState({
                loading: false,
                total: 0,
                loaded: 0,
                failed: 0,
            });
            return undefined;
        }
        if (focusLayer !== 'gene') {
            setGeneSpanPrefetchState((prev) => ({
                ...prev,
                loading: false,
            }));
            return undefined;
        }
        const bandPrefetchKey = `${bandId}::${coordinatePrefetchKey}`;
        if (geneCoordinateBandRef.current.has(bandPrefetchKey)) return undefined;
        let cancelled = false;

        const scheduleRetry = (unresolvedCount) => {
            if (!unresolvedCount) return;
            const attempts = geneCoordinateRetryAttemptsRef.current.get(bandPrefetchKey) || 0;
            if (attempts >= GENE_COORDINATE_RETRY_LIMIT) return;
            geneCoordinateRetryAttemptsRef.current.set(bandPrefetchKey, attempts + 1);
            const delayMs = 1200 * (attempts + 1);
            geneCoordinateRetryTimerRef.current = setTimeout(() => {
                if (requestId !== activeGeneCoordinateRequestRef.current) return;
                setGeneCoordinateRetryNonce((prev) => prev + 1);
            }, delayMs);
        };

        const knownMissingCount = prefetchSymbols.filter((symbol) =>
            geneCoordinateMissingRef.current.has(symbol),
        ).length;
        const missing = prefetchSymbols.filter(
            (symbol) =>
                !geneCoordinateCacheRef.current.has(symbol) &&
                !geneCoordinateMissingRef.current.has(symbol),
        );
        if (!missing.length) {
            geneCoordinateBandRef.current.add(bandPrefetchKey);
            setGeneSpanPrefetchState({
                loading: false,
                total: prefetchSymbols.length,
                loaded: prefetchSymbols.length - knownMissingCount,
                failed: knownMissingCount,
            });
            return undefined;
        }

        const loadedFromCache = prefetchSymbols.length - missing.length - knownMissingCount;
        geneSpanLoadingStartRef.current = Date.now();
        setGeneSpanPrefetchState({
            loading: true,
            total: prefetchSymbols.length,
            loaded: loadedFromCache,
            failed: knownMissingCount,
        });
        const load = async () => {
            try {
                const rows = await fetchGeneCoordinates(missing);
                if (cancelled || requestId !== activeGeneCoordinateRequestRef.current) return;
                const next = new Map(geneCoordinateCacheRef.current);
                const hit = new Set();
                (Array.isArray(rows) ? rows : []).forEach((row) => {
                    const sourceDb = String(row?.sourceDb ?? row?.source_db ?? '')
                        .trim()
                        .toUpperCase();
                    const sourceFile = String(row?.sourceFile ?? row?.source_file ?? '')
                        .trim()
                        .toLowerCase();
                    const hasCoordinateId =
                        row?.geneCoordinateId != null || row?.gene_coordinate_id != null;
                    if (!hasCoordinateId && (sourceDb === 'GENE_TABLE' || sourceFile === 'gene')) {
                        return;
                    }
                    const key = normalizeGeneSymbolKey(
                        row?.geneSymbol || row?.gene_symbol || row?.gene,
                    );
                    if (key) {
                        next.set(key, row);
                        hit.add(key);
                    }
                });
                const unresolved = missing.filter((symbol) => !hit.has(symbol));
                unresolved.forEach((symbol) => {
                    geneCoordinateMissingRef.current.add(symbol);
                });
                geneCoordinateCacheRef.current = next;
                setGeneCoordinateMap(new Map(next));
                const loaded = prefetchSymbols.filter((symbol) => next.has(symbol)).length;
                const retryPendingCount = prefetchSymbols.filter(
                    (symbol) => !next.has(symbol) && !geneCoordinateMissingRef.current.has(symbol),
                ).length;
                const failed = Math.max(0, prefetchSymbols.length - loaded);
                if (retryPendingCount === 0) {
                    geneCoordinateBandRef.current.add(bandPrefetchKey);
                    geneCoordinateRetryAttemptsRef.current.delete(bandPrefetchKey);
                } else {
                    geneCoordinateBandRef.current.delete(bandPrefetchKey);
                }
                const elapsed = Date.now() - (geneSpanLoadingStartRef.current || Date.now());
                const remain = Math.max(0, GENE_COORDINATE_LOADING_MIN_MS - elapsed);
                if (remain > 0) {
                    await new Promise((resolve) => setTimeout(resolve, remain));
                    if (cancelled || requestId !== activeGeneCoordinateRequestRef.current) return;
                }
                const retryAttempt =
                    geneCoordinateRetryAttemptsRef.current.get(bandPrefetchKey) || 0;
                const keepLoading =
                    retryPendingCount > 0 && retryAttempt < GENE_COORDINATE_RETRY_LIMIT;
                setGeneSpanPrefetchState({
                    loading: keepLoading,
                    total: prefetchSymbols.length,
                    loaded,
                    failed,
                });
                if (!cancelled && requestId === activeGeneCoordinateRequestRef.current) {
                    scheduleRetry(retryPendingCount);
                }
            } catch (error) {
                geneCoordinateBandRef.current.delete(bandPrefetchKey);
                console.warn('[chromosomeDetail] failed to fetch gene coordinates', error);
                if (cancelled || requestId !== activeGeneCoordinateRequestRef.current) return;
                const next = new Map(geneCoordinateCacheRef.current);
                const loaded = prefetchSymbols.filter((symbol) => next.has(symbol)).length;
                const retryPendingCount = prefetchSymbols.filter(
                    (symbol) => !next.has(symbol) && !geneCoordinateMissingRef.current.has(symbol),
                ).length;
                const failed = Math.max(0, prefetchSymbols.length - loaded);
                const elapsed = Date.now() - (geneSpanLoadingStartRef.current || Date.now());
                const remain = Math.max(0, GENE_COORDINATE_LOADING_MIN_MS - elapsed);
                if (remain > 0) {
                    await new Promise((resolve) => setTimeout(resolve, remain));
                    if (cancelled || requestId !== activeGeneCoordinateRequestRef.current) return;
                }
                const retryAttempt =
                    geneCoordinateRetryAttemptsRef.current.get(bandPrefetchKey) || 0;
                const keepLoading =
                    retryPendingCount > 0 && retryAttempt < GENE_COORDINATE_RETRY_LIMIT;
                setGeneSpanPrefetchState({
                    loading: keepLoading,
                    total: prefetchSymbols.length,
                    loaded,
                    failed,
                });
                if (!cancelled && requestId === activeGeneCoordinateRequestRef.current) {
                    scheduleRetry(retryPendingCount);
                }
            }
        };

        load();
        return () => {
            cancelled = true;
        };
    }, [coordinatePrefetchKey, focusLayer, focusedBandData?.bandId, geneCoordinateRetryNonce]);

    useEffect(
        () => () => {
            if (geneCoordinateRetryTimerRef.current) {
                clearTimeout(geneCoordinateRetryTimerRef.current);
                geneCoordinateRetryTimerRef.current = null;
            }
        },
        [],
    );

    const setRibbonBandText = useCallback(
        (bandName) => {
            if (!bandName) {
                setRibbonBand('Band: --');
                return;
            }
            const band = bandLookup.get(bandName.toLowerCase());
            const meta = bandSummary(band);
            if (meta?.start != null && meta?.stop != null) {
                setRibbonBand(
                    `Band: ${bandName} [${meta.start.toLocaleString()} - ${meta.stop.toLocaleString()}]`,
                );
            } else {
                setRibbonBand(`Band: ${bandName}`);
            }
        },
        [bandLookup],
    );

    const updateScale = useCallback(
        (svg) => {
            if (!svg) return;
            const rect = svg.getBoundingClientRect();
            if (!rect.width) return;
            const bp = chrSpan.stop - chrSpan.start;
            if (bp <= 0) return;
            const pxPerMb = rect.width / (bp / 1e6);
            let pick = 50;
            for (let i = 0; i < SCALE_CANDIDATES.length; i += 1) {
                const candidate = SCALE_CANDIDATES[i];
                const w = candidate * pxPerMb;
                if (w >= 120 && w <= 240) {
                    pick = candidate;
                    break;
                }
            }
            const rawWidth = Math.round(pick * pxPerMb);
            const maxWidth = Math.max(120, rect.width * 0.25);
            const width = Math.max(60, Math.min(rawWidth, maxWidth));
            setScaleInfo({ width, label: `${pick} Mb` });
        },
        [chrSpan],
    );
    useEffect(() => {
        updateScaleRef.current = updateScale;
    }, [updateScale]);

    const syncLegendColors = useCallback((svg) => {
        const legend = legendRef.current;
        if (!legend || !svg) return;
        const stains = ['gneg', 'gpos25', 'gpos50', 'gpos75', 'gpos100', 'gvar', 'stalk', 'acen'];
        stains.forEach((stain) => {
            const band = svg.querySelector(`.bands .${stain}`);
            if (!band) return;
            const computed = window.getComputedStyle(band);
            const fill = computed?.fill || band.getAttribute('fill');
            if (!fill || fill === 'none' || fill.startsWith('url(')) return;
            const swatch = legend.querySelector(`.sw.${stain}`);
            if (!swatch) return;
            swatch.style.background = fill;
            if (stain === 'gneg') {
                swatch.style.borderColor = '#9ca3af';
            } else {
                swatch.style.borderColor = fill;
            }
        });
    }, []);

    const activateHover = useCallback(
        (bandName) => {
            if (!bandName) return;
            if (hoverBandRef.current === bandName) {
                return;
            }
            if (hoverDiseaseTimerRef.current) {
                clearTimeout(hoverDiseaseTimerRef.current);
                hoverDiseaseTimerRef.current = null;
            }
            const band = bandLookup.get(bandName.toLowerCase());
            if (!band) return;
            const diseases = getBandDiseases({ band, bandDiseases: bandDiseasesRef.current });
            const summaryCount = resolveDiseaseCount(bandSummaryRef.current?.[band.bandId]);
            const totalCount = Number.isFinite(summaryCount) ? summaryCount : diseases.length;
            const hasCache = Object.prototype.hasOwnProperty.call(
                bandDiseasesRef.current,
                band.bandId,
            );
            const loading = !hasCache && (!Number.isFinite(summaryCount) || summaryCount > 0);
            hoverBandRef.current = bandName;
            setHoverPreview({
                chromosome,
                bandName,
                diseases,
                loading,
                totalCount,
            });
            onHoverContextChange?.({
                chromosome,
                bandName,
                diseases,
                pinned: false,
            });
            setRibbonBandText(bandName);

            if (!hasCache && band?.bandId) {
                hoverDiseaseTimerRef.current = setTimeout(() => {
                    hoverDiseaseTimerRef.current = null;
                    ensureBandDiseases(band).then((list) => {
                        if (hoverBandRef.current !== bandName) return;
                        const refreshedCount = resolveDiseaseCount(
                            bandSummaryRef.current?.[band.bandId],
                        );
                        const resolvedCount = Number.isFinite(refreshedCount)
                            ? refreshedCount
                            : list.length;
                        const nextPreview = {
                            chromosome,
                            bandName,
                            diseases: list,
                            loading: false,
                            totalCount: resolvedCount,
                        };
                        setHoverPreview(nextPreview);
                        onHoverContextChange?.({
                            chromosome,
                            bandName,
                            diseases: list,
                            pinned: false,
                        });
                    });
                }, 140);
            }
        },
        [bandLookup, chromosome, ensureBandDiseases, onHoverContextChange, setRibbonBandText],
    );

    const clearHover = useCallback(() => {
        if (hoverDiseaseTimerRef.current) {
            clearTimeout(hoverDiseaseTimerRef.current);
            hoverDiseaseTimerRef.current = null;
        }
        hoverBandRef.current = null;
        setHoverPreview(null);
        setRibbonBand('Band: --');
        if (!hoverContext?.pinned) {
            onHoverContextChange?.(null);
        }
    }, [hoverContext, onHoverContextChange]);

    useEffect(() => {
        activateHoverRef.current = activateHover;
    }, [activateHover]);

    useEffect(() => {
        setRibbonBandTextRef.current = setRibbonBandText;
    }, [setRibbonBandText]);

    useEffect(() => {
        onBandFocusRef.current = onBandFocus;
    }, [onBandFocus]);

    const renderDiseaseMarkers = useCallback(
        (svg) => {
            if (!svg) return;
            const countMap = diseaseBandCountsRef.current || new Map();
            const caseCountMap = caseBandCountsRef.current || new Map();
            const overlapBandMap = caseOverlapBandCountsRef.current || new Map();
            const diseaseSignature = Array.from(countMap.entries())
                .filter(([, count]) => Number(count) > 0)
                .sort((a, b) => String(a[0]).localeCompare(String(b[0])))
                .map(([bandName, count]) => `${String(bandName)}:${Number(count)}`)
                .join('|');
            const caseSignature = Array.from(caseCountMap.entries())
                .filter(([, count]) => Number(count) > 0)
                .sort((a, b) => String(a[0]).localeCompare(String(b[0])))
                .map(([bandName, count]) => `${String(bandName)}:${Number(count)}`)
                .join('|');
            const overlapSignature = Array.from(overlapBandMap.entries())
                .filter(([, count]) => Number(count) > 0)
                .sort((a, b) => String(a[0]).localeCompare(String(b[0])))
                .map(([bandName, count]) => `${String(bandName)}:${Number(count)}`)
                .join('|');
            const segmentSignature = segmentRange
                ? `${segmentRange.start}:${segmentRange.end}`
                : 'none';
            const signature = `${diseaseSignature}__${caseSignature}__${overlapSignature}__${segmentSignature}`;
            if (signature === diseaseMarkerSignatureRef.current) return;
            diseaseMarkerSignatureRef.current = signature;
            const chromosomeSet =
                svg.querySelector('g.chromosome-set') ||
                svg.querySelector('g[class*="chromosome-set"]');
            if (!chromosomeSet) return;
            let overlay = chromosomeSet.querySelector('g.cv-disease-overlay');
            if (!overlay) {
                overlay = document.createElementNS('http://www.w3.org/2000/svg', 'g');
                overlay.setAttribute('class', 'cv-disease-overlay');
                overlay.setAttribute('pointer-events', 'none');
                chromosomeSet.appendChild(overlay);
            }
            while (overlay.firstChild) overlay.removeChild(overlay.firstChild);
            let caseOverlay = chromosomeSet.querySelector('g.cv-case-overlay');
            if (!caseOverlay) {
                caseOverlay = document.createElementNS('http://www.w3.org/2000/svg', 'g');
                caseOverlay.setAttribute('class', 'cv-case-overlay');
                caseOverlay.setAttribute('pointer-events', 'none');
                chromosomeSet.appendChild(caseOverlay);
            }
            while (caseOverlay.firstChild) caseOverlay.removeChild(caseOverlay.firstChild);
            let overlapOverlay = chromosomeSet.querySelector('g.cv-case-overlap-overlay');
            if (!overlapOverlay) {
                overlapOverlay = document.createElementNS('http://www.w3.org/2000/svg', 'g');
                overlapOverlay.setAttribute('class', 'cv-case-overlap-overlay');
                overlapOverlay.setAttribute('pointer-events', 'none');
                chromosomeSet.appendChild(overlapOverlay);
            }
            while (overlapOverlay.firstChild) overlapOverlay.removeChild(overlapOverlay.firstChild);

            const ns = 'http://www.w3.org/2000/svg';
            const bandsEls = svg.querySelectorAll('.bands .band');
            let minBandX = Number.POSITIVE_INFINITY;
            let maxBandX = Number.NEGATIVE_INFINITY;
            let minBandY = Number.POSITIVE_INFINITY;
            let maxBandY = Number.NEGATIVE_INFINITY;
            bandsEls.forEach((el) => {
                const bbox = el.getBBox();
                minBandX = Math.min(minBandX, bbox.x);
                maxBandX = Math.max(maxBandX, bbox.x + bbox.width);
                minBandY = Math.min(minBandY, bbox.y);
                maxBandY = Math.max(maxBandY, bbox.y + bbox.height);
                const bandName = extractBandName(el).toLowerCase();
                const overlapCount = overlapBandMap.get(bandName) || 0;
                if (overlapCount > 0) {
                    const ringInset = 1.35;
                    const ring = document.createElementNS(ns, 'rect');
                    const ringWidth = Math.max(2, bbox.width + ringInset * 2);
                    const ringHeight = Math.max(2, bbox.height + ringInset * 2);
                    ring.setAttribute('x', `${bbox.x - ringInset}`);
                    ring.setAttribute('y', `${bbox.y - ringInset}`);
                    ring.setAttribute('width', `${ringWidth}`);
                    ring.setAttribute('height', `${ringHeight}`);
                    ring.setAttribute('rx', `${Math.min(3.2, Math.max(0.8, ringHeight * 0.2))}`);
                    ring.setAttribute('ry', `${Math.min(3.2, Math.max(0.8, ringHeight * 0.2))}`);
                    ring.setAttribute('class', 'cv-band-case-overlap-ring');
                    overlapOverlay.appendChild(ring);
                }
                const count = countMap.get(bandName) || 0;
                const caseCount = caseCountMap.get(bandName) || 0;
                if (!count && !caseCount) return;
                const x = bbox.x + bbox.width / 2;
                const yTop = bbox.y;
                const yBottom = bbox.y + bbox.height;
                if (count > 0) {
                    const lineLen = 17;
                    const y2 = yTop - lineLen;

                    let stroke = '#059669';
                    if (count >= 11) stroke = '#ef4444';
                    else if (count >= 6) stroke = '#f97316';

                    const lineOutline = document.createElementNS(ns, 'line');
                    lineOutline.setAttribute('x1', `${x}`);
                    lineOutline.setAttribute('x2', `${x}`);
                    lineOutline.setAttribute('y1', `${yTop}`);
                    lineOutline.setAttribute('y2', `${y2}`);
                    lineOutline.setAttribute('stroke', stroke);
                    lineOutline.setAttribute('stroke-width', '6');
                    lineOutline.setAttribute('stroke-linecap', 'round');
                    lineOutline.setAttribute('vector-effect', 'non-scaling-stroke');
                    overlay.appendChild(lineOutline);

                    const line = document.createElementNS(ns, 'line');
                    line.setAttribute('x1', `${x}`);
                    line.setAttribute('x2', `${x}`);
                    line.setAttribute('y1', `${yTop}`);
                    line.setAttribute('y2', `${y2}`);
                    line.setAttribute('stroke', '#000000');
                    line.setAttribute('stroke-width', '1');
                    line.setAttribute('stroke-linecap', 'round');
                    line.setAttribute('vector-effect', 'non-scaling-stroke');
                    overlay.appendChild(line);

                    const dot = document.createElementNS(ns, 'circle');
                    dot.setAttribute('cx', `${x}`);
                    dot.setAttribute('cy', `${y2}`);
                    dot.setAttribute('r', '6.75');
                    dot.setAttribute('fill', stroke);
                    dot.setAttribute('stroke', '#000000');
                    dot.setAttribute('stroke-width', '1');
                    overlay.appendChild(dot);
                }

                if (caseCount > 0) {
                    const caseLineLen = 15;
                    const caseY2 = yBottom + caseLineLen;
                    const caseStroke = '#0d47a1';

                    const caseLineOutline = document.createElementNS(ns, 'line');
                    caseLineOutline.setAttribute('x1', `${x}`);
                    caseLineOutline.setAttribute('x2', `${x}`);
                    caseLineOutline.setAttribute('y1', `${yBottom}`);
                    caseLineOutline.setAttribute('y2', `${caseY2}`);
                    caseLineOutline.setAttribute('stroke', caseStroke);
                    caseLineOutline.setAttribute('stroke-width', '5');
                    caseLineOutline.setAttribute('stroke-linecap', 'round');
                    caseLineOutline.setAttribute('vector-effect', 'non-scaling-stroke');
                    caseOverlay.appendChild(caseLineOutline);

                    const caseLine = document.createElementNS(ns, 'line');
                    caseLine.setAttribute('x1', `${x}`);
                    caseLine.setAttribute('x2', `${x}`);
                    caseLine.setAttribute('y1', `${yBottom}`);
                    caseLine.setAttribute('y2', `${caseY2}`);
                    caseLine.setAttribute('stroke', '#000000');
                    caseLine.setAttribute('stroke-width', '1');
                    caseLine.setAttribute('stroke-linecap', 'round');
                    caseLine.setAttribute('vector-effect', 'non-scaling-stroke');
                    caseOverlay.appendChild(caseLine);

                    const caseDot = document.createElementNS(ns, 'circle');
                    caseDot.setAttribute('cx', `${x}`);
                    caseDot.setAttribute('cy', `${caseY2}`);
                    caseDot.setAttribute('r', '5.4');
                    caseDot.setAttribute('fill', caseStroke);
                    caseDot.setAttribute('stroke', '#000000');
                    caseDot.setAttribute('stroke-width', '1');
                    caseOverlay.appendChild(caseDot);
                }
            });

            let segmentOverlay = chromosomeSet.querySelector('g.cv-segment-overlay');
            if (!segmentOverlay) {
                segmentOverlay = document.createElementNS(ns, 'g');
                segmentOverlay.setAttribute('class', 'cv-segment-overlay');
                segmentOverlay.setAttribute('pointer-events', 'none');
                chromosomeSet.appendChild(segmentOverlay);
            }
            while (segmentOverlay.firstChild) segmentOverlay.removeChild(segmentOverlay.firstChild);

            if (
                segmentRange &&
                Number.isFinite(minBandX) &&
                Number.isFinite(maxBandX) &&
                Number.isFinite(minBandY) &&
                Number.isFinite(maxBandY)
            ) {
                const span = chrSpanRef.current || { start: 0, stop: 0 };
                const chrStart = Number(span.start);
                const chrStop = Number(span.stop);
                if (Number.isFinite(chrStart) && Number.isFinite(chrStop) && chrStop > chrStart) {
                    const toX = (bp) => {
                        const clamped = Math.max(chrStart, Math.min(chrStop, Number(bp)));
                        const ratio = (clamped - chrStart) / (chrStop - chrStart);
                        return minBandX + ratio * (maxBandX - minBandX);
                    };
                    const startX = toX(segmentRange.start);
                    const endX = toX(segmentRange.end);
                    const leftX = Math.min(startX, endX);
                    const rightX = Math.max(startX, endX);
                    const topY = minBandY - 14;
                    const bottomY = maxBandY + 14;

                    const rangeRect = document.createElementNS(ns, 'rect');
                    rangeRect.setAttribute('x', `${leftX}`);
                    rangeRect.setAttribute('y', `${minBandY + 1}`);
                    rangeRect.setAttribute('width', `${Math.max(1, rightX - leftX)}`);
                    rangeRect.setAttribute('height', `${Math.max(1, maxBandY - minBandY - 2)}`);
                    rangeRect.setAttribute('fill', 'rgba(56, 189, 248, 0.26)');
                    rangeRect.setAttribute('stroke', 'rgba(30, 64, 175, 0.45)');
                    rangeRect.setAttribute('stroke-width', '1');
                    segmentOverlay.appendChild(rangeRect);

                    const drawBoundary = (x, color) => {
                        const boundaryLine = document.createElementNS(ns, 'line');
                        boundaryLine.setAttribute('x1', `${x}`);
                        boundaryLine.setAttribute('x2', `${x}`);
                        boundaryLine.setAttribute('y1', `${topY}`);
                        boundaryLine.setAttribute('y2', `${bottomY}`);
                        boundaryLine.setAttribute('stroke', color);
                        boundaryLine.setAttribute('stroke-width', '2');
                        boundaryLine.setAttribute('stroke-dasharray', '4 2');
                        boundaryLine.setAttribute('vector-effect', 'non-scaling-stroke');
                        segmentOverlay.appendChild(boundaryLine);

                        const capTop = document.createElementNS(ns, 'circle');
                        capTop.setAttribute('cx', `${x}`);
                        capTop.setAttribute('cy', `${topY}`);
                        capTop.setAttribute('r', '3.5');
                        capTop.setAttribute('fill', color);
                        capTop.setAttribute('stroke', '#0f172a');
                        capTop.setAttribute('stroke-width', '1');
                        segmentOverlay.appendChild(capTop);

                        const capBottom = document.createElementNS(ns, 'circle');
                        capBottom.setAttribute('cx', `${x}`);
                        capBottom.setAttribute('cy', `${bottomY}`);
                        capBottom.setAttribute('r', '3.5');
                        capBottom.setAttribute('fill', color);
                        capBottom.setAttribute('stroke', '#0f172a');
                        capBottom.setAttribute('stroke-width', '1');
                        segmentOverlay.appendChild(capBottom);
                    };

                    drawBoundary(startX, '#0f766e');
                    drawBoundary(endX, '#b45309');
                }
            }
        },
        [segmentRange],
    );

    useEffect(() => {
        if (isFocusedView) return undefined;
        if (!hostRef.current) return undefined;
        const hasBands = Object.values(chrBandsRef.current || {})[0]?.length;
        if (!hasBands) return undefined;

        if (hostRef.current) {
            hostRef.current.innerHTML = '';
        }
        diseaseMarkerSignatureRef.current = '';

        const ideogram = new Ideogram({
            container: `#${containerId}`,
            organism: 'human',
            chrBands: chrBandsRef.current,
            chromosomes: [chromosome],
            orientation: 'horizontal',
            rotatable: false,
            showChromosomeLabels: false,
            chromosomeHeight: 400,
            chrWidth: 60,
            zoomable: false,
        });

        let detach = null;
        let raf = null;
        const tryBind = () => {
            const svg = hostRef.current?.querySelector('svg#_ideogram');
            if (!svg) return false;
            svgRef.current = svg;
            const bandsEls = svg.querySelectorAll('.bands .band');
            if (!bandsEls.length) return false;

            const resolveBandXRange = () => {
                const nodes = svg.querySelectorAll('.bands .band');
                if (!nodes.length) return null;
                let left = Number.POSITIVE_INFINITY;
                let right = Number.NEGATIVE_INFINITY;
                nodes.forEach((node) => {
                    const rect = node.getBoundingClientRect();
                    if (!Number.isFinite(rect.left) || !Number.isFinite(rect.right)) return;
                    left = Math.min(left, rect.left);
                    right = Math.max(right, rect.right);
                });
                if (!Number.isFinite(left) || !Number.isFinite(right) || right <= left) return null;
                return { left, right };
            };

            const updateCursorPos = (clientX, node, bandName) => {
                if (node && bandName) {
                    const band = bandLookup.get(String(bandName).toLowerCase());
                    const meta = bandSummary(band);
                    const rect = node.getBoundingClientRect();
                    if (
                        Number.isFinite(rect.left) &&
                        Number.isFinite(rect.right) &&
                        rect.right > rect.left &&
                        Number.isFinite(meta?.start) &&
                        Number.isFinite(meta?.stop) &&
                        meta.stop > meta.start
                    ) {
                        const clampedX = Math.min(Math.max(clientX, rect.left), rect.right);
                        const ratio = (clampedX - rect.left) / (rect.right - rect.left);
                        const bp = Math.round(meta.start + ratio * (meta.stop - meta.start));
                        if (!Number.isNaN(bp)) {
                            setCursorPos(bp);
                            return;
                        }
                    }
                }

                const range = resolveBandXRange();
                if (!range || clientX < range.left || clientX > range.right) {
                    setCursorPos(null);
                    return;
                }
                const clampedX = Math.min(Math.max(clientX, range.left), range.right);
                const ratio = (clampedX - range.left) / (range.right - range.left);
                const span = chrSpanRef.current || { start: 0, stop: 0 };
                const bp = Math.round(span.start + ratio * (span.stop - span.start));
                if (!Number.isNaN(bp)) {
                    setCursorPos(bp);
                } else {
                    setCursorPos(null);
                }
            };

            const handleSvgMove = (e) => {
                const node =
                    e.target?.closest?.('.bands .band') || findBandByPoint(e.clientX, e.clientY);
                const bandName = node ? extractBandName(node) : '';
                updateCursorPos(e.clientX, node, bandName);
                setRibbonBandTextRef.current?.(bandName);
                if (bandName) {
                    activateHoverRef.current?.(bandName);
                } else {
                    hoverBandRef.current = null;
                }
            };

            const handleSvgLeave = () => {
                hoverBandRef.current = null;
                setCursorPos(null);
            };

            const callBandSelect = (bandName) => {
                if (bandName) onBandFocusRef.current?.(bandName);
            };

            const findBandByPoint = (pageX, pageY) => {
                const nodes = svg.querySelectorAll('.bands .band');
                return Array.from(nodes).find((node) => {
                    const rect = node.getBoundingClientRect();
                    return (
                        pageX >= rect.left &&
                        pageX <= rect.right &&
                        pageY >= rect.top &&
                        pageY <= rect.bottom
                    );
                });
            };

            const handleClick = (e) => {
                const bandName = extractBandName(e.currentTarget);
                callBandSelect(bandName);
            };

            const handleSvgClick = (e) => {
                const node =
                    e.target?.closest?.('.bands .band') || findBandByPoint(e.clientX, e.clientY);
                if (node) {
                    callBandSelect(extractBandName(node));
                }
            };

            const handleResize = () => {
                updateScaleRef.current?.(svg);
                renderDiseaseMarkers(svg);
            };
            const handleLegendSync = () => syncLegendColors(svg);

            bandsEls.forEach((el) => {
                el.style.cursor = 'pointer';
                el.addEventListener('click', handleClick);
            });

            window.addEventListener('resize', handleResize);
            window.addEventListener('resize', handleLegendSync);
            svg.addEventListener('click', handleSvgClick);
            svg.addEventListener('mousemove', handleSvgMove);
            svg.addEventListener('mouseleave', handleSvgLeave);
            updateScaleRef.current?.(svg);
            syncLegendColors(svg);
            renderDiseaseMarkers(svg);

            detach = () => {
                bandsEls.forEach((el) => {
                    el.removeEventListener('click', handleClick);
                });
                window.removeEventListener('resize', handleResize);
                window.removeEventListener('resize', handleLegendSync);
                svg.removeEventListener('click', handleSvgClick);
                svg.removeEventListener('mousemove', handleSvgMove);
                svg.removeEventListener('mouseleave', handleSvgLeave);
            };
            return true;
        };

        const loop = () => {
            if (tryBind()) return;
            raf = requestAnimationFrame(loop);
        };
        loop();

        return () => {
            if (raf) cancelAnimationFrame(raf);
            detach?.();
            ideogram?.destroy?.();
        };
    }, [
        bandStructureKey,
        chromosome,
        containerId,
        isFocusedView,
        renderDiseaseMarkers,
        syncLegendColors,
        bandLookup,
    ]);

    useEffect(() => {
        const globalClick = (e) => {
            if (!hostRef.current) return;
            if (!hostRef.current.contains(e.target)) return;
            const svg = hostRef.current.querySelector('svg');
            if (!svg) return;
            const node =
                e.target?.closest?.('.bands .band') ||
                Array.from(svg.querySelectorAll('.bands .band')).find((node) => {
                    const rect = node.getBoundingClientRect();
                    return (
                        e.clientX >= rect.left &&
                        e.clientX <= rect.right &&
                        e.clientY >= rect.top &&
                        e.clientY <= rect.bottom
                    );
                });
            if (!node) return;
            const bandName = extractBandName(node);
            if (bandName) {
                onBandFocusRef.current?.(bandName);
                e.stopPropagation();
            }
        };
        document.addEventListener('click', globalClick, true);
        return () => document.removeEventListener('click', globalClick, true);
    }, []);

    useEffect(
        () => () => {
            if (hoverDiseaseTimerRef.current) {
                clearTimeout(hoverDiseaseTimerRef.current);
                hoverDiseaseTimerRef.current = null;
            }
            onHoverContextChange?.(null);
        },
        [onHoverContextChange],
    );

    useEffect(() => {
        const host = hostRef.current;
        let raf = null;
        const highlight = () => {
            const svg = host?.querySelector('svg#_ideogram');
            if (!svg) {
                raf = requestAnimationFrame(highlight);
                return;
            }
            svg.querySelectorAll('.cv-band-highlight').forEach((el) =>
                el.classList.remove('cv-band-highlight'),
            );
            if (!focusedBand) return;
            const nodes = svg.querySelectorAll('.bands .band');
            const target = Array.from(nodes).find((node) => extractBandName(node) === focusedBand);
            if (target) {
                target.classList.add('cv-band-highlight');
            }
        };
        highlight();
        return () => {
            if (raf) cancelAnimationFrame(raf);
            const svg = host?.querySelector('svg#_ideogram');
            svg?.querySelectorAll('.cv-band-highlight').forEach((el) =>
                el.classList.remove('cv-band-highlight'),
            );
        };
    }, [focusedBand]);

    useEffect(() => {
        const handleMove = (e) => {
            const host = hostRef.current;
            if (!host) return;
            const rect = host.getBoundingClientRect();
            const inside =
                e.clientX >= rect.left &&
                e.clientX <= rect.right &&
                e.clientY >= rect.top &&
                e.clientY <= rect.bottom;
            if (!inside) {
                hoverBandRef.current = null;
            }
        };
        window.addEventListener('mousemove', handleMove);
        return () => window.removeEventListener('mousemove', handleMove);
    }, []);

    useEffect(() => {
        const handleBlankClick = (e) => {
            if (!hostRef.current) return;
            const withinHost = hostRef.current.contains(e.target);
            if (withinHost) {
                const bandNode = e.target?.closest?.('.bands .band');
                if (bandNode) return;
            }
            clearHover();
            onBandFocus?.(null);
        };
        document.addEventListener('mousedown', handleBlankClick);
        return () => document.removeEventListener('mousedown', handleBlankClick);
    }, [clearHover, onBandFocus]);

    const displayPreview = useMemo(() => {
        if (hoverPreview) return hoverPreview;
        if (hoverContext?.pinned && hoverContext.chromosome === chromosome) return hoverContext;
        return null;
    }, [chromosome, hoverContext, hoverPreview]);

    const hoverPreviewItems = useMemo(
        () => (displayPreview?.diseases || []).slice(0, 5),
        [displayPreview],
    );
    const hoverPreviewTotal = Number.isFinite(displayPreview?.totalCount)
        ? displayPreview.totalCount
        : displayPreview?.diseases?.length || 0;

    const detailClass = ['cv-detail', focusedBandData ? 'cv-detail-zooming' : '']
        .filter(Boolean)
        .join(' ');
    const cursorLabel = cursorPos != null ? `${cursorPos.toLocaleString()} bp` : '--';
    const focusedBandGeneReady = focusedBandData?.bandId
        ? Boolean(bandGenesReadyMap[String(focusedBandData.bandId)])
        : false;
    const handleBackAction = useCallback(() => {
        if (!focusedBandData) {
            onBack?.();
            return;
        }
        if (focusLayer === 'locus') {
            setFocusLayer('gene');
            return;
        }
        if (focusLayer === 'gene') {
            handleOpenBandLayer();
            return;
        }
        onBandFocus?.(null);
    }, [focusLayer, focusedBandData, handleOpenBandLayer, onBack, onBandFocus]);

    return (
        <Stack gap={2} className={detailClass}>
            {!focusedBandData && (
                <Stack
                    direction="row"
                    justifyContent="space-between"
                    alignItems="center"
                    className="cv-detail-header"
                >
                    <div>
                        <Typography variant="h6" className="cv-detail-title">
                            Chromosome {chromosome}
                        </Typography>
                    </div>
                    <Stack direction="row" gap={1}>
                        <Button
                            variant="outlined"
                            onClick={handleBackAction}
                            className="cv-back-btn"
                        >
                            BACK
                        </Button>
                    </Stack>
                </Stack>
            )}
            {focusedBandData ? (
                <Box
                    className={`cv-focus-panel cv-focus-panel-overlay${focusLayer === 'gene' ? ' is-gene-layer' : ''}${focusLayer === 'gene' && hasCaseData ? ' is-gene-compare' : ''}${focusLayer === 'locus' ? ' is-locus-layer' : ''}`}
                >
                    <Stack
                        direction="row"
                        justifyContent="space-between"
                        alignItems="center"
                        className="cv-focus-head"
                    >
                        <div>
                            <Typography variant="h6">{focusHeaderTitle}</Typography>
                            {focusedBandMeta && (
                                <Typography variant="body2" color="text.secondary">
                                    {focusedBandMeta.start?.toLocaleString() || '--'} -{' '}
                                    {focusedBandMeta.stop?.toLocaleString() || '--'} -{' '}
                                    {focusedBandMeta.stain || 'NA'}
                                </Typography>
                            )}
                        </div>
                        <Stack direction="row" gap={1}>
                            <Button
                                variant="outlined"
                                onClick={handleBackAction}
                                className="cv-back-btn"
                            >
                                BACK
                            </Button>
                        </Stack>
                    </Stack>
                    {hasCaseData && caseChromosomeState.error ? (
                        <Typography variant="caption" color="error" sx={{ mt: -0.5 }}>
                            {caseChromosomeState.error}
                        </Typography>
                    ) : null}
                    {navigationNotice ? (
                        <Typography variant="caption" color="warning.main" sx={{ mt: -0.5 }}>
                            {navigationNotice}
                        </Typography>
                    ) : null}
                    {focusLayer === 'gene' &&
                    hasCaseData &&
                    !caseGeneTrackState.loading &&
                    !caseGenesForCompare.length ? (
                        <Typography variant="caption" color="text.secondary" sx={{ mt: -0.5 }}>
                            No patient-hit genes were found in this band. Knowledge track remains
                            available.
                        </Typography>
                    ) : null}
                    {focusLayer === 'gene' && hasCaseData && caseGeneTrackState.error ? (
                        <Typography variant="caption" color="error" sx={{ mt: -0.5 }}>
                            {caseGeneTrackState.error}
                        </Typography>
                    ) : null}
                    {focusLayer === 'band' && (
                        <>
                            <div className="cv-focus-zoom">
                                <BandZoom
                                    chromosome={chromosome}
                                    band={focusedBandWithAll}
                                    highlightBandName={focusedBandData?.name}
                                    diseaseCount={focusedBandDiseaseCount}
                                    caseVariantCount={focusedBandCaseVariantCount}
                                    caseOverlapVariantCount={focusedBandCaseOverlapCount}
                                    enableDiseaseMarkers
                                    onBandActivate={handleOpenGeneLayer}
                                />
                            </div>
                            <Box className="cv-focus-meta">
                                <Stack
                                    direction="row"
                                    justifyContent="space-between"
                                    alignItems="center"
                                >
                                    <Typography
                                        variant="subtitle2"
                                        className="cv-linked-diseases-title"
                                    >
                                        Linked diseases
                                    </Typography>
                                    <Typography
                                        variant="caption"
                                        color="text.secondary"
                                        className="cv-linked-diseases-stats"
                                    >
                                        {!focusedBandGeneReady || focusedBandGeneLoading
                                            ? 'Loading gene coordinates...'
                                            : focusedBandLoading
                                              ? 'Loading linked diseases...'
                                              : `${focusedBandDiseases.length} item${focusedBandDiseases.length === 1 ? '' : 's'} - ${focusedBandGenes.length} genes`}
                                    </Typography>
                                </Stack>
                                {hasCaseData ? (
                                    <Typography variant="caption" color="text.secondary">
                                        Patient variants in this band:{' '}
                                        {focusedBandCaseVariantCount.toLocaleString()}
                                    </Typography>
                                ) : null}
                                {focusedBandLoading && !focusedBandDiseases.length ? (
                                    <Typography variant="body2" color="text.secondary">
                                        Loading disease records for this band...
                                    </Typography>
                                ) : focusedBandDiseases.length > 0 ? (
                                    <Box className="cv-focus-disease-cards">
                                        {focusedBandDiseases.map((dz) => {
                                            const diseaseName = dz.name || 'Disease';
                                            const genes = extractGenesFromDisease(dz);
                                            const locusSummary = summarizeDisplayItems(
                                                dz.locus || dz.Chromosomal_Loci,
                                                LINKED_DISEASE_MAX_LOCUS_ITEMS,
                                            );
                                            const visibleGenes = genes.slice(
                                                0,
                                                LINKED_DISEASE_MAX_GENE_ITEMS,
                                            );
                                            const hiddenGeneCount = Math.max(
                                                0,
                                                genes.length - visibleGenes.length,
                                            );
                                            return (
                                                <div
                                                    className="cv-focus-disease-card"
                                                    key={dz.id || dz.orpha || dz.name}
                                                >
                                                    <div className="head">
                                                        <Tooltip
                                                            arrow
                                                            placement="top-start"
                                                            disableInteractive
                                                            enterDelay={70}
                                                            leaveDelay={30}
                                                            title={
                                                                <span className="cv-locus-hover-tooltip-text">
                                                                    <span className="cv-locus-hover-row">
                                                                        <span className="cv-locus-hover-label">
                                                                            Disease:
                                                                        </span>{' '}
                                                                        <span className="cv-locus-hover-value">
                                                                            {diseaseName}
                                                                        </span>
                                                                    </span>
                                                                </span>
                                                            }
                                                            classes={{
                                                                popper: 'cv-locus-hover-popper cv-disease-name-hover-popper',
                                                                tooltip:
                                                                    'cv-locus-hover-tooltip cv-disease-name-hover-tooltip',
                                                                arrow: 'cv-locus-hover-tooltip-arrow cv-disease-name-hover-tooltip-arrow',
                                                            }}
                                                            PopperProps={{
                                                                modifiers: [
                                                                    {
                                                                        name: 'offset',
                                                                        options: {
                                                                            offset: [0, 14],
                                                                        },
                                                                    },
                                                                    {
                                                                        name: 'preventOverflow',
                                                                        options: {
                                                                            boundary: 'viewport',
                                                                            padding: 12,
                                                                        },
                                                                    },
                                                                    {
                                                                        name: 'flip',
                                                                        options: {
                                                                            fallbackPlacements: [
                                                                                'top',
                                                                                'bottom',
                                                                            ],
                                                                        },
                                                                    },
                                                                ],
                                                            }}
                                                        >
                                                            <strong>{diseaseName}</strong>
                                                        </Tooltip>
                                                        {dz.orpha ? (
                                                            <span className="pill">
                                                                Orpha {dz.orpha}
                                                            </span>
                                                        ) : null}
                                                    </div>
                                                    <div className="meta">
                                                        {locusSummary.text ? (
                                                            <span>
                                                                {locusSummary.text}
                                                                {locusSummary.truncated ? (
                                                                    <em className="cv-inline-etc">
                                                                        {' '}
                                                                        等
                                                                    </em>
                                                                ) : null}
                                                            </span>
                                                        ) : (
                                                            <span className="muted">
                                                                No locus provided
                                                            </span>
                                                        )}
                                                        {dz.locus_type || dz.Locus_Type ? (
                                                            <span className="muted">
                                                                {dz.locus_type || dz.Locus_Type}
                                                            </span>
                                                        ) : null}
                                                    </div>
                                                    {dz.definition && (
                                                        <p className="muted" title={dz.definition}>
                                                            {dz.definition}
                                                        </p>
                                                    )}
                                                    {dz.description && (
                                                        <p title={dz.description}>
                                                            {dz.description}
                                                        </p>
                                                    )}
                                                    {(dz.moi || dz.onset) && (
                                                        <div className="kv">
                                                            {dz.moi && (
                                                                <span>
                                                                    <strong>Inheritance:</strong>{' '}
                                                                    {Array.isArray(dz.moi)
                                                                        ? dz.moi.join(', ')
                                                                        : dz.moi}
                                                                </span>
                                                            )}
                                                            {dz.onset && (
                                                                <span>
                                                                    <strong>Onset:</strong>{' '}
                                                                    {Array.isArray(dz.onset)
                                                                        ? dz.onset.join(', ')
                                                                        : dz.onset}
                                                                </span>
                                                            )}
                                                        </div>
                                                    )}
                                                    {genes.length > 0 && (
                                                        <div className="genes">
                                                            <strong>Associated genes:</strong>
                                                            <div className="gene-pills">
                                                                {visibleGenes.map((g) => (
                                                                    <button
                                                                        key={g}
                                                                        type="button"
                                                                        className={`gene-pill cv-gene-pill-btn${caseHitGeneSymbols.has(String(g).trim().toUpperCase()) ? ' is-hit' : ''}`}
                                                                        onClick={() =>
                                                                            handleGeneSelect({
                                                                                name: g,
                                                                                gene: g,
                                                                            })
                                                                        }
                                                                    >
                                                                        {g}
                                                                    </button>
                                                                ))}
                                                                {hiddenGeneCount > 0 ? (
                                                                    <span className="gene-more-text">
                                                                        等 {hiddenGeneCount} 个
                                                                    </span>
                                                                ) : null}
                                                            </div>
                                                        </div>
                                                    )}
                                                </div>
                                            );
                                        })}
                                    </Box>
                                ) : (
                                    <Typography variant="body2" color="text.secondary">
                                        No disease records for this band.
                                    </Typography>
                                )}
                            </Box>
                        </>
                    )}
                    {focusLayer === 'gene' && (
                        <GeneZoom
                            band={{ ...focusedBandData, chr: chromosome }}
                            genes={genesForActiveView}
                            patientGenes={caseGenesForCompare}
                            compareMode={hasCaseData}
                            patientLoading={caseGeneTrackState.loading}
                            selectedGene={selectedGene}
                            importantGeneNames={importantGeneSymbolsForActiveView}
                            chromosomeSpan={chrSpan}
                            spanPrefetchState={effectiveGeneSpanPrefetchState}
                            onGeneSelect={handleGeneSelect}
                        />
                    )}
                    {focusLayer === 'locus' && (
                        <LocusZoom
                            band={{ ...focusedBandData, chr: chromosome }}
                            gene={selectedGeneData || { name: selectedGene, gene: selectedGene }}
                            variants={variantState.variants}
                            knowledgeVariants={variantState.knowledgeVariants}
                            patientVariants={variantState.caseVariants}
                            compareMode={hasCaseData}
                            loading={variantState.loading}
                            error={variantState.error}
                            preferredVariantExactKey={preferredVariantExactKey}
                            onWindowChange={setLocusWindowRange}
                            onVariantSelect={onVariantSelect}
                        />
                    )}
                </Box>
            ) : (
                <Box className="cv-ideo-detail" style={{ position: 'relative' }}>
                    <Box id={containerId} className="cv-ideo-canvas" ref={hostRef} />
                    <Box className="cv-hover-info">
                        <div className="cv-hover-header">
                            <Typography variant="subtitle2">
                                {displayPreview?.bandName
                                    ? `Diseases on band ${displayPreview.bandName}`
                                    : 'Hover over the chromosome'}
                            </Typography>
                            {displayPreview?.bandName && (
                                <Typography variant="body2" color="text.secondary">
                                    {hoverPreviewTotal} linked disease{hoverPreviewTotal === 1 ? '' : 's'}
                                </Typography>
                            )}
                        </div>
                        <Box className="cv-hover-disease-list">
                            {displayPreview?.loading && !hoverPreviewItems.length ? (
                                <Typography variant="body2" color="text.secondary">
                                    Loading diseases for this band...
                                </Typography>
                            ) : hoverPreviewItems.length ? (
                                hoverPreviewItems.map((dz) => {
                                    const variantType = dz.locus_type || dz.Locus_Type;
                                    return (
                                        <div
                                            key={dz.id || dz.orpha || dz.name}
                                            className="cv-hover-disease-card"
                                        >
                                            <strong>{dz.name || 'Disease'}</strong>
                                            <span className="meta">
                                                <span className="meta-top">
                                                    <span>
                                                        {dz.orpha
                                                            ? `Orpha ${dz.orpha}`
                                                            : 'No Orpha ID'}
                                                    </span>
                                                    {variantType ? (
                                                        <span className="meta-type">
                                                            {variantType}
                                                        </span>
                                                    ) : null}
                                                </span>
                                                {dz.locus || dz.Chromosomal_Loci ? (
                                                    <em>{dz.locus || dz.Chromosomal_Loci}</em>
                                                ) : null}
                                            </span>
                                            {dz.description ? <p>{dz.description}</p> : null}
                                        </div>
                                    );
                                })
                            ) : displayPreview?.bandName ? (
                                <Typography variant="body2" color="text.secondary">
                                    No diseases linked to this band.
                                </Typography>
                            ) : null}
                        </Box>
                        {hoverPreviewItems.length > 0 ? (
                            <Typography variant="caption" color="text.secondary">
                                Showing {hoverPreviewItems.length} of {hoverPreviewTotal} diseases
                                for this band.
                            </Typography>
                        ) : null}
                    </Box>
                </Box>
            )}
            {!focusedBandData && (
                <Box className="cv-detail-bottom">
                    <div className="cv-ribbon">
                        <div className="cv-ribbon-left">
                            <span>{ribbonBand}</span>
                            <div className="cv-ribbon-position">Position: {cursorLabel}</div>
                        </div>
                        {segmentSummaryText ? (
                            <div className="cv-ribbon-segment">{segmentSummaryText}</div>
                        ) : null}
                    </div>
                    <div className="cv-bottom-section">
                        <div className="cv-bottom-row">
                            <div className="cv-scale-card">
                                <div className="rule" style={{ width: `${scaleInfo.width}px` }} />
                                <div className="label">{scaleInfo.label}</div>
                            </div>
                            <div className="cv-bottom-right">
                                <div className="cv-legend-card" ref={legendRef}>
                                    <span className="lg">
                                        <i className="sw gneg" /> gneg <i className="sw gpos25" />{' '}
                                        gpos25 <i className="sw gpos50" /> gpos50{' '}
                                        <i className="sw gpos75" /> gpos75{' '}
                                        <i className="sw gpos100" /> gpos100{' '}
                                        <i className="sw gvar" /> gvar <i className="sw stalk" />{' '}
                                        stalk <i className="sw acen" /> acen
                                    </span>
                                </div>
                            </div>
                        </div>
                    </div>
                </Box>
            )}
        </Stack>
    );
};

function extractBandName(node) {
    if (!node) return '';
    const dataName = node.getAttribute('data-name');
    if (dataName) return dataName;
    const id = node.getAttribute('id') || '';
    const marker = '-9606-';
    const idx = id.indexOf(marker);
    if (idx >= 0) {
        return id.slice(idx + marker.length).replace(/-/g, '.');
    }
    const token = id.split('-').pop();
    return token ? token.replace(/-/g, '.') : '';
}

function resolveDiseaseId(disease) {
    if (!disease) return null;
    return (
        disease.disease_id ??
        disease.diseaseId ??
        disease.id ??
        disease.orpha_number ??
        disease.orphaNumber ??
        disease.orpha ??
        disease.omim_id ??
        disease.omimId ??
        disease.disease_name ??
        disease.diseaseName ??
        disease.name ??
        null
    );
}

function normalizeDiseaseRecord(disease) {
    if (!disease) return null;
    const id = resolveDiseaseId(disease);
    return {
        ...disease,
        id,
        name:
            disease.disease_name ??
            disease.diseaseName ??
            disease.disease_name_cn ??
            disease.diseaseNameCn ??
            disease.name ??
            'Disease',
        orpha: disease.orpha_number ?? disease.orphaNumber ?? disease.orpha,
        locus: disease.locus ?? disease.Chromosomal_Loci,
        locus_type: disease.locus_type ?? disease.Locus_Type,
    };
}

function extractGeneSymbols(rows = []) {
    const safe = Array.isArray(rows) ? rows : [];
    const symbols = safe
        .map(
            (item) =>
                item?.gene_symbol ??
                item?.geneSymbol ??
                item?.gene_name ??
                item?.geneName ??
                item?.gene ??
                item?.symbol ??
                item?.name,
        )
        .filter(Boolean)
        .map((item) => String(item).trim().toUpperCase())
        .filter(Boolean);
    return Array.from(new Set(symbols));
}

function tokenizeDisplayItems(value) {
    if (value == null) return [];
    const splitToTokens = (source) =>
        String(source || '')
            .split(/[\n;,，；]+/)
            .map((item) => item.trim())
            .filter(Boolean);
    if (Array.isArray(value)) {
        return value.flatMap((item) => splitToTokens(item));
    }
    return splitToTokens(value);
}

function summarizeDisplayItems(value, maxItems = 6) {
    const list = Array.from(new Set(tokenizeDisplayItems(value)));
    if (!list.length) {
        return {
            text: '',
            fullText: '',
            truncated: false,
            hiddenCount: 0,
        };
    }
    const safeMax = Math.max(1, Number(maxItems) || 1);
    const visible = list.slice(0, safeMax);
    const hiddenCount = Math.max(0, list.length - visible.length);
    return {
        text: visible.join('; '),
        fullText: list.join('; '),
        truncated: hiddenCount > 0,
        hiddenCount,
    };
}

function resolveDiseaseCount(summary) {
    if (!summary) return null;
    const raw =
        summary.disease_count ??
        summary.diseaseCount ??
        summary.disease_total ??
        summary.diseaseTotal;
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : null;
}

export default ChromosomeDetail;
