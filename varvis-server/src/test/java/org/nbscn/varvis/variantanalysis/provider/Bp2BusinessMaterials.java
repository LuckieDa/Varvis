package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
/** Access to the existing independent TEST_ONLY issuer; no production hashing supplies expected pins. */
public final class Bp2BusinessMaterials {
    private Bp2BusinessMaterials() { }
    public static String hash(JsonNode node) throws Exception { return Bp2TestMaterials.hash(node); }
    public static void reissue(ObjectNode root) throws Exception { Bp2TestMaterials.reissue(root, "facts", "phase", "partnerClassification", "diseaseApplicability"); }
}
