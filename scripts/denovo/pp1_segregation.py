"""PP1 simple-segregation events and PP1/PP4 locus cap (ClinGen SVI 2024).

Only typed, source-qualified observations are inputs. Raw head counts, model
scores, an author's ACMG codes and unreviewed statistics are never points.
Complex phase/allocation/statistical methods remain explicit unsupported needs.
"""
from collections import defaultdict
from decimal import Decimal, ROUND_FLOOR

from model_literature_contract import canonical, digest
from pp1_literature_contract import require
from pp1_context import METHOD as SIMPLE_METHOD
from pp1_diagnostic_context import normalized
from pp1_hht_phenotype import METHOD as HHT_PHENOTYPE_METHOD, assess_members as assess_hht_members
from pp1_meiosis import METHOD as MEIOSIS_METHOD, informative_transmissions, score as meiosis_score, select_families
from pp1_affected_segregations import ANCHOR

COUNT_METHOD = 'PUBLISHED_AFFECTED_RELATIVES_COUNT'

VERSION = 'pp1-segregation-evaluation-v1'
RULE = 'PMID38103548/Table3-Table4/p0115-p0130'
SCORING_ADDITION_FIELDS = {'F02.5', 'F04.2', 'F05.2', 'F05.3', 'F06.3', 'F06.4', 'F06.5', 'F07.1', 'F07.3', 'F08.1'}
REQUIREMENTS = {
    'PP1_DIAGNOSTIC_MAPPING_UNIMPLEMENTED':'原文报告了满足诊断标准，发布规范也已找到，但其疾病作用域、附加条件或表达形式尚不能由当前映射器完整核实；这是规范接入限制，不是认定论文没有临床资料。',
    'PP1_REPORTED_DIAGNOSTIC_CRITERIA_NOT_BOUND':'原文已明确报告该成员满足某项诊断标准，但当前疾病知识尚未将该标准绑定为本次PP1计数所需的患病状态；保留原文定位，不能解释成论文没有临床资料。',
    'PP1_PP4_LEDGER_UNKNOWN':'需要当前变异、疾病和分析选择的既有 PP4/共享事实记录；未知不能当作零。',
    'PP1_REASONABLE_CANDIDATE_SCOPE_UNKNOWN':'需要确认该家系合理候选范围；发现一个变异不等于只有一个合理候选。',
    'PP1_CANDIDATE_ASSESSMENT_SCOPE_UNQUALIFIED':'需要原文的候选排查方法，并与该疾病知识中允许的检测范围相容。',
    'PP1_CANDIDATE_SCOPE_CONFLICT_OR_UNRESOLVED':'原文有其他候选范围声明，尚不能和单一候选结论核对一致。',
    'PP1_AR_PHASE_ANCHOR_REQUIRED':'需要明确哪一位成员及哪些事实用于建立双等位基因相位，不能把其重复当作新增分离。',
    'PP1_AR_BIALLELIC_PHASE_NOT_ESTABLISHED':'需要核实双等位基因型及相位；单个目标变异阳性不足以支持隐性共分离。',
    'PP1_AFFECTED_NONCARRIER':'原文存在患病但未携带目标的成员；需要处理该反例及拟表型/模型适用性。',
    'PP1_UNAFFECTED_CARRIER_MODEL_CONFLICT':'可判定年龄的未患病成员携带疾病基因型，与所选完全外显模型存在冲突。',
    'PP1_MEMBER_EVENT_INPUT_INCOMPLETE':'该成员尚缺可核实的疾病状态、目标基因型或信息性关系；不自动抹掉其他合格事件。',
    'PP1_NO_QUALIFIED_INFORMATIVE_EVENT':'当前尚无可计数的合格传递/同胞事件；携带人数和作者采用 PP1 的表述不能替代。',
    'PP1_CONTRIBUTING_SOURCE_SEMANTICS_UNRESOLVED':'原文已有相关描述，但程序尚未核实其明确语义；不是认定论文没有记录。',
    'PP1_EXACT_DISEASE_CONTEXT_NOT_ADOPTED':'尚无可采用的精确基因—疾病—遗传方式知识及适用规则，不能由本篇共分离循环证明。',
    'PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW':'来源复核时补提了可能影响判定的新事实，需核实其成员、变异和原文含义；不能未经复核采用或忽略。',
    'PP1_EXPERT_COUNT_PROBAND_UNRESOLVED':'此专规需排除先证者，须明确家系经哪位成员被识别或谁被原文指定为先证者；普通患者、送检对象、首个被提及者或患病最重者均不能单独确定该角色。相关原文已保留，不能解释成完全没有病例资料。',
    'PP1_EXPERT_COUNT_NO_QUALIFIED_RELATIVE':'尚无符合本专规患病定义且携带目标变异的可核亲属；不会用携带总人数替代。',
    'PP1_OBLIGATE_CARRIER_PROOF_INCOMPLETE':'计入必然携带者需要本家系中已核实的亲子关系、子代对目标变异的杂合结果和明确的亲本来源。',
    'PP1_OBLIGATE_CARRIER_ORIGIN_CONFLICT':'目标变异的亲本来源与同家系的其他来源记录或基因型冲突，须先核对原文。',
    'PP1_OBLIGATE_CARRIER_SHARED_FACT':'该亲属的携带依据含已用于共享证据的事实，本次 PP1 不重复计入。',
    'PP1_EXPERT_COUNT_RELATIONSHIP_UNRESOLVED':'需核对可计数成员之间的亲缘链；远亲经状态未知或未患病成员连接可能存在多个病因，不能只凭同家系标签累计人数。',
    'PP1_PUBLISHED_TRANSCRIPT_NOT_MATCHED':'已找到疾病专规，但原文变异的已核实转录本尚不能与专规限定转录本对应。',
    'PP1_MEIOSIS_AFFECTED_INDEX_REQUIRED':'需要明确一位经核实患有本次疾病且携带目标杂合变异的先证者。',
    'PP1_CONDITIONAL_SIBSHIP_PHASE_REQUIRED':'需要核实条件参考成员的双亲、目标杂合结果、传递亲本和另一亲本的同位点阴性。',
    'PP1_MEIOSIS_ORIGIN_CONFLICT':'同一目标变异有相冲突的亲本来源记录，须核对亲子及检测原文。',
    'PP1_MEIOSIS_ORIGIN_UNRESOLVED':'需要明确目标变异的传递亲本，或核实另一位生物学亲本的目标变异为阴性。',
    'PP1_MEIOSIS_INDEX_CONNECTION_UNRESOLVED':'需要核实该亲子传递与先证者处于同一条可计数的家系链。',
    'PP1_MEIOSIS_FAMILY_SNAPSHOTS_DIFFER':'同一家系的来源记录存在差异，需在同一份复核资料中统一核对成员、检测和传递关系。',
    'PP1_MEIOSIS_STRENGTH_FAMILY_REQUIREMENT':'已核实的传递次数达到该档门槛，还需满足发布规则要求的独立家系数量。',
    'PP1_ADDITIONAL_FAMILY_INDEPENDENCE_REQUIRED':'需要原文核实这些家系之间是否独立，以及是否存在同一家系的重复报告。',
    'PP1_RULE_ATTACHMENT_REQUIRED':'发布规则另有必须核对的附件，需要取得与本版专规对应的原件后才能采用。',
    'PP1_RULE_ATTACHMENT_VERIFICATION_FAILED':'附件版本、适用范围或文件校验未通过，需要重新核对发布原件。',
    'PP1_RULE_ATTACHMENT_CONDITIONS_NOT_IMPLEMENTED':'附件原件已核实，附件规定的条件尚待程序接入。',
    'PP1_HHT_CURACAO_PHENOTYPE_INCOMPLETE':'需要至少三类已核实的 Curaçao 条件，或原文明确报告满足该临床标准。合格的一等亲家族史最多计一类。',
    'PP1_HHT_CURACAO_PHENOTYPE_CONFLICT':'同一成员的 Curaçao 临床表现记载存在冲突，需核实原文。',
}


