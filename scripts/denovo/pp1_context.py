"""Source-pinned PP1 knowledge selection, separate from model-extracted facts.

Catalog entries are reused by gene/disease/inheritance, never by patient or
variant whitelist. This module does not approve a catalog or infer penetrance.
Locally curated catalogs require an adoption receipt. The separate published
specification adapter rereads exact released clauses and their scope; it does
not manufacture or inherit an adoption receipt.
"""
from datetime import date
from pathlib import Path

from model_literature_contract import canonical, digest, parse
from pp1_literature_contract import require
from pp1_formal_policy import FORMAL_METHOD, validate_model

VERSION = 'pp1-context-catalog-v1'
METHOD = 'CLINGEN_SVI_2024_SIMPLE_SEGREGATION'


def formal_context(row, selection, sources, metadata, source_needs=()):
    """Preserve the adopted row and known provenance when formal inputs are absent.

    Source pins and adoption are checked by select first. Parameter failures are
    reviewable context needs, never permission to substitute the simple method.
    """
    details = list(source_needs)

    def problem(code, field, expected, actual, requirement):
        details.append({'reasonCode': code, 'field': field, 'expected': expected,
                        'actual': actual, 'requirement': requirement})

    decisions = row.get('decisionSources')
    if not isinstance(decisions, dict):
        decisions = {}
    required = ('method', 'geneDiseaseValidity', 'expertPolicyStatus', 'affectedTerms',
                'penetrance', 'phenocopy', 'locusModel', 'candidateAssessmentMethods',
                'formalModel', 'formalModel.ascertainment', 'formalModel.pointBase')
    for field in required:
        refs = decisions.get(field)
        if (not isinstance(refs, list) or not refs
                or not all(isinstance(ref, str) and ref in sources for ref in refs)):
            problem('PP1_FORMAL_DECISION_SOURCE_REQUIRED', field,
                    'nonempty references to pinned independent knowledge sources', refs,
                    '该正式方法决策需要独立固定知识原件及精确定位，论文家系观察或模型意见不能代替。')
        if field not in {'formalModel.ascertainment', 'formalModel.pointBase'} and field not in row:
            problem('PP1_FORMAL_POLICY_PARAMETER_REQUIRED', field, 'explicit adopted policy value', None,
                    '正式方法上下文缺少明确采用的资格或参数；不能用默认值或简单法补齐。')
    for field, expected in {'penetrance': 'EXPLICIT_FORMAL_MODEL',
                            'phenocopy': 'EXPLICIT_FORMAL_MODEL',
                            'expertPolicyStatus': 'GENERAL_ALLOWED'}.items():
        if row.get(field) != expected:
            problem('PP1_FORMAL_POLICY_PARAMETER_UNSUPPORTED', field, expected, row.get(field),
                    '该资格条件不是当前正式模型明确支持的条件；不回退简单法。')
    if row.get('geneDiseaseValidity') not in ('STRONG', 'DEFINITIVE'):
        problem('PP1_FORMAL_GENE_DISEASE_VALIDITY_UNQUALIFIED', 'geneDiseaseValidity',
                ['STRONG', 'DEFINITIVE'], row.get('geneDiseaseValidity'),
                '需要独立核定的强或确定基因疾病关系，不能从本次家系共分离循环证明。')
    if row.get('locusModel') not in ('HETEROGENEOUS', 'HIGH_YIELD_HOMOGENEOUS'):
        problem('PP1_FORMAL_LOCUS_MODEL_UNSUPPORTED', 'locusModel',
                ['HETEROGENEOUS', 'HIGH_YIELD_HOMOGENEOUS'], row.get('locusModel'),
                '需要当前已实现的位点归属模型；正式似然本身不解除PP1/PP4容量和增量限制。')
    terms = row.get('affectedTerms')
    if (not isinstance(terms, list) or not terms
            or not all(isinstance(term, str) and 0 < len(term) <= 100 for term in terms)):
        problem('PP1_FORMAL_AFFECTED_DEFINITION_REQUIRED', 'affectedTerms',
                'nonempty adopted disease terms', terms, '需要本次精确疾病背景的受累状态定义。')
    methods = row.get('candidateAssessmentMethods')
    if (not isinstance(methods, list) or not methods
            or not all(isinstance(method, str) and method for method in methods)):
        problem('PP1_FORMAL_CANDIDATE_POLICY_REQUIRED', 'candidateAssessmentMethods',
                'nonempty adopted candidate-assessment methods', methods,
                '需要明确候选排查方法的资格，发现目标变异不等于排除了其他合理候选。')
    if 'affectedNativeTableTerms' in row:
        terms = row['affectedNativeTableTerms']
        if (not isinstance(terms, list) or not all(isinstance(term, str) and term for term in terms)
                or not isinstance(decisions.get('affectedNativeTableTerms'), list)
                or not decisions['affectedNativeTableTerms']
                or not all(isinstance(ref, str) and ref in sources for ref in decisions['affectedNativeTableTerms'])):
            problem('PP1_FORMAL_TABLE_AFFECTED_POLICY_REQUIRED', 'affectedNativeTableTerms',
                    'explicit disease terms with pinned decision sources', terms,
                    '表格原生表型用于受累状态需要独立采用的定义及原件定位。')
    details.extend(validate_model(row.get('formalModel'), selection.get('inheritance')))
    all_refs = [{'sourceId': key, **value} for key, value in sources.items()]
    known = {key: row[key] for key in required if key in row}
    known_facts = [{'kind': key, 'value': value} for key, value in known.items()]
    needs = []
    for detail in details:
        field = detail.get('field', '')
        matching = sorted((key for key in decisions if isinstance(key, str)
                           and (field == key or field.startswith(key + '.'))), key=len, reverse=True)
        source_ids = decisions.get(matching[0], []) if matching else []
        refs = [{'sourceId': key, **sources[key]} for key in source_ids
                if isinstance(key, str) and key in sources] if isinstance(source_ids, list) else []
        need = {**detail, 'route': 'KNOWLEDGE', 'scope': row['scope'],
                'known': known, 'knownFacts': known_facts, 'sourceRefs': refs,
                'sourceLocators': refs, 'blockingEffect': 'BLOCKS_SCORING'}
        need['needId'] = digest(canonical([FORMAL_METHOD, row['scope'], detail]))
        needs.append(need)
    status = 'QUALIFIED'
    if needs:
        status = ('METHOD_UNSUPPORTED' if any('UNSUPPORTED' in need['reasonCode'] for need in needs)
                  else 'KNOWLEDGE_CONFLICT' if any('INVALID' in need['reasonCode'] or 'CONFLICT' in need['reasonCode']
                                                  for need in needs) else 'KNOWLEDGE_MISSING')
    out = {**metadata, 'status': status,
           'policy': row, 'sourceEvidence': sources, 'known': known,
           'knownFacts': known_facts, 'sourceRefs': all_refs, 'details': needs, 'needs': needs}
    if needs:
        out['reasonCode'] = needs[0]['reasonCode']
    return out


