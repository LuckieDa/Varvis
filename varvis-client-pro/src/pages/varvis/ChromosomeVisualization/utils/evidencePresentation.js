import { validCoreMaterial } from './deNovoCoreMaterial';

/** Presentation only: share identical, validated material arrays across PS2/PM6.
 * Different case versions, ledgers, selection matches or fields stay separate.
 */
export function sharedDeNovoMaterials(cards) {
    const groups = new Map();
    for (const card of cards) {
        if (!['PS2', 'PM6'].includes(card.code)) continue;
        for (const entry of card.entries) {
            const materials = entry.coreMaterials;
            if (entry.displayIssue || !Array.isArray(materials) || !materials.length ||
                materials.length > 256 || !materials.every(validCoreMaterial)) continue;
            const key = JSON.stringify(materials);
            if (!groups.has(key)) groups.set(key, { materials, codes: new Set(), entries: [] });
            const group = groups.get(key);
            group.codes.add(card.code);
            group.entries.push(entry);
        }
    }
    return [...groups.values()].filter(g => g.codes.size > 1);
}

export const displayStrength = value => ({
    VERY_STRONG: '非常强', STRONG: '强', MODERATE: '中等', SUPPORTING: '支持',
    STAND_ALONE: '独立', NONE: '无',
})[value] || value || '未记录';

// Keep meaningful zero/false; omit only empty placeholders from the visible fields.
export const hasDisplayValue = value => value !== null && value !== undefined &&
    (typeof value !== 'string' || value.trim().length > 0) &&
    (Array.isArray(value) ? value.length > 0 : typeof value !== 'object' || Object.keys(value).length > 0);
