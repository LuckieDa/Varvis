"""Read published PP1 policies from the existing pinned CSpec source inventory.

BA1 adoption is never inherited. Scope and every supported clause are reread
from the released original; unimplemented text is not a general-rule fallback.
The finite affected-relative-count grammar is independent of gene/variant.
"""
from copy import deepcopy
import re

from model_literature_contract import canonical, digest
from pp1_literature_contract import require
from pm3_specification_context import SpecificationContext, scopes
from prepare_applicability_sources import released_current, pointer, MOI
from pp1_diagnostic_context import published_bindings
from pp1_meiosis import (METHOD as MEIOSIS_METHOD, AFFECTED_NOTE, BENIGN_COMBINATION_NOTE, SCOPE_AND_SYMBOLS_NOTE,
                        FAMILY_THRESHOLD_PREFIX, duplicated_threshold_summary, threshold as meiosis_threshold)
from pp1_rule_attachments import referenced as attachment_references, inspect as inspect_attachments, load_manifest
from pp1_hht_phenotype import INSTRUCTIONS as HHT_INSTRUCTIONS
from pp1_affected_segregations import ANCHOR, SHARED_CONDITIONS, bs1_check, resolve_binding, threshold as affected_threshold

METHOD = 'PUBLISHED_AFFECTED_RELATIVES_COUNT'
INSTRUCTIONS = (
    '* Only count affected individuals from the same family (minus proband) that carry the variant or obligate carriers known with the disease. '
    '* The EP will not specifically define “affected”, “clinical examination” or cut-offs for aortic Z-scores. These are left to the discretion of the referring physicians.'
)
CLAUSE_FIELDS = {'_uniqueProp','additionalComments','baseStrength','defaultStrength','evidenceCategory',
    'gene','geneType','instructionsToUse','label','ns','originalACMGSummary','sepioID','strengthDescriptor',
    'disease','applicability','status','specificationType'}
DESCRIPTOR_FIELDS = {'applicability','id','instructionsToUse','specificationType','strength',
                     'strengthSepioID','text','type','status'}
HHT_RELEASE_NOTES = r'This version includes: \-additional clarifying notes for not applying certain evidence codes together \-updated HHT Phenotype attached document'
HHT_ACVRL1_RELEASE_NOTES = HHT_RELEASE_NOTES.replace('includes: ', r'includes: \-updated BMP10 interaction cluster residues (PM1) ')


def normal(value):
    return ' '.join(value.split()) if isinstance(value, str) else ''


def compile_count(clause):
    """Reject unknown conditions; extract all numeric boundaries from source."""
    require(not (set(clause) - CLAUSE_FIELDS), 'PUBLISHED_PP1_UNKNOWN_FIELD')
    require(clause.get('geneType') == 'nuclear' and clause.get('baseStrength') == 'Pathogenic'
            and clause.get('defaultStrength') == 'Pathogenic Supporting'
            and normal(clause.get('instructionsToUse')) == INSTRUCTIONS
            and not normal(clause.get('additionalComments')), 'PUBLISHED_PP1_METHOD_UNSUPPORTED')
    require(clause.get('applicability') in {None,'Applicable','Applicable with VCEP specification'},
            'PUBLISHED_PP1_NOT_APPLICABLE')
    descriptors = clause.get('strengthDescriptor', [])
    require(len(descriptors) == 5, 'PUBLISHED_PP1_STRENGTH_SET_UNSUPPORTED')
    thresholds = {}
    for row in descriptors:
        require(not (set(row) - DESCRIPTOR_FIELDS) and not normal(row.get('instructionsToUse')),
                'PUBLISHED_PP1_STRENGTH_CONDITION_UNSUPPORTED')
        strength = row.get('strength')
        require(strength not in thresholds, 'PUBLISHED_PP1_DUPLICATE_STRENGTH')
        if strength in {'Very Strong','Stand Alone'}:
            require(row.get('applicability') == 'Not Applicable for this VCEP' and not normal(row.get('text')),
                    'PUBLISHED_PP1_STRENGTH_CONDITION_UNSUPPORTED')
            thresholds[strength] = None
            continue
        require(strength in {'Supporting','Moderate','Strong'} and
                row.get('applicability') == 'Applicable with VCEP specification', 'PUBLISHED_PP1_STRENGTH_UNSUPPORTED')
        text = normal(row.get('text'))
        match = re.fullmatch(r'(≥)?([1-9][0-9]*)(?:-([1-9][0-9]*))? affected individuals\.', text)
        require(match is not None, 'PUBLISHED_PP1_THRESHOLD_UNSUPPORTED')
        low = int(match[2]); high = None if match[1] else int(match[3] or match[2])
        require(not (match[1] and match[3]) and (high is None or low <= high), 'PUBLISHED_PP1_THRESHOLD_INVALID')
        thresholds[strength] = {'minimum':low,'maximum':high,'sourceText':text}
    require(set(thresholds) == {'Supporting','Moderate','Strong','Very Strong','Stand Alone'},
            'PUBLISHED_PP1_STRENGTH_SET_UNSUPPORTED')
    rows = [thresholds[k] for k in ('Supporting','Moderate','Strong')]
    require(rows[0]['maximum'] is not None and rows[1]['maximum'] is not None
            and rows[0]['maximum'] + 1 == rows[1]['minimum']
            and rows[1]['maximum'] + 1 == rows[2]['minimum'] and rows[2]['maximum'] is None,
            'PUBLISHED_PP1_THRESHOLD_GAP_OR_OVERLAP')
    return {k.upper():thresholds[k] for k in ('Supporting','Moderate','Strong')}


