// Additive checks for the committed A5/A10 facts. These do not replace the
// caller's candidate/context, policy qualification or original source checks.
export { rawHash as populationRawRecordSha256, regionValid as populationRegionBindingValid };
const sha = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const text = value => typeof value === 'string' && value.length > 0;
const integer = value => Number.isSafeInteger(value) && value >= 0;
const same = (a, b) => a === b || !!a && !!b && typeof a === 'object' && typeof b === 'object' &&
    Array.isArray(a) === Array.isArray(b) && Object.keys(a).length === Object.keys(b).length &&
    Object.keys(a).every(key => Object.hasOwn(b, key) && same(a[key], b[key]));
const without = (value, key) => Object.fromEntries(Object.entries(value).filter(([name]) => name !== key));
const shown = value => value == null || value === '' ? '未记录' : typeof value === 'object' ? JSON.stringify(value) : String(value);
const REGION_PIN = '41b99cb587f84e0f9823a7994ea29573a3f6aad619ebe02d011e02b964b820aa';
const HEADER_PIN = '66875ac50f53b8f491c263394fc85e17308c639bae4b7d5daa43fea6f773ca83';
const PROFILE_PIN = 'bce4c90293c0bd5028d5d240a9bdabea9bf4118e27adf2af245f61d98c8e7a1d';
const SEX_VERSION = 'bs1-sex-stratified-frequency-v1';
const AF = 'gnomad.exomes.sex_ancestry_max_af';
const FAF = 'gnomad.exomes.sex_ancestry_max_faf95';
const JOINT_AF = 'gnomad.joint.sex_ancestry_max_af';
const JOINT_FAF = 'gnomad.joint.sex_ancestry_max_faf95';
const JOINT_VERSION = 'bs1-joint-sex-stratified-frequency-v1';
const JOINT_AC_VERSION = 'bs1-joint-sex-stratified-frequency-v2';
const JOINT_VERSIONS = [JOINT_VERSION,JOINT_AC_VERSION];
const JOINT_HEADER = '2875792c14a97bc0d69d96267f3532594d0cef1c1c9578cd7f0c43159aa0cc00';
const JOINT_PROFILE = '626320cc24bb480adb4d34c97646326c3ca358c5c68b8282eb7edf177731a0a3';
const JOINT_RESOURCE = 'c7ab8fc9194cc8474c29fbc493cb6ce05c4958264d00c9faa40a6d569bc2ef2f';
const populationContract = value => [JOINT_AF, JOINT_FAF].includes(value?.preparation?.policy?.statistic?.metricId)
    ? { dataset:'JOINT', version:Object.hasOwn(value.preparation.policy.statistic,'eligibleAc')?JOINT_AC_VERSION:JOINT_VERSION, header:JOINT_HEADER, profile:JOINT_PROFILE,
        schema:'gnomad-joint-sex-stratified-observation-v1', prefix:'gnomad-joint-sex-observation-sha256-', scope:'BS1_JOINT_SEX_V1' }
    : { dataset:'EXOMES', version:SEX_VERSION, header:HEADER_PIN, profile:PROFILE_PIN,
        schema:'gnomad-sex-stratified-observation-v1', prefix:'gnomad-sex-observation-sha256-', scope:'BS1_LOCAL_EXOMES_V1' };

// Bounded exact decimals; AF ordering and threshold checks never use Number.
function decimal(value) {
    if (typeof value !== 'string' || value.length > 80) return null;
    const match = /^([+-]?)(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d{1,3}))?$/.exec(value);
    if (!match) return null;
    const exponent = Number(match[4] || 0) - (match[3]?.length || 0);
    if (Math.abs(exponent) > 100) return null;
    const numerator = BigInt((match[1] === '-' ? '-' : '') + match[2] + (match[3] || ''));
    return exponent >= 0 ? [numerator * 10n ** BigInt(exponent), 1n] : [numerator, 10n ** BigInt(-exponent)];
}
const compare = (a, b) => a[0] * b[1] < b[0] * a[1] ? -1 : a[0] * b[1] > b[0] * a[1] ? 1 : 0;
const fraction = value => { const d = decimal(value); return d && d[0] >= 0n && d[0] <= d[1]; };
const count = value => { const d = decimal(value); return d && d[0] >= 0n && d[0] % d[1] === 0n; };
function countsAgree(ac, an, af) {
    const a = decimal(ac), n = decimal(an), f = decimal(af);
    if ((a[0] === 0n) !== (f[0] === 0n)) return false;
    const parts = af.toLowerCase().split('e'), scale = (parts[0].split('.')[1]?.length || 0) - Number(parts[1] || 0);
    const half = scale >= 0 ? [1n, 2n * 10n ** BigInt(scale)] : [10n ** BigInt(-scale), 2n];
    const view = new DataView(new ArrayBuffer(4));
    view.setFloat32(0, Number(af) + Number(half[0]) / Number(half[1]));
    const power = Math.max(1, (view.getUint32(0) >>> 23) & 255) - 127 - 24;
    const binary = power >= 0 ? [2n ** BigInt(power), 1n] : [1n, 2n ** BigInt(-power)];
    const error = [f[0] * n[0] * a[1] - a[0] * f[1] * n[1], f[1] * n[1] * a[1]];
    error[0] = error[0] < 0n ? -error[0] : error[0];
    return compare(error, [(half[0] * binary[1] + binary[0] * half[1]) * n[0], half[1] * binary[1] * n[1]]) <= 0;
}

function regionValid(region, allele) {
    const catalog = {
        'GRCh37:X': [155270560, [60001,2699520], [154931044,155260560]],
        'GRCh37:Y': [59373566, [10001,2649520], [59034050,59363566]],
        'GRCh38:X': [156040895, [10001,2781479], [155701383,156030895]],
        'GRCh38:Y': [57227415, [10001,2781479], [56887903,57217415]],
    };
    const contig = allele?.contig?.replace(/^chr/, ''), row = catalog[`${allele?.assembly}:${contig}`];
    const ref = allele?.reference, alt = allele?.alternate;
    if (!row || !integer(allele.position) || allele.position < 1 || !/^[ACGT]{1,50}$/.test(ref || '') ||
        !/^[ACGT]{1,50}$/.test(alt || '') || ref === alt || allele.position + ref.length - 1 > row[0]) return false;
    let prefix = 0, suffix = 0;
    while (prefix < Math.min(ref.length, alt.length) && ref[prefix] === alt[prefix]) prefix += 1;
    while (suffix < Math.min(ref.length, alt.length) - prefix && ref[ref.length - suffix - 1] === alt[alt.length - suffix - 1]) suffix += 1;
    const consumed = ref.length - prefix - suffix, inserted = alt.length - prefix - suffix;
    const start = allele.position + prefix - (consumed === 0 ? 1 : 0), end = consumed === 0 ? start + 1 : start + consumed - 1;
    const par = row.slice(1).findIndex(([lo, hi]) => start >= lo && end <= hi);
    const crossed = par < 0 && row.slice(1).some(([lo, hi]) => start <= hi && end >= lo);
    return start >= 1 && end <= row[0] && !crossed && region?.resolvedContig === contig &&
        region.methodVersion === '1.0.0' && region.region === (par >= 0 ? 'PAR' : 'NON_PAR') &&
        region.regionId === (par >= 0 ? `PAR${par + 1}` : 'NON_PAR') &&
        same(region.classificationSpan, { start, end, role: consumed === 0 ? 'INSERTION_FLANKS' : 'AFFECTED_REFERENCE_BASES' }) &&
        region.event?.trimmedPrefixBases === prefix && region.event.trimmedSuffixBases === suffix &&
        region.event.referenceBasesConsumed === consumed && region.event.alternateBasesInserted === inserted;
}

