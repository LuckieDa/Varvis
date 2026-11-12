package org.nbscn.varvis.variantanalysis.domain;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.exception.InvalidVcfUploadException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenomeAssemblyTest {

    @Test
    void acceptsGrch38AndGrch37InputAliases() {
        assertEquals(GenomeAssembly.GRCh38, GenomeAssembly.require("GRCh38"));
        assertEquals(GenomeAssembly.GRCh38, GenomeAssembly.require("grch38"));
        assertEquals(GenomeAssembly.GRCh38, GenomeAssembly.require("hg38"));
        assertEquals(GenomeAssembly.GRCh37, GenomeAssembly.require("GRCh37"));
        assertEquals(GenomeAssembly.GRCh37, GenomeAssembly.require("hg19"));
        assertEquals(GenomeAssembly.GRCh37, GenomeAssembly.require("b37"));
        assertThrows(InvalidVcfUploadException.class, () -> GenomeAssembly.require(null));
        assertThrows(InvalidVcfUploadException.class, () -> GenomeAssembly.require("T2T"));
    }
}
