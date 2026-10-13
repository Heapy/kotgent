# Git Diff viewer

Use **Open changes** in the command palette (session menu → Commands), or choose **Diff** in Configure
columns. Open changes reuses an existing Diff column and creates one Diff tab only when none exists. From a task, Open changes uses
its linked sessions. One resolved checkout with no lookup failures opens directly; otherwise the task
shows available checkouts and discovery errors. Without a linked session, it asks you to link one;
it does not use the project path.

Diff opens in a session workspace and reads Git from that session's stored cwd. Choose any worktree
in the same Git common directory, including one without a session. This changes the Diff source,
not the terminal attachment. Different clones remain different repositories. Missing cwd is an
unavailable state, never a fallback to the project's directory. Locked worktrees remain readable;
missing/prunable worktrees are unavailable. Bare entries have no Local changes.

Local changes defaults to **All**: the net HEAD → working-copy change plus untracked files.
**Staged** compares HEAD → index; **Unstaged** compares index → working copy plus untracked files.
Ignored files are excluded. An edit staged and then reversed can disappear from All while remaining
visible in both other scopes. All combines the tracked HEAD-to-working net diff with a separate
untracked inventory. For example, `git rm --cached file` leaving a HEAD-tracked file on disk displays
both a deletion and an untracked entry for that path in All; paths are not coalesced. Index and
working-copy statuses are shown separately. Unborn HEAD uses an empty tree. Conflicts are explicit and are never represented as resolved ordinary patches.

The toolbar exposes **Local changes**, **Log**, and **Branch changes** directly. On desktop, a branch
sidebar groups local and already-present remote branches, with a filter and a mark for the checked-out
branch. Selecting a branch opens its Log without checking it out. **Checkout HEAD** reads the selected
worktree's history, including detached HEAD. On phones, **History branch** provides the same choices.
The Log stays open while reading a commit and shows subject, branch labels, author, date and hash.
Up/Down and Home/End move focus between rows; Enter/Space selects. **Filter loaded commits** searches
subject, author and hash within the loaded pages; **Load more commits** extends that history.

Log pages through the selected branch tip's history (Checkout HEAD by default), independent of base selection. A commit compares
its first parent to itself; a root compares the empty tree to itself. A merge exposes all parents and
defaults to parent 1, with an explicit parent label. Selecting a commit does not change the checkout.
Shallow boundaries must not be misidentified as root commits.

Branch changes compares merge-base(base, HEAD) → HEAD, excluding local edits. Its history lists
commits reachable from HEAD but not from the resolved base tip. Base defaults to a still-valid saved
choice for this worktree, then a known remote default branch, then local main, then local master;
otherwise choose explicitly. Multiple conflicting remote defaults require a choice/fallback, not an
arbitrary remote. A feature branch's upstream is not a default base. Refs use local and already-present
remote-tracking branches only; nothing fetches. No common ancestor, ambiguous merge bases, and missing
shallow history are distinct unavailable states. A shallow repository is readable when Git proves
one endpoint is the unique merge base; unproven ancestry stays unavailable. Equal base/HEAD is a valid
empty comparison.

The viewer loads a file list and only the selected patch. The file sidebar offers **List** and **Tree**.
Tree groups paths into expandable folders (click or Left/Right); Up/Down and Home/End move focus,
and Enter/Space opens a file. Folders do not change the selected patch. Search matches both rename
paths and expands matching ancestors; next-unviewed reveals its file even inside a collapsed folder.
List/Tree and folder expansion survive workspace-tab remounts in page memory; changing worktree
resets folder expansion. The list retains separate entries that share a Git path.
Loading uses the refresh icon and a permanent footer without moving the file list or patch. The
footer keeps stale/error status visible; **Details** expands full errors. File-list and patch snapshot
identities open from the footer without reducing the reading area. Unified/Split, wrap, path filtering, Viewed,
and next-unviewed operate on this one comparison. The filter, layout and wrap controls are local to
the viewer. File-list and patch comparison details show their own resolved OIDs, including a retained
stale patch whose identity differs from a refreshed file list. On phones, Files → Patch → Back replaces
side-by-side panels. Reading position and Viewed survive workspace-tab remounts in page memory. Refresh, changed
worktree/base/commit, and stale snapshots reset Viewed. Failed revalidation keeps prior content with
an explicit stale/error indication. Load on opening, Refresh, visibility restoration and connection
recovery; no continuous polling or filesystem watchers. No review state is stored on the server.

## HTTP contract

