"""Qualify a traceable exact-disease assertion with an explicit final criteria list.

Project automatic scope: source classification is retained, never recomputed or
upgraded. Comparison-dependent lists remain unresolved until their dependencies
can be checked. This is not a blanket ClinVar-star or pathogenic-label shortcut.
"""
from datetime import date, datetime
from copy import deepcopy
import base64
import re
from pm3_partner_dependencies import audit
from model_literature_contract import digest

CLASSES={'Pathogenic':'PATHOGENIC','Likely pathogenic':'LIKELY_PATHOGENIC',
         'Uncertain significance':'VUS','Benign':'BENIGN','Likely benign':'LIKELY_BENIGN'}
EXPERT_STATUSES={'reviewed by expert panel','practice guideline'}
CRITERION_TOKEN = r'\b(?:PVS1|PS[1-4]|PM[1-6]|PP[1-5]|BA1|BS[1-4]|BP[1-7])(?:_[A-Za-z_]+)?\b'
# Without a dependency resolver, automatic scope is intrinsic criteria only.
# Case-count/segregation/de novo evidence may involve another allele and needs
# an event-level dependency analysis which is not supplied by a code list.

def criteria_tail_use(text):
    """Qualify only bounded suffix forms; never discard contrary list use.

    This classifies use of the explicit list, not the partner or specification.
    Uninterpreted suffixes remain source statements requiring review.
    """
    verb = r'(?:use[ds]?|using|appl(?:y|ied|ies|ying)|adopt(?:ed)?|follow(?:ed)?|consider(?:ed)?)'
    auxiliary = r'(?:(?:be|been|being|ever|actually|previously|yet|have)\s+){0,4}'
    for sentence in re.split(r'(?<=[.!?;])\s+|[\r\n]+', text.strip()):
        normalized = ' '.join(sentence.lower().split()).strip()
        if not normalized:
            continue
        refers = re.search(r'\b(?:criteria|criterion|list|codes|basis|classification)\b', normalized)
        denied = (re.search(r'\b(?:not|never|without)\s+'+auxiliary+verb+r'\b', normalized)
                  or re.search(r'\bnone\s+of\s+(?:(?:these|those|the|listed)\s+){0,2}(?:criteria|codes|list)\b.{0,100}\b'+verb+r'\b', normalized))
        uncertain = (re.search(r'\b(?:may|might|could|will|would|plan to|planned to|propose to)\s+'+auxiliary+verb+r'\b', normalized)
                     or re.search(r'\b(?:unknown|unclear|uncertain|undetermined|partial|preliminary)\b', normalized))
        if refers and denied:
            return 'CONFLICT', sentence, 'PM3_PARTNER_CRITERIA_LIST_USE_CONFLICT'
        if refers and uncertain:
            return 'UNKNOWN', sentence, 'PM3_PARTNER_CRITERIA_LIST_USE_NOT_ESTABLISHED'
        # Recovery is bounded: normal method prose or a separate population
        # observation does not negate list use. Arbitrary prose is not ignored.
        method = re.fullmatch(r'(?:the )?(?:variant )?classification (?:was |is |has been )?'
            r'(?:performed|assessed|evaluated) (?:using|following|according to|based on) .+', normalized)
        population = re.fullmatch(r'(?:this |the )?variant (?:was |is |has been )?'
            r'(?:not observed|not found|absent) in (?:the )?(?:population databases?|gnomad)[.!]?', normalized)
        if not method and not population:
            return 'UNKNOWN', sentence, 'PM3_PARTNER_CRITERIA_LIST_SUFFIX_UNINTERPRETED'
    return 'VERIFIED', '', ''


def expert_approval_note(row, prefix, tail):
    """Recognize a trailing source receipt separately from the declared list."""
    if row.get('reviewStatus') != 'reviewed by expert panel':
        return None
    approval = re.fullmatch(r'\(Classification approved by (?:the )?([^()]{1,180}) on '
                            r'([A-Za-z]+ [1-9][0-9]?, [0-9]{4})\)\.?', tail.strip())
    submitter = row.get('sourceAccession', {}).get('SubmitterName', '').removesuffix(', ClinGen')
    if not approval or approval[1] != submitter:
        return None
    try:
        if datetime.strptime(approval[2], '%B %d, %Y').date() != date.fromisoformat(row['dateLastEvaluated']):
            return None
    except (KeyError, ValueError, TypeError):
        return None
    conclusion = re.search(r'(?:^|(?<=\.))\s*In summary,? this variant meets (?:the )?criteria to be classified as '
                           r'(pathogenic|likely pathogenic) for [^\r\n.]{1,256} based on (?:the )?'
                           r'(?:[A-Za-z0-9.-]{1,40}-specific )?$', prefix)
    if not conclusion or conclusion[1] != row.get('sourceClassification', '').lower():
        return None
    return prefix[:conclusion.start()]


