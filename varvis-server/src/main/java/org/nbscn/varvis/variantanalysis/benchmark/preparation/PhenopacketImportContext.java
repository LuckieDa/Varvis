package org.nbscn.varvis.variantanalysis.benchmark.preparation;

public class PhenopacketImportContext {

    private String datasetId;
    private String datasetVersion;
    private String sourceRecordId;
    private String sourceUri;
    private String sourceLicense;
    private String adapterVersion = "phenopacket-adapter-v1";
    private String defaultAssembly = "GRCh38";
    private PhenopacketCaseIdStrategy caseIdStrategy = new DefaultPhenopacketCaseIdStrategy();

    public PhenopacketImportContext() {
    }

    public PhenopacketImportContext(String datasetId, String datasetVersion,
                                    String sourceRecordId, String sourceUri, String sourceLicense) {
        this.datasetId = datasetId;
        this.datasetVersion = datasetVersion;
        this.sourceRecordId = sourceRecordId;
        this.sourceUri = sourceUri;
        this.sourceLicense = sourceLicense;
    }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String value) { datasetId = value; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String value) { datasetVersion = value; }
    public String getSourceRecordId() { return sourceRecordId; }
    public void setSourceRecordId(String value) { sourceRecordId = value; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String value) { sourceUri = value; }
    public String getSourceLicense() { return sourceLicense; }
    public void setSourceLicense(String value) { sourceLicense = value; }
    public String getAdapterVersion() { return adapterVersion; }
    public void setAdapterVersion(String value) { adapterVersion = value; }
    public String getDefaultAssembly() { return defaultAssembly; }
    public void setDefaultAssembly(String value) { defaultAssembly = value; }
    public PhenopacketCaseIdStrategy getCaseIdStrategy() { return caseIdStrategy; }
    public void setCaseIdStrategy(PhenopacketCaseIdStrategy value) { caseIdStrategy = value; }
}
