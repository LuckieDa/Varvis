"""Source-bound A1 experimental reading, using the existing public-paper library.

This is an applicability/reading stage, not a functional-rescue classifier.
Assay keywords select passages for review; neither their presence nor their
absence establishes function, endogenous compensation, or a negative search.
"""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from bundle_model_contract import load, cite
from model_literature_contract import canonical, digest
from start_loss_experiment_facts import assess

VERSION = 'pvs1-start-loss-functional-reading-v1'
# These are search concepts, not qualification rules. Keep full context and
# negation so "no overexpression" cannot turn into an overexpression fact.
CONCEPTS = {
    'isoformIdentity': r'\b(?:isoforms?|transcripts?|splice variants?)\b',
    'constructExtent': r'\b(?:full[- ]length|fragments?|constructs?|domains?|truncated)\b',
    'experimentalSystem': r'\b(?:mice|mouse|human|neurons?|fibroblasts?|iPSCs?|cells?|in vitro|in vivo)\b',
    'dosageAndIntervention': r'\b(?:endogenous|overexpress\w*|transgen\w*|upregulat\w*|knockout|knock[- ]in|deleted|deletion|morpholino|ASO)\b',
    'functionalOutcome': r'\b(?:rescu\w*|compensat\w*|survival|behavio\w*|electrophysiolog\w*|enzym\w*|binding|activity|phenotypes?)\b',
    'controlsAndReplicates': r'\b(?:controls?|replicates?|isogenic|wild[- ]type|sample size|n\s*=)\b',
    'limitationsAndCounterevidence': r'\b(?:however|although|limitations?|not sufficient|unable|did not|does not|cannot|failed|partial\w*)\b',
}
REQUIREMENTS = {
    'isoformIdentity': 'Exact alternative isoform and version/sequence correspondence to the candidate transcript.',
    'constructExtent': 'Whether the experiment studied the full isoform or only a fragment/domain.',
    'experimentalSystem': 'Organism, tissue/cell system and relevance to the disease mechanism.',
    'dosageAndIntervention': 'Endogenous setting versus dose increase, therapeutic intervention or altered expression.',
    'functionalOutcome': 'Observed disease-relevant function; abundance or a single binding assay alone does not establish rescue.',
    'controlsAndReplicates': 'Appropriate controls, biological replication and interpretable measurements.',
    'limitationsAndCounterevidence': 'Restrictions, contradictory observations and unassessed endpoints.',
}


def require(condition, reason):
    if not condition:
        raise ValueError('PVS1_FUNCTIONAL_' + reason)


