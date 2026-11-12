package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"schemaVersion", "reviewProtocolVersion", "reviewId", "packetFingerprint", "packetContentChecksum", "datasetId", "datasetVersion", "hpoOntologyVersion", "generatorVersion", "sourceContext", "terms"})
public class HpoReviewPacket {
    private String schemaVersion;
    private String reviewProtocolVersion;
    private String reviewId;
    private String packetFingerprint;
    private String packetContentChecksum;
    private String datasetId;
    private String datasetVersion;
    private String hpoOntologyVersion;
    private String generatorVersion;
    private String sourceContext;
    private List<HpoReviewTerm> terms = new ArrayList<HpoReviewTerm>();

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getReviewProtocolVersion() { return reviewProtocolVersion; }
    public void setReviewProtocolVersion(String value) { reviewProtocolVersion = value; }
    public String getReviewId() { return reviewId; }
    public void setReviewId(String value) { reviewId = value; }
    public String getPacketFingerprint() { return packetFingerprint; }
    public void setPacketFingerprint(String value) { packetFingerprint = value; }
    public String getPacketContentChecksum() { return packetContentChecksum; }
    public void setPacketContentChecksum(String value) { packetContentChecksum = value; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String value) { datasetId = value; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String value) { datasetVersion = value; }
    public String getHpoOntologyVersion() { return hpoOntologyVersion; }
    public void setHpoOntologyVersion(String value) { hpoOntologyVersion = value; }
    public String getGeneratorVersion() { return generatorVersion; }
    public void setGeneratorVersion(String value) { generatorVersion = value; }
    public String getSourceContext() { return sourceContext; }
    public void setSourceContext(String value) { sourceContext = value; }
    public List<HpoReviewTerm> getTerms() { return terms; }
    public void setTerms(List<HpoReviewTerm> value) { terms = value == null ? new ArrayList<HpoReviewTerm>() : value; }
}
