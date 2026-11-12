package org.nbscn.varvis.variantanalysis.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessWorkflows;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class Pm3LiteratureControllerTest {
    @TempDir Path root;
    @Test void clientsCannotInjectFactsOrSkipConfirmation() throws Exception {
        var workflows=mock(AcmgBusinessWorkflows.class);
        String body="{\"topN\":1,\"candidateId\":\"c1\",\"pmcid\":\"PMC123\",\"bundleSha256\":\""+"b".repeat(64)
                +"\",\"selectionSha256\":\""+"a".repeat(64)+"\",\"confirmPublicModel\":true}";
        try(var controller=new PublicLiteratureController(workflows,root.resolve("missing.json").toString())) {
            for(String invalid:new String[]{body.replace("true","false"),body.replace("1,","0,"),
                    body.substring(0,body.length()-1)+",\"target\":{\"geneSymbol\":\"FAKE\"}}",
                    body.substring(0,body.length()-1)+",\"partnerClassification\":\"PATHOGENIC\"}"}) {
                var input=new MockHttpServletRequest();input.setContent(invalid.getBytes(StandardCharsets.UTF_8));
                assertThrows(ResponseStatusException.class,()->controller.startPm3("analysis",input));
            }
            verifyNoInteractions(workflows);
        }
    }
}
