import { parseCoordinate } from './bandHelpers';

export function extractGenesFromDisease(dz) {
    if (!dz) return [];
    const candidates = [
        dz.genes,
        dz.Genes,
        dz.gene,
        dz.Gene,
        dz.gene_symbol,
        dz.Gene_symbol,
        dz.geneSymbols,
        dz.GeneSymbols,
    ];
    const bucket = [];
    candidates.forEach((token) => {
        if (!token) return;
        if (Array.isArray(token)) {
            token.forEach((item) => {
                if (typeof item === 'string') {
                    const trimmed = item.trim();
                    if (trimmed) bucket.push(trimmed.toUpperCase());
                }
            });
        } else if (typeof token === 'string') {
            token
                .split(/[,;/\s]+/)
                .map((part) => part.trim())
                .filter(Boolean)
                .forEach((part) => bucket.push(part.toUpperCase()));
        }
    });
    return Array.from(new Set(bucket));
}

export function buildBandGenes(diseases = [], bandMeta = null) {
    const geneMap = new Map();

    diseases.forEach((disease) => {
        const genes = extractGenesFromDisease(disease);
        genes.forEach((symbol) => {
            const key = symbol.toUpperCase();
            if (!geneMap.has(key)) {
                geneMap.set(key, {
                    name: key,
                    gene: key,
                    diseaseCount: 0,
                    diseases: [],
                    variantCount: null,
                    start: null,
                    stop: null,
                    coordinateMode: 'unavailable',
                });
            }
            const item = geneMap.get(key);
            item.diseaseCount += 1;
            item.diseases.push(disease);
        });
    });

    const genes = Array.from(geneMap.values()).sort((a, b) => {
        if (b.diseaseCount !== a.diseaseCount) return b.diseaseCount - a.diseaseCount;
        return a.name.localeCompare(b.name);
    });

    return applyEstimatedGeneCoordinates(genes, bandMeta);
}

export function scoreGeneImportance(gene) {
    if (!gene) return 0;
    const diseaseCount = Number(gene.diseaseCount) || 0;
    const variantCount = Number(gene.variantCount) || 0;
    const hasVariantSpan = gene.coordinateMode === 'variant_span';
    return diseaseCount * 100 + variantCount * 4 + (hasVariantSpan ? 35 : 0);
}

export function pickImportantGenes(genes = [], limit = 10) {
    const safe = Array.isArray(genes) ? genes.filter(Boolean) : [];
    if (!safe.length) return [];
    const cap = Math.max(1, Math.min(Number(limit) || 1, safe.length));
    return [...safe]
        .sort((a, b) => {
            const scoreDelta = scoreGeneImportance(b) - scoreGeneImportance(a);
            if (scoreDelta !== 0) return scoreDelta;
            const spanA = Math.abs((Number(a?.stop) || 0) - (Number(a?.start) || 0));
            const spanB = Math.abs((Number(b?.stop) || 0) - (Number(b?.start) || 0));
            if (spanB !== spanA) return spanB - spanA;
            return String(a?.name || '').localeCompare(String(b?.name || ''));
        })
        .slice(0, cap);
}

function applyEstimatedGeneCoordinates(genes, bandMeta) {
    if (!genes.length) return [];
    const start = parseCoordinate(bandMeta?.start);
    const stop = parseCoordinate(bandMeta?.stop);
    if (!Number.isFinite(start) || !Number.isFinite(stop) || stop <= start) {
        return genes.map((gene, index) => ({
            ...gene,
            start: index * 1000,
            stop: index * 1000 + 800,
            coordinateMode: 'estimated_index',
        }));
    }

    const span = stop - start;
    const slot = span / genes.length;
    return genes.map((gene, index) => {
        const offsetStart = start + index * slot;
        const leftPadding = slot * 0.15;
        const rightPadding = slot * 0.15;
        const geneStart = Math.round(offsetStart + leftPadding);
        const geneStop = Math.round(start + (index + 1) * slot - rightPadding);
        return {
            ...gene,
            start: geneStart,
            stop: Math.max(geneStop, geneStart + 1),
            coordinateMode: 'estimated_band_slot',
        };
    });
}

