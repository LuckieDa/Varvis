"""PS2/PM6 task-1 diagnostic feedback over existing, source-checked core packages.

No scheduler, model call, source qualification, branch selection or score. This
offline entry must receive the trusted context-package pin from its caller;
hash equality is provenance, not permission to adopt clinical evidence.
"""
import argparse
from copy import deepcopy
from pathlib import Path

from core_evaluation_intake import project_package
from core_material_contract import bounded, parse, canonical, digest as object_digest, require
from core_material_review import safe_file
from model_literature_contract import digest

VERSION = 'denovo-reading-feedback-v1'
PUBLIC_ROLES = {'PAPER_PRIMARY', 'PAPER_ATTACHMENT'}

# Search hints describe existing fields, never facts or scientific qualification.
HINTS = {
    'SOURCE': ('来源和可获取范围', ['ARTICLE_IDENTITY', 'ATTACHMENT_MANIFEST'], 'ENGINEERING_PROVENANCE'),
    'CASE': ('病例归属', ['CASE_DESCRIPTION', 'TABLE_HEADER_FOOTNOTE'], 'IDENTITY_AND_PROVENANCE'),
    'IDENTITY': ('目标变异身份', ['VARIANT_TABLE', 'TRANSCRIPT_FOOTNOTE'], 'IDENTITY_AND_PROVENANCE'),
    'TESTING': ('先证者或双亲的目标变异检测', ['GENETIC_RESULTS', 'FAMILY_TABLE', 'ASSAY_METHODS'], 'EXISTING_RULE_INPUT'),
    'PARENTAGE': ('亲子关系状态', ['PARENTAGE_METHODS', 'TRIO_RESULTS'], 'EXISTING_RULE_BRANCH_INPUT'),
    'PHENOTYPE': ('病例临床表现', ['CLINICAL_DESCRIPTION', 'PHENOTYPE_TABLE'], 'EXISTING_RULE_INPUT'),
    'FAMILY': ('病例相关家系信息', ['PEDIGREE_LEGEND', 'FAMILY_HISTORY', 'PARENTAL_PHENOTYPE'], 'EXISTING_RULE_INPUT'),
    'EVENT': ('原始病例和事件独立性', ['COHORT_ORIGIN', 'PRIOR_REPORT_LINK'], 'EXISTING_RULE_AND_DEDUPLICATION'),
    'CONTEXT': ('疾病背景和适用政策', ['QUALIFIED_LOCAL_KNOWLEDGE'], 'EXISTING_POLICY_INPUT'),
    'CONDITIONAL': ('条件触发的补充字段', ['TRIGGER_SPECIFIC_SOURCE'], 'CONDITIONAL_EXISTING_REQUIREMENT'),
    'UNKNOWN': ('尚未映射的要求', [], 'UNMAPPED_IMPLEMENTATION_REQUIREMENT'),
}


def group(path):
    if path.startswith('/groups/paper/'): return 'SOURCE'
    if path.startswith('/groups/overlap/') or path == '/groups/case/origin': return 'EVENT'
    if path.startswith('/groups/case/'): return 'CASE'
    if path.startswith(('/groups/gene/', '/groups/variant/')) or path in ('/context/allele', '/context/hgncId'): return 'IDENTITY'
    if path.startswith('/groups/parentage/'): return 'PARENTAGE'
    if path.startswith('/groups/phenotype/'): return 'PHENOTYPE'
    if path.startswith('/groups/family/') or path == '/conditions/specialFamilyModel': return 'FAMILY'
    if path.startswith(('/groups/testing/', '/groups/proband/', '/groups/mother/', '/groups/father/')): return 'TESTING'
    if path.startswith('/context/'): return 'CONTEXT'
    if path.startswith(('/extensions/', '/conditions/')): return 'CONDITIONAL'
    return 'UNKNOWN'


def route(blocker, field):
    categories = {blocker['category'], *blocker.get('causeCategories', [])}
    code = blocker['code']; codes = {code, *blocker.get('causeCodes', [])}
    if categories & {'CONFLICT', 'DATA_CONFLICT'} or field.get('verification') == 'CONFLICT': return 'CONFLICT_REVIEW'
    if categories & {'SOFTWARE_UNSUPPORTED'}: return 'SOFTWARE_SUPPORT_REQUIRED'
    if categories & {'RULE_NOT_APPLICABLE', 'NOT_APPLICABLE'}: return 'NOT_APPLICABLE'
    if categories & {'EXECUTION_FAILURE'}: return 'EXECUTION_RECOVERY'
    if categories & {'KNOWLEDGE_MISSING', 'RESOURCE_UNAVAILABLE'}: return 'EXTERNAL_RESOURCE'
    if code in {'SOURCE_UNAVAILABLE'} or field.get('availability') == 'SOURCE_UNAVAILABLE': return 'SOURCE_ACQUISITION'
    if categories & {'SOURCE_UNAVAILABLE'}: return 'SOURCE_ACQUISITION'
    if group(blocker['path']) == 'CONTEXT': return 'EXTERNAL_RESOURCE'
    if group(blocker['path']) == 'UNKNOWN': return 'UNMAPPED_REQUIREMENT'
    # Explicit resource errors cannot be solved by rereading identical prose.
    if any(c in {'DENOVO_IDENTITY_LOCAL_RESOURCE_NOT_FOUND', 'DENOVO_LOCAL_IDENTITY_NOT_CONFIGURED'} for c in codes): return 'EXTERNAL_RESOURCE'
    return 'SOURCE_REVIEW' if code == 'FACT_NOT_VERIFIED' or categories & {'REVIEW_REQUIRED'} else 'LITERATURE_READING'


