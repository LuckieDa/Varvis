"""Business source supply: exact selected targets, configured originals, bounded public citations.

Only source preparation; no patient fields, model, policy changes, or scoring.
"""
import argparse
import base64
import json
from pathlib import Path
from fetch_public_literature import collect
from register_materials import read, sha, unique
from prepare_literature import prepare
from literature_allele_identity import read_record

VERSION='denovo-selected-literature-supply-1.0.0'
FILES=('supply_selected_literature.py','fetch_public_literature.py','register_materials.py',
       'prepare_literature.py','literature_allele_identity.py')
MAX_BYTES=25_165_824


def process(request_raw, work, cache, online=False):
    if not 0<len(request_raw)<=MAX_BYTES:
        raise ValueError('DENOVO_SUPPLY_REQUEST_LIMIT')
    req=json.loads(request_raw,object_pairs_hook=unique)
    if not isinstance(req,dict) or set(req)!={'schemaVersion','targets','records','documents','originals','implementation'} or req['schemaVersion']!='denovo-selected-literature-request-v1':
        raise ValueError('DENOVO_SUPPLY_REQUEST_SCHEMA')
    implementation={name:sha((Path(__file__).parent/name).read_bytes()) for name in FILES}
    if req['implementation']!=implementation or not all(isinstance(req[k],list) for k in ['targets','records','documents']) or len(req['targets'])>50 or len(req['records'])>100 or len(req['documents'])>32:
        raise ValueError('DENOVO_SUPPLY_REQUEST_INVALID')
    if not isinstance(req['originals'],dict):raise ValueError('DENOVO_SUPPLY_ORIGINAL_INVALID')
    originals={}
    for pin,value in req['originals'].items():
        raw=base64.b64decode(value,validate=True)
        if not 0<len(raw)<=1_048_576 or sha(raw)!=pin:
            raise ValueError('DENOVO_SUPPLY_ORIGINAL_INVALID')
        originals[pin]=raw
    if len(originals)>100 or sum(map(len,originals.values()))>16_777_216:
        raise ValueError('DENOVO_SUPPLY_ORIGINAL_LIMIT')
    work=Path(work);work.mkdir(exist_ok=False)
    # Existing registered papers take priority over a network request or cache lookup.
    local={};documents=[];doc_by_pmc={}
    for d in req['documents']:
        raw=originals[d['sha256']];p=prepare(raw,d['pmcid'],d['sha256']);pmid=p['identifiers'].get('pmid')
        if pmid:
            if pmid in local and local[pmid]!=(d['pmcid'],raw):raise ValueError('DENOVO_SUPPLY_LOCAL_PAPER_CONFLICT')
            local[pmid]=(d['pmcid'],raw)
        if d['pmcid'] in doc_by_pmc:raise ValueError('DENOVO_SUPPLY_LOCAL_PAPER_CONFLICT')
        documents.append(d);doc_by_pmc[d['pmcid']]=d
    targets=[]
    for t in req['targets']:
        if not isinstance(t,dict) or set(t)!={'hgncId','allele'}:raise ValueError('DENOVO_SUPPLY_TARGET_SCHEMA')
        if t not in targets:targets.append(t)
    records=[];issues=[]
    for r in req['records']:
        try:
            if not isinstance(r,dict) or set(r)!={'sha256','vcv'}:raise ValueError('DENOVO_SUPPLY_RECORD_SCHEMA')
            raw=originals[r['sha256']];parsed=read_record(raw,r['sha256'],r['vcv'])
            target={'hgncId':parsed['hgncId'],'allele':parsed['allele']}
            if target not in targets:continue
            path=r['sha256']+'.xml'
            if not (work/path).exists():(work/path).write_bytes(raw)
            records.append({'path':path,'sha256':r['sha256'],'vcv':r['vcv'],**target})
        except (ValueError,KeyError,TypeError) as e:
            issues.append({'reasonCode':'DENOVO_SUPPLY_CONFIGURED_VCV_INVALID','sourceSha256':r.get('sha256') if isinstance(r,dict) else None})
    request={'schemaVersion':'denovo-public-citation-request-v1','records':records}
    (work/'request.json').write_text(json.dumps(request),encoding='utf-8')
    receipt=collect(work/'request.json',work/'retrieved',cache=cache,online=online,local_papers=local)
    for r,queried in zip(records,receipt['records']):
        for citation in queried.get('citations',[]):
            if citation['status'] not in {'SOURCE_READY_FOR_QUALIFICATION','DUPLICATE_CITATION'} or 'sha256' not in citation:continue
            pmcid,pin=citation['pmcid'],citation['sha256'];raw=read(work/'retrieved/papers'/(pmcid+'.xml'),pin)
            originals[pin]=raw
            if pmcid in doc_by_pmc and doc_by_pmc[pmcid]['sha256']!=pin:raise ValueError('DENOVO_SUPPLY_LOCAL_PAPER_CONFLICT')
            if pmcid not in doc_by_pmc:
                d={'sourceId':'supply-'+pin,'pmcid':pmcid,'sha256':pin,'alleles':[]};documents.append(d);doc_by_pmc[pmcid]=d
            allele={'sha256':r['sha256'],'vcv':r['vcv']}
            if allele not in doc_by_pmc[pmcid]['alleles']:doc_by_pmc[pmcid]['alleles'].append(allele)
    if len(documents)>32 or len(originals)>100 or sum(map(len,originals.values()))>16_777_216:
        raise ValueError('DENOVO_SUPPLY_OUTPUT_LIMIT')
    summaries=[]
    for target in targets:
        codes=[];matched=False
        for r,q in zip(records,receipt['records']):
            if r['hgncId']!=target['hgncId'] or r['allele']!=target['allele']:continue
            matched=True;codes+=q.get('issues',[])
            if 'reasonCode' in q:codes.append(q['reasonCode'])
            codes += [c['reasonCode'] for c in q.get('citations',[]) if 'reasonCode' in c]
        if not matched:codes.append('DENOVO_PUBLIC_NO_EXACT_CITATION_SOURCE')
        if issues:codes.append('DENOVO_SUPPLY_CONFIGURED_VCV_INVALID')
        summaries.append({**target,'reasonCodes':sorted(set(codes))})
    result={'schemaVersion':VERSION,'requestSha256':sha(request_raw),'implementation':implementation,'documents':documents,
            'originals':{pin:base64.b64encode(raw).decode() for pin,raw in sorted(originals.items())},'targets':summaries,
            'retrieval':receipt,'issues':issues,'modelCalls':0,'clinicalApproval':'NONE'}
    output=json.dumps(result,ensure_ascii=False,separators=(',',':')).encode()
    if len(output)>MAX_BYTES:raise ValueError('DENOVO_SUPPLY_OUTPUT_LIMIT')
    return output


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['request','output','work','cache']:parser.add_argument('--'+name,required=True,type=Path)
    parser.add_argument('--allow-public-network',action='store_true');a=parser.parse_args()
    with a.request.open('rb') as stream:raw=stream.read(MAX_BYTES+1)
    output=process(raw,a.work,a.cache,a.allow_public_network)
    with a.output.open('xb') as stream:stream.write(output)
    print(json.dumps({'resultBytes':len(output),'modelCalls':0}))


if __name__=='__main__':main()
