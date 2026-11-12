package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded GA4GH v2 original-fact reader. A valid format, author diagnosis or creator
 * identifier is never a qualification, complete cohort, native call or adoption authority.
 * Raw JSON is retained exactly; JSON pointers refer to that original, not a fabricated table.
 */
public final class Pp4PhenopacketReplay {
    public static final String FORMAT="GA4GH_PHENOPACKETS_V2";
    public static final String STANDARD_COMMIT="dd70d53b2c1dfd5ab6ac25b1b4b422b7be90a6ae";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final FamilyDeNovoJson READER=new FamilyDeNovoJson("PP4_PHENOPACKET_");
    private Pp4PhenopacketReplay() { }

    public static ObjectNode implementationIdentity() {
        var value=JSON.createObjectNode().put("format",FORMAT).put("standardRelease","2.0.0")
                .put("standardRepository","https://github.com/ga4gh/phenopacket-schema").put("standardCommit",STANDARD_COMMIT)
                .put("readerScope","PHENOPACKET_FAMILY_COHORT_FACTS_AND_EXPLICIT_FILE_SAMPLE_LINKS")
                .put("clinicalAuthority",false).put("interpretationRecalculated",false).put("fullSchemaValidationPerformed",false)
                .put("acceptedJsonSubset","CANONICAL_CAMEL_CASE_AND_NAMED_ENUMS_ONLY_KNOWN_V2_0_FIELDS");
        value.putObject("normativeOriginalSha256s")
                .put("phenopackets.proto","b752adb33cfb66fee76f9db0fe1626fee70afda6288083851f87ef22579da703")
                .put("core/base.proto","23665d0a109059279ee0b3dbbe86b827ecf914b1340f637cdcacbc37f190d4c0")
                .put("core/phenotypic_feature.proto","806cb211b9534074e15156051b0ac478f6cfdbdbfdafd793c12d6f766a0d4942")
                .put("core/interpretation.proto","6cb376f0b32fd920439d7d694319c7845fa3da14341d9346971e6bd0949e16fd")
                .put("core/disease.proto","3286cd5234faeaff069598481a194603b5e9c8d89b5616cfdd04b49d83bc5dd3");
        return value;
    }

    public static Replay read(byte[] raw,String rawSha256,String kind) throws IOException {
        if(raw==null) throw new IOException("PP4_PHENOPACKET_ORIGINAL_PIN_MISMATCH");
        need(raw.length>0 && raw.length<=1048576 && Pp4ProductionMethodRegistration.hash(rawSha256)
                && rawSha256.equals(Pp4ProductionMethodRegistration.rawSha(raw)),"ORIGINAL_PIN_MISMATCH");
        byte[] original=raw.clone(); ObjectNode document;
        try { document=READER.parse(JSON,original); }
        catch(IllegalArgumentException invalid) { throw new IOException("PP4_PHENOPACKET_JSON_INVALID",invalid); }
        need(Set.of("PHENOPACKET","FAMILY","COHORT").contains(kind),"DOCUMENT_KIND_UNSUPPORTED");
        var facts=JSON.createObjectNode().put("schemaVersion","pp4-phenopacket-original-facts-v1")
                .put("format",FORMAT).put("kind",kind).put("rawSha256",rawSha256).put("recordId",text(document,"id"))
                .put("clinicalAuthority",false).put("cohortCompletenessProven",false).put("clinicalExaminationAgeInferred",false);
        facts.set("readerIdentity",implementationIdentity()); metadata(document.path("metaData"),"/metaData",facts);
        var packets=facts.putArray("packets"); var mappings=facts.putArray("sampleAssociations");
        var subjects=new HashSet<String>(); var biosamples=new HashSet<String>(); var packetIds=new HashSet<String>();
        if(kind.equals("PHENOPACKET")) packet(document,"",facts,subjects,biosamples,packetIds);
        else if(kind.equals("FAMILY")) {
            need(document.path("proband").isObject(),"PROBAND_REQUIRED"); packet(document.path("proband"),"/proband",facts,subjects,biosamples,packetIds);
            int index=0; for(var relative:array(document,"relatives")) packet(relative,"/relatives/"+index++,facts,subjects,biosamples,packetIds);
            // Pedigree/consanguinity are preserved as source assertions, not independently verified parentage.
            if(document.has("pedigree")) facts.putObject("pedigreeSource").put("jsonPointer","/pedigree");
        } else {
            int index=0; for(var member:array(document,"members")) packet(member,"/members/"+index++,facts,subjects,biosamples,packetIds);
            need(!packets.isEmpty(),"COHORT_MEMBERS_REQUIRED");
        }
        if(!kind.equals("PHENOPACKET")) files(document,"",facts,subjects,biosamples,null,null);
        need(packets.size()<=256 && mappings.size()<=1024,"RECORD_CAPACITY_UNSUPPORTED");
        var seen=new HashSet<String>();
        for(var link:mappings) {
            String key=link.path("subjectId").asText()+"\n"+link.path("biosampleId").asText()+"\n"+link.path("fileUri").asText();
            need(seen.add(key),"SAMPLE_ASSOCIATION_AMBIGUOUS");
        }
        facts.put("factsSha256",Pp4ProductionMethodRegistration.canonical(facts));
        return new Replay(original,facts);
    }

