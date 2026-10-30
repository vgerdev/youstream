#!/usr/bin/env bash
# The namespace-scoped account GitHub Actions deploys with, and a kubeconfig for it (written next to this script as
# github-deployer.kubeconfig, git-ignored). Store it as the secret KUBECONFIG_B64 of the GitHub environment
# "production"; the commands are printed at the end. Works the same on DOKS and EKS (no cloud credentials).
. "$(dirname "$0")/lib.sh"
require kubectl base64
ensure_namespace
k get namespace nfs >/dev/null 2>&1 || die "namespace nfs missing: run 40-storage.sh first"

step "ServiceAccount github-deployer and its roles"
render "$MANIFESTS/deployer.yaml" | k apply -f -

step "Token"
token=""
for _ in $(seq 1 30); do
    token=$(k -n "$NAMESPACE" get secret github-deployer-token -o jsonpath='{.data.token}' 2>/dev/null | base64 -d || true)
    [ -n "$token" ] && break
    sleep 1
done
[ -n "$token" ] || die "the token controller did not fill secret github-deployer-token"
ca=$(k -n "$NAMESPACE" get secret github-deployer-token -o jsonpath='{.data.ca\.crt}')
server=$(k config view --minify --context "$KUBE_CONTEXT" -o jsonpath='{.clusters[0].cluster.server}')
[ -n "$server" ] || die "cannot read the API server address of $KUBE_CONTEXT"

out="$HERE/github-deployer.kubeconfig"
umask 077
cat >"$out" <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: production
    cluster:
      server: $server
      certificate-authority-data: $ca
users:
  - name: github-deployer
    user:
      token: $token
contexts:
  - name: github-deployer
    context:
      cluster: production
      user: github-deployer
      namespace: $NAMESPACE
current-context: github-deployer
EOF
unset token

step "Checking the kubeconfig"
KUBECONFIG="$out" kubectl auth can-i create deployments -n "$NAMESPACE" | sed 's/^/deployments in '"$NAMESPACE"': /'
KUBECONFIG="$out" kubectl auth can-i create volumesnapshots.snapshot.storage.k8s.io -n nfs | sed 's/^/volumesnapshots in nfs: /'
denied=$(KUBECONFIG="$out" kubectl auth can-i create clusterrolebindings 2>/dev/null || true)
[ "$denied" = no ] || die "the deployer can create clusterrolebindings: check the cluster's RBAC"
echo "clusterrolebindings: no"

cat <<TXT

Kubeconfig: $out
Store it in GitHub (repository Settings -> Environments -> production -> Environment secrets), name KUBECONFIG_B64:
  base64 -w0 "$out"        (paste the output), or with the GitHub CLI:
  base64 -w0 "$out" | gh secret set KUBECONFIG_B64 --env production --repo vgerdev/youstream
Then delete the file. Rotate: kubectl -n $NAMESPACE delete secret github-deployer-token && ./70-deployer.sh
TXT
