"""Batch-bind unchanged source-reviewed PVS1 policies for explicit project research.
No source review is created; no disease alias, new interval or clinical adoption is inferred.
"""
import argparse
import copy
import re
from datetime import datetime
from pathlib import Path
from assemble_materials import parse, pinned, content_hash, require, write_new, historical_materials, load_sources, background, domain_review_issues, same_splice_scope
from splice_source_qualification import qualify as qualify_splice

QUALIFIED_SCHEMA = 'pvs1-source-qualified-knowledge-v1'


def source_qualification(config):
    q = config['sourceQualification']
    require(set(q) == {'policyId', 'version', 'purpose', 'allowedDataClasses', 'clinicalAcceptance', 'createsExpertReview'}
            and isinstance(q['policyId'], str) and q['policyId'].strip() and q['version'] == '1.0.0'
            and q['purpose'] == 'PROJECT_RESEARCH'
            and q['allowedDataClasses'] == ['SYNTHETIC', 'PRIVACY_BOUNDED_RUNTIME']
            and q['clinicalAcceptance'] is False and q['createsExpertReview'] is False,
            'SOURCE_QUALIFICATION_POLICY_INVALID')
    release = config['release']
    require(set(release) == {'bindingId', 'assembledAt'} and isinstance(release['bindingId'], str)
            and release['bindingId'].strip(), 'RELEASE_IDENTITY_INVALID')
    require(datetime.fromisoformat(release['assembledAt'].replace('Z', '+00:00')).tzinfo is not None,
            'RELEASE_TIME_INVALID')
    return dict(q, bindingId=release['bindingId'], boundAt=release['assembledAt'])


def splice_binding(material, policy):
    """Verify transport identities only. Event, review and consequence checks remain in the core."""
    records, reasons = [], []
    checks = material.get('spliceResourceChecks', [])
    if any(c.get('status') != 'RAW_SHA_MATCH' for c in checks):
        reasons.append('SPLICE_RESOURCE_BYTES_NOT_ALL_VERIFIED')
    verified = {c['binding']['canonicalSha256']: c['binding']['sha256'] for c in checks
                if c.get('status') == 'RAW_SHA_MATCH'}
    sources = {s['sourceId']: s for s in policy['scientificSources']}
    for m in material.get('spliceMaterials', []):
        outcome = m['outcome']
        pin = content_hash(outcome)
        if pin != m.get('canonicalSha256') or verified.get(pin) != m.get('rawSha256'):
            reasons.append('SPLICE_RESOURCE_BINDING_REQUIRED')
        if not same_splice_scope(policy, outcome):
            reasons.append('SPLICE_CONTEXT_BINDING_MISMATCH')
        if outcome.get('transcriptModelSha256') != policy['transcriptModelBinding']['canonicalSha256']:
            reasons.append('SPLICE_MODEL_BINDING_MISMATCH')
        source_checks = m.get('sourceChecks', [])
        if (not source_checks or any(c.get('status') != 'RAW_SHA_MATCH' for c in source_checks)
                or [c.get('source') for c in source_checks] != outcome.get('sourceRecords')):
            reasons.append('SPLICE_SOURCE_BYTES_REQUIRED')
        if any(s['sourceId'] in sources and sources[s['sourceId']] != s for s in outcome['sourceRecords']):
            reasons.append('SPLICE_SOURCE_IDENTITY_CONFLICT')
        records.append(copy.deepcopy(outcome))
    return records, list(dict.fromkeys(reasons))


def assemble(config, facts, facts_sha, materials, profile):
    qualified = config.get('schemaVersion') == 'pvs1-source-qualified-assembly-input-v1'
    auth=source_qualification(config) if qualified else config['authorization']
    if not qualified:
        legacy_authorization(auth)
    require(profile.get('resources') and profile.get('registryBinding') and profile.get('vepResourceId'), 'RESOURCE_PROFILE_INCOMPLETE')
    return assemble_materials(config, facts, facts_sha, materials, profile, auth, qualified)


