# Releasing

Version preparation is separate from building. The tracked `versionCode` in
`app/build.gradle` is the source of truth, locally and in CI. `make release`
never bumps it, edits the base versionName, commits, or publishes anything.

## Development workflow

1. Make the app changes.
2. Run `make bump-version` (or `python3 scripts/bump-version.py`). This increments
   the literal `versionCode` in `app/build.gradle`, leaving the base versionName
   and formatting unchanged. Python 3 is the only dependency of this script.
3. Run `make release` to build and verify the signed APK locally.
4. Commit the app changes and bumped versionCode. On the first release, also
   commit the generated public `release-cert.sha256`.
5. Push to `main`. Gitea Actions builds the committed version and publishes its
   APK as a release, unless that push has been superseded.

If the local build fails, fix it and retry **without another bump**. Rebuilding
an unpublished version replaces its local APK in `dist/`. Once a version is
published, a fix needs a new code; published APKs are never overwritten by CI.
Every new versionCode must exceed all codes previously released or installed.

## How versions work

For example, `app/build.gradle` contains:

```groovy
versionCode = 42
versionName = '0.1.0-preview'
```

`app/release.gradle` derives the release APK's versionName as
**`0.1.0-preview.42`**. The literal base stays `0.1.0-preview`; change that base
whenever you want, using only letters, digits and `. _ + -`. The code stays 42
until you explicitly bump it. Android permits codes from 1 to 2100000000.

Output: `dist/<applicationId>-<derived versionName>.apk`, for example
`dist/com.example.app-0.1.0-preview.42.apk`. Debug builds use the same tracked
code, the base name without the suffix, and the debug signing key.

There is no `.last-version-code`, `release-init`, or CI counter allocation.
When migrating an existing app, first set the tracked code to at least the
highest code ever published or installed (including the old local counter,
if it was ahead); then bump for the next release. Old counter files are no
longer read and can be deleted. The old `-PreleaseVersionCode` override is
rejected to prevent accidentally bypassing the tracked value.

## Signing

All personal apps share **one** release keystore, outside every repo in
`~/.android-signing/` (override with `SIGNING_DIR=...`):

```
~/.android-signing/release.keystore      the private key (PKCS12, alias "release")
~/.android-signing/signing.properties    keystore path + password (mode 600)
```

The key never enters the Gradle build. `make release` runs in three steps:

1. **Build** (normal container, network on): Gradle builds an unsigned APK using
   the tracked code and derived name. The signing directory is not mounted.
2. **Sign** (separate container, `--network=none`): only the signing directory
   (read-only), unsigned APK (read-only), and output directory are mounted—not
   the repo or Gradle cache. It zipaligns and signs with `apksigner`.
3. **Finish** (normal container): checks the signature and expected certificate,
   then atomically saves/replaces the local APK in `dist/`. No version changes.

If signing configuration is missing, signing cannot proceed. Running
`assembleRelease` alone only produces `app-release-unsigned.apk`, which Android
refuses to install.

The password is random and kept in `signing.properties`, so releases never
prompt. Anyone who can read the directory has both key and password: protect
it with filesystem permissions and an encrypted backup. Never commit it or
upload it to the APK server. Shared SELinux labeling on the signing-directory
mount lets different apps use the same key concurrently.

### Certificate check

The first local release records the signing certificate's SHA-256 fingerprint
in `release-cert.sha256`. **Commit that file before CI runs.** It is public,
not a private key or password. Later builds fail if signed by a different
certificate. CI requires an existing fingerprint and never silently learns a
new signing identity.

## First-time setup

Once per machine, for a genuinely new signing identity:

```sh
make signing-key
```

This creates the key with a random password, writes `signing.properties`, and
prints the fingerprint. It refuses to replace an existing key. **Back it up
right away.** On another machine, restore that key instead of generating one.

For a new app, set the base name, bump the code, run a local release, and commit
the code and generated certificate fingerprint before pushing to CI.

### Switching an installed app from debug to release signing

