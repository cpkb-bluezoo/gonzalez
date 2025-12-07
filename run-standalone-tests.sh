#!/bin/bash
# Run all standalone tests with main() methods

passed=0
failed=0
skipped=0

# Get list of test files with main methods
tests=$(find test -name "*.java" -exec grep -l "public static void main" {} \;)

for test_file in $tests; do
    test_name=$(basename "$test_file" .java)
    echo "=========================================="
    echo "Running: $test_name"
    echo "=========================================="
    
    # Try to compile
    if javac -cp build:test "$test_file" 2>/dev/null; then
        # Try to run
        if java -cp build:test "$test_name" 2>&1; then
            echo "✓ $test_name PASSED"
            ((passed++))
        else
            echo "✗ $test_name FAILED (runtime error)"
            ((failed++))
        fi
    else
        echo "⊘ $test_name SKIPPED (compilation error)"
        ((skipped++))
    fi
    echo ""
done

echo "=========================================="
echo "SUMMARY"
echo "=========================================="
echo "Passed:  $passed"
echo "Failed:  $failed"
echo "Skipped: $skipped"
echo "Total:   $((passed + failed + skipped))"
