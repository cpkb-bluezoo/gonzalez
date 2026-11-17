#!/bin/bash
# Runs the comparison with the JDK's parser and aalto-xml and prints the tables
# of PERFORMANCE.md, as Markdown, to standard output, or to the file named by
# the second argument. Progress goes to standard error. The usual way to run it
# is "ant benchmark-compare", which fetches what it needs first.
#
#   benchmark/external-compare/performance-tables.sh [reps [report-file]]
#
# reps is how many fresh JVMs each figure is the median of; the default is 5,
# which is what the figures in PERFORMANCE.md are. Set AALTO_JAR and
# STAX2_JAR to the dependency jars, as for run-isolated.sh, and
# GONZALEZ_CLASSES to measure a build other than build/core. To try it out
# more quickly, CORPORA="plain accent" runs only the corpora named. The French
# and German novels of benchmark/corpora (ant download-corpora) are measured,
# as well as the others, when they are there.
#
# BENCH_REF, if set, is how the commit is described in the heading, for a run
# in which git does not know it as a branch.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

REPS="${1:-5}"
if [ -n "${2:-}" ]; then
    exec > "$2"
fi
RUN=benchmark/external-compare/run-isolated.sh
# The two real documents, a French and a German novel, are not kept with the
# code. They are measured too when they have been downloaded (ant
# download-corpora puts them in benchmark/corpora), or are named by FRENCH_XML
# and GERMAN_XML.
if [ -z "${CORPORA:-}" ]; then
    CORPORA="plain attrs whitespace markup multibyte accent latin-light latin odd-word emoji mathml utf16 euc-jp xhtml-dtd"
    if [ -f "${FRENCH_XML:-benchmark/corpora/FRA03601_Proust.xml}" ] \
            && [ -f "${GERMAN_XML:-benchmark/corpora/DEU037.xml}" ]; then
        CORPORA="$CORPORA french german"
    fi
fi

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
        latin-light) echo "Accent on every second e (German-like)" ;;
        latin) echo "Accent on every e (French-like)" ;;
        odd-word) echo "English with the odd foreign word" ;;
        emoji) echo "English with emoji" ;;
        mathml) echo "MathML (generated)" ;;
        french) echo "French novel (ELTeC)" ;;
        german) echo "German novel (ELTeC)" ;;
        utf16) echo "Japanese UTF-16LE" ;;
        euc-jp) echo "Japanese EUC-JP" ;;
        xhtml-dtd) echo "XHTML with an internal DTD subset" ;;
        *) echo "$1" ;;
    esac
}

# mib <bytes>
mib() { awk -v b="$1" 'BEGIN { printf "%.2f MiB", b / 1048576 }'; }

# ---- which corpora ---------------------------------------------------------

# The document with an internal DTD subset is not measured against Aalto, which
# is not a validating parser and does not process the subset (see the note
# under the SAX table), nor in the native table, in which Xerces, the other
# parser that does process it, has no counterpart. It is in the SAX table, for
# Gonzalez and Xerces.
DTD=xhtml-dtd
OTHERS=""
HAS_DTD=no
for c in $CORPORA; do
    if [ "$c" = "$DTD" ]; then
        HAS_DTD=yes
    else
        OTHERS="$OTHERS $c"
    fi
done
OTHERS="${OTHERS# }"

# cells for the corpora other than the DTD one, of which there may be none (and
# an empty list would be taken by run-isolated.sh to mean all of them)
others_cells() {
    if [ -n "$OTHERS" ]; then
        cells "$OTHERS" "$1"
    fi
}

# ---- the native interfaces -----------------------------------------------

log "Native interfaces..."
echo "### Native interfaces"
echo
echo "Gonzalez's \`XMLHandler\` against Aalto's StAX cursor, each the parser's own"
echo "interface and the most direct. The consumer of each reads what it is given:"
echo "every character of text, comments, processing instruction data and attribute"
echo "values, and the length of every name. Throughput is of the encoded document."
echo
echo "| Corpus | Size | Gonzalez XMLHandler | Aalto StAX cursor | Gonzalez / Aalto |"
echo "|--------|-----:|--------------------:|------------------:|-----------------:|"
others_cells "xmlhandler-sum aalto-stax-sum" | while read -r corpus a b; do
    bytes="$(size "$corpus")"
    awk -v label="$(name_of "$corpus")" -v size="$(mib "$bytes")" \
        -v a="$a" -v b="$b" "$awk_fmt"'
        BEGIN {
            printf "| %s | %s | %s MB/s | %s MB/s | %.2f |\n", label, size, fmt(a), fmt(b), a / b
        }'
done
echo

# ---- SAX ------------------------------------------------------------------

log "SAX..."
echo "### SAX"
echo
echo "The same documents through SAX, with a handler that reads every attribute and"
echo "all the text. (An empty handler does less work with Aalto, which decodes an"
echo "attribute value only when asked.)"
echo
echo "| Corpus | Gonzalez SAX | JDK Xerces SAX | Aalto SAX | Gonzalez / Aalto |"
echo "|--------|-------------:|---------------:|----------:|-----------------:|"
others_cells "sax-touch jdk-touch aalto-sax-touch" | while read -r corpus a b c; do
    awk -v label="$(name_of "$corpus")" -v a="$a" -v b="$b" -v c="$c" "$awk_fmt"'
        BEGIN {
            printf "| %s | %s MB/s | %s MB/s | %s MB/s | %.2f |\n", label, fmt(a), fmt(b), fmt(c), a / c
        }'
done
if [ "$HAS_DTD" = yes ]; then
    cells "$DTD" "sax-touch jdk-touch" | while read -r corpus a b; do
        awk -v label="$(name_of "$corpus")" -v a="$a" -v b="$b" "$awk_fmt"'
            BEGIN {
                printf "| %s | %s MB/s | %s MB/s | *not compared†* | |\n", label, fmt(a), fmt(b)
            }'
    done
    echo
    echo "† Aalto is not compared on this document. It is not a validating parser and"
    echo "does not process a DTD subset: it recognises the DOCTYPE but skips the"
    echo "internal declarations, so it neither applies declared attribute types and"
    echo "defaults nor expands the declared entities, as Gonzalez and Xerces do, and"
    echo "its throughput would be for less work."
fi
echo
