"""Finite source-observation review for the PP2 other-phenotype condition.

This local program does not diagnose, infer causality, use ACMG scores or create
reviewed intake templates. Native expert variant-condition attribution remains
an independent route. A/B only terminate an actually owned source observation.
"""
from copy import deepcopy
import re
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from literature_case_structure import tables, paragraph_owned
from qualify_literature_cases import sentences
from model_literature_contract import canonical, digest

VERSION = 'pp2-bounded-literature-qualification-v1'
HCM = re.compile(r'(?:familial )?hypertrophic cardiomyopathy(?: [1-9][0-9]*)?', re.I)
NEGATIVE = re.compile(r'\b(?:no|not|without|suspected|possible|uncertain|excluded|family history)\b', re.I)
RELATIVE = re.compile(r"\b(?:mother|father|parents|sister|brother|sibling|aunt|uncle|grandmother|grandfather)\b", re.I)
RELATIVE_CARRIER = re.compile(RELATIVE.pattern + r'[^.!?]{0,80}\b(?:carried|carries|carry|harbou?red|tested (?:positive|negative))\b', re.I)
CODING = re.compile(r'c\.([1-9][0-9]*)([ACGT])>([ACGT])')
TRANSCRIPT = re.compile(r'(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*')
IDENTITY_FIELDS = ('geneSymbol', 'variantText', 'transcript')
CARRIER_NEGATIVE = re.compile(r'\b(?:no|not|neither|nor|without|lacked|negative|absent|excluded|undetectable)\b', re.I)


def issue(code): return dict(reasonCode='PP2_LITERATURE_' + code)


def citation(document, value):
    if not isinstance(value, dict) or value.get('sourceSha256',document['sourceSha256'])!=document['sourceSha256']: return None
    found = [u for u in document['units'] if u['unitId'] == value.get('unitId')]
    if len(found) != 1 or not isinstance(value.get('quote'), str) or not value['quote']:
        return None
    unit, text = found[0], value['quote']
    if unit['text'].count(text) != 1: return None
    return dict(sourceRawSha256=document['sourceSha256'], unitId=unit['unitId'], quote=text,
                start=unit['text'].index(text), end=unit['text'].index(text)+len(text))


def source_cases(source, core_reviews):
    doc, groups = source['main'], {}
    native = tables(doc)
    labels = {r['caseLabel'] for r in native}
    for review in core_reviews:
        for case in review.get('cases', []): labels.add(case['caseLabel'])
    for label in sorted(labels):
        originals = [r for r in native if r['caseLabel'] == label]
        owned = set()
        native_fields = []
        for row in originals:
            owned.update(row.get('rowUnitIds', []))
            owned.add(row['caseCitation']['unitId'])
            for field in row['fields']:
                owned.add(field['citation']['unitId']); native_fields.append(deepcopy(field))
        for unit in doc['units']:
            if not originals and paragraph_owned(doc, unit, label): owned.add(unit['unitId'])
        fields = native_fields[:]
        for review in core_reviews:
            for row in review.get('cases', []):
                if row['caseLabel'] != label: continue
                for field in row.get('fields', []):
                    ref = citation(doc, field.get('citation'))
                    if ref and ref['unitId'] in owned and field.get('subject') == 'PROBAND' and field.get('sourceValue','') in ref['quote']:
                        if originals:
                            if any(f['field']==field['field'] and f['sourceValue']==field['sourceValue'] and f['citation']['unitId']==ref['unitId'] for f in native_fields): fields.append(deepcopy(field))
                        elif field['field'] not in IDENTITY_FIELDS or narrative_identity_field(doc,label,field): fields.append(deepcopy(field))
        groups[label] = dict(caseLabel=label, ownedUnitIds=sorted(owned), fields=fields,
                             nativeRows=originals,ambiguousTableOwnership=len({r.get('tableId') for r in originals})>1)
    return groups


def carrier_pattern(label):
    prefix=r'(?:The|Our) (?:patient|proband)' if label=='proband' else r'(?:Case|Patient|Individual|Proband)\s+'+re.escape(label)
    return re.compile(r'^'+prefix+r'\s+(?:carried|harbored|harboured|was found to carry|was found to harbor|was heterozygous for)\b',re.I)


def affirmative_carrier(carrier, sentence, value):
    start=carrier.search(sentence)
    at=sentence.find(value, start.end()) if start else -1
    return (at >= 0 and not RELATIVE.search(sentence[:at+len(value)])
            and not CARRIER_NEGATIVE.search(sentence[start.end():at]))


