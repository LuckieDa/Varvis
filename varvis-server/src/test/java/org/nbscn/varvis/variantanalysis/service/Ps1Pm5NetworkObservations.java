package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.engine.ExomiserProcessRunner;
import org.nbscn.varvis.variantanalysis.engine.ConfiguredExomiserEngineAdapter;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.NonNull;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Observations only: every counted runner/provider delegates to its unmodified real implementation. */
public final class Ps1Pm5NetworkObservations implements BeanPostProcessor {
    private final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Path output;
    private final boolean jointMode;
    private final boolean forbidModelLaunch;
    private final boolean allowReferenceModels;
    private final List<Pm2SupportingRuntime> populationRuntimes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Hnf1aPvs1Runtime> pvs1Runtimes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Hnf1aPm1Runtime> pm1Runtimes = new java.util.concurrent.CopyOnWriteArrayList<>();
    public Ps1Pm5NetworkObservations(Path output) { this(output, false); }
    public Ps1Pm5NetworkObservations(Path output, boolean jointMode) {
        this(output, jointMode, false);
    }
    public Ps1Pm5NetworkObservations(Path output, boolean jointMode, boolean forbidModelLaunch) {
        this(output, jointMode, forbidModelLaunch, false);
    }
    public Ps1Pm5NetworkObservations(Path output, boolean jointMode, boolean forbidModelLaunch, boolean allowReferenceModels) {
        this.output = output; this.jointMode = jointMode;
        this.forbidModelLaunch = forbidModelLaunch;
        this.allowReferenceModels = allowReferenceModels;
        for (String key : List.of("exomiser", "vepPublication", "vepComparator", "clinvarDiscovery", "clinvarSidecarLookup",
                "missenseRuntime", "spliceRawBatch", "spliceQuery", "provider", "evaluator", "ba1Provider", "ba1Evaluator",
                "predictionProvider", "predictionEvaluator", "missenseProvider", "missenseEvaluator", "coordinatorFactory", "amIdentityLookup",
                "publicationClinvarBatch", "publicationClinvarTargets", "blockedModelLaunch"))
            counters.put(key, new AtomicInteger());
        if (jointMode) for (String key : List.of("pm2Provider", "pm2Evaluator", "pm2QueryAttempts", "pm2AllSitesBatch",
                "pvs1Provider", "pvs1Evaluator", "pvs1StoredVepProjection", "predictionSpliceRawBatch", "predictionSpliceQuery",
                "revelProvider", "revelEvaluator",
                "hnf1aFrequencyRuntime", "hnf1aFrequencyProvider", "hnf1aFrequencyEvaluator",
                "functionalRuntime", "functionalProvider", "functionalEvaluator", "pm1Provider", "pm1Evaluator", "pm1StoredVepProjection"))
            counters.put(key, new AtomicInteger());
    }
    @Override public @NonNull Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String name) {
        if (bean instanceof ConfiguredExomiserEngineAdapter) {
            var runner = (ExomiserProcessRunner) requiredField(bean, "processRunner");
            ReflectionTestUtils.setField(bean, "processRunner", (ExomiserProcessRunner) (command, directory, stdout, stderr, environment, timeout) -> {
                requireModelAuthorization(); add("exomiser", 1); return runner.run(command, directory, stdout, stderr, environment, timeout);
            });
        }
        if (bean instanceof VepTranscriptAnnotationEnrichmentService) {
            var runner = (ExternalProcessRunner) requiredField(bean, "processRunner");
            ReflectionTestUtils.setField(bean, "processRunner", countedVep("vepPublication", runner));
        }
        if (bean instanceof ClinVarEvidenceProvider provider) {
            var counted = spy(provider);
            doAnswer(call -> {
                int batch = Objects.requireNonNull(counters.get("publicationClinvarBatch")).incrementAndGet();
                List<VariantInterpretation> targets = call.getArgument(0);
                add("publicationClinvarTargets", Objects.requireNonNull(targets).size());
                // Counts the public provider call, not individual Tabix I/O or model inference.
                Object result = call.callRealMethod();
                save("publication-clinvar-batch-" + batch + ".json", mapper.valueToTree(result));
                return result;
            }).when(counted).annotate(anyList(), any());
            return Objects.requireNonNull(counted);
        }
        if (bean instanceof AcmgBusinessService service) observeBusiness(service);
        return bean;
    }
    public Map<String, Integer> snapshot() {
        Map<String, Integer> result = new TreeMap<>(); counters.forEach((name, count) -> result.put(name, count.get()));
        if (jointMode) {
            int pp = populationRuntimes.stream().mapToInt(r -> Objects.requireNonNull(r).providerCalls()).sum();
            int pe = populationRuntimes.stream().mapToInt(r -> Objects.requireNonNull(r).evaluatorCalls()).sum();
            int pv = pvs1Runtimes.stream().mapToInt(r -> Objects.requireNonNull(r).evaluatorCalls()).sum();
            int pm1 = pm1Runtimes.stream().mapToInt(r -> Objects.requireNonNull(r).evaluatorCalls()).sum();
            result.put("pm1Evaluator", pm1);
            result.put("pm1StoredVepProjection", pm1Runtimes.stream().mapToInt(r -> Objects.requireNonNull(r).storedVepProjections()).sum());
            result.put("pm2Provider", pp); result.put("pm2Evaluator", pe);
            result.put("pm2AllSitesBatch", populationRuntimes.stream().mapToInt(r -> Objects.requireNonNull(r).allSitesBatches()).sum());
            result.put("pm2QueryAttempts", populationRuntimes.stream().mapToInt(r -> Objects.requireNonNull(r).queryDiagnostics().size()).sum());
            result.put("pvs1Evaluator", pv);
            result.put("pvs1StoredVepProjection", pvs1Runtimes.stream().mapToInt(r -> Objects.requireNonNull(r).storedVepProjections()).sum());
            result.compute("provider", (key, count) -> Objects.requireNonNull(count) + pp);
            result.compute("evaluator", (key, count) -> Objects.requireNonNull(count) + pe + pv + pm1);
            try { save("population-query-diagnostics-" + java.util.UUID.randomUUID() + ".json",
                    mapper.valueToTree(populationRuntimes.stream().map(r -> Objects.requireNonNull(r).queryDiagnostics()).toList())); }
            catch (IOException failed) { throw new java.io.UncheckedIOException(failed); }
        }
        return result;
    }
    private void add(String key, int count) { counters.computeIfAbsent(key, ignored -> new AtomicInteger()).addAndGet(count); }
    private void requireModelAuthorization() {
        if (forbidModelLaunch) {
            add("blockedModelLaunch", 1);
            throw new IllegalStateException("Published-artifact reuse does not authorize new model execution");
        }
    }
    private ExternalProcessRunner countedVep(String name, ExternalProcessRunner runner) {
        return (command, directory, timeout) -> {
            if (!"vepComparator".equals(name) || !allowReferenceModels) requireModelAuthorization();
            add(name, 1); return runner.run(command, directory, timeout);
        };
    }
    private static @NonNull Object requiredField(@NonNull Object target, @NonNull String name) {
        return Objects.requireNonNull(ReflectionTestUtils.getField(target, name), name);
    }
    private void save(String name, JsonNode value) throws IOException {
        Files.write(output.resolve(name), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }
    @SuppressWarnings("unchecked")
    private void observeBusiness(@NonNull AcmgBusinessService service) {
        Object recommendations = requiredField(service, "recommendations");
        var identityLookup = (Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup>) requiredField(recommendations, "lookup");
        ReflectionTestUtils.setField(recommendations, "lookup", (Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup>) candidate -> {
            add("amIdentityLookup", 1); return identityLookup.apply(candidate);
        });
        Supplier<ExplicitCaseInputCoordinator> factory = (Supplier<ExplicitCaseInputCoordinator>) requiredField(service, "runtime");
        ReflectionTestUtils.setField(service, "runtime", (Supplier<ExplicitCaseInputCoordinator>) () -> {
            add("coordinatorFactory", 1); var coordinator = spy(Objects.requireNonNull(factory.get()));
            if (jointMode) observePredictionSplice(Objects.requireNonNull(coordinator));
            doAnswer(call -> {
                var result = (ExplicitCaseInputCoordinator.Result) call.callRealMethod();
                var dispatch = Objects.requireNonNull(result).adapterResult().dispatch();
                if (jointMode) for (var row : dispatch.candidates()) for (var attempt : row.attempts()) {
                    var input = attempt.transcriptInput();
                    if (input != null && "HNF1A_REVEL".equals(input.path("predictionPath").asText())) {
                        add("revelProvider", attempt.providerCalls()); add("revelEvaluator", attempt.evaluatorCalls());
                    }
                }
                add("predictionProvider", dispatch.providerCalls()); add("predictionEvaluator", dispatch.evaluatorCalls());
                add("provider", dispatch.providerCalls()); add("evaluator", dispatch.evaluatorCalls()); return result;
            }).when(coordinator).dispatch(any(), any(), any(), any(), anyList(), anyInt(), anyList(), anyMap());
            return coordinator;
        });
        Supplier<AcmgBusinessService.Ba1Runtime> ba1 = (Supplier<AcmgBusinessService.Ba1Runtime>) requiredField(service, "ba1Runtime");
        ReflectionTestUtils.setField(service, "ba1Runtime", (Supplier<AcmgBusinessService.Ba1Runtime>) () -> {
            var runtime = Objects.requireNonNull(ba1.get()); var evaluator = spy(runtime.evaluator());
            doAnswer(call -> {
                ObjectNode result = (ObjectNode) call.callRealMethod();
                int p = Objects.requireNonNull(result).path("providerCalls").asInt(), e = result.path("evaluatorCalls").asInt();
                add("ba1Provider", p); add("ba1Evaluator", e); add("provider", p); add("evaluator", e); return result;
            }).when(evaluator).evaluate(anyString(), any(), any(), nullable(JsonNode.class), nullable(String.class));
            return new AcmgBusinessService.Ba1Runtime(runtime.producer(), evaluator);
        });
        if (jointMode) {
            Supplier<org.nbscn.varvis.variantanalysis.provider.Hnf1aFunctionalRuntime> functional =
                    (Supplier<org.nbscn.varvis.variantanalysis.provider.Hnf1aFunctionalRuntime>) requiredField(service, "functionalRuntime");
            ReflectionTestUtils.setField(service, "functionalRuntime", (Supplier<org.nbscn.varvis.variantanalysis.provider.Hnf1aFunctionalRuntime>) () -> {
                var actual = spy(Objects.requireNonNull(functional.get()));
                doAnswer(call -> {
                    add("functionalRuntime", 1);
                    ObjectNode result = (ObjectNode) call.callRealMethod();
                    int p = Objects.requireNonNull(result).path("providerCalls").asInt(), e = result.path("evaluatorCalls").asInt();
                    add("functionalProvider", p); add("functionalEvaluator", e);
                    add("provider", p); add("evaluator", e); return result;
                }).when(actual).evaluate(any(), any());
                return actual;
            });
            Supplier<Hnf1aPopulationRuntime> frequency = (Supplier<Hnf1aPopulationRuntime>) requiredField(service, "hnf1aFrequencyRuntime");
            ReflectionTestUtils.setField(service, "hnf1aFrequencyRuntime", (Supplier<Hnf1aPopulationRuntime>) () -> {
                var actual = spy(Objects.requireNonNull(frequency.get()));
                doAnswer(call -> {
                    add("hnf1aFrequencyRuntime", 1);
                    ObjectNode result = (ObjectNode) call.callRealMethod();
                    int p = Objects.requireNonNull(result).path("providerCalls").asInt(), e = result.path("evaluatorCalls").asInt();
                    add("hnf1aFrequencyProvider", p); add("hnf1aFrequencyEvaluator", e);
                    add("provider", p); add("evaluator", e); return result;
                }).when(actual).evaluate(any(), any(), anyString());
                return actual;
            });
            Supplier<Hnf1aPm1Runtime> pm1 = (Supplier<Hnf1aPm1Runtime>) requiredField(service, "pm1Runtime");
            ReflectionTestUtils.setField(service, "pm1Runtime", (Supplier<Hnf1aPm1Runtime>) () -> {
                var actual = Objects.requireNonNull(pm1.get());
                if (!pm1Runtimes.contains(actual)) pm1Runtimes.add(actual);
                return actual;
            });
            Supplier<Pm2SupportingRuntime> population = (Supplier<Pm2SupportingRuntime>) requiredField(service, "pm2Runtime");
            ReflectionTestUtils.setField(service, "pm2Runtime", (Supplier<Pm2SupportingRuntime>) () -> {
                var actual = Objects.requireNonNull(population.get());
                if (!populationRuntimes.contains(actual)) populationRuntimes.add(actual);
                return actual;
            });
            Supplier<Hnf1aPvs1Runtime> pvs1 = (Supplier<Hnf1aPvs1Runtime>) requiredField(service, "pvs1Runtime");
            ReflectionTestUtils.setField(service, "pvs1Runtime", (Supplier<Hnf1aPvs1Runtime>) () -> {
                var actual = Objects.requireNonNull(pvs1.get());
                if (!pvs1Runtimes.contains(actual)) pvs1Runtimes.add(actual);
                return actual;
            });
        }
        Supplier<ClinVarMissenseBusinessRuntime> missense = (Supplier<ClinVarMissenseBusinessRuntime>) requiredField(service, "missenseRuntime");
        ReflectionTestUtils.setField(service, "missenseRuntime", (Supplier<ClinVarMissenseBusinessRuntime>) () -> {
            var runtime = Objects.requireNonNull(missense.get());
            Object comparator = requiredField(runtime, "comparator");
            var clinvar = spy((ClinVarTabixProvider) requiredField(comparator, "clinVarProvider"));
            doAnswer(call -> { add("clinvarDiscovery", 1); return call.callRealMethod(); })
                    .when(clinvar).discoverBoundedComparators(any(), anyInt(), anyInt());
            ReflectionTestUtils.setField(comparator, "clinVarProvider", clinvar);
            var sidecar = spy((ClinVarComparatorSidecar) requiredField(comparator, "sidecar"));
            doAnswer(call -> { add("clinvarSidecarLookup", 1); return call.callRealMethod(); })
                    .when(sidecar).lookup(any(), anyString());
            ReflectionTestUtils.setField(comparator, "sidecar", sidecar);
            Object vep = requiredField(comparator, "vepProvider");
            ReflectionTestUtils.setField(vep, "processRunner", countedVep("vepComparator", (ExternalProcessRunner) requiredField(vep, "processRunner")));
            var actual = (Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult>) requiredField(runtime, "splice");
            ReflectionTestUtils.setField(runtime, "splice", (Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult>) queries -> {
                if (!allowReferenceModels) requireModelAuthorization();
                add("spliceRawBatch", 1); add("spliceQuery", queries.size()); var result = actual.apply(queries);
                try {
                    save("raw-batch-" + snapshot().get("spliceRawBatch") + "-queries.json", mapper.valueToTree(queries));
                    if (result != null && result.getResult() != null) save("raw-batch-" + snapshot().get("spliceRawBatch") + "-response.json", result.getResult());
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                return result;
            });
            ClinVarMissenseBusinessRuntime counted = spy(runtime);
            doAnswer(call -> {
                add("missenseRuntime", 1); ObjectNode result = (ObjectNode) call.callRealMethod();
                int p = Objects.requireNonNull(result).path("providerCalls").asInt(), e = result.path("evaluatorCalls").asInt();
                add("missenseProvider", p); add("missenseEvaluator", e); add("provider", p); add("evaluator", e);
                save("missense-runtime-" + snapshot().get("missenseRuntime") + ".json", result); return result;
            }).when(counted).evaluate(any(), any(), any());
            return counted;
        });
    }

    @SuppressWarnings("unchecked")
    private void observePredictionSplice(@NonNull ExplicitCaseInputCoordinator coordinator) {
        Object dispatcher = requiredField(coordinator, "dispatcher");
        Object configured = ReflectionTestUtils.getField(dispatcher, "spliceRuntime");
        if (configured == null) return;
        Supplier<SpliceAiBusinessRuntime> factory = (Supplier<SpliceAiBusinessRuntime>) configured;
        ReflectionTestUtils.setField(dispatcher, "spliceRuntime", (Supplier<SpliceAiBusinessRuntime>) () -> {
            var runtime = Objects.requireNonNull(factory.get());
            var actual = (Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult>) requiredField(runtime, "run");
            ReflectionTestUtils.setField(runtime, "run", (Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult>) queries -> {
                requireModelAuthorization();
                add("predictionSpliceRawBatch", 1); add("predictionSpliceQuery", queries.size());
                add("spliceRawBatch", 1); add("spliceQuery", queries.size());
                String id = java.util.UUID.randomUUID().toString();
                try {
                    save("prediction-raw-" + id + "-queries.json", mapper.valueToTree(queries));
                    var result = actual.apply(queries);
                    if (result != null && result.getResult() != null) save("prediction-raw-" + id + "-response.json", result.getResult());
                    return result;
                } catch (IOException failed) { throw new java.io.UncheckedIOException(failed); }
            });
            return runtime;
        });
    }

}
