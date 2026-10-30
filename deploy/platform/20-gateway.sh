#!/usr/bin/env bash
# Gateway API CRDs, Envoy Gateway and the shared public Gateway with its load balancer.
. "$(dirname "$0")/lib.sh"
require kubectl helm

step "Gateway API ($GATEWAY_API_CHANNEL channel) and Envoy Gateway CRDs, $ENVOY_GATEWAY_VERSION"
# helm template + server-side apply: the CRDs are too large for a Helm release (Envoy Gateway's documented way).
# If the provider already manages the Gateway API CRDs, this stops with field-manager conflicts: check them with
# 00-preflight.sh, then set FORCE_CRDS=1 to take them over.
force=()
[ "${FORCE_CRDS:-0}" = 1 ] && force=(--force-conflicts)
helm template eg-crds oci://docker.io/envoyproxy/gateway-crds-helm --version "$ENVOY_GATEWAY_VERSION" \
    --set crds.gatewayAPI.enabled=true \
    --set crds.gatewayAPI.channel="$GATEWAY_API_CHANNEL" \
    --set crds.envoyGateway.enabled=true \
    | k apply --server-side "${force[@]}" -f - >/dev/null
echo "CRDs applied"

step "Envoy Gateway controller"
h upgrade --install eg oci://docker.io/envoyproxy/gateway-helm --version "$ENVOY_GATEWAY_VERSION" \
    --namespace envoy-gateway-system --create-namespace \
    --set crds.enabled=false \
    --wait --timeout 5m

step "Gateway public (namespace gateway)"
k create namespace gateway --dry-run=client -o yaml | k apply -f - >/dev/null
render "$MANIFESTS/envoyproxy-$PROVIDER.yaml" | k apply -f -
render "$MANIFESTS/gateway.yaml" | k apply -f -

step "Waiting for the load balancer address (a few minutes for a new load balancer)"
address=""
for _ in $(seq 1 60); do
    address=$(k -n gateway get gateway public -o jsonpath='{.status.addresses[0].value}' 2>/dev/null || true)
    [ -n "$address" ] && break
    sleep 10
done
[ -n "$address" ] || die "no address yet: check 'kubectl -n envoy-gateway-system get svc' and the Gateway's status"

cat <<TXT

Gateway address: $address
DNS: create   $DOMAIN.  A  $address   (TTL 300)
The HTTPS listener stays unready until 30-cert-manager.sh has issued youstream-tls.
TXT
