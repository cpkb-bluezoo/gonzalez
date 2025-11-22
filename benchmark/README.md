# Gonzalez Parser Benchmarks

This directory contains JMH (Java Microbenchmark Harness) benchmarks for comparing
Gonzalez parser performance against the default Java SAX parser.

## Structure

```
benchmark/
├── src/                           # Benchmark source code
│   └── org/bluezoo/gonzalez/benchmark/
│       └── XMLParserBenchmark.java
├── resources/                     # Test XML files
│   ├── small.xml                  # Small test file (~1KB)
│   └── large.xml                  # Large test file (~1MB)
└── build/                         # Compiled benchmark classes
```

## Running Benchmarks

### Quick Start

```bash
# Download JMH libraries (only needed once)
ant download-jmh

# Compile and run benchmarks
ant benchmark
```

### What Gets Tested

The benchmark compares three approaches:

1. **Java SAX Parser** - Default javax.xml.parsers.SAXParser
2. **Gonzalez ByteBuffer** - Direct ByteBuffer.wrap() with pre-loaded bytes
3. **Gonzalez FileChannel** - Streaming via FileChannel with 8KB buffer

Each approach is tested with:
- **Small file** (~1KB): Simple document with nested elements
- **Large file** (~1MB): Catalog with 1000 book entries

### Benchmark Configuration

- **Mode**: Average time per operation
- **Time Unit**: Microseconds
- **Warmup**: 5 iterations, 1 second each
- **Measurement**: 10 iterations, 1 second each
- **Forks**: 2 (for statistical reliability)
- **JVM Args**: -Xms2G -Xmx2G

### Understanding Results

JMH will output results like:

```
Benchmark                                           Mode  Cnt    Score    Error  Units
XMLParserBenchmark.smallFile_JavaSAX                avgt   20   45.678 ±  2.345  us/op
XMLParserBenchmark.smallFile_GonzalezByteBuffer     avgt   20   38.901 ±  1.234  us/op
XMLParserBenchmark.smallFile_GonzalezFileChannel    avgt   20   42.345 ±  1.678  us/op
```

- **Score**: Average time per operation (lower is better)
- **Error**: 99.9% confidence interval
- **Units**: Microseconds (us/op)

### Advanced Usage

Run specific benchmarks:
```bash
# Run only small file benchmarks
java -cp benchmark/build:build:lib/jmh-core-1.37.jar:lib/jmh-generator-annprocess-1.37.jar \
  org.openjdk.jmh.Main ".*smallFile.*"

# Run only Gonzalez benchmarks
java -cp benchmark/build:build:lib/jmh-core-1.37.jar:lib/jmh-generator-annprocess-1.37.jar \
  org.openjdk.jmh.Main ".*Gonzalez.*"
```

Generate JSON results for analysis:
```bash
java -cp benchmark/build:build:lib/jmh-core-1.37.jar:lib/jmh-generator-annprocess-1.37.jar \
  org.openjdk.jmh.Main -rf json -rff results.json
```

### Custom Test Files

To test with your own XML files:

1. Place XML files in `benchmark/resources/`
2. Modify `XMLParserBenchmark.java` to reference your files
3. Rebuild: `ant build-benchmark`
4. Run: `ant benchmark`

## Cleaning Up

```bash
# Clean benchmark build artifacts
ant clean-benchmark

# Clean everything including JMH libraries
ant clean
rm lib/jmh-*.jar
```

## Requirements

- Java 8 or later
- Apache Ant
- Internet connection (for first-time JMH download)

## Notes

- Benchmarks run with an empty `DefaultHandler` to measure pure parsing speed
- No DTD validation is performed (documents don't have DOCTYPE declarations)
- File I/O time is included in FileChannel benchmarks
- ByteBuffer benchmarks pre-load files to isolate parsing performance

