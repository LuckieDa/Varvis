// src/pages/varvis/ChromosomeVisualization/services/cytobands.js
import axios from 'axios';

const VARIANT_BASE = '/variantBasedMap';
const DEFAULT_RETRY = 2;
const DEFAULT_RETRY_DELAY = 250;
const CACHE_TTL = 5 * 60 * 1000;
const SEARCH_TTL = 60 * 1000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const shouldRetry = (error) => {
    if (!error) return true;
    if (error.response && Number.isFinite(error.response.status)) {
        return false;
    }
    return true;
};

const requestWithRetry = async (fn, retries = DEFAULT_RETRY, delayMs = DEFAULT_RETRY_DELAY) => {
    let lastError;
    for (let attempt = 0; attempt <= retries; attempt += 1) {
        try {
            return await fn();
        } catch (error) {
            lastError = error;
            if (!shouldRetry(error)) break;
            if (attempt === retries) break;
            await sleep(delayMs * (attempt + 1));
        }
    }
    throw lastError;
};

const now = () => Date.now();

const isFresh = (entry, ttl = CACHE_TTL) =>
    entry && entry.data != null && now() - (entry.ts || 0) < ttl;

const wrapSingletonFetch = async (slot, fetcher, ttl = CACHE_TTL) => {
    if (isFresh(slot, ttl)) return slot.data;
    if (slot.inflight) return slot.inflight;
    const p = fetcher()
        .then((data) => {
            slot.data = data;
            slot.ts = now();
            slot.inflight = null;
            return data;
        })
        .catch((error) => {
            slot.inflight = null;
            throw error;
        });
    slot.inflight = p;
    return p;
};

const wrapMapFetch = async (map, key, fetcher, ttl = CACHE_TTL) => {
    const entry = map.get(key);
    if (isFresh(entry, ttl)) return entry.data;
    if (entry?.inflight) return entry.inflight;
    const p = fetcher()
        .then((data) => {
            map.set(key, { data, ts: now(), inflight: null });
            return data;
        })
        .catch((error) => {
            map.set(key, { ...(entry || {}), inflight: null });
            throw error;
        });
    map.set(key, { ...(entry || {}), inflight: p });
    return p;
};

const runWithConcurrency = async (items, limit, worker) => {
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

const cache = {
    chromosomes: { data: null, ts: 0, inflight: null },
    cytobands: { data: null, ts: 0, inflight: null },
    bandsByChr: new Map(),
    chromosomeOverview: new Map(),
    bandSummary: new Map(),
    bandDiseaseCountsByChr: new Map(),
    bandDiseaseCountsAll: { data: null, ts: 0, inflight: null },
    bandDiseases: new Map(),
    bandGenes: new Map(),
    diseaseDetail: new Map(),
    diseaseGenes: new Map(),
    geneSearch: new Map(),
    geneDetail: new Map(),
    geneCoordinate: new Map(),
    geneCoordinateBatch: new Map(),
    geneDiseases: new Map(),
    regionDetail: new Map(),
    smallVariants: new Map(),
    structuralVariants: new Map(),
    caseSmallVariants: new Map(),
    caseStructuralVariants: new Map(),
    caseSmallVariantsRegion: new Map(),
    caseStructuralVariantsRegion: new Map(),
    caseOverlapBandsByChromosome: new Map(),
    caseMatchedVariants: new Map(),
    knowledgeSmallVariantsRegion: new Map(),
    knowledgeStructuralVariantsRegion: new Map(),
};

const normalizeBand = (band, chromosome) => ({
    bandId: band.band_id ?? band.bandId ?? band.id ?? null,
    name: band.band_name ?? band.bandName ?? band.name ?? '',
    start: band.start_bp ?? band.startBp ?? band.start ?? null,
    stop: band.end_bp ?? band.endBp ?? band.stop ?? null,
    gieStain: band.gie_stain ?? band.gieStain ?? band.stain ?? '',
    chromosome: band.chromosome ?? chromosome ?? null,
});

export const fetchChromosomes = async () => {
    return wrapSingletonFetch(cache.chromosomes, async () => {
        // Initial readiness must fail promptly; the user can retry after starting the backend.
        const res = await axios.get(`${VARIANT_BASE}/chromosomes`, { timeout: 15000 });
        const rows = Array.isArray(res?.data) ? res.data : [];
        if (!rows.some((row) => String(row?.chromosome || '').trim())) {
            throw new Error('Chromosome data is empty. Check the backend data source.');
        }
        return rows;
    });
};

export const fetchBandsByChromosome = async (chromosome) => {
    if (!chromosome) return [];
    return wrapMapFetch(cache.bandsByChr, String(chromosome), async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/chromosomes/${encodeURIComponent(chromosome)}/bands`, {
                timeout: 20000,
            }),
        );
        const rows = Array.isArray(res?.data) ? res.data : [];
        if (!rows.length) throw new Error(`Cytoband data for chromosome ${chromosome} is empty.`);
        return rows;
    });
};

export const fetchChromosomeOverview = async (chromosome) => {
    if (!chromosome) return null;
    return wrapMapFetch(cache.chromosomeOverview, String(chromosome), async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/chromosomes/${encodeURIComponent(chromosome)}/overview`, {
                timeout: 20000,
            }),
        );
        return res?.data || null;
    });
};

