organization := "com.jamesward"

name := "zio-acp"

scalaVersion := "3.10.0"

scalacOptions ++= Seq(
  "-language:strictEquality",
  "-deprecation",
  "-Werror",
)

// sbt-mcp settings (loopback-only: its tools can execute build tasks)
mcpEnabled := true
mcpHost := "127.0.0.1"
mcpPort := 5150

// SkillsJars: extract agent Skills with `./sbt extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

val zioVersion = "2.1.26"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"         % zioVersion,
  "dev.zio" %% "zio-streams" % zioVersion,
  "dev.zio" %% "zio-json"    % "1.1.0",
  "dev.zio" %% "zio-direct"  % "1.0.0-RC7", // no stable zio-direct release exists yet

  "dev.zio" %% "zio-test"     % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test,

  // interop validation against the official ACP Java and Kotlin SDKs
  "com.agentclientprotocol" % "acp-core"          % "0.18.0" % Test,
  "com.agentclientprotocol" % "acp-json-jackson2" % "0.18.0" % Test,
  "com.agentclientprotocol" % "acp-jvm"           % "0.30.1" % Test,

  // validates our wire format against the vendored ACP v1 JSON Schema
  "com.networknt" % "json-schema-validator" % "3.0.8" % Test,

  "com.jamesward" % "skills" % "0.0.12" % Skills,
)

fork := true

javaOptions ++= Seq(
  "--enable-native-access=ALL-UNNAMED",
  "--sun-misc-unsafe-memory-access=allow",
)

licenses := Seq("Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0"))

homepage := Some(uri("https://github.com/jamesward/zio-acp"))

developers := List(
  Developer(
    "jamesward",
    "James Ward",
    "james@jamesward.com",
    uri("https://jamesward.com")
  )
)

versionScheme := Some("semver-spec")
