variable "project_name" {
  type = string
}

variable "load_generator_image_uri" {
  type = string
}

variable "enable_load_generator" {
  type = bool
}

variable "enable_observability" {
  type = bool
}

variable "strimzi_chart_version" {
  type = string
}

variable "kube_prometheus_stack_chart_version" {
  type = string
}
