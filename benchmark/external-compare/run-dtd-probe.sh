#!/bin/bash
# Compiles and runs DtdProbe.java: inspects the SAX events each parser emits
# for the XHTML internal-subset corpus to establish whether the internal DTD
# subset is actually processed (see DtdProbe.java for the specific facts
# checked). Shares the aalto-xml/stax2 discovery logic with run.sh.
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

echo "Compiling DtdProbe.java..."
javac -cp "$CP" -d "$WORK" \
    benchmark/external-compare/BenchmarkCorpora.java \
    benchmark/external-compare/DtdProbe.java

echo "Running DTD probe..."
echo
java -cp "$WORK:$CP" org.bluezoo.gonzalez.DtdProbe
