# OpenLDAP for YouStream's username/password sign-in: Debian's slapd (security updates from Debian), no
# third-party image. Runs as UID 10002, listens on 1389 (LDAP) and on a local socket for administration.
#
#   docker build -t youstream-openldap deploy/openldap
#
# State (cn=config and the database) lives in /var/lib/openldap, a volume. The first start creates the directory
# tree for LDAP_BASE_DN; every start sets the service account's password from LDAP_BIND_PASSWORD.
# Users are managed with deploy/openldap/ldap-user.sh.

FROM debian:trixie-slim
# fixed IDs before the package creates its user, so volume ownership is predictable
RUN groupadd --system --gid 10002 openldap \
    && useradd --system --uid 10002 --gid 10002 --home-dir /var/lib/openldap --shell /usr/sbin/nologin openldap \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends slapd ldap-utils \
    && rm -rf /var/lib/apt/lists/* /etc/ldap/slapd.d /var/lib/ldap/* \
    && mkdir -p /var/lib/openldap && chown openldap:openldap /var/lib/openldap
COPY --chmod=0644 config.ldif bootstrap.ldif /opt/openldap/
COPY --chmod=0755 entrypoint.sh /opt/openldap/
ENV LDAPI_URL=ldapi://%2Ftmp%2Fldapi
VOLUME /var/lib/openldap
EXPOSE 1389
USER 10002
ENTRYPOINT ["/opt/openldap/entrypoint.sh"]
