package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Files;
import java.nio.file.Path;

/** Task-local temporary files, checked before any synthetic artifact is created. */
public final class Ps2TestDirectory implements org.junit.jupiter.api.io.TempDirFactory {
    public static Path create(String prefix) throws Exception {
        Path checkout = Path.of("").toAbsolutePath().normalize().getParent();
        if (checkout == null || !Files.isRegularFile(checkout.resolve("AGENTS.md"))) throw new IllegalStateException("CHECKOUT_NOT_FOUND");
        Path allowed = checkout.resolve("local/temp/PS2-denovo-core-20260928").toRealPath();
        Path root = Path.of(System.getProperty("varvis.ps2.temp", allowed.resolve("junit").toString())).toAbsolutePath().normalize();
        Path business = checkout.resolve("local/temp/PS2-denovo-business-20260928").toAbsolutePath().normalize();
        if (root.startsWith(business)) allowed = business.toRealPath();
        Path businessR1 = checkout.resolve("local/temp/PS2-denovo-business-r1-20260928").toAbsolutePath().normalize();
        if (root.startsWith(businessR1)) allowed = businessR1.toRealPath();
        Path pm6 = checkout.resolve("local/temp/PM6-denovo-core-20260928").toAbsolutePath().normalize();
        if (root.startsWith(pm6)) allowed = pm6.toRealPath();
        Path pm6Business = checkout.resolve("local/temp/PM6-denovo-business-20260929").toAbsolutePath().normalize();
        if (root.startsWith(pm6Business)) allowed = pm6Business.toRealPath();
        Path pm3 = checkout.resolve("local/temp/PM3-in-trans-core-20260929").toAbsolutePath().normalize();
        if (root.startsWith(pm3)) allowed = pm3.toRealPath();
        Path pm3Business = checkout.resolve("local/temp/PM3-in-trans-business-20260929").toAbsolutePath().normalize();
        if (root.startsWith(pm3Business)) allowed = pm3Business.toRealPath();
        Path bp2 = checkout.resolve("local/temp/BP2-phase-core-20260930").toAbsolutePath().normalize();
        if (root.startsWith(bp2)) allowed = bp2.toRealPath();
        Path bp2Business = checkout.resolve("local/temp/BP2-phase-business-20261001").toAbsolutePath().normalize();
        if (root.startsWith(bp2Business)) allowed = bp2Business.toRealPath();
        Path pp1Business = checkout.resolve("local/temp/PP1-segregation-business-20261002").toAbsolutePath().normalize();
        if (root.startsWith(pp1Business)) allowed = pp1Business.toRealPath();
        Path bs4Business = checkout.resolve("local/temp/BS4-nonsegregation-business-20261002").toAbsolutePath().normalize();
        if (root.startsWith(bs4Business)) allowed = bs4Business.toRealPath();
        Path pp4Business = checkout.resolve("local/temp/PP4-phenotype-business-20261003").toAbsolutePath().normalize();
        if (root.startsWith(pp4Business)) allowed = pp4Business.toRealPath();
        if (!root.startsWith(allowed)) throw new IllegalStateException("PS2_TEMP_OUTSIDE_TASK");
        Files.createDirectories(root);
        if (!root.toRealPath().startsWith(allowed)) throw new IllegalStateException("PS2_TEMP_LINK_OUTSIDE_TASK");
        Path result = Files.createTempDirectory(root, prefix + "-").toRealPath();
        if (!result.startsWith(allowed)) throw new IllegalStateException("PS2_ACTUAL_TEMP_OUTSIDE_TASK");
        System.out.println("PS2_ACTUAL_LOCAL_TEMP=" + result);
        return result;
    }
    @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,
            org.junit.jupiter.api.extension.ExtensionContext context) throws Exception { return create("compat"); }
}
