package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.nbscn.varvis.variantanalysis.trio.*;

/** Explicit fictional issuance, before loading. Never modifies a loaded bundle or old fixture. */
final class Pm6TestMaterials {
    static final ObjectMapper M = new ObjectMapper();
    static final RuntimeEvidenceV2Adapter A = new RuntimeEvidenceV2Adapter(M);
    static ObjectNode at(JsonNode n, String pointer) { return (ObjectNode) n.at(pointer); }
    static ObjectNode facts(ObjectNode b) { return at(b, "/observations/0/facts"); }
    static ObjectNode basis(ObjectNode b) { return at(b, "/observations/0/parentageBasis"); }
    static ObjectNode resource(String name) throws Exception {
        try (var in = Pm6TestMaterials.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name); return (ObjectNode) M.readTree(in);
        }
    }
    static ObjectNode base(String background) throws Exception { return resource("/pm6-denovo-core/" + background + "-bundle.json"); }
    static String hash(JsonNode n) { return A.canonicalSha256(n); }
    static String raw(byte[] b) { return FamilyDeNovoJson.sha256(b); }
    static void source(ObjectNode value) throws Exception {
        ObjectNode projection = value.deepCopy(); projection.remove("sources");
        for (JsonNode s : value.path("sources")) {
            String bytes = M.writerWithDefaultPrettyPrinter().writeValueAsString(projection);
            ((ObjectNode) s).put("rawUtf8", bytes).put("rawSha256", raw(bytes.getBytes(StandardCharsets.UTF_8))).put("canonicalSha256", hash(projection));
        }
    }
    static void pinCatalog(ObjectNode b) {
        for (JsonNode e : b.at("/catalog/entries")) at(e,"/policyBinding").put("policySha256",hash(b.path("policy")));
        b.put("catalogSha256",hash(b.path("catalog")));
    }
    static void pinReview(ObjectNode b) {
        ObjectNode f=facts(b), basis=basis(b), a=at(b,"/observations/0/assessment");
        a.put("factsSha256",hash(f)).put("parentageBasisSha256",hash(basis)); a.remove("contentSha256");a.put("contentSha256",hash(a));
        at(b,"/policy").put("factsSha256",hash(f)).put("parentageBasisSha256",hash(basis)).put("assessmentSha256",hash(a));pinCatalog(b);
    }
    static void seal(ObjectNode b) throws Exception {
        source(facts(b));basis(b).put("factsSha256",hash(facts(b)));source(basis(b));pinReview(b);
    }
    static void genotype(ObjectNode b,int i,String gt,String call,String ad) {
        at(facts(b),"/records/"+i+"/observation").put("genotype",gt).put("call",call).put("alleleDepth",ad).put("phased",gt.contains("|"));
        at(facts(b),"/records/"+i).put("rawSampleValue",gt+":40:60:"+ad);
    }
    static void relation(ObjectNode b,int i,String conclusion) {
        at(basis(b),"/relations/"+i).put("conclusion",conclusion);
        at(facts(b),"/parentage/"+i).put("status","ASSUMED".equals(conclusion)?"UNCONFIRMED":conclusion);
    }
    static ObjectNode create(String background) throws Exception {
        ObjectNode b=resource("/ps2-denovo-core/r1/"+background+"-bundle.json");
        b.put("schemaVersion","pm6-denovo-core-bundle-v1");
        ObjectNode f=facts(b);f.put("observationVersion","TEST_ONLY_PM6_1");
        // Materialize real atomic records and run the existing adapter BEFORE issuing facts/report/pins.
        List<TrioVcfGenotypeRecord> records=new ArrayList<>();
        for(JsonNode r:f.path("records")) {
            JsonNode k=r.path("variantKey"),g=r.path("observation");TrioRole role=TrioRole.valueOf(r.path("role").asText());
            records.add(new TrioVcfGenotypeRecord(new TrioVcfVariantKey(k.path("assembly").asText(),k.path("chromosome").asText(),k.path("position").asLong(),k.path("reference").asText(),k.path("alternate").asText()),role,r.path("sampleId").asText(),r.path("geneSymbol").asText(),r.path("recordFilter").asText(),r.path("sampleFilter").asText(),r.path("rawSampleValue").asText(),new TrioGenotypeObservation(role,g.path("genotype").asText(),TrioGenotypeCall.valueOf(g.path("call").asText()),g.path("depth").asInt(),g.path("genotypeQuality").asInt(),g.path("alleleDepth").asText(),g.path("phased").asBoolean())));
        }
        f=new CaseFamilyObservationAdapter(M).adapt(f,records).snapshot();at(b,"/observations/0").set("facts",f);
        for(JsonNode p:f.path("parentage"))((ObjectNode)p).put("status","UNCONFIRMED").put("method","TEST_ONLY completed limited parentage-related assay; relationship reviewed as assumed, not confirmed");
        for(JsonNode s:f.path("sources"))((ObjectNode)s).put("version","TEST_ONLY_PM6_1");
        ObjectNode basis=at(b,"/observations/0").putObject("parentageBasis").put("schemaVersion","pm6-parentage-basis-v1");basis.set("binding",f.path("binding").deepCopy());
        var relations=basis.putArray("relations");
        String sourceId="TEST_ONLY_PM6_PARENTAGE_"+background;
        JsonNode proband=f.at("/members/0");
        for(JsonNode p:f.path("parentage")) {
            JsonNode member=null;for(JsonNode m:f.path("members"))if(m.path("role").equals(p.path("parentRole")))member=m;
            if(member==null)throw new IllegalStateException("parent missing");
            ObjectNode r=relations.addObject();r.set("binding",f.path("binding").deepCopy());
            r.put("parentRole",p.path("parentRole").asText()).put("parentMemberId",member.path("memberId").asText()).put("parentSampleId",member.path("sampleId").asText()).put("probandMemberId",proband.path("memberId").asText()).put("probandSampleId",proband.path("sampleId").asText());
            r.put("testStatus","COMPLETED").put("method","TEST_ONLY fictional targeted relationship-marker assay; not target-variant genotype inference").put("result","TEST_ONLY recorded marker observations compatible with the stated relationship within this fictional assay scope").put("limitations","TEST_ONLY limited marker panel; does not establish confirmed parentage; no real patients or instrument").put("conclusion","ASSUMED");
            r.putArray("sourceIds").add(sourceId);
            r.putObject("review").put("status","REVIEWED").put("reviewer","TEST_ONLY PM6 relationship reviewer").put("date","2026-09-28T00:00:00Z").put("basis","TEST_ONLY reviewed the completed assay, result and limitations for these exact members; explicitly ASSUMED, not UNKNOWN and not professional approval").putArray("sourceIds").add(sourceId);
        }
        basis.putArray("sources").addObject().put("sourceId",sourceId).put("recordId",sourceId+":report").put("version","1").put("locator","TEST_ONLY all relation facts and review sections").put("acquiredAt","2026-09-28T00:00:00Z");
        ObjectNode a=at(b,"/observations/0/assessment");a.put("schemaVersion","pm6-case-family-assessment-v1").put("assessmentId","TEST_ONLY_PM6_"+background).put("assessmentVersion","1");
        for(String part:List.of("quality","parentage","clinical","scope"))at(a,"/"+part).put("reviewer","TEST_ONLY PM6 "+part+" reviewer").put("basis","TEST_ONLY new PM6 review binds exact shared facts AND complete parentage sidecar; not a professional approval");
        ObjectNode p=at(b,"/policy");p.put("schemaVersion","pm6-reviewed-denovo-policy-v1").put("policyId","TEST_ONLY_PM6_"+background).put("mode",Pm6DeNovoPolicy.MODE);p.putArray("criterionIds").add(Pm6DeNovoCriterionEvaluator.CRITERION);
        for(JsonNode e:b.at("/catalog/entries")){((ObjectNode)e).put("criterionId",Pm6DeNovoCriterionEvaluator.CRITERION);at(e,"/policyBinding").put("policyId",p.path("policyId").asText());}
        try(var in=Pm6TestMaterials.class.getResourceAsStream(Pm6DeNovoCriterionEvaluator.REGISTRY)){
            if(in==null)throw new IllegalStateException("registry");byte[] bytes=in.readAllBytes();JsonNode registry=M.readTree(bytes);
            at(b,"/registryBinding").put("registryId",registry.path("registryId").asText()).put("registrySha256",raw(bytes)).put("registryClasspathResource",Pm6DeNovoCriterionEvaluator.REGISTRY);
        }
        seal(b);return b;
    }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        for(String bg:List.of("alpha","beta")) {
            Path file=output.resolve(bg+"-bundle.json");if(Files.exists(file))throw new IllegalStateException("Never overwrite issued fixtures");
            ObjectNode b=create(bg);byte[] bytes=M.writerWithDefaultPrettyPrinter().writeValueAsBytes(b);Files.write(file,bytes);
            ObjectNode pins=M.createObjectNode().put("rawSha256",raw(bytes)).put("canonicalSha256",hash(b));Files.write(output.resolve(bg+"-pins.json"),M.writerWithDefaultPrettyPrinter().writeValueAsBytes(pins));
        }
    }
}
