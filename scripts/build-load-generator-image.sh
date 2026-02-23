#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${IMAGE_URI:?Set IMAGE_URI, example: 928621976489.dkr.ecr.us-east-1.amazonaws.com/oracle-cdc-load-generator:latest}"

cd "$ROOT_DIR"
./scripts/generate-protobuf.sh
./gradlew :kafka-tools:installDist

docker buildx build --platform linux/amd64 -f docker/kafka-tools.Dockerfile -t "$IMAGE_URI" --push .

echo "Pushed load generator image: $IMAGE_URI"