def _feedback(core, report):
    """Internal projection of project_package output. Never accept this as facts."""
    require(report['caseKey'] == core['caseKey'], 'READING_FEEDBACK_CASE_MISMATCH')
    fields = {f['path']: f for f in core['display']['fields']}
    issues = []
    for b in core['blockers']:
        item = deepcopy(b)
        # Preserve every cause category: explain() historically selects the first.
        item['causeCategories'] = sorted({i['category'] for i in report['issues'] if b['path'] in i['paths']})
        issues.append(item)
    bindings = {k: core[k] for k in ('caseKey', 'documentSha256', 'ledgerSha256', 'evaluationKey', 'verifiedFactsKey', 'sourcePins')}
    context = {p: core['values'].get(p) for p in ('/context/allele', '/context/hgncId', '/context/diseaseId', '/context/inheritance', '/context/policyId', '/context/policyVersion')}
    def view(f):
        # Reuse resolved public locators; internal paths/knowledge quotes stay out.
        return {**{k: deepcopy(f.get(k)) for k in ('path', 'value', 'availability', 'verification', 'origin', 'subject')},
                'sourceEvidence': [deepcopy(c) for c in f.get('sourceEvidence', []) if c.get('role') in PUBLIC_ROLES]}
    needs=[]
    for b in issues:
        path=b['path']; family=group(path); field=fields.get(path, {})
        label, locations, basis=HINTS[family]
        related_groups={family} | ({'TESTING', 'PARENTAGE'} if family=='FAMILY' else set())
        related=[view(f) for p,f in fields.items() if p!=path and group(p) in related_groups and f['availability']=='REPORTED' and f['verification']=='VERIFIED']
        scopes=[]
        for explanation in core['display'].get('explanation', {}).get('items', []):
            if path in explanation['fieldPaths']:scopes.extend(deepcopy(explanation['checkedScope']))
        reason_state=('NOT_REPORTED_IN_CHECKED_SCOPE' if b['code']=='NOT_REPORTED_IN_CHECKED_SCOPE' else
                      'NOT_EXTRACTED' if b['code']=='NOT_EXTRACTED' else b['category'])
        needs.append({'needId': object_digest({'bindings':bindings,'path':path,'code':b['code']}),
            'caseKey':core['caseKey'],'fieldPath':path,'group':family,'label':label,
            'reasonCode':b['code'],'causeCodes':deepcopy(b.get('causeCodes', [])),
            'category':b['category'],'causeCategories':b['causeCategories'],'missingState':reason_state,
            'route':route(b,field),'requirementOrigin':basis,'requirementAuthority':'EXISTING_CONTRACT_NOT_NEW_GUIDELINE',
            'requiredForCurrentContract':True,'preferredLocations':locations,
            'observedField':view(field) if field else None,'relatedVerifiedFacts':related,
            'checkedScope':list({canonical(s):s for s in scopes}.values()),
            'absenceMeaning':b.get('meaning','NO_CLAIM_OF_SOURCE_ABSENCE'),
            'effect':'当前必要条件尚未通过；相关事实不能代填该项，评分仍由原评价器决定。'})
    return {'schemaVersion':VERSION, 'bindings':bindings, 'context':context,
            'scope':'EXISTING_CORE_CONTRACT_DIAGNOSTIC_ONLY','criterionDecision':'NOT_RUN',
            'scientificExecution':False,'businessPublishable':False,'autoContinue':False,
            'coreStatus':core['status'],'requirementsSatisfied':not needs,'needs':needs,
            'coverageLimitations':deepcopy(core['coverageLimitations']),
            'scopeStatement':'仅反映既有资料检查；未映射或无法获取不等于原文未报告。无缺项不代表已评分或全文读完。'}


def from_package(package, expected_index_sha256):
    root=Path(package).resolve(strict=True);raw=bounded(root/'index.json')
    require(digest(raw)==expected_index_sha256,'READING_FEEDBACK_INDEX_PIN_MISMATCH')
    index=parse(raw)
    require(index.get('schemaVersion')=='denovo-core-context-package-v1','READING_FEEDBACK_CONTEXT_PACKAGE_REQUIRED')
    require(len(index['cases'])<=128 and len({r['caseKey'] for r in index['cases']})==len(index['cases']),'READING_FEEDBACK_CASE_LIMIT_OR_DUPLICATE')
    reports={}
    for row in index['cases']:
        for name,pin in [('path','sha256'),('ledgerPath','ledgerSha256'),('reportPath','reportSha256')]:
            data=bounded(safe_file(root,row[name]));doc=parse(data)
            actual=object_digest(doc) if name=='ledgerPath' else digest(data)
            require(actual==row[pin],'READING_FEEDBACK_INPUT_PIN_MISMATCH')
            if name=='path':require(doc['caseKey']==row['caseKey'],'READING_FEEDBACK_CASE_MISMATCH')
            if name=='reportPath':reports[row['caseKey']]=doc
    # Existing assessor verifies the field ledger, sources, subjects and citations.
    cores=project_package(root,index)
    return {'schemaVersion':'denovo-reading-feedback-package-v1','indexSha256':expected_index_sha256,
            'pmcid':index['pmcid'],'bundleSha256':index['bundleSha256'],
            'cases':[_feedback(core,reports[key]) for key,core in cores.items()],
            'modelCalls':0,'networkCalls':0,'scientificExecution':False,'businessPublishable':False}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--package',type=Path,required=True);p.add_argument('--index-sha256',required=True)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    result=from_package(a.package,a.index_sha256)
    with a.output.open('xb') as f:f.write(canonical(result)+b'\n')
    print(canonical({'cases':len(result['cases']),'needs':sum(len(c['needs']) for c in result['cases']), 'scientificExecution':False}).decode())


if __name__=='__main__':main()
