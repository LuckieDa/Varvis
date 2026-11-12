# Offline single-protein worker. stdout is a small strict tab protocol; stderr
# preserves upstream diagnostics. No clinical or negative qualification here.
args <- commandArgs(trailingOnly=TRUE)
stopifnot(length(args) == 4L)
.libPaths(c(args[[1]], .Library))
suppressPackageStartupMessages(library(DECIPHER))
stopifnot(as.character(packageVersion("DECIPHER")) == "3.8.1")
stopifnot(getRversion() == "4.6.1")
seqs <- readAAStringSet(args[[2]])
stopifnot(length(seqs) == 1L, width(seqs)[[1]] <= 6000L,
          names(seqs)[[1]] == args[[3]], args[[4]] %in% c("FALSE", "TRUE"))
cat("BEGIN\t", args[[3]], "\t3.8.1\t", args[[4]], "\n", sep="")
result <- DetectRepeats(seqs, type="tandem", minScore=8, allScores=TRUE,
    maxCopies=1000, maxPeriod=1000, maxFailures=3, maxShifts=5,
    useEmpirical=(args[[4]] == "TRUE"), correctBackground=TRUE,
    processors=1, verbose=FALSE)
stopifnot(all(c("Index", "Begin", "End", "Left", "Right", "Score") %in% colnames(result)))
if (nrow(result) > 0L) {
    for (i in seq_len(nrow(result))) {
        stopifnot(result[i, "Index"] == 1L)
        left <- result[[i, "Left"]]
        right <- result[[i, "Right"]]
        stopifnot(length(left) == length(right), length(left) >= 2L)
        cat("GROUP\t", i, "\t", result[i, "Begin"], "\t", result[i, "End"], "\t",
            format(result[i, "Score"], digits=17, scientific=FALSE, trim=TRUE), "\n", sep="")
        for (j in seq_along(left)) {
            piece <- as.character(subseq(seqs[[1]], start=left[[j]], end=right[[j]]))
            cat("UNIT\t", i, "\t", j, "\t", left[[j]], "\t", right[[j]], "\t", piece, "\n", sep="")
        }
    }
}
cat("END\t", args[[3]], "\t", nrow(result), "\n", sep="")
