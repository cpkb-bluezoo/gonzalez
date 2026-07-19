#!/bin/bash
# Compiles and runs ExternalCompare.java: a throughput comparison of
# Gonzalez's raw XMLHandler and SAXAdapter paths against the JDK's bundled
# Xerces implementation (no download needed) and a locally-built aalto-xml
# (built from ~/github/aalto-xml via its own Maven build).
#
# Deliberately not wired into build.xml or ant - a standalone script, no new
# project dependency. Run from the repo root, or this script will cd there
# itself.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

AALTO_DIR="${AALTO_DIR:-$HOME/github/aalto-xml}"
AALTO_JAR="$(find "$AALTO_DIR/target" -maxdepth 1 -name 'aalto-xml-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)"
if [ -z "$AALTO_JAR" ]; then
    echo "No aalto-xml jar found under $AALTO_DIR/target - building it..."
    (cd "$AALTO_DIR" && ./mvnw -q -DskipTests package)
    AALTO_JAR="$(find "$AALTO_DIR/target" -maxdepth 1 -name 'aalto-xml-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)"
fi
echo "Using aalto-xml jar: $AALTO_JAR"

STAX2_JAR="$(find "$HOME/.m2/repository/org/codehaus/woodstox/stax2-api" -name 'stax2-api-*.jar' | sort -V | tail -1)"
if [ -z "$STAX2_JAR" ]; then
    echo "stax2-api jar not found in ~/.m2 - run 'cd $AALTO_DIR && ./mvnw -q -DskipTests package' first" >&2
    exit 1
fi
echo "Using stax2-api jar: $STAX2_JAR"

echo "Building Gonzalez core..."
ant -q build-core

WORK="$REPO_ROOT/benchmark/external-compare/classes"
rm -rf "$WORK"
mkdir -p "$WORK"

CP="build/core:$AALTO_JAR:$STAX2_JAR"

echo "Compiling ExternalCompare.java..."
javac -cp "$CP" -d "$WORK" benchmark/external-compare/ExternalCompare.java

echo "Running comparison (this takes a minute or two)..."
echo
java -cp "$WORK:$CP" org.bluezoo.gonzalez.ExternalCompare
