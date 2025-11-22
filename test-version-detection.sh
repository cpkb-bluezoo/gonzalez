#!/bin/bash
cd /Users/cburdess@mimecast.com/cpkb-bluezoo/gonzalez

# Show hexdump of test 012 to see where DEL character is
echo "=== Hexdump of test 012.xml ==="
od -A x -t x1z xmlconf/eduni/xml-1.1/012.xml

echo ""
echo "=== Hexdump of test 022.xml (NEL) ==="
od -A x -t x1z xmlconf/eduni/xml-1.1/022.xml

echo ""
echo "=== Hexdump of test 010.xml (C1) ==="
od -A x -t x1z xmlconf/eduni/xml-1.1/010.xml

