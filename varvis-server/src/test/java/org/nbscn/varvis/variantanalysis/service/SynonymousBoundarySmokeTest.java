package org.nbscn.varvis.variantanalysis.service;

import java.nio.file.Path;
import java.util.List;

/** Additional prespecified last-exon-base control; never replaces the retained failed biological negative. */
class SynonymousBoundarySmokeTest extends SynonymousBusinessSmokeTest {
    SynonymousBoundarySmokeTest() {
        work=Path.of("target/bp7-synonymous-boundary").toAbsolutePath();artifacts=work;
        vcf=vcf.substring(0,vcf.indexOf("chr1\t156138613"))
                +"chr1\t156138757\tClinVar-162412-boundary\tG\tA\t.\tPASS\t.\tGT\t0/1\n";
    }
    @Override List<Integer> positions() { return List.of(156138757); }
    @Override String referenceAllele() { return "G"; }
    @Override int formalCount() { return 1; }
    @Override int evaluatedCount() { return 1; }
    @Override boolean usePhenotype() { return false; }
    @Override String expectedBp7(long position) { return "NOT_EVALUATED"; }
}
