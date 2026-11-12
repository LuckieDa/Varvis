"""Automatic PM3 policy lookup from pinned, released CSpec originals.

The old BA1 inventory is only a source index. Its approvals and thresholds are
not inherited. Interpret only a complete explicit SVI delegation; retain other
clauses as unsupported, not as absent. No per-gene or per-variant policy list.
"""
from copy import deepcopy
from pathlib import Path
import base64
import re
import sys
from core_local_identity import pinned
from model_literature_contract import parse, digest, canonical

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'pvs1'))
from prepare_applicability_sources import load_specifications, scopes, pointer
from pm3_case_eligibility import (FAMILY as CASE_ELIGIBILITY, METHOD as PHENOTYPE_SCOPE, phenotype,
                                 prepare_case_context, published_rarity, reviewed_releases, transcript_bindings)

VERSION = 'pm3-specification-context-v2'
POLICY = 'CLINGEN_SVI_PM3_2019_V1'
SOURCE = 'https://www.clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf'
SOURCE_SHA256 = '17877e54edd58decfe2c374b3d3e2103e52752ea808d48eb7147d007f1ca4c70'
DELEGATION = 'Applicable as described in SVI recommendations for in trans criterion'
PHASE_DELEGATION = ('* Follow SVI guidance for PM3 ('
    '[https://clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf]'
    '(https://clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf)). '
    '* Parental testing, or another appropriate molecular method (such as cloning each allele separately followed by sequencing), '
    'must have been performed in order to confirm that the variants are in trans if the patient is compound heterozygous.')
APPROVED_PARTNER = ('Any additional variants used to support the classification of the variant under assessment MUST have been '
    'previously classified by the CCDS VCEP, using these criteria, and the classification MUST be approved. In other words, '
    'when awarding points for PM3 for a compound heterozygote, the second variant MUST have been assessed by the VCEP and '
    'an appropriate classification approved. As noted in the SVI guidance, circularity must be avoided. Variant A can support '
    'the classification of Variant B, or vice versa but NOT both.')


def normal(value):
    return ' '.join(value.split()) if isinstance(value, str) else ''


def program(original, clause):
    """Interpret complete known clause forms; a program is not source authority."""
    fields = {'_uniqueProp', 'additionalComments', 'instructionsToUse', 'applicability', 'baseStrength', 'defaultStrength',
              'disease', 'evidenceCategory', 'gene', 'geneType', 'label', 'ns', 'originalACMGSummary', 'sepioID',
              'specificationType', 'status', 'strengthDescriptor'}
    descriptor_fields = {'applicability', 'id', 'instructionsToUse', 'specificationType', 'status', 'strength',
                         'strengthSepioID', 'text', 'type'}
    descriptors = clause.get('strengthDescriptor', [])
    active = [d for d in descriptors if d.get('applicability') == 'Applicable']
    if (set(clause) - fields or any(set(d) - descriptor_fields for d in descriptors)
            or normal(clause.get('additionalComments'))
            or clause.get('baseStrength') != 'Pathogenic' or clause.get('defaultStrength') != 'Pathogenic Moderate'
            or len(active) != 4 or {d.get('strength') for d in active} != {'Supporting', 'Moderate', 'Strong', 'Very Strong'}
            or any(d.get('status') not in (None, 'approved') or normal(d.get('instructionsToUse')) for d in active)
            or any(d.get('applicability') not in {'Applicable', 'Not applicable', 'Not Applicable', 'Not Applicable for this VCEP'} for d in descriptors)
            or original.get('ld', {}).get('File')
            or re.search(r'\bPM3\b', normal(original.get('entContent', {}).get('notes')) + ' '
                         + normal(original.get('entContent', {}).get('releaseNotes')))):
        return None
    texts = {normal(d.get('text')) for d in active}
    instructions = normal(clause.get('instructionsToUse'))
    if texts == {DELEGATION} and not instructions:
        family = 'SVI_EXPLICIT_DELEGATION'
    elif texts == {PHASE_DELEGATION} and instructions in ('', APPROVED_PARTNER):
        family = 'SVI_PHASE_AND_APPROVED_PARTNER' if instructions else 'SVI_PHASE_METHOD'
    else:
        return None
    operators = ['SOURCE_SCOPE_AND_VERSION', 'SVI_PHASE_FACTS', 'EXISTING_SVI_MATHEMATICS']
    if family == 'SVI_PHASE_AND_APPROVED_PARTNER':
        operators.append('PARTNER_APPROVED_BY_SPECIFICATION_ISSUER')
    return {'schemaVersion': 'pm3-published-condition-program-v1', 'version': '1', 'family': family,
            'mathPolicy': POLICY, 'operators': operators, 'clinicalApproval': False}


