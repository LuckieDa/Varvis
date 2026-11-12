package org.nbscn.varvis.ps1pm5acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class Ps1Pm5SourceUpgradeTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode original() throws Exception {
        var value=mapper.createObjectNode().put("sourceHead", "a".repeat(40)).put("configurationSha256", "c".repeat(64)).put("instanceId", "same-private-instance").put("port", 63271);
        Files.write(root.resolve("instance-manifest.json"),mapper.writeValueAsBytes(value)); return value;
    }
    @Test void upgradeKeepsOriginalAndAllowsSameSourceRestart() throws Exception {
        var next=original().put("sourceHead", "b".repeat(40)); var before=Files.readAllBytes(root.resolve("instance-manifest.json"));
        var receipt=Ps1Pm5NetworkApplication.sourceUpgrade(root,next,true);
        assertArrayEquals(before,Files.readAllBytes(root.resolve("instance-manifest.json")));
        assertEquals(receipt,Ps1Pm5NetworkApplication.sourceUpgrade(root,next,false));
        assertEquals("a".repeat(40),receipt.path("previousSourceHead").asText());
    }
    @Test void newSourceNeedsExplicitUpgrade() throws Exception {
        var next=original().put("sourceHead", "b".repeat(40));
        assertThrows(IllegalArgumentException.class,()->Ps1Pm5NetworkApplication.sourceUpgrade(root,next,false));
    }
    @Test void upgradeCannotChangeConfigurationOrReplaceReceipt() throws Exception {
        var next=original().put("sourceHead", "b".repeat(40));
        assertThrows(IllegalArgumentException.class,()->Ps1Pm5NetworkApplication.sourceUpgrade(root,next.deepCopy().put("port", 63272),true));
        Ps1Pm5NetworkApplication.sourceUpgrade(root,next,true);
        Files.writeString(root.resolve("instance-source-"+"b".repeat(40)+".json"),"{}");
        assertThrows(IllegalArgumentException.class,()->Ps1Pm5NetworkApplication.sourceUpgrade(root,next,true));
    }
}
