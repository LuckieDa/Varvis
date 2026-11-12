"""Carry one pinned source audit per extraction through the offline intake.

Review votes are proposals. Replay checks source, request, original response and
review receipt before deterministic qualification; this module never calls API.
"""
from pathlib import Path
from model_literature_contract import canonical, digest, parse
from model_transport import bounded
from bundle_model_contract import require
from run_core_model_review import replay


def pack_reviews(library, folders, artifacts):
    expected = {digest(canonical(a)): a for a in artifacts}
    result = []
    for folder in folders:
        folder = Path(folder)
        replay(library, folder)
        original = bounded(folder / 'original-extraction.json', 1048576)
        pin = digest(original)
        require(pin in expected and original == canonical(expected[pin]), 'REVIEW_ORIGINAL_MISMATCH')
        result.append({'originalArtifactSha256': pin,
                       'artifact': parse(bounded(folder / 'artifact.json', 8 * 1048576)),
                       'request': parse(bounded(folder / 'request.json', 1048576))})
    require(len(result) == len(expected) and {r['originalArtifactSha256'] for r in result} == set(expected),
            'REVIEW_COVERAGE_REQUIRED')
    return result


def replay_reviews(library, root, intake):
    artifacts = {digest(canonical(a)): a for a in intake['artifacts']}
    rows = intake['sourceReviews']
    require(isinstance(rows, list) and len(rows) == len(artifacts), 'REVIEW_COVERAGE_REQUIRED')
    seen, reviews, receipts = set(), [], []
    for i, row in enumerate(rows):
        require(set(row) == {'originalArtifactSha256', 'artifact', 'request'}, 'REVIEW_ENTRY_SCHEMA')
        pin = row['originalArtifactSha256']
        require(pin in artifacts and pin not in seen, 'REVIEW_ORIGINAL_MISMATCH')
        seen.add(pin)
        folder = Path(root) / ('source-review-' + str(i)); folder.mkdir()
        for name, value in [('original-extraction', artifacts[pin]), ('artifact', row['artifact']), ('request', row['request'])]:
            (folder / (name + '.json')).write_bytes(canonical(value))
        checked = replay(library, folder)
        reviews.extend(checked['reviews'])
        receipts.append(row['artifact']['receipt'])
    return reviews, receipts
