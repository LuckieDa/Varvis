// Actual renderer execution with a minimal DOM. This checks content/interactions,
// not browser layout or visual acceptance. No browser, provider or network runs.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const source = fs.readFileSync(process.argv[2], 'utf8');
const data = source.match(/<script type="application\/json" id="report-data">([\s\S]*?)<\/script>/)?.[1];
const code = [...source.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]).join('\n');
assert.ok(data && code, 'Generated HTML must contain data and the actual renderer');
assert.ok(!code.includes('innerHTML') && !/\b(fetch|XMLHttpRequest|localStorage)\b/.test(code));
new vm.Script(code);

let focus = null;
class Element {
  constructor(tag = 'div') { this.tagName = tag; this.children = []; this.attrs = {}; this.events = {}; this.value = ''; this.isConnected = true; }
  set textContent(value) { this.value = String(value); this.children = []; }
  get textContent() { return this.value + this.children.map(child => child.textContent).join(''); }
  append(...values) { this.children.push(...values); }
  replaceChildren(...values) { this.value = ''; this.children = [...values]; }
  setAttribute(name, value) { this.attrs[name] = String(value); }
  removeAttribute(name) { delete this.attrs[name]; }
  addEventListener(type, callback) { (this.events[type] ||= []).push(callback); }
  dispatch(type, extra = {}) {
    for (const callback of this.events[type] || []) callback({ target: this, preventDefault() {}, ...extra });
  }
  focus() { focus = this; }
  showModal() { this.open = true; }
  close() { this.open = false; this.dispatch('close'); }
  getBoundingClientRect() { return { left: 0, top: 0, right: 720, bottom: 600 }; }
}
function descendants(element) { return [element, ...element.children.flatMap(descendants)]; }
function start(payload) {
  const nodes = new Map();
  for (const match of source.matchAll(/\bid="([a-zA-Z0-9-]+)"/g)) nodes.set(match[1], new Element());
  nodes.get('report-data').textContent = payload;
  let prints = 0;
  const context = vm.createContext({
    document: { getElementById(id) { assert.ok(nodes.has(id), 'Known DOM id: ' + id); return nodes.get(id); },
      createElement(tag) { return new Element(tag); } },
    window: { print() { prints++; } }, URL,
  });
  new vm.Script(code).runInContext(context);
  return { nodes, get prints() { return prints; } };
}
const results = [];
function check(name, callback) { callback(); results.push({ name, status: 'PASS' }); }
const report = JSON.parse(data);
const app = start(data), tabs = app.nodes.get('document-tabs');
const panel = app.nodes.get('document-panel'), dialog = app.nodes.get('source-dialog');
const unresolved = report.documents.some(documentRecord => Array.isArray(documentRecord.gaps) && documentRecord.gaps.length > 0);

