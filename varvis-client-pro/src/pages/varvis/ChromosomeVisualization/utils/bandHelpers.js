export function parseCoordinate(value) {
    if (Number.isFinite(value)) return Number(value);
    if (value == null) return null;
    const text = String(value).trim();
    if (!text) return null;
    const compact = text.replace(/[,\s_]/g, '');
    const direct = Number(compact);
    if (Number.isFinite(direct)) return direct;

    const numericTokens = text.match(/-?\d[\d,]*(?:\.\d+)?/g);
    if (!numericTokens?.length) return null;
    const normalized = numericTokens
        .map((token) => String(token).replace(/,/g, ''))
        .map((token) => ({ token, digits: token.replace(/\D/g, '').length }))
        .filter((item) => item.digits > 0);
    if (!normalized.length) return null;

    let candidate = normalized[normalized.length - 1];
    const longest = normalized.reduce(
        (best, item) => (item.digits > best.digits ? item : best),
        normalized[0],
    );
    // For mixed strings like "chr1:12345", prefer the longer token instead of chromosome index.
    if (candidate.digits <= 3 && longest.digits > candidate.digits) {
        candidate = longest;
    }

    const parsed = Number(candidate.token);
    return Number.isFinite(parsed) ? parsed : null;
}

export function resolveBandRange(band) {
    if (!band) return { start: null, stop: null };
    const startCandidates = [band.start, band.bp?.[0], band.bpStart, band.rangeStart];
    const stopCandidates = [band.stop, band.bp?.[1], band.bpStop, band.rangeStop];
    const parsedStart = pickFirstCoordinate(startCandidates);
    const parsedStop = pickFirstCoordinate(stopCandidates);
    if (!Number.isFinite(parsedStart) || !Number.isFinite(parsedStop)) {
        return { start: null, stop: null };
    }
    return {
        start: Math.min(parsedStart, parsedStop),
        stop: Math.max(parsedStart, parsedStop),
    };
}

function pickFirstCoordinate(candidates = []) {
    for (let i = 0; i < candidates.length; i += 1) {
        const parsed = parseCoordinate(candidates[i]);
        if (Number.isFinite(parsed)) return parsed;
    }
    return null;
}

export function buildChrBands(chromosome, bands) {
    const safe = Array.isArray(bands) ? bands : [];
    return {
        [chromosome]: safe
            .map((b) => {
                const range = resolveBandRange(b);
                return {
                    ...b,
                    start: range.start,
                    stop: range.stop,
                };
            })
            .filter((b) => Number.isFinite(b.start) && Number.isFinite(b.stop) && b.stop > b.start)
            .map((b) => ({
                bp: [b.start, b.stop],
                chr: chromosome,
                name: b.name,
                gieStain: b.gieStain,
            })),
    };
}

export function buildBandLookup(bands) {
    const safe = Array.isArray(bands) ? bands : [];
    const map = new Map();
    safe.forEach((b) => {
        if (b?.name) {
            map.set(b.name.toLowerCase(), b);
        }
    });
    return map;
}

export function getBandDiseases({ band, bandDiseases }) {
    if (!band) return [];
    const direct = Array.isArray(band.diseases) ? band.diseases : [];
    if (direct.length) {
        return direct.map((d) => ({
            ...d,
            id: d.id || d.disease_id || d.orpha || d.name,
        }));
    }
    if (!bandDiseases || band.bandId == null) return [];
    return bandDiseases[band.bandId] || [];
}

export function splitDiseasesByRange(diseases) {
    const point = [];
    const range = [];
    diseases.forEach((dz) => {
        if (isRangeDisease(dz)) range.push(dz);
        else point.push(dz);
    });
    return { point, range };
}

function isRangeDisease(dz) {
    const locusType = (dz?.locus_type || dz?.Locus_Type || '').toString().toLowerCase();
    if (locusType.includes('range')) return true;
    const locus = (dz?.locus || dz?.Chromosomal_Loci || '').toString();
    return /[pq]\d+(?:\.\d+)?\s*(?:\u2013|-|to)\s*[pq]?\d+(?:\.\d+)?/i.test(locus);
}

export function bandSummary(band) {
    if (!band) return null;
    const { start: rawStart, stop: rawStop } = resolveBandRange(band);
    const start = Number.isFinite(rawStart) ? rawStart : null;
    const stop = Number.isFinite(rawStop) ? rawStop : null;
    return {
        start,
        stop,
        length: Number.isFinite(rawStart) && Number.isFinite(rawStop) ? rawStop - rawStart : null,
        stain: band.gieStain,
    };
}
