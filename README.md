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
  it can run (`GET /agents`). Settings can choose the agent preselected for every
  new session, or defer to the one the server flags as default. If that preferred
  adapter is unavailable, the picker safely falls back to the server default. An
  agent added or removed backend-side shows up without an app release. A server
  too old to have the endpoint falls back to the two the app used to hardcode.
  Session cards label each agent through the same list, so an id the server no
  longer offers is still shown, as itself.
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
- **Archive** — file a session or a whole project away without deleting it.
  The archive lives **server-side** (`archived_at`, a timestamp rather than a
  flag), so it is the same on every device and a change made elsewhere arrives
  over the session's WebSocket. Archive a session from its ⚙ settings, or a
  project from the project's ⚙ settings — the latter is one call that
  **cascades** to every session in it, and unarchiving restores exactly the ones
  that cascade took, so a session you archived by hand beforehand stays
  archived. Archived work is **read-only, not sealed**: the transcript still
  opens and replays, and renaming, the auto-approve toggles and deleting all
  still work — only new prompts and shell commands are refused, so the composer
  is replaced by a notice with an Unarchive button. An archived project offers
  no **+ New**. Archiving is refused while a session is busy, and a busy session
  makes a whole project archive fail without writing anything. Everything
  archived is under **Settings ▸ Archived**, newest filing first, where a row
  opens what it points at and **Restore** puts it back. The main lists still
  admit to what they hide ("2 sessions · 1 archived"). Two things archiving is
  deliberately not: a shield against deletion — deleting a project still takes
  its archived sessions with it, and says so — and a cleanup. Worktrees are a
  project's, hold real uncommitted work, and are left exactly where they are;
  an archived project's still list and can still be removed, but no new one can
  be cut in it. An archived session may later **detach** from its worktree in
  Session settings. This releases only the database reference and preserves the
  same cwd as a `FORMER WORKTREE`; deleting that worktree later can make the
  session impossible to restore until the exact directory is recreated.
- **Settings** — the server host / port (and optional TLS), the projects
  directory, and the worktree path template are stored in `SharedPreferences`,
  so they **persist across app restarts and device reboots**. Set them via the ⚙
  button on the project list. The ⚙ always opens the settings for what is on
  screen: the server's from the project list, a project's from its session list,
  a session's from its transcript. The template takes `%P` (the project's parent
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
| `SessionSettingsActivity.java` | per-session settings: rename, notification opt-in, auto-approve toggles, archive |
| `ProjectSettingsActivity.java` | per-project settings: archive / unarchive the project and its sessions |
| `SettingsActivity.java`    | server address + projects directory form (persisted) + the way in to Archived |
| `ArchivedActivity.java`    | everything archived: projects, then the sessions under live projects, each with Restore |
| `WatchService.java`        | foreground service: per-session WebSocket watch + task-completion notifications |
| `Prefs.java`               | `SharedPreferences`-backed server config |
| `Archive.java`             | live/archived split of the two list responses, and the archive's ordering (pure, unit tested) |
| `Api.java`                 | OkHttp REST client for `/projects` + `/worktrees` + `/sessions` |
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

REST: `GET /agents`, `GET /projects`, `POST /projects`, `PATCH /projects`,
`DELETE /projects`, `GET /worktrees`, `POST /worktrees`, `DELETE /worktrees/{id}`,
`GET /sessions`, `POST /sessions`, `PATCH /sessions/{id}`, `DELETE /sessions/{id}`,
`POST /sessions/{id}/detach-worktree`, `POST /sessions/{id}/stop`.

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
does any other failure as far as the picker is concerned: it falls back to the
server's default `claude-code` agent rather than advertising an optional adapter
whose availability it cannot verify. An `agent` id no longer in the list — a
session that outlived an adapter — renders as itself.

`GET /projects` returns
`[{ id, path, name, exists, is_git_repo, archived_at, session_count,
archived_session_count, last_active_at }]`, where
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

