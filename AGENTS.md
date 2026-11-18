# STDISCMP3 project guidance

## Purpose and source of truth

Implement the media upload producer-consumer exercise in **Java**. Read
`requirements.txt` as the assignment specification, not as a Python dependency
file. Preserve it. Producers and the consumer run on different virtual machines
and communicate through gRPC.

Follow the global working agreement through `~/.codex/AGENTS.md`. Resolve
`standards/engineering.md` from the canonical environment identified there.
Use the shared Java engineering skill for Java implementation.

## Current state and decisions

Joshua chose Option B on 2026-11-18: Maven modules `protocol`, `producer`, and
`consumer`; Java producers and a Spring Boot gRPC consumer with a browser GUI.
Java 17 bytecode, Maven 3.9.11 wrapper, Spring Boot 3.5.16, gRPC 1.85.1, protobuf
4.36.2. Read the project plans and `docs/verification.md` for observed evidence.
The separate-VM demonstration remains unverified. Slide content exists, but a
PowerPoint export is pending the required presentation runtime.

Verified commands (PowerShell, JAVA_HOME pointing to JDK 17):
- `.\mvnw.cmd -B clean verify`: Java formatting, 32 tests, executable JARs.
- `npm run format:check`: optional frontend/browser-test formatting checks.
- `npm run test:browser`: 14 scenarios; requires running consumer and fixtures.
Stop the consumer before clean builds on Windows because its JAR is locked.
See README.md for executable launch syntax and optional browser setup.

## Project workflow

- State intended file changes before substantive edits; preserve unrelated work.
- Use `.codex/plans/03-implementation.md` for milestones and acceptance checks.
- Keep mandatory behavior separate from bonus features.
- Treat `p` as producer workers, each assigned a distinct source folder; `c` as
  consumer workers; and `q` as the bounded queue capacity. Finalize exact queue
  admission and worker semantics in the approved design before implementation.
- Review queue admission, overload drops, cancellation, partial-file cleanup,
  filename collisions, and GUI thread access as first-class correctness concerns.
- Do not silently add an external broker, database, or paid service.
- Update commands here after a build tool is chosen and commands actually run.
- Verify meaningful behavior and self-review changes against the shared standard.
  Report failures and unverified VM or media checks accurately.

## Codex extensions

Keep project extensions under `.codex/`. Registered project agents own narrowly
assigned work; their existence does not require automatic delegation. Give any
delegated editor explicit file ownership and tell it that others share the
workspace and their changes must be preserved.

Use `$media-upload-workflow` for recurring implementation and verification work
on this exercise. Register project skills in `.codex/config.toml`. Keep
project-specific findings local; global environment maintenance is out of scope.
