"""Self-contained, bounded OFFLINE intake for existing bundle model artifacts.

Packages exact source files, not approved model conclusions. Each business read
reconstructs and reparses the original library, response, request and receipt.
No credentials, retrieval, model invocation or scientific scoring is supported.
"""
import argparse
import base64
import os
from pathlib import Path, PurePosixPath
import tempfile

from bundle_model_contract import load, pinned
from run_bundle_model import replay
from model_literature_contract import canonical, digest, parse
from model_transport import bounded
from qualify_bundle_candidates import qualify, VERSION

SCHEMA = 'denovo-bundle-candidate-intake-v1'
MAX_BYTES = 16 * 1024 * 1024
FILES = ('bundle_candidate_intake.py', 'qualify_bundle_candidates.py',
         'bundle_model_contract.py', 'bundle_model_transport.py', 'run_bundle_model.py',
         'prepare_literature_prompt.py', 'literature_attachments.py',
         'paper_library_registration.py', 'literature_work_units.py',
         'literature_case_structure.py', 'bundle_replay_compatibility.py', 'author_manuscript_source.py')
from core_model_review_contract import implementation as review_implementation
FILES = tuple(dict.fromkeys((*FILES, 'core_model_review_intake.py', *review_implementation())))


def require(value, code):
    if not value:
        raise ValueError('DENOVO_BUNDLE_INTAKE_' + code)


def pack(library, artifact_paths, review_folders=None):
    library = Path(library).resolve(strict=True)
    require(0 < len(artifact_paths) <= 64, 'ARTIFACT_LIMIT')
    artifacts = {}
    for path in artifact_paths:
        raw = bounded(path, 1048576)
        replay(library, path)
        artifacts[digest(raw)] = parse(raw)
    values = list(artifacts.values())
    identities = {(a['binding']['pmcid'], a['binding']['bundleSha256']) for a in values}
    require(len(identities) == 1, 'SINGLE_BUNDLE_REQUIRED')
    pmcid, pin = next(iter(identities))
    source = load(library, pmcid, pin)
    entries = parse(bounded(library / 'evidence/PS2/index.json', 1048576))['documents']
    entry = next(e for e in entries if e['pmcid'] == pmcid and e.get('sourceClass') == 'PUBLIC_PAPER')
    prefix = 'papers/' + pmcid + '/'
    bundle_name = 'source-bundles/' + pin + '.json'
    bundle_raw = pinned(library / prefix, bundle_name, pin, 2097152)
    bundle = parse(bundle_raw)
    files = {'evidence/PS2/index.json': canonical({'criterion': 'PS2', 'documents': [entry]}), prefix + bundle_name: bundle_raw}
    for item in [bundle['main'], *bundle['attachments']] + ([bundle['package']] if bundle.get('package') else []):
        files[prefix + item['path']] = pinned(library / prefix, item['path'], item['sha256'], MAX_BYTES)
    for item in bundle['attachments']:
        if item.get('officialSingleFile'):
            ref = item['officialSingleFile']
            receipt_raw = pinned(library / prefix, ref['path'], ref['sha256'], 65536)
            files[prefix + ref['path']] = receipt_raw
            receipt = parse(receipt_raw)
            for name, limit in [('article', 2097152), ('articleRecord', 65536), ('acquisitionRecord', 1048576)]:
                ref = receipt[name]
                files[prefix + ref['path']] = pinned(library / prefix, ref['path'], ref['sha256'], limit)
    if bundle.get('authorManuscript'):
        item = bundle['authorManuscript']
        files[prefix + item['rawPath']] = pinned(library / prefix, item['rawPath'], item['rawSha256'], MAX_BYTES)
    result = {'schemaVersion': SCHEMA, 'pmcid': pmcid, 'sourceSha256': source['main']['sourceSha256'],
              'bundleSha256': pin, 'artifacts': values,
              'files': {name: {'sha256': digest(body), 'base64': base64.b64encode(body).decode()} for name, body in sorted(files.items())},
              'clinicalAdoption': 'NONE', 'businessPublishable': False}
    if review_folders is not None:
        from core_model_review_intake import pack_reviews
        result['sourceReviews'] = pack_reviews(library, review_folders, values)
    require(len(canonical(result)) <= MAX_BYTES, 'SIZE_LIMIT')
    return result


