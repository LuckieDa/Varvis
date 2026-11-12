package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizationResult;

import java.io.IOException;
import java.nio.file.Path;

public interface VcfNormalizer {

    NormalizationResult normalize(Path inputFile, Path workspace, GenomeAssembly genomeAssembly) throws IOException;
}
