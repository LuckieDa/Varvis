package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.util.concurrent.TimeUnit;
import org.springframework.lang.Nullable;

/** Public original-paper assets only. No evaluation, model, network or ACMG-store writes. */
public final class LiteratureSourceAssets {
    public record ReviewedFigure(String sourceSha256, String reviewSha256, String caseLabel) { }
    public record Reference(String pmcid, String locator, String quote, @Nullable ReviewedFigure figure) {
        public Reference(String pmcid, String locator, String quote) { this(pmcid, locator, quote, null); }
    }
    private static final java.util.regex.Pattern SHA256 = java.util.regex.Pattern.compile("[a-f0-9]{64}");
    private static final java.util.regex.Pattern DOCX_LOCATOR=java.util.regex.Pattern.compile(
            "attachment:([a-f0-9]{64}):word/document\\.xml:/document/body/(?:p\\[[1-9][0-9]*\\]|tbl\\[[1-9][0-9]*\\]/tr\\[[1-9][0-9]*\\]/tc\\[[1-9][0-9]*\\])");
    private final ObjectMapper mapper;
    private final Path configuration;
    @Nullable private final Path suppliedLibrary;

    public LiteratureSourceAssets(ObjectMapper mapper, Path configuration) {
        this.mapper=mapper;this.configuration=configuration.toAbsolutePath().normalize();this.suppliedLibrary=null;
    }
    /** Isolated fixture root; never accepted from an HTTP request. */
    public LiteratureSourceAssets(ObjectMapper mapper, Path library, boolean fixture) {
        this.mapper=mapper;this.configuration=library.resolve("no-configuration.json");this.suppliedLibrary=library.toAbsolutePath().normalize();
    }
    private byte[] read(Path path,int limit) throws IOException {
        if(!Files.isRegularFile(path)||Files.size(path)>limit)throw new IOException("SOURCE_ASSET_UNAVAILABLE");
        try(var input=Files.newInputStream(path)){var bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("SOURCE_ASSET_LIMIT");return bytes;}
    }
    @Nullable private ObjectNode config() throws IOException {
        if(!Files.isRegularFile(configuration))return null;
        var value=mapper.readTree(read(configuration,16384));
        if(!(value instanceof ObjectNode object)||!PublicLiteratureJobs.VERSION.equals(value.path("schemaVersion").asText()))return null;
        return object;
    }
    @Nullable private Path library() throws IOException {
        if(suppliedLibrary!=null)return suppliedLibrary;
        var c=config();if(c==null||c.path("projectRoot").asText().isBlank()||c.path("library").asText().isBlank())return null;
        var project=Path.of(c.path("projectRoot").asText()).toAbsolutePath().normalize();
        var root=project.resolve(c.path("library").asText()).normalize();
        return root.startsWith(project.resolve("local"))&&!root.equals(project.resolve("local"))?root:null;
    }
    public static void validate(Reference ref) {
        if(ref==null||ref.pmcid()==null||!ref.pmcid().matches("PMC[1-9][0-9]*")||ref.locator()==null
                ||ref.locator().isBlank()||ref.locator().length()>2048||ref.quote()==null
                ||ref.quote().isBlank()||ref.quote().length()>12000)throw new IllegalArgumentException("SOURCE_REFERENCE_INVALID");
        var figure=ref.figure();
        boolean validLocation=figure==null
                ? ref.locator().startsWith("/article[1]/")||DOCX_LOCATOR.matcher(ref.locator()).matches()
                : isSha256(figure.sourceSha256())&&isSha256(figure.reviewSha256())&&figure.caseLabel()!=null
                    &&!figure.caseLabel().isBlank()&&figure.caseLabel().length()<=256;
        if(!validLocation)throw new IllegalArgumentException("SOURCE_REFERENCE_INVALID");
    }
    private static boolean isSha256(@Nullable String value) {
        return value!=null&&SHA256.matcher(value).matches();
    }
    private static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String normalized(String value){return value.replaceAll("(?U)\\s+"," ").strip();}
    private Path confined(Path root,Path file) throws IOException {
        if(!Files.isRegularFile(file)||!file.toRealPath().startsWith(root.toRealPath()))throw new IOException("SOURCE_ASSET_SCOPE_INVALID");
        return file;
    }
    private static String base(String pmcid,String key){return "/variant-analyses/acmg/source-view/papers/"+pmcid+"/"+key;}
    private ObjectNode unavailable(Reference ref) {
        var row=mapper.createObjectNode().put("pmcid",ref.pmcid()).put("locator",ref.locator()).put("quote",ref.quote()).put("status","QUOTE_ONLY");
        if(ref.figure()!=null)row.set("figure",mapper.valueToTree(ref.figure()));
        return row;
    }

