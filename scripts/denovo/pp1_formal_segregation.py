"""Source-qualified formal PP1 adapter; legacy simple events stay separate.

Parameters come from the pinned context, family observations from the existing
source replay. The internal v2 result deliberately cannot masquerade as v1
transmission points. Public frontend method dispatch is an integration task.
"""
from collections import defaultdict
from decimal import Decimal, localcontext
from fractions import Fraction

from model_literature_contract import canonical, digest
from pp1_literature_contract import require
from pp1_formal_likelihood import likelihood
from pp1_formal_observations import bind_formal_observations
from pp1_formal_policy import FORMAL_METHOD, validate_model

VERSION = 'pp1-segregation-evaluation-v2'
RULE = 'VARVIS_CSLR_TYPED_FOUNDERS_V1/PMID19563646/PMID32720330/PMID38103548'
GENOTYPES = {'NONCARRIER': 0, 'HETEROZYGOUS': 1, 'HOMOZYGOUS': 2}
RELEVANT_FIELDS = {'F02.5', 'F04.1', 'F04.2', 'F05.2', 'F05.3', 'F05.4', 'F06.3', 'F06.4', 'F06.5', 'F07.1', 'F07.3', 'F08.1'}
UNRESOLVED_WITHOUT_FIELD = {'PP1_PARTNER_IDENTITY_UNRESOLVED', 'PP1_SUBJECT_SCOPE_UNRESOLVED', 'PP1_SOURCE_AUDIT_UNRESOLVED'}


def fraction(value):
    return Fraction(Decimal(value['numerator'])) / Fraction(Decimal(value['denominator']))


def text(value):
    return format(value, 'f')


def log_fraction(value, base=Decimal(10)):
    if value == 0:
        return None
    with localcontext() as ctx:
        ctx.prec = 64
        return (Decimal(value.numerator).ln() - Decimal(value.denominator).ln()) / base.ln()


def calibrate(bf, remaining):
    """Exact strength comparisons; approximate logs are display values only."""
    require(isinstance(bf, Fraction) and bf >= 0 and isinstance(remaining, Decimal)
            and remaining.is_finite() and 0 <= remaining <= 5, 'FORMAL_CALIBRATION_INPUT_INVALID')
    raw = log_fraction(bf, Decimal('2.08'))
    # Preserve exact integer log identities; never snap a merely nearby BF.
    # Other logarithms are explicitly display approximations.
    for exact_power in range(-8, 9):
        if bf == Fraction(52, 25) ** exact_power:
            raw = Decimal(exact_power)
            break
    positive = max(Decimal(0), raw) if raw is not None else Decimal(0)
    strength, awarded = None, 0
    for points, label in ((4, 'STRONG'), (2, 'MODERATE'), (1, 'SUPPORTING')):
        if remaining >= points and bf >= Fraction(52, 25) ** points:
            strength, awarded = label, points
            break
    return {'status': 'MET' if strength else 'NOT_MET', 'strength': strength,
            'awardedPP1Points': awarded, 'rawCalibratedPoints': text(raw) if raw is not None else None,
            'positiveCalibratedPoints': text(positive), 'allocatedPP1Points': text(min(remaining, positive)),
            'log10BayesFactor': text(log_fraction(bf)) if bf else None,
            'log10BayesFactorStatus': 'FINITE' if bf else 'NEGATIVE_INFINITY',
            'strengthDetermination': 'EXACT_FRACTION_THRESHOLDS', 'displayLogPrecision': 64}


