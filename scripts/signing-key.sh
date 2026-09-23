#!/usr/bin/env bash
# Generates the shared release keystore and signing.properties in /signing.
# Runs inside the build container via `make signing-key`; see RELEASING.md.
set -euo pipefail

keystore=/signing/release.keystore
props=/signing/signing.properties
alias=release

die() { echo "signing-key: $*" >&2; exit 1; }

[[ ! -e $keystore && ! -e $props ]] ||
    die "$keystore or $props already exists; refusing to overwrite the signing key"

read -rsp 'New keystore password (min 6 chars): ' pw; echo
read -rsp 'Repeat password: ' pw2; echo
[[ $pw == "$pw2" ]] || die "passwords do not match"
(( ${#pw} >= 6 )) || die "password must be at least 6 characters"

umask 077
export KEYSTORE_PASSWORD=$pw
keytool -genkeypair \
    -keystore "$keystore" -storetype PKCS12 -storepass:env KEYSTORE_PASSWORD \
    -alias "$alias" -keyalg RSA -keysize 4096 -validity 36500 \
    -dname 'CN=Personal Android apps'

# Properties files treat backslash as an escape character.
esc=${pw//\\/\\\\}
cat > "$props" <<EOF
storeFile=release.keystore
storePassword=$esc
keyAlias=$alias
keyPassword=$esc
EOF

keytool -list -v -keystore "$keystore" -storepass:env KEYSTORE_PASSWORD 2>/dev/null |
    grep -m 1 'SHA256' || true
echo "signing-key: created $keystore and $props — back them up now (see RELEASING.md)"
