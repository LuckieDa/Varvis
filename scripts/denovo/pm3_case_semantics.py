"""Verify literal PM3 genotypes in explicit case table columns.

This proves reported same-case observations, not genomic identity, trans phase,
pathogenicity or disease applicability. Unrecognised prose remains reviewable.
"""
import re
from copy import deepcopy

ALLELE = r'(?:N[MR]_[0-9]+\.[0-9]+:)?c\.[*\-]?[0-9]+(?:[+-][0-9]+)?(?:_[*\-]?[0-9]+(?:[+-][0-9]+)?)?(?:[ACGT]>[ACGT]|delins[ACGT]+|del[ACGT]*|dup[ACGT]*|ins[ACGT]+)'
EXACT_ALLELE = re.compile(ALLELE)
PROTEIN_ALLELE = re.compile(r'p\.(?:[A-Z*]|[A-Z][a-z]{2})[1-9][0-9]*(?:[A-Z*]|[A-Z][a-z]{2})')
VARIANT_HEADING = re.compile(r'(?:(?:variant|mutation|allele|genotype|cdna|cds change|nucleotide change|coding change|protein change|amino acid change|HGVS c\.|HGVS p\.)(?:\s*[12ab])?|(?:paternal|maternal) allele)', re.I)
GENE_HEADING = re.compile(r'gene(?: symbol)?', re.I)
ZYGOSITY_HEADING = re.compile(r'zygosity|genotype', re.I)
TRANSCRIPT_HEADING = re.compile(r'(?:refseq\s+)?transcript(?:\s+(?:id|accession|version))?', re.I)
DIAGNOSIS_HEADING = re.compile(r'(?:clinical\s+)?diagnosis|disease(?:\s+(?:id|identifier))?|condition', re.I)
AFFECTED_HEADING = re.compile(r'affected(?:\s+status)?|clinical\s+status', re.I)
PARENT_TEST_HEADING = re.compile(r'(mother|father)(?:\s+(?:variant\s+)?testing|\s+test results)', re.I)
PARENT_TEST = re.compile(r'(?P<first>'+ALLELE+r')\s+(?P<a>detected|not detected);\s*(?P<second>'+ALLELE+r')\s+(?P<b>detected|not detected)\.?', re.I)


def parent_test(fact):
    """Two explicit results, never infer a negative from an unmentioned allele.

    The case-owned row identifies the proband; the heading identifies its parent.
    Restricted to literal table cells. Broad prose/assay ambiguity stays unresolved.
    """
    scope=fact.get('ownership') or {}
    heading=PARENT_TEST_HEADING.fullmatch((scope.get('columnHeading') or '').strip())
    value=fact['sourceValue'].strip()
    match=PARENT_TEST.fullmatch(value)
    if (scope.get('method')!='EXPLICIT_UNIQUE_CASE_ROW' or not heading or not match
            or not scope.get('headingUnitId') or fact.get('issues')
            or scope.get('headingEvidence', {}).get('status') == 'AMBIGUOUS'
            or fact.get('subject')!=heading.group(1).upper()
            or scope.get('cellText','').strip()!=value or fact['citation']['quote'].strip()!=value
            or match['first']==match['second']):
        return None
    return {'parent':heading.group(1).upper(), 'results':{
        match['first']:match['a'].casefold()=='detected',match['second']:match['b'].casefold()=='detected'}}


def table_fact(fact, heading):
    scope = fact.get('ownership') or {}
    return (scope.get('method') == 'EXPLICIT_UNIQUE_CASE_ROW'
            and scope.get('headingEvidence', {}).get('status') != 'AMBIGUOUS'
            and scope.get('cellText', '').strip() == fact['sourceValue'].strip()
            and heading.fullmatch((scope.get('columnHeading') or '').strip())
            and fact.get('subject') == 'PROBAND' and not fact.get('issues'))


def alias_key(value):
    """Notation equivalence only. Does not identify a genomic allele."""
    from literature_allele_identity import protein
    value=re.sub(r'\s+','',value)
    if PROTEIN_ALLELE.fullmatch(value) or re.fullmatch(r'[A-Z*][1-9][0-9]*[A-Z*]',value):
        return 'protein:'+protein(value)
    return value


