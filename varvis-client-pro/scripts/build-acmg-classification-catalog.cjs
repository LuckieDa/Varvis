// Compile public CSpec combination rules from the existing, byte-pinned inventory.
// No network, credentials, patient data, BA1 thresholds or natural-language inference.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const inheritance = {
    'Autosomal dominant inheritance':'AUTOSOMAL_DOMINANT', 'Autosomal Dominant':'AUTOSOMAL_DOMINANT',
    'Autosomal recessive inheritance':'AUTOSOMAL_RECESSIVE', 'Autosomal Recessive':'AUTOSOMAL_RECESSIVE',
    'X-linked inheritance':'X_LINKED', 'X-linked dominant inheritance':'X_LINKED_DOMINANT',
    'X-linked recessive inheritance':'X_LINKED_RECESSIVE', 'Y-linked inheritance':'Y_LINKED',
};

function compile(snapshotBytes, sourceDirectory, expectedSnapshotSha256) {
    assert.match(expectedSnapshotSha256 || '', /^[a-f0-9]{64}$/);
    assert.equal(sha(snapshotBytes), expectedSnapshotSha256);
    const snapshot = JSON.parse(snapshotBytes), meta = snapshot.metadata;
    assert.equal(meta.status, 'COMPLETE');
    assert.equal(meta.publicationScope, 'Released');
    assert.equal(meta.declaredEntityCount, meta.enumeratedEntityCount);
    assert.equal(snapshot.entries.length, meta.entryCount);
    const files = new Map(), documents = new Map();
    for (const entry of snapshot.entries) {
        const source = entry.source, key = `${entry.specificationId}@${entry.version}`;
        assert.match(source.rawRef, /^(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_.-]+\.json$/);
        let bytes = files.get(source.rawRef);
        if (!bytes) { bytes = fs.readFileSync(path.join(sourceDirectory, source.rawRef)); files.set(source.rawRef, bytes); }
        assert.equal(sha(bytes), source.rawSha256);
        assert.match(source.jsonPointer, /^\/data(?:\/[0-9]+)?$/);
        const original = source.jsonPointer.split('/').slice(1).reduce((value, token) => value[token], JSON.parse(bytes));
        assert.equal(original.entId, entry.specificationId);
        assert.equal(original.entContent.version, entry.version);
        assert.deepEqual(original.entContent.states.filter(s => s.current === true).map(s => s.name), ['Released']);
        if (documents.has(key)) { assert.deepEqual(documents.get(key).original, original); continue; }
        documents.set(key, { original, source });
    }
    assert.equal(documents.size, meta.selectedReleasedCount);
    const specifications = [];
    for (const [key, { original, source }] of [...documents].sort(([a], [b]) => a.localeCompare(b))) {
        const genes = new Map((original.ld.Gene || []).map(g => [g.entContent.HGNC?.symbol, g.entContent.HGNC?.hgnc_id]));
        for (const [index, ruleSet] of (original.ld.RuleSet || []).entries()) {
            const content = ruleSet.entContent, scopes = [];
            for (const gene of content.genes || []) for (const disease of gene.diseases || []) {
                const modes = [...new Set([disease.preferredModeOfInheritance,
                    ...(disease.modesOfInheritance || []).map(m => m.modeOfInheritance)].filter(Boolean))];
                scopes.push({ gene:gene.gene, hgncId:genes.get(gene.gene) || null,
                    disease:disease.preferredMondoId || null,
                    inheritance:modes.map(m => inheritance[m.trim()] || `UNRESOLVED:${m}`),
                    preferredTranscript:gene.preferredTranscript || null });
            }
            specifications.push({ id:`${key}/RuleSet/${index}`, specificationId:original.entId, version:original.entContent.version,
                title:original.entContent.title || original.entContent.shortTitle, geneType:content.geneType,
                url:`https://cspec.genome.network/cspec/ui/svi/doc/${original.entId}?version=${encodeURIComponent(original.entContent.version)}`,
                source:{sha256:source.rawSha256, pointer:`${source.jsonPointer}/ld/RuleSet/${index}`, revision:original.rev},
                scopes, rules:content.rules || {},
                // Preserve applicable code/strength pairs independently of original ACMG defaults.
                criteria:(original.ld.CriteriaCode || []).map(c => ({ code:c.entContent.label,
                    applicable:c.entContent.applicability === 'Applicable',
                    strengths:(c.entContent.strengthDescriptor || []).filter(s => s.applicability?.toLowerCase() === 'applicable' &&
                        (!s.status || s.status === 'approved')).map(s => s.strength.toUpperCase().replaceAll(' ', '_')),
                    points:Object.fromEntries((c.entContent.strengthDescriptor || []).filter(s => s.applicability?.toLowerCase() === 'applicable' &&
                        (!s.status || s.status === 'approved') && Number.isSafeInteger(s.defaultPoint))
                        .map(s => [s.strength.toUpperCase().replaceAll(' ', '_'),s.defaultPoint])),
                })),
            });
        }
    }
    const payload = { schemaVersion:'acmg-classification-catalog-v1',
        source:{snapshotId:meta.snapshotId, snapshotSha256:sha(snapshotBytes), capturedAt:meta.generatedAt,
            url:meta.sourceBase, documents:documents.size, scope:'PINNED_RELEASED_CSPEC_INVENTORY'}, specifications };
    return { ...payload, sha256:sha(JSON.stringify(payload)) };
}

module.exports = { compile };
if (require.main === module) {
    const [snapshotPath, sourceDirectory, output, expectedSha256] = process.argv.slice(2);
    if (!expectedSha256) throw Error('Supply the existing inventory, captured source directory, output JSON path and trusted inventory SHA-256');
    const catalog = compile(fs.readFileSync(snapshotPath), sourceDirectory, expectedSha256);
    fs.writeFileSync(output, JSON.stringify(catalog) + '\n');
    console.log(JSON.stringify({documents:catalog.source.documents, ruleSets:catalog.specifications.length, sha256:catalog.sha256}));
}