def read_pinned(root, spec):
    root = Path(root).resolve(strict=True)
    path = (root / spec['path']).resolve(strict=True)
    require(path.is_relative_to(root) and path.is_file(), 'CONTEXT_PATH_OUTSIDE_ROOT')
    require(path.stat().st_size <= 4 * 1024 * 1024, 'CONTEXT_FILE_LIMIT')
    raw = path.read_bytes()
    require(digest(raw) == spec['sha256'], 'CONTEXT_PIN_MISMATCH')
    return raw


def select(root, configuration, selection, as_of=None, *, published_inheritance=None):
    """configuration is trusted local configuration, not an LLM request field.

    Both catalog and adoption are pinned. Required decisions cite independently
    adopted knowledge. Literal source matching checks provenance, not whether a
    scientific decision is correct; that remains the catalog adoption boundary.
    """
    if not configuration:
        return {'status': 'KNOWLEDGE_MISSING', 'reasonCode': 'PP1_QUALIFIED_CONTEXT_UNAVAILABLE'}
    if configuration.get('kind') == 'PUBLISHED_CSPEC':
        from pp1_specification_context import select as published
        require(set(configuration) - {'ruleAttachmentManifest', 'releasedAlleleScopes'}
                == {'kind','catalog','captureDirectory'}, 'PUBLISHED_CONTEXT_CONFIGURATION')
        return published(Path(root).resolve(strict=True), configuration, selection, published_inheritance=published_inheritance)
    raw = read_pinned(root, configuration['catalog'])
    doc = parse(raw)
    receipt = parse(read_pinned(root, configuration['adoption']))
    require(doc['schemaVersion'] == VERSION, 'CONTEXT_SCHEMA')
    require(receipt['catalogSha256'] == digest(raw) and receipt['status'] == 'ACCEPTED'
            and receipt['scope'] in {'PUBLIC_RESEARCH', 'SYNTHETIC_TEST'}, 'CONTEXT_NOT_ADOPTED')
    today = str(as_of or date.today())
    rows = [r for r in doc['entries'] if r['scope'] == {
        k: selection[k] for k in ('geneSymbol', 'diseaseId', 'inheritance')}]
    if not rows:
        return {'status': 'KNOWLEDGE_MISSING', 'reasonCode': 'PP1_EXACT_DISEASE_CONTEXT_UNAVAILABLE'}
    require(len(rows) == 1, 'CONTEXT_SELECTION_AMBIGUOUS')
    row = rows[0]
    date.fromisoformat(row['validFrom']); date.fromisoformat(row['validThrough'])
    require(row['validFrom'] <= today <= row['validThrough'], 'CONTEXT_EXPIRED')
    sources = {}
    is_formal = row.get('method') == FORMAL_METHOD
    source_needs = []
    specifications = row.get('sources', {}) if is_formal else row['sources']
    if is_formal and not isinstance(specifications, dict):
        specifications = {}
        source_needs.append({'reasonCode': 'PP1_FORMAL_SOURCE_REQUIRED', 'field': 'sources',
                             'expected': 'pinned source records', 'actual': row.get('sources'),
                             'requirement': '正式参数需要独立原件及精确引文定位。'})
    for key, spec in specifications.items():
        if is_formal and (not isinstance(spec, dict)
                          or not all(isinstance(spec.get(field), str) and spec[field]
                                     for field in ('path', 'sha256', 'quote', 'locator'))):
            source_needs.append({'reasonCode': 'PP1_FORMAL_SOURCE_REQUIRED', 'field': 'sources.' + key,
                                 'expected': 'path, sha256, quote and locator', 'actual': spec,
                                 'requirement': '正式参数来源缺少原件pin、引文或定位，不能用于评分。'})
            continue
        content = read_pinned(root, spec)
        require(spec['quote'] and spec['quote'] in content.decode('utf-8'), 'CONTEXT_SOURCE_QUOTE_MISMATCH')
        sources[key] = {'sha256': digest(content), 'quote': spec['quote'], 'locator': spec['locator']}
    metadata = {'catalogSha256': digest(raw), 'adoptionSha256': configuration['adoption']['sha256'],
                'dataClass': receipt['scope'], 'contextKey': digest(canonical([row['scope'], digest(raw)]))}
    if is_formal:
        return formal_context(row, selection, sources, metadata, source_needs)
    required = ('method', 'geneDiseaseValidity', 'expertPolicyStatus', 'affectedTerms',
                'penetrance', 'phenocopy', 'locusModel', 'candidateAssessmentMethods')
    for key in required:
        require(key in row and row['decisionSources'].get(key)
                and set(row['decisionSources'][key]) <= set(sources), 'CONTEXT_DECISION_SOURCE_REQUIRED')
    require(row['method'] == METHOD, 'CONTEXT_METHOD_NOT_IMPLEMENTED')
    require(row['geneDiseaseValidity'] in {'STRONG', 'DEFINITIVE'}, 'GENE_DISEASE_VALIDITY_UNQUALIFIED')
    require(row['expertPolicyStatus'] == 'GENERAL_ALLOWED', 'EXPERT_POLICY_REQUIRES_IMPLEMENTATION')
    require(row['penetrance'] in {'COMPLETE', 'AFFECTED_ONLY'} and row['phenocopy'] == 'HEURISTIC_APPLICABLE',
            'FORMAL_MODEL_REQUIRED')
    require(row['locusModel'] in {'HETEROGENEOUS', 'HIGH_YIELD_HOMOGENEOUS'}, 'LOCUS_MODEL_UNKNOWN')
    require(isinstance(row['candidateAssessmentMethods'],list) and row['candidateAssessmentMethods']
            and all(isinstance(v,str) and v for v in row['candidateAssessmentMethods']), 'CANDIDATE_ASSESSMENT_POLICY_REQUIRED')
    require(isinstance(row['affectedTerms'], list) and row['affectedTerms']
            and all(isinstance(t, str) and 0 < len(t) <= 100 for t in row['affectedTerms']), 'AFFECTED_DEFINITION_REQUIRED')
    if 'affectedNativeTableTerms' in row:
        require(isinstance(row['affectedNativeTableTerms'], list)
                and all(isinstance(t,str) and t for t in row['affectedNativeTableTerms'])
                and row['decisionSources'].get('affectedNativeTableTerms')
                and set(row['decisionSources']['affectedNativeTableTerms']) <= set(sources), 'TABLE_AFFECTED_POLICY_REQUIRED')
    if row['penetrance'] == 'COMPLETE':
        require(type(row.get('minimumUnaffectedAge')) is int and row['minimumUnaffectedAge'] >= 0
                and row['decisionSources'].get('minimumUnaffectedAge')
                and set(row['decisionSources']['minimumUnaffectedAge']) <= set(sources), 'UNAFFECTED_AGE_POLICY_REQUIRED')
    return {'status': 'QUALIFIED', 'policy': row, **metadata, 'sourceEvidence': sources}
