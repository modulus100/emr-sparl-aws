variable "aws_region" {
  type        = string
  description = "AWS region"
  default     = "us-east-1"
}

variable "aws_account_id" {
  type        = string
  description = "AWS account id for IAM policy scoping"
}

variable "project_name" {
  type        = string
  description = "Project prefix"
  default     = "oracle-cdc"
}

variable "environment" {
  type        = string
  description = "Environment name"
  default     = "dev"
}

variable "extra_tags" {
  type        = map(string)
  description = "Additional tags"
  default     = {}
}

variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR"
  default     = "10.40.0.0/16"
}

variable "public_subnet_count" {
  type        = number
  description = "Number of public subnets"
  default     = 2
}

variable "eks_cluster_name" {
  type        = string
  description = "EKS cluster name"
  default     = "oracle-cdc-eks"
}

variable "kubernetes_version" {
  type        = string
  description = "EKS Kubernetes version"
  default     = "1.31"
}

variable "eks_node_instance_types" {
  type        = list(string)
  description = "Instance types for EKS managed node group"
  default     = ["m6i.xlarge"]
}

variable "eks_node_capacity_type" {
  type        = string
  description = "EKS managed node group capacity type (ON_DEMAND or SPOT)"
  default     = "SPOT"
}

variable "eks_node_disk_size_gb" {
  type        = number
  description = "Root disk size (GiB) for EKS managed node group instances"
  default     = 40
}

variable "eks_desired_size" {
  type        = number
  description = "Desired node count"
  default     = 3
}

variable "eks_min_size" {
  type        = number
  description = "Minimum node count"
  default     = 2
}

variable "eks_max_size" {
  type        = number
  description = "Maximum node count"
  default     = 5
}

variable "emr_virtual_cluster_name" {
  type        = string
  description = "EMR on EKS virtual cluster name"
  default     = "oracle-cdc-emr-vc"
}

variable "emr_namespace" {
  type        = string
  description = "EKS namespace used by EMR on EKS jobs"
  default     = "emr"
}

variable "enable_k8s_runtime" {
  type        = bool
  description = "Deploy Strimzi/Kafka/generator and optional observability via Terraform"
  default     = true
}

variable "enable_load_generator" {
  type        = bool
  description = "Deploy Kafka load generator pod"
  default     = true
}

variable "load_generator_image_uri" {
  type        = string
  description = "ECR image URI for the Kafka load generator"
  default     = ""
}

variable "enable_observability" {
  type        = bool
  description = "Deploy Prometheus + Grafana and monitor manifests"
  default     = true
}

variable "strimzi_chart_version" {
  type        = string
  description = "Strimzi Helm chart version"
  default     = "0.50.1"
}

variable "kube_prometheus_stack_chart_version" {
  type        = string
  description = "kube-prometheus-stack chart version"
  default     = "69.8.2"
}

variable "enable_emr_job_module" {
  type        = bool
  description = "Build/upload EMR job assets with Terraform and optionally submit EMR job run"
  default     = true
}

variable "aws_profile" {
  type        = string
  description = "AWS profile name used by Terraform local-exec for EMR job submission"
  default     = ""
}

variable "emr_release_label" {
  type        = string
  description = "EMR on EKS release label for Spark job runs"
  default     = "emr-7.12.0-latest"
}

variable "emr_job_name" {
  type        = string
  description = "Name for EMR start-job-run submissions"
  default     = "oracle-cdc-kafka-to-iceberg"
}

variable "kafka_bootstrap_servers" {
  type        = string
  description = "Kafka bootstrap server for Spark streaming source"
  default     = "oracle-kafka-kafka-bootstrap.kafka.svc:9092"
}

variable "kafka_topic" {
  type        = string
  description = "Kafka topic for Spark streaming source"
  default     = "oracle-cdc-events"
}

variable "spark_reader_group_id" {
  type        = string
  description = "Kafka consumer group used by Spark stream reader"
  default     = "spark-cdc-reader"
}

variable "lag_commit_group_id" {
  type        = string
  description = "Kafka consumer group used by lag offset committer"
  default     = "spark-cdc-lag"
}

variable "stream_runtime_seconds" {
  type        = number
  description = "Bounded runtime for Spark streaming job; set 0 for unbounded"
  default     = 180
}

variable "submit_emr_job_on_apply" {
  type        = bool
  description = "Submit an EMR job run from Terraform apply"
  default     = false
}

variable "submit_emr_job_token" {
  type        = string
  description = "Change this token to force another EMR job submission in Terraform"
  default     = "initial"
}
