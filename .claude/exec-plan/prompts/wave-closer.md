# Wave closer

Every participant of wave **WAVE_NUMBER** has returned. You are the single agent that turns their
concurrent, unvalidated, uncommitted edits into one validated commit.

Plan file: `PLAN_FILE_PATH`
Progress file: `PROGRESS_FILE_PATH`
Tasks in this wave: **WAVE_TASKS**

Participants reported these files:

```
PARTICIPANT_FILES
```

USER_RULES

## Step 1 — reconcile the working tree

Run `git status --porcelain`.

The set of changed paths must equal the union of the participants' `FILES:` blocks, plus nothing. Two
failure modes, both of which stop the wave:

- **A path nobody claimed.** Someone edited outside their `**Files:**` block. Report the path and which
  task most plausibly owns it. Do not "fix" it by reverting — you cannot tell an overreach from a
  legitimate edit whose report was simply forgotten.
- **A claimed path that is unchanged.** A participant reported work it did not do.

Report and stop. Do not proceed to validation with a tree you cannot account for.

## Step 2 — apply the wave's registry edits

The shared registries belong to you, not to the participants, precisely because several tasks would
otherwise need the same file. Apply what this wave requires — read the wave's tasks in the plan for the
exact entries:

- `project.yaml` — register new modules; keep the `modules:` list alphabetical (the toolchain warns
  otherwise).
- the root `module.yaml` — `test-dependencies` entries.

If this wave needs no registry edit, say so and move on.

## Step 3 — validate once, for the whole wave

Run the plan's `## Validation Commands` section, in order, in full. Do not substitute a faster subset:
the participants wrote their code blind to each other, and this is the first moment anything is compiled
together.

Note that `./kotlin build` must precede `./kotlin test` — the suite execs main binaries that
`./kotlin test` never links.

## Step 4 — on failure, attribute rather than repair

If validation fails, do **not** start fixing broadly. Your job is attribution.

For each failure, map it to the participant whose file caused it, using the `FILES:` blocks. Return:

```
WAVE FAILED
- <task name> → <file:line> → <the compiler/test error, verbatim>
- ...
```

The orchestrator re-runs only those participants, handing them the error text. A trivially mechanical
fix that is unambiguously the wave's seam (a missing import, a registry line you own) you may make
yourself — say so explicitly in the report. Anything requiring a judgement about someone's design is
theirs, not yours.

## Step 5 — on success, mark and commit

1. Edit the plan file: mark `[x]` on **every** checkbox of **every** task in this wave, including your
   own closing task. Match the participants' `DONE:` lines; a checkbox nobody reported stays `[ ]` and
   you report that as an incomplete wave rather than checking it.
2. Write the wave's progress block. You — and only you — may use the multi-line stdin form:
   ```
   echo "<block>" | bash RESOLVE_SCRIPT_DIR/append-progress.sh PROGRESS_FILE_PATH
   ```
   Include: the wave number, each task and a one-line summary, the validation result with test counts,
   and the measured wall-clock of the validation run.
3. Make exactly **one** commit for the wave, including the plan file:
   ```
   bash RESOLVE_SCRIPT_DIR/stage-and-commit.sh "<message>" <every changed path> PLAN_FILE_PATH
   ```
   The message names the wave and its tasks, e.g.
   `test(webui): wave 1 — shared fakes module and Playwright browser cache in CI`.
   Body: one line per task. Follow the repository's commit conventions.

## Step 6 — report

Return a short plain-text report: which tasks closed, the validation numbers (native tests, skipped,
JVM tests, ptycheck checks), the commit subject, and anything the next wave should know — especially a
frozen contract that turned out to be wrong, since the next wave's participants will code against the
plan's text without being able to read this wave's files.
