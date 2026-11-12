#!/usr/bin/env node
'use strict';

// Offline acceptance comparison. Never imports an evaluator or performs network/DB calls.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { isDeepStrictEqual } = require('node:util');
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
const key = a => a && [a.assembly, a.contig, a.position, a.reference, a.alternate].join('|');
const code = id => typeof id === 'string' ? id.split(':').at(-1) : null;
const same = (a, b) => isDeepStrictEqual(a ?? null, b ?? null);
function pointer(value, p) {
  if (p === '') return value;
  if (typeof p !== 'string' || !p.startsWith('/')) return undefined;
  return p.slice(1).split('/').reduce((v, k) => v?.[k.replace(/~1/g, '/').replace(/~0/g, '~')], value);
}
function contextOf(a) {
  if (a.context) return a.context;
  // Some historical literature-only results have no ordinary context. Preserve that fact.
  const f = a.localBatch?.criteria?.find(r => r.details?.pp1Facts)?.details.pp1Facts;
  const t = f?.evaluation?.target;
  if (!t) return null;
  const moi = { AD: 'AUTOSOMAL_DOMINANT', AR: 'AUTOSOMAL_RECESSIVE', XR: 'X_LINKED_RECESSIVE' };
  return {
    variant: { inputAllele: t.allele }, gene: { symbol: t.geneSymbol, hgncId: a.binding?.hgncId },
    disease: { mondoId: t.diseaseId }, moi: moi[t.inheritance] || t.inheritance,
    historicalContextOrigin: 'PP1_EVALUATION_TARGET_AND_BINDING',
  };
}
function flatRows(result) {
  const rows = [];
  for (const [ci, c] of (result.candidates || []).entries()) {
    for (const [ai, a] of (c.attempts || []).entries()) {
      const context = contextOf(a);
      const prefix = `/candidates/${ci}/attempts/${ai}`;
      function add(r, p, kind) {
        if (!r) return;
        rows.push({ candidateId: c.candidateId, attemptPointer: prefix, context,
          criterion: code(r.criterionId), status: r.evaluationResult?.status || r.status,
          strength: r.appliedStrength, reasonCode: r.evaluationResult?.reasonCode || r.reasonCode || null,
          pointer: p, kind, raw: r });
      }
      for (const [ri, r] of (a.localBatch?.criteria || []).entries()) add(r, `${prefix}/localBatch/criteria/${ri}`, 'LOCAL');
      add(a.bs1?.evaluation, `${prefix}/bs1/evaluation`, 'BS1');
      const selected = a.ba1ResultSelection;
      if (selected?.effectiveResultPath?.startsWith('/ba1/contextResults/')) {
        const cr = pointer(c, selected.effectiveResultPath);
        // Never silently fall back to a different context's BA1 result.
        if (cr?.contextId === a.ba1Context?.contextId || cr?.contextId === a.context?.contextId) {
          add(cr?.evaluation, `/candidates/${ci}${selected.effectiveResultPath}/evaluation`, 'BA1');
        }
      } else if (selected?.effectiveResultPath === '/ba1' && selected.reasonCode === 'BA1_CONTEXT_RESULT_UNAVAILABLE_CANDIDATE_DISPOSITION_ONLY' && c.ba1?.evaluation?.evaluationResult?.status === 'NOT_EVALUATED') {
        add(c.ba1.evaluation, `/candidates/${ci}/ba1/evaluation`, 'BA1_CANDIDATE_DISPOSITION_ONLY');
      }
    }
  }
  return rows;
}
function compact(row) {
  const c = row.context || {};
  return {
    criterion: row.criterion, status: row.status, strength: row.strength,
    reasonCode: row.reasonCode, pointer: row.pointer, candidateId: row.candidateId,
    context: { allele: c.variant?.inputAllele, gene: c.gene, disease: c.disease, moi: c.moi,
      transcript: c.transcript || null, dataClass: c.dataClass || null,
      affectedStatus: c.patientContext?.affectedStatus || null,
      referenceSha256: c.variant?.normalization?.referenceSequenceSha256 || null },
    materialPreparation: row.raw.details?.materialPreparation || null,
    detailSha256: sha(JSON.stringify(row.raw.details || row.raw)),
  };
}
function materialIdentities(value, rootPointer) {
  const out = [];
  function visit(v, p) {
    if (!v || typeof v !== 'object') return;
    for (const [k, child] of Object.entries(v)) {
      const next = p + '/' + k.replace(/~/g, '~0').replace(/\//g, '~1');
      if (typeof child === 'string' && /(?:sha256|version|policyId|pmcid|sourceId)$/i.test(k)) out.push({ pointer: next, value: child });
      if (child && typeof child === 'object') visit(child, next);
    }
  }
  visit(value, rootPointer); return out;
}
function validateManifest(m) {
  if (m.schemaVersion !== 'acmg-acceptance-baseline-v1' || !Array.isArray(m.cases) || !m.cases.length) throw Error('INVALID_BASELINE_SCHEMA');
  if (!Array.isArray(m.criteria) || new Set(m.criteria).size !== m.criteria.length) throw Error('INVALID_CRITERIA');
  const ids = new Set();
  for (const c of m.cases) {
    if (!c.id || ids.has(c.id)) throw Error('DUPLICATE_OR_MISSING_CASE_ID');
    ids.add(c.id);
    const a = c.allele;
    if (!a?.assembly || !a.contig || !Number.isInteger(a.position) || a.position < 1 || !a.reference || !a.alternate) throw Error('INCOMPLETE_ALLELE');
    if (c.type === 'UPSTREAM_CONTROL') continue;
    if (!c.context?.hgncId || !c.context.mondoId || !c.context.moi || !m.criteria.includes(c.criterion)) throw Error('INCOMPLETE_EXPECTED_CONTEXT');
    const e = c.expected;
    if (!e || !['MET', 'NOT_MET', 'NOT_EVALUATED', 'NOT_APPLICABLE'].includes(e.status) || !e.strength) throw Error('INVALID_EXPECTATION');
    if (e.reasonCodes && (!Array.isArray(e.reasonCodes) || e.reasonCodes.some(x => typeof x !== 'string' || !x))) throw Error('INVALID_EXPECTED_REASON');
    if (e.status !== 'MET' && !e.reasonCodes?.length && !e.assertions?.length) throw Error('NEGATIVE_REASON_REQUIRED');
    if (!c.basis?.kind || !c.basis.sourceId || !c.basis.pointer) throw Error('EXPECTATION_PROVENANCE_REQUIRED');
  }
}
function compare(result, m) {
  validateManifest(m);
  if (!Array.isArray(result.candidates)) throw Error('INVALID_RESULT_SCHEMA');
  const rows = flatRows(result);
  const structuralIssues = [];
  if (!['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(result.status)) structuralIssues.push(`RUN_NOT_COMPLETE:${result.status}`);
  if (result.selectedCount !== result.candidates.length) structuralIssues.push('SELECTED_COUNT_MISMATCH');
  if (new Set(result.enabledCriteria?.map(code)).size !== m.criteria.length || m.criteria.some(c => !result.enabledCriteria?.map(code).includes(c))) structuralIssues.push('ENABLED_CRITERIA_MISMATCH');
  const seenAlleles = new Set();
  for (const [ci, c] of result.candidates.entries()) {
    const alleles = new Set(c.attempts?.map(a => key(contextOf(a)?.variant?.inputAllele)).filter(Boolean));
    if (alleles.size !== 1) structuralIssues.push(`CANDIDATE_IDENTITY_INVALID:${ci}`);
    for (const k of alleles) { if (seenAlleles.has(k)) structuralIssues.push(`DUPLICATE_CANDIDATE:${k}`); seenAlleles.add(k); }
    const backgrounds = new Set();
    for (const [ai, a] of (c.attempts || []).entries()) {
      const ctx = contextOf(a);
      const bg = JSON.stringify([ctx?.gene?.hgncId, ctx?.disease?.mondoId, ctx?.moi]);
      if (backgrounds.has(bg)) structuralIssues.push(`DUPLICATE_BACKGROUND:${ci}:${ai}`);
      backgrounds.add(bg);
      const selected = rows.filter(r => r.attemptPointer === `/candidates/${ci}/attempts/${ai}`);
      const actual = selected.map(r => r.criterion);
      if (actual.length !== m.criteria.length || new Set(actual).size !== actual.length || m.criteria.some(x => !actual.includes(x))) structuralIssues.push(`CRITERIA_COVERAGE:${ci}:${ai}`);
      for (const r of selected) {
        if (!['MET', 'NOT_MET', 'NOT_EVALUATED', 'NOT_APPLICABLE'].includes(r.status)) structuralIssues.push(`UNKNOWN_STATUS:${r.pointer}`);
        if (!r.strength || (r.status === 'MET') === (r.strength === 'NONE')) structuralIssues.push(`INVALID_STRENGTH:${r.pointer}`);
        if (r.status !== 'MET' && !r.reasonCode && !r.raw.deterministicTrace?.steps?.length) structuralIssues.push(`MISSING_REASON:${r.pointer}`);
      }
    }
  }
  const cases = m.cases.map(c => {
    const alleleRows = rows.filter(r => key(r.context?.variant?.inputAllele) === key(c.allele));
    if (c.type === 'UPSTREAM_CONTROL') {
      const verified = m.upstreamProof?.analysisId === result.analysisId && m.upstreamProof?.excludedAlleleKeys?.includes(key(c.allele));
      return { id: c.id, label: c.label, result: alleleRows.length ? 'UNEXPECTED_CANDIDATE' : verified ? 'UPSTREAM_CONTROL_MATCH' : 'ABSENT_UPSTREAM_UNVERIFIED', expected: c.expected };
    }
    const matches = alleleRows.filter(r => r.criterion === c.criterion && r.context?.gene?.hgncId === c.context.hgncId && r.context?.disease?.mondoId === c.context.mondoId && r.context?.moi === c.context.moi);
    let verdict = !alleleRows.length ? 'CANDIDATE_MISSING' : !matches.length ? 'CONTEXT_OR_CRITERION_MISSING' : matches.length !== 1 ? 'AMBIGUOUS_MATCH' : 'MATCH';
    const actual = matches.map(compact);
    const differences = [];
    if (matches.length === 1) {
      const r = matches[0];
      if (r.status !== c.expected.status || r.strength !== c.expected.strength) verdict = 'OUTCOME_MISMATCH';
      else if (c.expected.reasonCodes?.length && !c.expected.reasonCodes.includes(r.reasonCode)) verdict = 'REASON_MISMATCH';
      else if (c.expected.assertions?.some(x => !same(pointer(r.raw, x.pointer), x.equals))) verdict = 'DETAIL_MISMATCH';
      for (const [field, value] of Object.entries(c.historicalConditions || {})) {
        const observed = pointer(r.context, field);
        if (!same(value, observed)) differences.push({ field, historical: value, observed: observed ?? null });
      }
    }
    return { id: c.id, label: c.label, criterion: c.criterion, basis: c.basis.kind, result: verdict,
      expected: c.expected, expectedContext: c.context, actual, conditionDifferences: differences,
      observedMaterialIdentities: matches.flatMap(r => materialIdentities(r.raw, r.pointer)),
      availableContexts: matches.length ? undefined : [...new Map(alleleRows.map(r => [r.attemptPointer, compact(r).context])).values()],
      acceptanceMeaning: 'Outcome comparison only; condition differences and scientific applicability require review; no automatic adoption.' };
  });
  const counts = {};
  for (const c of cases) counts[c.result] = (counts[c.result] || 0) + 1;
  const unresolved = cases.filter(c => !['MATCH', 'UPSTREAM_CONTROL_MATCH'].includes(c.result));
  const conditionDifferences = cases.filter(c => c.conditionDifferences?.length).map(c => c.id);
  return { schemaVersion: 'acmg-acceptance-comparison-v1', runId: result.runId, analysisId: result.analysisId,
    baselineId: m.id, clinicalApproval: false, scientificallyReevaluated: false,
    summary: { cases: cases.length, counts, structuralIssues, conditionDifferenceCases: conditionDifferences,
      acceptance: structuralIssues.length || unresolved.length || conditionDifferences.length ? 'NOT_ACCEPTED' : 'TECHNICAL_MATCH_ONLY' },
    cases, inventory: rows.map(compact),
    oracleCoverage: { targeted: [...new Set(m.cases.filter(c => c.criterion).map(c => c.criterion))],
      noIndependentExpectation: m.criteria.filter(x => !m.cases.some(c => c.criterion === x && c.basis?.kind === 'HISTORICAL_CONDITIONAL_TARGET')),
      note: 'Other rows are structural/observational checks, not independent scientific ground truth.' } };
}
function verifySources(m, root) {
  const verifiedBytes = new Map();
  for (const s of m.sources || []) {
    if (path.isAbsolute(s.path) || /^[a-z]:/i.test(s.path)) throw Error('SOURCE_PATH_MUST_BE_REPO_RELATIVE');
    const file = path.resolve(root, s.path);
    const rel = path.relative(root, file);
    if (rel === '..' || rel.startsWith(`..${path.sep}`)) throw Error('SOURCE_OUTSIDE_REPOSITORY');
    const bytes = fs.readFileSync(file);
    if (sha(bytes) !== s.sha256) throw Error(`SOURCE_HASH_MISMATCH:${s.id}`);
    if (verifiedBytes.has(s.id)) throw Error(`DUPLICATE_SOURCE_ID:${s.id}`);
    verifiedBytes.set(s.id, bytes);
  }
  const parsed = new Map();
  for (const c of m.cases.filter(c => c.basis?.pointer)) {
    const s = m.sources.find(s => s.id === c.basis.sourceId);
    if (!s) throw Error(`MISSING_BASIS_SOURCE:${c.id}`);
    if (!parsed.has(s.id)) parsed.set(s.id, JSON.parse(verifiedBytes.get(s.id)));
    const historical = parsed.get(s.id);
    const r = pointer(historical, c.basis.pointer);
    const e = r?.evaluationResult || r;
    if (!r || e.status !== c.expected.status || r.appliedStrength !== c.expected.strength || (c.expected.reasonCodes?.length && !c.expected.reasonCodes.includes(e.reasonCode || r.reasonCode)) || c.expected.assertions?.some(x => !same(pointer(r, x.pointer), x.equals))) throw Error(`EXPECTED_SOURCE_CONFLICT:${c.id}`);
    const row = flatRows(historical).find(row => row.pointer === c.basis.pointer);
    if (!row || key(row.context?.variant?.inputAllele) !== key(c.allele) || row.criterion !== c.criterion || row.context?.gene?.hgncId !== c.context.hgncId || row.context?.disease?.mondoId !== c.context.mondoId || row.context?.moi !== c.context.moi) throw Error(`EXPECTED_SOURCE_IDENTITY_CONFLICT:${c.id}`);
  }
}
function main(args) {
  const opts = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!['--baseline', '--baseline-sha256', '--result', '--output', '--repo'].includes(args[i]) || !args[i + 1]) throw Error('INVALID_ARGUMENTS');
    opts[args[i]] = args[i + 1];
  }
  for (const k of ['--baseline', '--baseline-sha256', '--result', '--output', '--repo']) if (!opts[k]) throw Error(`REQUIRED:${k}`);
  const bytes = fs.readFileSync(opts['--baseline']);
  if (sha(bytes) !== opts['--baseline-sha256']) throw Error('BASELINE_HASH_MISMATCH');
  const m = JSON.parse(bytes), raw = fs.readFileSync(opts['--result']);
  validateManifest(m);
  verifySources(m, path.resolve(opts['--repo']));
  const report = compare(JSON.parse(raw), m);
  report.input = { resultSha256: sha(raw), baselineSha256: sha(bytes) };
  // Exclusive creation prevents overwriting baseline/source/result evidence.
  fs.writeFileSync(opts['--output'], JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify(report.summary, null, 2));
  process.exitCode = report.summary.acceptance === 'NOT_ACCEPTED' ? 2 : 0;
}
module.exports = { sha, key, pointer, contextOf, flatRows, compact, compare, verifySources, validateManifest };
if (require.main === module) { try { main(process.argv.slice(2)); } catch (e) { console.error(e.message); process.exitCode = 1; } }