All reads are authenticated GET routes under `/api/v1/sessions/{id}/diff/`. Authorization precedes
filesystem access. `worktreeId` is a URL-encoded canonical root returned by `worktrees`, checked against
the current inventory and common-dir on **every** read; it is not permission to open an arbitrary path.
There is no caller-provided cwd or file path. Unknown session is 404, invalid query/identity is 400,
stale snapshot is 409, unavailable is 422, resource limit is 413, and full queue is 429. Authentication
and Origin failures retain the existing server policy. Responses use `Cache-Control: no-store`.

| Route | Query fields | `data` model |
|---|---|---|
| `worktrees` | none | `DiffWorktrees` |
| `refs` | `worktreeId` | `DiffRefs` |
| `commits` | `worktreeId`, optional `baseRef`, optional `cursor` | `DiffCommits` |
| `files` | `worktreeId`, `mode` (default `local`), mode fields below | `DiffFiles` |
| `patch` | `worktreeId`, `snapshot`, `fileId` | `DiffPatch` |

Files mode fields: local accepts `scope=all|staged|unstaged` (default all); commit requires full
`commitOid` and accepts one-based `parent`; branch requires full `baseRef` such as `refs/heads/main`.
Reject irrelevant, repeated, malformed, and unknown query fields. Missing branch base is invalid;
`defaultBaseRef` from refs is a UI suggestion, not an implicit moving server default. The server checks
baseRef membership. For commit mode, it looks up the full `commitOid` as a commit object inside the
authorized repository; it does not require reachability from current HEAD or a particular history page.
Thus a commit selected from history pinned at HEAD A remains readable after HEAD moves to B, provided
the object still exists. Missing objects return unavailable/missing_object; an existing non-commit
object is invalid/invalid_request. Rev expressions and abbreviated OIDs are not accepted. Full OIDs
support SHA-1 and SHA-256. The UI selects only commits in its loaded history; arbitrary-SHA search and
graph views remain outside this version. No pinned-history context is required in the files request.

`cursor` is URL-encoded JSON `{ "headOid": "…", "baseOid": null, "offset": 100 }`, not a signed
capability. Without a cursor, the first page pins current HEAD and optional baseRef OIDs. A selected
branch starts with an offset-zero cursor using its full OID from `refs`; this uses the same object
validation as pagination. Refresh rereads its tip; a removed branch reports unavailable rather than
silently falling back to HEAD. Later pages use those same OIDs,
with baseRef omitted; reject baseRef plus cursor. Validate OIDs, nonnegative offset, repository object
membership and bounded paging work. Each page contains at most 100 commits, in Git topo order with
the same ordering flags for each page. Removed pinned objects yield unavailable, never restart at a
new HEAD. `nextCursor=null` ends history. Unborn history has empty commits and null cursor/nextCursor;
it does not fabricate a commit.

Every response is `{ "data": <model>, "error": null }` or
`{ "data": null, "error": { "code": "stale", "reason": "snapshot_changed", "message": "Refresh" } }`.
Enum strings are lowercase. Null/default fields are emitted with the existing transport JSON settings.
Times (`readAt`, `authoredAt`) are epoch milliseconds. Clients branch on code/reason, not message.
Models in `src/diff/DiffModels.kt` are the authoritative field list. Illustrative payloads below abbreviate
full OIDs as `H`, `B`, `M` and omit null/default fields for readability; real OIDs are full hexadecimal.

Worktrees:

```json
{"data":{"repository":{"commonDir":"/repo/.git"},"currentWorktreeId":"/repo","worktrees":[{"id":"/repo","path":"/repo","headOid":"H","branchRef":"refs/heads/topic","current":true},{"id":"/linked","path":"/linked","headOid":"B","detached":true,"locked":true}],"readAt":1000},"error":null}
```

Refs and commits:

```json
{"data":{"repository":{"commonDir":"/repo/.git"},"worktreeId":"/repo","refs":[{"name":"refs/heads/main","oid":"B","kind":"local"}],"remoteDefaultRef":null,"defaultBaseRef":"refs/heads/main","readAt":1000},"error":null}
{"data":{"repository":{"commonDir":"/repo/.git"},"worktreeId":"/repo","cursor":{"headOid":"H","baseOid":null,"offset":0},"commits":[{"oid":"H","parents":["B"],"author":"Ada","authoredAt":900,"subject":"Update"}],"nextCursor":null,"readAt":1000},"error":null}
```

Files and patch (the repeated snapshot is intentional):

