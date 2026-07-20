#!/bin/bash
# Multi-engine W3C xslt30-test bake-off (Gonzalez / JDK / Saxon-HE).
# Requires XSLT30_TEST_DIR. Downloads Saxon-HE into lib/ via Ant.
# Optional: -Dxslt.version=1.0|2.0|3.0 -Dxslt.filter=... -Dxslt.limit=N
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

if [ -z "${XSLT30_TEST_DIR:-}" ]; then
    echo "Error: XSLT30_TEST_DIR is not set; set it to the xslt30-test checkout." >&2
    exit 1
fi
if [ ! -f "${XSLT30_TEST_DIR}/catalog.xml" ]; then
    echo "Error: catalog.xml not found under XSLT30_TEST_DIR=${XSLT30_TEST_DIR}" >&2
    exit 1
fi

SAXON_VERSION=12.9
XMLRESOLVER_VERSION=5.3.3
SAXON_JAR="${REPO_ROOT}/lib/Saxon-HE-${SAXON_VERSION}.jar"
XMLRESOLVER_JAR="${REPO_ROOT}/lib/xmlresolver-${XMLRESOLVER_VERSION}.jar"
XMLRESOLVER_DATA_JAR="${REPO_ROOT}/lib/xmlresolver-${XMLRESOLVER_VERSION}-data.jar"
JSONPARSER_JAR="${REPO_ROOT}/lib/jsonparser-1.3.jar"

echo "Downloading Saxon-HE (skipexisting) and building Gonzalez..."
ant -q download-saxon build

for jar in "$SAXON_JAR" "$XMLRESOLVER_JAR" "$XMLRESOLVER_DATA_JAR"; do
    if [ ! -f "$jar" ]; then
        echo "Error: jar not found at $jar" >&2
        exit 1
    fi
done

WORK="${REPO_ROOT}/benchmark/xslt-compare/classes"
rm -rf "$WORK"
mkdir -p "$WORK" "${REPO_ROOT}/benchmark/xslt-compare/out"

CP="build/core:build/xslt:${JSONPARSER_JAR}:${SAXON_JAR}:${XMLRESOLVER_JAR}:${XMLRESOLVER_DATA_JAR}"

echo "Compiling conformance compare..."
javac -cp "$CP" -d "$WORK" \
    test/conformance/src/org/bluezoo/gonzalez/transform/XMLComparator.java \
    benchmark/xslt-compare/CatalogParser.java \
    benchmark/xslt-compare/CompareSupport.java \
    benchmark/xslt-compare/XmlCompare.java \
    benchmark/xslt-compare/EngineAdapters.java \
    benchmark/xslt-compare/XsltConformanceCompare.java

JAVA_OPTS=()
# Forward -Dxslt.* system properties from the environment-style args or JAVA_TOOL_OPTIONS
for arg in "$@"; do
    JAVA_OPTS+=("$arg")
done

echo "Running conformance comparison against ${XSLT30_TEST_DIR}..."
echo
java -cp "$WORK:$CP" "${JAVA_OPTS[@]}" org.bluezoo.gonzalez.xsltcompare.XsltConformanceCompare
