variable "project_name" {
  type = string
}

variable "eks_cluster_name" {
  type = string
}

variable "emr_virtual_cluster_name" {
  type = string
}

variable "emr_namespace" {
  type = string
}

variable "emr_job_execution_role_arn" {
  type = string
}

variable "tags" {
  type = map(string)
}
