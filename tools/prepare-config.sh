#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    printf 'Usage: %s SOURCE.conf [OUTPUT.conf]\n' "$0" >&2
    exit 2
fi

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_path="$(realpath "$1")"
if [[ ! -f "$source_path" ]]; then
    printf 'Config not found: %s\n' "$source_path" >&2
    exit 1
fi

if [[ $# -eq 2 ]]; then
    output_path="$(realpath -m "$2")"
else
    base_name="${source_path%.*}"
    output_path="${base_name}.messenger.conf"
fi
if [[ "$source_path" == "$output_path" ]]; then
    printf 'Output must differ from source\n' >&2
    exit 1
fi

umask 077
cd "$project_dir/build-src/wireproxy-awg-1.0.16"
go run -buildvcs=false ./cmd/config-adapter -in "$source_path" -out "$output_path"
