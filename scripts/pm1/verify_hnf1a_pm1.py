"""Read-only PM1 verifier: official residue list, independent CDS translation and v2 identities."""
import argparse
import copy
import calendar
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.revel.verify_hnf1a_revel_business import (
    AA, CODE, derive_protein, e1, equal, file_sha, read, require, verify_identity,
)
from scripts.e3_p1a.validate_acmg_rule_registry_v2 import _validate_schema

RES = ROOT / 'varvis-server/src/main/resources/acmg'
PROFILE = RES / 'pm1-hnf1a-business-profile-v1.json'
SOURCE = RES / 'pm1-hnf1a-source-v1.json'
MODEL = RES / 'pvs1-hnf1a-transcript-model-v1.json'
RESIDUES = dict(zip((130,131,132,143,144,145,146,147,148,149,155,156,157,158,203,204,205,206,263,264,265,270,271,272,273),
                    'QREHLSQHLNKTQKRFKWRVYNRRK', strict=True))
REGIONS = ((1,32), (107,174), (201,280))


def verify_policy(sources=None):
    profile, source, model = read(PROFILE), read(SOURCE), read(MODEL)
    equal(e1.canonical_sha256(source), profile['sourceBinding']['canonicalSha256'], 'source projection identity')
    equal(e1.canonical_sha256(model), profile['transcriptModel']['canonicalSha256'], 'model identity')
    equal((model['transcript'], model['refseqTranscript'], model['protein'], model['refseqProtein'], len(model['cdsSequence'])),
          ('ENST00000257555.11','NM_000545.8','ENSP00000257555.5','NP_000536.6',1896), 'reference identities')
    aa_names = {v:k for k,v in AA.items()}
    text = next(r['text'] for r in source['rule']['strengthDescriptor'] if r['strength']=='Moderate')
    extracted = [(int(p), aa_names[a]) for a,p in re.findall(r'([A-Z][a-z]{2})(\d+)', text)]
    equal(len(extracted), 25, '25 source residues'); equal(dict(extracted), RESIDUES, 'independently fixed source residues')
    equal(profile['policy']['moderateResidues'], [dict(position=p,referenceAminoAcid=a) for p,a in extracted], 'compiled residues')
    equal(profile['policy']['supportingRegions'], [dict(first=a,last=b) for a,b in REGIONS], 'closed supporting regions')
    supporting = next(r['text'] for r in source['rule']['strengthDescriptor'] if r['strength']=='Supporting')
    require(all(f'{a}-{b}' in supporting for a,b in REGIONS), 'supporting source text')
    equal([(r['first'],r['last']) for r in profile['policy']['excludedRegions']], [(281,631)], 'TAD exclusion')
    equal(profile['activation']['classificationEnabled'], False, 'no classification')
    for position, expected in RESIDUES.items():
        equal(CODE[model['cdsSequence'][(position-1)*3:position*3]], expected, 'reference residue '+str(position))
    registry_path = RES.parent / profile['registryBinding']['registryClasspathResource'].lstrip('/')
    equal(file_sha(registry_path), profile['registryBinding']['registrySha256'], 'registry raw identity')
    registry=read(registry_path); _validate_schema(registry)
    predecessor=read(RES/'rule-registry-v2/acmg-rule-registry-hnf1a-revel-2026-09-10.json')
    for before,after in zip(predecessor['criteria'],registry['criteria'],strict=True):
        if before['code']!='PM1': equal(before,after,'unrelated criterion unchanged')
    if sources:
        sources=Path(sources)
        equal(file_sha(sources/'GN017_3.1.0.json'), source['sourceJsonSha256'], 'raw CSpec snapshot')
        raw=read(sources/'GN017_3.1.0.json')
        equal(raw['ld']['RuleSet'][0]['ld']['CriteriaCode'][27]['entContent'], source['rule'], 'exact PM1 source projection')
        equal(file_sha(sources/'gn017-live.html'),source['sourceHtmlSha256'],'HTML exclusion source')
        require('will not apply PM1 at any level' in (sources/'gn017-live.html').read_text(encoding='utf-8'),'explicit exclusion')
    return profile, model


def location(position, reference):
    if position in RESIDUES:
        equal(reference,RESIDUES[position],'moderate reference amino acid')
        return 'MODERATE','GN017_DIRECT_DNA_BINDING_RESIDUE'
    for first,last in REGIONS:
        if first<=position<=last: return 'SUPPORTING',f'GN017_REGION_{first}_{last}'
    return 'NONE','GN017_TAD_EXCLUDED' if 281<=position<=631 else 'GN017_POSITION_NOT_SUPPORTED'


