package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LiteratureSourceAssetsTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper=new ObjectMapper();
    private static final String PMCID="PMC123456",KEY="a".repeat(24),ANNOTATION="b".repeat(64),LOCATOR="/article[1]/body[1]/p[1]",QUOTE="A source statement.";
    private static String sha(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private Path setup()throws Exception{
        var paper=temporary.resolve("papers/"+PMCID);Files.createDirectories(paper);
        var xml="<article><body><p>A source statement.</p></body></article>".getBytes(StandardCharsets.UTF_8);Files.write(paper.resolve(PMCID+".xml"),xml);
        var folder=temporary.resolve("source-screenshots/"+PMCID+"/"+KEY);Files.createDirectories(folder);
        var image=folder.resolve("source-cached-annotated.png");
        var png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=");Files.write(image,png);
        var manifest=mapper.createObjectNode().put("sourceSha256",sha(xml));
        var annotation=manifest.putObject("annotations").putObject(ANNOTATION).put("available",true).put("path",image.toString()).put("sha256",sha(png));
        annotation.putArray("citations").addObject().put("unitId",LOCATOR).put("quote",QUOTE);
        Files.writeString(folder.resolve("manifest.json"),manifest.toString());
        var media=temporary.resolve("source-screenshots/source-media/"+PMCID);Files.createDirectories(media);Files.write(media.resolve("figure.png"),png);
        Files.writeString(folder.resolve("fulltext.html"),"<html><body><p>original</p><img src=\""+media.resolve("figure.png").toUri()+"\"><script>secret()</script></body></html>");
        return folder;
    }
    private LiteratureSourceAssets service(){return new LiteratureSourceAssets(mapper,temporary,true);}
    @Test void cachedOriginalRegionIsReusedWithExactCitationAndWithoutExposingFilePaths()throws Exception{
        setup();var assets=service();var refs=List.of(new LiteratureSourceAssets.Reference(PMCID,LOCATOR,QUOTE));
        var a=assets.regions(refs);var b=assets.regions(refs);assertEquals(a,b);
        assertEquals("AVAILABLE",a.at("/regions/0/status").asText());assertEquals(QUOTE,a.at("/regions/0/quote").asText());
        assertEquals("RETAINED",a.at("/regions/0/archiveStatus").asText());
        assertTrue(a.at("/regions/0/imageUrl").asText().startsWith("/variant-analyses/acmg/source-view/"));
        assertFalse(a.toString().contains(temporary.toString()));assertFalse(a.toString().contains("file:"));
        assertArrayEquals(assets.image(PMCID,KEY,ANNOTATION),assets.media(PMCID,"figure.png"));
        var archives=temporary.resolve("reviewed-evidence").resolve(PMCID).resolve("screenshots");
        try(var paths=Files.walk(archives)){
            var png=paths.filter(path->path.getFileName().toString().endsWith(".png")).toList();
            assertEquals(1,png.size());
            assertArrayEquals(assets.image(PMCID,KEY,ANNOTATION),Files.readAllBytes(png.get(0)));
        }
    }
    @Test void docxRegionRequiresTheExactRegisteredAttachmentBytes()throws Exception{
        var folder=setup();var manifestFile=folder.resolve("manifest.json");
        var manifest=(ObjectNode)mapper.readTree(Files.readAllBytes(manifestFile));
        byte[] original="registered attachment bytes".getBytes(StandardCharsets.UTF_8);
        String pin=sha(original),locator="attachment:"+pin+":word/document.xml:/document/body/tbl[1]/tr[1]/tc[2]";
        var directory=temporary.resolve("papers/"+PMCID+"/supplementary");Files.createDirectories(directory);
        var attachment=directory.resolve(pin+".docx");Files.write(attachment,original);
        manifest.putArray("attachmentSha256s").add(pin);
        ((ObjectNode)manifest.at("/annotations/"+ANNOTATION+"/citations/0")).put("unitId",locator);
        Files.writeString(manifestFile,manifest.toString());
        var assets=service();var refs=List.of(new LiteratureSourceAssets.Reference(PMCID,locator,QUOTE));
        assertEquals("AVAILABLE",assets.regions(refs).at("/regions/0/status").asText());
        Files.writeString(attachment,"other attachment bytes");
        assertEquals("QUOTE_ONLY",assets.regions(refs).at("/regions/0/status").asText());
        assertThrows(java.io.IOException.class,()->assets.image(PMCID,KEY,ANNOTATION));
    }

    @Test void reviewedFigureUsesTheSavedCaseAndSourceAndKeepsLegacyQuoteFallback() throws Exception {
        var rendered=Files.readAllBytes(setup().resolve("source-cached-annotated.png"));
        var originals=Files.createDirectories(temporary.resolve("papers/"+PMCID+"/originals"));
        byte[] article="<article>Public source.</article>".getBytes(StandardCharsets.UTF_8);
        String paperSha=sha(article), imageSha=sha(rendered);
        Files.write(originals.resolve(paperSha+".html"),article);
        byte[] originalFigure="registered figure bytes".getBytes(StandardCharsets.UTF_8);
        String figureSha=sha(originalFigure);
        var original=Files.write(originals.resolve(figureSha+".jpg"),originalFigure);
        String locator="figure-one", panel="A · Case 1 · II-1", figureLocator=locator+" · "+panel;
        var review=mapper.createObjectNode().put("schemaVersion","denovo-reviewed-paper-v1")
                .put("pmcid",PMCID).put("format","PMC_HTML").put("sourceSha256",paperSha);
        var cases=review.putArray("cases");
        cases.addObject().put("label","Case 1").putObject("refs").putArray("proband").addObject()
                .put("imageSha256",figureSha).put("locator",locator).put("panel",panel).put("quote",QUOTE);
        cases.addObject().put("label","Case 2").putObject("refs").putArray("proband").addObject()
                .put("imageSha256",figureSha).put("locator",locator).put("panel","B · Case 2 · II-1").put("quote",QUOTE);
        byte[] rawReview=mapper.writeValueAsBytes(review);
        String reviewSha=sha(rawReview);
        var archive=temporary.resolve("reviewed-evidence/"+PMCID);
        Files.write(Files.createDirectories(archive.resolve("reviews/DENOVO")).resolve(reviewSha+".json"),rawReview);
        var folder=Files.createDirectories(archive.resolve("screenshots").resolve(figureSha));
        var png=Files.write(folder.resolve(imageSha+".png"),rendered);
        var manifest=mapper.createObjectNode().put("schemaVersion","literature-reviewed-figure-regions-v1")
                .put("pmcid",PMCID).put("sourceSha256",figureSha).put("sourceExtension","jpg");
        var region=manifest.putObject("regions").putObject(imageSha).put("reviewSha256",reviewSha).put("paperSha256",paperSha)
                .put("caseLabel","Case 1").put("locationLabel","Figure 1A");
        region.putArray("citations").addObject().put("locator",figureLocator).put("quote",QUOTE);
        var manifestFile=Files.writeString(folder.resolve("manifest.json"),manifest.toString());

        var binding=new LiteratureSourceAssets.ReviewedFigure(figureSha,reviewSha,"Case 1");
        var ref=new LiteratureSourceAssets.Reference(PMCID,figureLocator,QUOTE,binding);
        var other=new LiteratureSourceAssets.Reference(PMCID,figureLocator,QUOTE,
                new LiteratureSourceAssets.ReviewedFigure(figureSha,reviewSha,"Case 2"));
        var legacy=new LiteratureSourceAssets.Reference(PMCID,LOCATOR,QUOTE);
        var result=service().regions(List.of(ref,other,legacy));
        assertEquals(result,service().regions(List.of(ref,other,legacy)));
        assertEquals("AVAILABLE",result.at("/regions/0/status").asText());
        assertEquals("Figure 1A",result.at("/regions/0/locationLabel").asText());
        assertEquals("QUOTE_ONLY",result.at("/regions/1/status").asText());
        assertEquals("AVAILABLE",result.at("/regions/2/status").asText());
        assertArrayEquals(rendered,service().image(PMCID,figureSha,imageSha));

        region.put("caseLabel","Case 2");
        Files.writeString(manifestFile,manifest.toString());
        assertEquals("QUOTE_ONLY",service().regions(List.of(other)).at("/regions/0/status").asText());
        assertThrows(java.io.IOException.class,()->service().image(PMCID,figureSha,imageSha));
        region.put("caseLabel","Case 1");
        Files.writeString(manifestFile,manifest.toString());
        Files.writeString(png,"other PNG bytes");
        assertEquals("QUOTE_ONLY",service().regions(List.of(ref)).at("/regions/0/status").asText());
        Files.write(png,rendered);
        Files.writeString(original,"other original bytes");
        assertThrows(java.io.IOException.class,()->service().image(PMCID,figureSha,imageSha));
    }
    @Test void reviewedMaterialRequiresTheRegisteredOriginalAndCanBeWrittenRepeatedly() throws Exception {
        setup();var assets=service();var facts=mapper.createObjectNode().put("example","source-bound");
        var xml=temporary.resolve("papers").resolve(PMCID).resolve(PMCID+".xml");
        String pin=sha(Files.readAllBytes(xml));
        assets.retainReviewed(PMCID,"DENOVO",pin,facts);
        assets.retainReviewed(PMCID,"DENOVO",pin,facts);
        try(var paths=Files.list(temporary.resolve("reviewed-evidence").resolve(PMCID).resolve("reviews/DENOVO"))){
            var records=paths.toList();assertEquals(1,records.size());
            var stored=mapper.readTree(Files.readAllBytes(records.get(0)));
            assertEquals(pin,stored.path("sourceSha256").asText());
            assertEquals(facts,stored.path("material"));
            assertFalse(stored.path("scoringAuthority").asBoolean());
        }
        assertThrows(java.io.IOException.class,()->assets.retainReviewed(PMCID,"DENOVO","0".repeat(64),facts));
    }
    @Test void aNewlyAvailableOriginalFigureInvalidatesTheCaptionOnlyRegion() throws Exception {
        var folder = setup();
        var xml = temporary.resolve("papers/" + PMCID + "/" + PMCID + ".xml");
        Files.writeString(xml, """
                <article xmlns:xlink="http://www.w3.org/1999/xlink"><body><p>A source statement.</p>
                <fig><graphic xlink:href="figure.png"/></fig></body></article>
                """);
        var manifestFile = folder.resolve("manifest.json");
        var manifest = (ObjectNode) mapper.readTree(Files.readString(manifestFile));
        manifest.put("sourceSha256", sha(Files.readAllBytes(xml)));
        manifest.putObject("mediaSha256");
        Files.writeString(manifestFile, manifest.toString());
        var media = temporary.resolve("source-screenshots/source-media/" + PMCID + "/figure.png");
        var imageBytes = Files.readAllBytes(media);
        Files.delete(media);
        var refs = List.of(new LiteratureSourceAssets.Reference(PMCID, LOCATOR, QUOTE));
        var assets = service();

        assertEquals("AVAILABLE", assets.regions(refs).at("/regions/0/status").asText());
        Files.write(media, imageBytes);
        assertEquals("QUOTE_ONLY", assets.regions(refs).at("/regions/0/status").asText());

        manifest.withObject("mediaSha256").put("figure.png", sha(imageBytes));
        Files.writeString(manifestFile, manifest.toString());
        assertEquals("AVAILABLE", assets.regions(refs).at("/regions/0/status").asText());
    }

    @Test void anotherQuoteOrLocationCannotBorrowTheExistingImage()throws Exception{
        setup();for(var ref:List.of(new LiteratureSourceAssets.Reference(PMCID,LOCATOR,"Another statement."),
                new LiteratureSourceAssets.Reference(PMCID,"/article[1]/body[1]/p[2]",QUOTE))){
            var row=service().regions(List.of(ref)).at("/regions/0");assertEquals("QUOTE_ONLY",row.path("status").asText());assertFalse(row.has("imageUrl"));}
    }
    @Test void changingOriginalOrImageInvalidatesReuseInsteadOfServingWrongEvidence()throws Exception{
        var folder=setup();Files.writeString(folder.resolve("source-cached-annotated.png"),"changed");
        assertEquals("QUOTE_ONLY",service().regions(List.of(new LiteratureSourceAssets.Reference(PMCID,LOCATOR,QUOTE))).at("/regions/0/status").asText());
        assertThrows(java.io.IOException.class,()->service().image(PMCID,KEY,ANNOTATION));
        setup();Files.writeString(temporary.resolve("papers/"+PMCID+"/"+PMCID+".xml"),"changed");
        assertEquals("QUOTE_ONLY",service().regions(List.of(new LiteratureSourceAssets.Reference(PMCID,LOCATOR,QUOTE))).at("/regions/0/status").asText());
        assertThrows(java.io.IOException.class,()->service().fullText(PMCID,KEY));
    }
    @Test void fullTextUsesLocalPublicMediaAndCannotExecuteRendererScripts()throws Exception{
        setup();String text=service().fullText(PMCID,KEY);
        assertTrue(text.contains("/variant-analyses/acmg/source-view/papers/"+PMCID+"/media/figure.png"));
        assertFalse(text.contains("<script"));assertFalse(text.contains("file:"));
    }
    @Test void traversalAndManifestImageEscapeAreRejected()throws Exception{
        var folder=setup();var assets=service();
        assertThrows(IllegalArgumentException.class,()->assets.regions(List.of(new LiteratureSourceAssets.Reference("../private",LOCATOR,QUOTE))));
        assertThrows(java.io.IOException.class,()->assets.image(PMCID,"../private",ANNOTATION));
        assertThrows(java.io.IOException.class,()->assets.media(PMCID,"../private.png"));
        var manifest=(ObjectNode)mapper.readTree(Files.readString(folder.resolve("manifest.json")));
        ((ObjectNode)manifest.at("/annotations/"+ANNOTATION)).put("path",temporary.resolve("outside.png").toString());
        Files.writeString(folder.resolve("manifest.json"),manifest.toString());
        assertThrows(java.io.IOException.class,()->assets.image(PMCID,KEY,ANNOTATION));
    }
    @Test void missingCachePreservesQuoteAndEnforcesRequestBounds()throws Exception{
        var assets=service();assertEquals("QUOTE_ONLY",assets.regions(List.of(new LiteratureSourceAssets.Reference(PMCID,LOCATOR,QUOTE))).at("/regions/0/status").asText());
        assertThrows(IllegalArgumentException.class,()->assets.regions(List.of()));
        assertThrows(IllegalArgumentException.class,()->assets.regions(java.util.Collections.nCopies(33,new LiteratureSourceAssets.Reference(PMCID,LOCATOR,QUOTE))));
    }
    @Test void oneUnreadablePaperCannotSuppressOtherAvailableOriginalRegions()throws Exception{
        setup();var xml=temporary.resolve("papers/PMC222222/PMC222222.xml");Files.createDirectories(xml.getParent());
        try(var channel=Files.newByteChannel(xml,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE)){
            channel.position(20L*1024*1024);channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        var result=service().regions(List.of(new LiteratureSourceAssets.Reference("PMC222222",LOCATOR,QUOTE),new LiteratureSourceAssets.Reference(PMCID,LOCATOR,QUOTE)));
        assertEquals("QUOTE_ONLY",result.at("/regions/0/status").asText());assertEquals("AVAILABLE",result.at("/regions/1/status").asText());
    }
}
