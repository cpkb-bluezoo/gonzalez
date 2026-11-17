#!/bin/bash
# Parses a document with one very large construct, in a small heap, with each
# parser in turn, and says which of them get to the end of it. The document is
# made up as it is read (see MemoryProbe), so nothing about the harness is
# large. Set AALTO_JAR and STAX2_JAR as for run-isolated.sh, or run "ant
# benchmark-memory", which fetches them.
#
#   benchmark/external-compare/run-memory.sh [megabytes] [heap]
#
# The construct is 256 MB and the heap 32 MB unless told otherwise.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

for name in AALTO_JAR STAX2_JAR; do
    value="${!name:-}"
    if [ -z "$value" ] || [ ! -f "$value" ]; then
        echo "Error: $name must name a jar file." >&2
        exit 1
    fi
done

SIZE="${1:-256}"
HEAP="${2:-32m}"
CLASSES="${GONZALEZ_CLASSES:-build/core}"
MODES="${MODES:-xmlhandler sax jdk aalto-sax aalto-stax}"
SCENARIOS="${SCENARIOS:-text cdata attribute comment elements}"

if [ -z "${GONZALEZ_CLASSES:-}" ]; then
    ant -q build-core
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CP="$CLASSES:$AALTO_JAR:$STAX2_JAR"
javac -cp "$CP" -d "$WORK" benchmark/external-compare/MemoryProbe.java

printf "%-12s" "construct"
for mode in $MODES; do printf " %14s" "$mode"; done
echo "   (${SIZE} MB in a ${HEAP} heap)"
for scenario in $SCENARIOS; do
    printf "%-12s" "$scenario"
    for mode in $MODES; do
        result=$(java -Xmx"$HEAP" -cp "$WORK:$CP" org.bluezoo.gonzalez.MemoryProbe "$mode" "$scenario" "$SIZE" 2>&1 | tail -1)
        case "$result" in
            ok*) result="ok" ;;
            out-of-memory*) result="OUT OF MEMORY" ;;
        esac
        printf " %14s" "$result"
    done
    echo
done
