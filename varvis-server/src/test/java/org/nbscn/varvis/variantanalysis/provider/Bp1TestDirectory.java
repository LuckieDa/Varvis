package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Files;
import java.nio.file.Path;

/** Task-local temporary files, checked before any H2 or micro-resource is created. */
public final class Bp1TestDirectory implements org.junit.jupiter.api.io.TempDirFactory {
    public static Path create(String prefix) throws Exception {
        Path checkout = Path.of("").toAbsolutePath().normalize().getParent();
        if (checkout == null || !Files.isRegularFile(checkout.resolve("AGENTS.md"))) throw new IllegalStateException("CHECKOUT_NOT_FOUND");
        Path allowed = checkout.resolve("local/temp/BP1-missense-20260928").toRealPath();
        Path root = Path.of(System.getProperty("varvis.bp1.temp", allowed.resolve("junit").toString())).toAbsolutePath().normalize();
        if (!root.startsWith(allowed)) throw new IllegalStateException("BP1_TEMP_OUTSIDE_TASK");
        Files.createDirectories(root);
        if (!root.toRealPath().startsWith(allowed)) throw new IllegalStateException("BP1_TEMP_LINK_OUTSIDE_TASK");
        Path result = Files.createTempDirectory(root, prefix + "-").toRealPath();
        if (!result.startsWith(allowed)) throw new IllegalStateException("BP1_ACTUAL_TEMP_OUTSIDE_TASK");
        System.out.println("BP1_ACTUAL_LOCAL_TEMP=" + result);
        return result;
    }
    @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,
            org.junit.jupiter.api.extension.ExtensionContext context) throws Exception { return create("compat"); }
}
