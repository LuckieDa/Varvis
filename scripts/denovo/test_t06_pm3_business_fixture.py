"""Synthetic raw sources/reference/catalog and transport cache for CURRENT18.

No source adapter, identity resolver, evaluator or qualification is substituted.
Only original input bytes and explicitly fake provider responses are generated.
The candidate/declaration comes from normal ranking. No user case is touched.
"""
from copy import deepcopy
import gzip
import json
from pathlib import Path
import re
import sys
from xml.sax.saxutils import escape

from acquire_public_literature import source_bundle
from bundle_model_contract import load, requests
from model_literature_contract import canonical, digest, parse
from model_transport import atomic
from paper_library_registration import register
from pm3_literature_contract import VERSION, SCHEMA
from pm3_specification_context import PHASE_DELEGATION, APPROVED_PARTNER
from prepare_literature import prepare
from run_pm3_literature_model import run
from test_core_context_review import LocalFixture
from test_t02_pm3_java_fixture import config as model_config

PMCID = 'PMC902061'
DOI = '10.5281/zenodo.9999999'
SCENARIOS = {'GENERAL_HOMO', 'HOMO_CONFLICT', 'PHASE_TRANS', 'APPROVED_TRANS', 'PHASE_CONFLICT', 'APPROVAL_MISSING'}


def pin(local, name, value):
    raw = value if isinstance(value, bytes) else canonical(value)
    (local/name).write_bytes(raw)
    return {'path': 'local/'+name, 'sha256': digest(raw)}