def legacy_authorization(auth):
    require(set(auth)=={'bindingId','boundAt','validUntil','operator','purpose','allowedDataClasses'},'AUTHORIZATION_FIELDS_INVALID')
    require(all(isinstance(auth[k],str) and auth[k].strip() for k in ['bindingId','operator']), 'BINDING_IDENTITY_REQUIRED')
    start=datetime.fromisoformat(auth['boundAt'].replace('Z','+00:00')); end=datetime.fromisoformat(auth['validUntil'].replace('Z','+00:00'))
    require(start.tzinfo is not None and end.tzinfo is not None and end>start, 'BINDING_TIME_INVALID')
    require(auth['purpose']=='PROJECT_RESEARCH' and auth['allowedDataClasses']==['SYNTHETIC'], 'SYNTHETIC_RESEARCH_BINDING_ONLY')


def assemble_materials(config, facts, facts_sha, materials, profile, auth, qualified):
    source_by_id={};source_occurrences={};entry_by_id={};policies=[];models={};entries=[];audit=[];splice_outcomes={}
    for m in materials:
        p=m['policy'];policy_sha=content_hash(p)
        require(policy_sha==m['policySha256'],'POLICY_IDENTITY_CHANGED')
        # Preserve all original catalog restrictions, including gaps and unadopted entries.
        for group,items in [(source_by_id,m['catalog']['sources']),(entry_by_id,m['catalog']['entries'])]:
            key='sourceId' if group is source_by_id else 'entryId'
            for item in items:
                previous=group.get(item[key])
                if key=='sourceId':
                    # The same bytes may have different explanatory roles in different
                    # original catalogs. Keep every original record, never merge identities.
                    without_role=lambda x:{k:v for k,v in x.items() if k!='role'}
                    require(previous is None or without_role(previous)==without_role(item),'CATALOG_ID_CONFLICT')
                    occurrence={'catalogId':m['catalog'].get('catalogId',''),'record':copy.deepcopy(item)}
                    if occurrence not in source_occurrences.setdefault(item[key],[]):source_occurrences[item[key]].append(occurrence)
                else:require(previous is None or previous==item,'CATALOG_ID_CONFLICT')
                group[item[key]]=copy.deepcopy(item)
        policies.append(copy.deepcopy(p))
        model=m.get('modelMaterial')
        reasons=[];splice_verification=None;unresolved_splice_outcomes=[]
        if p.get('review',{}).get('status')!='REVIEWED_IMPLEMENTED':reasons.append('POLICY_SOURCE_REVIEW_INCOMPLETE')
        reasons.extend(domain_review_issues(p))
        splice_records=[]
        if p.get('schemaVersion')=='pvs1-truncating-nmd-policy-v4':
            splice_records,splice_issues=splice_binding(m,p)
            reasons.extend(splice_issues)
        review_checks=m.get('sourceReviewChecks',[])
        inline=bool(re.fullmatch('[0-9a-f]{64}',m.get('candidateBundleRawSha256',''))) and any(content_hash(q)==policy_sha for q in m.get('originalCandidate',{}).get('policies',[]))
        review_witness='EXTERNAL_PINNED_REVIEW' if review_checks else 'INLINE_REVIEW_IN_PINNED_ORIGINAL' if inline else 'MISSING'
        if (review_checks and any(c['status']!='RAW_SHA_MATCH' for c in review_checks)) or (not review_checks and not inline):reasons.append('POLICY_SOURCE_REVIEW_ORIGINALS_REQUIRED')
        if not m.get('sourceChecks') or any(c['status']!='RAW_SHA_MATCH' for c in m['sourceChecks']):reasons.append('POLICY_SOURCE_BYTES_REQUIRED')
        if qualified:
            checks=m.get('sourceChecks', [])
            if 'SYNTHETIC_RESEARCH_ONLY' in p.get('review', {}).get('kind', ''):
                reasons.append('POLICY_ORIGINAL_USE_RESTRICTED')
            if ([c.get('source') for c in checks] != p.get('scientificSources')
                    or any(type(c.get('bytes')) is not int or not 1 <= c['bytes'] <= 4_000_000
                           or not re.fullmatch('[0-9a-f]{64}', c.get('source', {}).get('sha256', '')) for c in checks)):
                reasons.append('POLICY_SOURCE_BYTES_REQUIRED')
            if not re.fullmatch('[0-9a-f]{64}', m.get('candidateBundleRawSha256', '')):
                reasons.append('POLICY_ORIGINAL_CANDIDATE_PIN_REQUIRED')
            if p.get('appliedStrength') not in {'VERY_STRONG', 'STRONG', 'MODERATE', 'SUPPORTING'}:
                reasons.append('POLICY_STRENGTH_CONDITIONS_REQUIRED')
            if p.get('schemaVersion') == 'pvs1-truncating-nmd-policy-v4':
                splice_verification, qualification_issues, unresolved_splice_outcomes = qualify_splice(m, p)
                reasons.extend(qualification_issues)
        if model is None or content_hash(model['model'])!=p['transcriptModelBinding']['canonicalSha256']:reasons.append('POLICY_MODEL_BINDING_REQUIRED')
        else:models[model['canonicalSha256']]=copy.deepcopy(model['model'])
        relevance=p.get('nmdScope',{}).get('exonRelevance','')
        if not relevance or relevance.startswith('PENDING') or p.get('mechanism',{}).get('status')!='ESTABLISHED_LOSS_OF_FUNCTION':reasons.append('POLICY_APPLICABILITY_INCOMPLETE')
        sc=p['scope'];allelic={'AUTOSOMAL_DOMINANT':'monoallelic_autosomal','AUTOSOMAL_RECESSIVE':'biallelic_autosomal'}.get(sc['moi'])
        if not allelic:reasons.append('INHERITANCE_MAPPING_NOT_IMPLEMENTED')
        related=[r for r in facts['records'] if background(r['sourceRecord']) and background(r['sourceRecord'])[:2]==(sc['hgncId'],sc['mondoId'])]
        records=[r for r in related if background(r['sourceRecord'])[2]==allelic]
        if related and not records:reasons.append('G2P_INHERITANCE_CONFLICT')
        if any(r['sourceRecord']['molecular mechanism'].strip()!='loss of function' for r in records):reasons.append('G2P_MECHANISM_CONFLICT')
        if any(r['sourceRecord']['confidence'].strip() not in {'strong','definitive'} for r in records):reasons.append('G2P_SOURCE_STRENGTH_REQUIRES_REVIEW')
        audit.append({'policyId':p['policyId'],'policySha256':policy_sha,'scope':copy.deepcopy(sc),'status':'BLOCKED' if reasons else 'SOURCE_QUALIFIED_PROJECT_RESEARCH' if qualified else 'BOUND_FOR_SYNTHETIC_RESEARCH','reasonCodes':list(dict.fromkeys(reasons)),'g2pExactRecords':len(records),'newScientificApproval':False,'reviewWitness':review_witness,'originalCandidateRawSha256':m.get('candidateBundleRawSha256')})
        if p.get('schemaVersion')=='pvs1-truncating-nmd-policy-v4':
            audit[-1].update(spliceOutcomeCount=len(splice_records), spliceMaterialStatus='BLOCKED' if reasons else
                             'CORE_VALIDATION_REQUIRED' if splice_records else 'MATERIAL_REQUIRED_FOR_SPLICE_EVALUATION')
            if unresolved_splice_outcomes:
                audit[-1]['unresolvedSpliceOutcomes'] = unresolved_splice_outcomes
        if reasons:continue
        # Deduplicate reuse across policies only. Distinct outcomes for the same allele
        # remain separate so the core can reject ambiguity instead of selecting a winner.
        for outcome in splice_records:splice_outcomes.setdefault(content_hash(outcome),outcome)
        tx=sc['transcripts'];require(len(tx)==1,'EXACT_TRANSCRIPT_REQUIRED')
        source_ids=p['mechanism']['sourceIds'];sources=[s for s in p['scientificSources'] if s['sourceId'] in source_ids]
        require(source_ids and len(set(source_ids))==len(source_ids)==len(sources),'MECHANISM_SOURCE_BINDING_INVALID')
        entry={'entryId':p['policyId'],'scope':{k:sc[k] for k in ['hgncId','mondoId','moi','assembly']}|{'transcript':tx[0]['accession']+'.'+str(tx[0]['version'])},
          'sourceRecordSetSha256':content_hash(records),'policySha256':policy_sha,'modelSha256':p['transcriptModelBinding']['canonicalSha256'],
          'exonNmdScopeSha256':content_hash(p['nmdScope']),'mechanismStatus':'ESTABLISHED_LOSS_OF_FUNCTION','transcriptRelevance':'REVIEWED_RELEVANT',
          'purpose':auth['purpose'],'allowedDataClasses':auth['allowedDataClasses'],
          'mechanismEvidence':{'type':'REVIEWED_POLICY_ORIGINAL_SOURCES','policyReviewId':p['review']['reviewId'],'sourceIds':copy.deepcopy(source_ids),'sourceSetSha256':content_hash(sources)}}
        if qualified:
            knowledge={k:copy.deepcopy(p[k]) for k in ['mechanism', 'nmdScope']}
            knowledge['maximumStrength']=p['appliedStrength']
            for name in ['escapeScope', 'spliceScope', 'codingEditScope']:
                if name in p:knowledge[name]=copy.deepcopy(p[name])
            entry['qualification']={'method':'UNCHANGED_SOURCE_POLICY_CONDITIONS', 'originalPolicyReview':copy.deepcopy(p['review']),
                'sourceVerification':copy.deepcopy(m['sourceChecks']), 'reviewWitness':review_witness,
                'originalCandidateRawSha256':m['candidateBundleRawSha256'], 'knowledge':knowledge,
                'createsExpertReview':False, 'clinicalAcceptance':False}
            if splice_verification is not None:entry['qualification']['spliceVerification']=splice_verification
        else:
            entry['review']={'status':'REVIEWED_FOR_PVS1_NMD','reviewId':auth['bindingId'],'reviewedBy':auth['operator'],'reviewedAt':auth['boundAt'],'validUntil':auth['validUntil'],
                'basis':'Mechanical runtime binding of unchanged policy '+policy_sha+'; science authority remains original review '+p['review']['reviewId']+'. No new scientific review, no disease alias, synthetic research only.'}
        entries.append(entry)
    require(len({p['policyId'] for p in policies})==len(policies),'DUPLICATE_POLICY')
    for sid,source in source_by_id.items():
        source.pop('role',None)
        source['originalCatalogSourceRecords']=source_occurrences[sid]
    catalog={'schemaVersion':'acmg-specification-catalog-v1','catalogId':auth['bindingId'],'catalogVersion':'1.0.0','searchedAt':auth['boundAt'],
      'searchScope':'Exact union of pinned original catalogs. No new scientific search; original restrictions preserved.',
      'review':{'status':'REVIEWED','reviewId':auth['bindingId']},'sources':list(source_by_id.values()),'entries':list(entry_by_id.values())}
    bundle={'schemaVersion':'reviewed-rule-bundle-pvs1-v6','bundleId':auth['bindingId'],'bundleVersion':'1.0.0',
      'review':{'status':'REVIEWED_IMPLEMENTED','reviewId':auth['bindingId'],'scope':'Engineering source-to-runtime binding only. Original science reviews unchanged.'},
      'activation':{'automaticTrigger':False,'classificationEnabled':False,'adoptionSupported':False,'defaultEnabled':False},
      'catalog':catalog,'catalogSha256':content_hash(catalog),'policies':policies,'materials':{'transcriptModels':models,'pvs1ResourceProfile':copy.deepcopy(profile)}}
    if any(p.get('schemaVersion')=='pvs1-truncating-nmd-policy-v4' for p in policies):
        require(len(splice_outcomes)<=1024,'SPLICE_OUTCOME_LIMIT_EXCEEDED')
        bundle['materials']['pvs1SpliceOutcomes']=list(splice_outcomes.values())
    use={'schemaVersion':'pvs1-knowledge-adoption-v2','catalogId':auth['bindingId'],'catalogVersion':'1.0.0','sourceCandidatesSha256':facts_sha,'sourceDataClass':'PUBLIC_REFERENCE','entries':entries}
    if qualified:
        use.update(schemaVersion=QUALIFIED_SCHEMA, qualificationPolicy=copy.deepcopy(config['sourceQualification']))
    return bundle,use,{'schemaVersion':'pvs1-runtime-assembly-audit-v1','policies':audit,'boundPolicies':len(entries),'blockedPolicies':len(audit)-len(entries),'scientificPolicyChanges':False,'clinicalAcceptance':False}


