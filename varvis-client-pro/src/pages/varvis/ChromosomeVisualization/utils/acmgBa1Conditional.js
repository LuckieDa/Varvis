// Consistency checks for a saved conditional BA1 result. No population lookup,
// threshold evaluation, material issuance or scientific qualification occurs here.
import { populationRawRecordSha256, populationRegionBindingValid } from './acmgSequencePopulation.js';
const contract='a01-ba1-faf-or-individual-count-v1';
const object=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const truth=v=>['TRUE','FALSE','UNKNOWN'].includes(v);
const sha=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const list=v=>Array.isArray(v)?v:[];
const check=(value)=>{if(!value)throw Error('BA1_CONDITIONAL_SAVED_BINDING_INVALID');};
const nativeContracts={
    '1.0.0':{scope:'GNOMAD_JOINT_4_1_ADJ_DIPLOID_HOMOZYGOUS_LOWER_BOUND',
        identity:'836aa4500815b7759140fb28e95b25a82be78420969427027578a409cb433c98',engine:'2.3.0-catalog-ba1-native-count'},
    '2.0.0':{scope:'GNOMAD_JOINT_4_1_SOURCE_BOUND_INDIVIDUAL_COUNT_BOUNDS',
        identity:'3b99429f172120671060995b3aa7db340b83baddfc3d242c78875bcca2170745',engine:'2.4.0-catalog-ba1-native-bounds'},
};
const count=value=>typeof value==='string'&&/^[0-9]{1,64}$/.test(value);
const sameCount=(a,b)=>count(a)&&count(b)&&BigInt(a)===BigInt(b);

