package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.config.Pm4Bp3ComplexPublicFixture;
import org.springframework.mock.env.MockEnvironment;

/** Invented engineering sources, read by the production factories. No scientific result is stubbed.
 * Independent sequence oracle: ATG GAA CCT GGC TTT AAA TAA -> ATG GAA GGC TTT AAA TAA;
 * reference P3 alone is removed; the DNA c8C>T lies inside that removed codon and is not applied twice.
 * Identity aliases are transport only, not assertions about the real named human genes.
 */
public final class T07FullRnaBusinessMaterials {
    public static final String REFERENCE="ATGGAACCTGGCTTTAAATAAGCTGGTTGACCC";
    public static final String PRODUCT="ATGGAAGGCTTTAAATAAGCTGGTTGACCC";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter hashes;
    private final Path root;
    private final MockEnvironment env;
    private final Pvs1NmdBusinessFixture business;
    private final List<ObjectNode> originals=new ArrayList<>(), bindings=new ArrayList<>();
    public T07FullRnaBusinessMaterials(ObjectMapper mapper,Path root,Pm4Bp3ComplexPublicFixture transport,JsonNode choices) throws Exception {
        this(mapper,root,transport,choices,107,"C","T");
    }
    public T07FullRnaBusinessMaterials(ObjectMapper mapper,Path root,Pm4Bp3ComplexPublicFixture transport,JsonNode choices,
            int position,String reference,String alternate) throws Exception {
        this.mapper=mapper;this.hashes=new RuntimeEvidenceV2Adapter(mapper);this.root=Files.createDirectories(root);this.env=transport.env();
        business=new Pvs1NmdBusinessFixture(mapper,false);business.models.clear();business.policies.clear();
        for(int i=0;i<2;i++) {
            String gene=i==0?"HBB":"GJB2";var model=transport.model(gene);var choice=choices.get(i);
            var old=new Pvs1SpliceConsequenceTest().data(1,false,false);var policy=old.input().policy.deepCopy();
            policy.put("policyId","SYNTHETIC_T07_FULL_RNA_"+i);
            var scope=policy.withObject("scope");scope.set("hgncId",model.path("hgncId"));scope.set("assembly",model.path("assembly"));
            scope.set("mondoId",choice.path("syntheticHypothesisMondoId"));scope.set("moi",choice.path("syntheticHypothesisMoi"));
            String tx=model.path("transcript").asText();int dot=tx.lastIndexOf('.');
            scope.putArray("transcripts").addObject().put("accession",tx.substring(0,dot)).put("version",Integer.parseInt(tx.substring(dot+1)));
            var escape=Pvs1NmdEscapeTest.scope(policy,1);Pvs1NmdEscapeTest.critical(escape,3,3,1,"STRONG");policy.put("schemaVersion",Pvs1SpliceConsequence.SCHEMA);
            var nmd=policy.withObject("nmdScope").put("longExonScope","REVIEWED_WITHOUT_LENGTH_LIMIT");
            nmd.putArray("relevantExonOrdinals").add(1);nmd.putArray("engineeringSupportedExonOrdinals");
            var inframe=policy.withObject("spliceScope").putObject("inFrameScope").put("status","REVIEWED_FOR_PVS1_SPLICE_INFRAME")
                    .put("reviewId","SYNTHETIC_T07_INFRAME_POLICY").put("basis","Invented engineering critical P3 rule, not human functional evidence");
            inframe.putArray("sourceIds").add("synthetic-source");
            inframe.putArray("exonAssessments").addObject().put("ordinal",1).put("biologicalRelevance","PRESENT").put("populationLof","NOT_FREQUENT")
                    .put("basis","Invented engineering exon scope").putArray("sourceIds").add("synthetic-source");
            inframe.putArray("rules").addObject().put("ruleId","SYNTHETIC_T07_CRITICAL_P3").put("predicate","CRITICAL_REGION")
                    .put("start",3).put("end",3).put("minimumAffectedResidues",1).put("strength","STRONG")
                    .put("basis","Invented independent exact-residue rule, not real clinical review").putArray("sourceIds").add("synthetic-source");
            configureSources(policy,model);
            business.models.add(model);business.policies.add(policy);
            var original=mapper.createObjectNode().put("schemaVersion",Pvs1SourceQualification.FULL_RNA_ORIGINAL_SCHEMA)
                    .put("reviewId","SYNTHETIC_T07_RNA_"+i).put("basis","Invented exact RNA assay, not an actual human experiment");
            var nativeScope=original.putObject("scope");for(String field:List.of("assembly","contig","hgncId","geneSymbol","transcript"))nativeScope.set(field,model.path(field));
            nativeScope.put("modelSha256",hashes.canonicalSha256(model)).put("referenceRnaSha256",CodingTranscriptMaterialSupply.sha(REFERENCE));
            var allele=nativeScope.putObject("allele").put("assembly","GRCh38").put("contig",model.path("contig").asText())
                    .put("position",position).put("reference",reference).put("alternate",alternate);
            original.putObject("use").put("purpose","PROJECT_RESEARCH").put("dataClass","SYNTHETIC").put("clinicalAcceptance",false).put("createsExpertReview",false);
            original.putObject("assay").put("evidenceKind","OBSERVED_RNA").put("alleleAttribution","ASSESSED").put("assayQuality","ASSESSED")
                    .put("tissueRelevance","ASSESSED").put("residualNormalTranscript","NOT_DETECTED_IN_ASSESSED_SCOPE")
                    .put("outcomeMultiplicity","SINGLE_SUPPORTED_OUTCOME").put("basis","SYNTHETIC declared engineering assay, no real phase or functional qualification");
            original.putObject("physicalAllele").put("status","SINGLE_PHYSICAL_ALLELE").put("alleleSha256",hashes.canonicalSha256(allele))
                    .put("basis","Synthetic one-physical-allele source declaration");
            original.putObject("events").put("coordinateSystem",CodingTranscriptEdits.RNA_COORDINATES).put("sequenceSemantics","REFERENCE_RNA_EDIT_DELTAS")
                    .putArray("rnaEdits").addObject().put("eventId","SYNTHETIC_REMOVE_P3").put("start0",6).put("end0",9)
                    .put("expectedReference","CCT").put("insertedSequence","");
            original.putObject("sequence").put("completeAlteredRnaWitness",PRODUCT).put("alteredRnaSha256",CodingTranscriptMaterialSupply.sha(PRODUCT));
            original.putObject("topology").put("completeness","COMPLETE").put("basis","Synthetic single-exon complete RNA").putArray("junctions0");
            originals.add(original);
            var binding=mapper.createObjectNode().put("relativePath","rna-"+i+".json");binding.putObject("sourceRecord").put("sourceId","SYNTHETIC_T07_RNA_"+i)
                    .put("version","TEST_ONLY").put("url","https://example.test/SYNTHETIC/t07/rna-"+i).put("sha256","0".repeat(64));bindings.add(binding);
        }
        writeKnowledge();writeBundle();writeRegions(transport,choices);
    }
    private Path write(String name,JsonNode doc) throws Exception {Path path=root.resolve(name);Files.write(path,mapper.writeValueAsBytes(doc));return path;}
    private void pin(String key,Path path) throws Exception {env.withProperty(Pm4Bp3ComplexPublicFixture.PREFIX+key+"-file",Objects.requireNonNull(path.toString()))
            .withProperty(Pm4Bp3ComplexPublicFixture.PREFIX+key+"-sha256",Objects.requireNonNull(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(path))));}
    private java.util.Map<String,byte[]> configureSources(ObjectNode policy,ObjectNode model) {
        var originals=new java.util.LinkedHashMap<String,byte[]>();
        for(var value:policy.path("scientificSources")) {
            var source=(ObjectNode)value;String id=source.path("sourceId").asText();
            byte[] bytes=("SYNTHETIC TEST ONLY rule original: "+id+"; critical reference P3 deletion supports Strong in this invented engineering rule; not human functional evidence.").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            originals.put(id,bytes);source.put("sha256",CodingTranscriptMaterialSupply.sha(bytes));
        }
        policy.putObject("transcriptModelBinding").put("modelId",model.path("modelId").asText()).put("modelVersion",model.path("modelVersion").asText())
                .put("canonicalSha256",hashes.canonicalSha256(model));return originals;
    }
    public void configureCompanions(Path python) throws Exception {
        Path denovo=root.resolve("denovo");new DeNovoBatchFixture(denovo);
        String prefix=Pm4Bp3ComplexPublicFixture.PREFIX;
        env.withProperty(prefix+"local-batch-denovo-enabled","true").withProperty(prefix+"local-batch-denovo-use","SYNTHETIC_VALIDATION")
                .withProperty(prefix+"local-batch-denovo-material-file",Objects.requireNonNull(denovo.resolve("manifest.json").toString()))
                .withProperty(prefix+"local-batch-denovo-material-sha256",Objects.requireNonNull(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(denovo.resolve("manifest.json")))))
                .withProperty(prefix+"local-batch-pm3-literature-enabled","true").withProperty(prefix+"local-batch-pp1-literature-enabled","true");
        Path workflow=UnifiedEighteenCompanionFixtures.prepareEmptyLiteratureWorkflow(mapper,root.resolve("literature"),Path.of("../scripts/denovo"),python);
        env.withProperty("varvis.literature-workflow-config",Objects.requireNonNull(workflow.toString()));
        for(String code:List.of("pm1","pp2","bp1")) {
            Path knowledge=Path.of("D:/LSD/Project/Code/local/runtime/bp7-evidence-workbench/materials/fourteen-v1").resolve(code+"-knowledge.json");
            env.withProperty(prefix+"local-batch-"+code+"-enabled","true");pin(code+"-published-knowledge",knowledge);
        }
    }
    private void writeKnowledge() throws Exception {
        var facts=Pvs1KnowledgeCatalogTest.facts(mapper,business.policies);Path factFile=write("facts.json",facts);
        var adoption=Pvs1SpliceSourceQualificationTest.adoption(mapper,facts,business.policies);
        adoption.put("sourceCandidatesSha256",CodingTranscriptMaterialSupply.sha(Files.readAllBytes(factFile)));
        for(int i=0;i<2;i++) {
            var policy=business.policies.get(i);var model=business.models.get(i);
            var bytes=configureSources(policy,model);
            var qualification=Pvs1SpliceSourceQualificationTest.qualification(mapper,root.resolve("knowledge-"+i),policy,model,List.of(),bytes);
            ((ObjectNode)adoption.at("/entries/"+i)).set("qualification",qualification);
        }
        pin("pvs1-knowledge-source",factFile);pin("pvs1-knowledge-adoption",write("adoption.json",adoption));
        env.withProperty(Pm4Bp3ComplexPublicFixture.PREFIX+"pvs1-selected-knowledge-enabled","true")
                .withProperty(Pm4Bp3ComplexPublicFixture.PREFIX+"pvs1-local-material-supply-enabled","true");
    }
    public void writeBundle() throws Exception {
        var bundle=business.document();var index=bundle.withObject("materials").putArray("pvs1FullRnaOriginals");
        for(int i=0;i<2;i++) {
            byte[] bytes=mapper.writeValueAsBytes(originals.get(i));Files.write(root.resolve("rna-"+i+".json"),bytes);
            bindings.get(i).withObject("sourceRecord").put("sha256",CodingTranscriptMaterialSupply.sha(bytes));
            var row=index.addObject().put("entryId","SYNTHETIC_T07_"+i).put("transcript",business.models.get(i).path("transcript").asText());
            row.set("allele",originals.get(i).at("/scope/allele"));row.set("binding",bindings.get(i));
        }
        Path file=write("bundle.json",bundle);String prefix=Pm4Bp3ComplexPublicFixture.PREFIX;
        env.withProperty(prefix+"pvs1-bundle-file",Objects.requireNonNull(file.toString())).withProperty(prefix+"pvs1-bundle-file-sha256",Objects.requireNonNull(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file))))
                .withProperty(prefix+"pvs1-bundle-sha256",Objects.requireNonNull(hashes.canonicalSha256(bundle)));
    }
    /** Binds only the observed mechanical identity from an actual neutral preparation.
     * The P3 rule/independent expected Strong above is fixed before this run; no observed score is used as oracle. */
    public void bindReviewedEffects(JsonNode fullBatch) throws Exception {
        var materials=fullBatch.at("/fullRnaArchive/materialsBySha256");if(materials.size()!=2)throw new IllegalStateException("T07_TWO_ACTUAL_NEUTRAL_MATERIALS_REQUIRED:"+fullBatch);
        for(int i=0;i<2;i++) {
            JsonNode material=null;String tx=business.models.get(i).path("transcript").asText();
            for(var value:materials) { var origin=mapper.readTree(fullBatch.at("/fullRnaArchive/originalsByRawSha256").path(value.path("originalReference").asText()).path("originalUtf8").asText());
                if(tx.equals(origin.at("/scope/transcript").asText())) {if(material!=null)throw new IllegalStateException("T07_AMBIGUOUS_MATERIAL");material=value;} }
            if(material==null)throw new IllegalStateException("T07_NEUTRAL_MATERIAL_MISSING:"+tx+materials);
            var assessment=originals.get(i).putObject("pvs1Assessment").put("method","SOURCE_BOUND_FULL_RNA_PVS1")
                    .put("policySha256",hashes.canonicalSha256(business.policies.get(i))).put("mechanicalEffectSha256",material.at("/consequenceFacts/mechanicalEffectSha256").asText());
            assessment.putObject("parentReview").put("status","REVIEWED_FOR_PVS1_SPLICE").put("reviewId","SYNTHETIC_T07_PARENT")
                    .put("basis","Invented complete-effect review, not an actual human experiment").put("maximumStrength","VERY_STRONG")
                    .put("functionalRescue","EXCLUDED_IN_REVIEWED_SCOPE").put("outcomeMultiplicity","SINGLE_SUPPORTED_OUTCOME")
                    .put("residualNormalTranscript","NOT_DETECTED_IN_ASSESSED_SCOPE").putArray("sourceIds").add("synthetic-source").add("SYNTHETIC_T07_RNA_"+i);
            var frame=assessment.putObject("inFrameReview").put("status","REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId","SYNTHETIC_T07_WHOLE_PRODUCT")
                    .put("basis","Invented exact P3-only removal");frame.putArray("sourceIds").add("SYNTHETIC_T07_RNA_"+i);
            frame.putObject("compositeEffectAssessment").put("schemaVersion","pvs1-composite-inframe-effect-review-v1")
                    .put("status","REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT").put("mechanicalEffectSha256",material.at("/consequenceFacts/mechanicalEffectSha256").asText())
                    .put("reviewId","SYNTHETIC_T07_COMPLETE_EFFECT").put("basis","Invented source-qualified engineering full product")
                    .putArray("sourceIds").add("SYNTHETIC_T07_RNA_"+i);
        }
        writeBundle();
    }
    /** Neutral synthetic RNA observation only. Removes the old PVS1 scientific review;
     * it never creates a functional rescue, chooses a start, or grants an evidence score. */
    public void neutralRnaEvent(String id,int start0,int end0,String inserted,String witness) throws Exception {
        for(var original:originals) {
            original.remove("pvs1Assessment");
            original.withObject("events").putArray("rnaEdits").addObject().put("eventId",id).put("start0",start0).put("end0",end0)
                    .put("expectedReference",REFERENCE.substring(start0,end0)).put("insertedSequence",inserted);
            original.withObject("sequence").put("completeAlteredRnaWitness",witness).put("alteredRnaSha256",CodingTranscriptMaterialSupply.sha(witness));
        }
        writeBundle();
    }
    public Path originalFile(int index) {return root.resolve("rna-"+index+".json");}
    public void noIndex() throws Exception {var bundle=business.document();Path file=write("bundle.json",bundle);String prefix=Pm4Bp3ComplexPublicFixture.PREFIX;
        env.withProperty(prefix+"pvs1-bundle-file-sha256",Objects.requireNonNull(CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file)))).withProperty(prefix+"pvs1-bundle-sha256",Objects.requireNonNull(hashes.canonicalSha256(bundle)));}
    private void writeRegions(Pm4Bp3ComplexPublicFixture transport,JsonNode choices) throws Exception {
        String prefix=Pm4Bp3ComplexPublicFixture.PREFIX;
        var uniprot=new UniProtProteinRegionSupply(mapper,Path.of(env.getRequiredProperty(prefix+"pm4-protein-region-directory")),env.getRequiredProperty(prefix+"pm4-protein-region-manifest-sha256"));
        var combined=new CombinedProteinRegionSupply(mapper,Path.of(env.getRequiredProperty(prefix+"pm4-combined-region-file")),env.getRequiredProperty(prefix+"pm4-combined-region-sha256"));
        var pm4=mapper.createObjectNode().put("schemaVersion","pm4-reviewed-protein-repeat-assertions-v1").put("coordinateSystem","REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE").put("dataClass","SYNTHETIC_TEST_ONLY");
        pm4.putObject("review").put("status","REVIEWED_APPLICABLE").put("reviewId","SYNTHETIC_T07_NONREPEAT").put("reviewer","SYNTHETIC")
                .put("basis","Invented non-repeat coverage; not inferred from annotation absence").put("evaluatedAt",LocalDate.now(java.time.Clock.systemUTC()).toString()).put("validUntil",LocalDate.now(java.time.Clock.systemUTC()).plusDays(1).toString());
        var rows=pm4.putArray("records");var bp3=mapper.createObjectNode().put("schemaVersion",Bp3ProteinRegionAssessment.SCHEMA)
                .put("coordinateSystem","REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE").put("dataClass","SYNTHETIC_TEST_ONLY");var bpRows=bp3.putArray("records");
        for(int i=0;i<2;i++) {
            var model=business.models.get(i);var mapped=uniprot.mapModel(model,3,3);if(!"READY".equals(mapped.path("status").asText()))throw new IllegalStateException(mapped.toPrettyString());
            var record=rows.addObject();for(String k:List.of("hgncId","transcript","protein"))record.set(k,model.path(k));
            record.set("proteinSequenceSha256WithoutStop",mapped.path("proteinSequenceSha256WithoutStop"));record.set("uniProtRawSha256",mapped.at("/source/rawSha256"));
            record.putArray("intervals").addObject().put("start",1).put("end",6).put("repeatStatus","NON_REPEAT")
                    .put("basis","Invented independent non-repeat coverage").putArray("sourceLocators").add("https://example.test/SYNTHETIC/non-repeat");
            var helper=new Bp3ProteinRegionAssessmentTest();helper.model=model;helper.annotations=mapped;helper.extra=combined.resolveForBp3(mapped);
            helper.context=mapper.createObjectNode();helper.context.putObject("gene").set("hgncId",model.path("hgncId"));helper.context.putObject("disease").set("mondoId",choices.get(i).path("syntheticHypothesisMondoId"));helper.context.set("moi",choices.get(i).path("syntheticHypothesisMoi"));
            var bp=(ObjectNode)helper.qualified().at("/records/0");bp.withObject("review").put("evaluatedAt",LocalDate.now(java.time.Clock.systemUTC()).toString()).put("validUntil",LocalDate.now(java.time.Clock.systemUTC()).plusDays(1).toString());
            ((ObjectNode)bp.at("/intervals/0")).put("start",1).put("end",6);bpRows.add(bp);
        }
        pin("pm4-reviewed-repeat-assertions",write("pm4-region-review.json",pm4));write("bp3-region-review.json",bp3);
    }
}
