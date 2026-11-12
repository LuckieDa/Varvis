"""Compile only the reviewed GN017 protein PM1 branch; no downloads or frozen edits."""
import copy
import hashlib
import itertools
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'varvis-server/src/main/resources/acmg'
PROFILE = 'pm1-hnf1a-business-profile-v1.json'
REGISTRY = 'acmg-rule-registry-pm1-hnf1a-2026-09-10'
AA = dict(zip(('Ala','Cys','Asp','Glu','Phe','Gly','His','Ile','Lys','Leu',
               'Met','Asn','Pro','Gln','Arg','Ser','Thr','Val','Trp','Tyr'), 'ACDEFGHIKLMNPQRSTVWY'))
CODE = dict(zip(map(''.join, itertools.product('TCAG', repeat=3)),
                'FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG'))


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def canonical(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2)+'\n').encode())


def build(source):
    raw = (source/'GN017_3.1.0.json').read_bytes()
    assert hashlib.sha256(raw).hexdigest() == '6195d0bf2f4cd7e86016ff13cdd78ad2696168b224fafc60f10cfc2409bc0ec1'
    doc = json.loads(raw)
    rules = [x['entContent'] for x in doc['ld']['RuleSet'][0]['ld']['CriteriaCode']]
    pm1 = next(x for x in rules if x['label'] == 'PM1')
    moderate = next(x['text'] for x in pm1['strengthDescriptor'] if x['strength'] == 'Moderate')
    supporting = next(x['text'] for x in pm1['strengthDescriptor'] if x['strength'] == 'Supporting')
    assert all(x in supporting for x in ('1-32', '107-174', '201-280'))
    residues = [dict(position=int(p), referenceAminoAcid=AA[a]) for a,p in re.findall(r'([A-Z][a-z]{2})(\d+)', moderate)]
    assert len(residues) == len({r['position'] for r in residues}) == 25
    model = read(RES/'pvs1-hnf1a-transcript-model-v1.json')
    assert canonical(model) == '270076043014c34aba9e7719ba7fa7bc7ecf090989c0bdb0fc08f7fa7cb0bacf'
    cds = model['cdsSequence']
    for r in residues:
        assert CODE[cds[3*r['position']-3:3*r['position']]] == r['referenceAminoAcid']
    html = (source/'gn017-live.html').read_bytes()
    assert hashlib.sha256(html).hexdigest() == '2fb9d33bb3d7a20f15997833eb2456eac762bcb06046f091545e805bfda5aa87'
    assert 'will not apply PM1 at any level' in html.decode()
    snapshot = dict(schemaVersion='pm1-hnf1a-source-projection-v1',
                    sourceUrl='https://cspec.genome.network/cspec/ui/svi/doc/GN017?version=3.1',
                    sourceJsonSha256=hashlib.sha256(raw).hexdigest(), sourceHtmlSha256=hashlib.sha256(html).hexdigest(),
                    sourceJsonLocator='/ld/RuleSet/0/ld/CriteriaCode/27/entContent',
                    specification='GN017', version='3.1', rule=pm1,
                    excludedRegion=dict(first=281,last=631,reason='GN017_TAD_NOT_PM1',sourceSection='PS3 additional explanatory note'),
                    retrievedOn='2026-09-10')
    write(RES/'pm1-hnf1a-source-v1.json', snapshot)
    registry = read(RES/'rule-registry-v2/acmg-rule-registry-hnf1a-revel-2026-09-10.json')
    registry['registryId'] = REGISTRY
    row = next(x for x in registry['criteria'] if x['code'] == 'PM1')
    row.update(implementationStatus='V2_IMPLEMENTED', notes='Only the separately pinned GN017 3.1 HNF1A protein missense PM1 policy is executable. General PM1 and other contexts remain outside this runtime.')
    registry_path = RES/'rule-registry-v2'/f'{REGISTRY}.json'
    write(registry_path, registry)
    predecessor = read(RES/'pvs1-hnf1a-business-profile-v3.json')
    profile = dict(profileSchemaVersion='pm1-hnf1a-business-profile-v1', profileId='HNF1A_PROTEIN_REGION_PM1',profileVersion='1.0.0',
                   activation=predecessor['activation'],context=predecessor['context'],
                   policy=dict(policyId='HNF1A_GN017_3_1_PROTEIN_PM1',criterionId='ACMG_AMP_2015:PM1',
                               authority=predecessor['policy']['authority'],moderateResidues=residues,
                               supportingRegions=[dict(first=a,last=b) for a,b in [(1,32),(107,174),(201,280)]],
                               excludedRegions=[snapshot['excludedRegion']],variantTypes=['SNV'],consequence='missense_variant',
                               strengthPrecedence=['MODERATE','SUPPORTING'],noncanonicalSplicing='OUTSIDE_THIS_PROTEIN_BRANCH',
                               mechanism='DISEASE_SPECIFIC_CRITICAL_PROTEIN_LOCATION',classificationEnabled=False),
                   transcriptModel=predecessor['transcriptModel'],
                   sourceBinding=dict(classpathResource='/acmg/pm1-hnf1a-source-v1.json',canonicalSha256=canonical(snapshot)),
                   registryBinding=dict(registryId=REGISTRY,registrySchemaVersion='acmg-rule-registry-v2',
                                        registrySha256=hashlib.sha256(registry_path.read_bytes()).hexdigest(),
                                        registryClasspathResource=f'/acmg/rule-registry-v2/{REGISTRY}.json',criterionIds=['ACMG_AMP_2015:PM1']),
                   resources=copy.deepcopy(predecessor['resources']))
    profile['policy']['authority'].pop('decisionTreeUrl',None)
    profile['policy']['authority'].pop('decisionTreeSha256',None)
    profile['policy']['authority'].update(sourceJsonSha256=snapshot['sourceJsonSha256'],sourceSection='PM1; PS3 transactivation-domain exclusion note')
    write(RES/PROFILE,profile)
    print(json.dumps({'profileSha256':canonical(profile),'registrySha256':profile['registryBinding']['registrySha256']}))


if __name__ == '__main__':
    build(Path(sys.argv[1]))