def narrative_identity_field(document,label,field):
    found=[u for u in document['units'] if u['unitId']==field['citation']['unitId']]
    if len(found)!=1:return False
    carrier=carrier_pattern(label)
    return any(affirmative_carrier(carrier,sentence,field['sourceValue']) for _,_,sentence in sentences(found[0]['text']))

def representations(case):
    values = {name: set() for name in IDENTITY_FIELDS}
    evidence = []
    for field in case['fields']:
        name, value = field.get('field'), field.get('sourceValue')
        if name in values and isinstance(value, str):
            values[name].add(value.strip()); evidence.append(deepcopy(field['citation']))
    if len(values['geneSymbol']) != 1 or len(values['variantText']) != 1 or len(values['transcript']) > 1:
        return None, issue('EXACT_SOURCE_GENE_TRANSCRIPT_CODING_UNRESOLVED')
    gene, change = (next(iter(values[name])) for name in ('geneSymbol', 'variantText'))
    tx = next(iter(values['transcript']), '')
    paired = re.fullmatch(r'((?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*):(c\.[1-9][0-9]*[ACGT]>[ACGT])', change)
    if paired:
        if tx and tx != paired[1]: return None, issue('SOURCE_TRANSCRIPT_CONFLICT')
        tx, change = paired[1], paired[2]
    match = CODING.fullmatch(change)
    if not TRANSCRIPT.fullmatch(tx) or not match or match[2] == match[3]:
        return None, issue('EXACT_VERSIONED_CODING_SNV_REQUIRED')
    return dict(gene=gene, coding=change, transcript=tx, evidenceRefs=evidence), None


def relative_carrier_context(source_text, refs):
    # The literal value may omit the relative's name. Inspect only original
    # sentences that overlap this record's anchored excerpts, not the paper.
    return any(start < ref['end'] and end > ref['start'] and RELATIVE_CARRIER.search(sentence)
        for ref in refs for start, end, sentence in sentences(source_text[ref['unitId']]))


