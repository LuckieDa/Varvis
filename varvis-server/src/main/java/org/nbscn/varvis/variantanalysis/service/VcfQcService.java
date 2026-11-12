package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VcfQcResult;

import java.nio.file.Path;

public interface VcfQcService {

    VcfQcResult extract(Path normalizedVcf, GenomeAssembly genomeAssembly, String requestedSampleId);
}
