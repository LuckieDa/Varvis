"""Read-only independent HNF1A reference, codon, Grantham and HTTP verification.

Uses original public SCV/SEPIO bytes and the original AAindex record. It never
calls a production evaluator or edits a saved response to obtain an expectation.
"""
import argparse
import copy
from decimal import Decimal
from datetime import datetime
import json
import os
import re
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
if os.environ.get('VARVIS_CONTRACT_PYTHON_DEPS'):
    sys.path.insert(0, os.environ['VARVIS_CONTRACT_PYTHON_DEPS'])
from scripts.revel.verify_hnf1a_revel_business import (
    e1, _validate_schema, GN017_ALIASES, derive_protein, require, equal, file_sha,
    read, verify_instances, verify_responses, verify_counter_pairs,
)

RES = ROOT / 'varvis-server/src/main/resources/acmg'
DATA = RES / 'hnf1a-ps1-pm5-v1'
PROFILE = RES / 'hnf1a-ps1-pm5-business-profile-v2.json'
AUTHORITY = RES / 'authority-catalog-hnf1a-ps1-pm5-2026-09-11.json'
RULES = RES / 'rule-registry-v2/acmg-rule-registry-hnf1a-ps1-pm5-2026-09-11.json'
OVERLAY = RES / 'cspec-overlay-registry-v1/cspec-overlay-registry-hnf1a-ps1-pm5-2026-09-11.json'


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def original_matrix(path):
    lines = Path(path).read_text().splitlines()
    equal(lines[0], 'H GRAR740104', 'AAindex original record')
    row = next(i for i, line in enumerate(lines) if line.startswith('M rows = '))
    axes = lines[row].split(' = ')
    amino = axes[1].split(',')[0]
    equal(amino, axes[2], 'matrix row/column axes')
    values = [line.split() for line in lines[row+1:] if line != '//']
    equal([len(v) for v in values], list(range(1, 21)), 'original lower triangle')
    return {(a,b): int(Decimal(values[max(i,j)][min(i,j)])) for i,a in enumerate(amino) for j,b in enumerate(amino)}


def expected_strength(code, classification, target, reference):
    require(classification.lower() in ('pathogenic', 'likely pathogenic'), 'unqualified reference classification')
    require(code in ('PS1','PM5') and target >= 0 and reference >= 0, 'invalid comparison inputs')
    if code == 'PS1':
        return 'STRONG' if classification.lower() == 'pathogenic' else 'MODERATE'
    return 'MODERATE' if classification.lower() == 'pathogenic' and target >= reference else 'SUPPORTING'


def metric(atomic, name):
    values = [r['rawValue'] for r in atomic.get('measurements', []) if r['metricId'] == name]
    require(len(values) <= 1, 'duplicate measurement ' + name)
    return str(values[0]) if values else ''


