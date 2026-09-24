#!/usr/bin/env bash
# Generates the shared release keystore, with a random password, and
# signing.properties in /signing. `make signing-key` feeds this script on stdin
# to an offline container with only the signing directory mounted. See
# RELEASING.md.
set -euo pipefail

keystore=/signing/release.keystore
props=/signing/signing.properties
alias=release

die() { echo "signing-key: $*" >&2; exit 1; }

[[ ! -e $keystore && ! -e $props ]] ||
    die "$keystore or $props already exists; refusing to overwrite the signing key"

# The password is stored next to the keystore anyway (releases are unattended),
# so it only needs to be strong, not memorable.
KEYSTORE_PASSWORD=$(head -c 48 /dev/urandom | base64 -w 0 | tr -dc 'A-Za-z0-9' | cut -c 1-40)
export KEYSTORE_PASSWORD

umask 077
keytool -genkeypair \
    -keystore "$keystore" -storetype PKCS12 -storepass:env KEYSTORE_PASSWORD \
    -alias "$alias" -keyalg RSA -keysize 4096 -validity 36500 \
    -dname 'CN=Personal Android apps' < /dev/null

cat > "$props" <<EOF
storeFile=release.keystore
storePassword=$KEYSTORE_PASSWORD
keyAlias=$alias
keyPassword=$KEYSTORE_PASSWORD
EOF

keytool -list -v -keystore "$keystore" -storepass:env KEYSTORE_PASSWORD < /dev/null 2>/dev/null |
    grep -m 1 'SHA256' || true
echo "signing-key: created $keystore and $props — back them up now (see RELEASING.md)"