export function withVariantCoordinateHints(genes = [], variantHints = new Map()) {
    if (!genes.length) return [];
    return genes.map((gene) => {
        const hint = variantHints.get(gene.name) || variantHints.get(gene.gene);
        if (!hint) return gene;
        const start = Number(hint.start);
        const stop = Number(hint.stop);
        const variantCount = Number(hint.count);
        if (!Number.isFinite(start) || !Number.isFinite(stop)) {
            return {
                ...gene,
                variantCount: Number.isFinite(variantCount) ? variantCount : gene.variantCount,
            };
        }
        return {
            ...gene,
            variantCount: Number.isFinite(variantCount) ? variantCount : gene.variantCount,
            estimatedStart: gene.start,
            estimatedStop: gene.stop,
            start,
            stop: Math.max(stop, start + 1),
            locusStart: start,
            locusStop: Math.max(stop, start + 1),
            coordinateMode: 'variant_span',
        };
    });
}

export function withGeneCoordinateHints(genes = [], coordinateHints = new Map()) {
    if (!genes.length) return [];
    return genes.map((gene) => {
        const hint =
            coordinateHints.get(gene.name) ||
            coordinateHints.get(gene.gene) ||
            coordinateHints.get(gene.symbol);
        if (!hint) return gene;
        const start = parseCoordinate(hint.startBp ?? hint.start_bp ?? hint.start);
        const stop = parseCoordinate(hint.endBp ?? hint.end_bp ?? hint.stop);
        if (!Number.isFinite(start) || !Number.isFinite(stop)) return gene;
        const nextStop = Math.max(stop, start + 1);
        return {
            ...gene,
            start,
            stop: nextStop,
            locusStart: start,
            locusStop: nextStop,
            coordinateMode: 'gene_coordinate',
            geneCoordinate: hint,
        };
    });
}

const VARIANT_FIELD_CANDIDATES = {
    chromosome: ['Chromosome', 'chromosome', 'Chr', 'chr', 'CHROM', '#CHROM'],
    position: ['PositionVCF', 'Position', 'position', 'POS', 'pos', 'Start', 'start', 'Location'],
    rsId: ['RS# (dbSNP)', 'rsId', 'rsid', 'RSID', 'RS', 'snp_id'],
    name: ['Name', 'name', 'HGVS', 'HGVS_c', 'HGVS_g', 'Variant', 'variant_name'],
    type: ['Type', 'type', 'VariantType', 'Variant_Type'],
    clinicalSignificance: ['ClinicalSignificance', 'clinical_significance', 'ClinSig'],
    knowledgeClinicalSignificance: [
        'KnowledgeClinicalSignificance',
        'knowledgeClinicalSignificance',
        'knowledge_clinical_significance',
    ],
    clinSigSimple: ['ClinSigSimple', 'clinsig_simple'],
    knowledgeMatched: ['KnowledgeMatched', 'knowledgeMatched', 'knowledge_matched'],
    patientOnly: ['PatientOnly', 'patientOnly', 'patient_only'],
    assembly: ['Assembly', 'assembly'],
    gene: ['GeneSymbol', 'gene_symbol', 'gene', 'Gene'],
    ref: [
        'ref',
        'ref_allele',
        'REF',
        'Ref',
        'Reference',
        'ReferenceAllele',
        'RefAllele',
        'AlleleRef',
    ],
    alt: [
        'alt',
        'alt_allele',
        'ALT',
        'Alt',
        'Alternate',
        'AlternateAllele',
        'AltAllele',
        'AlleleAlt',
    ],
    genotype: ['Genotype', 'genotype', 'GT', 'Zygosity', 'GenotypeCall'],
    transcript: [
        'Transcript',
        'TranscriptID',
        'TranscriptId',
        'Feature',
        'FeatureID',
        'RefSeq',
        'EnsemblTranscript',
    ],
    functionalRegion: [
        'FunctionalRegion',
        'FunctionRegion',
        'Region',
        'LocationType',
        'GeneRegion',
        'ExonIntron',
        'Exon/Intron',
    ],
    consequence: ['Consequence', 'Effect', 'MolecularConsequence', 'VariantConsequence'],
    proteinImpact: ['ProteinImpact', 'ProteinChange', 'AAChange', 'HGVSp', 'HGVS_p'],
    spliceImpact: ['SpliceImpact', 'SplicingImpact', 'SplicePrediction', 'SpliceAI'],
    hgvsC: ['HGVS_c', 'HGVSc', 'cDNAChange', 'CodingDNAChange'],
    hgvsP: ['HGVS_p', 'HGVSp', 'ProteinChange', 'AAChange'],
    phenotypeMatch: [
        'PhenotypeMatch',
        'Phenotype_Match',
        'ClinicalPhenotypeMatch',
        'HPO_Match',
        'PhenotypeCorrelation',
    ],
    evidenceText: ['Evidence', 'EvidenceSummary', 'Interpretation', 'ReviewStatus'],
    clinVarId: ['ClinVarID', 'ClinVarVariationID', 'VariationID', 'RCV', 'RCVaccession'],
    clinVarUrl: ['ClinVar', 'ClinVarURL', 'clinvar_url'],
    dbSnpUrl: ['dbSNP', 'dbSNPURL', 'DbSNPURL'],
    pubMed: ['PubMed', 'PubMedID', 'PMID', 'PubmedIds'],
    omim: ['OMIM', 'OMIM_ID', 'OMIMID'],
};

