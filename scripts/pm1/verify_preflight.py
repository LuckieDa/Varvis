"""Verify small public controls against fresh reference records, indexed FASTA and actual VEP."""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from scripts.pm1.verify_hnf1a_pm1 import CODE, derive_protein, equal, file_sha, location, read, require, verify_policy


def indexed_sequence(fasta, first, last):
    rows=[line.split('\t') for line in Path(str(fasta)+'.fai').read_text().splitlines()]
    row=next(r for r in rows if r[0]=='12')
    _,length,offset,bases,width=row[:5]; length,offset,bases,width=map(int,(length,offset,bases,width))
    require(1<=first<=last<=length and last-first<2000,'bounded indexed query')
    start=first-1; result=bytearray()
    with fasta.open('rb') as stream:
        while start<last:
            count=min(bases-start%bases,last-start)
            stream.seek(offset+start//bases*width+start%bases); result.extend(stream.read(count));start+=count
    return result.decode().upper()


def verify(archive, resource_root, with_vep):
    source=archive/'sources'; pre=archive/'preflight'; profile,model=verify_policy(source)
    lookup,sequence=read(source/'ensembl-lookup.json'),read(source/'ensembl-cds.json')
    equal((lookup['id'],lookup['version'],lookup['assembly_name'],lookup['strand']),('ENST00000257555',11,'GRCh38',1),'fresh Ensembl identity')
    equal((lookup['Translation']['id'],lookup['Translation']['version'],lookup['Translation']['length']),('ENSP00000257555',5,631),'fresh protein')
    equal(sequence['seq'],model['cdsSequence'],'fresh Ensembl CDS')
    equal([(e['start'],e['end']) for e in lookup['Exon']],[(e['genomicStart'],e['genomicEnd']) for e in model['exons']],'fresh exon mapping')
    gb=(source/'refseq.gb').read_text(); require('NM_000545.8' in gb and 'NP_000536.6' in gb and 'ENST00000257555.11' in gb and 'ENSP00000257555.5' in gb,'RefSeq cross references')
    cdna=re.sub('[^acgt]','',gb.split('ORIGIN')[1].split('//')[0]).upper()
    match=re.search(r'^\s+CDS\s+(\d+)\.\.(\d+)',gb,re.M);require(match is not None,'RefSeq CDS feature')
    equal(cdna[int(match[1])-1:int(match[2])],model['cdsSequence'],'fresh RefSeq CDS')
    protein=''.join(CODE[model['cdsSequence'][i:i+3]] for i in range(0,1893,3))
    translation=re.search('/translation="([^"]+)"',gb);require(translation is not None,'RefSeq translation')
    equal(re.sub(r'\s','',translation[1]),protein,'fresh RefSeq protein')
    fasta=resource_root/'vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa'
    assembled=[]
    for e in model['exons']:
        first=max(1,e['cdsStart']);last=min(1896,e['cdsEnd'])
        assembled.append(indexed_sequence(fasta,e['genomicStart']+first-e['cdsStart'],e['genomicStart']+last-e['cdsStart']))
    equal(''.join(assembled),model['cdsSequence'],'native genomic FASTA spliced CDS')
    manifest=read(pre/'fixed-panel.json');equal(file_sha(pre/'fixed-public.vcf'),manifest['vcfSha256'],'fixed VCF')
    result={'referenceIdentity':'PASS','splicedCdsBases':1896,'proteinLength':631,'controls':[]}
    raw=[json.loads(line) for line in (pre/'raw-vep.jsonl').read_text().splitlines()] if with_vep else []
    freq=read(pre/'frequency-precheck.json') if with_vep else None
    for control in manifest['controls']:
        d=derive_protein(control,model);strength,_=location(d['proteinPosition'],d['refAminoAcid'])
        equal(strength,control['expectedStrength'],'predeclared location stratum')
        equal(indexed_sequence(fasta,control['position'],control['position']),control['reference'],'native REF')
        row={'position':control['position'],**d,'expectedStrength':strength}
        if with_vep:
            records=[r for r in raw if r['start']==control['position'] and r['allele_string']==control['reference']+'/'+control['alternate']]
            equal(len(records),1,'unique VEP input')
            selected=[t for t in records[0]['transcript_consequences'] if t['transcript_id']=='ENST00000257555']
            equal(len(selected),1,'unique VEP transcript');t=selected[0]
            for key,expected in [('hgvsc',d['hgvsc']),('hgvsp',d['hgvsp']),('protein_start',d['proteinPosition']),('exon',d['exon']),('amino_acids',d['refAminoAcid']+'/'+d['altAminoAcid'])]: equal(t[key],expected,'VEP '+key)
            equal(t['codons'].upper(),d['refCodon']+'/'+d['altCodon'],'VEP codons')
            require('missense_variant' in t['consequence_terms'],'VEP missense')
            f=next(a for a in freq['alleles'] if a['allele']==f"GRCh38:12:{control['position']}:{control['reference']}:{control['alternate']}")
            require(f['frequencyFilter']=='PASS' and float(f['selectedMaxPercent'])<=freq['inheritanceModesPercent']['AUTOSOMAL_DOMINANT'],'original frequency reachability')
            row.update(vep='PASS',selectedFrequencyPercent=f['selectedMaxPercent'])
        result['controls'].append(row)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True);p.add_argument('--resource-root',type=Path,required=True);p.add_argument('--with-vep',action='store_true');p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();result=verify(a.archive,a.resource_root,a.with_vep);text=json.dumps(result,indent=2);a.output.write_text(text+'\n',encoding='utf-8');print(text)
