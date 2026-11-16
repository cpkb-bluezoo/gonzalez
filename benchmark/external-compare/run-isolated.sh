#!/bin/bash
# Fresh-JVM throughput comparison: for each corpus and mode, runs IsolatedAB
# in a new JVM REPS times and reports the median MB/s. Set AALTO_JAR and
# STAX2_JAR to the dependency jars, as for run.sh.
#
#   benchmark/external-compare/run-isolated.sh [reps] [corpora] [modes]
#
# corpora and modes are space-separated lists in quotes; the defaults are all.
# Set UNIT=us to report microseconds per parse instead of MB/s, which suits the
# small corpora (tiny small books attrs-small).
# Set GONZALEZ_CLASSES to use another build of Gonzalez than build/core (for
# comparing two versions of the code).
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

REPS="${1:-5}"
CORPORA="${2:-plain attrs whitespace markup multibyte utf16 euc-jp xhtml-dtd}"
MODES="${3:-xmlhandler xmlhandler-nolimits sax jdk aalto-sax aalto-stax}"
CLASSES="${GONZALEZ_CLASSES:-build/core}"

if [ -z "${GONZALEZ_CLASSES:-}" ]; then
    ant -q build-core
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CP="$CLASSES:$AALTO_JAR:$STAX2_JAR"
javac -cp "$CP" -d "$WORK" \
    benchmark/external-compare/BenchmarkCorpora.java \
    benchmark/external-compare/IsolatedAB.java

printf "%-12s" "corpus"
for mode in $MODES; do printf " %20s" "$mode"; done
if [ "${UNIT:-}" = "us" ]; then
    echo "   (median microseconds per parse of $REPS fresh JVMs)"
else
    echo "   (median MB/s of $REPS fresh JVMs)"
fi
for corpus in $CORPORA; do
    printf "%-12s" "$corpus"
    for mode in $MODES; do
        results=""
        for _ in $(seq 1 "$REPS"); do
            mbs=$(java -cp "$WORK:$CP" org.bluezoo.gonzalez.IsolatedAB "$corpus" "$mode" \
                  | awk -v unit="${UNIT:-}" '{ if (unit == "us") print $(NF-3) * 1000; else print $(NF-1) }')
            results="$results $mbs"
        done
        median=$(echo $results | tr ' ' '\n' | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}')
        printf " %20s" "$median"
    done
    echo
done
