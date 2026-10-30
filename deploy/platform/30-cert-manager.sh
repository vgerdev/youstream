#!/usr/bin/env bash
# cert-manager, the Let's Encrypt issuers and the certificate for the Gateway's HTTPS listener.
# Run again after changing ACME_ISSUER: cert-manager then reissues youstream-tls from the new issuer.
. "$(dirname "$0")/lib.sh"
require kubectl helm
[ -n "$ACME_EMAIL" ] || die "ACME_EMAIL is empty"
case "$ACME_ISSUER" in letsencrypt-staging|letsencrypt-production) ;; *) die "unknown ACME_ISSUER $ACME_ISSUER" ;; esac
k get crd gateways.gateway.networking.k8s.io >/dev/null 2>&1 || die "Gateway API CRDs missing: run 20-gateway.sh first"

step "cert-manager $CERT_MANAGER_VERSION"
h upgrade --install cert-manager oci://quay.io/jetstack/charts/cert-manager --version "$CERT_MANAGER_VERSION" \
    --namespace cert-manager --create-namespace \
    -f "$MANIFESTS/cert-manager-values.yaml" \
    --wait --timeout 5m

step "ClusterIssuers"
render "$MANIFESTS/cluster-issuers.yaml" | k apply -f -

step "Certificate for $DOMAIN from $ACME_ISSUER"
k -n gateway annotate gateway public cert-manager.io/cluster-issuer="$ACME_ISSUER" --overwrite

if [ "${WAIT:-1}" = 1 ]; then
    echo "Waiting up to 10 minutes (the DNS record for $DOMAIN must point at the Gateway)..."
    for _ in $(seq 1 60); do
        k -n gateway get certificate youstream-tls >/dev/null 2>&1 && break
        sleep 5
    done
    if ! k -n gateway wait certificate/youstream-tls --for=condition=Ready --timeout=10m; then
        k -n gateway describe certificate youstream-tls | tail -15 || true
        k -n gateway get challenges.acme.cert-manager.io -o wide || true
        die "certificate not ready; check DNS, then: kubectl -n gateway describe challenges"
    fi
fi
k -n gateway get certificate youstream-tls -o wide
