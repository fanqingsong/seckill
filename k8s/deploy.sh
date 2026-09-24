#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v kubectl >/dev/null; then
  echo "kubectl is required" >&2
  exit 1
fi

if ! kubectl get ns istio-system >/dev/null 2>&1; then
  echo "istio-system not found. Install Istio first, for example:" >&2
  echo "  istioctl install --set profile=demo -y" >&2
  exit 1
fi

if ! kubectl -n istio-system get deploy istio-ingressgateway >/dev/null 2>&1; then
  echo "istio-ingressgateway not found in istio-system" >&2
  exit 1
fi

kubectl apply -k "$ROOT/k8s"

echo
echo "Applied SecKill + Istio routing. Wait for Ready:"
echo "  kubectl -n seckill get pods"
echo
echo "Open the UI (same origin as Compose, port 8080):"
echo "  kubectl -n istio-system port-forward svc/istio-ingressgateway 8080:80"
echo
echo "Replay stays off the ingress. Port-forward Event Service when needed:"
echo "  kubectl -n seckill port-forward svc/event-service 8084:8084"
echo "  curl -sS -X POST 'http://localhost:8084/admin/replay?promotionId=...&fromSeq=0'"
