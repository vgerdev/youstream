{{/* Release-scoped name: "youstream" for a release called youstream. */}}
{{- define "youstream.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 50 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 50 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "youstream.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* Selector labels of one role: dict "root" $ "role" "web" */}}
{{- define "youstream.selectorLabels" -}}
app.kubernetes.io/name: {{ .root.Chart.Name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .role }}
{{- end -}}

{{- define "youstream.image" -}}
{{- $tag := required "image.tag is required (the deploy workflow sets the commit SHA)" .Values.image.tag -}}
{{ printf "%s:%s" .Values.image.repository $tag | quote }}
{{- end -}}

{{- define "youstream.claimName" -}}
{{- .Values.persistence.existingClaim | default (printf "%s-media" (include "youstream.fullname" .)) -}}
{{- end -}}

{{/*
Environment of a YouStream container: dict "root" $ "cfg" <role values> "roles" "web" (empty for one-shot commands).
Settings come from values; secrets from the platform's Secrets via envFrom (see youstream.envFrom).
*/}}
{{- define "youstream.env" -}}
{{- $v := .root.Values -}}
- name: JAVA_OPTS
  value: {{ .cfg.javaOpts | quote }}
- name: DB_POOL_SIZE
  value: {{ .cfg.dbPoolSize | quote }}
- name: DB_MIGRATE
  value: "false"
- name: MAINTENANCE_EVERY
  value: "0"
- name: STORAGE_ROOT
  value: /data
- name: PUBLIC_URL
  value: {{ $v.publicUrl | quote }}
{{- if .roles }}
- name: YOUSTREAM_ROLES
  value: {{ .roles | quote }}
{{- end }}
- name: LDAP_ENABLED
  value: {{ $v.ldap.enabled | quote }}
{{- if $v.ldap.enabled }}
- name: LDAP_URL
  value: {{ $v.ldap.url | quote }}
- name: LDAP_BASE_DN
  value: {{ $v.ldap.baseDn | quote }}
- name: LDAP_BIND_DN
  value: {{ $v.ldap.bindDn | quote }}
- name: LDAP_REQUIRED_GROUP
  value: {{ $v.ldap.requiredGroup | quote }}
- name: LDAP_ADMIN_GROUP
  value: {{ $v.ldap.adminGroup | quote }}
{{- end }}
- name: ENTRA_ENABLED
  value: {{ $v.entra.enabled | quote }}
{{- if $v.entra.enabled }}
- name: ENTRA_TENANT_ID
  value: {{ required "entra.tenantId is required when entra.enabled" $v.entra.tenantId | quote }}
- name: ENTRA_CLIENT_ID
  value: {{ required "entra.clientId is required when entra.enabled" $v.entra.clientId | quote }}
- name: ENTRA_REDIRECT_URI
  value: {{ printf "%s/api/auth/microsoft/callback" (trimSuffix "/" $v.publicUrl) | quote }}
{{- end }}
- name: UPLOAD_QUOTA
  value: {{ $v.upload.quotaBytes | quote }}
- name: SFTP_PUBLIC_HOST
  value: {{ $v.sftp.publicHost | quote }}
- name: SFTP_PUBLIC_PORT
  value: {{ $v.sftp.publicPort | quote }}
- name: SFTP_HOST_KEY
  value: /etc/youstream/sftp/host_key
- name: SFTP_PROXY_PROTOCOL
  value: {{ $v.sftp.proxyProtocol | quote }}
- name: MEDIA_WORKERS
  value: {{ $v.transcode.workers | quote }}
{{- with $v.extraEnv }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{- define "youstream.envFrom" -}}
- secretRef:
    name: {{ .Values.secrets.database }}
- secretRef:
    name: {{ .Values.secrets.jwt }}
- secretRef:
    name: {{ .Values.secrets.entra }}
    optional: {{ not .Values.entra.enabled }}
{{- if .Values.ldap.enabled }}
- secretRef:
    name: {{ .Values.secrets.ldap }}
{{- end }}
{{- end -}}

{{/* Volume mounts: dict "root" $ "media" true|false */}}
{{- define "youstream.volumeMounts" -}}
- name: tmp
  mountPath: /tmp
- name: config
  mountPath: /opt/youstream/conf/local.conf
  subPath: local.conf
  readOnly: true
- name: db-ca
  mountPath: /etc/youstream/db-ca
  readOnly: true
- name: sftp-host-key
  mountPath: /etc/youstream/sftp
  readOnly: true
{{- if .media }}
- name: media
  mountPath: /data
{{- end }}
{{- end -}}

{{- define "youstream.volumes" -}}
- name: tmp
  emptyDir: {}
- name: config
  configMap:
    name: {{ include "youstream.fullname" .root }}
- name: db-ca
  secret:
    secretName: {{ .root.Values.secrets.databaseCa }}
    defaultMode: 0440
- name: sftp-host-key
  secret:
    secretName: {{ .root.Values.secrets.sftpHostKey }}
    defaultMode: 0440
{{- if .media }}
- name: media
  persistentVolumeClaim:
    claimName: {{ include "youstream.claimName" .root }}
{{- end }}
{{- end -}}
