locals {
  entrypoint           = "s3://${var.artifacts_bucket}/pyspark_job/main.py"
  descriptor_path      = "s3://${var.artifacts_bucket}/protobuf/oracle_cdc.pb"
  committer_jar        = "s3://${var.artifacts_bucket}/jars/kafka-tools.jar"
  checkpoint_location  = "s3://${var.raw_bucket}/checkpoints/oracle-cdc-iceberg"
  offsets_state_location = "s3://${var.raw_bucket}/checkpoints/oracle-cdc-iceberg/offset-state"
  stream_runtime_seconds = var.stream_runtime_seconds
  iceberg_warehouse    = "s3://${var.warehouse_bucket}/warehouse"
  submit_output_file   = "${var.repo_root}/artifacts/emr-job-run.json"

  spark_conf = [
    "spark.executor.instances=1",
    "spark.executor.memory=768m",
    "spark.executor.cores=1",
    "spark.driver.memory=768m",
    "spark.dynamicAllocation.enabled=false",
    "spark.kubernetes.driver.request.cores=100m",
    "spark.kubernetes.driver.limit.cores=300m",
    "spark.kubernetes.executor.request.cores=100m",
    "spark.kubernetes.executor.limit.cores=300m",
    "spark.ui.prometheus.enabled=true",
    "spark.executor.processTreeMetrics.enabled=true",
    "spark.sql.catalog.iceberg=org.apache.iceberg.spark.SparkCatalog",
    "spark.sql.catalog.iceberg.type=hadoop",
    "spark.sql.catalog.iceberg.warehouse=${local.iceberg_warehouse}",
    "spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
    "spark.jars=${local.committer_jar}",
    "spark.kubernetes.driverEnv.KAFKA_BOOTSTRAP_SERVERS=${var.kafka_bootstrap_servers}",
    "spark.executorEnv.KAFKA_BOOTSTRAP_SERVERS=${var.kafka_bootstrap_servers}",
    "spark.kubernetes.driverEnv.KAFKA_TOPIC=${var.kafka_topic}",
    "spark.executorEnv.KAFKA_TOPIC=${var.kafka_topic}",
    "spark.kubernetes.driverEnv.SPARK_READER_GROUP_ID=${var.spark_reader_group_id}",
    "spark.executorEnv.SPARK_READER_GROUP_ID=${var.spark_reader_group_id}",
    "spark.kubernetes.driverEnv.LAG_COMMIT_GROUP_ID=${var.lag_commit_group_id}",
    "spark.executorEnv.LAG_COMMIT_GROUP_ID=${var.lag_commit_group_id}",
    "spark.kubernetes.driverEnv.SPARK_CHECKPOINT_LOCATION=${local.checkpoint_location}",
    "spark.executorEnv.SPARK_CHECKPOINT_LOCATION=${local.checkpoint_location}",
    "spark.kubernetes.driverEnv.OFFSETS_STATE_LOCATION=${local.offsets_state_location}",
    "spark.executorEnv.OFFSETS_STATE_LOCATION=${local.offsets_state_location}",
    "spark.kubernetes.driverEnv.ICEBERG_WAREHOUSE=${local.iceberg_warehouse}",
    "spark.executorEnv.ICEBERG_WAREHOUSE=${local.iceberg_warehouse}",
    "spark.kubernetes.driverEnv.PROTOBUF_DESCRIPTOR_PATH=${local.descriptor_path}",
    "spark.executorEnv.PROTOBUF_DESCRIPTOR_PATH=${local.descriptor_path}",
    "spark.kubernetes.driverEnv.STREAM_RUNTIME_SECONDS=${local.stream_runtime_seconds}",
    "spark.executorEnv.STREAM_RUNTIME_SECONDS=${local.stream_runtime_seconds}",
    "spark.kubernetes.driver.label.monitoring=enabled",
    "spark.kubernetes.executor.label.monitoring=enabled"
  ]

  spark_submit_parameters = "${join(" ", [for conf in local.spark_conf : "--conf ${conf}"])} --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.1,org.apache.spark:spark-protobuf_2.12:3.5.1,org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.6.1"

  job_driver = jsonencode({
    sparkSubmitJobDriver = {
      entryPoint            = local.entrypoint
      sparkSubmitParameters = local.spark_submit_parameters
    }
  })

  config_overrides = jsonencode({
    monitoringConfiguration = {
      cloudWatchMonitoringConfiguration = {
        logGroupName        = "/aws/emr-containers/${var.project_name}"
        logStreamNamePrefix = "kafka-iceberg-offload"
      }
    }
  })
}

