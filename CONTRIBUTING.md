# Contributing

This repository is built by a fleet of agents coordinated through the GitHub issue tracker.

## Workflow

1. Every change traces to an issue. Issue titles are verb phrases; bodies carry **Goal**, **Acceptance criteria**, **Scope limits**, **Relationships**.
2. Work happens on a branch named `issue-<N>-<slug>` in its own worktree. Commit frequently; push in reasonable clumps.
3. Open a PR with `Closes #N`. CI (`.github/workflows/ci.yml`) must pass.
4. **Competing implementations.** For core code, two implementers (Claude Sonnet 5.5 and GPT 6.1 Sol) each produce a candidate. The council (Claude Fable 5.1, GPT 6 Astra, GPT 6.1 Sol) scores each out of 10 and picks one, the other, both (merged), or neither. Iterate until all delegates are satisfied. Scores live in the PR description under **Council review**.
5. **Code review** is done by GPT 6 Astra, read-only. **UI** work is done by Claude Opus 5.5. **Discovery and simple code** by GPT 6 Luna.
6. Labels: `area:*` for the component, `qualify:emulator` / `qualify:tor` to trigger gated CI jobs, `council:pending` / `council:approved` for review state.

## Hard constraints

- No server, no FCM or any push service. Presence is opt-in: both peers go online deliberately.
- Tor is used as relay-style NAT traversal (both sides outbound). Do not describe it as hole punching.
- Audio is Opus, 24 kbps, mono, 20 ms frames, over a single TCP stream through Tor.
- The report body must stay between 2 and 3 pages.

## Commit attribution

End commit messages with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and PR descriptions with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
