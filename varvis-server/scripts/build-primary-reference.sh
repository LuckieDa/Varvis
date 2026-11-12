#!/usr/bin/env bash

set -euo pipefail

export PATH="/ucrt64/bin:/usr/bin:${PATH}"

if [ "$#" -ne 4 ]; then
    echo "Usage: $0 <label> <input.fna.gz> <assembly-report.txt> <output.fa>" >&2
    exit 2
fi

label="$1"
input_gz="$2"
assembly_report="$3"
output="$4"
full="${output}.full.fna"

if [ ! -s "$full" ]; then
    echo "DECOMPRESSING ${label}"
    rm -f "${full}.part"
    gzip -dc "$input_gz" > "${full}.part"
    mv "${full}.part" "$full"
fi

if [ ! -s "${full}.fai" ]; then
    echo "INDEXING_FULL ${label}"
    samtools faidx "$full"
fi

echo "EXTRACTING_PRIMARY_CHROMOSOMES ${label}"
rm -f "${output}.part" "${output}.fai"
while IFS=$'\t' read -r accession ucsc_name; do
    samtools faidx "$full" "$accession" |
        awk -v name="$ucsc_name" 'NR == 1 { print ">" name; next } { print }'
done < <(
    awk -F '\t' '!/^#/ && $2 == "assembled-molecule" { print $7 "\t" $10 }' "$assembly_report"
) > "${output}.part"

mv "${output}.part" "$output"
samtools faidx "$output"

sequence_count="$(wc -l < "${output}.fai")"
if [ "$sequence_count" -ne 25 ]; then
    echo "Expected 25 primary sequences for ${label}, found ${sequence_count}" >&2
    exit 1
fi

rm -f "$full" "${full}.fai"
echo "BUILT ${label} sequences=${sequence_count}"