def escape_review_gaps(refs, bundle, adoption):
    """Bind an unadopted draft for explanation only, outside the policy catalog."""
    require(isinstance(refs, list) and len(refs) <= 128, 'ESCAPE_REVIEW_CANDIDATE_LIMIT_INVALID')
    adopted = {entry['policySha256'] for entry in adoption['entries']}
    policies = {content_hash(policy): policy for policy in bundle['policies']}
    result = {}
    for ref in refs:
        require(set(ref) == {'file', 'sha256'}, 'ESCAPE_REVIEW_CANDIDATE_PIN_REQUIRED')
        original = parse(pinned(ref['file'], ref['sha256'], 4_000_000))
        require(original['schemaVersion'] == 'pvs1-truncating-nmd-bundle-candidate-v1'
                and len(original['policies']) == 1, 'ESCAPE_REVIEW_CANDIDATE_INVALID')
        draft, = original['policies']
        scope = draft['escapeScope']
        base_pin = original['derivation']['basePolicySha256']
        base = policies.get(base_pin)
        require(base is not None and base_pin in adopted and base_pin not in result
                and 'escapeScope' not in base
                and all(draft[key] == base[key] for key in ('scope', 'transcriptModelBinding',
                                                            'scientificSources', 'mechanism', 'nmdScope'))
                and draft['review']['status'] == scope['status'] == 'UNREVIEWED',
                'ESCAPE_REVIEW_CANDIDATE_SCOPE_MISMATCH')
        allowed_sources = {source['sourceId'] for source in base['scientificSources']}
        conditions = scope['positionConditions']
        source_ids = scope['sourceIds']
        require(isinstance(conditions, list) and 1 <= len(conditions) <= 16
                and isinstance(source_ids, list) and source_ids and set(source_ids) <= allowed_sources,
                'ESCAPE_REVIEW_CANDIDATE_SOURCES_INVALID')
        relevant = set(base['nmdScope']['relevantExonOrdinals'])
        exons = [row['ordinal'] for row in scope['exonAssessments'] if row['biologicalRelevance'] == 'PRESENT'
                 and row['populationLof'] == 'UNKNOWN']
        require(exons and len(exons) == len(set(exons)) and set(exons) <= relevant,
                'ESCAPE_REVIEW_CANDIDATE_EXONS_INVALID')
        result[base_pin] = {
            'status': 'UNREVIEWED', 'candidateRawSha256': ref['sha256'],
            'transcriptModelSha256': base['transcriptModelBinding']['canonicalSha256'],
            'positionConditions': conditions, 'sourceIds': source_ids,
            'exonAssessments': [dict(ordinal=exon, biologicalRelevance='PRESENT', populationLof='UNKNOWN')
                                for exon in exons],
        }
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--config',required=True);ap.add_argument('--config-sha256',required=True);ap.add_argument('--output-directory',required=True);a=ap.parse_args()
    config=parse(pinned(a.config,a.config_sha256))
    qualified_fields = {'schemaVersion', 'materialsConfig', 'resourceProfile', 'sourceQualification', 'release'}
    require((set(config)=={'schemaVersion','materialsConfig','resourceProfile','authorization'} and config['schemaVersion']=='pvs1-runtime-assembly-input-v1')
            or (qualified_fields <= set(config) <= qualified_fields | {'escapeReviewCandidates'}
                and config['schemaVersion']=='pvs1-source-qualified-assembly-input-v1'), 'CONFIG_INVALID')
    ref=config['materialsConfig'];source_config=parse(pinned(ref['file'],ref['sha256']))
    facts,_,_=load_sources(source_config);materials=historical_materials(source_config)
    ref=config['resourceProfile'];profile=parse(pinned(ref['file'],ref['sha256']))
    bundle,use,audit=assemble(config,facts,source_config['mechanisms']['sha256'],materials,profile)
    if 'escapeReviewCandidates' in config:
        gaps = escape_review_gaps(config['escapeReviewCandidates'], bundle, use)
        if gaps:
            bundle['materials']['pvs1EscapeReviewGaps'] = gaps
            audit['unadoptedEscapeReviewCandidates'] = {pin: row['candidateRawSha256'] for pin, row in gaps.items()}
    out=Path(a.output_directory);out.mkdir(parents=True,exist_ok=False)
    write_new(out/'bundle.json',bundle);write_new(out/'knowledge-adoption.json',use)
    audit['inputConfigRawSha256']=a.config_sha256;audit['bundleCanonicalSha256']=content_hash(bundle)
    write_new(out/'assembly-audit.json',audit)
    print({'boundPolicies':audit['boundPolicies'],'blockedPolicies':audit['blockedPolicies'],'newScientificApproval':False})


if __name__=='__main__':main()
