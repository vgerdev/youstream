# Deploying YouStream

| Folder | What | When |
|---|---|---|
| [`platform/`](platform/README.md) | Cluster preparation: media node pool, Gateway, certificates, RWX storage, database, secrets, deploy account | once per cluster, by hand |
| [`helm/`](helm/README.md) | Charts `youstream` and `openldap`, values per cluster | every deploy (GitHub Actions) |
| [`openldap/`](openldap/Dockerfile) | OpenLDAP image and `ldap-user.sh` | image built by GitHub Actions |
| [`../.github/workflows/`](../.github/workflows) | `ci.yml` (build, tests, OpenLDAP check), `deploy.yml` (images + Helm), `backup.yml` (daily media snapshot) | automatic |

## First production deploy

1. **Platform.** Run `deploy/platform/00-preflight.sh` to `60-secrets.sh` (see the [platform README](platform/README.md)),
   including DNS for `youstream.paulsdavenport3.com`, the production certificate and the Entra redirect URI.
2. **Deploy account.** `deploy/platform/70-deployer.sh` creates a ServiceAccount that can change only namespace
   `youstream` (and media snapshots in `nfs`), and writes `github-deployer.kubeconfig`.
3. **GitHub environment.** Repository Settings → Environments → New environment `production`:
   - Deployment branches: *Selected branches* → `master` (so only master can deploy).
   - Required reviewers (optional): you, if each deploy should wait for an approval.
   - Environment secret `KUBECONFIG_B64`: the base64 of the kubeconfig from step 2 (then delete the file).
   - Environment variables:

     | Variable | Value |
     |---|---|
     | `ENTRA_TENANT_ID`, `ENTRA_CLIENT_ID` | from the Entra app registration (Overview page) |
     | `ENTRA_ENABLED` | `false` only if the `youstream-entra` secret does not exist yet (default `true`) |
     | `GHCR_PULL_SECRET` | `true` while the images are private and `ghcr-pull` exists (60-secrets.sh) |
     | `PUBLIC_URL` | optional, default `https://youstream.paulsdavenport3.com` |
     | `SNAPSHOT_CLASS` | optional VolumeSnapshotClass, default `do-block-storage` (`kubectl get volumesnapshotclass`) |

4. **Ship.** Push to `master`. CI runs; when it passes, *Deploy* builds `ghcr.io/vgerdev/youstream:<commit>` and
   `youstream-openldap:<hash of deploy/openldap>`, runs the migrations (Helm hook), upgrades both releases with
   `--atomic` and checks `/api/health` through the public URL. *Actions → Deploy → Run workflow* redeploys by hand.
   - The first run creates the packages in GHCR. They are private if the repository is; either make them public
     (package settings → visibility) or keep `GHCR_PULL_SECRET=true`.
5. **SFTP DNS.** `kubectl -n youstream get svc youstream-sftp` → `sftp.youstream.paulsdavenport3.com. A <EXTERNAL-IP>`.
6. **First LDAP user.** `deploy/openldap/ldap-user.sh add <you> "<Your Name>" <email> --admin`.

## Go-live checklist

- [ ] `https://youstream.paulsdavenport3.com` shows a valid **production** certificate (not "(STAGING)").
- [ ] `http://` redirects to `https://`.
- [ ] Sign in with Microsoft (a tenant account); an account with the `YouStream.Admin` app role is admin.
- [ ] Sign in with an LDAP user; a user outside `youstream-users` (`ldap-user.sh disable`) is refused.
- [ ] Browser upload of a short video: the `transcode` pod on the media node picks it up
      (`kubectl -n youstream logs deploy/youstream-transcode`), the video plays, seeking works.
- [ ] SFTP upload with an LDAP password and with an SSH key (`sftp <user>@sftp.youstream.paulsdavenport3.com`);
      the host key fingerprint matches the Account page.
- [ ] Embed a video on another site.
- [ ] The access log shows real client addresses (`kubectl -n youstream logs deploy/youstream-web | tail`), and
      a burst of bad logins gets 429.
- [ ] `helm -n youstream rollback youstream` once, then deploy again.
- [ ] *Actions → Media snapshot → Run workflow* succeeds; `kubectl -n nfs get volumesnapshot` lists it.
- [ ] The next morning: the `backups/openldap` LDIFs exist (`kubectl -n youstream exec openldap-0 -c backup -- ls /backup/backups/openldap`).

## Backups and restore

| Data | Backup | Restore |
|---|---|---|
| Database | DigitalOcean daily backups with point-in-time recovery | DO console → database → Backups → restore to a new cluster; point `youstream-db` (`DB_URL`) at it and restart the pods |
| Media (and LDAP dumps) | `backup.yml`: daily VolumeSnapshot of the NFS server's volume, 7 days | below |
| OpenLDAP | daily `slapcat` to `backups/openldap` on the media volume, 14 days | below |

The two procedures below are written from the setup, not rehearsed yet: try them once on a test namespace or
cluster before you need them.

**Media.** Restores the whole volume to the snapshot's state (uploads since then are lost):

```bash
kubectl -n youstream scale deploy --all --replicas=0
kubectl -n nfs scale statefulset nfs-nfs-server-provisioner --replicas=0
kubectl -n nfs delete pvc data-nfs-nfs-server-provisioner-0
kubectl -n nfs apply -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: data-nfs-nfs-server-provisioner-0, namespace: nfs}
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: do-block-storage
  resources: {requests: {storage: 100Gi}}
  dataSource: {apiGroup: snapshot.storage.k8s.io, kind: VolumeSnapshot, name: <media-YYYYMMDD-HHMM>}
EOF
kubectl -n nfs scale statefulset nfs-nfs-server-provisioner --replicas=1
# then redeploy (Actions -> Deploy -> Run workflow) to restore the replica counts
```

**OpenLDAP.** From the LDIF dumps (config and data of the same day):

```bash
kubectl -n youstream scale statefulset openldap --replicas=0
kubectl -n youstream run ldap-restore --rm -it --restart=Never --image=ghcr.io/vgerdev/youstream-openldap:<tag> \
  --overrides='{"spec":{"securityContext":{"runAsUser":10002,"fsGroup":10002},"containers":[{"name":"ldap-restore",
  "image":"ghcr.io/vgerdev/youstream-openldap:<tag>","command":["sh"],"stdin":true,"tty":true,
  "volumeMounts":[{"name":"s","mountPath":"/var/lib/openldap"},{"name":"b","mountPath":"/backup"}]}],
  "volumes":[{"name":"s","persistentVolumeClaim":{"claimName":"state-openldap-0"}},
  {"name":"b","persistentVolumeClaim":{"claimName":"youstream-media"}}]}}'
# inside: rm -rf /var/lib/openldap/slapd.d /var/lib/openldap/data && mkdir -p /var/lib/openldap/slapd.d /var/lib/openldap/data
#         slapadd -n 0 -F /var/lib/openldap/slapd.d -l /backup/backups/openldap/<day>-config.ldif
#         slapadd -n 1 -F /var/lib/openldap/slapd.d -l /backup/backups/openldap/<day>-data.ldif
kubectl -n youstream scale statefulset openldap --replicas=1
```

## Moving to another cluster (EKS)

Run the platform scripts there with `PROVIDER=eks` (see the platform README), copy `deploy/helm/values/*-doks.yaml`
to `*-eks.yaml` starting from `youstream-eks.example.yaml`, give the deploy workflow a second environment with that
cluster's kubeconfig, and restore media and LDAP as above. The SFTP host key secret should be copied from the old
cluster (`kubectl get secret youstream-sftp-hostkey -o yaml`), so users don't see a host-key warning.