def compile_meiosis(clause, family_condition=False, *, attachment=False, affected_segregation=False):
    require(not (set(clause) - CLAUSE_FIELDS) and clause.get('geneType') == 'nuclear'
            and clause.get('baseStrength') == 'Pathogenic' and clause.get('defaultStrength') == 'Pathogenic Supporting'
            and clause.get('applicability') == 'Applicable', 'PUBLISHED_PP1_METHOD_UNSUPPORTED')
    thresholds, seen = {}, set()
    for row in clause.get('strengthDescriptor', []):
        strength = row.get('strength')
        fields = DESCRIPTOR_FIELDS | {'additionalComments'} if attachment else DESCRIPTOR_FIELDS
        comments = row.get('additionalComments')
        require(not (set(row) - fields) and not normal(row.get('instructionsToUse')) and strength not in seen
                and (not comments or attachment and strength == 'Supporting' and
                     comments == {'Notes':['May be used as stronger evidence with increasing segregation data']}),
                'PUBLISHED_PP1_STRENGTH_CONDITION_UNSUPPORTED')
        seen.add(strength)
        if strength in {'Very Strong', 'Stand Alone'}:
            require(row.get('applicability') == 'Not applicable' and not normal(row.get('text')),
                    'PUBLISHED_PP1_STRENGTH_CONDITION_UNSUPPORTED')
            continue
        require(strength in {'Supporting','Moderate','Strong'} and row.get('applicability') == 'Applicable'
                and row.get('status') == 'approved', 'PUBLISHED_PP1_STRENGTH_UNSUPPORTED')
        text = normal(row.get('text'))
        require(text.startswith(FAMILY_THRESHOLD_PREFIX) == family_condition, 'PUBLISHED_PP1_MEIOSIS_THRESHOLD_UNSUPPORTED')
        if affected_segregation:
            thresholds[strength.upper()] = affected_threshold(text, strength)
        elif attachment:
            match = re.fullmatch(r'([1-9][0-9]?)(\+)? meioses \(1/([1-9][0-9]*) likelihood\)', text)
            require(match and int(match[1]) <= 16 and int(match[3]) == 2 ** int(match[1]),
                    'PUBLISHED_PP1_MEIOSIS_THRESHOLD_UNSUPPORTED')
            thresholds[strength.upper()] = {'minimum':int(match[1]), 'maximum':None if match[2] else int(match[1]),
                                            'minimumIndependentFamilies':1, 'sourceText':text}
        else:
            thresholds[strength.upper()] = meiosis_threshold(text)
    require(seen == {'Supporting','Moderate','Strong','Very Strong','Stand Alone'}, 'PUBLISHED_PP1_STRENGTH_SET_UNSUPPORTED')
    low, mid, high = (thresholds[k] for k in ('SUPPORTING','MODERATE','STRONG'))
    if affected_segregation:
        require(low['minimum'] < mid['minimum'] < high['minimum']
                and low['minimum'] < mid['highlySpecificMinimum'] < high['highlySpecificMinimum']
                and not normal(clause.get('instructionsToUse')) and not normal(clause.get('additionalComments')),
                'PUBLISHED_PP1_AFFECTED_SEGREGATION_CONDITIONS_UNSUPPORTED')
        low['maximum'] = mid['minimum'] - 1
        mid['maximum'] = high['minimum'] - 1
        return thresholds
    require(low['maximum'] == mid['minimum'] - 1 and mid['maximum'] == high['minimum'] - 1 and high['maximum'] is None,
            'PUBLISHED_PP1_THRESHOLD_GAP_OR_OVERLAP')
    if family_condition:
        summary = duplicated_threshold_summary(thresholds)
        header = re.escape(' '.join(clause.get('gene', [])) + ' EP Specification:')
        instructions = normal(clause.get('instructionsToUse')).replace(r'\_', '_')
        require(re.fullmatch(rf'\*\*{header}\*\* {re.escape(summary)}', instructions)
                and re.fullmatch(rf'{header} (?:PP1: )?{re.escape(summary.removeprefix("PP1: "))}',
                                 normal(clause.get('additionalComments'))),
                'PUBLISHED_PP1_MEIOSIS_SUMMARY_CONFLICT')
    else:
        require(normal(clause.get('instructionsToUse')) == (HHT_INSTRUCTIONS if attachment else AFFECTED_NOTE)
                and not normal(clause.get('additionalComments')),
                'PUBLISHED_PP1_METHOD_UNSUPPORTED')
    return thresholds


