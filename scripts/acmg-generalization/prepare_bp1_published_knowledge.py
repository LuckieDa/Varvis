"""Prepare BP1's explicit published all-missense Supporting grammar from a pinned batch.

No gene switch, LoF-tag inference, artificial expert review or network access.
Known non-executable clauses remain queryable; unresolved scopes stay in the audit.
The additional RAW <= 0.1 restriction is a project research exclusion only.
Truncating-rule originals retain complete pinned RAW bytes for source replay;
project-authored future declarations are not source selection or qualification.
"""
import argparse
import copy
import json
from pathlib import Path
import re
import hashlib
from prepare_published_criterion_knowledge import compile_plan, load_specifications, scopes, pointer
from assemble_materials import pinned, parse, write_new, child, require, content_hash
from bp1_region_policy import derive as derive_regions, load_adoptions

TYPE = 'PUBLISHED_ALL_MISSENSE_SUPPORTING_V1'
RESTRICTION = 'PROJECT_RESEARCH_CONSERVATIVE_SPLICE_EXCLUSION_V1'
TRUNCATING_TYPE = 'PUBLISHED_TRUNCATING_GOF_SUPPORTING_V1'
TRUNCATING_INSTRUCTION = 'Truncating, LOF variant in a gene for which primarily missense, GOF variants are known to cause disease.'
TRUNCATING_COMMENT = ('This rule has contraindications for use with RASopathies. Given the disease mechanism is gain-of-function for RASopathies, '
    'BP1 should be used for any truncating variant (nonsense, frameshift, affects canonical splice sites, initiation codon, entire gene or multi exon deletion) '
    'in genes without established LOF correlation to disease. See the supplemental material regarding dosage sensitivity information for each individual gene '
    'and potential association to disorders associated with LOF variants.')
GERMLINE_NOTES_SHA256 = '1a705778fd3fe0ba4b76dac758193b802e3b5d6faf82d95135dfab0bf8e13616'
MAX_LOF_RAW_SOURCES = 8
MAX_LOF_RAW_BYTES = 4_000_000


def truncating_interpretation():
    return {'type': TRUNCATING_TYPE, 'version': '1.0.0', 'appliedStrength': 'SUPPORTING',
            'germlineOnly': True, 'requiresNoEstablishedLofDiseaseCorrelation': True}


def truncating_source(original, clause):
    """Recognise a subtype; keep actual LoF and gene-disease qualification unresolved."""
    if (clause.get('label') != 'BP1' or clause.get('applicability') != 'Applicable'
            or clause.get('baseStrength') != 'Benign' or clause.get('defaultStrength') != 'Benign Supporting'
            or normalized(clause.get('instructionsToUse')) != TRUNCATING_INSTRUCTION
            or normalized(clause.get('additionalComments')) != TRUNCATING_COMMENT
            or hashlib.sha256(normalized(original.get('entContent', {}).get('notes')).encode()).hexdigest() != GERMLINE_NOTES_SHA256
            or original.get('ld', {}).get('File')):
        return False
    genes = clause.get('gene', [])
    if not isinstance(genes, list) or len(genes) != 1 or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', str(genes[0])):
        return False
    active = []
    for s in clause.get('strengthDescriptor', []):
        if s.get('applicability') == 'Applicable': active.append(s)
        elif s.get('applicability') not in ('Not applicable', 'Not Applicable', 'Not Applicable for this VCEP'): return False
    if (len(active) != 1 or active[0].get('strength') != 'Supporting' or active[0].get('status') != 'approved'
            or normalized(active[0].get('text')) != TRUNCATING_COMMENT or normalized(active[0].get('instructionsToUse'))):
        return False
    if any(normalized(r.get('entContent', {}).get('generalComments')) for r in original.get('ld', {}).get('RuleSet', [])):
        return False
    for w in original.get('ld', {}).get('CriteriaCode', []):
        other = w.get('entContent', {})
        if other.get('label') == 'BP1': continue
        if any('BP1' in str(other.get(k) or '') for k in ('instructionsToUse', 'additionalComments')): return False
        if any('BP1' in str(s.get(k) or '') for s in other.get('strengthDescriptor', []) for k in ('text', 'instructionsToUse')): return False
    return True


