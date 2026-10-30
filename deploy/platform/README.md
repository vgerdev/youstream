# Cluster platform (Phase 9)

These scripts prepare a Kubernetes cluster for the YouStream Helm chart (Phase 10). Run them once per cluster, in
order. Every script can be run again: it checks what already exists and changes only what is missing or outdated.
The design is in [PLAN.md §16](../../PLAN.md).

| Script | Creates | Manual part |
|---|---|---|
| `00-preflight.sh` | nothing (read-only): nodes, requested vs allocatable resources, workloads, existing Gateway API CRDs, storage classes, Helm releases | read the capacity numbers |
| `10-node-pool.sh` | node pool `media` (1 × `s-4vcpu-8gb`, label and taint `workload=media`) | EKS: create the node group yourself |
| `20-gateway.sh` | Gateway API + Envoy Gateway CRDs, Envoy Gateway, Gateway `gateway/public` with its load balancer, http→https redirect | DNS record for the Gateway address |
| `30-cert-manager.sh` | cert-manager, ClusterIssuers `letsencrypt-staging` / `letsencrypt-production`, certificate `gateway/youstream-tls` | switch `ACME_ISSUER` to production |
| `40-storage.sh` | NFS server (namespace `nfs`, on the media node) and storage class `youstream-rwx`, then a two-pod check | – |
| `50-database.sh` | trusted source, secrets `youstream-db-ca` and `youstream-db`, database and role `youstream_prod` | type the `doadmin` password |
| `60-secrets.sh` | secrets `youstream-jwt`, `youstream-entra`, `youstream-ldap`, `youstream-sftp-hostkey`, optionally `ghcr-pull` | type the Entra client secret |

## Before you start

- **Shell:** bash (Cygwin, Git Bash, WSL, Linux or macOS) with `kubectl`, `helm` 3.8+ (OCI charts) and
  `ssh-keygen` on `PATH`. On DigitalOcean you also need `doctl`, signed in (`doctl auth init`).
- **Settings:** copy `cluster.env.example` to `cluster.env` and fill it in. `cluster.env` is git-ignored. For more
  than one cluster keep one file each and pick it with `CLUSTER_ENV=cluster.eks.env ./20-gateway.sh`.
- **Context:** every command runs against `KUBE_CONTEXT` explicitly, never against whatever context is current.
- **Versions:** chart versions are pinned in `versions.env`.

## Steps

```bash
cd deploy/platform
cp cluster.env.example cluster.env      # then edit it
./00-preflight.sh
```

**Capacity.** The default pool has 3 × 1 vCPU / 2 GB nodes (about 920m CPU and 1.5 GB allocatable each). The
platform adds Envoy Gateway (controller + one proxy), cert-manager (three pods) and later two `web` pods, `sftp`
and `openldap`. Together that is about 1 CPU and 2.5 GB of requests. Compare that with the free requests that
00-preflight.sh prints per node. If they don't fit, there are two ways out: resize the default pool to
`s-2vcpu-4gb` (about +$24/month for three nodes), or let `sftp` and `openldap` tolerate the media taint (Phase 10
values). The NFS server already runs on the media node.

```bash
./10-node-pool.sh       # about $48/month; the node joins in a few minutes
./20-gateway.sh         # prints the load balancer address
```

**DNS.** Create `youstream.paulsdavenport3.com. A <gateway address>` (TTL 300) with your DNS provider.
`sftp.youstream.paulsdavenport3.com` gets its own load balancer from the Helm chart in Phase 10; its record comes
then. Wait until the name resolves, e.g. `nslookup youstream.paulsdavenport3.com`, before the next step.

```bash
./30-cert-manager.sh    # staging certificate first (ACME_ISSUER=letsencrypt-staging)
```

Once the staging certificate is `Ready`, set `ACME_ISSUER=letsencrypt-production` in `cluster.env` and run
`./30-cert-manager.sh` again; cert-manager reissues the certificate from production.

```bash
./40-storage.sh         # NFS server + check: prints "OK pod-0 on <node> ..." for both pods
./50-database.sh        # asks for the doadmin password
./60-secrets.sh         # asks for the Entra client secret
```

**Database firewall.** If the managed database has no trusted sources yet, it accepts connections from
anywhere. Adding the cluster then shuts out every other client, your development machine included.
50-database.sh asks before it does that. Add your own IP as well if you develop against the same database
server:

```bash
doctl databases firewalls append <db-id> --rule ip_addr:<your public IP>
```

**Connection limit.** The smallest managed PostgreSQL plans allow only about 20 to 25 connections. Phase 10
sets small pools per role (`DB_POOL_SIZE`), because 2 × web, sftp, transcode and maintenance at the default of
10 each would exceed that.

**Microsoft Entra ID.** In the app registration (Azure portal → Microsoft Entra ID → App registrations →
YouStream):

- Authentication → Web → add the redirect URI
  `https://youstream.paulsdavenport3.com/api/auth/microsoft/callback`.
- Certificates & secrets → New client secret, named for production. Paste it when 60-secrets.sh asks.
  The development secret stays in `conf/local.conf`.

## Client addresses

The app limits requests by client IP, so the cluster has to pass real client addresses through:

- **DigitalOcean.** The Gateway's Service gets a network load balancer (the DOKS default) with
  `externalTrafficPolicy: Local`. It keeps the client source IP, Envoy appends it to `X-Forwarded-For`, and the app
  trusts one hop (`TRUSTED_PROXY_HOPS=1`). DO network load balancers don't support PROXY protocol and don't need it.
  The SFTP Service in Phase 10 works the same way, so `SFTP_PROXY_PROTOCOL` stays off on DOKS.
- **EKS.** The NLB runs in IP-target mode with PROXY protocol v2. A `ClientTrafficPolicy` makes Envoy read it, and
  `SFTP_PROXY_PROTOCOL=true` does the same for SFTP.

## EKS

- **Settings:** set `PROVIDER=eks` and `NFS_BACKING_CLASS=gp3`. The DigitalOcean-only settings (`CLUSTER_NAME`,
  `DB_CLUSTER`) are then ignored, so fill in `DB_HOST` and `DB_CA_FILE` yourself (the RDS CA bundle).
- **Before you start:** install the AWS Load Balancer Controller and an EBS CSI `gp3` storage class.
- **Database access:** the security groups take the place of DigitalOcean's trusted sources.
- **Media volume:** Amazon EFS can replace the NFS server (`efs-sc` as `persistence.storageClass` in the chart).
  Skip 40-storage.sh then.

## Upgrades

To upgrade, raise the version in `versions.env` and run the script again. Read the release notes first:

- **Envoy Gateway:** upgrade the Gateway API CRDs before the controller. 20-gateway.sh applies them first.
- **cert-manager:** keeps its CRDs on uninstall (`crds.keep`).
