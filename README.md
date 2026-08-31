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
  Scoped to the project, so there is no path to type, and the name arrives
  pre-filled with a generated `adjective-noun` suggestion — creating a session
  is two taps unless you want to name it yourself. Suggestions are not checked
  for uniqueness; sessions are identified by id and duplicate names are fine.
- **Git worktrees** — a toggle in the new-session dialog, shown for projects
  that are git repositories, runs the session in its own worktree on a new
  branch instead of sharing the project directory. Both the directory (a
  sibling of the project, `…/app-fix-login`) and the branch (`fix-login`) are
  seeded from the session name and editable, after which they stop tracking it.
  The worktree is created as part of creating the session: if git refuses, the
  dialog stays open with git's reason and **no session is created**. Such
  sessions are tagged `WORKTREE` in the list and show their directory.
  Deleting one removes the worktree too — but never with `--force`, so a
  worktree holding uncommitted *or untracked* files is left on disk and
  reported. That is the common outcome for a session that did any work, and it
  reads as a notice rather than an error.
- **Live transcript** — streamed agent output, collapsible tool-use cards,
  inline approval prompts (Allow / Deny), and multiple-choice question prompts
  (Claude's AskUserQuestion) over a WebSocket that auto-reconnects.
- **Auto-approve** — per-session toggles in session settings to skip the approval
  prompt for writes and/or shell commands; auto-approved tools still appear in the
  transcript, marked as such. Reads always run.
- **Composer** — send prompts. Stays usable while the agent is running: a prompt
  sent mid-turn is refused with a toast, but a command still goes through.
- **Bash mode** — a message starting with `!` runs as a shell command in the
  session's working directory instead of going to the agent, and its output comes
  back in a red-bordered card the agent never sees. `\!` sends a prompt that
  really does start with an exclamation mark. While a `!` line is being typed the
  composer turns red and switches to a monospace keyboard with sentence
  capitalisation and suggestions off.
- **Background notifications** — toggle the bell on a session to watch it from a
  foreground service. You get a high-priority notification when the task finishes
  or needs your approval, even with the app off-screen; the watch stops once the
  turn ends and is silent for whichever session you're currently viewing. The
  opt-in is remembered per session **per server address**: ids are only unique
  within one backend, so pointing the app elsewhere starts from a clean set
  rather than inheriting whatever wore the same id there.
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
| `Json.java`                | id decoding — server ids are JSON numbers, held as opaque strings (pure, unit tested) |
| `NameGenerator.java`       | `adjective-noun` session-name suggestions from `res/raw` word lists |
| `Composer.java`            | the `!` / `\!` split — prompt or shell command (pure, unit tested) |
| `Worktree.java`            | worktree directory + branch seeds from a session name (pure, unit tested) |
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
`POST /sessions/{id}/stop`. There is no worktree endpoint: a worktree is
created and destroyed as part of the session that owns it.

**Ids.** `projects.id`, `sessions.id` and `project_id` are JSON **numbers** —
they were uuid strings until a server migration renumbered every row. The app
decodes them through `Json.id` and then treats them as opaque strings
everywhere: URL segments, Intent extras, map keys. Never parse one back, order
two, or truncate one for display. The ids minted elsewhere — `request_id`,
`agent_session_id`, and the `id` inside an approval option — are strings on
every server and stay that way. Session routes type their `{id}` as an int, so
a malformed one is a **422** (our bug) rather than the 404 that means the
session is gone; ids are never reused, so a cached one is either valid or gone,
never a different session.

`GET /projects` returns
`[{ id, path, name, exists, is_git_repo, session_count, last_active_at }]`, where
`last_active_at` is `null` for a project with no sessions yet, `exists` reports
whether the directory is still on the server's disk, and `is_git_repo` is a hint
for whether to offer the worktree toggle. There are no nested project routes:
`GET /sessions` carries `project_id` on every row, so the client filters
locally — on the id, not the path, because a worktree session's `working_dir`
is somewhere else entirely. A **404 means an older server** — the app then falls
back to the unscoped session list, i.e. its pre-projects behaviour, and to
matching sessions by `working_dir`.

`POST /projects` body: `{ path, name }` — path absolute; creates the directory
and the row, so a new project lists immediately. On a 404 the app opens the
project anyway and lets the first session's `mkdir -p` create the directory.

`DELETE /projects` body: `{ path }` — removes the project and its sessions,
leaving the directory alone. The path is in the body, not the URL, so no
filesystem path has to be encoded into a path segment. The response adds
`worktrees_removed` and `worktree_errors: [{ session, path, error }]` for the
sessions that owned one.

`POST /sessions` body: `{ name, project_path, agent, worktree? }`, where
`worktree` is `{ path, branch }`. The project must already exist — an unknown
`project_path` is a 404, not an adopted project. With a `worktree` block the
server creates the worktree first and rolls it back if the session insert
fails, so the two can never disagree about who owns the directory; any failure
along the way is a 400 and no session. The client also sends the path as
`working_dir`, the deprecated spelling of `project_path`, so the same request
works against a server on either side of the rename.

Sessions come back with `project_id`, `working_dir` (the cwd — the worktree
when there is one) and `owns_worktree` (the server created it and will remove
it).

`DELETE /sessions/{id}` returns `{ status, worktree_removed, worktree_error }`.
It is **200 even when `worktree_error` is set**: the session is deleted either
way, and removal is never forced, so a worktree with modified or untracked
files is reported and left alone.

`PATCH /sessions/{id}` body (all optional): `{ name, auto_approve_write,
auto_approve_command }` — rename and/or flip the per-session auto-approve toggles.

WebSocket: `ws(s)://<host>/ws/sessions/{id}`

```
client -> server : { type: "input", text }
                   { type: "bash",  command }
                   { type: "approval_response", request_id, behavior }
                   { type: "question_response", request_id, answers }

server -> client : { type: "status",           status }   # idle | running | awaiting_approval
                   { type: "input",            text }
                   { type: "output",           text }
                   { type: "bash_input",       command }
                   { type: "bash_output",      command, stdout, stderr, exit_code,
                                               duration_ms, timed_out, truncated }
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

`bash` runs a shell command in the session's working directory, bypassing the
agent entirely — no tokens, no context, no approval. The `!` split is the
client's: the server never inspects prompt text, which is what keeps a prompt
beginning with `!` sendable. A command **never takes the turn lock**, so it runs
while the agent is working and the composer is deliberately not gated on session
status; the echo (`bash_input`) and the result (`bash_output`, which may arrive
much later) render as one card. `exit_code` is null when the command never
started. A second command while one is in flight comes back as an `error`.

## Notes

- **Container-only by design.** There is no Gradle wrapper (`gradlew`); the
  pinned Gradle version lives solely in the `Containerfile`. Build through
  `make`, not on the host.
- SDK level, build-tools, and Gradle versions are all `ARG`s at the top of the
  `Containerfile` — change them in one place.
- Cleartext HTTP is enabled (`usesCleartextTraffic`) so plain `http://` LAN
  backends work; flip the TLS switch in Settings for `https`/`wss`.
