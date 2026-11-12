package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
/** Access to the existing independent TEST_ONLY issuer; no production hashing supplies expected pins. */
public final class Pm3BusinessMaterials {
    private Pm3BusinessMaterials() { }
    public static String hash(JsonNode node) throws Exception { return Pm3TestMaterials.hash(node); }
    public static void reissue(ObjectNode root) throws Exception { Pm3TestMaterials.reissue(root, "facts", "phase", "partnerClassification", "targetRarity", "partnerRarity"); }
}
