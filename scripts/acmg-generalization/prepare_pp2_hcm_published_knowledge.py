"""Compile a closed published HCM PP2 source method, never variant phenotype clearance.

Reuses the existing pinned catalog/compiler. No population cutoff, causal review,
clinical approval, network access or runtime activation is created here.
"""
import argparse
import json
from pathlib import Path
import re
from prepare_published_criterion_knowledge import compile_plan, load_specifications, criterion_scopes, pointer
from assemble_materials import pinned, parse, require, write_new

TYPE = 'PP2_PUBLISHED_HCM_ENRICHMENT_WITH_PHENOTYPE_EXCLUSION_V1'
VERSION = '1.0.0'
CRITERION = 'ACMG_AMP_2015:PP2'
PREFIX = ('Application of this rule takes into consideration empirical data quantifying levels of rare missense variant '
          'enrichment in HCM referral cohorts compared to population-based cohorts (Walsh _et al._ 2019[<sup>12</sup>]'
          '(#pmid_30696458)) rather than the missense constraint score in gnomAD. On the basis of data from Walsh '
          '_et al._ 2019[<sup>12</sup>](#pmid_30696458), **PP2 is currently** **only applicable to** _**')
MIDDLE = '**_ **for HCM** (transcripts '
SUFFIX = (')_._ Data from HCM case cohorts was used to derive these cluster regions. Therefore, this rule should NOT be '
          'applied when additional evidence for the variant supports that the variant causes a phenotype other than HCM '
          '(e.g., variant seen in multiple DCM cases). Enrichment was not observed for DCM in any genes.')
TEXT = re.compile(re.escape(PREFIX) + r'([A-Za-z0-9_.-]{1,100})' + re.escape(MIDDLE) + r'(ENST[0-9]+)'
                  + re.escape(' and NM\\_') + r'([0-9]+\.[1-9][0-9]*)' + re.escape(SUFFIX))
COMMENTS = re.compile(re.escape('Application of this rule takes into consideration empirical data quantifying levels of '
    'rare missense variant enrichment in HCM referral cohorts compared to population-based cohorts (Walsh et al. 2019 '
    'PMID:30696458) rather than the missense constraint score in gnomAD. For ') + r'([A-Za-z0-9_.-]{1,100})'
    + re.escape(', there is evidence for regional enrichment of rare missense variants (see PM1 specifications).'))
INACTIVE = {'Not applicable', 'Not Applicable', 'Not Applicable for this VCEP'}


def normalized(value):
    return ' '.join(value.split()) if isinstance(value, str) else ''


def blank(value):
    return value is None or isinstance(value, str) and not value.strip()


def entirely_inactive(clause):
    strengths = clause.get('strengthDescriptor')
    return clause.get('applicability') in INACTIVE and isinstance(strengths, list) and bool(strengths) and all(
        isinstance(row, dict) and row.get('applicability') in INACTIVE for row in strengths)


def derive(original, cp, association):
    """Returns only normative source conditions; does not review any target variant."""
    clause = pointer(original, cp).get('entContent', {})
    require(clause.get('label') == 'PP2' and clause.get('applicability') == 'Applicable'
            and clause.get('baseStrength') == 'Pathogenic' and clause.get('defaultStrength') == 'Pathogenic Supporting'
            and blank(clause.get('instructionsToUse')), 'PP2_HCM_SOURCE_METHOD_UNSUPPORTED')
    all_clauses = original.get('ld', {}).get('CriteriaCode', [])
    require(sum(row.get('entContent', {}).get('label') == 'PP2' for row in all_clauses) == 1,
            'PP2_HCM_SOURCE_CLAUSE_AMBIGUOUS')
    require(blank(original.get('entContent', {}).get('notes')), 'PP2_HCM_GENERAL_NOTES_UNSUPPORTED')
    files = original.get('ld', {}).get('File')
    require(files is None or isinstance(files, list) and not files, 'PP2_HCM_ATTACHMENTS_UNSUPPORTED')
    strengths = clause.get('strengthDescriptor')
    require(isinstance(strengths, list), 'PP2_HCM_STRENGTH_UNSUPPORTED')
    active = [row for row in strengths if row.get('applicability') == 'Applicable']
    require(len(active) == 1 and all(row.get('applicability') == 'Applicable' or row.get('applicability') in INACTIVE
                                   for row in strengths), 'PP2_HCM_STRENGTH_AMBIGUOUS')
    active = active[0]
    require(active.get('strength') == 'Supporting' and active.get('status') == 'approved'
            and blank(active.get('instructionsToUse')), 'PP2_HCM_STRENGTH_UNSUPPORTED')
    match = TEXT.fullmatch(normalized(active.get('text')))
    require(match is not None and COMMENTS.fullmatch(normalized(clause.get('additionalComments'))) is not None,
            'PP2_HCM_SOURCE_CONDITIONS_UNSUPPORTED')
    symbol, ensembl, refseq = match.groups(); refseq = 'NM_' + refseq
    gene = association['rawGeneAssociation']; scope = association['scope']; disease = pointer(original, association['scopePointer'])
    require(clause.get('gene') == [symbol] and gene.get('gene') == symbol and gene.get('preferredTranscript') == refseq,
            'PP2_HCM_SOURCE_GENE_OR_TRANSCRIPT_RESTRICTION')
    require(normalized(disease.get('preferredTitle')).lower() == 'hypertrophic cardiomyopathy'
            and disease.get('preferredModeOfInheritance') == 'Autosomal dominant inheritance'
            and scope.get('moi') == 'AUTOSOMAL_DOMINANT', 'PP2_HCM_SOURCE_DISEASE_RESTRICTION')
    rule_pointer = association['scopePointer'].split('/entContent/genes/')[0]
    require(blank(pointer(original, rule_pointer).get('entContent', {}).get('generalComments')),
            'PP2_HCM_GENERAL_COMMENTS_UNSUPPORTED')
    for wrapper in all_clauses:
        c = wrapper.get('entContent', {})
        if c.get('label') == 'PP2' or entirely_inactive(c):
            continue
        bodies = [c.get('instructionsToUse'), c.get('additionalComments')]
        for row in c.get('strengthDescriptor', []):
            if row.get('applicability') not in INACTIVE:
                bodies += [row.get('text'), row.get('instructionsToUse')]
        require(not any('PP2' in normalized(text).upper() for text in bodies),
                'PP2_HCM_CROSS_RULE_CONDITION_UNSUPPORTED')
    return {'type': TYPE, 'version': VERSION, 'appliedStrength': 'SUPPORTING', 'germlineOnly': True,
        'requiresQualifiedVariantOtherPhenotypeReview': True,
        'sourceScope': {'geneSymbol': symbol, 'hgncId': scope['hgncId'], 'mondoId': scope['mondoId'],
                        'moi': scope['moi'], 'preferredTranscript': refseq, 'unversionedSourceEnsembl': ensembl},
        'sourceTextBindings': {'supportingText': active['text'], 'comments': clause['additionalComments']}}