check('real saved records produce one tab per document and visible core rows', () => {
  assert.equal(tabs.children.length, report.documents.length);
  assert.ok(panel.textContent.includes(report.documents[0].gene));
  if (!unresolved) {
    assert.equal(panel.textContent.includes('还需要核实或补充什么'), false);
    assert.equal(panel.textContent.includes('其他未计入亲属的资料仍待补充'), false);
  }
  assert.equal(app.nodes.has('report-footer'), false);
  const expected = report.documents[0].cases.reduce((count, item) => count + item.rows.length, 0);
  assert.equal(descendants(panel).filter(node => node.tagName === 'tbody').flatMap(node => node.children).length, expected);
});
check('source opens and closes while returning keyboard focus', () => {
  const button = descendants(panel).find(node => node.tagName === 'button' && node.textContent === '原文');
  assert.ok(button); button.dispatch('click'); assert.equal(dialog.open, true);
  const content = app.nodes.get('source-content');
  assert.ok(content.textContent.includes('原文位置') || descendants(content).some(node => node.tagName === 'img'));
  app.nodes.get('close-source').dispatch('click'); assert.equal(dialog.open, false); assert.equal(focus, button);
});
if (unresolved) {
  check('unresolved records show the formal pending section and the need/have/difference details', () => {
    assert.ok(panel.textContent.includes(report.gapsHeading));
    assert.ok(panel.textContent.includes('需要'));
    assert.ok(panel.textContent.includes('已有'));
    assert.ok(panel.textContent.includes('差异'));
    assert.ok(panel.textContent.includes('判定含义待核'));
  });
}
check('shared and contained excerpts appear once without merging distinct source passages', () => {
  const packet = structuredClone(report);
  const row = packet.documents[0].cases[0].rows[0];
  const short = { label: 'Gene', quote: 'The variant was homozygous.', unitId: '/article/p[1]' };
  const full = { label: 'Genotype', quote: 'The variant was homozygous. Both parents were carriers.', unitId: short.unitId };
  row.sourceRefs = [short, { ...short, label: 'Transcript' }, full, { ...full, label: 'Variant' },
    { ...short, unitId: '/article/p[2]' },
    { label: 'Other statement', quote: 'The proband was affected.', unitId: short.unitId }];
  const shared = start(JSON.stringify(packet));
  descendants(shared.nodes.get('document-panel')).find(node => node.tagName === 'button' && node.textContent === '原文').dispatch('click');
  const quotes = descendants(shared.nodes.get('source-content')).filter(node => node.tagName === 'blockquote');
  assert.deepEqual(quotes.map(node => node.textContent), [full.quote, short.quote, 'The proband was affected.']);
});
check('each source button shows only references bound to that row', () => {
  const packet = structuredClone(report);
  const originalCase = packet.documents[0].cases[0];
  const base = originalCase.rows[0];
  packet.documents[0].cases = [{ ...originalCase, rows: [
    { ...base, label: 'Gene', sourceRefs: [{ quote: 'Gene source only.', unitId: '/article/p[1]' }] },
    { ...base, label: 'Genotype', sourceRefs: [{ quote: 'Genotype source only.', unitId: '/article/p[2]' }] },
  ] }];
  const isolated = start(JSON.stringify(packet));
  const buttons = descendants(isolated.nodes.get('document-panel')).filter(node => node.tagName === 'button' && node.textContent === '原文');
  assert.equal(buttons.length, 2);
  for (const [index, quote] of ['Gene source only.', 'Genotype source only.'].entries()) {
    buttons[index].dispatch('click');
    const quotes = descendants(isolated.nodes.get('source-content')).filter(node => node.tagName === 'blockquote');
    assert.deepEqual(quotes.map(node => node.textContent), [quote]);
    isolated.nodes.get('close-source').dispatch('click');
  }
});
check('screenshots share one image per region with a working full-text link', () => {
  const packet = structuredClone(report), doc = packet.documents[0];
  const firstCase = doc.cases[0], base = firstCase.rows[0];
  doc.sources = { localScreenshotRoot: 'file:///D:/source-images/' };
  const imageUrl = 'file:///D:/source-images/table.png';
  const fullTextUrl = 'file:///D:/source-images/fulltext.html#cell1';
  doc.cases = [{ ...firstCase, rows: [{ ...base, sourceRefs: [
    { unitId: '/table/cell1', quote: 'Cell 1' }, { unitId: '/table/cell2', quote: 'Cell 2' },
  ] }] }];
  doc.sourceScreenshots = Object.fromEntries(['/table/cell1', '/table/cell2'].map(unit => [unit, [{ quote: unit.endsWith('1') ? 'Cell 1' : 'Cell 2', imageUrl, fullTextUrl }]]));
  const screenshots = start(JSON.stringify(packet));
  const button = descendants(screenshots.nodes.get('document-panel')).find(node => node.tagName === 'button' && node.textContent === '原文');
  button.dispatch('click');
  const content = descendants(screenshots.nodes.get('source-content'));
  const images = content.filter(node => node.tagName === 'img');
  assert.equal(images.length, 1); assert.equal(images[0].src, imageUrl);
  assert.equal(content.filter(node => node.tagName === 'a').length, 1);
  assert.equal(content.filter(node => node.tagName === 'blockquote').length, 0);
  assert.equal(content.some(node => node.className === 'source-annotation-note'), false);
  for (const [label, href] of [['查看完整原文', fullTextUrl]]) {
    const link = content.find(node => node.tagName === 'a' && node.textContent === label);
    assert.ok(link); assert.equal(link.href, href); assert.equal(link.target, '_blank');
    assert.equal(link.rel, 'noopener noreferrer');
  }
  images[0].dispatch('error');
  assert.equal(content.find(node => node.className === 'image-error').hidden, false);
  assert.equal(content.find(node => node.tagName === 'a' && node.textContent === '查看完整原文').href, fullTextUrl);
});
check('an unavailable screenshot falls back to the saved quotation', () => {
  const packet = structuredClone(report), doc = packet.documents[0];
  const row = doc.cases[0].rows[0];
  doc.sources = { localScreenshotRoot: 'file:///D:/source-images/' };
  row.sourceRefs = [{ unitId: '/article/p[1]', quote: 'Saved original quote.' }];
  doc.sourceScreenshots = { '/article/p[1]': [{ quote: row.sourceRefs[0].quote,
    imageUrl: null, fullTextUrl: 'file:///D:/source-images/fulltext.html#p1' }] };
  const fallback = start(JSON.stringify(packet));
  descendants(fallback.nodes.get('document-panel')).find(node => node.tagName === 'button' && node.textContent === '原文').dispatch('click');
  const content = descendants(fallback.nodes.get('source-content'));
  assert.equal(content.some(node => node.className === 'source-annotation-note'), false);
  assert.ok(content.some(node => node.tagName === 'blockquote' && node.textContent === row.sourceRefs[0].quote));
});
check('unsafe screenshot locations fall back to the saved quote', () => {
  for (const imageUrl of ['javascript:alert(1)', 'https://example.com/paper.png', 'file://host/share/paper.png', 'file:///D:/paper.png', 'file:///D:/source-images/paper.svg']) {
    const packet = structuredClone(report), doc = packet.documents[0];
    doc.sources = { localScreenshotRoot: 'file:///D:/source-images/' };
    const row = doc.cases[0].rows[0];
    row.sourceRefs = [{ unitId: '/article/p[1]', quote: 'Saved original quote.' }];
    doc.sourceScreenshots = { '/article/p[1]': { imageUrl, fullTextUrl: 'javascript:alert(1)' } };
    const invalid = start(JSON.stringify(packet));
    descendants(invalid.nodes.get('document-panel')).find(node => node.tagName === 'button' && node.textContent === '原文').dispatch('click');
    const content = descendants(invalid.nodes.get('source-content'));
    assert.equal(content.filter(node => node.tagName === 'img').length, 0);
    assert.ok(content.some(node => node.tagName === 'blockquote' && node.textContent === 'Saved original quote.'));
  }
});
check('figure and table entries use the source dialog and return focus', () => {
  const packet = structuredClone(report), doc = packet.documents[0];
  doc.sources = { localScreenshotRoot: 'file:///D:/source-images/' };
  doc.sourceGallery = [{ label: '家系图', sourceRefs: [{ unitId: '/figure', quote: 'Original pedigree caption.' }] }];
  doc.sourceScreenshots = { '/figure': [{ quote: 'Original pedigree caption.', imageUrl: 'file:///D:/source-images/figure.png', fullTextUrl: 'file:///D:/source-images/fulltext.html#figure' }] };
  const gallery = start(JSON.stringify(packet));
  const button = descendants(gallery.nodes.get('document-panel')).find(node => node.tagName === 'button' && node.textContent === '家系图');
  assert.ok(button); button.dispatch('click');
  assert.ok(descendants(gallery.nodes.get('source-content')).some(node => node.tagName === 'img'));
  gallery.nodes.get('close-source').dispatch('click');
  assert.equal(focus, button);
});
check('second article and keyboard navigation update the actual content', () => {
  assert.ok(tabs.children.length >= 2); tabs.children[1].dispatch('click');
  assert.ok(panel.textContent.includes(report.documents[1].pmcid));
  assert.equal(tabs.children[1].attrs['aria-selected'], 'true');
  tabs.dispatch('keydown', { key: 'Home' });
  assert.ok(panel.textContent.includes(report.documents[0].pmcid));
});
check('print is user initiated and initial viewing has no print action', () => {
  assert.equal(app.prints, 0); app.nodes.get('print-report').dispatch('click'); assert.equal(app.prints, 1);
  assert.equal(app.nodes.has('report-footer'), false);
});
check('an invalid or empty packet has a readable error state', () => {
  for (const payload of ['{', JSON.stringify({ schemaVersion: 'literature-readable-view-v1', documents: [] })]) {
    const invalid = start(payload);
    assert.ok(invalid.nodes.get('document-panel').textContent.includes('无法读取'));
    assert.equal(invalid.nodes.get('print-report').hidden, true);
  }
});
check('document content is treated as text and not executable HTML', () => {
  const unsafe = structuredClone(report);
  unsafe.documents[0].cases[0].rows[0].value = '<img src=x onerror=alert(1)>';
  const safe = start(JSON.stringify(unsafe));
  const all = descendants(safe.nodes.get('document-panel'));
  assert.ok(all.some(node => node.textContent.includes('<img src=x')));
  assert.equal(all.filter(node => node.tagName === 'img').length, 0);
});
console.log(JSON.stringify({ verification: 'ACTUAL_RENDERER_MINIMAL_DOM_NOT_BROWSER_VISUAL', tests: results }, null, 2));