def normalized(value):
    return ' '.join((value or '').split())


def disposition(original, clause):
    if clause.get('applicability') in ('Not applicable', 'Not Applicable', 'Not Applicable for this VCEP'):
        if any(s.get('applicability') == 'Applicable' for s in clause.get('strengthDescriptor', [])):
            return 'BP1_SOURCE_APPLICABILITY_CONFLICT'
        return 'BP1_SOURCE_NOT_APPLICABLE'
    if clause.get('applicability') != 'Applicable':
        return 'BP1_SOURCE_APPLICABILITY_UNRESOLVED'
    if truncating_source(original, clause):
        return 'BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED'
    if any(re.match(r'(?i)^(?:do not use\b|not applicable\b)', normalized(clause.get(field)))
           for field in ('instructionsToUse', 'additionalComments')):
        return 'BP1_SOURCE_APPLICABILITY_CONFLICT'
    genes = clause.get('gene', [])
    if len(genes) != 1 or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', genes[0]):
        return 'BP1_PUBLISHED_SOURCE_SEMANTICS_UNSUPPORTED'
    gene = genes[0]
    text = (f'Based on published and unpublished functional studies, {gene} has a low rate of missense variants that are non-functional in relevant assays. '
            'True missense pathogenic variants are not yet confirmed or refuted but are thought to be exceedingly rare. '
            f'Given the very low likelihood that missense variants are pathogenic, this rule applies to all missense variants in {gene}.')
    if (normalized(clause.get('instructionsToUse')) != text or normalized(clause.get('additionalComments'))
            or clause.get('baseStrength') != 'Benign' or clause.get('defaultStrength') != 'Benign Supporting'):
        return 'BP1_PUBLISHED_SOURCE_CONDITIONS_UNSUPPORTED'
    if original.get('ld', {}).get('File'):
        return 'BP1_PUBLISHED_ATTACHMENTS_UNSUPPORTED'
    active = []
    for strength in clause.get('strengthDescriptor', []):
        if strength.get('applicability') == 'Applicable':
            active.append(strength)
        elif strength.get('applicability') not in ('Not Applicable', 'Not Applicable for this VCEP'):
            return 'BP1_PUBLISHED_STRENGTH_UNSUPPORTED'
    if (len(active) != 1 or active[0].get('strength') != 'Supporting'
            or normalized(active[0].get('text')) != 'Apply to all missense variants.'
            or normalized(active[0].get('instructionsToUse'))):
        return 'BP1_PUBLISHED_STRENGTH_UNSUPPORTED'
    return 'BP1_SUPPORTED_PUBLISHED_GRAMMAR'


def retain_lof_association_raw_sources(compiled, specifications, capture):
    """Keep full pinned originals, never caller-authored qualification claims."""
    # Retain the actual rule that requires the review, independently of how its
    # notes are worded. This is transport only, not an absence qualification.
    candidates = [spec for spec in specifications if any(
        clause.get('entContent', {}).get('label') == 'BP1'
        and normalized(clause['entContent'].get('instructionsToUse')) == TRUNCATING_INSTRUCTION
        for clause in spec['original'].get('ld', {}).get('CriteriaCode', []))]
    if not candidates:
        return
    retained = {spec['specificationKey']: spec for spec in compiled['specifications']}
    raw_sources, captures = {}, {}
    for spec in candidates:
        source = spec['source']
        raw_sha = source['rawSha256']
        require(raw_sha in raw_sources or len(raw_sources) < MAX_LOF_RAW_SOURCES,
                'BP1_LOF_ASSOCIATION_RAW_SOURCE_LIMIT')
        capture_key = (source['rawRef'], raw_sha)
        if capture_key not in captures:
            captures[capture_key] = pinned(child(capture, source['rawRef']), raw_sha, MAX_LOF_RAW_BYTES)
        raw = captures[capture_key]
        entity = pointer(parse(raw), source['jsonPointer'])
        require(entity == spec['original'] and content_hash(entity) == spec['entitySha256'],
                'BP1_LOF_ASSOCIATION_RAW_ENTITY_MISMATCH')
        try:
            raw_text = raw.decode('utf-8')
        except UnicodeDecodeError as invalid:
            raise ValueError('BP1_LOF_ASSOCIATION_RAW_UTF8_REQUIRED') from invalid
        raw_sources[raw_sha] = {'rawSha256': raw_sha, 'rawJsonUtf8': raw_text}
        # Same compiled specification shape; the entire original graph remains untouched.
        retained[spec['specificationKey']] = {key: copy.deepcopy(spec[key]) for key in
                ('specificationKey', 'source', 'original', 'entitySha256')}
    compiled['specifications'] = [retained[key] for key in sorted(retained)]
    compiled['bp1LofAssociationRawSources'] = [raw_sources[key] for key in sorted(raw_sources)]


