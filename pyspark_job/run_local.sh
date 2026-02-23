#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${KAFKA_BOOTSTRAP_SERVERS:=localhost:9092}"
: "${KAFKA_TOPIC:=oracle-cdc-events}"
: "${ICEBERG_WAREHOUSE:=s3://replace-me/warehouse}"
: "${SPARK_CHECKPOINT_LOCATION:=s3://replace-me/checkpoints/oracle-cdc-iceberg}"
: "${PROTOBUF_DESCRIPTOR_PATH:=${ROOT_DIR}/artifacts/descriptors/oracle_cdc.pb}"

cd "$ROOT_DIR"

uv run spark-submit \
  --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.1,org.apache.spark:spark-protobuf_2.12:3.5.1,org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.6.1 \
  --jars kafka-tools/build/libs/kafka-tools.jar \
  pyspark_job/main.py