def verify_reference(ref, model):
    equal((ref['geneSymbol'],ref['hgncId'],ref['mondoId']),('HNF1A','HGNC:11621','MONDO:0015967'),'audited reference context')
    name = {'SCV002032337.1': 'g20r', 'SCV002499518.2': 'w206r'}[ref['scv']]
    xml = ET.parse(DATA / ('clinvar-'+name+'.xml'))
    accession, version = ref['scv'].split('.')
    found = [a for a in xml.findall('.//ClinicalAssertion') if a.find('ClinVarAccession').get('Accession') == accession]
    equal(len(found),1,'unique original SCV')
    assertion = found[0]
    acc = assertion.find('ClinVarAccession'); classification = assertion.find('Classification')
    equal(acc.get('Version'),version,'exact SCV version')
    equal(acc.get('OrgID'),'508240','MDEP submitter')
    equal(classification.findtext('ReviewStatus'),'reviewed by expert panel','expert review')
    equal(classification.findtext('GermlineClassification'),ref['classification'],'original classification raw term')
    equal(classification.get('DateLastEvaluated'),ref['evaluationDate'],'expert evaluation date')
    equal(assertion.find('ClinVarSubmissionID').get('localKey'),ref['erepoId'],'ClinVar/ERepo linkage')
    equal(assertion.find('ClinVarSubmissionID').get('submittedAssembly'),ref['submittedAssembly'],'original submitted assembly preserved')
    require(any(n.get('DB') == 'MONDO' and n.get('ID') in ('0015967','MONDO:0015967') for n in assertion.findall('.//XRef')), 'original disease identity')
    equal(xml.getroot().get('VariationID') or xml.find('.//VariationArchive').get('VariationID'),ref['variationId'],'ClinVar variation ID')
    sp = read(DATA / ('erepo-'+name+'-sepio.json'))['data']
    equal((sp['uuid'],sp['metadata']['version']), (ref['erepoId'],ref['erepoVersion']), 'original ERepo version')
    equal(sp['condition']['id'],'MONDO:0015967','original ERepo disease')
    equal(sp['statementOutcome']['label'].lower(),ref['classification'].lower(),'expert classification agreement')
    # The current SEPIO prototype omits criterion labels in some W206R nodes.
    # Verify the exact expert SCV's explicit final applied-criteria narrative;
    # do not invent labels for unlabeled SEPIO assessments.
    narrative = classification.findtext('Comment') or ''
    require('ACMG/AMP criteria applied' in narrative and '):' in narrative,'explicit expert applied-criteria statement required')
    applied = set(re.findall(r'\b(?:PVS|PS|PM|PP|BS|BP|BA)\d(?:_(?:Strong|Moderate|Supporting))?\b', narrative.rsplit('):',1)[1]))
    equal(applied,set(ref['independentCriteria']),'original expert applied criteria')
    met = {n['criterion']['label'] for n in walk(sp) if n.get('type') == 'CriterionAssessment' and n.get('statementOutcome',{}).get('label') == 'Met' and 'criterion' in n}
    require(met.issubset({code.split('_')[0] for code in applied}),'labeled SEPIO criteria contradict exact SCV')
    require(not {code.split('_')[0] for code in applied}.intersection({'PS1','PM5'}),'circular reference')
    derived = derive_protein(ref['allele'],model)
    equal(ref['transcriptId'],'ENST00000257555.11','reference transcript version')
    equal(ref['proteinPosition'],derived['proteinPosition'],'reference residue')
    equal(ref['referenceAminoAcid'],derived['refAminoAcid'],'reference wild type')
    equal(ref['alternateAminoAcid'],derived['altAminoAcid'],'reference alternate')
    vep = ref['selectedVep']
    equal(vep['hgvsc'],derived['hgvsc'],'reference VEP HGVSc')
    equal(vep['hgvsp'],derived['hgvsp'],'reference VEP HGVSp')
    equal(vep['codons'].upper(),derived['refCodon']+'/'+derived['altCodon'],'reference VEP codon')
    equal(vep['consequence_terms'],['missense_variant'],'reference pure protein mechanism annotation')
    return derived


def verify_resources():
    p=read(PROFILE); model=read(RES/'pvs1-hnf1a-transcript-model-v1.json'); audit=read(DATA/'reference-audit.json')
    equal(p['profileId'],'HNF1A_GN017_3_1_PS1_PM5','bounded profile')
    require(p['activation']['classificationEnabled'] is False and p['activation']['automaticTrigger'] is False,'explicit execution only')
    equal(file_sha(DATA/'GN017_3.1.0.json'),'6195d0bf2f4cd7e86016ff13cdd78ad2696168b224fafc60f10cfc2409bc0ec1','official GN017 release')
    for artifact in p['sourceArtifacts']:
        path=(RES.parent/artifact['resource'].lstrip('/')).resolve()
        require(path.is_relative_to(RES.resolve()),'artifact resource path escape')
        equal(file_sha(path),artifact['sha256'],'source artifact bytes '+path.name)
    for path,key in [(RULES,'registrySha256')]: equal(file_sha(path),p['registryBinding'][key],'registry binding')
    equal(file_sha(AUTHORITY),p['scientificArtifactBinding']['authoritySha256'],'authority binding')
    equal(file_sha(OVERLAY),p['scientificArtifactBinding']['overlaySha256'],'overlay binding')
    s=e1.ContractSchemas();e1.validate_authority_catalog(read(AUTHORITY),s);_validate_schema(read(RULES))
    e1.validate_cspec_registry(read(OVERLAY),read(AUTHORITY),read(RULES),s,authority_path=AUTHORITY,rule_registry_path=RULES,successor_alias_identities=GN017_ALIASES)
    matrix=original_matrix(DATA/'grantham-GRAR740104.txt')
    stored=read(DATA/'grantham.json')['matrix']
    for (a,b),value in matrix.items(): equal(stored[a][b],value,'derived matrix '+a+b)
    proof=read(DATA/'splice-annotation-proof.json')
    equal(proof['modelCanonicalSha256'],e1.canonical_sha256(model),'full transcript model')
    fields=proof['line'].split('\t')
    equal([int(v)+1 for v in fields[5].strip(',').split(',')],[e['genomicStart'] for e in model['exons']],'every packaged exon start')
    equal([int(v) for v in fields[6].strip(',').split(',')],[e['genomicEnd'] for e in model['exons']],'every packaged exon end')
    equal((fields[0],fields[1],fields[2]),('HNF1A','12','+'),'packaged gene identity')
    equal(proof['packagedAnnotationSha256'],'565c0ea83f66b2210182364c3d626fef526229e3d02f444bab247329b6695f2c','raw annotation identity')
    require(proof['normalRnaClaimed'] is False,'boundary equivalence is not normal RNA')
    for ref in audit['references']: verify_reference(ref,model)
    return p,model,audit,matrix,s


