package org.nbscn.varvis.variantanalysis.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemExomiserProcessRunnerTest {

    @TempDir
    Path temporary;

    @Test
    void timeoutTerminatesParentAndDescendantBeforeReturning() throws Exception {
        Path parentPidFile = temporary.resolve("timeout-parent.pid");
        Path childPidFile = temporary.resolve("timeout-child.pid");
        ExomiserProcessResult result = new SystemExomiserProcessRunner().run(
                javaCommand(ParentWithChildProcess.class,
                        parentPidFile.toString(), childPidFile.toString()),
                temporary,
                temporary.resolve("timeout.stdout.log"),
                temporary.resolve("timeout.stderr.log"),
                Collections.emptyMap(),
                Duration.ofSeconds(30));

        long parentPid = waitForPid(parentPidFile);
        long childPid = waitForPid(childPidFile);
        assertTrue(result.isTimedOut());
        assertFalse(isAlive(parentPid));
        assertFalse(isAlive(childPid));
    }

    @Test
    void interruptionTerminatesParentAndDescendantAndRestoresInterruptFlag() throws Exception {
        Path parentPidFile = temporary.resolve("parent.pid");
        Path childPidFile = temporary.resolve("child.pid");
        Path stdout = temporary.resolve("interrupt.stdout.log");
        Path stderr = temporary.resolve("interrupt.stderr.log");
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread runner = new Thread(() -> {
            try {
                new SystemExomiserProcessRunner().run(
                        javaCommand(ParentWithChildProcess.class,
                                parentPidFile.toString(), childPidFile.toString()),
                        temporary,
                        stdout,
                        stderr,
                        Collections.emptyMap(),
                        Duration.ofMinutes(2));
            } catch (Throwable error) {
                failure.set(error);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "synthetic-exomiser-runner");
        runner.start();
        long parentPid = waitForPid(parentPidFile);
        long childPid = waitForPid(childPidFile);

        runner.interrupt();
        runner.join(Duration.ofSeconds(15).toMillis());

        assertFalse(runner.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);
        assertTrue(interruptRestored.get());
        assertFalse(isAlive(parentPid));
        assertFalse(isAlive(childPid));
        Files.delete(stdout);
        Files.delete(stderr);
    }

    private static java.util.List<String> javaCommand(Class<?> mainClass, String... arguments) {
        java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass.getName());
        command.addAll(Arrays.asList(arguments));
        return command;
    }

    private static long waitForPid(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) {
                String value = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (value.matches("\\d+")) {
                    return Long.parseLong(value);
                }
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("synthetic process did not publish its PID");
    }

    private static boolean isAlive(long pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        return handle.isPresent() && handle.get().isAlive();
    }

    public static final class SleepingProcess {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8);
            Thread.sleep(Duration.ofMinutes(2).toMillis());
        }
    }

    public static final class ParentWithChildProcess {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8);
            Process child = new ProcessBuilder(javaCommand(SleepingProcess.class, args[1])).start();
            try {
                Thread.sleep(Duration.ofMinutes(2).toMillis());
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
        }
    }
}
