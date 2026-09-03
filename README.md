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
- **Agent picker** — the new-session dialog offers the agents the *server* says
  it can run (`GET /agents`), preselecting the one it flags as default, so an
  agent added or removed backend-side shows up here without an app release. A
  server too old to have the endpoint falls back to the two the app used to
  hardcode. The session cards label each agent through the same list, so an id
  the server no longer offers is still shown, as itself.
- **Git worktrees** — a worktree is a project's, not a session's: it is created
  and removed on its own, any number of sessions can run in one, and it outlives
  all of them. The new-session dialog picks between the project directory (the
  default) and any of the project's worktrees, and can create one on the spot;
  such sessions are tagged `WORKTREE` in the list and show their directory. The
  **Worktrees** row on the session list opens the project's worktrees, where
  they are created and cleaned up. Creating one takes a branch — always cut
  fresh from the project's current HEAD — and a directory, seeded by expanding
  the path template from Settings and tracking the branch until you edit it by
  hand; **Reset** ties it back. Removal is never forced, so a worktree holding
  uncommitted *or untracked* files is left in place and said so, which is the
  common outcome for one an agent worked in. Deleting a session removes nothing
  from disk.
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
- **Settings** — the server host / port (and optional TLS), the projects
  directory, and the worktree path template are stored in `SharedPreferences`,
  so they **persist across app restarts and device reboots**. Set them via the ⚙
  button on the project list. The template takes `%P` (the project's parent
  directory), `%N` (the project directory's own name), `%B` (the branch with
  slashes flattened to dashes) and `%b` (the branch verbatim); the default
  `%P/%N-%B` puts the worktree beside the project, and a live example shows what
  it expands to. It is expanded entirely client-side — the server only ever
  receives a finished absolute path.

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
| `SessionListActivity.java` | one project's sessions: list / create / delete, and the worktree picker |
| `WorktreeListActivity.java` | one project's worktrees: list / create / remove |
| `WorktreeForm.java`        | the create-worktree dialog, shared by the picker and that list |
| `SessionActivity.java`     | per-session transcript + composer + WebSocket |
| `SessionSettingsActivity.java` | per-session settings: rename, notification opt-in, auto-approve toggles |
| `SettingsActivity.java`    | server address + projects directory form (persisted) |
| `WatchService.java`        | foreground service: per-session WebSocket watch + task-completion notifications |
| `Prefs.java`               | `SharedPreferences`-backed server config |
| `Api.java`                 | OkHttp REST client for `/projects` + `/sessions` |
| `Session.java` / `Project.java` / `Worktree.java` | session, project and worktree models |
| `Agent.java`               | the server's agent list: labels, the default to preselect, the older-server fallback (pure parts unit tested) |
| `Json.java`                | id decoding — server ids are JSON numbers, held as opaque strings (pure, unit tested) |
| `NameGenerator.java`       | `adjective-noun` session-name suggestions from `res/raw` word lists |
| `Composer.java`            | the `!` / `\!` split — prompt or shell command (pure, unit tested) |
| `WorktreePath.java`        | the path template, its expansion, and lexical path normalising (pure, unit tested) |
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

REST: `GET /agents`, `GET /projects`, `POST /projects`, `DELETE /projects`, `GET /worktrees`,
`POST /worktrees`, `DELETE /worktrees/{id}`, `GET /sessions`, `POST /sessions`,
`PATCH /sessions/{id}`, `DELETE /sessions/{id}`, `POST /sessions/{id}/stop`.

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

`GET /agents` returns `[{ id, name, default }]` in the server's registration
order — the `id` to send back as `agent`, a label to show, and which one to
preselect. It is the server's own registry, so the app keeps no list of agents:
it renders what it is given and sends an id back. Fetched with the session list
rather than once per process, since the answer belongs to whichever server the
address in Settings currently points at. A **404 means an older server**, and so
does any other failure as far as the picker is concerned: it falls back to
`claude-code` / `opencode`, the two the app used to hardcode, which are exactly
the agents a server without this endpoint has. An `agent` id no longer in the
list — a session that outlived an adapter — renders as itself.

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

`DELETE /projects` body: `{ path }` — removes the project, its sessions and its
worktrees, leaving the project's own directory alone. The path is in the body,
not the URL, so no filesystem path has to be encoded into a path segment. The
response adds `worktrees_removed` and `worktree_errors: [{ path, error }]` for
the worktrees git refused to remove; it is always a 200 and the rows are gone
regardless, so those directories are reported as left in place.

**Worktrees.** A worktree is its own resource, not something a session owns.
`GET /worktrees?project_path=…` returns
`[{ id, project_id, path, branch, created_at, session_count, exists }]`, newest
first, where `branch` is the branch the worktree was *created on* (null for one
carried over by the server's migration, and never live state — an agent can
switch branches in it), `session_count` may be `0` without anything being wrong,
and `exists` is a `stat` of the directory at request time. A **404 means an
older server**, one that only made worktrees as part of a session; the app then
hides the picker and the worktree screen entirely.

`POST /worktrees` body: `{ project_path, path, branch }` — runs
`git worktree add -b <branch> <path>`, always cutting a **new** branch off the
project's current HEAD. `path` must be absolute; the app resolves a relative one
against the project directory first, since the server has no notion of one.
Paths are normalised lexically on arrival, which is why the app normalises the
same way before comparing. A **409** means a worktree is already there — routine,
since a template maps a branch to the same path every time — and the body has no
id, so the app looks the worktree up by path and offers it instead. Everything
else is a 400 carrying git's own message, which is passed through unedited.

`DELETE /worktrees/{id}` removes the directory and the row, **never** with
`--force`. Two 409s, neither of them a failure to show as an error: sessions
still attached (the server names them), or a dirty tree — and git counts
untracked files as dirty, so this is the common outcome. Both leave the row
intact, so the list is re-rendered rather than dropped optimistically. For a
worktree whose directory is already gone the call succeeds and tidies the row
away, which is offered as "clean up" rather than delete.

`POST /sessions` body: `{ name, project_path, agent, worktree_id? }`, where
`agent` is one of the ids from `GET /agents`. The
project must already exist — an unknown `project_path` is a 404, not an adopted
project. `worktree_id` is optional; omitted, the session runs in the project
directory. A stale one is a 404 and one from another project a 400, and no
session is created either way. The client also sends the path as `working_dir`,
the deprecated spelling of `project_path`, so the same request works against a
server on either side of the rename.

Sessions come back with `project_id`, `working_dir` (the cwd, computed
server-side) and `worktree_id` (null when they run in the project directory).

`DELETE /sessions/{id}` returns `{ status }` and touches nothing on disk: the
worktree belongs to the project and stays, along with any other session in it.

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
