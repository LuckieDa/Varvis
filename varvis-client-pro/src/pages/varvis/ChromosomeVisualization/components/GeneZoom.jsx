import React, { useMemo, useEffect, useRef, useState, useCallback } from 'react';
import { Box, Button, Typography } from '@mui/material';
import { parseCoordinate, resolveBandRange } from '../utils/bandHelpers';
import { pickImportantGenes, scoreGeneImportance } from '../utils/geneHelpers';

const LANE_SAFE_GAP_PCT = 0.25;
const MAX_TRACK_LANES = 5;
const FOCUS_GENE_MIN = 6;
const FOCUS_GENE_MAX = 12;
const TRACK_ROW_HEIGHT = 58;
const COMPARE_TRACK_ROW_HEIGHT = 44;
const COMPARE_AXIS_GAP = 70;
const COMPARE_TRACK_TOP_OFFSET = 30;
const TRACK_TOP_PADDING = 24;
const SINGLE_TRACK_MIN_HEIGHT = 300;
const AUTO_ZOOM_COVERAGE_THRESHOLD = 0.2;
const SEGMENT_HEIGHT = 14;
const AXIS_ROW_HEIGHT = 36;
const AXIS_ROW_BOTTOM = -20;
const MIN_VIEW_SPAN_FACTOR = 0.0005;
const BOX_ZOOM_MIN_RATIO = 0.012;
const ZOOM_IN_FACTOR = 0.8;
const GENE_COLOR_PALETTE = [
    {
        fill: '#3f6d8f',
        soft: '#e9f1f7',
        border: '#bfd2e2',
        text: '#2f4f68',
        glow: 'rgba(63, 109, 143, 0.2)',
        estDark: '#6887a1',
        estLight: '#ccd9e5',
    },
    {
        fill: '#5a7a51',
        soft: '#edf3ea',
        border: '#c7d8c1',
        text: '#3f5639',
        glow: 'rgba(90, 122, 81, 0.2)',
        estDark: '#7c9774',
        estLight: '#d4e1cf',
    },
    {
        fill: '#7a667f',
        soft: '#f1ebf3',
        border: '#d8c8dc',
        text: '#5a4a5f',
        glow: 'rgba(122, 102, 127, 0.2)',
        estDark: '#97809c',
        estLight: '#e0d3e3',
    },
    {
        fill: '#7b7252',
        soft: '#f3f0e7',
        border: '#d9d2be',
        text: '#5e563d',
        glow: 'rgba(123, 114, 82, 0.2)',
        estDark: '#988d6d',
        estLight: '#e3dcc8',
    },
    {
        fill: '#4f7f7f',
        soft: '#e8f4f4',
        border: '#bfdbdb',
        text: '#3a5f5f',
        glow: 'rgba(79, 127, 127, 0.2)',
        estDark: '#719797',
        estLight: '#cee3e3',
    },
    {
        fill: '#8a6654',
        soft: '#f5ece8',
        border: '#ddc8be',
        text: '#684c3f',
        glow: 'rgba(138, 102, 84, 0.2)',
        estDark: '#a18577',
        estLight: '#e5d4cc',
    },
    {
        fill: '#5d7392',
        soft: '#ebf0f7',
        border: '#c5d0e1',
        text: '#465871',
        glow: 'rgba(93, 115, 146, 0.2)',
        estDark: '#7e93ae',
        estLight: '#d3ddeb',
    },
    {
        fill: '#6b7b5b',
        soft: '#eef3eb',
        border: '#ccd8c2',
        text: '#4f5d44',
        glow: 'rgba(107, 123, 91, 0.2)',
        estDark: '#88987a',
        estLight: '#d8e1d1',
    },
    {
        fill: '#7c6a6a',
        soft: '#f3eded',
        border: '#dbcaca',
        text: '#5d5050',
        glow: 'rgba(124, 106, 106, 0.2)',
        estDark: '#988383',
        estLight: '#e2d5d5',
    },
    {
        fill: '#576f80',
        soft: '#eaf0f4',
        border: '#c1cfda',
        text: '#415462',
        glow: 'rgba(87, 111, 128, 0.2)',
        estDark: '#7890a0',
        estLight: '#d2dde6',
    },
];

function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
}

function formatBp(value) {
    const parsed = parseCoordinate(value);
    if (!Number.isFinite(parsed)) return '--';
    return `${Math.round(parsed).toLocaleString()} bp`;
}

function formatCompact(value) {
    const parsed = parseCoordinate(value);
    if (!Number.isFinite(parsed)) return '--';
    const abs = Math.abs(parsed);
    if (abs < 1000) return `${parsed.toFixed(abs < 10 ? 2 : 1)} bp`;
    if (abs >= 1e6) return `${(parsed / 1e6).toFixed(abs >= 1e7 ? 1 : 2)} Mb`;
    if (abs >= 1e3) return `${(parsed / 1e3).toFixed(abs >= 1e4 ? 1 : 2)} Kb`;
    return `${Math.round(parsed)} bp`;
}

function buildLaneLayout(items, gapPct = LANE_SAFE_GAP_PCT, maxLanes = Number.POSITIVE_INFINITY) {
    if (!items.length) return { items: [], laneCount: 1 };
    const sorted = [...items].sort((a, b) => a.leftPct - b.leftPct || b.widthPct - a.widthPct);
    const laneEnds = [];
    const assigned = sorted.map((item) => {
        let lane = laneEnds.findIndex((end) => item.leftPct >= end + gapPct);
        if (lane < 0) {
            if (laneEnds.length < maxLanes) {
                lane = laneEnds.length;
                laneEnds.push(item.endPct);
            } else {
                lane = laneEnds.reduce(
                    (bestIdx, end, idx, arr) => (end < arr[bestIdx] ? idx : bestIdx),
                    0,
                );
                laneEnds[lane] = Math.max(laneEnds[lane], item.endPct);
            }
        } else {
            laneEnds[lane] = item.endPct;
        }
        return { ...item, lane };
    });
    return {
        items: assigned,
        laneCount: Math.max(1, laneEnds.length),
    };
}

