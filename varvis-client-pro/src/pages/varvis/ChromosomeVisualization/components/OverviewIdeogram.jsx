import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Autocomplete from '@mui/material/Autocomplete';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import Ideogram from 'ideogram';
import {
    fetchCaseSmallVariantsByRegion,
    fetchCaseStructuralVariantsByRegion,
    fetchCaseOverlapBandsByChromosome,
    fetchCaseMatchedVariants,
    fetchAllBandDiseaseCounts,
    fetchBandDiseaseCountsByChromosome,
    fetchGeneDetail,
    fetchGeneSearch,
} from '../services/cytobands';

const bandSummaryStateCache = {
    map: {},
};

const geneOptionStateCache = {
    list: null,
};

const caseBandMarkerCache = {
    map: new Map(),
};

const caseOverlapBandMarkerCache = {
    map: new Map(),
};

const caseMatchedVariantCache = {
    map: new Map(),
};

const CASE_REGION_PAGE_SIZE = 5000;
const CASE_FETCH_CONCURRENCY = 3;
const CASE_OVERLAP_FETCH_CONCURRENCY = 6;
const CASE_MATCHED_PAGE_SIZE = 500;
const CASE_MATCHED_MAX_PAGES = 200;

const toFiniteNumber = (value) => {
    if (value == null || value === '') return NaN;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : NaN;
};

const overlapsRange = (startA, stopA, startB, stopB) =>
    Number.isFinite(startA) &&
    Number.isFinite(stopA) &&
    Number.isFinite(startB) &&
    Number.isFinite(stopB) &&
    Math.max(startA, startB) <= Math.min(stopA, stopB);

const runWithConcurrency = async (items, limit, worker) => {
    if (!Array.isArray(items) || !items.length) return [];
    const concurrency = Math.max(1, Math.min(limit, items.length));
    const results = new Array(items.length);
    let cursor = 0;
    const runners = Array.from({ length: concurrency }).map(async () => {
        while (cursor < items.length) {
            const index = cursor;
            cursor += 1;
            if (index >= items.length) break;
            // eslint-disable-next-line no-await-in-loop
            results[index] = await worker(items[index], index);
        }
    });
    await Promise.all(runners);
    return results;
};

const areBandMapsEqual = (left, right) => {
    if (left === right) return true;
    if (!(left instanceof Map) || !(right instanceof Map)) return false;
    if (left.size !== right.size) return false;
    for (const [key, value] of left.entries()) {
        if (right.get(key) !== value) return false;
    }
    return true;
};

const areAnchorMapsEqual = (left, right) => {
    if (left === right) return true;
    if (!(left instanceof Map) || !(right instanceof Map)) return false;
    if (left.size !== right.size) return false;
    for (const [key, value] of left.entries()) {
        const next = right.get(key);
        if (!next) return false;
        if (
            value?.geneSymbol !== next?.geneSymbol ||
            value?.start !== next?.start ||
            value?.end !== next?.end ||
            value?.ref !== next?.ref ||
            value?.alt !== next?.alt ||
            value?.variantType !== next?.variantType ||
            value?.exactKey !== next?.exactKey
        ) {
            return false;
        }
    }
    return true;
};

const normalizeClinicalText = (value) => {
    if (value == null) return '';
    const text = String(value).trim();
    if (!text || text === '-' || text === '--') return '';
    return text;
};

const resolveClinicalBucket = (value) => {
    const text = normalizeClinicalText(value).toLowerCase();
    if (!text) return 'unknown';
    if (text.includes('pathogenic')) return 'pathogenic';
    if (text.includes('uncertain') || text.includes('conflict')) return 'uncertain';
    if (text.includes('benign')) return 'benign';
    return 'other';
};

const resolveClinicalPriority = (bucket) => {
    switch (String(bucket || '').toLowerCase()) {
        case 'pathogenic':
            return 4;
        case 'uncertain':
            return 3;
        case 'other':
            return 2;
        case 'unknown':
            return 1;
        case 'benign':
            return 0;
        default:
            return 1;
    }
};

