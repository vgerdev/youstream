# Helm charts (Phase 10)

| Chart | Release | Runs |
|---|---|---|
| [`youstream`](youstream) | `youstream` | Deployments `web` (×2), `sftp` (LoadBalancer on port 22), `transcode` (media node); CronJob `maintenance`; pre-install/pre-upgrade Job `migrate`; PVC `youstream-media` (RWX, kept on uninstall); HTTPRoute on the shared Gateway; NetworkPolicies; PDB |
| [`openldap`](openldap) | `openldap` | StatefulSet `openldap` (Service `openldap:389`, 1 Gi RWO volume), daily `slapcat` backup to the media volume, NetworkPolicy (only `web` and `sftp` connect) |

Per-cluster values live in [`values/`](values): `youstream-doks.yaml` and `openldap-doks.yaml` for the DigitalOcean cluster,
`youstream-eks.example.yaml` as the starting point for EKS. Everything the charts need from the cluster (Gateway,
storage class, Secrets) comes from [`deploy/platform`](../platform/README.md), which must run first.

## Install / upgrade

The GitHub Actions deploy workflow (Phase 11) runs these with the commit SHA as the image tag. By hand, from the
repository root, against the right context:

```bash
helm upgrade --install youstream deploy/helm/youstream -n youstream \
  -f deploy/helm/values/youstream-doks.yaml \
  --set image.tag=<sha> --set entra.tenantId=<tenant id> --set entra.clientId=<client id> \
  --atomic --wait --timeout 15m

helm upgrade --install openldap deploy/helm/openldap -n youstream \
  -f deploy/helm/values/openldap-doks.yaml --set image.tag=<sha> \
  --atomic --wait --timeout 5m
```

- Install `youstream` first: it creates the media volume that the LDAP backup writes to. Until `openldap` runs, only
  Microsoft sign-in works.
- `migrate` runs before any pod changes; a failed migration fails the release and `--atomic` rolls it back.
- Without the `youstream-entra` secret, deploy with `--set entra.enabled=false`.
- After the first install, point DNS at the SFTP load balancer:
  `kubectl -n youstream get svc youstream-sftp` → `sftp.youstream.paulsdavenport3.com. A <EXTERNAL-IP>`.
- Rollback: `helm -n youstream rollback youstream` (database migrations are not rolled back; they only ever add).

## LDAP users

```bash
deploy/openldap/ldap-user.sh add alice "Alice Example" alice@example.com     # asks for a password
deploy/openldap/ldap-user.sh add bob "Bob Example" bob@example.com --admin
deploy/openldap/ldap-user.sh disable alice
deploy/openldap/ldap-user.sh list
```

The script runs `ldapmodify` inside `openldap-0` through `kubectl exec`; slapd accepts the container user on its local
socket as the directory administrator, so no admin password exists. Users must be in `youstream-users` to sign in
(`ldap.requiredGroup`); `youstream-admins` makes them YouStream administrators. Password rules: at least 12
characters, 10 failed binds lock the account for 15 minutes. LDAP users can also sign in to SFTP with their password.

## Resources

| Pod | Node pool | Requests | Limits |
|---|---|---|---|
| web ×2 | default | 250m, 640 Mi | 1 Gi |
| sftp | default | 100m, 384 Mi | 768 Mi |
| openldap (+ backup) | default | 60m, 96 Mi | 384 Mi |
| transcode | media | 3 CPU, 5 Gi | 6.5 Gi (JVM heap 15 %, the rest for FFmpeg) |
| maintenance (every 15 min), migrate | default | 100m, 256 Mi | 512 Mi |

Database connections: web 5 each, sftp 3, transcode 3, maintenance 2, migrate 2, at most 20 at once.
`transcode` and `sftp` use the Recreate strategy: the media node has room for one transcode pod, and SFTP has one
host key. `web` rolls with one extra pod (maxSurge 1, maxUnavailable 0).

## Client addresses

`web` trusts one proxy hop (`TRUSTED_PROXY_HOPS=1`): Envoy appends the address it received the connection from, which
is the real client because the load balancer keeps source IPs (see the platform README). The SFTP Service uses
`externalTrafficPolicy: Local` for the same reason on DigitalOcean; on EKS, `sftp.proxyProtocol: true` with NLB
PROXY protocol annotations (see `values/youstream-eks.example.yaml`).
