variable "aws_region" {
  type = string
}

variable "aws_profile" {
  type = string
}

variable "project_name" {
  type = string
}

variable "repo_root" {
  type = string
}

variable "artifacts_bucket" {
  type = string
}

variable "raw_bucket" {
  type = string
}

variable "warehouse_bucket" {
  type = string
}

variable "emr_virtual_cluster_id" {
  type = string
}

variable "emr_job_role_arn" {
  type = string
}

variable "emr_release_label" {
  type = string
}

variable "emr_job_name" {
  type = string
}

variable "kafka_bootstrap_servers" {
  type = string
}

variable "kafka_topic" {
  type = string
}

variable "spark_reader_group_id" {
  type = string
}

variable "lag_commit_group_id" {
  type = string
}

variable "stream_runtime_seconds" {
  type = number
}

variable "submit_job_on_apply" {
  type = bool
}

variable "submit_job_token" {
  type = string
}