```json
{"data":{"snapshot":{"id":"s1","comparison":{"repository":{"commonDir":"/repo/.git"},"worktreeId":"/repo","mode":"branch","headOid":"H","baseRef":"refs/heads/main","baseOid":"B","mergeBaseOid":"M","leftOid":"M","rightOid":"H"},"readAt":1000},"files":[{"fileId":"f1","oldPath":"a.kt","newPath":"a.kt","change":"modified","kind":"text","oldMode":"100644","newMode":"100644","additions":1,"deletions":0}],"limited":null},"error":null}
{"data":{"snapshot":{"id":"s1","comparison":{"repository":{"commonDir":"/repo/.git"},"worktreeId":"/repo","mode":"branch","headOid":"H","baseRef":"refs/heads/main","baseOid":"B","mergeBaseOid":"M","leftOid":"M","rightOid":"H"},"readAt":1000},"file":{"fileId":"f1","oldPath":"a.kt","newPath":"a.kt","change":"modified","kind":"text"},"hunks":[{"oldStart":0,"oldCount":0,"newStart":1,"newCount":1,"heading":"","lines":[{"kind":"addition","text":"hello","oldLine":null,"newLine":1,"noNewline":true}]}],"limited":null},"error":null}
```

## Snapshot, file and outcome rules

`DiffComparison` value equality defines the resolved comparison: repository, worktree, mode/scope,
HEAD, base ref and OID, merge-base, selected commit/parent and both endpoints. For local, `rightOid=null`
means index for Staged and working copy otherwise; Unstaged's `leftOid=null` means index. Other null
left endpoints represent empty trees for root/unborn. Commit/branch patches keep their pinned OIDs
when refs move. Refresh resolves new tips. Local HEAD/index/content movement invalidates the snapshot.

`snapshot.id` names one server-resolved comparison and content observation; `fileId` selects only an
entry in that snapshot. Neither is a bearer capability or an encoded path. No token format is promised.
The service may keep a bounded, expiring snapshot registry or revalidate equivalent reader identities;
an unknown/expired snapshot returns stale. A mismatched fileId returns invalid/unknown_file. The reader
receives the resolved snapshot and entry, not untrusted query paths. Bind snapshot lookup to session,
common-dir and worktree. A new common-dir/checkout must never reuse old registry entries.

Local validation must include content bytes and relevant index entries/modes/conflict stages, including
untracked content: unchanged M status, size or mtime does not prove identity. Check before and after
reading the selected patch. Git offers no atomic snapshot of an entire mutable working copy; detected
races return stale rather than mixing observations. Implementations must not claim stronger guarantees.
The frontend also guards requests by session/source selection generation, since readAt is not a revision.

Paths are decoded strictly, remain repository-relative, and preserve tabs/newlines. Rename/copy uses both
paths; additions have null oldPath, deletions null newPath. `indexStatus`/`worktreeStatus` are one-character
Git porcelain codes (including space for unchanged), present for local entries only. Stats can be null
when unavailable, not guessed as zero. Mode-only text changes have no hunks. Line numbers are one-based;
absent sides are null. Hunk starts may be zero for empty ranges. Text excludes LF, preserves CR, and
`noNewline` marks a final line without LF. Render all text as escaped text, never HTML.

Binary, conflict, symlink and submodule entries have explicit kinds; first version shows metadata, not
ordinary text hunks or recursive content. Never follow a symlink or recurse into submodules. Unsupported
encoding/path and unreadable content have `unavailable`/`limited` kind with a problem. A whole-read error
must never masquerade as a clean list. A partial file list or patch sets `limited` with its reason, so
the client cannot present it as complete; partial patches contain only complete parsed hunks.

Budgets: 10 seconds per process, 8 MiB stdout and 256 KiB stderr; 5,000 file entries; 100 commits/page;
2 MiB or 20,000 lines per patch. The service admits 4 active reads and 16 queued reads, with a 30-second
deadline including queue time. Local content observation reads at most 2 MiB per file, sequentially,
retaining only content fingerprints between entries. There is no aggregate local-content byte cap:
unrelated text or binary files do not block the file list or another file's patch. An oversized file
is listed with its own explicit limit. File-count and request-deadline limits still apply, with
cancellation checked between entries. Server history cursors stop beyond offset 100,000.
Snapshot metadata is capped at 64 snapshots and 20,000 files total, with a two-minute TTL.
The browser uses an eight-session cache, evicting only unmounted entries (mounted consumers can exceed
that cap), one selected patch per session and at most 2,000 loaded history commits; Refresh restarts paging. Propagate
cancellation and drain/reap owned subprocesses. Do not invoke external diff, textconv, filters, fsmonitor,
interactive prompts or lazy fetch. Respect safe.directory. No read mutates Git/index/files.