function pickFirstValue(source, keys) {
    if (!source || !keys?.length) return undefined;
    for (let i = 0; i < keys.length; i += 1) {
        const value = source[keys[i]];
        if (value != null && value !== '') return value;
    }
    return undefined;
}

function pickManyValues(source, keys) {
    if (!source || !keys?.length) return [];
    const values = [];
    keys.forEach((key) => {
        const value = source[key];
        if (value == null || value === '') return;
        if (Array.isArray(value)) {
            value.forEach((item) => {
                if (item != null && item !== '') values.push(item);
            });
            return;
        }
        values.push(value);
    });
    return values;
}

function splitTokens(value) {
    if (value == null || value === '') return [];
    if (Array.isArray(value)) {
        return value
            .flatMap((item) => splitTokens(item))
            .map((item) => item.trim())
            .filter(Boolean);
    }
    if (typeof value === 'number') return [String(value)];
    if (typeof value !== 'string') return [String(value)];
    return value
        .split(/[;,|]+/)
        .map((item) => item.trim())
        .filter(Boolean);
}

function asText(value) {
    if (value == null || value === '') return null;
    const text = String(value).trim();
    return text || null;
}

function toBoolean(value) {
    if (value == null || value === '') return null;
    if (typeof value === 'boolean') return value;
    if (typeof value === 'number') {
        if (value === 1) return true;
        if (value === 0) return false;
        return null;
    }
    const normalized = String(value).trim().toLowerCase();
    if (['true', '1', 'yes', 'y'].includes(normalized)) return true;
    if (['false', '0', 'no', 'n'].includes(normalized)) return false;
    return null;
}

function normalizeRsId(value) {
    const raw = asText(value);
    if (!raw || raw === '-1' || raw === '.') return null;
    if (/^rs/i.test(raw)) return `rs${raw.replace(/^rs/i, '')}`;
    if (/^\d+$/.test(raw)) return `rs${raw}`;
    return raw;
}

function normalizeAllele(value) {
    const text = asText(value);
    if (!text) return null;
    return text.toUpperCase();
}

function normalizePhenotypeMatch(value) {
    const text = asText(value);
    if (!text) return null;
    const normalized = text.toLowerCase();
    if (['yes', 'match', 'matched', 'compatible', 'consistent'].includes(normalized)) {
        return 'Matched';
    }
    if (['no', 'mismatch', 'not matched', 'incompatible', 'not consistent'].includes(normalized)) {
        return 'Not matched';
    }
    return text;
}

function looksLikeUrl(value) {
    return /^https?:\/\//i.test(String(value || '').trim());
}

function pushEvidenceLink(bucket, url, label) {
    const normalizedUrl = asText(url);
    if (!normalizedUrl || !looksLikeUrl(normalizedUrl)) return;
    const key = normalizedUrl.toLowerCase();
    if (bucket.some((item) => item.key === key)) return;
    bucket.push({
        key,
        label: label || 'Evidence',
        url: normalizedUrl,
    });
}

