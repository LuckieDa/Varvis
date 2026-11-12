package org.nbscn.varvis.variantanalysis.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessWorkflows;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, bounded configuration projection; no paths, credentials or case data. */
@RestController
@RequestMapping("/variant-analyses/acmg")
@ConditionalOnProperty(name = "varvis.datasource.enabled", havingValue = "true", matchIfMissing = true)
public class AcmgConfigurationController {
    private final AcmgBusinessWorkflows workflows;
    public AcmgConfigurationController(AcmgBusinessWorkflows workflows) { this.workflows = workflows; }

    @GetMapping("/configuration")
    public ObjectNode configuration() { return workflows.current().configurationSummary(); }
}
