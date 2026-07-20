#!/bin/bash
# Compiles and runs XsltPerfCompare: Gonzalez vs JDK JAXP vs Saxon-HE.
# Downloads Saxon-HE into lib/ via Ant on first run. Run from repo root.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

SAXON_VERSION=12.9
XMLRESOLVER_VERSION=5.3.3
SAXON_JAR="${REPO_ROOT}/lib/Saxon-HE-${SAXON_VERSION}.jar"
XMLRESOLVER_JAR="${REPO_ROOT}/lib/xmlresolver-${XMLRESOLVER_VERSION}.jar"
XMLRESOLVER_DATA_JAR="${REPO_ROOT}/lib/xmlresolver-${XMLRESOLVER_VERSION}-data.jar"

echo "Downloading Saxon-HE (skipexisting) and building Gonzalez..."
ant -q download-saxon build

for jar in "$SAXON_JAR" "$XMLRESOLVER_JAR" "$XMLRESOLVER_DATA_JAR"; do
    if [ ! -f "$jar" ]; then
        echo "Error: jar not found at $jar" >&2
        exit 1
    fi
done

JSONPARSER_JAR="${REPO_ROOT}/lib/jsonparser-1.3.jar"
WORK="${REPO_ROOT}/benchmark/xslt-compare/classes"
rm -rf "$WORK"
mkdir -p "$WORK"

CP="build/core:build/xslt:${JSONPARSER_JAR}:${SAXON_JAR}:${XMLRESOLVER_JAR}:${XMLRESOLVER_DATA_JAR}"

echo "Compiling XsltPerfCompare.java..."
javac -cp "$CP" -d "$WORK" benchmark/xslt-compare/XsltPerfCompare.java

echo "Running performance comparison..."
echo
java -cp "$WORK:$CP" org.bluezoo.gonzalez.xsltcompare.XsltPerfCompare