def obligate_parent_evidence(rows, by, genotypes, statuses, ledger, need, family, rule_clause):
    """Recognize a reported exact AD allele transmission without imputing a test result."""
    parent_edges = defaultdict(list)
    for row in rows:
        if row['kind'] in {'parentOf', 'childOf'}:
            parent, child = (row['memberKey'], row['value']) if row['kind'] == 'parentOf' else (row['value'], row['memberKey'])
            parent_edges[parent, child].append(row)
    exclusive = set(ledger.get('exclusiveFactIds', [])) if ledger else set()
    admitted = {}
    for child in sorted(genotypes):
        claims = by[child]['alleleOriginParent']
        if not claims:
            continue
        parents = {r['value'] for r in claims}
        child_genotype = genotypes[child]
        noncarrier_parents = sorted(parent for parent in parents if genotypes.get(parent) == 'NONCARRIER')
        if child_genotype == 'NONCARRIER' or noncarrier_parents or child_genotype == 'HETEROZYGOUS' and len(parents) > 1:
            proof = claims + by[child]['genotype']
            for parent in noncarrier_parents:
                proof += by[parent]['genotype']
            need('PP1_OBLIGATE_CARRIER_ORIGIN_CONFLICT', 'SOURCE_REVIEW', family, proof, 'BLOCKS_FAMILY', rule_clause)
            continue
        if len(parents) != 1:
            continue
        parent = next(iter(parents))
        if statuses.get(parent) != 'AFFECTED' or by[parent]['genotype']:
            continue
        edges = parent_edges.get((parent, child), [])
        proof = claims + by[child]['genotype'] + edges
        if child_genotype != 'HETEROZYGOUS' or not edges or parent == child:
            need('PP1_OBLIGATE_CARRIER_PROOF_INCOMPLETE', 'LITERATURE',
                 {'familyKey':family,'memberKey':parent}, proof, 'MAY_ADD_EVENT', rule_clause)
        elif exclusive.intersection(r['id'] for r in proof):
            need('PP1_OBLIGATE_CARRIER_SHARED_FACT', 'BUSINESS_CONTEXT',
                 {'familyKey':family,'memberKey':parent}, proof, 'MAY_ADD_EVENT', rule_clause)
        else:
            admitted.setdefault(parent, proof)
    return admitted


