import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Box, Button, Stack, Typography, Tooltip } from '@mui/material';

const COMPARE_TRACK_VERTICAL_OFFSET_PX = 12;

function formatBp(value) {
    if (!Number.isFinite(value)) return 'N/A';
    return `${Math.round(value).toLocaleString()} bp`;
}

function asText(value, fallback = 'N/A') {
    if (value == null || value === '') return fallback;
    const text = String(value).trim();
    return text || fallback;
}

function normalizeClinicalText(value, fallback = 'Not classified') {
    if (value == null) return fallback;
    const text = String(value).trim();
    if (!text || text === '-' || text === '--') return fallback;
    return text;
}

function resolveClinicalBucket(value) {
    const text = normalizeClinicalText(value, '').toLowerCase();
    if (!text) return 'unknown';
    if (text === 'not classified') return 'unknown';
    if (text.includes('pathogenic')) return 'pathogenic';
    if (text.includes('uncertain') || text.includes('conflict')) return 'uncertain';
    if (text.includes('benign')) return 'benign';
    return 'other';
}

function formatMb(value, fractionDigits = 2) {
    if (!Number.isFinite(value)) return 'N/A';
    return `${(value / 1e6).toFixed(fractionDigits)} Mb`;
}

function formatCoordinateRange(chromosome, start, end) {
    const chrText = chromosome ? `Chr ${chromosome}` : 'Chr ?';
    if (Number.isFinite(start) && Number.isFinite(end) && end > start) {
        return `${chrText}:${Math.round(start).toLocaleString()} - ${Math.round(end).toLocaleString()}`;
    }
    if (Number.isFinite(start)) {
        return `${chrText}:${Math.round(start).toLocaleString()}`;
    }
    return chrText;
}

function buildMarkerHoverFields(item) {
    const coord = asText(item?.coordinateLabel, 'Chr ?');
    const refAlt = item?.ref && item?.alt ? `${item.ref}>${item.alt}` : 'N/A';
    const variantType = asText(item?.variantType, 'Unknown');
    const clinicalText = normalizeClinicalText(item?.clinicalText, 'Not classified');
    return [
        { label: 'Coordinate', value: coord },
        { label: 'REF/ALT', value: refAlt },
        { label: 'Type', value: variantType },
        { label: 'ClinSig', value: clinicalText },
    ];
}

function buildVariantHeadline(variant, geneFallback = 'N/A') {
    const clinicalText = normalizeClinicalText(variant?.clinicalText, 'Not classified');
    const geneSymbol = asText(variant?.geneSymbol, geneFallback || 'Unknown gene');
    const variantType = asText(
        variant?.variantType,
        variant?.hasRange ? 'Structural variant' : 'Single-point variant',
    );
    return `${clinicalText} · ${geneSymbol} · ${variantType}`;
}

function buildVariantSubline(variant) {
    const coordinateText = asText(variant?.coordinateLabel, 'Chr ?');
    if (variant?.ref && variant?.alt) {
        return `${coordinateText} · ${variant.ref} > ${variant.alt}`;
    }
    if (variant?.hasRange && Number.isFinite(variant?.spanBp)) {
        return `${coordinateText} · span ${formatBp(variant.spanBp)}`;
    }
    return coordinateText;
}

function normalizeChromosomeKey(value) {
    return String(value || '')
        .trim()
        .toUpperCase()
        .replace(/^CHR/, '');
}

function normalizeAlleleKey(value) {
    return String(value || '')
        .trim()
        .toUpperCase();
}

function normalizeSvTypeKey(value) {
    return String(value || '')
        .trim()
        .toUpperCase()
        .replace(/\s+/g, '_');
}

function buildSmallVariantExactKey(item) {
    const chr = normalizeChromosomeKey(item?.chromosome);
    const pos = Number(item?.position);
    const ref = normalizeAlleleKey(item?.ref);
    const alt = normalizeAlleleKey(item?.alt);
    if (!chr || !Number.isFinite(pos) || !ref || !alt) return '';
    return `${chr}:${Math.round(pos)}:${ref}:${alt}`;
}

function buildStructuralVariantExactKey(item) {
    const chr = normalizeChromosomeKey(item?.chromosome);
    const start = Number(item?.start);
    const end = Number(item?.end);
    const svType = normalizeSvTypeKey(
        item?.variantType || item?.type || item?.svType || item?.sv_type,
    );
    if (!chr || !Number.isFinite(start) || !Number.isFinite(end)) return '';
    return `${chr}:${Math.round(Math.min(start, end))}:${Math.round(Math.max(start, end))}:${svType}`;
}

function buildVariantExactKey(item) {
    const smallKey = buildSmallVariantExactKey(item);
    if (smallKey) return smallKey;
    return buildStructuralVariantExactKey(item);
}

