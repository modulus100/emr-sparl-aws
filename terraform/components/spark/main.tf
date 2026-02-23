resource "aws_emrcontainers_virtual_cluster" "this" {
  name = var.emr_virtual_cluster_name

  container_provider {
    id   = var.eks_cluster_name
    type = "EKS"

    info {
      eks_info {
        namespace = var.emr_namespace
      }
    }
  }

  tags = merge(var.tags, {
    Component = "spark"
  })
}
