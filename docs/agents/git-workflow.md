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

## Rules for agents

- Never commit or push to `main`. Every implementation is a feature branch
  opened as a PR, per `/implement`.
- If a write would touch `main` directly (scaffold, docs, hotfix), it still goes
  through a branch and a PR.