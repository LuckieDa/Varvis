"""Derive the bounded HNF1A policy from independently retrieved public sources.

Usage: python -I -S scripts/pvs1/build_hnf1a_profile.py SOURCE_DIRECTORY
Reads small files only; performs no downloads and never edits frozen contracts.
"""
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'varvis-server/src/main/resources/acmg'


def canonical(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                     ensure_ascii=False).encode()).hexdigest()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Freeze the accepted snapshot bytes across hosts, including line endings;
    # the registry binding is a raw file hash (the other identities are canonical).
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\r\n')


def build(source):
    gb = (source / 'refseq.gb').read_text(encoding='utf-8')
    tx = json.loads((source / 'ensembl-lookup.json').read_text())
    seq_record = json.loads((source / 'ensembl-cds.json').read_text())
    assert 'VERSION     NM_000545.8' in gb
    assert 'ENST00000257555.11/ ENSP00000257555.5' in gb
    assert '/protein_id="NP_000536.6"' in gb
    assert tx['id'] == 'ENST00000257555' and tx['version'] == 11
    assert tx['assembly_name'] == 'GRCh38' and tx['strand'] == 1
    assert tx['Parent'] == 'ENSG00000135100' and tx['seq_region_name'] == '12'
    start, end = map(int, re.search(r'     CDS\s+(\d+)\.\.(\d+)', gb).groups())
    sequence = ''.join(re.findall('[a-z]+', gb.split('ORIGIN')[1].split('//')[0])).upper()
    cds = sequence[start - 1:end]
    assert seq_record['id'] == tx['id'] and seq_record['seq'] == cds
    assert len(cds) == 1896 and cds.startswith('ATG') and cds[-3:] in ('TAA', 'TAG', 'TGA')
    ref_exons = [tuple(map(int, pair)) for pair in re.findall(r'     exon\s+(\d+)\.\.(\d+)', gb)]
    exons = []
    offset = 0
    for ordinal, (exon, (lo, hi)) in enumerate(zip(tx['Exon'], ref_exons, strict=True), 1):
        length = exon['end'] - exon['start'] + 1
        assert (lo, hi) == (offset + 1, offset + length)
        exons.append(dict(ordinal=ordinal, genomicStart=exon['start'], genomicEnd=exon['end'],
                          cdnaStart=lo, cdnaEnd=hi, cdsStart=lo-start+1, cdsEnd=hi-start+1))
        offset += length
    assert len(exons) == 10 and offset == len(sequence) == tx['length']
    assert exons[-2]['cdsEnd'] == 1768 and start == 227
    page = (source / 'cspec.html').read_text(encoding='utf-8')
    assert 'Version 3.1' in page and 'NM_000545.8' in page and 'MONDO:0015967' in page
    assert '5af96ed6-b35b-4704-be42-fe069d5e7426' in page
    assert (source / 'hnf1a-pvs1-tree.bin').read_bytes().startswith(b'%PDF-')
    urls = {
        'cspec.html': 'https://cspec.genome.network/cspec/ui/svi/doc/GN017?version=3.1',
        'hnf1a-pvs1-tree.bin': 'https://cspec.genome.network/cspec/File/id/5af96ed6-b35b-4704-be42-fe069d5e7426/data',
        'refseq.gb': 'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=nuccore&id=NM_000545.8&rettype=gbwithparts&retmode=text',
        'ensembl-lookup.json': 'https://rest.ensembl.org/lookup/id/ENST00000257555?expand=1&content-type=application/json',
        'ensembl-cds.json': 'https://rest.ensembl.org/sequence/id/ENST00000257555?type=cds;content-type=application/json',
    }
    model = dict(schemaVersion='hnf1a-transcript-model-v1', assembly='GRCh38', contig='12', strand=1,
                 hgncId='HGNC:11621', ensemblGeneId=tx['Parent'], transcript='ENST00000257555.11',
                 refseqTranscript='NM_000545.8', protein='ENSP00000257555.5', refseqProtein='NP_000536.6',
                 cdsStartInCdna=start, cdsLength=len(cds), proteinLength=631, cdsSequence=cds,
                 lastJunctionCdsPosition=1768, exons=exons,
                 sourceRecords=[dict(file=f, url=u, sha256=sha(source/f), sizeBytes=(source/f).stat().st_size,
                                     retrievedOn='2026-09-09') for f,u in urls.items()])
    model_file = RES / 'pvs1-hnf1a-transcript-model-v1.json'
    write(model_file, model)
    registry_file = RES / 'rule-registry-v2/acmg-rule-registry-pvs1-hnf1a-2026-09-09.json'
    old_file = RES / 'rule-registry-v2/acmg-rule-registry-e3-l1a-2026-09-05.json'
    registry = json.loads(old_file.read_text())
    registry['registryId'] = 'acmg-rule-registry-pvs1-hnf1a-2026-09-09'
    # v2 lineage records the frozen v1 scientific base. Keep that contract intact;
    # the business profile records the executable predecessor separately.
    # General authority stays general. The separately pinned criterion-only policy selects GN017.
    next(r for r in registry['criteria'] if r['code']=='PVS1')['notes'] = (
        'HNF1A stop-gained NMD business successor uses a separately pinned criterion-only GN017 3.1 policy; '
        'general PVS1 and other HNF1A decision-tree branches are not implemented by this successor.')
    write(registry_file, registry)
    profile = dict(profileSchemaVersion='pvs1-hnf1a-business-profile-v1', profileId='HNF1A_STOP_GAINED_NMD_PVS1',
                   profileVersion='1.0.0', predecessorProfileId='HNF1A_FIXED_POINT_PVS1_VERY_STRONG',
                   activation=dict(patientFlowEnabled=True, automaticTrigger=False, classificationEnabled=False),
                   context=dict(hgncId='HGNC:11621', geneSymbol='HNF1A', mondoId='MONDO:0015967',
                                moi='AUTOSOMAL_DOMINANT', assembly='GRCh38', transcript='ENST00000257555.11'),
                   policy=dict(policyId='HNF1A_GN017_3_1_STOP_GAINED_NMD', criterionId='ACMG_AMP_2015:PVS1',
                               appliedStrength='VERY_STRONG', authority=dict(specificationId='GN017', authorityVersion='3.1',
                               sourceUrl=urls['cspec.html'], sourceSha256=sha(source/'cspec.html'),
                               decisionTreeUrl=urls['hnf1a-pvs1-tree.bin'], decisionTreeSha256=sha(source/'hnf1a-pvs1-tree.bin')),
                               minimumCdsPosition=355, exclusivePtcEnd=1714,
                               firstExon=2, lastExon=9, maximumExonLength=400,
                               startProximalScope='AFTER_MET118; engineering scope restriction, not a new ClinGen threshold',
                               nmdEscapeScope='NOT_IMPLEMENTED; GN017 may still support PVS1 outside this NMD branch',
                               spliceConsequencesScope='NOT_IMPLEMENTED', evidenceMechanism='PREDICTED_LOSS_OF_FUNCTION'),
                   transcriptModel=dict(classpathResource='/acmg/'+model_file.name, canonicalSha256=canonical(model)),
                   registryBinding=dict(registryId=registry['registryId'], registrySchemaVersion='acmg-rule-registry-v2',
                                        registrySha256=sha(registry_file), registryClasspathResource='/acmg/rule-registry-v2/'+registry_file.name,
                                        criterionIds=['ACMG_AMP_2015:PVS1']),
                   resources=json.loads((RES/'spliceai-business-vep-identity-v1.json').read_text())['resources'])
    write(RES/'pvs1-hnf1a-business-profile-v1.json', profile)
    business = json.loads((RES/'acmg-business-execution-profile-pm2-v1.json').read_text())
    business['profileId']='acmg-explicit-business-pvs1-hnf1a-v1'
    business['predecessorProfileId']='acmg-explicit-business-pm2-v1'
    business['enabledCriteria'].append('ACMG_AMP_2015:PVS1')
    business['slices'].append(dict(criterionIds=['ACMG_AMP_2015:PVS1'],profileId=profile['profileId'],profileVersion='1.0.0',
                                   profileCanonicalSha256=canonical(profile), registryBinding=profile['registryBinding'],
                                   resources=[{k:r[k] for k in ('resourceId','resourceVersion','identityFingerprintSha256')} for r in profile['resources']],
                                   scope='HNF1A_GN017_3_1_STOP_GAINED_NMD',frozenContextBundleRequired=False))
    write(RES/'acmg-business-execution-profile-pvs1-hnf1a-v1.json', business)
    print(json.dumps(dict(profileSha256=canonical(profile), modelSha256=canonical(model), cdsMatch=True,
                          exonStructureMatch=True, sourceCount=len(urls))))


if __name__ == '__main__':
    build(Path(sys.argv[1]))