// Raw UTF-8 digests deliberately do not normalize source text. Canonical JSON
// normalization is applied only by identityHash below.
function rawHash(value) {
    const bytes = new TextEncoder().encode(value);
    const data = new Uint8Array(Math.ceil((bytes.length + 9) / 64) * 64);
    data.set(bytes); data[bytes.length] = 0x80;
    const view = new DataView(data.buffer); view.setUint32(data.length - 4, bytes.length * 8);
    const h = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
    const k = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
    const rotate = (v, n) => (v >>> n) | (v << (32 - n));
    for (let offset = 0; offset < data.length; offset += 64) {
        const w = new Uint32Array(64);
        for (let i = 0; i < 16; i += 1) w[i] = view.getUint32(offset + i * 4);
        for (let i = 16; i < 64; i += 1) {
            const x = w[i - 15], y = w[i - 2];
            w[i] = w[i - 16] + (rotate(x,7) ^ rotate(x,18) ^ (x >>> 3)) + w[i - 7] + (rotate(y,17) ^ rotate(y,19) ^ (y >>> 10));
        }
        let [a,b,c,d,e,f,g,t] = h;
        for (let i = 0; i < 64; i += 1) {
            const one = (t + (rotate(e,6) ^ rotate(e,11) ^ rotate(e,25)) + ((e & f) ^ (~e & g)) + k[i] + w[i]) | 0;
            const two = ((rotate(a,2) ^ rotate(a,13) ^ rotate(a,22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
            t = g; g = f; f = e; e = (d + one) | 0; d = c; c = b; b = a; a = (one + two) | 0;
        }
        [a,b,c,d,e,f,g,t].forEach((v, i) => { h[i] = (h[i] + v) | 0; });
    }
    return h.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('');
}
function identityHash(value) {
    const canonical = v => typeof v === 'string' ? v.normalize('NFC') : Array.isArray(v) ? v.map(canonical)
        : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(key => [key.normalize('NFC'), canonical(v[key])])) : v;
    return rawHash(JSON.stringify(canonical(value)));
}

export function sequenceEditSummaryValid(f, context) {
    try { return sequenceSummaryValid(f, context); } catch { return false; }
}
function sequenceSummaryValid(f, context) {
    if (f?.schemaVersion !== 'coding-transcript-edit-facts-v1' || f.methodVersion !== '1.0.0' ||
        f.scope !== 'CDS_SEQUENCE_FACTS_ONLY' || f.topologyScope !== 'CODING_EXONS_ONLY' || f.scientificQualificationGranted !== false ||
        !sha(f.alteredCdsSha256) || !integer(f.referenceCdsLength) || f.referenceCdsLength < 1 || f.referenceCdsLength > 1000000 ||
        !integer(f.alteredCdsLength) || f.alteredCdsLength > 2000000 ||
        f.netCdsLengthChange !== f.alteredCdsLength - f.referenceCdsLength ||
        !['modelSha256', 'referenceCdsSha256', 'contextSha256'].every(key => sha(f.binding?.[key])) ||
        !text(f.binding?.transcript) || !text(f.binding?.assembly) || !text(f.binding?.contig)) return false;
    if (context && (f.binding.contextSha256 !== identityHash(context) || f.binding.assembly !== context.variant?.evidenceQueryAllele?.assembly ||
        f.binding.contig !== context.variant?.evidenceQueryAllele?.contig)) return false;
    if (!Array.isArray(f.edits) || !f.edits.length || f.edits.length > 1000 || new Set(f.edits.map(e => e.id)).size !== f.edits.length) return false;
    if (!f.edits.every(e => text(e.id) && ['CODING_ALLELE', 'SPLICE_PROJECTION'].includes(e.origin) &&
        ['APPLIED', 'DUPLICATE_SEQUENCE_EDIT', 'REMOVED_BY_SPLICE'].includes(e.status) && integer(e.referenceEditStart0) &&
        integer(e.referenceEditEnd0) && e.referenceEditEnd0 >= e.referenceEditStart0 && e.referenceEditEnd0 <= f.referenceCdsLength &&
        typeof e.expectedReference === 'string' && /^[ACGT]*$/.test(e.expectedReference) &&
        e.expectedReference.length === e.referenceEditEnd0 - e.referenceEditStart0 && typeof e.insertedSequence === 'string' &&
        /^[ACGT]*$/.test(e.insertedSequence) && (e.expectedReference.length > 0 || e.insertedSequence.length > 0) &&
        sha(e.evidenceBindingSha256) && (e.alleleGroupSha256 === '' || sha(e.alleleGroupSha256)) && integer(e.insertionExonOrdinal))) return false;
    if (f.edits.length > 1 && (!sha(f.edits[0].alleleGroupSha256) || !f.edits.every(e => e.alleleGroupSha256 === f.edits[0].alleleGroupSha256))) return false;
    if (f.edits.filter(e => e.status === 'APPLIED').reduce((n,e) => n + e.insertedSequence.length - e.expectedReference.length, 0) !== f.netCdsLengthChange) return false;
    if (!Array.isArray(f.alteredToReference)) return false;
    let end = 0;
    for (const segment of f.alteredToReference) {
        if (segment.alteredStart0 !== end || !integer(segment.alteredEnd0) || segment.alteredEnd0 <= end || segment.alteredEnd0 > f.alteredCdsLength ||
            segment.referenceStart0 !== null && (!integer(segment.referenceStart0) ||
                segment.referenceStart0 + segment.alteredEnd0 - segment.alteredStart0 > f.referenceCdsLength)) return false;
        end = segment.alteredEnd0;
    }
    if (end !== f.alteredCdsLength || !Array.isArray(f.codingExons) || !Array.isArray(f.codingJunctions0)) return false;
    const expected = []; let referenceCursor = 0, alteredCursor = 0;
    const append = (length, referenceStart0) => {
        if (!length) return;
        const last = expected[expected.length - 1];
        if (last && (last.referenceStart0 === null && referenceStart0 === null || last.referenceStart0 !== null &&
            referenceStart0 === last.referenceStart0 + last.alteredEnd0 - last.alteredStart0)) last.alteredEnd0 += length;
        else expected.push({ alteredStart0: alteredCursor, alteredEnd0: alteredCursor + length, referenceStart0 });
        alteredCursor += length;
    };
    for (const edit of f.edits.filter(e => e.status === 'APPLIED').sort((a,b) => a.referenceEditStart0 - b.referenceEditStart0 || a.referenceEditEnd0 - b.referenceEditEnd0)) {
        if (edit.referenceEditStart0 < referenceCursor) return false;
        append(edit.referenceEditStart0 - referenceCursor, referenceCursor);
        append(edit.insertedSequence.length, null); referenceCursor = edit.referenceEditEnd0;
    }
    append(f.referenceCdsLength - referenceCursor, referenceCursor);
    if (!same(expected, f.alteredToReference)) return false;
    end = 0;
    for (const [index, exon] of f.codingExons.entries()) {
        if (exon.ordinal !== index + 1 || !integer(exon.referenceOrdinal) || exon.referenceOrdinal < 1 ||
            exon.alteredCdsStart0 !== end || !integer(exon.alteredCdsEnd0) || exon.alteredCdsEnd0 <= end ||
            !(exon.referenceCdsStart0 === -1 && exon.referenceCdsEnd0 === -1) &&
                (!integer(exon.referenceCdsStart0) || !integer(exon.referenceCdsEnd0) || exon.referenceCdsEnd0 <= exon.referenceCdsStart0 ||
                exon.referenceCdsEnd0 > f.referenceCdsLength)) return false;
        end = exon.alteredCdsEnd0;
    }
    return end === f.alteredCdsLength && same(f.codingJunctions0, f.codingExons.slice(0, -1).map(e => e.alteredCdsEnd0));
}

export function sequenceMaterialValid(material, context) {
    try { return materialValid(material, context); } catch { return false; }
}
function materialValid(material, context) {
    const a = material?.alleleAssociation;
    if (material?.method !== 'EXACT_TRANSCRIPT_LOCAL_MATERIAL_SUPPLY' || material.status !== 'READY' ||
        material.clinicalAcceptance !== false || material.scientificEvaluationPerformed !== false ||
        material.associationVersion !== '2.0.0' || !a || !sha(material.modelCanonicalSha256) ||
        !sha(material.verification?.fullSplicedTranscriptSha256) || material.verification.cdsSha256 !== a.referenceCdsSha256 ||
        !['referenceCdsSha256', 'alteredCdsSha256', 'referenceProteinSha256', 'alteredProteinSha256', 'storedAnnotationSha256'].every(key => sha(a[key])) ||
        a.transcript !== material.transcript || a.coordinateSystem !== 'REFERENCE_CDS_ZERO_BASED_HALF_OPEN' ||
        !integer(a.referenceEditStart0) || !integer(a.referenceEditEnd0) || a.referenceEditEnd0 < a.referenceEditStart0 ||
        typeof a.deletedSequence !== 'string' || !/^[ACGT]*$/.test(a.deletedSequence) || a.deletedSequence.length !== a.referenceEditEnd0 - a.referenceEditStart0 ||
        typeof a.insertedSequence !== 'string' || !/^[ACGT]*$/.test(a.insertedSequence) ||
        !integer(a.referenceCdsLength) || !integer(a.alteredCdsLength) || !integer(a.referenceProteinLength) || !integer(a.alteredProteinLength) ||
        a.hgvscSequenceEquivalent !== true || a.editDerivation !== 'CODING_TRANSCRIPT_EDITS_S0') return false;
    if (context && (material.contextId !== context.contextId || !same(material.queryAllele, context.variant?.evidenceQueryAllele))) return false;
    if (material.associationKind === 'VERIFIED_INFRAME_LENGTH_CHANGE') return a.branch === 'VERIFIED_INFRAME_SMALL_ALLELE' &&
        a.sequenceDerivationStatus === 'GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE' && a.netNucleotideChange !== 0 &&
        a.netNucleotideChange === a.insertedSequence.length - a.deletedSequence.length && a.netNucleotideChange % 3 === 0 &&
        a.alteredCdsLength - a.referenceCdsLength === a.netNucleotideChange && a.netAminoAcidChange === a.netNucleotideChange / 3 &&
        a.alteredProteinLength - a.referenceProteinLength === a.netAminoAcidChange;
    return material.associationKind === 'VERIFIED_STOP_LOSS_SMALL_ALLELE' && a.branch === 'VERIFIED_STOP_LOSS_SMALL_ALLELE' &&
        a.methodVersion === '2.0.0' && a.interpretation === 'REFERENCE_BASED_PREDICTED_PROTEIN_EXTENSION' &&
        a.independentRnaVerification === false && a.newTerminationResolved === true &&
        a.sequenceDerivationStatus === 'REFERENCE_CDS_THREE_PRIME_SEQUENCE_AND_STORED_ANNOTATION_AGREE' &&
        integer(a.netAminoAcidChange) && a.netAminoAcidChange > 0 && a.alteredProteinLength - a.referenceProteinLength === a.netAminoAcidChange;
}

export function sequenceSourceExecutionValid(value) {
    const d = value?.details || value || {};
    const diagnostic = d.scope === 'RETAINED_ACTUAL_VEP_DIAGNOSTIC_REPLAY';
    const claimed = diagnostic || ['sourceExecutionReceipt', 'sourceOwnerReceipt', 'sourceExecutionAccepted'].some(key => Object.hasOwn(d, key));
    if (!claimed) return true; // Old records remain governed by their original source contract.
    return !diagnostic && d.sourceExecutionAccepted === true && d.sourceExecutionReceipt?.status === 'SUCCEEDED' &&
        d.sourceOwnerReceipt?.success === true && d.sourceOwnerReceipt?.ownedExecutionReceipt?.outputReadComplete === true;
}

export function sourceExecutionDetails(value) {
    value = value?.details || value;
    const execution = value?.sourceExecutionReceipt;
    const owner = value?.sourceOwnerReceipt;
    if (!execution && !owner && !Object.hasOwn(value || {}, 'sourceExecutionAccepted') && value?.scope !== 'RETAINED_ACTUAL_VEP_DIAGNOSTIC_REPLAY') return [];
    const failed = execution?.status === 'FAILED' || owner?.success === false;
    const complete = sequenceSourceExecutionValid(value);
    const diagnostic = value?.scope === 'RETAINED_ACTUAL_VEP_DIAGNOSTIC_REPLAY' || value?.sourceExecutionAccepted === false;
    return [['来源执行', failed ? '失败；保留完整 JSON 不能证明来源执行成功' : complete ? '受控执行成功且完整输出已确认；科学资格仍须另行核定' : '尚未核实执行成功及输出完整性；不由可解析 JSON 推定成功'],
        ['执行状态与输出完整性', `${shown(execution?.status)} / ${shown(owner?.ownedExecutionReceipt?.outputReadComplete)}`],
        ['执行失败原因', shown(owner?.ownedExecutionReceipt?.cleanupTrigger || owner?.cleanupTrigger || execution?.failureMessage || execution?.status)],
        ['本次回放用途', diagnostic ? '诊断回放；未接纳来源执行，不授科学资格或正式采用' : '用途以本次保存的来源资格及评价为准'],
        ['科学资格 / 临床采用', `${shown(value?.scientificQualificationGranted)} / ${shown(value?.clinicalAdoption)}`]];
}

export function sequenceEditDetails(value) {
    const d = value?.details || value || {};
    const f = d.codingEditDerivation || d.sequenceDerivation || d.resolvedInput?.codingEditDerivation || d.pvs1Facts?.codingEditDerivation;
    const material = d.lengthMaterial || d.materialSupply || d.materialPreparation;
    const input = d.resolvedInput || d;
    const a = material?.alleleAssociation || d.alleleAssociation ||
        (['VERIFIED_INFRAME_SMALL_ALLELE', 'VERIFIED_STOP_LOSS_SMALL_ALLELE'].includes(input.branch) ? input : null);
    const rows = [...sourceExecutionDetails(value)];
    if (f) rows.push(['编码编辑核验', sequenceEditSummaryValid(f) ? '版本化编码序列事实；不授疾病或 RNA 资格' : '保存的编辑结构或绑定无效，不能据此采用'],
        ['编辑模型 / 上下文绑定', `${shown(f.binding?.modelSha256)} / ${shown(f.binding?.contextSha256)}`],
        ['参考 / 改变后 CDS 长度', `${shown(f.referenceCdsLength)} / ${shown(f.alteredCdsLength)}；净变化 ${shown(f.netCdsLengthChange)} nt`],
        ['编码编辑与来源', shown(f.edits)], ['改变后参考映射', shown(f.alteredToReference)],
        ['编码外显子与连接点', `${shown(f.codingExons)}；${shown(f.codingJunctions0)}`]);
    if (a) rows.push(['序列材料类型', `${shown(material?.associationKind)} @ ${shown(material?.associationVersion)}；${shown(a.branch)}`],
        ['序列一致性', shown(a.sequenceDerivationStatus)], ['编码 / 蛋白描述', `${shown(a.hgvsc)} / ${shown(a.hgvsp || a.predictedHgvsp)}`],
        ['净核苷酸 / 氨基酸变化', `${shown(a.netNucleotideChange)} / ${shown(a.netAminoAcidChange)}`],
        ['完整等价编辑包络', shown(a.equivalentEditEnvelope)], ['材料资格边界', '仅验证序列及蛋白改变；重复区域、功能、LoF、政策适用与来源执行资格须分别核定']);
    return rows;
}

export const populationStrataClaim = value => [AF, FAF, JOINT_AF, JOINT_FAF].includes(value?.preparation?.policy?.statistic?.metricId) ||
    [SEX_VERSION,...JOINT_VERSIONS].includes(value?.qualityDecision?.adapterVersion) || !!value?.qualityDecision?.sexStratifiedObservation;

// A saved capability set describes available methods, not rule adoption. Old
// v1 identities have no arrays; new count eligibility declares explicit sets.
function savedMethodSet(identity,single,plural,allowed){
    const values=identity?.[plural];
    if(values===undefined)return identity?.[single]===JOINT_VERSION?[JOINT_VERSION]:null;
    if(!Array.isArray(values)||!values.length||values.length>allowed.length||new Set(values).size!==values.length||
        values.some((v,i)=>!allowed.includes(v)||i>0&&values[i-1]>=v)||
        identity[single]!== (values.length===1?values[0]:'MIXED'))return null;
    return values;
}
function runtimeJointMethods(binding){
    const k=binding?.knowledgeIdentity;
    const primary=binding?.jointSexChromosomeStatisticVersion===undefined
        ? {...binding,jointSexChromosomeStatisticVersion:binding?.jointSexStatisticVersion}:binding;
    const runtime=savedMethodSet(primary,'jointSexChromosomeStatisticVersion','jointSexChromosomeStatisticVersions',JOINT_VERSIONS);
    const knowledge=savedMethodSet(k,'jointSexChromosomeStatisticVersion','jointSexChromosomeStatisticVersions',JOINT_VERSIONS);
    if(!runtime||!same(runtime,knowledge)||binding.jointSexStatisticVersion!==(runtime.length===1?runtime[0]:'MIXED'))return null;
    if(runtime.includes(JOINT_AC_VERSION)||binding.sexChromosomeStatisticVersions||k?.sexChromosomeStatisticVersions){
        const allowed=[SEX_VERSION,'bs1-sex-stratified-frequency-v2',...JOINT_VERSIONS];
        const all=savedMethodSet(binding,'sexChromosomeStatisticVersion','sexChromosomeStatisticVersions',allowed);
        const known=savedMethodSet(k,'sexChromosomeStatisticVersion','sexChromosomeStatisticVersions',allowed);
        if(!all||!same(all,known)||!runtime.every(v=>all.includes(v)))return null;
    }
    return runtime;
}

export function populationStrataResultValid(value, context = value?.context, runtimeBinding) {
    try { return populationValid(value, context) && (!runtimeBinding || populationSourceRoutingValid(value, runtimeBinding)); } catch { return false; }
}
/** Read the new execution identity separately from legacy EXOMES activation. */
export function populationRuntimeBindingValid(binding) {
    try {
        const r=binding?.sourceRouting, k=binding?.knowledgeIdentity;
        return binding?.schemaVersion === 'bs1-policy-routed-runtime-identity-v1' &&
            binding.activationContract === 'POLICY_ROUTED_POPULATION_V1' && binding.formalEnabled === true &&
            binding.contextVersion === 'evidence-context-v3' && binding.observationVersion === 'population-observation-v1' &&
            binding.evaluatorVersion === '1.0.0-bs1-policy-v2' && binding.qualityAdapterVersion === 'population-policy-source-routing-v1' &&
            ['policiesSha256','nativeSourceSha256','jointFilterSourceSha256'].every(key=>sha(binding[key])) &&
            binding.localExomesAdapterVersion === 'bs1-local-exomes-v1' && binding.exomesSexObservationVersion === 'gnomad-sex-stratified-observation-v1' &&
            binding.exomesSexHeaderSha256 === HEADER_PIN && typeof binding.nativeSupplementEnabled === 'boolean' &&
            runtimeJointMethods(binding)!==null && binding.jointSexObservationVersion === 'gnomad-joint-sex-stratified-observation-v1' &&
            binding.jointSexHeaderSha256 === JOINT_HEADER && binding.jointSexFilterSourceSha256 === '8c78bf39bbd6a1cdb11a68fd736c1c72248d3b6641329fd1bc54ed172323d66a' &&
            k?.schemaVersion === 'bs1-knowledge-identity-v1' && sha(k.rawSha256) && text(k.version) &&
            k.qualificationVersion === 'bs1-knowledge-qualification-v1' && k.sexChromosomeRegionVersion === '1.0.0' &&
            k.sexChromosomeRegionCatalogSha256 === REGION_PIN &&
            k.jointSexObservationVersion === binding.jointSexObservationVersion && k.jointSexHeaderSha256 === JOINT_HEADER &&
            k.jointSexFilterSourceSha256 === binding.jointSexFilterSourceSha256 &&
            r?.schemaVersion === 'population-policy-source-routing-v1' && r.cacheScope === 'ONE_RUNTIME_RUN' &&
            r.selectionBasis === 'VALIDATED_POLICY_DATASET_METRIC_RESOURCE' && r.componentSummation === false &&
            r.bindingSha256 === identityHash(without(r,'bindingSha256')) && Array.isArray(r.sources) && r.sources.length === 2 &&
            r.sources.every((source,index)=>source.dataset === ['EXOMES','JOINT'][index] &&
                source.profileSha256 === [PROFILE_PIN,JOINT_PROFILE][index] &&
                source.resourceSha256 === ['e4c39eef8a4f1481e1277e62fec61a00ccdb4a8bd0e3c8ca70a6da6465e87639',JOINT_RESOURCE][index] &&
                sha(source.configurationSha256) && typeof source.enabled === 'boolean' && typeof source.populationInputEnabled === 'boolean');
    } catch { return false; }
}

// Route identity is separate from a statistical verdict. An old EXOMES record
// without routing metadata stays on its original source/qualification contract.
export function populationSourceRoutingValid(value, runtimeBinding) {
    try {
        const r = value?.sourceRouting, p = value?.preparation?.policy, c = populationContract(value);
        if (!r) return c.dataset === 'EXOMES';
        const joint = c.dataset === 'JOINT', resource = r.resourceContract, effective = r.effectiveSource;
        if (r.schemaVersion !== 'population-policy-source-routing-v1' || r.status !== 'READY' || r.dataset !== c.dataset ||
            r.metricId !== p?.statistic?.metricId || r.policyId !== p?.policyId || r.policySha256 !== identityHash(p) ||
            r.profileSha256 !== c.profile || r.profileId !== (joint ? 'GNOMAD_POPULATION_INPUT_V2' : 'GNOMAD_BA1_LOCAL_EXOMES') ||
            resource?.identityFingerprintSha256 !== (joint ? JOINT_RESOURCE : 'e4c39eef8a4f1481e1277e62fec61a00ccdb4a8bd0e3c8ca70a6da6465e87639') ||
            identityHash(resource.identity) !== resource.identityFingerprintSha256 || resource.provider !== 'GNOMAD' ||
            resource.resourceId !== (joint ? 'GNOMAD_JOINT_V4_1_GRCH38_SITES' : 'GNOMAD_EXOMES_V4_1_GRCH38_FULL_SITES') ||
            resource.identity?.dataset !== c.dataset.toLowerCase() || resource.identity.release !== '4.1' || resource.assembly !== value.context?.variant?.evidenceQueryAllele?.assembly ||
            p.resourceCompatibility?.status !== 'COMPATIBLE' || p.resourceCompatibility.resourceVersion !== resource.resourceVersion ||
            p.resourceCompatibility.resourceSha256 !== resource.identityFingerprintSha256 ||
            effective?.dataset !== c.dataset || effective.profileSha256 !== c.profile ||
            effective.resourceSha256 !== resource.identityFingerprintSha256 || !sha(effective.configurationSha256) ||
            effective.enabled !== true || effective.populationInputEnabled !== true) return false;
        const plan = Object.fromEntries(Object.entries(r).filter(([key]) => !['routeId','effectiveSource','executionRouteId'].includes(key)));
        if (r.routeId !== 'population-route-sha256-' + identityHash(plan) ||
            r.executionRouteId !== 'population-execution-route-sha256-' + identityHash(without(r,'executionRouteId'))) return false;
        if (runtimeBinding && (!populationRuntimeBindingValid(runtimeBinding) ||
            joint&&!runtimeJointMethods(runtimeBinding)?.includes(c.version) ||
            !runtimeBinding.sourceRouting.sources.some(source => same(source,effective)))) return false;
        const o = value.observation, e=value.evidence;
        if (!e || !Array.isArray(e.measurements) || !['evidenceType','source','resourceExecution','resourceAvailability','queryOutcome','resourceApplicability','measurements'].every(key=>same(e[key],o?.carrier?.[key]))) return false;
        const profileVersion=joint ? '2.0.0' : '1.0.0';
        for (const [metric,expected] of [['datasetType',c.dataset],['inputProfileId',r.profileId],['inputProfileVersion',profileVersion],['inputProfileSha256',c.profile]]) {
            const matching=e.measurements.filter(m=>m?.metricId === 'gnomad.'+metric);
            if (matching.length !== 1 || matching[0].rawValue !== expected || matching[0].normalizedValue !== expected ||
                matching[0].calibrationProfileId !== r.profileId+'@'+profileVersion) return false;
        }
        return o?.profileSha256 === c.profile && same(o.resourceContract,resource) &&
            (!value.evidence?.source || value.evidence.source.sourceId === resource.resourceId &&
                value.evidence.source.resourceSha256 === resource.identityFingerprintSha256 && value.evidence.source.resourceVersion === resource.resourceVersion);
    } catch { return false; }
}

function jointSourceQualityValid(value) {
    const q=value.qualityDecision, sq=q?.sourceQualityDecision, o=value.observation, release=q?.contract?.releaseSemantics;
    const filter=sq?.filterCondition, source=filter?.sourceContract, raw=o?.rawRecord?.split('\t');
    if (q?.contract?.contractId !== 'BS1_GNOMAD41_QUALITY' || q.contract.version !== '1.1.1' ||
        release?.version !== '4.1' || release.headerSha256 !== JOINT_HEADER || release.jointFilterDefinition !== 'GNOMAD_4_1_JOINT_FILTER' ||
        release.pValueField !== 'stat_union_p_value' || release.pValueThreshold !== '0.0001' ||
        q.contract.engineering?.discordance !== 'ABSTAIN_SIGNIFICANT_UNEXPLAINED' || q.contract.officialConditions?.rejectFilteredCalls !== true ||
        sq?.schemaVersion !== 'bs1-quality-decision-v2' || sq.adapterVersion !== 'bs1-joint-filter-adapter-v1' ||
        sq.status !== 'READY' || !Array.isArray(sq.issues) || sq.issues.length || !same(sq.contract,q.contract) || sq.contractSha256 !== q.contractSha256 ||
        filter?.observedJointFilter !== 'PASS' || filter.status !== 'SATISFIED' || filter.basis !== 'JOINT_FILTER_DEFINITION' ||
        filter.decision !== 'SATISFIED_BY_JOINT_FILTER_DEFINITION' || !Array.isArray(filter.sourceEvidenceIds) || !filter.sourceEvidenceIds.includes(o?.observationId) ||
        identityHash(source) !== '8c78bf39bbd6a1cdb11a68fd736c1c72248d3b6641329fd1bc54ed172323d66a' ||
        o?.headerContract?.sha256 !== JOINT_HEADER || o.headerContract.resourceFingerprintSha256 !== JOINT_RESOURCE ||
        o.carrier?.source?.resourceSha256 !== JOINT_RESOURCE || !raw || raw[6] !== 'PASS' ||
        o.transport?.querySource !== 'remote-joint-vcf-population-input' || o.transport.sourceDataset !== 'joint' ||
        o.transport.gnomadVersion !== o.resourceContract?.resourceVersion) return false;
    const header=source.headers?.[o.queryAllele?.contig];
    if (!header || o.transport.resourceUri !== header.url || o.transport.resourceIndexUri !== header.url+'.tbi') return false;
    const info = new Map(raw[7].split(';').map(f => { const i=f.indexOf('='); return [i<0?f:f.slice(0,i),i<0?'true':f.slice(i+1)]; }));
    // Publish both raw subtype states. Joint PASS never proves both contain the
    // variant. Optional/dot states require the bound publisher definition.
    for (const subtype of ['exomes','genomes']) {
        const key=subtype+'_filters', field=info.get(key), state=sq.rawFieldStates?.[key];
        const expected=field === undefined ? 'MISSING_KEY' : field === '.' ? 'DOT' : field === '' ? 'EMPTY' : field === 'PASS' ? 'PASS' : 'FILTERED';
        if (state?.state !== expected || state.raw !== (field ?? null) || ['EMPTY','FILTERED'].includes(expected) ||
            expected === 'MISSING_KEY' && release.missingSubtype !== 'OPTIONAL_WITH_BOUND_JOINT_FILTER' ||
            expected === 'DOT' && release.subtypeDot !== 'RAW_UNKNOWN_FILTER_BY_BOUND_JOINT') return false;
        if (state.nativeStatus && !['PASS','NO_RECORD'].includes(state.nativeStatus)) return false;
        if (state.nativeStatus === 'NO_RECORD' && expected === 'PASS') return false;
    }
    const discordance=sq.discordance, selected=value.qualityDecision.sexStratifiedObservation.selectedAltIndex;
    const p=info.get(release.pValueField)?.split(',')[selected];
    return fraction(p) && decimal(release.pValueThreshold) && compare(decimal(p),decimal(release.pValueThreshold)) >= 0 &&
        discordance?.field === release.pValueField && discordance.applicability === 'APPLICABLE' && discordance.signal === false &&
        decimal(discordance.observedP) && compare(decimal(discordance.observedP),decimal(p)) === 0 &&
        discordance.signalThreshold === release.pValueThreshold && discordance.dispositionBasis === 'LOCAL_ENGINEERING_SAFEGUARD';
}

function populationQualificationValid(p, context, contract) {
    const r = p.knowledgeRecord, q = r?.qualification, review = r?.review, prior = q?.priorReview;
    if (r?.kind !== 'EXPERT_POLICY' || r.publicationStatus !== 'Released' || r.model || p.knowledgeDecision?.calculation ||
        p.knowledgeDecision?.basis !== 'EXPERT_POLICY' || p.knowledgeDecision?.reasonCode ||
        q?.schemaVersion !== 'bs1-knowledge-qualification-v1' || !same(q.scope, r.scope) ||
        q.payloadSha256 !== identityHash(without(without(r, 'review'), 'qualification')) ||
        review?.status !== 'REVIEWED_FOR_LOCAL_USE' || review.dataScopePolicyId !== contract.scope ||
        review.contentSha256 !== identityHash(without(r, 'review')) || prior?.contentSha256 !== q.payloadSha256 ||
        prior.status !== 'REVIEWED_FOR_LOCAL_USE' || prior.dataScopePolicyId !== contract.scope ||
        !['reviewer', 'reviewedAt', 'basis'].every(key => text(q[key]) && q[key] === review[key] && text(prior[key])) ||
        !['strength', 'expertSpecificationCheck'].every(key => prior[key] === review[key]) ||
        !same(p.sources, r.sources) || !same(p.statistic, r.expertPolicy?.statistic) || !same(p.scope, r.expertPolicy?.scope) ||
        !same(p.tiers, r.expertPolicy?.tiers) || !same(p.commonConditions, r.expertPolicy?.commonConditions) ||
        !same(p.exceptionSet, r.exceptionSet) || !same(r.exceptionSet, r.expertPolicy?.exceptionSet) ||
        r.exceptionSet?.status !== 'COMPLETE_IN_PINNED_SCOPE' ||
        p.scope?.hgncId !== context.gene?.hgncId || p.scope?.mondoId !== context.disease?.mondoId ||
        p.scope.modeSelection !== 'EXACT_DECLARED_MODE' || !p.scope.modes?.includes(context.moi) ||
        !same(p.scope.modes, r.scope?.modes) || !same(p.scope.sexChromosome, r.scope?.sexChromosome) ||
        !Array.isArray(r.sources) || !r.sources.length || !Array.isArray(q.sourceBindings) || !q.sourceBindings.length) return false;
    const sex=p.scope.sexChromosome, allele=context.variant?.evidenceQueryAllele;
    if (sex.regionCatalogSha256 && sex.regionCatalogSha256 !== REGION_PIN ||
        sex.assemblies && !sex.assemblies.includes(allele?.assembly) || sex.contigs && !sex.contigs.includes(allele?.contig) ||
        sex.regions && !sex.regions.includes(context.moi?.startsWith('AUTOSOMAL') ? 'PAR' : 'NON_PAR')) return false;
    const ids = new Set(r.sources.map(source => source.sourceId));
    if (ids.size !== r.sources.length || !r.sources.every(source => text(source.sourceId) && sha(source.sha256) && /^https:\/\//.test(source.url || '') && text(source.locator)) ||
        !q.sourceBindings.every(binding => ids.has(binding.sourceId) && text(binding.locator) && sha(binding.contentSha256)) ||
        new Set(q.sourceBindings.map(binding => `${binding.sourceId}|${binding.locator}`)).size !== q.sourceBindings.length ||
        !r.sources.every(source => q.sourceBindings.some(binding => binding.sourceId === source.sourceId))) return false;
    const categories = ['SOURCE_COMPLETENESS', 'PARAMETER_SCOPE', 'POPULATION_METRIC', 'ADDITIONAL_CONDITIONS', 'EXCEPTIONS'];
    if (!categories.every(category => {
        const check = q.checks?.[category];
        return check?.status === (category === 'PARAMETER_SCOPE' ? 'PUBLISHED_THRESHOLD_NOT_RECALCULATED' : 'SATISFIED') &&
            text(check.basis) && Array.isArray(check.sourceIds) && check.sourceIds.length && new Set(check.sourceIds).size === check.sourceIds.length &&
            check.sourceIds.every(id => q.sourceBindings.some(binding => binding.sourceId === id));
    })) return false;
    const bound = (id, locator) => q.sourceBindings.filter(binding => binding.sourceId === id && binding.locator === locator).length === 1;
    const location = locator => q.sourceBindings.filter(binding => binding.locator === locator).length === 1;
    return bound(p.statistic.sourceId, p.statistic.locator) &&
        (!Object.hasOwn(p.statistic,'eligibleAc')||bound(p.statistic.eligibleAc.sourceId,p.statistic.eligibleAc.locator)) &&
        bound(p.scope.sexChromosome.sourceId, p.scope.sexChromosome.sourceLocator) &&
        p.tiers.every(tier => location(tier.sourceLocator) && tier.conditions.every(condition => condition.basis !== 'OFFICIAL_CONDITION' || location(condition.sourceLocator))) &&
        p.commonConditions.every(condition => condition.basis !== 'OFFICIAL_CONDITION' || location(condition.sourceLocator));
}

function populationEvaluationValid(value, context, af, contract) {
    const p = value.preparation.policy, q = value.qualityDecision, evaluation = value.evaluation, trace = evaluation.deterministicTrace;
    if (trace.evaluatorId !== 'varvis-bs1-policy-evaluator' || trace.executionStatus !== 'COMPLETED' ||
        trace.algorithmId !== 'BS1_POLICY_TIERS' || trace.algorithmVersion !== contract.version ||
        trace.traceSha256 !== identityHash(without(trace, 'traceSha256')) || !Array.isArray(trace.parameters) || !Array.isArray(trace.steps) ||
        evaluation.appliedStrength == null || value.preparation.selectedMode !== context.moi ||
        !same(evaluation.evidenceIds, trace.inputEvidenceIds) || !same(evaluation.evidenceIds, [value.evidence?.evidenceId]) ||
        value.evidence?.contextId !== context.contextId) return false;
    for (const [key, expected] of Object.entries({ policySha256: identityHash(p), qualitySha256: identityHash(q),
        exceptionSetSha256: identityHash(p.exceptionSet), selectedMode: context.moi })) {
        const parameters = trace.parameters.filter(parameter => parameter.name === key);
        if (parameters.length !== 1 || parameters[0].value !== expected) return false;
    }
    const result = evaluation.evaluationResult;
    const conditions = [], parameters = [];
    const evaluate = condition => {
        if (!['AC', 'AN', af ? 'AF' : 'FAF95'].includes(condition.fact) || !['GT', 'GTE', 'LT', 'LTE'].includes(condition.operator) ||
            !decimal(condition.decimal) || !['OFFICIAL_CONDITION', 'LOCAL_ENGINEERING_SAFEGUARD'].includes(condition.basis) ||
            !text(condition.originalText) || !text(condition.sourceLocator) ||
            condition.unit !== (['AC', 'AN'].includes(condition.fact) ? 'alleles' : 'fraction')) return null;
        const actual = condition.fact === 'AF' ? [decimal(q.facts.AC)[0] * decimal(q.facts.AN)[1], decimal(q.facts.AC)[1] * decimal(q.facts.AN)[0]] : decimal(q.facts[condition.fact]);
        if (!actual || actual[1] <= 0n) return null;
        const order = compare(actual, decimal(condition.decimal));
        return { GT: order > 0, GTE: order >= 0, LT: order < 0, LTE: order <= 0 }[condition.operator];
    };
    for (const [index, condition] of p.commonConditions.entries()) {
        const outcome = evaluate(condition); if (outcome !== true) return false;
        conditions.push({ stepCode: `BS1_COMMON_CONDITION_${index + 1}`, outcome: 'TRUE', inputEvidenceIds: evaluation.evidenceIds });
    }
    const tiers = p.tiers.filter(tier => tier.moi === context.moi).sort((a,b) => a.priority - b.priority);
    if (!tiers.length) return false;
    let expectedStatus = 'NOT_MET', expectedStrength = 'NONE';
    for (const [index, tier] of tiers.entries()) {
        if (!text(tier.tierId) || !['STRONG', 'SUPPORTING'].includes(tier.strength) || !Array.isArray(tier.conditions) || !tier.conditions.length) return false;
        let satisfied = true;
        for (const [conditionIndex, condition] of tier.conditions.entries()) {
            const outcome = evaluate(condition);
            if (outcome == null || condition.fact === 'AN' && !outcome) return false;
            satisfied = satisfied && outcome;
            conditions.push({ stepCode: `BS1_TIER_${index + 1}_CONDITION_${conditionIndex + 1}`, outcome: outcome ? 'TRUE' : 'FALSE', inputEvidenceIds: evaluation.evidenceIds });
            parameters.push({ name: `tier.${tier.tierId}.${condition.fact}.${condition.operator}`, value: condition.decimal, unit: condition.unit });
        }
        if (satisfied) { expectedStatus = 'MET'; expectedStrength = tier.strength; break; }
    }
    const savedTierParameters = trace.parameters.filter(parameter => parameter.name?.startsWith('tier.'));
    return result.status === expectedStatus && evaluation.appliedStrength === expectedStrength && !result.reasonCode &&
        same(conditions, trace.steps) && same(parameters, savedTierParameters);
}
function populationValid(value, context) {
    const q = value?.qualityDecision, p = value?.preparation?.policy, s = p?.statistic, o = value?.observation;
    const d = q?.sexStratifiedObservation, selection = q?.frequencySelection, evaluation = value?.evaluation;
    const contract = populationContract(value), joint = contract.dataset === 'JOINT';
    const af = [AF, JOINT_AF].includes(s?.metricId);
    if (!populationStrataClaim(value) || ![AF, FAF, JOINT_AF, JOINT_FAF].includes(s?.metricId) || !context?.contextId ||
        q?.adapterVersion !== contract.version || q.status !== 'READY' || !Array.isArray(q.issues) || q.issues.length ||
        evaluation?.deterministicTrace?.evaluatorVersion !== contract.version || evaluation.contextId !== context.contextId ||
        !['MET', 'NOT_MET'].includes(evaluation.evaluationResult?.status) ||
        value?.dataScope?.policyId !== contract.scope || !joint && value.dataScope.version !== '1.0.0' ||
        value.dataScope.dataset !== contract.dataset || value.dataScope.release !== '4.1' ||
        !same(value.dataScope, q.dataScope) || !same(value.dataScope, p.dataScope) ||
        Object.hasOwn(value.preparation, 'policySha256') && value.preparation.policySha256 !== identityHash(p) ||
        (Object.hasOwn(value.evidence || {}, 'measurements') && !Array.isArray(value.evidence.measurements)) ||
        value.evidence?.measurements?.some(m => typeof m?.metricId !== 'string' || m.metricId.startsWith(`gnomad.${joint ? 'exomes' : 'joint'}.`)) ||
        p.scope?.sexChromosome?.requiredDataset !== contract.dataset ||
        (joint ? !jointSourceQualityValid(value) : q.contract?.contractId !== 'bs1-local-exomes-quality-v1' || q.contract.dataset !== 'EXOMES') ||
        q.contractSha256 !== identityHash(q.contract) || !same(q.contract, p.qualityContract) || !same(q.dataScope, p.dataScope) ||
        q.filterCondition?.status !== 'SATISFIED' || q.filterCondition.observedFilter !== 'PASS' ||
        q.filterCondition.basis !== (joint ? 'BOUND_PUBLISHER_JOINT_FILTER' : 'LOCAL_EXOMES_NATIVE_FILTER') ||
        d?.schemaVersion !== contract.schema || d.methodVersion !== '1.0.0' || d.dataset !== contract.dataset || d.release !== '4.1' ||
        d.headerSha256 !== contract.header || d.headerSource?.sha256 !== contract.header || d.profileSha256 !== contract.profile ||
        d.statisticalQualification !== 'NOT_EVALUATED' || d.criterionEvaluationEnabled !== false ||
        d.stratumMeaning !== 'SOURCE_INFERRED_GENETIC_KARYOTYPE_NOT_PATIENT_SEX' || !['OBSERVED', 'INCOMPLETE'].includes(d.status) ||
        d.observationId !== contract.prefix + identityHash(without(d, 'observationId')) ||
        o?.schemaVersion !== 'population-observation-v1' || o.observationId !== 'population-observation-sha256-' + identityHash(without(o, 'observationId')) ||
        o.rawRecordSha256 !== rawHash(o.rawRecord) || d.rawRecordSha256 !== o.rawRecordSha256 || d.parentObservationId !== o.observationId ||
        !sha(o.sourceSnapshot) || d.sourceSnapshot !== o.sourceSnapshot || o.profileSha256 !== contract.profile ||
        o.outputCompleteness !== 'VERIFIED' || o.carrier?.resourceExecution?.status !== 'SUCCEEDED' ||
        o.carrier.resourceAvailability?.status !== 'AVAILABLE' || o.carrier.queryOutcome?.status !== 'HIT' ||
        !same(o.queryAllele, context.variant?.evidenceQueryAllele) || !same(o.queryAllele, d.queryAllele) ||
        !same(o.queryAllele, o.carrier.queryAllele) || !same(o.resourceContract, d.resourceContract) ||
        d.resourceContract?.assembly !== o.queryAllele.assembly || d.region?.sourceCatalogSha256 !== REGION_PIN ||
        d.region?.clinicalQualificationGranted !== false || d.region?.patientPloidyInferred !== false ||
        !same(d.region.queryAllele, d.queryAllele) || !['PAR', 'NON_PAR'].includes(d.region.region) || d.region.status !== 'RESOLVED' ||
        d.region.queryAlleleSha256 !== identityHash(d.queryAllele) || !regionValid(d.region, d.queryAllele) ||
        selection?.version !== contract.version || !same(selection.statistic, s) || selection.sourceObservationId !== d.observationId ||
        selection.comparisonBasis !== (af ? 'EXACT_TARGET_AC_OVER_AN' : 'SOURCE_STRATUM_FAF95') ||
        s.unit !== 'fraction' || s.confidence !== (af ? 'POINT_ESTIMATE' : '95_PERCENT_LOWER_BOUND') ||
        s.sourceTerm !== (af ? 'AF' : 'FAF95') || s.interpretation !== (af ? 'TARGET_ALT_AF' : 'TARGET_ALT_FAF95') ||
        !text(s.interpretationBasis) || !count(s.eligibleAn?.decimal) || compare(decimal(s.eligibleAn.decimal), [2000n, 1n]) < 0 ||
        !['GT', 'GTE'].includes(s.eligibleAn.operator) || !Array.isArray(s.ancestries) || !s.ancestries.length ||
        (contract.version===JOINT_AC_VERSION && (!count(s.eligibleAc?.decimal)||!['GT','GTE'].includes(s.eligibleAc.operator)||
            s.eligibleAc.unit!=='alleles'||s.eligibleAc.sourceTerm!=='AC'||!text(s.eligibleAc.sourceId)||!text(s.eligibleAc.locator))) ||
        !Array.isArray(s.karyotypes) || !s.karyotypes.length || !Array.isArray(selection.populations) || !Array.isArray(d.strata) ||
        !populationQualificationValid(p, context, contract) ||
        joint && !populationSourceRoutingValid(value) || Object.hasOwn(value, 'sourceRouting') && !populationSourceRoutingValid(value)) return false;
    const columns = o.rawRecord.split('\t');
    if (columns.length < 8 || /[\r\n]/.test(o.rawRecord) || columns[6] !== 'PASS' || d.recordFilter !== 'PASS' ||
        columns[0].replace(/^chr/, '') !== o.queryAllele.contig.replace(/^chr/, '') || columns[1] !== String(o.queryAllele.position) || columns[3] !== o.queryAllele.reference) return false;
    const alts = columns[4].split(','), selected = alts.indexOf(o.queryAllele.alternate);
    if (selected < 0 || alts.lastIndexOf(o.queryAllele.alternate) !== selected || d.selectedAltIndex !== selected || d.recordAltCount !== alts.length) return false;
    const info = new Map();
    for (const field of columns[7].split(';')) { const i = field.indexOf('='); const key = i < 0 ? field : field.slice(0, i); if (info.has(key)) return false; info.set(key, i < 0 ? 'true' : field.slice(i + 1)); }
    const combinations = [...new Set(s.ancestries)].sort().flatMap(ancestry => [...new Set(s.karyotypes)].sort().map(karyotype => [ancestry, karyotype]));
    if (combinations.length !== s.ancestries.length * s.karyotypes.length || selection.populations.length !== combinations.length) return false;
    let best;
    for (const [index, [ancestry, karyotype]] of combinations.entries()) {
        const row = selection.populations[index], source = d.strata.filter(r => r.ancestry === ancestry && r.karyotype === karyotype);
        if (row?.ancestry !== ancestry || row.karyotype !== karyotype || source.length !== 1 || !same(row.sourceStratum, source[0]) ||
            row.sourceStratum.denominatorUnit !== 'ALLELES' || row.sourceStratum.sampleCount !== 'NOT_SUPPLIED' ||
            row.sourceStratum.individualPloidy !== 'NOT_ESTABLISHED') return false;
        const m = source[0].measurements;
        const raw = statistic => {
            const field = m?.[statistic], id = statistic + (joint ? '_joint' : '') + (ancestry === 'ALL' ? '' : '_' + ancestry) + (karyotype === 'ALL' ? '' : '_' + karyotype);
            if (field?.state !== 'PRESENT' || field.infoId !== id || field.rawFieldValue !== info.get(id) ||
                field.rawValue !== info.get(id)?.split(',')[statistic === 'AN' ? 0 : selected] ||
                field.unit !== (['AC', 'AN'].includes(statistic) ? 'alleles' : 'fraction')) return null;
            return field.rawValue;
        };
        const an = raw('AN'); if (!count(an)) return false;
        const support = compare(decimal(an), decimal(s.eligibleAn.decimal));
        if (support < 0 || support === 0 && s.eligibleAn.operator === 'GT') { if (row.status !== 'EXCLUDED' || row.reasonCode !== 'BS1_SEX_AN_INSUFFICIENT') return false; continue; }
        const ac = raw('AC');
        if(!count(ac)||compare(decimal(ac),decimal(an))>0)return false;
        if(contract.version===JOINT_AC_VERSION){
            const acSupport=compare(decimal(ac),decimal(s.eligibleAc.decimal));
            if(acSupport<0||acSupport===0&&s.eligibleAc.operator==='GT'){
                if(row.status!=='EXCLUDED'||row.reasonCode!=='BS1_SEX_AC_INSUFFICIENT')return false;
                continue;
            }
        }
        const observedAf = raw('AF'), faf = af ? null : raw('faf95');
        if (row.status !== 'ELIGIBLE' || !count(ac) || !fraction(observedAf) || compare(decimal(ac), decimal(an)) > 0 || !countsAgree(ac, an, observedAf) ||
            !af && (!fraction(faf) || compare(decimal(faf), decimal(observedAf)) > 0 || m.faf95.confidence !== '95_PERCENT_LOWER_BOUND' || m.faf95.applicability !== 'APPLICABLE')) return false;
        const ratio = [decimal(ac)[0] * decimal(an)[1], decimal(ac)[1] * decimal(an)[0]];
        if (ratio[1] <= 0n) return false;
        const candidate = { ancestry, karyotype, ac, an, observedAf, faf, metric: af ? ratio : decimal(faf) };
        if (!best || compare(candidate.metric, best.metric) > 0) best = candidate;
    }
    if (!best || selection.selectedAncestry !== best.ancestry || selection.selectedKaryotype !== best.karyotype ||
        q.facts?.ancestry !== best.ancestry || q.facts?.karyotype !== best.karyotype ||
        !['AC', 'AN', 'AF'].every((key, index) => decimal(q.facts[key]) && compare(decimal(q.facts[key]), decimal([best.ac, best.an, best.observedAf][index])) === 0) ||
        !af && (!decimal(q.facts.FAF95) || compare(decimal(q.facts.FAF95), decimal(best.faf)) !== 0)) return false;
    return populationEvaluationValid(value, context, af, contract);
}

export function populationRoutingDetails(value) {
    const r=value?.sourceRouting, sq=value?.qualityDecision?.sourceQualityDecision;
    if (!r) return [];
    return [['政策选择的来源', `${shown(r.dataset)}；${shown(r.metricId)}；${shown(r.status)}；${shown(r.reasonCode)}`],
        ['路由政策与输入配置', `${shown(r.policyId)}；政策 SHA256 ${shown(r.policySha256)}；${shown(r.profileId)} / ${shown(r.profileSha256)}`],
        ['实际来源身份', `${shown(r.resourceContract?.resourceId)} / ${shown(r.resourceContract?.resourceVersion)}；SHA256 ${shown(r.resourceContract?.identityFingerprintSha256)}；配置 SHA256 ${shown(r.effectiveSource?.configurationSha256)}`],
        ['实际来源开关（保存声明）', `enabled=${shown(r.effectiveSource?.enabled)}；populationInputEnabled=${shown(r.effectiveSource?.populationInputEnabled)}；仅展示保存值，不改变当前开关`],
        ['来源执行与查询', `${shown(value?.observation?.carrier?.resourceExecution?.status)} / ${shown(value?.observation?.carrier?.queryOutcome?.status)}；${shown(value?.evaluation?.evaluationResult?.reasonCode)}`],
        ['事实复用与独立评价', `本运行复用 ${shown(value?.observationReusedWithinRun)}；从BA1复用 ${shown(value?.observationReusedFromBa1)}；相同来源原始事实可复用，各背景阈值及结论仍独立，不能相加为多份独立来源。`],
        ...(r.dataset === 'JOINT' ? [['JOINT原发布与质量边界', '采用发布者原生JOINT字段，不本地累加EXOMES和GENOMES计数；JOINT PASS不证明两个子库均包含该变异。'],
            ['子库原始质量', `${shown(sq?.rawFieldStates)}；实际核验 ${shown(sq?.filterCondition?.status)}；${shown(sq?.filterCondition?.decision)}`],
            ['分歧核查', `${shown(sq?.discordance)}；质量问题不能解释成零频率或未满足。`]] : [])];
}

export function populationStrataDetails(value) {
    if (!populationStrataClaim(value)) return [];
    const q = value?.qualityDecision, d = q?.sexStratifiedObservation, s = value?.preparation?.policy?.statistic;
    const selection = q?.frequencySelection;
    return [...populationRoutingDetails(value), ['性染色体统计范围', `${shown(d?.dataset || value?.dataScope?.dataset || value?.sourceRouting?.dataset)} ${shown(d?.release || value?.dataScope?.release)}；祖源 × 来源遗传核型，不能改称患者性别或患者倍性`],
        ['区域与原始事件', `${shown(d?.region?.region)} / ${shown(d?.region?.status)}；${shown(d?.region?.classificationSpan)}`],
        ['区域原件 / 版本', `${shown(d?.region?.sourceCatalogSha256)} / ${shown(d?.region?.methodVersion)}`],
        ['统计口径', `${shown(s?.metricId)}；${shown(s?.confidence)}；${shown(s?.interpretationBasis)}`],
        ['所选祖源 × 核型', `${shown(selection?.selectedAncestry)} × ${shown(selection?.selectedKaryotype)}`],
        ['同分层 AC / AN / 普通 AF', `${shown(q?.facts?.AC)} / ${shown(q?.facts?.AN)} / ${shown(q?.facts?.AF)}`],
        ['同分层 FAF95', [AF,JOINT_AF].includes(s?.metricId) ? '普通 AF 路径不借用 FAF95 作判定' : shown(q?.facts?.FAF95)],
        ['比较依据', selection?.comparisonBasis === 'EXACT_TARGET_AC_OVER_AN' ? '精确目标 AC / AN；不使用已舍入 AF 或 FAF95 替代' : shown(selection?.comparisonBasis)],
        ['分层选择与排除', shown(Array.isArray(selection?.populations) ? selection.populations.map(row => ({ ancestry: row?.ancestry, karyotype: row?.karyotype, status: row?.status, reasonCode: row?.reasonCode })) : selection?.populations)],
        ['质量与缺项', `${shown(q?.status)}；${shown(q?.issues)}`],
        ['原始观测 / 派生观测', `${shown(d?.parentObservationId)} / ${shown(d?.observationId)}`],
        ['原记录 / header 摘要', `${shown(d?.rawRecordSha256)} / ${shown(d?.headerSha256)}`],
        ['中立事实与采用', `${shown(d?.statisticalQualification)}；中立观测本身不授证。BS1、BA1、PM2复用事实时仍须分别满足各自政策，不叠加为独立来源。`]];
}