def replay_intake(raw, pmcid, pin, intake, allele_sources=(), work_root=None, core_context=None):
    require(intake.get('schemaVersion') == SCHEMA and len(canonical(intake)) <= MAX_BYTES, 'SCHEMA_OR_SIZE')
    require(intake['pmcid'] == pmcid and intake['sourceSha256'] == pin == digest(raw), 'SOURCE_MISMATCH')
    require(0 < len(intake['files']) <= 520 and 0 < len(intake['artifacts']) <= 64, 'COUNT_LIMIT')
    # Work root is the task's cache directory, never the public source library.
    if os.name == 'nt' and work_root is not None:
        work_root = str(Path(work_root).resolve())
        if not work_root.startswith('\\\\?\\'):
            work_root = '\\\\?\\' + work_root
    with tempfile.TemporaryDirectory(prefix='bundle-intake-', dir=work_root) as directory:
        root = Path(directory); library = root / 'library'; library.mkdir()
        total = 0
        for name, value in intake['files'].items():
            path = PurePosixPath(name)
            require(not path.is_absolute() and all(p not in {'..', '.', ''} and ':' not in p and '\\' not in p for p in path.parts), 'PATH_INVALID')
            require(name == 'evidence/PS2/index.json' or name.startswith('papers/' + pmcid + '/'), 'PATH_INVALID')
            body = base64.b64decode(value['base64'], validate=True); total += len(body)
            require(total <= MAX_BYTES and digest(body) == value['sha256'], 'FILE_PIN_OR_BUDGET')
            if name == 'evidence/PS2/index.json':
                # v1 self-contained de novo packages originally stored only
                # documents. After verifying those exact original bytes, label
                # this temporary PS2 reconstruction, never the archived package
                # or an arbitrary live library. An explicit other criterion is
                # not legacy and remains rejected by the normal loader.
                index_document = parse(body)
                if set(index_document) == {'documents'}:
                    body = canonical({'criterion': 'PS2', **index_document})
            destination = library.joinpath(*path.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with destination.open('xb') as stream:
                stream.write(body)
        source = load(library, pmcid, intake['bundleSha256'])
        require(source['raw'] == raw, 'SOURCE_MISMATCH')
        reviews, receipts, responses, seen = [], [], [], set()
        chunks = set()
        for i, artifact in enumerate(intake['artifacts']):
            binding = artifact['binding']
            require(binding['pmcid'] == pmcid and binding['bundleSha256'] == intake['bundleSha256'], 'ARTIFACT_SOURCE_MISMATCH')
            artifact_pin = digest(canonical(artifact))
            if artifact_pin in seen:
                continue
            seen.add(artifact_pin)
            require(binding['chunkIndex'] not in chunks, 'CONFLICTING_TARGET_RESPONSES')
            chunks.add(binding['chunkIndex'])
            path = root / ('artifact-' + str(i) + '.json'); path.write_bytes(canonical(artifact))
            reviews.append(replay(library, path))
            receipts.append(artifact['receipt'])
            responses.append({'artifactSha256': artifact_pin, 'responseBase64': artifact['responseBase64'], 'review': reviews[-1]})
        review_receipts = []
        if 'sourceReviews' in intake:
            from core_model_review_intake import replay_reviews
            reviews, review_receipts = replay_reviews(library, root, intake)
        facts = qualify(source, reviews, allele_sources)
        if core_context is not None:
            from core_evaluation_intake import enrich
            facts = enrich(source, facts, reviews, intake, root / 'core-evaluation', core_context)
        kinds = {r['transportKind'] for r in [*receipts, *review_receipts]}
        facts['modelProvenance'] = {'artifactSha256': digest(canonical(intake)),
            'receipt': {'transportKind': 'REAL_PROVIDER' if kinds == {'REAL_PROVIDER'} else 'SYNTHETIC_TEST', 'receipts': receipts},
            'artifacts': responses, 'intakeVersion': VERSION, 'currentReplayModelCalls': 0,
            'originalExtractionModelCalls': len(receipts)}
        if review_receipts:
            facts['modelProvenance'].update(sourceReviewReceipts=review_receipts,
                originalSourceReviewModelCalls=len(review_receipts),
                sourceReviewStatus='REPLAYED_PROPOSALS_PROGRAM_QUALIFICATION_REQUIRED')
        return facts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--artifact', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    value = pack(args.library, args.artifact)
    with args.output.open('xb') as stream:
        stream.write(canonical(value))
    print(canonical({'status': 'OFFLINE_INTAKE_READY_NOT_APPROVED', 'sha256': digest(canonical(value)), 'modelCalls': 0, 'networkCalls': 0}).decode())


if __name__ == '__main__':
    main()
