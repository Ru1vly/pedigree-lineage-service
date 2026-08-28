{{/*
Expand the name of the chart.
*/}}
{{- define "pedigree-lineage.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "pedigree-lineage.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "pedigree-lineage.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "pedigree-lineage.labels" -}}
helm.sh/chart: {{ include "pedigree-lineage.chart" . }}
{{ include "pedigree-lineage.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "pedigree-lineage.selectorLabels" -}}
app.kubernetes.io/name: {{ include "pedigree-lineage.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
API Selector labels
*/}}
{{- define "pedigree-lineage.apiSelectorLabels" -}}
app.kubernetes.io/name: {{ include "pedigree-lineage.name" . }}-api
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: api
{{- end }}

{{/*
Worker Selector labels
*/}}
{{- define "pedigree-lineage.workerSelectorLabels" -}}
app.kubernetes.io/name: {{ include "pedigree-lineage.name" . }}-worker
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: worker
{{- end }}

{{/*
Service Account Name
*/}}
{{- define "pedigree-lineage.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "pedigree-lineage.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Resolve the Secret name a credential group should be read from: an operator-supplied
pre-existing Secret (e.g. one populated by Vault Agent Injector or ExternalSecrets) if
"existingSecret" is set, otherwise this chart's own templated Secret name. Usage:
  {{ include "pedigree-lineage.secretNameOrExisting" (list .Values.kafka.existingSecret .Values.kafka.secretName) }}
*/}}
{{- define "pedigree-lineage.secretNameOrExisting" -}}
{{- $existing := index . 0 -}}
{{- $default := index . 1 -}}
{{- $existing | default $default -}}
{{- end }}
