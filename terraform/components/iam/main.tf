locals {
  oidc_provider_host = replace(var.eks_oidc_issuer_url, "https://", "")
}

data "aws_iam_policy_document" "emr_assume_role" {
  statement {
    actions = [
      "sts:AssumeRole",
      "sts:TagSession"
    ]

    principals {
      type        = "Service"
      identifiers = ["emr-containers.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:emr-containers:${var.aws_region}:${var.account_id}:/virtualclusters/*"]
    }
  }

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = ["arn:aws:iam::${var.account_id}:oidc-provider/${local.oidc_provider_host}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_provider_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "${local.oidc_provider_host}:sub"
      values   = ["system:serviceaccount:${var.emr_namespace}:emr-containers-sa-*"]
    }
  }
}

resource "aws_iam_role" "emr_job_execution" {
  name               = "${var.project_name}-emr-job-execution"
  assume_role_policy = data.aws_iam_policy_document.emr_assume_role.json

  tags = merge(var.tags, {
    Component = "iam"
  })
}

data "aws_iam_policy_document" "emr_job_execution" {
  statement {
    sid = "AllowS3Access"

    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:ListBucket"
    ]

    resources = [
      "arn:aws:s3:::${var.raw_bucket}",
      "arn:aws:s3:::${var.raw_bucket}/*",
      "arn:aws:s3:::${var.warehouse_bucket}",
      "arn:aws:s3:::${var.warehouse_bucket}/*",
      "arn:aws:s3:::${var.artifacts_bucket}",
      "arn:aws:s3:::${var.artifacts_bucket}/*"
    ]
  }

  statement {
    sid = "AllowCloudWatchLogs"

    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogGroups",
      "logs:DescribeLogStreams"
    ]

    resources = [
      "arn:aws:logs:${var.aws_region}:${var.account_id}:*"
    ]
  }

  statement {
    sid = "AllowCloudWatchMetrics"

    actions = [
      "cloudwatch:PutMetricData"
    ]

    resources = ["*"]
  }
}

resource "aws_iam_policy" "emr_job_execution" {
  name   = "${var.project_name}-emr-job-execution"
  policy = data.aws_iam_policy_document.emr_job_execution.json
}

resource "aws_iam_role_policy_attachment" "emr_job_execution" {
  role       = aws_iam_role.emr_job_execution.name
  policy_arn = aws_iam_policy.emr_job_execution.arn
}
