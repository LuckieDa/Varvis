package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessRuleScope;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.server.ResponseStatusException;

/** Selects an already configured service; never accepts runtime configuration from a request. */
public final class AcmgBusinessWorkflows {
    public static final String CURRENT = "CURRENT";
    private volatile AcmgBusinessService current;
    @Nullable private final AcmgBusinessService local;

    public AcmgBusinessWorkflows(AcmgBusinessService current, @Nullable AcmgBusinessService local) {
        this.current = Objects.requireNonNull(current);
        this.local = local;
    }

    public AcmgBusinessService current() { return current; }
    public synchronized void refreshCurrentPublicSources() {current=current.withRefreshedLocalSources();}

    public AcmgBusinessService select(String workflow) {
        if (CURRENT.equals(workflow)) return current;
        if (!AcmgBusinessRuleScope.LOCAL_BATCH1.equals(workflow))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ACMG_WORKFLOW_UNKNOWN");
        var selected = local;
        if (selected == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "ACMG_WORKFLOW_NOT_ENABLED");
        return selected;
    }

    public ObjectNode inputOptions(String analysisId, int topN, String workflow) {
        var result = select(workflow).inputOptions(analysisId, topN);
        result.put("selectedWorkflow", workflow);
        var choices = result.putArray("availableWorkflows");
        choices.addObject().put("id", CURRENT).put("label", current.configuredCriteria().size() == 11
                ? "证据分析（BA1 / BS1 / PM2 / PP3 / BP4 / PS1 / PM5 / BP7 / PVS1 / PM4 / BP3）" : current.configuredCriteria().size() == 8
                ? "证据分析（BA1 / BS1 / PM2 / PP3 / BP4 / PS1 / PM5 / BP7）" : "原有证据入口（BA1 / BS1 等）");
        var local = this.local;
        if (local != null) choices.addObject().put("id", AcmgBusinessRuleScope.LOCAL_BATCH1)
                .put("label", local.configuredCriteria().contains("ACMG_AMP_2015:BP7")
                        ? "本地计算证据（PP3 / BP4 / PM2 / PS1 / PM5 / BP7）"
                        : "本地计算证据（PP3 / BP4 / PM2 / PS1 / PM5）");
        return result;
    }
}