export const fetchBandSummary = async (bandId) => {
    if (!bandId) return null;
    return wrapMapFetch(cache.bandSummary, String(bandId), async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/bands/${encodeURIComponent(bandId)}/summary`, {
                timeout: 20000,
            }),
        );
        return res?.data || null;
    });
};

export const fetchBandDiseaseCountsByChromosome = async (chromosome) => {
    if (!chromosome) return [];
    return wrapMapFetch(cache.bandDiseaseCountsByChr, String(chromosome), async () => {
        const res = await requestWithRetry(() =>
            axios.get(
                `${VARIANT_BASE}/chromosomes/${encodeURIComponent(chromosome)}/bandDiseaseCounts`,
                {
                    timeout: 20000,
                },
            ),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchAllBandDiseaseCounts = async () => {
    return wrapSingletonFetch(cache.bandDiseaseCountsAll, async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/bandDiseaseCounts`, { timeout: 25000 }),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchBandDiseases = async (bandId, page = 1, pageSize = 200) => {
    if (!bandId) return [];
    const key = `${bandId}-${page}-${pageSize}`;
    return wrapMapFetch(cache.bandDiseases, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/bands/${encodeURIComponent(bandId)}/diseases`, {
                params: { page, pageSize },
                timeout: 20000,
            }),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchBandGenes = async (bandId) => {
    if (!bandId) return [];
    return wrapMapFetch(cache.bandGenes, String(bandId), async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/bands/${encodeURIComponent(bandId)}/genes`, {
                timeout: 20000,
            }),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchDiseaseDetail = async (diseaseId) => {
    if (!diseaseId) return null;
    return wrapMapFetch(cache.diseaseDetail, String(diseaseId), async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/diseases/${encodeURIComponent(diseaseId)}/detail`, {
                timeout: 20000,
            }),
        );
        return res?.data || null;
    });
};

export const fetchDiseaseGenes = async (diseaseId, page = 1, pageSize = 200) => {
    if (!diseaseId) return [];
    const key = `${diseaseId}-${page}-${pageSize}`;
    return wrapMapFetch(cache.diseaseGenes, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/diseases/${encodeURIComponent(diseaseId)}/genes`, {
                params: { page, pageSize },
                timeout: 20000,
            }),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchGeneSearch = async (keyword, page = 1, pageSize = 10) => {
    const safeKeyword = String(keyword || '').trim();
    const key = `${safeKeyword.toUpperCase() || '__ALL__'}-${page}-${pageSize}`;
    return wrapMapFetch(
        cache.geneSearch,
        key,
        async () => {
            const res = await requestWithRetry(() =>
                axios.get(`${VARIANT_BASE}/genes/search`, {
                    params: { keyword: safeKeyword, page, pageSize },
                    timeout: 20000,
                }),
            );
            return Array.isArray(res?.data) ? res.data : [];
        },
        SEARCH_TTL,
    );
};

export const fetchGeneDetail = async (geneSymbol) => {
    if (!geneSymbol) return null;
    return wrapMapFetch(cache.geneDetail, String(geneSymbol).toUpperCase(), async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/genes/${encodeURIComponent(geneSymbol)}/detail`, {
                timeout: 20000,
            }),
        );
        return res?.data || null;
    });
};

export const fetchGeneCoordinate = async (geneSymbol) => {
    if (!geneSymbol) return null;
    return wrapMapFetch(cache.geneCoordinate, String(geneSymbol).toUpperCase(), async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/genes/${encodeURIComponent(geneSymbol)}/coordinate`, {
                timeout: 20000,
            }),
        );
        return res?.data || null;
    });
};