def complete_criteria_sentence(row):
    """Reuse the shared parser on one source-bound complete list sentence.

    Later method/population prose is not a criterion token. Other criterion
    codes or declarations outside that sentence keep the result unresolved.
    """
    statements = row.get('criteriaStatements', [])
    if len(statements) != 1 or statements[0].get('status') != 'UNPARSED_SOURCE_STATEMENT':
        return
    from pm3_partner_sources import applied_criteria_statements
    marker = 'ACMG/AMP criteria applied'
    statement = statements[0]; text = statement.get('originalText', '')
    if text.count(marker) != 1:
        return
    start = text.find(marker)
    colon = text.find(':', start+len(marker))
    end = text.find('.', colon+1) if colon >= 0 else -1
    if end < 0 or not text[end+1:].strip():
        return
    comment = next((c for c in row.get('comments', []) if c.get('xpath') == statement.get('xpath') and c.get('text') == text), None)
    if comment is None:
        return
    tail = text[end+1:]
    narrative = expert_approval_note(row, text[:start], tail)
    if narrative is None:
        if re.search(CRITERION_TOKEN, text[:start]+' '+tail):
            return
        use, evidence, reason = criteria_tail_use(tail)
        if use != 'VERIFIED':
            row['criteriaListUse'] = {'status': use, 'reasonCode': reason, 'statement': evidence,
                'sourceLocator': comment['xpath'], 'originalText': text,
                'completeCommentSha256': digest(text.encode('utf-8'))}
            return
        start = 0
    parsed = applied_criteria_statements([{**comment, 'text': text[start:end+1]}], row.get('vcv', '').split('.')[0])
    if len(parsed) != 1 or parsed[0].get('status') != 'EXPLICIT_SOURCE_LIST':
        return
    if narrative is not None:
        listed = {c['original'] for c in parsed[0]['criteria']}
        for sentence in re.split(r'(?<=\.)\s+', narrative):
            mentions = set(re.findall(CRITERION_TOKEN, sentence))
            if mentions and (mentions - listed or re.search(CRITERION_TOKEN, re.sub(r'\((' + CRITERION_TOKEN + r')\)', '', sentence))
                    or re.search(r'\b(?:(?:not|never|without)\s+(?:been\s+)?(?:met|applied|used|assigned)|'
                                 r'considered|hypothetical|proposed|excluded|inapplicable)\b', sentence, re.I)):
                return
    parsed[0].update(originalText=text, sourceTextSlice={'start': start, 'end': end+1},
                     completeCommentSha256=digest(text.encode('utf-8')), qualificationMethod='SOURCE_BOUND_COMPLETE_LIST_SENTENCE_V1')
    row['originalCriteriaStatements'] = deepcopy(statements)
    row['criteriaStatements'] = parsed


def condition_ids(nodes,names=None):
    result=set();proofs=[]
    def walk(node):
        if not isinstance(node,dict):return
        if node.get('tag')=='XRef':
            a=node.get('attributes',{});db=a.get('DB','').upper();value=a.get('ID','')
            if db=='MONDO':
                value=value if value.startswith('MONDO:') else 'MONDO:'+value
                if re.fullmatch('MONDO:[0-9]{7}',value):result.add(value)
            elif db=='ORPHANET' and names is not None:
                match=re.fullmatch(r'(?:ORPHA|Orphanet:)?([1-9][0-9]*)',value)
                if match:
                    mapped=names.lookup('Orphanet:'+match[1]);proofs.extend(mapped)
                    result.update(p['mapping']['subject_id'] for p in mapped)
        for child in node.get('children',[]):walk(child)
    for node in nodes:walk(node)
    return result,proofs

