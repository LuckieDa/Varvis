package org.nbscn.varvis.variantanalysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.provider.LiteratureSourceAssets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Local public-paper asset views; independent of analysis execution and model settings. */
@RestController
@RequestMapping("/variant-analyses/acmg/source-view")
public class LiteratureSourceViewController {
    private final LiteratureSourceAssets assets;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    public LiteratureSourceViewController(ObjectMapper mapper,
            @Value("${varvis.literature-workflow-config:../local/private/config/denovo-literature-workflow/config.json}") String config) {
        this.mapper=mapper;this.assets=new LiteratureSourceAssets(mapper,Path.of(config));
    }
    public LiteratureSourceViewController(ObjectMapper mapper,LiteratureSourceAssets assets){this.mapper=mapper;this.assets=assets;}
    @PostMapping(value="/regions",consumes="application/json")
    public ObjectNode regions(jakarta.servlet.http.HttpServletRequest request) throws IOException {
        byte[] bytes=request.getInputStream().readNBytes(512*1024+1);
        if(bytes.length>512*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"SOURCE_REQUEST_LIMIT");
        var body=mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        if(body==null||!body.isObject()||body.size()!=1||!body.path("references").isArray())throw new IllegalArgumentException("SOURCE_REQUEST_INVALID");
        var refs=new ArrayList<LiteratureSourceAssets.Reference>();
        for(var ref:body.path("references")){
            var keys=new java.util.HashSet<String>();ref.fieldNames().forEachRemaining(keys::add);
            LiteratureSourceAssets.ReviewedFigure figure=null;
            if(keys.remove("figure")) {
                var binding=ref.path("figure");
                if(!binding.isObject()||binding.size()!=3||!binding.path("sourceSha256").isTextual()
                        ||!binding.path("reviewSha256").isTextual()||!binding.path("caseLabel").isTextual())
                    throw new IllegalArgumentException("SOURCE_REFERENCE_INVALID");
                figure=new LiteratureSourceAssets.ReviewedFigure(binding.path("sourceSha256").asText(),
                        binding.path("reviewSha256").asText(),binding.path("caseLabel").asText());
            }
            if(!keys.equals(Set.of("pmcid","locator","quote"))||!ref.path("pmcid").isTextual()||!ref.path("locator").isTextual()||!ref.path("quote").isTextual())throw new IllegalArgumentException("SOURCE_REFERENCE_INVALID");
            refs.add(new LiteratureSourceAssets.Reference(ref.path("pmcid").asText(),ref.path("locator").asText(),ref.path("quote").asText(),figure));
        }
        return assets.regions(refs);
    }
    @GetMapping("/papers/{pmcid}/{key}/regions/{annotation}.png")
    public ResponseEntity<byte[]> image(@PathVariable String pmcid,@PathVariable String key,@PathVariable String annotation) throws IOException {
        return ResponseEntity.ok().header("Content-Type","image/png").header("Cache-Control","private, max-age=3600").header("X-Content-Type-Options","nosniff").body(assets.image(pmcid,key,annotation));
    }
    @GetMapping("/papers/{pmcid}/{key}/fulltext")
    public ResponseEntity<String> fullText(@PathVariable String pmcid,@PathVariable String key) throws IOException {
        return ResponseEntity.ok().header("Content-Type","text/html;charset=UTF-8")
                .header("Content-Security-Policy","default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'")
                .header("X-Content-Type-Options","nosniff").body(assets.fullText(pmcid,key));
    }
    @GetMapping("/papers/{pmcid}/media/{filename}")
    public ResponseEntity<byte[]> media(@PathVariable String pmcid,@PathVariable String filename) throws IOException {
        String mime=filename.endsWith(".png")?"image/png":filename.endsWith(".gif")?"image/gif":filename.endsWith(".webp")?"image/webp":filename.endsWith(".tif")||filename.endsWith(".tiff")?"image/tiff":"image/jpeg";
        return ResponseEntity.ok().header("Content-Type",mime).header("X-Content-Type-Options","nosniff").body(assets.media(pmcid,filename));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> invalid(){return java.util.Map.of("message","原文引用或定位信息无效。");}
    @ExceptionHandler(com.fasterxml.jackson.core.JsonProcessingException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> malformed(){return invalid();}
    @ExceptionHandler(IOException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public java.util.Map<String,String> unavailable(){return java.util.Map.of("message","当前原文截图不可用。");}
}
