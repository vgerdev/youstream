#!/usr/bin/env bash
# Installs or upgrades both releases on the local cluster with the local values and the locally built images.
# The image tag is always "local", so Helm sees no change after images.sh: pass --restart to roll the pods.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
VALUES="$ROOT/deploy/helm/values"

echo "==> youstream"
helm upgrade --install youstream "$ROOT/deploy/helm/youstream" --namespace youstream \
    -f "$VALUES/youstream-local.yaml" --atomic --wait --timeout 15m
echo "==> openldap"
helm upgrade --install openldap "$ROOT/deploy/helm/openldap" --namespace youstream \
    -f "$VALUES/openldap-local.yaml" --atomic --wait --timeout 5m

if [ "${1:-}" = --restart ]; then
    # the tag stays "local", so a rebuilt image needs an explicit restart
    kubectl -n youstream rollout restart deployment
    kubectl -n youstream rollout restart statefulset
    kubectl -n youstream rollout status deployment --timeout=10m
fi

kubectl -n youstream get pods -o wide
cat <<'TXT'

Publish it on localhost:  deploy/local/forward.sh   (keep it running)
Then:  https://youstream.localhost   (self-signed certificate: accept the warning once)
       sftp -P 2222 <user>@localhost
First LDAP user:  deploy/openldap/ldap-user.sh add <user> "<Name>" <email> --admin
TXT
