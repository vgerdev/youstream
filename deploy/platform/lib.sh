# Shared by the platform scripts: settings, tool checks, kubectl/helm bound to the configured context.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MANIFESTS="$HERE/manifests"

die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

# shellcheck source=versions.env
. "$HERE/versions.env"
CLUSTER_ENV="${CLUSTER_ENV:-cluster.env}"
[ -f "$HERE/$CLUSTER_ENV" ] || die "$HERE/$CLUSTER_ENV is missing: copy cluster.env.example and fill it in"
# shellcheck disable=SC1090
. "$HERE/$CLUSTER_ENV"

case "$PROVIDER" in doks|eks) ;; *) die "PROVIDER must be doks or eks" ;; esac
[ -n "$KUBE_CONTEXT" ] || die "KUBE_CONTEXT is empty in $CLUSTER_ENV"

require() {
    local tool
    for tool in "$@"; do
        command -v "$tool" >/dev/null || die "$tool is not on PATH"
    done
}

k() { kubectl --context "$KUBE_CONTEXT" "$@"; }
h() { helm --kube-context "$KUBE_CONTEXT" "$@"; }

# Prints a manifest with ${NAME} placeholders replaced by the settings above; fails on an unknown placeholder.
render() {
    local file="$1" name text
    text=$(cat "$file")
    for name in DOMAIN SFTP_DOMAIN ACME_EMAIL ACME_ISSUER NAMESPACE NFS_SIZE NFS_BACKING_CLASS \
            DB_HOST DB_PORT DB_ADMIN_USER DB_ADMIN_DATABASE DB_NAME DB_USER POSTGRES_CLIENT_IMAGE; do
        text=${text//"\${$name}"/${!name}}
    done
    if grep -q '\${' <<<"$text"; then
        die "unreplaced placeholder in $file: $(grep -o '\${[A-Z_]*}' <<<"$text" | sort -u | tr '\n' ' ')"
    fi
    printf '%s\n' "$text"
}

# A random secret for passwords and signing keys (URL- and shell-safe).
random_secret() { head -c 36 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }

# Waits for a run-once pod to finish, prints its log and fails if it did not succeed.
wait_pod() {
    local ns="$1" pod="$2" phase="" i
    for i in $(seq 1 120); do
        phase=$(k -n "$ns" get pod "$pod" -o jsonpath='{.status.phase}' 2>/dev/null || true)
        case "$phase" in Succeeded|Failed) break ;; esac
        sleep 5
    done
    k -n "$ns" logs "$pod" || true
    [ "$phase" = Succeeded ] || die "pod $ns/$pod ended as '${phase:-unknown}'"
}

secret_exists() { k -n "$NAMESPACE" get secret "$1" >/dev/null 2>&1; }

# Creates or replaces a Secret from KEY=value lines on stdin, without putting values on a command line.
apply_env_secret() {
    local name="$1" tmp
    tmp=$(mktemp)
    chmod 600 "$tmp"
    cat >"$tmp"
    local rc=0
    k -n "$NAMESPACE" create secret generic "$name" --from-env-file="$tmp" --dry-run=client -o yaml \
        | k apply -f - >/dev/null || rc=$?
    rm -f "$tmp"
    [ "$rc" -eq 0 ] || die "could not apply secret/$name"
    echo "secret/$name applied"
}

ensure_namespace() {
    k create namespace "$NAMESPACE" --dry-run=client -o yaml | k apply -f - >/dev/null
}


# Creates or replaces a Secret from files: apply_file_secret NAME KEY=PATH...
apply_file_secret() {
    local name="$1" args=() pair
    shift
    for pair in "$@"; do
        args+=(--from-file="$pair")
    done
    k -n "$NAMESPACE" create secret generic "$name" "${args[@]}" --dry-run=client -o yaml | k apply -f - >/dev/null
    echo "secret/$name applied"
}

echo "cluster: $KUBE_CONTEXT ($PROVIDER)"
