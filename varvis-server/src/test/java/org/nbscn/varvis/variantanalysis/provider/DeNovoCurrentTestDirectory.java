package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Files;
import java.nio.file.Path;

/** Each CURRENT product-chain test owns a short directory within this checkout's local/temp category. */
public final class DeNovoCurrentTestDirectory implements org.junit.jupiter.api.io.TempDirFactory {
    @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,
            org.junit.jupiter.api.extension.ExtensionContext context) throws Exception {
        Path checkout = Path.of("").toAbsolutePath().normalize().getParent();
        if (checkout == null || !Files.isRegularFile(checkout.resolve("AGENTS.md")))
            throw new IllegalStateException("DENOVO_CURRENT_CHECKOUT_REQUIRED");
        Path root = Files.createDirectories(checkout.resolve("local/temp/dc")).toRealPath();
        if (!root.startsWith(checkout.toRealPath())) throw new IllegalStateException("DENOVO_CURRENT_TEMP_OUTSIDE_CHECKOUT");
        Path actual = Files.createTempDirectory(root, "c").toRealPath();
        if (!actual.startsWith(root)) throw new IllegalStateException("DENOVO_CURRENT_TEMP_LINK_OUTSIDE_TASK");
        System.out.println("DENOVO_CURRENT_LOCAL_TEMP=" + actual);
        return actual;
    }
}
