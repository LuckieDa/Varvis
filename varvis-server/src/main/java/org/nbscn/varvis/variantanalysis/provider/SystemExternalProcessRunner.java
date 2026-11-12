package org.nbscn.varvis.variantanalysis.provider;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class SystemExternalProcessRunner implements ExternalProcessRunner {

    private static final int MAX_CAPTURED_OUTPUT_CHARS = 20000;

    @Override
    public ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StreamCollector outputCollector = new StreamCollector(process);
        Thread outputThread = new Thread(outputCollector, "varvis-process-output-reader");
        outputThread.setDaemon(true);
        outputThread.start();
        long timeoutMillis = timeout == null ? Duration.ofMinutes(10).toMillis() : timeout.toMillis();
        boolean completed;
        try {
            completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("VEP process interrupted", ex);
        }
        if (!completed) {
            process.destroyForcibly();
            joinOutputThread(outputThread);
            return new ProcessResult(-1, outputCollector.output(), true);
        }
        joinOutputThread(outputThread);
        if (outputCollector.exception != null) {
            throw outputCollector.exception;
        }
        return new ProcessResult(process.exitValue(), outputCollector.output(), false);
    }

    private void joinOutputThread(Thread outputThread) throws IOException {
        try {
            outputThread.join(1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Process output reader interrupted", ex);
        }
    }

    private static class StreamCollector implements Runnable {

        private final Process process;
        private final StringBuilder builder = new StringBuilder();
        private IOException exception;
        private boolean truncated;

        private StreamCollector(Process process) {
            this.process = process;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    append(line);
                }
            } catch (IOException ex) {
                exception = ex;
            }
        }

        private void append(String line) {
            if (builder.length() >= MAX_CAPTURED_OUTPUT_CHARS) {
                truncated = true;
                return;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            int remaining = MAX_CAPTURED_OUTPUT_CHARS - builder.length();
            if (line.length() > remaining) {
                builder.append(line, 0, Math.max(0, remaining));
                truncated = true;
            } else {
                builder.append(line);
            }
        }

        private String output() {
            if (!truncated) {
                return builder.toString();
            }
            return builder.toString() + "\n[process output truncated]";
        }
    }
}
