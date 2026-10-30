#!/usr/bin/env bash
# The NFS server behind storage class youstream-rwx, then a check that two pods share it as the app's user.
. "$(dirname "$0")/lib.sh"
require kubectl helm
[ -n "$(k get nodes -l workload=media -o name)" ] || die "no media node: run 10-node-pool.sh first"

step "nfs-server-provisioner $NFS_PROVISIONER_CHART_VERSION ($NFS_SIZE on $NFS_BACKING_CLASS)"
helm repo add nfs-ganesha https://kubernetes-sigs.github.io/nfs-ganesha-server-and-external-provisioner/ >/dev/null 2>&1 || true
helm repo update nfs-ganesha >/dev/null
values=$(mktemp)
render "$MANIFESTS/nfs-values.yaml" >"$values"
h upgrade --install nfs nfs-ganesha/nfs-server-provisioner --version "$NFS_PROVISIONER_CHART_VERSION" \
    --namespace nfs --create-namespace -f "$values" --wait --timeout 10m
rm -f "$values"
k get storageclass youstream-rwx

step "Storage check: two pods, UID 10001, rename and cross-node visibility"
ensure_namespace
k -n "$NAMESPACE" delete job storage-check --ignore-not-found >/dev/null
render "$MANIFESTS/storage-check.yaml" | k apply -f -
ok=1
k -n "$NAMESPACE" wait job/storage-check --for=condition=Complete --timeout=5m || ok=0
k -n "$NAMESPACE" get pods -l app=storage-check -o wide
k -n "$NAMESPACE" logs -l app=storage-check --prefix || true

if [ "${KEEP_CHECK:-0}" != 1 ]; then
    pv=$(k -n "$NAMESPACE" get pvc storage-check -o jsonpath='{.spec.volumeName}' 2>/dev/null || true)
    k -n "$NAMESPACE" delete job storage-check --ignore-not-found >/dev/null
    k -n "$NAMESPACE" delete pvc storage-check --ignore-not-found --wait=false >/dev/null
    # youstream-rwx retains volumes; this one is only a test
    [ -n "$pv" ] && k delete pv "$pv" --ignore-not-found --wait=false >/dev/null
    echo "check resources removed"
fi
[ "$ok" = 1 ] || die "storage check failed (KEEP_CHECK=1 keeps the job and claim for a closer look)"
echo "youstream-rwx works"
