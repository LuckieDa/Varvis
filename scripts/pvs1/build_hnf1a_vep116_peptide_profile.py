"""Create immutable implementation-patch identities; keep the scientific policy unchanged."""
import copy
import json

from build_hnf1a_frameshift_profile import RES, canonical, read, write


def build():
    previous = read(RES / 'pvs1-hnf1a-business-profile-v2.json')
    profile = copy.deepcopy(previous)
    profile.update(profileVersion='2.0.1', predecessorProfileId=previous['profileId'],
                   predecessorProfileVersion=previous['profileVersion'],
                   predecessorProfileSha256=canonical(previous))
    profile['implementation'] = {
        'evaluatorId': 'hnf1a-truncating-nmd-pvs1',
        'evaluatorVersion': '2.0.1',
        'revision': 'VEP116_ALLELE_LOCAL_PEPTIDE_COMPATIBILITY',
        'sourceCommit': 'db7394a02523a6e519cb0d89f84b2889bac12737',
        'sourceUrl': 'https://github.com/Ensembl/ensembl-variation/blob/'
                     'db7394a02523a6e519cb0d89f84b2889bac12737/'
                     'modules/Bio/EnsEMBL/Variation/TranscriptVariationAllele.pm',
        'localPeptide': 'TRANSLATE_ALL_COMPLETE_LOCAL_CODONS; APPEND_X_FOR_PARTIAL_UNLESS_PEPTIDE_IS_SINGLE_STOP; '
                        'EMPTY_IS_DASH; EQUAL_PEPTIDES_COLLAPSE',
        'scientificPolicyChanged': False,
    }
    write(RES / 'pvs1-hnf1a-business-profile-v3.json', profile)
    joint = read(RES / 'acmg-business-execution-profile-joint-v2.json')
    joint.update(manifestVersion='1.3.1', profileId='acmg-explicit-business-joint-v3',
                 predecessorProfileId='acmg-explicit-business-joint-v2')
    pvs1_slice = next(row for row in joint['slices'] if row['criterionIds'] == ['ACMG_AMP_2015:PVS1'])
    pvs1_slice.update(profileVersion=profile['profileVersion'], profileCanonicalSha256=canonical(profile))
    write(RES / 'acmg-business-execution-profile-joint-v3.json', joint)
    print(json.dumps({'profileCanonicalSha256': canonical(profile), 'jointCanonicalSha256': canonical(joint)}))


if __name__ == '__main__':
    build()