def disposition(original, clause):
    """Finite grammar, not an LLM interpretation or a keyword implication."""
    if clause.get('applicability') in {'Not applicable', 'Not Applicable', 'Not Applicable for this VCEP'}:
        return 'NOT_APPLICABLE', 'PM3_PUBLISHED_CLAUSE_NOT_APPLICABLE'
    if clause.get('applicability') != 'Applicable':
        return 'UNRESOLVED', 'PM3_PUBLISHED_APPLICABILITY_UNRESOLVED'
    if program(original, clause) is None:
        return 'UNSUPPORTED', 'PM3_PUBLISHED_CONDITIONS_NOT_IMPLEMENTED'
    return 'SELECTED', 'PM3_PUBLISHED_FINITE_SVI_CONDITIONS'


def independent_mrs_clause(original, gene, disease):
    """Bind the exact released one-assay Moderate route and its PM2 prerequisite."""
    expected = ('3 points based on any combination of the following. Two or more data types are recommended '
        'to apply PP4_Moderate: * Elevated urine guanidinoacetate with or without low or low normal creatine (1 point). '
        '* Elevated plasma guanidinoacetate with or without low or low normal creatine (2 points). '
        '* Significantly decreased creatine peak in brain magnetic resonance spectroscopy with or without visible '
        f'guanidinoacetate peak (3 points). * {gene} enzyme activity <5% of normal (3 points). '
        '* Variant must meet PM2_Supporting for PP4 to apply at any strength.')
    found = []
    for index, clause in enumerate(original.get('ld', {}).get('CriteriaCode', [])):
        content = clause['entContent']
        if (content.get('label') != 'PP4' or content.get('applicability') != 'Applicable'
                or gene not in content.get('gene', []) or content.get('disease') not in (None, [], [disease])
                or any(normal(content.get(k)) for k in ('additionalComments', 'instructionsToUse'))):
            continue
        for j, desc in enumerate(content.get('strengthDescriptor', [])):
            if (desc.get('applicability') == 'Applicable' and desc.get('strength') == 'Moderate'
                    and not normal(desc.get('instructionsToUse'))
                    and normal(desc.get('text')).replace('\\', '') == expected):
                found.append({'clausePointer': f'/ld/CriteriaCode/{index}/entContent/strengthDescriptor/{j}',
                    'clauseSha256': digest(canonical(desc)), 'assay': 'BRAIN_MRS', 'requiredPartnerCriterion': 'PM2_Supporting'})
    return found[0] if len(found) == 1 else None


