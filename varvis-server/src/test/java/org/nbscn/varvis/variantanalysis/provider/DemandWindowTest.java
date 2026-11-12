package org.nbscn.varvis.variantanalysis.provider;

import com.sun.net.httpserver.HttpServer;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.IndexFactory;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.tribble.readers.TabixReader;
import htsjdk.variant.vcf.VCFCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DemandWindowTest {
    @TempDir Path temp;
    @Test void delayedRateLimitedTabixComparisonAndRandomSeeks() throws Exception {
        byte[] random=new byte[600000];new Random(172).nextBytes(random);
        String large=Base64.getEncoder().encodeToString(random);
        String row="chr1\t11801166\t.\tC\tT\t.\tPASS\tX=target";
        Path file=temp.resolve("synthetic.vcf.gz");
        try(var out=new BlockCompressedOutputStream(file.toFile())) {
            out.write(("##fileformat=VCFv4.2\n##contig=<ID=chr1,length=248956422>\n##INFO=<ID=X,Number=1,Type=String,Description=\"Synthetic\">\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr1\t1\t.\tA\tC\t.\tPASS\tX="+large+"\n"+row+"\nchr1\t11801167\t.\tC\tG\t.\tPASS\tX="+large+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        IndexFactory.createTabixIndex(file.toFile(),new VCFCodec(),TabixFormat.VCF,null).writeBasedOnFeatureFile(file.toFile());
        byte[] data=Files.readAllBytes(file);Path index=Path.of(file+".tbi");
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",exchange->{try {
            String[] range=exchange.getRequestHeaders().getFirst("Range").substring(6).split("-");
            int first=Integer.parseInt(range[0]),last=Integer.parseInt(range[1]);Thread.sleep(75);
            exchange.getResponseHeaders().set("ETag","\"data\"");exchange.getResponseHeaders().set("Content-Range","bytes "+first+"-"+last+"/"+data.length);
            exchange.sendResponseHeaders(206,last-first+1);
            for(int p=first;p<=last;p+=16384){exchange.getResponseBody().write(data,p,Math.min(16384,last-p+1));exchange.getResponseBody().flush();Thread.sleep(30);}
        }catch(Exception ignored){}finally{exchange.close();}});server.start();
        var results=new ArrayList<Map<String,Object>>();String url="http://localhost:"+server.getAddress().getPort()+"/synthetic";
        var binding=new BoundedRangeTabix.Binding(data.length,4096,"\"data\"","\"index\"");
        try {
            for(int size:new int[]{1048576,131072,262144}) {
                var receipt=new LinkedHashMap<String,Object>();long started=System.nanoTime();
                try(var session=new BoundedRangeTabix.Session(url,url+".tbi",binding,BoundedRangeTabix.Limits.joint(),receipt);
                    SeekableStream stream=size==1048576?new LegacyAligned(session):new BoundedRangeTabix.RangeStream(session,size);
                    var reader=new TabixReader(url,index.toString(),stream)) {
                    var it=reader.query("chr1:11801166-11801166");assertNotNull(it);assertEquals(row,it.next());assertNull(it.next());
                    receipt.put("size",size);receipt.put("exactOutput",row);
                    List<long[]> consumed;
                    if(stream instanceof LegacyAligned legacy){receipt.put("firstRequestedPosition",legacy.firstPosition);consumed=legacy.consumed;}
                    else {
                        receipt.put("firstRequestedPosition",((List<?>)receipt.get("readEvents")).get(0));consumed=new ArrayList<>();
                        for(var event:((BoundedRangeTabix.RangeStream)stream).events){long begin=((Number)event.get("requestedPosition")).longValue();consumed.add(new long[]{begin,begin+((Number)event.get("returnedBytes")).longValue()});}
                    }
                    consumed.sort(Comparator.comparingLong(x->x[0]));long used=0,end=-1;
                    for(var interval:consumed){used+=Math.max(0,interval[1]-Math.max(end,interval[0]));end=Math.max(end,interval[1]);}
                    receipt.put("uniqueConsumedBytes",used);receipt.put("prefetchedUnusedBytes",session.bytes-used);
                }
                receipt.put("elapsedMillis",(System.nanoTime()-started)/1000000);results.add(receipt);
            }
            assertTrue((long)results.get(1).get("bodyBytes")<(long)results.get(0).get("bodyBytes"));
            assertTrue((long)results.get(1).get("bodyBytes")<=(long)results.get(2).get("bodyBytes"));
            var receipt=new LinkedHashMap<String,Object>();
            try(var session=new BoundedRangeTabix.Session(url,url+".tbi",binding,BoundedRangeTabix.Limits.joint(),receipt);
                var stream=new BoundedRangeTabix.RangeStream(session)) {
                for(int at:new int[]{337292,337300,468355,337280,337292,700000,337300}) {
                    stream.seek(at);byte[] b=new byte[57];int got=stream.read(b,0,b.length);
                    assertArrayEquals(Arrays.copyOfRange(data,at,at+got),Arrays.copyOf(b,got));
                }
                assertTrue((long)receipt.get("cacheHits")>0);assertTrue((long)receipt.get("cachedBytes")<=8388608);
            }
            String evidence=System.getProperty("varvis.window.evidence");
            if(evidence!=null)new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(evidence).toFile(),Map.of("fixtureOnly",true,"serverLatencyMillis",75,"chunkBytes",16384,"chunkDelayMillis",30,"comparisons",results,"randomSeeks",receipt));
        } finally {server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
    /** Benchmark-only prior aligned policy; production never selects this path. */
    static final class LegacyAligned extends SeekableStream {
        final BoundedRangeTabix.Session session;long position,firstPosition=-1;long block=-1;byte[] bytes;final List<long[]> consumed=new ArrayList<>();
        LegacyAligned(BoundedRangeTabix.Session session){this.session=session;}
        public long length(){return session.length;}public long position(){return position;}
        public void seek(long p){position=p;}
        public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        public int read(byte[] b,int off,int len)throws IOException {
            if(eof())return -1;if(firstPosition<0)firstPosition=position;
            long start=position/1048576*1048576;
            if(start!=block){bytes=session.fetch(false,start,Math.min(start+1048575,length()-1));block=start;}
            int n=Math.min(len,bytes.length-(int)(position-block));System.arraycopy(bytes,(int)(position-block),b,off,n);consumed.add(new long[]{position,position+n});position+=n;return n;
        }
        public boolean eof(){return position>=length();}public String getSource(){return session.data;}public void close(){}
    }
    @Test void rejectedIncomingBodyStillConsumesBudget()throws Exception {
        var receipt=new LinkedHashMap<String,Object>();
        try(var session=new BoundedRangeTabix.Session("http://localhost/data","http://localhost/data.tbi",new BoundedRangeTabix.Binding(100,10,"\"data\"","\"index\""),BoundedRangeTabix.Limits.joint(),new LinkedHashMap<>())){
            var subscriber=new BoundedRangeTabix.LimitedBody(2,session,receipt);
            var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});
            subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[3])));
            assertTrue(cancelled.get());assertEquals(3,session.bytes);assertEquals(3L,receipt.get("bodyBytes"));
            assertThrows(CompletionException.class,()->subscriber.getBody().toCompletableFuture().join());assertEquals(0,session.count);
        }
    }
}