resource "terraform_data" "build_artifacts" {
  triggers_replace = {
    proto_schema = filemd5("${var.repo_root}/proto/oracle_cdc.proto")
    pyspark_job  = filemd5("${var.repo_root}/pyspark_job/main.py")
    gradle_file  = filemd5("${var.repo_root}/kafka-tools/build.gradle.kts")
    java_toolchain = filemd5("${var.repo_root}/build-logic/src/main/kotlin/buildlogic.java-common-conventions.gradle.kts")
    gen_script   = filemd5("${var.repo_root}/scripts/generate-protobuf.sh")
  }

  provisioner "local-exec" {
    working_dir = var.repo_root
    command     = "./scripts/generate-protobuf.sh && ./gradlew :kafka-tools:jar"
  }
}

resource "aws_s3_object" "pyspark_main" {
  bucket = var.artifacts_bucket
  key    = "pyspark_job/main.py"
  source = "${var.repo_root}/pyspark_job/main.py"
  etag   = filemd5("${var.repo_root}/pyspark_job/main.py")

  depends_on = [terraform_data.build_artifacts]
}

resource "aws_s3_object" "protobuf_descriptor" {
  bucket = var.artifacts_bucket
  key    = "protobuf/oracle_cdc.pb"
  source = "${var.repo_root}/artifacts/descriptors/oracle_cdc.pb"
  etag   = filemd5("${var.repo_root}/artifacts/descriptors/oracle_cdc.pb")

  depends_on = [terraform_data.build_artifacts]
}

resource "aws_s3_object" "kafka_tools_jar" {
  bucket = var.artifacts_bucket
  key    = "jars/kafka-tools.jar"
  source = "${var.repo_root}/kafka-tools/build/libs/kafka-tools.jar"
  etag   = filemd5("${var.repo_root}/kafka-tools/build/libs/kafka-tools.jar")

  depends_on = [terraform_data.build_artifacts]
}

resource "terraform_data" "submit_emr_job" {
  count = var.submit_job_on_apply ? 1 : 0

  triggers_replace = {
    submit_token       = var.submit_job_token
    emr_release_label  = var.emr_release_label
    kafka_bootstrap    = var.kafka_bootstrap_servers
    kafka_topic        = var.kafka_topic
    reader_group       = var.spark_reader_group_id
    lag_commit_group   = var.lag_commit_group_id
    pyspark_etag       = aws_s3_object.pyspark_main.etag
    descriptor_version = coalesce(aws_s3_object.protobuf_descriptor.version_id, "")
    jar_version        = coalesce(aws_s3_object.kafka_tools_jar.version_id, "")
  }

  provisioner "local-exec" {
    working_dir = var.repo_root
    interpreter = ["/bin/bash", "-lc"]
    environment = {
      AWS_PROFILE            = var.aws_profile
      AWS_REGION             = var.aws_region
      EMR_VIRTUAL_CLUSTER_ID = var.emr_virtual_cluster_id
      EMR_JOB_ROLE_ARN       = var.emr_job_role_arn
      EMR_RELEASE_LABEL      = var.emr_release_label
      EMR_JOB_NAME           = var.emr_job_name
      JOB_DRIVER             = local.job_driver
      CONFIG_OVERRIDES       = local.config_overrides
      OUTPUT_FILE            = local.submit_output_file
    }
    command = <<-EOT
      mkdir -p "$(dirname "$OUTPUT_FILE")"
      aws emr-containers start-job-run \
        --region "$AWS_REGION" \
        --name "$EMR_JOB_NAME" \
        --virtual-cluster-id "$EMR_VIRTUAL_CLUSTER_ID" \
        --execution-role-arn "$EMR_JOB_ROLE_ARN" \
        --release-label "$EMR_RELEASE_LABEL" \
        --job-driver "$JOB_DRIVER" \
        --configuration-overrides "$CONFIG_OVERRIDES" \
        > "$OUTPUT_FILE"
      cat "$OUTPUT_FILE"
    EOT
  }

  depends_on = [
    aws_s3_object.pyspark_main,
    aws_s3_object.protobuf_descriptor,
    aws_s3_object.kafka_tools_jar
  ]
}