export const fetchGeneCoordinates = async (geneSymbols = []) => {
    const list = (Array.isArray(geneSymbols) ? geneSymbols : [])
        .map((sym) =>
            String(sym || '')
                .trim()
                .toUpperCase(),
        )
        .filter(Boolean);
    if (!list.length) return [];
    const unique = Array.from(new Set(list));
    const key = unique.slice().sort().join('|');
    return wrapMapFetch(cache.geneCoordinateBatch, key, async () => {
        const res = await requestWithRetry(
            () => axios.post(`${VARIANT_BASE}/genes/coordinates`, unique, { timeout: 20000 }),
            1,
            350,
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchGeneDiseases = async (geneSymbol) => {
    if (!geneSymbol) return [];
    return wrapMapFetch(cache.geneDiseases, String(geneSymbol).toUpperCase(), async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/genes/${encodeURIComponent(geneSymbol)}/diseases`, {
                timeout: 20000,
            }),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchRegionDetail = async (chromosome, start, end) => {
    if (!chromosome || start == null || end == null) return null;
    const key = `${chromosome}-${start}-${end}`;
    return wrapMapFetch(cache.regionDetail, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/regions/detail`, {
                params: { chr: chromosome, start, end },
                timeout: 20000,
            }),
        );
        return res?.data || null;
    });
};

export const fetchSmallVariantsByGene = async (geneSymbol, page = 1, pageSize = 200) => {
    if (!geneSymbol) return [];
    const key = `${String(geneSymbol).toUpperCase()}-${page}-${pageSize}`;
    return wrapMapFetch(cache.smallVariants, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/genes/${encodeURIComponent(geneSymbol)}/smallVariants`, {
                params: { page, pageSize },
                timeout: 20000,
            }),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchStructuralVariantsByGene = async (geneSymbol, page = 1, pageSize = 200) => {
    if (!geneSymbol) return [];
    const key = `${String(geneSymbol).toUpperCase()}-${page}-${pageSize}`;
    return wrapMapFetch(cache.structuralVariants, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(
                `${VARIANT_BASE}/genes/${encodeURIComponent(geneSymbol)}/structuralVariants`,
                {
                    params: { page, pageSize },
                    timeout: 20000,
                },
            ),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchKnowledgeSmallVariantsByRegion = async ({
    chromosome,
    start,
    end,
    page = 1,
    pageSize = 200,
    sortBy = 'start_bp',
    sortOrder = 'asc',
} = {}) => {
    if (!chromosome) return [];
    const key = `${chromosome}:${start}:${end}:${page}:${pageSize}:${sortBy}:${sortOrder}`;
    return wrapMapFetch(cache.knowledgeSmallVariantsRegion, key, async () => {
        const payload = {
            chromosome,
            start_bp: start,
            end_bp: end,
            page,
            page_size: pageSize,
            sort_by: sortBy,
            sort_order: sortOrder,
        };
        const res = await requestWithRetry(
            () => axios.post(`${VARIANT_BASE}/positions/small/filter`, payload, { timeout: 30000 }),
            1,
            350,
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchKnowledgeStructuralVariantsByRegion = async ({
    chromosome,
    start,
    end,
    page = 1,
    pageSize = 200,
    sortBy = 'start_bp',
    sortOrder = 'asc',
} = {}) => {
    if (!chromosome) return [];
    const key = `${chromosome}:${start}:${end}:${page}:${pageSize}:${sortBy}:${sortOrder}`;
    return wrapMapFetch(cache.knowledgeStructuralVariantsRegion, key, async () => {
        const payload = {
            chromosome,
            start_bp: start,
            end_bp: end,
            page,
            page_size: pageSize,
            sort_by: sortBy,
            sort_order: sortOrder,
        };
        const res = await requestWithRetry(
            () =>
                axios.post(`${VARIANT_BASE}/positions/structural/filter`, payload, {
                    timeout: 30000,
                }),
            1,
            350,
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const uploadCaseVcf = async (file, genomeAssembly) => {
    if (!file) {
        throw new Error('VCF file is required');
    }
    if (!genomeAssembly) {
        throw new Error('Genome assembly is required');
    }
    const formData = new FormData();
    formData.append('vcfFile', file);
    formData.append('genomeAssembly', genomeAssembly);
    const res = await requestWithRetry(
        () =>
            axios.post(`${VARIANT_BASE}/cases/vcf`, formData, {
                timeout: 120000,
            }),
        0,
    );
    const payload = res?.data || null;
    if (!payload || typeof payload !== 'object') return null;
    return {
        caseId: payload.caseId ?? payload.case_id ?? null,
        fileName: payload.fileName ?? payload.file_name ?? null,
        sampleName: payload.sampleName ?? payload.sample_name ?? null,
        genomeAssembly: payload.genomeAssembly ?? payload.genome_assembly ?? null,
        totalVariants: payload.totalVariants ?? payload.total_variants ?? 0,
        smallVariantCount: payload.smallVariantCount ?? payload.small_variant_count ?? 0,
        structuralVariantCount:
            payload.structuralVariantCount ?? payload.structural_variant_count ?? 0,
        uploadedAtEpochMs: payload.uploadedAtEpochMs ?? payload.uploaded_at_epoch_ms ?? null,
    };
};

export const removeCaseVcf = async (caseId) => {
    if (!caseId) return false;
    const res = await requestWithRetry(
        () =>
            axios.delete(`${VARIANT_BASE}/cases/${encodeURIComponent(caseId)}`, { timeout: 20000 }),
        0,
    );
    return Boolean(res?.data);
};

export const fetchCaseSmallVariantsByGene = async ({
    caseId,
    geneSymbol,
    chromosome,
    start,
    end,
    page = 1,
    pageSize = 200,
} = {}) => {
    if (!caseId || !geneSymbol || !chromosome) return [];
    const key = `${caseId}:${String(geneSymbol).toUpperCase()}:${chromosome}:${start}:${end}:${page}:${pageSize}`;
    return wrapMapFetch(cache.caseSmallVariants, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(
                `${VARIANT_BASE}/cases/${encodeURIComponent(caseId)}/genes/${encodeURIComponent(geneSymbol)}/smallVariants`,
                {
                    params: {
                        chr: chromosome,
                        start,
                        end,
                        page,
                        pageSize,
                    },
                    timeout: 30000,
                },
            ),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchCaseStructuralVariantsByGene = async ({
    caseId,
    geneSymbol,
    chromosome,
    start,
    end,
    page = 1,
    pageSize = 200,
} = {}) => {
    if (!caseId || !geneSymbol || !chromosome) return [];
    const key = `${caseId}:${String(geneSymbol).toUpperCase()}:${chromosome}:${start}:${end}:${page}:${pageSize}`;
    return wrapMapFetch(cache.caseStructuralVariants, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(
                `${VARIANT_BASE}/cases/${encodeURIComponent(caseId)}/genes/${encodeURIComponent(geneSymbol)}/structuralVariants`,
                {
                    params: {
                        chr: chromosome,
                        start,
                        end,
                        page,
                        pageSize,
                    },
                    timeout: 30000,
                },
            ),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchCaseSmallVariantsByRegion = async ({
    caseId,
    chromosome,
    start,
    end,
    page = 1,
    pageSize = 5000,
} = {}) => {
    if (!caseId || !chromosome) return [];
    const key = `${caseId}:${chromosome}:${start}:${end}:${page}:${pageSize}`;
    return wrapMapFetch(cache.caseSmallVariantsRegion, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/cases/${encodeURIComponent(caseId)}/smallVariants`, {
                params: {
                    chr: chromosome,
                    start,
                    end,
                    page,
                    pageSize,
                },
                timeout: 45000,
            }),
        );
        const rows = Array.isArray(res?.data) ? res.data : [];
        return rows.map((row) => ({
            ...row,
            type: row?.variant_type ?? row?.variantType ?? row?.type ?? null,
            variantType: row?.variant_type ?? row?.variantType ?? row?.type ?? null,
        }));
    });
};