function toneByIndex(index = 0) {
    const length = GENE_COLOR_PALETTE.length;
    if (!length) {
        return {
            fill: '#3f6d8f',
            soft: '#e9f1f7',
            border: '#bfd2e2',
            text: '#2f4f68',
            glow: 'rgba(63, 109, 143, 0.2)',
            estDark: '#6887a1',
            estLight: '#ccd9e5',
        };
    }
    const safeIndex = Math.abs(Math.floor(Number(index) || 0)) % length;
    return GENE_COLOR_PALETTE[safeIndex];
}

function isFinalCoordinateMode(mode) {
    const normalized = String(mode || '').toLowerCase();
    return normalized === 'gene_coordinate';
}

const GeneZoom = ({
    band,
    genes = [],
    patientGenes = [],
    compareMode = false,
    patientLoading = false,
    selectedGene = null,
    importantGeneNames = [],
    chromosomeSpan: _chromosomeSpan = null,
    spanPrefetchState = null,
    onGeneSelect,
}) => {
    if (!band) return null;

    const selectedGeneKey = useMemo(
        () => (selectedGene ? String(selectedGene).toUpperCase() : ''),
        [selectedGene],
    );
    const trackRef = useRef(null);
    const trackWrapRef = useRef(null);
    const legendButtonRefs = useRef(new Map());
    const brushStateRef = useRef(null);
    const [viewWindow, setViewWindow] = useState(null);
    const [brushState, setBrushState] = useState(null);
    const [hoveredGeneKey, setHoveredGeneKey] = useState('');
    const [hoveredLegendMeta, setHoveredLegendMeta] = useState({ symbol: '', edge: 'center' });

    const normalizeGeneItems = useCallback(
        (sourceGenes) => {
            const safeGenes = Array.isArray(sourceGenes) ? sourceGenes : [];
            const bandRange = resolveBandRange(band);
            const bandStart = parseCoordinate(bandRange.start);
            const bandStop = parseCoordinate(bandRange.stop);
            const hasBandRange =
                Number.isFinite(bandStart) && Number.isFinite(bandStop) && bandStop > bandStart;
            const slotSpan =
                hasBandRange && safeGenes.length ? (bandStop - bandStart) / safeGenes.length : NaN;
            const estimatedMinSpan = Number.isFinite(slotSpan)
                ? Math.max(5000, Math.round(slotSpan * 0.25))
                : 5000;
            const normalizeRange = (rawStart, rawStop, minSpan = 1) => {
                if (!Number.isFinite(rawStart) || !Number.isFinite(rawStop)) return null;
                let nextStart = Math.min(rawStart, rawStop);
                let nextStop = Math.max(rawStart, rawStop);
                const bandSpan = hasBandRange
                    ? Math.max(1, bandStop - bandStart)
                    : Number.POSITIVE_INFINITY;
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
                        const anchor = clamp((nextStart + nextStop) / 2, bandStart, bandStop);
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
            return safeGenes
                .map((gene, index) => {
                    const symbol = (gene?.name || gene?.gene || `GENE_${index + 1}`)
                        .toString()
                        .toUpperCase();
                    const rawStart = parseCoordinate(gene?.start);
                    const rawStop = parseCoordinate(gene?.stop);
                    if (!Number.isFinite(rawStart) || !Number.isFinite(rawStop)) return null;
                    let start = Math.min(rawStart, rawStop);
                    let stop = Math.max(rawStart, rawStop, start + 1);
                    let coordinateMode = gene?.coordinateMode || 'unavailable';
                    const isFinalCoordinate = isFinalCoordinateMode(coordinateMode);
                    const minVisibleSpan = isFinalCoordinate ? 1 : estimatedMinSpan;
                    const normalizedRawRange = normalizeRange(start, stop, minVisibleSpan);
                    if (!normalizedRawRange) return null;
                    start = normalizedRawRange.start;
                    stop = normalizedRawRange.stop;

                    if (hasBandRange && (stop < bandStart || start > bandStop)) {
                        const estStartRaw = parseCoordinate(gene?.estimatedStart);
                        const estStopRaw = parseCoordinate(gene?.estimatedStop);
                        const estimatedRange = normalizeRange(
                            estStartRaw,
                            estStopRaw,
                            estimatedMinSpan,
                        );
                        if (estimatedRange) {
                            start = estimatedRange.start;
                            stop = estimatedRange.stop;
                            coordinateMode = 'adjusted_to_band';
                        } else {
                            const anchor = stop < bandStart ? bandStart : bandStop;
                            const anchoredRange = normalizeRange(
                                anchor,
                                anchor + estimatedMinSpan,
                                estimatedMinSpan,
                            );
                            if (!anchoredRange) return null;
                            start = anchoredRange.start;
                            stop = anchoredRange.stop;
                            coordinateMode = 'adjusted_to_band';
                        }
                    } else if (!isFinalCoordinate) {
                        const adjusted = normalizeRange(start, stop, estimatedMinSpan);
                        if (adjusted) {
                            start = adjusted.start;
                            stop = adjusted.stop;
                        }
                    }

                    return {
                        ...gene,
                        raw: gene,
                        symbol,
                        name: gene?.name || gene?.gene || symbol,
                        start,
                        stop,
                        spanBp: Math.max(1, stop - start),
                        coordinateMode,
                        hasFinalCoordinate: isFinalCoordinateMode(coordinateMode),
                        isSelected: selectedGeneKey === symbol,
                        importanceScore: scoreGeneImportance(gene),
                    };
                })
                .filter(Boolean)
                .sort((a, b) => a.start - b.start || b.importanceScore - a.importanceScore);
        },
        [band, selectedGeneKey],
    );

    const normalizedGenes = useMemo(() => normalizeGeneItems(genes), [genes, normalizeGeneItems]);
    const normalizedPatientGenes = useMemo(
        () => normalizeGeneItems(patientGenes),
        [normalizeGeneItems, patientGenes],
    );
    const normalizedGenesFinal = useMemo(
        () => normalizedGenes.filter((item) => item?.hasFinalCoordinate),
        [normalizedGenes],
    );

    const focusGenesByScore = useMemo(() => {
        const allGenes = normalizedGenes;
        const finalGenes = normalizedGenesFinal;
        if (!allGenes.length) return [];
        const finalGeneMap = new Map(finalGenes.map((item) => [item.symbol, item]));
        const provided = new Set(
            (Array.isArray(importantGeneNames) ? importantGeneNames : [])
                .map((name) =>
                    String(name || '')
                        .trim()
                        .toUpperCase(),
                )
                .filter(Boolean),
        );
        let picked = allGenes.filter((item) => provided.has(item.symbol));
        if (!picked.length || picked.length < Math.min(FOCUS_GENE_MIN, allGenes.length)) {
            const dynamicLimit = Math.max(
                FOCUS_GENE_MIN,
                Math.min(FOCUS_GENE_MAX, Math.round(allGenes.length * 0.35)),
            );
            const candidates = pickImportantGenes(allGenes, dynamicLimit);
            picked = candidates
                .map((item) => {
                    const key = (item?.name || item?.gene || '').toString().toUpperCase();
                    return allGenes.find((gene) => gene.symbol === key);
                })
                .filter(Boolean);
        }
        if (selectedGeneKey && !picked.find((item) => item.symbol === selectedGeneKey)) {
            const selected = allGenes.find((item) => item.symbol === selectedGeneKey);
            if (selected) picked = [...picked, selected];
        }
        picked = picked.map((item) => finalGeneMap.get(item.symbol) || item);
        return [...picked].sort((a, b) => {
            const delta = b.importanceScore - a.importanceScore;
            if (delta !== 0) return delta;
            return a.start - b.start;
        });
    }, [importantGeneNames, normalizedGenes, normalizedGenesFinal, selectedGeneKey]);

    const focusGenesForTrack = useMemo(
        () =>
            [...focusGenesByScore].sort(
                (a, b) => a.start - b.start || b.importanceScore - a.importanceScore,
            ),
        [focusGenesByScore],
    );

    const coordinateWindow = useMemo(() => {
        const bandRange = resolveBandRange(band);
        const bandStartRaw = parseCoordinate(bandRange.start);
        const bandStopRaw = parseCoordinate(bandRange.stop);
        const hasBand =
            Number.isFinite(bandStartRaw) &&
            Number.isFinite(bandStopRaw) &&
            bandStopRaw > bandStartRaw;
        const pool = focusGenesForTrack.length
            ? focusGenesForTrack
            : compareMode && normalizedPatientGenes.length
              ? normalizedPatientGenes
              : normalizedGenesFinal;
        const poolStart = pool.length ? Math.min(...pool.map((item) => item.start)) : 0;
        const poolStop = pool.length ? Math.max(...pool.map((item) => item.stop)) : 1;
        let start = hasBand ? bandStartRaw : poolStart;
        let stop = hasBand ? bandStopRaw : poolStop;
        if (hasBand) {
            const bandSpan = Math.max(1, bandStopRaw - bandStartRaw);
            const poolSpan = Math.max(1, poolStop - poolStart);
            const coverage = poolSpan / bandSpan;
            if (coverage > 0 && coverage < AUTO_ZOOM_COVERAGE_THRESHOLD) {
                const pad = Math.max(poolSpan * 0.35, bandSpan * 0.02, 200000);
                start = Math.max(bandStartRaw, poolStart - pad);
                stop = Math.min(bandStopRaw, poolStop + pad);
                if (stop <= start) {
                    start = bandStartRaw;
                    stop = bandStopRaw;
                }
                return {
                    start,
                    stop,
                    span: Math.max(1, stop - start),
                    source: 'band_adaptive',
                };
            }
            start = bandStartRaw;
            stop = bandStopRaw;
        } else {
            start = poolStart;
            stop = poolStop;
        }
        if (!Number.isFinite(start) || !Number.isFinite(stop) || stop <= start) {
            return { start: 0, stop: 1, span: 1, source: 'fallback' };
        }
        return {
            start,
            stop,
            span: Math.max(1, stop - start),
            source: hasBand ? 'band' : 'gene',
        };
    }, [band, compareMode, focusGenesForTrack, normalizedGenesFinal, normalizedPatientGenes]);

    const focusTrackLayout = useMemo(() => {
        const activeWindow = viewWindow || coordinateWindow;
        const baseItems = focusGenesForTrack
            .map((item) => {
                const rawLeft = ((item.start - activeWindow.start) / activeWindow.span) * 100;
                const rawRight = ((item.stop - activeWindow.start) / activeWindow.span) * 100;
                const leftPct = clamp(rawLeft, 0, 100);
                const rightPct = clamp(rawRight, 0, 100);
                const widthPct = Math.max(0, rightPct - leftPct);
                return {
                    ...item,
                    leftPct,
                    widthPct,
                    endPct: leftPct + widthPct,
                };
            })
            .filter((item) => item.endPct > 0 && item.leftPct < 100 && item.widthPct > 0);
        return buildLaneLayout(baseItems, LANE_SAFE_GAP_PCT, MAX_TRACK_LANES);
    }, [coordinateWindow, focusGenesForTrack, viewWindow]);
    const patientGenesForTrack = useMemo(() => {
        if (!compareMode) return [];
        const knowledgeBySymbol = new Map(normalizedGenes.map((item) => [item.symbol, item]));
        return normalizedPatientGenes
            .map((item) => {
                const peer = knowledgeBySymbol.get(item.symbol);
                if (!peer) return item;
                return {
                    ...item,
                    start: peer.start,
                    stop: peer.stop,
                    spanBp: peer.spanBp,
                    hasFinalCoordinate: peer.hasFinalCoordinate,
                    coordinateMode: peer.coordinateMode,
                };
            })
            .sort((a, b) => a.start - b.start || b.importanceScore - a.importanceScore);
    }, [compareMode, normalizedGenes, normalizedPatientGenes]);
    const hitGeneSymbolSet = useMemo(() => {
        if (!compareMode) return new Set();
        const next = new Set();
        patientGenesForTrack.forEach((item) => {
            const symbol = String(item?.symbol || item?.name || item?.gene || '')
                .trim()
                .toUpperCase();
            if (!symbol) return;
            const matchedVariantCount = Number(
                item?.matchedVariantCount ?? item?.knowledgeMatchedCount ?? 0,
            );
            if (!Number.isFinite(matchedVariantCount) || matchedVariantCount <= 0) return;
            next.add(symbol);
        });
        return next;
    }, [compareMode, patientGenesForTrack]);
    const patientTrackLayout = useMemo(() => {
        const activeWindow = viewWindow || coordinateWindow;
        const baseItems = patientGenesForTrack
            .map((item) => {
                const rawLeft = ((item.start - activeWindow.start) / activeWindow.span) * 100;
                const rawRight = ((item.stop - activeWindow.start) / activeWindow.span) * 100;
                const leftPct = clamp(rawLeft, 0, 100);
                const rightPct = clamp(rawRight, 0, 100);
                const widthPct = Math.max(0, rightPct - leftPct);
                return {
                    ...item,
                    leftPct,
                    widthPct,
                    endPct: leftPct + widthPct,
                };
            })
            .filter((item) => item.endPct > 0 && item.leftPct < 100 && item.widthPct > 0);
        return buildLaneLayout(baseItems, LANE_SAFE_GAP_PCT, MAX_TRACK_LANES);
    }, [coordinateWindow, patientGenesForTrack, viewWindow]);
    const knowledgeTrackRowHeight = compareMode ? COMPARE_TRACK_ROW_HEIGHT : TRACK_ROW_HEIGHT;
    const patientTrackRowHeight = COMPARE_TRACK_ROW_HEIGHT;
    const compareTrackGeometry = useMemo(() => {
        if (!compareMode) return null;
        const knowledgeLaneCount = Math.max(1, focusTrackLayout.laneCount);
        const patientLaneCount = Math.max(1, patientTrackLayout.laneCount || 1);
        const knowledgeStart = TRACK_TOP_PADDING + COMPARE_TRACK_TOP_OFFSET;
        const knowledgeHeight = knowledgeLaneCount * knowledgeTrackRowHeight;
        const axisY = knowledgeStart + knowledgeHeight + COMPARE_AXIS_GAP;
        const patientStart = axisY + COMPARE_AXIS_GAP;
        const patientHeight = patientLaneCount * patientTrackRowHeight;
        const naturalHeight = patientStart + patientHeight + 26;
        const minHeight = axisY + AXIS_ROW_HEIGHT + 72;
        return {
            knowledgeStart,
            axisY,
            patientStart,
            trackHeight: Math.max(380, naturalHeight, minHeight),
        };
    }, [
        compareMode,
        focusTrackLayout.laneCount,
        knowledgeTrackRowHeight,
        patientTrackLayout.laneCount,
        patientTrackRowHeight,
    ]);

    const singleTrackGeometry = useMemo(() => {
        const knowledgeStart = TRACK_TOP_PADDING;
        const trackHeight = SINGLE_TRACK_MIN_HEIGHT;
        const axisY = trackHeight - (AXIS_ROW_BOTTOM + AXIS_ROW_HEIGHT);
        return {
            knowledgeStart,
            axisY,
            patientStart: null,
            trackHeight,
        };
    }, []);

    const activeTrackGeometry =
        compareMode && compareTrackGeometry ? compareTrackGeometry : singleTrackGeometry;

    const trackHeightFinal = useMemo(() => {
        return activeTrackGeometry.trackHeight;
    }, [activeTrackGeometry]);

    const axisLineY = useMemo(() => {
        return activeTrackGeometry.axisY;
    }, [activeTrackGeometry]);

    const knowledgeTrackStart = activeTrackGeometry.knowledgeStart;
    const patientTrackStart =
        compareMode && compareTrackGeometry ? compareTrackGeometry.patientStart : null;

    const guideAnnotations = useMemo(() => {
        const anchors = [];
        focusTrackLayout.items.forEach((item) => {
            const segmentTop = knowledgeTrackStart + item.lane * knowledgeTrackRowHeight + 13;
            const segmentBottom = segmentTop + SEGMENT_HEIGHT;
            anchors.push({
                key: `k-${item.symbol}-start-${item.start}`,
                pct: item.leftPct,
                value: item.start,
                lineAnchorY: segmentBottom,
            });
            anchors.push({
                key: `k-${item.symbol}-end-${item.stop}`,
                pct: item.endPct,
                value: item.stop,
                lineAnchorY: segmentBottom,
            });
        });
        if (compareMode && patientTrackStart != null) {
            patientTrackLayout.items.forEach((item) => {
                const segmentTop = patientTrackStart + item.lane * patientTrackRowHeight + 13;
                anchors.push({
                    key: `p-${item.symbol}-start-${item.start}`,
                    pct: item.leftPct,
                    value: item.start,
                    lineAnchorY: segmentTop,
                });
                anchors.push({
                    key: `p-${item.symbol}-end-${item.stop}`,
                    pct: item.endPct,
                    value: item.stop,
                    lineAnchorY: segmentTop,
                });
            });
        }

        return [...anchors]
            .sort((a, b) => a.pct - b.pct || a.lineAnchorY - b.lineAnchorY)
            .map((anchor) => {
                const lineTop = Math.min(anchor.lineAnchorY, axisLineY);
                const lineBottom = Math.max(anchor.lineAnchorY, axisLineY);
                return {
                    ...anchor,
                    lineTop,
                    lineHeight: Math.max(1, lineBottom - lineTop),
                    dotTop: axisLineY,
                    label: formatBp(anchor.value),
                    isLeftEdge: anchor.pct <= 2.5,
                    isRightEdge: anchor.pct >= 97.5,
                };
            });
    }, [
        axisLineY,
        compareMode,
        focusTrackLayout.items,
        knowledgeTrackRowHeight,
        knowledgeTrackStart,
        patientTrackLayout.items,
        patientTrackRowHeight,
        patientTrackStart,
    ]);

    const rankMap = useMemo(() => {
        const map = new Map();
        focusGenesForTrack.forEach((item, index) => {
            map.set(item.symbol, index + 1);
        });
        return map;
    }, [focusGenesForTrack]);
    const geneToneMap = useMemo(() => {
        const map = new Map();
        focusGenesForTrack.forEach((item, index) => {
            map.set(item.symbol, toneByIndex(index));
        });
        return map;
    }, [focusGenesForTrack]);
    const patientGeneToneMap = useMemo(() => {
        const map = new Map();
        patientGenesForTrack.forEach((item, index) => {
            map.set(
                item.symbol,
                geneToneMap.get(item.symbol) || toneByIndex(index + focusGenesForTrack.length),
            );
        });
        return map;
    }, [focusGenesForTrack.length, geneToneMap, patientGenesForTrack]);
    const rankLegendItems = useMemo(
        () =>
            focusGenesForTrack.map((item) => ({
                ...item,
                rank: rankMap.get(item.symbol) || '--',
                tone: geneToneMap.get(item.symbol) || toneByIndex(0),
            })),
        [focusGenesForTrack, rankMap, geneToneMap],
    );

    const exactCount = useMemo(() => normalizedGenesFinal.length, [normalizedGenesFinal]);
    const patientExactCount = useMemo(
        () => normalizedPatientGenes.filter((item) => item?.hasFinalCoordinate).length,
        [normalizedPatientGenes],
    );
    const missingCoordinateGenes = useMemo(
        () =>
            focusGenesForTrack
                .filter((item) => !item?.hasFinalCoordinate)
                .map((item) => item.symbol),
        [focusGenesForTrack],
    );
    const missingCoordinateSummary = useMemo(() => {
        if (!missingCoordinateGenes.length) return '';
        const limit = 6;
        const head = missingCoordinateGenes.slice(0, limit);
        const more = missingCoordinateGenes.length - head.length;
        return more > 0 ? `${head.join(', ')} +${more} more` : head.join(', ');
    }, [missingCoordinateGenes]);
    const spanLoading = Boolean(spanPrefetchState?.loading);
    const spanTotal = Number(spanPrefetchState?.total) || 0;
    const spanFailed = Number(spanPrefetchState?.failed) || 0;

    const activeWindow = viewWindow || coordinateWindow;

    const axisTicks = useMemo(() => {
        const points = [0, 25, 50, 75, 100];
        return points.map((pct) => ({
            pct,
            value: activeWindow.start + (activeWindow.span * pct) / 100,
        }));
    }, [activeWindow]);

    const coordinateSourceMeta = useMemo(() => {
        if (coordinateWindow.source === 'band') {
            return {
                label: 'Source: band coordinates',
                severity: 'ok',
            };
        }
        if (coordinateWindow.source === 'band_adaptive') {
            return {
                label: 'Source: band coordinates (adaptive zoom)',
                severity: 'ok',
            };
        }
        if (coordinateWindow.source === 'gene') {
            return {
                label: 'Source: estimated from gene coordinates',
                severity: 'warn',
            };
        }
        return {
            label: 'Source: fallback scale',
            severity: 'warn',
        };
    }, [coordinateWindow.source]);

    useEffect(() => {
        setViewWindow({
            start: coordinateWindow.start,
            stop: coordinateWindow.stop,
            span: coordinateWindow.span,
        });
    }, [coordinateWindow]);

    useEffect(() => {
        brushStateRef.current = brushState;
    }, [brushState]);

    useEffect(() => {
        const resetScroll = (node) => {
            if (!node) return;
            if (typeof node.scrollTo === 'function') {
                node.scrollTo({ top: 0, left: 0, behavior: 'auto' });
            } else {
                node.scrollTop = 0;
                node.scrollLeft = 0;
            }
        };
        resetScroll(trackRef.current);
        resetScroll(trackWrapRef.current);
    }, [compareMode, trackHeightFinal]);

    const getTrackRatioByClientX = useCallback((clientX) => {
        const rect = trackRef.current?.getBoundingClientRect();
        if (!rect || !Number.isFinite(rect.width) || rect.width <= 0) return 0.5;
        return clamp((clientX - rect.left) / rect.width, 0, 1);
    }, []);

    const applyZoom = useCallback(
        (scale, anchorRatio = 0.5) => {
            const safeAnchor = clamp(anchorRatio, 0, 1);
            setViewWindow((prev) => {
                const current = prev || coordinateWindow;
                const minSpan = Math.max(1, coordinateWindow.span * MIN_VIEW_SPAN_FACTOR);
                const maxSpan = coordinateWindow.span;
                const nextSpan = clamp(current.span * scale, minSpan, maxSpan);
                const anchor = current.start + current.span * safeAnchor;
                let nextStart = anchor - nextSpan * safeAnchor;
                let nextStop = nextStart + nextSpan;

                if (nextStart < coordinateWindow.start) {
                    nextStart = coordinateWindow.start;
                    nextStop = nextStart + nextSpan;
                }
                if (nextStop > coordinateWindow.stop) {
                    nextStop = coordinateWindow.stop;
                    nextStart = nextStop - nextSpan;
                }

                nextStart = clamp(
                    nextStart,
                    coordinateWindow.start,
                    coordinateWindow.stop - nextSpan,
                );
                nextStop = nextStart + nextSpan;
                return {
                    start: nextStart,
                    stop: nextStop,
                    span: Math.max(1, nextStop - nextStart),
                };
            });
        },
        [coordinateWindow],
    );

    const handleTrackDoubleClick = useCallback(
        (event) => {
            if (spanLoading) return;
            const ratio = getTrackRatioByClientX(event.clientX);
            applyZoom(ZOOM_IN_FACTOR, ratio);
        },
        [applyZoom, getTrackRatioByClientX, spanLoading],
    );

    const isZoomed = useMemo(() => {
        if (!viewWindow) return false;
        const epsilon = Math.max(1, coordinateWindow.span * 1e-6);
        return (
            Math.abs(viewWindow.start - coordinateWindow.start) > epsilon ||
            Math.abs(viewWindow.stop - coordinateWindow.stop) > epsilon
        );
    }, [coordinateWindow.span, coordinateWindow.start, coordinateWindow.stop, viewWindow]);

    const handleResetZoom = useCallback(() => {
        setBrushState(null);
        setViewWindow({
            start: coordinateWindow.start,
            stop: coordinateWindow.stop,
            span: coordinateWindow.span,
        });
    }, [coordinateWindow.span, coordinateWindow.start, coordinateWindow.stop]);

    const handleTrackMouseDown = useCallback(
        (event) => {
            if (spanLoading || event.button !== 0) return;
            event.preventDefault();
            const ratio = getTrackRatioByClientX(event.clientX);
            setBrushState({ startRatio: ratio, currentRatio: ratio });
        },
        [getTrackRatioByClientX, spanLoading],
    );

    useEffect(() => {
        if (!brushState) return undefined;

        const handleWindowMouseMove = (event) => {
            const ratio = getTrackRatioByClientX(event.clientX);
            setBrushState((prev) => (prev ? { ...prev, currentRatio: ratio } : prev));
        };

        const handleWindowMouseUp = () => {
            const snapshot = brushStateRef.current;
            setBrushState(null);
            if (!snapshot) return;

            const left = Math.min(snapshot.startRatio, snapshot.currentRatio);
            const right = Math.max(snapshot.startRatio, snapshot.currentRatio);
            const ratioSpan = right - left;
            if (ratioSpan < BOX_ZOOM_MIN_RATIO) return;

            setViewWindow((prev) => {
                const current = prev || coordinateWindow;
                const minSpan = Math.max(1, coordinateWindow.span * MIN_VIEW_SPAN_FACTOR);
                const maxSpan = coordinateWindow.span;
                let nextStart = current.start + current.span * left;
                let nextStop = current.start + current.span * right;
                let nextSpan = Math.max(1, nextStop - nextStart);

                if (nextSpan < minSpan) {
                    const center = (nextStart + nextStop) / 2;
                    nextSpan = minSpan;
                    nextStart = center - nextSpan / 2;
                    nextStop = center + nextSpan / 2;
                }
                if (nextSpan > maxSpan) {
                    nextSpan = maxSpan;
                }

                if (nextStart < coordinateWindow.start) {
                    nextStart = coordinateWindow.start;
                    nextStop = nextStart + nextSpan;
                }
                if (nextStop > coordinateWindow.stop) {
                    nextStop = coordinateWindow.stop;
                    nextStart = nextStop - nextSpan;
                }

                nextStart = clamp(
                    nextStart,
                    coordinateWindow.start,
                    coordinateWindow.stop - nextSpan,
                );
                nextStop = nextStart + nextSpan;
                return {
                    start: nextStart,
                    stop: nextStop,
                    span: Math.max(1, nextStop - nextStart),
                };
            });
        };

        const handleWindowKeyDown = (event) => {
            if (event.key === 'Escape') {
                setBrushState(null);
            }
        };

        window.addEventListener('mousemove', handleWindowMouseMove);
        window.addEventListener('mouseup', handleWindowMouseUp);
        window.addEventListener('keydown', handleWindowKeyDown);
        return () => {
            window.removeEventListener('mousemove', handleWindowMouseMove);
            window.removeEventListener('mouseup', handleWindowMouseUp);
            window.removeEventListener('keydown', handleWindowKeyDown);
        };
    }, [brushState, coordinateWindow, getTrackRatioByClientX]);

    const brushVisual = useMemo(() => {
        if (!brushState) return null;
        const left = Math.min(brushState.startRatio, brushState.currentRatio) * 100;
        const right = Math.max(brushState.startRatio, brushState.currentRatio) * 100;
        return {
            left,
            width: Math.max(0, right - left),
        };
    }, [brushState]);

    const resolveLegendTooltipEdge = useCallback((symbol) => {
        if (!symbol) return 'center';
        const buttonNode = legendButtonRefs.current.get(symbol);
        const wrapNode = trackWrapRef.current;
        const buttonRect = buttonNode?.getBoundingClientRect?.();
        const wrapRect = wrapNode?.getBoundingClientRect?.();
        if (!buttonRect || !wrapRect) return 'center';

        const tooltipWidth = 560;
        const centerX = buttonRect.left + buttonRect.width / 2;
        const projectedLeft = centerX - tooltipWidth / 2;
        const projectedRight = centerX + tooltipWidth / 2;
        const safeInset = 14;

        if (projectedLeft < wrapRect.left + safeInset) return 'left';
        if (projectedRight > wrapRect.right - safeInset) return 'right';
        return 'center';
    }, []);

    useEffect(() => {
        if (!hoveredGeneKey) {
            setHoveredLegendMeta({ symbol: '', edge: 'center' });
            return;
        }
        setHoveredLegendMeta({
            symbol: hoveredGeneKey,
            edge: resolveLegendTooltipEdge(hoveredGeneKey),
        });
    }, [hoveredGeneKey, resolveLegendTooltipEdge]);

    const handleLegendMouseLeave = useCallback((symbol) => {
        setHoveredGeneKey((prev) => (prev === symbol ? '' : prev));
    }, []);

    return (
        <Box
            className={`cv-genezoom cv-genezoom-enter${compareMode ? ' is-compare' : ' is-single'}`}
        >
            <Box className="cv-gene-focus-metrics">
                <div className="metric">
                    <strong>{focusGenesForTrack.length}</strong>
                    <span>Key genes</span>
                </div>
                <div className="metric">
                    <strong>{exactCount}</strong>
                    <span>With final coordinates</span>
                </div>
                <div className="metric">
                    <strong>{normalizedGenes.length}</strong>
                    <span>Total genes in this band</span>
                </div>
                {compareMode ? (
                    <div className="metric is-patient">
                        <strong>{patientGenesForTrack.length}</strong>
                        <span>
                            {patientLoading
                                ? 'Patient hits loading...'
                                : `Patient-hit genes (${patientExactCount} exact coord)`}
                        </span>
                    </div>
                ) : null}
            </Box>

            <Box className="cv-gene-focus-layout">
                <Box className="cv-gene-focus-track-wrap" ref={trackWrapRef}>
                    <div className="cv-gene-focus-track-head">
                        <strong>Coordinate segment track</strong>
                        <span>
                            {formatBp(activeWindow.start)} - {formatBp(activeWindow.stop)}
                        </span>
                    </div>
                    <div className="cv-gene-track-meta-row">
                        <div
                            className={`cv-gene-track-source ${coordinateSourceMeta.severity === 'warn' ? 'is-warn' : 'is-ok'}`}
                        >
                            {coordinateSourceMeta.label}
                        </div>
                        {!spanLoading && spanTotal > 0 && spanFailed > 0 && (
                            <div className="cv-gene-span-loading is-warning">
                                <span>
                                    Coordinate spans unavailable for {spanFailed}/{spanTotal} genes.
                                    {missingCoordinateSummary
                                        ? ` Missing: ${missingCoordinateSummary}.`
                                        : ''}{' '}
                                    Track keeps estimated placeholders.
                                </span>
                            </div>
                        )}
                    </div>
                    <div className="cv-gene-track-toolbar">
                        <Box className="cv-gene-track-tip-box">
                            <Typography
                                variant="caption"
                                color="text.secondary"
                                className="cv-gene-track-tip"
                            >
                                Drag to select a range or double-click to zoom in.
                            </Typography>
                        </Box>
                        <Button
                            variant="outlined"
                            className="cv-gene-reset-btn"
                            onClick={handleResetZoom}
                            disabled={!isZoomed || spanLoading}
                        >
                            RESET
                        </Button>
                    </div>
                    <div className="cv-gene-rank-legend">
                        {rankLegendItems.map((item) => {
                            const legendHoverText = `${item.name} | ${formatBp(item.start)} - ${formatBp(item.stop)}${!item?.hasFinalCoordinate ? ' | estimated placeholder' : ''}`;
                            const edgeClass =
                                hoveredLegendMeta.symbol === item.symbol
                                    ? hoveredLegendMeta.edge === 'left'
                                        ? ' is-left-edge'
                                        : hoveredLegendMeta.edge === 'right'
                                          ? ' is-right-edge'
                                          : ''
                                    : '';
                            return (
                                <button
                                    key={`legend-${item.symbol}-${item.start}-${item.stop}`}
                                    type="button"
                                    className={`cv-gene-rank-legend-item${item.isSelected ? ' is-selected' : ''}${hoveredGeneKey === item.symbol ? ' is-hovered' : ''}${!item?.hasFinalCoordinate ? ' is-estimated' : ''}${edgeClass}`}
                                    ref={(node) => {
                                        if (node) {
                                            legendButtonRefs.current.set(item.symbol, node);
                                        } else {
                                            legendButtonRefs.current.delete(item.symbol);
                                        }
                                    }}
                                    onClick={() => onGeneSelect?.(item.raw || item)}
                                    onMouseEnter={() => setHoveredGeneKey(item.symbol)}
                                    onMouseLeave={() => handleLegendMouseLeave(item.symbol)}
                                    data-hover-text={legendHoverText}
                                    aria-label={legendHoverText}
                                    style={{
                                        '--cv-gene-accent': item.tone.fill,
                                        '--cv-gene-accent-soft': item.tone.soft,
                                        '--cv-gene-accent-border': item.tone.border,
                                        '--cv-gene-accent-text': item.tone.text,
                                        '--cv-gene-accent-glow': item.tone.glow,
                                        '--cv-gene-accent-est-dark': item.tone.estDark,
                                        '--cv-gene-accent-est-light': item.tone.estLight,
                                    }}
                                >
                                    <span className="rank">{item.rank}</span>
                                    <span className="name">{item.name}</span>
                                </button>
                            );
                        })}
                    </div>
                    <Box
                        ref={trackRef}
                        className={`cv-gene-focus-track${spanLoading ? ' is-loading' : ''}${brushState ? ' is-brushing' : ''}${compareMode ? ' is-compare' : ''}`}
                        style={{ height: `${trackHeightFinal}px` }}
                        onMouseDown={handleTrackMouseDown}
                        onDoubleClick={handleTrackDoubleClick}
                    >
                        {brushVisual && (
                            <div
                                className="cv-gene-track-brush"
                                style={{
                                    left: `${brushVisual.left}%`,
                                    width: `${brushVisual.width}%`,
                                }}
                            />
                        )}
                        {compareMode ? (
                            <>
                                <span className="cv-gene-track-side-label is-knowledge">
                                    Knowledge
                                </span>
                                <span className="cv-gene-track-side-label is-patient">
                                    {patientLoading ? 'Patient (loading...)' : 'Patient'}
                                </span>
                            </>
                        ) : null}
                        {Array.from({ length: focusTrackLayout.laneCount }).map((_, lane) => (
                            <span
                                key={`rail-${lane}`}
                                className="cv-gene-focus-rail"
                                style={{
                                    top: `${knowledgeTrackStart + lane * knowledgeTrackRowHeight + 20}px`,
                                }}
                            />
                        ))}
                        {compareMode &&
                            patientTrackStart != null &&
                            Array.from({ length: patientTrackLayout.laneCount }).map((_, lane) => (
                                <span
                                    key={`patient-rail-${lane}`}
                                    className="cv-gene-focus-rail is-patient"
                                    style={{
                                        top: `${patientTrackStart + lane * patientTrackRowHeight + 24}px`,
                                    }}
                                />
                            ))}
                        {focusTrackLayout.items.map((item) => {
                            const tone = geneToneMap.get(item.symbol) || toneByIndex(0);
                            const segmentTop =
                                knowledgeTrackStart + item.lane * knowledgeTrackRowHeight + 13;
                            const centerPct = item.leftPct + item.widthPct / 2;
                            const isEstimated = !item?.hasFinalCoordinate;
                            const isHovered = hoveredGeneKey === item.symbol;
                            const isHit =
                                compareMode &&
                                hitGeneSymbolSet.has(String(item.symbol || '').toUpperCase());
                            const nameTop = compareMode
                                ? Math.max(4, segmentTop - 24)
                                : segmentTop + SEGMENT_HEIGHT + 3;
                            return (
                                <React.Fragment
                                    key={`seg-${item.symbol}-${item.start}-${item.stop}`}
                                >
                                    {item.isSelected && (
                                        <span
                                            className={`cv-gene-segment-name${isEstimated ? ' is-estimated' : ''}${centerPct <= 2 ? ' is-left-edge' : ''}${centerPct >= 98 ? ' is-right-edge' : ''}`}
                                            style={{
                                                left: `${item.leftPct}%`,
                                                width: `${Math.max(item.widthPct, 8)}%`,
                                                top: `${nameTop}px`,
                                                color: tone.fill,
                                                '--cv-gene-accent-est-dark': tone.estDark,
                                            }}
                                        >
                                            {item.name}
                                            {isEstimated ? ' (EST)' : ''}
                                        </span>
                                    )}
                                    <button
                                        type="button"
                                        className={`cv-gene-focus-segment${item.isSelected ? ' is-selected' : ''}${isHovered ? ' is-hovered' : ''}${isEstimated ? ' is-estimated' : ''}${isHit ? ' is-hit' : ''}`}
                                        style={{
                                            left: `${item.leftPct}%`,
                                            width: `${item.widthPct}%`,
                                            top: `${segmentTop}px`,
                                            background: tone.fill,
                                            boxShadow: `0 0 0 1px rgba(15, 23, 42, 0.75), 0 8px 16px ${tone.glow}`,
                                            '--cv-gene-accent': tone.fill,
                                            '--cv-gene-accent-est-dark': tone.estDark,
                                            '--cv-gene-accent-est-light': tone.estLight,
                                        }}
                                        onClick={() => onGeneSelect?.(item.raw || item)}
                                        onMouseEnter={() => setHoveredGeneKey(item.symbol)}
                                        onMouseLeave={() =>
                                            setHoveredGeneKey((prev) =>
                                                prev === item.symbol ? '' : prev,
                                            )
                                        }
                                    />
                                </React.Fragment>
                            );
                        })}
                        {compareMode &&
                            patientTrackStart != null &&
                            patientTrackLayout.items.map((item) => {
                                const tone = patientGeneToneMap.get(item.symbol) || toneByIndex(0);
                                const segmentTop =
                                    patientTrackStart + item.lane * patientTrackRowHeight + 13;
                                const centerPct = item.leftPct + item.widthPct / 2;
                                const isEstimated = !item?.hasFinalCoordinate;
                                const isHovered = hoveredGeneKey === item.symbol;
                                const isHit = hitGeneSymbolSet.has(
                                    String(item.symbol || '').toUpperCase(),
                                );
                                const nameTop = segmentTop + SEGMENT_HEIGHT + 4;
                                return (
                                    <React.Fragment
                                        key={`patient-seg-${item.symbol}-${item.start}-${item.stop}`}
                                    >
                                        {item.isSelected && (
                                            <span
                                                className={`cv-gene-segment-name is-patient${isEstimated ? ' is-estimated' : ''}${centerPct <= 2 ? ' is-left-edge' : ''}${centerPct >= 98 ? ' is-right-edge' : ''}`}
                                                style={{
                                                    left: `${item.leftPct}%`,
                                                    width: `${Math.max(item.widthPct, 8)}%`,
                                                    top: `${nameTop}px`,
                                                    color: tone.fill,
                                                    '--cv-gene-accent-est-dark': tone.estDark,
                                                }}
                                            >
                                                {item.name}
                                                {isEstimated ? ' (EST)' : ''}
                                            </span>
                                        )}
                                        <button
                                            type="button"
                                            className={`cv-gene-focus-segment is-patient${item.isSelected ? ' is-selected' : ''}${isHovered ? ' is-hovered' : ''}${isEstimated ? ' is-estimated' : ''}${isHit ? ' is-hit' : ''}`}
                                            style={{
                                                left: `${item.leftPct}%`,
                                                width: `${item.widthPct}%`,
                                                top: `${segmentTop}px`,
                                                background: tone.fill,
                                                boxShadow: `0 0 0 1px rgba(15, 23, 42, 0.75), 0 8px 16px ${tone.glow}`,
                                                '--cv-gene-accent': tone.fill,
                                                '--cv-gene-accent-est-dark': tone.estDark,
                                                '--cv-gene-accent-est-light': tone.estLight,
                                            }}
                                            onClick={() => onGeneSelect?.(item.raw || item)}
                                            onMouseEnter={() => setHoveredGeneKey(item.symbol)}
                                            onMouseLeave={() =>
                                                setHoveredGeneKey((prev) =>
                                                    prev === item.symbol ? '' : prev,
                                                )
                                            }
                                        />
                                    </React.Fragment>
                                );
                            })}
                        {guideAnnotations.map((annotation) => (
                            <React.Fragment key={annotation.key}>
                                <span
                                    className="cv-gene-guide-line"
                                    style={{
                                        left: `${annotation.pct}%`,
                                        top: `${annotation.lineTop}px`,
                                        height: `${annotation.lineHeight}px`,
                                    }}
                                />
                                <span
                                    className={`cv-gene-guide-dot${annotation.isLeftEdge ? ' is-left-edge' : ''}${annotation.isRightEdge ? ' is-right-edge' : ''}`}
                                    style={{
                                        left: `${annotation.pct}%`,
                                        top: `${annotation.dotTop}px`,
                                    }}
                                    data-label={annotation.label}
                                />
                            </React.Fragment>
                        ))}
                        <div
                            className={`cv-gene-focus-axis-row${compareMode ? ' is-compare' : ''}`}
                            style={{ top: `${axisLineY}px`, bottom: 'auto' }}
                        >
                            {axisTicks.map((tick, index) => (
                                <div
                                    key={`axis-${tick.pct}`}
                                    className={`cv-gene-focus-axis-tick${index === 0 ? ' is-start' : ''}${index === axisTicks.length - 1 ? ' is-end' : ''}`}
                                    style={{ left: `${tick.pct}%` }}
                                >
                                    <i />
                                    <span>{formatCompact(tick.value)}</span>
                                </div>
                            ))}
                        </div>
                    </Box>
                </Box>
            </Box>
        </Box>
    );
};

export default GeneZoom;
