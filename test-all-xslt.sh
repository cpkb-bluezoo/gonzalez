#!/bin/bash
# Run all XSLT conformance tests and show comprehensive summary
#
# Usage: ./test-all-xslt.sh [options]
#   -q, --quiet     Only show summary, not individual test output
#   -1, --xslt10    Run only XSLT 1.0 tests
#   -2, --xslt20    Run only XSLT 2.0 tests  
#   -3, --xslt30    Run only XSLT 3.0 tests
#   -h, --help      Show this help

set -e

QUIET=false
RUN_10=true
RUN_20=true
RUN_30=true

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -q|--quiet)
            QUIET=true
            shift
            ;;
        -1|--xslt10)
            RUN_10=true
            RUN_20=false
            RUN_30=false
            shift
            ;;
        -2|--xslt20)
            RUN_10=false
            RUN_20=true
            RUN_30=false
            shift
            ;;
        -3|--xslt30)
            RUN_10=false
            RUN_20=false
            RUN_30=true
            shift
            ;;
        -h|--help)
            head -12 "$0" | tail -9
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Check for xslt30-test repository
if [ ! -f "../xslt30-test/catalog.xml" ]; then
    echo "Error: xslt30-test repository not found at ../xslt30-test"
    echo "Clone it with: cd .. && git clone https://github.com/w3c/xslt30-test.git"
    exit 1
fi

# Create output directory
mkdir -p test/output

# Function to extract failure categories from report
extract_categories() {
    local report_file="$1"
    if [ -f "$report_file" ]; then
        grep "^\[FAIL\]" "$report_file" 2>/dev/null | \
            awk '{print $2}' | \
            sed 's/-[0-9]*[a-z]*$//' | \
            sort | uniq -c | sort -rn
    fi
}

# Function to run tests for a version
run_tests() {
    local version="$1"
    local target="test-xslt${version//./}"
    
    echo ""
    echo "========================================"
    echo "  Running XSLT $version tests..."
    echo "========================================"
    
    if $QUIET; then
        ant "$target" > /dev/null 2>&1 || true
    else
        ant "$target" 2>&1 | grep -E "(Loading|Found|Total|Tests run|FAILED)" || true
    fi
    
    # Save stats
    if [ -f "test/output/xslt-conformance-statistics.txt" ]; then
        cp "test/output/xslt-conformance-statistics.txt" "test/output/xslt${version//./}-statistics.txt"
    fi
    if [ -f "test/output/xslt-conformance-report.txt" ]; then
        cp "test/output/xslt-conformance-report.txt" "test/output/xslt${version//./}-report.txt"
    fi
}

# Function to show results for a version
show_results() {
    local version="$1"
    local stats_file="test/output/xslt${version//./}-statistics.txt"
    local report_file="test/output/xslt${version//./}-report.txt"
    
    echo ""
    echo "--- XSLT $version Results ---"
    
    if [ -f "$stats_file" ]; then
        grep -E "^(Total|Passed|Failed)" "$stats_file" | sed 's/^/  /'
    else
        echo "  No statistics available"
        return
    fi
    
    echo ""
    echo "  Failures by category:"
    categories=$(extract_categories "$report_file")
    if [ -n "$categories" ]; then
        echo "$categories" | head -20 | while read count name; do
            printf "    %3d %s\n" "$count" "$name"
        done
        total_cats=$(echo "$categories" | wc -l | tr -d ' ')
        if [ "$total_cats" -gt 20 ]; then
            echo "    ... and $((total_cats - 20)) more categories"
        fi
    else
        echo "    (none)"
    fi
}

# Build first
echo "Building..."
ant build > /dev/null 2>&1
ant junit-build > /dev/null 2>&1

# Run requested tests
if $RUN_10; then run_tests "1.0"; fi
if $RUN_20; then run_tests "2.0"; fi
if $RUN_30; then run_tests "3.0"; fi

