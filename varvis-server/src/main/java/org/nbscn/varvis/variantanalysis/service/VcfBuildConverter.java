package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;

import java.io.IOException;
import java.nio.file.Path;

public interface VcfBuildConverter {

    BuildConversionResult convert(Path inputFile,
                                  Path workspace,
                                  GenomeAssembly inputAssembly,
                                  GenomeAssembly analysisAssembly) throws IOException;
}
