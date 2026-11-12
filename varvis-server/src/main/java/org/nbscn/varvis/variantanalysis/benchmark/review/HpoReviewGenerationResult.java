package org.nbscn.varvis.variantanalysis.benchmark.review;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class HpoReviewGenerationResult {
    private final List<HpoReviewPacket> packets;
    private final Path reviewerDirectory;
    private final Path internalDirectory;

    public HpoReviewGenerationResult(List<HpoReviewPacket> packets, Path reviewerDirectory, Path internalDirectory) {
        this.packets = new ArrayList<HpoReviewPacket>(packets); this.reviewerDirectory = reviewerDirectory; this.internalDirectory = internalDirectory;
    }
    public List<HpoReviewPacket> getPackets() { return new ArrayList<HpoReviewPacket>(packets); }
    public Path getReviewerDirectory() { return reviewerDirectory; }
    public Path getInternalDirectory() { return internalDirectory; }
}
