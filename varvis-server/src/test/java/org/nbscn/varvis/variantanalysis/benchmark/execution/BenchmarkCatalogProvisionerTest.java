package org.nbscn.varvis.variantanalysis.benchmark.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkCatalogProvisionerTest {

    @Test
    void replacesOnlyTheDatabaseCatalogInSupportedMysqlUrl() {
        assertEquals("jdbc:mysql://localhost:3306/varvis_benchmark_pilot_0_1_27?useSSL=false",
                BenchmarkCatalogProvisioner.replaceCatalog(
                        "jdbc:mysql://localhost:3306/varvis?useSSL=false",
                        BenchmarkCatalogProvisioner.CATALOG_ID));
    }

    @Test
    void rejectsUnsupportedOrUnsafeCatalogInput() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCatalogProvisioner.replaceCatalog(
                "jdbc:postgresql://localhost/database", BenchmarkCatalogProvisioner.CATALOG_ID));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCatalogProvisioner.provision(
                null, "not_the_authorized_catalog"));
    }
}