def inherited_scope(root, descriptors, specification, association, selection):
    from urllib.parse import parse_qs, urlsplit
    from expert_inheritance_source import supply
    applicable = [row for row in descriptors if isinstance(row, dict)
                  and parse_qs(urlsplit(row.get('sourceUrl','')).query).get('values') == [selection['geneSymbol']]]
    context = {'hgncId':association['scope']['hgncId'], 'geneSymbol':selection['geneSymbol'],
               'mondoId':selection['diseaseId'], 'publishedInheritance':[], 'sources':[]}
    supply(root, applicable, [specification], [context])
    require(context['publishedInheritance'] == [selection['inheritance']],
            'PUBLISHED_PP1_EXACT_INHERITANCE_SOURCE_REQUIRED' if not context['publishedInheritance']
            else 'PUBLISHED_PP1_INHERITANCE_CONFLICT')
    return [{field: row['source'][field] for field in ('sourceUrl','sha256','jsonPointer','uuid','declaredMode')}
            for src in context['sources'] for row in src['matches']]


def select_specifications(specifications, source, selection, *, root=None, attachment_records=(), allele_scope=None,
                          published_inheritance=None):
    matches = []
    for spec in specifications:
        original = spec['original']
        for association in scopes(original):
            scope = association['scope']
            if association['geneSymbol'] != selection['geneSymbol'] or scope['mondoId'] != selection['diseaseId']:
                continue
            for index, node in enumerate(original.get('ld',{}).get('CriteriaCode',[])):
                clause = node.get('entContent',{})
                if clause.get('label') != 'PP1' or selection['geneSymbol'] not in clause.get('gene',[]):
                    continue
                disease = clause.get('disease')
                if isinstance(disease,list) and disease and selection['diseaseId'] not in disease:
                    continue
                match = {'specificationKey':spec['specificationKey'],'source':deepcopy(spec['source']),
                    'scopePointer':association['scopePointer'],'clausePointer':f'/ld/CriteriaCode/{index}',
                    'clauseSha256':digest(canonical(node)), 'clause':deepcopy(node),'status':'UNRESOLVED'}
                matches.append(match)
                try:
                    require(released_current(original), 'PUBLISHED_PP1_NOT_RELEASED')
                    require(disease is None or isinstance(disease,list), 'PUBLISHED_PP1_SCOPE_UNRESOLVED')
                    require(association['scopeStatus'] in {'EXACT','INHERITANCE_NOT_PROVIDED'}, 'PUBLISHED_PP1_SCOPE_UNRESOLVED')
                    modes={'AD':'AUTOSOMAL_DOMINANT','AR':'AUTOSOMAL_RECESSIVE','XLR':'X_LINKED_RECESSIVE'}
                    require(not scope['moi'] or scope['moi'] == modes.get(selection['inheritance']), 'PUBLISHED_PP1_INHERITANCE_CONFLICT')
                    declared_modes = pointer(original, association['scopePointer']).get('modesOfInheritance', [])
                    require(isinstance(declared_modes, list) and all(isinstance(m, dict)
                            and MOI.get(normal(m.get('modeOfInheritance'))) == modes.get(selection['inheritance'])
                            for m in declared_modes), 'PUBLISHED_PP1_INHERITANCE_CONFLICT')
                    inheritance_proof = []
                    if not scope['moi'] and not declared_modes and published_inheritance is not None:
                        inheritance_proof = inherited_scope(root, published_inheritance, spec, association, selection)
                    attachments = attachment_references(clause)
                    phenotype = None
                    ruleset = pointer(original, association['scopePointer'].split('/entContent/', 1)[0])
                    if not attachments and allele_scope is None and (original.get('ld',{}).get('File') or ruleset.get('ld',{}).get('File')):
                        attachments = ['']
                    require(allele_scope is None or not attachments, 'PUBLISHED_PP1_ATTACHMENT_SCOPE_UNRESOLVED')
                    if attachments:
                        audits = inspect_attachments(root, spec, match, association, clause, attachments, attachment_records)
                        match['ruleAttachments'] = audits
                        statuses = {a['status'] for a in audits}
                        if (len(audits) == 1 and statuses == {'VERIFIED_CONDITIONS_IMPLEMENTED'}
                                and normal(clause.get('instructionsToUse')) == HHT_INSTRUCTIONS):
                            phenotype = audits[0]['phenotypeQualification']
                        else:
                            match['reasonCode'] = ('PP1_RULE_ATTACHMENT_VERIFICATION_FAILED' if 'VERIFICATION_FAILED' in statuses
                                                   else 'PP1_RULE_ATTACHMENT_CONDITIONS_NOT_IMPLEMENTED' if 'VERIFIED_CONDITIONS_NOT_IMPLEMENTED' in statuses
                                                   else 'PP1_RULE_ATTACHMENT_REQUIRED')
                            continue
                    instruction = normal(clause.get('instructionsToUse'))
                    has_family_condition = bool(re.match(r'^\*\*[A-Z][A-Z0-9-]* EP Specification:\*\* PP1: Requires ', instruction))
                    is_meiosis = instruction == AFFECTED_NOTE or has_family_condition or phenotype is not None or allele_scope is not None
                    release_notes = normal(original['entContent'].get('releaseNotes'))
                    verified_release_note = (allele_scope is not None and release_notes ==
                        ('Updated ClinGen Cardiomyopathy Expert Panel Specifications to the ACMG/AMP Variant '
                         'Interpretation Guidelines for ' + selection['geneSymbol']))
                    require((not original.get('ld',{}).get('File') or phenotype or allele_scope is not None)
                            and not normal(original['entContent'].get('notes'))
                            and (not release_notes or instruction == AFFECTED_NOTE and release_notes == BENIGN_COMBINATION_NOTE
                                 or has_family_condition and release_notes == SCOPE_AND_SYMBOLS_NOTE
                                 or phenotype and release_notes in {HHT_RELEASE_NOTES, HHT_ACVRL1_RELEASE_NOTES}
                                 or verified_release_note),
                            'PUBLISHED_PP1_ADDITIONAL_CONDITIONS_UNRESOLVED')
                    if is_meiosis:
                        require(scope['moi'] == 'AUTOSOMAL_DOMINANT', 'PUBLISHED_PP1_MEIOSIS_AD_REQUIRED')
                        thresholds = compile_meiosis(clause, has_family_condition, attachment=phenotype is not None,
                                                    affected_segregation=allele_scope is not None)
                    else:
                        thresholds = compile_count(clause)
                    gene = association['rawGeneAssociation']
                    transcripts = list(gene.get('transcripts',[]))
                    if gene.get('preferredTranscript'): transcripts.append(gene['preferredTranscript'])
                    require(all(isinstance(t,str) and re.fullmatch(r'NM_[0-9]+(?:\.[1-9][0-9]*)?',t)
                                for t in transcripts), 'PUBLISHED_PP1_TRANSCRIPT_SCOPE_UNRESOLVED')
                    title = next(d['preferredTitle'] for d in gene['diseases'] if d.get('preferredMondoId') == selection['diseaseId'])
                    require(isinstance(title,str) and title.strip(), 'PUBLISHED_PP1_DISEASE_TITLE_MISSING')
                    policy = {'method':METHOD,'scope':{k:selection[k] for k in ('geneSymbol','diseaseId','inheritance')},
                        'thresholds':thresholds, 'affectedTerms':[title], 'affectedDefinition':'REPORTED_PHYSICIAN_ASSESSMENT',
                        'penetrance':'AFFECTED_ONLY','locusModel':'EXPERT_AFFECTED_RELATIVE_COUNT',
                        'transcriptRestrictions':sorted(set(transcripts)), 'excludeProband':True,
                        'sameFamilyOnly':True,'obligateCarriersImplemented':selection['inheritance'] == 'AD',
                        'candidateHandling':'REPORTED_ALTERNATIVES_REQUIRE_REVIEW',
                        'relationshipCaution':'AFFECTED_FIRST_DEGREE_CONNECTED_COMPONENT_REQUIRED',
                        'methodCautionsSource':'https://www.clinicalgenome.org/site/assets/files/7445/clingen_fbn1_acmg_specifications_v1.pdf#page=12',
                        'inheritanceBasis':'PUBLISHED_SCOPE' if scope['moi'] or declared_modes else 'NOT_SPECIFIED_COUNT_HAS_NO_INHERITANCE_BRANCH',
                        'ruleClause':spec['specificationKey']+match['clausePointer']}
                    if inheritance_proof:
                        policy.update(inheritanceBasis='EXACT_PUBLISHED_EXPERT_CONTEXT',inheritanceSources=inheritance_proof)
                    if is_meiosis:
                        policy.update(method=MEIOSIS_METHOD, locusModel='EXPERT_INFORMATIVE_MEIOSIS_COUNT',
                                      obligateCarriersImplemented=False)
                        policy.pop('excludeProband')
                        policy.pop('sameFamilyOnly')
                        policy.pop('methodCautionsSource')
                    if allele_scope is not None:
                        preferred = allele_scope['transcript']
                        require(not transcripts or any(preferred == tx or '.' not in tx and preferred.startswith(tx + '.')
                                                       for tx in transcripts), 'PUBLISHED_PP1_EXPERT_TRANSCRIPT_CONFLICT')
                        policy.update(transcriptRestrictions=[preferred], alleleScope=deepcopy(allele_scope),
                                      conditioning=ANCHOR, obligateCarriersImplemented=True,
                                      publishedCautions=SHARED_CONDITIONS,
                                      bs1FrequencyCheck=bs1_check(original, allele_scope))
                    if phenotype:
                        policy['phenotypeQualification'] = phenotype
                        policy['affectedDefinition'] = phenotype['method']
                    diagnostic = published_bindings(spec, scopes(original), selection)
                    policy['diagnosticCriteriaBindings'] = diagnostic['bindings']
                    policy['diagnosticBindingNeeds'] = diagnostic['unresolved']
                    pp4 = published_pp4_exclusion(spec, original, association, selection)
                    if pp4:
                        policy['pp4Exclusion'] = pp4
                    match.update(status='SUPPORTED',policy=policy)
                except (ValueError,KeyError,StopIteration) as error:
                    match['reasonCode'] = str(error) if isinstance(error,ValueError) else 'PP1_PUBLISHED_SCOPE_UNRESOLVED'
    out = {'status':'KNOWLEDGE_MISSING','reasonCode':'PP1_PUBLISHED_EXACT_CONTEXT_UNAVAILABLE',
           'matches':matches,'source':deepcopy(source),'clinicalApproval':False,'dataClass':'PUBLIC_RESEARCH'}
    if len(matches) > 1:
        out.update(status='KNOWLEDGE_CONFLICT',reasonCode='PP1_MULTIPLE_PUBLISHED_CONTEXTS')
    elif matches:
        m=matches[0]
        if m.get('ruleAttachments'):
            out['ruleAttachments'] = m['ruleAttachments']
        if m['status'] != 'SUPPORTED':
            out.update(status='METHOD_UNSUPPORTED',reasonCode=m['reasonCode'])
        else:
            out.update(status='QUALIFIED',reasonCode='PP1_PUBLISHED_EXACT_RULE_SELECTED',policy=m['policy'],
                contextKey=digest(canonical([source,m])), qualificationBasis='ORIGINAL_PUBLISHED_EXPERT_SPECIFICATION',
                sourceEvidence={'specification':m['source'],'scopePointer':m['scopePointer'],
                    'clausePointer':m['clausePointer'],'clauseSha256':m['clauseSha256']})
    return out


