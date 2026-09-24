# Releasing

`make release` builds a permanently signed APK with a new, never-reused
versionCode and saves it to `dist/`, ready to copy to an HTTP server that
Obtainium watches. Every successful build is a release; there is no separate
test build.

## How versions work

- **versionCode** is an integer counter kept per app in `.last-version-code`
  (untracked, repo root). `make release` builds with *last + 1* and writes the
  new value back **only after** the signed APK has been built, verified and
  saved. A failed build does not use up a number; a build that later fails
  testing on the phone does — the fix simply gets the next number.
- **versionName** is the base `versionName` in `app/build.gradle` with the code
  appended: base `0.1.0-preview` + code 42 → `0.1.0-preview.42`. Change the base
  whenever you like (major/minor/patch bumps or any other label, using only
  letters, digits and `. _ + -`); the counter never resets.
- Output: `dist/<applicationId>-<versionName>.apk`, e.g.
  `dist/com.example.app-0.1.0-preview.42.apk`. Each file name is unique, and an
  existing file is never overwritten.

Debug builds (`make debug`) are unchanged: versionCode 1, debug key.

## Signing

All personal apps share **one** release keystore, stored outside every repo in
`~/.android-signing/` (override with `SIGNING_DIR=...`):

```
~/.android-signing/release.keystore      the key (PKCS12, alias "release")
~/.android-signing/signing.properties    keystore path + password (mode 600)
```

The key never enters the Gradle build. `make release` runs in three steps:

1. **Build** (normal container, network on): Gradle builds an *unsigned* APK.
   The signing directory is not mounted, so no build script, plugin or
   dependency can read the key.
2. **Sign** (separate container, `--network=none`): only the signing
   directory (read-only), the unsigned APK (read-only) and an output directory
   are mounted — not the repo or the Gradle cache. It zipaligns and signs with
   `apksigner`.
3. **Finish** (normal container): checks the signature and certificate, saves
   the APK to `dist/` and advances the counter.

If the signing directory is missing, `make release` stops before building.
Running `assembleRelease` any other way only produces
`app-release-unsigned.apk`, which Android refuses to install.

The keystore password is random and kept in `signing.properties`, so releases
never prompt. It adds little on its own — anyone who can read the directory has
both — so the real protection is the directory's permissions and an encrypted
backup. Never commit this directory, paste its contents anywhere, or put it on
the APK server.

### Certificate check

The first release of an app writes the signing certificate's SHA-256
fingerprint to `release-cert.sha256`. **Commit that file** (the fingerprint is
public). Every later release fails if the APK was signed with any other
certificate, so a wrong or regenerated key is caught at build time instead of
when the phone refuses the update.

## First-time setup (once per machine)

```sh
make signing-key
```

Creates the keystore with a random password, writes `signing.properties`, and
prints the certificate's SHA-256 fingerprint. It refuses to run if a key
already exists. **Back it up right away** (below).

On a new machine, restore the backup to `~/.android-signing/` instead — never
generate a second key.

## First release of an app (once per app)

1. Set a base `versionName` in `app/build.gradle` (e.g. `'0.1.0-preview'`).
2. Initialize the counter:

   ```sh
   make release-init LAST=0      # new app: first release will be versionCode 1
   ```

   If the app has already been released or installed, use the highest
   versionCode ever published (or installed) instead, so versions never go
   backwards.
3. `make release`, then copy the APK from `dist/` to your HTTP server, and
   commit the newly created `release-cert.sha256`.

Each app has its own `applicationId` and its own counter; only the key is
shared.

### Switching an installed app from debug to release signing

Android only installs an update signed with the same key as the installed app.
An app that is currently installed with a debug build (signed by the debug key
in the Gradle cache volume) must be **uninstalled once** before installing the
first release-signed APK. Uninstalling wipes the app's local data (settings,
cached state on the phone); anything stored on a server is unaffected. After
that, Obtainium can update it normally.

## Backups and recovery

Back up, encrypted and somewhere other than the build machine:

- **`~/.android-signing/`** — critical. If the key is lost, installed apps can
  never be updated again; each would have to be uninstalled (losing local data)
  and reinstalled with a new key. If it leaks, anyone can publish updates your
  phone accepts. Example:

  ```sh
  tar -C ~ -czf - .android-signing | gpg -c > android-signing.tar.gz.gpg
  ```

  The keystore password is inside the backup; you only need to remember the
  `gpg` passphrase (e.g. in a password manager).
- **Each app's `.last-version-code`** — nice to have. It is recoverable: the
  highest versionCode released is the number at the end of the newest APK name
  on the server.

Restoring on a new machine or fresh clone:

```sh
gpg -d android-signing.tar.gz.gpg | tar -C ~ -xzf -
make release-init LAST=<highest versionCode already released>
```

If the counter ever falls behind what was released, Android and Obtainium will
treat new builds as older versions; fix it by editing `.last-version-code` to
the highest released code.

## Serving with Obtainium

Upload `dist/*.apk` to any static HTTP directory listing. In Obtainium, add the
app with the listing URL as an HTML source. The APK names are unique and end in
the full versionName, so newer builds are easy to pick out of the listing
(Obtainium can extract the version from the link if needed). Only APKs belong
on the server — never the signing directory.
