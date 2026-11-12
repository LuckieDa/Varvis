package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.*;
import java.util.*;

/** Standalone JDK-only helper. No remote resources, discovery, service or PID-based termination. */
public final class WindowsOwnedProcessFixture {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); String role = args[1];
        if ("echo".equals(role)) {
            for (int i = 2; i < args.length; i++) System.out.println(Base64.getEncoder().encodeToString(args[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            System.out.println(Base64.getEncoder().encodeToString(Path.of("").toAbsolutePath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            System.err.println(System.getenv("VARVIS_OWNED_TEST")); return;
        }
        int depth = Integer.parseInt(args[2]);
        Files.writeString(root.resolve(role + ".pid"), Long.toString(ProcessHandle.current().pid()));
        if (depth > 0) {
            if ("late".equals(role)) while (!Files.exists(root.resolve("spawn"))) Thread.sleep(10);
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"), WindowsOwnedProcessFixture.class.getName(),
                    root.toString(), role + "-child", Integer.toString(depth - 1)).inheritIO().start();
        }
        if ("exit-root".equals(role)) return;
        Thread.sleep(60000);
    }
}
