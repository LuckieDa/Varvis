package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import static org.nbscn.varvis.variantanalysis.provider.Ps1SpliceComparison.require;

/** Pinned, server-owned research knowledge. Original expert assertions are checked against raw XML.
 * No new external approval or patient classification is created. No per-target pairing is stored. */
public final class Ps1SpliceKnowledge {
    public static final String VERSION = "1.1.0";
    private final ObjectNode content;
    private final String pin;
    private final Map<String, Map<String, String>> codingExpressions;
    private final Map<String, JsonNode> transcriptModels;
    private Ps1SpliceKnowledge(ObjectNode content, String pin, Map<String, Map<String, String>> expressions, Map<String, JsonNode> models) {
        this.content = content.deepCopy(); this.pin = pin; codingExpressions = Map.copyOf(expressions); transcriptModels = Map.copyOf(models);
    }

    public static Ps1SpliceKnowledge load(ObjectMapper mapper, Path file, String pin) throws Exception {
        byte[] bytes = read(file, pin, 4_000_000);
        var strict = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode value = strict.readTree(bytes);
        require(value instanceof ObjectNode && "ps1-splice-knowledge-v1".equals(value.path("schemaVersion").asText())
                && "PROJECT_RESEARCH".equals(value.path("purpose").asText())
                && value.path("scientificApprovalGranted").isBoolean() && !value.path("scientificApprovalGranted").asBoolean(),
                "PS1_SPLICE_KNOWLEDGE_SCHEMA_INVALID");
        require(value.path("policies").isArray() && !value.path("policies").isEmpty() && value.path("policies").size() <= 128
                && value.path("references").isArray() && value.path("references").size() <= 256, "PS1_SPLICE_KNOWLEDGE_CONTENT_INVALID");
        var sourceIds = new HashSet<String>();
        for (var p : value.path("policies")) {
            require(sourceIds.add(p.path("policyId").asText()) && !p.path("policyId").asText().isBlank()
                    && "CLINGEN_SVI_2023_TABLE2".equals(p.path("strengthPolicy").asText())
                    && Set.of("NO_WEAKER", "PUBLISHED_RELATIVE_TEN_PERCENT").contains(p.path("similarityMode").asText())
                    && Set.of("SINGLE_NATIVE_SITE_LOSS", "MATCHED_NATIVE_LOSS_PROFILE").contains(p.path("eventScope").asText())
                    && "SOURCE_ORIGINAL_ACMG2015_EXCLUDE_PS1_PM5".equals(p.path("referenceQualification").asText())
                    && p.path("supportedOriginalVersions").isArray() && !p.path("supportedOriginalVersions").isEmpty()
                    && p.path("sourceUrl").asText().startsWith("https://")
                    && p.path("sourceSha256").asText().matches("[0-9a-f]{64}"), "PS1_SPLICE_POLICY_INVALID");
            for (String field : List.of("hgncId", "geneSymbol", "mondoId", "moi", "transcript", "expertOrgId"))
                require(!p.path("scope").path(field).asText().isBlank(), "PS1_SPLICE_POLICY_SCOPE_INVALID");
            Path source = file.toAbsolutePath().getParent().resolve(p.path("sourceFile").asText()).normalize();
            require(source.startsWith(file.toAbsolutePath().getParent()), "PS1_SPLICE_SOURCE_PATH_INVALID");
            String original = new String(read(source, p.path("sourceSha256").asText(), 2_000_000), java.nio.charset.StandardCharsets.UTF_8);
            require(!p.path("sourceClause").asText().isBlank() && original.contains(p.path("sourceClause").asText()), "PS1_SPLICE_POLICY_SOURCE_MISMATCH");
            if ("PUBLISHED_RELATIVE_TEN_PERCENT".equals(p.path("similarityMode").asText()))
                require(p.path("sourceClause").asText().contains("SpliceAI score within 10% of the original variant"), "PS1_SPLICE_SIMILARITY_SOURCE_REQUIRED");
            if (p.has("targetBaselineMode")) require("SOURCE_ORIGINAL_EXPERT_PVS1".equals(p.path("targetBaselineMode").asText())
                    && p.path("sourceTranscript").asText().matches("NM_[0-9]+\\.[1-9][0-9]*"), "PS1_SPLICE_BASELINE_POLICY_INVALID");
        }
        Map<String, JsonNode> models = new HashMap<>();
        if (value.has("transcriptModels")) {
            require(value.path("transcriptModels").isArray() && value.path("transcriptModels").size() <= 128, "PS1_SPLICE_BASELINE_MODEL_INVALID");
            for (var spec : value.path("transcriptModels")) {
                Path source = file.toAbsolutePath().getParent().resolve(spec.path("file").asText()).normalize();
                require(source.startsWith(file.toAbsolutePath().getParent()) && !spec.path("file").asText().isBlank(), "PS1_SPLICE_SOURCE_PATH_INVALID");
                var model = strict.readTree(read(source, spec.path("sha256").asText(), 2_000_000));
                String tx = spec.path("sourceTranscript").asText();
                require(PublishedCriterionKnowledge.exactBridge(tx, model) && models.putIfAbsent(tx, model) == null,
                        "PS1_SPLICE_BASELINE_TRANSCRIPT_PROOF_INVALID");
            }
        }
        Map<String, Map<String, String>> expressions = new HashMap<>();
        Set<String> refs = new HashSet<>();
        for (var r : value.path("references")) {
            require(refs.add(r.path("scv").asText()), "PS1_SPLICE_REFERENCE_AMBIGUOUS");
            Path raw = file.toAbsolutePath().getParent().resolve(r.path("xmlFile").asText()).normalize();
            require(raw.startsWith(file.toAbsolutePath().getParent()) && !r.path("xmlFile").asText().isBlank(), "PS1_SPLICE_SOURCE_PATH_INVALID");
            expressions.put(r.path("scv").asText(), verifyXml(read(raw, r.path("xmlSha256").asText(), 2_000_000), r));
        }
        return new Ps1SpliceKnowledge((ObjectNode) value, pin, expressions, models);
    }

