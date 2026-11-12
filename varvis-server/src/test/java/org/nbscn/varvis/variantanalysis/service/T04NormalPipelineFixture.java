package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.DeNovoSelectedBatch;
import org.nbscn.varvis.variantanalysis.provider.LocalEvidenceBatchRuntime;
import org.nbscn.varvis.variantanalysis.provider.Pp1CurrentContributionSource;
import org.nbscn.varvis.variantanalysis.provider.Pp1SelectedBatch;
import org.nbscn.varvis.variantanalysis.provider.Pp4CurrentAdoptionProducer;
import org.nbscn.varvis.variantanalysis.provider.Pp4ServerRegisteredInputs;
import org.nbscn.varvis.variantanalysis.provider.T04NativeOriginalFixture;
import org.nbscn.varvis.variantanalysis.provider.T04Pp1ReplaySource;

/** Actual local CURRENT execution, native H2/session/current-use and actual Python PP1 replay.
 * All original clinical/report/pedigree/parameter input facts are explicit SYNTHETIC engineering data.
 * The local source-capture seam supplies real pinned artifacts; it never supplies a criterion result.
 */
public final class T04NormalPipelineFixture implements AutoCloseable {
    public final ObjectMapper json = new ObjectMapper();
    public final T04CurrentSessionFixture nativeFixture;
    public final ObjectNode context;
    public final Pp4ServerRegisteredInputs registered;
    public final T04Pp1ReplaySource pp1Source;
    public final DeNovoSelectedBatch deNovo;
    public final LocalEvidenceBatchRuntime runtime;
    public final CurrentAcmgEvaluationSession session;
    public final AtomicInteger callbacks = new AtomicInteger();
    private final AnalysisContext analysis;
    private final Path directory;
    private CurrentAdoptionInventory inventory;
    private CurrentClinicalAllocation.CurrentUseSource currentUse;
    private ObjectNode archive;
    private ObjectNode useAudit;
    private ObjectNode consumerAudit;
    private ObjectNode result;

    public T04NormalPipelineFixture(Path directory, Path python, Path scripts, List<String> selectedCriteria,
            String pedigreeMode, boolean deNovoConfigured, String isolatedJdbcUrl) throws Exception {
        this(directory, python, scripts, selectedCriteria, pedigreeMode, deNovoConfigured, isolatedJdbcUrl, ignored -> { }, false);
    }
    public T04NormalPipelineFixture(Path directory, Path python, Path scripts, List<String> selectedCriteria,
            String pedigreeMode, boolean deNovoConfigured, String isolatedJdbcUrl,
            java.util.function.Consumer<T04NativeOriginalFixture> sourceMutation) throws Exception {
        this(directory, python, scripts, selectedCriteria, pedigreeMode, deNovoConfigured, isolatedJdbcUrl, sourceMutation, false);
    }
    public T04NormalPipelineFixture(Path directory, Path python, Path scripts, List<String> selectedCriteria,
            String pedigreeMode, boolean deNovoConfigured, String isolatedJdbcUrl,
            java.util.function.Consumer<T04NativeOriginalFixture> sourceMutation, boolean duplicateSameFacts) throws Exception {
        this(directory, python, scripts, selectedCriteria, pedigreeMode, deNovoConfigured, isolatedJdbcUrl,
                sourceMutation, duplicateSameFacts, ignored -> { });
    }
    public T04NormalPipelineFixture(Path directory, Path python, Path scripts, List<String> selectedCriteria,
            String pedigreeMode, boolean deNovoConfigured, String isolatedJdbcUrl,
            java.util.function.Consumer<T04NativeOriginalFixture> sourceMutation, boolean duplicateSameFacts,
            java.util.function.Consumer<T04CurrentSessionFixture> beforeClaimMutation) throws Exception {
        this(directory,python,scripts,selectedCriteria,pedigreeMode,deNovoConfigured,isolatedJdbcUrl,sourceMutation,duplicateSameFacts,
                beforeClaimMutation,(fixture,context,path)->path);
    }
    @FunctionalInterface public interface RegisteredInputTransform {
        Path apply(T04CurrentSessionFixture fixture,ObjectNode context,Path manifest) throws Exception;
    }
    public T04NormalPipelineFixture(Path directory,Path python,Path scripts,List<String> selectedCriteria,
            String pedigreeMode,boolean deNovoConfigured,String isolatedJdbcUrl,
            java.util.function.Consumer<T04NativeOriginalFixture> sourceMutation,boolean duplicateSameFacts,
            java.util.function.Consumer<T04CurrentSessionFixture> beforeClaimMutation,RegisteredInputTransform transform) throws Exception {
        this.directory = directory;
        nativeFixture = new T04CurrentSessionFixture(directory.resolve("native"), "t04-normal-analysis", "t04-normal-run",
                selectedCriteria, true, isolatedJdbcUrl);
        try {
            beforeClaimMutation.accept(nativeFixture);
            context = T04NativeOriginalFixture.context(nativeFixture);
            var sources = new T04NativeOriginalFixture(nativeFixture, context); sources.nativeSampleLink(nativeFixture, context); sourceMutation.accept(sources);
            registered = Pp4ServerRegisteredInputs.load(transform.apply(nativeFixture,context,sources.writeServerBundle(directory.resolve("registered"), context)));
            ObjectNode preparedBinding = T04NativeOriginalFixture.analysisBinding(context, nativeFixture, selectedCriteria);
            pp1Source = T04Pp1ReplaySource.generate(directory.resolve("pp1-project"), python, scripts, preparedBinding, pedigreeMode);
            deNovo = deNovoConfigured ? T04NativeOriginalFixture.inheritedDeNovo(directory.resolve("de-novo-source"), context) : null;
            var factory = new Pp1CurrentContributionSource.Factory(new Pp4CurrentAdoptionProducer(registered));
            var pp1 = Pp1SelectedBatch.withCurrentFactory(json, pp1Source, factory);
            runtime = new LocalEvidenceBatchRuntime(json, json.createObjectNode().put("dataClass", "SYNTHETIC").put("fixture", "T04_ACTUAL_NATIVE_PIPELINE"),
                    null, null, null, null, null, null, null, null, null, null, null, true, deNovo, null, pp1);
            runtime.bindExecutionCriteria(selectedCriteria);
            runtime.preparePp1Sources(nativeFixture.analysisId(), json.createObjectNode());
            session = duplicateSameFacts ? nativeFixture.issue(List.of(T04CurrentSessionFixture.AttemptSpec.ready("hypothesis", context),
                    T04CurrentSessionFixture.AttemptSpec.ready("duplicate-hypothesis", context)), runtime.identity())
                    : nativeFixture.issue(context, runtime.identity());
            assertEquals(preparedBinding, session.attempts().get(0).path("binding"), "pre-claim source scope and actual native session must agree");
            analysis = new AnalysisContext(nativeFixture.analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES);
        } catch (Exception | AssertionError failure) { nativeFixture.close(); throw failure; }
    }

