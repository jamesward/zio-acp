#!/usr/bin/env bash
# Runs the ACP cross-SDK interop suite of the ACP Java SDK (integration-testing/ in
# agentclientprotocol/java-sdk) with zio-acp added as the language "scala": the step catalogue
# (steps.json) between the Scala programs and the Java and Kotlin SDK programs over stdio, then the
# raw JSON-RPC conformance driver (programs/raw/raw.py) against the Scala agent and client.
#
#   integration-testing/run.sh                 # every Scala cell
#   integration-testing/run.sh --only x-java-scala-stdio
#   integration-testing/run.sh --list
#
# Needs JDK 17+ (and JDK 21 for the Kotlin cells), JBang, git and python3. Logs go to
# integration-testing/.cache/java-sdk/integration-testing/logs/.
set -euo pipefail

JAVA_SDK_REF="${JAVA_SDK_REF:-5bb2cf62d2349fb9c47ec9ba8ad0a27762713e59}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
sdk="$here/.cache/java-sdk"

if [ ! -d "$sdk/.git" ]; then
    git clone --quiet https://github.com/agentclientprotocol/java-sdk.git "$sdk"
fi
if [ "$(git -C "$sdk" rev-parse HEAD)" != "$JAVA_SDK_REF" ]; then
    git -C "$sdk" fetch --quiet origin "$JAVA_SDK_REF"
    git -C "$sdk" checkout --quiet --force "$JAVA_SDK_REF"
fi
# start from the pinned tree: drop the configs a previous run generated
git -C "$sdk" checkout --quiet -- integration-testing
git -C "$sdk" clean --quiet -fd -- integration-testing/configs integration-testing/programs/scala

it="$sdk/integration-testing"
cp -r "$here/programs/scala" "$it/programs/scala"
cp "$here/expectations/scala.json" "$it/expectations/scala.json"

python3 - "$it/matrix.json" "$root" <<'PY'
import json, sys
path, root = sys.argv[1], sys.argv[2]
matrix = json.load(open(path))
matrix["languages"]["scala"] = {
    "enabled": True,
    "dir": "programs/scala",
    "peers": [],
    "env": {"ZIO_ACP": root},
    "transports": ["stdio"],
    "readyTimeoutSec": 90,
}
matrix["pairs"] += [["java", "scala"], ["scala", "java"], ["kotlin", "scala"], ["scala", "kotlin"]]
json.dump(matrix, open(path, "w"), indent=2)
PY

cd "$it"
jbang GenConfigs.java > /dev/null
case " $* " in
    *" --only"*|*" --tag"*) set -- "$@" ;;
    *) set -- --only 'x-*-scala-*,x-scala-*' "$@" ;;
esac
status=0
scripts/run-all.sh "$@" || status=$?
case " $* " in *" --list "*|*" --prepare-only "*) exit $status ;; esac

# The raw JSON-RPC conformance driver (programs/raw/raw.py) against the Scala programs, as the
# conf-java-agent-stdio and conf-java-client-catalogue-stdio scenarios do for the Java ones.
export ZIO_ACP="$root"
logs="$it/logs/conf-scala"
mkdir -p "$logs"
scala="$it/programs/scala/launch"
"$scala/build.sh"

AGENT_CMD="$scala/agent.sh --transport stdio" python3 -u programs/raw/raw.py client --transport stdio > "$logs/agent.log" 2>&1 || true
if grep -q '^RESULT pass=[0-9]* fail=0 ' "$logs/agent.log"; then
    echo "PASS conf-scala-agent-stdio: $(grep '^RESULT' "$logs/agent.log")"
else
    echo "FAIL conf-scala-agent-stdio (log: $logs/agent.log)"; grep -E '^STEP .* FAIL|^RESULT' "$logs/agent.log" || true; status=1
fi

STEPS="init.initialize,session.new,session.load,update.agent_message_chunk,update.unknown,enum.tool_call,enum.plan,enum.stop,enum.audience,config.grouped,perm.selected,fs.write,error.method-not-found,mode.set,auth.authenticate,auth.logout,session.delete,stdio.eof-exit,conn.close" \
AGENT_CMD="python3 -u $it/programs/raw/raw.py agent --transport stdio" "$scala/client.sh" --transport stdio > "$logs/client.log" 2>&1 || true
if grep -q '^RESULT pass=[0-9]* fail=0 ' "$logs/client.log" && ! grep -q '^STEP .* FAIL' "$logs/client.log" && grep -q '^STEP agent.raw.' "$logs/client.log"; then
    echo "PASS conf-scala-client-catalogue-stdio: $(grep '^RESULT' "$logs/client.log") agent.raw cases: $(grep -c '^STEP agent.raw.* PASS' "$logs/client.log")"
else
    echo "FAIL conf-scala-client-catalogue-stdio (log: $logs/client.log)"; grep -E '^STEP .* FAIL|^RESULT' "$logs/client.log" || true; status=1
fi
exit $status
