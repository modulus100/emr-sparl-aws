#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v buf >/dev/null 2>&1; then
  echo "buf CLI is required. Install from https://buf.build/docs/installation" >&2
  exit 1
fi

cd "$ROOT_DIR"
mkdir -p kafka-tools/src/generated/java artifacts/descriptors

buf generate
buf build -o artifacts/descriptors/oracle_cdc.pb --exclude-source-info

echo "Generated Java classes in kafka-tools/src/generated/java"
echo "Generated descriptor set in artifacts/descriptors/oracle_cdc.pb"
