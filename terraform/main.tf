locals {
  common_tags = merge(
    {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    },
    var.extra_tags
  )
}

module "network" {
  source = "./components/network"

  project_name = var.project_name
  vpc_cidr     = var.vpc_cidr
  subnet_count = var.public_subnet_count
  tags         = local.common_tags
}

module "eks" {
  source = "./components/eks"

  project_name        = var.project_name
  kubernetes_version  = var.kubernetes_version
  cluster_name        = var.eks_cluster_name
  subnet_ids          = module.network.public_subnet_ids
  vpc_id              = module.network.vpc_id
  node_instance_types = var.eks_node_instance_types
  node_capacity_type  = var.eks_node_capacity_type
  node_disk_size_gb   = var.eks_node_disk_size_gb
  desired_size        = var.eks_desired_size
  min_size            = var.eks_min_size
  max_size            = var.eks_max_size
  tags                = local.common_tags
}

module "s3" {
  source = "./components/s3"

  project_name  = var.project_name
  bucket_suffix = var.aws_account_id
  tags          = local.common_tags
}

module "iam" {
  source = "./components/iam"

  project_name     = var.project_name
  aws_region       = var.aws_region
  account_id       = var.aws_account_id
  eks_oidc_issuer_url = module.eks.cluster_oidc_issuer
  emr_namespace    = var.emr_namespace
  raw_bucket       = module.s3.raw_bucket_name
  warehouse_bucket = module.s3.warehouse_bucket_name
  artifacts_bucket = module.s3.artifacts_bucket_name
  tags             = local.common_tags
}

module "spark" {
  source = "./components/spark"

  project_name               = var.project_name
  eks_cluster_name           = module.eks.cluster_name
  emr_virtual_cluster_name   = var.emr_virtual_cluster_name
  emr_namespace              = var.emr_namespace
  emr_job_execution_role_arn = module.iam.emr_job_execution_role_arn
  tags                       = local.common_tags
}

module "observability" {
  source = "./components/observability"

  project_name = var.project_name
  tags         = local.common_tags
}

module "runtime" {
  count  = var.enable_k8s_runtime ? 1 : 0
  source = "./components/runtime"

  project_name                        = var.project_name
  load_generator_image_uri            = var.load_generator_image_uri
  enable_load_generator               = var.enable_load_generator
  enable_observability                = var.enable_observability
  strimzi_chart_version               = var.strimzi_chart_version
  kube_prometheus_stack_chart_version = var.kube_prometheus_stack_chart_version

  depends_on = [
    module.eks
  ]
}

module "emr_job" {
  count  = var.enable_emr_job_module ? 1 : 0
  source = "./components/emr_job"

  aws_region              = var.aws_region
  aws_profile             = var.aws_profile
  project_name            = var.project_name
  repo_root               = abspath("${path.root}/..")
  artifacts_bucket        = module.s3.artifacts_bucket_name
  raw_bucket              = module.s3.raw_bucket_name
  warehouse_bucket        = module.s3.warehouse_bucket_name
  emr_virtual_cluster_id  = module.spark.emr_virtual_cluster_id
  emr_job_role_arn        = module.iam.emr_job_execution_role_arn
  emr_release_label       = var.emr_release_label
  emr_job_name            = var.emr_job_name
  kafka_bootstrap_servers = var.kafka_bootstrap_servers
  kafka_topic             = var.kafka_topic
  spark_reader_group_id   = var.spark_reader_group_id
  lag_commit_group_id     = var.lag_commit_group_id
  stream_runtime_seconds  = var.stream_runtime_seconds
  submit_job_on_apply     = var.submit_emr_job_on_apply
  submit_job_token        = var.submit_emr_job_token

  depends_on = [
    module.spark,
    module.runtime
  ]
}