def identity(case, target, document, resolver=None):
    if case.get('ambiguousTableOwnership'):return dict(targetMatch='UNRESOLVED',identityEvidenceRefs=[],gaps=[issue('REPEATED_CASE_LABEL_ACROSS_TABLES_UNRESOLVED')])
    rep, gap = representations(case)
    if gap: return dict(targetMatch='UNRESOLVED', identityEvidenceRefs=[], gaps=[gap])
    if not case['nativeRows']:
        candidates = []
        explicit_changes=set()
        for unit in document['units']:
            if unit['unitId'] not in case['ownedUnitIds']: continue
            explicit_changes.update(re.findall(r'(?<![A-Za-z0-9])c\.[0-9]+(?:[+-][0-9]+|_[0-9]+)?(?:[ACGT]>[ACGT]|delins[ACGT]+|del[ACGT]*|ins[ACGT]+|dup[ACGT]*)',unit['text']))
            for start,end,sentence in sentences(unit['text']):
                combined=rep['transcript']+':'+rep['coding']
                if (affirmative_carrier(carrier_pattern(case['caseLabel']),sentence,combined)
                        and re.search(r'(?<![\w-])'+re.escape(rep['gene'])+r'(?![\w-])',sentence)):
                    candidates.append(dict(sourceRawSha256=document['sourceSha256'],unitId=unit['unitId'],quote=sentence,start=start,end=end))
        if not candidates or explicit_changes!={rep['coding']}:
            return dict(targetMatch='UNRESOLVED',identityEvidenceRefs=rep['evidenceRefs'],gaps=[issue('UNIQUE_SOURCE_OWNED_ALLELE_ASSERTION_UNRESOLVED')])
        rep['evidenceRefs']=candidates
    if resolver is not None:
        try:
            resolved = resolver.resolve_coding(rep['gene'], rep['coding'], rep['transcript'], None)
            if resolved.get('status') != 'LOCAL_IDENTITY_VERIFIED': raise ValueError('REFERENCE_IDENTITY_NOT_VERIFIED')
            # Actual resolver retained the reference read/identity. It is not an alias string comparison.
            if not resolved.get('referenceIdentity') or not resolved.get('referenceReads'):
                raise ValueError('REFERENCE_PROOF_NOT_RETAINED')
            # Keep only the three actually consulted codon bases for this allele
            # (the shared reference reader also keeps previous queries/cache hits).
            pos=int(CODING.fullmatch(rep['coding'])[1]); start=(pos-1)//3*3+1
            model=resolved['gencodeRecord']; wanted=set()
            for cpos in range(start,start+3):
                exon=next(e for e in model['exons'] if e['cdsStart']<=cpos<=e['cdsEnd'])
                coordinate=exon['genomicStart']+cpos-exon['cdsStart'] if model['strand']==1 else exon['genomicEnd']-cpos+exon['cdsStart']
                wanted.add(str(model['contig'])+':'+str(coordinate))
            reference_reads=[row for row in resolved['referenceReads'] if row.get('windowId') in wanted]
            if len(reference_reads)!=3: raise ValueError('REFERENCE_PROOF_NOT_RETAINED')
            same_current=(rep['gene']==target.get('geneSymbol') and rep['coding']==target.get('coding') and rep['transcript']==target.get('transcript'))
            if same_current and resolved['allele']!=target['allele']:
                return dict(targetMatch='UNRESOLVED',identityDisposition='SOURCE_CONFLICT',sourceRepresentation=rep,
                    normalizedSourceAllele=resolved['allele'],normalizedTargetAllele=target['allele'],
                    referenceIdentity=resolved['referenceIdentity'],referenceReads=reference_reads,
                    materialIdentity={k:target.get(k) for k in ('nativeModelSha256','alleleAssociationSha256','contextSha256','hcmPolicyBindingSha256')},
                    identityEvidenceRefs=rep['evidenceRefs'],gaps=[issue('CURRENT_ASSOCIATION_AND_LOCAL_REFERENCE_CONFLICT')])
            return dict(targetMatch='EXACT' if resolved['allele'] == target['allele'] else 'NON_TARGET',
                sourceRepresentation=rep, normalizedSourceAllele=resolved['allele'], normalizedTargetAllele=target['allele'],
                referenceIdentity=resolved['referenceIdentity'], referenceReads=reference_reads,
                identityEvidenceRefs=rep['evidenceRefs'], gaps=[])
        except (ValueError, KeyError, TypeError, OSError) as error:
            resolver_gap = str(error) if re.fullmatch(r'[A-Z][A-Z0-9_]{1,140}',str(error)) else 'REFERENCE_MAPPING_UNAVAILABLE'
    else: resolver_gap = 'REFERENCE_MAPPING_NOT_CONFIGURED'
    # This fallback is available only for the already reference-checked CURRENT
    # association supplied by the real opaque Java Material. It cannot exclude a
    # different coding alias, choose a transcript or manufacture another allele.
    pins = ('nativeModelSha256', 'alleleAssociationSha256', 'contextSha256', 'hcmPolicyBindingSha256')
    can_fallback=resolver is None or resolver_gap in {'DENOVO_IDENTITY_EXACT_MANE_MAPPING_UNAVAILABLE',
        'DENOVO_IDENTITY_EXACT_GENCODE_TRANSCRIPT_UNAVAILABLE','REFERENCE_MAPPING_NOT_CONFIGURED'} or (
        resolver_gap=='DENOVO_IDENTITY_EXACT_TRANSCRIPT_REQUIRED' and rep['transcript'].startswith('ENST'))
    if can_fallback and all(isinstance(target.get(k),str) and re.fullmatch('[a-f0-9]{64}', target[k]) for k in pins) and (
            rep['gene'] == target.get('geneSymbol') and rep['transcript'] == target.get('transcript') and rep['coding'] == target.get('coding')):
        return dict(targetMatch='EXACT', sourceRepresentation=rep, normalizedSourceAllele=target['allele'], normalizedTargetAllele=target['allele'],
            materialIdentity={k:target[k] for k in pins}, identityEvidenceRefs=rep['evidenceRefs'],
            referenceScope='ALREADY_REFERENCE_CHECKED_CURRENT_OPAQUE_MATERIAL_ASSOCIATION',gaps=[])
    return dict(targetMatch='UNRESOLVED', identityEvidenceRefs=rep['evidenceRefs'],gaps=[issue(resolver_gap)])


def hcm_value(value, document):
    value = value.strip()
    if HCM.fullmatch(value): return True
    return value.upper() == 'HCM' and any(re.search(r'hypertrophic cardiomyopathy\s*\(HCM\)', u['text'], re.I) for u in document['units'])


