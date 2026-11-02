# AGENTS.md — zio-acp

`zio-acp` is a published Scala 3 / ZIO 2 library (`com.jamesward:zio-acp_3`) implementing version 1 of the
[Agent Client Protocol](https://agentclientprotocol.com) (ACP): the typed v1 schema with its JSON codecs
(`com.jamesward.zioacp.schema`), a bidirectional JSON-RPC 2.0 connection (`jsonrpc`), the agent side (`Agent`,
`AcpAgent`, `ClientConnection`), the client side (`Client`, `AcpClient`, `AgentConnection`), and the stdio, subprocess
and in-memory transports (`Transport`). See `README.md` for the feature overview. It is a library: releases go through
sbt-ci-release with `versionScheme := Some("semver-spec")`.

## Skills

- Follow the `zen-of-projects` Skill (extract with `./sbta extractSkillsJars` into the gitignored `.kiro/skills/`);
  this file records only project-specific facts and exceptions. Read the relevant Skills before working.
- For Scala 3 and ZIO code (writing or reviewing it), follow `zen-of-scala`.
- For design, domain modeling, effects and testing strategy, follow `zen-of-james`.

## Protocol sources

- `src/test/resources/acp-schema-v1.json` is `schema/v1/schema.json` from
  [agentclientprotocol/agent-client-protocol](https://github.com/agentclientprotocol/agent-client-protocol) at
  `1c2b84c785c923ed283a4e7ea3badb2422596418` (Apache-2.0). `SchemaSpec` validates every sample in `Samples.scala`
  against it. When the schema changes, replace the file, update the commit here, and extend the model and `Samples`.
- `CrossSdkSpec` pins the ACP Java SDK commit whose `integration-testing/` suite it runs (`JavaSdkRef`) and the
  Kotlin SDK commit it runs against (`KotlinSdkRef`). Bump them with the other dependencies; a Kotlin bump can change
  the Kotlin SDK's expected failures, which live in that suite's `expectations/kotlin.json`.

## Build, Test & Dev Workflow

- MCP server `sbta` (this build's `./sbta --mcp`, configured in `.mcp.json` and `.kiro/settings/mcp.json`, approved in
  `.claude/settings.json`): use it for all sbt work: `compile`, `test` (`full` for all), `run`, `check` after editing
  Scala 3 sources, `glob-search` / `inspect` / `symbol-location` for symbols, `sbt-task` for any other command. Its
  tools are deferred in Claude Code: load them with ToolSearch (search `sbta`). If it's unavailable, say so and use
  `./sbta <command>`. Logs: `project/target/sbta/mcp.log`.
- Commands (CLI fallback form):
  - `./sbta "Test / compile"` — compile main + tests.
  - `./sbta testFull` — full test suite (what CI runs): codecs, schema conformance, agent/client in memory, the ACP
    Java SDK in both directions (`JavaSdkInteropSpec`), and `CrossSdkSpec`.
  - `./sbta "testOnly *SchemaSpec"` — a single spec.
  - `./sbta "testOnly *CrossSdkSpec"` — the ACP SDKs' cross-SDK suite: its step catalogue between the Scala interop
    programs and the Java and Kotlin SDKs (four stdio cells), and its raw JSON-RPC driver against the Scala agent and
    client.
  - `./sbta interopClasspath` — writes `target/interop-classpath.txt` for running the interop programs by hand.
- `CrossSdkSpec` needs Docker (see "Docker for tests" in zen-of-projects). Its image
  (`src/test/resources/interop/Dockerfile`, built by Testcontainers) holds JDK 21, Python, JBang and the peer SDKs
  built from source; the first build takes about 15 minutes, later runs reuse Docker's layer cache. The Scala programs
  are the test classpath, mounted into the container. The spec passes the host's environment config into the image
  build when it exists: `HTTPS_PROXY`, the CA bundle (`SSL_CERT_FILE` and friends), `~/.m2/settings.xml` (whose mirror
  the Java SDK's Maven Wrapper also uses) and `~/.gradle/init.d/` scripts. With a warm image `testFull` takes about a
  minute.
- The interop programs (`src/test/scala/interop`) implement the cross-SDK step catalogue (`steps.json` in the Java
  SDK's `integration-testing/`): `InteropAgent` and `InteropClient`, launched in the container by
  `src/test/resources/interop/scala/launch/*.sh`. Expected failures caused by zio-acp go in
  `src/test/resources/interop/scala-expectations.json` (format: the Java SDK's
  `integration-testing/expectations/README.md`).
- Tests need no network beyond dependency resolution and the first `CrossSdkSpec` image build, and none are paid or
  metered.

## Maintenance

- Maintenance routine: `.factory/MAINTENANCE.md` (weekly), following the `zen-of-projects` Skill.

## Releasing

- Run the `Tag Release` workflow (or push a `v<major>.<minor>.<patch>` tag); `release.yml` runs `testFull` then
  `ci-release`.

## Exceptions to zen-of-projects

- `dev.zio:zio-direct` has no stable release; keep it on its newest release (RC) as long as tests pass.
- The codecs are derived with zio-json (`derives JsonCodec`) instead of zio-schema, and the unions the ACP schema
  defines with flattened or untagged variants, open values and catch-all cases are assembled from derived codecs with
  the helpers in `schema/JsonSupport.scala`.
- Test-only dependencies `com.agentclientprotocol:acp-core` and `acp-json-jackson2` (the ACP Java SDK),
  `com.networknt:json-schema-validator` and `org.testcontainers:testcontainers` are interop and conformance tooling,
  not library dependencies.
