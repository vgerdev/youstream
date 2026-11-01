#!/usr/bin/env bash
# Builds youstream:local and youstream-openldap:local with Docker and imports them into k3s's containerd, so the
# local values (pullPolicy: Never) use them without a registry. Run again after code changes, then up.sh.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

echo "==> youstream:local (Gradle build inside Docker; the first build takes several minutes)"
docker build -t youstream:local "$ROOT"
echo "==> youstream-openldap:local"
docker build -t youstream-openldap:local "$ROOT/deploy/openldap"

echo "==> importing into k3s"
# exactly this command is password-free with the optional sudoers rule from setup-wsl.sh
docker save youstream:local youstream-openldap:local | sudo k3s ctr -n k8s.io images import -
