output "amp_workspace_id" {
  value = aws_prometheus_workspace.this.id
}

output "amp_workspace_arn" {
  value = aws_prometheus_workspace.this.arn
}

output "amp_workspace_prometheus_endpoint" {
  value = aws_prometheus_workspace.this.prometheus_endpoint
}

output "spark_log_group_name" {
  value = aws_cloudwatch_log_group.spark.name
}
