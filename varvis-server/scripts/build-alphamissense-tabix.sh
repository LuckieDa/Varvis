#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <official-AlphaMissense_hg38.tsv.gz> <indexed-output.tsv.gz>" >&2
  exit 1
fi

source_file="$1"
output_file="$2"

source_lower=$(printf '%s' "$source_file" | tr '[:upper:]' '[:lower:]')
output_lower=$(printf '%s' "$output_file" | tr '[:upper:]' '[:lower:]')
if [ "$source_lower" = "$output_lower" ]; then
  echo "source and output files must differ" >&2
  exit 1
fi

gzip -dc "$source_file" | bgzip -c > "$output_file"
tabix -f -s 1 -b 2 -e 2 "$output_file"
