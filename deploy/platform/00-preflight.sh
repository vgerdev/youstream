#!/usr/bin/env bash
# Read-only checks before the other steps: tools, cluster access, capacity, existing Gateway API CRDs.
. "$(dirname "$0")/lib.sh"
require kubectl helm
[ "$PROVIDER" = doks ] && require doctl

step "Kubernetes"
k version 2>/dev/null | sed -n '2p'
k get nodes -L workload -o wide

step "Requested vs allocatable per node"
k describe nodes | awk '
    /^Name:/ { node = $2 }
    /^Allocated resources:/ { show = 1; print "-- " node; next }
    show && /^Events:/ { show = 0 }
    show && /^ +(cpu|memory) / { print }'

step "Live usage"
k top nodes 2>/dev/null || echo "(metrics-server not installed: kubectl top unavailable)"

step "Workloads already on the cluster"
k get pods -A -o wide --field-selector=status.phase!=Succeeded | awk 'NR == 1 || $1 !~ /^kube-system$/'

step "Gateway API CRDs"
if k get crd gateways.gateway.networking.k8s.io >/dev/null 2>&1; then
    k get crd gateways.gateway.networking.k8s.io -o go-template='version={{ index .metadata.annotations "gateway.networking.k8s.io/bundle-version" }} channel={{ index .metadata.annotations "gateway.networking.k8s.io/channel" }}{{ "\n" }}'
    echo "Already installed (by the provider or earlier). 20-gateway.sh upgrades them in place with server-side apply."
else
    echo "not installed (20-gateway.sh installs them)"
fi

step "Storage classes"
k get storageclass

step "Helm releases"
h list -A

if [ "$PROVIDER" = doks ]; then
    step "DigitalOcean"
    doctl kubernetes cluster node-pool list "$CLUSTER_NAME"
    doctl databases list --format ID,Name,Engine,Version,Region,Status
fi
