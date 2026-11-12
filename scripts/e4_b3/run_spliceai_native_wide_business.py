"""Explicit 10 kb selected-transcript RAW adapter using the installed official function.

The legacy 4,999 bp adapter and binding remain byte-for-byte untouched. This
entry has a separate runtime identity and never presents a legacy score as wide.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import run_spliceai_native_business as selected

engine = selected.engine
require = selected.require
DISTANCE = 10000

def identity(reference):
    return json.loads(json.dumps({'schemaVersion':'spliceai-native-business-identity-v1','version':'1.2.0',
            'adapterSha256':engine.raw.sha256_file(Path(__file__)),
            'selectedAdapterSha256':engine.raw.sha256_file(Path(selected.__file__)),
            'engine':engine.runtime_identity(reference),
            'associationMethod':'SELECTED_GENCODE_CUSTOM_ANNOTATION_AND_QUALIFIED_WINDOW_V1',
            'executionDistanceBp':DISTANCE,'mask':0,'legacyRuntimeIdentityReused':False,
            'engineDefaultDistanceOverridden':True,'refAltScores':'OFFICIAL_MODEL_OUTPUT_AT_REPORTED_DELTA_POSITIONS'}))

def validate(request):
    require(isinstance(request,dict) and type(request.get('distanceBp')) is int
            and request['distanceBp']==DISTANCE,'NATIVE_WIDE_DISTANCE_REQUIRED')
    equivalent=copy.deepcopy(request);equivalent['distanceBp']=4999
    selected.validate_selected_request(equivalent)
    return request

def parse_wide_annotation(raw):
    """Parse the official ten VCF fields with this entry's explicit 10 kb bound.

    The pinned legacy helper intentionally accepts only its 4,999 bp contract.
    """
    fields=raw.split('|');require(len(fields)==10,'SPLICEAI_INFO_FIELD_COUNT_INVALID')
    require(all(x!='.' for x in fields[2:]),'SPLICEAI_SCORE_MISSING')
    scores=dict(zip(('DS_AG','DS_AL','DS_DG','DS_DL'),fields[2:6]))
    positions=dict(zip(('DP_AG','DP_AL','DP_DG','DP_DL'),map(int,fields[6:])))
    require(all(abs(x)<=DISTANCE for x in positions.values()),'SPLICEAI_DELTA_POSITION_OUT_OF_RANGE')
    return {'allele':fields[0],'geneSymbol':fields[1],'deltaScores':scores,'deltaPositions':positions,
            'maximumRawScore':max(scores.values(),key=engine.raw.Decimal)}

def evaluate(variant, annotator, official, ordinal, annotation_sha):
    view=copy.copy(annotator)
    for field in ('genes','chroms','strands','tx_starts','tx_ends'):
        setattr(view,field,getattr(annotator,field)[[ordinal]])
    for field in ('exon_starts','exon_ends'):
        setattr(view,field,[getattr(annotator,field)[ordinal]])
    expected=variant['selectedAnnotation']
    actual={'geneSymbol':str(view.genes[0]),'contig':str(view.chroms[0]),'strand':str(view.strands[0]),
            'start':int(view.tx_starts[0]),'end':int(view.tx_ends[0]),
            'exons':[[int(s),int(e)] for s,e in zip(view.exon_starts[0],view.exon_ends[0])]}
    require(all(actual[k]==expected[k] for k in actual),'NATIVE_SELECTED_ANNOTATION_PARSE_MISMATCH')
    a=variant['allele'];record=SimpleNamespace(chrom=a['contig'],pos=a['position'],ref=a['reference'],alts=(a['alternate'],))
    chrom=official.normalise_chrom(a['contig'],list(view.ref_fasta.keys())[0])
    start=a['position']-(5000+DISTANCE);length=10000+2*DISTANCE+1
    require(start>=1,'REFERENCE_WINDOW_INCOMPLETE')
    bases=view.ref_fasta[chrom][start-1:start-1+length].seq.upper()
    require(len(bases)==length,'REFERENCE_WINDOW_INCOMPLETE')
    require(bases[5000+DISTANCE]==a['reference'],'REFERENCE_ALLELE_MISMATCH')
    # Observe the official outputs, without a second inference or a replacement algorithm.
    captured=[];originals=[]
    for model in view.models:
        old=model.predict;originals.append(old)
        def capture(*args,_old=old,**kwargs):
            value=_old(*args,**kwargs);captured.append(value.copy());return value
        model.predict=capture
    try: raw_scores=official.get_delta_scores(record,view,DISTANCE,0)
    finally:
        for model,old in zip(view.models,originals):model.predict=old
    require(len(raw_scores)==1 and len(captured)==10,'NATIVE_WIDE_OFFICIAL_RESULT_INCOMPLETE')
    score=parse_wide_annotation(raw_scores[0]);require(score['allele']==a['alternate'],'NATIVE_OUTPUT_ALLELE_MISMATCH')
    import numpy as np
    ref=np.mean(captured[:5],axis=0);alt=np.mean(captured[5:],axis=0)
    if actual['strand']=='-':ref=ref[:,::-1];alt=alt[:,::-1]
    require(ref.shape==(1,2*DISTANCE+1,3) and alt.shape==ref.shape,'NATIVE_WIDE_MODEL_SHAPE_INVALID')
    values={}
    for kind,channel in [('AG',1),('AL',1),('DG',2),('DL',2)]:
        ds=engine.raw.Decimal(score['deltaScores']['DS_'+kind]);dp=score['deltaPositions']['DP_'+kind]
        require(ds.is_finite() and 0<=ds<=1 and type(dp) is int and abs(dp)<=DISTANCE,'NATIVE_OUTPUT_SCORE_INVALID')
        values[kind]={'position':dp,'referenceScore':str(float(ref[0,DISTANCE+dp,channel])),
                      'alternateScore':str(float(alt[0,DISTANCE+dp,channel]))}
    return {'queryId':variant['queryId'],'allele':copy.deepcopy(a),'status':'HIT','exactAlleleMatch':True,
            'annotations':[score],'selectedAnnotation':copy.deepcopy(expected),'annotationRows':[actual],
            'annotationFileSha256':annotation_sha,'distanceBp':DISTANCE,'mask':0,'officialRefAltScores':values,
            'referenceWindow':{'start':start,'length':length,'sha256':hashlib.sha256(bases.encode('ascii')).hexdigest()}}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ('reference-fasta','output','batch-request','binding'):p.add_argument('--'+flag,type=Path,required=flag in ('reference-fasta','output'))
    p.add_argument('--readiness-only',action='store_true');a=p.parse_args()
    require(not a.output.exists(),'NATIVE_OUTPUT_ALREADY_EXISTS');current=identity(a.reference_fasta)
    if a.readiness_only:engine.write_new_json(a.output,current);return
    require(a.binding is not None and a.batch_request is not None,'NATIVE_REQUEST_AND_BINDING_REQUIRED')
    require(a.binding.stat().st_size<=65536 and engine.raw.load_json_object(a.binding)==current,'NATIVE_RUNTIME_BINDING_MISMATCH')
    require(a.batch_request.stat().st_size<=1024*1024,'NATIVE_REQUEST_TOO_LARGE')
    request=validate(engine.raw.load_json_object(a.batch_request))
    file=a.output.with_name('selected-annotations.tsv');equivalent=copy.deepcopy(request);equivalent['distanceBp']=4999
    sha=selected.write_selected_annotations(equivalent,file)
    ordinals={v['queryId']:i for i,v in enumerate(request['variants'])}
    result=engine.execute(request,a.reference_fasta,lambda v,ann,official:evaluate(v,ann,official,ordinals[v['queryId']],sha),file)
    require(engine.raw.sha256_file(file)==sha and identity(a.reference_fasta)==current,'NATIVE_RUNTIME_CHANGED')
    result.update(resultSchemaVersion=selected.SCHEMA,scope='RAW_FACTS_REQUIRE_JAVA_ASSOCIATION',
                  annotationFileSha256=sha,identity=current,request=request)
    engine.write_new_json(a.output,result)

if __name__=='__main__':main()