def published_pp4_exclusion(spec, original, association, selection):
    """Accept an unconditional whole-criterion exclusion for an exact scope."""
    if association['scopeStatus'] != 'EXACT':
        return None
    entries = [(i, n) for i, n in enumerate(original.get('ld', {}).get('CriteriaCode', []))
               if n.get('entContent', {}).get('label') == 'PP4']
    if len(entries) != 1:
        return None
    i, node = entries[0]
    clause = node['entContent']
    strengths = clause.get('strengthDescriptor', [])
    diseases = clause.get('disease')
    covers_scope = selection['diseaseId'] in diseases if isinstance(diseases, list) and diseases else (
        not diseases and len(scopes(original)) == 1)
    if (normal(clause.get('applicability')).casefold() != 'not applicable'
            or clause.get('gene') != [selection['geneSymbol']] or not covers_scope
            or (diseases and normal(clause.get('additionalComments')) not in {
                '', 'Inherited cardiomyopathies have high locus heterogeneity as well as non-genetic etiologies.'})
            or normal(clause.get('instructionsToUse'))
            or not isinstance(strengths, list) or not strengths
            or any(normal(d.get('applicability')).casefold() != 'not applicable'
                   or normal(d.get('instructionsToUse')) or normal(d.get('text')) for d in strengths)):
        return None
    return {'schemaVersion': 'pp1-published-pp4-exclusion-v1', 'criterion': 'ACMG_AMP_2015:PP4',
            'status': 'NOT_APPLICABLE', 'specificationKey': spec['specificationKey'],
            'clausePointer': f'/ld/CriteriaCode/{i}', 'clauseSha256': digest(canonical(node)),
            'scope': {k: selection[k] for k in ('geneSymbol', 'diseaseId', 'inheritance')}}


def select(root, config, selection, *, published_inheritance=None):
    inventory = SpecificationContext(root, config)
    try:
        reviewed = resolve_binding(root, config.get('releasedAlleleScopes'), selection)
    except (ValueError, OSError, KeyError, TypeError) as error:
        reason = str(error)
        return {'status':'METHOD_UNSUPPORTED',
                'reasonCode':reason if re.fullmatch(r'PUBLISHED_PP1_[A-Z_0-9]+', reason)
                else 'PP1_RELEASED_ALLELE_SCOPE_VERIFICATION_FAILED', 'source':inventory.source}
    if reviewed is not None:
        spec, allele_scope = reviewed
        return select_specifications([spec], inventory.source, selection, root=root, allele_scope=allele_scope,
                                     published_inheritance=published_inheritance)
    try:
        records = load_manifest(root, config['ruleAttachmentManifest']) if 'ruleAttachmentManifest' in config else ()
    except (ValueError, OSError, KeyError, TypeError):
        records = None
    return select_specifications(inventory.specifications, inventory.source, selection, root=root, attachment_records=records,
                                 published_inheritance=published_inheritance)
