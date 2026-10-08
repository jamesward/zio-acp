#!/usr/bin/env bash
# Builds the Scala interop programs (src/test/scala/interop) and writes their classpath.
# Launcher contract: java-sdk integration-testing/README.md, "Contracts". Env: ZIO_ACP (the zio-acp checkout).
set -euo pipefail
cd "${ZIO_ACP:?ZIO_ACP is not set}"
./sbt --client --no-colors interopClasspath > /dev/null
