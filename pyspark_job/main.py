from __future__ import annotations

import json
import os
import time
import uuid
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from urllib.parse import urlparse

from pyspark import SparkFiles
from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F


@dataclass(frozen=True)
class AppConfig:
    kafka_bootstrap_servers: str
    kafka_topic: str
    kafka_starting_offsets: str
    kafka_max_offsets_per_trigger: int
    spark_reader_group_id: str
    lag_commit_group_id: str
    checkpoint_location: str
    offsets_state_location: str
    iceberg_catalog: str
    iceberg_database: str
    iceberg_table: str
    iceberg_warehouse: str
    protobuf_descriptor_path: str
    protobuf_message_name: str
    stream_runtime_seconds: int

    @property
    def full_table_name(self) -> str:
        return f"{self.iceberg_catalog}.{self.iceberg_database}.{self.iceberg_table}"

    @staticmethod
    def from_env() -> "AppConfig":
        return AppConfig(
            kafka_bootstrap_servers=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            kafka_topic=os.getenv("KAFKA_TOPIC", "oracle-cdc-events"),
            kafka_starting_offsets=os.getenv("KAFKA_STARTING_OFFSETS", "earliest"),
            kafka_max_offsets_per_trigger=int(os.getenv("KAFKA_MAX_OFFSETS_PER_TRIGGER", "50000")),
            spark_reader_group_id=os.getenv("SPARK_READER_GROUP_ID", "spark-cdc-reader"),
            lag_commit_group_id=os.getenv("LAG_COMMIT_GROUP_ID", "spark-cdc-reader"),
            checkpoint_location=os.getenv(
                "SPARK_CHECKPOINT_LOCATION",
                "s3://replace-me/checkpoints/oracle-cdc-iceberg",
            ),
            offsets_state_location=os.getenv(
                "OFFSETS_STATE_LOCATION",
                "s3://replace-me/checkpoints/oracle-cdc-iceberg/offset-state",
            ),
            iceberg_catalog=os.getenv("ICEBERG_CATALOG", "iceberg"),
            iceberg_database=os.getenv("ICEBERG_DATABASE", "oracle_cdc"),
            iceberg_table=os.getenv("ICEBERG_TABLE", "events"),
            iceberg_warehouse=os.getenv("ICEBERG_WAREHOUSE", "s3://replace-me/warehouse"),
            protobuf_descriptor_path=os.getenv(
                "PROTOBUF_DESCRIPTOR_PATH",
                "s3://replace-me/artifacts/oracle_cdc.pb",
            ),
            protobuf_message_name=os.getenv(
                "PROTOBUF_MESSAGE_NAME",
                "cdc.oracle.v1.OracleCdcEnvelope",
            ),
            stream_runtime_seconds=int(os.getenv("STREAM_RUNTIME_SECONDS", "0")),
        )


def build_spark_session(config: AppConfig) -> SparkSession:
    return (
        SparkSession.builder.appName("oracle-cdc-kafka-to-iceberg")
        .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
        .config(f"spark.sql.catalog.{config.iceberg_catalog}", "org.apache.iceberg.spark.SparkCatalog")
        .config(f"spark.sql.catalog.{config.iceberg_catalog}.type", "hadoop")
        .config(f"spark.sql.catalog.{config.iceberg_catalog}.warehouse", config.iceberg_warehouse)
        .config("spark.sql.session.timeZone", "UTC")
        .getOrCreate()
    )


def create_table_if_needed(spark: SparkSession, config: AppConfig) -> None:
    spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {config.iceberg_catalog}.{config.iceberg_database}")
    spark.sql(
        f"""
        CREATE TABLE IF NOT EXISTS {config.full_table_name} (
            kafka_topic STRING,
            kafka_partition INT,
            kafka_offset BIGINT,
            kafka_timestamp TIMESTAMP,
            op STRING,
            transaction_id STRING,
            source_db STRING,
            source_schema STRING,
            source_table STRING,
            source_scn STRING,
            source_commit_scn BIGINT,
            ts_ms BIGINT,
            event_ts TIMESTAMP,
            iceberg_written_at TIMESTAMP,
            before_json STRING,
            after_json STRING
        ) USING iceberg
        PARTITIONED BY (days(event_ts))
        """
    )


def read_kafka_stream(spark: SparkSession, config: AppConfig) -> DataFrame:
    return (
        spark.readStream.format("kafka")
        .option("kafka.bootstrap.servers", config.kafka_bootstrap_servers)
        .option("subscribe", config.kafka_topic)
        .option("startingOffsets", config.kafka_starting_offsets)
        .option("maxOffsetsPerTrigger", config.kafka_max_offsets_per_trigger)
        .option("failOnDataLoss", "false")
        .load()
    )


