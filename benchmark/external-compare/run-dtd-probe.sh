#!/bin/bash
# Compiles and runs DtdProbe.java: inspects the SAX events each parser emits
# for the XHTML internal-subset corpus to establish whether the internal DTD
# subset is actually processed (see DtdProbe.java for the specific facts
# checked). Set AALTO_JAR and STAX2_JAR to the corresponding dependency jars.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

require_jar() {
    local name="$1"
    local value="${!name:-}"
    if [ -z "$value" ]; then
        echo "Error: $name is not set; set it to the dependency jar path." >&2
        exit 1
    fi
    if [ ! -f "$value" ]; then
        echo "Error: $name does not name a file: $value" >&2
        exit 1
    fi
}

require_jar AALTO_JAR
require_jar STAX2_JAR

echo "Using aalto-xml jar: $AALTO_JAR"
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
