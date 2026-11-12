package org.nbscn.varvis.variantanalysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.LiteratureSourceAssets;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LiteratureSourceViewControllerTest {
    @TempDir Path temporary;
    @Test void missingCachedAssetHasReadableFallbackAndNeverExecutesEvidence()throws Exception{
        var mapper=new ObjectMapper();var mvc=MockMvcBuilders.standaloneSetup(new LiteratureSourceViewController(mapper,new LiteratureSourceAssets(mapper,temporary,true))).build();
        mvc.perform(post("/variant-analyses/acmg/source-view/regions").contentType("application/json")
            .content("{\"references\":[{\"pmcid\":\"PMC123\",\"locator\":\"/article[1]/body[1]/p[1]\",\"quote\":\"An original quote.\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.regions[0].status").value("QUOTE_ONLY"));
        mvc.perform(post("/variant-analyses/acmg/source-view/regions").contentType("application/json")
            .content("{\"references\":[{\"pmcid\":\"PMC123\",\"locator\":\"/article[1]/body[1]/p[1]\",\"quote\":\"An original quote.\",\"path\":\"private.json\"}]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/variant-analyses/acmg/source-view/papers/PMC123/"+"a".repeat(24)+"/fulltext"))
            .andExpect(status().isNotFound());
    }
    @Test void malformedDuplicateAndTrailingJsonAreRejectedAsInvalidRequests()throws Exception{
        var mapper=new ObjectMapper();var mvc=MockMvcBuilders.standaloneSetup(new LiteratureSourceViewController(mapper,new LiteratureSourceAssets(mapper,temporary,true))).build();
        for(String body:new String[]{"{", "{\"references\":[],\"references\":[]}","{\"references\":[]} {}"}){
            if(body==null)throw new AssertionError("JSON test input must be present");
            mvc.perform(post("/variant-analyses/acmg/source-view/regions").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("原文引用或定位信息无效。"));
        }
    }

    @Test void reviewedFigureRequestAcceptsOnlyTheThreeSavedBindingFields()throws Exception{
        var mapper=new ObjectMapper();var mvc=MockMvcBuilders.standaloneSetup(new LiteratureSourceViewController(mapper,new LiteratureSourceAssets(mapper,temporary,true))).build();
        var body=mapper.createObjectNode();
        var figure=body.putArray("references").addObject().put("pmcid","PMC123")
                .put("locator","figure-one · A · Case 1 · II-1").put("quote","An original quote.")
                .putObject("figure").put("sourceSha256","a".repeat(64)).put("reviewSha256","b".repeat(64)).put("caseLabel","Case 1");
        mvc.perform(post("/variant-analyses/acmg/source-view/regions").contentType("application/json").content(Objects.requireNonNull(body.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.regions[0].status").value("QUOTE_ONLY"))
                .andExpect(jsonPath("$.regions[0].figure.caseLabel").value("Case 1"));
        figure.put("path","private.png");
        mvc.perform(post("/variant-analyses/acmg/source-view/regions").contentType("application/json").content(Objects.requireNonNull(body.toString())))
                .andExpect(status().isBadRequest());
    }
}
