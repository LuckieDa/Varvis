package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.tribble.readers.TabixReader;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

/** HTTP byte transport only: HTSJDK owns TBI/BGZF and the existing provider owns allele parsing. */
public final class BoundedRangeTabix {
    public static final String VERSION = "jdk-range-tabix-v1";
    private static final String INTEGRITY_VERSION = "htsjdk-crc-bounded-tbi-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    // Start at the consumer's position; never fetch an unused aligned prefix.
    static final int BLOCK = 128 * 1024;
    private static final int CACHE_BYTES = 8 * 1024 * 1024;
    private static final String WINDOW_VERSION = "demand-start-lru-v1";
    public record Binding(long dataLength, int indexLimit, String dataEtag, String indexEtag) {
        public Binding {
            if (dataLength < 0 || indexLimit < 1 || indexLimit > 4 * 1024 * 1024)
                throw new IllegalArgumentException("RANGE_BINDING_INVALID");
        }
    }
    public record Limits(long bytes, int requests, Duration perRequest, Duration total) {
        public Limits {
            if (bytes < 1 || bytes > 16 * 1024 * 1024 || requests < 1 || requests > 64
                    || perRequest.isNegative() || perRequest.isZero() || perRequest.compareTo(Duration.ofSeconds(20)) > 0
                    || total.isNegative() || total.isZero() || total.compareTo(Duration.ofSeconds(120)) > 0)
                throw new IllegalArgumentException("RANGE_BUDGET_INVALID");
        }
        public static Limits joint() { return new Limits(16*1024*1024,64,Duration.ofSeconds(20),Duration.ofSeconds(120)); }
        public static Limits nativeFilter() { return new Limits(4*1024*1024,16,Duration.ofSeconds(20),Duration.ofSeconds(60)); }
    }
    public static Map<String, Binding> bindings(String file) throws IOException {
        if (file == null || file.isBlank()) throw new IOException("RANGE_BINDINGS_REQUIRED");
        byte[] raw;
        try (var in = Files.newInputStream(Path.of(file))) { raw=in.readNBytes(65537); }
        if (raw.length > 65536) throw new IOException("RANGE_BINDINGS_OVERSIZED");
        var tree=JSON.readTree(raw);
        if (!VERSION.equals(tree.path("version").asText()) || !tree.path("objects").isObject()) throw new IOException("RANGE_BINDINGS_INVALID");
        var result=new TreeMap<String,Binding>();
        var fields=tree.path("objects").properties().iterator();
        while(fields.hasNext()) { var e=fields.next(); validateUri(e.getKey());var n=e.getValue();
            result.put(e.getKey(),new Binding(n.path("dataLength").asLong(-1),n.path("indexLimit").asInt(),n.path("dataEtag").asText(""),n.path("indexEtag").asText(""))); }
        if(result.isEmpty())throw new IOException("RANGE_BINDINGS_EMPTY");
        return Collections.unmodifiableMap(result);
    }
    public static Map<String,Object> identity(String file) {
        try { return Map.of("version",VERSION,"integrityVersion",INTEGRITY_VERSION,"bindings",bindings(file),"jointLimits",Limits.joint().toString(),"nativeLimits",Limits.nativeFilter().toString(),"windowStrategy",WINDOW_VERSION,"windowBytes",BLOCK,"cacheBytes",CACHE_BYTES); }
        catch(IOException e) { throw new IllegalArgumentException("RANGE_BINDINGS_INVALID",e); }
    }
    static URI validateUri(String url) throws IOException {
        URI uri;
        try { uri=URI.create(url); } catch(IllegalArgumentException e) {throw new IOException("RANGE_URI_INVALID",e);}
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null) throw new IOException("RANGE_URI_INVALID");
        return uri;
    }
    public static void query(String data, String index, Binding binding, Limits limits, Path root,
                             List<String> regions, Path output, List<Map<String,Object>> diagnostics) throws IOException {
        if (!index.equals(data+".tbi")) throw new IOException("RANGE_INDEX_TARGET_MISMATCH");
        Path work=Files.createTempDirectory(Files.createDirectories(root),"jdk-range-");
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("transport",VERSION);receipt.put("dataUri",data);receipt.put("indexUri",index);
        receipt.put("attempt",1);receipt.put("maximumAttempts",1);receipt.put("status","FAILED");
        receipt.put("effectiveAttemptMillis",limits.total().toMillis());
        receipt.put("integrityVersion",INTEGRITY_VERSION);
        long start=System.nanoTime();
        try (Session session=new Session(data,index,binding,limits,receipt)) {
            Path localIndex=work.resolve("index.tbi");Files.write(localIndex,session.fetchIndex());
            // Validate with HTSJDK under our own handle scope before TabixReader's constructor.
            // Its legacy readIndex constructor does not close a malformed index on failure.
            try(var input=new htsjdk.samtools.util.BlockCompressedInputStream(localIndex.toFile())) {
                input.setCheckCrcs(true);
                byte[] decoded=input.readNBytes(32*1024*1024+1);
                if(decoded.length>32*1024*1024)throw new IOException("RANGE_INDEX_EXPANSION_LIMIT");
                validateTbi(decoded,session);
            }
            var indexChannels=new ArrayList<java.nio.channels.SeekableByteChannel>();
            try (var stream=new RangeStream(session);var tabix=new TabixReader(data,localIndex.toString(),stream,channel->{indexChannels.add(channel);return channel;});
                 var writer=Files.newBufferedWriter(output)) {
                enableDataCrc(tabix);
                long chars=0;
                for(String region:regions) {
                    session.check();var iterator=tabix.query(region);
                    if(iterator==null) continue;
                    String line;
                    while((line=iterator.next())!=null) {session.check();chars+=line.length()+1;
                        if(chars>4*1024*1024)throw new IOException("RANGE_RECORD_OUTPUT_LIMIT");writer.write(line);writer.newLine();}
                }
                session.check();
            } finally {for(var channel:indexChannels)channel.close();}
            receipt.put("status","SUCCEEDED");receipt.put("stdoutBytes",Files.size(output));
        } catch(IOException|RuntimeException e) {
            receipt.put("reason",e.toString());receipt.put("failureChain",failureChain(e));
            Files.deleteIfExists(output);throw new IOException("RANGE_QUERY_FAILED: "+e.getMessage(),e);
        }
        finally {
            receipt.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            receipt.put("completedAt",Instant.now().toString());diagnostics.add(Collections.unmodifiableMap(receipt));
            JSON.writerWithDefaultPrettyPrinter().writeValue(work.resolve("transport.json").toFile(),receipt);
        }
    }
    /** HTSJDK 2.24.1 exposes no TabixReader CRC switch. Configure its actual decoder before any query.
     * No global factory/default is changed; incompatible library internals fail closed. */
    private static void enableDataCrc(TabixReader tabix) throws IOException {
        try {
            var field=TabixReader.class.getDeclaredField("mFp");
            if(!field.trySetAccessible())throw new IOException("RANGE_CRC_DECODER_INACCESSIBLE");
            Object decoder=field.get(tabix);
            if(!(decoder instanceof htsjdk.samtools.util.BlockCompressedInputStream input))
                throw new IOException("RANGE_CRC_DECODER_INCOMPATIBLE");
            input.setCheckCrcs(true);
        } catch(ReflectiveOperationException|SecurityException e) {throw new IOException("RANGE_CRC_CONFIGURATION_FAILED",e);}
    }
    /** Structural allocation guard only. HTSJDK still owns index construction and genomic queries.
     * Every count is checked against both remaining bytes and an aggregate allocation ceiling. */
    static void validateTbi(byte[] raw,Session session) throws IOException {
        var b=ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if(b.remaining()<36 || b.getInt()!=0x01494254)throw new IOException("RANGE_TBI_HEADER");
        int refs=boundedCount(b,4096,8,"REFERENCE");
        b.position(b.position()+24);
        int names=boundedCount(b,1024*1024,1,"NAMES");
        int end=b.position()+names,terminated=0,nameLength=0;
        while(b.position()<end) {
            if(b.get()==0){if(nameLength==0)throw new IOException("RANGE_TBI_EMPTY_NAME");terminated++;nameLength=0;}
            else if(++nameLength>4096)throw new IOException("RANGE_TBI_NAME_LIMIT");
        }
        if(nameLength!=0 || terminated!=refs)throw new IOException("RANGE_TBI_NAME_COUNT");
        long totalBins=0,totalChunks=0,totalLinear=0;
        for(int ref=0;ref<refs;ref++) {
            session.check();
            int bins=boundedCount(b,37451,8,"BIN");
            if((totalBins+=bins)>262144)throw new IOException("RANGE_TBI_BIN_LIMIT");
            for(int bin=0;bin<bins;bin++) {
                session.check();
                if(b.remaining()<4)throw new IOException("RANGE_TBI_TRUNCATED_BIN");
                int id=b.getInt();if(id<0||id>37450)throw new IOException("RANGE_TBI_BIN_ID");
                int chunks=boundedCount(b,1048576,16,"CHUNK");
                if((totalChunks+=chunks)>1048576)throw new IOException("RANGE_TBI_CHUNK_LIMIT");
                b.position(b.position()+chunks*16);
            }
            int linear=boundedCount(b,32768,8,"LINEAR");
            if((totalLinear+=linear)>1048576)throw new IOException("RANGE_TBI_LINEAR_LIMIT");
            b.position(b.position()+linear*8);
        }
        if(b.remaining()!=0&&b.remaining()!=8)throw new IOException("RANGE_TBI_TRAILING_BYTES");
        session.check();
    }
    private static int boundedCount(ByteBuffer b,int ceiling,int width,String field) throws IOException {
        if(b.remaining()<4)throw new IOException("RANGE_TBI_TRUNCATED_"+field);
        int count=b.getInt();
        if(count<0||count>ceiling||count>b.remaining()/width)throw new IOException("RANGE_TBI_INVALID_"+field+"_COUNT");
        return count;
    }
    private static List<String> failureChain(Throwable error) {
        var chain = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause=error; cause!=null && chain.size()<16 && seen.add(cause); cause=cause.getCause())
            chain.add(cause.getClass().getName()+": "+Objects.toString(cause.getMessage(),""));
        return chain;
    }
    static final class Session implements AutoCloseable {
        final String data,index;final Binding binding;final Limits limits;final long deadline;
        final HttpClient client;final ExecutorService executor;final Map<String,Object> receipt;final List<Map<String,Object>> requests=new ArrayList<>();
        long bytes,length;int count;volatile boolean closed;String dataTag,indexTag;IOException failure;
        Session(String data,String index,Binding binding,Limits limits,Map<String,Object> receipt)throws IOException {
            validateUri(data);validateUri(index);this.data=data;this.index=index;this.binding=Objects.requireNonNull(binding);this.limits=limits;this.receipt=receipt;
            if(!"true".equals(System.getProperty("jdk.httpclient.disableRetryConnect")))throw new IOException("RANGE_CONNECT_RETRY_MUST_BE_DISABLED_AT_JVM_START");
            if(Runtime.version().feature()<21)throw new IOException("RANGE_JDK21_REQUIRED");
            deadline=System.nanoTime()+limits.total().toNanos();length=binding.dataLength();dataTag=binding.dataEtag();indexTag=binding.indexEtag();
            executor=Executors.newFixedThreadPool(2);
            client=HttpClient.newBuilder().executor(executor).connectTimeout(limits.perRequest()).followRedirects(HttpClient.Redirect.NEVER).build();
            receipt.put("requests",requests);receipt.put("startedAt",Instant.now().toString());receipt.put("limits",limits.toString());
        }
        void check() throws IOException {
            if(closed)throw new IOException("RANGE_CLOSED");
            if(failure!=null)throw new IOException("RANGE_PREVIOUS_FAILURE",failure);
            if(Thread.currentThread().isInterrupted())throw new IOException("RANGE_CANCELLED");
            if(System.nanoTime()>=deadline)throw new IOException("RANGE_TOTAL_TIMEOUT");
        }
        byte[] fetchIndex()throws IOException {return fetch(true,0,0);}
        byte[] fetch(boolean isIndex,long first,long last)throws IOException {
            check();if(count>=limits.requests())throw new IOException("RANGE_REQUEST_LIMIT");
            if(bytes>=limits.bytes())throw new IOException("RANGE_BYTE_LIMIT");
            String url=isIndex?index:data, pin=isIndex?indexTag:dataTag;
            long allowance=Math.min(limits.perRequest().toNanos(),deadline-System.nanoTime());
            var r=new LinkedHashMap<String,Object>();requests.add(r);r.put("method","GET");r.put("url",url);r.put("range",isIndex?"NONE":"bytes="+first+"-"+last);r.put("startedAt",Instant.now().toString());
            var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofNanos(allowance)).header("Accept-Encoding","identity").GET();
            if(!isIndex)builder.header("Range","bytes="+first+"-"+last);
            if(pin!=null&&!pin.isBlank())builder.header("If-Match",pin);
            r.put("ifMatch",Objects.toString(pin,""));r.put("allowanceNanos",allowance);
            long started=System.nanoTime();CompletableFuture<HttpResponse<byte[]>> future=null;
            try {
                count++;
                future=client.sendAsync(builder.build(),info->{
                    r.put("status",info.statusCode());r.put("headers",info.headers().map());
                    try {
                        if(info.statusCode()!=(isIndex?200:206))throw new IOException("RANGE_STATUS_REJECTED");
                        if(!info.headers().firstValue("content-encoding").orElse("identity").equals("identity"))throw new IOException("RANGE_ENCODING_REJECTED");
                        String tag=info.headers().firstValue("etag").orElse("");
                        if(tag.isBlank()||tag.startsWith("W/")||!tag.startsWith("\"")||!tag.endsWith("\""))throw new IOException("RANGE_STRONG_ETAG_REQUIRED");
                        if(pin!=null&&!pin.isBlank()&&!pin.equals(tag))throw new IOException("RANGE_OBJECT_CHANGED");
                        long expected=info.headers().firstValueAsLong("content-length").orElse(-1);
                        if(isIndex) {if(expected<1||expected>binding.indexLimit())throw new IOException("RANGE_INDEX_LENGTH");indexTag=tag;}
                        else {
                            String cr=info.headers().firstValue("content-range").orElse("");String prefix="bytes "+first+"-"+last+"/";
                            if(!cr.startsWith(prefix))throw new IOException("RANGE_CONTENT_RANGE");
                            long total=Long.parseLong(cr.substring(prefix.length()));
                            if(total<=last||(length!=0&&length!=total)||expected!=last-first+1)throw new IOException("RANGE_LENGTH_CHANGED");
                            length=total;dataTag=tag;
                        }
                        if(expected>limits.bytes()-bytes)throw new IOException("RANGE_BYTE_LIMIT");
                        return new LimitedBody((int)expected,this,r);
                    } catch(Exception e) { return rejected(e); }
                });
                byte[] value=future.get(Math.max(1,allowance-(System.nanoTime()-started)),TimeUnit.NANOSECONDS).body();
                try { r.put("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value))); }
                catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
                r.put("complete",true);return value;
            } catch(InterruptedException e) {Thread.currentThread().interrupt();r.put("failureChain",failureChain(e));failure=new IOException("RANGE_CANCELLED",e);throw failure;}
            catch(ExecutionException|TimeoutException|RuntimeException e) {
                r.put("failureChain",failureChain(e));
                String reason=System.nanoTime()>=deadline?"RANGE_TOTAL_TIMEOUT":e instanceof TimeoutException?"RANGE_REQUEST_TIMEOUT":"RANGE_HTTP_FAILED";
                failure=new IOException(reason,e);throw failure;
            }
            finally {if(future!=null&&!future.isDone())future.cancel(true);r.put("elapsedNanos",System.nanoTime()-started);r.put("completedAt",Instant.now().toString());}
        }
        public void close() throws IOException {
            closed=true;
            try { HttpClient.class.getMethod("shutdownNow").invoke(client); }
            catch(ReflectiveOperationException e){throw new IOException("RANGE_CLIENT_CLOSE_FAILED",e);}
            finally {executor.shutdownNow();receipt.put("requestsStarted",count);receipt.put("bodyBytes",bytes);receipt.put("dataLength",length);receipt.put("dataEtag",Objects.toString(dataTag,""));receipt.put("indexEtag",Objects.toString(indexTag,""));}
            try { if(!executor.awaitTermination(2,TimeUnit.SECONDS))throw new IOException("RANGE_EXECUTOR_CLOSE_FAILED"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("RANGE_CLOSE_INTERRUPTED",e);}
            receipt.put("clientShutdown",true);
        }
    }
    static HttpResponse.BodySubscriber<byte[]> rejected(Throwable failure) {
        return new HttpResponse.BodySubscriber<>() {
            public CompletionStage<byte[]> getBody(){return CompletableFuture.failedFuture(failure);}
            public void onSubscribe(Subscription s){s.cancel();}public void onNext(List<ByteBuffer>b){}public void onError(Throwable e){}public void onComplete(){}
        };
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final int expected;final Session session;final Map<String,Object> receipt;final ByteArrayOutputStream out=new ByteArrayOutputStream();
        final CompletableFuture<byte[]> result=new CompletableFuture<>();Subscription subscription;
        LimitedBody(int expected,Session session,Map<String,Object> receipt){this.expected=expected;this.session=session;this.receipt=receipt;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> items){try{long n=0;for(var b:items)n+=b.remaining();
            long remaining=session.limits.bytes()-session.bytes;session.bytes+=n;
            receipt.put("bodyBytes",((Number)receipt.getOrDefault("bodyBytes",0L)).longValue()+n);
            session.check();if(n>expected-out.size()||n>remaining)throw new IOException("RANGE_BODY_LIMIT");
            for(var b:items){byte[] v=new byte[b.remaining()];b.get(v);out.writeBytes(v);}subscription.request(1);
        }catch(Exception e){subscription.cancel();result.completeExceptionally(e);}}
        public void onError(Throwable e){result.completeExceptionally(e);}
        public void onComplete(){if(out.size()!=expected)result.completeExceptionally(new IOException("RANGE_SHORT_BODY"));else result.complete(out.toByteArray());}
    }
    static final class RangeStream extends SeekableStream {
        final Session session;final int windowBytes;long pos;boolean closed;
        long cacheBytes,returnedBytes,hits,reads;final List<Map<String,Object>> events=new ArrayList<>();
        final LinkedHashMap<Long,byte[]> cache=new LinkedHashMap<>(16,0.75f,true);
        RangeStream(Session session){this(session,BLOCK);}
        RangeStream(Session session,int windowBytes){
            if(windowBytes<1||windowBytes>256*1024)throw new IllegalArgumentException("RANGE_WINDOW_LIMIT");
            this.session=session;this.windowBytes=windowBytes;
            session.receipt.put("windowStrategy",WINDOW_VERSION);session.receipt.put("windowBytes",windowBytes);
            session.receipt.put("cacheLimitBytes",CACHE_BYTES);session.receipt.put("readEvents",events);
        }
        public long length(){return session.length==0?Long.MAX_VALUE:session.length;}
        public long position(){return pos;}
        public void seek(long p)throws IOException{check();if(p<0||(session.length>0&&p>session.length))throw new IOException("RANGE_SEEK_BOUNDS");pos=p;}
        void check()throws IOException{session.check();if(closed)throw new IOException("RANGE_STREAM_CLOSED");}
        public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        public int read(byte[] b,int offset,int len)throws IOException{
            Objects.checkFromIndexSize(offset,len,b.length);check();if(len==0)return 0;if(eof())return -1;
            long start=pos;
            for(var entry:cache.entrySet())if(pos>=entry.getKey()&&pos-entry.getKey()<entry.getValue().length){start=entry.getKey();break;}
            byte[] value=cache.get(start);boolean hit=value!=null;
            var event=new LinkedHashMap<String,Object>();event.put("requestedPosition",pos);event.put("requestedBytes",len);event.put("cacheHit",hit);
            if(events.size()<4096)events.add(event);
            reads++;if(hit)hits++;
            try {
                if(value==null){long end=Math.addExact(start,windowBytes-1L);if(session.length>0)end=Math.min(end,session.length-1);
                    event.put("windowStart",start);event.put("windowEnd",end);
                    value=session.fetch(false,start,end);cache.put(start,value);cacheBytes+=value.length;
                    while(cacheBytes>CACHE_BYTES){var first=cache.entrySet().iterator().next();cacheBytes-=first.getValue().length;cache.remove(first.getKey());}
                }
                int at=(int)(pos-start),n=Math.min(len,value.length-at);if(n<=0)throw new IOException("RANGE_INVALID_BLOCK");
                System.arraycopy(value,at,b,offset,n);pos+=n;returnedBytes+=n;
                event.put("windowStart",start);event.put("windowEnd",start+value.length-1);event.put("returnedBytes",n);
                return n;
            } finally {
                session.receipt.put("consumerReadCalls",reads);session.receipt.put("consumerReturnedBytes",returnedBytes);
                session.receipt.put("cacheHits",hits);session.receipt.put("cachedBytes",cacheBytes);
                session.receipt.put("readEventsOmitted",Math.max(0,reads-events.size()));
            }
        }
        public boolean eof(){return session.length>0&&pos>=session.length;}
        public String getSource(){return session.data;}
        public void close(){closed=true;cache.clear();cacheBytes=0;session.receipt.put("cacheReleased",true);}
    }
}