def identity(project, allele, gene, hgnc, transcript, coding):
    """Small real indexed FASTA/annotation bytes, explicitly synthetic biology."""
    f = LocalFixture(project); local = project/'local'
    match = re.fullmatch(r'c\.([1-9][0-9]*)([ACGT])>([ACGT])', coding)
    if not match:
        raise ValueError('T06_SYNTHETIC_CODING_SNV_REQUIRED')
    c = int(match[1]); complement = str.maketrans('ACGT', 'TGCA')
    strand = 1 if match[2] == allele['reference'] else -1
    if strand == -1 and match[2].translate(complement) != allele['reference']:
        raise ValueError('T06_SYNTHETIC_REF_ORIENTATION')
    length = ((c+2)//3)*3
    coding_sequence = list('A'*length); coding_sequence[c-1] = match[2]
    # Preserve the known test annotation codon where the selected source has E7V.
    if c == 20:
        coding_sequence[18:21] = list('GAA')
    coding_sequence = ''.join(coding_sequence)
    start = allele['position']-(c-1 if strand == 1 else length-c)
    if start < 1 or start+length > 10000:
        raise ValueError('T06_BOUNDED_SYNTHETIC_REFERENCE_ONLY')
    end = start+length-1
    seq = list('A'*max(300,end+10))
    seq[start-1:end] = list(coding_sequence if strand == 1 else coding_sequence.translate(complement)[::-1])
    contig = allele['contig']; header = ('>chr'+contig+'\n').encode()
    raw = header+b'\n'.join(''.join(seq)[i:i+50].encode() for i in range(0,len(seq),50))+b'\n'
    fasta = f.put('reference.fa', raw)
    index = f.put('reference.fa.fai', f'chr{contig}\t{len(seq)}\t{len(header)}\t50\t51\n'.encode())
    receipt = parse((local/'receipt.json').read_bytes()); claim = receipt['records']['reference']
    claim.update(assembly=allele['assembly'], expectedSha256=digest(raw), observedSha256=digest(raw))
    claim['actualChecksumReceipt'].update(assembly=allele['assembly'], expectedSha256=digest(raw), observedSha256=digest(raw),
        byteLength=len(raw), mtimeNs=(local/'reference.fa').stat().st_mtime_ns, indexSha256=index['sha256'])
    f.config['reference'].update(sha256=fasta['sha256'], receipt=f.put('receipt.json',canonical(receipt)),
        aliases=f.put('aliases.txt', f'{contig} chr{contig}\n'.encode()))
    sign = '+' if strand == 1 else '-'
    gtf = f'chr{contig}\tSYNTHETIC\tCDS\t{start}\t{end}\t.\t{sign}\t0\tgene_id "{hgnc}"; gene_name "{gene}"; transcript_id "ENST000001.1";\n'.encode()
    f.put('synthetic.gtf',gtf)
    f.annotation.update(assembly=allele['assembly'],geneSymbol=gene,hgncId=hgnc,strand=strand,contig=contig,
                        cdsLength=length,rawGtfSha256=digest(gtf),exons=[{'cdsStart':1,'cdsEnd':length,'genomicStart':start,'genomicEnd':end}])
    f.seal_annotation()
    manifest=parse((local/'gencode-manifest.json').read_bytes());manifest['source']['sha256']=digest(gtf)
    f.config['gencodeManifest']=f.put('gencode-manifest.json',canonical(manifest))
    mane=('RefSeq_nuc\tsymbol\tHGNC_ID\tEnsembl_nuc\tEnsembl_prot\tchr_strand\n'+
          f'{transcript}\t{gene}\t{hgnc}\tENST000001.1\tENSP000001.1\t{sign}\n').encode()
    m=f.put('mane.gz',gzip.compress(mane,mtime=0));f.config['mane']=m
    f.config['contextManifest']=f.put('context-manifest.json',canonical({'resources':{'mane':{'rawSha256':m['sha256']}}}))
    partner_c = c+1 if c < length else c-1
    partner_ref = coding_sequence[partner_c-1];partner_alt = next(x for x in 'ACGT' if x != partner_ref)
    partner_pos = start+(partner_c-1 if strand == 1 else length-partner_c)
    partner = {**allele,'position':partner_pos,'reference':partner_ref if strand == 1 else partner_ref.translate(complement),
               'alternate':partner_alt if strand == 1 else partner_alt.translate(complement)}
    return f.config, f'c.{partner_c}{partner_ref}>{partner_alt}', partner


def catalog(local,gene,hgnc,disease,transcript,scenario):
    entity={'entId':'GN900','entContent':{'version':'1.0','states':[{'current':True,'name':'Released'}],
        'approvedOn':'2020-01-01T00:00:00Z','notes':'SYNTHETIC TEST ONLY. No clinical curation claim.',
        'doi':{'docDoi':DOI,'authors':[{'role':{'id':'researchgroup'},'person_or_org':{'name':'SYNTHETIC VCEP'}}]}},
        'ld':{'Gene':[{'entContent':{'HGNC':{'symbol':gene,'hgnc_id':hgnc}}}],
            'RuleSet':[{'entContent':{'genes':[{'gene':gene,'preferredTranscript':transcript,
                'diseases':[{'preferredMondoId':disease,'preferredModeOfInheritance':'Autosomal recessive inheritance'}]}]}}],
            'CriteriaCode':[{'entContent':{'label':'PM3','gene':[gene],'disease':[disease],'applicability':'Applicable',
                'baseStrength':'Pathogenic','defaultStrength':'Pathogenic Moderate',
                'strengthDescriptor':[{'strength':s,'applicability':'Applicable','text':PHASE_DELEGATION}
                                      for s in ['Supporting','Moderate','Strong','Very Strong']]}}]}}
    if scenario in {'APPROVED_TRANS','APPROVAL_MISSING'}:
        entity['ld']['CriteriaCode'][0]['entContent']['instructionsToUse']=APPROVED_PARTNER
    raw=canonical({'data':[entity]});capture=local/'cspec';capture.mkdir()
    (capture/'raw.json').write_bytes(raw)
    entries=[] if scenario in {'GENERAL_HOMO','HOMO_CONFLICT'} else [{'specificationId':'GN900','rawRef':'raw.json','rawSha256':digest(raw),
        'source':{'rawRef':'raw.json','rawSha256':digest(raw),'jsonPointer':'/data/0','sourceUrl':'https://example.invalid/synthetic-rule'}}]
    snapshot={'schemaVersion':'a01-ba1-cspec-snapshot-v1','metadata':{'status':'COMPLETE','selectedReleasedCount':len(entries),
        'snapshotId':'T06-SYNTHETIC','generatedAt':'2020-01-01T00:00:00Z'},'entries':entries}
    snapshot['sha256']=digest(canonical(snapshot))
    return {'catalog':pin(local,'cspec.json',snapshot),'captureDirectory':'local/cspec'}


def cold(project,options,scenario,resource_root=None):
    if scenario not in SCENARIOS:raise ValueError('T06_SCENARIO_REQUIRED')
    local=project/'local';local.mkdir(exist_ok=True);(local/'library').mkdir(exist_ok=True)
    candidate=options['candidates'][0];gene=candidate['geneSymbol']
    rec=candidate['recommendation'];relation=next(r for r in rec['relationships'] if r.get('selectable') and r['moi']=='AR')
    annotation=next(a for a in rec['annotations'] if a.get('contextSelectable'))
    hgnc=relation['hgncId'];disease=relation['mondoId']
    source_transcripts=[a.get('versionedTranscriptId','') for a in candidate['transcriptAnnotations']]
    source_transcripts += [a[k].split(':')[0] for a in candidate['transcriptAnnotations'] for k in ('hgvsc','hgvsCoding')
                          if isinstance(a.get(k),str) and ':' in a[k]]
    # The source has its own explicit RefSeq; the selected CURRENT transcript
    # remains the normal user choice. The fallback is a named synthetic source
    # annotation here, never a production transcript-selection policy.
    transcript=next((t for t in source_transcripts if re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*',t)), 'NM_000001.1')
    allele=deepcopy(candidate.get('evidenceQueryAllele',candidate.get('allele',{})))
    if allele.get('genomeAssembly'):
        allele={'assembly':allele['genomeAssembly'],'contig':allele['chromosome'],'position':allele['position'],
                'reference':allele['reference'],'alternate':allele['alternate']}
    if not allele:
        allele={k:candidate[k] for k in ('assembly','contig','position','reference','alternate') if k in candidate}
    if not allele:
        raise ValueError('T06_NORMAL_SELECTED_ALLELE_REQUIRED')
    aliases=[a[k] for a in candidate['transcriptAnnotations'] if a.get('geneSymbol')==gene
             for k in ('hgvsc','hgvsp','hgvsCoding','hgvsProtein') if isinstance(a.get(k),str) and a[k]]
    coding=next(v.split(':')[-1] for v in aliases if re.fullmatch(r'c\.[1-9][0-9]*[ACGT]>[ACGT]',v.split(':')[-1]))
    identity_config,partner_coding,partner=identity(project,allele,gene,hgnc,transcript,coding)
    # Source transport supplies observed low-frequency counts for both alleles;
    # the real PM2 parser/policy still decides rarity. No MET label is supplied.
    info='AC=1;AN=2000000;AF=0.0000005;nhomalt=0;faf95=0.0000005;fafmax_faf95_max=0.0000005;fafmax_faf95_max_gen_anc=nfe;AC_nfe=1;AN_nfe=2000000;AF_nfe=0.0000005;nhomalt_nfe=0;faf95_nfe=0.0000005'
    transport='@echo off\r\necho x>> "%~dp0queries.txt"\r\n'
    for value in [allele,partner]:
        transport+=f'echo chr{value["contig"]}\t{value["position"]}\t.\t{value["reference"]}\t{value["alternate"]}\t.\tPASS\t{info}\r\n'
    if gene == 'CFTR':
        # Actual query outcome from the synthetic transport is NO_RECORD, not
        # an injected conclusion. The existing native all-sites reader decides
        # whether this exact locus is callable under the fixed PM2 policy.
        transport='@echo off\r\necho x>> "%~dp0queries.txt"\r\nexit /b 0\r\n'
    (project/'synthetic-tabix.cmd').write_text(transport,encoding='utf-8',newline='')
    if scenario not in {'GENERAL_HOMO','HOMO_CONFLICT'}:
        comment='ACMG/AMP criteria applied: PS3, PM2, PP3.'
        if scenario != 'APPROVAL_MISSING':comment+=' Classification performed using https://doi.org/'+DOI+'.'
        xml=(f'<VariationArchive VariationID="123" Accession="VCV000000123" Version="1" RecordType="classified">'
            '<RecordStatus>current</RecordStatus><Species>Homo sapiens</Species><ClassifiedRecord>'
            f'<SimpleAllele VariationID="123"><GeneList><Gene Symbol="{gene}" HGNC_ID="{hgnc}"/></GeneList><Location>'
            f'<SequenceLocation Assembly="{allele["assembly"]}" Chr="{allele["contig"]}" positionVCF="{partner["position"]}" '
            f'referenceAlleleVCF="{partner["reference"]}" alternateAlleleVCF="{partner["alternate"]}"/></Location></SimpleAllele>'
            '<ClinicalAssertionList><ClinicalAssertion><ClinVarAccession Accession="SCV000001" Version="1" OrgID="999" SubmitterName="SYNTHETIC VCEP"/>'
            '<RecordStatus>current</RecordStatus><Classification DateLastEvaluated="2025-01-01"><GermlineClassification>Pathogenic</GermlineClassification>'
            f'<ReviewStatus>reviewed by expert panel</ReviewStatus><Comment>{comment}</Comment></Classification>'
            f'<TraitSet><Trait><XRef DB="MONDO" ID="{disease.removeprefix("MONDO:")}"/></Trait></TraitSet>'
            '</ClinicalAssertion></ClinicalAssertionList></ClassifiedRecord></VariationArchive>').encode()
        identity_config['clinvar']=[{**pin(local,'partner.xml',xml),'accession':'VCV000000123'}]
    core=pin(local,'core-context.json',{'schemaVersion':'denovo-core-context-config-v1','identity':identity_config,'contexts':[]})
    rows=[{'hgncId':hgnc,'symbol':gene,'mondoId':disease,'diseaseLabel':'SYNTHETIC disorder','moi':'AR','classification':'Definitive','sourceRow':1}]
    background_source = {'kind': 'SYNTHETIC_TEST_ONLY'}
    if gene == 'CFTR':
        if resource_root is None: raise ValueError('T06_PINNED_SOURCE_ROOT_REQUIRED')
        context_root = Path(resource_root)/'local/resources/acmg-context/e4-a1a-2026-09-05'
        archive = (context_root/'Clingen-Gene-Disease-Summary-2026-09-05.csv.gz').read_bytes()
        raw = gzip.decompress(archive)
        if digest(archive) != '1ee6863e7b668b3749eab4447c4656049440ce602f961858f50f10d985358dea' or digest(raw) != '964b90650a947fef7b1d4be2520bc8c1afdf2871000f1e9a7824e98ce8e2df60':
            raise ValueError('T06_CLINGEN_ORIGINAL_PIN_MISMATCH')
        catalog_raw = (context_root/'clingen-catalog.json').read_bytes()
        manifest_raw = (context_root/'manifest.json').read_bytes()
        if digest(catalog_raw) != 'af957954c3ab82cb9c57dde002cd911cc7be88f71c807c216ad388f9f50623f4' or digest(manifest_raw) != '7019056f31ab44bb8955381baed1cbd24cb72c220898685e3dc7862128ba761b':
            raise ValueError('T06_CLINGEN_DERIVED_PIN_MISMATCH')
        exact = [r for r in parse(catalog_raw) if r.get('symbol') == gene and r.get('hgncId') == hgnc
                 and r.get('mondoId') == disease and r.get('moi') == 'AR' and r.get('classification') == 'Definitive']
        if len(exact) != 1 or exact[0]['sourceRow'] != 3511:
            raise ValueError('T06_CFTR_EXACT_RELATIONSHIP_REQUIRED')
        line = raw.decode('utf-8-sig').splitlines()[3510]
        if digest(line.encode()) != 'e83eb8d8c719c5a79960bbf152573be01dcc77ad9857658ec0852033be96299f':
            raise ValueError('T06_CLINGEN_EXACT_SOURCE_LINE_MISMATCH')
        relationship = pin(local,'disease.json',catalog_raw); manifest = pin(local,'disease-manifest.json',manifest_raw)
        background_source = {'kind': 'ACTUAL_PINNED_CLINGEN_RELATIONSHIP', 'rawResponseSha256': digest(raw),
                             'rawLineSha256': digest(line.encode()), 'relationship': exact[0]}
        atomic(local/'t06-disease-source-receipt.json', background_source)
    else:
        relationship=pin(local,'disease.json',rows)
        manifest=pin(local,'disease-manifest.json',{'profileId':'PATIENT_DISEASE_BINDING_INTAKE','profileVersion':'1.0.0','resources':{'clingen':{
            'resourceId':'CLINGEN_GENE_DISEASE_VALIDITY','sourceUrl':'https://search.clinicalgenome.org/kb/gene-validity/download',
            'derivedSha256':relationship['sha256'],'derivedRecordCount':1}}})
    w=parse((local/'w.json').read_bytes());w.update(coreContext=core,pm3DiseaseContext={'manifest':manifest,'catalog':relationship},
        pm3SpecificationContext=catalog(local,gene,hgnc,disease,transcript,scenario));atomic(local/'w.json',w)
    columns=[('Case','1'),('Gene',gene),('Transcript',transcript),('Allele 1',coding),
        ('Zygosity','homozygous' if scenario in {'GENERAL_HOMO','HOMO_CONFLICT'} else 'compound heterozygous'),('Diagnosis',disease),('Affected status','affected')]
    if scenario not in {'GENERAL_HOMO','HOMO_CONFLICT'}:
        columns += [('Allele 2',partner_coding)]
    if scenario != 'GENERAL_HOMO':
        columns += [('Phase','trans'),('Phase method',
            'Cloning and sequencing of both alleles confirmed the variants in '+('cis' if scenario in {'PHASE_CONFLICT','HOMO_CONFLICT'} else 'trans')+'.')]
    xml=('<article><front><article-meta><article-id pub-id-type="pmcid">'+PMCID+'</article-id><title-group><article-title>SYNTHETIC T06 only</article-title></title-group></article-meta></front>'
        '<body><sec><title>Methods</title><p>Patients were recruited at our hospital.</p></sec><sec><title>Results</title>'
        '<table-wrap id="t"><table><thead><tr>'+''.join('<th>'+k+'</th>' for k,v in columns)+'</tr></thead><tbody><tr>'+
        ''.join('<td>'+escape(v)+'</td>' for k,v in columns)+'</tr></tbody></table></table-wrap></sec></body></article>').encode()
    class NoDownload:
        def get(self,*args):raise AssertionError('No external transport in the fixture')
    files,bundle=source_bundle({'pmcid':PMCID},xml,prepare(xml,PMCID,digest(xml)),NoDownload(),False)
    registration=register(local/'library',PMCID,files,bundle,{'sha256':digest(xml),
        'acquisitionUrl':f'https://www.ebi.ac.uk/europepmc/webservices/rest/{PMCID}/fullTextXML'},criteria=('PM3',))
    atomic(local/'model.json',model_config());(local/'key.txt').write_text('synthetic-not-secret',encoding='utf-8')
    result={'schemaVersion':'t06-pm3-synthetic-source-v1','scenario':scenario,'phase':'COLD','sourceClass':'SYNTHETIC_TEST_ONLY',
        'pmcid':PMCID,'bundleSha256':registration['bundleSha256'],'candidateGene':gene,'allele':allele,'partnerAllele':partner,'coding':coding,
        'backgroundSource':background_source,
        'externalProviderCalls':'NOT_MEASURED','publicNetworkRequests':'NOT_MEASURED',
        'modelTransportKind':'SYNTHETIC_CACHE_SEED','syntheticTransportCalls':0,'clinicalApproval':'NONE'}
    atomic(local/'t06-pm3-fixture.json',result);return result


def warm(project,request):
    local=project/'local';manifest=parse((local/'t06-pm3-fixture.json').read_bytes())
    source=load(local/'library',PMCID,manifest['bundleSha256'],criterion='PM3')
    model=parse((local/'model.json').read_bytes());planned=requests(source,model['model'],model['maxOutputTokens'],model['maxRequestBytes']);calls=[]
    def transport(configuration,key,body):
        content=parse(parse(body)['messages'][1]['content']);calls.append(content['chunkIndex']);units=content['units']
        target_ids=set(content['workUnit']['targetUnitIds']);unit=next(u for u in units if u['unitId'] in target_ids)
        proposal={'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET','sourceAssessments':[
            {'materialKind':'UNCLEAR','citation':{'unitId':unit['unitId'],'quote':unit['text']}}]}
        anchor=next((u for u in units if u['kind'] in {'TABLE_CELL','SUPPLEMENT_CELL'} and u['text']=='1'),None)
        origin=next((u for u in units if u['kind']=='BODY_PARAGRAPH' and u['text']=='Patients were recruited at our hospital.'),None)
        coding=next((u for u in units if u['text']==manifest['coding']),None)
        if anchor and origin and coding:
            citation=lambda u:{'unitId':u['unitId'],'quote':u['text']}
            proposal['cases']=[{'caseLabel':'1','caseCitation':citation(anchor),'caseOrigin':'AUTHOR_COHORT','originCitations':[citation(origin)],
                'fields':[{'field':'targetVariant','sourceValue':manifest['coding'],'subject':'PROBAND','citation':citation(coding)}]}]
        return canonical({'model':model['expectedResponseModel'],'usage':{'prompt_tokens':10,'completion_tokens':20,'total_tokens':30},
            'choices':[{'finish_reason':'stop','message':{'content':json.dumps(proposal)}}]})
    archive=local/'seed';archive.mkdir(exist_ok=True)
    for index in range(len(planned)):
        for stage in ('extraction','review'):
            run(local/'library',PMCID,manifest['bundleSha256'],request['target'],model,local/'model.json',local/'budget',
                archive/f'chunk-{index:04d}-{stage}',chunk_index=index,previous=archive/f'chunk-{index:04d}-extraction/artifact.json' if stage=='review' else None,
                allow_model=True,transport=transport)
    manifest.update(phase='WARM',syntheticTransportCalls=len(calls),chunkCount=len(planned));atomic(local/'t06-pm3-fixture.json',manifest);return manifest


if __name__=='__main__':
    project,input_file,phase=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
    data=parse(input_file.read_bytes())
    result=cold(project,data,sys.argv[4],Path(sys.argv[5]) if len(sys.argv)>5 else None) if phase=='COLD' else warm(project,data)
    print(json.dumps({k:result[k] for k in ('phase','scenario','pmcid','externalProviderCalls','publicNetworkRequests','syntheticTransportCalls')}))
