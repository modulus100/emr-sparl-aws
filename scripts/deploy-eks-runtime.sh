#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${AWS_REGION:=us-east-1}"
: "${EKS_CLUSTER_NAME:=oracle-cdc-eks}"

aws eks update-kubeconfig --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION"

kubectl apply -f "$ROOT_DIR/k8s/emr/namespace.yaml"
kubectl apply -f "$ROOT_DIR/k8s/strimzi/namespace.yaml"

helm repo add strimzi https://strimzi.io/charts/
helm repo update
helm upgrade --install strimzi-kafka-operator strimzi/strimzi-kafka-operator \
  --namespace kafka \
  --create-namespace \
  --set resources.requests.cpu=50m \
  --set resources.requests.memory=256Mi \
  --set resources.limits.cpu=500m \
  --set resources.limits.memory=384Mi

kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n kafka --timeout=300s

kubectl apply -f "$ROOT_DIR/k8s/strimzi/kafka-nodepool.yaml"
kubectl apply -f "$ROOT_DIR/k8s/strimzi/kafka-cluster.yaml"
kubectl wait kafka/oracle-kafka -n kafka --for=condition=Ready=True --timeout=900s
kubectl apply -f "$ROOT_DIR/k8s/strimzi/topic-oracle-cdc.yaml"

if [[ -n "${LOAD_GENERATOR_IMAGE_URI:-}" ]]; then
  sed "s#REPLACE_WITH_ECR_IMAGE_URI#${LOAD_GENERATOR_IMAGE_URI}#g" \
    "$ROOT_DIR/k8s/load-generator/deployment.yaml" | kubectl apply -f -
else
  echo "Skipping load generator deployment. Set LOAD_GENERATOR_IMAGE_URI to deploy it."
fi
