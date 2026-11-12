package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
/** TEST_ONLY access to the independent PP4 issuer; fixed inputs are never reissued at load. */
public final class Pp4BusinessMaterials {
    private Pp4BusinessMaterials() { }
    public static String hash(JsonNode n) throws Exception { return Pp4TestMaterials.hash(n); }
    public static void reissue(ObjectNode n) throws Exception { Pp4TestMaterials.reissue(n, Pp4TestMaterials.PARTS.toArray(String[]::new)); }
    public static void pinsOnly(ObjectNode n) throws Exception { Pp4TestMaterials.pinsOnly(n); }
}
