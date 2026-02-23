#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${AWS_REGION:=us-east-1}"
: "${EKS_CLUSTER_NAME:=oracle-cdc-eks}"
: "${PROM_STACK_VERSION:=67.5.0}"

aws eks update-kubeconfig --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION"

kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --version "$PROM_STACK_VERSION" \
  -f "$ROOT_DIR/k8s/observability/kube-prometheus-stack-values.yaml"

kubectl apply -f "$ROOT_DIR/k8s/observability/spark-driver-podmonitor.yaml"
kubectl apply -f "$ROOT_DIR/k8s/observability/kafka-exporter-servicemonitor.yaml"

echo "Grafana admin user: admin"
echo "Grafana admin password: admin123"
echo "To fetch Grafana endpoint: kubectl get svc -n monitoring kube-prometheus-stack-grafana"
