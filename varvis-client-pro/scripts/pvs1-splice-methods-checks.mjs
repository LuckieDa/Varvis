import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
const source=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgPvs1Splice.js',import.meta.url),'utf8');
const api=await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const canonical=v=>typeof v==='string'?v.normalize('NFC'):Array.isArray(v)?v.map(canonical):v&&typeof v==='object'
    ?Object.fromEntries(Object.keys(v).sort().map(k=>[k.normalize('NFC'),canonical(v[k])])):v;
const identityHash=v=>createHash('sha256').update(JSON.stringify(canonical(v))).digest('hex');
const sha=v=>createHash('sha256').update(v).digest('hex');
const H='a'.repeat(64), EVENT='b'.repeat(64);
const bounded=(parent,name,list)=>Object.assign(parent,{[name]:list,[name+'Total']:list.length,[name+'Truncated']:false});

// Independent public controls, not clinical cases or new backend executions.
export function syntheticPvs1SpliceFixture(mode='INFRAME') {
    const allele={assembly:'GRCh38',contig:'1',position:3000,reference:'G',alternate:'A'};
    const context={schemaVersion:'evidence-context-v3',canonicalizationProfile:'varvis-json-c14n-v1',dataClass:'SYNTHETIC',
        scope:{analysisDomain:'GERMLINE',diseaseModel:'MENDELIAN',variantScope:'SMALL_SEQUENCE_VARIANT'},
        variant:{alleleId:'allele-sha256-'+H,evidenceQueryAllele:allele,inputAllele:allele,normalizedAllele:allele},
        gene:{hgncId:'HGNC:999001',symbol:'SYNTHETIC'},disease:{mondoId:'MONDO:0999001'},moi:'AUTOSOMAL_DOMINANT',
        transcript:{accession:'NM_999001',version:1},patientContext:{patientContextId:'SYNTHETIC'},contextCompleteness:{status:'COMPLETE',missingFields:[]},contextId:'context-SYNTHETIC'};
    const e1={scope:context.scope,alleleId:context.variant.alleleId,hgncId:context.gene.hgncId,mondoId:context.disease.mondoId,moi:context.moi,
        transcript:context.transcript,patientContextId:'SYNTHETIC',familyContextId:null};
    const evaluationContext={...context,schemaVersion:'evidence-context-v2',contextId:'context-sha256-'+identityHash(e1)};
    const group=identityHash(allele), inframe=['INFRAME','NEGATIVE'].includes(mode), topology=mode==='TOPOLOGY', composed=mode==='COMPOSED';
    const count=topology?5:4, alteredLength=inframe?588:587, retainedCount=inframe?4:topology?4:3;
    const c={schemaVersion:'coding-transcript-edit-facts-v1',methodVersion:'1.0.0',scope:'CDS_SEQUENCE_FACTS_ONLY',topologyScope:'CODING_EXONS_ONLY',
        scientificQualificationGranted:false,referenceCdsLength:600,alteredCdsLength:alteredLength,netCdsLengthChange:alteredLength-600,
        alteredCdsSha256:H,binding:{assembly:'GRCh38',contig:'1',transcript:'NM_999001.1',modelSha256:H,referenceCdsSha256:H,contextSha256:identityHash(evaluationContext)},
        compositionStatus:composed?'SAME_ALLELE_CODING_AND_SPLICE_EDITS_APPLIED':'NO_CODING_COMPONENT',explicitCompositionReviewed:composed,
        rnaSequenceScope:'REFERENCE_CDS_DELETION',completeAllelicCdsWitnessVerified:false,fullRnaOrEjcProofGranted:false};
    const edits=[{id:'splice:'+EVENT,origin:'SPLICE_PROJECTION',status:'APPLIED',referenceEditStart0:12,referenceEditEnd0:inframe?24:25,
        evidenceBindingSha256:H,alleleGroupSha256:group,insertionExonOrdinal:0}];
    if(composed) {
        edits.unshift({id:'allele:'+group,origin:'CODING_ALLELE',status:'APPLIED',referenceEditStart0:27,referenceEditEnd0:28,evidenceBindingSha256:group,alleleGroupSha256:group,insertionExonOrdinal:3});
        edits.push({...edits[0],id:'hgvs:'+group,status:'DUPLICATE_SEQUENCE_EDIT'});
        c.sourceDeclaration={schemaVersion:'pvs1-splice-coding-composition-v1',status:'REVIEWED_FOR_SAME_ALLELE_COMPOSITION',reviewId:'SYNTHETIC',
            alleleGroupSha256:group,referenceCdsSha256:H,spliceEventSha256:EVENT,sequenceScope:'REFERENCE_CDS_DELETION'};
    }
    bounded(c,'edits',edits);
    const t={methodVersion:'1.1.0',kind:'MULTI_EXON_CODING_JUNCTION',exonCount:retainedCount,cdsLength:alteredLength,lastJunctionCdsPosition:inframe?438:437};
    const retained=inframe?[[1,1,1,12],[2,2,13,13],[3,3,14,438],[4,4,439,588]]:topology?
        [[1,1,1,12],[3,2,13,49],[4,3,50,437],[5,4,438,587]]:[[1,1,1,12],[3,2,13,437],[4,3,438,587]];
    const s={schemaVersion:'pvs1-splice-assessment-v1',methodVersion:'1.5.0',status:mode==='NEGATIVE'?'NOT_MET':'MET',outcomeId:'SYNTHETIC',
        evidenceKind:'OBSERVED_RNA',interpretation:'SOURCE_REVIEWED_RNA_OUTCOME',maximumStrength:inframe?'MODERATE':'VERY_STRONG',
        doubleCountingConstraint:'DO_NOT_COMBINE_WITH_PP3_OR_PS3_FOR_THE_SAME_SPLICE_EFFECT',rnaReadingFrame:inframe?'PRESERVED':'ALTERED',compositionStatus:c.compositionStatus,
        referenceTopology:{scope:'REFERENCE_TRANSCRIPT',exonCount:count,cdsLength:600,lastJunctionCdsPosition:450},alteredTopology:t};
    bounded(s,'retainedExons',retained.map(([referenceOrdinal,alteredOrdinal,alteredCdsStart,alteredCdsEnd])=>({referenceOrdinal,alteredOrdinal,alteredCdsStart,alteredCdsEnd})));
    bounded(s,'alteredJunctions',s.retainedExons.slice(0,-1).map((left,i)=>({leftReferenceOrdinal:left.referenceOrdinal,rightReferenceOrdinal:s.retainedExons[i+1].referenceOrdinal,alteredCdsPosition:left.alteredCdsEnd})));
    bounded(s,'sourceRecords',[{sourceId:'SYNTHETIC',url:'https://example.invalid/SYNTHETIC',sha256:H}]);
    const f={schemaVersion:'pvs1-selected-facts-v1',methodVersion:'1.7.0',method:'SOURCE_BOUND_SPLICE_CONSEQUENCE',scope:'REVIEWED_SELECTED_TRANSCRIPT',
        clinicalValidationCompleted:false,knowledgeStatus:'READY',knowledgeReason:'PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND',policySchema:'pvs1-truncating-nmd-policy-v4',
        doubleCountingConstraint:'NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE',sourceQualifiedResearch:true,policyStrengthCap:'VERY_STRONG',
        materialSupply:{status:'READY',reasonCode:'PVS1_FULL_TRANSCRIPT_SEQUENCE_AND_STRUCTURE_VERIFIED',bindingMethod:'EXACT_FULL_TRANSCRIPT_EQUIVALENCE',verifiedExonCount:count,policyModelUnchanged:true},
        selectedTranscript:'NM_999001.1',variantMechanism:'SPLICE_CDS_DELETION',coordinateSystem:'ALTERED_CDS',cdsPosition:13,exonOrdinal:2,exonTotal:count,
        referenceExonTotal:count,alteredExonTotal:retainedCount,referenceLastJunctionCdsPosition:450,lastJunctionCdsPosition:t.lastJunctionCdsPosition,
        mutantLastJunctionCdsPosition:t.lastJunctionCdsPosition,transcriptTopology:{...t,utrApplicabilityBound:false},spliceAssessment:s,codingEditComposition:c};
    const criterion={criterionId:'ACMG_AMP_2015:PVS1',status:mode==='NEGATIVE'?'NOT_MET':'MET',appliedStrength:mode==='NEGATIVE'?'NONE':inframe?'MODERATE':'VERY_STRONG',
        reasonCode:mode==='NEGATIVE'?'PVS1_SPLICE_INFRAME_EXON_NOT_RELEVANT':'LOCAL_BATCH_CRITERION_MET',reason:'SYNTHETIC independent public control',details:{pvs1Facts:f}};
    if(mode==='NEGATIVE') s.reasonCode=criterion.reasonCode; else s.adoptedStrength=criterion.appliedStrength;
    if(inframe) {
        f.inFrameAssessment={schemaVersion:'pvs1-splice-inframe-assessment-v1',operatorVersion:'1.1.1',status:s.status,policyReviewId:'SYNTHETIC',deleteStartCds:13,deleteEndCds:24,deletedCdsBases:12,
            referenceProteinLength:199,alteredProteinLength:195,codonAligned:true,firstTouchedReferenceResidue:5,lastTouchedReferenceResidue:8,fullyRemovedReferenceResidues:4,
            firstFullyRemovedReferenceResidue:5,lastFullyRemovedReferenceResidue:8,netCodonDeletion:4,deletedProteinResidues:4,deletedProteinResidueMetric:'NET_PROTEIN_LENGTH_DECREASE',nativeStopRetained:true,
            sequenceDerivationStatus:'EXACT_LOCAL_CODON_ALIGNED_DELETION'};
        if(mode==='NEGATIVE')f.inFrameAssessment.reasonCode=criterion.reasonCode;
        else {Object.assign(f.inFrameAssessment,{strength:'STRONG',selectedRule:{ruleId:'SYNTHETIC',predicate:'CRITICAL_REGION',start:5,end:8,minimumAffectedResidues:1,strength:'STRONG'}});bounded(f.inFrameAssessment,'ruleChecks',[{status:'MATCH',partiallyDeletedReferenceResiduesInRegion:0}]);}
    } else {
        Object.assign(f,{ptcStart:49,ptcEnd:51,ptcAlteredExonOrdinal:topology?3:2,ptcReferenceStart:62,ptcReferenceMiddle:63,ptcReferenceEnd:64,ptcReferenceMapping:'REFERENCE_BASES',
            nmdPrediction:'PREDICTED_NMD',sequenceDerivationStatus:'SOURCE_BOUND_SPLICE_EDIT_AND_TRANSCRIPT_SEQUENCE_AGREE',distanceToLastJunction:386});
        s.ptcCrossesJunction=topology;
        bounded(s,'ptcExons',topology?[{referenceOrdinal:3,alteredOrdinal:2},{referenceOrdinal:4,alteredOrdinal:3}]:[{referenceOrdinal:3,alteredOrdinal:2}]);
    }
    return {criterion,context};
}

