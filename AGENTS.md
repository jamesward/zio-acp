# AGENTS.md — zio-acp

`zio-acp` is a published Scala 3 / ZIO 2 library (`com.jamesward:zio-acp_3`) implementing version 1 of the
[Agent Client Protocol](https://agentclientprotocol.com) (ACP): the typed v1 schema with its JSON codecs
(`com.jamesward.zioacp.schema`), a bidirectional JSON-RPC 2.0 connection (`jsonrpc`), the agent side (`Agent`,
`AcpAgent`, `ClientConnection`), the client side (`Client`, `AcpClient`, `AgentConnection`), and the stdio, subprocess
and in-memory transports (`Transport`). See `README.md` for the feature overview. It is a library: releases go through
sbt-ci-release with `versionScheme := Some("semver-spec")`.

Follow the `zen-of-projects` Skill (extract with `./sbt extractSkillsJars`); this file records only project-specific
facts and exceptions.

## Skills

- `zen-of-scala` — Scala 3 / ZIO idioms for all code and review.
- `zen-of-james` — design, domain modeling, effects, and testing principles.
- `zen-of-projects` — project/build conventions.

## Exceptions to zen-of-projects

- `dev.zio:zio-direct` has no stable release; keep it on its newest release (RC) as long as tests pass.
- The codecs are derived with zio-json (`derives JsonCodec`) instead of zio-schema, and the unions the ACP schema
  defines with flattened or untagged variants, open values and catch-all cases are assembled from derived codecs with
  the helpers in `schema/JsonSupport.scala`.
- Test-only dependencies `com.agentclientprotocol:acp-core` and `acp-json-jackson2` (the ACP Java SDK) and
  `com.networknt:json-schema-validator` are interop and schema-conformance tooling, not library dependencies.

## Protocol sources

- `src/test/resources/acp-schema-v1.json` is `schema/v1/schema.json` from
  [agentclientprotocol/agent-client-protocol](https://github.com/agentclientprotocol/agent-client-protocol) at
  `1c2b84c785c923ed283a4e7ea3badb2422596418` (Apache-2.0). `SchemaSpec` validates every sample in `Samples.scala`
  against it. When the schema changes, replace the file, update the commit here, and extend the model and `Samples`.
- `integration-testing/run.sh` pins the ACP Java SDK commit whose `integration-testing/` suite it runs
  (`JAVA_SDK_REF`). Bump it with the other dependencies.

## Build, Test & Dev Workflow

- Use the MCP server named `sbt-mcp-zio-acp` (`http://127.0.0.1:5150/`) for sbt interactions: `sbt-task` (separate
  commands with `;`), `list-tasks`, `check` (`"scope":"module"` after API changes), and `glob-search` / `inspect` /
  `symbol-location` plus the proxied javadocs.dev tools for symbol lookups. If it is unavailable, say so and fall back
  to the `./sbt` launcher.
  - Kiro: HTTP entry in `.kiro/settings/mcp.json`; start sbt first.
  - Claude Code: `.mcp.json` runs `.claude/sbt-mcp-stdio.sh` (approved in `.claude/settings.json`), a stdio bridge
    that starts a foreground sbt in cloud sessions (`CLAUDE_CODE_REMOTE=true`) and connects to an already-running sbt
    locally. Its tools are deferred: load them with ToolSearch (search `sbt-mcp-zio-acp`). Diagnostics:
    `/tmp/sbt-mcp-stdio.log`, `/tmp/sbt-mcp-server.log`.
- Commands (CLI fallback form):
  - `./sbt "Test / compile"` — compile main + tests.
  - `./sbt testFull` — full test suite (what CI runs): codecs, schema conformance, agent/client in memory, and the ACP
    Java SDK in both directions (`JavaSdkInteropSpec` starts the Scala interop agent as a subprocess).
  - `./sbt "testOnly *SchemaSpec"` — a single spec.
  - `./sbt interopClasspath` — writes `target/interop-classpath.txt` for running the interop programs by hand.
  - `integration-testing/run.sh` — the ACP cross-SDK suite (Java and Kotlin SDKs, raw JSON-RPC driver); CI runs it in
    `.github/workflows/interop.yml`. It needs JBang, python3, git, and JDK 21 for the Kotlin cells; the first run
    clones and builds the peer SDKs (several minutes). `--only <cell>` and `--list` are passed to the runner.
- The interop programs (`src/test/scala/interop`) implement the cross-SDK step catalogue (`steps.json` in the Java
  SDK's `integration-testing/`): `InteropAgent` and `InteropClient`, launched by
  `integration-testing/programs/scala/launch/*.sh`. Expected failures caused by zio-acp go in
  `integration-testing/expectations/scala.json` (format: the Java SDK's `integration-testing/expectations/README.md`).
- Tests need no network beyond dependency resolution, and none are paid or metered.

## Releasing

- Run the `Tag Release` workflow (or push a `v<major>.<minor>.<patch>` tag); `release.yml` runs `testFull` then
  `ci-release`.
