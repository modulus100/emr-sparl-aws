#!/usr/bin/env bash
set -euo pipefail

: "${AWS_REGION:=us-east-1}"
: "${EMR_RELEASE_LABEL:=emr-7.12.0-latest}"
: "${EMR_VIRTUAL_CLUSTER_ID:?Set EMR_VIRTUAL_CLUSTER_ID}"
: "${EMR_JOB_ROLE_ARN:?Set EMR_JOB_ROLE_ARN}"
: "${ARTIFACTS_BUCKET:?Set ARTIFACTS_BUCKET}"
: "${RAW_BUCKET:?Set RAW_BUCKET}"
: "${WAREHOUSE_BUCKET:?Set WAREHOUSE_BUCKET}"
: "${KAFKA_BOOTSTRAP_SERVERS:=oracle-kafka-kafka-bootstrap.kafka.svc:9092}"
: "${KAFKA_TOPIC:=oracle-cdc-events}"
: "${SPARK_READER_GROUP_ID:=spark-cdc-reader}"
: "${LAG_COMMIT_GROUP_ID:=spark-cdc-lag}"
: "${STREAM_RUNTIME_SECONDS:=180}"

ENTRYPOINT="s3://${ARTIFACTS_BUCKET}/pyspark_job/main.py"
DESCRIPTOR_PATH="s3://${ARTIFACTS_BUCKET}/protobuf/oracle_cdc.pb"
COMMITTER_JAR="s3://${ARTIFACTS_BUCKET}/jars/kafka-tools.jar"
CHECKPOINT_LOCATION="s3://${RAW_BUCKET}/checkpoints/oracle-cdc-iceberg"
OFFSETS_STATE_LOCATION="s3://${RAW_BUCKET}/checkpoints/oracle-cdc-iceberg/offset-state"
ICEBERG_WAREHOUSE="s3://${WAREHOUSE_BUCKET}/warehouse"

SPARK_ARGS=$(cat <<EOT
--conf spark.executor.instances=1
--conf spark.executor.memory=768m
--conf spark.executor.cores=1
--conf spark.driver.memory=768m
--conf spark.dynamicAllocation.enabled=false
--conf spark.kubernetes.driver.request.cores=100m
--conf spark.kubernetes.driver.limit.cores=300m
--conf spark.kubernetes.executor.request.cores=100m
--conf spark.kubernetes.executor.limit.cores=300m
--conf spark.ui.prometheus.enabled=true
--conf spark.executor.processTreeMetrics.enabled=true
--conf spark.sql.catalog.iceberg=org.apache.iceberg.spark.SparkCatalog
--conf spark.sql.catalog.iceberg.type=hadoop
--conf spark.sql.catalog.iceberg.warehouse=${ICEBERG_WAREHOUSE}
--conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
--conf spark.jars=${COMMITTER_JAR}
--conf spark.kubernetes.driverEnv.KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}
--conf spark.executorEnv.KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}
--conf spark.kubernetes.driverEnv.KAFKA_TOPIC=${KAFKA_TOPIC}
--conf spark.executorEnv.KAFKA_TOPIC=${KAFKA_TOPIC}
--conf spark.kubernetes.driverEnv.SPARK_READER_GROUP_ID=${SPARK_READER_GROUP_ID}
--conf spark.executorEnv.SPARK_READER_GROUP_ID=${SPARK_READER_GROUP_ID}
--conf spark.kubernetes.driverEnv.LAG_COMMIT_GROUP_ID=${LAG_COMMIT_GROUP_ID}
--conf spark.executorEnv.LAG_COMMIT_GROUP_ID=${LAG_COMMIT_GROUP_ID}
--conf spark.kubernetes.driverEnv.SPARK_CHECKPOINT_LOCATION=${CHECKPOINT_LOCATION}
--conf spark.executorEnv.SPARK_CHECKPOINT_LOCATION=${CHECKPOINT_LOCATION}
--conf spark.kubernetes.driverEnv.OFFSETS_STATE_LOCATION=${OFFSETS_STATE_LOCATION}
--conf spark.executorEnv.OFFSETS_STATE_LOCATION=${OFFSETS_STATE_LOCATION}
--conf spark.kubernetes.driverEnv.ICEBERG_WAREHOUSE=${ICEBERG_WAREHOUSE}
--conf spark.executorEnv.ICEBERG_WAREHOUSE=${ICEBERG_WAREHOUSE}
--conf spark.kubernetes.driverEnv.PROTOBUF_DESCRIPTOR_PATH=${DESCRIPTOR_PATH}
--conf spark.executorEnv.PROTOBUF_DESCRIPTOR_PATH=${DESCRIPTOR_PATH}
--conf spark.kubernetes.driverEnv.STREAM_RUNTIME_SECONDS=${STREAM_RUNTIME_SECONDS}
--conf spark.executorEnv.STREAM_RUNTIME_SECONDS=${STREAM_RUNTIME_SECONDS}
--conf spark.kubernetes.driver.label.monitoring=enabled
--conf spark.kubernetes.executor.label.monitoring=enabled
EOT
)

JOB_DRIVER=$(cat <<EOT
{
  "sparkSubmitJobDriver": {
    "entryPoint": "${ENTRYPOINT}",
    "sparkSubmitParameters": "${SPARK_ARGS//$'\n'/ } --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.1,org.apache.spark:spark-protobuf_2.12:3.5.1,org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.6.1"
  }
}
EOT
)

CONFIG_OVERRIDES=$(cat <<EOT
{
  "monitoringConfiguration": {
    "cloudWatchMonitoringConfiguration": {
      "logGroupName": "/aws/emr-containers/oracle-cdc",
      "logStreamNamePrefix": "kafka-iceberg-offload"
    }
  }
}
EOT
)

aws emr-containers start-job-run \
  --region "$AWS_REGION" \
  --name "oracle-cdc-kafka-to-iceberg" \
  --virtual-cluster-id "$EMR_VIRTUAL_CLUSTER_ID" \
  --execution-role-arn "$EMR_JOB_ROLE_ARN" \
  --release-label "$EMR_RELEASE_LABEL" \
  --job-driver "$JOB_DRIVER" \
  --configuration-overrides "$CONFIG_OVERRIDES"
