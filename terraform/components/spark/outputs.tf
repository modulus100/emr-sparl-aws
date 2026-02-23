output "emr_virtual_cluster_id" {
  value = aws_emrcontainers_virtual_cluster.this.id
}

output "emr_virtual_cluster_arn" {
  value = aws_emrcontainers_virtual_cluster.this.arn
}

output "emr_namespace" {
  value = var.emr_namespace
}

output "emr_job_execution_role_arn" {
  value = var.emr_job_execution_role_arn
}