    public static final class Replay {
        private final byte[] original; private final ObjectNode facts;
        @JsonCreator(mode=JsonCreator.Mode.DISABLED)
        private Replay(byte[] original,ObjectNode facts) { this.original=original.clone();this.facts=facts.deepCopy(); }
        public byte[] originalBytes() { return original.clone(); }
        public ObjectNode snapshot() { return facts.deepCopy(); }
        /** Minimal display facts. Consumers must match the saved attempt/context separately.
         * This cannot issue a qualification, resolve identity namespaces, or calculate a contribution.
         */
        public ObjectNode detailFacts() {
            var value=JSON.createObjectNode().put("schemaVersion","pp4-native-clinical-details-v1");
            value.set("source",sourceIdentity());
            var members=value.putArray("members");
            for(var packet:facts.path("packets")) {
                var member=members.addObject();
                for(String field:List.of("subjectId","packetId","jsonPointer","declaredSex","lastEncounterAgeSource","phenotypicFeatures","authorDiseaseAssertions","interpretations"))
                    if(packet.has(field)) member.set(field,packet.path(field).deepCopy());
                member.set("biosamples",packet.path("biosamples").deepCopy());
                member.put("identityBindingStatus","SOURCE_NAMESPACE_ONLY_REQUIRES_SAME_ATTEMPT_BINDING")
                        .put("clinicalAssessmentScope","ONLY_EXPLICIT_SOURCE_FEATURES_NOT_COMPLETE_EXAMINATION")
                        .put("featureAssessmentAgeStatus","UNKNOWN").put("clinicalDiagnosisVerified",false);
            }
            var mappings=value.putArray("sampleAssociations");
            for(var link:facts.path("sampleAssociations")) {
                var displayed=((ObjectNode)link).deepCopy();displayed.remove("fileUri");
                // The original URI remains in archived raw facts; a public detail need not expose a host path.
                displayed.put("recordedFileUriSha256",Pp4ProductionMethodRegistration.rawSha(link.path("fileUri").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                mappings.add(displayed);
            }
            value.putObject("qualificationLimits").put("clinicalAuthority",false).put("completeTestedCohortProven",false)
                    .put("clinicalQualificationIssued",false).put("contributionKnownFromFormat",false);
            return value;
        }
        public ObjectNode sourceIdentity() {
            var value=JSON.createObjectNode();
            for(String field:List.of("format","kind","recordId","creator","created","declaredSchemaVersion","rawSha256","factsSha256","readerIdentity")) value.set(field,facts.path(field).deepCopy());
            value.put("clinicalAuthority",false);return value;
        }
    }
    private static void metadata(JsonNode value,String pointer,ObjectNode output) throws IOException {
        need(value.isObject(),"METADATA_REQUIRED"); String version=text(value,"phenopacketSchemaVersion");
        need(version.matches("2\\.0(?:\\.[0-9]+)?"),"SCHEMA_VERSION_UNSUPPORTED");
        String created=text(value,"created"); try { Instant.parse(created); } catch(DateTimeException e) { throw new IOException("PP4_PHENOPACKET_CREATED_INVALID",e); }
        output.put("creator",text(value,"createdBy")).put("created",created).put("declaredSchemaVersion",version).put("metadataPointer",pointer);
        need(value.path("resources").isArray() && !value.path("resources").isEmpty(),"RESOURCE_IDENTITIES_REQUIRED");
        var resources=output.putArray("resourceIdentities"); var ids=new HashSet<String>();
        for(var resource:value.path("resources")) {
            String id=text(resource,"id");need(ids.add(id),"RESOURCE_ID_AMBIGUOUS");
            var row=resources.addObject().put("id",id).put("name",text(resource,"name"));
            for(String key:List.of("url","version","namespacePrefix","iriPrefix")) if(resource.has(key)) row.put(key,text(resource,key));
        }
        if(value.has("externalReferences")) output.putObject("externalReferencesSource").put("jsonPointer",pointer+"/externalReferences");
    }
    private static void packet(JsonNode packet,String pointer,ObjectNode facts,Set<String> subjects,Set<String> allBiosamples,Set<String> packetIds) throws IOException {
        need(packet.isObject(),"PACKET_REQUIRED");String packetId=text(packet,"id"),subject=text(packet.path("subject"),"id");
        need(packetIds.add(packetId) && subjects.add(subject) && !allBiosamples.contains(subject),"SUBJECT_OR_PACKET_AMBIGUOUS");
        var row=facts.withArray("packets").addObject().put("packetId",packetId).put("subjectId",subject).put("jsonPointer",pointer);
        metadata(packet.path("metaData"),pointer+"/metaData",row);
        if(packet.path("subject").has("sex")) row.put("declaredSex",text(packet.path("subject"),"sex"));
        if(packet.path("subject").has("timeAtLastEncounter")) {
            row.putObject("lastEncounterAgeSource").put("jsonPointer",pointer+"/subject/timeAtLastEncounter")
                    .put("semanticRole","LAST_ENCOUNTER_NOT_FEATURE_ASSESSMENT_OR_ONSET")
                    .set("recordedTimeElement",packet.at("/subject/timeAtLastEncounter").deepCopy());
        }
        var observed=row.putArray("phenotypicFeatures");int index=0;
        for(var feature:array(packet,"phenotypicFeatures")) {
            String path=pointer+"/phenotypicFeatures/"+index++;
            need(!feature.has("excluded") || feature.path("excluded").isBoolean(),"EXCLUDED_BOOLEAN_REQUIRED");
            var fact=observed.addObject().put("featureId",text(feature.path("type"),"id"))
                    .put("presence",feature.path("excluded").asBoolean(false)?"ABSENT":"PRESENT")
                    .put("presenceBasis",feature.has("excluded")?"EXPLICIT_EXCLUDED_FIELD":"PROTO3_EXCLUDED_DEFAULT_FALSE_FOR_LISTED_FEATURE")
                    .put("jsonPointer",path).put("assessmentAgeKnown",false).put("examinationMethodKnown",false);
            if(feature.path("type").has("label")) fact.put("label",text(feature.path("type"),"label"));
            for(String key:List.of("onset","resolution","evidence","modifiers","severity"))
                if(feature.has(key)) fact.putObject(key+"Source").put("jsonPointer",path+"/"+key).set("recordedValue",feature.path(key).deepCopy());
        }
        var diseases=row.putArray("authorDiseaseAssertions");index=0;
        for(var disease:array(packet,"diseases")) {
            need(!disease.has("excluded") || disease.path("excluded").isBoolean(),"DISEASE_EXCLUDED_BOOLEAN_REQUIRED");
            var assertion=diseases.addObject().put("diseaseId",text(disease.path("term"),"id"))
                    .put("presence",disease.path("excluded").asBoolean(false)?"ABSENT":"PRESENT")
                    .put("jsonPointer",pointer+"/diseases/"+index++).put("clinicalDiagnosisVerified",false);
            if(disease.path("term").has("label")) assertion.put("label",text(disease.path("term"),"label"));
            for(String key:List.of("onset","resolution")) if(disease.has(key)) assertion.putObject(key+"Source")
                    .put("jsonPointer",assertion.path("jsonPointer").asText()+"/"+key).set("recordedValue",disease.path(key).deepCopy());
        }
        var sampleIds=new HashSet<String>(); var samples=row.putArray("biosamples"); index=0;
        for(var sample:array(packet,"biosamples")) {
            String id=text(sample,"id"),path=pointer+"/biosamples/"+index++;
            need(!subjects.contains(id) && sampleIds.add(id) && allBiosamples.add(id),"BIOSAMPLE_ID_AMBIGUOUS");
            need(!sample.has("individualId") || subject.equals(text(sample,"individualId")),"BIOSAMPLE_SUBJECT_MISMATCH");
            samples.addObject().put("id",id).put("subjectId",subject).put("jsonPointer",path);
            files(sample,path,facts,Set.of(subject),Set.of(id),subject,id);
        }
        files(packet,pointer,facts,Set.of(subject),sampleIds,subject,null);
        var interpretations=row.putArray("interpretations");index=0;
        for(var interpretation:array(packet,"interpretations")) {
            String path=pointer+"/interpretations/"+index++;var entry=interpretations.addObject().put("id",text(interpretation,"id")).put("jsonPointer",path);
            String progress=interpretation.path("progressStatus").asText("UNKNOWN_PROGRESS");
            need(Set.of("UNKNOWN_PROGRESS","IN_PROGRESS","COMPLETED","SOLVED","UNSOLVED").contains(progress),"PROGRESS_STATUS_UNSUPPORTED");
            entry.put("authorProgressStatus",progress).put("clinicalDiagnosisVerified",false);
            if(interpretation.path("diagnosis").has("disease")) entry.put("authorDiseaseId",text(interpretation.at("/diagnosis/disease"),"id"));
            var variants=entry.putArray("genomicInterpretations");int genomicIndex=0;
            for(var genomic:array(interpretation.path("diagnosis"),"genomicInterpretations")) {
                String owner=text(genomic,"subjectOrBiosampleId");need(owner.equals(subject)||sampleIds.contains(owner),"GENOMIC_SUBJECT_SCOPE_MISMATCH");
                String status=genomic.path("interpretationStatus").asText("UNKNOWN_STATUS");
                need(Set.of("UNKNOWN_STATUS","REJECTED","CANDIDATE","CONTRIBUTORY","CAUSATIVE").contains(status),"GENOMIC_STATUS_UNSUPPORTED");
                var variant=variants.addObject().put("subjectOrBiosampleId",owner).put("authorInterpretationStatus",status)
                        .put("jsonPointer",path+"/diagnosis/genomicInterpretations/"+genomicIndex++);
                need(!(genomic.has("gene")&&genomic.has("variantInterpretation")),"GENOMIC_ONEOF_CONFLICT");
                if(genomic.has("variantInterpretation")) {
                    String classification=genomic.path("variantInterpretation").path("acmgPathogenicityClassification").asText("NOT_PROVIDED");
                    need(Set.of("NOT_PROVIDED","BENIGN","LIKELY_BENIGN","UNCERTAIN_SIGNIFICANCE","LIKELY_PATHOGENIC","PATHOGENIC").contains(classification),"CLASSIFICATION_UNSUPPORTED");
                    variant.put("authorAcmgClassification",classification).put("identityResolutionPerformed",false);
                }
            }
        }
        need(facts.path("packets").size()<=256,"RECORD_CAPACITY_UNSUPPORTED");
    }
    private static void files(JsonNode owner,String pointer,ObjectNode facts,Set<String> subjects,Set<String> samples,String knownSubject,String knownSample) throws IOException {
        int index=0;
        for(var file:array(owner,"files")) {
            String path=pointer+"/files/"+index++,uri=text(file,"uri");
            try { need(URI.create(uri).isAbsolute(),"FILE_URI_INVALID"); } catch(IllegalArgumentException e) { throw new IOException("PP4_PHENOPACKET_FILE_URI_INVALID",e); }
            if(!file.has("individualToFileIdentifiers")) continue;
            need(file.path("individualToFileIdentifiers").isObject(),"FILE_IDENTIFIERS_REQUIRED");
            var mappings=file.path("individualToFileIdentifiers").properties().iterator();
            while(mappings.hasNext()) {
                var mapping=mappings.next();String id=mapping.getKey();need(subjects.contains(id)||samples.contains(id),"FILE_SUBJECT_SCOPE_MISMATCH");
                need(mapping.getValue().isTextual()&&valid(mapping.getValue().asText()),"FILE_SAMPLE_ID_REQUIRED");
                String subject=subjects.contains(id)?id:knownSubject;String sample=samples.contains(id)?id:knownSample;
                if(subject==null) {
                    for(var packet:facts.path("packets")) for(var bio:packet.path("biosamples")) if(id.equals(bio.path("id").asText())) subject=packet.path("subjectId").asText();
                }
                need(subject!=null,"FILE_SUBJECT_SCOPE_MISMATCH");
                var link=facts.withArray("sampleAssociations").addObject().put("subjectId",subject).put("fileUri",uri)
                        .put("vcfSampleId",mapping.getValue().asText()).put("jsonPointer",path+"/individualToFileIdentifiers/"+escape(id));
                if(sample==null) link.putNull("biosampleId");else link.put("biosampleId",sample);
                for(String key:List.of("genomeAssembly","fileFormat","htsFormat")) if(file.path("fileAttributes").has(key)) link.put(key,text(file.path("fileAttributes"),key));
                link.put("nativeFileVerified",false).put("sampleOrdinalInferred",false).put("clinicalQualificationIssued",false);
            }
        }
    }
    /** URI comparison only; this never follows a URI or opens a patient file. */
    public static String localFileIdentitySha256(String uri) throws IOException {
        try {
            URI value=URI.create(uri);need("file".equals(value.getScheme())&&(value.getHost()==null||value.getHost().isEmpty())
                    &&value.getFragment()==null&&value.getRawQuery()==null,"LOCAL_NATIVE_FILE_URI_REQUIRED");
            Path path=Path.of(value);need(path.isAbsolute(),"LOCAL_NATIVE_FILE_URI_REQUIRED");
            return Pp4ProductionMethodRegistration.rawSha(path.toAbsolutePath().normalize().toUri().toASCIIString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch(IllegalArgumentException e) { throw new IOException("PP4_PHENOPACKET_LOCAL_NATIVE_FILE_URI_REQUIRED",e); }
    }
    private static Iterable<JsonNode> array(JsonNode owner,String field) throws IOException {
        if(!owner.has(field)) return List.of();need(owner.path(field).isArray(),"ARRAY_REQUIRED");need(owner.path(field).size()<=1024,"RECORD_CAPACITY_UNSUPPORTED");return owner.path(field);
    }
    private static String text(JsonNode owner,String field) throws IOException { need(owner.path(field).isTextual()&&valid(owner.path(field).asText()),"TEXT_IDENTITY_REQUIRED");return owner.path(field).asText(); }
    private static boolean valid(String text) { return !text.isBlank()&&text.length()<=4096&&text.codePoints().noneMatch(Character::isISOControl); }
    private static String escape(String value) { return value.replace("~","~0").replace("/","~1"); }
    private static void need(boolean condition,String code) throws IOException { if(!condition) throw new IOException("PP4_PHENOPACKET_"+code); }
}
