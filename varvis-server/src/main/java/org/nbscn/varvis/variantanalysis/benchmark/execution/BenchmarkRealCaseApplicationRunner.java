package org.nbscn.varvis.variantanalysis.benchmark.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Paths;
import java.time.Clock;

/** Explicit local CLI bridge; absent its opt-in property, normal Varvis startup is unchanged. */
@Component
@ConditionalOnProperty(prefix = "varvis.benchmark.real-case", name = "enabled", havingValue = "true")
public class BenchmarkRealCaseApplicationRunner {

    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private final BenchmarkRealCaseExecutor executor;

    public BenchmarkRealCaseApplicationRunner(Environment environment,
                                              ConfigurableApplicationContext context,
                                              ObjectMapper objectMapper,
                                              VariantAnalysisService analysisService,
                                              VariantAnalysisJobRepository repository,
                                              DataSource dataSource) {
        this.environment = environment;
        this.context = context;
        this.executor = new BenchmarkRealCaseExecutor(
                objectMapper,
                analysisService,
                repository,
                dataSource,
                Clock.systemUTC());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {
        String requestFile = environment.getProperty("varvis.benchmark.real-case.request-file");
        if (requestFile == null || requestFile.trim().isEmpty()) {
            throw new IllegalArgumentException("BENCHMARK_REQUEST_FILE_REQUIRED");
        }
        try {
            executor.execute(Paths.get(requestFile.trim()));
        } finally {
            closeAfterReadyEventReturns();
        }
    }

    private void closeAfterReadyEventReturns() {
        Thread shutdown = new Thread(() -> {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                context.close();
            }
        }, "benchmark-context-shutdown");
        shutdown.setDaemon(false);
        shutdown.start();
    }
}
