package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fake process outputs test the supplier trust boundary; no network/model or real clinical facts. */
class DeNovoLiteratureSupplyTest {
    @TempDir Path temp;
    final ObjectMapper m=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"ok","timeout","nonzero","wrong-request","wrong-target","wrong-pin","network-disabled","bad-reason"})
    void rejectsUnboundProcessOutputs(String mode) throws Exception {
        Path scripts=Path.of(System.getProperty("denovo.test.scripts","../scripts/denovo"));
        var targets=m.createArrayNode(); var t=targets.addObject().put("hgncId","HGNC:999999997");
        t.putObject("allele").put("assembly","GRCh38").put("contig","1").put("position",123).put("reference","A").put("alternate","G");
        ExternalProcessRunner runner=(command,cwd,timeout)->{
            if (mode.equals("timeout")) return new ProcessResult(-1,"SYNTHETIC timeout",true);
            if (mode.equals("nonzero")) return new ProcessResult(2,"SYNTHETIC failure",false);
            Path input=Path.of(command.get(command.indexOf("--request")+1)), output=Path.of(command.get(command.indexOf("--output")+1));
            byte[] raw=Files.readAllBytes(input);var request=m.readTree(raw);
            var r=m.createObjectNode().put("schemaVersion",DeNovoLiteratureSupply.VERSION).put("requestSha256",FamilyDeNovoJson.sha256(raw))
                    .put("modelCalls",0).put("clinicalApproval","NONE");
            r.set("implementation",request.path("implementation"));r.putArray("documents");r.putObject("originals");
            var target=targets.get(0).deepCopy();((ObjectNode)target).putArray("reasonCodes");r.putArray("targets").add(target);
            r.putObject("retrieval").put("networkRequests",0);
            switch(mode) {
                case "wrong-request" -> r.put("requestSha256","0".repeat(64));
                case "wrong-target" -> ((ObjectNode)target.path("allele")).put("position",124);
                case "wrong-pin" -> ((ObjectNode)r.path("originals")).put("0".repeat(64),Base64.getEncoder().encodeToString(new byte[]{1}));
                case "network-disabled" -> ((ObjectNode)r.path("retrieval")).put("networkRequests",1);
                case "bad-reason" -> ((ArrayNode)target.path("reasonCodes")).add("C:/private/example");
                default -> { }
            }
            Files.write(output,m.writeValueAsBytes(r));return new ProcessResult(0,"SYNTHETIC output",false);
        };
        var supplier=new DeNovoLiteratureSupply(m,Path.of("unused-python"),scripts,temp.resolve("work"),temp.resolve("cache"),false,runner);
        var actual=supplier.prepare(targets,m.createArrayNode(),m.createArrayNode(),Map.of());
        if (mode.equals("ok")) { assertNull(actual.issue());assertEquals(1,actual.originals().size()); }
        else { assertNotNull(actual.issue());assertTrue(actual.originals().isEmpty());assertTrue(actual.documents().isEmpty()); }
        assertEquals(1,supplier.executions());assertEquals(0,supplier.networkRequests());
    }
}
