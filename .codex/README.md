# STDISCMP3 Codex environment

This folder prepares a Java implementation of `../requirements.txt`; it does
not contain the application itself.

- `agents/media-upload-engineer.toml`: scoped upload, queue, and storage owner.
- `agents/media-upload-reviewer.toml`: read-only assignment and concurrency review.
- `skills/media-upload-workflow/SKILL.md`: recurring project implementation workflow.
- `plans/01-requirements.md`: required behavior and acceptance evidence.
- `plans/02-architecture.md`: three proposals and the pending architecture choice.
- `plans/03-implementation.md`: ordered milestones and verification plan.
- `config.toml`: agent and skill registrations.

Start with the requirements map and architecture proposals. Record the user's
choice before implementing the application. Java is confirmed; tool versions and
GUI technology are still proposals. Agent definitions can be used when a bounded
delegated task is useful; they do not mandate parallel execution.

After creating or changing local configuration, check that Codex discovers the
registered skill and agent names in a trusted project session. File validation
alone does not establish runtime discovery.
