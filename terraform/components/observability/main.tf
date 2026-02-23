resource "aws_prometheus_workspace" "this" {
  alias = "${var.project_name}-amp"

  tags = merge(var.tags, {
    Component = "observability"
  })
}

resource "aws_cloudwatch_log_group" "spark" {
  name              = "/aws/emr-containers/${var.project_name}"
  retention_in_days = 14

  tags = merge(var.tags, {
    Component = "observability"
  })
}
