# Agent UI — Android

A native Android client for controlling Claude Code agents. It talks to the
[agent-ui-server](https://github.com/rauaap/agent-ui-server) backend over REST for managing
sessions and a WebSocket per session for the live transcript.

The build is fully containerized and CLI-driven — no JDK, Android SDK, or Gradle
needed on the host. The only host dependency is `podman` (and `adb`, if you want
to install on a device).

## Features

- **Project list** — the launcher screen. A project is a working directory,
  stored server-side; each card shows its session count and last activity.
  Creating one takes a name and a directory: the directory tracks the name under
  the projects directory from Settings until you edit it by hand, after which the
  two are independent. Deleting a project removes it and its sessions but
  **never touches the disk**. A project whose directory has since been removed
  is flagged `MISSING`, and opening it offers to forget it.
- **Session list** — the sessions inside one project: create, open, and delete.
  Scoped to the project, so a new session takes a name and nothing else — no
  path to type.
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
- **Settings** — the server host / port (and optional TLS) plus the projects
  directory are stored in `SharedPreferences`, so they **persist across app
  restarts and device reboots**. Set them via the ⚙ button on the project list.

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
| `ProjectListActivity.java` | launcher screen: list projects, open or start one |
| `SessionListActivity.java` | one project's sessions: list / create / delete |
| `SessionActivity.java`     | per-session transcript + composer + WebSocket |
| `SessionSettingsActivity.java` | per-session settings: rename, notification opt-in, auto-approve toggles |
| `SettingsActivity.java`    | server address + projects directory form (persisted) |
| `WatchService.java`        | foreground service: per-session WebSocket watch + task-completion notifications |
| `Prefs.java`               | `SharedPreferences`-backed server config |
| `Api.java`                 | OkHttp REST client for `/projects` + `/sessions` |
| `Session.java` / `Project.java` | session and project models |
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

REST: `GET /projects`, `POST /projects`, `DELETE /projects`, `GET /sessions`,
`POST /sessions`, `PATCH /sessions/{id}`, `DELETE /sessions/{id}`,
`POST /sessions/{id}/stop`.

`GET /projects` returns `[{ path, name, exists, session_count, last_active_at }]`,
where `last_active_at` is `null` for a project with no sessions yet and `exists`
reports whether the directory is still on the server's disk. There are no nested
project routes: `GET /sessions` carries `working_dir` on every row, so the client
filters locally. A **404 means an older server** — the app then falls back to the
unscoped session list, i.e. its pre-projects behaviour.

`POST /projects` body: `{ path, name }` — path absolute; creates the directory
and the row, so a new project lists immediately. On a 404 the app opens the
project anyway and lets the first session's `mkdir -p` create the directory.

`DELETE /projects` body: `{ path }` — removes the project and its sessions,
leaving the directory alone. The path is in the body, not the URL, so no
filesystem path has to be encoded into a path segment.

`POST /sessions` body: `{ name, working_dir, agent }`. Creating a session is
also what creates the project directory (`mkdir -p` server-side).

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