def clinical(case, source):
    doc = source['main']; positive, progression, gaps = [], [], []
    # A native table disease/diagnosis column belongs to that row; a generic
    # phenotype, title, current analysis context or model materialKind does not.
    for row in case['nativeRows']:
        for field in row['fields']:
            if field.get('field') == 'reportedDiagnosis' and hcm_value(field['sourceValue'], doc):
                ref=citation(doc,field['citation'])
                if ref: positive.append(ref)
    prefix=r'(?:Case|Patient|Individual|Proband)\s+'+re.escape(case['caseLabel'])
    if case['caseLabel']=='proband': prefix=r'(?:The|Our) (?:patient|proband)'
    assertion=re.compile(r'^'+prefix+r'\s+(?:was|is|had been|has been|was previously|had previously been)\s+(?:clinically )?diagnosed with\s+(?:familial )?hypertrophic cardiomyopathy(?:\s*\(HCM\))?\.?$', re.I)
    # Only explicit progression/dilation of an actually asserted HCM course.
    # A second report of DCM following HCM is not by itself a shared disease process.
    progression_pattern=re.compile(r'^'+prefix
        +r'\s+(?:was|was previously|was initially|had previously been|had initially been)\s+diagnosed with'
        +r'\s+(?:familial )?hypertrophic cardiomyopathy(?:\s*\(HCM\))?'
        +r'(?:,\s*which|\s+and)\s+(?:later|subsequently)\s+(?:'
        +r'(?:progressed to|evolved to)\s+(?:a dilated phase|ventricular dilation|dilated cardiomyopathy|end-stage HCM)'
        +r'|developed\s+ventricular dilation)\.?$',re.I)
    for field in case['fields']:
        if field.get('field')=='reportedDiagnosis' and not hcm_value(field['sourceValue'],doc):gaps.append(issue('ADDITIONAL_OR_UNRESOLVED_SOURCE_DIAGNOSIS'))
    for unit in doc['units']:
        if unit['unitId'] not in case['ownedUnitIds'] or unit['kind'] != 'BODY_PARAGRAPH': continue
        if re.search(r'\b(?:diagnosis|hypertrophic cardiomyopathy|HCM)\b.{0,100}\b(?:excluded|ruled out|withdrawn|revised|not confirmed|incorrect|in error)\b',unit['text'],re.I):gaps.append(issue('SOURCE_HCM_DIAGNOSIS_REVISED_OR_EXCLUDED'))
        if re.search(r'\bprimary dilated cardiomyopathy\b',unit['text'],re.I):gaps.append(issue('ADDITIONAL_NON_HCM_PRIMARY_DIAGNOSIS'))
        for start,end,sentence in sentences(unit['text']):
            other=re.match(r'^'+prefix+r'\s+(?:was|is|has been|had been)\s+(?:also |later |subsequently )?diagnosed with\s+(.+?)[.]?$',sentence,re.I)
            if other and not (assertion.search(sentence) or progression_pattern.search(sentence)) and not NEGATIVE.search(sentence):
                gaps.append(issue('ADDITIONAL_OR_UNRESOLVED_SOURCE_DIAGNOSIS'))
            if assertion.search(sentence) and not NEGATIVE.search(sentence):
                positive.append(dict(sourceRawSha256=doc['sourceSha256'],unitId=unit['unitId'],quote=sentence,start=start,end=end))
            if progression_pattern.search(sentence) and not NEGATIVE.search(sentence):
                ref=dict(sourceRawSha256=doc['sourceSha256'],unitId=unit['unitId'],quote=sentence,start=start,end=end)
                positive.append(ref); progression.append(ref)
    return dict(affirmativeHcm=bool(positive), explicitHcmProgression=bool(progression), clinicalEvidenceRefs=positive,
                scope='SOURCE_OWNED_CLINICAL_OBSERVATION_ONLY_NOT_VARIANT_CAUSALITY',gaps=gaps)