function buildEvidenceLinks(row, { rsId, clinVarId, pubMedIds = [], omimIds = [] } = {}) {
    const links = [];
    const clinVarUrlRaw = pickFirstValue(row, VARIANT_FIELD_CANDIDATES.clinVarUrl);
    const dbSnpUrlRaw = pickFirstValue(row, VARIANT_FIELD_CANDIDATES.dbSnpUrl);

    if (clinVarUrlRaw) {
        splitTokens(clinVarUrlRaw).forEach((url) => pushEvidenceLink(links, url, 'ClinVar'));
    }
    if (dbSnpUrlRaw) {
        splitTokens(dbSnpUrlRaw).forEach((url) => pushEvidenceLink(links, url, 'dbSNP'));
    }
    if (clinVarId && !links.some((item) => item.label === 'ClinVar')) {
        pushEvidenceLink(
            links,
            `https://www.ncbi.nlm.nih.gov/clinvar/?term=${encodeURIComponent(clinVarId)}`,
            'ClinVar',
        );
    }
    if (rsId && !links.some((item) => item.label === 'dbSNP')) {
        pushEvidenceLink(
            links,
            `https://www.ncbi.nlm.nih.gov/snp/${encodeURIComponent(rsId)}`,
            'dbSNP',
        );
    }

    const uniquePubMed = Array.from(new Set(pubMedIds)).slice(0, 3);
    uniquePubMed.forEach((pmid, index) => {
        pushEvidenceLink(
            links,
            `https://pubmed.ncbi.nlm.nih.gov/${encodeURIComponent(pmid)}/`,
            uniquePubMed.length > 1 ? `PubMed ${index + 1}` : 'PubMed',
        );
    });

    const uniqueOmim = Array.from(new Set(omimIds)).slice(0, 2);
    uniqueOmim.forEach((omimId, index) => {
        pushEvidenceLink(
            links,
            `https://omim.org/entry/${encodeURIComponent(omimId)}`,
            uniqueOmim.length > 1 ? `OMIM ${index + 1}` : 'OMIM',
        );
    });

    return links.map(({ key, ...link }) => link);
}

function toNumber(value) {
    return parseCoordinate(value);
}

