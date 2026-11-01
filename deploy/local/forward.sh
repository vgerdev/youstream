#!/usr/bin/env bash
# Publishes the local cluster on 127.0.0.1 with kubectl port-forward: Gateway on 80/443, SFTP on 2222. WSL forwards
# these sockets to Windows, so https://youstream.localhost works in a Windows browser. Runs until Ctrl-C and
# reconnects when a pod restarts.
set -euo pipefail
# k3s's kubectl falls back to /etc/rancher/k3s/k3s.yaml (root only); setup-wsl.sh copies it to ~/.kube/config
[ -n "${KUBECONFIG:-}" ] || export KUBECONFIG="$HOME/.kube/config"

gateway=$(kubectl -n envoy-gateway-system get svc \
    -l gateway.envoyproxy.io/owning-gateway-name=public,gateway.envoyproxy.io/owning-gateway-namespace=gateway \
    -o jsonpath='{.items[0].metadata.name}')
[ -n "$gateway" ] || { echo "no Envoy service for gateway/public: run deploy/local/platform.sh 20" >&2; exit 1; }

forward() {
    while true; do
        kubectl port-forward --address 127.0.0.1 "$@" >/dev/null || true
        sleep 2
    done
}

trap 'kill 0' EXIT INT TERM
forward -n envoy-gateway-system "svc/$gateway" 80:80 443:443 &
forward -n youstream svc/youstream-sftp 2222:2222 &
echo "https://youstream.localhost  and  sftp -P 2222 <user>@localhost  (Ctrl-C to stop)"
wait