def decode_protobuf(df: DataFrame, config: AppConfig) -> DataFrame:
    from pyspark.sql.protobuf.functions import from_protobuf

    decoded = df.select(
        F.col("topic").alias("kafka_topic"),
        F.col("partition").alias("kafka_partition"),
        F.col("offset").alias("kafka_offset"),
        F.col("timestamp").alias("kafka_timestamp"),
        from_protobuf(
            F.col("value"),
            config.protobuf_message_name,
            config.protobuf_descriptor_path,
        ).alias("event"),
    )

    return decoded.select(
        "kafka_topic",
        "kafka_partition",
        "kafka_offset",
        "kafka_timestamp",
        F.col("event.op").alias("op"),
        F.col("event.transaction_id").alias("transaction_id"),
        F.col("event.source.db").alias("source_db"),
        F.col("event.source.schema").alias("source_schema"),
        F.col("event.source.table").alias("source_table"),
        F.col("event.source.scn").alias("source_scn"),
        F.col("event.source.commit_scn").alias("source_commit_scn"),
        F.col("event.ts_ms").alias("ts_ms"),
        F.to_timestamp((F.col("event.ts_ms") / F.lit(1000)).cast("double")).alias("event_ts"),
        F.to_json(F.col("event.before")).alias("before_json"),
        F.to_json(F.col("event.after")).alias("after_json"),
    )


def localize_descriptor_path(spark: SparkSession, descriptor_path: str) -> str:
    # from_protobuf expects a local file path; distribute remote descriptor to executors.
    if descriptor_path.startswith(("s3://", "s3a://", "s3n://", "http://", "https://")):
        spark.sparkContext.addFile(descriptor_path)
        file_name = os.path.basename(urlparse(descriptor_path).path)
        localized = SparkFiles.get(file_name)
        print(f"Using localized protobuf descriptor: {localized}")
        return localized
    return descriptor_path


def commit_offsets_for_lag(batch_df: DataFrame, config: AppConfig) -> None:
    offsets_rows = (
        batch_df.groupBy("kafka_topic", "kafka_partition")
        .agg((F.max("kafka_offset") + F.lit(1)).alias("next_offset"))
        .collect()
    )

    if not offsets_rows:
        return

    offsets_payload: dict[str, dict[str, int]] = {}
    for row in offsets_rows:
        topic = row["kafka_topic"]
        partition = str(row["kafka_partition"])
        next_offset = int(row["next_offset"])
        offsets_payload.setdefault(topic, {})[partition] = next_offset

    jvm = batch_df.sparkSession.sparkContext._jvm
    result = jvm.org.example.kafkatools.OffsetLagCommitter.commitOffsetsAndUpdateLag(
        config.kafka_bootstrap_servers,
        config.lag_commit_group_id,
        json.dumps(offsets_payload),
    )

    print(f"Committed offsets for lag tracking: {result}")
    persist_offsets_to_s3(batch_df, config, offsets_payload, result)


def persist_offsets_to_s3(
    batch_df: DataFrame,
    config: AppConfig,
    offsets_payload: dict[str, dict[str, int]],
    commit_result: str,
) -> None:
    parsed = urlparse(config.offsets_state_location)
    if parsed.scheme not in {"s3", "s3a", "s3n"}:
        raise ValueError(f"Unsupported offsets_state_location scheme: {parsed.scheme}")

    bucket = parsed.netloc
    prefix = parsed.path.lstrip("/").rstrip("/")
    key = f"{prefix}/{int(time.time() * 1000)}-{uuid.uuid4().hex}.json"

    payload = {
        "consumer_group": config.lag_commit_group_id,
        "offsets_json": json.dumps(offsets_payload, sort_keys=True),
        "commit_result_json": commit_result,
        "committed_at": datetime.now(timezone.utc).isoformat(),
    }

    import boto3

    boto3.client("s3").put_object(
        Bucket=bucket,
        Key=key,
        Body=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        ContentType="application/json",
    )
    print(f"Persisted offset-state snapshot: s3://{bucket}/{key}")


def process_batch(batch_df: DataFrame, batch_id: int, config: AppConfig) -> None:
    if batch_df.rdd.isEmpty():
        print(f"Batch {batch_id}: empty")
        return

    # Use one constant write timestamp for the whole micro-batch so Athena can measure end-to-end latency.
    write_started_at = datetime.now(timezone.utc).replace(tzinfo=None)
    batch_to_write = batch_df.withColumn(
        "iceberg_written_at",
        F.lit(write_started_at).cast("timestamp"),
    )

    (
        batch_to_write.write.format("iceberg")
        .mode("append")
        .save(config.full_table_name)
    )

    commit_offsets_for_lag(batch_df, config)
    print(f"Batch {batch_id}: offloaded to {config.full_table_name}")


def main() -> None:
    config = AppConfig.from_env()
    spark = build_spark_session(config)
    spark.sparkContext.setLogLevel("WARN")
    config = replace(
        config,
        protobuf_descriptor_path=localize_descriptor_path(spark, config.protobuf_descriptor_path),
    )

    create_table_if_needed(spark, config)

    raw_stream = read_kafka_stream(spark, config)
    decoded_stream = decode_protobuf(raw_stream, config)

    query = (
        decoded_stream.writeStream.trigger(processingTime="30 seconds")
        .option("checkpointLocation", config.checkpoint_location)
        .foreachBatch(lambda df, batch_id: process_batch(df, batch_id, config))
        .start()
    )

    if config.stream_runtime_seconds > 0:
        terminated = query.awaitTermination(timeout=config.stream_runtime_seconds)
        if not terminated:
            print(
                f"Stopping stream after {config.stream_runtime_seconds}s runtime limit "
                f"for bounded EMR job execution."
            )
            query.stop()
        query.awaitTermination()
    else:
        query.awaitTermination()


if __name__ == "__main__":
    main()
