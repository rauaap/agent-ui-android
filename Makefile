IMAGE ?= android-builder
PODMAN ?= podman
GRADLE_CACHE ?= android-gradle-cache
# Shared release keystore + passwords for all apps; never inside a repo.
SIGNING_DIR ?= $(HOME)/.android-signing

RUN_ANDROID = $(PODMAN) run --rm --userns=keep-id $(PODMAN_ARGS) \
	-e HOME=/gradle-cache \
	-e JAVA_TOOL_OPTIONS=-Duser.home=/gradle-cache \
	-e GRADLE_USER_HOME=/gradle-cache \
	-v "$(CURDIR):/work:Z" \
	-v "$(GRADLE_CACHE):/gradle-cache:Z" \
	-w /work \
	$(IMAGE)

.PHONY: image debug release test clean gradle shell install

image:
	$(PODMAN) build -t $(IMAGE) .

debug: image
	$(RUN_ANDROID) gradle --no-daemon assembleDebug

release: image
	$(RUN_ANDROID) scripts/release.sh

test: image
	$(RUN_ANDROID) gradle --no-daemon testDebugUnitTest

clean: image
	$(RUN_ANDROID) gradle --no-daemon clean

gradle: image
	$(RUN_ANDROID) gradle --no-daemon $(ARGS)

shell: image
	$(RUN_ANDROID) bash

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release signing and versioning; see RELEASING.md.
.PHONY: signing-key release-init

release: PODMAN_ARGS = -v "$(SIGNING_DIR):/signing:ro,z"
release: check-signing

signing-key: PODMAN_ARGS = -it -v "$(SIGNING_DIR):/signing:z"
signing-key: image
	mkdir -p -m 700 "$(SIGNING_DIR)"
	$(RUN_ANDROID) scripts/signing-key.sh

release-init:
	@case "$(LAST)" in ''|*[!0-9]*) echo "usage: make release-init LAST=<last released versionCode, 0 for a new app>" >&2; exit 1;; esac
	@test ! -e .last-version-code || { echo ".last-version-code already exists ($$(cat .last-version-code)); edit it by hand if you really mean to change it" >&2; exit 1; }
	echo "$(LAST)" > .last-version-code

.PHONY: check-signing
check-signing:
	@test -f "$(SIGNING_DIR)/signing.properties" || { echo "No release signing config in $(SIGNING_DIR). Run 'make signing-key' once, or restore your backup (see RELEASING.md)." >&2; exit 1; }
