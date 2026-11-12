"""Adapt the source-bound missing-fact loop to the existing public-paper job.

Selection is service-owned. Exact configured ClinVar can supply planning identity,
but cannot make a paper case match it; source/context checks still run each round.
"""
from pathlib import Path
import time

from model_literature_contract import canonical,digest,parse
from model_transport import bounded,atomic
from bundle_model_contract import require
from workbench_literature_targets import allele
from literature_allele_identity import read_record
from core_local_identity import pinned
from denovo_reading_loop import run as read_loop,safe_error


def bootstrap_order(packets,targets):
    aliases=[a.split(':')[-1] for t in targets for a in t.get('variantAliases',[])]
    def rank(i):
        content=parse(packets[i]['messages'][1]['content'])
        units=[u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds']]
        text=' '.join(u['text'] for u in units)
        return (-sum(a in text for a in aliases),-int(any(w in text.lower() for w in ('case ','patient ','proband'))),i)
    return sorted(range(len(packets)),key=rank)


def bind_target(core,selected,root,context):
    values=core['values']; known=values.get('/context/allele') or values.get('/groups/variant/resolvedAllele')
    gene=values.get('/groups/gene/symbol')
    matches=[]
    for row in selected:
        a=allele(row.get('allele'))
        if not a or gene and row['geneSymbol']!=gene or known and known!=a:
            continue
        hgnc=values.get('/context/hgncId')
        if not hgnc:
            identities=[]
            for spec in context.get('identity',{}).get('clinvar',[]):
                record=read_record(pinned(root,spec,16*1048576),spec['sha256'],spec['accession'])
                if record['allele']==a and record['geneSymbol']==row['geneSymbol']:
                    identities.append(record['hgncId'])
            if len(set(identities))!=1:continue
            hgnc=identities[0]
        policies=[s['scope'] for s in context.get('contexts',[]) if s['scope']['hgncId']==hgnc]
        if len(policies)!=1:continue
        scope=policies[0]
        if any(values.get('/context/'+k) not in (None,scope[k]) for k in ('diseaseId','inheritance')):continue
        aliases=row.get('variantAliases',[])
        require(len(aliases)<=64,'READING_ALIAS_LIMIT')
        matches.append({'allele':a,'geneSymbol':row['geneSymbol'],'variantAliases':aliases,
                        'hgncId':hgnc,'diseaseId':scope['diseaseId'],'inheritance':scope['inheritance']})
    unique={canonical(m):m for m in matches}
    require(len(unique)==1,'READING_SELECTED_TARGET_UNRESOLVED_OR_AMBIGUOUS')
    return next(iter(unique.values()))


def pairs_from_loop(folder,result):
    pairs={}
    candidates=[(p,p.parent/'review') for p in folder.glob('seed-*/extraction.json')]
    candidates += [(p,folder/('chunk-'+str(parse(p.read_bytes())['binding']['chunkIndex'])+'-review'))
                   for p in folder.glob('chunk-*-extraction/artifact.json')]
    accepted={e['chunkIndex']:e for e in result['checkpoint']['entries'] if e['status'] in ('REVIEWED','INCOMPLETE')}
    for original,review in candidates:
        raw=bounded(original,8*1048576);index=parse(raw)['binding']['chunkIndex']
        spec=accepted.get(index)
        if spec and digest(raw)==spec['artifactSha256'] and (review/'artifact.json').is_file():
            require(digest((review/'artifact.json').read_bytes())==spec['reviewArtifactSha256'],'READING_REVIEW_PIN_CHANGED')
            pairs[index]=(original,review)
    require(set(pairs)==set(accepted),'READING_ACCEPTED_ARTIFACT_MISSING')
    return pairs


def advance(library,source,config,config_path,budget,work,result,item,save,deadline,allow_model,core_context,options):
    state={'schemaVersion':'denovo-workflow-reading-v1','cases':[],'issues':[],
           'criterionDecision':'NOT_RUN','absenceProven':False}
    item['needReading']=state
    try:
        require(item.get('candidateIntake') and item.get('coreEvaluation'),'READING_REVIEWED_SEED_UNAVAILABLE')
        require(item.get('sourceReview',{}).get('status')=='REPLAYED_NOT_SCORED','READING_SOURCE_AUDIT_REQUIRED')
        root=Path(core_context['root']);context=core_context['config']
        work=Path(work);pmcid=source['pmcid'];pin=source['bundleSha256']
        evaluation=parse(bounded(work/item['coreEvaluation']['path'],24*1048576))
        seeds={r['chunkIndex']:(work/f"{pmcid}-chunk-{r['chunkIndex']}"/'artifact.json',
                              work/f"{pmcid}-source-review-{r['chunkIndex']}") for r in item['chunks']}
        original_chunks={r['chunkIndex']:r for r in item['chunks']}
        for number,(case_key,core) in enumerate(sorted(evaluation.items())):
            try:target=bind_target(core,options['targets'],root,context)
            except (ValueError,OSError) as error:
                state['cases'].append({'caseKey':case_key,'stopReason':safe_error(error),'requirementsSatisfied':False})
                continue
            folder=work/f'{pmcid}-need-{number}'
            left=int(deadline-time.monotonic()-3)
            require(left>0,'READING_TIME_LIMIT')
            outcome=read_loop(library,pmcid,pin,case_key,target,config,config_path,budget,
                {'root':root,**options['contextSpec']},folder,list(seeds.values()),allow_model=allow_model,
                maximum_chunks=options['maximumChunks'],
                maximum_calls=max(0,options['maximumCalls']-result['modelAttempts']),timeout_seconds=min(3600,left))
            for key in ('modelCalls','modelAttempts','chargedOrReservedMicroCreditsThisRun'):
                result[key]+=outcome[key]
            result['estimatedMicroCreditsThisRun']+=outcome['chargedOrReservedMicroCreditsThisRun']
            if outcome['modelAttempts']:
                result['modelChargeStatus']='LEDGER_CHARGED_OR_RESERVED_NOT_PLATFORM_BILL'
            state['cases'].append({'caseKey':case_key,'stopReason':outcome['stopReason'],
                'requirementsSatisfied':outcome.get('finalFeedback',{}).get('requirementsSatisfied',False),
                'feedback':outcome.get('finalFeedback'),'plan':outcome.get('readingPlan'),
                'rounds':outcome['rounds'],'modelCalls':outcome['modelCalls'],
                'resultSha256':digest((folder/'result.json').read_bytes())})
            seeds=pairs_from_loop(folder,outcome)
            intake=bounded(folder/outcome['candidateIntake']['path'],16*1048576)
            facts=parse(bounded(folder/outcome['assessedFacts']['path'],32*1048576))
            atomic(work/f'{pmcid}-candidate-intake.json',parse(intake))
            item['candidateIntake']['sha256']=digest(intake)
            rows={c['coreEvaluation']['caseKey']:c['coreEvaluation'] for c in facts['cases']}
            atomic(work/f'{pmcid}-core-evaluation.json',rows)
            item['coreEvaluation']['sha256']=digest(canonical(rows))
            item['coreEvaluation']['readyCases']=sum(r['status']=='READY_FOR_EVALUATION' for r in rows.values())
            item['coreMaterials']=[c['display'] for c in rows.values()]
            final_round=(folder/outcome['assessedFacts']['path']).parent
            for field,part in (('coreMaterial','extraction'),('coreReview','review'),('coreContext','context')):
                index_path=final_round/'core'/part/'index.json'
                item[field]['path']=str(index_path.relative_to(work)).replace('\\','/')
                item[field]['sha256']=digest(index_path.read_bytes())
            atomic(work/f'{pmcid}-cumulative-cases.json',facts)
            item['cumulativeCases'].update(sha256=digest(canonical(facts)),uniqueCases=len(facts['cases']),
                proposals=sum(len(c.get('candidateProvenance',[])) for c in facts['cases']),
                cases=[{'caseLabel':c['caseLabel'],'issues':c['issues'],
                    'fieldLimitations':c.get('fieldLimitations',[]),
                    'alleleIdentityIssues':c['alleleIdentity']['issues'],
                    'targetTesting':c['targetTesting']['status'],'parentage':c['parentage']['status']}
                    for c in facts['cases']])
            new_rounds={r['chunkIndex']:r for r in outcome['rounds']}
            item['chunks']=[{**original_chunks.get(i,{}),'pmcid':pmcid,'bundleSha256':pin,
                'chunkIndex':i,'chunkCount':item['plannedChunks'],'artifactSha256':digest(p.read_bytes()),
                'review':parse(p.read_bytes())['review'],'cacheHit':original_chunks[i]['cacheHit'] if i in original_chunks
                else new_rounds.get(i,{}).get('extractionCacheHit',False)}
                for i,(p,_) in sorted(seeds.items())]
            handled={e['chunkIndex']:e for e in outcome['checkpoint']['entries']}
            item['unprocessedChunks']=[{'chunkIndex':i,'reasonCode':
                'NEED_READING_'+handled[i]['status'] if i in handled else 'NEED_READING_NOT_PROCESSED'}
                for i in range(item['plannedChunks']) if i not in seeds]
            item['sourceReview'].update(reviewedTargets=len(seeds),pendingTargets=[])
            item['completeModelInputProcessed']=len(seeds)==item['plannedChunks']
            item['completeTargetExtraction']=item['completeModelInputProcessed'] and all(
                e['status']=='REVIEWED' for e in handled.values())
            resumable=outcome['stopReason'] in ('READING_CHUNK_LIMIT','DENOVO_BUNDLE_READING_CALL_LIMIT',
                'DENOVO_BUNDLE_READING_TIME_LIMIT','DENOVO_BUNDLE_READING_MODEL_DISABLED')
            item['continuation']={'eligible':resumable,'reasonCode':outcome['stopReason'],
                'newTargets':len(set(seeds)-set(original_chunks)),
                'reusedTargets':sum(c['cacheHit'] for c in item['chunks']),
                'completedTargets':len(seeds),'pendingTargets':len(item['unprocessedChunks']),
                'maximumAccumulatedTargets':64}
            item['issues']=[i for i in item['issues'] if i not in (
                'NEED_READING_PLANNER_PENDING','MODEL_DISABLED_NO_PAID_CALL','MODEL_BATCH_LIMIT_UNPROCESSED_CHUNKS')]
            save()
            if outcome['issues']:
                state['issues'].extend(outcome['issues'])
                if not resumable:
                    item['issues'].extend(outcome['issues']);result['issues'].extend(outcome['issues'])
                break
        state['status']='MATERIAL_READY' if state['cases'] and all(c['requirementsSatisfied'] for c in state['cases']) else 'PARTIAL'
    except (ValueError,OSError,KeyError,TypeError) as error:
        state['status']='PARTIAL';state['issues'].append(safe_error(error))
        item['issues'].append(safe_error(error));result['issues'].append(safe_error(error))
    save()