def qualify_paper(source, core_reviews, question_reviews, target, question_coverage, resolver=None, reading_state=None):
    doc=source['main']; cases=source_cases(source,core_reviews); observations=[]; gaps=[]; units=set(); seen_case_labels=set()
    source_text={unit['unitId']:unit['text'] for unit in doc['units']}
    allele=target.get('allele',{})
    if (allele.get('assembly')!='GRCh38' or type(allele.get('position')) is not int or allele.get('position',0)<1
            or allele.get('reference') not in tuple('ACGT') or allele.get('alternate') not in tuple('ACGT') or allele.get('reference')==allele.get('alternate')):
        gaps.append(issue('CURRENT_TARGET_REFERENCE_SCOPE_UNRESOLVED'))
    state=reading_state or {}
    if state.get('unreviewedVisualCaptionUnitIds'): gaps.append(issue('RELEVANT_VISUAL_MATERIAL_NOT_REVIEWED'))
    if state.get('pending') or state.get('materialIssues'): gaps.append(issue('SOURCE_READING_OR_EXTRACTION_PENDING'))
    coverage=source.get('coverage',{})
    if coverage.get('unparsedAttachments') or coverage.get('unmatchedSourceUnits') or coverage.get('missingFiles') or coverage.get('status')=='SUPPLEMENT_UNAVAILABLE_OR_UNPARSED':
        gaps.append(issue('ATTACHMENT_SOURCE_SCOPE_UNRESOLVED'))
    if any(a.get('status')=='ARCHIVED_NOT_PARSED' or a.get('unparsedParts') for a in source.get('attachments',[])):
        gaps.append(issue('ATTACHMENT_SOURCE_SCOPE_UNRESOLVED'))
    identities={label:identity(case,target,doc,resolver) for label,case in cases.items()}
    clinics={label:clinical(case,source) for label,case in cases.items()}
    all_targets=set()
    for review in question_reviews:
        all_targets.update(review.get('workUnit',{}).get('targetUnitIds',[]))
        if review.get('questionVersion')!='pp2-relationship-material-v1' or review.get('extractionCoverageProposal')!='EXTRACTED_WITHIN_TARGET':
            gaps.append(issue('QUESTION_EXTRACTION_INCOMPLETE'))
        for ordinal,record in enumerate(review.get('records',[]),1):
            if record.get('materialKindProposal')!='INDIVIDUAL_CASE':
                row_kind_gap=issue('AGGREGATE_FUNCTIONAL_OR_AUTHOR_MATERIAL_NEEDS_INDEPENDENT_CONTEXT')
            else:row_kind_gap=None
            refs=[record.get('anchor',{})]+[f.get('citation',{}) for f in record.get('facts',[])]
            checked=[citation(doc,ref) for ref in refs]
            row=dict(observationId=doc['sourceSha256']+':'+review.get('workUnitSha256','')+':'+str(ordinal),
                recordKeys=[dict(workUnitSha256=review.get('workUnitSha256'),recordOrdinal=ordinal)],
                targetMatch='UNRESOLVED',disposition='UNRESOLVED',identityEvidenceRefs=[],clinicalEvidenceRefs=[],gaps=[])
            observations.append(row)
            if row_kind_gap:row['gaps'].append(row_kind_gap);continue
            if (not checked or any(ref is None for ref in checked) or not record.get('facts')
                    or any(not isinstance(f.get('sourceValue'),str) or not f['sourceValue'] or f['sourceValue'] not in ref['quote']
                        for f,ref in zip(record['facts'],checked[1:]))):
                row['gaps'].append(issue('ORIGINAL_RECORD_ANCHOR_OR_FACTS_UNRESOLVED')); continue
            units.update(ref['unitId'] for ref in checked)
            anchor=checked[0]['unitId']; owners=[label for label,c in cases.items() if anchor in c['ownedUnitIds'] and all(ref['unitId'] in c['ownedUnitIds'] for ref in checked)]
            if len(owners)!=1:
                row['gaps'].append(issue('OBSERVATION_SOURCE_OWNERSHIP_UNRESOLVED')); continue
            family_clinical=re.compile(r'\b(?:mother|father|parents|sister|brother|sibling|aunt|uncle|grandmother|grandfather)\b.{0,70}\b(?:diagnos|disease|syndrome|cardiomyopathy|diabetes|phenotype|developed)',re.I)
            if relative_carrier_context(source_text,checked) or family_clinical.search(checked[0]['quote']) or any(family_clinical.search(f['sourceValue']) or family_clinical.search(f['citation']['quote']) for f in record['facts']):
                row['gaps'].append(issue('CLINICAL_SUBJECT_OR_RELATIVE_CONTEXT_UNRESOLVED')); continue
            # Do not borrow the case's HCM finding for a question fact that
            # actually names another family member's allele or result.
            if any(RELATIVE.search(f['sourceValue']) for f in record['facts']):
                row['gaps'].append(issue('CLINICAL_SUBJECT_OR_RELATIVE_CONTEXT_UNRESOLVED')); continue
            clinical_material=[f for f in record['facts'] if f['field'] in ('reportedClinicalDefinition','reportedDiseaseCourse','reportedClinicalAssessmentBasis','reportedSubject')]
            if any(RELATIVE.search(f['sourceValue']) or RELATIVE.search(f['citation']['quote']) for f in clinical_material):
                row['gaps'].append(issue('CLINICAL_SUBJECT_OR_RELATIVE_CONTEXT_UNRESOLVED')); continue
            label=owners[0]; seen_case_labels.add(label); row['caseKey']=dict(caseLabel=label)
            ident=identities[label]; row.update({k:deepcopy(v) for k,v in ident.items() if k!='gaps'}); row['gaps'].extend(ident['gaps'])
            dependencies=[]
            fact_names={f['field'] for f in record['facts']}
            if fact_names & {'reportedAlternativeExplanation','reportedLimitationsOrContradiction'}:
                dependencies.append(issue('ALTERNATIVE_OR_CONTRADICTORY_EXPLANATION_UNRESOLVED'))
            if fact_names & {'reportedVariantDiseaseStatement','reportedRelationshipBasis'}:
                dependencies.append(issue('AUTHOR_CLAIM_DIRECTION_OR_OBJECT_NOT_INDEPENDENTLY_RESOLVED'))
            if 'reportedSupportingReference' in fact_names:
                dependencies.append(issue('SUPPORTING_REFERENCE_NOT_INDEPENDENTLY_REVIEWED'))
            if row['targetMatch']=='NON_TARGET':
                # The owned genotype is a different allele. An ambiguous
                # author/cited claim in this record need not be about that allele.
                row['gaps'].extend(dependencies)
                if not dependencies: row['disposition']='EXACT_NON_TARGET'
                continue
            if row['targetMatch']!='EXACT': continue
            definitions=[f['sourceValue'] for f in clinical_material if f['field']=='reportedClinicalDefinition']
            primary=any(re.search(r'\bprimary dilated cardiomyopathy\b', f['sourceValue'],re.I) for f in record['facts'])
            clinic=clinics[label]; row['clinicalEvidenceRefs']=deepcopy(clinic['clinicalEvidenceRefs'])
            if clinic['gaps']:row['gaps'].extend(clinic['gaps']);continue
            if primary or (any(re.search(r'\bdilated cardiomyopathy\b',v,re.I) for v in definitions) and not clinic['explicitHcmProgression']):
                row['gaps'].append(issue('NON_HCM_OBSERVATION_CAUSAL_RELATIONSHIP_NOT_ESTABLISHED')); continue
            if not clinic['affirmativeHcm']:
                row['gaps'].append(issue('AFFIRMATIVE_SOURCE_HCM_CLINICAL_BACKGROUND_NOT_ESTABLISHED')); continue
            if any(not hcm_value(v,doc) and not any(v in r['quote'] for r in clinic['clinicalEvidenceRefs']) for v in definitions):
                row['gaps'].append(issue('ADDITIONAL_CLINICAL_BACKGROUND_UNRESOLVED')); continue
            row['clinicalDisposition']='CURRENT_TARGET_HCM_PROCESS'
            row['gaps'].extend(dependencies)
            if not dependencies: row['disposition']='CURRENT_TARGET_HCM_PROCESS'
    for review in question_reviews:
        for assessment in review.get('sourceAssessments',[]):
            ref=citation(doc,assessment.get('citation'))
            matching=ref and any(record.get('materialKindProposal')==assessment.get('materialKindProposal')
                and any(citation(doc,r) and r['unitId']==ref['unitId'] and (r['quote'] in ref['quote'] or ref['quote'] in r['quote'])
                    for r in [record.get('anchor',{})]+[f.get('citation',{}) for f in record.get('facts',[])]) for record in review.get('records',[]))
            if not matching:gaps.append(issue('SOURCE_ASSESSMENT_MATERIAL_WITHOUT_INDEPENDENT_REVIEW'))
    if not observations: gaps.append(issue('EMPTY_EXTRACTION_IS_NOT_NO_OTHER_PHENOTYPE_SUPPORT'))
    unreviewed_units=sorted(all_targets-units)
    if unreviewed_units: gaps.append(issue('DECLARED_SOURCE_UNITS_WITHOUT_QUALIFIED_OBSERVATION_REVIEW'))
    if set(cases)-seen_case_labels: gaps.append(issue('SOURCE_CASES_WITHOUT_RELATIONSHIP_REVIEW'))
    if question_coverage.get('status')!='RAW_QUESTION_SCOPE_READ': gaps.append(issue('RAW_QUESTION_SCOPE_INCOMPLETE'))
    unresolved=sum(r['disposition']=='UNRESOLVED' for r in observations)
    if unresolved: gaps.append(issue('OBSERVATION_RELATIONSHIP_UNRESOLVED'))
    return dict(schemaVersion='pp2-bounded-literature-paper-review-v1',qualificationVersion=VERSION,
        pmcid=source['pmcid'],sourceRawSha256=doc['sourceSha256'],bundleSha256=source['bundleSha256'],
        scopeMeaning='DECLARED_AVAILABLE_MAIN_AND_PARSED_ATTACHMENT_TEXT_UNITS_ONLY',wholePublicationRead=False,
        observations=observations,unresolvedRecords=unresolved,unresolvedUnitIds=unreviewed_units,gaps=gaps,
        unreviewedVisualCaptionUnitIds=deepcopy(state.get('unreviewedVisualCaptionUnitIds',[])),
        unresolvedAttachmentRefs=[{k:deepcopy(a[k]) for k in ('id','name','sourceSha256','status','unparsedParts') if k in a}
            for a in source.get('attachments',[]) if a.get('status')=='ARCHIVED_NOT_PARSED' or a.get('unparsedParts')],
        scopeStatus='COMPLETE_DECLARED_SCOPE' if not gaps else 'INCOMPLETE',
        positiveOtherPhenotypeGranted=False,scientificClassificationCreated=False)