# Show comprehensive summary
echo ""
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║           XSLT CONFORMANCE TEST SUMMARY                      ║"
echo "╚══════════════════════════════════════════════════════════════╝"

if $RUN_10; then show_results "1.0"; fi
if $RUN_20; then show_results "2.0"; fi
if $RUN_30; then show_results "3.0"; fi

# Show combined summary table
echo ""
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "                    SUMMARY TABLE"
echo "═══════════════════════════════════════════════════════════════"
printf "  %-10s %8s %8s %8s\n" "Version" "Tests" "Passed" "Rate"
echo "  ─────────────────────────────────────"

for version in "1.0" "2.0" "3.0"; do
    stats_file="test/output/xslt${version//./}-statistics.txt"
    if [ -f "$stats_file" ]; then
        total=$(grep "^Total:" "$stats_file" | awk '{print $2}')
        passed=$(grep "^Passed:" "$stats_file" | awk '{print $2}')
        rate=$(grep "^Passed:" "$stats_file" | grep -oE '[0-9]+\.[0-9]+%')
        printf "  XSLT %-4s %8s %8s %8s\n" "$version" "$total" "$passed" "$rate"
    fi
done

echo "═══════════════════════════════════════════════════════════════"

# Generate comprehensive markdown report for TODO-XSLT.md
generate_markdown_report() {
    local out="test/output/xslt-conformance-summary.md"
    
    echo "## Conformance" > "$out"
    echo "" >> "$out"
    echo "| Version | Tests | Passed | Rate |" >> "$out"
    echo "|---------|-------|--------|------|" >> "$out"
    
    for version in "1.0" "2.0" "3.0"; do
        stats_file="test/output/xslt${version//./}-statistics.txt"
        if [ -f "$stats_file" ]; then
            total=$(grep "^Total:" "$stats_file" | awk '{print $2}' | tr -d ',')
            passed=$(grep "^Passed:" "$stats_file" | awk '{print $2}' | tr -d ',')
            rate=$(grep "^Passed:" "$stats_file" | grep -oE '[0-9]+\.[0-9]+%')
            # Format with commas
            total_fmt=$(printf "%'d" "$total" 2>/dev/null || echo "$total")
            passed_fmt=$(printf "%'d" "$passed" 2>/dev/null || echo "$passed")
            echo "| XSLT $version | $total_fmt | $passed_fmt | $rate |" >> "$out"
        fi
    done
    
    echo "" >> "$out"
    echo "*Last updated: $(date +%Y-%m-%d)*" >> "$out"
    echo "" >> "$out"
    echo "### Failure Breakdown by Category" >> "$out"
    
    for version in "1.0" "2.0" "3.0"; do
        report_file="test/output/xslt${version//./}-report.txt"
        stats_file="test/output/xslt${version//./}-statistics.txt"
        
        if [ -f "$report_file" ] && [ -f "$stats_file" ]; then
            failed=$(grep "^Failed:" "$stats_file" | awk '{print $2}' | tr -d ',')
            echo "" >> "$out"
            echo "**XSLT $version** ($failed failures):" >> "$out"
            echo "\`\`\`" >> "$out"
            
            # Extract and format categories
            grep "^\[FAIL\]" "$report_file" 2>/dev/null | \
                awk '{print $2}' | \
                sed 's/-[0-9]*[a-z]*$//' | \
                sort | uniq -c | sort -rn | \
                head -20 | \
                awk '{printf "%4d %-16s", $1, $2; if (NR % 4 == 0) print ""; else printf " "}' >> "$out"
            
            echo "" >> "$out"
            echo "\`\`\`" >> "$out"
        fi
    done
    
    echo "" >> "$out"
    echo "---" >> "$out"
    echo "Generated by test-all-xslt.sh" >> "$out"
}

generate_markdown_report

echo ""
echo "Reports saved to:"
echo "  test/output/xslt{10,20,30}-{statistics,report}.txt"
echo "  test/output/xslt-conformance-summary.md  (copy to TODO-XSLT.md)"
