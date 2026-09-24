#!/usr/bin/env bash
# Aligns and signs /in/app.apk into /out/app.apk with the shared release key.
# `make release` feeds this script on stdin to an offline container that has
# only the signing directory, the unsigned APK and the output directory
# mounted — not the repo or the Gradle cache. See RELEASING.md.
set -euo pipefail

build_tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)
props=/signing/signing.properties
prop() { sed -n "s/^$1=//p" "$props" | head -n 1; }

store_file=$(prop storeFile)
key_alias=$(prop keyAlias)
KS_PASS=$(prop storePassword)
export KS_PASS

"$build_tools/zipalign" -P 16 -f 4 /in/app.apk /tmp/aligned.apk
"$build_tools/apksigner" sign \
    --ks "/signing/$store_file" --ks-key-alias "$key_alias" --ks-pass env:KS_PASS \
    --v4-signing-enabled false \
    --out /out/app.apk /tmp/aligned.apk
