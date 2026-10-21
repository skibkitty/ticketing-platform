# AGENTS.md

## Agent skills

### Issue tracker

Issues and specs live as GitHub issues, operated via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five default labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` at the repo root, ADRs under `docs/adr/`. See `docs/agents/domain.md`.

### Git workflow

Never commit or push to `main`. Work on a feature branch per ticket
(`feat/<issue-number>-<kebab-slug>`), open a PR with `Closes #<issue>`, and
stop there: **merging is the human's decision, never the agent's** — green CI
is a precondition for a merge, not authorisation to perform one. See
`docs/agents/git-workflow.md`. Enable the guardrail hooks once per clone with
`git config core.hooksPath .githooks`; note they cover pushes only and cannot
guard the merge path.