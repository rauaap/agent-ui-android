#!/usr/bin/env bash
# Builds a signed release APK with the next versionCode, saves it to dist/, and
# only then advances the counter. Runs inside the build container via
# `make release`; see RELEASING.md.
set -euo pipefail

counter=.last-version-code
out_dir=dist
apk_dir=app/build/outputs/apk/release

die() { echo "release: $*" >&2; exit 1; }

[[ -f /signing/signing.properties ]] ||
    die "no signing config mounted at /signing (run \`make signing-key\` or restore your backup)"
[[ -f $counter ]] ||
    die "no $counter; initialize it with \`make release-init LAST=<last released versionCode, 0 for a new app>\`"
last=$(tr -d '[:space:]' < "$counter")
[[ $last =~ ^(0|[1-9][0-9]*)$ ]] || die "$counter does not contain a non-negative integer: '$last'"
code=$((last + 1))

echo "release: building versionCode $code"
gradle --no-daemon assembleRelease -PreleaseVersionCode="$code"

apk=$apk_dir/app-release.apk
meta=$apk_dir/output-metadata.json
json_field() { sed -n "s/.*\"$1\": *\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}\$/\1/p" "$meta" | head -n 1; }
app_id=$(json_field applicationId)
built_code=$(json_field versionCode)
name=$(json_field versionName)

[[ $built_code == "$code" ]] || die "built versionCode '$built_code', expected $code"
[[ $name =~ ^[A-Za-z0-9._+-]+$ ]] ||
    die "versionName '$name' is not filename-safe; use only letters, digits and . _ + -"

apksigner=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n 1)
"$apksigner" verify "$apk" || die "$apk failed signature verification"

out=$out_dir/$app_id-$name.apk
[[ ! -e $out ]] || die "$out already exists; refusing to overwrite"
mkdir -p "$out_dir"
cp "$apk" "$out.tmp"
mv "$out.tmp" "$out"

echo "$code" > "$counter.tmp"
mv "$counter.tmp" "$counter"

"$apksigner" verify --print-certs "$apk" | grep -m 1 'SHA-256' || true
echo "release: $out (versionName $name, versionCode $code)"
