# Wave participant task

You are ONE participant in a WAVE of tasks that are running **at the same time** as you, in the same
working tree. Other agents are editing other files right now. Everything below exists to keep you from
colliding with them.

Plan file: `PLAN_FILE_PATH`
Progress file: `PROGRESS_FILE_PATH`
Your task: **TASK_NAME**

USER_RULES

## Find your task

Open the plan file and locate the section whose header matches **TASK_NAME** exactly.

Do **not** search for "the first section with unchecked boxes" — several sections are unchecked right now
and another participant owns them. Address your task by name, only.

Read that section's `**Files:**` block. It is the complete list of paths you are allowed to create,
modify or delete. Read its `**Wave:**` and `**Depends:**` markers for context.

## Hard prohibitions

These are not style preferences. Breaking any of them corrupts the wave for every other participant.

1. **Do not edit the plan file.** Not the checkboxes, not the prose, not anything. The wave closer marks
   your checkboxes after it has validated your work.
2. **Do not run mutating `git` commands.** No `add`, no `commit`, no `stash`, no `checkout`, no
   `restore`, no `rebase`. The index is shared; the wave closer makes exactly one commit for the whole
   wave.
3. **Do not run `./kotlin`** — not `build`, not `test`, not `task :…`. Nothing from the plan's
   `## Validation Commands` section. A native link and the test suite are shared, expensive and would
   compile your neighbours' half-written code. The wave closer validates once, for everyone.
4. **Do not touch a file outside your `**Files:**` block.** In particular the shared registries
   (`project.yaml`, the root `module.yaml`, `gradle/libs.versions.toml` unless your block names it)
   belong to the wave closer or to a single named owner.
5. **Do not read your neighbours' new files** and do not wait for them. They may not exist yet, or may be
   half-written. Anything you need from them is written out as a frozen contract in the plan's
   Technical Details — code against that text, not against their files.

## What you may do

Read any existing file in the repository. Search freely. Write, edit and delete **only** the paths in your
`**Files:**` block. Run terminating read-only shell commands (`grep`, `ls`, `node --check`, `git log`,
`git show`, `git diff` — reading history is fine, mutating is not).

Follow the repository's conventions. `CLAUDE.md` at the root is the authority; read the sections relevant
to what you are writing. Match the surrounding code's comment density, naming and idiom.

## Progress logging

One line per entry, using the single-argument form only:

```
bash RESOLVE_SCRIPT_DIR/append-progress.sh PROGRESS_FILE_PATH "task N: <what you did>"
```

Never use the multi-line stdin form (`cat >> …`, piping into the script) — that is the wave closer's
privilege and interleaved multi-line writes from concurrent participants corrupt the file.

Additional entries you may write:

- `[decision] task N: <choice and why>` — one line, when you made a judgement call.
- `[deviation] task N: <what differed from the plan and why>` — one line.
- `[replaces] task N: <File>.<testName>` — **required whenever your task's section says so**, one line
  per existing test that your new work supersedes. It exists for the case where the tests being replaced
  live in a file several participants would otherwise all edit: you must NOT delete them yourself, so
  their removal is batched into a single later task that reconciles exactly these lines. Emit one line
  per replaced test, no more and no fewer, and only if your section asks for it — a task that supersedes
  nothing writes none of these.

## Your final message

Your final message IS the machine-readable return value. It must contain exactly these two blocks, and
nothing else that could be mistaken for them:

```
DONE:
- <verbatim text of a checkbox you completed, copied from the plan>
- <...one line per completed checkbox...>

FILES:
- created: <path>
- modified: <path>
- deleted: <path>
```

Copy checkbox text **verbatim** from the plan — the closer matches on it. List every path you touched,
including ones you created and then deleted. The closer diffs your `FILES:` against
`git status --porcelain` and stops the wave if they disagree, so an omission reads as another
participant's stray edit.

If you could not complete a checkbox, leave it out of `DONE:` and say why in one plain sentence after the
blocks. Do not fake completion, and do not silently narrow the work.
