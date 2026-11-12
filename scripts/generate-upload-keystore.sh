#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Lumina — Local Google Play Upload Keystore Generator
# Run locally on your workstation (NEVER commit .jks files to git).
# ==============================================================================

KEYSTORE_FILE="${1:-lumina-upload-keystore.jks}"
KEY_ALIAS="${2:-lumina-upload}"

if [ -f "${KEYSTORE_FILE}" ]; then
  echo "Error: ${KEYSTORE_FILE} already exists. Refusing to overwrite." >&2
  exit 1
fi

read -r -s -p "Enter a strong Keystore & Key Password (min 12 chars): " STORE_PASS
echo ""
read -r -s -p "Confirm Password: " STORE_PASS_CONFIRM
echo ""

if [ "${STORE_PASS}" != "${STORE_PASS_CONFIRM}" ] || [ "${#STORE_PASS}" -lt 8 ]; then
  echo "Error: Passwords did not match or were shorter than 8 characters." >&2
  exit 1
fi

keytool -genkeypair \
  -v \
  -keystore "${KEYSTORE_FILE}" \
  -storetype PKCS12 \
  -alias "${KEY_ALIAS}" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "${STORE_PASS}" \
  -keypass "${STORE_PASS}" \
  -dname "CN=Lumina HDR Media Player, OU=Mobile, O=Petros Vassiliou, C=GR"

B64_OUT="${KEYSTORE_FILE}.base64.txt"
base64 < "${KEYSTORE_FILE}" | tr -d '\n' > "${B64_OUT}"

echo ""
echo "=============================================================================="
echo "Upload Keystore created: ${KEYSTORE_FILE}"
echo "Base64 secret written to: ${B64_OUT} (ignored by .gitignore)"
echo ""
echo "Add these 4 Repository Secrets at:"
echo "  https://github.com/petrosvassiliou/lumina/settings/secrets/actions"
echo ""
echo "  1. LUMINA_KEYSTORE_BASE64   -> contents of ${B64_OUT}"
echo "  2. LUMINA_KEYSTORE_PASSWORD -> (your chosen password)"
echo "  3. LUMINA_KEY_ALIAS         -> ${KEY_ALIAS}"
echo "  4. LUMINA_KEY_PASSWORD      -> (your chosen password)"
echo "=============================================================================="