def shared_caption(fact,field):
    scope=fact.get('ownership') or {};text=scope.get('captionText','');value=fact['sourceValue'].strip()
    if (scope.get('method')!='SHARED_TABLE_CAPTION_SCOPE' or fact.get('issues')
            or fact.get('subject')!='PROBAND' or fact['citation']['quote'] not in text or value not in fact['citation']['quote']):return False
    # Only an explicit table heading, not a gene name found anywhere in prose.
    from pm3_source_context import caption_gene
    gene=caption_gene(text)
    if not gene:return False
    if field=='geneSymbol':return gene==value
    if field=='transcript':
        transcripts=set(re.findall(r'NM_[0-9]+\.[1-9][0-9]*',text))
        return transcripts=={value} and bool(re.search(r'Variants are reported relative to '+re.escape(value)+r'\.',text))
    return False


def allele_representations(values):
    """Keep a reported coding/protein pair; sequence verification happens later.

    Multiple coding alleles or differing protein effects remain ambiguous. A
    matching protein label never establishes equality of two genomic alleles.
    """
    coding={v for v in values if EXACT_ALLELE.fullmatch(v)}
    proteins={alias_key(v):v for v in sorted(values) if PROTEIN_ALLELE.fullmatch(v)}
    if len(coding)>1 or len(proteins)>1 or not values:return None
    if len(coding)+sum(PROTEIN_ALLELE.fullmatch(v) is not None for v in values)!=len(values):return None
    return {'representation':next(iter(coding)) if coding else next(iter(proteins.values())),
            'protein':next(iter(proteins)).removeprefix('protein:') if proteins else None}


