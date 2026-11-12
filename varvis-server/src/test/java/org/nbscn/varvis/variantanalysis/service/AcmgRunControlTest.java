package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AcmgRunControlTest {
    DriverManagerDataSource ds; JdbcTemplate jdbc; AcmgBusinessStore store;
    @BeforeEach void setup() {
        ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE variant_analysis_acmg_run(run_id VARCHAR(64) PRIMARY KEY,analysis_id VARCHAR(64),request_sha256 VARCHAR(64),request_json CLOB,status VARCHAR(32),created_at VARCHAR(40),response_sha256 VARCHAR(64),response_json CLOB,private_json CLOB)");
        store=new AcmgBusinessStore(ds);
        store.claim(new AcmgBusinessStore.Run("r","a","hash","{}","RUNNING","now",null,null,null));
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    @Test void pauseAcknowledgesOnlyAtCheckpointAndResumesSameWorker() throws Exception {
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);var resumed=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->{try(var session=AcmgRunControl.begin(store,"a","r")){entered.countDown();proceed.await();AcmgRunControl.checkpoint();resumed.countDown();}return null;});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertEquals("PAUSE_REQUESTED",AcmgRunControl.command(store,"a","r","PAUSE").path("state").asText());
            proceed.countDown();awaitState("PAUSED");assertEquals(1,resumed.getCount());
            AcmgRunControl.command(store,"a","r","RESUME");future.get(3,TimeUnit.SECONDS);assertEquals(0,resumed.getCount());
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS)); }
    }
    @Test void stopPersistsCancelledAndCannotOverwriteCompleted() throws Exception {
        try(var session=AcmgRunControl.begin(store,"a","r")) {
            AcmgRunControl.command(store,"a","r","STOP");
            assertThrows(AcmgRunControl.Stopped.class,AcmgRunControl::checkpoint);session.stopped();
        }
        assertEquals("CANCELLED",store.metadata("a","r").orElseThrow().status());
        assertThrows(IllegalStateException.class,()->store.finish("r","COMPLETED","{}","{}"));
        assertThrows(IllegalArgumentException.class,()->AcmgRunControl.command(store,"other","r","STOP"));
    }
    @Test void stopWakesPausedWorkerWithoutResumingWork() throws Exception {
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->{try(var session=AcmgRunControl.begin(store,"a","r")) {
                entered.countDown();proceed.await();
                assertThrows(AcmgRunControl.Stopped.class,AcmgRunControl::checkpoint);session.stopped();
            }return null;});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            AcmgRunControl.command(store,"a","r","PAUSE");proceed.countDown();awaitState("PAUSED");
            AcmgRunControl.command(store,"a","r","STOP");future.get(3,TimeUnit.SECONDS);
            assertEquals("CANCELLED",store.metadata("a","r").orElseThrow().status());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void pauseNeverWaitsInsideDatabaseTransactionAndStopRollsItBack() {
        try(var session=AcmgRunControl.begin(store,"a","r")) {
            var tx=new TransactionTemplate(new DataSourceTransactionManager(requireNonNull(ds)));
            tx.executeWithoutResult(s->{AcmgRunControl.command(store,"a","r","PAUSE");AcmgRunControl.checkpoint();});
            assertEquals("PAUSE_REQUESTED",AcmgRunControl.status(store,"a","r").path("state").asText());
            AcmgRunControl.command(store,"a","r","STOP");
            assertThrows(AcmgRunControl.Stopped.class,()->tx.executeWithoutResult(s->{jdbc.update("UPDATE variant_analysis_acmg_run SET created_at='mutated'");AcmgRunControl.checkpoint();}));
            assertEquals("now",store.metadata("a","r").orElseThrow().createdAt());session.stopped();
        }
    }
    @Test void legacyRecordNeedsExplicitAcknowledgementAndDeadOwnerCannotResume() {
        var unknown=AcmgRunControl.status(store,"a","r");
        assertEquals("RECOVERY_REQUIRED",unknown.path("state").asText());
        assertEquals("ACKNOWLEDGE_INTERRUPTED",unknown.at("/availableActions/0/controlAction").asText());
        assertTrue(unknown.at("/availableActions/0/requiresConfirmation").asBoolean());
        assertFalse(unknown.path("activeWorkerAvailable").asBoolean());
        assertFalse(unknown.path("sameWorkerResumable").asBoolean());
        assertEquals("RUNNING",store.metadata("a","r").orElseThrow().status(),"Reading capabilities must not acknowledge or resume work");
        assertThrows(IllegalArgumentException.class,()->AcmgRunControl.command(store,"a","r","RESUME"));
        String host=System.getenv().getOrDefault("COMPUTERNAME","unknown");
        store.saveControl("a","r","{\"schemaVersion\":\"acmg-run-control-v1\",\"host\":\""+host+"\",\"pid\":999999999,\"processStart\":\"old\"}");
        assertEquals("unknown".equals(host)?"RECOVERY_REQUIRED":"INTERRUPTED",AcmgRunControl.status(store,"a","r").path("state").asText());
        AcmgRunControl.command(store,"a","r","ACKNOWLEDGE_INTERRUPTED");
        assertEquals("INTERRUPTED",store.metadata("a","r").orElseThrow().status());
        var acknowledged=AcmgRunControl.command(store,"a","r","ACKNOWLEDGE_INTERRUPTED");
        assertEquals("INTERRUPTED",acknowledged.path("state").asText());
        assertTrue(acknowledged.path("availableActions").isEmpty(),"A terminal interrupted record has no live process to resume");
    }
    @Test void liveCapabilitiesFollowTheSameWorkerAndNeverEnterPersistedOwnerReceipt() throws Exception {
        try(var session=AcmgRunControl.begin(store,"a","r")) {
            var running=AcmgRunControl.status(store,"a","r");
            assertEquals("PAUSE",running.at("/availableActions/0/controlAction").asText());
            assertEquals("STOP",running.at("/availableActions/1/controlAction").asText());
            assertTrue(running.path("activeWorkerAvailable").asBoolean());
            assertFalse(running.path("sameWorkerResumable").asBoolean());
            var paused=AcmgRunControl.command(store,"a","r","PAUSE");
            assertEquals("RESUME",paused.at("/availableActions/0/controlAction").asText());
            assertTrue(paused.path("sameWorkerResumable").asBoolean());
            assertEquals("a",paused.at("/availableActions/0/target/analysisId").asText());
            assertEquals("r",paused.at("/availableActions/0/target/runId").asText());
            assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(store.controlData("a","r")).has("availableActions"));
            var stopping=AcmgRunControl.command(store,"a","r","STOP");
            assertTrue(stopping.path("availableActions").isEmpty());
            assertFalse(stopping.path("sameWorkerResumable").asBoolean());
            session.stopped();
        }
    }
    @Test void completionWinningAcknowledgementKeepsItsScientificResultAndReturnsNoRecoveryAction() {
        var racing=org.mockito.Mockito.spy(store);
        String result="{\"scientificResult\":\"unchanged\"}";
        org.mockito.Mockito.doAnswer(invocation->{
            store.finish("r","COMPLETED",result,"{}");
            store.endControlled("r","INTERRUPTED");
            return null;
        }).when(racing).endControlled("r","INTERRUPTED");
        var finalState=AcmgRunControl.command(racing,"a","r","ACKNOWLEDGE_INTERRUPTED");
        assertEquals("COMPLETED",finalState.path("state").asText());
        assertTrue(finalState.path("availableActions").isEmpty());
        assertFalse(finalState.path("sameWorkerResumable").asBoolean());
        assertEquals(result,store.run("a","r").orElseThrow().responseJson());
    }
    @Test void completedResultWinsLateControlAndProgressReadIsReadOnly() {
        try(var session=AcmgRunControl.begin(store,"a","r")) {store.finish("r","COMPLETED","{}","{}");}
        assertEquals("COMPLETED",AcmgRunControl.status(store,"a","r").path("state").asText());
        assertThrows(IllegalArgumentException.class,()->AcmgRunControl.command(store,"a","r","STOP"));
        assertEquals("{}",store.run("a","r").orElseThrow().responseJson());
    }
    private void awaitState(String wanted) throws Exception {
        for(int i=0;i<100;i++){if(wanted.equals(AcmgRunControl.status(store,"a","r").path("state").asText()))return;Thread.sleep(20);}
        fail("state not reached: "+wanted);
    }
}
