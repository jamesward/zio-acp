"""Adds zio-acp to the cross-SDK matrix as the language "scala": its stdio cells against Java and Kotlin."""
import json
import sys

path = sys.argv[1]
matrix = json.load(open(path))
matrix["languages"]["scala"] = {
    "enabled": True,
    "dir": "programs/scala",
    "peers": [],
    "env": {},
    "transports": ["stdio"],
    "readyTimeoutSec": 90,
}
matrix["pairs"] += [["java", "scala"], ["scala", "java"], ["kotlin", "scala"], ["scala", "kotlin"]]
json.dump(matrix, open(path, "w"), indent=2)
