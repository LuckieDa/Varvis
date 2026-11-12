"""Explicit reviewed replay profile; never substitute it into a new receipt.

The 2074378 profile predates structural ownership checks. It is accepted only
when the complete original request reconstructs byte-for-byte and the response
is reviewed again. Unknown implementations still fail closed.
"""
PRE_STRUCTURE = {'bundle_model_contract.py': '988dcbcbc22491b9b766cf13d34c6423fa6a0b7ce6813107271b582b2922410c', 'bundle_model_transport.py': '3279ed4d036ef17cd366981b4daeaef069cccfceaa057365a5b05362bc4dd322', 'extract_literature_cases.py': '1f2437f373e57e376241bd3e1748bbdc0666966f339710bd1b116d5720681e6b', 'literature_allele_identity.py': '2b336d235b192ed4f5d93669061f8b8b0486e8f2ee826399d3c0575cbda01e2c', 'literature_attachments.py': '82c0bb96d785730633ba85e4fcace1769c564e4284f64d36d21552d2831028c2', 'literature_work_units.py': 'aa91ef1a204fb97551b8760b69f52c3f20bd6d74b826bc360d96ab85034d6aa8', 'model_literature_contract.py': 'b97e56f2c51b70b7e9df6507fd2295023d01ccb808618335d5fb1b6f451719a0', 'model_replay.py': 'b5f95619b5199d4b5543840dc092e451969e623cf6bad34ead9bcedc95d26846', 'model_transport.py': 'ba538968c5ef36fd4aa04c144f11e652e8661871bfbdf5078ccb01b262e0480d', 'paper_library_registration.py': '808467c84b714a514b06ee792f8538aa348ecbf11934474c6f3426625487145f', 'prepare_literature.py': '7bf7bd887a8ca033eb22768e46949921995eb4b4c36a2a95f0d2a29ea3972d46', 'prepare_literature_prompt.py': 'a05aff96ccf368201e902c716c7bafe1e06006ae6c664076378e6bab3cf19845', 'qualify_literature_cases.py': 'c9114267aca2cae158500e46247a8a069e201fab20d3679b42251eb7331ba5b8', 'run_bundle_model.py': 'ae4d35d90af514dbb2f371770b3ae1aaf74c231125c7ee71c6cfd6decc9563a4', 'run_model_extraction.py': '59856693d5f2ac86839e75cedf1de1156807c7c7d415f2af9591c80381690f4d'}


def reviewed_profiles():
    import json
    from pathlib import Path
    saved = json.loads(Path(__file__).with_name('bundle_replay_profiles.json').read_text(encoding='utf-8'))
    if saved.get('schemaVersion') != 'denovo-reviewed-replay-profiles-v1':
        raise ValueError('DENOVO_BUNDLE_REPLAY_PROFILE_INVALID')
    return [p['implementation'] for p in saved['profiles']] + [PRE_STRUCTURE]


def compatible(saved, current):
    return saved == current or saved in reviewed_profiles()
