#!/usr/bin/env bash
set -euo pipefail
umask 077

# Copy the image's public roots and append the AI CA; OAuth/public HTTPS remain trusted.
# All files are container-private in tmpfs. The AI CA private key is never mounted.
truststore="$(mktemp /tmp/hanmaum-ai-trust.XXXXXXXXXX)"
cp -- "$JAVA_HOME/lib/security/cacerts" "$truststore"
keytool -importcert -noprompt -alias hanmaum-ai-internal \
  -file /run/hanmaum-ai/ca.crt -keystore "$truststore" -storepass changeit > /dev/null
exec java "-Djavax.net.ssl.trustStore=$truststore" \
  -Djavax.net.ssl.trustStorePassword=changeit "$@"
