"""Explicit published outside-region BP1 grammar; no gene-name dispatch."""
import re
from prepare_published_criterion_knowledge import pinned, parse

TYPE = 'PUBLISHED_OUTSIDE_REGION_SPLICE_STRONG_V1'
HEADER = 'See Specifications Figure 1A for process to apply codes according to variant type, location and predicted bioinformatic impact. Missense prediction not applicable.'
PREFIX = 'Apply BP1_Strong for silent substitution, missense or in-frame insertion, deletion or delins variants outside a (potentially) clinically important functional domain AND no splicing predicted (SpliceAI ≤0.1). As justified in the appendices, (potentially) clinically important functional domains are defined as: '
SUFFIX = '. See Specifications Figure1A and Appendix J for details.'

def plain(value):
    return ' '.join((value or '').replace('\\_', '_').replace('**', '').replace('_Missense prediction not applicable_', 'Missense prediction not applicable').split())

def require(ok, reason):
    if not ok: raise ValueError(reason)

def derive(clause):
    require(clause.get('label') == 'BP1' and clause.get('applicability') == 'Applicable'
            and clause.get('baseStrength') == 'Benign' and clause.get('defaultStrength') == 'Benign Supporting'
            and plain(clause.get('instructionsToUse')) == HEADER and not plain(clause.get('additionalComments'))
            and len(clause.get('gene', [])) == 1, 'BP1_REGION_SOURCE_CONDITIONS_UNSUPPORTED')
    active=[]
    for item in clause.get('strengthDescriptor', []):
        if item.get('applicability') == 'Applicable': active.append(item)
        else: require(item.get('applicability') in ['Not applicable', 'Not Applicable', 'Not Applicable for this VCEP'], 'BP1_REGION_STRENGTH_UNSUPPORTED')
    require(len(active)==1 and active[0].get('strength')=='Strong' and not plain(active[0].get('instructionsToUse')), 'BP1_REGION_STRENGTH_UNSUPPORTED')
    text=plain(active[0].get('text')); require(text.startswith(PREFIX) and text.endswith(SUFFIX), 'BP1_REGION_SOURCE_CONDITIONS_UNSUPPORTED')
    regions=[];previous=0
    for value in text[len(PREFIX):-len(SUFFIX)].split('; '):
        match=re.fullmatch(re.escape(clause['gene'][0])+r' ([A-Za-z0-9 -]+) aa ([1-9][0-9]*)-([1-9][0-9]*)',value)
        require(match is not None, 'BP1_REGION_SOURCE_RANGE_UNSUPPORTED')
        start,end=int(match[2]),int(match[3]);require(previous<start<=end<=100_000,'BP1_REGION_SOURCE_RANGE_UNSUPPORTED')
        regions.append({'label':match[1],'start':start,'end':end});previous=end
    require(0<len(regions)<=100,'BP1_REGION_SOURCE_RANGE_UNSUPPORTED')
    return {'type':TYPE,'version':'1.0.0','appliedStrength':'STRONG','supportedConsequence':'PURE_MISSENSE_SNV',
            'excludedProteinRegions':regions,'maximumRawDelta':'0.1','distanceBp':10000,'mask':0,
            'clinicalAdoption':False,'absenceMeansNormal':False}

def load_adoptions(file, sha):
    if file is None: return {}
    doc=parse(pinned(file,sha,12_000_000))
    require(doc.get('schemaVersion')=='bp1-region-attachment-assessment-v1' and doc.get('clinicalAdoption') is False,
            'BP1_REGION_ATTACHMENT_ASSESSMENT_REQUIRED')
    result={}
    for item in doc.get('assessments',[]):
        key=item['specificationKey'];require(key not in result,'BP1_REGION_ATTACHMENT_AMBIGUOUS')
        require(item.get('purpose')=='PROJECT_RESEARCH_SOURCE_INTERPRETATION' and len(item.get('attachments',[]))>=2,
                'BP1_REGION_ATTACHMENT_ASSESSMENT_REQUIRED')
        for attachment in item['attachments']:
            pinned(attachment['file'],attachment['rawSha256'],8_000_000)
            require(attachment.get('url','').startswith('https://cspec.genome.network/cspec/File/id/'), 'BP1_REGION_ATTACHMENT_SOURCE_INVALID')
        result[key]=item
    return result
