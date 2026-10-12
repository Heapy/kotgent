# Git Diff viewer (upcoming)

This contract is for the upcoming viewer; the integrated UI/API is not available yet.

Diff opens in a session workspace and reads Git from that session's stored cwd. Choose any worktree
in the same Git common directory, including one without a session. This changes the Diff source,
not the terminal attachment. Different clones remain different repositories. Missing cwd is an
unavailable state, never a fallback to the project's directory. Locked worktrees remain readable;
missing/prunable worktrees are unavailable. Bare entries have no Local changes.

Local changes defaults to **All**: the net HEAD → working-copy change plus untracked files.
**Staged** compares HEAD → index; **Unstaged** compares index → working copy plus untracked files.
Ignored files are excluded. An edit staged and then reversed can disappear from All while remaining
visible in both other scopes. Index and working-copy statuses are shown separately. Unborn HEAD uses
an empty tree. Conflicts are explicit and are never represented as resolved ordinary patches.

Commits pages through the selected HEAD's history, independent of base selection. A commit compares
its first parent to itself; a root compares the empty tree to itself. A merge exposes all parents and
defaults to parent 1, with an explicit parent label. Selecting a commit does not change the checkout.
Shallow boundaries must not be misidentified as root commits.

Branch changes compares merge-base(base, HEAD) → HEAD, excluding local edits. Its history lists
commits reachable from HEAD but not from the resolved base tip. Base defaults to a still-valid saved
choice for this worktree, then a known remote default branch, then local main, then local master;
otherwise choose explicitly. Multiple conflicting remote defaults require a choice/fallback, not an
arbitrary remote. A feature branch's upstream is not a default base. Refs use local and already-present
remote-tracking branches only; nothing fetches. No common ancestor, ambiguous merge bases, and missing
shallow history are distinct unavailable states. Equal base/HEAD is a valid empty comparison.

The viewer loads a file list and only the selected patch. Unified/Split, wrap, path filtering, Viewed,
and next-unviewed operate on this one comparison. On phones, Files → Patch → Back replaces side-by-side
panels. Reading position and Viewed survive workspace-tab remounts in page memory. Refresh, changed
worktree/base/commit, and stale snapshots reset Viewed. Failed revalidation keeps prior content with
an explicit stale/error indication. Load on opening, Refresh, visibility restoration and connection
recovery; no continuous polling or filesystem watchers. No review state is stored on the server.

## Upcoming HTTP contract

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
baseRef membership and commit reachability in the selected pinned history, not arbitrary rev expressions.
Full OIDs support SHA-1 and SHA-256. No arbitrary-SHA search UI or graph view is included.

`cursor` is URL-encoded JSON `{ "headOid": "…", "baseOid": null, "offset": 100 }`, not a signed
capability. First page pins current HEAD and optional baseRef OIDs. Later pages use those same OIDs,
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
2 MiB or 20,000 lines per patch. Total concurrency and queued reads must also be bounded. Propagate
cancellation and drain/reap owned subprocesses. Do not invoke external diff, textconv, filters, fsmonitor,
interactive prompts or lazy fetch. Respect safe.directory. No read mutates Git/index/files.

Editing, staging, commits, branch switching, worktree management, fetch, blame, review comments,
full Files browsing, and session-start baselines are outside this version. Checkout changes are not
attributed to a particular agent.