def prepare(catalog_file, catalog_sha, capture, function_screen=None, function_screen_sha=None,
            region_assessment=None, region_assessment_sha=None):
    catalog = parse(pinned(catalog_file, catalog_sha, 32_000_000))
    specifications, _ = load_specifications(catalog, Path(capture))
    regional = load_adoptions(region_assessment, region_assessment_sha)
    plan = {'schemaVersion': 'published-criterion-interpretation-plan-v1',
            'adoptionMode': 'PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS', 'clinicalValidationCompleted': False,
            'policyId': 'PROJECT_PUBLISHED_BP1', 'policyVersion': '1.2.0' if regional else '1.1.0',
            'catalog': {'file': str(catalog_file), 'sha256': catalog_sha}, 'captureDirectory': str(capture), 'records': []}
    audit = {'schemaVersion': 'bp1-published-batch-audit-v1', 'clinicalValidationCompleted': False,
             'projectRestriction': {'policyId': RESTRICTION, 'maximumRawDelta': '0.1', 'expertBp1Threshold': False,
                                    'experimentalNormalityEstablished': False}, 'clauses': []}
    for spec in specifications:
        original = spec['original']
        for ci, wrapper in enumerate(original.get('ld', {}).get('CriteriaCode', [])):
            clause = wrapper.get('entContent', {})
            if clause.get('label') != 'BP1':
                continue
            reason = disposition(original, clause)
            region_policy = None
            if spec['specificationKey'] in regional:
                assessment = regional[spec['specificationKey']]
                if assessment['entitySha256'] != spec['entitySha256']:
                    raise ValueError('BP1_REGION_SOURCE_ENTITY_MISMATCH')
                region_policy = derive_regions(clause)
                region_policy['sourceAssessment'] = {k:v for k,v in assessment.items() if k != 'attachments'}
                region_policy['sourceAssessment']['attachments'] = [{k:v for k,v in a.items() if k != 'file'} for a in assessment['attachments']]
                reason = 'BP1_SUPPORTED_PUBLISHED_REGION_GRAMMAR'
            item = {'specificationKey': spec['specificationKey'], 'source': spec['source'],
                    'entitySha256': spec['entitySha256'], 'clausePointer': f'/ld/CriteriaCode/{ci}',
                    'originalClause': wrapper, 'reasonCode': reason, 'executableScopes': 0,
                    'retainedScopes': 0, 'unresolvedScopes': []}
            audit['clauses'].append(item)
            for association in scopes(original):
                if association['geneSymbol'] not in clause.get('gene', []):
                    continue
                gene = association['rawGeneAssociation']
                disease = gene['diseases'][int(association['scopePointer'].rsplit('/', 1)[1])]
                tx = gene.get('preferredTranscript') or ''
                cd = clause.get('disease')
                issue = ('BP1_SOURCE_BACKGROUND_UNRESOLVED' if association['scopeStatus'] != 'EXACT' else
                    'BP1_SOURCE_CLAUSE_SCOPE_UNRESOLVED' if cd is not None and
                        (not isinstance(cd, list) or cd and association['scope']['mondoId'] not in cd) else
                    'BP1_SOURCE_INHERITANCE_CONFLICT' if any(m.get('modeOfInheritance', '').strip() !=
                        disease.get('preferredModeOfInheritance', '').strip() for m in disease.get('modesOfInheritance', [])) else
                    'BP1_SOURCE_TRANSCRIPT_UNRESOLVED' if not re.fullmatch(r'(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*', tx) else None)
                if issue:
                    item['unresolvedScopes'].append({'scopePointer': association['scopePointer'],
                        'scope': association['scope'], 'reasonCode': issue, 'sourceTranscript': tx})
                    continue
                executable = reason in ('BP1_SUPPORTED_PUBLISHED_GRAMMAR', 'BP1_SUPPORTED_PUBLISHED_REGION_GRAMMAR')
                status = ('EXECUTABLE' if executable else 'NOT_APPLICABLE' if reason == 'BP1_SOURCE_NOT_APPLICABLE'
                          else 'SOURCE_CONFLICT' if reason == 'BP1_SOURCE_APPLICABILITY_CONFLICT' else 'UNSUPPORTED_CONDITIONS')
                plan['records'].append({'recordId': f"bp1-{spec['specificationKey']}-{len(plan['records'])}",
                    'criterionId': 'ACMG_AMP_2015:BP1', 'specificationKey': spec['specificationKey'],
                    'clausePointer': item['clausePointer'], 'scopePointer': association['scopePointer'],
                    'transcript': tx, 'status': status, 'reasonCode': reason,
                    'interpretation': region_policy if region_policy else truncating_interpretation()
                        if reason == 'BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED' else {'type': TYPE, 'version': '1.0.0', 'projectRestriction': RESTRICTION} if executable
                        else {'type': 'PUBLISHED_SOURCE_DISPOSITION_ONLY_V1', 'version': '1.0.0'}})
                item['retainedScopes'] += 1
                item['executableScopes'] += int(executable)
    compiled = compile_plan(plan)
    retain_lof_association_raw_sources(compiled, specifications, Path(capture))
    if function_screen is not None:
        screening = parse(pinned(function_screen, function_screen_sha, 12_000_000))
        if (screening.get('schemaVersion') != 'bp1-published-function-screen-v1'
                or screening.get('purpose') != 'RESEARCH_REVIEW_BLOCK_ONLY'
                or screening.get('clinicalAdoption') is not False or screening.get('absenceMeansNormal') is not False):
            raise ValueError('BP1_FUNCTION_SCREEN_INVALID')
        compiled['bp1FunctionalScreen'] = screening
        compiled['preparation']['bp1FunctionScreenRawSha256'] = function_screen_sha
    return plan, compiled, audit


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--catalog', required=True); p.add_argument('--catalog-sha256', required=True)
    p.add_argument('--capture-directory', required=True); p.add_argument('--output-directory', required=True)
    p.add_argument('--function-screen'); p.add_argument('--function-screen-sha256')
    p.add_argument('--region-assessment'); p.add_argument('--region-assessment-sha256')
    a = p.parse_args()
    if bool(a.function_screen) != bool(a.function_screen_sha256): p.error('Both function-screen arguments are required together')
    if bool(a.region_assessment) != bool(a.region_assessment_sha256): p.error('Both region-assessment arguments are required together')
    plan, compiled, audit = prepare(a.catalog, a.catalog_sha256, a.capture_directory, a.function_screen, a.function_screen_sha256,
                                    a.region_assessment, a.region_assessment_sha256)
    output = Path(a.output_directory)
    output.mkdir(parents=True, exist_ok=False)
    for name, value in [('plan.json', plan), ('compiled.json', compiled), ('batch-audit.json', audit)]:
        write_new(output / name, value)
    print(json.dumps({'examinedClauses': len(audit['clauses']), 'retainedScopes': len(compiled['records']),
                      'executableScopes': sum(r['status'] == 'EXECUTABLE' for r in compiled['records']),
                      'fullSpecifications': len(compiled['specifications']), 'clinicalValidationCompleted': False}))


if __name__ == '__main__':
    main()
