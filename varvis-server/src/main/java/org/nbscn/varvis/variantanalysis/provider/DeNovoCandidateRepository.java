package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Public source snapshots only. Acceptance neither changes policy nor scores a case. */
public final class DeNovoCandidateRepository {
    public static final String VERSION="denovo-candidate-sources-v1";
    private static final int LIMIT=16777216;
    private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final Path root;
    public DeNovoCandidateRepository(Path root) {this.root=root.toAbsolutePath().normalize();}
    public record Snapshot(String sha256,ObjectNode artifacts,ArrayNode entries) {}
    private ObjectNode index() throws IOException {
        if(!Files.exists(root.resolve("index.json"))) {
            var empty=json.createObjectNode().put("schemaVersion",VERSION);empty.putArray("entries");return empty;
        }
        var value=parse(read(root.resolve("index.json"),65536));
        if(!VERSION.equals(value.path("schemaVersion").asText()) || !value.path("entries").isArray() || value.path("entries").size()>20)
            throw new IOException("DENOVO_CANDIDATE_INDEX_INVALID");
        return value;
    }
    public Snapshot snapshot() throws IOException {
        var value=index();var artifacts=json.createObjectNode();var seen=new HashSet<String>();long total=0;
        for(var entry:value.path("entries")) {
            String pin=entry.path("intakeSha256").asText(),paper=entry.path("pmcid").asText();
            if(!pin.matches("[a-f0-9]{64}") || !paper.matches("PMC[1-9][0-9]*") || !seen.add(paper))
                throw new IOException("DENOVO_CANDIDATE_INDEX_INVALID");
            Path path=root.resolve(pin).resolve("intake.json").toRealPath();
            if(!path.startsWith(root.toRealPath())) throw new IOException("DENOVO_CANDIDATE_PATH_INVALID");
            byte[] raw=read(path,LIMIT);total+=raw.length;
            if(total>25165824) throw new IOException("DENOVO_CANDIDATE_TOTAL_SIZE_LIMIT");
            if(!pin.equals(FamilyDeNovoJson.sha256(raw))) throw new IOException("DENOVO_CANDIDATE_PIN_MISMATCH");
            var intake=parse(raw);verify(intake);
            if(!paper.equals(intake.path("pmcid").asText()) || !entry.path("sourceSha256").equals(intake.path("sourceSha256"))
                    || !entry.path("bundleSha256").equals(intake.path("bundleSha256"))) throw new IOException("DENOVO_CANDIDATE_SOURCE_MISMATCH");
            if(artifacts.has(intake.path("sourceSha256").asText())) throw new IOException("DENOVO_CANDIDATE_DUPLICATE_SOURCE");
            artifacts.set(intake.path("sourceSha256").asText(),intake);
        }
        return new Snapshot(FamilyDeNovoJson.sha256(json.writeValueAsBytes(value)),artifacts,(ArrayNode)value.path("entries"));
    }
    /** Called only after exact Python source replay. Monotonic targets avoid stale/parallel overwrite. */
    synchronized ObjectNode accept(byte[] raw,ObjectNode check,String taskId) throws IOException {
        var intake=parse(raw);verify(intake);String pin=FamilyDeNovoJson.sha256(raw);
        if(!pin.equals(check.path("intakeSha256").asText()) || !"SOURCE_REPLAYED_NOT_SCORED".equals(check.path("status").asText())
                || !"REAL_PROVIDER".equals(check.path("transportKind").asText()) || check.path("modelCalls").asInt(-1)!=0
                || check.path("networkCalls").asInt(-1)!=0 || !taskId.matches("[a-f0-9]{64}"))
            throw new IOException("DENOVO_CANDIDATE_SOURCE_CHECK_REQUIRED");
        for(String field:List.of("pmcid","sourceSha256","bundleSha256")) if(!check.path(field).equals(intake.path(field)))
            throw new IOException("DENOVO_CANDIDATE_SOURCE_MISMATCH");
        Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("accept.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock held;
            try {held=channel.tryLock();} catch(java.nio.channels.OverlappingFileLockException busy) {throw new IOException("DENOVO_CANDIDATE_ACCEPT_BUSY");}
            if(held==null) throw new IOException("DENOVO_CANDIDATE_ACCEPT_BUSY");
            try(held) {
                var previous=snapshot();JsonNode old=null;
                for(var row:previous.entries()) if(row.path("pmcid").equals(intake.path("pmcid"))) old=row;
                if(old!=null) {
                    if(pin.equals(old.path("intakeSha256").asText())) return receipt(pin,previous.sha256(),true);
                    var before=previous.artifacts().path(old.path("sourceSha256").asText());
                    cumulative(before,intake);
                } else if(previous.entries().size()>=20) throw new IOException("DENOVO_CANDIDATE_PAPER_LIMIT");
                long total=raw.length;
                for(var artifact:previous.artifacts()) if(!artifact.path("pmcid").equals(intake.path("pmcid"))) total+=json.writeValueAsBytes(artifact).length;
                if(total>25165824) throw new IOException("DENOVO_CANDIDATE_TOTAL_SIZE_LIMIT");
                Path folder=root.resolve(pin);Files.createDirectories(folder);
                if(!folder.toRealPath().startsWith(root.toRealPath())) throw new IOException("DENOVO_CANDIDATE_PATH_INVALID");
                writeOnce(folder.resolve("intake.json"),raw);
                writeOnce(folder.resolve("source-check.json"),json.writeValueAsBytes(check));
                var value=json.createObjectNode().put("schemaVersion",VERSION);var entries=value.putArray("entries");
                for(var row:previous.entries()) if(!row.path("pmcid").equals(intake.path("pmcid"))) entries.add(row);
                var row=entries.addObject().put("intakeSha256",pin).put("taskId",taskId).put("clinicalAdoption","NONE");
                for(String field:List.of("pmcid","sourceSha256","bundleSha256")) row.set(field,intake.path(field));
                byte[] bytes=json.writeValueAsBytes(value);Path staged=Files.createTempFile(root,"index-",".tmp");
                Files.write(staged,bytes);Files.move(staged,root.resolve("index.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                return receipt(pin,FamilyDeNovoJson.sha256(bytes),false);
            }
        }
    }
    static void cumulative(JsonNode old,JsonNode next) throws IOException {
        if(!old.path("bundleSha256").equals(next.path("bundleSha256")) || !old.path("sourceSha256").equals(next.path("sourceSha256")))
            throw new IOException("DENOVO_CANDIDATE_SOURCE_VERSION_CHANGED");
        for(var review:old.path("sourceReviews")) {
            boolean found=false;
            for(var fresh:next.path("sourceReviews")) if(review.equals(fresh)) found=true;
            if(!found) throw new IOException("DENOVO_CANDIDATE_SOURCE_REVIEW_CHANGED_OR_REMOVED");
        }
        for(var artifact:old.path("artifacts")) {
            boolean found=false;
            for(var fresh:next.path("artifacts")) if(artifact.path("binding").path("chunkIndex").equals(fresh.path("binding").path("chunkIndex"))) {
                if(!artifact.equals(fresh)) throw new IOException("DENOVO_CANDIDATE_TARGET_CONFLICT");
                found=true;
            }
            if(!found) throw new IOException("DENOVO_CANDIDATE_STALE_SNAPSHOT");
        }
    }
    static byte[] source(JsonNode intake) throws IOException {
        byte[] found=null;String pin=intake.path("sourceSha256").asText();
        for(var file:intake.path("files")) if(pin.equals(file.path("sha256").asText())) {
            byte[] bytes;
            try {bytes=Base64.getDecoder().decode(file.path("base64").asText());}
            catch(IllegalArgumentException invalid) {throw new IOException("DENOVO_CANDIDATE_SOURCE_INVALID");}
            if(!pin.equals(FamilyDeNovoJson.sha256(bytes)) || found!=null&&!Arrays.equals(found,bytes))
                throw new IOException("DENOVO_CANDIDATE_SOURCE_MISMATCH");
            found=bytes;
        }
        if(found==null) throw new IOException("DENOVO_CANDIDATE_SOURCE_MISSING");return found;
    }
    private void verify(ObjectNode intake) throws IOException {
        if(!"denovo-bundle-candidate-intake-v1".equals(intake.path("schemaVersion").asText())
                || !intake.path("pmcid").asText().matches("PMC[1-9][0-9]*")
                || !intake.path("sourceSha256").asText().matches("[a-f0-9]{64}")
                || !intake.path("bundleSha256").asText().matches("[a-f0-9]{64}")
                || !intake.path("artifacts").isArray() || intake.path("artifacts").isEmpty() || intake.path("artifacts").size()>64)
            throw new IOException("DENOVO_CANDIDATE_INTAKE_INVALID");
        source(intake);
    }
    private ObjectNode receipt(String pin,String index,boolean reused) {
        return json.createObjectNode().put("schemaVersion",VERSION).put("status","ACCEPTED_FOR_QUALIFICATION_NOT_SCORED")
                .put("intakeSha256",pin).put("snapshotSha256",index).put("reused",reused).put("modelCalls",0).put("scientificExecution",false);
    }
    private void writeOnce(Path path,byte[] bytes) throws IOException {
        if(Files.exists(path)) {if(!Arrays.equals(read(path,LIMIT),bytes)) throw new IOException("DENOVO_CANDIDATE_IMMUTABLE_CONFLICT");}
        else Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
    }
    private ObjectNode parse(byte[] bytes) throws IOException {
        var factory=new com.fasterxml.jackson.core.JsonFactory();factory.setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(LIMIT).build());
        try(var parser=factory.createParser(bytes)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var node=json.readTree(parser);if(!(node instanceof ObjectNode object)||parser.nextToken()!=null) throw new IOException("DENOVO_CANDIDATE_JSON_INVALID");return object;
        }
    }
    private static byte[] read(Path path,int limit) throws IOException {
        try(var in=Files.newInputStream(path)){byte[] raw=in.readNBytes(limit+1);if(raw.length==0||raw.length>limit) throw new IOException("DENOVO_CANDIDATE_SIZE_LIMIT");return raw;}
    }
}
