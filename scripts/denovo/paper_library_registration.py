"""Register immutable public sources and retrieval-only PS2/PM6 indexes.

Two indexes are not a database transaction. A write-ahead journal preserves both
before/after images. Repeating the same bundle completes interrupted registration.
"""
import json
from pathlib import Path
import re
import uuid

from prepare_literature import sha
from select_public_papers import bounded, unique


def require(value, reason):
    if not value:
        raise ValueError('PAPER_LIBRARY_' + reason)


def encoded(value, maximum=20 * 1024 * 1024):
    # A shared spreadsheet string can expand many times during JSON encoding.
    chunks, total = [], 1
    for chunk in json.JSONEncoder(ensure_ascii=False, indent=2).iterencode(value):
        raw = chunk.encode('utf-8')
        total += len(raw)
        require(total <= maximum, 'JSON_SIZE_LIMIT')
        chunks.append(raw)
    return b''.join(chunks) + b'\n'


def inside(root, relative):
    path = (root / relative).resolve()
    require(path.is_relative_to(root) and path != root, 'PATH_OUTSIDE_LIBRARY')
    return path


def immutable(path, raw):
    if path.exists():
        require(bounded(path, len(raw)) == raw, 'IMMUTABLE_CONFLICT')
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        # Atomic publication prevents interrupted copies looking like valid sources.
        temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
        with temporary.open('xb') as stream:
            stream.write(raw)
        temporary.replace(path)