def source_route(row):
    """A star or organisation name alone does not establish an independent basis.

    Nonexpert adoption still requires the complete, source-bound final list and
    the same dependency audit as the expert path. No classification is computed.
    """
    if row.get('reviewStatus') in EXPERT_STATUSES:
        return 'EXPERT_OR_GUIDELINE',[]
    if row.get('reviewStatus')!='criteria provided, single submitter':
        return None,['PM3_PARTNER_SOURCE_REVIEW_STATUS_UNSUPPORTED']
    acc=row.get('sourceAccession',{})
    bound=(isinstance(acc,dict) and re.fullmatch(r'[1-9][0-9]*',acc.get('OrgID',''))
           and bool(acc.get('SubmitterName','').strip())
           and row.get('scv')==acc.get('Accession','')+'.'+acc.get('Version','')
           and re.fullmatch(r'SCV[0-9]+\.[1-9][0-9]*',row.get('scv',''))
           and re.fullmatch(r'VCV[0-9]+\.[1-9][0-9]*',row.get('vcv',''))
           and re.fullmatch(r'[a-f0-9]{64}',row.get('sourceSha256',''))
           and bool(row.get('sourceLocator')))
    if not bound:return None,['PM3_PARTNER_SUBMITTER_SOURCE_BINDING_INCOMPLETE']
    statements=row.get('criteriaStatements',[])
    for statement in statements:
        if (statement.get('sourceId')!=row['vcv'].split('.')[0]
                or not statement.get('originalText')
                or not statement.get('xpath','').startswith(row['sourceLocator']+'/Classification[')):
            return None,['PM3_PARTNER_SUBMITTER_SOURCE_BINDING_INCOMPLETE']
    return 'SOURCE_CRITERIA_PROVIDED_VERIFIED_BASIS',[]


def qualify(sources,context,names=None,target=None,dependency_resolver=None,case_observation=None):
    out={'classification':'UNKNOWN','independentClassification':'UNKNOWN','businessPublishable':False,
         'method':'EXACT_DISEASE_SOURCE_CRITERION_DEPENDENCY_AUDIT_V6','sourceAssertions':[],'issues':[],
         'assertionAssessments':[],
         'ruleRequirement':'PARTNER_CLASSIFICATION_MUST_NOT_USE_TARGET_VARIANT_EVIDENCE',
         'automaticScope':'TRACEABLE_CRITERIA_PROVIDED_OR_EXPERT_EXPLICIT_INTRINSIC_FINAL_LIST'}
    if context.get('qualification')!='VERIFIED':
        return {**out,'issues':['PM3_PARTNER_DISEASE_CONTEXT_UNRESOLVED']}
    if sources.get('status')!='EXACT_SOURCE_ASSERTIONS_AVAILABLE':
        return {**out,'issues':['PM3_PARTNER_CLASSIFICATION_SOURCE_UNAVAILABLE']}
    disease=context['diseaseId']
    matching=[]
    for original in sources['assertions']:
        if original.get('recordStatus')!='current':continue
        ids,proofs=condition_ids(original.get('conditions',[]),names)
        if disease not in ids:continue
        if len(ids)!=1:
            return {**out,'issues':['PM3_PARTNER_DISEASE_MAPPING_AMBIGUOUS']}
        row=deepcopy(original)
        complete_criteria_sentence(row)
        if proofs:row['diseaseMappingProof']=proofs
        matching.append(row)
    out['sourceAssertions']=deepcopy(matching)
    if not matching:return {**out,'issues':['PM3_PARTNER_EXACT_DISEASE_ASSERTION_MISSING']}
    labels={r.get('sourceClassification') for r in matching}
    out['sourceClassificationLabels']=sorted(label for label in labels if isinstance(label,str))
    # Do not silently choose a convenient submitter across a conflicting record.
    # P vs LP is a confidence distinction within the pathogenic category. Keep
    # the lower PM3 phase-unknown weight whenever either source reports LP.
    mixed_pathogenic=labels=={'Pathogenic','Likely pathogenic'}
    if len(labels)!=1 and not mixed_pathogenic:
        return {**out,'independentClassification':'CONFLICT','issues':['PM3_PARTNER_DISEASE_CLASSIFICATION_CONFLICT']}
    label='Likely pathogenic' if mixed_pathogenic else next(iter(labels))
    if label not in CLASSES:return {**out,'issues':['PM3_PARTNER_CLASSIFICATION_LABEL_UNSUPPORTED']}
    out['sourceClassification']=CLASSES[label]
    out['classificationSelection']='P_LP_CONSERVATIVE_LOWER_WEIGHT' if mixed_pathogenic else 'SOURCE_LABEL_UNCHANGED'
    eligible=[];limitations=set()
    for row in matching:
        route,reasons=source_route(row);dependencies=[]
        try:
            if date.fromisoformat(row.get('dateLastEvaluated',''))>date.today():raise ValueError()
        except (ValueError,TypeError):
            reasons.append('PM3_PARTNER_ASSERTION_DATE_UNSUPPORTED')
        use = row.get('criteriaListUse', {})
        if use.get('status') in {'CONFLICT', 'UNKNOWN'}:
            reasons.append(use['reasonCode'])
        lists=row.get('criteriaStatements',[])
        if len(lists)!=1 or lists[0].get('status')!='EXPLICIT_SOURCE_LIST':
            reasons.append('PM3_PARTNER_COMPLETE_CRITERIA_LIST_MISSING')
        else:
            codes={c.get('code') for c in lists[0].get('criteria',[])}
            if not codes or None in codes:reasons.append('PM3_PARTNER_COMPLETE_CRITERIA_LIST_MISSING')
            else:
                dependencies=audit(lists[0],target,case_observation if route == 'EXPERT_OR_GUIDELINE' else None)
                if dependency_resolver is not None:
                    dependencies=[dependency_resolver(d) for d in dependencies]
                # Report every unresolved criterion, not just the first category.
                for dependency in dependencies:reasons.extend(dependency['issues'])
        reasons=sorted(set(reasons))
        out['assertionAssessments'].append({'scv':row['scv'],'status':'UNRESOLVED' if reasons else 'VERIFIED',
                                            'issues':reasons,'criterionDependencies':dependencies,
                                            'qualificationRoute':route})
        limitations.update(reasons)
        if not reasons:eligible.append(row['scv'])
    if not eligible:
        conflict = any(r.get('criteriaListUse', {}).get('status') == 'CONFLICT' for r in matching)
        return {**out,'independentClassification':'CONFLICT' if conflict else 'UNKNOWN',
                'issues':sorted(limitations) or ['PM3_PARTNER_INDEPENDENT_EXPERT_BASIS_NOT_ESTABLISHED']}
    return {**out,'classification':CLASSES[label],'independentClassification':'VERIFIED',
            'qualifiedScvs':eligible,'diseaseId':disease}

