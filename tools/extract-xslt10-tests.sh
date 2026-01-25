#!/bin/bash
#
# extract-xslt10-tests.sh
#
# DEPRECATED: Use extract-xslt-tests.sh instead for XSLT 1.0/2.0/3.0 support.
#
# This script extracts only XSLT 1.0 compatible tests from the W3C XSLT 3.0 
# test suite. For comprehensive XSLT 2.0/3.0 and XPath testing, use:
#
#   ./tools/extract-xslt-tests.sh /path/to/xslt30-test [/path/to/qt3tests]
#
# Usage: ./tools/extract-xslt10-tests.sh /path/to/xslt30-test
#
# Copyright (C) 2026 Chris Burdess
# Licensed under LGPL v3+

set -e

echo "NOTE: Consider using extract-xslt-tests.sh for XSLT 2.0/3.0 support."
echo ""

if [ -z "$1" ]; then
    echo "Usage: $0 /path/to/xslt30-test"
    echo "Example: $0 ../xslt30-test"
    exit 1
fi

SOURCE_DIR="$1"
TARGET_DIR="xsltconf"

if [ ! -f "$SOURCE_DIR/catalog.xml" ]; then
    echo "Error: Cannot find catalog.xml in $SOURCE_DIR"
    echo "Make sure the path points to the xslt30-test repository root"
    exit 1
fi

echo "Extracting XSLT 1.0 compatible tests from $SOURCE_DIR"
echo "Target directory: $TARGET_DIR"
echo ""

# Create target directory structure
rm -rf "$TARGET_DIR"
mkdir -p "$TARGET_DIR/tests"

# Copy the schema and admin files needed for understanding the catalog
mkdir -p "$TARGET_DIR/admin"
cp "$SOURCE_DIR/admin/catalog-schema.xsd" "$TARGET_DIR/admin/" 2>/dev/null || true
cp "$SOURCE_DIR/admin/xml.xsd" "$TARGET_DIR/admin/" 2>/dev/null || true

# Find all test-set XML files that contain XSLT10+ tests
echo "Scanning for XSLT 1.0 compatible test sets..."

# Create arrays to track what we need to copy
declare -a TEST_SET_FILES

# Find test-set files with XSLT10+ or XSLT10 tests
while IFS= read -r file; do
    TEST_SET_FILES+=("$file")
done < <(grep -l 'spec value="XSLT10' "$SOURCE_DIR/tests"/*/*/*-test-set.xml 2>/dev/null || true)

echo "Found ${#TEST_SET_FILES[@]} test sets with XSLT 1.0 compatible tests"
echo ""

# Process each test set
TOTAL_TESTS=0
for test_set_file in "${TEST_SET_FILES[@]}"; do
    # Get relative path from source dir
    rel_path="${test_set_file#$SOURCE_DIR/}"
    dir_path=$(dirname "$rel_path")
    
    # Create target directory
    mkdir -p "$TARGET_DIR/$dir_path"
    
    # Copy the test-set XML file
    cp "$test_set_file" "$TARGET_DIR/$rel_path"
    
    # Count XSLT 1.0 tests in this file
    count=$(grep -c 'spec value="XSLT10' "$test_set_file" 2>/dev/null || echo "0")
    TOTAL_TESTS=$((TOTAL_TESTS + count))
    
    # Copy all files in the same directory (stylesheets, XML sources, expected outputs)
    src_dir=$(dirname "$test_set_file")
    if [ -d "$src_dir" ]; then
        # Copy all non-test-set XML files and XSL files
        find "$src_dir" -maxdepth 1 -type f \( -name "*.xsl" -o -name "*.xml" -o -name "*.out" -o -name "*.txt" -o -name "*.dtd" -o -name "*.xsd" \) ! -name "_*-test-set.xml" -exec cp {} "$TARGET_DIR/$dir_path/" \; 2>/dev/null || true
        
        # Also copy subdirectories that might contain additional test resources
        for subdir in "$src_dir"/*/; do
            if [ -d "$subdir" ]; then
                subdir_name=$(basename "$subdir")
                if [ "$subdir_name" != "." ] && [ "$subdir_name" != ".." ]; then
                    cp -r "$subdir" "$TARGET_DIR/$dir_path/" 2>/dev/null || true
                fi
            fi
        done
    fi
    
    echo "  Copied: $dir_path ($count XSLT10+ tests)"
done

echo ""
echo "Creating filtered catalog.xml..."

# Create the catalog.xml file
cat > "$TARGET_DIR/catalog.xml" << 'CATALOG_HEADER'
<?xml version="1.0" encoding="UTF-8"?>
<!--
  XSLT 1.0 Conformance Test Catalog
  
  Extracted from W3C XSLT 3.0 Test Suite
  Contains only tests compatible with XSLT 1.0 processors
  
  See: https://github.com/w3c/xslt30-test
-->
<catalog xmlns="http://www.w3.org/2012/10/xslt-test-catalog">
CATALOG_HEADER

# Add test-set references
for test_set_file in "${TEST_SET_FILES[@]}"; do
    rel_path="${test_set_file#$SOURCE_DIR/}"
    name=$(basename "$rel_path" | sed 's/_\(.*\)-test-set\.xml/\1/')
    echo "    <test-set name=\"$name\" file=\"$rel_path\"/>" >> "$TARGET_DIR/catalog.xml"
done

echo "</catalog>" >> "$TARGET_DIR/catalog.xml"

echo ""
echo "Creating README.md..."

cat > "$TARGET_DIR/README.md" << README
# XSLT 1.0 Conformance Test Suite

This directory contains XSLT 1.0 compatible tests extracted from the
[W3C XSLT 3.0 Test Suite](https://github.com/w3c/xslt30-test).

## Contents

- \`catalog.xml\` - Main catalog file listing all test sets
- \`tests/\` - Test files organized by category
- \`admin/\` - Schema files for the catalog format

## Test Selection Criteria

Tests are included if they have:
- \`<spec value="XSLT10+"/>\` - Compatible with XSLT 1.0 and later
- \`<spec value="XSLT10"/>\` - XSLT 1.0 only
- \`<spec value="XSLT10 XSLT20"/>\` - Compatible with XSLT 1.0 and 2.0

## Statistics

- Total test sets: ${#TEST_SET_FILES[@]}
- Total XSLT 1.0 compatible tests: approximately $TOTAL_TESTS

## License

The W3C XSLT 3.0 Test Suite is available under the
[W3C Software License](https://www.w3.org/Consortium/Legal/2015/copyright-software-and-document).

## Regenerating

To regenerate this directory from a fresh copy of xslt30-test:

\`\`\`bash
./tools/extract-xslt10-tests.sh /path/to/xslt30-test
\`\`\`
README

echo ""
echo "=== Extraction Complete ==="
echo "Test sets: ${#TEST_SET_FILES[@]}"
echo "XSLT 1.0 tests: approximately $TOTAL_TESTS"
echo "Output directory: $TARGET_DIR"
echo ""
echo "To run conformance tests:"
echo "  ant test-xslt"
