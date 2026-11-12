package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryVariantEnhancementRepository implements VariantEnhancementRepository {

    private final ConcurrentMap<String, VariantEnhancementTask> tasks =
            new ConcurrentHashMap<String, VariantEnhancementTask>();

    @Override
    public void save(VariantEnhancementTask task) {
        tasks.put(task.getId(), task);
    }

    @Override
    public Optional<VariantEnhancementTask> findById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public int failInterruptedTasks() {
        int recovered = 0;
        for (VariantEnhancementTask task : tasks.values()) {
            switch (task.getStatus()) {
                case QUEUED:
                case RUNNING:
                    task.fail("Server restarted before enhancement completed");
                    recovered++;
                    break;
                default:
                    break;
            }
        }
        return recovered;
    }
}