def joint_rarity_clause(original, gene, disease):
    """Recognize the complete all-populations observed-AF and homozygote rule."""
    expression = re.compile(
        r'\*\*Allele frequency <(?P<threshold>0\.[0-9]+) \(<0\.[0-9]+%\) in all populations in gnomAD\.\*\* '
        r'It is acceptable for a '+re.escape(gene)+r' variant to be present in controls, if heterozygous, because [A-Z0-9-]+ is a recessive disorder\. '
        r'Homozygotes should not be seen in a population database, such as gnomAD, because the penetrance of this condition in individuals '
        r'with biallelic pathogenic variants is expected to be 100% and the condition is expected to present with severe symptoms early in life\. '
        +re.escape(gene)+r' specifications: \* All subpopulations in gnomAD v4\.0 must have a maximum allele frequency less than (?P=threshold) '
        r'\(the highest population minor allele frequency of the most common pathogenic '+re.escape(gene)+r' variant, c\.[0-9]+[ACGT]>[ACGT], in gnomAD\)\. '
        r'Any variant with a frequency below this cutoff will meet PM2_Supporting\. '
        r'\* If homozygotes are observed, or variant is confirmed in trans with a known pathogenic variant, the variant will meet BS2 '
        r'\(assuming 100% penetrance for an individual with 2 pathogenic variants in trans\)\.')
    found = []
    for i, clause in enumerate(original.get('ld', {}).get('CriteriaCode', [])):
        content = clause['entContent']
        if (content.get('label') != 'PM2' or content.get('applicability') != 'Applicable'
                or gene not in content.get('gene', []) or content.get('disease') not in (None, [], [disease])
                or normal(content.get('additionalComments'))):
            continue
        for j, desc in enumerate(content.get('strengthDescriptor', [])):
            if (desc.get('applicability') == 'Applicable' and desc.get('strength') == 'Supporting'
                    and not normal(desc.get('instructionsToUse'))
                    and (condition := expression.fullmatch(normal(desc.get('text')).replace('\\', '')))):
                found.append({'clausePointer': f'/ld/CriteriaCode/{i}/entContent/strengthDescriptor/{j}',
                    'clauseSha256': digest(canonical(desc)), 'maximumAlleleFrequency': condition['threshold'],
                    'method': 'ALL_JOINT_ANCESTRIES_STRICT_OBSERVED_AF_NO_HOMOZYGOTES'})
    return found[0] if len(found) == 1 else None


