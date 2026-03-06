# Oracle CDC Kafka -> Iceberg on EKS (EMR on EKS)

This repo now includes an end-to-end scaffold to run everything on one EKS cluster with standard Terraform + Helm resources:

- Kafka on EKS via Strimzi (+ Kafka Exporter for consumer lag metrics)
- Java thread-pool protobuf CDC load generator
- Spark Structured Streaming job on EMR on EKS (PySpark, 30-second trigger)
- Iceberg sink to S3
- Java offset committer callable from PySpark for committed-offset lag tracking
- Open-source observability on EKS via Terraform-managed Helm (`kube-prometheus-stack`)
- Terraform grouped by components (`network`, `eks`, `s3`, `iam`, `spark`, `observability`, `runtime`, `emr_job`)

## 1. Provision AWS infrastructure + EKS runtime

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform apply
```

Required variable:
- `aws_account_id` (already set in example from your current account: `928621976489`)
- For lowest cost single-node setup, keep:
  - `eks_node_instance_types = ["t3a.large"]`
  - `eks_node_capacity_type = "ON_DEMAND"`
  - `eks_desired_size = eks_min_size = eks_max_size = 1`
- Runtime on EKS is also managed by Terraform now:
  - Strimzi operator (Helm release)
  - Kafka cluster/node pool/topic (Kubernetes manifests)
  - Load generator Deployment
  - kube-prometheus-stack (+ embedded PodMonitor/ServiceMonitor)
  - EMR job artifact build/upload (protobuf descriptor + jar + pyspark) and optional `start-job-run`

## 2. Build and push load generator image once

Build/push image:

```bash
IMAGE_URI=<account>.dkr.ecr.<region>.amazonaws.com/oracle-cdc-load-generator:latest \
  ./scripts/build-load-generator-image.sh
```

Deploy generator:

```bash
LOAD_GENERATOR_IMAGE_URI=<same-image-uri> ./scripts/deploy-eks-runtime.sh
```

## 3. Submit EMR job via Terraform (no shell script)

Terraform now builds/uploads assets and can submit the EMR job directly.
Control with tfvars:
- `submit_emr_job_on_apply = true|false`
- `submit_emr_job_token = "run-1"` (change token to force a new submission)

Result file:
- `artifacts/emr-job-run.json`

## 6. Consumer lag and Spark performance in Grafana

Lag/throughput metrics sources:
- Strimzi Kafka Exporter from the Kafka CR (`k8s/strimzi/kafka-cluster.yaml`)
- Spark driver Prometheus endpoint scraped by PodMonitor (`k8s/observability/spark-driver-podmonitor.yaml`)

Use Prometheus/Grafana to query:
- Kafka consumer lag by group from Strimzi exporter
- Spark streaming batch/executor metrics from Spark driver Prometheus endpoint
The PySpark job commits offsets each micro-batch via Java class:
- `org.example.kafkatools.OffsetLagCommitter`
It also writes committed-offset snapshots to S3 under:
- `s3://<raw-bucket>/checkpoints/oracle-cdc-iceberg/offset-state`
Default lag commit group is `spark-cdc-lag` (separate from the Spark Kafka source reader group).
Default bounded job runtime is `stream_runtime_seconds = 180` (set to `0` for unbounded streaming).

### Prometheus queries

Use these queries in Grafana panels:

- Consumer lag:
```promql
sum(kafka_consumergroup_lag{consumergroup="spark-cdc-lag",job="kafka-exporter"})
```

- Committed offset (absolute):
```promql
sum(kafka_consumergroup_current_offset{consumergroup="spark-cdc-lag",job="kafka-exporter"})
```

- Commit throughput (messages/sec):
```promql
sum(rate(kafka_consumergroup_current_offset{consumergroup="spark-cdc-lag",job="kafka-exporter"}[2m]))
```

- Commit throughput (messages/min):
```promql
sum(rate(kafka_consumergroup_current_offset{consumergroup="spark-cdc-lag",job="kafka-exporter"}[2m])) * 60
```