Android accepts updates only with the same signing key as the installed app.
A debug-signed app must be **uninstalled once** before installing a release
build. This wipes its local data; server-side data is unaffected. Afterward,
updates signed with the same release key install normally.

## Gitea Actions setup

`.gitea/workflows/release.yml` queues a job on pushes to `main`, not pull
requests. Android tooling remains containerized even though the job runs on
the host.

1. Enable Actions on the repository. Register the act_runner label
   **`android-release:host`**, or change `runs-on` to your host label.
2. The runner user needs working Podman, `make`, Python 3, Git, and Node.js 20+
   for checkout. Its home directory must be stable and writable. Test Podman
   as the actual runner user.
3. Securely restore your existing `~/.android-signing/` into that user's home,
   with directory mode 700 and private files mode 600. Local and CI releases
   must use the same key. A custom path can be configured with `SIGNING_DIR`
   in the workflow step's environment. Never put the private key in the repo.
4. Commit `release-cert.sha256`, obtained from the first local release.
5. Create a Gitea token for an account with repository write access
   (`write:repository` scope on versions supporting scoped tokens). Store it as
   the repository Actions secret **`RELEASE_TOKEN`**. It needs to read the main
   branch/tags/releases, create and edit releases, create their tags, and manage
   draft attachments. Tag protections must allow this account to create
   `build-*` tags. Prefer a dedicated account with access only to necessary repos.
6. Allow APK attachments in Gitea's release settings and set an upload size limit
   large enough for the app.

Use **one runner host/user per repository**. A per-repository lock under
`~/.cache/android-release-locks/` serializes builds/publication and protects
shared Podman resources. Images and Gradle cache volumes are named per repo.
The lock stores no version state and does not depend on Actions `concurrency`.

Only trusted repositories should use the signing host. A host workflow can
read the runner user's files, including the signing key. Offline signing
isolates the key from Gradle, **not from malicious workflow or repo scripts**.
Do not run untrusted pull requests on this host.

### Publication and retries

CI builds the exact pushed commit and reads the resulting version metadata.
It creates a draft release named after the derived versionName, using a
**`build-<versionCode>`** tag pointing to that commit. The tag identifies the
version; it is not a counter and does not determine the next code.

After uploading the signed APK, CI publishes the draft. An already-published
release for the same commit/code is left untouched on a rerun. Reusing a tag
for another commit fails with a reminder to bump versionCode. Failed uploads
or builds never cause a version bump. A same-commit rerun can resume a draft,
replacing only its unpublished attachments. If an unpublished tag/draft belongs
to a different commit, either bump for the fix or explicitly remove that
unpublished tag/draft before retrying. Never delete a published version's tag
or attachments to republish a different APK under that code.

### Superseded pushes

After acquiring the lock, CI checks whether its commit is still the tip of
`main`. Older queued jobs exit successfully without building. If a newer push
arrives during a build, the build may finish, but CI rechecks before uploading
and immediately before publishing. A superseded draft stays unpublished.
Already published releases are retained.

The final tip check and publication are separate API requests: a push in that
small interval can still race with publication. Gitea has no atomic
publish-if-main-is-still-this-commit operation.

## Backups and Obtainium

Back up `~/.android-signing/` encrypted and off the build machine. Losing it
means installed apps cannot be updated; a leaked key can sign forged updates.
For example:

```sh
tar -C ~ -czf - .android-signing | gpg -c > android-signing.tar.gz.gpg
# Restore:
gpg -d android-signing.tar.gz.gpg | tar -C ~ -xzf -
```

Version state is already tracked in Git. Back up Gitea's repository/release
storage for published tags and APK attachments.

Obtainium can watch the repo using its Gitea release source. Alternatively,
copy local APKs to a static HTTP directory listing and use an HTML source.
Only APKs belong on that server—never the signing directory.

## Tooling tests

```sh
make test-tooling
```

These tests use Python's standard library and fake build/signing tools; they do
not require Podman, Android tooling, a real signing key, or a Gitea instance.