def prepare(config):
    source = config['catalog']
    specs, _ = load_specifications(parse(pinned(source['file'], source['sha256'], 32_000_000)), Path(config['captureDirectory']))
    selected = config.get('specificationKeys')
    require(selected is None or isinstance(selected, list) and bool(selected)
            and all(isinstance(key, str) and key in {s['specificationKey'] for s in specs} for key in selected)
            and len(set(selected)) == len(selected),
            'PP2_HCM_SELECTED_SPECIFICATIONS_INVALID')
    plan = {key: config[key] for key in ('catalog', 'captureDirectory', 'policyId', 'policyVersion')}
    plan.update(schemaVersion='published-criterion-interpretation-plan-v1',
                adoptionMode='PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS', clinicalValidationCompleted=False, records=[])
    audit = {'schemaVersion': 'pp2-hcm-published-source-audit-v1', 'clinicalValidationCompleted': False,
             'variantPhenotypeClearanceCreated': False, 'populationThresholdCreated': False, 'clauses': []}
    for spec in specs:
        if selected is not None and spec['specificationKey'] not in selected:
            continue
        original = spec['original']
        for index, wrapper in enumerate(original.get('ld', {}).get('CriteriaCode', [])):
            clause = wrapper.get('entContent', {})
            if clause.get('label') != 'PP2' or not any('HCM referral cohorts' in normalized(body)
                    for body in [clause.get('additionalComments')] + [r.get('text') for r in clause.get('strengthDescriptor', [])]):
                continue
            cp = f'/ld/CriteriaCode/{index}'
            entry = {'specificationKey': spec['specificationKey'], 'clausePointer': cp, 'scopes': []}; audit['clauses'].append(entry)
            for association in criterion_scopes(original, CRITERION):
                if association['geneSymbol'] not in (clause.get('gene') or []):
                    continue
                status, reason, interpretation = 'EXECUTABLE', 'PP2_HCM_PUBLISHED_METHOD_BOUND', None
                try:
                    require(association['scopeStatus'] == 'EXACT', 'PP2_HCM_EXACT_SCOPE_REQUIRED')
                    interpretation = derive(original, cp, association)
                except (ValueError, KeyError, TypeError, AttributeError, IndexError) as invalid:
                    status = 'UNSUPPORTED_CONDITIONS'; reason = str(invalid)
                    if not re.fullmatch(r'PP2_HCM_[A-Z0-9_]+', reason): reason = 'PP2_HCM_SOURCE_INVALID'
                tx = association['rawGeneAssociation'].get('preferredTranscript')
                entry['scopes'].append({'scopePointer': association['scopePointer'], 'status': status, 'reasonCode': reason})
                if association['scopeStatus'] != 'EXACT' or not isinstance(tx, str) or not re.fullmatch(r'(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*', tx):
                    continue
                plan['records'].append({'recordId': f"PP2-HCM:{spec['specificationKey']}:{index}:{len(plan['records'])}",
                    'criterionId': CRITERION, 'specificationKey': spec['specificationKey'], 'clausePointer': cp,
                    'scopePointer': association['scopePointer'], 'transcript': tx, 'status': status, 'reasonCode': reason,
                    'interpretation': interpretation or {'type': 'PP2_HCM_RETAINED_UNEXECUTABLE_SOURCE_V1', 'version': VERSION}})
    return compile_plan(plan), audit, plan


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True); parser.add_argument('--config-sha256', required=True)
    parser.add_argument('--output-directory', required=True); args = parser.parse_args()
    output = Path(args.output_directory); output.mkdir(parents=True, exist_ok=False)
    knowledge, audit, plan = prepare(parse(pinned(args.config, args.config_sha256, 1_000_000)))
    for name, document in [('knowledge', knowledge), ('audit', audit), ('plan', plan)]: write_new(output / (name + '.json'), document)
    print(json.dumps({'records': len(knowledge['records']), 'executable': sum(r['status'] == 'EXECUTABLE' for r in knowledge['records']),
                      'variantPhenotypeClearanceCreated': False, 'populationThresholdCreated': False}))


if __name__ == '__main__':
    main()
