#!/usr/bin/env bash
# Release steps that run in the normal build container via `make release`:
#
#   build   build the unsigned APK with the next versionCode into build/release/
#   finish  check the signed APK's certificate, save it to dist/, and only then
#           advance the counter
#
# Signing happens between the two in an offline container (scripts/sign-apk.sh)
# that never sees this repo or Gradle. See RELEASING.md.
set -euo pipefail

counter=.last-version-code
cert_file=release-cert.sha256
out_dir=dist
stage=build/release

die() { echo "release: $*" >&2; exit 1; }
apksigner() { "$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)/apksigner" "$@"; }

build() {
    [[ -f $counter ]] ||
        die "no $counter; initialize it with \`make release-init LAST=<last released versionCode, 0 for a new app>\`"
    local last code
    last=$(tr -d '[:space:]' < "$counter")
    [[ $last =~ ^(0|[1-9][0-9]*)$ ]] || die "$counter does not contain a non-negative integer: '$last'"
    code=$((last + 1))

    rm -rf "$stage"
    echo "release: building versionCode $code"
    gradle --no-daemon assembleRelease -PreleaseVersionCode="$code"

    local apk_dir=app/build/outputs/apk/release
    local meta=$apk_dir/output-metadata.json
    json_field() { sed -n "s/.*\"$1\": *\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}\$/\1/p" "$meta" | head -n 1; }
    local app_id built_code name
    app_id=$(json_field applicationId)
    built_code=$(json_field versionCode)
    name=$(json_field versionName)

    [[ $built_code == "$code" ]] || die "built versionCode '$built_code', expected $code"
    [[ $name =~ ^[A-Za-z0-9._+-]+$ ]] ||
        die "versionName '$name' is not filename-safe; use only letters, digits and . _ + -"

    mkdir -p "$stage/unsigned" "$stage/signed"
    cp "$apk_dir/app-release-unsigned.apk" "$stage/unsigned/app.apk"
    printf '%s\n' "$app_id" "$name" "$code" > "$stage/info"
}

finish() {
    local app_id name code
    { read -r app_id; read -r name; read -r code; } < "$stage/info"
    local apk=$stage/signed/app.apk
    [[ -f $apk ]] || die "$apk missing; signing did not complete"

    apksigner verify "$apk" || die "$apk failed signature verification"
    local certs signers cert
    certs=$(apksigner verify --print-certs "$apk")
    signers=$(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' <<< "$certs" || true)
    [[ $signers == 1 ]] || die "expected exactly one signer, found $signers"
    cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$certs")

    if [[ -f $cert_file ]]; then
        local expected
        expected=$(tr -d '[:space:]' < "$cert_file")
        [[ $cert == "$expected" ]] || die "APK was signed with certificate $cert, but $cert_file expects $expected.
The signing directory holds a different key than earlier releases; restore the right one (see RELEASING.md)."
    else
        echo "$cert" > "$cert_file"
        echo "release: recorded signing certificate in $cert_file — commit it"
    fi

    local out=$out_dir/$app_id-$name.apk
    [[ ! -e $out ]] || die "$out already exists; refusing to overwrite"
    mkdir -p "$out_dir"
    cp "$apk" "$out.tmp"
    mv "$out.tmp" "$out"

    echo "$code" > "$counter.tmp"
    mv "$counter.tmp" "$counter"

    echo "release: $out (versionName $name, versionCode $code, certificate $cert)"
}

case ${1:-} in
    build) build ;;
    finish) finish ;;
    *) die "usage: $0 build|finish" ;;
esac
