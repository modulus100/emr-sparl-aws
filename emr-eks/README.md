# EMR on EKS job scripts

1. Upload assets:
   - `ARTIFACTS_BUCKET=<bucket> ./emr-eks/upload-job-assets.sh`
2. Start streaming job:
   - `EMR_VIRTUAL_CLUSTER_ID=<vc-id> EMR_JOB_ROLE_ARN=<arn> ARTIFACTS_BUCKET=<bucket> RAW_BUCKET=<bucket> WAREHOUSE_BUCKET=<bucket> ./emr-eks/start-job-run.sh`

The job runs as structured streaming with a fixed `30 seconds` trigger, persists streaming checkpoint offsets to S3, commits processed offsets via the Java `OffsetLagCommitter` class, and appends committed-offset snapshots to S3 (`.../checkpoints/oracle-cdc-iceberg/offset-state`).
Default release label is `emr-7.12.0-latest` (override with `EMR_RELEASE_LABEL` if needed).
`STREAM_RUNTIME_SECONDS` defaults to `180` for bounded runs (set `0` for unbounded streaming).
