# Agent UI — Android

A native Android client for controlling Claude Code agents. It talks to the
[agent-ui-server](https://github.com/rauaap/agent-ui-server) backend over REST for managing
sessions and a WebSocket per session for the live transcript.

The build is fully containerized and CLI-driven — no JDK, Android SDK, or Gradle
needed on the host. The only host dependency is `podman` (and `adb`, if you want
to install on a device).

## Server authentication

On first launch, Settings asks for the server address and **Server token**.
Copy the token from `~/.config/agent-ui-server/token` on the server. The field
supports paste and show/hide; surrounding whitespace is removed on save.
Saving checks `GET /agents`: a rejected token is not saved, and **Save anyway**
is offered only when the server cannot be reached.

The token is stored in app-private preferences excluded from cloud backup and
device transfer. REST and WebSocket handshakes use an Authorization header,
never a URL parameter. Redirects are not followed. If the server rotates its
token, Settings reopens and reconnect attempts pause until a replacement is
saved. The previous token remains stored until then. Install this client and
enter the token before enabling server authentication.

Device smoke checks before release:
- Fresh install: Settings opens without any API traffic.
- Correct token: projects, usage, sandbox paths, session and file sockets work.
- Wrong token: “Token rejected”; existing preferences remain unchanged.
- Offline save: only network errors offer “Save anyway”.
- Rotate the server token, including while watching a session in the background:
  Settings opens on return; no reconnect traffic continues until replacement.
- Restart the server: both sockets and background watches reconnect.
- Restore a backup or transfer devices: the token must be entered again.
- Check device logs for credentials; tokens must never appear there.

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
  sent mid-turn is refused with a toast, but a command still goes through. Up and
  Down keys walk the session's prompt and command history when a keyboard offers
  them; otherwise, hold the composer and drag vertically. A stationary hold and
  release retains the normal text-selection menu, and moving past the newest
  history entry restores the unsent draft.
- **Bash mode** — a message starting with `!` runs as a shell command in the
  session's working directory instead of going to the agent, and its output comes
  back in a red-bordered card the agent never sees. `\!` sends a prompt that
  really does start with an exclamation mark. While a `!` line is being typed the
  composer turns red and switches to a monospace keyboard with sentence
  capitalisation and suggestions off. The live file tree is available from the
  floating **‹** handle in every mode: it opens an overlay file panel whose
  bottom search uses component-prefix matching and ranking. Tapping a result
  inserts it into prompts, or safely shell-escapes it into the token at the
  cursor in a command.
- **Background notifications** — enabled by default for sessions created in the
  Android app; disable them in session settings. A foreground service watches
  enabled sessions. You get a high-priority notification when the task finishes
  or needs your approval, even with the app off-screen; the watch stops once the
  turn ends and is silent for whichever session you're currently viewing. The
  preference is remembered per session **per server address**: ids are only unique
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
- **Subscription usage** — the **U** button on the project list opens a panel of
  meters: how much of each plan's five-hour and weekly quota is already spent,
  what it resets to zero at, and how long that is away. The two rows are
  **subscriptions, not agents** — `claude_code` and `codex` deliberately do not
  match the agent ids, because one plan can back several harnesses, so nothing
  here maps a session's agent onto a quota. Each plan is read independently: one
  that is unauthenticated is shown as unconfigured while the other still reports
  its numbers, and a rejected token says to re-authenticate on the server, which
  does not refresh them. A plan that fails *transiently* keeps the last good
  numbers, tagged `STALE` with the time they were read — but drops its
  countdown, because Codex's five-hour window rolls and a cached reset time
  would quietly drift. Every call queries both providers live, so the panel
  refreshes about once a minute while open, and every countdown is recomputed
  from the reset time that arrived with that read rather than ticked down
  locally. A server without the endpoint says so.
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

- **Sandbox paths** — open **Settings ▸ Server sandbox paths** for shared defaults,
  or **Project settings ▸ Project sandbox paths** for additions and overrides.
  Server defaults use `GET /sandbox-paths` and `PATCH /sandbox-paths` with
  `{"sandbox_paths": [...]}`; project lists use `GET /projects` and `PATCH /projects`.
  Add/edit server file or directory paths and an **Allow writes** flag (off for new
  entries), then **Save paths** to replace that scope's list atomically. Remote
  paths are saved separately from device preferences; save a changed server
  address before opening the editor. Project settings show inherited defaults;
  removing an override restores its default, and resetting the whole project list
  restores all inheritance. Exact-text overrides are marked in the UI; equivalent
  spellings such as `~/config` and `$HOME/config` are matched only by the server.
  Path expansion and validation happen on the server, and errors retain the draft.
  Changes affect future sandboxed turns, never running processes. Read-only paths
  can expose credentials; writable paths permit changes or deletion of host data.

## Layout

```
Containerfile          Fedora + JDK 25 + Android SDK + Gradle toolchain
Makefile               build / shell / install targets (podman wrapper)
RELEASING.md           signing, versioning, and release workflow
scripts/               release and signing-key helpers (run in the container)
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
| `ProjectSettingsActivity.java` | per-project settings: sandbox paths, archive / unarchive |
| `SettingsActivity.java`    | server address + projects directory form (persisted) + the way in to Archived |
| `SandboxPathsActivity.java` | shared server/project sandbox-path editor with inheritance and atomic saves |
| `SandboxPath.java` | original path strings, permissions, and whole-list payloads |
| `ArchivedActivity.java`    | everything archived: projects, then the sessions under live projects, each with Restore |
| `UsageActivity.java`       | subscription usage: a meter per plan per window, polled about once a minute |
| `Usage.java`               | the `/usage` response: windows, error classification, and carrying last good values across a transient failure (pure, unit tested) |
| `Meter.java`               | the usage bar — severity-coloured fill on a wash of the same colour |
| `WatchService.java`        | foreground service: per-session WebSocket watch + task-completion notifications |
| `Prefs.java`               | `SharedPreferences`-backed server config |
| `Archive.java`             | live/archived split of the two list responses, and the archive's ordering (pure, unit tested) |
| `Api.java`                 | OkHttp REST client for `/projects` + `/worktrees` + `/sessions` |
| `Session.java` / `Project.java` / `Worktree.java` | session, project and worktree models |
| `Agent.java`               | the server's agent list: labels, the default to preselect, the older-server fallback (pure parts unit tested) |
| `Json.java`                | id decoding — server ids are JSON numbers, held as opaque strings (pure, unit tested) |
| `NameGenerator.java`       | `adjective-noun` session-name suggestions from `res/raw` word lists |
| `Composer.java`            | the `!` / `\!` split — prompt or shell command (pure, unit tested) |
| `MessageHistory.java`      | prompt/command recall and draft restoration (pure, unit tested) |
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
make release            # signed, auto-versioned APK in dist/ (see RELEASING.md)
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

REST: `GET /agents`, `GET /usage`, `GET /projects`, `POST /projects`, `PATCH /projects`,
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

`GET /usage` returns `{ claude_code: {...}, codex: {...} }`, each
`{ five_hour, weekly, error }` where a window is `{ used_percent, reset_at }` or
`null`. `used_percent` is the share already **spent**, 0–100, rendered as it
arrives; `reset_at` is Unix seconds and may be `null` on an otherwise successful
read, which means the countdown is unavailable — never an epoch date to show.
**Both keys are always present**, and a plan that is unauthenticated or
unreachable reports null windows and a reason rather than failing the request,
so each is read on its own and a non-null `error` never condemns the response.
The app classifies the reason: `"not authenticated"` is unconfigured,
`"HTTP 401"` needs a human to re-authenticate on the server, and `"HTTP <code>"`,
`"unreachable: …"` — and anything a later server invents — are transient, which
keeps the last good numbers on screen under a `STALE` tag until the next poll.
**The keys are plans, not agents**: they intentionally differ from the ids in
`GET /agents`, one subscription can back several harnesses, and a session's
agent does not determine the quota it draws from. Every call queries both
upstream providers live with no server-side cache, so the app polls on the order
of a minute and only while the panel is open. Weekly `reset_at` is a fixed
window, but Codex's five-hour one is **rolling** and moves on every poll until
usage starts — so it is re-read rather than cached and counted down locally. A
**404 means an older server**, and the panel says so instead of retrying.

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

The session screen also holds the server-only
`ws(s)://<host>/ws/sessions/{id}/files` socket while visible. Its authoritative
`file_tree_snapshot` and ordered `file_tree_patch` frames feed Bash path
completion without per-keystroke requests. A generation/revision mismatch drops
that cache and reconnects for a snapshot; an error frame displays its supplied
message and waits for manual retry. The cache is invalidated whenever the socket
is disconnected.

```
client -> server : { type: "input", text }
                   { type: "bash",  command }
                   { type: "approval_response", request_id, option_id }
                   { type: "approval_response", request_id, behavior } # allow | deny
                   { type: "question_response", request_id, answers }

server -> client : { type: "status",           status }   # idle | running | awaiting_approval
                   { type: "input",            text }
                   { type: "output",           text }
                   { type: "bash_input",       command }
                   { type: "bash_output",      command, stdout, stderr, exit_code,
                                               duration_ms, timed_out, truncated }
                   { type: "tool_use",         call_id, action }
                   { type: "approval_request", request_id, call_id, action, options,
                                               auto_approved? }
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

Tool calls and approvals carry the same provider-neutral canonical `action`,
discriminated by `action.kind`: `command`, `read`, `edit`, `write`, `search`,
`list`, `web`, `task`, or `other`. Their opaque `call_id` correlates one
invocation with its approval. Historical rows without an action remain visible
as raw JSON but are not interpreted or actionable.

An `approval_request` with `auto_approved: true` was answered by a session toggle;
the client renders it as a marker instead of Allow / Deny buttons, and the paired
`approval_response` carries `auto: true`. Named `options` are answered by
`option_id`; an empty options array produces generic Allow / Deny controls that
send `behavior`.

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
