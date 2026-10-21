# Git workflow

The shorthand: `main` is a protected trunk; every change arrives through a
pull request from a feature branch, and merges only when CI is green.

## Branching

- One feature branch per GitHub issue/ticket.
- Branch naming: `feat/<issue-number>-<kebab-case-slug>` (e.g. `feat/17-git-workflow`).
- Branch from current `main`. Keep the branch up to date with `main` before merging
  (the protection rule "require branches to be up to date" enforces this).
- `docs/interview-prep.md` is a private, untracked local file — never commit or push it.

## Pull requests

- Open the PR with `gh pr create` (use `--fill` or title/body).
- Put `Closes #<n>` in the PR body so a successful merge closes the ticket.
- CI (`build-and-test`) must pass; `main` protection requires the check and an
  up-to-date branch.
- Merge with **squash** so `main` history stays linear; the squash message is the
  conventional commit for the work.
- **Merging is a human decision.** An agent's job ends when the PR is open and CI
  is green; see "Rules for agents" below.
- No approving review is required on this solo repo (0 required reviews): the PR
  plus CI is the gate. If a second contributor shows up, set the required review
  count to 1.

## Commits

- Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`.
- Imperative, present tense, no trailing period; reference the ticket when useful
  (e.g. `feat: protect main with CI gate (#17)`).
- Commit freely on the feature branch; squash on merge keeps `main` clean.

## `main` protection (enforced in repo settings)

- Require a pull request before merging (0 required reviews)
- Require status checks: `build-and-test` with branches up to date
- Do not allow force pushes; do not allow deletions
- Do not bypass the above via admin override unless truly stuck

## Local guardrails

- The repo ships hooks under `.githooks/`; enable them once per clone:

  ```bash
  git config core.hooksPath .githooks
  ```

- `.githooks/pre-push` rejects any direct push to `refs/heads/main` (covers
  creation, force-push, and deletion). This mirrors the server-side protection on
  machines where it isn't yet configured (e.g. fresh clones). Deliberate bypass:
  `git push --no-verify` — the hook prints a loud message first on purpose.

- **A git hook cannot guard the merge path.** `gh pr merge` is a GitHub API call,
  not a git command: it runs no local git operation, so no hook under
  `.githooks/` can observe or block it, and `main`'s "require a pull request"
  protection is satisfied by the PR existing rather than by anyone approving it.
  A green check is a *precondition* for merging, not an authorisation to merge.
  The merge is gated by the agent's own permission rules
  (`gh pr merge*` → `ask` in `~/.config/opencode/opencode.json`) — a machine-local
  setting, so a fresh clone or a different machine has neither hook nor rule until
  it is configured.

## Rules for agents

- Never commit or push to `main`. Every implementation is a feature branch
  opened as a PR, per `/implement`.
- If a write would touch `main` directly (scaffold, docs, hotfix), it still goes
  through a branch and a PR.
- **Never run `gh pr merge`, or any other merge, without asking the human first.**
  Open the PR, get CI green, report the result, then stop and wait. A squash-merge
  is irreversible from the branch's point of view — `--delete-branch` discards the
  only name the work had — so it is the human's call even when the automated gates
  are all green and the merge is obviously the right next step.
- If a merge turns out to have been made prematurely, do not paper over it. Say so,
  and prefer a revert commit on a branch over a force-push to `main`.