def evaluate(qualified, context, ledger):
    require(qualified and len(qualified) <= 32, 'QUALIFIED_SOURCE_LIMIT')
    target = qualified[0]['target']
    require(all(q['target'] == target for q in qualified), 'EVALUATION_TARGET_MISMATCH')
    scope = {k: target[k] for k in ('geneSymbol', 'diseaseId', 'inheritance')}
    out = {'schemaVersion': VERSION, 'method': FORMAL_METHOD, 'modelVersion': '1.0.0',
           'ruleClause': RULE, 'target': target, 'status': 'NOT_EVALUATED', 'strength': None,
           'awardedPP1Points': 0, 'familyAssessments': [], 'events': [], 'needs': [], 'ignored': [],
           'context': context, 'ledger': ledger, 'businessPublishable': False,
           'scopeStatement': 'SOURCE_BOUND_CONDITIONAL_GENOTYPE_LIKELIHOOD_NOT_CLINICAL_ADOPTION',
           'calibration': {'base': '2.08', 'locusCapacity': '5', 'maximumPP1Strength': 'STRONG',
                           'negativeEvidenceAutomaticallyAssignedToBS4': False}}

    def need(code, requirement, family=None, rows=(), expected=None, actual=None, blocking=True, route=None):
        if route is None:
            route = 'LITERATURE' if code in {
                'PP1_FORMAL_ASCERTAINMENT_SOURCE_REQUIRED', 'PP1_FORMAL_PROBAND_REQUIRED',
                'PP1_FORMAL_PROBAND_GENOTYPE_REQUIRED', 'PP1_FORMAL_EXACT_GENOTYPE_REQUIRED',
                'PP1_FORMAL_FOUNDER_GENOTYPE_REQUIRED', 'PP1_FORMAL_RELATION_ENDPOINT_UNRESOLVED',
                'PP1_FORMAL_SIBLING_PARENTAGE_REQUIRED', 'PP1_FORMAL_EXAMINATION_AGE_REQUIRED',
                'PP1_FORMAL_CANDIDATE_ALLOCATION_REQUIRED', 'PP1_CANDIDATE_ASSESSMENT_SCOPE_UNQUALIFIED'
            } else 'KNOWLEDGE' if code == 'PP1_FORMAL_DIAGNOSTIC_DEFINITION_REQUIRED' else 'METHOD_REVIEW'
        item = {'reasonCode': code, 'requirement': requirement, 'scope': family,
                'route': route, 'blockingEffect': 'BLOCKS_SCORING' if blocking else 'MAY_ADD_INFORMATION',
                'knownFacts': [{'kind': r['kind'], 'value': r['value']} for r in rows],
                'sourceLocators': [ref for r in rows for ref in r.get('sourceRefs', [])],
                'expected': expected, 'actual': actual, 'ruleClause': RULE}
        item['needId'] = digest(canonical([code, family, expected, actual]))
        out['needs'].append(item)

    policy = context.get('policy', {})
    if policy:
        require(policy['scope'] == scope, 'EVALUATION_CONTEXT_MISMATCH')
    if context.get('status') != 'QUALIFIED':
        out['needs'].extend(context.get('needs', []))
        if not out['needs']:
            need(context.get('reasonCode', 'PP1_FORMAL_CONTEXT_REQUIRED'), '需要有来源和采用记录的精确疾病正式方法及参数。')
        return out
    if policy.get('method') != FORMAL_METHOD or target['inheritance'] not in {'AD', 'AR'}:
        need('PP1_FORMAL_INHERITANCE_UNSUPPORTED', '本版正式方法支持常染色体AD及单目标AR纯合，不借用简单XLR或复合杂合规则。')
        return out
    if target['allele'].get('contig') not in {str(n) for n in range(1, 23)}:
        need('PP1_FORMAL_AUTOSOMAL_ALLELE_REQUIRED', '当前正式剂量模型只支持常染色体；不能把X/PAR或其他倍性直接套入0/1/2传递。')
        return out
    model = policy.get('formalModel')
    invalid = validate_model(model, target['inheritance'])
    if invalid:
        for issue in invalid:
            need(issue['reasonCode'], issue['requirement'], expected=issue.get('expected'), actual=issue.get('actual'))
        return out
    if ledger is None or ledger.get('status') not in {'KNOWN', 'KNOWN_EMPTY'}:
        need('PP1_PP4_LEDGER_UNKNOWN', '需要当前选择的PP4及共享事实账本；未知不等于零。')
        return out
    require(ledger.get('selectionKey') == target['selectionKey'] and ledger.get('scope') == scope
            and ledger.get('allele') == target['allele'], 'EVALUATION_LEDGER_SCOPE_MISMATCH')
    require(isinstance(ledger.get('eventIds'), list) and isinstance(ledger.get('exclusiveFactIds'), list), 'LEDGER_FACTS_REQUIRED')
    used = Decimal(str(ledger['pp4Points']))
    require(used.is_finite() and 0 <= used <= 5, 'PP4_POINTS_INVALID')
    if ledger['status'] == 'KNOWN_EMPTY':
        require(used == 0 and not ledger['eventIds'] and not ledger['exclusiveFactIds'], 'EMPTY_LEDGER_CONFLICT')
    remaining = Decimal(5) - used
    out['remainingLocusCapacity'] = text(remaining)
    if policy.get('locusModel') == 'HIGH_YIELD_HOMOGENEOUS':
        out.update(status='NOT_MET', rawCalibratedPoints='0', allocatedPP1Points='0')
        out['ignored'].append({'reasonCode': 'PP1_NOT_INCREMENTAL_TO_HIGH_YIELD_PP4'})
        return out
    clinical_review = bind_formal_observations(qualified, policy)
    out['clinicalObservationReview'] = clinical_review
    out['needs'].extend(clinical_review['needs'])
    bound_states = {(r['familyKey'], r['memberKey']): r for r in clinical_review['memberStates']}
    groups = {}
    for q in qualified:
        if not q.get('identity'):
            need(q.get('identityIssue') or 'PP1_TARGET_IDENTITY_UNRESOLVED', '尚缺与当前目标一致的已核原始变异身份。')
            continue
        for key, roster in q['families'].items():
            group = groups.setdefault(key, {'roster': {}, 'rows': {}, 'reports': [], 'unresolved': []})
            for label, member in roster['members'].items():
                if label in group['roster'] and group['roster'][label] != member:
                    need('PP1_FORMAL_MEMBER_IDENTITY_CONFLICT', '同一家系标签对应的成员身份冲突。', key)
                group['roster'][label] = member
            group.setdefault('mechanisms', []).append({
                'packet': q.get('ascertainmentReview'), 'sourceIdentity': q['sourceIdentity'],
                'sourceFamilyLabel': roster.get('sourceFamilyLabel'),
                'observations': [r for r in q['observations'] if r['familyKey'] == key]})
            group['reports'].append(q['sourceIdentity'])
            for row in q['observations']:
                if row['familyKey'] == key:
                    signature = digest(canonical([row['memberKey'], row['kind'], row['value']]))
                    prior = group['rows'].setdefault(signature, dict(row))
                    prior['sourceRefs'] = list({digest(canonical(r)): r for r in prior.get('sourceRefs', []) + row.get('sourceRefs', [])}.values())
            group['unresolved'] += [r for r in q.get('unresolved', []) if r.get('familyKey') in (None, key)]
    for family, group in sorted(groups.items()):
        rows = list(group['rows'].values())
        prior_count = len(out['needs'])
        by = defaultdict(lambda: defaultdict(list))
        for row in rows:
            by[row['memberKey']][row['kind']].append(row)

        def value(member, kind):
            records = by[member][kind]
            values = {canonical(r['value']): r['value'] for r in records}
            if kind == 'genotype' and len(values) > 1:
                precise = set(values) - {canonical('CARRIER')}
                if len(precise) == 1 and canonical('NONCARRIER') not in precise:
                    return values[next(iter(precise))]
            if len(values) > 1:
                need('PP1_FORMAL_SOURCE_CONFLICT', '同一成员的已核资料相互冲突，不能选择有利值。', family, records,
                     expected={'memberKey': member, 'kind': kind, 'uniqueValue': True}, actual=list(values.values()))
                return None
            return next(iter(values.values())) if values else None

        # A complete, audited clinical assertion may be supported by the new
        # observation grammar although the legacy simple-event reader cannot
        # type it. Only that exact claim's old syntax need is superseded here;
        # pending source audits, identity issues and adverse context remain.
        bound_claims = {claim for r in clinical_review['observations'] if r['familyKey'] == family
                        for claim in r['claimIds']}
        for unresolved in group['unresolved']:
            if (unresolved.get('reasonCode') == 'PP1_TYPED_SEMANTICS_UNRESOLVED'
                    and unresolved.get('fieldId') in {'F05.2', 'F05.3', 'F05.4'}
                    and unresolved.get('claimId') in bound_claims):
                continue
            if (unresolved.get('fieldId') in RELEVANT_FIELDS or unresolved.get('fieldId', '').startswith('F09.')
                    or unresolved.get('reasonCode') in UNRESOLVED_WITHOUT_FIELD):
                pending = unresolved.get('reviewStatus') == 'PENDING_ADDITION_REVIEW'
                source_scope = {k: unresolved.get(k) for k in ('claimId', 'familyKey', 'memberKey', 'memberLabel')}
                need('PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW' if pending else 'PP1_FORMAL_SOURCE_SEMANTICS_UNRESOLVED',
                     '正式联合量所需资料尚未核定，不能忽略潜在反例或用模型文字替代。', source_scope,
                     [{'kind': unresolved.get('fieldId'), 'value': unresolved.get('sourceValue'), 'sourceRefs': unresolved.get('sourceRefs', [])}],
                     route='SOURCE_REVIEW' if pending else 'IDENTITY_RESOURCE' if unresolved.get('reasonCode') == 'PP1_PARTNER_IDENTITY_UNRESOLVED' else 'SOURCE_REVIEW')
        candidates = [r for r in rows if r['kind'] == 'candidateScope']
        if not candidates or any(r['value'] != 'SINGLE_REPORTED_CANDIDATE' for r in candidates):
            need('PP1_FORMAL_CANDIDATE_ALLOCATION_REQUIRED', '需要已核合理候选范围；多个候选的位点证据分配不能由本方法默认独占。', family, candidates)
        if any(r['kind'] == 'candidateScopeUnresolved' for r in rows):
            need('PP1_CANDIDATE_SCOPE_CONFLICT_OR_UNRESOLVED', '候选范围有未解决的来源记录。', family,
                 [r for r in rows if r['kind'] == 'candidateScopeUnresolved'])
        if not any(r['kind'] == 'candidateMethod' and r['value'] in policy.get('candidateAssessmentMethods', []) for r in rows):
            need('PP1_CANDIDATE_ASSESSMENT_SCOPE_UNQUALIFIED', '需要与独立疾病政策相容的候选排查方法原件。', family,
                 [r for r in rows if r['kind'] == 'candidateMethod'])
        members = set(group['roster'].values()) - {r['memberKey'] for r in rows if r['memberLabel'] == 'FAMILY'}
        parents = {m: set() for m in members}
        observed, phenotypes, penetrances, probands = {}, {}, {}, []
        for row in rows:
            if row['kind'] in {'parentOf', 'childOf'}:
                parent, child = (row['memberKey'], row['value']) if row['kind'] == 'parentOf' else (row['value'], row['memberKey'])
                if parent not in members or child not in members:
                    need('PP1_FORMAL_RELATION_ENDPOINT_UNRESOLVED', '亲缘关系端点须对应原件内明确成员。', family, [row])
                else:
                    parents[child].add(parent)
        for row in rows:
            if row['kind'] == 'fullSiblingOf':
                a, b = row['memberKey'], row['value']
                if a not in members or b not in members or a == b:
                    need('PP1_FORMAL_RELATION_ENDPOINT_UNRESOLVED', '全同胞关系端点须为两个不同的已核成员。', family, [row])
                elif len(parents[a]) != 2 or len(parents[b]) != 2:
                    need('PP1_FORMAL_SIBLING_PARENTAGE_REQUIRED', '已有全同胞原句，但正式图尚未核定两人的同一双亲；不自动创造亲本。', family, [row],
                         expected='SAME_TWO_VERIFIED_PARENTS', actual={a: sorted(parents[a]), b: sorted(parents[b])})
                elif parents[a] != parents[b]:
                    need('PP1_FORMAL_RELATIONSHIP_CONFLICT', '全同胞声明与已核亲子关系冲突，不能丢弃其中一条。', family, [row],
                         expected='SAME_TWO_VERIFIED_PARENTS', actual={a: sorted(parents[a]), b: sorted(parents[b])})
        for member in sorted(members):
            genotype = value(member, 'genotype')
            if genotype is not None:
                if type(genotype) is not str or genotype not in GENOTYPES:
                    need('PP1_FORMAL_EXACT_GENOTYPE_REQUIRED', '本方法需要明确0/1/2剂量，泛阳性或未定相复合杂合不能代替。', family, by[member]['genotype'])
                else:
                    observed[member] = GENOTYPES[genotype]
            if by[member]['biallelic']:
                need('PP1_FORMAL_COMPOUND_HETEROZYGOTE_UNSUPPORTED', '本版单位点方法不将另一变异或未知相位当作目标剂量2。', family, by[member]['biallelic'])
            if value(member, 'proband') is True:
                probands.append(member)
            clinical, diagnosis = value(member, 'clinical'), value(member, 'diagnosis')
            native = value(member, 'nativePhenotypes') or []
            affected = diagnosis in policy['affectedTerms'] or any(t in policy.get('affectedNativeTableTerms', []) for t in native)
            if by[member]['reportedDiagnosticCriteria'] and not affected:
                need('PP1_FORMAL_DIAGNOSTIC_DEFINITION_REQUIRED', '已有满足诊断标准的原句，但正式方法尚未核定其与本疾病患病定义的映射；不能丢弃该临床信息或当作未报告。', family,
                     by[member]['reportedDiagnosticCriteria'], expected={'memberKey': member, 'definition': policy['affectedTerms']},
                     actual=[r['value'] for r in by[member]['reportedDiagnosticCriteria']])
            if affected and clinical == 'UNAFFECTED':
                need('PP1_CLINICAL_CONFLICT', '疾病状态和明确未患病原件冲突。', family, by[member]['diagnosis'] + by[member]['clinical'])
            state = bound_states.get((family, member))
            if state:
                if ((affected and state['status'] != 'AFFECTED')
                        or (clinical in {'AFFECTED', 'UNAFFECTED'} and clinical != state['status'])):
                    need('PP1_CLINICAL_CONFLICT', '原始状态行与同次临床观察不一致，不能选取有利状态。', family,
                         by[member]['diagnosis'] + by[member]['clinical'], expected=state['status'], actual=clinical or diagnosis)
                phenotypes[member] = state['status']
                phenotype_model = model['phenotypeModel']
                if phenotype_model['kind'] == 'AGE_INDEPENDENT_BINARY_STATUS':
                    probabilities = phenotype_model['probabilities']
                else:
                    age = state['attainedAge']
                    matches = [b for b in phenotype_model['bands'] if age is not None and b['minimumAge'] <= age
                               and (b['maximumAgeExclusive'] is None or age < b['maximumAgeExclusive'])]
                    probabilities = matches[0]['probabilities'] if len(matches) == 1 else None
                    if probabilities is None:
                        need('PP1_FORMAL_EXAMINATION_AGE_REQUIRED', '累计风险需同次状态绑定的检查年龄及对应参数年龄段。', family,
                             [{'kind': 'clinicalObservation', 'value': state, 'sourceRefs': state['sourceRefs']}],
                             expected='SAME_SOURCE_OBSERVATION_STATUS_AND_ATTAINED_AGE', actual=age)
                if probabilities is not None:
                    penetrances[member] = tuple(probabilities[str(g)] for g in range(3))
            elif affected or clinical in {'AFFECTED', 'UNAFFECTED'}:
                need('PP1_FORMAL_OBSERVATION_BINDING_REQUIRED', '已知状态没有可采用的同次临床观察，需重放原始事实；独立状态及年龄行不提供绑定。', family,
                     by[member]['diagnosis'] + by[member]['clinical'] + by[member]['examinationAge'],
                     expected='SOURCE_BOUND_CLINICAL_ASSESSMENT', actual={'memberKey': member}, route='SOURCE_REVIEW')
        mechanisms = []
        for item in group['mechanisms']:
            packet = item['packet']
            valid = (isinstance(packet, dict) and packet.get('schemaVersion') == 'pp1-formal-ascertainment-review-v1'
                     and packet.get('sourceIdentity') == item['sourceIdentity'] and packet.get('target') == target)
            mechanism = packet.get('families', {}).get(family) if valid else None
            conditions = mechanism.get('sufficientConditions', []) if mechanism else []
            allowed = {'PHENOTYPE_BEFORE_GENOTYPING'}
            if model['ascertainment'] == 'CONDITIONAL_SELECTION_AND_REPORTING_INDEPENDENCE':
                allowed.add('SELECTION_AND_REPORTING_INDEPENDENT_OF_TARGET_GIVEN_PHENOTYPE')
            supported = [c for c in conditions if c.get('subjectLabel') == 'FAMILY' and c.get('sourceBasis') in allowed
                         and c.get('claimIds') and c.get('sourceRefs')
                         and all(ref.get('pmcid') == item['sourceIdentity'].get('pmcid')
                                 and ref.get('bundleSha256') == item['sourceIdentity'].get('bundleSha256')
                                 for ref in c['sourceRefs'])
                         and any(r['kind'] == 'formalAscertainment' and r['memberLabel'] == 'FAMILY'
                                 and r['value'] == c['sourceBasis'] and r.get('claimIds') == c.get('claimIds')
                                 and r.get('sourceRefs') == c.get('sourceRefs') for r in item['observations'])]
            same_report = [m['packet'] for m in group['mechanisms'] if m['sourceIdentity'] == item['sourceIdentity']]
            consistent_report = len({canonical(p) for p in same_report}) == 1
            if not (mechanism and consistent_report and mechanism.get('familyKey') == family
                    and mechanism.get('sourceFamilyLabel') == item['sourceFamilyLabel']
                    and not mechanism.get('conflicts') and not mechanism.get('unresolved')
                    and mechanism.get('status') == 'QUALIFIED_SUFFICIENT_CONDITION'
                    and 'FAMILY' in mechanism.get('qualifiedSubjectLabels', []) and supported):
                proof = [{'kind': 'selectionAndReportingMechanism', 'value': mechanism,
                          'sourceRefs': [ref for r in (mechanism or {}).get('records', []) for ref in r.get('sourceRefs', [])]}]
                code = ('PP1_FORMAL_ASCERTAINMENT_GENOTYPE_DEPENDENT' if mechanism and mechanism.get('status') == 'INELIGIBLE'
                        else 'PP1_FORMAL_ASCERTAINMENT_SOURCE_REQUIRED')
                need(code, '需核全家系取样、分型和报告机制。支持句不能覆盖后续按基因型筛选；先证者角色也不能代替。',
                     family, proof, expected={'modelCondition': model['ascertainment'], 'familyMechanismReview': 'QUALIFIED_SUFFICIENT_CONDITION'},
                     actual={'sourceIdentity': item['sourceIdentity'], 'review': mechanism,
                             'bindingValid': valid}, route='METHOD_REVIEW' if mechanism and mechanism.get('status') == 'INELIGIBLE' else 'SOURCE_REVIEW')
            elif mechanism:
                mechanisms.append(mechanism)
        if len(probands) != 1:
            need('PP1_FORMAL_PROBAND_REQUIRED', '正式条件化需要唯一且有来源的先证者及其基因型。', family,
                 [r for r in rows if r['kind'] == 'proband'], expected=1, actual=len(probands))
        elif observed.get(probands[0], 0) == 0:
            need('PP1_FORMAL_PROBAND_GENOTYPE_REQUIRED', '需要来源明确的目标变异携带先证者，不能由其他亲属基因型替代入组锚点。', family,
                 by[probands[0]]['genotype'], expected='TYPED_TARGET_CARRIER_PROBAND', actual=observed.get(probands[0]))
        # Cautions remain adverse observations unless a typed, same-claim
        # consanguinity assertion is represented by this actual pedigree graph.
        consanguineous = [r for r in rows if r['kind'] == 'consanguinity' and r['value'] is True]
        for caution in (r for r in rows if r['kind'] == 'caution'):
            covered = any(set(caution.get('claimIds', [])) & set(c.get('claimIds', [])) for c in consanguineous)
            if not covered:
                need('PP1_FORMAL_RECORDED_CAUTION_UNRESOLVED', '原件中的限制或潜在反证未被当前模型核定，不能静默忽略。', family, [caution])
        active = {m for m in members if parents[m] or any(m in p for p in parents.values()) or m in observed or m in phenotypes or m in probands}
        graph = {m: tuple(sorted(parents[m])) for m in sorted(active)}
        missing_founders = [m for m, ps in graph.items() if not ps and m not in observed]
        if missing_founders:
            need('PP1_FORMAL_FOUNDER_GENOTYPE_REQUIRED', '缺少创始者剂量；不能把未知创始者设为非携带或均匀先验。', family, rows,
                 expected={'typedFounders': sorted(m for m, ps in graph.items() if not ps)}, actual={'untypedFounderKeys': missing_founders})
        if (len(out['needs']) != prior_count or any(n.get('blockingEffect') == 'BLOCKS_SCORING'
                and (n.get('scope') or {}).get('familyKey') in (None, family)
                for n in clinical_review['needs'])):
            continue
        computation = likelihood(graph, observed, phenotypes, penetrances, probands[0])
        if computation['status'] != 'COMPUTED':
            need(computation['reasonCode'], '家系或模型未满足正式计算条件；需核对完整亲缘、创始者基因型和参数，未输出部分评分。', family, rows,
                 expected={'typedFounders': True, 'twoParentsPerNonfounder': True}, actual={'computation': computation})
            continue
        event_id = digest(canonical([target['allele'], scope, family, 'PP1_FAMILY_CONTRIBUTION']))
        clinical_states = [r for r in clinical_review['memberStates'] if r['familyKey'] == family]
        fact_ids = sorted({r['id'] for r in rows} | {a for r in clinical_states for a in r['assessmentIds']})
        legacy_ids = {digest(canonical([target['allele'], scope, family, 'PUBLISHED_AFFECTED_RELATIVES_COUNT']))}
        # Recognize old simple event allocations without trusting a saved score.
        for child, ps in graph.items():
            alternatives = [list(ps), *([p] for p in ps), [probands[0]]]
            for kind in ('AD_AFFECTED_TRANSMISSION', 'AD_UNAFFECTED_TRANSMISSION', 'AR_TRANS_AFFECTED_SIBLING', 'AR_AFFECTED_SIBLING', 'AR_UNAFFECTED_SIBLING'):
                for parent_keys in alternatives:
                    legacy_ids.add(digest(canonical([target['allele'], scope, family, kind, child, sorted(parent_keys)])))
        shared = event_id in ledger['eventIds'] or bool(legacy_ids & set(ledger['eventIds'])) or bool(set(fact_ids) & set(ledger['exclusiveFactIds']))
        record = {'familyKey': family, 'familyContributionId': event_id, 'factIds': fact_ids,
                  'sourceRefs': list({digest(canonical(ref)): ref for r in rows + clinical_states
                                      for ref in r.get('sourceRefs', [])}.values()),
                  'clinicalObservationIds': sorted({r['sourceObservationId'] for r in clinical_states}),
                  'clinicalAssessmentIds': sorted({a for r in clinical_states for a in r['assessmentIds']}),
                  'sourceReports': list({digest(canonical(s)): s for s in group['reports']}.values()),
                  'computation': computation, 'alreadyAllocated': shared,
                  'selectionAndReportingReviews': mechanisms,
                  'typedConsanguinityStatementPresent': bool(consanguineous),
                  'founderIndependenceAssumptionUsed': False}
        out['familyAssessments'].append(record)
        if shared:
            out['ignored'].append({'familyKey': family, 'reasonCode': 'PP1_SHARED_FAMILY_ALREADY_ALLOCATED', 'familyContributionId': event_id})
    if any(n.get('blockingEffect') == 'BLOCKS_SCORING' for n in out['needs']):
        return out
    assessments = out['familyAssessments']
    all_values = [fraction(r['computation']['bayesFactor']) for r in assessments]
    if any(b < 1 for b in all_values) and any(b > 1 for b in all_values):
        need('PP1_FORMAL_FAMILIES_DISAGREE', '所有已核家系均需保留反证，包括已分配贡献的家系；不能先过滤账本再选择有利结论。')
        return out
    records = [r for r in assessments if not r['alreadyAllocated']]
    if records and (ledger['eventIds'] or ledger['exclusiveFactIds']):
        need('PP1_FORMAL_PRIOR_FAMILY_IDENTITY_UNRESOLVED', '已有同作用域的贡献，但当前来源内家系编号不能证明新论文/新家系与旧贡献独立；需先核身份，不能换论文编号再次计分。',
             expected='VERIFIED_FAMILY_IDENTITY_AGAINST_PRIOR_CONTRIBUTIONS',
             actual={'priorEventIds': ledger['eventIds'], 'priorExclusiveFactIds': ledger['exclusiveFactIds']})
        return out
    if not records:
        if out['familyAssessments']:
            out.update(status='NOT_MET', rawCalibratedPoints='0', allocatedPP1Points='0')
        else:
            need('PP1_FORMAL_NO_QUALIFIED_FAMILY', '尚无满足正式模型及来源要求的家系。')
        return out
    if len(records) > 1:
        need('PP1_ADDITIONAL_FAMILY_INDEPENDENCE_REQUIRED', '家系之间独立性未核定，未相乘；只保留一个完整家系的结果。', blocking=False)
    chosen = max(records, key=lambda r: (fraction(r['computation']['bayesFactor']), r['familyKey']))
    chosen['selectedForContribution'] = True
    bf = fraction(chosen['computation']['bayesFactor'])
    out.update(calibrate(bf, remaining))
    out.update(selectedFamilyContributionId=chosen['familyContributionId'],
               bayesFactor=chosen['computation']['bayesFactor'], direction=chosen['computation']['direction'],
               requiredFrontendMethodDispatch=True)
    return out