def qualify(case, target):
    """Only exact typed table values; a negated or parent-valued cell cannot pass."""
    proposals = []
    for index, fact in enumerate(case['facts']):
        name, value = fact['field'], fact['sourceValue'].strip()
        accepted = False
        parental=parent_test(fact) if name=='parentalTransmission' else None
        if name in ('targetVariant','partnerVariant'):
            notation=re.sub(r'\s+','',value)
            accepted = bool(table_fact(fact, VARIANT_HEADING) and (EXACT_ALLELE.fullmatch(notation) or PROTEIN_ALLELE.fullmatch(notation))
                and fact['citation']['quote'].strip() == value)
        elif name == 'geneSymbol':
            accepted = bool(table_fact(fact, GENE_HEADING) and re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]{0,39}', value)
                            and fact['citation']['quote'].strip() == value)
        elif name == 'transcript':
            accepted = bool(table_fact(fact, TRANSCRIPT_HEADING) and re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*', value)
                            and fact['citation']['quote'].strip() == value)
        elif name == 'diagnosis':
            accepted = bool(table_fact(fact,DIAGNOSIS_HEADING) and 0<len(value)<=512
                            and fact['citation']['quote'].strip()==value)
        elif name == 'affectedStatus':
            accepted = bool(table_fact(fact,AFFECTED_HEADING) and value.casefold() in ('affected','unaffected')
                            and fact['citation']['quote'].strip()==value)
        elif name == 'reportedZygosity':
            accepted = bool(table_fact(fact, ZYGOSITY_HEADING) and value.casefold() in
                            {'homozygous','heterozygous','compound heterozygous'}
                            and fact['citation']['quote'].strip() == value)
        elif name == 'parentalTransmission':
            accepted=parental is not None
        if name in ('geneSymbol','transcript') and shared_caption(fact,name):accepted=True
        if name in ('diagnosis','affectedStatus'):
            from pm3_cohort_context import cohort_fact
            if cohort_fact(fact):accepted=True
        from pm3_prose_facts import accepts
        if accepts(fact,case['caseLabel']):accepted=True
        from pm3_single_case import accepts as single_accepts
        if single_accepts(fact,case['caseLabel']):accepted=True
        from pm3_clinical_table_context import accepts as clinical_accepts
        if clinical_accepts(fact,case['caseLabel']):accepted=True
        from pm3_genotype_context import accepts as genotype_accepts
        if genotype_accepts(fact,case['caseLabel']):accepted=True
        from pm3_pdf_observations import accepts as pdf_accepts
        if pdf_accepts(fact):accepted=True
        from pm3_supplement_context import accepts as supplement_accepts
        if supplement_accepts(fact):accepted=True
        if accepted:
            proposals.append({'field':name,'value':re.sub(r'\s+','',value) if name in ('targetVariant','partnerVariant') else value,
                'reportedValue':value,'factIndex':index,
                'container':fact['ownership']['container'],
                'columnHeading':fact['ownership'].get('columnHeading'),
                'headingUnitId':fact['ownership'].get('headingUnitId'),
                'method':'EXPLICIT_SHARED_TABLE_CAPTION' if fact['ownership']['method']=='SHARED_TABLE_CAPTION_SCOPE' else 'EXPLICIT_CASE_TYPED_TABLE_VALUE',
                'citation':deepcopy(fact['citation']),
                'supportingCitations':deepcopy(fact.get('supportingCitations',[]))})
            if fact['ownership'].get('headingEvidence'):
                proposals[-1]['headingEvidence']=deepcopy(fact['ownership']['headingEvidence'])
            if (fact.get('ownership') or {}).get('method')=='EXPLICIT_COHORT_TABLE_CASE':
                proposals[-1]['method']='EXPLICIT_COHORT_TABLE_CASE'
            elif (fact.get('ownership') or {}).get('method')=='EXPLICIT_CASE_PARAGRAPH':
                proposals[-1]['method']='EXPLICIT_CASE_SENTENCE'
            elif (fact.get('ownership') or {}).get('method')=='ANONYMOUS_SOURCE_GENOTYPE_ROW':
                proposals[-1]['method']='ANONYMOUS_SOURCE_GENOTYPE_ROW_NOT_INDEPENDENCE_PROOF'
            elif (fact.get('ownership') or {}).get('method')=='EXPLICIT_JATS_COMPLETE_GENOTYPE_SUPPLEMENT':
                proposals[-1]['method']='EXPLICIT_JATS_COMPLETE_GENOTYPE_SUPPLEMENT'
            if fact['ownership'].get('method') in ('EXPLICIT_SINGLE_HOMOZYGOUS_GENOTYPE_ROW','SOURCE_CLINICAL_APPENDIX_COHORT_ROW_JOIN','EXPLICIT_SINGLE_PROBAND_RESULTS'):
                proposals[-1]['method']=fact['ownership']['method']
                proposals[-1]['sourceBinding']=deepcopy(fact['ownership'])
            if parental is not None:proposals[-1]['parentTest']=parental
    observations = []; issues=[]; gaps=[]
    for container in sorted({p['container'] for p in proposals}):
        group = [p for p in proposals if p['container']==container]
        values = lambda name: {p['value'] for p in group if p['field']==name}
        genes, target_values, partner_values = (values(n) for n in ('geneSymbol','targetVariant','partnerVariant'))
        target_pair=allele_representations(target_values);partner_pair=allele_representations(partner_values)
        if (target_values and target_pair is None) or (partner_values and partner_pair is None):
            if 'PM3_VARIANT_REPRESENTATIONS_UNRESOLVED' not in issues:issues.append('PM3_VARIANT_REPRESENTATIONS_UNRESOLVED')
        zygosity = {v.casefold() for v in values('reportedZygosity')}
        def missing(reason, fields):
            if reason not in issues:issues.append(reason)
            requirement = ({'requiredAnyOf': [[field] for field in fields]}
                           if reason == 'PM3_CASE_PARTNER_OR_HOMOZYGOSITY_UNRESOLVED' else {'requiredFields':fields})
            gaps.append({'container':container,'reasonCode':reason,**requirement,
                         'verifiedFactIndexes':[p['factIndex'] for p in group],
                         'scope':'CURRENT_CASE_CONTAINER_NOT_WHOLE_PAPER'})
        # Alias selection only: exact transcript and selected genomic identity
        # are independently verified downstream, never inferred from this match.
        if genes != {target['geneSymbol']}:
            missing('PM3_CASE_GENE_UNRESOLVED' if not genes else 'PM3_CASE_GENE_CONFLICT',['geneSymbol'])
            continue
        if target_pair is None:
            missing('PM3_CASE_TARGET_REPRESENTATION_UNRESOLVED',['targetVariant'])
            continue
        if not {alias_key(v.split(':')[-1]) for v in target_values}&{alias_key(v.split(':')[-1]) for v in target['variantAliases']}:
            missing('PM3_CASE_TARGET_ALIAS_NOT_MATCHED',['targetVariant'])
            continue
        targets={target_pair['representation']};partners={partner_pair['representation']} if partner_pair else set()
        if partner_pair and {alias_key(v) for v in targets}.isdisjoint({alias_key(v) for v in partners}) and 'homozygous' not in zygosity:
            branch = 'REPORTED_TWO_ALLELES_PHASE_UNRESOLVED'
        elif zygosity == {'homozygous'} and (not partner_values or partner_pair
                and target_pair['representation'] == partner_pair['representation']
                and target_pair['protein'] == partner_pair['protein']):
            branch = 'REPORTED_HOMOZYGOUS'
            partners=set();partner_pair=None
        else:
            missing('PM3_CASE_GENOTYPE_CONFLICT' if 'homozygous' in zygosity and partner_values else
                    'PM3_CASE_PARTNER_OR_HOMOZYGOSITY_UNRESOLVED',['partnerVariant','reportedZygosity'])
            continue
        paternal={p['value'] for p in group if p['field'] in ('targetVariant','partnerVariant')
                  and (p.get('columnHeading') or '').strip().casefold()=='paternal allele' and p.get('headingUnitId')}
        maternal={p['value'] for p in group if p['field'] in ('targetVariant','partnerVariant')
                  and (p.get('columnHeading') or '').strip().casefold()=='maternal allele' and p.get('headingUnitId')}
        inherited_pair=(len(paternal)==len(maternal)==1 and paternal.isdisjoint(maternal)
                        and paternal|maternal==targets|partners and bool(partners))
        tests=[p['parentTest'] for p in group if 'parentTest' in p
               and set(p['parentTest']['results'])==targets|partners and bool(partners)]
        parent_values={}
        for test in tests:
            for allele,present in test['results'].items():
                parent_values.setdefault((test['parent'],allele),set()).add(present)
        contradictory=any(len(v)>1 for v in parent_values.values())
        for parent,alleles in [('FATHER',paternal),('MOTHER',maternal)]:
            if any(parent_values.get((parent,a))=={False} for a in alleles):contradictory=True
        one_parent=any(sum(t['results'].values())==1 for t in tests)
        phase='CONFLICT' if contradictory else 'TRANS' if inherited_pair or one_parent else 'UNKNOWN'
        observations.append({'kind':branch,'geneSymbol':target['geneSymbol'],
            'targetRepresentation':next(iter(targets)),
            'partnerRepresentation':next(iter(partners)) if partners else None,
            'reportedTranscripts':sorted(values('transcript')),
            'reportedProteins':{'target':target_pair['protein'],'partner':partner_pair['protein'] if partner_pair else None},
            'facts':group,'phase':phase,
            'reportedDiagnoses':sorted(values('diagnosis')),
            'reportedAffectedStatus':('AFFECTED' if {v.casefold() for v in values('affectedStatus')}=={'affected'}
                else 'UNAFFECTED' if {v.casefold() for v in values('affectedStatus')}=={'unaffected'}
                else 'CONFLICT' if values('affectedStatus') else 'UNKNOWN'),
            'phaseBasis':'CONTRADICTORY_PARENTAL_RESULTS' if contradictory else
                'EXPLICIT_DISTINCT_PATERNAL_MATERNAL_ALLELES' if inherited_pair else
                'ONE_PARENT_BOTH_ALLELES_TESTED_ONLY_ONE_DETECTED' if one_parent else 'NOT_ESTABLISHED',
            'genomicIdentityStatus':'NOT_RESOLVED',
            'classificationStatus':'NOT_ESTABLISHED','rarityStatus':'NOT_ESTABLISHED',
            'businessPublishable':False})
    return {'schemaVersion':'pm3-case-semantics-v1','caseLabel':case['caseLabel'],
            'verifiedLiteralFacts':proposals,'observations':observations,'issues':issues,'assemblyGaps':gaps,
            'status':'REPORTED_GENOTYPE_VERIFIED' if observations else 'CASE_SEMANTICS_UNRESOLVED',
            'unverifiedFactIndexes':[i for i in range(len(case['facts'])) if i not in {p['factIndex'] for p in proposals}],
            'unverifiedFindings':[{'factIndex':i,'field':f['field'],'reasonCode':
                'PM3_CASE_ATTRIBUTION_UNRESOLVED' if not f.get('ownership') else
                'PM3_TABLE_HEADER_MEANING_AMBIGUOUS' if f['ownership'].get('headingEvidence',{}).get('status')=='AMBIGUOUS' else
                'PM3_VARIANT_COLUMN_MEANING_UNRESOLVED' if f['field'] in ('targetVariant','partnerVariant') and
                    not VARIANT_HEADING.fullmatch((f['ownership'].get('columnHeading') or '').strip()) else
                'PM3_REPORTED_FACT_SEMANTICS_NOT_SUPPORTED',
                'columnHeading':(f.get('ownership') or {}).get('columnHeading'),
                **({'headingEvidence':deepcopy(f['ownership']['headingEvidence'])}
                   if (f.get('ownership') or {}).get('headingEvidence') else {}),
                'citation':deepcopy(f['citation'])}
                for i,f in enumerate(case['facts']) if i not in {p['factIndex'] for p in proposals}]}
