#!/usr/bin/env bash
# Starts the Scala interop agent: --transport stdio. Prints nothing itself: stdout is the protocol stream.
set -euo pipefail
exec java ${JAVA_OPTS:-} -cp "$(cat "${ZIO_ACP:?ZIO_ACP is not set}/target/interop-classpath.txt")" interop.InteropAgent "$@"
