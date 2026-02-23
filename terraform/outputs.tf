output "eks_cluster_name" {
  value = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "eks_cluster_oidc_issuer" {
  value = module.eks.cluster_oidc_issuer
}

output "raw_bucket" {
  value = module.s3.raw_bucket_name
}

output "warehouse_bucket" {
  value = module.s3.warehouse_bucket_name
}

output "artifacts_bucket" {
  value = module.s3.artifacts_bucket_name
}

output "emr_virtual_cluster_id" {
  value = module.spark.emr_virtual_cluster_id
}

output "emr_job_execution_role_arn" {
  value = module.iam.emr_job_execution_role_arn
}

output "amp_workspace_id" {
  value = module.observability.amp_workspace_id
}

output "emr_job_run_output_file" {
  value = var.enable_emr_job_module ? module.emr_job[0].job_run_output_file : null
}
