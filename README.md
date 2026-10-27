# Agent UI — Android

A native Android client for controlling Claude Code agents. It talks to the
[agent-ui-server](https://github.com/rauaap/agent-ui-server) backend over REST for managing
sessions and a WebSocket per session for the live transcript.

The build is fully containerized and CLI-driven — no JDK, Android SDK, or Gradle
needed on the host. Host build dependencies are `make` and `podman` (and `adb`
for device installation). The version-bump helper and tooling tests also need
Python 3.

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
  agent added or removed backend-side shows up without an app release. If the
  request fails, the dialog does not open and says why. Session cards label
  each agent through the same list, so an id the server no longer offers is
  still shown, as itself.
- **Model picker** — below the agent, the new-session dialog offers the models
  listed with that agent in `GET /agents`, which the server discovers once at
  startup. There is no "default" entry: the catalog's first model is
  preselected, and changing the agent starts over from that agent's first,
  since model ids mean nothing across harnesses. Every session is created with
  an explicit model. If an agent has no usable catalog — failed discovery on
  the server, or none found — the dialog shows why and disables Create for
  that agent; other agents are unaffected. It never falls back to letting the
  harness choose. The model is fixed when the session is created, and session
  cards show it by its catalog name.
- **Reasoning level** — below the model, the new-session dialog offers that
  model's `reasoning_levels`, in the harness's own words and order, behind a
  **Default** that sends `reasoning_level: null` and leaves the choice to the
  harness. It resets to Default whenever the model changes, and is hidden for a
  model with no levels. Session settings show the current level (Default when
  null) and change it with `PATCH /sessions/{id}`, effective from the next turn;
  only real levels are offered, since a set level cannot go back to Default.
  Changes from other devices arrive as `reasoning_level` WebSocket events.
  Session cards show a set level after the model.
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
  common outcome for one an agent worked in. Removing a worktree preserves all
  sessions and their original directory, clearing their worktree association.
  `FORMER WORKTREE` and `MISSING` are independent: recreating the directory clears
  only `MISSING`. Busy agent turns or shell commands prevent removal.
  Deleting a session removes nothing from disk.
- **Live transcript** — streamed agent output, collapsible tool-use cards,
  inline approval prompts (Allow / Deny), and multiple-choice question prompts
  (Claude's AskUserQuestion) over a WebSocket that auto-reconnects.
- **Auto-approve** — per-session toggles in session settings to skip the approval
  prompt for writes and/or shell commands; auto-approved tools still appear in the
  transcript, marked as such. Reads always run.
- **Composer** — send prompts. Stays usable while the agent is running: a prompt
  sent mid-turn is queued by the server for the next turn and waits, marked
  **QUEUED**, above the composer — out of the transcript, so it never splits a
  streaming reply — until the server hands it to a turn. Messages from other
  agents queue the same way. A command runs immediately as always. Up and
  Down keys walk the session's prompt and command history when a keyboard offers
  them; otherwise, hold the composer and drag vertically. A stationary hold and
  release retains the normal text-selection menu, and moving past the newest
  history entry restores the unsent draft. Composer text and successfully uploaded
  attachment references survive reopening, scoped to the server and session.
- **Image attachments** — the inside-right paperclip appears for image-capable session models
  in prompt mode. Selecting JPEG, PNG, GIF or WebP immediately uploads original
  bytes without resizing/recompression, with preview, progress/error state and
  corner **×** removal. Compact aspect-preserving 88×66dp previews sit inside the
  composer/message bubble and round the actual image corners to 10dp. Tap a preview
  in the transcript, queue or composer to open a full-screen, fit-to-screen image
  dialog; close it with **×** or Back. Original cached/selected bytes are decoded
  at screen resolution off the UI thread, including pending/failed selections.
  Image-only prompts work; Send refuses unfinished or failed attachments.
  Limits are 10 MiB per image, 10 images per message, and a server-enforced 20 MiB
  image-occurrence budget for the queued turn. Pending/replayed images load into
  fixed placeholders asynchronously. A shared 100 MiB disk cache is keyed by full
  server download URL and seeded after upload, avoiding redundant downloads;
  misses use authenticated GET. Removing/sending clears draft references, not
  cached bytes or server uploads. Failed/in-progress uploads are not saved drafts.
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
- **Session visibility** — project session lists and Search share a compact
  single-row slider with unlabeled ticks and a linked exact day-count field.
  Slider presets are 1–7, 14, 30, 60, 90 days, and ∞ (all sessions, the initial
  setting). Typing a number preserves that exact cutoff; the slider moves to the
  first preset at least that large. Tapping or dragging the slider replaces the
  number with its preset, including ∞. The app remembers the selection.
  Hidden counts explain filtered results; session-name search matches bypass
  the inactivity cutoff. Projects and status summaries stay visible and complete;
  nothing is deleted or closed.
- **Retained sessions** — all projects and sessions remain in the catalog.
  Removing a worktree preserves attached sessions and their exact working
  directory as a `FORMER WORKTREE`. Missing paths are marked independently as
  `MISSING`; history and settings remain available, while attempts to run in a
  missing directory fail through the ordinary operation error path.
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

- **Sandbox network** — open **Settings ▸ Server sandbox network** beside sandbox
  paths, or **Project settings ▸ Project sandbox network** for project additions.
  Both scopes reuse the sandbox settings editor layout, navigation, card actions,
  explicit save, reset confirmation, and unsaved-change discard behavior.
  Load, add/edit/remove exact IPv4 + TCP port pairs, then **Save exceptions**.
  The server-wide list uses `GET/PATCH /sandbox-network` with
  `{"sandbox_network_allowlist": [{"ip": "100.64.0.10", "port": 443}]}`.
  Project own lists come from `GET /projects`; `PATCH /projects` replaces them
  with `path` and `sandbox_network_allowlist`. Creation accepts an optional list;
  omitted lists on create default to `[]`, and omission on update preserves them.
  Clearing and saving sends `[]`. The normalized response becomes the saved list;
  API errors retain the draft. Ports must be integers 1–65535; the server enforces
  unicast IPv4 policy (no hostnames, CIDRs, IPv6, loopback, unspecified, reserved,
  multicast, or sandbox DNS proxy address). Only the specified TCP port is exposed,
  even if Gitea and agent-ui-server share an IP; other ports and UDP stay blocked.
  Changes affect the next sandboxed turn for both agents, including worktree
  sessions, not running turns. Effective exceptions are the union of server and
  project IP/port pairs. Inherited server entries are read-only in the project
  editor and cannot be removed there. An empty project list means server
  inheritance only; an empty server list does not remove project exceptions.
  There is no per-session list.

- **Shared assets** — open **Settings ▸ Server shared assets** for global roots,
  or **Project settings ▸ Project shared assets** for project-associated roots.
  Add/edit (including rename)/delete registrations via `/shared-asset-roots`;
  changes save immediately. Identifiers use ASCII letters, digits, `_` and `-`;
  paths are absolute server paths and are not expanded or created. The server's
  normalized response is reloaded after saving. Registration does not grant
  sandbox access or copy files, and deletion never removes files. Renaming breaks
  existing links. Anyone who can reach the server can read registered files
  without authentication; project association only organizes settings.
  **Open** targets the root's `index.html` (404 if missing). Chat Markdown links
  beginning with `/shared-assets/` open against the saved server's origin,
  including its scheme and port; ordinary HTTP(S) links still open unchanged.

## Layout

```
Containerfile          Fedora + JDK 25 + Android SDK + Gradle toolchain
Makefile               build / shell / install targets (podman wrapper)
RELEASING.md           signing, versioning, and release workflow
.gitea/workflows/      release and GitHub mirror workflows
scripts/               version bump, release, signing, and publishing helpers
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
| `StreamDrafts.java`        | transient model-output drafts: strict frames, revisions, snapshots, source order (pure, unit tested) |
| `ToolPreview.java`         | display text for provisional tool arguments; never validated or actionable (pure, unit tested) |
| `SessionSettingsActivity.java` | per-session settings: rename, notification opt-in, reasoning level, auto-approve toggles |
| `ProjectSettingsActivity.java` | per-project settings: sandbox paths, network, shared assets |
| `SettingsActivity.java`    | server address + projects directory form (persisted) |
| `SandboxPathsActivity.java` | shared server/project sandbox-path editor with inheritance and atomic saves |
| `SandboxNetworkActivity.java` | shared server/project IPv4 + TCP port editor, read-only inheritance and atomic saves |
| `SharedAssetsActivity.java` | global/project shared-asset root CRUD, rename and browser Open |
| `SharedAssetRoot.java` | shared-asset registrations, scope filtering and request payloads |
| `MarkdownLinks.java` | selectable chat link tap handling and shared-asset URL resolution |
| `SandboxPath.java` | original path strings, permissions, and whole-list payloads |
| `UsageActivity.java`       | subscription usage: a meter per plan per window, polled about once a minute |
| `Usage.java`               | the `/usage` response: windows, error classification, and carrying last good values across a transient failure (pure, unit tested) |
| `Meter.java`               | the usage bar — severity-coloured fill on a wash of the same colour |
| `WatchService.java`        | foreground service: per-session WebSocket watch + task-completion notifications |
| `Prefs.java`               | `SharedPreferences`-backed server config |
| `Api.java`                 | OkHttp REST transport; reports successful responses without owning cache policy |
| `CatalogRepository.java`   | scoped catalog operations, existence ordering/retry, raw cache policy and background restoration |
| `CatalogLoader.java`       | screen-owned cache/network coordination, per-resource freshness and lifecycle cancellation |
| `CatalogJson.java`         | shared JSON decoders for network responses and cached catalogs |
| `ProjectSearch.java`       | indexed project/session ownership, normalized query matching and expansion state |
| `SearchResults.java`       | cached search matches and testable keyed result-row coordination |
| `ProjectStatus.java`       | shared project badge policy; search computes summaries when catalog data changes |
| `CatalogSnapshot.java` / `CatalogProgress.java` | completed refresh data and early catalog delivery |
| `ResourceCache.java`       | bounded, server/auth-scoped raw-response cache; readers decode independent models |
| `RefreshController.java`   | shared in-flight guard, queued action refresh and lifecycle callback cancellation |
| `Session.java` / `Project.java` / `Worktree.java` | session, project and worktree models |
| `Agent.java`               | the server's agent list: labels, the default to preselect, the older-server fallback (pure parts unit tested) |
| `Json.java`                | id decoding — server ids are JSON numbers, held as opaque strings (pure, unit tested) |
| `NameGenerator.java`       | `adjective-noun` session-name suggestions from `res/raw` word lists |
| `Composer.java`            | the `!` / `\!` split — prompt or shell command (pure, unit tested) |
| `MessageHistory.java`      | prompt/command recall and in-memory traversal draft restoration (pure, unit tested) |
| `SessionDraft.java`        | persistent composer text + uploaded image metadata scoped to server/session (pure, unit tested) |
| `ImageAttachment.java` / `ImageTransport.java` | ordered image metadata and raw binary upload/download contract (unit tested) |
| `Images.java` / `ImageDiskCache.java` | background image decoding and shared bounded disk cache keyed by full URL |
| `ImageViewer.java` / `SelectedImageFile.java` | dismissible full-screen image viewer and composer-owned original selection bytes |
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

Debug builds use the `.debug` application ID suffix and the label
`Agent UI (Debug)`, so debug and release builds can coexist on the same device
with separate app data. Debug version names have a `-debug` suffix.

Other targets:

```sh
make bump-version       # increment tracked versionCode before committing
make release            # signed APK for that version in dist/ (no bump)
make test               # app unit tests in the build container
make test-tooling       # tooling tests (Python 3; no container needed)
make clean              # gradle clean
make gradle ARGS="tasks"   # run any gradle task in the container
make shell              # interactive shell inside the build container
```

The Gradle cache is persisted in a named volume (`android-gradle-cache`) so
incremental builds and the debug keystore survive between runs.

## Releases and Gitea Actions

Run `make bump-version`, then `make release` to verify the build. Commit the
bumped `versionCode` with your changes and push to `main`. Release names are
derived as `<base versionName>.<versionCode>`; builds never change the version.
Local rebuilds replace their APK in `dist/`, but CI never overwrites a published
release. Superseded pushes are skipped.

See [RELEASING.md](RELEASING.md) for signing, migration from the old local
counter, and Gitea setup (host runner, signing key, certificate fingerprint,
and the `RELEASE_TOKEN` Actions secret).

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
`POST /sessions/{id}/stop`.

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
`[{ id, path, name, is_git_repo, session_count, last_active_at }]`, where
`session_count` includes all sessions and `last_active_at` considers all activity;
`last_active_at` is `null` for a project with no sessions yet, and `is_git_repo` is a hint
for whether to offer the worktree toggle. There are no nested project routes:
`GET /sessions` carries `project_id` on every row, so the client filters
locally — on the id, not the path, because a worktree session's `working_dir`
is somewhere else entirely. A **404 means an older server** — the app then falls
back to the unscoped session list, i.e. its pre-projects behaviour, and to
matching sessions by `working_dir`.

`POST /projects` body: `{ path, name }` — path absolute; creates the directory
and the row, so a new project lists immediately. On a 404 the app opens the
project anyway and lets the first session's `mkdir -p` create the directory.

`DELETE /projects` body: `{ path }` — removes the project, all its sessions
and its worktrees, leaving the project's own directory
alone. The path is in the body, not the URL, so no filesystem path has to be
encoded into a path segment. The response adds `worktrees_removed` and
`worktree_errors: [{ path, error }]` for the worktrees git refused to remove; it
is always a 200 and the rows are gone regardless, so those directories are
reported as left in place.

**Worktrees.** A worktree is its own resource, not something a session owns.
`GET /worktrees?project_path=…` returns
`[{ id, project_id, path, branch, created_at, session_count }]`, newest
first, where `branch` is the branch the worktree was *created on* (null for one
carried over by the server's migration, and never live state — an agent can
switch branches in it), `session_count` counts attached sessions only and may be
`0` without anything being wrong. Session and worktree screens request only
that project's worktrees, including worktrees with no sessions.

`GET /directory-existence` returns an absolute-path-to-boolean object for all
projects, worktrees and effective session directories, including
former-worktree sessions. Project and worktree responses have no `exists` field. Each screen fetches only the catalogs it uses, in
parallel, then requests existence. Session cards render as soon as `/sessions`
returns, without waiting for worktrees or existence. Absent keys mean unknown,
not missing; a concurrent catalog change triggers one bounded retry.

Fetched catalogs and existence are cached in memory by server and authentication
identity. On initial population, cached JSON is decoded on a shared background
executor while network requests start immediately. Both paths use `CatalogJson`;
only publication and view updates run on the UI thread. Mutable models are decoded
separately for each reader. Fresh network results supersede cached results per
resource, so fresh sessions do not discard useful cached projects or existence.
Populated lists retain their data on return and only refresh from the network.
Transcript metadata uses an explicit initialization flag: interrupted restoration
is retried on return until a matching cached or REST session has been applied.
Conversation content is separate and arrives through WebSocket replay. Agents
are reused for up to one minute. Catalog mutations invalidate affected cached catalogs; directory creation
or removal also invalidates existence. Foreground project/session/worktree/chat
polling refreshes current data every three seconds; session polling includes
project-scoped worktrees. Project/search screens never fetch worktrees, and chat
fetches projects only when its project path is unknown. Worktree listing fetches
sessions only for deletion confirmation and post-deletion refresh.
There is no new WebSocket event.

Search builds its ownership/text index and project status summaries when project
or session catalogs change. Typing searches each project's already-grouped sessions;
project badges summarize all owned sessions, not only matching children. Matching
is separate from view binding: expansion, agent-label and directory-existence
updates reuse current matches without rerunning the query. Project groups and
session cards are rebound in place, with unchanged rows left attached. Filtering
and view updates remain on the UI thread; no typing debounce is introduced.

`CatalogRepository` owns scoped operations and cache policy; `Api` remains the
HTTP transport. `CatalogLoader` coordinates restoration and refresh for each
screen, using `RefreshController` for its in-flight guard and one queued action
refresh. Activities own rendering and cancel their loader on leaving the foreground
(on pause for lists/search, on stop for transcript metadata). Cancellation suppresses
late publications and further existence/retry requests; already-started decoding
or HTTP requests may still finish, and HTTP responses can warm the cache.
Server changes and mutations invalidate pending restoration. Pre-mutation
responses cannot refill invalidated cache entries.

`POST /worktrees` body: `{ project_path, path, branch }` — runs
`git worktree add -b <branch> <path>`, always cutting a **new** branch off the
project's current HEAD. `path` must be absolute; the app resolves a relative one
against the project directory first, since the server has no notion of one.
Paths are normalised lexically on arrival, which is why the app normalises the
same way before comparing. A **409** means a worktree is already there — routine,
since a template maps a branch to the same path every time — and the body has no
id, so the app looks the worktree up by path and offers it instead. Everything
else is a 400 carrying git's own message, which is passed through unedited.

`DELETE /worktrees/{id}` safely removes a registered worktree and its row,
**never** with `--force`. A 409 leaves metadata intact when a directory-dependent
session is busy (active agent turn or shell command), Git registration listing
fails, or Git refuses safe removal. Idle sessions do not block removal. If the
path is not registered, only the server record is removed; an existing directory
is left untouched. Attached sessions retain the original `working_dir` and lose
their worktree association; former-worktree sessions are unchanged. History,
identity, queues and settings are preserved. The app confirms the number of
attached sessions and all other sessions using the same absolute path, then
refetches sessions and worktrees before existence. Missing directories prevent
running, not reading history or changing settings. A database failure after Git
removal can leave stale metadata; retrying cleans up that record.

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
former worktree after worktree removal; comparing `working_dir` with the
project path distinguishes them.

`DELETE /sessions/{id}` returns `{ status }` and touches nothing on disk: the
worktree belongs to the project and stays, along with any other session in it.

`PATCH /sessions/{id}` changes only supplied settings, such as `name`,
`auto_approve_write`, `auto_approve_command`, and
`auto_approve_inter_agent_communication`. Existing busy/approval rules remain.

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
                   { type: "input",            message_id, text, source, delivery }  # "queued"
                   { type: "inputs_shipped",   messages: [{ message_id, text, source, delivery }] }
                   { type: "input_queue",      messages: [{ message_id, text, source, delivery }] }
                   { type: "output",           text, stream_id?, turn_id?, ordinal? }
                   { type: "bash_input",       command }
                   { type: "bash_output",      command, stdout, stderr, exit_code,
                                               duration_ms, timed_out, truncated }
                   { type: "tool_use",         call_id, action, stream_id?, turn_id?, ordinal? }
                   { type: "approval_request", request_id, call_id, action, options,
                                               auto_approved? }
                   { type: "approval_response", request_id, behavior, auto? }
                   { type: "question",         request_id, call_id, questions }
                   { type: "question_response", request_id, answers }
                   { type: "stream_interrupted", draft, reason }  # cancelled | error | retry | incomplete
                   { type: "stream_start",     draft }                       # transient
                   { type: "stream_delta",     stream_id, revision, delta, arguments_preview? }  # transient
                   { type: "stream_snapshot",  drafts: [draft] }             # transient
                   { type: "stream_remove",    stream_id }                   # transient
                   { type: "settings",         auto_approve_write, auto_approve_command }
                   { type: "done" }
                   { type: "error",            message }
```

WebSocket initialization is persisted transcript replay, then exactly one
`stream_snapshot`, then `input_queue`, then `status`, followed by live
publications. There is no worktree-removal
frame; polling the REST session catalog updates saved paths and associations.

**Message queue.** Every ordinary message, the user's or another agent's, goes
through one server-owned queue, so the composer is not gated on status. An
`input` with `delivery: "queued"` is the acceptance: it joins the pending list
above the composer and touches neither the transcript nor a streaming reply.
A user's acceptance is the composer-history echo; an agent's never is.
`inputs_shipped` is the delivery boundary: its messages leave the pending list
and become one transcript row each, in array order, and the next output starts a
new bubble. It carries full contents, so a message whose acceptance fell out of
the replay window still ships; shipping never touches history. After the replay
and before `status`, `input_queue` replaces the whole pending list — it is
authoritative, not a transcript event. All three are keyed by `message_id`;
the app ignores a message it has already shown. Replayed `input` rows without
`delivery` predate the queue and render where they stand. Stop, failure and
restart keep pending messages until the next idle submission ships them along
with it; there is no edit or cancel.

**Model output streaming.** A draft is one assistant content block still being
generated: `{ stream_id, turn_id, ordinal, revision, kind: "text", text }` or
`{ …, kind: "tool", call_id, name, arguments_json, arguments_preview }`. The
`stream_*` frames are transient: they have no `event_id`, bypass the scrollback
log that history prepends rebuild from, and their rows are read-only views of
the `StreamDrafts` model. Within a turn, every generated row — draft, final or
interrupted — keeps `ordinal` (source) order, not completion order: a final
takes its draft's slot, or else goes ahead of the first later row of its turn;
adjacent text blocks of a turn share one bubble. Rows without streaming metadata
keep their arrival order. A delta appends
its native fragment to the text or raw argument JSON and **replaces** the tool
preview; views redraw in ~50 ms batches. Each delta must be the draft's next
`revision`: an older or repeated one is ignored, while a gap or an unknown
`stream_id` means the client is out of step, so it drops the socket and
resubscribes for history and a fresh snapshot (backing off on repeats); so
does any malformed `stream_*` frame. The snapshot is validated whole, replaces
every draft, is built offscreen with a reconnect's replay and
swaps in at `status`. A final `output`/`tool_use`, a durable `stream_interrupted`
(shown labelled, never actionable) or `stream_remove` (an empty placeholder)
retires its draft by `stream_id`; finals still render standalone in history. A
history prepend carries live draft rows over unchanged. Tool previews never pass
through canonical validation and have no controls; a session tool's message
grows in plain view, and a missing `session_id` reads as composing.

`question` / `question_response` cover Claude Code's **AskUserQuestion** tool — a
multiple-choice prompt the client renders as selectable options, sending the
pick back as `answers` (keyed by question text; a label, or array of labels for
`multiSelect`). The question tool's completed `tool_use` arrives first as an
ordinary read-only card; the `question`, correlated by `call_id`, takes its
place so the call shows once, and only it carries controls.

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
