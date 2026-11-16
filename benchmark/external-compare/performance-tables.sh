#!/bin/bash
# Runs the comparison with the JDK's parser and aalto-xml and prints the tables
# of PERFORMANCE.md, as Markdown, to standard output. Progress goes to
# standard error.
#
#   benchmark/external-compare/performance-tables.sh [reps]
#
# reps is how many fresh JVMs each figure is the median of; the default is 5,
# which is what the figures in PERFORMANCE.md are. Set AALTO_JAR and
# STAX2_JAR to the dependency jars, as for run-isolated.sh, and
# GONZALEZ_CLASSES to measure a build other than build/core. To try it out
# more quickly, CORPORA="plain accent" runs only the corpora named, of those
# in the first table below.
#
# BENCH_REF, if set, is how the commit is described in the heading, for a run
# in which git does not know it as a branch.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

REPS="${1:-5}"
RUN=benchmark/external-compare/run-isolated.sh
CORPORA="${CORPORA:-plain attrs whitespace markup multibyte accent utf16 euc-jp xhtml-dtd}"

log() { echo "$@" >&2; }

# cells <corpora> <modes>: one line per corpus, "corpus value value ...", the
# medians of run-isolated.sh in the order of the modes.
cells() {
    "$RUN" "$REPS" "$1" "$2" 2>/dev/null | awk -v want="$1" '
        BEGIN { n = split(want, w, " "); for (i = 1; i <= n; i++) ok[w[i]] = 1 }
        ok[$1] { print }'
}

# size <corpus>: its size in bytes
size() {
    java -cp "$WORK:$CP" org.bluezoo.gonzalez.IsolatedAB "$1" size
}

# The classes are compiled once here, so that size can be asked of them.
for name in AALTO_JAR STAX2_JAR; do
    value="${!name:-}"
    if [ -z "$value" ] || [ ! -f "$value" ]; then
        echo "Error: $name must name a jar file." >&2
        exit 1
    fi
done
if [ -z "${GONZALEZ_CLASSES:-}" ]; then
    ant -q build-core >&2
fi
CP="${GONZALEZ_CLASSES:-build/core}:$AALTO_JAR:$STAX2_JAR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
javac -cp "$CP" -d "$WORK" \
    benchmark/external-compare/BenchmarkCorpora.java \
    benchmark/external-compare/IsolatedAB.java

# ---- the heading ---------------------------------------------------------

commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
ref="${BENCH_REF:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
cpu="$( (grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//') \
        || (sysctl -n machdep.cpu.brand_string 2>/dev/null) || true)"
cores="$( (nproc 2>/dev/null) || (sysctl -n hw.ncpu 2>/dev/null) || echo '?')"
jvm="$(java -version 2>&1 | head -1)"
aalto="$(basename "$AALTO_JAR" .jar)"

echo "## Performance comparison"
echo
echo "- Gonzalez: \`$ref\` at \`$commit\`"
echo "- JDK: $jvm"
echo "- aalto-xml: \`$aalto\`"
echo "- Machine: ${cpu:-unknown}, $cores logical processors"
echo "- Each figure is the median of $REPS fresh JVMs."
echo
echo "Figures from different machines, or from a machine shared with other work"
echo "as a hosted runner is, are not comparable with one another; compare the"
echo "columns of one run."
echo

# fmt <number> [decimals]: with thousands separators
awk_fmt='
function fmt(v, d,    s, i, out, n, neg) {
    if (d == "") d = 0
    s = sprintf("%." d "f", v)
    n = index(s, "."); int_part = (n ? substr(s, 1, n - 1) : s); frac = (n ? substr(s, n) : "")
    out = ""
    for (i = length(int_part); i > 0; i -= 3)
        out = substr(int_part, (i - 2 > 0 ? i - 2 : 1), (i - 2 > 0 ? 3 : i)) (out == "" ? "" : "," out)
    return out frac
}'

name_of() {
    case "$1" in
        plain) echo "Plain UTF-8" ;;
        attrs) echo "Attribute-heavy UTF-8" ;;
        whitespace) echo "Whitespace-heavy UTF-8" ;;
        markup) echo "Markup-heavy UTF-8" ;;
        multibyte) echo "Multibyte UTF-8" ;;
        accent) echo "Mostly ASCII with accents" ;;
        utf16) echo "Japanese UTF-16LE" ;;
        euc-jp) echo "Japanese EUC-JP" ;;
        xhtml-dtd) echo "XHTML internal DTD (not equivalent)" ;;
        *) echo "$1" ;;
    esac
}

# mib <bytes>
mib() { awk -v b="$1" 'BEGIN { printf "%.2f MiB", b / 1048576 }'; }

# ---- throughput, empty handlers ------------------------------------------

log "Throughput with empty handlers..."
echo "### Throughput"
echo
echo "The SAX parsers are given an empty handler; the \`XMLHandler\` column is the"
echo "native path with an empty \`XMLHandler\`."
echo
echo "| Corpus | Size | Gonzalez XMLHandler | Gonzalez SAX | JDK Xerces SAX | Aalto SAX | Aalto StAX cursor |"
echo "|--------|-----:|--------------------:|-------------:|----------------:|----------:|------------------:|"
cells "$CORPORA" "xmlhandler sax jdk aalto-sax aalto-stax" | while read -r corpus a b c d e; do
    label="$(name_of "$corpus")"
    bytes="$(size "$corpus")"
    awk -v label="$label" -v size="$(mib "$bytes")" -v corpus="$corpus" \
        -v a="$a" -v b="$b" -v c="$c" -v d="$d" -v e="$e" "$awk_fmt"'
        BEGIN {
            # aalto-xml does not process the DTD, so its figures there are
            # not like for like
            ad = fmt(d) " MB/s"; ae = fmt(e) " MB/s"
            if (corpus == "xhtml-dtd") { ad = "*" ad "†*"; ae = "*" ae "†*" }
            printf "| %s | %s | **%s MB/s** | %s MB/s | %s MB/s | %s | %s |\n",
                label, size, fmt(a), fmt(b), fmt(c), ad, ae
        }'
done
case " $CORPORA " in
    *" xhtml-dtd "*)
        echo
        echo "† Aalto's figures are non-validating: it skips the internal DTD declarations."
        ;;
esac
echo

# ---- throughput, a handler that reads everything --------------------------

log "Throughput with a handler that reads everything..."
echo "### Throughput with a handler that reads every attribute and all the text"
echo
echo "| Corpus | Gonzalez SAX | Aalto SAX |"
echo "|--------|-------------:|----------:|"
cells "$CORPORA" "sax-touch aalto-sax-touch" | while read -r corpus a b; do
    awk -v label="$(name_of "$corpus")" -v corpus="$corpus" -v a="$a" -v b="$b" "$awk_fmt"'
        BEGIN {
            ab = fmt(b) " MB/s"
            if (corpus == "xhtml-dtd") ab = "*" ab "†*"
            printf "| %s | %s MB/s | %s |\n", label, fmt(a), ab
        }'
done
echo
