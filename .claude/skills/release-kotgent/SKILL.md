---
name: release-kotgent
description: "Publish a complete Kotgent version release: bump version metadata, keep main linear, run local and CI gates, tag vX.Y.Z, publish and verify GitHub assets, then update Heapy/homebrew-tap. Use only when explicitly asked to release, publish, or cut a Kotgent version; do not trigger for notes-only, planning, inspection, or formula-only work."
---

# Release Kotgent

Run this end-to-end workflow only for an explicit release request. For a narrower request, do only that task and do not push, tag, publish, or update the tap.

Ask for a missing version. Normalize an optional leading `v`; accept only stable `MAJOR.MINOR.PATCH` because this workflow publishes a non-prerelease GitHub Release.

## Non-negotiable contract

- Release `Heapy/kotgent` from `main`; keep Kotgent and `Heapy/homebrew-tap` linear.
- Store the version in `version.txt`; name the tag and Release `v<version>`.
- Never merge, force-push, move a tag, or replace an existing release.
- Build only `macosArm64`; require exactly the archive and matching `.sha256` asset named by `.github/workflows/release.yml`.
- Verify the published archive before updating `Formula/kotgent.rb`.
- Preserve the caller's worktree. Stage only explicit release files in isolated temporary checkouts.
- A pushed tag alone is not completion.

## 1. Preflight and isolate

1. Read `version.txt` and both GitHub workflows. If repository guidance is not already loaded, inspect only the build-system and build-info sections of `CLAUDE.md`.
2. Inspect status, fetch `origin/main` and tags, and confirm:
   - `origin` and `gh repo view` identify `Heapy/kotgent`;
   - the requested version is newer than the newest stable tag reachable from `origin/main`, unless the user explicitly approves an exception;
   - neither the remote tag nor GitHub Release exists.
3. Create a unique temporary root and detached worktree:

   ```sh
   mktemp -d /private/tmp/kotgent-release-v<version>.XXXXXX
   git worktree add --detach <temp-root>/repo origin/main
   ```

   Perform all Kotgent edits, builds, commits, and tagging there. Use `--repo Heapy/kotgent` for repository-scoped `gh release` and `gh run` commands.

## 2. Prepare and verify

1. Set `version.txt` to `<version>`. Search for the previous version with `rg`; update only active documentation that presents the current release.
2. Derive concise notes from `v<previous>..HEAD`, excluding uncommitted work, and save them outside the checkout. Include material highlights, `https://github.com/Heapy/kotgent/compare/v<previous>...v<version>`, and an Upgrade block with:

   ```sh
   brew update
   brew upgrade kotgent
   kotgent install
   ```

   Explain that reinstalling the launchd plist is required because it stores a version-qualified Cellar path.

3. Run in order:

   ```sh
   git diff --check
   KOTGENT_RELEASE_BUILD=true ./kotlin build -v release -p macosArm64 -m kotgent
   build/tasks/_kotgent_linkMacosArm64Release/kotgent.kexe --version
   ./kotlin build
   ./kotlin test
   ```

   Require exactly `kotgent <version>` and zero failures. Never run `./kotlin run`.

## 3. Push and pass CI

1. Stage only `version.txt` and changed current-version documentation; commit `chore: release <version>`.
2. Fetch again. If `origin/main` advanced, rebase and rerun all verification gates.
3. Require:

   ```sh
   git rev-list --left-right --count origin/main...HEAD  # 0 1
   git rev-list --merges origin/main..HEAD               # empty
   git log --oneline origin/main..HEAD                   # release commit only
   ```

4. Push `HEAD:main` without force. Find the `CI` run whose `headSha` is the release commit and wait for success. On failure, read [references/ci-failure-policy.md](references/ci-failure-policy.md); do not tag without the approval required there.

## 4. Publish and verify

1. Immediately before tagging, reconfirm that both the remote tag and GitHub Release are absent.
2. Tag the exact pushed commit and push:

   ```sh
   git tag -a v<version> <release-sha> -m "kotgent <version> — <headline>"
   git push origin v<version>
   ```

3. Find the `Release` workflow run for the same `headSha` and wait for success.
4. Verify the workflow-created Release targets `v<version>`, is neither draft nor prerelease, and has exactly the expected archive and checksum assets. Publish the prepared title/notes, then verify it again.
5. Download into a new explicit temporary directory and run:

   ```sh
   shasum -a 256 -c kotgent-<version>-macos-arm64.tar.gz.sha256
   shasum -a 256 kotgent-<version>-macos-arm64.tar.gz
   tar -tzf kotgent-<version>-macos-arm64.tar.gz
   tar -xzf kotgent-<version>-macos-arm64.tar.gz
   ./kotgent-<version>-macos-arm64/kotgent --version
   ```

   Require checksum `OK`; record the independently computed SHA-256; require `kotgent` and `resources/webui` in the archive; require exactly `kotgent <version>`.

## 5. Update the Homebrew tap

1. Clone `git@github.com:Heapy/homebrew-tap.git` into another unique temporary root and read tap-local instructions.
2. Change only the formula's version, release URL/filename, verified SHA-256, and expected test version.
3. Run tap-prescribed gates and at least:

   ```sh
   git diff --check
   ruby -c Formula/kotgent.rb
   HOMEBREW_CACHE=<explicit-temp-cache> brew style Formula/kotgent.rb
   ```

   Run the formula install/test only where it cannot replace the user's installed Kotgent. Otherwise disclose the missing integration check and obtain explicit approval before pushing.

4. Stage only `Formula/kotgent.rb`; commit `kotgent <version>`. Fetch, rebase and revalidate if `origin/main` advanced. Apply the same linear-history checks, then push `HEAD:main` without force.
5. Read the formula back through `gh api` and verify its version, URL, SHA-256, and test.

## 6. Finish

Report the Release URL, commit/tag target, workflow results, assets/SHA-256, local gates and accepted exceptions, tap commit, and preservation of caller changes.

After confirming the worktree is clean, remove it with `git worktree remove <temp-root>/repo`. Delete only exact temporary paths created by this run. Declare completion only after remotely verifying both repositories and the published archive.
