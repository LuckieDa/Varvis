package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Fictional disease/MOI/location control for public business routing, never real HBB knowledge. */
public final class Bs1RoutedPopulationTestMaterials {
    private Bs1RoutedPopulationTestMaterials() { }

    public static ObjectNode jointExpert(ObjectMapper mapper) {
        return record(true);
    }

    public static ObjectNode record(boolean joint) {
        var fixture = new Bs1JointSexChromosomeRuntimeTest();
        var record = joint ? fixture.record(true) : new Bs1SexChromosomeRuntimeTest().record("GT");
        String disease = joint ? "MONDO:0999001" : "MONDO:0999002";
        record.put("recordId", "SYNTHETIC_ROUTED_X_HBB_" + (joint ? "JOINT" : "EXOMES"));
        record.withObject("/scope").put("hgncId", "HGNC:4827").put("mondoId", disease);
        record.withObject("/expertPolicy/scope").put("hgncId", "HGNC:4827").put("mondoId", disease);
        record.putObject("relationship").put("symbol", "HBB").put("diseaseLabel", "SYNTHETIC X routing disease; not HBB biology")
                .put("sourceId", record.at("/sources/0/sourceId").asText()).put("specificationId", "GN900")
                .put("specificationVersion", record.path("version").asText()).put("approvedOn", "2026-11-03T00:00:00Z")
                .put("panel", "SYNTHETIC_ROUTING_TEST_NOT_CLINICAL_APPROVAL")
                .put("url", "https://cspec.clinicalgenome.org/cspec/ui/svi/doc/GN900");
        qualify(record);
        return record;
    }

    public static void qualify(ObjectNode record) {
        if (Bs1SexChromosomePolicy.isJoint(record.path("expertPolicy"))) new Bs1JointSexChromosomeRuntimeTest().qualify(record);
        else Bs1SexChromosomeStatisticTest.qualify(record);
    }

    public static ObjectNode bundle(ObjectMapper mapper, ObjectNode... records) {
        var bundle = mapper.createObjectNode().put("schemaVersion", "bs1-knowledge-bundle-v1")
                .put("version", "SYNTHETIC_ROUTED_PUBLIC_BUSINESS_V1");
        var sources = bundle.putArray("sources"); var ids = new java.util.HashSet<String>();
        var rows = bundle.putArray("records");
        for (var record : records) {
            rows.add(record.deepCopy());
            for (var source : record.path("sources")) if (ids.add(source.path("sourceId").asText())) sources.add(source.deepCopy());
        }
        bundle.putArray("backgrounds"); return bundle;
    }
}
