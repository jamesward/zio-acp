#!/usr/bin/env bash
# Starts the Scala interop client: --transport stdio, with STEPS and AGENT_CMD in the environment.
set -euo pipefail
exec java ${JAVA_OPTS:-} -cp "$(cat /interop/classpath.txt)" interop.InteropClient "$@"
