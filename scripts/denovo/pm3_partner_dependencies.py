"""Source-bound dependency audit, not a replacement variant classifier.

An explicit final criterion list identifies what needs checking. Narrative
sections and comparison names explain the remaining work; neither a name list
nor absence of the selected variant proves that the classification is independent.
"""
import re
from literature_allele_identity import protein

COMPARISONS = {'PM3', 'PS1', 'PM5'}
INTRINSIC = {'PVS1','PS3','PM1','PM2','PM4','PP2','PP3','BA1','BS1','BS3','BP1','BP3','BP4','BP7'}
TOKEN = r'(?:PVS1|PS[1-4]|PM[1-6]|PP[1-5]|BA1|BS[1-4]|BP[1-7])(?:_(?:VeryStrong|Very_Strong|Strong|Moderate|Supporting|StandAlone))?'
ASSAY = re.compile(
    r'This variant has been detected in at least one individual with (?P<diagnosis>[^().;]{1,100}) '
    r'\(PMID: (?P<pmid>[1-9][0-9]*)\), who was compound heterozygous for this variant and a '
    r'(?:likely pathogenic|pathogenic) variant (?P<comparator>c\.[1-9][0-9]*(?:[+-][1-9][0-9]*)?[ACGT]>[ACGT]), '
    r'which was confirmed in trans by parental testing\. '
    r'(?P<finding>This patient had significantly decreased creatine peak on brain MRS '
    r'(?:\(GAA not reported\) )?\(PP4_Moderate\)\.)'
)


def audit(statement, target=None, case_observation=None):
    """Only inspect the same source comment as the explicit final list.

    Source names are deliberately not promoted to genomic identities. Even a
    matched target protein is a review flag, not proof of circular reasoning.
    """
    text = statement.get('originalText', '')
    endings = list(re.finditer(r'\bIn summary,? this variant meets (?:the )?criteria', text))
    narrative = text[:endings[0].start()] if len(endings) == 1 else ''
    codes = {c.get('original', c.get('code')) for c in statement.get('criteria', [])}
    headers = list(re.finditer(r'(?<![A-Za-z0-9_])(' + TOKEN + r'):\s*', narrative))
    rows = []
    for criterion in statement.get('criteria', []):
        code = criterion.get('code')
        token = criterion.get('original', code)
        matches = [(m, headers[i+1].start() if i+1 < len(headers) else len(narrative))
                   for i, m in enumerate(headers) if m[1] == token]
        row = {'code': code, 'sourceDeclaredCriterion': token,
               'requirement': 'TARGET_INDEPENDENT_CLASSIFICATION_BASIS',
               'sourceId': statement.get('sourceId'), 'sourceLocator': statement.get('xpath'),
               'comparisonNames': [], 'issues': []}
        if len(matches) == 1:
            match, stop = matches[0]
            row['sourceText'] = narrative[match.start():stop].strip().rstrip(';').strip()
            row['sourceTextStart'] = match.start()
            row['sourceTextEnd'] = stop
        if code in INTRINSIC:
            row['status'] = 'INTRINSIC_CRITERION_DECLARED'
        elif code in COMPARISONS:
            row['status'] = 'COMPARISON_DEPENDENCY_UNRESOLVED'
            row['issues'].append('PM3_PARTNER_COMPARISON_DEPENDENCY_UNRESOLVED')
            if 'sourceText' not in row:
                row['issues'].append('PM3_PARTNER_CRITERION_NARRATIVE_MISSING_OR_AMBIGUOUS')
            else:
                # A bounded, literal source format. Never strip arbitrary prose
                # into a falsely exhaustive list of recognized variants.
                match = re.fullmatch(TOKEN + r':\s*Detected with (.+?)\.\s*\((PMID:[0-9]+(?:;\s*PMID:[0-9]+)*)\);?', row['sourceText'])
                if match:
                    parts = re.split(r',\s*(?:and\s+)?|\s+and\s+', match[1])
                    parsed = [re.fullmatch(r'([^(),;]{1,120}?)\s*\((P|LP|P/LP|VUS)\)', p.strip()) for p in parts]
                    if 0 < len(parsed) <= 32 and all(parsed):
                        row['comparisonNames'] = [{'reportedName': m[1].strip(), 'sourceLabel': m[2],
                            'identityStatus': 'NOT_VERIFIED', 'classificationIndependence': 'NOT_VERIFIED'} for m in parsed]
                        row['sourcePmids'] = re.findall(r'PMID:([0-9]+)', match[2])
                        row['issues'].append('PM3_PARTNER_COMPARATOR_IDENTITIES_AND_DEPENDENCIES_REQUIRED')
                if not row['comparisonNames']:
                    row['issues'].append('PM3_PARTNER_COMPARISON_NARRATIVE_NOT_STRUCTURED')
            target_aa = (target or {}).get('proteinChange')
            if target_aa:
                for comparison in row['comparisonNames']:
                    try:
                        if protein(comparison['reportedName']) == protein(target_aa):
                            comparison['targetAliasMatch'] = True
                            row['issues'].append('PM3_PARTNER_TARGET_ALIAS_PRESENT_REQUIRES_REVIEW')
                    except ValueError:
                        pass
        elif token == 'PP4_Moderate' and 'PM2_Supporting' in codes and case_observation:
            assay = case_observation.get('clinicalAssayProof') or {}
            findings = list(ASSAY.finditer(narrative))
            if (len(findings) == 1 and findings[0]['pmid'] == case_observation.get('paperPmid')
                    and findings[0]['comparator'] == case_observation.get('targetRepresentation')
                    and findings[0]['diagnosis'] in case_observation.get('reportedDiagnoses', [])
                    and case_observation.get('phase') == 'TRANS'
                    and assay.get('assay') == 'BRAIN_MRS'
                    and assay.get('observedResult') == 'SIGNIFICANTLY_REDUCED_CREATINE_PEAK'
                    and len(assay.get('references', [])) == 2):
                match = findings[0]
                row.update(status='TARGET_INDEPENDENT_BRAIN_MRS_ORIGINAL', sourceText=match['finding'],
                    sourceTextStart=match.start('finding'), sourceTextEnd=match.end('finding'),
                    sourceCaseLocator=match[0], sourcePmid=match['pmid'], sourceAssay=assay,
                    requirement='INDEPENDENT_PHENOTYPE_WITH_APPLICABLE_PP4_AND_PARTNER_PM2')
            else:
                row['status'] = 'EVENT_OR_OTHER_DEPENDENCY_UNRESOLVED'
                row['issues'].append('PM3_PARTNER_PHENOTYPE_ASSAY_OR_CASE_BINDING_UNRESOLVED')
        else:
            row['status'] = 'EVENT_OR_OTHER_DEPENDENCY_UNRESOLVED'
            row['issues'].append('PM3_PARTNER_EVENT_OR_OTHER_CRITERIA_DEPENDENCY_NOT_IMPLEMENTED')
        row['issues'] = sorted(set(row['issues']))
        rows.append(row)
    return rows
