#!/usr/bin/env bash
# The media node pool: one 4 vCPU / 8 GB node, labelled and tainted so only FFmpeg (and the NFS server) run there.
. "$(dirname "$0")/lib.sh"
require kubectl

if [ "$PROVIDER" = doks ]; then
    require doctl
    [ -n "$CLUSTER_NAME" ] || die "CLUSTER_NAME is empty"
    if doctl kubernetes cluster node-pool get "$CLUSTER_NAME" media >/dev/null 2>&1; then
        step "Node pool media exists"
    else
        step "Creating node pool media ($MEDIA_NODE_SIZE, about \$48/month)"
        doctl kubernetes cluster node-pool create "$CLUSTER_NAME" \
            --name media --size "$MEDIA_NODE_SIZE" --count 1 \
            --label workload=media --taint workload=media:NoSchedule
    fi
else
    cat <<'TXT'
EKS: create a managed node group yourself (eksctl, Terraform or the console), e.g.
  instance type c7i.xlarge, desired/min/max 1,
  label  workload=media
  taint  workload=media:NoSchedule
then run this script again to check it.
TXT
fi

step "Media nodes"
k get nodes -l workload=media -o wide
[ -n "$(k get nodes -l workload=media -o name)" ] || die "no node with label workload=media yet (it can take a few minutes to join)"
k get nodes -l workload=media -o custom-columns='NAME:.metadata.name,TAINTS:.spec.taints[*].key,CPU:.status.allocatable.cpu,MEMORY:.status.allocatable.memory'