class SpecificationContext:
    def __init__(self, root, config):
        self.root = Path(root)
        raw = pinned(root, config['catalog'], 32 * 1024 * 1024)
        name = config['captureDirectory']
        capture = (root / name).resolve(strict=True)
        if Path(name).is_absolute() or not capture.is_relative_to(root / 'local'):
            raise ValueError('PM3_SPECIFICATION_CAPTURE_OUTSIDE_LOCAL')
        catalog = parse(raw)
        self.specifications, _ = load_specifications(catalog, capture)
        self.capture = capture
        self.reviewed_releases, self.reviewed_release_issues = reviewed_releases(root, config.get('reviewedReleaseIndex'))
        # Replace the same release only. Another version or overlapping
        # specification still participates in ordinary conflict detection.
        self.specifications = [self.reviewed_releases.get(s['specificationKey'], s) for s in self.specifications]
        known = {s['specificationKey'] for s in self.specifications}
        self.specifications.extend(s for k, s in self.reviewed_releases.items() if k not in known)
        self.method_documents = []
        documents = config.get('assertionMethodDocuments', [])
        if not isinstance(documents, list) or len(documents) > 32:
            raise ValueError('PM3_ASSERTION_METHOD_DOCUMENT_LIMIT')
        for item in documents:
            spec = next((s for s in self.specifications if s['specificationKey'] == item['specificationKey']), None)
            if (spec is None or item.get('documentDoi') != spec['original']['entContent']['doi']['docDoi']
                    or not re.fullmatch(r'https://submit\.ncbi\.nlm\.nih\.gov/api/2\.0/files/[a-z0-9]+/[A-Za-z0-9_.-]+\.pdf/\?format=attachment', item['url'])
                    or not re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}', item['version'])):
                raise ValueError('PM3_ASSERTION_METHOD_DOCUMENT_BINDING_INVALID')
            document = pinned(root, item, 2*1024*1024)
            if not document.startswith(b'%PDF-') or not document.rstrip().endswith(b'%%EOF'):
                raise ValueError('PM3_ASSERTION_METHOD_PDF_REQUIRED')
            proof = {k: item[k] for k in ('specificationKey', 'documentDoi', 'url', 'sha256', 'version')}
            self.method_documents.append((proof, document))
        self.source = {'catalogRawSha256': digest(raw), 'snapshotId': catalog['metadata']['snapshotId'],
                       'snapshotGeneratedAt': catalog['metadata']['generatedAt'],
                       'scope': 'PINNED_RELEASED_LOCAL_SNAPSHOT', 'worldwideAbsenceClaim': False,
                       'priorBa1ApprovalInherited': False}

    def select(self, row, case=None, resolver=None):
        identity = row.get('identities', {}).get('target', {})
        disease = row.get('diseaseContext', {})
        out = {'schemaVersion': VERSION, 'status': 'UNRESOLVED', 'reasonCode': 'PM3_POLICY_CONTEXT_UNRESOLVED',
               'policy': None, 'generalPolicySource': SOURCE, 'source': deepcopy(self.source),
               'clinicalApproval': False, 'matches': [], 'businessPublishable': False}
        phenotype_scope = disease.get('scopeBasis') == PHENOTYPE_SCOPE
        if (identity.get('status') != 'LOCAL_IDENTITY_VERIFIED' or
                (disease.get('qualification') != 'VERIFIED' and not phenotype_scope)
                or disease.get('inheritance') != 'AR' or not re.fullmatch(r'MONDO:[0-9]{7}', disease.get('diseaseId', ''))
                or not re.fullmatch(r'HGNC:[1-9][0-9]*', identity.get('hgncId', ''))):
            return out
        out['context'] = {'hgncId': identity['hgncId'], 'geneSymbol': identity['geneSymbol'],
                          'diseaseId': disease['diseaseId'], 'inheritance': 'AR'}
        subtype_ids=set(disease.get('reportedDiseaseIds',[]))-{disease['diseaseId']}
        if disease.get('diseaseScopeMapping') and subtype_ids:
            # A named child specification must not disappear when the qualified
            # gene relationship is expressed at its parent disease scope.
            for spec in self.specifications:
                for scope in scopes(spec['original']):
                    if (scope['scope']['mondoId'] in subtype_ids and
                            (scope['scope']['hgncId']==identity['hgncId'] or scope['geneSymbol']==identity['geneSymbol'])):
                        out['matches'].append({'specificationKey':spec['specificationKey'],
                            'source':deepcopy(spec['source']),'scope':deepcopy(scope['scope']),
                            'status':'UNSUPPORTED','reasonCode':'PM3_SUBTYPE_SPECIFICATION_REQUIRES_EXACT_CONTEXT'})
            if out['matches']:
                out.update(status='UNSUPPORTED',reasonCode='PM3_SUBTYPE_SPECIFICATION_REQUIRES_EXACT_CONTEXT')
                return out
        for spec in self.specifications:
            original = spec['original']
            associations = scopes(original)
            release_issue = getattr(self, 'reviewed_release_issues', {}).get(spec['specificationKey'])
            named_gene = any(identity['geneSymbol'] in c.get('entContent', {}).get('gene', [])
                             for c in original.get('ld', {}).get('CriteriaCode', []))
            if named_gene and not any(a['geneSymbol'] == identity['geneSymbol'] for a in associations):
                out['matches'].append({'specificationKey': spec['specificationKey'], 'source': deepcopy(spec['source']),
                    'status': 'UNRESOLVED', 'reasonCode': 'PM3_PUBLISHED_SCOPE_UNRESOLVED'})
            for scope in associations:
                s = scope['scope']
                if s['hgncId'] != identity['hgncId'] and scope['geneSymbol'] != identity['geneSymbol']:
                    continue
                if s['mondoId'] and s['mondoId'] != disease['diseaseId']:
                    continue
                # Missing source MOI can be supplied by the separately qualified
                # exact ClinGen gene/disease relationship; explicit contradictions cannot.
                association = pointer(original, scope['scopePointer'])
                modes = association.get('modesOfInheritance', [])
                mode_conflict = any(m.get('modeOfInheritance', '').strip() not in
                                   {'Autosomal recessive inheritance', 'Autosomal Recessive'} for m in modes)
                resolved_scope = (s['hgncId'] == identity['hgncId'] and scope['geneSymbol'] == identity['geneSymbol']
                                  and s['mondoId'] == disease['diseaseId'] and
                                  (s['moi'] == 'AUTOSOMAL_RECESSIVE' or scope['scopeStatus'] == 'INHERITANCE_NOT_PROVIDED')
                                  and not mode_conflict)
                clauses = [(i, c) for i, c in enumerate(original.get('ld', {}).get('CriteriaCode', []))
                           if c.get('entContent', {}).get('label') == 'PM3']
                matched = False
                for index, clause in clauses:
                    c = clause['entContent']
                    if identity['geneSymbol'] not in c.get('gene', []):
                        continue
                    ds = c.get('disease')
                    if isinstance(ds, list) and ds and disease['diseaseId'] not in ds:
                        continue
                    matched = True
                    reviewed = spec.get('reviewedCaseRule')
                    uses_case_conditions = reviewed is not None and reviewed['pm3Pointer'] == f'/ld/CriteriaCode/{index}'
                    if uses_case_conditions:
                        executable = {'schemaVersion': 'pm3-published-condition-program-v1', 'version': '1',
                                      'family': CASE_ELIGIBILITY, 'mathPolicy': POLICY, 'clinicalApproval': False,
                                      'operators': ['SOURCE_SCOPE_AND_VERSION', 'SVI_PHASE_FACTS', 'EXISTING_SVI_MATHEMATICS',
                                                    'SOURCE_CASE_PHENOTYPE', reviewed['rarity'], reviewed['classification']]}
                        status, reason = 'SELECTED', 'PM3_PUBLISHED_RELEASED_CASE_ELIGIBILITY'
                    else:
                        executable = program(original, c)
                        status, reason = disposition(original, c)
                    # If the source restricts transcripts, require its exact version.
                    gene = scope['rawGeneAssociation']
                    transcripts = set(gene.get('transcripts', [])) | ({gene['preferredTranscript']} if gene.get('preferredTranscript') else set())
                    binding = transcript_bindings(row, scope, resolver) if uses_case_conditions and case is not None else None
                    if not resolved_scope or ds is not None and not isinstance(ds, list):
                        status, reason = 'UNRESOLVED', 'PM3_PUBLISHED_SCOPE_UNRESOLVED'
                    elif release_issue and status == 'UNSUPPORTED':
                        status, reason = 'UNRESOLVED', release_issue
                    elif ((uses_case_conditions and not binding) or
                            (not uses_case_conditions and transcripts and identity.get('transcript') not in transcripts)):
                        status, reason = 'UNRESOLVED', 'PM3_PUBLISHED_TRANSCRIPT_SCOPE_UNRESOLVED'
                    match = {'specificationKey': spec['specificationKey'], 'source': deepcopy(spec['source']),
                        'scopePointer': scope['scopePointer'], 'scope': deepcopy(s),
                        'inheritanceSupply': 'EXACT_QUALIFIED_GENE_DISEASE_RELATIONSHIP' if not s['moi'] else 'SPECIFICATION',
                        'clausePointer': f'/ld/CriteriaCode/{index}', 'clauseSha256': digest(canonical(clause)),
                        'clause': deepcopy(clause), 'program': executable, 'status': status, 'reasonCode': reason}
                    if uses_case_conditions and case is not None:
                        match['reviewedCaseRule'] = deepcopy(reviewed)
                        match['reviewedAttachments'] = deepcopy(spec['reviewedAttachments'])
                        row['publishedPhenotype'] = phenotype(case, row['reportedObservation'])
                        if binding:
                            row['publishedTranscriptBinding'] = binding
                        if status == 'SELECTED':
                            match['jointRarity'] = published_rarity(spec)
                    if status == 'SELECTED' and match['program']['family'] == 'SVI_PHASE_AND_APPROVED_PARTNER':
                        mrs = independent_mrs_clause(original, identity['geneSymbol'], disease['diseaseId'])
                        if mrs is not None:
                            match['independentPp4'] = mrs
                            rarity = joint_rarity_clause(original, identity['geneSymbol'], disease['diseaseId'])
                            if rarity is not None:
                                match['jointRarity'] = rarity
                    out['matches'].append(match)
                if not matched:
                    out['matches'].append({'specificationKey': spec['specificationKey'], 'source': deepcopy(spec['source']),
                        'scopePointer': scope['scopePointer'], 'status': 'UNRESOLVED',
                        'reasonCode': 'PM3_MATCHING_SPECIFICATION_CLAUSE_MISSING'})
        matches = out['matches']
        if not matches and phenotype_scope:
            return out
        if not matches:
            # This is the source-reviewed fixed SVI implementation, not an
            # inference that a partial local catalog proves worldwide absence.
            # An applicable but unread/unsupported specification above never
            # takes this branch.
            out.update(status='SELECTED', reasonCode='PM3_GENERAL_WITH_NO_MATCH_IN_LOCAL_SNAPSHOT', policy=POLICY,
                       generalPolicy={'policy': POLICY, 'version': '1.0', 'sourceUrl': SOURCE,
                                      'sourceRawSha256': SOURCE_SHA256,
                                      'basis': 'SOURCE_REVIEWED_FIXED_SVI_IMPLEMENTATION'})
        elif len(matches) != 1:
            out.update(status='CONFLICT', reasonCode='PM3_MULTIPLE_APPLICABLE_SPECIFICATIONS')
        else:
            out.update(status=matches[0]['status'], reasonCode=matches[0]['reasonCode'])
            if out['status'] == 'SELECTED':
                out['policy'] = POLICY
        return out

    def attach(self, observations, disease_catalog=None, resolver=None):
        originals = observations.setdefault('specificationOriginals', {})
        for case in observations.get('cases', []):
            for row in case.get('resolvedObservations', []):
                prepare_case_context(row, case, self.specifications, disease_catalog,
                                     getattr(self, 'reviewed_release_issues', {}))
                row['policySelection'] = self.select(row, case, resolver)
                # Full registered RAW collections are deduplicated once per paper,
                # not copied into every observation or public explanation.
                for match in row['policySelection']['matches']:
                    if match.get('status') not in {'SELECTED', 'NOT_APPLICABLE'} or not hasattr(self, 'capture'):
                        continue
                    source = match['source']; pin = source['rawSha256']
                    reviewed = getattr(self, 'reviewed_releases', {}).get(match['specificationKey'])
                    if reviewed is not None and pin == reviewed['source']['rawSha256']:
                        if pin not in originals:
                            originals[pin] = base64.b64encode(reviewed['originalBytes']).decode('ascii')
                        bodies = observations.setdefault('specificationAttachmentOriginals', {})
                        for sha, body in reviewed['attachmentBytes'].items():
                            if sha not in bodies:
                                bodies[sha] = base64.b64encode(body).decode('ascii')
                        continue
                    if pin not in originals:
                        path = (self.capture / source['rawRef']).resolve(strict=True)
                        if not path.is_relative_to(self.capture) or path.stat().st_size > 8_000_000:
                            raise ValueError('PM3_SPECIFICATION_ORIGINAL_PATH_INVALID')
                        raw = path.read_bytes()
                        if digest(raw) != pin:
                            raise ValueError('PM3_SPECIFICATION_ORIGINAL_PIN_MISMATCH')
                        originals[pin] = base64.b64encode(raw).decode('ascii')
                    for proof, raw in getattr(self, 'method_documents', ()):
                        if proof['specificationKey'] != match['specificationKey']:
                            continue
                        refs = observations.setdefault('specificationMethodDocuments', [])
                        if proof not in refs:
                            refs.append(deepcopy(proof))
                        observations.setdefault('specificationMethodOriginals', {})[proof['sha256']] = base64.b64encode(raw).decode('ascii')
