variable "project_name" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "account_id" {
  type = string
}

variable "eks_oidc_issuer_url" {
  type = string
}

variable "emr_namespace" {
  type = string
}

variable "raw_bucket" {
  type = string
}

variable "warehouse_bucket" {
  type = string
}

variable "artifacts_bucket" {
  type = string
}

variable "tags" {
  type = map(string)
}
