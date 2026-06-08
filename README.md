# Agent UI — Android

A native Android client for controlling Claude Code agents. It talks to the
backend over REST for managing sessions and a WebSocket per session for the
live transcript.

The build is fully containerized and CLI-driven — no JDK, Android SDK, or Gradle
needed on the host. The only host dependency is `podman` (and `adb`, if you want
to install on a device).

## Features

- **Session list** — create, open, and delete agent sessions.
- **Live transcript** — streamed agent output, collapsible tool-use cards, and
  inline approval prompts (Allow / Deny) over a WebSocket that auto-reconnects.
- **Composer** — send prompts; input locks while the agent is running.
- **Settings** — the server host / port (and optional TLS) are stored in
  `SharedPreferences`, so the address **persists across app restarts and device
  reboots**. Set it once via the ⚙ button on the session list.

## Layout

```
Containerfile          Fedora + JDK 21 + Android SDK + Gradle toolchain
Makefile               build / shell / install targets (podman wrapper)
build.gradle           root project — pins the Android Gradle Plugin version
settings.gradle        project name + module list
app/                   the application module
```

Key sources under `app/src/main/java/com/agentui/app/`:

| File | Role |
|------|------|
| `SessionListActivity.java` | launcher screen: list / create / delete sessions |
| `SessionActivity.java`     | per-session transcript + composer + WebSocket |
| `SettingsActivity.java`    | server address form (persisted) |
| `Prefs.java`               | `SharedPreferences`-backed server config |
| `Api.java`                 | OkHttp REST client for `/sessions` endpoints |
| `Session.java`             | session model |
| `Theme.java` / `Widgets.java` | colours + programmatic view helpers |

## Build

Build the toolchain image once:

```sh
make image
```

Build a debug APK (rebuilds the image if needed):

```sh
make debug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Other targets:

```sh
make release            # assembleRelease
make clean              # gradle clean
make gradle ARGS="tasks"   # run any gradle task in the container
make shell              # interactive shell inside the build container
```

The Gradle cache is persisted in a named volume (`android-gradle-cache`) so
incremental builds and the debug keystore survive between runs.

## Install on a device

The build stays containerized; only `adb` runs on the host:

```sh
sudo dnf install android-tools     # Fedora
make install                       # adb install -r the debug APK
```

On first launch, open Settings (⚙) and enter the host / IP and port of your
agent backend.

## Backend protocol

REST: `GET /sessions`, `POST /sessions`, `DELETE /sessions/{id}`,
`POST /sessions/{id}/stop`.

WebSocket: `ws(s)://<host>/ws/sessions/{id}`

```
client -> server : { type: "input", text }
                   { type: "approval_response", request_id, behavior }

server -> client : { type: "status",           status }   # idle | running | awaiting_approval
                   { type: "input",            text }
                   { type: "output",           text }
                   { type: "tool_use",         tool, input }
                   { type: "approval_request", request_id, tool, input }
                   { type: "approval_response", request_id, behavior }
                   { type: "done" }
                   { type: "error",            message }
```

## Notes

- **Container-only by design.** There is no Gradle wrapper (`gradlew`); the
  pinned Gradle version lives solely in the `Containerfile`. Build through
  `make`, not on the host.
- SDK level, build-tools, and Gradle versions are all `ARG`s at the top of the
  `Containerfile` — change them in one place.
- Cleartext HTTP is enabled (`usesCleartextTraffic`) so plain `http://` LAN
  backends work; flip the TLS switch in Settings for `https`/`wss`.
