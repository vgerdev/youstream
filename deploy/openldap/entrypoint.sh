#!/bin/sh
# Creates the configuration and directory tree on first start, sets the service account's password from
# LDAP_BIND_PASSWORD on every start, then runs slapd in the foreground.
set -eu

: "${LDAP_BASE_DN:?LDAP_BASE_DN is required, e.g. dc=youstream,dc=example,dc=com}"
: "${LDAP_BIND_PASSWORD:?LDAP_BIND_PASSWORD is required (the YouStream service account)}"
STATE=/var/lib/openldap
CONF="$STATE/slapd.d"
DATA="$STATE/data"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [ ! -d "$CONF/cn=config" ]; then
    echo "first start: creating $LDAP_BASE_DN"
    mkdir -p "$CONF" "$DATA"
    sed -e "s|@BASE_DN@|$LDAP_BASE_DN|g" -e "s|@DATA_DIR@|$DATA|g" \
        -e "s|@UID@|$(id -u)|g" -e "s|@GID@|$(id -g)|g" \
        /opt/openldap/config.ldif >"$work/config.ldif"
    slapadd -n 0 -F "$CONF" -l "$work/config.ldif"
    dc=$(printf '%s' "$LDAP_BASE_DN" | sed -n 's/^dc=\([^,]*\).*/\1/p')
    [ -n "$dc" ] || { echo "LDAP_BASE_DN must start with dc=" >&2; exit 1; }
    sed -e "s|@BASE_DN@|$LDAP_BASE_DN|g" -e "s|@DC@|$dc|g" /opt/openldap/bootstrap.ldif >"$work/bootstrap.ldif"
    slapadd -n 1 -F "$CONF" -l "$work/bootstrap.ldif"
fi

# offline, before slapd starts: the service account always has the password from the Kubernetes secret
umask 077
printf '%s' "$LDAP_BIND_PASSWORD" >"$work/password"
hash=$(slappasswd -h '{SSHA}' -T "$work/password")
cat >"$work/service.ldif" <<EOF
dn: cn=youstream,ou=system,$LDAP_BASE_DN
changetype: modify
replace: userPassword
userPassword: $hash
EOF
slapmodify -n 1 -F "$CONF" -l "$work/service.ldif" >/dev/null
rm -rf "$work"
trap - EXIT

exec slapd -d "${SLAPD_DEBUG_LEVEL:-256}" -F "$CONF" -h "ldap://:1389/ $LDAPI_URL"