def attach(observations,names=None,sources=None,identity=None):
    originals = observations.setdefault('partnerSourceOriginals', {})
    for case in observations.get('cases',[]):
        for row in case.get('resolvedObservations',[]):
            if row.get('reportedObservation',{}).get('phase') == 'CIS':
                continue
            matches = row.get('policySelection', {}).get('matches', [])
            if len(matches) == 1 and matches[0].get('reviewedCaseRule'):
                continue
            if 'partner' in row.get('identities',{}):
                if sources is not None and identity is not None:
                    from pm3_partner_graph import PartnerDependencyGraph
                    graph=PartnerDependencyGraph(sources,identity,names,row['identities'].get('target'),
                        row['reportedObservation']['geneSymbol'],row.get('diseaseContext',{}))
                    case = row['reportedObservation'] if len(matches) == 1 and matches[0].get('independentPp4') else None
                    row['partnerQualification']=graph.assess(row.get('partnerClassificationSources',{}),
                        row['identities']['partner'], case)
                else:
                    row['partnerQualification']=qualify(row.get('partnerClassificationSources',{}),row.get('diseaseContext',{}),names,
                                                       row.get('identities',{}).get('target'))
                # Preserve exact originals for stricter published partner conditions.
                # This performs no new acquisition and grants no approval by itself.
                if sources is not None:
                    pins = {a.get('sourceSha256') for a in row.get('partnerClassificationSources', {}).get('assertions', [])}
                    for unused, raw, spec in getattr(sources, 'records', []):
                        if spec['sha256'] in pins:
                            originals[spec['sha256']] = base64.b64encode(raw).decode('ascii')
                    for spec in row.get('partnerClassificationSources', {}).get('acquisition', {}).get('records', []):
                        if spec['sha256'] in pins and spec['sha256'] not in originals:
                            from core_local_identity import pinned
                            raw = pinned(sources.root, spec, 4 * 1024 * 1024)
                            originals[spec['sha256']] = base64.b64encode(raw).decode('ascii')