    private Path reviewedFigureFolder(Path library, String pmcid, String figureSha) {
        return library.resolve("reviewed-evidence").resolve(pmcid).resolve("screenshots").resolve(figureSha);
    }

    private JsonNode reviewedFigureRegions(Path library, String pmcid, String figureSha) throws IOException {
        Path folder=reviewedFigureFolder(library,pmcid,figureSha);
        var manifest=mapper.readTree(read(confined(library,confined(folder,folder.resolve("manifest.json"))),1024*1024));
        if(manifest==null||!"literature-reviewed-figure-regions-v1".equals(manifest.path("schemaVersion").asText())
                ||!pmcid.equals(manifest.path("pmcid").asText())||!figureSha.equals(manifest.path("sourceSha256").asText())
                ||!manifest.path("regions").isObject()||manifest.path("regions").size()>32)
            throw new IOException("SOURCE_FIGURE_REGISTRATION_INVALID");
        String extension=manifest.path("sourceExtension").asText();
        if(!extension.matches("jpe?g|png"))throw new IOException("SOURCE_FIGURE_REGISTRATION_INVALID");
        var originals=library.resolve("papers").resolve(pmcid).resolve("originals");
        var figure=confined(library,confined(originals,originals.resolve(figureSha+"."+extension)));
        if(!figureSha.equals(sha(read(figure,24*1024*1024))))throw new IOException("SOURCE_FIGURE_CHANGED");
        return manifest.path("regions");
    }

    private byte[] reviewedFigureImage(Path library, String pmcid, String figureSha, String imageSha, JsonNode region)
            throws IOException {
        String reviewSha=region.path("reviewSha256").asText(), caseLabel=region.path("caseLabel").asText();
        var citations=region.path("citations");
        if(!isSha256(reviewSha)||caseLabel.isBlank()||!citations.isArray()||citations.isEmpty()||citations.size()>32)
            throw new IOException("SOURCE_FIGURE_REGION_UNBOUND");
        var reviews=library.resolve("reviewed-evidence").resolve(pmcid).resolve("reviews/DENOVO");
        var reviewPath=confined(library,confined(reviews,reviews.resolve(reviewSha+".json")));
        byte[] rawReview=read(reviewPath,8*1024*1024);
        if(!reviewSha.equals(sha(rawReview)))throw new IOException("SOURCE_FIGURE_REVIEW_CHANGED");
        var review=mapper.readTree(rawReview);
        if(review==null||!"denovo-reviewed-paper-v1".equals(review.path("schemaVersion").asText())
                ||!pmcid.equals(review.path("pmcid").asText())||!"PMC_HTML".equals(review.path("format").asText())
                ||!"PUBLIC_PAPER_RESEARCH".equals(review.path("dataClass").asText("PUBLIC_PAPER_RESEARCH")))
            throw new IOException("SOURCE_FIGURE_REVIEW_INVALID");
        String paperSha=review.path("sourceSha256").asText();
        if(!isSha256(paperSha)||!paperSha.equals(region.path("paperSha256").asText()))
            throw new IOException("SOURCE_FIGURE_REVIEW_INVALID");
        var originals=library.resolve("papers").resolve(pmcid).resolve("originals");
        var article=confined(library,confined(originals,originals.resolve(paperSha+".html")));
        if(!paperSha.equals(sha(read(article,20*1024*1024))))throw new IOException("SOURCE_ORIGINAL_CHANGED");
        var cases=review.path("cases").valueStream().filter(c->caseLabel.equals(c.path("label").asText())).toList();
        if(cases.size()!=1)throw new IOException("SOURCE_FIGURE_REGION_UNBOUND");
        var reviewedReferences=cases.get(0).findParents("imageSha256").stream()
                .filter(ref->figureSha.equals(ref.path("imageSha256").asText())).toList();
        for(var citation:citations) {
            if(reviewedReferences.stream().noneMatch(ref->
                    (ref.path("locator").asText()+" · "+ref.path("panel").asText()).equals(citation.path("locator").asText())
                    &&normalized(ref.path("quote").asText()).equals(normalized(citation.path("quote").asText()))))
                throw new IOException("SOURCE_FIGURE_REGION_UNBOUND");
        }
        var folder=reviewedFigureFolder(library,pmcid,figureSha);
        return verifiedPng(confined(library,confined(folder,folder.resolve(imageSha+".png"))),imageSha);
    }

