#!/usr/bin/env bash
# Manages YouStream's OpenLDAP users through kubectl exec (the local-socket admin identity, no admin password).
#
#   ldap-user.sh add <uid> "<Full Name>" <email> [--admin]    create a user (asks for the password)
#   ldap-user.sh passwd <uid>                                  set a new password
#   ldap-user.sh disable <uid> | enable <uid>                  lock / unlock sign-in (keeps the account and videos)
#   ldap-user.sh admin <uid> | unadmin <uid>                   grant / revoke YouStream admin rights
#   ldap-user.sh delete <uid>                                  remove the directory entry
#   ldap-user.sh list
#
# Environment: KUBE_CONTEXT (default: current context), NAMESPACE (default youstream), POD (default openldap-0).
# Passwords are read without echo and passed on stdin, never on a command line.
set -euo pipefail

NAMESPACE="${NAMESPACE:-youstream}"
POD="${POD:-openldap-0}"
kube=(kubectl)
[ -n "${KUBE_CONTEXT:-}" ] && kube+=(--context "$KUBE_CONTEXT")
kube+=(-n "$NAMESPACE")

die() { echo "error: $*" >&2; exit 1; }
in_pod() { "${kube[@]}" exec -i "$POD" -c openldap -- "$@"; }
modify() { in_pod sh -c 'ldapmodify -Q -Y EXTERNAL -H "$LDAPI_URL" "$@"' ldapmodify "$@"; }
b64() { printf '%s' "$1" | base64 | tr -d '\n'; }

check_uid() {
    [[ "${1:-}" =~ ^[a-z][a-z0-9._-]{1,31}$ ]] || die "user name must be 2-32 characters: a-z, 0-9, . _ - (starting with a letter)"
}

read_password() {
    local p1 p2
    read -r -s -p "Password for $1 (12+ characters): " p1; echo >&2
    read -r -s -p "Repeat: " p2; echo >&2
    [ "$p1" = "$p2" ] || die "passwords differ"
    [ "${#p1}" -ge 12 ] || die "password shorter than 12 characters"
    password=$p1
}

base=$(in_pod printenv LDAP_BASE_DN) || die "cannot reach $POD in namespace $NAMESPACE"
people="ou=people,$base"
users_group="cn=youstream-users,ou=groups,$base"
admins_group="cn=youstream-admins,ou=groups,$base"

cmd="${1:-}"
shift || true
case "$cmd" in
    add)
        [ $# -ge 3 ] || die "usage: add <uid> \"<Full Name>\" <email> [--admin]"
        uid=$1 name=$2 mail=$3 admin=${4:-}
        check_uid "$uid"
        [[ "$mail" == *@*.* ]] || die "not an e-mail address: $mail"
        sn=${name##* }
        read_password "$uid"
        modify <<LDIF
dn: uid=$uid,$people
changetype: add
objectClass: inetOrgPerson
uid: $uid
cn:: $(b64 "$name")
sn:: $(b64 "$sn")
mail: $mail
userPassword:: $(b64 "$password")

dn: $users_group
changetype: modify
add: member
member: uid=$uid,$people
LDIF
        if [ "$admin" = --admin ]; then
            modify <<LDIF
dn: $admins_group
changetype: modify
add: member
member: uid=$uid,$people
LDIF
        fi
        echo "added $uid${admin:+ (admin)}"
        ;;
    passwd)
        check_uid "${1:-}"
        read_password "$1"
        modify <<LDIF
dn: uid=$1,$people
changetype: modify
replace: userPassword
userPassword:: $(b64 "$password")
LDIF
        echo "password changed for $1"
        ;;
    disable)
        check_uid "${1:-}"
        # a pwdAccountLockedTime of 000001010000Z locks the account until an administrator removes it
        modify -c <<LDIF || true
dn: uid=$1,$people
changetype: modify
replace: pwdAccountLockedTime
pwdAccountLockedTime: 000001010000Z

dn: $users_group
changetype: modify
delete: member
member: uid=$1,$people
LDIF
        echo "disabled $1: new sign-ins and SFTP password logins are refused; existing browser sessions are not ended"
        ;;
    enable)
        check_uid "${1:-}"
        modify -c <<LDIF || true
dn: uid=$1,$people
changetype: modify
delete: pwdAccountLockedTime

dn: $users_group
changetype: modify
add: member
member: uid=$1,$people
LDIF
        echo "enabled $1"
        ;;
    admin|unadmin)
        check_uid "${1:-}"
        op=add
        [ "$cmd" = unadmin ] && op=delete
        modify <<LDIF
dn: $admins_group
changetype: modify
$op: member
member: uid=$1,$people
LDIF
        echo "$cmd $1 done (takes effect at the user's next sign-in)"
        ;;
    delete)
        check_uid "${1:-}"
        read -r -p "Delete uid=$1 from the directory? Their YouStream account and videos stay. [y/N] " answer
        [ "$answer" = y ] || die "stopped"
        modify <<LDIF
dn: uid=$1,$people
changetype: delete
LDIF
        echo "deleted $1"
        ;;
    list)
        in_pod sh -c 'ldapsearch -LLL -Q -Y EXTERNAL -H "$LDAPI_URL" -b "$1" "(objectClass=inetOrgPerson)" \
            uid cn mail memberOf pwdAccountLockedTime' ldapsearch "$people"
        ;;
    *)
        sed -n '2,12p' "$0"
        exit 2
        ;;
esac