function normalizeVariantRows(rows = [], { geneFallback = 'N/A', sourceTag = 'knowledge' } = {}) {
    return (Array.isArray(rows) ? rows : []).map((item, index) => {
        const clinicalText = normalizeClinicalText(
            item?.clinicalSignificance ?? item?.clinSigSimple,
            'Not classified',
        );
        const clinicalBucket = resolveClinicalBucket(clinicalText);
        const ref = item?.ref || null;
        const alt = item?.alt || null;
        const start = Number(
            item?.start ??
                item?.start_bp ??
                item?.startBp ??
                item?.raw?.start ??
                item?.raw?.start_bp ??
                item?.raw?.startBp ??
                item?.position,
        );
        const end = Number(
            item?.end ??
                item?.end_bp ??
                item?.endBp ??
                item?.raw?.end ??
                item?.raw?.end_bp ??
                item?.raw?.endBp ??
                item?.position,
        );
        const hasRange = Number.isFinite(start) && Number.isFinite(end) && end > start;
        const variantClass = hasRange ? 'Structural variant' : 'Small variant';
        const variantType = asText(
            item?.type ||
                item?.variantType ||
                item?.variant_type ||
                item?.svType ||
                item?.sv_type ||
                item?.raw?.variant_type ||
                item?.raw?.variantType ||
                item?.raw?.sv_type ||
                item?.raw?.svType,
            'Unknown',
        );
        const variantId = asText(
            item?.variantId || item?.variant_id || item?.raw?.variantId || item?.raw?.variant_id,
            null,
        );
        const geneSymbol = asText(
            item?.geneSymbol ||
                item?.gene_symbol ||
                item?.gene ||
                item?.raw?.geneSymbol ||
                item?.raw?.gene_symbol,
            geneFallback || 'N/A',
        );
        const spanBp = hasRange ? Math.max(1, Math.round(end - start + 1)) : null;
        const coordinateLabel = formatCoordinateRange(item?.chromosome, start, end);
        const rowKey = `${sourceTag}-${item?.id || 'variant'}-${item?.position ?? 'na'}-${index}`;
        const headline = buildVariantHeadline(
            {
                clinicalText,
                geneSymbol,
                variantType,
                hasRange,
            },
            geneFallback || 'N/A',
        );
        const subline = buildVariantSubline({
            coordinateLabel,
            ref,
            alt,
            hasRange,
            spanBp,
        });
        return {
            ...item,
            rowKey,
            sourceTag,
            start: Number.isFinite(start) ? Math.round(start) : null,
            end: Number.isFinite(end) ? Math.round(end) : null,
            hasRange,
            spanBp,
            variantClass,
            variantType,
            variantId,
            geneSymbol,
            ref,
            alt,
            clinicalText,
            clinicalBucket,
            displayId: variantId || item?.rsId || item?.id || `variant-${index + 1}`,
            coordinateLabel,
            headline,
            subline,
            transcriptLabel: item?.transcript || item?.gene || geneFallback || 'N/A',
            functionalRegionLabel: item?.functionalRegion || item?.type || 'N/A',
            consequenceLabel:
                item?.consequence || item?.proteinImpact || item?.spliceImpact || 'N/A',
            isPatientOnly: Boolean(item?.patientOnly || item?.isPatientOnly),
            exactKey: buildVariantExactKey({
                ...item,
                start,
                end,
                variantType,
            }),
        };
    });
}

