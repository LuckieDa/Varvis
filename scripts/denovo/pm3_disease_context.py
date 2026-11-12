"""Match source-verified case diagnoses to an existing pinned ClinGen catalog.

Uses the existing relationship intake scope, not gene-to-patient diagnosis or
new PM3-specific per-gene knowledge. Exact labels/IDs, with optional pinned
direct-subclass relationships kept distinct from equivalence; no fuzzy diagnosis.
"""
from core_local_identity import pinned
from model_literature_contract import parse,digest

class DiseaseContext:
    def __init__(self,root,spec):
        manifest_raw=pinned(root,spec['manifest'],65536);manifest=parse(manifest_raw)
        raw=pinned(root,spec['catalog'],4*1024*1024);rows=parse(raw)
        identity=manifest.get('resources',{}).get('clingen',{})
        if (manifest.get('profileId')!='PATIENT_DISEASE_BINDING_INTAKE' or manifest.get('profileVersion')!='1.0.0'
                or identity.get('resourceId')!='CLINGEN_GENE_DISEASE_VALIDITY'
                or identity.get('sourceUrl')!='https://search.clinicalgenome.org/kb/gene-validity/download'
                or identity.get('derivedSha256')!=digest(raw) or not isinstance(rows,list)
                or len(rows)>10000 or identity.get('derivedRecordCount')!=len(rows)):
            raise ValueError('PM3_DISEASE_CATALOG_BINDING_INVALID')
        self.rows=rows;self.source={'manifestSha256':digest(manifest_raw),'catalogSha256':digest(raw),'identity':identity}
        self.subclasses=None
        if spec.get("directSubclassContext") is not None:
            from pm3_mondo_context import DirectSubclassContext
            self.subclasses=DirectSubclassContext(root,spec["directSubclassContext"])
        self.names=None; names=[]
        if spec.get('exactDiseaseMappings') is not None:
            from pm3_disease_names import ExactDiseaseNames
            names.append(ExactDiseaseNames(root,spec['exactDiseaseMappings']))
        if spec.get('exactMondoTerms') is not None:
            from pm3_disease_names import ExactMondoTerms
            names.append(ExactMondoTerms(root,spec['exactMondoTerms']))
        if names:
            from pm3_disease_names import CombinedDiseaseNames
            self.names=CombinedDiseaseNames(names)

    def qualify(self,observation,identity):
        out={'qualification':'UNKNOWN','meaning':'LITERATURE_CASE_CONTEXT_NOT_PATIENT_DIAGNOSIS',
             'source':self.source,'reportedDiagnoses':observation.get('reportedDiagnoses',[]),
             'reportedAffectedStatus':observation.get('reportedAffectedStatus','UNKNOWN'),
             'businessPublishable':False,'reasonCode':'PM3_CASE_DIAGNOSIS_NOT_MATCHED'}
        diagnoses={v.strip().casefold() for v in out['reportedDiagnoses']}
        proofs=[p for v in out['reportedDiagnoses'] for p in self.names.lookup(v)] if self.names else []
        out['diseaseNameMapping']={'status':'CHECKED' if self.names else 'NOT_CONFIGURED','matches':proofs,
                                  'scope':'EXACT_NAMES_AND_IDENTIFIERS_ONLY_NOT_BROADER_OR_NARROWER'}
        subtype_proofs=[p for v in out['reportedDiagnoses'] for p in self.subclasses.lookup(v)] if self.subclasses else []
        out['reportedDiseaseIds']=sorted({p['reportedDiseaseId'] for p in subtype_proofs})
        if subtype_proofs:
            out['diseaseTermMapping']={'scope':'PINNED_EXACT_LABEL_OR_EXACT_SYNONYM',
                                      'matches':subtype_proofs}
        # Resolve name ambiguity globally, not by selecting the convenient gene.
        for value in out['reportedDiagnoses']:
            ids={p['mapping']['subject_id'] for p in self.names.lookup(value)} if self.names else set()
            ids.update(r['mondoId'] for r in self.rows if r.get('diseaseLabel','').casefold()==value.strip().casefold())
            if self.subclasses:
                ids.update(p['reportedDiseaseId'] for p in self.subclasses.lookup(value))
            if len(ids)>1:return {**out,'qualification':'CONFLICT','reasonCode':'PM3_CASE_DISEASE_NAME_AMBIGUOUS'}
        diagnoses.update(p['mapping']['subject_id'].casefold() for p in proofs)
        diagnoses.update(p['reportedDiseaseId'].casefold() for p in subtype_proofs)
        matched=[r for r in self.rows if r.get('hgncId')==identity.get('hgncId') and r.get('symbol')==identity.get('geneSymbol')
                 and (r.get('mondoId','').casefold() in diagnoses or r.get('diseaseLabel','').casefold() in diagnoses)]
        # A child remains a child, not an alias for its parent. Only a direct
        # published subclass edge may supply a broader existing context, and
        # only when no exact relationship is available. Never walk ancestors.
        if not matched and subtype_proofs:
            parent_ids={ident for p in subtype_proofs for ident in p['parentDiseaseIds']}
            matched=[r for r in self.rows if r.get('hgncId')==identity.get('hgncId')
                     and r.get('symbol')==identity.get('geneSymbol') and r.get('mondoId') in parent_ids]
            out['diseaseScopeMapping']={'method':'DIRECT_SUBCLASS_TO_EXISTING_GENE_DISEASE_CONTEXT',
                'equivalent':False,'matches':subtype_proofs,'clinicalApproval':False}
        out['matchedRelationships']=matched
        if not matched:return out
        if len({r.get('mondoId') for r in matched})!=1:
            return {**out,'qualification':'CONFLICT','reasonCode':'PM3_CASE_DISEASE_AMBIGUOUS'}
        if any(r.get('classification') not in {'Definitive','Strong','Moderate'} for r in matched):
            return {**out,'reasonCode':'PM3_GENE_DISEASE_RELATIONSHIP_NOT_QUALIFIED'}
        if {r.get('moi') for r in matched}!={'AR'}:
            return {**out,'reasonCode':'PM3_RECESSIVE_CONTEXT_NOT_ESTABLISHED'}
        affected=out['reportedAffectedStatus']
        if affected!='AFFECTED':
            return {**out,'qualification':'CONFLICT' if affected=='CONFLICT' else 'FAILED' if affected=='UNAFFECTED' else 'UNKNOWN',
                    'reasonCode':'PM3_CASE_NOT_AFFECTED' if affected=='UNAFFECTED' else 'PM3_AFFECTED_STATUS_NOT_ESTABLISHED'}
        return {**out,'qualification':'VERIFIED','reasonCode':'PM3_EXPLICIT_AFFECTED_AR_CASE_CONTEXT',
                'diseaseId':matched[0]['mondoId'],'inheritance':'AR'}

    def attach(self,observations):
        for case in observations.get('cases',[]):
            for row in case.get('resolvedObservations',[]):
                identity=row.get('identities',{}).get('target')
                if identity is not None:row['diseaseContext']=self.qualify(row['reportedObservation'],identity)
