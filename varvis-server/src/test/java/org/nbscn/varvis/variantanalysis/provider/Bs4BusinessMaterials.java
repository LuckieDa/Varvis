package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
/** Test-only access to the frozen BS4 independent issuer. */
public final class Bs4BusinessMaterials {
    private Bs4BusinessMaterials() { }
    public static String hash(JsonNode n) throws Exception { return Bs4TestMaterials.hash(n); }
    public static void reissue(ObjectNode n) throws Exception { Bs4TestMaterials.reissue(n, Bs4TestMaterials.PARTS.toArray(String[]::new)); }
    public static void pinsOnly(ObjectNode n) throws Exception { Bs4TestMaterials.pinsOnly(n); }
    public static void call(ObjectNode r, String gt) { Bs4TestMaterials.call(r, gt); }
}
