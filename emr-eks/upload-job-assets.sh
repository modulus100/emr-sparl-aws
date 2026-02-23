#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${ARTIFACTS_BUCKET:?Set ARTIFACTS_BUCKET to your artifacts S3 bucket name}"
: "${AWS_REGION:=us-east-1}"

cd "$ROOT_DIR"

./scripts/generate-protobuf.sh
./gradlew :kafka-tools:jar

aws s3 cp "$ROOT_DIR/pyspark_job/main.py" "s3://${ARTIFACTS_BUCKET}/pyspark_job/main.py" --region "$AWS_REGION"
aws s3 cp "$ROOT_DIR/artifacts/descriptors/oracle_cdc.pb" "s3://${ARTIFACTS_BUCKET}/protobuf/oracle_cdc.pb" --region "$AWS_REGION"
aws s3 cp "$ROOT_DIR/kafka-tools/build/libs/kafka-tools.jar" "s3://${ARTIFACTS_BUCKET}/jars/kafka-tools.jar" --region "$AWS_REGION"

echo "Uploaded job assets to s3://${ARTIFACTS_BUCKET}/"