    @Nullable private ObjectNode lookupReviewedFigure(Path library, Reference ref, ReviewedFigure figure) {
        try {
            for(var entry:reviewedFigureRegions(library,ref.pmcid(),figure.sourceSha256()).properties()) {
                String imageSha=entry.getKey();
                var region=entry.getValue();
                if(!isSha256(imageSha)||!figure.reviewSha256().equals(region.path("reviewSha256").asText())
                        ||!figure.caseLabel().equals(region.path("caseLabel").asText()))continue;
                boolean matches=region.path("citations").valueStream().anyMatch(citation->
                        ref.locator().equals(citation.path("locator").asText())
                        &&normalized(ref.quote()).equals(normalized(citation.path("quote").asText())));
                if(!matches)continue;
                reviewedFigureImage(library,ref.pmcid(),figure.sourceSha256(),imageSha,region);
                return unavailable(ref).put("status","AVAILABLE").put("archiveStatus","RETAINED")
                        .put("locationLabel",region.path("locationLabel").asText(ref.locator()))
                        .put("imageUrl",base(ref.pmcid(),figure.sourceSha256())+"/regions/"+imageSha+".png");
            }
        } catch(IOException unavailable) { /* Keep the saved citation and article link. */ }
        return null;
    }

    private record PaperIdentity(String xmlSha, ObjectNode mediaShas) { }

    @Nullable private PaperIdentity paperIdentity(Path library, String pmcid) throws IOException {
        var xml = library.resolve("papers").resolve(pmcid).resolve(pmcid + ".xml");
        if (!Files.isRegularFile(xml)) return null;
        byte[] original = read(confined(library, xml), 20 * 1024 * 1024);
        var media = mapper.createObjectNode();
        var mediaRoot = library.resolve("source-screenshots/source-media").resolve(pmcid);
        if (Files.isDirectory(mediaRoot) && mediaRoot.toRealPath().startsWith(library.toRealPath())) {
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            try {
                var reader = factory.createXMLStreamReader(new ByteArrayInputStream(original));
                try {
                    while (reader.hasNext()) {
                        if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
                        String tag = reader.getLocalName();
                        if (!"graphic".equals(tag) && !"inline-graphic".equals(tag)) continue;
                        String name = reader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                        if (!isMediaBasename(name) || media.has(name)) continue;
                        var file = mediaRoot.resolve(name);
                        if (Files.isRegularFile(file)) media.put(name, sha(read(confined(mediaRoot, file), 24 * 1024 * 1024)));
                    }
                } finally { reader.close(); }
            } catch (XMLStreamException invalid) {
                throw new IOException("SOURCE_XML_MEDIA_INVALID", invalid);
            }
        }
        return new PaperIdentity(sha(original), media);
    }