- Producer load (messages/min):
```promql
sum(rate(kafka_topic_partition_current_offset{topic="oracle-cdc-events",job="kafka-exporter"}[2m])) * 60
```

- Manual lag check (`topic_latest - committed`):
```promql
clamp_min(
  sum(kafka_topic_partition_current_offset{topic="oracle-cdc-events",job="kafka-exporter"})
  - sum(kafka_consumergroup_current_offset{consumergroup="spark-cdc-lag",job="kafka-exporter"}),
  0
)
```

## 7. Local PySpark development with uv

```bash
uv sync
./pyspark_job/run_local.sh
```

## 8. New controllable Spring Boot load generator (separate module)

This is an additional generator and does not replace `kafka-tools`.
It runs as a singleton at the API level: one submitted job at a time, with one or more YAML-defined workers inside that job.
Each worker can target a different topic and rate.

Run service:

```bash
./gradlew :spring-load-generator:bootRun
```

Quick local submit + stop (using `local-job.yml`):

```bash
curl -X POST \
  -H "Content-Type: application/x-yaml" \
  --data-binary @local-job.yml \
  http://localhost:8080/api/v1/load-generator/submit

curl http://localhost:8080/api/v1/load-generator/status

curl -X POST http://localhost:8080/api/v1/load-generator/stop
```

Local runtime ports:

- Kafka: `localhost:29092`
- Schema Registry: `http://localhost:8086`
- Kafbat: `http://localhost:8085`

`docker-compose.yaml` wires Kafbat to Schema Registry, so protobuf messages produced by the load generator can be inspected there.

YAML shape:

```yaml
job_name: local-test
bootstrap_servers: localhost:29092
schema_registry_url: http://localhost:8086
duration_seconds: 0
workers:
  - name: local-primary
    topic: oracle-cdc-events
    messages_per_second: 1
    key_prefix: cust-primary-
  - name: local-secondary
    topic: oracle-cdc-events-2
    messages_per_second: 2
    key_prefix: cust-secondary-
source:
  version: 2.6.0.Final
  connector: oracle
  name: oracle-cdc
  db: ORCLCDB
  schema: DEBEZIUM
  table: CUSTOMERS
  snapshot: "false"
  transaction_prefix: tx
  scn_prefix: scn
```

Submit YAML config with curl:

```bash
curl -X POST \
  -H "Content-Type: application/x-yaml" \
  --data-binary @spring-load-generator/examples/job.yaml \
  http://localhost:8080/api/v1/load-generator/submit
```

Submit as multipart file:

```bash
curl -X POST \
  -F "file=@spring-load-generator/examples/job.yaml" \
  http://localhost:8080/api/v1/load-generator/submit-file
```

Update running generator with new YAML config:

```bash
curl -X POST \
  -H "Content-Type: application/x-yaml" \
  --data-binary @spring-load-generator/examples/job.yaml \
  http://localhost:8080/api/v1/load-generator/update
```

Update via multipart file:

```bash
curl -X POST \
  -F "file=@spring-load-generator/examples/job.yaml" \
  http://localhost:8080/api/v1/load-generator/update-file
```

Check singleton generator status:

```bash
curl http://localhost:8080/api/v1/load-generator/status
```

Stop generator:

```bash
curl -X POST http://localhost:8080/api/v1/load-generator/stop
```

## Key files

- Protobuf schema: `proto/oracle_cdc.proto`
- Buf config: `buf.yaml`, `buf.gen.yaml`
- Java generator + committer: `kafka-tools/src/main/java/org/example/kafkatools`
- PySpark stream job: `pyspark_job/main.py`
- EMR job Terraform module: `terraform/components/emr_job/`
- Strimzi manifests: `k8s/strimzi/`
- Terraform components: `terraform/components/`

## Assumptions

- EKS worker nodes run in public subnets for simpler bootstrap.
