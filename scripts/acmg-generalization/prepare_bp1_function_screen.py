"""Compile pinned published functional rows into a BP1 review-only screen.

Offline, data-driven column/scope mapping. No inferred clinical classification,
normal-RNA claim, automatic source discovery, or gene-specific algorithm.
Requires openpyxl only for read-only source workbook access.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import zipfile
from xml.etree import ElementTree
from openpyxl import load_workbook
from build_transcript_model import CODONS


def require(ok, reason):
    if not ok: raise ValueError(reason)


def archived_cell(value):
    # These measurements are retained for review, never used as a BP1 threshold.
    # Runtime identity forbids floating-point JSON; preserve the decoded numeric
    # type and its round-trip decimal text, with the exact XLSX bytes separately pinned.
    if isinstance(value, float):
        require(math.isfinite(value), 'FUNCTION_SCREEN_NONFINITE_CELL')
        return {'decodedCellType': 'number', 'decimalText': repr(value)}
    return value


def pinned(source, limit):
    path=Path(source['file'])
    require(path.stat().st_size<=limit, 'FUNCTION_SCREEN_SOURCE_SIZE_LIMIT')
    raw=path.read_bytes()
    require(hashlib.sha256(raw).hexdigest()==source['sha256'], 'FUNCTION_SCREEN_SOURCE_PIN_MISMATCH')
    return raw


def compile_screen(config):
    require(config.get('schemaVersion')=='bp1-function-screen-plan-v1'
            and config.get('clinicalAdoption') is False, 'FUNCTION_SCREEN_RESEARCH_PLAN_REQUIRED')
    workbook=pinned(config['workbook'],8_000_000)
    require(workbook[:4]==b'PK\x03\x04','FUNCTION_SCREEN_WORKBOOK_REQUIRED')
    with zipfile.ZipFile(Path(config['workbook']['file'])) as archive:
        require(len(archive.infolist())<=200 and sum(x.file_size for x in archive.infolist())<=64_000_000,
                'FUNCTION_SCREEN_UNCOMPRESSED_SIZE_LIMIT')
    article=pinned(config['article'],4_000_000)
    xml=ElementTree.fromstring(article)
    paragraphs=[''.join(x.itertext()) for x in xml.iter('p')]
    excerpts=[]
    for sha in config['paragraphSha256']:
        found=[(i+1,t) for i,t in enumerate(paragraphs) if hashlib.sha256(t.encode()).hexdigest()==sha]
        require(len(found)==1,'FUNCTION_SCREEN_SOURCE_PARAGRAPH_AMBIGUOUS')
        excerpts.append({'paragraphOrdinal':found[0][0],'text':found[0][1],'sha256':sha})
    require(excerpts,'FUNCTION_SCREEN_SOURCE_EXCERPTS_REQUIRED')
    model=json.loads(pinned(config['model'],2_000_000))
    cds=model['cdsSequence'];cds_sha=hashlib.sha256(cds.encode()).hexdigest()
    require(re.fullmatch('[ACGT]+',cds) and len(cds)%3==0
            and cds_sha==model['sequenceVerification']['cdsSha256'], 'FUNCTION_SCREEN_MODEL_CDS_INVALID')
    protein=''.join(CODONS[cds[i:i+3]] for i in range(0,len(cds),3))
    require(protein.endswith('*') and '*' not in protein[:-1], 'FUNCTION_SCREEN_MODEL_PROTEIN_INVALID')
    tx, pid=config['transcript'],config['protein']
    require(tx in '\n'.join(x['text'] for x in excerpts) and pid in '\n'.join(x['text'] for x in excerpts),
            'FUNCTION_SCREEN_PUBLISHED_SEQUENCE_IDENTITY_REQUIRED')
    bridge=model.get('transcriptBridge',{})
    require(model['hgncId']==config['hgncId'] and
            (model['transcript']==tx and model['protein']==pid or
             bridge.get('status')=='FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE' and bridge.get('refseqTranscript')==tx
             and bridge.get('refseqProtein')==pid and bridge.get('ensemblTranscript')==model['transcript']
             and bridge.get('ensemblProtein')==model['protein']), 'FUNCTION_SCREEN_MODEL_IDENTITY_MISMATCH')
    ranges=config['ranges']
    require(isinstance(ranges,list) and ranges and all(type(r['start']) is int and type(r['end']) is int
            and 1<=r['start']<=r['end']<len(protein) for r in ranges), 'FUNCTION_SCREEN_RANGE_INVALID')
    wb=load_workbook(Path(config['workbook']['file']),read_only=True,data_only=False)
    try:
        sheet=wb[config['sheet']]
        require(sheet.max_row is not None and sheet.max_row<=100_000 and sheet.max_column is not None
                and sheet.max_column<=128, 'FUNCTION_SCREEN_TABLE_SIZE_LIMIT')
        rows=sheet.iter_rows(values_only=True);headers=None;raw_headers=None;records=[];seen=set()
        norm=lambda s:' '.join((s or '').split())
        for number,values in enumerate(rows,1):
            if number==config['headerRow']:
                raw_headers=list(values)
                headers=[norm(v) if isinstance(v,str) else None for v in values]
                for column in config['columns'].values():require(headers.count(column)==1,'FUNCTION_SCREEN_COLUMN_AMBIGUOUS')
                continue
            if number<config['headerRow'] or not headers:continue
            get=lambda key:values[headers.index(config['columns'][key])]
            if get('variantType')!=config['missenseLabel']:continue
            key=get('proteinVariant');status=get('classification')
            match=re.fullmatch(r'([ACDEFGHIKLMNPQRSTVWY])([1-9][0-9]*)([ACDEFGHIKLMNPQRSTVWY])',str(key))
            require(match and key not in seen and match[1]!=match[3], 'FUNCTION_SCREEN_VARIANT_AMBIGUOUS')
            position=int(match[2])
            require(position<len(protein) and protein[position-1]==match[1]
                    and any(r['start']<=position<=r['end'] for r in ranges), 'FUNCTION_SCREEN_RESIDUE_OR_COVERAGE_MISMATCH')
            require(status in config['classMap'] and config['classMap'][status] in ('DAMAGING','INTERMEDIATE','FUNCTIONAL'),
                    'FUNCTION_SCREEN_CLASS_UNSUPPORTED')
            require(not any(isinstance(v,str) and v.startswith('=') for v in values),'FUNCTION_SCREEN_FORMULA_NOT_RAW_OBSERVATION')
            seen.add(key)
            records.append({'proteinVariantKey':key,'reportedClass':config['classMap'][status],
                'sourceRow':number,'sourceSheet':sheet.title,'originalRow':{'cells':[archived_cell(v) for v in values]}})
    finally:wb.close()
    require(dict(Counter(r['reportedClass'] for r in records))==config['expectedCounts'],'FUNCTION_SCREEN_EXPECTED_COUNTS_MISMATCH')
    source={'sourceId':config['sourceId'],'url':config['sourceUrl'],'rawSha256':config['workbook']['sha256'],
        'articleRawSha256':config['article']['sha256'],'originalHeaders':raw_headers,'normalizedColumnLabels':headers,'excerpts':excerpts,
        'numericCellRepresentation':'DECODED_ROUND_TRIP_DECIMAL_TEXT; exact source bytes retained in pinned XLSX; not a scientific threshold',
        'modelRawSha256':config['model']['sha256'],'classificationMapping':config['classMap']}
    return {'schemaVersion':'bp1-published-function-screen-v1','purpose':'RESEARCH_REVIEW_BLOCK_ONLY',
        'coverage':'LOADED_PUBLICATIONS_ONLY','clinicalAdoption':False,'absenceMeansNormal':False,
        'screens':[{'screenId':config['screenId'],'hgncId':config['hgncId'],'transcript':tx,'protein':pid,
            'cdsSha256':cds_sha,'source':source,'ranges':ranges,'records':records}]}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--plan',required=True);p.add_argument('--plan-sha256',required=True);p.add_argument('--output',required=True)
    a=p.parse_args();config=json.loads(pinned({'file':a.plan,'sha256':a.plan_sha256},1_000_000));result=compile_screen(config)
    with Path(a.output).open('xb') as f:f.write((json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode())
    print(json.dumps({'screens':len(result['screens']),'records':sum(len(s['records']) for s in result['screens']),'clinicalAdoption':False}))


if __name__=='__main__':main()