def register(library, pmcid, files, bundle, metadata, criteria=('PS2', 'PM6')):
    require(isinstance(criteria, tuple) and bool(criteria) and len(set(criteria)) == len(criteria)
            and set(criteria) <= {'PS2', 'PM6', 'PM3', 'PP1', 'PVS1', 'PP2'}, 'CRITERIA_INVALID')
    root = Path(library).resolve(strict=True)
    require(re.fullmatch(r'PMC[1-9][0-9]*', pmcid), 'ID_INVALID')
    lock = inside(root, '_provenance/acquisition.lock')
    lock.parent.mkdir(parents=True, exist_ok=True)
    try:
        with lock.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps({'operation': 'PUBLIC_PAPER_RETRIEVAL_ONLY', 'criteria':list(criteria), 'id': uuid.uuid4().hex}))
    except FileExistsError as error:
        raise ValueError('PAPER_LIBRARY_BUSY_OR_INTERRUPTED') from error
    try:
        paper_root = inside(root, 'papers/' + pmcid)
        reader = bundle.get('publisherReader')
        suffix = '.xml' if reader is None else '.publisher.html'
        source_name = pmcid + suffix
        source = files[source_name]
        pin = sha(source)
        require(bundle['main']['sha256'] == pin and metadata['sha256'] == pin, 'SOURCE_PIN_MISMATCH')
        if reader is not None:
            from pm3_publisher_reader import prepare
            require(criteria == ('PM3',) and bundle['main']['path'] == source_name
                    and bundle['main']['url'] == metadata['acquisitionUrl']
                    and not bundle['attachments'] and not bundle.get('package')
                    and not bundle.get('authorManuscript'), 'PUBLISHER_REVIEW_SCOPE_INVALID')
            def checked_file(ref, limit):
                raw = files[ref['path']]
                require(0 < len(raw) <= limit and sha(raw) == ref['sha256'], 'PUBLISHER_PROOF_PIN_MISMATCH')
                return raw
            prepare(source, pmcid, bundle['main'], reader, checked_file)
        existing = paper_root / source_name
        if existing.exists() and bounded(existing, 2097152) != source:
            # Preserve the new version for review, but never silently switch active inputs.
            immutable(inside(root, f'papers/{pmcid}/versions/{pin}{suffix}'), source)
            return {'status': 'NEW_VERSION_PENDING', 'sha256': pin, 'businessPublishable': False}
        bundle_raw = encoded(bundle)
        bundle_pin = sha(bundle_raw)
        bundle_name = 'source-bundles/' + bundle_pin + '.json'
        additions = {**files, bundle_name: bundle_raw}
        if not (paper_root / 'metadata.json').exists():
            additions['metadata.json'] = encoded(metadata)
        paths = []
        for name, raw in additions.items():
            require(isinstance(raw, bytes), 'BYTES_REQUIRED')
            path = inside(root, 'papers/' + pmcid + '/' + name)
            require(path.is_relative_to(paper_root), 'PATH_OUTSIDE_PAPER')
            if path.exists():
                require(bounded(path, len(raw)) == raw, 'IMMUTABLE_CONFLICT')
            paths.append((path, raw))
        changes = []
        for criterion in criteria:
            index_path = inside(root, 'evidence/' + criterion + '/index.json')
            before = bounded(index_path, 1048576) if index_path.exists() else None
            index = json.loads(before, object_pairs_hook=unique) if before else {
                'schemaVersion': 'public-paper-evidence-index-v1', 'criterion': criterion,
                'status': 'CANDIDATES_REGISTERED', 'classificationMeaning': 'RETRIEVAL_HINT_NOT_APPROVAL',
                'documents': []}
            require(index.get('schemaVersion') == 'public-paper-evidence-index-v1' and
                    index.get('criterion') == criterion and isinstance(index.get('documents'), list), 'INDEX_INVALID')
            entries = [entry for entry in index['documents'] if entry.get('pmcid') == pmcid]
            require(len(entries) <= 1, 'INDEX_DUPLICATE')
            if entries:
                entry = entries[0]
                require(entry.get('sha256') == pin and entry.get('sourceClass') == 'PUBLIC_PAPER'
                        and (index_path.parent / entry['path']).resolve() == existing, 'INDEX_SOURCE_CONFLICT')
            else:
                entry = {'sourceClass': 'PUBLIC_PAPER', 'pmcid': pmcid, 'sha256': pin,
                         'path': '../../papers/' + pmcid + '/' + source_name,
                         'acquisitionUrl': metadata['acquisitionUrl']}
                index['documents'].append(entry)
            reference = {'path': '../../papers/' + pmcid + '/' + bundle_name, 'sha256': bundle_pin,
                         'qualification': 'ACQUIRED_NOT_QUALIFIED'}
            bundles = entry.setdefault('acquisitionBundles', [])
            require(isinstance(bundles, list), 'INDEX_INVALID')
            if reference not in bundles:
                require(len(bundles) < 100, 'BUNDLE_HISTORY_LIMIT')
                bundles.append(reference)
            after = encoded(index)
            require(len(after) <= 1048576, 'INDEX_SIZE_LIMIT')
            changes.append((index_path, before, after))
        journal = inside(root, '_provenance/acquisition-' + uuid.uuid4().hex + '.json')
        record = {'schemaVersion': 'public-paper-acquisition-journal-v1', 'pmcid': pmcid,
                  'bundleSha256': bundle_pin, 'businessPublishable': False,
                  'files': [{'path': str(p.relative_to(root)).replace('\\', '/'), 'sha256': sha(raw), 'bytes': len(raw)}
                            for p, raw in paths],
                  'indexes': [{'path': str(p.relative_to(root)).replace('\\', '/'),
                               'beforeSha256': sha(before) if before else None, 'afterSha256': sha(after),
                               'before': json.loads(before) if before else None, 'after': json.loads(after)}
                              for p, before, after in changes]}
        immutable(journal, encoded(record))
        for path, raw in paths:
            immutable(path, raw)
        for path, before, after in changes:
            # Reject concurrent edits instead of replacing another writer's changes.
            require((bounded(path, 1048576) if path.exists() else None) == before, 'INDEX_CHANGED_DURING_WRITE')
            if before != after:
                path.parent.mkdir(parents=True, exist_ok=True)
                temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
                temporary.write_bytes(after)
                temporary.replace(path)
        immutable(journal.with_suffix('.complete.json'), encoded({'status': 'REGISTERED', 'journalSha256': sha(encoded(record))}))
        return {'status': 'REGISTERED', 'bundleSha256': bundle_pin,
                'bundlePath': str((paper_root / bundle_name).relative_to(root)).replace('\\', '/'),
                'journalPath': str(journal.relative_to(root)).replace('\\', '/'), 'businessPublishable': False}
    finally:
        lock.unlink()
