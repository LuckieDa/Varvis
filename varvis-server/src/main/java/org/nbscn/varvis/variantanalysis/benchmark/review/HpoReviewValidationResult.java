package org.nbscn.varvis.variantanalysis.benchmark.review;

import java.util.ArrayList;
import java.util.List;

public class HpoReviewValidationResult {
    private HpoReviewReadinessStatus status;
    private List<String> errorCodes = new ArrayList<String>();

    public HpoReviewValidationResult(HpoReviewReadinessStatus status) { this.status = status; }
    public HpoReviewReadinessStatus getStatus() { return status; }
    public void setStatus(HpoReviewReadinessStatus value) { status = value; }
    public List<String> getErrorCodes() { return errorCodes; }
    public void setErrorCodes(List<String> value) { errorCodes = value == null ? new ArrayList<String>() : value; }
    public boolean isReady() { return status == HpoReviewReadinessStatus.READY_IDENTICAL_TO_G1 || status == HpoReviewReadinessStatus.READY_WITH_REVIEWED_CHANGES; }
}
