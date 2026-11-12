"""Compile source-derived declarations for the original missense bridge, without clinical approval.

The selected policy must separately enable the source-preservation and computational RNA routes.
This compiler never changes an original expert packet, invents criteria, or grants final material approval.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re

import prepare_reference_evidence as source_parser

MODE = 'PRESERVE_SOURCE_VERIFIED_EXPERT_CLASSIFICATION'
RNA_MODE = 'PREDICTION_AND_REGISTERED_CONCERNS'
COVERAGE = 'PINNED_AVAILABLE_REPORTS_NOT_EXHAUSTIVE'
RNA_WORDS = re.compile(r'\b(?:rna|mrna|splice|splicing|exon\s+skipping|intron\s+retention)\b', re.I)
# Exact whitespace-normalized criterion wording, not a variant-specific mechanism report.
# ACMG 2015 PP3 definition, also reproduced in PMC10357475 Table 1. No fuzzy removal.
DEFINITION_HASHES = {'d0f5a347cea2d96a2102191d2f7c15d2c7ba92a7f2663432edba2f6f688cb6ab': 'ACMG_2015_PP3_DEFINITION'}


def require(value, reason):
    if not value:
        raise ValueError(reason)


def canonical(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def source_screen(records):
    """Conservative triage of retained original assertion comments; a mention blocks, never proves normal RNA."""
    concerns, inventory = [], []
    for record in records:
        require(record.get('sourceIds') and isinstance(record.get('assertions'), list), 'SOURCE_SCREEN_INPUT_REQUIRED')
        hits, definitions = [], []
        for assertion in record['assertions']:
            require(isinstance(assertion.get('comments'), list), 'SOURCE_COMMENTS_REQUIRED')
            for comment in assertion['comments']:
                require(isinstance(comment.get('text'), str) and comment.get('xpath'), 'SOURCE_COMMENT_LOCATOR_REQUIRED')
                relevant = []
                for segment in comment['text'].split(';'):
                    digest = hashlib.sha256(' '.join(segment.split()).casefold().encode()).hexdigest()
                    if digest in DEFINITION_HASHES:
                        definitions.append(dict(scv=assertion.get('scv'), xpath=comment['xpath'],
                                                definition=DEFINITION_HASHES[digest], segmentSha256=digest))
                    else:
                        relevant.append(segment)
                if RNA_WORDS.search(';'.join(relevant)):
                    hits.append(dict(scv=assertion.get('scv'), xpath=comment['xpath'], text=comment['text']))
        inventory.append(dict(allele=record['allele'], sourceIds=record['sourceIds'], assertions=len(record['assertions']),
                              matches=hits, conclusion='REQUIRES_REVIEW' if hits else 'NO_EXPLICIT_MENTION_IN_RETAINED_COMMENTS',
                              criterionDefinitionMentions=definitions, normalRnaClaimed=False))
        if hits:
            concerns.append(dict(allele=record['allele'], sourceIds=record['sourceIds'],
                                 reason='SOURCE_RNA_MENTION_REQUIRES_INTERPRETATION'))
    return concerns, inventory


def compile_review(draft, rna, report, packet, profile, pins, screen_records):
    require(profile.get('mode') == MODE and profile.get('rnaMode') == RNA_MODE, 'EXPLICIT_SOURCE_POLICY_PROFILE_REQUIRED')
    require(packet.get('reviewStatus') == 'UNREVIEWED' and packet.get('scientificAdoption') is False, 'ORIGINAL_UNREVIEWED_PACKET_REQUIRED')
    require(profile.get('sourceIds') and profile.get('background'), 'PROFILE_SOURCES_AND_SCOPE_REQUIRED')
    require(len(draft['references']) <= 256 and len(packet['references']) <= 256, 'REFERENCE_LIMIT')
    concerns, inventory = source_screen(screen_records)
    selections, selected = [], 0
    for ref in draft['references']:
        for assertion in ref['assertions']:
            original = assertion['sourceAssertion']
            selection = dict(referenceDraftId=ref['referenceDraftId'], assertionDraftId=assertion['assertionDraftId'])
            rows = [r for r in packet['references'] if r.get('allele') == ref['allele'] and r.get('variationId') == ref['variationId']
                    and r.get('originalAssertion', {}).get('scv') == original.get('scv')]
            require(len(rows) <= 1, 'AMBIGUOUS_EXPERT_ROW')
            classification = {'pathogenic': 'PATHOGENIC', 'likely pathogenic': 'LIKELY_PATHOGENIC'}.get(original.get('classification', '').lower())
            if not rows or not classification or original.get('reviewStatus') not in ('reviewed by expert panel', 'practice guideline'):
                selection.update(decision='DO_NOT_ADOPT', reason='NO_EXACT_PATHOGENIC_EXPERT_SOURCE')
            else:
                row = rows[0]
                require(row['originalAssertion']['recordStatus'] == 'current', 'EXPERT_SOURCE_NOT_CURRENT')
                require(row['disease']['id'] == profile['background']['mondoId'], 'SOURCE_DISEASE_PROFILE_MISMATCH')
                review = dict(reviewId='source-qualification-' + canonical(row), classification=classification,
                              assertionStatus='ACTIVE', conflictState='SOURCE_RETAINED_RUNTIME_CONFLICT_CHECK_REQUIRED',
                              sourceIds=sorted(set(profile['sourceIds'] + row['sourceIds'])),
                              expertEvidenceReferenceId=row['referenceId'], expertClassificationAdoption=MODE)
                for field in ('hgncId', 'geneSymbol', 'mondoId', 'moi'):
                    review[field] = profile['background'][field]
                # A fixed method registry is policy-level configuration, not a new per-target judgment.
                bindings = []
                for binding in profile.get('originalMethods', []):
                    for group in row.get('specification', {}).get('originalClinVarMethod', []):
                        children = group.get('children', [])
                        labels = [c.get('text') for c in children if c.get('tag') == 'Attribute' and c.get('attributes', {}).get('Type') == 'AssertionMethod']
                        urls = [u.get('text') for c in children if c.get('tag') == 'Citation' for u in c.get('children', []) if u.get('tag') == 'URL']
                        if labels == [binding['assertionMethodLabel']] and urls == [binding['url']]:
                            bindings.append(binding)
                require(len(bindings) <= 1, 'AMBIGUOUS_ORIGINAL_METHOD_BINDING')
                if bindings:
                    review['originalSpecificationBinding'] = copy.deepcopy(bindings[0])
                selection.update(decision='ADOPT', review=review)
                selected += 1
            selections.append(selection)
    require(selected > 0, 'NO_EXACT_EXPERT_REFERENCE')
    require(len(selections) <= 256, 'ASSERTION_LIMIT')
    review = dict(schemaVersion='missense-reference-review-declaration-v1', dataClass=profile['dataClass'], purpose=profile['purpose'],
                  background=copy.deepcopy(profile['background']), references=selections,
                  draftBinding=dict(draftId=draft['draftId'], **{k: pins[k] for k in ('referenceDraft', 'rnaDraft', 'draftReport')}),
                  referenceAudit=dict(status='SOURCE_VERIFIED', reviewId='source-qualification-' + canonical(packet), sourceIds=profile['sourceIds']),
                  rnaReview=dict(mode=RNA_MODE, status='SOURCE_SCREENED_PREDICTION_REQUIRED', sourceCoverage=COVERAGE,
                                 rnaEffect='UNKNOWN', normalRnaClaimed=False, reviewId='source-screen-' + canonical(inventory),
                                 sourceIds=sorted(set(profile['sourceIds'] + [s for record in screen_records for s in record['sourceIds']])),
                                 knownConcerns=concerns), rnaComparisons=[])
    return review, dict(status='COMPILED_FOR_ORIGINAL_BRIDGE_VALIDATION', selectedReferences=selected,
                        sourceScreen=inventory, sourceCoverage=COVERAGE, normalRnaClaimed=False,
                        scientificAdoption=False, finalApprovalCreated=False,
                        limitations=['NO_EXHAUSTIVE_LITERATURE_SEARCH', 'NATIVE_RAW_REQUIRED_FOR_BOTH_ALLELES',
                                     'ORIGINAL_BRIDGE_MUST_VALIDATE_ALL_SOURCE_FACTS_AND_POLICY'])


def prepare(manifest_path, pin, output):
    root, output = Path(manifest_path).resolve().parent, Path(output)
    require(not output.exists(), 'OUTPUT_EXISTS_REFUSE_OVERWRITE')
    total, inputs = 0, []
    def read(desc):
        nonlocal total
        path = root / desc['path']
        with path.open('rb') as stream:
            raw = stream.read(source_parser.MAX_FILE + 1)
        total += len(raw)
        require(len(raw) <= source_parser.MAX_FILE and total <= source_parser.MAX_TOTAL, 'INPUT_SIZE_LIMIT')
        require(hashlib.sha256(raw).hexdigest() == desc['rawSha256'], 'RAW_SHA256_MISMATCH')
        inputs.append(dict(path=str(path), bytes=len(raw), rawSha256=desc['rawSha256']))
        return raw
    manifest = source_parser.parse_json(read(dict(path=str(Path(manifest_path).resolve()), rawSha256=pin)))
    require(manifest.get('schemaVersion') == 'missense-source-qualification-manifest-v1', 'MANIFEST_VERSION_INVALID')
    values, pins = {}, {}
    for role in ('referenceDraft', 'rnaDraft', 'draftReport', 'expertEvidence', 'profile'):
        raw = read(manifest[role]); values[role] = source_parser.parse_json(raw)
        pins[role] = dict(rawSha256=hashlib.sha256(raw).hexdigest(), canonicalSha256=canonical(values[role]))
    records = [dict(allele=r['allele'], sourceIds=r['sourceIds'], assertions=[r['originalAssertion']] + r.get('otherAssertions', []))
               for r in values['expertEvidence']['references'] if r.get('originalAssertion')]
    require(len(manifest.get('additionalClinVarSources', [])) <= 50, 'SOURCE_SCREEN_LIMIT')
    for desc in manifest.get('additionalClinVarSources', []):
        cv = source_parser.parse_vcv(read(desc), desc['variationId'], desc['allele'])
        records.append(dict(allele=desc['allele'], sourceIds=[desc['sourceId']], assertions=cv['assertions']))
    review, report = compile_review(values['referenceDraft'], values['rnaDraft'], values['draftReport'], values['expertEvidence'], values['profile'], pins, records)
    report['inputs'] = inputs
    output.mkdir()
    for name, value in (('source-qualified-review.json', review), ('qualification-report.json', report)):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('manifest', 'manifest-sha256', 'output'):
        parser.add_argument('--' + field, required=True)
    args = parser.parse_args()
    try:
        report = prepare(args.manifest, args.manifest_sha256, args.output)
        print(json.dumps({k: report[k] for k in ('status', 'selectedReferences', 'scientificAdoption', 'finalApprovalCreated')}))
        return 0
    except (ValueError, OSError, KeyError, TypeError, AttributeError, source_parser.ET.ParseError) as error:
        print(json.dumps(dict(status='BLOCKED', reason=str(error), scientificAdoption=False)))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
