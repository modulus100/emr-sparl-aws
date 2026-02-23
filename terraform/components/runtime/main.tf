locals {
  load_generator_manifest = yamldecode(replace(
    file("${path.module}/../../../k8s/load-generator/deployment.yaml"),
    "REPLACE_WITH_ECR_IMAGE_URI",
    var.load_generator_image_uri
  ))
}

resource "helm_release" "strimzi" {
  name             = "strimzi-kafka-operator"
  repository       = "https://strimzi.io/charts/"
  chart            = "strimzi-kafka-operator"
  version          = var.strimzi_chart_version
  namespace        = "kafka"
  create_namespace = true
  wait             = true
  timeout          = 600

  set {
    name  = "resources.requests.cpu"
    value = "50m"
  }
  set {
    name  = "resources.requests.memory"
    value = "256Mi"
  }
  set {
    name  = "resources.limits.cpu"
    value = "500m"
  }
  set {
    name  = "resources.limits.memory"
    value = "384Mi"
  }

}

resource "time_sleep" "wait_for_strimzi_crds" {
  create_duration = "45s"

  depends_on = [
    helm_release.strimzi
  ]
}

resource "kubernetes_manifest" "kafka_nodepool" {
  manifest = yamldecode(file("${path.module}/../../../k8s/strimzi/kafka-nodepool.yaml"))

  depends_on = [
    time_sleep.wait_for_strimzi_crds
  ]
}

resource "kubernetes_manifest" "kafka_cluster" {
  manifest = yamldecode(file("${path.module}/../../../k8s/strimzi/kafka-cluster.yaml"))

  depends_on = [
    kubernetes_manifest.kafka_nodepool
  ]
}

resource "kubernetes_manifest" "kafka_topic" {
  manifest = yamldecode(file("${path.module}/../../../k8s/strimzi/topic-oracle-cdc.yaml"))

  depends_on = [
    kubernetes_manifest.kafka_cluster
  ]
}

resource "kubernetes_manifest" "load_generator" {
  count = var.enable_load_generator && var.load_generator_image_uri != "" ? 1 : 0

  manifest = local.load_generator_manifest

  depends_on = [
    kubernetes_manifest.kafka_topic
  ]
}

resource "helm_release" "kube_prometheus_stack" {
  count = var.enable_observability ? 1 : 0

  name             = "kube-prometheus-stack"
  repository       = "https://prometheus-community.github.io/helm-charts"
  chart            = "kube-prometheus-stack"
  version          = var.kube_prometheus_stack_chart_version
  namespace        = "monitoring"
  create_namespace = true
  wait             = true
  timeout          = 600
  values = [
    file("${path.module}/../../../k8s/observability/kube-prometheus-stack-values.yaml")
  ]
}

resource "kubernetes_manifest" "kafka_exporter_deployment" {
  count = var.enable_observability ? 1 : 0

  manifest = yamldecode(file("${path.module}/../../../k8s/observability/kafka-exporter-deployment.yaml"))

  depends_on = [
    kubernetes_manifest.kafka_cluster
  ]
}

resource "kubernetes_manifest" "kafka_exporter_service" {
  count = var.enable_observability ? 1 : 0

  manifest = yamldecode(file("${path.module}/../../../k8s/observability/kafka-exporter-service.yaml"))

  depends_on = [
    kubernetes_manifest.kafka_exporter_deployment
  ]
}

resource "kubernetes_manifest" "kafka_exporter_servicemonitor" {
  count = var.enable_observability ? 1 : 0

  manifest = yamldecode(file("${path.module}/../../../k8s/observability/kafka-exporter-servicemonitor.yaml"))

  depends_on = [
    helm_release.kube_prometheus_stack,
    kubernetes_manifest.kafka_exporter_service
  ]
}