`PATCH /projects` body: `{ path, archived }` — archives or unarchives the
project, addressed by path in the body for the same reason `DELETE` is. The
response is the project row plus `sessions_affected`, how many sessions the
cascade archived or restored; it can legitimately be `0`, and on an unarchive it
is **not** the same as `archived_session_count`, because sessions archived by
hand beforehand stay archived. While archiving, a **409 means a session is busy**
and *nothing was written* — its `detail` names them. While unarchiving, a 409
means one or more effective working directories are unavailable; again nothing
is restored, and those exact absolute paths must be recreated before retrying.

`DELETE /projects` body: `{ path }` — removes the project, its sessions
(archived ones included) and its worktrees, leaving the project's own directory
alone. The path is in the body, not the URL, so no filesystem path has to be
encoded into a path segment. The response adds `worktrees_removed` and
`worktree_errors: [{ path, error }]` for the worktrees git refused to remove; it
is always a 200 and the rows are gone regardless, so those directories are
reported as left in place.

**Worktrees.** A worktree is its own resource, not something a session owns.
`GET /worktrees?project_path=…` returns
`[{ id, project_id, path, branch, created_at, session_count, exists }]`, newest
first, where `branch` is the branch the worktree was *created on* (null for one
carried over by the server's migration, and never live state — an agent can
switch branches in it), `session_count` counts attached sessions only and may be
`0` without anything being wrong, and `exists` is a `stat` of the directory at
request time. A **404 means an
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
Creating one **in an archived project is a 409**, so the app withholds the
control there rather than let it fail.

`DELETE /worktrees/{id}` removes the directory and the row, **never** with
`--force`. Three 409s leave both intact: attached sessions, live detached
sessions still using its path, or a dirty tree — and git counts untracked files
as dirty, so the last is common. The server names blocking sessions. Before the
request, the app also checks the complete session listing for archived detached
sessions whose preserved `working_dir` equals the worktree path and warns that
removal prevents restoring them until the exact directory is recreated. For a
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

Sessions come back with `project_id`, `working_dir` (their effective cwd) and
`worktree_id`. A null worktree id means either the project directory, or a
former worktree after explicit detachment; comparing `working_dir` with the
project path distinguishes them.

`DELETE /sessions/{id}` returns `{ status }` and touches nothing on disk: the
worktree belongs to the project and stays, along with any other session in it.

`PATCH /sessions/{id}` body (all optional): `{ name, auto_approve_write,
auto_approve_command, archived }` — rename, flip the per-session auto-approve
toggles, and/or archive it. Only the supplied fields are applied, so
`{ archived: true }` alone disturbs nothing else. Unarchiving a session also
unarchives its project — a live session under an archived project would have
nowhere to show — so the app refetches afterwards. While archiving, a **409 means
the session is busy**, which `status` alone cannot predict: a shell command from
bash mode runs outside the turn state machine. While unarchiving, a 409 means its
effective cwd is unavailable and must be recreated at the same absolute path.

`POST /sessions/{id}/detach-worktree` is available only to archived sessions.
It clears `worktree_id` but preserves `working_dir`, touches nothing on disk,
and is idempotent after a successful detach. It is offered later in Session
settings, never automatically or as part of archive.

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
                   { type: "archived",         archived_at }   # null = brought back
                   { type: "worktree_detached", worktree_id: null, working_dir }
                   { type: "done" }
                   { type: "error",            message }
```

`archived` is sent on every change **and again on connect**, right after
`status`, so a session archived from another device is picked up even if this
client was offline when it happened. It is treated like `status` —
authoritative, idempotent, safe to receive when nothing changed — and drives
whether the composer is a composer or the archived notice. An `input` or `bash`
frame sent for an archived session comes back as an ordinary
`{ type: "error", message: "Session is archived" }`, which is exactly why the
composer is taken away rather than left to collect text that can only bounce.
`worktree_detached` updates the cwd and location display but is not replayed on
connect, so the unfiltered REST session listing remains authoritative after an
offline detach; duplicate events from retries are harmless.

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