const LocusZoom = ({
    band,
    gene,
    variants = [],
    knowledgeVariants = [],
    patientVariants = [],
    loading = false,
    error = '',
    compareMode = false,
    preferredVariantExactKey = '',
    onWindowChange,
    onVariantSelect,
}) => {
    const trackRef = useRef(null);
    const brushStateRef = useRef(null);
    const variantListRef = useRef(null);
    const variantRowRefs = useRef(new Map());
    const [selectedVariantKey, setSelectedVariantKey] = useState('');
    const [brushState, setBrushState] = useState(null);

    const bindVariantRowRef = useCallback((rowKey, node) => {
        if (!rowKey) return;
        if (node) {
            variantRowRefs.current.set(rowKey, node);
            return;
        }
        variantRowRefs.current.delete(rowKey);
    }, []);

    const knowledgeSourceRows = useMemo(() => {
        if (Array.isArray(knowledgeVariants) && knowledgeVariants.length) return knowledgeVariants;
        return (Array.isArray(variants) ? variants : []).filter(
            (item) => String(item?.dataSource || '').toLowerCase() !== 'case',
        );
    }, [knowledgeVariants, variants]);

    const patientSourceRows = useMemo(() => {
        if (!compareMode) return [];
        if (Array.isArray(patientVariants) && patientVariants.length) return patientVariants;
        return (Array.isArray(variants) ? variants : []).filter(
            (item) => String(item?.dataSource || '').toLowerCase() === 'case',
        );
    }, [compareMode, patientVariants, variants]);

    const normalizedKnowledgeVariants = useMemo(
        () =>
            normalizeVariantRows(knowledgeSourceRows, {
                geneFallback: gene?.name || 'N/A',
                sourceTag: 'knowledge',
            }),
        [gene?.name, knowledgeSourceRows],
    );

    const knowledgeExactMap = useMemo(() => {
        const map = new Map();
        normalizedKnowledgeVariants.forEach((item) => {
            const key = item?.exactKey || buildVariantExactKey(item);
            if (!key || map.has(key)) return;
            map.set(key, item);
        });
        return map;
    }, [normalizedKnowledgeVariants]);

    const normalizedPatientVariants = useMemo(
        () =>
            normalizeVariantRows(patientSourceRows, {
                geneFallback: gene?.name || 'N/A',
                sourceTag: 'patient',
            }).map((item) => {
                const exactKey = item?.exactKey || buildVariantExactKey(item);
                const matchedKnowledge = exactKey ? knowledgeExactMap.get(exactKey) : null;
                const matchedByExact = Boolean(matchedKnowledge);
                const upstreamMatchedRaw = item?.knowledgeMatched;
                const upstreamPatientOnlyRaw = item?.patientOnly;
                const upstreamMatched =
                    upstreamMatchedRaw == null ? null : Boolean(upstreamMatchedRaw);
                const upstreamPatientOnly =
                    upstreamPatientOnlyRaw == null ? null : Boolean(upstreamPatientOnlyRaw);

                // Prefer upstream reconciliation signals (from ChromosomeDetail), and
                // treat local exact-key hit as an additional positive signal.
                const matched =
                    upstreamMatched == null
                        ? upstreamPatientOnly == null
                            ? matchedByExact
                            : !upstreamPatientOnly || matchedByExact
                        : upstreamMatched || matchedByExact;
                const isPatientOnly =
                    upstreamPatientOnly == null ? !matched : upstreamPatientOnly && !matched;

                const matchedKnowledgeClinicalText =
                    matchedKnowledge?.clinicalText ||
                    normalizeClinicalText(
                        item?.knowledgeClinicalSignificance || item?.matchedKnowledgeClinicalText,
                        '',
                    );
                const markerBucket = isPatientOnly
                    ? 'patient-only'
                    : matchedKnowledge?.clinicalBucket ||
                      (matchedKnowledgeClinicalText
                          ? resolveClinicalBucket(matchedKnowledgeClinicalText)
                          : item.clinicalBucket);

                return {
                    ...item,
                    exactKey,
                    knowledgeMatched: matched,
                    patientOnly: isPatientOnly,
                    isPatientOnly,
                    markerBucket,
                    matchedKnowledgeClinicalText: matchedKnowledgeClinicalText || null,
                };
            }),
        [gene?.name, knowledgeExactMap, patientSourceRows],
    );

    const normalizedVariants = useMemo(
        () =>
            compareMode
                ? [...normalizedKnowledgeVariants, ...normalizedPatientVariants]
                : normalizedKnowledgeVariants,
        [compareMode, normalizedKnowledgeVariants, normalizedPatientVariants],
    );
    const matchedPatientExactKeySet = useMemo(() => {
        const next = new Set();
        if (!compareMode) return next;
        normalizedPatientVariants.forEach((item) => {
            if (!item?.knowledgeMatched) return;
            const key = item?.exactKey || buildVariantExactKey(item);
            if (!key) return;
            next.add(key);
        });
        return next;
    }, [compareMode, normalizedPatientVariants]);

    const variantPositions = useMemo(
        () =>
            normalizedVariants
                .map((item) => Number(item?.position))
                .filter((value) => Number.isFinite(value))
                .sort((a, b) => a - b),
        [normalizedVariants],
    );

    const fullRange = useMemo(() => {
        const locusStart = Number(gene?.locusStart);
        const locusStop = Number(gene?.locusStop);
        if (Number.isFinite(locusStart) && Number.isFinite(locusStop) && locusStop > locusStart) {
            return { start: locusStart, stop: locusStop };
        }
        const geneStart = Number(gene?.start);
        const geneStop = Number(gene?.stop);
        if (Number.isFinite(geneStart) && Number.isFinite(geneStop) && geneStop > geneStart) {
            return { start: geneStart, stop: geneStop };
        }
        if (variantPositions.length > 1) {
            const span = variantPositions[variantPositions.length - 1] - variantPositions[0];
            const padding = Math.max(Math.round(span * 0.08), 10);
            return {
                start: Math.max(0, variantPositions[0] - padding),
                stop: variantPositions[variantPositions.length - 1] + padding,
            };
        }
        if (variantPositions.length === 1) {
            return {
                start: Math.max(0, variantPositions[0] - 120),
                stop: variantPositions[0] + 120,
            };
        }
        const bandStart = Number(band?.start);
        const bandStop = Number(band?.stop);
        if (Number.isFinite(bandStart) && Number.isFinite(bandStop) && bandStop > bandStart) {
            return { start: bandStart, stop: bandStop };
        }
        return { start: 0, stop: 1 };
    }, [
        band?.start,
        band?.stop,
        gene?.start,
        gene?.stop,
        gene?.locusStart,
        gene?.locusStop,
        variantPositions,
    ]);

    const [windowRange, setWindowRange] = useState(fullRange);

    useEffect(() => {
        setWindowRange(fullRange);
        setSelectedVariantKey('');
    }, [fullRange, gene?.name]);

    const windowSpan = Math.max(windowRange.stop - windowRange.start, 1);
    const fullSpan = Math.max(fullRange.stop - fullRange.start, 1);
    const minWindowSpan = Math.max(Math.round(fullSpan / 1000), 1);

    useEffect(() => {
        brushStateRef.current = brushState;
    }, [brushState]);

    const visibleKnowledgeVariants = useMemo(
        () =>
            normalizedKnowledgeVariants.filter((item) => {
                if (!Number.isFinite(item?.position)) return true;
                return item.position >= windowRange.start && item.position <= windowRange.stop;
            }),
        [normalizedKnowledgeVariants, windowRange.start, windowRange.stop],
    );

    const visiblePatientVariants = useMemo(
        () =>
            normalizedPatientVariants.filter((item) => {
                if (!Number.isFinite(item?.position)) return true;
                return item.position >= windowRange.start && item.position <= windowRange.stop;
            }),
        [normalizedPatientVariants, windowRange.start, windowRange.stop],
    );

    const visibleVariants = useMemo(() => {
        if (!compareMode) return visibleKnowledgeVariants;
        return [...visibleKnowledgeVariants, ...visiblePatientVariants].sort((a, b) => {
            const posA = Number(a?.position);
            const posB = Number(b?.position);
            if (Number.isFinite(posA) && Number.isFinite(posB) && posA !== posB) return posA - posB;
            if (a?.sourceTag !== b?.sourceTag) {
                return a?.sourceTag === 'knowledge' ? -1 : 1;
            }
            return String(a?.rowKey || '').localeCompare(String(b?.rowKey || ''));
        });
    }, [compareMode, visibleKnowledgeVariants, visiblePatientVariants]);

    useEffect(() => {
        if (!visibleVariants.length) {
            if (selectedVariantKey) setSelectedVariantKey('');
            return;
        }
        const preferredExactKey = String(preferredVariantExactKey || '').trim();
        if (preferredExactKey) {
            const preferred = visibleVariants.find(
                (item) => String(item?.exactKey || '').trim() === preferredExactKey,
            );
            if (preferred?.rowKey && preferred.rowKey !== selectedVariantKey) {
                setSelectedVariantKey(preferred.rowKey);
                return;
            }
        }
        const selectedVisible = visibleVariants.some((item) => item.rowKey === selectedVariantKey);
        if (!selectedVisible) {
            setSelectedVariantKey(visibleVariants[0].rowKey);
        }
    }, [preferredVariantExactKey, selectedVariantKey, visibleVariants]);

    useEffect(() => {
        if (!selectedVariantKey) return;
        const listNode = variantListRef.current;
        const rowNode = variantRowRefs.current.get(selectedVariantKey);
        if (!listNode || !rowNode) return;

        const listRect = listNode.getBoundingClientRect();
        const rowRect = rowNode.getBoundingClientRect();
        const outsideTop = rowRect.top < listRect.top + 8;
        const outsideBottom = rowRect.bottom > listRect.bottom - 8;
        if (outsideTop || outsideBottom) {
            rowNode.scrollIntoView({
                behavior: 'smooth',
                block: 'center',
                inline: 'nearest',
            });
        }
    }, [selectedVariantKey, visibleVariants]);

    const selectedVariant = useMemo(
        () =>
            visibleVariants.find((item) => item.rowKey === selectedVariantKey) ||
            visibleVariants[0] ||
            null,
        [selectedVariantKey, visibleVariants],
    );
    const handleSelectVariantKey = useCallback(
        (rowKey) => {
            setSelectedVariantKey(rowKey);
            const item = visibleVariants.find((variant) => variant?.rowKey === rowKey);
            if (!item || typeof onVariantSelect !== 'function') return;
            onVariantSelect({
                exactKey: item?.exactKey || buildVariantExactKey(item),
                variantId: item?.variantId || null,
                displayId: item?.displayId || null,
                chromosome: item?.chromosome || band?.chr || band?.chromosome || null,
                position: item?.position || item?.start || null,
                ref: item?.ref || null,
                alt: item?.alt || null,
                geneSymbol: item?.geneSymbol || gene?.name || null,
                sourceTag: item?.sourceTag || null,
            });
        },
        [band?.chr, band?.chromosome, gene?.name, onVariantSelect, visibleVariants],
    );
    const buildMarkerLayout = useCallback(
        (rows) => {
            const laneCap = 6;
            const minGapPercent = 3.2;
            const laneLast = [];
            return rows
                .filter((item) => Number.isFinite(item?.position))
                .map((item) => ({
                    ...item,
                    x: ((item.position - windowRange.start) / windowSpan) * 100,
                }))
                .sort((a, b) => a.position - b.position)
                .map((item) => {
                    let lane = laneLast.findIndex((lastX) => item.x - lastX >= minGapPercent);
                    if (lane === -1) lane = laneLast.length;
                    if (lane >= laneCap) lane = laneCap - 1;
                    laneLast[lane] = item.x;
                    return {
                        ...item,
                        lane,
                        stemHeight: 18 + lane * 20,
                    };
                });
        },
        [windowRange.start, windowSpan],
    );

    const markerVariants = useMemo(
        () => buildMarkerLayout(visibleKnowledgeVariants),
        [buildMarkerLayout, visibleKnowledgeVariants],
    );

    const patientMarkerVariants = useMemo(
        () => (compareMode ? buildMarkerLayout(visiblePatientVariants) : []),
        [buildMarkerLayout, compareMode, visiblePatientVariants],
    );

    const axisTicks = useMemo(() => {
        const count = 5;
        return Array.from({ length: count }).map((_, idx) => {
            const ratio = count === 1 ? 0 : idx / (count - 1);
            const value = windowRange.start + windowSpan * ratio;
            const fractionDigits = windowSpan > 4e6 ? 1 : 2;
            return {
                key: `tick-${idx}`,
                ratio,
                label: formatMb(value, fractionDigits),
            };
        });
    }, [windowRange.start, windowSpan]);

    const pathogenicCount = useMemo(
        () => normalizedVariants.filter((item) => item.clinicalBucket === 'pathogenic').length,
        [normalizedVariants],
    );

    const refAltCount = useMemo(
        () => normalizedVariants.filter((item) => item.ref && item.alt).length,
        [normalizedVariants],
    );

    const structuralVariantCount = useMemo(
        () => normalizedVariants.filter((item) => item.hasRange).length,
        [normalizedVariants],
    );
    const knowledgeVariantCount = normalizedKnowledgeVariants.length;
    const patientVariantCount = normalizedPatientVariants.length;
    const overlappedPatientCount = useMemo(
        () => normalizedPatientVariants.filter((item) => item.knowledgeMatched).length,
        [normalizedPatientVariants],
    );

    const applyZoom = useCallback(
        (scale, anchorRatio = 0.5) => {
            if (fullRange.stop <= fullRange.start) return;
            const safeAnchor = Math.min(1, Math.max(0, anchorRatio));
            setWindowRange((prev) => {
                const currentSpan = Math.max(prev.stop - prev.start, 1);
                const targetSpan = Math.min(
                    fullSpan,
                    Math.max(minWindowSpan, Math.round(currentSpan * scale)),
                );
                const anchor = prev.start + currentSpan * safeAnchor;
                let nextStart = anchor - targetSpan * safeAnchor;
                let nextStop = nextStart + targetSpan;
                if (nextStart < fullRange.start) {
                    nextStart = fullRange.start;
                    nextStop = nextStart + targetSpan;
                }
                if (nextStop > fullRange.stop) {
                    nextStop = fullRange.stop;
                    nextStart = nextStop - targetSpan;
                }
                return {
                    start: Math.max(fullRange.start, nextStart),
                    stop: Math.min(fullRange.stop, nextStop),
                };
            });
        },
        [fullRange.start, fullRange.stop, fullSpan, minWindowSpan],
    );

    const isZoomed = useMemo(() => {
        const epsilon = Math.max(1, fullSpan * 1e-6);
        return (
            Math.abs(windowRange.start - fullRange.start) > epsilon ||
            Math.abs(windowRange.stop - fullRange.stop) > epsilon
        );
    }, [fullRange.start, fullRange.stop, fullSpan, windowRange.start, windowRange.stop]);

    const handleResetZoom = useCallback(() => {
        setBrushState(null);
        setWindowRange({
            start: fullRange.start,
            stop: fullRange.stop,
        });
    }, [fullRange.start, fullRange.stop]);

    const getTrackRatioByClientX = useCallback((clientX) => {
        const rect = trackRef.current?.getBoundingClientRect();
        if (!rect || !Number.isFinite(rect.width) || rect.width <= 0) return 0.5;
        return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    }, []);

    const commitBrushZoom = useCallback(
        (state) => {
            if (!state) return;
            const left = Math.max(0, Math.min(1, Math.min(state.startRatio, state.currentRatio)));
            const right = Math.max(0, Math.min(1, Math.max(state.startRatio, state.currentRatio)));
            const ratioSpan = right - left;
            if (ratioSpan < 0.012) return;

            setWindowRange((prev) => {
                const prevSpan = Math.max(prev.stop - prev.start, 1);
                const targetSpan = Math.max(minWindowSpan, prevSpan * ratioSpan);
                const center = prev.start + prevSpan * (left + right) * 0.5;
                let nextStart = center - targetSpan / 2;
                let nextStop = center + targetSpan / 2;

                if (nextStart < fullRange.start) {
                    nextStart = fullRange.start;
                    nextStop = nextStart + targetSpan;
                }
                if (nextStop > fullRange.stop) {
                    nextStop = fullRange.stop;
                    nextStart = nextStop - targetSpan;
                }

                return {
                    start: Math.max(fullRange.start, nextStart),
                    stop: Math.min(fullRange.stop, nextStop),
                };
            });
        },
        [fullRange.start, fullRange.stop, minWindowSpan],
    );

    const handleTrackMouseDown = useCallback(
        (event) => {
            if (loading || event.button !== 0) return;
            event.preventDefault();
            const ratio = getTrackRatioByClientX(event.clientX);
            setBrushState({ startRatio: ratio, currentRatio: ratio });
        },
        [getTrackRatioByClientX, loading],
    );

    const handleTrackDoubleClick = useCallback(
        (event) => {
            if (loading) return;
            const ratio = getTrackRatioByClientX(event.clientX);
            applyZoom(0.62, ratio);
        },
        [applyZoom, getTrackRatioByClientX, loading],
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
            commitBrushZoom(snapshot);
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
    }, [brushState, commitBrushZoom, getTrackRatioByClientX]);

    const brushVisual = useMemo(() => {
        if (!brushState) return null;
        const left = Math.min(brushState.startRatio, brushState.currentRatio) * 100;
        const right = Math.max(brushState.startRatio, brushState.currentRatio) * 100;
        return {
            left,
            width: Math.max(0, right - left),
        };
    }, [brushState]);

    useEffect(() => {
        if (typeof onWindowChange !== 'function') return;
        onWindowChange({
            start: windowRange.start,
            stop: windowRange.stop,
        });
    }, [onWindowChange, windowRange.start, windowRange.stop]);

    return (
        <Box className="cv-variantzoom">
            <Box className="cv-locus-metrics">
                <div className="metric">
                    <strong>
                        {compareMode ? knowledgeVariantCount : normalizedVariants.length}
                    </strong>
                    <span>{compareMode ? 'Knowledge variants' : 'Total variants'}</span>
                </div>
                <div className="metric">
                    <strong>{compareMode ? patientVariantCount : pathogenicCount}</strong>
                    <span>
                        {compareMode ? 'Patient variants' : 'Pathogenic / likely pathogenic'}
                    </span>
                </div>
                <div className="metric">
                    <strong>{compareMode ? overlappedPatientCount : refAltCount}</strong>
                    <span>
                        {compareMode ? 'Patient matched in knowledge' : 'With REF / ALT calls'}
                    </span>
                </div>
                <div className="metric">
                    <strong>{structuralVariantCount}</strong>
                    <span>
                        {compareMode ? 'Structural variants (combined)' : 'Structural variants'}
                    </span>
                </div>
            </Box>

            <Stack direction="row" alignItems="center" mb={1} className="cv-variant-toolbar-row">
                <Box className="cv-variant-summary cv-variant-summary-inline">
                    <Typography variant="body2" color="text.secondary">
                        {loading
                            ? 'Loading locus variant records...'
                            : `${visibleVariants.length} variants in current window (${normalizedVariants.length} total). Drag to box-zoom; double-click to zoom in.${compareMode ? ' Compare view: knowledge (top) vs patient (bottom).' : ''}`}
                    </Typography>
                    {error && (
                        <Typography variant="body2" color="error">
                            {error}
                        </Typography>
                    )}
                </Box>
                <Button
                    variant="outlined"
                    className="cv-variant-reset-btn"
                    onClick={handleResetZoom}
                    disabled={!isZoomed || loading}
                >
                    RESET
                </Button>
            </Stack>

            <Box
                className={`cv-variant-track${loading ? ' is-loading' : ''}${brushState ? ' is-brushing' : ''}${compareMode ? ' is-compare' : ''}`}
                ref={trackRef}
                onMouseDown={handleTrackMouseDown}
                onDoubleClick={handleTrackDoubleClick}
            >
                {loading && (
                    <div className="cv-variant-track-loading-overlay" aria-hidden="true">
                        <span className="loading-bar bar-1" />
                        <span className="loading-bar bar-2" />
                        <span className="loading-bar bar-3" />
                        <span className="loading-bar bar-4" />
                    </div>
                )}
                {brushVisual && (
                    <div
                        className="cv-variant-brush"
                        style={{ left: `${brushVisual.left}%`, width: `${brushVisual.width}%` }}
                    />
                )}
                <div className="cv-variant-axis" />
                {compareMode ? (
                    <>
                        <span className="cv-variant-track-label is-knowledge">Knowledge</span>
                        <span className="cv-variant-track-label is-patient">Patient</span>
                    </>
                ) : null}
                {axisTicks.map((tick, idx) => (
                    <div
                        key={tick.key}
                        className={[
                            'cv-variant-tick',
                            idx === 0 ? 'is-start' : '',
                            idx === axisTicks.length - 1 ? 'is-end' : '',
                        ]
                            .filter(Boolean)
                            .join(' ')}
                        style={{ left: `${tick.ratio * 100}%` }}
                    >
                        <i />
                        <span>{tick.label}</span>
                    </div>
                ))}
                {markerVariants.map((item) => (
                    <Tooltip
                        key={item.rowKey}
                        arrow
                        placement="top"
                        disableInteractive
                        enterDelay={70}
                        leaveDelay={30}
                        title={
                            <span className="cv-locus-hover-tooltip-text">
                                {buildMarkerHoverFields(item).map((field) => (
                                    <span key={field.label} className="cv-locus-hover-row">
                                        <span className="cv-locus-hover-label">{field.label}:</span>{' '}
                                        <span className="cv-locus-hover-value">{field.value}</span>
                                    </span>
                                ))}
                            </span>
                        }
                        classes={{
                            popper: 'cv-locus-hover-popper',
                            tooltip: 'cv-locus-hover-tooltip',
                            arrow: 'cv-locus-hover-tooltip-arrow',
                        }}
                        PopperProps={{
                            modifiers: [
                                { name: 'offset', options: { offset: [0, 14] } },
                                {
                                    name: 'preventOverflow',
                                    options: { boundary: 'viewport', padding: 12 },
                                },
                                {
                                    name: 'flip',
                                    options: { fallbackPlacements: ['top', 'bottom'] },
                                },
                            ],
                        }}
                    >
                        <button
                            type="button"
                            className={[
                                'cv-variant-marker',
                                `is-${item.clinicalBucket}`,
                                compareMode ? 'is-track-knowledge' : '',
                                compareMode &&
                                matchedPatientExactKeySet.has(
                                    item?.exactKey || buildVariantExactKey(item),
                                )
                                    ? 'is-hit'
                                    : '',
                                selectedVariant?.rowKey === item.rowKey ? 'is-selected' : '',
                            ]
                                .filter(Boolean)
                                .join(' ')}
                            style={{
                                left: `${Math.min(Math.max(item.x, 0), 100)}%`,
                                ...(compareMode
                                    ? {
                                          bottom: `calc(50% - ${COMPARE_TRACK_VERTICAL_OFFSET_PX}px)`,
                                      }
                                    : {}),
                            }}
                            onClick={() => handleSelectVariantKey(item.rowKey)}
                        >
                            <span className="dot" />
                            <span className="stem" style={{ height: `${item.stemHeight}px` }} />
                        </button>
                    </Tooltip>
                ))}
                {compareMode &&
                    patientMarkerVariants.map((item) => (
                        <Tooltip
                            key={item.rowKey}
                            arrow
                            placement="bottom"
                            disableInteractive
                            enterDelay={70}
                            leaveDelay={30}
                            title={
                                <span className="cv-locus-hover-tooltip-text">
                                    {buildMarkerHoverFields(item).map((field) => (
                                        <span key={field.label} className="cv-locus-hover-row">
                                            <span className="cv-locus-hover-label">
                                                {field.label}:
                                            </span>{' '}
                                            <span className="cv-locus-hover-value">
                                                {field.value}
                                            </span>
                                        </span>
                                    ))}
                                </span>
                            }
                            classes={{
                                popper: 'cv-locus-hover-popper',
                                tooltip: 'cv-locus-hover-tooltip',
                                arrow: 'cv-locus-hover-tooltip-arrow',
                            }}
                            PopperProps={{
                                modifiers: [
                                    { name: 'offset', options: { offset: [0, 14] } },
                                    {
                                        name: 'preventOverflow',
                                        options: { boundary: 'viewport', padding: 12 },
                                    },
                                    {
                                        name: 'flip',
                                        options: { fallbackPlacements: ['bottom', 'top'] },
                                    },
                                ],
                            }}
                        >
                            <button
                                type="button"
                                className={[
                                    'cv-variant-marker',
                                    'is-track-patient',
                                    `is-${item.markerBucket || item.clinicalBucket}`,
                                    item?.knowledgeMatched ? 'is-hit' : '',
                                    selectedVariant?.rowKey === item.rowKey ? 'is-selected' : '',
                                ]
                                    .filter(Boolean)
                                    .join(' ')}
                                style={{
                                    left: `${Math.min(Math.max(item.x, 0), 100)}%`,
                                    top: `calc(50% + ${COMPARE_TRACK_VERTICAL_OFFSET_PX}px)`,
                                }}
                                onClick={() => handleSelectVariantKey(item.rowKey)}
                            >
                                <span className="stem" style={{ height: `${item.stemHeight}px` }} />
                                <span className="dot" />
                            </button>
                        </Tooltip>
                    ))}
            </Box>

            {!loading && (
                <Box className="cv-variant-panels">
                    <Box className="cv-variant-list" ref={variantListRef}>
                        {visibleVariants.map((item) => (
                            <button
                                type="button"
                                className={[
                                    'cv-variant-row',
                                    `is-${item.clinicalBucket}`,
                                    selectedVariant?.rowKey === item.rowKey ? 'is-selected' : '',
                                ]
                                    .filter(Boolean)
                                    .join(' ')}
                                key={item.rowKey}
                                ref={(node) => bindVariantRowRef(item.rowKey, node)}
                                onClick={() => handleSelectVariantKey(item.rowKey)}
                            >
                                <div className="cv-variant-row-header">
                                    <strong className="cv-variant-row-type">
                                        {asText(
                                            item.variantType,
                                            item.hasRange
                                                ? 'Structural variant'
                                                : 'Single-point variant',
                                        )}
                                    </strong>
                                    <div className="cv-variant-row-chip-group">
                                        {compareMode ? (
                                            <span
                                                className={`cv-source-chip is-${item.sourceTag === 'patient' ? 'patient' : 'knowledge'}`}
                                            >
                                                {item.sourceTag === 'patient'
                                                    ? 'Patient'
                                                    : 'Knowledge'}
                                            </span>
                                        ) : null}
                                        {item.isPatientOnly ? (
                                            <span className="cv-clin-chip is-patient-only">
                                                Patient-only
                                            </span>
                                        ) : null}
                                        <span className={`cv-clin-chip is-${item.clinicalBucket}`}>
                                            {normalizeClinicalText(
                                                item.clinicalText,
                                                'Not classified',
                                            )}
                                        </span>
                                    </div>
                                </div>

                                <span className="cv-variant-row-coordinate">
                                    {item.coordinateLabel}
                                </span>

                                <div className="cv-variant-row-highlight">
                                    {item.ref && item.alt ? (
                                        <span className="cv-refalt-inline is-emphasis">
                                            <i>{item.ref}</i>
                                            <b>{'>'}</b>
                                            <i>{item.alt}</i>
                                        </span>
                                    ) : (
                                        <span className="cv-variant-span-inline">
                                            {item.hasRange && Number.isFinite(item.spanBp)
                                                ? `Span ${formatBp(item.spanBp)}`
                                                : 'Span N/A'}
                                        </span>
                                    )}
                                    <span className="cv-variant-row-gene">
                                        {`Gene: ${asText(item.geneSymbol, gene?.name || 'N/A')}`}
                                    </span>
                                </div>
                            </button>
                        ))}
                        {!visibleVariants.length && (
                            <Typography variant="body2" color="text.secondary">
                                No variants found in this window.
                            </Typography>
                        )}
                    </Box>

                    <Box className="cv-variant-focus">
                        {selectedVariant ? (
                            <>
                                <div className="cv-variant-focus-head">
                                    <div className="cv-variant-focus-head-main">
                                        <strong>{selectedVariant.headline}</strong>
                                        <span className="cv-variant-focus-subline">
                                            {selectedVariant.subline}
                                        </span>
                                    </div>
                                    <div className="cv-variant-row-chip-group">
                                        {compareMode ? (
                                            <span
                                                className={`cv-source-chip is-${selectedVariant.sourceTag === 'patient' ? 'patient' : 'knowledge'}`}
                                            >
                                                {selectedVariant.sourceTag === 'patient'
                                                    ? 'Patient'
                                                    : 'Knowledge'}
                                            </span>
                                        ) : null}
                                        {selectedVariant.isPatientOnly ? (
                                            <span className="cv-clin-chip is-patient-only">
                                                Patient-only
                                            </span>
                                        ) : null}
                                        <span
                                            className={`cv-clin-chip is-${selectedVariant.clinicalBucket}`}
                                        >
                                            {normalizeClinicalText(
                                                selectedVariant.clinicalText,
                                                'Not classified',
                                            )}
                                        </span>
                                    </div>
                                </div>
                                <div className="cv-variant-focus-grid">
                                    <div>
                                        <em className="cv-field-label">Coordinate</em>
                                        <span>{selectedVariant.coordinateLabel || 'N/A'}</span>
                                    </div>
                                    <div>
                                        <em className="cv-field-label">Variant ID</em>
                                        <span>
                                            {asText(
                                                selectedVariant.variantId,
                                                selectedVariant.displayId,
                                            )}
                                        </span>
                                    </div>
                                    <div>
                                        <em className="cv-field-label">Gene symbol</em>
                                        <span>
                                            {asText(
                                                selectedVariant.geneSymbol,
                                                gene?.name || 'N/A',
                                            )}
                                        </span>
                                    </div>
                                    <div>
                                        <em className="cv-field-label">Variant type</em>
                                        <span>
                                            {asText(selectedVariant.variantType, 'Unknown')}
                                        </span>
                                    </div>
                                    <div>
                                        <em className="cv-field-label">Variant class</em>
                                        <span>{selectedVariant.variantClass}</span>
                                    </div>
                                    {compareMode ? (
                                        <div>
                                            <em className="cv-field-label">Source</em>
                                            <span>
                                                {selectedVariant.sourceTag === 'patient'
                                                    ? 'Patient case'
                                                    : 'Knowledge base'}
                                            </span>
                                        </div>
                                    ) : null}
                                    <div>
                                        <em className="cv-field-label">REF / ALT</em>
                                        {selectedVariant.ref && selectedVariant.alt ? (
                                            <span className="cv-refalt-group">
                                                <i className="cv-refalt-badge ref">
                                                    {selectedVariant.ref}
                                                </i>
                                                <b>{'>'}</b>
                                                <i className="cv-refalt-badge alt">
                                                    {selectedVariant.alt}
                                                </i>
                                            </span>
                                        ) : (
                                            <span>
                                                {selectedVariant.hasRange
                                                    ? 'Not applicable (structural variant)'
                                                    : 'N/A'}
                                            </span>
                                        )}
                                    </div>
                                    <div>
                                        <em className="cv-field-label">Span length</em>
                                        <span>
                                            {selectedVariant.hasRange &&
                                            Number.isFinite(selectedVariant.spanBp)
                                                ? formatBp(selectedVariant.spanBp)
                                                : 'Single-point variant'}
                                        </span>
                                    </div>
                                    <div>
                                        <em className="cv-field-label">Clinical significance</em>
                                        <span>
                                            {normalizeClinicalText(
                                                selectedVariant.clinicalText,
                                                'Not classified',
                                            )}
                                        </span>
                                    </div>
                                </div>
                            </>
                        ) : (
                            <Typography variant="body2" color="text.secondary">
                                Select a variant from the left list to inspect details.
                            </Typography>
                        )}
                    </Box>
                </Box>
            )}
        </Box>
    );
};

export default LocusZoom;
