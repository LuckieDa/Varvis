package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.engine.ExomiserRuntimeValidator;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only readiness: no provider lookup, scientific process, upload or database. */
class PredictionPublicPreparationTest {
    @Test @EnabledIfSystemProperty(named = "varvis.prediction.preparation.directory", matches = ".+")
    void inspectConfiguredResourcesAndExactReferenceWithoutScienceExecution() throws Exception {
        var root = Path.of(System.getProperty("varvis.prediction.preparation.directory"));
        var mapper = new ObjectMapper(); var values = new Properties();
        try (var input = Files.newInputStream(root.resolve("preflight.properties"))) { values.load(input); }
        var map = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> map.put(key.toString(), value));
        var settings = new Binder(new MapConfigurationPropertySource(map))
                .bind("variant-analysis", Bindable.of(VariantAnalysisProperties.class)).get();
        var binding = PredictionLocalResourceBinding.load(mapper, root.resolve("local-binding.json"),
                Files.readString(root.resolve("local-binding.sha256")).trim());
        var registered = mapper.readTree(root.resolve("frozen-input.json").toFile());
        var output = mapper.createObjectNode().put("kind", "READ_ONLY_RESOURCE_PREFLIGHT")
                .put("providerLookups", 0).put("transportAttempts", 0).put("wholeResourcesRehashed", false)
                .put("businessRun", false).put("processExecutionVerified", false);
        output.set("exomiserResources", mapper.valueToTree(new ExomiserRuntimeValidator().validate(settings.getExomiser())));
        output.set("vepResources", mapper.valueToTree(new VepResourceValidator(settings).validate()));
        var controls = output.putArray("controls");
        for (var control : registered.path("controls")) {
            var key = mapper.treeToValue(control.path("queryAllele"), NormalizedVariantKey.class);
            String issue = binding.issue(settings.getAlphaMissense().getGrch38File(), key, binding.identity(), "SYNTHETIC");
            var item = controls.addObject(); item.set("queryAllele", control.path("queryAllele"));
            item.put("referenceAndBinding", issue == null ? "VERIFIED" : issue);
        }
        Files.write(root.resolve("resource-readiness.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(output), StandardOpenOption.CREATE_NEW);
        assertEquals(2, controls.size());
        for (var control : controls) assertEquals("VERIFIED", control.path("referenceAndBinding").asText());
    }
}
