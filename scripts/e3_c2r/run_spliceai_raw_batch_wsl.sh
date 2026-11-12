#!/usr/bin/env bash
set -euo pipefail

if (( $# < 2 )); then
  echo "usage: run_spliceai_raw_batch_wsl.sh <python> <probe-script> [probe arguments...]" >&2
  exit 2
fi

python_bin=$1
probe_script=$2
shift 2

converted=()
while (( $# > 0 )); do
  argument=$1
  shift
  case "$argument" in
    --reference-fasta|--batch-request|--output)
      if (( $# == 0 )); then
        echo "missing path after $argument" >&2
        exit 2
      fi
      path=$1
      shift
      if [[ "$path" =~ ^[A-Za-z]:/ ]]; then
        path=$(wslpath -u "$path")
      fi
      converted+=("$argument" "$path")
      ;;
    *)
      converted+=("$argument")
      ;;
  esac
done

exec "$python_bin" "$probe_script" "${converted[@]}"
