package org.nbscn.varvis.variantanalysis.config;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Server configuration and finite policy routing; no daily environment or provider execution. */
class AcmgRoutedPopulationConfigTest {
    @TempDir Path temporary;
    @Test void pinnedJointKnowledgeEntersNormalBusinessWithoutChangingSourceSwitches() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var bytes=mapper.writeValueAsBytes(Bs1RoutedPopulationTestMaterials.bundle(mapper,Bs1RoutedPopulationTestMaterials.record(true)));
        var file=temporary.resolve("synthetic-routing-knowledge.json");Files.write(file,bytes);
        var env=new MockEnvironment().withProperty("variant-analysis.acmg-business.enabled","true")
                .withProperty("variant-analysis.acmg-business.ba1-local-exomes-enabled","true")
                .withProperty("variant-analysis.acmg-business.bs1-local-exomes-enabled","true")
                .withProperty("variant-analysis.acmg-business.bs1-knowledge-file",requireNonNull(file.toString()))
                .withProperty("variant-analysis.acmg-business.bs1-knowledge-sha256",requireNonNull(Bs1KnowledgeCatalog.sha(bytes)));
        var settings=new VariantAnalysisProperties();settings.getGnomad().setEnabled(false);settings.getGnomad().setPopulationInputEnabled(false);
        var service=new AcmgBusinessConfig().acmgBusinessService(mapper,null,null,null,settings,env);
        var profile=(AcmgExecutionProfile)ReflectionTestUtils.getField(requireNonNull(service),"executionProfile");assertNotNull(profile);
        assertEquals("POLICY_ROUTED_POPULATION_V1",profile.document().at("/bs1RuntimeBinding/activationContract").asText());
        assertEquals(Bs1KnowledgeCatalog.sha(bytes),profile.document().at("/bs1RuntimeBinding/knowledgeIdentity/rawSha256").asText());
        Object rawFactory=ReflectionTestUtils.getField(requireNonNull(service),"bs1Runtime");assertInstanceOf(Supplier.class,rawFactory);
        Object runtime=((Supplier<?>)requireNonNull(rawFactory)).get();assertInstanceOf(Bs1Runtime.class,runtime);assertTrue(((Bs1Runtime)runtime).isPolicyRouted());
        assertFalse(settings.getGnomad().isEnabled());assertFalse(settings.getGnomad().isPopulationInputEnabled());
        assertEquals("4.2.0",profile.document().path("profileVersion").asText());
    }
    @Test void effectiveJointSettingsKeepDisabledDistinctFromReadyAndNeverEnableFallback() {
        var properties=new VariantAnalysisProperties();var source=properties.getGnomad();
        source.setEnabled(false);source.setPopulationInputEnabled(true);source.setVersion("unchanged-ranking-version");
        var env=new MockEnvironment().withProperty("variant-analysis.acmg-business.gnomad-timeout-seconds","10")
                .withProperty("variant-analysis.acmg-business.gnomad-total-timeout-seconds","20");
        var joint=AcmgBusinessConfig.jointPopulationSettings(properties,env).getGnomad();
        assertFalse(joint.isEnabled());assertTrue(joint.isPopulationInputEnabled());
        assertEquals("gnomAD joint v4.1 GRCh38",joint.getVersion());
        assertFalse(joint.isAllowFullVcfFallback());assertFalse(joint.isAllowVcfInfoFallback());
        assertEquals(java.time.Duration.ofSeconds(10),joint.getTimeout());
        assertEquals("unchanged-ranking-version",source.getVersion());assertFalse(source.isEnabled());
    }
    @Test void normalJointBaseCanLoadPinnedKnowledgeWithoutAnExomesExecutionFlag() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();var bytes=mapper.writeValueAsBytes(Bs1RoutedPopulationTestMaterials.bundle(mapper,Bs1RoutedPopulationTestMaterials.record(true)));
        var file=temporary.resolve("joint-base.json");Files.write(file,bytes);
        var env=new MockEnvironment().withProperty("variant-analysis.acmg-business.bs1-knowledge-file",requireNonNull(file.toString()))
                .withProperty("variant-analysis.acmg-business.bs1-knowledge-sha256",requireNonNull(Bs1KnowledgeCatalog.sha(bytes)));
        var service=new AcmgBusinessConfig().acmgBusinessService(mapper,null,null,null,new VariantAnalysisProperties(),env);
        var profile=(AcmgExecutionProfile)ReflectionTestUtils.getField(requireNonNull(service),"executionProfile");assertNotNull(profile);
        assertEquals("POLICY_ROUTED_POPULATION_V1",profile.document().at("/bs1RuntimeBinding/activationContract").asText());
        assertFalse(profile.document().path("formalEnabled").asBoolean());
        assertFalse(profile.document().at("/bs1RuntimeBinding/sourceRouting/sources/0/enabled").asBoolean());
        assertFalse(profile.document().at("/bs1RuntimeBinding/sourceRouting/sources/1/enabled").asBoolean());
    }
}