def splice_scores(value):
    raw=value['rawSpliceBatch']; rows=raw['results']; equal(raw['status'],'SUCCEEDED','actual raw batch status')
    equal(len({r['queryId'] for r in rows}),len(rows),'unique raw queries')
    scores={}
    for r in rows:
        require(r['status']=='HIT' and r['exactAlleleMatch'] is True,'raw exact hit')
        annotations=[a for a in r['annotations'] if a['geneSymbol']=='HNF1A']
        equal(len(annotations),1,'unique raw HNF1A annotation')
        components=[Decimal(annotations[0]['deltaScores'][n]) for n in ('DS_AG','DS_AL','DS_DG','DS_DL')]
        require(all(v.is_finite() and 0<=v<=1 for v in components),'valid raw scores')
        scores[r['queryId']]=max(components)
        display=[a for a in value['spliceEvidence'] if a['queryId']==r['queryId']]
        equal(len(display),1,'unique displayed splice query')
        equal(display[0]['allele'],r['allele'],'raw/displayed allele')
        equal(Decimal(str(display[0]['maximumScore'])),max(components),'raw/displayed score')
    equal(value['scientificDecisions']['rawSpliceBatchSha256'],e1.canonical_sha256(raw),'raw dependency hash')
    return scores


def verify_comparison(value, formal, p, model, audit, matrix, schemas):
    equal(value['policy'],p,'saved full policy identity')
    context=value['context']; allele=context['variant']['evidenceQueryAllele']; derived=derive_protein(allele,model)
    equal((context['gene']['hgncId'],context['disease']['mondoId'],context['moi']),('HGNC:11621','MONDO:0015967','AUTOSOMAL_DOMINANT'),'exact context')
    for k,v in derived.items():
        if k!='exon': equal(value['targetIdentity'][k],v,'independent target '+k)
    equal(value['targetIdentity']['exonOrdinal'],int(derived['exon'].split('/')[0]),'target exon ordinal')
    equal(value['targetIdentity']['transcriptModelSha256'],e1.canonical_sha256(model),'target transcript model')
    key=formal['observation']['key']
    equal((key['genomeAssembly'],key['chromosome'],key['position'],key['reference'],key['alternate']),
          (allele['assembly'],allele['contig'],allele['position'],allele['reference'],allele['alternate']),'formal original allele')
    matches=[a for a in formal['transcriptAnnotations'] if a.get('hgvsCoding')==derived['hgvsc']]
    equal(len(matches),1,'unique original formal VEP')
    require(matches[0]['source'] in ('VEP','VEP_CACHED'),'formal VEP annotation source')
    for key,expected in [('hgvsProtein',derived['hgvsp']),('exon',derived['exon']),('aminoAcids',derived['refAminoAcid']+'/'+derived['altAminoAcid']),('version','116.0'),('consequence','missense_variant')]:equal(matches[0][key],expected,'formal VEP '+key)
    equal(matches[0]['codons'].upper(),derived['refCodon']+'/'+derived['altCodon'],'formal VEP codons')
    bundle=value['bundle'];equal(e1.rehash_bundle(copy.deepcopy(bundle)),bundle,'unmodified Java canonical bundle')
    e1.validate_bundle(bundle,read(AUTHORITY),read(RULES),read(OVERLAY),schemas,authority_path=AUTHORITY,rule_registry_path=RULES,cspec_registry_path=OVERLAY)
    decisions=value['scientificDecisions'];equal(decisions['referenceAuditSha256'],e1.canonical_sha256(audit),'audit decision dependency')
    equal(decisions['granthamSha256'],e1.canonical_sha256(read(DATA/'grantham.json')),'original matrix dependency')
    equal(decisions['rnaReviewSha256'],e1.canonical_sha256(read(DATA/'rna-review.json')),'RNA decision dependency')
    equal(decisions['targetIdentitySha256'],e1.canonical_sha256(value['targetIdentity']),'target dependency')
    equal(decisions['referenceAssessmentsSha256'],e1.canonical_sha256(value['referenceAssessments']),'all reference decisions dependency')
    evaluations={e['criterionId'].split(':')[-1]:e for e in value['criterionEvaluations']}
    equal(set(evaluations),{'PS1','PM5'},'one decision per rule')
    references=value['references']; eligible={'PS1':[],'PM5':[]}
    scores=splice_scores(value) if references else {}
    if references:
        expected_queries={'TARGET':allele, **{r['scv']:r['allele'] for r in references}}
        equal({r['queryId']:r['allele'] for r in value['rawSpliceBatch']['results']},expected_queries,'raw query identities cover target and every reference')
    atomics={a['evidenceId']:a for a in value['atomicEvidence']}
    trusted={r['scv']:r for r in audit['references']}
    adopted_alleles=set()
    for ref in references:
        original=trusted[ref['scv']]
        for k,v in original.items():equal(ref[k],v,'reference source '+k)
        require(ref['allele']!=allele,'no self reference')
        identity=e1.canonical_sha256(ref['allele']);require(identity not in adopted_alleles,'duplicate reference allele');adopted_alleles.add(identity)
        rd=verify_reference(ref,model)
        rna=read(DATA/'rna-review.json')
        require(rna['normalRnaClaimed'] is False,'RNA literature review is not a normal RNA experiment')
        require(not any(c.get('allele') in (allele,ref['allele']) for c in rna['knownConcerns']),'unresolved public RNA concern')
        relation={'referenceAminoAcid':derived['refAminoAcid'],'targetAlternate':derived['altAminoAcid'],
                  'comparatorAlternate':rd['altAminoAcid'],'proteinPosition':derived['proteinPosition']}
        require(relation in rna['reviewedProteinRelations'],'unreviewed protein relation cannot adopt a reference')
        equal((rd['proteinPosition'],rd['refAminoAcid']),(derived['proteinPosition'],derived['refAminoAcid']),'same WT residue')
        code='PS1' if rd['altAminoAcid']==derived['altAminoAcid'] else 'PM5'
        equal(ref['proteinRelation'],'SAME_AMINO_ACID_CHANGE' if code=='PS1' else 'SAME_RESIDUE_DIFFERENT_AMINO_ACID','independent protein relationship')
        td=matrix[derived['refAminoAcid'],derived['altAminoAcid']];rcd=matrix[rd['refAminoAcid'],rd['altAminoAcid']]
        equal((ref['targetGrantham'],ref['referenceGrantham']),(td,rcd),'independent distance direction')
        require(scores['TARGET']<=Decimal('0.1') and scores[ref['scv']]<=Decimal('0.1'),'both raw splice gates')
        relevant=[a for a in atomics.values() if metric(a,'clinvar.assertion.scv')==ref['scv']]
        require(relevant,'reference actual provider assertion absent')
        for atomic in relevant:
            equal(metric(atomic,'clinvar.comparator.variationId'),ref['variationId'],'actual Variation ID')
            equal(metric(atomic,'clinvar.assertion.classification').lower(),ref['classification'].lower(),'actual SCV classification')
            equal(metric(atomic,'clinvar.assertion.reviewStatus'),'reviewed by expert panel','actual expert review')
            for k,v in ref['allele'].items():equal(metric(atomic,'clinvar.comparator.'+k),str(v),'actual reference allele '+k)
            date=metric(atomic,'clinvar.assertion.dateLastEvaluated')
            if not re.fullmatch(r'\d{4}-\d{2}-\d{2}',date):date=datetime.strptime(date,'%b %d, %Y').date().isoformat()
            equal(date,ref['evaluationDate'],'actual expert evaluation date')
            conditions=[metric(atomic,'clinvar.assertion.condition.'+role) for role in ('submittedRaw','reportedRaw')]
            require(any(v in ('MONDO:0015967','MONDO:MONDO:0015967') for v in conditions),'actual reference disease absent')
            require(all(not v.startswith('MONDO:') or v in ('MONDO:0015967','MONDO:MONDO:0015967') for v in conditions),'actual reference disease conflict')
            for role,ident in [('target',derived),('comparator',rd)]:
                equal(metric(atomic,'vep.'+role+'.transcriptId'),'ENST00000257555.11','actual reference transcript')
                for a,b in [('proteinPosition','proteinPosition'),('referenceAminoAcid','refAminoAcid'),('alternateAminoAcid','altAminoAcid')]:equal(metric(atomic,'vep.'+role+'.'+a),str(ident[b]),'actual protein comparison')
        eligible[code].append((expected_strength(code,ref['classification'],td,rcd),ref['scv']))
    for code,evaluation in evaluations.items():
        equal(set(evaluation['evidenceIds']),set(atomics),'all original comparison assertions remain evaluation inputs')
        if not references:
            expected='NOT_EVALUATED';reason='INDEPENDENT_REFERENCE_EVIDENCE_MISSING'
        elif code=='PM5' and eligible['PS1']:
            expected='NOT_EVALUATED';reason='PS1_PM5_OVERLAPPING_SUPPORT_NOT_INDEPENDENT'
        else:
            expected='MET' if eligible[code] else 'NOT_MET';reason=None
        equal(evaluation['evaluationResult']['status'],expected,'independent '+code+' outcome')
        equal(decisions[code]['status'],expected,'decision/evaluation agreement')
        if reason:equal(evaluation['evaluationResult']['reasonCode'],reason,'explicit missing or overlap reason')
        if expected=='MET':
            ranking={'SUPPORTING':1,'MODERATE':2,'STRONG':3}
            best=sorted(eligible[code],key=lambda r:(-ranking[r[0]],r[1]))[0]
            equal(evaluation['appliedStrength'],best[0],'independent '+code+' strength')
            equal(decisions[code]['selectedReferenceScv'],best[1],'deterministic single reference')
            require(len(decisions[code]['evidenceIds'])==1,'single reference support')
            chosen=atomics[decisions[code]['evidenceIds'][0]]
            equal(metric(chosen,'clinvar.assertion.scv'),best[1],'selected support points to actual audited SCV')
        else:equal(evaluation['appliedStrength'],'NONE','non-MET strength')
    return {'allele':allele,'outcomes':{k:v['evaluationResult']['status'] for k,v in evaluations.items()},'rawScores':{k:str(v) for k,v in scores.items()},'referenceCount':len(references)}