    public ObjectNode identity(ObjectMapper mapper) {
        return mapper.createObjectNode().put("method", "PS1_SPLICE_SOURCE_QUALIFIED_RESEARCH").put("version", VERSION)
                .put("knowledgeSha256", pin).put("scientificApprovalGranted", false);
    }

    ObjectNode select(JsonNode context, JsonNode model) {
        ObjectNode found = null;
        for (var p : content.path("policies")) {
            var s = p.path("scope");
            if (!s.path("hgncId").equals(context.at("/gene/hgncId")) || !s.path("geneSymbol").equals(context.at("/gene/symbol"))
                    || !s.path("mondoId").equals(context.at("/disease/mondoId")) || !s.path("moi").equals(context.path("moi"))
                    || !s.path("transcript").equals(model.path("transcript"))) continue;
            require(found == null, "PS1_SPLICE_POLICY_AMBIGUOUS"); found = (ObjectNode) p;
        }
        require(found != null, "PS1_SPLICE_POLICY_SCOPE_MISSING");
        return java.util.Objects.requireNonNull(found).deepCopy();
    }

    JsonNode references() { return content.path("references").deepCopy(); }

    /** Existing target assertion only. This does not evaluate PVS1 or transfer a reference's strength. */
    ObjectNode targetBaseline(ObjectMapper mapper, ObjectNode context, JsonNode currentModel, JsonNode policy) {
        require("SOURCE_ORIGINAL_EXPERT_PVS1".equals(policy.path("targetBaselineMode").asText()), "PS1_SPLICE_PVS1_BASELINE_REQUIRED");
        require(select(context, currentModel).equals(policy), "PS1_SPLICE_BASELINE_CONTEXT_MISMATCH");
        String tx = policy.path("sourceTranscript").asText();
        JsonNode proof = transcriptModels.get(tx);
        require(proof != null, "PS1_SPLICE_BASELINE_TRANSCRIPT_PROOF_MISSING");
        for (String field : List.of("assembly", "contig", "strand", "transcript", "protein", "hgncId", "geneSymbol", "cdsSequence", "cdsLength", "exons"))
            require(currentModel.path(field).equals(java.util.Objects.requireNonNull(proof).path(field)), "PS1_SPLICE_BASELINE_TRANSCRIPT_MISMATCH");
        require(currentModel.at("/sequenceVerification/fullSplicedTranscriptSha256").equals(java.util.Objects.requireNonNull(proof).at("/sequenceVerification/fullSplicedTranscriptSha256")),
                "PS1_SPLICE_BASELINE_TRANSCRIPT_MISMATCH");
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        var motif = Ps1SpliceComparison.motif(mapper, currentModel, allele.path("position").asInt());
        require(motif.path("canonical").asBoolean(), "PS1_SPLICE_BASELINE_CANONICAL_REQUIRED");
        boolean donor = "DONOR".equals(motif.path("kind").asText());
        var exon = currentModel.path("exons").get(motif.path("intronOrdinal").asInt() - (donor ? 1 : 0));
        int anchor = exon.path(donor ? "cdsEnd" : "cdsStart").asInt(), offset = motif.path("offset").asInt();
        require(anchor >= 1 && anchor <= currentModel.path("cdsLength").asInt(), "PS1_SPLICE_BASELINE_HGVS_UNSUPPORTED");
        String hgvs = "c." + anchor + (offset > 0 ? "+" : "") + offset
                + CodingTranscriptMapping.orientAllele(currentModel, allele.path("reference").asText()) + ">"
                + CodingTranscriptMapping.orientAllele(currentModel, allele.path("alternate").asText());
        var out = mapper.createObjectNode().put("origin", "SOURCE_ORIGINAL_EXPERT_PVS1").put("currentPvs1EvaluationCreated", false)
                .put("sourceTranscript", tx).put("runtimeTranscript", currentModel.path("transcript").asText()).put("codingHgvs", hgvs);
        var originals = out.putArray("assertions"); String strength = null;
        for (var ref : references()) {
            if (!ref.path("allele").equals(allele) || !ref.path("mondoId").equals(context.at("/disease/mondoId"))
                    || !ref.path("geneSymbol").equals(context.at("/gene/symbol")) || !ref.path("expertOrgId").equals(policy.at("/scope/expertOrgId"))) continue;
            var qualification = qualify(mapper, ref, policy);
            require(hgvs.equals(codingExpressions.getOrDefault(ref.path("scv").asText(), Map.of()).get(tx))
                    && java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(tx) + "(?![A-Za-z0-9_]|\\.[0-9])").matcher(ref.path("originalText").asText()).find(),
                    "PS1_SPLICE_BASELINE_SOURCE_TRANSCRIPT_MISMATCH");
            String selected = null;
            for (var code : qualification.path("retainedCriteria")) if (code.asText().startsWith("PVS1")) {
                selected = switch (code.asText()) {
                    case "PVS1", "PVS1_VeryStrong", "PVS1_VS" -> "PVS1";
                    case "PVS1_Strong" -> "PVS1_STRONG"; case "PVS1_Moderate" -> "PVS1_MODERATE";
                    case "PVS1_Supporting" -> "PVS1_SUPPORTING"; default -> null;
                };
            }
            require(selected != null, "PS1_SPLICE_BASELINE_EXPLICIT_STRENGTH_MISSING");
            require(strength == null || strength.equals(selected), "PS1_SPLICE_BASELINE_SOURCE_CONFLICT");
            strength = selected;
            var row = originals.addObject(); row.set("original", ref); row.set("qualification", qualification);
        }
        require(strength != null, "PS1_SPLICE_BASELINE_TARGET_SOURCE_MISSING");
        out.put("strength", strength); out.set("transcriptProof", java.util.Objects.requireNonNull(proof).deepCopy());
        out.set("knowledgeIdentity", identity(mapper)); return out;
    }

    /** Preserve an original P/LP only when removing comparison codes still yields that same class.
     * Unsupported/benign codes, uncertain defaults and downgrades are rejected, not silently omitted. */
    static ObjectNode qualify(ObjectMapper mapper, JsonNode ref, JsonNode policy) {
        var out = mapper.createObjectNode().put("mode", "SOURCE_ORIGINAL_ACMG2015_EXCLUDE_PS1_PM5")
                .put("newClinicalClassification", false);
        require(ref.path("mondoId").equals(policy.at("/scope/mondoId")) && ref.path("geneSymbol").equals(policy.at("/scope/geneSymbol"))
                && ref.path("expertOrgId").equals(policy.at("/scope/expertOrgId")), "PS1_SPLICE_REFERENCE_CONTEXT_MISMATCH");
        boolean supported = false;
        for (var v : policy.path("supportedOriginalVersions")) if (v.equals(ref.path("originalVersion"))) supported = true;
        require(supported, "PS1_SPLICE_REFERENCE_ORIGINAL_VERSION_UNSUPPORTED");
        String classification = ref.path("classification").asText();
        require(Set.of("PATHOGENIC", "LIKELY_PATHOGENIC").contains(classification), "PS1_SPLICE_REFERENCE_NOT_QUALIFIED");
        int vs = 0, strong = 0, moderate = 0, supporting = 0; boolean clinical = false;
        var seen = new HashSet<String>(); var retained = out.putArray("retainedCriteria"); var excluded = out.putArray("excludedComparisonCriteria");
        require(ref.path("criteria").isArray() && !ref.path("criteria").isEmpty(), "PS1_SPLICE_REFERENCE_FINAL_CRITERIA_MISSING");
        for (var item : ref.path("criteria")) {
            require(item.isTextual(), "PS1_SPLICE_REFERENCE_CRITERIA_UNSUPPORTED");
            String token = item.asText(); String[] parts = token.split("_", -1); String code = parts[0];
            require(seen.add(code) && token.matches("(?:PVS1|PS[1-4]|PM[12356]|PP[1234])(?:_(?:VeryStrong|VS|Strong|Moderate|Supporting))?"), "PS1_SPLICE_REFERENCE_CRITERIA_AMBIGUOUS");
            if (Set.of("PS1", "PM5").contains(code)) { excluded.add(token); continue; }
            require(Set.of("PVS1", "PS2", "PS3", "PS4", "PM1", "PM2", "PM3", "PM6", "PP1", "PP2", "PP3", "PP4").contains(code),
                    "PS1_SPLICE_REFERENCE_CRITERIA_UNSUPPORTED");
            String strength = parts.length == 2 ? parts[1] : code.equals("PVS1") ? "VeryStrong" : code.startsWith("PS") ? "Strong" : code.startsWith("PM") ? "Moderate" : "Supporting";
            switch (strength) {
                case "VeryStrong", "VS" -> vs++;
                case "Strong" -> strong++;
                case "Moderate" -> moderate++;
                case "Supporting" -> supporting++;
                default -> throw new IllegalArgumentException("PS1_SPLICE_REFERENCE_CRITERIA_UNSUPPORTED");
            }
            clinical |= Set.of("PS2", "PS4", "PM3", "PM6", "PP1", "PP4").contains(code);
            retained.add(token);
        }
        require(!(seen.contains("PS2") && seen.contains("PM6")), "PS1_SPLICE_REFERENCE_CRITERIA_DEPENDENT");
        require(vs <= 1, "PS1_SPLICE_REFERENCE_CRITERIA_UNSUPPORTED");
        String remaining = vs == 1 && (strong >= 1 || moderate >= 2 || moderate >= 1 && supporting >= 1 || supporting >= 2)
                ? "PATHOGENIC" : vs == 1 && moderate >= 1 ? "LIKELY_PATHOGENIC"
                : MissenseIndependentReference.combine(strong, moderate, supporting);
        require(classification.equals(remaining), "PS1_SPLICE_REFERENCE_INDEPENDENCE_INSUFFICIENT");
        return out.put("classification", classification).put("clinicalSupport", clinical).put("status", "SOURCE_QUALIFIED")
                .put("scv", ref.path("scv").asText()).put("originalVersion", ref.path("originalVersion").asText());
    }

    private static Map<String, String> verifyXml(byte[] raw, JsonNode r) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
        var archives = doc.getElementsByTagName("VariationArchive");
        require(archives.getLength() == 1, "PS1_SPLICE_XML_RECORD_INVALID");
        Element archive = (Element) archives.item(0);
        require(r.path("variationId").asText().equals(archive.getAttribute("VariationID"))
                && "current".equals(child(archive, "RecordStatus")), "PS1_SPLICE_XML_IDENTITY_INVALID");
        var genes = archive.getElementsByTagName("Gene"); boolean geneMatched = false;
        for (int i = 0; i < genes.getLength(); i++)
            if (r.path("geneSymbol").asText().equals(((Element) genes.item(i)).getAttribute("Symbol"))) geneMatched = true;
        require(geneMatched && "GRCh38".equals(r.at("/allele/assembly").asText()), "PS1_SPLICE_XML_GENE_ASSEMBLY_MISMATCH");
        var locations = archive.getElementsByTagName("SequenceLocation"); int locationsMatched = 0;
        for (int i = 0; i < locations.getLength(); i++) {
            Element loc = (Element) locations.item(i); JsonNode a = r.path("allele");
            if ("GRCh38".equals(loc.getAttribute("Assembly")) && a.path("contig").asText().equals(loc.getAttribute("Chr"))
                    && a.path("position").asText().equals(loc.getAttribute("positionVCF"))
                    && a.path("reference").asText().equals(loc.getAttribute("referenceAlleleVCF"))
                    && a.path("alternate").asText().equals(loc.getAttribute("alternateAlleleVCF"))) locationsMatched++;
        }
        require(locationsMatched == 1, "PS1_SPLICE_XML_ALLELE_MISMATCH");
        var assertions = archive.getElementsByTagName("ClinicalAssertion"); Element matched = null;
        for (int i = 0; i < assertions.getLength(); i++) {
            Element a = (Element) assertions.item(i); var ids = a.getElementsByTagName("ClinVarAccession");
            if (ids.getLength() != 1) continue; Element id = (Element) ids.item(0);
            if (!(id.getAttribute("Accession") + "." + id.getAttribute("Version")).equals(r.path("scv").asText())) continue;
            require(matched == null && r.path("expertOrgId").asText().equals(id.getAttribute("OrgID")), "PS1_SPLICE_XML_SCV_MISMATCH"); matched = a;
        }
        require(matched != null, "PS1_SPLICE_XML_SCV_MISSING"); Element a = java.util.Objects.requireNonNull(matched);
        require("current".equals(child(a, "RecordStatus")), "PS1_SPLICE_XML_SCV_NOT_CURRENT");
        var classes = a.getElementsByTagName("Classification"); require(classes.getLength() == 1, "PS1_SPLICE_XML_CLASSIFICATION_INVALID");
        Element c = (Element) classes.item(0);
        require("reviewed by expert panel".equals(child(c, "ReviewStatus"))
                && child(c, "GermlineClassification").toUpperCase(java.util.Locale.ROOT).replace(' ', '_').equals(r.path("classification").asText())
                && c.getAttribute("DateLastEvaluated").equals(r.path("evaluationDate").asText()), "PS1_SPLICE_XML_CLASSIFICATION_MISMATCH");
        require(diseases(a).equals(Set.of(r.path("mondoId").asText())), "PS1_SPLICE_XML_DISEASE_MISMATCH");
        for (int i = 0; i < assertions.getLength(); i++) {
            Element other = (Element) assertions.item(i);
            if (other == a || !"current".equals(child(other, "RecordStatus")) || !diseases(other).contains(r.path("mondoId").asText())) continue;
            var otherClasses = other.getElementsByTagName("Classification");
            for (int j = 0; j < otherClasses.getLength(); j++) {
                Element otherClass = (Element) otherClasses.item(j);
                if ("reviewed by expert panel".equals(child(otherClass, "ReviewStatus")))
                    require(child(otherClass, "GermlineClassification").equals(child(c, "GermlineClassification")), "PS1_SPLICE_XML_EXPERT_CONFLICT");
            }
        }
        boolean statement = false; var comments = c.getElementsByTagName("Comment");
        for (int i = 0; i < comments.getLength(); i++) if (comments.item(i).getTextContent().equals(r.path("originalText").asText())) statement = true;
        require(statement, "PS1_SPLICE_XML_STATEMENT_MISMATCH");
        String suffix = r.path("rawSuffix").asText();
        String original = r.path("originalText").asText();
        String marker = "ACMG/AMP criteria applied";
        int start = original.indexOf(marker), colon = original.indexOf(':', start);
        require(start >= 0 && start == original.lastIndexOf(marker) && colon > start
                && (start == 0 || original.substring(0, start).stripTrailing().endsWith("."))
                && original.substring(colon + 1).equals(suffix)
                && original.substring(start + marker.length(), colon).matches(
                        "(?:,\\s+as specified by [^:]{1,1024}|\\s*\\(specification version [^:]{1,1024}\\))")
                && !java.util.regex.Pattern.compile("\\b(?:not|no|none|hypothetical|proposed|example|considered|may|would|could)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(original.substring(start, colon)).find(), "PS1_SPLICE_XML_FINAL_LIST_INVALID");
        require(!suffix.isBlank() && r.path("originalText").asText().endsWith(suffix)
                && r.path("originalText").asText().matches("(?s).*specification version\\s+" + java.util.regex.Pattern.quote(r.path("originalVersion").asText()) + "[\\s,)].*"),
                "PS1_SPLICE_XML_FINAL_LIST_INVALID");
        var expected = new HashSet<String>(); for (var token : r.path("criteria")) require(expected.add(token.asText()), "PS1_SPLICE_XML_FINAL_LIST_INVALID");
        var actual = new HashSet<String>(); for (String token : suffix.trim().replaceFirst("[).\\s]+$", "").split(",", -1)) require(actual.add(token.trim()), "PS1_SPLICE_XML_FINAL_LIST_INVALID");
        require(expected.equals(actual), "PS1_SPLICE_XML_FINAL_LIST_MISMATCH");
        Map<String, String> expressions = new HashMap<>();
        var nucleotides = archive.getElementsByTagName("NucleotideExpression");
        for (int i = 0; i < nucleotides.getLength(); i++) {
            Element n = (Element) nucleotides.item(i);
            String tx = n.getAttribute("sequenceAccessionVersion"), change = n.getAttribute("change");
            if (!tx.matches("NM_[0-9]+\\.[1-9][0-9]*")) continue;
            require(child(n, "Expression").equals(tx + ":" + change)
                    && (expressions.putIfAbsent(tx, change) == null || expressions.get(tx).equals(change)), "PS1_SPLICE_XML_TRANSCRIPT_AMBIGUOUS");
        }
        return Map.copyOf(expressions);
    }

    private static Set<String> diseases(Element assertion) {
        Set<String> found = new HashSet<>(); var traits = assertion.getElementsByTagName("TraitSet");
        for (int i = 0; i < traits.getLength(); i++) {
            var refs = ((Element) traits.item(i)).getElementsByTagName("XRef");
            for (int j = 0; j < refs.getLength(); j++) { Element ref = (Element) refs.item(j);
                if ("MONDO".equals(ref.getAttribute("DB"))) found.add(ref.getAttribute("ID")); }
        }
        return found;
    }

    private static String child(Element element, String name) {
        String result = null;
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && name.equals(e.getTagName())) {
            require(result == null, "PS1_SPLICE_XML_AMBIGUOUS_FIELD"); result = e.getTextContent();
        }
        return result == null ? "" : result;
    }
    private static byte[] read(Path path, String pin, int limit) throws Exception {
        require(pin.matches("[0-9a-f]{64}") && Files.size(path) <= limit, "PS1_SPLICE_SOURCE_PIN_INVALID");
        byte[] bytes; try (var in = Files.newInputStream(path)) { bytes = in.readNBytes(limit + 1); }
        require(bytes.length <= limit && CodingTranscriptMaterialSupply.sha(bytes).equals(pin), "PS1_SPLICE_SOURCE_HASH_MISMATCH"); return bytes;
    }
}