def instant_seconds(value):
    """Preserve nanoseconds for both supported HTTP Instant representations."""
    if isinstance(value,(int,Decimal)) and not isinstance(value,bool):return Decimal(value)
    require(isinstance(value,str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z',value) is not None,'exact UTC annotation timestamp')
    whole,_,fraction=value.removesuffix('Z').partition('.')
    return Decimal(calendar.timegm(datetime.fromisoformat(whole).timetuple()))+Decimal('0.'+(fraction or '0'))


def verify_envelope(row, profile, model, schemas):
    before=copy.deepcopy(row)
    equal(row['profileSha256'], e1.canonical_sha256(profile), 'PM1 profile')
    equal(row['policy'],profile['policy'],'PM1 policy'); equal(row['sourceBinding'],profile['sourceBinding'],'source binding')
    if 'evaluation' not in row:
        equal(row['evaluationResult']['status'],'NOT_EVALUATED','blocked result')
        require(row['evaluationResult'].get('reasonCode'),'explicit blocker')
        return {'status':'NOT_EVALUATED'}
    c,a,v,s=row['context'],row['evidence'],row['evaluation'],row['policySelection']
    for node,kind,identity,projection in (
        (c,'evidence-context-v2','contextId',e1.context_identity_projection),
        (a,'atomic-evidence-v2','evidenceId',e1.evidence_identity_projection),
        (v,'criterion-evaluation-v2','evaluationId',e1.evaluation_identity_projection)):
        verify_identity(node,kind,identity,projection,schemas)
    equal(v['criterionId'],'ACMG_AMP_2015:PM1','criterion')
    equal(v['contextId'],c['contextId'],'evaluation context'); equal(a['contextId'],c['contextId'],'source context')
    equal(v['evidenceIds'],[a['evidenceId']],'one source'); equal(a['deduplicationKey'],'dedup-sha256-'+e1.canonical_sha256(e1.evidence_deduplication_projection(a)),'dedup')
    selection=copy.deepcopy(s); sid=selection.pop('selectionId')
    equal(sid,'cspec-selection-sha256-'+e1.canonical_sha256(selection),'policy selection identity')
    equal(s['contextId'],c['contextId'],'policy context'); equal(s['authority'],profile['policy']['authority'],'authority')
    equal(v['ruleBinding']['cspecSelectionId'],sid,'selected policy binding')
    equal(v['ruleBinding']['ruleRegistrySha256'],profile['registryBinding']['registrySha256'],'rule registry binding')
    if v['evaluationResult']['status']=='NOT_EVALUATED':
        require(v['evaluationResult'].get('reasonCode'),'not evaluated reason'); equal(v['appliedStrength'],'NONE','no strength')
        return {'status':'NOT_EVALUATED'}
    equal((c['gene']['hgncId'],c['disease']['mondoId'],c['moi']),('HGNC:11621','MONDO:0015967','AUTOSOMAL_DOMINANT'),'scoped context')
    equal(c['variant']['assemblyLineage']['mappingStatus'],'NATIVE','native assembly')
    equal(c['variant']['normalizedAllele'],c['variant']['evidenceQueryAllele'],'no projection')
    derived=derive_protein(c['variant']['evidenceQueryAllele'],model)
    input=row['resolvedInput']; tx=row['storedAnnotation']
    for key in ('cdsPosition','proteinPosition','refCodon','altCodon','refAminoAcid','altAminoAcid'): equal(input[key],derived[key],key)
    for key,expected in [('hgvsCoding',derived['hgvsc']),('hgvsProtein',derived['hgvsp']),('exon',derived['exon']),('codons',derived['refCodon']+'/'+derived['altCodon']),('aminoAcids',derived['refAminoAcid']+'/'+derived['altAminoAcid'])]:
        equal(tx[key].upper() if key=='codons' else tx[key],expected,key)
    terms=set(re.split('[&,|]',tx['consequence'])); require('missense_variant' in terms,'missense source')
    require(not any((('splice' in t and t!='splice_region_variant') or t in ('intron_variant','frameshift_variant','start_lost','stop_gained','stop_lost','synonymous_variant')) for t in terms),'protein branch consequence')
    strength,match=location(derived['proteinPosition'],derived['refAminoAcid'])
    equal((input['strength'],input['matchedPolicy']),(strength,match),'location result')
    equal(v['appliedStrength'],strength,'strength precedence'); equal(v['baseStrength'],'MODERATE','base strength')
    equal(v['evaluationResult']['status'],'NOT_MET' if strength=='NONE' else 'MET','status')
    trace=v['deterministicTrace']; equal(trace['traceSha256'],e1.canonical_sha256(e1.deterministic_trace_projection(trace,include_hash=False)),'trace identity')
    params={p['name']:p['value'] for p in trace['parameters']}
    equal(params['resolvedInputSha256'],e1.canonical_sha256(input),'resolved input binding')
    equal(input['storedAnnotationSha256'],e1.canonical_sha256(tx),'stored annotation binding')
    equal(row,before,'source output unchanged')
    return dict(status=v['evaluationResult']['status'],strength=strength,proteinPosition=derived['proteinPosition'],allele=c['variant']['evidenceQueryAllele'])


def verify_business(directory, profile, model):
    from scripts.revel.verify_hnf1a_revel_business import verify_responses
    directory=Path(directory);saved=read(directory/'saved-business.json')
    equal(saved['executionProfile']['profileId'],'acmg-explicit-business-pm1-hnf1a-v1','active profile')
    rules=saved['enabledCriteria'];equal(len(rules),len(set(rules)),'unique criteria');require('ACMG_AMP_2015:PM1' in rules,'PM1 enabled')
    formal=json.loads((directory/'formal-topn.json').read_text(encoding='utf-8'),parse_float=Decimal)
    formal={r['candidateId']:r for r in formal};options=read(directory/'input-options.json')
    equal(saved['candidateIds'],options['candidateIds'],'formal candidate order')
    results=[];schemas=e1.ContractSchemas()
    for candidate in saved['candidates']:
        source=formal[candidate['candidateId']]
        for attempt in candidate.get('attempts',[]):
            row=attempt.get('pm1',{})
            if 'profileSha256' not in row:
                equal(row['criterionId'],'ACMG_AMP_2015:PM1','minimal disposition criterion')
                equal(row['evaluationResult']['status'],'NOT_EVALUATED','minimal blocker status');require(row['evaluationResult'].get('reasonCode'),'explicit blocker')
                continue
            result=verify_envelope(row,profile,model,schemas)
            if 'evaluation' not in row:continue
            equal(row['candidateId'],candidate['candidateId'],'envelope candidate')
            equal(row['executionSourceSha256'],saved['executionSourceSha256'],'formal execution source binding')
            allele=row['context']['variant']['evidenceQueryAllele'];key=source['observation']['key']
            equal((allele['assembly'],allele['contig']),(key['genomeAssembly'],key['chromosome']),'formal assembly')
            for field in ('position','reference','alternate'):equal(allele[field],key[field],'formal '+field)
            equal(source['candidateIdentity']['modeOfInheritance'],'AD','formal MOI')
            annotation=row['storedAnnotation'];matches=[t for t in source['transcriptAnnotations'] if t.get('hgvsCoding')==annotation['hgvsCoding']]
            equal(len(matches),1,'unique original stored annotation');original=matches[0]
            equal({k:v for k,v in annotation.items() if k!='timestamp'},{k:v for k,v in original.items() if k!='timestamp'},'original VEP fields')
            equal(instant_seconds(annotation['timestamp']),instant_seconds(original['timestamp']),'original annotation timestamp')
            results.append(result)
    equal({(r['proteinPosition'],r['strength']) for r in results},{(112,'SUPPORTING'),(203,'MODERATE'),(574,'NONE')},'three fixed strata')
    equal(len(results),3,'one PM1 per formal AD control')
    response_names=('saved-get','repeat-post','rebuilt-saved-get','rebuilt-repeat-post')
    response=verify_responses(directory/'saved-business.json',{name:directory/(name+'-response.json') for name in response_names})
    events=[read(directory/(name+'-event.json')) for name in response_names]
    for event in events:
        equal(event['runId'],saved['runId'],'reuse run ID');require(event['callIncrement'] and not any(event['callIncrement'].values()),'no added calls')
    evaluate,reuse=read(directory/'evaluate-report.json'),read(directory/'reuse-report.json')
    before,after=evaluate['instance'],reuse['instance']
    require(before['processId']!=after['processId'],'distinct JVM processes')
    for field in ('instanceId','sourceHead','configurationSha256','executionProfile'):equal(before[field],after[field],'stable JVM '+field)
    require(reuse['jvmRestartCovered'],'actual JVM restart receipt')
    # Recompute measured increments from the original HTTP counter response bodies.
    for name in response_names:
        event=read(directory/(name+'-event.json'));raw_event=Path(event['eventFile'])
        require(raw_event.is_relative_to(directory),'event ownership')
        raw=Path(str(raw_event).removesuffix('.event.json')+'.response-body')
        equal(file_sha(raw),event['responseSha256'],'raw HTTP response receipt')
        equal(raw.read_bytes(),(directory/'saved-business.json').read_bytes(),'recorded HTTP body')
        counts=[]
        for suffix in ('-calls-before','-calls-after'):
            paths=list(directory.glob(name+suffix+'-????????.event.json'))
            equal(len(paths),1,'one original counter request')
            counter_event=read(paths[0]);body=Path(str(paths[0]).removesuffix('.event.json')+'.response-body')
            equal(file_sha(body),counter_event['responseSha256'],'counter body receipt')
            counts.append(read(body))
        equal(counts[0],counts[1],'original counter bodies')
        equal({k:counts[1][k]-counts[0][k] for k in counts[0]},event['callIncrement'],'measured increment recomputed')
    return dict(results=results,reuse=response,counterFields=len(events[0]['callIncrement']),jvmRestart=True,sourceHead=before['sourceHead'])


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--sources'); parser.add_argument('--envelopes'); parser.add_argument('--business-directory'); parser.add_argument('--output')
    args=parser.parse_args(); profile,model=verify_policy(args.sources); result={'policy':'PASS','officialResidues':25}
    if args.envelopes:
        schemas=e1.ContractSchemas(); result['envelopes']=[verify_envelope(r,profile,model,schemas) for r in read(args.envelopes)]
    if args.business_directory:result['business']=verify_business(args.business_directory,profile,model)
    text=json.dumps(result,indent=2)
    if args.output: Path(args.output).write_text(text+'\n',encoding='utf-8')
    print(text)


if __name__=='__main__': main()