def verify(business=None, formal=None, options=None, responses=None, counter_pairs=None, before=None, after=None):
    p,model,audit,matrix,schemas=verify_resources()
    result={'resourcesVerified':True,'referenceCount':len(audit['references']),'clinicalValidationClaimed':False,'pageCovered':False}
    if business is None:return result
    require(formal and options,'original formal outputs and input options required')
    saved=read(business);rows={r['candidateId']:r for r in read(formal)};opts=read(options)
    equal(saved['candidateIds'],opts['candidateIds'],'original candidate ordering')
    equal(saved['analysisId'],opts['analysisId'],'original analysis ID')
    result['comparisons']=[]
    for c in saved['candidates']:
        for attempt in c.get('attempts',[]):
            value=attempt.get('missenseComparison') or {}
            if value.get('policy',{}).get('profileId')==p['profileId'] and value.get('bundle'):
                result['comparisons'].append(verify_comparison(value,rows[c['candidateId']],p,model,audit,matrix,schemas))
    require(result['comparisons'],'no actual HNF1A comparison bundle')
    result['reuse']=verify_responses(business,responses or {})
    result['counterPairs']=verify_counter_pairs(counter_pairs or [])
    result['jvmRestart']=verify_instances(before,after)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser()
    for key in ('business','formal','options','before','after','output'):p.add_argument('--'+key,type=Path)
    p.add_argument('--response',nargs=2,action='append',default=[])
    p.add_argument('--counter-pair',nargs=2,action='append',default=[])
    args=p.parse_args()
    result=verify(args.business,args.formal,args.options,dict(args.response),args.counter_pair,args.before,args.after)
    raw=json.dumps(result,indent=2)+'\n'
    if args.output:
        with args.output.open('x',encoding='utf-8') as f:f.write(raw)
    print(raw)
