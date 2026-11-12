"""Automatically project exact local ClinVar assertions for PM3 partners.

Source classification and independence qualification are deliberately separate.
No patient VCF membership, gene whitelist, network, or new classification claim.
"""
from pathlib import Path
import sys
import re
from copy import deepcopy
from core_local_identity import pinned
from literature_allele_identity import read_record

# Existing source parsers, not a second ClinVar interpretation implementation.
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ps1_pm5.prepare_reference_evidence import parse_vcv
from ps1_pm5.prepare_expert_reference_evidence import applied_criteria_statements


class PartnerSources:
    def __init__(self,root,identity_config,supply=None):
        self.root=root;self.supply=supply;self.records=[];self.issues=[]
        specs=identity_config.get('clinvar',[])
        if not isinstance(specs,list) or len(specs)>100:raise ValueError('PM3_PARTNER_SOURCE_LIMIT')
        for spec in specs:
            # Pin failures are not equivalent to missing database records.
            try:
                raw=pinned(root,spec,4*1024*1024)
                record=read_record(raw,spec['sha256'],spec['accession'])
                self.records.append((record,raw,spec))
            except (ValueError,OSError,KeyError,TypeError) as error:
                text=str(error)
                self.issues.append(text if re.fullmatch('[A-Z][A-Z0-9_]{1,150}',text) else 'PM3_PARTNER_SOURCE_READ_FAILED')

    def lookup(self,allele,gene):
        result={'status':'NOT_FOUND_IN_CONFIGURED_RECORDS','assertions':[],
                'sourceScope':'CONFIGURED_PINNED_CLINVAR_RECORDS','independenceStatus':'NOT_ESTABLISHED',
                'businessPublishable':False,'issues':list(self.issues)}
        matches=[(r,raw,s) for r,raw,s in self.records if r['allele']==allele and r['geneSymbol']==gene]
        if not matches and self.supply is not None:
            acquired=self.supply.lookup(allele,gene)
            result['acquisition']=deepcopy(acquired)
            result['sourceScope']='LOCAL_EXACT_LOCATOR_AND_OFFICIAL_VCV_CACHE'
            result['issues']=list(acquired['issues'])
            if acquired['status']!='EXACT_RECORDS_ACQUIRED':
                result['status']=acquired['status']
                return result
            for spec in acquired['records']:
                try:
                    raw=pinned(self.root,spec,4*1024*1024)
                    record=read_record(raw,spec['sha256'],spec['accession'])
                    if record['allele']!=allele or record['geneSymbol']!=gene:raise ValueError('PM3_CLINVAR_FETCHED_IDENTITY_MISMATCH')
                    matches.append((record,raw,spec))
                except (ValueError,OSError,KeyError,TypeError):
                    result['issues'].append('PM3_PARTNER_SOURCE_READ_FAILED')
        if not matches:
            if result['issues']:result['status']='SOURCE_READ_INCOMPLETE'
            else:result['issues']=['PM3_PARTNER_EXACT_RECORD_NOT_CONFIGURED']
            return result
        for record,raw,spec in matches:
            try:full=parse_vcv(raw,str(int(spec['accession'][3:])),allele)
            except (ValueError,KeyError,TypeError) as error:
                text=str(error)
                result['issues'].append(text if re.fullmatch('[A-Z][A-Z0-9_]{1,150}',text) else 'PM3_PARTNER_ASSERTION_PARSE_FAILED')
                continue
            for assertion in full['assertions']:
                statements=applied_criteria_statements(assertion['comments'],spec['accession'])
                codes={c['code'] for s in statements if s['status']=='EXPLICIT_SOURCE_LIST' for c in s['criteria']}
                dependency='COMPARISON_EVIDENCE_REQUIRES_TARGET_CHECK' if codes & {'PM3','PS1','PM5'} else (
                    'DECLARED_LIST_WITHOUT_COMPARISON_CRITERIA' if len(statements)==1 and statements[0]['status']=='EXPLICIT_SOURCE_LIST'
                    else 'COMPLETE_CRITERIA_LIST_NOT_AVAILABLE')
                result['assertions'].append({'vcv':full['vcv'],'scv':assertion['scv'],
                    'sourceSha256':spec['sha256'],'sourceLocator':assertion['xpath'],
                    'sourceClassification':assertion['classification'],'reviewStatus':assertion['reviewStatus'],
                    'dateLastEvaluated':assertion['dateLastEvaluated'],'recordStatus':assertion['recordStatus'],
                    'conditions':deepcopy(assertion['condition']),'criteriaStatements':statements,
                    'submissionIdentity':deepcopy(assertion['submissionIdentity']),
                    'sourceAccession':deepcopy(assertion['accession']),
                    'comments':deepcopy(assertion['comments']),'citations':deepcopy(assertion['citations']),
                    'sourceUrl':'https://www.ncbi.nlm.nih.gov/clinvar/variation/'+full['vcv']+'/',
                    'dependencyAssessment':dependency,'independenceStatus':'NOT_ESTABLISHED',
                    'issues':['PM3_DISEASE_AND_CLASSIFICATION_APPLICABILITY_NOT_YET_QUALIFIED']})
        result['status']='SOURCE_READ_INCOMPLETE' if result['issues'] else 'EXACT_SOURCE_ASSERTIONS_AVAILABLE'
        return result

    def attach(self,observations):
        for case in observations.get('cases',[]):
            for row in case.get('resolvedObservations',[]):
                if row.get('reportedObservation',{}).get('phase') == 'CIS':
                    continue
                partner=row.get('identities',{}).get('partner')
                if partner is not None and row.get('status')=='LOCAL_IDENTITIES_VERIFIED':
                    row['partnerClassificationSources']=self.lookup(partner['allele'],row['reportedObservation']['geneSymbol'])
        return observations