    private static boolean isMediaBasename(@Nullable String name) {
        if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) return false;
        try {
            var path = Path.of(name);
            return path.getFileName() != null && name.equals(path.getFileName().toString());
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    private static boolean validMediaName(@Nullable String name) {
        return name != null && !name.contains("..") && name.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(?:png|jpg|jpeg|gif|webp|tif|tiff)");
    }

    @Nullable private ObjectNode lookup(Path library,Reference ref,PaperIdentity identity) throws IOException {
        var root=library.resolve("source-screenshots").resolve(ref.pmcid());
        if(!Files.isDirectory(root))return null;
        List<Path> folders;
        try(var stream=Files.list(root)){folders=stream.filter(p->p.getFileName().toString().matches("[a-f0-9]{24}")).limit(64).toList();}
        var attachment=DOCX_LOCATOR.matcher(ref.locator());
        String attachmentSha=attachment.matches()?attachment.group(1):null;
        for(var folder:folders){
            Path manifest=folder.resolve("manifest.json");if(!Files.isRegularFile(manifest))continue;
            JsonNode data;try{data=mapper.readTree(read(confined(library,manifest),4*1024*1024));}catch(IOException unavailable){continue;}
            if (data == null || !identity.xmlSha().equals(data.path("sourceSha256").asText())) continue;
            var renderedMedia = data.path("mediaSha256");
            if (!identity.mediaShas().equals(renderedMedia) && !(identity.mediaShas().isEmpty() && renderedMedia.isMissingNode())) continue;
            if(ref.locator().contains("/fig[")&&!data.path("registeredMediaChecked").asBoolean()){
                var paper=library.resolve("papers").resolve(ref.pmcid());
                if(Files.isDirectory(paper.resolve("pedigree-figures"))||Files.isDirectory(paper.resolve("source-bundles")))continue;
            }
            var attachmentPins=data.path("attachmentSha256s");
            if(attachmentSha!=null&&!containsPin(attachmentPins,attachmentSha))continue;
            if(!validAttachments(library,ref.pmcid(),attachmentPins))continue;
            for(var entry:data.path("annotations").properties()){
                var annotation=entry.getValue();
                if(!entry.getKey().matches("[a-f0-9]{64}")||!annotation.path("available").asBoolean())continue;
                boolean match=false;
                for(var citation:annotation.path("citations"))if(ref.locator().equals(citation.path("unitId").asText())
                        &&normalized(ref.quote()).equals(normalized(citation.path("quote").asText())))match=true;
                if(!match)continue;
                Path image;
                try { image=imageFile(folder,annotation); }
                catch(IOException invalid) { continue; }
                String key=folder.getFileName().toString(),url=base(ref.pmcid(),key);
                var result=unavailable(ref).put("status","AVAILABLE").put("imageUrl",url+"/regions/"+entry.getKey()+".png")
                        .put("fullTextUrl",url+"/fulltext#source-"+sha(ref.locator().getBytes(StandardCharsets.UTF_8)).substring(0,20));
                try {
                    retainRegion(library,ref.pmcid(),identity,entry.getKey(),annotation,image);
                    result.put("archiveStatus","RETAINED");
                } catch(IOException unavailable) { result.put("archiveStatus","UNAVAILABLE"); }
                return result;
            }
        }
        return null;
    }
    private static boolean containsPin(JsonNode pins,String sha){
        for(var pin:pins)if(pin.isTextual()&&sha.equals(pin.asText()))return true;
        return false;
    }
    private boolean validAttachments(Path library,String pmcid,JsonNode pins){
        if(pins.isMissingNode())return true; // Historical JATS-only cache.
        if(!pins.isArray()||pins.size()>32)return false;
        Path paper=library.resolve("papers").resolve(pmcid),directory=paper.resolve("supplementary");
        for(var pin:pins){
            if(!pin.isTextual()||!pin.asText().matches("[a-f0-9]{64}"))return false;
            try{
                if(!directory.toRealPath().startsWith(paper.toRealPath()))return false;
                Path source=confined(directory,directory.resolve(pin.asText()+".docx"));
                if(!source.toRealPath().startsWith(library.toRealPath())||!pin.asText().equals(sha(read(source,24*1024*1024))))return false;
            }catch(IOException invalid){return false;}
        }
        return true;
    }
    private void retainRegion(Path library, String pmcid, PaperIdentity paper, String key, JsonNode annotation,
            Path image) throws IOException {
        byte[] png=read(image,24*1024*1024);
        String imageSha=sha(png);
        if(!imageSha.equals(annotation.path("sha256").asText())) throw new IOException("SOURCE_IMAGE_IDENTITY_MISMATCH");
        Path directory=library.resolve("reviewed-evidence").resolve(pmcid).resolve("screenshots").resolve(paper.xmlSha());
        Files.createDirectories(directory);
        if(!directory.toRealPath().startsWith(library.toRealPath())) throw new IOException("SOURCE_ARCHIVE_SCOPE_INVALID");
        var record=mapper.createObjectNode().put("schemaVersion","literature-source-image-v1")
                .put("pmcid",pmcid).put("sourceSha256",paper.xmlSha()).put("imageSha256",imageSha)
                .put("image",imageSha+".png").put("kind","RENDERED_REGISTERED_ARTICLE")
                .put("source","https://pmc.ncbi.nlm.nih.gov/articles/"+pmcid+"/");
        record.set("citations",annotation.path("citations").deepCopy());
        record.set("sourceMediaSha256",paper.mediaShas().deepCopy());
        retainFile(directory,imageSha+".png",png);
        retainFile(directory,key+"-"+imageSha+".json",mapper.writeValueAsBytes(record));
    }

    public synchronized void retainReviewed(String pmcid, String criterion, String sourceSha, JsonNode facts) throws IOException {
        if(!pmcid.matches("PMC[1-9][0-9]*") || !criterion.matches("[A-Z][A-Z0-9]{0,15}")
                || !sourceSha.matches("[a-f0-9]{64}") || !facts.isObject())
            throw new IllegalArgumentException("SOURCE_REVIEW_IDENTITY_INVALID");
        var library=library();
        if(library==null)throw new IOException("SOURCE_LIBRARY_UNAVAILABLE");
        Path original=confined(library,library.resolve("papers").resolve(pmcid).resolve(pmcid+".xml"));
        if(!sourceSha.equals(sha(read(original,20*1024*1024))))throw new IOException("SOURCE_ORIGINAL_CHANGED");
        var record=mapper.createObjectNode().put("schemaVersion","public-literature-reviewed-material-v1")
                .put("pmcid",pmcid).put("criterion",criterion).put("sourceSha256",sourceSha)
                .put("source","https://pmc.ncbi.nlm.nih.gov/articles/"+pmcid+"/").put("scoringAuthority",false);
        record.set("material",facts.deepCopy());
        retainReview(library,pmcid,criterion,record);
    }

    /** Called with a record from the validated public experiment catalog, never a patient/run payload. */
    synchronized void retainExperiment(JsonNode experiment) throws IOException {
        var source=experiment.path("source");
        String pmid=source.path("pmid").asText(), url=source.path("url").asText();
        if(!pmid.matches("[1-9][0-9]{0,14}") || !source.path("artifactSha256").asText().matches("[a-f0-9]{64}"))
            throw new IOException("SOURCE_EXPERIMENT_IDENTITY_INVALID");
        retainExperimentRecord(pmid,url,"RNA",experiment);
    }

    synchronized void retainPublishedScreen(JsonNode match) throws IOException {
        var source=match.path("source");
        String id=source.path("sourceId").asText();
        if(!id.matches("PMID:[1-9][0-9]{0,14}") || !source.path("rawSha256").asText().matches("[a-f0-9]{64}")
                || !match.path("record").isObject()) return;
        retainExperimentRecord(id.substring(5),source.path("url").asText(),"BP1",match);
    }

    private void retainExperimentRecord(String pmid,String url,String criterion,JsonNode experiment) throws IOException {
        var library=library();
        if(library==null)throw new IOException("SOURCE_LIBRARY_UNAVAILABLE");
        var pmc=java.util.regex.Pattern.compile("https://pmc\\.ncbi\\.nlm\\.nih\\.gov/articles/(PMC[1-9][0-9]*)/?").matcher(url);
        String paper=pmc.matches()?pmc.group(1):"PMID-"+pmid;
        var record=mapper.createObjectNode().put("schemaVersion","public-literature-experiment-material-v1")
                .put("paperId",paper).put("pmid",pmid).put("scoringAuthority",false);
        record.set("material",experiment.deepCopy());
        retainReview(library,paper,criterion,record);
    }

    private void retainReview(Path library,String paper,String criterion,JsonNode record) throws IOException {
        byte[] bytes=mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
        if(bytes.length>8*1024*1024)throw new IOException("SOURCE_REVIEW_SIZE_LIMIT");
        var directory=library.resolve("reviewed-evidence").resolve(paper).resolve("reviews").resolve(criterion);
        Files.createDirectories(directory);
        if(!directory.toRealPath().startsWith(library.toRealPath()))throw new IOException("SOURCE_ARCHIVE_SCOPE_INVALID");
        retainFile(directory,sha(bytes)+".json",bytes);
    }

    private void retainFile(Path directory, String name, byte[] bytes) throws IOException {
        Path file=directory.resolve(name);
        if(Files.exists(file)) {
            if(!java.util.Arrays.equals(read(confined(directory,file),bytes.length),bytes))
                throw new IOException("SOURCE_ARCHIVE_IDENTITY_MISMATCH");
            return;
        }
        Path staged=Files.createTempFile(directory,"region-",".tmp");
        try {
            Files.write(staged,bytes);
            Files.move(staged,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(staged); }
    }

    @Nullable private ObjectNode cachedRegion(Path library,Reference ref,@Nullable PaperIdentity identity) {
        if (identity == null) return null;
        try{return lookup(library,ref,identity);}catch(IOException unavailable){return null;}
    }
    private Path imageFile(Path folder,JsonNode annotation) throws IOException {
        String value=annotation.path("path").asText();if(value.isBlank())throw new IOException("SOURCE_IMAGE_UNAVAILABLE");
        Path file;
        try{file=Path.of(value).toAbsolutePath().normalize();}catch(java.nio.file.InvalidPathException invalid){throw new IOException("SOURCE_IMAGE_PATH_INVALID",invalid);}
        if(!file.startsWith(folder.toAbsolutePath().normalize())||!Files.isRegularFile(file)
                ||!file.toRealPath().startsWith(folder.toRealPath()))throw new IOException("SOURCE_IMAGE_SCOPE_INVALID");
        verifiedPng(file,annotation.path("sha256").asText());
        return file;
    }
    private byte[] verifiedPng(Path file,String expectedSha) throws IOException {
        byte[] bytes=read(file,24*1024*1024);
        if(bytes.length<8||bytes[0]!=(byte)137||bytes[1]!='P'||bytes[2]!='N'||bytes[3]!='G'
                ||!sha(bytes).equals(expectedSha))throw new IOException("SOURCE_IMAGE_IDENTITY_MISMATCH");
        return bytes;
    }
    public synchronized ObjectNode regions(List<Reference> refs) throws IOException {
        if(refs==null||refs.isEmpty()||refs.size()>32)throw new IllegalArgumentException("SOURCE_REFERENCE_LIMIT");
        refs.forEach(LiteratureSourceAssets::validate);
        var out=mapper.createObjectNode();var rows=out.putArray("regions");var library=library();
        if(library==null){refs.forEach(ref->rows.add(unavailable(ref)));return out;}
        var identities = new HashMap<String, PaperIdentity>();
        for (String pmcid : refs.stream().filter(ref->ref.figure()==null).map(ref->ref.pmcid()).distinct().toList()) {
            try { identities.put(pmcid, paperIdentity(library, pmcid)); }
            catch (IOException unavailable) { /* Other papers remain independently readable. */ }
        }
        var foundByReference=new HashMap<Reference,ObjectNode>();
        var missing=new ArrayList<Reference>();
        for (var ref : refs) {
            if(foundByReference.containsKey(ref))continue;
            var figure=ref.figure();
            if(figure!=null) {
                var found=lookupReviewedFigure(library,ref,figure);
                if(found!=null)foundByReference.put(ref,found);
                continue;
            }
            var identity = identities.get(ref.pmcid());
            if (identity == null) continue;
            var found=cachedRegion(library,ref,identity);
            if(found==null)missing.add(ref);else foundByReference.put(ref,found);
        }
        if(!missing.isEmpty()){
            var unique=missing.stream().distinct().toList();
            try{prepare(library,unique);}catch(IOException unavailable){/* Original quotations remain available when rendering is unavailable. */}
            // Preparation can populate source-media with previously registered figure originals.
            for(String pmcid:unique.stream().map(ref->ref.pmcid()).distinct().toList()){
                try{identities.put(pmcid,paperIdentity(library,pmcid));}
                catch(IOException unavailable){identities.remove(pmcid);}
            }
            for(var ref:unique){
                var found=cachedRegion(library,ref,identities.get(ref.pmcid()));
                if(found!=null)foundByReference.put(ref,found);
            }
        }
        for(var ref:refs){var found=foundByReference.get(ref);rows.add(found==null?unavailable(ref):found);}
        return out;
    }
    private void prepare(Path library,List<Reference> refs) throws IOException {
        var c=config();if(c==null)return;
        Path project=Path.of(c.path("projectRoot").asText()).toAbsolutePath().normalize();
        Path python=project.resolve(c.path("python").asText()).normalize();
        if(!python.startsWith(project.resolve("local/tools"))||!Files.isRegularFile(python))return;
        Path script=project.resolve("scripts/denovo/prepare_literature_source_view.py");if(!Files.isRegularFile(script))return;
        var request=mapper.createObjectNode();request.set("references",mapper.valueToTree(refs));byte[] bytes=mapper.writeValueAsBytes(request);
        Path directory=library.resolve("source-screenshots/requests");Files.createDirectories(directory);
        String key=sha(bytes);Path input=directory.resolve(key+".json");Files.write(input,bytes);
        var process=new ProcessBuilder(python.toString(),script.toString(),"--config",configuration.toString(),"--request",input.toString())
                .directory(project.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve(key+".log").toFile()).start();
        try{if(!process.waitFor(45,TimeUnit.SECONDS)){process.descendants().forEach(child->{if(child!=null)child.destroyForcibly();});process.destroyForcibly();}}
        catch(InterruptedException interrupted){process.descendants().forEach(child->{if(child!=null)child.destroyForcibly();});process.destroyForcibly();Thread.currentThread().interrupt();}
    }
    private Path folder(Path library,String pmcid,String key) throws IOException {
        if(!pmcid.matches("PMC[1-9][0-9]*")||!key.matches("[a-f0-9]{24}"))throw new IOException("SOURCE_ASSET_ID_INVALID");
        Path folder=library.resolve("source-screenshots").resolve(pmcid).resolve(key);
        if(!Files.isDirectory(folder)||!folder.toRealPath().startsWith(library.toRealPath()))throw new IOException("SOURCE_ASSET_SCOPE_INVALID");
        var manifest=mapper.readTree(read(confined(library,folder.resolve("manifest.json")),4*1024*1024));
        if(manifest==null||!manifest.path("sourceSha256").asText().equals(sha(read(confined(library,library.resolve("papers").resolve(pmcid).resolve(pmcid+".xml")),20*1024*1024))))
            throw new IOException("SOURCE_ORIGINAL_CHANGED");
        if(!validAttachments(library,pmcid,manifest.path("attachmentSha256s")))throw new IOException("SOURCE_ATTACHMENT_CHANGED");
        return folder;
    }
    public byte[] image(String pmcid,String key,String annotationKey) throws IOException {
        if(!annotationKey.matches("[a-f0-9]{64}"))throw new IOException("SOURCE_ASSET_ID_INVALID");
        var library=library();if(library==null)throw new IOException("SOURCE_LIBRARY_UNAVAILABLE");
        if(isSha256(key)&&pmcid.matches("PMC[1-9][0-9]*")) {
            var region=reviewedFigureRegions(library,pmcid,key).path(annotationKey);
            return reviewedFigureImage(library,pmcid,key,annotationKey,region);
        }
        var folder=folder(library,pmcid,key);var data=mapper.readTree(read(folder.resolve("manifest.json"),4*1024*1024));
        if(data==null)throw new IOException("SOURCE_IMAGE_UNAVAILABLE");
        var annotation=data.path("annotations").path(annotationKey);
        if(!annotation.path("available").asBoolean())throw new IOException("SOURCE_IMAGE_UNAVAILABLE");
        return read(imageFile(folder,annotation),24*1024*1024);
    }
    public String fullText(String pmcid,String key) throws IOException {
        var library=library();if(library==null)throw new IOException("SOURCE_LIBRARY_UNAVAILABLE");
        var folder=folder(library,pmcid,key);var file=folder.resolve("fulltext.html");
        if(!file.toRealPath().startsWith(folder.toRealPath()))throw new IOException("SOURCE_ASSET_SCOPE_INVALID");
        String html=new String(read(file,8*1024*1024),StandardCharsets.UTF_8);
        String mediaPrefix=library.resolve("source-screenshots/source-media").resolve(pmcid).toUri().toString();
        if(!mediaPrefix.endsWith("/"))mediaPrefix+="/";
        // The local document renderer escapes JATS text. Restrict its media to this
        // public-paper route and discard renderer scripts in the served full text.
        return html.replaceAll("(?is)<script\\b[^>]*>.*?</script>","")
                .replace(mediaPrefix,
                        "/variant-analyses/acmg/source-view/papers/"+pmcid+"/media/")
                .replace("img-src file:","img-src 'self'");
    }
    public byte[] media(String pmcid,String filename) throws IOException {
        if(!pmcid.matches("PMC[1-9][0-9]*")||!validMediaName(filename))throw new IOException("SOURCE_MEDIA_ID_INVALID");
        var library=library();if(library==null)throw new IOException("SOURCE_LIBRARY_UNAVAILABLE");
        var root=library.resolve("source-screenshots/source-media").resolve(pmcid);var file=root.resolve(filename);
        confined(library,file);
        if(!file.toRealPath().startsWith(root.toRealPath()))throw new IOException("SOURCE_MEDIA_SCOPE_INVALID");
        return read(file,24*1024*1024);
    }
}
