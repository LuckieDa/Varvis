package org.nbscn.varvis.variantanalysis.provider;

import com.sun.net.httpserver.HttpServer;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.IndexFactory;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.variant.vcf.VCFCodec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import java.nio.file.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BoundedRangeTabixTest {
    @TempDir Path temp;
    HttpServer server;ExecutorService executor;byte[] data,index;String url;String fault="";AtomicInteger calls=new AtomicInteger();
    static final String ROW="chr1\t11801166\t.\tC\tT\t.\tPASS\tAF_joint=0.01;AC_joint=100;AN_joint=10000";
    @BeforeAll static void retryOff(){System.setProperty("jdk.httpclient.disableRetryConnect","true");}
    @BeforeEach void start()throws Exception{
        Path file=temp.resolve("synthetic.vcf.gz");
        try(var out=new BlockCompressedOutputStream(file.toFile())){out.write(("##fileformat=VCFv4.2\n##contig=<ID=chr1,length=248956422>\n##INFO=<ID=AF_joint,Number=A,Type=Float,Description=\"Synthetic\">\n##INFO=<ID=AC_joint,Number=A,Type=Integer,Description=\"Synthetic\">\n##INFO=<ID=AN_joint,Number=1,Type=Integer,Description=\"Synthetic\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"+ROW+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        IndexFactory.createTabixIndex(file.toFile(),new VCFCodec(),TabixFormat.VCF,null).writeBasedOnFeatureFile(file.toFile());
        data=Files.readAllBytes(file);index=Files.readAllBytes(Path.of(file+".tbi"));
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",exchange->{int request=calls.incrementAndGet();boolean idx=exchange.getRequestURI().getPath().endsWith(".tbi");
            try{
                byte[] bytes=idx?index:data;long first=0,last=bytes.length-1;String range=exchange.getRequestHeaders().getFirst("Range");
                assertEquals("GET",exchange.getRequestMethod());assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                if(!idx){assertNotNull(range);String[] parts=range.substring(6).split("-");first=Long.parseLong(parts[0]);last=Long.parseLong(parts[1]);}
                var h=exchange.getResponseHeaders();h.set("ETag",fault.equals("changed")&&request>1?"\"changed\"":idx?"\"index\"":"\"data\"");
                if(!idx)h.set("Content-Range","bytes "+(fault.equals("wrong-range")?first+1:first)+"-"+last+"/"+(fault.equals("wrong-total")?bytes.length+1:bytes.length));
                if(fault.equals("encoding"))h.set("Content-Encoding","gzip");
                if(fault.equals("redirect")){h.set("Location",url);exchange.sendResponseHeaders(302,-1);return;}
                int status=idx?200:fault.equals("full200")?200:206;int length=(int)(last-first+1);
                exchange.sendResponseHeaders(status,length);
                if(fault.equals("stall")){Thread.sleep(2000);return;}
                if(fault.equals("short")){exchange.getResponseBody().write(bytes,(int)first,Math.max(1,length/2));return;}
                exchange.getResponseBody().write(bytes,(int)first,length);
            }catch(Exception ignored){}finally{exchange.close();}});
        server.start();url="http://localhost:"+server.getAddress().getPort()+"/synthetic.vcf.gz";
    }
    @AfterEach void stop()throws Exception{server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    BoundedRangeTabix.Binding binding(){return new BoundedRangeTabix.Binding(data.length,index.length,"\"data\"","\"index\"");}
    List<Map<String,Object>> query(BoundedRangeTabix.Limits limits)throws Exception{var receipts=new ArrayList<Map<String,Object>>();BoundedRangeTabix.query(url,url+".tbi",binding(),limits,temp,List.of("chr1:11801166-11801166"),temp.resolve("out.vcf"),receipts);return receipts;}
    @Test void realTabixFixtureAndProductionParser()throws Exception{
        var receipts=query(BoundedRangeTabix.Limits.joint());assertEquals(ROW,Files.readString(temp.resolve("out.vcf")).trim());assertEquals(2,calls.get());assertEquals("SUCCEEDED",receipts.get(0).get("status"));
        var settings=new VariantAnalysisProperties();settings.getGnomad().setPopulationInputEnabled(true);
        var provider=new GnomadTabixProvider(settings,GnomadPopulationInputProfile.loadExecutableV2(),true,true);
        var targetMethod=GnomadTabixProvider.class.getDeclaredMethod("remoteJointTarget",String.class);targetMethod.setAccessible(true);Object target=targetMethod.invoke(provider,"1");
        var parse=GnomadTabixProvider.class.getDeclaredMethod("parseRemoteBatchOutput",target.getClass(),Path.class,Map.class);parse.setAccessible(true);
        var key=new NormalizedVariantKey("GRCh38","1",11801166,"C","T");
        @SuppressWarnings("unchecked") var parsed=(Map<NormalizedVariantKey,?>)parse.invoke(provider,target,temp.resolve("out.vcf"),Map.of("1:11801166",List.of(key)));
        assertTrue(parsed.containsKey(key));assertEquals(2,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"full200","wrong-range","wrong-total","changed","encoding","redirect","short","stall"})
    void rejectsProtocolAndBodyFailures(String value){fault=value;long start=System.nanoTime();assertThrows(IOException.class,()->query(new BoundedRangeTabix.Limits(100000,8,Duration.ofMillis(250),Duration.ofMillis(500))));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1500);assertTrue(calls.get()<=2);}
    @Test void damagedIndexIsFailureNotNoRecord(){index=new byte[20];assertThrows(IOException.class,()->query(BoundedRangeTabix.Limits.joint()));}
    @Test void damagedBgzfIsFailureNotNoRecord(){Arrays.fill(data,(byte)0);assertThrows(IOException.class,()->query(BoundedRangeTabix.Limits.joint()));}
    @Test void requestLimitAndByteLimit(){assertThrows(IOException.class,()->query(new BoundedRangeTabix.Limits(100000,1,Duration.ofSeconds(1),Duration.ofSeconds(2))));assertEquals(1,calls.get());calls.set(0);assertThrows(IOException.class,()->query(new BoundedRangeTabix.Limits(index.length-1,8,Duration.ofSeconds(1),Duration.ofSeconds(2))));assertEquals(1,calls.get());}
    @Test void seekCacheBoundsAndClose()throws Exception{
        var receipt=new LinkedHashMap<String,Object>();try(var session=new BoundedRangeTabix.Session(url,url+".tbi",binding(),BoundedRangeTabix.Limits.joint(),receipt)){
            var stream=new BoundedRangeTabix.RangeStream(session);assertThrows(IOException.class,()->stream.seek(-1));assertThrows(IOException.class,()->stream.seek(data.length+1L));
            assertEquals(data[0]&255,stream.read());stream.seek(0);assertEquals(data[0]&255,stream.read());assertEquals(1,calls.get());
            stream.seek(data.length);assertEquals(-1,stream.read());stream.close();assertThrows(IOException.class,stream::read);assertEquals(1,calls.get());}
    }
    @Test void cancellationAndTotalBudget()throws Exception{
        var receipt=new LinkedHashMap<String,Object>();try(var session=new BoundedRangeTabix.Session(url,url+".tbi",binding(),new BoundedRangeTabix.Limits(100000,8,Duration.ofMillis(50),Duration.ofMillis(50)),receipt)){
            Thread.currentThread().interrupt();try{assertThrows(IOException.class,session::fetchIndex);}finally{Thread.interrupted();}
            Thread.sleep(70);assertThrows(IOException.class,session::fetchIndex);assertEquals(0,calls.get());}
    }
    @Test void crossBlockReadAndChangedObjectAreBounded()throws Exception{
        int block=BoundedRangeTabix.BLOCK;
        data=new byte[2*block];new Random(7).nextBytes(data);
        try(var session=new BoundedRangeTabix.Session(url,url+".tbi",binding(),BoundedRangeTabix.Limits.joint(),new LinkedHashMap<>());
            var stream=new BoundedRangeTabix.RangeStream(session)){
            stream.read();stream.seek(block-6);byte[] b=new byte[20];assertEquals(6,stream.read(b,0,20));assertArrayEquals(Arrays.copyOfRange(data,block-6,block),Arrays.copyOf(b,6));
            assertEquals(14,stream.read(b,6,14));assertArrayEquals(Arrays.copyOfRange(data,block-6,block+14),b);assertEquals(2,calls.get());stream.seek(0);stream.read();assertEquals(2,calls.get());
        }
        calls.set(0);fault="changed";
        try(var session=new BoundedRangeTabix.Session(url,url+".tbi",binding(),BoundedRangeTabix.Limits.joint(),new LinkedHashMap<>());
            var stream=new BoundedRangeTabix.RangeStream(session)){
            stream.read();stream.seek(block);assertThrows(IOException.class,stream::read);assertThrows(IOException.class,stream::read);assertEquals(2,calls.get());
        }
    }
    @Test void defaultsAndIdentityAreSeparate()throws Exception{
        var settings=new VariantAnalysisProperties();assertEquals("TABIX",settings.getGnomad().getRemoteTransport());assertThrows(IllegalArgumentException.class,()->settings.getGnomad().setRemoteTransport("silent-fallback"));
        Path manifest=temp.resolve("bindings.json");Files.writeString(manifest,"{\"version\":\"jdk-range-tabix-v1\",\"objects\":{\""+url+"\":{\"dataLength\":"+data.length+",\"indexLimit\":"+index.length+",\"dataEtag\":\"\\\"data\\\"\",\"indexEtag\":\"\\\"index\\\"\"}}}");
        settings.getGnomad().setRemoteTransport(BoundedRangeTabix.VERSION);settings.getGnomad().setRangeBindingsFile(manifest.toString());
        assertEquals(BoundedRangeTabix.VERSION,new GnomadTabixProvider(settings).transportIdentity().get("version"));assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"data-crc-corrupt","index-crc-corrupt","index-invalid-reference-count"})
    void frozenIntegrityCounterexamplesAreControlled(String mode)throws Exception {
        String frozen=System.getProperty("varvis.range.frozen");
        if(frozen!=null) {
            Path fixture=Path.of(frozen).resolve(mode);
            data=Files.readAllBytes(fixture.resolve("data.bgz"));index=Files.readAllBytes(fixture.resolve("index.tbi"));
        } else if(mode.equals("data-crc-corrupt")) flipCrc(data);
        else if(mode.equals("index-crc-corrupt")) flipCrc(index);
        else {
            byte[] decoded;try(var in=new htsjdk.samtools.util.BlockCompressedInputStream(new ByteArrayInputStream(index))){decoded=in.readAllBytes();}
            java.nio.ByteBuffer.wrap(decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4,Integer.MAX_VALUE);
            Path malformed=temp.resolve("bad.tbi");try(var out=new BlockCompressedOutputStream(malformed.toFile())){out.write(decoded);}index=Files.readAllBytes(malformed);
        }
        Throwable observed=null;
        try { query(BoundedRangeTabix.Limits.joint()); } catch(Throwable failure) { observed=failure; }
        // Test-only capture records the pre-fix VM-limit error without aborting the other counterexamples.
        assertInstanceOf(IOException.class,observed);
        assertFalse(Files.exists(temp.resolve("out.vcf")));
    }
    @Test void rangeReceiptFlowsThroughProductionNativeValidator()throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var settings=new VariantAnalysisProperties();settings.getGnomad().setTabixExecutable("SYNTHETIC_NOT_EXECUTED");
        var policy=mapper.readTree(getClass().getResourceAsStream("/acmg/ba1-native-subtype-source-policy-v1.json"));
        var helper=new Ba1SubtypeFilterSupplement(mapper,settings,policy,temp,(command,out,err,budget,resource,sha,diagnostics)->
                BoundedRangeTabix.query(url,url+".tbi",binding(),BoundedRangeTabix.Limits.nativeFilter(),temp,List.of("chr1:11801166-11801166"),out,diagnostics));
        var allele=mapper.createObjectNode().put("assembly","GRCh38").put("contig","1").put("position",11801166).put("reference","C").put("alternate","T");
        var result=helper.supplement(allele,Set.of("exomes"),"4.1","a".repeat(64));
        assertTrue(Ba1SubtypeFilterSupplement.verifiedPass(mapper,result,"exomes",allele,"a".repeat(64)),result.toString());
        assertTrue(Ba1SubtypeFilterSupplement.verifiedPass(mapper,result,"genomes",allele,"a".repeat(64)));
        fault="full200";var failed=helper.supplement(allele,Set.of("exomes"),"4.1","a".repeat(64));
        assertFalse(Ba1SubtypeFilterSupplement.verifiedPass(mapper,failed,"exomes",allele,"a".repeat(64)));
    }
    static void flipCrc(byte[] bytes){int size=(bytes[16]&255)+((bytes[17]&255)<<8)+1;bytes[size-8]^=1;}
    @Test void frozenValidControlRemainsReadable()throws Exception {
        String frozen=System.getProperty("varvis.range.frozen");
        if(frozen!=null) {
            data=Files.readAllBytes(Path.of(frozen,"valid","data.bgz"));
            index=Files.readAllBytes(Path.of(frozen,"valid","index.tbi"));
        }
        query(BoundedRangeTabix.Limits.joint());assertEquals(ROW,Files.readString(temp.resolve("out.vcf")).trim());
    }
    @ParameterizedTest @ValueSource(strings={"reference","name","bin","chunk","linear"})
    void allIndexAllocationCountsAreBounded(String field)throws Exception {
        byte[] decoded;try(var in=new htsjdk.samtools.util.BlockCompressedInputStream(new ByteArrayInputStream(index))){decoded=in.readAllBytes();}
        var b=java.nio.ByteBuffer.wrap(decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int binOffset=36+b.getInt(32),bins=b.getInt(binOffset),linear=binOffset+4;
        for(int i=0;i<bins;i++)linear+=8+b.getInt(linear+4)*16;
        int offset=switch(field){case "reference"->4;case "name"->32;case "bin"->binOffset;case "chunk"->binOffset+8;default->linear;};
        b.putInt(offset,Integer.MAX_VALUE);
        try(var session=new BoundedRangeTabix.Session(url,url+".tbi",binding(),BoundedRangeTabix.Limits.joint(),new LinkedHashMap<>())){
            assertThrows(IOException.class,()->BoundedRangeTabix.validateTbi(decoded,session));assertEquals(0,calls.get());
        }
    }
    @Test void crcProtectsLaterBgzfBlocksAcrossRangeBoundary()throws Exception {
        byte[] random=new byte[1700000];new Random(41).nextBytes(random);
        String row=ROW+";X="+Base64.getEncoder().encodeToString(random);
        Path file=temp.resolve("large.vcf.gz");
        try(var out=new BlockCompressedOutputStream(file.toFile())){
            out.write(("##fileformat=VCFv4.2\n##contig=<ID=chr1,length=248956422>\n##INFO=<ID=X,Number=1,Type=String,Description=\"Synthetic\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"+row+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        IndexFactory.createTabixIndex(file.toFile(),new VCFCodec(),TabixFormat.VCF,null).writeBasedOnFeatureFile(file.toFile());
        data=Files.readAllBytes(file);index=Files.readAllBytes(Path.of(file+".tbi"));assertTrue(data.length>BoundedRangeTabix.BLOCK);
        query(BoundedRangeTabix.Limits.joint());assertEquals(row,Files.readString(temp.resolve("out.vcf")).trim());
        int at=0;while(at<BoundedRangeTabix.BLOCK)at+=(data[at+16]&255)+((data[at+17]&255)<<8)+1;
        int size=(data[at+16]&255)+((data[at+17]&255)<<8)+1;data[at+size-8]^=1;
        assertThrows(IOException.class,()->query(BoundedRangeTabix.Limits.joint()));assertFalse(Files.exists(temp.resolve("out.vcf")));
    }
}