const OverviewIdeogram = ({
    cytobands,
    onChromosomeSelect,
    chromosomes = [],
    onSegmentNavigate,
    onGeneNavigate,
    onVariantNavigate,
    diseaseCounts = [],
    initialGeneSelection = null,
    onGeneSelectionChange,
    initialSegmentState = null,
    onSegmentStateChange,
    activeCase = null,
    hideAuxiliaryPanels = false,
    activeToolPanel = null,
    onToolPanelClose,
}) => {
    const hostRef = useRef(null);
    const containerId = 'cv-ideo-overview';
    const mountedRef = useRef(false);
    const [selectedGene, setSelectedGene] = useState(null);
    const [geneQuery, setGeneQuery] = useState('');
    const [allGeneOptions, setAllGeneOptions] = useState(
        Array.isArray(geneOptionStateCache.list) ? geneOptionStateCache.list : [],
    );
    const [geneOptions, setGeneOptions] = useState([]);
    const [geneSearchError, setGeneSearchError] = useState('');
    const [allGeneLoading, setAllGeneLoading] = useState(false);
    const [geneSearchLoading, setGeneSearchLoading] = useState(false);
    const [activeMarkerLevel, setActiveMarkerLevel] = useState(null);
    const activeMarkerLevelRef = useRef(null);
    const markerRafRef = useRef(null);
    const markerRetryRef = useRef(0);
    const ideogramViewRef = useRef(null);
    const markerOverlayRef = useRef(null);
    const matchedListRef = useRef(null);
    const summaryCacheRef = useRef(new Set());
    const [segmentChromosome, setSegmentChromosome] = useState(
        initialSegmentState?.chromosome ?? null,
    );
    const [segmentStart, setSegmentStart] = useState(initialSegmentState?.start ?? '');
    const [segmentEnd, setSegmentEnd] = useState(initialSegmentState?.end ?? '');
    const [segmentResults, setSegmentResults] = useState(
        Array.isArray(initialSegmentState?.results) ? initialSegmentState.results : [],
    );
    const [segmentError, setSegmentError] = useState(initialSegmentState?.error || '');
    const [segmentSearched, setSegmentSearched] = useState(Boolean(initialSegmentState?.searched));
    const [bandSummaryMap, setBandSummaryMap] = useState(() => ({ ...bandSummaryStateCache.map }));
    const [caseBandMap, setCaseBandMap] = useState(() => new Map());
    const [caseOverlapBandMap, setCaseOverlapBandMap] = useState(() => new Map());
    const [caseOverlapAnchorMap, setCaseOverlapAnchorMap] = useState(() => new Map());
    const [matchedVariantsState, setMatchedVariantsState] = useState({
        loading: false,
        error: '',
        rows: [],
    });
    const [showMatchedOverlay, setShowMatchedOverlay] = useState(true);
    const [linkedBandKey, setLinkedBandKey] = useState('');
    const [linkedVariantKey, setLinkedVariantKey] = useState('');
    const overviewChromosomeHeight = 650;
    const overviewContainerHeight = overviewChromosomeHeight + 200;

    const normalizeChromosomeLabel = useCallback((value) => {
        if (value == null) return '';
        const text = String(value).trim().toUpperCase();
        if (!text) return '';
        return text.replace(/^CHR/, '');
    }, []);

    const chrBands = useMemo(() => {
        const mapped = {};
        Object.entries(cytobands || {}).forEach(([chr, bands]) => {
            mapped[chr] = (bands || []).map((b) => ({
                bp: [b.start, b.stop],
                chr,
                name: b.name,
                gieStain: b.gieStain,
            }));
        });
        return mapped;
    }, [cytobands]);

    const diseaseBandMap = useMemo(() => {
        const map = new Map();
        Object.entries(cytobands || {}).forEach(([chr, bands = []]) => {
            const normalizedChr = normalizeChromosomeLabel(chr);
            bands.forEach((band) => {
                const id = band?.bandId ?? band?.band_id;
                if (id == null) return;
                const summary = bandSummaryMap[id];
                const count = summary?.diseaseCount ?? summary?.disease_count ?? 0;
                if (count > 0) {
                    map.set(`${normalizedChr}:${band.name}`, count);
                }
            });
        });
        return map;
    }, [bandSummaryMap, cytobands, normalizeChromosomeLabel]);

    const bandRangeMap = useMemo(() => {
        const map = new Map();
        Object.entries(cytobands || {}).forEach(([chr, bands = []]) => {
            const normalizedChr = normalizeChromosomeLabel(chr);
            (Array.isArray(bands) ? bands : []).forEach((band) => {
                const start = toFiniteNumber(band?.start);
                const stop = toFiniteNumber(band?.stop);
                if (!Number.isFinite(start) || !Number.isFinite(stop)) return;
                const key = `${normalizedChr}:${band?.name || ''}`;
                map.set(key, {
                    start: Math.round(Math.min(start, stop)),
                    end: Math.round(Math.max(start, stop)),
                });
            });
        });
        return map;
    }, [cytobands, normalizeChromosomeLabel]);

    const chromosomeBandList = useMemo(() => {
        const next = new Map();
        Object.entries(cytobands || {}).forEach(([chr, bands = []]) => {
            const normalizedChr = normalizeChromosomeLabel(chr);
            const sortedBands = (Array.isArray(bands) ? bands : [])
                .map((band) => {
                    const start = toFiniteNumber(band?.start);
                    const stop = toFiniteNumber(band?.stop);
                    if (!Number.isFinite(start) || !Number.isFinite(stop)) return null;
                    return {
                        name: band?.name || '',
                        start: Math.round(Math.min(start, stop)),
                        end: Math.round(Math.max(start, stop)),
                    };
                })
                .filter(Boolean)
                .sort((a, b) => a.start - b.start);
            if (normalizedChr && sortedBands.length) {
                next.set(normalizedChr, sortedBands);
            }
        });
        return next;
    }, [cytobands, normalizeChromosomeLabel]);

    const resolveBandKeyByRange = useCallback(
        (chromosome, start, end) => {
            const normalizedChr = normalizeChromosomeLabel(chromosome);
            const startNum = toFiniteNumber(start);
            const endNum = toFiniteNumber(end);
            if (!normalizedChr || !Number.isFinite(startNum) || !Number.isFinite(endNum)) return '';
            const rangeStart = Math.round(Math.min(startNum, endNum));
            const rangeEnd = Math.round(Math.max(startNum, endNum));
            const bands = chromosomeBandList.get(normalizedChr) || [];
            if (!bands.length) return '';
            let bestBand = null;
            let bestOverlap = -1;
            bands.forEach((band) => {
                const overlap = Math.min(rangeEnd, band.end) - Math.max(rangeStart, band.start);
                if (overlap >= 0 && overlap > bestOverlap) {
                    bestBand = band;
                    bestOverlap = overlap;
                }
            });
            if (bestBand?.name) {
                return `${normalizedChr}:${bestBand.name}`;
            }
            const center = (rangeStart + rangeEnd) / 2;
            const fallback = bands.find((band) => center >= band.start && center <= band.end);
            return fallback?.name ? `${normalizedChr}:${fallback.name}` : '';
        },
        [chromosomeBandList, normalizeChromosomeLabel],
    );

    const caseBandTopologyKey = useMemo(() => {
        const parts = [];
        Object.entries(cytobands || {})
            .sort((a, b) => String(a[0]).localeCompare(String(b[0])))
            .forEach(([chr, bands = []]) => {
                const normalizedChr = normalizeChromosomeLabel(chr);
                (Array.isArray(bands) ? bands : []).forEach((band) => {
                    const start = toFiniteNumber(band?.start);
                    const stop = toFiniteNumber(band?.stop);
                    parts.push(`${normalizedChr}:${band?.name || ''}:${start}:${stop}`);
                });
            });
        return parts.join('|');
    }, [cytobands, normalizeChromosomeLabel]);

    useEffect(() => {
        const caseId = activeCase?.caseId;
        if (!caseId || !caseBandTopologyKey) {
            setCaseBandMap((prev) => (prev.size ? new Map() : prev));
            return;
        }
        const cacheKey = `${caseId}:${caseBandTopologyKey}`;
        const cached = caseBandMarkerCache.map.get(cacheKey);
        if (cached && typeof cached === 'object') {
            const cachedMap = new Map(Object.entries(cached));
            setCaseBandMap((prev) => (areBandMapsEqual(prev, cachedMap) ? prev : cachedMap));
            return;
        }

        let cancelled = false;
        const loadCaseBandMarkers = async () => {
            const chromosomeEntries = Object.entries(cytobands || {})
                .map(([chr, bands = []]) => {
                    const normalizedChr = normalizeChromosomeLabel(chr);
                    const normalizedBands = (Array.isArray(bands) ? bands : [])
                        .map((band) => {
                            const start = toFiniteNumber(band?.start);
                            const stop = toFiniteNumber(band?.stop);
                            if (!Number.isFinite(start) || !Number.isFinite(stop)) return null;
                            return {
                                key: `${normalizedChr}:${band?.name || ''}`,
                                start: Math.min(start, stop),
                                stop: Math.max(start, stop),
                            };
                        })
                        .filter(Boolean);
                    if (!normalizedChr || !normalizedBands.length) return null;
                    return {
                        chromosome: normalizedChr,
                        bands: normalizedBands,
                        start: Math.min(...normalizedBands.map((item) => item.start)),
                        end: Math.max(...normalizedBands.map((item) => item.stop)),
                    };
                })
                .filter(Boolean);

            const nextMap = new Map();
            chromosomeEntries.forEach((entry) => {
                entry.bands.forEach((band) => {
                    nextMap.set(band.key, 0);
                });
            });

            const collectPaged = async (fetcher) => {
                let page = 1;
                const rows = [];
                let keep = true;
                while (keep) {
                    // eslint-disable-next-line no-await-in-loop
                    const chunk = await fetcher(page, CASE_REGION_PAGE_SIZE);
                    const safe = Array.isArray(chunk) ? chunk : [];
                    if (safe.length) rows.push(...safe);
                    if (safe.length < CASE_REGION_PAGE_SIZE || page >= 200) {
                        keep = false;
                    } else {
                        page += 1;
                    }
                }
                return rows;
            };

            await runWithConcurrency(chromosomeEntries, CASE_FETCH_CONCURRENCY, async (entry) => {
                const [smallRows, structuralRows] = await Promise.all([
                    collectPaged((page, pageSize) =>
                        fetchCaseSmallVariantsByRegion({
                            caseId,
                            chromosome: entry.chromosome,
                            start: Math.round(entry.start),
                            end: Math.round(entry.end),
                            page,
                            pageSize,
                        }),
                    ),
                    collectPaged((page, pageSize) =>
                        fetchCaseStructuralVariantsByRegion({
                            caseId,
                            chromosome: entry.chromosome,
                            start: Math.round(entry.start),
                            end: Math.round(entry.end),
                            page,
                            pageSize,
                        }),
                    ),
                ]);
                const rows = [...smallRows, ...structuralRows];
                if (!rows.length) return;
                rows.forEach((row) => {
                    const start = toFiniteNumber(
                        row?.position ?? row?.start_bp ?? row?.startBp ?? row?.start,
                    );
                    const end = toFiniteNumber(
                        row?.end_bp ??
                            row?.endBp ??
                            row?.end ??
                            row?.position ??
                            row?.start_bp ??
                            row?.startBp ??
                            row?.start,
                    );
                    if (!Number.isFinite(start) || !Number.isFinite(end)) return;
                    const rangeStart = Math.min(start, end);
                    const rangeEnd = Math.max(start, end);
                    entry.bands.forEach((band) => {
                        if (!overlapsRange(rangeStart, rangeEnd, band.start, band.stop)) return;
                        nextMap.set(band.key, (nextMap.get(band.key) || 0) + 1);
                    });
                });
            });

            if (cancelled) return;
            const plainObject = Object.fromEntries(nextMap.entries());
            caseBandMarkerCache.map.set(cacheKey, plainObject);
            setCaseBandMap((prev) => (areBandMapsEqual(prev, nextMap) ? prev : nextMap));
        };

        loadCaseBandMarkers().catch((error) => {
            if (cancelled) return;
            console.warn('[overview] failed to load case markers', error);
            setCaseBandMap((prev) => (prev.size ? new Map() : prev));
        });

        return () => {
            cancelled = true;
        };
    }, [activeCase?.caseId, caseBandTopologyKey, cytobands, normalizeChromosomeLabel]);

    useEffect(() => {
        const caseId = activeCase?.caseId;
        if (!caseId) {
            setMatchedVariantsState((prev) =>
                prev.loading || prev.error || prev.rows.length
                    ? { loading: false, error: '', rows: [] }
                    : prev,
            );
            return;
        }
        const cacheKey = `${caseId}:overview:matched`;
        const cached = caseMatchedVariantCache.map.get(cacheKey);
        if (Array.isArray(cached)) {
            setMatchedVariantsState((prev) =>
                prev.loading || prev.error || prev.rows !== cached
                    ? { loading: false, error: '', rows: cached }
                    : prev,
            );
            return;
        }

        let cancelled = false;
        const loadMatchedVariants = async () => {
            setMatchedVariantsState((prev) => ({
                ...prev,
                loading: true,
                error: '',
            }));
            const rows = [];
            let page = 1;
            let keep = true;
            while (keep) {
                // eslint-disable-next-line no-await-in-loop
                const chunk = await fetchCaseMatchedVariants({
                    caseId,
                    page,
                    pageSize: CASE_MATCHED_PAGE_SIZE,
                });
                const safe = Array.isArray(chunk) ? chunk : [];
                if (safe.length) {
                    rows.push(...safe);
                }
                if (safe.length < CASE_MATCHED_PAGE_SIZE || page >= CASE_MATCHED_MAX_PAGES) {
                    keep = false;
                } else {
                    page += 1;
                }
            }
            if (cancelled) return;
            caseMatchedVariantCache.map.set(cacheKey, rows);
            setMatchedVariantsState({
                loading: false,
                error: '',
                rows,
            });
        };

        loadMatchedVariants().catch((error) => {
            if (cancelled) return;
            console.warn('[overview] failed to load case matched variants', error);
            setMatchedVariantsState({
                loading: false,
                error: 'Failed to load matched variants.',
                rows: [],
            });
        });

        return () => {
            cancelled = true;
        };
    }, [activeCase?.caseId]);

    useEffect(() => {
        const caseId = activeCase?.caseId;
        if (!caseId || !caseBandTopologyKey) {
            setCaseOverlapBandMap((prev) => (prev.size ? new Map() : prev));
            setCaseOverlapAnchorMap((prev) => (prev.size ? new Map() : prev));
            return;
        }
        const cacheKey = `${caseId}:${caseBandTopologyKey}`;
        const cached = caseOverlapBandMarkerCache.map.get(cacheKey);
        if (cached && typeof cached === 'object') {
            const cachedCountMap = new Map(Object.entries(cached?.counts || {}));
            const cachedAnchorMap = new Map(Object.entries(cached?.anchors || {}));
            setCaseOverlapBandMap((prev) =>
                areBandMapsEqual(prev, cachedCountMap) ? prev : cachedCountMap,
            );
            setCaseOverlapAnchorMap((prev) =>
                areAnchorMapsEqual(prev, cachedAnchorMap) ? prev : cachedAnchorMap,
            );
            return;
        }

        const chromosomeList = Object.keys(cytobands || {})
            .map((chr) => normalizeChromosomeLabel(chr))
            .filter(Boolean);
        if (!chromosomeList.length) {
            setCaseOverlapBandMap((prev) => (prev.size ? new Map() : prev));
            setCaseOverlapAnchorMap((prev) => (prev.size ? new Map() : prev));
            return;
        }

        let cancelled = false;
        const loadOverlapBandMarkers = async () => {
            const countMap = new Map();
            const anchorMap = new Map();
            await runWithConcurrency(
                chromosomeList,
                CASE_OVERLAP_FETCH_CONCURRENCY,
                async (chromosomeKey) => {
                    const rows = await fetchCaseOverlapBandsByChromosome({
                        caseId,
                        chromosome: chromosomeKey,
                    });
                    (Array.isArray(rows) ? rows : []).forEach((item) => {
                        const bandName = item?.bandName ?? item?.band_name;
                        if (!bandName) return;
                        const normalizedChr = normalizeChromosomeLabel(
                            item?.chromosome || item?.chr || chromosomeKey,
                        );
                        const key = `${normalizedChr}:${bandName}`;
                        const count = Number(item?.overlapCount ?? item?.overlap_count ?? 0);
                        if (!Number.isFinite(count) || count <= 0) return;
                        countMap.set(key, Math.max(0, Math.round(count)));

                        const anchorStart = toFiniteNumber(
                            item?.anchorStartBp ??
                                item?.anchor_start_bp ??
                                item?.anchorPosition ??
                                item?.anchor_position,
                        );
                        const anchorEndRaw = toFiniteNumber(
                            item?.anchorEndBp ?? item?.anchor_end_bp,
                        );
                        const anchorEnd = Number.isFinite(anchorEndRaw)
                            ? anchorEndRaw
                            : anchorStart;
                        const anchorExactKey = item?.anchorExactKey ?? item?.anchor_exact_key ?? '';
                        const anchorGeneSymbol =
                            item?.anchorGeneSymbol ?? item?.anchor_gene_symbol ?? null;
                        if (!Number.isFinite(anchorStart) && !anchorExactKey && !anchorGeneSymbol)
                            return;

                        anchorMap.set(key, {
                            chromosome: normalizedChr,
                            bandName: String(bandName),
                            geneSymbol: anchorGeneSymbol
                                ? String(anchorGeneSymbol).trim().toUpperCase()
                                : null,
                            start: Number.isFinite(anchorStart)
                                ? Math.round(Math.min(anchorStart, anchorEnd))
                                : null,
                            end: Number.isFinite(anchorEnd)
                                ? Math.round(Math.max(anchorStart, anchorEnd))
                                : null,
                            ref: item?.anchorRef ?? item?.anchor_ref ?? null,
                            alt: item?.anchorAlt ?? item?.anchor_alt ?? null,
                            variantType:
                                item?.anchorVariantType ?? item?.anchor_variant_type ?? null,
                            exactKey: anchorExactKey || null,
                            knowledgeClinicalSignificance:
                                item?.anchorKnowledgeClinicalSignificance ??
                                item?.anchor_knowledge_clinical_significance ??
                                null,
                            variantId: item?.anchorVariantId ?? item?.anchor_variant_id ?? null,
                        });
                    });
                },
            );

            if (cancelled) return;
            caseOverlapBandMarkerCache.map.set(cacheKey, {
                counts: Object.fromEntries(countMap.entries()),
                anchors: Object.fromEntries(anchorMap.entries()),
            });
            setCaseOverlapBandMap((prev) => (areBandMapsEqual(prev, countMap) ? prev : countMap));
            setCaseOverlapAnchorMap((prev) =>
                areAnchorMapsEqual(prev, anchorMap) ? prev : anchorMap,
            );
        };

        loadOverlapBandMarkers().catch((error) => {
            if (cancelled) return;
            console.warn('[overview] failed to load overlap case markers', error);
            setCaseOverlapBandMap((prev) => (prev.size ? new Map() : prev));
            setCaseOverlapAnchorMap((prev) => (prev.size ? new Map() : prev));
        });

        return () => {
            cancelled = true;
        };
    }, [activeCase?.caseId, caseBandTopologyKey, cytobands, normalizeChromosomeLabel]);

    useEffect(() => {
        let alive = true;
        const loadSummaries = async () => {
            const chrList = Object.keys(cytobands || {});
            if (!chrList.length) {
                if (alive) {
                    setBandSummaryMap({});
                    bandSummaryStateCache.map = {};
                }
                return;
            }
            summaryCacheRef.current = new Set();
            try {
                const rows = await fetchAllBandDiseaseCounts();
                if (Array.isArray(rows)) {
                    const updates = {};
                    rows.forEach((item) => {
                        const id = item?.band_id ?? item?.bandId;
                        if (id == null) return;
                        if (summaryCacheRef.current.has(String(id))) return;
                        summaryCacheRef.current.add(String(id));
                        updates[String(id)] = {
                            disease_count: item?.disease_count ?? item?.diseaseCount ?? 0,
                        };
                    });
                    if (alive && Object.keys(updates).length) {
                        setBandSummaryMap((prev) => ({
                            ...prev,
                            ...updates,
                        }));
                        bandSummaryStateCache.map = {
                            ...bandSummaryStateCache.map,
                            ...updates,
                        };
                        return;
                    }
                }
            } catch (err) {
                // fall back to per-chromosome queries
            }

            const queue = [...chrList];
            const concurrency = Math.min(6, queue.length || 1);
            const workers = Array.from({ length: concurrency }).map(async () => {
                while (queue.length) {
                    const chr = queue.shift();
                    if (!chr) continue;
                    try {
                        const rows = await fetchBandDiseaseCountsByChromosome(chr);
                        if (!Array.isArray(rows)) continue;
                        const updates = {};
                        rows.forEach((item) => {
                            const id = item?.band_id ?? item?.bandId;
                            if (id == null) return;
                            if (summaryCacheRef.current.has(String(id))) return;
                            summaryCacheRef.current.add(String(id));
                            updates[String(id)] = {
                                disease_count: item?.disease_count ?? item?.diseaseCount ?? 0,
                            };
                        });
                        if (alive && Object.keys(updates).length) {
                            setBandSummaryMap((prev) => ({
                                ...prev,
                                ...updates,
                            }));
                            bandSummaryStateCache.map = {
                                ...bandSummaryStateCache.map,
                                ...updates,
                            };
                        }
                    } catch (err) {
                        // ignore per chromosome failure
                    }
                }
            });
            await Promise.all(workers);
        };
        loadSummaries();
        return () => {
            alive = false;
        };
    }, [cytobands]);

    const extractBandName = useCallback((node) => {
        const dataName = node.getAttribute?.('data-name');
        if (dataName) return dataName;
        const id = node.getAttribute?.('id') || '';
        const marker = '-9606-';
        const idx = id.indexOf(marker);
        if (idx >= 0) {
            return id.slice(idx + marker.length).replace(/-/g, '.');
        }
        const token = id.split('-').pop();
        return token ? token.replace(/-/g, '.') : '';
    }, []);

    const extractChromosome = useCallback(
        (node) => {
            const group = node.closest?.('g.chromosome') || node.closest?.('g.chr');
            const dataName = group?.__data__?.name;
            if (dataName) return normalizeChromosomeLabel(dataName);
            const groupId = group?.getAttribute?.('id') || '';
            const groupMatch = /^chr([^-\s]+)-9606/.exec(groupId);
            if (groupMatch) return normalizeChromosomeLabel(groupMatch[1]);
            const nodeId = node.getAttribute?.('id') || '';
            const nodeMatch = /^chr([^-\s]+)-9606/.exec(nodeId);
            return nodeMatch ? normalizeChromosomeLabel(nodeMatch[1]) : '';
        },
        [normalizeChromosomeLabel],
    );

    const applyDiseaseMarkers = useCallback(() => {
        const svg = hostRef.current?.querySelector('svg#_ideogram');
        if (!svg) return false;
        const hostRect = hostRef.current?.getBoundingClientRect?.();
        if (!hostRect) return false;
        if (!ideogramViewRef.current) {
            const viewBox = svg.getAttribute('viewBox');
            const rect = svg.getBoundingClientRect?.();
            const widthPx = rect?.width ? Math.round(rect.width) : null;
            const heightPx = rect?.height ? Math.round(rect.height) : null;
            ideogramViewRef.current = {
                viewBox,
                widthPx,
                heightPx,
            };
        }
        const { viewBox, widthPx, heightPx } = ideogramViewRef.current || {};
        if (hideAuxiliaryPanels) {
            const responsiveViewBox =
                viewBox || (widthPx && heightPx ? `0 0 ${widthPx} ${heightPx}` : null);
            if (responsiveViewBox) svg.setAttribute('viewBox', responsiveViewBox);
            const responsiveWidth = Math.max(1, Math.floor(hostRect.width));
            svg.setAttribute('width', `${responsiveWidth}`);
            svg.style.width = '100%';
            svg.style.maxWidth = '100%';
        } else {
            if (viewBox) {
                svg.setAttribute('viewBox', viewBox);
            } else {
                svg.removeAttribute('viewBox');
            }
            if (widthPx) svg.setAttribute('width', `${widthPx}`);
            svg.style.removeProperty('width');
            svg.style.removeProperty('max-width');
        }
        if (heightPx) svg.setAttribute('height', `${heightPx}`);
        svg.setAttribute('preserveAspectRatio', 'xMinYMin meet');
        const markerOverlay = markerOverlayRef.current;
        if (!markerOverlay) return false;
        while (markerOverlay.firstChild) {
            markerOverlay.removeChild(markerOverlay.firstChild);
        }
        markerOverlay.setAttribute('width', `${hostRect.width}`);
        markerOverlay.setAttribute('height', `${hostRect.height}`);
        markerOverlay.setAttribute('viewBox', `0 0 ${hostRect.width} ${hostRect.height}`);
        svg.querySelectorAll('.bands .band').forEach((node) => {
            const bandName = extractBandName(node);
            const chr = extractChromosome(node);
            if (!bandName || !chr) return;
            const key = `${chr}:${bandName}`;
            const count = diseaseBandMap.get(key) || 0;
            const caseCount = caseBandMap.get(key) || 0;
            const overlapCount = caseOverlapBandMap.get(key) || 0;
            const hasCaseHit = overlapCount > 0;
            if (count <= 0 && caseCount <= 0 && !hasCaseHit) return;
            const bandRect = node.getBoundingClientRect?.();
            if (!bandRect) return;
            const midPoint = {
                x: bandRect.left - hostRect.left + bandRect.width / 2,
                y: bandRect.top - hostRect.top + bandRect.height / 2,
            };
            const y = midPoint.y;
            if (count > 0) {
                const level =
                    count >= 11 ? 'cv-disease-l3' : count >= 6 ? 'cv-disease-l2' : 'cv-disease-l1';
                const activeLevel = activeMarkerLevelRef.current;
                if (!activeLevel || level === activeLevel) {
                    const x1 = midPoint.x + 6;
                    const x2 = x1 + 1;
                    const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
                    line.setAttribute('x1', `${x1}`);
                    line.setAttribute('x2', `${x2}`);
                    line.setAttribute('y1', `${y}`);
                    line.setAttribute('y2', `${y}`);
                    line.setAttribute('class', `cv-disease-marker ${level}`);
                    markerOverlay.appendChild(line);
                    const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
                    dot.setAttribute('cx', `${x2 + 5}`);
                    dot.setAttribute('cy', `${y}`);
                    dot.setAttribute('r', '3.5');
                    dot.setAttribute('class', `cv-disease-marker-dot ${level}`);
                    markerOverlay.appendChild(dot);
                }
            }

            if (caseCount > 0 || hasCaseHit) {
                const anchor = caseOverlapAnchorMap.get(key) || null;
                const bandRange = bandRangeMap.get(key) || null;
                const isLinked = linkedBandKey && key === linkedBandKey;
                const handleHitClick = (event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    if (
                        anchor &&
                        anchor.geneSymbol &&
                        Number.isFinite(anchor.start) &&
                        Number.isFinite(anchor.end) &&
                        onVariantNavigate
                    ) {
                        onVariantNavigate({
                            chromosome: chr,
                            bandName,
                            segmentRange: {
                                start: Math.round(Math.min(anchor.start, anchor.end)),
                                end: Math.round(Math.max(anchor.start, anchor.end)),
                            },
                            variantAnchor: {
                                ...anchor,
                                chromosome: chr,
                                bandName,
                            },
                        });
                        return;
                    }
                    onSegmentNavigate?.({
                        chromosome: chr,
                        bandName,
                        segmentRange: bandRange
                            ? {
                                  start: bandRange.start,
                                  end: bandRange.end,
                              }
                            : null,
                        notice: 'matched_without_anchor',
                    });
                };

                const x1 = midPoint.x - 6;
                const x2 = hasCaseHit ? x1 - 14 : x1 - 1;
                const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
                line.setAttribute('x1', `${x1}`);
                line.setAttribute('x2', `${x2}`);
                line.setAttribute('y1', `${y}`);
                line.setAttribute('y2', `${y}`);
                line.setAttribute(
                    'class',
                    `cv-case-marker${hasCaseHit ? ' cv-case-marker-hit' : ''}${isLinked ? ' is-linked' : ''}`,
                );
                if (hasCaseHit) {
                    line.setAttribute('data-case-hit-marker', '1');
                    line.setAttribute('data-band-key', key);
                    line.style.cursor = 'pointer';
                    line.style.pointerEvents = 'all';
                    line.addEventListener('click', handleHitClick);
                    line.addEventListener('mouseenter', () => {
                        setLinkedBandKey(key);
                        setLinkedVariantKey('');
                    });
                    line.addEventListener('mouseleave', () => {
                        setLinkedBandKey((prev) => (prev === key ? '' : prev));
                    });
                }
                markerOverlay.appendChild(line);
                const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
                dot.setAttribute('cx', `${x2 - 5}`);
                dot.setAttribute('cy', `${y}`);
                dot.setAttribute('r', hasCaseHit ? '4.8' : '3.4');
                dot.setAttribute(
                    'class',
                    `cv-case-marker-dot${hasCaseHit ? ' cv-case-marker-dot-hit' : ''}${isLinked ? ' is-linked' : ''}`,
                );
                if (hasCaseHit) {
                    dot.setAttribute('data-case-hit-marker', '1');
                    dot.setAttribute('data-band-key', key);
                    dot.style.cursor = 'pointer';
                    dot.style.pointerEvents = 'all';
                    dot.addEventListener('click', handleHitClick);
                    dot.addEventListener('mouseenter', () => {
                        setLinkedBandKey(key);
                        setLinkedVariantKey('');
                    });
                    dot.addEventListener('mouseleave', () => {
                        setLinkedBandKey((prev) => (prev === key ? '' : prev));
                    });
                }
                markerOverlay.appendChild(dot);
            }
        });
        return true;
    }, [
        bandRangeMap,
        caseBandMap,
        caseOverlapAnchorMap,
        caseOverlapBandMap,
        diseaseBandMap,
        extractBandName,
        extractChromosome,
        hideAuxiliaryPanels,
        linkedBandKey,
        onSegmentNavigate,
        onVariantNavigate,
    ]);

    const normalizeGeneSymbol = useCallback((value) => {
        if (value == null) return '';
        return String(value).trim().toUpperCase();
    }, []);

    const toGeneOption = useCallback(
        (row) => {
            const rawSymbol =
                row?.gene_symbol ??
                row?.geneSymbol ??
                row?.gene_name ??
                row?.geneName ??
                row?.label ??
                row?.value ??
                row;
            const symbol = normalizeGeneSymbol(rawSymbol);
            if (!symbol) return null;
            const startRaw = Number(row?.start_bp ?? row?.startBp ?? row?.start);
            const endRaw = Number(row?.end_bp ?? row?.endBp ?? row?.end ?? row?.stop);
            const chromosome = normalizeChromosomeLabel(row?.chromosome);
            return {
                label: symbol,
                value: symbol,
                chromosome: chromosome || null,
                start: Number.isFinite(startRaw) ? Math.round(startRaw) : null,
                end: Number.isFinite(endRaw) ? Math.round(endRaw) : null,
            };
        },
        [normalizeChromosomeLabel, normalizeGeneSymbol],
    );

    const mergedGeneOptionMap = useMemo(() => {
        const map = new Map();
        [...allGeneOptions, ...geneOptions].forEach((option) => {
            const key = normalizeGeneSymbol(option?.value || option?.label);
            if (!key || map.has(key)) return;
            map.set(key, {
                ...option,
                label: option?.label || key,
                value: key,
            });
        });
        return map;
    }, [allGeneOptions, geneOptions, normalizeGeneSymbol]);

    const selectedOption = useMemo(() => {
        if (!selectedGene) return null;
        const key = normalizeGeneSymbol(selectedGene);
        return (
            mergedGeneOptionMap.get(key) || {
                label: key,
                value: key,
            }
        );
    }, [mergedGeneOptionMap, normalizeGeneSymbol, selectedGene]);

    const filteredGeneOptions = useMemo(() => {
        const keyword = normalizeGeneSymbol(geneQuery);
        if (!keyword) return allGeneOptions;
        const startsWith = [];
        const includes = [];
        allGeneOptions.forEach((option) => {
            const symbol = normalizeGeneSymbol(option?.value || option?.label);
            if (!symbol) return;
            if (symbol.startsWith(keyword)) {
                startsWith.push(option);
            } else if (symbol.includes(keyword)) {
                includes.push(option);
            }
        });
        const localMatches = [...startsWith, ...includes].slice(0, 180);
        const merged = new Map();
        [...geneOptions, ...localMatches].forEach((option) => {
            const key = normalizeGeneSymbol(option?.value || option?.label);
            if (!key || merged.has(key)) return;
            merged.set(key, option);
        });
        if (selectedOption?.value && !merged.has(selectedOption.value)) {
            merged.set(selectedOption.value, selectedOption);
        }
        return Array.from(merged.values());
    }, [allGeneOptions, geneOptions, geneQuery, normalizeGeneSymbol, selectedOption]);

    const loadGeneDetails = useCallback(
        async (symbolValue) => {
            const symbol = normalizeGeneSymbol(symbolValue);
            if (!symbol) return null;
            try {
                return (await fetchGeneDetail(symbol)) || null;
            } catch (err) {
                return null;
            }
        },
        [normalizeGeneSymbol],
    );

    const handleGeneInputChange = useCallback(
        (_, value, reason) => {
            setGeneQuery(value || '');
            setGeneSearchError('');
            if (reason === 'clear') {
                setSelectedGene(null);
                onGeneSelectionChange?.(null);
            }
        },
        [onGeneSelectionChange],
    );

    const handleGeneChange = useCallback(
        (_, option) => {
            if (!option) {
                setSelectedGene(null);
                setGeneSearchError('');
                onGeneSelectionChange?.(null);
                return;
            }
            const symbol = normalizeGeneSymbol(
                typeof option === 'string' ? option : option?.value || option?.label,
            );
            if (!symbol) return;
            setGeneSearchError('');
            setGeneQuery(symbol);
            setSelectedGene(symbol);
            onGeneSelectionChange?.(symbol);
        },
        [normalizeGeneSymbol, onGeneSelectionChange],
    );

    const handleGeneLocate = useCallback(async () => {
        const typedSymbol = normalizeGeneSymbol(geneQuery);
        const selectedSymbol = normalizeGeneSymbol(selectedGene);
        const targetSymbol = typedSymbol || selectedSymbol;
        if (!targetSymbol) {
            setGeneSearchError('Select or enter a gene symbol first.');
            return;
        }
        setGeneSearchError('');
        setSelectedGene(targetSymbol);
        onGeneSelectionChange?.(targetSymbol);
        const detail = await loadGeneDetails(targetSymbol);
        const fallbackOption = mergedGeneOptionMap.get(targetSymbol) || null;
        if (!detail && !fallbackOption) {
            setGeneSearchError(`Gene "${targetSymbol}" was not found.`);
            return;
        }
        const coordinate = detail?.coordinate || {};
        const chromosome = normalizeChromosomeLabel(
            coordinate?.chromosome || detail?.chromosome || fallbackOption?.chromosome || '',
        );
        const startRaw = Number(
            coordinate?.start_bp ??
                coordinate?.startBp ??
                coordinate?.start ??
                fallbackOption?.start,
        );
        const endRaw = Number(
            coordinate?.end_bp ??
                coordinate?.endBp ??
                coordinate?.end ??
                coordinate?.stop ??
                fallbackOption?.end,
        );
        if (!chromosome || !Number.isFinite(startRaw) || !Number.isFinite(endRaw)) {
            setGeneSearchError(
                `Gene "${targetSymbol}" is missing coordinate data and cannot be located.`,
            );
            return;
        }
        setGeneQuery(targetSymbol);
        onGeneNavigate?.({
            geneSymbol: targetSymbol,
            chromosome,
            start: Math.round(Math.min(startRaw, endRaw)),
            end: Math.round(Math.max(startRaw, endRaw)),
        });
    }, [
        geneQuery,
        loadGeneDetails,
        mergedGeneOptionMap,
        normalizeChromosomeLabel,
        normalizeGeneSymbol,
        onGeneSelectionChange,
        onGeneNavigate,
        selectedGene,
    ]);

    const handleGeneClear = useCallback(() => {
        setSelectedGene(null);
        setGeneSearchError('');
        onGeneSelectionChange?.(null);
        setGeneQuery('');
    }, [onGeneSelectionChange]);

    useEffect(() => {
        if (!initialGeneSelection) {
            setSelectedGene(null);
            setGeneQuery('');
            setGeneSearchError('');
            return;
        }
        const symbol = normalizeGeneSymbol(initialGeneSelection);
        setSelectedGene(symbol);
        setGeneQuery(symbol);
        setGeneSearchError('');
    }, [initialGeneSelection, normalizeGeneSymbol]);

    useEffect(() => {
        let active = true;
        if (Array.isArray(geneOptionStateCache.list) && geneOptionStateCache.list.length) {
            setAllGeneOptions(geneOptionStateCache.list);
            return undefined;
        }
        setAllGeneLoading(true);
        const pageSize = 200;
        const maxPages = 200;
        const loadAllGeneOptions = async () => {
            const optionMap = new Map();
            for (let page = 1; page <= maxPages; page += 1) {
                // eslint-disable-next-line no-await-in-loop
                const rows = await fetchGeneSearch('', page, pageSize);
                const safeRows = Array.isArray(rows) ? rows : [];
                safeRows.forEach((item) => {
                    const option = toGeneOption(item);
                    if (!option) return;
                    if (!optionMap.has(option.value)) {
                        optionMap.set(option.value, option);
                    }
                });
                if (safeRows.length < pageSize) break;
            }
            const options = Array.from(optionMap.values()).sort((a, b) =>
                a.value.localeCompare(b.value),
            );
            if (!active) return;
            setAllGeneOptions(options);
            geneOptionStateCache.list = options;
        };
        loadAllGeneOptions()
            .catch(() => {
                if (active) setAllGeneOptions([]);
            })
            .finally(() => {
                if (active) setAllGeneLoading(false);
            });
        return () => {
            active = false;
        };
    }, [toGeneOption]);

    useEffect(() => {
        let active = true;
        if (!geneQuery) {
            setGeneOptions([]);
            return undefined;
        }
        setGeneSearchLoading(true);
        const handle = setTimeout(async () => {
            try {
                const results = await fetchGeneSearch(geneQuery, 1, 30);
                if (!active) return;
                const options = [];
                (Array.isArray(results) ? results : []).forEach((item) => {
                    const option = toGeneOption(item);
                    if (option) options.push(option);
                });
                setGeneOptions(options);
            } catch (err) {
                if (active) setGeneOptions([]);
            } finally {
                if (active) setGeneSearchLoading(false);
            }
        }, 300);
        return () => {
            active = false;
            clearTimeout(handle);
        };
    }, [geneQuery, toGeneOption]);

    const chromosomeOptions = useMemo(
        () =>
            (chromosomes || []).map((ch) => ({
                label: `Chr ${ch}`,
                value: String(ch),
            })),
        [chromosomes],
    );

    const emitSegmentState = useCallback(
        (next) => {
            if (!onSegmentStateChange) return;
            onSegmentStateChange({
                chromosome: next.chromosome ?? null,
                start: next.start ?? '',
                end: next.end ?? '',
                results: Array.isArray(next.results) ? next.results : [],
                error: next.error || '',
                searched: Boolean(next.searched),
            });
        },
        [onSegmentStateChange],
    );

    const handleSegmentChromosome = useCallback(
        (_, option) => {
            const value = option?.value || null;
            setSegmentChromosome(value);
            setSegmentError('');
            setSegmentSearched(false);
            emitSegmentState({
                chromosome: value,
                start: segmentStart,
                end: segmentEnd,
                results: segmentResults,
                error: '',
                searched: false,
            });
        },
        [segmentStart, segmentEnd, segmentResults, emitSegmentState],
    );

    const resetSegment = useCallback(() => {
        setSegmentChromosome(null);
        setSegmentStart('');
        setSegmentEnd('');
        setSegmentResults([]);
        setSegmentError('');
        setSegmentSearched(false);
        emitSegmentState({
            chromosome: null,
            start: '',
            end: '',
            results: [],
            error: '',
            searched: false,
        });
    }, [emitSegmentState]);

    const handleSegmentSearch = useCallback(() => {
        const chr = segmentChromosome;
        if (!chr) {
            setSegmentError('Select a chromosome first.');
            setSegmentSearched(true);
            setSegmentResults([]);
            emitSegmentState({
                chromosome: segmentChromosome,
                start: segmentStart,
                end: segmentEnd,
                results: [],
                error: 'Select a chromosome first.',
                searched: true,
            });
            return;
        }
        const startNum = Number(segmentStart);
        const endNum = Number(segmentEnd);
        if (!Number.isFinite(startNum) || !Number.isFinite(endNum)) {
            setSegmentError('Enter valid numeric coordinates.');
            setSegmentSearched(true);
            setSegmentResults([]);
            emitSegmentState({
                chromosome: segmentChromosome,
                start: segmentStart,
                end: segmentEnd,
                results: [],
                error: 'Enter valid numeric coordinates.',
                searched: true,
            });
            return;
        }
        if (startNum === endNum) {
            setSegmentError('Start and end cannot be the same value.');
            setSegmentSearched(true);
            setSegmentResults([]);
            emitSegmentState({
                chromosome: segmentChromosome,
                start: segmentStart,
                end: segmentEnd,
                results: [],
                error: 'Start and end cannot be the same value.',
                searched: true,
            });
            return;
        }
        const rangeStart = Math.min(startNum, endNum);
        const rangeEnd = Math.max(startNum, endNum);
        const normalizedStart = Math.round(rangeStart);
        const normalizedEnd = Math.round(rangeEnd);
        setSegmentError('');
        setSegmentResults([]);
        setSegmentSearched(true);
        emitSegmentState({
            chromosome: segmentChromosome,
            start: segmentStart,
            end: segmentEnd,
            results: [],
            error: '',
            searched: true,
        });
        onSegmentNavigate?.({
            chromosome: chr,
            bandName: null,
            segmentRange: {
                start: normalizedStart,
                end: normalizedEnd,
            },
        });
    }, [segmentChromosome, segmentStart, segmentEnd, emitSegmentState, onSegmentNavigate]);

    const scheduleMarkerRender = useCallback(() => {
        if (markerRafRef.current) return;
        markerRafRef.current = requestAnimationFrame(() => {
            markerRafRef.current = null;
            const rendered = applyDiseaseMarkers();
            if (!rendered && markerRetryRef.current < 6) {
                markerRetryRef.current += 1;
                scheduleMarkerRender();
            } else {
                markerRetryRef.current = 0;
            }
        });
    }, [applyDiseaseMarkers]);

    useEffect(() => {
        const host = hostRef.current;
        if (!host) return undefined;
        let resizeFrame = null;
        const handleResize = () => {
            if (resizeFrame) cancelAnimationFrame(resizeFrame);
            resizeFrame = requestAnimationFrame(() => {
                resizeFrame = null;
                scheduleMarkerRender();
            });
        };
        const resizeObserver =
            typeof ResizeObserver === 'function' ? new ResizeObserver(handleResize) : null;
        resizeObserver?.observe(host);
        window.addEventListener('resize', handleResize);
        handleResize();
        return () => {
            resizeObserver?.disconnect();
            window.removeEventListener('resize', handleResize);
            if (resizeFrame) cancelAnimationFrame(resizeFrame);
        };
    }, [hideAuxiliaryPanels, scheduleMarkerRender]);

    useEffect(() => {
        activeMarkerLevelRef.current = activeMarkerLevel;
    }, [activeMarkerLevel]);

    useEffect(() => {
        if (!Object.keys(chrBands).length) return undefined;
        const ideogram = new Ideogram({
            container: `#${containerId}`,
            organism: 'human',
            chrBands,
            chromosomes: Object.keys(chrBands),
            showChromosomeLabels: true,
            zoomable: false,
            draggable: false,
            rotatable: false,
            orientation: 'vertical',
            chrWidth: 23,
            chromosomeHeight: overviewChromosomeHeight,
            chrMargin: 26,
            onClickChromosome: (ch) => {
                if (ch) onChromosomeSelect(String(ch));
            },
        });
        mountedRef.current = true;

        let raf = null;
        const applySvgHeight = () => {
            const svg = hostRef.current?.querySelector('svg#_ideogram');
            if (!svg) return false;
            svg.setAttribute('height', `${overviewChromosomeHeight}`);
            const wraps = [
                document.getElementById('_ideogramOuterWrap'),
                document.getElementById('_ideogramMiddleWrap'),
                document.getElementById('_ideogramInnerWrap'),
                document.getElementById('_ideogram'),
            ];
            wraps.forEach((el) => {
                if (el) {
                    el.style.height = `${overviewChromosomeHeight}px`;
                }
            });
            return true;
        };

        const disableScroll = () => {
            const middle = document.getElementById('_ideogramMiddleWrap');
            const right = document.getElementById('_ideogramRightWrap');
            [middle, right].forEach((el) => {
                if (el) {
                    el.style.overflow = 'hidden';
                }
            });
        };
        disableScroll();
        scheduleMarkerRender();
        const applyHeightLoop = () => {
            if (applySvgHeight()) return;
            raf = requestAnimationFrame(applyHeightLoop);
        };
        applyHeightLoop();
        const observer = new MutationObserver(disableScroll);
        const root = document.getElementById('_ideogram');
        if (root) {
            observer.observe(root, { childList: true, subtree: true });
        }
        const markerObserver = new MutationObserver(scheduleMarkerRender);
        if (root) {
            markerObserver.observe(root, { childList: true, subtree: true });
        }

        const chrPattern = /^(?:[1-9]|1[0-9]|2[0-2]|X|Y)$/i;
        const resolveChromosome = (node) => {
            let current = node;
            while (current && current !== hostRef.current) {
                if (current.__data__?.name) {
                    return normalizeChromosomeLabel(current.__data__.name);
                }
                const attr =
                    current.getAttribute?.('data-chr') || current.getAttribute?.('data-name');
                if (attr && chrPattern.test(attr)) {
                    return normalizeChromosomeLabel(attr);
                }
                const text = current.textContent?.trim();
                if (text && chrPattern.test(text)) {
                    return normalizeChromosomeLabel(text);
                }
                current = current.parentElement;
            }
            return null;
        };

        const handleClick = (event) => {
            const isCaseHitMarker = Boolean(event?.target?.closest?.('[data-case-hit-marker="1"]'));
            if (isCaseHitMarker) return;
            const chrLabel = resolveChromosome(event.target);
            if (!chrLabel) return;
            if (event?.cancelable) {
                event.preventDefault();
            }
            event.stopPropagation();
            onChromosomeSelect(chrLabel);
        };

        const host = hostRef.current;
        host?.addEventListener('click', handleClick, true);

        return () => {
            observer.disconnect();
            markerObserver.disconnect();
            if (raf) cancelAnimationFrame(raf);
            if (markerRafRef.current) {
                cancelAnimationFrame(markerRafRef.current);
                markerRafRef.current = null;
            }
            host?.removeEventListener('click', handleClick, true);
            mountedRef.current = false;
            ideogram?.destroy?.();
        };
    }, [
        chrBands,
        onChromosomeSelect,
        diseaseBandMap,
        scheduleMarkerRender,
        normalizeChromosomeLabel,
    ]);

    useEffect(() => {
        scheduleMarkerRender();
    }, [
        scheduleMarkerRender,
        activeMarkerLevel,
        diseaseBandMap,
        caseBandMap,
        caseOverlapBandMap,
        caseOverlapAnchorMap,
        linkedBandKey,
    ]);

    const handleMarkerToggle = useCallback((level) => {
        setActiveMarkerLevel((prev) => (prev === level ? null : level));
    }, []);

    useEffect(() => {
        if (!initialSegmentState) return;
        const nextChrom = initialSegmentState.chromosome ?? null;
        const nextStart = initialSegmentState.start ?? '';
        const nextEnd = initialSegmentState.end ?? '';
        const nextResults = Array.isArray(initialSegmentState.results)
            ? initialSegmentState.results
            : [];
        const nextError = initialSegmentState.error || '';
        const nextSearched = Boolean(initialSegmentState.searched);
        const matches =
            nextChrom === segmentChromosome &&
            nextStart === segmentStart &&
            nextEnd === segmentEnd &&
            nextError === segmentError &&
            nextSearched === segmentSearched &&
            segmentResults === nextResults;
        if (matches) return;
        setSegmentChromosome(nextChrom);
        setSegmentStart(nextStart);
        setSegmentEnd(nextEnd);
        setSegmentResults(nextResults);
        setSegmentError(nextError);
        setSegmentSearched(nextSearched);
    }, [
        initialSegmentState,
        segmentChromosome,
        segmentStart,
        segmentEnd,
        segmentResults,
        segmentError,
        segmentSearched,
    ]);

    const renderSegmentResults = () => {
        const startNum = Number(segmentStart);
        const endNum = Number(segmentEnd);
        if (!Number.isFinite(startNum) || !Number.isFinite(endNum) || !segmentChromosome) {
            return (
                <Typography variant="body2" color="text.secondary">
                    Last locate request failed validation. Please adjust the coordinates and retry.
                </Typography>
            );
        }
        const rangeStart = Math.round(Math.min(startNum, endNum));
        const rangeEnd = Math.round(Math.max(startNum, endNum));
        const span = Math.max(1, rangeEnd - rangeStart + 1);
        return (
            <Typography variant="body2" color="text.secondary">
                Located Chr {segmentChromosome}: {rangeStart.toLocaleString()} -{' '}
                {rangeEnd.toLocaleString()} bp ({span.toLocaleString()} bp). Chromosome Detail
                highlights both boundaries and the interval.
            </Typography>
        );
    };

    const hasCaseData = Boolean(activeCase?.caseId);
    const matchedRows = useMemo(() => {
        const rows = Array.isArray(matchedVariantsState?.rows) ? matchedVariantsState.rows : [];
        return rows
            .map((row, index) => {
                const chromosome = normalizeChromosomeLabel(row?.chromosome);
                const start = toFiniteNumber(
                    row?.start ?? row?.start_bp ?? row?.startBp ?? row?.position,
                );
                const endRaw = toFiniteNumber(
                    row?.end ?? row?.end_bp ?? row?.endBp ?? row?.position ?? start,
                );
                const end = Number.isFinite(endRaw) ? endRaw : start;
                if (!chromosome || !Number.isFinite(start) || !Number.isFinite(end)) return null;
                const rangeStart = Math.round(Math.min(start, end));
                const rangeEnd = Math.round(Math.max(start, end));
                const bandKey = resolveBandKeyByRange(chromosome, rangeStart, rangeEnd);
                const exactKey = String(row?.exactKey || row?.exact_key || '').trim();
                const variantId = row?.variantId ?? row?.variant_id ?? '';
                const rowKey =
                    exactKey || `${chromosome}:${rangeStart}:${rangeEnd}:${variantId || index}`;
                const variantType =
                    row?.variantType ??
                    row?.variant_type ??
                    row?.svType ??
                    row?.sv_type ??
                    row?.type ??
                    'Variant';
                const geneSymbol = String(row?.geneSymbol ?? row?.gene_symbol ?? '')
                    .trim()
                    .toUpperCase();
                const clinicalText = normalizeClinicalText(
                    row?.knowledgeClinicalSignificance ??
                        row?.knowledge_clinical_significance ??
                        row?.clinicalSignificance ??
                        row?.clinical_significance ??
                        '',
                );
                const refAltText = row?.ref && row?.alt ? `${row.ref} > ${row.alt}` : '';
                const coordinateText = `Chr ${chromosome}:${rangeStart.toLocaleString()}${rangeEnd > rangeStart ? ` - ${rangeEnd.toLocaleString()}` : ''}`;
                const bandLabel = bandKey ? bandKey.replace(/^([^:]+):/, 'Band $1:') : '';
                return {
                    ...row,
                    rowKey,
                    exactKey: exactKey || null,
                    chromosome,
                    start: rangeStart,
                    end: rangeEnd,
                    bandKey,
                    variantType,
                    geneSymbol: geneSymbol || '',
                    clinical: clinicalText || 'Matched',
                    clinicalBucket: resolveClinicalBucket(clinicalText),
                    refAltText,
                    coordinateText,
                    bandLabel,
                };
            })
            .filter(Boolean)
            .sort((a, b) => {
                const priorityDiff =
                    resolveClinicalPriority(b?.clinicalBucket) -
                    resolveClinicalPriority(a?.clinicalBucket);
                if (priorityDiff !== 0) return priorityDiff;

                const chrA = normalizeChromosomeLabel(a?.chromosome);
                const chrB = normalizeChromosomeLabel(b?.chromosome);
                const numericA = Number(chrA);
                const numericB = Number(chrB);
                if (
                    Number.isFinite(numericA) &&
                    Number.isFinite(numericB) &&
                    numericA !== numericB
                ) {
                    return numericA - numericB;
                }
                if (chrA !== chrB) {
                    if (chrA === 'X') return 1;
                    if (chrB === 'X') return -1;
                    if (chrA === 'Y') return 1;
                    if (chrB === 'Y') return -1;
                    return String(chrA).localeCompare(String(chrB));
                }
                if (a.start !== b.start) return a.start - b.start;
                if (a.end !== b.end) return a.end - b.end;
                return String(a.rowKey).localeCompare(String(b.rowKey));
            });
    }, [matchedVariantsState?.rows, normalizeChromosomeLabel, resolveBandKeyByRange]);

    const overviewSummary = useMemo(() => {
        const rows = Array.isArray(diseaseCounts) ? diseaseCounts : [];
        const totalDiseases = rows.reduce((sum, entry) => sum + Number(entry?.count || 0), 0);
        const topEntry = rows.reduce((best, entry) => {
            const count = Number(entry?.count || 0);
            if (!best || count > Number(best?.count || 0)) return { ...entry, count };
            return best;
        }, null);
        return {
            chromosomeCount: rows.length || chromosomes.length || 0,
            totalDiseases,
            topChromosome: topEntry?.chromosome || '-',
            topChromosomeCount: Number(topEntry?.count || 0),
            matchedCount: matchedRows.length,
        };
    }, [chromosomes.length, diseaseCounts, matchedRows.length]);

    useEffect(() => {
        if (!hasCaseData) {
            setShowMatchedOverlay(true);
            setLinkedBandKey('');
            setLinkedVariantKey('');
            return;
        }
        setShowMatchedOverlay(true);
    }, [hasCaseData, activeCase?.caseId]);

    useEffect(() => {
        const listNode = matchedListRef.current;
        if (!listNode || !linkedBandKey) return;
        const target = listNode.querySelector(
            `[data-band-key="${linkedBandKey.replace(/"/g, '\\"')}"]`,
        );
        if (!target) return;
        const listRect = listNode.getBoundingClientRect();
        const rowRect = target.getBoundingClientRect();
        const outsideTop = rowRect.top < listRect.top + 8;
        const outsideBottom = rowRect.bottom > listRect.bottom - 8;
        if (outsideTop || outsideBottom) {
            target.scrollIntoView({
                behavior: 'smooth',
                block: 'center',
                inline: 'nearest',
            });
        }
    }, [linkedBandKey, matchedRows]);

    const handleMatchedRowClick = useCallback(
        (row) => {
            if (!row) return;
            const bandKey = row.bandKey || '';
            const parts = String(bandKey).split(':');
            const bandName = parts.length > 1 ? parts.slice(1).join(':') : null;
            const bandRange = bandRangeMap.get(bandKey) || null;
            if (
                row.geneSymbol &&
                Number.isFinite(row.start) &&
                Number.isFinite(row.end) &&
                onVariantNavigate
            ) {
                onVariantNavigate({
                    chromosome: row.chromosome,
                    bandName: bandName || null,
                    segmentRange: {
                        start: Math.round(Math.min(row.start, row.end)),
                        end: Math.round(Math.max(row.start, row.end)),
                    },
                    variantAnchor: {
                        chromosome: row.chromosome,
                        bandName: bandName || null,
                        geneSymbol: row.geneSymbol,
                        start: Math.round(Math.min(row.start, row.end)),
                        end: Math.round(Math.max(row.start, row.end)),
                        ref: row.ref ?? null,
                        alt: row.alt ?? null,
                        variantType: row.variantType ?? null,
                        exactKey: row.exactKey || null,
                    },
                });
                return;
            }
            onSegmentNavigate?.({
                chromosome: row.chromosome,
                bandName: bandName || null,
                segmentRange: bandRange
                    ? {
                          start: bandRange.start,
                          end: bandRange.end,
                      }
                    : {
                          start: Math.round(Math.min(row.start, row.end)),
                          end: Math.round(Math.max(row.start, row.end)),
                      },
                notice: 'matched_without_anchor',
            });
        },
        [bandRangeMap, onSegmentNavigate, onVariantNavigate],
    );

    return (
        <div className="cv-overview-page">
            <div
                className={`cv-overview-layout${hideAuxiliaryPanels ? ' is-visualization-only' : ''}`}
            >
                <div className="cv-overview-main">
                    <section className="cv-overview-visual-card">
                        <div className="cv-overview-visual-head">
                            <div>
                                <Typography variant="h6">Chromosome Visualization</Typography>
                            </div>
                            <div className="cv-marker-filter">
                                <span className="cv-marker-filter-label">Marker density</span>
                                <Button
                                    type="button"
                                    variant="outlined"
                                    size="small"
                                    className={`cv-marker-filter-btn cv-disease-l1${activeMarkerLevel === 'cv-disease-l1' ? ' is-active' : ''}`}
                                    onClick={() => handleMarkerToggle('cv-disease-l1')}
                                >
                                    LOW
                                </Button>
                                <Button
                                    type="button"
                                    variant="outlined"
                                    size="small"
                                    className={`cv-marker-filter-btn cv-disease-l2${activeMarkerLevel === 'cv-disease-l2' ? ' is-active' : ''}`}
                                    onClick={() => handleMarkerToggle('cv-disease-l2')}
                                >
                                    MID
                                </Button>
                                <Button
                                    type="button"
                                    variant="outlined"
                                    size="small"
                                    className={`cv-marker-filter-btn cv-disease-l3${activeMarkerLevel === 'cv-disease-l3' ? ' is-active' : ''}`}
                                    onClick={() => handleMarkerToggle('cv-disease-l3')}
                                >
                                    HIGH
                                </Button>
                            </div>
                        </div>
                        <div
                            className="cv-ideo"
                            ref={hostRef}
                            style={{ height: `${overviewContainerHeight}px` }}
                        >
                            <div
                                id={containerId}
                                className="cv-ideo-host"
                                style={{ height: `${overviewContainerHeight}px` }}
                            />
                            <svg className="cv-ideo-marker-overlay" ref={markerOverlayRef} />
                        </div>
                    </section>
                </div>
                {!hideAuxiliaryPanels && activeToolPanel ? (
                    <div
                        className={`cv-overview-floating-panel is-${activeToolPanel}`}
                        role="dialog"
                        aria-modal="false"
                        aria-label={
                            activeToolPanel === 'navigator'
                                ? 'Genome Navigator panel'
                                : 'Dataset Summary panel'
                        }
                    >
                        <div className="cv-overview-panel-header">
                            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                                {activeToolPanel === 'navigator'
                                    ? 'Genome Navigator'
                                    : 'Dataset Summary'}
                            </Typography>
                            <IconButton
                                type="button"
                                size="small"
                                className="cv-overview-panel-close"
                                aria-label="Close overview tool panel"
                                onClick={onToolPanelClose}
                            >
                                <CloseRoundedIcon fontSize="small" />
                            </IconButton>
                        </div>
                        <div className="cv-overview-panel-body">
                            <Box className="cv-overview-tool-card cv-genome-navigator-card">
                                <div className="cv-overview-tool-head">
                                    <div>
                                        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                                            Genome Navigator
                                        </Typography>
                                        <Typography
                                            className="cv-overview-helper-text"
                                            variant="body2"
                                            color="text.secondary"
                                        >
                                            Jump by gene or genomic interval without competing with
                                            the chromosome view.
                                        </Typography>
                                    </div>
                                </div>
                                <div className="cv-navigator-section">
                                    <Typography variant="subtitle2">Gene search</Typography>
                                    <div className="cv-gene-search-controls">
                                        <div className="cv-gene-action-row">
                                            <div className="cv-gene-autocomplete">
                                                <Autocomplete
                                                    size="small"
                                                    options={filteredGeneOptions}
                                                    value={selectedOption}
                                                    inputValue={geneQuery}
                                                    onChange={handleGeneChange}
                                                    onInputChange={handleGeneInputChange}
                                                    loading={geneSearchLoading || allGeneLoading}
                                                    freeSolo
                                                    forcePopupIcon
                                                    disableClearable={false}
                                                    getOptionLabel={(option) => {
                                                        if (typeof option === 'string')
                                                            return option;
                                                        return option?.label || '';
                                                    }}
                                                    isOptionEqualToValue={(option, value) => {
                                                        const optionValue =
                                                            typeof option === 'string'
                                                                ? option
                                                                : option?.value ||
                                                                  option?.label ||
                                                                  '';
                                                        const targetValue =
                                                            typeof value === 'string'
                                                                ? value
                                                                : value?.value ||
                                                                  value?.label ||
                                                                  '';
                                                        return optionValue === targetValue;
                                                    }}
                                                    renderInput={(params) => (
                                                        <TextField
                                                            {...params}
                                                            label="Search gene"
                                                            placeholder="Type or pick a gene symbol"
                                                            onKeyDown={(event) => {
                                                                if (event.key === 'Enter') {
                                                                    event.preventDefault();
                                                                    handleGeneLocate();
                                                                }
                                                            }}
                                                        />
                                                    )}
                                                />
                                            </div>
                                            <div className="cv-gene-actions">
                                                <Button
                                                    variant="contained"
                                                    size="small"
                                                    onClick={handleGeneLocate}
                                                >
                                                    Search
                                                </Button>
                                                <Button
                                                    size="small"
                                                    className="cv-gene-reset-btn"
                                                    onClick={handleGeneClear}
                                                >
                                                    Reset
                                                </Button>
                                            </div>
                                        </div>
                                        {geneSearchError ? (
                                            <Typography variant="body2" color="error">
                                                {geneSearchError}
                                            </Typography>
                                        ) : null}
                                    </div>
                                    <Typography
                                        className="cv-overview-helper-text"
                                        variant="caption"
                                        color="text.secondary"
                                    >
                                        Opens Chromosome Detail and the gene layer.
                                    </Typography>
                                </div>
                                <div className="cv-navigator-section">
                                    <Typography variant="subtitle2">Segment search</Typography>
                                    <div className="cv-segment-search-controls">
                                        <div className="cv-segment-chr-row">
                                            <Autocomplete
                                                size="small"
                                                options={chromosomeOptions}
                                                value={
                                                    segmentChromosome
                                                        ? chromosomeOptions.find(
                                                              (opt) =>
                                                                  opt.value === segmentChromosome,
                                                          ) || null
                                                        : null
                                                }
                                                onChange={handleSegmentChromosome}
                                                renderInput={(params) => (
                                                    <TextField
                                                        {...params}
                                                        label="Chromosome"
                                                        placeholder="Pick chromosome"
                                                    />
                                                )}
                                            />
                                            <div className="cv-segment-actions">
                                                <Button
                                                    variant="contained"
                                                    size="small"
                                                    onClick={handleSegmentSearch}
                                                >
                                                    Locate
                                                </Button>
                                                <Button size="small" onClick={resetSegment}>
                                                    Reset
                                                </Button>
                                            </div>
                                        </div>
                                        <div className="cv-segment-inputs">
                                            <TextField
                                                label="Start bp"
                                                type="number"
                                                size="small"
                                                value={segmentStart}
                                                onChange={(e) => {
                                                    const value = e.target.value;
                                                    setSegmentStart(value);
                                                    setSegmentError('');
                                                    setSegmentSearched(false);
                                                    emitSegmentState({
                                                        chromosome: segmentChromosome,
                                                        start: value,
                                                        end: segmentEnd,
                                                        results: segmentResults,
                                                        error: '',
                                                        searched: false,
                                                    });
                                                }}
                                            />
                                            <TextField
                                                label="End bp"
                                                type="number"
                                                size="small"
                                                value={segmentEnd}
                                                onChange={(e) => {
                                                    const value = e.target.value;
                                                    setSegmentEnd(value);
                                                    setSegmentError('');
                                                    setSegmentSearched(false);
                                                    emitSegmentState({
                                                        chromosome: segmentChromosome,
                                                        start: segmentStart,
                                                        end: value,
                                                        results: segmentResults,
                                                        error: '',
                                                        searched: false,
                                                    });
                                                }}
                                            />
                                        </div>
                                        {segmentError && (
                                            <Typography variant="body2" color="error">
                                                {segmentError}
                                            </Typography>
                                        )}
                                    </div>
                                    {segmentSearched && (
                                        <div className="cv-segment-result">{renderSegmentResults()}</div>
                                    )}
                                </div>
                            </Box>
                            <div className="cv-overview-tool-card cv-overview-summary">
                                <div className="cv-summary-metrics">
                                    <div className="cv-summary-metric">
                                        <span>Total diseases</span>
                                        <strong>
                                            {overviewSummary.totalDiseases.toLocaleString()}
                                        </strong>
                                    </div>
                                    <div className="cv-summary-metric">
                                        <span>Chromosomes</span>
                                        <strong>{overviewSummary.chromosomeCount}</strong>
                                    </div>
                                    <div className="cv-summary-metric">
                                        <span>Peak chromosome</span>
                                        <strong>
                                            Chr {overviewSummary.topChromosome}{' '}
                                            <small>
                                                {overviewSummary.topChromosomeCount.toLocaleString()}
                                            </small>
                                        </strong>
                                    </div>
                                    <div className="cv-summary-metric">
                                        <span>Matched VCF</span>
                                        <strong>
                                            {hasCaseData
                                                ? overviewSummary.matchedCount.toLocaleString()
                                                : 'No case'}
                                        </strong>
                                    </div>
                                </div>
                                <div
                                    className="cv-chromosome-heatmap"
                                    aria-label="Disease counts by chromosome"
                                >
                                    {(diseaseCounts || []).length ? (
                                        (diseaseCounts || []).map((entry, idx) => {
                                            const count = Number(entry?.count || 0);
                                            const strength =
                                                overviewSummary.topChromosomeCount > 0
                                                    ? count / overviewSummary.topChromosomeCount
                                                    : 0;
                                            const backgroundAlpha = 0.05 + strength * 0.9;
                                            const percentage =
                                                overviewSummary.totalDiseases > 0
                                                    ? (count / overviewSummary.totalDiseases) * 100
                                                    : 0;
                                            const isPeak =
                                                String(entry?.chromosome) ===
                                                String(overviewSummary.topChromosome);
                                            return (
                                                <button
                                                    type="button"
                                                    className={`cv-chromosome-heat-cell${isPeak ? ' is-peak' : ''}`}
                                                    key={`summary-${entry.chromosome}-${idx}`}
                                                    style={{
                                                        backgroundColor: `rgba(13, 111, 209, ${backgroundAlpha.toFixed(3)})`,
                                                    }}
                                                    title={`Chr ${entry.chromosome}: ${count.toLocaleString()} mapped disease records (${percentage.toFixed(1)}%)`}
                                                    aria-label={`Chromosome ${entry.chromosome}, ${count.toLocaleString()} mapped disease records`}
                                                    onClick={() =>
                                                        onChromosomeSelect?.(
                                                            String(entry.chromosome),
                                                        )
                                                    }
                                                >
                                                    <span>Chr {entry.chromosome}</span>
                                                    <strong>{count.toLocaleString()}</strong>
                                                </button>
                                            );
                                        })
                                    ) : (
                                        <div className="cv-summary-empty">
                                            <Typography variant="body2" color="text.secondary">
                                                No disease statistics available.
                                            </Typography>
                                        </div>
                                    )}
                                </div>
                            </div>
                            {hasCaseData ? (
                                <div className="cv-overview-tool-card cv-overview-matched-card">
                                    <div className="cv-summary-header-row">
                                        <div>
                                            <Typography
                                                variant="subtitle1"
                                                sx={{ fontWeight: 700 }}
                                            >
                                                Matched VCF variants
                                            </Typography>
                                            <Typography variant="body2" color="text.secondary">
                                                Case variants linked to visible cytobands.
                                            </Typography>
                                        </div>
                                        <Button
                                            type="button"
                                            size="small"
                                            className="cv-summary-toggle-btn"
                                            onClick={() => setShowMatchedOverlay((prev) => !prev)}
                                        >
                                            {showMatchedOverlay ? 'Hide list' : 'Show list'}
                                        </Button>
                                    </div>
                                    {showMatchedOverlay ? (
                                        <div className="cv-matched-overlay">
                                            <div className="cv-matched-overlay-head">
                                                <Typography
                                                    variant="caption"
                                                    color="text.secondary"
                                                >
                                                    {matchedRows.length.toLocaleString()} matched
                                                    records
                                                </Typography>
                                            </div>
                                            <div
                                                className="cv-matched-overlay-list"
                                                ref={matchedListRef}
                                            >
                                                {matchedVariantsState.loading ? (
                                                    <Typography
                                                        variant="body2"
                                                        color="text.secondary"
                                                    >
                                                        Loading matched variants...
                                                    </Typography>
                                                ) : null}
                                                {!matchedVariantsState.loading &&
                                                matchedVariantsState.error ? (
                                                    <Typography variant="body2" color="error">
                                                        {matchedVariantsState.error}
                                                    </Typography>
                                                ) : null}
                                                {!matchedVariantsState.loading &&
                                                !matchedVariantsState.error &&
                                                !matchedRows.length ? (
                                                    <Typography
                                                        variant="body2"
                                                        color="text.secondary"
                                                    >
                                                        No matched variants found for this VCF.
                                                    </Typography>
                                                ) : null}
                                                {!matchedVariantsState.loading &&
                                                !matchedVariantsState.error &&
                                                matchedRows.length
                                                    ? matchedRows.map((item) => {
                                                          const isBandLinked = Boolean(
                                                              linkedBandKey &&
                                                                  item.bandKey &&
                                                                  item.bandKey === linkedBandKey,
                                                          );
                                                          const isVariantLinked = Boolean(
                                                              linkedVariantKey &&
                                                                  item.rowKey === linkedVariantKey,
                                                          );
                                                          return (
                                                              <button
                                                                  key={item.rowKey}
                                                                  type="button"
                                                                  className={`cv-matched-row${isBandLinked ? ' is-band-linked' : ''}${isVariantLinked ? ' is-variant-linked' : ''}`}
                                                                  data-band-key={item.bandKey || ''}
                                                                  onMouseEnter={() => {
                                                                      setLinkedBandKey(
                                                                          item.bandKey || '',
                                                                      );
                                                                      setLinkedVariantKey(
                                                                          item.rowKey,
                                                                      );
                                                                  }}
                                                                  onMouseLeave={() => {
                                                                      setLinkedBandKey((prev) =>
                                                                          prev === item.bandKey
                                                                              ? ''
                                                                              : prev,
                                                                      );
                                                                      setLinkedVariantKey((prev) =>
                                                                          prev === item.rowKey
                                                                              ? ''
                                                                              : prev,
                                                                      );
                                                                  }}
                                                                  onClick={() =>
                                                                      handleMatchedRowClick(item)
                                                                  }
                                                              >
                                                                  <div className="top">
                                                                      <div className="left">
                                                                          {item.geneSymbol ? (
                                                                              <span className="gene">
                                                                                  {item.geneSymbol}
                                                                              </span>
                                                                          ) : null}
                                                                          <span className="variant-type">
                                                                              {item.variantType ||
                                                                                  'variant'}
                                                                          </span>
                                                                      </div>
                                                                      <span
                                                                          className={`cv-clin-chip is-${item.clinicalBucket}`}
                                                                      >
                                                                          {item.clinical}
                                                                      </span>
                                                                  </div>
                                                                  <div className="line strong">
                                                                      {item.coordinateText}
                                                                  </div>
                                                                  {item.refAltText ? (
                                                                      <div className="line strong">
                                                                          {item.refAltText}
                                                                      </div>
                                                                  ) : null}
                                                                  {item.bandLabel ? (
                                                                      <div className="line muted">
                                                                          {item.bandLabel}
                                                                      </div>
                                                                  ) : null}
                                                              </button>
                                                          );
                                                      })
                                                    : null}
                                            </div>
                                        </div>
                                    ) : (
                                        <Typography variant="body2" color="text.secondary">
                                            The matched VCF list is hidden to keep the overview
                                            compact.
                                        </Typography>
                                    )}
                                </div>
                            ) : null}
                        </div>
                    </div>
                ) : null}
            </div>
        </div>
    );
};

export default OverviewIdeogram;
