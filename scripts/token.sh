#!/usr/bin/env bash
set -euo pipefail

SUB="${1:-vendedor-1}"
ROLE="${2:-PROFESSIONAL}"
MINUTES="${3:-60}"
KEY="${4:-dev-keys/dev-private.pem}"

if ! [[ "$MINUTES" =~ ^[0-9]+$ ]] || (( MINUTES < 1 )); then
  echo "Los minutos deben ser un entero positivo." >&2
  exit 1
fi
if [[ ! -f "$KEY" ]]; then
  echo "No se encontró la llave privada: $KEY" >&2
  exit 1
fi

NOW="$(date +%s)"
EXP="$((NOW + MINUTES * 60))"
HEADER='{"alg":"RS256","typ":"JWT"}'
PAYLOAD="$(printf '{"sub":"%s","role":"%s","iat":%s,"exp":%s}' \
  "${SUB//\"/\\\"}" "${ROLE//\"/\\\"}" "$NOW" "$EXP")"
base64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
HEADER_B64="$(printf '%s' "$HEADER" | base64url)"
PAYLOAD_B64="$(printf '%s' "$PAYLOAD" | base64url)"
SIGNING_INPUT="$HEADER_B64.$PAYLOAD_B64"
SIGNATURE="$(printf '%s' "$SIGNING_INPUT" | openssl dgst -sha256 -sign "$KEY" -binary | base64url)"
printf '%s.%s\n' "$SIGNING_INPUT" "$SIGNATURE"
