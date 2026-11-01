# Local cluster in WSL

The production stack (the same platform scripts and Helm charts, Kubernetes 1.34) runs on a single-node
[k3s](https://k3s.io) cluster inside WSL2, at no cost. Use it to try chart, platform and image changes before
DigitalOcean. It runs on your machine:
- k3s and Envoy Gateway
- cert-manager, with a self-signed certificate
- the NFS server behind `youstream-rwx`, which is the same RWX path as production
- OpenLDAP, and the web, SFTP and transcode pods

The database is a separate database (`youstream_local`) on your DigitalOcean development server.

| | Production (DOKS) | Local (WSL) |
|---|---|---|
| Media node | separate `media` pool with taint | the one node, labelled `workload=media`, no taint |
| Load balancers | DO network load balancers | none: `forward.sh` publishes 80/443/2222 on `127.0.0.1` |
| Certificate | Let's Encrypt | self-signed (`ACME_ISSUER=selfsigned`) |
| Address | `youstream.paulsdavenport3.com` | `https://youstream.localhost` (no DNS needed) |
| Images | GHCR, built by GitHub Actions | built with Docker in WSL and imported into k3s |
| Deploys | GitHub Actions | `up.sh` |
| Microsoft sign-in | on | off unless you add the localhost redirect URI |

## 1. WSL (once)

In **Windows** (PowerShell):

```powershell
wsl --install -d Ubuntu-24.04
```

Give WSL enough resources in `%UserProfile%\.wslconfig`, then run `wsl --shutdown`:

```ini
[wsl2]
processors=4
memory=10GB
```

In **Ubuntu**, turn on systemd (k3s and Docker run as services):

```bash
printf '[boot]\nsystemd=true\n' | sudo tee /etc/wsl.conf
```

Then run `wsl --shutdown` in Windows and reopen Ubuntu.

## 2. Tools and k3s (once)

The repository is at `/mnt/c/business/mit/youstream` in WSL.

```bash
cd /mnt/c/business/mit/youstream
deploy/local/setup-wsl.sh
```

This installs:
- `nfs-common`, Docker and BuildKit;
- k3s from the v1.34 channel, without Traefik and without the built-in ServiceLB load balancer;
- Helm v3.19.0, with its checksum verified;
- a kubeconfig at `~/.kube/config` (context `default`);
- a link to the Windows `doctl.exe` if it is on your PATH.

It also allows ports from 80 without root. Open a new WSL shell afterwards so that the Docker group membership
and `KUBECONFIG` take effect.

## 3. Images

```bash
deploy/local/images.sh
```

This builds `youstream:local` and `youstream-openldap:local` and imports them into k3s. Builds from `/mnt/c` are
slower than from the WSL file system. Cloning the repository into `~` is faster, if you prefer.

## 4. Platform (once per cluster)

```bash
cp deploy/platform/cluster.local.env.example deploy/platform/cluster.local.env
# set DB_CLUSTER to your development database (doctl databases list), or DB_HOST + DB_CA_FILE
deploy/local/platform.sh
```

This runs `00-preflight.sh` through `60-secrets.sh` with `PROVIDER=local`:
- **Node:** labels the node.
- **Gateway:** installs Envoy Gateway with a ClusterIP gateway Service.
- **Certificates:** installs cert-manager and issues a self-signed certificate for `youstream.localhost`.
- **Storage:** installs the NFS server on k3s's local-path storage and runs the two-pod check.
- **Database:** creates `youstream_local` and its role on the development server. It asks for the `doadmin` password and doesn't touch the server's trusted sources.
- **Secrets:** creates them. Press Enter at the Entra prompt to skip it.

## 5. Deploy and open

```bash
deploy/local/up.sh                 # both Helm releases with deploy/helm/values/*-local.yaml
deploy/local/forward.sh            # keep running in its own terminal
deploy/openldap/ldap-user.sh add alice "Alice Example" alice@example.com --admin
```

Then:
- **Web:** open `https://youstream.localhost` in a Windows browser and accept the certificate warning once.
- **SFTP:** `sftp -P 2222 alice@localhost`.

After a code change, run `deploy/local/images.sh && deploy/local/up.sh --restart`. The tag stays `local`, so the
pods have to be restarted to pick up the new image.

## Clean up

```bash
helm -n youstream uninstall openldap youstream     # the media claim is kept (helm.sh/resource-policy: keep)
/usr/local/bin/k3s-uninstall.sh                    # removes k3s and every volume
```

The `youstream_local` database stays on the development server until you drop it.

## Not covered locally

- **Load balancers:** DigitalOcean load balancers and their client-IP behaviour. Locally every request comes from 127.0.0.1.
- **Certificates and node pools:** Let's Encrypt, DNS, and the media node pool's taint.
- **GitHub Actions:** the deploy workflow and the deploy account.

Those wait for the DigitalOcean run described in [deploy/README.md](../README.md).
