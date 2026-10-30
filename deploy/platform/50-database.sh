#!/usr/bin/env bash
# The production database: trusted source for the cluster (DOKS), CA certificate, database and role, and
# secret youstream-db with DB_URL / DB_USER / DB_PASSWORD for the app. Prompts once for the admin password.
. "$(dirname "$0")/lib.sh"
require kubectl
ensure_namespace

if [ "$PROVIDER" = doks ]; then
    require doctl
    [ -n "$DB_CLUSTER" ] || die "DB_CLUSTER is empty"
    [ -n "$CLUSTER_NAME" ] || die "CLUSTER_NAME is empty"
    db_id=$(doctl databases get "$DB_CLUSTER" --format ID --no-header)
    cluster_id=$(doctl kubernetes cluster get "$CLUSTER_NAME" --format ID --no-header)
    [ -n "$DB_HOST" ] || DB_HOST=$(doctl databases connection "$db_id" --format Host --no-header)

    step "Trusted sources of the database"
    rules=$(doctl databases firewalls list "$db_id" --format Type,Value --no-header || true)
    echo "${rules:-(none: open to any address)}"
    if grep -q "$cluster_id" <<<"$rules"; then
        echo "the cluster is already a trusted source"
    else
        if [ -z "$rules" ]; then
            cat <<'TXT'
The database accepts connections from anywhere today. Adding the cluster makes it accept ONLY trusted
sources, which locks out every other client (your development machine included) unless you add it too:
  doctl databases firewalls append <db-id> --rule ip_addr:<your public IP>
TXT
            read -r -p "Add the cluster as a trusted source now? [y/N] " answer
            [ "$answer" = y ] || die "stopped; nothing changed"
        fi
        doctl databases firewalls append "$db_id" --rule "k8s:$cluster_id"
    fi

    step "CA certificate"
    if [ -z "$DB_CA_FILE" ]; then
        DB_CA_FILE=$(mktemp)
        ca_download=$DB_CA_FILE
        doctl databases get-ca "$db_id" --format Certificate --no-header | base64 -d >"$DB_CA_FILE"
    fi
fi
[ -n "$DB_HOST" ] || die "DB_HOST is empty"
[ -s "$DB_CA_FILE" ] || die "DB_CA_FILE is empty or missing"
grep -q 'BEGIN CERTIFICATE' "$DB_CA_FILE" || die "$DB_CA_FILE is not a PEM certificate"
apply_file_secret youstream-db-ca "ca.crt=$DB_CA_FILE"
[ -n "${ca_download:-}" ] && rm -f "$ca_download"

step "Secret youstream-db"
if secret_exists youstream-db; then
    password=$(k -n "$NAMESPACE" get secret youstream-db -o jsonpath='{.data.DB_PASSWORD}' | base64 -d)
    echo "keeping the existing password"
else
    password=$(random_secret)
fi
apply_env_secret youstream-db <<ENV
DB_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME?sslmode=verify-full&sslrootcert=/etc/youstream/db-ca/ca.crt
DB_USER=$DB_USER
DB_PASSWORD=$password
ENV
unset password

step "Database $DB_NAME and role $DB_USER (as $DB_ADMIN_USER)"
read -r -s -p "Password of $DB_ADMIN_USER: " admin_password
echo
[ -n "$admin_password" ] || die "no password entered"
apply_env_secret youstream-db-admin <<<"password=$admin_password"
unset admin_password
cleanup() {
    k -n "$NAMESPACE" delete pod db-setup --ignore-not-found --wait=false >/dev/null 2>&1 || true
    k -n "$NAMESPACE" delete configmap db-setup --ignore-not-found >/dev/null 2>&1 || true
    k -n "$NAMESPACE" delete secret youstream-db-admin --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup EXIT
k -n "$NAMESPACE" delete pod db-setup --ignore-not-found >/dev/null
k -n "$NAMESPACE" create configmap db-setup --from-file=database.sql="$MANIFESTS/database.sql" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
render "$MANIFESTS/db-setup.yaml" | k apply -f - >/dev/null
wait_pod "$NAMESPACE" db-setup
echo "database ready; the admin password was only held in a temporary secret, now deleted"
