#!/bin/bash
cd /Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez

# Create a simple XML 1.0 file with NEL character (0x85)
printf '<?xml version="1.0" encoding="iso-8859-1"?>\n<root>X\x85Y</root>\n' > test-nel-simple.xml

echo "=== Hex dump of test file ==="
od -A x -t x1z test-nel-simple.xml

echo ""
echo "=== Running parser on test file ==="
java -cp build:test/junit/classes org.bluezoo.gonzalez.SimpleTest test-nel-simple.xml 2>&1 || echo "Parser failed"

