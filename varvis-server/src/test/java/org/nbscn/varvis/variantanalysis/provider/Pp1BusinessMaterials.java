package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
/** Test-only access to the frozen PP1 independent issuer. */
public final class Pp1BusinessMaterials {
    private Pp1BusinessMaterials() { }
    public static String hash(JsonNode n) throws Exception { return Pp1TestMaterials.hash(n); }
    public static void reissue(ObjectNode n) throws Exception { Pp1TestMaterials.reissue(n, Pp1TestMaterials.PARTS.toArray(String[]::new)); }
    public static void pinsOnly(ObjectNode n) throws Exception { Pp1TestMaterials.pinsOnly(n); }
    public static void call(ObjectNode r, String gt) { Pp1TestMaterials.call(r, gt); }
}
