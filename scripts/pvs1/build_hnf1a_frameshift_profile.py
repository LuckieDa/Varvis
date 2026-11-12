"""Derive only the new HNF1A frameshift policy/registry and joint business successor.

No downloads; all predecessor files and the verified transcript model remain unchanged.
The GN017 3.1 text/tree were rechecked on 2026-09-10 with identical source hashes.
"""
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'varvis-server/src/main/resources/acmg'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def canonical(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode('utf-8')).hexdigest()


def write(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2)+'\n').encode('utf-8'))


def build():
    before = read(RES / 'pvs1-hnf1a-business-profile-v1.json')
    assert canonical(before) == 'bd9702b1ea32b63c6ce0ee9f3a786cf601bc00192fdddb21a2814f096d74bafc'
    profile = copy.deepcopy(before)
    profile.update(profileSchemaVersion='pvs1-hnf1a-business-profile-v2',
                   profileId='HNF1A_TRUNCATING_NMD_PVS1', profileVersion='2.0.0',
                   predecessorProfileId=before['profileId'], predecessorProfileSha256=canonical(before))
    policy = profile['policy']
    policy['policyId'] = 'HNF1A_GN017_3_1_TRUNCATING_NMD'
    policy['authorityReverifiedOn'] = '2026-09-10'
    policy['frameshift'] = dict(
        variantTypes=['SMALL_INSERTION', 'SMALL_DELETION'],
        maximumAlleleLengthIncludingAnchor=50, maximumInsertedOrDeletedBases=49,
        lengthBoundary='ENGINEERING: current evidence-v2 normalized REF/ALT limit, including the VCF anchor',
        minimumDistanceToLastJunction=55,
        nmdCoordinates='ALTERED_CDS_PTC_END_AND_ALTERED_LAST_JUNCTION',
        nmdMarginBoundary='Conservative engineering margin preserving predecessor strict PTC-end < reference c.1714; not a new ClinGen threshold',
        sequenceDerivation='ENTIRE_VERIFIED_CDS_ALLELE_EDIT_HGVSC_EQUIVALENCE_AND_FIRST_TRANSLATED_STOP',
        proteinValidation='EXACT_VERSIONED_HGVSP_AND_ALLELE_LOCAL_VEP_CODONS_AMINO_ACIDS',
        exonBoundary='ENTIRE_SIMPLE_EDIT_AND_NEW_PTC_WITHIN_VERIFIED_CODING_EXONS',
        exonLengthScope='ALTERED_VARIANT_EXON_AND_ALTERED_PTC_EXON_MUST_EACH_SATISFY_EXISTING_MAXIMUM',
        unknownTermination='NOT_EVALUATED; no unverified UTR extension or assumed terminal stop',
        excluded=['COMPLEX_DELINS', 'SPLICE_CONSEQUENCES', 'CROSS_EXON_EDITS', 'PTC_SPANNING_EXON_JUNCTION',
                  'HAPLOTYPE_COMPENSATION', 'START_PROXIMAL', 'NMD_ESCAPE', 'LONG_EXONS', 'OTHER_TRANSCRIPTS'])
    registry = read(RES / 'rule-registry-v2/acmg-rule-registry-pvs1-hnf1a-2026-09-09.json')
    registry['registryId'] = 'acmg-rule-registry-pvs1-hnf1a-frameshift-2026-09-10'
    next(row for row in registry['criteria'] if row['code'] == 'PVS1')['notes'] = (
        'HNF1A truncating NMD business successor separately pins GN017 3.1. It preserves the prior '
        'stop-gained SNV branch and adds bounded simple coding insertions/deletions only when whole-CDS '
        'sequence derivation, versioned VEP annotation and altered-transcript NMD coordinates agree. '
        'Other decision-tree branches and clinical classification remain unimplemented.')
    registry_path = RES / 'rule-registry-v2/acmg-rule-registry-pvs1-hnf1a-frameshift-2026-09-10.json'
    write(registry_path, registry)
    profile['registryBinding'].update(registryId=registry['registryId'],
        registryClasspathResource='/acmg/rule-registry-v2/'+registry_path.name,
        registrySha256=hashlib.sha256(registry_path.read_bytes()).hexdigest())
    write(RES / 'pvs1-hnf1a-business-profile-v2.json', profile)
    joint = read(RES / 'acmg-business-execution-profile-joint-v1.json')
    joint.update(manifestVersion='1.3.0', profileId='acmg-explicit-business-joint-v2',
                 predecessorProfileId='acmg-explicit-business-joint-v1')
    pvs1_slice = next(row for row in joint['slices'] if row['criterionIds'] == ['ACMG_AMP_2015:PVS1'])
    pvs1_slice.update(profileId=profile['profileId'], profileVersion=profile['profileVersion'],
                      profileCanonicalSha256=canonical(profile), registryBinding=profile['registryBinding'],
                      scope=profile['policy']['policyId'])
    write(RES / 'acmg-business-execution-profile-joint-v2.json', joint)
    print(json.dumps(dict(profileCanonicalSha256=canonical(profile), registrySha256=profile['registryBinding']['registrySha256'],
                          jointFileSha256=hashlib.sha256((RES/'acmg-business-execution-profile-joint-v2.json').read_bytes()).hexdigest())))


if __name__ == '__main__':
    build()
