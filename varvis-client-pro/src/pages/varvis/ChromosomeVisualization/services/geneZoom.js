import {
    fetchCaseSmallVariantsByGene,
    fetchCaseStructuralVariantsByGene,
    fetchSmallVariantsByGene,
    fetchStructuralVariantsByGene,
} from './cytobands';

function normalizeSmallVariant(row) {
    if (!row) return row;
    const variantType = row.variant_type ?? row.variantType ?? row.type;
    return {
        ...row,
        type: variantType,
        VariantType: variantType,
    };
}

function normalizeStructuralVariant(row) {
    if (!row) return row;
    const start = row.start_bp ?? row.startBp ?? row.start ?? row.position ?? null;
    const end = row.end_bp ?? row.endBp ?? row.end ?? null;
    const variantType = row.sv_type ?? row.svType ?? row.variantType ?? row.type ?? 'SV';
    return {
        ...row,
        position: start,
        start,
        end,
        type: variantType,
        VariantType: variantType,
    };
}

export async function fetchVariantsByGene(geneSymbol, options = {}) {
    if (!geneSymbol) return [];
    const caseId = options?.caseId || null;
    const chromosome = options?.chromosome || null;
    const start = options?.start ?? null;
    const end = options?.end ?? null;
    const pageSize = Number(options?.pageSize) > 0 ? Number(options.pageSize) : 120;

    try {
        const smallFetcher = caseId
            ? fetchCaseSmallVariantsByGene({
                  caseId,
                  geneSymbol,
                  chromosome,
                  start,
                  end,
                  page: 1,
                  pageSize,
              })
            : fetchSmallVariantsByGene(geneSymbol, 1, pageSize);
        const structuralFetcher = caseId
            ? fetchCaseStructuralVariantsByGene({
                  caseId,
                  geneSymbol,
                  chromosome,
                  start,
                  end,
                  page: 1,
                  pageSize,
              })
            : fetchStructuralVariantsByGene(geneSymbol, 1, pageSize);

        const smallResult = await smallFetcher.catch(() => []);
        const structuralResult = await structuralFetcher.catch(() => []);
        const smallRows = Array.isArray(smallResult) ? smallResult.map(normalizeSmallVariant) : [];
        const structuralRows = Array.isArray(structuralResult)
            ? structuralResult.map(normalizeStructuralVariant)
            : [];
        return [...smallRows, ...structuralRows];
    } catch (error) {
        console.warn('[geneZoom] failed to fetch variants', error);
        return [];
    }
}