    public ObjectNode execute() throws Exception {
        var actual = runtime.execute(nativeFixture.generalPrepared(), analysis, nativeFixture.executionSourceSha256(), nativeFixture.prepared(), session, token -> {
            callbacks.incrementAndGet(); consumerAudit = token.snapshot();
            try {
                var current = CurrentClinicalAllocation.prepareAfterClaim(json, nativeFixture.store(), session, nativeFixture.prepared(),
                        nativeFixture.recovered(), nativeFixture.ranked(), registered, token);
                currentUse=current;
                var factory = runtime.currentPp1Factory().orElseThrow(); factory.bindCurrentUseSource(current);
                inventory = CurrentAdoptionInventory.capture(session, factory.producer());
                archive = CurrentClinicalArchive.capture(json, session, registered, inventory);
                useAudit = current.snapshot(); runtime.bindCurrentPp1(session, inventory);
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("T04_NATIVE_PIPELINE_FIXTURE_FAILURE", failure); }
        });
        result = actual.deepCopy();
        Path debug = debugDirectory(); java.nio.file.Files.createDirectories(debug);
        java.nio.file.Files.write(debug.resolve("consumer-audit.json"), json.writeValueAsBytes(consumerAudit));
        java.nio.file.Files.write(debug.resolve("runtime-result.json"), json.writeValueAsBytes(result));
        java.nio.file.Files.write(debug.resolve("inventory.json"), json.writeValueAsBytes(inventory.snapshot()));
        return result.deepCopy();
    }
    public Path debugDirectory() { return directory.resolve("debug"); }
    public ObjectNode inventoryAudit() { assertNotNull(inventory); return inventory.snapshot(); }
    public java.util.Map<String, byte[]> coreArtifacts() { assertNotNull(inventory); return inventory.coreArtifacts(); }
    public java.util.Map<String, ObjectNode> materialOriginals() { assertNotNull(inventory); return inventory.materialOriginals(); }
    public ObjectNode archive() { assertNotNull(archive); return archive.deepCopy(); }
    public CurrentClinicalAllocation.CurrentUseSource currentUseSource() { if(currentUse==null) throw new IllegalStateException("Actual current source not prepared");return currentUse; }
    public ObjectNode useAudit() { assertNotNull(useAudit); return useAudit.deepCopy(); }
    public ObjectNode consumerAudit() { assertNotNull(consumerAudit); return consumerAudit.deepCopy(); }
    public ObjectNode result() { assertNotNull(result); return result.deepCopy(); }
    public void save() throws Exception {
        assertNotNull(result); var privateData = json.createObjectNode();
        privateData.set("currentClinicalArchive", archive()); privateData.set("currentInventory", inventoryAudit());
        privateData.set("currentUseAudit", useAudit()); privateData.set("currentConsumerAudit", consumerAudit());
        privateData.set("localBatch", result());
        String privateJson = json.writeValueAsString(privateData); var publicResult = result.deepCopy();
        publicResult.put("privateArtifactSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(privateJson));
        nativeFixture.store().finish(nativeFixture.runId(), "COMPLETED", json.writeValueAsString(publicResult), privateJson);
    }
    @Override public void close() { nativeFixture.close(); }
}
