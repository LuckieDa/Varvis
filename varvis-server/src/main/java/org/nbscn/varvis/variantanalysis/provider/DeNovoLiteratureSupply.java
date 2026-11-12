package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;

/** Source supply after selected-source gates; only public citation IDs may leave the machine. */
public final class DeNovoLiteratureSupply {
    public static final String VERSION = "denovo-selected-literature-supply-1.0.0";
    private static final List<String> FILES = List.of("supply_selected_literature.py", "fetch_public_literature.py",
            "register_materials.py", "prepare_literature.py", "literature_allele_identity.py");
    private final ObjectMapper mapper;
    private final Path python, scripts, work, cache;
    private final boolean online;
    private final ExternalProcessRunner runner;
    private final ObjectNode implementation;
    private DeNovoSourceTasks tasks;
    private String retryTask;
    private Duration executionTimeout = Duration.ofSeconds(135);
    private int executions, networkRequests;

    public DeNovoLiteratureSupply(ObjectMapper mapper, Path python, Path scripts, Path work, Path cache, boolean online) throws IOException {
        this(mapper, python, scripts, work, cache, online, new SystemExternalProcessRunner());
    }
    DeNovoLiteratureSupply(ObjectMapper mapper, Path python, Path scripts, Path work, Path cache, boolean online, ExternalProcessRunner runner) throws IOException {
        this.mapper=mapper; this.python=python.toAbsolutePath().normalize(); this.scripts=scripts.toRealPath();
        this.work=work.toAbsolutePath().normalize(); this.cache=cache.toAbsolutePath().normalize(); this.online=online; this.runner=runner;
        implementation=mapper.createObjectNode();
        for (String name:FILES) {
            Path source=this.scripts.resolve(name).toRealPath();
            if (!source.startsWith(this.scripts) || Files.size(source)>131072) throw new IOException("DENOVO_SUPPLY_SCRIPT_INVALID");
            implementation.put(name,FamilyDeNovoJson.sha256(Files.readAllBytes(source)));
        }
    }
    public ObjectNode identity() {
        var result=mapper.createObjectNode().put("version",VERSION).put("publicNetworkEnabled",online)
                .put("scope","EXACT_PINNED_CLINVAR_CITATIONS_ONLY").put("modelCalls",0).put("completeLiteratureSearch",false);
        result.set("implementation",implementation.deepCopy());
        if (tasks != null) result.put("executionMode", "ASYNCHRONOUS").put("taskVersion", DeNovoSourceTasks.VERSION)
                .put("retryPolicy",DeNovoSourceTasks.RETRY_VERSION).put("maximumAttempts",DeNovoSourceTasks.MAX_ATTEMPTS);
        return result;
    }
    /** Production uses this isolated owner; legacy saved bindings remain readable. No task starts here. */
    public DeNovoLiteratureSupply asynchronous() throws IOException {
        return asynchronous(DeNovoSourceTasks.shared(), new DeNovoSourceProcessRunner());
    }
    DeNovoLiteratureSupply asynchronous(DeNovoSourceTasks executor, ExternalProcessRunner owned) throws IOException {
        var result = new DeNovoLiteratureSupply(mapper, python, scripts, work, cache, online, owned);
        result.tasks = Objects.requireNonNull(executor); return result;
    }
    public ObjectNode taskStatus(String id) throws IOException {
        if (tasks == null) throw new IOException("DENOVO_TASK_NOT_CONFIGURED");
        return tasks.read(work.resolve("tasks-v1"), id).status();
    }
    void explicitRetry(String id) {
        if(id==null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("DENOVO_TASK_RETRY_IDENTITY_MISMATCH");
        retryTask=id;
    }
    public int executions() { return executions; }
    public int networkRequests() { return networkRequests; }
    record Supplied(ObjectNode snapshot, Map<String,byte[]> originals, ArrayNode documents, String issue, byte[] rawResult) {}

    synchronized Supplied prepare(ArrayNode targets, ArrayNode records, ArrayNode documents, Map<String,byte[]> originals) {
        try {
            if (targets.size()>50 || records.size()>100 || documents.size()>32) return failed("DENOVO_SUPPLY_REQUEST_LIMIT");
            var request=mapper.createObjectNode().put("schemaVersion","denovo-selected-literature-request-v1");
            request.set("targets",targets); request.set("records",records); request.set("documents",documents);
            request.set("implementation",implementation.deepCopy());
            var encoded=request.putObject("originals");
            for (var entry:new TreeMap<>(originals).entrySet()) encoded.put(entry.getKey(),Base64.getEncoder().encodeToString(entry.getValue()));
            byte[] input=mapper.writeValueAsBytes(request);
            if (input.length>25165824) return failed("DENOVO_SUPPLY_REQUEST_LIMIT");
            // Bind configured implementation before process startup, not only the script-reported hash.
            for (String name:FILES) if (!implementation.path(name).asText().equals(FamilyDeNovoJson.sha256(bounded(scripts.resolve(name),131072))))
                return failed("DENOVO_SUPPLY_IMPLEMENTATION_CHANGED");
            if (tasks != null) {
                var binding = mapper.createObjectNode().put("requestSha256", FamilyDeNovoJson.sha256(input));
                binding.set("supplyIdentity", identity());
                binding.put("cacheLocation", cache.toString()).put("pythonLocation", python.toString());
                // Capture independent values; the pending business snapshot is never changed by a worker.
                ArrayNode frozenTargets = targets.deepCopy(), frozenRecords = records.deepCopy(), frozenDocuments = documents.deepCopy();
                Map<String,byte[]> frozenOriginals = new TreeMap<>(); originals.forEach((k,v) -> frozenOriginals.put(k,v.clone()));
                var read = tasks.prepare(work.resolve("tasks-v1"), mapper.writeValueAsBytes(binding), remaining -> {
                    var worker = new DeNovoLiteratureSupply(mapper,python,scripts,work,cache,online,runner);
                    worker.executionTimeout = remaining.compareTo(Duration.ofSeconds(135))>0 ? Duration.ofSeconds(135) : remaining;
                    if (!implementation.equals(worker.implementation)) throw new DeNovoSourceTasks.SourceFailure("DENOVO_SUPPLY_IMPLEMENTATION_CHANGED");
                    var supplied = worker.prepare(frozenTargets,frozenRecords,frozenDocuments,frozenOriginals);
                    if (supplied.issue()!=null) throw new DeNovoSourceTasks.SourceFailure(supplied.issue());
                    return supplied.rawResult();
                },retryTask);
                if (read.result() == null) {
                    String issue = read.status().path("reasonCode").asText("DENOVO_SUPPLY_PENDING");
                    return new Supplied(read.status().put("reasonCode",issue),Map.of(),mapper.createArrayNode(),issue,null);
                }
                var supplied = validate(input,targets,read.result(),false);
                if (supplied.issue()!=null) return supplied;
                var snapshot = supplied.snapshot().deepCopy(); snapshot.set("sourceTask",read.status());
                return new Supplied(snapshot,supplied.originals(),supplied.documents(),null,supplied.rawResult());
            }
            Files.createDirectories(work); Path task=Files.createTempDirectory(work,"selected-");
            Path requestFile=task.resolve("request.json"), output=task.resolve("result.json");
            Files.write(requestFile,input,StandardOpenOption.CREATE_NEW);
            var command=new ArrayList<>(List.of(python.toString(),"-E","-s","-B",scripts.resolve(FILES.get(0)).toString(),
                    "--request",requestFile.toString(),"--output",output.toString(),"--work",task.resolve("supply").toString(),"--cache",cache.toString()));
            if (online) command.add("--allow-public-network");
            executions++;
            // If an online process cannot return a validated receipt, request count is unknown, not zero.
            if (online) networkRequests=-1;
            var process=runner.run(command,scripts,executionTimeout);
            Files.writeString(task.resolve("process.log"),process.getOutput(),StandardOpenOption.CREATE_NEW);
            if (!process.isSuccess()) return failed(process.isTimedOut()?"DENOVO_SUPPLY_PROCESS_TIMEOUT":"DENOVO_SUPPLY_PROCESS_FAILED");
            return validate(input, targets, bounded(output,25165824),true);
        } catch (IOException | RuntimeException failure) { return failed("DENOVO_SUPPLY_UNAVAILABLE"); }
    }
    private Supplied validate(byte[] input, ArrayNode targets, byte[] raw, boolean recordCalls) throws IOException {
            JsonNode parsed=mapper.readTree(raw);
            if (!(parsed instanceof ObjectNode result) || !VERSION.equals(result.path("schemaVersion").asText())
                    || !FamilyDeNovoJson.sha256(input).equals(result.path("requestSha256").asText())
                    || !implementation.equals(result.path("implementation")) || result.path("modelCalls").asInt(-1)!=0
                    || !"NONE".equals(result.path("clinicalApproval").asText()) || !result.path("documents").isArray()
                    || result.path("documents").size()>32 || !result.path("originals").isObject() || result.path("originals").size()>100
                    || !result.path("targets").isArray() || result.path("targets").size()!=targets.size()) return failed("DENOVO_SUPPLY_RESULT_MISMATCH");
            int calls=result.at("/retrieval/networkRequests").asInt(-1);
            if (calls<0 || calls>8 || (!online && calls!=0)) return failed("DENOVO_SUPPLY_RESULT_MISMATCH");
            if (recordCalls) networkRequests=calls;
            Map<String,byte[]> sources=new TreeMap<>(); int total=0;
            for (var entry:result.path("originals").properties()) {
                byte[] bytes=Base64.getDecoder().decode(entry.getValue().asText()); total+=bytes.length;
                if (bytes.length==0 || bytes.length>1048576 || !entry.getKey().equals(FamilyDeNovoJson.sha256(bytes)) || total>16777216)
                    return failed("DENOVO_SUPPLY_ORIGINAL_INVALID");
                sources.put(entry.getKey(),bytes);
            }
            for (int i=0;i<targets.size();i++) {
                var expected=targets.get(i); var actual=result.path("targets").get(i);
                if (!expected.path("allele").equals(actual.path("allele")) || !expected.path("hgncId").equals(actual.path("hgncId"))
                        || !actual.path("reasonCodes").isArray() || actual.path("reasonCodes").size()>100) return failed("DENOVO_SUPPLY_RESULT_MISMATCH");
                for (var code:actual.path("reasonCodes")) if (!code.isTextual() || !code.asText().matches("[A-Z][A-Z0-9_]{0,160}"))
                    return failed("DENOVO_SUPPLY_RESULT_MISMATCH");
            }
            // The source reader independently checks every document and VCV binding before qualification.
            var snapshot=result.deepCopy(); snapshot.remove("originals");
            byte[] snapshotRaw=mapper.writeValueAsBytes(snapshot);
            if (snapshotRaw.length>1048576 || total+snapshotRaw.length>16777216) return failed("DENOVO_SUPPLY_OUTPUT_LIMIT");
            sources.put(FamilyDeNovoJson.sha256(snapshotRaw),snapshotRaw);
            return new Supplied(snapshot,sources,(ArrayNode) result.path("documents"),null,raw);
    }
    private Supplied failed(String issue) { return new Supplied(mapper.createObjectNode().put("reasonCode",issue),Map.of(),mapper.createArrayNode(),issue,null); }
    private static byte[] bounded(Path file,int maximum) throws IOException {
        try (var input=Files.newInputStream(file)) { byte[] bytes=input.readNBytes(maximum+1); if (bytes.length==0 || bytes.length>maximum) throw new IOException("DENOVO_SUPPLY_FILE_LIMIT"); return bytes; }
    }
}