def read(source, gene, isoforms, maximum_units=32):
    require(bool(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}', gene)), 'GENE_INVALID')
    require(isinstance(isoforms, list) and 0 <= len(isoforms) <= 20 and
            all(isinstance(v, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_. -]{0,79}', v) for v in isoforms),
            'ISOFORMS_REQUIRED')
    require(len(set(isoforms)) == len(isoforms), 'DUPLICATE_ISOFORM')
    require(type(maximum_units) is int and 1 <= maximum_units <= 96, 'UNIT_LIMIT_INVALID')
    terms = [gene, *isoforms]
    subject = re.compile(r'(?<![\w])(?:' + '|'.join(re.escape(v) for v in terms) + r')(?![\w])', re.I)
    units = source['units']
    require(len({u['unitId'] for u in units}) == len(units), 'DUPLICATE_SOURCE_UNIT')
    # Do not quietly truncate long passages. Unread ones remain in the queue.
    relevant = []
    for unit in units:
        text = unit['text']
        matches = {name: list(re.finditer(pattern, text, re.I)) for name, pattern in CONCEPTS.items()}
        if not subject.search(text) or not any(matches.values()):
            continue
        # References and background can identify leads but are not original
        # experimental results. Record this rather than dropping them.
        sections = ' / '.join(unit.get('sections', []))
        context = 'CITED_OR_BACKGROUND' if unit['kind'] in {'REFERENCE', 'ABSTRACT'} or re.search(
            r'\b(?:introduction|background|references)\b', sections, re.I) else 'PRIMARY_OR_DISCUSSION_REQUIRES_REVIEW'
        priority = 0 if matches['functionalOutcome'] and matches['dosageAndIntervention'] else 1
        relevant.append((priority, unit, matches, context))
    relevant.sort(key=lambda row: row[0])  # stable: preserve source order within priority
    findings, pending = [], []
    for _, unit, matches, context in relevant:
        if len(findings) >= maximum_units or len(unit['text']) > 16000:
            pending.append({'unitId': unit['unitId'], 'sourceSha256': unit['sourceSha256'],
                            'reasonCode': 'READING_UNIT_BUDGET' if len(findings) >= maximum_units else 'SOURCE_UNIT_REQUIRES_PARTITION'})
            continue
        fields = []
        for name, occurrences in matches.items():
            if not occurrences:
                continue
            fields.append({'field': name, 'matchedTerms': sorted({m.group(0) for m in occurrences}),
                           'status': 'PASSAGE_LOCATED_NOT_FACT_QUALIFIED'})
        # One full source scope per passage, shared by all located fields.
        ref = cite(source, {'unitId': unit['unitId'], 'sourceSha256': unit['sourceSha256'], 'quote': unit['text']})
        require(ref['sourceSha256'] == unit['sourceSha256'], 'CITATION_SOURCE_MISMATCH')
        ref.pop('sourceContext', None)  # identical to the whole-unit quote
        findings.append({'unitId': unit['unitId'], 'sourceContext': context, 'fields': fields,
                         'citation': ref,
                         'subjectStatus': 'LITERAL_MENTION_NOT_EXACT_ISOFORM_BINDING',
                         'functionalRescueEstablished': False})
    comparison = []
    for name, requirement in REQUIREMENTS.items():
        located = [row['unitId'] for row in findings if any(f['field'] == name for f in row['fields'])]
        comparison.append({'field': name, 'required': requirement, 'availablePassages': located,
                           'gap': 'SOURCE_MEANING_AND_APPLICABILITY_NOT_YET_QUALIFIED' if located else 'NOT_LOCATED_IN_REVIEWED_UNITS',
                           'absenceEstablished': False})
    result = {'schemaVersion': VERSION, 'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
            'sourceSha256': source['main']['sourceSha256'], 'geneSymbol': gene, 'isoformSearchTerms': isoforms,
            'status': 'FUNCTIONAL_APPLICABILITY_REVIEW_REQUIRED', 'runtimeEligible': False,
            'functionalRescueEstablished': False, 'negativeFunctionalCoverageGranted': False,
            'findings': findings, 'requirementsComparison': comparison, 'pendingUnits': pending,
            'sourceUnitCount': len(units), 'matchingUnitCount': len(relevant), 'reviewedUnitCount': len(findings),
            'supplementCoverage': source['coverage'], 'attachments': source['attachments'],
            'searchMeaning': 'Literal term selection is not complete experimental or biological coverage.',
            'methodSha256': digest(Path(__file__).read_bytes()),
            'experimentalMethodSha256': digest(Path(__file__).with_name('start_loss_experiment_facts.py').read_bytes())}
    result['experimentalAssessment'] = assess(result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', required=True)
    parser.add_argument('--pmcid', required=True)
    parser.add_argument('--bundle-sha256', required=True)
    parser.add_argument('--gene', required=True)
    parser.add_argument('--isoform', action='append', required=True)
    parser.add_argument('--maximum-units', type=int, default=32)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    source = load(Path(args.library), args.pmcid, args.bundle_sha256, criterion='PVS1')
    result = read(source, args.gene, args.isoform, args.maximum_units)
    path = Path(args.output)
    # This is a research receipt, not a business result or a replacement source.
    with path.open('xb') as stream:
        stream.write(canonical(result))
    print(json.dumps({'status': result['status'], 'reviewedUnits': result['reviewedUnitCount'],
                      'pendingUnits': len(result['pendingUnits']), 'runtimeEligible': False}))


if __name__ == '__main__':
    main()