function inspectNativeBounds(material,statistic,evidence,rawRecord,runtime,evaluation,condition,hash){
    const native=runtime.nativeIndividualCounts,b=material.binding,known=nativeContracts[material.version];
    const same=(a,c)=>hash(a)===hash(c);
    check(known&&native?.version===material.version&&native.schemaVersion==='ba1-native-individual-count-supply-v1'
        &&material.schemaVersion==='ba1-native-individual-count-material-v1'&&native.qualificationScope===known.scope
        &&material.qualificationScope===known.scope&&hash(native)===known.identity&&same(material.sourceContract,native)
        &&runtime.conditionalConsumerVersion===known.engine&&object(b)
        &&b.profileSha256===runtime.populationInputProfileSha256&&b.alleleId===evaluation.alleleId
        &&b.carrierSha256===hash(evidence)&&b.supplyIdentitySha256===known.identity
        &&same(b.queryAllele,evidence.queryAllele)&&same(b.source,evidence.source)
        &&b.producedAt===evidence.producedAt&&b.producedAt===evidence.source?.retrievedAt);
    if(material.version==='1.0.0'){
        if(material.status==='UNRESOLVED'){
            check(condition.countTruth==='UNKNOWN'&&statistic?.qualificationStatus!=='QUALIFIED_LOWER_BOUND');
            return;
        }
        check(material.status==='QUALIFIED_LOWER_BOUND'&&statistic?.qualificationStatus==='QUALIFIED_LOWER_BOUND'
            &&statistic.unit==='individuals'&&statistic.metricId==='gnomad.joint.nhomalt'
            &&sameCount(statistic.rawValue,material.observed?.homozygotes));
        return;
    }
    check(['QUALIFIED_BOUNDS','QUALIFIED_UPPER_BOUND','QUALIFIED_LOWER_BOUND','UNRESOLVED'].includes(material.status)
        &&b.dataset==='JOINT'&&b.release==='4.1'&&b.ancestry==='ALL'&&b.sex==='ALL'&&b.genotypeQualityScope==='ADJ'
        &&b.ploidyScope==='SOURCE_DIPLOID_HOMO_AND_QUALIFIED_XY_NONPAR_HAPLOID'
        &&b.countScopeCompleteness==='UNVERIFIED_OR_PARTIAL'&&material.countScopeCompleteness==='UNVERIFIED_OR_PARTIAL'
        &&condition.countScopeCompleteness==='UNVERIFIED_OR_PARTIAL'
        &&material.materialId==='ba1-native-count-material-sha256-'+hash(Object.fromEntries(Object.entries(material).filter(([key])=>key!=='materialId'))));
    const raw=rawRecord===undefined&&evidence.queryOutcome?.status!=='HIT'?'':rawRecord;
    check(typeof raw==='string'&&b.rawRecordSha256===populationRawRecordSha256(raw));
    const region=b.region;
    check(region?.schemaVersion==='sex-chromosome-region-v1'&&region.methodVersion==='1.0.0'
        &&region.sourceCatalogSha256==='41b99cb587f84e0f9823a7994ea29573a3f6aad619ebe02d011e02b964b820aa'
        &&same(region.queryAllele,evidence.queryAllele)&&region.queryAlleleSha256===hash(evidence.queryAllele)
        &&region.patientPloidyInferred===false&&region.clinicalQualificationGranted===false);
    if(region.status==='RESOLVED'){
        const ranges={X:[[10001,2781479],[155701383,156030895]],Y:[[10001,2781479],[56887903,57217415]]};
        check(populationRegionBindingValid(region,evidence.queryAllele)
            &&same(list(region.referenceRegions).map(p=>[p.start,p.end]),ranges[region.resolvedContig])
            &&region.sourceVersion==='GRCh38.p14'&&region.assemblyAccession==='GCF_000001405.40');
    }else check(region.status==='UNRESOLVED'&&region.region==='UNKNOWN'||region.status==='NOT_APPLICABLE'
        &&region.region==='AUTOSOMAL'&&/^(?:[1-9]|1[0-9]|2[0-2])$/.test(region.resolvedContig||''));
    const parameter=(name,value)=>{
        const rows=list(evaluation.deterministicTrace?.parameters).filter(p=>p.name===name);
        check(rows.length===1&&rows[0].value===value);
    };
    parameter('ba1.condition.nativeCountMaterialId',material.materialId);
    for(const name of ['frequencyTruth','countTruth','countScopeCompleteness'])parameter('ba1.condition.'+name,condition[name]);
    const bounds=material.additionalNativeBounds,observed=material.observed;
    if(!object(bounds)){
        check(material.status==='UNRESOLVED'&&condition.countTruth==='UNKNOWN');
        return;
    }
    const columns=raw.split('\t'),query=evidence.queryAllele;
    check(columns.length>=8&&!/[\r\n]/.test(raw)&&columns[0].replace(/^chr/,'')===query.contig
        &&columns[1]===String(query.position)&&columns[3]===query.reference);
    const alts=columns[4].split(','),index=alts.indexOf(query.alternate),info=new Map();
    check(index>=0&&alts.lastIndexOf(query.alternate)===index);
    for(const field of columns[7].split(';')){
        const at=field.indexOf('='),key=at<0?field:field.slice(0,at);
        check(key&&!info.has(key));info.set(key,at<0?'true':field.slice(at+1));
    }
    const readings=list(bounds.readings),ids=['AC_joint_raw','AN_joint_raw','AF_joint_raw','AC_joint_XY','AN_joint_XY','AF_joint_XY','nhomalt_joint_XY'];
    check([3,7].includes(readings.length)&&new Set(readings.map(r=>r.infoId)).size===readings.length);
    for(const [i,r]of readings.entries()){
        check(r.infoId===ids[i]&&r.unit===(r.infoId.startsWith('AF_')?'fraction':r.infoId.startsWith('nhomalt')?'individuals':'alleles'));
        const field=info.get(r.infoId),items=field?.split(',');
        check(!items||field==='.'||items.length===(r.infoId.startsWith('AN_')?1:alts.length));
        const value=items?.[r.infoId.startsWith('AN_')?0:index];
        check(value===undefined||value==='.'?r.status==='MISSING'&&!Object.hasOwn(r,'rawValue'):r.status==='PRESENT'&&r.rawValue===value);
    }
    const reading=id=>readings.find(r=>r.infoId===id),hom=observed?.homozygotes;
    check(observed?.metricId==='gnomad.joint.nhomalt'&&observed.infoId==='nhomalt_joint'&&observed.unit==='individuals'
        &&statistic?.metricId==='gnomad.joint.nhomalt'&&statistic.unit==='individuals');
    for(const [field,id]of [['AC','gnomad.joint.ac'],['AN','gnomad.joint.an']]){
        const metrics=list(evidence.measurements).filter(m=>m.metricId===id);
        check(metrics.length===1&&metrics[0].unit==='alleles'&&sameCount(observed[field],metrics[0].rawValue));
    }
    if(hom!==undefined){
        const metrics=list(evidence.measurements).filter(m=>m.metricId==='gnomad.joint.nhomalt');
        check(metrics.length===1&&metrics[0].unit==='individuals'&&sameCount(hom,metrics[0].rawValue)
            &&sameCount(hom,statistic?.rawValue)&&statistic.qualificationStatus==='QUALIFIED_LOWER_BOUND');
    }else check(statistic?.qualificationStatus!=='QUALIFIED_LOWER_BOUND');
    const hemi=bounds.hemizygoteLowerBound,upper=bounds.personUpperBound;
    const hasHemi=hemi?.status==='QUALIFIED_LOWER_BOUND',hasUpper=upper?.status==='QUALIFIED_UPPER_BOUND';
    check(['UNAVAILABLE','QUALIFIED_LOWER_BOUND'].includes(hemi?.status)&&hemi.scope===native.hemizygoteLowerBoundScope
        &&hemi.unit==='individuals'&&hemi.completeHemizygoteClass===false
        &&['UNAVAILABLE','QUALIFIED_UPPER_BOUND'].includes(upper?.status)&&upper.scope===native.personUpperBoundScope
        &&upper.countUnit==='individuals'&&upper.sourceMetric==='AC_joint_raw'&&upper.sourceUnit==='alleles'&&upper.exactPersonCount===false);
    if(hasHemi)check(region.status==='RESOLVED'&&region.region==='NON_PAR'&&populationRegionBindingValid(region,query)
        &&['X','Y'].includes(query.contig)&&list(region.referenceRegions).length>0
        &&region.referenceRegions.every(p=>query.position<p.start||query.position>p.end)
        &&['AC_joint_XY','AN_joint_XY','AF_joint_XY','nhomalt_joint_XY'].every(id=>reading(id)?.status==='PRESENT')
        &&sameCount(hemi.value,reading('AC_joint_XY').rawValue)&&sameCount(hemi.value,observed?.hemizygoteLowerBound)
        &&sameCount(reading('nhomalt_joint_XY').rawValue,'0'));
    else check(observed?.hemizygoteLowerBound===undefined&&!Object.hasOwn(hemi,'value'));
    if(hasUpper)check(['AC_joint_raw','AN_joint_raw','AF_joint_raw'].every(id=>reading(id)?.status==='PRESENT')
        &&sameCount(upper.value,reading('AC_joint_raw').rawValue));
    else check(!Object.hasOwn(upper,'value'));
    const hasLower=hom!==undefined||hasHemi;
    check(material.status===(hasLower?hasUpper?'QUALIFIED_BOUNDS':'QUALIFIED_LOWER_BOUND':hasUpper?'QUALIFIED_UPPER_BOUND':'UNRESOLVED')
        &&(condition.countTruth!=='TRUE'||hasLower)&&(condition.countTruth!=='FALSE'||hasUpper));
}
const checkedViews=new WeakMap();
export function validatedBa1ConditionalEntry(entry,identityHash){
    const known=entry?.ba1ConditionView&&checkedViews.get(entry.ba1ConditionView);
    return Boolean(known&&known.evaluation===entry.evaluation&&known.evaluationSha===identityHash(entry.evaluation));
}