def qualify_read_scope(configuration_path, expected_configuration_sha256, ledger, work_root, target, known_references, candidates):
    """Existing library/receipt owners are replayed again; no model or network call.

    target is produced only by the Java owner from its real opaque Material,
    not supplied to the model and not accepted as a reviewed intake document.
    """
    from bundle_model_contract import load
    from run_bundle_model import replay
    from core_local_identity import LocalIdentity, pinned
    from model_literature_contract import parse
    with Path(configuration_path).open('rb') as stream: config_raw=stream.read(65537)
    if len(config_raw)>65536 or digest(config_raw)!=expected_configuration_sha256:
        raise ValueError('PP2_QUALIFICATION_CONFIGURATION_PIN_MISMATCH')
    cfg=parse(config_raw); root=Path(cfg['projectRoot']).resolve(strict=True)
    library=Path(cfg['library']); library=library if library.is_absolute() else root/library
    work=Path(work_root).resolve(strict=True); papers=[]; gaps=[]; resolver=None
    spec=cfg.get('coreContext')
    if isinstance(spec,dict):
        try:
            context_raw=pinned(root,spec,2097152); context_cfg=parse(context_raw)
            if context_cfg.get('schemaVersion')!='denovo-core-context-config-v1': raise ValueError('CONTEXT_CONFIG_UNSUPPORTED')
            resolver=LocalIdentity(root,context_cfg['identity'])
        except (ValueError,KeyError,TypeError,OSError):
            resolver=None
    def original(pin):
        if not isinstance(pin,str) or not re.fullmatch('[a-f0-9]{64}',pin): raise ValueError('PP2_QUALIFICATION_ORIGINAL_PIN_INVALID')
        declared=ledger.get('originals',{}).get(pin)
        if not declared or declared.get('relativePath')!='originals/'+pin+'.body': raise ValueError('PP2_QUALIFICATION_ORIGINAL_NOT_RETAINED')
        path=(work/declared['relativePath']).resolve(strict=True)
        if not path.is_relative_to(work): raise ValueError('PP2_QUALIFICATION_ORIGINAL_OUTSIDE_CONTROL')
        with path.open('rb') as stream:raw=stream.read(4*1024*1024+1)
        if len(raw)>4*1024*1024 or len(raw)!=declared['bytes'] or digest(raw)!=pin: raise ValueError('PP2_QUALIFICATION_ORIGINAL_CHANGED')
        return path,parse(raw)
    for paper in ledger.get('papers',[]):
        try:
            source=load(library,paper['pmcid'],paper['bundleSha256'],criterion='PP2')
            if (source['main']['sourceSha256']!=paper['sourceRawSha256']
                    or digest(canonical(source['units']))!=paper.get('sourceUnitManifestSha256')):
                raise ValueError('PP2_QUALIFICATION_SOURCE_CHANGED')
            checked_core=[]; checked_q=[]
            for name,profile,out in [('checkedChunks','core-material-v2',checked_core),('relationshipChunks','pp2-relationship-material-v1',checked_q)]:
                indices=[]; counts=set(); configurations=set(); unit_pins={}
                for chunk in paper.get(name,[]):
                    criterion=chunk.get('originalExtractionCriterion','PP2')
                    if name=='relationshipChunks' and (criterion!='PP2' or chunk.get('originalExtractionProfile')!=profile):
                        raise ValueError('PP2_QUALIFICATION_QUESTION_PROFILE_OR_PURPOSE_MISMATCH')
                    path,artifact=original(chunk['artifactRawSha256'])
                    if artifact['binding'].get('extractionProfile')!=profile: raise ValueError('PP2_QUALIFICATION_ARTIFACT_PROFILE_MISMATCH')
                    # Every actually used raw request/response/receipt remains mandatory.
                    if (artifact['requestSha256']!=chunk['requestRawSha256'] or artifact['receipt']['responseSha256']!=chunk['responseRawSha256']
                            or digest(canonical(artifact['receipt']))!=chunk['receiptSha256']):
                        raise ValueError('PP2_QUALIFICATION_MODEL_ORIGINAL_PINS_MISMATCH')
                    for key in ('requestRawSha256','responseRawSha256','receiptSha256'): original(chunk[key])
                    actual=replay(library,path,criterion=criterion)
                    binding=artifact['binding']; indices.append(binding['chunkIndex']);counts.add(binding['chunkCount']);configurations.add(digest(canonical(artifact['configuration'])))
                    plan_pin=digest(canonical(actual['workUnit']));unit_pins[binding['chunkIndex']]=plan_pin
                    if chunk.get('chunkIndex')!=binding['chunkIndex'] or chunk.get('workUnitSha256')!=plan_pin:raise ValueError('PP2_QUALIFICATION_CHUNK_IDENTITY_MISMATCH')
                    if actual!=chunk['review']: raise ValueError('PP2_QUALIFICATION_REPLAY_CHANGED')
                    out.append(actual)
                planned=paper.get('workPlans' if name=='checkedChunks' else 'relationshipWorkPlans',[])
                if len(counts)!=1 or len(configurations)!=1 or sorted(indices)!=list(range(next(iter(counts),0))) or len(indices)!=len(set(indices)):
                    raise ValueError('PP2_QUALIFICATION_ALL_ARTIFACT_CHUNKS_REQUIRED')
                if len(planned)!=len(indices) or {p.get('chunkIndex') for p in planned}!=set(indices) or any(p.get('workUnitSha256')!=unit_pins.get(p.get('chunkIndex')) for p in planned):
                    raise ValueError('PP2_QUALIFICATION_DECLARED_WORK_PLAN_SET_MISMATCH')
            result=qualify_paper(source,checked_core,checked_q,target,paper.get('questionCoverage',{}),resolver,reading_state=paper)
            if paper.get('questionCoverage',{}).get('status')!='RAW_QUESTION_SCOPE_READ' or paper.get('pending'):
                result['scopeStatus']='INCOMPLETE'; result['gaps'].append(issue('SOURCE_QUESTION_WORK_PENDING'))
            papers.append(result)
        except (ValueError,KeyError,TypeError,OSError) as error:
            code=str(error) if re.fullmatch(r'[A-Z][A-Z0-9_]{1,150}',str(error)) else 'PP2_QUALIFICATION_SOURCE_OR_REPLAY_UNAVAILABLE'
            papers.append(dict(**{k:deepcopy(paper[k]) for k in ('pmid','pmcid','sourceRawSha256','bundleSha256') if k in paper},
                scopeStatus='INCOMPLETE',observations=[],unresolvedRecords=0,unresolvedUnitIds=[],gaps=[dict(reasonCode=code)]))
    present={p.get('pmid') for p in ledger.get('papers',[]) if p.get('pmid')}
    missing_refs=[deepcopy(r) for r in known_references if r.get('pmid') not in present]
    if missing_refs:gaps.append(issue('KNOWN_TARGET_REFERENCES_NOT_REVIEWED'))
    unmatched=[]
    for candidate in candidates:
        fields=[key for key in ('pmid','pmcid') if candidate.get(key)]
        matched=[paper for paper in ledger.get('papers',[]) if fields and all(candidate[key]==paper.get(key) for key in fields)]
        if len(matched)!=1: unmatched.append({key:candidate[key] for key in fields})
    if unmatched:gaps.append(issue('DECLARED_DISCOVERY_PAPERS_NOT_UNIQUELY_REVIEWED'))
    if ledger.get('pending') or not ledger.get('readingComplete') or ledger.get('unretainedOriginals'):
        gaps.append(issue('DECLARED_SOURCE_READING_PENDING'))
    if not papers:gaps.append(issue('NO_REVIEWED_PAPERS_NOT_AN_EMPTY_RETRIEVAL_PROOF'))
    if any(p['scopeStatus']!='COMPLETE_DECLARED_SCOPE' for p in papers):gaps.append(issue('PAPER_OBSERVATIONS_UNRESOLVED'))
    return dict(schemaVersion='pp2-bounded-literature-review-v1',qualificationVersion=VERSION,
        scopeMeaning='DECLARED_AVAILABLE_MAIN_AND_PARSED_ATTACHMENT_TEXT_UNITS_ONLY',wholePublicationRead=False,
        materialIdentity={k:target.get(k) for k in ('nativeModelSha256','alleleAssociationSha256','contextSha256','hcmPolicyBindingSha256')},
        targetAllele=deepcopy(target.get('allele')),papers=papers,declaredCandidateCount=len(candidates),declaredKnownCitationCount=len(known_references),
        unmatchedDeclaredPapers=unmatched,unresolvedRecords=sum(p.get('unresolvedRecords',0) for p in papers),
        unresolvedUnits=sum(len(p.get('unresolvedUnitIds',[])) for p in papers),unresolvedCitationRefs=missing_refs,
        scopeStatus='COMPLETE_DECLARED_SCOPE' if not gaps else 'INCOMPLETE',gaps=gaps,
        positiveOtherPhenotypeGranted=False,scientificClassificationCreated=False)