def evaluate(qualified, context, ledger):
    """ledger is supplied by the original evaluation service, not an LLM.

    Explicit NONE for an independently checked empty PP4 ledger differs from
    UNKNOWN. Stage 4 must bind this input to the current selection/run; callers
    cannot use a different disease or a paper author's PP4 as that ledger.
    """
    from pp1_formal_policy import FORMAL_METHOD
    if context.get('policy', {}).get('method') == FORMAL_METHOD:
        from pp1_formal_segregation import evaluate as formal_evaluate
        return formal_evaluate(qualified, context, ledger)
    require(qualified and len(qualified) <= 32, 'QUALIFIED_SOURCE_LIMIT')
    target = qualified[0]['target']
    require(all(q['target'] == target for q in qualified), 'EVALUATION_TARGET_MISMATCH')
    needs, events, ignored, cautions, clinical_assessments = [], {}, [], [], []
    recognized_ledger_events, recognized_ledger_facts = set(), set()
    def need(code, route, scope=None, rows=(), effect='BLOCKS_SCORING', rule_clause=RULE):
        refs = [r for o in rows for r in o.get('sourceRefs', [])]
        value = {'reasonCode': code, 'route': route, 'scope': scope, 'ruleClause': rule_clause,
            'knownFacts': [{'kind': o['kind'], 'value': o['value']} for o in rows],
            'sourceLocators': refs, 'blockingEffect': effect,
            'requirement': REQUIREMENTS.get(code, {
                'KNOWLEDGE':'需要相容且有来源依据的疾病/方法知识；论文事实和模型意见不替代知识采用。',
                'BUSINESS_CONTEXT':'需要正式选择和既有证据记录绑定，不能借用其他分析或疾病的结果。',
                'IDENTITY_RESOURCE':'需要相容的本地身份资源或已支持的表示解析，软件未支持与数据库无记录分开处理。',
                'METHOD_REVIEW':'当前事实触发简单计数之外的方法条件，保留原文，不能套用简单分数。',
                'IDENTITY_REVIEW':'需要确定报告之间的家系身份及独立性，不能把不同论文视为不同家系。',
                'SOFTWARE_OR_SOURCE_REVIEW':'原文信息已找到，但语义核实尚不充分；需源语义处理，不应解释成没有资料。',
            }.get(route,'需要核对该范围的原文事实、成员归属及关系，当前无法把它转为合格分离事件。')),
            'absenceClaim':'NO_CLAIM_OF_ABSENCE_FROM_WHOLE_PAPER'}
        value['needId'] = digest(canonical([code, route, scope]))
        needs.append(value)
    if context.get('status') != 'QUALIFIED':
        need(context.get('reasonCode', 'PP1_CONTEXT_UNAVAILABLE'), 'KNOWLEDGE')
    scope = {k: target[k] for k in ('geneSymbol', 'diseaseId', 'inheritance')}
    policy = context.get('policy')
    if policy:
        require(policy['scope'] == scope, 'EVALUATION_CONTEXT_MISMATCH')
        if policy.get('method') not in {SIMPLE_METHOD,COUNT_METHOD,MEIOSIS_METHOD}:
            need('PP1_POLICY_METHOD_NOT_IMPLEMENTED','METHOD_REVIEW'); policy = None
    expert_count = policy is not None and policy.get('method') == COUNT_METHOD
    expert_meiosis = policy is not None and policy.get('method') == MEIOSIS_METHOD
    conditional = expert_meiosis and policy.get('conditioning') == ANCHOR
    expert = expert_count or expert_meiosis
    relationship_records = []
    family_snapshots = defaultdict(set)
    cap_used = None
    if ledger is None or ledger.get('status') not in {'KNOWN', 'KNOWN_EMPTY'}:
        need('PP1_PP4_LEDGER_UNKNOWN', 'BUSINESS_CONTEXT')
    else:
        require(ledger.get('selectionKey') == target['selectionKey'] and ledger.get('scope') == scope
                and ledger.get('allele') == target['allele'], 'EVALUATION_LEDGER_SCOPE_MISMATCH')
        require(isinstance(ledger.get('eventIds'), list) and isinstance(ledger.get('exclusiveFactIds'), list), 'LEDGER_FACTS_REQUIRED')
        cap_used = Decimal(str(ledger['pp4Points']))
        require(cap_used.is_finite() and 0 <= cap_used <= 5, 'PP4_POINTS_INVALID')
        if ledger['status'] == 'KNOWN_EMPTY':
            require(cap_used == 0 and not ledger['eventIds'] and not ledger['exclusiveFactIds'], 'EMPTY_LEDGER_CONFLICT')
    # Separate reports with the same people are not automatically independent.
    # Without an identity service, no cross-paper sum is allowed. A single
    # verified report can still provide its own conservative lower bound.
    if len(qualified) > 1 and not expert_meiosis:
        need('PP1_CROSS_PAPER_INDEPENDENCE_UNRESOLVED', 'IDENTITY_REVIEW', effect='MAY_ADD_EVENT')
    for q in qualified:
        for item in q.get('unresolved', []):
            field = item.get('fieldId', '')
            age_relevant = field == 'F05.4' and policy and policy.get('penetrance') == 'COMPLETE' and any(
                o['familyKey'] == item.get('familyKey') and o['memberKey'] == item.get('memberKey')
                and o['kind'] == 'clinical' and o['value'] == 'UNAFFECTED' for o in q['observations'])
            sex_relevant = field == 'F05.5' and target['inheritance'] == 'XLR'
            if item.get('reviewStatus') == 'PENDING_ADDITION_REVIEW' \
                    and (field in SCORING_ADDITION_FIELDS or field.startswith('F09.') or age_relevant or sex_relevant):
                need('PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW', 'SOURCE_REVIEW',
                     {k: item[k] for k in ('claimId', 'familyKey', 'memberKey', 'memberLabel')},
                     [{'kind': field, 'value': item['sourceValue'], 'sourceRefs': item['sourceRefs']}])
        if not q['identity']:
            code = q['identityIssue'] or 'PP1_TARGET_IDENTITY_UNRESOLVED'
            route = 'LITERATURE' if 'MISSING_OR_AMBIGUOUS' in code else 'IDENTITY_RESOURCE'
            need(code, route, q['sourceIdentity'], q.get('identityEvidence', [])); continue
        if expert and policy.get('transcriptRestrictions'):
            transcript = q['identity'].get('transcript','')
            if not any(transcript == t or '.' not in t and transcript.startswith(t+'.')
                       for t in policy['transcriptRestrictions']):
                need('PP1_PUBLISHED_TRANSCRIPT_NOT_MATCHED','IDENTITY_RESOURCE',q['sourceIdentity'],q.get('identityEvidence',[]));continue
        if conditional and q['identity'].get('coding') != policy['alleleScope']['coding']:
            need('PP1_PUBLISHED_ALLELE_SCOPE_MISMATCH', 'IDENTITY_RESOURCE',
                 q['sourceIdentity'], q.get('identityEvidence', [])); continue
        if expert_meiosis:
            for record in q.get('familyRelationshipEvidence', []):
                if all(key in q['families'] for key in record['familyKeys']):
                    relationship_records.append(record)
        for family, roster in q['families'].items():
            rows = [r for r in q['observations'] if r['familyKey'] == family]
            if expert_meiosis:
                semantics = tuple(sorted({canonical([r['memberKey'], r['kind'], r['value']]) for r in rows}))
                family_snapshots[family].add((tuple(sorted(roster['members'].values())), semantics))
            family_contribution_id = digest(canonical([target['allele'], scope, family, 'PP1_FAMILY_CONTRIBUTION']))
            count_contribution_id = digest(canonical([target['allele'], scope, family, COUNT_METHOD]))
            recognized_ledger_events.update((family_contribution_id, count_contribution_id))
            recognized_ledger_facts.update(r['id'] for r in rows)
            by = defaultdict(lambda: defaultdict(list))
            for row in rows: by[row['memberKey']][row['kind']].append(row)
            def value(member, kind):
                vals = {canonical(r['value']): r['value'] for r in by[member][kind]}
                if kind == 'genotype' and len(vals) > 1:
                    # An explicit zygosity refines a generic positive result.
                    specific = set(vals) - {canonical('CARRIER')}
                    if len(specific) == 1 and canonical('NONCARRIER') not in specific:
                        return vals[next(iter(specific))]
                if len(vals) > 1:
                    need('PP1_CONFLICTING_' + kind.upper(), 'LITERATURE', family, by[member][kind], 'BLOCKS_FAMILY')
                    return None
                return next(iter(vals.values())) if vals else None
            family_cautions = [r for r in rows if r['kind'] == 'caution']
            if family_cautions:
                cautions.extend(family_cautions)
                need('PP1_RECORDED_CAUTION_REQUIRES_MODEL_REVIEW', 'METHOD_REVIEW', family, family_cautions, 'BLOCKS_FAMILY')
                continue
            if policy is None: continue
            if policy['locusModel'] == 'HIGH_YIELD_HOMOGENEOUS':
                ignored.append({'familyKey': family, 'reasonCode': 'PP1_NOT_INCREMENTAL_TO_HIGH_YIELD_PP4',
                                'ruleClause': 'PMID38103548/p0100'}); continue
            candidates = [r for r in rows if r['kind'] == 'candidateScope']
            # The published count rule cautions about reported alternatives;
            # it does not require every paper to assert exhaustive exclusion.
            # Keep the simple-method candidate allocation gate unchanged.
            if not candidates and not (expert and policy.get('candidateHandling') ==
                                        'REPORTED_ALTERNATIVES_REQUIRE_REVIEW'):
                need('PP1_REASONABLE_CANDIDATE_SCOPE_UNKNOWN', 'LITERATURE', family, rows, 'BLOCKS_FAMILY')
            unknown_candidates=[r for r in rows if r['kind']=='candidateScopeUnresolved']
            if unknown_candidates:
                need('PP1_CANDIDATE_SCOPE_CONFLICT_OR_UNRESOLVED','SOURCE_REVIEW',family,unknown_candidates,'BLOCKS_FAMILY')
            methods = [r for r in rows if r['kind']=='candidateMethod' and r['value'] in policy.get('candidateAssessmentMethods',[])]
            if not methods and not expert:
                need('PP1_CANDIDATE_ASSESSMENT_SCOPE_UNQUALIFIED', 'LITERATURE', family, rows, 'BLOCKS_FAMILY')
            members = {k: v for k, v in roster['members'].items() if k != 'FAMILY'}
            statuses, genotypes = {}, {}
            hht_members = {}
            if expert_meiosis and policy.get('affectedDefinition') == HHT_PHENOTYPE_METHOD:
                rule_attachment, = context['ruleAttachments']
                hht_members = {key: (proof, assessment) for key, proof, assessment in assess_hht_members(
                    rows, {key: label for label, key in members.items()}, policy['phenotypeQualification'], rule_attachment)}
            for label, member in members.items():
                genotype = value(member, 'genotype'); genotypes[member] = genotype
                diagnosis = value(member, 'diagnosis'); clinical = value(member, 'clinical')
                affected = diagnosis in policy['affectedTerms']
                mapped = []
                if policy.get('affectedDefinition') == 'REPORTED_PHYSICIAN_ASSESSMENT':
                    for observation in by[member]['reportedDiagnosticCriteria']:
                        bindings = [b for b in policy.get('diagnosticCriteriaBindings', [])
                                    if b.get('scope') == scope
                                    and b.get('meaning') == 'REPORTED_FULFILLMENT_OF_PUBLISHED_DISEASE_CRITERIA'
                                    and normalized(b.get('criteriaName')) == normalized(observation['value'])]
                        if len(bindings) != 1: continue
                        mapped.append(observation)
                        clinical_assessments.append({'familyKey':family,'memberKey':member,'memberLabel':label,
                            'diseaseId':scope['diseaseId'],'status':'CONFLICT' if clinical == 'UNAFFECTED' else 'AFFECTED',
                            'basis':'REPORTED_DIAGNOSTIC_CRITERIA_WITH_PUBLISHED_DISEASE_BINDING',
                            'reportedCriteria':observation['value'],'sourceRefs':observation['sourceRefs'],
                            'knowledgeBinding':bindings[0], 'independentClinicalDiagnosisPerformed':False})
                    if mapped:
                        affected = True
                        # Keep the original assertion in event/conflict proof;
                        # do not mutate the source-qualified input inventory.
                        by[member]['diagnosis'] += mapped
                native_terms = value(member, 'nativePhenotypes') or []
                if any(t in policy.get('affectedNativeTableTerms', []) for t in native_terms):
                    affected = True
                    # Retain the native phenotype-cell proof in every event.
                    by[member]['diagnosis'] += by[member]['nativePhenotypes']
                if member in hht_members:
                    proof, assessment = hht_members[member]
                    affected = assessment['status'] == 'AFFECTED'
                    clinical = None  # Age and reported unaffected status never qualify an HHT event.
                    if affected:
                        by[member]['diagnosis'] = proof
                    if proof or genotype is not None:
                        clinical_assessments.append({**assessment, 'familyKey':family, 'diseaseId':scope['diseaseId']})
                    if assessment['status'] == 'CONFLICT':
                        need('PP1_HHT_CURACAO_PHENOTYPE_CONFLICT', 'SOURCE_REVIEW', family, proof,
                             'BLOCKS_FAMILY', policy['ruleClause'])
                    elif not affected and genotype in {'CARRIER', 'HETEROZYGOUS'}:
                        need('PP1_HHT_CURACAO_PHENOTYPE_INCOMPLETE', 'LITERATURE',
                             {'familyKey':family, 'memberKey':member, 'memberLabel':label}, proof,
                             'MAY_ADD_EVENT', policy['ruleClause'])
                if affected and clinical == 'UNAFFECTED':
                    need('PP1_CLINICAL_CONFLICT', 'LITERATURE', member, by[member]['diagnosis'] + by[member]['clinical'])
                    statuses[member] = None; continue
                statuses[member] = 'AFFECTED' if affected else clinical
                if expert_meiosis and not affected and clinical == 'AFFECTED':
                    statuses[member] = None
                if member not in hht_members and statuses[member] is None and by[member]['reportedDiagnosticCriteria']:
                    mapping_pending = bool(policy.get('diagnosticBindingNeeds'))
                    need('PP1_DIAGNOSTIC_MAPPING_UNIMPLEMENTED' if mapping_pending else 'PP1_REPORTED_DIAGNOSTIC_CRITERIA_NOT_BOUND',
                         'SOFTWARE_OR_SOURCE_REVIEW' if mapping_pending else 'KNOWLEDGE',
                         {'familyKey':family,'memberKey':member,'memberLabel':label},
                         by[member]['reportedDiagnosticCriteria'],'MAY_ADD_EVENT')
                if affected and genotype == 'NONCARRIER':
                    need('PP1_AFFECTED_NONCARRIER', 'METHOD_REVIEW', family, by[member]['genotype'] + by[member]['diagnosis'])
                risk_genotype = (target['inheritance'] == 'AD' and genotype in {'CARRIER','HETEROZYGOUS','HOMOZYGOUS'}) \
                    or (target['inheritance'] == 'AR' and (genotype == 'HOMOZYGOUS' or value(member,'biallelic') is not None)) \
                    or (target['inheritance'] == 'XLR' and genotype == 'HEMIZYGOUS' and value(member,'sex') == 'MALE')
                if clinical == 'UNAFFECTED' and risk_genotype:
                    cautions.append({'memberKey': member, 'reasonCode': 'PP1_UNAFFECTED_CARRIER_RETAINED',
                                     'sourceRefs': [r for o in by[member]['genotype'] for r in o['sourceRefs']]})
                    age = value(member, 'age')
                    if policy['penetrance'] == 'COMPLETE' and age is not None and age >= policy['minimumUnaffectedAge']:
                        need('PP1_UNAFFECTED_CARRIER_MODEL_CONFLICT', 'METHOD_REVIEW', family, by[member]['genotype'])
            if expert_count:
                inferred = {}
                if target['inheritance'] == 'AD' and policy.get('obligateCarriersImplemented') is True:
                    inferred = obligate_parent_evidence(rows, by, genotypes, statuses, ledger, need, family, policy['ruleClause'])
                # Report independent missing inputs before the proband gate.
                # Otherwise the reader never sees which members need clinical
                # or carrier facts until a different early gate is resolved.
                for label, member in members.items():
                    missing = []
                    if statuses[member] is None: missing.append('CLINICAL_STATUS')
                    if genotypes[member] is None and member not in inferred: missing.append('TARGET_GENOTYPE')
                    if missing:
                        need('PP1_MEMBER_EVENT_INPUT_INCOMPLETE', 'LITERATURE',
                             {'familyKey': family, 'memberKey': member, 'memberLabel': label,
                              'missingInputs': missing},
                             [r for r in rows if r['memberKey'] == member], 'MAY_ADD_EVENT')
                probands = [m for m in members.values() if value(m,'proband') is True]
                if len(probands) != 1:
                    role_proposals = [{'kind':'unqualifiedAscertainmentClaim','value':item.get('sourceValue'),
                        'sourceRefs':item.get('sourceRefs',[])} for item in q.get('unresolved',[])
                        if item.get('familyKey') == family and item.get('fieldId') == 'F04.2']
                    need('PP1_EXPERT_COUNT_PROBAND_UNRESOLVED','LITERATURE',family,rows+role_proposals,'BLOCKS_FAMILY');continue
                proband = probands[0]
                selected = sorted(m for m in set(members.values()) if m != proband and statuses[m] == 'AFFECTED'
                                  and (m in inferred or genotypes[m] in {'CARRIER','HETEROZYGOUS','HOMOZYGOUS','HEMIZYGOUS'}))
                if not selected:
                    need('PP1_EXPERT_COUNT_NO_QUALIFIED_RELATIVE','LITERATURE',family,rows,'BLOCKS_FAMILY');continue
                proof = candidates + by[proband]['proband']
                # The published notes caution against distant affected members
                # connected through unknown/unaffected relatives. A common
                # family label is not a verified relationship chain. Reuse
                # source-qualified first-degree edges, without inferring phase.
                connected_members = set(selected)
                if statuses.get(proband) == 'AFFECTED': connected_members.add(proband)
                graph = defaultdict(set); relationship_proof = []; count_parents = defaultdict(set)
                for row in rows:
                    if row['kind'] not in {'parentOf','childOf','fullSiblingOf'}: continue
                    left,right = row['memberKey'],row['value']
                    if row['kind'] in {'parentOf','childOf'}:
                        parent,child=(left,right) if row['kind']=='parentOf' else (right,left)
                        count_parents[child].add(parent)
                    if left in connected_members and right in connected_members and left != right:
                        graph[left].add(right);graph[right].add(left);relationship_proof.append(row)
                def count_cycle(node, active, visited):
                    if node in active: return True
                    if node in visited: return False
                    active.add(node)
                    if any(count_cycle(p,active,visited) for p in count_parents[node]): return True
                    active.remove(node);visited.add(node);return False
                if any(len(ps)>2 for ps in list(count_parents.values())) or any(
                        count_cycle(m,set(),set()) for m in members.values()):
                    need('PP1_COMPLEX_OR_CYCLIC_PEDIGREE','LITERATURE',family,rows,'BLOCKS_FAMILY');continue
                reached=set();todo=[selected[0]]
                while todo:
                    member=todo.pop()
                    if member in reached: continue
                    reached.add(member);todo.extend(graph[member]-reached)
                if not set(selected).issubset(reached):
                    need('PP1_EXPERT_COUNT_RELATIONSHIP_UNRESOLVED','LITERATURE',family,rows,'BLOCKS_FAMILY');continue
                proof += relationship_proof
                if proband in reached:
                    proof += by[proband]['diagnosis'] + by[proband]['clinical']
                for member in selected:
                    proof += inferred.get(member, by[member]['genotype']) + by[member]['diagnosis'] + by[member]['clinical']
                for item in q.get('unresolved',[]):
                    if item.get('memberKey') in selected and item.get('fieldId') in SCORING_ADDITION_FIELDS \
                            and item.get('reasonCode') == 'PP1_TYPED_SEMANTICS_UNRESOLVED':
                        need('PP1_CONTRIBUTING_SOURCE_SEMANTICS_UNRESOLVED','SOFTWARE_OR_SOURCE_REVIEW',
                             {'familyKey':family,'claimId':item['claimId']},
                             [{'kind':item['fieldId'],'value':'UNRESOLVED','sourceRefs':item['sourceRefs']}])
                count = len(selected)
                count_strength = next((s for s in ('STRONG','MODERATE','SUPPORTING')
                    if count >= policy['thresholds'][s]['minimum'] and
                    (policy['thresholds'][s]['maximum'] is None or count <= policy['thresholds'][s]['maximum'])),None)
                points = {'SUPPORTING':1,'MODERATE':2,'STRONG':4,None:0}[count_strength]
                # A grouped rule contribution avoids assigning made-up fractional
                # points per relative when expert thresholds are nonlinear.
                fact_ids = sorted({r['id'] for r in proof})
                # A different analysis selection does not create a new family
                # observation. Membership is kept in the payload/fact ledger;
                # finding another relative must not evade an allocated group.
                eid = digest(canonical([target['allele'], scope, family, COUNT_METHOD]))
                family_contribution_id = digest(canonical([target['allele'], scope, family, 'PP1_FAMILY_CONTRIBUTION']))
                if ledger and (eid in ledger['eventIds'] or family_contribution_id in ledger['eventIds']
                               or set(fact_ids) & set(ledger['exclusiveFactIds'])):
                    ignored.append({'eventId':eid,'reasonCode':'PP1_SHARED_EVENT_ALREADY_ALLOCATED'});continue
                events[eid] = {'eventId':eid,'familyKey':family,'familyLabel':roster.get('sourceFamilyLabel'),
                    'memberKey':proband,'memberLabel':'、'.join(label for label,key in members.items() if key in selected),
                    'memberKeys':selected,'probandKey':proband,'affectedRelativesCount':count,
                    'kind':'EXPERT_AFFECTED_RELATIVES_COUNT','rawPoints':str(points),'expertStrength':count_strength,
                    'factIds':fact_ids,'ruleClause':policy['ruleClause'],'pmcid':q['sourceIdentity']['pmcid'],
                    'sourceRefs':[ref for r in proof for ref in r['sourceRefs']]}
                inferred_members = [m for m in selected if m in inferred]
                if inferred_members:
                    events[eid]['obligateCarrierCount'] = len(inferred_members)
                    events[eid]['obligateCarrierLabels'] = sorted(label for label, key in members.items() if key in inferred_members)
                    events[eid]['sourceRefs'] = list({canonical(ref):ref for ref in events[eid]['sourceRefs']}.values())
                for member in sorted(set(members.values()) - set(selected) - {proband}):
                    if statuses[member] is None or genotypes[member] is None:
                        need('PP1_MEMBER_EVENT_INPUT_INCOMPLETE','LITERATURE',
                             {'familyKey':family,'memberKey':member},by[member]['genotype']+by[member]['diagnosis'],'MAY_ADD_EVENT')
                continue
            parents = defaultdict(set)
            edge_refs = defaultdict(list)
            for r in rows:
                if r['kind'] not in {'parentOf', 'childOf'}: continue
                p, c = (r['memberKey'], r['value']) if r['kind'] == 'parentOf' else (r['value'], r['memberKey'])
                if p not in members.values() or c not in members.values() or p == c:
                    need('PP1_RELATION_ENDPOINT_UNRESOLVED', 'LITERATURE', family, [r]); continue
                parents[c].add(p); edge_refs[(p,c)].append(r)
            active, visited = set(), set()
            def cycle(node):
                if node in active: return True
                if node in visited: return False
                active.add(node)
                if any(cycle(p) for p in parents[node]): return True
                active.remove(node); visited.add(node); return False
            if any(len(ps) > 2 for ps in list(parents.values())) or any(cycle(m) for m in members.values()):
                need('PP1_COMPLEX_OR_CYCLIC_PEDIGREE', 'METHOD_REVIEW', family, rows, 'BLOCKS_FAMILY'); continue
            def ancestors(node):
                found = set()
                todo = list(parents[node])
                while todo:
                    p = todo.pop()
                    if p not in found: found.add(p); todo.extend(parents[p])
                return found
            if any(len(ps)==2 and bool(ancestors(list(ps)[0]) & ancestors(list(ps)[1]))
                   for ps in list(parents.values())):
                need('PP1_CONSANGUINITY_REQUIRES_FORMAL_MODEL', 'METHOD_REVIEW', family, rows, 'BLOCKS_FAMILY'); continue
            def unaffected(member):
                age = value(member, 'age')
                return statuses[member] == 'UNAFFECTED' and policy['penetrance'] == 'COMPLETE' \
                    and age is not None and age >= policy['minimumUnaffectedAge']
            def add(kind, member, parent_keys, points, proof, reference=None):
                proof = proof + candidates + methods
                contributing_members={r['memberKey'] for r in proof}
                unresolved=[r for r in q.get('unresolved',[]) if r.get('memberKey') in contributing_members
                            and r.get('fieldId') in {'F05.2','F06.3','F06.4','F07.1','F08.1'}
                            and r['reasonCode']=='PP1_TYPED_SEMANTICS_UNRESOLVED']
                if unresolved:
                    for r in unresolved:
                        need('PP1_CONTRIBUTING_SOURCE_SEMANTICS_UNRESOLVED','SOFTWARE_OR_SOURCE_REVIEW',
                             {'familyKey':family,'memberKey':r['memberKey'],'claimId':r['claimId']},
                             [{'kind':r['fieldId'],'value':'已有原句，但尚未核实为明确的成员/变异/关系事实。','sourceRefs':r['sourceRefs']}],
                             'MAY_ADD_EVENT')
                    return
                eid = digest(canonical([target['allele'], scope, family, kind, member, sorted(parent_keys)]))
                recognized_ledger_events.add(eid)
                prior_id = digest(canonical([target['allele'], scope, family, 'AD_AFFECTED_TRANSMISSION', member, sorted(parent_keys)])) if expert_meiosis else eid
                recognized_ledger_events.add(prior_id)
                fact_ids = sorted({o['id'] for o in proof})
                # Only facts adjudicated as exclusive contributions are blocked;
                # sharing a paper or a parent's genotype is not itself exclusion.
                family_contribution_id = digest(canonical([target['allele'], scope, family, 'PP1_FAMILY_CONTRIBUTION']))
                if ledger and (eid in ledger.get('eventIds', []) or prior_id in ledger.get('eventIds', []) or family_contribution_id in ledger.get('eventIds', [])
                               or count_contribution_id in ledger.get('eventIds', [])
                               or set(fact_ids) & set(ledger.get('exclusiveFactIds', []))):
                    ignored.append({'eventId': eid, 'reasonCode': 'PP1_ALREADY_USED_OR_SHARED_FACT', 'factIds': fact_ids}); return
                events[eid] = {'eventId': eid, 'familyKey': family, 'memberKey': member, 'parentKeys': sorted(parent_keys),
                    'memberLabel': next((label for label,key in members.items() if key==member), member),
                    'familyLabel': roster.get('sourceFamilyLabel'), 'pmcid': q['sourceIdentity']['pmcid'],
                    'kind': kind, 'factIds': fact_ids, 'ruleClause': policy['ruleClause'] if expert_meiosis else RULE,
                    'sourceRefs': [r for o in proof for r in o['sourceRefs']]}
                if expert_meiosis:
                    events[eid]['informativeMeioses'] = 1
                    events[eid]['parentLabel'] = next((label for label, key in members.items() if key == parent_keys[0]), '')
                    events[eid]['sourceRefs'] = list({canonical(ref):ref for ref in events[eid]['sourceRefs']}.values())
                    if conditional:
                        events[eid]['meiosisBasis'] = 'CONDITIONAL_SIBSHIP' if reference else 'DIRECT_PARENT_CHILD'
                        if reference:
                            events[eid]['conditionalReferenceLabel'] = next(label for label, key in members.items() if key == reference)
                else:
                    events[eid]['rawPoints'] = str(points)
            if expert_meiosis:
                if target['inheritance'] != 'AD':
                    need('PUBLISHED_PP1_MEIOSIS_AD_REQUIRED', 'KNOWLEDGE')
                    continue
                inferred = (obligate_parent_evidence(rows, by, genotypes, statuses, ledger, need, family,
                                                    policy['ruleClause']) if conditional else {})
                for parent, child, proof, reference in informative_transmissions(
                        family, members.values(), parents, edge_refs, by, statuses, genotypes, need,
                        policy['ruleClause'], conditional=conditional, inferred=inferred):
                    add('INFORMATIVE_MEIOSIS', child, [parent], None, proof, reference)
            elif target['inheritance'] == 'AD':
                for child, ps in list(parents.items()):
                    carriers = [p for p in ps if genotypes.get(p) in {'CARRIER','HETEROZYGOUS','HOMOZYGOUS'}]
                    if len(carriers) != 1:
                        if len(carriers) > 1: need('PP1_PARENTAL_ORIGIN_AMBIGUOUS', 'LITERATURE', child, rows)
                        continue
                    parent = carriers[0]
                    if genotypes[parent] != 'HETEROZYGOUS' or statuses.get(parent) != 'AFFECTED':
                        need('PP1_INFORMATIVE_PARENT_GENOTYPE_OR_PHENOTYPE_UNRESOLVED','LITERATURE',
                             {'familyKey':family,'memberKey':parent},by[parent]['genotype'] + by[parent]['diagnosis'],'MAY_ADD_EVENT')
                        continue
                    if statuses[child] == 'AFFECTED' and genotypes[child] in {'CARRIER','HETEROZYGOUS'}:
                        add('AD_AFFECTED_TRANSMISSION', child, [parent], Decimal(1),
                            edge_refs[(parent,child)] + by[parent]['genotype'] + by[child]['genotype']
                            + by[parent]['diagnosis'] + by[child]['diagnosis'])
                    elif unaffected(child) and genotypes[child] == 'NONCARRIER':
                        add('AD_UNAFFECTED_TRANSMISSION', child, [parent], Decimal(1),
                            edge_refs[(parent,child)] + by[parent]['genotype'] + by[child]['genotype']
                            + by[parent]['diagnosis'] + by[child]['clinical'] + by[child]['age'])
            elif target['inheritance'] == 'AR':
                # Affected index establishes the homozygous phase. Only siblings
                # with the same two demonstrated heterozygous parents add points.
                probands = [m for m in members.values() if value(m, 'proband') is True]
                if len(probands) != 1:
                    need('PP1_AR_PHASE_ANCHOR_REQUIRED', 'LITERATURE', family, rows, 'BLOCKS_FAMILY'); continue
                anchor = probands[0]; ps = parents[anchor]
                pair = value(anchor,'biallelic')
                if pair and pair['phase'] == 'IN_TRANS' and statuses[anchor] == 'AFFECTED':
                    # Directly established trans phase does not require a full
                    # trio or a pre-existing P/LP classification of the partner.
                    for child in members.values():
                        if child == anchor: continue
                        links = [r for r in rows if r['kind']=='fullSiblingOf' and
                                 {r['memberKey'],r['value']} == {anchor,child}]
                        shared_parents = len(ps)==2 and parents[child]==ps
                        if not links and not shared_parents: continue
                        if statuses[child]=='AFFECTED' and value(child,'biallelic') == pair:
                            proof = by[anchor]['biallelic'] + by[anchor]['diagnosis'] + by[anchor]['proband'] \
                                + by[child]['biallelic'] + by[child]['diagnosis'] + links
                            if shared_parents: proof += [r for p in ps for r in edge_refs[(p,anchor)] + edge_refs[(p,child)]]
                            add('AR_TRANS_AFFECTED_SIBLING',child,[anchor],Decimal(2),proof)
                    continue
                if len(ps) != 2 or genotypes[anchor] != 'HOMOZYGOUS' or statuses[anchor] != 'AFFECTED' \
                        or any(genotypes.get(p) != 'HETEROZYGOUS' for p in ps):
                    need('PP1_AR_BIALLELIC_PHASE_NOT_ESTABLISHED', 'LITERATURE', family, rows, 'BLOCKS_FAMILY'); continue
                phase_proof = by[anchor]['genotype'] + by[anchor]['diagnosis'] + by[anchor]['proband']
                phase_proof += [r for p in ps for r in edge_refs[(p,anchor)] + by[p]['genotype']]
                for child in members.values():
                    if child == anchor or child in ps or parents[child] != ps: continue
                    proof = phase_proof + [r for p in ps for r in edge_refs[(p,child)]] + by[child]['genotype']
                    if statuses[child] == 'AFFECTED' and genotypes[child] == 'HOMOZYGOUS':
                        add('AR_AFFECTED_SIBLING', child, ps, Decimal(2), proof + by[child]['diagnosis'])
                    elif unaffected(child) and genotypes[child] in {'NONCARRIER', 'HETEROZYGOUS'}:
                        add('AR_UNAFFECTED_SIBLING', child, ps, Decimal('0.4'), proof + by[child]['clinical'] + by[child]['age'])
            elif target['inheritance'] == 'XLR':
                for child, ps in list(parents.items()):
                    mothers = [p for p in ps if value(p,'sex') == 'FEMALE' and genotypes.get(p) == 'HETEROZYGOUS']
                    if len(mothers) != 1 or value(child,'sex') != 'MALE': continue
                    mother = mothers[0]
                    proof = edge_refs[(mother,child)] + by[mother]['genotype'] + by[mother]['sex'] + by[child]['sex'] + by[child]['genotype']
                    if statuses[child] == 'AFFECTED' and genotypes[child] == 'HEMIZYGOUS':
                        add('XLR_AFFECTED_MALE', child, [mother], Decimal(1), proof + by[child]['diagnosis'])
                    elif unaffected(child) and genotypes[child] == 'NONCARRIER':
                        add('XLR_UNAFFECTED_MALE', child, [mother], Decimal(1), proof + by[child]['clinical'] + by[child]['age'])
            else:
                need('PP1_INHERITANCE_METHOD_UNSUPPORTED', 'METHOD_REVIEW', family, rows, 'BLOCKS_FAMILY')
            # Missing members do not delete known events. They remain opportunities
            # for targeted reading, not a mandatory all-relatives-complete gate.
            for label, member in members.items():
                if statuses[member] is None or genotypes[member] is None or (not parents[member] and not any(member in p for p in parents.values())):
                    need('PP1_MEMBER_EVENT_INPUT_INCOMPLETE', 'LITERATURE', {'familyKey':family,'memberKey':member,'memberLabel':label},
                         [r for r in rows if r['memberKey'] == member], 'MAY_ADD_EVENT')
    if events and ledger and (set(ledger.get('eventIds', [])) - recognized_ledger_events
                             or set(ledger.get('exclusiveFactIds', [])) - recognized_ledger_facts):
        need('PP1_PRIOR_FAMILY_IDENTITY_UNRESOLVED', 'IDENTITY_REVIEW',
             {'priorEventIds': ledger.get('eventIds', []), 'priorExclusiveFactIds': ledger.get('exclusiveFactIds', [])})
    for family, snapshots in family_snapshots.items():
        if len(snapshots) > 1:
            need('PP1_MEIOSIS_FAMILY_SNAPSHOTS_DIFFER', 'SOURCE_REVIEW', family, (), 'BLOCKS_FAMILY', policy['ruleClause'])
    excluded_families = {n['scope'] for n in needs if n['blockingEffect'] == 'BLOCKS_FAMILY'}
    for eid, event in list(events.items()):
        if event['familyKey'] in excluded_families:
            ignored.append({**events.pop(eid), 'reasonCode': 'PP1_FAMILY_REQUIREMENTS_UNRESOLVED'})
    event_families = sorted({e['familyKey'] for e in events.values()})
    meiosis_families, independence_evidence = (), []
    if expert_meiosis:
        counts = defaultdict(int)
        for event in events.values():
            counts[event['familyKey']] += event['informativeMeioses']
        meiosis_families, independence_evidence = select_families(counts, relationship_records, policy['thresholds'])
        if len(meiosis_families) != len(event_families):
            evidence = [{'kind':'familyRelationship', 'value':record['status'], 'sourceRefs':record['sourceRefs']}
                        for record in relationship_records if record['status'] != 'INDEPENDENT']
            need('PP1_ADDITIONAL_FAMILY_INDEPENDENCE_REQUIRED','IDENTITY_REVIEW',event_families,
                 evidence,'MAY_ADD_EVENT',policy['ruleClause'])
            for eid, event in list(events.items()):
                if event['familyKey'] not in meiosis_families:
                    ignored.append({**events.pop(eid), 'reasonCode':'PP1_INDEPENDENCE_NOT_ESTABLISHED'})
    elif len(event_families)>1:
        # Distinct display names are not evidence of unrelated pedigrees. Use
        # a complete single-family lower bound; do not combine independent-
        # looking transmissions across reports. Check all adverse observations
        # before choosing, so this cannot hide a conflicting report.
        family_points = {family: sum((Decimal(e['rawPoints']) for e in events.values()
                                     if e['familyKey'] == family), Decimal(0)) for family in event_families}
        retained = sorted(event_families, key=lambda f: (-family_points[f], f))[0]
        need('PP1_ADDITIONAL_FAMILY_INDEPENDENCE_REQUIRED','LITERATURE',event_families,
             effect='MAY_ADD_EVENT')
        for eid,event in list(events.items()):
            if event['familyKey']!=retained:
                ignored.append({**events.pop(eid),'reasonCode':'PP1_INDEPENDENCE_NOT_ESTABLISHED'})
    if expert_meiosis:
        count = len(events)
        count_strength, points = meiosis_score(policy['thresholds'], count, len(meiosis_families))
        sufficient_strength, _ = meiosis_score(policy['thresholds'], count, 2)
        if sufficient_strength and not count_strength:
            need('PP1_MEIOSIS_STRENGTH_FAMILY_REQUIREMENT','IDENTITY_REVIEW',list(meiosis_families),
                 (), 'BLOCKS_SCORING',policy['ruleClause'])
        raw = Decimal(points)
    else:
        raw = sum((Decimal(e['rawPoints']) for e in events.values()), Decimal(0))
    blocking = [n for n in needs if n['blockingEffect'] == 'BLOCKS_SCORING']
    available = max(Decimal(0), Decimal(5) - cap_used) if cap_used is not None else Decimal(0)
    allocated = min(raw, available) if not blocking else Decimal(0)
    integer = int(allocated.to_integral_value(rounding=ROUND_FLOOR))
    # One PP1 assertion can be Supporting/Moderate/Strong, never Very Strong.
    strength, awarded = ('STRONG', 4) if integer >= 4 else ('MODERATE', 2) if integer >= 2 else ('SUPPORTING', 1) if integer >= 1 else (None, 0)
    status = 'NOT_EVALUATED' if blocking or (excluded_families and not events) else 'MET' if strength else 'NOT_MET'
    if expert_meiosis and not count_strength and any(n['blockingEffect'] == 'MAY_ADD_EVENT' for n in needs):
        status = 'NOT_EVALUATED'
    if not events and not blocking and not ignored:
        need('PP1_NO_QUALIFIED_INFORMATIVE_EVENT', 'LITERATURE', effect='BLOCKS_SCORING'); status = 'NOT_EVALUATED'
    out = {'schemaVersion': VERSION, 'target': target, 'status': status, 'strength': strength,
        'rawSegregationPoints': str(raw), 'remainingLocusCapacity': str(available),
        'cappedPP1Points': str(allocated), 'awardedPP1Points': awarded,
        'unallocatedRemainder': str(allocated - Decimal(awarded)),
        'events': list(events.values()), 'needs': list({n['needId']:n for n in needs}.values()),
        'ignored': ignored, 'cautions': cautions, 'context': context, 'ledger': ledger,
        'clinicalAssessments': clinical_assessments,
        'businessPublishable': False, 'scopeStatement': 'SOURCE_BOUND_RESEARCH_EVALUATION_NOT_WORKBENCH_OR_CLINICAL_ADOPTION'}
    if expert_meiosis:
        out['meiosisAssessment'] = {'informativeMeioses': count, 'qualifiedFamilyCount': len(meiosis_families),
                                    'countStrength': count_strength, 'thresholds': policy['thresholds'],
                                    'familyIndependenceEvidence': independence_evidence}
    return out
