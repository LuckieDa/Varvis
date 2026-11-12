"""Fixed public PM1 controls through production HTTP; the caller owns the isolated JVM lifecycle."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from scripts.ps1_pm5.network_acceptance import Client, joint_declaration, multipart, patient_context_id, save
from scripts.revel.revel_network_acceptance import measured, raw_response, verify_reuse

PROFILE='acmg-explicit-business-pm1-hnf1a-v1'
CRITERIA={'ACMG_AMP_2015:'+c for c in ('PP3','BP4','BA1','BP7','PM2','PS1','PM5','PVS1','PM1')}
CONTROLS={('12',120988841,'C','T'):'SUPPORTING',('12',120993601,'G','A'):'MODERATE',('12',120999580,'G','A'):'NONE'}
CONTEXT=[dict(geneSymbol='HNF1A',hgncId='HGNC:11621',mondoId='MONDO:0015967',moi='AUTOSOMAL_DOMINANT')]


def sha(data): return hashlib.sha256(data).hexdigest()
def key(a): return a.get('chromosome',a.get('contig')),a['position'],a['reference'],a['alternate']


def validate_vcf(raw):
    if len(raw)>10000 or not raw.startswith(b'##fileformat=VCF') or b'##reference=GRCh38' not in raw: raise ValueError('Fixed small GRCh38 public VCF required')
    lines=raw.decode().splitlines();header=next(l.split('\t') for l in lines if l.startswith('#CHROM'))
    if header[9:]!=['synthetic-proband']: raise ValueError('Synthetic sample required')
    observed=[]
    for line in lines:
        if line.startswith('#'):continue
        f=line.split('\t');observed.append((f[0].removeprefix('chr'),int(f[1]),f[3],f[4]))
        if len(f)!=10 or dict(zip(f[8].split(':'),f[9].split(':'),strict=True))['GT']!='0/1':raise ValueError('Fixed heterozygous genotype required')
    if observed!=list(CONTROLS):raise ValueError('Public panel changed')


def declaration(options):
    if set(options['enabledCriteria'])!=CRITERIA:raise ValueError('Inherited rules plus PM1 required')
    ad={r['candidateId']:key(r['allele']) for r in options['candidates'] if r['candidateIdentity']['modeOfInheritance']=='AD'}
    if len(ad)!=3 or set(ad.values())!=set(CONTROLS):raise ValueError('Each fixed control must reach original formal AD Top-N')
    request=joint_declaration(options,'synthetic-proband',10,patient_context_id(options['analysisId']),confirmed_contexts=CONTEXT)
    if {h['hypothesis']['candidateId'] for h in request['hypotheses']}!=set(ad):raise ValueError('Confirmable source-backed contexts required')
    for h in request['hypotheses']:
        if (h['transcript']['accession'],h['transcript']['version'])!=('ENST00000257555',11):raise ValueError('Versioned HNF1A transcript required')
    return request


def outcomes(saved,options):
    if saved['candidateIds']!=options['candidateIds']:raise ValueError('Formal order changed')
    formal={r['candidateId']:r for r in options['candidates']};results=[]
    for c in saved['candidates']:
        for attempt in c.get('attempts',[]):
            row=attempt.get('pm1',{});v=row.get('evaluation',{})
            if not v:continue
            allele=key(row['context']['variant']['evidenceQueryAllele']);r=formal[c['candidateId']]
            if allele!=key(r['allele']) or r['candidateIdentity']['modeOfInheritance']!='AD':raise ValueError('PM1 formal source identity mismatch')
            strength=CONTROLS[allele]
            if v['appliedStrength']!=strength or v['evaluationResult']['status']!=('NOT_MET' if strength=='NONE' else 'MET'):raise ValueError('Fixed PM1 control did not reach expected branch')
            results.append(dict(candidateId=c['candidateId'],allele=allele,strength=strength,status=v['evaluationResult']['status']))
    if len(results)!=3 or {tuple(r['allele']) for r in results}!=set(CONTROLS):raise ValueError('Three distinct PM1 outcomes required')
    return results


def main():
    p=argparse.ArgumentParser();p.add_argument('stage',choices=('prepare','evaluate','reuse'));p.add_argument('--acceptance-root',type=Path,required=True)
    p.add_argument('--instance-id',required=True);p.add_argument('--head',required=True);p.add_argument('--port',type=int,required=True);p.add_argument('--vcf',type=Path)
    p.add_argument('--confirm-prepared',action='store_true');p.add_argument('--timeout',type=int,default=3600)
    args=p.parse_args();uuid.UUID(args.instance_id.removeprefix('instance-'))
    instance=args.acceptance_root.resolve()/'network'/args.instance_id;out=instance/'pm1-client'
    if args.stage=='prepare':out.mkdir(exist_ok=False)
    if not out.is_dir() or (out/(args.stage+'-report.json')).exists():raise ValueError('Preserve prior stage artifacts')
    client=Client(f'http://127.0.0.1:{args.port}',out,args.timeout,joint=True,expected_port=args.port)
    current=client.call(args.stage+'-instance','GET','/__varvis_ps1pm5_instance')
    expected=dict(instanceId=args.instance_id,sourceHead=args.head,branch='feature/pm1-hnf1a',profileId=PROFILE,port=args.port,mode='JOINT',transport='REAL_NETWORK_HTTP',database='PRIVATE_H2')
    if any(current.get(k)!=v for k,v in expected.items()) or Path(current['instanceRoot']).resolve()!=instance:raise ValueError('Owned instance/source identity mismatch')
    if set(current['executionProfile']['enabledCriteria'])!=CRITERIA:raise ValueError('Nine exact rules required')
    before=client.call(args.stage+'-calls-before','GET','/__varvis_ps1pm5_calls');report=dict(stage=args.stage,status='BLOCKED',pageCovered=False,instance=current)
    try:
        statepath=out/'network-state.json'
        if args.stage=='prepare':
            if args.vcf is None:raise ValueError('Public VCF required')
            raw=args.vcf.read_bytes();validate_vcf(raw);(out/'fixed-public.vcf').write_bytes(raw)
            readiness=client.call('preflight','GET','/variant-analyses/preflight?genomeAssembly=GRCh38')
            if not readiness.get('applicationReady') or readiness.get('blockingErrors'):raise ValueError('Original readiness blocked')
            body,kind=multipart(raw,'synthetic-proband','STANDARD');job=client.call('upload','POST','/variant-analyses',body,kind)
            state={k:current[k] for k in ('instanceId','sourceHead','configurationSha256','executionProfile')}
            state.update(analysisId=job['id'],processIdBeforeRestart=current['processId'],vcfSha256=sha(raw),clientSha256=sha(Path(__file__).read_bytes()));save(statepath,state)
            deadline=time.monotonic()+args.timeout
            while job['status']!='READY':
                if job['status'] in ('FAILED','CANCELLED') or time.monotonic()>=deadline:raise ValueError('Original analysis failed or timed out')
                time.sleep(2);job=client.call('analysis-poll','GET','/variant-analyses/'+job['id'])
            save(out/'formal-analysis.json',job);analysis=job['id']
            options=client.call('input-options','GET',f'/variant-analyses/{analysis}/acmg/input-options?topN=10');(out/'input-options.json').write_bytes(raw_response(client))
            rows=[]
            for row in options['candidates']:
                client.call('formal-'+row['candidateId'],'GET',f"/variant-analyses/{analysis}/candidates/{row['candidateId']}");rows.append(raw_response(client))
            (out/'formal-topn.json').write_bytes(b'[\n'+b',\n'.join(rows)+b'\n]\n')
            prepared=declaration(options);save(out/'prepared-declaration.json',prepared);state['preparedSha256']=sha((out/'prepared-declaration.json').read_bytes());save(statepath,state)
            report.update(status='FORMAL_PM1_CONTROLS_PREPARED',analysisId=analysis,hypotheses=len(prepared['hypotheses']))
        else:
            state=json.loads(statepath.read_bytes())
            if any(state[k]!=current[k] for k in ('instanceId','sourceHead','configurationSha256','executionProfile')) or state['clientSha256']!=sha(Path(__file__).read_bytes()):raise ValueError('Saved identity changed')
            prepared=(out/'prepared-declaration.json').read_bytes()
            if sha(prepared)!=state['preparedSha256']:raise ValueError('Declaration changed')
            path=f"/variant-analyses/{state['analysisId']}/acmg/runs"
            if args.stage=='evaluate':
                if not args.confirm_prepared or (out/'saved-business.json').exists():raise ValueError('Explicit fresh evaluation required')
                saved,savedraw,event=measured(client,'first-post','POST',path,prepared);(out/'saved-business.json').write_bytes(savedraw);state['runId']=saved['runId'];save(statepath,state)
                report['firstPostCallIncrement']=event['callIncrement']
            else:
                if state['processIdBeforeRestart']==current['processId']:raise ValueError('A real owned JVM restart is required')
                savedraw=(out/'saved-business.json').read_bytes();saved=json.loads(savedraw)
            prefix='' if args.stage=='evaluate' else 'rebuilt-'
            _,getraw,getevent=measured(client,prefix+'saved-get','GET',path+'/'+saved['runId'])
            _,postraw,postevent=measured(client,prefix+'repeat-post','POST',path,prepared)
            verify_reuse(savedraw,getraw,postraw,getevent,postevent)
            report.update(status='PM1_CONTROLS_AND_SAVE_REUSE_VERIFIED',runId=saved['runId'],responseSha256=sha(savedraw),
                          savedGetCallIncrement=getevent['callIncrement'],repeatPostCallIncrement=postevent['callIncrement'],jvmRestartCovered=args.stage=='reuse',
                          controls=outcomes(saved,json.loads((out/'input-options.json').read_bytes())))
    except Exception as exc:
        report.update(status='BLOCKED',failure=repr(exc));raise
    finally:
        after=client.call(args.stage+'-calls-after','GET','/__varvis_ps1pm5_calls');report['callIncrement']={k:after[k]-before[k] for k in after}
        save(out/(args.stage+'-report.json'),report);print(json.dumps(report))


if __name__=='__main__':main()
