package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Reads individual current SCVs, not aggregate classification as independent
 * pathogenicity. A matching ERepo submission is the SAME assertion, never an
 * additional observation. Coding-allele equivalence is checked by the caller. */
final class Pvs1StartLossClinVarAssertions {
    private Pvs1StartLossClinVarAssertions() { }
    static ObjectNode read(ObjectMapper mapper, JsonNode context, JsonNode summary,
            Pvs1StartLossPublicSources.Snapshot snapshot) {
        return read(mapper, context, summary.path("cvId").asText(), summary, snapshot, false);
    }
    /** Negative candidate resolution inspects every SCV, never the VCV aggregate.
     * A P assertion is a lead requiring independence review, not a witness. */
    static ObjectNode candidate(ObjectMapper mapper, JsonNode context, String id,
            Pvs1StartLossPublicSources.Snapshot snapshot) {
        return read(mapper, context, id, null, snapshot, false);
    }
    /** Conflict review of another record for an already independently qualified
     * allele. This never supplies a second observation or independent P basis. */
    static ObjectNode equivalentRecord(ObjectMapper mapper, JsonNode context, String id,
            Pvs1StartLossPublicSources.Snapshot snapshot) {
        return read(mapper, context, id, null, snapshot, true);
    }
    private static ObjectNode read(ObjectMapper mapper, JsonNode context, String id,
            @org.springframework.lang.Nullable JsonNode summary, Pvs1StartLossPublicSources.Snapshot snapshot, boolean equivalentRecord) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-clinvar-reconciliation-v1")
                .put("status", "UNRESOLVED").put("independentPathogenicityGranted", false);
        out.set("source", snapshot.receipt());
        try {
            require(id.matches("[1-9][0-9]{0,8}"), "CLINVAR_VARIATION_ID_INVALID");
            String expectedMode = summary == null ? switch (context.path("moi").asText()) {
                case "AUTOSOMAL_DOMINANT" -> "Autosomal dominant inheritance";
                case "AUTOSOMAL_RECESSIVE" -> "Autosomal recessive inheritance";
                case "X_LINKED_DOMINANT" -> "X-linked dominant inheritance";
                case "X_LINKED_RECESSIVE" -> "X-linked recessive inheritance";
                default -> "";
            } : summary.path("moi").asText();
            require(!expectedMode.isBlank(), "CLINVAR_CONTEXT_INHERITANCE_UNRESOLVED");
            String accession = "VCV" + "0".repeat(9 - id.length()) + id;
            require("AVAILABLE".equals(snapshot.receipt().path("status").asText())
                    && "XML".equals(snapshot.receipt().path("format").asText()), "CLINVAR_ASSERTION_SOURCE_UNAVAILABLE");
            byte[] raw = snapshot.document().path("xml").asText().getBytes(StandardCharsets.UTF_8);
            require(raw.length > 0 && raw.length <= 4_000_000
                    && CodingTranscriptMaterialSupply.sha(raw).equals(snapshot.receipt().path("sha256").asText()), "CLINVAR_ASSERTION_SOURCE_PIN_MISMATCH");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            Element root = builder.parse(new ByteArrayInputStream(raw)).getDocumentElement();
            limit(root, 0, new int[]{0});
            require(Set.of("ClinVarResult-Set", "VariationArchive").contains(root.getTagName()), "CLINVAR_XML_ROOT_UNSUPPORTED");
            Element archive = "VariationArchive".equals(root.getTagName()) ? root : one(root, "VariationArchive");
            require(id.equals(archive.getAttribute("VariationID")) && accession.equals(archive.getAttribute("Accession"))
                    && archive.getAttribute("Version").matches("[1-9][0-9]*")
                    && "current".equals(text(archive, "RecordStatus")) && "Homo sapiens".equals(text(archive, "Species")), "CLINVAR_RECORD_IDENTITY_OR_STATUS_INVALID");
            require(children(archive, "IncludedRecord").isEmpty(), "CLINVAR_COMPOSITE_ALLELE_UNSUPPORTED");
            var classified = one(archive, "ClassifiedRecord"); var allele = one(classified, "SimpleAllele");
            require(children(classified, "Haplotype").isEmpty() && children(classified, "Genotype").isEmpty()
                    && id.equals(allele.getAttribute("VariationID")), "CLINVAR_COMPOSITE_ALLELE_UNSUPPORTED");
            var genes = children(one(allele, "GeneList"), "Gene");
            String hgnc = context.at("/gene/hgncId").asText(), symbol = context.at("/gene/symbol").asText();
            require(hgnc.matches("HGNC:[1-9][0-9]*") && !symbol.isBlank() && !genes.isEmpty() && genes.size() <= 100,
                    "CLINVAR_REFERENCE_GENE_MISMATCH");
            int matched = 0, matchedIndex = -1;
            for (int i = 0; i < genes.size(); i++) {
                var gene = genes.get(i);
                boolean sameId = hgnc.equals(gene.getAttribute("HGNC_ID")), sameSymbol = symbol.equals(gene.getAttribute("Symbol"));
                // Overlap with another gene is not ambiguity in an already
                // explicit context. A conflicting ID/symbol or duplicate is.
                require(sameId == sameSymbol, "CLINVAR_REFERENCE_GENE_MISMATCH");
                if (sameId) { matched++; matchedIndex = i; }
            }
            require(matched == 1, "CLINVAR_REFERENCE_GENE_MISMATCH");
            out.putObject("geneMatch").put("hgncId", hgnc).put("symbol", symbol).put("sourceGeneCount", genes.size())
                    .put("sourceLocator", "/VariationArchive/ClassifiedRecord/SimpleAllele/GeneList/Gene[" + (matchedIndex + 1) + "]")
                    .put("method", "UNIQUE_EXACT_CONTEXT_GENE");
            var locations = children(one(allele, "Location"), "SequenceLocation").stream()
                    .filter(e -> "GRCh38".equals(e.getAttribute("Assembly"))).toList();
            require(locations.size() == 1, "CLINVAR_REFERENCE_LOCATION_UNRESOLVED");
            var location = locations.get(0);
            require("GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                    && context.at("/variant/evidenceQueryAllele/contig").asText().equals(location.getAttribute("Chr"))
                    && location.getAttribute("positionVCF").matches("[1-9][0-9]{0,8}")
                    && location.getAttribute("referenceAlleleVCF").matches("[ACGT]{1,50}")
                    && location.getAttribute("alternateAlleleVCF").matches("[ACGT]{1,50}"), "CLINVAR_REFERENCE_LOCATION_UNRESOLVED");
            out.putObject("allele").put("assembly", "GRCh38").put("contig", location.getAttribute("Chr"))
                    .put("position", Integer.parseInt(location.getAttribute("positionVCF")))
                    .put("reference", location.getAttribute("referenceAlleleVCF")).put("alternate", location.getAttribute("alternateAlleleVCF"));
            out.put("accession", accession + "." + archive.getAttribute("Version"));
            var assertions = children(one(classified, "ClinicalAssertionList"), "ClinicalAssertion");
            require(!assertions.isEmpty() && assertions.size() <= 1000
                    && String.valueOf(assertions.size()).equals(archive.getAttribute("NumberOfSubmissions")), "CLINVAR_ASSERTION_ENUMERATION_UNRESOLVED");
            var records = out.putArray("assertions"); var identifiers = new LinkedHashSet<String>();
            int linked = 0; boolean conflict = false, unresolved = false;
            for (int i = 0; i < assertions.size(); i++) {
                var a = assertions.get(i); var scv = one(a, "ClinVarAccession");
                String base = scv.getAttribute("Accession"), version = scv.getAttribute("Version");
                require(base.matches("SCV[0-9]{9}") && version.matches("[1-9][0-9]*") && identifiers.add(base), "CLINVAR_ASSERTION_IDENTITY_UNRESOLVED");
                var row = records.addObject().put("accession", base + "." + version)
                        .put("sourceLocator", "/VariationArchive/ClassifiedRecord/ClinicalAssertionList/ClinicalAssertion[" + (i + 1) + "]")
                        .put("submitter", scv.getAttribute("SubmitterName")).put("orgId", scv.getAttribute("OrgID"));
                String status = text(a, "RecordStatus"); row.put("recordStatus", status);
                if (Set.of("withdrawn", "replaced", "removed").contains(status)) { row.put("disposition", "INACTIVE_EXCLUDED"); continue; }
                if (!"current".equals(status)) { unresolved = true; row.put("disposition", "STATUS_UNRESOLVED"); continue; }
                var classification = one(a, "Classification");
                String value = text(classification, "GermlineClassification");
                row.put("classification", value).put("reviewStatus", text(classification, "ReviewStatus"))
                        .put("evaluationDate", classification.getAttribute("DateLastEvaluated"));
                var diseases = diseases(a); row.set("mondoIds", mapper.valueToTree(diseases));
                boolean exact = diseases.equals(Set.of(context.at("/disease/mondoId").asText()));
                boolean other = !diseases.isEmpty() && !diseases.contains(context.at("/disease/mondoId").asText())
                        && completeDiseases(a);
                String mode = inheritance(a); row.put("inheritance", mode);
                boolean sameSubmission = summary != null && children(a, "ClinVarSubmissionID").stream()
                        .anyMatch(s -> summary.path("uuid").asText().equals(s.getAttribute("localKey")));
                if (summary != null && sameSubmission) {
                    require(exact && completeDiseases(a) && mode.equals(expectedMode) && "Pathogenic".equals(value)
                            && "reviewed by expert panel".equals(text(classification, "ReviewStatus"))
                            && classification.getAttribute("DateLastEvaluated").equals(summary.path("approvedDate").asText())
                            && children(classification, "Comment").stream().anyMatch(c -> normalized(c.getTextContent()).equals(normalized(summary.path("summaryDesc").asText()))), "CLINVAR_EREPO_SUBMISSION_MISMATCH");
                    LocalDate.parse(classification.getAttribute("DateLastEvaluated"));
                    linked++; row.put("disposition", "SAME_EREPO_EXPERT_ASSERTION").put("additionalEvidenceCount", 0); continue;
                }
                if (other || Set.of("Autosomal dominant inheritance", "Autosomal recessive inheritance",
                        "X-linked dominant inheritance", "X-linked recessive inheritance").contains(mode)
                        && !mode.equals(expectedMode)) {
                    row.put("disposition", "OTHER_BACKGROUND_EXCLUDED"); continue;
                }
                if (summary == null && !equivalentRecord) {
                    try { LocalDate.parse(classification.getAttribute("DateLastEvaluated")); }
                    catch (RuntimeException unknownDate) {
                        unresolved = true; row.put("disposition", "EVALUATION_DATE_UNRESOLVED"); continue;
                    }
                    if (Set.of("Likely pathogenic", "Uncertain significance", "Likely benign", "Benign", "Benign/Likely benign").contains(value))
                        row.put("disposition", "NOT_PATHOGENIC_ASSERTION");
                    else {
                        unresolved = true;
                        row.put("disposition", Set.of("Pathogenic", "Pathogenic/Likely pathogenic").contains(value)
                                ? "PATHOGENIC_LEAD_REQUIRES_INDEPENDENCE_REVIEW" : "CLASSIFICATION_UNRESOLVED");
                    }
                    continue;
                }
                if (equivalentRecord) {
                    try { LocalDate.parse(classification.getAttribute("DateLastEvaluated")); }
                    catch (RuntimeException unknownDate) {
                        unresolved = true; row.put("disposition", "EVALUATION_DATE_UNRESOLVED"); continue;
                    }
                }
                if (Set.of("Pathogenic", "Likely pathogenic", "Pathogenic/Likely pathogenic").contains(value)) {
                    row.put("disposition", "NON_ADVERSE_NOT_INDEPENDENTLY_QUALIFIED"); continue;
                }
                boolean adverse = Set.of("Uncertain significance", "Likely benign", "Benign", "Benign/Likely benign").contains(value);
                if (exact && adverse) { conflict = true; row.put("disposition", "CONFLICTING_CURRENT_ASSERTION"); }
                else { unresolved = true; row.put("disposition", "POTENTIAL_CONFLICT_REQUIRES_BACKGROUND_REVIEW"); }
            }
            out.put("assertionsEnumerated", assertions.size()).put("linkedExpertAssertions", linked);
            if (summary == null && !equivalentRecord) return unresolved
                    ? out.put("reasonCode", "CLINVAR_CANDIDATE_QUALIFICATION_REQUIRED")
                    : out.put("status", "NO_PATHOGENIC_ASSERTION_IN_SCOPE").put("reasonCode", "CLINVAR_NO_CURRENT_PATHOGENIC_ASSERTION_IN_SCOPE");
            if (!equivalentRecord) require(linked == 1, "CLINVAR_LINKED_EXPERT_ASSERTION_UNRESOLVED");
            if (conflict) return out.put("status", "CONFLICT").put("reasonCode", "CLINVAR_REFERENCE_CURRENT_ASSERTION_CONFLICT");
            if (unresolved) return out.put("reasonCode", "CLINVAR_REFERENCE_POTENTIAL_CONFLICT_UNRESOLVED");
            if (equivalentRecord) return out.put("status", "NO_ADVERSE_CURRENT_ASSERTION")
                    .put("reasonCode", "CLINVAR_EQUIVALENT_RECORD_REVIEWED").put("additionalEvidenceCount", 0);
            return out.put("status", "CONSISTENT_WITH_EREPO").put("reasonCode", "CLINVAR_EREPO_REFERENCE_CONSISTENT");
        } catch (Exception failure) {
            String reason = failure.getMessage();
            return out.put("reasonCode", reason != null && reason.matches("[A-Z_]+") ? reason : "CLINVAR_ASSERTION_XML_INVALID");
        }
    }
    private static String normalized(String text) { return text.replaceAll("(?U)\\s+", " ").strip(); }
    private static List<Element> children(Element parent, String tag) {
        var out = new ArrayList<Element>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && tag.equals(e.getTagName())) out.add(e);
        return out;
    }
    private static Element one(Element parent, String tag) {
        var found = children(parent, tag); require(found.size() == 1, "CLINVAR_XML_REQUIRED_ELEMENT_UNRESOLVED"); return found.get(0);
    }
    private static String text(Element parent, String tag) {
        var found = children(parent, tag); return found.size() == 1 ? found.get(0).getTextContent().strip() : "";
    }
    private static Set<String> diseases(Element assertion) {
        var out = new LinkedHashSet<String>();
        for (var set : children(assertion, "TraitSet")) for (var trait : children(set, "Trait"))
            for (var x : children(trait, "XRef")) if ("MONDO".equals(x.getAttribute("DB")) && x.getAttribute("ID").matches("MONDO:[0-9]{7}")) out.add(x.getAttribute("ID"));
        return out;
    }
    private static boolean completeDiseases(Element assertion) {
        var sets = children(assertion, "TraitSet"); if (sets.isEmpty()) return false;
        for (var set : sets) {
            var traits = children(set, "Trait"); if (traits.isEmpty()) return false;
            for (var trait : traits) if (children(trait, "XRef").stream().noneMatch(x ->
                    "MONDO".equals(x.getAttribute("DB")) && x.getAttribute("ID").matches("MONDO:[0-9]{7}"))) return false;
        }
        return true;
    }
    private static String inheritance(Element assertion) {
        var values = new LinkedHashSet<String>();
        for (var set : children(assertion, "AttributeSet")) for (var a : children(set, "Attribute"))
            if ("ModeOfInheritance".equals(a.getAttribute("Type"))) values.add(a.getTextContent().strip());
        return values.size() == 1 ? values.iterator().next() : "";
    }
    private static void limit(Node node, int depth, int[] count) {
        require(depth <= 64 && ++count[0] <= 60000, "CLINVAR_XML_STRUCTURE_LIMIT");
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) limit(n, depth + 1, count);
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
