#!/usr/bin/env bash
# cert-manager, the Let's Encrypt issuers and the certificate for the Gateway's HTTPS listener.
# Run again after changing ACME_ISSUER: cert-manager then reissues youstream-tls from the new issuer.
# ACME_ISSUER=selfsigned (local clusters, which Let's Encrypt cannot reach) issues a self-signed certificate.
. "$(dirname "$0")/lib.sh"
require kubectl helm
case "$ACME_ISSUER" in
    letsencrypt-staging|letsencrypt-production) [ -n "$ACME_EMAIL" ] || die "ACME_EMAIL is empty" ;;
    selfsigned) ;;
    *) die "unknown ACME_ISSUER $ACME_ISSUER" ;;
esac
k get crd gateways.gateway.networking.k8s.io >/dev/null 2>&1 || die "Gateway API CRDs missing: run 20-gateway.sh first"

step "cert-manager $CERT_MANAGER_VERSION"
h upgrade --install cert-manager oci://quay.io/jetstack/charts/cert-manager --version "$CERT_MANAGER_VERSION" \
    --namespace cert-manager --create-namespace \
    -f "$MANIFESTS/cert-manager-values.yaml" \
    --wait --timeout 5m

step "ClusterIssuers"
k apply -f "$MANIFESTS/cluster-issuer-selfsigned.yaml"
if [ -n "$ACME_EMAIL" ]; then
    render "$MANIFESTS/cluster-issuers.yaml" | k apply -f -
fi

step "Certificate for $DOMAIN from $ACME_ISSUER"
k -n gateway annotate gateway public cert-manager.io/cluster-issuer="$ACME_ISSUER" --overwrite

if [ "${WAIT:-1}" = 1 ]; then
    if [ "$ACME_ISSUER" = selfsigned ]; then
        echo "Waiting for the self-signed certificate..."
    else
        echo "Waiting up to 10 minutes (the DNS record for $DOMAIN must point at the Gateway)..."
    fi
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