export const fetchCaseStructuralVariantsByRegion = async ({
    caseId,
    chromosome,
    start,
    end,
    page = 1,
    pageSize = 5000,
} = {}) => {
    if (!caseId || !chromosome) return [];
    const key = `${caseId}:${chromosome}:${start}:${end}:${page}:${pageSize}`;
    return wrapMapFetch(cache.caseStructuralVariantsRegion, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/cases/${encodeURIComponent(caseId)}/structuralVariants`, {
                params: {
                    chr: chromosome,
                    start,
                    end,
                    page,
                    pageSize,
                },
                timeout: 45000,
            }),
        );
        const rows = Array.isArray(res?.data) ? res.data : [];
        return rows.map((row) => {
            const start = row?.start_bp ?? row?.startBp ?? row?.start ?? row?.position ?? null;
            const end = row?.end_bp ?? row?.endBp ?? row?.end ?? start;
            const variantType =
                row?.sv_type ?? row?.svType ?? row?.variantType ?? row?.type ?? 'SV';
            return {
                ...row,
                position: start,
                start,
                end,
                type: variantType,
                variantType,
            };
        });
    });
};

export const fetchCaseOverlapBandsByChromosome = async ({ caseId, chromosome } = {}) => {
    if (!caseId || !chromosome) return [];
    const key = `${caseId}:${chromosome}`;
    return wrapMapFetch(cache.caseOverlapBandsByChromosome, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(
                `${VARIANT_BASE}/cases/${encodeURIComponent(caseId)}/chromosomes/${encodeURIComponent(chromosome)}/overlapBands`,
                {
                    timeout: 30000,
                },
            ),
        );
        return Array.isArray(res?.data) ? res.data : [];
    });
};

export const fetchCaseMatchedVariants = async ({
    caseId,
    chromosome,
    page = 1,
    pageSize = 500,
} = {}) => {
    if (!caseId) return [];
    const key = `${caseId}:${chromosome || 'ALL'}:${page}:${pageSize}`;
    return wrapMapFetch(cache.caseMatchedVariants, key, async () => {
        const res = await requestWithRetry(() =>
            axios.get(`${VARIANT_BASE}/cases/${encodeURIComponent(caseId)}/matchedVariants`, {
                params: {
                    chr: chromosome || undefined,
                    page,
                    pageSize,
                },
                timeout: 45000,
            }),
        );
        const rows = Array.isArray(res?.data) ? res.data : [];
        return rows.map((row) => {
            const start = row?.start_bp ?? row?.startBp ?? row?.start ?? row?.position ?? null;
            const end = row?.end_bp ?? row?.endBp ?? row?.end ?? start;
            return {
                ...row,
                start,
                end,
                position: row?.position ?? start,
                variantType:
                    row?.variant_type ??
                    row?.variantType ??
                    row?.sv_type ??
                    row?.svType ??
                    row?.type ??
                    null,
                exactKey: row?.exact_key ?? row?.exactKey ?? null,
                geneSymbol: row?.gene_symbol ?? row?.geneSymbol ?? null,
            };
        });
    });
};

export const fetchCytobands = async () => {
    return wrapSingletonFetch(cache.cytobands, async () => {
        const chromosomes = await fetchChromosomes();
        const chrList = (chromosomes || [])
            .map((item) => String(item?.chromosome || '').trim())
            .filter(Boolean);

        if (!chrList.length) throw new Error('Chromosome data is empty. Check the backend data source.');

        const responses = await runWithConcurrency(chrList, 6, async (chr) => {
            // This function already retries. Never multiply retries or cache failed bands as success.
            const bands = await fetchBandsByChromosome(chr);
            if (!bands.length) throw new Error(`Cytoband data for chromosome ${chr} is empty.`);
            return { chr, bands };
        });

        const cytobands = {};
        responses.forEach(({ chr, bands }) => {
            const safeBands = Array.isArray(bands) ? bands : [];
            cytobands[chr] = safeBands.map((band) => normalizeBand(band, chr));
        });
        return cytobands;
    });
};

export const getCachedCytobands = () => (isFresh(cache.cytobands) ? cache.cytobands.data : null);