let count=0;
const run=(name,fn)=>{fn();count++;console.log('PASS '+name);};
const inspect=v=>api.inspectPvs1SpliceCriterion(v.criterion,v.context,{identityHash});
const facts=v=>v.criterion.details.pvs1Facts;
for(const mode of ['INFRAME','NEGATIVE','TOPOLOGY','COMPOSED'])run('independent '+mode+' public method and input are stable',()=>{
    const v=syntheticPvs1SpliceFixture(mode),before=JSON.stringify(v);assert.deepEqual(inspect(v),{valid:true,issues:[]});assert.ok(api.pvs1SpliceDetails(v.criterion).length>0);assert.equal(JSON.stringify(v),before);
});
run('old A1 and ordinary frozen 1.1 facts keep their existing dispatcher',()=>{
    for(const methodVersion of ['1.1.0','1.2.0','1.3.0','1.4.0','1.5.0','1.6.0'])assert.equal(api.isPvs1SpliceSummary({methodVersion}),false);
    assert.equal(api.isPvs1SpliceSummary({methodVersion:'1.1.0',inFrameAssessment:{}}),true);
    assert.equal(api.isPvs1SpliceSummary({methodVersion:'1.1.0',method:'UNKNOWN'}),true);
});
const mutations=[
    ['method unknown',f=>f.method='UNKNOWN'],['missing method',f=>delete f.method],['unsupported major',f=>f.methodVersion='2.0.0'],
    ['parent qualification',f=>f.sourceQualifiedResearch=false],['policy cap',f=>f.policyStrengthCap='SUPPORTING'],['material cap',f=>f.spliceAssessment.maximumStrength='SUPPORTING'],
    ['module adopted strength',f=>f.spliceAssessment.adoptedStrength='STRONG'],['operator major',f=>f.inFrameAssessment.operatorVersion='2.0.0'],
    ['critical region only touched',f=>f.inFrameAssessment.selectedRule.start=9],['protein net deletion',f=>f.inFrameAssessment.netCodonDeletion=5],
    ['RNA edit position',f=>f.cdsPosition++],['RNA frame',f=>f.spliceAssessment.rnaReadingFrame='ALTERED'],
    ['in-frame edit interval',f=>{f.inFrameAssessment.deleteStartCds+=3;f.inFrameAssessment.deleteEndCds+=3;}],
    ['source digest',f=>f.spliceAssessment.sourceRecords[0].sha256='wrong'],['material model binding',f=>f.materialSupply.policyModelUnchanged=false],
    ['context hash',f=>f.codingEditComposition.binding.contextSha256='f'.repeat(64)],['same-allele binding',f=>f.codingEditComposition.edits[0].alleleGroupSha256='f'.repeat(64)],
    ['topology mismatch',f=>f.transcriptTopology.exonCount++],['altered ordinal',f=>f.spliceAssessment.retainedExons[1].alteredOrdinal=3],
    ['junction coordinate',f=>f.spliceAssessment.alteredJunctions[0].alteredCdsPosition++],['collection total',f=>f.spliceAssessment.retainedExonsTotal++],
    ['whole RNA proof invented',f=>f.codingEditComposition.fullRnaOrEjcProofGranted=true],['duplicate matching rules',f=>bounded(f.inFrameAssessment,'ruleChecks',[{status:'MATCH'},{status:'MATCH'}])],
];
for(const [name,mutate]of mutations)run('reject '+name,()=>{const v=syntheticPvs1SpliceFixture();mutate(facts(v));assert.equal(inspect(v).valid,false,name);});
run('unknown or conflicting facts cannot become NOT_MET',()=>{
    for(const reason of ['PVS1_SPLICE_INFRAME_RULES_AMBIGUOUS','PVS1_SPLICE_EVIDENCE_QUALIFICATION_REQUIRED','PVS1_SPLICE_INFRAME_NO_REVIEWED_RULE_MATCH']){
        const v=syntheticPvs1SpliceFixture('NEGATIVE');v.criterion.reasonCode=reason;facts(v).spliceAssessment.reasonCode=reason;facts(v).inFrameAssessment.reasonCode=reason;assert.equal(inspect(v).valid,false);
    }
});
run('PTC reference zero is never silently used as a real reference base',()=>{
    const v=syntheticPvs1SpliceFixture('COMPOSED'),f=facts(v);f.ptcReferenceStart=0;
    assert.equal(inspect(v).valid,false);f.ptcReferenceMapping='MIXED_REFERENCE_AND_INSERTED_BASES';f.ptcReferenceMappingReason='INSERTED_OR_REPLACEMENT_BASES_HAVE_NO_REFERENCE_IDENTITY';
    assert.equal(inspect(v).valid,false,'zero also has to match actual applied edit boundaries');
    assert.ok(api.pvs1SpliceDetails(v.criterion).some(([,value])=>value.includes('无参考身份')));
});
run('partial PTC and composition source identities cannot be substituted',()=>{
    for(const mutate of [f=>f.ptcEnd++,f=>f.distanceToLastJunction++,f=>f.spliceAssessment.ptcCrossesJunction=false,f=>f.spliceAssessment.ptcExons[1].referenceOrdinal=5]){
        const v=syntheticPvs1SpliceFixture('TOPOLOGY');mutate(facts(v));assert.equal(inspect(v).valid,false);
    }
    const v=syntheticPvs1SpliceFixture('COMPOSED');facts(v).codingEditComposition.sourceDeclaration.spliceEventSha256=H;assert.equal(inspect(v).valid,false);
});
run('source/conflict NOT_EVALUATED keeps no strength and does not invent a PTC',()=>{
    const v=syntheticPvs1SpliceFixture(),f=facts(v);v.criterion.status='NOT_EVALUATED';v.criterion.appliedStrength='NONE';v.criterion.reasonCode='PVS1_SPLICE_INFRAME_RULES_AMBIGUOUS';
    f.spliceAssessment.status='NOT_EVALUATED';f.spliceAssessment.reasonCode=v.criterion.reasonCode;delete f.spliceAssessment.adoptedStrength;assert.equal(inspect(v).valid,true);
    v.criterion.appliedStrength='MODERATE';assert.equal(inspect(v).valid,false);
});
run('new escape strength omits adopted rule proof and preserves its saved value',()=>{
    const v=syntheticPvs1SpliceFixture('COMPOSED'),f=facts(v);f.nmdPrediction='NMD_NOT_PREDICTED';f.escapeAssessment={schemaVersion:'pvs1-nmd-escape-assessment-v1',status:'MET'};
    assert.deepEqual(inspect(v),{valid:false,issues:['PVS1_SPLICE_ESCAPE_PUBLIC_RULE_PROOF_REQUIRED']});
    assert.ok(api.pvs1SpliceDetails(v.criterion).some(([label])=>label==='逃逸强度展示核验依赖'));
});
run('exact local fraction uses integer cross-products and rejects the unspecified equality edge',()=>{
    const v=syntheticPvs1SpliceFixture(),a=facts(v).inFrameAssessment;
    a.selectedRule={ruleId:'SYNTHETIC_FRACTION',predicate:'DELETED_PROTEIN_FRACTION',numerator:1,denominator:50,comparison:'GT',strength:'STRONG'};
    bounded(a,'ruleChecks',[{status:'MATCH',actualNumerator:4,actualDenominator:199,leftProduct:200,rightProduct:199}]);
    assert.equal(inspect(v).valid,true);
    a.selectedRule.numerator=4;a.selectedRule.denominator=199;a.ruleChecks[0].leftProduct=796;a.ruleChecks[0].rightProduct=796;
    assert.equal(inspect(v).valid,false);
});
run('fusion codon keeps touched versus completely removed residues and cannot hide a new stop',()=>{
    const v=syntheticPvs1SpliceFixture(),f=facts(v),a=f.inFrameAssessment;
    f.cdsPosition=14;f.codingEditComposition.edits[0].referenceEditStart0=13;f.codingEditComposition.edits[0].referenceEditEnd0=25;
    Object.assign(a,{deleteStartCds:14,deleteEndCds:25,codonAligned:false,lastTouchedReferenceResidue:9,fullyRemovedReferenceResidues:3,
        firstFullyRemovedReferenceResidue:6,lastFullyRemovedReferenceResidue:8,sequenceDerivationStatus:'EXACT_LOCAL_INFRAME_DELETION_WITH_FUSION_CODON',
        junctionEffect:{alteredProteinPosition:5,alteredCodon:'GCT',alteredAminoAcid:'A',leftReferenceResidue:5,rightReferenceResidue:9,leftRetainedBaseCount:1,rightRetainedBaseCount:2}});
    assert.equal(inspect(v).valid,true);a.junctionEffect.alteredCodon='TAA';assert.equal(inspect(v).valid,false);
});
run('legitimate inserted PTC bases use zero identity while altered PTC remains exact',()=>{
    const v=syntheticPvs1SpliceFixture('COMPOSED'),f=facts(v),c=f.codingEditComposition,ctx=v.context;
    ctx.variant.evidenceQueryAllele.reference='CCC';ctx.variant.evidenceQueryAllele.alternate='TAA';
    const group=identityHash(ctx.variant.evidenceQueryAllele);
    const projection={scope:ctx.scope,alleleId:ctx.variant.alleleId,hgncId:ctx.gene.hgncId,mondoId:ctx.disease.mondoId,moi:ctx.moi,transcript:ctx.transcript,patientContextId:'SYNTHETIC',familyContextId:null};
    c.binding.contextSha256=identityHash({...ctx,schemaVersion:'evidence-context-v2',contextId:'context-sha256-'+identityHash(projection)});
    c.sourceDeclaration.alleleGroupSha256=group;
    for(const edit of c.edits){edit.alleleGroupSha256=group;if(edit.origin==='CODING_ALLELE'){edit.referenceEditStart0=61;edit.referenceEditEnd0=64;edit.evidenceBindingSha256=group;edit.id=(edit.status==='APPLIED'?'allele:':'hgvs:')+group;}}
    Object.assign(f,{ptcReferenceStart:0,ptcReferenceMiddle:0,ptcReferenceEnd:0,ptcReferenceMapping:'NO_REFERENCE_BASES',ptcReferenceMappingReason:'INSERTED_OR_REPLACEMENT_BASES_HAVE_NO_REFERENCE_IDENTITY'});
    assert.equal(inspect(v).valid,true);f.ptcReferenceStart=62;assert.equal(inspect(v).valid,false);
});
run('32-row public topology limits remain explicit and are not reported as missing scientific facts',()=>{
    const v=syntheticPvs1SpliceFixture(),f=facts(v),s=f.spliceAssessment;
    const full=[{referenceOrdinal:1,alteredOrdinal:1,alteredCdsStart:1,alteredCdsEnd:12},{referenceOrdinal:2,alteredOrdinal:2,alteredCdsStart:13,alteredCdsEnd:13}];
    for(let ordinal=3;ordinal<=38;ordinal++)full.push({referenceOrdinal:ordinal,alteredOrdinal:ordinal,alteredCdsStart:ordinal+11,alteredCdsEnd:ordinal+11});
    full.push({referenceOrdinal:39,alteredOrdinal:39,alteredCdsStart:50,alteredCdsEnd:438},{referenceOrdinal:40,alteredOrdinal:40,alteredCdsStart:439,alteredCdsEnd:588});
    s.retainedExons=full.slice(0,32);s.retainedExonsTotal=40;s.retainedExonsTruncated=true;
    s.alteredJunctions=full.slice(0,32).map((e,i)=>({leftReferenceOrdinal:e.referenceOrdinal,rightReferenceOrdinal:full[i+1].referenceOrdinal,alteredCdsPosition:e.alteredCdsEnd}));
    s.alteredJunctionsTotal=39;s.alteredJunctionsTruncated=true;
    f.referenceExonTotal=40;f.exonTotal=40;f.alteredExonTotal=40;f.materialSupply.verifiedExonCount=40;s.referenceTopology.exonCount=40;s.alteredTopology.exonCount=40;f.transcriptTopology.exonCount=40;
    assert.equal(inspect(v).valid,true);assert.ok(api.pvs1SpliceDetails(v.criterion).some(([,value])=>value.includes('截断 true')));
});
run('coding-only method has its own PTC map/cap and the same escape-proof boundary',()=>{
    const v=syntheticPvs1SpliceFixture('COMPOSED'),f=facts(v),ctx=v.context,c=f.codingEditComposition;
    ctx.variant.evidenceQueryAllele.alternate='GA';
    const group=identityHash(ctx.variant.evidenceQueryAllele),projection={scope:ctx.scope,alleleId:ctx.variant.alleleId,hgncId:ctx.gene.hgncId,mondoId:ctx.disease.mondoId,moi:ctx.moi,
        transcript:ctx.transcript,patientContextId:'SYNTHETIC',familyContextId:null};
    const derivation={schemaVersion:c.schemaVersion,methodVersion:c.methodVersion,scope:c.scope,topologyScope:c.topologyScope,scientificQualificationGranted:false,
        alteredCdsSha256:H,referenceCdsLength:600,alteredCdsLength:601,netCdsLengthChange:1,binding:{...c.binding,contextSha256:identityHash({...ctx,schemaVersion:'evidence-context-v2',contextId:'context-sha256-'+identityHash(projection)})}};
    bounded(derivation,'edits',[{id:'allele:'+group,origin:'CODING_ALLELE',status:'APPLIED',referenceEditStart0:12,referenceEditEnd0:12,evidenceBindingSha256:group,alleleGroupSha256:group,insertionExonOrdinal:2}]);
    delete f.codingEditComposition;delete f.spliceAssessment;delete f.transcriptTopology;
    Object.assign(f,{method:'SOURCE_BOUND_CODING_EDITS',codingEditDerivation:derivation,variantMechanism:'FRAMESHIFT',sequenceDerivationStatus:'GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE',
        referenceLastJunctionCdsPosition:450,mutantLastJunctionCdsPosition:451,lastJunctionCdsPosition:451,distanceToLastJunction:400,
        ptcReferenceStart:48,ptcReferenceMiddle:49,ptcReferenceEnd:50,ptcReferenceMapping:'REFERENCE_BASES'});
    assert.equal(inspect(v).valid,true);v.criterion.appliedStrength='STRONG';assert.equal(inspect(v).valid,false);v.criterion.appliedStrength='VERY_STRONG';
    f.nmdPrediction='NMD_NOT_PREDICTED';f.escapeAssessment={schemaVersion:'pvs1-nmd-escape-assessment-v1',status:'MET'};
    assert.deepEqual(inspect(v),{valid:false,issues:['PVS1_SPLICE_ESCAPE_PUBLIC_RULE_PROOF_REQUIRED']});
});

