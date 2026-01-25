#!/bin/bash
#
# extract-xslt-tests.sh
#
# Extracts XSLT 1.0, 2.0, and 3.0 tests from the W3C XSLT 3.0 test suite
# into the xsltconf directory for use with Gonzalez XSLT conformance testing.
#
# Also extracts XPath 2.0 and 3.1 tests that don't obviously depend on XSD types.
#
# Usage: ./tools/extract-xslt-tests.sh /path/to/xslt30-test [/path/to/qt3tests]
#
# Copyright (C) 2026 Chris Burdess
# Licensed under LGPL v3+

set -e

if [ -z "$1" ]; then
    echo "Usage: $0 /path/to/xslt30-test [/path/to/qt3tests]"
    echo ""
    echo "Arguments:"
    echo "  xslt30-test  - Path to W3C XSLT 3.0 test suite (required)"
    echo "  qt3tests     - Path to W3C XPath/XQuery 3.1 test suite (optional)"
    echo ""
    echo "Example:"
    echo "  $0 ../xslt30-test ../qt3tests"
    exit 1
fi

XSLT_SOURCE_DIR="$1"
XPATH_SOURCE_DIR="${2:-}"
TARGET_DIR="xsltconf"

if [ ! -f "$XSLT_SOURCE_DIR/catalog.xml" ]; then
    echo "Error: Cannot find catalog.xml in $XSLT_SOURCE_DIR"
    echo "Make sure the path points to the xslt30-test repository root"
    exit 1
fi

echo "=============================================="
echo "Gonzalez XSLT/XPath Test Suite Extractor"
echo "=============================================="
echo ""
echo "XSLT source: $XSLT_SOURCE_DIR"
[ -n "$XPATH_SOURCE_DIR" ] && echo "XPath source: $XPATH_SOURCE_DIR"
echo "Target: $TARGET_DIR"
echo ""

# Create target directory structure
rm -rf "$TARGET_DIR"
mkdir -p "$TARGET_DIR/tests"
mkdir -p "$TARGET_DIR/xpath"
mkdir -p "$TARGET_DIR/admin"

# Copy the schema and admin files
cp "$XSLT_SOURCE_DIR/admin/catalog-schema.xsd" "$TARGET_DIR/admin/" 2>/dev/null || true
cp "$XSLT_SOURCE_DIR/admin/xml.xsd" "$TARGET_DIR/admin/" 2>/dev/null || true

# ============================================
# XSLT Test Extraction
# ============================================

echo "Phase 1: Extracting XSLT tests..."
echo ""

# Arrays to track what we copy
declare -a XSLT10_FILES
declare -a XSLT20_FILES
declare -a XSLT30_FILES

# Patterns that indicate XSD type dependency (to exclude)
XSD_PATTERNS='schema-aware|xs:integer|xs:decimal|xs:date|xs:time|xs:duration|xs:QName|typed-value|schema-element|schema-attribute|validate|xsl:import-schema'

# Find XSLT 1.0 test sets
echo "  Scanning for XSLT 1.0 test sets..."
while IFS= read -r file; do
    XSLT10_FILES+=("$file")
done < <(grep -l 'spec value="XSLT10' "$XSLT_SOURCE_DIR/tests"/*/*/*-test-set.xml 2>/dev/null || true)

# Find XSLT 2.0 test sets (excluding schema-aware tests)
echo "  Scanning for XSLT 2.0 test sets..."
while IFS= read -r file; do
    # Skip if it contains obvious schema-aware patterns
    if ! grep -qE "$XSD_PATTERNS" "$file" 2>/dev/null; then
        XSLT20_FILES+=("$file")
    fi
done < <(grep -l 'spec value="XSLT20' "$XSLT_SOURCE_DIR/tests"/*/*/*-test-set.xml 2>/dev/null | grep -v 'XSLT10' || true)

# Find XSLT 3.0 test sets (excluding schema-aware tests)
echo "  Scanning for XSLT 3.0 test sets..."
while IFS= read -r file; do
    # Skip if it contains obvious schema-aware patterns
    if ! grep -qE "$XSD_PATTERNS" "$file" 2>/dev/null; then
        XSLT30_FILES+=("$file")
    fi
done < <(grep -l 'spec value="XSLT30' "$XSLT_SOURCE_DIR/tests"/*/*/*-test-set.xml 2>/dev/null | grep -v 'XSLT10\|XSLT20' || true)

echo ""
echo "  Found test sets:"
echo "    XSLT 1.0: ${#XSLT10_FILES[@]}"
echo "    XSLT 2.0: ${#XSLT20_FILES[@]} (non-schema-aware)"
echo "    XSLT 3.0: ${#XSLT30_FILES[@]} (non-schema-aware)"
echo ""

