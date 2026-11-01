#!/usr/bin/env bash
# Runs the platform scripts against the local cluster with deploy/platform/cluster.local.env:
# node label, Envoy Gateway, cert-manager (self-signed), NFS storage + check, database, secrets.
#   deploy/local/platform.sh            all steps
#   deploy/local/platform.sh 40 50      only these steps
set -euo pipefail
PLATFORM=$(cd "$(dirname "$0")/../platform" && pwd)
[ -f "$PLATFORM/cluster.local.env" ] \
    || { echo "copy deploy/platform/cluster.local.env.example to cluster.local.env and fill in the database" >&2; exit 1; }
export CLUSTER_ENV=cluster.local.env

steps=("$@")
[ ${#steps[@]} -gt 0 ] || steps=(00 10 20 30 40 50 60)
for n in "${steps[@]}"; do
    script=$(ls "$PLATFORM/$n"-*.sh)
    echo
    echo "################ $(basename "$script")"
    "$script"
done