if(process.env.VARVIS_NATIVE_ROOT){
    const task=path.join(process.env.VARVIS_NATIVE_ROOT,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a2-a4/round3');
    const handoffBytes=fs.readFileSync(path.join(task,'frontend-contract.json')),handoff=JSON.parse(handoffBytes);
    let actual=0;
    for(const mode of ['INFRAME','NEGATIVE','TOPOLOGY','COMPOSED']){
        const number={INFRAME:1,NEGATIVE:2,TOPOLOGY:3,COMPOSED:4}[mode];
        const name=`newSourceQualifiedSpliceMethodsReachPvs1OnlyBatchAndDurableHistory-_${number}__mode_${mode}`;
        const filename=path.join(task,'business-final-01/business',name,'round3-all.json'),bytes=fs.readFileSync(filename),document=JSON.parse(bytes);
        for(const candidate of document.candidates)for(const attempt of candidate.attempts){
            const criterion=attempt.localBatch.criteria.find(row=>row.criterionId==='ACMG_AMP_2015:PVS1');
            run(`actual cf2e8e61 Java public ${mode} ${criterion.details.pvs1Facts.selectedTranscript}`,()=>{
                const before=JSON.stringify(document),f=criterion.details.pvs1Facts;
                assert.deepEqual(api.inspectPvs1SpliceCriterion(criterion,attempt.context,{identityHash}),{valid:true,issues:[]});
                assert.ok(handoff.examples.some(example=>example.scenario===name&&example.facts.selectedTranscript===f.selectedTranscript&&JSON.stringify(example.facts)===JSON.stringify(f)));
                // Independent geometry/cap expectations, not values read from the result under test.
                assert.equal(f.codingEditComposition.referenceCdsLength,600);assert.equal(f.codingEditComposition.alteredCdsLength,mode==='INFRAME'||mode==='NEGATIVE'?588:587);
                assert.equal(criterion.status,mode==='NEGATIVE'?'NOT_MET':'MET');assert.equal(criterion.appliedStrength,mode==='NEGATIVE'?'NONE':mode==='INFRAME'?'MODERATE':'VERY_STRONG');
                assert.equal(f.transcriptTopology.lastJunctionCdsPosition,mode==='INFRAME'||mode==='NEGATIVE'?438:437);
                api.pvs1SpliceDetails(criterion);assert.equal(JSON.stringify(document),before);assert.deepEqual(fs.readFileSync(filename),bytes);actual++;
            });
        }
        console.log(`FROZEN ${mode} raw Java response SHA256 ${sha(bytes)}`);
    }
    assert.equal(actual,8);assert.deepEqual(fs.readFileSync(path.join(task,'frontend-contract.json')),handoffBytes);
    console.log(`FROZEN frontend handoff SHA256 ${sha(handoffBytes)}`);
}else console.log('NOT_RUN actual cf2e8e61 Java public results; set VARVIS_NATIVE_ROOT. Synthetic controls do not certify source adoption.');
console.log(`${count} PVS1 splice frontend checks passed; Java source/assembly/H2 execution is inherited evidence, not rerun here.`);