export function normalizeVariantRecords(records = [], chromosomeFallback = null) {
    const rows = Array.isArray(records) ? records : [];
    const normalized = rows.map((row, index) => {
        const chromosome =
            pickFirstValue(row, VARIANT_FIELD_CANDIDATES.chromosome) || chromosomeFallback || '';
        const position = toNumber(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.position));
        const rsId = normalizeRsId(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.rsId));
        const name = pickFirstValue(row, VARIANT_FIELD_CANDIDATES.name);
        const type = pickFirstValue(row, VARIANT_FIELD_CANDIDATES.type);
        const clinicalSignificanceRaw = pickFirstValue(
            row,
            VARIANT_FIELD_CANDIDATES.clinicalSignificance,
        );
        const knowledgeClinicalSignificance = asText(
            pickFirstValue(row, VARIANT_FIELD_CANDIDATES.knowledgeClinicalSignificance),
        );
        const clinSigSimple = pickFirstValue(row, VARIANT_FIELD_CANDIDATES.clinSigSimple);
        const knowledgeMatchedRaw = toBoolean(
            pickFirstValue(row, VARIANT_FIELD_CANDIDATES.knowledgeMatched),
        );
        const patientOnlyRaw = toBoolean(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.patientOnly));
        const patientOnly =
            patientOnlyRaw != null
                ? patientOnlyRaw
                : knowledgeMatchedRaw == null
                  ? null
                  : !knowledgeMatchedRaw;
        const knowledgeMatched =
            knowledgeMatchedRaw != null
                ? knowledgeMatchedRaw
                : patientOnly == null
                  ? null
                  : !patientOnly;
        const clinicalSignificance =
            clinicalSignificanceRaw != null && clinicalSignificanceRaw !== ''
                ? clinicalSignificanceRaw
                : knowledgeClinicalSignificance;
        const assembly = pickFirstValue(row, VARIANT_FIELD_CANDIDATES.assembly);
        const gene = pickFirstValue(row, VARIANT_FIELD_CANDIDATES.gene);
        const ref = normalizeAllele(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.ref));
        const alt = normalizeAllele(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.alt));
        const genotype = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.genotype));
        const transcript = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.transcript));
        const functionalRegion = asText(
            pickFirstValue(row, VARIANT_FIELD_CANDIDATES.functionalRegion),
        );
        const consequence = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.consequence));
        const proteinImpact = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.proteinImpact));
        const spliceImpact = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.spliceImpact));
        const hgvsC = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.hgvsC));
        const hgvsP = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.hgvsP));
        const phenotypeMatch = normalizePhenotypeMatch(
            pickFirstValue(row, VARIANT_FIELD_CANDIDATES.phenotypeMatch),
        );
        const evidenceText = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.evidenceText));
        const clinVarId = asText(pickFirstValue(row, VARIANT_FIELD_CANDIDATES.clinVarId));

        const pubMedIds = pickManyValues(row, VARIANT_FIELD_CANDIDATES.pubMed)
            .flatMap((token) => splitTokens(token))
            .map((token) => token.replace(/^PMID:/i, '').trim())
            .filter((token) => /^\d+$/.test(token));

        const omimIds = pickManyValues(row, VARIANT_FIELD_CANDIDATES.omim)
            .flatMap((token) => splitTokens(token))
            .map((token) => token.replace(/^OMIM:/i, '').trim())
            .filter(Boolean);

        const evidenceLinks = buildEvidenceLinks(row, {
            rsId,
            clinVarId,
            pubMedIds,
            omimIds,
        });

        const id = rsId || name || `variant-${index + 1}`;
        return {
            id,
            rsId,
            name: name ? String(name) : null,
            chromosome: chromosome ? String(chromosome) : '',
            position: Number.isFinite(position) ? position : null,
            type: type ? String(type) : null,
            clinicalSignificance: clinicalSignificance ? String(clinicalSignificance) : null,
            knowledgeClinicalSignificance,
            clinSigSimple: clinSigSimple ? String(clinSigSimple) : null,
            knowledgeMatched,
            patientOnly,
            assembly: assembly ? String(assembly) : null,
            gene: gene ? String(gene).toUpperCase() : null,
            ref,
            alt,
            genotype,
            transcript,
            functionalRegion,
            consequence,
            proteinImpact,
            spliceImpact,
            hgvsC,
            hgvsP,
            phenotypeMatch,
            evidenceText,
            clinVarId,
            pubMedIds,
            omimIds,
            evidenceLinks,
            raw: row,
        };
    });

    return normalized.sort((a, b) => {
        if (a.position == null && b.position == null)
            return String(a.id).localeCompare(String(b.id));
        if (a.position == null) return 1;
        if (b.position == null) return -1;
        return a.position - b.position;
    });
}

export function deriveSpanFromVariants(variants = []) {
    let rows = Array.isArray(variants) ? variants : [];
    let options = {};
    if (arguments.length >= 2 && arguments[1] && typeof arguments[1] === 'object') {
        options = arguments[1];
    }

    const targetChr = normalizeChromosome(options.chromosome);
    const rangeStart = parseCoordinate(options.rangeStart);
    const rangeStop = parseCoordinate(options.rangeStop);

    if (targetChr) {
        const chrFiltered = rows.filter((item) => {
            const chr = normalizeChromosome(item?.chromosome);
            if (!chr) return true;
            return chr === targetChr;
        });
        if (chrFiltered.length) {
            rows = chrFiltered;
        }
    }

    if (Number.isFinite(rangeStart) && Number.isFinite(rangeStop) && rangeStop > rangeStart) {
        const inRange = rows.filter((item) => {
            const pos = parseCoordinate(item?.position);
            return Number.isFinite(pos) && pos >= rangeStart && pos <= rangeStop;
        });
        if (inRange.length) {
            rows = inRange;
        }
    }

    const positions = rows
        .map((item) => parseCoordinate(item?.position))
        .filter((value) => Number.isFinite(value))
        .sort((a, b) => a - b);
    if (!positions.length) return null;
    const start = positions[0];
    const stop = positions[positions.length - 1];
    if (start === stop) {
        return {
            start: Math.max(0, start - 10),
            stop: stop + 10,
            count: positions.length,
        };
    }
    return { start, stop, count: positions.length };
}

function normalizeChromosome(value) {
    if (value == null) return '';
    const text = String(value).trim().toUpperCase();
    if (!text) return '';
    return text.replace(/^CHR/, '');
}