Editing, staging, commits, branch switching, worktree management, fetch, blame, review comments,
full Files browsing, and session-start baselines are outside this version. Checkout changes are not
attributed to a particular agent.

Real-device Safari and installed-PWA checks remain separate from Chromium automation; see
[the testing checklist](../TESTING.md#git-diff-viewer). Linux native runtime requires its own CI run.

## Immutable disk payload cache

Production uses a private `~/.kotgent/diff-cache-v1` directory outside the checkout and `.git`.
A configured root inside any freshly discovered worktree or the common Git directory is bypassed,
including home-directory repositories while reading a linked sibling. This check precedes cache reads
as well as writes and reuses the current request's validated inventory. Tests inject a temporary directory.
Only successful, complete immutable history, file metadata and
selected patch payloads are stored. Local comparisons continue to read and fingerprint all relevant
bytes before and after the selected patch. No new memory payload cache is used; the existing snapshot
registry budgets are unchanged.

Every request still passes HTTP authorization, current session/cwd/worktree/common-directory checks,
ref resolution where applicable, and object membership/type checks before lookup. Keys hash a reader
schema, canonical repository and worktree, full pinned OIDs, parent/paths/paging/options, effective Git
configuration, shallow/graft bytes and the available object inventory. Diff keys also include index
entries and the bytes of applicable checkout, info and global attributes. This intentionally invalidates
more broadly than necessary (even an unrelated object addition can cause a miss). Context reads are
bounded; an unprovable, unsafe or oversized dependency bypasses caching without reducing the ordinary
reader's limits. Configured `diff.orderFile` also bypasses diff payload caching because Git reads an
external ordering file; edits/removal must take effect through ordinary Git. History caching remains
independent of ordering-file contents. Worktrees and clones do not share entries. The session-bound snapshot and response
timestamps come from the current request, not a stored authorization envelope.

The disk budget is 256 MiB, at most 1,024 records, at most 8 MiB per record, retained for at most seven
days from publication. Records include schema, key and content checksums. Fixed hashed slots (four slots per set) bound
lookup and cleanup work; collisions are ordinary misses. A permanent, nonblocking cross-process file lock
serializes publication and eviction, with contention treated as a miss. Before each write, at most
1,024 slots are inspected, unsafe/expired records are removed, and oldest records are evicted until
the new record (including staging bytes) fits. Failed cleanup stops publication. Corrupt payloads miss
and are replaced by the next successful read. A private temporary record is atomically renamed;
crash leftovers have one fixed staging name, reclaimed by the next writer. Lock files are never
unlinked. Descriptor-relative no-follow access rejects symlinks, hard links, special files and unsafe
permissions. Non-ASCII fixed headers and invalid UTF-8 payloads are misses, including checksum-valid
invalid payloads. Missing/unwritable storage, malformed records and old schemas fall back to Git; coroutine
cancellation still propagates. There is no background cleanup process or watcher.

Warm hits skip the expensive `log`/`diff` command, but still perform bounded dependency validation.
That validation can dominate small repositories; a disk hit is not a promise of lower latency for every
fixture. The opt-in benchmark reports disk-cold and restart-warm history/files/patch separately from
its original cache-disabled scenarios, with Git command counts and uncontrolled OS page-cache state.
For example, the initial tiny-fixture task-3 measurements had history/root-files/root-patch p50s of
221/220/233 ms with caching disabled, 320/501/410 ms with an empty application cache, and
260/289/420 ms with a warm cache. Keep these results alongside subsequent runs: dependency checks
can outweigh saved computation. Process-worker reuse has separately measured causal latency gains;
fewer reader commands and successful disk hits alone do not establish additional latency gains.

Benchmark accounting preserves `gitBytes`, `fileBytes` (returned local content) and `hashBytes`
(reader SHA inputs). Additive `dependencyReadBytes` counts successful dependency read/readlink syscall
bytes; `cacheReadBytes` and `cacheWriteBytes` count successful cache syscall bytes, including timestamp
and record headers, collision probes and eviction scans. `cacheHashBytes` counts cache key/checksum
SHA inputs. `totalReadBytes` sums captured Git output, returned local content and the two new read
counters; `totalHashBytes` sums reader and cache SHA inputs. These are application byte counts, excluding
Git's internal filesystem I/O and metadata syscalls. Counters are optional scalars and retain no payloads.
