#!/usr/bin/env bash
# Application secrets in namespace $NAMESPACE. Existing secrets are kept; ROTATE="name ..." recreates the named ones.
# Generated values are never printed. The database secret comes from 50-database.sh.
#   youstream-jwt            JWT_SECRET (session signing key)
#   youstream-entra          ENTRA_CLIENT_SECRET (prompted: the production client secret from Entra ID)
#   youstream-ldap           LDAP_BIND_PASSWORD (YouStream's read-only service account; OpenLDAP itself is
#                            administered through its local socket, without a password)
#   youstream-sftp-hostkey   host_key (EC P-256, the same on every cluster so clients never see a key change)
#   ghcr-pull                image pull secret, only with GHCR_PULL_SECRET=true
. "$(dirname "$0")/lib.sh"
require kubectl ssh-keygen
ensure_namespace

# true when the secret should be (re)created
wanted() {
    if [[ " ${ROTATE:-} " == *" $1 "* ]] || ! secret_exists "$1"; then
        return 0
    fi
    echo "secret/$1 exists, kept"
    return 1
}

step "Secrets"

if wanted youstream-jwt; then
    apply_env_secret youstream-jwt <<<"JWT_SECRET=$(random_secret)$(random_secret)"
fi

if wanted youstream-entra; then
    read -r -s -p "Entra ID client secret for production (input hidden, empty = skip): " entra
    echo
    if [ -n "$entra" ]; then
        apply_env_secret youstream-entra <<<"ENTRA_CLIENT_SECRET=$entra"
    else
        echo "skipped youstream-entra: deploy with entra.enabled=false until it exists"
    fi
    unset entra
fi

if wanted youstream-ldap; then
    apply_env_secret youstream-ldap <<<"LDAP_BIND_PASSWORD=$(random_secret)"
fi

if wanted youstream-sftp-hostkey; then
    dir=$(mktemp -d)
    ssh-keygen -q -t ecdsa -b 256 -m PEM -N "" -C "" -f "$dir/host_key"
    apply_file_secret youstream-sftp-hostkey "host_key=$dir/host_key"
    echo "SFTP host key: $(ssh-keygen -l -f "$dir/host_key.pub" | cut -d' ' -f2)"
    rm -rf "$dir"
fi

if [ "$GHCR_PULL_SECRET" = true ] && wanted ghcr-pull; then
    echo "GitHub token with only the read:packages scope (classic PAT) for pulling ghcr.io images."
    read -r -p "GitHub user: " gh_user
    read -r -s -p "Token (input hidden): " gh_token
    echo
    [ -n "$gh_user" ] && [ -n "$gh_token" ] || die "user and token are required"
    dir=$(mktemp -d)
    auth=$(printf '%s:%s' "$gh_user" "$gh_token" | base64 | tr -d '\n')
    printf '{"auths":{"ghcr.io":{"auth":"%s"}}}' "$auth" >"$dir/config.json"
    k -n "$NAMESPACE" create secret generic ghcr-pull --type=kubernetes.io/dockerconfigjson \
        --from-file=.dockerconfigjson="$dir/config.json" --dry-run=client -o yaml | k apply -f - >/dev/null
    rm -rf "$dir"
    unset gh_token auth
    echo "secret/ghcr-pull applied"
fi

step "Secrets in $NAMESPACE"
k -n "$NAMESPACE" get secrets
