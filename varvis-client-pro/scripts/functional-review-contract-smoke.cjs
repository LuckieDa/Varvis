/* Isolated component/hook contract harness. No browser, React DOM or real HTTP acceptance. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { transformSync } = require('esbuild');

const source = process.env.VARVIS_FUNCTIONAL_REVIEW_SOURCE || path.join(__dirname,
    '../src/pages/varvis/ChromosomeVisualization/components/FunctionalExperimentReview.jsx');
let host;
let pending;
const downloads = [];
const blobs = [];
const react = {
    createElement: (type, props, ...children) => ({ type, key: props?.key, props: { ...props, children } }),
    useState(initial) {
        const owner = host; const i = owner.cursor++;
        if (!(i in owner.slots)) owner.slots[i] = typeof initial === 'function' ? initial() : initial;
        return [owner.slots[i], (value) => {
            owner.slots[i] = typeof value === 'function' ? value(owner.slots[i]) : value; owner.dirty = true;
        }];
    },
    useRef(initial) {
        const owner = host; const i = owner.cursor++;
        if (!(i in owner.slots)) owner.slots[i] = { current: initial };
        return owner.slots[i];
    },
    useEffect(effect, deps) {
        const owner = host; const i = owner.cursor++; const previous = owner.slots[i];
        if (!previous || !deps || deps.some((d, n) => !Object.is(d, previous.deps[n]))) {
            owner.effects.push(() => { previous?.cleanup?.(); owner.slots[i] = { deps, cleanup: effect() }; });
        }
    },
};
const moduleValue = { exports: {} };
vm.runInNewContext(transformSync(fs.readFileSync(source, 'utf8'), { loader: 'jsx', format: 'cjs' }).code, {
    module: moduleValue, exports: moduleValue.exports,
    require(name) {
        if (name === 'react') return react;
        if (name === '@mui/material') return new Proxy({}, { get: (_, key) => key });
        if (name.endsWith('/services/variantAnalysis')) return { functionalReviewRequest: (...args) => {
            let resolve; let reject;
            const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
            pending.push({ args, resolve, reject }); return promise;
        } };
        throw new Error(name);
    },
    Blob, URL: { createObjectURL: (blob) => { blobs.push(blob); return 'blob:fixture'; }, revokeObjectURL: () => {} },
    document: { createElement: () => ({ click() { downloads.push(this.download); } }) },
    setTimeout: (callback) => callback(),
});
const Component = moduleValue.exports.default;
class Harness {
    constructor(props) { this.slots = []; this.props = props; this.flush(); }
    flush() {
        let loops = 0;
        do {
            assert.ok(++loops < 30, 'Component render loop');
            this.dirty = false; this.cursor = 0; this.effects = []; host = this;
            const element = Component(this.props);
            if (element && typeof element.type === 'function') {
                if (this.key !== element.key) { this.dispose(); this.slots = []; this.key = element.key; }
                this.tree = element.type(element.props);
            } else this.tree = element;
            this.effects.forEach((effect) => effect());
        } while (this.dirty);
    }
    dispose() { this.slots.forEach((slot) => slot?.cleanup?.()); }
    switch(props) { this.props = props; this.flush(); }
    elements(node = this.tree) {
        if (!node || typeof node !== 'object') return [];
        if (Array.isArray(node)) return node.flatMap((child) => this.elements(child));
        return [node, ...this.elements(node.props?.children)];
    }
    text(node = this.tree) {
        if (node === null || node === undefined || typeof node === 'boolean') return '';
        if (typeof node !== 'object') return String(node);
        return Array.isArray(node) ? node.map((c) => this.text(c)).join(' ') : this.text(node.props?.children);
    }
    button(label) { return this.elements().find((e) => e.type === 'Button' && this.text(e).includes(label)); }
    field(label) { return this.elements().find((e) => e.type === 'TextField' && e.props.label === label); }
    change(label, value) { this.field(label).props.onChange({ target: { value } }); this.flush(); }
    async settle() { for (let i = 0; i < 6; i++) { await Promise.resolve(); this.flush(); } }
}
const props = (subject = 'evaluation-p', runId = 'run-a') => ({ run: { analysisId: 'analysis-a', runId }, value: {
    profileSha256: 'profile-current', atomicEvidence: [{ source: { resourceSha256: 'facts-current' } }],
    evaluation: { evaluationId: subject, evaluationResult: { status: 'MET' }, criterionApplicability: { status: 'APPLICABLE' }, conflict: { status: 'NONE' } },
} });
const response = (p, decision) => ({ ...p.run, currentProfileSha256: 'profile-current', records: decision ? [{
    ...p.run, reviewRecord: { subjectId: p.value.evaluation.evaluationId, reviewRecordId: `review-${decision}`,
        decision, actor: { actorId: 'reviewer-synthetic-fixture' }, rationaleSummary: 'Fixture only', eventType: decision === 'NONE' ? 'STARTED' : 'DECIDED' },
}] : [] });
function mount(p = props()) { pending = []; return new Harness(p); }
async function ready(h, decision = 'REJECTED') { pending.shift().resolve(response(h.props, decision)); await h.settle(); }
function fill(h, decision = 'ACCEPTED') {
    h.change('审阅者标识', 'reviewer-synthetic-functional-page'); h.change('审阅角色', 'SYSTEM_ADMINISTRATOR');
    h.change('本次决定', decision); h.change('采用、拒绝或待补理由', 'Synthetic component fixture only.');
}
let count = 0;
async function test(name, action) { await action(); count++; console.log('PASS', name); }
(async () => {
    await test('loading/failure/retry distinguish unknown state from empty reviewed history', async () => {
        const h = mount(); assert.match(h.text(), /正在读取/); assert.doesNotMatch(h.text(), /尚无/);
        pending.shift().reject(new Error('simulated GET failure')); await h.settle();
        assert.match(h.text(), /尚未核实/); assert.doesNotMatch(h.text(), /尚无/);
        h.button('重新读取').props.onClick(); h.flush(); await ready(h, null);
        assert.match(h.text(), /尚无人工采用决定/); assert.match(h.text(), /系统请求审阅不代表/);
        assert.equal(h.button('保存本次').props.disabled, true); h.button('保存本次').props.onClick();
        assert.equal(pending.length, 0); h.dispose();
    });
    await test('restored rejected history is separate from blank draft and explicit pending decision', async () => {
        const h = mount(); await ready(h); assert.match(h.text(), /已保存记录：已拒绝/);
        assert.equal(h.field('审阅者标识').props.value, ''); assert.equal(h.field('本次决定').props.value, 'NONE');
        h.dispose(); const pendingState = mount(); await ready(pendingState, 'NONE');
        assert.match(pendingState.text(), /已保存记录：待补资料/); pendingState.dispose();
    });
    await test('late GET cannot replace another candidate and draft resets on run change', async () => {
        const h = mount(); const old = pending.shift();
        h.switch(props('evaluation-q', 'run-b')); await ready(h, null);
        old.resolve(response(props(), 'ACCEPTED')); await h.settle(); assert.doesNotMatch(h.text(), /已保存记录：已采用/);
        fill(h); h.switch(props('evaluation-q', 'run-c')); assert.equal(h.field('审阅者标识').props.value, '');
        assert.equal(h.field('本次决定').props.value, 'NONE'); assert.match(h.text(), /正在读取/); h.dispose();
    });
    await test('late submit response after subject switch cannot fetch or publish old state', async () => {
        const h = mount(); await ready(h); fill(h); h.button('保存本次').props.onClick(); h.flush();
        const oldPost = pending.shift(); assert.equal(oldPost.args[2], 'post');
        h.switch(props('evaluation-q')); await ready(h, null);
        oldPost.resolve({}); await h.settle(); assert.equal(pending.length, 0); assert.doesNotMatch(h.text(), /已保存并重新读取/); h.dispose();
    });
    await test('submit conflict requires read retry without implying nothing was saved', async () => {
        const h = mount(); await ready(h); fill(h); h.button('保存本次').props.onClick();
        pending.shift().reject(new Error('409 fixture')); await h.settle();
        assert.match(h.text(), /保存结果未确认/); assert.match(h.text(), /尚未核实/);
        assert.equal(h.button('保存本次').props.disabled, true);
        h.button('重新读取').props.onClick(); h.flush(); await ready(h); assert.match(h.text(), /已拒绝/); h.dispose();
    });
    await test('POST success with GET failure is reported honestly and no duplicate POST is sent', async () => {
        const h = mount(); await ready(h); fill(h); const click = h.button('保存本次').props.onClick;
        click(); click(); h.flush(); assert.equal(pending.length, 1); assert.equal(h.field('本次决定').props.disabled, true);
        pending.shift().resolve({}); await h.settle(); pending.shift().reject(new Error('GET failed after POST')); await h.settle();
        assert.match(h.text(), /已收到保存响应/); assert.doesNotMatch(h.text(), /本次采用记录已保存并重新读取/);
        h.button('重新读取').props.onClick(); h.flush(); await ready(h, 'ACCEPTED'); assert.match(h.text(), /已采用/); h.dispose();
    });
    await test('MET/conflict and HOLD cannot use ACCEPTED even through a forced handler', async () => {
        for (const field of ['evaluationResult', 'conflict']) {
            const p = props(); p.value.evaluation[field].status = field === 'conflict' ? 'PRESENT' : 'NOT_EVALUATED';
            const h = mount(p); await ready(h); fill(h);
            assert.equal(h.button('保存本次').props.disabled, true); h.button('保存本次').props.onClick();
            assert.equal(pending.length, 0); h.dispose();
        }
    });
    await test('old profile remains readable/exportable and cannot receive a new adoption', async () => {
        const p = props(); p.value.profileSha256 = 'profile-old'; const h = mount(p); await ready(h);
        assert.match(h.text(), /旧资料\/政策/); fill(h); h.button('保存本次').props.onClick(); assert.equal(pending.length, 0);
        h.button('下载实验').props.onClick(); const request = pending.shift(); assert.equal(request.args[4], '/export');
        request.resolve({ ...response(p, 'REJECTED'), calculation: p.run }); await h.settle();
        assert.equal(downloads.at(-1), 'functional-review.json'); assert.deepEqual(JSON.parse(await blobs.at(-1).text()).calculation, p.run); h.dispose();
    });
    await test('cross-run response is rejected instead of showing an empty or accepted state', async () => {
        const h = mount(); pending.shift().resolve(response(props('evaluation-p','other-run'),'ACCEPTED')); await h.settle();
        assert.match(h.text(), /尚未核实/); assert.doesNotMatch(h.text(), /已保存记录：/); h.dispose();
    });
    await test('successful explicit save uses real predecessor and shows confirmed saved state', async () => {
        const h = mount(); await ready(h); fill(h, 'NONE'); h.button('保存本次').props.onClick();
        const request = pending.shift(); assert.equal(request.args[3].previousReviewRecordId, 'review-REJECTED');
        assert.equal(request.args[3].decision, 'NONE'); request.resolve({}); await h.settle(); await ready(h,'NONE');
        assert.match(h.text(), /本次采用记录已保存并重新读取/); assert.match(h.text(), /待补资料/);
        h.change('采用、拒绝或待补理由', 'Unsaved new draft'); assert.doesNotMatch(h.text(), /本次采用记录已保存并重新读取/); h.dispose();
    });
    await test('joint execution identity does not obsolete an unchanged current functional science profile', async () => {
        const p = props();
        p.run.executionProfile = { profileId: 'synthetic-joint-successor', profileSha256: 'joint-successor-hash' };
        p.run.enabledCriteria = ['ACMG_AMP_2015:BS1', 'ACMG_AMP_2015:PS3'];
        const original = JSON.stringify(p);
        const h = mount(p); await ready(h, null); fill(h);
        assert.doesNotMatch(h.text(), /旧资料\/政策/);
        assert.equal(h.button('保存本次').props.disabled, false);
        h.button('保存本次').props.onClick();
        const request = pending.shift();
        assert.equal(request.args[3].profileSha256, 'profile-current');
        assert.equal(request.args[3].factPackageSha256, 'facts-current');
        assert.equal(request.args[3].decision, 'ACCEPTED');
        request.resolve({}); await h.settle(); await ready(h, 'ACCEPTED');
        assert.match(h.text(), /已保存记录：已采用/);
        assert.equal(JSON.stringify(p), original); h.dispose();
    });
    if (process.env.VARVIS_FUNCTIONAL_REVIEW_SAVED) await test('retained public JSON fixture keeps both historical events and the final REJECTED state', async () => {
        const bytes = fs.readFileSync(process.env.VARVIS_FUNCTIONAL_REVIEW_SAVED);
        assert.equal(require('node:crypto').createHash('sha256').update(bytes).digest('hex'), 'eaa7e2a12460bf411492a8b07b1b2acc959cc2c4a63d88a3349f249c9ee6eecd');
        const exported = JSON.parse(bytes); const run = exported.calculation;
        const value = run.candidates.flatMap((c) => c.attempts).find((a) => a.functional?.recordId === 'P112L').functional;
        const h = mount({ run, value });
        // Only the fixture response's CURRENT profile is simulated; retained records/calculation stay intact.
        pending.shift().resolve({ ...exported, currentProfileSha256: 'synthetic-current-corrected-profile' }); await h.settle();
        assert.equal(exported.records.length, 2); assert.match(h.text(), /已保存记录：已拒绝/);
        assert.match(h.text(), /reviewer-synthetic-functional-network/); assert.match(h.text(), /旧资料\/政策/);
        assert.equal(h.field('本次决定').props.value, 'NONE'); assert.equal(h.button('保存本次').props.disabled, true);
        assert.equal(pending.length, 0); assert.equal(fs.readFileSync(process.env.VARVIS_FUNCTIONAL_REVIEW_SAVED).compare(bytes), 0); h.dispose();
    });
    console.log(`${count}/${count} isolated component contracts passed; browser coverage=0; real requests=0`);
})().catch((failure) => { console.error(failure); process.exitCode = 1; });