export function inspectBa1Conditional({evaluation,selection,policy,evidence,rawRecord,context,runtimeBinding},identityHash){
    if(selection?.status!=='CONDITIONAL_SELECTED')return {claimed:false,valid:true};
    try{
        const e=evaluation,c=e?.ruleConditionEvaluation,expression=policy?.clause?.expression;
        check(typeof identityHash==='function'&&object(e)&&object(c)&&object(expression)&&object(context));
        check(policy.conditionRuntimeContract===contract&&policy.clause.semanticFamily==='FAF95_OR_HOMO_HEMIZYGOTE_COUNT_V1'
            &&expression.operator==='ANY'&&c.schemaVersion==='ba1-condition-evaluation-v1'&&c.contractVersion===contract
            &&e.ruleBinding?.conditionRuntimeContract===contract&&runtimeBinding?.catalog?.conditionalFrequencyContract===contract);
        check(sha(selection.catalogSha256)&&selection.catalogSha256===runtimeBinding.catalog.catalogSha256
            &&typeof selection.selectionId==='string'&&selection.selectionId===e.ruleBinding.ba1SelectionId
            &&selection.contextId===context.contextId&&e.alleleId===context.variant?.alleleId
            &&e.criterionId==='ACMG_AMP_2015:BA1'&&sha(runtimeBinding.populationInputProfileSha256));
        check(typeof runtimeBinding.conditionalConsumerVersion==='string'&&
            e.deterministicTrace?.evaluatorVersion===runtimeBinding.conditionalConsumerVersion);
        const same=(a,b)=>identityHash(a)===identityHash(b);
        const trace=e.deterministicTrace;
        check(object(trace)&&((trace.executionStatus!=='COMPLETED'&&!Object.hasOwn(trace,'traceSha256'))||
            trace.traceSha256===identityHash(Object.fromEntries(Object.entries(trace).filter(([k])=>k!=='traceSha256'))))
            &&e.evaluationId==='ba1-allele-evaluation-sha256-'+identityHash(Object.fromEntries(Object.entries(e).filter(([k])=>!['evaluationId','conflict','review'].includes(k)))));
        check(object(evidence?.queryAllele)&&same(evidence.queryAllele,context.variant?.evidenceQueryAllele)
            &&same(selection.geneMapping?.queryAllele,evidence.queryAllele)
            &&same(e.evidenceIds,['population-evidence-sha256-'+identityHash(evidence)]));
        check(truth(c.truth)&&truth(c.frequencyTruth)&&truth(c.countTruth));
        for(const [field,key]of [['frequencyRequirement','frequency'],['individualCountRequirement','individualCount']])
            if(Object.hasOwn(c,field))check(same(c[field],expression[key]));
        const status=e.evaluationResult?.status;
        check(['MET','NOT_MET','NOT_EVALUATED'].includes(status));
        if(status==='MET')check(c.truth==='TRUE'&&['STANDALONE','STAND_ALONE'].includes(e.appliedStrength)
            &&e.deterministicTrace.executionStatus==='COMPLETED');
        else check(e.appliedStrength==='NONE'&&(status!=='NOT_MET'||c.truth==='FALSE'));
        if(e.deterministicTrace.executionStatus==='COMPLETED'){
            const expected=c.frequencyTruth==='TRUE'||c.countTruth==='TRUE'?'TRUE':
                c.frequencyTruth==='FALSE'&&c.countTruth==='FALSE'?'FALSE':'UNKNOWN';
            check(c.truth===expected);
        }
        const material=c.countSourceQualification,statistic=c.observedHomozygoteStatistic;
        if(material?.schemaVersion==='ba1-native-individual-count-material-v1'||material?.status?.startsWith('QUALIFIED_')||c.countTruth==='TRUE'||c.countTruth==='FALSE')
            inspectNativeBounds(material,statistic,evidence,rawRecord,runtimeBinding,e,c,identityHash);
        // Optional diagnostics stay optional for old stored UNKNOWN/shared-blocker shapes.
        const view={valid:true,schemaVersion:c.schemaVersion,contractVersion:c.contractVersion,
            truth:c.truth,frequencyTruth:c.frequencyTruth,countTruth:c.countTruth,status,
            frequencyRequirement:c.frequencyRequirement||expression.frequency,individualCountRequirement:c.individualCountRequirement||expression.individualCount,
            frequencyReadings:list(c.frequencyReadings),observedHomozygoteStatistic:statistic,
            countSourceQualification:material,countScopeCompleteness:c.countScopeCompleteness,
            sources:[...list(selection.sources),...list(material?.sourceContract?.sources)],
            summary:status==='MET'?'本次频率或人数条件满足。':status==='NOT_MET'?'本次频率与人数条件均未满足。':'本次频率或人数条件尚未形成可采用结论。'};
        checkedViews.set(view,{evaluation:e,evaluationSha:identityHash(e)});
        return {claimed:true,valid:true,view};
    }catch{return {claimed:true,valid:false,issue:'本次 BA1 条件结果与保存的运行、背景或来源记录不一致。'};}
}
