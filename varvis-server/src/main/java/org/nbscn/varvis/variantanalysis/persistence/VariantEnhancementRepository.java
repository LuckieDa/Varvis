package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;

import java.util.Optional;

public interface VariantEnhancementRepository {

    void save(VariantEnhancementTask task);

    Optional<VariantEnhancementTask> findById(String id);

    int failInterruptedTasks();
}
