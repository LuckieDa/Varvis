"""Backend-invocable PM3 fixed-public-paper task. No discovery or clinical publication.

Uses the existing workflow's library, model configuration and shared ledger.
Does not create a replacement budget or turn on daily model/network settings.
"""
import argparse
from pathlib import Path
import re
from bundle_model_contract import require
from model_literature_contract import canonical,parse,digest
from model_transport import bounded,config as read_model_config
from pm3_literature_contract import validate_target
from process_pm3_paper import process


def execute(request_path, request_sha256, config_path, output, reading_feedback=None, reading_feedback_sha256=None,
            local_first_pass=False):
    raw=bounded(request_path,65536)
    require(digest(raw)==request_sha256,'PM3_TASK_REQUEST_PIN_MISMATCH')
    request=parse(raw)
    discovery=isinstance(request,dict) and request.get('schemaVersion')=='pm3-public-discovery-task-v1'
    fields={'schemaVersion','target','selectedAllele','selectionSha256'}
    if isinstance(request,dict) and 'processingContinuation' in request:
        fields.add('processingContinuation')
        origin=request['processingContinuation']
        require(isinstance(origin,dict) and set(origin)=={'parentTaskId','parentResultSha256'}
                and all(isinstance(v,str) and re.fullmatch(r'[a-f0-9]{64}',v) for v in origin.values()),
                'PM3_PROCESSING_CONTINUATION_INVALID')
    if discovery and 'discoveryContinuation' in request:fields.add('discoveryContinuation')
    require(isinstance(request,dict) and set(request)==(fields if discovery else fields|{'pmcid','bundleSha256'})
            and request['schemaVersion'] in ('pm3-public-paper-task-v1','pm3-public-discovery-task-v1'),'PM3_TASK_SCHEMA')
    validate_target(request['target'])
    require((discovery or re.fullmatch(r'PMC[1-9][0-9]*',request['pmcid'])) and
            all(isinstance(request[k],str) and re.fullmatch(r'[a-f0-9]{64}',request[k]) for k in (('selectionSha256',) if discovery else ('bundleSha256','selectionSha256'))),
            'PM3_TASK_BINDING_INVALID')
    allele=request['selectedAllele']
    require(isinstance(allele,dict) and set(allele)=={'assembly','contig','position','reference','alternate'}
            and allele['assembly']=='GRCh38' and isinstance(allele['contig'],str)
            and re.fullmatch(r'[1-9]|1[0-9]|2[0-2]',allele['contig']) and type(allele['position']) is int
            and 1<=allele['position']<=300000000 and all(isinstance(allele[k],str) and re.fullmatch(r'[ACGT]{1,4096}',allele[k])
            for k in ('reference','alternate')) and allele['reference']!=allele['alternate'],'PM3_SELECTED_ALLELE_INVALID')
    config_raw=bounded(config_path,16384)
    config=parse(config_raw)
    require(config.get('schemaVersion')=='denovo-public-literature-workflow-v1' and config.get('enabled') is True,
            'WORKFLOW_DISABLED')
    chunks=config.get('pm3MaximumNewChunksPerPaper',32)
    require(type(chunks) is int and 1<=chunks<=256,'PM3_CHUNK_BUDGET')
    root=Path(config['projectRoot']).resolve(strict=True)
    def local(value):
        path=Path(value)
        require(not path.is_absolute(),'PM3_RELATIVE_CONFIG_PATH_REQUIRED')
        resolved=(root/path).resolve()
        require(resolved.is_relative_to(root/'local'),'CONFIG_PATH_OUTSIDE_LOCAL')
        return resolved
    destination=Path(output).resolve()
    require(destination.is_relative_to(root/'local') and not destination.exists(),'PM3_NEW_LOCAL_OUTPUT_REQUIRED')
    library,budget,model_path=(local(config[k]) for k in ('library','modelBudget','modelConfig'))
    allow_model=config.get('allowModel') is True
    stepwise=config.get('pm3NeedDrivenReading') is True
    feedback={}
    if reading_feedback is not None:
        require(stepwise, 'PM3_READING_LOOP_DISABLED')
        feedback_path=Path(reading_feedback).resolve(strict=True)
        require(feedback_path.is_relative_to(root/'local'),'PM3_READING_FEEDBACK_PATH')
        feedback_raw=bounded(feedback_path,262144)
        require(digest(feedback_raw)==reading_feedback_sha256,'PM3_READING_FEEDBACK_PIN')
        feedback=parse(feedback_raw)
        require(feedback.get('schemaVersion') in {'pm3-reading-feedback-v1','pm3-reading-feedback-v2'}
                and feedback.get('requestSha256')==request_sha256
                and isinstance(feedback.get('papers'),list) and len(feedback['papers'])<=200,
                'PM3_READING_FEEDBACK_BINDING')
    # Initial round reconstructs caches before deciding whether new work is
    # needed. The server alone binds feedback; no facts are accepted from it.
    cache_pass=stepwise and feedback.get('cacheOnly',False) is True
    model_enabled=allow_model
    if cache_pass or local_first_pass:allow_model=False
    allow_network=config.get('allowPublicNetwork') is True and not local_first_pass
    if stepwise:chunks=1
    model=read_model_config(model_path) if allow_model else parse(bounded(model_path,16384))
    identity=None;context=None
    if config.get('coreContext'):
        from core_context_review import load_config
        from core_local_identity import LocalIdentity
        context,_=load_config(root,config['coreContext'])
        identity=LocalIdentity(root,context['identity'])
    from pm3_reviewed_material import reviewed_papers
    reviewed, review_issues=reviewed_papers(library,request['target'],None if discovery else request['pmcid'])
    if discovery:
        from process_pm3_discovery import run
        result=run(root,library,local(config['searchCache']),budget,model,model_path,request['target'],allele,destination,
                   allow_network=allow_network,allow_model=allow_model,context=context,identity=identity,
                   local_only=local_first_pass,reviewed=reviewed,
                   continuation=feedback.get('discoveryContinuation',request.get('discoveryContinuation')),maximum_chunks=chunks,
                   priority_options=config.get('readingPriority'),
                   **({'stepwise':True,'reading_feedback':feedback.get('papers',[])} if stepwise else {}))
    else:
        result=process(library,request['pmcid'],request['bundleSha256'],request['target'],model,model_path,budget,destination,
                       allow_model=allow_model,identity_resolver=identity,selected_allele=allele,maximum_chunks=chunks,
                       manual_reviews=reviewed.get(request['pmcid'],{}).get(request['bundleSha256'],()),
                       **({'stepwise':True,'reading_feedback':next((p for p in feedback.get('papers',[])
                           if p.get('pmcid')==request['pmcid']),None)} if stepwise else {}))
    if stepwise:
        result['readingCachePass']=cache_pass
        result['readingModelEnabled']=model_enabled
    if review_issues:result['manualReviewIssues']=review_issues
    result['selectionSha256']=request['selectionSha256']
    result['requestSha256']=request_sha256
    result['workflowConfigSha256']=digest(config_raw)
    if 'processingContinuation' in request:
        result['reassemblyOrigin']={**request['processingContinuation'],
            'method':'CURRENT_VALIDATION_OVER_SOURCE_BOUND_RAW_CACHES','savedScoreAdopted':False}
    disease=None
    if config.get('pm3DiseaseContext') is not None:
        from pm3_disease_context import DiseaseContext
        disease= DiseaseContext(root,config['pm3DiseaseContext'])
        for paper in result.get('papers',[]) if discovery else [result]:
            if 'observations' in paper:disease.attach(paper['observations'])
    if config.get('pm3SpecificationContext') is not None:
        from pm3_specification_context import SpecificationContext
        specifications=SpecificationContext(root,config['pm3SpecificationContext'])
        for paper in result.get('papers',[]) if discovery else [result]:
            if 'observations' in paper:
                specifications.attach(paper['observations'], disease, identity)
                if config.get('pm3JointPopulation') is not None:
                    from pm3_joint_sites import attach as supply_joint_population
                    supply_joint_population(root,config['pm3JointPopulation'],paper['observations'])
    from pm3_partner_qualification import attach as qualify_partners
    if context is not None:
        from pm3_partner_sources import PartnerSources
        from contextlib import nullcontext
        spec=config.get('pm3PartnerSources')
        if spec is not None:
            from literature_search_transport import SearchTransport
            manager=SearchTransport(local(spec['cache']),destination/'partner-transport',online=allow_network)
        else: manager=nullcontext(None)
        with manager as transport:
            supply=None
            if spec is not None:
                from pm3_clinvar_supply import ClinVarSupply
                supply=ClinVarSupply(root,spec,destination/'partner-acquisition',transport)
            sources=PartnerSources(root,context['identity'],supply)
            from pm3_published_partner import attach as qualify_published_partners, reviewed_dependencies
            review_pin = config.get('pm3ReviewedAssertionDependencies')
            try:
                reviews = reviewed_dependencies(root, review_pin)
                review_issue = 'PM3_PARTNER_DEPENDENCY_REVIEW_UNAVAILABLE' if review_pin is None else None
            except (ValueError, OSError, KeyError, TypeError):
                reviews, review_issue = {}, 'PM3_PARTNER_DEPENDENCY_REVIEW_UNAVAILABLE'
            for paper in result.get('papers',[]) if discovery else [result]:
                if 'observations' in paper:
                    sources.attach(paper['observations'])
                    qualify_partners(paper['observations'],disease.names if disease else None,sources,identity)
                    qualify_published_partners(paper['observations'], sources, reviews, review_issue)
            if transport is not None:
                result['partnerAcquisition']={'networkRequests':transport.requests,'localQueries':supply.queries,
                                               'events':transport.events,'businessPublishable':False}
    else:
        for paper in result.get('papers',[]) if discovery else [result]:
            if 'observations' in paper:qualify_partners(paper['observations'],disease.names if disease else None)
    from model_transport import atomic
    atomic(destination/'result.json',result)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request',type=Path,required=True)
    parser.add_argument('--request-sha256',required=True)
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--reading-feedback',type=Path)
    parser.add_argument('--reading-feedback-sha256')
    parser.add_argument('--local-first-pass',action='store_true')
    args=parser.parse_args()
    try:
        result=execute(args.request,args.request_sha256,args.config,args.output,
                       args.reading_feedback,args.reading_feedback_sha256,args.local_first_pass)
        print(canonical({k:result[k] for k in ('status','modelCalls','businessPublishable')}).decode())
    except (ValueError,OSError,KeyError,TypeError) as error:
        from public_literature_workflow import safe_error
        print(canonical({'status':'FAILED','reasonCode':safe_error(error)}).decode())
        raise SystemExit(2)

if __name__=='__main__':main()
