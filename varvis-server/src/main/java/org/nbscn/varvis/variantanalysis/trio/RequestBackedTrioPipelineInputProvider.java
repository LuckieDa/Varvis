package org.nbscn.varvis.variantanalysis.trio;

import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Primary
@Service
public class RequestBackedTrioPipelineInputProvider implements TrioPipelineInputProvider {

    private final ConcurrentMap<String, TrioPipelineInput> inputs =
            new ConcurrentHashMap<String, TrioPipelineInput>();

    public void register(String jobId, TrioPipelineInput input) {
        if (!StringUtils.hasText(jobId) || input == null || !input.isPresent()) {
            return;
        }
        inputs.put(jobId, input);
    }

    public void remove(String jobId) {
        if (StringUtils.hasText(jobId)) {
            inputs.remove(jobId);
        }
    }

    @Override
    public TrioPipelineInput inputFor(VariantAnalysisJob job) {
        if (job == null || !StringUtils.hasText(job.getId())) {
            return TrioPipelineInput.absent();
        }
        TrioPipelineInput input = inputs.get(job.getId());
        return input == null ? TrioPipelineInput.absent() : input;
    }

    @Override
    public void release(VariantAnalysisJob job) {
        if (job != null && StringUtils.hasText(job.getId())) {
            inputs.remove(job.getId());
        }
    }
}