# Function to copy a test set and its resources
copy_test_set() {
    local test_set_file="$1"
    local source_dir="$2"
    local target_base="$3"
    
    # Get relative path from source dir
    local rel_path="${test_set_file#$source_dir/}"
    local dir_path=$(dirname "$rel_path")
    
    # Create target directory
    mkdir -p "$target_base/$dir_path"
    
    # Copy the test-set XML file
    cp "$test_set_file" "$target_base/$rel_path"
    
    # Copy all files in the same directory
    local src_dir=$(dirname "$test_set_file")
    if [ -d "$src_dir" ]; then
        find "$src_dir" -maxdepth 1 -type f \( -name "*.xsl" -o -name "*.xml" -o -name "*.out" -o -name "*.txt" -o -name "*.dtd" \) ! -name "_*-test-set.xml" -exec cp {} "$target_base/$dir_path/" \; 2>/dev/null || true
        
        # Copy subdirectories with test resources
        for subdir in "$src_dir"/*/; do
            if [ -d "$subdir" ]; then
                local subdir_name=$(basename "$subdir")
                if [ "$subdir_name" != "." ] && [ "$subdir_name" != ".." ]; then
                    cp -r "$subdir" "$target_base/$dir_path/" 2>/dev/null || true
                fi
            fi
        done
    fi
    
    echo "$rel_path"
}

# Copy all XSLT test sets
echo "Copying XSLT 1.0 test sets..."
XSLT10_COUNT=0
for file in "${XSLT10_FILES[@]}"; do
    copy_test_set "$file" "$XSLT_SOURCE_DIR" "$TARGET_DIR" > /dev/null
    ((XSLT10_COUNT++))
done
echo "  Copied $XSLT10_COUNT test sets"

echo "Copying XSLT 2.0 test sets..."
XSLT20_COUNT=0
for file in "${XSLT20_FILES[@]}"; do
    copy_test_set "$file" "$XSLT_SOURCE_DIR" "$TARGET_DIR" > /dev/null
    ((XSLT20_COUNT++))
done
echo "  Copied $XSLT20_COUNT test sets"

echo "Copying XSLT 3.0 test sets..."
XSLT30_COUNT=0
for file in "${XSLT30_FILES[@]}"; do
    copy_test_set "$file" "$XSLT_SOURCE_DIR" "$TARGET_DIR" > /dev/null
    ((XSLT30_COUNT++))
done
echo "  Copied $XSLT30_COUNT test sets"

# ============================================
# XPath Test Extraction (if qt3tests provided)
# ============================================

XPATH_COUNT=0
if [ -n "$XPATH_SOURCE_DIR" ] && [ -d "$XPATH_SOURCE_DIR" ]; then
    echo ""
    echo "Phase 2: Extracting XPath tests..."
    echo ""
    
    # XPath test categories that don't require schema types
    XPATH_CATEGORIES=(
        "fn"           # Core functions
        "op"           # Operators
        "math"         # Math functions
        "map"          # Map functions (we support basic maps in expressions)
        "array"        # Array functions
        "prod"         # Production tests
        "misc"         # Miscellaneous
    )
    
    # Categories to skip (heavily schema-dependent)
    SKIP_CATEGORIES="xs|SchemaImport|SchemaValidation"
    
    for category in "${XPATH_CATEGORIES[@]}"; do
        if [ -d "$XPATH_SOURCE_DIR/$category" ]; then
            echo "  Processing XPath category: $category"
            
            # Find test catalogs in this category
            find "$XPATH_SOURCE_DIR/$category" -name "*.xml" -type f | while read -r catalog_file; do
                # Skip files with schema-dependent patterns
                if ! grep -qE "$XSD_PATTERNS|$SKIP_CATEGORIES" "$catalog_file" 2>/dev/null; then
                    rel_path="${catalog_file#$XPATH_SOURCE_DIR/}"
                    dir_path=$(dirname "$rel_path")
                    
                    mkdir -p "$TARGET_DIR/xpath/$dir_path"
                    cp "$catalog_file" "$TARGET_DIR/xpath/$rel_path"
                    ((XPATH_COUNT++)) || true
                fi
            done
        fi
    done
    
    echo "  Copied approximately $XPATH_COUNT XPath test files"
fi

# ============================================
# Create Catalog Files
# ============================================

echo ""
echo "Creating catalog files..."

# XSLT catalog
cat > "$TARGET_DIR/catalog.xml" << 'CATALOG_HEADER'
<?xml version="1.0" encoding="UTF-8"?>
<!--
  XSLT Conformance Test Catalog
  
  Extracted from W3C XSLT 3.0 Test Suite
  Contains XSLT 1.0, 2.0, and 3.0 tests (non-schema-aware)
  
  See: https://github.com/w3c/xslt30-test
-->
<catalog xmlns="http://www.w3.org/2012/10/xslt-test-catalog">
    
    <!-- XSLT 1.0 Tests -->
CATALOG_HEADER

# Add XSLT 1.0 test-set references
for file in "${XSLT10_FILES[@]}"; do
    rel_path="${file#$XSLT_SOURCE_DIR/}"
    name=$(basename "$rel_path" | sed 's/_\(.*\)-test-set\.xml/\1/')
    echo "    <test-set name=\"xslt10-$name\" file=\"$rel_path\" spec=\"XSLT10\"/>" >> "$TARGET_DIR/catalog.xml"
done

echo "" >> "$TARGET_DIR/catalog.xml"
echo "    <!-- XSLT 2.0 Tests (non-schema-aware) -->" >> "$TARGET_DIR/catalog.xml"

# Add XSLT 2.0 test-set references
for file in "${XSLT20_FILES[@]}"; do
    rel_path="${file#$XSLT_SOURCE_DIR/}"
    name=$(basename "$rel_path" | sed 's/_\(.*\)-test-set\.xml/\1/')
    echo "    <test-set name=\"xslt20-$name\" file=\"$rel_path\" spec=\"XSLT20\"/>" >> "$TARGET_DIR/catalog.xml"
done

echo "" >> "$TARGET_DIR/catalog.xml"
echo "    <!-- XSLT 3.0 Tests (non-schema-aware) -->" >> "$TARGET_DIR/catalog.xml"

# Add XSLT 3.0 test-set references
for file in "${XSLT30_FILES[@]}"; do
    rel_path="${file#$XSLT_SOURCE_DIR/}"
    name=$(basename "$rel_path" | sed 's/_\(.*\)-test-set\.xml/\1/')
    echo "    <test-set name=\"xslt30-$name\" file=\"$rel_path\" spec=\"XSLT30\"/>" >> "$TARGET_DIR/catalog.xml"
done

echo "</catalog>" >> "$TARGET_DIR/catalog.xml"

# ============================================
# Create README
# ============================================

cat > "$TARGET_DIR/README.md" << README
# XSLT/XPath Conformance Test Suite

This directory contains XSLT and XPath tests extracted from the W3C test suites
for use with Gonzalez XSLT conformance testing.

## Contents

- \`catalog.xml\` - Main XSLT test catalog
- \`tests/\` - XSLT test files organized by category
- \`xpath/\` - XPath test files (if qt3tests was provided)
- \`admin/\` - Schema files for the catalog format

## Test Selection

### XSLT Tests

Tests are organized by XSLT version:

| Version | Test Sets | Description |
|---------|-----------|-------------|
| XSLT 1.0 | ${#XSLT10_FILES[@]} | Full XSLT 1.0 specification |
| XSLT 2.0 | ${#XSLT20_FILES[@]} | Non-schema-aware XSLT 2.0 |
| XSLT 3.0 | ${#XSLT30_FILES[@]} | Non-schema-aware XSLT 3.0 |

### Exclusions

The following test categories are excluded as they require XML Schema support:

- Schema-aware processing (\`xsl:import-schema\`)
- Typed values (\`xs:integer\`, \`xs:date\`, etc.)
- Schema validation
- \`schema-element()\` / \`schema-attribute()\` tests

### XPath Tests

If the qt3tests repository was provided, XPath 2.0/3.1 tests are included
in the \`xpath/\` directory, excluding schema-dependent tests.

## Running Tests

\`\`\`bash
# Compile the project
ant compile

# Run XSLT conformance tests
ant test-xslt

# Run with specific version filter
ant test-xslt -Dxslt.version=1.0
ant test-xslt -Dxslt.version=2.0
ant test-xslt -Dxslt.version=3.0
\`\`\`

## Regenerating

To regenerate this directory:

\`\`\`bash
# XSLT tests only
./tools/extract-xslt-tests.sh /path/to/xslt30-test

# XSLT and XPath tests
./tools/extract-xslt-tests.sh /path/to/xslt30-test /path/to/qt3tests
\`\`\`

## Sources

- XSLT 3.0 Test Suite: https://github.com/w3c/xslt30-test
- XPath/XQuery 3.1 Test Suite: https://github.com/w3c/qt3tests

## License

The W3C test suites are available under the
[W3C Software License](https://www.w3.org/Consortium/Legal/2015/copyright-software-and-document).
README

# ============================================
# Summary
# ============================================

echo ""
echo "=============================================="
echo "Extraction Complete"
echo "=============================================="
echo ""
echo "XSLT Test Sets:"
echo "  XSLT 1.0: ${#XSLT10_FILES[@]}"
echo "  XSLT 2.0: ${#XSLT20_FILES[@]} (non-schema-aware)"
echo "  XSLT 3.0: ${#XSLT30_FILES[@]} (non-schema-aware)"
echo "  Total:    $((${#XSLT10_FILES[@]} + ${#XSLT20_FILES[@]} + ${#XSLT30_FILES[@]}))"
echo ""
if [ -n "$XPATH_SOURCE_DIR" ]; then
    echo "XPath Tests: $XPATH_COUNT files"
    echo ""
fi
echo "Output: $TARGET_DIR/"
echo ""
echo "Run tests with: ant test-xslt"
