"""Explicit synthetic PM3 source and cache transport for normal Java tasks.

The cold source follows actual recovered candidate annotations. The warm pass
uses the real Java task request, original source and normal model receipt/cache
contract; it supplies no resolved genotype, phase, disease, rarity or score.
No provider, network, source adapter, evaluator or qualifier is replaced.
"""
import json
from pathlib import Path
import re
import sys
from xml.sax.saxutils import escape
from bundle_model_contract import load,requests
from model_literature_contract import canonical,digest,parse
from model_transport import atomic
from literature_search_transport import filesystem_path
from prepare_literature import prepare
from acquire_public_literature import source_bundle
from paper_library_registration import register
from run_pm3_literature_model import run
from pm3_literature_contract import VERSION,SCHEMA

PMCID='PMC902031'


def config():
    return {'schemaVersion':'denovo-model-config-v1','enabled':True,'pricingConfirmed':True,
        'protocol':'openai-chat-completions-json-schema','endpoint':'https://example.invalid/v1/chat/completions',
        'model':'gemini-3-flash','expectedResponseModel':'gemini-3-flash','maxCalls':100,
        'maxOutputTokens':8192,'maxBilledOutputTokens':16384,'timeoutSeconds':1,
        'maxRequestBytes':262144,'maxResponseBytes':1048576,'inputCreditsPerMillion':1,
        'outputCreditsPerMillion':6,'creditsPerRmb':3,'budgetRmb':16.66,
        'apiKeyEnvironment':'VARVIS_TEST_FAKE','apiKeyFile':'key.txt'}


def cold(project, options):
    project=filesystem_path(project);local=project/'local';local.mkdir(exist_ok=True)
    (local/'library').mkdir(exist_ok=True)
    candidate=options['candidates'][0];gene=candidate['geneSymbol'];aliases=[]
    assert re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]{0,39}',gene)
    for tx in candidate['transcriptAnnotations']:
        if tx.get('geneSymbol')!=gene:continue
        for field in ('hgvsc','hgvsp','hgvsCoding','hgvsProtein'):
            value=tx.get(field)
            if isinstance(value,str) and value and value not in aliases:
                aliases.append(value)
                if ':' in value:
                    short=value.split(':',1)[1]
                    if short not in aliases:aliases.append(short)
    assert aliases
    # Explicitly missing partner/phase/diagnostic facts, not a fabricated negative.
    text=' '.join(aliases)
    xml=(f'<article><front><article-meta><article-id pub-id-type="pmcid">{PMCID}</article-id>'
         '<title-group><article-title>SYNTHETIC TEST ONLY PM3 incomplete case</article-title></title-group>'
         '</article-meta></front><body><p>SYNTHETIC TEST ONLY. '
         f'Case 1 in {escape(gene)} carries {escape(text)}. '
         'Partner allele, phase, independent classification, rarity and diagnostic background were not provided.</p>'
         '<table-wrap><caption><p>SYNTHETIC TEST ONLY observations</p></caption><table><thead><tr>'
         '<th>Case</th><th>Gene</th><th>Target variant</th><th>Genotype</th></tr></thead><tbody><tr>'
         f'<td>Case 1</td><td>{escape(gene)}</td><td>{escape(aliases[0])}</td><td>heterozygous</td>'
         '</tr></tbody></table></table-wrap></body></article>').encode()
    class NoDownload:
        def get(self,*args):raise AssertionError('Fixture has no external supplement')
    files,bundle=source_bundle({'pmcid':PMCID},xml,prepare(xml,PMCID,digest(xml)),NoDownload(),False)
    registration=register(local/'library',PMCID,files,bundle,{'sha256':digest(xml),
        'acquisitionUrl':f'https://www.ebi.ac.uk/europepmc/webservices/rest/{PMCID}/fullTextXML'},criteria=('PM3',))
    atomic(local/'model.json',config());(local/'key.txt').write_text('synthetic-not-secret',encoding='utf-8')
    manifest={'schemaVersion':'t02-pm3-java-source-fixture-v1','phase':'COLD','sourceClass':'SYNTHETIC_TEST_ONLY',
        'pmcid':PMCID,'bundleSha256':registration['bundleSha256'],'rawSourceSha256':digest(xml),
        'candidateGene':gene,'candidateAliases':aliases,'actualProviderCalls':0,'networkRequests':0,
        'syntheticTransportCalls':0,'scientificVerdict':'NOT_SUPPLIED','clinicalApproval':'NONE'}
    atomic(local/'t02-pm3-fixture.json',manifest);return manifest


def warm(project, request):
    project=filesystem_path(project);local=project/'local'
    manifest=parse((local/'t02-pm3-fixture.json').read_bytes())
    assert request['pmcid']==manifest['pmcid'] and request['bundleSha256']==manifest['bundleSha256']
    assert request['target']['geneSymbol']==manifest['candidateGene']
    assert all(a in manifest['candidateAliases'] for a in request['target']['variantAliases'])
    model=parse((local/'model.json').read_bytes());source=load(local/'library',PMCID,manifest['bundleSha256'],criterion='PM3')
    planned=requests(source,model['model'],model['maxOutputTokens'],model['maxRequestBytes']);calls=[]
    def transport(configuration,key,body):
        content=parse(parse(body)['messages'][1]['content']);calls.append(content['chunkIndex'])
        unit=next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
        # The real recovered ranking may retain many transcript aliases in one
        # source unit. Keep the complete input and quote an exact bounded span;
        # this UNCLEAR source assessment asserts no biological or clinical fact.
        maximum=SCHEMA['properties']['sourceAssessments']['items']['properties']['citation']['properties']['quote']['maxLength']
        proposal={'schemaVersion':VERSION,'cases':[],'coverage':'EXTRACTED_WITHIN_TARGET',
            'sourceAssessments':[{'materialKind':'UNCLEAR','citation':{'unitId':unit['unitId'],'quote':unit['text'][:maximum]}}]}
        return canonical({'model':model['expectedResponseModel'],
            'usage':{'prompt_tokens':10,'completion_tokens':20,'total_tokens':30},
            'choices':[{'finish_reason':'stop','message':{'content':json.dumps(proposal)}}]})
    archive=local/'seed';archive.mkdir(exist_ok=True)
    for index in range(len(planned)):
        for stage in ('extraction','review'):
            dest=archive/f'chunk-{index:04d}-{stage}'
            run(local/'library',PMCID,manifest['bundleSha256'],request['target'],model,local/'model.json',
                local/'budget',dest,chunk_index=index,
                previous=archive/f'chunk-{index:04d}-extraction/artifact.json' if stage=='review' else None,
                allow_model=True,transport=transport)
    manifest.update(phase='WARM',requestSha256=digest(canonical(request)),
        exactTaskTarget=request['target'],syntheticTransportCalls=len(calls),chunkCount=len(planned),
        meaning='Receipt-valid synthetic cache only; task replays and assesses independently')
    atomic(local/'t02-pm3-fixture.json',manifest);return manifest


def main():
    project,input_file,phase=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
    data=parse(input_file.read_bytes())
    result=cold(project,data) if phase=='COLD' else warm(project,data) if phase=='WARM' else None
    if result is None:raise ValueError('T02_PM3_FIXTURE_PHASE')
    print(json.dumps({k:result[k] for k in ('phase','pmcid','bundleSha256','actualProviderCalls','networkRequests','syntheticTransportCalls')}))


if __name__=='__main__':main()
