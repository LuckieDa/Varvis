#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 8 || "$5" != "--batch-request" || "$7" != "--output" ]]; then
  echo "gnomAD all-sites WSL wrapper: invalid arguments" >&2
  exit 2
fi

python_executable="$1"
probe_script="$2"
profile_path="$3"
connector_path="$4"
request_path="$(wslpath -u "$6")"
output_path="$(wslpath -u "$8")"

exec "$python_executable" "$probe_script" \
  --profile "$profile_path" \
  --gcs-connector-jar "$connector_path" \
  --batch-request "$request_path" \
  --output "$output_path"
