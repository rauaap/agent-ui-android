# Agent UI — Android

A native Android client for controlling Claude Code agents. It talks to the
[agent-ui-server](https://github.com/rauaap/agent-ui-server) backend over REST for managing
sessions and a WebSocket per session for the live transcript.

The build is fully containerized and CLI-driven — no JDK, Android SDK, or Gradle
needed on the host. The only host dependency is `podman` (and `adb`, if you want
to install on a device).

## Features

- **Session list** — create, open, and delete agent sessions. New sessions take
  a name and a working directory; the directory is pre-filled from a configurable
  default and tracks the name as you type until you edit it by hand.
- **Live transcript** — streamed agent output, collapsible tool-use cards,
  inline approval prompts (Allow / Deny), and multiple-choice question prompts
  (Claude's AskUserQuestion) over a WebSocket that auto-reconnects.
- **Auto-approve** — per-session toggles in session settings to skip the approval
  prompt for writes and/or shell commands; auto-approved tools still appear in the
  transcript, marked as such. Reads always run.
- **Composer** — send prompts; input locks while the agent is running.
- **Background notifications** — toggle the bell on a session to watch it from a
  foreground service. You get a high-priority notification when the task finishes
  or needs your approval, even with the app off-screen; the watch stops once the
  turn ends and is silent for whichever session you're currently viewing.
- **Settings** — the server host / port (and optional TLS) plus a default working
  directory are stored in `SharedPreferences`, so they **persist across app
  restarts and device reboots**. Set them via the ⚙ button on the session list.

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
| `SessionSettingsActivity.java` | per-session settings: rename, notification opt-in, auto-approve toggles |
| `SettingsActivity.java`    | server address + default working directory form (persisted) |
| `WatchService.java`        | foreground service: per-session WebSocket watch + task-completion notifications |
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

The server lives in [rauaap/agent-ui-server](https://github.com/rauaap/agent-ui-server); this
is the protocol this client speaks to it.

REST: `GET /sessions`, `POST /sessions`, `PATCH /sessions/{id}`,
`DELETE /sessions/{id}`, `POST /sessions/{id}/stop`.

`POST /sessions` body: `{ name, working_dir, agent }`.

`PATCH /sessions/{id}` body (all optional): `{ name, auto_approve_write,
auto_approve_command }` — rename and/or flip the per-session auto-approve toggles.

WebSocket: `ws(s)://<host>/ws/sessions/{id}`

```
client -> server : { type: "input", text }
                   { type: "approval_response", request_id, behavior }
                   { type: "question_response", request_id, answers }

server -> client : { type: "status",           status }   # idle | running | awaiting_approval
                   { type: "input",            text }
                   { type: "output",           text }
                   { type: "tool_use",         tool, input }
                   { type: "approval_request", request_id, tool, input, category, auto_approved? }
                   { type: "approval_response", request_id, behavior, auto? }
                   { type: "question",         request_id, questions }
                   { type: "question_response", request_id, answers }
                   { type: "settings",         auto_approve_write, auto_approve_command }
                   { type: "done" }
                   { type: "error",            message }
```

`question` / `question_response` cover Claude Code's **AskUserQuestion** tool — a
multiple-choice prompt the client renders as selectable options, sending the
pick back as `answers` (keyed by question text; a label, or array of labels for
`multiSelect`). Claude only.

An `approval_request` with `auto_approved: true` was answered by a session toggle;
the client renders it as a marker instead of Allow / Deny buttons, and the paired
`approval_response` carries `auto: true`.

## Notes

- **Container-only by design.** There is no Gradle wrapper (`gradlew`); the
  pinned Gradle version lives solely in the `Containerfile`. Build through
  `make`, not on the host.
- SDK level, build-tools, and Gradle versions are all `ARG`s at the top of the
  `Containerfile` — change them in one place.
- Cleartext HTTP is enabled (`usesCleartextTraffic`) so plain `http://` LAN
  backends work; flip the TLS switch in Settings for `https`/`wss`.
